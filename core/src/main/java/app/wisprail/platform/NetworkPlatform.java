package app.wisprail.platform;

import app.wisprail.profile.VpnProfile;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public interface NetworkPlatform extends AutoCloseable {
  record Route(String prefix, String interfaceName) {}

  record DnsRule(String domain, UUID owner) {}

  record Inspection(List<Route> routes, List<DnsRule> dnsRules) {
    public Inspection {
      routes = List.copyOf(routes);
      dnsRules = List.copyOf(dnsRules);
    }
  }

  Inspection inspect() throws IOException, InterruptedException;

  void installDns(UUID session, List<String> domains, String address)
      throws IOException, InterruptedException;

  void removeDns(UUID session) throws IOException, InterruptedException;

  boolean dnsRemoved(UUID session) throws IOException, InterruptedException;

  void verifyRoutes(VpnProfile profile, String interfaceName)
      throws IOException, InterruptedException;

  boolean routesRemoved(String interfaceName) throws IOException, InterruptedException;

  @Override
  void close() throws IOException;
}
