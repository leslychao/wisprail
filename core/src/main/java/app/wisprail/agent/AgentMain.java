package app.wisprail.agent;

import app.wisprail.connection.ConnectionService;
import app.wisprail.connection.EventJournal;
import app.wisprail.engine.SingboxRuntime;
import app.wisprail.platform.MacNetwork;
import app.wisprail.platform.NetworkPlatform;
import app.wisprail.platform.WindowsNetwork;
import app.wisprail.storage.JsonFiles;
import app.wisprail.storage.SecureFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Entry point for the installed, privileged service; never launches from the profile IPC. */
public final class AgentMain {
  public record Configuration(String allowedUser) {}

  private AgentMain() {}

  public static void main(String[] arguments) throws Exception {
    Path application =
        Path.of(AgentMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .getParent();
    if (System.getProperty("os.name").startsWith("Windows")) {
      new WindowsServiceHost(() -> open(application)).run();
    } else if (System.getProperty("os.name").startsWith("Mac")) {
      try (AgentServer server = open(application)) {
        Runtime.getRuntime()
            .addShutdownHook(new Thread(() -> shutdown(server), "wisprail-shutdown"));
        server.run();
      }
    } else {
      throw new IllegalStateException("Поддерживаются Windows и macOS");
    }
  }

  static AgentServer open(Path application) throws IOException, InterruptedException {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    Configuration configuration =
        JsonFiles.read(
            windows
                ? application.resolve("agent.json")
                : Path.of("/Library/Application Support/Wisprail/agent.json"),
            Configuration.class);
    Path state =
        windows
            ? Path.of(System.getenv("ProgramData"), "Wisprail", "service")
            : Path.of("/Library/Application Support/Wisprail/service");
    SecureFiles.directory(state);
    FileChannel lockChannel =
        FileChannel.open(
            state.resolve("service.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    var lock = lockChannel.tryLock();
    if (lock == null) {
      lockChannel.close();
      throw new IOException("Другой экземпляр службы уже работает");
    }
    NetworkPlatform platform = null;
    SingboxRuntime runtime = null;
    LocalTransport transport = null;
    try {
      platform =
          windows
              ? new WindowsNetwork(application.resolve("windows-network.ps1"))
              : new MacNetwork();
      runtime =
          new SingboxRuntime(
              application.resolve("engine").resolve(windows ? "sing-box.exe" : "sing-box"),
              state.resolve("runtime"),
              platform);
      EventJournal journal = new EventJournal(state.resolve("journal"));
      try {
        runtime.recover();
      } catch (IOException exception) {
        journal.add(
            "error",
            null,
            "RECOVERY_FAILED",
            "Очистка после аварии не подтверждена; запуск заблокирован");
      }
      if (windows) {
        transport = new WindowsPipeTransport(configuration.allowedUser());
      } else {
        Path sockets = Path.of("/var/run/wisprail");
        SecureFiles.directory(sockets);
        Files.setPosixFilePermissions(sockets, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path socket = sockets.resolve("control.sock");
        // The root-owned parent and exclusive service lock establish ownership of this fixed path.
        Files.deleteIfExists(socket);
        transport = new UnixTransport(socket, configuration.allowedUser());
      }
      return new AgentServer(
          transport, new ConnectionService(runtime, journal), journal, lockChannel);
    } catch (IOException | InterruptedException | RuntimeException exception) {
      try {
        if (transport != null) {
          transport.close();
        }
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      try {
        if (runtime != null) {
          runtime.close();
        } else if (platform != null) {
          platform.close();
        }
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      try {
        lockChannel.close();
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw exception;
    }
  }

  static void shutdown(AgentServer server) {
    try {
      server.close();
    } catch (IOException exception) {
      System.err.println(
          "Wisprail: очистка службы не подтверждена; проверьте журнал после запуска");
    }
  }
}
