package app.wisprail.agent;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

public final class WindowsPipeTransport implements LocalTransport {
  public static final String PIPE = "\\\\.\\pipe\\Wisprail.Control.v1";
  private static final PipeApi PIPE_API =
      Native.load("kernel32", PipeApi.class, W32APIOptions.DEFAULT_OPTIONS);
  private final String allowedSid;
  private volatile WinNT.HANDLE listener;
  private volatile boolean closed;

  public interface PipeApi extends StdCallLibrary {
    boolean CancelIoEx(WinNT.HANDLE handle, Pointer overlapped);
  }

  public interface SecurityApi extends StdCallLibrary {
    boolean ConvertStringSecurityDescriptorToSecurityDescriptor(
        String sddl, int revision, PointerByReference descriptor, IntByReference size);

    int GetSecurityInfo(
        WinNT.HANDLE object,
        int type,
        int information,
        PointerByReference owner,
        PointerByReference group,
        PointerByReference dacl,
        PointerByReference sacl,
        PointerByReference descriptor);
  }

  public WindowsPipeTransport(String allowedSid) {
    if (!allowedSid.matches("S-1-[0-9-]+")) {
      throw new IllegalArgumentException("Некорректный SID владельца установки");
    }
    this.allowedSid = allowedSid;
  }

