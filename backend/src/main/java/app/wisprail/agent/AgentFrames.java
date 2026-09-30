package app.wisprail.agent;

import app.wisprail.storage.JsonFiles;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class AgentFrames {
  private AgentFrames() {}

  static <T> T read(InputStream input, Class<T> type) throws IOException {
    DataInputStream data = new DataInputStream(input);
    int size = data.readInt();
    if (size < 1 || size > JsonFiles.MAX_BYTES) {
      throw new IOException("Некорректный размер IPC-сообщения");
    }
    byte[] bytes = data.readNBytes(size);
    if (bytes.length != size) {
      throw new EOFException("Неполное IPC-сообщение");
    }
    return JsonFiles.mapper().readValue(bytes, type);
  }

  static void write(OutputStream output, Object value) throws IOException {
    byte[] bytes = JsonFiles.mapper().writeValueAsBytes(value);
    if (bytes.length > JsonFiles.MAX_BYTES) {
      throw new IOException("IPC-сообщение превышает лимит");
    }
    DataOutputStream data = new DataOutputStream(output);
    data.writeInt(bytes.length);
    data.write(bytes);
    data.flush();
  }
}
