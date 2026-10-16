// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.concurrent.CancellationSource;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseCoordinator;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.agent.application.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.catalog.application.api.ThreadMcpUseCase;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadMetadataEvent;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjectionFailure;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.review.application.api.ReviewUseCase;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanEvent;
import io.github.kongweiguang.ja.plan.application.api.PlanExecutionEventSink;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.collaboration.application.api.TaskEvent;
import io.github.kongweiguang.ja.collaboration.application.api.TaskEventSink;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import io.github.kongweiguang.ja.transport.rpc.protocol.ContextCompactionEventWireMapper;
import io.github.kongweiguang.ja.transport.rpc.protocol.InteractionWireMapper;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.TaskEventWireMapper;
import io.github.kongweiguang.ja.transport.rpc.protocol.ThreadMetadataEventWireMapper;
import io.github.kongweiguang.ja.transport.rpc.protocol.TurnEventWireMapper;
import io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 保存当前连接代际状态，供窄职责 Handler 共享且不暴露全局活动资源标识。
 */
public final class RpcSession implements AutoCloseable {
    private static final AtomicLong NEXT_RUNTIME_GENERATION = new AtomicLong(1);

    private final SidecarConfiguration configuration;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final StdioWriter writer;
    private final RpcServicesFactory factory;
    private final long runtimeGeneration;
    private final ConfigurationUseCase configurationUseCase;
    private final CancellationSource cancellation = new CancellationSource();
    private final ActiveStreamRegistry activeStreams;
    private final Map<String, TurnNotificationContext> turnNotificationContexts = new ConcurrentHashMap<>();
    private final Set<String> taskObservationIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> planObservations = new ConcurrentHashMap<>();
    private final Map<String, InteractionObservation> interactionObservations = new ConcurrentHashMap<>();
    private final Set<String> interactionObservationIds = ConcurrentHashMap.newKeySet();
    private final String serverInstanceId = "srv_ja_" + UUID.randomUUID().toString().replace("-", "");
    private final AtomicLong eventIds = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean coldApprovalObservationScanned = new AtomicBoolean();
    private final Object runtimePublicationLock = new Object();
    private final Set<CompletableFuture<Void>> runtimePublications = new HashSet<>();
    private final ReentrantLock runtimeOpenLock = new ReentrantLock();
    private final DeadlineCloseCoordinator closeCoordinator = new DeadlineCloseCoordinator();
    private CompletableFuture<Void> runtimeOpenCompletion;
    private volatile WorkspaceUseCase workspaces;
    private volatile WorkspacePathSearchUseCase workspacePathSearch;
    private volatile ThreadUseCase threads;
    private volatile ThreadMcpUseCase threadMcp;
    private volatile TurnUseCase turns;
    private volatile ContextCompactionUseCase compactions;
    private volatile ApprovalUseCase approvalUseCase;
    private volatile CatalogUseCase catalog;
    private volatile AttachmentUseCase attachments;
    private volatile AttachmentPreviewUseCase attachmentPreviews;
    private volatile TaskUseCase tasks;
    private volatile PlanUseCase plans;
    private volatile InteractionUseCase interactions;
    private volatile ProcessSessionUseCase processes;
    private volatile ReviewUseCase reviews;
    private volatile AutoCloseable taskSubscription;
    private volatile AutoCloseable planSubscription;
    private ConversationTurnEventSink turnEventSink;
    private volatile DeadlineCloseable lifecycle;
    private volatile String readyToken;
    private volatile boolean initialized;
    private volatile boolean ready;
    private volatile boolean servicesReady;
    private boolean runtimeCloseSnapshotTaken;
    private volatile Consumer<Throwable> projectionFailureHandler = ignored -> { };

    /**
     * 将 Java 配置 Owner 与 Agent 运行时 Factory 分开绑定，确保尚未配置 Provider 或 Credential 时
     * 仍可在启动阶段读取 Settings。
     */
    public RpcSession(SidecarConfiguration configuration, ObjectMapper mapper, Clock clock,
                      StdioWriter writer, RpcServicesFactory factory, ConfigurationUseCase configurationUseCase) {
        this(configuration, mapper, clock, writer, factory, configurationUseCase,
                NEXT_RUNTIME_GENERATION.getAndIncrement());
    }

