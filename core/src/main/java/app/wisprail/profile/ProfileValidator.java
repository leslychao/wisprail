package app.wisprail.profile;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ProfileValidator {
  public static final int MAX_ENTRIES = 4096;
  public static final int MAX_TEXT_BYTES = 1024 * 1024;

  private ProfileValidator() {}

  public static List<ValidationIssue> validateProfile(Profile profile) {
    List<ValidationIssue> issues = new ArrayList<>();
    if (profile.name().isBlank() || profile.name().length() > 80) {
      issues.add(new ValidationIssue("name", "Название должно содержать от 1 до 80 символов"));
    }
    if (profile.revision() < 0) {
      issues.add(new ValidationIssue("revision", "Некорректная ревизия профиля"));
    }
    try {
      if (profile.server().matches("[0-9.]+")) {
        Ipv4Cidr.parseAddress(profile.server());
      } else {
        DomainNames.normalize(profile.server());
      }
    } catch (IllegalArgumentException e) {
      issues.add(new ValidationIssue("server", "Нужен домен или IPv4 сервера, без URL"));
    }
    if (profile.port() < 1 || profile.port() > 65535) {
      issues.add(new ValidationIssue("port", "Порт должен быть от 1 до 65535"));
    }
    List<Ipv4Cidr> networks = new ArrayList<>();
    if (profile.networks().isEmpty() || profile.networks().size() > MAX_ENTRIES) {
      issues.add(new ValidationIssue("networks", "Укажите от 1 до 4096 IPv4-сетей"));
    } else {
      Set<String> unique = new HashSet<>();
      for (String value : profile.networks()) {
        try {
          Ipv4Cidr network = Ipv4Cidr.parse(value);
          networks.add(network);
          if (!value.equals(network.canonical())) {
            issues.add(
                new ValidationIssue(
                    "networks", "Подтвердите каноническую сеть: " + network.canonical()));
          }
          if (!unique.add(network.canonical())) {
            issues.add(new ValidationIssue("networks", "Список содержит повторяющуюся сеть"));
          }
        } catch (IllegalArgumentException e) {
          issues.add(new ValidationIssue("networks", "Список содержит некорректную IPv4-сеть"));
        }
      }
      if (Ipv4Cidr.coversEntireIpv4(networks)) {
        issues.add(new ValidationIssue("networks", "Полный туннель IPv4 не поддерживается"));
      }
    }
    if (!profile.dns().isBlank()) {
      try {
        long dns = Ipv4Cidr.parseAddress(profile.dns());
        if (networks.stream().noneMatch(network -> network.contains(dns))) {
          issues.add(new ValidationIssue("dns", "DNS вне выбранных сетей: явно добавьте его /32"));
        }
      } catch (IllegalArgumentException e) {
        issues.add(new ValidationIssue("dns", "DNS должен быть IPv4-адресом"));
      }
    }
    if (!profile.domains().isEmpty() && profile.dns().isBlank()) {
      issues.add(new ValidationIssue("dns", "Для доменов нужен корпоративный DNS"));
    }
    if (profile.domains().size() > MAX_ENTRIES) {
      issues.add(new ValidationIssue("domains", "Слишком много DNS-доменов"));
    } else {
      Set<String> unique = new HashSet<>();
      for (String domain : profile.domains()) {
        try {
          if (!unique.add(DomainNames.normalize(domain))) {
            issues.add(new ValidationIssue("domains", "Повторяющийся DNS-домен"));
          }
        } catch (IllegalArgumentException e) {
          issues.add(new ValidationIssue("domains", "Некорректный DNS-домен"));
        }
      }
    }
    if (!profile.healthUrl().isBlank()) {
      try {
        URI uri = URI.create(profile.healthUrl());
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || uri.getFragment() != null
            || uri.getPort() == 0
            || uri.getPort() > 65535
            || uri.getPort() < -1
            || profile.healthUrl().length() > 2048) {
          throw new IllegalArgumentException("Invalid health URL");
        }
        if (uri.getHost().matches("[0-9.]+")) {
          long address = Ipv4Cidr.parseAddress(uri.getHost());
          if (networks.stream().noneMatch(network -> network.contains(address))) {
            issues.add(new ValidationIssue("healthUrl", "Ресурс проверки должен быть в сетях VPN"));
          }
        } else {
          DomainNames.normalize(uri.getHost());
        }
      } catch (IllegalArgumentException e) {
        issues.add(new ValidationIssue("healthUrl", "Нужен HTTPS-адрес без секрета и фрагмента"));
      }
    }
    switch (profile.settings()) {
      case OpenVpnSettings settings -> validateOpenVpn(settings, issues);
      case VlessSettings settings -> validateVless(profile, settings, issues);
    }
    return List.copyOf(issues);
  }

  public static List<ValidationIssue> validate(Profile profile, ProfileSecrets secrets) {
    List<ValidationIssue> issues = new ArrayList<>(validateProfile(profile));
    switch (profile.settings()) {
      case OpenVpnSettings settings -> {
        if (!settings.askPassword()
            && !settings.username().isBlank()
            && secrets.password().isBlank()) {
          issues.add(
              new ValidationIssue("password", "Введите пароль или включите запрос при входе"));
        }
        if (!settings.clientCertificate().isBlank() && secrets.privateKey().isBlank()) {
          issues.add(new ValidationIssue("privateKey", "Для сертификата нужен закрытый ключ"));
        }
        if (settings.username().isBlank() && settings.clientCertificate().isBlank()) {
          issues.add(new ValidationIssue("username", "Укажите имя пользователя или сертификат"));
        }
        if (!secrets.tlsAuthKey().isBlank() && !secrets.tlsCryptKey().isBlank()) {
          issues.add(new ValidationIssue("tlsAuthKey", "tls-auth и tls-crypt несовместимы"));
        }
        if (!secrets.privateKey().isBlank() && !secrets.privateKey().contains("PRIVATE KEY-----")) {
          issues.add(new ValidationIssue("privateKey", "Нужен закрытый ключ в формате PEM"));
        }
      }
      case VlessSettings ignored -> {
        try {
          UUID parsed = UUID.fromString(secrets.uuid());
          if (!parsed.toString().equalsIgnoreCase(secrets.uuid())) {
            throw new IllegalArgumentException("Noncanonical UUID");
          }
        } catch (IllegalArgumentException e) {
          issues.add(new ValidationIssue("uuid", "Введите корректный UUID VLESS"));
        }
      }
    }
    if (secrets.password().length() > 4096
        || secrets.uuid().length() > 64
        || secrets.privateKey().length() > MAX_TEXT_BYTES
        || secrets.tlsAuthKey().length() > MAX_TEXT_BYTES
        || secrets.tlsCryptKey().length() > MAX_TEXT_BYTES
        || secrets.privateKeyPassword().length() > 4096) {
      issues.add(new ValidationIssue("access", "Данные доступа превышают допустимый размер"));
    }
    return List.copyOf(issues);
  }

  public static void requireValid(Profile profile, ProfileSecrets secrets) {
    List<ValidationIssue> issues = validate(profile, secrets);
    if (!issues.isEmpty()) {
      throw new IllegalArgumentException(issues.getFirst().message());
    }
  }

  private static void validateOpenVpn(OpenVpnSettings settings, List<ValidationIssue> issues) {
    if (!Set.of("udp", "tcp").contains(settings.transport())) {
      issues.add(new ValidationIssue("transport", "OpenVPN поддерживает UDP или TCP"));
    }
    if (settings.username().length() > 256 || settings.username().contains("\n")) {
      issues.add(new ValidationIssue("username", "Некорректное имя пользователя"));
    }
    if (settings.caCertificate().isBlank()) {
      issues.add(
          new ValidationIssue("caCertificate", "Укажите сертификат центра для проверки TLS"));
    } else {
      validateCertificate("caCertificate", settings.caCertificate(), issues);
    }
    if (!settings.clientCertificate().isBlank()) {
      validateCertificate("clientCertificate", settings.clientCertificate(), issues);
    }
    if (!settings.cipher().isEmpty() && !settings.cipher().matches("[A-Za-z0-9:_-]{1,256}")) {
      issues.add(new ValidationIssue("cipher", "Некорректный шифр"));
    }
    if (!settings.authDigest().isEmpty() && !settings.authDigest().matches("[A-Za-z0-9_-]{1,64}")) {
      issues.add(new ValidationIssue("authDigest", "Некорректный алгоритм аутентификации"));
    }
    if (settings.tlsKeyDirection() < -1 || settings.tlsKeyDirection() > 1) {
      issues.add(new ValidationIssue("tlsKeyDirection", "Направление ключа: 0, 1 или не задано"));
    }
  }

  private static void validateCertificate(String field, String pem, List<ValidationIssue> issues) {
    if (pem.length() > MAX_TEXT_BYTES) {
      issues.add(new ValidationIssue(field, "Сертификат слишком большой"));
      return;
    }
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      if (factory
          .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))
          .isEmpty()) {
        throw new CertificateException("Empty certificate");
      }
    } catch (CertificateException e) {
      issues.add(new ValidationIssue(field, "Некорректный сертификат X.509"));
    }
  }

  private static void validateVless(
      Profile profile, VlessSettings settings, List<ValidationIssue> issues) {
    if (profile.dns().isBlank() && profile.healthUrl().isBlank()) {
      issues.add(
          new ValidationIssue("healthUrl", "Для проверки VLESS нужен DNS или HTTPS-ресурс VPN"));
    }
    if (settings.security() == null) {
      issues.add(new ValidationIssue("security", "Выберите TLS или Reality"));
    }
    if (!settings.serverName().isBlank()) {
      try {
        DomainNames.normalize(settings.serverName());
      } catch (IllegalArgumentException e) {
        issues.add(new ValidationIssue("serverName", "Некорректное имя TLS/SNI"));
      }
    }
    if (!Set.of("", "xtls-rprx-vision").contains(settings.flow())) {
      issues.add(new ValidationIssue("flow", "Неподдержанный VLESS flow"));
    }
    if (!Set.of(
            "",
            "chrome",
            "firefox",
            "edge",
            "safari",
            "ios",
            "android",
            "random",
            "randomized",
            "360",
            "qq")
        .contains(settings.fingerprint())) {
      issues.add(new ValidationIssue("fingerprint", "Неподдержанный TLS fingerprint"));
    }
    if (settings.security() == TlsMode.REALITY) {
      try {
        if (Base64.getUrlDecoder().decode(settings.publicKey()).length != 32) {
          throw new IllegalArgumentException("Invalid key size");
        }
      } catch (IllegalArgumentException e) {
        issues.add(
            new ValidationIssue("publicKey", "Нужен публичный ключ Reality, 32 байта base64url"));
      }
      if (!settings.shortId().matches("(?:[0-9a-fA-F]{2}){0,8}")) {
        issues.add(new ValidationIssue("shortId", "Short ID: до 8 байт в шестнадцатеричном виде"));
      }
      if (settings.serverName().isBlank()) {
        issues.add(new ValidationIssue("serverName", "Для Reality укажите имя сервера"));
      }
    }
  }
}
