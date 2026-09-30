package app.wisprail.storage;

import app.wisprail.profile.ProfileSecrets;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Calls Security.framework directly so passwords never occur in command-line arguments. */
public final class KeychainSecretStore implements SecretStore {
  private static final byte[] SERVICE = "app.wisprail.profiles".getBytes(StandardCharsets.UTF_8);
  private static final int NOT_FOUND = -25300;
  private final Security security = Native.load("Security", Security.class);

  public interface Security extends Library {
    int SecKeychainAddGenericPassword(
        Pointer keychain,
        int serviceLength,
        byte[] service,
        int accountLength,
        byte[] account,
        int passwordLength,
        byte[] password,
        PointerByReference item);

    int SecKeychainFindGenericPassword(
        Pointer keychain,
        int serviceLength,
        byte[] service,
        int accountLength,
        byte[] account,
        IntByReference length,
        PointerByReference data,
        PointerByReference item);

    int SecKeychainItemFreeContent(Pointer attributes, Pointer data);

    int SecKeychainItemDelete(Pointer item);
  }

  public interface CoreFoundation extends Library {
    void CFRelease(Pointer reference);
  }

  @Override
  public String save(ProfileSecrets secrets) throws IOException {
    String reference = UUID.randomUUID().toString();
    byte[] account = reference.getBytes(StandardCharsets.UTF_8);
    byte[] bytes = JsonFiles.mapper().writeValueAsBytes(secrets);
    try {
      if (bytes.length > JsonFiles.MAX_BYTES) {
        throw new IOException("Секрет превышает 1 МБ");
      }
      check(
          security.SecKeychainAddGenericPassword(
              null, SERVICE.length, SERVICE, account.length, account, bytes.length, bytes, null));
      return reference;
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  @Override
  public ProfileSecrets read(String reference) throws IOException {
    if (reference.isEmpty()) {
      return ProfileSecrets.empty();
    }
    byte[] account = UUID.fromString(reference).toString().getBytes(StandardCharsets.UTF_8);
    IntByReference length = new IntByReference();
    PointerByReference data = new PointerByReference();
    check(
        security.SecKeychainFindGenericPassword(
            null, SERVICE.length, SERVICE, account.length, account, length, data, null));
    try {
      if (length.getValue() < 0
          || length.getValue() > JsonFiles.MAX_BYTES
          || data.getValue() == null) {
        throw new IOException("Некорректный размер секрета Keychain");
      }
      byte[] bytes = data.getValue().getByteArray(0, length.getValue());
      try {
        return JsonFiles.mapper().readValue(bytes, ProfileSecrets.class);
      } finally {
        Arrays.fill(bytes, (byte) 0);
      }
    } finally {
      check(security.SecKeychainItemFreeContent(null, data.getValue()));
    }
  }

  @Override
  public void delete(String reference) throws IOException {
    if (reference.isEmpty()) {
      return;
    }
    byte[] account = UUID.fromString(reference).toString().getBytes(StandardCharsets.UTF_8);
    PointerByReference item = new PointerByReference();
    int status =
        security.SecKeychainFindGenericPassword(
            null, SERVICE.length, SERVICE, account.length, account, null, null, item);
    if (status == NOT_FOUND) {
      return;
    }
    check(status);
    try {
      check(security.SecKeychainItemDelete(item.getValue()));
    } finally {
      Native.load("CoreFoundation", CoreFoundation.class).CFRelease(item.getValue());
    }
  }

  private static void check(int status) throws IOException {
    if (status != 0) {
      throw new IOException("Хранилище Keychain недоступно, код " + status);
    }
  }
}
