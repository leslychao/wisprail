package app.wisprail.platform;

import app.wisprail.engine.SingboxEngineControl;
import app.wisprail.storage.JsonCodec;
import app.wisprail.storage.PrivateFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Set;

/** Installer entry point. User registration and root cleanup remain separate OS contexts. */
public final class MacMaintenanceMain {
  private record RestoreSettings(boolean service, boolean autoStart) {}

  private MacMaintenanceMain() {}

  public static void main(String[] arguments) {
    try {
      if (!PlatformServices.isMac() || arguments.length != 1) {
        throw new IOException("Недопустимый запуск обслуживания приложения");
      }
      MacNative.Api api = MacNative.api();
      if (arguments[0].equals("--cleanup-system")) {
        if (api.wr_effective_uid() != 0) {
          throw new IOException("Очистка системного компонента требует root");
        }
        cleanupSystem();
      } else {
        if (api.wr_effective_uid() == 0) {
          throw new IOException("Регистрацию нужно менять в пользовательской сессии");
        }
        requireClosedUi();
        maintainRegistration(api, arguments[0]);
      }
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      System.err.println(
          "Обслуживание Wisprail не завершено: " + exception.getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static void requireClosedUi() throws IOException {
    Path executable = PlatformServices.installationDirectory().resolve("MacOS/Wisprail");
    try (var processes = ProcessHandle.allProcesses()) {
      if (processes.anyMatch(
          process ->
              process.pid() != ProcessHandle.current().pid()
                  && process
                      .info()
                      .command()
                      .map(Path::of)
                      .filter(executable::equals)
                      .isPresent())) {
        throw new IOException("Закройте интерфейс Wisprail перед обслуживанием");
      }
    }
  }

  private static void maintainRegistration(MacNative.Api api, String operation) throws IOException {
    if (!Set.of("--prepare-update", "--finish-update", "--uninstall-component")
        .contains(operation)) {
      throw new IOException("Неизвестная операция обслуживания");
    }
    Path preferences =
        PrivateFiles.directory(PlatformServices.appDataDirectory()).resolve("maintenance.json");
    if (operation.equals("--finish-update")) {
      if (!Files.exists(preferences)) {
        return;
      }
      RestoreSettings previous = readSettings(preferences);
      if (previous.service()) {
        MacNative.check(
            api.wr_service_register(), "Не удалось восстановить регистрацию компонента");
        if (api.wr_service_status() == 2) {
          api.wr_service_open_settings();
        }
      }
      if (previous.autoStart()) {
        MacNative.check(api.wr_autostart_set(1), "Не удалось восстановить автозапуск");
      }
      Files.delete(preferences);
      return;
    }
    if (operation.equals("--prepare-update") && !Files.exists(preferences)) {
      int service = api.wr_service_status();
      int autoStart = api.wr_autostart_status();
      RestoreSettings previous =
          new RestoreSettings(service == 1 || service == 2, autoStart == 1 || autoStart == 2);
      PrivateFiles.writeAtomic(
          preferences, JsonCodec.gson().toJson(previous).getBytes(StandardCharsets.UTF_8));
    }
    if (api.wr_autostart_status() == 1 || api.wr_autostart_status() == 2) {
      MacNative.check(api.wr_autostart_set(0), "Не удалось отключить автозапуск");
    }
    if (api.wr_service_status() == 1 || api.wr_service_status() == 2) {
      MacNative.check(api.wr_service_unregister(), "Не удалось отключить компонент");
    }
    if (operation.equals("--uninstall-component")) {
      Files.deleteIfExists(preferences);
    }
  }

  private static RestoreSettings readSettings(Path preferences) throws IOException {
    var json =
        JsonCodec.parseStrict(
            new String(PrivateFiles.read(preferences, 4096), StandardCharsets.UTF_8), 4);
    if (!json.isJsonObject()
        || !json.getAsJsonObject().keySet().equals(Set.of("service", "autoStart"))) {
      throw new IOException("Повреждены настройки восстановления установки");
    }
    for (var value : json.getAsJsonObject().asMap().values()) {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
        throw new IOException("Повреждены настройки восстановления установки");
      }
    }
    return JsonCodec.gson().fromJson(json, RestoreSettings.class);
  }

  private static void cleanupSystem() throws Exception {
    Path state = PlatformServices.serviceDataDirectory();
    if (!Files.exists(state)) {
      return;
    }
    Path daemon = PlatformServices.installationDirectory().resolve("MacOS/wisprail-agent");
    long exitDeadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    while (processRunning(daemon)) {
      if (System.nanoTime() >= exitDeadline) {
        throw new IOException("Системная служба ещё не завершила процесс");
      }
      Thread.sleep(100);
    }
    try (FileChannel ownership =
        FileChannel.open(
            state.resolve("agent.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
      FileLock acquired = ownership.tryLock();
      while (acquired == null && System.nanoTime() < deadline) {
        Thread.sleep(100);
        acquired = ownership.tryLock();
      }
      if (acquired == null) {
        throw new IOException("Системная служба ещё не завершила работу");
      }
      try {
        Path executable = PlatformServices.installationDirectory().resolve("app/engine/sing-box");
        try (var engine =
            new SingboxEngineControl(
                executable, state.resolve("engine"), new SystemNetworkPlatform(state))) {
          engine.recover();
        }
      } finally {
        acquired.release();
      }
    }
  }

  private static boolean processRunning(Path executable) {
    try (var processes = ProcessHandle.allProcesses()) {
      return processes.anyMatch(
          process -> process.info().command().map(Path::of).filter(executable::equals).isPresent());
    }
  }
}
