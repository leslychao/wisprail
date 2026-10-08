package app.wisprail.connection;

import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.ProfileValidator;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns all network transitions. Blocking engine work is serialized on a bounded worker. */
public final class ConnectionService implements ConnectionGateway {
  private static final int EVENT_LIMIT = 2000;
  private static final Duration CANDIDATE_LIFETIME = Duration.ofMinutes(2);
  private final EngineControl engine;
  private final ExecutorService worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(16),
          Thread.ofPlatform().daemon().name("wisprail-connection").factory(),
          new ThreadPoolExecutor.AbortPolicy());
  private final CopyOnWriteArrayList<Consumer<ConnectionSnapshot>> listeners =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<ConnectionEvent>> eventListeners =
      new CopyOnWriteArrayList<>();
  private final ArrayDeque<ConnectionEvent> events = new ArrayDeque<>();
  private final AtomicReference<UUID> cancelledOperation = new AtomicReference<>();
  private final AtomicBoolean diagnosticRunning = new AtomicBoolean();
  private volatile ConnectionSnapshot snapshot = ConnectionSnapshot.initial();
  private boolean initialized;
  private volatile boolean closing;
  private boolean connectionRequested;
  private CompletableFuture<Void> disconnectingFuture;
  private CompletableFuture<Void> cancellingFuture;
  private UUID cancellingOperation;
  private UUID failingOperation;
  private EngineControl.Candidate candidate;
  private PreparedConnection prepared;
  private UUID activeOperation;
  private long eventSequence;

  public ConnectionService(EngineControl engine) {
    this.engine = Objects.requireNonNull(engine);
    engine.onFailure(this::engineFailed);
  }

  public synchronized CompletableFuture<Void> initialize() {
    if (initialized) {
      return CompletableFuture.completedFuture(null);
    }
    if (snapshot.busy()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Восстановление уже выполняется"));
    }
    publish(
        ConnectionState.DISCONNECTING,
        null,
        null,
        null,
        false,
        false,
        "Проверяем состояние системного компонента…");
    return submit(
        () -> {
          try {
            engine.recover();
            synchronized (this) {
              initialized = true;
              publish(ConnectionState.DISCONNECTED, null, null, null, false, false, "Не подключён");
            }
          } catch (Exception failure) {
            preserveInterrupt(failure);
            synchronized (this) {
              initialized = true;
              publish(
                  ConnectionState.ERROR,
                  null,
                  null,
                  null,
                  false,
                  true,
                  "Не удалось подтвердить очистку предыдущего подключения");
            }
            throw new IllegalStateException("Очистка сети требует повторной проверки");
          }
          return null;
        });
  }

  @Override
  public ConnectionSnapshot snapshot() {
    return snapshot;
  }

  @Override
  public synchronized AutoCloseable subscribe(Consumer<ConnectionSnapshot> listener) {
    listeners.add(listener);
    listener.accept(snapshot);
    return () -> listeners.remove(listener);
  }

  @Override
  public AutoCloseable subscribeEvents(Consumer<ConnectionEvent> listener) {
    eventListeners.add(listener);
    return () -> eventListeners.remove(listener);
  }

  @Override
  public synchronized List<ConnectionEvent> recentEvents() {
    return List.copyOf(events);
  }

  @Override
  public synchronized CompletionStage<PreparedConnection> prepare(
      Profile profile, ProfileSecrets secrets) {
    try {
      requireAvailable();
      requireIdle();
      ProfileValidator.requireValid(profile, secrets);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    UUID operation = UUID.randomUUID();
    cancelledOperation.set(null);
    publish(
        snapshot.state(),
        operation,
        snapshot.activeProfile(),
        profile.id(),
        true,
        false,
        "Проверяем профиль и конфигурацию…");
    return submit(
        () -> {
          try {
            discardCandidate();
            EngineControl.Candidate result = engine.prepare(profile, secrets, operation);
            synchronized (this) {
              candidate = result;
              if (isCancelled(operation) || closing) {
                discardCandidate();
                restoreAfterPreparation("Проверка отменена");
                throw new CancellationException("Проверка отменена");
              }
              publish(
                  snapshot.activeProfile() == null
                      ? ConnectionState.DISCONNECTED
                      : ConnectionState.CONNECTED,
                  operation,
                  snapshot.activeProfile(),
                  profile.id(),
                  false,
                  false,
                  "Конфигурация проверена; подключение ещё не изменено");
              prepared =
                  new PreparedConnection(
                      UUID.randomUUID(),
                      operation,
                      profile.id(),
                      profile.revision(),
                      snapshot.revision(),
                      snapshot.activeProfile() != null,
                      snapshot.activeProfile() == null
                          ? List.of()
                          : List.of("Текущее подключение будет остановлено перед запуском нового."),
                      Instant.now().plus(CANDIDATE_LIFETIME));
              event("INFO", profile.id(), "Конфигурация проверена", "Сеть ещё не изменялась");
              return prepared;
            }
          } catch (CancellationException cancelled) {
            throw cancelled;
          } catch (Exception failure) {
            preserveInterrupt(failure);
            synchronized (this) {
              if (candidate != null) {
                publish(
                    ConnectionState.ERROR,
                    operation,
                    snapshot.activeProfile(),
                    null,
                    false,
                    true,
                    "Не удалось удалить подготовленную конфигурацию");
              } else if (!snapshot.cleanupRequired()) {
                restoreAfterPreparation("Проверка не пройдена; действующее подключение сохранено");
              }
              event(
                  "ERROR",
                  profile.id(),
                  "Не удалось подготовить подключение",
                  "Проверьте профиль, данные доступа, движок и системные конфликты");
            }
            throw new IllegalStateException("Проверка профиля или компонента не пройдена");
          }
        });
  }

  @Override
  public synchronized CompletionStage<Void> connect(PreparedConnection confirmation) {
    try {
      requireAvailable();
      requireIdle();
      if (prepared == null
          || candidate == null
          || !prepared.equals(confirmation)
          || prepared.connectionRevision() != snapshot.revision()
          || !prepared.expiresAt().isAfter(Instant.now())) {
        throw new IllegalStateException("Подтверждение устарело. Повторите проверку подключения");
      }
    } catch (IllegalStateException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    EngineControl.Candidate connecting = candidate;
    candidate = null;
    prepared = null;
    connectionRequested = true;
    UUID operation = connecting.operationId();
    cancelledOperation.set(null);
    boolean switching = snapshot.activeProfile() != null;
    publish(
        switching ? ConnectionState.DISCONNECTING : ConnectionState.CONNECTING,
        operation,
        snapshot.activeProfile(),
        connecting.profile().id(),
        false,
        false,
        switching ? "Останавливаем текущее подключение…" : "Подключение…");
    return submit(
        () -> {
          try (connecting) {
            if (switching) {
              engine.stop();
              synchronized (this) {
                activeOperation = null;
                publish(
                    ConnectionState.CONNECTING,
                    operation,
                    null,
                    connecting.profile().id(),
                    false,
                    false,
                    "Подключение…");
              }
            }
            if (isCancelled(operation) || closing) {
              throw new CancellationException("Подключение отменено");
            }
            engine.start(
                connecting,
                () -> isCancelled(operation) || closing,
                message -> progress(operation, message));
            synchronized (this) {
              if (isCancelled(operation) || closing) {
                throw new CancellationException("Подключение отменено");
              }
              activeOperation = operation;
              publish(
                  ConnectionState.CONNECTED,
                  operation,
                  connecting.profile(),
                  null,
                  false,
                  false,
                  "Подключён");
              event(
                  "INFO",
                  connecting.profile().id(),
                  "Подключение установлено",
                  "Готовность подтверждена протоколом");
            }
          } catch (Exception failure) {
            preserveInterrupt(failure);
            boolean cancelled = failure instanceof CancellationException || isCancelled(operation);
            synchronized (this) {
              candidate = connecting;
            }
            cleanupAfterFailure(operation, connecting.profile().id(), cancelled);
            if (cancelled) {
              throw new CancellationException("Подключение отменено");
            }
            throw new IllegalStateException(
                snapshot.cleanupRequired()
                    ? "Не удалось подтвердить очистку сети"
                    : "Не удалось подключиться к VPN");
          } finally {
            synchronized (this) {
              connectionRequested = false;
            }
          }
          return null;
        });
  }

  @Override
  public synchronized CompletionStage<Void> cancel(UUID operationId) {
    if (operationId != null
        && operationId.equals(cancellingOperation)
        && cancellingFuture != null) {
      return cancellingFuture;
    }
    if (operationId == null
        || !operationId.equals(snapshot.operationId())
        || (!snapshot.preparing() && !connectionRequested && prepared == null)) {
      return CompletableFuture.completedFuture(null);
    }
    cancelledOperation.set(operationId);
    cancellingOperation = operationId;
    cancellingFuture =
        submit(
            () -> {
              synchronized (this) {
                if (prepared != null && operationId.equals(prepared.operationId())) {
                  try {
                    discardCandidate();
                  } catch (IOException failure) {
                    publish(
                        ConnectionState.ERROR,
                        operationId,
                        snapshot.activeProfile(),
                        null,
                        false,
                        true,
                        "Не удалось удалить подготовленную конфигурацию");
                    throw new IllegalStateException(
                        "Не удалось удалить подготовленную конфигурацию");
                  }
                  restoreAfterPreparation("Проверка отменена; подключение не изменено");
                }
              }
              return null;
            });
    return cancellingFuture;
  }

  @Override
  public synchronized CompletionStage<Void> disconnect() {
    if (disconnectingFuture != null && !disconnectingFuture.isDone()) {
      return disconnectingFuture;
    }
    if (closing) {
      return CompletableFuture.failedFuture(new IllegalStateException("Служба завершает работу"));
    }
    if (snapshot.preparing() || connectionRequested) {
      cancelledOperation.set(snapshot.operationId());
    }
    if (snapshot.state() == ConnectionState.DISCONNECTING && !connectionRequested) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Отключение уже выполняется"));
    }
    UUID operation = snapshot.operationId();
    UUID profileId =
        snapshot.activeProfile() == null
            ? snapshot.targetProfileId()
            : snapshot.activeProfile().id();
    publish(
        ConnectionState.DISCONNECTING,
        operation,
        snapshot.activeProfile(),
        null,
        false,
        snapshot.cleanupRequired(),
        "Отключение…");
    disconnectingFuture =
        submit(
            () -> {
              try {
                engine.stop();
                discardCandidate();
                synchronized (this) {
                  activeOperation = null;
                  publish(
                      ConnectionState.DISCONNECTED, null, null, null, false, false, "Не подключён");
                  event("INFO", profileId, "Подключение остановлено", "Очистка подтверждена");
                }
              } catch (Exception failure) {
                preserveInterrupt(failure);
                synchronized (this) {
                  publish(
                      ConnectionState.ERROR,
                      operation,
                      snapshot.activeProfile(),
                      null,
                      false,
                      true,
                      "Не удалось подтвердить очистку сети");
                  event("ERROR", profileId, "Ошибка очистки", "Новый запуск заблокирован");
                }
                throw new IllegalStateException("Не удалось подтвердить очистку сети");
              }
              return null;
            });
    return disconnectingFuture;
  }

  @Override
  public synchronized CompletionStage<Void> retryCleanup() {
    if (snapshot.busy()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Дождитесь текущей операции"));
    }
    if (!snapshot.cleanupRequired()) {
      return CompletableFuture.completedFuture(null);
    }
    publish(
        ConnectionState.DISCONNECTING,
        snapshot.operationId(),
        snapshot.activeProfile(),
        null,
        false,
        true,
        "Повторная проверка очистки…");
    return submit(
        () -> {
          try {
            engine.stop();
            discardCandidate();
            engine.recover();
            synchronized (this) {
              activeOperation = null;
              publish(
                  ConnectionState.DISCONNECTED,
                  null,
                  null,
                  null,
                  false,
                  false,
                  "Очистка подтверждена");
            }
          } catch (Exception failure) {
            preserveInterrupt(failure);
            synchronized (this) {
              publish(
                  ConnectionState.ERROR,
                  snapshot.operationId(),
                  snapshot.activeProfile(),
                  null,
                  false,
                  true,
                  "Очистка по-прежнему не подтверждена");
            }
            throw new IllegalStateException("Очистка по-прежнему не подтверждена");
          }
          return null;
        });
  }

  @Override
  public synchronized CompletionStage<Void> renameActive(UUID profileId, String name) {
    if (name == null || name.isBlank() || name.length() > 80) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("Некорректное название"));
    }
    Profile active = snapshot.activeProfile();
    if (active != null && active.id().equals(profileId)) {
      Profile renamed =
          active.withIdentity(active.id(), active.revision(), name.strip(), active.secretRef());
      publish(
          snapshot.state(),
          snapshot.operationId(),
          renamed,
          snapshot.targetProfileId(),
          snapshot.preparing(),
          snapshot.cleanupRequired(),
          snapshot.message());
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<DiagnosticReport> diagnose(Profile profile, ProfileSecrets secrets) {
    return diagnostic(() -> engine.diagnose(profile, secrets));
  }

  @Override
  public CompletionStage<AddressAnalysis> analyze(Profile profile, String address) {
    return diagnostic(() -> engine.analyze(profile, address));
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closing) {
        return;
      }
      closing = true;
      cancelledOperation.set(snapshot.operationId());
    }
    CompletableFuture<Void> shutdown =
        submit(
            () -> {
              try {
                discardCandidate();
              } finally {
                engine.close();
              }
              synchronized (this) {
                activeOperation = null;
                publish(
                    ConnectionState.DISCONNECTED,
                    null,
                    null,
                    null,
                    false,
                    false,
                    "Служба завершена; очистка подтверждена");
              }
              return null;
            });
    try {
      shutdown.get(45, TimeUnit.SECONDS);
    } catch (InterruptedException
        | java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failure) {
      preserveInterrupt(failure);
      synchronized (this) {
        publish(
            ConnectionState.ERROR,
            snapshot.operationId(),
            snapshot.activeProfile(),
            null,
            false,
            true,
            "Завершение службы требует проверки очистки");
      }
    } finally {
      worker.shutdownNow();
      listeners.clear();
      eventListeners.clear();
    }
  }

  private synchronized void progress(UUID operation, String message) {
    if (operation.equals(snapshot.operationId())
        && !isCancelled(operation)
        && snapshot.state() == ConnectionState.CONNECTING) {
      publish(
          snapshot.state(),
          operation,
          snapshot.activeProfile(),
          snapshot.targetProfileId(),
          false,
          false,
          message);
    }
  }

  private void engineFailed(UUID operation, String ignoredUnsafeDetails) {
    synchronized (this) {
      if (closing
          || operation == null
          || operation.equals(failingOperation)
          || (!operation.equals(activeOperation) && !operation.equals(snapshot.operationId()))) {
        return;
      }
      failingOperation = operation;
      cancelledOperation.set(snapshot.operationId());
    }
    submit(
            () -> {
              UUID profileId;
              synchronized (this) {
                if (!operation.equals(activeOperation)
                    && !operation.equals(snapshot.operationId())) {
                  return null;
                }
                profileId =
                    snapshot.activeProfile() == null
                        ? snapshot.targetProfileId()
                        : snapshot.activeProfile().id();
              }
              cleanupAfterFailure(operation, profileId, false);
              return null;
            })
        .whenComplete(
            (result, failure) -> {
              synchronized (this) {
                failingOperation = null;
              }
            });
  }

  private void cleanupAfterFailure(UUID operation, UUID profileId, boolean cancelled) {
    boolean clean = false;
    try {
      engine.stop();
      discardCandidate();
      clean = true;
    } catch (Exception failure) {
      preserveInterrupt(failure);
    }
    synchronized (this) {
      activeOperation = null;
      publish(
          cancelled && clean ? ConnectionState.DISCONNECTED : ConnectionState.ERROR,
          operation,
          clean ? null : snapshot.activeProfile(),
          null,
          false,
          !clean,
          !clean
              ? "Не удалось подтвердить очистку сети"
              : cancelled ? "Подключение отменено" : "Соединение не установлено или прервано");
      event(
          cancelled && clean ? "INFO" : "ERROR",
          profileId,
          cancelled ? "Подключение отменено" : "Ошибка подключения",
          clean ? "Собственные сетевые объекты очищены" : "Новый запуск заблокирован");
    }
  }

  private <T> CompletionStage<T> diagnostic(CheckedSupplier<T> action) {
    synchronized (this) {
      if (!initialized
          || closing
          || snapshot.busy()
          || !diagnosticRunning.compareAndSet(false, true)) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("Компонент занят или недоступен"));
      }
    }
    CompletableFuture<T> future =
        submit(
            () -> {
              try {
                return action.get();
              } catch (Exception failure) {
                preserveInterrupt(failure);
                throw new IllegalStateException("Не удалось завершить проверку");
              } finally {
                diagnosticRunning.set(false);
              }
            });
    future.whenComplete((result, failure) -> diagnosticRunning.set(false));
    return future;
  }

  private synchronized void requireAvailable() {
    if (!initialized || closing) {
      throw new IllegalStateException("Системный компонент ещё не готов");
    }
    if (snapshot.cleanupRequired()) {
      throw new IllegalStateException("Сначала подтвердите очистку предыдущего подключения");
    }
  }

  private synchronized void requireIdle() {
    if (snapshot.busy()
        || connectionRequested
        || (cancellingFuture != null && !cancellingFuture.isDone())
        || (disconnectingFuture != null && !disconnectingFuture.isDone())
        || diagnosticRunning.get()) {
      throw new IllegalStateException("Дождитесь текущей операции");
    }
  }

  private synchronized void discardCandidate() throws IOException {
    EngineControl.Candidate previous = candidate;
    if (previous != null) {
      previous.close();
    }
    candidate = null;
    prepared = null;
  }

  private synchronized void restoreAfterPreparation(String message) {
    if (disconnectingFuture != null && !disconnectingFuture.isDone()) {
      return;
    }
    publish(
        snapshot.activeProfile() == null ? ConnectionState.DISCONNECTED : ConnectionState.CONNECTED,
        activeOperation,
        snapshot.activeProfile(),
        null,
        false,
        false,
        message);
  }

  private boolean isCancelled(UUID operation) {
    return operation.equals(cancelledOperation.get()) || Thread.currentThread().isInterrupted();
  }

  private synchronized void publish(
      ConnectionState state,
      UUID operationId,
      Profile active,
      UUID target,
      boolean preparing,
      boolean cleanupRequired,
      String message) {
    snapshot =
        new ConnectionSnapshot(
            snapshot.revision() + 1,
            state,
            operationId,
            active,
            target,
            preparing,
            cleanupRequired,
            message);
    for (Consumer<ConnectionSnapshot> listener : listeners) {
      try {
        listener.accept(snapshot);
      } catch (RuntimeException failure) {
        // A failed UI delivery must not abort mandatory network cleanup.
        listeners.remove(listener);
      }
    }
  }

  private synchronized void event(String level, UUID profileId, String message, String detail) {
    ConnectionEvent event =
        new ConnectionEvent(++eventSequence, Instant.now(), level, profileId, message, detail);
    if (events.size() == EVENT_LIMIT) {
      events.removeFirst();
    }
    events.addLast(event);
    for (Consumer<ConnectionEvent> listener : eventListeners) {
      try {
        listener.accept(event);
      } catch (RuntimeException failure) {
        eventListeners.remove(listener);
      }
    }
  }

  private <T> CompletableFuture<T> submit(CheckedSupplier<T> action) {
    CompletableFuture<T> future = new CompletableFuture<>();
    try {
      worker.execute(
          () -> {
            try {
              future.complete(action.get());
            } catch (Exception failure) {
              preserveInterrupt(failure);
              future.completeExceptionally(failure);
            }
          });
    } catch (RejectedExecutionException failure) {
      future.completeExceptionally(new IllegalStateException("Очередь операций недоступна"));
    }
    return future;
  }

  private static void preserveInterrupt(Exception failure) {
    if (failure instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
  }

  @FunctionalInterface
  private interface CheckedSupplier<T> {
    T get() throws Exception;
  }
}
