// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import io.github.kongweiguang.ja.transport.rpc.handler.ApprovalCompletions;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.TurnEventWireMapper;
import io.github.kongweiguang.ja.transport.rpc.protocol.ContextCompactionEventWireMapper;
import io.github.kongweiguang.ja.transport.rpc.protocol.ThreadMetadataEventWireMapper;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEvent;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseCoordinator;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 保存当前连接代际状态，供窄职责 Handler 共享且不暴露全局活动资源标识。 */
public final class RpcSession implements AutoCloseable {
    private static final AtomicLong NEXT_RUNTIME_GENERATION = new AtomicLong(1);

    private final SidecarConfiguration configuration;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final StdioWriter writer;
    private final RpcServicesFactory factory;
    private final long runtimeGeneration;
    private final ConfigurationUseCase configurationUseCase;
    private final ApprovalCompletions approvalCompletions;
    private final CancellationSource cancellation = new CancellationSource();
    private final Map<String, TurnNotificationContext> turnNotificationContexts = new ConcurrentHashMap<>();
    private final String serverInstanceId = "srv_ja_" + UUID.randomUUID().toString().replace("-", "");
    private final AtomicLong eventIds = new AtomicLong();
    private final AtomicLong notificationSequences = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final DeadlineCloseCoordinator closeCoordinator = new DeadlineCloseCoordinator();
    private volatile WorkspaceUseCase workspaces;
    private volatile ThreadUseCase threads;
    private volatile TurnUseCase turns;
    private volatile ContextCompactionUseCase compactions;
    private volatile ApprovalUseCase approvalUseCase;
    private volatile CatalogUseCase catalog;
    private volatile AttachmentUseCase attachments;
    private volatile DeadlineCloseable lifecycle;
    private volatile String readyToken;
    private volatile boolean initialized;
    private volatile boolean ready;

    /**
     * 将 Java 配置 Owner 与 Agent 运行时 Factory 分开绑定，确保尚未配置 Provider 或 Credential 时
     * 仍可在启动阶段读取 Settings。
     */
    public RpcSession(SidecarConfiguration configuration, ObjectMapper mapper, Clock clock,
                      StdioWriter writer, RpcServicesFactory factory, ConfigurationUseCase configurationUseCase) {
        this(configuration, mapper, clock, writer, factory, configurationUseCase,
                NEXT_RUNTIME_GENERATION.getAndIncrement());
    }

    /** 为测试与组合边界固定非默认进程代际，使生命周期夹具可以稳定断言。 */
    public RpcSession(SidecarConfiguration configuration, ObjectMapper mapper, Clock clock,
                      StdioWriter writer, RpcServicesFactory factory, ConfigurationUseCase configurationUseCase,
                      long runtimeGeneration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.configurationUseCase = Objects.requireNonNull(configurationUseCase, "configurationUseCase");
        if (runtimeGeneration < 1 || runtimeGeneration > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("invalid runtime generation");
        }
        this.runtimeGeneration = runtimeGeneration;
        this.approvalCompletions = new ApprovalCompletions(clock, 64, 256);
    }

    /** 打开空启动代际；配置仅在握手后由 Java 端解析。 */
    public synchronized void initialize() {
        if (initialized) throw JaRpcException.of(JaErrorCatalog.ALREADY_INITIALIZED,
                "runtime is already initialized");
        RpcServiceBindings opened;
        try {
            opened = factory.open(mapper);
        } catch (JaRpcException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw JaRpcException.of(JaErrorCatalog.CONFIG_INVALID, "runtime configuration is invalid");
        }
        workspaces = opened.workspaces();
        threads = opened.threads();
        turns = opened.turns();
        compactions = opened.compactions();
        approvalUseCase = opened.approvals();
        catalog = opened.catalog();
        attachments = opened.attachments();
        lifecycle = opened.lifecycle();
        initialized = true;
        notifyRuntime("starting", "initialize").join();
    }

    /** 应用唯一一次客户端 ready 挑战，其他通知不得改变运行时状态。 */
    synchronized void ready(String token) {
        if (!initialized || ready || token == null || !token.matches("[0-9a-f]{32}")) {
            throw JaRpcException.invalidFrame();
        }
        readyToken = token;
        ready = true;
        notifyRuntime("ready", null).join();
    }

