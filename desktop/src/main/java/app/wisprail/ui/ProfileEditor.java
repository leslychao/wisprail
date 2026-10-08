package app.wisprail.ui;

import app.wisprail.profile.OpenVpnSettings;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.TlsMode;
import app.wisprail.profile.VlessSettings;
import app.wisprail.profile.VpnSettings;
import app.wisprail.profile.VpnType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** One unsaved form; changes do not reach storage or the network until its owner accepts them. */
final class ProfileEditor extends BorderPane {
  record Draft(Profile profile, ProfileSecrets secrets) {}

  private final Profile original;
  private final ProfileSecrets originalSecrets;
  private final TextField name = new TextField();
  private final TextField server = new TextField();
  private final TextField port = new TextField();
  private final ComboBox<VpnType> type = new ComboBox<>();
  private final TextField healthUrl = new TextField();
  private final ComboBox<String> transport = new ComboBox<>();
  private final TextField username = new TextField();
  private final SecretField password = new SecretField("пароль VPN", "Спрашивать при подключении");
  private final CheckBox askPassword = new CheckBox("Спрашивать пароль при подключении");
  private final SecretField privateKeyPassword = new SecretField("пароль ключа", "Если требуется");
  private final TextField cipher = new TextField();
  private final TextField authDigest = new TextField();
  private final TextField tlsServerName = new TextField();
  private final ComboBox<Integer> keyDirection = new ComboBox<>();
  private final SecretField uuid = new SecretField("ключ доступа UUID", "Из ссылки подключения");
  private final ComboBox<TlsMode> security = new ComboBox<>();
  private final TextField sni = new TextField();
  private final TextField publicKey = new TextField();
  private final TextField shortId = new TextField();
  private final ComboBox<String> fingerprint = new ComboBox<>();
  private final ComboBox<String> flow = new ComboBox<>();
  private final ObservableList<String> networks = FXCollections.observableArrayList();
  private final ObservableList<String> domains = FXCollections.observableArrayList();
  private final TextField dns = new TextField();
  private final VBox dnsNotice = new VBox(8);
  private final Label error = Ui.label("", "error-text");
  private final VBox form = new VBox(19);
  private final List<ToggleButton> tabs = new ArrayList<>();
  private final BiConsumer<String, Consumer<String>> chooseCertificate;
  private final Consumer<ProfileEditor> addNetwork;
  private final Consumer<ProfileEditor> addDomain;
  private String caCertificate = "";
  private String clientCertificate = "";
  private String privateKey = "";
  private String tlsAuthKey = "";
  private String tlsCryptKey = "";
  private int currentTab;

