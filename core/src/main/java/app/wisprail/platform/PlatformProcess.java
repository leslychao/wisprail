package app.wisprail.platform;

import app.wisprail.storage.JsonCodec;
import app.wisprail.storage.PrivateFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns native process handles. Windows activation follows the caller's durable ownership write. */
public final class PlatformProcess {
  private PlatformProcess() {}

  /** Returns only processes with the exact executable and an exact sing-box config argument. */
  public static List<ProcessHandle> findByCommand(Path executable, String configArgument)
      throws IOException {
    if (PlatformServices.isWindows()) {
      return findWindowsProcesses(executable.toAbsolutePath().normalize(), configArgument);
    }
    List<ProcessHandle> matching = new ArrayList<>();
    try (var processes = ProcessHandle.allProcesses()) {
      var remaining = processes.iterator();
      while (remaining.hasNext()) {
        ProcessHandle process = remaining.next();
        ProcessHandle.Info info = process.info();
        if (info.command().isEmpty() || !Path.of(info.command().orElseThrow()).equals(executable)) {
          continue;
        }
        String[] arguments =
            info.arguments()
                .orElseThrow(
                    () ->
                        new IOException(
                            "Не удалось подтвердить аргументы оставшегося процесса VPN"));
        if (List.of(arguments).equals(List.of("run", "-c", configArgument))) {
          if (matching.size() >= 64) {
            throw new IOException("Слишком много оставшихся процессов VPN");
          }
          matching.add(process);
        }
      }
    }
    return List.copyOf(matching);
  }

  private static List<ProcessHandle> findWindowsProcesses(Path executable, String config)
      throws IOException {
    Path script = Files.createTempFile("wisprail-processes-", ".ps1");
    try {
      PrivateFiles.restrict(script);
      try (var resource =
          PlatformProcess.class.getResourceAsStream("/platform/windows-processes.ps1")) {
        if (resource == null) {
          throw new IOException("Компонент проверки процессов отсутствует");
        }
        byte[] bytes = resource.readNBytes(65536);
        if (resource.read() != -1) {
          throw new IOException("Слишком большой компонент проверки процессов");
        }
        Files.write(script, bytes);
      }
      JsonObject request = new JsonObject();
      request.addProperty("executable", executable.toString());
      Path powershell =
          Path.of(
              System.getenv("SystemRoot"),
              "System32",
              "WindowsPowerShell",
              "v1.0",
              "powershell.exe");
      String output =
          PlatformCommand.run(
              List.of(
                  powershell.toString(),
                  "-NoProfile",
                  "-NonInteractive",
                  "-ExecutionPolicy",
                  "RemoteSigned",
                  "-File",
                  script.toString()),
              JsonCodec.gson().toJson(request).getBytes(StandardCharsets.UTF_8),
              Duration.ofSeconds(20));
      JsonObject result = JsonParser.parseString(output).getAsJsonObject();
      List<ProcessHandle> matches = new ArrayList<>();
      for (JsonElement item : result.getAsJsonArray("processes")) {
        JsonObject value = item.getAsJsonObject();
        ProcessHandle process = ProcessHandle.of(value.get("pid").getAsLong()).orElse(null);
        if (process == null || !process.isAlive()) {
          continue;
        }
        if (!value.get("identified").getAsBoolean()) {
          throw new IOException("Не удалось установить владельца оставшегося процесса VPN");
        }
        long started = value.get("started").getAsLong();
        if (process.info().startInstant().isEmpty()
            || process.info().startInstant().orElseThrow().toEpochMilli() != started) {
          throw new IOException("Идентификатор процесса изменился во время проверки");
        }
        List<String> arguments = windowsArguments(value.get("command").getAsString());
        if (arguments.size() == 4 && arguments.subList(1, 4).equals(List.of("run", "-c", config))) {
          matches.add(process);
        }
      }
      return List.copyOf(matches);
    } catch (IllegalStateException | IllegalArgumentException exception) {
      throw new IOException("Не удалось проверить оставшийся процесс VPN", exception);
    } finally {
      Files.deleteIfExists(script);
    }
  }

  private static List<String> windowsArguments(String command) throws IOException {
    IntByReference count = new IntByReference();
    Pointer arguments = Shell32.INSTANCE.CommandLineToArgvW(new WString(command), count);
    if (arguments == null) {
      throw nativeFailure("Не удалось проверить аргументы процесса VPN");
    }
    try {
      if (count.getValue() > 128) {
        throw new IOException("Слишком много аргументов оставшегося процесса VPN");
      }
      List<String> result = new ArrayList<>(count.getValue());
      for (int index = 0; index < count.getValue(); index++) {
        result.add(arguments.getPointer((long) index * Native.POINTER_SIZE).getWideString(0));
      }
      return result;
    } finally {
      Kernel32.INSTANCE.LocalFree(arguments);
    }
  }

