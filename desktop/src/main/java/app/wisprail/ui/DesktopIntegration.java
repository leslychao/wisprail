package app.wisprail.ui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI.SHELLEXECUTEINFO;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class DesktopIntegration {
  private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
  private static final int SEE_MASK_NOCLOSEPROCESS = 0x40;
  private static final int WAIT_OBJECT_0 = 0;

  /** Messages in this exception are application-owned and safe to display. */
  static final class Failure extends IOException {
    private static final long serialVersionUID = 1L;

    Failure(String message) {
      super(message);
    }

    Failure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  interface MacServices extends Library {
    int wisprail_login(int enabled);

    int wisprail_service(int enabled);
  }

  private DesktopIntegration() {}

  static boolean packaged() {
    return System.getProperty("jpackage.app-path") != null;
  }

  static void setAutoStart(boolean enabled) throws IOException {
    if (!packaged()) {
      if (enabled) {
        throw new Failure("Автозапуск доступен после установки приложения");
      }
      return;
    }
    if (System.getProperty("os.name").startsWith("Windows")) {
      if (enabled) {
        Advapi32Util.registrySetStringValue(
            WinReg.HKEY_CURRENT_USER,
            RUN_KEY,
            "Wisprail",
            "\"" + System.getProperty("jpackage.app-path") + "\" --tray");
      } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, "Wisprail")) {
        Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, "Wisprail");
      }
    } else if (macServices().wisprail_login(enabled ? 1 : 0) != 0) {
      throw new Failure("macOS не разрешила изменение автозапуска");
    }
  }

  static void enableMacService() throws IOException {
    if (macServices().wisprail_service(1) != 0) {
      throw new Failure("Разрешите фоновый компонент Wisprail в настройках macOS");
    }
  }

  static void installService() throws IOException {
    if (!packaged()) {
      throw new Failure("Установите приложение из пакета Wisprail");
    }
    if (!System.getProperty("os.name").startsWith("Windows")) {
      enableMacService();
      return;
    }
    runWindowsInstaller("install-service.ps1", " -AllowedSid " + currentSid());
  }

  static void removeService() throws IOException {
    if (!packaged()) {
      throw new Failure("Удаление компонента доступно из установленного приложения");
    }
    if (System.getProperty("os.name").startsWith("Windows")) {
      runWindowsInstaller("uninstall-service.ps1", "");
    } else if (macServices().wisprail_service(0) != 0) {
      throw new Failure("macOS не подтвердила удаление фонового компонента");
    }
  }

  private static void runWindowsInstaller(String script, String arguments) throws IOException {
    Path directory = Path.of(System.getProperty("jpackage.app-path")).getParent();
    Path installer = directory.resolve("app").resolve(script);
    if (!Files.isRegularFile(installer)) {
      throw new Failure("Установщик системного компонента отсутствует");
    }
    String parameters = "-NoProfile -File \"" + installer + "\"" + arguments;
    Path installed = Path.of(System.getenv("ProgramFiles"), "Wisprail");
    if (!directory.toAbsolutePath().normalize().equals(installed.toAbsolutePath().normalize())) {
      throw new Failure(
          "Для установки службы поместите дистрибутив в Program Files\\Wisprail. Интерфейс можно"
              + " запускать из распакованного ZIP.");
    }
    SHELLEXECUTEINFO request = new SHELLEXECUTEINFO();
    request.fMask = SEE_MASK_NOCLOSEPROCESS;
    request.lpVerb = "runas";
    request.lpFile =
        Path.of(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe")
            .toString();
    request.lpParameters = parameters;
    request.lpDirectory = directory.toString();
    request.nShow = 0;
    if (!Shell32.INSTANCE.ShellExecuteEx(request)) {
      throw new Failure(
          "Системное разрешение не предоставлено или установщик не удалось запустить.");
    }
    if (request.hProcess == null) {
      throw new Failure("Не удалось получить результат установщика системного компонента.");
    }
    try {
      if (Kernel32.INSTANCE.WaitForSingleObject(request.hProcess, 120_000) != WAIT_OBJECT_0) {
        throw new Failure(
            "Установщик не завершился за две минуты. Результат неизвестен; проверьте состояние"
                + " компонента перед повторением.");
      }
      IntByReference code = new IntByReference();
      if (!Kernel32.INSTANCE.GetExitCodeProcess(request.hProcess, code) || code.getValue() != 0) {
        throw new Failure(
            "Установка или удаление компонента завершилось ошибкой. Проверьте расположение"
                + " приложения, разрешение ОС и журнал службы.");
      }
    } finally {
      Kernel32.INSTANCE.CloseHandle(request.hProcess);
    }
  }

  private static MacServices macServices() throws IOException {
    Path executable = Path.of(System.getProperty("jpackage.app-path", ""));
    Path directory = executable.getParent();
    if (directory == null) {
      throw new Failure("Платформенный компонент доступен из установленного приложения");
    }
    Path library = directory.resolve("../Frameworks/libwisprail.dylib").normalize();
    if (!Files.isRegularFile(library)) {
      throw new Failure("Платформенный компонент не установлен");
    }
    try {
      return Native.load(library.toString(), MacServices.class);
    } catch (UnsatisfiedLinkError failure) {
      throw new Failure(
          "macOS не загрузила libwisprail.dylib. Проверьте архитектуру дистрибутива и системный"
              + " журнал загрузки компонентов.",
          failure);
    }
  }

  private static String currentSid() throws IOException {
    WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
    if (!Advapi32.INSTANCE.OpenProcessToken(
        Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
      throw new Failure("Не удалось определить текущую учётную запись Windows");
    }
    try {
      return Advapi32Util.getTokenAccount(token.getValue()).sidString;
    } finally {
      Kernel32.INSTANCE.CloseHandle(token.getValue());
    }
  }
}
