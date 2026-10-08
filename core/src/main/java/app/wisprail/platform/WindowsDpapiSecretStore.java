package app.wisprail.platform;

import app.wisprail.storage.PrivateFiles;
import app.wisprail.storage.SecretStore;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinCrypt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** DPAPI is deliberately called in the ordinary user's process, not the service. */
public final class WindowsDpapiSecretStore implements SecretStore {
  private final Path directory;

  public WindowsDpapiSecretStore(Path userDirectory) throws IOException {
    directory = PrivateFiles.directory(userDirectory.resolve("secrets"));
  }

  @Override
  public void write(String reference, byte[] value) throws IOException {
    if (value.length > 1024 * 1024) {
      throw new IOException("Слишком большой секрет");
    }
    Path destination = path(reference);
    byte[] encrypted;
    try {
      encrypted = Crypt32Util.cryptProtectData(value, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
    } catch (Win32Exception exception) {
      throw new IOException("DPAPI не смог защитить секрет", exception);
    }
    try {
      PrivateFiles.writeAtomic(destination, encrypted);
    } finally {
      Arrays.fill(encrypted, (byte) 0);
    }
  }

  @Override
  public Optional<byte[]> read(String reference) throws IOException {
    Path source = path(reference);
    if (!Files.exists(source)) {
      return Optional.empty();
    }
    byte[] encrypted = PrivateFiles.read(source, 2 * 1024 * 1024);
    try {
      return Optional.of(
          Crypt32Util.cryptUnprotectData(encrypted, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN));
    } catch (Win32Exception exception) {
      throw new IOException("DPAPI не смог открыть секрет этого пользователя", exception);
    } finally {
      Arrays.fill(encrypted, (byte) 0);
    }
  }

  @Override
  public void delete(String reference) throws IOException {
    Files.deleteIfExists(path(reference));
  }

  private Path path(String reference) {
    return directory.resolve(UUID.fromString(reference) + ".dpapi");
  }
}