  public interface Launch extends AutoCloseable {
    ProcessHandle process();

    void activate() throws IOException;

    @Override
    void close() throws IOException;
  }

  public static Launch prepareLaunch(Path executable, List<String> arguments) throws IOException {
    if (PlatformServices.isWindows()) {
      return WindowsLaunch.create(executable, arguments);
    }
    List<String> command = new ArrayList<>();
    command.add(executable.toString());
    command.addAll(arguments);
    Process process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    return new Launch() {
      @Override
      public ProcessHandle process() {
        return process.toHandle();
      }

      @Override
      public void activate() {
        // POSIX ProcessBuilder has already started; the caller persists launch intent beforehand.
      }

      @Override
      public void close() {
        if (process.isAlive()) {
          process.destroyForcibly();
        }
      }
    };
  }

  private interface JobApi extends StdCallLibrary {
    JobApi INSTANCE = Native.load("Kernel32", JobApi.class);

    HANDLE CreateJobObjectW(WinBase.SECURITY_ATTRIBUTES attributes, WString name);

    boolean SetInformationJobObject(HANDLE job, int type, Structure value, int size);

    boolean InitializeProcThreadAttributeList(
        Pointer attributes, int count, int flags, BaseTSD.ULONG_PTRByReference size);

    boolean UpdateProcThreadAttribute(
        Pointer attributes,
        int flags,
        BaseTSD.ULONG_PTR attribute,
        Pointer value,
        BaseTSD.SIZE_T size,
        Pointer previousValue,
        Pointer returnSize);

    void DeleteProcThreadAttributeList(Pointer attributes);

    boolean CreateProcessW(
        WString executable,
        char[] command,
        Pointer processSecurity,
        Pointer threadSecurity,
        boolean inheritHandles,
        int flags,
        Pointer environment,
        WString directory,
        StartupInfoEx startup,
        WinBase.PROCESS_INFORMATION process);

    boolean TerminateJobObject(HANDLE job, int exitCode);

    int ResumeThread(HANDLE thread);
  }

  @FieldOrder({"startup", "attributes"})
  public static final class StartupInfoEx extends Structure {
    public WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
    public Pointer attributes;
  }

  @FieldOrder({
    "processTime",
    "jobTime",
    "flags",
    "minimumWorkingSet",
    "maximumWorkingSet",
    "activeProcesses",
    "affinity",
    "priority",
    "scheduling"
  })
  public static final class BasicLimits extends Structure {
    public long processTime;
    public long jobTime;
    public int flags;
    public BaseTSD.SIZE_T minimumWorkingSet = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T maximumWorkingSet = new BaseTSD.SIZE_T();
    public int activeProcesses;
    public BaseTSD.ULONG_PTR affinity = new BaseTSD.ULONG_PTR();
    public int priority;
    public int scheduling;
  }

  @FieldOrder({
    "readOperations",
    "writeOperations",
    "otherOperations",
    "readBytes",
    "writeBytes",
    "otherBytes"
  })
  public static final class IoCounters extends Structure {
    public long readOperations;
    public long writeOperations;
    public long otherOperations;
    public long readBytes;
    public long writeBytes;
    public long otherBytes;
  }

