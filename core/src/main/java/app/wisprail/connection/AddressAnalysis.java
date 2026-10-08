package app.wisprail.connection;

import java.util.List;

public record AddressAnalysis(
    String input,
    String matchingDomain,
    List<String> addresses,
    List<String> matchingNetworks,
    boolean actualRouteVerified,
    String detail) {
  public AddressAnalysis {
    addresses = List.copyOf(addresses);
    matchingNetworks = List.copyOf(matchingNetworks);
  }
}
