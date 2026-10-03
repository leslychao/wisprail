package app.wisprail.ui;

import java.awt.SystemTray;
import java.util.Objects;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class WisprailApplication extends Application {
  private final DesktopModel model = new DesktopModel();
  private final TrayController tray = new TrayController();
  private Stage stage;
  private DesktopView view;
  private boolean trayAvailable;
  private boolean exiting;

  @Override
  public void start(Stage primaryStage) {
    stage = primaryStage;
    Platform.setImplicitExit(false);
    trayAvailable = SystemTray.isSupported();
    view = new DesktopView(stage, model, trayAvailable);
    trayAvailable = tray.install(model, view.connections(), this::show, () -> close(true));
    Scene scene = new Scene(view, 1070, 700);
    scene
        .getStylesheets()
        .add(Objects.requireNonNull(getClass().getResource("wisprail.css")).toExternalForm());
    stage.setScene(scene);
    stage.setTitle("Wisprail");
    stage.setMinWidth(860);
    stage.setMinHeight(640);
    stage.setOnCloseRequest(
        event -> {
          event.consume();
          requestClose();
        });
    stage.show();
    model.initialize(message -> Ui.error(stage, message));
    if (getParameters().getRaw().contains("--tray") && trayAvailable) {
      stage.hide();
    }
  }

  private void show() {
    stage.show();
    stage.toFront();
  }

  private void requestClose() {
    close(false);
  }

  private void close(boolean explicitExit) {
    if (exiting || !view.canLeaveEditor()) {
      return;
    }
    if (!explicitExit
        && model.settings.get().closeAction() == AppSettings.CloseAction.TRAY
        && trayAvailable) {
      stage.hide();
      return;
    }
    if ((explicitExit || model.settings.get().closeAction() == AppSettings.CloseAction.ASK)
        && (model.connection.get().appliedProfile() != null || model.connection.get().busy())) {
      var applied = model.connection.get().appliedProfile();
      String name = applied == null ? "VPN" : applied.name();
      var result = Ui.exitDialog(stage, name, trayAvailable).showAndWait();
      if (result.filter(button -> button.getText().equals("Скрыть в трей")).isPresent()) {
        stage.hide();
        return;
      }
      if (result.filter(button -> button.getText().equals("Отключить и выйти")).isEmpty()) {
        return;
      }
    }
    exiting = true;
    stage.getScene().getRoot().setDisable(true);
    model.close(
        () -> {
          tray.close();
          view.dispose();
          Platform.exit();
        });
  }
}
