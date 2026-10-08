package app.wisprail.platform;

import app.wisprail.agent.WindowsPipeTransport;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI.SHELLEXECUTEINFO;
import com.sun.jna.platform.win32.W32Service;
import com.sun.jna.platform.win32.W32ServiceManager;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.Winsvc;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** User actions invoke native registration; system passwords never pass through Java. */
public final class PlatformDesktopIntegration {
  private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
  private final Path installationDirectory;

  private PlatformDesktopIntegration(Path installationDirectory) {
    this.installationDirectory = installationDirectory.toAbsolutePath().normalize();
  }

  public static PlatformDesktopIntegration create(Path installationDirectory) {
    return new PlatformDesktopIntegration(installationDirectory);
  }

  public ComponentStatus serviceStatus() throws IOException {
    if (PlatformServices.isWindows()) {
      try (W32ServiceManager manager = new W32ServiceManager(Winsvc.SC_MANAGER_CONNECT);
          W32Service service =
              manager.openService(WindowsPipeTransport.SERVICE_NAME, Winsvc.SERVICE_QUERY_STATUS)) {
        return service.queryStatus().dwCurrentState == Winsvc.SERVICE_RUNNING
            ? new ComponentStatus(ComponentStatus.State.READY, "Компонент запущен")
            : new ComponentStatus(ComponentStatus.State.STOPPED, "Компонент остановлен");
      } catch (Win32Exception exception) {
        return new ComponentStatus(
            exception.getErrorCode() == 1060
                ? ComponentStatus.State.MISSING
                : ComponentStatus.State.ERROR,
            "Компонент управления недоступен (код ОС " + exception.getErrorCode() + ")");
      }
    }
    return switch (MacNative.api().wr_service_status()) {
      case 0 -> new ComponentStatus(ComponentStatus.State.MISSING, "Компонент не установлен");
      case 1 -> new ComponentStatus(ComponentStatus.State.READY, "Компонент разрешён системой");
      case 2 ->
          new ComponentStatus(
              ComponentStatus.State.APPROVAL_REQUIRED, "Разрешите компонент в настройках системы");
      case 3 -> new ComponentStatus(ComponentStatus.State.MISSING, "Компонент не найден");
      default ->
          new ComponentStatus(ComponentStatus.State.ERROR, "Неизвестное состояние компонента");
    };
  }

  public void requestServiceInstallation() throws IOException {
    if (PlatformServices.isWindows()) {
      Path script = installationDirectory.resolve("app/platform/windows/install-service.ps1");
      if (!Files.isRegularFile(script)) {
        throw new IOException("Установщик компонента отсутствует в дистрибутиве");
      }
      String powershell =
          Path.of(
                  System.getenv("SystemRoot"),
                  "System32",
                  "WindowsPowerShell",
                  "v1.0",
                  "powershell.exe")
              .toString();
      SHELLEXECUTEINFO request = new SHELLEXECUTEINFO();
      request.fMask = Shell32.SEE_MASK_NOCLOSEPROCESS | 0x00000100;
      request.lpVerb = "runas";
      request.lpFile = powershell;
      request.lpParameters =
          "-NoProfile -ExecutionPolicy RemoteSigned -File "
              + PlatformProcess.quote(script.toString())
              + " -InstallRoot "
              + PlatformProcess.quote(installationDirectory.toString())
              + " -OwnerSid "
              + PlatformProcess.quote(WindowsPipeTransport.currentUserSid());
      request.lpDirectory = installationDirectory.toString();
      request.nShow = 0;
      if (!Shell32.INSTANCE.ShellExecuteEx(request) || request.hProcess == null) {
        throw new IOException(
            "Установка не разрешена системой (код ОС " + Kernel32.INSTANCE.GetLastError() + ")");
      }
      try {
        if (Kernel32.INSTANCE.WaitForSingleObject(request.hProcess, 120_000) != 0) {
          throw new IOException(
              "Установщик ещё не завершён. Проверьте состояние компонента позже.");
        }
        IntByReference exitCode = new IntByReference();
        if (!Kernel32.INSTANCE.GetExitCodeProcess(request.hProcess, exitCode)
            || exitCode.getValue() != 0) {
          throw new IOException("Установка компонента не завершена успешно");
        }
        if (serviceStatus().state() != ComponentStatus.State.READY) {
          throw new IOException("Установленный компонент не запущен");
        }
      } finally {
        Kernel32.INSTANCE.CloseHandle(request.hProcess);
      }
      return;
    }
    MacNative.Api api = MacNative.api();
    int result = api.wr_service_register();
    if (result != 0 && api.wr_service_status() != 2) {
      MacNative.check(result, "Регистрация компонента не выполнена");
    }
    if (api.wr_service_status() == 2) {
      api.wr_service_open_settings();
    }
  }

  public void openServiceSettings() throws IOException {
    if (PlatformServices.isMac()) {
      MacNative.api().wr_service_open_settings();
    } else {
      requestServiceInstallation();
    }
  }

  public boolean isAutoStartEnabled() throws IOException {
    if (PlatformServices.isWindows()) {
      return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, "Wisprail");
    }
    return MacNative.api().wr_autostart_status() == 1;
  }

  public void setAutoStart(boolean enabled) throws IOException {
    if (PlatformServices.isWindows()) {
      Path executable = installationDirectory.resolve("Wisprail.exe");
      if (!Files.isRegularFile(executable)) {
        throw new IOException("Автозапуск доступен после установки приложения");
      }
      try {
        if (enabled) {
          Advapi32Util.registrySetStringValue(
              WinReg.HKEY_CURRENT_USER, RUN_KEY, "Wisprail", "\"" + executable + "\"");
        } else if (isAutoStartEnabled()) {
          Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, "Wisprail");
        }
      } catch (Win32Exception exception) {
        throw new IOException(
            "Не удалось изменить автозапуск (код ОС " + exception.getErrorCode() + ")", exception);
      }
      return;
    }
    MacNative.check(
        MacNative.api().wr_autostart_set(enabled ? 1 : 0), "Не удалось изменить автозапуск");
  }
}
