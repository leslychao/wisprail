package app.wisprail.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface LocalTransport extends AutoCloseable {
  interface Session extends AutoCloseable {
    InputStream input();

    OutputStream output();

    @Override
    void close() throws IOException;
  }

  Session accept() throws IOException;

  @Override
  void close() throws IOException;
}
