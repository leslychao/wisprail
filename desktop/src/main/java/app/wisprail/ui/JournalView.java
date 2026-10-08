package app.wisprail.ui;

import app.wisprail.connection.AddressAnalysis;
import app.wisprail.connection.CheckStatus;
import app.wisprail.connection.ConnectionEvent;
import app.wisprail.connection.DiagnosticCheck;
import app.wisprail.connection.DiagnosticReport;
import app.wisprail.profile.Profile;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Bounded event presentation and explicit diagnostics, never a source of connection readiness. */
final class JournalView extends BorderPane implements AutoCloseable {
  private static final int EVENT_LIMIT = 2_000;
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
  private final ObservableList<ConnectionEvent> events = FXCollections.observableArrayList();
  private final FilteredList<ConnectionEvent> filtered = new FilteredList<>(events);
  private final ArrayBlockingQueue<ConnectionEvent> pending = new ArrayBlockingQueue<>(EVENT_LIMIT);
  private final AtomicLong omitted = new AtomicLong();
  private final ObservableList<Profile> profiles = FXCollections.observableArrayList();
  private final ComboBox<Profile> eventProfile = new ComboBox<>(profiles);
  private final ComboBox<Profile> diagnosticProfile = new ComboBox<>(profiles);
  private final ComboBox<String> level = new ComboBox<>();
  private final TextField query = new TextField();
  private final TableView<ConnectionEvent> table = new TableView<>(filtered);
  private final Label empty = Ui.label("Здесь появятся события подключения", "hint");
  private final TextArea detail = new TextArea();
  private final Label bufferInfo = Ui.label("", "hint");
  private final CheckBox follow = new CheckBox("Следить за новыми событиями");
  private final VBox checks = new VBox(12);
  private final VBox addressResult = new VBox(8);
  private final Button diagnose;
  private final Button analyze;
  private final TextField address = new TextField();
  private final Consumer<Profile> onDiagnose;
  private final BiConsumer<Profile, String> onAnalyze;
  private final Consumer<String> export;
  private final Dialogs dialogs;
  private final Timeline flush;
  private final ToggleButton eventsTab = new ToggleButton("События");
  private final ToggleButton checksTab = new ToggleButton("Проверка");
  private DiagnosticReport report;
  private boolean checking;

