package app.wisprail.profile;

import app.wisprail.storage.JsonFiles;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ProfileImport {
  public record Preview(VpnProfile profile, ProfileSecrets secrets, List<String> notes) {
    public Preview {
      notes = List.copyOf(notes);
    }

    @Override
    public String toString() {
      return "ImportPreview[redacted]";
    }
  }

  public record Export(String format, VpnProfile profile) {}

  public Preview fromLink(String text) {
    URI uri;
    try {
      uri = URI.create(text.strip());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Некорректная VLESS-ссылка");
    }
    if (!"vless".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() == null) {
      throw new IllegalArgumentException("Ожидается одна ссылка vless://");
    }
    String uuid = decode(uri.getRawUserInfo());
    if (!uuid.matches(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      throw new IllegalArgumentException("Некорректный UUID VLESS");
    }
    if (uri.getPath() != null && !uri.getPath().isEmpty()) {
      throw new IllegalArgumentException("VLESS TCP не поддерживает путь в ссылке");
    }
    Map<String, String> parameters = new HashMap<>();
    Set<String> allowed =
        Set.of("security", "type", "sni", "pbk", "sid", "fp", "flow", "encryption");
    for (String pair : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      String[] parts = pair.split("=", 2);
      String key = decode(parts[0]);
      if (!allowed.contains(key)
          || parameters.putIfAbsent(key, parts.length == 2 ? decode(parts[1]) : "") != null) {
        throw new IllegalArgumentException("Неподдерживаемый или повторный параметр VLESS: " + key);
      }
    }
    if (!parameters.getOrDefault("type", "tcp").equals("tcp")
        || !parameters.getOrDefault("encryption", "none").equals("none")) {
      throw new IllegalArgumentException("Поддерживается только VLESS TCP с TLS или Reality");
    }
    VpnProfile.Security security =
        switch (parameters.getOrDefault("security", "tls")) {
          case "tls" -> VpnProfile.Security.TLS;
          case "reality" -> VpnProfile.Security.REALITY;
          default -> throw new IllegalArgumentException("VLESS без TLS не поддерживается");
        };
    VpnProfile profile =
        new VpnProfile(
            1,
            UUID.randomUUID(),
            0,
            uri.getRawFragment() == null ? "Новый VLESS" : decode(uri.getRawFragment()),
            uri.getHost(),
            uri.getPort() == -1 ? 443 : uri.getPort(),
            VpnProfile.Protocol.VLESS,
            null,
            new VpnProfile.Vless(
                security,
                parameters.getOrDefault("sni", uri.getHost()),
                parameters.getOrDefault("pbk", ""),
                parameters.getOrDefault("sid", ""),
                parameters.getOrDefault("fp", "chrome"),
                parameters.getOrDefault("flow", "")),
            List.of(),
            VpnProfile.Dns.empty(),
            "",
            "");
    return new Preview(
        profile,
        new ProfileSecrets("", uuid, "", "", "", ""),
        List.of(
            "Добавьте сети и корпоративный DNS либо HTTPS-адрес проверки. Импорт не подключает"
                + " VPN."));
  }

  public Preview fromFile(Path file) throws IOException {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".json")) {
      Export imported = JsonFiles.read(file, Export.class);
      if (!"wisprail-profile-v1".equals(imported.format())) {
        throw new IOException("Ожидается экспорт профиля Wisprail, а не sing-box.json");
      }
      VpnProfile copy = ProfileRepository.copyWithoutSecrets(imported.profile());
      return new Preview(
          copy.withIdentity(copy.id(), 0, imported.profile().name(), ""),
          ProfileSecrets.empty(),
          List.of("Повторно заполните данные доступа и сертификаты."));
    }
    if (!name.endsWith(".ovpn")) {
      throw new IOException("Выберите .ovpn или .json");
    }
    byte[] data;
    try (var input = Files.newInputStream(file)) {
      data = input.readNBytes(JsonFiles.MAX_BYTES + 1);
    }
    if (data.length > JsonFiles.MAX_BYTES) {
      throw new IOException("Файл превышает 1 МБ");
    }
    return new OpenVpnImporter().parse(new String(data, StandardCharsets.UTF_8), file);
  }

  public void export(Path file, VpnProfile profile) throws IOException {
    VpnProfile safe = ProfileRepository.copyWithoutSecrets(profile);
    JsonFiles.writeAtomic(
        file,
        new Export("wisprail-profile-v1", safe.withIdentity(safe.id(), 0, profile.name(), "")));
  }

  private static String decode(String text) {
    return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
  }
}
