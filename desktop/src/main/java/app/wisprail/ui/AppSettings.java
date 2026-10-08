package app.wisprail.ui;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** Settings of the current user's interface, never a source of VPN connection state. */
final class AppSettings {
  enum CloseAction {
    ASK("Спрашивать"),
    TRAY("Скрывать в трей"),
    QUIT("Отключать VPN и выходить");

    private final String title;

    CloseAction(String title) {
      this.title = title;
    }

    @Override
    public String toString() {
      return title;
    }
  }

  private final Preferences preferences = Preferences.userNodeForPackage(AppSettings.class);

  CloseAction closeAction() {
    String saved = preferences.get("closeAction", CloseAction.ASK.name());
    for (CloseAction action : CloseAction.values()) {
      if (action.name().equals(saved)) {
        return action;
      }
    }
    return CloseAction.ASK;
  }

  void setCloseAction(CloseAction action) throws BackingStoreException {
    preferences.put("closeAction", action.name());
    preferences.flush();
  }

  boolean notifications() {
    return preferences.getBoolean("notifications", true);
  }

  void setNotifications(boolean enabled) throws BackingStoreException {
    preferences.putBoolean("notifications", enabled);
    preferences.flush();
  }
}
