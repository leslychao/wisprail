package app.wisprail.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.profile.Ipv4Cidr;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemNetworkPlatformTest {
  @Test
  void acceptsSplitRoutesAndServerExclusionThatTogetherCoverSelectedNetwork() {
    assertTrue(
        SystemNetworkPlatform.covered(
            Ipv4Cidr.parse("10.0.0.0/29"),
            List.of(
                Ipv4Cidr.parse("10.0.0.0/31"),
                Ipv4Cidr.parse("10.0.0.2/32"),
                Ipv4Cidr.parse("10.0.0.3/32"),
                Ipv4Cidr.parse("10.0.0.4/30"))));
  }

  @Test
  void refusesOneAddressGapAndAcceptsLastIpv4AddressWithoutOverflow() {
    assertFalse(
        SystemNetworkPlatform.covered(
            Ipv4Cidr.parse("10.0.0.0/29"),
            List.of(
                Ipv4Cidr.parse("10.0.0.0/31"),
                Ipv4Cidr.parse("10.0.0.3/32"),
                Ipv4Cidr.parse("10.0.0.4/30"))));
    assertTrue(
        SystemNetworkPlatform.covered(
            Ipv4Cidr.parse("255.255.255.255/32"), List.of(Ipv4Cidr.parse("255.255.255.255/32"))));
  }
}
