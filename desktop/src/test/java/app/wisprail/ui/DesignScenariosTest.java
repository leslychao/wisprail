package app.wisprail.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.EventJournal;
import app.wisprail.connection.NetworkRuntime;
import app.wisprail.profile.ProfileImport;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** UI fixtures never initialize IPC, install a service or simulate success in the application. */
class DesignScenariosTest {
  private static final Instant TIME = Instant.parse("2026-09-30T08:00:00Z");
  private static final ProfileSecrets SECRETS =
      new ProfileSecrets("", "", "fixture CA", "", "", "");
  private static final List<String> SCENARIOS =
      List.of(
          "connected",
          "disconnected",
          "connecting",
          "switch",
          "disconnecting",
          "error",
          "empty",
          "add",
          "import",
          "editor",
          "access",
          "networks",
          "dns",
          "dns-warning",
          "active-save",
          "auth",
          "permission",
          "unsupported",
          "journal",
          "diagnostics",
          "settings",
          "components",
          "delete",
          "export",
          "cleanup",
          "tray",
          "exit",
          "vless-main",
          "vless-access",
          "add-file",
          "add-link",
          "import-error",
          "network-form",
          "domain-form",
          "validation",
          "unsaved",
          "log-detail",
          "diagnostics-done",
          "profile-menu",
          "report",
          "about");

  @BeforeAll
  static void toolkit() throws Exception {
    FxTestSupport.initialize();
  }

  @TestFactory
  Stream<DynamicTest> designStates() {
    return SCENARIOS.stream()
        .map(name -> DynamicTest.dynamicTest(name, () -> FxTestSupport.onFx(() -> render(name))));
  }

