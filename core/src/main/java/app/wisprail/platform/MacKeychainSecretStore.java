package app.wisprail.platform;

import app.wisprail.storage.SecretStore;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public final class MacKeychainSecretStore implements SecretStore {
  @Override
  public void write(String reference, byte[] value) throws IOException {
    UUID.fromString(reference);
    if (value.length > 1024 * 1024) {
      throw new IOException("Слишком большой секрет");
    }
    MacNative.check(
        MacNative.api().wr_keychain_write(reference, value, value.length),
        "Не удалось сохранить секрет в Keychain");
  }

  @Override
  public Optional<byte[]> read(String reference) throws IOException {
    UUID.fromString(reference);
    MacNative.Api api = MacNative.api();
    PointerByReference output = new PointerByReference();
    LongByReference length = new LongByReference();
    int status = api.wr_keychain_read(reference, output, length);
    if (status == -25300) {
      return Optional.empty();
    }
    MacNative.check(status, "Не удалось прочитать секрет из Keychain");
    return Optional.of(MacNative.result(api, output, length));
  }

  @Override
  public void delete(String reference) throws IOException {
    UUID.fromString(reference);
    MacNative.check(
        MacNative.api().wr_keychain_delete(reference), "Не удалось удалить секрет из Keychain");
  }
}
