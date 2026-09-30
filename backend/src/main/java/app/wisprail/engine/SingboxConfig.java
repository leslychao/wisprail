package app.wisprail.engine;

import app.wisprail.profile.DomainName;
import app.wisprail.profile.ProfileSecrets;
import app.wisprail.profile.VpnProfile;
import app.wisprail.storage.JsonFiles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Generates the allowlisted sing-box 1.14.2 schema; callers cannot inject raw JSON. */
public final class SingboxConfig {
  public static final String VERSION = "1.14.2";
  public static final String VPN_TAG = "profile-vpn";

  public record RuntimeNetwork(
      String interfaceName,
      String tunAddress,
      String dnsAddress,
      List<String> serverAddresses,
      int apiPort,
      String apiSecret) {
    public RuntimeNetwork {
      serverAddresses = List.copyOf(serverAddresses);
    }

    @Override
    public String toString() {
      return "RuntimeNetwork[redacted]";
    }
  }

  public ObjectNode compile(VpnProfile profile, ProfileSecrets secrets, RuntimeNetwork network) {
    ObjectNode root = JsonFiles.mapper().createObjectNode();
    root.putObject("log").put("level", "warn").put("timestamp", false);
    ObjectNode tun = root.putArray("inbounds").addObject();
    tun.put("type", "tun")
        .put("tag", "wisprail-tun")
        .put("interface_name", network.interfaceName());
    tun.putArray("address").add(network.tunAddress());
    tun.put("auto_route", true).put("strict_route", false).put("dns_mode", "disabled");
    tun.put("stack", "gvisor").put("mtu", 1500);
    profile.networks().forEach(tun.putArray("route_address")::add);
    var exclusions = tun.putArray("route_exclude_address");
    network.serverAddresses().forEach(address -> exclusions.add(address + "/32"));
    ObjectNode vpn =
        profile.protocol() == VpnProfile.Protocol.OPENVPN
            ? openVpn(profile, secrets, network.serverAddresses().getFirst())
            : vless(profile, secrets, network.serverAddresses().getFirst());
    root.putArray(profile.protocol() == VpnProfile.Protocol.OPENVPN ? "endpoints" : "outbounds")
        .add(vpn);
    ObjectNode route = root.putObject("route");
    route.put("auto_detect_interface", true);
    var rules = route.putArray("rules");
    ObjectNode dnsRule = rules.addObject();
    dnsRule.putArray("ip_cidr").add(network.dnsAddress() + "/32");
    dnsRule.put("port", 53).put("action", "hijack-dns");
    ObjectNode vpnRule = rules.addObject();
    profile.networks().forEach(vpnRule.putArray("ip_cidr")::add);
    vpnRule.put("action", "route").put("outbound", VPN_TAG);
    rules.addObject().put("action", "reject");
    ObjectNode dns = root.putObject("dns");
    var dnsRules = dns.putArray("rules");
    if (!profile.dns().server().isEmpty()) {
      ObjectNode upstream = dns.putArray("servers").addObject();
      upstream
          .put("type", "udp")
          .put("tag", "corporate-dns")
          .put("server", profile.dns().server())
          .put("detour", VPN_TAG);
      ObjectNode corporate = dnsRules.addObject();
      var suffixes = corporate.putArray("domain_suffix");
      profile.dns().domains().forEach(domain -> suffixes.add(DomainName.normalize(domain)));
      corporate.put("action", "route").put("server", "corporate-dns").put("disable_cache", true);
    }
    dnsRules.addObject().put("action", "reject").put("method", "default");
    root.putArray("services")
        .addObject()
        .put("type", "api")
        .put("listen", "127.0.0.1")
        .put("listen_port", network.apiPort())
        .put("secret", network.apiSecret())
        .put("dashboard", false);
    return root;
  }

  private static ObjectNode vless(
      VpnProfile profile, ProfileSecrets secrets, String serverAddress) {
    var settings = profile.vless();
    ObjectNode outbound = JsonFiles.mapper().createObjectNode();
    outbound
        .put("type", "vless")
        .put("tag", VPN_TAG)
        .put("server", serverAddress)
        .put("server_port", profile.port())
        .put("uuid", secrets.uuid());
    if (!settings.flow().isEmpty()) {
      outbound.put("flow", settings.flow());
    }
    ObjectNode tls = outbound.putObject("tls");
    tls.put("enabled", true).put("server_name", settings.serverName());
    if (settings.security() == VpnProfile.Security.REALITY) {
      tls.putObject("utls").put("enabled", true).put("fingerprint", settings.fingerprint());
      tls.putObject("reality")
          .put("enabled", true)
          .put("public_key", settings.publicKey())
          .put("short_id", settings.shortId());
    }
    return outbound;
  }

  private static ObjectNode openVpn(
      VpnProfile profile, ProfileSecrets secrets, String serverAddress) {
    var settings = profile.openVpn();
    ObjectNode endpoint = JsonFiles.mapper().createObjectNode();
    endpoint
        .put("type", "openvpn-client")
        .put("tag", VPN_TAG)
        .put("server", serverAddress)
        .put("server_port", profile.port())
        .put("network", settings.transport())
        .put("system", false)
        .put("route_no_pull", true)
        .put("redirect_gateway", false)
        .put("username", settings.username())
        .put("password", secrets.password())
        .put("auth", settings.auth());
    if (!settings.cipher().isEmpty()) {
      endpoint.putArray("data_ciphers").add(settings.cipher());
      endpoint.put("data_ciphers_fallback", settings.cipher());
    }
    ObjectNode tls = endpoint.putObject("tls");
    tls.put("certificate", secrets.ca())
        .put("remote_certificate_tls", "server")
        .put("version_min", "1.2");
    if (!settings.serverName().isEmpty()) {
      tls.put("server_name", settings.serverName()).put("server_name_type", "name");
    }
    if (!secrets.certificate().isEmpty()) {
      tls.put("client_certificate", secrets.certificate()).put("client_key", secrets.privateKey());
    }
    if (!settings.controlWrap().isEmpty()) {
      ObjectNode wrap = tls.putObject("control_wrap");
      wrap.put("type", settings.controlWrap().replace('-', '_')).put("key", secrets.controlKey());
      if (!settings.keyDirection().isEmpty()) {
        wrap.put("direction", settings.keyDirection().equals("0") ? "server" : "client");
      }
    }
    return endpoint;
  }
}
