package app.wisprail.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

public final class SecureFiles {
  private SecureFiles() {}

  public static void directory(Path path) throws IOException {
    Files.createDirectories(path);
    if (Files.isSymbolicLink(path)) {
      throw new IOException("Каталог данных не должен быть символической ссылкой");
    }
    restrict(path);
  }

  public static void restrict(Path path) throws IOException {
    if (Files.isSymbolicLink(path)) {
      throw new IOException("Символическая ссылка недопустима");
    }
    AclFileAttributeView acl =
        Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl != null) {
      var owner = acl.getOwner();
      var permissions = EnumSet.allOf(AclEntryPermission.class);
      acl.setAcl(
          List.of(
              AclEntry.newBuilder()
                  .setType(AclEntryType.ALLOW)
                  .setPrincipal(owner)
                  .setPermissions(permissions)
                  .build()));
    } else {
      Files.setPosixFilePermissions(
          path,
          PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
    }
  }
}
