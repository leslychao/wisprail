package app.wisprail.engine;

import com.google.protobuf.Empty;
import daemon.StartedServiceGrpc;
import daemon.StartedServiceOuterClass.OpenVPNStatusUpdate;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class EngineApi implements AutoCloseable {
  private final ManagedChannel channel;
  private final StartedServiceGrpc.StartedServiceBlockingStub blocking;
  private final StartedServiceGrpc.StartedServiceStub async;
  private final Context.CancellableContext subscription = Context.current().withCancellation();
  private final AtomicReference<String> openVpnState = new AtomicReference<>("connecting");
  private final AtomicReference<String> failure = new AtomicReference<>();

  EngineApi(int port, EngineCredentials credentials) throws IOException {
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
        "Bearer " + credentials.token());
    channel =
        NettyChannelBuilder.forAddress("127.0.0.1", port)
            .sslContext(
                GrpcSslContexts.forClient()
                    .trustManager(
                        new ByteArrayInputStream(
                            credentials.certificate().getBytes(StandardCharsets.US_ASCII)))
                    .build())
            .maxInboundMessageSize(256 * 1024)
            .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
            .build();
    blocking = StartedServiceGrpc.newBlockingStub(channel);
    async = StartedServiceGrpc.newStub(channel);
  }

  void verifyVersion(Duration timeout) throws IOException {
    var version =
        blocking
            .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .getVersion(Empty.getDefaultInstance());
    if (!version.getVersion().equals("1.14.2") || version.getApiVersion() != 4) {
      throw new IOException("Версия API движка не соответствует комплектной 1.14.2");
    }
  }

  void subscribeOpenVpn() {
    subscription.run(
        () ->
            async.subscribeOpenVPNStatus(
                Empty.getDefaultInstance(),
                new StreamObserver<>() {
                  @Override
                  public void onNext(OpenVPNStatusUpdate update) {
                    update.getEndpointsList().stream()
                        .filter(endpoint -> endpoint.getEndpointTag().equals(SingboxConfig.VPN_TAG))
                        .findFirst()
                        .ifPresent(endpoint -> openVpnState.set(endpoint.getState()));
                  }

                  @Override
                  public void onError(Throwable error) {
                    if (!subscription.isCancelled()) {
                      failure.set("Потеряна связь с API движка");
                    }
                  }

                  @Override
                  public void onCompleted() {
                    if (!subscription.isCancelled()) {
                      failure.set("Движок завершил передачу состояния");
                    }
                  }
                }));
  }

  String openVpnState() throws IOException {
    String error = failure.get();
    if (error != null) {
      throw new IOException(error);
    }
    return openVpnState.get();
  }

  @Override
  public void close() {
    subscription.cancel(null);
    channel.shutdownNow();
  }
}
