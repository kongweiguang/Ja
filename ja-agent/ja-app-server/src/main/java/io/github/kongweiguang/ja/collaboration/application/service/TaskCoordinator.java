// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.service;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadMetadataEvent;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjectionFailure;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.api.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnCancellationListener;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.SubagentPolicyRepository;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.api.TaskEvent;
import io.github.kongweiguang.ja.collaboration.application.api.TaskEventSink;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxPort;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxQueueCommandMapper;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationBoundary;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskAdmissionGateway;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskAdmissionRegistration;
import io.github.kongweiguang.ja.collaboration.domain.model.ThreadDefinition;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Child Thread 的唯一应用层协调器；持久化、Turn 调度与 UI 观察通过窄端口组合而不建立第二运行时。
 */
public final class TaskCoordinator implements TaskUseCase, TurnCancellationListener,
        NativeExecutionContext.NativeClientProcessExitListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskCoordinator.class);
    private static final int MAX_TREE_TASKS = 64;
    private static final Duration SIDE_CHAT_CLOSE_TIMEOUT = Duration.ofSeconds(10);
    private static final long SIDE_CHAT_CLOSE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long MAX_PROCESS_EXIT_RETRY_DELAY_MILLIS = 30_000L;
    private static final SideChatOwnerController NO_SIDE_CHAT_OWNER_CONTROLLER = (ignored, deadlineNanos) -> {
        throw new IllegalStateException("side chat execution owner is not bound");
    };
    private final TaskRepository tasks;
    private final TaskMailboxPort mailboxes;
    private final ThreadUseCase threads;
    private final TurnUseCase turns;
    private final ChildTurnScheduler scheduler;
    private final WorkspaceUseCase workspaces;
    private final Clock clock;
    private final SubagentPolicyRepository subagentPolicies;
    private final TaskAdmissionGateway admissions;
    private final ConversationBoundary conversation;
    private final JsonValueCodec jsonValues;
    private final AtomicReference<TaskEventSink> subscriber = new AtomicReference<>();
    private final Map<String, ObservationState> observations = new ConcurrentHashMap<>();
    private final Map<String, Waiter> waiters = new ConcurrentHashMap<>();
    private final Object waiterLifecycleLock = new Object();
    private final Map<String, AtomicInteger> acceptedTaskTurns = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TaskModels.SideChatCloseReceipt>> sideChatClosures =
            new ConcurrentHashMap<>();
    private final Map<NativeClientOwner, NativeClientWork> nativeClientWork = new ConcurrentHashMap<>();
    private final Map<String, NativeClientWork> nativeSideChatOwners = new ConcurrentHashMap<>();
    private final Set<String> closingSideChats = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final AtomicBoolean sideChatShutdownAttempted = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final AtomicReference<SideChatOwnerController> sideChatOwnerController =
            new AtomicReference<>(NO_SIDE_CHAT_OWNER_CONTROLLER);
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ja-task-wait-timeout-", 0).factory());
    private final ScheduledExecutorService nativeClientCleanup = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ja-native-client-cleanup-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile RuntimeException sideChatShutdownFailure;
    /**
     * Task 与 Thread 共用持久 Mailbox owner；来源封套需要显式 JSON codec 才能被模型读取。
     */
    public TaskCoordinator(TaskRepository tasks, TaskMailboxPort mailboxes,
                           ThreadUseCase threads, TurnUseCase turns,
                           ChildTurnScheduler scheduler, WorkspaceUseCase workspaces, Clock clock,
                           SubagentPolicyRepository subagentPolicies, TaskAdmissionGateway admissions,
                           JsonValueCodec jsonValues, ConversationBoundary conversation) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.mailboxes = Objects.requireNonNull(mailboxes, "mailboxes");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (turns != scheduler) {
            throw new IllegalArgumentException("Task scheduler must share Turn owner");
        }
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.subagentPolicies = Objects.requireNonNull(subagentPolicies, "subagentPolicies");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.jsonValues = Objects.requireNonNull(jsonValues, "jsonValues");
        NativeExecutionContext.shared().registerNativeClientProcessExitListener(this);
    }

    /**
     * 用户侧边任务可独立选择完整执行偏好；titleSource 仍由服务端固定为人工来源。
     */
    private static ThreadPreferences sideTaskPreferences(ThreadPreferences parent,
                                                         TaskUseCase.CreatePreferences override) {
        Objects.requireNonNull(parent, "parent preferences");
        if (override == null) {
            return parent.withTitleSource(ThreadPreferences.TitleSource.MANUAL);
        }
        return new ThreadPreferences(override.providerId(), override.modelId(), override.reasoningLevel(),
                override.accessMode(), override.collaborationMode(), ThreadPreferences.TitleSource.MANUAL);
    }

    /**
     * Side Task 将用户本次选择记录进冻结 seed，供后续 Turn 解析其创建时执行偏好。
     */
    private static JsonObject accessCeiling(AccessMode accessMode) {
        return JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", accessMode == AccessMode.APPROVAL_REQUIRED
                        ? "approval_required" : "full_access").build();
    }

    /**
     * 并发关闭共享已提交结果，并遵守当前调用的绝对截止线，避免关机等待越过全局预算。
     * 运行时根因原样抛出，checked cause 保留为 wrapper cause，避免把 owner 失败改报为关闭成功。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static TaskModels.SideChatCloseReceipt awaitClose(
            CompletableFuture<TaskModels.SideChatCloseReceipt> completion, long deadlineNanos) {
        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw closeTimeout();
            }
            return completion.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "side chat close was interrupted", failure);
        } catch (java.util.concurrent.TimeoutException failure) {
            throw closeTimeout(failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("side chat close failed", cause == null ? failure : cause);
        }
    }

    /**
     * 单调时间只用于关闭预算，不受 Clock.fixed 测试时钟或系统校时影响。
     */
    private static boolean expired(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    /**
     * 超时保留持久 closing 闸门，调用者可安全重试而不会再次接纳输入。
     */
    private static TaskRepositoryException closeTimeout() {
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                "side chat turns did not settle before close deadline");
    }

    /**
     * 超时分类保持稳定，同时让诊断能够确认真实等待失败。
     */
    private static TaskRepositoryException closeTimeout(Throwable cause) {
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                "side chat turns did not settle before close deadline", cause);
    }

    /**
     * 普通补充信息与审批都需要用户介入，和空闲/终态一样立即交还控制权。
     */
    private static boolean ready(TaskModels.Summary task) {
        return switch (task.projection().state()) {
            case IDLE, WAITING_APPROVAL, WAITING_INPUT, SUSPENDED, COMPLETED, FAILED, CANCELLED -> true;
            case QUEUED, RUNNING -> false;
        };
    }

    /**
     * Follow-up 继承当前配置；只有创建后尚无 Turn 的 SIDE_TASK 首轮携带冻结 seed。
     */
    private static TurnStartRequest request(ThreadSummary child, Workspace workspace, String turnId,
                                            UserContent content, Instant now,
                                            Optional<InitialContextSeed> initialContextSeed) {
        ThreadPreferences preferences = Objects.requireNonNull(child.preferences(), "child preferences");
        return new TurnStartRequest(child.threadId(), turnId, child.workspaceId(), workspace.root(), content,
                preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                preferences.accessMode(), preferences.collaborationMode(), child.revision(), 0, now,
                Objects.requireNonNull(initialContextSeed, "initialContextSeed"), java.util.Optional.empty(), java.util.Optional.empty(), false);
    }

    /**
     * Seed 只保存资源身份与类型；真实 workspace 路径仍由 Turn 入口授权，不复制 Timeline 或附件正文。
     */
    private static JsonArray references(UserContent content) {
        List<JsonValue> values = new ArrayList<>();
        for (UserContentBlock block : content.blocks()) {
            if (block instanceof ResourceReferenceContent(String sourceId, String resourceId, String kind)) {
                values.add(JsonObjects.builder().putText("kind", kind)
                        .putText("sourceId", sourceId)
                        .putText("resourceId", resourceId).build());
            } else if (block instanceof SkillReferenceContent(String skillId)) {
                values.add(JsonObjects.builder().putText("kind", "skill")
                        .putText("skillId", skillId).build());
            } else if (block instanceof AttachmentContent(String attachmentId)) {
                values.add(JsonObjects.builder().putText("kind", "attachment")
                        .putText("attachmentId", attachmentId).build());
            }
        }
        return new JsonArray(values);
    }

    /**
     * Mailbox envelope 的因果 Turn 由调用者显式提供，不从 active registry 猜测。
     */
    private static TaskModels.MailboxEnvelope envelope(MessageCommand command, TaskModels.MailboxKind kind,
                                                       Instant now) {
        return new TaskModels.MailboxEnvelope(id("msg_"), command.senderThreadId(), command.targetThreadId(),
                command.causalTurnId(), kind, command.content(), command.operationKey(), now);
    }

    /**
     * Activity summary 只保存安全短文本，原 prompt 与思考内容不会进入父 Timeline。
     */
    private static JsonObject summary(String text) {
        return JsonObjects.builder().putText("text", text).build();
    }

    /**
     * 领域事件上下文直接取 Task 投影。
     */
    private static TaskEvent.Context context(TaskModels.Summary task, Instant occurredAt) {
        return new TaskEvent.Context(task.lineage().rootThreadId(), task.lineage().taskThreadId(),
                task.projection().revision(), occurredAt);
    }

    /**
     * History 的状态投影使用小写 wire 拼写；在领域边界显式归一后再使用终态闭集。
     */
    private static boolean terminal(String status) {
        return TurnState.valueOf(status.toUpperCase(java.util.Locale.ROOT)).terminal();
    }

    /**
     * Task 投影终态判断与持久状态闭集保持显式，不把需处理状态误判为完成。
     */
    private static boolean terminal(TaskModels.State state) {
        return state == TaskModels.State.COMPLETED || state == TaskModels.State.FAILED
               || state == TaskModels.State.CANCELLED;
    }

    /**
     * UUID 仅作为不透明身份熵，不携带时间、路径或用户输入。
     */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 所有 CAS 冲突使用稳定 Task 分类。
     */
    private static TaskRepositoryException conflict() {
        return new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                "task revision changed");
    }

    /**
     * 绑定停止 Thread 所属 Plan 执行的协调器；采用一次性 late binding 打破 Task 与 Plan 执行的组合环，
     * 同时让单元测试可以继续使用不绑定该协调器的窄构造器。
     */
    public void bindSideChatOwnerController(SideChatOwnerController controller) {
        Objects.requireNonNull(controller, "controller");
        if (!sideChatOwnerController.compareAndSet(NO_SIDE_CHAT_OWNER_CONTROLLER, controller)) {
            throw new IllegalStateException("side chat owner controller is already bound");
        }
    }

    /**
     * 供 Plan repository 在 admission 前检查临时生命周期闸门；关闭失败时保留该闸门，
     * 使重试不会重新接纳一个已经取消的侧聊。
     */
    public boolean isSideChatClosing(String taskThreadId) {
        Objects.requireNonNull(taskThreadId, "taskThreadId");
        return closingSideChats.contains(taskThreadId);
    }

    /**
     * 创建时先冻结 parent revision 的真实有效上下文，admission 事务会再次校验该 revision。
     * admission 提交后通知失败不能改写已落库的创建结果，否则客户端会把通知故障误判为创建失败并重试。
     */
    @Override
    public TaskModels.Summary createSideTask(CreateCommand command) {
        return createSideTask(command, Optional.empty());
    }

    /**
     * owner 在持久准入前预留生成的 Thread ID；即使事务提交后 ACK/read 失败，关闭仍能精确对账该 root。
     */
    @Override
    public TaskModels.Summary createSideTask(CreateCommand command, Optional<NativeClientOwner> ownerScope) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(ownerScope, "ownerScope");
        NativeClientWork clientOwner = ownerScope.map(this::requireCreatableNativeClient).orElse(null);
        if (clientOwner != null) {
            requireCompatibleParentOwner(command.parentThreadId(), clientOwner);
        }
        ThreadSummary parent = requireParent(command.parentThreadId(), command.expectedParentRevision());
        TaskModels.EffectiveContextSnapshot context = tasks.freezeEffectiveContext(
                command.parentThreadId(), command.parentTurnId(), command.expectedParentRevision());
        ThreadPreferences preferences = sideTaskPreferences(parent.preferences(), command.preferences());
        Instant now = clock.instant();
        String childThreadId = id("thr_task_");
        TaskModels.ContextSeedDraft seed = new TaskModels.ContextSeedDraft(id("seed_"),
                command.parentThreadId(), command.parentTurnId(), parent.revision(),
                TaskModels.InheritanceMode.EFFECTIVE_CONTEXT, null, context.initialContextSeed(),
                context.references(), accessCeiling(preferences.accessMode()), now);
        ThreadDefinition childThread = new ThreadDefinition(
                childThreadId, parent.workspaceId(), command.taskName(),
                conversation.fromConversation(preferences.withTitleSource(ThreadPreferences.TitleSource.MANUAL)), now);
        TaskModels.ChildAdmission admission = new TaskModels.ChildAdmission(childThread,
                command.parentThreadId(), parent.revision(), command.parentTurnId(), command.taskName(),
                TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT, seed, id("activity_"),
                summary("已创建 " + command.taskName()));
        if (clientOwner != null) {
            clientOwner.reserveCreate(childThreadId);
            nativeSideChatOwners.put(childThreadId, clientOwner);
        }
        TaskModels.Summary created;
        try {
            created = tasks.admitIdleChild(admission);
            if (clientOwner != null) {
                clientOwner.commitCreate(childThreadId);
            }
        } catch (RuntimeException failure) {
            if (clientOwner != null) {
                reconcileFailedCreate(clientOwner, childThreadId, failure);
            }
            throw failure;
        } finally {
            if (clientOwner != null) {
                clientOwner.finishCreate();
            }
        }
        try {
            publishLatest(created);
        } catch (RuntimeException failure) {
            LOGGER.warn("event=task_activity_publish_failed task_thread_id={}",
                    created.lineage().taskThreadId(), failure);
        }
        return created;
    }

    /**
     * 有活跃临时资源的存活 Host 阻止无连接空闲关闭；断开的 WebSocket 不改变进程 owner 生命周期。
     * @author kongweiguang
     */
    @Override
    public boolean hasLiveNativeClientWork() {
        return nativeClientWork.values().stream()
                .anyMatch(owner -> owner.hasWork() &&
                        NativeExecutionContext.shared().nativeClientProcessAlive(owner.owner()));
    }

    /**
     * 同一绝对 deadline 覆盖等待准入和所有 root close；失败保留 Closing 与 reservation 允许安全重试。
     * @author kongweiguang
     */
    @Override
    public void closeNativeClient(NativeClientOwner owner, long deadlineNanos) {
        if (deadlineNanos == Long.MIN_VALUE) {
            throw new IllegalArgumentException("invalid native client close deadline");
        }
        NativeClientOwner registered = requireRegisteredNativeClient(owner, false);
        NativeClientWork clientOwner = nativeClientWork.computeIfAbsent(registered, NativeClientWork::new);
        NativeExecutionContext.shared().beginNativeClientClose(registered);
        NativeClientWork.CloseAttempt attempt = clientOwner.beginClose();
        if (attempt == null) {
            return;
        }
        if (!attempt.leader()) {
            awaitNativeClientClose(attempt.completion(), deadlineNanos);
            return;
        }
        try {
            clientOwner.awaitNoCreates(deadlineNanos);
            if (!clientOwner.hasWork()) {
                clientOwner.completeClose();
                nativeClientWork.remove(registered, clientOwner);
                NativeExecutionContext.shared().completeNativeClientClose(registered);
                attempt.completion().complete(null);
                return;
            }
            for (Map.Entry<String, NativeRootState> root : clientOwner.rootsSnapshot()) {
                String rootThreadId = root.getKey();
                if (!clientOwner.containsRoot(rootThreadId)) {
                    continue;
                }
                if (root.getValue() != NativeRootState.COMMITTED) {
                    Optional<TaskModels.Summary> persisted = tasks.findTask(rootThreadId);
                    if (persisted.isEmpty()) {
                        removeNativeSideChatRoots(clientOwner, Set.of(rootThreadId));
                        continue;
                    }
                    if (persisted.orElseThrow().lineage().kind() != TaskModels.Kind.SIDE_TASK) {
                        throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                                "native client reservation resolved to a non-side-chat task");
                    }
                    clientOwner.commitCreate(rootThreadId);
                }
                TaskModels.SideChatCloseReceipt receipt = runSideChatClose(rootThreadId, deadlineNanos);
                if (!receipt.closedThreadIds().contains(rootThreadId)) {
                    throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                            "native client close omitted its root");
                }
                removeNativeSideChatRoots(clientOwner, Set.copyOf(receipt.closedThreadIds()));
            }
            clientOwner.completeClose();
            nativeClientWork.remove(registered, clientOwner);
            NativeExecutionContext.shared().completeNativeClientClose(registered);
            attempt.completion().complete(null);
        } catch (RuntimeException failure) {
            attempt.completion().completeExceptionally(failure);
            throw failure;
        } finally {
            clientOwner.finishClose(attempt.completion());
        }
    }

    /**
     * Host 进程退出后复用相同 Task 关闭链；失败只按封顶退避重试，不丢失 owner roots 或伪造成功。
     * @author kongweiguang
     */
    @Override
    public void processExited(List<NativeClientOwner> owners) {
        List<NativeClientOwner> snapshot = List.copyOf(Objects.requireNonNull(owners, "owners"));
        if (!snapshot.isEmpty()) {
            scheduleProcessExitCleanup(snapshot, 250L);
        }
    }

    /**
     * 为每个进程组只安排一个后续批次，关闭共享单线程中等待数据库的时间不超过既有 close deadline。
     * @author kongweiguang
     */
    private void scheduleProcessExitCleanup(List<NativeClientOwner> owners, long delayMillis) {
        if (nativeClientCleanup.isShutdown()) {
            return;
        }
        try {
            nativeClientCleanup.schedule(() -> closeProcessExitBatch(owners, delayMillis),
                    delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            LOGGER.warn("event=native_client_exit_cleanup_rejected owner_count={}", owners.size());
        }
    }

    /**
     * 同一进程退出批次逐个运行原有 root close；部分失败不会阻止同组其它 Native Host 收口。
     * @author kongweiguang
     */
    private void closeProcessExitBatch(List<NativeClientOwner> owners, long previousDelayMillis) {
        boolean failed = false;
        for (NativeClientOwner owner : owners) {
            try {
                closeNativeClient(owner, System.nanoTime() + SIDE_CHAT_CLOSE_TIMEOUT.toNanos());
            } catch (RuntimeException failure) {
                failed = true;
                LOGGER.warn("event=native_client_exit_cleanup_failed cause_type={}",
                        failure.getClass().getSimpleName());
            }
        }
        if (failed) {
            scheduleProcessExitCleanup(owners, Math.min(MAX_PROCESS_EXIT_RETRY_DELAY_MILLIS,
                    Math.max(250L, previousDelayMillis * 2L)));
        }
    }

    /**
     * 只以对象句柄而非 clientLifetimeId 建立 Task 归属；Context registry 是身份与进程代际的唯一事实源。
     * @author kongweiguang
     */
    private NativeClientOwner requireRegisteredNativeClient(NativeClientOwner owner, boolean requireOpenOwner) {
        NativeClientOwner value = Objects.requireNonNull(owner, "native client owner");
        NativeExecutionContext context = NativeExecutionContext.shared();
        if (!context.isRegisteredNativeClient(value)
                || (requireOpenOwner && !context.nativeClientAcceptsRequests(value))) {
            throw nativeClientPermissionDenied();
        }
        return value;
    }

    /**
     * Create 的 reservation 是先于持久准入写入内存 root ledger，避免提交后读取或 ACK 失败遗失清理身份。
     * @author kongweiguang
     */
    private NativeClientWork requireCreatableNativeClient(NativeClientOwner owner) {
        NativeClientOwner registered = requireRegisteredNativeClient(owner, true);
        NativeClientWork work = nativeClientWork.computeIfAbsent(registered, NativeClientWork::new);
        if (!work.acceptsCreates()) {
            throw nativeClientPermissionDenied();
        }
        return work;
    }

    /**
     * 显式 task/close 只能针对当前 Context owner 已登记的临时会话，普通 Thread 不经过此收口入口。
     * @author kongweiguang
     */
    private NativeClientWork requireTaskNativeClient(NativeClientOwner owner) {
        NativeClientOwner registered = requireRegisteredNativeClient(owner, true);
        NativeClientWork work = nativeClientWork.get(registered);
        if (work == null) {
            throw nativeClientPermissionDenied();
        }
        return work;
    }

    /**
     * 沿持久父链核验每个临时祖先，并证明链尾是真实 Thread；foreign、缺失、循环或超限链一律拒绝。
     * @author kongweiguang
     */
    private void requireCompatibleParentOwner(String parentThreadId, NativeClientWork requestedOwner) {
        String ancestorThreadId = parentThreadId;
        Set<String> visited = new LinkedHashSet<>();
        for (int depth = 0; ancestorThreadId != null && depth < MAX_TREE_TASKS; depth++) {
            if (!visited.add(ancestorThreadId)) {
                throw nativeClientPermissionDenied();
            }
            Optional<TaskModels.RuntimeIdentity> identity = readRuntimeIdentity(ancestorThreadId);
            if (identity.isEmpty()) {
                ThreadSnapshot ordinaryAncestor = requireThread(ancestorThreadId);
                if (!ordinaryAncestor.thread().threadId().equals(ancestorThreadId)) {
                    throw nativeClientPermissionDenied();
                }
                return;
            }
            TaskModels.RuntimeIdentity lineage = identity.orElseThrow();
            if (lineage.kind() == TaskModels.Kind.SIDE_TASK) {
                NativeClientWork ancestorOwner = nativeSideChatOwners.get(lineage.taskThreadId());
                if (ancestorOwner == null || ancestorOwner != requestedOwner) {
                    throw nativeClientPermissionDenied();
                }
            }
            if (lineage.taskThreadId().equals(lineage.rootThreadId())) {
                return;
            }
            ancestorThreadId = lineage.parentThreadId();
        }
        if (ancestorThreadId != null) {
            throw nativeClientPermissionDenied();
        }
    }

    /**
     * 准入异常后精确读取生成 ID：已提交则保留为 root，确定不存在才释放；读取故障保留 reservation。
     * @author kongweiguang
     */
    private void reconcileFailedCreate(NativeClientWork owner, String childThreadId, RuntimeException failure) {
        try {
            Optional<TaskModels.Summary> persisted = tasks.findTask(childThreadId);
            if (persisted.isEmpty()) {
                removeNativeSideChatRoots(owner, Set.of(childThreadId));
            } else if (persisted.orElseThrow().lineage().kind() == TaskModels.Kind.SIDE_TASK) {
                owner.commitCreate(childThreadId);
            } else {
                throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "native client reservation resolved to a non-side-chat task");
            }
        } catch (RuntimeException reconciliationFailure) {
            if (reconciliationFailure != failure) {
                failure.addSuppressed(reconciliationFailure);
            }
        }
    }

    /**
     * 只有确定的持久关闭回执才能移除 owner root 与嵌套归属索引；未知关闭结果保留两者供重试。
     * @author kongweiguang
     */
    private void removeNativeSideChatRoots(NativeClientWork owner, Set<String> closedThreadIds) {
        owner.removeRoots(closedThreadIds);
        closedThreadIds.forEach(threadId -> nativeSideChatOwners.remove(threadId, owner));
    }

    /**
     * 任务树普通删除没有 stop/cancel/barrier 语义；只要子树含临时 Side Task 就必须先走专用关闭。
     * @author kongweiguang
     */
    private boolean treeContainsTemporarySideChat(String rootThreadId) {
        return tasks.listTree(rootThreadId).stream()
                .anyMatch(task -> task.lineage().kind() == TaskModels.Kind.SIDE_TASK);
    }

    /**
     * Task 关闭失败保留稳定的权限拒绝分类，外层 RPC 不会误把错误 owner 映射为 Not Found。
     * @author kongweiguang
     */
    private static TaskRepositoryException nativeClientPermissionDenied() {
        return new TaskRepositoryException(TaskRepositoryException.Code.PERMISSION_DENIED,
                "native client does not own this temporary task");
    }

    /**
     * 并发关闭者只等待当前 owner 的单飞回执，并与首个调用者共享同一绝对 deadline。
     * @author kongweiguang
     */
    private static void awaitNativeClientClose(CompletableFuture<Void> completion, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "native client close deadline expired");
        }
        try {
            completion.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "native client close was interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw nativeClientCloseFailure(failed);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "native client close deadline expired", timeout);
        }
    }

    /**
     * 保留单飞 Future 中业务 RuntimeException 的对象与错误码；非 Runtime 失败保留完整 ExecutionException 链。
     * @author kongweiguang
     */
    private static RuntimeException nativeClientCloseFailure(java.util.concurrent.ExecutionException failed) {
        Throwable cause = failed.getCause();
        if (cause instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                "native client close failed", failed);
    }

    /**
     * 单 owner 的 Task 侧仅保存 root/reservation 与 close single-flight；PID、token 和生命周期仍属于 Context。
     * @author kongweiguang
     */
    private static final class NativeClientWork {
        private final NativeClientOwner owner;
        private final Object lock = new Object();
        private final LinkedHashMap<String, NativeRootState> roots = new LinkedHashMap<>();
        private int inFlightCreates;
        private boolean closing;
        private boolean closed;
        private CompletableFuture<Void> closeCompletion;

        /**
         * owner 引用即是单一生命周期注册表中的身份句柄，避免在业务 ledger 复制 token。
         * @author kongweiguang
         */
        private NativeClientWork(NativeClientOwner owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        /**
         * 返回用于绑定 root 的不透明对象身份，不把 PID/token 暴露到任务侧。
         * @author kongweiguang
         */
        private NativeClientOwner owner() { return owner; }

        /**
         * close 闸门与新建 reservation 共用锁，防止检查后竞态越过关闭。
         * @author kongweiguang
         */
        private boolean acceptsCreates() {
            synchronized (lock) {
                return !closing && !closed;
            }
        }

        /**
         * 将生成 ID 先登记为不确定 reservation，使提交后任何读取/ACK 故障都可被精确清理。
         * @author kongweiguang
         */
        private void reserveCreate(String taskThreadId) {
            synchronized (lock) {
                if (closing || closed) {
                    throw nativeClientPermissionDenied();
                }
                if (roots.putIfAbsent(taskThreadId, NativeRootState.RESERVED) != null) {
                    throw new IllegalStateException("native task identity is already reserved");
                }
                inFlightCreates++;
            }
        }

        /**
         * 持久准入成功后只升级该 reservation，不扩大 owner 的任务归属范围。
         * @author kongweiguang
         */
        private void commitCreate(String taskThreadId) {
            synchronized (lock) {
                if (roots.containsKey(taskThreadId)) {
                    roots.put(taskThreadId, NativeRootState.COMMITTED);
                }
            }
        }

        /**
         * 准入事务结束时释放关闭屏障，并唤醒共享绝对 deadline 的 close waiter。
         * @author kongweiguang
         */
        private void finishCreate() {
            synchronized (lock) {
                if (inFlightCreates <= 0) {
                    throw new IllegalStateException("native task create lease is not active");
                }
                inFlightCreates--;
                lock.notifyAll();
            }
        }

        /**
         * close 与显式 task/close 只查询当前 owner 的本地根集合。
         * @author kongweiguang
         */
        private boolean containsRoot(String taskThreadId) {
            synchronized (lock) {
                return roots.containsKey(taskThreadId);
            }
        }

        /**
         * idle hold 只由真实 root/reservation 驱动，不因空逻辑 Host 阻止后台回收。
         * @author kongweiguang
         */
        private boolean hasWork() {
            synchronized (lock) {
                return inFlightCreates > 0 || !roots.isEmpty();
            }
        }

        /**
         * 返回稳定快照供关闭链遍历，避免迭代期间持有 owner 锁执行持久 IO。
         * @author kongweiguang
         */
        private List<Map.Entry<String, NativeRootState>> rootsSnapshot() {
            synchronized (lock) {
                return roots.entrySet().stream()
                        .map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
            }
        }

        /**
         * 仅按持久关闭回执移除 root；未知结果必须留在 ledger 以支持显式重试。
         * @author kongweiguang
         */
        private void removeRoots(Set<String> taskThreadIds) {
            synchronized (lock) {
                taskThreadIds.forEach(roots::remove);
            }
        }

        /**
         * 每个 owner 共享一个 close leader；失败允许下一次请求重试同一 Closing 代际。
         * @author kongweiguang
         */
        private CloseAttempt beginClose() {
            synchronized (lock) {
                if (closed) {
                    return null;
                }
                if (closeCompletion != null) {
                    return new CloseAttempt(false, closeCompletion);
                }
                closing = true;
                closeCompletion = new CompletableFuture<>();
                return new CloseAttempt(true, closeCompletion);
            }
        }

        /**
         * 关闭等所有准入离开临界区，且等待时不持有 monitor 外的数据库资源。
         * @author kongweiguang
         */
        private void awaitNoCreates(long deadlineNanos) {
            synchronized (lock) {
                while (inFlightCreates > 0) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                                "native client close deadline expired");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                                "native client close was interrupted", interrupted);
                    }
                }
            }
        }

        /**
         * 只在全部根收到持久关闭回执后清空 ledger 并标记本业务句柄关闭。
         * @author kongweiguang
         */
        private void completeClose() {
            synchronized (lock) {
                closing = true;
                closed = true;
                roots.clear();
            }
        }

        /**
         * leader 结束后解除 single-flight，异常时保留 Closing 和剩余根供下一次重试。
         * @author kongweiguang
         */
        private void finishClose(CompletableFuture<Void> completion) {
            synchronized (lock) {
                if (closeCompletion == completion) {
                    closeCompletion = null;
                }
            }
        }

        /**
         * 关闭只允许一个 leader 执行持久清理，其余调用共享同一完成结果。
         * @param leader 是否由本调用执行清理，确保同一 owner 不并发重复关闭
         * @param completion 当前关闭尝试的共享回执，失败后允许后续请求重试
         * @author kongweiguang
        */
        private record CloseAttempt(boolean leader,
                                    CompletableFuture<Void> completion) { }
    }

    /** 记录 Native owner 的临时根是否已由持久化任务确认，关闭时据此恢复提交结果。 */
    private enum NativeRootState {
        /** 创建已预留但尚未确认持久化，关闭时必须核实仓储结果。 */
        RESERVED,
        /** 创建已经持久化确认，关闭时可直接进入既有关闭链。 */
        COMMITTED
    }

    /**
     * Subagent 只保存 brief 与显式引用，运行偏好取创建它的父 Turn 冻结值而不是父 Thread 后续值。
     */
    @Override
    public StartResult spawnAgent(SpawnCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        SubagentPolicy parentPolicy = requireSubagentsEnabled(command.parentThreadId());
        ThreadSummary parent = requireParent(command.parentThreadId(), null);
        parent = new ThreadSummary(parent.threadId(), parent.workspaceId(), parent.title(),
                parent.workspaceKind(), command.frozenPreferences(),
                parent.status(), parent.pinned(), parent.latestTurnStatus(),
                parent.latestTurnSeen(), parent.revision(), parent.createdAt(),
                parent.updatedAt(), parent.latestTurnId());
        return startNew(command.parentThreadId(), command.parentTurnId(), command.taskName(), command.brief(),
                parent, TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                TaskModels.InheritanceMode.BRIEF_ONLY, null, references(command.brief()),
                command.capabilityCeiling(), parentPolicy);
    }

    /**
     * Java Task 入口再次校验冻结策略，防止隐藏调用绕过 Tool 列表直接创建 Subagent。
     */
    private SubagentPolicy requireSubagentsEnabled(String threadId) {
        SubagentPolicy policy = subagentPolicies.find(threadId).orElseThrow(() ->
                new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "subagent policy is unavailable"));
        if (!policy.enabled()) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.PERMISSION_DENIED,
                    "subagents are disabled for this Thread");
        }
        return policy;
    }

    /**
     * 先用父 Task 的不可变 lineage 解析树根，仅从已取得的父 Thread 快照补齐名称，避免准入后再猜身份。
     */
    private TaskModels.RuntimeIdentity runtimeIdentityForChild(String childThreadId, String parentThreadId,
                                                                ThreadSummary parent, String taskName,
                                                                TaskModels.Kind kind) {
        Optional<TaskModels.RuntimeIdentity> parentIdentity = readRuntimeIdentity(parentThreadId);
        String rootThreadId = parentIdentity.map(TaskModels.RuntimeIdentity::rootThreadId)
                .orElse(parentThreadId);
        String rootTaskName = parentIdentity.map(TaskModels.RuntimeIdentity::rootTaskName)
                .orElse(parent.title());
        return new TaskModels.RuntimeIdentity(childThreadId, parentThreadId, rootThreadId,
                taskName, parent.title(), rootTaskName, kind);
    }

    /**
     * Child 创建严格遵循 reserve → 单事务 admission → submit，完成回调只读取已提交终态。
     */
    private StartResult startNew(String parentThreadId, String parentTurnId, String taskName, UserContent content,
                                 ThreadSummary parent, TaskModels.Kind kind,
                                 TaskModels.Lifecycle lifecycle, TaskModels.InheritanceMode inheritance,
                                 InitialContextSeed initialContextSeed,
                                 JsonArray references, JsonObject permissionCeiling,
                                 SubagentPolicy parentPolicy) {
        if (tasks.listTree(rootThread(parentThreadId)).size() >= MAX_TREE_TASKS) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.TREE_LIMIT, "task tree is full");
        }
        Instant now = clock.instant();
        String childThreadId = id("thr_task_");
        String turnId = id("turn_task_");
        String seedId = id("seed_");
        String activityId = id("activity_");
        ThreadPreferences preferences = parent.preferences().withTitleSource(ThreadPreferences.TitleSource.MANUAL);
        Workspace workspace = workspaces.requireOpenWorkspace(parent.workspaceId());
        ThreadDefinition childThread = new ThreadDefinition(childThreadId, parent.workspaceId(), taskName,
                conversation.fromConversation(preferences), now);
        TaskModels.ContextSeedDraft seed = new TaskModels.ContextSeedDraft(seedId, parentThreadId, parentTurnId,
                parent.revision(), inheritance, content, initialContextSeed, references, permissionCeiling, now);
        TaskModels.ChildAdmission child = new TaskModels.ChildAdmission(childThread, parentThreadId,
                parent.revision(), parentTurnId, taskName, kind, lifecycle, seed, activityId,
                summary("已派发 " + taskName));
        TaskModels.RuntimeIdentity runtimeIdentity = runtimeIdentityForChild(
                childThreadId, parentThreadId, parent, taskName, kind);
        TurnStartRequest request = new TurnStartRequest(childThreadId, turnId, parent.workspaceId(), workspace.root(),
                content, preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                preferences.accessMode(), preferences.collaborationMode(), 0, 0, now,
                Optional.ofNullable(initialContextSeed), java.util.Optional.empty(), java.util.Optional.empty(), false);
        TurnEventBinding events = prepareTurn(request);
        TurnUseCase.Accepted accepted;
        boolean admitted = false;
        try {
            TaskAdmissionRegistration registration = admissions.registerChild(
                    child, request, runtimeIdentity, parentPolicy);
            try (registration) {
                accepted = scheduler.startChild(request, events.turnEvents(), parentThreadId, parentTurnId);
            }
            admitted = true;
        } finally {
            if (!admitted) {
                events.abandon();
            }
        }
        if (!accepted.turnId().equals(turnId)) {
            events.abandon();
        }
        TaskModels.Summary task = requireTask(childThreadId);
        publishLatest(task);
        observeCompletion(task, accepted);
        return new StartResult(task, turnId);
    }

    /**
     * 根 Thread 自身没有 lineage；Child 的 root 由现有投影读取，避免按 ID 或深度猜测。
     */
    private String rootThread(String threadId) {
        return tasks.findTask(threadId).map(value -> value.lineage().rootThreadId()).orElse(threadId);
    }

    /**
     * 完成阶段在终态事务与 Turn 通知之后更新活动数、唤醒 waiter，并发布最新持久 Activity。
     */
    private void observeCompletion(TaskModels.Summary initial, TurnUseCase.Accepted accepted) {
        if (!accepted.queued()) {
            return;
        }
        acceptedTaskTurns.compute(initial.lineage().taskThreadId(), (ignored, count) -> {
            AtomicInteger result = count == null ? new AtomicInteger() : count;
            result.incrementAndGet();
            return result;
        });
        logActiveTaskCount();
        accepted.completion().whenComplete((ignored, failure) -> {
            if (closed.get()) {
                return;
            }
            acceptedTaskTurns.computeIfPresent(initial.lineage().taskThreadId(), (key, count) ->
                    count.decrementAndGet() == 0 ? null : count);
            logActiveTaskCount();
            tasks.findTask(initial.lineage().taskThreadId()).ifPresent(this::publishLatest);
        });
    }

    /**
     * 列表只消费 projection，不读取 Child messages 或 Timeline。
     */
    @Override
    public List<TaskModels.Summary> listTree(String rootThreadId) {
        requireOpen();
        List<TaskModels.Summary> result = tasks.listTree(rootThreadId);
        if (result.size() > MAX_TREE_TASKS) {
            throw new IllegalStateException("task tree exceeded hard limit");
        }
        return result;
    }

    /**
     * 主 Timeline 只经有界 Activity 投影读取持久事实，禁止借此物化 Child Transcript。
     */
    @Override
    public List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit) {
        requireOpen();
        if (limit < 1 || limit > 128) {
            throw new IllegalArgumentException("invalid root activity limit");
        }
        return tasks.listRootActivities(rootThreadId, limit);
    }

    /**
     * 详情读取保持 caller cursor，隐藏详情不会触发该方法。
     */
    @Override
    public TaskModels.Detail read(String taskThreadId, long afterActivitySequence,
                                  long afterMailboxSequence, int limit) {
        requireOpen();
        if (afterActivitySequence < 0 || afterMailboxSequence < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("invalid task detail page");
        }
        return tasks.readTask(taskThreadId, afterActivitySequence, afterMailboxSequence, limit)
                .orElseThrow(() -> new TaskRepositoryException(TaskRepositoryException.Code.NOT_FOUND,
                        "task is unavailable"));
    }

    /**
     * 在每次 Runtime resolve 安全点从 SQLite 重读 Child lineage 与父/主 Thread 标题；不缓存身份，
     * 从而让重启、压缩和 Plan 执行续跑都不会继续使用已过期的任务关系。
     */
    @Override
    public Optional<TaskModels.RuntimeIdentity> readRuntimeIdentity(String taskThreadId) {
        requireOpen();
        Objects.requireNonNull(taskThreadId, "taskThreadId");
        return tasks.findTask(taskThreadId)
                .map(task -> {
                    TaskModels.Lineage lineage = task.lineage();
                    String parentName = requireThread(lineage.parentThreadId()).thread().title();
                    String rootName = lineage.rootThreadId().equals(lineage.parentThreadId())
                            ? parentName : requireThread(lineage.rootThreadId()).thread().title();
                    return new TaskModels.RuntimeIdentity(lineage.taskThreadId(), lineage.parentThreadId(),
                            lineage.rootThreadId(), lineage.taskName(), parentName, rootName, lineage.kind());
                });
    }

    /**
     * 每次观察都有独立句柄，连接只释放自己取得的资源；登记与关闭共用锁，避免退出后遗留订阅。
     */
    @Override
    public Observation observe(String taskThreadId, long expectedTaskRevision) {
        synchronized (waiterLifecycleLock) {
            requireOpen();
            TaskModels.Summary task = requireTask(taskThreadId);
            if (task.projection().revision() != expectedTaskRevision) {
                throw conflict();
            }
            ObservationState state = new ObservationState(id("observe_"), taskThreadId, expectedTaskRevision);
            observations.put(state.observationId(), state);
            return new Observation(state.observationId(), state.taskThreadId(), state.revision());
        }
    }

    /**
     * 未知 observation 不可静默成功，否则前端会误以为旧高频流已经解除。
     */
    @Override
    public void unobserve(String observationId) {
        synchronized (waiterLifecycleLock) {
            requireOpen();
            if (observations.remove(observationId) == null) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.OBSERVATION_INVALID,
                        "task observation is unavailable");
            }
        }
    }

    /**
     * 已读 CAS 提交后发布完整摘要，UI 无需猜测 unread decrement。
     */
    @Override
    public TaskModels.Summary markSeen(String taskThreadId, long expectedTaskRevision,
                                       long throughActivitySequence) {
        requireOpen();
        return tasks.markSeen(taskThreadId, expectedTaskRevision, throughActivitySequence, clock.instant());
    }

    /**
     * 先持久化 PENDING 邮箱作为消息事实；活动 Turn 只尝试原子绑定 Core MESSAGE 队列，容量满时留待下次准入。
     * 这样崩溃发生在即时投递前也不会丢失消息，重复请求仍由发送方幂等键回放同一行。
     */
    @Override
    public MessageReceipt sendMessage(MessageCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        if (command.content().blocks().stream().anyMatch(SkillReferenceContent.class::isInstance)) {
            throw new IllegalArgumentException("Task mailbox messages cannot change the receiver's active Skills");
        }
        Instant now = clock.instant();
        TaskModels.MailboxEnvelope envelope = envelope(command, TaskModels.MailboxKind.MESSAGE, now);
        TaskModels.MailboxMessage mailbox = tasks.enqueueMessage(envelope).mailbox();
        if (mailbox.state() == TaskModels.MailboxState.PENDING) {
            turns.findActiveTurn(mailbox.targetThreadId()).ifPresent(active -> enqueueActiveMessage(mailbox, active));
        }
        return new MessageReceipt(mailbox.messageId(), mailbox.sequence());
    }

    /**
     * 只对已持久 PENDING 消息请求一次 Engine 队列提交；确定的容量/owner 竞态留给下一 admission 接纳，
     * 其他失败向发送方暴露以便按稳定幂等键核实，而不伪造消息已绑定。
     */
    private void enqueueActiveMessage(TaskModels.MailboxMessage mailbox, TurnUseCase.ActiveTurn active) {
        if (!mailbox.targetThreadId().equals(active.threadId())) {
            throw new IllegalStateException("active Turn does not belong to the mailbox target");
        }
        try {
            turns.enqueueTaskMessage(active.turnId(), TaskMailboxQueueCommandMapper.toQueueCommand(mailbox, jsonValues));
        } catch (TurnUseCase.InputMutationException failure) {
            if (failure.failure() == TurnUseCase.InputMutationFailure.QUEUE_FULL
                || failure.failure() == TurnUseCase.InputMutationFailure.TURN_NOT_FOUND) {
                    return;
                }
            throw failure;
        }
        TaskModels.MailboxMessage settled = mailboxes.findByIdempotency(
                mailbox.senderThreadId(), mailbox.operationKey()).orElseThrow(() ->
                new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "Core accepted a Task message without its Mailbox fact"));
        if (settled.state() == TaskModels.MailboxState.PENDING
            || !active.turnId().equals(settled.boundTurnId())) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "Core accepted a Task message without binding its Mailbox owner");
        }
    }

    /**
     * Follow-up 先回读幂等事实，再执行 revision 门与 TurnQueue 预留；公开重试因此不会创建草稿 Turn。
     */
    @Override
    public FollowUpResult followUp(FollowUpCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        TaskModels.MailboxEnvelope envelope = envelope(command.message(), TaskModels.MailboxKind.FOLLOW_UP, now);
        Optional<TaskModels.FollowUpAdmissionReceipt> replay = tasks.findFollowUpByIdempotency(envelope);
        if (replay.isPresent()) {
            TaskModels.FollowUpAdmissionReceipt receipt = replay.orElseThrow();
            TaskModels.Summary task = requireTask(command.message().targetThreadId());
            return new FollowUpResult(task, receipt.admission().turnId(), receipt.mailbox().messageId());
        }
        TaskModels.Summary before = requireTask(command.message().targetThreadId());
        if (before.projection().revision() != command.expectedTaskRevision()) {
            throw conflict();
        }
        ThreadSnapshot child = requireThread(before.lineage().taskThreadId());
        Workspace workspace = workspaces.requireOpenWorkspace(child.thread().workspaceId());
        String turnId = id("turn_task_");
        TurnStartRequest request = request(child.thread(), workspace, turnId, command.message().content(),
                now, tasks.findInitialContextSeedForFirstTurn(child.thread().threadId()));
        TurnEventBinding events = prepareTurn(request);
        TurnUseCase.Accepted accepted;
        boolean admitted = false;
        try {
            TaskAdmissionRegistration registration = admissions.registerFollowUp(
                    envelope, command.expectedTaskRevision(), request,
                    id("activity_"), summary("已追加后续任务"));
            try (registration) {
                accepted = scheduler.startChild(request, events.turnEvents(),
                        command.message().senderThreadId(), command.message().causalTurnId());
            }
            admitted = true;
        } finally {
            if (!admitted) {
                events.abandon();
            }
        }
        TaskModels.FollowUpAdmissionReceipt receipt = tasks.findFollowUpByIdempotency(envelope)
                .orElseThrow(() -> new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "follow-up admission committed without its Task receipt"));
        if (!accepted.turnId().equals(turnId)) {
            events.abandon();
        }
        TaskModels.Summary task = requireTask(before.lineage().taskThreadId());
        publishLatest(task);
        observeCompletion(task, accepted);
        return new FollowUpResult(task, receipt.admission().turnId(), receipt.mailbox().messageId());
    }

    /**
     * Agent continuation 比用户侧 FOLLOW_UP 更窄：只有目标 ATTACHED/SUBAGENT 委派链中的真实请求者
     * 才能启动下一 Turn，不能因为共享 root Thread 就把独立侧聊当成 Agent 控制目标。
     */
    @Override
    public FollowUpResult continueAgentFrom(String requesterThreadId, FollowUpCommand command) {
        requireOpen();
        Objects.requireNonNull(requesterThreadId, "requesterThreadId");
        Objects.requireNonNull(command, "command");
        if (!requesterThreadId.equals(command.message().senderThreadId())) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.PERMISSION_DENIED,
                    "agent continuation sender does not match requester");
        }
        TaskModels.Summary target = requireTask(command.message().targetThreadId());
        if (target.lineage().kind() != TaskModels.Kind.SUBAGENT
            || target.lineage().lifecycle() != TaskModels.Lifecycle.ATTACHED) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.PERMISSION_DENIED,
                    "only attached subagents can be continued by an agent");
        }
        requireDelegatedTargets(requesterThreadId, Set.of(target.lineage().taskThreadId()));
        return followUp(command);
    }

    /**
     * 取消先校验 Task CAS，再使用 Child Thread 自身 revision 请求 Turn 取消。
     */
    @Override
    public TaskModels.Summary cancel(String taskThreadId, long expectedTaskRevision) {
        requireOpen();
        TaskModels.Summary task = requireTask(taskThreadId);
        if (task.projection().revision() != expectedTaskRevision) {
            throw conflict();
        }
        cancelAllTurns(taskThreadId);
        cancelAttachedDescendants(taskThreadId);
        return requireTask(taskThreadId);
    }

    /**
     * 关闭只适用于仓储确认的独立侧聊；返回事务持久化的完整范围，使响应丢失后的重试仍能清理 UI 子树。
     */
    @Override
    public TaskModels.SideChatCloseReceipt closeSideChat(String taskThreadId) {
        requireOpen();
        Objects.requireNonNull(taskThreadId, "taskThreadId");
        return runSideChatClose(taskThreadId);
    }

    /**
     * RPC 携带的 owner 只能关闭自己登记的独立 root；回执提交后同步移除已收口的嵌套 root。
     */
    @Override
    public TaskModels.SideChatCloseReceipt closeSideChat(String taskThreadId,
                                                         Optional<NativeClientOwner> ownerScope) {
        Objects.requireNonNull(ownerScope, "ownerScope");
        if (ownerScope.isEmpty()) {
            return closeSideChat(taskThreadId);
        }
        NativeClientWork owner = requireTaskNativeClient(ownerScope.orElseThrow());
        if (!owner.containsRoot(taskThreadId)) {
            throw nativeClientPermissionDenied();
        }
        TaskModels.SideChatCloseReceipt receipt = runSideChatClose(taskThreadId);
        removeNativeSideChatRoots(owner, Set.copyOf(receipt.closedThreadIds()));
        return receipt;
    }

    /**
     * 在 TurnService 停止接纳前收口临时侧聊；父关闭回执覆盖其冻结子树，避免后续处理旧枚举里的已删嵌套 root。
     * 该前置阶段不关闭协调器，因为 Turn 取消回调仍需通过它传播已提交的 Child 取消事实。
     */
    @Override
    public void closeTemporarySideChats(long shutdownDeadlineNanos) {
        if (shutdownDeadlineNanos == Long.MIN_VALUE) {
            throw new IllegalArgumentException("invalid side chat shutdown deadline");
        }
        shutdownRequested.set(true);
        if (!sideChatShutdownAttempted.compareAndSet(false, true)) {
            RuntimeException previous = sideChatShutdownFailure;
            if (previous != null) {
                throw previous;
            }
            return;
        }
        RuntimeException failure = null;
        try {
            Set<String> closedThreadIds = new LinkedHashSet<>();
            for (String taskThreadId : tasks.listTemporarySideChats().stream()
                    .map(TaskRepository.TemporarySideChat::threadId).toList()) {
                if (closedThreadIds.contains(taskThreadId)) {
                    continue;
                }
                try {
                    TaskModels.SideChatCloseReceipt receipt =
                            runSideChatClose(taskThreadId, shutdownDeadlineNanos);
                    closedThreadIds.addAll(receipt.closedThreadIds());
                } catch (RuntimeException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    }
                    else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
        } catch (RuntimeException listFailure) {
            failure = listFailure;
        }
        sideChatShutdownFailure = failure;
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 同一侧聊的并发关闭共享一个 Future，避免两个调用者分别取消、purge 或清理同一组租约；
     * 失败只解除内存中的 single-flight，让后续显式重试重新读取持久 closing 状态。
     */
    private TaskModels.SideChatCloseReceipt runSideChatClose(String taskThreadId) {
        return runSideChatClose(taskThreadId, System.nanoTime() + SIDE_CHAT_CLOSE_TIMEOUT.toNanos());
    }

    /**
     * 关闭单棵临时树时沿用调用方的绝对截止线，保证应用退出的全局预算不会被每个侧聊重复放大。
     */
    private TaskModels.SideChatCloseReceipt runSideChatClose(String taskThreadId, long shutdownDeadlineNanos) {
        CompletableFuture<TaskModels.SideChatCloseReceipt> candidate = new CompletableFuture<>();
        CompletableFuture<TaskModels.SideChatCloseReceipt> current = sideChatClosures.putIfAbsent(
                taskThreadId, candidate);
        if (current != null) {
            long deadline = Math.min(shutdownDeadlineNanos,
                    System.nanoTime() + SIDE_CHAT_CLOSE_TIMEOUT.toNanos());
            return awaitClose(current, deadline);
        }
        closingSideChats.add(taskThreadId);
        try {
            TaskModels.SideChatCloseReceipt receipt = closeSideChatOnce(taskThreadId, shutdownDeadlineNanos);
            closingSideChats.remove(taskThreadId);
            candidate.complete(receipt);
            return receipt;
        } catch (RuntimeException failure) {
            // 持久 closing 标记刻意不回滚；它是拒绝迟到输入和后续重试的安全边界。
            candidate.completeExceptionally(failure);
            throw failure;
        } finally {
            sideChatClosures.remove(taskThreadId, candidate);
        }
    }

    /**
     * 已关闭回执直接重放；进行中关闭严格执行 begin -> stop -> cancel -> terminal barrier -> purge，
     * 并沿用同一冻结集合清理运行资源与 UI 所需回执，避免从已删 Thread 反推范围。
     */
    private TaskModels.SideChatCloseReceipt closeSideChatOnce(String taskThreadId,
                                                              long shutdownDeadlineNanos) {
        TaskModels.SideChatCloseState state = tasks.beginSideChatClose(taskThreadId);
        if (!state.rootThreadId().equals(taskThreadId)) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "side chat close state differs from the requested root");
        }
        Set<String> subtree = Set.copyOf(state.threadIds());
        if (state.phase() == TaskModels.SideChatClosePhase.CLOSED) {
            TaskModels.SideChatCloseReceipt receipt = new TaskModels.SideChatCloseReceipt(
                    state.rootThreadId(), state.ownerRootThreadId(), state.threadIds());
            cleanupClosedSideChat(subtree);
            publishClosedSideChat(receipt);
            return receipt;
        }
        long deadline = Math.min(shutdownDeadlineNanos,
                System.nanoTime() + SIDE_CHAT_CLOSE_TIMEOUT.toNanos());
        // 先停止续跑 owner，再等待其 Turn 终态；反向顺序会让取消与 Plan 的推进/挂起争夺同一轮次。
        sideChatOwnerController.get().stopOwners(subtree, deadline);
        cancelSideChatTurns(subtree, deadline);
        awaitSideChatTurns(subtree, deadline);
        TaskModels.SideChatCloseReceipt receipt = tasks.deleteClosedSideChat(
                taskThreadId, state.ownerRootThreadId(), state.threadIds());
        if (!receipt.rootThreadId().equals(state.rootThreadId())
            || !receipt.ownerRootThreadId().equals(state.ownerRootThreadId())
            || !receipt.closedThreadIds().equals(state.threadIds())) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "side chat close receipt differs from its frozen Thread set");
        }
        cleanupClosedSideChat(subtree);
        publishClosedSideChat(receipt);
        return receipt;
    }

    /**
     * 持久关闭完成后尽力通知同树观察者；通知链故障不能把已提交 purge 改报为业务失败。
     */
    private void publishClosedSideChat(TaskModels.SideChatCloseReceipt receipt) {
        try {
            publish(new TaskEvent.Closed(receipt));
        } catch (RuntimeException failure) {
            LOGGER.warn("event=task_side_chat_close_publish_failed task_thread_id={} cause_type={}",
                    receipt.rootThreadId(), failure.getClass().getSimpleName());
        }
    }

    /**
     * 关闭闸门提交后逐个刷新 Thread revision 取消所有已存在 Turn；每次只使用新快照，避免两个
     * 取消请求共享过期 revision，超过有界预算则保留 closing 状态而不伪造成功。
     */
    private void cancelSideChatTurns(Set<String> subtree, long deadlineNanos) {
        while (true) {
            boolean found = false;
            for (String threadId : subtree) {
                ThreadSnapshot snapshot = threads.readThread(threadId, null, 1).orElse(null);
                if (snapshot == null) {
                    continue;
                }
                Optional<ThreadSnapshot.Turn> pending = snapshot.turns().stream()
                        .filter(turn -> !terminal(turn.status())).findFirst();
                if (pending.isEmpty()) {
                    continue;
                }
                found = true;
                turns.cancel(pending.orElseThrow().turnId());
                if (expired(deadlineNanos)) {
                    throw closeTimeout();
                }
            }
            if (!found) {
                return;
            }
            if (expired(deadlineNanos)) {
                throw closeTimeout();
            }
            LockSupport.parkNanos(SIDE_CHAT_CLOSE_POLL_NANOS);
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "side chat close was interrupted");
            }
        }
    }

    /**
     * 取消请求只是意图，不能作为 purge 条件；这里重新读取每个 Thread，等待 TurnService 的异步
     * 终态结算和恢复回调清理真正完成。
     */
    private void awaitSideChatTurns(Set<String> subtree, long deadlineNanos) {
        while (true) {
            boolean pending = false;
            for (String threadId : subtree) {
                ThreadSnapshot snapshot = threads.readThread(threadId, null, 1).orElse(null);
                if (snapshot != null && snapshot.turns().stream().anyMatch(turn -> !terminal(turn.status()))) {
                    pending = true;
                    break;
                }
            }
            if (!pending) {
                return;
            }
            if (expired(deadlineNanos)) {
                throw closeTimeout();
            }
            LockSupport.parkNanos(SIDE_CHAT_CLOSE_POLL_NANOS);
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "side chat close was interrupted");
            }
        }
    }

    /**
     * 关闭成功后按 Task 清理所有独立观察；与登记共用锁，失败重试仍保留原标签的本地状态。
     */
    private void cleanupClosedSideChat(Set<String> subtree) {
        synchronized (waiterLifecycleLock) {
            observations.values().removeIf(observation -> subtree.contains(observation.taskThreadId()));
            subtree.forEach(acceptedTaskTurns::remove);
            waiters.entrySet().removeIf(entry -> {
                if (entry.getValue().targets().stream().noneMatch(subtree::contains)) {
                    return false;
                }
                entry.getValue().completion().completeExceptionally(
                        new CancellationException("side chat closed"));
                return true;
            });
        }
    }

    /**
     * Agent 入口先证明真实委派关系，再复用用户取消的完整状态机。
     */
    @Override
    public TaskModels.Summary cancelFrom(String requesterThreadId, String taskThreadId,
                                         long expectedTaskRevision) {
        requireDelegatedTargets(requesterThreadId, Set.of(taskThreadId));
        return cancel(taskThreadId, expectedTaskRevision);
    }

    /**
     * 每次取消后重读 Thread revision，令 active 与全部 queued Turn 都经各自持久 CAS 收敛。
     */
    private void cancelAllTurns(String taskThreadId) {
        while (true) {
            ThreadSnapshot snapshot = requireThread(taskThreadId);
            Optional<ThreadSnapshot.Turn> pending = snapshot.turns().stream().filter(turn -> !terminal(turn.status()))
                    .reduce((left, right) -> right);
            if (pending.isEmpty()) {
                return;
            }
            turns.cancel(pending.orElseThrow().turnId());
        }
    }

    /**
     * ATTACHED 后代按深度从叶到根取消，并重复快照直到并发派生不再留下非终态 Turn。
     */
    private void cancelAttachedDescendants(String taskThreadId) {
        while (true) {
            List<TaskModels.Summary> descendants = tasks.attachedDescendants(taskThreadId).stream()
                    .sorted(Comparator.comparingInt(value -> -value.lineage().depth())).toList();
            boolean found = false;
            for (TaskModels.Summary descendant : descendants) {
                if (hasNonTerminalTurn(descendant.lineage().taskThreadId())) {
                    found = true;
                    cancelAllTurns(descendant.lineage().taskThreadId());
                }
            }
            if (!found) {
                return;
            }
        }
    }

    /**
     * 取消传播只依赖持久 Turn 状态，Task projection 的 sibling revision 变化不会影响判断。
     */
    private boolean hasNonTerminalTurn(String taskThreadId) {
        return requireThread(taskThreadId).turns().stream().anyMatch(turn -> !terminal(turn.status()));
    }

    /**
     * 普通父 Turn 的取消只命中由该 Turn 直接派生的 ATTACHED Child；更深层传播由被取消 Child Turn
     * 再次触发同一监听器完成，因此不会越过 SIDE_TASK 或误伤同一 Thread 的其它 Turn 所派任务。
     */
    @Override
    public void cancellationClaimed(String parentThreadId, String parentTurnId) {
        // 关闭前置阶段仍需消费 TurnService 的已提交取消回调；TaskCoordinator 完全关闭后，
        // closeTemporarySideChats 已冻结并逐个处理完整侧聊子树，迟到传播只会制造无效重试。
        if (closed.get()) {
            return;
        }
        tasks.attachedDescendants(parentThreadId).stream()
                .filter(task -> task.lineage().parentThreadId().equals(parentThreadId))
                .filter(task -> Objects.equals(task.lineage().originTurnId(), parentTurnId))
                .forEach(task -> cancelAllTurns(task.lineage().taskThreadId()));
    }

    /**
     * 绑定监听器时从 SQLite 父取消事实恢复传播；查询已排除 INDEPENDENT 侧边任务。
     */
    @Override
    public void reconcilePending() {
        requireOpen();
        for (TaskModels.CancellationPropagation pending : tasks.pendingCancellationPropagations()) {
            cancellationClaimed(pending.parentThreadId(), pending.parentTurnId());
        }
    }

    /**
     * 删除必须重复精确身份；Repository 负责原子核对整树无活动 Turn。
     */
    @Override
    public int deleteTree(String taskThreadId, long expectedTaskRevision, String confirmTaskThreadId) {
        return deleteTree(taskThreadId, expectedTaskRevision, confirmTaskThreadId, Optional.empty());
    }

    /**
     * 拒绝直接删除临时侧聊树；删除不是关闭回执，必须走能够停止 owner、等待终态并释放租约的专用链。
     */
    @Override
    public int deleteTree(String taskThreadId, long expectedTaskRevision, String confirmTaskThreadId,
                          Optional<NativeClientOwner> ownerScope) {
        requireOpen();
        Objects.requireNonNull(ownerScope, "ownerScope");
        if (!Objects.equals(taskThreadId, confirmTaskThreadId)) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.TREE_DELETE_REQUIRED,
                    "task tree deletion requires exact confirmation");
        }
        if (treeContainsTemporarySideChat(taskThreadId)) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                    "temporary side chats must be closed before deleting a task tree");
        }
        return tasks.deleteTree(taskThreadId, expectedTaskRevision, clock.instant());
    }

    /**
     * 先校验令牌再观察目标，避免无效取消依赖在登记 waiter 与超时任务后才失败。
     */
    @Override
    public CompletionStage<WaitResult> waitAgents(Set<String> taskThreadIds, Duration timeout,
                                                  CancellationToken cancellation) {
        requireOpen();
        Set<String> targets = Set.copyOf(Objects.requireNonNull(taskThreadIds, "taskThreadIds"));
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancellation, "cancellation");
        if (targets.isEmpty() || targets.size() > 8 || timeout.isNegative() || timeout.isZero()
            || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("invalid task wait request");
        }
        List<TaskModels.Summary> ready = readyTargets(targets);
        if (!ready.isEmpty()) {
            return CompletableFuture.completedFuture(new WaitResult(ready, false));
        }
        String waiterId = id("waiter_");
        CompletableFuture<WaitResult> completion = new CompletableFuture<>();
        Waiter waiter = new Waiter(targets, completion);
        synchronized (waiterLifecycleLock) {
            requireOpen();
            waiters.put(waiterId, waiter);
            ScheduledFuture<?> timeoutTask;
            try {
                timeoutTask = timeouts.schedule(() -> completion.complete(new WaitResult(List.of(), true)),
                        timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RuntimeException failure) {
                waiters.remove(waiterId, waiter);
                throw failure;
            }
            try {
                // Registration 跨越当前栈帧，由 completion 的唯一终结回调关闭，不能使用 try-with-resources。
                @SuppressWarnings("PMD.CloseResource")
                CancellationToken.Registration registration = cancellation.onCancellation(() ->
                        completion.completeExceptionally(new CancellationException("parent turn cancelled")));
                completion.whenComplete((ignored, failure) -> {
                    waiters.remove(waiterId, waiter);
                    timeoutTask.cancel(false);
                    registration.close();
                });
            } catch (RuntimeException failure) {
                waiters.remove(waiterId, waiter);
                timeoutTask.cancel(false);
                throw failure;
            }
        }
        try {
            List<TaskModels.Summary> changedWhileRegistering = readyTargets(targets);
            if (!changedWhileRegistering.isEmpty()) {
                completion.complete(new WaitResult(changedWhileRegistering, false));
            }
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    /**
     * 先验证所有目标都属于调用者的委派链，再登记 waiter，避免旁支目标留下观察句柄。
     */
    @Override
    public CompletionStage<WaitResult> waitAgentsFrom(String requesterThreadId, Set<String> taskThreadIds,
                                                      Duration timeout, CancellationToken cancellation) {
        requireDelegatedTargets(requesterThreadId, taskThreadIds);
        return waitAgents(taskThreadIds, timeout, cancellation);
    }

    /**
     * 单运行连接订阅 Task 与 Child Timeline；关闭显示订阅不触碰持久 Task。
     */
    @Override
    public AutoCloseable subscribe(TaskEventSink sink) {
        requireOpen();
        Objects.requireNonNull(sink, "sink");
        if (!subscriber.compareAndSet(null, sink)) {
            throw new IllegalStateException("task events already subscribed");
        }
        return () -> subscriber.compareAndSet(sink, null);
    }

    /**
     * 进程退出先枚举并收口全部临时侧聊，再释放本地观察资源；失败继续清理其它侧聊并向生命周期
     * owner 报错，避免把未 purge 的临时会话误报为正常关闭。
     */
    @Override
    public void close() {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        shutdownRequested.set(true);
        RuntimeException failure = null;
        try {
            if (!sideChatShutdownAttempted.get()) {
                closeTemporarySideChats(System.nanoTime() + SIDE_CHAT_CLOSE_TIMEOUT.toNanos());
            } else {
                failure = sideChatShutdownFailure;
            }
        } catch (RuntimeException listFailure) {
            failure = listFailure;
        } finally {
            synchronized (waiterLifecycleLock) {
                closed.set(true);
                closingSideChats.clear();
                observations.clear();
                acceptedTaskTurns.clear();
                logActiveTaskCount();
                waiters.values().forEach(waiter -> waiter.completion().completeExceptionally(
                        new CancellationException("task coordinator closed")));
                waiters.clear();
                subscriber.set(null);
                timeouts.shutdownNow();
            }
            if (failure == null) {
                NativeExecutionContext.shared().releaseNativeClientProcessExitListener(this);
                nativeClientCleanup.shutdownNow();
                nativeClientWork.clear();
                nativeSideChatOwners.clear();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 可见详情由 Task 身份匹配所有独立观察；没有观察者时不向来源会话广播侧边任务活动。
     */
    private void publishLatest(TaskModels.Summary task) {
        if (task.lineage().kind() == TaskModels.Kind.SIDE_TASK
            && observations.values().stream().noneMatch(observation ->
                    observation.taskThreadId().equals(task.lineage().taskThreadId()))) {
            return;
        }
        long sequence = task.projection().latestActivitySequence();
        tasks.readTask(task.lineage().taskThreadId(), sequence - 1, 0, 1).ifPresent(detail -> {
            if (!detail.activities().isEmpty()) {
                publish(new TaskEvent.Activity(context(task, detail.activities().getFirst().createdAt()),
                        detail.activities().getFirst(), task));
            }
        });
        notifyWaiters(task);
    }

    /**
     * Durable 事件要求连接背压；无活动连接时只保留 SQLite 事实供下次 task/list 重读。
     */
    private void publish(TaskEvent event) {
        TaskEventSink sink = subscriber.get();
        if (sink != null) {
            sink.publish(event).toCompletableFuture().join();
        }
    }

    /**
     * 在 Child 可能产生首个 delta 前登记关联，并复用统一 Task wrapper 保留 RPC 的权威提交 fence。
     * @author kongweiguang
     */
    private TurnEventBinding prepareTurn(TurnStartRequest request) {
        TaskEventSink sink = subscriber.get();
        if (sink == null) {
            sink = TaskEventSink.noop();
        }
        else {
            sink.registerTurn(request.turnId(), request.workspaceId(), request.threadId(),
                request.expectedThreadRevision());
        }
        TaskEventSink bound = sink;
        ConversationTurnEventSink projected = projectContinuationEvents(request.threadId(), bound.turnEvents());
        return new TurnEventBinding(sink, request.turnId(), projected);
    }

    /**
     * Child Turn 的低频生命周期进入 Task projection；流式 delta 只给当前 observation，且永不持久化。
     */
    private void projectTurnEvent(String taskThreadId, io.github.kongweiguang.ja.conversation.domain.model.TurnEvent event) {
        switch (event) {
            case io.github.kongweiguang.ja.conversation.domain.model.TurnEvent.StateChanged state
                    when state.to() == TurnState.RUNNING -> recordTurnActivity(taskThreadId,
                    TaskModels.State.RUNNING, TaskModels.ActivityKind.RESUMED, state.context().turnId(), "正在运行");
            case io.github.kongweiguang.ja.conversation.domain.model.TurnEvent.StateChanged state
                    when state.to() == TurnState.WAITING_INPUT -> recordTurnActivity(taskThreadId,
                    TaskModels.State.WAITING_INPUT, TaskModels.ActivityKind.WAITING_INPUT,
                    state.context().turnId(), "等待补充信息");
            case io.github.kongweiguang.ja.conversation.domain.model.TurnEvent.ApprovalRequested approval ->
                    recordTurnActivity(taskThreadId, TaskModels.State.WAITING_APPROVAL,
                            TaskModels.ActivityKind.WAITING_APPROVAL, approval.context().turnId(), "等待审批");
            case io.github.kongweiguang.ja.conversation.domain.model.TurnEvent.ApprovalResolved resolved ->
                    recordTurnActivity(taskThreadId, TaskModels.State.RUNNING,
                            TaskModels.ActivityKind.RESUMED, resolved.context().turnId(), "审批已处理，继续运行");
            case io.github.kongweiguang.ja.conversation.domain.model.TurnEvent.TextDelta delta ->
                    publishProgress(taskThreadId, delta.streamSeq(), delta.text());
            case io.github.kongweiguang.ja.conversation.domain.model.TurnEvent.ReasoningSummaryDelta delta ->
                    publishProgress(taskThreadId, delta.streamSeq(), delta.text());
            default -> {
            }
        }
    }

    /**
     * projection CAS 与 Activity 由 Repository 同事务提交，竞争时让下一权威事件或 task/read 收敛。
     */
    private void recordTurnActivity(String taskThreadId, TaskModels.State state, TaskModels.ActivityKind kind,
                                    String turnId, String text) {
        for (int attempt = 0; attempt < 8; attempt++) {
            TaskModels.Summary current = requireTask(taskThreadId);
            if (current.projection().state() == state || terminal(current.projection().state())) {
                return;
            }
            try {
                TaskModels.Summary updated = tasks.recordActivity(new TaskModels.ActivityMutation(taskThreadId,
                        current.projection().revision(), state, id("activity_"), taskThreadId, turnId, kind,
                        summary(text), text, clock.instant()));
                publishLatest(updated);
                return;
            } catch (TaskRepositoryException conflict) {
                if (conflict.code() != TaskRepositoryException.Code.CAS_CONFLICT) {
                    throw conflict;
                }
            }
        }
        LOGGER.warn("event=task_activity_projection_conflict retry_count=8 state={}", state.name());
    }

    /**
     * 同一 Task 的每个句柄独立接收草稿；先固定观察快照再投递，避免持锁等待慢客户端。
     */
    private void publishProgress(String taskThreadId, long progressRevision, String text) {
        List<ObservationState> targets;
        synchronized (waiterLifecycleLock) {
            targets = observations.values().stream()
                    .filter(observation -> observation.taskThreadId().equals(taskThreadId)).toList();
        }
        if (targets.isEmpty()) {
            return;
        }
        TaskModels.Summary task = requireTask(taskThreadId);
        String safe = text.length() <= 4_096 ? text : text.substring(0, 4_096);
        for (ObservationState observation : targets) {
            publish(new TaskEvent.Progress(context(task, clock.instant()), observation.observationId(),
                    progressRevision, safe));
        }
    }

    /**
     * Child 与 Plan continuation 共用 Task 投影装饰器；下游先确认原始事件，连接级提交 fence 与恢复通知
     * 原样透传，终态再读取最新 Activity，避免各执行入口各自实现不同的 sink 边界。
     * @author kongweiguang
     */
    @Override
    public ConversationTurnEventSink projectContinuationEvents(
            String taskThreadId, ConversationTurnEventSink downstream) {
        requireOpen();
        Objects.requireNonNull(taskThreadId, "taskThreadId");
        Objects.requireNonNull(downstream, "downstream");
        AtomicReference<Boolean> taskProjection = new AtomicReference<>();
        return new ConversationTurnEventSink() {
            /** 透传已登记的 Workspace 身份，使持久 Turn 生命周期事件能够沿原 RPC 连接补齐路由信息。
             * @author kongweiguang
             */
            @Override
            public java.util.Optional<String> workspaceId(AgentIdentity identity) {
                return downstream.workspaceId(identity);
            }

            /** 下游先接收原始事件，Task 投影随后只消费已确认的同一事件。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(TurnEvent event) {
                downstream.publish(event).toCompletableFuture().join();
                Boolean shouldProject = taskProjection.get();
                if (shouldProject == null) {
                    shouldProject = tasks.findTask(taskThreadId).isPresent();
                    taskProjection.compareAndSet(null, shouldProject);
                }
                if (!shouldProject) {
                    return CompletableFuture.completedFuture(null);
                }
                projectTurnEvent(taskThreadId, event);
                if (event instanceof io.github.kongweiguang.ja.conversation.domain.model.TurnEvent.Terminal) {
                    tasks.findTask(taskThreadId).ifPresent(TaskCoordinator.this::publishLatest);
                }
                return CompletableFuture.completedFuture(null);
            }

            /** Task 展示投影不解释失败原因，重同步信号原样交给真实会话路由。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publishProjectionFailure(
                    TurnProjectionFailure failure) {
                return downstream.publishProjectionFailure(failure);
            }

            /** Thread 标题仍沿用同一 RPC 连接出口，不被 Task Timeline 装饰器截断。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(ThreadMetadataEvent event) {
                return downstream.publish(event);
            }

            /** Child 共用主会话压缩链路；非 Task 事件仍须抵达真实会话 sink，不能被默认 no-op 吞掉。 */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(ContextCompactionEvent event) {
                return downstream.publish(event);
            }

            /** 将非 wire 的内部提交边界透传到真正 RPC sink，避免 Task 包装造成恢复水位断裂。 */
            @Override
            public void observeCommittedTurn(String threadId, String turnId, long threadRevision,
                                             long turnMutationVersion, int modelRound, TurnEvent event,
                                             java.time.Instant occurredAt) {
                downstream.observeCommittedTurn(threadId, turnId, threadRevision, turnMutationVersion,
                        modelRound, event, occurredAt);
            }

            /** Admission 的 Thread revision 是通知 fence，不是 wire 事件；装饰器必须同步转交给连接。 */
            @Override
            public void onThreadRevision(AgentIdentity identity, long revision) {
                downstream.onThreadRevision(identity, revision);
            }
        };
    }

    /**
     * 只有匹配目标进入终态或 needs-attention 时完成 wait，普通 progress 不唤醒父模型。
     */
    private void notifyWaiters(TaskModels.Summary task) {
        if (!ready(task)) {
            return;
        }
        waiters.values().forEach(waiter -> {
            if (waiter.targets().contains(task.lineage().taskThreadId())) {
                waiter.completion().complete(new WaitResult(List.of(task), false));
            }
        });
    }

    /**
     * Immediate snapshot 与事件唤醒使用同一 ready 判定。
     */
    private List<TaskModels.Summary> readyTargets(Set<String> targets) {
        List<TaskModels.Summary> ready = new ArrayList<>();
        for (String target : targets) {
            TaskModels.Summary task = requireTask(target);
            if (ready(task)) {
                ready.add(task);
            }
        }
        return List.copyOf(ready);
    }

    /**
     * Parent revision 可选校验只用于 user create；Agent spawn 读取当前 revision 后由 admission 再校验。
     */
    private ThreadSummary requireParent(String threadId, Long expectedRevision) {
        ThreadSummary parent = requireThread(threadId).thread();
        if (expectedRevision != null && parent.revision() != expectedRevision) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.CONTEXT_REVISION_CONFLICT,
                    "parent revision changed");
        }
        return parent;
    }

    /**
     * Thread 读取 limit 只约束 Timeline items，Turn metadata 仍完整供取消选择。
     */
    private ThreadSnapshot requireThread(String threadId) {
        return threads.readThread(threadId, null, 1)
                .orElseThrow(() -> new TaskRepositoryException(TaskRepositoryException.Code.NOT_FOUND,
                        "thread is unavailable"));
    }

    /**
     * Repository summary 是右栏和事件唯一权威投影。
     */
    private TaskModels.Summary requireTask(String taskThreadId) {
        return tasks.findTask(taskThreadId)
                .orElseThrow(() -> new TaskRepositoryException(TaskRepositoryException.Code.NOT_FOUND,
                        "task is unavailable"));
    }

    /**
     * 通信可跨所有会话，控制权只沿真实委派边向下；共享上下文来源不能赋予取消或等待旁支的权限。
     */
    private void requireDelegatedTargets(String requesterThreadId, Set<String> targetThreadIds) {
        Objects.requireNonNull(requesterThreadId, "requesterThreadId");
        Set<String> targets = Set.copyOf(Objects.requireNonNull(targetThreadIds, "targetThreadIds"));
        for (String targetThreadId : targets) {
            TaskModels.Summary current = requireTask(targetThreadId);
            boolean delegated = false;
            for (int depth = 0; current != null && depth < 4; depth++) {
                TaskModels.Lineage lineage = current.lineage();
                if (lineage.kind() != TaskModels.Kind.SUBAGENT
                    || lineage.lifecycle() != TaskModels.Lifecycle.ATTACHED) {
                        break;
                    }
                if (requesterThreadId.equals(lineage.parentThreadId())) {
                    delegated = true;
                    break;
                }
                if (lineage.parentThreadId().equals(lineage.rootThreadId())) {
                    break;
                }
                current = tasks.findTask(lineage.parentThreadId()).orElse(null);
            }
            if (!delegated) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.PERMISSION_DENIED,
                        "task target is not delegated by the requester");
            }
        }
    }

    /**
     * 活动计数只记录聚合值，不包含 Thread、任务名、prompt 或配置身份。
     */
    private void logActiveTaskCount() {
        LOGGER.info("event=task_active_count active_count={}", acceptedTaskTurns.size());
    }

    /**
     * 关闭请求一旦发布就拒绝新调用，但允许 Turn cancellation callback 继续消费持久事实。
     */
    private void requireOpen() {
        if (closed.get() || shutdownRequested.get()) {
            throw new IllegalStateException(closed.get()
                    ? "task coordinator is closed" : "task coordinator is shutting down");
        }
    }

    /**
     * Plan 执行的取消由其权威协调器执行，Task 不能仅删除数据伪装异步运行已经结束。
     */
    @FunctionalInterface
    public interface SideChatOwnerController {
        /**
         * 关闭前停止这些 Thread 的全部独立执行及 evaluator，并在同一单调 deadline 内完成；超时必须向关闭调用方传播。
         *
         * @param threadIds 待停止的 owner Thread
         * @param deadlineNanos 关闭流程共享的单调时钟截止时间
         */
        void stopOwners(Set<String> threadIds, long deadlineNanos);
    }

    /**
     * 保存一次 Child 通知登记；无 UI 连接时 sink 为 noop，但低频 Task 投影仍必须持久化。
     *
     * @param sink 输出接收端
     * @param turnId 本次可恢复执行的稳定标识
     * @param projectedEvents 投影事件列表
     */
    private record TurnEventBinding(TaskEventSink sink,
                                    String turnId,
                                    ConversationTurnEventSink projectedEvents) {
        /**
         * Timeline sink 与清理动作必须来自同一订阅代际。
         */
        private ConversationTurnEventSink turnEvents() {
            return projectedEvents;
        }

        /**
         * 清理未运行的 requested Turn；终态运行项由 RpcSession 自动回收。
         */
        private void abandon() {
            sink.abandonTurn(turnId);
        }
    }

    /**
     * 当前进程 observation 不持有 transcript 或 UI 组件引用。
     *
     * @param observationId 观察结果标识
     * @param taskThreadId 任务对话标识
     * @param revision 当前修订版本
     */
    private record ObservationState(String observationId,
                                    String taskThreadId,
                                    long revision) {
    }

    /**
     * Waiter 只保存最多八个稳定 ID 和一次性完成阶段。
     *
     * @param targets 目标列表
     * @param completion 执行完成时的异步信号
     */
    private record Waiter(Set<String> targets,
                          CompletableFuture<WaitResult> completion) {
    }

}