  JournalView(
      Consumer<Profile> onDiagnose,
      BiConsumer<Profile, String> onAnalyze,
      Consumer<String> export,
      Dialogs dialogs) {
    this.onDiagnose = onDiagnose;
    this.onAnalyze = onAnalyze;
    this.export = export;
    this.dialogs = dialogs;
    level.getItems().setAll("Все события", "Ошибки", "Предупреждения", "Информация");
    level.setValue("Все события");
    eventProfile.setPromptText("Все профили");
    configureProfiles(eventProfile);
    configureProfiles(diagnosticProfile);
    query.setPromptText("Найти в журнале");
    query.setAccessibleText("Поиск в журнале");
    eventProfile.setAccessibleText("Фильтр по профилю");
    level.setAccessibleText("Уровень событий");
    eventProfile.valueProperty().addListener((observable, oldValue, newValue) -> filter());
    level.valueProperty().addListener((observable, oldValue, newValue) -> filter());
    query.textProperty().addListener((observable, oldValue, newValue) -> filter());
    TableColumn<ConnectionEvent, String> time = column("Время", event -> TIME.format(event.time()));
    TableColumn<ConnectionEvent, String> severity =
        column("Уровень", event -> levelText(event.level()));
    TableColumn<ConnectionEvent, String> profile =
        column("Профиль", event -> profileName(event.profileId()));
    TableColumn<ConnectionEvent, String> message = column("Событие", ConnectionEvent::message);
    time.setPrefWidth(90);
    severity.setPrefWidth(105);
    profile.setPrefWidth(155);
    message.setPrefWidth(460);
    table.getColumns().add(time);
    table.getColumns().add(severity);
    table.getColumns().add(profile);
    table.getColumns().add(message);
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setPlaceholder(empty);
    table.setAccessibleText("События подключения");
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (observable, oldValue, selected) -> {
              detail.setText(
                  selected == null ? "" : selected.message() + "\n\n" + selected.detail());
            });
    detail.setEditable(false);
    detail.setWrapText(true);
    detail.setPrefRowCount(4);
    detail.getStyleClass().add("mono");
    detail.setAccessibleText("Подробности события");
    follow.setSelected(true);
    ToggleGroup tabs = new ToggleGroup();
    eventsTab.setToggleGroup(tabs);
    checksTab.setToggleGroup(tabs);
    eventsTab.getStyleClass().add("tab-button");
    checksTab.getStyleClass().add("tab-button");
    eventsTab.setOnAction(event -> showEvents());
    checksTab.setOnAction(event -> showDiagnostics(diagnosticProfile.getValue()));
    HBox tabBar = Ui.row(eventsTab, checksTab);
    tabBar.getStyleClass().add("tabs");
    setTop(
        Ui.page(
            Ui.heading("Журнал"),
            Ui.label("События и проверка подключения — в одном месте.", "hint"),
            tabBar));
    diagnose =
        Ui.button(
            "Проверить",
            "primary",
            () -> {
              Profile selected = diagnosticProfile.getValue();
              if (selected != null) {
                onDiagnose.accept(selected);
              }
            });
    address.setPromptText("git.company.local или 10.20.5.17");
    analyze =
        Ui.button(
            "Разобрать",
            "button",
            () -> {
              Profile selected = diagnosticProfile.getValue();
              if (selected != null && !address.getText().isBlank()) {
                onAnalyze.accept(selected, address.getText().trim());
              }
            });
    diagnosticProfile
        .valueProperty()
        .addListener(
            (observable, oldValue, newValue) -> {
              report = null;
              addressResult.getChildren().clear();
              analyzing(false);
              showChecks();
            });
    address
        .textProperty()
        .addListener(
            (observable, oldValue, newValue) -> {
              addressResult.getChildren().clear();
              analyzing(false);
            });
    flush = new Timeline(new KeyFrame(Duration.millis(150), event -> flushEvents()));
    flush.setCycleCount(Timeline.INDEFINITE);
    flush.play();
    showEvents();
  }

  private static TableColumn<ConnectionEvent, String> column(
      String title, Function<ConnectionEvent, String> text) {
    TableColumn<ConnectionEvent, String> column = new TableColumn<>(title);
    column.setCellValueFactory(value -> new ReadOnlyStringWrapper(text.apply(value.getValue())));
    return column;
  }

  private static void configureProfiles(ComboBox<Profile> box) {
    box.setButtonCell(profileCell());
    box.setCellFactory(view -> profileCell());
    box.setMaxWidth(240);
  }

  private static ListCell<Profile> profileCell() {
    return new ListCell<>() {
      @Override
      protected void updateItem(Profile profile, boolean empty) {
        super.updateItem(profile, empty);
        setText(empty || profile == null ? "Все профили" : profile.name());
      }
    };
  }

  void setProfiles(List<Profile> values, Profile selected) {
    UUID previous = diagnosticProfile.getValue() == null ? null : diagnosticProfile.getValue().id();
    profiles.setAll(values);
    Profile keep =
        values.stream()
            .filter(profile -> profile.id().equals(previous))
            .findFirst()
            .orElse(selected);
    diagnosticProfile.setValue(keep);
  }

  void accept(ConnectionEvent event) {
    if (!pending.offer(event)) {
      pending.poll();
      pending.offer(event);
      omitted.incrementAndGet();
    }
  }

  private void flushEvents() {
    ArrayList<ConnectionEvent> batch = new ArrayList<>(200);
    pending.drainTo(batch, 200);
    if (batch.isEmpty()) {
      return;
    }
    events.addAll(batch);
    if (events.size() > EVENT_LIMIT) {
      int removed = events.size() - EVENT_LIMIT;
      events.remove(0, removed);
      omitted.addAndGet(removed);
    }
    empty.setText(
        events.isEmpty()
            ? "Здесь появятся события подключения"
            : "Нет событий по выбранным фильтрам");
    bufferInfo.setText(
        "На экране: "
            + events.size()
            + " из ограниченного буфера"
            + (omitted.get() > 0 ? ". Ранние записи не показаны: " + omitted.get() : ""));
    if (follow.isSelected() && !filtered.isEmpty()) {
      table.scrollTo(filtered.size() - 1);
    }
  }

  private void filter() {
    Profile selected = eventProfile.getValue();
    String expectedLevel =
        switch (level.getValue()) {
          case "Ошибки" -> "error";
          case "Предупреждения" -> "warning";
          case "Информация" -> "info";
          default -> "";
        };
    String search = query.getText().toLowerCase(Locale.ROOT);
    filtered.setPredicate(
        event ->
            (selected == null || selected.id().equals(event.profileId()))
                && (expectedLevel.isBlank() || expectedLevel.equalsIgnoreCase(event.level()))
                && (event.message() + " " + event.detail())
                    .toLowerCase(Locale.ROOT)
                    .contains(search));
    empty.setText(
        events.isEmpty()
            ? "Здесь появятся события подключения"
            : "Нет событий по выбранным фильтрам");
  }

  void showErrors(Profile profile) {
    eventProfile.setValue(profile);
    level.setValue("Ошибки");
    showEvents();
  }

  private void showEvents() {
    eventsTab.setSelected(true);
    HBox filters = Ui.row(eventProfile, level, query);
    HBox.setHgrow(query, Priority.ALWAYS);
    VBox content =
        Ui.page(
            filters,
            table,
            Ui.row(
                follow,
                Ui.spacer(),
                Ui.button(
                    "К последним событиям",
                    "link",
                    () -> {
                      follow.setSelected(true);
                      table.scrollTo(Math.max(0, filtered.size() - 1));
                    })),
            detail,
            Ui.row(
                Ui.button("Копировать событие", "link", this::copySelected),
                Ui.spacer(),
                Ui.button("Экспорт журнала", "link", this::exportEvents)),
            Ui.row(
                bufferInfo,
                Ui.spacer(),
                Ui.button(
                    "Сбросить фильтры",
                    "link",
                    () -> {
                      eventProfile.setValue(null);
                      level.setValue("Все события");
                      query.clear();
                    }),
                Ui.button(
                    "Очистить экран",
                    "link",
                    () -> {
                      events.clear();
                      pending.clear();
                      omitted.set(0);
                      bufferInfo.setText("Экран очищен; файлы журнала не удалены.");
                      filter();
                    })));
    VBox.setVgrow(table, Priority.ALWAYS);
    table.setMinHeight(150);
    setCenter(content);
  }

  void showDiagnostics(Profile selected) {
    checksTab.setSelected(true);
    if (selected != null) {
      diagnosticProfile.setValue(selected);
    }
    VBox addressField = Ui.field("Адрес для разбора по настройкам", address, "");
    HBox.setHgrow(addressField, Priority.ALWAYS);
    VBox content =
        Ui.page(
            Ui.row(Ui.label("Почему адрес не открывается", "subheading"), Ui.spacer(), diagnose),
            Ui.field("Профиль", diagnosticProfile, "Проверка не подключает другой VPN."),
            checks,
            Ui.row(addressField, analyze),
            addressResult,
            Ui.notice(
                "«По настройкам» и «Проверено запросом» — разные результаты. "
                    + "Отсутствие ответа отдельного ресурса не всегда означает, что VPN отключён.",
                ""),
            Ui.button("Сохранить отчёт", "link", this::exportReport));
    showChecks();
    setCenter(Ui.scroll(content));
  }

  void checking(boolean value) {
    checking = value;
    diagnose.setDisable(value || diagnosticProfile.getValue() == null);
    diagnose.setText(value ? "Проверяем…" : "Проверить");
    diagnosticProfile.setDisable(value);
    showChecks();
  }

  void analyzing(boolean value) {
    analyze.setDisable(value);
    analyze.setText(value ? "Проверяем…" : "Разобрать");
  }

  void report(DiagnosticReport value) {
    if (diagnosticProfile.getValue() != null
        && diagnosticProfile.getValue().id().equals(value.profileId())) {
      report = value;
      checking(false);
      showChecks();
    }
  }

  void analysis(UUID profileId, String input, AddressAnalysis result) {
    Profile selected = diagnosticProfile.getValue();
    if (selected == null
        || !selected.id().equals(profileId)
        || !address.getText().trim().equals(input)) {
      return;
    }
    addressResult
        .getChildren()
        .setAll(
            Ui.notice(
                (result.actualRouteVerified() ? "Проверено запросом" : "По настройкам")
                    + "\n"
                    + result.detail()
                    + (result.matchingDomain().isBlank()
                        ? ""
                        : "\nDNS-правило: " + result.matchingDomain())
                    + (result.addresses().isEmpty()
                        ? ""
                        : "\nIP: " + String.join(", ", result.addresses()))
                    + (result.matchingNetworks().isEmpty()
                        ? ""
                        : "\nСети: " + String.join(", ", result.matchingNetworks())),
                "information"));
    analyzing(false);
  }

  private void showChecks() {
    checks.getChildren().clear();
    List<DiagnosticCheck> values =
        report == null
            ? List.of(
                    "Сетевой компонент",
                    "Конфигурация",
                    "VPN и выбранные маршруты",
                    "DNS",
                    "Другие настройки системы")
                .stream()
                .map(
                    name ->
                        new DiagnosticCheck(
                            name, checking ? CheckStatus.RUNNING : CheckStatus.NOT_RUN, ""))
                .toList()
            : report.checks();
    for (DiagnosticCheck check : values) {
      String state =
          switch (check.status()) {
            case PASS -> "✓ Подтверждено";
            case FAIL -> "✕ Ошибка";
            case INCONCLUSIVE -> "Не подтверждено";
            case RUNNING -> "Проверяем…";
            case NOT_RUN -> "Не проверено";
          };
      Label result =
          Ui.label(state + (check.detail().isBlank() ? "" : "\n" + check.detail()), "hint");
      result
          .getStyleClass()
          .add(
              check.status() == CheckStatus.PASS
                  ? "connected-text"
                  : check.status() == CheckStatus.FAIL ? "error-text" : "muted");
      VBox row = new VBox(6, Ui.label(check.name(), "field-label"), result);
      row.getStyleClass().add("rule-row");
      checks.getChildren().add(row);
    }
    diagnose.setDisable(checking || diagnosticProfile.getValue() == null);
  }

  private void copySelected() {
    ConnectionEvent event = table.getSelectionModel().getSelectedItem();
    if (event != null) {
      ClipboardContent content = new ClipboardContent();
      content.putString(TIME.format(event.time()) + " " + event.message() + "\n" + event.detail());
      Clipboard.getSystemClipboard().setContent(content);
    }
  }

  private void exportEvents() {
    String text =
        filtered.stream()
            .map(
                event ->
                    TIME.format(event.time())
                        + " "
                        + event.level()
                        + " "
                        + event.message()
                        + "\n"
                        + event.detail())
            .collect(Collectors.joining("\n\n"));
    if (dialogs.preview(
        "Экспорт журнала",
        "Проверьте адреса и имена перед передачей файла. "
            + "Экспортируются только показанные события по текущим фильтрам.",
        text,
        "Сохранить файл")) {
      export.accept(text);
    }
  }

  private void exportReport() {
    if (report == null) {
      dialogs.information("Отчёт проверки", "Сначала выполните проверку выбранного профиля.");
      return;
    }
    CheckBox addresses = new CheckBox("Добавить адреса и имена из профиля");
    boolean proceed =
        dialogs
            .choose(
                "Сохранить отчёт проверки",
                new VBox(
                    14,
                    Ui.label(
                        "По умолчанию: только типы проверок и состояния. "
                            + "Адреса, домены, имена пользователей и полные пути исключены.",
                        "hint"),
                    addresses,
                    Ui.notice(
                        "Даже без паролей адреса и домены могут быть внутренними данными компании.",
                        "warning")),
                List.of(
                    new Dialogs.Choice<>("Отмена", false, true, false),
                    new Dialogs.Choice<>("Просмотреть", true, false, false)))
            .orElse(false);
    if (!proceed) {
      return;
    }
    StringBuilder text =
        new StringBuilder("Wisprail — отчёт проверки\nВремя: ")
            .append(report.checkedAt())
            .append("\n\n");
    for (DiagnosticCheck check : report.checks()) {
      text.append(check.name()).append(": ").append(check.status()).append('\n');
    }
    Profile selected = diagnosticProfile.getValue();
    if (addresses.isSelected() && selected != null) {
      text.append("\nПрофиль: ")
          .append(selected.name())
          .append("\nСервер: ")
          .append(selected.server())
          .append("\nСети: ")
          .append(String.join(", ", selected.networks()))
          .append("\nDNS: ")
          .append(selected.dns())
          .append("\nДомены: ")
          .append(String.join(", ", selected.domains()))
          .append('\n');
    }
    if (dialogs.preview(
        "Просмотр отчёта",
        "Отчёт никуда не отправляется автоматически.",
        text.toString(),
        "Сохранить файл")) {
      export.accept(text.toString());
    }
  }

  private String profileName(UUID id) {
    if (id == null) {
      return "Система";
    }
    return profiles.stream()
        .filter(profile -> profile.id().equals(id))
        .findFirst()
        .map(Profile::name)
        .orElse("Удалённый профиль");
  }

  private static String levelText(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "error" -> "Ошибка";
      case "warning" -> "Внимание";
      default -> "Информация";
    };
  }

  @Override
  public void close() {
    flush.stop();
    pending.clear();
  }
}
