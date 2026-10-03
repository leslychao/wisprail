package app.wisprail.ui;

import app.wisprail.profile.DomainName;
import app.wisprail.profile.Ipv4Network;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.ProfileValidator;
import app.wisprail.profile.VpnProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** One workspace editor. The shell guards leaving it; saving never changes the active snapshot. */
final class ProfileEditor extends BorderPane {
  record Result(VpnProfile profile, ProfileSecrets secrets) {
    @Override
    public String toString() {
      return "EditorResult[redacted]";
    }
  }

  private final VpnProfile original;
  private final DesktopModel model;
  private final TextField name = new TextField();
  private final TextField server = new TextField();
  private final TextField port = new TextField();
  private final TextField probe = new TextField();
  private final TextField username = new TextField();
  private final PasswordField password = new PasswordField();
  private final PasswordField uuid = new PasswordField();
  private final TextField sni = new TextField();
  private final TextField publicKey = new TextField();
  private final TextField shortId = new TextField();
  private final TextField cipher = new TextField();
  private final TextField auth = new TextField();
  private final ComboBox<String> transport = choice("udp", "tcp");
  private final ComboBox<VpnProfile.Security> security = new ComboBox<>();
  private final ComboBox<String> fingerprint =
      choice("chrome", "firefox", "safari", "edge", "randomized");
  private final ComboBox<String> flow = choice("", "xtls-rprx-vision");
  private final ComboBox<String> wrap = choice("", "tls-auth", "tls-crypt", "tls-crypt-v2");
  private final ComboBox<String> direction = choice("", "0", "1");
  private final CheckBox remember = new CheckBox("Сохранить пароль в защищённом хранилище");
  private final ObservableList<String> networks = FXCollections.observableArrayList();
  private final TextField dns = new TextField();
  private final ObservableList<String> domains = FXCollections.observableArrayList();
  private final Label feedback = Ui.label("", "error-text");
  private final TabPane tabs = new TabPane();
  private String ca;
  private String certificate;
  private String privateKey;
  private String controlKey;
  private final String initialInput;
  private final Window owner;
  private boolean saving;

