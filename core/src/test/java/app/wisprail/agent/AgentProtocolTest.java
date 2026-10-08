package app.wisprail.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AgentProtocolTest {
  @Test
  void rejectsOversizedFrameBeforeAllocatingPayloadAndTruncatedFrame() {
    var oversized =
        new FragmentedConnection(
            ByteBuffer.allocate(4).putInt(IpcConnection.MAX_MESSAGE_BYTES + 1).array());
    assertThrows(IOException.class, oversized::receive);
    var truncated = new FragmentedConnection(new byte[] {0, 0, 0, 2, 1});
    assertThrows(EOFException.class, truncated::receive);
  }

  @Test
  void handlesFragmentedFramesAndSerializesLengthWithoutChangingPayload() throws Exception {
    var connection = new FragmentedConnection(new byte[] {0, 0, 0, 3, 1, 2, 3});
    assertArrayEquals(new byte[] {1, 2, 3}, connection.receive());
    connection.send(new byte[] {4, 5});
    assertArrayEquals(new byte[] {0, 0, 0, 2, 4, 5}, connection.output.toByteArray());
  }

  @Test
  void requiresStrictUtf8JsonWithBoundedDepthAndKnownEnvelope() throws Exception {
    String valid =
        "{\"kind\":\"request\",\"method\":\"ping\",\"payload\":null,"
            + "\"success\":true,\"error\":\"\"}";
    assertEquals("ping", AgentProtocol.decode(valid.getBytes(StandardCharsets.UTF_8)).method());
    assertThrows(
        IOException.class,
        () -> AgentProtocol.decode((valid + "{}").getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        IOException.class,
        () ->
            AgentProtocol.decode(
                valid
                    .replace("null", "[".repeat(33) + "0" + "]".repeat(33))
                    .getBytes(StandardCharsets.UTF_8)));
    assertThrows(IOException.class, () -> AgentProtocol.decode(new byte[] {(byte) 0xff}));
    assertThrows(
        IOException.class,
        () ->
            AgentProtocol.decode(
                valid
                    .replace("\"success\":true", "\"success\":true,\"extra\":true")
                    .getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        IOException.class,
        () ->
            AgentProtocol.decode(
                valid
                    .replace("\"success\":true", "\"success\":true,\"success\":false")
                    .getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        IOException.class,
        () ->
            AgentProtocol.decode(
                valid.replace("\"ping\"", "123").getBytes(StandardCharsets.UTF_8)));
  }

  private static final class FragmentedConnection extends FramedConnection {
    private final ByteBuffer input;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    FragmentedConnection(byte[] input) {
      super("test");
      this.input = ByteBuffer.wrap(input);
    }

    @Override
    int read(ByteBuffer target) {
      if (!input.hasRemaining()) {
        return -1;
      }
      target.put(input.get());
      return 1;
    }

    @Override
    void write(ByteBuffer source) {
      output.write(source.get());
    }

    @Override
    public void close() {}
  }
}
