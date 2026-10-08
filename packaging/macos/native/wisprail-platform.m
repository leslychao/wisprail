#import <Foundation/Foundation.h>
#import <Security/Security.h>
#import <ServiceManagement/ServiceManagement.h>
#import <SystemConfiguration/SystemConfiguration.h>
#include "installation-trust.h"
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/acl.h>
#include <fts.h>
#include <limits.h>

static SCDynamicStoreRef dnsStore;

int wr_effective_uid(void) {
  return (int)geteuid();
}

static NSString *stringValue(const char *value) {
  return value == NULL ? nil : [NSString stringWithUTF8String:value];
}

static int copyData(NSData *data, void **output, size_t *length) {
  if (data == nil || data.length > 8 * 1024 * 1024) return -1;
  *length = data.length;
  *output = malloc(MAX(data.length, 1));
  if (*output == NULL) return -1;
  memcpy(*output, data.bytes, data.length);
  return 0;
}

void wr_free_secret(void *buffer, size_t length) {
  if (buffer != NULL) {
    memset_s(buffer, length, 0, length);
    free(buffer);
  }
}

int wr_keychain_read(const char *reference, void **output, size_t *length) {
  @autoreleasepool {
    NSDictionary *query = @{
      (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
      (__bridge id)kSecAttrService: @"app.wisprail",
      (__bridge id)kSecAttrAccount: stringValue(reference),
      (__bridge id)kSecReturnData: @YES,
      (__bridge id)kSecMatchLimit: (__bridge id)kSecMatchLimitOne
    };
    CFTypeRef result = NULL;
    OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &result);
    if (status != errSecSuccess) return (int)status;
    NSData *data = CFBridgingRelease(result);
    return copyData(data, output, length);
  }
}

