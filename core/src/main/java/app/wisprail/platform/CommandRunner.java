package app.wisprail.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Bounded subprocess execution; arguments are passed directly, never interpreted as shell code. */
public final class CommandRunner {
  private static final int OUTPUT_LIMIT = 1024 * 1024;

  public record Result(int exitCode, String output) {}

  public Result run(List<String> command, byte[] input, Duration timeout)
      throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    CompletableFuture<byte[]> output =
        CompletableFuture.supplyAsync(
            () -> {
              try (var stream = process.getInputStream()) {
                byte[] bytes = stream.readNBytes(OUTPUT_LIMIT + 1);
                if (bytes.length > OUTPUT_LIMIT) {
                  process.destroyForcibly();
                  throw new UncheckedIOException(new IOException("Вывод процесса превысил лимит"));
                }
                return bytes;
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              }
            });
    try {
      try (var stream = process.getOutputStream()) {
        stream.write(input);
      }
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IOException("Превышено время ожидания процесса");
      }
      try {
        return new Result(process.exitValue(), new String(output.get(), StandardCharsets.UTF_8));
      } catch (ExecutionException exception) {
        throw new IOException("Не удалось получить результат процесса", exception.getCause());
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
