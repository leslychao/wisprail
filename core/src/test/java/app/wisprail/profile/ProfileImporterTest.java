package app.wisprail.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.storage.JsonCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileImporterTest {
  @TempDir Path directory;

  @Test
  void vlessImportsRealityWithoutInventingNetworks() {
    ImportResult result =
        new ProfileImporter()
            .importVless(
                "vless://51fc8016-0547-455f-9fb5-b224034fa67c@vpn.example.test:443"
                    + "?type=tcp&security=reality&sni=example.test&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                    + "&sid=00&fp=chrome&flow=xtls-rprx-vision#%D0%A0%D0%B0%D0%B1%D0%BE%D1%82%D0%B0");
    assertEquals("Работа", result.profile().name());
    assertTrue(result.profile().networks().isEmpty());
    assertEquals(TlsMode.REALITY, ((VlessSettings) result.profile().settings()).security());
    assertEquals(ProfileFixtures.secrets().uuid(), result.secrets().uuid());
  }

  @Test
  void unsupportedTransportAndParametersDoNotLeakSecretInError() {
    for (String query :
        List.of(
            "type=ws&security=tls",
            "security=tls&allowInsecure=1",
            "security=tls&security=reality")) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  new ProfileImporter()
                      .importVless(
                          "vless://"
                              + ProfileFixtures.secrets().uuid()
                              + "@vpn.example.test:443?"
                              + query));
      assertFalse(error.getMessage().contains(ProfileFixtures.secrets().uuid()));
    }
  }

  @Test
  void invalidVlessPartsHaveDistinctSafeExplanations() {
    String prefix = "vless://" + ProfileFixtures.secrets().uuid() + "@vpn.example.test:443";
    assertEquals(
        "Поддерживается только VLESS TCP без дополнительного encryption",
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProfileImporter().importVless(prefix + "?type=ws&security=tls"))
            .getMessage());
    assertEquals(
        "Ссылка должна начинаться с vless://",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    new ProfileImporter()
                        .importVless(prefix.replace("vless:", "https:") + "?security=tls"))
            .getMessage());
    assertEquals(
        "Некорректный UUID VLESS",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    new ProfileImporter()
                        .importVless(
                            "vless://private-invalid-id@vpn.example.test" + "?security=tls"))
            .getMessage());
    assertEquals(
        "Некорректный формат VLESS-ссылки",
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProfileImporter().importVless(prefix + "?sni=%wrong&security=tls"))
            .getMessage());
  }

  @Test
  void openVpnMapsRoutesAndIgnoresRedirectOnlyWithExplicitNotice() throws IOException {
    Path source = directory.resolve("office.ovpn");
    Files.writeString(
        source,
        """
        client
        dev tun
        proto udp
        remote vpn.example.test 1194
        route 10.20.0.0 255.255.0.0
        dhcp-option DNS 10.20.0.53
        dhcp-option DOMAIN corp.example
        redirect-gateway def1
        <ca>
        certificate-placeholder-for-import-only
        </ca>
        """);
    ImportResult imported = new ProfileImporter().importFile(source);
    assertEquals(List.of("10.20.0.0/16"), imported.profile().networks());
    assertEquals("10.20.0.53", imported.profile().dns());
    assertTrue(imported.notes().stream().anyMatch(note -> note.contains("redirect-gateway")));
    assertTrue(
        ProfileValidator.validate(imported.profile(), imported.secrets()).stream()
            .anyMatch(issue -> issue.field().equals("caCertificate")));
  }

  @Test
  void credentialFileWhitespaceIsPartOfThePassword() throws IOException {
    Path source = directory.resolve("credentials.ovpn");
    Files.writeString(source, "remote vpn.example.test 1194\nauth-user-pass access.txt\n");
    Files.writeString(directory.resolve("access.txt"), "employee\n password with spaces \n");
    ImportResult result = new ProfileImporter().importFile(source);
    assertEquals(" password with spaces ", result.secrets().password());
  }

  @Test
  void scriptsAndEscapingFileReferencesBlockOpenVpnImport() throws IOException {
    Path source = directory.resolve("bad.ovpn");
    Files.writeString(source, "remote vpn.example.test 1194\nup attack.ps1\n");
    assertThrows(IOException.class, () -> new ProfileImporter().importFile(source));
    Files.writeString(source, "remote vpn.example.test 1194\nca ../outside.pem\n");
    assertThrows(IOException.class, () -> new ProfileImporter().importFile(source));
  }

  @Test
  void unknownAndDuplicateJsonPropertiesAreRejected() throws IOException {
    String valid = JsonCodec.gson().toJson(new ProfileDocument(ProfileFixtures.vless("Профиль")));
    Path source = directory.resolve("profile.json");
    Files.writeString(
        source, valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"script\":\"x\""));
    assertThrows(IOException.class, () -> new ProfileImporter().importFile(source));
    Files.writeString(
        source, valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"));
    assertThrows(IOException.class, () -> new ProfileImporter().importFile(source));
    Files.writeString(source, valid.replace("\"flow\":\"\"", "\"flow\":\"\",\"flow\":\"x\""));
    assertThrows(IOException.class, () -> new ProfileImporter().importFile(source));
    Files.writeString(source, valid.replace("\"port\":443", "\"port\":443.5"));
    assertThrows(IOException.class, () -> new ProfileImporter().importFile(source));
    Files.writeString(source, valid.replace("\"port\":443", "\"port\":\"443\""));
    assertThrows(IOException.class, () -> new ProfileImporter().importFile(source));
    Files.writeString(source, valid.replace("\"dns\":\"10.20.0.53\"", "\"dns\":null"));
    assertThrows(IOException.class, () -> new ProfileImporter().importFile(source));
  }

  @Test
  void maximumFileSizeIsEnforcedBeforeParsing() throws IOException {
    Path source = directory.resolve("oversized.ovpn");
    Files.write(source, new byte[ProfileValidator.MAX_TEXT_BYTES + 1]);
    assertThrows(IOException.class, () -> new ProfileImporter().importFile(source));
  }
}
