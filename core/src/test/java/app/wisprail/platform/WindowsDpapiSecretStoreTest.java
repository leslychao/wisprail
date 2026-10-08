package app.wisprail.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsDpapiSecretStoreTest {
  @TempDir Path directory;

  @Test
  void roundTripsThroughCurrentUserDpapiWithoutPlaintextSidecar() throws Exception {
    var store = new WindowsDpapiSecretStore(directory);
    String reference = UUID.randomUUID().toString();
    byte[] value = "test-only-private-material-987654".getBytes(StandardCharsets.UTF_8);
    store.write(reference, value);
    assertArrayEquals(value, store.read(reference).orElseThrow());
    byte[] stored = Files.readAllBytes(directory.resolve("secrets").resolve(reference + ".dpapi"));
    assertFalse(
        new String(stored, StandardCharsets.ISO_8859_1)
            .contains(new String(value, StandardCharsets.UTF_8)));
    store.delete(reference);
    assertTrue(store.read(reference).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> store.read("../outside"));
  }
}
