package app.wisprail.profile;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A saved revision or an unsaved draft; validation is owned by ProfileValidator. */
public record Profile(
    UUID id,
    long revision,
    String name,
    String server,
    int port,
    VpnSettings settings,
    List<String> networks,
    String dns,
    List<String> domains,
    String healthUrl,
    String secretRef) {

  public Profile {
    Objects.requireNonNull(id);
    Objects.requireNonNull(settings);
    Objects.requireNonNull(name);
    Objects.requireNonNull(server);
    Objects.requireNonNull(dns);
    Objects.requireNonNull(healthUrl);
    Objects.requireNonNull(secretRef);
    networks = List.copyOf(networks);
    domains = List.copyOf(domains);
  }

  public static Profile draft(VpnType type) {
    VpnSettings settings =
        type == VpnType.OPENVPN ? OpenVpnSettings.defaults() : VlessSettings.defaults();
    return new Profile(
        UUID.randomUUID(),
        0,
        "",
        "",
        type == VpnType.OPENVPN ? 1194 : 443,
        settings,
        List.of(),
        "",
        List.of(),
        "",
        "");
  }

  public VpnType type() {
    return settings.type();
  }

  public Profile withIdentity(UUID newId, long newRevision, String newName, String reference) {
    return new Profile(
        newId,
        newRevision,
        newName,
        server,
        port,
        settings,
        networks,
        dns,
        domains,
        healthUrl,
        reference);
  }

  public boolean sameNetworkSettings(Profile other) {
    return other != null
        && server.equals(other.server)
        && port == other.port
        && settings.equals(other.settings)
        && networks.equals(other.networks)
        && dns.equals(other.dns)
        && domains.equals(other.domains)
        && healthUrl.equals(other.healthUrl)
        && secretRef.equals(other.secretRef);
  }
}