  @FieldOrder({"basic", "io", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory"})
  public static final class ExtendedLimits extends Structure {
    public BasicLimits basic = new BasicLimits();
    public IoCounters io = new IoCounters();
    public BaseTSD.SIZE_T processMemory = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T jobMemory = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T peakProcessMemory = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T peakJobMemory = new BaseTSD.SIZE_T();
  }

  private static final class WindowsLaunch implements Launch {
    private final HANDLE job;
    private final WinBase.PROCESS_INFORMATION nativeProcess;
    private final ProcessHandle process;
    private final AtomicBoolean activated = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private WindowsLaunch(
        HANDLE job, WinBase.PROCESS_INFORMATION nativeProcess, ProcessHandle process) {
      this.job = job;
      this.nativeProcess = nativeProcess;
      this.process = process;
    }

    private static Launch create(Path executable, List<String> arguments) throws IOException {
      if (Native.POINTER_SIZE != Long.BYTES) {
        throw new IOException("Системный компонент требует 64-разрядную Windows");
      }
      HANDLE job = JobApi.INSTANCE.CreateJobObjectW(null, null);
      if (job == null) {
        throw nativeFailure("Не удалось создать группу процесса VPN");
      }
      WinBase.PROCESS_INFORMATION created = new WinBase.PROCESS_INFORMATION();
      boolean processCreated = false;
      try {
        ExtendedLimits limits = new ExtendedLimits();
        limits.basic.flags = 0x00002000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        limits.write();
        if (!JobApi.INSTANCE.SetInformationJobObject(job, 9, limits, limits.size())) {
          throw nativeFailure("Не удалось защитить жизненный цикл процесса VPN");
        }
        StringBuilder command = new StringBuilder(quote(executable.toString()));
        for (String argument : arguments) {
          command.append(' ').append(quote(argument));
        }
        createInJob(executable, command.toString(), job, created);
        processCreated = true;
        ProcessHandle handle =
            ProcessHandle.of(Integer.toUnsignedLong(created.dwProcessId.intValue()))
                .orElseThrow(() -> new IOException("Не удалось подтвердить созданный процесс VPN"));
        return new WindowsLaunch(job, created, handle);
      } catch (IOException | RuntimeException exception) {
        if (processCreated) {
          Kernel32.INSTANCE.TerminateProcess(created.hProcess, 1);
          Kernel32.INSTANCE.CloseHandle(created.hThread);
          Kernel32.INSTANCE.CloseHandle(created.hProcess);
        }
        Kernel32.INSTANCE.CloseHandle(job);
        throw exception;
      }
    }

    private static void createInJob(
        Path executable, String command, HANDLE job, WinBase.PROCESS_INFORMATION process)
        throws IOException {
      var size = new BaseTSD.ULONG_PTRByReference();
      JobApi.INSTANCE.InitializeProcThreadAttributeList(null, 1, 0, size);
      long bytes = size.getValue().longValue();
      if (bytes < 1 || bytes > 65536) {
        throw nativeFailure("Не удалось подготовить владение процессом VPN");
      }
      try (Memory attributes = new Memory(bytes);
          Memory jobs = new Memory(Native.POINTER_SIZE)) {
        if (!JobApi.INSTANCE.InitializeProcThreadAttributeList(attributes, 1, 0, size)) {
          throw nativeFailure("Не удалось подготовить атрибуты процесса VPN");
        }
        try {
          jobs.setPointer(0, job.getPointer());
          // Win10+ assigns this job atomically during creation, closing the orphan-process window.
          if (!JobApi.INSTANCE.UpdateProcThreadAttribute(
              attributes,
              0,
              new BaseTSD.ULONG_PTR(0x0002000d),
              jobs,
              new BaseTSD.SIZE_T(Native.POINTER_SIZE),
              null,
              null)) {
            throw nativeFailure("Не удалось установить владение процессом VPN");
          }
          StartupInfoEx startup = new StartupInfoEx();
          startup.startup.cb = new DWORD(startup.size());
          startup.attributes = attributes;
          if (!JobApi.INSTANCE.CreateProcessW(
              new WString(executable.toString()),
              Native.toCharArray(command),
              null,
              null,
              false,
              0x08000000 | 0x00000004 | 0x00080000,
              null,
              new WString(executable.toAbsolutePath().getParent().toString()),
              startup,
              process)) {
            throw nativeFailure("Не удалось создать процесс VPN");
          }
        } finally {
          JobApi.INSTANCE.DeleteProcThreadAttributeList(attributes);
        }
      }
    }

    @Override
    public ProcessHandle process() {
      return process;
    }

    @Override
    public void activate() throws IOException {
      if (closed.get()) {
        throw new IOException("Процесс VPN уже закрыт");
      }
      if (activated.compareAndSet(false, true)
          && JobApi.INSTANCE.ResumeThread(nativeProcess.hThread) == -1) {
        throw nativeFailure("Не удалось запустить подготовленный процесс VPN");
      }
    }

    @Override
    public void close() throws IOException {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      try {
        if (process.isAlive() && !JobApi.INSTANCE.TerminateJobObject(job, 1)) {
          throw nativeFailure("Не удалось остановить собственную группу процесса VPN");
        }
      } finally {
        Kernel32.INSTANCE.CloseHandle(nativeProcess.hThread);
        Kernel32.INSTANCE.CloseHandle(nativeProcess.hProcess);
        Kernel32.INSTANCE.CloseHandle(job);
      }
    }
  }

  static String quote(String value) {
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Недопустимый аргумент процесса");
    }
    StringBuilder result = new StringBuilder("\"");
    int slashes = 0;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\\') {
        slashes++;
      } else {
        result.append("\\".repeat(character == '"' ? slashes * 2 + 1 : slashes));
        result.append(character);
        slashes = 0;
      }
    }
    return result.append("\\".repeat(slashes * 2)).append('"').toString();
  }

  private static IOException nativeFailure(String message) {
    return new IOException(message + " (код ОС " + Kernel32.INSTANCE.GetLastError() + ")");
  }
}
