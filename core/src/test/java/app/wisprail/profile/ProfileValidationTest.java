package app.wisprail.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.storage.JsonCodec;
import com.google.gson.JsonElement;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileValidationTest {
  @Test
  void cidrUsesUnsignedIpv4AndRejectsNoncanonicalNumericInput() {
    assertEquals("255.255.255.255/32", Ipv4Cidr.parse("255.255.255.255").canonical());
    assertEquals("10.20.0.0/16", Ipv4Cidr.parse("10.20.5.17/16").canonical());
    assertThrows(IllegalArgumentException.class, () -> Ipv4Cidr.parse("127.1"));
    assertThrows(IllegalArgumentException.class, () -> Ipv4Cidr.parse("010.1.2.3"));
    assertThrows(IllegalArgumentException.class, () -> Ipv4Cidr.parse("::1"));
    assertThrows(IllegalArgumentException.class, () -> Ipv4Cidr.parse("10.0.0.0/33"));
  }

  @Test
  void detectsCombinedFullTunnelWithoutRejectingOverlapsWithGaps() {
    assertTrue(
        Ipv4Cidr.coversEntireIpv4(
            List.of(Ipv4Cidr.parse("0.0.0.0/1"), Ipv4Cidr.parse("128.0.0.0/1"))));
    assertTrue(Ipv4Cidr.coversEntireIpv4(List.of(Ipv4Cidr.parse("0.0.0.0/0"))));
    assertFalse(
        Ipv4Cidr.coversEntireIpv4(
            List.of(Ipv4Cidr.parse("0.0.0.0/2"), Ipv4Cidr.parse("128.0.0.0/1"))));
    assertFalse(Ipv4Cidr.coversEntireIpv4(List.of()));
  }

  @Test
  void domainMatchingRespectsLabelBoundaryAndNormalizesIdn() {
    assertTrue(DomainNames.matches("CORP.example.", "corp.example"));
    assertTrue(DomainNames.matches("a.corp.example", "corp.example"));
    assertFalse(DomainNames.matches("evilcorp.example", "corp.example"));
    assertEquals("xn--e1afmkfd.xn--p1ai", DomainNames.normalize("пример.рф"));
    assertThrows(IllegalArgumentException.class, () -> DomainNames.normalize("*.corp.example"));
    assertThrows(
        IllegalArgumentException.class, () -> DomainNames.normalize("https://corp.example"));
  }

  @Test
  void corporateDnsMustBeExplicitlyRoutedAndVlessNeedsReadinessTarget() {
    Profile valid = ProfileFixtures.vless("VPN");
    assertTrue(ProfileValidator.validate(valid, ProfileFixtures.secrets()).isEmpty());
    Profile outsideDns =
        new Profile(
            valid.id(),
            0,
            valid.name(),
            valid.server(),
            valid.port(),
            valid.settings(),
            valid.networks(),
            "192.168.10.1",
            valid.domains(),
            "",
            "");
    assertTrue(
        ProfileValidator.validate(outsideDns, ProfileFixtures.secrets()).stream()
            .anyMatch(issue -> issue.field().equals("dns")));
    Profile noProbe =
        new Profile(
            valid.id(),
            0,
            valid.name(),
            valid.server(),
            valid.port(),
            valid.settings(),
            valid.networks(),
            "",
            List.of(),
            "",
            "");
    assertTrue(
        ProfileValidator.validate(noProbe, ProfileFixtures.secrets()).stream()
            .anyMatch(issue -> issue.field().equals("healthUrl")));
  }

  @Test
  void fullVlessNeedsNoManualDnsButKeepsAccessAndRoutingValidation() {
    Profile selected = ProfileFixtures.vless("VPN");
    Profile full = withNetworks(selected, List.of("0.0.0.0/0"));
    assertTrue(full.fullTunnel());
    assertTrue(ProfileValidator.validate(full, ProfileFixtures.secrets()).isEmpty());
    assertTrue(
        ProfileValidator.validate(full, ProfileSecrets.empty()).stream()
            .anyMatch(issue -> issue.field().equals("uuid")));
    for (List<String> networks :
        List.of(
            List.<String>of(),
            List.of("0.0.0.0/1", "128.0.0.0/1"),
            List.of("0.0.0.0/0", "10.0.0.0/8"))) {
      assertTrue(
          ProfileValidator.validate(withNetworks(selected, networks), ProfileFixtures.secrets())
              .stream()
              .anyMatch(issue -> issue.field().equals("networks")));
    }
  }

  @Test
  void fullInternetSurvivesCanonicalSecretFreeProfileDocument() {
    Profile full = withNetworks(ProfileFixtures.vless("VPN"), List.of("0.0.0.0/0"));
    String json = JsonCodec.gson().toJson(new ProfileDocument(full));
    Profile restored =
        JsonCodec.readProfileDocument(JsonCodec.gson().fromJson(json, JsonElement.class))
            .profile();
    assertEquals(full, restored);
    assertTrue(restored.fullTunnel());
    assertTrue(ProfileValidator.validate(restored, ProfileFixtures.secrets()).isEmpty());
  }

  private static Profile withNetworks(Profile source, List<String> networks) {
    return new Profile(
        source.id(),
        source.revision(),
        source.name(),
        source.server(),
        source.port(),
        source.settings(),
        networks,
        "",
        List.of(),
        "",
        source.secretRef());
  }

  @Test
  void profileJsonPreservesTypedProtocolButContainsNoCredential() {
    Profile profile = ProfileFixtures.vless("Профиль");
    String encoded = JsonCodec.gson().toJson(new ProfileDocument(profile));
    assertEquals(profile, JsonCodec.gson().fromJson(encoded, ProfileDocument.class).profile());
    assertFalse(encoded.contains(ProfileFixtures.secrets().uuid()));
    assertEquals("ProfileSecrets[REDACTED]", ProfileFixtures.secrets().toString());
  }

  @Test
  void grpcAllowsEmptyAndSimpleServiceNamesButRejectsIncompatibleTransportSettings() {
    for (String serviceName : List.of("", "Example.Tunnel-01")) {
      Profile profile =
          ProfileFixtures.vless(
              "gRPC",
              new VlessSettings(TlsMode.TLS, "", "", "", "chrome", "", "grpc", serviceName));
      assertTrue(ProfileValidator.validate(profile, ProfileFixtures.secrets()).isEmpty());
    }
    assertVlessIssue(
        new VlessSettings(TlsMode.TLS, "", "", "", "chrome", "", "ws", ""), "transport");
    assertVlessIssue(
        new VlessSettings(TlsMode.TLS, "", "", "", "chrome", "", "tcp", "Tunnel"), "serviceName");
    assertVlessIssue(
        new VlessSettings(TlsMode.TLS, "", "", "", "chrome", "xtls-rprx-vision", "grpc", ""),
        "flow");
    for (String serviceName : List.of("/custom/Tun", "service\nname", "x".repeat(1025))) {
      assertVlessIssue(
          new VlessSettings(TlsMode.TLS, "", "", "", "chrome", "", "grpc", serviceName),
          "serviceName");
    }
  }

  private static void assertVlessIssue(VlessSettings settings, String field) {
    assertTrue(
        ProfileValidator.validate(ProfileFixtures.vless("VPN", settings), ProfileFixtures.secrets())
            .stream()
            .anyMatch(issue -> issue.field().equals(field)));
  }
}
