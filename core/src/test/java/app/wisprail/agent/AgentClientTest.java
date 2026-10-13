package app.wisprail.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.connection.ConnectionEvent;
import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.ConnectionState;
import app.wisprail.storage.JsonCodec;
import com.google.gson.JsonNull;
import java.io.EOFException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentClientTest {
  @Test
  void lateInitialResponseCannotOverwriteNewerEventAndReconnectPreservesLocalRevision()
      throws Exception {
    FakeConnection first = new FakeConnection();
    FakeConnection second = new FakeConnection();
    AtomicInteger connections = new AtomicInteger();
    try (AgentClient client =
        new AgentClient(() -> connections.getAndIncrement() == 0 ? first : second)) {
      AgentMessage initialRequest = first.nextRequest();
      first.snapshot(8, ConnectionState.CONNECTED);
      awaitState(client, ConnectionState.CONNECTED);
      long connectedRevision = client.snapshot().revision();
      first.reply(initialRequest, ConnectionSnapshot.initial());
      CompletableFuture<ConnectionSnapshot> refreshed = client.refresh().toCompletableFuture();
      first.reply(first.nextRequest(), ConnectionSnapshot.initial());
      assertEquals(ConnectionState.CONNECTED, refreshed.get(5, TimeUnit.SECONDS).state());
      assertEquals(ConnectionState.CONNECTED, client.snapshot().state());
      assertEquals(connectedRevision, client.snapshot().revision());
      first.close();
      awaitState(client, ConnectionState.ERROR);
      long failedRevision = client.snapshot().revision();
      AgentMessage request = second.nextRequest();
      assertEquals("snapshot", request.method());
      second.reply(request, ConnectionSnapshot.initial());
      awaitState(client, ConnectionState.DISCONNECTED);
      assertTrue(client.snapshot().revision() > failedRevision);

      CountDownLatch eventReceived = new CountDownLatch(1);
      AutoCloseable events = client.subscribeEvents(event -> eventReceived.countDown());
      try {
        second.event(new ConnectionEvent(0, Instant.now(), "INFO", null, "restored", ""));
        assertTrue(eventReceived.await(5, TimeUnit.SECONDS));
      } finally {
        events.close();
      }
      second.snapshot(1, ConnectionState.CONNECTED);
      awaitState(client, ConnectionState.CONNECTED);
    }
  }

  @Test
  void recoversWhenServiceBecomesAvailableWithoutRestartingClient() throws Exception {
    FakeConnection connection = new FakeConnection();
    AtomicBoolean available = new AtomicBoolean();
    try (AgentClient client =
        new AgentClient(
            () -> {
              if (!available.get()) {
                throw new IOException("Service not installed yet");
              }
              return connection;
            })) {
      awaitState(client, ConnectionState.ERROR);
      available.set(true);
      AgentMessage recovery = connection.nextRequest();
      assertEquals("snapshot", recovery.method());
      connection.reply(recovery, ConnectionSnapshot.initial());
      awaitState(client, ConnectionState.DISCONNECTED);
      connection.snapshot(1, ConnectionState.CONNECTED);
      awaitState(client, ConnectionState.CONNECTED);
    }
  }

  @Test
  void failedCommandIsNotResentDuringRecovery() throws Exception {
    FakeConnection first = new FakeConnection();
    FakeConnection second = new FakeConnection();
    AtomicInteger connections = new AtomicInteger();
    try (AgentClient client =
        new AgentClient(() -> connections.getAndIncrement() == 0 ? first : second)) {
      first.reply(first.nextRequest(), ConnectionSnapshot.initial());
      awaitState(client, ConnectionState.DISCONNECTED);
      CompletableFuture<Void> disconnect = client.disconnect().toCompletableFuture();
      assertEquals("disconnect", first.nextRequest().method());
      first.close();
      assertThrows(ExecutionException.class, () -> disconnect.get(5, TimeUnit.SECONDS));
      AgentMessage recovery = second.nextRequest();
      assertEquals("snapshot", recovery.method());
      second.reply(recovery, ConnectionSnapshot.initial());
      awaitState(client, ConnectionState.DISCONNECTED);
      assertEquals(0, second.output.size());
    }
  }

  @Test
  void explicitRefreshReconnectsImmediatelyAfterServiceInstallation() throws Exception {
    FakeConnection connection = new FakeConnection();
    AtomicBoolean available = new AtomicBoolean();
    try (AgentClient client =
        new AgentClient(
            () -> {
              if (!available.get()) {
                throw new IOException("Service not installed yet");
              }
              return connection;
            })) {
      awaitState(client, ConnectionState.ERROR);
      available.set(true);
      CompletableFuture<ConnectionSnapshot> refreshed = client.refresh().toCompletableFuture();
      AgentMessage request = connection.nextRequest();
      assertEquals("snapshot", request.method());
      connection.reply(request, ConnectionSnapshot.initial());
      assertEquals(ConnectionState.DISCONNECTED, refreshed.get(5, TimeUnit.SECONDS).state());
      assertEquals(ConnectionState.DISCONNECTED, client.snapshot().state());
    }
  }

  @Test
  void closeCancelsRecoveryOfUnavailableService() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch retried = new CountDownLatch(1);
    try (AgentClient client =
        new AgentClient(
            () -> {
              if (attempts.incrementAndGet() > 1) {
                retried.countDown();
              }
              throw new IOException("Service unavailable");
            })) {
      awaitState(client, ConnectionState.ERROR);
    }
    assertFalse(retried.await(1, TimeUnit.SECONDS));
    assertEquals(1, attempts.get());
  }

  @Test
  void boundsOutstandingRequestsUnderParallelCallers() throws Exception {
    FakeConnection connection = new FakeConnection();
    try (AgentClient client = new AgentClient(() -> connection)) {
      AgentMessage initial = connection.nextRequest();
      connection.reply(initial, ConnectionSnapshot.initial());
      awaitState(client, ConnectionState.DISCONNECTED);
      List<CompletableFuture<Void>> requests = new ArrayList<>();
      CountDownLatch start = new CountDownLatch(1);
      List<Thread> callers = new ArrayList<>();
      for (int index = 0; index < 64; index++) {
        Thread caller =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        start.await();
                        CompletableFuture<Void> response =
                            client.disconnect().toCompletableFuture();
                        synchronized (requests) {
                          requests.add(response);
                        }
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                      }
                    });
        callers.add(caller);
      }
      start.countDown();
      for (Thread caller : callers) {
        caller.join(5000);
      }
      assertEquals(64, requests.size());
      assertTrue(
          requests.stream().filter(CompletableFuture::isCompletedExceptionally).count() >= 32);
    }
  }

  private static void awaitState(AgentClient client, ConnectionState expected) throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    AutoCloseable subscription =
        client.subscribe(
            value -> {
              if (value.state() == expected) {
                received.countDown();
              }
            });
    try {
      assertTrue(received.await(5, TimeUnit.SECONDS));
    } finally {
      subscription.close();
    }
  }

  private static final class FakeConnection implements IpcConnection {
    private final ArrayBlockingQueue<byte[]> input = new ArrayBlockingQueue<>(128);
    private final ArrayBlockingQueue<AgentMessage> output = new ArrayBlockingQueue<>(128);

    @Override
    public String peerIdentity() {
      return "authenticated-test-peer";
    }

    @Override
    public byte[] receive() throws IOException {
      try {
        byte[] bytes = input.take();
        if (bytes.length == 0) {
          throw new EOFException();
        }
        return bytes;
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException(exception);
      }
    }

    @Override
    public void send(byte[] message) throws IOException {
      if (!output.offer(AgentProtocol.decode(message))) {
        throw new IOException("Test queue overflow");
      }
    }

    AgentMessage nextRequest() throws InterruptedException {
      AgentMessage request = output.poll(5, TimeUnit.SECONDS);
      if (request == null) {
        throw new AssertionError("No request received");
      }
      return request;
    }

    void reply(AgentMessage request, Object value) {
      add(
          new AgentMessage(
              "response",
              request.id(),
              request.method(),
              value == null ? JsonNull.INSTANCE : JsonCodec.gson().toJsonTree(value),
              true,
              ""));
    }

    void snapshot(long revision, ConnectionState state) {
      add(
          new AgentMessage(
              "snapshot",
              null,
              "",
              JsonCodec.gson()
                  .toJsonTree(
                      new ConnectionSnapshot(
                          revision, state, null, null, null, false, false, "test")),
              true,
              ""));
    }

    void event(ConnectionEvent event) {
      add(
          new AgentMessage(
              "event", null, "", JsonCodec.gson().toJsonTree(event), true, ""));
    }

    private void add(AgentMessage message) {
      assertTrue(input.offer(AgentProtocol.encode(message)));
    }

    @Override
    public void close() {
      input.offer(new byte[0]);
    }
  }
}
