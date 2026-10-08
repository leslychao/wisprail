package app.wisprail.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class PlatformProcessTest {
  @TempDir Path directory;

  @Test
  void childCannotExecuteBeforeActivationAndDiesWhenItsOwnerCloses() throws Exception {
    Path marker = directory.resolve("child marker.txt");
    ProcessHandle child;
    try (PlatformProcess.Launch launch =
        PlatformProcess.prepareLaunch(javaExecutable(), childArguments(marker))) {
      child = launch.process();
      assertTrue(child.isAlive());
      Thread.sleep(200);
      assertFalse(Files.exists(marker));
      launch.activate();
      waitForFile(marker);
    }
    child.onExit().get(10, TimeUnit.SECONDS);
    assertFalse(child.isAlive());
  }

  @Test
  void abruptOwnerExitClosesJobAndKillsActivatedChild() throws Exception {
    Path marker = directory.resolve("crash child.txt");
    Path identity = directory.resolve("child.pid");
    Process owner =
        new ProcessBuilder(
                javaExecutable().toString(),
                "-cp",
                classPath(),
                CrashOwner.class.getName(),
                marker.toString(),
                identity.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      assertTrue(owner.waitFor(20, TimeUnit.SECONDS));
      assertTrue(Files.exists(marker));
      long pid = Long.parseLong(Files.readString(identity));
      ProcessHandle child = ProcessHandle.of(pid).orElse(null);
      if (child != null) {
        child.onExit().get(10, TimeUnit.SECONDS);
        assertFalse(child.isAlive());
      }
    } finally {
      owner.destroyForcibly();
    }
  }

  private static Path javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java.exe");
  }

  private static String classPath() {
    return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
  }

  private static List<String> childArguments(Path marker) {
    return List.of("-cp", classPath(), SleepingChild.class.getName(), marker.toString());
  }

  private static void waitForFile(Path marker) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (!Files.exists(marker) && Instant.now().isBefore(deadline)) {
      Thread.sleep(20);
    }
    assertTrue(Files.exists(marker));
  }

  public static final class SleepingChild {
    public static void main(String[] arguments) throws Exception {
      Files.writeString(Path.of(arguments[0]), "started");
      Thread.sleep(60_000);
    }
  }

  public static final class CrashOwner {
    public static void main(String[] arguments) throws Exception {
      Path marker = Path.of(arguments[0]);
      PlatformProcess.Launch launch =
          PlatformProcess.prepareLaunch(javaExecutable(), childArguments(marker));
      Files.writeString(Path.of(arguments[1]), Long.toString(launch.process().pid()));
      launch.activate();
      waitForFile(marker);
      Runtime.getRuntime().halt(0);
    }
  }
}
