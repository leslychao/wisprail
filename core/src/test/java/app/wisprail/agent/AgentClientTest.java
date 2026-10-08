package app.wisprail.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.ConnectionState;
import app.wisprail.storage.JsonCodec;
import com.google.gson.JsonNull;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
      CompletableFuture<Void> ping = client.disconnect().toCompletableFuture();
      first.reply(first.nextRequest(), null);
      ping.get(5, TimeUnit.SECONDS);
      assertEquals(ConnectionState.CONNECTED, client.snapshot().state());
      assertEquals(connectedRevision, client.snapshot().revision());
      first.close();
      awaitState(client, ConnectionState.ERROR);
      long failedRevision = client.snapshot().revision();
      CompletableFuture<Void> reconnect = client.retryCleanup().toCompletableFuture();
      AgentMessage request = second.nextRequest();
      second.snapshot(0, ConnectionState.DISCONNECTED);
      second.reply(request, null);
      reconnect.get(5, TimeUnit.SECONDS);
      awaitState(client, ConnectionState.DISCONNECTED);
      assertTrue(client.snapshot().revision() > failedRevision);
    }
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

    private void add(AgentMessage message) {
      assertTrue(input.offer(AgentProtocol.encode(message)));
    }

    @Override
    public void close() {
      input.offer(new byte[0]);
    }
  }
}
