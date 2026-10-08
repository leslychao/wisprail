package app.wisprail.agent;

import java.io.Closeable;
import java.io.IOException;

/** A local channel whose peer has been authenticated by the operating system. */
public interface IpcConnection extends Closeable {
  int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

  String peerIdentity();

  byte[] receive() throws IOException;

  void send(byte[] message) throws IOException;
}
