package app.wisprail.connection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PreparedConnection(
    UUID token,
    UUID operationId,
    UUID profileId,
    long profileRevision,
    long connectionRevision,
    boolean confirmationRequired,
    List<String> notes,
    Instant expiresAt) {

  public PreparedConnection {
    notes = List.copyOf(notes);
  }
}
