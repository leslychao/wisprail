package app.wisprail.ui;

import java.util.List;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/** Shared, accessible controls and layout used by the desktop views. */
final class Ui {
  private Ui() {}

  static Label label(String text, String style) {
    Label label = new Label(text);
    label.getStyleClass().add(style);
    label.setWrapText(true);
    return label;
  }

  static Label heading(String text) {
    return label(text, "heading");
  }

  static Button button(String text, String style, Runnable action) {
    Button button = new Button(text);
    button.setMinWidth(Region.USE_PREF_SIZE);
    button.getStyleClass().add(style);
    button.setOnAction(event -> action.run());
    return button;
  }

  static Button iconButton(String symbol, String description, Runnable action) {
    Button button = button("", "icon-button", action);
    button.setGraphic(icon(symbol));
    button.setAccessibleText(description);
    button.setTooltip(new Tooltip(description));
    return button;
  }

  static SVGPath icon(String name) {
    String path =
        switch (name) {
          case "shield" -> "M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6l-8-3Z";
          case "check" -> "m5 12 4 4L19 6";
          case "plus" -> "M12 5v14M5 12h14";
          case "close" -> "m6 6 12 12M6 18 18 6";
          case "power" -> "M12 3v8M6.5 5.5a8 8 0 1 0 11 0";
          case "network" -> "M5 16v-4h14v4M12 8v4M9 3h6v5H9zM2 16h6v5H2zM16 16h6v5h-6z";
          case "globe" ->
              "M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM3 12h18M12 3c-5 5-5 13 0 18 5-5 5-13 0-18Z";
          case "file" -> "M14 3H5v18h14V8l-5-5Zm0 0v5h5M8 12h8M8 16h6";
          case "edit" -> "m15 4 5 5-11 11H4v-5L15 4Zm-2 2 5 5";
          case "key" -> "M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Zm-5 4v10m0-4h4m-4 4h3";
          case "dots" -> "M5 12h.01M12 12h.01M19 12h.01";
          case "eye" ->
              "M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7Zm13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z";
          case "swap" -> "M4 7h15m-4-4 4 4-4 4M20 17H5m4-4-4 4 4 4";
          case "alert" -> "m12 3 10 18H2L12 3Zm0 6v5m0 3h.01";
          default -> "M12 11v6M12 7h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z";
        };
    SVGPath icon = new SVGPath();
    icon.setContent(path);
    icon.getStyleClass().add("line-icon");
    icon.setScaleX(0.75);
    icon.setScaleY(0.75);
    icon.setMouseTransparent(true);
    return icon;
  }

  static HBox row(Node... nodes) {
    HBox row = new HBox(10, nodes);
    row.setAlignment(Pos.CENTER_LEFT);
    return row;
  }

  static Region spacer() {
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    return spacer;
  }

  static VBox field(String title, Node control, String hint) {
    Label label = label(title, "field-label");
    label.setLabelFor(control);
    VBox box = new VBox(7, label, control);
    if (!hint.isBlank()) {
      box.getChildren().add(label(hint, "hint"));
    }
    return box;
  }

  static VBox notice(String text, String kind) {
    VBox notice = new VBox(8, label(text, "notice-text"));
    notice.getStyleClass().addAll("notice", kind);
    return notice;
  }

  static VBox page(Node... children) {
    VBox page = new VBox(20, children);
    page.setPadding(new Insets(26, 32, 26, 32));
    page.setFillWidth(true);
    return page;
  }

  static ScrollPane scroll(Node content) {
    ScrollPane scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
    scroll.getStyleClass().add("content-scroll");
    return scroll;
  }

  static void invalid(TextInputControl control, boolean invalid) {
    control.pseudoClassStateChanged(PseudoClass.getPseudoClass("invalid"), invalid);
  }

  static void stylesheet(Parent parent) {
    var resource = Ui.class.getResource("/app/wisprail/ui/application.css");
    if (resource == null) {
      throw new IllegalStateException("Missing application stylesheet");
    }
    parent.getStylesheets().setAll(List.of(resource.toExternalForm()));
  }
}
