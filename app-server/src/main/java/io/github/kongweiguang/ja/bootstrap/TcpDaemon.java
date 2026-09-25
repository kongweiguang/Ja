// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.port.in.NativeExecutionContext;
import io.github.kongweiguang.ja.foundation.runtime.ProductVersion;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeHandler;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcServer;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个进程级 JA-RPC owner 对应多个受认证的本地连接；TCP 断线只撤销传输，不关闭 Agent Runtime。
 *
 * <p>内部 RpcServer 的输入和 Writer 始终被事件泵排空，避免把任意一个前端连接的寿命作为
 * Turn、审批或数据库租约的寿命。连接只持有请求 ID 映射和有界出站队列。</p>
 */
public final class TcpDaemon implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TcpDaemon.class);
    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private static final int MAX_CLIENTS = 16;
    private static final int MAX_PENDING = 64;
    private static final int MAX_AUTH_FRAME = 256;
    private static final int CLIENT_QUEUE_FRAMES = 64;
    private static final int CLIENT_QUEUE_BYTES = 8 * 1024 * 1024;
    private static final int INGRESS_QUEUE_BYTES = 16 * 1024 * 1024;
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(5);
    private static final Set<String> GLOBAL_NOTIFICATIONS = Set.of("runtime/status-changed",
            "configuration/changed", "workspace/dirty", "thread/metadata-changed");
    private static final List<String> THREAD_ID_FIELDS = List.of("threadId", "ownerThreadId",
            "rootThreadId", "taskThreadId");
    private final SidecarConfiguration configuration;
    private final Duration idleTimeout;
    private final ObjectMapper mapper = new JaRpcCodec().mapper();
    private final JaRpcCodec codec = new JaRpcCodec();
    private final PipedInputStream runtimeInput;
    private final PipedOutputStream runtimeIngress;
    private final PipedInputStream runtimeOutput;
    private final PipedOutputStream runtimeEgress;
    private final RpcServer runtime;
    private volatile RpcSession ownerSession;
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
    private final CompletableFuture<Integer> runtimeExit = new CompletableFuture<>();
    private final AtomicReference<Throwable> transportFailure = new AtomicReference<>();
    private final Path endpointPath;
    private volatile ServerSocket listener;
    private volatile String instanceId;

    /**
     * 管道容量只用于内部帧交接；对外 socket 无法把 Writer owner 阻塞在慢客户端上。
     */
    public TcpDaemon(SidecarConfiguration configuration, RpcServicesFactory factory,
                     ConfigurationUseCase configurationUseCase, RpcServer.SessionBinding binding) throws IOException {
        this(configuration, factory, configurationUseCase, binding, IDLE_TIMEOUT);
    }

    /** 测试可收窄空闲期限；生产固定五分钟，业务保活仍由 runtime/shutdown 裁决。 */
    TcpDaemon(SidecarConfiguration configuration, RpcServicesFactory factory,
              ConfigurationUseCase configurationUseCase, RpcServer.SessionBinding binding,
              Duration idleTimeout) throws IOException {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isZero() || idleTimeout.isNegative() || idleTimeout.compareTo(IDLE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("invalid TCP idle timeout");
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
                });
        TOKEN_RANDOM.nextBytes(token);
    }

    /**
     * 初始化唯一业务 owner 后才发布端点；认证失败或无法设置当前用户 ACL 时绝不接受请求。
     */
    @SuppressWarnings("PMD.CloseResource")
    public int run() throws IOException {
        // 共享后台无权把最早启动客户端的环境当作随后所有 Turn 的默认环境。
        NativeExecutionContext.shared().enableSharedMode();
        Thread.ofVirtual().name("ja-tcp-output-pump").start(this::pumpOutput);
        Thread.ofVirtual().name("ja-tcp-owner-ingress").start(this::pumpIngress);
        Thread.ofVirtual().name("ja-tcp-rpc-owner").start(() -> {
            try {
                runtimeExit.complete(runtime.run());
            } catch (Throwable failure) {
                runtimeExit.completeExceptionally(failure);
                failTransport(failure);
            }
        });
        initializeRuntime();
        try (ServerSocket server = new ServerSocket()) {
            listener = server;
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), MAX_CLIENTS);
            server.setSoTimeout(1_000);
            publishEndpoint(server.getLocalPort());
            emptySinceNanos.set(System.nanoTime());
            while (!runtimeExit.isDone() && transportFailure.get() == null) {
                try {
                    Socket socket = server.accept();
                    if (!connectionSlots.tryAcquire()) {
                        socket.close();
                    } else {
                        Thread.ofVirtual().name("ja-tcp-client-auth").start(() -> {
                            try { accept(socket); }
                            finally { connectionSlots.release(); }
                        });
                    }
                } catch (SocketTimeoutException ignored) {
                    requestIdleShutdownIfSafe();
                } catch (IOException failure) {
                    if (transportFailure.get() != null) break;
                    throw failure;
                }
            }
            return transportFailure.get() == null ? runtimeExit.getNow(1) : 1;
        } finally {
            listener = null;
            removeOwnedEndpoint();
        }
    }

    /**
     * 无连接五分钟后请求同一服务端裁决：待审批、澄清和 Plan/Goal 保活由权威
     * runtime/shutdown 检查，拒绝后重新计时，不能由 TCP 层猜测业务空闲。
     */
    private void requestIdleShutdownIfSafe() throws IOException {
        if (!clients.isEmpty()) {
            emptySinceNanos.set(0);
            return;
        }
        long since = emptySinceNanos.updateAndGet(current -> current == 0 ? System.nanoTime() : current);
        if (System.nanoTime() - since < idleTimeout.toNanos()) return;
        emptySinceNanos.set(System.nanoTime());
        String id = "c:daemon_idle_" + nextRequest.incrementAndGet();
        synchronized (requestGate) {
            if (!pending.isEmpty() || stopRequested.get()) return;
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

    /** 内部握手预先完成，让后续每个连接只协商传输身份而不重复打开 Java 服务图。 */
    private void initializeRuntime() throws IOException {
        ObjectNode params = mapper.createObjectNode().put("protocolMajor", 1).put("protocolMinor", 0)
                .put("clientVersion", ProductVersion.current());
        params.set("capabilities", HandshakeHandler.capabilities(mapper));
        params.set("limits", HandshakeHandler.limits(mapper));
        sendInternal(request("c:daemon_init", "runtime/initialize", params));
        ObjectNode result = await(initialized);
        JsonNode id = result.get("serverInstanceId");
        if (id == null || !id.isTextual()) throw new IOException("Ja runtime identity is unavailable");
        instanceId = id.textValue();
        sendInternal(notification("runtime/initialized", mapper.createObjectNode()
                .put("readyToken", "00000000000000000000000000000000")));
        await(ready);
    }

    /** 只接受一个冻结版本的握手结果；失败不得把旧端点发布给客户端。 */
    private static ObjectNode await(CompletableFuture<ObjectNode> completion) throws IOException {
        try {
            return completion.get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            throw new IOException("Ja runtime did not complete local startup", failure);
        }
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

    /** 唯一长寿命 Pipe 写线程维持请求帧完整性，并持续排空有界队列。 */
    private void pumpIngress() {
        try {
            while (!transportClosed.get()) {
                byte[] frame = ingressQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                ingressQueuedBytes.addAndGet(-frame.length);
                runtimeIngress.write(frame);
                runtimeIngress.write('\n');
                runtimeIngress.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            initialized.completeExceptionally(new IOException("Ja runtime ingress failed"));
        } finally {
            try { runtimeIngress.close(); } catch (IOException ignored) { /* wake owner */ }
        }
    }

    /** 持续排空唯一 Writer，并按内部请求标识将响应送回正确连接。 */
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
            clients.forEach(Client::close);
        }
    }

    /**
     * 输出泵不可用时立刻撤销可发现端点并唤醒 accept/RPC 读取循环；
     * 新连接不能在已经失去响应通道的实例上无限等待 initialize。
     */
    private void failTransport(Throwable failure) {
        if (!transportFailure.compareAndSet(null, Objects.requireNonNull(failure, "failure"))) return;
        initialized.completeExceptionally(failure);
        ready.completeExceptionally(failure);
        removeOwnedEndpoint();
        try { if (listener != null) listener.close(); }
        catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        try { runtimeIngress.close(); }
        catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
    }

    /** 内部 ID 从不泄露；断开连接后的迟到响应直接丢弃，避免重试造成第二次副作用。 */
    private void routeResponse(JaRpcCodec.Response response) {
        if ("c:daemon_init".equals(response.id())) {
            if (response.result() != null) initialized.complete(response.result());
            else initialized.completeExceptionally(new IOException("Ja runtime rejected initialization"));
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
        NativeExecutionContext.shared().unbindRequest(response.id());
        Pending target = pending.remove(response.id());
        if (target == null) return;
        if (response.error() != null && "thread/observe".equals(target.method())) {
            target.client().observedThreads.remove(target.threadId());
        }
        if (response.error() != null && "thread/unobserve".equals(target.method())) {
            target.client().observedThreads.add(target.threadId());
        }
        if (target.observation() != null) {
            target.client().settleObservation(target.observation(), response);
        }
        if (target.client().closed) return;
        ObjectNode frame = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", target.clientId());
        if (response.result() != null) frame.set("result", response.result());
        else frame.set("error", response.error());
        target.client().offer(frame);
    }

    /** 进程事件只编码一次；每个慢读者拥有独立预算，不能背压 Agent 线程。 */
    private void routeEvent(JaRpcCodec.Notification event) throws IOException {
        ObjectNode params = event.params();
        if ("runtime/status-changed".equals(event.method())
            && "ready".equals(params.path("status").asText())) {
            ready.complete(params);
        }
        ObjectNode frame = notification(event.method(), params);
        byte[] encoded = mapper.writeValueAsBytes(frame);
        for (Client client : clients) {
            if (client.ready && client.accepts(event.method(), params)) client.offerEncoded(encoded);
        }
    }

    /** 认证过程限制字节、时间与并发连接；错误只关闭 socket，不反射敏感输入。 */
    private void accept(Socket socket) {
        try {
            socket.setSoTimeout(2_000);
            byte[] auth = readAuthLine(socket.getInputStream());
            JsonNode object = mapper.readTree(auth);
            if (!object.isObject() || object.size() != 1 || !object.path("token").isTextual()
                || !MessageDigest.isEqual(HexFormat.of().formatHex(token).getBytes(StandardCharsets.US_ASCII),
                        object.path("token").asText().getBytes(StandardCharsets.US_ASCII))) {
                socket.close();
                return;
            }
            socket.setSoTimeout(0);
            Client client = new Client(socket);
            clients.add(client);
            emptySinceNanos.set(0);
            client.run();
        } catch (IOException | RuntimeException ignored) {
            try { socket.close(); }
            catch (IOException closeIgnored) { return; /* socket is already unavailable */ }
        }
    }

    /** 逐字节读认证帧，避免缓冲读取预吞后续 JA-RPC 请求。 */
    private static byte[] readAuthLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(96);
        while (bytes.size() <= MAX_AUTH_FRAME) {
            int value = input.read();
            if (value < 0) throw new IOException("authentication frame is incomplete");
            if (value == '\n') return bytes.toByteArray();
            bytes.write(value);
        }
        throw new IOException("authentication frame is too large");
    }

    /** 在写入 token 前收紧文件 ACL，原子替换旧端点只发生在新 owner 已可接入之后。 */
    private void publishEndpoint(int port) throws IOException {
        Files.createDirectories(configuration.runDirectory());
        Path temp = Files.createTempFile(configuration.runDirectory(), ".app-server-", ".endpoint");
        try {
            protectCurrentUser(temp);
            ObjectNode endpoint = mapper.createObjectNode().put("protocolMajor", 1).put("protocolMinor", 0)
                    .put("port", port).put("serverInstanceId", instanceId)
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

    /** Windows 当前 owner 已匹配时避免无意义的 setOwner 特权调用，DACL 仍必须独占当前用户。 */
    private static void protectCurrentUser(Path file) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            String currentUser = ProcessHandle.current().info().user()
                    .orElseThrow(() -> new IOException("Ja current user is unavailable"));
            var principal = file.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(currentUser);
            if (!acl.getOwner().equals(principal)) acl.setOwner(principal);
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

    /** 只移除仍属于本实例的端点，避免旧 owner 在竞态下删除新 owner 文件。 */
    private void removeOwnedEndpoint() {
        try {
            if (Files.exists(endpointPath)
                && instanceId.equals(mapper.readTree(Files.readAllBytes(endpointPath))
                        .path("serverInstanceId").asText())) {
                Files.deleteIfExists(endpointPath);
            }
        } catch (IOException | RuntimeException ignored) {
            // 过期端点由下次 owner 原子替换，绝不在异常清理时删除未知文件。
            return;
        }
    }

    /** 服务端请求封装在唯一入口，避免内部代理绕过严格 JSONL 信封。 */
    private ObjectNode request(String id, String method, ObjectNode params) {
        return mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id)
                .put("method", method).set("params", params);
    }

    /** 服务端通知保持现有 JA-RPC 方法名与参数字段，不增加连接私有信息。 */
    private ObjectNode notification(String method, ObjectNode params) {
        return mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method)
                .set("params", params);
    }

    /** 先撤销端点和连接，再关闭内部输入，最后由唯一 RpcServer 执行有界业务清理。 */
    @Override
    public void close() {
        if (!transportClosed.compareAndSet(false, true)) return;
        removeOwnedEndpoint();
        clients.forEach(Client::close);
        try { if (listener != null) listener.close(); } catch (IOException failure) {
            LOG.warn("Ja local listener close failed type={}", failure.getClass().getSimpleName());
        }
        try { runtimeIngress.close(); } catch (IOException failure) {
            LOG.warn("Ja runtime ingress close failed type={}", failure.getClass().getSimpleName());
        }
        try {
            runtime.close();
        } finally {
            try { runtimeEgress.close(); } catch (IOException failure) {
                LOG.warn("Ja runtime egress close failed type={}", failure.getClass().getSimpleName());
            }
            NativeExecutionContext.shared().disableSharedMode();
        }
    }

    /** 映射已提交到唯一 RpcServer 的请求；断线不能从这里自动重新提交。 */
    private record Pending(Client client, String clientId, String method, String threadId,
                           Observation observation) { }

    /** 观察前后分别保存聚合身份和句柄，断线可只释放本连接拥有的高频订阅。 */
    private record Observation(String kind, String identity, String handle, boolean adding) { }

    /** 断线清理只持有连接自己签发的句柄，不把整个 Client 带入异步资源释放。 */
    private record OwnedHandle(String kind, String handle) { }

    /** 每个客户端独立限额和 Writer，避免 TCP 慢读者影响进程级协议顺序。 */
    private final class Client {
        private final Socket socket;
        private final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(CLIENT_QUEUE_FRAMES);
        private final Set<String> observedThreads = ConcurrentHashMap.newKeySet();
        private final Map<String, Integer> goalCounts = new ConcurrentHashMap<>();
        private final Map<String, Integer> planCounts = new ConcurrentHashMap<>();
        private final Map<String, Integer> taskCounts = new ConcurrentHashMap<>();
        private final Map<String, String> goalHandles = new ConcurrentHashMap<>();
        private final Map<String, String> planHandles = new ConcurrentHashMap<>();
        private final Map<String, String> taskHandles = new ConcurrentHashMap<>();
        private volatile boolean ready;
        private volatile boolean initializedConnection;
        private volatile boolean closed;
        private volatile String contextId;
        private int queuedBytes;

        /** 认证后的连接才分配出站线程。 */
        private Client(Socket socket) {
            this.socket = socket;
        }

        /** 单连接握手不重新打开服务图；其余请求由进程级 owner 保持原有分发语义。 */
        private void run() {
            Thread.ofVirtual().name("ja-tcp-client-writer").start(this::writeLoop);
            try {
                while (!closed) {
                    Optional<JaRpcCodec.Frame> next = codec.read(socket.getInputStream());
                    if (next.isEmpty()) break;
                    switch (next.orElseThrow()) {
                        case JaRpcCodec.Request request -> onRequest(request);
                        case JaRpcCodec.Notification event -> onNotification(event);
                        case JaRpcCodec.Response ignored -> throw new IOException("unexpected client response");
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // 断线保留进程级 Turn 与审批，客户端以权威快照对账。
            } finally {
                close();
            }
        }

        /** 严格复核客户端的版本、能力与限制，再复用唯一内部 initialize 结果。 */
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
            if (!ready) throw new IOException("runtime is not initialized");
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

        /** 关闭检查与普通请求准入共用一把短锁，防止 non-force 空闲判断后又接纳新副作用。 */
        private void forward(JaRpcCodec.Request request) throws IOException {
            if (stopRequested.get()) {
                offerError(request.id(), JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN,
                        "runtime is shutting down"));
                return;
            }
            boolean stopping = "runtime/shutdown".equals(request.method());
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
                if (stopping) stopRequested.set(false);
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
                if ("thread/observe".equals(request.method())) observedThreads.add(threadId);
                else observedThreads.remove(threadId);
            }
            String internalId = "c:daemon_" + nextRequest.incrementAndGet();
            if (stopping) stoppingRequestId.set(internalId);
            String context = contextId;
            if (context != null) NativeExecutionContext.shared().bindRequest(internalId, context);
            pending.put(internalId, new Pending(this, request.id(), request.method(), threadId,
                    observation));
            try {
                sendInternal(request(internalId, request.method(), request.params()));
            } catch (IOException failure) {
                pending.remove(internalId);
                if (context != null) NativeExecutionContext.shared().unbindRequest(internalId);
                if (observation != null) rollbackObservation(observation);
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
            if (method.startsWith("goal/observe") || method.startsWith("goal/unobserve")) {
                kind = "goal"; field = "goalId";
            } else if (method.startsWith("plan/observe") || method.startsWith("plan/unobserve")) {
                kind = "plan"; field = "planId";
            } else if (method.startsWith("task/observe") || method.startsWith("task/unobserve")) {
                kind = "task"; field = "taskThreadId";
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
            if (identity != null) decrement(counts(kind), identity);
            return new Observation(kind, identity, handle, false);
        }

        /** 成功观察保存仅本连接拥有的句柄；失败撤销先行过滤，断线迟到 ACK 立即清理。 */
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
                    if (!handle.startsWith("observe_")) invalid = true;
                    else if (closed) lateHandle = new OwnedHandle(observation.kind(), handle);
                    else handles(observation.kind()).put(handle, observation.identity());
                } else {
                    handles(observation.kind()).remove(observation.handle());
                }
            }
            if (invalid) close();
            if (lateHandle != null) scheduleObservationRelease(List.of(lateHandle));
        }

        /** 传输发送失败或领域拒绝时恢复当前连接的过滤状态，不改变其它连接句柄。 */
        private void rollbackObservation(Observation observation) {
            if (observation.adding()) decrement(counts(observation.kind()), observation.identity());
            else if (observation.identity() != null) increment(counts(observation.kind()), observation.identity());
        }

        /** 聚合类型只在代理层解析，所有 map 都限定于当前客户端。 */
        private Map<String, Integer> counts(String kind) {
            return switch (kind) {
                case "goal" -> goalCounts;
                case "plan" -> planCounts;
                case "task" -> taskCounts;
                default -> throw new IllegalArgumentException("unknown observation kind");
            };
        }

        /** 句柄与聚合身份分开，以免某一客户端 unobserve 猜测另一客户端的句柄。 */
        private Map<String, String> handles(String kind) {
            return switch (kind) {
                case "goal" -> goalHandles;
                case "plan" -> planHandles;
                case "task" -> taskHandles;
                default -> throw new IllegalArgumentException("unknown observation kind");
            };
        }

        /** 同聚合重复观察保留引用计数，关闭其中一个详情不撤销仍活跃的句柄。 */
        private void increment(Map<String, Integer> counts, String identity) {
            counts.merge(identity, 1, Integer::sum);
        }

        /** 只在最后一个句柄撤销时关闭该连接的高频事件。 */
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
                if (owner == null) return;
                switch (kind) {
                    case "goal" -> owner.unobserveGoal(handle);
                    case "plan" -> owner.unobservePlan(handle);
                    case "task" -> owner.unobserveTask(handle);
                    default -> throw new IllegalArgumentException("unknown observation kind");
                }
            } catch (RuntimeException ignored) {
                // Owner 已关闭时其 Session 清理会撤销全部观察，不跨连接重试。
            }
        }

        /** 环境缺失是连接协议状态错误；保持连接可注册后重试且不反射环境内容。 */
        private void offerError(String id, JaRpcException failure) {
            ObjectNode data = mapper.createObjectNode().put("errorCode", failure.errorCode())
                    .put("category", failure.category()).put("retryable", failure.retryable())
                    .put("errorId", failure.errorId());
            ObjectNode error = mapper.createObjectNode().put("code", failure.code())
                    .put("message", failure.getMessage());
            error.set("data", data);
            offer(mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).set("error", error));
        }

        /** 全局生命周期仍广播；显式观察连接只接收当前 Thread 的流与衍生活动。 */
        private boolean accepts(String method, ObjectNode params) {
            if (GLOBAL_NOTIFICATIONS.contains(method)) return true;
            if ("goal/activity".equals(method)) {
                return goalCounts.containsKey(params.path("goalId").asText(""));
            }
            if ("goal/changed".equals(method)) {
                if (goalCounts.containsKey(params.path("goalId").asText(""))) return true;
                JsonNode owner = params.path("goal").path("owner");
                return observedThreads.contains(owner.path("threadId").asText(""))
                        || observedThreads.contains(owner.path("taskThreadId").asText(""));
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


        /** 凭据与完整环境仅留在 Java 内存；重复注册原子替换后撤销旧连接身份。 */
        private void registerContext(JaRpcCodec.Request request) throws IOException {
            ObjectNode params = request.params();
            if (params.size() != 2 || !(params.get("environment") instanceof ObjectNode environment)
                || !params.has("shell") || !(params.get("shell").isNull() || params.get("shell").isTextual())) {
                throw new IOException("invalid native execution context");
            }
            Map<String, String> values = new LinkedHashMap<>();
            if (environment.size() > 4_096) throw new IOException("native environment is too large");
            for (Map.Entry<String, JsonNode> entry : environment.properties()) {
                if (!entry.getValue().isTextual()) throw new IOException("invalid native environment");
                values.put(entry.getKey(), entry.getValue().textValue());
            }
            String shell = params.get("shell").isNull() ? null : params.get("shell").textValue();
            String replacement = NativeExecutionContext.shared().register(values, shell);
            String prior = contextId;
            contextId = replacement;
            if (prior != null) NativeExecutionContext.shared().release(prior);
            offer(mapper.createObjectNode().put("jsonrpc", "2.0").put("id", request.id())
                    .set("result", mapper.createObjectNode().put("contextId", replacement)));
        }

        /** Ready token 只在连接内回显；内部 owner 的 ready 挑战早在发布端点前已完成。 */
        private void onNotification(JaRpcCodec.Notification event) throws IOException {
            if (!initializedConnection || ready || !"runtime/initialized".equals(event.method())) {
                throw new IOException("unexpected client notification");
            }
            ObjectNode params = event.params();
            if (params.size() != 1 || !params.path("readyToken").isTextual()
                || !params.path("readyToken").asText().matches("[0-9a-f]{32}")) {
                throw new IOException("invalid ready token");
            }
            ObjectNode status = TcpDaemon.this.ready.join().deepCopy()
                    .put("readyToken", params.path("readyToken").asText());
            offer(notification("runtime/status-changed", status));
            ready = true;
        }

        /** 不允许第二次握手或能力漂移；避免代理接受内部 RpcServer 本会拒绝的客户端。 */
        private boolean validateInitialize(ObjectNode params) {
            return params.size() == 5
                    && params.path("protocolMajor").isInt() && params.path("protocolMajor").intValue() == 1
                    && params.path("protocolMinor").isInt() && params.path("protocolMinor").intValue() == 0
                    && params.path("clientVersion").isTextual()
                    && params.path("clientVersion").asText().length() <= 128
                    && HandshakeHandler.capabilities(mapper).equals(params.path("capabilities"))
                    && HandshakeHandler.limits(mapper).equals(params.path("limits"));
        }

        /** 帧数与总字节双重限额，单连接消费慢时直接断开，由客户端重连快照恢复。 */
        private void offer(ObjectNode frame) {
            if (closed) return;
            try {
                offerEncoded(mapper.writeValueAsBytes(frame));
            } catch (IOException failure) {
                close();
            }
        }

        /** 共享不可变编码帧，每个连接仍独立计入字节预算并单独处理慢读者。 */
        private void offerEncoded(byte[] bytes) {
            boolean overflow;
            synchronized (this) {
                if (closed) return;
                overflow = bytes.length > CLIENT_QUEUE_BYTES
                        || queuedBytes + bytes.length > CLIENT_QUEUE_BYTES || !outbound.offer(bytes);
                if (!overflow) queuedBytes += bytes.length;
            }
            if (overflow) close();
        }

        /** Writer 独占 socket output；客户端 TCP flush 失败不污染内部 RpcSession 的持久状态。 */
        @SuppressWarnings("PMD.CloseResource")
        private void writeLoop() {
            try {
                OutputStream output = socket.getOutputStream();
                while (!closed) {
                    byte[] frame = outbound.poll(100, TimeUnit.MILLISECONDS);
                    if (frame == null) continue;
                    synchronized (this) { queuedBytes -= frame.length; }
                    output.write(frame);
                    output.write('\n');
                    output.flush();
                }
            } catch (IOException | InterruptedException ignored) {
                // 清理只作用于连接，内部 Writer 与 Agent 继续运行。
            } finally {
                close();
            }
        }

        /** 清理请求映射，未知提交状态留待重连对账，不把断线解释成取消。 */
        private void close() {
            List<OwnedHandle> owned = new ArrayList<>();
            String context;
            synchronized (this) {
                if (closed) return;
                closed = true;
                clients.remove(this);
                if (clients.isEmpty()) emptySinceNanos.set(System.nanoTime());
                goalHandles.keySet().forEach(handle -> owned.add(new OwnedHandle("goal", handle)));
                planHandles.keySet().forEach(handle -> owned.add(new OwnedHandle("plan", handle)));
                taskHandles.keySet().forEach(handle -> owned.add(new OwnedHandle("task", handle)));
                goalHandles.clear();
                planHandles.clear();
                taskHandles.clear();
                goalCounts.clear();
                planCounts.clear();
                taskCounts.clear();
                observedThreads.clear();
                context = contextId;
                outbound.clear();
                queuedBytes = 0;
            }
            try { socket.close(); } catch (IOException failure) {
                LOG.warn("Ja local client close failed type={}", failure.getClass().getSimpleName());
            }
            if (context != null) NativeExecutionContext.shared().release(context);
            scheduleObservationRelease(owned);
        }

        /** 清理不占用唯一 output pump 和连接队列锁；每个连接最多只启动一条有限工作线程。 */
        private void scheduleObservationRelease(List<OwnedHandle> owned) {
            if (owned.isEmpty()) return;
            Thread.ofVirtual().name("ja-tcp-observation-close").start(() ->
                    owned.forEach(handle -> releaseObservation(handle.kind(), handle.handle())));
        }
    }
}
