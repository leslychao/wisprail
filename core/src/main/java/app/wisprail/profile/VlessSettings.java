package app.wisprail.profile;

import java.util.Objects;

public record VlessSettings(
    TlsMode security,
    String serverName,
    String publicKey,
    String shortId,
    String fingerprint,
    String flow)
    implements VpnSettings {

  public VlessSettings {
    Objects.requireNonNull(security);
    Objects.requireNonNull(serverName);
    Objects.requireNonNull(publicKey);
    Objects.requireNonNull(shortId);
    Objects.requireNonNull(fingerprint);
    Objects.requireNonNull(flow);
  }

  public static VlessSettings defaults() {
    return new VlessSettings(TlsMode.TLS, "", "", "", "chrome", "");
  }

  @Override
  public VpnType type() {
    return VpnType.VLESS;
  }
}
