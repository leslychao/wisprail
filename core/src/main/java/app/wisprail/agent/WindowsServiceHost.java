package app.wisprail.agent;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Winsvc;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Native SCM lifecycle for the bundled Java agent launcher. */
final class WindowsServiceHost {
  private final Callable<AgentServer> factory;
  private final CountDownLatch stop = new CountDownLatch(1);
  private final AtomicReference<Exception> failure = new AtomicReference<>();
  private final Winsvc.SERVICE_STATUS status = new Winsvc.SERVICE_STATUS();
  private Winsvc.SERVICE_STATUS_HANDLE handle;
  private final Winsvc.HandlerEx handler =
      (control, eventType, eventData, context) -> {
        if (control == Winsvc.SERVICE_CONTROL_STOP || control == Winsvc.SERVICE_CONTROL_SHUTDOWN) {
          stop.countDown();
          return 0;
        }
        return control == Winsvc.SERVICE_CONTROL_INTERROGATE ? 0 : 120;
      };
  private final Winsvc.SERVICE_MAIN_FUNCTION main = this::serviceMain;

  WindowsServiceHost(Callable<AgentServer> factory) {
    this.factory = factory;
  }

  void run() throws Exception {
    Winsvc.SERVICE_TABLE_ENTRY[] table =
        (Winsvc.SERVICE_TABLE_ENTRY[]) new Winsvc.SERVICE_TABLE_ENTRY().toArray(2);
    table[0].lpServiceName = WindowsPipeTransport.SERVICE_NAME;
    table[0].lpServiceProc = main;
    table[0].write();
    table[1].write();
    if (!Advapi32.INSTANCE.StartServiceCtrlDispatcher(table)) {
      throw new IOException(
          "Процесс не запущен диспетчером служб (код ОС " + Kernel32.INSTANCE.GetLastError() + ")");
    }
    if (failure.get() != null) {
      throw failure.get();
    }
  }

  private void serviceMain(int count, com.sun.jna.Pointer arguments) {
    handle =
        Advapi32.INSTANCE.RegisterServiceCtrlHandlerEx(
            WindowsPipeTransport.SERVICE_NAME, handler, null);
    if (handle == null) {
      failure.set(new IOException("Не удалось зарегистрировать обработчик службы"));
      return;
    }
    status.dwServiceType = WinNT.SERVICE_WIN32_OWN_PROCESS;
    try {
      publish(Winsvc.SERVICE_START_PENDING, 0, 60_000);
      AgentServer server = factory.call();
      try {
        Thread worker =
            Thread.ofPlatform()
                .name("wisprail-agent")
                .start(
                    () -> {
                      try {
                        server.run();
                      } catch (IOException exception) {
                        failure.compareAndSet(null, exception);
                      } finally {
                        stop.countDown();
                      }
                    });
        publish(Winsvc.SERVICE_RUNNING, 0, 0);
        stop.await();
        publish(Winsvc.SERVICE_STOP_PENDING, 0, 45_000);
        try {
          server.close();
        } finally {
          worker.join(45_000);
        }
      } finally {
        server.close();
      }
      publish(Winsvc.SERVICE_STOPPED, failure.get() == null ? 0 : 1, 0);
    } catch (Exception exception) {
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      failure.compareAndSet(null, exception);
      try {
        publish(Winsvc.SERVICE_STOPPED, 1, 0);
      } catch (IOException statusFailure) {
        exception.addSuppressed(statusFailure);
      }
    }
  }

  private void publish(int state, int error, int waitHint) throws IOException {
    status.dwCurrentState = state;
    status.dwControlsAccepted =
        state == Winsvc.SERVICE_RUNNING
            ? Winsvc.SERVICE_ACCEPT_STOP | Winsvc.SERVICE_ACCEPT_SHUTDOWN
            : 0;
    status.dwWin32ExitCode = error;
    status.dwWaitHint = waitHint;
    status.dwCheckPoint =
        state == Winsvc.SERVICE_START_PENDING || state == Winsvc.SERVICE_STOP_PENDING
            ? status.dwCheckPoint + 1
            : 0;
    if (!Advapi32.INSTANCE.SetServiceStatus(handle, status)) {
      throw new IOException(
          "Не удалось подтвердить состояние службы (код ОС "
              + Kernel32.INSTANCE.GetLastError()
              + ")");
    }
  }
}
