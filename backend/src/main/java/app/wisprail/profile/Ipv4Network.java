package app.wisprail.profile;

import java.util.Comparator;
import java.util.List;

/** A canonical IPv4 network, represented without DNS lookups or signed arithmetic. */
public record Ipv4Network(long address, int prefix) {
  private static final long IPV4_MAX = 0xffff_ffffL;

  public Ipv4Network {
    if (address < 0 || address > IPV4_MAX || prefix < 0 || prefix > 32) {
      throw new IllegalArgumentException("Некорректная IPv4-сеть");
    }
    address &= mask(prefix);
  }

  public static Ipv4Network parse(String text) {
    String[] parts = text.strip().split("/", -1);
    if (parts.length > 2) {
      throw new IllegalArgumentException("Укажите IPv4-адрес или сеть CIDR");
    }
    try {
      return new Ipv4Network(
          parseAddress(parts[0]), parts.length == 1 ? 32 : Integer.parseInt(parts[1]));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Некорректная маска IPv4", exception);
    }
  }

  public static long parseAddress(String text) {
    String[] octets = text.split("\\.", -1);
    if (octets.length != 4) {
      throw new IllegalArgumentException("Ожидается IPv4-адрес; IPv6 пока не поддерживается");
    }
    long result = 0;
    for (String octet : octets) {
      if (!octet.matches("0|[1-9][0-9]{0,2}")) {
        throw new IllegalArgumentException("Некорректный IPv4-адрес");
      }
      int value = Integer.parseInt(octet);
      if (value > 255) {
        throw new IllegalArgumentException("Некорректный IPv4-адрес");
      }
      result = (result << 8) | value;
    }
    return result;
  }

  public boolean contains(long candidate) {
    return (candidate & mask(prefix)) == address;
  }

  public boolean overlaps(Ipv4Network other) {
    return address <= other.lastAddress() && other.address <= lastAddress();
  }

  public long lastAddress() {
    return address | (IPV4_MAX ^ mask(prefix));
  }

  public static boolean coversInternet(List<Ipv4Network> networks) {
    long next = 0;
    for (Ipv4Network network :
        networks.stream().sorted(Comparator.comparingLong(Ipv4Network::address)).toList()) {
      if (network.address > next) {
        return false;
      }
      next = Math.max(next, network.lastAddress() + 1);
      if (next == IPV4_MAX + 1) {
        return true;
      }
    }
    return false;
  }

  private static long mask(int prefix) {
    return prefix == 0 ? 0 : (IPV4_MAX << (32 - prefix)) & IPV4_MAX;
  }

  public static String formatAddress(long address) {
    return "%d.%d.%d.%d"
        .formatted(address >>> 24, (address >>> 16) & 255, (address >>> 8) & 255, address & 255);
  }

  @Override
  public String toString() {
    return formatAddress(address) + "/" + prefix;
  }
}
