package app.wisprail.connection;

import app.wisprail.storage.JsonFiles;
import app.wisprail.storage.SecureFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/** Stores application-authored events only. Raw engine output never enters this journal. */
public final class EventJournal {
  private static final int MAX_EVENTS = 2000;
  private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;
  private final ArrayDeque<Event> events = new ArrayDeque<>();
  private final Path file;
  private long sequence;
  private String persistenceError = "";

  public record Event(
      long sequence, Instant time, String level, UUID profileId, String code, String message) {}

  public EventJournal(Path directory) throws IOException {
    SecureFiles.directory(directory);
    file = directory.resolve("events.jsonl");
  }

  public synchronized void add(String level, UUID profileId, String code, String message) {
    Event event = new Event(++sequence, Instant.now(), level, profileId, code, message);
    events.addLast(event);
    while (events.size() > MAX_EVENTS) {
      events.removeFirst();
    }
    try {
      if (Files.exists(file) && Files.size(file) >= MAX_FILE_BYTES) {
        Files.move(
            file,
            file.resolveSibling("events.previous.jsonl"),
            StandardCopyOption.REPLACE_EXISTING);
      }
      Files.writeString(
          file,
          JsonFiles.mapper().writeValueAsString(event) + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      SecureFiles.restrict(file);
      persistenceError = "";
    } catch (IOException exception) {
      persistenceError = "Не удалось записать журнал на диск";
    }
  }

  public synchronized List<Event> after(long lastSequence) {
    return events.stream().filter(event -> event.sequence() > lastSequence).toList();
  }

  public synchronized String persistenceError() {
    return persistenceError;
  }
}
