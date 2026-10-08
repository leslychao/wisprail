package app.wisprail.agent;

import app.wisprail.storage.JsonCodec;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

final class AgentProtocol {
  private AgentProtocol() {}

  static byte[] encode(AgentMessage message) {
    StringWriter output = new StringWriter();
    try (JsonWriter writer = new JsonWriter(output)) {
      writer.setSerializeNulls(true);
      JsonCodec.gson().getAdapter(AgentMessage.class).write(writer, message);
    } catch (IOException exception) {
      throw new IllegalStateException("Не удалось подготовить сообщение IPC", exception);
    }
    return output.toString().getBytes(StandardCharsets.UTF_8);
  }

  static AgentMessage decode(byte[] bytes) throws IOException {
    if (bytes.length == 0 || bytes.length > IpcConnection.MAX_MESSAGE_BYTES) {
      throw new IOException("Недопустимый размер сообщения IPC");
    }
    try {
      JsonElement json =
          JsonCodec.parseStrict(
              StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), 32);
      if (!json.isJsonObject()) {
        throw new IOException("Недопустимое сообщение IPC");
      }
      JsonObject object = json.getAsJsonObject();
      if (!Set.of("kind", "id", "method", "payload", "success", "error")
              .containsAll(object.keySet())
          || !isString(object.get("kind"))
          || !isString(object.get("method"))
          || !isString(object.get("error"))
          || (object.has("id") && !object.get("id").isJsonNull() && !isString(object.get("id")))) {
        throw new IOException("Неизвестное поле IPC");
      }
      AgentMessage message = JsonCodec.gson().fromJson(json, AgentMessage.class);
      if (message == null
          || message.kind() == null
          || message.payload() == null
          || message.error() == null
          || message.error().length() > 4096
          || message.method() == null
          || message.method().length() > 32
          || !Set.of("request", "response", "snapshot", "event").contains(message.kind())
          || !object.has("success")
          || !object.get("success").isJsonPrimitive()
          || !object.getAsJsonPrimitive("success").isBoolean()) {
        throw new IOException("Неполное сообщение IPC");
      }
      return message;
    } catch (JsonParseException | IllegalStateException | NumberFormatException exception) {
      throw new IOException("Повреждено сообщение IPC", exception);
    }
  }

  private static boolean isString(JsonElement value) {
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
  }
}
