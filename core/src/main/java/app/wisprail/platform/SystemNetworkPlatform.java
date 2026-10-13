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
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
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

  record Route(Ipv4Cidr network, String interfaceName) {}

  record Ipv6Network(BigInteger address, int prefix) {
    Ipv6Network {
      if (address.signum() < 0 || address.bitLength() > 128 || prefix < 0 || prefix > 128) {
        throw new IllegalArgumentException("Некорректная IPv6-сеть");
      }
      address = address.shiftRight(128 - prefix).shiftLeft(128 - prefix);
    }

    static Ipv6Network parse(String cidr) throws IOException {
      String[] parts = cidr.split("/", -1);
      String literal = parts[0].split("%", 2)[0];
      if (parts.length > 2 || !literal.matches("[0-9a-fA-F:.]+") || !literal.contains(":")) {
        throw new IOException("Некорректный системный IPv6-маршрут");
      }
      try {
        InetAddress address = InetAddress.getByName(literal);
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
          byte[] mapped = new byte[16];
          mapped[10] = (byte) 255;
          mapped[11] = (byte) 255;
          System.arraycopy(bytes, 0, mapped, 12, 4);
          bytes = mapped;
        }
        return new Ipv6Network(
            new BigInteger(1, bytes), parts.length == 2 ? Integer.parseInt(parts[1]) : 128);
      } catch (UnknownHostException | IllegalArgumentException exception) {
        throw new IOException("Некорректный системный IPv6-маршрут", exception);
      }
    }

    BigInteger lastAddress() {
      return address.add(BigInteger.ONE.shiftLeft(128 - prefix).subtract(BigInteger.ONE));
    }

    boolean overlaps(Ipv6Network other) {
      return address.compareTo(other.lastAddress()) <= 0
          && other.address().compareTo(lastAddress()) <= 0;
    }
  }

  record Ipv6Route(Ipv6Network network, String interfaceName) {}

  record Snapshot(
      List<Route> routes, List<Ipv6Route> ipv6Routes, Set<String> interfaces, JsonObject dns) {}

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
    return prepare(profile, operationId, servers, inspect());
  }

  static NetworkPlan prepare(
      Profile profile, UUID operationId, List<String> servers, Snapshot snapshot)
      throws IOException {
    boolean fullTunnel = profile.fullTunnel();
    List<Ipv4Cidr> selected = profile.networks().stream().map(Ipv4Cidr::parse).toList();
    long poolAddress = Ipv4Cidr.parseAddress("198.18.0.0");
    for (int index = 0; index < 32768; index++) {
      long networkAddress = poolAddress + index * 4L;
      Ipv4Cidr candidate = new Ipv4Cidr(networkAddress, 30);
      boolean collision =
          (!fullTunnel && selected.stream().anyMatch(candidate::overlaps))
              || servers.stream().anyMatch(candidate::contains)
              || snapshot.routes().stream()
                  .anyMatch(
                      route -> route.network().prefix() > 1 && candidate.overlaps(route.network()));
      if (collision) {
        continue;
      }
      String tunIpv6Cidr =
          fullTunnel ? "fdfe:dcba:9876:" + Integer.toHexString(index) + "::1/126" : "";
      if (fullTunnel) {
        Ipv6Network ipv6Candidate = Ipv6Network.parse(tunIpv6Cidr);
        if (snapshot.ipv6Routes().stream()
            .anyMatch(
                route -> route.network().prefix() > 1 && ipv6Candidate.overlaps(route.network()))) {
          continue;
        }
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
          profile.domains(),
          tunIpv6Cidr);
    }
    throw new IOException("Не удалось выделить свободные служебные диапазоны для TUN");
  }

  @Override
  public void checkConflicts(NetworkPlan plan) throws IOException {
    checkConflicts(plan, inspect(), ownedInterfaces());
  }

  void checkConflicts(NetworkPlan plan, Snapshot snapshot, Set<String> ownedInterfaces)
      throws IOException {
    if (snapshot.interfaces().contains(plan.interfaceName())) {
      throw new IOException("Служебный сетевой интерфейс уже занят");
    }
    Ipv4Cidr tun = Ipv4Cidr.parse(plan.tunCidr());
    boolean fullTunnel = Profile.isFullTunnel(plan.selectedNetworks());
    List<Ipv4Cidr> selected = plan.selectedNetworks().stream().map(Ipv4Cidr::parse).toList();
    for (Route route : snapshot.routes()) {
      if (ownedInterfaces.contains(route.interfaceName())) {
        continue;
      }
      if (fullTunnel && route.network().prefix() == 1) {
        throw new IOException("Другой VPN уже направляет весь IPv4-трафик через свои маршруты");
      }
      if (route.network().prefix() <= 1) {
        // Defaults stay untouched; selected-network profiles also allow existing /1 pairs.
        continue;
      }
      if (route.network().overlaps(tun)
          || (!fullTunnel
              && selected.stream().anyMatch(network -> network.overlaps(route.network())))) {
        throw new IOException(
            "Сеть профиля пересекается с существующим маршрутом: " + route.network().canonical());
      }
    }
    if (fullTunnel) {
      Ipv6Network tunIpv6 = Ipv6Network.parse(plan.tunIpv6Cidr());
      for (Ipv6Route route : snapshot.ipv6Routes()) {
        if (ownedInterfaces.contains(route.interfaceName())) {
          continue;
        }
        if (route.network().prefix() == 1) {
          throw new IOException("Другой VPN уже направляет весь IPv6-трафик через свои маршруты");
        }
        if (route.network().prefix() > 1 && tunIpv6.overlaps(route.network())) {
          throw new IOException("Служебная IPv6-сеть TUN пересекается с существующим маршрутом");
        }
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
    verifyRoutes(plan, profile, snapshot);
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

  static void verifyRoutes(NetworkPlan plan, Profile profile, Snapshot snapshot)
      throws IOException {
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
    if (profile.fullTunnel() && !coversEntireIpv6(snapshot.ipv6Routes(), plan.interfaceName())) {
      throw new IOException("Не подтверждён маршрут всего IPv6-трафика через VPN");
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
    if (hasOwnedRoutes(snapshot, ownedInterface)) {
      throw new IOException("Не подтверждена очистка собственных маршрутов VPN");
    }
    Files.delete(journal);
  }

  static boolean hasOwnedRoutes(Snapshot snapshot, String interfaceName) {
    return snapshot.routes().stream().anyMatch(route -> route.interfaceName().equals(interfaceName))
        || snapshot.ipv6Routes().stream()
            .anyMatch(route -> route.interfaceName().equals(interfaceName));
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
      nextAddress = Math.max(nextAddress, route.lastAddress() + 1);
      if (nextAddress > selected.lastAddress()) {
        return true;
      }
    }
    return false;
  }

  private static boolean coversEntireIpv6(List<Ipv6Route> routes, String interfaceName) {
    List<Ipv6Network> coverage =
        routes.stream()
            .filter(route -> route.interfaceName().equals(interfaceName))
            .map(Ipv6Route::network)
            .sorted(Comparator.comparing(Ipv6Network::address))
            .toList();
    BigInteger nextAddress = BigInteger.ZERO;
    for (Ipv6Network route : coverage) {
      if (route.address().compareTo(nextAddress) > 0) {
        return false;
      }
      nextAddress = nextAddress.max(route.lastAddress().add(BigInteger.ONE));
    }
    return nextAddress.equals(BigInteger.ONE.shiftLeft(128));
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
    List<Ipv6Route> ipv6Routes = new ArrayList<>();
    Set<String> interfaces = new HashSet<>();
    if (PlatformServices.isWindows()) {
      JsonObject data = runWindows("Inspect", new JsonObject());
      for (JsonElement item : data.getAsJsonArray("routes")) {
        JsonObject route = item.getAsJsonObject();
        String destination = route.get("DestinationPrefix").getAsString();
        String interfaceName = route.get("InterfaceAlias").getAsString();
        if (destination.contains(":")) {
          ipv6Routes.add(new Ipv6Route(Ipv6Network.parse(destination), interfaceName));
        } else {
          routes.add(new Route(Ipv4Cidr.parse(destination), interfaceName));
        }
      }
      for (JsonElement item : data.getAsJsonArray("interfaces")) {
        interfaces.add(item.getAsJsonObject().get("InterfaceAlias").getAsString());
      }
      return new Snapshot(routes, ipv6Routes, interfaces, data);
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
    String ipv6Table =
        PlatformCommand.run(
            List.of("/usr/sbin/netstat", "-rn", "-f", "inet6"),
            new byte[0],
            Duration.ofSeconds(10));
    for (String line : ipv6Table.lines().toList()) {
      String[] fields = line.strip().split("\\s+");
      if (fields.length < 4 || (!fields[0].equals("default") && !fields[0].contains(":"))) {
        continue;
      }
      String destination = fields[0].equals("default") ? "::/0" : fields[0];
      ipv6Routes.add(new Ipv6Route(Ipv6Network.parse(destination), fields[3]));
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
    return new Snapshot(routes, ipv6Routes, interfaces, dns);
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
