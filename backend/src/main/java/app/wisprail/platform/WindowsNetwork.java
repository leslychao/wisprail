package app.wisprail.platform;

import app.wisprail.profile.Ipv4Network;
import app.wisprail.profile.VpnProfile;
import app.wisprail.storage.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WindowsNetwork implements NetworkPlatform {
  private final Path script;
  private final CommandRunner commands = new CommandRunner();

  public WindowsNetwork(Path script) {
    this.script = script;
  }

  private JsonNode invoke(String mode, UUID session, List<String> domains, String address)
      throws IOException, InterruptedException {
    byte[] input =
        JsonFiles.mapper()
            .writeValueAsBytes(
                Map.of(
                    "mode",
                    mode,
                    "session",
                    session == null ? "" : session.toString(),
                    "domains",
                    domains,
                    "address",
                    address));
    Path powershell =
        Path.of(
            System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    var result =
        commands.run(
            List.of(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                script.toString()),
            input,
            Duration.ofSeconds(20));
    if (result.exitCode() != 0) {
      throw new IOException("Не удалось выполнить системную DNS-операцию: " + mode);
    }
    return JsonFiles.mapper().readTree(result.output().strip());
  }

  @Override
  public Inspection inspect() throws IOException, InterruptedException {
    JsonNode result = invoke("inspect", null, List.of(), "");
    List<Route> routes = new ArrayList<>();
    for (JsonNode route : result.path("routes")) {
      routes.add(new Route(route.path("prefix").asText(), route.path("interfaceName").asText()));
    }
    List<DnsRule> domains = new ArrayList<>();
    for (JsonNode rule : result.path("dnsRules")) {
      String owner = rule.path("owner").asText();
      domains.add(
          new DnsRule(
              rule.path("domain").asText(), owner.isEmpty() ? null : UUID.fromString(owner)));
    }
    return new Inspection(routes, domains);
  }

  @Override
  public void installDns(UUID session, List<String> domains, String address)
      throws IOException, InterruptedException {
    if (!domains.isEmpty()) {
      invoke("install", session, domains, address);
    }
  }

  @Override
  public void removeDns(UUID session) throws IOException, InterruptedException {
    invoke("remove", session, List.of(), "");
  }

  @Override
  public boolean dnsRemoved(UUID session) throws IOException, InterruptedException {
    return invoke("exists", session, List.of(), "").path("count").asInt(-1) == 0;
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

  @Override
  public void close() {}
}
