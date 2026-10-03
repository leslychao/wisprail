package app.wisprail.connection;

import java.io.IOException;

/** An application-authored, safe error that may cross the service/UI boundary. */
public final class NetworkFailure extends IOException {
  private static final long serialVersionUID = 1L;
  private final String code;

  public NetworkFailure(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
