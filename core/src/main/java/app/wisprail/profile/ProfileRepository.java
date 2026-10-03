package app.wisprail.profile;

import app.wisprail.storage.JsonFiles;
import app.wisprail.storage.SecretStore;
import app.wisprail.storage.SecureFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Serializes profile writes. An active engine already owns its protected copy of secret material.
 */
public final class ProfileRepository {
  /** A committed profile remains saved even when retiring its former secret fails. */
  public record SaveResult(VpnProfile profile, String cleanupWarning) {}

  private final Path directory;
  private final SecretStore secrets;
  private final ProfileValidator validator = new ProfileValidator();

  public ProfileRepository(Path directory, SecretStore secrets) throws IOException {
    this.directory = directory;
    this.secrets = secrets;
    SecureFiles.directory(directory);
  }

  public synchronized List<VpnProfile> list() throws IOException {
    List<VpnProfile> profiles = new ArrayList<>();
    try (var files = Files.list(directory)) {
      for (Path file :
          files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
        profiles.add(JsonFiles.read(file, VpnProfile.class));
      }
    }
    profiles.sort(Comparator.comparing(VpnProfile::name, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(profiles);
  }

  public synchronized SaveResult save(VpnProfile draft, ProfileSecrets secretValues)
      throws IOException {
    validator.requireValid(draft, secretValues);
    Path file = path(draft.id());
    VpnProfile existing = Files.exists(file) ? JsonFiles.read(file, VpnProfile.class) : null;
    if (existing != null
        && (existing.revision() != draft.revision() || existing.protocol() != draft.protocol())) {
      throw new IOException("Профиль уже изменён или изменён его тип; откройте его заново");
    }
    String reference = existing == null ? "" : existing.secretReference();
    boolean changed = reference.isEmpty() || !secrets.read(reference).equals(secretValues);
    if (changed) {
      reference = secrets.save(secretValues);
    }
    VpnProfile saved =
        draft.withIdentity(draft.id(), draft.revision() + 1, draft.name().strip(), reference);
    try {
      JsonFiles.writeAtomic(file, saved);
    } catch (IOException exception) {
      if (changed) {
        try {
          secrets.delete(reference);
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
    if (changed && existing != null && !existing.secretReference().isEmpty()) {
      try {
        secrets.delete(existing.secretReference());
      } catch (IOException exception) {
        return new SaveResult(
            saved,
            "Профиль сохранён, но прежний секрет не удалён из защищённого хранилища. Проверьте"
                + " доступ к хранилищу.");
      }
    }
    return new SaveResult(saved, "");
  }

  public ProfileSecrets secrets(VpnProfile profile) throws IOException {
    return secrets.read(profile.secretReference());
  }

  public synchronized String delete(UUID id) throws IOException {
    VpnProfile profile = JsonFiles.read(path(id), VpnProfile.class);
    Files.delete(path(id));
    try {
      secrets.delete(profile.secretReference());
      return "";
    } catch (IOException exception) {
      return "Профиль удалён, но секрет остался в защищённом хранилище. Проверьте доступ к"
          + " хранилищу.";
    }
  }

  public static VpnProfile copyWithoutSecrets(VpnProfile profile) {
    VpnProfile copy = profile.withIdentity(UUID.randomUUID(), 0, profile.name() + " — копия", "");
    if (copy.openVpn() == null) {
      return copy;
    }
    var settings = copy.openVpn();
    return new VpnProfile(
        1,
        copy.id(),
        0,
        copy.name(),
        copy.server(),
        copy.port(),
        copy.protocol(),
        new VpnProfile.OpenVpn(
            settings.transport(),
            "",
            settings.serverName(),
            settings.cipher(),
            settings.auth(),
            settings.controlWrap(),
            settings.keyDirection()),
        null,
        copy.networks(),
        copy.dns(),
        copy.probeUrl(),
        "");
  }

  private Path path(UUID id) {
    return directory.resolve(id + ".json");
  }
}
