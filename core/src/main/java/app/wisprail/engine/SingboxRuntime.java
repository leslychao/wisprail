package app.wisprail.engine;

import app.wisprail.connection.NetworkFailure;
import app.wisprail.connection.NetworkRuntime;
import app.wisprail.platform.CommandRunner;
import app.wisprail.platform.NetworkPlatform;
import app.wisprail.profile.DomainName;
import app.wisprail.profile.Ipv4Network;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.ProfileValidator;
import app.wisprail.profile.VpnProfile;
import app.wisprail.storage.JsonFiles;
import app.wisprail.storage.SecureFiles;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

public final class SingboxRuntime implements NetworkRuntime {
  public record Ownership(
      UUID session, long pid, Instant startedAt, String interfaceName, String directory) {}

  private final Path binary;
  private final Path directory;
  private final NetworkPlatform platform;
  private final CommandRunner commands = new CommandRunner();
  private volatile EngineProcess process;
  private Prepared active;
  private volatile boolean clean = true;

  public SingboxRuntime(Path binary, Path directory, NetworkPlatform platform) throws IOException {
    this.binary = binary.toAbsolutePath();
    this.directory = directory.toAbsolutePath();
    this.platform = platform;
    SecureFiles.directory(this.directory);
    clean = !Files.exists(this.directory.resolve("ownership.json"));
  }

  public void recover() throws IOException, InterruptedException {
    clean = false;
    Path ownershipFile = directory.resolve("ownership.json");
    if (!Files.exists(ownershipFile)) {
      discardOrphanedCandidates();
      clean = true;
      return;
    }
    Ownership ownership = JsonFiles.read(ownershipFile, Ownership.class);
    ProcessHandle handle =
        ownership.pid() > 0 ? ProcessHandle.of(ownership.pid()).orElse(null) : null;
    if (handle != null
        && handle.isAlive()
        && handle.info().startInstant().filter(ownership.startedAt()::equals).isPresent()) {
      String executable = handle.info().command().orElse("");
      if (executable.isEmpty() || !Path.of(executable).toRealPath().equals(binary.toRealPath())) {
        throw new IOException("Невозможно подтвердить принадлежность прежнего процесса");
      }
      handle.destroy();
      try {
        handle.onExit().get(10, TimeUnit.SECONDS);
      } catch (ExecutionException | TimeoutException exception) {
        throw new IOException("Прежний процесс не завершился", exception);
      }
    }
    platform.removeDns(ownership.session());
    if (!platform.dnsRemoved(ownership.session())
        || !platform.routesRemoved(ownership.interfaceName())) {
      throw new IOException("Очистка после предыдущей сессии не подтверждена");
    }
    discardDirectory(directory.resolve(ownership.directory()));
    Files.delete(ownershipFile);
    discardOrphanedCandidates();
    clean = true;
  }

  private void discardOrphanedCandidates() throws IOException {
    try (var entries = Files.list(directory)) {
      for (Path entry : entries.toList()) {
        if (entry.getFileName().toString().matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
          discardDirectory(entry);
        }
      }
    }
  }

  @Override
  public Prepared prepare(VpnProfile profile, ProfileSecrets secrets, BooleanSupplier cancelled)
      throws IOException, InterruptedException {
    new ProfileValidator().requireValid(profile, secrets);
    verifyBinary();
    NetworkPlatform.Inspection inspection = platform.inspect();
    checkConflicts(profile, inspection);
    List<String> servers =
        Arrays.stream(AddressResolver.resolve(profile.server(), cancelled))
            .filter(Inet4Address.class::isInstance)
            .map(InetAddress::getHostAddress)
            .toList();
    if (servers.isEmpty()) {
      throw new NetworkFailure(
          "SERVER_IPV4_UNAVAILABLE", "VPN-сервер не имеет доступного IPv4-адреса");
    }
    String subnet = allocateSubnet(profile, inspection);
    long base = Ipv4Network.parse(subnet).address();
    String interfaceName =
        System.getProperty("os.name").startsWith("Windows") ? "Wisprail" : freeUtun(inspection);
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    byte[] token = new byte[32];
    new SecureRandom().nextBytes(token);
    var network =
        new SingboxConfig.RuntimeNetwork(
            interfaceName,
            Ipv4Network.formatAddress(base + 1) + "/30",
            Ipv4Network.formatAddress(base + 2),
            servers,
            port,
            HexFormat.of().formatHex(token));
    UUID id = UUID.randomUUID();
    Path candidateDirectory = directory.resolve(id.toString());
    SecureFiles.directory(candidateDirectory);
    Prepared prepared = new Prepared(id, profile, candidateDirectory, network);
    boolean completed = false;
    try {
      JsonFiles.writeAtomic(
          candidateDirectory.resolve("sing-box.json"),
          new SingboxConfig().compile(profile, secrets, network));
      var result =
          commands.run(
              List.of(
                  binary.toString(),
                  "check",
                  "-c",
                  candidateDirectory.resolve("sing-box.json").toString()),
              new byte[0],
              Duration.ofSeconds(15));
      if (result.exitCode() != 0) {
        throw new NetworkFailure(
            "CONFIG_REJECTED",
            "sing-box отклонил конфигурацию. Проверьте сертификаты и параметры протокола.");
      }
      if (cancelled.getAsBoolean()) {
        throw new IOException("Проверка отменена");
      }
      completed = true;
      return prepared;
    } finally {
      if (!completed) {
        try {
          discard(prepared);
        } catch (IOException exception) {
          NetworkFailure failure =
              new NetworkFailure(
                  "CANDIDATE_CLEANUP",
                  "Не удалось удалить временную конфигурацию. Перезапустите компонент для повторной"
                      + " очистки.");
          failure.initCause(exception);
          throw failure;
        }
      }
    }
  }

