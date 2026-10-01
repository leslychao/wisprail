package app.wisprail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.wisprail.platform.CommandRunner;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import app.wisprail.storage.JsonFiles;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingboxConfigIT {
  @TempDir Path directory;

  @Test
  void realEngineAcceptsVlessTlsRealityAndOpenVpnProfiles() throws Exception {
    Path binary = binary();
    String ca = trustedCa();
    for (String type : List.of("tls", "reality", "openvpn")) {
      boolean openVpn = type.equals("openvpn");
      VpnProfile profile =
          new VpnProfile(
              1,
              UUID.randomUUID(),
              1,
              "Engine schema",
              "vpn.example.com",
              443,
              openVpn ? VpnProfile.Protocol.OPENVPN : VpnProfile.Protocol.VLESS,
              openVpn
                  ? new VpnProfile.OpenVpn(
                      "udp", "", "vpn.example.com", "AES-256-GCM", "SHA256", "", "")
                  : null,
              openVpn
                  ? null
                  : new VpnProfile.Vless(
                      type.equals("tls") ? VpnProfile.Security.TLS : VpnProfile.Security.REALITY,
                      "vpn.example.com",
                      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]),
                      "",
                      "chrome",
                      ""),
              List.of("10.20.0.0/16"),
              new VpnProfile.Dns("10.20.0.53", List.of("corp.example")),
              "",
              "");
      var secrets = new ProfileSecrets("", "00000000-0000-4000-8000-000000000001", ca, "", "", "");
      var network =
          new SingboxConfig.RuntimeNetwork(
              "Wisprail",
              "198.18.0.1/30",
              "198.18.0.2",
              List.of("192.0.2.1"),
              19891,
              "test-secret");
      Path configuration = directory.resolve(type + ".json");
      JsonFiles.writeAtomic(configuration, new SingboxConfig().compile(profile, secrets, network));
      var result =
          new CommandRunner()
              .run(
                  List.of(binary.toString(), "check", "-c", configuration.toString()),
                  new byte[0],
                  Duration.ofSeconds(15));
      assertEquals(0, result.exitCode(), type + ": " + result.output());
    }
  }

  @Test
  void realApiAuthenticatesAndReportsPinnedVersionWithoutTun() throws Exception {
    Path binary = binary();
    int port;
    try (ServerSocket listener = new ServerSocket(0)) {
      port = listener.getLocalPort();
    }
    var config = JsonFiles.mapper().createObjectNode();
    config
        .putArray("services")
        .addObject()
        .put("type", "api")
        .put("listen", "127.0.0.1")
        .put("listen_port", port)
        .put("secret", "integration-secret")
        .put("dashboard", false);
    Path file = directory.resolve("api.json");
    JsonFiles.writeAtomic(file, config);
    try (EngineProcess process = EngineProcess.start(binary, file);
        EngineApi api = new EngineApi(port, "integration-secret");
        EngineApi wrong = new EngineApi(port, "wrong-secret")) {
      boolean ready = false;
      for (int attempt = 0; attempt < 20; attempt++) {
        try {
          api.checkVersion();
          ready = true;
          break;
        } catch (IOException exception) {
          Thread.sleep(100);
        }
      }
      assertTrue(ready, "Real engine API did not report version");
      assertThrows(IOException.class, wrong::checkVersion);
      process.stop();
      assertTrue(!process.isAlive(), "Owned engine process remained alive after stop");
    }
  }

  private static Path binary() {
    Path path =
        Path.of(System.getProperty("wisprail.engine", "../build/target/engine/sing-box.exe"))
            .toAbsolutePath();
    assumeTrue(Files.isRegularFile(path), "Download pinned engine before integration tests");
    return path;
  }

  private static String trustedCa() throws Exception {
    Path store = Path.of(System.getProperty("java.home"), "lib/security/cacerts");
    KeyStore keys = KeyStore.getInstance(store.toFile(), "changeit".toCharArray());
    var certificate = keys.getCertificate(keys.aliases().nextElement());
    return "-----BEGIN CERTIFICATE-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded())
        + "\n-----END CERTIFICATE-----\n";
  }
}
