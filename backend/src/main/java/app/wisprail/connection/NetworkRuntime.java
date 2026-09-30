package app.wisprail.connection;

import app.wisprail.engine.SingboxConfig;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public interface NetworkRuntime extends AutoCloseable {
  record Prepared(
      UUID id, VpnProfile profile, Path directory, SingboxConfig.RuntimeNetwork network) {}

  record Check(String name, String status, String message) {}

  Prepared prepare(VpnProfile profile, ProfileSecrets secrets, BooleanSupplier cancelled)
      throws IOException, InterruptedException;

  void start(Prepared prepared, BooleanSupplier cancelled) throws IOException, InterruptedException;

  void discard(Prepared prepared) throws IOException;

  void stop() throws IOException, InterruptedException;

  boolean isRunning();

  boolean cleanupConfirmed();

  List<Check> diagnose() throws IOException, InterruptedException;

  @Override
  void close() throws IOException;
}
