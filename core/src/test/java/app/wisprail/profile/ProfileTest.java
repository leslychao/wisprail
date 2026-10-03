package app.wisprail.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileTest {
  @TempDir Path directory;

  public static VpnProfile profile(String name) {
    return new VpnProfile(
        1,
        UUID.randomUUID(),
        1,
        name,
        "vpn.example.com",
        443,
        VpnProfile.Protocol.VLESS,
        null,
        VpnProfile.Vless.defaults(),
        List.of("10.20.0.0/16"),
        new VpnProfile.Dns("10.20.0.53", List.of("corp.example")),
        "",
        "");
  }

  @Test
  void networksRejectIpv6AndCoverAllAddressesEvenWhenSplitAndDuplicated() {
    assertEquals("10.20.0.0/16", Ipv4Network.parse("10.20.42.8/16").toString());
    assertThrows(IllegalArgumentException.class, () -> Ipv4Network.parse("2001:db8::/32"));
    assertThrows(IllegalArgumentException.class, () -> Ipv4Network.parseAddress("010.0.0.1"));
    assertThrows(IllegalArgumentException.class, () -> Ipv4Network.parseAddress("256.0.0.1"));
    assertTrue(
        Ipv4Network.coversInternet(
            List.of(
                Ipv4Network.parse("0.0.0.0/1"),
                Ipv4Network.parse("128.0.0.0/2"),
                Ipv4Network.parse("192.0.0.0/2"),
                Ipv4Network.parse("10.0.0.0/8"))));
    assertFalse(
        Ipv4Network.coversInternet(
            List.of(Ipv4Network.parse("0.0.0.0/1"), Ipv4Network.parse("128.0.0.0/2"))));
    assertTrue(Ipv4Network.parse("255.255.255.255/32").contains(0xffffffffL));
  }

  @Test
  void domainSuffixHasAnExplicitLabelBoundary() {
    assertTrue(DomainName.matches("CORP.EXAMPLE.", "corp.example"));
    assertTrue(DomainName.matches("a.corp.example", "corp.example"));
    assertFalse(DomainName.matches("evilcorp.example", "corp.example"));
    assertFalse(DomainName.matches("corp.example.evil", "corp.example"));
    assertThrows(IllegalArgumentException.class, () -> DomainName.normalize("*.corp.example"));
  }

  @Test
  void strictVlessImportRejectsTransportsUnknownFieldsAndSecretBearingErrors() {
    ProfileImport importer = new ProfileImport();
    String base =
        "vless://00000000-0000-4000-8000-000000000001@vpn.example.com:443?security=tls&sni=vpn.example.com";
    var draft = importer.fromLink(base + "#Work");
    assertEquals("Work", draft.profile().name());
    assertEquals(0, draft.profile().revision());
    assertTrue(draft.profile().networks().isEmpty());
    for (String suffix :
        List.of("&type=ws", "&type=grpc", "&allowInsecure=1", "&sni=evil.example")) {
      assertThrows(IllegalArgumentException.class, () -> importer.fromLink(base + suffix));
    }
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> importer.fromLink("vless://VERY_SECRET@vpn.example.com?security=tls"));
    assertFalse(error.getMessage().contains("VERY_SECRET"));
  }

  @Test
  void openVpnImportRejectsScriptsAndReferencesOutsideSourceDirectory() throws Exception {
    Path source = directory.resolve("profile.ovpn");
    Files.writeString(source, "client\nremote vpn.example.com 1194\nup evil.exe\n");
    assertThrows(IOException.class, () -> new ProfileImport().fromFile(source));
    Files.writeString(source, "client\nremote vpn.example.com 1194\nca ../outside.pem\n");
    assertThrows(IOException.class, () -> new ProfileImport().fromFile(source));
  }

  @Test
  void exportDoesNotIncludeSecretReferenceOrCredentials() throws Exception {
    VpnProfile profile =
        profile("Corporate").withIdentity(UUID.randomUUID(), 5, "Corporate", "SECRET_REFERENCE");
    Path file = directory.resolve("export.json");
    new ProfileImport().export(file, profile);
    String json = Files.readString(file);
    assertFalse(json.contains("SECRET_REFERENCE"));
    var imported = new ProfileImport().fromFile(file);
    assertEquals("Corporate", imported.profile().name());
    assertEquals(ProfileSecrets.empty(), imported.secrets());
    assertFalse(profile.id().equals(imported.profile().id()));
  }
}
