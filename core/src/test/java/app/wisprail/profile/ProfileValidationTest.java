package app.wisprail.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.storage.JsonCodec;
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
  void profileJsonPreservesTypedProtocolButContainsNoCredential() {
    Profile profile = ProfileFixtures.vless("Профиль");
    String encoded = JsonCodec.gson().toJson(new ProfileDocument(profile));
    assertEquals(profile, JsonCodec.gson().fromJson(encoded, ProfileDocument.class).profile());
    assertFalse(encoded.contains(ProfileFixtures.secrets().uuid()));
    assertEquals("ProfileSecrets[REDACTED]", ProfileFixtures.secrets().toString());
  }
}
