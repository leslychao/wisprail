package app.wisprail.agent;

import app.wisprail.connection.AddressAnalysis;
import app.wisprail.connection.ConnectionEvent;
import app.wisprail.connection.ConnectionGateway;
import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.ConnectionState;
import app.wisprail.connection.DiagnosticReport;
import app.wisprail.connection.PreparedConnection;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.storage.JsonCodec;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Asynchronous UI proxy. An unavailable service does not prevent local profile editing. */
public final class AgentClient implements ConnectionGateway {
  @FunctionalInterface
  public interface Connector {
    IpcConnection connect() throws IOException;
  }

  private final Gson gson = JsonCodec.gson();
  private final Connector connector;
  private final ScheduledExecutorService sender =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("wisprail-ipc-sender").factory());

  private record Pending(String method, CompletableFuture<JsonElement> response) {}

  private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
  private final Semaphore requestSlots = new Semaphore(32);
  private final CopyOnWriteArrayList<Consumer<ConnectionSnapshot>> listeners =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<ConnectionEvent>> eventListeners =
      new CopyOnWriteArrayList<>();
  private final ArrayDeque<ConnectionEvent> events = new ArrayDeque<>();
  private volatile ConnectionSnapshot snapshot = ConnectionSnapshot.initial();
  private volatile IpcConnection channel;
  private volatile boolean closed;
  private long serviceRevision = Long.MIN_VALUE;

  public AgentClient(Connector connector) {
    this.connector = connector;
    request("snapshot", JsonNull.INSTANCE, ConnectionSnapshot.class);
    sender.scheduleWithFixedDelay(
        () -> {
          if (channel != null && !closed) {
            request("ping", JsonNull.INSTANCE, Void.class);
          }
        },
        10,
        10,
        TimeUnit.SECONDS);
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
  public List<ConnectionEvent> recentEvents() {
    synchronized (events) {
      return List.copyOf(events);
    }
  }

  @Override
  public CompletionStage<PreparedConnection> prepare(Profile profile, ProfileSecrets secrets) {
    return request("prepare", profilePayload(profile, secrets), PreparedConnection.class);
  }

  @Override
  public CompletionStage<Void> connect(PreparedConnection prepared) {
    return request("connect", gson.toJsonTree(prepared), Void.class);
  }

  @Override
  public CompletionStage<Void> cancel(UUID operationId) {
    return request("cancel", gson.toJsonTree(operationId), Void.class);
  }

  @Override
  public CompletionStage<Void> disconnect() {
    return request("disconnect", JsonNull.INSTANCE, Void.class);
  }

  @Override
  public CompletionStage<Void> retryCleanup() {
    return request("cleanup", JsonNull.INSTANCE, Void.class);
  }

  @Override
  public CompletionStage<Void> renameActive(UUID profileId, String name) {
    JsonObject payload = new JsonObject();
    payload.addProperty("profileId", profileId.toString());
    payload.addProperty("name", name);
    return request("rename", payload, Void.class);
  }

  @Override
  public CompletionStage<DiagnosticReport> diagnose(Profile profile, ProfileSecrets secrets) {
    return request("diagnose", profilePayload(profile, secrets), DiagnosticReport.class);
  }

  @Override
  public CompletionStage<AddressAnalysis> analyze(Profile profile, String address) {
    JsonObject payload = new JsonObject();
    payload.add("profile", gson.toJsonTree(profile));
    payload.addProperty("address", address);
    return request("analyze", payload, AddressAnalysis.class);
  }

  private JsonObject profilePayload(Profile profile, ProfileSecrets secrets) {
    JsonObject payload = new JsonObject();
    payload.add("profile", gson.toJsonTree(profile));
    payload.add("secrets", gson.toJsonTree(secrets));
    return payload;
  }

  private synchronized <T> CompletableFuture<T> request(
      String method, JsonElement payload, Class<T> type) {
    if (closed) {
      return CompletableFuture.failedFuture(new IOException("Клиент службы закрыт"));
    }
    if (!requestSlots.tryAcquire()) {
      return CompletableFuture.failedFuture(new IOException("Слишком много операций службы"));
    }
    UUID id = UUID.randomUUID();
    CompletableFuture<JsonElement> response = new CompletableFuture<>();
    pending.put(id, new Pending(method, response));
    response
        .orTimeout(90, TimeUnit.SECONDS)
        .whenComplete(
            (value, error) -> {
              pending.remove(id);
              requestSlots.release();
            });
    try {
      sender.execute(
          () -> {
            if (response.isDone()) {
              return;
            }
            IpcConnection connection = null;
            try {
              connection = ensureConnected();
              byte[] data =
                  AgentProtocol.encode(new AgentMessage("request", id, method, payload, true, ""));
              try {
                connection.send(data);
              } finally {
                Arrays.fill(data, (byte) 0);
              }
            } catch (IOException | RuntimeException exception) {
              response.completeExceptionally(
                  new IOException("Служба управления недоступна", exception));
              failConnection(connection);
            }
          });
    } catch (RejectedExecutionException exception) {
      response.completeExceptionally(new IOException("Клиент службы закрыт", exception));
    }
    return response.thenApply(value -> gson.fromJson(value, type));
  }

  private synchronized IpcConnection ensureConnected() throws IOException {
    if (closed) {
      throw new IOException("Клиент службы закрыт");
    }
    if (channel == null) {
      channel = connector.connect();
      serviceRevision = Long.MIN_VALUE;
      IpcConnection connected = channel;
      Thread.ofVirtual().name("wisprail-ipc-reader").start(() -> readResponses(connected));
    }
    return channel;
  }

  private void readResponses(IpcConnection connection) {
    try {
      while (!closed) {
        byte[] bytes = connection.receive();
        AgentMessage message;
        try {
          message = AgentProtocol.decode(bytes);
        } finally {
          Arrays.fill(bytes, (byte) 0);
        }
        acceptMessage(connection, message);
      }
    } catch (IOException | RuntimeException exception) {
      failConnection(connection);
    }
  }

  private synchronized void acceptMessage(IpcConnection connection, AgentMessage message)
      throws IOException {
    if (closed || connection != channel) {
      return;
    }
    switch (message.kind()) {
      case "response" -> {
        Pending request = message.id() == null ? null : pending.get(message.id());
        if (request != null && request.method().equals(message.method())) {
          if (message.success()) {
            if (request.method().equals("snapshot")) {
              acceptSnapshot(gson.fromJson(message.payload(), ConnectionSnapshot.class));
            }
            request.response().complete(message.payload());
          } else {
            request.response().completeExceptionally(new IOException(message.error()));
          }
        }
      }
      case "snapshot" -> acceptSnapshot(gson.fromJson(message.payload(), ConnectionSnapshot.class));
      case "event" -> {
        ConnectionEvent event = gson.fromJson(message.payload(), ConnectionEvent.class);
        synchronized (events) {
          if (events.size() == 1000) {
            events.removeFirst();
          }
          events.addLast(event);
        }
        eventListeners.forEach(listener -> listener.accept(event));
      }
      default -> throw new IOException("Неподдерживаемый ответ службы");
    }
  }

  private synchronized void failConnection(IpcConnection connection) {
    if (connection != null && connection != channel) {
      return;
    }
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException ignored) {
        // The channel is already failed; no successful cleanup is inferred from this close.
      }
      channel = null;
    }
    pending
        .values()
        .forEach(
            request ->
                request
                    .response()
                    .completeExceptionally(
                        new IOException(
                            "Связь со службой потеряна; состояние VPN требует проверки")));
    pending.clear();
    if (!closed) {
      updateSnapshot(
          new ConnectionSnapshot(
              snapshot.revision() + 1,
              ConnectionState.ERROR,
              snapshot.operationId(),
              snapshot.activeProfile(),
              snapshot.targetProfileId(),
              false,
              snapshot.activeProfile() != null
                  || snapshot.cleanupRequired()
                  || snapshot.state() == ConnectionState.CONNECTING
                  || snapshot.state() == ConnectionState.DISCONNECTING,
              "Служба управления недоступна. Установите или проверьте компонент."));
    }
  }

  private void acceptSnapshot(ConnectionSnapshot value) throws IOException {
    if (value == null || value.state() == null || value.revision() < 0) {
      throw new IOException("Недопустимое состояние службы");
    }
    if (value.revision() <= serviceRevision) {
      return;
    }
    serviceRevision = value.revision();
    updateSnapshot(
        new ConnectionSnapshot(
            snapshot.revision() + 1,
            value.state(),
            value.operationId(),
            value.activeProfile(),
            value.targetProfileId(),
            value.preparing(),
            value.cleanupRequired(),
            value.message()));
  }

  private void updateSnapshot(ConnectionSnapshot value) {
    snapshot = value;
    listeners.forEach(listener -> listener.accept(value));
  }

  @Override
  public synchronized void close() {
    closed = true;
    sender.shutdownNow();
    failConnection(channel);
  }
}
