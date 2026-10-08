package app.wisprail.ui;

import app.wisprail.connection.ConnectionGateway;
import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.ConnectionState;
import app.wisprail.connection.PreparedConnection;
import app.wisprail.platform.ComponentStatus;
import app.wisprail.platform.PlatformDesktopIntegration;
import app.wisprail.platform.PlatformServices;
import app.wisprail.profile.DomainNames;
import app.wisprail.profile.ImportResult;
import app.wisprail.profile.Ipv4Cidr;
import app.wisprail.profile.OpenVpnSettings;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileImporter;
import app.wisprail.profile.ProfileRepository;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.ProfileValidator;
import app.wisprail.profile.ValidationIssue;
import app.wisprail.profile.VpnType;
import app.wisprail.storage.PrivateFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Composes desktop storage and authenticated service contracts without owning VPN transitions. */
final class MainWindow implements AutoCloseable {
  private enum Page {
    CONNECTIONS,
    JOURNAL,
    SETTINGS
  }

  private enum SaveAction {
    CANCEL,
    LATER,
    RECONNECT
  }

  private enum ExitAction {
    CANCEL,
    TRAY,
    QUIT
  }

  private final Stage stage;
  private final ProfileRepository repository;
  private final ConnectionGateway gateway;
  private final Dialogs dialogs;
  private final BackgroundTasks tasks;
  private final AppSettings settings = new AppSettings();
  private final BorderPane shell = new BorderPane();
  private final StackPane root = new StackPane(shell);
  private final Label global = Ui.label("VPN отключён", "global-status");
  private final Label toast = Ui.label("", "toast");
  private final PauseTransition toastTimeout = new PauseTransition(Duration.seconds(3.3));
  private final ConnectionsView connections;
  private final JournalView journal;
  private final SettingsView preferences;
  private final TrayController tray;
  private final ToggleButton connectionsTab = new ToggleButton("Подключения");
  private final ToggleButton journalTab = new ToggleButton("Журнал");
  private final ToggleButton settingsTab = new ToggleButton("Настройки");
  private final AutoCloseable snapshots;
  private final AutoCloseable events;
  private List<Profile> profiles = List.of();
  private ProfileEditor editor;
  private ConnectionSnapshot snapshot;
  private boolean closed;
  private boolean saving;
  private boolean connectionRequested;
  private long editorGeneration;
  private long importGeneration;
  private long analysisGeneration;

  MainWindow(Stage stage, ProfileRepository repository, ConnectionGateway gateway) {
    this.stage = stage;
    this.repository = repository;
    this.gateway = gateway;
    snapshot = gateway.snapshot();
    dialogs = new Dialogs(stage);
    tasks = new BackgroundTasks(this::operationFailed);
    connections = new ConnectionsView(profile -> {}, this::connectionAction);
    journal = new JournalView(this::diagnose, this::analyze, this::saveReport, dialogs);
    tray =
        new TrayController(
            stage,
            this::disconnect,
            id -> {
              if (navigate(Page.CONNECTIONS)) {
                connections.select(id);
                requestConnect(connections.selected());
              }
            },
            () -> requestExit(true));
    preferences =
        new SettingsView(
            PlatformDesktopIntegration.create(PlatformServices.installationDirectory()),
            settings,
            tasks,
            dialogs,
            tray.available(),
            status -> {
              connections.serviceUnavailable(
                  status.state() == ComponentStatus.State.READY ? "" : status.message());
            });
    StackPane brandMark = new StackPane(Ui.icon("shield"));
    brandMark.getStyleClass().add("brand-mark");
    brandMark.setMinSize(29, 29);
    brandMark.setMaxSize(29, 29);
    HBox brand = Ui.row(brandMark, Ui.label("Wisprail", "brand"));
    ToggleGroup navigation = new ToggleGroup();
    List<ToggleButton> buttons = List.of(connectionsTab, journalTab, settingsTab);
    for (ToggleButton button : buttons) {
      button.setToggleGroup(navigation);
      button.getStyleClass().add("nav-button");
    }
    connectionsTab.setOnAction(event -> navigate(Page.CONNECTIONS));
    journalTab.setOnAction(event -> navigate(Page.JOURNAL));
    settingsTab.setOnAction(event -> navigate(Page.SETTINGS));
    HBox header = Ui.row(brand, connectionsTab, journalTab, settingsTab, Ui.spacer(), global);
    header.getStyleClass().add("navigation");
    global.setMaxWidth(230);
    global.setWrapText(true);
    shell.setTop(header);
    shell.setCenter(connections);
    connectionsTab.setSelected(true);
    toast.setVisible(false);
    toast.setManaged(false);
    root.getChildren().add(toast);
    StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
    StackPane.setMargin(toast, new Insets(18));
    toastTimeout.setOnFinished(event -> toast.setVisible(false));
    Ui.stylesheet(root);
    Scene scene = new Scene(root, 1070, 700);
    scene
        .getAccelerators()
        .put(
            new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
            () -> {
              if (editor != null && !saving) {
                saveDraft(editor.draft());
              }
            });
    stage.setScene(scene);
    stage.setOnCloseRequest(
        event -> {
          event.consume();
          requestExit(false);
        });
    snapshots = gateway.subscribe(value -> tasks.dispatch(() -> update(value)));
    events = gateway.subscribeEvents(journal::accept);
    gateway.recentEvents().forEach(journal::accept);
    refreshProfiles(null);
  }

