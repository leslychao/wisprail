package app.wisprail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.wisprail.platform.CommandRunner;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import app.wisprail.storage.JsonFiles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real local protocol evidence. These tests deliberately do not create a system TUN or DNS rule.
 */
class LoopbackVpnIT {
  private static final String CLIENT_ID = "00000000-0000-4000-8000-000000000001";
  private static final String API_SECRET = "local-fixture-api";
  @TempDir Path directory;
  private Path binary;
  private String certificate;
  private String privateKey;

  @BeforeEach
  void createFixtureCertificate() throws Exception {
    binary =
        Path.of(System.getProperty("wisprail.engine", "../deploy/target/engine/sing-box.exe"))
            .toAbsolutePath();
    assumeTrue(Files.isRegularFile(binary), "Pinned engine is required");
    Path store = directory.resolve("fixture.p12");
    String keytool =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool")
            .toString();
    var generated =
        new CommandRunner()
            .run(
                List.of(
                    keytool,
                    "-genkeypair",
                    "-alias",
                    "fixture",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "2",
                    "-dname",
                    "CN=localhost",
                    "-ext",
                    "SAN=dns:localhost,ip:127.0.0.1",
                    "-ext",
                    "BC=ca:true",
                    "-ext",
                    "EKU=serverAuth,clientAuth",
                    "-ext",
                    "KU=digitalSignature,keyEncipherment",
                    "-keystore",
                    store.toString(),
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    "fixture-only-password",
                    "-noprompt"),
                new byte[0],
                Duration.ofSeconds(20));
    assertEquals(0, generated.exitCode(), "Local certificate fixture failed");
    KeyStore keys = KeyStore.getInstance(store.toFile(), "fixture-only-password".toCharArray());
    certificate = pem("CERTIFICATE", keys.getCertificate("fixture").getEncoded());
    privateKey =
        pem(
            "PRIVATE KEY",
            keys.getKey("fixture", "fixture-only-password".toCharArray()).getEncoded());
  }

