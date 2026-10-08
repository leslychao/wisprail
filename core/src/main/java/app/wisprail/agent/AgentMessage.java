package app.wisprail.agent;

import com.google.gson.JsonElement;
import java.util.UUID;

record AgentMessage(
    String kind, UUID id, String method, JsonElement payload, boolean success, String error) {
  @Override
  public String toString() {
    return "AgentMessage[kind=" + kind + ", id=" + id + ", payload=REDACTED]";
  }
}
