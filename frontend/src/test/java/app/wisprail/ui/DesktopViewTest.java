package app.wisprail.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.PixelFormat;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DesktopViewTest {
  @BeforeAll
  static void startToolkit() throws Exception {
    FxTestSupport.initialize();
  }

  @Test
  void emptySelectedConnectedAndDeferredSettingsRenderInNativeJavaFx() throws Exception {
    onFx(
        () -> {
          DesktopModel model = new DesktopModel();
          Stage stage = new Stage();
          DesktopView view = new DesktopView(stage, model, false);
          ConnectionsPane pane = view.connections();
          Scene scene = new Scene(view, 1070, 700);
          scene.getStylesheets().add(getClass().getResource("wisprail.css").toExternalForm());
          stage.setScene(scene);
          stage.show();
          try {
            assertTrue(labels(pane).contains("Загружаем профили…"));
            model.ready.set(true);
            assertTrue(labels(pane).contains("Добавьте первый VPN"));
            VpnProfile first =
                profile("Рабочая сеть — длинное русское название подразделения", "10.20.0.0/16");
            VpnProfile second = profile("Тестовое подключение", "10.30.0.0/16");
            model.profiles.setAll(first, second);
            model.ready.set(true);
            model.online.set(true);
            model.connection.set(
                new ConnectionSnapshot(
                    ConnectionSnapshot.State.CONNECTED,
                    1,
                    null,
                    first,
                    null,
                    "",
                    "",
                    "",
                    null,
                    Instant.now()));
            pane.select(second);
            assertTrue(labels(pane).contains("При переключении он будет отключён"));
            snapshot(scene, "selected-other.png");
            VpnProfile changed =
                new VpnProfile(
                    1,
                    first.id(),
                    2,
                    first.name(),
                    first.server(),
                    first.port(),
                    first.protocol(),
                    first.openVpn(),
                    first.vless(),
                    List.of("10.99.0.0/16"),
                    first.dns(),
                    first.probeUrl(),
                    "");
            model.profiles.setAll(changed, second);
            pane.select(changed);
            String text = labels(pane);
            assertTrue(text.contains("Есть сохранённые изменения"));
            assertTrue(text.contains("10.20.0.0/16"));
            assertFalse(text.contains("10.99.0.0/16"));
            snapshot(scene, "deferred-settings.png");
            stage.hide();
            DesktopView narrowView = new DesktopView(stage, model, false);
            ConnectionsPane narrowPane = narrowView.connections();
            narrowPane.select(changed);
            Scene narrow = new Scene(narrowView, 860, 640);
            narrow.getStylesheets().add(getClass().getResource("wisprail.css").toExternalForm());
            stage.setScene(narrow);
            stage.sizeToScene();
            stage.show();
            assertEquals(860, narrow.getWidth());
            snapshot(narrow, "minimum-window.png");
          } finally {
            stage.close();
          }
        });
  }

  @Test
  void invalidDraftStaysOpenAndDisplaysValidation() throws Exception {
    onFx(
        () -> {
          Stage owner = new Stage();
          owner.setScene(new Scene(new VBox(), 300, 200));
          owner.show();
          ProfileEditor editor =
              new ProfileEditor(
                  owner,
                  new DesktopModel(),
                  ProfileEditor.blank(VpnProfile.Protocol.VLESS),
                  ProfileSecrets.empty(),
                  (profile, secrets) -> {
                    throw new AssertionError("Invalid profile reached engine");
                  },
                  result -> {
                    throw new AssertionError("Invalid profile was saved");
                  },
                  () -> {});
          Scene scene = new Scene(editor, 800, 620);
          scene.getStylesheets().add(getClass().getResource("wisprail.css").toExternalForm());
          owner.setScene(scene);
          try {
            owner.getScene().getRoot().applyCss();
            ((Button) editor.lookup("#save-profile")).fire();
            assertTrue(owner.isShowing());
            assertTrue(labels(editor).contains("Название должно"));
            snapshot(scene, "editor-validation.png");
          } finally {
            owner.close();
          }
        });
  }

  private static String labels(Node node) {
    String result = node instanceof Label label ? label.getText() + "\n" : "";
    if (node instanceof Parent parent) {
      for (Node child : parent.getChildrenUnmodifiable()) {
        result += labels(child);
      }
    }
    return result;
  }

  private static void snapshot(Scene scene, String name) throws Exception {
    scene.getRoot().applyCss();
    scene.getRoot().layout();
    var image = scene.snapshot(null);
    int width = (int) image.getWidth();
    int height = (int) image.getHeight();
    int[] pixels = new int[width * height];
    image
        .getPixelReader()
        .getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
    BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    output.setRGB(0, 0, width, height, pixels, 0, width);
    Path directory = Path.of("target", "ui-evidence");
    Files.createDirectories(directory);
    ImageIO.write(output, "png", directory.resolve(name).toFile());
  }

  private static VpnProfile profile(String name, String network) {
    return new VpnProfile(
        1,
        UUID.randomUUID(),
        1,
        name,
        "vpn.example.com",
        443,
        VpnProfile.Protocol.VLESS,
        null,
        new VpnProfile.Vless(VpnProfile.Security.TLS, "vpn.example.com", "", "", "chrome", ""),
        List.of(network),
        VpnProfile.Dns.empty(),
        "https://10.20.0.1/",
        "");
  }

  private static void onFx(CheckedAction action) throws Exception {
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

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }
}
