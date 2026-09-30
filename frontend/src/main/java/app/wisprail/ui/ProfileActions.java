package app.wisprail.ui;

import app.wisprail.agent.AgentProtocol;
import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.profile.ProfileImport;
import app.wisprail.profile.ProfileRepository;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.ProfileValidator;
import app.wisprail.profile.VpnProfile;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class ProfileActions {
  private final Window owner;
  private final DesktopModel model;
  private final Consumer<VpnProfile> select;
  private final DesktopView navigation;
  private long importAttempt;
  private UUID preparation;
  private boolean checkingOnly;
  private VpnProfile deleting;
  private long deletionRevision = -1;
  private boolean commandPending;

  ProfileActions(
      Window owner, DesktopModel model, Consumer<VpnProfile> select, DesktopView navigation) {
    this.owner = owner;
    this.model = model;
    this.select = select;
    this.navigation = navigation;
    model.connection.addListener((observable, previous, current) -> changed(current));
  }

  void create() {
    Dialog<String> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Добавить VPN");
    dialog.setHeaderText("Добавить VPN");
    VBox choices =
        new VBox(
            10,
            Ui.label(
                "Выберите, как добавить подключение. Оно не включится автоматически.", "muted"),
            addMethod(dialog, "Из файла", "Конфигурация OpenVPN или профиль приложения", "file"),
            addMethod(dialog, "По ссылке", "Ссылка VLESS", "link"),
            addMethod(dialog, "Вручную", "Сервер, данные доступа, сети и DNS", "edit"));
    dialog.getDialogPane().setContent(choices);
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
    dialog.setResultConverter(button -> null);
    Ui.style(dialog);
    dialog.getDialogPane().getStyleClass().add("methods-dialog");
    var cancel = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
    cancel.setVisible(false);
    cancel.setManaged(false);
    dialog
        .showAndWait()
        .ifPresent(
            method -> {
              switch (method) {
                case "Из файла" -> showFileImport();
                case "По ссылке" -> importLink();
                case "Вручную" -> createManual();
                default -> {}
              }
            });
  }

  private static Button addMethod(Dialog<String> dialog, String title, String help, String icon) {
    Button button =
        Ui.button(
            "",
            () -> {
              dialog.setResult(title);
              dialog.close();
            });
    Label description = Ui.label(help, "small");
    description.setMaxWidth(330);
    HBox graphic =
        new HBox(14, Icons.of(icon), new VBox(5, Ui.label(title, "section-title"), description));
    graphic.setPrefWidth(390);
    graphic.setAlignment(Pos.CENTER_LEFT);
    button.setGraphic(graphic);
    button.setPrefHeight(76);
    button.setMaxHeight(76);
    button.setAccessibleText(title);
    button.setMaxWidth(Double.MAX_VALUE);
    button.getStyleClass().add("method-button");
    return button;
  }

  private void createManual() {
    ChoiceDialog<String> type = new ChoiceDialog<>("OpenVPN", "OpenVPN", "VLESS");
    type.initOwner(owner);
    type.setHeaderText("Тип нового VPN");
    Ui.style(type);
    type.showAndWait()
        .ifPresent(
            value ->
                editor(
                    ProfileEditor.blank(
                        value.equals("OpenVPN")
                            ? VpnProfile.Protocol.OPENVPN
                            : VpnProfile.Protocol.VLESS),
                    ProfileSecrets.empty(),
                    "Основное"));
  }

  void showFileImport() {
    Dialog<Boolean> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setHeaderText("Импорт из файла");
    Button choose = Ui.button("Выберите файл подключения", () -> dialog.setResult(true));
    choose.setGraphic(Icons.of("file"));
    choose.setMaxWidth(Double.MAX_VALUE);
    choose.setPrefHeight(96);
    choose.getStyleClass().add("dropzone");
    dialog
        .getDialogPane()
        .setContent(
            new VBox(
                18,
                Ui.label(
                    "Файл обрабатывается локально. Перед сохранением вы увидите распознанные"
                        + " настройки.",
                    "muted"),
                choose,
                Ui.label(".ovpn или .json · не более 1 МБ", "small")));
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
    dialog.setResultConverter(button -> false);
    Ui.style(dialog);
    dialog.showAndWait().filter(Boolean.TRUE::equals).ifPresent(ignored -> importFile());
  }

  void edit(VpnProfile profile) {
    edit(profile, "Основное");
  }

  void edit(VpnProfile profile, String tab) {
    model.loadSecrets(profile, secrets -> editor(profile, secrets, tab), this::error);
  }

  void copy(VpnProfile profile) {
    editor(ProfileRepository.copyWithoutSecrets(profile), ProfileSecrets.empty(), "Основное");
  }

  void editor(VpnProfile profile, ProfileSecrets secrets, String tab) {
    navigation.openEditor(
        profile,
        secrets,
        tab,
        (candidate, material) -> prepare(candidate, material, true),
        result -> saveDraft(result, secrets));
  }

  private void saveDraft(ProfileEditor.Result result, ProfileSecrets previousSecrets) {
    VpnProfile applied = model.connection.get().appliedProfile();
    boolean activeChange =
        applied != null
            && applied.id().equals(result.profile().id())
            && (!applied.sameNetworkSettings(result.profile())
                || !previousSecrets.equals(result.secrets()));
    boolean reconnect = false;
    if (activeChange) {
      ButtonType later = new ButtonType("Сохранить на потом");
      ButtonType apply = new ButtonType("Переподключить");
      Alert choice =
          new Alert(
              Alert.AlertType.CONFIRMATION,
              "Профиль сейчас подключён. Для новых настроек потребуется переподключение.",
              ButtonType.CANCEL,
              later,
              apply);
      choice.initOwner(owner);
      choice.setHeaderText("Применить изменения?");
      Ui.style(choice);
      choice.getDialogPane().setPrefWidth(610);
      choice
          .getDialogPane()
          .setContent(
              new VBox(
                  18,
                  Ui.label("Профиль «" + applied.name() + "» сейчас подключён.", "muted"),
                  Ui.label(
                      "Для новых настроек потребуется переподключение. Рабочие соединения через VPN"
                          + " будут прерваны.",
                      "notice")));
      ButtonType selected = choice.showAndWait().orElse(ButtonType.CANCEL);
      if (selected == ButtonType.CANCEL) {
        return;
      }
      reconnect = selected == apply;
    }
    boolean applySaved = reconnect;
    navigation.setSaving(true);
    model.save(
        result.profile(),
        result.secrets(),
        savedResult -> {
          navigation.saved();
          select.accept(savedResult.profile());
          if (!savedResult.cleanupWarning().isEmpty()) {
            error(savedResult.cleanupWarning());
          }
          if (applySaved) {
            connect(savedResult.profile());
          }
        },
        message -> {
          navigation.setSaving(false);
          error(message);
        });
  }

  void connect(VpnProfile profile) {
    if (commandPending || model.connection.get().busy() || preparation != null) {
      return;
    }
    commandPending = true;
    model.loadSecrets(
        profile,
        secrets -> {
          commandPending = false;
          if (profile.openVpn() != null
              && !profile.openVpn().username().isEmpty()
              && secrets.password().isEmpty()) {
            credentials(profile)
                .showAndWait()
                .ifPresent(value -> prepare(profile, secrets.withPassword(value), false));
          } else {
            prepare(profile, secrets, false);
          }
        },
        message -> {
          commandPending = false;
          error(message);
        });
  }

  Dialog<String> credentials(VpnProfile profile) {
    Dialog<String> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Данные доступа VPN");
    dialog.setHeaderText("Вход в " + profile.name());
    PasswordField password = new PasswordField();
    password.setAccessibleText("Пароль VPN");
    password.setPromptText("Не вводите системный пароль");
    TextField username = new TextField(profile.openVpn().username());
    username.setEditable(false);
    username.setAccessibleText("Имя пользователя VPN");
    dialog
        .getDialogPane()
        .setContent(
            new VBox(
                12,
                Ui.label(
                    "Серверу нужны данные для входа в VPN. Отмена остановит эту попытку"
                        + " подключения.",
                    "muted"),
                Ui.label("Имя пользователя", "field-label"),
                username,
                Ui.label("Пароль VPN", "field-label"),
                password,
                Ui.label(
                    "Это не системный пароль. Данные этой попытки не сохраняются.",
                    "neutral-notice")));
    ButtonType login = new ButtonType("Подключить");
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, login);
    Ui.style(dialog);
    dialog.setResultConverter(button -> button == login ? password.getText() : null);
    return dialog;
  }

  private void prepare(VpnProfile profile, ProfileSecrets secrets, boolean checkOnly) {
    if (commandPending || preparation != null) {
      error("Дождитесь завершения проверки");
      return;
    }
    commandPending = true;
    checkingOnly = checkOnly;
    model.command(
        AgentProtocol.Command.PREPARE,
        profile,
        secrets,
        null,
        response -> {
          commandPending = false;
          preparation = response.snapshot().operationId();
          changed(response.snapshot());
        },
        message -> {
          commandPending = false;
          error(message);
        });
  }

  private void changed(ConnectionSnapshot state) {
    if (deleting != null
        && deletionRevision >= 0
        && state.revision() >= deletionRevision
        && !state.busy()) {
      VpnProfile target = deleting;
      deleting = null;
      deletionRevision = -1;
      if (state.state() == ConnectionSnapshot.State.DISCONNECTED) {
        model.delete(target, () -> {}, this::error);
      } else {
        error("Удаление отменено: сначала необходимо подтвердить остановку VPN");
      }
    }
    if (preparation == null) {
      return;
    }
    if (state.operationId() == null) {
      preparation = null;
      if (!state.errorMessage().isEmpty()) {
        error(state.errorMessage());
      }
      return;
    }
    if (!preparation.equals(state.operationId()) || state.preparedToken() == null) {
      return;
    }
    UUID operation = preparation;
    preparation = null;
    if (checkingOnly) {
      model.command(
          AgentProtocol.Command.CANCEL,
          null,
          null,
          operation,
          ignored -> {
            Alert result =
                new Alert(
                    Alert.AlertType.INFORMATION,
                    "Конфигурация принята sing-box. Сетевое подключение и доступность ресурса этим"
                        + " не подтверждаются.",
                    ButtonType.OK);
            result.initOwner(owner);
            result.setHeaderText("Настройки проверены");
            Ui.style(result);
            result.showAndWait();
          },
          this::error);
      return;
    }
    boolean approved =
        state.appliedProfile() == null
            || Ui.confirm(
                owner,
                "Переключить VPN?",
                "Подключение «"
                    + state.appliedProfile().name()
                    + "» будет остановлено. При ошибке нового подключения автоматического возврата"
                    + " не будет.",
                "Переключиться");
    model.command(
        approved ? AgentProtocol.Command.CONNECT : AgentProtocol.Command.CANCEL,
        null,
        null,
        approved ? state.preparedToken() : operation,
        ignored -> {},
        this::error);
  }

  void stop() {
    var state = model.connection.get();
    model.command(
        state.busy() && state.operationId() != null
            ? AgentProtocol.Command.CANCEL
            : AgentProtocol.Command.DISCONNECT,
        null,
        null,
        state.operationId(),
        ignored -> {},
        this::error);
  }

  void delete(VpnProfile profile) {
    boolean active =
        model.connection.get().appliedProfile() != null
            && model.connection.get().appliedProfile().id().equals(profile.id());
    if (!Ui.confirm(
        owner,
        "Удалить «" + profile.name() + "»?",
        active
            ? "Сначала VPN будет отключён. Профиль и его секреты будут удалены."
            : "Профиль и его секреты будут удалены.",
        active ? "Отключить и удалить" : "Удалить")) {
      return;
    }
    if (active) {
      deleting = profile;
      model.command(
          AgentProtocol.Command.DISCONNECT,
          null,
          null,
          null,
          response -> {
            deletionRevision = response.snapshot().revision();
            changed(response.snapshot());
          },
          message -> {
            deleting = null;
            deletionRevision = -1;
            error(message);
          });
    } else {
      model.delete(profile, () -> {}, this::error);
    }
  }

  void export(VpnProfile profile) {
    if (!Ui.confirm(
        owner,
        "Экспорт профиля",
        "Будут включены название, тип VPN, сервер, сети и DNS.\n\n"
            + "Пароль, UUID, ключи, сертификаты и имя пользователя исключены.\n\n"
            + "Имена серверов, внутренние IP и домены могут быть чувствительными данными. После"
            + " импорта потребуется настроить доступ.",
        "Экспортировать")) {
      return;
    }
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Экспорт профиля");
    chooser.setInitialFileName("wisprail-profile.json");
    var file = chooser.showSaveDialog(owner);
    if (file != null) {
      model.background(
          () -> {
            new ProfileImport().export(file.toPath(), profile);
            return Boolean.TRUE;
          },
          ignored -> navigation.notifyUser("Профиль экспортирован без секретов"),
          this::error);
    }
  }

  private void importFile() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Импорт VPN · .ovpn или .json, до 1 МБ");
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter("VPN-профили", "*.ovpn", "*.json"));
    var file = chooser.showOpenDialog(owner);
    if (file == null) {
      return;
    }
    long attempt = ++importAttempt;
    Dialog<Void> progress = new Dialog<>();
    progress.initOwner(owner);
    progress.setHeaderText("Читаем файл…");
    ProgressIndicator indicator = new ProgressIndicator();
    indicator.setMaxSize(26, 26);
    progress
        .getDialogPane()
        .setContent(
            new HBox(12, indicator, Ui.label("Проверяем формат и параметры подключения", "muted")));
    progress.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
    progress.setResultConverter(button -> null);
    progress.setOnCloseRequest(event -> importAttempt++);
    Ui.style(progress);
    progress.show();
    model.background(
        () -> new ProfileImport().fromFile(file.toPath()),
        result -> {
          if (attempt != importAttempt) {
            return;
          }
          progress.setOnCloseRequest(null);
          progress.close();
          preview(result);
        },
        message -> {
          if (attempt != importAttempt) {
            return;
          }
          progress.setOnCloseRequest(null);
          progress.close();
          importError(message);
        });
  }

  void importLink() {
    Dialog<String> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Добавить по ссылке");
    dialog.setHeaderText("Добавление по ссылке");
    TextArea text = new TextArea();
    text.setWrapText(true);
    text.setPrefRowCount(4);
    text.setAccessibleText("VLESS-ссылка с данными доступа");
    text.setPromptText("vless://…");
    dialog
        .getDialogPane()
        .setContent(
            new VBox(
                12,
                Ui.label(
                    "Вставьте ссылку подключения, которую вы получили. Ссылка может содержать ключ"
                        + " доступа — не публикуйте её.",
                    "muted"),
                Ui.label("Ссылка VLESS", "field-label"),
                text));
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
    Ui.style(dialog);
    ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Продолжить");
    dialog.setResultConverter(button -> button == ButtonType.OK ? text.getText() : null);
    dialog
        .showAndWait()
        .ifPresent(
            value ->
                model.background(
                    () -> new ProfileImport().fromLink(value), this::preview, this::importError));
  }

  void preview(ProfileImport.Preview preview) {
    var profile = preview.profile();
    Dialog<Boolean> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setHeaderText("Проверьте импорт");
    VBox recognized =
        new VBox(
            importRow("Название", profile.name()),
            importRow("Тип", ConnectionsPane.protocol(profile)),
            importRow("Сервер", profile.server() + ":" + profile.port()),
            importRow(
                "Сети / DNS-домены",
                profile.networks().size() + " / " + profile.dns().domains().size()));
    recognized.getStyleClass().add("rules");
    var issues = new ProfileValidator().validate(profile, preview.secrets());
    VBox content =
        new VBox(
            14,
            Ui.label(
                "Перед сохранением проверьте, что было добавлено. Подключение не запускается.",
                "muted"),
            recognized);
    if (!issues.isEmpty()) {
      content
          .getChildren()
          .add(
              Ui.label(
                  "Нужно заполнить: "
                      + String.join(
                          "\n", issues.stream().map(ProfileValidator.Issue::message).toList()),
                  "notice"));
    }
    if (!preview.notes().isEmpty()) {
      content.getChildren().add(Ui.label(String.join("\n", preview.notes()), "notice"));
    }
    content
        .getChildren()
        .add(Ui.label("Данные доступа скрыты. Профиль ещё не сохранён и не подключён.", "small"));
    dialog.getDialogPane().setContent(content);
    ButtonType open = new ButtonType("Перейти к настройкам");
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, open);
    dialog.setResultConverter(button -> button == open);
    Ui.style(dialog);
    dialog.getDialogPane().setPrefWidth(610);
    dialog
        .showAndWait()
        .filter(Boolean.TRUE::equals)
        .ifPresent(ignored -> editor(profile, preview.secrets(), "Основное"));
  }

  void importError(String message) {
    if (Ui.confirm(
        owner,
        "Ошибка импорта",
        message + "\n\nТекущие подключения и сохранённые профили не изменены.",
        "Выбрать другой файл")) {
      importFile();
    }
  }

  private static HBox importRow(String name, String value) {
    Label label = Ui.label(name, "small");
    HBox.setHgrow(label, Priority.ALWAYS);
    label.setMaxWidth(Double.MAX_VALUE);
    HBox row = new HBox(16, label, Ui.label(value, "summary-name"));
    row.setAlignment(Pos.CENTER_LEFT);
    row.getStyleClass().add("rule-row");
    return row;
  }

  private void error(String message) {
    Ui.error(owner, message);
  }
}