  void show() {
    Platform.setImplicitExit(false);
    stage.show();
  }

  private void update(ConnectionSnapshot value) {
    if (value.revision() < snapshot.revision()) {
      return;
    }
    boolean newError =
        value.state() == ConnectionState.ERROR
            && (snapshot.state() != ConnectionState.ERROR
                || !snapshot.message().equals(value.message()));
    snapshot = value;
    boolean connected = value.activeProfile() != null && value.state() == ConnectionState.CONNECTED;
    global.setText(connected ? value.activeProfile().name() : TrayController.stateText(value));
    global.setGraphic(connected ? new Circle(3.5, Color.web("#18794e")) : null);
    global.setAccessibleText(
        connected ? "Подключён: " + value.activeProfile().name() : global.getText());
    connections.update(value);
    if (value.state() == ConnectionState.ERROR
        && value.message().contains("Служба управления недоступна")) {
      connections.serviceUnavailable(value.message());
    } else if (value.state() != ConnectionState.ERROR) {
      connections.serviceUnavailable("");
    }
    tray.update(value, profiles);
    if (newError && settings.notifications()) {
      tray.notifyError(value.message());
    }
  }

  private void refreshProfiles(UUID selected) {
    tasks.run(
        "Чтение профилей",
        repository::list,
        values -> {
          profiles = values;
          connections.setProfiles(values, selected);
          journal.setProfiles(values, connections.selected());
          tray.update(snapshot, values);
        });
  }

  private boolean navigate(Page page) {
    if (!discardEditor()) {
      connectionsTab.setSelected(true);
      return false;
    }
    editor = null;
    editorGeneration++;
    switch (page) {
      case CONNECTIONS -> {
        connectionsTab.setSelected(true);
        shell.setCenter(connections);
      }
      case JOURNAL -> {
        journalTab.setSelected(true);
        shell.setCenter(journal);
      }
      case SETTINGS -> {
        settingsTab.setSelected(true);
        shell.setCenter(preferences);
      }
    }
    return true;
  }

  private boolean discardEditor() {
    if (saving) {
      showToast("Дождитесь завершения сохранения");
      return false;
    }
    return editor == null
        || !editor.isDirty()
        || dialogs
            .choose(
                "Есть несохранённые изменения",
                Ui.label(
                    "Введённые настройки будут потеряны. Работающий VPN останется без изменений.",
                    "body"),
                List.of(
                    new Dialogs.Choice<>("Продолжить редактирование", false, true, false),
                    new Dialogs.Choice<>("Не сохранять", true, false, false)))
            .orElse(false);
  }

