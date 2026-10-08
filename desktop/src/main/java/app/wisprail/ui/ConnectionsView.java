package app.wisprail.ui;

import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.ConnectionState;
import app.wisprail.profile.Profile;
import app.wisprail.profile.VpnType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/** Connection cards show either a saved profile or the actual applied snapshot from the service. */
final class ConnectionsView extends BorderPane {
  enum Action {
    ADD,
    CONNECT,
    DISCONNECT,
    CANCEL,
    EDIT,
    NETWORKS,
    DNS,
    APPLY,
    DIAGNOSE,
    LOG,
    COPY,
    EXPORT,
    DELETE,
    COMPONENTS,
    CLEANUP
  }

  private final ObservableList<Profile> profiles = FXCollections.observableArrayList();
  private final FilteredList<Profile> filtered = new FilteredList<>(profiles);
  private final ListView<Profile> list = new ListView<>(filtered);
  private final Consumer<Profile> selectionChanged;
  private final Consumer<Action> action;
  private final TextField search = new TextField();
  private final Label count = Ui.label("Мои VPN", "section-label");
  private final VBox sidebar;
  private ConnectionSnapshot snapshot = ConnectionSnapshot.initial();
  private Profile selected;
  private boolean updating;
  private String unavailable = "";

  ConnectionsView(Consumer<Profile> selectionChanged, Consumer<Action> action) {
    this.selectionChanged = selectionChanged;
    this.action = action;
    list.getStyleClass().add("profile-list");
    list.setCellFactory(view -> new ProfileCell());
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (observable, oldValue, newValue) -> {
              if (!updating && newValue != null) {
                selected = newValue;
                selectionChanged.accept(newValue);
                renderDetail();
              }
            });
    search.setPromptText("Найти VPN");
    search.setAccessibleText("Поиск VPN");
    search
        .textProperty()
        .addListener(
            (observable, oldValue, newValue) -> {
              String query = newValue.toLowerCase(Locale.ROOT);
              filtered.setPredicate(
                  profile -> profile.name().toLowerCase(Locale.ROOT).contains(query));
            });
    HBox heading =
        Ui.row(
            count,
            Ui.spacer(),
            Ui.iconButton("plus", "Добавить VPN", () -> action.accept(Action.ADD)));
    Label sidebarHint = Ui.label("Одновременно подключён только один профиль.", "hint");
    sidebarHint.setMinHeight(Region.USE_PREF_SIZE);
    sidebarHint.setMaxWidth(Double.MAX_VALUE);
    sidebar = new VBox(15, heading, search, list, new Separator(), sidebarHint);
    sidebar.getStyleClass().add("profiles-panel");
    sidebar.setPrefWidth(246);
    sidebar.setMinWidth(200);
    sidebar.setMaxWidth(246);
    VBox.setVgrow(list, Priority.ALWAYS);
    setLeft(sidebar);
    renderDetail();
  }

  void setProfiles(List<Profile> values, UUID preferred) {
    updating = true;
    try {
      UUID desired = preferred != null ? preferred : selected == null ? null : selected.id();
      profiles.setAll(values);
      selected =
          profiles.stream()
              .filter(profile -> profile.id().equals(desired))
              .findFirst()
              .orElse(profiles.isEmpty() ? null : profiles.getFirst());
      list.getSelectionModel().select(selected);
      count.setText("Мои VPN " + profiles.size());
      search.setVisible(profiles.size() >= 7);
      search.setManaged(profiles.size() >= 7);
    } finally {
      updating = false;
    }
    if (selected != null) {
      selectionChanged.accept(selected);
    }
    renderDetail();
  }

  Profile selected() {
    return selected;
  }

  void select(UUID id) {
    profiles.stream()
        .filter(profile -> profile.id().equals(id))
        .findFirst()
        .ifPresent(profile -> list.getSelectionModel().select(profile));
  }

  void update(ConnectionSnapshot value) {
    snapshot = value;
    list.refresh();
    renderDetail();
  }

  void serviceUnavailable(String message) {
    if (unavailable.equals(message)) {
      return;
    }
    unavailable = message;
    renderDetail();
  }

  private void renderDetail() {
    if (selected == null) {
      setLeft(null);
      VBox empty =
          Ui.page(
              Ui.heading("Добавьте первый VPN"),
              Ui.label(
                  "Импортируйте файл или ссылку подключения. "
                      + "Сети и DNS можно настроить после добавления.",
                  "muted"),
              Ui.button("Добавить VPN", "primary", () -> action.accept(Action.ADD)),
              Ui.label(
                  "В приложении работает один VPN за раз. "
                      + "Подключения других программ мы не переключаем.",
                  "hint"));
      empty.setAlignment(Pos.CENTER);
      setCenter(empty);
      return;
    }
    setLeft(sidebar);
    boolean active =
        snapshot.activeProfile() != null && snapshot.activeProfile().id().equals(selected.id());
    boolean target = selected.id().equals(snapshot.targetProfileId());
    Profile shown = active ? snapshot.activeProfile() : selected;
    VBox title =
        new VBox(
            7,
            Ui.heading(selected.name()),
            Ui.label(typeTitle(selected) + " · " + shown.server(), "muted"));
    title.setMinWidth(0);
    HBox.setHgrow(title, Priority.ALWAYS);
    Button edit = Ui.button("Изменить", "small-button", () -> action.accept(Action.EDIT));
    edit.setDisable(snapshot.busy());
    Button menu = Ui.iconButton("dots", "Действия с профилем", () -> {});
    ContextMenu contextMenu = new ContextMenu();
    menuItem(contextMenu, "Изменить", Action.EDIT);
    menuItem(contextMenu, "Создать копию", Action.COPY);
    menuItem(contextMenu, "Экспорт профиля", Action.EXPORT);
    menuItem(contextMenu, "Удалить профиль", Action.DELETE);
    menu.setOnAction(event -> contextMenu.show(menu, Side.BOTTOM, 0, 0));
    menu.setDisable(snapshot.busy());
    VBox detail = Ui.page(Ui.row(title, edit, menu));
    if (!unavailable.isBlank()) {
      VBox warning = Ui.notice(unavailable, "error");
      warning
          .getChildren()
          .add(
              Ui.button(
                  "Открыть настройки компонента", "link", () -> action.accept(Action.COMPONENTS)));
      detail.getChildren().add(warning);
    }
    if (!active && snapshot.activeProfile() != null) {
      detail
          .getChildren()
          .add(
              Ui.notice(
                  "Сейчас работает "
                      + snapshot.activeProfile().name()
                      + ". При переключении он будет отключён.",
                  "warning"));
    }
    if (snapshot.preparing()) {
      VBox preparing =
          Ui.notice(
              "Проверяем настройки нового подключения. "
                  + "Текущее подключение сохраняется до подтверждения.",
              "information");
      preparing
          .getChildren()
          .add(Ui.button("Отменить проверку", "link", () -> action.accept(Action.CANCEL)));
      detail.getChildren().add(preparing);
    }
    String status = "Не подключён";
    String description = "Работает только для выбранных сетей";
    String actionText = snapshot.activeProfile() == null ? "Подключить" : "Переключиться";
    Action requested = Action.CONNECT;
    boolean busyHere = (active || target) && snapshot.busy() && !snapshot.preparing();
    boolean errorHere =
        snapshot.state() == ConnectionState.ERROR && (target || snapshot.cleanupRequired());
    if (active && snapshot.state() == ConnectionState.CONNECTED) {
      status = "Подключён";
      description = "VPN работает для выбранных сетей";
      actionText = "Отключить";
      requested = Action.DISCONNECT;
    } else if (busyHere) {
      boolean connecting = snapshot.state() == ConnectionState.CONNECTING;
      status = connecting ? "Подключение…" : "Отключение…";
      description = snapshot.message();
      actionText = connecting ? "Отменить" : "Отключение…";
      requested = Action.CANCEL;
    } else if (errorHere) {
      status = snapshot.cleanupRequired() ? "Требуется проверка" : "Не удалось подключиться";
      description = snapshot.message();
      actionText = snapshot.cleanupRequired() ? "Проверить очистку" : "Повторить";
      requested = snapshot.cleanupRequired() ? Action.CLEANUP : Action.CONNECT;
    }
    Action operation = requested;
    Button primary =
        Ui.button(
            actionText, active || busyHere ? "button" : "primary", () -> action.accept(operation));
    primary.setDisable(
        snapshot.state() == ConnectionState.DISCONNECTING
            || snapshot.preparing()
            || (snapshot.busy() && !busyHere)
            || !unavailable.isBlank());
    Label statusTitle = Ui.label(status, "status-title");
    VBox statusText = new VBox(7, statusTitle, Ui.label(description, "muted"));
    HBox.setHgrow(statusText, Priority.ALWAYS);
    StackPane statusSymbol =
        new StackPane(Ui.icon(active ? "check" : errorHere ? "alert" : "power"));
    statusSymbol
        .getStyleClass()
        .addAll("status-symbol", active ? "connected-symbol" : errorHere ? "error-symbol" : "");
    statusSymbol.setMinSize(42, 42);
    statusSymbol.setMaxSize(42, 42);
    if (active && !busyHere) {
      primary.setGraphic(Ui.icon("power"));
    }
    HBox statusRow = Ui.row(statusSymbol, statusText, primary);
    statusRow.setSpacing(12);
    if (busyHere) {
      ProgressIndicator indicator = new ProgressIndicator();
      indicator.setPrefSize(27, 27);
      indicator.setMaxSize(27, 27);
      statusSymbol.getChildren().setAll(indicator);
    }
    VBox card = new VBox(18, statusRow);
    card.getStyleClass().add("status-card");
    if (active || errorHere) {
      VBox footer =
          new VBox(
              16,
              new Separator(),
              Ui.row(
                  Ui.label(
                      errorHere ? "Настройки профиля сохранены" : "Подключение активно", "hint"),
                  Ui.spacer(),
                  Ui.button(
                      errorHere ? "Открыть журнал" : "Проверить доступность",
                      "link",
                      () -> action.accept(errorHere ? Action.LOG : Action.DIAGNOSE))));
      card.getChildren().add(footer);
    }
    detail.getChildren().add(card);
    if (active && !selected.sameNetworkSettings(shown)) {
      VBox pending =
          Ui.notice(
              "Есть сохранённые изменения. " + "Ниже показаны действующие настройки.", "warning");
      Button apply =
          Ui.button("Переподключить и применить", "link", () -> action.accept(Action.APPLY));
      apply.setDisable(snapshot.busy());
      pending.getChildren().add(apply);
      detail.getChildren().add(pending);
    }
    VBox summary =
        new VBox(
            8,
            Ui.label(active ? "Действующие настройки VPN" : "Настройки этого VPN", "section-label"),
            summary("network", "Сети через VPN", networkChips(shown.networks()), Action.NETWORKS),
            summary(
                "globe",
                "DNS для выбранных доменов",
                Ui.label(
                    shown.dns().isBlank()
                        ? "Используется системный DNS"
                        : String.join(", ", shown.domains())
                            + "\n"
                            + shown.dns()
                            + " · через этот VPN",
                    "muted"),
                Action.DNS),
            summary(
                "swap",
                "Другие адреса",
                Ui.label(
                    "По текущим настройкам системы.\n"
                        + "В том числе через другой VPN, если он настроен в системе.",
                    "muted"),
                null));
    detail.getChildren().add(summary);
    TitledPane technical =
        new TitledPane(
            "Технические сведения",
            Ui.label(
                "Режим: только выбранные IPv4-сети\n"
                    + "Сохранённая ревизия: "
                    + selected.revision()
                    + "\n"
                    + (active ? "Применённая ревизия: " + shown.revision() + "\n" : "")
                    + "Подробная проверка доступна в разделе «Журнал → Проверка».",
                "hint"));
    technical.setExpanded(false);
    technical.getStyleClass().add("technical");
    detail.getChildren().add(technical);
    setCenter(Ui.scroll(detail));
  }

  private static Node networkChips(List<String> networks) {
    if (networks.isEmpty()) {
      return Ui.label("Не добавлены", "muted");
    }
    FlowPane chips = new FlowPane(4, 4);
    networks.stream()
        .limit(4)
        .forEach(network -> chips.getChildren().add(Ui.label(network, "network-chip")));
    if (networks.size() > 4) {
      chips.getChildren().add(Ui.label("и ещё " + (networks.size() - 4), "hint"));
    }
    return chips;
  }

  private HBox summary(String icon, String title, Node description, Action editAction) {
    VBox text = new VBox(7, Ui.label(title, "field-label"), description);
    HBox.setHgrow(text, Priority.ALWAYS);
    HBox row = Ui.row(Ui.icon(icon), text);
    row.setAlignment(Pos.TOP_LEFT);
    row.getStyleClass().add("summary-row");
    if (editAction != null) {
      Button edit = Ui.button("Изменить", "link", () -> action.accept(editAction));
      edit.setDisable(snapshot.busy());
      row.getChildren().add(edit);
    }
    return row;
  }

  private void menuItem(ContextMenu menu, String title, Action command) {
    MenuItem item = new MenuItem(title);
    item.setOnAction(event -> action.accept(command));
    menu.getItems().add(item);
  }

  static String typeTitle(Profile profile) {
    return profile.type() == VpnType.OPENVPN ? "OpenVPN" : "VLESS";
  }

  private final class ProfileCell extends ListCell<Profile> {
    @Override
    protected void updateItem(Profile profile, boolean empty) {
      super.updateItem(profile, empty);
      if (empty || profile == null) {
        setGraphic(null);
        setTooltip(null);
        return;
      }
      boolean active =
          snapshot.activeProfile() != null && snapshot.activeProfile().id().equals(profile.id());
      boolean target = profile.id().equals(snapshot.targetProfileId());
      String status;
      if ((active || target) && snapshot.state() == ConnectionState.DISCONNECTING) {
        status = "Отключение…";
      } else if (active) {
        status = "Подключён";
      } else if (target && snapshot.busy()) {
        status = "Подключение…";
      } else {
        status = "Не подключён";
      }
      Label name = Ui.label(profile.name(), "profile-title");
      name.setMinWidth(0);
      name.setWrapText(false);
      name.setMaxWidth(200);
      Circle dot =
          new Circle(
              3.5,
              Color.web(active ? "#18794e" : target && snapshot.busy() ? "#365ad8" : "#9aa3af"));
      HBox state =
          Ui.row(Ui.label(typeTitle(profile) + " ·", "hint"), dot, Ui.label(status, "hint"));
      state.setSpacing(6);
      VBox card = new VBox(7, name, state);
      card.setMinWidth(0);
      card.prefWidthProperty().bind(list.widthProperty().subtract(20));
      card.maxWidthProperty().bind(list.widthProperty().subtract(20));
      card.getStyleClass().add("profile-card");
      setGraphic(card);
      setTooltip(new Tooltip(profile.name()));
      setAccessibleText(profile.name() + ", " + status);
    }
  }
}
