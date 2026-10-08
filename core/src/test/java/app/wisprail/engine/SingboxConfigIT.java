package app.wisprail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.wisprail.platform.NetworkPlan;
import app.wisprail.profile.OpenVpnSettings;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VlessSettings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Section;

class SingboxConfigIT {
  @TempDir Path temporary;

  @Test
  void vlessTlsCarriesRealUdpDnsResponseThroughPinnedEngineWithoutTun() throws Exception {
    Path executable = engine();
    EngineCredentials credentials = EngineCredentials.create();
    String uuid = UUID.randomUUID().toString();
    int vlessPort = availablePort();
    int dnsPort = availablePort();
    try (DatagramSocket dns = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      dns.setSoTimeout(12000);
      var answer =
          executor.submit(
              () -> {
                DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
                dns.receive(packet);
                Message request = new Message(packet.getData());
                Message response = new Message(request.getHeader().getID());
                response.getHeader().setFlag(Flags.QR);
                response.getHeader().setRcode(Rcode.NXDOMAIN);
                response.addRecord(request.getQuestion(), Section.QUESTION);
                byte[] wire = response.toWire();
                dns.send(new DatagramPacket(wire, wire.length, packet.getSocketAddress()));
                return request.getQuestion().getName().toString();
              });
      JsonObject inbound = new JsonObject();
      inbound.addProperty("type", "vless");
      inbound.addProperty("listen", "127.0.0.1");
      inbound.addProperty("listen_port", vlessPort);
      JsonObject user = new JsonObject();
      user.addProperty("uuid", uuid);
      inbound.add("users", array(user));
      JsonObject serverTls = new JsonObject();
      serverTls.addProperty("enabled", true);
      serverTls.addProperty("certificate", credentials.certificate());
      serverTls.addProperty("key", credentials.key());
      inbound.add("tls", serverTls);
      JsonObject server = new JsonObject();
      server.add("inbounds", array(inbound));
      JsonObject direct = new JsonObject();
      direct.addProperty("type", "direct");
      server.add("outbounds", array(direct));

      Profile profile =
          new Profile(
              UUID.randomUUID(),
              1,
              "Loopback",
              "127.0.0.1",
              vlessPort,
              VlessSettings.defaults(),
              List.of("127.0.0.0/8"),
              "127.0.0.1",
              List.of("corp.example"),
              "",
              "");
      NetworkPlan network =
          new NetworkPlan(
              UUID.randomUUID(),
              "unused",
              "198.18.1.1/30",
              "198.18.1.2",
              List.of("127.0.0.1"),
              List.of());
      JsonObject generated =
          SingboxConfig.create(
              profile,
              new ProfileSecrets("", "", "", "", "", uuid),
              network,
              availablePort(),
              credentials.token(),
              credentials.certificate(),
              credentials.key());
      JsonObject outbound = generated.getAsJsonArray("outbounds").get(0).getAsJsonObject();
      outbound.getAsJsonObject("tls").addProperty("server_name", "127.0.0.1");
      outbound.getAsJsonObject("tls").addProperty("certificate", credentials.certificate());
      JsonObject forward = new JsonObject();
      forward.addProperty("type", "direct");
      forward.addProperty("listen", "127.0.0.1");
      forward.addProperty("listen_port", dnsPort);
      forward.addProperty("network", "udp");
      forward.addProperty("override_address", "127.0.0.1");
      forward.addProperty("override_port", dns.getLocalPort());
      JsonObject client = new JsonObject();
      client.add("inbounds", array(forward));
      client.add("outbounds", array(outbound));
      Process remote = startLoopback(executable, "vless-server", server);
      Process local = null;
      try {
        local = startLoopback(executable, "vless-client", client);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
          try {
            ReadinessProbe.dns(profile, dnsPort, Duration.ofSeconds(1));
            break;
          } catch (IOException starting) {
            if (!local.isAlive() || !remote.isAlive() || System.nanoTime() >= deadline) {
              throw starting;
            }
          }
        }
        assertTrue(answer.get(2, TimeUnit.SECONDS).endsWith(".corp.example."));
      } finally {
        if (local != null) {
          local.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
        remote.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private Process startLoopback(Path executable, String name, JsonObject configuration)
      throws IOException {
    Path path = temporary.resolve(name + ".json");
    Files.writeString(path, new Gson().toJson(configuration));
    return new ProcessBuilder(executable.toString(), "run", "-c", path.toString())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
  }

  private static JsonArray array(JsonObject object) {
    JsonArray array = new JsonArray();
    array.add(object);
    return array;
  }

  private static int availablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  @Test
  void pinnedEngineChecksBothProtocolConfigurationsWithoutStartingTun() throws Exception {
    Path engine = engine();
    EngineCredentials credentials = EngineCredentials.create();
    Profile vless = profile(VlessSettings.defaults());
    Profile openVpn =
        profile(
            new OpenVpnSettings(
                "udp",
                "test-user",
                false,
                credentials.certificate(),
                "",
                "",
                "",
                "vpn.example",
                -1));
    ProfileSecrets secrets =
        new ProfileSecrets("test-password", "", "", "", "", UUID.randomUUID().toString());
    for (Profile profile : List.of(vless, openVpn)) {
      NetworkPlan network =
          new NetworkPlan(
              UUID.randomUUID(),
              "WisprailTest",
              "198.18.1.1/30",
              "198.18.1.2",
              List.of("192.0.2.10"),
              List.of("192.0.2.10/32"));
      JsonObject config =
          SingboxConfig.create(
              profile,
              secrets,
              network,
              18745,
              credentials.token(),
              credentials.certificate(),
              credentials.key());
      assertEquals(
          "disabled",
          config.getAsJsonArray("inbounds").get(0).getAsJsonObject().get("dns_mode").getAsString());
      assertEquals(
          SingboxConfig.VPN_TAG,
          config
              .getAsJsonObject("dns")
              .getAsJsonArray("servers")
              .get(0)
              .getAsJsonObject()
              .get("detour")
              .getAsString());
      if (profile.settings() instanceof VlessSettings) {
        assertFalse(config.getAsJsonArray("outbounds").get(0).getAsJsonObject().has("network"));
      }
      Path file = temporary.resolve(profile.type() + ".json");
      Files.writeString(file, new Gson().toJson(config));
      Process check =
          new ProcessBuilder(engine.toString(), "check", "-c", file.toString())
              .redirectErrorStream(true)
              .redirectOutput(temporary.resolve("check-output").toFile())
              .start();
      try {
        assertTrue(check.waitFor(15, TimeUnit.SECONDS), "sing-box check timeout");
        assertEquals(0, check.exitValue(), "Pinned engine rejected " + profile.type());
      } finally {
        check.destroyForcibly();
      }
    }
  }

  @Test
  void realLoopbackApiRequiresBearerAndUsesPinnedServerCertificate() throws Exception {
    Path engine = engine();
    EngineCredentials credentials = EngineCredentials.create();
    int port;
    try (ServerSocket reserve = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = reserve.getLocalPort();
    }
    JsonObject config = new JsonObject();
    JsonObject service = new JsonObject();
    service.addProperty("type", "api");
    service.addProperty("listen", "127.0.0.1");
    service.addProperty("listen_port", port);
    service.addProperty("secret", credentials.token());
    service.addProperty("dashboard", false);
    JsonObject tls = new JsonObject();
    tls.addProperty("enabled", true);
    tls.addProperty("certificate", credentials.certificate());
    tls.addProperty("key", credentials.key());
    service.add("tls", tls);
    JsonArray services = new JsonArray();
    services.add(service);
    config.add("services", services);
    Path file = temporary.resolve("loopback.json");
    Files.writeString(file, new Gson().toJson(config));
    Process process =
        new ProcessBuilder(engine.toString(), "run", "-c", file.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (EngineApi api = new EngineApi(port, credentials)) {
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while (true) {
        try {
          api.verifyVersion(Duration.ofMillis(500));
          break;
        } catch (StatusRuntimeException starting) {
          if (!process.isAlive() || System.nanoTime() >= deadline) {
            throw starting;
          }
          Thread.sleep(100);
        }
      }
      EngineCredentials wrong = new EngineCredentials("incorrect", credentials.certificate(), "");
      try (EngineApi unauthenticated = new EngineApi(port, wrong)) {
        StatusRuntimeException error =
            assertThrows(
                StatusRuntimeException.class,
                () -> unauthenticated.verifyVersion(Duration.ofSeconds(2)));
        assertEquals(Status.Code.UNAUTHENTICATED, error.getStatus().getCode());
      }
      try (EngineApi impersonator = new EngineApi(port, EngineCredentials.create())) {
        assertThrows(
            StatusRuntimeException.class, () -> impersonator.verifyVersion(Duration.ofSeconds(2)));
      }
    } finally {
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private static Profile profile(app.wisprail.profile.VpnSettings settings) {
    return new Profile(
        UUID.randomUUID(),
        1,
        "Integration test",
        "192.0.2.10",
        443,
        settings,
        List.of("10.20.0.0/16"),
        "10.20.0.53",
        List.of("corp.example"),
        "",
        "");
  }

  private static Path engine() {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    String platform =
        windows
            ? "windows-amd64"
            : "darwin-" + (System.getProperty("os.arch").equals("aarch64") ? "arm64" : "amd64");
    Path path =
        Path.of(
                "..",
                "packaging",
                "target",
                "engine",
                platform,
                windows ? "sing-box.exe" : "sing-box")
            .toAbsolutePath()
            .normalize();
    assumeTrue(Files.isExecutable(path), "Fetch the pinned engine for integration checks");
    return path;
  }
}