  private void connectionAction(ConnectionsView.Action action) {
    Profile selected = connections.selected();
    switch (action) {
      case ADD -> addProfile();
      case CONNECT, APPLY -> requestConnect(selected);
      case DISCONNECT -> disconnect();
      case CANCEL -> cancel();
      case EDIT -> edit(selected, 0);
      case NETWORKS -> edit(selected, 2);
      case DNS -> edit(selected, 3);
      case DIAGNOSE -> {
        navigate(Page.JOURNAL);
        journal.showDiagnostics(selected);
      }
      case LOG -> {
        navigate(Page.JOURNAL);
        journal.showErrors(selected);
      }
      case COPY -> {
        if (selected != null) {
          openEditor(ProfileRepository.copy(selected), ProfileSecrets.empty(), 0);
        }
      }
      case EXPORT -> exportProfile(selected);
      case DELETE -> deleteProfile(selected);
      case COMPONENTS -> {
        navigate(Page.SETTINGS);
        preferences.showComponents();
        preferences.checkComponents();
      }
      case CLEANUP -> tasks.observe("Проверка очистки", gateway.retryCleanup(), ignored -> {});
    }
  }

  private void edit(Profile profile, int tab) {
    if (profile == null || snapshot.busy()) {
      return;
    }
    long generation = ++editorGeneration;
    tasks.run(
        "Открытие профиля",
        () -> repository.loadSecrets(profile),
        secrets -> {
          if (editorGeneration == generation) {
            openEditor(profile, secrets, tab);
          }
        });
  }

  private void openEditor(Profile profile, ProfileSecrets secrets, int tab) {
    if (!discardEditor()) {
      return;
    }
    boolean active =
        snapshot.activeProfile() != null && snapshot.activeProfile().id().equals(profile.id());
    editor =
        new ProfileEditor(
            profile,
            secrets,
            active,
            this::saveDraft,
            this::validateDraft,
            () -> navigate(Page.CONNECTIONS),
            this::chooseCertificate,
            this::editNetwork,
            this::addDomain);
    editor.showTab(tab);
    connectionsTab.setSelected(true);
    shell.setCenter(editor);
  }

  private void addProfile() {
    if (!discardEditor()) {
      return;
    }
    dialogs
        .addMethod()
        .ifPresent(
            method -> {
              switch (method) {
                case "file" -> importFile();
                case "link" -> importLink();
                case "manual" ->
                    openEditor(Profile.draft(VpnType.OPENVPN), ProfileSecrets.empty(), 0);
                default -> throw new IllegalArgumentException("Unknown add method");
              }
            });
  }

  private void importFile() {
    if (!dialogs
        .choose(
            "Импорт из файла",
            new VBox(
                14,
                Ui.label(
                    "Выберите конфигурацию OpenVPN или профиль Wisprail. "
                        + "Файл обрабатывается локально и не выполняется.",
                    "body"),
                Ui.notice(
                    ".ovpn или .json · не более 1 МиБ вместе со связанными файлами. "
                        + "Перед сохранением будет показан предпросмотр.",
                    "")),
            List.of(
                new Dialogs.Choice<>("Отмена", false, true, false),
                new Dialogs.Choice<>("Выбрать файл", true, false, false)))
        .orElse(false)) {
      return;
    }
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Импорт профиля · не более 1 МиБ");
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter("Профиль OpenVPN или Wisprail", "*.ovpn", "*.json"));
    File file = chooser.showOpenDialog(stage);
    if (file == null) {
      return;
    }
    long generation = ++importGeneration;
    Dialog<Void> progress = new Dialog<>();
    progress.initOwner(stage);
    progress.setTitle("Импорт профиля");
    progress.setHeaderText("Читаем файл…");
    progress.getDialogPane().setContent(new ProgressIndicator());
    progress.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
    ((Button) progress.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Отмена");
    Ui.stylesheet(progress.getDialogPane());
    progress.setOnHidden(
        event -> {
          if (importGeneration == generation) {
            importGeneration++;
          }
        });
    progress.show();
    tasks.run(
        "Импорт профиля",
        () -> new ProfileImporter().importFile(file.toPath()),
        result -> {
          if (generation == importGeneration) {
            importGeneration++;
            progress.close();
            importPreview(result);
          }
        },
        failure -> {
          if (generation == importGeneration) {
            importGeneration++;
            progress.close();
            importFailure(failure);
          }
        });
  }

