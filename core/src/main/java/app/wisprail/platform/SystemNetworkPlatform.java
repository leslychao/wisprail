package app.wisprail.platform;

import app.wisprail.profile.DomainNames;
import app.wisprail.profile.Ipv4Cidr;
import app.wisprail.profile.Profile;
import app.wisprail.storage.JsonCodec;
import app.wisprail.storage.PrivateFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Real operating-system inspection and ownership-scoped split DNS. Never changes routes. */
public final class SystemNetworkPlatform implements NetworkPlatform {
  private final Path directory;
  private final UUID installationId;
  private final Path windowsScript;

  private record DnsOwnership(String marker, NetworkPlan plan, List<String> ruleIds) {}

  private record Route(Ipv4Cidr network, String interfaceName) {}

  private record Snapshot(List<Route> routes, Set<String> interfaces, JsonObject dns) {}

  public SystemNetworkPlatform(Path stateDirectory) throws IOException {
    directory = PrivateFiles.directory(stateDirectory.resolve("dns"));
    Path identity = directory.resolve("installation-id");
    if (!Files.exists(identity)) {
      PrivateFiles.writeAtomic(
          identity, UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
    }
    installationId =
        UUID.fromString(
            new String(PrivateFiles.read(identity, 64), StandardCharsets.US_ASCII).strip());
    windowsScript = directory.resolve("windows-network.ps1");
    if (PlatformServices.isWindows()) {
      try (var resource =
          SystemNetworkPlatform.class.getResourceAsStream("/platform/windows-network.ps1")) {
        if (resource == null) {
          throw new IOException("Системный компонент Windows отсутствует");
        }
        byte[] script = resource.readNBytes(128 * 1024);
        if (resource.read() != -1) {
          throw new IOException("Слишком большой системный компонент");
        }
        PrivateFiles.writeAtomic(windowsScript, script);
      }
    }
  }

  @Override
  public NetworkPlan prepare(Profile profile, UUID operationId) throws IOException {
    List<String> servers = new ArrayList<>();
    for (InetAddress address : InetAddress.getAllByName(profile.server())) {
      if (address instanceof Inet4Address) {
        servers.add(address.getHostAddress());
      }
    }
    if (servers.isEmpty()) {
      throw new IOException("У сервера VPN нет IPv4-адреса");
    }
    Snapshot snapshot = inspect();
    List<Ipv4Cidr> selected = profile.networks().stream().map(Ipv4Cidr::parse).toList();
    long poolAddress = Ipv4Cidr.parseAddress("198.18.0.0");
    for (int index = 0; index < 32768; index++) {
      long networkAddress = poolAddress + index * 4L;
      Ipv4Cidr candidate = new Ipv4Cidr(networkAddress, 30);
      boolean collision =
          selected.stream().anyMatch(candidate::overlaps)
              || servers.stream().anyMatch(candidate::contains)
              || snapshot.routes().stream()
                  .anyMatch(
                      route -> route.network().prefix() > 1 && candidate.overlaps(route.network()));
      if (collision) {
        continue;
      }
      String interfaceName =
          PlatformServices.isWindows()
              ? "wisprail-" + operationId.toString().substring(0, 8)
              : "utun" + (128 + index);
      if (snapshot.interfaces().contains(interfaceName)) {
        continue;
      }
      List<String> exclusions = servers.stream().map(address -> address + "/32").toList();
      return new NetworkPlan(
          operationId,
          interfaceName,
          Ipv4Cidr.formatAddress(networkAddress + 1) + "/30",
          Ipv4Cidr.formatAddress(networkAddress + 2),
          servers,
          exclusions,
          profile.networks(),
          profile.domains());
    }
    throw new IOException("Нет свободного служебного IPv4-диапазона для TUN");
  }

  @Override
  public void checkConflicts(NetworkPlan plan) throws IOException {
    Snapshot snapshot = inspect();
    Set<String> ownedInterfaces = ownedInterfaces();
    if (snapshot.interfaces().contains(plan.interfaceName())) {
      throw new IOException("Служебный сетевой интерфейс уже занят");
    }
    Ipv4Cidr tun = Ipv4Cidr.parse(plan.tunCidr());
    List<Ipv4Cidr> selected = plan.selectedNetworks().stream().map(Ipv4Cidr::parse).toList();
    for (Route route : snapshot.routes()) {
      if (ownedInterfaces.contains(route.interfaceName()) || route.network().prefix() <= 1) {
        // Existing default paths, including the common pair of /1 routes, remain untouched.
        continue;
      }
      if (route.network().overlaps(tun)
          || selected.stream().anyMatch(network -> network.overlaps(route.network()))) {
        throw new IOException(
            "Сеть профиля пересекается с существующим маршрутом: " + route.network().canonical());
      }
    }
    checkDnsConflicts(snapshot.dns(), plan.domains());
  }

  @Override
  public void reserve(NetworkPlan plan) throws IOException {
    DnsOwnership ownership =
        new DnsOwnership("Wisprail:" + installationId + ":" + plan.operationId(), plan, List.of());
    Path journal = journal(plan.operationId());
    if (Files.exists(journal)) {
      throw new IOException("Не завершена предыдущая операция DNS");
    }
    PrivateFiles.writeAtomic(
        journal, JsonCodec.gson().toJson(ownership).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void applyDns(NetworkPlan plan, Profile profile) throws IOException {
    DnsOwnership ownership = readOwnership(journal(plan.operationId()));
    if (!ownership.plan().equals(plan)) {
      throw new IOException("Журнал сети не соответствует подготовленному профилю");
    }
    if (profile.domains().isEmpty()) {
      return;
    }
    if (PlatformServices.isWindows()) {
      List<String> ids = strings(runWindows("ApplyDns", request(ownership)).get("ids"));
      if (ids.isEmpty() || ids.size() > 4096) {
        throw new IOException("Не подтверждены идентификаторы созданных DNS-правил");
      }
      ownership = new DnsOwnership(ownership.marker(), plan, ids);
      PrivateFiles.writeAtomic(
          journal(plan.operationId()),
          JsonCodec.gson().toJson(ownership).getBytes(StandardCharsets.UTF_8));
    } else {
      MacNative.check(
          MacNative.api()
              .wr_dns_add(
                  plan.operationId().toString(),
                  plan.dnsAddress(),
                  new StringArray(plan.domains().toArray(String[]::new)),
                  plan.domains().size()),
          "Не удалось применить системные DNS-правила");
    }
  }

  @Override
  public void verify(NetworkPlan plan, Profile profile) throws IOException {
    Snapshot snapshot = inspect();
    if (!snapshot.interfaces().contains(plan.interfaceName())) {
      throw new IOException("Сетевой интерфейс VPN отсутствует");
    }
    List<Ipv4Cidr> ownRoutes =
        snapshot.routes().stream()
            .filter(route -> route.interfaceName().equals(plan.interfaceName()))
            .map(Route::network)
            .toList();
    List<Ipv4Cidr> excluded = plan.routeExclusions().stream().map(Ipv4Cidr::parse).toList();
    if (ownRoutes.stream().anyMatch(route -> excluded.stream().anyMatch(route::overlaps))) {
      throw new IOException("Адрес сервера VPN попал в собственные маршруты TUN");
    }
    List<Ipv4Cidr> coverage = new ArrayList<>(ownRoutes);
    coverage.addAll(excluded);
    coverage.sort(Comparator.comparingLong(Ipv4Cidr::address));
    for (String network : profile.networks()) {
      Ipv4Cidr selected = Ipv4Cidr.parse(network);
      if (!covered(selected, coverage)) {
        throw new IOException("Маршрут выбранной сети не подтверждён: " + selected.canonical());
      }
    }
    if (profile.domains().isEmpty()) {
      return;
    }
    if (PlatformServices.isWindows()) {
      JsonArray effective = snapshot.dns().getAsJsonArray("effective");
      for (String suffix : profile.domains()) {
        boolean rootFound = false;
        boolean childrenFound = false;
        for (JsonElement item : effective) {
          JsonObject rule = item.getAsJsonObject();
          if (strings(rule.get("NameServers")).contains(plan.dnsAddress())) {
            rootFound |= strings(rule.get("Namespace")).contains(suffix);
            childrenFound |= strings(rule.get("Namespace")).contains("." + suffix);
          }
        }
        if (!rootFound || !childrenFound) {
          throw new IOException("DNS-правило не действует: проверьте управляемую политику Windows");
        }
      }
    } else {
      String key = "State:/Network/Service/app.wisprail." + plan.operationId() + "/DNS";
      JsonElement value = snapshot.dns().get(key);
      if (value == null
          || !strings(value.getAsJsonObject().get("ServerAddresses"))
              .equals(List.of(plan.dnsAddress()))
          || !strings(value.getAsJsonObject().get("SupplementalMatchDomains"))
              .equals(plan.domains())) {
        throw new IOException("Системный supplemental resolver не подтверждён");
      }
    }
  }

  @Override
  public void cleanup(UUID operationId) throws IOException {
    Path journal = journal(operationId);
    if (!Files.exists(journal)) {
      return;
    }
    DnsOwnership ownership = readOwnership(journal);
    if (!ownership.plan().operationId().equals(operationId)) {
      throw new IOException("Нарушена принадлежность журнала DNS");
    }
    if (!ownership.plan().domains().isEmpty()) {
      if (PlatformServices.isWindows()) {
        if (ownership.ruleIds().isEmpty()) {
          ownership = discoverDnsRules(ownership);
          PrivateFiles.writeAtomic(
              journal, JsonCodec.gson().toJson(ownership).getBytes(StandardCharsets.UTF_8));
        }
        runWindows("RemoveDns", request(ownership));
      } else {
        MacNative.check(
            MacNative.api()
                .wr_dns_remove(
                    operationId.toString(),
                    ownership.plan().dnsAddress(),
                    new StringArray(ownership.plan().domains().toArray(String[]::new)),
                    ownership.plan().domains().size()),
            "Не удалось подтвердить очистку DNS");
      }
    }
    Snapshot snapshot = inspect();
    String ownedInterface = ownership.plan().interfaceName();
    if (snapshot.routes().stream()
        .anyMatch(route -> route.interfaceName().equals(ownedInterface))) {
      throw new IOException("Не подтверждена очистка собственных маршрутов VPN");
    }
    Files.delete(journal);
  }

  @Override
  public void recover() throws IOException {
    try (var files = Files.newDirectoryStream(directory, "*.json")) {
      for (Path file : files) {
        cleanup(readOwnership(file).plan().operationId());
      }
    }
  }

  private Set<String> ownedInterfaces() throws IOException {
    Set<String> result = new HashSet<>();
    try (var files = Files.newDirectoryStream(directory, "*.json")) {
      for (Path file : files) {
        result.add(readOwnership(file).plan().interfaceName());
      }
    }
    return result;
  }

  private DnsOwnership readOwnership(Path file) throws IOException {
    DnsOwnership value =
        JsonCodec.gson()
            .fromJson(
                new String(PrivateFiles.read(file, 2 * 1024 * 1024), StandardCharsets.UTF_8),
                DnsOwnership.class);
    if (value == null
        || value.plan() == null
        || value.marker() == null
        || value.ruleIds() == null
        || value.ruleIds().size() > 4096
        || !value
            .marker()
            .equals("Wisprail:" + installationId + ":" + value.plan().operationId())) {
      throw new IOException("Повреждён журнал принадлежности DNS");
    }
    return value;
  }

  private Path journal(UUID operationId) {
    return directory.resolve(operationId + ".json");
  }

  private JsonObject request(DnsOwnership ownership) {
    JsonObject request = new JsonObject();
    request.addProperty("marker", ownership.marker());
    request.addProperty("server", ownership.plan().dnsAddress());
    request.add("domains", JsonCodec.gson().toJsonTree(ownership.plan().domains()));
    request.add("ids", JsonCodec.gson().toJsonTree(ownership.ruleIds()));
    return request;
  }

  private DnsOwnership discoverDnsRules(DnsOwnership ownership) throws IOException {
    List<String> expected = new ArrayList<>();
    for (String domain : ownership.plan().domains()) {
      expected.add(domain);
      expected.add("." + domain);
    }
    List<String> ids = new ArrayList<>();
    JsonObject data = runWindows("Inspect", new JsonObject());
    for (JsonElement item : data.getAsJsonArray("rules")) {
      JsonObject rule = item.getAsJsonObject();
      if (!strings(rule.get("Comment")).equals(List.of(ownership.marker()))) {
        continue;
      }
      if (!new HashSet<>(strings(rule.get("Namespace"))).equals(new HashSet<>(expected))
          || !strings(rule.get("NameServers")).equals(List.of(ownership.plan().dnsAddress()))) {
        throw new IOException("DNS-объект изменён извне; автоматическая очистка запрещена");
      }
      ids.add(rule.get("Name").getAsString());
    }
    return new DnsOwnership(ownership.marker(), ownership.plan(), List.copyOf(ids));
  }

  /** Sorted installed routes plus explicit server exclusions must cover every selected address. */
  static boolean covered(Ipv4Cidr selected, List<Ipv4Cidr> sortedCoverage) {
    long nextAddress = selected.address();
    for (Ipv4Cidr route : sortedCoverage) {
      if (route.lastAddress() < nextAddress) {
        continue;
      }
      if (route.address() > nextAddress) {
        return false;
      }
      nextAddress = route.lastAddress() + 1;
      if (nextAddress > selected.lastAddress()) {
        return true;
      }
    }
    return false;
  }

  private JsonObject runWindows(String operation, JsonObject request) throws IOException {
    Path powershell =
        Path.of(
            System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
    String output =
        PlatformCommand.run(
            List.of(
                powershell.toString(),
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "RemoteSigned",
                "-File",
                windowsScript.toString(),
                "-Operation",
                operation),
            JsonCodec.gson().toJson(request).getBytes(StandardCharsets.UTF_8),
            Duration.ofSeconds(20));
    return JsonParser.parseString(output.strip()).getAsJsonObject();
  }

  private Snapshot inspect() throws IOException {
    List<Route> routes = new ArrayList<>();
    Set<String> interfaces = new HashSet<>();
    if (PlatformServices.isWindows()) {
      JsonObject data = runWindows("Inspect", new JsonObject());
      for (JsonElement item : data.getAsJsonArray("routes")) {
        JsonObject route = item.getAsJsonObject();
        routes.add(
            new Route(
                Ipv4Cidr.parse(route.get("DestinationPrefix").getAsString()),
                route.get("InterfaceAlias").getAsString()));
      }
      for (JsonElement item : data.getAsJsonArray("interfaces")) {
        interfaces.add(item.getAsJsonObject().get("InterfaceAlias").getAsString());
      }
      return new Snapshot(routes, interfaces, data);
    }
    String table =
        PlatformCommand.run(
            List.of("/usr/sbin/netstat", "-rn", "-f", "inet"), new byte[0], Duration.ofSeconds(10));
    for (String line : table.lines().toList()) {
      String[] fields = line.strip().split("\\s+");
      if (fields.length < 4 || (!fields[0].equals("default") && !fields[0].matches("[0-9].*"))) {
        continue;
      }
      String destination = fields[0].equals("default") ? "0.0.0.0/0" : expandMacNetwork(fields[0]);
      routes.add(new Route(Ipv4Cidr.parse(destination), fields[3]));
    }
    for (NetworkInterface device : Collections.list(NetworkInterface.getNetworkInterfaces())) {
      interfaces.add(device.getName());
    }
    MacNative.Api api = MacNative.api();
    PointerByReference output = new PointerByReference();
    LongByReference length = new LongByReference();
    MacNative.check(api.wr_dns_snapshot(output, length), "Не удалось проверить системный DNS");
    JsonObject dns =
        JsonParser.parseString(
                new String(
                    MacNative.result(api, output, length, 8 * 1024 * 1024), StandardCharsets.UTF_8))
            .getAsJsonObject();
    return new Snapshot(routes, interfaces, dns);
  }

  private static String expandMacNetwork(String value) {
    String[] parts = value.split("/", -1);
    String[] octets = parts[0].split("\\.");
    String address = parts[0] + ".0".repeat(4 - octets.length);
    return address + "/" + (parts.length == 2 ? parts[1] : octets.length * 8);
  }

  private void checkDnsConflicts(JsonObject dns, List<String> domains) throws IOException {
    if (domains.isEmpty()) {
      return;
    }
    List<String> existingDomains = new ArrayList<>();
    if (PlatformServices.isWindows()) {
      Set<String> ownedNamespaces = new HashSet<>();
      for (JsonElement item : dns.getAsJsonArray("rules")) {
        JsonObject rule = item.getAsJsonObject();
        JsonElement comment = rule.get("Comment");
        if (comment != null
            && !comment.isJsonNull()
            && comment.getAsString().startsWith("Wisprail:" + installationId + ":")) {
          for (String namespace : strings(rule.get("Namespace"))) {
            ownedNamespaces.add(namespace + "|" + strings(rule.get("NameServers")));
          }
        }
      }
      for (String collection : List.of("rules", "effective")) {
        for (JsonElement item : dns.getAsJsonArray(collection)) {
          JsonObject rule = item.getAsJsonObject();
          JsonElement comment = rule.get("Comment");
          if (comment != null
              && !comment.isJsonNull()
              && comment.getAsString().startsWith("Wisprail:" + installationId + ":")) {
            continue;
          }
          for (String namespace : strings(rule.get("Namespace"))) {
            if (!ownedNamespaces.contains(namespace + "|" + strings(rule.get("NameServers")))) {
              existingDomains.add(namespace);
            }
          }
        }
      }
    } else {
      for (var entry : dns.entrySet()) {
        if (!entry.getKey().startsWith("State:/Network/Service/app.wisprail.")) {
          existingDomains.addAll(
              strings(entry.getValue().getAsJsonObject().get("SupplementalMatchDomains")));
        }
      }
    }
    for (String existing : existingDomains) {
      String suffix = existing.startsWith(".") ? existing.substring(1) : existing;
      if (suffix.isBlank()) {
        throw new IOException("Управляемая DNS-политика запрещает применение профиля");
      }
      for (String domain : domains) {
        if (DomainNames.matches(domain, suffix) || DomainNames.matches(suffix, domain)) {
          throw new IOException("DNS-домен пересекается с существующей системной политикой");
        }
      }
    }
  }

  private static List<String> strings(JsonElement value) {
    if (value == null || value.isJsonNull()) {
      return List.of();
    }
    if (value.isJsonArray()) {
      List<String> result = new ArrayList<>();
      for (JsonElement item : value.getAsJsonArray()) {
        result.add(item.getAsString());
      }
      return result;
    }
    return List.of(value.getAsString());
  }
}
