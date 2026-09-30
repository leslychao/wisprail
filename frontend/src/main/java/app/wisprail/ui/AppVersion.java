package app.wisprail.ui;

import java.io.IOException;
import java.util.Properties;

final class AppVersion {
  private AppVersion() {}

  static String value() {
    try (var input = AppVersion.class.getResourceAsStream("build.properties")) {
      if (input == null) {
        return "версия не определена";
      }
      Properties properties = new Properties();
      properties.load(input);
      return properties.getProperty("version", "версия не определена");
    } catch (IOException exception) {
      return "версия не определена";
    }
  }
}
