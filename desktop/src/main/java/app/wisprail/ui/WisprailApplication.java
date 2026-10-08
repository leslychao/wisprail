package app.wisprail.ui;

import app.wisprail.platform.PlatformServices;
import app.wisprail.profile.ProfileRepository;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.Stage;

public final class WisprailApplication extends Application {
  private MainWindow window;

  @Override
  public void start(Stage stage) {
    stage.setTitle("Wisprail");
    stage.setMinWidth(860);
    stage.setMinHeight(640);
    var loading =
        Ui.page(
            Ui.heading("Wisprail"),
            new ProgressIndicator(),
            Ui.label("Открываем профили…", "hint"));
    Ui.stylesheet(loading);
    stage.setScene(new Scene(loading, 1070, 700));
    stage.show();
    Task<ProfileRepository> load =
        new Task<>() {
          @Override
          protected ProfileRepository call() throws Exception {
            var data = PlatformServices.appDataDirectory();
            return new ProfileRepository(
                data.resolve("profiles"), PlatformServices.secretStore(data));
          }
        };
    load.setOnSucceeded(
        event -> {
          window = new MainWindow(stage, load.getValue(), PlatformServices.createClient());
          window.show();
        });
    load.setOnFailed(
        event -> {
          new Dialogs(stage)
              .error(
                  "Не удалось открыть данные",
                  "Хранилище профилей или системная защита секретов недоступны. Данные не изменены."
                      + " Проверьте права текущего пользователя.");
          Platform.exit();
        });
    Thread.ofPlatform().daemon(true).name("wisprail-startup").start(load);
  }

  @Override
  public void stop() {
    if (window != null) {
      window.close();
    }
  }
}
