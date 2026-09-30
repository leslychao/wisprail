package app.wisprail.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.ptr.IntByReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Requires an interactive desktop: it sends real keys to an owned window, never to other apps. */
@EnabledIfSystemProperty(named = "wisprail.nativeUi", matches = "true")
class DesktopKeyboardTest {
  private Stage stage;
  private ProfileEditor editor;

  @Test
  void realKeyboardFocusValidationAndScreenAtConfiguredScale() throws Exception {
    FxTestSupport.initialize();
    try {
      FxTestSupport.onFx(
          () -> {
            stage = new Stage();
            stage.setTitle("Wisprail keyboard acceptance " + ProcessHandle.current().pid());
            editor =
                new ProfileEditor(
                    stage,
                    new DesktopModel(),
                    ProfileEditor.blank(VpnProfile.Protocol.VLESS),
                    ProfileSecrets.empty(),
                    (profile, secrets) -> {
                      throw new AssertionError("Invalid settings reached IPC");
                    },
                    result -> {
                      throw new AssertionError("Invalid settings saved");
                    },
                    () -> {
                      if (editor.canLeave()) {
                        stage.close();
                      }
                    });
            Scene scene = new Scene(editor, 860, 640);
            scene
                .getStylesheets()
                .add(
                    Objects.requireNonNull(getClass().getResource("wisprail.css"))
                        .toExternalForm());
            stage.setScene(scene);
            stage.setX(60);
            stage.setY(60);
            stage.show();
            stage.toFront();
          });
      pulse();
      FxTestSupport.onFx(
          () -> {
            assertTrue(stage.isFocused(), "The test must own keyboard focus");
            editor.lookup("#profile-name").requestFocus();
          });
      press(KeyCode.TAB);
      FxTestSupport.onFx(
          () -> assertEquals("profile-server", stage.getScene().getFocusOwner().getId()));
      press(KeyCode.SHIFT, KeyCode.TAB);
      FxTestSupport.onFx(
          () -> assertEquals("profile-name", stage.getScene().getFocusOwner().getId()));
      press(KeyCode.ENTER);
      FxTestSupport.onFx(
          () -> {
            assertNotNull(editor.lookup(".invalid-field"));
            assertTrue(FxTestSupport.text(editor).contains("Название должно"));
            ((TextField) editor.lookup("#profile-name"))
                .setText("Очень длинное русское название корпоративного подключения подразделения");
            editor.selectTab("Доступ");
          });
      pulse();
      FxTestSupport.onFx(
          () -> {
            String scale = System.getProperty("glass.win.uiScale", "1.0");
            FxTestSupport.captureScreen(stage, "native-editor-" + scale);
            Path output = Path.of("target", "visual", "javafx", "native-scale-" + scale + ".txt");
            Files.writeString(
                output,
                "Window output scale="
                    + stage.getOutputScaleX()
                    + "\nClient="
                    + stage.getScene().getWidth()
                    + "x"
                    + stage.getScene().getHeight()
                    + "\nTab, Shift+Tab, Enter: PASS; toolkit scaling is not an OS DPI setting.\n");
          });
      press(KeyCode.ESCAPE);
      FxTestSupport.onFx(
          () -> {
            Window dialog =
                Window.getWindows().stream()
                    .filter(
                        window ->
                            window != stage && window.getScene().getRoot() instanceof DialogPane)
                    .findFirst()
                    .orElseThrow();
            assertTrue(dialog.isFocused());
            FxTestSupport.captureScreen(
                stage, "native-unsaved-" + System.getProperty("glass.win.uiScale", "1.0"));
            Robot robot = new Robot();
            robot.keyPress(KeyCode.ESCAPE);
            robot.keyRelease(KeyCode.ESCAPE);
          });
      pulse();
      FxTestSupport.onFx(
          () -> assertTrue(stage.isShowing(), "Esc in the warning must preserve the draft"));
    } finally {
      FxTestSupport.onFx(
          () -> {
            if (stage != null) {
              stage.close();
            }
          });
    }
  }

  private void press(KeyCode... keys) throws Exception {
    FxTestSupport.onFx(
        () -> {
          assertTrue(stage.isFocused(), "Refuse to send keys to an unrelated window");
          if (System.getProperty("os.name").startsWith("Windows")) {
            IntByReference process = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(
                User32.INSTANCE.GetForegroundWindow(), process);
            assertEquals(
                ProcessHandle.current().pid(),
                Integer.toUnsignedLong(process.getValue()),
                "The native foreground window must belong to this test process");
          }
          Robot robot = new Robot();
          for (KeyCode key : keys) {
            robot.keyPress(key);
          }
          for (int index = keys.length - 1; index >= 0; index--) {
            robot.keyRelease(keys[index]);
          }
        });
    pulse();
  }

  private static void pulse() throws InterruptedException {
    Thread.sleep(250);
  }
}
