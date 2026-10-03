package app.wisprail.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionServiceTest {
  @TempDir Path directory;
  private static final ProfileSecrets SECRETS =
      new ProfileSecrets("", "00000000-0000-4000-8000-000000000001", "", "", "", "");

  @Test
  void candidateFailureDoesNotStopRunningVpnAndAppliedSettingsStayImmutable() throws Exception {
    FakeRuntime runtime = new FakeRuntime();
    try (var service = service(runtime)) {
      VpnProfile first = profile("First");
      connect(service, first);
      int previousStops = runtime.stops;
      runtime.failPrepare = true;
      service.prepare(profile("Second"), SECRETS);
      await(() -> !service.snapshot().busy());
      assertEquals(ConnectionSnapshot.State.CONNECTED, service.snapshot().state());
      assertEquals(first, service.snapshot().appliedProfile());
      assertEquals(previousStops, runtime.stops);
      assertEquals("PREPARATION_FAILED", service.snapshot().errorCode());
    }
  }

  @Test
  void confirmationIsBoundToCandidateAndRevisionAndFailureDoesNotRollback() throws Exception {
    FakeRuntime runtime = new FakeRuntime();
    try (var service = service(runtime)) {
      connect(service, profile("First"));
      service.prepare(profile("Second"), SECRETS);
      await(() -> service.snapshot().preparedToken() != null);
      var state = service.snapshot();
      assertThrows(
          IllegalStateException.class, () -> service.connect(UUID.randomUUID(), state.revision()));
      assertThrows(
          IllegalStateException.class,
          () -> service.connect(state.preparedToken(), state.revision() - 1));
      runtime.failStart = true;
      service.connect(state.preparedToken(), state.revision());
      await(() -> !service.snapshot().busy());
      assertEquals(ConnectionSnapshot.State.ERROR, service.snapshot().state());
      assertEquals(2, runtime.starts);
      assertTrue(runtime.clean);
    }
  }

  @Test
  void duplicateClickAndCancelledLatePreparationCannotStartVpn() throws Exception {
    FakeRuntime runtime = new FakeRuntime();
    runtime.prepareGate = new CountDownLatch(1);
    try (var service = service(runtime)) {
      UUID operation = service.prepare(profile("First"), SECRETS);
      assertThrows(IllegalStateException.class, () -> service.prepare(profile("Second"), SECRETS));
      service.cancel(operation);
      runtime.prepareGate.countDown();
      await(() -> !service.snapshot().busy());
      assertEquals(ConnectionSnapshot.State.DISCONNECTED, service.snapshot().state());
      assertEquals(0, runtime.starts);
      assertEquals(1, runtime.discards);
    }
  }

  @Test
  void cleanupFailureBlocksAnotherStart() throws Exception {
    FakeRuntime runtime = new FakeRuntime();
    try (var service = service(runtime)) {
      connect(service, profile("First"));
      runtime.failStop = true;
      service.disconnect();
      await(() -> !service.snapshot().busy());
      assertEquals("CLEANUP_FAILED", service.snapshot().errorCode());
      assertThrows(IllegalStateException.class, () -> service.prepare(profile("Second"), SECRETS));
      runtime.failStop = false;
      service.disconnect();
      await(() -> !service.snapshot().busy());
      assertEquals(ConnectionSnapshot.State.DISCONNECTED, service.snapshot().state());
    }
  }

  private ConnectionService service(FakeRuntime runtime) throws IOException {
    return new ConnectionService(runtime, new EventJournal(directory));
  }

  @Test
  void cancellationCannotHidePreparationCleanupFailure() throws Exception {
    FakeRuntime runtime = new FakeRuntime();
    runtime.prepareGate = new CountDownLatch(1);
    runtime.failPrepareCleanup = true;
    try (var service = service(runtime)) {
      UUID operation = service.prepare(profile("First"), SECRETS);
      service.cancel(operation);
      runtime.prepareGate.countDown();
      await(() -> !service.snapshot().busy());
      assertEquals("CANDIDATE_CLEANUP", service.snapshot().errorCode());
      assertThrows(IllegalStateException.class, () -> service.prepare(profile("Second"), SECRETS));
    }
  }

  @Test
  void failedCandidateRemovalBlocksStartButDoesNotPreventStoppingActiveVpn() throws Exception {
    FakeRuntime runtime = new FakeRuntime();
    try (var service = service(runtime)) {
      connect(service, profile("First"));
      service.prepare(profile("Second"), SECRETS);
      await(() -> service.snapshot().preparedToken() != null);
      runtime.failDiscard = true;
      service.cancel(service.snapshot().operationId());
      await(() -> service.snapshot().operationId() == null);
      assertEquals("CANDIDATE_CLEANUP", service.snapshot().errorCode());
      assertTrue(runtime.running);
      assertThrows(IllegalStateException.class, () -> service.prepare(profile("Third"), SECRETS));
      service.disconnect();
      await(() -> !service.snapshot().busy());
      assertEquals(ConnectionSnapshot.State.DISCONNECTED, service.snapshot().state());
      assertTrue(runtime.clean);
      assertThrows(IllegalStateException.class, () -> service.prepare(profile("Third"), SECRETS));
    }
  }

  @Test
  void lateSuccessfulStartCannotOverwriteDisconnect() throws Exception {
    FakeRuntime runtime = new FakeRuntime();
    runtime.startGate = new CountDownLatch(1);
    try (var service = service(runtime)) {
      service.prepare(profile("First"), SECRETS);
      await(() -> service.snapshot().preparedToken() != null);
      var prepared = service.snapshot();
      service.connect(prepared.preparedToken(), prepared.revision());
      await(() -> runtime.starts == 1);
      service.disconnect();
      UUID stopOperation = service.snapshot().operationId();
      runtime.startGate.countDown();
      await(() -> !service.snapshot().busy());
      assertEquals(ConnectionSnapshot.State.DISCONNECTED, service.snapshot().state());
      assertEquals(null, service.snapshot().appliedProfile());
      assertNotEquals(stopOperation, service.snapshot().operationId());
      assertTrue(runtime.clean);
    }
  }

  @Test
  void lostReadinessStopsVpnWithoutAutomaticRetry() throws Exception {
    FakeRuntime runtime = new FakeRuntime();
    try (var service = service(runtime)) {
      connect(service, profile("First"));
      runtime.failHealth = true;
      service.observeProcess();
      await(() -> service.snapshot().errorCode().equals("CONNECTION_LOST"));
      assertEquals(ConnectionSnapshot.State.ERROR, service.snapshot().state());
      assertEquals(1, runtime.starts);
      assertTrue(runtime.clean);
    }
  }

  private static VpnProfile profile(String name) {
    return new VpnProfile(
        1,
        UUID.randomUUID(),
        1,
        name,
        "vpn.example.com",
        443,
        VpnProfile.Protocol.VLESS,
        null,
        new VpnProfile.Vless(VpnProfile.Security.TLS, "vpn.example.com", "", "", "chrome", ""),
        List.of("10.20.0.0/16"),
        new VpnProfile.Dns("10.20.0.53", List.of("corp.example")),
        "",
        "");
  }

  private static void connect(ConnectionService service, VpnProfile profile) throws Exception {
    service.prepare(profile, SECRETS);
    await(() -> service.snapshot().preparedToken() != null);
    var state = service.snapshot();
    service.connect(state.preparedToken(), state.revision());
    await(() -> !service.snapshot().busy());
    assertEquals(ConnectionSnapshot.State.CONNECTED, service.snapshot().state());
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(), "Transition did not complete");
  }

  private static final class FakeRuntime implements NetworkRuntime {
    volatile boolean clean = true;
    volatile boolean running;
    volatile boolean failPrepare;
    volatile boolean failPrepareCleanup;
    volatile boolean failStart;
    volatile boolean failStop;
    volatile boolean failHealth;
    volatile boolean failDiscard;
    volatile int starts;
    volatile int stops;
    volatile int discards;
    CountDownLatch prepareGate = new CountDownLatch(0);
    CountDownLatch startGate = new CountDownLatch(0);

    @Override
    public Prepared prepare(VpnProfile profile, ProfileSecrets secrets, BooleanSupplier cancelled)
        throws IOException, InterruptedException {
      prepareGate.await();
      if (failPrepareCleanup) {
        throw new NetworkFailure("CANDIDATE_CLEANUP", "Cannot clean preparation");
      }
      if (failPrepare) {
        throw new IOException("Rejected candidate");
      }
      return new Prepared(UUID.randomUUID(), profile, Path.of("candidate"), null);
    }

    @Override
    public void start(Prepared prepared, BooleanSupplier cancelled)
        throws IOException, InterruptedException {
      starts++;
      startGate.await();
      if (failStart) {
        throw new IOException("Failed to start");
      }
      running = true;
      clean = false;
    }

    @Override
    public void stop() throws IOException {
      stops++;
      if (failStop) {
        throw new IOException("Cleanup failed");
      }
      running = false;
      clean = true;
    }

    @Override
    public void discard(Prepared prepared) throws IOException {
      if (failDiscard) {
        throw new IOException("Cannot remove candidate");
      }
      discards++;
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public boolean cleanupConfirmed() {
      return clean;
    }

    @Override
    public List<Check> diagnose() throws IOException {
      if (failHealth) {
        throw new IOException("Readiness lost");
      }
      return List.of();
    }

    @Override
    public void close() {}
  }
}
