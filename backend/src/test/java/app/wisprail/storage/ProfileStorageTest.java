package app.wisprail.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.wisprail.profile.ProfileImport;
import app.wisprail.profile.ProfileRepository;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileStorageTest {
  @TempDir Path directory;

  @Test
  void exportingDoesNotChangeDestinationDirectoryPermissions() throws Exception {
    Path destination = Files.createDirectory(directory.resolve("exports"));
    AclFileAttributeView acl = Files.getFileAttributeView(destination, AclFileAttributeView.class);
    Object before = acl == null ? Files.getPosixFilePermissions(destination) : acl.getAcl();
    new ProfileImport().export(destination.resolve("profile.json"), profile());
    Object after = acl == null ? Files.getPosixFilePermissions(destination) : acl.getAcl();
    assertEquals(before, after);
  }

  @Test
  void revisionsSecretsAndAtomicReplacementPreserveSavedAndAppliedSnapshots() throws Exception {
    Map<String, ProfileSecrets> entries = new HashMap<>();
    SecretStore secrets =
        new SecretStore() {
          @Override
          public String save(ProfileSecrets value) {
            String id = UUID.randomUUID().toString();
            entries.put(id, value);
            return id;
          }

          @Override
          public ProfileSecrets read(String reference) {
            return entries.get(reference);
          }

          @Override
          public void delete(String reference) {
            entries.remove(reference);
          }
        };
    ProfileRepository repository = new ProfileRepository(directory, secrets);
    var profile = profile();
    var material = new ProfileSecrets("", "00000000-0000-4000-8000-000000000001", "", "", "", "");
    var saved = repository.save(profile, material).profile();
    var renamed =
        repository
            .save(
                saved.withIdentity(
                    saved.id(), saved.revision(), "Renamed", saved.secretReference()),
                material)
            .profile();
    assertEquals(1, saved.revision());
    assertEquals(2, renamed.revision());
    assertEquals("Original", saved.name());
    assertTrue(saved.sameNetworkSettings(renamed));
    assertEquals(1, entries.size());
    assertThrows(IOException.class, () -> repository.save(saved, material));
    var changed =
        repository
            .save(
                renamed,
                new ProfileSecrets("", "00000000-0000-4000-8000-000000000002", "", "", "", ""))
            .profile();
    assertNotEquals(saved.secretReference(), changed.secretReference());
    assertEquals(1, entries.size());
    String json = Files.readString(directory.resolve(profile.id() + ".json"));
    assertFalse(json.contains(material.uuid()));
    assertFalse(json.contains("00000000-0000-4000-8000-000000000002"));
    repository.delete(profile.id());
    assertTrue(repository.list().isEmpty());
    assertTrue(entries.isEmpty());
  }

  @Test
  void dpapiEncryptsActualFileAndRoundTripsOnlyThroughWindowsStore() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    var secrets = new ProfileSecrets("TEST-password-7b832", "", "TEST-ca", "", "TEST-key", "");
    var store = new DpapiSecretStore(directory);
    String reference = store.save(secrets);
    assertEquals(secrets, store.read(reference));
    byte[] bytes = Files.readAllBytes(directory.resolve(reference + ".dpapi"));
    assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(secrets.password()));
    store.delete(reference);
    assertFalse(Files.exists(directory.resolve(reference + ".dpapi")));
  }

  private static VpnProfile profile() {
    return new VpnProfile(
        1,
        UUID.randomUUID(),
        0,
        "Original",
        "vpn.example.com",
        443,
        VpnProfile.Protocol.VLESS,
        null,
        new VpnProfile.Vless(VpnProfile.Security.TLS, "vpn.example.com", "", "", "chrome", ""),
        List.of("10.20.0.0/16"),
        new VpnProfile.Dns("10.20.0.53", List.of("corp.example")),
        "",
        "");
  }

  @Test
  void retirementFailureReportsCommittedProfileWithoutLosingNewSecret() throws Exception {
    Map<String, ProfileSecrets> entries = new HashMap<>();
    SecretStore secrets =
        new SecretStore() {
          @Override
          public String save(ProfileSecrets value) {
            String reference = UUID.randomUUID().toString();
            entries.put(reference, value);
            return reference;
          }

          @Override
          public ProfileSecrets read(String reference) {
            return entries.get(reference);
          }

          @Override
          public void delete(String reference) throws IOException {
            throw new IOException("Vault unavailable");
          }
        };
    ProfileRepository repository = new ProfileRepository(directory, secrets);
    var first =
        repository
            .save(
                profile(),
                new ProfileSecrets("", "00000000-0000-4000-8000-000000000001", "", "", "", ""))
            .profile();
    var material = new ProfileSecrets("", "00000000-0000-4000-8000-000000000002", "", "", "", "");
    var second = repository.save(first, material);
    assertFalse(second.cleanupWarning().isEmpty());
    assertEquals(second.profile(), repository.list().getFirst());
    assertEquals(material, repository.secrets(second.profile()));
    assertEquals(2, second.profile().revision());
  }
}