  ProfileEditor(
      Window owner,
      DesktopModel model,
      VpnProfile profile,
      ProfileSecrets secrets,
      BiConsumer<VpnProfile, ProfileSecrets> check,
      Consumer<Result> save,
      Runnable cancel) {
    this.owner = owner;
    this.model = model;
    original = profile;
    ca = secrets.ca();
    certificate = secrets.certificate();
    privateKey = secrets.privateKey();
    controlKey = secrets.controlKey();
    name.setText(profile.name());
    server.setText(profile.server());
    port.setText(Integer.toString(profile.port()));
    probe.setText(profile.probeUrl());
    networks.setAll(profile.networks());
    dns.setText(profile.dns().server());
    domains.setAll(profile.dns().domains());
    password.setText(secrets.password());
    uuid.setText(secrets.uuid());
    remember.setSelected(!secrets.password().isEmpty());
    security.getItems().setAll(VpnProfile.Security.values());
    name.setId("profile-name");
    server.setId("profile-server");
    port.setId("profile-port");
    dns.setId("profile-dns");
    name.setPromptText("Рабочий VPN");
    server.setPromptText("vpn.example.com");
    port.setPrefWidth(135);
    port.setMaxWidth(135);
    Node address = field("Адрес сервера *", server);
    HBox.setHgrow(address, Priority.ALWAYS);
    HBox endpoint = new HBox(16, address, field("Порт *", port));
    TitledPane advanced =
        new TitledPane(
            "Дополнительные параметры подключения",
            new VBox(
                16,
                field("HTTPS-адрес проверки (необязательно)", probe),
                Ui.label(
                    "Для VLESS нужен ответ корпоративного DNS или HTTPS-ресурса внутри выбранных"
                        + " сетей.",
                    "small")));
    advanced.setExpanded(false);
    advanced.getStyleClass().add("technical");
    Label type = Ui.label(ConnectionsPane.protocol(profile), "type-value");
    type.setMaxWidth(Double.MAX_VALUE);
    VBox main =
        form(
            field("Название *", name),
            Ui.label("Название видно в списке подключений.", "small"),
            field("Тип VPN", type),
            Ui.label("Для другого типа подключения создайте новый профиль.", "small"),
            endpoint,
            advanced);
    VBox access =
        profile.protocol() == VpnProfile.Protocol.OPENVPN
            ? openVpn(profile.openVpn())
            : vless(profile.vless());
    VBox routes =
        form(
            Ui.label("Что направлять через VPN", "section-heading"),
            Ui.label("Только эти сети и адреса будут использовать выбранный VPN.", "muted"),
            entries(networks, true),
            Ui.link("+ Добавить сеть", () -> editEntry(networks, -1, true)),
            Ui.label("Другие адреса остаются по текущим настройкам системы.", "small"));
    Label dnsStatus = Ui.label("", "small");
    Button addDns = Ui.button("", this::addDnsRoute);
    addDns.getStyleClass().add("small-button");
    Runnable updateDns = () -> updateDnsStatus(dnsStatus, addDns);
    dns.textProperty().addListener((observable, before, current) -> updateDns.run());
    networks.addListener((ListChangeListener<String>) change -> updateDns.run());
    updateDns.run();
    VBox resolver =
        form(
            Ui.label("DNS для выбранных доменов", "section-heading"),
            Ui.label("Например, чтобы открывались внутренние адреса компании.", "muted"),
            field("DNS-сервер этого VPN", dns),
            Ui.label("Один IPv4-адрес. Оставьте пустым, если отдельный DNS не нужен.", "small"),
            Ui.label("Домены для этого DNS", "field-label"),
            entries(domains, false),
            Ui.link("+ Добавить домен", () -> editEntry(domains, -1, false)),
            dnsStatus,
            addDns,
            Ui.label(
                "DNS определяет, у кого спросить IP. Сети определяют маршрут к этому IP.\n"
                    + "Остальные домены — через системный DNS.",
                "small"));
    tabs.getTabs()
        .addAll(
            tab("Основное", main),
            tab("Доступ", access),
            tab("Сети", routes),
            tab("DNS", resolver));
    tabs.setId("editor-tabs");
    tabs.getStyleClass().add("editor-tabs");
    var validate =
        Ui.link(
            "Проверить настройки",
            () -> {
              Result result = validateInput();
              if (result == null) {
                return;
              }
              if (!model.online.get()) {
                Alert resultDialog =
                    new Alert(
                        Alert.AlertType.INFORMATION,
                        "Название, сервер, порт, сети и DNS проверены.\n\n"
                            + "Проверка sing-box недоступна: системный компонент не подключён."
                            + " Сетевое соединение и доступность сервера не проверялись.",
                        ButtonType.OK);
                resultDialog.initOwner(owner);
                resultDialog.setHeaderText("Поля заполнены корректно");
                Ui.style(resultDialog);
                resultDialog.showAndWait();
                return;
              }
              check.accept(result.profile(), result.secrets());
            });
    Button saveButton =
        Ui.button(
            "Сохранить",
            () -> {
              if (validateInput() != null) {
                save.accept(value(true));
              }
            });
    saveButton.setId("save-profile");
    validate.setGraphic(Icons.of("check"));
    saveButton.setDefaultButton(true);
    saveButton.getStyleClass().add("primary");
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox footer = new HBox(10, validate, spacer, Ui.button("Отмена", cancel), saveButton);
    footer.setAlignment(Pos.CENTER_LEFT);
    footer.getStyleClass().add("editor-footer");
    Label title = Ui.label(profile.revision() == 0 ? "Новый VPN" : profile.name(), "title");
    VBox titleText = new VBox(6, title, Ui.label("Настройки профиля", "muted"));
    HBox.setHgrow(titleText, Priority.ALWAYS);
    HBox titleRow = new HBox(20, titleText);
    titleRow.setAlignment(Pos.CENTER_LEFT);
    var applied = model.connection.get().appliedProfile();
    if (applied != null && applied.id().equals(profile.id())) {
      titleRow.getChildren().add(Ui.label("● Сейчас подключён", "active-badge"));
      for (Tab tab : tabs.getTabs()) {
        var scroll = (ScrollPane) tab.getContent();
        VBox content = (VBox) scroll.getContent();
        Label message =
            Ui.label(
                "Изменения подключения применятся после переподключения. До сохранения работающий"
                    + " VPN не меняется.",
                "editor-notice-text");
        message.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(message, Priority.ALWAYS);
        HBox notice = new HBox(9, Icons.of("info"), message);
        notice.getStyleClass().add("editor-notice");
        content.getChildren().addFirst(notice);
      }
    }
    VBox heading = new VBox(14, Ui.link("‹ К подключениям", cancel), titleRow);
    heading.getStyleClass().add("editor-heading");
    setTop(heading);
    setCenter(tabs);
    feedback.setId("validation-error");
    feedback.setPadding(new Insets(0, 32, 12, 32));
    feedback.visibleProperty().bind(feedback.textProperty().isNotEmpty());
    feedback.managedProperty().bind(feedback.visibleProperty());
    setBottom(new VBox(feedback, footer));
    setOnKeyPressed(
        event -> {
          if (event.getCode() == KeyCode.ESCAPE) {
            cancel.run();
            event.consume();
          } else if (event.isShortcutDown() && event.getCode() == KeyCode.S) {
            saveButton.fire();
            event.consume();
          }
        });
    initialInput = inputFingerprint();
  }

