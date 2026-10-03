package app.wisprail.ui;

public record AppSettings(boolean autoStart, CloseAction closeAction, boolean notifications) {
  public enum CloseAction {
    ASK,
    TRAY,
    EXIT
  }

  public static AppSettings defaults() {
    return new AppSettings(false, CloseAction.ASK, true);
  }
}