  private static void render(String name) throws Exception {
    DesktopModel model = new DesktopModel();
    VpnProfile first = profile("RGF Moscow", false);
    VpnProfile second = profile("RGF Test", false);
    VpnProfile personal = profile("Personal", true);
    model.profiles.setAll(first, second, personal);
    model.ready.set(true);
    model.online.set(true);
    model.serviceMessage.set("");
    model.connection.set(snapshot(ConnectionSnapshot.State.CONNECTED, first, null, "", ""));
    model.events.addAll(
        new EventJournal.Event(1, TIME, "info", first.id(), "CONNECTED", "VPN подключён"),
        new EventJournal.Event(
            2, TIME, "warning", first.id(), "DNS", "Проверка DNS ещё не выполнялась"));
    Stage stage = new Stage();
    DesktopView view = new DesktopView(stage, model, true);
    Scene scene = new Scene(view, 1070, 700);
    scene
        .getStylesheets()
        .add(DesignScenariosTest.class.getResource("wisprail.css").toExternalForm());
    stage.setScene(scene);
    stage.setX(100);
    stage.setY(80);
    stage.show();
    view.connections().select(first);
    ProfileActions actions = view.connections().actions();
    try {
      switch (name) {
        case "disconnected" -> model.connection.set(ConnectionSnapshot.initial());
        case "connecting" ->
            model.connection.set(
                snapshot(
                    ConnectionSnapshot.State.CONNECTING,
                    null,
                    first,
                    "Ожидаем готовности VPN",
                    ""));
        case "switch" -> {
          view.connections().select(second);
          assertEquals(first, model.connection.get().appliedProfile());
          assertTrue(FxTestSupport.text(view).contains("При переключении он будет отключён"));
        }
        case "disconnecting" ->
            model.connection.set(
                snapshot(
                    ConnectionSnapshot.State.DISCONNECTING,
                    first,
                    first,
                    "Останавливаем VPN и убираем его настройки",
                    ""));
        case "error" ->
            model.connection.set(
                snapshot(ConnectionSnapshot.State.ERROR, null, first, "", "TIMEOUT"));
        case "cleanup" ->
            model.connection.set(
                snapshot(ConnectionSnapshot.State.ERROR, null, first, "", "CLEANUP_FAILED"));
        case "empty" -> {
          model.connection.set(ConnectionSnapshot.initial());
          model.profiles.clear();
        }
        case "unsupported" -> {
          model.online.set(false);
          model.connection.set(ConnectionSnapshot.initial());
          model.serviceMessage.set(
              "Сетевой компонент недоступен. Профили сохранены; восстановите компонент в"
                  + " настройках.");
        }
        case "editor",
            "access",
            "networks",
            "dns",
            "dns-warning",
            "network-form",
            "domain-form",
            "unsaved",
            "active-save" -> {
          String tab =
              switch (name) {
                case "access" -> "Доступ";
                case "networks", "network-form", "active-save" -> "Сети";
                case "dns", "dns-warning", "domain-form" -> "DNS";
                default -> "Основное";
              };
          actions.editor(first, SECRETS, tab);
          view.applyCss();
          view.layout();
          if (name.equals("dns-warning")) {
            ((TextField) view.lookup("#profile-dns")).setText("10.80.0.53");
          }
        }
        case "vless-main", "vless-access" ->
            actions.editor(
                personal,
                new ProfileSecrets("", "11111111-1111-4111-8111-111111111111", "", "", "", ""),
                name.equals("vless-main") ? "Основное" : "Доступ");
        case "validation" -> {
          actions.editor(first, SECRETS, "Основное");
          model.online.set(false);
        }
        case "journal", "log-detail" -> view.journal(first, false, false);
        case "diagnostics", "diagnostics-done", "report" -> {
          view.journal(first, true, false);
          if (name.equals("diagnostics-done")) {
            find(view, JournalPane.class)
                .displayChecks(
                    List.of(
                        new NetworkRuntime.Check("Сетевой компонент", "PASS", "sing-box 1.14.2"),
                        new NetworkRuntime.Check("Конфигурация", "PASS", "Конфигурация принята"),
                        new NetworkRuntime.Check(
                            "VPN и маршруты", "NOT_RUN", "Сетевое испытание не проводилось"),
                        new NetworkRuntime.Check(
                            "DNS", "NOT_RUN", "Сетевое испытание не проводилось")));
          }
        }
        case "settings", "components", "permission", "about" -> {
          click(view, "Настройки");
          if (!name.equals("settings")) {
            find(view, TabPane.class).getSelectionModel().select(1);
          }
        }
        case "tray" -> {
          Popup popup = new Popup();
          popup
              .getContent()
              .add(TrayController.content(model, view.connections(), () -> {}, () -> {}));
          var origin = view.localToScreen(0, 0);
          popup.show(stage, origin.getX() + 724, origin.getY() + 250);
        }
        default -> {}
      }
      view.applyCss();
      view.layout();
      boolean modal = true;
      switch (name) {
        case "validation" ->
            FxTestSupport.modal(stage, name, () -> click(view, "Проверить настройки"));
        case "add" -> FxTestSupport.modal(stage, name, actions::create);
        case "add-file" -> FxTestSupport.modal(stage, name, actions::showFileImport);
        case "import" ->
            FxTestSupport.modal(
                stage,
                name,
                () ->
                    actions.preview(
                        new ProfileImport.Preview(
                            second,
                            SECRETS,
                            List.of(
                                "redirect-gateway не будет применён: весь интернет остаётся по"
                                    + " настройкам системы."))));
        case "active-save" -> {
          ((TextField) view.lookup("#profile-server")).setText("new.example.com");
          FxTestSupport.modal(stage, name, () -> FxTestSupport.button(view, "Сохранить").fire());
          assertEquals(first, model.profiles.getFirst());
          assertEquals(first, model.connection.get().appliedProfile());
          assertNotNull(view.lookup("#save-profile"));
        }
        case "auth" ->
            FxTestSupport.modal(stage, name, () -> actions.credentials(first).showAndWait());
        case "permission" ->
            FxTestSupport.modal(
                stage, name, () -> click(view, "Установить / разрешить системный компонент"));
        case "delete" -> FxTestSupport.modal(stage, name, () -> actions.delete(first));
        case "export" -> FxTestSupport.modal(stage, name, () -> actions.export(first));
        case "exit" ->
            FxTestSupport.modal(
                stage, name, () -> Ui.exitDialog(stage, first.name(), true).showAndWait());
        case "add-link" -> FxTestSupport.modal(stage, name, actions::importLink);
        case "import-error" ->
            FxTestSupport.modal(
                stage,
                name,
                () -> actions.importError("Не поддерживается параметр plugin. Импорт остановлен."));
        case "network-form" ->
            FxTestSupport.modal(stage, name, () -> click(view, "+ Добавить сеть"));
        case "domain-form" ->
            FxTestSupport.modal(stage, name, () -> click(view, "+ Добавить домен"));
        case "unsaved" -> {
          ((TextField) view.lookup("#profile-name")).setText("Новое название");
          FxTestSupport.modal(stage, name, () -> click(view, "Журнал"));
          assertNotNull(view.lookup("#save-profile"));
        }
        case "report" ->
            FxTestSupport.modal(stage, name, () -> click(view, "Предпросмотр отчёта…"));
        case "about" -> FxTestSupport.modal(stage, name, () -> click(view, "О программе"));
        default -> modal = false;
      }
      if (!modal) {
        if (name.equals("log-detail")) {
          find(view, TableView.class).getSelectionModel().select(0);
        }
        if (name.equals("profile-menu")) {
          find(view, MenuButton.class).show();
        }
        FxTestSupport.capture(stage, name);
      }
      if (name.equals("cleanup")) {
        assertFalse(FxTestSupport.text(view).contains("Повторить"));
      }
    } finally {
      view.dispose();
      for (var window : List.copyOf(Window.getWindows())) {
        if (window != stage) {
          window.hide();
        }
      }
      stage.close();
    }
  }

