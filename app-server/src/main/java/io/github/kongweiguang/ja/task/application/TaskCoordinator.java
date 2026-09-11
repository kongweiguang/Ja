// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.application;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnCancellationListener;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.SubagentPolicyRepository;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.in.TaskEvent;
import io.github.kongweiguang.ja.task.port.in.TaskEventSink;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.task.port.out.TaskRepository;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Child Thread 的唯一应用层协调器；持久化、Turn 调度与 UI 观察通过窄端口组合而不建立第二运行时。
 */
public final class TaskCoordinator implements TaskUseCase, TurnCancellationListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskCoordinator.class);
    private static final int MAX_TREE_TASKS = 64;
    private static final Duration SIDE_CHAT_CLOSE_TIMEOUT = Duration.ofSeconds(10);
    private static final long SIDE_CHAT_CLOSE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    /** Plan/Goal 的取消由其权威 owner 执行，Task 不能仅删除数据伪装异步运行已经结束。 */
    @FunctionalInterface
    public interface SideChatOwnerController {
        /** 关闭前停止这些 Thread 的全部独立执行及 evaluator，失败必须向关闭调用方传播。 */
        void stopOwners(Set<String> threadIds);
    }

    private static final SideChatOwnerController NO_SIDE_CHAT_OWNER_CONTROLLER = ignored -> {
        throw new IllegalStateException("side chat execution owner is not bound");
    };
    private final TaskRepository tasks;
    private final ThreadUseCase threads;
    private final TurnUseCase turns;
    private final ChildTurnScheduler scheduler;
    private final WorkspaceUseCase workspaces;
    private final Clock clock;
    private final SubagentPolicyRepository subagentPolicies;
    private final AtomicReference<TaskEventSink> subscriber = new AtomicReference<>();
    private final Map<String, ObservationState> observations = new ConcurrentHashMap<>();
    private final Map<String, Waiter> waiters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> acceptedTaskTurns = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> sideChatClosures = new ConcurrentHashMap<>();
    private final Set<String> closingSideChats = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final AtomicBoolean sideChatShutdownAttempted = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private volatile RuntimeException sideChatShutdownFailure;
    private final AtomicReference<SideChatOwnerController> sideChatOwnerController =
            new AtomicReference<>(NO_SIDE_CHAT_OWNER_CONTROLLER);
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ja-task-wait-timeout-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * TurnUseCase 与 ChildTurnScheduler 必须指向同一 TurnService，避免 Task 绕过普通取消和关闭闸门。
     */
    /** 生产组合根注入冻结策略读取端口；没有策略 Owner 时禁止启动，避免默认放行。 */
    public TaskCoordinator(TaskRepository tasks, ThreadUseCase threads, TurnUseCase turns,
                           ChildTurnScheduler scheduler, WorkspaceUseCase workspaces, Clock clock,
                           SubagentPolicyRepository subagentPolicies) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (turns != scheduler) throw new IllegalArgumentException("Task scheduler must share Turn owner");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.subagentPolicies = Objects.requireNonNull(subagentPolicies, "subagentPolicies");
    }

    /**
     * 绑定 Goal/Plan owner 的停止协调器；采用一次性 late binding 打破 Task、Goal 与 Plan 组合环，
     * 同时让单元测试可以继续使用不带 Goal/Plan 运行时的窄构造器。
     */
    public void bindSideChatOwnerController(SideChatOwnerController controller) {
        Objects.requireNonNull(controller, "controller");
        if (!sideChatOwnerController.compareAndSet(NO_SIDE_CHAT_OWNER_CONTROLLER, controller)) {
            throw new IllegalStateException("side chat owner controller is already bound");
        }
    }

    /**
     * 供 Goal/Plan continuation adapter 在 admission 前检查临时生命周期闸门；关闭失败时保留该闸门，
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
        requireOpen();
        Objects.requireNonNull(command, "command");
        ThreadSummary parent = requireParent(command.parentThreadId(), command.expectedParentRevision());
        TaskModels.EffectiveContextSnapshot context = tasks.freezeEffectiveContext(
                command.parentThreadId(), command.expectedParentRevision());
        ThreadPreferences preferences = sideTaskPreferences(parent.preferences(), command.preferences());
        Instant now = clock.instant();
        String childThreadId = id("thr_task_");
        TaskModels.ContextSeedDraft seed = new TaskModels.ContextSeedDraft(id("seed_"),
                command.parentThreadId(), command.parentTurnId(), parent.revision(),
                TaskModels.InheritanceMode.EFFECTIVE_CONTEXT, null, context.context(),
                context.references(), accessCeiling(preferences.accessMode()), now);
        ConversationRepository.ThreadDefinition childThread = new ConversationRepository.ThreadDefinition(
                childThreadId, parent.workspaceId(), command.taskName(),
                preferences.withTitleSource(ThreadPreferences.TitleSource.MANUAL), now);
        TaskModels.ChildAdmission admission = new TaskModels.ChildAdmission(childThread,
                command.parentThreadId(), parent.revision(), command.parentTurnId(), command.taskName(),
                TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT, seed, id("activity_"),
                summary("已创建 " + command.taskName()));
        TaskModels.Summary created = tasks.admitIdleChild(admission);
        try {
            publishLatest(created);
        } catch (RuntimeException failure) {
            LOGGER.warn("event=task_activity_publish_failed task_thread_id={}",
                    created.lineage().taskThreadId(), failure);
        }
        return created;
    }

    /** 用户侧边任务可独立选择完整执行偏好；titleSource 仍由服务端固定为人工来源。 */
    private static ThreadPreferences sideTaskPreferences(ThreadPreferences parent,
                                                         TaskUseCase.CreatePreferences override) {
        Objects.requireNonNull(parent, "parent preferences");
        if (override == null) return parent.withTitleSource(ThreadPreferences.TitleSource.MANUAL);
        return new ThreadPreferences(override.providerId(), override.modelId(), override.reasoningLevel(),
                override.accessMode(), override.collaborationMode(), ThreadPreferences.TitleSource.MANUAL);
    }

    /** Side Task 将用户本次选择记录进冻结 seed，供后续 Turn 解析其创建时执行偏好。 */
    private static JsonObject accessCeiling(AccessMode accessMode) {
        return JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", accessMode == AccessMode.APPROVAL_REQUIRED
                        ? "approval_required" : "full_access").build();
    }

    /** Subagent 只保存 brief 与显式引用，运行偏好取创建它的父 Turn 冻结值而不是父 Thread 后续值。 */
    @Override
    public StartResult spawnAgent(SpawnCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        requireSubagentsEnabled(command.parentThreadId());
        ThreadSummary parent = requireParent(command.parentThreadId(), null);
        parent = new ThreadSummary(parent.threadId(), parent.workspaceId(), parent.title(),
                command.frozenPreferences(), parent.status(), parent.pinned(), parent.latestTurnStatus(),
                parent.latestTurnSeen(), parent.activeGoalId(), parent.revision(), parent.createdAt(),
                parent.updatedAt());
        return startNew(command.parentThreadId(), command.parentTurnId(), command.taskName(), command.brief(),
                command.deadline(), parent, TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                TaskModels.InheritanceMode.BRIEF_ONLY, null, references(command.brief()),
                command.capabilityCeiling());
    }

    /** Java Task 入口再次校验冻结策略，防止隐藏调用绕过 Tool 列表直接创建 Subagent。 */
    private void requireSubagentsEnabled(String threadId) {
        SubagentPolicy policy = subagentPolicies.find(threadId).orElseThrow(() ->
                new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "subagent policy is unavailable"));
        if (!policy.enabled()) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.PERMISSION_DENIED,
                    "subagents are disabled for this Thread");
        }
    }

    /** Child 创建严格遵循 reserve → 单事务 admission → submit，完成回调只读取已提交终态。 */
    private StartResult startNew(String parentThreadId, String parentTurnId, String taskName, UserContent content,
                                 Duration deadline, ThreadSummary parent, TaskModels.Kind kind,
                                 TaskModels.Lifecycle lifecycle, TaskModels.InheritanceMode inheritance,
                                 JsonObject effectiveContext, JsonArray references, JsonObject permissionCeiling) {
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
        ConversationRepository.ThreadDefinition childThread = new ConversationRepository.ThreadDefinition(
                childThreadId, parent.workspaceId(), taskName, preferences, now);
        TaskModels.ContextSeedDraft seed = new TaskModels.ContextSeedDraft(seedId, parentThreadId, parentTurnId,
                parent.revision(), inheritance, content, effectiveContext, references, permissionCeiling, now);
        TaskModels.ChildAdmission child = new TaskModels.ChildAdmission(childThread, parentThreadId,
                parent.revision(), parentTurnId, taskName, kind, lifecycle, seed, activityId,
                summary("已派发 " + taskName));
        TurnStartRequest request = new TurnStartRequest(childThreadId, turnId, parent.workspaceId(), workspace.root(),
                content, preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                preferences.accessMode(), preferences.collaborationMode(), deadline, 0, 0, now);
        TurnEventBinding events = prepareTurn(request);
        TurnUseCase.Accepted accepted;
        boolean admitted = false;
        try {
            accepted = scheduler.startChild(request, events.turnEvents(),
                    admission -> childAdmissionReceipt(tasks.admitChild(child,
                            repositoryAdmission(admission))));
            admitted = true;
        } finally {
            if (!admitted) events.abandon();
        }
        if (!accepted.turnId().equals(turnId)) events.abandon();
        TaskModels.Summary task = requireTask(childThreadId);
        publishLatest(task);
        observeCompletion(task, accepted);
        return new StartResult(task, turnId);
    }

    /** Task application 在调用自身出站仓储前显式转换调度 DTO，保持 conversation 入站端口纯净。 */
    private static ConversationRepository.TurnAdmission repositoryAdmission(
            ChildTurnScheduler.AdmissionRequest admission) {
        return new ConversationRepository.TurnAdmission(admission.threadId(), admission.turnId(),
                admission.messageId(), admission.userMessage(), admission.attachmentIds(),
                admission.expectedThreadRevision(), admission.requestedAt(), admission.initialExecution());
    }

    /** Task admission 回执只向调度器返回继续运行所需的稳定身份与版本。 */
    private static ChildTurnScheduler.AdmissionReceipt childAdmissionReceipt(
            ConversationRepository.AdmissionReceipt receipt) {
        return new ChildTurnScheduler.AdmissionReceipt(receipt.threadId(), receipt.turnId(),
                receipt.threadRevision(), receipt.turnMutationVersion(), receipt.provisionalTitle());
    }

    /** 根 Thread 自身没有 lineage；Child 的 root 由现有投影读取，避免按 ID 或深度猜测。 */
    private String rootThread(String threadId) {
        return tasks.findTask(threadId).map(value -> value.lineage().rootThreadId()).orElse(threadId);
    }

    /** 完成阶段在终态事务与 Turn 通知之后更新活动数、唤醒 waiter，并发布最新持久 Activity。 */
    private void observeCompletion(TaskModels.Summary initial, TurnUseCase.Accepted accepted) {
        if (!accepted.queued()) return;
        acceptedTaskTurns.compute(initial.lineage().taskThreadId(), (ignored, count) -> {
            AtomicInteger result = count == null ? new AtomicInteger() : count;
            result.incrementAndGet();
            return result;
        });
        logActiveTaskCount();
        accepted.completion().whenComplete((ignored, failure) -> {
            if (closed.get()) return;
            acceptedTaskTurns.computeIfPresent(initial.lineage().taskThreadId(), (key, count) ->
                    count.decrementAndGet() == 0 ? null : count);
            logActiveTaskCount();
            tasks.findTask(initial.lineage().taskThreadId()).ifPresent(this::publishLatest);
        });
    }

    /**
     * 启动事务提交后只按原身份重接纳从未开始的 QUEUED Turn；同 Thread 串行推进，确保后继不会
     * 越过现有 resume 的最早非终态门，曾运行或等待审批的 Turn 不会进入此入口。
     */
    public void resumeRecoveredQueued(List<RecoveredQueuedTurn> recovered) {
        requireOpen();
        Map<String, List<RecoveredQueuedTurn>> byThread = new LinkedHashMap<>();
        for (RecoveredQueuedTurn queued : List.copyOf(recovered)) {
            byThread.computeIfAbsent(queued.threadId(), ignored -> new ArrayList<>()).add(queued);
        }
        byThread.values().forEach(queue -> resumeRecoveredNext(List.copyOf(queue), 0));
    }

    /** 前一 Turn 完成后才接纳同 Thread 后继；重接纳失败保留当前及后继 SUSPENDED 供用户处理。 */
    private void resumeRecoveredNext(List<RecoveredQueuedTurn> queue, int index) {
        if (closed.get() || index >= queue.size()) return;
        RecoveredQueuedTurn queued = queue.get(index);
        ThreadSnapshot thread = requireThread(queued.threadId());
        TurnEventBinding events = prepareRecoveredTurn(thread, queued.turnId());
        TurnUseCase.Accepted accepted;
        try {
            accepted = turns.resume(queued.turnId(), thread.thread().revision(), events.turnEvents());
        } catch (RuntimeException failure) {
            events.abandon();
            return;
        }
        tasks.findTask(queued.threadId()).ifPresent(task -> observeCompletion(task, accepted));
        accepted.completion().whenComplete((ignored, failure) -> resumeRecoveredNext(queue, index + 1));
    }

    /** Root Turn 使用 noop Timeline；Child Turn 仍绑定可选 UI Timeline 与始终持久化的低频投影。 */
    private TurnEventBinding prepareRecoveredTurn(ThreadSnapshot thread, String turnId) {
        TaskModels.Summary task = tasks.findTask(thread.thread().threadId()).orElse(null);
        if (task == null) return new TurnEventBinding(TaskEventSink.noop(), turnId,
                event -> CompletableFuture.completedFuture(null));
        TaskEventSink sink = subscriber.get();
        if (sink == null) sink = TaskEventSink.noop();
        else sink.registerTurn(turnId, thread.thread().workspaceId(), thread.thread().threadId(),
                    thread.thread().revision());
        TaskEventSink bound = sink;
        return new TurnEventBinding(sink, turnId, event -> {
            bound.turnEvents().publish(event).toCompletableFuture().join();
            projectTurnEvent(task.lineage().taskThreadId(), event);
            return CompletableFuture.completedFuture(null);
        });
    }

    /** 列表只消费 projection，不读取 Child messages 或 Timeline。 */
    @Override
    public List<TaskModels.Summary> listTree(String rootThreadId) {
        requireOpen();
        List<TaskModels.Summary> result = tasks.listTree(rootThreadId);
        if (result.size() > MAX_TREE_TASKS) throw new IllegalStateException("task tree exceeded hard limit");
        return result;
    }

    /** 主 Timeline 只经有界 Activity 投影读取持久事实，禁止借此物化 Child Transcript。 */
    @Override
    public List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit) {
        requireOpen();
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("invalid root activity limit");
        return tasks.listRootActivities(rootThreadId, limit);
    }

    /** 详情读取保持 caller cursor，隐藏详情不会触发该方法。 */
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
     * 从而让重启、压缩和 Goal/Plan continuation 都不会继续使用已过期的任务关系。
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

    /** Observation 只保存在当前进程且按 Task 唯一，重复打开复用句柄以避免高频订阅翻倍。 */
    @Override
    public Observation observe(String taskThreadId, long expectedTaskRevision) {
        requireOpen();
        TaskModels.Summary task = requireTask(taskThreadId);
        if (task.projection().revision() != expectedTaskRevision) throw conflict();
        ObservationState state = observations.compute(taskThreadId, (ignored, current) -> current == null
                ? new ObservationState(id("observe_"), taskThreadId, expectedTaskRevision)
                : current);
        return new Observation(state.observationId(), state.taskThreadId(), task.projection().revision());
    }

    /** 未知 observation 不可静默成功，否则前端会误以为旧高频流已经解除。 */
    @Override
    public void unobserve(String observationId) {
        requireOpen();
        boolean removed = observations.entrySet().removeIf(entry -> entry.getValue().observationId().equals(observationId));
        if (!removed) throw new TaskRepositoryException(TaskRepositoryException.Code.OBSERVATION_INVALID,
                "task observation is unavailable");
    }

    /** 已读 CAS 提交后发布完整摘要，UI 无需猜测 unread decrement。 */
    @Override
    public TaskModels.Summary markSeen(String taskThreadId, long expectedTaskRevision,
                                       long throughActivitySequence) {
        requireOpen();
        return tasks.markSeen(taskThreadId, expectedTaskRevision, throughActivitySequence, clock.instant());
    }

    /** QueueOnly 只写跨会话 Mailbox；不存在 Turn、Task Activity 或父未读投影副作用。 */
    @Override
    public MessageReceipt sendMessage(MessageCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        TaskModels.MailboxEnvelope envelope = envelope(command, TaskModels.MailboxKind.MESSAGE, now);
        TaskModels.MessageEnqueueReceipt receipt = tasks.enqueueMessage(envelope);
        return new MessageReceipt(receipt.mailbox().messageId(), receipt.mailbox().sequence());
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
        if (before.projection().revision() != command.expectedTaskRevision()) throw conflict();
        ThreadSnapshot child = requireThread(before.lineage().taskThreadId());
        Workspace workspace = workspaces.requireOpenWorkspace(child.thread().workspaceId());
        String turnId = id("turn_task_");
        TurnStartRequest request = request(child.thread(), workspace, turnId, command.message().content(),
                command.deadline(), now);
        TurnEventBinding events = prepareTurn(request);
        AtomicReference<TaskModels.FollowUpAdmissionReceipt> persisted = new AtomicReference<>();
        TurnUseCase.Accepted accepted;
        boolean admitted = false;
        try {
            accepted = scheduler.startChild(request, events.turnEvents(), admission -> {
                TaskModels.FollowUpAdmissionReceipt receipt = tasks.admitFollowUp(
                        new TaskModels.FollowUpAdmission(envelope, repositoryAdmission(admission),
                                command.expectedTaskRevision(), id("activity_"), summary("已追加后续任务")));
                persisted.set(receipt);
                return childAdmissionReceipt(receipt.admission());
            });
            admitted = true;
        } finally {
            if (!admitted) events.abandon();
        }
        TaskModels.FollowUpAdmissionReceipt receipt = Objects.requireNonNull(persisted.get(),
                "follow-up admission receipt");
        if (!accepted.turnId().equals(turnId)) events.abandon();
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

    /** 取消先校验 Task CAS，再使用 Child Thread 自身 revision 请求 Turn 取消。 */
    @Override
    public TaskModels.Summary cancel(String taskThreadId, long expectedTaskRevision) {
        requireOpen();
        TaskModels.Summary task = requireTask(taskThreadId);
        if (task.projection().revision() != expectedTaskRevision) throw conflict();
        cancelAllTurns(taskThreadId);
        cancelAttachedDescendants(taskThreadId);
        return requireTask(taskThreadId);
    }

    /**
     * 关闭只适用于独立侧聊：先以持久闸门拒绝迟到准入，再取消整棵临时树并停止 owner 的
     * Goal/Plan，确认所有 Turn 终态后才 purge；失败保留 closing 事实供原标签重试。
     */
    @Override
    public void closeSideChat(String taskThreadId) {
        requireOpen();
        Objects.requireNonNull(taskThreadId, "taskThreadId");
        runSideChatClose(taskThreadId);
    }

    /**
     * 在 TurnService 停止接纳前收口全部临时侧聊；该前置阶段不关闭 TaskCoordinator，
     * 因为 Turn 取消回调仍需通过本协调器传播已提交的 Child 取消事实。
     */
    @Override
    public void closeTemporarySideChats(long shutdownDeadlineNanos) {
        if (shutdownDeadlineNanos == Long.MIN_VALUE) {
            throw new IllegalArgumentException("invalid side chat shutdown deadline");
        }
        shutdownRequested.set(true);
        if (!sideChatShutdownAttempted.compareAndSet(false, true)) {
            RuntimeException previous = sideChatShutdownFailure;
            if (previous != null) throw previous;
            return;
        }
        RuntimeException failure = null;
        try {
            for (String taskThreadId : tasks.listTemporarySideChats().stream()
                    .map(TaskRepository.TemporarySideChat::threadId).toList()) {
                try {
                    runSideChatClose(taskThreadId, shutdownDeadlineNanos);
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
        } catch (RuntimeException listFailure) {
            failure = listFailure;
        }
        sideChatShutdownFailure = failure;
        if (failure != null) throw failure;
    }

    /**
     * 同一侧聊的并发关闭共享一个 Future，避免两个调用者分别取消、purge 或清理同一组租约；
     * 失败只解除内存中的 single-flight，让后续显式重试重新读取持久 closing 状态。
     */
    private void runSideChatClose(String taskThreadId) {
        runSideChatClose(taskThreadId, System.nanoTime() + SIDE_CHAT_CLOSE_TIMEOUT.toNanos());
    }

    /**
     * 关闭单棵临时树时沿用调用方的绝对截止线，保证应用退出的全局预算不会被每个侧聊重复放大。
     */
    private void runSideChatClose(String taskThreadId, long shutdownDeadlineNanos) {
        CompletableFuture<Void> candidate = new CompletableFuture<>();
        CompletableFuture<Void> current = sideChatClosures.putIfAbsent(taskThreadId, candidate);
        if (current != null) {
            awaitClose(current);
            return;
        }
        closingSideChats.add(taskThreadId);
        try {
            closeSideChatOnce(taskThreadId, shutdownDeadlineNanos);
            closingSideChats.remove(taskThreadId);
            candidate.complete(null);
        } catch (RuntimeException failure) {
            // 持久 closing 标记刻意不回滚；它是拒绝迟到输入和后续重试的安全边界。
            candidate.completeExceptionally(failure);
            throw failure;
        } finally {
            sideChatClosures.remove(taskThreadId, candidate);
        }
    }

    /**
     * 单次关闭严格遵循 begin -> cancel -> owner stop -> terminal barrier -> purge -> memory cleanup，
     * 因而 purge 成功回执不会掩盖仍运行的 Provider、交互恢复或子任务。
     */
    private void closeSideChatOnce(String taskThreadId, long shutdownDeadlineNanos) {
        TaskModels.Summary root = tasks.findTask(taskThreadId).orElse(null);
        if (root == null) {
            if (threads.readThread(taskThreadId, null, 1).isPresent()) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                        "only independent side chats can be closed");
            }
            List<String> returned = beginSideChatClose(taskThreadId);
            if (!returned.isEmpty()) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "side chat close returned a tree without a root task");
            }
            cleanupClosedSideChat(Set.of(taskThreadId));
            return;
        }
        if (root.lineage().kind() != TaskModels.Kind.SIDE_TASK
                || root.lineage().lifecycle() != TaskModels.Lifecycle.INDEPENDENT) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                    "only independent side chats can be closed");
        }
        List<String> returned = beginSideChatClose(taskThreadId);
        if (returned.isEmpty()) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID,
                    "temporary side chat marker is unavailable");
        }
        Set<String> subtree = sideChatTree(taskThreadId, returned);
        long deadline = Math.min(shutdownDeadlineNanos,
                System.nanoTime() + SIDE_CHAT_CLOSE_TIMEOUT.toNanos());
        // 先停止续跑 owner，再等待其 Turn 终态；反向顺序会让取消与 Goal 的推进/挂起争夺同一轮次。
        sideChatOwnerController.get().stopOwners(subtree);
        cancelSideChatTurns(subtree, deadline);
        awaitSideChatTurns(subtree, deadline);
        tasks.deleteClosedSideChat(taskThreadId);
        cleanupClosedSideChat(subtree);
    }

    /**
     * 仓储返回的树身份是关闭事务的权威结果；本地补入根 ID 只为确保根自身的 Turn、观察和 waiter
     * 不会因实现漏返而遗留，所有外部身份仍由仓储校验。
     */
    private static Set<String> sideChatTree(String taskThreadId, List<String> returned) {
        Set<String> result = new LinkedHashSet<>();
        result.add(taskThreadId);
        for (String value : List.copyOf(Objects.requireNonNull(returned, "side chat subtree"))) {
            if (value == null || value.isBlank() || !value.startsWith("thr_")) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "side chat close returned invalid task identity");
            }
            result.add(value);
        }
        return Set.copyOf(result);
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
                if (snapshot == null) continue;
                Optional<ThreadSnapshot.Turn> pending = snapshot.turns().stream()
                        .filter(turn -> !terminal(turn.status())).findFirst();
                if (pending.isEmpty()) continue;
                found = true;
                try {
                    turns.cancel(pending.orElseThrow().turnId(), snapshot.thread().revision());
                } catch (TurnUseCase.TurnCancellationException race) {
                    if (race.failure() != TurnUseCase.CancelFailure.CONFLICT
                            && race.failure() != TurnUseCase.CancelFailure.TURN_NOT_FOUND) throw race;
                }
                if (expired(deadlineNanos)) throw closeTimeout();
            }
            if (!found) return;
            if (expired(deadlineNanos)) throw closeTimeout();
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
            if (!pending) return;
            if (expired(deadlineNanos)) throw closeTimeout();
            LockSupport.parkNanos(SIDE_CHAT_CLOSE_POLL_NANOS);
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                        "side chat close was interrupted");
            }
        }
    }

    /** 关闭成功后才释放观察、活动计数和 waiter；失败重试仍可读取原标签所需的本地状态。 */
    private void cleanupClosedSideChat(Set<String> subtree) {
        subtree.forEach(id -> {
            observations.remove(id);
            acceptedTaskTurns.remove(id);
        });
        waiters.entrySet().removeIf(entry -> {
            if (entry.getValue().targets().stream().noneMatch(subtree::contains)) return false;
            entry.getValue().completion().completeExceptionally(
                    new CancellationException("side chat closed"));
            return true;
        });
    }

    /** begin 的默认失败必须明确传播，防止没有持久 closing 闸门时误删临时树。 */
    private List<String> beginSideChatClose(String taskThreadId) {
        try {
            return List.copyOf(tasks.beginSideChatClose(taskThreadId));
        } catch (TaskRepositoryException missing) {
            if (missing.code() == TaskRepositoryException.Code.NOT_FOUND) return List.of();
            throw missing;
        }
    }

    /** 并发调用复用相同错误；调用方显式再次点击关闭时才会重新尝试。 */
    private static void awaitClose(CompletableFuture<Void> completion) {
        try {
            completion.join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    /** 单调时间只用于关闭预算，不受 Clock.fixed 测试时钟或系统校时影响。 */
    private static boolean expired(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    /** 超时保留持久 closing 闸门，调用者可安全重试而不会再次接纳输入。 */
    private static TaskRepositoryException closeTimeout() {
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                "side chat turns did not settle before close deadline");
    }

    /** Agent 入口先证明真实委派关系，再复用用户取消的完整状态机。 */
    @Override
    public TaskModels.Summary cancelFrom(String requesterThreadId, String taskThreadId,
                                         long expectedTaskRevision) {
        requireDelegatedTargets(requesterThreadId, Set.of(taskThreadId));
        return cancel(taskThreadId, expectedTaskRevision);
    }

    /** 每次取消后重读 Thread revision，令 active 与全部 queued Turn 都经各自持久 CAS 收敛。 */
    private void cancelAllTurns(String taskThreadId) {
        while (true) {
            ThreadSnapshot snapshot = requireThread(taskThreadId);
            var pending = snapshot.turns().stream().filter(turn -> !terminal(turn.status()))
                    .reduce((left, right) -> right);
            if (pending.isEmpty()) return;
            try {
                turns.cancel(pending.orElseThrow().turnId(), snapshot.thread().revision());
            } catch (TurnUseCase.TurnCancellationException race) {
                if (race.failure() != TurnUseCase.CancelFailure.CONFLICT
                        && race.failure() != TurnUseCase.CancelFailure.TURN_NOT_FOUND) throw race;
            }
        }
    }

    /** ATTACHED 后代按深度从叶到根取消，并重复快照直到并发派生不再留下非终态 Turn。 */
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
            if (!found) return;
        }
    }

    /** 取消传播只依赖持久 Turn 状态，Task projection 的 sibling revision 变化不会影响判断。 */
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
        if (closed.get()) return;
        tasks.attachedDescendants(parentThreadId).stream()
                .filter(task -> task.lineage().parentThreadId().equals(parentThreadId))
                .filter(task -> Objects.equals(task.lineage().originTurnId(), parentTurnId))
                .forEach(task -> cancelAllTurns(task.lineage().taskThreadId()));
    }

    /** 绑定监听器时从 SQLite 父取消事实恢复传播；查询已排除 INDEPENDENT 侧边任务。 */
    @Override
    public void reconcilePending() {
        requireOpen();
        for (TaskModels.CancellationPropagation pending : tasks.pendingCancellationPropagations()) {
            cancellationClaimed(pending.parentThreadId(), pending.parentTurnId());
        }
    }

    /** 删除必须重复精确身份；Repository 负责原子核对整树无活动 Turn。 */
    @Override
    public int deleteTree(String taskThreadId, long expectedTaskRevision, String confirmTaskThreadId) {
        requireOpen();
        if (!Objects.equals(taskThreadId, confirmTaskThreadId)) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.TREE_DELETE_REQUIRED,
                    "task tree deletion requires exact confirmation");
        }
        return tasks.deleteTree(taskThreadId, expectedTaskRevision, clock.instant());
    }

    /** Wait 首先返回已经终态/需处理的目标，否则只登记事件驱动 waiter 与有界超时。 */
    @Override
    public CompletionStage<WaitResult> waitAgents(Set<String> taskThreadIds, Duration timeout,
                                                  CancellationToken cancellation) {
        requireOpen();
        Set<String> targets = Set.copyOf(Objects.requireNonNull(taskThreadIds, "taskThreadIds"));
        if (targets.isEmpty() || targets.size() > 8 || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("invalid task wait request");
        }
        List<TaskModels.Summary> ready = readyTargets(targets);
        if (!ready.isEmpty()) return CompletableFuture.completedFuture(new WaitResult(ready, false));
        String waiterId = id("waiter_");
        CompletableFuture<WaitResult> completion = new CompletableFuture<>();
        Waiter waiter = new Waiter(targets, completion);
        waiters.put(waiterId, waiter);
        var timeoutTask = timeouts.schedule(() -> completion.complete(new WaitResult(List.of(), true)),
                timeout.toMillis(), TimeUnit.MILLISECONDS);
        // Registration 跨越当前栈帧，由 completion 的唯一终结回调关闭，不能使用 try-with-resources。
        @SuppressWarnings("PMD.CloseResource")
        CancellationToken.Registration registration = cancellation.onCancellation(() ->
                completion.completeExceptionally(new CancellationException("parent turn cancelled")));
        completion.whenComplete((ignored, failure) -> {
            waiters.remove(waiterId, waiter);
            timeoutTask.cancel(false);
            registration.close();
        });
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

    /** 先验证所有目标都属于调用者的委派链，再登记 waiter，避免旁支目标留下观察句柄。 */
    @Override
    public CompletionStage<WaitResult> waitAgentsFrom(String requesterThreadId, Set<String> taskThreadIds,
                                                      Duration timeout, CancellationToken cancellation) {
        requireDelegatedTargets(requesterThreadId, taskThreadIds);
        return waitAgents(taskThreadIds, timeout, cancellation);
    }

    /** 单运行连接订阅 Task 与 Child Timeline；关闭显示订阅不触碰持久 Task。 */
    @Override
    public AutoCloseable subscribe(TaskEventSink sink) {
        requireOpen();
        Objects.requireNonNull(sink, "sink");
        if (!subscriber.compareAndSet(null, sink)) throw new IllegalStateException("task events already subscribed");
        return () -> subscriber.compareAndSet(sink, null);
    }

    /**
     * 进程退出先枚举并收口全部临时侧聊，再释放本地观察资源；失败继续清理其它侧聊并向生命周期
     * owner 报错，避免把未 purge 的临时会话误报为正常关闭。
     */
    @Override
    public void close() {
        if (!closeStarted.compareAndSet(false, true)) return;
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
        if (failure != null) throw failure;
    }

    /** 侧聊状态仅服务自身可见详情；没有观察者时不向来源会话广播活动，子任务等待仍独立结算。 */
    private void publishLatest(TaskModels.Summary task) {
        if (task.lineage().kind() == TaskModels.Kind.SIDE_TASK
                && !observations.containsKey(task.lineage().taskThreadId())) {
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

    /** Durable 事件要求连接背压；无活动连接时只保留 SQLite 事实供下次 task/list 重读。 */
    private void publish(TaskEvent event) {
        TaskEventSink sink = subscriber.get();
        if (sink != null) sink.publish(event).toCompletableFuture().join();
    }

    /** 在 Child 可能产生首个 delta 前固定连接关联，并把失败清理绑定到同一个 sink 实例。 */
    private TurnEventBinding prepareTurn(TurnStartRequest request) {
        TaskEventSink sink = subscriber.get();
        if (sink == null) sink = TaskEventSink.noop();
        else sink.registerTurn(request.turnId(), request.workspaceId(), request.threadId(),
                    request.expectedThreadRevision());
        TaskEventSink bound = sink;
        return new TurnEventBinding(sink, request.turnId(), event -> {
            bound.turnEvents().publish(event).toCompletableFuture().join();
            projectTurnEvent(request.threadId(), event);
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Child Turn 的低频生命周期进入 Task projection；流式 delta 只给当前 observation，且永不持久化。
     */
    private void projectTurnEvent(String taskThreadId, io.github.kongweiguang.ja.conversation.port.in.TurnEvent event) {
        switch (event) {
            case io.github.kongweiguang.ja.conversation.port.in.TurnEvent.StateChanged state
                    when state.to() == TurnState.RUNNING -> recordTurnActivity(taskThreadId,
                    TaskModels.State.RUNNING, TaskModels.ActivityKind.RESUMED, state.context().turnId(), "正在运行");
            case io.github.kongweiguang.ja.conversation.port.in.TurnEvent.ApprovalRequested approval ->
                    recordTurnActivity(taskThreadId, TaskModels.State.WAITING_APPROVAL,
                            TaskModels.ActivityKind.WAITING_APPROVAL, approval.context().turnId(), "等待审批");
            case io.github.kongweiguang.ja.conversation.port.in.TurnEvent.ApprovalResolved resolved ->
                    recordTurnActivity(taskThreadId, TaskModels.State.RUNNING,
                            TaskModels.ActivityKind.RESUMED, resolved.context().turnId(), "审批已处理，继续运行");
            case io.github.kongweiguang.ja.conversation.port.in.TurnEvent.TextDelta delta ->
                    publishProgress(taskThreadId, delta.streamSeq(), delta.text());
            case io.github.kongweiguang.ja.conversation.port.in.TurnEvent.ReasoningSummaryDelta delta ->
                    publishProgress(taskThreadId, delta.streamSeq(), delta.text());
            default -> { }
        }
    }

    /** projection CAS 与 Activity 由 Repository 同事务提交，竞争时让下一权威事件或 task/read 收敛。 */
    private void recordTurnActivity(String taskThreadId, TaskModels.State state, TaskModels.ActivityKind kind,
                                    String turnId, String text) {
        for (int attempt = 0; attempt < 8; attempt++) {
            TaskModels.Summary current = requireTask(taskThreadId);
            if (current.projection().state() == state || terminal(current.projection().state())) return;
            try {
                TaskModels.Summary updated = tasks.recordActivity(new TaskModels.ActivityMutation(taskThreadId,
                        current.projection().revision(), state, id("activity_"), taskThreadId, turnId, kind,
                        summary(text), text, clock.instant()));
                publishLatest(updated);
                return;
            } catch (TaskRepositoryException conflict) {
                if (conflict.code() != TaskRepositoryException.Code.CAS_CONFLICT) throw conflict;
            }
        }
        LOGGER.warn("event=task_activity_projection_conflict retry_count=8 state={}", state.name());
    }

    /** observation 缺失时丢弃草稿；存在时只发布 4096 字符以内的既有脱敏展示文本。 */
    private void publishProgress(String taskThreadId, long progressRevision, String text) {
        ObservationState observation = observations.get(taskThreadId);
        if (observation == null) return;
        TaskModels.Summary task = requireTask(taskThreadId);
        String safe = text.length() <= 4_096 ? text : text.substring(0, 4_096);
        publish(new TaskEvent.Progress(context(task, clock.instant()), observation.observationId(),
                progressRevision, safe));
    }

    /**
     * 为不经过 Child scheduler 的 Goal/Plan hidden Turn 复用同一 Task 投影入口；下游 sink 先收到
     * 原始事件，保证既有 Timeline/审批路由顺序不变，Terminal 再回读已提交的最新 Activity。
     */
    public TurnEventSink projectContinuationEvents(String taskThreadId, TurnEventSink downstream) {
        requireOpen();
        Objects.requireNonNull(taskThreadId, "taskThreadId");
        Objects.requireNonNull(downstream, "downstream");
        AtomicReference<Boolean> taskProjection = new AtomicReference<>();
        return event -> {
            downstream.publish(event).toCompletableFuture().join();
            Boolean shouldProject = taskProjection.get();
            if (shouldProject == null) {
                shouldProject = tasks.findTask(taskThreadId).isPresent();
                taskProjection.compareAndSet(null, shouldProject);
            }
            if (!shouldProject) return CompletableFuture.completedFuture(null);
            projectTurnEvent(taskThreadId, event);
            if (event instanceof io.github.kongweiguang.ja.conversation.port.in.TurnEvent.Terminal) {
                tasks.findTask(taskThreadId).ifPresent(this::publishLatest);
            }
            return CompletableFuture.completedFuture(null);
        };
    }

    /** 只有匹配目标进入终态或 needs-attention 时完成 wait，普通 progress 不唤醒父模型。 */
    private void notifyWaiters(TaskModels.Summary task) {
        if (!ready(task)) return;
        waiters.values().forEach(waiter -> {
            if (waiter.targets().contains(task.lineage().taskThreadId())) {
                waiter.completion().complete(new WaitResult(List.of(task), false));
            }
        });
    }

    /** Immediate snapshot 与事件唤醒使用同一 ready 判定。 */
    private List<TaskModels.Summary> readyTargets(Set<String> targets) {
        List<TaskModels.Summary> ready = new ArrayList<>();
        for (String target : targets) {
            TaskModels.Summary task = requireTask(target);
            if (ready(task)) ready.add(task);
        }
        return List.copyOf(ready);
    }

    /** 空闲任务没有待完成的 Turn；与等待人工处理及终态一起立即交还控制，避免无谓等待。 */
    private static boolean ready(TaskModels.Summary task) {
        return switch (task.projection().state()) {
            case IDLE, WAITING_APPROVAL, SUSPENDED, COMPLETED, FAILED, CANCELLED -> true;
            case QUEUED, RUNNING -> false;
        };
    }

    /** Parent revision 可选校验只用于 user create；Agent spawn 读取当前 revision 后由 admission 再校验。 */
    private ThreadSummary requireParent(String threadId, Long expectedRevision) {
        ThreadSummary parent = requireThread(threadId).thread();
        if (expectedRevision != null && parent.revision() != expectedRevision) {
            throw new TaskRepositoryException(TaskRepositoryException.Code.CONTEXT_REVISION_CONFLICT,
                    "parent revision changed");
        }
        return parent;
    }

    /** Thread 读取 limit 只约束 Timeline items，Turn metadata 仍完整供取消选择。 */
    private ThreadSnapshot requireThread(String threadId) {
        return threads.readThread(threadId, null, 1)
                .orElseThrow(() -> new TaskRepositoryException(TaskRepositoryException.Code.NOT_FOUND,
                        "thread is unavailable"));
    }

    /** Repository summary 是右栏和事件唯一权威投影。 */
    private TaskModels.Summary requireTask(String taskThreadId) {
        return tasks.findTask(taskThreadId)
                .orElseThrow(() -> new TaskRepositoryException(TaskRepositoryException.Code.NOT_FOUND,
                        "task is unavailable"));
    }

    /** 通信可跨所有会话，控制权只沿真实委派边向下；共享上下文来源不能赋予取消或等待旁支的权限。 */
    private void requireDelegatedTargets(String requesterThreadId, Set<String> targetThreadIds) {
        Objects.requireNonNull(requesterThreadId, "requesterThreadId");
        Set<String> targets = Set.copyOf(Objects.requireNonNull(targetThreadIds, "targetThreadIds"));
        for (String targetThreadId : targets) {
            TaskModels.Summary current = requireTask(targetThreadId);
            boolean delegated = false;
            for (int depth = 0; current != null && depth < 4; depth++) {
                TaskModels.Lineage lineage = current.lineage();
                if (lineage.kind() != TaskModels.Kind.SUBAGENT
                        || lineage.lifecycle() != TaskModels.Lifecycle.ATTACHED) break;
                if (requesterThreadId.equals(lineage.parentThreadId())) {
                    delegated = true;
                    break;
                }
                if (lineage.parentThreadId().equals(lineage.rootThreadId())) break;
                current = tasks.findTask(lineage.parentThreadId()).orElse(null);
            }
            if (!delegated) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.PERMISSION_DENIED,
                        "task target is not delegated by the requester");
            }
        }
    }

    /** 活动计数只记录聚合值，不包含 Thread、任务名、prompt 或配置身份。 */
    private void logActiveTaskCount() {
        LOGGER.info("event=task_active_count active_count={}", acceptedTaskTurns.size());
    }

    /** Follow-up 继承目标 Child 当前偏好，每个 Turn 开始重新冻结配置。 */
    private static TurnStartRequest request(ThreadSummary child, Workspace workspace, String turnId,
                                            UserContent content, Duration deadline, Instant now) {
        ThreadPreferences preferences = Objects.requireNonNull(child.preferences(), "child preferences");
        return new TurnStartRequest(child.threadId(), turnId, child.workspaceId(), workspace.root(), content,
                preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                preferences.accessMode(), preferences.collaborationMode(), deadline, child.revision(), 0, now);
    }

    /** 明确引用单独进入 seed；不复制父 Timeline 或把附件内容内联进 JSON。 */
    private static JsonArray references(UserContent content) {
        List<JsonValue> values = new ArrayList<>();
        for (UserContentBlock block : content.blocks()) {
            if (block instanceof WorkspaceReferenceContent reference) {
                values.add(JsonObjects.builder().putText("kind", "workspace")
                        .putText("workspaceId", reference.workspaceId())
                        .putText("relativePath", reference.relativePath()).build());
            } else if (block instanceof SkillReferenceContent reference) {
                values.add(JsonObjects.builder().putText("kind", "skill")
                        .putText("skillId", reference.skillId()).build());
            } else if (block instanceof AttachmentContent reference) {
                values.add(JsonObjects.builder().putText("kind", "attachment")
                        .putText("attachmentId", reference.attachmentId()).build());
            }
        }
        return new JsonArray(values);
    }

    /** Mailbox envelope 的因果 Turn 由调用者显式提供，不从 active registry 猜测。 */
    private static TaskModels.MailboxEnvelope envelope(MessageCommand command, TaskModels.MailboxKind kind,
                                                       Instant now) {
        return new TaskModels.MailboxEnvelope(id("msg_"), command.senderThreadId(), command.targetThreadId(),
                command.causalTurnId(), kind, command.content(), command.idempotencyKey(), now);
    }

    /** Activity summary 只保存安全短文本，原 prompt 与思考内容不会进入父 Timeline。 */
    private static JsonObject summary(String text) {
        return JsonObjects.builder().putText("text", text).build();
    }

    /** 领域事件上下文直接取 Task 投影。 */
    private static TaskEvent.Context context(TaskModels.Summary task, Instant occurredAt) {
        return new TaskEvent.Context(task.lineage().rootThreadId(), task.lineage().taskThreadId(),
                task.projection().revision(), occurredAt);
    }

    /** History 的状态投影使用小写 wire 拼写；在领域边界显式归一后再使用终态闭集。 */
    private static boolean terminal(String status) {
        return TurnState.valueOf(status.toUpperCase(java.util.Locale.ROOT)).terminal();
    }

    /** Task 投影终态判断与持久状态闭集保持显式，不把需处理状态误判为完成。 */
    private static boolean terminal(TaskModels.State state) {
        return state == TaskModels.State.COMPLETED || state == TaskModels.State.FAILED
                || state == TaskModels.State.CANCELLED;
    }

    /** 保存一次 Child 通知登记；无 UI 连接时 sink 为 noop，但低频 Task 投影仍必须持久化。 */
    private record TurnEventBinding(TaskEventSink sink, String turnId,
                                    io.github.kongweiguang.ja.conversation.port.in.TurnEventSink projectedEvents) {
        /** Timeline sink 与清理动作必须来自同一订阅代际。 */
        private io.github.kongweiguang.ja.conversation.port.in.TurnEventSink turnEvents() {
            return projectedEvents;
        }

        /** 清理未运行的 requested Turn；终态运行项由 RpcSession 自动回收。 */
        private void abandon() {
            sink.abandonTurn(turnId);
        }
    }

    /** UUID 仅作为不透明身份熵，不携带时间、路径或用户输入。 */
    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    /** 所有 CAS 冲突使用稳定 Task 分类。 */
    private static TaskRepositoryException conflict() {
        return new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                "task revision changed");
    }

    /** 关闭请求一旦发布就拒绝新调用，但允许 Turn cancellation callback 继续消费持久事实。 */
    private void requireOpen() {
        if (closed.get() || shutdownRequested.get()) {
            throw new IllegalStateException(closed.get()
                    ? "task coordinator is closed" : "task coordinator is shutting down");
        }
    }

    /** 当前进程 observation 不持有 transcript 或 UI 组件引用。 */
    private record ObservationState(String observationId, String taskThreadId, long revision) { }

    /** Waiter 只保存最多八个稳定 ID 和一次性完成阶段。 */
    private record Waiter(Set<String> targets, CompletableFuture<WaitResult> completion) { }

    /** 组合根交入的原 QUEUED identity；Task application 不反向依赖具体恢复适配器。 */
    public record RecoveredQueuedTurn(String threadId, String turnId) {
        /** 身份前缀在进入 TurnService 前失败关闭，避免把恢复损坏伪装成普通不可恢复。 */
        public RecoveredQueuedTurn {
            if (threadId == null || !threadId.startsWith("thr_")
                    || turnId == null || !turnId.startsWith("turn_")) {
                throw new IllegalArgumentException("invalid recovered queued Turn identity");
            }
        }
    }
}
