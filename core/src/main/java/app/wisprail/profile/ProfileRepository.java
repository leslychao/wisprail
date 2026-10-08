package app.wisprail.profile;

import app.wisprail.storage.JsonCodec;
import app.wisprail.storage.PrivateFiles;
import app.wisprail.storage.SecretStore;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ProfileRepository {
  public static final int MAX_PROFILE_BYTES = 2 * 1024 * 1024;
  private static final int MAX_PROFILES = 1000;
  private static final long MAX_REPOSITORY_BYTES = 32L * 1024 * 1024;
  private final Path directory;
  private final SecretStore secrets;

  public ProfileRepository(Path directory, SecretStore secrets) throws IOException {
    this.directory = PrivateFiles.directory(directory.toAbsolutePath().normalize());
    this.secrets = secrets;
  }

  public synchronized List<Profile> list() throws IOException {
    List<Profile> profiles = new ArrayList<>();
    long bytes = 0;
    try (var paths = Files.newDirectoryStream(directory, "*.json")) {
      for (Path path : paths) {
        if (profiles.size() >= MAX_PROFILES || (bytes += Files.size(path)) > MAX_REPOSITORY_BYTES) {
          throw new IOException("Хранилище превышает лимит 1000 профилей или 32 МиБ");
        }
        Profile profile = readProfile(path);
        if (!path.getFileName().toString().equals(profile.id() + ".json")) {
          throw new IOException("Идентификатор профиля не соответствует файлу");
        }
        profiles.add(profile);
      }
    }
    profiles.sort(Comparator.comparing(Profile::name, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(profiles);
  }

  public synchronized Optional<Profile> find(UUID id) throws IOException {
    Path path = path(id);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    Profile profile = readProfile(path);
    if (!profile.id().equals(id)) {
      throw new IOException("Идентификатор профиля не соответствует файлу");
    }
    return Optional.of(profile);
  }

  public ProfileSecrets loadSecrets(Profile profile) throws IOException {
    if (profile.secretRef().isEmpty()) {
      return ProfileSecrets.empty();
    }
    byte[] value =
        secrets
            .read(profile.secretRef())
            .orElseThrow(() -> new IOException("Сохранённые данные доступа недоступны"));
    try {
      ProfileSecrets result =
          JsonCodec.readSecrets(
              JsonCodec.parseStrict(
                  StandardCharsets.UTF_8
                      .newDecoder()
                      .decode(java.nio.ByteBuffer.wrap(value))
                      .toString(),
                  16));
      if (result == null) {
        throw new IOException("Повреждены сохранённые данные доступа");
      }
      return result;
    } catch (JsonParseException | IllegalArgumentException e) {
      throw new IOException("Повреждены сохранённые данные доступа", e);
    } finally {
      Arrays.fill(value, (byte) 0);
    }
  }

  public synchronized Profile save(Profile draft, long expectedRevision, ProfileSecrets newSecrets)
      throws IOException {
    ProfileValidator.requireValid(draft, newSecrets);
    try (FileChannel channel = lockChannel();
        FileLock lock = channel.lock()) {
      if (!lock.isValid()) {
        throw new IOException("Не удалось заблокировать хранилище");
      }
      recoverSecretTransition();
      Optional<Profile> previous = find(draft.id());
      requireRevision(previous, expectedRevision);
      if (previous.isPresent() && previous.get().type() != draft.type()) {
        throw new IOException("Тип сохранённого профиля нельзя изменить");
      }
      String oldReference = previous.map(Profile::secretRef).orElse("");
      boolean changedSecrets =
          previous.isEmpty() || !loadSecrets(previous.get()).equals(newSecrets);
      String newReference =
          changedSecrets
              ? (newSecrets.isEmpty() ? "" : UUID.randomUUID().toString())
              : oldReference;
      Profile stored =
          canonicalize(draft)
              .withIdentity(draft.id(), expectedRevision + 1, draft.name().strip(), newReference);
      byte[] profileBytes =
          JsonCodec.gson().toJson(new ProfileDocument(stored)).getBytes(StandardCharsets.UTF_8);
      if (profileBytes.length > MAX_PROFILE_BYTES) {
        throw new IOException("Профиль превышает допустимый размер");
      }
      checkRepositoryCapacity(draft.id(), profileBytes.length);
      if (changedSecrets) {
        writeSecretTransition(new SecretTransition(draft.id(), oldReference, newReference));
      }
      if (changedSecrets && !newReference.isEmpty()) {
        byte[] value = JsonCodec.gson().toJson(newSecrets).getBytes(StandardCharsets.UTF_8);
        try {
          if (value.length > ProfileValidator.MAX_TEXT_BYTES) {
            throw new IOException("Данные доступа превышают 1 МиБ");
          }
          secrets.write(newReference, value);
        } finally {
          Arrays.fill(value, (byte) 0);
        }
      }
      PrivateFiles.writeAtomic(path(draft.id()), profileBytes);
      if (changedSecrets) {
        recoverSecretTransition();
      }
      return stored;
    }
  }

  public synchronized void delete(UUID id, long expectedRevision) throws IOException {
    try (FileChannel channel = lockChannel();
        FileLock lock = channel.lock()) {
      if (!lock.isValid()) {
        throw new IOException("Не удалось заблокировать хранилище");
      }
      recoverSecretTransition();
      Optional<Profile> previous = find(id);
      requireRevision(previous, expectedRevision);
      if (previous.isEmpty()) {
        return;
      }
      writeSecretTransition(new SecretTransition(id, previous.get().secretRef(), ""));
      Files.delete(path(id));
      recoverSecretTransition();
    }
  }

  public static Profile copy(Profile original) {
    Profile copy = withoutSecrets(original);
    String name =
        original.name().length() > 71 ? original.name().substring(0, 71) : original.name();
    return copy.withIdentity(UUID.randomUUID(), 0, name + " — копия", "");
  }

  public void exportProfile(Profile profile, Path destination) throws IOException {
    Profile exported =
        withoutSecrets(profile).withIdentity(UUID.randomUUID(), 0, profile.name(), "");
    byte[] value =
        JsonCodec.gson().toJson(new ProfileDocument(exported)).getBytes(StandardCharsets.UTF_8);
    PrivateFiles.writeAtomic(destination, value);
  }

  private static Profile withoutSecrets(Profile profile) {
    VpnSettings settings = profile.settings();
    if (settings instanceof OpenVpnSettings openVpn) {
      settings =
          new OpenVpnSettings(
              openVpn.transport(),
              openVpn.username(),
              true,
              "",
              "",
              openVpn.cipher(),
              openVpn.authDigest(),
              openVpn.tlsServerName(),
              openVpn.tlsKeyDirection());
    }
    return new Profile(
        profile.id(),
        profile.revision(),
        profile.name(),
        profile.server(),
        profile.port(),
        settings,
        profile.networks(),
        profile.dns(),
        profile.domains(),
        profile.healthUrl(),
        "");
  }

  private static Profile canonicalize(Profile profile) {
    String server =
        profile.server().matches("[0-9.]+")
            ? profile.server()
            : DomainNames.normalize(profile.server());
    return new Profile(
        profile.id(),
        profile.revision(),
        profile.name().strip(),
        server,
        profile.port(),
        profile.settings(),
        profile.networks(),
        profile.dns(),
        profile.domains().stream().map(DomainNames::normalize).toList(),
        profile.healthUrl(),
        profile.secretRef());
  }

  private Profile readProfile(Path path) throws IOException {
    byte[] value = PrivateFiles.read(path, MAX_PROFILE_BYTES);
    try {
      ProfileDocument document =
          JsonCodec.readProfileDocument(
              JsonCodec.parseStrict(
                  StandardCharsets.UTF_8
                      .newDecoder()
                      .decode(java.nio.ByteBuffer.wrap(value))
                      .toString(),
                  16));
      if (document == null
          || document.formatVersion() != ProfileDocument.VERSION
          || document.profile() == null) {
        throw new IOException("Неподдержанный формат сохранённого профиля");
      }
      List<ValidationIssue> issues = ProfileValidator.validateProfile(document.profile());
      if (!issues.isEmpty()) {
        throw new IOException("Повреждён сохранённый профиль: " + issues.getFirst().message());
      }
      return document.profile();
    } catch (JsonParseException | IllegalArgumentException e) {
      throw new IOException("Не удалось прочитать сохранённый профиль", e);
    }
  }

  private void checkRepositoryCapacity(UUID changedId, int changedBytes) throws IOException {
    int count = 1;
    long bytes = changedBytes;
    try (var paths = Files.newDirectoryStream(directory, "*.json")) {
      for (Path path : paths) {
        if (!path.equals(path(changedId))) {
          count++;
          bytes += Files.size(path);
        }
        if (count > MAX_PROFILES || bytes > MAX_REPOSITORY_BYTES) {
          throw new IOException("Достигнут лимит хранилища: 1000 профилей или 32 МиБ");
        }
      }
    }
  }

  private record SecretTransition(UUID profileId, String previousReference, String nextReference) {}

  private void writeSecretTransition(SecretTransition transition) throws IOException {
    PrivateFiles.writeAtomic(
        directory.resolve("secret-transition.pending"),
        JsonCodec.gson().toJson(transition).getBytes(StandardCharsets.UTF_8));
  }

  private void recoverSecretTransition() throws IOException {
    Path pending = directory.resolve("secret-transition.pending");
    if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    SecretTransition transition;
    try {
      transition =
          JsonCodec.gson()
              .fromJson(
                  new String(PrivateFiles.read(pending, 4096), StandardCharsets.UTF_8),
                  SecretTransition.class);
      if (transition == null
          || transition.profileId() == null
          || transition.previousReference() == null
          || transition.nextReference() == null) {
        throw new JsonParseException("Invalid transition");
      }
    } catch (JsonParseException | IllegalArgumentException e) {
      throw new IOException("Повреждён журнал изменения секретов", e);
    }
    String applied = find(transition.profileId()).map(Profile::secretRef).orElse("");
    String unused;
    if (applied.equals(transition.nextReference())) {
      unused = transition.previousReference();
    } else if (applied.equals(transition.previousReference())) {
      unused = transition.nextReference();
    } else {
      throw new IOException("Не удалось подтвердить результат сохранения; секреты сохранены");
    }
    if (!unused.isEmpty() && !unused.equals(applied)) {
      secrets.delete(unused);
    }
    Files.delete(pending);
  }

  private static void requireRevision(Optional<Profile> previous, long expected)
      throws IOException {
    if (expected < 0 || previous.map(Profile::revision).orElse(0L) != expected) {
      throw new IOException("Профиль изменён. Заново откройте его перед сохранением");
    }
  }

  private FileChannel lockChannel() throws IOException {
    Path lock = directory.resolve("profiles.lock");
    if (Files.isSymbolicLink(lock)) {
      throw new IOException("Недопустимый файл блокировки");
    }
    FileChannel channel =
        FileChannel.open(
            lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    try {
      PrivateFiles.restrict(lock);
      return channel;
    } catch (IOException failure) {
      channel.close();
      throw failure;
    }
  }

  private Path path(UUID id) {
    return directory.resolve(id + ".json");
  }
}