  private void importFailure(Throwable failure) {
    if (dialogs
        .choose(
            "Не удалось импортировать",
            new VBox(
                14,
                Ui.notice(safeMessage(failure), "error"),
                Ui.label("Текущие подключения и сохранённые профили не изменены.", "hint")),
            List.of(
                new Dialogs.Choice<>("Закрыть", false, true, false),
                new Dialogs.Choice<>("Выбрать другой файл", true, false, false)))
        .orElse(false)) {
      importFile();
    }
  }

  private void importLink() {
    TextArea value = new TextArea();
    value.setPromptText("vless://…");
    value.setPrefRowCount(5);
    value.setWrapText(true);
    VBox body =
        new VBox(
            14,
            Ui.label(
                "Ссылка может содержать ключ доступа. "
                    + "Не публикуйте её. Вставьте ссылку самостоятельно.",
                "hint"),
            Ui.field("Ссылка VLESS", value, "Одно подключение, TCP с TLS или Reality."));
    boolean accepted =
        dialogs
            .choose(
                "Добавление по ссылке",
                body,
                List.of(
                    new Dialogs.Choice<>("Отмена", false, true, false),
                    new Dialogs.Choice<>("Продолжить", true, false, false)))
            .orElse(false);
    if (accepted) {
      String link = value.getText();
      value.clear();
      tasks.run(
          "Импорт ссылки", () -> new ProfileImporter().importVless(link), this::importPreview);
    }
  }

  private void importPreview(ImportResult result) {
    Profile profile = result.profile();
    VBox details =
        new VBox(
            12,
            Ui.label(
                "Перед сохранением проверьте распознанные настройки. "
                    + "Подключение не запускается, секреты не показываются.",
                "hint"),
            Ui.label(
                "Название: "
                    + profile.name()
                    + "\nТип: "
                    + ConnectionsView.typeTitle(profile)
                    + "\nСервер: "
                    + profile.server()
                    + ":"
                    + profile.port()
                    + "\nСетей: "
                    + profile.networks().size()
                    + " · DNS-доменов: "
                    + profile.domains().size(),
                "body"));
    for (String note : result.notes()) {
      details.getChildren().add(Ui.notice(note, "warning"));
    }
    List<ValidationIssue> issues = ProfileValidator.validate(profile, result.secrets());
    if (!issues.isEmpty()) {
      details
          .getChildren()
          .add(
              Ui.notice(
                  "Требует заполнения: "
                      + issues.stream()
                          .map(ValidationIssue::message)
                          .collect(Collectors.joining("; ")),
                  "warning"));
    }
    if (dialogs
        .choose(
            "Проверьте импорт",
            Ui.scroll(details),
            List.of(
                new Dialogs.Choice<>("Отмена", false, true, false),
                new Dialogs.Choice<>("Перейти к настройкам", true, false, false)))
        .orElse(false)) {
      openEditor(profile, result.secrets(), 0);
    }
  }

  private boolean valid(ProfileEditor.Draft draft) {
    List<ValidationIssue> issues = ProfileValidator.validate(draft.profile(), draft.secrets());
    if (issues.isEmpty()) {
      return true;
    }
    if (editor != null) {
      ValidationIssue first = issues.getFirst();
      editor.showError(first.field(), first.message());
    }
    dialogs.information(
        "Проверьте настройки",
        issues.stream().map(ValidationIssue::message).collect(Collectors.joining("\n")));
    return false;
  }

