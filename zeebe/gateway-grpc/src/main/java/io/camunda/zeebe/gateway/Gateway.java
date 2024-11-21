/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.gateway;

import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

import com.google.rpc.Code;
import io.camunda.identity.sdk.IdentityConfiguration;
import io.camunda.zeebe.broker.client.api.BrokerClient;
import io.camunda.zeebe.gateway.health.GatewayHealthManager;
import io.camunda.zeebe.gateway.health.Status;
import io.camunda.zeebe.gateway.health.impl.GatewayHealthManagerImpl;
import io.camunda.zeebe.gateway.impl.configuration.AuthenticationCfg.AuthMode;
import io.camunda.zeebe.gateway.impl.configuration.GatewayCfg;
import io.camunda.zeebe.gateway.impl.configuration.IdentityCfg;
import io.camunda.zeebe.gateway.impl.configuration.MultiTenancyCfg;
import io.camunda.zeebe.gateway.impl.configuration.NetworkCfg;
import io.camunda.zeebe.gateway.impl.configuration.SecurityCfg;
import io.camunda.zeebe.gateway.impl.job.ActivateJobsHandler;
import io.camunda.zeebe.gateway.impl.job.LongPollingActivateJobsHandler;
import io.camunda.zeebe.gateway.impl.job.RoundRobinActivateJobsHandler;
import io.camunda.zeebe.gateway.impl.stream.StreamJobsHandler;
import io.camunda.zeebe.gateway.interceptors.impl.ContextInjectingInterceptor;
import io.camunda.zeebe.gateway.interceptors.impl.DecoratedInterceptor;
import io.camunda.zeebe.gateway.interceptors.impl.IdentityInterceptor;
import io.camunda.zeebe.gateway.interceptors.impl.InterceptorRepository;
import io.camunda.zeebe.gateway.protocol.GatewayOuterClass.ActivateJobsResponse;
import io.camunda.zeebe.gateway.query.impl.QueryApiImpl;
import io.camunda.zeebe.protocol.impl.stream.job.JobActivationProperties;
import io.camunda.zeebe.scheduler.Actor;
import io.camunda.zeebe.scheduler.ActorSchedulingService;
import io.camunda.zeebe.scheduler.future.ActorFuture;
import io.camunda.zeebe.scheduler.future.CompletableActorFuture;
import io.camunda.zeebe.transport.stream.api.ClientStreamer;
import io.camunda.zeebe.util.CloseableSilently;
import io.camunda.zeebe.util.TlsConfigUtil;
import io.camunda.zeebe.util.error.FatalErrorHandler;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.StatusException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import me.dinowernli.grpc.prometheus.Configuration;
import me.dinowernli.grpc.prometheus.MonitoringServerInterceptor;
import org.slf4j.Logger;

public final class Gateway implements CloseableSilently {

  public static final Function<String, Exception> NO_JOBS_RECEIVED_EXCEPTION_PROVIDER =
      msg -> grpcStatusException(Code.RESOURCE_EXHAUSTED_VALUE, msg);
  public static final Function<String, Throwable> REQUEST_CANCELED_EXCEPTION_PROVIDER =
      msg -> grpcStatusException(Code.CANCELLED_VALUE, msg);

  private static final Logger LOG = Loggers.GATEWAY_LOGGER;
  private static final MonitoringServerInterceptor MONITORING_SERVER_INTERCEPTOR =
      MonitoringServerInterceptor.create(Configuration.allMetrics());
  private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

  private final GatewayCfg gatewayCfg;
  private final IdentityConfiguration identityCfg;
  private final ActorSchedulingService actorSchedulingService;
  private final GatewayHealthManager healthManager;
  private final ClientStreamer<JobActivationProperties> jobStreamer;
  private final Duration shutdownTimeout;

  private Server server;
  private ExecutorService grpcExecutor;
  private final BrokerClient brokerClient;

  public Gateway(
      final GatewayCfg gatewayCfg,
      final IdentityConfiguration identityCfg,
      final BrokerClient brokerClient,
      final ActorSchedulingService actorSchedulingService,
      final ClientStreamer<JobActivationProperties> jobStreamer) {
    this(
        DEFAULT_SHUTDOWN_TIMEOUT,
        gatewayCfg,
        identityCfg,
        brokerClient,
        actorSchedulingService,
        jobStreamer);
  }