  @Test
  void searchAppearsAtSevenProfilesAndSelectionDoesNotDisconnect() throws Exception {
    FxTestSupport.onFx(
        () -> {
          DesktopModel model = new DesktopModel();
          VpnProfile first = profile("Рабочий", false);
          model.profiles.add(first);
          model.connection.set(snapshot(ConnectionSnapshot.State.CONNECTED, first, null, "", ""));
          Stage stage = new Stage();
          DesktopView view = new DesktopView(stage, model, false);
          stage.setScene(new Scene(view, 860, 640));
          stage.show();
          try {
            TextField search = find(view, TextField.class);
            assertFalse(search.isVisible());
            for (int index = 0; index < 6; index++) {
              model.profiles.add(profile("Тест " + index, false));
            }
            assertTrue(search.isVisible());
            search.setText("Тест 5");
            assertEquals(first, model.connection.get().appliedProfile());
            search.setText("Нет такого профиля");
            assertFalse(FxTestSupport.text(view).contains("Добавьте первый VPN"));
            assertTrue(FxTestSupport.text(view).contains("измените запрос"));
          } finally {
            view.dispose();
            stage.close();
          }
        });
  }

  private static void click(Parent root, String text) {
    root.applyCss();
    root.lookupAll("*").stream()
        .filter(ButtonBase.class::isInstance)
        .map(ButtonBase.class::cast)
        .filter(button -> button.getText().equals(text))
        .findFirst()
        .orElseThrow()
        .fire();
  }

  private static <T> T find(Parent root, Class<T> type) {
    root.applyCss();
    return root.lookupAll("*").stream()
        .filter(type::isInstance)
        .map(type::cast)
        .findFirst()
        .orElseThrow();
  }

  private static ConnectionSnapshot snapshot(
      ConnectionSnapshot.State state,
      VpnProfile applied,
      VpnProfile target,
      String stage,
      String error) {
    boolean busy =
        state == ConnectionSnapshot.State.CONNECTING
            || state == ConnectionSnapshot.State.DISCONNECTING;
    String message = error.isEmpty() ? "" : "Сервер не ответил вовремя.";
    if (error.equals("CLEANUP_FAILED")) {
      message = "VPN остановлен, но не удалось подтвердить удаление его маршрутов.";
    }
    return new ConnectionSnapshot(
        state,
        1,
        busy ? UUID.randomUUID() : null,
        applied,
        target,
        stage,
        error,
        message,
        null,
        TIME);
  }

  private static VpnProfile profile(String name, boolean vless) {
    return new VpnProfile(
        1,
        UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),
        1,
        name,
        "vpn.example.com",
        vless ? 443 : 1194,
        vless ? VpnProfile.Protocol.VLESS : VpnProfile.Protocol.OPENVPN,
        vless ? null : new VpnProfile.OpenVpn("udp", "employee", "vpn.example.com", "", "", "", ""),
        vless ? VpnProfile.Vless.defaults() : null,
        List.of("10.20.0.0/16", "10.30.0.0/16"),
        new VpnProfile.Dns("10.20.0.53", List.of("company.local", "corp.local")),
        "",
        "");
  }
}
