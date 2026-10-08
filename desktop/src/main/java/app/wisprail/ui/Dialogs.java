package app.wisprail.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Owned dialogs with safe keyboard defaults and focus restoration supplied by JavaFX. */
final class Dialogs {
  record Choice<T>(String label, T value, boolean cancel, boolean destructive) {}

  private final Window owner;

  Dialogs(Window owner) {
    this.owner = owner;
  }

  <T> Optional<T> choose(String title, Node content, List<Choice<T>> choices) {
    Dialog<T> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle(title);
    dialog.setHeaderText(title);
    dialog.getDialogPane().setContent(content);
    dialog.getDialogPane().setPrefWidth(520);
    dialog.setResizable(true);
    Ui.stylesheet(dialog.getDialogPane());
    Map<ButtonType, T> results = new LinkedHashMap<>();
    Button cancel = null;
    for (Choice<T> choice : choices) {
      ButtonType type =
          new ButtonType(
              choice.label(),
              choice.cancel() ? ButtonBar.ButtonData.CANCEL_CLOSE : ButtonBar.ButtonData.OTHER);
      dialog.getDialogPane().getButtonTypes().add(type);
      results.put(type, choice.value());
      Button button = (Button) dialog.getDialogPane().lookupButton(type);
      button.setDefaultButton(false);
      if (choice.destructive()) {
        button.getStyleClass().add("danger");
      }
      if (choice.cancel()) {
        cancel = button;
      }
    }
    if (cancel != null) {
      Button initialFocus = cancel;
      dialog.setOnShown(event -> Platform.runLater(initialFocus::requestFocus));
    }
    dialog.setResultConverter(results::get);
    return dialog.showAndWait();
  }

  boolean confirm(String title, String message, String action, boolean destructive) {
    return choose(
            title,
            Ui.label(message, "body"),
            List.of(
                new Choice<>("Отмена", false, true, false),
                new Choice<>(action, true, false, destructive)))
        .orElse(false);
  }

  void information(String title, String message) {
    choose(title, Ui.label(message, "body"), List.of(new Choice<>("Закрыть", true, true, false)));
  }

  void error(String title, String message) {
    choose(title, Ui.notice(message, "error"), List.of(new Choice<>("Закрыть", true, true, false)));
  }

  Optional<String> addMethod() {
    Dialog<String> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Добавить VPN");
    dialog.setHeaderText("Добавить VPN");
    Ui.stylesheet(dialog.getDialogPane());
    VBox choices =
        new VBox(
            12,
            Ui.label(
                "Выберите, как добавить подключение. Оно не включится автоматически.", "hint"));
    String[][] options = {
      {"file", "Из файла · OpenVPN или профиль приложения"},
      {"link", "По ссылке · VLESS"},
      {"manual", "Вручную · сервер, доступ, сети и DNS"}
    };
    for (String[] option : options) {
      Button button =
          Ui.button(
              option[1],
              "button",
              () -> {
                dialog.setResult(option[0]);
                dialog.close();
              });
      button.setMaxWidth(Double.MAX_VALUE);
      button.setMinHeight(60);
      choices.getChildren().add(button);
    }
    dialog.getDialogPane().setContent(choices);
    dialog.getDialogPane().setPrefWidth(520);
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
    ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Отмена");
    dialog.setResultConverter(button -> null);
    return dialog.showAndWait();
  }

  boolean preview(String title, String description, String content, String action) {
    TextArea text = new TextArea(content);
    text.setEditable(false);
    text.setWrapText(true);
    text.setPrefRowCount(15);
    text.getStyleClass().add("mono");
    VBox body = new VBox(14, Ui.label(description, "hint"), text);
    return choose(
            title,
            body,
            List.of(
                new Choice<>("Отмена", false, true, false),
                new Choice<>(action, true, false, false)))
        .orElse(false);
  }
}
