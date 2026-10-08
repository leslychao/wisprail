package app.wisprail.agent;

import app.wisprail.connection.ConnectionService;
import app.wisprail.engine.SingboxEngineControl;
import app.wisprail.platform.PlatformServices;
import app.wisprail.platform.SystemNetworkPlatform;
import app.wisprail.storage.PrivateFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

public final class AgentMain {
  private AgentMain() {}

  public static void main(String[] arguments) {
    try {
      if (PlatformServices.isWindows()) {
        if (!Arrays.asList(arguments).contains("--service")) {
          throw new IOException("Системный компонент запускается только диспетчером служб");
        }
        new WindowsServiceHost(AgentMain::create).run();
      } else if (PlatformServices.isMac()) {
        AgentServer server = create();
        try {
          Runtime.getRuntime()
              .addShutdownHook(
                  Thread.ofPlatform()
                      .unstarted(
                          () -> {
                            try {
                              server.close();
                            } catch (IOException exception) {
                              System.err.println(
                                  "Не удалось завершить службу; требуется проверка очистки");
                            }
                          }));
          server.run();
        } finally {
          server.close();
        }
      } else {
        throw new IOException("Неподдерживаемая операционная система");
      }
    } catch (Exception exception) {
      System.err.println(
          "Служба Wisprail не запущена или завершилась с ошибкой. Код: "
              + exception.getClass().getSimpleName());
      System.exit(1);
    }
  }

  private static AgentServer create() throws Exception {
    Path state = PlatformServices.prepareServiceDirectory();
    FileChannel ownership =
        FileChannel.open(
            state.resolve("agent.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      if (ownership.tryLock() == null) {
        throw new IOException("Другой процесс уже управляет системным компонентом");
      }
      return createLocked(state, ownership);
    } catch (Exception exception) {
      ownership.close();
      throw exception;
    }
  }

  private static AgentServer createLocked(Path state, FileChannel ownership) throws Exception {
    String owner = owner(state);
    Path executable =
        PlatformServices.installationDirectory()
            .resolve(
                PlatformServices.isWindows() ? "app/engine/sing-box.exe" : "app/engine/sing-box");
    var engine =
        new SingboxEngineControl(
            executable, state.resolve("engine"), new SystemNetworkPlatform(state));
    var service = new ConnectionService(engine);
    try {
      service.initialize().join();
    } catch (java.util.concurrent.CompletionException exception) {
      if (!service.snapshot().cleanupRequired()) {
        service.close();
        throw exception;
      }
      // Keep IPC available so the user can inspect ERROR and explicitly retry cleanup.
    }
    try {
      IpcListener listener;
      if (PlatformServices.isWindows()) {
        listener = WindowsPipeTransport.listen(owner);
      } else {
        Path socketDirectory = PlatformServices.socketPath().getParent();
        Files.createDirectories(socketDirectory);
        Files.setPosixFilePermissions(
            socketDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));
        listener = UnixSocketTransport.listen(PlatformServices.socketPath(), owner);
      }
      return new AgentServer(listener, service, ownership);
    } catch (Exception exception) {
      service.close();
      throw exception;
    }
  }

  private static String owner(Path state) throws IOException {
    Path file = state.resolve("owner.txt");
    if (!Files.exists(file) && PlatformServices.isMac()) {
      String owner = PlatformServices.consoleUser();
      PrivateFiles.writeAtomic(file, owner.getBytes(StandardCharsets.UTF_8));
    }
    if (!Files.isRegularFile(file)) {
      throw new IOException("Владелец службы не задан установщиком");
    }
    if (PlatformServices.isWindows()) {
      PlatformServices.restrictServiceAdministration(file);
    }
    String owner = new String(PrivateFiles.read(file, 256), StandardCharsets.UTF_8).strip();
    if (owner.isBlank()) {
      throw new IOException("Владелец службы не задан");
    }
    return owner;
  }
}
