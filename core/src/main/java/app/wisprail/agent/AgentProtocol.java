package app.wisprail.agent;

import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.EventJournal;
import app.wisprail.connection.NetworkRuntime;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import java.util.List;
import java.util.UUID;

public final class AgentProtocol {
  public static final int VERSION = 1;

  private AgentProtocol() {}

  public enum Command {
    STATUS,
    PREPARE,
    CONNECT,
    CANCEL,
    DISCONNECT,
    DIAGNOSE
  }

  public record Request(
      int version,
      UUID id,
      Command command,
      VpnProfile profile,
      ProfileSecrets secrets,
      UUID token,
      long revision,
      long afterSequence) {
    public static Request status(long afterSequence) {
      return new Request(
          VERSION, UUID.randomUUID(), Command.STATUS, null, null, null, 0, afterSequence);
    }

    @Override
    public String toString() {
      return "AgentRequest[redacted]";
    }
  }

  public record Response(
      int version,
      UUID id,
      ConnectionSnapshot snapshot,
      List<EventJournal.Event> events,
      List<NetworkRuntime.Check> checks,
      String error,
      String journalError) {
    public Response {
      events = List.copyOf(events);
      checks = List.copyOf(checks);
    }
  }
}
