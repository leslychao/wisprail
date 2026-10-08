package app.wisprail.storage;

import java.io.IOException;
import java.util.Optional;

/** User-scoped OS protection; references are opaque UUIDs, never user-supplied paths. */
public interface SecretStore {
  void write(String reference, byte[] value) throws IOException;

  Optional<byte[]> read(String reference) throws IOException;

  void delete(String reference) throws IOException;
}
