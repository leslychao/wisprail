package app.wisprail.storage;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class JsonFiles {
  public static final int MAX_BYTES = 1024 * 1024;
  private static final ObjectMapper MAPPER = createMapper();

  private JsonFiles() {}

  private static ObjectMapper createMapper() {
    ObjectMapper mapper =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    mapper
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(MAX_BYTES).build());
    return mapper;
  }

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static <T> T read(Path path, Class<T> type) throws IOException {
    try (var stream = Files.newInputStream(path)) {
      byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) {
        throw new IOException("Файл превышает 1 МБ");
      }
      return MAPPER.readValue(bytes, type);
    }
  }

  public static void writeAtomic(Path path, Object value) throws IOException {
    // Callers secure private storage directories. An export must not change a chosen folder's ACL.
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".pending-", ".json");
    try {
      SecureFiles.restrict(temporary);
      byte[] data = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
      if (data.length > MAX_BYTES) {
        throw new IOException("Файл превышает 1 МБ");
      }
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        var buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      Files.move(
          temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
