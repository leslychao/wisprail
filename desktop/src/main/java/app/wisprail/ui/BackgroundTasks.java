package app.wisprail.ui;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;

/** Owns desktop IO work and returns results to the FX thread while the window is alive. */
final class BackgroundTasks implements AutoCloseable {
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          Thread.ofPlatform().daemon(true).name("wisprail-ui-io-", 0).factory());
  private final BiConsumer<String, Throwable> errors;
  private volatile boolean closed;

  BackgroundTasks(BiConsumer<String, Throwable> errors) {
    this.errors = errors;
  }

  <T> void run(String operation, Callable<T> work, Consumer<T> success) {
    run(operation, work, success, failure -> errors.accept(operation, failure));
  }

  <T> void run(
      String operation, Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) {
    try {
      executor.execute(
          () -> {
            try {
              T value = work.call();
              dispatch(() -> success.accept(value));
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              dispatch(() -> failure.accept(exception));
            } catch (Exception exception) {
              dispatch(() -> failure.accept(exception));
            }
          });
    } catch (RejectedExecutionException exception) {
      if (!closed) {
        failure.accept(exception);
      }
    }
  }

  <T> void observe(String operation, CompletionStage<T> stage, Consumer<T> success) {
    observe(stage, success, failure -> errors.accept(operation, failure));
  }

  <T> void observe(CompletionStage<T> stage, Consumer<T> success, Consumer<Throwable> failed) {
    stage.whenComplete(
        (value, failure) ->
            dispatch(
                () -> {
                  if (failure != null) {
                    Throwable cause =
                        failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause()
                            : failure;
                    failed.accept(cause);
                  } else {
                    success.accept(value);
                  }
                }));
  }

  void dispatch(Runnable action) {
    if (!closed) {
      Platform.runLater(
          () -> {
            if (!closed) {
              action.run();
            }
          });
    }
  }

  @Override
  public void close() {
    closed = true;
    executor.shutdownNow();
  }
}
