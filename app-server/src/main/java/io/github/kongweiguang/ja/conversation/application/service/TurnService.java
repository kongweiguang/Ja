// @author kongweiguang
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
import io.github.kongweiguang.ja.conversation.domain.ClientOperationReceipt;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.conversation.application.prompt.DefaultAgentPromptSessionFactory.SkillSelectionException;
import io.github.kongweiguang.ja.conversation.port.in.ThreadMetadataEvent;
import io.github.kongweiguang.ja.conversation.port.in.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.NativeExecutionContext;
import io.github.kongweiguang.ja.conversation.domain.NativeExecutionSnapshot;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.HexFormat;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
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
    private static final long MAX_RECOVERY_FILE_BYTES = 4_000_000L;
    private static final long RECOVERY_ITEM_TIMEOUT_MILLIS = 2_000L;
    private static final long RECOVERY_TOTAL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private final ConversationRepository store;
    private final AgentLoop loop;
    private final TurnQueue queue;
    private final CancellationCoordinator cancellations;
    private final TurnRuntimeResolver runtimeResolver;
    private final Clock clock;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ja-turn-deadline-", 0).factory());
    private final Map<Key, TurnOwnership> active = new ConcurrentHashMap<>();
    private final Map<Key, NativeExecutionSnapshot> executionContexts = new ConcurrentHashMap<>();
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
    private volatile InteractionService interactionOwner;

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
     * 准入只用短租约验证首条输入；租约在 SQLite admission 后立即释放，
     * 后续每次 Provider 请求都从 ThreadPreferences 重新解析。
     */
    @SuppressWarnings("PMD.CloseResource")
    public TurnUseCase.Accepted start(TurnStartRequest request, TurnEventSink sink) {
        return startWithAdmission(StartCommand.user(request, TurnOrigin.USER), sink,
                admission -> admissionReceipt(store.admit(repositoryAdmission(admission))), "");
    }

    /** 客户端重试先读取已提交回执，命中时不解析新 Runtime，也不重复调度 Tool。 */
    @Override
    public TurnUseCase.Accepted start(TurnStartRequest request, TurnEventSink sink,
                                      String clientOperationId, String requestFingerprint) {
        TurnUseCase.Accepted existing = acceptedOperation(clientOperationId, "turn/start", requestFingerprint);
        if (existing != null) return existing;
        return startWithAdmission(StartCommand.user(request, TurnOrigin.USER), sink,
                admission -> admissionReceipt(store.admit(repositoryAdmission(admission),
                        clientOperationId, requestFingerprint)), "", null, clientOperationId);
    }

    /** 继续请求不创建 USER 消息；来源从当前 Thread 最后一个失败问题解析并由 admission 事务重验。 */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public TurnUseCase.Accepted continueQuestion(InternalTurnStartRequest request, TurnEventSink sink) {
        return continueQuestion(request, sink, null, null);
    }

    /** 隐藏续答的重复提交必须先命中原 receipt，旧 revision 已失效时仍能回到原 Turn。 */
    @Override
    public TurnUseCase.Accepted continueQuestion(InternalTurnStartRequest request, TurnEventSink sink,
                                                 String clientOperationId, String requestFingerprint) {
        if (clientOperationId != null) {
            TurnUseCase.Accepted existing = acceptedOperation(clientOperationId,
                    "turn/continue", requestFingerprint);
            if (existing != null) return existing;
        }
        if (request.origin() != TurnOrigin.USER_CONTINUATION) {
            throw new IllegalArgumentException("question continuation origin is required");
        }
        String sourceMessageId = store.findLastUnansweredQuestionMessageId(
                request.threadId(), request.expectedThreadRevision()).orElseThrow(() ->
                TurnUseCase.QuestionRecoveryException.of(TurnUseCase.QuestionRecoveryFailure.NOT_REASKABLE));
        String continuationContext = "继续回答当前路径最后一个尚未成功答复的问题，并结合已经提交的工具结果。";
        return startWithAdmission(StartCommand.internal(request), sink,
                admission -> {
                    ConversationRepository.ContinuationAdmission value =
                            new ConversationRepository.ContinuationAdmission(admission.threadId(), admission.turnId(),
                                    admission.expectedThreadRevision(), admission.requestedAt(),
                                    admission.initialExecution(), continuationContext, sourceMessageId);
                    return admissionReceipt(clientOperationId == null ? store.admitContinuation(value)
                            : store.admitContinuation(value, clientOperationId, requestFingerprint));
                },
                continuationContext, null, clientOperationId);
    }

    /** 重答沿普通 USER 生命周期运行，但只在持久 CAS 切旧路径成功时才进入队列。 */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public TurnUseCase.Accepted reask(TurnStartRequest request, String sourceMessageId, TurnEventSink sink) {
        return reask(request, sourceMessageId, sink, null, null);
    }

    /** 重答先查提交身份，再执行路径剪切；重复响应不会把已经完成的新路径当作新的失败问题。 */
    @Override
    public TurnUseCase.Accepted reask(TurnStartRequest request, String sourceMessageId, TurnEventSink sink,
                                      String clientOperationId, String requestFingerprint) {
        if (clientOperationId != null) {
            TurnUseCase.Accepted existing = acceptedOperation(clientOperationId,
                    "turn/reask", requestFingerprint);
            if (existing != null) return existing;
        }
        try {
            return startWithAdmission(StartCommand.user(request, TurnOrigin.USER), sink,
                    admission -> {
                        ConversationRepository.ReaskAdmission value = new ConversationRepository.ReaskAdmission(
                                repositoryAdmission(admission), sourceMessageId);
                        return admissionReceipt(clientOperationId == null ? store.admitReask(value)
                                : store.admitReask(value, clientOperationId, requestFingerprint));
                    }, "", null, clientOperationId);
        } catch (StorageException failure) {
            if (failure.code() == StorageException.Code.INVALID_STATE) {
                throw TurnUseCase.QuestionRecoveryException.of(TurnUseCase.QuestionRecoveryFailure.NOT_REASKABLE);
            }
            throw failure;
        }
    }

    /** Handler 与服务内部共享同一持久读取，空值绝不解释为未发生外部副作用。 */
    @Override
    public Optional<ClientOperationReceipt> readClientOperation(String clientOperationId) {
        return store.readClientOperation(clientOperationId);
    }

    /** 只有同方法同指纹才能重放安全身份；已提交结果无需再次获取 Provider 或工作区租约。 */
    private TurnUseCase.Accepted acceptedOperation(String clientOperationId, String method,
                                                   String requestFingerprint) {
        ClientOperationReceipt receipt = store.readClientOperation(clientOperationId).orElse(null);
        if (receipt == null) return null;
        if (!receipt.matches(method, requestFingerprint)) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT, "client operation identity conflicts");
        }
        return new TurnUseCase.Accepted(receipt.threadId(), receipt.turnId(), receipt.threadRevision(),
                receipt.queued(), CompletableFuture.completedFuture(null));
    }

    /** Goal/Plan 内部 Turn 复用完整生命周期，但类型和 admission 都不提供 USER message。 */
    public TurnUseCase.Accepted startContinuation(InternalTurnStartRequest request, String hiddenSummary,
                                                  TurnEventSink sink) {
        return startContinuation(request, hiddenSummary, sink, null);
    }

    /**
     * Goal/Plan 后台续跑必须持有原 run 冻结的客户端环境；共享后台重启后若没有新客户端
     * 显式恢复绑定，拒绝用 daemon 自身环境执行原任务的 Shell 副作用。
     */
    public TurnUseCase.Accepted startContinuation(InternalTurnStartRequest request, String hiddenSummary,
                                                  TurnEventSink sink,
                                                  NativeExecutionSnapshot inheritedContext) {
        if (hiddenSummary == null || hiddenSummary.isBlank() || hiddenSummary.length() > 1_000_000) {
            throw new IllegalArgumentException("invalid Goal continuation context");
        }
        if (NativeExecutionContext.shared().sharedMode() && inheritedContext == null) {
            throw new IllegalStateException("native execution context must be rebound before continuation");
        }
        return startWithAdmission(StartCommand.internal(request), sink,
                admission -> admissionReceipt(store.admitContinuation(
                new ConversationRepository.ContinuationAdmission(admission.threadId(), admission.turnId(),
                        admission.expectedThreadRevision(), admission.requestedAt(), admission.initialExecution(),
                        hiddenSummary, null))),
                hiddenSummary, inheritedContext, null);
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
     * Child Task 复用普通 Turn 生命周期，并从明确的父 Turn 继承冻结执行环境；
     * Thread、lineage、seed 与首 Turn 的原子写入权仍交给 Task Repository。
     */
    @Override
    @SuppressWarnings("PMD.CloseResource")
    public TurnUseCase.Accepted startChild(TurnStartRequest request, TurnEventSink sink,
                                           ChildTurnScheduler.Admission admission,
                                           String sourceThreadId, String sourceTurnId) {
        ChildTurnScheduler.Admission required = ChildTurnScheduler.required(admission);
        NativeExecutionSnapshot inherited = sourceThreadId == null || sourceTurnId == null
                ? null : executionContexts.get(new Key(sourceThreadId, sourceTurnId));
        return startWithAdmission(StartCommand.user(request, TurnOrigin.CHILD_TASK), sink,
                value -> required.admit(childAdmission(value)), "", inherited, null);
    }

    /**
     * 所有来源共用唯一准入状态机；只有 user-authored origin 才验证 UserContent 并构造 USER message，
     * 内部来源从类型到 Provider 上下文都只携带 hidden summary。
     */
    private TurnUseCase.Accepted startWithAdmission(StartCommand request, TurnEventSink sink,
                                                    StartAdmission admission,
                                                    String initialSummary) {
        return startWithAdmission(request, sink, admission, initialSummary, null, null);
    }

    /** 客户端 operation 只影响重复准入的 ACK；继承上下文不扩大运行预算。 */
    private TurnUseCase.Accepted startWithAdmission(StartCommand request, TurnEventSink sink,
                                                    StartAdmission admission, String initialSummary,
                                                    NativeExecutionSnapshot inheritedContext,
                                                    String clientOperationId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(admission, "admission");
        NativeExecutionSnapshot executionContext = inheritedContext != null
                ? inheritedContext : NativeExecutionContext.shared().current().orElse(null);
        if (NativeExecutionContext.shared().sharedMode() && executionContext == null) {
            throw new IllegalStateException("native execution context is unavailable for turn admission");
        }
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
                request.requestedAt(), executionContext);
        RuntimeLease runtimeLease = Objects.requireNonNull(runtimeResolver.resolve(runtimeRequest), "runtimeLease");
        try {
            validateResolvedRuntime(request, runtimeLease);
            TurnLimits effectiveLimits = runtimeLease.limits();
            UserContent validatedContent = request.origin().internal()
                    ? null : validateWorkspaceReferences(request.workspaceId(), request.content());
            if (validatedContent != null) replaceMessageSkills(runtimeLease, validatedContent);
            /* 初始租约只约束本次 Provider/Tool IO，下一安全点重新取得完整请求窗口。 */
            Instant deadlineAt = request.requestedAt().plus(effectiveLimits.requestWindow());
            TurnExecutionPlan.RequestRuntimeFactory runtimeFactory = requestRuntimeFactory(
                    request.threadId(), request.turnId(), request.workspaceRoot(), validatedContent,
                    request.origin(), request.requestedAt(), request.workspaceId(),
                    executionContext);
            TurnChangeTracker changeTracker = TurnChangeTracker.fresh(request.workspaceRoot());
            TurnExecutionPlan executionRequest = new TurnExecutionPlan(request.threadId(), request.turnId(),
                    request.workspaceRoot(), validatedContent, request.origin(),
                    runtimeLease.model(), runtimeLease.accessMode(),
                    effectiveLimits, request.requestedAt(), request.workspaceId(),
                    request.expectedThreadRevision(), request.initialTurnMutationVersion(),
                    initialSummary, runtimeLease.promptSession(), queuedInputBoundary(request.threadId(), request.workspaceRoot(),
                            request.workspaceId(), executionContext),
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
                            command.requestedAt(), initialExecution(runtimeLease, command.origin())));
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
                            receipt.threadRevision(), clientOperationId != null,
                            CompletableFuture.completedFuture(null));
                }
                Key key = new Key(command.threadId(), command.turnId());
                // 准入只固定当前请求租约；后续轮次在安全点重新取得网络窗口。
                TurnExecutionState.Ready initialExecution = initialExecution(runtimeLease, command.origin());
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
                if (executionContext != null) executionContexts.put(key, executionContext);
                try {
                    publishProvisionalTitle(command, receipt, sink);
                    reservation.submit(() -> run(key, accepted));
                } catch (RuntimeException failure) {
                    active.remove(key, accepted);
                    if (executionContext != null) executionContexts.remove(key, executionContext);
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
        return resume(turnId, expectedThreadRevision, sink, null);
    }

    /**
     * 内部 Plan 恢复沿用精确 Run 快照；重启后显式用户 Resume 可以提供新连接快照，
     * 缺失两者时共享后台不能借用自己的启动环境执行旧 Turn。
     */
    @SuppressWarnings("PMD.CloseResource")
    public TurnUseCase.Accepted resume(String turnId, long expectedThreadRevision, TurnEventSink sink,
                                       NativeExecutionSnapshot inheritedContext) {
        Objects.requireNonNull(sink, "sink");
        ConversationRepository.ResumeCandidate candidate = recoverBeforeResume(turnId, expectedThreadRevision);
        expectedThreadRevision = candidate.threadRevision();
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
        Instant resumeRequestAt = clock.instant();
        Key resumeKey = new Key(candidate.threadId(), candidate.turnId());
        NativeExecutionSnapshot executionContext = executionContexts.get(resumeKey);
        if (executionContext == null) {
            executionContext = inheritedContext != null ? inheritedContext
                    : NativeExecutionContext.shared().current().orElse(null);
        }
        if (NativeExecutionContext.shared().sharedMode() && executionContext == null) {
            throw new IllegalStateException("native execution context must be rebound before resume");
        }
        RuntimeLease lease = openCurrentLease(candidate.threadId(), candidate.turnId(), candidate.workspaceRoot(),
                candidate.workspaceId(), origin, executionContext, resumeRequestAt);
        try {
            Instant resumedDeadline = resumeRequestAt.plus(lease.limits().requestWindow());
            TurnExecutionState resumedExecution = candidate.execution();
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
                        lease.limits(), resumedAt,
                        candidate.workspaceId(), receipt.threadRevision(),
                        receipt.turnMutationVersion(), candidate.initialSummary(),
                        lease.promptSession(), queuedInputBoundary(candidate.threadId(), candidate.workspaceRoot(),
                                candidate.workspaceId(), executionContext),
                        lease.attachments(), lease.tools(),
                        lease.generationId(), lease.toolSessions(), lease.outputLimits(), lease.presentationSecrets(),
                        resumedDeadline, requestRuntimeFactory(candidate.threadId(), candidate.turnId(),
                                candidate.workspaceRoot(), content, origin, resumedAt,
                                candidate.workspaceId(), executionContext),
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
                if (executionContext != null) executionContexts.put(key, executionContext);
                try {
                    publishResumeQueued(candidate, receipt, sink, resumedAt);
                    Consumer<CompletionStage<?>> continuation = resumeContinuations.remove(candidate.turnId());
                    if (continuation != null) continuation.accept(owner.completion);
                    reservation.submit(() -> run(key, owner));
                } catch (RuntimeException failure) {
                    active.remove(key, owner);
                    if (executionContext != null) executionContexts.remove(key, executionContext);
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
     * 前端的明确重试/跳过只映射成 Kernel 的封闭意图；真实的 Tool 结果、审计与版本 CAS 留在
     * ConversationRepository 事务内，避免 transport 假定状态已变化。
     */
    @Override
    public ToolRecoveryResponse respondToolRecovery(ToolRecoveryRequest request) {
        Objects.requireNonNull(request, "request");
        ConversationRepository.ToolRecoveryDisposition disposition = switch (request.disposition()) {
            case RETRY -> ConversationRepository.ToolRecoveryDisposition.RETRY;
            case SKIP -> ConversationRepository.ToolRecoveryDisposition.SKIP;
        };
        ConversationRepository.ToolRecoveryResolution resolution;
        try {
            resolution = store.resolveToolRecovery(new ConversationRepository.ToolRecoveryResolutionRequest(
                    request.turnId(), request.callId(), request.expectedThreadRevision(),
                    request.expectedRecoveryRevision(), disposition, request.idempotencyKey(), clock.instant()));
        } catch (StorageException failure) {
            if (failure.code() == StorageException.Code.CAS_CONFLICT) {
                throw TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_RESUME_ORDER_CONFLICT);
            }
            if (failure.code() == StorageException.Code.NOT_FOUND) {
                throw TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE);
            }
            throw failure;
        }
        return new ToolRecoveryResponse(resolution.threadId(), resolution.turnId(), resolution.threadRevision(),
                request.disposition(), resolution.changed());
    }

    /**
     * 在重新接纳前按顺序完成可信文件核实。每项仅比较已保存的精确相对路径、长度与摘要，Shell/MCP
     * 和旧记录一律停在原 Tool 详情等待用户选择；匹配只证明当前条件成立，不伪造原执行成功回执。
     */
    private ConversationRepository.ResumeCandidate recoverBeforeResume(String turnId, long expectedThreadRevision) {
        long startedNanos = System.nanoTime();
        long expected = expectedThreadRevision;
        while (true) {
            ConversationRepository.ResumeCandidate candidate = store.findResumeCandidate(turnId)
                    .orElseThrow(() -> TurnUseCase.TurnResumeException.of(
                            TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE));
            if (candidate.threadRevision() != expected) {
                throw TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_RESUME_ORDER_CONFLICT);
            }
            java.util.Optional<ConversationRepository.PendingToolRecovery> pending =
                    store.findPendingToolRecovery(turnId);
            if (pending.isEmpty()) return candidate;
            ConversationRepository.PendingToolRecovery recovery = pending.get();
            if (recovery.evidenceKind() != ConversationRepository.EvidenceKind.FILE_TEXT
                    || !fileRecoveryMatches(candidate.workspaceRoot(), recovery, startedNanos)) {
                throw TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.RECOVERY_REQUIRED);
            }
            try {
                ConversationRepository.ToolRecoveryResolution resolution = store.resolveToolRecovery(
                        new ConversationRepository.ToolRecoveryResolutionRequest(turnId, recovery.callId(),
                                candidate.threadRevision(), recovery.recoveryRevision(),
                                ConversationRepository.ToolRecoveryDisposition.VERIFIED,
                                automaticRecoveryKey(recovery), clock.instant()));
                expected = resolution.threadRevision();
            } catch (StorageException failure) {
                if (failure.code() == StorageException.Code.CAS_CONFLICT) {
                    throw TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_RESUME_ORDER_CONFLICT);
                }
                if (failure.code() == StorageException.Code.NOT_FOUND) {
                    throw TurnUseCase.TurnResumeException.of(TurnUseCase.ResumeFailure.TURN_NOT_RESUMABLE);
                }
                throw failure;
            }
        }
    }

    /** 自动核实使用稳定幂等身份，进程在落库后崩溃时下一次 Resume 只重读原裁决，不会第二次推进游标。 */
    private static String automaticRecoveryKey(ConversationRepository.PendingToolRecovery recovery) {
        return "auto_verify_" + recovery.recoveryId() + "_" + recovery.recoveryRevision();
    }

    /**
     * 在不阻塞 Resume 超过单项和总预算的前提下读取唯一目标。真实路径与大小先校验，随后才哈希字节；
     * 任一超时、取消、符号链接逃逸或 IO 异常都归为无法确认，绝不把异常当作文件不匹配或执行成功。
     */
    private static boolean fileRecoveryMatches(Path workspaceRoot,
                                               ConversationRepository.PendingToolRecovery recovery,
                                               long startedNanos) {
        long remainingNanos = RECOVERY_TOTAL_TIMEOUT_NANOS - (System.nanoTime() - startedNanos);
        if (remainingNanos <= 0) return false;
        long timeoutNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(RECOVERY_ITEM_TIMEOUT_MILLIS), remainingNanos);
        FutureTask<Boolean> task = new FutureTask<>(() -> matchesExpectedFile(workspaceRoot, recovery));
        Thread.ofVirtual().name("ja-recovery-verify-").start(task);
        try {
            return task.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return false;
        } catch (TimeoutException timeout) {
            task.cancel(true);
            return false;
        } catch (java.util.concurrent.ExecutionException failure) {
            return false;
        }
    }

    /**
     * 只接受 workspace 真正根目录下的常规文件，避免相对路径虽然通过 schema 但经符号链接越界；
     * 预期长度同时作为读入上限，防止恢复核实成为未受控的大文件读取。
     */
    private static boolean matchesExpectedFile(Path workspaceRoot,
                                               ConversationRepository.PendingToolRecovery recovery) {
        try {
            Long expectedBytes = recovery.expectedAfterBytes();
            if (expectedBytes == null || expectedBytes > MAX_RECOVERY_FILE_BYTES) return false;
            Path root = workspaceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path target = root.resolve(recovery.targetRelativePath()).normalize();
            if (!target.startsWith(root) || Files.isSymbolicLink(target)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(target) != expectedBytes) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8_192];
            long remaining = expectedBytes;
            try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) return false;
                    if (read == 0) continue;
                    digest.update(buffer, 0, read);
                    remaining -= read;
                }
                if (input.read() >= 0) return false;
            }
            return HexFormat.of().formatHex(digest.digest()).equals(recovery.expectedAfterSha256());
        } catch (IOException | SecurityException | NoSuchAlgorithmException unavailable) {
            return false;
        }
    }

    /**
     * 仅按全局 Turn ID 认领最高优先级停止意图；Thread revision 与 Turn mutation version 由 SQLite owner
     * 在同一事务中读取，避免客户端快照过期把基础取消动作错误地拒绝为冲突。
     */
    public TurnUseCase.CancelResult cancel(String turnId) {
        String parentThreadId = cancellationThreadId(turnId);
        try {
            TurnUseCase.CancelResult result = cancellationLifecycle.cancel(turnId);
            publishCancellationClaim(parentThreadId, result, result.accepted() && !result.status().terminal());
            return result;
        } catch (TurnUseCase.TurnCancellationException absent) {
            if (absent.failure() != TurnUseCase.CancelFailure.TURN_NOT_FOUND) throw absent;
            return cancelWithoutRuntimeOwner(turnId, parentThreadId, absent);
        }
    }

    /**
     * 活动 owner 短暂缺席时只对明确的状态竞态做一次权威重试；存储事务或完整性故障必须原样传播，
     * 终态回读只生成 ACK，不取得 Task 取消传播资格。
     */
    private TurnUseCase.CancelResult cancelWithoutRuntimeOwner(
            String turnId, String parentThreadId, TurnUseCase.TurnCancellationException notFound) {
        for (int attempt = 0; attempt < 2; attempt++) {
            TurnUseCase.CancelResult result;
            boolean cancellationIntentClaimed;
            String propagationThreadId;
            synchronized (admissionLifecycle) {
                ConversationRepository.TurnSnapshot current = store.findTurn(turnId).orElse(null);
                if (current == null) throw notFound;
                if (current.state().terminal()) return terminalCancelAck(turnId, current);
                propagationThreadId = parentThreadId == null ? current.threadId() : parentThreadId;
                if (current.state() == TurnState.SUSPENDED) {
                    try {
                        ConversationRepository.CancelResult cancelled = store.cancelSuspended(
                                turnId, clock.instant());
                        executionContexts.remove(new Key(current.threadId(), turnId));
                        result = new TurnUseCase.CancelResult(
                                true, turnId, TurnState.CANCELLED, cancelled.threadRevision());
                        cancellationIntentClaimed = true;
                    } catch (StorageException race) {
                        if (!isCancellationStateRace(race) || attempt == 1) throw race;
                        continue;
                    }
                } else {
                    try {
                        result = cancellationLifecycle.cancel(turnId);
                        cancellationIntentClaimed = result.accepted() && !result.status().terminal();
                    } catch (TurnUseCase.TurnCancellationException retryable) {
                        if (retryable.failure() != TurnUseCase.CancelFailure.TURN_NOT_FOUND || attempt == 1) {
                            throw retryable;
                        }
                        continue;
                    }
                }
            }
            publishCancellationClaim(propagationThreadId, result, cancellationIntentClaimed);
            return result;
        }
        throw notFound;
    }

    /** 终态竞态只回传当前权威状态，禁止把自然完成解释为新的取消 intent。 */
    private static TurnUseCase.CancelResult terminalCancelAck(
            String turnId, ConversationRepository.TurnSnapshot current) {
        return new TurnUseCase.CancelResult(true, turnId, current.state(), current.threadRevision());
    }

    /** 只有 CAS/缺失行代表可恢复状态竞态，其它 SQLite 故障必须保留原始分类与 cause。 */
    private static boolean isCancellationStateRace(StorageException failure) {
        return failure.code() == StorageException.Code.CAS_CONFLICT
                || failure.code() == StorageException.Code.NOT_FOUND;
    }

    /**
     * Plan pause 的窄入口：活动 Turn 先完成取消清理，再尝试保留 execution cursor；已有
     * SUSPENDED Turn 不重复写状态，避免把暂停误收敛为 CANCELLED。
     */
    public CompletionStage<Void> suspendPlanRun(String turnId) {
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
            cancellationLifecycle.cancel(turnId, "plan paused");
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
    private void publishCancellationClaim(String parentThreadId, TurnUseCase.CancelResult result,
                                          boolean cancellationIntentClaimed) {
        if (!cancellationIntentClaimed || parentThreadId == null) return;
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

    /** 用户选择的 Steering/Follow-up 直接在单次入队事务冻结，不经过事后提升。 */
    @Override public TurnUseCase.InputMutation enqueueInput(String turnId, UserContent content,
                                                            TurnUseCase.InputKind kind, TurnEventSink sink,
                                                            String clientOperationId, String requestFingerprint) {
        TurnUseCase.InputMutation result = mutateInput(turnId, false, authority -> {
            UserContent validated = validateQueuedContent(authority, content);
            var pending = new ConversationRepository.PendingInput(
                "input_" + UUID.randomUUID(), authority.key().threadId(), turnId,
                kind == TurnUseCase.InputKind.STEERING ? ConversationRepository.InputKind.STEERING
                        : ConversationRepository.InputKind.FOLLOW_UP, validated, clock.instant());
            return clientOperationId == null ? store.enqueueInput(pending)
                    : store.enqueueInput(pending, clientOperationId, requestFingerprint);
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

    /** 入队查询转为入站安全 DTO，操作指纹仍只用于服务端识别相同请求。 */
    @Override public Optional<TurnUseCase.InputOperationReceipt> readInputOperation(String clientOperationId) {
        return store.readInputOperation(clientOperationId).map(receipt -> new TurnUseCase.InputOperationReceipt(
            receipt.operationId(), receipt.fingerprint(), receipt.threadId(), receipt.turnId(), receipt.inputId(),
            receipt.kind() == ConversationRepository.InputKind.STEERING ? TurnUseCase.InputKind.STEERING
                : TurnUseCase.InputKind.FOLLOW_UP));
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
                    clock.instant());
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
                    key.threadId(), key.turnId(), receipt.threadRevision(), receipt.turnMutationVersion(),
                    clock.instant());
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
                authority.workspaceId(), TurnOrigin.USER,
                executionContexts.get(authority.key()))) {
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
                                                     String workspaceId,
                                                     NativeExecutionSnapshot executionContext) {
        return content -> {
            try {
                validateWorkspaceReferences(workspaceId, content);
                try (RuntimeLease runtime = openCurrentLease(threadId, null, workspaceRoot, workspaceId,
                        TurnOrigin.USER, executionContext)) {
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
        executionContexts.clear();
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
            turn.cancellation.close();
            cancellations.complete(key.threadId(), key.turnId());
            cancellationLifecycle.clearBarrier(key);
            active.remove(key, turn);
            store.findTurn(key.threadId(), key.turnId()).ifPresent(snapshot -> {
                if (snapshot.state().terminal()) {
                    resumeContinuations.remove(key.turnId());
                    executionContexts.remove(key);
                }
            });
        }
    }

    /**
     * 终态已经发布后仅非阻塞提交后台标题任务；runtime factory 留到 worker 真正发送前执行，
     * 因而排队期间的模型或 reasoning 修改会自然进入标题请求，调度故障也不能反向改写成功 Turn。
     */
    private CompletionStage<Void> scheduleAutomaticTitle(TurnOwnership turn, TurnResult result) {
        if (!turn.provisionalTitleCreated) return null;
        NativeExecutionSnapshot executionContext = executionContexts.get(
                new Key(turn.request.threadId(), turn.request.turnId()));
        try {
            return automaticTitles.schedule(new AutomaticThreadTitleScheduler.Request(
                    turn.request.threadId(), turn.request.turnId(),
                    result.terminal().context().threadRevision(), turn.request.userInput(),
                    result.terminal().finalMessage().text(), timeout -> {
                        RuntimeLease lease = openCurrentLease(turn.request.threadId(), null,
                                turn.request.workspaceRoot(), turn.request.workspaceId(),
                                TurnOrigin.USER, executionContext);
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
                receipt.threadRevision(), receipt.turnMutationVersion(), occurredAt),
                TurnState.SUSPENDED, TurnState.QUEUED);
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
        if (runtime.accessMode() != request.accessMode()
            || runtime.collaborationMode() != request.collaborationMode()) {
            throw new IllegalArgumentException("resolved runtime does not match turn start request");
        }
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
    private static TurnExecutionState.Ready initialExecution(RuntimeLease lease, TurnOrigin origin) {
        TurnExecutionState.Common common = new TurnExecutionState.Common(
                0, 0, 1, null, lease.promptSession().activeSkillReferences(), origin);
        return new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null);
    }

    /**
     * 请求安全点从 SQLite 权威 ThreadPreferences 解析最新配置，不继承旧请求截止时间。
     */
    private RuntimeLease openCurrentLease(String threadId, String turnId, java.nio.file.Path workspaceRoot,
                                          String workspaceId, TurnOrigin origin,
                                          NativeExecutionSnapshot executionContext) {
        return openCurrentLease(threadId, turnId, workspaceRoot, workspaceId, origin,
                executionContext, clock.instant());
    }

    /** 显式传入同一请求时刻，使 Tool 能力声明和执行上下文共享一个短租约截止线。 */
    private RuntimeLease openCurrentLease(String threadId, String turnId, java.nio.file.Path workspaceRoot,
                                          String workspaceId, TurnOrigin origin,
                                          NativeExecutionSnapshot executionContext, Instant requestAt) {
        ConversationRepository.ThreadSnapshot snapshot = store.readThread(threadId)
                .orElseThrow(() -> new AgentLoop.LoopFailure("INVALID_STATE", "Thread history is unavailable"));
        ThreadPreferences preferences = snapshot.preferences();
        TurnRuntimeRequest request = new TurnRuntimeRequest(threadId, turnId, workspaceRoot, workspaceId,
                preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                preferences.accessMode(), preferences.collaborationMode(), origin, requestAt,
                executionContext);
        return Objects.requireNonNull(runtimeResolver.resolve(request), "runtimeLease");
    }

    /** 创建生产请求 factory；每次调用都重新走配置 Owner，且返回值关闭即释放 Provider/MCP 凭据。 */
    private TurnExecutionPlan.RequestRuntimeFactory requestRuntimeFactory(
            String threadId, String turnId, java.nio.file.Path workspaceRoot, UserContent content,
            TurnOrigin origin, Instant requestedAt, String workspaceId,
            NativeExecutionSnapshot executionContext) {
        return (common, promptSummary) -> {
            Instant requestAt = clock.instant();
            RuntimeLease lease = openCurrentLease(threadId, turnId, workspaceRoot, workspaceId, origin,
                    executionContext, requestAt);
            boolean transferred = false;
            try {
                restoreRequestPrompt(common, promptSummary, lease);
                TurnLimits effectiveLimits = lease.limits();
                Instant requestDeadline = requestAt.plus(effectiveLimits.requestWindow());
                TurnExecutionPlan plan = new TurnExecutionPlan(threadId, turnId, workspaceRoot, content, origin,
                        lease.model(), lease.accessMode(), effectiveLimits, requestedAt, workspaceId,
                        0, 0, promptSummary, lease.promptSession(),
                        queuedInputBoundary(threadId, workspaceRoot, workspaceId, executionContext),
                        lease.attachments(), lease.tools(), lease.generationId(), lease.toolSessions(),
                        lease.outputLimits(), lease.presentationSecrets(), requestDeadline,
                        requestRuntimeFactory(threadId, turnId, workspaceRoot, content, origin, requestedAt,
                                workspaceId, executionContext),
                        TurnChangeTracker.fresh(workspaceRoot));
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
     * 回答事务提交后只排队恢复意图；旧 owner 未释放时持续退避，保留发起端的执行环境供重连后恢复。
     */
    private void scheduleInteractionResume(String threadId, String turnId, long threadRevision,
                                            TurnEventSink sink, long delayMillis) {
        scheduleInteractionResume(threadId, turnId, threadRevision, sink, delayMillis, null);
    }

    /** 将本次已确认的执行环境随恢复意图传递，计时器线程不读取自己的宿主环境。 */
    private void scheduleInteractionResume(String threadId, String turnId, long threadRevision,
                                            TurnEventSink sink, long delayMillis,
                                            NativeExecutionSnapshot inheritedContext) {
        if (!accepting.get() || shutdown.isClosed()) return;
        Key key = new Key(threadId, turnId);
        NativeExecutionSnapshot context = inheritedContext != null ? inheritedContext : executionContexts.get(key);
        if (context == null) context = NativeExecutionContext.shared().current().orElse(null);
        PendingInteractionResume pending = new PendingInteractionResume(threadRevision, sink, delayMillis, context);
        if (pendingInteractionResumes.putIfAbsent(key, pending) != null) return;
        try {
            deadlines.schedule(() -> tryInteractionResume(key, pending), delayMillis, TimeUnit.MILLISECONDS);
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
     * 只有旧 owner/FIFO 竞争值得持续重试；确定性阻塞或存储异常保留 SUSPENDED 权威事实。
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
            resume(key.turnId(), pending.threadRevision(), pending.sink(), pending.executionContext());
        } catch (TurnUseCase.TurnResumeException failure) {
            boolean olderOwnerStillActive = active.keySet().stream()
                    .anyMatch(owner -> owner.threadId().equals(key.threadId()));
            if (failure.failure() != TurnUseCase.ResumeFailure.TURN_RESUME_ORDER_CONFLICT
                    || !olderOwnerStillActive) {
                pendingInteractionResumes.remove(key, pending);
                LOGGER.warn("Interaction resume needs attention threadId={} turnId={} reason={}",
                        key.threadId(), key.turnId(), failure.failure());
                return;
            }
            retryInteractionResume(key, pending);
            return;
        } catch (RejectedExecutionException busy) {
            retryInteractionResume(key, pending);
            return;
        } catch (StorageException failure) {
            pendingInteractionResumes.remove(key, pending);
            LOGGER.warn("Interaction resume stopped after storage failure threadId={} turnId={} code={}",
                    key.threadId(), key.turnId(), failure.code());
            return;
        } catch (RuntimeException failure) {
            pendingInteractionResumes.remove(key, pending);
            LOGGER.warn("Interaction resume needs attention threadId={} turnId={} failure={}",
                    key.threadId(), key.turnId(), failure.getClass().getSimpleName());
            return;
        }
        pendingInteractionResumes.remove(key, pending);
    }

    /** 重用同一待恢复身份并把竞争等待收敛到两秒；次数不决定是否放弃已提交的回答。 */
    private void retryInteractionResume(Key key, PendingInteractionResume pending) {
        pendingInteractionResumes.remove(key, pending);
        long nextDelay = pending.delayMillis() == 0L ? 25L : Math.min(2_000L, pending.delayMillis() * 2L);
        scheduleInteractionResume(key.threadId(), key.turnId(), pending.threadRevision(), pending.sink(),
                nextDelay, pending.executionContext());
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
    private record PendingInteractionResume(long threadRevision, TurnEventSink sink, long delayMillis,
                                            NativeExecutionSnapshot executionContext) {
        /** 等待时间是调度间隔而非尝试预算；执行环境可缺失以便明确暴露重绑定阻塞。 */
        private PendingInteractionResume {
            if (threadRevision < 0 || delayMillis < 0 || delayMillis > 2_000 || sink == null) {
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
                                long expectedThreadRevision,
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
                    request.reasoningLevel(), request.accessMode(), request.collaborationMode(),
                    request.expectedThreadRevision(), request.initialTurnMutationVersion(), request.requestedAt(), origin);
        }

        /** 内部 DTO 没有 content 字段，映射时保持强类型来源。 */
        private static StartCommand internal(InternalTurnStartRequest request) {
            Objects.requireNonNull(request, "request");
            return new StartCommand(request.threadId(), request.turnId(), request.workspaceId(),
                    request.workspaceRoot(), null, request.providerId(), request.modelId(), request.reasoningLevel(),
                    request.accessMode(), request.collaborationMode(),
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
