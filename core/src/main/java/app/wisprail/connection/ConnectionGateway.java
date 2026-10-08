package app.wisprail.connection;

import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface ConnectionGateway extends AutoCloseable {
  ConnectionSnapshot snapshot();

  AutoCloseable subscribe(Consumer<ConnectionSnapshot> listener);

  AutoCloseable subscribeEvents(Consumer<ConnectionEvent> listener);

  List<ConnectionEvent> recentEvents();

  CompletionStage<PreparedConnection> prepare(Profile profile, ProfileSecrets secrets);

  CompletionStage<Void> connect(PreparedConnection prepared);

  CompletionStage<Void> cancel(UUID operationId);

  CompletionStage<Void> disconnect();

  CompletionStage<Void> retryCleanup();

  CompletionStage<Void> renameActive(UUID profileId, String name);

  CompletionStage<DiagnosticReport> diagnose(Profile profile, ProfileSecrets secrets);

  CompletionStage<AddressAnalysis> analyze(Profile profile, String address);

  @Override
  void close();
}
