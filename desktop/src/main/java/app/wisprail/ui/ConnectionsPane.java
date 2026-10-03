package app.wisprail.ui;

import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.profile.VpnProfile;
import java.util.Locale;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
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
import javafx.scene.text.TextAlignment;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

final class ConnectionsPane extends BorderPane {
  private final DesktopModel model;
  private final DesktopView navigation;
  private final ListView<VpnProfile> profiles;
  private final ProfileActions actions;
  private final VBox detail = new VBox(23);
  private final ScrollPane scroll = Ui.scroll(detail);

  ConnectionsPane(Window owner, DesktopModel model, DesktopView navigation) {
    this.model = model;
    this.navigation = navigation;
    FilteredList<VpnProfile> filtered = new FilteredList<>(model.profiles);
    profiles = new ListView<>(filtered);
    actions = new ProfileActions(owner, model, this::select, navigation);
    profiles.setId("profiles");
    profiles.setAccessibleText("Мои VPN");
    profiles.setCellFactory(
        list ->
            new ListCell<>() {
              @Override
              protected void updateItem(VpnProfile profile, boolean empty) {
                super.updateItem(profile, empty);
                setText(null);
                if (empty || profile == null) {
                  setGraphic(null);
                  return;
                }
                var applied = model.connection.get().appliedProfile();
                boolean active = applied != null && applied.id().equals(profile.id());
                Label name = Ui.label(profile.name(), "profile-name");
                name.setWrapText(false);
                name.setMaxWidth(188);
                name.setTooltip(new Tooltip(profile.name()));
                Label dot = Ui.label("●", active ? "success-dot" : "muted-dot");
                HBox subtitle =
                    new HBox(
                        6,
                        Ui.label(protocol(profile) + " ·", "small"),
                        dot,
                        Ui.label(active ? "Подключён" : "Не подключён", "small"));
                subtitle.setAlignment(Pos.CENTER_LEFT);
                VBox content = new VBox(7, name, subtitle);
                content.setPrefWidth(188);
                setGraphic(content);
              }
            });
    Label count = Ui.label("", "sidebar-title");
    count.textProperty().bind(Bindings.size(model.profiles).asString("Мои VPN %d"));
    Button add = Ui.iconButton("plus", "Добавить VPN", actions::create);
    add.disableProperty().bind(model.ready.not());
    Region space = new Region();
    HBox.setHgrow(space, Priority.ALWAYS);
    HBox heading = new HBox(count, space, add);
    heading.setAlignment(Pos.CENTER_LEFT);
    heading.setPadding(new Insets(0, 8, 14, 9));
    TextField search = new TextField();
    search.setPromptText("Найти VPN");
    search.setAccessibleText("Поиск VPN");
    search.visibleProperty().bind(Bindings.size(model.profiles).greaterThanOrEqualTo(7));
    search.managedProperty().bind(search.visibleProperty());
    search
        .textProperty()
        .addListener(
            (observable, before, value) ->
                filtered.setPredicate(
                    profile ->
                        profile
                            .name()
                            .toLowerCase(Locale.ROOT)
                            .contains(value.toLowerCase(Locale.ROOT))));
    Label note = Ui.label("Одновременно подключён\nтолько один профиль.", "profile-note");
    note.setMaxWidth(Double.MAX_VALUE);
    VBox.setMargin(note, new Insets(24, 10, 0, 10));
    VBox sidebar = new VBox(0, heading, search, profiles, note);
    sidebar.setPrefWidth(246);
    sidebar.setMinWidth(220);
    sidebar.setMaxWidth(246);
    sidebar.getStyleClass().add("sidebar");
    sidebar.visibleProperty().bind(Bindings.isNotEmpty(model.profiles));
    sidebar.managedProperty().bind(sidebar.visibleProperty());
    VBox.setVgrow(profiles, Priority.ALWAYS);
    detail.setPadding(new Insets(29, 32, 8, 32));
    setLeft(sidebar);
    scroll
        .viewportBoundsProperty()
        .addListener(
            (observable, before, bounds) -> {
              double reserved = Math.max(0, scroll.getWidth() - bounds.getWidth());
              detail.setPadding(new Insets(29, Math.max(0, 32 - reserved), 8, 32));
            });
    setCenter(scroll);
    profiles
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((observable, before, value) -> refresh());
    model.connection.addListener(
        (observable, before, value) -> {
          profiles.refresh();
          refresh();
        });
    model.online.addListener((observable, before, value) -> refresh());
    model.ready.addListener((observable, before, value) -> refresh());
    model.serviceMessage.addListener((observable, before, value) -> refresh());
    model.profiles.addListener(
        (ListChangeListener<VpnProfile>)
            change -> {
              if (selected() == null && !model.profiles.isEmpty()) {
                profiles.getSelectionModel().selectFirst();
              }
              refresh();
            });
    if (!model.profiles.isEmpty()) {
      profiles.getSelectionModel().selectFirst();
    }
    refresh();
  }

