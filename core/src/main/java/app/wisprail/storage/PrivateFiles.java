package app.wisprail.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/** Owner-only files. Atomic replacement fails rather than falling back to a partial write. */
public final class PrivateFiles {
  private PrivateFiles() {}

  public static Path directory(Path path) throws IOException {
    Files.createDirectories(path);
    restrict(path);
    return path;
  }

  public static void restrict(Path path) throws IOException {
    if (Files.isSymbolicLink(path)) {
      throw new IOException("Хранилище не должно быть символической ссылкой");
    }
    var posix =
        Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      String mode = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "rwx------" : "rw-------";
      posix.setPermissions(PosixFilePermissions.fromString(mode));
      return;
    }
    var acl =
        Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl == null) {
      throw new IOException("Файловая система не поддерживает защиту данных");
    }
    acl.setAcl(
        List.of(
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build()));
  }

  public static byte[] read(Path path, int maximumBytes) throws IOException {
    if (Files.isSymbolicLink(path)) {
      throw new IOException("Чтение данных по символической ссылке запрещено");
    }
    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(maximumBytes + 1);
      if (bytes.length > maximumBytes) {
        throw new IOException("Файл превышает допустимый размер");
      }
      return bytes;
    }
  }

  public static void writeAtomic(Path destination, byte[] bytes) throws IOException {
    Path directory = destination.toAbsolutePath().getParent();
    if (directory == null || Files.isSymbolicLink(destination)) {
      throw new IOException("Недопустимый путь данных");
    }
    Path temporary = Files.createTempFile(directory, ".wisprail-", ".tmp");
    try {
      restrict(temporary);
      try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          output.write(buffer);
        }
        output.force(true);
      }
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
