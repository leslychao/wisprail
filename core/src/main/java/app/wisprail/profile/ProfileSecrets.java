package app.wisprail.profile;

import java.util.Objects;

/** Never include this object in diagnostics, profile exports, or event payloads. */
public record ProfileSecrets(
    String password,
    String uuid,
    String ca,
    String certificate,
    String privateKey,
    String controlKey) {
  public ProfileSecrets {
    Objects.requireNonNull(password);
    Objects.requireNonNull(uuid);
    Objects.requireNonNull(ca);
    Objects.requireNonNull(certificate);
    Objects.requireNonNull(privateKey);
    Objects.requireNonNull(controlKey);
  }

  public static ProfileSecrets empty() {
    return new ProfileSecrets("", "", "", "", "", "");
  }

  public ProfileSecrets withPassword(String value) {
    return new ProfileSecrets(value, uuid, ca, certificate, privateKey, controlKey);
  }

  @Override
  public String toString() {
    return "ProfileSecrets[redacted]";
  }
}