  private void saveDraft(ProfileEditor.Draft draft) {
    if (saving || !valid(draft)) {
      return;
    }
    Profile active = snapshot.activeProfile();
    boolean networkChange =
        active != null
            && active.id().equals(draft.profile().id())
            && (!draft.profile().sameNetworkSettings(active)
                || (editor != null && editor.secretsChanged()));
    SaveAction action = SaveAction.LATER;
    if (networkChange) {
      action =
          dialogs
              .choose(
                  "Применить изменения?",
                  Ui.notice(
                      "Профиль сейчас подключён. Для новых настроек потребуется переподключение;"
                          + " рабочие соединения могут прерваться.",
                      "warning"),
                  List.of(
                      new Dialogs.Choice<>("Отмена", SaveAction.CANCEL, true, false),
                      new Dialogs.Choice<>("Сохранить на потом", SaveAction.LATER, false, false),
                      new Dialogs.Choice<>("Переподключить", SaveAction.RECONNECT, false, false)))
              .orElse(SaveAction.CANCEL);
    }
    if (action == SaveAction.CANCEL) {
      return;
    }
    boolean reconnect = action == SaveAction.RECONNECT;
    saving = true;
    editor.setDisable(true);
    tasks.run(
        "Сохранение профиля",
        () -> repository.save(draft.profile(), draft.profile().revision(), draft.secrets()),
        saved -> {
          saving = false;
          editor = null;
          navigate(Page.CONNECTIONS);
          refreshProfiles(saved.id());
          if (active != null && active.id().equals(saved.id()) && !networkChange) {
            tasks.observe(
                "Переименование профиля",
                gateway.renameActive(saved.id(), saved.name()),
                ignored -> {});
          }
          if (reconnect) {
            requestConnect(saved);
          } else {
            showToast(
                networkChange
                    ? "Профиль сохранён. Новые настройки применятся после переподключения."
                    : "Профиль сохранён");
          }
        },
        failure -> {
          saving = false;
          if (editor != null) {
            editor.setDisable(false);
          }
          operationFailed("Сохранение профиля", failure);
        });
  }

  private void validateDraft(ProfileEditor.Draft draft) {
    if (!valid(draft) || snapshot.busy() || connectionRequested) {
      return;
    }
    withCredentials(
        draft.profile(),
        draft.secrets(),
        secrets -> {
          connectionRequested = true;
          tasks.observe(
              gateway.prepare(draft.profile(), secrets),
              prepared -> {
                tasks.observe(
                    gateway.cancel(prepared.operationId()),
                    ignored -> {
                      connectionRequested = false;
                      dialogs.information(
                          "Конфигурация проверена",
                          "Поля заполнены корректно, конфигурация принята настоящим sing-box check."
                              + " Сетевое подключение и доступность сервера этим не подтверждены."
                              + " Работающая сеть не изменена.");
                    },
                    failure -> connectionFailed("Освобождение проверенного кандидата", failure));
              },
              failure -> connectionFailed("Проверка конфигурации", failure));
        });
  }

  private void requestConnect(Profile profile) {
    if (profile == null || connectionRequested || snapshot.busy()) {
      return;
    }
    connectionRequested = true;
    tasks.run(
        "Чтение данных доступа",
        () -> repository.loadSecrets(profile),
        secrets -> {
          connectionRequested = false;
          withCredentials(
              profile,
              secrets,
              complete -> {
                connectionRequested = true;
                tasks.observe(
                    gateway.prepare(profile, complete),
                    prepared -> {
                      connectionRequested = false;
                      confirmPrepared(prepared, profile);
                    },
                    failure -> connectionFailed("Проверка подключения", failure));
              });
        },
        failure -> connectionFailed("Чтение данных доступа", failure));
  }

