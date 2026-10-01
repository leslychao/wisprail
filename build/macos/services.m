#import <Foundation/Foundation.h>
#import <ServiceManagement/ServiceManagement.h>

// Called from the ordinary user's JavaFX process; macOS owns authorization UI.
static int set_service(SMAppService *service, int enabled) {
    @autoreleasepool {
        NSError *error = nil;
        if (enabled) {
            if (service.status == SMAppServiceStatusEnabled) return 0;
            if (![service registerAndReturnError:&error]) return 1;
        } else if (![service unregisterAndReturnError:&error]) {
            return 1;
        }
        if (enabled && service.status == SMAppServiceStatusRequiresApproval) {
            [SMAppService openSystemSettingsLoginItems];
            return 2;
        }
        return 0;
    }
}

int wisprail_login(int enabled) {
    return set_service([SMAppService mainAppService], enabled);
}

int wisprail_service(int enabled) {
    return set_service([SMAppService daemonServiceWithPlistName:@"app.wisprail.agent.plist"], enabled);
}
