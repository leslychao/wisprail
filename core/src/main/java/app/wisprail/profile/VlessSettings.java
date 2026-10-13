package app.wisprail.profile;

import java.util.List;
import java.util.Objects;

public record VlessSettings(
    TlsMode security,
    String serverName,
    String publicKey,
    String shortId,
    String fingerprint,
    String flow,
    String transport,
    String serviceName)
    implements VpnSettings {
  public static final List<String> SUPPORTED_TRANSPORTS = List.of("tcp", "grpc");

  public VlessSettings {
    Objects.requireNonNull(security);
    Objects.requireNonNull(serverName);
    Objects.requireNonNull(publicKey);
    Objects.requireNonNull(shortId);
    Objects.requireNonNull(fingerprint);
    Objects.requireNonNull(flow);
    Objects.requireNonNull(transport);
    Objects.requireNonNull(serviceName);
  }

  public static VlessSettings defaults() {
    return new VlessSettings(TlsMode.TLS, "", "", "", "chrome", "", "tcp", "");
  }

  @Override
  public VpnType type() {
    return VpnType.VLESS;
  }
}
