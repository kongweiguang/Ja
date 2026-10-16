// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAdmissionReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputMutationReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInternalRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest.HistorySelection;
import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionService;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOwnerProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.application.api.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnCancellationListener;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentExecutionEvents;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationAttachmentPort;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationWorkspaceReferencePort;
import io.github.kongweiguang.ja.conversation.application.gateway.InitialContextSeedSource;
import io.github.kongweiguang.ja.conversation.application.gateway.JaOperationReceiptRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.PendingQueueInputSource;
import io.github.kongweiguang.ja.conversation.application.gateway.RuntimeLease;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.WorkspaceBindingResolver;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;

/**
 * Ja 的请求与展示边界；执行、事实恢复与状态推进均由唯一 AgentEngine 持有。
 */
public final class TurnService implements TurnUseCase, ChildTurnScheduler {
    private static final System.Logger LOG = System.getLogger(TurnService.class.getName());
    private static final int ADMISSION_STRIPES = 64;
    /**
     * 标记来自 Ja 本地可信 RPC 的用户明确裁决策略，不伪造认证主体身份。
     */
    private static final String TOOL_RECOVERY_AUTHORIZATION_ID = "ja.local-user-explicit-recovery";

    private final AgentEngine engine;
    private final AgentStore store;
    private final ExecutionRecordCodec records;
    private final ExecutionControlCodec controls;
    private final JaOperationReceiptRepository operationReceipts;
    private final ConversationProjectionRepository projections;
    private final JaExecutionContexts contexts;
    private final JaAdmissionParticipants admissions;
    private final InitialContextSeedSource initialContextSeeds;
    private final PendingQueueInputSource pendingQueueInputs;
    private final AgentExecutionEvents events;
    private final ConversationAttachmentPort attachments;
    private final ConversationWorkspaceReferencePort workspaceReferences;
    private final WorkspaceBindingResolver workspaces;
    private final WorkspaceAdmissionPort workspaceAdmissions;
    private final TurnRuntimeResolver runtimeResolver;
    private final AutomaticThreadTitleScheduler automaticTitles;
    private final Clock clock;
    private final Object[] admissionStripes = new Object[ADMISSION_STRIPES];
    private final ReentrantReadWriteLock admissionGate = new ReentrantReadWriteLock();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final ConcurrentMap<AgentIdentity, CompletableFuture<Void>> owners = new ConcurrentHashMap<>();
    private final ConcurrentMap<AgentIdentity, CompletableFuture<TurnResult>> completions = new ConcurrentHashMap<>();
    private final java.util.Set<AgentIdentity> terminalizingCompletions = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<AgentIdentity, AutoCloseable> suspendedSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentMap<AgentIdentity, CompletableFuture<Void>> suspendedSubscriptionReleases =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<AgentIdentity, Consumer<TurnResult>> terminalObservers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Consumer<CompletionStage<TurnResult>>> resumeContinuations =
            new ConcurrentHashMap<>();
    private final java.util.Set<AgentIdentity> resumeClaims = ConcurrentHashMap.newKeySet();
    private final AtomicReference<TurnCancellationListener> cancellationListener = new AtomicReference<>();
    private final AtomicReference<ProcessSessionUseCase> processSessions = new AtomicReference<>();
    private final Object preShutdownLock = new Object();
    private final AtomicReference<LongConsumer> preShutdown = new AtomicReference<>();

    /**
     * 依赖均从组合根显式注入，保证执行状态只由 AgentStore 与 Ja 只读投影提供。
     */
    public TurnService(AgentEngine engine, AgentStore store, ExecutionRecordCodec records,
                       ExecutionControlCodec controls, JaOperationReceiptRepository operationReceipts,
                       ConversationProjectionRepository projections, JaExecutionContexts contexts,
                       JaAdmissionParticipants admissions, InitialContextSeedSource initialContextSeeds,
                       PendingQueueInputSource pendingQueueInputs,
                       AgentExecutionEvents events,
                       ConversationAttachmentPort attachments,
                       ConversationWorkspaceReferencePort workspaceReferences,
                       WorkspaceBindingResolver workspaces, TurnRuntimeResolver runtimeResolver,
                       AutomaticThreadTitleScheduler automaticTitles, Clock clock) {
        this(engine, store, records, controls, operationReceipts, projections, contexts, admissions,
                initialContextSeeds, pendingQueueInputs, events, attachments, workspaceReferences,
                workspaces, runtimeResolver, automaticTitles, clock, WorkspaceAdmissionPort.unlocked());
    }

