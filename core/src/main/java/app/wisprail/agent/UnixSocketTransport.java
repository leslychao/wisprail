package app.wisprail.agent;

import java.io.IOException;
import java.net.ConnectException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import jdk.net.ExtendedSocketOptions;

/** macOS local IPC with kernel peer credentials and a root-owned socket directory. */
public final class UnixSocketTransport {
  private UnixSocketTransport() {}

  public static IpcConnection connect(Path socket) throws IOException {
    requireRootDirectory(socket.getParent());
    SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      channel.connect(UnixDomainSocketAddress.of(socket));
      UserPrincipal peer = channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user();
      UserPrincipal root =
          socket.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("root");
      if (!peer.equals(root)) {
        throw new IOException("Служба не принадлежит системному пользователю");
      }
      return new Connection(channel, peer.getName());
    } catch (IOException | RuntimeException exception) {
      channel.close();
      throw exception;
    }
  }

  public static IpcListener listen(Path socket, String authorizedUser) throws IOException {
    requireRootDirectory(socket.getParent());
    UserPrincipal owner =
        socket
            .getFileSystem()
            .getUserPrincipalLookupService()
            .lookupPrincipalByName(authorizedUser);
    if (Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
      BasicFileAttributes attributes =
          Files.readAttributes(socket, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isOther()
          || !Files.getOwner(socket, LinkOption.NOFOLLOW_LINKS).equals(owner)) {
        throw new IOException("Нельзя удалить чужой объект на месте сокета службы");
      }
      boolean stale = false;
      try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
        probe.connect(UnixDomainSocketAddress.of(socket));
      } catch (ConnectException exception) {
        stale = true;
      }
      if (!stale) {
        throw new IOException("Другой экземпляр службы уже принимает соединения");
      }
      Files.delete(socket);
    }
    ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      server.bind(UnixDomainSocketAddress.of(socket));
      // Only the authorized user can connect; the containing directory remains root-owned.
      Files.setOwner(socket, owner);
      Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
      return new Listener(server, socket, owner);
    } catch (IOException | RuntimeException exception) {
      server.close();
      throw exception;
    }
  }

  private static void requireRootDirectory(Path directory) throws IOException {
    var attributes = Files.readAttributes(directory, "posix:*", LinkOption.NOFOLLOW_LINKS);
    UserPrincipal root =
        directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("root");
    if (!root.equals(attributes.get("owner"))
        || Boolean.TRUE.equals(attributes.get("isSymbolicLink"))) {
      throw new IOException("Каталог службы должен принадлежать root");
    }
    var permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
    if (permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
        || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
      throw new IOException("Каталог службы доступен для посторонней записи");
    }
  }

  private static final class Connection extends FramedConnection {
    private final SocketChannel channel;

    private Connection(SocketChannel channel, String peer) {
      super(peer);
      this.channel = channel;
    }

    @Override
    int read(ByteBuffer buffer) throws IOException {
      return channel.read(buffer);
    }

    @Override
    void write(ByteBuffer buffer) throws IOException {
      channel.write(buffer);
    }

    @Override
    public void close() throws IOException {
      channel.close();
    }
  }

  private static final class Listener implements IpcListener {
    private final ServerSocketChannel server;
    private final Path socket;
    private final UserPrincipal authorizedUser;

    private Listener(ServerSocketChannel server, Path socket, UserPrincipal authorizedUser) {
      this.server = server;
      this.socket = socket;
      this.authorizedUser = authorizedUser;
    }

    @Override
    public IpcConnection accept() throws IOException {
      SocketChannel channel = server.accept();
      try {
        UserPrincipal peer = channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user();
        if (!peer.equals(authorizedUser)) {
          throw new IOException("Пользователь не имеет доступа к службе");
        }
        return new Connection(channel, peer.getName());
      } catch (IOException | RuntimeException exception) {
        channel.close();
        throw exception;
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
      Files.deleteIfExists(socket);
    }
  }
}