  @Test
  void vlessCarriesAnHttpResponseAcrossRealTlsLoopbackEndpoint() throws Exception {
    int serverPort = freePort();
    int clientPort = freePort();
    int apiPort = freePort();
    var server = JsonFiles.mapper().createObjectNode();
    var inbound = server.putArray("inbounds").addObject();
    inbound.put("type", "vless").put("listen", "127.0.0.1").put("listen_port", serverPort);
    inbound.putArray("users").addObject().put("uuid", CLIENT_ID);
    inbound
        .putObject("tls")
        .put("enabled", true)
        .put("certificate", certificate)
        .put("key", privateKey);
    server.putArray("outbounds").addObject().put("type", "direct").put("tag", "direct");
    server.putObject("route").put("final", "direct");
    var profile = profile(VpnProfile.Protocol.VLESS, serverPort);
    var client = client(profile, apiPort);
    client.path("outbounds").get(0).withObject("tls").put("certificate", certificate);
    client
        .putArray("inbounds")
        .addObject()
        .put("type", "mixed")
        .put("listen", "127.0.0.1")
        .put("listen_port", clientPort);
    try (ServerSocket target = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      target.setSoTimeout(10000);
      CompletableFuture<String> received = new CompletableFuture<>();
      Thread.ofVirtual()
          .start(
              () -> {
                try (Socket socket = target.accept()) {
                  socket.setSoTimeout(10000);
                  received.complete(headers(socket));
                  socket
                      .getOutputStream()
                      .write(
                          "HTTP/1.1 200 OK\r\nContent-Length: 16\r\nConnection: close\r\n\r\nwisprail-fixture"
                              .getBytes(StandardCharsets.US_ASCII));
                } catch (IOException exception) {
                  received.completeExceptionally(exception);
                }
              });
      Process remote = run("vless-server", server);
      Process local = run("vless-client", client);
      try (EngineApi api = new EngineApi(apiPort, API_SECRET)) {
        awaitApi(api);
        try (Socket socket = new Socket("127.0.0.1", clientPort)) {
          socket.setSoTimeout(10000);
          String authority = "127.0.0.1:" + target.getLocalPort();
          socket
              .getOutputStream()
              .write(
                  ("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
          assertTrue(headers(socket).startsWith("HTTP/1.1 200"));
          socket
              .getOutputStream()
              .write(
                  "GET /proof HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n"
                      .getBytes(StandardCharsets.US_ASCII));
          String response =
              new String(socket.getInputStream().readNBytes(4096), StandardCharsets.US_ASCII);
          assertTrue(response.contains("200 OK") && response.contains("wisprail-fixture"));
          assertTrue(received.get(10, TimeUnit.SECONDS).startsWith("GET /proof HTTP/1.1"));
        }
      } finally {
        stop(local);
        stop(remote);
      }
    }
  }

  @Test
  void openVpnReportsConnectedOnlyAfterRealCertificateHandshake() throws Exception {
    int serverPort;
    try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
      serverPort = socket.getLocalPort();
    }
    int apiPort = freePort();
    int serverApiPort = freePort();
    var server = JsonFiles.mapper().createObjectNode();
    var endpoint = server.putArray("endpoints").addObject();
    endpoint
        .put("type", "openvpn-server")
        .put("tag", "server")
        .put("listen", "127.0.0.1")
        .put("listen_port", serverPort)
        .put("network", "udp")
        .put("system", false);
    endpoint.putArray("address").add("10.88.0.1/24");
    endpoint
        .putObject("tls")
        .put("certificate", certificate)
        .put("key", privateKey)
        .put("client_certificate", certificate)
        .put("verify_client_certificate", "require");
    server.putArray("outbounds").addObject().put("type", "direct").put("tag", "direct");
    server.putObject("route").put("final", "direct");
    server
        .putArray("services")
        .addObject()
        .put("type", "api")
        .put("listen", "127.0.0.1")
        .put("listen_port", serverApiPort)
        .put("secret", API_SECRET)
        .put("dashboard", false);
    var client = client(profile(VpnProfile.Protocol.OPENVPN, serverPort), apiPort);
    Process remote = run("openvpn-server", server);
    try (EngineApi serverApi = new EngineApi(serverApiPort, API_SECRET)) {
      awaitApi(serverApi);
      Process local = run("openvpn-client", client);
      try (EngineApi api = new EngineApi(apiPort, API_SECRET)) {
        awaitApi(api);
        api.awaitOpenVpn(() -> false);
        api.checkOpenVpnReady();
      } finally {
        stop(local);
      }
    } finally {
      stop(remote);
    }
  }

  private ObjectNode client(VpnProfile profile, int apiPort) {
    var secrets = new ProfileSecrets("", CLIENT_ID, certificate, certificate, privateKey, "");
    var network =
        new SingboxConfig.RuntimeNetwork(
            "unused", "198.18.0.1/30", "198.18.0.2", List.of("127.0.0.1"), apiPort, API_SECRET);
    ObjectNode client = new SingboxConfig().compile(profile, secrets, network);
    client.remove("inbounds");
    // The fixture uses loopback sockets; system routing and TUN are tested by a separate privileged
    // gate.
    client.withObject("route").remove("auto_detect_interface");
    return client;
  }

  private Process run(String name, ObjectNode config) throws Exception {
    Path file = directory.resolve(name + ".json");
    JsonFiles.writeAtomic(file, config);
    var checked =
        new CommandRunner()
            .run(
                List.of(binary.toString(), "check", "-c", file.toString()),
                new byte[0],
                Duration.ofSeconds(10));
    assertEquals(0, checked.exitCode(), name + ": " + checked.output());
    Path logs = Path.of("target", "loopback-evidence");
    Files.createDirectories(logs);
    return new ProcessBuilder(binary.toString(), "run", "-c", file.toString())
        .redirectOutput(logs.resolve(name + ".stdout.log").toFile())
        .redirectError(logs.resolve(name + ".stderr.log").toFile())
        .start();
  }

  private static void awaitApi(EngineApi api) throws Exception {
    for (int attempt = 0; attempt < 30; attempt++) {
      try {
        api.checkVersion();
        return;
      } catch (IOException exception) {
        Thread.sleep(100);
      }
    }
    throw new IOException("Local fixture API did not become ready");
  }

  private static VpnProfile profile(VpnProfile.Protocol protocol, int serverPort) {
    boolean openVpn = protocol == VpnProfile.Protocol.OPENVPN;
    return new VpnProfile(
        1,
        UUID.randomUUID(),
        1,
        "Loopback fixture",
        "localhost",
        serverPort,
        protocol,
        openVpn ? new VpnProfile.OpenVpn("udp", "", "localhost", "", "SHA256", "", "") : null,
        openVpn
            ? null
            : new VpnProfile.Vless(VpnProfile.Security.TLS, "localhost", "", "", "chrome", ""),
        List.of("127.0.0.1/32"),
        VpnProfile.Dns.empty(),
        "",
        "");
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static String headers(Socket socket) throws IOException {
    StringBuilder text = new StringBuilder();
    while (text.length() < 16384) {
      int next = socket.getInputStream().read();
      if (next == -1) {
        throw new IOException("Incomplete fixture HTTP headers");
      }
      text.append((char) next);
      if (text.toString().endsWith("\r\n\r\n")) {
        return text.toString();
      }
    }
    throw new IOException("Fixture HTTP headers exceeded limit");
  }

  private static void stop(Process process) throws InterruptedException {
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor();
    }
  }

  private static String pem(String type, byte[] bytes) {
    return "-----BEGIN "
        + type
        + "-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
        + "\n-----END "
        + type
        + "-----\n";
  }
}
