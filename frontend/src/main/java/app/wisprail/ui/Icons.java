package app.wisprail.ui;

import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/** Small outline icons, using the same 24-unit coordinate system as the design. */
final class Icons {
  private Icons() {}

  static Node of(String name) {
    String path =
        switch (name) {
          case "shield" -> "M12 3 4 6v6c0 5 8 9 8 9s8-4 8-9V6l-8-3Z";
          case "check" -> "m5 12 4 4L19 6";
          case "info" -> "M22 12 A10 10 0 1 1 2 12 A10 10 0 1 1 22 12 M12 11 V17 M12 7 H12.01";
          case "power" -> "M12 3v8M6.5 5.5a8 8 0 1 0 11 0";
          case "network" ->
              "M9 3 H15 V8 H9 Z M12 8 V12 M4 12 H20 M4 12 V16 M20 12 V16 M1 16 H7 V21 H1 Z M17 16"
                  + " H23 V21 H17 Z";
          case "globe" ->
              "M22 12 A10 10 0 1 1 2 12 A10 10 0 1 1 22 12 M2 12 H22 M12 2 C5 8 5 16 12 22 C19 16"
                  + " 19 8 12 2";
          case "arrows" -> "M4 7h15m-4-4 4 4-4 4M20 17H5m4-4-4 4 4 4";
          case "plus" -> "M12 5v14M5 12h14";
          case "close" -> "m6 6 12 12M6 18 18 6";
          case "edit" -> "m15 4 5 5-11 11H4v-5L15 4Zm-2 2 5 5";
          case "file" -> "M14 3H5v18h14V8l-5-5Zm0 0v5h5M8 12h8M8 16h6";
          case "link" ->
              "M9 15 L15 9 M8 16 L5 19 A4 4 0 0 1 1 15 L7 9 A4 4 0 0 1 13 9 M11 15 A4 4 0 0 0 17 15"
                  + " L23 9 A4 4 0 0 0 19 5 L16 8";
          case "warning" -> "m12 3 10 18H2L12 3Zm0 6v5m0 3h.01";
          case "back" -> "M19 12H5m6-6-6 6 6 6";
          case "more" -> "M5 12h.01M12 12h.01M19 12h.01";
          default -> throw new IllegalArgumentException("Unknown icon: " + name);
        };
    SVGPath drawing = new SVGPath();
    drawing.setContent(path);
    drawing.setFill(Color.TRANSPARENT);
    drawing.setStroke(Color.web("#7c8796"));
    drawing.setStrokeWidth(1.7);
    drawing.setStrokeLineCap(StrokeLineCap.ROUND);
    drawing.setStrokeLineJoin(StrokeLineJoin.ROUND);
    drawing.getTransforms().add(new Scale(.75, .75));
    drawing.getStyleClass().add("icon-path");
    Pane icon = new Pane(drawing);
    icon.setMinSize(18, 18);
    icon.setPrefSize(18, 18);
    icon.setMaxSize(18, 18);
    icon.setMouseTransparent(true);
    return icon;
  }
}
