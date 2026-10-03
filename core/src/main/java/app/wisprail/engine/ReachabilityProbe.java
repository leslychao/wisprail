package app.wisprail.engine;

import app.wisprail.profile.DomainName;
import app.wisprail.profile.Ipv4Network;
import app.wisprail.profile.VpnProfile;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class ReachabilityProbe {
  private static final int TIMEOUT_MILLIS = 5000;

  public void verify(VpnProfile profile, String localDns) throws IOException {
    if (!profile.probeUrl().isBlank()) {
      https(profile);
    } else if (!profile.dns().server().isEmpty()) {
      dns(localDns, profile.dns().domains().getFirst());
    } else {
      throw new IOException("Не задан ресурс проверки");
    }
  }

  public void dns(String server, String domain) throws IOException {
    int id = new SecureRandom().nextInt(65536);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (DataOutputStream data = new DataOutputStream(buffer)) {
      data.writeShort(id);
      data.writeShort(0x0100);
      data.writeShort(1);
      data.writeShort(0);
      data.writeShort(0);
      data.writeShort(0);
      for (String label : DomainName.normalize(domain).split("\\.")) {
        byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
        data.writeByte(bytes.length);
        data.write(bytes);
      }
      data.writeByte(0);
      data.writeShort(6);
      data.writeShort(1);
    }
    byte[] query = buffer.toByteArray();
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.connect(InetAddress.getByName(server), 53);
      socket.setSoTimeout(TIMEOUT_MILLIS);
      socket.send(new DatagramPacket(query, query.length));
      byte[] bytes = new byte[65535];
      DatagramPacket response = new DatagramPacket(bytes, bytes.length);
      socket.receive(response);
      int code = bytes[3] & 15;
      if (response.getLength() < query.length
          || (bytes[0] & 255) != id >>> 8
          || (bytes[1] & 255) != (id & 255)
          || (bytes[2] & 0x80) == 0
          || (bytes[2] & 0x02) != 0
          || code != 0 && code != 3
          || !Arrays.equals(
              Arrays.copyOfRange(query, 12, query.length),
              Arrays.copyOfRange(bytes, 12, query.length))) {
        throw new IOException("DNS не вернул ожидаемый полноценный ответ");
      }
    }
  }

  private static void https(VpnProfile profile) throws IOException {
    URI uri = URI.create(profile.probeUrl());
    List<Ipv4Network> networks = profile.networks().stream().map(Ipv4Network::parse).toList();
    InetAddress address = null;
    for (InetAddress candidate : AddressResolver.resolve(uri.getHost(), () -> false)) {
      if (candidate instanceof Inet4Address
          && networks.stream()
              .anyMatch(
                  network ->
                      network.contains(Ipv4Network.parseAddress(candidate.getHostAddress())))) {
        address = candidate;
        break;
      }
    }
    if (address == null) {
      throw new IOException("Адрес проверки не входит в IPv4-сети профиля");
    }
    int port = uri.getPort() == -1 ? 443 : uri.getPort();
    try (Socket raw = new Socket()) {
      raw.connect(new InetSocketAddress(address, port), TIMEOUT_MILLIS);
      raw.setSoTimeout(TIMEOUT_MILLIS);
      SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
      try (SSLSocket socket = (SSLSocket) factory.createSocket(raw, uri.getHost(), port, true)) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!uri.getHost().matches("[0-9.]+")) {
          parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
        }
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
          path += "?" + uri.getRawQuery();
        }
        String request =
            "HEAD "
                + path
                + " HTTP/1.1\r\nHost: "
                + uri.getHost()
                + "\r\nConnection: close\r\nUser-Agent: Wisprail/0.1\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        StringBuilder status = new StringBuilder();
        for (int index = 0; index < 1024; index++) {
          int character = reader.read();
          if (character == -1 || character == '\n') {
            break;
          }
          status.append((char) character);
        }
        if (!status.toString().matches("HTTP/1\\.[01] [1-5][0-9]{2}[^\\n]*")) {
          throw new IOException("Ресурс не вернул HTTP-ответ через VPN");
        }
      }
    }
  }
}
