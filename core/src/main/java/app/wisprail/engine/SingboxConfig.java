package app.wisprail.engine;

import app.wisprail.platform.NetworkPlan;
import app.wisprail.profile.OpenVpnSettings;
import app.wisprail.profile.Profile;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.TlsMode;
import app.wisprail.profile.VlessSettings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** The only translation from validated profiles to the pinned engine schema. */
public final class SingboxConfig {
  public static final String VPN_TAG = "wisprail-vpn";
  public static final String TUN_TAG = "wisprail-tun";

  private SingboxConfig() {}

  static JsonObject create(
      Profile profile,
      ProfileSecrets secrets,
      NetworkPlan network,
      int apiPort,
      String apiSecret,
      String certificate,
      String key) {
    JsonObject config = new JsonObject();
    JsonObject log = new JsonObject();
    log.addProperty("level", "error");
    log.addProperty("disabled", true);
    config.add("log", log);

    JsonObject tun = new JsonObject();
    tun.addProperty("type", "tun");
    tun.addProperty("tag", TUN_TAG);
    tun.addProperty("interface_name", network.interfaceName());
    tun.add("address", strings(List.of(network.tunCidr())));
    tun.addProperty("mtu", 1500);
    tun.addProperty("stack", "mixed");
    tun.addProperty("auto_route", true);
    tun.addProperty("strict_route", false);
    tun.addProperty("dns_mode", "disabled");
    tun.add("route_address", strings(profile.networks()));
    tun.add("route_exclude_address", strings(network.routeExclusions()));
    config.add("inbounds", array(tun));

    JsonObject vpn;
    if (profile.settings() instanceof OpenVpnSettings openVpn) {
      vpn = openVpn(profile, openVpn, secrets);
      config.add("endpoints", array(vpn));
    } else if (profile.settings() instanceof VlessSettings vless) {
      vpn = vless(profile, vless, secrets);
      config.add("outbounds", array(vpn));
    } else {
      throw new IllegalArgumentException("Неизвестный протокол VPN");
    }
    // Resolve once before TUN starts, preserving TLS identity separately.
    vpn.addProperty("server", network.serverAddresses().getFirst());

    JsonObject dns = new JsonObject();
    JsonObject corporate = new JsonObject();
    corporate.addProperty("type", "udp");
    corporate.addProperty("tag", "corporate");
    corporate.addProperty("server", profile.dns());
    corporate.addProperty("server_port", 53);
    corporate.addProperty("detour", VPN_TAG);
    dns.add("servers", array(corporate));
    dns.addProperty("final", "corporate");
    dns.addProperty("disable_cache", true);
    JsonArray dnsRules = new JsonArray();
    if (!profile.domains().isEmpty()) {
      JsonObject domains = new JsonObject();
      domains.add("domain_suffix", strings(profile.domains()));
      domains.addProperty("action", "route");
      domains.addProperty("server", "corporate");
      dnsRules.add(domains);
    }
    JsonObject rejectDns = new JsonObject();
    rejectDns.addProperty("action", "reject");
    dnsRules.add(rejectDns);
    dns.add("rules", dnsRules);
    if (!profile.dns().isBlank()) {
      config.add("dns", dns);
    }

    JsonObject route = new JsonObject();
    JsonArray rules = new JsonArray();
    JsonObject dnsInbound = new JsonObject();
    dnsInbound.add("inbound", strings(List.of(TUN_TAG)));
    dnsInbound.add("ip_cidr", strings(List.of(network.dnsAddress() + "/32")));
    dnsInbound.addProperty("port", 53);
    dnsInbound.addProperty("action", "hijack-dns");
    if (!profile.dns().isBlank()) {
      rules.add(dnsInbound);
    }
    JsonObject selected = new JsonObject();
    selected.add("inbound", strings(List.of(TUN_TAG)));
    selected.add("ip_cidr", strings(profile.networks()));
    selected.addProperty("action", "route");
    selected.addProperty("outbound", VPN_TAG);
    rules.add(selected);
    JsonObject reject = new JsonObject();
    reject.addProperty("action", "reject");
    rules.add(reject);
    route.add("rules", rules);
    config.add("route", route);

    JsonObject api = new JsonObject();
    api.addProperty("type", "api");
    api.addProperty("listen", "127.0.0.1");
    api.addProperty("listen_port", apiPort);
    api.addProperty("secret", apiSecret);
    api.addProperty("dashboard", false);
    JsonObject tls = new JsonObject();
    tls.addProperty("enabled", true);
    tls.add("certificate", strings(List.of(certificate)));
    tls.add("key", strings(List.of(key)));
    api.add("tls", tls);
    config.add("services", array(api));
    return config;
  }