  void selectTab(String title) {
    tabs.getTabs().stream()
        .filter(tab -> tab.getText().equals(title))
        .findFirst()
        .ifPresent(tab -> tabs.getSelectionModel().select(tab));
  }

  boolean canLeave() {
    if (saving) {
      return false;
    }
    if (initialInput.equals(inputFingerprint())) {
      return true;
    }
    return Ui.confirm(
        owner,
        "Есть несохранённые изменения",
        "Изменения будут потеряны. Продолжить редактирование или не сохранять?",
        "Не сохранять");
  }

  void setSaving(boolean value) {
    saving = value;
    setDisable(value);
  }

  private static VBox form(Node... children) {
    VBox form = new VBox(19, children);
    form.setMaxWidth(720);
    form.setPadding(new Insets(22, 32, 24, 32));
    return form;
  }

  static VpnProfile blank(VpnProfile.Protocol protocol) {
    return new VpnProfile(
        1,
        UUID.randomUUID(),
        0,
        "",
        "",
        protocol == VpnProfile.Protocol.OPENVPN ? 1194 : 443,
        protocol,
        protocol == VpnProfile.Protocol.OPENVPN ? VpnProfile.OpenVpn.defaults() : null,
        protocol == VpnProfile.Protocol.VLESS ? VpnProfile.Vless.defaults() : null,
        List.of(),
        VpnProfile.Dns.empty(),
        "",
        "");
  }

  private Node entries(ObservableList<String> values, boolean network) {
    VBox rows = new VBox(0);
    rows.getStyleClass().add("rules");
    Runnable refresh =
        () -> {
          rows.getChildren().clear();
          if (values.isEmpty()) {
            rows.getChildren()
                .add(
                    Ui.label(
                        network ? "Сети пока не добавлены" : "Домены пока не добавлены",
                        "empty-rules"));
          }
          for (int index = 0; index < values.size(); index++) {
            int position = index;
            String value = values.get(index);
            String hint = "Включая поддомены";
            if (network) {
              hint = value.endsWith("/32") ? "Один адрес" : "Сеть";
            }
            Label text = Ui.label(value, "addresses");
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row =
                new HBox(
                    12,
                    text,
                    Ui.label(hint, "small"),
                    Ui.iconButton(
                        "edit", "Изменить " + value, () -> editEntry(values, position, network)),
                    Ui.iconButton("close", "Удалить " + value, () -> values.remove(position)));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("rule-row");
            rows.getChildren().add(row);
          }
        };
    values.addListener((ListChangeListener<String>) change -> refresh.run());
    refresh.run();
    return rows;
  }

