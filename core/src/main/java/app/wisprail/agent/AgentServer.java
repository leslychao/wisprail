package app.wisprail.agent;

import app.wisprail.connection.ConnectionGateway;
import app.wisprail.connection.PreparedConnection;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.storage.JsonCodec;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One authenticated UI session. Loss of that session always asks the lifecycle owner to stop. */
public final class AgentServer implements AutoCloseable {
  private final IpcListener listener;
  private final ConnectionGateway gateway;
  private final Closeable ownership;
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile Session active;

  public AgentServer(IpcListener listener, ConnectionGateway gateway, Closeable ownership) {
    this.listener = listener;
    this.gateway = gateway;
    this.ownership = ownership;
  }

  public void run() throws IOException {
    while (!closed.get()) {
      try {
        IpcConnection connection = listener.accept();
        Session session = new Session(connection);
        active = session;
        session.run();
      } catch (IOException exception) {
        if (closed.get()) {
          return;
        }
        // Failed authentication or peer loss never grants a session or changes its owner.
        if (active == null) {
          try {
            Thread.sleep(250);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Приём сессий прерван", interrupted);
          }
        }
      } finally {
        Session session = active;
        if (session != null) {
          session.close();
          active = null;
          try {
            gateway.disconnect().toCompletableFuture().get(45, TimeUnit.SECONDS);
          } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
              Thread.currentThread().interrupt();
              return;
            }
            // ConnectionService retains ERROR/cleanupRequired; a later client can retry cleanup.
          }
        }
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (closed.compareAndSet(false, true)) {
      Session session = active;
      if (session != null) {
        session.close();
      }
      try {
        try {
          listener.close();
        } finally {
          gateway.close();
        }
        if (gateway.snapshot().cleanupRequired()) {
          throw new IOException("Системная очистка не завершена; компонент необходимо сохранить");
        }
      } finally {
        ownership.close();
      }
    }
  }

  private final class Session implements AutoCloseable {
    private final IpcConnection connection;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> output = new ArrayBlockingQueue<>(128);
    private final AtomicLong queuedBytes = new AtomicLong();
    private final Semaphore requests = new Semaphore(16);
    private final LinkedHashMap<UUID, CompletableFuture<JsonElement>> completed =
        new LinkedHashMap<>();
    private volatile long lastInput = System.nanoTime();

    private Session(IpcConnection connection) {
      this.connection = connection;
    }

    private void run() throws IOException {
      Thread writer = Thread.ofVirtual().name("wisprail-ipc-writer").start(this::writeMessages);
      AutoCloseable snapshots =
          gateway.subscribe(
              value ->
                  enqueue(
                      new AgentMessage(
                          "snapshot", null, "", JsonCodec.gson().toJsonTree(value), true, "")));
      AutoCloseable events =
          gateway.subscribeEvents(
              value ->
                  enqueue(
                      new AgentMessage(
                          "event", null, "", JsonCodec.gson().toJsonTree(value), true, "")));
      try (var watchdog =
          Executors.newSingleThreadScheduledExecutor(
              Thread.ofPlatform().daemon().name("wisprail-ipc-watchdog").factory())) {
        watchdog.scheduleAtFixedRate(
            () -> {
              if (System.nanoTime() - lastInput > TimeUnit.SECONDS.toNanos(45)) {
                close();
              }
            },
            15,
            15,
            TimeUnit.SECONDS);
        while (!ended.get() && !closed.get()) {
          byte[] bytes = connection.receive();
          AgentMessage message;
          try {
            message = AgentProtocol.decode(bytes);
          } finally {
            Arrays.fill(bytes, (byte) 0);
          }
          lastInput = System.nanoTime();
          if (!message.kind().equals("request")
              || message.id() == null
              || message.method() == null) {
            throw new IOException("Недопустимая команда IPC");
          }
          CompletableFuture<JsonElement> result = completed.get(message.id());
          if (result == null) {
            if (!requests.tryAcquire()) {
              throw new IOException("Превышено число команд IPC");
            }
            try {
              result =
                  dispatch(message).thenApply(JsonCodec.gson()::toJsonTree).toCompletableFuture();
            } catch (RuntimeException exception) {
              result = CompletableFuture.failedFuture(exception);
            }
            completed.put(message.id(), result);
            result.whenComplete((value, error) -> requests.release());
            if (completed.size() > 32) {
              var entries = completed.entrySet().iterator();
              while (entries.hasNext() && completed.size() > 32) {
                if (entries.next().getValue().isDone()) {
                  entries.remove();
                }
              }
            }
          }
          result.whenComplete(
              (value, error) ->
                  enqueue(
                      new AgentMessage(
                          "response",
                          message.id(),
                          message.method(),
                          error == null ? value : JsonNull.INSTANCE,
                          error == null,
                          error == null
                              ? ""
                              : "Операция не выполнена. Проверьте состояние подключения и"
                                  + " журнал.")));
        }
      } catch (IOException exception) {
        throw exception;
      } catch (Exception exception) {
        throw new IOException("Сессия службы закрыта", exception);
      } finally {
        close();
        writer.interrupt();
        try {
          try {
            snapshots.close();
          } finally {
            events.close();
          }
        } catch (Exception exception) {
          throw new IOException("Не удалось завершить подписку службы", exception);
        }
      }
    }

