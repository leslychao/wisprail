package app.wisprail.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AgentFramesTest {
  @Test
  void typedFrameRoundTripsAndRejectsTruncatedOrOversizedMessages() throws Exception {
    var request = AgentProtocol.Request.status(0);
    var bytes = new ByteArrayOutputStream();
    AgentFrames.write(bytes, request);
    assertEquals(
        request,
        AgentFrames.read(
            new ByteArrayInputStream(bytes.toByteArray()), AgentProtocol.Request.class));
    assertThrows(
        IOException.class,
        () ->
            AgentFrames.read(
                new ByteArrayInputStream(new byte[] {0, 0, 0, 9, 1}), AgentProtocol.Request.class));
    assertThrows(
        IOException.class,
        () ->
            AgentFrames.read(
                new ByteArrayInputStream(new byte[] {127, 0, 0, 0}), AgentProtocol.Request.class));
  }

  @Test
  void serviceDoesNotAcceptRawEngineConfigurationOrExecutablePaths() throws Exception {
    String text =
        "{\"version\":1,\"command\":\"STATUS\",\"executable\":\"evil.exe\",\"config\":{}}";
    var bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      byte[] json = text.getBytes(StandardCharsets.UTF_8);
      output.writeInt(json.length);
      output.write(json);
    }
    assertThrows(
        IOException.class,
        () ->
            AgentFrames.read(
                new ByteArrayInputStream(bytes.toByteArray()), AgentProtocol.Request.class));
  }
}
