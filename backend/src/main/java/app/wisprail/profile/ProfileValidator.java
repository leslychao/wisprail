package app.wisprail.profile;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ProfileValidator {
  public record Issue(String field, String message) {}

  public List<Issue> validate(VpnProfile profile) {
    List<Issue> issues = new ArrayList<>();
    if (profile.schemaVersion() != 1 || profile.revision() < 0) {
      issues.add(new Issue("main", "Неподдерживаемая версия профиля"));
    }
    if (profile.name().isBlank() || profile.name().length() > 80) {
      issues.add(new Issue("name", "Название должно содержать от 1 до 80 символов"));
    }
    check(issues, "server", () -> validateHost(profile.server()));
    if (profile.port() < 1 || profile.port() > 65535) {
      issues.add(new Issue("port", "Порт должен быть от 1 до 65535"));
    }
    List<Ipv4Network> networks = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    for (String value : profile.networks()) {
      check(
          issues,
          "networks",
          () -> {
            Ipv4Network network = Ipv4Network.parse(value);
            if (!network.toString().equals(value)) {
              throw new IllegalArgumentException("Подтвердите каноническую сеть: " + network);
            }
            if (!unique.add(value)) {
              throw new IllegalArgumentException("Сеть добавлена повторно");
            }
            networks.add(network);
          });
    }
    if (networks.isEmpty() || networks.size() > 256 || Ipv4Network.coversInternet(networks)) {
      issues.add(new Issue("networks", "Нужно от 1 до 256 сетей без полного охвата IPv4"));
    }
    if (profile.dns().server().isEmpty() != profile.dns().domains().isEmpty()) {
      issues.add(
          new Issue("dns", "Укажите DNS-сервер вместе с доменами или" + " очистите оба поля"));
    }
    if (!profile.dns().server().isEmpty()) {
      check(
          issues,
          "dns",
          () -> {
            long address = Ipv4Network.parseAddress(profile.dns().server());
            if (networks.stream().noneMatch(network -> network.contains(address))) {
              throw new IllegalArgumentException("Добавьте адрес DNS в сети VPN явно");
            }
          });
    }
    Set<String> domains = new HashSet<>();
    if (profile.dns().domains().size() > 128) {
      issues.add(new Issue("dns", "Допускается не более 128 доменов"));
    }
    for (String domain : profile.dns().domains()) {
      check(
          issues,
          "dns",
          () -> {
            if (!domains.add(DomainName.normalize(domain))) {
              throw new IllegalArgumentException("Домен добавлен повторно");
            }
          });
    }
    validateProtocol(profile, issues);
    if (!profile.probeUrl().isBlank()) {
      check(
          issues,
          "probe",
          () -> {
            URI uri = URI.create(profile.probeUrl());
            if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || uri.getFragment() != null) {
              throw new IllegalArgumentException(
                  "Для проверки нужен HTTPS-адрес без данных" + " доступа");
            }
            validateHost(uri.getHost());
          });
    } else if (profile.protocol() == VpnProfile.Protocol.VLESS
        && profile.dns().server().isEmpty()) {
      issues.add(
          new Issue("probe", "Для VLESS задайте корпоративный DNS или HTTPS-адрес" + " проверки"));
    }
    return List.copyOf(issues);
  }

  public List<Issue> validate(VpnProfile profile, ProfileSecrets secrets) {
    List<Issue> issues = new ArrayList<>(validate(profile));
    if (profile.protocol() == VpnProfile.Protocol.VLESS) {
      check(
          issues,
          "uuid",
          () -> {
            if (!secrets
                .uuid()
                .matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
              throw new IllegalArgumentException("Некорректный UUID VLESS");
            }
          });
    } else {
      if (secrets.ca().isBlank()) {
        issues.add(new Issue("access", "Выберите сертификат центра OpenVPN"));
      }
      if (secrets.certificate().isBlank() != secrets.privateKey().isBlank()) {
        issues.add(new Issue("access", "Сертификат пользователя и ключ нужны" + " вместе"));
      }
      if (profile.openVpn() != null
          && !profile.openVpn().controlWrap().isEmpty()
          && secrets.controlKey().isBlank()) {
        issues.add(new Issue("access", "Отсутствует ключ защиты канала OpenVPN"));
      }
    }
    return List.copyOf(issues);
  }

  public void requireValid(VpnProfile profile, ProfileSecrets secrets) {
    List<Issue> issues = validate(profile, secrets);
    if (!issues.isEmpty()) {
      throw new IllegalArgumentException(issues.getFirst().message());
    }
  }

  public static void validateHost(String host) {
    if (host.matches("[0-9.]+")) {
      Ipv4Network.parseAddress(host);
    } else {
      DomainName.normalize(host);
    }
  }

  private static void validateProtocol(VpnProfile profile, List<Issue> issues) {
    if (profile.protocol() == VpnProfile.Protocol.OPENVPN) {
      var settings = profile.openVpn();
      if (settings == null || profile.vless() != null) {
        issues.add(new Issue("access", "Некорректные настройки OpenVPN"));
        return;
      }
      if (!Set.of("udp", "tcp").contains(settings.transport())
          || !Set.of("", "tls-auth", "tls-crypt", "tls-crypt-v2").contains(settings.controlWrap())
          || !Set.of("", "0", "1").contains(settings.keyDirection())) {
        issues.add(new Issue("access", "Неподдерживаемые параметры OpenVPN"));
      }
    } else {
      var settings = profile.vless();
      if (settings == null || profile.openVpn() != null) {
        issues.add(new Issue("access", "Некорректные настройки VLESS"));
        return;
      }
      check(issues, "sni", () -> DomainName.normalize(settings.serverName()));
      if (!Set.of("", "xtls-rprx-vision").contains(settings.flow())
          || !Set.of("chrome", "firefox", "safari", "edge", "randomized")
              .contains(settings.fingerprint())) {
        issues.add(new Issue("access", "Неподдерживаемые flow или fingerprint VLESS"));
      }
      if (settings.security() == VpnProfile.Security.REALITY) {
        check(
            issues,
            "access",
            () -> {
              if (Base64.getUrlDecoder().decode(settings.publicKey()).length != 32
                  || !settings.shortId().matches("(?:[0-9a-fA-F]{2}){0,8}")) {
                throw new IllegalArgumentException("Проверьте public key и short ID Reality");
              }
            });
      }
    }
  }

  private static void check(List<Issue> issues, String field, Runnable validation) {
    try {
      validation.run();
    } catch (IllegalArgumentException exception) {
      issues.add(new Issue(field, exception.getMessage()));
    }
  }
}