  void select(VpnProfile profile) {
    model.profiles.stream()
        .filter(item -> item.id().equals(profile.id()))
        .findFirst()
        .ifPresent(item -> profiles.getSelectionModel().select(item));
  }

  ProfileActions actions() {
    return actions;
  }

  VpnProfile selected() {
    return profiles.getSelectionModel().getSelectedItem();
  }

  private void refresh() {
    detail.getChildren().clear();
    VpnProfile selected = selected();
    scroll.setFitToHeight(selected == null);
    detail.setAlignment(selected == null ? Pos.CENTER : Pos.TOP_LEFT);
    if (!model.ready.get() && model.profiles.isEmpty()) {
      ProgressIndicator progress = new ProgressIndicator();
      progress.setMaxSize(28, 28);
      detail.getChildren().addAll(progress, Ui.label("Загружаем профили…", "muted"));
      return;
    }
    if (selected == null && !model.profiles.isEmpty()) {
      detail
          .getChildren()
          .add(
              Ui.label(
                  "Выберите VPN в списке. Если поиск не дал результатов, измените запрос.",
                  "muted"));
      return;
    }
    if (selected == null) {
      Button add = Ui.button("Добавить VPN", actions::create);
      add.getStyleClass().add("primary");
      add.disableProperty().bind(model.ready.not());
      StackPane symbol = new StackPane(Icons.of("plus"));
      symbol.getStyleClass().add("empty-symbol");
      VBox empty =
          new VBox(
              18,
              symbol,
              Ui.label("Добавьте первый VPN", "title"),
              Ui.label(
                  "Импортируйте файл или ссылку подключения.\n"
                      + "Сети и DNS можно настроить после добавления.",
                  "muted"),
              add,
              Ui.label(
                  "В приложении работает один VPN за раз.\n"
                      + "Подключения других программ мы не переключаем.",
                  "small"));
      empty.setAlignment(Pos.CENTER);
      empty.setMaxWidth(460);
      empty.setPadding(new Insets(30));
      for (Node child : empty.getChildren()) {
        if (child instanceof Label label) {
          label.setTextAlignment(TextAlignment.CENTER);
        }
      }
      detail.getChildren().add(empty);
      return;
    }
    var state = model.connection.get();
    VpnProfile applied = state.appliedProfile();
    boolean active = applied != null && applied.id().equals(selected.id());
    VpnProfile shown = active ? applied : selected;
    HBox endpoint =
        new HBox(
            5, Ui.label(protocol(selected) + " ·", "muted"), Ui.label(shown.server(), "addresses"));
    VBox title = new VBox(6, Ui.label(selected.name(), "title"), endpoint);
    title.setMinWidth(0);
    HBox.setHgrow(title, Priority.ALWAYS);
    Button edit = Ui.button("Изменить", () -> actions.edit(selected));
    edit.getStyleClass().add("small-button");
    edit.setMinWidth(Region.USE_PREF_SIZE);
    MenuButton more = new MenuButton();
    more.setGraphic(Icons.of("more"));
    more.setMinWidth(34);
    more.setPrefWidth(34);
    more.setMaxWidth(34);
    more.setAccessibleText("Действия с профилем");
    more.setOnShown(
        event -> {
          for (Window window : Window.getWindows()) {
            if (window instanceof PopupWindow popup && popup.getOwnerNode() == more) {
              var bounds = more.localToScreen(more.getBoundsInLocal());
              popup.setX(bounds.getMaxX() - popup.getWidth());
            }
          }
        });
    more.getStyleClass().add("profile-menu");
    more.getItems()
        .addAll(
            item("Изменить", () -> actions.edit(selected)),
            item("Создать копию", () -> actions.copy(selected)),
            item("Экспорт профиля", () -> actions.export(selected)),
            item("Удалить профиль", () -> actions.delete(selected)));
    HBox heading = new HBox(8, title, edit, more);
    heading.setAlignment(Pos.CENTER_LEFT);
    detail.getChildren().add(heading);
    if (!model.online.get() && !model.serviceMessage.get().isEmpty()) {
      VBox unavailable =
          new VBox(
              6,
              Ui.label(model.serviceMessage.get(), "error-text"),
              Ui.link("Открыть настройки компонентов", navigation::showSettings));
      unavailable.getStyleClass().add("unavailable-notice");
      detail.getChildren().add(unavailable);
    }
    if (applied != null && !active) {
      detail
          .getChildren()
          .add(
              Ui.label(
                  "Сейчас работает «"
                      + currentName(applied)
                      + "». При переключении он будет отключён.",
                  "notice"));
    }
    if (active && !applied.sameNetworkSettings(selected)) {
      VBox deferred =
          new VBox(
              8,
              Ui.label("Есть сохранённые изменения", "section-title"),
              Ui.label("Ниже показаны действующие настройки VPN.", "muted"),
              Ui.link("Переподключить и применить", () -> actions.connect(selected)));
      deferred.getStyleClass().add("notice");
      detail.getChildren().add(deferred);
    }
    detail.getChildren().add(connection(selected, active, state));
    VBox summary =
        new VBox(
            0, Ui.label(active ? "Действующие настройки VPN" : "Настройки VPN", "section-title"));
    FlowPane networks = new FlowPane(5, 5);
    for (String network : shown.networks()) {
      networks.getChildren().add(Ui.label(network, "token"));
    }
    summary
        .getChildren()
        .add(
            summaryRow(
                "network",
                "Сети через VPN",
                networks,
                Ui.link("Изменить", () -> actions.edit(selected, "Сети"))));
    VBox dns =
        new VBox(
            3,
            Ui.label(
                shown.dns().domains().isEmpty()
                    ? "Не настроен"
                    : String.join(", ", shown.dns().domains()),
                "muted"));
    if (!shown.dns().server().isEmpty()) {
      dns.getChildren().add(Ui.label(shown.dns().server() + " · через этот VPN", "muted"));
    }
    summary
        .getChildren()
        .add(
            summaryRow(
                "globe",
                "DNS для выбранных доменов",
                dns,
                Ui.link("Изменить", () -> actions.edit(selected, "DNS"))));
    summary
        .getChildren()
        .add(
            summaryRow(
                "arrows",
                "Другие адреса",
                Ui.label(
                    "По текущим настройкам системы.\n"
                        + "В том числе через другой VPN, если он настроен в системе.",
                    "muted"),
                new Region()));
    TitledPane technical =
        new TitledPane(
            "Технические сведения",
            Ui.label(
                "IPv6 и приложения со своим DoH вне гарантии split DNS.\nСервер: "
                    + shown.server()
                    + ":"
                    + shown.port()
                    + "\nРевизия настроек: "
                    + shown.revision(),
                "small"));
    technical.setExpanded(false);
    technical.getStyleClass().add("technical");
    summary.getChildren().add(technical);
    detail.getChildren().add(summary);
  }

