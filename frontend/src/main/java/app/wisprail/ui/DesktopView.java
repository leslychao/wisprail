package app.wisprail.ui;

import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

/** Owns navigation and the current editor; connection state belongs to the service. */
final class DesktopView extends BorderPane {
  private final Window owner;
  private final DesktopModel model;
  private final ConnectionsPane connections;
  private final JournalPane journal;
  private final ToggleGroup navigation = new ToggleGroup();
  private final StackPane workspace = new StackPane();
  private final Label toast = Ui.label("", "toast");
  private final PauseTransition toastLifetime = new PauseTransition(Duration.seconds(3));
  private final SettingsPane settings;
  private ProfileEditor editor;
  private int page;

  DesktopView(Window owner, DesktopModel model, boolean trayAvailable) {
    this.owner = owner;
    this.model = model;
    connections = new ConnectionsPane(owner, model, this);
    journal = new JournalPane(owner, model);
    settings = new SettingsPane(owner, model, trayAvailable);
    HBox links = new HBox(22);
    links.setAlignment(Pos.CENTER_LEFT);
    String[] names = {"Подключения", "Журнал", "Настройки"};
    for (int index = 0; index < names.length; index++) {
      int selectedPage = index;
      ToggleButton button = new ToggleButton(names[index]);
      button.setToggleGroup(navigation);
      button.setMaxHeight(Double.MAX_VALUE);
      button.setId("navigation-" + index);
      button.getStyleClass().add("navigation-button");
      button.setOnAction(event -> showPage(selectedPage));
      links.getChildren().add(button);
    }
    StackPane brandIcon = new StackPane(Icons.of("shield"));
    brandIcon.getStyleClass().add("brand-mark");
    HBox brand = new HBox(9, brandIcon, Ui.label("VPN", "brand"));
    brand.setAlignment(Pos.CENTER_LEFT);
    Label status = Ui.label("Не подключён", "header-status");
    status.setWrapText(false);
    Label dot = Ui.label("●", "success-dot");
    Runnable refresh =
        () -> {
          var state = model.connection.get();
          var applied = state.appliedProfile();
          String text = ConnectionsPane.statusText(state);
          dot.getStyleClass().setAll(applied == null ? "muted-dot" : "success-dot");
          if (state.state() == ConnectionSnapshot.State.DISCONNECTED) {
            text = "VPN отключён";
          }
          if (applied != null) {
            text =
                model.profiles.stream()
                    .filter(profile -> profile.id().equals(applied.id()))
                    .map(VpnProfile::name)
                    .findFirst()
                    .orElse(applied.name());
          }
          status.setText(text);
          status.getStyleClass().remove("header-connected");
          if (applied != null) {
            status.getStyleClass().add("header-connected");
          }
        };
    model.connection.addListener((observable, before, value) -> refresh.run());
    model.profiles.addListener((ListChangeListener<VpnProfile>) change -> refresh.run());
    refresh.run();
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox connectionStatus = new HBox(7, dot, status);
    connectionStatus.setAlignment(Pos.CENTER_LEFT);
    HBox header = new HBox(34, brand, links, spacer, connectionStatus);
    header.setAlignment(Pos.CENTER_LEFT);
    header.setPadding(new Insets(0, 24, 0, 24));
    header.setMinHeight(66);
    header.setPrefHeight(66);
    header.getStyleClass().add("header");
    Label banner = Ui.label("", "service-banner");
    banner.textProperty().bind(model.serviceMessage);
    banner.visibleProperty().bind(model.serviceMessage.isNotEmpty().and(model.online));
    banner.managedProperty().bind(banner.visibleProperty());
    banner.setMaxWidth(Double.MAX_VALUE);
    setTop(new VBox(header, banner));
    setCenter(workspace);
    toast.setMouseTransparent(true);
    toastLifetime.setOnFinished(event -> workspace.getChildren().remove(toast));
    showPage(0);
  }

  ConnectionsPane connections() {
    return connections;
  }

  void showSettings() {
    showPage(2);
  }

  boolean canLeaveEditor() {
    return editor == null || editor.canLeave();
  }

  private void showPage(int index) {
    if (!canLeaveEditor()) {
      navigation.selectToggle(navigation.getToggles().get(page));
      return;
    }
    editor = null;
    page = index;
    Node content =
        switch (index) {
          case 1 -> journal;
          case 2 -> settings;
          default -> connections;
        };
    workspace.getChildren().setAll(content);
    navigation.selectToggle(navigation.getToggles().get(index));
  }

  void journal(VpnProfile profile, boolean diagnostics, boolean errors) {
    showPage(1);
    if (page == 1) {
      journal.select(profile, diagnostics, errors);
    }
  }

  void openEditor(
      VpnProfile profile,
      ProfileSecrets secrets,
      String tab,
      BiConsumer<VpnProfile, ProfileSecrets> check,
      Consumer<ProfileEditor.Result> save) {
    if (!canLeaveEditor()) {
      return;
    }
    editor = new ProfileEditor(owner, model, profile, secrets, check, save, () -> showPage(0));
    editor.selectTab(tab);
    page = 0;
    navigation.selectToggle(navigation.getToggles().getFirst());
    workspace.getChildren().setAll(editor);
  }

  void setSaving(boolean value) {
    if (editor != null) {
      editor.setSaving(value);
    }
  }

  void saved() {
    editor = null;
    showPage(0);
    notifyUser("Профиль сохранён");
  }

  void notifyUser(String text) {
    toast.setText("✓  " + text);
    workspace.getChildren().remove(toast);
    workspace.getChildren().add(toast);
    StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
    StackPane.setMargin(toast, new Insets(18));
    toastLifetime.playFromStart();
  }

  void dispose() {
    toastLifetime.stop();
  }
}
