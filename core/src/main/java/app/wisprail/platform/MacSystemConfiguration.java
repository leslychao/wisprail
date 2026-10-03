package app.wisprail.platform;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A narrow binding for owned supplemental resolvers in configd's dynamic store. */
final class MacSystemConfiguration implements AutoCloseable {
  private static final int UTF8 = 0x08000100;
  private static final int STATUS_NO_KEY = 1004;
  private final CoreFoundation cf = Native.load("CoreFoundation", CoreFoundation.class);
  private final SystemConfiguration sc =
      Native.load("SystemConfiguration", SystemConfiguration.class);
  private final Pointer store;

  public interface CoreFoundation extends Library {
    Pointer CFStringCreateWithCString(Pointer allocator, String text, int encoding);

    byte CFStringGetCString(Pointer value, byte[] buffer, long size, int encoding);

    Pointer CFArrayCreate(Pointer allocator, Pointer[] values, long count, Pointer callbacks);

    long CFArrayGetCount(Pointer array);

    Pointer CFArrayGetValueAtIndex(Pointer array, long index);

    Pointer CFDictionaryCreateMutable(
        Pointer allocator, long capacity, Pointer keyCallbacks, Pointer valueCallbacks);

    void CFDictionarySetValue(Pointer dictionary, Pointer key, Pointer value);

    Pointer CFDictionaryGetValue(Pointer dictionary, Pointer key);

    void CFRelease(Pointer value);
  }

  public interface SystemConfiguration extends Library {
    Pointer SCDynamicStoreCreate(
        Pointer allocator, Pointer name, Pointer callback, Pointer context);

    byte SCDynamicStoreAddTemporaryValue(Pointer store, Pointer key, Pointer value);

    byte SCDynamicStoreRemoveValue(Pointer store, Pointer key);

    Pointer SCDynamicStoreCopyValue(Pointer store, Pointer key);

    Pointer SCDynamicStoreCopyKeyList(Pointer store, Pointer pattern);

    int SCError();
  }

  MacSystemConfiguration() throws IOException {
    Pointer name = string("Wisprail");
    try {
      store = sc.SCDynamicStoreCreate(null, name, null, null);
    } finally {
      cf.CFRelease(name);
    }
    if (store == null) {
      throw new IOException("Не удалось открыть SystemConfiguration");
    }
  }

  void install(String key, List<String> domains, String address) throws IOException {
    NativeLibrary library = NativeLibrary.getInstance("CoreFoundation");
    Pointer dictionary =
        cf.CFDictionaryCreateMutable(
            null,
            0,
            library.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
            library.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"));
    Pointer keyValue = string(key);
    try {
      putArray(dictionary, "ServerAddresses", List.of(address));
      putArray(dictionary, "SupplementalMatchDomains", domains);
      if (sc.SCDynamicStoreAddTemporaryValue(store, keyValue, dictionary) == 0) {
        throw new IOException("Не удалось добавить собственное DNS-правило macOS");
      }
    } finally {
      cf.CFRelease(keyValue);
      cf.CFRelease(dictionary);
    }
  }

  boolean exists(String key) throws IOException {
    Pointer keyValue = string(key);
    Pointer value = sc.SCDynamicStoreCopyValue(store, keyValue);
    cf.CFRelease(keyValue);
    if (value == null) {
      if (sc.SCError() != STATUS_NO_KEY) {
        throw new IOException("Не удалось проверить DNS-правило macOS");
      }
      return false;
    }
    cf.CFRelease(value);
    return true;
  }

  void remove(String key) throws IOException {
    if (!exists(key)) {
      return;
    }
    Pointer keyValue = string(key);
    try {
      if (sc.SCDynamicStoreRemoveValue(store, keyValue) == 0) {
        throw new IOException("Не удалось удалить собственное DNS-правило macOS");
      }
    } finally {
      cf.CFRelease(keyValue);
    }
  }

  List<NetworkPlatform.DnsRule> domains() throws IOException {
    List<NetworkPlatform.DnsRule> domains = new ArrayList<>();
    Pointer pattern = string("State:/Network/Service/.*/DNS");
    Pointer keys = sc.SCDynamicStoreCopyKeyList(store, pattern);
    cf.CFRelease(pattern);
    if (keys == null) {
      throw new IOException("Не удалось прочитать доменные политики macOS");
    }
    Pointer domainKey = string("SupplementalMatchDomains");
    try {
      for (long index = 0; index < cf.CFArrayGetCount(keys); index++) {
        Pointer key = cf.CFArrayGetValueAtIndex(keys, index);
        String keyName = text(key);
        UUID owner = null;
        String ownPrefix = "State:/Network/Service/app.wisprail.";
        if (keyName.startsWith(ownPrefix) && keyName.endsWith("/DNS")) {
          String identifier = keyName.substring(ownPrefix.length(), keyName.length() - 4);
          if (identifier.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            owner = UUID.fromString(identifier);
          }
        }
        Pointer value = sc.SCDynamicStoreCopyValue(store, key);
        if (value == null) {
          continue;
        }
        try {
          Pointer matches = cf.CFDictionaryGetValue(value, domainKey);
          if (matches != null) {
            for (long item = 0; item < cf.CFArrayGetCount(matches); item++) {
              domains.add(
                  new NetworkPlatform.DnsRule(
                      text(cf.CFArrayGetValueAtIndex(matches, item)), owner));
            }
          }
        } finally {
          cf.CFRelease(value);
        }
      }
      return List.copyOf(domains);
    } finally {
      cf.CFRelease(domainKey);
      cf.CFRelease(keys);
    }
  }

  private Pointer string(String value) {
    return cf.CFStringCreateWithCString(null, value, UTF8);
  }

  private String text(Pointer value) throws IOException {
    byte[] buffer = new byte[1024];
    if (cf.CFStringGetCString(value, buffer, buffer.length, UTF8) == 0) {
      throw new IOException("Недопустимое DNS-правило macOS");
    }
    return Native.toString(buffer, StandardCharsets.UTF_8);
  }

  private void putArray(Pointer dictionary, String name, List<String> values) {
    Pointer key = string(name);
    Pointer[] elements = values.stream().map(this::string).toArray(Pointer[]::new);
    Pointer array =
        cf.CFArrayCreate(
            null,
            elements,
            elements.length,
            NativeLibrary.getInstance("CoreFoundation")
                .getGlobalVariableAddress("kCFTypeArrayCallBacks"));
    try {
      cf.CFDictionarySetValue(dictionary, key, array);
    } finally {
      cf.CFRelease(array);
      cf.CFRelease(key);
      for (Pointer element : elements) {
        cf.CFRelease(element);
      }
    }
  }

  @Override
  public void close() {
    cf.CFRelease(store);
  }
}
