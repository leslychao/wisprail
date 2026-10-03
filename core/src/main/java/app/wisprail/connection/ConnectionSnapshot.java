package app.wisprail.connection;

import app.wisprail.profile.VpnProfile;
import java.time.Instant;
import java.util.UUID;

public record ConnectionSnapshot(
    State state,
    long revision,
    UUID operationId,
    VpnProfile appliedProfile,
    VpnProfile targetProfile,
    String stage,
    String errorCode,
    String errorMessage,
    UUID preparedToken,
    Instant connectedAt) {
  public enum State {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
  }

  public static ConnectionSnapshot initial() {
    return new ConnectionSnapshot(State.DISCONNECTED, 0, null, null, null, "", "", "", null, null);
  }

  public boolean busy() {
    return operationId != null && preparedToken == null;
  }
}
