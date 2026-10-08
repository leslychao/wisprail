package app.wisprail.platform;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class MacNative {
  interface Api extends Library {
    int wr_effective_uid();

    int wr_keychain_read(String reference, PointerByReference output, LongByReference length);

    int wr_keychain_write(String reference, byte[] value, long length);

    int wr_keychain_delete(String reference);

    void wr_free_secret(Pointer buffer, long length);

    int wr_dns_add(String operation, String server, StringArray domains, int count);

    int wr_dns_remove(String operation, String server, StringArray domains, int count);

    int wr_dns_snapshot(PointerByReference output, LongByReference length);

    int wr_service_status();

    int wr_service_register();

    int wr_service_unregister();

    void wr_service_open_settings();

    int wr_autostart_status();

    int wr_autostart_set(int enabled);

    int wr_console_user(PointerByReference output, LongByReference length);
  }

  private MacNative() {}

  static Api api() throws IOException {
    Path library =
        PlatformServices.installationDirectory().resolve("app/native/libwisprail-platform.dylib");
    if (!Files.isRegularFile(library)) {
      throw new IOException("Нативный компонент macOS отсутствует в дистрибутиве");
    }
    try {
      return Native.load(library.toString(), Api.class);
    } catch (UnsatisfiedLinkError exception) {
      throw new IOException("Не удалось загрузить нативный компонент macOS", exception);
    }
  }

  static byte[] result(Api api, PointerByReference pointer, LongByReference length)
      throws IOException {
    return result(api, pointer, length, 1024 * 1024);
  }

  static byte[] result(Api api, PointerByReference pointer, LongByReference length, int limit)
      throws IOException {
    long count = length.getValue();
    Pointer value = pointer.getValue();
    try {
      if (value == null || count < 0 || count > limit) {
        throw new IOException("Недопустимый ответ нативного компонента");
      }
      return value.getByteArray(0, (int) count);
    } finally {
      if (value != null) {
        api.wr_free_secret(value, Math.max(0, Math.min(count, limit)));
      }
    }
  }

  static void check(int status, String operation) throws IOException {
    if (status != 0) {
      throw new IOException(operation + " (код ОС " + status + ")");
    }
  }
}
