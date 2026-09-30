package app.wisprail.profile;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Saved settings contain only a secret reference; secret material travels separately. */
public record VpnProfile(
    int schemaVersion,
    UUID id,
    long revision,
    String name,
    String server,
    int port,
    Protocol protocol,
    OpenVpn openVpn,
    Vless vless,
    List<String> networks,
    Dns dns,
    String probeUrl,
    String secretReference) {
  public enum Protocol {
    OPENVPN,
    VLESS
  }

  public enum Security {
    TLS,
    REALITY
  }

  public record OpenVpn(
      String transport,
      String username,
      String serverName,
      String cipher,
      String auth,
      String controlWrap,
      String keyDirection) {
    public OpenVpn {
      Objects.requireNonNull(transport);
      Objects.requireNonNull(username);
      Objects.requireNonNull(serverName);
      Objects.requireNonNull(cipher);
      Objects.requireNonNull(auth);
      Objects.requireNonNull(controlWrap);
      Objects.requireNonNull(keyDirection);
    }

    public static OpenVpn defaults() {
      return new OpenVpn("udp", "", "", "", "SHA256", "", "");
    }
  }

  public record Vless(
      Security security,
      String serverName,
      String publicKey,
      String shortId,
      String fingerprint,
      String flow) {
    public Vless {
      Objects.requireNonNull(security);
      Objects.requireNonNull(serverName);
      Objects.requireNonNull(publicKey);
      Objects.requireNonNull(shortId);
      Objects.requireNonNull(fingerprint);
      Objects.requireNonNull(flow);
    }

    public static Vless defaults() {
      return new Vless(Security.TLS, "", "", "", "chrome", "");
    }
  }

  public record Dns(String server, List<String> domains) {
    public Dns {
      Objects.requireNonNull(server);
      domains = List.copyOf(domains);
    }

    public static Dns empty() {
      return new Dns("", List.of());
    }
  }

  public VpnProfile {
    Objects.requireNonNull(id);
    Objects.requireNonNull(name);
    Objects.requireNonNull(server);
    Objects.requireNonNull(protocol);
    networks = List.copyOf(networks);
    Objects.requireNonNull(dns);
    Objects.requireNonNull(probeUrl);
    Objects.requireNonNull(secretReference);
  }

  public VpnProfile withIdentity(UUID newId, long newRevision, String newName, String reference) {
    return new VpnProfile(
        schemaVersion,
        newId,
        newRevision,
        newName,
        server,
        port,
        protocol,
        openVpn,
        vless,
        networks,
        dns,
        probeUrl,
        reference);
  }

  public boolean sameNetworkSettings(VpnProfile other) {
    return server.equals(other.server)
        && port == other.port
        && protocol == other.protocol
        && Objects.equals(openVpn, other.openVpn)
        && Objects.equals(vless, other.vless)
        && networks.equals(other.networks)
        && dns.equals(other.dns)
        && probeUrl.equals(other.probeUrl)
        && secretReference.equals(other.secretReference);
  }
}
