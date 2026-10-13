package app.wisprail.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.platform.SystemNetworkPlatform.Ipv6Network;
import app.wisprail.platform.SystemNetworkPlatform.Ipv6Route;
import app.wisprail.platform.SystemNetworkPlatform.Route;
import app.wisprail.platform.SystemNetworkPlatform.Snapshot;
import app.wisprail.profile.Ipv4Cidr;
import app.wisprail.profile.Profile;
import app.wisprail.profile.VlessSettings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemNetworkPlatformTest {
  @TempDir Path temporary;

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

  @Test
  void allocatesFullTunnelWithoutTreatingDefaultRouteAsTunCollision() throws Exception {
    Snapshot snapshot =
        snapshot(
            List.of(route("0.0.0.0/0", "ethernet"), route("198.18.0.0/30", "other-vpn")),
            List.of(ipv6Route("fdfe:dcba:9876:1::/126", "other-vpn")),
            Set.of("ethernet", "other-vpn"));

    NetworkPlan plan =
        SystemNetworkPlatform.prepare(
            profile(Profile.FULL_TUNNEL_NETWORKS, List.of()),
            UUID.randomUUID(),
            List.of("89.125.210.180"),
            snapshot);

    assertEquals("198.18.0.9/30", plan.tunCidr());
    assertEquals("fdfe:dcba:9876:2::1/126", plan.tunIpv6Cidr());
    assertEquals(List.of("89.125.210.180/32"), plan.routeExclusions());
  }

  @Test
  void allowsLanRoutesForFullTunnelAndKeepsSelectedNetworkConflictChecks() throws Exception {
    SystemNetworkPlatform platform = new SystemNetworkPlatform(temporary);
    Snapshot snapshot =
        snapshot(
            List.of(route("0.0.0.0/0", "ethernet"), route("192.168.1.0/24", "ethernet")),
            List.of(ipv6Route("fe80::%ethernet/64", "ethernet")),
            Set.of("ethernet"));
    Profile full = profile(Profile.FULL_TUNNEL_NETWORKS, List.of());
    NetworkPlan fullPlan =
        SystemNetworkPlatform.prepare(full, UUID.randomUUID(), List.of(full.server()), snapshot);

    assertDoesNotThrow(() -> platform.checkConflicts(fullPlan, snapshot, Set.of()));

    Profile selected = profile(List.of("192.168.1.0/24"), List.of());
    NetworkPlan selectedPlan =
        SystemNetworkPlatform.prepare(
            selected, UUID.randomUUID(), List.of(selected.server()), snapshot);
    assertThrows(
        IOException.class, () -> platform.checkConflicts(selectedPlan, snapshot, Set.of()));
  }

  @Test
  void stillRejectsFullTunnelAddressCollisionsAndSelectedSplitDnsPolicies() throws Exception {
    SystemNetworkPlatform platform = new SystemNetworkPlatform(temporary);
    Profile full = profile(Profile.FULL_TUNNEL_NETWORKS, List.of());
    NetworkPlan plan =
        SystemNetworkPlatform.prepare(
            full,
            UUID.randomUUID(),
            List.of(full.server()),
            snapshot(List.of(), List.of(), Set.of()));
    Snapshot ipv4Collision =
        snapshot(List.of(route(plan.tunCidr(), "other-vpn")), List.of(), Set.of("other-vpn"));
    Snapshot ipv6Collision =
        snapshot(
            List.of(), List.of(ipv6Route(plan.tunIpv6Cidr(), "other-vpn")), Set.of("other-vpn"));

    assertThrows(
        IOException.class, () -> platform.checkConflicts(plan, ipv4Collision, Set.of()));
    assertThrows(
        IOException.class, () -> platform.checkConflicts(plan, ipv6Collision, Set.of()));

    JsonObject policy =
        PlatformServices.isWindows()
            ? JsonParser.parseString(
                    """
                    {"rules": [], "effective": [{"Namespace": [".example.com"],
                       "NameServers": ["192.168.1.1"]}]}
                    """)
                .getAsJsonObject()
            : JsonParser.parseString(
                    """
                    {"State:/Network/Service/other/DNS":
                       {"SupplementalMatchDomains": ["example.com"]}}
                    """)
                .getAsJsonObject();
    Snapshot dnsCollision = new Snapshot(List.of(), List.of(), Set.of(), policy);
    Profile selected = profile(List.of("192.168.1.0/24"), List.of("example.com"));
    NetworkPlan selectedPlan =
        SystemNetworkPlatform.prepare(
            selected,
            UUID.randomUUID(),
            List.of(selected.server()),
            snapshot(List.of(), List.of(), Set.of()));
    assertThrows(
        IOException.class, () -> platform.checkConflicts(selectedPlan, dnsCollision, Set.of()));
  }

  @Test
  void verifiesBothAddressFamiliesAndServerExclusionForFullTunnel() throws Exception {
    Profile profile = profile(Profile.FULL_TUNNEL_NETWORKS, List.of());
    NetworkPlan plan =
        SystemNetworkPlatform.prepare(
            profile,
            UUID.randomUUID(),
            List.of("255.255.255.255"),
            snapshot(List.of(), List.of(), Set.of()));
    List<Route> ipv4Routes = fullIpv4Routes(plan.interfaceName());
    List<Ipv6Route> ipv6Routes =
        List.of(
            ipv6Route("::/1", plan.interfaceName()),
            ipv6Route("8000::/1", plan.interfaceName()));
    Snapshot installed = snapshot(ipv4Routes, ipv6Routes, Set.of(plan.interfaceName()));

    assertDoesNotThrow(() -> SystemNetworkPlatform.verifyRoutes(plan, profile, installed));
    assertThrows(
        IOException.class,
        () ->
            SystemNetworkPlatform.verifyRoutes(
                plan,
                profile,
                snapshot(
                    ipv4Routes.subList(1, ipv4Routes.size()), ipv6Routes, installed.interfaces())));
    assertThrows(
        IOException.class,
        () ->
            SystemNetworkPlatform.verifyRoutes(
                plan,
                profile,
                snapshot(ipv4Routes, ipv6Routes.subList(0, 1), installed.interfaces())));
    assertThrows(
        IOException.class,
        () ->
            SystemNetworkPlatform.verifyRoutes(
                plan,
                profile,
                snapshot(
                    List.of(route("0.0.0.0/0", plan.interfaceName())),
                    ipv6Routes,
                    installed.interfaces())));
  }

  @Test
  void rejectsExistingVpnDefaultSplitsBeforeTheyCanBeReplaced() throws Exception {
    SystemNetworkPlatform platform = new SystemNetworkPlatform(temporary);
    Profile full = profile(Profile.FULL_TUNNEL_NETWORKS, List.of());
    NetworkPlan plan =
        SystemNetworkPlatform.prepare(
            full,
            UUID.randomUUID(),
            List.of(full.server()),
            snapshot(List.of(), List.of(), Set.of()));

    assertThrows(
        IOException.class,
        () ->
            platform.checkConflicts(
                plan,
                snapshot(List.of(route("0.0.0.0/1", "other-vpn")), List.of(), Set.of("other-vpn")),
                Set.of()));
    assertThrows(
        IOException.class,
        () ->
            platform.checkConflicts(
                plan,
                snapshot(List.of(), List.of(ipv6Route("::/1", "other-vpn")), Set.of("other-vpn")),
                Set.of()));
  }

  @Test
  void includesResidualIpv6RoutesInCleanupOwnershipCheck() throws Exception {
    Snapshot snapshot =
        snapshot(List.of(), List.of(ipv6Route("::/0", "wisprail-test")), Set.of("wisprail-test"));

    assertTrue(SystemNetworkPlatform.hasOwnedRoutes(snapshot, "wisprail-test"));
    assertFalse(SystemNetworkPlatform.hasOwnedRoutes(snapshot, "ethernet"));
  }

  private static Profile profile(List<String> networks, List<String> domains) {
    return new Profile(
        UUID.randomUUID(),
        0,
        "VPN",
        "89.125.210.180",
        8443,
        VlessSettings.defaults(),
        networks,
        "",
        domains,
        "",
        "");
  }

  private static Snapshot snapshot(
      List<Route> routes, List<Ipv6Route> ipv6Routes, Set<String> interfaces) {
    return new Snapshot(
        routes,
        ipv6Routes,
        interfaces,
        JsonParser.parseString("{\"rules\": [], \"effective\": []}").getAsJsonObject());
  }

  private static Route route(String network, String interfaceName) {
    return new Route(Ipv4Cidr.parse(network), interfaceName);
  }

  private static Ipv6Route ipv6Route(String network, String interfaceName) throws IOException {
    return new Ipv6Route(Ipv6Network.parse(network), interfaceName);
  }

  private static List<Route> fullIpv4Routes(String interfaceName) {
    // These CIDRs partition IPv4 while leaving only the final server address outside TUN.
    return IntStream.rangeClosed(1, 32)
        .mapToObj(
            prefix ->
                new Route(
                    new Ipv4Cidr(0x1_0000_0000L - (1L << (33 - prefix)), prefix), interfaceName))
        .toList();
  }
}
