package app.wisprail.profile;

import app.wisprail.storage.JsonCodec;
import app.wisprail.storage.PrivateFiles;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Imports data into an unsaved draft. Never executes options from an imported document. */
public final class ProfileImporter {
  private static final Set<String> VLESS_PARAMETERS =
      Set.of("type", "security", "sni", "pbk", "sid", "fp", "flow", "encryption");
  private static final Set<String> INLINE_FIELDS =
      Set.of("ca", "cert", "key", "tls-auth", "tls-crypt", "auth-user-pass");

  public ImportResult importFile(Path path) throws IOException {
    byte[] bytes = PrivateFiles.read(path, ProfileValidator.MAX_TEXT_BYTES);
    String text =
        StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".json")) {
      return importJson(text);
    }
    if (name.endsWith(".ovpn")) {
      return importOpenVpn(path, text);
    }
    throw new IOException("Выберите файл .ovpn или профиль Wisprail .json");
  }

  public ImportResult importVless(String input) {
    if (input.length() > ProfileValidator.MAX_TEXT_BYTES) {
      throw new IllegalArgumentException("Ссылка слишком длинная");
    }
    URI uri;
    try {
      uri = URI.create(input.strip());
    } catch (IllegalArgumentException failure) {
      // The URI parser's exception contains the complete secret-bearing input.
      throw new IllegalArgumentException("Некорректный формат VLESS-ссылки");
    }
    if (!"vless".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("Ссылка должна начинаться с vless://");
    }
    if (uri.getHost() == null
        || uri.getRawUserInfo() == null
        || uri.getRawUserInfo().contains(":")
        || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
        || uri.getHost().contains(":")) {
      throw new IllegalArgumentException("Нужна одна VLESS-ссылка с доменом или IPv4");
    }
    String uuid = decode(uri.getRawUserInfo());
    boolean validUuid;
    try {
      validUuid = UUID.fromString(uuid).toString().equalsIgnoreCase(uuid);
    } catch (IllegalArgumentException failure) {
      validUuid = false;
    }
    if (!validUuid) {
      throw new IllegalArgumentException("Некорректный UUID VLESS");
    }
    Map<String, String> parameters = new HashMap<>();
    if (uri.getRawQuery() != null) {
      for (String pair : uri.getRawQuery().split("&", -1)) {
        String[] parts = pair.split("=", 2);
        String key = decode(parts[0]);
        if (!VLESS_PARAMETERS.contains(key)
            || parts.length != 2
            || parameters.putIfAbsent(key, decode(parts[1])) != null) {
          throw new IllegalArgumentException("Неподдержанный или повторный параметр VLESS");
        }
      }
    }
    if (!parameters.getOrDefault("type", "tcp").equals("tcp")
        || !parameters.getOrDefault("encryption", "none").equals("none")) {
      throw new IllegalArgumentException(
          "Поддерживается только VLESS TCP без дополнительного encryption");
    }
    TlsMode mode =
        switch (parameters.getOrDefault("security", "")) {
          case "tls" -> TlsMode.TLS;
          case "reality" -> TlsMode.REALITY;
          default ->
              throw new IllegalArgumentException("Ссылка должна явно использовать TLS или Reality");
        };
    if (mode == TlsMode.TLS && (parameters.containsKey("pbk") || parameters.containsKey("sid"))) {
      throw new IllegalArgumentException("Параметры Reality требуют security=reality");
    }
    VlessSettings settings =
        new VlessSettings(
            mode,
            parameters.getOrDefault("sni", ""),
            parameters.getOrDefault("pbk", ""),
            parameters.getOrDefault("sid", ""),
            parameters.getOrDefault("fp", "chrome"),
            parameters.getOrDefault("flow", ""));
    int port = uri.getPort() == -1 ? 443 : uri.getPort();
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Некорректный порт VLESS");
    }
    String name = uri.getRawFragment() == null ? "Новый VLESS" : decode(uri.getRawFragment());
    Profile profile =
        new Profile(
            UUID.randomUUID(),
            0,
            name,
            uri.getHost(),
            port,
            settings,
            List.of(),
            "",
            List.of(),
            "",
            "");
    return new ImportResult(
        profile,
        new ProfileSecrets("", "", "", "", "", uuid),
        List.of(
            "Укажите выбранные IPv4-сети и корпоративный DNS или HTTPS-ресурс проверки.",
            "Профиль не сохранён и не подключён."));
  }

  private ImportResult importJson(String text) throws IOException {
    try {
      Profile profile = JsonCodec.readProfileDocument(JsonCodec.parseStrict(text, 16)).profile();
      if (!profile.secretRef().isEmpty()) {
        throw new IOException(
            "Импорт ссылок на локальные секреты запрещён; используйте экспорт профиля");
      }
      profile = profile.withIdentity(UUID.randomUUID(), 0, profile.name(), "");
      return new ImportResult(
          profile,
          ProfileSecrets.empty(),
          List.of(
              "Заполните данные доступа. Секреты не содержатся в экспорте.",
              "Настройки ещё не сохранены и не применены."));
    } catch (JsonParseException | IllegalArgumentException | IllegalStateException failure) {
      throw new IOException("Некорректный JSON-профиль Wisprail");
    }
  }

  private ImportResult importOpenVpn(Path source, String text) throws IOException {
    Map<String, String> options = new HashMap<>();
    List<String> notes = new ArrayList<>();
    Set<String> networks = new LinkedHashSet<>();
    Set<String> domains = new LinkedHashSet<>();
    String[] lines = text.split("\\R", -1);
    long importedBytes = text.getBytes(StandardCharsets.UTF_8).length;
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index].strip();
      if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
        continue;
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new IOException("Импорт отменён");
      }
      if (line.startsWith("<") && line.endsWith(">")) {
        String field = line.substring(1, line.length() - 1);
        if (!INLINE_FIELDS.contains(field) || options.containsKey(field)) {
          throw new IOException("Неподдержанный или повторный блок OpenVPN");
        }
        StringBuilder value = new StringBuilder();
        boolean closed = false;
        while (++index < lines.length) {
          if (lines[index].strip().equals("</" + field + ">")) {
            closed = true;
            break;
          }
          value.append(lines[index]).append('\n');
        }
        if (!closed) {
          throw new IOException("Незакрытый блок OpenVPN");
        }
        options.put(field, value.toString());
        continue;
      }
      List<String> tokens = tokenize(line);
      if (tokens.isEmpty()) {
        continue;
      }
      String directive = tokens.getFirst();
      switch (directive) {
        case "remote" -> {
          requireCount(tokens, 2, 4);
          putUnique(options, "server", tokens.get(1));
          if (tokens.size() > 2) {
            putUnique(options, "port", tokens.get(2));
          }
          if (tokens.size() > 3) {
            putUnique(options, "proto", tokens.get(3));
          }
        }
        case "proto", "port", "cipher", "data-ciphers", "auth", "key-direction" -> {
          requireCount(tokens, 2, 2);
          putUnique(options, directive, tokens.get(1));
        }
        case "verify-x509-name" -> {
          requireCount(tokens, 3, 3);
          if (!tokens.get(2).equals("name")) {
            throw new IOException("Поддерживается verify-x509-name с типом name");
          }
          putUnique(options, directive, tokens.get(1));
        }
        case "dev" -> {
          requireCount(tokens, 2, 2);
          if (!tokens.get(1).equals("tun")) {
            throw new IOException("Поддерживается только OpenVPN TUN");
          }
        }
        case "remote-cert-tls" -> {
          requireCount(tokens, 2, 2);
          if (!tokens.get(1).equals("server")) {
            throw new IOException("Нужна проверка серверного сертификата");
          }
        }
        case "ca", "cert", "key", "tls-auth", "tls-crypt", "auth-user-pass" -> {
          requireCount(
              tokens,
              directive.equals("auth-user-pass") ? 1 : 2,
              directive.equals("tls-auth") ? 3 : 2);
          String value = "";
          if (tokens.size() > 1) {
            Path parent = source.toAbsolutePath().normalize().getParent();
            if (parent == null) {
              throw new IOException("Не удалось определить каталог импорта");
            }
            Path reference = Path.of(tokens.get(1));
            Path path = parent.resolve(reference).normalize();
            if (reference.isAbsolute()
                || !path.startsWith(parent)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().startsWith(parent.toRealPath())) {
              throw new IOException(
                  "Связанный файл должен находиться в каталоге импортируемого профиля");
            }
            byte[] bytes = PrivateFiles.read(path, ProfileValidator.MAX_TEXT_BYTES);
            importedBytes += bytes.length;
            if (importedBytes > ProfileValidator.MAX_TEXT_BYTES) {
              throw new IOException("Профиль со связанными файлами превышает 1 МиБ");
            }
            value =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
          }
          putUnique(options, directive, value);
          if (tokens.size() == 3) {
            putUnique(options, "key-direction", tokens.get(2));
          }
        }
        case "route" -> {
          requireCount(tokens, 2, 4);
          if (tokens.size() == 4 && !tokens.get(3).equals("vpn_gateway")) {
            throw new IOException("Произвольный шлюз маршрута не поддерживается");
          }
          int prefix = tokens.size() > 2 ? maskPrefix(tokens.get(2)) : 32;
          networks.add(Ipv4Cidr.parse(tokens.get(1) + "/" + prefix).canonical());
        }
        case "dhcp-option" -> {
          requireCount(tokens, 3, 3);
          switch (tokens.get(1)) {
            case "DNS" -> putUnique(options, "dns", tokens.get(2));
            case "DOMAIN", "DOMAIN-SEARCH" -> domains.add(DomainNames.normalize(tokens.get(2)));
            default -> throw new IOException("Неподдержанный параметр dhcp-option");
          }
        }
        case "redirect-gateway" ->
            notes.add("redirect-gateway не применяется: полного туннеля нет.");
        case "client", "nobind", "persist-key", "persist-tun", "auth-nocache", "route-nopull" -> {
          requireCount(tokens, 1, 1);
          notes.add(directive + ": поведением управляет Wisprail и внутренний стек движка.");
        }
        case "verb", "mute", "resolv-retry" -> {
          requireCount(tokens, 2, 2);
          notes.add(directive + ": журнал и ограниченные повторы управляются Wisprail.");
        }
        default ->
            throw new IOException(
                "Профиль содержит неподдержанную директиву OpenVPN: " + safeDirective(directive));
      }
    }
    if (!options.containsKey("server")) {
      throw new IOException("В файле отсутствует remote");
    }
    if (networks.size() > ProfileValidator.MAX_ENTRIES
        || domains.size() > ProfileValidator.MAX_ENTRIES) {
      throw new IOException("Слишком много сетей или доменов");
    }
    if (Ipv4Cidr.coversEntireIpv4(networks.stream().map(Ipv4Cidr::parse).toList())) {
      throw new IOException("Полный охват IPv4 в импортированном профиле запрещён");
    }
    if (options.containsKey("cipher") && options.containsKey("data-ciphers")) {
      throw new IOException("Совместный импорт cipher и data-ciphers требует явного выбора шифров");
    }
    String transport =
        switch (options.getOrDefault("proto", "udp")) {
          case "udp", "udp4" -> "udp";
          case "tcp", "tcp-client", "tcp4-client" -> "tcp";
          default -> throw new IOException("Неподдержанный транспорт OpenVPN");
        };
    String username = "";
    String password = "";
    if (!options.getOrDefault("auth-user-pass", "").isEmpty()) {
      List<String> credentials = options.get("auth-user-pass").lines().toList();
      if (credentials.size() != 2) {
        throw new IOException("auth-user-pass должен содержать имя и пароль отдельными строками");
      }
      username = credentials.getFirst();
      password = credentials.get(1);
    }
    int port;
    int direction;
    try {
      port = Integer.parseInt(options.getOrDefault("port", "1194"));
      direction = Integer.parseInt(options.getOrDefault("key-direction", "-1"));
    } catch (NumberFormatException e) {
      throw new IOException("Некорректный порт или направление TLS-ключа");
    }
    OpenVpnSettings settings =
        new OpenVpnSettings(
            transport,
            username,
            password.isEmpty(),
            options.getOrDefault("ca", ""),
            options.getOrDefault("cert", ""),
            options.getOrDefault("data-ciphers", options.getOrDefault("cipher", "")),
            options.getOrDefault("auth", ""),
            options.getOrDefault("verify-x509-name", ""),
            direction);
    String name = source.getFileName().toString().replaceFirst("(?i)\\.ovpn$", "");
    Profile profile =
        new Profile(
            UUID.randomUUID(),
            0,
            name,
            options.get("server"),
            port,
            settings,
            List.copyOf(networks),
            options.getOrDefault("dns", ""),
            List.copyOf(domains),
            "",
            "");
    ProfileSecrets secrets =
        new ProfileSecrets(
            password,
            options.getOrDefault("key", ""),
            "",
            options.getOrDefault("tls-auth", ""),
            options.getOrDefault("tls-crypt", ""),
            "");
    notes.add("Проверьте параметры и заполните недостающие поля; подключение не выполнялось.");
    return new ImportResult(profile, secrets, List.copyOf(new LinkedHashSet<>(notes)));
  }

  private static List<String> tokenize(String line) throws IOException {
    StreamTokenizer tokenizer = new StreamTokenizer(new StringReader(line));
    tokenizer.resetSyntax();
    tokenizer.wordChars(33, 65535);
    tokenizer.whitespaceChars(0, 32);
    tokenizer.quoteChar('"');
    tokenizer.quoteChar('\'');
    tokenizer.commentChar('#');
    tokenizer.commentChar(';');
    List<String> tokens = new ArrayList<>();
    while (tokenizer.nextToken() != StreamTokenizer.TT_EOF) {
      if (tokenizer.sval == null) {
        throw new IOException("Некорректная строка OpenVPN");
      }
      tokens.add(tokenizer.sval);
    }
    return tokens;
  }

  private static void putUnique(Map<String, String> options, String field, String value)
      throws IOException {
    if (options.putIfAbsent(field, value) != null) {
      throw new IOException("Повторный значимый параметр OpenVPN: " + field);
    }
  }

  private static int maskPrefix(String mask) throws IOException {
    long value = Ipv4Cidr.parseAddress(mask);
    long inverted = (~value) & 0xffffffffL;
    if ((inverted & (inverted + 1)) != 0) {
      throw new IOException("Некорректная маска сети");
    }
    return Long.bitCount(value);
  }

  private static void requireCount(List<String> tokens, int minimum, int maximum)
      throws IOException {
    if (tokens.size() < minimum || tokens.size() > maximum) {
      throw new IOException(
          "Неверное число параметров OpenVPN: " + safeDirective(tokens.getFirst()));
    }
  }

  private static String safeDirective(String value) {
    return value.matches("[a-zA-Z][a-zA-Z0-9-]{0,40}") ? value : "неизвестная";
  }

  private static String decode(String text) {
    try {
      return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("Некорректное кодирование параметров VLESS-ссылки");
    }
  }
}