  private Node connection(VpnProfile selected, boolean active, ConnectionSnapshot state) {
    boolean target =
        state.targetProfile() != null && state.targetProfile().id().equals(selected.id());
    boolean cleanup = state.errorCode().equals("CLEANUP_FAILED");
    boolean failed =
        state.state() == ConnectionSnapshot.State.ERROR
            && (target || state.targetProfile() == null || cleanup);
    boolean busy = state.busy() && (target || active);
    boolean stopping = state.state() == ConnectionSnapshot.State.DISCONNECTING;
    String status = "Не подключён";
    String description = "Подключите VPN для доступа к выбранным сетям";
    String icon = "power";
    String style = "status-idle";
    String actionText = state.appliedProfile() == null ? "Подключить" : "Переключиться";
    if (active) {
      status = "Подключён";
      description = "VPN работает для выбранных сетей";
      icon = "check";
      style = "status-connected";
      actionText = "Отключить";
    }
    if (busy) {
      status = stopping ? "Отключение…" : "Подключение…";
      description = state.stage();
      style = "status-busy";
      actionText = "Отменить";
    }
    if (failed) {
      status = cleanup ? "Требуется проверка" : "Не удалось подключиться";
      description = state.errorMessage();
      icon = "warning";
      style = "status-error";
      actionText = cleanup ? "Проверить" : "Повторить";
    }
    StackPane symbol = new StackPane(Icons.of(icon));
    symbol.getStyleClass().addAll("status-icon", style);
    if (busy) {
      ProgressIndicator progress = new ProgressIndicator();
      progress.setMaxSize(22, 22);
      symbol.getChildren().setAll(progress);
    }
    VBox text = new VBox(3, Ui.label(status, "status-title"), Ui.label(description, "muted"));
    HBox.setHgrow(text, Priority.ALWAYS);
    HBox row = new HBox(12, symbol, text);
    row.setAlignment(Pos.CENTER_LEFT);
    if (!stopping) {
      Button action =
          Ui.button(
              actionText,
              () -> {
                if (cleanup) {
                  navigation.journal(selected, true, false);
                } else if (busy || active) {
                  actions.stop();
                } else {
                  actions.connect(selected);
                }
              });
      action.setMinWidth(Region.USE_PREF_SIZE);
      action.setDisable(!cleanup && (!model.online.get() || state.busy() && !busy));
      if (!active && !busy && !cleanup) {
        action.getStyleClass().add("primary");
      }
      if (active && !busy) {
        action.setGraphic(Icons.of("power"));
      }
      row.getChildren().add(action);
    }
    Label foot =
        Ui.label(
            active ? "Подключение активно" : "Остальные адреса — по настройкам системы", "small");
    foot.setMaxWidth(Double.MAX_VALUE);
    if (cleanup) {
      foot.setText("Не считайте настройки сети восстановленными.");
    }
    HBox.setHgrow(foot, Priority.ALWAYS);
    HBox footer =
        new HBox(
            12,
            foot,
            Ui.link(
                failed ? "Открыть журнал" : "Проверить доступность",
                () -> navigation.journal(selected, !failed, failed)));
    footer.setAlignment(Pos.CENTER_LEFT);
    footer.getStyleClass().add("connection-footer");
    VBox card = new VBox(18, row, footer);
    card.getStyleClass().addAll("connection-card", failed ? "error-card" : "normal-card");
    return card;
  }

  private static Node summaryRow(String icon, String title, Node content, Node action) {
    VBox text = new VBox(6, Ui.label(title, "summary-name"), content);
    HBox.setHgrow(text, Priority.ALWAYS);
    HBox row = new HBox(16, Icons.of(icon), text, action);
    row.getStyleClass().add("summary-row");
    return row;
  }

  private String currentName(VpnProfile applied) {
    return model.profiles.stream()
        .filter(item -> item.id().equals(applied.id()))
        .map(VpnProfile::name)
        .findFirst()
        .orElse(applied.name());
  }

  static String protocol(VpnProfile profile) {
    return profile.protocol() == VpnProfile.Protocol.OPENVPN ? "OpenVPN" : "VLESS";
  }

  static String statusText(ConnectionSnapshot snapshot) {
    return switch (snapshot.state()) {
      case DISCONNECTED -> "Не подключён";
      case CONNECTING -> "Подключение…";
      case CONNECTED -> "Подключён";
      case DISCONNECTING -> "Отключение…";
      case ERROR -> "Требуется внимание";
    };
  }

  private static MenuItem item(String text, Runnable action) {
    MenuItem item = new MenuItem(text);
    item.setOnAction(event -> action.run());
    return item;
  }
}
