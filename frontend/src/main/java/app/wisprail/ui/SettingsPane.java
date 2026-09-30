package app.wisprail.ui;

import app.wisprail.agent.AgentProtocol;
import java.awt.Desktop;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

final class SettingsPane extends BorderPane {
  private boolean updating;
  private final BooleanProperty componentPending = new SimpleBooleanProperty();

  SettingsPane(Window owner, DesktopModel model, boolean trayAvailable) {

    CheckBox startup = new CheckBox();
    startup.getStyleClass().add("setting-switch");
    startup.setAccessibleText("Запускать при входе в систему");
    startup.setDisable(!DesktopIntegration.packaged());
    CheckBox notifications = new CheckBox();
    notifications.getStyleClass().add("setting-switch");
    notifications.setAccessibleText("Сообщать об ошибках и обрыве");
    notifications.setDisable(!trayAvailable);
    ComboBox<String> closing = new ComboBox<>();
    closing.getItems().addAll("Спрашивать", "Отключать VPN и выходить");
    if (trayAvailable) {
      closing.getItems().add("Скрывать в трей");
    }
    closing.setAccessibleText("При закрытии окна");
    Runnable refresh =
        () -> {
          updating = true;
          startup.setSelected(model.settings.get().autoStart());
          notifications.setSelected(model.settings.get().notifications());
          closing.setValue(
              switch (model.settings.get().closeAction()) {
                case ASK -> "Спрашивать";
                case EXIT -> "Отключать VPN и выходить";
                case TRAY -> trayAvailable ? "Скрывать в трей" : "Спрашивать";
              });
          updating = false;
        };
    Runnable save =
        () -> {
          if (!updating) {
            var action =
                switch (closing.getValue()) {
                  case "Скрывать в трей" -> AppSettings.CloseAction.TRAY;
                  case "Отключать VPN и выходить" -> AppSettings.CloseAction.EXIT;
                  default -> AppSettings.CloseAction.ASK;
                };
            model.saveSettings(
                new AppSettings(startup.isSelected(), action, notifications.isSelected()),
                message -> {
                  refresh.run();
                  Ui.error(owner, message);
                });
          }
        };
    startup.setOnAction(event -> save.run());
    notifications.setOnAction(event -> save.run());
    closing.setOnAction(event -> save.run());
    model.settings.addListener((observable, previous, current) -> refresh.run());
    refresh.run();
    VBox preferences =
        new VBox(
            setting(
                "Запускать при входе в систему",
                "Открывать интерфейс без автоматического подключения VPN",
                startup),
            setting("При закрытии окна", "Что делать с приложением и активным VPN", closing),
            setting(
                "Сообщать об ошибках и обрыве",
                "Только важные события подключения",
                notifications));
    preferences.getStyleClass().add("settings-section");
    closing.setPrefWidth(192);
    VBox general =
        Ui.box(
            Ui.label("Работа приложения", "summary-name"),
            preferences,
            Ui.label(
                "Автозапуск и трей — предпочтения приложения. Сети и DNS настраиваются в профиле"
                    + " VPN.",
                "neutral-notice"),
            Ui.label("Настройки приложения сохраняются сразу.", "small"),
            Ui.link("О программе", () -> about(owner)));
    general.setPadding(new Insets(24, 32, 24, 32));
    Label componentStatus = Ui.label("", "muted");
    componentStatus
        .textProperty()
        .bind(
            Bindings.when(model.online)
                .then("Системный компонент доступен")
                .otherwise("Системный компонент недоступен"));
    Label componentResult = Ui.label("", "muted");
    Button install =
        Ui.button(
            "Установить / разрешить системный компонент",
            () -> {
              if (Ui.confirm(
                  owner,
                  "Системное разрешение",
                  "ОС запросит разрешение на установку или запуск службы Wisprail. Системный"
                      + " пароль вводится только в диалоге ОС.",
                  "Продолжить")) {
                componentPending.set(true);
                componentResult.setText("Ожидаем системного разрешения и результата установщика…");
                model.background(
                    () -> {
                      DesktopIntegration.installService();
                      return Boolean.TRUE;
                    },
                    ignored -> {
                      componentPending.set(false);
                      componentResult.setText(
                          "Установщик завершился. Доступность службы подтверждается отдельно"
                              + " через соединение с компонентом.");
                    },
                    message -> {
                      componentPending.set(false);
                      componentResult.setText(message);
                      Ui.error(owner, message);
                    });
              }
            });
    Button remove =
        Ui.button(
            "Удалить системный компонент",
            () -> {
              if (model.connection.get().busy()
                  || model.connection.get().appliedProfile() != null) {
                Ui.error(owner, "Сначала отключите VPN и дождитесь подтверждения очистки.");
                return;
              }
              if (Ui.confirm(
                  owner,
                  "Удалить системный компонент?",
                  "Подключение VPN станет недоступно. Сохранённые профили останутся.",
                  "Удалить")) {
                componentPending.set(true);
                componentResult.setText("Удаляем системный компонент…");
                model.background(
                    () -> {
                      DesktopIntegration.removeService();
                      return Boolean.TRUE;
                    },
                    ignored -> {
                      componentPending.set(false);
                      componentResult.setText("Удаление завершено.");
                    },
                    message -> {
                      componentPending.set(false);
                      componentResult.setText(message);
                      Ui.error(owner, message);
                    });
              }
            });
    Button folder =
        Ui.button(
            "Открыть папку профилей",
            () ->
                model.background(
                    () -> {
                      Desktop.getDesktop().open(model.data.resolve("profiles").toFile());
                      return Boolean.TRUE;
                    },
                    ignored -> {},
                    message -> Ui.error(owner, message)));
    Button check =
        Ui.button(
            "Проверить",
            () -> {
              componentPending.set(true);
              componentResult.setText("Проверяем компонент…");
              model.command(
                  AgentProtocol.Command.DIAGNOSE,
                  null,
                  null,
                  null,
                  response -> {
                    componentPending.set(false);
                    componentResult.setText(
                        String.join(
                            "\n",
                            response.checks().stream()
                                .map(
                                    result ->
                                        result.name()
                                            + ": "
                                            + result.status()
                                            + " · "
                                            + result.message())
                                .toList()));
                  },
                  message -> {
                    componentPending.set(false);
                    componentResult.setText(message);
                  });
            });
    check.disableProperty().bind(componentPending.or(model.online.not()));
    install.disableProperty().bind(componentPending);
    remove.disableProperty().bind(componentPending);
    VBox network =
        new VBox(
            setting("sing-box 1.14.2", "Компонент в комплекте приложения", componentStatus),
            setting(
                "Поддерживаемые подключения",
                "По возможностям установленного компонента",
                Ui.label("OpenVPN · VLESS TCP TLS / Reality", "small")),
            setting("Проверка компонента", "Доступность и результаты реальной диагностики", check));
    network.getStyleClass().add("settings-section");
    VBox dataSection =
        new VBox(setting("Профили", "Отдельная папка для данных приложения", folder));
    dataSection.getStyleClass().add("settings-section");
    componentResult.visibleProperty().bind(componentResult.textProperty().isNotEmpty());
    componentResult.managedProperty().bind(componentResult.visibleProperty());
    VBox components =
        Ui.box(
            Ui.label("Сетевой компонент", "summary-name"),
            network,
            new HBox(8, install, remove),
            componentResult,
            Ui.label("Данные приложения", "summary-name"),
            dataSection,
            Ui.label(
                "Пароли и закрытые ключи не показываются здесь. Используется защищённое хранилище"
                    + " системы.",
                "neutral-notice"),
            Ui.label("Журнал службы и безопасный отчёт доступны в разделе «Журнал».", "small"),
            Ui.button("О программе", () -> about(owner)));
    components.setPadding(new Insets(24, 32, 24, 32));
    Tab common = new Tab("Общие", Ui.scroll(general));
    Tab data = new Tab("Компоненты и данные", Ui.scroll(components));
    common.setClosable(false);
    data.setClosable(false);
    TabPane tabs = new TabPane(common, data);
    VBox heading =
        new VBox(
            6,
            Ui.label("Настройки", "title"),
            Ui.label("Поведение приложения. Сети и DNS настраиваются в профиле VPN.", "muted"));
    heading.setPadding(new Insets(26, 32, 22, 32));
    setTop(heading);
    setCenter(tabs);
    widthProperty()
        .addListener(
            (observable, oldWidth, width) -> {
              double inset = Math.max(0, (width.doubleValue() - 808) / 2);
              setPadding(new Insets(0, inset, 0, inset));
            });
  }

  private static void about(Window owner) {
    Alert dialog =
        new Alert(
            Alert.AlertType.INFORMATION,
            "Wisprail "
                + AppVersion.value()
                + "\nJava "
                + System.getProperty("java.version")
                + " · JavaFX 21\nsing-box 1.14.2\n"
                + System.getProperty("os.name")
                + "\n\nIPv6 и собственный DoH приложений вне гарантии split DNS.\n"
                + "Лицензии зависимостей находятся в каталоге приложения.",
            ButtonType.OK);
    dialog.initOwner(owner);
    dialog.setHeaderText("Wisprail");
    Ui.style(dialog);
    dialog.showAndWait();
  }

  private static Node setting(String title, String help, Node control) {
    VBox description = new VBox(5, Ui.label(title, "summary-name"), Ui.label(help, "small"));
    HBox.setHgrow(description, Priority.ALWAYS);
    HBox row = new HBox(28, description, control);
    row.setAlignment(Pos.CENTER_LEFT);
    row.getStyleClass().add("setting-row");
    return row;
  }
}