  private void editEntry(ObservableList<String> values, int index, boolean network) {
    Dialog<String> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle(network ? "Добавление сети" : "Добавление домена");
    String kind = network ? "сеть" : "домен";
    dialog.setHeaderText((index < 0 ? "Добавить " : "Изменить ") + kind);
    TextField entry = new TextField(index < 0 ? "" : values.get(index));
    entry.setPromptText(network ? "10.20.0.0/16" : "company.local");
    entry.setAccessibleText(network ? "IP-адрес или сеть" : "Домен");
    Label error = Ui.label("", "error-text");
    dialog
        .getDialogPane()
        .setContent(
            new VBox(
                12,
                Ui.label(
                    network
                        ? "Укажите сеть или один IP-адрес назначения."
                        : "Все поддомены тоже будут использовать DNS этого VPN.",
                    "muted"),
                Ui.label(network ? "IP-адрес или сеть" : "Домен", "field-label"),
                entry,
                Ui.label(
                    network
                        ? "Один адрес будет добавлен с маской /32."
                        : "Включая поддомены. Без схемы, пути и *.",
                    "small"),
                error));
    ButtonType add =
        new ButtonType(index < 0 ? "Добавить" : "Изменить", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, add);
    Ui.style(dialog);
    dialog
        .getDialogPane()
        .lookupButton(add)
        .addEventFilter(
            ActionEvent.ACTION,
            event -> {
              try {
                String input = entry.getText().strip();
                String value =
                    network ? Ipv4Network.parse(input).toString() : DomainName.normalize(input);
                if (!input.equals(value)) {
                  entry.setText(value);
                  error.setText("Будет добавлено: " + value + ". Подтвердите изменение.");
                  event.consume();
                  return;
                }
                if (values.contains(value) && (index < 0 || !values.get(index).equals(value))) {
                  throw new IllegalArgumentException("Значение уже добавлено");
                }
                if (network) {
                  var candidate = new ArrayList<>(values);
                  if (index >= 0) {
                    candidate.remove(index);
                  }
                  candidate.add(value);
                  if (Ipv4Network.coversInternet(
                      candidate.stream().map(Ipv4Network::parse).toList())) {
                    throw new IllegalArgumentException("Полный охват IPv4 через VPN запрещён");
                  }
                }
              } catch (IllegalArgumentException exception) {
                error.setText(exception.getMessage());
                event.consume();
              }
            });
    dialog.setResultConverter(button -> button == add ? entry.getText().strip() : null);
    dialog
        .showAndWait()
        .ifPresent(
            value -> {
              if (index < 0) {
                values.add(value);
              } else {
                values.set(index, value);
              }
            });
  }

  private void updateDnsStatus(Label label, Button add) {
    add.setVisible(false);
    add.setManaged(false);
    if (dns.getText().isBlank()) {
      label.setText("");
      return;
    }
    try {
      long address = Ipv4Network.parseAddress(dns.getText().strip());
      boolean covered =
          networks.stream().map(Ipv4Network::parse).anyMatch(network -> network.contains(address));
      if (covered) {
        label.setText(
            "По настройкам адрес DNS входит в сети этого VPN. Его доступность ещё не проверена.");
      } else {
        label.setText(
            "DNS "
                + dns.getText().strip()
                + " не входит в выбранные сети. Ему нужен отдельный маршрут через VPN.");
        add.setText("Добавить " + dns.getText().strip() + "/32 в сети");
        add.setVisible(true);
        add.setManaged(true);
      }
    } catch (IllegalArgumentException exception) {
      label.setText("Укажите корректный IPv4-адрес DNS и сети.");
    }
  }

