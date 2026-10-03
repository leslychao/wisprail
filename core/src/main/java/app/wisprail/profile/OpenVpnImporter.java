package app.wisprail.profile;

import app.wisprail.storage.JsonFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit subset of OpenVPN directives; unknown input is never silently discarded. */
final class OpenVpnImporter {
  private static final Set<String> MATERIAL =
      Set.of("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2");
  private static final Set<String> CLIENT_OPTIONS =
      Set.of("client", "nobind", "persist-key", "persist-tun");

  ProfileImport.Preview parse(String content, Path source) throws IOException {
    List<String> notes = new ArrayList<>();
    Map<String, String> settings = new HashMap<>();
    Map<String, String> material = new HashMap<>();
    List<String> networks = new ArrayList<>();
    List<String> domains = new ArrayList<>();
    String[] lines = content.split("\\R");
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index].strip();
      if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
        continue;
      }
      if (line.startsWith("<")) {
        if (line.length() < 3) {
          throw new IOException("Некорректный блок OpenVPN");
        }
        String tag = line.substring(1, line.length() - 1);
        if (!MATERIAL.contains(tag) || !line.equals("<" + tag + ">")) {
          throw new IOException("Неподдерживаемый блок OpenVPN");
        }
        StringBuilder value = new StringBuilder();
        while (++index < lines.length && !lines[index].strip().equals("</" + tag + ">")) {
          value.append(lines[index]).append('\n');
        }
        if (index == lines.length || material.putIfAbsent(tag, value.toString()) != null) {
          throw new IOException("Незакрытый или повторный блок OpenVPN");
        }
        continue;
      }
      List<String> tokens = tokenize(line);
      String directive = tokens.getFirst();
      if (MATERIAL.contains(directive)) {
        requireCount(tokens, 2, directive.equals("tls-auth") ? 3 : 2);
        Path base = source.toAbsolutePath().getParent().toRealPath();
        Path file = base.resolve(tokens.get(1)).normalize().toRealPath();
        if (!file.startsWith(base) || Files.size(file) > JsonFiles.MAX_BYTES) {
          throw new IOException(
              "Связанный сертификат должен находиться в каталоге импорта и быть не больше 1 МБ");
        }
        if (material.putIfAbsent(directive, Files.readString(file, StandardCharsets.UTF_8))
            != null) {
          throw new IOException("Повторный материал OpenVPN: " + directive);
        }
        if (tokens.size() == 3) {
          settings.put("key-direction", tokens.get(2));
        }
      } else if (CLIENT_OPTIONS.contains(directive)) {
        requireCount(tokens, 1, 1);
        notes.add(directive + ": жизненным циклом управляет Wisprail.");
      } else {
        readDirective(tokens, settings, networks, domains, notes);
      }
    }
    String server = settings.get("remote");
    if (server == null) {
      throw new IOException("Не найден адрес remote");
    }
    String wrap = "";
    for (String type : List.of("tls-auth", "tls-crypt", "tls-crypt-v2")) {
      if (material.containsKey(type)) {
        if (!wrap.isEmpty()) {
          throw new IOException("Несколько несовместимых способов защиты канала OpenVPN");
        }
        wrap = type;
      }
    }
    VpnProfile profile =
        new VpnProfile(
            1,
            UUID.randomUUID(),
            0,
            source.getFileName().toString().replaceFirst("(?i)\\.ovpn$", ""),
            server,
            Integer.parseInt(settings.getOrDefault("port", "1194")),
            VpnProfile.Protocol.OPENVPN,
            new VpnProfile.OpenVpn(
                settings.getOrDefault("proto", "udp"),
                "",
                settings.getOrDefault("verify-x509-name", ""),
                settings.getOrDefault("cipher", ""),
                settings.getOrDefault("auth", "SHA256"),
                wrap,
                settings.getOrDefault("key-direction", "")),
            null,
            networks.stream().distinct().toList(),
            new VpnProfile.Dns(
                settings.getOrDefault("dns", ""), domains.stream().distinct().toList()),
            "",
            "");
    return new ProfileImport.Preview(
        profile,
        new ProfileSecrets(
            "",
            "",
            material.getOrDefault("ca", ""),
            material.getOrDefault("cert", ""),
            material.getOrDefault("key", ""),
            material.getOrDefault(wrap, "")),
        notes);
  }

  private static void readDirective(
      List<String> tokens,
      Map<String, String> settings,
      List<String> networks,
      List<String> domains,
      List<String> notes)
      throws IOException {
    String name = tokens.getFirst();
    switch (name) {
      case "remote" -> {
        requireCount(tokens, 2, 3);
        putOnce(settings, "remote", tokens.get(1));
        if (tokens.size() == 3) {
          putOnce(settings, "port", tokens.get(2));
        }
      }
      case "proto" -> {
        requireCount(tokens, 2, 2);
        String transport =
            switch (tokens.get(1)) {
              case "udp", "udp4" -> "udp";
              case "tcp", "tcp-client", "tcp4-client" -> "tcp";
              default -> throw new IOException("Неподдерживаемый транспорт OpenVPN");
            };
        putOnce(settings, name, transport);
      }
      case "dev", "remote-cert-tls", "tls-version-min" -> {
        requireCount(tokens, 2, 2);
        String expected =
            switch (name) {
              case "dev" -> "tun";
              case "remote-cert-tls" -> "server";
              default -> "1.2";
            };
        if (!tokens.get(1).equals(expected)) {
          throw new IOException("Неподдерживаемое значение " + name);
        }
      }
      case "auth-user-pass" -> {
        requireCount(tokens, 1, 1);
        notes.add("Имя пользователя и пароль задаются во вкладке «Доступ».");
      }
      case "verify-x509-name" -> {
        requireCount(tokens, 2, 3);
        if (tokens.size() == 3 && !tokens.get(2).equals("name")) {
          throw new IOException("Поддерживается verify-x509-name с точным именем");
        }
        putOnce(settings, name, tokens.get(1));
      }
      case "cipher", "auth", "key-direction", "port" -> {
        requireCount(tokens, 2, 2);
        putOnce(settings, name, tokens.get(1));
      }
      case "route" -> {
        requireCount(tokens, 3, 3);
        long mask = Ipv4Network.parseAddress(tokens.get(2));
        String bits = Long.toBinaryString(mask | (1L << 32)).substring(1);
        if (!bits.matches("1*0*")) {
          throw new IOException("Некорректная маска маршрута OpenVPN");
        }
        networks.add(
            new Ipv4Network(Ipv4Network.parseAddress(tokens.get(1)), Long.bitCount(mask))
                .toString());
      }
      case "dhcp-option" -> {
        requireCount(tokens, 3, 3);
        switch (tokens.get(1)) {
          case "DNS" -> putOnce(settings, "dns", tokens.get(2));
          case "DOMAIN", "DOMAIN-SEARCH" -> domains.add(DomainName.normalize(tokens.get(2)));
          default -> throw new IOException("Неподдерживаемый dhcp-option");
        }
      }
      case "redirect-gateway", "redirect-private", "route-nopull" ->
          notes.add(name + ": не применяется; используются только сети профиля.");
      case "verb" -> {
        requireCount(tokens, 2, 2);
        notes.add("Уровнем журнала управляет Wisprail.");
      }
      default -> throw new IOException("Неподдерживаемая директива OpenVPN: " + name);
    }
  }

  private static void putOnce(Map<String, String> settings, String key, String value)
      throws IOException {
    if (settings.putIfAbsent(key, value) != null) {
      throw new IOException("Повторная директива OpenVPN: " + key);
    }
  }

  private static void requireCount(List<String> tokens, int minimum, int maximum)
      throws IOException {
    if (tokens.size() < minimum || tokens.size() > maximum) {
      throw new IOException("Неподдерживаемые аргументы директивы " + tokens.getFirst());
    }
  }

  private static List<String> tokenize(String line) throws IOException {
    List<String> tokens = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    char quote = 0;
    for (char character : line.toCharArray()) {
      if (quote != 0) {
        if (character == quote) {
          quote = 0;
        } else {
          token.append(character);
        }
      } else if ((character == '#' || character == ';') && token.isEmpty()) {
        break;
      } else if (character == '\'' || character == '"') {
        quote = character;
      } else if (Character.isWhitespace(character)) {
        if (!token.isEmpty()) {
          tokens.add(token.toString());
          token.setLength(0);
        }
      } else {
        token.append(character);
      }
    }
    if (quote != 0 || tokens.isEmpty() && token.isEmpty()) {
      throw new IOException("Некорректная строка OpenVPN");
    }
    if (!token.isEmpty()) {
      tokens.add(token.toString());
    }
    return tokens;
  }
}
