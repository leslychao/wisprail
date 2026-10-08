package app.wisprail.platform;

public record ComponentStatus(State state, String message) {
  public enum State {
    READY,
    MISSING,
    APPROVAL_REQUIRED,
    STOPPED,
    ERROR
  }
}
