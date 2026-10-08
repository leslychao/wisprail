package app.wisprail.agent;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.W32Service;
import com.sun.jna.platform.win32.W32ServiceManager;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.ULONGByReference;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.platform.win32.Winsvc;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** A local-only named pipe restricted to the installed owner, with SCM server authentication. */
public final class WindowsPipeTransport {
  public static final String SERVICE_NAME = "WisprailAgent";
  public static final String PIPE_NAME = "\\\\.\\pipe\\Wisprail.Agent.v1";
  private static final int FILE_READ_DATA = 0x0001;
  private static final int FILE_WRITE_DATA = 0x0002;
  private static final int SECURITY_SQOS_PRESENT = 0x00100000;
  private static final int SECURITY_IDENTIFICATION = 0x00010000;

  private interface SecurityApi extends StdCallLibrary {
    SecurityApi INSTANCE = Native.load("Advapi32", SecurityApi.class);

    boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(
        WString descriptor, int revision, PointerByReference output, IntByReference size);

    boolean ImpersonateNamedPipeClient(HANDLE pipe);
  }

  private interface IoApi extends StdCallLibrary {
    IoApi INSTANCE = Native.load("Kernel32", IoApi.class);

    boolean ReadFile(
        HANDLE file,
        Pointer data,
        int count,
        IntByReference transferred,
        WinBase.OVERLAPPED overlapped);

    boolean WriteFile(
        HANDLE file,
        Pointer data,
        int count,
        IntByReference transferred,
        WinBase.OVERLAPPED overlapped);

    boolean GetOverlappedResult(
        HANDLE file, WinBase.OVERLAPPED overlapped, IntByReference transferred, boolean wait);

    boolean CancelIoEx(HANDLE file, WinBase.OVERLAPPED overlapped);
  }

  private WindowsPipeTransport() {}

