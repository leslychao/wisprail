package app.wisprail.ui;

import app.wisprail.platform.ComponentStatus;
import app.wisprail.platform.PlatformDesktopIntegration;
import app.wisprail.platform.PlatformServices;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** User settings and the real OS component status, with no implied network-health result. */
final class SettingsView extends BorderPane {
  private final PlatformDesktopIntegration platform;
  private final AppSettings settings;
  private final BackgroundTasks tasks;
  private final Dialogs dialogs;
  private final boolean trayAvailable;
  private final Consumer<ComponentStatus> onStatus;
  private final CheckBox startup = new CheckBox();
  private final Label status = Ui.label("Не проверено", "hint");
  private final Label supported = Ui.label("Не проверено", "hint");
  private final Label version = Ui.label("Не проверено", "hint");
  private final ToggleButton generalTab = new ToggleButton("Общие");
  private final ToggleButton componentsTab = new ToggleButton("Компоненты и данные");

  SettingsView(
      PlatformDesktopIntegration platform,
      AppSettings settings,
      BackgroundTasks tasks,
      Dialogs dialogs,
      boolean trayAvailable,
      Consumer<ComponentStatus> onStatus) {
    this.platform = platform;
    this.settings = settings;
    this.tasks = tasks;
    this.dialogs = dialogs;
    this.trayAvailable = trayAvailable;
    this.onStatus = onStatus;
    ToggleGroup group = new ToggleGroup();
    generalTab.setToggleGroup(group);
    componentsTab.setToggleGroup(group);
    generalTab.getStyleClass().add("tab-button");
    componentsTab.getStyleClass().add("tab-button");
    generalTab.setOnAction(event -> showGeneral());
    componentsTab.setOnAction(event -> showComponents());
    HBox tabs = Ui.row(generalTab, componentsTab);
    tabs.getStyleClass().add("tabs");
    setTop(
        Ui.page(
            Ui.heading("Настройки"),
            Ui.label("Поведение приложения. Сети и DNS настраиваются в профиле VPN.", "hint"),
            tabs));
    showGeneral();
  }

  private void showGeneral() {
    generalTab.setSelected(true);
    startup.setAccessibleText("Запускать при входе в систему");
    startup.setDisable(true);
    tasks.run(
        "Проверка автозапуска",
        platform::isAutoStartEnabled,
        enabled -> {
          startup.setSelected(enabled);
          startup.setDisable(false);
        },
        failure -> {
          startup.setDisable(false);
          dialogs.error(
              "Проверка автозапуска",
              "Не удалось прочитать системную настройку. "
                  + "Повторите проверку, открыв вкладку «Общие».");
        });
    startup.setOnAction(
        event -> {
          boolean enabled = startup.isSelected();
          startup.setDisable(true);
          tasks.run(
              "Настройка автозапуска",
              () -> {
                platform.setAutoStart(enabled);
                return platform.isAutoStartEnabled();
              },
              actual -> {
                startup.setSelected(actual);
                startup.setDisable(false);
              },
              failure -> {
                startup.setSelected(!enabled);
                startup.setDisable(false);
                dialogs.error(
                    "Настройка автозапуска",
                    "Система не подтвердила изменение. "
                        + "Проверьте разрешения или повторите действие.");
              });
        });
    ComboBox<AppSettings.CloseAction> close = new ComboBox<>();
    close.getItems().setAll(AppSettings.CloseAction.values());
    if (!trayAvailable) {
      close.getItems().remove(AppSettings.CloseAction.TRAY);
    }
    close.setValue(
        !trayAvailable && settings.closeAction() == AppSettings.CloseAction.TRAY
            ? AppSettings.CloseAction.ASK
            : settings.closeAction());
    close.setAccessibleText("Действие при закрытии окна");
    close.setOnAction(
        event -> {
          AppSettings.CloseAction action = close.getValue();
          tasks.run(
              "Сохранение настройки",
              () -> {
                settings.setCloseAction(action);
                return action;
              },
              saved -> {});
        });
    CheckBox notifications = new CheckBox();
    notifications.setAccessibleText("Сообщать об ошибках и обрыве");
    notifications.setSelected(settings.notifications());
    notifications.setOnAction(
        event -> {
          boolean enabled = notifications.isSelected();
          tasks.run(
              "Сохранение настройки",
              () -> {
                settings.setNotifications(enabled);
                return enabled;
              },
              saved -> {});
        });
    VBox card =
        new VBox(
            setting(
                "Запускать при входе в систему",
                "Открыть приложение, без подключения VPN.",
                startup),
            setting("При закрытии окна", "Действие с приложением и работающим VPN.", close),
            setting(
                "Сообщать об ошибках и обрыве",
                "Без уведомлений об обычных действиях.",
                notifications));
    card.getStyleClass().add("settings-card");
    VBox content =
        Ui.page(
            Ui.label("Работа приложения", "section-label"),
            card,
            Ui.label("Изменения здесь сохраняются сразу.", "hint"),
            Ui.button("О программе", "link", this::about));
    if (!trayAvailable) {
      content
          .getChildren()
          .add(
              Ui.notice(
                  "Системный трей недоступен. "
                      + "Приложение не будет скрываться при закрытии окна.",
                  "warning"));
    }
    setCenter(Ui.scroll(content));
  }

