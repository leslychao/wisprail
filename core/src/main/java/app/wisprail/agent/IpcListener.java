package app.wisprail.agent;

import java.io.Closeable;
import java.io.IOException;

public interface IpcListener extends Closeable {
  IpcConnection accept() throws IOException;
}
