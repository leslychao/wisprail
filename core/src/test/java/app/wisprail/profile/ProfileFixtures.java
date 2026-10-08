package app.wisprail.profile;

import java.util.List;
import java.util.UUID;

public final class ProfileFixtures {
  private ProfileFixtures() {}

  public static Profile vless(String name) {
    return new Profile(
        UUID.randomUUID(),
        0,
        name,
        "vpn.example.test",
        443,
        VlessSettings.defaults(),
        List.of("10.20.0.0/16"),
        "10.20.0.53",
        List.of("corp.example"),
        "",
        "");
  }

  public static ProfileSecrets secrets() {
    return new ProfileSecrets("", "", "", "", "", "51fc8016-0547-455f-9fb5-b224034fa67c");
  }
}
