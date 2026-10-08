package app.wisprail.platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Runs fixed platform tools with bounded output and no command-string evaluation. */
final class PlatformCommand {
  private PlatformCommand() {}

  static String run(List<String> command, byte[] input, Duration timeout) throws IOException {
    Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var output = tasks.submit(() -> readBounded(process.getInputStream(), 8 * 1024 * 1024));
      var errors = tasks.submit(() -> readBounded(process.getErrorStream(), 64 * 1024));
      var writer =
          tasks.submit(
              () -> {
                try (var stdin = process.getOutputStream()) {
                  stdin.write(input);
                }
                return null;
              });
      try {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          throw new IOException("Превышено время системной операции");
        }
        byte[] result = output.get();
        errors.get();
        writer.get();
        if (process.exitValue() != 0) {
          throw new IOException("Системная операция отклонена (код " + process.exitValue() + ")");
        }
        return new String(result, StandardCharsets.UTF_8);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw new IOException("Системная операция прервана", exception);
      } catch (ExecutionException exception) {
        process.destroyForcibly();
        throw new IOException(
            "Не удалось прочитать ответ системной операции", exception.getCause());
      } finally {
        process.destroy();
      }
    }
  }

  private static byte[] readBounded(InputStream input, int limit) throws IOException {
    try (input;
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      boolean excessive = false;
      while ((count = input.read(buffer)) >= 0) {
        if (output.size() + count <= limit) {
          output.write(buffer, 0, count);
        } else {
          excessive = true;
        }
      }
      if (excessive) {
        throw new IOException("Слишком большой ответ системной операции");
      }
      return output.toByteArray();
    }
  }
}
