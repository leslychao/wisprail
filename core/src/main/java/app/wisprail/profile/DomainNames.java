package app.wisprail.profile;

import java.net.IDN;
import java.util.Locale;

public final class DomainNames {
  private DomainNames() {}

  public static String normalize(String value) {
    String domain = value.strip();
    if (domain.endsWith(".")) {
      domain = domain.substring(0, domain.length() - 1);
    }
    if (domain.isEmpty()
        || domain.contains(":")
        || domain.contains("/")
        || domain.contains("*")
        || domain.contains("@")) {
      throw new IllegalArgumentException("Введите домен без схемы, порта, пути и *");
    }
    String ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    if (ascii.length() > 253 || ascii.endsWith(".") || ascii.contains("..")) {
      throw new IllegalArgumentException("Некорректное доменное имя");
    }
    return ascii;
  }

  public static boolean matches(String domain, String suffix) {
    String canonicalDomain = normalize(domain);
    String canonicalSuffix = normalize(suffix);
    return canonicalDomain.equals(canonicalSuffix)
        || canonicalDomain.endsWith("." + canonicalSuffix);
  }
}
