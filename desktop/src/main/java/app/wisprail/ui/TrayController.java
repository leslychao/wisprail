package app.wisprail.ui;

import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.profile.Profile;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/** Native tray registration with an accessible JavaFX panel showing the window's snapshot. */
final class TrayController implements AutoCloseable {
  private final Stage window;
  private final Runnable disconnect;
  private final Consumer<UUID> connect;
  private final Runnable exit;
  private final Stage popup = new Stage(StageStyle.UNDECORATED);
  private TrayIcon icon;
  private ConnectionSnapshot snapshot = ConnectionSnapshot.initial();
  private List<Profile> profiles = List.of();

  TrayController(Stage window, Runnable disconnect, Consumer<UUID> connect, Runnable exit) {
    this.window = window;
    this.disconnect = disconnect;
    this.connect = connect;
    this.exit = exit;
    popup.initOwner(window);
    popup.setAlwaysOnTop(true);
    popup.setResizable(false);
    popup
        .focusedProperty()
        .addListener(
            (observable, oldValue, focused) -> {
              if (!focused) {
                popup.hide();
              }
            });
    if (!SystemTray.isSupported()) {
      return;
    }
    BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(new Color(0x365AD8));
      graphics.fillRoundRect(1, 1, 30, 30, 9, 9);
      graphics.setColor(Color.WHITE);
      graphics.drawPolygon(new int[] {16, 7, 8, 16, 24, 25}, new int[] {6, 10, 20, 27, 20, 10}, 6);
    } finally {
      graphics.dispose();
    }
    TrayIcon candidate = new TrayIcon(image, "Wisprail");
    candidate.setImageAutoSize(true);
    candidate.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseReleased(MouseEvent event) {
            Platform.runLater(
                () -> {
                  var location = new Robot().getMousePosition();
                  showPanel(location.getX(), location.getY());
                });
          }
        });
    try {
      SystemTray.getSystemTray().add(candidate);
      icon = candidate;
    } catch (AWTException exception) {
      // The window must stay reachable when the platform declines tray registration.
      icon = null;
    }
  }

  boolean available() {
    return icon != null;
  }

  void update(ConnectionSnapshot snapshot, List<Profile> profiles) {
    this.snapshot = snapshot;
    this.profiles = List.copyOf(profiles);
    if (icon != null) {
      TrayIcon current = icon;
      String title =
          snapshot.activeProfile() == null
              ? "Wisprail · " + stateText(snapshot)
              : "Wisprail · " + snapshot.activeProfile().name() + " · " + stateText(snapshot);
      EventQueue.invokeLater(() -> current.setToolTip(title));
    }
    if (popup.isShowing()) {
      popup.getScene().setRoot(panel());
      popup.sizeToScene();
    }
  }

  void notifyError(String message) {
    if (icon != null) {
      TrayIcon current = icon;
      EventQueue.invokeLater(
          () -> current.displayMessage("Wisprail", message, TrayIcon.MessageType.ERROR));
    }
  }

  private void showPanel(double x, double y) {
    if (popup.isShowing()) {
      popup.hide();
      return;
    }
    Scene scene = new Scene(panel());
    scene.addEventFilter(
        KeyEvent.KEY_PRESSED,
        event -> {
          if (event.getCode() == KeyCode.ESCAPE) {
            popup.hide();
            event.consume();
          }
        });
    popup.setScene(scene);
    List<Screen> screens = Screen.getScreensForRectangle(x, y, 1, 1);
    Rectangle2D bounds =
        (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
    popup.sizeToScene();
    popup.setX(Math.max(bounds.getMinX(), Math.min(x - 326, bounds.getMaxX() - 340)));
    popup.setY(Math.max(bounds.getMinY(), Math.min(y - 420, bounds.getMaxY() - 450)));
    popup.show();
    popup.requestFocus();
  }

  private VBox panel() {
    VBox panel = new VBox(10);
    panel.getStyleClass().add("tray-panel");
    panel.setPrefWidth(326);
    Ui.stylesheet(panel);
    panel
        .getChildren()
        .addAll(
            Ui.label("Wisprail", "section-label"),
            Ui.label(
                snapshot.activeProfile() == null ? "VPN отключён" : snapshot.activeProfile().name(),
                "field-label"),
            Ui.label(stateText(snapshot), "hint"));
    if (snapshot.activeProfile() != null) {
      var button =
          Ui.button(
              "Отключить",
              "button",
              () -> {
                popup.hide();
                disconnect.run();
              });
      button.setDisable(snapshot.busy());
      panel.getChildren().add(button);
    }
    panel.getChildren().add(new Separator());
    VBox choices = new VBox(12);
    for (Profile profile : profiles) {
      boolean active =
          snapshot.activeProfile() != null && profile.id().equals(snapshot.activeProfile().id());
      var button =
          Ui.button(
              (active ? "✓ " : "") + profile.name(),
              "link",
              () -> {
                popup.hide();
                showWindow();
                connect.accept(profile.id());
              });
      button.setDisable(snapshot.busy() || snapshot.cleanupRequired() || active);
      button.setMaxWidth(Double.MAX_VALUE);
      choices.getChildren().add(button);
    }
    var scroll = Ui.scroll(choices);
    scroll.setMaxHeight(240);
    scroll.setPrefHeight(Math.min(240, profiles.size() * 30));
    panel.getChildren().add(scroll);
    panel
        .getChildren()
        .addAll(
            new Separator(),
            Ui.button("Открыть приложение", "link", this::showWindow),
            Ui.button(
                "Завершить работу",
                "link",
                () -> {
                  popup.hide();
                  showWindow();
                  exit.run();
                }));
    return panel;
  }

  void showWindow() {
    popup.hide();
    window.show();
    window.setIconified(false);
    window.toFront();
  }

  static String stateText(ConnectionSnapshot snapshot) {
    if (snapshot.cleanupRequired()) {
      return "Требуется проверка очистки";
    }
    return switch (snapshot.state()) {
      case DISCONNECTED -> "Не подключён";
      case CONNECTING -> "Подключение…";
      case CONNECTED -> "Подключён для выбранных сетей";
      case DISCONNECTING -> "Отключение…";
      case ERROR -> "Ошибка подключения";
    };
  }

  @Override
  public void close() {
    popup.hide();
    TrayIcon current = icon;
    icon = null;
    if (current != null) {
      EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(current));
    }
  }
}
