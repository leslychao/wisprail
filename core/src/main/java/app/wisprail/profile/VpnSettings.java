package app.wisprail.profile;

public sealed interface VpnSettings permits OpenVpnSettings, VlessSettings {
  VpnType type();
}
