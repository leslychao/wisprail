package app.wisprail.platform;

import app.wisprail.agent.AgentClient;
import app.wisprail.agent.UnixSocketTransport;
import app.wisprail.agent.WindowsPipeTransport;
import app.wisprail.storage.PrivateFiles;
import app.wisprail.storage.SecretStore;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Locale;

public final class PlatformServices {
  private PlatformServices() {}

  public static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
  }

  public static boolean isMac() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");
  }

  public static Path appDataDirectory() {
    if (isWindows()) {
      String local = System.getenv("LOCALAPPDATA");
      if (local == null || local.isBlank()) {
        throw new IllegalStateException("LOCALAPPDATA не определён");
      }
      return Path.of(local, "Wisprail");
    }
    return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Wisprail");
  }

  public static Path logDirectory() {
    return appDataDirectory().resolve("logs");
  }

  public static Path serviceDataDirectory() {
    if (isWindows()) {
      String programData = System.getenv("ProgramData");
      if (programData == null || programData.isBlank()) {
        throw new IllegalStateException("ProgramData не определён");
      }
      return Path.of(programData, "Wisprail", "agent");
    }
    return Path.of("/Library/Application Support/Wisprail/agent");
  }

  public static Path socketPath() {
    return Path.of("/var/run/wisprail/agent.sock");
  }

  public static Path prepareServiceDirectory() throws IOException {
    Path directory = serviceDataDirectory();
    if (!isWindows()) {
      return PrivateFiles.directory(directory);
    }
    if (!WindowsPipeTransport.currentUserSid().equals("S-1-5-18")) {
      throw new IOException("Системный компонент должен работать от LocalSystem");
    }
    Files.createDirectories(directory);
    restrictServiceAdministration(directory);
    return directory;
  }

  /** Service identity stays administrable during repair; user-secret files remain owner-only. */
  public static void restrictServiceAdministration(Path path) throws IOException {
    if (!isWindows() || Files.isSymbolicLink(path)) {
      throw new IOException("Недопустимый путь системного компонента");
    }
    AclFileAttributeView view =
        Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) {
      throw new IOException("Файловая система не поддерживает ACL службы");
    }
    var lookup = path.getFileSystem().getUserPrincipalLookupService();
    var permissions = new ArrayList<AclEntry>();
    for (String sid : new String[] {"S-1-5-18", "S-1-5-32-544"}) {
      var principal = lookup.lookupPrincipalByName(Advapi32Util.getAccountBySid(sid).fqn);
      var entry =
          AclEntry.newBuilder()
              .setType(AclEntryType.ALLOW)
              .setPrincipal(principal)
              .setPermissions(EnumSet.allOf(AclEntryPermission.class));
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
      }
      permissions.add(entry.build());
    }
    view.setAcl(permissions);
  }

  public static Path installationDirectory() {
    String configured = System.getProperty("wisprail.install.dir");
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured).toAbsolutePath().normalize();
    }
    Path javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath();
    if (isMac()) {
      for (Path ancestor = javaHome; ancestor != null; ancestor = ancestor.getParent()) {
        if (ancestor.getFileName() != null
            && ancestor.getFileName().toString().equals("Contents")
            && ancestor.getParent().getFileName().toString().endsWith(".app")) {
          return ancestor;
        }
      }
    } else if (javaHome.getFileName().toString().equals("runtime")) {
      return javaHome.getParent();
    }
    return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
  }

  public static SecretStore secretStore(Path userDirectory) throws IOException {
    if (isWindows()) {
      return new WindowsDpapiSecretStore(userDirectory);
    }
    if (isMac()) {
      return new MacKeychainSecretStore();
    }
    throw new IOException("Поддерживаются Windows и macOS");
  }

  public static AgentClient createClient() {
    return new AgentClient(
        () -> {
          if (isWindows()) {
            return WindowsPipeTransport.connect();
          }
          if (isMac()) {
            return UnixSocketTransport.connect(socketPath());
          }
          throw new IOException("Поддерживаются Windows и macOS");
        });
  }

  public static String consoleUser() throws IOException {
    MacNative.Api api = MacNative.api();
    PointerByReference output = new PointerByReference();
    LongByReference length = new LongByReference();
    MacNative.check(
        api.wr_console_user(output, length),
        "Нужна активная пользовательская сессия для установки компонента");
    return new String(MacNative.result(api, output, length), StandardCharsets.UTF_8);
  }
}