  public Gateway(
      final GatewayCfg gatewayCfg,
      final BrokerClient brokerClient,
      final ActorSchedulingService actorSchedulingService,
      final ClientStreamer<JobActivationProperties> jobStreamer) {
    this(gatewayCfg, null, brokerClient, actorSchedulingService, jobStreamer);
  }

  public Gateway(
      final Duration shutdownDuration,
      final GatewayCfg gatewayCfg,
      final IdentityConfiguration identityCfg,
      final BrokerClient brokerClient,
      final ActorSchedulingService actorSchedulingService,
      final ClientStreamer<JobActivationProperties> jobStreamer) {
    shutdownTimeout = shutdownDuration;
    this.gatewayCfg = gatewayCfg;
    this.identityCfg = identityCfg;
    this.brokerClient = brokerClient;
    this.actorSchedulingService = actorSchedulingService;
    this.jobStreamer = jobStreamer;

    healthManager = new GatewayHealthManagerImpl();
  }

  public GatewayCfg getGatewayCfg() {
    return gatewayCfg;
  }

  public Status getStatus() {
    return healthManager.getStatus();
  }

  public BrokerClient getBrokerClient() {
    return brokerClient;
  }

  public ActorFuture<Gateway> start() {
    final var resultFuture = new CompletableActorFuture<Gateway>();
    healthManager.setStatus(Status.STARTING);

    createAndStartActivateJobsHandler(brokerClient)
        .thenCombine(startClientStreamAdapter(), this::createServer)
        .thenAccept(this::startServer)
        .thenApply(ok -> this)
        .whenComplete(resultFuture);

    return resultFuture;
  }

