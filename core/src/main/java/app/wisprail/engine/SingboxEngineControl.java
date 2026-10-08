package app.wisprail.engine;

import app.wisprail.connection.AddressAnalysis;
import app.wisprail.connection.CheckStatus;
import app.wisprail.connection.DiagnosticCheck;
import app.wisprail.connection.DiagnosticReport;
import app.wisprail.connection.EngineControl;
import app.wisprail.platform.NetworkPlan;
import app.wisprail.platform.NetworkPlatform;
import app.wisprail.platform.PlatformProcess;
import app.wisprail.profile.DomainNames;
import app.wisprail.profile.Ipv4Cidr;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.ProfileValidator;
import app.wisprail.profile.VpnType;
import app.wisprail.storage.PrivateFiles;
import com.google.gson.Gson;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns the one engine process and its authenticated API, not the connection state machine. */
public final class SingboxEngineControl implements EngineControl {
  private static final Gson JSON = new Gson();
  private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
  private final Path executable;
  private final Path stateDirectory;
  private final Path journal;
  private final NetworkPlatform platform;
  private final ScheduledExecutorService monitor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> Thread.ofPlatform().daemon().name("engine-monitor").unstarted(runnable));
  private volatile Active active;
  private volatile BiConsumer<UUID, String> failureHandler = (id, message) -> {};

  public SingboxEngineControl(Path executable, Path stateDirectory, NetworkPlatform platform)
      throws IOException {
    this.executable = executable.toAbsolutePath().normalize();
    this.stateDirectory = PrivateFiles.directory(stateDirectory.toAbsolutePath().normalize());
    this.journal = this.stateDirectory.resolve("engine-owner.json");
    this.platform = platform;
    monitor.scheduleWithFixedDelay(this::checkActive, 1, 2, TimeUnit.SECONDS);
  }

  @Override
  public Candidate prepare(Profile profile, ProfileSecrets secrets, UUID operationId)
      throws Exception {
    ProfileValidator.requireValid(profile, secrets);
    verifyBinary();
    NetworkPlan network = platform.prepare(profile, operationId);
    platform.checkConflicts(network);
    EngineCredentials credentials = EngineCredentials.create();
    Path directory = PrivateFiles.directory(stateDirectory.resolve(operationId.toString()));
    Prepared candidate =
        new Prepared(operationId, profile, network, directory, availablePort(), credentials);
    try {
      String privateKey =
          EngineCredentials.decryptPrivateKey(secrets.privateKey(), secrets.privateKeyPassword());
      ProfileSecrets material =
          new ProfileSecrets(
              secrets.password(),
              privateKey,
              "",
              secrets.tlsAuthKey(),
              secrets.tlsCryptKey(),
              secrets.uuid());
      String configuration =
          JSON.toJson(
              SingboxConfig.create(
                  profile,
                  material,
                  network,
                  candidate.port,
                  credentials.token(),
                  credentials.certificate(),
                  credentials.key()));
      PrivateFiles.writeAtomic(candidate.config, configuration.getBytes(StandardCharsets.UTF_8));
      runCheck(candidate.config);
      return candidate;
    } catch (Exception failure) {
      candidate.close();
      throw failure;
    }
  }

  @Override
  public synchronized void start(
      Candidate value, BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
    if (!(value instanceof Prepared candidate) || candidate.closed.get()) {
      throw new IllegalArgumentException("Кандидат подключения недействителен");
    }
    if (active != null || Files.exists(journal)) {
      throw new IOException("Предыдущее подключение ещё не очищено");
    }
    checkCancelled(cancelled);
    platform.checkConflicts(candidate.network);
    progress.accept("Запускаем VPN");
    Ownership intent = new Ownership(candidate.operationId(), 0, "", executable.toString());
    PrivateFiles.writeAtomic(journal, JSON.toJson(intent).getBytes(StandardCharsets.UTF_8));
    candidate.retained = true;
    try {
      platform.reserve(candidate.network);
      PlatformProcess.Launch launch =
          PlatformProcess.prepareLaunch(
              executable, List.of("run", "-c", candidate.config.toString()));
      ProcessHandle process = launch.process();
      Active running = new Active(candidate, launch);
      active = running;
      Instant started =
          process
              .info()
              .startInstant()
              .orElseThrow(() -> new IOException("Не удалось подтвердить принадлежность процесса"));
      Ownership ownership =
          new Ownership(
              candidate.operationId(), process.pid(), started.toString(), executable.toString());
      PrivateFiles.writeAtomic(journal, JSON.toJson(ownership).getBytes(StandardCharsets.UTF_8));
      launch.activate();
      running.api = new EngineApi(candidate.port, candidate.credentials);
      long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
      awaitApi(running, cancelled, deadline);
      checkCancelled(cancelled);
      platform.applyDns(candidate.network, candidate.profile());
      platform.verify(candidate.network, candidate.profile());
      progress.accept("Ожидаем готовности VPN");
      if (candidate.profile().type() == VpnType.OPENVPN) {
        running.api.subscribeOpenVpn();
        awaitOpenVpn(running, cancelled, deadline);
      } else {
        awaitVless(running, cancelled, deadline);
      }
      checkCancelled(cancelled);
      platform.verify(candidate.network, candidate.profile());
      running.ready = true;
    } catch (Exception failure) {
      try {
        if (active == null) {
          recover();
          candidate.retained = false;
        } else {
          stop();
        }
      } catch (Exception cleanupFailure) {
        cleanupFailure.addSuppressed(failure);
        throw cleanupFailure;
      }
      throw failure;
    }
  }

  private void awaitApi(Active running, BooleanSupplier cancelled, long deadline) throws Exception {
    while (System.nanoTime() < deadline) {
      checkCancelled(cancelled);
      requireAlive(running);
      try {
        running.api.verifyVersion(Duration.ofMillis(500));
        return;
      } catch (StatusRuntimeException unavailable) {
        if (unavailable.getStatus().getCode() == io.grpc.Status.Code.UNAUTHENTICATED) {
          throw new IOException("API движка отклонил защищённое подключение");
        }
        Thread.sleep(100);
      }
    }
    throw new IOException("Движок не предоставил API готовности вовремя");
  }

  private static void awaitOpenVpn(Active running, BooleanSupplier cancelled, long deadline)
      throws Exception {
    while (System.nanoTime() < deadline) {
      checkCancelled(cancelled);
      requireAlive(running);
      String state = running.api.openVpnState();
      if (state.equals("connected")) {
        return;
      }
      if (state.equals("error") || state.equals("auth-pending")) {
        throw new IOException(
            state.equals("auth-pending")
                ? "Сервер требует дополнительные данные входа: проверьте параметры доступа"
                : "OpenVPN не подтвердил соединение: проверьте сервер и данные доступа");
      }
      Thread.sleep(100);
    }
    throw new IOException("OpenVPN не подтвердил готовность за 30 секунд");
  }

  private static void awaitVless(Active running, BooleanSupplier cancelled, long deadline)
      throws Exception {
    IOException last = null;
    while (System.nanoTime() < deadline) {
      checkCancelled(cancelled);
      requireAlive(running);
      try {
        long remaining = deadline - System.nanoTime();
        ReadinessProbe.verify(
            running.candidate.profile(),
            Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(3).toNanos())));
        return;
      } catch (IOException failure) {
        last = failure;
        Thread.sleep(200);
      }
    }
    throw new IOException(
        "VPN не подтвердил доступность корпоративного DNS или HTTPS-ресурса", last);
  }

  @Override
  public synchronized void stop() throws Exception {
    Active running = active;
    if (running == null) {
      if (Files.exists(journal)) {
        recover();
      }
      return;
    }
    running.stopping = true;
    if (running.api != null) {
      running.api.close();
    }
    stopProcess(running.process);
    running.launch.close();
    platform.cleanup(running.candidate.operationId());
    running.candidate.retained = false;
    running.candidate.close();
    Files.deleteIfExists(journal);
    active = null;
  }

  @Override
  public synchronized void recover() throws Exception {
    if (active != null) {
      stop();
      return;
    }
    if (Files.exists(journal)) {
      Ownership owner =
          JSON.fromJson(
              new String(PrivateFiles.read(journal, 8192), StandardCharsets.UTF_8),
              Ownership.class);
      if (owner == null
          || owner.operationId() == null
          || !executable.toString().equals(owner.executable())) {
        throw new IOException("Запись принадлежности VPN повреждена; запуск заблокирован");
      }
      var process =
          owner.pid() == 0
              ? java.util.Optional.<ProcessHandle>empty()
              : ProcessHandle.of(owner.pid());
      if (process.isPresent() && process.get().isAlive()) {
        var information = process.get().info();
        if (!information.startInstant().map(Instant::toString).orElse("").equals(owner.startedAt())
            || !information
                .command()
                .map(path -> Path.of(path).toAbsolutePath().normalize())
                .filter(executable::equals)
                .isPresent()) {
          throw new IOException("Принадлежность оставшегося процесса не подтверждена");
        }
        stopProcess(process.get());
      }
      Path config = stateDirectory.resolve(owner.operationId().toString()).resolve("config.json");
      for (ProcessHandle remaining : PlatformProcess.findByCommand(executable, config.toString())) {
        stopProcess(remaining);
      }
      platform.cleanup(owner.operationId());
      removeCandidateDirectory(stateDirectory.resolve(owner.operationId().toString()));
      Files.delete(journal);
    }
    platform.recover();
    try (var entries = Files.newDirectoryStream(stateDirectory)) {
      for (Path directory : entries) {
        if (!directory
            .getFileName()
            .toString()
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
          continue;
        }
        if (!PlatformProcess.findByCommand(executable, directory.resolve("config.json").toString())
            .isEmpty()) {
          throw new IOException("Принадлежность процесса без журнала не подтверждена");
        }
        removeCandidateDirectory(directory);
      }
    }
  }

  @Override
  public boolean running() {
    Active running = active;
    return running != null && running.process.isAlive();
  }

  @Override
  public void onFailure(BiConsumer<UUID, String> handler) {
    failureHandler = java.util.Objects.requireNonNull(handler);
  }

  private void checkActive() {
    Active running = active;
    if (running == null || !running.ready || running.stopping || running.failed.get()) {
      return;
    }
    String failure = null;
    try {
      requireAlive(running);
      if (running.candidate.profile().type() == VpnType.OPENVPN
          && !running.api.openVpnState().equals("connected")) {
        throw new IOException("OpenVPN потерял готовность");
      }
      platform.verify(running.candidate.network, running.candidate.profile());
    } catch (IOException error) {
      failure = error.getMessage();
    }
    if (failure != null
        && active == running
        && !running.stopping
        && running.failed.compareAndSet(false, true)) {
      failureHandler.accept(running.candidate.operationId(), failure);
    }
  }

  @Override
  public DiagnosticReport diagnose(Profile profile, ProfileSecrets secrets) throws Exception {
    List<DiagnosticCheck> checks = new ArrayList<>();
    try {
      verifyBinary();
      checks.add(
          new DiagnosticCheck(
              "VPN-компонент",
              CheckStatus.PASS,
              "Контрольная сумма комплектного sing-box проверена"));
    } catch (IOException failure) {
      checks.add(
          new DiagnosticCheck(
              "VPN-компонент", CheckStatus.FAIL, "Комплектный движок отсутствует или повреждён"));
    }
    Active running = active;
    boolean applied = matchesApplied(running, profile);
    try {
      ProfileValidator.requireValid(profile, secrets);
      if (applied) {
        runCheck(running.candidate.config);
      } else {
        Candidate checked = prepare(profile, secrets, UUID.randomUUID());
        checked.close();
      }
      checks.add(
          new DiagnosticCheck(
              "Конфигурация", CheckStatus.PASS, "Поля профиля и sing-box check проверены"));
    } catch (InterruptedException interruption) {
      throw interruption;
    } catch (Exception failure) {
      checks.add(
          new DiagnosticCheck(
              "Конфигурация",
              CheckStatus.FAIL,
              "Конфигурация не прошла проверку профиля, совместимости или системных конфликтов"));
    }
    boolean verified = false;
    if (applied) {
      try {
        requireAlive(running);
        running.api.verifyVersion(Duration.ofSeconds(2));
        if (profile.type() == VpnType.OPENVPN && !running.api.openVpnState().equals("connected")) {
          throw new IOException("OpenVPN не подтвердил готовность");
        }
        platform.verify(running.candidate.network, profile);
        verified = true;
      } catch (IOException failure) {
        verified = false;
      } catch (StatusRuntimeException failure) {
        verified = false;
      }
    }
    CheckStatus appliedStatus =
        !applied ? CheckStatus.NOT_RUN : verified ? CheckStatus.PASS : CheckStatus.FAIL;
    String appliedDetail =
        !applied
            ? "Профиль не применён; диагностика не запускает VPN"
            : verified
                ? "Применённые системные объекты и готовность движка подтверждены"
                : "Готовность движка или применённые системные объекты не подтверждены";
    checks.add(new DiagnosticCheck("VPN и маршруты", appliedStatus, appliedDetail));
    checks.add(new DiagnosticCheck("Системные параметры", appliedStatus, appliedDetail));
    checks.add(
        new DiagnosticCheck(
            "Split DNS",
            appliedStatus,
            applied && verified && profile.domains().isEmpty()
                ? "Домены не заданы; системные DNS-настройки не изменены"
                : appliedDetail));
    if (verified && (!profile.dns().isBlank() || !profile.healthUrl().isBlank())) {
      try {
        ReadinessProbe.verify(profile, Duration.ofSeconds(5));
        checks.add(
            new DiagnosticCheck(
                "Ресурс VPN",
                CheckStatus.PASS,
                "Получен ответ корпоративного DNS или HTTPS-ресурса"));
      } catch (IOException failure) {
        checks.add(
            new DiagnosticCheck(
                "Ресурс VPN",
                CheckStatus.FAIL,
                "Корпоративный DNS или HTTPS-ресурс не ответил успешно"));
      }
    } else {
      checks.add(
          new DiagnosticCheck(
              "Ресурс VPN",
              CheckStatus.NOT_RUN,
              verified
                  ? "Дополнительный DNS или HTTPS-ресурс не задан"
                  : "Ресурс проверяется только через подтверждённое подключение"));
    }
    return new DiagnosticReport(profile.id(), Instant.now(), checks);
  }

  @Override
  public AddressAnalysis analyze(Profile profile, String address) throws Exception {
    String host = address.strip();
    if (host.contains("://")) {
      host = URI.create(host).getHost();
    }
    if (host == null || host.isBlank() || host.length() > 253) {
      throw new IllegalArgumentException("Введите IPv4, домен или HTTPS-адрес");
    }
    String inputHost = host;
    String domain =
        profile.domains().stream()
            .filter(suffix -> DomainNames.matches(inputHost, suffix))
            .findFirst()
            .orElse("");
    Active running = active;
    boolean verified = false;
    if (matchesApplied(running, profile)) {
      platform.verify(running.candidate.network, running.candidate.profile());
      verified = true;
    }
    List<String> addresses;
    if (verified) {
      addresses = ReadinessProbe.resolveIpv4(profile, host, Duration.ofSeconds(5));
    } else if (host.matches("[0-9.]+")) {
      Ipv4Cidr.parseAddress(host);
      addresses = List.of(host);
    } else {
      addresses = List.of();
    }
    List<String> networks =
        profile.networks().stream()
            .filter(network -> addresses.stream().anyMatch(Ipv4Cidr.parse(network)::contains))
            .toList();
    return new AddressAnalysis(
        address,
        domain,
        addresses,
        networks,
        verified,
        verified
            ? "Проверены маршруты применённого профиля; это не проверка сайта"
            : "Сопоставлены только настройки профиля; DNS-запрос не выполнялся");
  }

  private static boolean matchesApplied(Active running, Profile profile) {
    return running != null
        && running.ready
        && !running.stopping
        && running.process.isAlive()
        && running.candidate.profile().id().equals(profile.id())
        && running.candidate.profile().sameNetworkSettings(profile);
  }

  private void verifyBinary() throws Exception {
    if (!Files.isRegularFile(executable) || Files.isSymbolicLink(executable)) {
      throw new IOException("Комплектный движок sing-box не найден");
    }
    Path checksum = executable.resolveSibling("sing-box.sha256");
    String expected =
        new String(PrivateFiles.read(checksum, 128), StandardCharsets.US_ASCII).strip();
    if (!expected.matches("[0-9a-f]{64}")) {
      throw new IOException("Контрольная сумма комплектного движка отсутствует");
    }
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(executable)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, count);
      }
    }
    if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
      throw new IOException("Комплектный движок повреждён: SHA-256 не совпадает");
    }
  }

  private void runCheck(Path configuration) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(executable.toString(), "check", "-c", configuration.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        throw new IOException("Проверка sing-box превысила 15 секунд");
      }
      if (process.exitValue() != 0) {
        throw new IOException("Движок отклонил настройки VPN; проверьте параметры протокола и TLS");
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private static void requireAlive(Active running) throws IOException {
    if (!running.process.isAlive()) {
      throw new IOException("Движок VPN остановился до подтверждённого завершения");
    }
  }

  private static void checkCancelled(BooleanSupplier cancelled) {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Подключение отменено");
    }
  }

  private static int availablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void stopProcess(ProcessHandle process) throws Exception {
    if (!process.isAlive()) {
      return;
    }
    process.destroy();
    try {
      process.onExit().get(5, TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException timeout) {
      process.destroyForcibly();
      process.onExit().get(5, TimeUnit.SECONDS);
    }
    if (process.isAlive()) {
      throw new IOException("Не удалось остановить собственный процесс VPN");
    }
  }

  private static void removeCandidateDirectory(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    if (Files.isSymbolicLink(directory)) {
      throw new IOException("Каталог конфигурации не должен быть ссылкой");
    }
    Files.deleteIfExists(directory.resolve("config.json"));
    try (var temporary = Files.newDirectoryStream(directory, ".wisprail-*.tmp")) {
      for (Path file : temporary) {
        Files.delete(file);
      }
    }
    Files.deleteIfExists(directory);
  }

  @Override
  public void close() throws IOException {
    monitor.shutdownNow();
    if (active == null) {
      return;
    }
    try {
      stop();
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      throw new IOException("Остановка VPN прервана; очистка не подтверждена", interruption);
    } catch (IOException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IOException("Не удалось подтвердить остановку VPN", failure);
    }
  }

  private record Ownership(UUID operationId, long pid, String startedAt, String executable) {}

  private static final class Active {
    private final Prepared candidate;
    private final PlatformProcess.Launch launch;
    private final ProcessHandle process;
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile EngineApi api;
    private volatile boolean ready;
    private volatile boolean stopping;

    private Active(Prepared candidate, PlatformProcess.Launch launch) {
      this.candidate = candidate;
      this.launch = launch;
      this.process = launch.process();
    }
  }

  private static final class Prepared implements Candidate {
    private final UUID operationId;
    private final Profile profile;
    private final NetworkPlan network;
    private final Path directory;
    private final Path config;
    private final int port;
    private final EngineCredentials credentials;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean retained;

    private Prepared(
        UUID operationId,
        Profile profile,
        NetworkPlan network,
        Path directory,
        int port,
        EngineCredentials credentials) {
      this.operationId = operationId;
      this.profile = profile;
      this.network = network;
      this.directory = directory;
      this.config = directory.resolve("config.json");
      this.port = port;
      this.credentials = credentials;
    }

    @Override
    public UUID operationId() {
      return operationId;
    }

    @Override
    public Profile profile() {
      return profile;
    }

    @Override
    public void close() throws IOException {
      if (!retained) {
        removeCandidateDirectory(directory);
        closed.set(true);
      }
    }
  }
}
