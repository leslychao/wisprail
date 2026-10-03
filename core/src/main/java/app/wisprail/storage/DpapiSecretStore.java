package app.wisprail.storage;

import app.wisprail.profile.ProfileSecrets;
import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

public final class DpapiSecretStore implements SecretStore {
  private final Path directory;

  public DpapiSecretStore(Path directory) throws IOException {
    this.directory = directory;
    SecureFiles.directory(directory);
  }

  @Override
  public String save(ProfileSecrets secrets) throws IOException {
    String reference = UUID.randomUUID().toString();
    byte[] cleartext = JsonFiles.mapper().writeValueAsBytes(secrets);
    try {
      if (cleartext.length > JsonFiles.MAX_BYTES) {
        throw new IOException("Секрет превышает 1 МБ");
      }
      byte[] encrypted = Crypt32Util.cryptProtectData(cleartext);
      Path path = path(reference);
      Files.write(path, encrypted);
      SecureFiles.restrict(path);
      return reference;
    } finally {
      Arrays.fill(cleartext, (byte) 0);
    }
  }

  @Override
  public ProfileSecrets read(String reference) throws IOException {
    if (reference.isEmpty()) {
      return ProfileSecrets.empty();
    }
    byte[] encrypted;
    try (var stream = Files.newInputStream(path(reference))) {
      encrypted = stream.readNBytes(JsonFiles.MAX_BYTES + 65537);
    }
    if (encrypted.length > JsonFiles.MAX_BYTES + 65536) {
      throw new IOException("Секрет превышает допустимый размер");
    }
    byte[] cleartext = Crypt32Util.cryptUnprotectData(encrypted);
    try {
      return JsonFiles.mapper().readValue(cleartext, ProfileSecrets.class);
    } finally {
      Arrays.fill(cleartext, (byte) 0);
    }
  }

  @Override
  public void delete(String reference) throws IOException {
    if (!reference.isEmpty()) {
      Files.deleteIfExists(path(reference));
    }
  }

  private Path path(String reference) {
    return directory.resolve(UUID.fromString(reference) + ".dpapi");
  }
}
