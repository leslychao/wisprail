package app.wisprail.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.wisprail.storage.SecretStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileRepositoryTest {
  @TempDir Path directory;

  @Test
  void revisionsPreventLostUpdatesAndRenamingReusesUnchangedSecrets() throws IOException {
    MemorySecrets secrets = new MemorySecrets();
    ProfileRepository repository = new ProfileRepository(directory.resolve("profiles"), secrets);
    Profile saved = repository.save(ProfileFixtures.vless("Первый"), 0, ProfileFixtures.secrets());
    Profile rename =
        saved.withIdentity(saved.id(), saved.revision(), "Новое имя", saved.secretRef());
    Profile updated = repository.save(rename, saved.revision(), ProfileFixtures.secrets());
    assertEquals(2, updated.revision());
    assertEquals(saved.secretRef(), updated.secretRef());
    assertEquals(1, secrets.values.size());
    assertThrows(IOException.class, () -> repository.save(rename, 1, ProfileFixtures.secrets()));
    assertEquals(updated, repository.find(saved.id()).orElseThrow());
    assertFalse(
        Files.readString(directory.resolve("profiles").resolve(saved.id() + ".json"))
            .contains(ProfileFixtures.secrets().uuid()));
  }

  @Test
  void failedSecretWriteDoesNotReplaceSavedRevisionAndNextWriteRecovers() throws IOException {
    MemorySecrets secrets = new MemorySecrets();
    Path profiles = directory.resolve("profiles");
    ProfileRepository repository = new ProfileRepository(profiles, secrets);
    Profile saved = repository.save(ProfileFixtures.vless("Первый"), 0, ProfileFixtures.secrets());
    ProfileSecrets replacement =
        new ProfileSecrets("", "", "", "", "", "6a53751f-38c2-476a-a34f-d246753707d5");
    secrets.failWrite = true;
    assertThrows(IOException.class, () -> repository.save(saved, 1, replacement));
    assertEquals(1, repository.find(saved.id()).orElseThrow().revision());
    assertEquals(ProfileFixtures.secrets(), repository.loadSecrets(saved));
    secrets.failWrite = false;
    Profile updated = repository.save(saved, 1, replacement);
    assertEquals(replacement, repository.loadSecrets(updated));
    assertEquals(1, secrets.values.size());
    assertFalse(Files.exists(profiles.resolve("secret-transition.pending")));
  }

  @Test
  void restartFinishesRetiredSecretCleanupWithoutRollingBackCommittedProfile() throws IOException {
    MemorySecrets secrets = new MemorySecrets();
    Path profiles = directory.resolve("profiles");
    ProfileRepository repository = new ProfileRepository(profiles, secrets);
    Profile saved = repository.save(ProfileFixtures.vless("Первый"), 0, ProfileFixtures.secrets());
    ProfileSecrets replacement =
        new ProfileSecrets("", "", "", "", "", "6a53751f-38c2-476a-a34f-d246753707d5");
    secrets.failDelete = true;
    assertThrows(IOException.class, () -> repository.save(saved, 1, replacement));
    Profile committed = repository.find(saved.id()).orElseThrow();
    assertEquals(2, committed.revision());
    assertEquals(replacement, repository.loadSecrets(committed));
    secrets.failDelete = false;
    ProfileRepository reopened = new ProfileRepository(profiles, secrets);
    reopened.save(committed, 2, replacement);
    assertEquals(1, secrets.values.size());
    assertFalse(secrets.values.containsKey(saved.secretRef()));
  }

  @Test
  void exportIsReimportableAsNewDraftWithoutSecretReference() throws IOException {
    ProfileRepository repository =
        new ProfileRepository(directory.resolve("profiles"), new MemorySecrets());
    Profile saved = repository.save(ProfileFixtures.vless("Первый"), 0, ProfileFixtures.secrets());
    Path export = directory.resolve("export.json");
    repository.exportProfile(saved, export);
    assertFalse(Files.readString(export).contains(saved.secretRef()));
    ImportResult imported = new ProfileImporter().importFile(export);
    assertEquals(0, imported.profile().revision());
    assertFalse(imported.profile().id().equals(saved.id()));
    assertTrue(imported.secrets().isEmpty());
    assertEquals(saved.networks(), imported.profile().networks());
  }

  private static final class MemorySecrets implements SecretStore {
    private final Map<String, byte[]> values = new HashMap<>();
    private boolean failWrite;
    private boolean failDelete;

    @Override
    public void write(String reference, byte[] value) throws IOException {
      if (failWrite) {
        throw new IOException("Secret store unavailable");
      }
      values.put(reference, value.clone());
    }

    @Override
    public Optional<byte[]> read(String reference) {
      return Optional.ofNullable(values.get(reference)).map(byte[]::clone);
    }

    @Override
    public void delete(String reference) throws IOException {
      if (failDelete) {
        throw new IOException("Secret store unavailable");
      }
      values.remove(reference);
    }
  }
}