  ProfileEditor(
      Profile profile,
      ProfileSecrets secrets,
      boolean active,
      Consumer<Draft> save,
      Consumer<Draft> validate,
      Runnable cancel,
      BiConsumer<String, Consumer<String>> chooseCertificate,
      Consumer<ProfileEditor> addNetwork,
      Consumer<ProfileEditor> addDomain) {
    original = profile;
    originalSecrets = secrets;
    this.chooseCertificate = chooseCertificate;
    this.addNetwork = addNetwork;
    this.addDomain = addDomain;
    name.setText(profile.name());
    name.setPromptText("Например, рабочий VPN");
    server.setText(profile.server());
    server.setPromptText("vpn.example.com");
    port.setText(Integer.toString(profile.port()));
    type.getItems().setAll(VpnType.values());
    type.setValue(profile.type());
    type.setDisable(profile.revision() > 0);
    type.setButtonCell(typeCell());
    type.setCellFactory(view -> typeCell());
    transport.getItems().setAll("udp", "tcp");
    transport.setValue("udp");
    keyDirection.getItems().setAll(-1, 0, 1);
    keyDirection.setValue(-1);
    security.getItems().setAll(TlsMode.values());
    security.setValue(TlsMode.TLS);
    fingerprint
        .getItems()
        .setAll(
            "",
            "chrome",
            "firefox",
            "safari",
            "ios",
            "android",
            "edge",
            "random",
            "randomized",
            "360",
            "qq");
    fingerprint.setValue("chrome");
    flow.getItems().setAll("", "xtls-rprx-vision");
    flow.setValue("");
    askPassword.setSelected(true);
    password.disableProperty().bind(askPassword.selectedProperty());
    if (profile.settings() instanceof OpenVpnSettings settings) {
      transport.setValue(settings.transport());
      username.setText(settings.username());
      askPassword.setSelected(settings.askPassword());
      caCertificate = settings.caCertificate();
      clientCertificate = settings.clientCertificate();
      cipher.setText(settings.cipher());
      authDigest.setText(settings.authDigest());
      tlsServerName.setText(settings.tlsServerName());
      keyDirection.setValue(settings.tlsKeyDirection());
    } else if (profile.settings() instanceof VlessSettings settings) {
      security.setValue(settings.security());
      sni.setText(settings.serverName());
      publicKey.setText(settings.publicKey());
      shortId.setText(settings.shortId());
      fingerprint.setValue(settings.fingerprint());
      flow.setValue(settings.flow());
    }
    password.setText(secrets.password());
    privateKey = secrets.privateKey();
    privateKeyPassword.setText(secrets.privateKeyPassword());
    tlsAuthKey = secrets.tlsAuthKey();
    tlsCryptKey = secrets.tlsCryptKey();
    uuid.setText(secrets.uuid());
    networks.setAll(profile.networks());
    domains.setAll(profile.domains());
    dns.setText(profile.dns());
    healthUrl.setText(profile.healthUrl());
    healthUrl.setPromptText("https://internal.example.com/health");
    type.valueProperty()
        .addListener(
            (observable, oldValue, newValue) -> {
              port.setText(newValue == VpnType.OPENVPN ? "1194" : "443");
              showTab(currentTab);
            });
    security.valueProperty().addListener((observable, oldValue, newValue) -> showTab(currentTab));
    dns.textProperty().addListener((observable, oldValue, newValue) -> updateDnsNotice());
    networks.addListener((ListChangeListener<String>) change -> updateDnsNotice());

    ToggleGroup group = new ToggleGroup();
    HBox tabBar = new HBox(4);
    tabBar.getStyleClass().add("tabs");
    String[] titles = {"Основное", "Доступ", "Сети", "DNS"};
    for (int index = 0; index < titles.length; index++) {
      int tabIndex = index;
      ToggleButton button = new ToggleButton(titles[index]);
      button.getStyleClass().add("tab-button");
      button.setToggleGroup(group);
      button.setOnAction(event -> showTab(tabIndex));
      tabs.add(button);
      tabBar.getChildren().add(button);
    }
    Label title = Ui.heading(profile.revision() == 0 ? "Новый VPN" : profile.name());
    VBox header =
        new VBox(
            14,
            Ui.button("‹ К подключениям", "link", cancel),
            title,
            Ui.label("Настройки профиля", "hint"),
            tabBar);
    header.getStyleClass().add("editor-header");
    if (active) {
      header
          .getChildren()
          .add(
              Ui.notice(
                  "Изменения подключения применятся после переподключения. "
                      + "До сохранения работающий VPN не меняется.",
                  "information"));
    }
    setTop(header);
    form.setMaxWidth(720);
    VBox content = new VBox(12, error, form);
    error.setVisible(false);
    error.setManaged(false);
    content.setPadding(new Insets(22, 32, 22, 32));
    setCenter(Ui.scroll(content));
    HBox footer =
        Ui.row(
            Ui.button("✓ Проверить настройки", "link", () -> validate.accept(draft())),
            Ui.spacer(),
            Ui.button("Отмена", "button", cancel),
            Ui.button("Сохранить", "primary", () -> save.accept(draft())));
    footer.getStyleClass().add("editor-footer");
    setBottom(footer);
    showTab(0);
  }

