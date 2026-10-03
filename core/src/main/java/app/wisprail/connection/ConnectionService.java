package app.wisprail.connection;

import static app.wisprail.connection.ConnectionSnapshot.State.CONNECTED;
import static app.wisprail.connection.ConnectionSnapshot.State.CONNECTING;
import static app.wisprail.connection.ConnectionSnapshot.State.DISCONNECTED;
import static app.wisprail.connection.ConnectionSnapshot.State.DISCONNECTING;
import static app.wisprail.connection.ConnectionSnapshot.State.ERROR;

import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.ProfileValidator;
import app.wisprail.profile.VpnProfile;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** The sole owner of connection transitions. Preparation never interrupts an active connection. */
public final class ConnectionService implements AutoCloseable {
  private final NetworkRuntime runtime;
  private final EventJournal journal;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private volatile ConnectionSnapshot snapshot = ConnectionSnapshot.initial();
  private NetworkRuntime.Prepared candidate;
  private Instant preparedAt;
  private boolean closed;
  private boolean healthPending;
  private boolean candidateCleanupBlocked;
  private Instant lastHealthCheck = Instant.EPOCH;

  public ConnectionService(NetworkRuntime runtime, EventJournal journal) {
    this.runtime = runtime;
    this.journal = journal;
  }

  public ConnectionSnapshot snapshot() {
    return snapshot;
  }

  public synchronized UUID prepare(VpnProfile profile, ProfileSecrets secrets) {
    requireIdle();
    if (candidateCleanupBlocked) {
      throw new IllegalStateException(
          "Не удалена временная конфигурация. Перезапустите компонент для подтверждённой очистки.");
    }
    if (snapshot.errorCode().equals("CLEANUP_FAILED")
        || (!runtime.cleanupConfirmed() && snapshot.appliedProfile() == null)) {
      throw new IllegalStateException("Сначала подтвердите очистку прежнего подключения");
    }
    new ProfileValidator().requireValid(profile, secrets);
    UUID operation = UUID.randomUUID();
    cancelled.set(false);
    var previous = snapshot;
    snapshot =
        new ConnectionSnapshot(
            previous.state(),
            previous.revision(),
            operation,
            previous.appliedProfile(),
            profile,
            "Проверяем настройки",
            "",
            "",
            null,
            previous.connectedAt());
    worker.execute(() -> prepareCandidate(operation, profile, secrets, previous));
    return operation;
  }

