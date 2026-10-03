package app.wisprail.agent;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Winsvc;
import java.io.IOException;

/** Minimal SCM lifecycle bridge; all application and network work remains in Java owners. */
final class WindowsServiceHost {
  @FunctionalInterface
  interface Factory {
    AgentServer open() throws IOException, InterruptedException;
  }

  private final Factory factory;
  private final Winsvc.SERVICE_STATUS status = new Winsvc.SERVICE_STATUS();
  private final Winsvc.HandlerEx handler = (control, eventType, data, context) -> control(control);
  private final Winsvc.SERVICE_MAIN_FUNCTION serviceMain = (count, arguments) -> serve();
  private Winsvc.SERVICE_STATUS_HANDLE handle;
  private volatile AgentServer server;

  WindowsServiceHost(Factory factory) {
    this.factory = factory;
  }

  void run() throws IOException {
    Winsvc.SERVICE_TABLE_ENTRY entry = new Winsvc.SERVICE_TABLE_ENTRY();
    Winsvc.SERVICE_TABLE_ENTRY[] table = (Winsvc.SERVICE_TABLE_ENTRY[]) entry.toArray(2);
    table[0].lpServiceName = "Wisprail";
    table[0].lpServiceProc = serviceMain;
    table[0].write();
    table[1].write();
    if (!Advapi32.INSTANCE.StartServiceCtrlDispatcher(table)) {
      throw new IOException("Запустите Wisprail через установленную Windows Service");
    }
  }

  private void serve() {
    handle = Advapi32.INSTANCE.RegisterServiceCtrlHandlerEx("Wisprail", handler, null);
    if (handle == null) {
      return;
    }
    publish(Winsvc.SERVICE_START_PENDING, 0);
    int exitCode = 0;
    try (AgentServer opened = factory.open()) {
      server = opened;
      publish(Winsvc.SERVICE_RUNNING, 0);
      opened.run();
    } catch (IOException exception) {
      exitCode = 1;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      exitCode = 1;
    } finally {
      server = null;
      publish(Winsvc.SERVICE_STOPPED, exitCode);
    }
  }

  private int control(int control) {
    if (control == Winsvc.SERVICE_CONTROL_STOP || control == Winsvc.SERVICE_CONTROL_SHUTDOWN) {
      publish(Winsvc.SERVICE_STOP_PENDING, 0);
      AgentServer current = server;
      if (current != null) {
        Thread.ofPlatform().name("wisprail-service-stop").start(() -> AgentMain.shutdown(current));
      }
    }
    return 0;
  }

  private synchronized void publish(int state, int exitCode) {
    status.dwServiceType = WinNT.SERVICE_WIN32_OWN_PROCESS;
    status.dwCurrentState = state;
    status.dwControlsAccepted =
        state == Winsvc.SERVICE_RUNNING
            ? Winsvc.SERVICE_ACCEPT_STOP | Winsvc.SERVICE_ACCEPT_SHUTDOWN
            : 0;
    status.dwWin32ExitCode = exitCode;
    status.dwWaitHint = 60000;
    status.write();
    Advapi32.INSTANCE.SetServiceStatus(handle, status);
  }
}
