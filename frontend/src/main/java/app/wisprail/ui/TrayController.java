package app.wisprail.ui;

import app.wisprail.profile.VpnProfile;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Objects;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

final class TrayController implements AutoCloseable {
  private TrayIcon icon;
  private final Popup popup = new Popup();

  boolean install(DesktopModel model, ConnectionsPane connections, Runnable show, Runnable exit) {
    if (!SystemTray.isSupported()) {
      return false;
    }
    BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
    var graphics = image.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setColor(new Color(54, 90, 216));
    graphics.fillRoundRect(2, 2, 28, 28, 8, 8);
    graphics.setColor(Color.WHITE);
    graphics.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    graphics.drawPolygon(
        new int[] {16, 23, 23, 20, 16, 12, 9, 9}, new int[] {7, 10, 17, 22, 25, 22, 17, 10}, 8);
    graphics.dispose();
    icon = new TrayIcon(image, "Wisprail");
    icon.setImageAutoSize(true);
    popup.setAutoHide(true);
    popup.setHideOnEscape(true);
    icon.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseReleased(MouseEvent event) {
            int x = event.getX();
            int y = event.getY();
            Platform.runLater(
                () -> {
                  if (popup.isShowing()) {
                    popup.hide();
                    return;
                  }
                  VBox content =
                      content(
                          model,
                          connections,
                          () -> {
                            popup.hide();
                            show.run();
                          },
                          () -> {
                            popup.hide();
                            exit.run();
                          });
                  popup.getContent().setAll(content);
                  popup.show(
                      connections.getScene().getWindow(), x - 326, y - content.prefHeight(326));
                });
          }
        });
    icon.addActionListener(event -> Platform.runLater(show));
    try {
      SystemTray.getSystemTray().add(icon);
    } catch (AWTException exception) {
      icon = null;
      return false;
    }
    Runnable refresh =
        () -> {
          String status =
              model.online.get()
                  ? ConnectionsPane.statusText(model.connection.get())
                  : "Служба недоступна";
          EventQueue.invokeLater(
              () -> {
                if (icon != null) {
                  icon.setToolTip("Wisprail · " + status);
                }
              });
          if (popup.isShowing()) {
            popup
                .getContent()
                .setAll(
                    content(
                        model,
                        connections,
                        () -> {
                          popup.hide();
                          show.run();
                        },
                        () -> {
                          popup.hide();
                          exit.run();
                        }));
          }
        };
    model.connection.addListener(
        (observable, previous, current) -> {
          refresh.run();
          if (model.settings.get().notifications()
              && !current.errorCode().isEmpty()
              && !current.errorCode().equals(previous.errorCode())) {
            EventQueue.invokeLater(
                () -> {
                  if (icon != null) {
                    icon.displayMessage(
                        "Wisprail", current.errorMessage(), TrayIcon.MessageType.WARNING);
                  }
                });
          }
        });
    model.online.addListener((observable, previous, current) -> refresh.run());
    model.profiles.addListener((ListChangeListener<VpnProfile>) change -> refresh.run());
    refresh.run();
    return true;
  }

  static VBox content(
      DesktopModel model, ConnectionsPane connections, Runnable show, Runnable exit) {
    var state = model.connection.get();
    String name =
        state.appliedProfile() == null ? "VPN не подключён" : state.appliedProfile().name();
    if (state.appliedProfile() != null) {
      name =
          model.profiles.stream()
              .filter(profile -> profile.id().equals(state.appliedProfile().id()))
              .map(VpnProfile::name)
              .findFirst()
              .orElse(name);
    }
    VBox content =
        new VBox(
            12,
            Ui.label(name, "section-title"),
            Ui.label(ConnectionsPane.statusText(state), "muted"));
    content.setPrefWidth(326);
    content.setPadding(new Insets(18));
    content.getStyleClass().add("tray-panel");
    content
        .getStylesheets()
        .add(
            Objects.requireNonNull(TrayController.class.getResource("wisprail.css"))
                .toExternalForm());
    Button disconnect = Ui.button("Отключить", connections.actions()::stop);
    disconnect.setDisable(state.appliedProfile() == null || state.busy());
    content
        .getChildren()
        .addAll(disconnect, new Separator(), Ui.label("Переключиться на", "small"));
    VBox profiles = new VBox(5);
    for (VpnProfile profile : model.profiles) {
      if (state.appliedProfile() != null && state.appliedProfile().id().equals(profile.id())) {
        continue;
      }
      Button select =
          Ui.button(
              profile.name(),
              () -> {
                show.run();
                connections.select(profile);
                connections.actions().connect(profile);
              });
      select.setMaxWidth(Double.MAX_VALUE);
      select.setDisable(
          !model.online.get() || state.busy() || state.errorCode().equals("CLEANUP_FAILED"));
      profiles.getChildren().add(select);
    }
    var scroll = Ui.scroll(profiles);
    scroll.setMaxHeight(220);
    content
        .getChildren()
        .addAll(
            scroll,
            new Separator(),
            Ui.button("Открыть приложение", show),
            Ui.button("Завершить работу", exit));
    return content;
  }

  @Override
  public void close() {
    popup.hide();
    EventQueue.invokeLater(
        () -> {
          if (icon != null) {
            SystemTray.getSystemTray().remove(icon);
            icon = null;
          }
        });
  }
}