  private void prepareCandidate(
      UUID operation, VpnProfile profile, ProfileSecrets secrets, ConnectionSnapshot previous) {
    NetworkRuntime.Prepared prepared = null;
    try {
      prepared = runtime.prepare(profile, secrets, cancelled::get);
      synchronized (this) {
        if (cancelled.get() || closed || !operation.equals(snapshot.operationId())) {
          runtime.discard(prepared);
          if (operation.equals(snapshot.operationId())) {
            snapshot = restored(previous);
          }
          return;
        }
        candidate = prepared;
        preparedAt = Instant.now();
        snapshot =
            new ConnectionSnapshot(
                previous.state(),
                previous.revision(),
                operation,
                previous.appliedProfile(),
                profile,
                "Конфигурация проверена",
                "",
                "",
                prepared.id(),
                previous.connectedAt());
      }
      event("info", profile, "CONFIG_VALID", "Конфигурация принята движком");
    } catch (IOException | IllegalArgumentException exception) {
      preparationFailed(operation, previous, profile, prepared, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      preparationFailed(operation, previous, profile, prepared, exception);
    }
  }

  private synchronized void preparationFailed(
      UUID operation,
      ConnectionSnapshot previous,
      VpnProfile profile,
      NetworkRuntime.Prepared prepared,
      Exception failure) {
    if (failure instanceof NetworkFailure specific && specific.code().equals("CANDIDATE_CLEANUP")) {
      candidateCleanupBlocked = true;
    }
    if (prepared != null) {
      try {
        runtime.discard(prepared);
      } catch (IOException exception) {
        candidateCleanupBlocked = true;
        event("error", profile, "CANDIDATE_CLEANUP", "Не удалось удалить временную конфигурацию");
      }
    }
    if (!operation.equals(snapshot.operationId())) {
      return;
    }
    if (cancelled.get() && !candidateCleanupBlocked) {
      snapshot = restored(previous);
      return;
    }
    String code = "PREPARATION_FAILED";
    String message =
        "Не удалось проверить профиль. Проверьте настройки, компонент и системные конфликты.";
    if (failure instanceof NetworkFailure specific) {
      code = specific.code();
      message = specific.getMessage();
    }
    if (candidateCleanupBlocked) {
      code = "CANDIDATE_CLEANUP";
      message =
          "Не удалось удалить временную конфигурацию. Перезапустите компонент для подтверждённой"
              + " очистки.";
    }
    snapshot =
        new ConnectionSnapshot(
            previous.state(),
            previous.revision(),
            null,
            previous.appliedProfile(),
            profile,
            "",
            code,
            message,
            null,
            previous.connectedAt());
    event("error", profile, code, message);
  }

  /** The token binds the user's confirmation to the already checked candidate and live revision. */
  public synchronized void connect(UUID token, long expectedRevision) {
    if (closed
        || candidate == null
        || !candidate.id().equals(token)
        || snapshot.revision() != expectedRevision
        || Duration.between(preparedAt, Instant.now()).compareTo(Duration.ofMinutes(2)) > 0) {
      throw new IllegalStateException("Подтверждение устарело; повторите проверку");
    }
    NetworkRuntime.Prepared prepared = candidate;
    candidate = null;
    cancelled.set(false);
    UUID operation = snapshot.operationId();
    boolean switching = snapshot.appliedProfile() != null;
    snapshot =
        new ConnectionSnapshot(
            switching ? DISCONNECTING : CONNECTING,
            snapshot.revision() + 1,
            operation,
            snapshot.appliedProfile(),
            prepared.profile(),
            switching ? "Останавливаем прежний VPN" : "Запускаем VPN",
            "",
            "",
            null,
            null);
    worker.execute(() -> activate(prepared, operation));
  }

  private void activate(NetworkRuntime.Prepared prepared, UUID operation) {
    try {
      runtime.stop();
      if (cancelled.get()) {
        runtime.discard(prepared);
        disconnected(operation, "", "");
        return;
      }
      synchronized (this) {
        if (operation.equals(snapshot.operationId())) {
          snapshot =
              new ConnectionSnapshot(
                  CONNECTING,
                  snapshot.revision(),
                  operation,
                  null,
                  prepared.profile(),
                  "Ожидаем готовности VPN",
                  "",
                  "",
                  null,
                  null);
        }
      }
      runtime.start(prepared, cancelled::get);
      synchronized (this) {
        if (!cancelled.get() && operation.equals(snapshot.operationId())) {
          snapshot =
              new ConnectionSnapshot(
                  CONNECTED,
                  snapshot.revision(),
                  null,
                  prepared.profile(),
                  null,
                  "",
                  "",
                  "",
                  null,
                  Instant.now());
          event("info", prepared.profile(), "CONNECTED", "VPN подключён для выбранных сетей");
          return;
        }
      }
      runtime.stop();
      if (operation.equals(snapshot.operationId())) {
        disconnected(operation, "", "");
      }
    } catch (IOException | IllegalStateException exception) {
      connectionFailed(prepared, operation, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      connectionFailed(prepared, operation, exception);
    }
  }

  public synchronized void cancel(UUID operation) {
    if (operation == null || !operation.equals(snapshot.operationId())) {
      return;
    }
    cancelled.set(true);
    if (candidate != null) {
      NetworkRuntime.Prepared discarded = candidate;
      candidate = null;
      worker.execute(
          () -> {
            try {
              runtime.discard(discarded);
              synchronized (this) {
                if (operation.equals(snapshot.operationId())) {
                  snapshot =
                      new ConnectionSnapshot(
                          snapshot.appliedProfile() == null ? DISCONNECTED : CONNECTED,
                          snapshot.revision(),
                          null,
                          snapshot.appliedProfile(),
                          null,
                          "",
                          "",
                          "",
                          null,
                          snapshot.connectedAt());
                }
              }
            } catch (IOException exception) {
              event(
                  "error",
                  discarded.profile(),
                  "CANDIDATE_CLEANUP",
                  "Не удалось удалить временную конфигурацию");
              synchronized (this) {
                candidateCleanupBlocked = true;
                if (operation.equals(snapshot.operationId())) {
                  snapshot =
                      new ConnectionSnapshot(
                          snapshot.appliedProfile() == null ? ERROR : CONNECTED,
                          snapshot.revision(),
                          null,
                          snapshot.appliedProfile(),
                          null,
                          "",
                          "CANDIDATE_CLEANUP",
                          "Не удалось удалить временную конфигурацию. Перезапустите системный"
                              + " компонент для повторной очистки.",
                          null,
                          snapshot.connectedAt());
                }
              }
            }
          });
    }
  }

  public synchronized void disconnect() {
    disconnect("", "");
  }

  private void disconnect(String code, String message) {
    if (worker.isShutdown()
        || snapshot.state() == DISCONNECTING && snapshot.targetProfile() == null) {
      return;
    }
    if (snapshot.busy()) {
      cancelled.set(true);
    }
    NetworkRuntime.Prepared discarded = candidate;
    candidate = null;
    UUID operation = UUID.randomUUID();
    snapshot =
        new ConnectionSnapshot(
            DISCONNECTING,
            snapshot.revision() + 1,
            operation,
            snapshot.appliedProfile(),
            null,
            "Останавливаем VPN и проверяем очистку",
            "",
            "",
            null,
            null);
    worker.execute(
        () -> {
          try {
            runtime.stop();
            if (discarded != null) {
              try {
                runtime.discard(discarded);
              } catch (IOException exception) {
                synchronized (this) {
                  candidateCleanupBlocked = true;
                }
                throw exception;
              }
            }
            disconnected(operation, code, message);
          } catch (IOException exception) {
            cleanupFailed(operation);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cleanupFailed(operation);
          }
        });
  }

  public synchronized void observeProcess() {
    if (closed) {
      return;
    }
    if (snapshot.state() == CONNECTED && !runtime.isRunning()) {
      disconnect("ENGINE_EXIT", "Сетевой компонент неожиданно завершился; VPN отключён");
    }
    if (candidate != null
        && Duration.between(preparedAt, Instant.now()).compareTo(Duration.ofMinutes(2)) > 0) {
      cancel(snapshot.operationId());
    }
    if (snapshot.state() == CONNECTED
        && snapshot.operationId() == null
        && !healthPending
        && Duration.between(lastHealthCheck, Instant.now()).compareTo(Duration.ofSeconds(15))
            >= 0) {
      healthPending = true;
      long revision = snapshot.revision();
      worker.execute(() -> checkHealth(revision));
    }
  }

  private void checkHealth(long revision) {
    boolean healthy = false;
    try {
      runtime.diagnose();
      healthy = true;
    } catch (IOException exception) {
      event("warning", null, "HEALTH_FAILED", "Готовность активного VPN не подтверждена");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      synchronized (this) {
        healthPending = false;
        lastHealthCheck = Instant.now();
        if (!healthy
            && !closed
            && snapshot.revision() == revision
            && snapshot.state() == CONNECTED) {
          disconnect(
              "CONNECTION_LOST",
              "Готовность VPN утрачена; соединение остановлено. Повторите подключение вручную.");
        }
      }
    }
  }

  public List<NetworkRuntime.Check> diagnose() throws IOException, InterruptedException {
    var future = worker.submit(runtime::diagnose);
    try {
      return future.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException exception) {
      throw new IOException("Диагностика не завершена", exception.getCause());
    } catch (TimeoutException exception) {
      future.cancel(false);
      throw new IOException("Диагностика не завершена вовремя", exception);
    }
  }

  private void connectionFailed(
      NetworkRuntime.Prepared prepared, UUID operation, Exception failure) {
    try {
      runtime.stop();
      runtime.discard(prepared);
      synchronized (this) {
        if (!operation.equals(snapshot.operationId())) {
          return;
        }
        if (cancelled.get()) {
          disconnected(operation, "", "");
          return;
        }
        String code =
            failure instanceof NetworkFailure specific ? specific.code() : "CONNECTION_FAILED";
        String message =
            failure instanceof NetworkFailure
                ? failure.getMessage()
                : "Подключение не подтверждено. Проверьте доступ, сервер и ресурс проверки.";
        snapshot =
            new ConnectionSnapshot(
                ERROR,
                snapshot.revision(),
                null,
                null,
                prepared.profile(),
                "",
                code,
                message,
                null,
                null);
        event("error", prepared.profile(), code, message);
      }
    } catch (IOException exception) {
      cleanupFailed(operation);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      cleanupFailed(operation);
    }
  }

  private synchronized void disconnected(UUID operation, String code, String message) {
    if (!operation.equals(snapshot.operationId())) {
      return;
    }
    if (!runtime.cleanupConfirmed()) {
      cleanupFailed(operation);
      return;
    }
    snapshot =
        new ConnectionSnapshot(
            code.isEmpty() ? DISCONNECTED : ERROR,
            snapshot.revision(),
            null,
            null,
            null,
            "",
            code,
            message,
            null,
            null);
    event(
        code.isEmpty() ? "info" : "error",
        null,
        code.isEmpty() ? "DISCONNECTED" : code,
        code.isEmpty() ? "VPN остановлен; очистка собственных настроек подтверждена" : message);
  }

  private synchronized void cleanupFailed(UUID operation) {
    if (!operation.equals(snapshot.operationId())) {
      return;
    }
    snapshot =
        new ConnectionSnapshot(
            ERROR,
            snapshot.revision(),
            null,
            snapshot.appliedProfile(),
            null,
            "",
            "CLEANUP_FAILED",
            "Не удалось подтвердить очистку. Новый запуск заблокирован.",
            null,
            null);
    event("error", null, "CLEANUP_FAILED", snapshot.errorMessage());
  }

  private synchronized void requireIdle() {
    if (closed || snapshot.busy() || candidate != null) {
      throw new IllegalStateException("Дождитесь завершения текущей операции");
    }
  }

  private static ConnectionSnapshot restored(ConnectionSnapshot previous) {
    return new ConnectionSnapshot(
        previous.state(),
        previous.revision(),
        null,
        previous.appliedProfile(),
        null,
        "",
        "",
        "",
        null,
        previous.connectedAt());
  }

  private void event(String level, VpnProfile profile, String code, String message) {
    journal.add(level, profile == null ? null : profile.id(), code, message);
  }

  @Override
  public void close() throws IOException {
    Future<?> cleanup;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      cancelled.set(true);
      disconnect();
      // All platform access, including final cleanup, stays on the connection owner thread.
      cleanup =
          worker.submit(
              () -> {
                runtime.close();
                return null;
              });
      worker.shutdown();
    }
    try {
      cleanup.get(60, TimeUnit.SECONDS);
    } catch (ExecutionException exception) {
      throw new IOException("Окончательная очистка службы не подтверждена", exception.getCause());
    } catch (TimeoutException exception) {
      worker.shutdownNow();
      throw new IOException("Остановка службы не завершилась вовремя", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Остановка службы прервана", exception);
    }
  }
}
