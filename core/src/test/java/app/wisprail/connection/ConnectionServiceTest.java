package app.wisprail.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileFixtures;
import app.wisprail.profile.ProfileSecrets;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConnectionServiceTest {
  @Test
  void checkingAndCancellingCandidatePreservesActiveConnection() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      Profile active = ProfileFixtures.vless("Первый");
      connect(service, active);
      PreparedConnection candidate =
          await(service.prepare(ProfileFixtures.vless("Второй"), ProfileFixtures.secrets()));
      assertTrue(candidate.confirmationRequired());
      assertEquals(active, service.snapshot().activeProfile());
      assertEquals(ConnectionState.CONNECTED, service.snapshot().state());
      await(service.cancel(candidate.operationId()));
      assertEquals(active, service.snapshot().activeProfile());
      assertEquals(0, engine.stops.get());
      assertEquals(1, engine.starts.get());
      assertTrue(engine.lastCandidate.closed);
    }
  }

  @Test
  void pendingCancellationImmediatelyRejectsConnectAndNewPreparation() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      Profile profile = ProfileFixtures.vless("Первый");
      PreparedConnection prepared = await(service.prepare(profile, ProfileFixtures.secrets()));
      CompletionStage<Void> cancelled;
      synchronized (service) {
        cancelled = service.cancel(prepared.operationId());
        assertTrue(service.connect(prepared).toCompletableFuture().isCompletedExceptionally());
        assertTrue(
            service
                .prepare(profile, ProfileFixtures.secrets())
                .toCompletableFuture()
                .isCompletedExceptionally());
      }
      await(cancelled);
      assertEquals(0, engine.starts.get());
      connect(service, profile);
      assertEquals(1, engine.starts.get());
    }
  }

  @Test
  void latePreparationCannotUndoPendingDisconnect() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      Profile profile = ProfileFixtures.vless("Первый");
      engine.prepareGate = new CountDownLatch(1);
      engine.stopGate = new CountDownLatch(1);
      CompletionStage<PreparedConnection> preparing =
          service.prepare(profile, ProfileFixtures.secrets());
      assertTrue(engine.prepareEntered.await(2, TimeUnit.SECONDS));
      CompletionStage<Void> disconnecting = service.disconnect();
      engine.prepareGate.countDown();
      try {
        assertTrue(engine.stopEntered.await(2, TimeUnit.SECONDS));
        assertThrows(Exception.class, () -> await(preparing));
        assertEquals(ConnectionState.DISCONNECTING, service.snapshot().state());
        assertTrue(
            service
                .prepare(profile, ProfileFixtures.secrets())
                .toCompletableFuture()
                .isCompletedExceptionally());
      } finally {
        engine.stopGate.countDown();
      }
      await(disconnecting);
      assertEquals(ConnectionState.DISCONNECTED, service.snapshot().state());
      connect(service, profile);
      assertEquals(1, engine.starts.get());
    }
  }

  @Test
  void failedPreflightNeverStopsPreviousConnection() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      Profile active = ProfileFixtures.vless("Первый");
      connect(service, active);
      engine.failPrepare = true;
      assertThrows(
          Exception.class,
          () -> await(service.prepare(ProfileFixtures.vless("Второй"), ProfileFixtures.secrets())));
      assertEquals(active, service.snapshot().activeProfile());
      assertEquals(ConnectionState.CONNECTED, service.snapshot().state());
      assertEquals(0, engine.stops.get());
    }
  }

  @Test
  void connectionChangeInvalidatesOldConfirmation() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      Profile active = ProfileFixtures.vless("Первый");
      connect(service, active);
      PreparedConnection candidate =
          await(service.prepare(ProfileFixtures.vless("Второй"), ProfileFixtures.secrets()));
      await(service.renameActive(active.id(), "Новое имя"));
      assertThrows(Exception.class, () -> await(service.connect(candidate)));
      assertEquals(1, engine.starts.get());
      assertEquals(0, engine.stops.get());
    }
  }

  @Test
  void cancellationAndLateEngineSuccessCannotPublishConnected() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      engine.startGate = new CountDownLatch(1);
      PreparedConnection candidate =
          await(service.prepare(ProfileFixtures.vless("Первый"), ProfileFixtures.secrets()));
      CompletionStage<Void> connecting = service.connect(candidate);
      assertTrue(engine.startEntered.await(2, TimeUnit.SECONDS));
      CompletionStage<Void> cancel = service.cancel(candidate.operationId());
      engine.startGate.countDown();
      assertThrows(Exception.class, () -> await(connecting));
      await(cancel);
      assertEquals(ConnectionState.DISCONNECTED, service.snapshot().state());
      assertFalse(engine.running());
      assertFalse(
          service.recentEvents().stream()
              .anyMatch(event -> event.message().equals("Подключение установлено")));
    }
  }

  @Test
  void repeatedClickStartsOneProcessAndSwitchNeverOverlapsProcesses() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      PreparedConnection candidate =
          await(service.prepare(ProfileFixtures.vless("Первый"), ProfileFixtures.secrets()));
      engine.startGate = new CountDownLatch(1);
      CompletionStage<Void> first = service.connect(candidate);
      assertTrue(engine.startEntered.await(2, TimeUnit.SECONDS));
      assertThrows(Exception.class, () -> await(service.connect(candidate)));
      engine.startGate.countDown();
      await(first);
      connect(service, ProfileFixtures.vless("Второй"));
      assertEquals(2, engine.starts.get());
      assertEquals(1, engine.stops.get());
      assertEquals(1, engine.maximumProcesses.get());
    }
  }

  @Test
  void failedCleanupBlocksNewAttemptUntilExplicitRetry() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      connect(service, ProfileFixtures.vless("Первый"));
      engine.failStop = true;
      assertThrows(Exception.class, () -> await(service.disconnect()));
      assertTrue(service.snapshot().cleanupRequired());
      assertEquals(ConnectionState.ERROR, service.snapshot().state());
      assertThrows(
          Exception.class,
          () -> await(service.prepare(ProfileFixtures.vless("Второй"), ProfileFixtures.secrets())));
      engine.failStop = false;
      await(service.retryCleanup());
      assertFalse(service.snapshot().cleanupRequired());
      assertEquals(ConnectionState.DISCONNECTED, service.snapshot().state());
      connect(service, ProfileFixtures.vless("Второй"));
      assertEquals(ConnectionState.CONNECTED, service.snapshot().state());
    }
  }

  @Test
  void failedCandidateDeletionIsRetainedForCleanupRetry() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      PreparedConnection prepared =
          await(service.prepare(ProfileFixtures.vless("Первый"), ProfileFixtures.secrets()));
      engine.lastCandidate.failClose = true;
      assertThrows(Exception.class, () -> await(service.cancel(prepared.operationId())));
      assertTrue(service.snapshot().cleanupRequired());
      engine.lastCandidate.failClose = false;
      await(service.retryCleanup());
      assertTrue(engine.lastCandidate.closed);
      assertFalse(service.snapshot().cleanupRequired());
    }
  }

  @Test
  void recoveryFailureCanBeRetriedWithoutRestartingService() throws Exception {
    FakeEngine engine = new FakeEngine();
    engine.failRecover = true;
    try (ConnectionService service = new ConnectionService(engine)) {
      assertThrows(Exception.class, () -> await(service.initialize()));
      assertTrue(service.snapshot().cleanupRequired());
      engine.failRecover = false;
      await(service.retryCleanup());
      connect(service, ProfileFixtures.vless("Первый"));
      assertEquals(ConnectionState.CONNECTED, service.snapshot().state());
    }
  }

  @Test
  void repeatedCancelAndDisconnectShareThePendingOperation() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      engine.startGate = new CountDownLatch(1);
      PreparedConnection prepared =
          await(service.prepare(ProfileFixtures.vless("Первый"), ProfileFixtures.secrets()));
      CompletionStage<Void> connecting = service.connect(prepared);
      assertTrue(engine.startEntered.await(2, TimeUnit.SECONDS));
      CompletionStage<Void> cancelled = service.cancel(prepared.operationId());
      CompletionStage<Void> disconnected = service.disconnect();
      for (int count = 0; count < 64; count++) {
        assertEquals(cancelled, service.cancel(prepared.operationId()));
        assertEquals(disconnected, service.disconnect());
      }
      engine.startGate.countDown();
      assertThrows(Exception.class, () -> await(connecting));
      await(cancelled);
      await(disconnected);
      assertEquals(ConnectionState.DISCONNECTED, service.snapshot().state());
      assertFalse(engine.running());
    }
  }

  @Test
  void engineFailureStillStopsVpnWhenPreparedConfigCannotBeRemoved() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      connect(service, ProfileFixtures.vless("Первый"));
      UUID activeOperation = service.snapshot().operationId();
      await(service.prepare(ProfileFixtures.vless("Второй"), ProfileFixtures.secrets()));
      engine.lastCandidate.failClose = true;
      CountDownLatch failed = new CountDownLatch(1);
      AutoCloseable subscription =
          service.subscribe(
              snapshot -> {
                if (snapshot.cleanupRequired()) {
                  failed.countDown();
                }
              });
      try {
        engine.failureHandler.accept(activeOperation, "private engine detail");
        assertTrue(failed.await(2, TimeUnit.SECONDS));
        assertFalse(engine.running());
        assertTrue(service.snapshot().cleanupRequired());
        engine.lastCandidate.failClose = false;
        await(service.retryCleanup());
        assertFalse(service.snapshot().cleanupRequired());
      } finally {
        subscription.close();
      }
    }
  }

  @Test
  void disconnectStopsActiveVpnEvenIfPreparedSecretFileCannotBeDeleted() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      connect(service, ProfileFixtures.vless("Первый"));
      await(service.prepare(ProfileFixtures.vless("Второй"), ProfileFixtures.secrets()));
      engine.lastCandidate.failClose = true;
      assertThrows(Exception.class, () -> await(service.disconnect()));
      assertFalse(engine.running());
      assertTrue(service.snapshot().cleanupRequired());
      engine.lastCandidate.failClose = false;
      await(service.retryCleanup());
      assertFalse(service.snapshot().cleanupRequired());
    }
  }

  @Test
  void interruptedServiceShutdownDoesNotClaimConfirmedCleanup() throws Exception {
    FakeEngine engine = new FakeEngine();
    ConnectionService service = initialized(engine);
    engine.closeGate = new CountDownLatch(1);
    Thread closer = Thread.ofPlatform().start(service::close);
    assertTrue(engine.closeEntered.await(2, TimeUnit.SECONDS));
    closer.interrupt();
    closer.join(2000);
    engine.closeGate.countDown();
    assertFalse(closer.isAlive());
    assertTrue(service.snapshot().cleanupRequired());
    assertEquals(ConnectionState.ERROR, service.snapshot().state());
  }

  @Test
  void staleFailureFromPreviousProcessDoesNotStopNewConnection() throws Exception {
    FakeEngine engine = new FakeEngine();
    try (ConnectionService service = initialized(engine)) {
      connect(service, ProfileFixtures.vless("Первый"));
      UUID oldOperation = service.snapshot().operationId();
      Profile current = ProfileFixtures.vless("Второй");
      connect(service, current);
      int stops = engine.stops.get();
      engine.failureHandler.accept(oldOperation, "old process failure");
      assertEquals(current, service.snapshot().activeProfile());
      assertEquals(ConnectionState.CONNECTED, service.snapshot().state());
      assertEquals(stops, engine.stops.get());
    }
  }

  private static ConnectionService initialized(FakeEngine engine) throws Exception {
    ConnectionService service = new ConnectionService(engine);
    await(service.initialize());
    return service;
  }

  private static void connect(ConnectionService service, Profile profile) throws Exception {
    PreparedConnection candidate = await(service.prepare(profile, ProfileFixtures.secrets()));
    await(service.connect(candidate));
  }

  private static <T> T await(CompletionStage<T> future) throws Exception {
    return future.toCompletableFuture().get(3, TimeUnit.SECONDS);
  }

  private static final class FakeEngine implements EngineControl {
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicInteger maximumProcesses = new AtomicInteger();
    private final CountDownLatch startEntered = new CountDownLatch(1);
    private final CountDownLatch closeEntered = new CountDownLatch(1);
    private final CountDownLatch prepareEntered = new CountDownLatch(1);
    private final CountDownLatch stopEntered = new CountDownLatch(1);
    private volatile CountDownLatch startGate = new CountDownLatch(0);
    private volatile CountDownLatch closeGate = new CountDownLatch(0);
    private volatile CountDownLatch prepareGate = new CountDownLatch(0);
    private volatile CountDownLatch stopGate = new CountDownLatch(0);
    private volatile boolean running;
    private boolean failPrepare;
    private boolean failStop;
    private boolean failRecover;
    private FakeCandidate lastCandidate;
    private BiConsumer<UUID, String> failureHandler;

    @Override
    public Candidate prepare(Profile profile, ProfileSecrets secrets, UUID operationId)
        throws IOException {
      prepareEntered.countDown();
      awaitGate(prepareGate);
      if (failPrepare) {
        throw new IOException("check failed");
      }
      lastCandidate = new FakeCandidate(profile, operationId);
      return lastCandidate;
    }

    @Override
    public void start(Candidate candidate, BooleanSupplier cancelled, Consumer<String> progress)
        throws InterruptedException {
      assertNotNull(candidate);
      maximumProcesses.accumulateAndGet(running ? 2 : 1, Math::max);
      starts.incrementAndGet();
      startEntered.countDown();
      if (!startGate.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Test start gate timed out");
      }
      running = true;
    }

    @Override
    public void stop() throws IOException {
      stops.incrementAndGet();
      stopEntered.countDown();
      awaitGate(stopGate);
      if (failStop) {
        throw new IOException("cleanup failed");
      }
      running = false;
    }

    @Override
    public void recover() throws IOException {
      if (failRecover) {
        throw new IOException("recovery failed");
      }
    }

    private static void awaitGate(CountDownLatch gate) throws IOException {
      try {
        if (!gate.await(2, TimeUnit.SECONDS)) {
          throw new IOException("Test operation gate timed out");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IOException("Test operation interrupted", failure);
      }
    }

    @Override
    public boolean running() {
      return running;
    }

    @Override
    public void onFailure(BiConsumer<UUID, String> handler) {
      failureHandler = handler;
    }

    @Override
    public DiagnosticReport diagnose(Profile profile, ProfileSecrets secrets) {
      return new DiagnosticReport(profile.id(), Instant.now(), List.of());
    }

    @Override
    public AddressAnalysis analyze(Profile profile, String address) {
      return new AddressAnalysis(address, "", List.of(), List.of(), false, "По настройкам");
    }

    @Override
    public void close() throws IOException {
      closeEntered.countDown();
      try {
        if (!closeGate.await(2, TimeUnit.SECONDS)) {
          throw new IOException("Test close gate timed out");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IOException("Test close interrupted", failure);
      }
      stop();
    }
  }

  private static final class FakeCandidate implements EngineControl.Candidate {
    private final Profile profile;
    private final UUID operation;
    private boolean failClose;
    private boolean closed;

    private FakeCandidate(Profile profile, UUID operation) {
      this.profile = profile;
      this.operation = operation;
    }

    @Override
    public UUID operationId() {
      return operation;
    }

    @Override
    public Profile profile() {
      return profile;
    }

    @Override
    public void close() throws IOException {
      if (failClose) {
        throw new IOException("candidate cleanup failed");
      }
      closed = true;
    }
  }
}
