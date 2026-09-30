package app.wisprail.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentClient implements AutoCloseable {
  private static final ScheduledExecutorService TIMEOUTS =
      Executors.newSingleThreadScheduledExecutor(
          runnable ->
              Thread.ofPlatform().daemon().name("wisprail-ipc-timeouts").unstarted(runnable));
  private final AtomicReference<LocalTransport.Session> session = new AtomicReference<>();

  public synchronized AgentProtocol.Response exchange(AgentProtocol.Request request)
      throws IOException {
    LocalTransport.Session active = currentSession();
    var timeout =
        TIMEOUTS.schedule(
            () -> {
              if (session.compareAndSet(active, null)) {
                try {
                  active.close();
                } catch (IOException exception) {
                  System.err.println("Wisprail: IPC timeout cleanup failed");
                }
              }
            },
            35,
            TimeUnit.SECONDS);
    try {
      AgentFrames.write(active.output(), request);
      var response = AgentFrames.read(active.input(), AgentProtocol.Response.class);
      if (response.version() != AgentProtocol.VERSION || !response.id().equals(request.id())) {
        throw new IOException("Несовместимый ответ службы");
      }
      return response;
    } catch (IOException exception) {
      close();
      throw exception;
    } finally {
      timeout.cancel(false);
    }
  }

  private LocalTransport.Session currentSession() throws IOException {
    LocalTransport.Session active = session.get();
    if (active != null) {
      return active;
    }
    LocalTransport.Session opened =
        System.getProperty("os.name").startsWith("Windows")
            ? WindowsPipeTransport.connect()
            : UnixTransport.connect(Path.of("/var/run/wisprail/control.sock"));
    session.set(opened);
    return opened;
  }

  @Override
  public void close() throws IOException {
    LocalTransport.Session active = session.getAndSet(null);
    if (active != null) {
      active.close();
    }
  }
}
