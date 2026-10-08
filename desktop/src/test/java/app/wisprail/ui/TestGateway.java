package app.wisprail.ui;

import app.wisprail.connection.AddressAnalysis;
import app.wisprail.connection.ConnectionEvent;
import app.wisprail.connection.ConnectionGateway;
import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.DiagnosticReport;
import app.wisprail.connection.PreparedConnection;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Scene fixtures only. No OS service, child engine, DNS or route operation is available here. */
final class TestGateway implements ConnectionGateway {
  private final ConnectionSnapshot snapshot;
  int connects;
  int disconnects;

  TestGateway(ConnectionSnapshot snapshot) {
    this.snapshot = snapshot;
  }

  @Override
  public ConnectionSnapshot snapshot() {
    return snapshot;
  }

  @Override
  public AutoCloseable subscribe(Consumer<ConnectionSnapshot> listener) {
    listener.accept(snapshot);
    return () -> {};
  }

  @Override
  public AutoCloseable subscribeEvents(Consumer<ConnectionEvent> listener) {
    return () -> {};
  }

  @Override
  public List<ConnectionEvent> recentEvents() {
    return List.of(
        new ConnectionEvent(
            1,
            Instant.now(),
            "error",
            snapshot.activeProfile().id(),
            "Сервер не ответил вовремя",
            "TIMEOUT\nПроверьте сервер или повторите попытку."));
  }

  @Override
  public CompletionStage<PreparedConnection> prepare(Profile profile, ProfileSecrets secrets) {
    return CompletableFuture.completedFuture(
        new PreparedConnection(
            UUID.randomUUID(),
            UUID.randomUUID(),
            profile.id(),
            profile.revision(),
            snapshot.revision(),
            true,
            List.of(),
            Instant.now().plusSeconds(60)));
  }

  @Override
  public CompletionStage<Void> connect(PreparedConnection prepared) {
    connects++;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> cancel(UUID operationId) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> disconnect() {
    disconnects++;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> retryCleanup() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<Void> renameActive(UUID profileId, String name) {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletionStage<DiagnosticReport> diagnose(Profile profile, ProfileSecrets secrets) {
    return CompletableFuture.completedFuture(
        new DiagnosticReport(profile.id(), Instant.now(), List.of()));
  }

  @Override
  public CompletionStage<AddressAnalysis> analyze(Profile profile, String address) {
    return CompletableFuture.completedFuture(
        new AddressAnalysis(
            address,
            "",
            List.of(),
            List.of(),
            false,
            "Сетевой запрос в UI-проверке не выполняется"));
  }

  @Override
  public void close() {}
}
