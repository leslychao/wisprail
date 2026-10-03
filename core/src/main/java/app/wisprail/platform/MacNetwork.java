package app.wisprail.platform;

import app.wisprail.profile.Ipv4Network;
import app.wisprail.profile.VpnProfile;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MacNetwork implements NetworkPlatform {
  private final MacSystemConfiguration configuration;
  private final CommandRunner commands = new CommandRunner();

  public MacNetwork() throws IOException {
    configuration = new MacSystemConfiguration();
  }

  @Override
  public Inspection inspect() throws IOException, InterruptedException {
    var result =
        commands.run(
            List.of("/usr/sbin/netstat", "-rn", "-f", "inet"), new byte[0], Duration.ofSeconds(10));
    if (result.exitCode() != 0) {
      throw new IOException("Не удалось прочитать маршруты macOS");
    }
    List<Route> routes = new ArrayList<>();
    for (String line : result.output().split("\\R")) {
      String[] fields = line.strip().split("\\s+");
      if (fields.length < 4 || !(fields[0].matches("[0-9./]+") || fields[0].equals("default"))) {
        continue;
      }
      String prefix = normalizeRoute(fields[0]);
      String interfaceName = "";
      for (String field : fields) {
        if (field.matches("(?:utun|en|lo|bridge|ppp|ipsec)[0-9]+")) {
          interfaceName = field;
        }
      }
      if (!interfaceName.isEmpty()) {
        routes.add(new Route(prefix, interfaceName));
      }
    }
    return new Inspection(routes, configuration.domains());
  }

  static String normalizeRoute(String route) {
    if (route.equals("default")) {
      return "0.0.0.0/0";
    }
    String[] parts = route.split("/");
    String[] octets = parts[0].split("\\.");
    String address = parts[0] + ".0".repeat(4 - octets.length);
    int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : octets.length * 8;
    return Ipv4Network.parse(address + "/" + prefix).toString();
  }

  @Override
  public void installDns(UUID session, List<String> domains, String address) throws IOException {
    if (!domains.isEmpty()) {
      configuration.install(key(session), domains, address);
    }
  }

  @Override
  public void removeDns(UUID session) throws IOException {
    configuration.remove(key(session));
  }

  @Override
  public boolean dnsRemoved(UUID session) throws IOException {
    return !configuration.exists(key(session));
  }

  @Override
  public void verifyRoutes(VpnProfile profile, String interfaceName)
      throws IOException, InterruptedException {
    var routes = inspect().routes();
    for (String network : profile.networks()) {
      Ipv4Network selected = Ipv4Network.parse(network);
      if (routes.stream()
          .filter(route -> route.interfaceName().equals(interfaceName))
          .map(route -> Ipv4Network.parse(route.prefix()))
          .noneMatch(
              route ->
                  route.contains(selected.address()) && route.contains(selected.lastAddress()))) {
        throw new IOException("Маршруты профиля не подтверждены на TUN");
      }
    }
  }

  @Override
  public boolean routesRemoved(String interfaceName) throws IOException, InterruptedException {
    return inspect().routes().stream()
        .noneMatch(route -> route.interfaceName().equals(interfaceName));
  }

  private static String key(UUID session) {
    return "State:/Network/Service/app.wisprail." + session + "/DNS";
  }

  @Override
  public void close() {
    configuration.close();
  }
}
