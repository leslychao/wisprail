package app.wisprail.agent;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded framing shared by named pipes and Unix-domain sockets. */
abstract class FramedConnection implements IpcConnection {
  private final String peerIdentity;
  private final Object writeLock = new Object();

  FramedConnection(String peerIdentity) {
    this.peerIdentity = peerIdentity;
  }

  @Override
  public final String peerIdentity() {
    return peerIdentity;
  }

  @Override
  public final byte[] receive() throws IOException {
    ByteBuffer header = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
    readFully(header);
    header.flip();
    int length = header.getInt();
    if (length <= 0 || length > MAX_MESSAGE_BYTES) {
      throw new IOException("Недопустимый размер сообщения службы");
    }
    ByteBuffer body = ByteBuffer.allocate(length);
    readFully(body);
    return body.array();
  }

  @Override
  public final void send(byte[] message) throws IOException {
    if (message.length == 0 || message.length > MAX_MESSAGE_BYTES) {
      throw new IOException("Недопустимый размер сообщения службы");
    }
    synchronized (writeLock) {
      ByteBuffer header = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
      header.putInt(message.length).flip();
      writeFully(header);
      writeFully(ByteBuffer.wrap(message));
    }
  }

  private void readFully(ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) {
      if (read(buffer) < 0) {
        throw new EOFException("Соединение со службой закрыто");
      }
    }
  }

  private void writeFully(ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) {
      write(buffer);
    }
  }

  abstract int read(ByteBuffer buffer) throws IOException;

  abstract void write(ByteBuffer buffer) throws IOException;
}
