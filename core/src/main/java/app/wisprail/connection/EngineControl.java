package app.wisprail.connection;

import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import java.io.IOException;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Blocking operations called by ConnectionService's worker, never by the FX thread. */
public interface EngineControl extends AutoCloseable {
  interface Candidate extends AutoCloseable {
    UUID operationId();

    Profile profile();

    @Override
    void close() throws IOException;
  }

  Candidate prepare(Profile profile, ProfileSecrets secrets, UUID operationId) throws Exception;

  void start(Candidate candidate, BooleanSupplier cancelled, Consumer<String> progress)
      throws Exception;

  void stop() throws Exception;

  void recover() throws Exception;

  boolean running();

  void onFailure(BiConsumer<UUID, String> handler);

  DiagnosticReport diagnose(Profile profile, ProfileSecrets secrets) throws Exception;

  AddressAnalysis analyze(Profile profile, String address) throws Exception;

  @Override
  void close() throws IOException;
}
