package app.wisprail.ui;

import javafx.application.Application;

public final class DesktopLauncher {
  private DesktopLauncher() {}

  public static void main(String[] arguments) {
    Application.launch(WisprailApplication.class, arguments);
  }
}