  @Override
  public void start(Prepared prepared, BooleanSupplier cancelled)
      throws IOException, InterruptedException {
    if (cancelled.getAsBoolean()) {
      throw new IOException("Подключение отменено");
    }
    if (process != null || !clean) {
      throw new IOException("Прежний запуск не завершён");
    }
    active = prepared;
    clean = false;
    // Persist intent before process creation; recovery also handles a crash before the PID is
    // recorded.
    JsonFiles.writeAtomic(
        directory.resolve("ownership.json"),
        new Ownership(
            prepared.id(),
            -1,
            Instant.EPOCH,
            prepared.network().interfaceName(),
            prepared.directory().getFileName().toString()));
    process = EngineProcess.start(binary, prepared.directory().resolve("sing-box.json"));
    JsonFiles.writeAtomic(
        directory.resolve("ownership.json"),
        new Ownership(
            prepared.id(),
            process.pid(),
            process.startedAt(),
            prepared.network().interfaceName(),
            prepared.directory().getFileName().toString()));
    try (EngineApi api =
        new EngineApi(prepared.network().apiPort(), prepared.network().apiSecret())) {
      Instant deadline = Instant.now().plusSeconds(45);
      boolean ready = false;
      while (Instant.now().isBefore(deadline) && !cancelled.getAsBoolean() && process.isAlive()) {
        try {
          api.checkVersion();
          ready = true;
          break;
        } catch (IOException exception) {
          Thread.sleep(100);
        }
      }
      if (!ready || cancelled.getAsBoolean()) {
        throw new NetworkFailure(
            "ENGINE_NOT_READY",
            "Сетевой компонент не подтвердил готовность. Проверьте права и доступность TUN.");
      }
      platform.verifyRoutes(prepared.profile(), prepared.network().interfaceName());
      platform.installDns(
          prepared.id(),
          prepared.profile().dns().domains().stream().map(DomainName::normalize).toList(),
          prepared.network().dnsAddress());
      if (prepared.profile().protocol() == VpnProfile.Protocol.OPENVPN) {
        api.awaitOpenVpn(cancelled);
      }
      if (prepared.profile().protocol() == VpnProfile.Protocol.VLESS
          || !prepared.profile().dns().server().isEmpty()) {
        new ReachabilityProbe().verify(prepared.profile(), prepared.network().dnsAddress());
      }
    }
  }

  @Override
  public synchronized void stop() throws IOException, InterruptedException {
    if (active == null) {
      if (!clean) {
        recover();
      }
      return;
    }
    if (process != null) {
      process.stop();
      process.close();
      process = null;
    }
    platform.removeDns(active.id());
    if (!platform.dnsRemoved(active.id())
        || !platform.routesRemoved(active.network().interfaceName())) {
      throw new IOException("Не удалось подтвердить удаление собственных настроек");
    }
    discard(active);
    Files.deleteIfExists(directory.resolve("ownership.json"));
    active = null;
    clean = true;
  }

  @Override
  public void discard(Prepared prepared) throws IOException {
    discardDirectory(prepared.directory());
  }

  private void discardDirectory(Path target) throws IOException {
    Path normalized = target.toAbsolutePath().normalize();
    if (!normalized.getParent().equals(directory) || Files.isSymbolicLink(normalized)) {
      throw new IOException("Некорректный каталог временной конфигурации");
    }
    UUID.fromString(normalized.getFileName().toString());
    if (Files.exists(normalized)) {
      Files.deleteIfExists(normalized.resolve("sing-box.json"));
      Files.delete(normalized);
    }
  }

  @Override
  public boolean isRunning() {
    EngineProcess current = process;
    return current != null && current.isAlive();
  }

  @Override
  public boolean cleanupConfirmed() {
    return clean;
  }

