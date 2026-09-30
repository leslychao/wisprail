package app.wisprail.ui;

import app.wisprail.agent.AgentProtocol;
import app.wisprail.connection.EventJournal;
import app.wisprail.connection.NetworkRuntime;
import app.wisprail.profile.DomainName;
import app.wisprail.profile.Ipv4Network;
import app.wisprail.profile.VpnProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class JournalPane extends BorderPane {
  private final DesktopModel model;
  private final Window owner;
  private final VBox results = new VBox(12);
  private List<NetworkRuntime.Check> checks = List.of();
  private final TabPane tabs = new TabPane();
  private final ComboBox<String> level = new ComboBox<>();
  private final ComboBox<VpnProfile> profile = new ComboBox<>();
  private final Button run = Ui.button("Проверить", this::diagnose);
  private boolean diagnosing;

  JournalPane(Window owner, DesktopModel model) {
    this.model = model;
    this.owner = owner;
    Tab events = new Tab("События", events());
    Tab diagnostics = new Tab("Проверка", diagnostics());
    events.setClosable(false);
    diagnostics.setClosable(false);
    tabs.getTabs().addAll(events, diagnostics);
    VBox title =
        new VBox(
            6,
            Ui.label("Журнал", "title"),
            Ui.label("События и проверка подключения — в одном месте.", "muted"));
    HBox.setHgrow(title, Priority.ALWAYS);
    HBox heading = new HBox(20, title, Ui.button("Экспорт журнала", this::exportEvents));
    heading.setPadding(new Insets(26, 32, 22, 32));
    setTop(heading);
    setCenter(tabs);
  }

  private VBox events() {
    TextField search = new TextField();
    search.setPromptText("Поиск события");
    search.setAccessibleText("Поиск события");
    level.getItems().addAll("Все уровни", "info", "warning", "error");
    level.setValue("Все уровни");
    level.setAccessibleText("Уровень события");
    level.setPrefWidth(180);
    level.setMinWidth(140);
    profile.setAccessibleText("Профиль журнала");
    profile.setPromptText("Все профили");
    profile.setPrefWidth(180);
    profile.setMinWidth(140);
    HBox.setHgrow(search, Priority.ALWAYS);
    profile.setCellFactory(list -> profileCell());
    profile.setButtonCell(profileCell());
    Runnable profilesChanged =
        () -> {
          VpnProfile selected = profile.getValue();
          profile.getItems().clear();
          profile.getItems().add(null);
          profile.getItems().addAll(model.profiles);
          profile.setValue(
              selected == null
                  ? null
                  : model.profiles.stream()
                      .filter(value -> value.id().equals(selected.id()))
                      .findFirst()
                      .orElse(null));
        };
    model.profiles.addListener((ListChangeListener<VpnProfile>) change -> profilesChanged.run());
    profilesChanged.run();
    FilteredList<EventJournal.Event> filtered = new FilteredList<>(model.events);
    Runnable filter =
        () ->
            filtered.setPredicate(
                event ->
                    (level.getValue().equals("Все уровни")
                            || level.getValue().equals(event.level()))
                        && (profile.getValue() == null
                            || profile.getValue().id().equals(event.profileId()))
                        && (event.message() + event.code())
                            .toLowerCase(Locale.ROOT)
                            .contains(search.getText().toLowerCase(Locale.ROOT)));
    search.textProperty().addListener((observable, previous, current) -> filter.run());
    level.valueProperty().addListener((observable, previous, current) -> filter.run());
    profile.valueProperty().addListener((observable, previous, current) -> filter.run());
    TableView<EventJournal.Event> table = new TableView<>(filtered);
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table
        .getColumns()
        .add(
            column(
                "Время",
                event ->
                    DateTimeFormatter.ofPattern("HH:mm:ss")
                        .withZone(ZoneId.systemDefault())
                        .format(event.time())));
    table.getColumns().add(column("Уровень", EventJournal.Event::level));
    table.getColumns().add(column("Профиль", this::profileName));
    table.getColumns().add(column("Событие", EventJournal.Event::message));
    table.getColumns().get(0).setMaxWidth(90);
    table.getColumns().get(1).setMaxWidth(92);
    table.getColumns().get(2).setMaxWidth(155);
    Label empty = Ui.label("Здесь появятся события подключения", "muted");
    table.setPlaceholder(empty);
    filtered.addListener(
        (ListChangeListener<EventJournal.Event>)
            change ->
                empty.setText(
                    model.events.isEmpty()
                        ? "Здесь появятся события подключения"
                        : "Нет событий по выбранным фильтрам"));
    TextArea detail = new TextArea();
    detail.setEditable(false);
    detail.setWrapText(true);
    detail.setPrefRowCount(3);
    detail.setAccessibleText("Подробности события");
    detail.visibleProperty().bind(table.getSelectionModel().selectedItemProperty().isNotNull());
    detail.managedProperty().bind(detail.visibleProperty());
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (observable, previous, current) -> {
              detail.setText(current == null ? "" : current.code() + "\n" + current.message());
            });
    VBox box =
        Ui.box(
            new HBox(12, profile, level, search),
            table,
            detail,
            new HBox(
                12,
                Ui.button("Копировать", () -> copy(detail.getText())),
                Ui.button("К последнему событию", () -> table.scrollTo(filtered.size() - 1)),
                Ui.button("Очистить экран", model.events::clear),
                Ui.button(
                    "Сбросить фильтры",
                    () -> {
                      search.clear();
                      level.setValue("Все уровни");
                      profile.setValue(null);
                    })));
    VBox.setVgrow(table, Priority.ALWAYS);
    box.setPadding(new Insets(24, 32, 24, 32));
    return box;
  }

  private Node diagnostics() {
    run.setDisable(!model.online.get());
    model.online.addListener((observable, before, online) -> run.setDisable(!online || diagnosing));
    TextField address = new TextField();
    address.setPromptText("IP или домен");
    address.setAccessibleText("Адрес для разбора по настройкам");
    Label explanation = Ui.label("Фактический маршрут не проверен", "muted");
    Button analyze =
        Ui.button(
            "Разобрать по настройкам",
            () -> {
              var applied = model.connection.get().appliedProfile();
              if (applied == null) {
                explanation.setText(
                    "Нет применённого профиля. Подключите VPN для разбора его настроек.");
                return;
              }
              try {
                String value = address.getText().strip();
                if (value.matches("[0-9.]+")) {
                  long ip = Ipv4Network.parseAddress(value);
                  String network =
                      applied.networks().stream()
                          .map(Ipv4Network::parse)
                          .filter(item -> item.contains(ip))
                          .map(Ipv4Network::toString)
                          .findFirst()
                          .orElse("");
                  explanation.setText(
                      network.isEmpty()
                          ? "По настройкам: IP не входит в выбранные сети. Фактический маршрут не"
                              + " проверен."
                          : "По настройкам: сеть "
                              + network
                              + ". Фактический маршрут не проверен.");
                } else {
                  String domain = DomainName.normalize(value);
                  boolean corporate =
                      applied.dns().domains().stream()
                          .anyMatch(suffix -> DomainName.matches(domain, suffix));
                  explanation.setText(
                      corporate
                          ? "По настройкам: корпоративный DNS. IP и маршрут ресурса пока"
                              + " неизвестны."
                          : "По настройкам: прежний системный DNS. IP и маршрут ресурса пока"
                              + " неизвестны.");
                }
              } catch (IllegalArgumentException exception) {
                explanation.setText("Введите корректный IPv4-адрес или домен");
              }
            });
    for (String check :
        List.of(
            "Сетевой компонент",
            "Конфигурация",
            "VPN и маршруты",
            "DNS",
            "Другие настройки системы")) {
      results.getChildren().add(checkRow(check, "Не проверено", "muted"));
    }
    run.getStyleClass().add("primary");
    Label heading = Ui.label("Почему адрес не открывается", "section-title");
    HBox.setHgrow(heading, Priority.ALWAYS);
    heading.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(address, Priority.ALWAYS);
    results.setSpacing(0);
    results.getStyleClass().add("rules");
    VBox content =
        Ui.box(
            new HBox(12, heading, run),
            Ui.label(
                "Проверяется фактически применённый профиль. Проверка не подключает другой VPN.",
                "muted"),
            results,
            Ui.label("Адрес для разбора по настройкам", "section-title"),
            new HBox(12, address, analyze),
            explanation,
            Ui.button("Предпросмотр отчёта…", this::report),
            Ui.label(
                "Ограничения: IPv6 и собственный DoH приложений не проверяются. Наличие маршрута не"
                    + " доказывает все пути обычных приложений.",
                "neutral-notice"));
    content.setMaxWidth(744);
    content.setPadding(new Insets(24, 32, 24, 32));
    StackPane container = new StackPane(content);
    StackPane.setAlignment(content, Pos.TOP_LEFT);
    return Ui.scroll(container);
  }

  private void diagnose() {
    if (diagnosing || !model.online.get()) {
      return;
    }
    diagnosing = true;
    run.setDisable(true);
    results.getChildren().setAll(Ui.label("Проверяем…", "muted"));
    model.command(
        AgentProtocol.Command.DIAGNOSE,
        null,
        null,
        null,
        response -> {
          diagnosing = false;
          run.setDisable(!model.online.get());
          displayChecks(response.checks());
        },
        message -> {
          diagnosing = false;
          run.setDisable(!model.online.get());
          checks = List.of(new NetworkRuntime.Check("Диагностика", "ERROR", message));
          results.getChildren().setAll(Ui.label(message, "error-text"));
        });
  }

  void displayChecks(List<NetworkRuntime.Check> values) {
    checks = List.copyOf(values);
    results
        .getChildren()
        .setAll(
            checks.stream()
                .map(
                    check ->
                        checkRow(
                            check.name(),
                            check.status() + " · " + check.message(),
                            check.status().equals("PASS") ? "success-text" : "muted"))
                .toList());
  }

  private void report() {
    Dialog<Boolean> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle("Предпросмотр отчёта");
    dialog.setHeaderText("Экспорт отчёта");
    CheckBox details = new CheckBox("Включить адреса и домены (внутренние данные)");
    TextArea preview = new TextArea();
    preview.setEditable(false);
    preview.setPrefSize(550, 300);
    Runnable update =
        () -> {
          String text =
              "Wisprail · sing-box 1.14.2\n"
                  + System.getProperty("os.name")
                  + "\n"
                  + String.join(
                      "\n",
                      checks.stream()
                          .map(
                              check ->
                                  check.name() + ": " + check.status() + " — " + check.message())
                          .toList())
                  + "\nIPv6 / собственный DoH: вне гарантии\nПолная приёмка маршрутов: NOT_RUN\n";
          var applied = model.connection.get().appliedProfile();
          if (details.isSelected() && applied != null) {
            text +=
                "Сервер: "
                    + applied.server()
                    + "\nСети: "
                    + String.join(", ", applied.networks())
                    + "\nDNS: "
                    + applied.dns().server()
                    + "\nДомены: "
                    + String.join(", ", applied.dns().domains());
          }
          preview.setText(text);
        };
    details.selectedProperty().addListener((observable, previous, current) -> update.run());
    update.run();
    dialog.getDialogPane().setContent(new VBox(12, details, preview));
    var save = new ButtonType("Сохранить отчёт");
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, save);
    dialog.setResultConverter(button -> button == save);
    Ui.style(dialog);
    dialog.getDialogPane().setPrefWidth(610);
    dialog
        .showAndWait()
        .filter(Boolean.TRUE::equals)
        .ifPresent(
            ignored -> {
              FileChooser chooser = new FileChooser();
              chooser.setInitialFileName("wisprail-report.txt");
              var file = chooser.showSaveDialog(owner);
              String text = preview.getText();
              if (file != null) {
                model.background(
                    () -> {
                      Files.writeString(file.toPath(), text);
                      return Boolean.TRUE;
                    },
                    saved -> {},
                    message -> Ui.error(owner, message));
              }
            });
  }

  private void exportEvents() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Экспорт журнала");
    chooser.setInitialFileName("wisprail-events.txt");
    var file = chooser.showSaveDialog(owner);
    if (file == null) {
      return;
    }
    String text =
        String.join(
            "\n",
            model.events.stream()
                .map(
                    event ->
                        event.time()
                            + " "
                            + event.level()
                            + " "
                            + event.code()
                            + " "
                            + event.message())
                .toList());
    model.background(
        () -> {
          Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
          return Boolean.TRUE;
        },
        ignored -> {},
        message -> Ui.error(owner, message));
  }

  void select(VpnProfile selected, boolean diagnostics, boolean errors) {
    tabs.getSelectionModel().select(diagnostics ? 1 : 0);
    profile.setValue(selected);
    level.setValue(errors ? "error" : "Все уровни");
  }

  private static ListCell<VpnProfile> profileCell() {
    return new ListCell<>() {
      @Override
      protected void updateItem(VpnProfile value, boolean empty) {
        super.updateItem(value, empty);
        setText(value == null ? "Все профили" : value.name());
      }
    };
  }

  private static Node checkRow(String name, String result, String style) {
    Label label = Ui.label(name, "summary-name");
    label.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(label, Priority.ALWAYS);
    HBox row = new HBox(20, label, Ui.label(result, style));
    row.getStyleClass().add("rule-row");
    return row;
  }

  private String profileName(EventJournal.Event event) {
    return model.profiles.stream()
        .filter(profile -> profile.id().equals(event.profileId()))
        .map(VpnProfile::name)
        .findFirst()
        .orElse("—");
  }

  private static TableColumn<EventJournal.Event, String> column(
      String title, Function<EventJournal.Event, String> value) {
    TableColumn<EventJournal.Event, String> column = new TableColumn<>(title);
    column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
    return column;
  }

  private static void copy(String value) {
    ClipboardContent content = new ClipboardContent();
    content.putString(value);
    Clipboard.getSystemClipboard().setContent(content);
  }
}