  private VBox openVpn(VpnProfile.OpenVpn settings) {
    transport.setValue(settings.transport());
    username.setText(settings.username());
    sni.setText(settings.serverName());
    cipher.setText(settings.cipher());
    auth.setText(settings.auth());
    wrap.setValue(settings.controlWrap());
    direction.setValue(settings.keyDirection());
    TitledPane advanced =
        new TitledPane(
            "Дополнительные параметры подключения",
            new VBox(
                16,
                field("Транспорт", transport),
                field("Имя сервера в сертификате", sni),
                field("Шифр канала", cipher),
                field("Аутентификация пакетов", auth),
                field("Защита управляющего канала", wrap),
                field("Направление tls-auth", direction),
                material("Ключ защиты канала", controlKey, value -> controlKey = value)));
    advanced.setExpanded(false);
    advanced.getStyleClass().add("technical");
    return form(
        Ui.label("Вход в VPN", "section-title"),
        field("Имя пользователя", username),
        field("Пароль VPN", password),
        Ui.label("Не пароль компьютера. В этом профиле не вводите системные секреты.", "small"),
        remember,
        Ui.label("Сертификаты и ключи", "section-title"),
        material("Сертификат центра CA", ca, value -> ca = value),
        material("Сертификат пользователя", certificate, value -> certificate = value),
        material("Закрытый ключ пользователя", privateKey, value -> privateKey = value),
        advanced);
  }

  private VBox vless(VpnProfile.Vless settings) {
    security.setValue(settings.security());
    sni.setText(settings.serverName());
    publicKey.setText(settings.publicKey());
    shortId.setText(settings.shortId());
    fingerprint.setValue(settings.fingerprint());
    flow.setValue(settings.flow());
    VBox reality =
        new VBox(
            16,
            field("Public key Reality", publicKey),
            field("Short ID", shortId),
            field("Fingerprint", fingerprint));
    reality.visibleProperty().bind(security.valueProperty().isEqualTo(VpnProfile.Security.REALITY));
    reality.managedProperty().bind(reality.visibleProperty());
    return form(
        Ui.label("Данные доступа VLESS", "section-heading"),
        field("UUID", uuid),
        field("Защита", security),
        field("Имя сервера TLS (SNI)", sni),
        field("Flow", flow),
        reality,
        Ui.label("WebSocket, gRPC и отключение проверки TLS не поддерживаются.", "muted"));
  }

  private Node material(String title, String initial, Consumer<String> assign) {
    Label status = Ui.label(initial.isBlank() ? "Не выбран" : "Материал загружен", "muted");
    Button browse =
        Ui.button(
            "Выбрать файл…",
            () -> {
              FileChooser chooser = new FileChooser();
              chooser.setTitle(title);
              var file = chooser.showOpenDialog(owner);
              if (file != null) {
                model.background(
                    () -> {
                      if (Files.size(file.toPath()) > 1024 * 1024) {
                        throw new IllegalArgumentException("Файл превышает 1 МБ");
                      }
                      return Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    },
                    value -> {
                      assign.accept(value);
                      status.setText(file.getName());
                    },
                    feedback::setText);
              }
            });
    VBox description = new VBox(5, Ui.label(title, "field-label"), status);
    HBox.setHgrow(description, Priority.ALWAYS);
    HBox row =
        new HBox(
            8,
            Icons.of("file"),
            description,
            browse,
            Ui.iconButton(
                "close",
                "Очистить " + title,
                () -> {
                  assign.accept("");
                  status.setText("Не выбран");
                }));
    row.setAlignment(Pos.CENTER_LEFT);
    row.getStyleClass().add("rule-row");
    return row;
  }

  private Result validateInput() {
    try {
      Result result = value(false);
      var validator = new ProfileValidator();
      var issues = validator.validate(result.profile(), result.secrets());
      for (Tab tab : tabs.getTabs()) {
        tab.getStyleClass().remove("invalid-tab");
      }
      for (Node control : List.of(name, server, port, probe, uuid, sni, dns)) {
        control.getStyleClass().remove("invalid-field");
      }
      for (ProfileValidator.Issue issue : issues) {
        tabs.getTabs().get(issueTab(issue.field())).getStyleClass().add("invalid-tab");
        issueControl(issue.field()).getStyleClass().add("invalid-field");
      }
      if (!issues.isEmpty()) {
        tabs.getSelectionModel().select(issueTab(issues.getFirst().field()));
        Node control = issueControl(issues.getFirst().field());
        for (Node parent = control.getParent(); parent != null; parent = parent.getParent()) {
          if (parent instanceof TitledPane accordion) {
            accordion.setExpanded(true);
          }
        }
        Platform.runLater(control::requestFocus);
        feedback.setText(issues.getFirst().message());
        return null;
      }
      feedback.setText("");
      return result;
    } catch (IllegalArgumentException exception) {
      if (exception instanceof NumberFormatException) {
        tabs.getSelectionModel().select(0);
        port.getStyleClass().add("invalid-field");
        port.requestFocus();
      }
      feedback.setText(
          exception instanceof NumberFormatException
              ? "Введите корректный порт"
              : exception.getMessage());
      return null;
    }
  }