int wr_keychain_write(const char *reference, const void *value, size_t length) {
  @autoreleasepool {
    if (length > 1024 * 1024) return -1;
    NSDictionary *query = @{
      (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
      (__bridge id)kSecAttrService: @"app.wisprail",
      (__bridge id)kSecAttrAccount: stringValue(reference)
    };
    NSData *data = [NSData dataWithBytes:value length:length];
    NSDictionary *attributes = @{(__bridge id)kSecValueData: data};
    OSStatus status = SecItemUpdate((__bridge CFDictionaryRef)query,
                                    (__bridge CFDictionaryRef)attributes);
    if (status == errSecItemNotFound) {
      NSMutableDictionary *item = [query mutableCopy];
      [item addEntriesFromDictionary:attributes];
      status = SecItemAdd((__bridge CFDictionaryRef)item, NULL);
    }
    return (int)status;
  }
}

int wr_keychain_delete(const char *reference) {
  @autoreleasepool {
    NSDictionary *query = @{
      (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
      (__bridge id)kSecAttrService: @"app.wisprail",
      (__bridge id)kSecAttrAccount: stringValue(reference)
    };
    OSStatus status = SecItemDelete((__bridge CFDictionaryRef)query);
    return status == errSecItemNotFound ? 0 : (int)status;
  }
}

static SCDynamicStoreRef store(void) {
  if (dnsStore == NULL) {
    dnsStore = SCDynamicStoreCreate(NULL, CFSTR("app.wisprail.agent"), NULL, NULL);
  }
  return dnsStore;
}

int wr_dns_add(const char *operation, const char *server,
               const char **domains, int domainCount) {
  @autoreleasepool {
    if (geteuid() != 0 || domainCount < 1 || domainCount > 4096) return -1;
    NSString *identifier = stringValue(operation);
    if ([[NSUUID alloc] initWithUUIDString:identifier] == nil) return -1;
    NSString *key = [NSString stringWithFormat:
        @"State:/Network/Service/app.wisprail.%@/DNS", identifier];
    NSMutableArray *suffixes = [NSMutableArray arrayWithCapacity:domainCount];
    for (int index = 0; index < domainCount; index++) {
      NSString *domain = stringValue(domains[index]);
      if (domain.length < 1 || domain.length > 253) return -1;
      [suffixes addObject:domain];
    }
    NSDictionary *value = @{
      @"ServerAddresses": @[stringValue(server)],
      @"SupplementalMatchDomains": suffixes,
      @"SupplementalMatchDomainsNoSearch": @1,
      @"WisprailOperation": identifier
    };
    if (store() == NULL) return SCError();
    Boolean added = SCDynamicStoreAddTemporaryValue(store(), (__bridge CFStringRef)key,
                                                    (__bridge CFDictionaryRef)value);
    return added ? 0 : SCError();
  }
}

int wr_dns_remove(const char *operation, const char *server,
                  const char **domains, int domainCount) {
  @autoreleasepool {
    NSString *identifier = stringValue(operation);
    if (geteuid() != 0 || [[NSUUID alloc] initWithUUIDString:identifier] == nil) return -1;
    NSString *key = [NSString stringWithFormat:
        @"State:/Network/Service/app.wisprail.%@/DNS", identifier];
    if (store() == NULL) return SCError();
    NSDictionary *existing = CFBridgingRelease(
        SCDynamicStoreCopyValue(store(), (__bridge CFStringRef)key));
    if (existing == nil) return SCError() == kSCStatusNoKey ? 0 : SCError();
    if (![existing isKindOfClass:[NSDictionary class]]) return -1;
    if (domainCount < 1 || domainCount > 4096) return -1;
    NSMutableArray *suffixes = [NSMutableArray arrayWithCapacity:domainCount];
    for (int index = 0; index < domainCount; index++) {
      [suffixes addObject:stringValue(domains[index])];
    }
    if (![existing[@"WisprailOperation"] isEqualToString:identifier]
        || ![existing[@"ServerAddresses"] isEqualToArray:@[stringValue(server)]]
        || ![existing[@"SupplementalMatchDomains"] isEqualToArray:suffixes]) return -1;
    return SCDynamicStoreRemoveValue(store(), (__bridge CFStringRef)key) ? 0 : SCError();
  }
}

int wr_dns_snapshot(void **output, size_t *length) {
  @autoreleasepool {
    if (store() == NULL) return SCError();
    NSArray *patterns = @[@"State:/Network/Service/.*/DNS", @"Setup:/Network/Service/.*/DNS"];
    NSDictionary *values = CFBridgingRelease(
        SCDynamicStoreCopyMultiple(store(), NULL, (__bridge CFArrayRef)patterns));
    if (values == nil) return SCError();
    NSError *error = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:values options:0 error:&error];
    return data == nil ? -1 : copyData(data, output, length);
  }
}

int wr_service_status(void) {
  @autoreleasepool {
    return (int)[SMAppService daemonServiceWithPlistName:@"app.wisprail.agent.plist"].status;
  }
}


int wr_service_register(void) {
  @autoreleasepool {
    if (!wr_trusted_installation(NSBundle.mainBundle.bundlePath.fileSystemRepresentation)) return -1;
    NSError *error = nil;
    BOOL success = [[SMAppService daemonServiceWithPlistName:@"app.wisprail.agent.plist"]
        registerAndReturnError:&error];
    return success ? 0 : (int)error.code;
  }
}

int wr_service_unregister(void) {
  @autoreleasepool {
    NSError *error = nil;
    BOOL success = [[SMAppService daemonServiceWithPlistName:@"app.wisprail.agent.plist"]
        unregisterAndReturnError:&error];
    return success ? 0 : (int)error.code;
  }
}

void wr_service_open_settings(void) {
  [SMAppService openSystemSettingsLoginItems];
}

int wr_autostart_status(void) {
  return (int)SMAppService.mainAppService.status;
}

int wr_autostart_set(int enabled) {
  @autoreleasepool {
    NSError *error = nil;
    BOOL success = enabled ? [SMAppService.mainAppService registerAndReturnError:&error]
                           : [SMAppService.mainAppService unregisterAndReturnError:&error];
    return success ? 0 : (int)error.code;
  }
}

int wr_console_user(void **output, size_t *length) {
  @autoreleasepool {
    uid_t user;
    gid_t group;
    NSString *name = CFBridgingRelease(SCDynamicStoreCopyConsoleUser(NULL, &user, &group));
    if (name == nil || user == 0 || [name isEqualToString:@"loginwindow"]) return -1;
    return copyData([name dataUsingEncoding:NSUTF8StringEncoding], output, length);
  }
}