  @Override
  public synchronized List<Check> diagnose() throws IOException, InterruptedException {
    verifyBinary();
    if (active == null || !isRunning()) {
      return List.of(
          new Check("Компонент", "PASS", "sing-box " + SingboxConfig.VERSION),
          new Check("Маршруты и DNS", "NOT_RUN", "Подключите профиль для сетевой проверки"));
    }
    platform.verifyRoutes(active.profile(), active.network().interfaceName());
    if (active.profile().protocol() == VpnProfile.Protocol.OPENVPN) {
      try (EngineApi api =
          new EngineApi(active.network().apiPort(), active.network().apiSecret())) {
        api.checkOpenVpnReady();
      }
    }
    if (!active.profile().dns().server().isEmpty() || !active.profile().probeUrl().isEmpty()) {
      new ReachabilityProbe().verify(active.profile(), active.network().dnsAddress());
      return List.of(
          new Check("Маршруты", "PASS", "Маршруты присутствуют на собственном TUN"),
          new Check("Ресурс профиля", "PASS", "Получен ответ через настроенный путь"));
    }
    return List.of(
        new Check("Маршруты", "PASS", "Маршруты присутствуют на собственном TUN"),
        new Check("Ресурс профиля", "NOT_RUN", "Ресурс проверки не задан"));
  }

  private void verifyBinary() throws IOException, InterruptedException {
    String expected = Files.readString(binary.resolveSibling("sing-box.sha256")).strip();
    try {
      String actual =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary)));
      if (!actual.equals(expected)) {
        throw new NetworkFailure(
            "ENGINE_CHECKSUM",
            "Контрольная сумма сетевого компонента не совпадает. Восстановите установленный"
                + " пакет.");
      }
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 недоступен", exception);
    }
    var version =
        commands.run(List.of(binary.toString(), "version"), new byte[0], Duration.ofSeconds(5));
    if (version.exitCode() != 0
        || !version.output().contains("sing-box version " + SingboxConfig.VERSION + "\n")
            && !version.output().contains("sing-box version " + SingboxConfig.VERSION + "\r\n")) {
      throw new NetworkFailure(
          "ENGINE_VERSION", "Неподдерживаемая версия sing-box; требуется комплектная 1.14.2");
    }
  }

  private void checkConflicts(VpnProfile profile, NetworkPlatform.Inspection inspection)
      throws IOException {
    List<Ipv4Network> networks = profile.networks().stream().map(Ipv4Network::parse).toList();
    for (NetworkPlatform.Route route : inspection.routes()) {
      if (active != null && route.interfaceName().equals(active.network().interfaceName())) {
        continue;
      }
      Ipv4Network external = Ipv4Network.parse(route.prefix());
      if (external.prefix() > 1 && networks.stream().anyMatch(external::overlaps)) {
        throw new NetworkFailure(
            "ROUTE_CONFLICT",
            "Сеть пересекается с существующим системным маршрутом. Проверьте сети профиля и другой"
                + " VPN.");
      }
    }
    for (NetworkPlatform.DnsRule rule : inspection.dnsRules()) {
      if (active != null && active.id().equals(rule.owner())) {
        continue;
      }
      String existing = rule.domain();
      String normalized = existing.startsWith(".") ? existing.substring(1) : existing;
      if (normalized.isBlank()) {
        throw new NetworkFailure(
            "DNS_POLICY_CONFLICT", "Обнаружена глобальная политика DNS другой программы");
      }
      for (String domain : profile.dns().domains()) {
        if (DomainName.matches(domain, normalized) || DomainName.matches(normalized, domain)) {
          throw new NetworkFailure(
              "DNS_POLICY_CONFLICT", "Домен пересекается с существующей политикой DNS");
        }
      }
    }
  }

  private static String allocateSubnet(VpnProfile profile, NetworkPlatform.Inspection inspection)
      throws IOException {
    for (int suffix = 0; suffix < 256; suffix++) {
      Ipv4Network candidate = Ipv4Network.parse("198.18." + suffix + ".0/30");
      boolean conflict =
          profile.networks().stream().map(Ipv4Network::parse).anyMatch(candidate::overlaps)
              || inspection.routes().stream()
                  .map(route -> Ipv4Network.parse(route.prefix()))
                  .filter(route -> route.prefix() > 1)
                  .anyMatch(candidate::overlaps);
      if (!conflict) {
        return candidate.toString();
      }
    }
    throw new IOException("Не найден свободный адрес для TUN");
  }

  private static String freeUtun(NetworkPlatform.Inspection inspection) throws IOException {
    for (int index = 20; index < 100; index++) {
      String name = "utun" + index;
      if (inspection.routes().stream().noneMatch(route -> route.interfaceName().equals(name))) {
        return name;
      }
    }
    throw new IOException("Не найден свободный TUN-интерфейс");
  }

  @Override
  public void close() throws IOException {
    try {
      stop();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Остановка прервана", exception);
    } finally {
      platform.close();
    }
  }
}