  private static JsonObject openVpn(
      Profile profile, OpenVpnSettings settings, ProfileSecrets secrets) {
    JsonObject vpn = base(profile, "openvpn-client");
    vpn.addProperty("system", false);
    vpn.addProperty("network", settings.transport());
    vpn.addProperty("route_no_pull", true);
    vpn.addProperty("auth_retry", "none");
    optional(vpn, "username", settings.username());
    optional(vpn, "password", secrets.password());
    optional(vpn, "cipher", settings.cipher());
    optional(vpn, "auth", settings.authDigest());
    JsonObject tls = new JsonObject();
    tls.add("certificate", strings(List.of(settings.caCertificate())));
    if (!settings.clientCertificate().isBlank()) {
      tls.add("client_certificate", strings(List.of(settings.clientCertificate())));
      tls.add("client_key", strings(List.of(secrets.privateKey())));
    }
    optional(tls, "server_name", settings.tlsServerName());
    tls.addProperty("remote_certificate_tls", "server");
    tls.addProperty("version_min", "1.2");
    if (!secrets.tlsCryptKey().isBlank() || !secrets.tlsAuthKey().isBlank()) {
      JsonObject wrap = new JsonObject();
      boolean crypt = !secrets.tlsCryptKey().isBlank();
      wrap.addProperty("type", crypt ? "tls_crypt" : "tls_auth");
      wrap.add("key", strings(List.of(crypt ? secrets.tlsCryptKey() : secrets.tlsAuthKey())));
      if (!crypt && settings.tlsKeyDirection() >= 0) {
        wrap.addProperty("direction", settings.tlsKeyDirection() == 1 ? "client" : "server");
      }
      tls.add("control_wrap", wrap);
    }
    vpn.add("tls", tls);
    return vpn;
  }

  private static JsonObject vless(Profile profile, VlessSettings settings, ProfileSecrets secrets) {
    JsonObject vpn = base(profile, "vless");
    vpn.addProperty("uuid", secrets.uuid());
    vpn.addProperty("packet_encoding", "xudp");
    optional(vpn, "flow", settings.flow());
    JsonObject tls = new JsonObject();
    tls.addProperty("enabled", true);
    tls.addProperty(
        "server_name", settings.serverName().isBlank() ? profile.server() : settings.serverName());
    JsonObject utls = new JsonObject();
    utls.addProperty("enabled", true);
    utls.addProperty("fingerprint", settings.fingerprint());
    tls.add("utls", utls);
    if (settings.security() == TlsMode.REALITY) {
      JsonObject reality = new JsonObject();
      reality.addProperty("enabled", true);
      reality.addProperty("public_key", settings.publicKey());
      reality.addProperty("short_id", settings.shortId());
      tls.add("reality", reality);
    }
    vpn.add("tls", tls);
    return vpn;
  }

  private static JsonObject base(Profile profile, String type) {
    JsonObject vpn = new JsonObject();
    vpn.addProperty("type", type);
    vpn.addProperty("tag", VPN_TAG);
    vpn.addProperty("server_port", profile.port());
    return vpn;
  }

  private static void optional(JsonObject target, String name, String value) {
    if (value != null && !value.isBlank()) {
      target.addProperty(name, value);
    }
  }

  private static JsonArray strings(List<String> values) {
    JsonArray result = new JsonArray();
    values.forEach(result::add);
    return result;
  }

  private static JsonArray array(JsonObject value) {
    JsonArray array = new JsonArray();
    array.add(value);
    return array;
  }
}
