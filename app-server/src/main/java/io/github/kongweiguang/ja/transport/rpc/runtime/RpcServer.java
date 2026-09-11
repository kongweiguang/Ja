// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.transport.rpc.handler.ConfigurationHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.AttachmentHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.AttachmentPreviewHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.HealthShutdownHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.GoalHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.InteractionHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.SettingsCatalogHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.TaskHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.ThreadHistoryHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.ThreadCompactionHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.TurnApprovalHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.WorkspaceHandler;
import io.github.kongweiguang.ja.transport.rpc.handler.WorkspacePathSearchHandler;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.BoundedVirtualExecutor;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseCoordinator;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理单个 JA-RPC 连接代际的有界 JSONL 传输边界。
 *
 * <p>读取线程只负责校验与分发，窄职责 Handler 负责请求适配，应用及基础设施资源仍由 Solon
 * 组合根持有；连接不隐式保存当前 Workspace、Thread 或 Provider/Model，也不提供兼容分发。</p>
 */
public final class RpcServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RpcServer.class);
    static final int MAX_IN_FLIGHT = 64;
    static final int MAX_INBOUND_QUEUE = 256;

    private final InputStream input;
    private final JaRpcCodec codec = new JaRpcCodec();
    private final StdioWriter writer;
    private final RpcSession session;
    private final ThreadHistoryHandler threadHistory;
    private final RpcRouter router;
    private final BoundedVirtualExecutor requests;
    private final Set<String> inFlightIds = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> handlerCompletions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final DeadlineCloseCoordinator closeCoordinator = new DeadlineCloseCoordinator();
    private final Object ingressMonitor = new Object();
    private final Runnable beforeDispatch;
    private int ingressInFlight;

    /**
     * 直接绑定 Java 配置所有者，避免 sidecar 依赖 Rust 生成的配置快照。
     */
    public RpcServer(InputStream input, OutputStream output, SidecarConfiguration configuration,
                     RpcServicesFactory factory, ConfigurationUseCase configurationUseCase) {
        this(input, output, configuration, Clock.systemUTC(), factory, configurationUseCase,
                () -> { }, configuration.runtimeGeneration());
    }

    /**
     * 为测试和组合根注入时钟，同时保持传输资源所有权不变。
     */
    RpcServer(InputStream input, OutputStream output, SidecarConfiguration configuration, Clock clock,
              RpcServicesFactory factory, ConfigurationUseCase configurationUseCase) {
        this(input, output, configuration, clock, factory, configurationUseCase, () -> {
        }, configuration.runtimeGeneration());
    }

    /**
     * 在测试中冻结帧读取与分发边界，且不改变生产 I/O 路径。
     */
    RpcServer(InputStream input, OutputStream output, SidecarConfiguration configuration, Clock clock,
              RpcServicesFactory factory, ConfigurationUseCase configurationUseCase,
              Runnable beforeDispatch) {
        this(input, output, configuration, clock, factory, configurationUseCase, beforeDispatch, 1);
    }

    /** 为事件投影测试固定非默认进程代际，使 generation 断言保持确定。 */
    RpcServer(InputStream input, OutputStream output, SidecarConfiguration configuration, Clock clock,
              RpcServicesFactory factory, ConfigurationUseCase configurationUseCase,
              Runnable beforeDispatch, long runtimeGeneration) {
        this.input = Objects.requireNonNull(input, "input");
        this.writer = new StdioWriter(Objects.requireNonNull(output, "output"), codec.mapper(),
                JaRpcCodec.DEFAULT_MAX_FRAME_BYTES);
        this.session = new RpcSession(configuration, codec.mapper(), clock, writer, factory,
                configurationUseCase, runtimeGeneration);
        this.beforeDispatch = Objects.requireNonNull(beforeDispatch, "beforeDispatch");
        this.threadHistory = new ThreadHistoryHandler(session);
        this.router = new RpcRouter(List.of(new HandshakeHandler(session), new WorkspaceHandler(session),
                new WorkspacePathSearchHandler(session),
                threadHistory, new ThreadCompactionHandler(session),
                new AttachmentHandler(session), new AttachmentPreviewHandler(session),
                new TurnApprovalHandler(session), new TaskHandler(session), new GoalHandler(session),
                new InteractionHandler(session),
                new SettingsCatalogHandler(session), new ConfigurationHandler(session),
                new HealthShutdownHandler(session)));
        this.requests = new BoundedVirtualExecutor(
                "ja-rpc-request-", 8, Math.addExact(8, MAX_INBOUND_QUEUE));
    }

    /**
     * 独占运行读取循环，直至 EOF、关闭、帧校验失败或输出代际失效。
     */
    public int run() {
        int exit = 0;
        try {
            while (beginIngress()) {
                try {
                    Optional<JaRpcCodec.Frame> next = codec.read(input);
                    if (next.isEmpty()) break;
                    beforeDispatch.run();
                    dispatch(next.orElseThrow());
                } finally {
                    endIngress();
                }
            }
        } catch (IOException | RuntimeException failure) {
            exit = 1;
        } finally {
            try {
                close(ShutdownDeadline.start());
            } catch (RuntimeException closeFailure) {
                exit = 1;
            }
        }
        return exit;
    }

    /**
     * 按封闭帧角色分发输入；客户端响应不存在对应的服务端请求，因此直接拒绝。
     */
    private void dispatch(JaRpcCodec.Frame frame) {
        switch (frame) {
            case JaRpcCodec.Request request -> dispatchRequest(request);
            case JaRpcCodec.Notification notification -> dispatchNotification(notification);
            case JaRpcCodec.Response ignored -> throw JaRpcException.invalidFrame();
        }
    }

    /**
     * 在读取通道同步处理唯一的 initialized 挑战，避免普通工作线程重排握手状态。
     */
    private void dispatchNotification(JaRpcCodec.Notification notification) {
        if (!"runtime/initialized".equals(notification.method())) throw JaRpcException.invalidFrame();
        RpcParams.requireExact(notification.params(), "readyToken");
        session.ready(RpcParams.text(notification.params(), "readyToken", 32, false));
    }

    /**
     * 保持 initialize 与 shutdown 严格有序，其余请求通过有界工作线程分发。
     */
    private void dispatchRequest(JaRpcCodec.Request request) {
        if (!inFlightIds.add(request.id())) {
            trackResponse(writer.error(request.id(), JaRpcException.invalidFrame()), null);
            return;
        }
        if ("runtime/initialize".equals(request.method()) || "runtime/shutdown".equals(request.method())) {
            execute(request);
            return;
        }
        if (!session.ready()) {
            trackResponse(completeError(request.id(), JaRpcException.of(JaErrorCatalog.NOT_INITIALIZED,
                    "runtime is not initialized")), request.id());
            return;
        }
        if (inFlightIds.size() > MAX_IN_FLIGHT) {
            trackResponse(completeError(request.id(), JaRpcException.of(JaErrorCatalog.QUEUE_FULL,
                    "request capacity is exhausted")), request.id());
            return;
        }
        try {
            requests.execute(() -> execute(request));
        } catch (RejectedExecutionException rejected) {
            trackResponse(completeError(request.id(), JaRpcException.of(JaErrorCatalog.QUEUE_FULL,
                    "request queue is full")), request.id());
        }
    }

    /**
     * 执行单个 Handler 阶段，并仅在响应或错误完成 flush 后释放关联标识。
     */
    private void execute(JaRpcCodec.Request request) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        handlerCompletions.add(completion);
        try {
            CompletionStage<ObjectNode> stage = router.dispatch(request.method(), request.params());
            stage.whenComplete((result, failure) -> {
                CompletionStage<Void> response = failure == null
                        ? completeResult(request.id(), result)
                        : completeError(request.id(), mapFailure(failure));
                settleResponse(request.id(), response, completion);
                if ("runtime/shutdown".equals(request.method())) accepting.set(false);
            });
        } catch (RuntimeException failure) {
            settleResponse(request.id(), completeError(request.id(), mapFailure(failure)), completion);
        }
    }

    /**
     * 写入成功响应；关联标识由统一终态路径在 flush 后释放。
     */
    private CompletionStage<Void> completeResult(String id, ObjectNode result) {
        try {
            return writer.response(id, Objects.requireNonNull(result, "result"));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 写入稳定脱敏错误；关联标识由统一终态路径在 flush 后释放。
     */
    private CompletionStage<Void> completeError(String id, JaRpcException failure) {
        try {
            return writer.error(id, failure);
        } catch (RuntimeException writeFailure) {
            return CompletableFuture.failedFuture(writeFailure);
        }
    }

    /**
     * 跟踪响应 flush，使关闭流程不能在已准入回复收敛前释放 Writer。
     */
    private void trackResponse(CompletionStage<Void> response, String id) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        handlerCompletions.add(completion);
        settleResponse(id, response, completion);
    }

    /**
     * 仅在 stdout 投影成功后完成 Handler，否则记录投影失败并关闭连接。
     */
    private void settleResponse(String id, CompletionStage<Void> response,
                                CompletableFuture<Void> completion) {
        response.whenComplete((ignored, failure) -> {
            if (id != null) inFlightIds.remove(id);
            if (failure == null) completion.complete(null);
            else {
                session.failProjection(failure);
                completion.completeExceptionally(failure);
            }
            handlerCompletions.remove(completion);
        });
    }

    /**
     * 将实现异常映射为有界稳定类别，禁止原始消息、路径、SQL 或 Secret 越过传输边界。
     */
    private static JaRpcException mapFailure(Throwable source) {
        Throwable failure = source;
        while ((failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException)
               && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof JaRpcException rpc) return rpc;
        if (failure instanceof TurnUseCase.TurnResumeException resume) {
            return switch (resume.failure()) {
                case TURN_NOT_RESUMABLE -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                        "turn is not resumable");
                case TURN_RESUME_ORDER_CONFLICT -> JaRpcException.of(
                        JaErrorCatalog.TURN_RESUME_ORDER_CONFLICT,
                        "an earlier turn must be resolved first");
            };
        }
        if (failure instanceof TurnUseCase.TurnCancellationException cancellation) {
            return switch (cancellation.failure()) {
                case TURN_NOT_FOUND -> JaRpcException.of(JaErrorCatalog.TURN_NOT_FOUND,
                        "turn is unavailable");
                case CONFLICT -> JaRpcException.of(JaErrorCatalog.CONFLICT,
                        "thread revision changed");
            };
        }
        if (failure instanceof StorageException persistence) {
            // 只记录异常类型与编译期位置，定位 Native 持久化映射失败而不输出 SQL、路径或用户载荷。
            Throwable root = persistence;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String origin = java.util.Arrays.stream(root.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith("io.github.kongweiguang.ja."))
                    .findFirst().map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                    .orElse("unknown");
            LOGGER.warn("JA-RPC storage rejected category={} causeType={} origin={}",
                    persistence.code(), root.getClass().getName(), origin);
            return mapPersistenceFailure(persistence.code());
        }
        if (failure instanceof ConfigurationError configuration) {
            return mapConfigurationFailure(configuration.code());
        }
        if (failure instanceof RejectedExecutionException rejected) {
            String category = rejected.getMessage();
            if ("THREAD_QUEUE_FULL".equals(category)) {
                return JaRpcException.of(JaErrorCatalog.THREAD_QUEUE_FULL, "thread queue is full");
            }
            if ("SHUTTING_DOWN".equals(category)) {
                return JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
            }
            return JaRpcException.of(JaErrorCatalog.QUEUE_FULL, "turn queue is full");
        }
        if (failure instanceof IllegalArgumentException) {
            JaRpcException invalid = JaRpcException.invalidParams();
            // 准入不变量也可能抛参数异常；仅记录编译期代码位置，既能定位真实故障，又不泄露请求正文。
            String origin = java.util.Arrays.stream(failure.getStackTrace())
                    .filter(frame -> frame.getClassName().startsWith("io.github.kongweiguang.ja."))
                    .findFirst().map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                    .orElse("unknown");
            LOGGER.warn("JA-RPC validation rejected errorId={} origin={}", invalid.errorId(), origin);
            return invalid;
        }
        JaRpcException mapped = JaRpcException.of(JaErrorCatalog.INTERNAL_ERROR, "runtime request failed");
        // 只记录关联 ID 与异常类型，既能定位真实环境故障，也不让异常消息中的路径、SQL 或 Secret 落盘。
        Throwable cause = failure.getCause();
        String origin = java.util.Arrays.stream(failure.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("io.github.kongweiguang.ja."))
                .findFirst().map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                .orElse("unknown");
        LOGGER.error("Unexpected JA-RPC failure errorId={} type={} causeType={} origin={}",
                mapped.errorId(), failure.getClass().getName(),
                cause == null ? "none" : cause.getClass().getName(), origin);
        return mapped;
    }

    /**
     * 将持久化类别映射到冻结错误目录，避免泄漏 SQL、路径或请求载荷。
     */
    static JaRpcException mapPersistenceFailure(StorageException.Code code) {
        Objects.requireNonNull(code, "code");
        return switch (code) {
            case CAS_CONFLICT -> JaRpcException.of(JaErrorCatalog.CONFLICT,
                    "thread revision changed");
            case INVALID_CONFIGURATION -> JaRpcException.of(JaErrorCatalog.CONFIG_INVALID,
                    "runtime configuration is invalid");
            case INSTANCE_LOCKED -> JaRpcException.of(JaErrorCatalog.DATA_DIR_IN_USE,
                    "runtime data directory is in use");
            case CLOSED -> JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN,
                    "runtime is shutting down");
            case QUEUE_FULL -> JaRpcException.of(JaErrorCatalog.QUEUE_FULL,
                    "storage queue is full");
            case QUEUE_TIMEOUT -> JaRpcException.of(JaErrorCatalog.REQUEST_DEADLINE_EXCEEDED,
                    "storage operation timed out");
            case STORAGE_CONFLICT -> JaRpcException.of(JaErrorCatalog.STORAGE_CONFLICT,
                    "database locations conflict");
            case INVALID_STATE -> JaRpcException.of(JaErrorCatalog.INVALID_STATE,
                    "runtime state is invalid");
            case IO, TRANSACTION, WRITER_CLOSE_UNCONFIRMED, NOT_FOUND ->
                    JaRpcException.of(JaErrorCatalog.STORAGE_UNAVAILABLE,
                            "runtime storage is unavailable");
        };
    }

    /**
     * 将配置所有者错误映射为 Wire 类别，禁止 TOML 与认证实现细节外泄。
     */
    static JaRpcException mapConfigurationFailure(ConfigurationError.Code code) {
        Objects.requireNonNull(code, "code");
        return switch (code) {
            case CAS_CONFLICT -> JaRpcException.of(JaErrorCatalog.CONFIG_CONFLICT,
                    "configuration version changed");
            case CORRUPT_CONFIG, CORRUPT_AUTH -> JaRpcException.of(JaErrorCatalog.CONFIG_CORRUPTED,
                    "configuration is corrupt");
            case MISSING_PROVIDER_OR_MODEL -> JaRpcException.of(
                    JaErrorCatalog.PROVIDER_OR_MODEL_NOT_FOUND,
                    "provider or model is unavailable");
            case MISSING_CREDENTIAL -> JaRpcException.of(JaErrorCatalog.CREDENTIAL_MISSING,
                    "credential is unavailable");
            case UNTRUSTED_WORKSPACE -> JaRpcException.of(JaErrorCatalog.WORKSPACE_TRUST_REQUIRED,
                    "workspace trust is required");
            case STORAGE_CONFLICT -> JaRpcException.of(JaErrorCatalog.STORAGE_CONFLICT,
                    "database locations conflict");
            case IO_FAILURE -> JaRpcException.of(JaErrorCatalog.STORAGE_UNAVAILABLE,
                    "configuration storage is unavailable");
            case INVALID_CWD, INVALID_ARGUMENT, INVALID_DOCUMENT, LIMIT_ESCALATION, LITERAL_SECRET ->
                    JaRpcException.invalidParams();
        };
    }

    /**
     * 启动唯一关闭级联，并让重复或并发调用者共享同一完成结果。
     */
    @Override
    public void close() {
        close(ShutdownDeadline.start());
    }

    /**
     * 在同一绝对期限内停止入站、排空 Handler、静默运行时并最终关闭 stdout。
     */
    public void close(ShutdownDeadline deadline) {
        closeCoordinator.close(deadline, "rpc server", this::closeOwnedConnection);
    }

    /**
     * 保持连接资源的原有关闭顺序，并在入站屏障失败后停止释放下游所有者。
     */
    private void closeOwnedConnection(ShutdownDeadline deadline) {
        RuntimeException failure = null;
        accepting.set(false);
        boolean ingressQuiesced = false;
        try {
            awaitIngress(deadline);
            requests.shutdown();
            awaitRequests(deadline);
            awaitHandlers(deadline);
            ingressQuiesced = true;
        } catch (RuntimeException closeFailure) {
            /*
             * 请求执行器、handler stage 和 stdout flush 是同一条 ingress 所有权边界。
             * 任一环节未完成就不能把 session/runtime 当作安全可关闭；否则仍运行的
             * Turn 可能访问已释放的 lease、SQLite 或 provider 资源。此处只发布稳定的
             * forced 标记并把清理交给外层 Job Object，不能刷新 deadline 或继续 close。
             */
            failure = forcedIngressFailure(closeFailure);
            requests.shutdownNow();
            try {
                session.failProjection(failure);
            } catch (RuntimeException projectionFailure) {
                failure.addSuppressed(projectionFailure);
            }
        }
        if (ingressQuiesced) {
            boolean changeSetReadsQuiesced = false;
            try {
                threadHistory.close(deadline);
                changeSetReadsQuiesced = true;
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (changeSetReadsQuiesced) {
                try {
                    session.close(deadline);
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
                try {
                    writer.close(deadline);
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) throw failure;
    }

    /**
     * 将入站排空失败转换为 Rust Job Object 使用的进程级强制终止标记；排空屏障破坏后已无法
     * 证明响应顺序或运行时资源所有权，因此不得再把 Handler 或 flush 失败伪装为 Wire 错误。
     */
    private static ShutdownDeadline.ForcedTerminationException forcedIngressFailure(
            RuntimeException failure) {
        if (failure instanceof ShutdownDeadline.ForcedTerminationException forced) return forced;
        return ShutdownDeadline.forced("forced termination: rpc ingress did not quiesce", failure);
    }

    /**
     * 原子准入读取通道，避免关闭流程遗漏已读取但尚未分发的帧。
     */
    private boolean beginIngress() {
        synchronized (ingressMonitor) {
            if (!accepting.get() || session.closing()) return false;
            ingressInFlight++;
            return true;
        }
    }

    /**
     * 释放读取到分发许可，并唤醒等待静默的唯一关闭所有者。
     */
    private void endIngress() {
        synchronized (ingressMonitor) {
            if (ingressInFlight <= 0) throw new IllegalStateException("rpc ingress permit underflow");
            ingressInFlight--;
            ingressMonitor.notifyAll();
        }
    }

    /**
     * 在共享期限内等待完整的读取到分发临界区收敛。
     */
    private void awaitIngress(ShutdownDeadline deadline) {
        synchronized (ingressMonitor) {
            while (ingressInFlight > 0) {
                if (deadline.expired()) {
                    throw ShutdownDeadline.forced("forced termination: rpc ingress read/dispatch barrier", null);
                }
                try {
                    long remaining = deadline.remainingMillis();
                    if (remaining <= 0) {
                        throw ShutdownDeadline.forced(
                                "forced termination: rpc ingress read/dispatch barrier", null);
                    }
                    ingressMonitor.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ShutdownDeadline.forced("rpc ingress barrier interrupted", interrupted);
                }
            }
        }
    }

    /**
     * 使用既有关闭预算等待有界请求线程，禁止创建第二个期限。
     */
    private void awaitRequests(ShutdownDeadline deadline) {
        try {
            long remaining = deadline.remainingMillis();
            if (!requests.awaitTermination(remaining, TimeUnit.MILLISECONDS)) {
                requests.shutdownNow();
                throw ShutdownDeadline.forced("rpc request executor timed out", null);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            requests.shutdownNow();
            throw ShutdownDeadline.forced("rpc request executor interrupted", interrupted);
        }
    }

    /**
     * 等待入站停止前已准入的全部响应投影完成。
     */
    private void awaitHandlers(ShutdownDeadline deadline) {
        while (!handlerCompletions.isEmpty()) {
            CompletableFuture<?>[] pending = handlerCompletions.toArray(CompletableFuture<?>[]::new);
            deadline.await(CompletableFuture.allOf(pending), "rpc handler responses");
        }
    }

}
