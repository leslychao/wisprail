package app.wisprail.profile;

import java.net.IDN;
import java.util.Locale;

public final class DomainName {
  private DomainName() {}

  public static String normalize(String value) {
    String name = value.strip();
    if (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    name = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    if (name.length() > 253 || !name.contains(".")) {
      throw new IllegalArgumentException("Укажите полное доменное имя без схемы, пути и *");
    }
    for (String label : name.split("\\.", -1)) {
      if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
        throw new IllegalArgumentException("Некорректное доменное имя");
      }
    }
    return name;
  }

  public static boolean matches(String query, String suffix) {
    String normalized = normalize(query);
    String domain = normalize(suffix);
    return normalized.equals(domain) || normalized.endsWith("." + domain);
  }
}