    /**
     * 为测试与组合边界固定非默认进程代际，使生命周期夹具可以稳定断言。
     */
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
        this.activeStreams = new ActiveStreamRegistry(runtimeGeneration);
    }

    /**
     * 连接级输出投影失败由 RpcServer 释放 ingress 并关闭资源；回调必须异步启动关闭，
     * 以免 Writer completion executor 等待自身退出。
     */
    void projectionFailureHandler(Consumer<Throwable> handler) {
        projectionFailureHandler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * TurnEvent 的两类流式增量没有 Context，其余事件统一从持久 Context 取得 identity。
     */
    private static String eventTurnId(TurnEvent event) {
        if (event instanceof TurnEvent.TextDelta delta) {
            return delta.turnId();
        }
        if (event instanceof TurnEvent.ReasoningSummaryDelta delta) {
            return delta.turnId();
        }
        return event.context().turnId();
    }

    /**
     * 校验内部关联标识，防止无效键污染连接级状态。
     */
    private static String requireIdentifier(String value, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 108
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw JaRpcException.invalidParams();
        }
        return value;
    }

    /**
     * 移除生命周期原因中由调用方控制的细节，仅保留有界公开类别。
     */
    private static String boundedReason(String reason) {
        return switch (reason) {
            case "user_requested", "host_shutdown", "shutdown_complete", "initialize" -> reason;
            default -> "runtime_lifecycle";
        };
    }

    /**
     * 即使存储阻塞也完成传输认证；独立启动锁避免 factory/订阅等待阻塞关闭侧的会话监视器，
     * 内层 finally 保证完成关闭屏障时发生异常也会释放启动锁。
     */
    public void initialize() {
        runtimeOpenLock.lock();
        CompletableFuture<Void> opening = null;
        try {
            synchronized (runtimePublicationLock) {
                if (initialized) {
                    throw JaRpcException.of(JaErrorCatalog.ALREADY_INITIALIZED,
                            "runtime is already initialized");
                }
            }
            opening = beginRuntimeOpen();
            openRuntime();
            synchronized (runtimePublicationLock) {
                if (closing.get()) {
                    throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
                }
                initialized = true;
            }
            notifyRuntime("starting", "initialize").join();
        } finally {
            try {
                completeRuntimeOpen(opening);
            } finally {
                runtimeOpenLock.unlock();
            }
        }
    }

    /**
     * 在 publication lock 下登记一次启动，让关闭方能用自己的 Deadline 等待所有者完成初始化。
     */
    private CompletableFuture<Void> beginRuntimeOpen() {
        synchronized (runtimePublicationLock) {
            if (closing.get()) {
                throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
            }
            CompletableFuture<Void> opening = new CompletableFuture<>();
            runtimeOpenCompletion = opening;
            return opening;
        }
    }

    /**
     * 启动的端口发布或回滚完成后解除关闭屏障；Future 完成始终发生在短 publication lock 内。
     */
    private void completeRuntimeOpen(CompletableFuture<Void> opening) {
        if (opening == null) {
            return;
        }
        synchronized (runtimePublicationLock) {
            opening.complete(null);
            if (runtimeOpenCompletion == opening) {
                runtimeOpenCompletion = null;
            }
        }
    }

    /**
     * 先取得连接订阅，再发布完整服务图；装配失败时在一个关闭期限内回收已取得的资源。
     * Factory 返回的 JA-RPC 异常保留原分类，其余打开异常映射为配置错误。
     * 订阅的局部变量在成功时转交连接 owner，失败时由 cleanupFailedRuntimeStart 关闭或保留供重试。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private void openRuntime() {
        RpcServiceBindings opened;
        try {
            opened = factory.open(mapper);
        } catch (RuntimeException failure) {
            RpcServicesFactory.RecoveryStatus recovery = factory.storageRecovery();
            if (recovery != null && "blocked".equals(recovery.phase())) {
                return;
            }
            if (failure instanceof JaRpcException rpcFailure) {
                throw rpcFailure;
            }
            throw JaRpcException.of(JaErrorCatalog.CONFIG_INVALID,
                    "runtime configuration is invalid", failure);
        }
        @SuppressWarnings("PMD.CloseResource")
        AutoCloseable openedTaskSubscription = null;
        @SuppressWarnings("PMD.CloseResource")
        AutoCloseable openedPlanSubscription = null;
        boolean lifecyclePublished = false;
        try {
            synchronized (runtimePublicationLock) {
                if (closing.get()) {
                    throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
                }
                lifecycle = opened.lifecycle();
                lifecyclePublished = true;
            }
            openedTaskSubscription = Objects.requireNonNull(opened.tasks().subscribe(new TaskEventSink() {
                /** Task 事件统一复用连接级 sequence 与 stdout owner。 */
                @Override
                public java.util.concurrent.CompletionStage<Void> publish(TaskEvent event) {
                    return RpcSession.this.publish(event);
                }

                /** Child Turn 继续走现有语义通知和审批关联链。 */
                @Override
                public ConversationTurnEventSink turnEvents() {
                    return RpcSession.this.eventSink();
                }

                /** Child 的流式事件在入队前取得与普通 Turn 相同的连接级关联。 */
                @Override
                public void registerTurn(String turnId, String workspaceId, String threadId,
                                         long initialThreadRevision) {
                    RpcSession.this.registerTurnNotificationContext(turnId, workspaceId, threadId,
                            initialThreadRevision);
                }

                /** Child admission 失败时复用普通 Turn 的上下文清理入口。 */
                @Override
                public void abandonTurn(String turnId) {
                    RpcSession.this.abandonTurnNotification(turnId);
                }
            }), "task event subscription");
            openedPlanSubscription = Objects.requireNonNull(opened.plans().subscribePlan(event -> {
                if (!closing.get() && planObservations.containsValue(event.plan().planId())) {
                    return publish(event);
                }
                return CompletableFuture.completedFuture(null);
            }), "plan event subscription");
            synchronized (runtimePublicationLock) {
                if (closing.get()) {
                    throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
                }
                workspaces = opened.workspaces();
                workspacePathSearch = opened.workspacePathSearch();
                threads = opened.threads();
                threadMcp = opened.threadMcp();
                turns = opened.turns();
                compactions = opened.compactions();
                approvalUseCase = opened.approvals();
                catalog = opened.catalog();
                attachments = opened.attachments();
                attachmentPreviews = opened.attachmentPreviews();
                tasks = opened.tasks();
                plans = opened.plans();
                interactions = opened.interactions();
                processes = opened.processes();
                reviews = opened.reviews();
                taskSubscription = openedTaskSubscription;
                planSubscription = openedPlanSubscription;
                servicesReady = true;
            }
        } catch (RuntimeException | Error failure) {
            cleanupFailedRuntimeStart(openedTaskSubscription, openedPlanSubscription, opened.lifecycle(),
                    lifecyclePublished, failure);
            throw failure;
        }
    }

    /**
     * 订阅装配失败时按正常关闭顺序回收连接 owner，并只保留关闭失败的句柄供最终关闭处理。
     */
    private void cleanupFailedRuntimeStart(AutoCloseable openedTaskSubscription,
                                           AutoCloseable openedPlanSubscription,
                                           DeadlineCloseable openedLifecycle, boolean lifecyclePublished,
                                           Throwable startupFailure) {
        ShutdownDeadline deadline = ShutdownDeadline.start();
        boolean taskClosed = closeStartupSubscription(openedTaskSubscription, "task", startupFailure);
        boolean planClosed = closeStartupSubscription(openedPlanSubscription, "plan", startupFailure);
        boolean lifecycleOwnedByClose;
        synchronized (runtimePublicationLock) {
            lifecycleOwnedByClose = lifecyclePublished && runtimeCloseSnapshotTaken;
            if (lifecyclePublished && !lifecycleOwnedByClose && lifecycle == openedLifecycle) {
                lifecycle = null;
            }
        }
        boolean lifecycleClosed = lifecycleOwnedByClose;
        if (!lifecycleOwnedByClose) {
            try {
                openedLifecycle.closeAt(deadline.deadlineNanos());
                lifecycleClosed = true;
            } catch (RuntimeException | Error cleanupFailure) {
                suppressStartupCleanupFailure(startupFailure, cleanupFailure);
            }
        }
        synchronized (runtimePublicationLock) {
            taskSubscription = taskClosed ? null : openedTaskSubscription;
            planSubscription = planClosed ? null : openedPlanSubscription;
            if (!lifecycleOwnedByClose) {
                lifecycle = lifecycleClosed ? null : openedLifecycle;
            }
            servicesReady = false;
            if (!taskClosed || !planClosed || !lifecycleClosed) {
                closing.set(true);
            }
        }
    }

    /**
     * 关闭订阅句柄失败时保留 owner，并把带有资源名的诊断附加到最初的启动异常。
     */
    private static boolean closeStartupSubscription(AutoCloseable subscription, String owner,
                                                    Throwable startupFailure) {
        if (subscription == null) {
            return true;
        }
        try {
            subscription.close();
            return true;
        } catch (Exception | Error cleanupFailure) {
            if (cleanupFailure != startupFailure) {
                startupFailure.addSuppressed(new IllegalStateException(
                        owner + " event subscription could not close", cleanupFailure));
            }
            return false;
        }
    }

    /**
     * 避免同一异常对象被自身抑制，同时保留启动失败后续清理的独立诊断。
     */
    private static void suppressStartupCleanupFailure(Throwable startupFailure, Throwable cleanupFailure) {
        if (cleanupFailure != startupFailure) {
            startupFailure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 在已认证连接内重试存储恢复；启动序列串行，但不会占用 closeInteractionObservations 使用的 this 锁，
     * 内层 finally 保证关闭屏障完成异常不泄漏启动锁。
     */
    public void retryStorage() {
        runtimeOpenLock.lock();
        CompletableFuture<Void> opening = null;
        try {
            requireConnected();
            if (servicesAvailable()) {
                return;
            }
            opening = beginRuntimeOpen();
            factory.retryStorage();
            openRuntime();
            if (ready && servicesAvailable()) {
                try {
                    observeColdApprovalWaits();
                } catch (RuntimeException | Error failure) {
                    failProjection(failure);
                    throw failure;
                }
            }
        } finally {
            try {
                completeRuntimeOpen(opening);
            } finally {
                runtimeOpenLock.unlock();
            }
        }
    }

    /** 服务图要么完整可用，要么保持未就绪，不提供可接纳 Turn 的降级实现。 */
    public boolean servicesAvailable() { return servicesReady; }

    /**
     * 在 publication lock 下登记唯一 ready 操作，随后锁外等待 stdout flush；关闭方用同一 deadline
     * 等待该操作退出，避免慢写入占住会话监视器或让 one-shot coordinator 越过尚未完成的发布。
     */
    void ready(String token) {
        CompletableFuture<Void> publication;
        synchronized (runtimePublicationLock) {
            if (!initialized || ready || token == null || !token.matches("[0-9a-f]{32}")) {
                throw JaRpcException.invalidFrame();
            }
            if (closing.get()) {
                throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
            }
            readyToken = token;
            ready = true;
            publication = registerRuntimePublicationLocked();
        }
        try {
            notifyRuntime("ready", null).join();
            if (servicesAvailable()) {
                observeColdApprovalWaits();
            }
        } finally {
            completeRuntimePublication(publication);
        }
    }

    /**
     * 就绪帧完成后只为确有未决审批的 MAIN/SUBAGENT 恢复 Bridge 观察；Core 的持久恢复与 Task 投影仍由原 owner 负责。
     * 分页无固定总数上限，关闭或取消会在页边界及时停止，损坏/重复游标作为连接启动失败处理。
     *
     * @author kongweiguang
     */
    private void observeColdApprovalWaits() {
        if (!ready || closing.get() || !servicesAvailable()
                || !coldApprovalObservationScanned.compareAndSet(false, true)) {
            return;
        }
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        try {
            do {
                checkColdApprovalScanActive();
                CursorPage<ThreadDiscovery> page = Objects.requireNonNull(
                        threads().discoverThreads(new ThreadDiscovery.Query("all", null, cursor, 200)),
                        "thread discovery page");
                for (ThreadDiscovery candidate : page.items()) {
                    checkColdApprovalScanActive();
                    if (candidate.kind() != ThreadDiscovery.Kind.MAIN
                            && candidate.kind() != ThreadDiscovery.Kind.SUBAGENT) {
                        continue;
                    }
                    if (candidate.latestTurnId() == null
                            || candidate.status() != ThreadDiscovery.Status.WAITING_APPROVAL
                            && candidate.status() != ThreadDiscovery.Status.SUSPENDED) {
                        continue;
                    }
                    observeColdApprovalWait(candidate);
                }
                String nextCursor = page.nextCursor();
                if (nextCursor == null) {
                    cursor = null;
                } else if (nextCursor.equals(cursor) || !seenCursors.add(nextCursor)) {
                    throw new IllegalStateException("thread discovery cursor did not advance");
                } else {
                    cursor = nextCursor;
                }
            } while (cursor != null);
        } catch (RuntimeException | Error failure) {
            // A partially restored session must not be reported ready; lifecycle shutdown releases attached watches.
            coldApprovalObservationScanned.set(false);
            throw failure;
        }
    }

    /**
     * 检查逐页恢复能否继续，避免长历史扫描在 RPC 关闭后继续占用存储。
     *
     * @author kongweiguang
     */
    private void checkColdApprovalScanActive() {
        if (closing.get() || cancellation.isCancellationRequested()) {
            throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
        }
    }

    /**
     * 使用同一轮读取确认 latest path 及实际未决审批，再先登记路由后 attach Bridge；若快照与 Turn 投影跨越审批到期，
     * 只重读一次以收敛到稳定身份，避免无限追逐并发更新。Observe 返回 false 时只清除本次新建的路由。
     *
     * @author kongweiguang
     */
    private void observeColdApprovalWait(ThreadDiscovery candidate) {
        String threadId = candidate.threadId();
        String turnId = candidate.latestTurnId();
        for (int attempt = 0; attempt < 2; attempt++) {
            checkColdApprovalScanActive();
            ThreadSnapshot snapshot = threads().readThreadLatest(threadId, null, 200).orElse(null);
            if (snapshot == null || !snapshot.thread().threadId().equals(threadId)) {
                return;
            }
            if (!snapshot.thread().workspaceId().equals(candidate.workspaceId())) {
                throw new IllegalStateException("discovered thread workspace identity changed");
            }
            if (!turnId.equals(snapshot.thread().latestTurnId())) {
                return;
            }
            ThreadSnapshot.Turn snapshotTurn = snapshot.turns().stream()
                    .filter(turn -> turn.turnId().equals(turnId)
                            && turn.origin() == ThreadSnapshot.Origin.NATIVE)
                    .findFirst().orElse(null);
            if (snapshotTurn == null) {
                continue;
            }
            TurnSummary turn = threads().findTurn(turnId).orElse(null);
            if (turn == null) {
                continue;
            }
            if (!turn.threadId().equals(threadId)) {
                return;
            }
            long revision = snapshot.thread().revision();
            if (turn.threadRevision() > revision
                    || turn.turnMutationVersion() != snapshotTurn.mutationVersion()
                    || !turn.status().equalsIgnoreCase(snapshotTurn.status())
                    || snapshot.thread().latestTurnStatus() == null
                    || !snapshot.thread().latestTurnStatus().name().equalsIgnoreCase(snapshotTurn.status())) {
                continue;
            }
            boolean waitingApproval = "waiting_approval".equalsIgnoreCase(turn.status());
            boolean suspended = "suspended".equalsIgnoreCase(turn.status());
            if ((!waitingApproval && !suspended) || !hasCurrentApproval(snapshot, turnId, suspended)) {
                return;
            }
            ThreadNotificationRegistration registration = registerColdApprovalContext(turnId,
                    candidate.workspaceId(), threadId, revision, turn.turnMutationVersion(), snapshotTurn.modelRound());
            try {
                boolean attached = turns().observeApprovalWait(turnId,
                        coldApprovalEventSink(turnId, threadId, revision));
                if (!attached) {
                    releaseColdApprovalContext(turnId, registration);
                }
            } catch (RuntimeException | Error failure) {
                releaseColdApprovalContext(turnId, registration);
                throw failure;
            }
            return;
        }
    }

    /** 只把当前 Turn 最后一个本地审批事实视作未决，继承历史和已裁决卡片都不能启动冷观察。 */
    private boolean hasCurrentApproval(ThreadSnapshot snapshot, String turnId, boolean suspended) {
        ThreadSnapshot.ApprovalItem latest = null;
        for (ThreadSnapshot.Item item : snapshot.items()) {
            if (item instanceof ThreadSnapshot.ApprovalItem approval
                    && approval.origin() == ThreadSnapshot.Origin.NATIVE
                    && approval.turnId().equals(turnId)) {
                latest = approval;
            }
        }
        return latest != null && latest.decision() == null
                && (!suspended || !latest.expiresAt().isAfter(clock.instant()));
    }

    /**
     * 为异步 replay 提前发布精确路由，并记录注册是否归本扫描所有，防止 false/异常误删已有活动上下文。
     */
    private ThreadNotificationRegistration registerColdApprovalContext(String turnId, String workspaceId,
                                                                        String threadId, long revision,
                                                                        long mutationVersion, int modelRound) {
        String validatedTurnId = requireIdentifier(turnId, "turn_");
        TurnNotificationContext proposed = new TurnNotificationContext(workspaceId, threadId, revision);
        TurnNotificationContext current = turnNotificationContexts.putIfAbsent(validatedTurnId, proposed);
        boolean created = current == null;
        TurnNotificationContext context = created ? proposed : current;
        if (!context.workspaceId().equals(workspaceId) || !context.threadId().equals(threadId)) {
            if (created) {
                turnNotificationContexts.remove(validatedTurnId, proposed);
            }
            throw JaRpcException.of(JaErrorCatalog.INVALID_STATE, "Turn notification identity changed");
        }
        try {
            if (created) {
                activeStreams.register(validatedTurnId, workspaceId, threadId, revision, mutationVersion, modelRound);
            } else {
                context.observeRevision(revision);
                activeStreams.observeThreadRevision(workspaceId, threadId, revision);
            }
            return new ThreadNotificationRegistration(context, created);
        } catch (RuntimeException | Error failure) {
            releaseColdApprovalContext(validatedTurnId, new ThreadNotificationRegistration(context, created));
            throw failure;
        }
    }

    /** 只回收本轮新创建且仍指向同一对象的 context；Stream 清理与路由移除保持原子归属判断。 */
    private void releaseColdApprovalContext(String turnId, ThreadNotificationRegistration registration) {
        if (registration.created()
                && turnNotificationContexts.remove(turnId, registration.context())) {
            activeStreams.abandon(turnId);
        }
    }

    /**
     * 包装 fresh replay：旧历史仍用于 Bridge 内部 reducer，但只有 exact identity 且不早于读取 fence 的 durable 帧
     * 才进入本 RPC 连接；无 Context 草稿永不作为冷恢复事实。非 Turn Sink 能力保持委派给唯一连接 Sink。
     */
    private ConversationTurnEventSink coldApprovalEventSink(String turnId, String threadId, long minimumRevision) {
        ConversationTurnEventSink delegate = eventSink();
        return new ConversationTurnEventSink() {
            /** 冷回放丢弃 fence 前历史与无上下文草稿，避免陈旧 ApprovalRequested 覆盖当前挂起状态。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(TurnEvent event) {
                TurnEvent.Context context = event.context();
                if (context == null || !context.threadId().equals(threadId)
                        || !context.turnId().equals(turnId)
                        || context.threadRevision() < minimumRevision) {
                    return CompletableFuture.completedFuture(null);
                }
                return delegate.publish(event);
            }

            /** Bridge 用同一 Workspace owner 构造压缩事实，不从发现摘要猜测路径。 */
            @Override
            public java.util.Optional<String> workspaceId(AgentIdentity identity) {
                return delegate.workspaceId(identity);
            }

            /** durable 回放故障仍经连接的唯一投影失败通道触发 owner 关闭。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publishProjectionFailure(
                    TurnProjectionFailure failure) {
                return delegate.publishProjectionFailure(failure);
            }

            /** Bridge 的内部流 fence 继续更新，但只允许当前被观察身份推进。 */
            @Override
            public void observeCommittedTurn(String committedThreadId, String committedTurnId,
                                             long threadRevision, long turnMutationVersion, int modelRound,
                                             TurnEvent event, Instant occurredAt) {
                if (threadId.equals(committedThreadId) && turnId.equals(committedTurnId)
                        && threadRevision >= minimumRevision) {
                    delegate.observeCommittedTurn(committedThreadId, committedTurnId, threadRevision,
                            turnMutationVersion, modelRound, event, occurredAt);
                }
            }

            /** 恢复仍沿用连接 Sink 的 Thread revision 观察规则，避免额外维护 stream fence。 */
            @Override
            public void onThreadRevision(AgentIdentity identity, long revision) {
                if (threadId.equals(identity.threadId()) && turnId.equals(identity.turnId())
                        && revision >= minimumRevision) {
                    delegate.onThreadRevision(identity, revision);
                }
            }

            /** 自动压缩仍使用已冻结 Workspace 的现有 wire mapper 与投影失败处理。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(ContextCompactionEvent event) {
                ContextCompactionEvent.Context context = event.context();
                if (context.turnId() == null || !context.threadId().equals(threadId)
                        || !context.turnId().equals(turnId)
                        || context.threadRevision() < minimumRevision) {
                    return CompletableFuture.completedFuture(null);
                }
                return delegate.publish(event);
            }

            /** 线程元数据仍交给连接级统一出口，避免复制标题与 revision 的 Wire 逻辑。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(ThreadMetadataEvent event) {
                return delegate.publish(event);
            }
        };
    }

    /**
     * 在 Turn 入队前冻结通知所需的工作区和线程身份；这些信息不从路径或配置快照反推，
     * 从而保证流式 delta 与持久事件使用同一条公开关联链。
     */
    public void registerTurnNotificationContext(String turnId, String workspaceId, String threadId,
                                                long initialThreadRevision) {
        registerTurnNotificationContext(turnId, workspaceId, threadId, initialThreadRevision, 0);
    }

    /**
     * 在恢复已有 Turn 时同时冻结持久 mutation 水位；新 admission 的初始水位为零，不能让
     * Thread revision 代替 Turn CAS 版本，否则迟到 model-step 会被错误地当作已消费。
     */
    public void registerTurnNotificationContext(String turnId, String workspaceId, String threadId,
                                                long initialThreadRevision, long initialTurnMutationVersion) {
        registerTurnNotificationContext(turnId, workspaceId, threadId, initialThreadRevision,
                initialTurnMutationVersion, 0);
    }

    /**
     * 恢复已有 Turn 时同时恢复已完成模型轮次；新 admission 仍从零开始，避免把尚未公开的
     * Provider dispatch 当成已经清稿的 Assistant 轮次。
     */
    public void registerTurnNotificationContext(String turnId, String workspaceId, String threadId,
                                                long initialThreadRevision, long initialTurnMutationVersion,
                                                int initialModelRound) {
        TurnNotificationContext context = new TurnNotificationContext(workspaceId, threadId,
                initialThreadRevision);
        String validatedTurnId = requireIdentifier(turnId, "turn_");
        if (turnNotificationContexts.putIfAbsent(validatedTurnId, context) != null) {
            throw JaRpcException.of(JaErrorCatalog.INVALID_STATE, "Turn notification context is duplicated");
        }
        activeStreams.register(validatedTurnId, context.workspaceId(), context.threadId(), initialThreadRevision,
                initialTurnMutationVersion, initialModelRound);
    }

    /**
     * 放弃尚未产生终态事件的 Turn 通知上下文；运行时租约由 TurnService 独占释放。
     */
    public void abandonTurnNotification(String turnId) {
        turnNotificationContexts.remove(turnId);
        activeStreams.abandon(turnId);
    }

    /**
     * 回答重试和重连可复用同一 Turn 路由，但绝不允许身份被别的 Thread 替换。
     */
    public void restoreTurnNotificationContext(String turnId, String workspaceId, String threadId, long revision) {
        TurnSummary turn = threads().findTurn(turnId)
                .filter(value -> value.threadId().equals(threadId))
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                        "turn is not resumable"));
        ThreadSnapshot snapshot = threads().readThread(threadId, null, 1)
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                        "turn is not resumable"));
        if (turn.threadRevision() != revision || snapshot.thread().revision() != revision) {
            throw JaRpcException.of(JaErrorCatalog.TURN_RESUME_ORDER_CONFLICT,
                    "turn resume order changed");
        }
        if (!snapshot.thread().threadId().equals(threadId)
            || !snapshot.thread().workspaceId().equals(workspaceId)) {
            throw JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE, "turn is not resumable");
        }
        int modelRound = snapshot.turns().stream()
                .filter(value -> value.turnId().equals(turnId))
                .mapToInt(ThreadSnapshot.Turn::modelRound)
                .findFirst()
                .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.TURN_NOT_RESUMABLE,
                        "turn is not resumable"));
        restoreTurnNotificationContext(turnId, workspaceId, threadId, revision,
                turn.turnMutationVersion(), modelRound);
    }

    /**
     * 交互恢复沿用同一 SQLite Turn mutation fence，避免旧回答上下文覆盖新提交。
     */
    public void restoreTurnNotificationContext(String turnId, String workspaceId, String threadId,
                                               long revision, long turnMutationVersion) {
        restoreTurnNotificationContext(turnId, workspaceId, threadId, revision, turnMutationVersion, 0);
    }

    /**
     * 交互恢复同时冻结持久已完成轮次，使新连接首段读取不依赖旧进程内状态。
     */
    public void restoreTurnNotificationContext(String turnId, String workspaceId, String threadId,
                                               long revision, long turnMutationVersion, int modelRound) {
        TurnNotificationContext proposed = new TurnNotificationContext(workspaceId, threadId, revision);
        TurnNotificationContext current = turnNotificationContexts.putIfAbsent(requireIdentifier(turnId, "turn_"), proposed);
        if (current == null) {
            activeStreams.register(turnId, workspaceId, threadId, revision, turnMutationVersion, modelRound);
            return;
        }
        if (!current.workspaceId().equals(workspaceId) || !current.threadId().equals(threadId)) {
            throw JaRpcException.of(JaErrorCatalog.INVALID_STATE, "Turn notification identity changed");
        }
        current.observeRevision(revision);
        activeStreams.register(turnId, workspaceId, threadId, revision, turnMutationVersion, modelRound);
    }

    /**
     * 返回配置入站用例，transport 只能通过不可变 JDK 投影读写配置。
     */
    public ConfigurationUseCase configurationUseCase() {
        return configurationUseCase;
    }

    /**
     * 返回宿主发布的进程根目录，供运行时域使用。
     */
    SidecarConfiguration processConfiguration() {
        return configuration;
    }

    /**
     * 配置已提交后，存储阻塞或刷新失败都不能改变保存成功的事实。
     */
    public void refreshPreparedWorkspaces() {
        if (!servicesAvailable()) {
            return;
        }
        try {
            workspaces().refreshPreparedWorkspaces();
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(RpcSession.class)
                    .warn("Ja workspace refresh failed after configuration commit type={}", failure.getClass().getName());
        }
    }

    /** 从进程级恢复 Owner 读取脱敏事实，避免连接侧维护第二份恢复状态。 */
    public io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory.RecoveryStatus storageRecovery() {
        return factory.storageRecovery();
    }

    /**
     * 返回工作区域入站端口，调用方不能通过 transport service locator 访问其它域。
     */
    public WorkspaceUseCase workspaces() {
        requireReady();
        return Objects.requireNonNull(workspaces, "workspace use case");
    }

    /**
     * 返回 Composer 路径建议的有界 Java 用例，不向 Handler 暴露物理根或 NIO adapter。
     */
    public WorkspacePathSearchUseCase workspacePathSearch() {
        requireReady();
        return Objects.requireNonNull(workspacePathSearch, "workspace path search use case");
    }

    /**
     * 返回服务端签发的连接代际，供异步建议结果关联且不接受 WebView 伪造。
     */
    public long runtimeGeneration() {
        return runtimeGeneration;
    }

    /**
     * 返回 Thread 入站端口。
     */
    public ThreadUseCase threads() {
        requireReady();
        return Objects.requireNonNull(threads, "thread use case");
    }

    /**
     * 返回 Thread 作用域的 MCP 观测端口，Handler 不得从设置目录推测会话运行状态。
     */
    public ThreadMcpUseCase threadMcp() {
        requireReady();
        return Objects.requireNonNull(threadMcp, "thread MCP use case");
    }

    /**
     * 返回当前连接唯一的公开活动流 owner；History Handler 只能用它做精确 revision 关联读取，
     * 不能自行从 stdout 或数据库正文重建草稿。
     */
    public ActiveStreamRegistry activeStreams() {
        requireReady();
        return activeStreams;
    }

    /**
     * 返回 Turn 入站端口。
     */
    public TurnUseCase turns() {
        requireReady();
        return Objects.requireNonNull(turns, "turn use case");
    }

    /**
     * 返回空闲 Thread 手动压缩端口，Handler 不得取得 Repository 或 Provider。
     */
    public ContextCompactionUseCase compactions() {
        requireReady();
        return Objects.requireNonNull(compactions, "context compaction use case");
    }

    /**
     * 返回连接生命周期只读取消令牌，使慢请求在关闭时与 Provider IO 同步收口。
     */
    public CancellationToken cancellationToken() {
        return cancellation;
    }

    /**
     * 返回审批领域端口，避免 handler 反向取得整个组合图。
     */
    public ApprovalUseCase approvalUseCase() {
        requireReady();
        return Objects.requireNonNull(approvalUseCase, "approval use case");
    }

    /**
     * 返回 Catalog 入站端口。
     */
    public CatalogUseCase catalog() {
        requireReady();
        return Objects.requireNonNull(catalog, "catalog use case");
    }

    /**
     * 返回受管附件入站端口；Handler 只能提交 opaque ingress/attachment identity。
     */
    public AttachmentUseCase attachments() {
        requireReady();
        return Objects.requireNonNull(attachments, "attachment use case");
    }

    /**
     * 返回附件短期预览端口；读取授权与普通附件生命周期共享同一个应用 owner。
     */
    public AttachmentPreviewUseCase attachmentPreviews() {
        requireReady();
        return Objects.requireNonNull(attachmentPreviews, "attachment preview use case");
    }

    /**
     * 返回 Task 入站端口；隐藏详情是否读取由调用方显式 task/read 决定。
     */
    public TaskUseCase tasks() {
        requireReady();
        return Objects.requireNonNull(tasks, "task use case");
    }

    /**
     * 返回 Plan 入站端口；读取、控制和计划批准始终经过同一聚合 CAS 边界。
     */
    public PlanUseCase plans() {
        requireReady();
        return Objects.requireNonNull(plans, "plan use case");
    }

    /**
     * 问答使用与 Turn 相同的 Java owner，连接只取得已组合的窄端口。
     */
    public InteractionUseCase interactions() {
        requireReady();
        return Objects.requireNonNull(interactions, "interaction use case");
    }

    /**
     * 独立 Plan 在当前连接登记后再读取版本，关闭右栏不停止其执行。
     */
    public ObjectNode observePlan(String threadId, String planId) {
        requireReady();
        PlanModels.PlanSnapshot snapshot = plans.readPlan(planId);
        if (!snapshot.plan().ownerThreadId().equals(threadId)) {
            throw JaRpcException.of(JaErrorCatalog.PLAN_NOT_FOUND, "计划不存在");
        }
        if (planObservations.size() >= 64) {
            throw JaRpcException.of(JaErrorCatalog.QUEUE_FULL, "计划观察数量已达上限");
        }
        String observationId = "observe_" + UUID.randomUUID().toString().replace("-", "");
        planObservations.put(observationId, planId);
        try {
            ObjectNode result = new io.github.kongweiguang.ja.transport.rpc.protocol.PlanWireMapper(mapper)
                    .planSnapshot(plans.readPlan(planId));
            return result.put("observationId", observationId);
        } catch (RuntimeException failure) {
            planObservations.remove(observationId);
            throw failure;
        }
    }

    /**
     * 观察句柄只管理连接投影，不改变 Plan Run 或其它会话。
     */
    public void unobservePlan(String observationId) {
        planObservations.remove(observationId);
    }

    /**
     * 在锁内预留唯一观察身份并登记退出 future，订阅和快照读取都在锁外执行；先订阅再读仍保证
     * 创建事件可由快照和序列号对账，失败清理若也失败则保留句柄 owner 并附加到原始异常。
     */
    public ObjectNode observeInteraction(String threadId) {
        CompletableFuture<Void> publication;
        String observationId;
        InteractionUseCase owner;
        synchronized (runtimePublicationLock) {
            requireReady();
            owner = Objects.requireNonNull(interactions, "interaction use case");
            if (interactionObservationIds.size() >= 64) {
                throw JaRpcException.of(JaErrorCatalog.QUEUE_FULL, "问答观察数量已达上限");
            }
            do {
                observationId = "observe_" + UUID.randomUUID().toString().replace("-", "");
            } while (!interactionObservationIds.add(observationId));
            publication = registerRuntimePublicationLocked();
        }

        String subscribedObservationId = observationId;
        InteractionObservation observation = new InteractionObservation();
        boolean registered = false;
        try {
            // 观察句柄归本连接所有；回调只在句柄仍有效且仍登记时发布。
            @SuppressWarnings("PMD.CloseResource")
            AutoCloseable subscription = owner.subscribe(threadId, event -> {
                if (!closing.get() && observation.active()
                        && interactionObservations.get(subscribedObservationId) == observation) {
                    publish(event);
                }
            });
            observation.acquire(Objects.requireNonNull(subscription, "interaction subscription"));
            if (interactionObservations.putIfAbsent(observationId, observation) != null) {
                throw new IllegalStateException("interaction observation identity is already owned");
            }
            registered = true;
            ObjectNode result = new InteractionWireMapper(mapper).snapshot(owner.read(threadId, null)
                    .orElseThrow(() -> JaRpcException.of(JaErrorCatalog.INTERACTION_NOT_FOUND, "问答不可用")));
            return result.put("observationId", observationId);
        } catch (RuntimeException | Error failure) {
            if (registered) {
                try {
                    closeInteractionObservation(observationId, observation);
                } catch (RuntimeException closeFailure) {
                    if (closeFailure != failure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
            } else {
                releaseInteractionObservationId(observationId);
            }
            throw failure;
        } finally {
            completeRuntimePublication(publication);
        }
    }

    /** 返回唯一进程 Owner；连接只借用端口，断线不会关闭其它 Thread 的后台进程。 */
    public ProcessSessionUseCase processes() {
        requireReady();
        return Objects.requireNonNull(processes, "process use case");
    }

    /** 审查始终经显式用例进入同一 Turn Runtime，transport 不另建模型循环。 */
    public ReviewUseCase reviews() {
        requireReady();
        return Objects.requireNonNull(reviews, "review use case");
    }

    /**
     * 观察句柄只属于当前连接；先登记退出 future，再锁外关闭句柄，使 session close 能在共享
     * ShutdownDeadline 内等待释放结果，并在句柄关闭失败时保留 owner 供后续重试。
     */
    public void unobserveInteraction(String observationId) {
        CompletableFuture<Void> publication;
        InteractionObservation observation;
        synchronized (runtimePublicationLock) {
            observation = interactionObservations.get(observationId);
            if (closing.get()) {
                if (observation == null) {
                    return;
                }
                throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
            }
            publication = registerRuntimePublicationLocked();
        }
        try {
            if (observation != null) {
                closeInteractionObservation(observationId, observation);
            }
        } finally {
            completeRuntimePublication(publication);
        }
    }

    /**
     * acquisition future 全部完成后才由 close coordinator 调用；此处不持 publication lock 执行
     * 用户提供的 AutoCloseable，失败的句柄仍保留在 registry，且不会再发布事件。
     */
    private RuntimeException closeInteractionObservations() {
        RuntimeException failure = null;
        for (Map.Entry<String, InteractionObservation> entry : Set.copyOf(interactionObservations.entrySet())) {
            try {
                closeInteractionObservation(entry.getKey(), entry.getValue());
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                }
                else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        return failure;
    }

    /** 在 publication lock 内登记活动发布，关闭栅栏据此等待锁外工作完成。 */
    private CompletableFuture<Void> registerRuntimePublicationLocked() {
        CompletableFuture<Void> publication = new CompletableFuture<>();
        runtimePublications.add(publication);
        return publication;
    }

    /** 仅在锁外工作已退出后移除其屏障，避免 close coordinator 提前快照资源 owner。 */
    private void completeRuntimePublication(CompletableFuture<Void> publication) {
        synchronized (runtimePublicationLock) {
            runtimePublications.remove(publication);
        }
        publication.complete(null);
    }

    /** 释放未能建立订阅的预留身份；句柄所有权成功转移后身份只在成功关闭时释放。 */
    private void releaseInteractionObservationId(String observationId) {
        synchronized (runtimePublicationLock) {
            interactionObservationIds.remove(observationId);
        }
    }

    /** 关闭一个连接级句柄，成功后才移除 owner；失败时保留其身份供 session close 重试。 */
    private void closeInteractionObservation(String observationId, InteractionObservation observation) {
        try {
            observation.close();
        } catch (Exception failure) {
            throw new IllegalStateException("interaction observation could not close", failure);
        }
        synchronized (runtimePublicationLock) {
            if (interactionObservations.remove(observationId, observation)) {
                interactionObservationIds.remove(observationId);
            }
        }
    }

    /**
     * standalone Plan 只把内部 Turn 投影回发起当前命令的连接；连接关闭后执行可继续持久化，
     * 但事件静默停止，重连通过 thread/read 恢复，避免借用 Plan observation 或跨窗口广播。
     */
    public PlanExecutionEventSink planExecutionEvents() {
        requireReady();
        return new PlanExecutionEventSink() {
            /** admission 前登记与普通 Turn 相同的连接级通知上下文。 */
            @Override
            public boolean registerTurn(String turnId, String workspaceId, String threadId,
                                        long initialThreadRevision) {
                if (closing.get()) {
                    return false;
                }
                registerTurnNotificationContext(turnId, workspaceId, threadId, initialThreadRevision);
                if (!closing.get()) {
                    return true;
                }
                abandonTurnNotification(turnId);
                return false;
            }

            /** 恢复路由只从当前权威 Turn 快照刷新同身份水位，不把既有暂停路由误判为重复 admission。 */
            @Override
            public boolean registerResumedTurn(String turnId, String workspaceId, String threadId,
                                               long initialThreadRevision) {
                if (closing.get()) {
                    return false;
                }
                restoreTurnNotificationContext(turnId, workspaceId, threadId, initialThreadRevision);
                if (!closing.get()) {
                    return true;
                }
                abandonTurnNotification(turnId);
                return false;
            }

            /**
             * 自动压缩只可借用 admission 已登记的连接身份；Thread 校验阻止以相同 Turn ID 跨线程解析。
             *
             * @author kongweiguang
             */
            @Override
            public java.util.Optional<String> workspaceId(AgentIdentity identity) {
                Objects.requireNonNull(identity, "identity");
                if (closing.get()) {
                    return java.util.Optional.empty();
                }
                TurnNotificationContext context = turnNotificationContexts.get(identity.turnId());
                if (context == null || !context.threadId().equals(identity.threadId())) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(context.workspaceId());
            }

            /** 连接或关联已关闭时不把后台执行失败升级为 Plan run 失败。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(TurnEvent event) {
                if (closing.get() || !turnNotificationContexts.containsKey(eventTurnId(event))) {
                    return CompletableFuture.completedFuture(null);
                }
                return RpcSession.this.publish(event);
            }

            /**
             * 压缩事件只允许通过仍存活且身份匹配的 Plan Turn 路由发布，复用 Thread 事件的权威快照水位。
             *
             * @author kongweiguang
             */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(ContextCompactionEvent event) {
                Objects.requireNonNull(event, "event");
                ContextCompactionEvent.Context identity = event.context();
                TurnNotificationContext registered = identity.turnId() == null
                        ? null : turnNotificationContexts.get(identity.turnId());
                if (closing.get() || registered == null
                    || !registered.workspaceId().equals(identity.workspaceId())
                    || !registered.threadId().equals(identity.threadId())) {
                    return CompletableFuture.completedFuture(null);
                }
                return RpcSession.this.publish(event);
            }

            /** 完成与 admission 失败共用幂等清理。 */
            @Override
            public void abandonTurn(String turnId) {
                abandonTurnNotification(turnId);
            }
        };
    }






    /**
     * 建立当前连接拥有的 Task observation；关闭竞态发生在远端创建之后时立即补偿释放，
     * 重复返回的句柄仍归首次调用所有，拒绝第二次登记不能撤销首次观察。
     */
    public TaskUseCase.Observation observeTask(String taskThreadId, long expectedTaskRevision) {
        requireReady();
        // TaskUseCase 是组合根拥有的长生命周期端口；这里只借用它建立连接级 observation。
        @SuppressWarnings("PMD.CloseResource")
        TaskUseCase owner = Objects.requireNonNull(tasks, "task use case");
        TaskUseCase.Observation observation = owner.observe(taskThreadId, expectedTaskRevision);
        if (!taskObservationIds.add(observation.observationId())) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.OBSERVATION_INVALID,
                    "task observation identity is already owned");
        }
        if (closing.get()) {
            if (taskObservationIds.remove(observation.observationId())) {
                owner.unobserve(observation.observationId());
            }
            throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
        }
        return observation;
    }

    /**
     * 只释放当前连接登记的 observation；先移除本地所有权再调用 Task owner，使幂等重试不会
     * 越过连接边界关闭另一会话的句柄，也不会在底层失败后由 close 重复执行同一释放。
     */
    public void unobserveTask(String observationId) {
        requireReady();
        if (!taskObservationIds.remove(observationId)) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.OBSERVATION_INVALID,
                    "task observation is not owned by this session");
        }
        Objects.requireNonNull(tasks, "task use case").unobserve(observationId);
    }

    /**
     * 返回由严格帧 Codec 配置的 ObjectMapper。
     */
    public ObjectMapper mapper() {
        return mapper;
    }

    /**
     * 交由组合根提供应用 codec，使 RPC Schema 解析沿用 Runtime 已发布的严格 JSON 值策略。
     */
    public JsonValueCodec jsonValueCodec() {
        return factory.jsonValueCodec();
    }

    /**
     * 返回 Handler CAS 与事件时间戳共享的注入时钟。
     */
    public Clock clock() {
        return clock;
    }

    /**
     * 返回复制到每条语义通知的进程代际身份。
     */
    public String serverInstanceId() {
        return serverInstanceId;
    }

    /**
     * 返回 ready 挑战是否完成，用于请求准入判断。
     */
    boolean ready() {
        return ready;
    }

    /**
     * 返回关闭流程是否已停止请求与 Turn 准入。
     */
    boolean closing() {
        return closing.get();
    }

    /**
     * 在返回关闭确认前停止新的 Turn 准入。
     */
    public boolean beginShutdown(String reason) {
        synchronized (runtimePublicationLock) {
            if (!closing.compareAndSet(false, true)) {
                return false;
            }
        }
        // TurnUseCase 由连接级生命周期统一持有，此处只借用端口停止准入，不能局部关闭。
        @SuppressWarnings("PMD.CloseResource")
        TurnUseCase currentTurns = turns;
        if (currentTurns != null) {
            currentTurns.stopAccepting();
        }
        // Host 收到 shutdown ACK 后会回收进程树；临时业务事实必须在 ACK 前清理，
        // 不能依赖随后 JVM/Solon 的退出 hook 获得额外执行机会。
        if (tasks != null) {
            tasks.closeTemporarySideChats(ShutdownDeadline.start().deadlineNanos());
        }
        /* 控制通道 FIFO 保证状态先于关闭响应写出，同时不让 Handler 阻塞 stdout；
         * 外层 RpcServer 使用同一绝对期限等待两者完成。 */
        try {
            notifyRuntime("shutting_down", reason);
        } catch (RuntimeException failure) {
            failProjection(failure);
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
            Instant occurredAt = clock.instant();
            TurnEventWireMapper.WireEvent wire = new TurnEventWireMapper(mapper, serverInstanceId).map(event);
            /* WireEvent 每次读取都会深拷贝；必须固定本次写出的唯一副本后再补全连接元数据，
             * 否则 sequence、generation 与 workspaceId 会只写进随后被丢弃的临时节点。 */
            ObjectNode wireParams = wire.params();
            enrichAgentNotification(event, wireParams, occurredAt);
            /*
             * AgentLoop 只有在 SQLite 提交后才发布 durable event；在此唯一出站边界更新基线，
             * 使 thread/read 的 revision 检查可以拒绝“已提交但尚未写出”窗口，而无需猜测数据库正文。
             */
            activeStreams.observe(event, occurredAt);
            published = wire.method().equals("assistant/text-delta")
                        || wire.method().equals("assistant/reasoning-summary-delta")
                    ? writer.delta(wire.method(), wireParams)
                    : writer.notification(wire.method(), wireParams);
        } catch (Throwable failure) {
            handlePublishFailure(event, failure);
            return CompletableFuture.failedFuture(failure);
        }
        /*
         * Writer 所有者只负责输出；完成回调移出该线程，防止后续同步写入重入 stdout。
         */
        CompletableFuture<Void> observed = new CompletableFuture<>();
        published.whenComplete((ignored, failure) -> {
            Runnable callback = () -> {
                try {
                    if (failure != null) {
                        handlePublishFailure(event, failure);
                    }
                    if (failure == null && event instanceof TurnEvent.Terminal terminal) {
                        turnNotificationContexts.remove(terminal.context().turnId());
                    } else if (failure != null && event instanceof TurnEvent.Terminal terminal) {
                        turnNotificationContexts.remove(terminal.context().turnId());
                    }
                    if (failure == null) {
                        observed.complete(null);
                    }
                    else {
                        observed.completeExceptionally(failure);
                    }
                } catch (Throwable callbackFailure) {
                    if (failure != null && callbackFailure != failure) {
                        callbackFailure.addSuppressed(failure);
                    }
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
     * 发送一次脱敏的快照重读提示；该观察通知不构造 Turn 事件，也不影响执行状态。
     */
    public CompletableFuture<Void> publish(TurnProjectionFailure failure) {
        TurnProjectionFailure value = Objects.requireNonNull(failure, "failure");
        TurnNotificationContext turn = turnNotificationContexts.get(value.turnId());
        if (turn == null || !turn.threadId().equals(value.threadId())) {
            return CompletableFuture.failedFuture(
                    JaRpcException.of(JaErrorCatalog.INVALID_STATE, "Turn notification context is unavailable"));
        }
        ObjectNode params = mapper.createObjectNode();
        addNotificationMetadata(params, "turn_resync_required");
        params.put("workspaceId", turn.workspaceId());
        params.put("threadId", value.threadId());
        params.put("turnId", value.turnId());
        params.put("reasonCode", value.reason().wireValue());
        try {
            return watchProjection(writer.notification("turn/resync-required", params));
        } catch (RuntimeException | Error projectionFailure) {
            failProjection(projectionFailure);
            throw projectionFailure;
        }
    }

    /**
     * 发布 Thread 级压缩生命周期；事件自带 Workspace/Thread 身份，不依赖活动 Turn registry，
     * 因而手动空闲压缩与自动 Turn 压缩使用完全相同的 wire 路径。
     * registry 同时保存只读恢复状态；thread/read 只在快照 revision 不早于该阶段且同一活动 Turn
     * 或手动 Thread 压缩仍有效时暴露它。
     *
     * @author kongweiguang
     */
    public CompletableFuture<Void> publish(ContextCompactionEvent event) {
        try {
            ContextCompactionEventWireMapper.WireEvent wire =
                    new ContextCompactionEventWireMapper(mapper, serverInstanceId).map(event);
            ObjectNode params = wire.params();
            params.put("generation", runtimeGeneration);
            /* 压缩也是 Thread revision 提交边界，活动流必须随同一版本前进。 */
            activeStreams.observeCompaction(event);
            return watchProjection(writer.notification(wire.method(), params));
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
            Instant occurredAt = clock.instant();
            addNotificationMetadata(params, "thread_metadata", occurredAt);
            /* 自动标题/偏好 metadata 不带 Turn，但会推进同一个 Thread revision。 */
            activeStreams.observeThreadRevision(event.workspaceId(), event.threadId(), event.revision());
            return watchProjection(writer.notification("thread/metadata-changed", params));
        } catch (Throwable failure) {
            failProjection(failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 发布 Workspace 持久元数据修订；stdout owner 在实际写出时补 sequence 以维持跨事件顺序。
     */
    public CompletableFuture<Void> publishWorkspaceMetadataChanged(String workspaceId, long revision) {
        try {
            if (workspaceId == null || workspaceId.isBlank() || revision < 0) {
                throw new IllegalArgumentException("invalid Workspace metadata event");
            }
            ObjectNode params = mapper.createObjectNode();
            addNotificationMetadata(params, "workspace_metadata");
            params.put("workspaceId", workspaceId);
            params.put("revision", revision);
            return watchProjection(writer.notification("workspace/metadata-changed", params));
        } catch (Throwable failure) {
            failProjection(failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Durable Task 事件不可丢；progress 交给 Rust 有界投影按 Task 合并。
     */
    public CompletableFuture<Void> publish(TaskEvent event) {
        try {
            TaskEventWireMapper.WireEvent wire = new TaskEventWireMapper(mapper).map(event);
            ObjectNode params = wire.params();
            addNotificationMetadata(params, "task");
            return watchProjection(writer.notification(wire.method(), params));
        } catch (Throwable failure) {
            failProjection(failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 问答事件仅携带对账身份，答案和草稿正文只通过已验证归属的查询读取。
     */
    public CompletableFuture<Void> publish(InteractionEvent event) {
        try {
            ObjectNode params = new InteractionWireMapper(mapper).event(event);
            addNotificationMetadata(params, "interaction");
            return watchProjection(writer.notification("interaction/changed", params));
        } catch (Throwable failure) {
            failProjection(failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Plan 使用自己的序列与状态行，Java stdout 仍只发送协议帧。
     */
    public CompletableFuture<Void> publish(PlanEvent event) {
        try {
            ObjectNode params = new io.github.kongweiguang.ja.transport.rpc.protocol.PlanWireMapper(mapper)
                    .planChanged(event);
            addNotificationMetadata(params, "plan");
            return watchProjection(writer.notification("plan/changed", params));
        } catch (Throwable failure) {
            failProjection(failure);
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * 返回连接级稳定 Sink 身份；事件桥据此在暂停恢复时续用游标且把新连接视作全量回放。
     */
    public synchronized ConversationTurnEventSink eventSink() {
        if (turnEventSink == null) {
            turnEventSink = new ConversationTurnEventSink() {
            /** 普通 Turn 事件沿用持久/草稿发布路径。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(TurnEvent event) {
                return RpcSession.this.publish(event);
            }

            /** 压缩 durable fact 只携带 Thread/Turn；workspace 由本连接已注册的 admission 身份补齐。
             * @author kongweiguang
             */
            @Override
            public java.util.Optional<String> workspaceId(AgentIdentity identity) {
                Objects.requireNonNull(identity, "identity");
                TurnNotificationContext context = turnNotificationContexts.get(identity.turnId());
                if (context == null || !context.threadId().equals(identity.threadId())) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(context.workspaceId());
            }

            /** 投影错误只触发权威读取提示，不伪造 durable Turn 终态。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publishProjectionFailure(
                    TurnProjectionFailure failure) {
                return RpcSession.this.publish(failure);
            }

            /** Provider dispatch 等无公开事件的事务只更新活动流内部 fence，不写 JA-RPC 帧。 */
            @Override
            public void observeCommittedTurn(String threadId, String turnId, long threadRevision,
                                             long turnMutationVersion, int modelRound, TurnEvent event,
                                             Instant occurredAt) {
                activeStreams.observeTurnCommit(threadId, turnId, threadRevision, turnMutationVersion,
                        modelRound, event, occurredAt);
            }

            /** Admission receipt 按已验证的 Workspace/Thread 身份推进所有同范围通知基线，不冒充 Turn mutation fence。 */
            @Override
            public void onThreadRevision(AgentIdentity identity, long revision) {
                Objects.requireNonNull(identity, "identity");
                TurnNotificationContext context = turnNotificationContexts.get(identity.turnId());
                if (context == null || !context.threadId().equals(identity.threadId())) {
                    throw JaRpcException.of(JaErrorCatalog.INVALID_STATE,
                            "Turn admission notification context is unavailable");
                }
                activeStreams.observeThreadRevision(context.workspaceId(), identity.threadId(), revision);
                turnNotificationContexts.values().stream()
                        .filter(candidate -> candidate.workspaceId().equals(context.workspaceId())
                                && candidate.threadId().equals(identity.threadId()))
                        .forEach(candidate -> candidate.observeRevision(revision));
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
        return turnEventSink;
    }

    /**
     * 响应或事件 flush 失败后标记连接投影失败并通知唯一 Server owner，不修改持久状态。
     */
    void failProjection(Throwable failure) {
        synchronized (runtimePublicationLock) {
            closing.set(true);
        }
        writer.poison(failure);
        projectionFailureHandler.accept(failure);
    }

    /**
     * 统一观察异步 Wire flush；已提交的业务事实不回滚，但丢失连接投影必须通知 Server owner 关闭。
     */
    private CompletableFuture<Void> watchProjection(CompletableFuture<Void> projection) {
        projection.whenComplete((ignored, failure) -> {
            if (failure != null) {
                failProjection(failure);
            }
        });
        return projection;
    }

    /**
     * 标记投影失败并使 stdout 中毒，不尝试第二次提交终态。
     */
    private void handlePublishFailure(TurnEvent event, Throwable failure) {
        if (event instanceof TurnEvent.Terminal terminal) {
            turnNotificationContexts.remove(terminal.context().turnId());
            activeStreams.abandon(terminal.context().turnId());
        }
        failProjection(failure);
    }

    /**
     * 在唯一 RPC 出站边界分配进程级单调 sequence，并为流式草稿补齐不可恢复的事件身份。
     * 持久事件保留存储层生成的 eventId、时间和 revision，避免传输层伪造领域顺序。
     */
    private void enrichAgentNotification(TurnEvent event, ObjectNode params, Instant occurredAt) {
        String turnId = event instanceof TurnEvent.TextDelta delta ? delta.turnId()
                : event instanceof TurnEvent.ReasoningSummaryDelta delta ? delta.turnId()
                  : event.context().turnId();
        TurnNotificationContext turn = turnNotificationContexts.get(turnId);
        if (turn == null) {
            throw JaRpcException.of(JaErrorCatalog.INVALID_STATE, "Turn notification context is unavailable");
        }
        TurnEvent.Context durable = event.context();
        if (durable != null) {
            turn.observeRevision(durable.threadRevision());
        }
        params.put("serverInstanceId", serverInstanceId);
        if (!params.has("eventId")) {
            params.put("eventId", nextEventId("stream"));
        }
        if (!params.has("occurredAt")) {
            params.put("occurredAt", occurredAt.toString());
        }
        params.put("generation", runtimeGeneration);
        params.put("workspaceId", turn.workspaceId());
        if (!params.has("threadId")) {
            params.put("threadId", turn.threadId());
        }
        if (!params.has("turnId")) {
            params.put("turnId", turnId);
        }
        if (!(event instanceof TurnEvent.InputQueueChanged) && !params.has("threadRevision")) {
            params.put("threadRevision", turn.threadRevision());
        }
    }

    /**
     * 配置写入提交后仅发送脱敏的 scope、workspace 与 version 事实；通知 flush 失败只关闭连接投影，
     * 不回滚已经由配置 Owner 提交的写入。
     */
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
        if ("user".equals(scope) && workspaceId != null) {
            throw JaRpcException.invalidParams();
        }
        ObjectNode params = mapper.createObjectNode().put("scope", scope).put("version", version);
        if (workspaceId != null) {
            params.put("workspaceId", workspaceId);
        }
        addNotificationMetadata(params, "configuration");
        try {
            watchProjection(writer.notification("configuration/changed", params));
        } catch (RuntimeException | Error failure) {
            failProjection(failure);
            throw failure;
        }
    }

    /**
     * 发布配置 Watcher 观察到的变更，但只对已完成握手且仍在运行的连接可见。
     *
     * <p>外部编辑可能发生在握手前或 shutdown 后；这些时点没有合法的客户端事件流，静默等待
     * 随后的权威读取即可。该门禁同时避免 Watcher 线程与连接关闭竞争时向已释放的 Writer 投影。</p>
     */
    public void publishObservedConfigurationChange(String scope, String workspaceId, String version) {
        if (!ready || closing.get()) {
            return;
        }
        notifyConfigChanged(scope, workspaceId, version);
    }

    /**
     * 发送有界生命周期事件，不包含配置、路径或 Secret。
     */
    CompletableFuture<Void> notifyRuntime(String status, String reason) {
        ObjectNode params = mapper.createObjectNode();
        addNotificationMetadata(params, "runtime");
        params.put("status", status);
        params.putArray("features").add("task_threads").add("plan").add("interaction");
        if ("ready".equals(status)) {
            params.put("readyToken", readyToken);
        }
        if (reason != null && !reason.isBlank()) {
            params.put("reason", boundedReason(reason));
        }
        try {
            return watchProjection(writer.notification("runtime/status-changed", params));
        } catch (RuntimeException | Error failure) {
            failProjection(failure);
            throw failure;
        }
    }

    /**
     * 为非 Turn 语义通知写入同一组公共元数据；物理 sequence 由唯一 stdout owner 分配。
     */
    private void addNotificationMetadata(ObjectNode params, String category) {
        addNotificationMetadata(params, category, clock.instant());
    }

    /**
     * 使用调用方已经冻结的发生时间补全 Wire，保证 metadata 事件与活动基线具有同一时间边界。
     */
    private void addNotificationMetadata(ObjectNode params, String category, Instant occurredAt) {
        params.put("serverInstanceId", serverInstanceId);
        params.put("eventId", nextEventId(category));
        params.put("occurredAt", Objects.requireNonNull(occurredAt, "occurredAt").toString());
        params.put("generation", runtimeGeneration);
    }

    /**
     * 分配当前进程实例内唯一且不含用户数据的事件标识。
     */
    private String nextEventId(String category) {
        return "evt_" + category + "_" + eventIds.incrementAndGet();
    }

    /** 业务请求除完成传输握手外，还必须等存储恢复并发布真实应用服务。 */
    public void requireReady() {
        requireConnected();
        if (!servicesAvailable()) {
            throw JaRpcException.of(JaErrorCatalog.STORAGE_UNAVAILABLE, "runtime storage needs recovery");
        }
    }

    /** 健康查询、存储重试和关闭只要求已连接，不代表业务服务已就绪。 */
    public void requireConnected() {
        if (!initialized || !ready) {
            throw JaRpcException.of(JaErrorCatalog.NOT_INITIALIZED, "runtime is not initialized");
        }
        if (closing.get()) {
            throw JaRpcException.of(JaErrorCatalog.SHUTTING_DOWN, "runtime is shutting down");
        }
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
     * 关闭先设置准入栅栏，再用同一绝对 Deadline 等待启动和 ready/interaction 操作退出；
     * 等待超时时不接纳 one-shot coordinator，后续 close 可捕获迟到完成并保留的资源 owner。
     */
    void close(ShutdownDeadline deadline) {
        CompletableFuture<Void> opening;
        CompletableFuture<Void> publications;
        synchronized (runtimePublicationLock) {
            closing.set(true);
            servicesReady = false;
            opening = runtimeOpenCompletion;
            publications = CompletableFuture.allOf(runtimePublications.toArray(CompletableFuture[]::new));
        }
        if (opening != null) {
            deadline.await(opening, "rpc session runtime startup");
        }
        deadline.await(publications, "rpc session publications");
        closeCoordinator.close(deadline, "rpc session", this::closeOwnedRuntime);
    }

    /**
     * 按顺序关闭运行时，并仅在静默等待成功后发布 stopped。
     * 三个句柄从连接字段转入本次关闭快照，使用共享 Deadline 显式关闭，失败时仍由 owner 保留。
     */
    private void closeOwnedRuntime(ShutdownDeadline deadline) {
        @SuppressWarnings("PMD.CloseResource")
        AutoCloseable closingTaskSubscription;
        @SuppressWarnings("PMD.CloseResource")
        AutoCloseable closingPlanSubscription;
        @SuppressWarnings("PMD.CloseResource")
        DeadlineCloseable closingLifecycle;
        synchronized (runtimePublicationLock) {
            closing.set(true);
            servicesReady = false;
            runtimeCloseSnapshotTaken = true;
            closingTaskSubscription = taskSubscription;
            closingPlanSubscription = planSubscription;
            closingLifecycle = lifecycle;
        }
        activeStreams.clear();
        CancellationSource.CancelResult cancellationResult = cancellation.cancel("runtime_closed");
        RuntimeException failure = cancellationResult.callbackFailure().orElse(null);
        RuntimeException interactionCloseFailure = closeInteractionObservations();
        if (interactionCloseFailure != null) {
            if (failure == null) {
                failure = interactionCloseFailure;
            }
            else {
                failure.addSuppressed(interactionCloseFailure);
            }
        }
        // Task owner 稍后由 lifecycle 统一关闭；此处只先释放当前连接拥有的 observation。
        if (tasks != null) {
            for (String observationId : Set.copyOf(taskObservationIds)) {
                if (!taskObservationIds.remove(observationId)) {
                    continue;
                }
                try {
                    tasks.unobserve(observationId);
                } catch (RuntimeException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    }
                    else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
        }
        // Subscription 由 RpcSession 独占，并在 lifecycle 关闭 Task owner 前解除。
        if (closingTaskSubscription != null) {
            try {
                closingTaskSubscription.close();
            } catch (Exception closeFailure) {
                RuntimeException subscriptionFailure = new IllegalStateException(
                        "task event subscription could not close", closeFailure);
                if (failure == null) {
                    failure = subscriptionFailure;
                }
                else {
                    failure.addSuppressed(subscriptionFailure);
                }
            }
        }
        planObservations.clear();
        if (closingPlanSubscription != null) {
            try {
                closingPlanSubscription.close();
            } catch (Exception closeFailure) {
                RuntimeException subscriptionFailure = new IllegalStateException("plan subscription could not close", closeFailure);
                if (failure == null) {
                    failure = subscriptionFailure;
                }
                else {
                    failure.addSuppressed(subscriptionFailure);
                }
            }
        }
        // 生命周期资源由本方法在共享 Deadline 下显式关闭，不能使用 try-with-resources 重置预算。
        @SuppressWarnings("PMD.CloseResource")
        DeadlineCloseable runtime = closingLifecycle;
        if (runtime != null) {
            try {
                runtime.closeAt(deadline.deadlineNanos());
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                }
                else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        // RuntimeLease 由 TurnService 在 quiescence 内释放，RPC 不再持有配置代际资源。
        try {
            /* Provider 或 Turn 静默等待失败是可观察事实，不能伪装为正常停止。 */
            deadline.await(notifyRuntime(failure == null ? "stopped" : "failed",
                            failure == null ? "shutdown_complete" : "runtime_lifecycle"),
                    "rpc session lifecycle notification");
        } catch (RuntimeException publishFailure) {
            if (failure == null) {
                failure = publishFailure;
            }
            else {
                failure.addSuppressed(publishFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }


    /** 一个订阅 owner 同时承担投影启停和串行 close，失败时保持 owner 可再次关闭。 */
    private static final class InteractionObservation {
        private AutoCloseable subscription;
        private volatile boolean active = true;
        private boolean closed;

        /** 将唯一订阅句柄转交给连接级 observation owner。 */
        private void acquire(AutoCloseable value) {
            if (subscription != null) {
                throw new IllegalStateException("interaction subscription is already owned");
            }
            subscription = Objects.requireNonNull(value, "subscription");
        }

        /** 回调只在当前 owner 仍允许发布时投影事件。 */
        private boolean active() {
            return active;
        }

        /** 关闭前立即禁用事件投影；close 抛错时保留句柄，使后续 owner 能重试。 */
        private synchronized void close() throws Exception {
            active = false;
            if (closed) {
                return;
            }
            Objects.requireNonNull(subscription, "interaction subscription").close();
            closed = true;
        }
    }

    /**
     * 保存流式通知无法自行携带的稳定 Turn 关联信息，并只允许 revision 单调前进。
     */
    private static final class TurnNotificationContext {
        private final String workspaceId;
        private final String threadId;
        private final AtomicLong threadRevision;

        /**
         * 冻结工作区和线程身份，revision 使用原子值承接持久事件推进。
         */
        private TurnNotificationContext(String workspaceId, String threadId, long initialThreadRevision) {
            this.workspaceId = requireIdentifier(workspaceId, "ws_");
            this.threadId = requireIdentifier(threadId, "thr_");
            if (initialThreadRevision < 0) {
                throw JaRpcException.invalidParams();
            }
            this.threadRevision = new AtomicLong(initialThreadRevision);
        }

        /**
         * 返回当前 Turn 的工作区身份。
         */
        private String workspaceId() {
            return workspaceId;
        }

        /**
         * 返回当前 Turn 的线程身份。
         */
        private String threadId() {
            return threadId;
        }

        /**
         * 返回最近一次已观察到的持久 revision。
         */
        private long threadRevision() {
            return threadRevision.get();
        }

        /**
         * 只接受单调递增的持久 revision，避免并发 delta 看到倒退状态。
         */
        private void observeRevision(long revision) {
            threadRevision.accumulateAndGet(revision, Math::max);
        }
    }

    /** 冷审批扫描记录该上下文是否由自己创建，以便 false attach 时不误删既有 owner。 */
    private record ThreadNotificationRegistration(TurnNotificationContext context, boolean created) {
    }

}