    /**
     * 在 Turn 入队前冻结通知所需的工作区和线程身份；这些信息不从路径或配置快照反推，
     * 从而保证流式 delta 与持久事件使用同一条公开关联链。
     */
    public void registerTurnNotificationContext(String turnId, String workspaceId, String threadId,
                                                long initialThreadRevision) {
        TurnNotificationContext context = new TurnNotificationContext(workspaceId, threadId,
                initialThreadRevision);
        if (turnNotificationContexts.putIfAbsent(requireIdentifier(turnId, "turn_"), context) != null) {
            throw JaRpcException.of(JaErrorCatalog.INVALID_STATE, "Turn notification context is duplicated");
        }
    }

    /** 放弃尚未产生终态事件的 Turn 通知上下文；运行时租约由 TurnService 独占释放。 */
    public void abandonTurnNotification(String turnId) {
        turnNotificationContexts.remove(turnId);
    }

    /** 返回配置入站用例，transport 只能通过不可变 JDK 投影读写配置。 */
    public ConfigurationUseCase configurationUseCase() {
        return configurationUseCase;
    }

    /** 返回宿主发布的进程根目录，仅用于 Java 所有的通用工作区。 */
    SidecarConfiguration processConfiguration() {
        return configuration;
    }

    /** 创建或重开 Java 数据目录内的唯一通用工作区。 */
    synchronized Workspace ensureGeneralWorkspace() {
        requireReady();
        return workspaces().openGeneralWorkspace();
    }

    /** 判断规范工作区根目录是否为 Java 所有的通用根目录。 */
    boolean isGeneralWorkspace(Path root) {
        return workspaces().isGeneralWorkspace(root);
    }

    /** 在配置或信任变更提交后重建所有已注册工作区的 Catalog。 */
    public void refreshPreparedWorkspaces() {
        requireReady();
        workspaces().refreshPreparedWorkspaces();
    }

    /** 返回工作区域入站端口，调用方不能通过 transport service locator 访问其它域。 */
    public WorkspaceUseCase workspaces() {
        requireReady();
        return Objects.requireNonNull(workspaces, "workspace use case");
    }

    /** 返回 Thread 入站端口。 */
    public ThreadUseCase threads() {
        requireReady();
        return Objects.requireNonNull(threads, "thread use case");
    }

    /** 返回 Turn 入站端口。 */
    public TurnUseCase turns() {
        requireReady();
        return Objects.requireNonNull(turns, "turn use case");
    }

    /** 返回空闲 Thread 手动压缩端口，Handler 不得取得 Repository 或 Provider。 */
    public ContextCompactionUseCase compactions() {
        requireReady();
        return Objects.requireNonNull(compactions, "context compaction use case");
    }

    /** 返回连接生命周期只读取消令牌，使慢请求在关闭时与 Provider IO 同步收口。 */
    public CancellationToken cancellationToken() {
        return cancellation;
    }

    /** 返回审批领域端口，避免 handler 反向取得整个组合图。 */
    public ApprovalUseCase approvalUseCase() {
        requireReady();
        return Objects.requireNonNull(approvalUseCase, "approval use case");
    }

    /** 返回 Catalog 入站端口。 */
    public CatalogUseCase catalog() {
        requireReady();
        return Objects.requireNonNull(catalog, "catalog use case");
    }

    /** 返回受管附件入站端口；Handler 只能提交 opaque ingress/attachment identity。 */
    public AttachmentUseCase attachments() {
        requireReady();
        return Objects.requireNonNull(attachments, "attachment use case");
    }

    /** 返回连接级审批提交关联器，仅用于普通审批响应。 */
    public ApprovalCompletions approvals() {
        return approvalCompletions;
    }

    /** 返回由严格帧 Codec 配置的 ObjectMapper。 */
    public ObjectMapper mapper() {
        return mapper;
    }

    /** 返回 Handler CAS 与事件时间戳共享的注入时钟。 */
    public Clock clock() {
        return clock;
    }

    /** 返回复制到每条语义通知的进程代际身份。 */
    public String serverInstanceId() {
        return serverInstanceId;
    }

    /** 返回 ready 挑战是否完成，用于请求准入判断。 */
    boolean ready() {
        return ready;
    }

    /** 返回关闭流程是否已停止请求与 Turn 准入。 */
    boolean closing() {
        return closing.get();
    }

    /** 在返回关闭确认前停止新的 Turn 准入。 */
    public boolean beginShutdown(String reason) {
        if (!closing.compareAndSet(false, true)) return false;
        // TurnUseCase 由连接级生命周期统一持有，此处只借用端口停止准入，不能局部关闭。
        @SuppressWarnings("PMD.CloseResource")
        TurnUseCase currentTurns = turns;
        if (currentTurns != null) currentTurns.stopAccepting();
        /* 控制通道 FIFO 保证状态先于关闭响应写出，同时不让 Handler 阻塞 stdout；
         * 外层 RpcServer 使用同一绝对期限等待两者完成。 */
        try {
            notifyRuntime("shutting_down", reason);
        } catch (RuntimeException failure) {
            writer.poison(failure);
            throw failure;
        }
        return true;
    }

