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
import app.wisprail.platform.ComponentStatus;
import app.wisprail.platform.PlatformDesktopIntegration;
import app.wisprail.profile.ImportResult;
import app.wisprail.profile.OpenVpnSettings;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileImporter;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
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
  void startupPreparesMissingComponentBeforeCreatingTheClientWithoutBlockingFx() throws Exception {
    ProfileRepository repository = localRepository();
    CountDownLatch preparing = new CountDownLatch(1);
    CountDownLatch permitted = new CountDownLatch(1);
    AtomicReference<ComponentStatus.State> component =
        new AtomicReference<>(ComponentStatus.State.MISSING);
    AtomicBoolean preparationOnFx = new AtomicBoolean();
    AtomicInteger clients = new AtomicInteger();
    WisprailApplication application =
        new WisprailApplication(
            () -> repository,
            () -> {
              preparationOnFx.set(Platform.isFxApplicationThread());
              preparing.countDown();
              assertTrue(permitted.await(10, TimeUnit.SECONDS));
              component.set(ComponentStatus.State.READY);
              return new ComponentStatus(component.get(), "Компонент запущен");
            },
            () -> {
              assertEquals(ComponentStatus.State.READY, component.get());
              clients.incrementAndGet();
              return new TestGateway(ConnectionSnapshot.initial());
            });
    try {
      fx(
          () -> {
            application.start(stage);
            return null;
          });
      assertTrue(preparing.await(10, TimeUnit.SECONDS));
      assertFalse(preparationOnFx.get());
      fx(
          () -> {
            assertTrue(text(stage.getScene().getRoot()).contains("подготавливаем компонент"));
            assertEquals(0, clients.get());
            return null;
          });
      permitted.countDown();
      awaitFx(
          () -> text(stage.getScene().getRoot()).contains("Добавьте первый VPN"),
          "The application must open after component preparation");
      fx(
          () -> {
            String displayed = text(stage.getScene().getRoot());
            assertEquals(1, clients.get());
            assertFalse(displayed.contains("Компонент недоступен"));
            assertFalse(displayed.contains("Ошибка подключения"));
            return null;
          });
    } finally {
      permitted.countDown();
      fx(
          () -> {
            application.stop();
            stage.hide();
            stage.setMinWidth(0);
            stage.setMinHeight(0);
            return null;
          });
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Установка не разрешена системой", "Установка компонента не завершена"})
  void deniedOrFailedPreparationLeavesActionableStatusAndLocalProfileEditing(String failure)
      throws Exception {
    ProfileRepository repository = localRepository();
    AtomicInteger clients = new AtomicInteger();
    WisprailApplication application =
        new WisprailApplication(
            () -> repository,
            () -> {
              throw new IOException(failure);
            },
            () -> {
              clients.incrementAndGet();
              return new TestGateway(ConnectionSnapshot.initial());
            });
    try {
      fx(
          () -> {
            application.start(stage);
            return null;
          });
      awaitFx(
          () -> text(stage.getScene().getRoot()).contains("Открыть настройки компонента"),
          "A failed component must remain visible on the empty connections screen");
      fx(
          () -> {
            String displayed = text(stage.getScene().getRoot());
            assertEquals(1, clients.get());
            assertTrue(displayed.contains(failure));
            assertTrue(displayed.contains("Компонент недоступен"));
            assertFalse(displayed.contains("Ошибка подключения"));
            assertFalse(button(stage.getScene().getRoot(), "Добавить VPN").isDisabled());
            MainWindow main = field(application, "window", MainWindow.class);
            invoke(
                main,
                "openEditor",
                new Class<?>[] {Profile.class, ProfileSecrets.class, int.class},
                Profile.draft(VpnType.OPENVPN),
                ProfileSecrets.empty(),
                0);
            assertFalse(button(stage.getScene().getRoot(), "Сохранить").isDisabled());
            assertTrue(text(stage.getScene().getRoot()).contains("Компонент недоступен"));
            return null;
          });
    } finally {
      fx(
          () -> {
            application.stop();
            stage.hide();
            stage.setMinWidth(0);
            stage.setMinHeight(0);
            return null;
          });
    }
  }

  @Test
  void stoppedApplicationIgnoresLatePreparationAndDoesNotCreateClient() throws Exception {
    ProfileRepository repository = localRepository();
    CountDownLatch preparing = new CountDownLatch(1);
    CountDownLatch permitted = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicInteger clients = new AtomicInteger();
    WisprailApplication application =
        new WisprailApplication(
            () -> repository,
            () -> {
              preparing.countDown();
              try {
                permitted.await(10, TimeUnit.SECONDS);
                return new ComponentStatus(ComponentStatus.State.READY, "Компонент запущен");
              } finally {
                finished.countDown();
              }
            },
            () -> {
              clients.incrementAndGet();
              return new TestGateway(ConnectionSnapshot.initial());
            });
    try {
      fx(
          () -> {
            application.start(stage);
            return null;
          });
      assertTrue(preparing.await(10, TimeUnit.SECONDS));
      fx(
          () -> {
            application.stop();
            return null;
          });
      permitted.countDown();
      assertTrue(finished.await(10, TimeUnit.SECONDS));
      fx(
          () -> {
            assertEquals(0, clients.get());
            return null;
          });
    } finally {
      permitted.countDown();
      fx(
          () -> {
            application.stop();
            stage.hide();
            stage.setMinWidth(0);
            stage.setMinHeight(0);
            return null;
          });
    }
  }

  @Test
  void confirmedComponentInstallationRefreshesClientAndRemovesTheNotice() throws Exception {
    ProfileRepository repository = localRepository();
    TestGateway gateway = new TestGateway(ConnectionSnapshot.initial());
    MainWindow main =
        fx(
            () -> {
              MainWindow window =
                  new MainWindow(
                      stage,
                      repository,
                      gateway,
                      new ComponentStatus(ComponentStatus.State.MISSING, "Компонент не установлен"));
              window.show();
              return window;
            });
    try {
      fx(
          () -> {
            assertTrue(text(stage.getScene().getRoot()).contains("Компонент недоступен"));
            SettingsView settingsView = field(main, "preferences", SettingsView.class);
            invoke(
                settingsView,
                "componentChecked",
                new Class<?>[] {ComponentStatus.class},
                new ComponentStatus(ComponentStatus.State.READY, "Компонент запущен"));
            return null;
          });
      awaitFx(
          () -> !text(stage.getScene().getRoot()).contains("Компонент недоступен"),
          "Component confirmation must recover the current client without restarting the UI");
      fx(
          () -> {
            assertEquals(1, gateway.refreshes);
            assertFalse(text(stage.getScene().getRoot()).contains("Открыть настройки компонента"));
            return null;
          });
    } finally {
      fx(
          () -> {
            main.close();
            stage.hide();
            return null;
          });
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {860, 1340})
  void longEngineDetailsKeepSettingsTitlesReadableAndValuesInsideTheCard(int width)
      throws Exception {
    fx(
        () -> {
          BackgroundTasks tasks = new BackgroundTasks((operation, failure) -> {});
          tasks.close();
          SettingsView view =
              new SettingsView(
                  PlatformDesktopIntegration.create(temporary),
                  new AppSettings(),
                  tasks,
                  new Dialogs(stage),
                  true,
                  component -> {});
          view.showComponents();
          field(view, "status", Label.class)
              .setText("Компонент управления недоступен (код ОС 1060)");
          Label version = field(view, "version", Label.class);
          version.setText(
              "sing-box version 1.14.2\n\n"
                  + "Environment: go1.26.8 windows/amd64\n"
                  + "Tags: with_gvisor,with_quic,with_dhcp,with_wireguard,with_utls,with_acme,"
                  + "with_clash_api,with_tailscale,with_ccm,with_ocm,with_cloudflared,"
                  + "with_naive_outbound,with_purego,with_usbip,with_openvpn,with_openconnect,"
                  + "badlinkname,tfogo_checklinkname0\n"
                  + "Revision: af6e64c3b69e6132ebaee0e1a3d24e93903f6709\n"
                  + "CGO: disabled");
          field(view, "supported", Label.class).setText("OpenVPN · VLESS");
          show(view, width, 700);

          for (Node node : view.lookupAll(".setting-row")) {
            HBox row = (HBox) node;
            VBox text = (VBox) row.getChildren().getFirst();
            Label title = (Label) text.getChildren().getFirst();
            Label description = (Label) text.getChildren().get(1);
            Node value = row.getChildren().get(1);
            assertTrue(
                title.getHeight() <= title.prefHeight(-1) + 1,
                "Settings titles must remain on one line: " + title.getText());
            assertTrue(
                description.getHeight() <= description.prefHeight(-1) * 2 + 1,
                "Settings descriptions must not become narrow columns: " + title.getText());
            assertTrue(
                value.getBoundsInParent().getMinX() >= text.getBoundsInParent().getMaxX() - 1,
                "The value must not overlap its description: " + title.getText());
            assertTrue(
                value.getBoundsInParent().getMaxX()
                    <= row.getWidth() - row.getInsets().getRight() + 1,
                "The value must fit inside the settings card: " + title.getText());
          }
          assertTrue(version.getHeight() > version.prefHeight(-1));
          capture(stage, "components-long-engine-" + width);
          return null;
        });
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
                      TlsMode.REALITY, "vpn.example", "public-key", "ab", "chrome", "", "tcp", ""),
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

  @ParameterizedTest
  @ValueSource(strings = {"", "vpn-service", "Example.Tunnel-01"})
  void importedGrpcSettingsSurviveEditorChangesSaveAndExport(String serviceName) throws Exception {
    ImportResult imported =
        new ProfileImporter()
            .importVless(
                "vless://00000000-0000-4000-8000-000000000001@vpn.example:443"
                    + "?type=grpc&security=tls&sni=vpn.example&serviceName="
                    + URLEncoder.encode(serviceName, StandardCharsets.UTF_8)
                    + "#gRPC");
    ProfileEditor.Draft draft =
        fx(
            () -> {
              List<ProfileEditor.Draft> saves = new ArrayList<>();
              ProfileEditor editor =
                  new ProfileEditor(
                      imported.profile(),
                      imported.secrets(),
                      false,
                      saves::add,
                      value -> {},
                      () -> {},
                      (title, consumer) -> {},
                      value -> {},
                      value -> {});
              show(editor, 1070, 700);
              assertFalse(editor.isDirty());
              assertEquals(serviceName, field(editor, "serviceName", TextField.class).getText());
              editor.showTab(1);
              editor.showError("serviceName", "Проверьте имя сервиса gRPC");
              TitledPane parameters =
                  nodes(editor)
                      .filter(TitledPane.class::isInstance)
                      .map(TitledPane.class::cast)
                      .findFirst()
                      .orElseThrow();
              assertTrue(parameters.isExpanded());
              ComboBox<?> vlessTransport =
                  nodes(editor)
                      .filter(ComboBox.class::isInstance)
                      .map(node -> (ComboBox<?>) node)
                      .filter(control -> control.getItems().contains("grpc"))
                      .findFirst()
                      .orElseThrow();
              assertEquals("gRPC", vlessTransport.getButtonCell().getText());
              assertTrue(field(editor, "serviceName", TextField.class).getParent().isVisible());
              vlessTransport.getSelectionModel().select(vlessTransport.getItems().indexOf("tcp"));
              assertFalse(field(editor, "serviceName", TextField.class).getParent().isVisible());
              assertEquals("", ((VlessSettings) editor.draft().profile().settings()).serviceName());
              vlessTransport.getSelectionModel().select(vlessTransport.getItems().indexOf("grpc"));
              assertTrue(field(editor, "serviceName", TextField.class).getParent().isVisible());
              ComboBox<?> protocol =
                  nodes(editor)
                      .filter(ComboBox.class::isInstance)
                      .map(node -> (ComboBox<?>) node)
                      .filter(control -> control.getItems().contains(VpnType.OPENVPN))
                      .findFirst()
                      .orElseThrow();
              protocol.getSelectionModel().select(protocol.getItems().indexOf(VpnType.OPENVPN));
              assertEquals(
                  "udp", ((OpenVpnSettings) editor.draft().profile().settings()).transport());
              protocol.getSelectionModel().select(protocol.getItems().indexOf(VpnType.VLESS));
              editor.showTab(1);
              editor.showTab(0);
              editor.putNetwork("", "10.20.0.0/16");
              field(editor, "healthUrl", TextField.class).setText("https://10.20.0.5/health");
              button(editor, "Сохранить").fire();
              assertEquals(1, saves.size());
              return saves.getFirst();
            });
    VlessSettings settings = (VlessSettings) draft.profile().settings();
    assertEquals("grpc", settings.transport());
    assertEquals(serviceName, settings.serviceName());
    Map<String, byte[]> access = new HashMap<>();
    SecretStore store =
        new SecretStore() {
          @Override
          public void write(String reference, byte[] value) {
            access.put(reference, value.clone());
          }

          @Override
          public Optional<byte[]> read(String reference) {
            return Optional.ofNullable(access.get(reference)).map(byte[]::clone);
          }

          @Override
          public void delete(String reference) {
            access.remove(reference);
          }
        };
    ProfileRepository repository = new ProfileRepository(temporary.resolve("profiles"), store);
    Profile saved = repository.save(draft.profile(), 0, draft.secrets());
    assertEquals(settings, repository.find(saved.id()).orElseThrow().settings());
    Path exported = temporary.resolve("grpc.json");
    repository.exportProfile(saved, exported);
    assertEquals(settings, new ProfileImporter().importFile(exported).profile().settings());
  }

  @Test
  void pastedVlessLinkCanBeSavedAndConnectedWithoutManualNetworkOrDns() throws Exception {
    String link =
        "vless://00000000-0000-4000-8000-000000000001@vpn.example:8443"
            + "?type=grpc&encryption=none&serviceName=&authority=&security=reality"
            + "&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&fp=chrome"
            + "&sni=www.example.com&sid=abcdef123456&spx=%2F#Personal";
    ProfileRepository repository = localRepository();
    TestGateway gateway = new TestGateway(ConnectionSnapshot.initial());
    MainWindow main =
        fx(
            () -> {
              MainWindow window = new MainWindow(stage, repository, gateway);
              window.show();
              return window;
            });
    CompletableFuture<Void> adding = new CompletableFuture<>();
    try {
      Platform.runLater(
          () -> {
            try {
              invoke(main, "addProfile", new Class<?>[0]);
              adding.complete(null);
            } catch (Throwable failure) {
              adding.completeExceptionally(failure);
            }
          });
      Window method = awaitDialog("Добавить VPN");
      fx(
          () -> {
            button(method.getScene().getRoot(), "По ссылке · VLESS").fire();
            return null;
          });
      Window input = awaitDialog("Добавление по ссылке");
      fx(
          () -> {
            TextArea value =
                nodes(input.getScene().getRoot())
                    .filter(TextArea.class::isInstance)
                    .map(TextArea.class::cast)
                    .findFirst()
                    .orElseThrow();
            value.setText(link);
            button(input.getScene().getRoot(), "Продолжить").fire();
            return null;
          });
      adding.get(10, TimeUnit.SECONDS);
      Window preview = awaitDialog("Проверьте импорт");
      fx(
          () -> {
            String displayed = text(preview.getScene().getRoot());
            assertTrue(displayed.contains("Весь интернет"));
            assertFalse(displayed.contains("Требует заполнения"));
            assertFalse(displayed.contains("Укажите выбранные IPv4-сети"));
            assertFalse(displayed.contains("00000000-0000-4000-8000-000000000001"));
            assertEquals(0, gateway.connects);
            capture(preview, "import-full-tunnel");
            button(preview.getScene().getRoot(), "Сохранить и подключить").fire();
            return null;
          });
      awaitFx(
          () -> gateway.connects == 1, "Import must save and connect without opening the editor");
      Profile saved = repository.list().getFirst();
      fx(
          () -> {
            assertEquals(saved, gateway.preparedProfile);
            assertEquals(List.of("0.0.0.0/0"), saved.networks());
            assertEquals("", saved.dns());
            assertEquals(List.of(), saved.domains());
            assertEquals("", saved.healthUrl());
            assertEquals(
                "00000000-0000-4000-8000-000000000001", gateway.preparedSecrets.uuid());
            assertEquals("grpc", ((VlessSettings) saved.settings()).transport());
            assertFalse(text(stage.getScene().getRoot()).contains("Сети через VPN"));
            assertTrue(text(stage.getScene().getRoot()).contains("Весь интернет"));
            capture(stage, "full-tunnel-connection");
            return null;
          });
    } finally {
      fx(
          () -> {
            for (Window dialog : List.copyOf(Window.getWindows())) {
              if (dialog != stage && dialog.isShowing()) {
                dialog.hide();
              }
            }
            main.close();
            stage.hide();
            return null;
          });
    }
  }

  @Test
  void fullTunnelModePreservesSelectiveSettingsWhenSwitchingBackBeforeSave() throws Exception {
    fx(
        () -> {
          Profile selective = vless();
          ProfileEditor editor = editor(selective, new AtomicInteger());
          editor.showTab(2);
          show(editor, 1070, 700);
          ComboBox<?> routing = field(editor, "routing", ComboBox.class);
          assertEquals("Выбранные сети", routing.getValue());
          routing.getSelectionModel().select(routing.getItems().indexOf("Весь интернет"));
          assertEquals(List.of("0.0.0.0/0"), editor.draft().profile().networks());
          assertEquals("", editor.draft().profile().dns());
          assertEquals(List.of(), editor.draft().profile().domains());
          assertFalse(text(editor).contains("+ Добавить сеть"));
          editor.showTab(3);
          assertFalse(text(editor).contains("DNS-сервер этого VPN"));
          assertTrue(text(editor).contains("через VPN"));
          editor.showTab(2);
          routing.getSelectionModel().select(routing.getItems().indexOf("Выбранные сети"));
          assertEquals(selective, editor.draft().profile());
          assertFalse(editor.isDirty());
          Profile full =
              new Profile(
                  selective.id(),
                  selective.revision(),
                  selective.name(),
                  selective.server(),
                  selective.port(),
                  selective.settings(),
                  Profile.FULL_TUNNEL_NETWORKS,
                  "1.1.1.1",
                  List.of(),
                  "https://example.com/health",
                  selective.secretRef());
          ProfileEditor fullEditor = editor(full, new AtomicInteger());
          assertEquals(full, fullEditor.draft().profile());
          assertFalse(fullEditor.isDirty());
          fullEditor.showError("healthUrl", "Проверьте HTTPS-ресурс");
          assertTrue(field(fullEditor, "healthUrl", TextField.class).getParent().isVisible());
          assertTrue(field(fullEditor, "connectionParameters", TitledPane.class).isExpanded());
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
                            : "Сервер не ответил вовремя"),
                    scenario.equals("unsupported")
                        ? "Компонент sing-box недоступен. Подключение пока невозможно."
                        : "");
                if (scenario.equals("switch")) {
                  connections.select(SECOND.id());
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

  private static Window awaitDialog(String title) throws Exception {
    AtomicReference<Window> shown = new AtomicReference<>();
    awaitFx(
        () -> {
          shown.set(
              Window.getWindows().stream()
                  .filter(window -> window != stage && window.isShowing())
                  .filter(
                      window -> window instanceof Stage dialog && dialog.getTitle().equals(title))
                  .findFirst()
                  .orElse(null));
          return shown.get() != null;
        },
        "Owned dialog did not open: " + title);
    return shown.get();
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

  private ProfileRepository localRepository() throws IOException {
    Map<String, byte[]> secrets = new ConcurrentHashMap<>();
    SecretStore store =
        new SecretStore() {
          @Override
          public void write(String reference, byte[] value) {
            secrets.put(reference, value.clone());
          }

          @Override
          public Optional<byte[]> read(String reference) {
            return Optional.ofNullable(secrets.get(reference)).map(byte[]::clone);
          }

          @Override
          public void delete(String reference) {
            secrets.remove(reference);
          }
        };
    return new ProfileRepository(temporary.resolve("profiles"), store);
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