  private void startServer(final Server server) {
    this.server = server;

    try {
      this.server.start();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    healthManager.setStatus(Status.RUNNING);
  }

  private CompletionStage<StreamJobsHandler> startClientStreamAdapter() {
    final var adapter = new StreamJobsHandler(jobStreamer);
    final var future = new CompletableFuture<StreamJobsHandler>();

    actorSchedulingService
        .submitActor(adapter)
        .onComplete(
            (ok, error) -> {
              if (error != null) {
                future.completeExceptionally(error);
                return;
              }

              future.complete(adapter);
            },
            ForkJoinPool.commonPool());

    return future;
  }

  private Server createServer(
      final ActivateJobsHandler<ActivateJobsResponse> activateJobsHandler,
      final StreamJobsHandler streamJobsHandler) {
    final NetworkCfg network = gatewayCfg.getNetwork();
    final MultiTenancyCfg multiTenancy = gatewayCfg.getMultiTenancy();

    final var serverBuilder = applyNetworkConfig(network);
    applyExecutorConfiguration(serverBuilder);
    applySecurityConfiguration(serverBuilder);

    final var endpointManager =
        new EndpointManager(brokerClient, activateJobsHandler, streamJobsHandler, multiTenancy);
    final var gatewayGrpcService = new GatewayGrpcService(endpointManager);
    return buildServer(serverBuilder, gatewayGrpcService);
  }

  private void applyExecutorConfiguration(final NettyServerBuilder builder) {
    final var config = gatewayCfg.getThreads();

    // the default boss and worker event loop groups defined by the library are good enough; they
    // will appropriately select epoll or nio based on availability, and the boss loop gets 1
    // thread, while the worker gets the number of cores

    // by default will start 1 thread per core; however, fork join pools may start more threads when
    // blocked on tasks, and here up to 2 threads per core.
    final ThreadFactory factory =
        Thread.ofVirtual()
            .uncaughtExceptionHandler(FatalErrorHandler.uncaughtExceptionHandler(LOG))
            .name("grpc-virtual-executor")
            .factory();
    grpcExecutor = newThreadPerTaskExecutor(factory);

    builder.executor(grpcExecutor);
  }

  private void applySecurityConfiguration(final NettyServerBuilder serverBuilder) {
    final SecurityCfg securityCfg = gatewayCfg.getSecurity();
    if (securityCfg.isEnabled()) {
      setSecurityConfig(serverBuilder, securityCfg);
    }
  }

  private Server buildServer(
      final ServerBuilder<?> serverBuilder, final BindableService interceptorService) {
    return serverBuilder
        .addService(applyInterceptors(interceptorService))
        .addService(
            ServerInterceptors.intercept(
                healthManager.getHealthService(), MONITORING_SERVER_INTERCEPTOR))
        .build();
  }

  private NettyServerBuilder applyNetworkConfig(final NetworkCfg cfg) {
    final Duration minKeepAliveInterval = cfg.getMinKeepAliveInterval();

    if (minKeepAliveInterval.isNegative() || minKeepAliveInterval.isZero()) {
      throw new IllegalArgumentException("Minimum keep alive interval must be positive.");
    }

    final var maxMessageSize = (int) cfg.getMaxMessageSize().toBytes();
    if (maxMessageSize <= 0) {
      throw new IllegalArgumentException("maxMessageSize must be positive");
    }

    return NettyServerBuilder.forAddress(new InetSocketAddress(cfg.getHost(), cfg.getPort()))
        .maxInboundMessageSize(maxMessageSize)
        .permitKeepAliveTime(minKeepAliveInterval.toMillis(), TimeUnit.MILLISECONDS)
        .permitKeepAliveWithoutCalls(false)
        .withOption(ChannelOption.SO_RCVBUF, (int) cfg.getSoRcvbuf().toBytes())
        .withOption(ChannelOption.SO_SNDBUF, (int) cfg.getSoSndbuf().toBytes());
  }

  private void setSecurityConfig(
      final NettyServerBuilder serverBuilder, final SecurityCfg security) {
    final var certificateChainPath = security.getCertificateChainPath();
    final var privateKeyPath = security.getPrivateKeyPath();
    final var keyStorePath = security.getKeyStore().getFilePath();
    final var keyStorePassword = security.getKeyStore().getPassword();

    TlsConfigUtil.validateTlsConfig(certificateChainPath, privateKeyPath, keyStorePath);

    try {
      final SslContextBuilder sslContextBuilder;
      if (keyStorePath != null) {
        final var privateKey = TlsConfigUtil.getPrivateKey(keyStorePath, keyStorePassword);
        final var certChain = TlsConfigUtil.getCertificateChain(keyStorePath, keyStorePassword);
        sslContextBuilder = SslContextBuilder.forServer(privateKey, certChain);
      } else {
        sslContextBuilder = SslContextBuilder.forServer(certificateChainPath, privateKeyPath);
      }
      final var sslContext = GrpcSslContexts.configure(sslContextBuilder).build();
      serverBuilder.sslContext(sslContext);
    } catch (final Exception e) {
      throw new IllegalArgumentException(
          "Failed to start messaging service; invalid server TLS configuration", e);
    }
  }

  @Override
  public void close() {
    healthManager.setStatus(Status.SHUTDOWN);

    if (server != null && !server.isShutdown()) {
      server.shutdown();
      try {
        LOG.debug("Waiting {} for server to shut down cleanly", shutdownTimeout);
        final var cleanTermination =
            server.awaitTermination(shutdownTimeout.getSeconds(), TimeUnit.SECONDS);
        if (!cleanTermination) {
          LOG.warn("Server did not shut down cleanly within {}", shutdownTimeout);
          server.shutdownNow();
          server.awaitTermination();
        }
      } catch (final InterruptedException e) {
        LOG.warn("Failed to await termination of gRPC server", e);
        Thread.currentThread().interrupt();
      } finally {
        server = null;
      }
    }

    if (grpcExecutor != null) {
      grpcExecutor.shutdownNow();
      try {
        //noinspection ResultOfMethodCallIgnored
        grpcExecutor.awaitTermination(10, TimeUnit.SECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        grpcExecutor = null;
      }
    }
  }

  private CompletableFuture<ActivateJobsHandler<ActivateJobsResponse>>
      createAndStartActivateJobsHandler(final BrokerClient brokerClient) {
    final var handler = buildActivateJobsHandler(brokerClient);
    return submitActorToActivateJobs(handler);
  }

  private CompletableFuture<ActivateJobsHandler<ActivateJobsResponse>> submitActorToActivateJobs(
      final ActivateJobsHandler<ActivateJobsResponse> handler) {
    final var future = new CompletableFuture<ActivateJobsHandler<ActivateJobsResponse>>();
    final var actor =
        Actor.newActor()
            .name("ActivateJobsHandler")
            .actorStartedHandler(handler.andThen(t -> future.complete(handler)))
            .build();
    actorSchedulingService.submitActor(actor);
    return future;
  }

  private ActivateJobsHandler<ActivateJobsResponse> buildActivateJobsHandler(
      final BrokerClient brokerClient) {
    if (gatewayCfg.getLongPolling().isEnabled()) {
      return buildLongPollingHandler(brokerClient);
    } else {
      return new RoundRobinActivateJobsHandler<>(
          brokerClient,
          gatewayCfg.getNetwork().getMaxMessageSize().toBytes(),
          ResponseMapper::toActivateJobsResponse,
          REQUEST_CANCELED_EXCEPTION_PROVIDER);
    }
  }

  private LongPollingActivateJobsHandler<ActivateJobsResponse> buildLongPollingHandler(
      final BrokerClient brokerClient) {
    return LongPollingActivateJobsHandler.<ActivateJobsResponse>newBuilder()
        .setBrokerClient(brokerClient)
        .setMaxMessageSize(gatewayCfg.getNetwork().getMaxMessageSize().toBytes())
        .setLongPollingTimeout(gatewayCfg.getLongPolling().getTimeout())
        .setProbeTimeoutMillis(gatewayCfg.getLongPolling().getProbeTimeout())
        .setMinEmptyResponses(gatewayCfg.getLongPolling().getMinEmptyResponses())
        .setActivationResultMapper(ResponseMapper::toActivateJobsResponse)
        .setNoJobsReceivedExceptionProvider(NO_JOBS_RECEIVED_EXCEPTION_PROVIDER)
        .setRequestCanceledExceptionProvider(REQUEST_CANCELED_EXCEPTION_PROVIDER)
        .build();
  }

  private ServerServiceDefinition applyInterceptors(final BindableService service) {
    final var repository = new InterceptorRepository().load(gatewayCfg.getInterceptors());
    final var queryApi = new QueryApiImpl(brokerClient);
    final List<ServerInterceptor> interceptors =
        repository.instantiate().map(DecoratedInterceptor::decorate).collect(Collectors.toList());

    // reverse the user interceptors, such that they will be called in the order in which they are
    // configured, such that the first configured interceptor is the outermost interceptor in the
    // chain
    Collections.reverse(interceptors);
    interceptors.add(new ContextInjectingInterceptor(queryApi));
    interceptors.add(MONITORING_SERVER_INTERCEPTOR);
    if (AuthMode.IDENTITY == gatewayCfg.getSecurity().getAuthentication().getMode()) {
      final var zeebeIdentityCfg = gatewayCfg.getSecurity().getAuthentication().getIdentity();
      if (isZeebeIdentityConfigurationNotNull(zeebeIdentityCfg)) {
        interceptors.add(new IdentityInterceptor(zeebeIdentityCfg, gatewayCfg.getMultiTenancy()));
        LOG.warn(
            "These Zeebe configuration properties for Camunda Identity are deprecated! Please use the "
                + "corresponding Camunda Identity properties or the environment variables defined here: "
                + "https://docs.camunda.io/docs/self-managed/identity/deployment/configuration-variables/");
      } else {
        interceptors.add(new IdentityInterceptor(identityCfg, gatewayCfg.getMultiTenancy()));
      }
    }

    return ServerInterceptors.intercept(service, interceptors);
  }

  private boolean isZeebeIdentityConfigurationNotNull(final IdentityCfg identityCfg) {
    return identityCfg.getIssuerBackendUrl() != null || identityCfg.getBaseUrl() != null;
  }

  private static StatusException grpcStatusException(final int code, final String msg) {
    return StatusProto.toStatusException(
        com.google.rpc.Status.newBuilder().setCode(code).setMessage(msg).build());
  }

  private static final class NamedForkJoinPoolThreadFactory implements ForkJoinWorkerThreadFactory {
    @Override
    public ForkJoinWorkerThread newThread(final ForkJoinPool pool) {
      final var worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
      worker.setName("grpc-executor-" + worker.getPoolIndex());
      return worker;
    }
  }
}
