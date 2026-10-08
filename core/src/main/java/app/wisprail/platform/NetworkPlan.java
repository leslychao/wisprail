package app.wisprail.platform;

import java.util.List;
import java.util.UUID;

public record NetworkPlan(
    UUID operationId,
    String interfaceName,
    String tunCidr,
    String dnsAddress,
    List<String> serverAddresses,
    List<String> routeExclusions,
    List<String> selectedNetworks,
    List<String> domains) {
  public NetworkPlan {
    serverAddresses = List.copyOf(serverAddresses);
    routeExclusions = List.copyOf(routeExclusions);
    selectedNetworks = List.copyOf(selectedNetworks);
    domains = List.copyOf(domains);
  }

  public NetworkPlan(
      UUID operationId,
      String interfaceName,
      String tunCidr,
      String dnsAddress,
      List<String> serverAddresses,
      List<String> routeExclusions) {
    this(
        operationId,
        interfaceName,
        tunCidr,
        dnsAddress,
        serverAddresses,
        routeExclusions,
        List.of(),
        List.of());
  }
}
