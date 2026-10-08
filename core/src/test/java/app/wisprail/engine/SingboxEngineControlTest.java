package app.wisprail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import app.wisprail.platform.NetworkPlan;
import app.wisprail.platform.NetworkPlatform;
import app.wisprail.profile.Profile;
import app.wisprail.profile.VlessSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingboxEngineControlTest {
  @TempDir Path temporary;

  @Test
  void disconnectedAnalysisMatchesSettingsWithoutContactingCorporateDns() throws Exception {
    Profile profile =
        new Profile(
            UUID.randomUUID(),
            1,
            "Test",
            "192.0.2.10",
            443,
            VlessSettings.defaults(),
            List.of("10.20.0.0/16"),
            "192.0.2.53",
            List.of("corp.example"),
            "",
            "");
    try (var engine =
        new SingboxEngineControl(
            temporary.resolve("missing-engine"), temporary, new NoNetworkOperations())) {
      var domain = engine.analyze(profile, "host.corp.example");
      assertEquals("corp.example", domain.matchingDomain());
      assertEquals(List.of(), domain.addresses());
      assertFalse(domain.actualRouteVerified());
      var literal = engine.analyze(profile, "10.20.1.2");
      assertEquals(List.of("10.20.0.0/16"), literal.matchingNetworks());
      assertFalse(literal.actualRouteVerified());
    }
  }

  private static final class NoNetworkOperations implements NetworkPlatform {
    @Override
    public NetworkPlan prepare(Profile profile, UUID operationId) {
      throw new AssertionError("Disconnected analysis must not prepare network effects");
    }

    @Override
    public void checkConflicts(NetworkPlan plan) {
      throw new AssertionError("Unexpected system access");
    }

    @Override
    public void reserve(NetworkPlan plan) {
      throw new AssertionError("Unexpected network reservation");
    }

    @Override
    public void applyDns(NetworkPlan plan, Profile profile) {
      throw new AssertionError("Unexpected DNS mutation");
    }

    @Override
    public void verify(NetworkPlan plan, Profile profile) {
      throw new AssertionError("Unexpected applied-profile verification");
    }

    @Override
    public void cleanup(UUID operationId) {
      throw new AssertionError("Unexpected network cleanup");
    }

    @Override
    public void recover() {
      throw new AssertionError("Unexpected network recovery");
    }
  }
}