  public static String currentUserSid() throws IOException {
    HANDLEByReference token = new HANDLEByReference();
    if (!Advapi32.INSTANCE.OpenProcessToken(
        Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
      throw failure("Не удалось определить пользователя");
    }
    try {
      return Advapi32Util.getTokenAccount(token.getValue()).sidString;
    } finally {
      Kernel32.INSTANCE.CloseHandle(token.getValue());
    }
  }

  public static IpcConnection connect() throws IOException {
    HANDLE pipe =
        Kernel32.INSTANCE.CreateFile(
            PIPE_NAME,
            FILE_READ_DATA | FILE_WRITE_DATA,
            0,
            null,
            WinNT.OPEN_EXISTING,
            0x40000000 | SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION,
            null);
    if (WinBase.INVALID_HANDLE_VALUE.equals(pipe)) {
      throw failure("Служба недоступна. Установите или запустите компонент управления");
    }
    try {
      ULONGByReference pid = new ULONGByReference();
      if (!Kernel32.INSTANCE.GetNamedPipeServerProcessId(pipe, pid)) {
        throw failure("Не удалось проверить владельца службы");
      }
      try (W32ServiceManager manager = new W32ServiceManager(Winsvc.SC_MANAGER_CONNECT);
          W32Service service = manager.openService(SERVICE_NAME, Winsvc.SERVICE_QUERY_STATUS)) {
        var status = service.queryStatus();
        if (status.dwCurrentState != Winsvc.SERVICE_RUNNING
            || Integer.toUnsignedLong(status.dwProcessId) != pid.getValue().longValue()) {
          throw new IOException("IPC не принадлежит зарегистрированной службе Wisprail");
        }
      }
      var connection = new Connection(pipe, "LocalSystem:" + pid.getValue().longValue());
      // One fixed, non-secret byte allows the service to obtain the kernel impersonation token.
      connection.write(ByteBuffer.wrap(new byte[] {0x57}));
      return connection;
    } catch (IOException | RuntimeException exception) {
      Kernel32.INSTANCE.CloseHandle(pipe);
      throw exception;
    }
  }

  public static IpcListener listen(String authorizedSid) throws IOException {
    if (!authorizedSid.matches("S-1-(?:[0-9]+-)*[0-9]+")) {
      throw new IOException("Повреждена учётная запись владельца службы");
    }
    return new Listener(authorizedSid);
  }

  private static IOException failure(String message) {
    return new IOException(message + " (код ОС " + Kernel32.INSTANCE.GetLastError() + ")");
  }

  private static final class Listener implements IpcListener {
    private final String owner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<HANDLE> pending = new AtomicReference<>();

    private Listener(String owner) {
      this.owner = owner;
    }

    @Override
    public IpcConnection accept() throws IOException {
      if (closed.get()) {
        throw new IOException("Служба остановлена");
      }
      PointerByReference descriptor = new PointerByReference();
      // Clients get read/write data only, never FILE_CREATE_PIPE_INSTANCE.
      String sddl = "O:SYG:SYD:P(A;;GA;;;SY)(A;;0x00100003;;;" + owner + ")";
      if (!SecurityApi.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
          new WString(sddl), 1, descriptor, null)) {
        throw failure("Не удалось создать защищённый IPC");
      }
      HANDLE pipe;
      try {
        WinBase.SECURITY_ATTRIBUTES attributes = new WinBase.SECURITY_ATTRIBUTES();
        attributes.lpSecurityDescriptor = descriptor.getValue();
        attributes.bInheritHandle = false;
        attributes.write();
        pipe =
            Kernel32.INSTANCE.CreateNamedPipe(
                PIPE_NAME,
                0x00000003 | 0x00080000 | 0x40000000,
                0x00000008,
                1,
                65536,
                65536,
                5000,
                attributes);
      } finally {
        Kernel32.INSTANCE.LocalFree(descriptor.getValue());
      }
      if (WinBase.INVALID_HANDLE_VALUE.equals(pipe)) {
        throw failure("Не удалось открыть защищённый IPC");
      }
      pending.set(pipe);
      try {
        WinBase.OVERLAPPED overlapped = overlapped();
        try {
          if (!Kernel32.INSTANCE.ConnectNamedPipe(pipe, overlapped)) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == 997) {
              IntByReference transferred = new IntByReference();
              while (Kernel32.INSTANCE.WaitForSingleObject(overlapped.hEvent, 1000) == 258) {
                if (closed.get()) {
                  IoApi.INSTANCE.CancelIoEx(pipe, overlapped);
                  break;
                }
              }
              if (!IoApi.INSTANCE.GetOverlappedResult(pipe, overlapped, transferred, true)) {
                throw failure("Приём UI-сессии отменён");
              }
            } else if (error != 535) {
              throw failure("Не удалось принять UI-сессию");
            }
          }
        } finally {
          Kernel32.INSTANCE.CloseHandle(overlapped.hEvent);
        }
        byte[] hello = new byte[1];
        if (transfer(pipe, hello, false, 5000) != 1 || hello[0] != 0x57) {
          throw new IOException("Недопустимое начало UI-сессии");
        }
        if (!SecurityApi.INSTANCE.ImpersonateNamedPipeClient(pipe)) {
          throw failure("Не удалось проверить пользователя UI");
        }
        String actualOwner;
        try {
          HANDLEByReference token = new HANDLEByReference();
          if (!Advapi32.INSTANCE.OpenThreadToken(
              Kernel32.INSTANCE.GetCurrentThread(), WinNT.TOKEN_QUERY, true, token)) {
            throw failure("Не удалось проверить токен UI");
          }
          try {
            actualOwner = Advapi32Util.getTokenAccount(token.getValue()).sidString;
          } finally {
            Kernel32.INSTANCE.CloseHandle(token.getValue());
          }
        } finally {
          if (!Advapi32.INSTANCE.RevertToSelf()) {
            throw failure("Не удалось восстановить системный контекст службы");
          }
        }
        if (!owner.equals(actualOwner)) {
          throw new IOException("Пользователь не является владельцем службы");
        }
        if (closed.get()) {
          throw new IOException("Служба остановлена");
        }
        pending.set(null);
        return new Connection(pipe, actualOwner);
      } catch (IOException | Win32Exception exception) {
        if (pending.compareAndSet(pipe, null)) {
          closePipe(pipe);
        }
        throw exception;
      }
    }

    @Override
    public void close() {
      closed.set(true);
      HANDLE handle = pending.get();
      if (handle != null) {
        IoApi.INSTANCE.CancelIoEx(handle, null);
      }
    }
  }

  private static final class Connection extends FramedConnection {
    private final HANDLE pipe;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantReadWriteLock operations = new ReentrantReadWriteLock();

    private Connection(HANDLE pipe, String owner) {
      super(owner);
      this.pipe = pipe;
    }

    @Override
    int read(ByteBuffer buffer) throws IOException {
      byte[] chunk = new byte[Math.min(buffer.remaining(), 65536)];
      operations.readLock().lock();
      try {
        if (closed.get()) {
          return -1;
        }
        int count = transfer(pipe, chunk, false, 30000);
        if (count > 0) {
          buffer.put(chunk, 0, count);
        }
        return count == 0 ? -1 : count;
      } finally {
        Arrays.fill(chunk, (byte) 0);
        operations.readLock().unlock();
      }
    }

    @Override
    void write(ByteBuffer buffer) throws IOException {
      byte[] chunk = new byte[Math.min(buffer.remaining(), 65536)];
      buffer.get(chunk);
      operations.readLock().lock();
      try {
        if (closed.get()) {
          throw new IOException("Канал IPC закрыт");
        }
        if (transfer(pipe, chunk, true, 5000) != chunk.length) {
          throw failure("Ошибка записи IPC");
        }
      } finally {
        Arrays.fill(chunk, (byte) 0);
        operations.readLock().unlock();
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        IoApi.INSTANCE.CancelIoEx(pipe, null);
        operations.writeLock().lock();
        try {
          Kernel32.INSTANCE.CloseHandle(pipe);
        } finally {
          operations.writeLock().unlock();
        }
      }
    }
  }

  private static WinBase.OVERLAPPED overlapped() throws IOException {
    WinBase.OVERLAPPED result = new WinBase.OVERLAPPED();
    result.hEvent = Kernel32.INSTANCE.CreateEvent(null, true, false, null);
    if (result.hEvent == null) {
      throw failure("Не удалось создать событие IPC");
    }
    result.write();
    return result;
  }

  private static int transfer(HANDLE pipe, byte[] bytes, boolean writing, int timeout)
      throws IOException {
    WinBase.OVERLAPPED overlapped = overlapped();
    try (Memory memory = new Memory(bytes.length)) {
      try {
        if (writing) {
          memory.write(0, bytes, 0, bytes.length);
        }
        IntByReference count = new IntByReference();
        boolean started =
            writing
                ? IoApi.INSTANCE.WriteFile(pipe, memory, bytes.length, count, overlapped)
                : IoApi.INSTANCE.ReadFile(pipe, memory, bytes.length, count, overlapped);
        if (!started) {
          int error = Kernel32.INSTANCE.GetLastError();
          if (!writing && (error == 109 || error == 232 || error == 995)) {
            return -1;
          }
          if (error != 997) {
            throw failure("Не удалось выполнить операцию IPC");
          }
          int waited = Kernel32.INSTANCE.WaitForSingleObject(overlapped.hEvent, timeout);
          if (waited != 0) {
            IoApi.INSTANCE.CancelIoEx(pipe, overlapped);
            // Native buffers must outlive cancellation of the outstanding operation.
            IoApi.INSTANCE.GetOverlappedResult(pipe, overlapped, count, true);
            throw new IOException("Истекло время ожидания IPC");
          }
          if (!IoApi.INSTANCE.GetOverlappedResult(pipe, overlapped, count, false)) {
            int result = Kernel32.INSTANCE.GetLastError();
            if (!writing && (result == 109 || result == 232 || result == 995)) {
              return -1;
            }
            throw failure("Операция IPC прервана");
          }
        }
        if (!writing) {
          memory.read(0, bytes, 0, count.getValue());
        }
        return count.getValue();
      } finally {
        memory.clear();
      }
    } finally {
      Kernel32.INSTANCE.CloseHandle(overlapped.hEvent);
    }
  }

  private static void closePipe(HANDLE pipe) {
    IoApi.INSTANCE.CancelIoEx(pipe, null);
    Kernel32.INSTANCE.CloseHandle(pipe);
  }
}
