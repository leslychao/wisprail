package app.wisprail.ui;

import java.util.Locale;
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import javafx.stage.Window;

final class Ui {
  private Ui() {}

  static Button button(String text, Runnable action) {
    Button button = new Button(text);
    button.setOnAction(event -> action.run());
    return button;
  }

  static Hyperlink link(String text, Runnable action) {
    Hyperlink link = new Hyperlink(text);
    link.setOnAction(event -> action.run());
    return link;
  }

  static Button iconButton(String name, String accessibleText, Runnable action) {
    Button button = button("", action);
    button.setGraphic(Icons.of(name));
    button.setAccessibleText(accessibleText);
    button.setTooltip(new Tooltip(accessibleText));
    button.getStyleClass().add("icon-button");
    return button;
  }

  static Label label(String text, String style) {
    Label label = new Label(text);
    label.setWrapText(true);
    label.getStyleClass().add(style);
    return label;
  }

  static VBox box(Node... children) {
    VBox box = new VBox(16, children);
    box.setPadding(new Insets(24));
    return box;
  }

  static ScrollPane scroll(Node content) {
    ScrollPane scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    return scroll;
  }

  static void error(Window owner, String message) {
    Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
    alert.initOwner(owner);
    alert.setHeaderText("Операция не завершена");
    style(alert);
    alert.showAndWait();
  }

  static boolean confirm(Window owner, String title, String message, String action) {
    ButtonType proceed = new ButtonType(action);
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, proceed);
    alert.initOwner(owner);
    alert.setHeaderText(title);
    style(alert);
    return alert.showAndWait().filter(proceed::equals).isPresent();
  }

  static Alert exitDialog(Window owner, String profileName, boolean trayAvailable) {
    Alert dialog =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            "Сейчас подключён «" + profileName + "». При завершении работы он будет отключён.",
            ButtonType.CANCEL);
    if (trayAvailable) {
      dialog.getButtonTypes().add(new ButtonType("Скрыть в трей"));
    }
    dialog.getButtonTypes().add(new ButtonType("Отключить и выйти"));
    dialog.initOwner(owner);
    dialog.setHeaderText("Завершить работу?");
    style(dialog);
    dialog.getDialogPane().setPrefWidth(610);
    return dialog;
  }

  static void style(Dialog<?> dialog) {
    String stylesheet =
        Objects.requireNonNull(Ui.class.getResource("wisprail.css")).toExternalForm();
    if (!dialog.getDialogPane().getStylesheets().contains(stylesheet)) {
      dialog.getDialogPane().getStylesheets().add(stylesheet);
      dialog.initStyle(StageStyle.TRANSPARENT);
      dialog.getDialogPane().setPrefWidth(480);
      dialog.getDialogPane().setGraphic(null);
      Label title = label(dialog.getHeaderText(), "section-heading");
      Region spacer = new Region();
      HBox.setHgrow(spacer, Priority.ALWAYS);
      HBox header =
          new HBox(12, title, spacer, iconButton("close", "Закрыть диалог", dialog::close));
      header.setAlignment(Pos.CENTER_LEFT);
      header.getStyleClass().add("dialog-heading");
      dialog.getDialogPane().setHeader(header);
      dialog.setOnShowing(event -> dialog.getDialogPane().getScene().setFill(Color.TRANSPARENT));
      if (dialog.getOwner() != null && dialog.getOwner().getScene() != null) {
        Node ownerRoot = dialog.getOwner().getScene().getRoot();
        var previous = ownerRoot.getEffect();
        dialog.setOnShown(
            event -> {
              ownerRoot.setEffect(new ColorAdjust(0, -.08, -.20, 0));
              var origin = ownerRoot.localToScreen(0, 0);
              if (origin != null) {
                dialog.setX(
                    origin.getX()
                        + (dialog.getOwner().getScene().getWidth() - dialog.getWidth()) / 2);
                dialog.setY(
                    origin.getY()
                        + (dialog.getOwner().getScene().getHeight() - dialog.getHeight()) / 2);
              }
            });
        dialog.setOnHidden(event -> ownerRoot.setEffect(previous));
      }
    }
    Node cancel = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
    if (dialog.getDialogPane().lookup(".button-bar") instanceof ButtonBar bar) {
      bar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);
    }
    for (ButtonType type : dialog.getDialogPane().getButtonTypes()) {
      Node node = dialog.getDialogPane().lookupButton(type);
      if (node instanceof Button button) {
        ButtonBar.setButtonUniformSize(button, false);
        button.setMinWidth(Region.USE_PREF_SIZE);
        if (type == dialog.getDialogPane().getButtonTypes().getLast()
            && type != ButtonType.CANCEL) {
          button
              .getStyleClass()
              .add(
                  type.getText().toLowerCase(Locale.ROOT).contains("удалить")
                      ? "danger"
                      : "primary");
        }
      }
    }
    if (cancel instanceof Button button) {
      button.setText("Отмена");
      button.setDefaultButton(true);
      dialog.getDialogPane().getButtonTypes().stream()
          .filter(type -> type != ButtonType.CANCEL)
          .map(type -> dialog.getDialogPane().lookupButton(type))
          .filter(Button.class::isInstance)
          .map(Button.class::cast)
          .forEach(other -> other.setDefaultButton(false));
    }
  }
}
