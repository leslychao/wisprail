package app.wisprail.ui;

import javafx.application.Application;

/** Plain launcher for the bundled classpath distribution. */
public final class Launcher {
  private Launcher() {}

  public static void main(String[] arguments) {
    Application.launch(WisprailApplication.class, arguments);
  }
}