  private static ListCell<VpnType> typeCell() {
    return new ListCell<>() {
      @Override
      protected void updateItem(VpnType item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? "" : item == VpnType.OPENVPN ? "OpenVPN" : "VLESS");
      }
    };
  }

  Draft draft() {
    int parsedPort;
    try {
      parsedPort = Integer.parseInt(port.getText().trim());
    } catch (NumberFormatException exception) {
      parsedPort = 0;
    }
    VpnSettings settings;
    ProfileSecrets secrets;
    if (type.getValue() == VpnType.OPENVPN) {
      settings =
          new OpenVpnSettings(
              transport.getValue(),
              username.getText().trim(),
              askPassword.isSelected(),
              caCertificate,
              clientCertificate,
              cipher.getText().trim(),
              authDigest.getText().trim(),
              tlsServerName.getText().trim(),
              keyDirection.getValue());
      secrets =
          new ProfileSecrets(
              askPassword.isSelected() ? "" : password.getText(),
              privateKey,
              privateKeyPassword.getText(),
              tlsAuthKey,
              tlsCryptKey,
              "");
    } else {
      settings =
          new VlessSettings(
              security.getValue(),
              sni.getText().trim(),
              publicKey.getText().trim(),
              shortId.getText().trim(),
              fingerprint.getValue(),
              flow.getValue());
      secrets = new ProfileSecrets("", "", "", "", "", uuid.getText().trim());
    }
    Profile profile =
        new Profile(
            original.id(),
            original.revision(),
            name.getText().trim(),
            server.getText().trim(),
            parsedPort,
            settings,
            networks,
            dns.getText().trim(),
            domains,
            healthUrl.getText().trim(),
            original.secretRef());
    return new Draft(profile, secrets);
  }

  boolean isDirty() {
    Draft draft = draft();
    return !draft.profile().equals(original) || !draft.secrets().equals(originalSecrets);
  }

  boolean secretsChanged() {
    return !draft().secrets().equals(originalSecrets);
  }

  void showTab(int index) {
    currentTab = index;
    tabs.get(index).setSelected(true);
    form.getChildren().clear();
    switch (index) {
      case 0 -> mainFields();
      case 1 -> accessFields();
      case 2 -> networkFields();
      case 3 -> dnsFields();
      default -> throw new IllegalArgumentException("Unknown editor tab");
    }
  }

  void showError(String field, String message) {
    int tab;
    if (field.startsWith("network")) {
      tab = 2;
    } else if (field.startsWith("dns") || field.startsWith("domain")) {
      tab = 3;
    } else if (List.of(
            "password",
            "privateKey",
            "username",
            "tlsAuthKey",
            "uuid",
            "access",
            "transport",
            "caCertificate",
            "clientCertificate",
            "cipher",
            "authDigest",
            "tlsKeyDirection",
            "security",
            "serverName",
            "flow",
            "fingerprint",
            "publicKey",
            "shortId")
        .contains(field)) {
      tab = 1;
    } else {
      tab = 0;
    }
    showTab(tab);
    error.setText(message);
    error.setVisible(true);
    error.setManaged(true);
    Node focus =
        switch (field) {
          case "name" -> name;
          case "server" -> server;
          case "port" -> port;
          case "dns" -> dns;
          case "healthUrl" -> healthUrl;
          default -> tabs.get(tab);
        };
    focus.requestFocus();
  }

  private void mainFields() {
    VBox serverField = Ui.field("Адрес сервера *", server, "Домен или IPv4 без https:// и пути.");
    HBox.setHgrow(serverField, Priority.ALWAYS);
    port.setPrefColumnCount(6);
    port.setMaxWidth(135);
    form.getChildren()
        .addAll(
            Ui.field("Название *", name, "Название видно в списке подключений."),
            Ui.field(
                "Тип VPN",
                type,
                original.revision() > 0
                    ? "Для другого типа подключения создайте новый профиль."
                    : ""),
            Ui.row(serverField, Ui.field("Порт *", port, "1–65535")),
            Ui.notice("Сюда нужен адрес VPN-сервера, не адрес корпоративного DNS.", ""));
    VBox advanced = new VBox(19);
    if (type.getValue() == VpnType.OPENVPN) {
      advanced.getChildren().add(Ui.field("Транспорт", transport, ""));
    } else {
      advanced.getChildren().add(Ui.label("Транспорт: TCP", "field-label"));
    }
    advanced
        .getChildren()
        .add(
            Ui.field(
                "HTTPS-ресурс для проверки готовности",
                healthUrl,
                "Необязательно при наличии корпоративного DNS. Только HTTPS внутри выбранных"
                    + " IPv4-сетей. Публичный ресурс автоматически не используется."));
    TitledPane extra = new TitledPane("Дополнительные параметры подключения", advanced);
    extra.setExpanded(false);
    form.getChildren().add(extra);
  }

  private void accessFields() {
    if (type.getValue() == VpnType.VLESS) {
      form.getChildren()
          .addAll(
              Ui.label("Доступ VLESS", "section-label"),
              Ui.field("Ключ доступа (UUID) *", uuid, "Обычно заполняется из ссылки подключения."),
              Ui.field("Защита подключения", security, "Проверка сертификата TLS не отключается."),
              Ui.field("Имя сервера TLS (SNI)", sni, ""));
      if (security.getValue() == TlsMode.REALITY) {
        form.getChildren()
            .addAll(
                Ui.field("Открытый ключ Reality *", publicKey, ""),
                Ui.field("Short ID", shortId, "Шестнадцатеричное значение из настроек сервера."));
      }
      form.getChildren()
          .addAll(
              Ui.field("TLS fingerprint", fingerprint, ""),
              Ui.field("Flow", flow, "Должен соответствовать серверу."));
      return;
    }
    form.getChildren()
        .addAll(
            Ui.label("Вход в VPN", "section-label"),
            Ui.field("Имя пользователя", username, ""),
            Ui.field("Пароль VPN", password, "Не пароль от компьютера."),
            askPassword,
            Ui.label("Сертификаты и ключ", "section-label"),
            certificateRow("Сертификат центра (CA)", caCertificate, value -> caCertificate = value),
            certificateRow(
                "Сертификат пользователя", clientCertificate, value -> clientCertificate = value),
            certificateRow("Закрытый ключ", privateKey, value -> privateKey = value),
            Ui.field("Пароль закрытого ключа", privateKeyPassword, "Если ключ зашифрован."));
    VBox advanced =
        new VBox(
            15,
            certificateRow("Ключ tls-auth", tlsAuthKey, value -> tlsAuthKey = value),
            certificateRow("Ключ tls-crypt", tlsCryptKey, value -> tlsCryptKey = value),
            Ui.field(
                "Направление tls-auth", keyDirection, "−1: не задано; 0 или 1: как на сервере."),
            Ui.field("Шифр", cipher, "Если явно задан в профиле."),
            Ui.field("Алгоритм аутентификации", authDigest, ""),
            Ui.field("Имя в сертификате сервера", tlsServerName, ""));
    TitledPane extra = new TitledPane("Дополнительные параметры OpenVPN", advanced);
    extra.setExpanded(false);
    form.getChildren().add(extra);
    form.getChildren()
        .add(
            Ui.notice(
                "Секреты сохраняются в защищённом хранилище текущего пользователя. "
                    + "Содержимое закрытого ключа не отображается.",
                "information"));
  }

  private HBox certificateRow(String title, String value, Consumer<String> update) {
    VBox text =
        new VBox(
            4,
            Ui.label(title, "field-label"),
            Ui.label(value.isBlank() ? "Не выбран" : "Загружен", "hint"));
    HBox.setHgrow(text, Priority.ALWAYS);
    Button choose =
        Ui.button(
            value.isBlank() ? "Выбрать" : "Заменить",
            "small-button",
            () ->
                chooseCertificate.accept(
                    title,
                    selected -> {
                      update.accept(selected);
                      showTab(currentTab);
                    }));
    HBox row = Ui.row(text, choose);
    row.getStyleClass().add("rule-row");
    if (!value.isBlank()) {
      row.getChildren()
          .add(
              Ui.iconButton(
                  "close",
                  "Удалить " + title,
                  () -> {
                    update.accept("");
                    showTab(currentTab);
                  }));
    }
    return row;
  }

  private void networkFields() {
    form.getChildren()
        .addAll(
            Ui.label("Что направлять через VPN", "subheading"),
            Ui.label("Добавьте IP-адреса или сети, которым нужен этот VPN.", "hint"),
            ruleList(networks, true),
            Ui.button("+ Добавить сеть", "small-button", () -> addNetwork.accept(this)),
            Ui.notice(
                "Другие адреса остаются по текущим настройкам системы. "
                    + "Например, 10.20.0.0/16 — сеть, а 10.20.5.17/32 — один адрес.",
                ""),
            Ui.notice(
                "Домены на вкладке DNS выбирают DNS-сервер, "
                    + "но сами по себе не добавляют маршруты к сайтам.",
                "information"));
  }

  private void dnsFields() {
    updateDnsNotice();
    form.getChildren()
        .addAll(
            Ui.label("DNS для выбранных доменов", "subheading"),
            Ui.label("Например, чтобы открывались внутренние адреса компании.", "hint"),
            Ui.field(
                "DNS-сервер этого VPN",
                dns,
                "Один IPv4-адрес. Оставьте пустым, если отдельный DNS не нужен."),
            Ui.label("Домены для этого DNS · включая поддомены", "field-label"),
            ruleList(domains, false),
            Ui.button("+ Добавить домен", "small-button", () -> addDomain.accept(this)),
            dnsNotice,
            Ui.notice(
                "Остальные домены — через системный DNS. Настройка DNS "
                    + "не означает, что весь трафик к этим доменам пойдёт через VPN: "
                    + "IP назначения должен входить в выбранные сети.",
                ""));
  }

  private ListView<String> ruleList(ObservableList<String> values, boolean network) {
    ListView<String> list = new ListView<>(values);
    list.setPrefHeight(Math.min(220, Math.max(85, values.size() * 49 + 5)));
    list.setPlaceholder(Ui.label(network ? "Сетей пока нет" : "Домены не добавлены", "hint"));
    list.setCellFactory(
        view ->
            new ListCell<>() {
              @Override
              protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                  setGraphic(null);
                  return;
                }
                Label text = Ui.label(item, "mono");
                HBox.setHgrow(text, Priority.ALWAYS);
                HBox row =
                    Ui.row(
                        text,
                        Ui.label(
                            network
                                ? item.endsWith("/32") ? "Один адрес" : "Сеть"
                                : "Включая поддомены",
                            "hint"),
                        Ui.iconButton("close", "Удалить " + item, () -> values.remove(item)));
                if (network) {
                  row.getChildren()
                      .add(
                          1,
                          Ui.iconButton(
                              "edit",
                              "Изменить " + item,
                              () -> {
                                list.getSelectionModel().select(item);
                                editingNetwork = item;
                                addNetwork.accept(ProfileEditor.this);
                              }));
                }
                setGraphic(row);
              }
            });
    return list;
  }

  private String editingNetwork = "";

  String editingNetwork() {
    String editing = editingNetwork;
    editingNetwork = "";
    return editing;
  }

  void putNetwork(String previous, String canonical) {
    int index = networks.indexOf(previous);
    if (index >= 0) {
      networks.set(index, canonical);
    } else if (!networks.contains(canonical)) {
      networks.add(canonical);
    }
  }

  void putDomain(String domain) {
    if (!domains.contains(domain)) {
      domains.add(domain);
    }
  }

  private void updateDnsNotice() {
    dnsNotice.getChildren().clear();
    String address = dns.getText().trim();
    if (address.isEmpty()) {
      return;
    }
    try {
      app.wisprail.profile.Ipv4Cidr host = app.wisprail.profile.Ipv4Cidr.parse(address);
      boolean covered =
          networks.stream()
              .map(app.wisprail.profile.Ipv4Cidr::parse)
              .anyMatch(network -> network.contains(address));
      if (covered) {
        dnsNotice
            .getChildren()
            .add(
                Ui.notice(
                    "По настройкам адрес DNS входит в сети этого VPN. "
                        + "Его доступность ещё не проверена.",
                    ""));
      } else {
        VBox warning =
            Ui.notice(
                "DNS "
                    + address
                    + " не входит в выбранные сети. "
                    + "В этой схеме ему нужен отдельный маршрут через VPN.",
                "warning");
        warning
            .getChildren()
            .add(
                Ui.button(
                    "Добавить " + host.canonical() + " в сети",
                    "link",
                    () -> putNetwork("", host.canonical())));
        dnsNotice.getChildren().add(warning);
      }
    } catch (IllegalArgumentException exception) {
      dnsNotice.getChildren().add(Ui.notice("Введите корректный IPv4-адрес DNS-сервера.", "error"));
    }
  }
}