  private void confirmPrepared(PreparedConnection prepared, Profile profile) {
    if (prepared.confirmationRequired()) {
      String active =
          snapshot.activeProfile() == null ? "Текущий VPN" : snapshot.activeProfile().name();
      if (!dialogs.confirm(
          "Переключить VPN?",
          active
              + " будет отключён, затем подключится "
              + profile.name()
              + ". Рабочие соединения через VPN могут прерваться. "
              + "Если новый VPN не подключится, прежний не восстановится автоматически.",
          "Переключиться",
          false)) {
        tasks.observe("Отмена переключения", gateway.cancel(prepared.operationId()), ignored -> {});
        return;
      }
    }
    connectionRequested = true;
    tasks.observe(
        gateway.connect(prepared),
        ignored -> connectionRequested = false,
        failure -> connectionFailed("Подключение VPN", failure));
  }

  private void withCredentials(
      Profile profile, ProfileSecrets secrets, Consumer<ProfileSecrets> ready) {
    if (!(profile.settings() instanceof OpenVpnSettings openVpn)
        || !openVpn.askPassword()
        || openVpn.username().isBlank()) {
      ready.accept(secrets);
      return;
    }
    TextField username = new TextField(openVpn.username());
    username.setEditable(false);
    SecretField password = new SecretField("пароль VPN", "Пароль от VPN, не компьютера");
    Label error = Ui.label("", "error-text");
    VBox body =
        new VBox(
            16,
            Ui.field("Имя пользователя", username, "Изменяется во вкладке «Доступ» профиля."),
            Ui.field("Пароль VPN", password, "Не сохраняется после этой попытки."),
            error);
    while (dialogs
        .choose(
            "Вход в " + profile.name(),
            body,
            List.of(
                new Dialogs.Choice<>("Отмена", false, true, false),
                new Dialogs.Choice<>("Продолжить", true, false, false)))
        .orElse(false)) {
      if (password.getText().isBlank()) {
        error.setText("Введите пароль VPN или отмените подключение.");
        continue;
      }
      ProfileSecrets value =
          new ProfileSecrets(
              password.getText(),
              secrets.privateKey(),
              secrets.privateKeyPassword(),
              secrets.tlsAuthKey(),
              secrets.tlsCryptKey(),
              secrets.uuid());
      password.clear();
      ready.accept(value);
      return;
    }
    password.clear();
    connectionRequested = false;
  }

  private void disconnect() {
    if (!snapshot.busy()) {
      tasks.observe("Отключение VPN", gateway.disconnect(), ignored -> {});
    }
  }

  private void cancel() {
    if (snapshot.operationId() != null) {
      tasks.observe("Отмена подключения", gateway.cancel(snapshot.operationId()), ignored -> {});
    }
  }

  private void deleteProfile(Profile profile) {
    if (profile == null || snapshot.busy()) {
      return;
    }
    boolean active =
        snapshot.activeProfile() != null && snapshot.activeProfile().id().equals(profile.id());
    if (!dialogs.confirm(
        active ? "Удалить подключённый VPN?" : "Удалить профиль?",
        "Профиль «"
            + profile.name()
            + "» будет удалён из приложения. Исходные файлы "
            + "и другие VPN не удаляются."
            + (active ? " Сначала этот VPN будет отключён, рабочие соединения прервутся." : ""),
        active ? "Отключить и удалить" : "Удалить профиль",
        true)) {
      return;
    }
    Runnable remove =
        () ->
            tasks.run(
                "Удаление профиля",
                () -> {
                  repository.delete(profile.id(), profile.revision());
                  return true;
                },
                deleted -> {
                  refreshProfiles(null);
                  showToast("Профиль удалён");
                });
    if (active) {
      tasks.observe("Отключение перед удалением", gateway.disconnect(), ignored -> remove.run());
    } else {
      remove.run();
    }
  }

