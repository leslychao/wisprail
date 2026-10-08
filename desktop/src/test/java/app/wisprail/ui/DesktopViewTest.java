package app.wisprail.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.wisprail.connection.AddressAnalysis;
import app.wisprail.connection.CheckStatus;
import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.ConnectionState;
import app.wisprail.connection.DiagnosticCheck;
import app.wisprail.connection.DiagnosticReport;
import app.wisprail.connection.PreparedConnection;
import app.wisprail.profile.ImportResult;
import app.wisprail.profile.OpenVpnSettings;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileRepository;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.TlsMode;
import app.wisprail.profile.VlessSettings;
import app.wisprail.profile.VpnType;
import app.wisprail.storage.SecretStore;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real JavaFX scene/keyboard checks. These fixtures never connect to a system service or VPN. */
class DesktopViewTest {
  private static Stage stage;
  private static final Profile MOSCOW = profile("RGF Moscow", "10.20.0.0/16", "10.20.0.53");
  private static final Profile SECOND = profile("RGF Test", "10.40.0.0/16", "10.40.0.53");
  @TempDir Path temporary;

  @BeforeAll
  static void toolkit() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          stage = new Stage();
          stage.setTitle("Wisprail — UI verification");
          ready.countDown();
        });
    assertTrue(ready.await(20, TimeUnit.SECONDS), "JavaFX graphical session did not start");
  }

  @AfterAll
  static void stopToolkit() throws Exception {
    fx(
        () -> {
          stage.close();
          return null;
        });
    Platform.exit();
  }

  @Test
  void choosingAnotherProfileDoesNotSendAnyNetworkCommand() throws Exception {
    fx(
        () -> {
          List<ConnectionsView.Action> operations = new ArrayList<>();
          ConnectionsView view = new ConnectionsView(profile -> {}, operations::add);
          view.setProfiles(List.of(MOSCOW, SECOND), MOSCOW.id());
          view.update(connected(MOSCOW));
          show(view, 1070, 700);
          view.select(SECOND.id());
          assertTrue(operations.isEmpty());
          assertEquals(SECOND, view.selected());
          assertTrue(text(view).contains("Сейчас работает RGF Moscow"));
          button(view, "Переключиться").fire();
          assertEquals(List.of(ConnectionsView.Action.CONNECT), operations);
          return null;
        });
  }

  @Test
  void deferredChangesShowAppliedNetworksAndDnsUntilExplicitReconnect() throws Exception {
    fx(
        () -> {
          Profile saved =
              new Profile(
                  MOSCOW.id(),
                  2,
                  MOSCOW.name(),
                  MOSCOW.server(),
                  MOSCOW.port(),
                  MOSCOW.settings(),
                  List.of("10.80.0.0/16"),
                  "10.80.0.53",
                  List.of("new.example"),
                  "",
                  "");
          List<ConnectionsView.Action> actions = new ArrayList<>();
          ConnectionsView view = new ConnectionsView(profile -> {}, actions::add);
          view.setProfiles(List.of(saved), saved.id());
          view.update(connected(MOSCOW));
          show(view, 1070, 700);
          String displayed = text(view);
          assertTrue(displayed.contains("Есть сохранённые изменения"));
          assertTrue(displayed.contains("10.20.0.53"));
          assertFalse(displayed.contains("10.80.0.53"));
          assertTrue(actions.isEmpty());
          button(view, "Переподключить и применить").fire();
          assertEquals(List.of(ConnectionsView.Action.APPLY), actions);
          return null;
        });
  }

  @Test
  void pendingAttemptHasCancelInsteadOfAnotherConnectAndCleanupHasNoRetryConnect()
      throws Exception {
    fx(
        () -> {
          List<ConnectionsView.Action> actions = new ArrayList<>();
          ConnectionsView view = new ConnectionsView(profile -> {}, actions::add);
          view.setProfiles(List.of(MOSCOW), MOSCOW.id());
          view.update(
              new ConnectionSnapshot(
                  2,
                  ConnectionState.CONNECTING,
                  UUID.randomUUID(),
                  null,
                  MOSCOW.id(),
                  false,
                  false,
                  "Ожидаем готовности VPN"));
          show(view, 1070, 700);
          assertFalse(buttons(view).anyMatch(button -> button.getText().equals("Подключить")));
          button(view, "Отменить").fire();
          assertEquals(List.of(ConnectionsView.Action.CANCEL), actions);
          view.update(
              new ConnectionSnapshot(
                  3,
                  ConnectionState.ERROR,
                  null,
                  null,
                  MOSCOW.id(),
                  false,
                  true,
                  "Не удалось подтвердить удаление собственных маршрутов"));
          assertTrue(text(view).contains("Требуется проверка"));
          assertFalse(buttons(view).anyMatch(button -> button.getText().equals("Подключить")));
          button(view, "Проверить очистку").fire();
          assertEquals(ConnectionsView.Action.CLEANUP, actions.getLast());
          return null;
        });
  }

  @Test
  void draftAndTabNavigationPreserveInputWithoutChangingSavedProfile() throws Exception {
    fx(
        () -> {
          AtomicInteger writes = new AtomicInteger();
          ProfileEditor editor = editor(MOSCOW, writes);
          show(editor, 1070, 700);
          TextField server =
              nodes(editor)
                  .filter(TextField.class::isInstance)
                  .map(TextField.class::cast)
                  .filter(field -> field.getText().equals(MOSCOW.server()))
                  .findFirst()
                  .orElseThrow();
          server.setText("changed.example");
          editor.putNetwork("", "10.30.0.0/16");
          editor.showTab(3);
          editor.showTab(0);
          assertEquals("changed.example", editor.draft().profile().server());
          assertEquals(List.of("10.20.0.0/16"), MOSCOW.networks());
          assertEquals(0, writes.get());
          assertTrue(editor.isDirty());
          return null;
        });
  }

  @Test
  void corporateDnsRouteRequiresTheExplicitAddButton() throws Exception {
    fx(
        () -> {
          Profile outside =
              new Profile(
                  MOSCOW.id(),
                  1,
                  MOSCOW.name(),
                  MOSCOW.server(),
                  MOSCOW.port(),
                  MOSCOW.settings(),
                  MOSCOW.networks(),
                  "10.80.0.53",
                  MOSCOW.domains(),
                  "",
                  "");
          ProfileEditor editor = editor(outside, new AtomicInteger());
          editor.showTab(3);
          show(editor, 1070, 700);
          assertEquals(List.of("10.20.0.0/16"), editor.draft().profile().networks());
          button(editor, "Добавить 10.80.0.53/32 в сети").fire();
          assertTrue(editor.draft().profile().networks().contains("10.80.0.53/32"));
          assertTrue(text(editor).contains("Его доступность ещё не проверена"));
          return null;
        });
  }

  @Test
  void realityHasStructuredFieldsAndUuidIsMaskedByDefault() throws Exception {
    fx(
        () -> {
          Profile reality =
              new Profile(
                  UUID.randomUUID(),
                  0,
                  "Personal",
                  "vpn.example",
                  443,
                  new VlessSettings(
                      TlsMode.REALITY, "vpn.example", "public-key", "ab", "chrome", ""),
                  MOSCOW.networks(),
                  MOSCOW.dns(),
                  MOSCOW.domains(),
                  "",
                  "");
          ProfileEditor editor = editor(reality, new AtomicInteger());
          editor.showTab(1);
          show(editor, 1070, 700);
          assertTrue(text(editor).contains("Открытый ключ Reality"));
          assertTrue(text(editor).contains("Short ID"));
          assertTrue(nodes(editor).anyMatch(PasswordField.class::isInstance));
          assertFalse(text(editor).contains("Сертификат пользователя"));
          return null;
        });
  }

  @Test
  void diagnosisKeepsUncheckedAndFailedResultsDistinctFromPassed() throws Exception {
    fx(
        () -> {
          JournalView journal =
              new JournalView(
                  profile -> {}, (profile, address) -> {}, report -> {}, new Dialogs(stage));
          try {
            journal.setProfiles(List.of(MOSCOW), MOSCOW);
            journal.showDiagnostics(MOSCOW);
            show(journal, 1070, 700);
            assertTrue(text(journal).contains("Не проверено"));
            assertFalse(text(journal).contains("Подтверждено"));
            journal.report(
                new DiagnosticReport(
                    MOSCOW.id(),
                    Instant.now(),
                    List.of(
                        new DiagnosticCheck("Компонент", CheckStatus.PASS, "Доступен"),
                        new DiagnosticCheck("DNS", CheckStatus.FAIL, "Ответ не получен"),
                        new DiagnosticCheck("Маршруты", CheckStatus.NOT_RUN, "Профиль отключён"))));
            assertTrue(text(journal).contains("✓ Подтверждено"));
            assertTrue(text(journal).contains("✕ Ошибка"));
            assertTrue(text(journal).contains("Не проверено"));
          } finally {
            journal.close();
          }
          return null;
        });
  }

  @Test
  void lateAddressResultCannotReplaceAnotherProfileOrChangedInput() throws Exception {
    fx(
        () -> {
          JournalView journal =
              new JournalView(
                  profile -> {}, (profile, address) -> {}, report -> {}, new Dialogs(stage));
          try {
            journal.setProfiles(List.of(MOSCOW, SECOND), MOSCOW);
            journal.showDiagnostics(MOSCOW);
            show(journal, 1070, 700);
            TextField address = field(journal, "address", TextField.class);
            address.setText("old.company.local");
            AddressAnalysis late =
                new AddressAnalysis(
                    "old.company.local",
                    "",
                    List.of(),
                    List.of(),
                    false,
                    "Late result must not replace current context");
            journal.showDiagnostics(SECOND);
            journal.analysis(MOSCOW.id(), "old.company.local", late);
            assertFalse(text(journal).contains(late.detail()));
            address.setText("new.company.local");
            journal.analysis(SECOND.id(), "old.company.local", late);
            assertFalse(text(journal).contains(late.detail()));
            journal.analysis(
                SECOND.id(),
                "new.company.local",
                new AddressAnalysis(
                    "new.company.local", "", List.of(), List.of(), false, "Актуальные настройки"));
            assertTrue(text(journal).contains("Актуальные настройки"));
          } finally {
            journal.close();
          }
          return null;
        });
  }

  @Test
  void scaledMinimumWindowKeepsLongProfilesAndControlsReachable() throws Exception {
    AtomicInteger actions = new AtomicInteger();
    ConnectionsView view =
        fx(
            () -> {
              List<Profile> many = new ArrayList<>();
              for (int index = 0; index < 300; index++) {
                many.add(
                    profile(
                        "Очень длинное название корпоративного подключения " + index,
                        "10.20.0.0/16",
                        "10.20.0.53"));
              }
              ConnectionsView result =
                  new ConnectionsView(profile -> {}, action -> actions.incrementAndGet());
              result.setProfiles(many, many.getFirst().id());
              show(result, 1070, 700);
              stage.setWidth(860);
              stage.setHeight(640);
              return result;
            });
    Thread.sleep(100);
    fx(
        () -> {
          String configuredScale = System.getProperty("glass.win.uiScale");
          if (configuredScale != null) {
            double expectedScale = Double.parseDouble(configuredScale);
            assertEquals(
                expectedScale,
                stage.getOutputScaleX(),
                0.01,
                "The actual JavaFX output scale must match the requested test scale");
            assertEquals(expectedScale, stage.getOutputScaleY(), 0.01);
          }
          ListView<?> list =
              nodes(view)
                  .filter(ListView.class::isInstance)
                  .map(ListView.class::cast)
                  .findFirst()
                  .orElseThrow();
          assertEquals(300, list.getItems().size());
          assertTrue(list.lookupAll(".list-cell").size() < 40, "Profiles must remain virtualized");
          assertTrue(
              list.lookupAll(".scroll-bar").stream()
                  .filter(ScrollBar.class::isInstance)
                  .map(ScrollBar.class::cast)
                  .filter(bar -> bar.getOrientation() == Orientation.HORIZONTAL)
                  .noneMatch(Node::isVisible),
              "Long profile names must not add horizontal scrolling");
          Button edit = button(view, "Изменить");
          assertTrue(edit.getWidth() >= edit.prefWidth(-1) - 1, "Edit must keep its full label");
          assertInsideScene(edit);
          capture(stage, "long-profiles-minimum");
          TextField search =
              nodes(view)
                  .filter(TextField.class::isInstance)
                  .map(TextField.class::cast)
                  .filter(field -> "Поиск VPN".equals(field.getAccessibleText()))
                  .findFirst()
                  .orElseThrow();
          search.setText("299");
          view.applyCss();
          view.layout();
          assertEquals(1, list.getItems().size());
          list.getSelectionModel().selectFirst();
          assertTrue(view.selected().name().endsWith("299"));
          Button connect = button(view, "Подключить");
          assertInsideScene(connect);
          connect.requestFocus();
          connect.fire();
          assertEquals(1, actions.get());
          return null;
        });
    fx(
        () -> {
          button(view, "Подключить").requestFocus();
          Node focused = stage.getScene().getFocusOwner();
          assertEquals(button(view, "Подключить"), focused);
          assertTrue(
              focused.isFocusTraversable(), "The action must participate in keyboard traversal");
          assertInsideScene(focused);
          capture(stage, "long-profiles-keyboard-focus");
          return null;
        });
  }

  private static void assertInsideScene(Node node) {
    var bounds = node.localToScene(node.getBoundsInLocal());
    assertTrue(bounds.getWidth() > 20 && bounds.getHeight() > 12);
    assertTrue(bounds.getMinX() >= 0 && bounds.getMaxX() <= node.getScene().getWidth() + 1);
    assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= node.getScene().getHeight() + 1);
  }

  @Test
  void keyboardTabMovesFocusBetweenRealControls() throws Exception {
    TextField first =
        fx(
            () -> {
              TextField firstField = new TextField();
              TextField secondField = new TextField();
              show(
                  new VBox(
                      20,
                      Ui.field("Первое поле", firstField, ""),
                      Ui.field("Второе поле", secondField, "")),
                  860,
                  640);
              firstField.requestFocus();
              return firstField;
            });
    focusWindow(stage);
    fx(
        () -> {
          first.requestFocus();
          return null;
        });
    assumeTrue(
        pressKey(stage, KeyCode.TAB),
        "NOT_RUN: native keyboard injection is unavailable; see target/visual/native-input.txt");
    awaitFx(() -> stage.getScene().getFocusOwner() != first, "Tab must move between text fields");
    fx(
        () -> {
          assertNotEquals(first, stage.getScene().getFocusOwner());
          assertTrue(stage.getScene().getFocusOwner() instanceof TextField);
          return null;
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "connected",
        "disconnected",
        "connecting",
        "switch",
        "disconnecting",
        "error",
        "empty",
        "editor",
        "access",
        "networks",
        "dns",
        "dns-warning",
        "unsupported",
        "journal",
        "diagnostics",
        "cleanup",
        "vless-main",
        "vless-access",
        "diagnostics-done"
      })
  void renderPrincipalDesignStatesAndSaveSceneEvidence(String scenario) throws Exception {
    Rendered rendering =
        fx(
            () -> {
              Parent content;
              JournalView journal = null;
              if (List.of(
                      "editor",
                      "access",
                      "networks",
                      "dns",
                      "dns-warning",
                      "vless-main",
                      "vless-access")
                  .contains(scenario)) {
                Profile profile =
                    scenario.startsWith("vless")
                        ? vless()
                        : scenario.equals("dns-warning")
                            ? new Profile(
                                MOSCOW.id(),
                                1,
                                MOSCOW.name(),
                                MOSCOW.server(),
                                MOSCOW.port(),
                                MOSCOW.settings(),
                                MOSCOW.networks(),
                                "10.80.0.53",
                                MOSCOW.domains(),
                                "",
                                "")
                            : MOSCOW;
                ProfileEditor editor = editor(profile, new AtomicInteger());
                editor.showTab(
                    switch (scenario) {
                      case "access", "vless-access" -> 1;
                      case "networks" -> 2;
                      case "dns", "dns-warning" -> 3;
                      default -> 0;
                    });
                content = editor;
              } else if (List.of("journal", "diagnostics", "diagnostics-done").contains(scenario)) {
                journal =
                    new JournalView(
                        profile -> {}, (profile, address) -> {}, report -> {}, new Dialogs(stage));
                journal.setProfiles(List.of(MOSCOW), MOSCOW);
                if (!scenario.equals("journal")) {
                  journal.showDiagnostics(MOSCOW);
                }
                if (scenario.equals("diagnostics-done")) {
                  journal.report(
                      new DiagnosticReport(
                          MOSCOW.id(),
                          Instant.now(),
                          List.of(
                              new DiagnosticCheck("Компонент", CheckStatus.PASS, "Доступен"),
                              new DiagnosticCheck(
                                  "VPN", CheckStatus.NOT_RUN, "Профиль отключён"))));
                }
                content = journal;
              } else {
                ConnectionsView connections = new ConnectionsView(profile -> {}, action -> {});
                connections.setProfiles(
                    scenario.equals("empty") ? List.of() : List.of(MOSCOW, SECOND), MOSCOW.id());
                ConnectionState state =
                    switch (scenario) {
                      case "connected", "switch" -> ConnectionState.CONNECTED;
                      case "connecting" -> ConnectionState.CONNECTING;
                      case "disconnecting" -> ConnectionState.DISCONNECTING;
                      case "error", "cleanup" -> ConnectionState.ERROR;
                      default -> ConnectionState.DISCONNECTED;
                    };
                connections.update(
                    new ConnectionSnapshot(
                        1,
                        state,
                        UUID.randomUUID(),
                        state == ConnectionState.CONNECTED ? MOSCOW : null,
                        MOSCOW.id(),
                        false,
                        scenario.equals("cleanup"),
                        scenario.equals("cleanup")
                            ? "Не удалось подтвердить очистку"
                            : "Сервер не ответил вовремя"));
                if (scenario.equals("switch")) {
                  connections.select(SECOND.id());
                }
                if (scenario.equals("unsupported")) {
                  connections.serviceUnavailable(
                      "Компонент sing-box недоступен. Подключение пока невозможно.");
                }
                content = connections;
              }
              show(content, 1070, 700);
              return new Rendered(content, journal);
            });
    Thread.sleep(60);
    fx(
        () -> {
          String displayed = text(rendering.content());
          assertFalse(displayed.contains("демонстрацион"), scenario);
          assertFalse(displayed.contains("демо"), scenario);
          WritableImage image = stage.getScene().snapshot(null);
          Path output = visualPath(scenario);
          Files.createDirectories(output.getParent());
          assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", output.toFile()));
          return null;
        });
    fx(
        () -> {
          stage.setWidth(860);
          stage.setHeight(640);
          return null;
        });
    Thread.sleep(60);
    fx(
        () -> {
          assertTrue(stage.getScene().getRoot().getLayoutBounds().getWidth() <= stage.getWidth());
          if (rendering.journal() != null) {
            rendering.journal().close();
          }
          return null;
        });
  }

  private record Rendered(Parent content, JournalView journal) {}

  @Test
  void completeShellAndOwnedDialogsRenderWithSafeCancellation() throws Exception {
    Map<String, byte[]> secretValues = new java.util.concurrent.ConcurrentHashMap<>();
    SecretStore store =
        new SecretStore() {
          @Override
          public void write(String reference, byte[] value) {
            secretValues.put(reference, value.clone());
          }

          @Override
          public Optional<byte[]> read(String reference) {
            return Optional.ofNullable(secretValues.get(reference)).map(byte[]::clone);
          }

          @Override
          public void delete(String reference) {
            secretValues.remove(reference);
          }
        };
    ProfileRepository repository = new ProfileRepository(temporary.resolve("profiles"), store);
    Profile draft =
        new Profile(
            MOSCOW.id(),
            0,
            MOSCOW.name(),
            MOSCOW.server(),
            MOSCOW.port(),
            new OpenVpnSettings("udp", "user", true, certificate(), "", "", "", "", -1),
            List.of("10.20.0.0/16", "10.30.0.0/16"),
            MOSCOW.dns(),
            MOSCOW.domains(),
            "",
            "");
    Profile stored = repository.save(draft, 0, ProfileSecrets.empty());
    Profile second =
        repository.save(
            new Profile(
                SECOND.id(),
                0,
                SECOND.name(),
                SECOND.server(),
                SECOND.port(),
                draft.settings(),
                SECOND.networks(),
                SECOND.dns(),
                SECOND.domains(),
                "",
                ""),
            0,
            ProfileSecrets.empty());
    Profile personal =
        repository.save(
            new Profile(
                UUID.randomUUID(),
                0,
                "Personal",
                "personal.example.com",
                443,
                VlessSettings.defaults(),
                MOSCOW.networks(),
                MOSCOW.dns(),
                MOSCOW.domains(),
                "",
                ""),
            0,
            new ProfileSecrets("", "", "", "", "", "00000000-0000-4000-8000-000000000001"));
    TestGateway gateway = new TestGateway(connected(stored));
    MainWindow main =
        fx(
            () -> {
              MainWindow window = new MainWindow(stage, repository, gateway);
              window.show();
              stage.sizeToScene();
              return window;
            });
    try {
      Thread.sleep(250);
      fx(
          () -> {
            ConnectionsView connections = field(main, "connections", ConnectionsView.class);
            connections.select(stored.id());
            capture(stage, "full-window");
            stage.setWidth(860);
            stage.setHeight(640);
            return null;
          });
      Thread.sleep(100);
      fx(
          () -> {
            capture(stage, "full-window-minimum");
            stage.sizeToScene();
            return null;
          });
      modalCapture("add", () -> invoke(main, "addProfile", new Class<?>[0]));
      modalCapture(
          "import",
          () ->
              invoke(
                  main,
                  "importPreview",
                  new Class<?>[] {ImportResult.class},
                  new ImportResult(
                      second,
                      ProfileSecrets.empty(),
                      List.of("redirect-gateway не применяется."))));
      modalCapture("add-file", () -> invoke(main, "importFile", new Class<?>[0]));
      modalCapture("add-link", () -> invoke(main, "importLink", new Class<?>[0]));
      modalCapture(
          "import-error",
          () ->
              invoke(
                  main,
                  "importFailure",
                  new Class<?>[] {Throwable.class},
                  new IOException("Неподдержанный обязательный параметр plugin.")));

      PreparedConnection prepared =
          new PreparedConnection(
              UUID.randomUUID(),
              UUID.randomUUID(),
              second.id(),
              second.revision(),
              1,
              true,
              List.of(),
              Instant.now().plusSeconds(60));
      modalCapture(
          "switch-confirm",
          () ->
              invoke(
                  main,
                  "confirmPrepared",
                  new Class<?>[] {PreparedConnection.class, Profile.class},
                  prepared,
                  second));
      modalCapture(
          "auth",
          () ->
              invoke(
                  main,
                  "withCredentials",
                  new Class<?>[] {Profile.class, ProfileSecrets.class, Consumer.class},
                  stored,
                  ProfileSecrets.empty(),
                  (Consumer<ProfileSecrets>) value -> {}));
      modalCapture(
          "delete", () -> invoke(main, "deleteProfile", new Class<?>[] {Profile.class}, stored));
      modalCapture(
          "export", () -> invoke(main, "exportProfile", new Class<?>[] {Profile.class}, stored));
      modalCapture("exit", () -> invoke(main, "requestExit", new Class<?>[] {boolean.class}, true));

      SettingsView settingsView = fx(() -> field(main, "preferences", SettingsView.class));
      modalCapture(
          "permission", () -> invoke(settingsView, "requestInstallation", new Class<?>[0]));
      modalCapture("about", () -> invoke(settingsView, "about", new Class<?>[0]));
      fx(
          () -> {
            show(settingsView, 1070, 700);
            return null;
          });
      Thread.sleep(80);
      fx(
          () -> {
            capture(stage, "settings");
            settingsView.showComponents();
            return null;
          });
      Thread.sleep(80);
      fx(
          () -> {
            capture(stage, "components");
            return null;
          });

      fx(
          () -> {
            ProfileEditor editor = editor(stored, new AtomicInteger());
            setField(main, "editor", editor);
            show(editor, 1070, 700);
            editor.putNetwork("", "10.60.0.0/16");
            return null;
          });
      modalCapture(
          "active-save",
          () ->
              invoke(
                  main,
                  "saveDraft",
                  new Class<?>[] {ProfileEditor.Draft.class},
                  field(main, "editor", ProfileEditor.class).draft()));
      modalCapture("unsaved", () -> invoke(main, "addProfile", new Class<?>[0]));
      modalCapture(
          "network-form",
          () ->
              invoke(
                  main,
                  "editNetwork",
                  new Class<?>[] {ProfileEditor.class},
                  field(main, "editor", ProfileEditor.class)));
      modalCapture(
          "domain-form",
          () ->
              invoke(
                  main,
                  "addDomain",
                  new Class<?>[] {ProfileEditor.class},
                  field(main, "editor", ProfileEditor.class)));
      modalCapture(
          "validation",
          () ->
              invoke(
                  main,
                  "validateDraft",
                  new Class<?>[] {ProfileEditor.Draft.class},
                  new ProfileEditor.Draft(Profile.draft(VpnType.VLESS), ProfileSecrets.empty())));

      JournalView journal = fx(() -> field(main, "journal", JournalView.class));
      fx(
          () -> {
            setField(main, "editor", null);
            journal.showErrors(stored);
            show(journal, 1070, 700);
            nodes(journal)
                .filter(TableView.class::isInstance)
                .findFirst()
                .ifPresent(node -> ((TableView<?>) node).getSelectionModel().selectFirst());
            return null;
          });
      Thread.sleep(80);
      fx(
          () -> {
            capture(stage, "log-detail");
            journal.showDiagnostics(stored);
            journal.report(
                new DiagnosticReport(
                    stored.id(),
                    Instant.now(),
                    List.of(
                        new DiagnosticCheck("Компонент", CheckStatus.PASS, "Доступен"),
                        new DiagnosticCheck("VPN", CheckStatus.NOT_RUN, "Запрос не выполнялся"))));
            return null;
          });
      modalCapture("report", () -> button(journal, "Сохранить отчёт").fire());
      fx(
          () -> {
            TrayController tray = field(main, "tray", TrayController.class);
            Parent panel = (Parent) invoke(tray, "panel", new Class<?>[0]);
            show(panel, 326, 480);
            return null;
          });
      Thread.sleep(80);
      fx(
          () -> {
            capture(stage, "tray");
            TrayController tray = field(main, "tray", TrayController.class);
            stage.hide();
            invoke(tray, "showPanel", new Class<?>[] {double.class, double.class}, 600.0, 500.0);
            return null;
          });
      Thread.sleep(80);
      fx(
          () -> {
            Stage panel = field(field(main, "tray", TrayController.class), "popup", Stage.class);
            assertTrue(
                panel.isShowing(), "Tray controls must open while the main window is hidden");
            assertFalse(stage.isShowing());
            capture(panel, "tray-hidden-owner");
            return null;
          });
      Stage trayPanel =
          fx(() -> field(field(main, "tray", TrayController.class), "popup", Stage.class));
      fx(
          () -> {
            // This verifies the handler contract only. Native delivery has its own prerequisite
            // test.
            Event.fireEvent(
                trayPanel.getScene(),
                new KeyEvent(
                    KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            return null;
          });
      awaitFx(() -> !trayPanel.isShowing(), "The Escape handler must close the tray panel");
      fx(
          () -> {
            Stage panel = field(field(main, "tray", TrayController.class), "popup", Stage.class);
            assertFalse(panel.isShowing(), "Escape must close the tray panel");
            stage.show();
            ConnectionsView view = field(main, "connections", ConnectionsView.class);
            field(main, "shell", BorderPane.class).setCenter(null);
            show(view, 1070, 700);
            nodes(view)
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> "Действия с профилем".equals(button.getAccessibleText()))
                .findFirst()
                .orElseThrow()
                .fire();
            return null;
          });
      Thread.sleep(80);
      fx(
          () -> {
            Window menu =
                Window.getWindows().stream()
                    .filter(window -> window != stage && window.isShowing())
                    .findFirst()
                    .orElseThrow();
            capture(menu, "profile-menu");
            menu.hide();
            assertEquals(0, gateway.connects, "Cancelling dialogs must not connect");
            assertEquals(0, gateway.disconnects, "Cancelling dialogs must not disconnect");
            return null;
          });
      assertEquals(3, repository.list().size(), "No dialog cancellation may change profiles");
      assertEquals(stored, repository.find(stored.id()).orElseThrow());
      assertTrue(repository.find(personal.id()).isPresent());
    } finally {
      fx(
          () -> {
            main.close();
            List.copyOf(Window.getWindows()).stream()
                .filter(window -> window != stage)
                .forEach(Window::hide);
            stage.setOnCloseRequest(null);
            return null;
          });
    }
  }

  private static void focusWindow(Stage target) throws Exception {
    fx(
        () -> {
          target.toFront();
          target.requestFocus();
          Robot robot = new Robot();
          robot.mouseMove(target.getX() + target.getWidth() / 2, target.getY() + 12);
          robot.mouseClick(MouseButton.PRIMARY);
          return null;
        });
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!fx(target::isFocused) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(
        fx(target::isFocused), "The native test window must receive focus before keyboard input");
  }

  private static boolean pressKey(Window target, KeyCode key) throws Exception {
    CountDownLatch received = new CountDownLatch(1);
    EventHandler<KeyEvent> observe =
        event -> {
          if (event.getCode() == key) {
            received.countDown();
          }
        };
    fx(
        () -> {
          target.addEventFilter(KeyEvent.KEY_PRESSED, observe);
          return null;
        });
    try {
      fx(
          () -> {
            new Robot().keyPress(key);
            return null;
          });
      boolean glass = received.await(1, TimeUnit.SECONDS);
      fx(
          () -> {
            new Robot().keyRelease(key);
            return null;
          });
      if (glass) {
        return true;
      }
      java.awt.Robot keyboard = new java.awt.Robot();
      keyboard.keyPress(key.getCode());
      boolean awt;
      try {
        awt = received.await(1, TimeUnit.SECONDS);
      } finally {
        keyboard.keyRelease(key.getCode());
      }
      if (!awt) {
        String diagnostic =
            "Native input NOT_RUN at "
                + Instant.now()
                + "\nJavaFX Robot: no KeyPress received\nAWT Robot: no KeyPress received\n"
                + "FX focused="
                + fx(target::isFocused)
                + "\n"
                + nativeInputContext()
                + "\n";
        Path output = Path.of("target", "visual", "native-input.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, diagnostic);
      }
      return awt;
    } finally {
      fx(
          () -> {
            target.removeEventFilter(KeyEvent.KEY_PRESSED, observe);
            return null;
          });
    }
  }

  private static String nativeInputContext() {
    if (!System.getProperty("os.name").startsWith("Windows")) {
      return "OS="
          + System.getProperty("os.name")
          + ", test process="
          + ProcessHandle.current().pid();
    }
    var process = new com.sun.jna.ptr.IntByReference();
    com.sun.jna.platform.win32.User32.INSTANCE.GetWindowThreadProcessId(
        com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow(), process);
    return "OS="
        + System.getProperty("os.name")
        + "\nforeground process="
        + Integer.toUnsignedLong(process.getValue())
        + "\ntest process="
        + ProcessHandle.current().pid();
  }

  private static void awaitFx(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!fx(condition::getAsBoolean) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(fx(condition::getAsBoolean), message);
  }

  private static void modalCapture(String name, Runnable open) throws Exception {
    CompletableFuture<Void> finished = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          try {
            open.run();
            finished.complete(null);
          } catch (Throwable failure) {
            finished.completeExceptionally(failure);
          }
        });
    Window dialog = null;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (dialog == null && System.nanoTime() < deadline) {
      if (finished.isCompletedExceptionally()) {
        finished.get(1, TimeUnit.SECONDS);
      }
      dialog =
          fx(
              () ->
                  Window.getWindows().stream()
                      .filter(window -> window != stage && window.isShowing())
                      .findFirst()
                      .orElse(null));
      if (dialog == null) {
        Thread.sleep(20);
      }
    }
    assertTrue(dialog != null, "Owned dialog did not open: " + name);
    Window opened = dialog;
    fx(
        () -> {
          capture(opened, name);
          List<Button> destructive =
              buttons(opened.getScene().getRoot())
                  .filter(button -> button.getStyleClass().contains("danger"))
                  .toList();
          assertTrue(destructive.stream().noneMatch(Button::isDefaultButton));
          Button cancel =
              buttons(opened.getScene().getRoot())
                  .filter(Button::isCancelButton)
                  .findFirst()
                  .orElseThrow();
          cancel.fire();
          return null;
        });
    finished.get(10, TimeUnit.SECONDS);
  }

  private static void capture(Window window, String name) throws IOException {
    window.getScene().getRoot().applyCss();
    window.getScene().getRoot().layout();
    Path output = visualPath(name);
    Files.createDirectories(output.getParent());
    assertTrue(
        ImageIO.write(
            SwingFXUtils.fromFXImage(window.getScene().snapshot(null), null),
            "png",
            output.toFile()));
  }

  private static Path visualPath(String name) {
    Path directory = Path.of("target", "visual");
    String scale = System.getProperty("glass.win.uiScale");
    if (scale != null) {
      directory = directory.resolve("scale-" + scale);
    }
    return directory.resolve(name + ".png");
  }

  /** Reflection is confined to selecting visual fixture states, never used by shipped code. */
  private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) {
    try {
      var method = target.getClass().getDeclaredMethod(name, types);
      method.setAccessible(true);
      return method.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      throw new AssertionError("UI action failed: " + name, failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("UI fixture could not select state: " + name, failure);
    }
  }

  private static <T> T field(Object target, String name, Class<T> type) {
    try {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return type.cast(field.get(target));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void setField(Object target, String name, Object value) {
    try {
      var field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static String certificate() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var keys = generator.generateKeyPair();
    var name = new X500Name("CN=Wisprail UI test");
    var builder =
        new JcaX509v3CertificateBuilder(
            name,
            BigInteger.ONE,
            Date.from(Instant.now().minusSeconds(60)),
            Date.from(Instant.now().plusSeconds(3600)),
            name,
            keys.getPublic());
    var certificate =
        builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()));
    StringWriter result = new StringWriter();
    try (var writer = new JcaPEMWriter(result)) {
      writer.writeObject(certificate);
    }
    return result.toString();
  }

  private static ProfileEditor editor(Profile profile, AtomicInteger writes) {
    return new ProfileEditor(
        profile,
        ProfileSecrets.empty(),
        true,
        draft -> writes.incrementAndGet(),
        draft -> {},
        () -> {},
        (title, consumer) -> {},
        editor -> {},
        editor -> {});
  }

  private static Profile profile(String name, String network, String dns) {
    return new Profile(
        UUID.randomUUID(),
        1,
        name,
        "vpn.example.com",
        1194,
        new OpenVpnSettings("udp", "user", true, "", "", "", "", "", -1),
        List.of(network),
        dns,
        List.of("company.local", "corp.local"),
        "",
        "");
  }

  private static Profile vless() {
    return new Profile(
        UUID.randomUUID(),
        1,
        "Personal",
        "personal.example.com",
        443,
        VlessSettings.defaults(),
        MOSCOW.networks(),
        MOSCOW.dns(),
        MOSCOW.domains(),
        "",
        "");
  }

  private static ConnectionSnapshot connected(Profile profile) {
    return new ConnectionSnapshot(
        1,
        ConnectionState.CONNECTED,
        null,
        profile,
        null,
        false,
        false,
        "VPN работает для выбранных сетей");
  }

  private static void show(Parent content, double width, double height) {
    Ui.stylesheet(content);
    stage.setScene(new Scene(content, width, height));
    stage.sizeToScene();
    stage.show();
    stage.toFront();
    content.applyCss();
    content.layout();
  }

  private static Button button(Parent content, String title) {
    return buttons(content)
        .filter(button -> button.getText().equals(title))
        .findFirst()
        .orElseThrow();
  }

  private static Stream<Button> buttons(Parent content) {
    content.applyCss();
    content.layout();
    return nodes(content).filter(Button.class::isInstance).map(Button.class::cast);
  }

  private static String text(Parent content) {
    content.applyCss();
    content.layout();
    return nodes(content)
        .filter(Labeled.class::isInstance)
        .map(Labeled.class::cast)
        .map(Labeled::getText)
        .collect(Collectors.joining("\n"));
  }

  private static Stream<Node> nodes(Node root) {
    return root instanceof Parent parent
        ? Stream.concat(
            Stream.of(root),
            parent.getChildrenUnmodifiable().stream().flatMap(DesktopViewTest::nodes))
        : Stream.of(root);
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    CompletableFuture<T> result = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          try {
            result.complete(action.call());
          } catch (Throwable failure) {
            result.completeExceptionally(failure);
          }
        });
    return result.get(20, TimeUnit.SECONDS);
  }
}
