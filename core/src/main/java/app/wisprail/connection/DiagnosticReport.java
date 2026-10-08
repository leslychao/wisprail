package app.wisprail.connection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DiagnosticReport(UUID profileId, Instant checkedAt, List<DiagnosticCheck> checks) {
  public DiagnosticReport {
    checks = List.copyOf(checks);
  }
}
