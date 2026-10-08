package app.wisprail.profile;

import java.util.Objects;

public record OpenVpnSettings(
    String transport,
    String username,
    boolean askPassword,
    String caCertificate,
    String clientCertificate,
    String cipher,
    String authDigest,
    String tlsServerName,
    int tlsKeyDirection)
    implements VpnSettings {

  public OpenVpnSettings {
    Objects.requireNonNull(transport);
    Objects.requireNonNull(username);
    Objects.requireNonNull(caCertificate);
    Objects.requireNonNull(clientCertificate);
    Objects.requireNonNull(cipher);
    Objects.requireNonNull(authDigest);
    Objects.requireNonNull(tlsServerName);
  }

  public static OpenVpnSettings defaults() {
    return new OpenVpnSettings("udp", "", true, "", "", "", "", "", -1);
  }

  @Override
  public VpnType type() {
    return VpnType.OPENVPN;
  }
}
