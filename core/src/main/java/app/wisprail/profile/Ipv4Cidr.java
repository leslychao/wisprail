package app.wisprail.profile;

import java.util.Comparator;
import java.util.List;

/** Unsigned IPv4 interval, without name resolution or acceptance of abbreviated addresses. */
public record Ipv4Cidr(long address, int prefix) {
  public Ipv4Cidr {
    if (address < 0 || address > 0xffffffffL || prefix < 0 || prefix > 32) {
      throw new IllegalArgumentException("Некорректная IPv4-сеть");
    }
    address &= mask(prefix);
  }

  public static Ipv4Cidr parse(String value) {
    String[] parts = value.strip().split("/", -1);
    if (parts.length > 2 || parts[0].isEmpty()) {
      throw new IllegalArgumentException("Введите IPv4-адрес или сеть с маской");
    }
    int prefix = 32;
    if (parts.length == 2) {
      if (!parts[1].matches("[0-9]{1,2}")) {
        throw new IllegalArgumentException("Маска IPv4 должна быть числом от 0 до 32");
      }
      prefix = Integer.parseInt(parts[1]);
    }
    return new Ipv4Cidr(parseAddress(parts[0]), prefix);
  }

  public static long parseAddress(String value) {
    String[] octets = value.split("\\.", -1);
    if (octets.length != 4) {
      throw new IllegalArgumentException("Нужен IPv4-адрес из четырёх чисел");
    }
    long result = 0;
    for (String octet : octets) {
      if (!octet.matches("0|[1-9][0-9]{0,2}")) {
        throw new IllegalArgumentException("Некорректный IPv4-адрес");
      }
      int number = Integer.parseInt(octet);
      if (number > 255) {
        throw new IllegalArgumentException("Числа IPv4 должны быть от 0 до 255");
      }
      result = (result << 8) | number;
    }
    return result;
  }

  public static String formatAddress(long value) {
    return ((value >>> 24) & 255)
        + "."
        + ((value >>> 16) & 255)
        + "."
        + ((value >>> 8) & 255)
        + "."
        + (value & 255);
  }

  public long lastAddress() {
    return address | (~mask(prefix) & 0xffffffffL);
  }

  public boolean contains(String value) {
    return contains(parseAddress(value));
  }

  public boolean contains(long value) {
    return value >= address && value <= lastAddress();
  }

  public boolean overlaps(Ipv4Cidr other) {
    return address <= other.lastAddress() && other.address <= lastAddress();
  }

  public String canonical() {
    return formatAddress(address) + "/" + prefix;
  }

  public static boolean coversEntireIpv4(List<Ipv4Cidr> networks) {
    List<Ipv4Cidr> sorted =
        networks.stream().sorted(Comparator.comparingLong(Ipv4Cidr::address)).toList();
    long coveredUntil = -1;
    for (Ipv4Cidr network : sorted) {
      if (network.address > coveredUntil + 1) {
        return false;
      }
      coveredUntil = Math.max(coveredUntil, network.lastAddress());
    }
    return coveredUntil == 0xffffffffL;
  }

  private static long mask(int prefix) {
    return prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
  }
}