    private CompletionStage<?> dispatch(AgentMessage message) throws IOException {
      JsonElement payload = message.payload();
      validatePayload(message.method(), payload);
      return switch (message.method()) {
        case "ping" -> CompletableFuture.completedFuture(null);
        case "snapshot" -> CompletableFuture.completedFuture(gateway.snapshot());
        case "prepare" -> gateway.prepare(profile(payload), secrets(payload));
        case "connect" ->
            gateway.connect(JsonCodec.gson().fromJson(payload, PreparedConnection.class));
        case "cancel" -> gateway.cancel(JsonCodec.gson().fromJson(payload, UUID.class));
        case "disconnect" -> gateway.disconnect();
        case "cleanup" -> gateway.retryCleanup();
        case "rename" -> {
          JsonObject object = payload.getAsJsonObject();
          yield gateway.renameActive(
              UUID.fromString(object.get("profileId").getAsString()),
              object.get("name").getAsString());
        }
        case "diagnose" -> gateway.diagnose(profile(payload), secrets(payload));
        case "analyze" ->
            gateway.analyze(
                profile(payload), payload.getAsJsonObject().get("address").getAsString());
        default -> CompletableFuture.failedFuture(new IOException("Неизвестная команда службы"));
      };
    }

    private Profile profile(JsonElement payload) throws IOException {
      return JsonCodec.readProfile(payload.getAsJsonObject().get("profile"));
    }

    private ProfileSecrets secrets(JsonElement payload) throws IOException {
      return JsonCodec.readSecrets(payload.getAsJsonObject().get("secrets"));
    }

    private void validatePayload(String method, JsonElement payload) throws IOException {
      switch (method) {
        case "ping", "snapshot", "disconnect", "cleanup" -> {
          if (!payload.isJsonNull()) {
            throw new IOException("Команда не принимает параметры");
          }
        }
        case "prepare", "diagnose" -> requireFields(payload, Set.of("profile", "secrets"));
        case "analyze" -> {
          requireFields(payload, Set.of("profile", "address"));
          requireString(payload.getAsJsonObject().get("address"), 4096);
        }
        case "rename" -> {
          requireFields(payload, Set.of("profileId", "name"));
          requireString(payload.getAsJsonObject().get("profileId"), 36);
          requireString(payload.getAsJsonObject().get("name"), 1024);
        }
        case "cancel" -> requireString(payload, 36);
        case "connect" -> {
          requireFields(
              payload,
              Set.of(
                  "token",
                  "operationId",
                  "profileId",
                  "profileRevision",
                  "connectionRevision",
                  "confirmationRequired",
                  "notes",
                  "expiresAt"));
          JsonObject object = payload.getAsJsonObject();
          for (String field : Set.of("token", "operationId", "profileId", "expiresAt")) {
            requireString(object.get(field), 128);
          }
          for (String field : Set.of("profileRevision", "connectionRevision")) {
            JsonElement number = object.get(field);
            if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()) {
              throw new IOException("Недопустимый номер ревизии");
            }
            try {
              number.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException exception) {
              throw new IOException("Недопустимый номер ревизии", exception);
            }
          }
          JsonElement confirmation = object.get("confirmationRequired");
          JsonElement notes = object.get("notes");
          if (!confirmation.isJsonPrimitive()
              || !confirmation.getAsJsonPrimitive().isBoolean()
              || !notes.isJsonArray()
              || notes.getAsJsonArray().size() > 8192) {
            throw new IOException("Недопустимое подтверждение подключения");
          }
          for (JsonElement note : notes.getAsJsonArray()) {
            requireString(note, 4096);
          }
        }
        default -> throw new IOException("Неизвестная команда службы");
      }
    }

    private void requireFields(JsonElement value, Set<String> fields) throws IOException {
      if (!value.isJsonObject() || !value.getAsJsonObject().keySet().equals(fields)) {
        throw new IOException("Недопустимые параметры команды службы");
      }
    }

    private void requireString(JsonElement value, int maximumLength) throws IOException {
      if (!value.isJsonPrimitive()
          || !value.getAsJsonPrimitive().isString()
          || value.getAsString().length() > maximumLength) {
        throw new IOException("Недопустимая строка команды службы");
      }
    }

    private void enqueue(AgentMessage message) {
      if (ended.get()) {
        return;
      }
      byte[] bytes = AgentProtocol.encode(message);
      long size = queuedBytes.addAndGet(bytes.length);
      if (bytes.length > IpcConnection.MAX_MESSAGE_BYTES
          || size > 8 * 1024 * 1024
          || !output.offer(bytes)) {
        queuedBytes.addAndGet(-bytes.length);
        Arrays.fill(bytes, (byte) 0);
        close();
      }
    }

    private void writeMessages() {
      try {
        while (!ended.get()) {
          byte[] bytes = output.take();
          queuedBytes.addAndGet(-bytes.length);
          try {
            connection.send(bytes);
          } finally {
            Arrays.fill(bytes, (byte) 0);
          }
        }
      } catch (IOException exception) {
        close();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void close() {
      if (ended.compareAndSet(false, true)) {
        try {
          connection.close();
        } catch (IOException ignored) {
          // Disconnect cleanup is owned by the enclosing server, irrespective of close outcome.
        }
        byte[] bytes;
        while ((bytes = output.poll()) != null) {
          Arrays.fill(bytes, (byte) 0);
        }
      }
    }
  }
}
