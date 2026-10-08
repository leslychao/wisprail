package app.wisprail.profile;

import java.util.Objects;

/** Secret values travel only over authenticated IPC and never in snapshots or diagnostics. */
public record ProfileSecrets(
    String password,
    String privateKey,
    String privateKeyPassword,
    String tlsAuthKey,
    String tlsCryptKey,
    String uuid) {

  public ProfileSecrets {
    Objects.requireNonNull(password);
    Objects.requireNonNull(privateKey);
    Objects.requireNonNull(privateKeyPassword);
    Objects.requireNonNull(tlsAuthKey);
    Objects.requireNonNull(tlsCryptKey);
    Objects.requireNonNull(uuid);
  }

  public static ProfileSecrets empty() {
    return new ProfileSecrets("", "", "", "", "", "");
  }

  public boolean isEmpty() {
    return equals(empty());
  }

  @Override
  public String toString() {
    return "ProfileSecrets[REDACTED]";
  }
}
