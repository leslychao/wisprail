package app.wisprail.engine;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Bounds native resolver work even when an OS lookup does not respond to interruption. */
final class AddressResolver {
  private static final ThreadPoolExecutor WORKER =
      new ThreadPoolExecutor(
          0,
          1,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          Thread.ofPlatform().daemon().name("wisprail-resolver").factory());

  private AddressResolver() {}

  static InetAddress[] resolve(String host, BooleanSupplier cancelled) throws IOException {
    Future<InetAddress[]> result;
    try {
      result = WORKER.submit(() -> InetAddress.getAllByName(host));
    } catch (RejectedExecutionException exception) {
      throw new IOException("Системный DNS ещё выполняет предыдущий запрос", exception);
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try {
      while (!cancelled.getAsBoolean() && System.nanoTime() < deadline) {
        try {
          return result.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
          // Check cancellation while the native resolver is running.
        }
      }
      throw new IOException("Разрешение адреса не завершено вовремя или отменено");
    } catch (ExecutionException exception) {
      throw new IOException("Не удалось разрешить адрес IPv4", exception.getCause());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Разрешение адреса прервано", exception);
    } finally {
      result.cancel(true);
    }
  }
}