    /**
     * 作为 handler 子包唯一的事件发布出口公开，避免各 Handler 直接持有 writer 或复制审批完成跟踪；
     * 只有已经提交的 Turn 事实允许越过该边界。
     */
    public CompletableFuture<Void> publish(TurnEvent event) {
        CompletableFuture<Void> published;
        try {
            TurnEventWireMapper.WireEvent wire = new TurnEventWireMapper(mapper, serverInstanceId).map(event);
            /* WireEvent 每次读取都会深拷贝；必须固定本次写出的唯一副本后再补全连接元数据，
             * 否则 sequence、generation 与 workspaceId 会只写进随后被丢弃的临时节点。 */
            ObjectNode wireParams = wire.params();
            enrichAgentNotification(event, wireParams);
            if (event instanceof TurnEvent.ApprovalRequested requested) {
                approvalCompletions.requested(requested.approvalId(), requested.context().threadId(),
                        requested.context().turnId(), requested.expiresAt());
            }
            published = wire.method().equals("assistant/text-delta")
                    || wire.method().equals("assistant/reasoning-summary-delta")
                    ? writer.delta(wire.method(), wireParams)
                    : writer.notification(wire.method(), wireParams);
        } catch (Throwable failure) {
            handlePublishFailure(event, failure);
            return CompletableFuture.failedFuture(failure);
        }
        /*
         * 即使 Writer 改为直接完成通知 Future，也不在其所有者线程执行审批关联逻辑；
         * 后续阶段可能完成普通审批响应，其同步 flush 绝不能运行在 stdout 所有者线程。
         */
        CompletableFuture<Void> observed = new CompletableFuture<>();
        published.whenComplete((ignored, failure) -> {
            Runnable callback = () -> {
                try {
                    if (failure != null) handlePublishFailure(event, failure);
                    else if (event instanceof TurnEvent.ApprovalResolved resolved) {
                        approvalCompletions.committed(resolved.approvalId(),
                                resolved.context().threadRevision(),
                                resolved.decision().name().toLowerCase(Locale.ROOT));
                    }
                    if (failure == null && event instanceof TurnEvent.Terminal terminal) {
                        approvalCompletions.terminal(terminal.context().turnId());
                        turnNotificationContexts.remove(terminal.context().turnId());
                    } else if (failure != null && event instanceof TurnEvent.Terminal terminal) {
                        turnNotificationContexts.remove(terminal.context().turnId());
                    }
                    if (failure == null) observed.complete(null);
                    else observed.completeExceptionally(failure);
                } catch (Throwable callbackFailure) {
                    if (failure != null && callbackFailure != failure) callbackFailure.addSuppressed(failure);
                    observed.completeExceptionally(callbackFailure);
                }
            };
            try {
                writer.completionExecutor().execute(callback);
            } catch (RuntimeException rejected) {
                handlePublishFailure(event, rejected);
                observed.completeExceptionally(rejected);
            }
        });
        return observed;
    }

