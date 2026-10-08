package app.wisprail.engine;

import app.wisprail.profile.DomainNames;
import app.wisprail.profile.Ipv4Cidr;
import app.wisprail.profile.Profile;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

/** Protocol answers, never process or port existence, establish VLESS readiness. */
final class ReadinessProbe {
  private ReadinessProbe() {}

  static void verify(Profile profile, Duration timeout) throws IOException {
    if (!profile.healthUrl().isBlank()) {
      https(profile, timeout);
    } else {
      dns(profile, 53, timeout);
    }
  }

  static void dns(Profile profile, int port, Duration timeout) throws IOException {
    if (profile.dns().isBlank()) {
      throw new IOException("Для проверки готовности нужен корпоративный DNS или HTTPS-ресурс");
    }
    String domain = ".";
    int type = Type.NS;
    if (!profile.domains().isEmpty()) {
      String suffix = profile.domains().getFirst();
      String nonce = "wisprail-" + UUID.randomUUID().toString().replace("-", "");
      domain = (suffix.length() < 200 ? nonce + "." : "") + suffix + ".";
      type = Type.A;
    }
    SimpleResolver resolver = new SimpleResolver(profile.dns());
    resolver.setAddress(new InetSocketAddress(profile.dns(), port));
    resolver.setTimeout(timeout);
    Message request = Message.newQuery(Record.newRecord(Name.fromString(domain), type, DClass.IN));
    Message response = resolver.send(request);
    requireResponse(request, response);
  }

  static List<String> resolveIpv4(Profile profile, String host, Duration timeout)
      throws IOException {
    if (host.matches("[0-9.]+")) {
      Ipv4Cidr.parseAddress(host);
      return List.of(host);
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    Name current = Name.fromString(host.endsWith(".") ? host : host + ".");
    for (int redirects = 0; redirects < 8; redirects++) {
      String queriedHost = current.toString(true);
      boolean corporate =
          !profile.dns().isBlank()
              && profile.domains().stream()
                  .anyMatch(suffix -> DomainNames.matches(queriedHost, suffix));
      SimpleResolver resolver =
          corporate ? new SimpleResolver(profile.dns()) : new SimpleResolver();
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new IOException("Истекло время разрешения IPv4-адреса");
      }
      resolver.setTimeout(Duration.ofNanos(remaining));
      Message request = Message.newQuery(Record.newRecord(current, Type.A, DClass.IN));
      Message response = resolver.send(request);
      requireResponse(request, response);
      Name answerName = current;
      for (int aliases = 0; aliases < 8; aliases++) {
        Name expectedName = answerName;
        List<String> addresses =
            response.getSection(Section.ANSWER).stream()
                .filter(ARecord.class::isInstance)
                .map(ARecord.class::cast)
                .filter(record -> record.getName().equals(expectedName))
                .map(record -> record.getAddress().getHostAddress())
                .distinct()
                .toList();
        if (!addresses.isEmpty()) {
          return addresses;
        }
        var alias =
            response.getSection(Section.ANSWER).stream()
                .filter(CNAMERecord.class::isInstance)
                .map(CNAMERecord.class::cast)
                .filter(record -> record.getName().equals(expectedName))
                .findFirst();
        if (alias.isEmpty()) {
          break;
        }
        answerName = alias.orElseThrow().getTarget();
      }
      if (answerName.equals(current) || response.getRcode() == Rcode.NXDOMAIN) {
        return List.of();
      }
      current = answerName;
    }
    throw new IOException("Слишком длинная цепочка DNS CNAME");
  }

  static void requireResponse(Message request, Message response) throws IOException {
    if (!response.getHeader().getFlag(Flags.QR)
        || response.getHeader().getID() != request.getHeader().getID()
        || !request.getQuestion().equals(response.getQuestion())) {
      throw new IOException("DNS вернул ответ, не соответствующий запросу");
    }
    if (response.getRcode() != Rcode.NOERROR && response.getRcode() != Rcode.NXDOMAIN) {
      throw new IOException(
          "Корпоративный DNS отклонил проверку: " + Rcode.string(response.getRcode()));
    }
  }

  private static void https(Profile profile, Duration timeout) throws IOException {
    URI uri = URI.create(profile.healthUrl());
    long deadline = System.nanoTime() + timeout.toNanos();
    List<String> resolved = resolveIpv4(profile, uri.getHost(), timeout);
    List<Ipv4Cidr> networks = profile.networks().stream().map(Ipv4Cidr::parse).toList();
    if (resolved.isEmpty()
        || resolved.stream()
            .anyMatch(
                address -> networks.stream().noneMatch(network -> network.contains(address)))) {
      throw new IOException("Ресурс проверки разрешается вне выбранных IPv4-сетей");
    }
    InetAddress[] addresses = new InetAddress[resolved.size()];
    for (int index = 0; index < addresses.length; index++) {
      addresses[index] = InetAddress.getByName(resolved.get(index));
      if (!(addresses[index] instanceof Inet4Address)) {
        throw new IOException("Проверка поддерживает только IPv4");
      }
    }
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) {
      throw new IOException("Истекло время проверки DNS ресурса");
    }
    DnsResolver resolver =
        new DnsResolver() {
          @Override
          public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!host.equalsIgnoreCase(uri.getHost())) {
              throw new UnknownHostException("Неожиданный адрес проверки");
            }
            return Arrays.copyOf(addresses, addresses.length);
          }

          @Override
          public String resolveCanonicalHostname(String host) throws UnknownHostException {
            resolve(host);
            return host;
          }
        };
    Timeout limit = Timeout.ofNanoseconds(remaining);
    var manager =
        PoolingAsyncClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom().setConnectTimeout(limit).setSocketTimeout(limit).build())
            .build();
    try (var client =
        HttpAsyncClients.custom()
            .setConnectionManager(manager)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .disableCookieManagement()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(limit)
                    .setConnectionRequestTimeout(limit)
                    .build())
            .build()) {
      client.start();
      var responseFuture = client.execute(SimpleRequestBuilder.head(uri).build(), null);
      try {
        var response = responseFuture.get(remaining, TimeUnit.NANOSECONDS);
        if (response.getCode() < 200 || response.getCode() > 499) {
          throw new IOException("Ресурс VPN вернул HTTP " + response.getCode());
        }
      } catch (TimeoutException timeoutFailure) {
        responseFuture.cancel(true);
        throw new IOException("Истекло время проверки HTTPS-ресурса", timeoutFailure);
      } catch (InterruptedException interruption) {
        responseFuture.cancel(true);
        Thread.currentThread().interrupt();
        throw new IOException("Проверка HTTPS-ресурса прервана", interruption);
      } catch (ExecutionException failure) {
        throw new IOException(
            "HTTPS-ресурс не подтвердил защищённое соединение", failure.getCause());
      }
    }
  }
}
