package app.wisprail.engine;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/** Assigns the suspended child to a kill-on-close Job before any VPN code can run. */
final class WindowsEngineProcess implements EngineProcess {
  private static final int JOB_EXTENDED_LIMIT_INFORMATION = 9;
  private static final int KILL_ON_JOB_CLOSE = 0x2000;
  private static final int CREATE_SUSPENDED = 0x00000004;
  private static final int CREATE_NEW_CONSOLE = 0x00000010;
  private static final int WAIT_TIMEOUT = 258;
  private final JobApi api = Native.load("kernel32", JobApi.class, W32APIOptions.DEFAULT_OPTIONS);
  private final WinNT.HANDLE job;
  private final WinNT.HANDLE process;
  private final long pid;
  private final Instant startedAt;
  private boolean closed;

  public interface JobApi extends StdCallLibrary {
    WinNT.HANDLE CreateJobObject(WinBase.SECURITY_ATTRIBUTES attributes, String name);

    boolean SetInformationJobObject(WinNT.HANDLE job, int type, Pointer information, int length);

    boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);

    int ResumeThread(WinNT.HANDLE thread);

    boolean AttachConsole(int processId);

    boolean FreeConsole();

    boolean SetConsoleCtrlHandler(Pointer handler, boolean add);

    boolean GenerateConsoleCtrlEvent(int event, int processGroup);
  }

  @Structure.FieldOrder({
    "processTime",
    "jobTime",
    "limitFlags",
    "minimumWorkingSet",
    "maximumWorkingSet",
    "activeProcessLimit",
    "affinity",
    "priorityClass",
    "schedulingClass"
  })
  public static class BasicLimits extends Structure {
    public long processTime;
    public long jobTime;
    public int limitFlags;
    public BaseTSD.SIZE_T minimumWorkingSet = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T maximumWorkingSet = new BaseTSD.SIZE_T();
    public int activeProcessLimit;
    public BaseTSD.ULONG_PTR affinity = new BaseTSD.ULONG_PTR();
    public int priorityClass;
    public int schedulingClass;
  }

  @Structure.FieldOrder({
    "basic",
    "ioCounters",
    "processMemoryLimit",
    "jobMemoryLimit",
    "peakProcessMemory",
    "peakJobMemory"
  })
  public static class ExtendedLimits extends Structure {
    public BasicLimits basic = new BasicLimits();
    public long[] ioCounters = new long[6];
    public BaseTSD.SIZE_T processMemoryLimit = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T jobMemoryLimit = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T peakProcessMemory = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T peakJobMemory = new BaseTSD.SIZE_T();
  }

  WindowsEngineProcess(Path binary, Path config) throws IOException {
    job = api.CreateJobObject(null, null);
    if (job == null) {
      throw new IOException("Не удалось создать Windows Job");
    }
    ExtendedLimits limits = new ExtendedLimits();
    limits.basic.limitFlags = KILL_ON_JOB_CLOSE;
    limits.write();
    if (!api.SetInformationJobObject(
        job, JOB_EXTENDED_LIMIT_INFORMATION, limits.getPointer(), limits.size())) {
      Kernel32.INSTANCE.CloseHandle(job);
      throw new IOException("Не удалось включить завершение дочернего процесса вместе со службой");
    }
    WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
    startup.cb = new WinDef.DWORD(startup.size());
    startup.dwFlags = WinBase.STARTF_USESHOWWINDOW;
    startup.wShowWindow = new WinDef.WORD(0);
    WinBase.PROCESS_INFORMATION information = new WinBase.PROCESS_INFORMATION();
    String command = "\"" + binary + "\" run -c \"" + config + "\"";
    if (!Kernel32.INSTANCE.CreateProcessW(
        binary.toString(),
        Native.toCharArray(command),
        null,
        null,
        false,
        new WinDef.DWORD(CREATE_SUSPENDED | CREATE_NEW_CONSOLE),
        null,
        binary.getParent().toString(),
        startup,
        information)) {
      Kernel32.INSTANCE.CloseHandle(job);
      throw new IOException("Windows не разрешила запуск sing-box");
    }
    process = information.hProcess;
    pid = information.dwProcessId.longValue();
    try {
      if (!api.AssignProcessToJobObject(job, process)
          || api.ResumeThread(information.hThread) == -1) {
        Kernel32.INSTANCE.TerminateProcess(process, 1);
        Kernel32.INSTANCE.CloseHandle(process);
        Kernel32.INSTANCE.CloseHandle(job);
        throw new IOException("Не удалось установить владение процессом sing-box");
      }
    } finally {
      Kernel32.INSTANCE.CloseHandle(information.hThread);
    }
    startedAt = ProcessHandle.of(pid).flatMap(handle -> handle.info().startInstant()).orElseThrow();
  }

  @Override
  public long pid() {
    return pid;
  }

  @Override
  public Instant startedAt() {
    return startedAt;
  }

  @Override
  public boolean isAlive() {
    return !closed && Kernel32.INSTANCE.WaitForSingleObject(process, 0) == WAIT_TIMEOUT;
  }

  @Override
  public void stop() throws IOException {
    if (!isAlive()) {
      return;
    }
    if (api.AttachConsole((int) pid)) {
      try {
        if (api.SetConsoleCtrlHandler(null, true)) {
          api.GenerateConsoleCtrlEvent(0, 0);
        }
      } finally {
        api.FreeConsole();
      }
    }
    if (Kernel32.INSTANCE.WaitForSingleObject(process, 10000) == WAIT_TIMEOUT) {
      if (!Kernel32.INSTANCE.TerminateProcess(process, 1)
          || Kernel32.INSTANCE.WaitForSingleObject(process, 5000) != WinBase.WAIT_OBJECT_0) {
        throw new IOException("Не удалось завершить sing-box");
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      try {
        stop();
      } finally {
        Kernel32.INSTANCE.CloseHandle(process);
        Kernel32.INSTANCE.CloseHandle(job);
        closed = true;
      }
    }
  }
}
