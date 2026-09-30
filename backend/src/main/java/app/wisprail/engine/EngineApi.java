package app.wisprail.engine;

import com.google.protobuf.CodedInputStream;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Minimal reader for the pinned daemon.StartedService wire contract in sing-box 1.14.2. */
public final class EngineApi implements AutoCloseable {
  private final ManagedChannel channel;

  public EngineApi(int port, String secret) {
    Metadata metadata = new Metadata();
    metadata.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + secret);
    channel =
        ManagedChannelBuilder.forAddress("127.0.0.1", port)
            .usePlaintext()
            .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))
            .build();
  }

  public void checkVersion() throws IOException {
    try {
      byte[] response =
          ClientCalls.blockingUnaryCall(
              channel,
              method("GetVersion", MethodDescriptor.MethodType.UNARY),
              CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS),
              new byte[0]);
      CodedInputStream input = CodedInputStream.newInstance(response);
      while (!input.isAtEnd()) {
        int tag = input.readTag();
        if (tag == 10 && SingboxConfig.VERSION.equals(input.readStringRequireUtf8())) {
          return;
        }
        if (tag != 10) {
          input.skipField(tag);
        }
      }
      throw new IOException("Версия API sing-box не соответствует комплектной");
    } catch (StatusRuntimeException exception) {
      throw new IOException("API sing-box ещё не готов", exception);
    }
  }

  public void awaitOpenVpn(BooleanSupplier cancelled) throws IOException, InterruptedException {
    CompletableFuture<Boolean> ready = new CompletableFuture<>();
    var call =
        channel.newCall(
            method("SubscribeOpenVPNStatus", MethodDescriptor.MethodType.SERVER_STREAMING),
            CallOptions.DEFAULT.withDeadlineAfter(45, TimeUnit.SECONDS));
    ClientCalls.asyncServerStreamingCall(
        call,
        new byte[0],
        new StreamObserver<>() {
          @Override
          public void onNext(byte[] response) {
            try {
              CodedInputStream input = CodedInputStream.newInstance(response);
              while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag == 10) {
                  if (endpointReady(input.readByteArray())) {
                    ready.complete(true);
                  }
                } else {
                  input.skipField(tag);
                }
              }
            } catch (IOException exception) {
              ready.completeExceptionally(exception);
            }
          }

          @Override
          public void onError(Throwable failure) {
            ready.completeExceptionally(failure);
          }

          @Override
          public void onCompleted() {
            ready.complete(false);
          }
        });
    try {
      while (!cancelled.getAsBoolean()) {
        try {
          if (ready.get(100, TimeUnit.MILLISECONDS)) {
            return;
          }
          throw new IOException("OpenVPN завершил передачу статуса без готовности");
        } catch (TimeoutException exception) {
          // A short bounded wait keeps cancellation responsive while the engine connects.
        }
      }
      throw new IOException("Попытка подключения отменена");
    } catch (ExecutionException exception) {
      throw new IOException("OpenVPN не подтвердил готовность туннеля", exception);
    } finally {
      call.cancel("Status observation completed", null);
    }
  }

  public void checkOpenVpnReady() throws IOException {
    var call =
        channel.newCall(
            method("SubscribeOpenVPNStatus", MethodDescriptor.MethodType.SERVER_STREAMING),
            CallOptions.DEFAULT.withDeadlineAfter(2, TimeUnit.SECONDS));
    try {
      var responses = ClientCalls.blockingServerStreamingCall(call, new byte[0]);
      if (responses.hasNext()) {
        CodedInputStream input = CodedInputStream.newInstance(responses.next());
        while (!input.isAtEnd()) {
          int tag = input.readTag();
          if (tag == 10) {
            if (endpointReady(input.readByteArray())) {
              return;
            }
          } else {
            input.skipField(tag);
          }
        }
      }
      throw new IOException("OpenVPN больше не подтверждает готовность");
    } catch (StatusRuntimeException exception) {
      throw new IOException("API OpenVPN недоступен", exception);
    } finally {
      call.cancel("Health check completed", null);
    }
  }

  private static boolean endpointReady(byte[] bytes) throws IOException {
    CodedInputStream input = CodedInputStream.newInstance(bytes);
    String endpoint = "";
    String state = "";
    boolean error = false;
    while (!input.isAtEnd()) {
      int tag = input.readTag();
      switch (tag) {
        case 10 -> endpoint = input.readStringRequireUtf8();
        case 18 -> state = input.readStringRequireUtf8();
        case 42 -> error = !input.readStringRequireUtf8().isEmpty();
        default -> input.skipField(tag);
      }
    }
    if (endpoint.equals(SingboxConfig.VPN_TAG) && error) {
      throw new IOException("OpenVPN сообщил об ошибке подключения или авторизации");
    }
    return endpoint.equals(SingboxConfig.VPN_TAG) && state.equals("connected");
  }

  private static MethodDescriptor<byte[], byte[]> method(
      String name, MethodDescriptor.MethodType type) {
    MethodDescriptor.Marshaller<byte[]> marshaller =
        new MethodDescriptor.Marshaller<>() {
          @Override
          public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
          }

          @Override
          public byte[] parse(InputStream stream) {
            try {
              byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
              if (bytes.length > 1024 * 1024) {
                throw new IOException("Слишком большой ответ API");
              }
              return bytes;
            } catch (IOException exception) {
              throw new UncheckedIOException(exception);
            }
          }
        };
    return MethodDescriptor.<byte[], byte[]>newBuilder()
        .setType(type)
        .setFullMethodName("daemon.StartedService/" + name)
        .setRequestMarshaller(marshaller)
        .setResponseMarshaller(marshaller)
        .build();
  }

  @Override
  public void close() {
    channel.shutdownNow();
  }
}