  void showComponents() {
    componentsTab.setSelected(true);
    VBox card =
        new VBox(
            setting(
                "Сетевой компонент", "Комплектный sing-box и системная служба управления.", status),
            setting(
                "Версия движка", "Проверяется фактический комплектный исполняемый файл.", version),
            setting(
                "Поддерживаемые подключения", "По возможностям комплектного движка.", supported),
            setting(
                "Проверка компонента",
                "Не изменяет маршруты или DNS.",
                Ui.button("Проверить", "small-button", this::checkComponents)),
            setting(
                "Системное разрешение",
                "Требуется для виртуального интерфейса и собственного DNS.",
                Ui.button("Установить / разрешить", "small-button", this::requestInstallation)));
    card.getStyleClass().add("settings-card");
    VBox data =
        new VBox(
            setting(
                "Профили",
                "Данные текущего пользователя.",
                Ui.button(
                    "Открыть папку",
                    "small-button",
                    () -> openDirectory(PlatformServices.appDataDirectory()))),
            setting(
                "Журналы",
                "Очистка экрана журнала не удаляет файлы.",
                Ui.button(
                    "Открыть папку",
                    "small-button",
                    () -> openDirectory(PlatformServices.logDirectory()))));
    data.getStyleClass().add("settings-card");
    setCenter(
        Ui.scroll(
            Ui.page(
                card,
                Ui.label("Данные приложения", "section-label"),
                data,
                Ui.notice(
                    "Пароли и закрытые ключи защищены DPAPI пользователя Windows "
                        + "или Keychain macOS и не показываются здесь.",
                    ""),
                Ui.button("О программе", "link", this::about))));
  }

  void checkComponents() {
    status.setText("Проверяем…");
    tasks.run(
        "Проверка компонента",
        platform::serviceStatus,
        result -> {
          status.setText(result.message());
          onStatus.accept(result);
        });
    tasks.run(
        "Проверка версии движка",
        () -> {
          Path root = PlatformServices.installationDirectory();
          Path binary =
              root.resolve(
                  PlatformServices.isWindows() ? "app/engine/sing-box.exe" : "app/engine/sing-box");
          if (!Files.isRegularFile(binary)) {
            return "Комплектный движок не найден";
          }
          Process process =
              new ProcessBuilder(binary.toString(), "version").redirectErrorStream(true).start();
          try (InputStream output = process.getInputStream()) {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
              throw new IOException("Проверка версии превысила время ожидания");
            }
            byte[] bytes = output.readNBytes(16_385);
            if (bytes.length > 16_384 || process.exitValue() != 0) {
              throw new IOException("Не удалось проверить версию компонента");
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
          } finally {
            if (process.isAlive()) {
              process.destroyForcibly();
            }
          }
        },
        result -> {
          version.setText(result);
          supported.setText(
              result.contains("1.14.2") && result.contains("with_openvpn")
                  ? "OpenVPN · VLESS"
                  : "Возможности не подтверждены");
        });
  }

  private void requestInstallation() {
    if (!dialogs.confirm(
        "Разрешите работу VPN",
        "Приложению нужно создать виртуальный сетевой "
            + "интерфейс и применять собственные настройки DNS. Далее откроется системное окно "
            + "подтверждения. Системный пароль вводится только там.",
        "Продолжить",
        false)) {
      return;
    }
    tasks.run(
        "Системное разрешение",
        () -> {
          platform.requestServiceInstallation();
          return true;
        },
        requested -> {
          status.setText("Запрос передан системе. После подтверждения нажмите «Проверить».");
        });
  }

  private void openDirectory(Path directory) {
    tasks.run(
        "Открытие папки",
        () -> {
          Files.createDirectories(directory);
          if (!Desktop.isDesktopSupported()) {
            throw new IOException("Системный файловый менеджер недоступен");
          }
          Desktop.getDesktop().open(directory.toFile());
          return true;
        },
        opened -> {});
  }

  private void about() {
    Properties properties = new Properties();
    try (InputStream resource =
        SettingsView.class.getResourceAsStream("/app/wisprail/ui/version.properties")) {
      if (resource != null) {
        properties.load(resource);
      }
    } catch (IOException exception) {
      dialogs.error("Сведения о версии", "Не удалось прочитать сведения о сборке.");
      return;
    }
    dialogs.information(
        "О программе",
        "Wisprail "
            + properties.getProperty("version", "development")
            + "\nJava "
            + System.getProperty("java.version")
            + " · "
            + System.getProperty("os.name")
            + "\nСетевой компонент: sing-box 1.14.2"
            + "\n\nJavaFX — GPL v2 с Classpath Exception; JNA — Apache 2.0/LGPL 2.1; "
            + "Gson, gRPC, dnsjava — Apache 2.0. sing-box — GPL v3."
            + "\nПолные применимые уведомления и лицензии находятся в дистрибутиве.");
  }

  private static HBox setting(String title, String description, Node control) {
    VBox text = new VBox(5, Ui.label(title, "field-label"), Ui.label(description, "hint"));
    HBox.setHgrow(text, Priority.ALWAYS);
    HBox row = Ui.row(text, control);
    row.getStyleClass().add("setting-row");
    return row;
  }
}