    /**
     * 生产构造器把 Turn admission 与 Workspace 根重绑接到同一个短期读写门，旧测试构造器仍使用隔离实现。
     */
    public TurnService(AgentEngine engine, AgentStore store, ExecutionRecordCodec records,
                       ExecutionControlCodec controls, JaOperationReceiptRepository operationReceipts,
                       ConversationProjectionRepository projections, JaExecutionContexts contexts,
                       JaAdmissionParticipants admissions, InitialContextSeedSource initialContextSeeds,
                       PendingQueueInputSource pendingQueueInputs,
                       AgentExecutionEvents events,
                       ConversationAttachmentPort attachments,
                       ConversationWorkspaceReferencePort workspaceReferences,
                       WorkspaceBindingResolver workspaces, TurnRuntimeResolver runtimeResolver,
                       AutomaticThreadTitleScheduler automaticTitles, Clock clock,
                       WorkspaceAdmissionPort workspaceAdmissions) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.store = Objects.requireNonNull(store, "store");
        this.records = Objects.requireNonNull(records, "records");
        this.controls = Objects.requireNonNull(controls, "controls");
        this.operationReceipts = Objects.requireNonNull(operationReceipts, "operationReceipts");
        this.projections = Objects.requireNonNull(projections, "projections");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.initialContextSeeds = Objects.requireNonNull(initialContextSeeds, "initialContextSeeds");
        this.pendingQueueInputs = Objects.requireNonNull(pendingQueueInputs, "pendingQueueInputs");
        this.events = Objects.requireNonNull(events, "events");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.workspaceReferences = Objects.requireNonNull(workspaceReferences, "workspaceReferences");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.workspaceAdmissions = Objects.requireNonNull(workspaceAdmissions, "workspaceAdmissions");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        this.automaticTitles = Objects.requireNonNull(automaticTitles, "automaticTitles");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (int index = 0; index < admissionStripes.length; index++) {
            admissionStripes[index] = new Object();
        }
    }

    /**
     * 组合根在进程会话 owner 完成创建后绑定一次；延后绑定避免扩大现有测试构造器和 Engine 所有权。
     */
    public void bindProcessSessions(ProcessSessionUseCase sessions) {
        if (!processSessions.compareAndSet(null, Objects.requireNonNull(sessions, "sessions"))) {
            throw new IllegalStateException("process sessions were already bound");
        }
    }

    /**
     * MESSAGE 投递不唤醒空闲会话；只有普通 USER 或显式 Child admission 才读取有限批次，内部/压缩按类型处理。
     */
    static List<AgentInputCommand.Enqueue> pendingMailboxInputs(
            TurnOrigin origin, String threadId, PendingQueueInputSource source) {
        Objects.requireNonNull(origin, "origin");
        if (origin != TurnOrigin.USER && origin != TurnOrigin.CHILD_TASK) {
            return List.of();
        }
        return loadPendingMailboxInputs(threadId, source);
    }

    /**
     * 隐藏请求只接纳纯 MESSAGE，避免内部 continuation 借邮箱绕过 USER 输入语义。
     */
    static List<AgentInputCommand.Enqueue> internalMailboxInputs(
            InternalTurnStartRequest request, PendingQueueInputSource source) {
        Objects.requireNonNull(request, "request");
        if (!request.origin().internal()) {
            throw new IllegalArgumentException("internal admission requires an internal origin");
        }
        List<AgentInputCommand.Enqueue> inputs = loadPendingMailboxInputs(request.threadId(), source);
        if (inputs.stream().anyMatch(input -> input.kind() != AgentInputCommand.Kind.MESSAGE)) {
            throw new IllegalStateException("internal mailbox source returned a non-MESSAGE input");
        }
        return inputs;
    }

    /**
     * 只读取有限 PENDING 快照；绑定状态只能由同一次 AgentStore 准入事务决定。
     */
    private static List<AgentInputCommand.Enqueue> loadPendingMailboxInputs(
            String threadId, PendingQueueInputSource source) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(source, "source");
        List<AgentInputCommand.Enqueue> inputs = List.copyOf(Objects.requireNonNull(
                source.loadPendingInputs(threadId, AgentInputQueue.MAX_ENTRIES), "pending queue inputs"));
        if (inputs.size() > AgentInputQueue.MAX_ENTRIES) {
            throw new IllegalStateException("pending queue source exceeded the Agent queue capacity");
        }
        return inputs;
    }

    /**
     * 首次 Plan 执行 admission 只读取 Task 创建时冻结的来源；普通继续和已有 Turn 不重新注入父上下文。
     */
    static Optional<InitialContextSeed> firstTaskContinuationSeed(
            InternalTurnStartRequest request, ThreadProjection thread, InitialContextSeedSource source) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(source, "source");
        if (!request.threadId().equals(thread.threadId())
            || request.expectedThreadRevision() != thread.revision()) {
            throw new IllegalArgumentException("continuation projection does not match its request");
        }
        if (request.origin() != TurnOrigin.PLAN_EXECUTION
            || !thread.turns().isEmpty()) {
            return Optional.empty();
        }
        return Objects.requireNonNull(source.findForFirstTurn(request.threadId()), "initial context seed");
    }

    /**
     * 将清理故障并入非终态错误；真实 terminal 已提交时不改写成功或失败的业务结果。
     */
    private static Throwable mergeCleanupFailure(boolean terminalProjectionEstablished, Throwable completionFailure,
                                                 Throwable cleanupFailure) {
        if (terminalProjectionEstablished) {
            return completionFailure;
        }
        if (completionFailure == null) {
            return cleanupFailure;
        }
        if (completionFailure != cleanupFailure) {
            completionFailure.addSuppressed(cleanupFailure);
        }
        return completionFailure;
    }

    /**
     * 按 Thread 的 turn_sequence 投影顺序选取最早的本地可执行项，等待和终态不能冒充 owner。
     */
    static Optional<TurnProjection> selectLocalOwner(ThreadProjection thread, Set<String> localIds) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(localIds, "localIds");
        if (localIds.isEmpty()) {
            return Optional.empty();
        }
        return thread.turns().stream()
                .filter(turn -> localIds.contains(turn.turnId()))
                .filter(TurnProjection::currentPath)
                .filter(turn -> turn.state() == TurnState.QUEUED || turn.state() == TurnState.RUNNING)
                .findFirst();
    }

    /**
     * 将已经版本校验的 Thread 快照冻结为 Engine 明确历史选择，避免在 seed 读取后重新拼装路径。
     */
    private static HistorySelection history(ThreadProjection thread) {
        return HistorySelection.explicit(currentPath(thread).stream().map(TurnProjection::turnId).toList(),
                thread.revision());
    }

    /**
     * AgentStore message ID 是不透明 Core 身份，reask 必须先投影成 Ja 时间线 item ID 比较。
     */
    static boolean matchesSourceMessageId(String expectedItemId, String coreMessageId) {
        return expectedItemId.equals(JaMessageIds.toItemId(coreMessageId));
    }

    /**
     * 只保留 Thread 的有序当前路径，帮助 continuation/reask 创建明确的历史前缀。
     */
    private static List<TurnProjection> currentPath(ThreadProjection thread) {
        return thread.turns().stream().filter(TurnProjection::currentPath).toList();
    }

    /**
     * 生成租户、Thread 和 Turn 均固定的 Store 复合键。
     */
    private static AgentStore.ExecutionKey executionKey(AgentIdentity identity) {
        return AppServerIdentity.local().executionKey(identity);
    }

    /**
     * 将未知或新增 Engine 状态显式拒绝，不通过小写字符串映射猜测 wire 行为。
     */
    private static TurnState state(String value) {
        try {
            return TurnState.valueOf(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("unknown Agent execution state", failure);
        }
    }

    /**
     * 每个并发条带保护一组相同 identity，不同会话无需共享锁。
     */
    private static int stripe(AgentIdentity identity) {
        return Math.floorMod(identity.hashCode(), ADMISSION_STRIPES);
    }

    /**
     * 只有非空宿主字段才写入请求，null 不会变成可误读的文本标记。
     */
    private static void put(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 传输幂等属性仅由明确 API 参数产生，不能从请求正文推导。
     */
    private static Map<String, String> attributes(String method, String operationKey, String fingerprint) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("ja.method", method);
        put(result, "ja.operationKey", operationKey);
        put(result, "ja.requestFingerprint", fingerprint);
        return result;
    }

    /**
     * mutation operation key 绑定被观察条目 revision，避免旧按钮作用于后续编辑。
     */
    private static String operation(String action, String inputId, long revision) {
        return "ja-" + action + "-" + inputId + "-" + revision;
    }

    /**
     * 等待只覆盖 Engine 的短持久控制提交，不等待 Provider 或工具执行完成。
     */
    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    /**
     * 识别 Store 或 Engine 包装后的容量异常，供 Task mailbox 保持 PENDING 并安全重试。
     */
    private static boolean causedByQueueFull(Throwable failure) {
        return causedBy(failure, AgentInputQueue.QueueFullException.class);
    }

    /**
     * Store 原子校验发现活动 owner 已失效时映射为可保留 mailbox 的稳定路由错误。
     */
    private static boolean causedByStoreConflict(Throwable failure) {
        return causedBy(failure, AgentStore.StoreConflictException.class);
    }

    /**
     * 检查受限异常因果链，不解析异常文案或把未知失败误判成可重试。
     */
    private static boolean causedBy(Throwable failure, Class<? extends Throwable> expectedType) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (expectedType.isInstance(cause)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * 保留原始可诊断 cause，但向调用方暴露稳定的不符合重答资格类别。
     */
    private static TurnUseCase.QuestionRecoveryException notReaskable() {
        return TurnUseCase.QuestionRecoveryException.of(TurnUseCase.QuestionRecoveryFailure.NOT_REASKABLE);
    }

    /**
     * 解开 CompletionException 只用于闭集持久状态核验，不向日志输出异常正文。
     */
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    /**
     * 订阅释放失败只写错误类别，展示客户端失败不能改写已经提交的业务事实；保留原 RuntimeException 分类。
     * PMD 无法识别分支中重抛的是同一异常对象，因此只在此方法抑制 PreserveStackTrace。
     *
     * @author kongweiguang
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static void closeSubscription(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception failure) {
            report("event subscription close", failure);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CompletionException(failure);
        }
    }

    /**
     * 暂停后只保留 durable event watcher；状态查询在锁外执行，stripe 只封闭终态判断后的登记竞态。
     * 关闭或已有 drain 时拒绝迟到登记，并在锁外释放，避免 sink ACK 阻塞 admission 与 shutdown。
     * 成功登记后 map 接管新订阅；未保留时本方法负责关闭。previous 是 map 已有 owner 的句柄，不由此处释放。
     * PMD 无法推断 putIfAbsent 的资源所有权转移，因此仅在本方法抑制 CloseResource。
     *
     * @author kongweiguang
     */
    @SuppressWarnings("PMD.CloseResource")
    private void retainSuspendedSubscription(AgentIdentity identity, AutoCloseable subscription) {
        boolean terminal;
        try {
            terminal = engine.query(identity).map(status -> state(status.state()).terminal()).orElse(false);
        } catch (RuntimeException queryFailure) {
            report("suspended event subscription state check", queryFailure);
            terminal = false;
        }
        boolean retained = false;
        boolean shutdown = false;
        boolean duplicate = false;
        synchronized (admissionStripes[stripe(identity)]) {
            shutdown = !accepting.get();
            CompletableFuture<TurnResult> completion = completions.get(identity);
            boolean completionTerminal = completion != null && completion.isDone();
            boolean terminalizing = terminalizingCompletions.contains(identity);
            boolean eligible = !shutdown && !terminal && !completionTerminal && !terminalizing;
            if (eligible && !suspendedSubscriptionReleases.containsKey(identity)) {
                AutoCloseable previous = suspendedSubscriptions.putIfAbsent(identity, subscription);
                retained = previous == null || previous == subscription;
                duplicate = previous != null && previous != subscription;
            } else if (eligible) {
                duplicate = true;
            }
        }
        if (!retained) {
            if (shutdown) {
                closeSubscriptionForShutdown(subscription);
            } else {
                closeSubscription(subscription);
            }
        }
        if (duplicate) {
            report("duplicate suspended event subscription", new IllegalStateException("duplicate watcher"));
        }
    }

    /**
     * 启动恢复只观察既有等待态事件；先占用身份级生命周期屏障，再锁外校验与 attach，避免与 resume 竞争。
     * 未能保留的订阅走即时 detach，因为它不再有调用方等待其水位，且不能拖住关停。
     * 被保留的句柄转交给 suspendedSubscriptions；其余路径由 finally 关闭，PMD 无法识别此 null 转移。
     *
     * @author kongweiguang
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public boolean observeApprovalWait(String turnId, ConversationTurnEventSink sink) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(sink, "sink");
        if (!accepting.get()) {
            return false;
        }
        TurnOwnerProjection owner = projections.readTurn(turnId).orElse(null);
        if (owner == null) {
            return false;
        }
        AgentIdentity identity = JaTurnRequestMapper.identity(owner.threadId(), turnId);
        Object stripe = admissionStripes[stripe(identity)];
        CompletableFuture<Void> observation = new CompletableFuture<>();
        synchronized (stripe) {
            if (!accepting.get() || terminalizingCompletions.contains(identity)
                    || owners.containsKey(identity) || completions.containsKey(identity)
                    || resumeClaims.contains(identity) || suspendedSubscriptions.containsKey(identity)
                    || suspendedSubscriptionReleases.putIfAbsent(identity, observation) != null) {
                return false;
            }
        }

        AutoCloseable subscription = null;
        boolean retained = false;
        try {
            if (!isApprovalWait(identity)) {
                return false;
            }
            subscription = Objects.requireNonNull(events.attach(identity, sink), "event subscription");
            if (!accepting.get()) {
                return false;
            }
            if (!isApprovalWait(identity)) {
                return false;
            }
            synchronized (stripe) {
                if (suspendedSubscriptionReleases.get(identity) == observation && accepting.get()
                        && !terminalizingCompletions.contains(identity) && !owners.containsKey(identity)
                        && !completions.containsKey(identity) && !resumeClaims.contains(identity)
                        && !suspendedSubscriptions.containsKey(identity)) {
                    retained = suspendedSubscriptions.putIfAbsent(identity, subscription) == null;
                    if (retained) {
                        subscription = null;
                        suspendedSubscriptionReleases.remove(identity, observation);
                        observation.complete(null);
                    }
                }
            }
            return retained;
        } finally {
            try {
                if (subscription != null) {
                    closeSubscriptionForShutdown(subscription);
                }
            } finally {
                synchronized (stripe) {
                    if (suspendedSubscriptionReleases.remove(identity, observation)) {
                        observation.complete(null);
                    }
                }
            }
        }
    }

    /**
     * 观察启动状态只允许 Core 权威状态仍处于审批等待或普通挂起，不从 UI 投影推断执行阶段。
     *
     * @author kongweiguang
     */
    private boolean isApprovalWait(AgentIdentity identity) {
        return engine.query(identity).map(status -> {
            TurnState current = state(status.state());
            return current == TurnState.WAITING_APPROVAL || current == TurnState.SUSPENDED;
        }).orElse(false);
    }

    /**
     * 显式恢复或终态完成前移除对应持久事件 watcher，确保同身份只能有一个活动桥订阅。
     * 唯一 release owner 关闭 map 中的句柄并结算屏障；并发调用只等待该屏障，不重复关闭资源。
     * PMD 无法推断身份级 release 协议，因此仅在本方法抑制 CloseResource。
     *
     * @author kongweiguang
     */
    @SuppressWarnings("PMD.CloseResource")
    private void releaseSuspendedSubscription(AgentIdentity identity) {
        Object stripe = admissionStripes[stripe(identity)];
        AutoCloseable subscription;
        CompletableFuture<Void> release;
        boolean owner;
        synchronized (stripe) {
            CompletableFuture<Void> existing = suspendedSubscriptionReleases.get(identity);
            if (existing != null) {
                release = existing;
                subscription = null;
                owner = false;
            } else {
                subscription = suspendedSubscriptions.get(identity);
                if (subscription == null) {
                    return;
                }
                release = new CompletableFuture<>();
                suspendedSubscriptionReleases.put(identity, release);
                owner = true;
            }
        }
        if (!owner) {
            release.join();
            return;
        }

        Throwable failure = null;
        try {
            closeSubscription(subscription);
        } catch (RuntimeException | Error closeFailure) {
            failure = closeFailure;
        } finally {
            synchronized (stripe) {
                suspendedSubscriptions.remove(identity, subscription);
                if (failure == null) {
                    release.complete(null);
                } else {
                    release.completeExceptionally(failure);
                }
                suspendedSubscriptionReleases.remove(identity, release);
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /**
     * 关闭服务前逐一释放等待中的投影 watcher；单个订阅关闭异常不得跳过其余身份。
     *
     * @author kongweiguang
     */
    private void releaseAllSuspendedSubscriptions(boolean shutdown) {
        java.util.Set<AgentIdentity> identities = new java.util.HashSet<>(suspendedSubscriptions.keySet());
        identities.addAll(suspendedSubscriptionReleases.keySet());
        Throwable failure = null;
        for (AgentIdentity identity : identities) {
            try {
                if (shutdown) {
                    detachSuspendedSubscriptionForShutdown(identity);
                } else {
                    releaseSuspendedSubscription(identity);
                }
            } catch (RuntimeException | Error closeFailure) {
                report("suspended event subscription release", closeFailure);
                failure = mergeCloseFailure(failure, closeFailure);
            }
        }
        throwCloseFailure(failure);
    }

    /**
     * Shutdown 即时 detach 当前 watcher，即使普通 waterline drain 正在进行也不等待其 future。
     * 从 map 移出的句柄交给 closeForShutdown 关闭或 detach，finally 结算屏障；PMD 无法追踪该委托 owner。
     * unchecked 首因按原对象重抛以保留关停错误分类，checked 失败包装时仍保留原 cause。
     * 因此仅在本方法抑制 CloseResource 与 PreserveStackTrace。
     * @author kongweiguang
     */
    @SuppressWarnings({"PMD.CloseResource", "PMD.PreserveStackTrace"})
    private void detachSuspendedSubscriptionForShutdown(AgentIdentity identity) {
        Object stripe = admissionStripes[stripe(identity)];
        AutoCloseable subscription;
        CompletableFuture<Void> release;
        synchronized (stripe) {
            subscription = suspendedSubscriptions.remove(identity);
            release = suspendedSubscriptionReleases.get(identity);
            if (subscription == null && release == null) {
                return;
            }
            if (release == null) {
                release = new CompletableFuture<>();
                suspendedSubscriptionReleases.put(identity, release);
            }
        }
        Throwable failure = null;
        try {
            if (subscription != null) {
                events.closeForShutdown(subscription);
            }
        } catch (Exception | Error closeFailure) {
            failure = closeFailure;
            if (closeFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (closeFailure instanceof Error error) {
                throw error;
            }
            throw new CompletionException(closeFailure);
        } finally {
            synchronized (stripe) {
                if (failure == null) {
                    release.complete(null);
                } else {
                    release.completeExceptionally(failure);
                }
                suspendedSubscriptionReleases.remove(identity, release);
            }
        }
    }

    /**
     * 关闭操作直接走事件端口的 shutdown detach；异常上抛，由聚合收尾记录并继续清理其他订阅。
     * @author kongweiguang
     */
    private void closeSubscriptionForShutdown(AutoCloseable subscription) {
        try {
            events.closeForShutdown(subscription);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    /**
     * 按固定序递归持有全部 admission stripes，为 accepting fence 原子化 late retention 边界。
     * @author kongweiguang
     */
    private void withAllAdmissionStripes(int index, Runnable action) {
        if (index == admissionStripes.length) {
            action.run();
            return;
        }
        synchronized (admissionStripes[index]) {
            withAllAdmissionStripes(index + 1, action);
        }
    }

    /**
     * 仅记录异常类型，避免工具参数、路径与 Provider 诊断写入 Ja 普通日志。
     */
    private static void report(String operation, Throwable failure) {
        LOG.log(System.Logger.Level.WARNING, operation + ": " + failure.getClass().getSimpleName());
    }

    /**
     * 同步绑定任务取消传播器，重复绑定暴露组合错误而不替换当前 owner。
     */
    public void bindCancellationListener(TurnCancellationListener listener) {
        if (!cancellationListener.compareAndSet(null, Objects.requireNonNull(listener, "listener"))) {
            throw new IllegalStateException("cancellation listener already bound");
        }
        listener.reconcilePending();
    }

    /**
     * 临时会话清理先于 Engine 关闭运行时执行资源。
     */
    public void bindPreShutdownHook(LongConsumer hook) {
        if (!preShutdown.compareAndSet(null, Objects.requireNonNull(hook, "hook"))) {
            throw new IllegalStateException("pre-shutdown hook already bound");
        }
    }

    /**
     * Interaction 与 Approval 共用同一个幂等恢复调度入口，回调不读取线程本地环境。
     */
    public InteractionService.ResumeScheduler resumeScheduler() {
        return this::scheduleResume;
    }

    /**
     * 普通提交与带幂等身份提交共用相同的准入路径。
     */
    @Override
    public Accepted start(TurnStartRequest request, ConversationTurnEventSink sink) {
        return start(request, sink, null, null);
    }

    /**
     * 幂等回执命中时读取原 execution 状态，不能再次运行用户请求或替换恢复身份。
     */
    @Override
    public Accepted start(TurnStartRequest request, ConversationTurnEventSink sink,
                          String operationKey, String fingerprint) {
        Accepted existing = existing(operationKey, "turn/start", fingerprint);
        if (existing != null) {
            return existing;
        }
        HistorySelection history = currentHistory(request.threadId(), request.expectedThreadRevision());
        return startUser(request, TurnOrigin.USER, sink, attributes("turn/start", operationKey, fingerprint),
                history, null);
    }

    /**
     * 隐藏继续只引用当前路径最后一个失败问题，不创建重复用户消息。
     */
    @Override
    public Accepted continueQuestion(InternalTurnStartRequest request, ConversationTurnEventSink sink) {
        return continueQuestion(request, sink, null, null);
    }

    /**
     * 问题身份与历史版本在准入前冻结，实际变更仍由 Store transaction participant 再校验。
     */
    @Override
    public Accepted continueQuestion(InternalTurnStartRequest request, ConversationTurnEventSink sink,
                                     String operationKey, String fingerprint) {
        Accepted existing = existing(operationKey, "turn/continue", fingerprint);
        if (existing != null) {
            return existing;
        }
        if (request.origin() != TurnOrigin.USER_CONTINUATION) {
            throw new IllegalArgumentException("invalid origin");
        }
        ThreadProjection thread = requireThreadRevision(request.threadId(), request.expectedThreadRevision());
        SourceTurn source = latestFailedSource(thread);
        Map<String, String> metadata = attributes("turn/continue", operationKey, fingerprint);
        metadata.put("ja.sourceMessageId", source.messageId());
        return startInternal(request, "继续回答当前路径最后一个尚未成功答复的问题，并结合已经提交的工具结果。",
                sink, metadata, null, thread, Optional.empty());
    }

    /**
     * 重答使用新用户输入和明确的历史前缀，不覆盖旧执行事实。
     */
    @Override
    public Accepted reask(TurnStartRequest request, String sourceMessageId, ConversationTurnEventSink sink) {
        return reask(request, sourceMessageId, sink, null, null);
    }

    /**
     * 旧分支只有精确来源仍位于末尾且最后尝试失败时才能剪切；CAS 再由 admission participant 执行。
     */
    @Override
    public Accepted reask(TurnStartRequest request, String sourceMessageId, ConversationTurnEventSink sink,
                          String operationKey, String fingerprint) {
        Accepted existing = existing(operationKey, "turn/reask", fingerprint);
        if (existing != null) {
            return existing;
        }
        ThreadProjection thread = requireThreadRevision(request.threadId(), request.expectedThreadRevision());
        SourceTurn latest = latestFailedSource(thread);
        if (!latest.messageId().equals(sourceMessageId)) {
            throw notReaskable();
        }
        List<String> visible = new ArrayList<>();
        for (TurnProjection turn : currentPath(thread)) {
            if (turn.turnId().equals(latest.ownerTurnId())) {
                break;
            }
            visible.add(turn.turnId());
        }
        Map<String, String> metadata = attributes("turn/reask", operationKey, fingerprint);
        metadata.put("ja.sourceMessageId", sourceMessageId);
        return startUser(request, TurnOrigin.USER, sink, metadata,
                HistorySelection.explicit(visible, thread.revision()), null);
    }

    /**
     * Plan 执行续跑只接纳显式内部来源和具名指令。
     */
    public Accepted startContinuation(InternalTurnStartRequest request, String instruction,
                                      ConversationTurnEventSink sink) {
        return startContinuation(request, instruction, sink, null);
    }

    /**
     * Plan 执行上下文由调用方显式快照传入；首轮 Task seed 只读取创建时持久快照。
     */
    public Accepted startContinuation(InternalTurnStartRequest request, String instruction,
                                      ConversationTurnEventSink sink, NativeExecutionSnapshot inherited) {
        ThreadProjection thread = requireThreadRevision(request.threadId(), request.expectedThreadRevision());
        Optional<InitialContextSeed> seed = firstTaskContinuationSeed(request, thread, initialContextSeeds);
        Map<String, String> metadata = attributes("plan/execute", null, null);
        put(metadata, "ja.planRevisionId", request.planRevisionId());
        put(metadata, "ja.runId", request.runId());
        return startInternal(request, instruction, sink, metadata, inherited, thread, seed);
    }

    /**
     * Task 先登记同事务 admission 快照；子任务路径仍复用普通 Engine user request。
     */
    @Override
    public Accepted startChild(TurnStartRequest request, ConversationTurnEventSink sink,
                               String sourceThreadId, String sourceTurnId) {
        NativeExecutionSnapshot inherited = sourceThreadId == null || sourceTurnId == null ? null
                : contexts.find(JaTurnRequestMapper.identity(sourceThreadId, sourceTurnId));
        AgentIdentity identity = JaTurnRequestMapper.identity(request.threadId(), request.turnId());
        JaAdmissionParticipants.PreparedAdmission prepared = admissions.find(identity)
                .orElseThrow(() -> new IllegalStateException("child admission is not registered"));
        AdmissionFacts facts = prepared.facts();
        Map<String, String> metadata = attributes(facts.method(), facts.operationKey(), facts.requestFingerprint());
        put(metadata, "ja.parentTurnId", facts.parentTurnId());
        put(metadata, "ja.rootTurnId", facts.rootTurnId());
        put(metadata, "ja.sourceMessageId", facts.sourceMessageId());
        return startUser(request, TurnOrigin.CHILD_TASK, sink, metadata,
                HistorySelection.explicit(List.of(), request.expectedThreadRevision()), inherited);
    }

    /**
     * 普通用户与显式 Child admission 都先复用原批次再读 PENDING，防止重试混入后来消息或漏掉并发投递；
     * receipt hook 先建立 RPC 水位，首次标题仍在 Engine 返回后、终态 observer 挂接前发布。
     */
    private Accepted startUser(TurnStartRequest request, TurnOrigin origin, ConversationTurnEventSink sink,
                               Map<String, String> metadata, HistorySelection history,
                               NativeExecutionSnapshot inherited) {
        UserContent content = validateReferences(request.workspaceId(), request.content());
        AgentIdentity identity = JaTurnRequestMapper.identity(request.threadId(), request.turnId());
        boolean first = projections.readThread(request.threadId()).map(value -> value.turns().isEmpty()).orElse(true);
        Accepted accepted = submit(identity, sink, inherited, beforeSchedule -> {
                    AgentRequest agentRequest = JaTurnRequestMapper.user(request, content, origin, history, metadata);
                    if (origin == TurnOrigin.USER && first && request.initialContextSeed().isEmpty()) {
                        Optional<InitialContextSeed> seed = initialContextSeeds.findForFirstTurn(request.threadId());
                        if (seed.isPresent()) {
                            agentRequest = agentRequest.withInitialContextSeed(seed.orElseThrow());
                        }
                    }
                    List<AgentInputCommand.Enqueue> initialInputs = engine.readAdmittedInitialInputs(agentRequest)
                            .orElseGet(() -> pendingMailboxInputs(origin, request.threadId(), pendingQueueInputs));
                    return engine.submit(agentRequest.withInitialQueuedInputs(initialInputs), beforeSchedule);
                },
                first ? result -> scheduleTitle(request, content, result, sink, inherited) : result -> {
                }, origin == TurnOrigin.USER && first
                        ? () -> publishProvisionalTitle(request.threadId(), sink) : () -> { });
        if (origin == TurnOrigin.USER || origin == TurnOrigin.CHILD_TASK) {
            drainPendingMailbox(request.threadId());
        }
        return accepted;
    }

    /**
     * Admission 与活动 Turn 查询之间可能有并发投递；提交成功后再读一次 PENDING，
     * 以相同 Engine 队列原子绑定能命中的消息。容量、终态竞态或未知失败都保留待处理事实，
     * 不把已经受理的用户请求改报失败。
     */
    private void drainPendingMailbox(String threadId) {
        List<AgentInputCommand.Enqueue> pending;
        try {
            pending = pendingMailboxInputs(TurnOrigin.USER, threadId, pendingQueueInputs);
            Optional<ActiveTurn> active = findActiveTurn(threadId);
            if (active.isEmpty()) {
                return;
            }
            ActiveTurn target = active.orElseThrow();
            for (AgentInputCommand.Enqueue command : pending) {
                try {
                    enqueueTaskMessage(target.turnId(), command);
                } catch (InputMutationException expectedRace) {
                    if (expectedRace.failure() == InputMutationFailure.QUEUE_FULL
                        || expectedRace.failure() == InputMutationFailure.TURN_NOT_FOUND) {
                            return;
                        }
                    report("pending mailbox drain", expectedRace);
                    return;
                } catch (RuntimeException uncertain) {
                    report("pending mailbox drain", uncertain);
                    return;
                }
            }
        } catch (RuntimeException uncertain) {
            report("pending mailbox lookup", uncertain);
        }
    }

    /**
     * 内部指令与 MESSAGE 快照共用 admission；重放读取冻结批次，新准入才读取 PENDING。
     */
    private Accepted startInternal(InternalTurnStartRequest request, String instruction,
                                   ConversationTurnEventSink sink, Map<String, String> metadata,
                                   NativeExecutionSnapshot inherited, ThreadProjection thread,
                                   Optional<InitialContextSeed> initialContextSeed) {
        if (instruction == null || instruction.isBlank() || instruction.length() > 1_000_000) {
            throw new IllegalArgumentException("invalid internal instruction");
        }
        AgentInternalRequest baseRequest = JaTurnRequestMapper.internal(request, instruction, history(thread), metadata);
        AgentInternalRequest core = initialContextSeed.map(baseRequest::withInitialContextSeed).orElse(baseRequest);
        Accepted accepted = submit(core.identity(), sink, inherited,
                beforeSchedule -> {
                    List<AgentInputCommand.Enqueue> initialInputs = engine.readAdmittedInitialInputs(core)
                            .orElseGet(() -> internalMailboxInputs(request, pendingQueueInputs));
                    return engine.submitInternal(core.withInitialQueuedInputs(initialInputs), beforeSchedule);
                }, result -> {
                });
        drainPendingMailbox(request.threadId());
        return accepted;
    }

    /**
     * 按会话分条带保护准入与关闭竞争；Engine 在真实 admission commit 后、调度前调用 hook，
     * 用 receipt 推进通知基线并冻结 Accepted revision，避免随后执行提交改变受理回执。
     */
    private Accepted submit(AgentIdentity identity, ConversationTurnEventSink sink,
                            NativeExecutionSnapshot inherited,
                            Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> admission,
                            Consumer<TurnResult> terminalObserver) {
        return submit(identity, sink, inherited, admission, terminalObserver, () -> { }, false);
    }

    /**
     * 为必须先于终态回调挂接的准入后元数据保留显式顺序。
     */
    private Accepted submit(AgentIdentity identity, ConversationTurnEventSink sink,
                            NativeExecutionSnapshot inherited,
                            Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> admission,
                            Consumer<TurnResult> terminalObserver,
                            Runnable admissionObserver) {
        return submit(identity, sink, inherited, admission, terminalObserver, admissionObserver, false);
    }

    /**
     * 在相同 admission 锁序下拒绝重复恢复，避免本地 owner 检查与新派发之间出现竞态。
     */
    private Accepted submit(AgentIdentity identity, ConversationTurnEventSink sink,
                            NativeExecutionSnapshot inherited,
                            Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> admission,
                            Consumer<TurnResult> terminalObserver, boolean requireNoLocalOwner) {
        return submit(identity, sink, inherited, admission, terminalObserver, () -> { }, requireNoLocalOwner);
    }

    /**
     * 在相同 admission 锁序下拒绝重复恢复，并保留提交后观察器相对于终态回调的原有顺序。
     * Workspace 租约只保留同步准入屏障，退出资源作用域才释放，无需在业务体中读取租约对象。
     * 首次 Child 的 Thread 与执行在同一事务落库，租约归属必须来自精确身份的准入快照，不能提前要求 Child 投影。
     *
     * @author kongweiguang
     */
    private Accepted submit(AgentIdentity identity, ConversationTurnEventSink sink,
                            NativeExecutionSnapshot inherited,
                            Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> admission,
                            Consumer<TurnResult> terminalObserver,
                            Runnable admissionObserver,
                            boolean requireNoLocalOwner) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(admissionObserver, "admissionObserver");
        String workspaceId = admissions.find(identity).map(prepared -> prepared.facts().workspaceId())
                .orElseGet(() -> projections.readThread(identity.threadId())
                        .orElseThrow(() -> new IllegalStateException("Turn Thread projection is missing"))
                        .workspaceId());
        try (WorkspaceAdmissionPort.Lease ignored =
                     workspaceAdmissions.acquire(workspaceId)) {
            while (true) {
                // Draining can wait for an event sink, so it must precede both admissionGate and the identity stripe.
                releaseSuspendedSubscription(identity);
                Lock gate = admissionGate.readLock();
                gate.lock();
                boolean retryRelease = false;
                try {
                    if (!accepting.get()) {
                        throw new RejectedExecutionException("SHUTTING_DOWN");
                    }
                    synchronized (admissionStripes[stripe(identity)]) {
                        if (requireNoLocalOwner && owners.containsKey(identity)) {
                            throw TurnUseCase.TurnResumeException.of(
                                    TurnUseCase.ResumeFailure.TURN_RESUME_ORDER_CONFLICT);
                        }
                        if (suspendedSubscriptions.containsKey(identity)
                                || suspendedSubscriptionReleases.containsKey(identity)) {
                            retryRelease = true;
                        } else {
                            return admit(identity, sink, inherited, admission, terminalObserver, admissionObserver);
                        }
                    }
                } finally {
                    gate.unlock();
                }
                if (!retryRelease) {
                    throw new IllegalStateException("suspended subscription admission fence changed unexpectedly");
                }
            }
        }
    }

    /**
     * 订阅和原生上下文先于准入建立；同身份重入可复用不再触发 hook 的 pending handle，故必须等待其原始 receipt，
     * 再转交终态回调释放资源。receipt 阶段失败等转交前错误均同步清理，不能借后续 query 水位冒充新准入。
     * PMD 无法识别异步回调持有的资源生命周期，因此仅在本方法抑制 CloseResource。
     */
    @SuppressWarnings("PMD.CloseResource")
    private Accepted admit(AgentIdentity identity, ConversationTurnEventSink sink,
                           NativeExecutionSnapshot inherited,
                           Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> admission,
                           Consumer<TurnResult> terminalObserver,
                           Runnable admissionObserver) {
        JaExecutionContexts.Binding contextBinding = contexts.bind(identity, inherited);
        AutoCloseable subscription = null;
        CompletableFuture<TurnResult> completion = null;
        CompletableFuture<Void> owner = null;
        boolean ownsCompletion = false;
        boolean ownsObserver = false;
        boolean ownerRegistered = false;
        boolean outcomeTransferred = false;
        try {
            subscription = Objects.requireNonNull(events.attach(identity, sink), "event subscription");
            CompletableFuture<TurnResult> freshCompletion = new CompletableFuture<>();
            CompletableFuture<TurnResult> existingCompletion = completions.putIfAbsent(identity, freshCompletion);
            completion = existingCompletion == null ? freshCompletion : existingCompletion;
            ownsCompletion = existingCompletion == null;
            ownsObserver = terminalObservers.putIfAbsent(identity, terminalObserver) == null;
            owner = new CompletableFuture<>();
            if (owners.putIfAbsent(identity, owner) != null) {
                throw new IllegalStateException("execution already has an active dispatch owner");
            }
            ownerRegistered = true;
            AtomicReference<AgentAdmissionReceipt> receipt = new AtomicReference<>();
            Consumer<AgentAdmissionReceipt> beforeSchedule = committed -> {
                AgentAdmissionReceipt accepted = Objects.requireNonNull(committed, "admission receipt");
                if (!receipt.compareAndSet(null, accepted)) {
                    throw new IllegalStateException("admission receipt was observed more than once");
                }
                sink.onThreadRevision(identity, accepted.threadRevision());
            };
            AgentExecutionHandle handle = Objects.requireNonNull(admission.apply(beforeSchedule),
                    "Engine execution handle");
            Optional<AgentAdmissionReceipt> handleReceipt = handle.admissionReceipt().toCompletableFuture().join();
            if (handleReceipt.isPresent()) {
                AgentAdmissionReceipt accepted = handleReceipt.orElseThrow();
                AgentAdmissionReceipt observed = receipt.get();
                if (observed == null) {
                    sink.onThreadRevision(identity, accepted.threadRevision());
                } else if (!accepted.equals(observed)) {
                    throw new IllegalStateException("admission hook receipt differs from execution handle receipt");
                }
            }
            admissionObserver.run();
            CompletableFuture<TurnResult> executionCompletion = completion;
            CompletableFuture<Void> executionOwner = owner;
            AutoCloseable executionSubscription = subscription;
            handle.completion().whenComplete((outcome, failure) -> dispatchOutcome(identity, executionOwner,
                    executionCompletion, contextBinding, executionSubscription, outcome, failure));
            outcomeTransferred = true;
            AgentExecutionStatus status = engine.query(identity)
                    .orElseThrow(() -> new IllegalStateException("admission status missing"));
            long threadRevision = handleReceipt.map(AgentAdmissionReceipt::threadRevision)
                    .orElse(status.threadRevision());
            return new Accepted(identity.threadId(), identity.turnId(), threadRevision,
                    !state(status.state()).terminal(), completion.minimalCompletionStage());
        } catch (RuntimeException | Error failure) {
            if (!outcomeTransferred) {
                cleanupAdmission(identity, contextBinding, subscription, completion, owner, terminalObserver,
                        ownsCompletion, ownsObserver, ownerRegistered, failure);
            }
            throw failure;
        }
    }

    /**
     * 事件 sink 排空完成后再注册终态回调，保证首次标题先于可能立即结束的 Turn；通知故障不撤销已提交准入。
     */
    private void publishProvisionalTitle(String threadId, ConversationTurnEventSink sink) {
        try {
            automaticTitles.publishProvisionalTitle(threadId, sink).toCompletableFuture().join();
        } catch (RuntimeException failure) {
            report("provisional title publication", unwrap(failure));
        }
    }

    /**
     * Engine 回调线程只负责无阻塞分派；虚拟线程无法创建时同步结算，仍释放绑定、订阅和 owner。
     */
    private void dispatchOutcome(AgentIdentity identity, CompletableFuture<Void> owner,
                                 CompletableFuture<TurnResult> completion,
                                 JaExecutionContexts.Binding contextBinding, AutoCloseable subscription,
                                 AgentExecutionOutcome outcome, Throwable failure) {
        try {
            Thread.ofVirtual().name("ja-turn-outcome-projection")
                    .start(() -> settleOutcome(identity, owner, completion, contextBinding, subscription,
                            outcome, failure));
        } catch (RuntimeException | Error dispatchFailure) {
            report("outcome projection dispatch", dispatchFailure);
            settleOutcome(identity, owner, completion, contextBinding, subscription, outcome, failure);
        }
    }

    /**
     * 准入未转交给 Engine 结果回调时释放本次取得的全部资源与仅由本次调用登记的内存项。
     */
    private void cleanupAdmission(AgentIdentity identity, JaExecutionContexts.Binding contextBinding,
                                  AutoCloseable subscription, CompletableFuture<TurnResult> completion,
                                  CompletableFuture<Void> owner, Consumer<TurnResult> terminalObserver,
                                  boolean ownsCompletion, boolean ownsObserver, boolean ownerRegistered,
                                  Throwable admissionFailure) {
        try {
            contextBinding.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != admissionFailure) {
                admissionFailure.addSuppressed(cleanupFailure);
            }
        }
        try {
            if (subscription != null) {
                closeSubscription(subscription);
            }
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != admissionFailure) {
                admissionFailure.addSuppressed(cleanupFailure);
            }
        } finally {
            if (ownerRegistered) {
                owners.remove(identity, owner);
                owner.complete(null);
            }
            if (ownsCompletion && completion != null) {
                completions.remove(identity, completion);
            }
            if (ownsObserver) {
                terminalObservers.remove(identity, terminalObserver);
            }
        }
    }

    /**
     * 先释放本地运行资源再通知终态；清理任一资源失败也必须释放 owner，避免卡住关机或恢复。
     *
     * @author kongweiguang
     */
    private void settleOutcome(AgentIdentity identity, CompletableFuture<Void> owner,
                               CompletableFuture<TurnResult> completion,
                               JaExecutionContexts.Binding contextBinding, AutoCloseable subscription,
                               AgentExecutionOutcome outcome, Throwable failure) {
        TurnResult terminalResult = null;
        Throwable completionFailure = null;
        boolean cancellationEstablished = false;
        boolean suspended = failure == null && outcome instanceof AgentExecutionOutcome.Suspended;
        try {
            if (failure != null) {
                Optional<TurnState> persisted = engine.query(identity).map(status -> state(status.state()));
                cancellationEstablished = persisted.filter(status -> status == TurnState.CANCELLED).isPresent();
                if (persisted.filter(TurnState::terminal).isPresent()) {
                    terminalResult = events.terminalResult(identity);
                } else {
                    completionFailure = unwrap(failure);
                }
            } else if (outcome instanceof AgentExecutionOutcome.Suspended) {
                // 持久等待保持同一 Ja completion，释放此轮 owner 后由显式 resume 继续它。
            } else if (outcome instanceof AgentExecutionOutcome.Completed(
                    io.github.kongweiguang.ja.agent.application.api.TurnResult result
            )) {
                cancellationEstablished = result.state() == TurnState.CANCELLED;
                terminalResult = Objects.requireNonNull(events.result(identity, result),
                        "terminal event projection");
            } else {
                completionFailure = new IllegalStateException("unknown Engine outcome");
            }
        } catch (RuntimeException | Error projectionFailure) {
            completionFailure = projectionFailure;
        } finally {
            if (cancellationEstablished) {
                try {
                    cancelProcessSessions(identity);
                } catch (RuntimeException | Error cleanupFailure) {
                    report("cancelled Turn process cleanup", cleanupFailure);
                    completionFailure = mergeCleanupFailure(false, completionFailure, cleanupFailure);
                }
            }
            try {
                contextBinding.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (terminalResult != null) {
                    report("execution context close", cleanupFailure);
                }
                completionFailure = mergeCleanupFailure(terminalResult != null, completionFailure, cleanupFailure);
            } finally {
                try {
                    if (suspended) {
                        retainSuspendedSubscription(identity, subscription);
                    } else {
                        closeSubscription(subscription);
                    }
                } catch (RuntimeException | Error cleanupFailure) {
                    if (terminalResult != null) {
                        report("event subscription close", cleanupFailure);
                    }
                    completionFailure = mergeCleanupFailure(terminalResult != null, completionFailure, cleanupFailure);
                } finally {
                    owners.remove(identity, owner);
                    owner.complete(null);
                }
            }
        }
        if (terminalResult != null && completionFailure == null) {
            finish(identity, completion, terminalResult);
        }
        else if (completionFailure != null) {
            failCompletion(identity, completion, completionFailure);
        }
    }

    /**
     * 非终态投影失败后移除同一执行的内存回执，使调用方重试时重新读取持久 Engine 状态。
     *
     * @author kongweiguang
     */
    private void failCompletion(AgentIdentity identity, CompletableFuture<TurnResult> completion,
                                Throwable failure) {
        completion.completeExceptionally(failure);
        if (completions.remove(identity, completion)) {
            terminalObservers.remove(identity);
        }
    }

    /**
     * 先在 identity stripe 建立终态 fence，再锁外释放 watcher；完成 future 后才撤 fence，封闭过期 query 的迟到登记。
     *
     * @author kongweiguang
     */
    private void finish(AgentIdentity identity, CompletableFuture<TurnResult> completion, TurnResult result) {
        Object stripe = admissionStripes[stripe(identity)];
        synchronized (stripe) {
            if (completion.isDone() || !terminalizingCompletions.add(identity)) {
                return;
            }
        }
        try {
            try {
                releaseSuspendedSubscription(identity);
            } catch (RuntimeException | Error releaseFailure) {
                completion.completeExceptionally(releaseFailure);
                terminalObservers.remove(identity);
                return;
            }
            if (!completion.complete(result)) {
                return;
            }
            Consumer<TurnResult> observer = terminalObservers.remove(identity);
            if (observer != null) {
                try {
                    observer.accept(result);
                } catch (RuntimeException notificationFailure) {
                    report("terminal observer", notificationFailure);
                }
            }
        } finally {
            synchronized (stripe) {
                terminalizingCompletions.remove(identity);
            }
        }
    }

    /**
     * 取消或幂等重试没有本地 Engine outcome 时，只从 Store terminal watermark 恢复投影。
     */
    private void finishFromStore(AgentIdentity identity, CompletableFuture<TurnResult> completion) {
        TurnResult result = events.terminalResult(identity);
        finish(identity, completion, result);
    }

    /**
     * ACK 状态和 revision 来自 Engine，不在 Ja 应用层自行计算版本。
     */
    private Accepted accepted(AgentIdentity identity, long revision, boolean queued,
                              CompletionStage<TurnResult> completion) {
        return new Accepted(identity.threadId(), identity.turnId(), revision, queued, completion);
    }

    /**
     * 显式恢复接受暂停态或本进程已失去 owner 的非终态；最终执行权仍由 Engine 持久 lease 裁决。
     */
    @Override
    public Accepted resume(String turnId, long revision, ConversationTurnEventSink sink) {
        return resume(turnId, revision, sink, null);
    }

    /**
     * 调用方显式快照客户端环境；未保存的 OS 上下文不会从后台线程恢复。
     */
    public Accepted resume(String turnId, long revision, ConversationTurnEventSink sink,
                           NativeExecutionSnapshot inherited) {
        TurnOwnerProjection turn = requireTurn(turnId);
        TurnState state = turn.turn().state();
        boolean resumable = state == TurnState.SUSPENDED || state == TurnState.QUEUED || state == TurnState.RUNNING;
        if (turn.threadRevision() != revision || !resumable) {
            throw TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE);
        }
        AgentIdentity identity = JaTurnRequestMapper.identity(turn.threadId(), turnId);
        return submit(identity, sink, inherited, ignored -> engine.resume(identity), result -> {
        }, true);
    }

    /**
     * 等旧执行 owner 释放后读取最新投影；只接受至少已反映触发恢复之提交版本的快照，避免旧状态越过 CAS。
     * 同一身份最多有一个有效 resume 准入；与冷启动观察在 stripe 内互斥，实际执行权仍由 Engine lease 裁决。
     *
     * @author kongweiguang
     */
    private void scheduleResume(String threadId, String turnId, long expectedRevision,
                                ConversationTurnEventSink sink, NativeExecutionSnapshot nativeContext) {
        AgentIdentity identity = JaTurnRequestMapper.identity(threadId, turnId);
        synchronized (admissionStripes[stripe(identity)]) {
            if (!resumeClaims.add(identity)) {
                return;
            }
        }
        CompletableFuture<Void> previousOwner = owners.get(identity);
        CompletionStage<Void> released = previousOwner == null
                ? CompletableFuture.completedFuture(null) : previousOwner.handle((ignored, failure) -> null);
        released.whenComplete((ignored, ownerFailure) -> {
            try {
                Thread.ofVirtual().name("ja-turn-resume").start(() -> resumeAfterOwnerRelease(identity,
                        threadId, turnId, expectedRevision, sink, nativeContext));
            } catch (RuntimeException | Error dispatchFailure) {
                resumeClaims.remove(identity);
                report("turn resume dispatch", dispatchFailure);
            }
        });
    }

    /**
     * 仅在旧 owner 释放后检查提交水位并显式恢复；失败或无法分派时释放幂等 claim 以允许重试。
     */
    private void resumeAfterOwnerRelease(AgentIdentity identity, String threadId, String turnId,
                                         long expectedRevision, ConversationTurnEventSink sink,
                                         NativeExecutionSnapshot nativeContext) {
        try {
            TurnOwnerProjection latest = projections.readTurn(turnId).orElse(null);
            if (latest == null || !latest.threadId().equals(threadId)
                || latest.turn().state() != TurnState.SUSPENDED) {
                    return;
                }
            if (latest.threadRevision() < expectedRevision) {
                throw new IllegalStateException("turn projection has not reached the committed resume revision");
            }
            Accepted accepted = resume(turnId, latest.threadRevision(), sink, nativeContext);
            Consumer<CompletionStage<TurnResult>> continuation = resumeContinuations.remove(turnId);
            if (continuation != null) {
                continuation.accept(accepted.completion());
            }
        } catch (RuntimeException failure) {
            report("turn resume", failure);
        } finally {
            resumeClaims.remove(identity);
        }
    }

    /**
     * 用户恢复裁决读取最新持久 Tool recovery decision，并绑定其真实 mutation version。
     */
    @Override
    public ToolRecoveryResponse respondToolRecovery(ToolRecoveryRequest request) {
        TurnOwnerProjection turn = requireTurn(request.turnId());
        AgentIdentity identity = JaTurnRequestMapper.identity(turn.threadId(), request.turnId());
        ToolRecoveryDecision recovery = latestPendingRecovery(identity, request.callId()).orElseThrow(
                () -> TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE));
        if (turn.threadRevision() != request.expectedThreadRevision()
            || recovery.revision() != request.expectedRecoveryRevision()) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT, "recovery revision changed");
        }
        await(engine.resolveToolRecovery(identity, request.operationKey(), recovery.recoveryId(),
                recovery.callId(), recovery.revision(),
                request.disposition() == ToolRecoveryDisposition.RETRY ? RecoveryCommand.Decision.RETRY
                        : RecoveryCommand.Decision.SKIP,
                TOOL_RECOVERY_AUTHORIZATION_ID));
        long revision = engine.query(identity).orElseThrow().threadRevision();
        return new ToolRecoveryResponse(turn.threadId(), request.turnId(), revision, request.disposition(), true);
    }

    /**
     * 取消事实先由 Engine 持久化，完成后再通知子任务协调者处理业务取消传播。
     */
    @Override
    public CancelResult cancel(String turnId) {
        TurnOwnerProjection before = requireTurn(turnId);
        AgentIdentity identity = JaTurnRequestMapper.identity(before.threadId(), turnId);
        if (before.turn().state().terminal()) {
            if (before.turn().state() == TurnState.CANCELLED) {
                cancelProcessSessions(identity);
            }
            CompletableFuture<TurnResult> completion = completions.computeIfAbsent(identity,
                    ignored -> new CompletableFuture<>());
            finishFromStore(identity, completion);
            return new CancelResult(false, turnId, before.turn().state(), before.threadRevision(),
                    completion.minimalCompletionStage());
        }
        await(engine.cancel(identity, "ja-cancel-" + turnId, "USER_CANCELLED"));
        AgentExecutionStatus status = engine.query(identity).orElseThrow();
        TurnState state = state(status.state());
        RuntimeException processCleanupFailure = null;
        if (state == TurnState.CANCELLED) {
            try {
                cancelProcessSessions(identity);
            } catch (RuntimeException cleanupFailure) {
                processCleanupFailure = cleanupFailure;
                report("cancelled Turn process cleanup", cleanupFailure);
            }
        }
        CompletableFuture<TurnResult> completion = completions.computeIfAbsent(identity,
                ignored -> new CompletableFuture<>());
        if (state.terminal()) {
            finishFromStore(identity, completion);
        }
        TurnCancellationListener listener = cancellationListener.get();
        if (listener != null) {
            try {
                listener.cancellationClaimed(before.threadId(), turnId);
            } catch (RuntimeException failure) {
                report("cancellation propagation", failure);
            }
        }
        if (processCleanupFailure != null) {
            throw new IllegalStateException("cancelled Turn process sessions could not be closed",
                    processCleanupFailure);
        }
        return new CancelResult(true, turnId, state, status.threadRevision(),
                completion.minimalCompletionStage());
    }

    /**
     * 取消仅由准确 Turn 和当前代际界定；借用全局注册表只清理该 Turn，不能关闭 App Server 的资源 owner。
     */
    @SuppressWarnings("PMD.CloseResource")
    private void cancelProcessSessions(AgentIdentity identity) {
        ProcessSessionUseCase sessions = processSessions.get();
        if (sessions == null) {
            throw new IllegalStateException("process session owner is not bound");
        }
        ThreadProjection thread = projections.readThread(identity.threadId())
                .orElseThrow(() -> new IllegalStateException("cancelled Turn Thread projection is missing"));
        sessions.cancelTurn(new ProcessSessionUseCase.Identity(thread.workspaceId(), thread.threadId(),
                identity.turnId(), sessions.generation()));
    }

    /**
     * Plan 暂停持久游标并结束本次 owner，不能把取消事实伪装回可恢复状态。
     */
    public CompletionStage<Void> suspendPlanRun(String turnId) {
        TurnOwnerProjection turn = requireTurn(turnId);
        return engine.suspend(JaTurnRequestMapper.identity(turn.threadId(), turnId),
                "ja-plan-pause-" + turnId + "-" + turn.turn().mutationVersion(), "PLAN_PAUSED");
    }

    /**
     * 使用稳定输入身份写入 Engine queue command；缺失 kind 不能静默解释为 follow-up。
     */
    @Override
    public InputMutation enqueueInput(String turnId, UserContent content, InputKind kind,
                                      ConversationTurnEventSink sink, String operationKey, String fingerprint) {
        Objects.requireNonNull(kind, "kind");
        String operation = operationKey == null ? "ja-input-" + UUID.randomUUID() : operationKey;
        String inputId = "input_" + UUID.nameUUIDFromBytes(operation.getBytes(StandardCharsets.UTF_8));
        Map<String, String> metadata = new LinkedHashMap<>();
        put(metadata, "ja.operationKey", operationKey);
        put(metadata, "ja.requestFingerprint", fingerprint);
        return mutate(turnId, new AgentInputCommand.Enqueue(operation, inputId,
                kind == InputKind.STEERING ? AgentInputCommand.Kind.STEERING : AgentInputCommand.Kind.FOLLOW_UP,
                validateQueued(turnId, content), metadata));
    }

    /**
     * 将已持久化的 Task 消息作为 MESSAGE 投递给活动 Turn，保留 mailbox 来源且不生成手输气泡。
     */
    @Override
    public InputMutation enqueueTaskMessage(String turnId, AgentInputCommand.Enqueue command) {
        Objects.requireNonNull(command, "command");
        if (command.kind() != AgentInputCommand.Kind.MESSAGE) {
            throw new IllegalArgumentException("task message queue requires MESSAGE kind");
        }
        TurnOwnerProjection turn = requireTurn(turnId);
        if (turn.turn().state().terminal()) {
            throw InputMutationException.of(InputMutationFailure.TURN_NOT_FOUND);
        }
        UserContent content = validateQueued(turnId, command.content());
        AgentInputCommand.Enqueue validated = new AgentInputCommand.Enqueue(command.operationKey(),
                command.inputId(), command.kind(), content, command.hostMetadata());
        try {
            return mutate(turn, validated);
        } catch (RuntimeException failure) {
            if (causedByStoreConflict(failure)) {
                throw InputMutationException.of(InputMutationFailure.TURN_NOT_FOUND, failure);
            }
            throw failure;
        }
    }

    /**
     * 条目优先级以已观察 input revision 做 CAS，队列消费顺序由 Core reducer 决定。
     */
    @Override
    public InputMutation prioritizeInput(String turnId, String inputId, long revision) {
        return mutate(turnId, new AgentInputCommand.Prioritize(operation("prioritize", inputId, revision),
                inputId, revision, Map.of()));
    }

    /**
     * 编辑沿用 Workspace 与 Skill 校验，不允许排队输入绕过初始准入约束。
     */
    @Override
    public InputMutation updateInput(String turnId, String inputId, long revision, UserContent content) {
        return mutate(turnId, new AgentInputCommand.Update("ja-update-" + UUID.randomUUID(), inputId,
                revision, validateQueued(turnId, content), Map.of()));
    }

    /**
     * 删除只影响版本仍匹配的未消费输入，不会抹去已经写入历史的消息。
     */
    @Override
    public InputMutation deleteInput(String turnId, String inputId, long revision) {
        return mutate(turnId, new AgentInputCommand.Delete(operation("delete", inputId, revision),
                inputId, revision, Map.of()));
    }

    /**
     * mutation ACK 之后重新读取 Store queue 与正文事实，响应不依赖本地缓存。
     */
    private InputMutation mutate(String turnId, AgentInputCommand command) {
        TurnOwnerProjection turn = requireTurn(turnId);
        return mutate(turn, command);
    }

    /**
     * 使用同一 Turn 投影执行变更，避免校验后的输入改投到另一个执行身份。
     * Workspace 租约只用于维持变更期间的准入屏障，由资源作用域统一释放。
     *
     * @author kongweiguang
     */
    private InputMutation mutate(TurnOwnerProjection turn, AgentInputCommand command) {
        AgentIdentity identity = JaTurnRequestMapper.identity(turn.threadId(), turn.turn().turnId());
        try (WorkspaceAdmissionPort.Lease ignored =
                     workspaceAdmissions.acquire(turn.workspaceId())) {
            AgentInputMutationReceipt receipt;
            try {
                receipt = await(engine.mutateInput(identity, command));
            } catch (RuntimeException failure) {
                if (causedByQueueFull(failure)) {
                    throw InputMutationException.of(InputMutationFailure.QUEUE_FULL, failure);
                }
                throw failure;
            }
            return new InputMutation(true, receipt.inputId(), readInputQueue(turn, identity));
        }
    }

    /**
     * 重连查询只读 Ja 幂等回执；执行 queue 的当前状态仍由 Engine API 查询。
     */
    @Override
    public Optional<InputOperationReceipt> readInputOperation(String operationKey) {
        return operationReceipts.readInputOperation(operationKey)
                .filter(value -> value.kind() != AgentInputQueue.Kind.MESSAGE)
                .map(value -> new InputOperationReceipt(
                        value.operationKey(), value.fingerprint(), value.threadId(), value.turnId(), value.inputId(),
                        value.kind() == AgentInputQueue.Kind.STEERING ? InputKind.STEERING : InputKind.FOLLOW_UP));
    }

    /**
     * 提交身份回执属于 Ja RPC 语义，具体准入事实由 AgentStore transaction participant 写入。
     */
    @Override
    public Optional<ClientOperationReceipt> readClientOperation(String operationKey) {
        return operationReceipts.readClientOperation(operationKey);
    }

    /**
     * 按 Thread FIFO 顺序选择仍由本进程接管的首个可执行 Turn，排队中的其它本地句柄不构成歧义。
     */
    @Override
    public Optional<ActiveTurn> findActiveTurn(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        ThreadProjection thread = projections.readThread(threadId).orElse(null);
        if (thread == null) {
            return Optional.empty();
        }
        TurnProjection turn = localOwner(thread).orElse(null);
        if (turn == null) {
            return Optional.empty();
        }
        return Optional.of(new ActiveTurn(thread.threadId(), turn.turnId(), turn.state(),
                thread.revision(), turn.mutationVersion()));
    }

    /**
     * 只读本进程 owner 与同快照 Turn FIFO；持久 RUNNING 状态在重启后不能冒充活执行者。
     */
    @Override
    public Optional<String> locallyActiveTurnId(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        ThreadProjection thread = projections.readThread(threadId).orElse(null);
        return thread == null ? Optional.empty() : localOwner(thread).map(TurnProjection::turnId);
    }

    /**
     * 只查当前进程句柄表，不扫描执行历史；Thread 与 Turn 必须同时匹配才算本地 owner。
     */
    @Override
    public boolean locallyOwnsTurn(String threadId, String turnId) {
        Objects.requireNonNull(threadId, "threadId");
        if (turnId == null) {
            return false;
        }
        return owners.containsKey(JaTurnRequestMapper.identity(threadId, turnId));
    }

    /**
     * 用持久 Turn 顺序消除多个已接纳本地句柄的歧义，并忽略已等待或终态的身份。
     */
    private Optional<TurnProjection> localOwner(ThreadProjection thread) {
        Set<String> localIds = owners.keySet().stream()
                .filter(identity -> identity.threadId().equals(thread.threadId()))
                .map(AgentIdentity::turnId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return selectLocalOwner(thread, localIds);
    }

    /**
     * Store terminal 或挂起均不是后台 owner idle，需显式终态后才能开始 Plan background work。
     */
    public boolean ownerIdle(String threadId) {
        return projections.readThread(threadId).map(value -> value.turns().stream()
                .allMatch(turn -> turn.state().terminal())).orElse(false);
    }

    /**
     * 保存唯一 Run 回调并保留 Conversation 的权威终态类型；挂起期间该阶段保持未完成。
     */
    public void registerResumeContinuation(String turnId, Consumer<CompletionStage<TurnResult>> continuation) {
        if (resumeContinuations.putIfAbsent(Objects.requireNonNull(turnId), Objects.requireNonNull(continuation)) != null) {
            throw new IllegalStateException("resume continuation already bound");
        }
    }

    /**
     * Run 终态后移除连接回调，防止 coordinator 长期保留过期 invocation。
     */
    public void clearResumeContinuation(String turnId) {
        resumeContinuations.remove(Objects.requireNonNull(turnId));
    }

    /**
     * 写锁屏障让已经进入的准入完成后再拒绝后续执行。
     *
     * @author kongweiguang
     */
    @Override
    public void stopAccepting() {
        Lock gate = admissionGate.writeLock();
        gate.lock();
        try {
            withAllAdmissionStripes(0, () -> accepting.set(false));
        } finally {
            gate.unlock();
        }
    }

    /**
     * 关闭扫描完成后在同一准入屏障内检查 owner 并关闸，防止检查与关闭间接纳新 Turn。
     *
     * @author kongweiguang
     */
    @Override
    public boolean stopAcceptingIfQuiescent() {
        Lock gate = admissionGate.writeLock();
        gate.lock();
        try {
            if (owners.values().stream().anyMatch(owner -> !owner.isDone())) {
                return false;
            }
            withAllAdmissionStripes(0, () -> accepting.set(false));
            return true;
        } finally {
            gate.unlock();
        }
    }

    /**
     * 零时限执行非阻塞快照；正时限等待当前 owner，且快照期间用写屏障排除准入竞态。
     * ExecutionException 的 cause 作为 CompletionException cause 传播，保留异步失败的原始栈。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    @Override
    public boolean awaitQuiescence(Duration timeout) {
        Duration budget = Objects.requireNonNull(timeout, "timeout");
        long budgetNanos;
        try {
            budgetNanos = budget.isNegative() ? 0 : budget.toNanos();
        } catch (ArithmeticException overflow) {
            budgetNanos = Long.MAX_VALUE;
        }
        long startedAt = System.nanoTime();
        Lock gate = admissionGate.writeLock();
        try {
            boolean locked = budgetNanos == 0 ? gate.tryLock()
                    : gate.tryLock(budgetNanos, TimeUnit.NANOSECONDS);
            if (!locked) {
                return false;
            }
            CompletableFuture<?>[] pending;
            try {
                pending = owners.values().stream().filter(owner -> !owner.isDone())
                        .toArray(CompletableFuture[]::new);
            } finally {
                gate.unlock();
            }
            if (pending.length == 0) {
                return true;
            }
            long remaining = budgetNanos - (System.nanoTime() - startedAt);
            if (remaining <= 0) {
                return false;
            }
            CompletableFuture.allOf(pending).get(remaining, TimeUnit.NANOSECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException failure) {
            return false;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            throw new CompletionException(cause == null ? failure : cause);
        } finally {
            if (admissionGate.isWriteLockedByCurrentThread()) {
                gate.unlock();
            }
        }
    }

    /**
     * 预关闭只在回调成功后消费；失败保留给同一关闭边界重试，且并发关闭不能重复并发执行回调。
     */
    private Throwable runPreShutdownHook(long deadline) {
        synchronized (preShutdownLock) {
            LongConsumer hook = preShutdown.get();
            if (hook == null) {
                return null;
            }
            try {
                hook.accept(deadline);
                preShutdown.compareAndSet(hook, null);
                return null;
            } catch (RuntimeException | Error failure) {
                return failure;
            }
        }
    }

    /**
     * 关闭失败保留首次原因，后续清理故障作为 suppressed 诊断而不遮蔽根因。
     */
    private static Throwable mergeCloseFailure(Throwable primary, Throwable later) {
        if (primary == null) {
            return later;
        }
        if (primary != later) {
            primary.addSuppressed(later);
        }
        return primary;
    }

    /**
     * 仅在全部关闭步骤都尝试后重抛 unchecked 根因，保持宿主可识别的异常类型和实例。
     */
    private static void throwCloseFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /**
     * 先收口临时会话，再封闭准入并关闭 Engine；任一步失败都不跳过其余资源收尾。
     *
     * @author kongweiguang
     */
    @Override
    public void close() {
        Throwable failure = runPreShutdownHook(Long.MAX_VALUE);
        try {
            stopAccepting();
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        try {
            releaseAllSuspendedSubscriptions(true);
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        try {
            engine.closeAsync();
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        try {
            engine.awaitClosed();
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        try {
            releaseAllSuspendedSubscriptions(true);
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        throwCloseFailure(failure);
    }

    /**
     * 宿主 deadline 下先收口临时会话，再封闭准入并等待 Engine；失败仍尝试剩余步骤并保留主因。
     *
     * @author kongweiguang
     */
    @Override
    public void closeAt(long deadline) {
        Throwable failure = runPreShutdownHook(deadline);
        try {
            stopAccepting();
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        try {
            releaseAllSuspendedSubscriptions(true);
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        try {
            engine.closeAsync();
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        try {
            engine.awaitClosed(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        try {
            releaseAllSuspendedSubscriptions(true);
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeCloseFailure(failure, closeFailure);
        }
        throwCloseFailure(failure);
    }

    /**
     * 在准入前冻结 Thread 当前路径并拒绝陈旧 revision，未选分支仍保留为可审计事实。
     */
    private HistorySelection currentHistory(String threadId, long expectedRevision) {
        ThreadProjection thread = requireThreadRevision(threadId, expectedRevision);
        return history(thread);
    }

    /**
     * 客户端重试只复用已提交 identity；原 Turn completion 仍从本地句柄或 Store terminal 恢复。
     */
    private Accepted existing(String operationKey, String method, String fingerprint) {
        if (operationKey == null) {
            return null;
        }
        ClientOperationReceipt receipt = operationReceipts.readClientOperation(operationKey).orElse(null);
        if (receipt == null) {
            return null;
        }
        if (!receipt.matches(method, fingerprint)) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT, "operation conflicts");
        }
        AgentIdentity identity = JaTurnRequestMapper.identity(receipt.threadId(), receipt.turnId());
        CompletableFuture<TurnResult> completion = completions.computeIfAbsent(identity,
                ignored -> new CompletableFuture<>());
        AgentExecutionStatus status = engine.query(identity)
                .orElseThrow(() -> new IllegalStateException("committed execution missing"));
        if (state(status.state()).terminal()) {
            finishFromStore(identity, completion);
        }
        return accepted(identity, receipt.threadRevision(), !state(status.state()).terminal(),
                completion.minimalCompletionStage());
    }

    /**
     * 唯一 Turn 投影携带会话版本，错误身份映射为稳定的 Turn not found 业务异常。
     */
    private TurnOwnerProjection requireTurn(String turnId) {
        return projections.readTurn(turnId).orElseThrow(
                () -> TurnUseCase.TurnCancellationException.of(TurnUseCase.CancelFailure.TURN_NOT_FOUND));
    }

    /**
     * 入队 Skill 校验借用当前配置，不恢复模型调用或释放挂起 owner。
     */
    private UserContent validateQueued(String turnId, UserContent input) {
        TurnOwnerProjection turn = requireTurn(turnId);
        ThreadProjection thread = projections.readThread(turn.threadId()).orElseThrow();
        UserContent validated = validateReferences(thread.workspaceId(), input);
        if (!validated.skillIds().isEmpty()) {
            try (RuntimeLease runtime = openRuntime(thread, null)) {
                runtime.promptSession().validateSkillReferences(validated.skillIds());
            }
        }
        return validated;
    }

    /**
     * Workspace owner 校验真实路径边界，Turn 只规范化结构化引用。
     */
    private UserContent validateReferences(String workspaceId, UserContent content) {
        try {
            List<UserContentBlock> blocks = new ArrayList<>();
            for (UserContentBlock block : content.blocks()) {
                if (!(block instanceof ResourceReferenceContent(String sourceId, String resourceId, String kind1))) {
                    blocks.add(block);
                    continue;
                }
                ConversationWorkspaceReferencePort.ValidatedReference checked = workspaceReferences.validate(
                        workspaceId, sourceId, resourceId, kind1);
                blocks.add(new ResourceReferenceContent(checked.workspaceId(), checked.relativePath(),
                        checked.kind()));
            }
            return new UserContent(blocks);
        } catch (RuntimeException failure) {
            throw TurnUseCase.ContentValidationException.of(
                    TurnUseCase.ContentFailure.WORKSPACE_REFERENCE_INVALID, failure);
        }
    }

    /**
     * 仅从 durable queue entry 引用读取正文，附件摘要走 metadata-only 受管查询。
     */
    private InputQueue readInputQueue(TurnOwnerProjection turn, AgentIdentity identity) {
        AgentInputQueue queue = engine.inspectInputQueue(identity);
        AgentStore.ExecutionKey key = executionKey(identity);
        List<InputQueue.QueuedInput> items = new ArrayList<>(queue.entries().size());
        for (AgentInputQueue.Entry entry : queue.entries()) {
            if (entry.kind() == AgentInputQueue.Kind.MESSAGE) {
                continue;
            }
            AgentStore.StoredRecord stored = store.readRecord(key, entry.messageRecordId()).orElseThrow(
                    () -> new IllegalStateException("queued input record is missing"));
            UserMessage message = records.decodeQueuedUser(stored);
            List<AttachmentSummary> metadata = attachments.readAuthorizedSummaries(turn.threadId(),
                    message.content().attachmentIds());
            if (!message.content().attachmentIds().equals(
                    metadata.stream().map(AttachmentSummary::attachmentId).toList())) {
                throw new IllegalStateException("authorized attachment metadata differs from is_queued input order");
            }
            List<AttachmentSummary> summaries = metadata;
            InputQueue.Status status = entry.status() == AgentInputQueue.Status.PENDING
                    ? InputQueue.Status.PENDING : InputQueue.Status.NEEDS_ATTENTION;
            InputQueue.Issue issue = entry.issue() == null ? null : new InputQueue.Issue(
                    entry.issue().code(), entry.issue().message(), entry.issue().retryable());
            InputQueue.Kind kind = switch (entry.kind()) {
                case STEERING -> InputQueue.Kind.STEERING;
                case FOLLOW_UP -> InputQueue.Kind.FOLLOW_UP;
                case MESSAGE -> throw new IllegalStateException("task message leaked into user input queue");
            };
            items.add(new InputQueue.QueuedInput(entry.inputId(), turn.turn().turnId(), message.content(), kind,
                    summaries, status, issue, entry.inputRevision(), entry.createdAt()));
        }
        return new InputQueue(turn.turn().turnId(), queue.revision(), !turn.turn().state().terminal(), items);
    }

    /**
     * 当前消息正文来自固定 record watermark，不从 Ja UI 消息表重建执行历史。
     */
    private SourceTurn latestFailedSource(ThreadProjection thread) {
        List<TurnProjection> path = currentPath(thread);
        for (int index = path.size() - 1; index >= 0; index--) {
            TurnProjection candidate = path.get(index);
            String source = candidate.sourceMessageId();
            if (source == null || !hasDirectUserMessage(thread.threadId(), candidate.turnId(), source)) {
                continue;
            }
            TurnProjection latestAttempt = candidate;
            for (int next = index + 1; next < path.size(); next++) {
                if (source.equals(path.get(next).sourceMessageId())) {
                    latestAttempt = path.get(next);
                }
            }
            if (latestAttempt.state() == TurnState.FAILED || latestAttempt.state() == TurnState.CANCELLED) {
                String ownerTurnId = path.stream().filter(turn -> source.equals(turn.sourceMessageId()))
                        .findFirst().orElseThrow().turnId();
                return new SourceTurn(source, ownerTurnId);
            }
            throw notReaskable();
        }
        throw notReaskable();
    }

    /**
     * 首条直接 USER 消息必须存在于 AgentStore；Ja message projection 不能替代模型执行事实。
     */
    private boolean hasDirectUserMessage(String threadId, String turnId, String expectedMessageId) {
        AgentIdentity identity = JaTurnRequestMapper.identity(threadId, turnId);
        AgentStore.ExecutionKey key = executionKey(identity);
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(key).orElse(null);
        if (snapshot == null) {
            return false;
        }
        long after = 0;
        while (after < snapshot.lastRecordSequence()) {
            AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                    key, after, snapshot.lastRecordSequence(), 128));
            for (AgentStore.StoredRecord record : page.records()) {
                if ("agent.user.message".equals(record.recordType())) {
                    return matchesSourceMessageId(expectedMessageId, records.decodeUser(record).messageId());
                }
            }
            if (!page.hasMore()) {
                break;
            }
            if (page.nextSequence() <= after) {
                throw new IllegalStateException("record page did not advance");
            }
            after = page.nextSequence();
        }
        return false;
    }

    /**
     * 按固定执行水位找出指定未知 Tool 调用的最新未决恢复事实。
     */
    private Optional<ToolRecoveryDecision> latestPendingRecovery(AgentIdentity identity, String callId) {
        AgentStore.ExecutionKey key = executionKey(identity);
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(key).orElseThrow(
                () -> TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE));
        ToolRecoveryDecision latest = null;
        long after = 0;
        while (after < snapshot.lastRecordSequence()) {
            AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                    key, after, snapshot.lastRecordSequence(), 128));
            for (AgentStore.StoredRecord record : page.records()) {
                if ("agent.tool.recovery.decision".equals(record.recordType())) {
                    ToolRecoveryDecision candidate = controls.decodeToolRecoveryDecision(record);
                    if (candidate.callId().equals(callId)) {
                        latest = candidate;
                    }
                }
            }
            if (!page.hasMore()) {
                break;
            }
            if (page.nextSequence() <= after) {
                throw new IllegalStateException("record page did not advance");
            }
            after = page.nextSequence();
        }
        return latest == null || latest.status() != ToolRecoveryDecision.Status.PENDING
                ? Optional.empty() : Optional.of(latest);
    }

    /**
     * 打开短时配置租约仅用于标题或 Skill 校验，不为这类查询创建 Engine 执行上下文。
     */
    private RuntimeLease openRuntime(ThreadProjection thread, NativeExecutionSnapshot snapshot) {
        ThreadPreferences preferences = thread.preferences();
        WorkspaceBindingResolver.WorkspaceBinding workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
        if (!workspace.workspaceId().equals(thread.workspaceId())) {
            throw new IllegalStateException("Workspace binding does not match the Thread");
        }
        return runtimeResolver.resolve(new TurnRuntimeRequest(thread.threadId(), null,
                workspace.root(), thread.workspaceId(),
                preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                preferences.accessMode(), preferences.collaborationMode(), TurnOrigin.USER,
                clock.instant(), snapshot));
    }

    /**
     * 成功终态后异步生成首次标题，标题失败不改写已提交 Turn。
     */
    private void scheduleTitle(TurnStartRequest request, UserContent content, TurnResult result,
                               ConversationTurnEventSink sink, NativeExecutionSnapshot inherited) {
        if (result.state() != TurnState.COMPLETED) {
            return;
        }
        automaticTitles.schedule(new AutomaticThreadTitleScheduler.Request(request.threadId(), request.turnId(),
                result.terminal().context().threadRevision(), content.text(), result.summary(), timeout -> {
            RuntimeLease runtime = openRuntime(projections.readThread(request.threadId()).orElseThrow(), inherited);
            return new AutomaticThreadTitleScheduler.RequestRuntime(runtime.model(), runtime);
        }), sink);
    }

    /**
     * 读取给定 Thread 的匹配版本投影。
     */
    private ThreadProjection requireThreadRevision(String threadId, long expectedRevision) {
        ThreadProjection thread = projections.readThread(threadId)
                .orElseThrow(() -> new IllegalArgumentException("thread missing"));
        if (thread.revision() != expectedRevision) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT, "thread revision changed");
        }
        return thread;
    }

    /**
     * 隐藏题目来源绑定直接用户输入及其失败 Turn，不根据相似文本搜索推断。
     *
     * @param messageId 消息标识
     * @param ownerTurnId 所属执行标识
     */
    private record SourceTurn(String messageId,
                              String ownerTurnId) {
    }
}
