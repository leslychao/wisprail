package app.wisprail.connection;

import app.wisprail.profile.Profile;
import java.util.UUID;

/** activeProfile and operation/target IDs are absent (null) when not applicable. */
public record ConnectionSnapshot(
    long revision,
    ConnectionState state,
    UUID operationId,
    Profile activeProfile,
    UUID targetProfileId,
    boolean preparing,
    boolean cleanupRequired,
    String message) {

  public static ConnectionSnapshot initial() {
    return new ConnectionSnapshot(
        0, ConnectionState.DISCONNECTED, null, null, null, false, false, "Не подключён");
  }

  public boolean busy() {
    return preparing
        || state == ConnectionState.CONNECTING
        || state == ConnectionState.DISCONNECTING;
  }
}
