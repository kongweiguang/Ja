// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.foundation.error.FailureDiagnostics;
import io.github.kongweiguang.ja.foundation.runtime.ProductVersion;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeHandler;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcServer;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocketListener;
import org.noear.solon.SimpleSolonApp;
import org.noear.solon.core.event.EventBus;
import org.noear.solon.core.event.EventListener;
import org.noear.solon.core.runtime.NativeDetector;
import org.noear.solon.net.websocket.WebSocket;
import org.noear.solon.net.websocket.WebSocketRouter;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import org.noear.solon.server.feathttp.integration.FeatHttpPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.smartboot.feat.core.server.ServerOptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * 一个进程级 JA-RPC owner 对应多个受认证的本地 WebSocket 连接；断线只撤销传输。
 *
 * <p>内部 RpcServer 的输入和 Writer 始终被事件泵排空，避免把任意一个前端连接的寿命作为
 * Turn、审批或数据库租约的寿命。连接只持有请求 ID 映射和有界出站队列。</p>
 */
public final class WebSocketDaemon implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketDaemon.class);
    private static final int PROTOCOL_MAJOR = 2;
    private static final String RPC_PATH = "/ja-rpc";
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private static final int MAX_CLIENTS = 16;
    private static final int MAX_PENDING = 64;
    private static final int MAX_AUTH_FRAME = 256;
    private static final int CLIENT_QUEUE_FRAMES = 64;
    private static final int CLIENT_QUEUE_BYTES = 8 * 1024 * 1024;
    private static final int INGRESS_QUEUE_BYTES = 16 * 1024 * 1024;
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration PING_INTERVAL = Duration.ofMinutes(1);
    private static final String CLIENT_CLOSE_LOG_TEMPLATE =
        "Ja WebSocket client closed category=websocket_client_close phase={} "
            + "textMessageSequence={} failureType={} origin={}";
    private static final Set<String> GLOBAL_NOTIFICATIONS = Set.of("runtime/status-changed",
        "configuration/changed", "workspace/dirty", "workspace/metadata-changed", "thread/metadata-changed");
    private static final List<String> THREAD_ID_FIELDS = List.of("threadId", "ownerThreadId",
        "rootThreadId", "taskThreadId");
    private final SidecarConfiguration configuration;
    private final Duration idleTimeout;
    private final Duration pingInterval;
    private final Duration connectionIdleTimeout;
    private final ObjectMapper mapper = new JaRpcCodec().mapper();
    private final JaRpcCodec codec = new JaRpcCodec();
    private final PipedInputStream runtimeInput;
    private final PipedOutputStream runtimeIngress;
    private final PipedInputStream runtimeOutput;
    private final PipedOutputStream runtimeEgress;
    private final RpcServer runtime;
    private final byte[] token = new byte[32];
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Semaphore connectionSlots = new Semaphore(MAX_CLIENTS);
    private final AtomicLong nextRequest = new AtomicLong();
    private final AtomicLong emptySinceNanos = new AtomicLong();
    private final AtomicLong ingressQueuedBytes = new AtomicLong();
    private final ArrayBlockingQueue<byte[]> ingressQueue = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean transportClosed = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicReference<String> stoppingRequestId = new AtomicReference<>();
    private final Object requestGate = new Object();
    private final CompletableFuture<ObjectNode> initialized = new CompletableFuture<>();
    private final CompletableFuture<ObjectNode> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> listenerReady = new CompletableFuture<>();
    private final CompletableFuture<Integer> runtimeExit = new CompletableFuture<>();
    private final CompletableFuture<Void> outputPumpStopped = new CompletableFuture<>();
    private final AtomicReference<Throwable> transportFailure = new AtomicReference<>();
    private final Path endpointPath;
    private final IntSupplier portCandidates;
    private volatile int port;
    private final ConnectionListener listener = new ConnectionListener();
    private SimpleSolonApp transportApp;
    private final ScheduledExecutorService authDeadlines = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("ja-ws-auth-deadline").factory());
    private final Set<Client> connections = ConcurrentHashMap.newKeySet();
    private final Object connectionGate = new Object();
    private volatile RpcSession ownerSession;
    private volatile String instanceId;

    /**
     * 管道容量只用于内部帧交接；对外 socket 无法把 Writer owner 阻塞在慢客户端上。
     */
    public WebSocketDaemon(SidecarConfiguration configuration, RpcServicesFactory factory,
                           ConfigurationUseCase configurationUseCase, RpcServer.SessionBinding binding) throws IOException {
        this(configuration, factory, configurationUseCase, binding, IDLE_TIMEOUT);
    }

    /**
     * 测试可收窄空闲期限；生产固定五分钟，业务保活仍由 runtime/shutdown 裁决。
     */
    WebSocketDaemon(SidecarConfiguration configuration, RpcServicesFactory factory,
                    ConfigurationUseCase configurationUseCase, RpcServer.SessionBinding binding,
                    Duration idleTimeout) throws IOException {
        this(configuration, factory, configurationUseCase, binding, idleTimeout, PING_INTERVAL);
    }

    /**
     * 测试可压缩 Ping 周期验证框架空闲关闭边界；生产周期固定小于 FeatHTTP 默认五分钟。
     */
    WebSocketDaemon(SidecarConfiguration configuration, RpcServicesFactory factory,
              ConfigurationUseCase configurationUseCase, RpcServer.SessionBinding binding,
              Duration idleTimeout, Duration pingInterval) throws IOException {
        this(configuration, factory, configurationUseCase, binding, idleTimeout, pingInterval, IDLE_TIMEOUT);
    }

    /**
     * 连接空闲期限由传输 owner 冻结，测试可收窄期限；恢复业务容器不能重置或撤销它。
     */
    WebSocketDaemon(SidecarConfiguration configuration, RpcServicesFactory factory,
                    ConfigurationUseCase configurationUseCase, RpcServer.SessionBinding binding,
                    Duration idleTimeout, Duration pingInterval, Duration connectionIdleTimeout) throws IOException {
        this(configuration, factory, configurationUseCase, binding, idleTimeout, pingInterval,
            connectionIdleTimeout, configuration.listenPort() == 0
                ? () -> 20000 + TOKEN_RANDOM.nextInt(20000)
                : configuration::listenPort);
    }

    /**
     * 注入候选端口仅供冲突测试；生产端口仍由 FeatHTTP 自身完成绑定裁决。
     * 内部输出管道的所有权转移给唯一 Writer，以在线程退出前发布物理 EOF；不借用宿主 stdout。
     */
    WebSocketDaemon(SidecarConfiguration configuration, RpcServicesFactory factory,
                    ConfigurationUseCase configurationUseCase, RpcServer.SessionBinding binding,
                    Duration idleTimeout, Duration pingInterval, Duration connectionIdleTimeout,
                    IntSupplier portCandidates) throws IOException {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.portCandidates = Objects.requireNonNull(portCandidates, "portCandidates");
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.pingInterval = Objects.requireNonNull(pingInterval, "pingInterval");
        this.connectionIdleTimeout = Objects.requireNonNull(connectionIdleTimeout, "connectionIdleTimeout");
        if (idleTimeout.isZero() || idleTimeout.isNegative() || idleTimeout.compareTo(IDLE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("invalid WebSocket idle timeout");
        }
        if (pingInterval.isZero() || pingInterval.isNegative()) {
            throw new IllegalArgumentException("invalid WebSocket ping interval");
        }
        if (connectionIdleTimeout.isZero() || connectionIdleTimeout.isNegative()
            || connectionIdleTimeout.compareTo(IDLE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("invalid WebSocket connection idle timeout");
        }
        this.endpointPath = configuration.runDirectory().resolve("app-server.endpoint.json");
        this.runtimeInput = new PipedInputStream(1024 * 1024);
        this.runtimeIngress = new PipedOutputStream(runtimeInput);
        this.runtimeOutput = new PipedInputStream(1024 * 1024);
        this.runtimeEgress = new PipedOutputStream(runtimeOutput);
        this.runtime = new RpcServer(runtimeInput, runtimeEgress, configuration, factory,
            configurationUseCase, session -> {
            ownerSession = session;
            return binding.bind(session);
        }, true);
        TOKEN_RANDOM.nextBytes(token);
    }

    /**
     * 只接受一个冻结版本的握手结果；失败不得把旧端点发布给客户端。
     */
    private static ObjectNode await(CompletableFuture<ObjectNode> completion) throws IOException {
        try {
            return completion.get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            throw new IOException("Ja runtime did not complete local startup", failure);
        }
    }

    /**
     * Windows 当前 owner 已匹配时避免无意义的 setOwner 特权调用，DACL 仍必须独占当前用户。
     */
    private static void protectCurrentUser(Path file) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            String currentUser = ProcessHandle.current().info().user()
                .orElseThrow(() -> new IOException("Ja current user is unavailable"));
            UserPrincipal principal = file.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(currentUser);
            if (!acl.getOwner().equals(principal)) {
                acl.setOwner(principal);
            }
            AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                .setPermissions(AclEntryPermission.values()).build();
            acl.setAcl(List.of(entry));
            if (!acl.getOwner().equals(principal) || !acl.getAcl().equals(List.of(entry))) {
                throw new IOException("Ja endpoint ACL is not private");
            }
            if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            }
            return;
        }
        if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
            return;
        }
        throw new IOException("Ja endpoint file permissions are unsupported");
    }

    /**
     * 独立监听与业务容器分开关闭，存储装配失败后仍可接入显式恢复；端点只在绑定和握手成功后发布。
     */
    @SuppressWarnings("PMD.CloseResource")
    public int run() throws IOException {
        // 共享后台无权把最早启动客户端的环境当作随后所有 Turn 的默认环境。
        NativeExecutionContext.shared().enableSharedMode();
        startTransport();
        Thread.ofVirtual().name("ja-ws-output-pump").start(this::pumpOutput);
        Thread.ofVirtual().name("ja-ws-owner-ingress").start(this::pumpIngress);
        Thread.ofVirtual().name("ja-ws-rpc-owner").start(() -> {
            try {
                runtimeExit.complete(runtime.run());
            } catch (Throwable failure) {
                runtimeExit.completeExceptionally(failure);
                failTransport(failure);
            }
        });
        initializeRuntime();
        try {
            verifyListener();
            publishEndpoint(port);
            emptySinceNanos.set(System.nanoTime());
            while (!runtimeExit.isDone() && transportFailure.get() == null) {
                requestIdleShutdownIfSafe(ownerSession);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Ja WebSocket daemon interrupted", interrupted);
                }
            }
            if (transportFailure.get() == null && runtimeExit.getNow(1) == 0) {
                try {
                    runtime.shutdownDeadline().await(outputPumpStopped, "websocket runtime output");
                } catch (RuntimeException failure) {
                    failTransport(failure);
                }
            }
            return transportFailure.get() == null ? runtimeExit.getNow(1) : 1;
        } finally {
            removeOwnedEndpoint();
        }
    }

    /** FeatHTTP 冻结监听地址与 RPC 路径；消息升级适配不改变 HTTP 大小或业务连接生命周期。 */
    private void startTransport() throws IOException {
        IOException lastBindFailure = null;
        int attempts = configuration.listenPort() == 0 ? 32 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            int candidate = portCandidates.getAsInt();
            if (candidate < 1 || candidate > 65535) {
                throw new IOException("Invalid FeatHTTP port candidate");
            }
            String[] transportArgs = {"--server.host=" + configuration.listenHost(), "--server.port=" + candidate,
                "--server.http.idleTimeout=" + connectionIdleTimeout.toMillis()};
            SimpleSolonApp opened = null;
            EventListener<ServerOptions> limits = new EventListener<>() {
                /** 配置仅作用于此监听器，业务容器重启不改变已升级连接的上限。 */
                @Override
                public void onEvent(ServerOptions options) {
                    options.setMaxRequestSize(16 * 1024);
                    options.addPlugin(new WebSocketFrameLimitPlugin(RPC_PATH));
                }
            };
            try {
                opened = new SimpleSolonApp(WebSocketTransportApp.class, transportArgs);
                opened.start(started -> {
                    started.enableWebSocket(true);
                    started.enableScanning(false);
                    // AOT 只索引主 App；传输容器显式拥有 FeatHTTP，排除 JVM 自动扫描的重复实例。
                    started.pluginExclude(FeatHttpPlugin.class);
                    started.pluginAdd(18, new FeatHttpPlugin());
                    started.onEvent(ServerOptions.class, limits);
                    WebSocketRouter.getInstance().of(RPC_PATH, listener);
                });
                if (!opened.enableHttp() || !opened.enableWebSocket()) {
                    LOG.error("Solon FeatHTTP listener disabled http={} websocket={} aotRuntime={}",
                        opened.enableHttp(), opened.enableWebSocket(), NativeDetector.isAotRuntime());
                    throw new IOException("Solon FeatHTTP listener is disabled");
                }
                if (FeatHttpPlugin.signal() == null || FeatHttpPlugin.signal().port() != candidate) {
                    throw new IOException("Solon FeatHTTP did not bind the requested port");
                }
                transportApp = opened;
                port = candidate;
                return;
            } catch (Throwable failure) {
                if (opened != null) {
                    try {
                        opened.stop();
                    } catch (Throwable cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                if (!isBindConflict(failure)) {
                    throw new IOException("Solon FeatHTTP transport failed to start", failure);
                }
                lastBindFailure = new IOException("Solon FeatHTTP port could not bind", failure);
            } finally {
                EventBus.unsubscribe(limits);
            }
        }
        if (configuration.listenPort() != 0) {
            LOG.error("Ja WebSocket listener could not bind; check --server.host and --server.port, or use their defaults");
            throw new IOException("Solon FeatHTTP could not bind " + configuration.listenHost()
                + ":" + configuration.listenPort(), lastBindFailure);
        }
        throw new IOException("No local WebSocket port is available", lastBindFailure);
    }

    /** FeatHTTP 包装启动异常；只沿 cause 链识别绑定冲突，其他故障立即暴露。 */
    private static boolean isBindConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BindException) {
                return true;
            }
        }
        return false;
    }

    /** 业务上下文切换时复用同一个连接回调，不让可重试容器持有 socket 或 Client。 */
    SimpleWebSocketListener listener() {
        return listener;
    }

    /** 发布端点前用实际可连接地址完成真实升级；通配绑定由本机环回地址探测。 */
    private void verifyListener() throws IOException {
        OkHttpClient probeClient = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(2)).build();
        CompletableFuture<Void> upgraded = new CompletableFuture<>();
        okhttp3.WebSocket probe = probeClient.newWebSocket(new Request.Builder()
            .url("ws://" + endpointHost() + ":" + port + RPC_PATH).build(), new WebSocketListener() {
                /** 成功回调证明同一 FeatHTTP 监听器已经完成 HTTP Upgrade。 */
                @Override
                public void onOpen(okhttp3.WebSocket socket, Response response) {
                    upgraded.complete(null);
                }

                /** 升级失败由等待启动的线程统一转换为 IOException。 */
                @Override
                public void onFailure(okhttp3.WebSocket socket, Throwable failure, Response response) {
                    upgraded.completeExceptionally(failure);
                }
            });
        try {
            upgraded.get(2, TimeUnit.SECONDS);
            try {
                listenerReady.get(2, TimeUnit.SECONDS);
            } finally {
                probe.close(1000, "");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Solon WebSocket readiness was interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            probe.cancel();
            throw new IOException("Solon WebSocket endpoint is unavailable", failure);
        } finally {
            probeClient.dispatcher().executorService().shutdown();
            probeClient.connectionPool().evictAll();
        }
    }

    /**
     * 无连接达到期限后请求同一服务端裁决；RpcSession 归 RpcServer 关闭，本方法只借用其保活状态。
     * @author kongweiguang
     */
    private void requestIdleShutdownIfSafe(RpcSession session) throws IOException {
        if (!clients.isEmpty()) {
            emptySinceNanos.set(0);
            return;
        }
        long since = emptySinceNanos.updateAndGet(current -> current == 0 ? System.nanoTime() : current);
        if (System.nanoTime() - since < idleTimeout.toNanos()) {
            return;
        }
        if (session != null && session.servicesAvailable() && session.tasks().hasLiveNativeClientWork()) {
            emptySinceNanos.set(System.nanoTime());
            return;
        }
        emptySinceNanos.set(System.nanoTime());
        String id = "c:daemon_idle_" + nextRequest.incrementAndGet();
        synchronized (requestGate) {
            if (!pending.isEmpty() || stopRequested.get()) {
                return;
            }
            stopRequested.set(true);
            stoppingRequestId.set(id);
            try {
                sendInternal(request(id, "runtime/shutdown", mapper.createObjectNode()));
            } catch (IOException failure) {
                stopRequested.set(false);
                stoppingRequestId.compareAndSet(id, null);
                throw failure;
            }
        }
    }

    /**
     * 内部握手预先完成，让后续每个连接只协商传输身份而不重复打开 Java 服务图。
     */
    private void initializeRuntime() throws IOException {
        ObjectNode params = mapper.createObjectNode()
            .put("protocolMajor", PROTOCOL_MAJOR)
            .put("protocolMinor", 0)
            .put("clientVersion", ProductVersion.current());
        params.set("capabilities", HandshakeHandler.capabilities(mapper));
        params.set("limits", HandshakeHandler.limits(mapper));
        sendInternal(request("c:daemon_init", "runtime/initialize", params));
        ObjectNode result = await(initialized);
        JsonNode id = result.get("serverInstanceId");
        if (id == null || !id.isTextual()) {
            throw new IOException("Ja runtime identity is unavailable");
        }
        instanceId = id.textValue();
        sendInternal(notification("runtime/initialized", mapper.createObjectNode()
            .put("readyToken", "00000000000000000000000000000000")));
        await(ready);
    }

    /**
     * 有界入站队列把客户端寿命与 Java Pipe 的写线程寿命分开；JDK Pipe 会在最后
     * 一次写入者线程退出后报告 write end dead，因此所有实际写入必须由常驻线程执行。
     */
    private void sendInternal(ObjectNode frame) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(frame);
        long queued = ingressQueuedBytes.addAndGet(bytes.length);
        if (transportClosed.get() || transportFailure.get() != null
            || queued > INGRESS_QUEUE_BYTES || !ingressQueue.offer(bytes)) {
            ingressQueuedBytes.addAndGet(-bytes.length);
            throw new IOException("Ja runtime ingress capacity is exhausted");
        }
    }

    /**
     * 唯一长寿命 Pipe 写线程维持请求帧完整性，并持续排空有界队列。
     */
    private void pumpIngress() {
        try {
            while (!transportClosed.get()) {
                byte[] frame = ingressQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    continue;
                }
                ingressQueuedBytes.addAndGet(-frame.length);
                runtimeIngress.write(frame);
                runtimeIngress.write('\n');
                runtimeIngress.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            initialized.completeExceptionally(new IOException("Ja runtime ingress failed"));
        } finally {
            try {
                runtimeIngress.close();
            } catch (IOException ignored) { /* wake owner */ }
        }
    }

    /**
     * 持续排空唯一 Writer，并按内部请求标识将响应送回正确连接。
     * EOF 和异常关闭使用不同 phase；正常关机使用 RPC 的剩余预算排空 WebSocket 尾帧，避免丢失关闭确认。
     * 连接断开不映射为任务取消，真实输出故障仍由 transportFailure 传播。
     *
     * @author kongweiguang
     */
    private void pumpOutput() {
        boolean orderlyEof = false;
        try {
            while (true) {
                Optional<JaRpcCodec.Frame> next = codec.read(runtimeOutput);
                if (next.isEmpty()) {
                    orderlyEof = true;
                    break;
                }
                switch (next.orElseThrow()) {
                    case JaRpcCodec.Response response -> routeResponse(response);
                    case JaRpcCodec.Notification event -> routeEvent(event);
                    case JaRpcCodec.Request ignored -> throw new IOException("unexpected runtime request");
                }
            }
        } catch (IOException | RuntimeException failure) {
            initialized.completeExceptionally(failure);
            ready.completeExceptionally(failure);
            failTransport(failure);
        } finally {
            if (orderlyEof && !runtimeExit.isDone() && !stopRequested.get()
                && !transportClosed.get()) {
                failTransport(new IOException("Ja runtime output ended before owner stopped"));
            }
            try {
                if (orderlyEof && stopRequested.get() && !transportClosed.get()
                    && transportFailure.get() == null) {
                    ShutdownDeadline deadline = runtime.shutdownDeadline();
                    for (Client client : List.copyOf(connections)) {
                        client.awaitOutbound(deadline);
                    }
                }
            } catch (RuntimeException failure) {
                failTransport(failure);
            } finally {
                connections.forEach(client -> client.close(ClientClosePhase.RUNTIME_OUTPUT_END, null));
                outputPumpStopped.complete(null);
            }
        }
    }

    /**
     * 输出泵不可用时立刻撤销可发现端点并唤醒 RPC 读取循环；
     * 新连接不能在已经失去响应通道的实例上无限等待 initialize。关闭原因只记录安全摘要。
     *
     * @author kongweiguang
     */
    private void failTransport(Throwable failure) {
        List<Client> active;
        synchronized (connectionGate) {
            if (!transportFailure.compareAndSet(null, Objects.requireNonNull(failure, "failure"))) {
                return;
            }
            active = List.copyOf(connections);
        }
        initialized.completeExceptionally(failure);
        ready.completeExceptionally(failure);
        removeOwnedEndpoint();
        active.forEach(client -> client.close(ClientClosePhase.RUNTIME_TRANSPORT_FAILURE, failure));
        try {
            runtimeIngress.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * 内部 ID 从不泄露；断开连接后的迟到响应直接丢弃，避免重试造成第二次副作用。
     */
    private void routeResponse(JaRpcCodec.Response response) {
        if ("c:daemon_init".equals(response.id())) {
            if (response.result() != null) {
                initialized.complete(response.result());
            } else {
                initialized.completeExceptionally(new IOException("Ja runtime rejected initialization"));
            }
            return;
        }
        String stoppingId = stoppingRequestId.get();
        if (stoppingId != null && stoppingId.equals(response.id())
            && stoppingRequestId.compareAndSet(stoppingId, null) && response.error() != null) {
            stopRequested.set(false);
        }
        if (response.id().startsWith("c:daemon_idle_")) {
            return;
        }
        NativeExecutionContext.shared().unbindRpcRequest(response.id());
        Pending target = pending.remove(response.id());
        if (target == null) {
            return;
        }
        if (response.error() != null && "thread/observe".equals(target.method())) {
            target.client().observedThreads.remove(target.threadId());
        }
        if (response.error() != null && "thread/unobserve".equals(target.method())) {
            target.client().observedThreads.add(target.threadId());
        }
        if (target.observation() != null) {
            target.client().settleObservation(target.observation(), response);
        }
        if (target.client().closed) {
            return;
        }
        ObjectNode frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", target.clientId());
        if (response.result() != null) {
            frame.set("result", response.result());
        } else {
            frame.set("error", response.error());
        }
        target.client().offer(frame);
    }

    /**
     * 进程事件只编码一次；每个慢读者拥有独立预算，不能背压 Agent 线程。
     */
    private void routeEvent(JaRpcCodec.Notification event) throws IOException {
        ObjectNode params = event.params();
        if ("runtime/status-changed".equals(event.method())
            && "ready".equals(params.path("status").asText())) {
            ready.complete(params);
        }
        ObjectNode frame = notification(event.method(), params);
        byte[] encoded = mapper.writeValueAsBytes(frame);
        for (Client client : clients) {
            if (client.ready && client.accepts(event.method(), params)) {
                client.offerEncoded(encoded);
            }
        }
    }

    /**
     * 首条文本消息只接受严格 token 对象；比较使用定时安全字节比较且不记录凭据。
     */
    private boolean authenticate(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_AUTH_FRAME) {
            return false;
        }
        JsonNode object = mapper.readTree(bytes);
        return object != null && object.isObject() && object.size() == 1
               && object.path("token").isTextual()
               && MessageDigest.isEqual(HexFormat.of().formatHex(token).getBytes(StandardCharsets.US_ASCII),
            object.path("token").asText().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 在写入 token 前收紧文件 ACL；记录可连接地址，通配绑定不作为客户端目标。
     */
    private void publishEndpoint(int port) throws IOException {
        Files.createDirectories(configuration.runDirectory());
        Path temp = Files.createTempFile(configuration.runDirectory(), ".app-server-", ".endpoint");
        try {
            protectCurrentUser(temp);
            ObjectNode endpoint = mapper.createObjectNode().put("protocolMajor", PROTOCOL_MAJOR).put("protocolMinor", 0)
                .put("host", endpointHost()).put("port", port).put("serverInstanceId", instanceId)
                .put("runtimeGeneration", configuration.runtimeGeneration())
                .put("token", HexFormat.of().formatHex(token));
            Files.write(temp, mapper.writeValueAsBytes(endpoint));
            Files.move(temp, endpointPath, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Ja endpoint requires atomic publication", unsupported);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 只移除仍属于本实例的端点，避免旧 owner 在竞态下删除新 owner 文件。
     */
    private void removeOwnedEndpoint() {
        try {
            if (Files.exists(endpointPath)
                && instanceId.equals(mapper.readTree(Files.readAllBytes(endpointPath))
                .path("serverInstanceId").asText())) {
                Files.deleteIfExists(endpointPath);
            }
        } catch (IOException | RuntimeException ignored) {
            // 过期端点由下次 owner 原子替换，绝不在异常清理时删除未知文件。
        }
    }

    /**
     * 服务端请求封装在唯一入口，避免内部代理绕过严格 JSONL 信封。
     */
    private ObjectNode request(String id, String method, ObjectNode params) {
        return mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id)
            .put("method", method).set("params", params);
    }

    /**
     * 服务端通知保持现有 JA-RPC 方法名与参数字段，不增加连接私有信息。
     */
    private ObjectNode notification(String method, ObjectNode params) {
        return mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method)
            .set("params", params);
    }

    /**
     * 先撤销端点和连接监听，再关闭内部输入；每个连接记录一次 shutdown phase，业务任务不随之取消。
     *
     * @author kongweiguang
     */
    @Override
    public void close() {
        List<Client> active;
        synchronized (connectionGate) {
            if (!transportClosed.compareAndSet(false, true)) {
                return;
            }
            active = List.copyOf(connections);
        }
        removeOwnedEndpoint();
        active.forEach(client -> client.close(ClientClosePhase.DAEMON_SHUTDOWN, null));
        authDeadlines.shutdownNow();
        try {
            runtimeIngress.close();
        } catch (IOException failure) {
            LOG.warn("Ja runtime ingress close failed type={}", failure.getClass().getSimpleName());
        }
        try {
            if (transportApp != null) {
                transportApp.stop();
            }
        } finally {
            try {
                runtime.close();
            } finally {
                try {
                    runtimeEgress.close();
                } catch (IOException failure) {
                    LOG.warn("Ja runtime egress close failed type={}", failure.getClass().getSimpleName());
                }
                NativeExecutionContext.shared().disableSharedMode();
            }
        }
    }

    /**
     * 映射已提交到唯一 RpcServer 的请求；断线不能从这里自动重新提交。
     *
     * @param client      客户端
     * @param clientId    客户端标识
     * @param method      方法
     * @param threadId    所属对话的稳定标识
     * @param observation 观察结果
     */
    private record Pending(Client client,
                           String clientId,
                           String method,
                           String threadId,
                           Observation observation) {
    }

    /**
     * 观察前后分别保存聚合身份和句柄，断线可只释放本连接拥有的高频订阅。
     *
     * @param kind     类型分类
     * @param identity 当前观察项的身份标识
     * @param handle   句柄
     * @param adding   是否为新增操作
     */
    private record Observation(String kind,
                               String identity,
                               String handle,
                               boolean adding) {
    }

    /**
     * 断线清理只持有连接自己签发的句柄，不把整个 Client 带入异步资源释放。
     *
     * @param kind   类型分类
     * @param handle 句柄
     */
    private record OwnedHandle(String kind,
                               String handle) {
    }

    /**
     * 固定关闭来源类别，避免把底层 transport 回调误称为对端主动关闭。
     *
     * @author kongweiguang
     */
    private enum ClientClosePhase {
        /** 守护进程关闭主动终止客户端连接。 */
        DAEMON_SHUTDOWN,
        /** 客户端未在认证期限内完成握手。 */
        AUTH_TIMEOUT,
        /** 握手凭据无效或未获授权。 */
        AUTHENTICATION_REJECTED,
        /** 入站文本帧违反消息合同或大小限制。 */
        INBOUND_TEXT_REJECTED,
        /** 此观察连接不接受二进制帧。 */
        BINARY_FRAME_REJECTED,
        /** 客户端提交了无法接受的观察响应。 */
        OBSERVATION_RESPONSE_REJECTED,
        /** 传出协议消息无法编码为帧。 */
        OUTBOUND_ENCODING_FAILURE,
        /** 有界发送队列已满，连接无法继续可靠接收输出。 */
        OUTBOUND_QUEUE_OVERFLOW,
        /** Writer 等待连接自身的输出资源时失败。 */
        WRITER_WAIT,
        /** Writer 的连接保活探测失败。 */
        WRITER_PING,
        /** Writer 发送帧时传输失败。 */
        WRITER_SEND,
        /** 运行时输出流正常结束并关闭观察连接。 */
        RUNTIME_OUTPUT_END,
        /** 运行时输出无法继续通过当前传输层送达。 */
        RUNTIME_TRANSPORT_FAILURE,
        /** 底层传输回调报告对端关闭。 */
        TRANSPORT_CLOSE_CALLBACK,
        /** 底层传输回调报告连接错误。 */
        TRANSPORT_ERROR_CALLBACK,
        /** 新连接未能登记到运行时观察集合。 */
        CONNECTION_REGISTRATION_FAILURE,
        /** 关闭底层 Socket 时发生失败。 */
        SOCKET_CLOSE_FAILURE
    }

    /**
     * 输出固定类别、关闭 phase 和共享脱敏摘要；异常 message、帧正文和连接身份不进入日志。
     *
     * @author kongweiguang
     */
    private static void logClientClose(ClientClosePhase phase, long textMessageSequence, Throwable failure) {
        String failureType = "none";
        String origin = "none";
        if (failure != null) {
            FailureDiagnostics.Summary diagnostic = FailureDiagnostics.summarize(failure);
            failureType = diagnostic.type();
            origin = diagnostic.origin();
        }
        if (phase == ClientClosePhase.DAEMON_SHUTDOWN || phase == ClientClosePhase.RUNTIME_OUTPUT_END) {
            LOG.debug(CLIENT_CLOSE_LOG_TEMPLATE, phase, textMessageSequence, failureType, origin);
        } else if (phase == ClientClosePhase.TRANSPORT_CLOSE_CALLBACK && failure == null) {
            LOG.info(CLIENT_CLOSE_LOG_TEMPLATE, phase, textMessageSequence, failureType, origin);
        } else {
            LOG.warn(CLIENT_CLOSE_LOG_TEMPLATE, phase, textMessageSequence, failureType, origin);
        }
    }

    /** 每个客户端独立限额和 Writer，避免 WebSocket 慢读者影响进程级协议顺序；关闭只影响连接观察。 */
    private final class Client {
        private final WebSocket socket;
        private final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(CLIENT_QUEUE_FRAMES);
        private final Set<String> observedThreads = ConcurrentHashMap.newKeySet();
        private final Map<String, Integer> planCounts = new ConcurrentHashMap<>();
        private final Map<String, Integer> taskCounts = new ConcurrentHashMap<>();
        private final Map<String, String> planHandles = new ConcurrentHashMap<>();
        private final Map<String, String> taskHandles = new ConcurrentHashMap<>();
        private volatile boolean ready;
        private volatile boolean initializedConnection;
        private volatile boolean closed;
        private volatile boolean authenticated;
        private final ScheduledFuture<?> authDeadline;
        private volatile String contextId;
        private int queuedBytes;
        private long textMessageSequence;

        /**
         * 未认证连接也受两秒期限和统一连接额度约束；超时以固定 phase 记录后复用幂等清理。
         *
         * @author kongweiguang
         */
        private Client(WebSocket socket) {
            this.socket = socket;
            this.authDeadline = authDeadlines.schedule(
                    () -> close(ClientClosePhase.AUTH_TIMEOUT, null), 2, TimeUnit.SECONDS);
        }

        /**
         * 首条文本只做认证；后续每条文本恰好对应一帧 JA-RPC，拒绝时只记录安全 phase 与连接内序号。
         *
         * @author kongweiguang
         */
        private synchronized void onText(String text) {
            if (closed) {
                return;
            }
            textMessageSequence++;
            ClientClosePhase failurePhase = authenticated
                    ? ClientClosePhase.INBOUND_TEXT_REJECTED : ClientClosePhase.AUTHENTICATION_REJECTED;
            try {
                if (!authenticated) {
                    if (!authenticate(text)) {
                        throw new IOException("invalid authentication");
                    }
                    authenticated = true;
                    authDeadline.cancel(false);
                    clients.add(this);
                    emptySinceNanos.set(0);
                    Thread.ofVirtual().name("ja-ws-client-writer").start(this::writeLoop);
                    return;
                }
                if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                    throw new IOException("WebSocket text must contain exactly one JA-RPC frame");
                }
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                if (data.length > CLIENT_QUEUE_BYTES) {
                    throw new IOException("JA-RPC frame is too large");
                }
                byte[] line = Arrays.copyOf(data, data.length + 1);
                line[data.length] = '\n';
                Optional<JaRpcCodec.Frame> next = codec.read(new ByteArrayInputStream(line));
                if (next.isEmpty()) {
                    throw new IOException("empty JA-RPC frame");
                }
                switch (next.orElseThrow()) {
                    case JaRpcCodec.Request request -> onRequest(request);
                    case JaRpcCodec.Notification event -> onNotification(event);
                    case JaRpcCodec.Response ignored -> throw new IOException("unexpected client response");
                }
            } catch (IOException | RuntimeException failure) {
                // 断线保留进程级 Turn 与审批，客户端以权威快照对账；只记录脱敏异常摘要。
                close(failurePhase, failure);
            }
        }

        /**
         * 严格复核客户端的版本、能力与限制，再复用唯一内部 initialize 结果。
         */
        private void onRequest(JaRpcCodec.Request request) throws IOException {
            if ("runtime/initialize".equals(request.method())) {
                if (initializedConnection || !validateInitialize(request.params())) {
                    throw new IOException("invalid initialize");
                }
                initializedConnection = true;
                offer(mapper.createObjectNode().put("jsonrpc", "2.0").put("id", request.id())
                    .set("result", initialized.join().deepCopy()));
                return;
            }
            if (!ready) {
                throw new IOException("runtime is not initialized");
            }
            if ("runtime/context/register".equals(request.method())) {
                registerContext(request);
                return;
            }
            if (contextId == null && !Set.of("runtime/health", "runtime/shutdown")
                .contains(request.method())) {
                offerError(request.id(), JaRpcException.of(JaErrorCatalog.NOT_INITIALIZED,
                    "native execution context is not registered"));
                return;
            }
            synchronized (requestGate) {
                forward(request);
            }
        }

        /**
         * 关闭检查与普通请求准入共用一把短锁，防止 non-force 空闲判断后又接纳新副作用。
         */
        private void forward(JaRpcCodec.Request request) throws IOException {
            if (stopRequested.get()) {
                offerError(request.id(), JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN,
                    "runtime is shutting down"));
                return;
            }
            boolean stopping = "runtime/shutdown".equals(request.method());
            boolean closingNativeClient = "runtime/client/close".equals(request.method());
            Optional<NativeClientOwner>
                    owner = contextId == null ? Optional.empty()
                    : NativeExecutionContext.shared().clientOwner(contextId);
            if (closingNativeClient) {
                if (owner.isEmpty() || !validNativeClientClose(request.params())) {
                    offerError(request.id(), JaRpcException.invalidParams());
                    return;
                }
                NativeExecutionContext.shared().beginNativeClientClose(owner.orElseThrow());
            } else if (owner.isPresent()
                    && !NativeExecutionContext.shared().nativeClientAcceptsRequests(owner.orElseThrow())) {
                offerError(request.id(), JaRpcException.of(JaErrorCatalog.INVALID_STATE,
                        "native client lifetime is closing"));
                return;
            }
            if (stopping) {
                boolean force = request.params().path("force").asBoolean(false);
                if (!force && !pending.isEmpty()) {
                    offerError(request.id(), JaRpcException.of(JaErrorCatalog.INVALID_STATE,
                        "runtime has pending requests"));
                    return;
                }
                stopRequested.set(true);
            }
            if (pending.size() >= MAX_PENDING) {
                if (stopping) {
                    stopRequested.set(false);
                }
                throw new IOException("request capacity is exhausted");
            }
            Observation observation = prepareObservation(request);
            if (observation != null && !observation.adding() && observation.identity() == null) {
                offer(mapper.createObjectNode().put("jsonrpc", "2.0").put("id", request.id())
                    .set("result", mapper.createObjectNode().put("accepted", true)));
                return;
            }
            String threadId = null;
            if ("thread/observe".equals(request.method()) || "thread/unobserve".equals(request.method())) {
                JsonNode value = request.params().get("threadId");
                if (request.params().size() != 1 || value == null || !value.isTextual()
                    || !value.textValue().matches("thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) {
                    throw new IOException("invalid thread observation");
                }
                threadId = value.textValue();
                if ("thread/observe".equals(request.method())) {
                    observedThreads.add(threadId);
                } else {
                    observedThreads.remove(threadId);
                }
            }
            String internalId = "c:daemon_" + nextRequest.incrementAndGet();
            if (stopping) {
                stoppingRequestId.set(internalId);
            }
            String context = contextId;
            if (context != null) {
                NativeExecutionContext.shared().bindRpcRequest(internalId, context);
            }
            pending.put(internalId, new Pending(this, request.id(), request.method(), threadId,
                observation));
            try {
                sendInternal(request(internalId, request.method(), request.params()));
            } catch (IOException failure) {
                pending.remove(internalId);
                if (context != null) {
                    NativeExecutionContext.shared().unbindRpcRequest(internalId);
                }
                if (observation != null) {
                    rollbackObservation(observation);
                }
                if (stopping) {
                    stopRequested.set(false);
                    stoppingRequestId.compareAndSet(internalId, null);
                }
                throw failure;
            }
        }

        /**
         * 先把当前连接加入高频观察再转发请求；Handler 返回的快照可覆盖 ACK 前事件，
         * 期间到达的通知也已有连接过滤身份，不会落入 read/subscribe 空窗。
         */
        private Observation prepareObservation(JaRpcCodec.Request request) throws IOException {
            String method = request.method();
            ObjectNode params = request.params();
            String kind;
            String field;
            if (method.startsWith("plan/observe") || method.startsWith("plan/unobserve")) {
                kind = "plan";
                field = "planId";
            } else if (method.startsWith("task/observe") || method.startsWith("task/unobserve")) {
                kind = "task";
                field = "taskThreadId";
            } else {
                return null;
            }
            boolean adding = method.endsWith("/observe");
            if (adding) {
                String identity = params.path(field).asText("");
                String prefix = "task".equals(kind) ? "thr_" : kind + "_";
                if (!identity.startsWith(prefix) || identity.length() > 128) {
                    throw new IOException("invalid observation identity");
                }
                increment(counts(kind), identity);
                return new Observation(kind, identity, null, true);
            }
            String handle = params.path("observationId").asText("");
            if (!handle.matches("observe_[A-Za-z0-9][A-Za-z0-9._-]{0,120}")) {
                throw new IOException("invalid observation handle");
            }
            String identity = handles(kind).get(handle);
            if (identity != null) {
                decrement(counts(kind), identity);
            }
            return new Observation(kind, identity, handle, false);
        }

        /**
         * 成功观察保存仅本连接拥有的句柄；无效 ACK 关闭当前连接并保留服务端执行 owner。
         *
         * @author kongweiguang
         */
        private void settleObservation(Observation observation, JaRpcCodec.Response response) {
            OwnedHandle lateHandle = null;
            boolean invalid = false;
            synchronized (this) {
                if (response.error() != null) {
                    rollbackObservation(observation);
                    return;
                }
                if (observation.adding()) {
                    String handle = response.result().path("observationId").asText("");
                    if (!handle.startsWith("observe_")) {
                        invalid = true;
                    } else if (closed) {
                        lateHandle = new OwnedHandle(observation.kind(), handle);
                    } else {
                        handles(observation.kind()).put(handle, observation.identity());
                    }
                } else {
                    handles(observation.kind()).remove(observation.handle());
                }
            }
            if (invalid) {
                close(ClientClosePhase.OBSERVATION_RESPONSE_REJECTED, null);
            }
            if (lateHandle != null) {
                scheduleObservationRelease(List.of(lateHandle));
            }
        }

        /**
         * 传输发送失败或领域拒绝时恢复当前连接的过滤状态，不改变其它连接句柄。
         */
        private void rollbackObservation(Observation observation) {
            if (observation.adding()) {
                decrement(counts(observation.kind()), observation.identity());
            } else if (observation.identity() != null) {
                increment(counts(observation.kind()), observation.identity());
            }
        }

        /**
         * 聚合类型只在代理层解析，所有 map 都限定于当前客户端。
         */
        private Map<String, Integer> counts(String kind) {
            return switch (kind) {
                case "plan" -> planCounts;
                case "task" -> taskCounts;
                default -> throw new IllegalArgumentException("unknown observation kind");
            };
        }

        /**
         * 句柄与聚合身份分开，以免某一客户端 unobserve 猜测另一客户端的句柄。
         */
        private Map<String, String> handles(String kind) {
            return switch (kind) {
                case "plan" -> planHandles;
                case "task" -> taskHandles;
                default -> throw new IllegalArgumentException("unknown observation kind");
            };
        }

        /**
         * 同聚合重复观察保留引用计数，关闭其中一个详情不撤销仍活跃的句柄。
         */
        private void increment(Map<String, Integer> counts, String identity) {
            counts.merge(identity, 1, Integer::sum);
        }

        /**
         * 只在最后一个句柄撤销时关闭该连接的高频事件。
         */
        private void decrement(Map<String, Integer> counts, String identity) {
            counts.computeIfPresent(identity, (ignored, count) -> count > 1 ? count - 1 : null);
        }

        /**
         * 断线直接撤销当前连接拥有的句柄；经有界入站队列投递清理在大量观察下可能
         * 被容量拒绝，留下长期共享 owner 订阅，因此这里调用唯一 Session 的线程安全入口。
         */
        @SuppressWarnings("PMD.CloseResource") // Session 由 RpcServer 关闭，连接只借用其观察释放入口。
        private void releaseObservation(String kind, String handle) {
            try {
                RpcSession owner = ownerSession;
                if (owner == null) {
                    return;
                }
                switch (kind) {
                    case "plan" -> owner.unobservePlan(handle);
                    case "task" -> owner.unobserveTask(handle);
                    default -> throw new IllegalArgumentException("unknown observation kind");
                }
            } catch (RuntimeException ignored) {
                // Owner 已关闭时其 Session 清理会撤销全部观察，不跨连接重试。
            }
        }

        /**
         * 环境缺失是连接协议状态错误；保持连接可注册后重试且不反射环境内容。
         */
        private void offerError(String id, JaRpcException failure) {
            ObjectNode data = mapper.createObjectNode().put("errorCode", failure.errorCode())
                .put("category", failure.category()).put("retryable", failure.retryable())
                .put("errorId", failure.errorId());
            ObjectNode error = mapper.createObjectNode().put("code", failure.code())
                .put("message", failure.getMessage());
            error.set("data", data);
            offer(mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).set("error", error));
        }

        /**
         * 全局生命周期仍广播；显式观察连接只接收当前 Thread 的流与衍生活动。
         */
        private boolean accepts(String method, ObjectNode params) {
            if (GLOBAL_NOTIFICATIONS.contains(method)) {
                return true;
            }
            if ("plan/changed".equals(method)) {
                return planCounts.containsKey(params.path("planId").asText(""));
            }
            if ("task/progress".equals(method) || "task/mailbox-changed".equals(method)) {
                return taskCounts.containsKey(params.path("taskThreadId").asText(""));
            }
            for (String field : THREAD_ID_FIELDS) {
                JsonNode thread = params.get(field);
                if (thread != null && thread.isTextual() && observedThreads.contains(thread.textValue())) {
                    return true;
                }
            }
            return false;
        }


        /**
         * 凭据与完整环境仅留在 Java 内存；重复注册原子替换后撤销旧连接身份。
         */
        private void registerContext(JaRpcCodec.Request request) throws IOException {
            ObjectNode params = request.params();
            if (params.size() != 4 || !(params.get("environment") instanceof ObjectNode environment)
                || !params.has("shell") || !(params.get("shell").isNull() || params.get("shell").isTextual())) {
                throw new IOException("invalid native execution context");
            }
            JsonNode processIdNode = params.get("clientProcessId");
            if (processIdNode == null || !processIdNode.isIntegralNumber() || !processIdNode.canConvertToLong()
                    || processIdNode.longValue() < 1
                    || processIdNode.longValue() > 9_007_199_254_740_991L
                    || !params.path("clientLifetimeId").isTextual()
                    || !params.path("clientLifetimeId").asText().matches("native_[0-9a-f]{32}")) {
                throw new IOException("invalid native client lifetime");
            }
            Map<String, String> values = new LinkedHashMap<>();
            if (environment.size() > 4_096) {
                throw new IOException("native environment is too large");
            }
            for (Map.Entry<String, JsonNode> entry : environment.properties()) {
                if (!entry.getValue().isTextual()) {
                    throw new IOException("invalid native environment");
                }
                values.put(entry.getKey(), entry.getValue().textValue());
            }
            String shell = params.get("shell").isNull() ? null : params.get("shell").textValue();
            NativeExecutionContext contexts = NativeExecutionContext.shared();
            String lifetimeId = params.path("clientLifetimeId").asText();
            String prior = contextId;
            Optional<NativeClientOwner>
                    priorOwner = prior == null ? Optional.empty() : contexts.clientOwner(prior);
            if (priorOwner.isPresent()
                    && !contexts.nativeClientLifetimeMatches(priorOwner.orElseThrow(), lifetimeId)
                    && !contexts.nativeClientClosed(priorOwner.orElseThrow())) {
                throw new IOException("native client lifetime must close before replacement");
            }
            NativeClientOwner owner;
            try {
                owner = contexts.registerNativeClient(processIdNode.longValue(), lifetimeId);
            } catch (IllegalArgumentException invalidIdentity) {
                throw new IOException("native client process identity is unavailable", invalidIdentity);
            }
            String replacement = contexts.register(values, shell, Optional.of(owner));
            contextId = replacement;
            if (prior != null) {
                contexts.release(prior);
            }
            offer(mapper.createObjectNode().put("jsonrpc", "2.0").put("id", request.id())
                .set("result", mapper.createObjectNode().put("contextId", replacement)));
        }

        /**
         * 在切换 owner 到 Closing 前复核精确协议，避免非法 close 帧永久关死本连接的业务准入。
         */
        private boolean validNativeClientClose(ObjectNode params) {
            if (params.size() != 1 || !params.path("timeoutMs").isIntegralNumber()
                    || !params.path("timeoutMs").canConvertToInt()) {
                return false;
            }
            int timeoutMs = params.path("timeoutMs").intValue();
            return timeoutMs >= 1 && timeoutMs <= 20_000;
        }

        /**
         * Ready token 只在连接内回显；内部 owner 的 ready 挑战早在发布端点前已完成。
         */
        private void onNotification(JaRpcCodec.Notification event) throws IOException {
            if (!initializedConnection || ready || !"runtime/initialized".equals(event.method())) {
                throw new IOException("unexpected client notification");
            }
            ObjectNode params = event.params();
            if (params.size() != 1 || !params.path("readyToken").isTextual()
                || !params.path("readyToken").asText().matches("[0-9a-f]{32}")) {
                throw new IOException("invalid ready token");
            }
            ObjectNode status = WebSocketDaemon.this.ready.join().deepCopy()
                .put("readyToken", params.path("readyToken").asText());
            offer(notification("runtime/status-changed", status));
            ready = true;
        }

        /**
         * 不允许第二次握手或能力漂移；避免代理接受内部 RpcServer 本会拒绝的客户端。
         */
        private boolean validateInitialize(ObjectNode params) {
            return params.size() == 5
                   && params.path("protocolMajor").isInt()
                   && params.path("protocolMajor").intValue() == PROTOCOL_MAJOR
                   && params.path("protocolMinor").isInt() && params.path("protocolMinor").intValue() == 0
                   && params.path("clientVersion").isTextual()
                   && params.path("clientVersion").asText().length() <= 128
                   && HandshakeHandler.capabilities(mapper).equals(params.path("capabilities"))
                   && HandshakeHandler.limits(mapper).equals(params.path("limits"));
        }

        /**
         * 帧编码失败只关闭当前连接；失败摘要不携带编码输入或异常 message。
         *
         * @author kongweiguang
         */
        private void offer(ObjectNode frame) {
            if (closed) {
                return;
            }
            try {
                offerEncoded(mapper.writeValueAsBytes(frame));
            } catch (IOException failure) {
                close(ClientClosePhase.OUTBOUND_ENCODING_FAILURE, failure);
            }
        }

        /**
         * 共享不可变编码帧；超限只记录固定 phase 并断开慢连接，不影响进程级消息 owner。
         *
         * @author kongweiguang
         */
        private void offerEncoded(byte[] bytes) {
            boolean overflow;
            synchronized (this) {
                if (closed) {
                    return;
                }
                overflow = bytes.length > CLIENT_QUEUE_BYTES
                           || queuedBytes + bytes.length > CLIENT_QUEUE_BYTES || !outbound.offer(bytes);
                if (!overflow) {
                    queuedBytes += bytes.length;
                }
            }
            if (overflow) {
                close(ClientClosePhase.OUTBOUND_QUEUE_OVERFLOW, null);
            }
        }

        /**
         * Writer 独占 WebSocket 发送；失败按等待、Ping 或文本发送阶段记录脱敏原因，只释放连接。
         *
         * @author kongweiguang
         */
        private void writeLoop() {
            ClientClosePhase failurePhase = ClientClosePhase.WRITER_WAIT;
            try {
                long lastSend = System.nanoTime();
                while (!closed) {
                    failurePhase = ClientClosePhase.WRITER_WAIT;
                    byte[] frame = outbound.poll(100, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        if (System.nanoTime() - lastSend >= pingInterval.toNanos()) {
                            failurePhase = ClientClosePhase.WRITER_PING;
                            socket.sendPing().get();
                            lastSend = System.nanoTime();
                        }
                        continue;
                    }
                    failurePhase = ClientClosePhase.WRITER_SEND;
                    socket.send(new String(frame, StandardCharsets.UTF_8)).get();
                    synchronized (this) {
                        if (!closed) {
                            queuedBytes -= frame.length;
                        }
                        notifyAll();
                    }
                    lastSend = System.nanoTime();
                }
            } catch (InterruptedException | ExecutionException | RuntimeException failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                close(failurePhase, failure);
            }
        }

        /**
         * 编码字节配额持续覆盖在途发送；正常 EOF 只能在已准入尾帧发送完成后关闭连接，并沿用 RPC 的期限。
         * 已断开的客户端没有可交付通道，可直接撤销观察；慢发送耗尽预算仍报告失败而非静默丢弃 ACK。
         */
        private synchronized void awaitOutbound(ShutdownDeadline deadline) {
            while (!closed && queuedBytes > 0) {
                long remaining = deadline.remainingMillis();
                if (remaining == 0) {
                    throw ShutdownDeadline.forced("websocket outbound drain timed out", null);
                }
                try {
                    wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ShutdownDeadline.forced("websocket outbound drain interrupted", interrupted);
                }
            }
        }

        /**
         * 首个关闭来源原子占有 phase 并只记录一次；随后撤销连接路由与观察句柄，不取消持久任务。
         * 无论关闭由哪个回调观察到，都沿用统一 socket 清理；未知提交状态留待重连对账。
         *
         * @author kongweiguang
         */
        private void close(ClientClosePhase phase, Throwable failure) {
            List<OwnedHandle> owned = new ArrayList<>();
            String context;
            long observedTextMessageSequence;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                observedTextMessageSequence = textMessageSequence;
                authDeadline.cancel(false);
                listener.accepted.remove(socket, this);
                connections.remove(this);
                connectionSlots.release();
                clients.remove(this);
                if (clients.isEmpty()) {
                    emptySinceNanos.set(System.nanoTime());
                }
                planHandles.keySet().forEach(handle -> owned.add(new OwnedHandle("plan", handle)));
                taskHandles.keySet().forEach(handle -> owned.add(new OwnedHandle("task", handle)));
                planHandles.clear();
                taskHandles.clear();
                planCounts.clear();
                taskCounts.clear();
                observedThreads.clear();
                context = contextId;
                outbound.clear();
                queuedBytes = 0;
                notifyAll();
            }
            logClientClose(phase, observedTextMessageSequence, failure);
            try {
                socket.close();
            } catch (RuntimeException closeFailure) {
                logClientClose(ClientClosePhase.SOCKET_CLOSE_FAILURE, observedTextMessageSequence, closeFailure);
            }
            if (context != null) {
                NativeExecutionContext.shared().release(context);
            }
            scheduleObservationRelease(owned);
        }

        /**
         * 清理不占用唯一 output pump 和连接队列锁；每个连接最多只启动一条有限工作线程。
         */
        private void scheduleObservationRelease(List<OwnedHandle> owned) {
            if (owned.isEmpty()) {
                return;
            }
            Thread.ofVirtual().name("ja-ws-observation-close").start(() ->
                owned.forEach(handle -> releaseObservation(handle.kind(), handle.handle())));
        }
    }

    /**
     * 每条 Solon 路由连接持有独立 Client；框架负责 WS 升级和控制帧，Ja 只管理身份与业务帧。
     */
    private final class ConnectionListener extends SimpleWebSocketListener {
        private final Map<WebSocket, Client> accepted = new ConcurrentHashMap<>();

        /**
         * 注册失败按唯一首关 phase 留痕；关闭来源日志不携带 socket、异常 message 或帧内容。
         *
         * @author kongweiguang
         */
        @Override
        public void onOpen(WebSocket socket) {
            boolean acceptedConnection = false;
            synchronized (connectionGate) {
                if (!transportClosed.get() && transportFailure.get() == null && connectionSlots.tryAcquire()) {
                    Client client = null;
                    try {
                        socket.setIdleTimeout(connectionIdleTimeout.toMillis());
                        client = new Client(socket);
                        accepted.put(socket, client);
                        connections.add(client);
                        acceptedConnection = true;
                    } catch (RuntimeException failure) {
                        if (client == null) {
                            connectionSlots.release();
                            try {
                                socket.close();
                            } catch (RuntimeException closeFailure) {
                                if (failure != closeFailure) {
                                    failure.addSuppressed(closeFailure);
                                }
                            }
                            logClientClose(ClientClosePhase.CONNECTION_REGISTRATION_FAILURE, 0, failure);
                        } else {
                            client.close(ClientClosePhase.CONNECTION_REGISTRATION_FAILURE, failure);
                        }
                        return;
                    }
                }
            }
            if (!acceptedConnection) {
                try {
                    socket.close();
                } catch (RuntimeException failure) {
                    LOG.debug("Ja rejected WebSocket peer close failed type={}",
                            failure.getClass().getSimpleName());
                }
                return;
            }
            listenerReady.complete(null);
        }

        /** 首条文本认证，其余文本按一条消息一帧 JA-RPC 串行处理。 */
        @Override
        public void onMessage(WebSocket socket, String text) {
            Client client = accepted.get(socket);
            if (client != null) {
                client.onText(text);
            } else {
                socket.close();
            }
        }

        /**
         * 二进制不是 JA-RPC 文本帧；只关闭该 Client 并以固定 phase 留痕，不触碰持久任务。
         *
         * @author kongweiguang
         */
        @Override
        public void onMessage(WebSocket socket, java.nio.ByteBuffer binary) {
            Client client = accepted.get(socket);
            if (client != null) {
                client.close(ClientClosePhase.BINARY_FRAME_REJECTED, null);
            } else {
                socket.close();
            }
        }

        /**
         * 外层 close callback 只记录可观察到的 transport 阶段，不推断底层发起方。
         *
         * @author kongweiguang
         */
        @Override
        public void onClose(WebSocket socket) {
            Client client = accepted.remove(socket);
            if (client != null) {
                client.close(ClientClosePhase.TRANSPORT_CLOSE_CALLBACK, null);
            }
        }

        /**
         * 外层 error callback 保留安全 Throwable 摘要，并与其它首关来源共享幂等清理。
         *
         * @author kongweiguang
         */
        @Override
        public void onError(WebSocket socket, Throwable error) {
            Client client = accepted.remove(socket);
            if (client != null) {
                client.close(ClientClosePhase.TRANSPORT_ERROR_CALLBACK, error);
            }
        }
    }

    /** 0.0.0.0 仅用于监听，端点文件必须给本机 Rust 客户端一个真实可连接的地址。 */
    private String endpointHost() {
        return "0.0.0.0".equals(configuration.listenHost())
            ? "127.0.0.1" : configuration.listenHost();
    }
}
