package app.wisprail.ui;

import app.wisprail.connection.ConnectionGateway;
import app.wisprail.platform.ComponentStatus;
import app.wisprail.platform.PlatformDesktopIntegration;
import app.wisprail.platform.PlatformServices;
import app.wisprail.profile.ProfileRepository;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.Stage;

public final class WisprailApplication extends Application {
  private record Startup(ProfileRepository repository, ComponentStatus component) {}

  private final Callable<ProfileRepository> openRepository;
  private final Callable<ComponentStatus> prepareComponent;
  private final Supplier<ConnectionGateway> createGateway;
  private MainWindow window;
  private Task<Startup> startup;
  private boolean stopped;

  public WisprailApplication() {
    this(
        () -> {
          var data = PlatformServices.appDataDirectory();
          return new ProfileRepository(data.resolve("profiles"), PlatformServices.secretStore(data));
        },
        () ->
            PlatformDesktopIntegration.create(PlatformServices.installationDirectory())
                .prepareService(),
        PlatformServices::createClient);
  }

  WisprailApplication(
      Callable<ProfileRepository> openRepository,
      Callable<ComponentStatus> prepareComponent,
      Supplier<ConnectionGateway> createGateway) {
    this.openRepository = openRepository;
    this.prepareComponent = prepareComponent;
    this.createGateway = createGateway;
  }

  @Override
  public void start(Stage stage) {
    stage.setTitle("Wisprail");
    stage.setMinWidth(860);
    stage.setMinHeight(640);
    var loading =
        Ui.page(
            Ui.heading("Wisprail"),
            new ProgressIndicator(),
            Ui.label("Открываем профили и подготавливаем компонент…", "hint"));
    Ui.stylesheet(loading);
    stage.setScene(new Scene(loading, 1070, 700));
    stage.show();
    startup =
        new Task<>() {
          @Override
          protected Startup call() throws Exception {
            ProfileRepository repository = openRepository.call();
            ComponentStatus component;
            try {
              component = prepareComponent.call();
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw exception;
            } catch (Exception exception) {
              String message = exception.getMessage();
              component =
                  new ComponentStatus(
                      ComponentStatus.State.ERROR,
                      message == null || message.isBlank()
                          ? "Не удалось подготовить компонент. Повторите установку в настройках."
                          : message);
            }
            return new Startup(repository, component);
          }
        };
    startup.setOnSucceeded(
        event -> {
          if (stopped) {
            return;
          }
          Startup result = startup.getValue();
          window =
              new MainWindow(stage, result.repository(), createGateway.get(), result.component());
          window.show();
        });
    startup.setOnFailed(
        event -> {
          if (stopped) {
            return;
          }
          new Dialogs(stage)
              .error(
                  "Не удалось открыть данные",
                  "Хранилище профилей или системная защита секретов недоступны. Данные не изменены."
                      + " Проверьте права текущего пользователя.");
          Platform.exit();
        });
    Thread.ofPlatform().daemon(true).name("wisprail-startup").start(startup);
  }

  @Override
  public void stop() {
    stopped = true;
    if (startup != null) {
      startup.cancel();
    }
    if (window != null) {
      window.close();
    }
  }
}
