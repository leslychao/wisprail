package app.wisprail.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public interface EngineProcess extends AutoCloseable {
  long pid();

  Instant startedAt();

  boolean isAlive();

  void stop() throws IOException, InterruptedException;

  @Override
  void close() throws IOException;

  static EngineProcess start(Path binary, Path config) throws IOException {
    if (System.getProperty("os.name").startsWith("Windows")) {
      return new WindowsEngineProcess(binary, config);
    }
    Process process =
        new ProcessBuilder(binary.toString(), "run", "-c", config.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    process.getOutputStream().close();
    return new EngineProcess() {
      @Override
      public long pid() {
        return process.pid();
      }

      @Override
      public Instant startedAt() {
        return process.info().startInstant().orElseThrow();
      }

      @Override
      public boolean isAlive() {
        return process.isAlive();
      }

      @Override
      public void stop() throws IOException, InterruptedException {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          if (!process.waitFor(5, TimeUnit.SECONDS)) {
            throw new IOException("Не удалось остановить сетевой процесс");
          }
        }
      }

      @Override
      public void close() throws IOException {
        try {
          stop();
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IOException("Остановка прервана", exception);
        }
      }
    };
  }
}
