package app.wisprail.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;

final class FxTestSupport {
  private FxTestSupport() {}

  static void initialize() throws Exception {
    if (System.getProperty("os.name").startsWith("Windows")
        && System.getProperty("glass.win.uiScale") == null) {
      System.setProperty("glass.win.uiScale", "1.0");
    }
    CompletableFuture<Void> started = new CompletableFuture<>();
    try {
      Platform.startup(
          () -> {
            Platform.setImplicitExit(false);
            started.complete(null);
          });
    } catch (IllegalStateException alreadyRunning) {
      Platform.runLater(() -> started.complete(null));
    }
    started.get(10, TimeUnit.SECONDS);
  }

  static void onFx(CheckedAction action) throws Exception {
    CompletableFuture<Void> completed = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          try {
            action.run();
            completed.complete(null);
          } catch (Throwable failure) {
            completed.completeExceptionally(failure);
          }
        });
    completed.get(20, TimeUnit.SECONDS);
  }

  static String text(Node node) {
    String result = node instanceof Labeled label ? label.getText() + "\n" : "";
    if (node instanceof Parent parent) {
      for (Node child : parent.getChildrenUnmodifiable()) {
        result += text(child);
      }
    }
    return result;
  }

  static Button button(Parent parent, String text) {
    parent.applyCss();
    return parent.lookupAll(".button").stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(button -> button.getText().equals(text) || text.equals(button.getAccessibleText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing button: " + text));
  }

  static void modal(Stage owner, String name, Runnable action) throws Exception {
    CompletableFuture<Void> captured = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          try {
            boolean shown =
                Window.getWindows().stream()
                    .anyMatch(window -> window != owner && window.isShowing());
            if (!shown) {
              throw new AssertionError("Expected a modal dialog: " + name);
            }
            for (Window window : Window.getWindows()) {
              if (window != owner && window.getHeight() > owner.getHeight()) {
                throw new AssertionError("Dialog exceeds the owner height: " + name);
              }
            }
            capture(owner, name);
            for (Window window : List.copyOf(Window.getWindows())) {
              if (window != owner && window.getScene().getRoot() instanceof DialogPane pane) {
                var cancel =
                    pane.getButtonTypes().stream()
                        .filter(type -> type.getButtonData().isCancelButton())
                        .findFirst();
                if (cancel.isPresent()) {
                  ((Button) pane.lookupButton(cancel.get())).fire();
                } else {
                  window.hide();
                }
              }
            }
            captured.complete(null);
          } catch (Throwable failure) {
            for (Window window : List.copyOf(Window.getWindows())) {
              if (window != owner) {
                window.hide();
              }
            }
            captured.completeExceptionally(failure);
          }
        });
    action.run();
    captured.get(3, TimeUnit.SECONDS);
  }

  static void capture(Stage stage, String name) throws Exception {
    Scene scene = stage.getScene();
    BufferedImage image = image(scene);
    var origin = scene.getRoot().localToScreen(0, 0);
    var graphics = image.createGraphics();
    try {
      for (Window window : List.copyOf(Window.getWindows())) {
        if (window != stage && window.isShowing() && window.getScene() != null) {
          var point = window.getScene().getRoot().localToScreen(0, 0);
          graphics.drawImage(
              image(window.getScene()),
              (int) Math.round(point.getX() - origin.getX()),
              (int) Math.round(point.getY() - origin.getY()),
              null);
        }
      }
    } finally {
      graphics.dispose();
    }
    Path directory = Path.of("target", "visual", "javafx");
    Files.createDirectories(directory);
    ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
  }

  private static BufferedImage image(Scene scene) {
    scene.getRoot().applyCss();
    scene.getRoot().layout();
    return image(scene.snapshot(null));
  }

  static void captureScreen(Stage stage, String name) throws Exception {
    var origin = stage.getScene().getRoot().localToScreen(0, 0);
    var snapshot =
        new Robot()
            .getScreenCapture(
                null,
                origin.getX(),
                origin.getY(),
                stage.getScene().getWidth(),
                stage.getScene().getHeight(),
                false);
    Path directory = Path.of("target", "visual", "javafx");
    Files.createDirectories(directory);
    ImageIO.write(image(snapshot), "png", directory.resolve(name + ".png").toFile());
  }

  private static BufferedImage image(WritableImage snapshot) {
    int width = (int) snapshot.getWidth();
    int height = (int) snapshot.getHeight();
    int[] pixels = new int[width * height];
    snapshot
        .getPixelReader()
        .getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
    BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    result.setRGB(0, 0, width, height, pixels, 0, width);
    return result;
  }

  @FunctionalInterface
  interface CheckedAction {
    void run() throws Exception;
  }
}