  private void exportProfile(Profile profile) {
    if (profile == null
        || !dialogs.confirm(
            "Экспорт профиля",
            "Будут сохранены название, сервер, "
                + "сети и DNS профиля «"
                + profile.name()
                + "». Пароль, UUID, ключи и сертификаты исключены. Внутренние адреса и домены всё"
                + " ещё могут быть чувствительными данными. После импорта доступ нужно настроить"
                + " заново.",
            "Сохранить файл",
            false)) {
      return;
    }
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Экспорт профиля без секретов");
    chooser.setInitialFileName("wisprail-profile.json");
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter("Профиль Wisprail", "*.json"));
    File destination = chooser.showSaveDialog(stage);
    if (destination != null) {
      tasks.run(
          "Экспорт профиля",
          () -> {
            repository.exportProfile(profile, destination.toPath());
            return true;
          },
          saved -> showToast("Профиль экспортирован без секретов"));
    }
  }

  private void chooseCertificate(String title, Consumer<String> selected) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle(title + " · до 1 МиБ");
    chooser
        .getExtensionFilters()
        .add(
            new FileChooser.ExtensionFilter(
                "Сертификат или ключ PEM", "*.pem", "*.crt", "*.cer", "*.key"));
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Все файлы", "*.*"));
    File file = chooser.showOpenDialog(stage);
    if (file != null) {
      ProfileEditor owner = editor;
      tasks.run(
          "Чтение сертификата или ключа",
          () ->
              new String(
                  PrivateFiles.read(file.toPath(), ProfileValidator.MAX_TEXT_BYTES),
                  StandardCharsets.UTF_8),
          value -> {
            if (editor == owner) {
              selected.accept(value);
            }
          });
    }
  }

  private void editNetwork(ProfileEditor target) {
    String previous = target.editingNetwork();
    TextField input = new TextField(previous);
    input.setPromptText("10.20.0.0/16");
    while (dialogs
        .choose(
            previous.isEmpty() ? "Добавить сеть" : "Изменить сеть",
            Ui.field("IP-адрес или сеть", input, "Один IPv4-адрес будет предложен с маской /32."),
            List.of(
                new Dialogs.Choice<>("Отмена", false, true, false),
                new Dialogs.Choice<>("Сохранить", true, false, false)))
        .orElse(false)) {
      try {
        Ipv4Cidr value = Ipv4Cidr.parse(input.getText());
        if (!value.canonical().equals(input.getText().trim())) {
          input.setText(value.canonical());
          dialogs.information(
              "Подтвердите сеть",
              "Адрес приведён к "
                  + value.canonical()
                  + ". Проверьте результат и нажмите «Сохранить» ещё раз.");
          continue;
        }
        List<String> others = new ArrayList<>(target.draft().profile().networks());
        others.remove(previous);
        if (others.contains(value.canonical())) {
          throw new IllegalArgumentException("Такая сеть уже добавлена.");
        }
        List<Ipv4Cidr> networks = new ArrayList<>(others.stream().map(Ipv4Cidr::parse).toList());
        networks.add(value);
        if (Ipv4Cidr.coversEntireIpv4(networks)) {
          throw new IllegalArgumentException(
              "В сумме сети охватывают весь IPv4-интернет. " + "Полный туннель не поддерживается.");
        }
        if (others.stream().map(Ipv4Cidr::parse).anyMatch(network -> network.overlaps(value))
            && !dialogs.confirm(
                "Перекрывающиеся сети",
                "Эта сеть пересекается с уже добавленной. " + "Часть правила будет избыточной.",
                "Добавить",
                false)) {
          continue;
        }
        target.putNetwork(previous, value.canonical());
        return;
      } catch (IllegalArgumentException exception) {
        dialogs.error("Проверьте сеть", exception.getMessage());
      }
    }
  }

  private void addDomain(ProfileEditor target) {
    TextField input = new TextField();
    input.setPromptText("company.local");
    while (dialogs
        .choose(
            "Добавить домен",
            Ui.field("Домен", input, "Включая поддомены. Без https://, порта, пути и звёздочки."),
            List.of(
                new Dialogs.Choice<>("Отмена", false, true, false),
                new Dialogs.Choice<>("Добавить", true, false, false)))
        .orElse(false)) {
      try {
        String value = DomainNames.normalize(input.getText());
        if (target.draft().profile().domains().contains(value)) {
          throw new IllegalArgumentException("Этот домен уже есть в списке.");
        }
        target.putDomain(value);
        return;
      } catch (IllegalArgumentException exception) {
        dialogs.error("Проверьте домен", exception.getMessage());
      }
    }
  }

  private void diagnose(Profile profile) {
    journal.checking(true);
    Consumer<Throwable> failed =
        failure -> {
          journal.checking(false);
          operationFailed("Проверка подключения", failure);
        };
    tasks.run(
        "Чтение данных для проверки",
        () -> repository.loadSecrets(profile),
        secrets -> tasks.observe(gateway.diagnose(profile, secrets), journal::report, failed),
        failed);
  }

  private void analyze(Profile profile, String address) {
    long generation = ++analysisGeneration;
    journal.analyzing(true);
    tasks.observe(
        gateway.analyze(profile, address),
        result -> {
          if (generation == analysisGeneration) {
            journal.analysis(profile.id(), address, result);
          }
        },
        failure -> {
          if (generation == analysisGeneration) {
            journal.analyzing(false);
            operationFailed("Разбор адреса", failure);
          }
        });
  }

  private void saveReport(String content) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Сохранить отчёт");
    chooser.setInitialFileName("wisprail-report.txt");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Текстовый отчёт", "*.txt"));
    File destination = chooser.showSaveDialog(stage);
    if (destination != null) {
      tasks.run(
          "Сохранение отчёта",
          () -> {
            PrivateFiles.writeAtomic(
                destination.toPath(), content.getBytes(StandardCharsets.UTF_8));
            return true;
          },
          saved -> showToast("Отчёт сохранён"));
    }
  }

  private void requestExit(boolean explicit) {
    if (!discardEditor()) {
      return;
    }
    AppSettings.CloseAction preference = settings.closeAction();
    if (!explicit && preference == AppSettings.CloseAction.TRAY && tray.available()) {
      stage.hide();
      return;
    }
    boolean active =
        snapshot.activeProfile() != null || snapshot.busy() || snapshot.cleanupRequired();
    if (active && (explicit || preference == AppSettings.CloseAction.ASK)) {
      List<Dialogs.Choice<ExitAction>> choices = new ArrayList<>();
      choices.add(new Dialogs.Choice<>("Отмена", ExitAction.CANCEL, true, false));
      if (tray.available()) {
        choices.add(new Dialogs.Choice<>("Скрыть в трей", ExitAction.TRAY, false, false));
      }
      choices.add(new Dialogs.Choice<>("Отключить и выйти", ExitAction.QUIT, false, false));
      ExitAction chosen =
          dialogs
              .choose(
                  "Завершить работу?",
                  Ui.label(
                      "При завершении приложения VPN будет отключён. Чтобы соединение продолжало"
                          + " работать, оставьте приложение открытым или скройте его в трей.",
                      "body"),
                  choices)
              .orElse(ExitAction.CANCEL);
      if (chosen == ExitAction.CANCEL) {
        return;
      }
      if (chosen == ExitAction.TRAY) {
        stage.hide();
        return;
      }
    }
    if (active) {
      tasks.observe("Отключение перед выходом", gateway.disconnect(), ignored -> finishExit());
    } else {
      finishExit();
    }
  }

  private void finishExit() {
    close();
    stage.hide();
    Platform.exit();
  }

  private void showToast(String message) {
    toast.setText(message);
    toast.setVisible(true);
    toastTimeout.playFromStart();
  }

  private void connectionFailed(String operation, Throwable failure) {
    connectionRequested = false;
    operationFailed(operation, failure);
  }

  private void operationFailed(String operation, Throwable failure) {
    if (!(failure instanceof CancellationException)) {
      dialogs.error(operation, safeMessage(failure));
    }
  }

  private static String safeMessage(Throwable failure) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      return "Операция не выполнена. Проверьте компонент и откройте журнал.";
    }
    return message;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      snapshots.close();
      events.close();
    } catch (Exception exception) {
      // Closing the gateway below also releases both subscriptions and its owned IPC session.
    }
    toastTimeout.stop();
    journal.close();
    tray.close();
    gateway.close();
    tasks.close();
  }
}
