package app.wisprail.ui;

import javafx.beans.property.StringProperty;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/** Masked by default; revealing the value always requires an explicit user action. */
final class SecretField extends HBox {
  private final PasswordField masked = new PasswordField();
  private final TextField visible = new TextField();
  private final Button reveal;

  SecretField(String accessibleName, String hint) {
    super(4);
    masked.setAccessibleText(accessibleName);
    visible.setAccessibleText(accessibleName);
    masked.setPromptText(hint);
    visible.setPromptText(hint);
    visible.textProperty().bindBidirectional(masked.textProperty());
    HBox.setHgrow(masked, Priority.ALWAYS);
    HBox.setHgrow(visible, Priority.ALWAYS);
    reveal =
        Ui.iconButton(
            "eye",
            "Показать " + accessibleName,
            () -> {
              boolean showing = getChildren().getFirst() == visible;
              getChildren().set(0, showing ? masked : visible);
              revealDescription(accessibleName, showing);
              if (showing) {
                masked.requestFocus();
              } else {
                visible.requestFocus();
              }
            });
    getChildren().addAll(masked, reveal);
  }

  private void revealDescription(String accessibleName, boolean maskedNow) {
    String description = (maskedNow ? "Показать " : "Скрыть ") + accessibleName;
    reveal.setAccessibleText(description);
    reveal.getTooltip().setText(description);
  }

  String getText() {
    return masked.getText();
  }

  void setText(String value) {
    masked.setText(value);
  }

  void clear() {
    masked.clear();
  }

  StringProperty textProperty() {
    return masked.textProperty();
  }
}