  private static int issueTab(String field) {
    return switch (field) {
      case "access", "uuid", "sni" -> 1;
      case "networks" -> 2;
      case "dns" -> 3;
      default -> 0;
    };
  }

  private Node issueControl(String field) {
    return switch (field) {
      case "server" -> server;
      case "port" -> port;
      case "probe" -> probe;
      case "uuid" -> uuid;
      case "sni" -> sni;
      case "dns" -> dns;
      case "access", "networks" -> tabs;
      default -> name;
    };
  }

  private Result value(boolean saving) {
    var openVpn =
        original.protocol() == VpnProfile.Protocol.OPENVPN
            ? new VpnProfile.OpenVpn(
                transport.getValue(),
                username.getText().strip(),
                sni.getText().strip(),
                cipher.getText().strip(),
                auth.getText().strip(),
                wrap.getValue(),
                direction.getValue())
            : null;
    var vless =
        original.protocol() == VpnProfile.Protocol.VLESS
            ? new VpnProfile.Vless(
                security.getValue(),
                sni.getText().strip(),
                publicKey.getText().strip(),
                shortId.getText().strip(),
                fingerprint.getValue(),
                flow.getValue())
            : null;
    var profile =
        new VpnProfile(
            1,
            original.id(),
            original.revision(),
            name.getText().strip(),
            server.getText().strip(),
            Integer.parseInt(port.getText().strip()),
            original.protocol(),
            openVpn,
            vless,
            List.copyOf(networks),
            new VpnProfile.Dns(
                dns.getText().strip(), domains.stream().map(DomainName::normalize).toList()),
            probe.getText().strip(),
            original.secretReference());
    var secrets =
        new ProfileSecrets(
            saving && !remember.isSelected() ? "" : password.getText(),
            uuid.getText().strip(),
            ca,
            certificate,
            privateKey,
            controlKey);
    return new Result(profile, secrets);
  }

  private void addDnsRoute() {
    try {
      long address = Ipv4Network.parseAddress(dns.getText().strip());
      if (networks.stream()
          .map(Ipv4Network::parse)
          .noneMatch(network -> network.contains(address))) {
        networks.add(Ipv4Network.formatAddress(address) + "/32");
      }
    } catch (IllegalArgumentException exception) {
      feedback.setText("Сначала исправьте IPv4-адрес DNS и список сетей");
    }
  }

  private String inputFingerprint() {
    return String.join(
        "\u0000",
        name.getText(),
        server.getText(),
        port.getText(),
        probe.getText(),
        username.getText(),
        password.getText(),
        uuid.getText(),
        sni.getText(),
        publicKey.getText(),
        shortId.getText(),
        cipher.getText(),
        auth.getText(),
        String.valueOf(transport.getValue()),
        String.valueOf(security.getValue()),
        String.valueOf(fingerprint.getValue()),
        String.valueOf(flow.getValue()),
        String.valueOf(wrap.getValue()),
        String.valueOf(direction.getValue()),
        String.join("\n", networks),
        dns.getText(),
        String.join("\n", domains),
        ca,
        certificate,
        privateKey,
        controlKey,
        Boolean.toString(remember.isSelected()));
  }

  private static Node field(String name, Node control) {
    Label label = Ui.label(name, "field-label");
    label.setLabelFor(control);
    return new VBox(6, label, control);
  }

  private static Tab tab(String name, Node content) {
    Tab tab = new Tab(name, Ui.scroll(content));
    tab.setClosable(false);
    return tab;
  }

  private static ComboBox<String> choice(String... items) {
    ComboBox<String> combo = new ComboBox<>();
    combo.getItems().addAll(items);
    combo.setValue(items[0]);
    return combo;
  }
}
