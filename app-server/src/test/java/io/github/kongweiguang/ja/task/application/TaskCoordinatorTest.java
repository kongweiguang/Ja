// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.application;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.port.in.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.task.adapter.in.tools.TaskAgentToolGateway;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.task.port.in.TaskEvent;
import io.github.kongweiguang.ja.task.port.in.TaskEventSink;
import io.github.kongweiguang.ja.task.port.out.TaskRepository;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 TaskCoordinator 将冻结能力原样带入 Child admission，而不是重新推断权限。 */
final class TaskCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-03T08:00:00Z");
    private static final ThreadPreferences PREFERENCES = new ThreadPreferences(
            "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
            io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
            ThreadPreferences.TitleSource.MANUAL);

    /** Subagent seed 必须保存完整 ceiling，且 brief-only 模式不得意外复制有效上下文。 */
    @Test
    void spawnPersistsCompleteCapabilityCeilingInContextSeed() {
        JsonObject ceiling = capabilityCeiling(PREFERENCES, Set.of("skill_review"));
        AtomicReference<TaskModels.ChildAdmission> admitted = new AtomicReference<>();
        TaskRepository repository = repository(admitted);
        ThreadUseCase threads = threads();
        Object turnOwner = turnOwner();
        WorkspaceUseCase workspaces = workspaces();

        ListAppender<ILoggingEvent> logs = captureLogs();
        try (TaskCoordinator coordinator = new TaskCoordinator(repository, threads,
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, workspaces,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            coordinator.spawnAgent(new TaskUseCase.SpawnCommand(
                    "thr_parent", "turn_parent", "review",
                    new UserContent(List.of(new TextContent("review changes"))),
                        Duration.ofMinutes(5), PREFERENCES, ceiling));
        } finally {
            detachLogs(logs);
        }

        assertEquals(ceiling, admitted.get().contextSeed().permissionCeiling());
        assertEquals(TaskModels.InheritanceMode.BRIEF_ONLY,
                admitted.get().contextSeed().inheritanceMode());
        assertNull(admitted.get().contextSeed().effectiveContext());
        assertEquals(List.of("event=task_active_count active_count=1",
                        "event=task_active_count active_count=0"),
                logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.startsWith("event=task_active_count")).toList());
        assertEquals(false, logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("review changes") || message.contains("thr_parent")));
    }

    /**
     * 公开 Follow-up 原样重试必须在 stale revision 门前回放真实身份，内容碰撞也不能进入调度器。
     */
    @Test
    void replaysFollowUpBeforeRevisionValidationWithoutCreatingAnotherTurn() {
        AtomicReference<TaskModels.FollowUpAdmissionReceipt> persisted = new AtomicReference<>();
        AtomicInteger revision = new AtomicInteger(3);
        AtomicInteger admissions = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findFollowUpByIdempotency" -> {
                TaskModels.MailboxEnvelope requested = (TaskModels.MailboxEnvelope) args[0];
                TaskModels.FollowUpAdmissionReceipt existing = persisted.get();
                if (existing == null) yield Optional.empty();
                TaskModels.MailboxMessage message = existing.mailbox();
                if (!message.targetThreadId().equals(requested.targetThreadId())
                        || !message.causalTurnId().equals(requested.causalTurnId())
                        || message.kind() != requested.kind()
                        || !message.content().equals(requested.content())) {
                    throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT,
                            "fixture idempotency collision");
                }
                yield Optional.of(existing);
            }
            case "findTask" -> Optional.of(task("thr_target", "thr_parent",
                    TaskModels.State.QUEUED, revision.get()));
            case "readTask" -> Optional.empty();
            case "admitFollowUp" -> {
                TaskModels.FollowUpAdmission admission = (TaskModels.FollowUpAdmission) args[0];
                ConversationRepository.TurnAdmission turn = admission.turn();
                TaskModels.MailboxEnvelope mailbox = admission.mailbox();
                ConversationRepository.AdmissionReceipt turnReceipt = new ConversationRepository.AdmissionReceipt(
                        turn.threadId(), turn.turnId(), 6, 0, null);
                TaskModels.MailboxMessage message = new TaskModels.MailboxMessage(
                        1, mailbox.messageId(), "thr_parent", mailbox.senderThreadId(), mailbox.targetThreadId(),
                        mailbox.causalTurnId(), mailbox.kind(), mailbox.content(), mailbox.idempotencyKey(),
                        TaskModels.MailboxState.BOUND, turn.turnId(), mailbox.createdAt(), mailbox.createdAt(), null);
                TaskModels.FollowUpAdmissionReceipt receipt =
                        new TaskModels.FollowUpAdmissionReceipt(turnReceipt, message);
                persisted.set(receipt);
                admissions.incrementAndGet();
                revision.incrementAndGet();
                yield receipt;
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase childThreads = proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.of(threadWithTurns(5, List.of()));
            }
            throw new UnsupportedOperationException(method.getName());
        });
        AtomicInteger starts = new AtomicInteger();
        Object owner = turnOwner(starts);
        TaskUseCase.FollowUpCommand command = new TaskUseCase.FollowUpCommand(
                new TaskUseCase.MessageCommand("thr_parent", "thr_target",
                        new UserContent(List.of(new TextContent("继续完成"))),
                        "follow-public-retry", "turn_parent"), 3, Duration.ofMinutes(5));

        try (TaskCoordinator coordinator = new TaskCoordinator(repository, childThreads,
                (TurnUseCase) owner, (ChildTurnScheduler) owner, workspaces(),
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            TaskUseCase.FollowUpResult first = coordinator.followUp(command);
            TaskUseCase.FollowUpResult replay = coordinator.followUp(command);
            TaskRepositoryException collision = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.followUp(new TaskUseCase.FollowUpCommand(
                            new TaskUseCase.MessageCommand("thr_parent", "thr_target",
                                    new UserContent(List.of(new TextContent("不同内容"))),
                                    "follow-public-retry", "turn_parent"),
                            3, Duration.ofMinutes(5))));

            assertEquals(first.messageId(), replay.messageId());
            assertEquals(first.turnId(), replay.turnId());
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, collision.code());
        }

        assertEquals(1, starts.get());
        assertEquals(1, admissions.get());
    }

    /** Child 到 root 的 QueueOnly 重试复用 Repository 回执，且只有首次插入发布低频事件。 */
    @Test
    void publishesChildToRootMessageOnlyForInsertWinner() {
        TaskModels.Summary owner = task("thr_child", "thr_root", TaskModels.State.RUNNING, 4);
        TaskModels.MailboxMessage mailbox = new TaskModels.MailboxMessage(7, "msg_stored", "thr_root",
                "thr_child", "thr_root", "turn_child", TaskModels.MailboxKind.MESSAGE,
                new UserContent(List.of(new TextContent("阶段结果"))), "child-root-key",
                TaskModels.MailboxState.PENDING, null, NOW, NOW, null);
        AtomicInteger enqueues = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "enqueueMessage" -> new TaskModels.MessageEnqueueReceipt(
                    mailbox, owner, enqueues.incrementAndGet() == 1);
            case "readTask" -> Optional.empty();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        AtomicInteger events = new AtomicInteger();
        TaskEventSink sink = new TaskEventSink() {
            /** 只统计首次持久事实对应的 Task 事件。 */
            @Override public java.util.concurrent.CompletionStage<Void> publish(TaskEvent event) {
                events.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }

            /** 本测试不启动 Child Turn，Timeline sink 保持显式空实现。 */
            @Override public io.github.kongweiguang.ja.conversation.port.in.TurnEventSink turnEvents() {
                return event -> CompletableFuture.completedFuture(null);
            }
        };
        Object turnOwner = passiveTurnOwner();
        TaskUseCase.MessageCommand command = new TaskUseCase.MessageCommand(
                "thr_child", "thr_root", new UserContent(List.of(new TextContent("阶段结果"))),
                "child-root-key", "turn_child");

        try (TaskCoordinator coordinator = new TaskCoordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC)); AutoCloseable ignored = coordinator.subscribe(sink)) {
            TaskUseCase.MessageReceipt first = coordinator.sendMessage(command);
            TaskUseCase.MessageReceipt replay = coordinator.sendMessage(command);

            assertEquals(first, replay);
            assertEquals(1, events.get());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    /** 终态若发生在首次检查与 waiter 登记之间，登记后的二次检查必须立即补获而非超时。 */
    @Test
    void waitAgentClosesCheckThenRegisterLostWakeup() {
        AtomicInteger reads = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> Optional.of(task("thr_wait", "thr_root", reads.incrementAndGet() == 1
                    ? TaskModels.State.RUNNING : TaskModels.State.COMPLETED, 1));
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = new TaskCoordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            TaskUseCase.WaitResult result = coordinator.waitAgents(Set.of("thr_wait"), Duration.ofSeconds(1),
                    CancellationToken.none()).toCompletableFuture().join();

            assertEquals(List.of("thr_wait"), result.tasks().stream()
                    .map(value -> value.lineage().taskThreadId()).toList());
            assertEquals(false, result.timedOut());
        }
    }

    /** Agent wait/cancel 在应用边界拒绝跨 root 目标，不能依赖模型先正确调用 list_agents。 */
    @Test
    void rejectsCrossRootAgentControl() {
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> {
                String id = (String) args[0];
                yield Optional.of("thr_requester".equals(id)
                        ? task(id, "thr_root_a", TaskModels.State.RUNNING, 1)
                        : task(id, "thr_root_b", TaskModels.State.RUNNING, 1));
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = new TaskCoordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            TaskRepositoryException wait = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.waitAgentsFrom("thr_requester", Set.of("thr_other"),
                            Duration.ofSeconds(1), CancellationToken.none()));
            TaskRepositoryException cancel = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.cancelFrom("thr_requester", "thr_other", 1));

            assertEquals(TaskRepositoryException.Code.PERMISSION_DENIED, wait.code());
            assertEquals(TaskRepositoryException.Code.PERMISSION_DENIED, cancel.code());
        }
    }

    /** Task 取消逐次重读 Thread revision，并把运行中与所有排队 Turn 全部提交为取消终态。 */
    @Test
    void cancelsActiveAndQueuedTurnsWithFreshRevision() {
        AtomicReference<ThreadSnapshot> snapshot = new AtomicReference<>(threadWithTurns(5,
                List.of(turn("turn_active", "RUNNING"), turn("turn_queued", "QUEUED"))));
        AtomicInteger cancellations = new AtomicInteger();
        Object owner = Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if (!"cancel".equals(method.getName())) throw new UnsupportedOperationException(method.getName());
                    String turnId = (String) args[0];
                    long expectedRevision = (long) args[1];
                    ThreadSnapshot before = snapshot.get();
                    assertEquals(before.thread().revision(), expectedRevision);
                    long nextRevision = expectedRevision + 1;
                    List<ThreadSnapshot.Turn> turns = before.turns().stream().map(value ->
                            new ThreadSnapshot.Turn(value.turnId(), value.turnId().equals(turnId)
                                    ? "CANCELLED" : value.status(), value.requestedAt(), NOW,
                                    value.turnId().equals(turnId) ? NOW : value.completedAt(),
                                    value.errorCode(), value.changeSet())).toList();
                    snapshot.set(threadWithTurns(nextRevision, turns));
                    cancellations.incrementAndGet();
                    return new TurnUseCase.CancelResult(true, turnId,
                            io.github.kongweiguang.ja.conversation.domain.turn.TurnState.CANCELLED, nextRevision);
                });
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> Optional.of(task("thr_target", "thr_root", TaskModels.State.RUNNING, 3));
            case "attachedDescendants" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase threadUseCase = proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) return Optional.of(snapshot.get());
            throw new UnsupportedOperationException(method.getName());
        });
        try (TaskCoordinator coordinator = new TaskCoordinator(repository, threadUseCase,
                (TurnUseCase) owner, (ChildTurnScheduler) owner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            coordinator.cancel("thr_target", 3);
        }

        assertEquals(2, cancellations.get());
        assertEquals(List.of("CANCELLED", "CANCELLED"), snapshot.get().turns().stream()
                .map(ThreadSnapshot.Turn::status).toList());
    }

    /** 低频 Activity 的 sibling CAS 冲突只丢弃该展示投影，不能反向使已接纳 Child 启动失败。 */
    @Test
    void isolatesActivityProjectionCasConflictFromChildExecution() {
        AtomicReference<TaskModels.ChildAdmission> admitted = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTree" -> List.of();
            case "findTask" -> admitted.get() == null ? Optional.empty() : Optional.of(summary(admitted.get()));
            case "readTask" -> Optional.empty();
            case "admitChild" -> {
                TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                ConversationRepository.TurnAdmission turn = (ConversationRepository.TurnAdmission) args[1];
                admitted.set(child);
                yield new ConversationRepository.AdmissionReceipt(turn.threadId(), turn.turnId(), 1, 0, null);
            }
            case "recordActivity" -> {
                attempts.incrementAndGet();
                throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT, "fixture race");
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object owner = eventPublishingTurnOwner();
        try (TaskCoordinator coordinator = new TaskCoordinator(repository, threads(),
                (TurnUseCase) owner, (ChildTurnScheduler) owner, workspaces(),
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            TaskUseCase.StartResult result = coordinator.spawnAgent(new TaskUseCase.SpawnCommand(
                    "thr_parent", "turn_parent", "review", new UserContent(List.of(new TextContent("review"))),
                    Duration.ofMinutes(5), PREFERENCES, capabilityCeiling(PREFERENCES, Set.of())));

            assertEquals("thr_parent", result.task().lineage().rootThreadId());
            assertEquals(8, attempts.get());
        }
    }

    /** Repository fake 只实现 spawn 路径，并从实际 admission 构造后续权威投影。 */
    private static TaskRepository repository(AtomicReference<TaskModels.ChildAdmission> admitted) {
        return proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTree" -> List.of();
            case "findTask" -> {
                TaskModels.ChildAdmission child = admitted.get();
                yield child == null || !child.childThread().threadId().equals(args[0])
                        ? Optional.empty() : Optional.of(summary(child));
            }
            case "readTask" -> Optional.empty();
            case "admitChild" -> {
                TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                ConversationRepository.TurnAdmission turn = (ConversationRepository.TurnAdmission) args[1];
                admitted.set(child);
                yield new ConversationRepository.AdmissionReceipt(turn.threadId(), turn.turnId(), 1, 0, null);
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** Thread fake 返回创建 Subagent 所需的父元数据，不提供历史正文。 */
    private static ThreadUseCase threads() {
        ThreadSummary parent = new ThreadSummary("thr_parent", "ws_test", "Parent", PREFERENCES,
                ThreadSummary.Status.ACTIVE, false, null, true, null, 7, NOW, NOW);
        ThreadSnapshot snapshot = new ThreadSnapshot(parent, List.of(), List.of(), null, null, null);
        return proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) return Optional.of(snapshot);
            throw new UnsupportedOperationException(method.getName());
        });
    }

    /** 同一代理同时实现 TurnUseCase 与 ChildTurnScheduler，复现生产组合的单一队列 owner 约束。 */
    private static Object turnOwner() {
        return turnOwner(new AtomicInteger());
    }

    /** 带计数版本用于证明公开幂等重试不会再次进入 TurnQueue 调度边界。 */
    private static Object turnOwner(AtomicInteger starts) {
        return Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if ("startChild".equals(method.getName())) {
                        starts.incrementAndGet();
                        TurnStartRequest request = (TurnStartRequest) args[0];
                        ChildTurnScheduler.Admission admission = (ChildTurnScheduler.Admission) args[2];
                        TurnExecutionState.Common common = new TurnExecutionState.Common(
                                0, 0, 1, null, List.of(), NOW.plus(request.deadline()),
                                io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.CHILD_TASK);
                        ChildTurnScheduler.AdmissionRequest child = new ChildTurnScheduler.AdmissionRequest(
                                request.threadId(), request.turnId(), "item_fixture",
                                new ModelMessage(ModelRole.USER, List.of(new TextContent(request.content().text()))),
                                List.of(), 0, NOW,
                                new TurnExecutionState.Ready(common, TurnExecutionState.Next.ASSISTANT, null));
                        ChildTurnScheduler.AdmissionReceipt receipt = admission.admit(child);
                        return new TurnUseCase.Accepted(request.threadId(), request.turnId(),
                                receipt.threadRevision(), true, new CompletableFuture<>());
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Workspace fake 只返回已绑定路径，确保测试不触碰真实文件系统。 */
    private static WorkspaceUseCase workspaces() {
        Workspace workspace = new Workspace("ws_test", Path.of("C:\\ja-task-coordinator"),
                "Fixture", Workspace.Trust.TRUSTED, 1);
        return proxy(WorkspaceUseCase.class, (method, args) -> {
            if ("requireOpenWorkspace".equals(method.getName())) return workspace;
            throw new UnsupportedOperationException(method.getName());
        });
    }

    /** 从真实 ChildAdmission 生成最小合法 projection，保证 Coordinator 后续读取同一事务事实。 */
    private static TaskModels.Summary summary(TaskModels.ChildAdmission child) {
        String threadId = child.childThread().threadId();
        TaskModels.Lineage lineage = new TaskModels.Lineage(threadId, child.parentThreadId(),
                child.parentThreadId(), child.originTurnId(), child.taskName(), 1, child.kind(),
                child.lifecycle(), child.contextSeed().contextSeedId(), NOW);
        TaskModels.Projection projection = new TaskModels.Projection(threadId, child.parentThreadId(),
                1, TaskModels.State.QUEUED, 1, null, 1, 0, 0, 0,
                "queued", null, null, NOW);
        return new TaskModels.Summary(lineage, projection);
    }

    /** 构造不依赖 ChildAdmission 的最小 Task 投影，供等待与授权竞态测试使用。 */
    private static TaskModels.Summary task(String threadId, String rootThreadId,
                                           TaskModels.State state, long revision) {
        TaskModels.Lineage lineage = new TaskModels.Lineage(threadId, rootThreadId, rootThreadId,
                "turn_origin", threadId, 1, TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                "seed_" + threadId.substring("thr_".length()), NOW);
        Instant completedAt = state == TaskModels.State.COMPLETED ? NOW : null;
        return new TaskModels.Summary(lineage, new TaskModels.Projection(threadId, rootThreadId,
                revision, state, 1, null, 1, 0, 0, 0, state.name(), NOW, completedAt, NOW));
    }

    /** 构造包含完整 Turn metadata 的 Child Thread 快照，消息正文保持为空。 */
    private static ThreadSnapshot threadWithTurns(long revision, List<ThreadSnapshot.Turn> turns) {
        ThreadSummary thread = new ThreadSummary("thr_target", "ws_test", "Target", PREFERENCES,
                ThreadSummary.Status.ACTIVE, false, null, true, null, revision, NOW, NOW);
        return new ThreadSnapshot(thread, List.copyOf(turns), List.of(), null, null, null);
    }

    /** Turn fixture 只携带取消循环读取的状态和 revision。 */
    private static ThreadSnapshot.Turn turn(String turnId, String state) {
        return new ThreadSnapshot.Turn(turnId, state, NOW, NOW, null, null, null);
    }

    /** 使用生产 ceiling port 创建完整 seed，测试不复制版本化 JSON 字段。 */
    private static JsonObject capabilityCeiling(ThreadPreferences preferences, Set<String> skillIds) {
        return new TaskAgentToolGateway().create(preferences, "cfg_parent",
                new AgentCapability.CatalogIdentity("a".repeat(64), "mcp_parent", skillIds));
    }

    /** 不参与调度的测试使用同一双接口 owner，仅保留 TaskCoordinator 构造不变量。 */
    private static Object passiveTurnOwner() {
        return Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class},
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }

    /** 在 admission 后同步发布 QUEUED→RUNNING，直接覆盖 Task Activity 投影与调度隔离边界。 */
    private static Object eventPublishingTurnOwner() {
        return Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if (!"startChild".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    TurnStartRequest request = (TurnStartRequest) args[0];
                    io.github.kongweiguang.ja.conversation.port.in.TurnEventSink sink =
                            (io.github.kongweiguang.ja.conversation.port.in.TurnEventSink) args[1];
                    ChildTurnScheduler.Admission admission = (ChildTurnScheduler.Admission) args[2];
                    TurnExecutionState.Common common = new TurnExecutionState.Common(
                            0, 0, 1, null, List.of(), NOW.plus(request.deadline()),
                            io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.CHILD_TASK);
                    ChildTurnScheduler.AdmissionReceipt receipt = admission.admit(
                            new ChildTurnScheduler.AdmissionRequest(request.threadId(), request.turnId(),
                                    "item_event", new ModelMessage(ModelRole.USER,
                                    List.of(new TextContent(request.content().text()))), List.of(), 0, NOW,
                                    new TurnExecutionState.Ready(
                                            common, TurnExecutionState.Next.ASSISTANT, null)));
                    sink.publish(new io.github.kongweiguang.ja.conversation.port.in.TurnEvent.StateChanged(
                            new io.github.kongweiguang.ja.conversation.port.in.TurnEvent.Context(
                                    "evt_running", request.threadId(), request.turnId(), 1, NOW),
                            io.github.kongweiguang.ja.conversation.domain.turn.TurnState.QUEUED,
                            io.github.kongweiguang.ja.conversation.domain.turn.TurnState.RUNNING))
                            .toCompletableFuture().join();
                    return new TurnUseCase.Accepted(request.threadId(), request.turnId(),
                            receipt.threadRevision(), true, new CompletableFuture<>());
                });
    }

    /** 为不会触达的依赖生成 fail-fast 代理，意外调用立即使聚焦测试失败。 */
    private static <T> T unsupported(Class<T> type) {
        return proxy(type, (method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }

    /** 捕获 Coordinator 自身结构化指标，避免测试依赖文件系统日志位置。 */
    private static ListAppender<ILoggingEvent> captureLogs() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(TaskCoordinator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** 测试结束解除 logger 引用，避免其它 Task 用例的指标串入断言。 */
    private static void detachLogs(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                TaskCoordinator.class)).detachAppender(appender);
        appender.stop();
    }

    /** 动态 fake 将无关接口方法显式拒绝，避免为大端口填充会掩盖调用漂移的默认值。 */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> invocation.invoke(method, args));
    }

    /** 测试 fake 的单一调用约定。 */
    @FunctionalInterface
    private interface Invocation {
        /** 根据方法名返回聚焦路径结果，未声明调用必须抛错。 */
        Object invoke(java.lang.reflect.Method method, Object[] args);
    }
}
