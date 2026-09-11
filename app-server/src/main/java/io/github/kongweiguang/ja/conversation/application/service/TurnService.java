// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.AgentLoop;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionSuspendedException;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionService;
import io.github.kongweiguang.ja.conversation.application.loop.QueuedInputBoundary;
import io.github.kongweiguang.ja.conversation.application.loop.TerminalCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.TurnExecutionPlan;
import io.github.kongweiguang.ja.conversation.application.loop.TurnQueue;
import io.github.kongweiguang.ja.conversation.application.change.TurnChangeTracker;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.conversation.application.prompt.DefaultAgentPromptSessionFactory.SkillSelectionException;
import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEvent;
import io.github.kongweiguang.ja.conversation.port.in.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.in.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnCancellationListener;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.RuntimeLease;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePathFailure;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceReferenceValidator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turn 准入、FIFO 执行、快速取消、终态提交和关闭的应用层 owner。
 */
public final class TurnService implements TurnUseCase, ChildTurnScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurnService.class);
    private static final long CANCELLATION_PROPAGATION_RETRY_MILLIS = 100L;
    private final ConversationRepository store;
    private final AgentLoop loop;
    private final TurnQueue queue;
    private final CancellationCoordinator cancellations;
    private final TurnRuntimeResolver runtimeResolver;
    private final Clock clock;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ja-turn-deadline-", 0).factory());
    private final Map<Key, TurnOwnership> active = new ConcurrentHashMap<>();
    private final Map<Key, CompletableFuture<CancellationCoordinator.CancelOutcome>> cancellationBarriers
            = new ConcurrentHashMap<>();
    private final Object admissionLifecycle = new Object();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final TurnTerminalSettlement terminalSettlement;
    private final TurnCancellationLifecycle cancellationLifecycle;
    private final TurnShutdown shutdown;
    private final AutomaticThreadTitleScheduler automaticTitles;
    private final WorkspaceReferenceValidator workspaceReferences;
    private final AtomicReference<TurnCancellationListener> cancellationListener =
            new AtomicReference<>(TurnCancellationListener.noop());
    private final AtomicBoolean cancellationListenerBound = new AtomicBoolean();
    private final AtomicReference<LongConsumer> preShutdownHook = new AtomicReference<>(ignored -> { });
    private final AtomicBoolean preShutdownHookBound = new AtomicBoolean();
    private final Map<Key, PendingInteractionResume> pendingInteractionResumes = new ConcurrentHashMap<>();
    private final Map<String, Consumer<CompletionStage<?>>> resumeContinuations = new ConcurrentHashMap<>();
    private volatile java.util.function.Function<String, java.util.Optional<TurnLimits>> planResumeBudget;
    private volatile InteractionService interactionOwner;

    /** Plan 预算由其唯一 Run owner 读取，Conversation 不依赖 Goal 仓储或重置配置额度。 */
    public void bindPlanResumeBudget(java.util.function.Function<String, java.util.Optional<TurnLimits>> budgets) {
        if (planResumeBudget != null) throw new IllegalStateException("Plan resume budget already bound");
        planResumeBudget = Objects.requireNonNull(budgets, "budgets");
    }

    /** 生产组合根注入 Workspace owner 的唯一引用校验端口，禁止 conversation 复制路径规则。 */
    public TurnService(ConversationRepository store, AgentLoop loop, TurnQueue queue,
                       CancellationCoordinator cancellations, TurnRuntimeResolver runtimeResolver,
                       Clock clock, AutomaticThreadTitleScheduler automaticTitles,
                       WorkspaceReferenceValidator workspaceReferences) {
        this(store, loop, queue, cancellations, runtimeResolver, clock,
                newTerminalExecutor(), automaticTitles, workspaceReferences);
    }

    /** 测试可显式注入终态 owner 与引用端口，生产仍经公开构造器绑定真实 Workspace owner。 */
    TurnService(ConversationRepository store, AgentLoop loop, TurnQueue queue,
                CancellationCoordinator cancellations, TurnRuntimeResolver runtimeResolver,
                Clock clock, ExecutorService terminalExecutor,
                AutomaticThreadTitleScheduler automaticTitles,
                WorkspaceReferenceValidator workspaceReferences) {
        this.store = Objects.requireNonNull(store, "store");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.automaticTitles = Objects.requireNonNull(automaticTitles, "automaticTitles");
        this.workspaceReferences = Objects.requireNonNull(workspaceReferences, "workspaceReferences");
        ExecutorService requiredTerminalExecutor =
                Objects.requireNonNull(terminalExecutor, "terminalExecutor");
        this.terminalSettlement = new TurnTerminalSettlement(store, clock);
        TurnQueueCancellationSettlement queuedSettlement = new TurnQueueCancellationSettlement(
                requiredTerminalExecutor, cancellations, active, terminalSettlement);
        this.cancellationLifecycle = new TurnCancellationLifecycle(
                store, cancellations, clock, queue, active, cancellationBarriers, queuedSettlement);
        this.shutdown = new TurnShutdown(queue, deadlines, requiredTerminalExecutor, active,
                this::stopAccepting, cancellationLifecycle);
    }

    /**
     * 创建生产 Turn 收口唯一的串行终态 owner，避免并发写终态。
     */
    private static ExecutorService newTerminalExecutor() {
        return Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("ja-turn-terminal-", 0).factory());
    }

    /**
     * Task 组合完成后只允许绑定一次真实监听器；构造期 noop 打破 TurnService 与 TaskCoordinator 的 Bean 环。
     */
    public void bindCancellationListener(TurnCancellationListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!cancellationListenerBound.compareAndSet(false, true)) {
            throw new IllegalStateException("turn cancellation listener is already bound");
        }
        cancellationListener.set(listener);
        listener.reconcilePending();
    }

    /**
     * 绑定唯一的关闭前置动作；该动作在 TurnShutdown 停止准入前执行，供临时侧聊先取消自己的
     * Child/Goal/Plan，避免 shutdown fence 先释放 Turn owner 后再留下无法收口的临时树。
     */
    public void bindPreShutdownHook(LongConsumer hook) {
        Objects.requireNonNull(hook, "hook");
        if (!preShutdownHookBound.compareAndSet(false, true)) {
            throw new IllegalStateException("turn pre-shutdown hook is already bound");
        }
        preShutdownHook.set(hook);
    }

    /**
     * 将回答 ACK 与 Turn owner 解耦；InteractionService 只负责提交事实，TurnService 负责等待旧 owner
     * 释放后以同一 turnId 恢复，避免 RPC 线程持有 Provider 或运行租约。
     */
    public void bindInteractionResumeScheduler(InteractionService interactions) {
        interactionOwner = Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(interactions, "interactions").bindResumeScheduler(
                (threadId, turnId, threadRevision, sink) -> scheduleInteractionResume(
                        threadId, turnId, threadRevision, sink, 0));
    }

    /**
     * 准入只用短租约验证首条输入并确定 Operation 预算；租约在 SQLite admission 后立即释放，
     * 后续每次 Provider 请求都从 ThreadPreferences 重新解析。
     */
    @SuppressWarnings("PMD.CloseResource")
    public TurnUseCase.Accepted start(TurnStartRequest request, TurnEventSink sink) {
        return startWithAdmission(StartCommand.user(request, TurnOrigin.USER), sink,
                admission -> admissionReceipt(store.admit(repositoryAdmission(admission))), "");
    }

    /** Goal/Plan 内部 Turn 复用完整生命周期，但类型和 admission 都不提供 USER message。 */
    public TurnUseCase.Accepted startContinuation(InternalTurnStartRequest request, String hiddenSummary,
                                                  TurnEventSink sink) {
        return startContinuation(request, hiddenSummary, sink, null);
    }

    /**
     * Plan continuation 的显式单 Turn ceiling；只收紧 model/tool/wall 预算，Provider 的 token 与权限
     * 仍由当前 RuntimeLease 决定，防止跨 Turn Run 通过反复 admission 绕过累计上限。
     */
    public TurnUseCase.Accepted startContinuation(InternalTurnStartRequest request, String hiddenSummary,
                                                  TurnEventSink sink, TurnLimits ceiling) {
        if (hiddenSummary == null || hiddenSummary.isBlank() || hiddenSummary.length() > 1_000_000) {
            throw new IllegalArgumentException("invalid Goal continuation context");
        }
        return startWithAdmission(StartCommand.internal(request), sink,
                admission -> admissionReceipt(store.admitContinuation(
                new ConversationRepository.ContinuationAdmission(admission.threadId(), admission.turnId(),
                        admission.expectedThreadRevision(), admission.requestedAt(), admission.initialExecution(),
                        hiddenSummary))),
                hiddenSummary, ceiling);
    }

    /** idle 来自持久 Turn 终态，UI store 与单一进程内 Map 都不能作为恢复依据。 */
    public boolean ownerIdle(String threadId) {
        return store.readThread(threadId).map(snapshot -> snapshot.turns().stream()
                .allMatch(turn -> turn.state().terminal())).orElse(false);
    }

    /**
     * 注册内部执行器在交互回答恢复后继续推进的回调。回调按全局 Turn identity 幂等消费，
     * 这样重启或 RPC 重试只会恢复原 cursor，不会启动第二条 Plan/Goal 执行链。
     */
    public void registerResumeContinuation(String turnId, Consumer<CompletionStage<?>> continuation) {
        if (turnId == null || !turnId.startsWith("turn_") || continuation == null) {
            throw new IllegalArgumentException("invalid resume continuation");
        }
        Consumer<CompletionStage<?>> previous = resumeContinuations.putIfAbsent(turnId, continuation);
        if (previous != null && previous != continuation) {
            throw new IllegalStateException("resume continuation is already registered");
        }
    }

    /** admission 失败或终态完成时移除尚未使用的恢复回调，避免跨 Run 保留连接与 coordinator 引用。 */
    public void clearResumeContinuation(String turnId) {
        if (turnId == null) throw new IllegalArgumentException("turnId is required");
        resumeContinuations.remove(turnId);
    }

    /**
     * Child Task 复用普通 Turn 生命周期，但把 Thread、lineage、seed 与首 Turn 的原子写入权
     * 交给 Task Repository；该回调仍严格位于 reserve 之后、submit 之前。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public TurnUseCase.Accepted startChild(TurnStartRequest request, TurnEventSink sink,
                                           ChildTurnScheduler.Admission admission) {
        ChildTurnScheduler.Admission required = ChildTurnScheduler.required(admission);
        return startWithAdmission(StartCommand.user(request, TurnOrigin.CHILD_TASK), sink,
                value -> required.admit(childAdmission(value)), "");
    }

    /**
     * 所有来源共用唯一准入状态机；只有 user-authored origin 才验证 UserContent 并构造 USER message，
     * 内部来源从类型到 Provider 上下文都只携带 hidden summary。
     */
    private TurnUseCase.Accepted startWithAdmission(StartCommand request, TurnEventSink sink,
                                                    StartAdmission admission,
                                                    String initialSummary) {
        return startWithAdmission(request, sink, admission, initialSummary, null);
    }

    /** 统一 admission 实现，ceiling 只在 Plan continuation 入口显式传入。 */
    private TurnUseCase.Accepted startWithAdmission(StartCommand request, TurnEventSink sink,
                                                    StartAdmission admission,
                                                    String initialSummary, TurnLimits ceiling) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(admission, "admission");
        if (store.hasSuspendedTurn(request.threadId())) {
            throw TurnUseCase.TurnResumeException.of(
                    TurnUseCase.ResumeFailure.TURN_RESUME_ORDER_CONFLICT);
        }
        // 新 Child 的 Thread/策略行在 reserve 后才原子写入。准入期只解析配置与预算，不准备依赖
        // 已持久 Thread 身份的 Agent 工具；首个 Provider 请求由 runtimeFactory 在提交后重新绑定。
        boolean pendingChildAdmission = request.origin() == TurnOrigin.CHILD_TASK
                && store.readThread(request.threadId()).isEmpty();
        TurnRuntimeRequest runtimeRequest = new TurnRuntimeRequest(request.threadId(),
                pendingChildAdmission ? null : request.turnId(), request.workspaceRoot(),
                request.workspaceId(), request.providerId(), request.modelId(), request.reasoningLevel(),
                request.accessMode(), request.collaborationMode(), request.origin(),
                request.deadline(), request.requestedAt());
        RuntimeLease runtimeLease = Objects.requireNonNull(runtimeResolver.resolve(runtimeRequest), "runtimeLease");
        try {
            validateResolvedRuntime(request, runtimeLease);
            TurnLimits effectiveLimits = capLimits(runtimeLease.limits(), ceiling);
            UserContent validatedContent = request.origin().internal()
                    ? null : validateWorkspaceReferences(request.workspaceId(), request.content());
            if (validatedContent != null) replaceMessageSkills(runtimeLease, validatedContent);
            /* ceiling 必须进入持久 execution deadline，否则一次交互挂起/恢复会绕过 Plan wall budget。 */
            Instant deadlineAt = request.requestedAt().plus(effectiveLimits.wallTimeout());
            TurnExecutionPlan.RequestRuntimeFactory runtimeFactory = requestRuntimeFactory(
                    request.threadId(), request.turnId(), request.workspaceRoot(), validatedContent,
                    request.origin(), request.requestedAt(), request.workspaceId(), deadlineAt, ceiling);
            TurnChangeTracker changeTracker = TurnChangeTracker.fresh(request.workspaceRoot());
            TurnExecutionPlan executionRequest = new TurnExecutionPlan(request.threadId(), request.turnId(),
                    request.workspaceRoot(), validatedContent, request.origin(),
                    runtimeLease.model(), runtimeLease.accessMode(),
                    effectiveLimits, request.requestedAt(), request.workspaceId(),
                    request.expectedThreadRevision(), request.initialTurnMutationVersion(),
                    initialSummary, runtimeLease.promptSession(), queuedInputBoundary(request.threadId(), request.workspaceRoot(),
                            request.workspaceId(), deadlineAt),
                    runtimeLease.attachments(), runtimeLease.tools(),
                    runtimeLease.generationId(), runtimeLease.toolSessions(), runtimeLease.outputLimits(),
                    runtimeLease.presentationSecrets(), deadlineAt, runtimeFactory, changeTracker);
            TurnExecutionPlan command = executionRequest;
            // 关闭路径采用同一准入锁，防止关闭快照之后再出现新的 active owner。
            synchronized (admissionLifecycle) {
                if (!accepting.get() || shutdown.isClosed()) {
                    throw rejected("SHUTTING_DOWN");
                }
                TurnQueue.Reservation reservation = queue.reserve(command.threadId(), command.turnId());
                /* CancellationScope 在成功准入后转交 TurnOwnership，由 run/失败收口唯一关闭；
                 * PMD 无法跨 ownership 对象追踪该异步生命周期。 */
                @SuppressWarnings("PMD.CloseResource")
                CancellationCoordinator.CancellationScope cancellation;
                try {
                    cancellation = cancellations.open(command.threadId(), command.turnId());
                } catch (RuntimeException failure) {
                    reservation.fail(failure);
                    throw failure;
                }
                ChildTurnScheduler.AdmissionReceipt receipt;
                try {
                    ModelMessage userMessage = command.origin().internal() ? null
                            : new ModelMessage(ModelRole.USER, List.copyOf(command.content().blocks()));
                    receipt = admission.admit(new AdmissionContext(command.threadId(), command.turnId(),
                            command.origin().internal() ? null : "item_" + UUID.randomUUID(),
                            userMessage, command.attachmentIds(),
                            executionRequest.initialThreadRevision(),
                            command.requestedAt(), initialExecution(runtimeLease, deadlineAt, command.origin())));
                } catch (RuntimeException failure) {
                    reservation.fail(failure);
                    cancellation.close();
                    cancellations.complete(command.threadId(), command.turnId());
                    throw failure;
                }
                /* Follow-up 的并发幂等 winner 可能已绑定另一个持久 Turn。当前预留仅为尝试身份，
                 * 必须正常释放且不运行 phantom command；调用方收到原始持久身份后自行观察权威投影。 */
                if (!receipt.threadId().equals(command.threadId()) || !receipt.turnId().equals(command.turnId())) {
                    reservation.releaseWithoutExecution();
                    cancellation.close();
                    cancellations.complete(command.threadId(), command.turnId());
                    return new TurnUseCase.Accepted(receipt.threadId(), receipt.turnId(),
                            receipt.threadRevision(), false, CompletableFuture.completedFuture(null));
                }
                Key key = new Key(command.threadId(), command.turnId());
                // 准入时固定唯一绝对 Deadline，后续环境刷新不得延长 Operation。
                TurnExecutionState.Ready initialExecution = initialExecution(runtimeLease, deadlineAt, command.origin());
                TurnOwnership accepted = new TurnOwnership(executionRequest.withAdmissionReceipt(
                        receipt.threadRevision(), receipt.turnMutationVersion()), sink,
                        cancellation, new TerminalCoordinator(),
                        new CompletableFuture<>(), receipt.createdProvisionalTitle(), deadlineAt, initialExecution);
                if (active.putIfAbsent(key, accepted) != null) {
                    reservation.fail(new IllegalArgumentException("turn identity is already active"));
                    cancellation.close();
                    cancellations.complete(command.threadId(), command.turnId());
                    throw new IllegalArgumentException("turn identity is already active");
                }
                long delayNanos = Math.max(0L, Duration.between(clock.instant(), accepted.deadlineAt).toNanos());
                try {
                    publishProvisionalTitle(command, receipt, sink);
                    accepted.deadline = deadlines.schedule(
                            () -> cancelFromRuntime(key, accepted, "turn deadline exceeded"),
                            delayNanos, TimeUnit.NANOSECONDS);
                    reservation.submit(() -> run(key, accepted));
                } catch (RuntimeException failure) {
                    active.remove(key, accepted);
                    if (accepted.deadline != null) accepted.deadline.cancel(false);
                    reservation.fail(failure);
                    cancellation.close();
                    cancellations.complete(command.threadId(), command.turnId());
                    terminalSettlement.commitUnexpectedTerminal(accepted, TurnState.FAILED,
                            "INTERNAL_ERROR", "turn could not be scheduled");
                    throw failure;
                }
                return new TurnUseCase.Accepted(command.threadId(), command.turnId(), receipt.threadRevision(), true,
                        accepted.completion);
            }
        } finally {
            runtimeLease.close();
        }
    }

    /**
     * 把最早 SUSPENDED Turn 放回现有 FIFO；恢复不比较旧运行环境，READY 在下一请求安全点读取最新偏好，
     * TOOLS 只按已持久化 binding 结算，禁止同名重路由。交互答案也通过此唯一入口恢复。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public TurnUseCase.Accepted resume(String turnId, long expectedThreadRevision, TurnEventSink sink) {
        Objects.requireNonNull(sink, "sink");
        ConversationRepository.ResumeCandidate candidate = store.findResumeCandidate(turnId)
                .orElseThrow(() -> TurnUseCase.TurnResumeException.of(
                        TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE));
        if (candidate.threadRevision() != expectedThreadRevision) {
            throw TurnUseCase.TurnResumeException.of(candidate.threadRevision() != expectedThreadRevision
                    ? TurnUseCase.ResumeFailure.TURN_RESUME_ORDER_CONFLICT
                    : TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE);
        }
        Instant resumedAt = clock.instant();
        TurnExecutionState.Common common = candidate.execution().common();
        InteractionService interactions = interactionOwner;
        if (interactions != null && interactions.read(candidate.threadId(), null)
                .flatMap(io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot::request)
                .filter(request -> request.turnId().equals(turnId)
                        && request.status() == io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus.PENDING).isPresent()) {
            throw TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE);
        }
        TurnOrigin origin = common.origin();
        UserContent content = origin.internal() ? null : candidate.originalContent();
        TurnLimits resumeCeiling = null;
        if (origin == TurnOrigin.PLAN_EXECUTION) {
            var budgets = planResumeBudget;
            if (budgets == null) throw new IllegalStateException("Plan resume budget is unavailable");
            resumeCeiling = budgets.apply(turnId).orElseThrow(() -> new IllegalStateException("Plan run budget is unavailable"));
        }
        Duration activeBudget = resumeCeiling == null || common.activeBudget().compareTo(resumeCeiling.wallTimeout()) <= 0
                ? common.activeBudget() : resumeCeiling.wallTimeout();
        Instant resumedDeadline = resumedAt.plus(activeBudget);
        TurnExecutionState resumedExecution = candidate.execution().withDeadline(resumedDeadline);
        RuntimeLease lease = openCurrentLease(candidate.threadId(), candidate.turnId(), candidate.workspaceRoot(),
                candidate.workspaceId(), resumedDeadline, origin);
        try {
            restoreRequestPrompt(common, candidate.promptSummary(), lease);
            TurnQueue.Reservation reservation;
            CancellationCoordinator.CancellationScope cancellation;
            synchronized (admissionLifecycle) {
                if (!accepting.get() || shutdown.isClosed()) throw rejected("SHUTTING_DOWN");
                reservation = queue.reserve(candidate.threadId(), candidate.turnId());
                try {
                    cancellation = cancellations.open(candidate.threadId(), candidate.turnId());
                } catch (RuntimeException failure) {
                    reservation.fail(failure);
                    throw failure;
                }
                ConversationRepository.ResumeReceipt receipt;
                try {
                    receipt = store.resume(candidate.turnId(), expectedThreadRevision,
                            candidate.turnMutationVersion(), resumedAt);
                } catch (StorageException failure) {
                    reservation.fail(failure);
                    cancellation.close();
                    cancellations.complete(candidate.threadId(), candidate.turnId());
                    if (failure.code() == StorageException.Code.CAS_CONFLICT) {
                        throw TurnUseCase.TurnResumeException.of(
                                TurnUseCase.ResumeFailure.TURN_RESUME_ORDER_CONFLICT);
                    }
                    if (failure.code() == StorageException.Code.NOT_FOUND) {
                        throw TurnUseCase.TurnResumeException.of(
                                TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE);
                    }
                    throw failure;
                } catch (RuntimeException failure) {
                    reservation.fail(failure);
                    cancellation.close();
                    cancellations.complete(candidate.threadId(), candidate.turnId());
                    throw failure;
                }
                TurnExecutionPlan plan = new TurnExecutionPlan(candidate.threadId(), candidate.turnId(),
                        candidate.workspaceRoot(), content, origin, lease.model(), lease.accessMode(),
                        capLimits(lease.limits(), resumeCeiling), resumedAt,
                        candidate.workspaceId(), receipt.threadRevision(),
                        receipt.turnMutationVersion(), candidate.initialSummary(),
                        lease.promptSession(), queuedInputBoundary(candidate.threadId(), candidate.workspaceRoot(),
                                candidate.workspaceId(), resumedDeadline),
                        lease.attachments(), lease.tools(),
                        lease.generationId(), lease.toolSessions(), lease.outputLimits(), lease.presentationSecrets(),
                        resumedDeadline, requestRuntimeFactory(candidate.threadId(), candidate.turnId(),
                                candidate.workspaceRoot(), content, origin, resumedAt,
                                candidate.workspaceId(), resumedDeadline, resumeCeiling),
                        TurnChangeTracker.resumed(candidate.workspaceRoot()));
                Key key = new Key(candidate.threadId(), candidate.turnId());
                Instant deadlineAt = resumedDeadline;
                TurnOwnership owner = new TurnOwnership(plan, sink, cancellation, new TerminalCoordinator(),
                        new CompletableFuture<>(), candidate.provisionalTitleEligible(), deadlineAt,
                        resumedExecution);
                if (active.putIfAbsent(key, owner) != null) {
                    reservation.fail(new IllegalArgumentException("turn identity is already active"));
                    cancellation.close();
                    cancellations.complete(candidate.threadId(), candidate.turnId());
                    throw TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE);
                }
                long delayNanos = Math.max(0L, Duration.between(clock.instant(), deadlineAt).toNanos());
                try {
                    publishResumeQueued(candidate, receipt, sink, resumedAt);
                    owner.deadline = deadlines.schedule(
                            () -> cancelFromRuntime(key, owner, "turn deadline exceeded"),
                            delayNanos, TimeUnit.NANOSECONDS);
                    Consumer<CompletionStage<?>> continuation = resumeContinuations.remove(candidate.turnId());
                    if (continuation != null) continuation.accept(owner.completion);
                    reservation.submit(() -> run(key, owner));
                } catch (RuntimeException failure) {
                    active.remove(key, owner);
                    if (owner.deadline != null) owner.deadline.cancel(false);
                    reservation.fail(failure);
                    cancellation.close();
                    cancellations.complete(candidate.threadId(), candidate.turnId());
                    terminalSettlement.commitUnexpectedTerminal(owner, TurnState.FAILED,
                            "INTERNAL_ERROR", "turn could not be scheduled");
                    throw failure;
                }
                return new TurnUseCase.Accepted(candidate.threadId(), candidate.turnId(),
                        receipt.threadRevision(), true, owner.completion);
            }
        } finally {
            lease.close();
        }
    }

    /**
     * 将客户端 revision 交给取消生命周期执行持久 CAS，拒绝未确认的本地取消。
     */
    public TurnUseCase.CancelResult cancel(String turnId, long expectedThreadRevision) {
        String parentThreadId = cancellationThreadId(turnId);
        try {
            TurnUseCase.CancelResult result = cancellationLifecycle.cancel(turnId, expectedThreadRevision);
            publishCancellationClaim(parentThreadId, result);
            return result;
        } catch (TurnUseCase.TurnCancellationException absent) {
            if (absent.failure() != TurnUseCase.CancelFailure.TURN_NOT_FOUND) throw absent;
            try {
                ConversationRepository.CancelResult cancelled = store.cancelSuspended(
                        turnId, expectedThreadRevision, clock.instant());
                TurnUseCase.CancelResult result = new TurnUseCase.CancelResult(true, turnId, TurnState.CANCELLED,
                        cancelled.threadRevision());
                publishCancellationClaim(parentThreadId, result);
                return result;
            } catch (RuntimeException unavailable) {
                throw absent;
            }
        }
    }

    /**
     * Plan pause 的窄入口：活动 Turn 先完成取消清理，再尝试保留 execution cursor；已有
     * SUSPENDED Turn 不重复写状态，避免把暂停误收敛为 CANCELLED。
     */
    public CompletionStage<Void> suspendPlanRun(String turnId, long expectedThreadRevision) {
        String threadId = cancellationThreadId(turnId);
        if (threadId == null) return CompletableFuture.completedFuture(null);
        TurnOwnership owner = active.get(new Key(threadId, turnId));
        if (owner == null) {
            return store.findResumeCandidate(turnId).isPresent()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(TurnUseCase.TurnResumeException.of(
                            TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE));
        }
        owner.planPauseRequested.set(true);
        try {
            cancellationLifecycle.cancel(turnId, expectedThreadRevision, "plan paused");
        } catch (RuntimeException failure) {
            owner.planPauseRequested.set(false);
            return CompletableFuture.failedFuture(failure);
        }
        return owner.completion.handle((ignored, failure) -> {
            if (failure != null && !isPlanSuspended(failure)) {
                throw new CompletionException(failure);
            }
            return null;
        });
    }

    /**
     * 父取消已持久化后再通知 Task；传播失败只记录安全类型，不能把已提交的父取消伪装成失败。
     */
    private void publishCancellationClaim(String parentThreadId, TurnUseCase.CancelResult result) {
        if (!result.accepted() || parentThreadId == null) return;
        TurnOwnership owner = active.get(new Key(parentThreadId, result.turnId()));
        if (owner != null && !owner.claimCancellationPropagation()) return;
        deliverCancellationPropagation(parentThreadId, result.turnId(), 0);
    }

    /**
     * 父 Turn 的 cancel_requested_at 是持久欠账；进程存活时指数退避重试监听器，关闭后由下次绑定恢复。
     */
    private void deliverCancellationPropagation(String parentThreadId, String parentTurnId, int attempt) {
        try {
            cancellationListener.get().cancellationClaimed(parentThreadId, parentTurnId);
        } catch (RuntimeException failure) {
            int nextAttempt = Math.min(30, attempt + 1);
            if (nextAttempt == 1 || Integer.bitCount(nextAttempt) == 1) {
                LOGGER.warn("event=task_cancellation_propagation_retry retry_attempt={} cause={}",
                        nextAttempt, failure.getClass().getSimpleName());
            }
            if (!accepting.get()) {
                LOGGER.warn("event=task_cancellation_propagation_pending durable_pending=1");
                return;
            }
            long delay = Math.min(5_000L, CANCELLATION_PROPAGATION_RETRY_MILLIS
                    << Math.min(5, attempt));
            try {
                deadlines.schedule(() -> deliverCancellationPropagation(
                        parentThreadId, parentTurnId, nextAttempt), delay, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException closing) {
                LOGGER.warn("event=task_cancellation_propagation_pending durable_pending=1");
            }
        }
    }

    /**
     * 活动 Turn 从内存所有权读取 Thread；SUSPENDED Turn 在终态 CAS 前读取恢复投影，避免取消后丢失归属。
     */
    private String cancellationThreadId(String turnId) {
        for (Key key : active.keySet()) {
            if (key.turnId().equals(turnId)) return key.threadId();
        }
        return store.findResumeCandidate(turnId).map(ConversationRepository.ResumeCandidate::threadId).orElse(null);
    }

    /** 普通提交固定为 FOLLOW_UP；“调整方向”只能对已入队 identity 做显式提升。 */
    @Override
    public TurnUseCase.InputMutation enqueueInput(String turnId, UserContent content) {
        return enqueueInput(turnId, content, TurnEventSink.noop());
    }

    /** 只有原子替代了未决问题才自动恢复，普通运行中的队列仍等待原安全点。 */
    @Override public TurnUseCase.InputMutation enqueueInput(String turnId, UserContent content, TurnEventSink sink) {
        TurnUseCase.InputMutation result = mutateInput(turnId, false, authority -> {
            UserContent validated = validateQueuedContent(authority, content);
            return store.enqueueInput(new ConversationRepository.PendingInput(
                "input_" + UUID.randomUUID(), authority.key().threadId(), turnId,
                ConversationRepository.InputKind.FOLLOW_UP, validated, clock.instant()));
        });
        var candidate = store.findResumeCandidate(turnId);
        InteractionService interactions = interactionOwner;
        if (candidate.isPresent() && interactions != null) {
            var suspended = candidate.orElseThrow();
            boolean superseded = interactions.read(suspended.threadId(), null)
                    .flatMap(io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot::request)
                    .filter(request -> request.turnId().equals(turnId)
                            && request.status() == io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus.SUPERSEDED).isPresent();
            if (superseded) {
                interactions.publishLatest(suspended.threadId());
                scheduleInteractionResume(suspended.threadId(), turnId, suspended.threadRevision(), sink, 0);
            }
        }
        return result;
    }

    /** 提升按 SQLite 分配的点击序列移动到普通 FIFO 之前，不中断当前 Provider 或 Tool。 */
    @Override
    public TurnUseCase.InputMutation prioritizeInput(String turnId, String inputId,
                                                     long expectedInputRevision) {
        return mutateInput(turnId, false, authority -> store.prioritizeInput(
                authority.key().threadId(), turnId, inputId, expectedInputRevision, clock.instant()));
    }

    /** 编辑只作用于尚未消费的条目；SUSPENDED 可从 SQLite 恢复授权，但不会隐式恢复执行。 */
    @Override
    public TurnUseCase.InputMutation updateInput(String turnId, String inputId,
                                                 long expectedInputRevision, UserContent content) {
        return mutateInput(turnId, true, authority -> {
            UserContent validated = validateQueuedContent(authority, content);
            return store.updateInput(authority.key().threadId(), turnId,
                    inputId, expectedInputRevision, validated, clock.instant());
        });
    }

    /** 删除通过 item revision CAS；SUSPENDED 只修复队列，Turn 仍须用户显式恢复。 */
    @Override
    public TurnUseCase.InputMutation deleteInput(String turnId, String inputId,
                                                 long expectedInputRevision) {
        return mutateInput(turnId, true, authority -> store.deleteInput(
                authority.key().threadId(), turnId, inputId, expectedInputRevision, clock.instant()));
    }

    /**
     * update/delete 在运行 owner 释放后可使用 SQLite SUSPENDED 候选恢复最小授权；enqueue/prioritize
     * 仍要求活动 owner，避免“准备修复”绕过显式 Resume 成为新的提交入口。
     */
    private TurnUseCase.InputMutation mutateInput(
            String turnId, boolean allowSuspended,
            Function<InputMutationAuthority, ConversationRepository.QueueMutation> mutation) {
        InputMutationAuthority authority = inputMutationAuthority(turnId, allowSuspended);
        ConversationRepository.QueueMutation receipt;
        try {
            receipt = mutation.apply(authority);
        } catch (ConversationRepository.InputQueueException failure) {
            throw mapInputFailure(failure);
        }
        if (receipt.changed()) publishInputQueueChanged(authority.key(), receipt);
        return new TurnUseCase.InputMutation(true, receipt.inputId(), receipt.inputQueue());
    }

    /**
     * 活动 owner 提供当前执行上下文；缺失时只接受 SQLite 确认的 SUSPENDED 候选，并携带其
     * Thread/Workspace/Deadline 快照完成校验，禁止构造伪 TurnOwnership 或复用已结束 sink。
     */
    private InputMutationAuthority inputMutationAuthority(String turnId, boolean allowSuspended) {
        Map.Entry<Key, TurnOwnership> activeOwner = active.entrySet().stream()
                .filter(entry -> entry.getKey().turnId().equals(turnId))
                .findFirst().orElse(null);
        if (activeOwner != null) {
            TurnOwnership owner = activeOwner.getValue();
            return new InputMutationAuthority(activeOwner.getKey(), owner.request.workspaceId(),
                    owner.request.workspaceRoot(), owner.deadlineAt);
        }
        if (!allowSuspended) {
            throw TurnUseCase.InputMutationException.of(TurnUseCase.InputMutationFailure.TURN_NOT_FOUND);
        }
        try {
            ConversationRepository.ResumeCandidate suspended = store.findResumeCandidate(turnId)
                    .orElseThrow(() -> TurnUseCase.InputMutationException.of(
                            TurnUseCase.InputMutationFailure.TURN_NOT_FOUND));
            return new InputMutationAuthority(new Key(suspended.threadId(), suspended.turnId()),
                    suspended.workspaceId(), suspended.workspaceRoot(),
                    clock.instant().plus(suspended.execution().common().activeBudget()));
        } catch (ConversationRepository.InputQueueException failure) {
            throw mapInputFailure(failure);
        }
    }

    /**
     * Repository 已提交后 ACK 始终成功返回；事件仅 best-effort 投递给此刻仍登记的 owner，
     * 防止 SUSPENDED 或退出竞态中的陈旧 sink 把已生效 mutation 伪装成操作失败。
     */
    private void publishInputQueueChanged(Key key, ConversationRepository.QueueMutation receipt) {
        TurnOwnership recipient = active.get(key);
        if (recipient == null) return;
        try {
            TurnEvent.Context context = new TurnEvent.Context("evt_" + UUID.randomUUID(),
                    key.threadId(), key.turnId(), receipt.threadRevision(), clock.instant());
            await(recipient.sink.publish(new TurnEvent.InputQueueChanged(context, receipt.inputQueue())));
        } catch (RuntimeException failure) {
            LOGGER.warn("Input queue publication failed threadId={} turnId={} cause={}",
                    key.threadId(), key.turnId(), failure.getClass().getSimpleName());
        }
    }

    /** mutation 校验只保存 SQLite 或活动 owner 已确认的最小身份，不拥有运行资源。 */
    private record InputMutationAuthority(Key key, String workspaceId,
                                          java.nio.file.Path workspaceRoot, Instant deadlineAt) {
        /** 拒绝缺失身份，避免 SUSPENDED fallback 退化为拼装字符串的伪 owner。 */
        private InputMutationAuthority {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
            Objects.requireNonNull(deadlineAt, "deadlineAt");
        }
    }

    /** Repository 闭集与 RPC 闭集一一映射，禁止 transport 解析异常文本。 */
    private static TurnUseCase.InputMutationException mapInputFailure(
            ConversationRepository.InputQueueException failure) {
        TurnUseCase.InputMutationFailure mapped = switch (failure.failure()) {
            case NOT_ACCEPTING -> TurnUseCase.InputMutationFailure.TURN_NOT_FOUND;
            case CAPACITY -> TurnUseCase.InputMutationFailure.CONTENT_TOO_LARGE;
            case NOT_FOUND -> TurnUseCase.InputMutationFailure.INPUT_NOT_FOUND;
            case CONFLICT -> TurnUseCase.InputMutationFailure.CONFLICT;
        };
        return TurnUseCase.InputMutationException.of(mapped);
    }

    /** 首轮准入与队列编辑都调用 Workspace owner；任何底层分类只收敛为稳定引用失效。 */
    private UserContent validateWorkspaceReferences(String workspaceId, UserContent content) {
        try {
            List<UserContentBlock> validated = new ArrayList<>();
            for (UserContentBlock block : content.blocks()) {
                if (!(block instanceof WorkspaceReferenceContent reference)) {
                    validated.add(block);
                    continue;
                }
                WorkspaceEntryKind requestedKind = reference.kind() == WorkspaceReferenceContent.Kind.FILE
                        ? WorkspaceEntryKind.FILE : WorkspaceEntryKind.DIRECTORY;
                WorkspaceReferenceValidator.ValidatedReference result = workspaceReferences.validate(
                        new WorkspaceReferenceValidator.ValidationRequest(workspaceId, reference.workspaceId(),
                                reference.relativePath(), requestedKind));
                WorkspaceReferenceContent.Kind validatedKind = result.kind() == WorkspaceEntryKind.FILE
                        ? WorkspaceReferenceContent.Kind.FILE : WorkspaceReferenceContent.Kind.DIRECTORY;
                validated.add(new WorkspaceReferenceContent(
                        result.workspaceId(), result.relativePath(), validatedKind));
            }
            return new UserContent(validated);
        } catch (WorkspacePathFailure | WorkspaceFailure | IllegalArgumentException failure) {
            throw TurnUseCase.ContentValidationException.of(
                    TurnUseCase.ContentFailure.WORKSPACE_REFERENCE_INVALID);
        }
    }

    /**
     * 队列准入只在实际携带 Skill 时短租当前目录；纯正文/附件修复不依赖 Provider 配置，
     * SUSPENDED 因而能先移除坏附件，再由用户显式 Resume 处理运行环境。
     */
    private UserContent validateQueuedContent(InputMutationAuthority authority, UserContent content) {
        UserContent validated = validateWorkspaceReferences(authority.workspaceId(), content);
        if (validated.skillIds().isEmpty()) return validated;
        try (RuntimeLease runtime = openCurrentLease(authority.key().threadId(), null, authority.workspaceRoot(),
                authority.workspaceId(), authority.deadlineAt(), TurnOrigin.USER)) {
            runtime.promptSession().validateSkillReferences(validated.skillIds());
        } catch (SkillSelectionException failure) {
            throw TurnUseCase.ContentValidationException.of(TurnUseCase.ContentFailure.SKILL_UNAVAILABLE);
        }
        return validated;
    }

    /** 首条消息必须在 admission 前完成实时 Skill 加载，失败时不写历史也不调用模型。 */
    private static void replaceMessageSkills(RuntimeLease runtime, UserContent content) {
        try {
            runtime.promptSession().replaceActiveSkills(content.skillIds());
        } catch (SkillSelectionException failure) {
            TurnUseCase.ContentFailure mapped = failure.code() == SkillSelectionException.Code.SKILL_UNAVAILABLE
                    ? TurnUseCase.ContentFailure.SKILL_UNAVAILABLE
                    : TurnUseCase.ContentFailure.SKILL_LOAD_FAILED;
            throw TurnUseCase.ContentValidationException.of(mapped);
        }
    }

    /**
     * 将 Workspace owner 与当前冻结 Prompt Session 组合成消费期窄门；底层异常只映射为稳定队列问题，
     * 不把绝对路径、Skill 文件内容或配置细节带入 Loop。
     */
    private QueuedInputBoundary queuedInputBoundary(String threadId, java.nio.file.Path workspaceRoot,
                                                     String workspaceId, Instant deadlineAt) {
        return content -> {
            try {
                validateWorkspaceReferences(workspaceId, content);
                try (RuntimeLease runtime = openCurrentLease(threadId, null, workspaceRoot, workspaceId,
                        deadlineAt, TurnOrigin.USER)) {
                    AgentPromptSession.SkillReplacement replacement =
                            runtime.promptSession().prepareSkillReplacement(content.skillIds());
                    return QueuedInputBoundary.Prepared.replacement(
                            replacement.promptRevision(), replacement.activeSkillReferences(), () -> { });
                }
            } catch (TurnUseCase.ContentValidationException failure) {
                String code = failure.failure().name();
                String message = switch (failure.failure()) {
                    case WORKSPACE_REFERENCE_INVALID -> "Workspace reference is no longer valid.";
                    case SKILL_UNAVAILABLE -> "Selected Skill is no longer enabled.";
                    case SKILL_LOAD_FAILED -> "Selected Skill could not be loaded.";
                    case CONTENT_TOO_LARGE -> "Queued content exceeds the supported limit.";
                };
                throw new QueuedInputBoundary.Rejected(new InputQueue.Issue(code, message, true));
            } catch (SkillSelectionException failure) {
                String code = failure.code() == SkillSelectionException.Code.SKILL_UNAVAILABLE
                        ? TurnUseCase.ContentFailure.SKILL_UNAVAILABLE.name()
                        : TurnUseCase.ContentFailure.SKILL_LOAD_FAILED.name();
                String message = failure.code() == SkillSelectionException.Code.SKILL_UNAVAILABLE
                        ? "Selected Skill is no longer enabled."
                        : "Selected Skill could not be loaded.";
                throw new QueuedInputBoundary.Rejected(new InputQueue.Issue(code, message, true));
            }
        };
    }

    /**
     * 在与准入共用的监视器内关闭服务和队列入口，确保关闭快照后不再新增 owner。
     */
    public void stopAccepting() {
        synchronized (admissionLifecycle) {
            if (accepting.compareAndSet(true, false)) queue.stopAccepting();
        }
    }

    /**
     * 等待全部 Thread Lane 退出，仅报告队列是否在给定预算内静默。
     */
    public boolean awaitQuiescence(Duration timeout) {
        return queue.awaitQuiescence(timeout);
    }

    /**
     * 以默认总预算委托关闭协调器收敛准入、活动 Turn 和自有执行器。
     */
    @Override
    public void close() {
        closeAt(ShutdownDeadline.start().deadlineNanos());
    }

    /**
     * 使用调用方单调截止线执行幂等关闭；先运行侧聊前置 hook，再停止 Turn 准入，
     * 保证临时 owner 仍能通过同一 TurnService 完成取消。
     */
    @Override
    public void closeAt(long shutdownDeadlineNanos) {
        RuntimeException failure = null;
        try {
            preShutdownHook.get().accept(shutdownDeadlineNanos);
        } catch (RuntimeException hookFailure) {
            failure = hookFailure;
        }
        try {
            shutdown.closeAt(shutdownDeadlineNanos);
        } catch (RuntimeException shutdownFailure) {
            if (failure == null) failure = shutdownFailure;
            else failure.addSuppressed(shutdownFailure);
        }
        try {
            automaticTitles.closeAt(shutdownDeadlineNanos);
        } catch (RuntimeException titleFailure) {
            if (failure == null) failure = titleFailure;
            else failure.addSuppressed(titleFailure);
        }
        if (failure != null) throw failure;
    }

    /**
     * 执行已接纳 Turn，并在所有退出路径取消 Deadline、释放 Scope 和索引；请求租约由安全点就地释放。
     */
    private void run(Key key, TurnOwnership turn) {
        try {
            TurnResult result = await(loop.run(turn.request, turn.cancellation, turn.sink,
                    turn.terminalCoordinator, turn.execution));
            if (result.state() == TurnState.COMPLETED) {
                scheduleAutomaticTitle(turn, result);
            }
            Throwable debt = turn.cancellationDebt.get();
            if (debt == null) turn.completion.complete(result);
            else turn.completion.completeExceptionally(debt);
        } catch (AgentLoop.UnsafeGenerationException unsafe) {
            // 草稿排序权威丢失后不允许提交终态；持久化 RUNNING 是刻意保留的恢复证据，
            // Completion 只负责释放 Turn 外层租约。
            turn.completion.completeExceptionally(unsafe);
        } catch (AgentLoop.InputNeedsAttentionException attention) {
            // Loop 已持久化 needs_attention 与 SUSPENDED；这里只结束运行 owner，禁止再写失败终态。
            turn.completion.completeExceptionally(attention);
        } catch (InteractionSuspendedException suspended) {
            // Interaction 已在 Loop 内原子持久化为 SUSPENDED；答案到达后由调度器复用同一 Resume CAS。
            turn.completion.completeExceptionally(suspended);
        } catch (AgentLoop.PlanPauseSuspendedException suspended) {
            // Plan pause 已在 Loop 安全点保留 cursor；不能再进入 CANCELLED/FAILED 终态。
            turn.completion.completeExceptionally(new PlanSuspendedException());
        } catch (CancellationException cancelled) {
            cancellationLifecycle.awaitBarrier(key, turn);
            if (turn.planPauseRequested.get() && suspendCancelledTurn(key)) {
                turn.completion.completeExceptionally(new PlanSuspendedException());
            } else {
                terminalSettlement.settleEmergency(turn, TurnState.CANCELLED, "CANCELLED",
                        "turn cancelled", turn.cancellationDebt.get());
            }
        } catch (TerminalCoordinator.CommitFailure failure) {
            cancellationLifecycle.awaitBarrier(key, turn);
            terminalSettlement.settleEmergency(turn, TurnState.FAILED, "INTERNAL_ERROR",
                    "turn execution failed", null);
        } catch (TerminalCoordinator.ProjectionFailure failure) {
            // 持久终态回执已经存在；调用方观察一次异常完成，Transport 恢复则重新读取权威投影。
            turn.completion.completeExceptionally(failure);
        } catch (RuntimeException failure) {
            cancellationLifecycle.awaitBarrier(key, turn);
            terminalSettlement.settleEmergency(turn, TurnState.FAILED, "INTERNAL_ERROR",
                    "turn execution failed", null);
        } catch (Throwable failure) {
            // Provider 或 Tool 错误仍属于已接纳 Turn 的可观察完成结果；若任其逃逸，关闭流程会永久等待未完成 Future。
            cancellationLifecycle.awaitBarrier(key, turn);
            terminalSettlement.settleEmergency(turn, TurnState.FAILED, "INTERNAL_ERROR",
                    "turn execution failed", failure);
        } finally {
            if (turn.deadline != null) turn.deadline.cancel(false);
            turn.cancellation.close();
            cancellations.complete(key.threadId(), key.turnId());
            cancellationLifecycle.clearBarrier(key);
            active.remove(key, turn);
            store.findTurn(key.threadId(), key.turnId()).ifPresent(snapshot -> {
                if (snapshot.state().terminal()) resumeContinuations.remove(key.turnId());
            });
        }
    }

    /**
     * 终态已经发布后仅非阻塞提交后台标题任务；runtime factory 留到 worker 真正发送前执行，
     * 因而排队期间的模型或 reasoning 修改会自然进入标题请求，调度故障也不能反向改写成功 Turn。
     */
    private CompletionStage<Void> scheduleAutomaticTitle(TurnOwnership turn, TurnResult result) {
        if (!turn.provisionalTitleCreated) return null;
        try {
            return automaticTitles.schedule(new AutomaticThreadTitleScheduler.Request(
                    turn.request.threadId(), turn.request.turnId(),
                    result.terminal().context().threadRevision(), turn.request.userInput(),
                    result.terminal().finalMessage().text(), timeout -> {
                        RuntimeLease lease = openCurrentLease(turn.request.threadId(), null,
                                turn.request.workspaceRoot(), turn.request.workspaceId(),
                                clock.instant().plus(timeout), TurnOrigin.USER);
                        return new AutomaticThreadTitleScheduler.RequestRuntime(lease.model(), lease);
                    }), turn.sink);
        } catch (RuntimeException failure) {
            LOGGER.warn("Automatic title scheduling failed threadId={} turnId={} cause={}",
                    turn.request.threadId(), turn.request.turnId(), failure.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * admission 已提交后立即发布短标题且不阻塞 Agent；通知失败只意味着客户端按既有恢复机制重读，
     * 不能反向回滚已接纳 Turn 或延迟首个模型输出。
     */
    private static void publishProvisionalTitle(TurnExecutionPlan command,
                                                ChildTurnScheduler.AdmissionReceipt receipt,
                                                TurnEventSink sink) {
        if (!receipt.createdProvisionalTitle()) return;
        try {
            CompletionStage<Void> publication = Objects.requireNonNull(sink.publish(new ThreadMetadataEvent(
                    command.threadId(), command.workspaceId(), receipt.threadRevision(),
                    receipt.provisionalTitle(), ThreadPreferences.TitleSource.PLACEHOLDER)),
                    "metadata publication");
            publication.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.warn("Provisional title publication failed threadId={} turnId={} cause={}",
                            command.threadId(), command.turnId(), failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Provisional title publication failed threadId={} turnId={} cause={}",
                    command.threadId(), command.turnId(), failure.getClass().getSimpleName());
        }
    }

    /** 入站调度 DTO 只在仓储调用点转换，避免 port.in 依赖 port.out。 */
    private static ConversationRepository.TurnAdmission repositoryAdmission(AdmissionContext admission) {
        return new ConversationRepository.TurnAdmission(admission.threadId(), admission.turnId(),
                admission.messageId(), admission.userMessage(), admission.attachmentIds(),
                admission.expectedThreadRevision(), admission.requestedAt(), admission.initialExecution());
    }

    /** Child port 仍只接收真实 USER message；内部来源无法通过该转换。 */
    private static ChildTurnScheduler.AdmissionRequest childAdmission(AdmissionContext admission) {
        return new ChildTurnScheduler.AdmissionRequest(admission.threadId(), admission.turnId(),
                admission.messageId(), admission.userMessage(), admission.attachmentIds(),
                admission.expectedThreadRevision(), admission.requestedAt(), admission.initialExecution());
    }

    /** 仓储回执在离开应用层出站边界前收窄为调度合同。 */
    private static ChildTurnScheduler.AdmissionReceipt admissionReceipt(
            ConversationRepository.AdmissionReceipt receipt) {
        return new ChildTurnScheduler.AdmissionReceipt(receipt.threadId(), receipt.turnId(),
                receipt.threadRevision(), receipt.turnMutationVersion(), receipt.provisionalTitle());
    }

    /**
     * Resume CAS 已提交后同步发布 SUSPENDED -> QUEUED，确保客户端不会先观察无法归约的 QUEUED -> RUNNING；
     * 投影失败只触发重读，不回滚已经取得的数据库执行权。
     */
    private static void publishResumeQueued(ConversationRepository.ResumeCandidate candidate,
                                            ConversationRepository.ResumeReceipt receipt,
                                            TurnEventSink sink, Instant occurredAt) {
        TurnEvent.StateChanged event = new TurnEvent.StateChanged(new TurnEvent.Context(
                "evt_" + UUID.randomUUID(), candidate.threadId(), candidate.turnId(),
                receipt.threadRevision(), occurredAt), TurnState.SUSPENDED, TurnState.QUEUED);
        try {
            await(Objects.requireNonNull(sink.publish(event), "resume state publication"));
        } catch (RuntimeException failure) {
            LOGGER.warn("Resume queued publication failed threadId={} turnId={} cause={}",
                    candidate.threadId(), candidate.turnId(), failure.getClass().getSimpleName());
        }
    }

    /**
     * 校验出站解析器只收紧 Deadline，且不得改变请求冻结的访问与协作模式；后者决定 Prompt/Tool
     * 目录和 Plan 执行资格，若在准入后漂移就必须整次拒绝，不能静默降级到另一种 Agent 行为。
     */
    private static void validateResolvedRuntime(StartCommand request, RuntimeLease runtime) {
        if (runtime.limits().wallTimeout().compareTo(request.deadline()) > 0
            || runtime.accessMode() != request.accessMode()
            || runtime.collaborationMode() != request.collaborationMode()) {
            throw new IllegalArgumentException("resolved runtime does not match turn start request");
        }
    }

    /** RuntimeLease 是权限/配置 owner，Plan ceiling 只能取交集而不能放宽它。 */
    private static TurnLimits capLimits(TurnLimits actual, TurnLimits ceiling) {
        if (ceiling == null) return actual;
        return new TurnLimits(Math.min(actual.maxModelRounds(), ceiling.maxModelRounds()),
                Math.min(actual.maxToolCalls(), ceiling.maxToolCalls()), actual.maxInputTokens(),
                actual.maxOutputTokens(), actual.wallTimeout().compareTo(ceiling.wallTimeout()) <= 0
                        ? actual.wallTimeout() : ceiling.wallTimeout());
    }

    /** 每个请求安全点按稳定 Skill ID 重读正文；缺失或无效作为本次请求失败而非旧环境恢复。 */
    private static void restoreRequestPrompt(TurnExecutionState.Common common, String promptSummary,
                                             RuntimeLease runtime) {
        try {
            runtime.promptSession().restoreActiveSkills(promptSummary, common.activeSkills());
        } catch (SkillSelectionException failure) {
            throw TurnUseCase.ContentValidationException.of(
                    failure.code() == SkillSelectionException.Code.SKILL_UNAVAILABLE
                            ? TurnUseCase.ContentFailure.SKILL_UNAVAILABLE
                            : TurnUseCase.ContentFailure.SKILL_LOAD_FAILED);
        }
    }

    /**
     * admission 从真实租约建立恢复基线，禁止以固定超时、零摘要或配置代际冒充
     * Prompt 修订；Active Skill 初始为空，后续只持久化名称并在恢复时重新读取。
     */
    private static TurnExecutionState.Ready initialExecution(RuntimeLease lease, Instant deadlineAt,
                                                              TurnOrigin origin) {
        TurnExecutionState.Common common = new TurnExecutionState.Common(
                0, 0, 1, null, lease.promptSession().activeSkillReferences(), deadlineAt, origin,
                lease.limits().wallTimeout());
        return new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null);
    }

    /**
     * 请求安全点从 SQLite 权威 ThreadPreferences 解析最新配置；剩余时长只会收紧，不能延长 admission Deadline。
     */
    private RuntimeLease openCurrentLease(String threadId, String turnId, java.nio.file.Path workspaceRoot,
                                          String workspaceId, Instant deadlineAt, TurnOrigin origin) {
        ConversationRepository.ThreadSnapshot snapshot = store.readThread(threadId)
                .orElseThrow(() -> new AgentLoop.LoopFailure("INVALID_STATE", "Thread history is unavailable"));
        Duration remaining = Duration.between(clock.instant(), deadlineAt);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new AgentLoop.LoopFailure("REQUEST_DEADLINE_EXCEEDED", "turn deadline exceeded");
        }
        ThreadPreferences preferences = snapshot.preferences();
        TurnRuntimeRequest request = new TurnRuntimeRequest(threadId, turnId, workspaceRoot, workspaceId,
                preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                preferences.accessMode(), preferences.collaborationMode(), origin, remaining, clock.instant());
        return Objects.requireNonNull(runtimeResolver.resolve(request), "runtimeLease");
    }

    /** 创建生产请求 factory；每次调用都重新走配置 Owner，且返回值关闭即释放 Provider/MCP 凭据。 */
    private TurnExecutionPlan.RequestRuntimeFactory requestRuntimeFactory(
            String threadId, String turnId, java.nio.file.Path workspaceRoot, UserContent content,
            TurnOrigin origin, Instant requestedAt, String workspaceId, Instant deadlineAt, TurnLimits ceiling) {
        return (common, promptSummary) -> {
            RuntimeLease lease = openCurrentLease(threadId, turnId, workspaceRoot, workspaceId, deadlineAt, origin);
            boolean transferred = false;
            try {
                restoreRequestPrompt(common, promptSummary, lease);
                TurnLimits effectiveLimits = capLimits(lease.limits(), ceiling);
                TurnExecutionPlan plan = new TurnExecutionPlan(threadId, turnId, workspaceRoot, content, origin,
                        lease.model(), lease.accessMode(), effectiveLimits, requestedAt, workspaceId,
                        0, 0, promptSummary, lease.promptSession(),
                        queuedInputBoundary(threadId, workspaceRoot, workspaceId, deadlineAt),
                        lease.attachments(), lease.tools(), lease.generationId(), lease.toolSessions(),
                        lease.outputLimits(), lease.presentationSecrets(), deadlineAt,
                        requestRuntimeFactory(threadId, turnId, workspaceRoot, content, origin, requestedAt,
                                workspaceId, deadlineAt, ceiling), TurnChangeTracker.fresh(workspaceRoot));
                TurnExecutionPlan.RequestRuntime result = new TurnExecutionPlan.RequestRuntime(
                        plan, lease.requestProfile(lease.promptSession().currentRevision()), lease);
                transferred = true;
                return result;
            } finally {
                if (!transferred) lease.close();
            }
        };
    }

    /**
     * 将 Deadline 或关闭触发转换为 CAS 优先的取消流程，禁止直接发布未持久化 Token。
     */
    private void cancelFromRuntime(Key key, TurnOwnership turn, String reason) {
        cancellationLifecycle.requestCancellation(key, turn, reason);
    }

    /**
     * 回答事务提交后只排队恢复意图；旧 Loop 尚未执行 finally 时保持等待，避免同一 Turn 出现两个 owner。
     */
    private void scheduleInteractionResume(String threadId, String turnId, long threadRevision,
                                            TurnEventSink sink, int attempt) {
        if (!accepting.get() || shutdown.isClosed()) return;
        Key key = new Key(threadId, turnId);
        PendingInteractionResume pending = new PendingInteractionResume(threadRevision, sink, attempt);
        if (pendingInteractionResumes.putIfAbsent(key, pending) != null) return;
        try {
            long delay = attempt == 0 ? 0L : Math.min(2_000L, 25L << Math.min(6, attempt - 1));
            deadlines.schedule(() -> tryInteractionResume(key, pending), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            pendingInteractionResumes.remove(key, pending);
        }
    }

    /** 取消收口只在持久 execution 仍存在且双 CAS 未被其它终态赢走时保留暂停事实。 */
    private boolean suspendCancelledTurn(Key key) {
        ConversationRepository.TurnSnapshot current = store.findTurn(key.threadId(), key.turnId()).orElse(null);
        if (current == null || current.state().terminal()) return false;
        return store.suspendCancelled(key.threadId(), key.turnId(), current.threadRevision(),
                current.turnMutationVersion(), clock.instant());
    }

    /** Plan pause 的可恢复控制流异常不应被 Plan coordinator 结算为失败。 */
    private static boolean isPlanSuspended(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PlanSuspendedException) return true;
            current = current.getCause();
        }
        return false;
    }

    /** 仅在暂停 cursor 已由 SQLite 保留后向上层传播，禁止携带内部执行细节。 */
    public static final class PlanSuspendedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** 固定消息避免暂停控制流把 Provider 或数据库细节带出边界。 */
        /** 构造无内部细节的控制流异常，供 Plan adapter 识别可恢复暂停。 */
        public PlanSuspendedException() {
            super("plan turn suspended");
        }
    }

    /**
     * 恢复只重试有限次数；若 owner 长时间未释放或应用正在关闭，保留 SUSPENDED 权威事实供显式 Resume。
     */
    private void tryInteractionResume(Key key, PendingInteractionResume pending) {
        if (!accepting.get() || shutdown.isClosed()) {
            pendingInteractionResumes.remove(key, pending);
            return;
        }
        if (active.containsKey(key)) {
            retryInteractionResume(key, pending);
            return;
        }
        try {
            resume(key.turnId(), pending.threadRevision(), pending.sink());
        } catch (TurnUseCase.TurnResumeException failure) {
            if (failure.failure() == TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE) {
                pendingInteractionResumes.remove(key, pending);
                LOGGER.warn("Interaction answer was persisted but Turn is no longer resumable threadId={} turnId={}",
                        key.threadId(), key.turnId());
                return;
            }
            retryInteractionResume(key, pending);
            return;
        } catch (RuntimeException failure) {
            retryInteractionResume(key, pending);
            return;
        }
        pendingInteractionResumes.remove(key, pending);
    }

    /** 有界指数退避避免网络重试或旧 owner 卡住时创建无限后台任务。 */
    private void retryInteractionResume(Key key, PendingInteractionResume pending) {
        if (pending.attempt() >= 8) {
            pendingInteractionResumes.remove(key, pending);
            LOGGER.warn("Interaction resume remains pending for explicit recovery threadId={} turnId={}",
                    key.threadId(), key.turnId());
            return;
        }
        pendingInteractionResumes.remove(key, pending);
        scheduleInteractionResume(key.threadId(), key.turnId(), pending.threadRevision(), pending.sink(),
                pending.attempt() + 1);
    }

    /**
     * 用稳定错误码创建准入拒绝，避免关闭分支泄露内部状态。
     */
    private static RejectedExecutionException rejected(String code) {
        return new RejectedExecutionException(code);
    }

    /**
     * 同步取得 Agent Loop 结果，并解包运行时异常以进入对应终态分支。
     */
    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    /**
     * 以 Thread 与 Turn 双维度索引活动所有权和取消屏障。
     */
    static record Key(String threadId, String turnId) {
        /**
         * 拒绝缺失的身份分量，防止活动表出现无法释放的匿名键。
         */
        Key {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
        }
    }

    /** Interaction resume 的内存去重门；答案与 Turn 状态仍以 SQLite 为最终权威。 */
    private record PendingInteractionResume(long threadRevision, TurnEventSink sink, int attempt) {
        /** 重试只携带已提交的版本与当前连接出口，不能扩大执行预算。 */
        private PendingInteractionResume {
            if (threadRevision < 0 || attempt < 0 || attempt > 8 || sink == null) {
                throw new IllegalArgumentException("invalid interaction resume schedule");
            }
        }
    }

    /**
     * 统一公开与内部启动字段；content/origin 组合在进入运行时前即验证，后续路径不再根据文本猜测来源。
     */
    private record StartCommand(String threadId, String turnId, String workspaceId,
                                java.nio.file.Path workspaceRoot, UserContent content,
                                String providerId, String modelId, String reasoningLevel,
                                io.github.kongweiguang.ja.conversation.domain.permission.AccessMode accessMode,
                                io.github.kongweiguang.ja.conversation.domain.CollaborationMode collaborationMode,
                                Duration deadline, long expectedThreadRevision,
                                long initialTurnMutationVersion, Instant requestedAt, TurnOrigin origin) {
        /** 公开/Child 请求必须有内容，内部请求必须无内容。 */
        private StartCommand {
            Objects.requireNonNull(origin, "origin");
            if (origin.internal() != (content == null)) {
                throw new IllegalArgumentException("Turn start content does not match origin");
            }
        }

        /** 公开 DTO 显式映射为 user-authored origin，不能由调用方自行声明内部来源。 */
        private static StartCommand user(TurnStartRequest request, TurnOrigin origin) {
            Objects.requireNonNull(request, "request");
            if (origin.internal()) throw new IllegalArgumentException("user Turn origin is invalid");
            return new StartCommand(request.threadId(), request.turnId(), request.workspaceId(),
                    request.workspaceRoot(), request.content(), request.providerId(), request.modelId(),
                    request.reasoningLevel(), request.accessMode(), request.collaborationMode(), request.deadline(),
                    request.expectedThreadRevision(), request.initialTurnMutationVersion(), request.requestedAt(), origin);
        }

        /** 内部 DTO 没有 content 字段，映射时保持强类型来源。 */
        private static StartCommand internal(InternalTurnStartRequest request) {
            Objects.requireNonNull(request, "request");
            return new StartCommand(request.threadId(), request.turnId(), request.workspaceId(),
                    request.workspaceRoot(), null, request.providerId(), request.modelId(), request.reasoningLevel(),
                    request.accessMode(), request.collaborationMode(), request.deadline(),
                    request.expectedThreadRevision(), request.initialTurnMutationVersion(), request.requestedAt(),
                    request.origin());
        }
    }

    /** admission adapter 只在 user-authored 路径携带 messageId/userMessage。 */
    private record AdmissionContext(String threadId, String turnId, String messageId,
                                    ModelMessage userMessage, List<String> attachmentIds,
                                    long expectedThreadRevision, Instant requestedAt,
                                    TurnExecutionState initialExecution) { }

    /** 各类 persistence admission 共享队列和取消生命周期，但保持各自 DTO。 */
    @FunctionalInterface
    private interface StartAdmission {
        /** 调用成功必须表示对应 SQLite admission 已完整提交。 */
        ChildTurnScheduler.AdmissionReceipt admit(AdmissionContext admission);
    }
}
