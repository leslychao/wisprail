package app.wisprail.agent;

import app.wisprail.connection.ConnectionService;
import app.wisprail.connection.EventJournal;
import app.wisprail.connection.NetworkRuntime;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AgentServer implements AutoCloseable {
  private final LocalTransport transport;
  private final ConnectionService service;
  private final EventJournal journal;
  private final FileChannel lock;
  private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
  private volatile LocalTransport.Session session;
  private volatile long lastRequest = System.nanoTime();
  private volatile boolean closed;

  public AgentServer(
      LocalTransport transport, ConnectionService service, EventJournal journal, FileChannel lock) {
    this.transport = transport;
    this.service = service;
    this.journal = journal;
    this.lock = lock;
  }

  public void run() throws IOException {
    monitor.scheduleAtFixedRate(this::observe, 1, 1, TimeUnit.SECONDS);
    while (!closed) {
      boolean accepted = false;
      try (var connected = transport.accept()) {
        accepted = true;
        session = connected;
        lastRequest = System.nanoTime();
        while (!closed) {
          var request = AgentFrames.read(connected.input(), AgentProtocol.Request.class);
          lastRequest = System.nanoTime();
          AgentFrames.write(connected.output(), dispatch(request));
        }
      } catch (EOFException exception) {
        journal.add("info", null, "UI_CLOSED", "Интерфейс завершил сессию; VPN останавливается");
      } catch (IOException exception) {
        if (!closed) {
          journal.add("warning", null, "IPC_CLOSED", "Локальная сессия завершена или отклонена");
        }
      } finally {
        session = null;
        if (accepted && !closed) {
          service.disconnect();
        }
      }
      if (!closed && !accepted) {
        throw new IOException("Канал службы недоступен");
      }
    }
  }

  private AgentProtocol.Response dispatch(AgentProtocol.Request request) {
    String error = "";
    List<NetworkRuntime.Check> checks = List.of();
    try {
      if (request.version() != AgentProtocol.VERSION
          || request.id() == null
          || request.command() == null) {
        throw new IllegalArgumentException("Несовместимый протокол службы");
      }
      switch (request.command()) {
        case STATUS -> {}
        case PREPARE -> {
          if (request.profile() == null || request.secrets() == null) {
            throw new IllegalArgumentException("Не передан профиль");
          }
          service.prepare(request.profile(), request.secrets());
        }
        case CONNECT -> service.connect(request.token(), request.revision());
        case CANCEL -> service.cancel(request.token());
        case DISCONNECT -> service.disconnect();
        case DIAGNOSE -> checks = service.diagnose();
      }
    } catch (IllegalArgumentException | IllegalStateException exception) {
      error = exception.getMessage();
    } catch (IOException exception) {
      error = "Проверка не завершена: компонент, маршруты или ресурс профиля недоступны";
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      error = "Проверка прервана";
    }
    return new AgentProtocol.Response(
        AgentProtocol.VERSION,
        request.id(),
        service.snapshot(),
        journal.after(request.afterSequence()),
        checks,
        error,
        journal.persistenceError());
  }

  private void observe() {
    service.observeProcess();
    if (session != null && System.nanoTime() - lastRequest > Duration.ofSeconds(60).toNanos()) {
      try {
        session.close();
      } catch (IOException exception) {
        journal.add("error", null, "IPC_CLOSE_FAILED", "Не удалось закрыть локальную сессию");
      }
      service.disconnect();
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    monitor.shutdownNow();
    try {
      if (session != null) {
        session.close();
      }
      transport.close();
    } finally {
      try {
        service.close();
      } finally {
        lock.close();
      }
    }
  }
}