    /**
     * 发布 Thread 级压缩生命周期；事件自带 Workspace/Thread 身份，不依赖活动 Turn registry，
     * 因而手动空闲压缩与自动 Turn 压缩使用完全相同的 wire 路径。
     */
    public CompletableFuture<Void> publish(ContextCompactionEvent event) {
        try {
            ContextCompactionEventWireMapper.WireEvent wire =
                    new ContextCompactionEventWireMapper(mapper, serverInstanceId).map(event);
            ObjectNode params = wire.params();
            params.put("sequence", nextNotificationSequence()).put("generation", runtimeGeneration);
            CompletableFuture<Void> published = writer.notification(wire.method(), params);
            published.whenComplete((ignored, failure) -> {
                if (failure != null) failProjection(failure);
            });
            return published;
        } catch (Throwable failure) {
            failProjection(failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 发布独立于 Turn 终态的标题提交事实；公共事件身份只在连接边界分配，避免后台标题任务
     * 猜测 stdout 顺序或复用已经结束的 Turn 通知上下文。
     */
    public CompletableFuture<Void> publish(ThreadMetadataEvent event) {
        try {
            ObjectNode params = new ThreadMetadataEventWireMapper(mapper, serverInstanceId).map(event);
            addNotificationMetadata(params, "thread_metadata");
            CompletableFuture<Void> published = writer.notification("thread/metadata-changed", params);
            published.whenComplete((ignored, failure) -> {
                if (failure != null) failProjection(failure);
            });
            return published;
        } catch (Throwable failure) {
            failProjection(failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** 返回同时支持 Turn 与 Thread 压缩事件的生产 Sink，避免 method reference 落入测试默认 no-op。 */
    public TurnEventSink eventSink() {
        return new TurnEventSink() {
            /** 普通 Turn 事件沿用持久/草稿发布路径。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(TurnEvent event) {
                return RpcSession.this.publish(event);
            }

            /** 压缩生命周期沿用 Thread 级发布路径。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(ContextCompactionEvent event) {
                return RpcSession.this.publish(event);
            }

            /** 自动或人工标题提交沿用连接级 Thread 元数据通知路径。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(ThreadMetadataEvent event) {
                return RpcSession.this.publish(event);
            }
        };
    }

    /** 响应或事件 flush 失败后标记连接投影失败，但不修改持久状态。 */
    void failProjection(Throwable failure) {
        closing.set(true);
        writer.poison(failure);
    }

    /** 标记投影失败并使 stdout 中毒，不尝试第二次提交终态。 */
    private void handlePublishFailure(TurnEvent event, Throwable failure) {
        closing.set(true);
        writer.poison(failure);
        if (event instanceof TurnEvent.Terminal terminal) {
            turnNotificationContexts.remove(terminal.context().turnId());
        }
    }

    /**
     * 在唯一 RPC 出站边界分配进程级单调 sequence，并为流式草稿补齐不可恢复的事件身份。
     * 持久事件保留存储层生成的 eventId、时间和 revision，避免传输层伪造领域顺序。
     */
    private void enrichAgentNotification(TurnEvent event, ObjectNode params) {
        String turnId = event instanceof TurnEvent.TextDelta delta ? delta.turnId()
                : event instanceof TurnEvent.ReasoningSummaryDelta delta ? delta.turnId()
                : event.context().turnId();
        TurnNotificationContext turn = turnNotificationContexts.get(turnId);
        if (turn == null) {
            throw JaRpcException.of(JaErrorCatalog.INVALID_STATE, "Turn notification context is unavailable");
        }
        TurnEvent.Context durable = event.context();
        if (durable != null) turn.observeRevision(durable.threadRevision());
        params.put("serverInstanceId", serverInstanceId);
        if (!params.has("eventId")) params.put("eventId", nextEventId("stream"));
        params.put("sequence", nextNotificationSequence());
        if (!params.has("occurredAt")) params.put("occurredAt", clock.instant().toString());
        params.put("generation", runtimeGeneration);
        params.put("workspaceId", turn.workspaceId());
        if (!params.has("threadId")) params.put("threadId", turn.threadId());
        if (!params.has("turnId")) params.put("turnId", turnId);
        if (!params.has("threadRevision")) params.put("threadRevision", turn.threadRevision());
    }

    /** 配置写入提交后仅发送脱敏的 scope、workspace 与 version 事实。 */
    public void notifyConfigChanged(String scope, String workspaceId, String version) {
        if (!("user".equals(scope) || "project".equals(scope))
                || version == null || version.isBlank() || version.length() > 256) {
            throw JaRpcException.invalidParams();
        }
        if ("project".equals(scope)
                && (workspaceId == null || !workspaceId.startsWith("ws_"))) {
            throw JaRpcException.of(JaErrorCatalog.WORKSPACE_TRUST_REQUIRED,
                    "workspace trust is required");
        }
        if ("user".equals(scope) && workspaceId != null) throw JaRpcException.invalidParams();
        ObjectNode params = mapper.createObjectNode().put("scope", scope).put("version", version);
        if (workspaceId != null) params.put("workspaceId", workspaceId);
        addNotificationMetadata(params, "configuration");
        writer.notification("configuration/changed", params);
    }

    /** 发送有界生命周期事件，不包含配置、路径或 Secret。 */
    CompletableFuture<Void> notifyRuntime(String status, String reason) {
        ObjectNode params = mapper.createObjectNode();
        addNotificationMetadata(params, "runtime");
        params.put("status", status);
        if ("ready".equals(status)) params.put("readyToken", readyToken);
        if (reason != null && !reason.isBlank()) params.put("reason", boundedReason(reason));
        return writer.notification("runtime/status-changed", params);
    }

    /** 为非 Turn 语义通知写入同一组公共元数据，确保跨类型事件可按 sequence 排序。 */
    private void addNotificationMetadata(ObjectNode params, String category) {
        params.put("serverInstanceId", serverInstanceId);
        params.put("eventId", nextEventId(category));
        params.put("sequence", nextNotificationSequence());
        params.put("occurredAt", clock.instant().toString());
        params.put("generation", runtimeGeneration);
    }

    /** 分配当前进程实例内唯一且不含用户数据的事件标识。 */
    private String nextEventId(String category) {
        return "evt_" + category + "_" + eventIds.incrementAndGet();
    }

    /** 使用溢出检测分配单调通知序号，绝不在同一实例内回绕。 */
    private long nextNotificationSequence() {
        long sequence = notificationSequences.incrementAndGet();
        if (sequence < 1) throw new IllegalStateException("notification sequence overflow");
        return sequence;
    }

    /** 校验内部关联标识，防止无效键污染连接级状态。 */
    private static String requireIdentifier(String value, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 108
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /** 保存流式通知无法自行携带的稳定 Turn 关联信息，并只允许 revision 单调前进。 */
    private static final class TurnNotificationContext {
        private final String workspaceId;
        private final String threadId;
        private final AtomicLong threadRevision;

        /** 冻结工作区和线程身份，revision 使用原子值承接持久事件推进。 */
        private TurnNotificationContext(String workspaceId, String threadId, long initialThreadRevision) {
            this.workspaceId = requireIdentifier(workspaceId, "ws_");
            this.threadId = requireIdentifier(threadId, "thr_");
            if (initialThreadRevision < 0) throw JaRpcException.invalidParams();
            this.threadRevision = new AtomicLong(initialThreadRevision);
        }

        /** 返回当前 Turn 的工作区身份。 */
        private String workspaceId() {
            return workspaceId;
        }

        /** 返回当前 Turn 的线程身份。 */
        private String threadId() {
            return threadId;
        }

        /** 返回最近一次已观察到的持久 revision。 */
        private long threadRevision() {
            return threadRevision.get();
        }

        /** 只接受单调递增的持久 revision，避免并发 delta 看到倒退状态。 */
        private void observeRevision(long revision) {
            threadRevision.accumulateAndGet(revision, Math::max);
        }
    }

    /** 使用不同且稳定的生命周期错误拒绝 ready 前与关闭后的请求。 */
    public void requireReady() {
        if (!initialized || !ready) {
            throw JaRpcException.of(JaErrorCatalog.NOT_INITIALIZED, "runtime is not initialized");
        }
        if (closing.get()) {
            throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
        }
    }

    /** 移除生命周期原因中由调用方控制的细节，仅保留有界公开类别。 */
    private static String boundedReason(String reason) {
        return switch (reason) {
            case "user_requested", "host_shutdown", "shutdown_complete", "initialize" -> reason;
            default -> "runtime_lifecycle";
        };
    }

    /**
     * 先于 stdout 关闭应用资源；静默等待失败不得被报告为正常停止，
     * Credential 支撑的租约必须保持有效，直至其运行时 Owner 已确认静默。
     */
    @Override
    public void close() {
        close(ShutdownDeadline.start());
    }

    /**
     * 只关闭一次运行时状态，并保持代际租约直至共享运行时栅栏成功；
     * 最终生命周期投影排队与 flush 期间 Writer 仍保持打开。
     */
    void close(ShutdownDeadline deadline) {
        closeCoordinator.close(deadline, "rpc session", this::closeOwnedRuntime);
    }

    /** 按顺序关闭运行时，并仅在静默等待成功后发布 stopped。 */
    private void closeOwnedRuntime(ShutdownDeadline deadline) {
        closing.set(true);
        CancellationSource.CancelResult cancellationResult = cancellation.cancel("runtime_closed");
        approvalCompletions.close();
        // 生命周期资源由本方法在共享 Deadline 下显式关闭，不能使用 try-with-resources 重置预算。
        @SuppressWarnings("PMD.CloseResource")
        DeadlineCloseable runtime = lifecycle;
        RuntimeException failure = cancellationResult.callbackFailure().orElse(null);
        if (runtime != null) {
            try {
                runtime.closeAt(deadline.deadlineNanos());
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
        }
        // RuntimeLease 由 TurnService 在 quiescence 内释放，RPC 不再持有配置代际资源。
        try {
            /* Provider 或 Turn 静默等待失败是可观察事实，不能伪装为正常停止。 */
            deadline.await(notifyRuntime(failure == null ? "stopped" : "failed",
                    failure == null ? "shutdown_complete" : "runtime_lifecycle"),
                    "rpc session lifecycle notification");
        } catch (RuntimeException publishFailure) {
            if (failure == null) failure = publishFailure;
            else failure.addSuppressed(publishFailure);
        }
        if (failure != null) throw failure;
    }

}
