// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.application;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnCancellationListener;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
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

/**
 * Child Thread 的唯一应用层协调器；持久化、Turn 调度与 UI 观察通过窄端口组合而不建立第二运行时。
 */
public final class TaskCoordinator implements TaskUseCase, TurnCancellationListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskCoordinator.class);
    private static final int MAX_TREE_TASKS = 64;
    private final TaskRepository tasks;
    private final ThreadUseCase threads;
    private final TurnUseCase turns;
    private final ChildTurnScheduler scheduler;
    private final WorkspaceUseCase workspaces;
    private final Clock clock;
    private final AtomicReference<TaskEventSink> subscriber = new AtomicReference<>();
    private final Map<String, ObservationState> observations = new ConcurrentHashMap<>();
    private final Map<String, Waiter> waiters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> acceptedTaskTurns = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ja-task-wait-timeout-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * TurnUseCase 与 ChildTurnScheduler 必须指向同一 TurnService，避免 Task 绕过普通取消和关闭闸门。
     */
    public TaskCoordinator(TaskRepository tasks, ThreadUseCase threads, TurnUseCase turns,
                           ChildTurnScheduler scheduler, WorkspaceUseCase workspaces, Clock clock) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.turns = Objects.requireNonNull(turns, "turns");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (turns != scheduler) throw new IllegalArgumentException("Task scheduler must share Turn owner");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 创建时先冻结 parent revision 的真实有效上下文，admission 事务会再次校验该 revision。 */
    @Override
    public StartResult createSideTask(CreateCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        ThreadSummary parent = requireParent(command.parentThreadId(), command.expectedParentRevision());
        TaskModels.EffectiveContextSnapshot context = tasks.freezeEffectiveContext(
                command.parentThreadId(), command.expectedParentRevision());
        return startNew(command.parentThreadId(), command.parentTurnId(), command.taskName(), command.content(),
                command.deadline(), parent, TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT,
                TaskModels.InheritanceMode.EFFECTIVE_CONTEXT, context.context(), context.references(),
                context.permissionCeiling());
    }

    /** Subagent 只保存 brief 与显式引用，运行偏好取创建它的父 Turn 冻结值而不是父 Thread 后续值。 */
    @Override
    public StartResult spawnAgent(SpawnCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
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

    /** QueueOnly 只写 Mailbox；不存在任何 Turn start 调用。 */
    @Override
    public MessageReceipt sendMessage(MessageCommand command) {
        requireOpen();
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        TaskModels.MailboxEnvelope envelope = envelope(command, TaskModels.MailboxKind.MESSAGE, now);
        TaskModels.MessageEnqueueReceipt receipt = tasks.enqueueMessage(
                envelope, id("activity_"), summary("收到新消息"));
        if (receipt.inserted()) {
            TaskModels.Summary owner = receipt.projectionOwner();
            publish(new TaskEvent.MailboxChanged(context(owner, now), receipt.mailbox().sequence(),
                    owner.projection().unreadCount()));
            publishLatest(owner);
        }
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

    /** Agent 入口先证明请求者与目标同根，再复用用户取消的完整状态机。 */
    @Override
    public TaskModels.Summary cancelFrom(String requesterThreadId, String taskThreadId,
                                         long expectedTaskRevision) {
        requireSameRoot(requesterThreadId, Set.of(taskThreadId));
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
        requireOpen();
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

    /** Agent 入口对全部等待目标做同根校验，之后才登记 waiter，避免越权目标留下内存句柄。 */
    @Override
    public CompletionStage<WaitResult> waitAgentsFrom(String requesterThreadId, Set<String> taskThreadIds,
                                                      Duration timeout, CancellationToken cancellation) {
        requireSameRoot(requesterThreadId, taskThreadIds);
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

    /** 关闭只释放内存观察资源和 waiters，持久 Task 由恢复服务接管。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        observations.clear();
        acceptedTaskTurns.clear();
        logActiveTaskCount();
        waiters.values().forEach(waiter -> waiter.completion().completeExceptionally(
                new CancellationException("task coordinator closed")));
        waiters.clear();
        subscriber.set(null);
        timeouts.shutdownNow();
    }

    /** 从最新 projection sequence 精确读取刚提交的 Activity，避免扫描整份详情。 */
    private void publishLatest(TaskModels.Summary task) {
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

    /** WAITING_APPROVAL/SUSPENDED 与三个终态均需要父 Agent 或用户处理。 */
    private static boolean ready(TaskModels.Summary task) {
        return switch (task.projection().state()) {
            case WAITING_APPROVAL, SUSPENDED, COMPLETED, FAILED, CANCELLED -> true;
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

    /** Agent 控制面只允许访问调用者所在根树；Root Thread 与任意层 Child 使用同一比较规则。 */
    private void requireSameRoot(String requesterThreadId, Set<String> targetThreadIds) {
        Objects.requireNonNull(requesterThreadId, "requesterThreadId");
        Set<String> targets = Set.copyOf(Objects.requireNonNull(targetThreadIds, "targetThreadIds"));
        String requesterRoot = rootThread(requesterThreadId);
        for (String targetThreadId : targets) {
            TaskModels.Summary target = requireTask(targetThreadId);
            if (!requesterRoot.equals(target.lineage().rootThreadId())) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.PERMISSION_DENIED,
                        "task target is outside the requester root");
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

    /** Turn status 来自持久闭集，未知值失败关闭。 */
    private static boolean terminal(String status) {
        return TurnState.valueOf(status).terminal();
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

    /** close 后拒绝新调用，但不改变数据库中的 Task。 */
    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("task coordinator is closed");
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
