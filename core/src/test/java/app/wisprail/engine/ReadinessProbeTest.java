package app.wisprail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.platform.NetworkPlan;
import app.wisprail.profile.Profile;
import app.wisprail.profile.VlessSettings;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

class ReadinessProbeTest {
  @Test
  void realNegativeDnsAnswerProvesTransportWithoutInventingResourceAvailability() throws Exception {
    try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setSoTimeout(3000);
      var response =
          executor.submit(
              () -> {
                DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
                server.receive(packet);
                Message request = new Message(packet.getData());
                Message reply = new Message(request.getHeader().getID());
                reply.getHeader().setFlag(Flags.QR);
                reply.getHeader().setRcode(Rcode.NXDOMAIN);
                reply.addRecord(request.getQuestion(), Section.QUESTION);
                byte[] content = reply.toWire();
                server.send(new DatagramPacket(content, content.length, packet.getSocketAddress()));
                return null;
              });
      Profile profile = profile("", List.of("corp.example"));
      assertDoesNotThrow(
          () ->
              ReadinessProbe.dns(
                  profile, profile.dns(), server.getLocalPort(), Duration.ofSeconds(2)));
      response.get(3, TimeUnit.SECONDS);
    }
  }

  @Test
  void mismatchedQuestionAndServfailDoNotBecomeConnected() throws Exception {
    Message request =
        Message.newQuery(Record.newRecord(Name.fromString("corp.example."), Type.A, DClass.IN));
    Message reply = new Message(request.getHeader().getID());
    reply.getHeader().setFlag(Flags.QR);
    reply.addRecord(request.getQuestion(), Section.QUESTION);
    reply.getHeader().setRcode(Rcode.SERVFAIL);
    assertThrows(IOException.class, () -> ReadinessProbe.requireResponse(request, reply));
    reply.getHeader().setRcode(Rcode.NOERROR);
    reply.getHeader().setID(request.getHeader().getID() ^ 1);
    assertThrows(IOException.class, () -> ReadinessProbe.requireResponse(request, reply));
  }

  @Test
  void httpsHandshakeWithoutResponseIsBoundedByReadinessDeadline() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Profile profile =
          profile("https://127.0.0.1:" + server.getLocalPort() + "/health", List.of());
      long started = System.nanoTime();
      assertThrows(
          IOException.class,
          () -> ReadinessProbe.verify(profile, network(), Duration.ofMillis(200)));
      assertTrue(
          Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0,
          "The entire HTTPS attempt must respect the deadline, including TLS handshake");
    }
  }

  @Test
  void httpsOutsideSelectedNetworksIsRejectedBeforeConnecting() {
    assertThrows(
        IOException.class,
        () ->
            ReadinessProbe.verify(
                profile("https://192.0.2.1/health", List.of()), network(), Duration.ofSeconds(2)));
  }

  @Test
  void httpsViaExcludedServerCannotConfirmVpnReadiness() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(200);
      Profile profile =
          fullTunnelProfile("https://127.0.0.1:" + server.getLocalPort() + "/health");
      NetworkPlan network =
          new NetworkPlan(
              UUID.randomUUID(),
              "unused",
              "198.18.1.1/30",
              "198.18.1.2",
              List.of("127.0.0.1"),
              List.of("127.0.0.1/32"));
      IOException failure =
          assertThrows(
              IOException.class,
              () -> ReadinessProbe.verify(profile, network, Duration.ofSeconds(2)));
      assertEquals("Ресурс проверки разрешается в исключение маршрута VPN", failure.getMessage());
      assertThrows(SocketTimeoutException.class, server::accept);
    }
  }

  @Test
  void fullTunnelProbeNeedsNoCorporateDnsAndRequiresMatchingProtocolResponse() throws Exception {
    try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setSoTimeout(3000);
      var response =
          executor.submit(
              () -> {
                DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
                server.receive(packet);
                Message request = new Message(packet.getData());
                Message reply = new Message(request.getHeader().getID());
                reply.getHeader().setFlag(Flags.QR);
                reply.addRecord(request.getQuestion(), Section.QUESTION);
                byte[] content = reply.toWire();
                server.send(new DatagramPacket(content, content.length, packet.getSocketAddress()));
                return request.getQuestion();
              });
      Profile profile = fullTunnelProfile("");
      ReadinessProbe.dns(
          profile, network().dnsAddress(), server.getLocalPort(), Duration.ofSeconds(2));
      Record question = response.get(3, TimeUnit.SECONDS);
      assertEquals(Name.root, question.getName());
      assertEquals(Type.NS, question.getType());
    }
  }

  private static NetworkPlan network() {
    return new NetworkPlan(
        UUID.randomUUID(), "unused", "198.18.1.1/30", "127.0.0.1", List.of(), List.of());
  }

  private static Profile fullTunnelProfile(String health) {
    return new Profile(
        UUID.randomUUID(),
        1,
        "Internet",
        "192.0.2.10",
        443,
        VlessSettings.defaults(),
        List.of("0.0.0.0/0"),
        "",
        List.of(),
        health,
        "");
  }

  private static Profile profile(String health, List<String> domains) {
    return new Profile(
        UUID.randomUUID(),
        1,
        "Test",
        "192.0.2.10",
        443,
        VlessSettings.defaults(),
        List.of("127.0.0.0/8"),
        "127.0.0.1",
        domains,
        health,
        "");
  }
}
