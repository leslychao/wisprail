package app.wisprail.ui;

import app.wisprail.agent.AgentClient;
import app.wisprail.agent.AgentProtocol;
import app.wisprail.connection.ConnectionSnapshot;
import app.wisprail.connection.EventJournal;
import app.wisprail.profile.ProfileRepository;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import app.wisprail.storage.DpapiSecretStore;
import app.wisprail.storage.JsonFiles;
import app.wisprail.storage.KeychainSecretStore;
import app.wisprail.storage.SecretStore;
import app.wisprail.storage.SecureFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** Owns UI data and background I/O; the service remains the owner of network state. */
final class DesktopModel {
  final ObservableList<VpnProfile> profiles = FXCollections.observableArrayList();
  final ObservableList<EventJournal.Event> events = FXCollections.observableArrayList();
  final ObjectProperty<ConnectionSnapshot> connection =
      new SimpleObjectProperty<>(ConnectionSnapshot.initial());
  final ObjectProperty<AppSettings> settings = new SimpleObjectProperty<>(AppSettings.defaults());
  final BooleanProperty ready = new SimpleBooleanProperty();
  final BooleanProperty online = new SimpleBooleanProperty();
  final StringProperty serviceMessage =
      new SimpleStringProperty("Подключаемся к системному компоненту…");
  final Path data;
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService ipc = Executors.newSingleThreadScheduledExecutor();
  private final AgentClient client = new AgentClient();
  private ProfileRepository repository;
  private FileChannel lock;
  private long sequence;
  private volatile boolean closing;

  DesktopModel() {
    data =
        System.getProperty("os.name").startsWith("Windows")
            ? Path.of(System.getenv("LOCALAPPDATA"), "Wisprail")
            : Path.of(
                System.getProperty("user.home"), "Library", "Application Support", "Wisprail");
  }

  void initialize(Consumer<String> failure) {
    background(
        () -> {
          SecureFiles.directory(data);
          lock =
              FileChannel.open(
                  data.resolve("ui.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          if (lock.tryLock() == null) {
            throw new IOException("Wisprail уже открыт. Проверьте значок в трее.");
          }
          SecretStore secretStore =
              System.getProperty("os.name").startsWith("Windows")
                  ? new DpapiSecretStore(data.resolve("secrets"))
                  : new KeychainSecretStore();
          repository = new ProfileRepository(data.resolve("profiles"), secretStore);
          return Files.exists(data.resolve("settings.json"))
              ? JsonFiles.read(data.resolve("settings.json"), AppSettings.class)
              : AppSettings.defaults();
        },
        loaded -> {
          settings.set(loaded);
          reload(() -> ready.set(true), failure);
          ipc.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
        },
        failure);
  }

  void reload(Runnable success, Consumer<String> failure) {
    background(
        repository::list,
        list -> {
          profiles.setAll(list);
          success.run();
        },
        failure);
  }

  void loadSecrets(VpnProfile profile, Consumer<ProfileSecrets> success, Consumer<String> failure) {
    background(
        () ->
            profile.secretReference().isEmpty()
                ? ProfileSecrets.empty()
                : repository.secrets(profile),
        success,
        failure);
  }

  void save(
      VpnProfile draft,
      ProfileSecrets secrets,
      Consumer<ProfileRepository.SaveResult> success,
      Consumer<String> failure) {
    background(
        () -> repository.save(draft, secrets),
        saved -> reload(() -> success.accept(saved), failure),
        failure);
  }

  void delete(VpnProfile profile, Runnable success, Consumer<String> failure) {
    background(
        () -> repository.delete(profile.id()),
        warning ->
            reload(
                () -> {
                  success.run();
                  if (!warning.isEmpty()) {
                    failure.accept(warning);
                  }
                },
                failure),
        failure);
  }

  void saveSettings(AppSettings value, Consumer<String> failure) {
    background(
        () -> {
          DesktopIntegration.setAutoStart(value.autoStart());
          JsonFiles.writeAtomic(data.resolve("settings.json"), value);
          return value;
        },
        settings::set,
        failure);
  }

  void command(
      AgentProtocol.Command command,
      VpnProfile profile,
      ProfileSecrets secrets,
      UUID token,
      Consumer<AgentProtocol.Response> success,
      Consumer<String> failure) {
    long revision = connection.get().revision();
    ipc.execute(
        () -> {
          try {
            var response =
                client.exchange(
                    new AgentProtocol.Request(
                        AgentProtocol.VERSION,
                        UUID.randomUUID(),
                        command,
                        profile,
                        secrets,
                        token,
                        revision,
                        sequence));
            accept(response);
            Platform.runLater(
                () -> {
                  if (response.error().isEmpty()) {
                    success.accept(response);
                  } else {
                    failure.accept(response.error());
                  }
                });
          } catch (IOException exception) {
            unavailable();
            Platform.runLater(
                () ->
                    failure.accept(
                        "Системный компонент недоступен. Проверьте его установку и права."));
          }
        });
  }

  <T> void background(Callable<T> work, Consumer<T> success, Consumer<String> failure) {
    files.execute(
        () -> {
          try {
            T result = work.call();
            Platform.runLater(() -> success.accept(result));
          } catch (IllegalArgumentException exception) {
            Platform.runLater(() -> failure.accept(exception.getMessage()));
          } catch (DesktopIntegration.Failure exception) {
            Platform.runLater(() -> failure.accept(exception.getMessage()));
          } catch (Exception exception) {
            // Storage and parser exceptions can include secret-bearing input; never expose raw
            // text.
            Platform.runLater(
                () ->
                    failure.accept(
                        "Операция не завершена. Проверьте формат файла, доступ к данным и свободное"
                            + " место."));
          }
        });
  }

  private void poll() {
    if (closing) {
      return;
    }
    try {
      accept(client.exchange(AgentProtocol.Request.status(sequence)));
    } catch (IOException exception) {
      unavailable();
    }
  }

  private void accept(AgentProtocol.Response response) {
    if (!response.events().isEmpty()) {
      sequence = response.events().getLast().sequence();
    }
    Platform.runLater(
        () -> {
          online.set(true);
          serviceMessage.set(response.journalError());
          if (!response.snapshot().equals(connection.get())) {
            connection.set(response.snapshot());
          }
          events.addAll(response.events());
          if (events.size() > 2000) {
            events.remove(0, events.size() - 2000);
          }
        });
  }

  private void unavailable() {
    sequence = 0;
    Platform.runLater(
        () -> {
          online.set(false);
          serviceMessage.set(
              "Служба Wisprail недоступна. Профили доступны; подключение требует установленного"
                  + " компонента.");
        });
  }

  void close(Runnable done) {
    closing = true;
    ipc.execute(
        () -> {
          try {
            client.exchange(
                new AgentProtocol.Request(
                    1,
                    UUID.randomUUID(),
                    AgentProtocol.Command.DISCONNECT,
                    null,
                    null,
                    null,
                    0,
                    sequence));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < deadline) {
              var response = client.exchange(AgentProtocol.Request.status(sequence));
              if (!response.snapshot().busy()) {
                break;
              }
              Thread.sleep(100);
            }
          } catch (IOException exception) {
            unavailable();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          } finally {
            try {
              client.close();
            } catch (IOException exception) {
              unavailable();
            }
            ipc.shutdown();
            files.execute(
                () -> {
                  try {
                    if (lock != null) {
                      lock.close();
                    }
                  } catch (IOException exception) {
                    System.err.println("Wisprail: не удалось закрыть локальное хранилище");
                  } finally {
                    Platform.runLater(done);
                  }
                });
            files.shutdown();
          }
        });
  }
}