  @Override
  public Session accept() throws IOException {
    if (closed) {
      throw new IOException("Служба остановлена");
    }
    SecurityApi security =
        Native.load("advapi32", SecurityApi.class, W32APIOptions.DEFAULT_OPTIONS);
    PointerByReference descriptor = new PointerByReference();
    String sddl = "O:SYG:SYD:P(A;;GA;;;SY)(A;;GA;;;" + allowedSid + ")";
    if (!security.ConvertStringSecurityDescriptorToSecurityDescriptor(sddl, 1, descriptor, null)) {
      throw new IOException("Не удалось создать ACL канала");
    }
    WinBase.SECURITY_ATTRIBUTES attributes = new WinBase.SECURITY_ATTRIBUTES();
    attributes.dwLength = new WinDef.DWORD(attributes.size());
    attributes.lpSecurityDescriptor = descriptor.getValue();
    WinNT.HANDLE handle;
    try {
      // Duplex, first-instance ownership, byte mode, reject remote clients, one UI lease.
      synchronized (this) {
        if (closed) {
          throw new IOException("Служба остановлена");
        }
        handle =
            Kernel32.INSTANCE.CreateNamedPipe(
                PIPE, 3 | 0x00080000, 0x00000008, 1, 65536, 65536, 5000, attributes);
        listener = handle;
      }
    } finally {
      Kernel32.INSTANCE.LocalFree(descriptor.getValue());
    }
    if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
      listener = null;
      throw new IOException("Канал Wisprail занят или недоступен");
    }
    if (!Kernel32.INSTANCE.ConnectNamedPipe(handle, null)
        && Kernel32.INSTANCE.GetLastError() != 535) {
      closeListener(handle);
      throw new IOException("Не удалось принять локальное подключение");
    }
    try {
      WinDef.ULONGByReference processId = new WinDef.ULONGByReference();
      if (!Kernel32.INSTANCE.GetNamedPipeClientProcessId(handle, processId)
          || !processSid(processId.getValue().intValue()).equals(allowedSid)) {
        throw new IOException("Пользователь IPC-клиента не разрешён");
      }
      synchronized (this) {
        if (closed) {
          throw new IOException("Служба остановлена");
        }
        listener = null;
        return session(handle);
      }
    } catch (IOException exception) {
      closeListener(handle);
      throw exception;
    }
  }

  public static Session connect() throws IOException {
    if (!Kernel32.INSTANCE.WaitNamedPipe(PIPE, 3000)) {
      throw new IOException(
          "Служба Wisprail недоступна. Установите или запустите системный компонент.");
    }
    WinNT.HANDLE handle =
        Kernel32.INSTANCE.CreateFile(
            PIPE, WinNT.GENERIC_READ | WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING, 0, null);
    if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
      throw new IOException("Нет доступа к службе Wisprail");
    }
    if (!systemOwned(handle)) {
      Kernel32.INSTANCE.CloseHandle(handle);
      throw new IOException("Не подтверждён системный владелец службы");
    }
    return session(handle);
  }

  private static boolean systemOwned(WinNT.HANDLE pipe) {
    SecurityApi security =
        Native.load("advapi32", SecurityApi.class, W32APIOptions.DEFAULT_OPTIONS);
    PointerByReference owner = new PointerByReference();
    PointerByReference descriptor = new PointerByReference();
    if (security.GetSecurityInfo(pipe, 6, 1, owner, null, null, null, descriptor) != 0) {
      return false;
    }
    try {
      return Advapi32Util.convertSidToStringSid(new WinNT.PSID(owner.getValue()))
          .equals("S-1-5-18");
    } finally {
      Kernel32.INSTANCE.LocalFree(descriptor.getValue());
    }
  }

  private static String processSid(int processId) throws IOException {
    WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(0x1000, false, processId);
    if (process == null) {
      throw new IOException("Не удалось проверить IPC-процесс");
    }
    WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
    try {
      if (!Advapi32.INSTANCE.OpenProcessToken(process, WinNT.TOKEN_QUERY, token)) {
        throw new IOException("Не удалось проверить пользователя IPC-процесса");
      }
      try {
        return Advapi32Util.getTokenAccount(token.getValue()).sidString;
      } finally {
        Kernel32.INSTANCE.CloseHandle(token.getValue());
      }
    } finally {
      Kernel32.INSTANCE.CloseHandle(process);
    }
  }

  private static Session session(WinNT.HANDLE handle) {
    InputStream input =
        new InputStream() {
          @Override
          public int read() throws IOException {
            byte[] bytes = new byte[1];
            return read(bytes, 0, 1) == -1 ? -1 : bytes[0] & 255;
          }

          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
              return 0;
            }
            byte[] buffer = new byte[Math.min(length, 65536)];
            IntByReference received = new IntByReference();
            if (!Kernel32.INSTANCE.ReadFile(handle, buffer, buffer.length, received, null)) {
              int error = Kernel32.INSTANCE.GetLastError();
              if (error == 109 || error == 233) {
                return -1;
              }
              throw new IOException("Ошибка чтения локального канала: " + error);
            }
            System.arraycopy(buffer, 0, bytes, offset, received.getValue());
            return received.getValue();
          }
        };
    OutputStream output =
        new OutputStream() {
          @Override
          public void write(int value) throws IOException {
            write(new byte[] {(byte) value});
          }

          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            int written = 0;
            while (written < length) {
              byte[] buffer =
                  Arrays.copyOfRange(
                      bytes, offset + written, offset + Math.min(length, written + 65536));
              IntByReference count = new IntByReference();
              if (!Kernel32.INSTANCE.WriteFile(handle, buffer, buffer.length, count, null)
                  || count.getValue() == 0) {
                throw new IOException("Ошибка записи локального канала");
              }
              written += count.getValue();
            }
          }
        };
    return new Session() {
      private boolean sessionClosed;

      @Override
      public InputStream input() {
        return input;
      }

      @Override
      public OutputStream output() {
        return output;
      }

      @Override
      public synchronized void close() {
        if (!sessionClosed) {
          PIPE_API.CancelIoEx(handle, null);
          Kernel32.INSTANCE.CloseHandle(handle);
          sessionClosed = true;
        }
      }
    };
  }

  @Override
  public synchronized void close() {
    closed = true;
    WinNT.HANDLE handle = listener;
    if (handle != null && !WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
      closeListener(handle);
    }
  }

  private synchronized void closeListener(WinNT.HANDLE handle) {
    if (handle.equals(listener)) {
      listener = null;
      PIPE_API.CancelIoEx(handle, null);
      Kernel32.INSTANCE.CloseHandle(handle);
    }
  }
}
