package app.wisprail.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import jdk.net.ExtendedSocketOptions;

public final class UnixTransport implements LocalTransport {
  private final Path socketPath;
  private final String allowedUser;
  private final ServerSocketChannel server;

  public UnixTransport(Path socketPath, String allowedUser) throws IOException {
    this.socketPath = socketPath;
    this.allowedUser = allowedUser;
    if (Files.exists(socketPath)) {
      throw new IOException(
          "IPC-сокет уже существует; требуется проверка владельца и восстановление");
    }
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      server.bind(UnixDomainSocketAddress.of(socketPath));
      Files.setOwner(
          socketPath,
          socketPath
              .getFileSystem()
              .getUserPrincipalLookupService()
              .lookupPrincipalByName(allowedUser));
      Files.setPosixFilePermissions(socketPath, PosixFilePermissions.fromString("rw-------"));
    } catch (IOException exception) {
      server.close();
      throw exception;
    }
  }

  @Override
  public Session accept() throws IOException {
    SocketChannel channel = server.accept();
    try {
      if (!channel
          .getOption(ExtendedSocketOptions.SO_PEERCRED)
          .user()
          .getName()
          .equals(allowedUser)) {
        throw new IOException("Доступ к службе запрещён");
      }
      return session(channel);
    } catch (IOException exception) {
      channel.close();
      throw exception;
    }
  }

  public static Session connect(Path path) throws IOException {
    SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      channel.connect(UnixDomainSocketAddress.of(path));
      if (!channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user().getName().equals("root")) {
        throw new IOException("Не подтверждён системный владелец службы");
      }
      return session(channel);
    } catch (IOException exception) {
      channel.close();
      throw exception;
    }
  }

  private static Session session(SocketChannel channel) {
    InputStream input = Channels.newInputStream(channel);
    OutputStream output = Channels.newOutputStream(channel);
    return new Session() {
      @Override
      public InputStream input() {
        return input;
      }

      @Override
      public OutputStream output() {
        return output;
      }

      @Override
      public void close() throws IOException {
        channel.close();
      }
    };
  }

  @Override
  public void close() throws IOException {
    server.close();
    Files.deleteIfExists(socketPath);
  }
}
