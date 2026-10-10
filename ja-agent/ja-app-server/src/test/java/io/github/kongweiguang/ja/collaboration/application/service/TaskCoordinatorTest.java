// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.service;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxQueueCommandMapper;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.TaskAdmissionGatewayAdapter;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.ConversationBoundaryAdapter;


import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolCatalogIdentity;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.TransactionResource;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionCursorCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.conversation.application.api.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjectionFailure;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionReceipt;
import io.github.kongweiguang.ja.conversation.application.gateway.SubagentPolicyRepository;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.collaboration.interfaces.tools.TaskAgentToolGateway;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.domain.model.AdmissionReceipt;
import io.github.kongweiguang.ja.collaboration.domain.model.CollaborationPreferences;
import io.github.kongweiguang.ja.collaboration.domain.model.TurnAdmission;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationAcl;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.collaboration.application.api.TaskEvent;
import io.github.kongweiguang.ja.collaboration.application.api.TaskEventSink;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxPort;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 TaskCoordinator 将冻结能力原样带入 Child admission，而不是重新推断权限。 */
final class TaskCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-03T08:00:00Z");
    private static final ExecutionRecordCodec RECORDS = new StorePayloadCodec(new ObjectMapper());
    private static final ExecutionCursorCodec CURSORS = new TurnExecutionStateCodec(new ObjectMapper());
    private static final JsonValueCodec JSON = new TestJsonValueCodec();
    private static final ThreadLocal<AdmissionProbe> ACTIVE_ADMISSION = new ThreadLocal<>();
    private static final ThreadPreferences PREFERENCES = new ThreadPreferences(
            "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
            io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
            ThreadPreferences.TitleSource.MANUAL);

    /** 测试显式提供策略 Owner，避免生产构造器通过缺失依赖默认放行 Subagent。 */
    private static SubagentPolicyRepository enabledPolicies() {
        return threadId -> Optional.of(SubagentPolicy.defaultPolicy());
    }

    /** 禁用快照必须在 Coordinator 入口拒绝，测试用例不能依赖 Tool 目录先行过滤。 */
    private static SubagentPolicyRepository disabledPolicies() {
        return threadId -> Optional.of(new SubagentPolicy(false, null, null, null));
    }

    /** Subagent seed 必须保存完整 ceiling，且 brief-only 模式不得意外复制有效上下文。 */
    @Test
    void spawnPersistsCompleteCapabilityCeilingInContextSeed() {
        JsonObject ceiling = capabilityCeiling(PREFERENCES, Set.of("skill_review"));
        AtomicReference<TaskModels.ChildAdmission> admitted = new AtomicReference<>();
        AtomicReference<AdmissionProbe> probe = new AtomicReference<>();
        JaAdmissionParticipants participants = new JaAdmissionParticipants();
        TaskRepository repository = repository(admitted);
        ThreadUseCase threads = threads();
        Object turnOwner = turnOwner(participants, new AtomicInteger(), probe);
        WorkspaceUseCase workspaces = workspaces();

        ListAppender<ILoggingEvent> logs = captureLogs();
        try (TaskCoordinator coordinator = coordinator(repository, threads,
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, workspaces,
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies(), participants)) {
            TaskUseCase.StartResult result = coordinator.spawnAgent(new TaskUseCase.SpawnCommand(
                    "thr_parent", "turn_parent", "review",
                    new UserContent(List.of(new TextContent("review changes"))),
                        PREFERENCES, ceiling));
            assertEquals(result.turnId(), probe.get().identity.turnId());
        } finally {
            detachLogs(logs);
        }

        assertEquals(ceiling, admitted.get().contextSeed().permissionCeiling());
        assertEquals(TaskModels.InheritanceMode.BRIEF_ONLY,
                admitted.get().contextSeed().inheritanceMode());
        assertNull(admitted.get().contextSeed().initialContextSeed());
        assertEquals(List.of("event=task_active_count active_count=1",
                        "event=task_active_count active_count=0",
                        "event=task_active_count active_count=0"),
                logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(message -> message.startsWith("event=task_active_count")).toList());
        assertEquals(false, logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("review changes") || message.contains("thr_parent")));
        assertTrue(participants.find(probe.get().identity).isEmpty());
        assertAdmissionCommittedThroughOneConnection(probe.get());
    }

    /** 调度拒绝必须释放精确准入快照，不留下可被下一次请求误用的运行时事实。 */
    @Test
    void failedChildSchedulingReleasesPreparedRuntimeSnapshot() {
        JaAdmissionParticipants participants = new JaAdmissionParticipants();
        AtomicReference<TaskModels.ChildAdmission> admitted = new AtomicReference<>();
        AtomicReference<AgentIdentity> identity = new AtomicReference<>();
        Object owner = Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if (!"startChild".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    TurnStartRequest request = (TurnStartRequest) args[0];
                    AgentIdentity key = JaAdmissionParticipants.identityFor(request.threadId(), request.turnId());
                    identity.set(key);
                    assertTrue(participants.find(key).orElseThrow().runtimeSnapshot().isPresent());
                    throw new IllegalStateException("scheduler rejected admission");
                });
        try (TaskCoordinator coordinator = coordinator(repository(admitted), threads(),
                (TurnUseCase) owner, (ChildTurnScheduler) owner, workspaces(),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies(), participants)) {
            assertThrows(IllegalStateException.class, () -> coordinator.spawnAgent(new TaskUseCase.SpawnCommand(
                    "thr_parent", "turn_parent", "review",
                    new UserContent(List.of(new TextContent("review changes"))),
                    PREFERENCES, capabilityCeiling(PREFERENCES, Set.of()))));
        }
        assertNotNull(identity.get());
        assertTrue(participants.find(identity.get()).isEmpty());
        assertNull(admitted.get());
    }

    /** 直接调用 spawnAgent 也不能绕过已持久化的禁用策略，且不得触达 Child scheduler。 */
    @Test
    void directSpawnRejectsDisabledPolicyBeforeScheduling() {
        AtomicInteger starts = new AtomicInteger();
        TaskRepository repository = repository(new AtomicReference<>());
        Object owner = turnOwner(starts);
        try (TaskCoordinator coordinator = coordinator(repository, threads(),
                (TurnUseCase) owner, (ChildTurnScheduler) owner, workspaces(),
                Clock.fixed(NOW, ZoneOffset.UTC), disabledPolicies())) {
            TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.spawnAgent(new TaskUseCase.SpawnCommand(
                            "thr_parent", "turn_parent", "blocked",
                            new UserContent(List.of(new TextContent("must not run"))),
                            PREFERENCES,
                            capabilityCeiling(PREFERENCES, Set.of()))));
            assertEquals(TaskRepositoryException.Code.PERMISSION_DENIED, failure.code());
        }
        assertEquals(0, starts.get());
    }

    /** 创建只提交侧聊偏好与 idle 回执；未观察的侧聊不启动模型，也不向主会话广播创建活动。 */
    @Test
    void sideTaskUsesExplicitPreferencesAndAccessCeiling() {
        AtomicReference<TaskModels.ChildAdmission> admitted = new AtomicReference<>();
        AtomicReference<TaskModels.Summary> admissionResult = new AtomicReference<>();
        AtomicReference<Optional<TaskModels.Detail>> detail = new AtomicReference<>(Optional.empty());
        AtomicBoolean failPublish = new AtomicBoolean();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTree" -> List.of();
            case "freezeEffectiveContext" -> new TaskModels.EffectiveContextSnapshot("thr_parent", 7,
                    null,
                    new io.github.kongweiguang.ja.agent.domain.json.JsonArray(List.of()),
                    JsonObjects.builder().putText("version", "task_access_v1")
                            .putText("accessMode", "approval_required").build());
            case "findTask" -> admitted.get() == null ? Optional.empty() : Optional.of(summary(admitted.get()));
            case "admitIdleChild" -> {
                TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                admitted.set(child);
                TaskModels.Summary idle = summary(child);
                admissionResult.set(idle);
                TaskModels.Projection runningProjection = new TaskModels.Projection(
                        idle.projection().taskThreadId(), idle.projection().rootThreadId(),
                        idle.projection().revision() + 1, TaskModels.State.RUNNING,
                        idle.projection().latestActivitySequence(), null, 1, 0, 0, 0,
                        "running", NOW, null, NOW);
                detail.set(Optional.of(detail(child,
                        new TaskModels.Summary(idle.lineage(), runningProjection))));
                yield idle;
            }
            case "readTask" -> detail.get();
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase parentThreads = threads();
        AtomicInteger starts = new AtomicInteger();
        Object owner = turnOwner(starts);
        AtomicReference<TaskEvent> lastTaskEvent = new AtomicReference<>();
        TaskEventSink sink = new TaskEventSink() {
            /** 捕获任何意外广播，防止创建 ACK 之外再向主会话回流状态。 */
            @Override public java.util.concurrent.CompletionStage<Void> publish(TaskEvent event) {
                if (failPublish.get()) {
                    return CompletableFuture.failedFuture(new IllegalStateException("sink closed"));
                }
                lastTaskEvent.set(event);
                return CompletableFuture.completedFuture(null);
            }

            /** 本测试只验证 Task 事件，不启动 Child Turn Timeline。 */
            @Override public io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink turnEvents() {
                return ConversationTurnEventSink.noop();
            }
        };
        TaskUseCase.CreatePreferences selected = new TaskUseCase.CreatePreferences(
                "provider_side", "model_side", "high", AccessMode.FULL_ACCESS,
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.PLAN);
        try (TaskCoordinator coordinator = coordinator(repository, parentThreads,
                (TurnUseCase) owner, (ChildTurnScheduler) owner, workspaces(),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies());
             AutoCloseable ignored = coordinator.subscribe(sink)) {
            TaskModels.Summary returned = coordinator.createSideTask(
                    new TaskUseCase.CreateCommand("thr_parent", "turn_parent", 7, "side", selected));
            assertSame(admissionResult.get(), returned);
            assertEquals(TaskModels.State.IDLE, returned.projection().state());
            failPublish.set(true);
            TaskModels.Summary returnedAfterNotificationFailure = coordinator.createSideTask(
                    new TaskUseCase.CreateCommand("thr_parent", "turn_parent", 7, "side-after-disconnect", selected));
            assertEquals(TaskModels.State.IDLE, returnedAfterNotificationFailure.projection().state());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertEquals(0, starts.get());
        assertEquals(null, lastTaskEvent.get());
        assertEquals("provider_side", admitted.get().childThread().preferences().providerId());
        assertEquals("model_side", admitted.get().childThread().preferences().modelId());
        assertEquals(CollaborationPreferences.AccessMode.FULL_ACCESS, admitted.get().childThread().preferences().accessMode());
        assertEquals(CollaborationPreferences.CollaborationMode.PLAN,
                admitted.get().childThread().preferences().collaborationMode());
        assertEquals("full_access", ((io.github.kongweiguang.ja.agent.domain.json.JsonText)
                admitted.get().contextSeed().permissionCeiling().get("accessMode")).value());
    }

    /**
     * 一个宿主进程可同时承载多个逻辑 Host；精确收口隔离资源，单飞等待者保留 leader 的业务错误身份。
     * @author kongweiguang
     */
    @Test
    void nativeClientOwnersWithinOneProcessRemainIsolated() throws Exception {
        io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext context =
                io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext.shared();
        Process nativeHost;
        try {
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            nativeHost = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                    NativeHostProcessFixture.class.getName())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (java.io.IOException failure) {
            throw new AssertionError("failed to start isolated Native host fixture", failure);
        }
        try {
            long processId = nativeHost.pid();
            String firstLifetime = "native_" + java.util.UUID.randomUUID().toString().replace("-", "");
            String secondLifetime = "native_" + java.util.UUID.randomUUID().toString().replace("-", "");
            NativeClientOwner firstOwner = context.registerNativeClient(processId, firstLifetime);
            NativeClientOwner secondOwner = context.registerNativeClient(processId, secondLifetime);
            java.util.concurrent.ConcurrentMap<String, TaskModels.ChildAdmission> admissions =
                    new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.ConcurrentMap<String, TaskModels.Summary> persistedTasks =
                    new java.util.concurrent.ConcurrentHashMap<>();
            List<String> closedRoots = new java.util.concurrent.CopyOnWriteArrayList<>();
            AtomicBoolean failNextClose = new AtomicBoolean();
            CountDownLatch closeFailureEntered = new CountDownLatch(1);
            CountDownLatch releaseCloseFailure = new CountDownLatch(1);
            AtomicReference<TaskRepositoryException> injectedCloseFailure = new AtomicReference<>();
            TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
                case "listTree" -> List.of();
                case "freezeEffectiveContext" -> new TaskModels.EffectiveContextSnapshot("thr_parent", 7,
                        null, new io.github.kongweiguang.ja.agent.domain.json.JsonArray(List.of()),
                        JsonObjects.builder().putText("version", "task_access_v1")
                                .putText("accessMode", "approval_required").build());
                case "admitIdleChild" -> {
                    TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                    admissions.put(child.childThread().threadId(), child);
                    TaskModels.Summary created = summary(child);
                    persistedTasks.put(child.childThread().threadId(), created);
                    yield created;
                }
                case "findTask" -> Optional.ofNullable(persistedTasks.get((String) args[0]));
                case "beginSideChatClose" -> {
                    String root = (String) args[0];
                    yield new TaskModels.SideChatCloseState(root, "thr_parent",
                            TaskModels.SideChatClosePhase.CLOSING, List.of(root));
                }
                case "deleteClosedSideChat" -> {
                    String root = (String) args[0];
                    String ownerRoot = (String) args[1];
                    @SuppressWarnings("unchecked")
                    List<String> subtree = (List<String>) args[2];
                    if (failNextClose.compareAndSet(true, false)) {
                        TaskRepositoryException failure = new TaskRepositoryException(
                                TaskRepositoryException.Code.CAS_CONFLICT, "injected close conflict");
                        injectedCloseFailure.set(failure);
                        closeFailureEntered.countDown();
                        try {
                            releaseCloseFailure.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE,
                                    "injected close wait interrupted", interrupted);
                        }
                        throw failure;
                    }
                    closedRoots.add(root);
                    subtree.forEach(admissions::remove);
                    subtree.forEach(persistedTasks::remove);
                    yield new TaskModels.SideChatCloseReceipt(root, ownerRoot, subtree);
                }
                case "listTemporarySideChats" -> List.of();
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            Object turnOwner = passiveTurnOwner();
            TaskUseCase.CreatePreferences preferences = new TaskUseCase.CreatePreferences(
                    "provider_side", "model_side", "medium", AccessMode.APPROVAL_REQUIRED,
                    io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT);
            try (TaskCoordinator coordinator = coordinator(repository, threads(),
                    (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, workspaces(),
                    Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
                coordinator.bindSideChatOwnerController((ignoredRoots, deadlineNanos) -> { });
                assertFalse(firstOwner == secondOwner);
                assertTrue(context.nativeClientAcceptsRequests(firstOwner));
                assertTrue(context.nativeClientAcceptsRequests(secondOwner));

                TaskModels.Summary first = coordinator.createSideTask(new TaskUseCase.CreateCommand(
                        "thr_parent", "turn_parent", 7, "first host", preferences), Optional.of(firstOwner));
                TaskModels.Summary nested = coordinator.createSideTask(new TaskUseCase.CreateCommand(
                        first.lineage().taskThreadId(), "turn_parent", 7, "same owner nested", preferences),
                        Optional.of(firstOwner));
                TaskModels.Summary second = coordinator.createSideTask(new TaskUseCase.CreateCommand(
                        "thr_parent", "turn_parent", 7, "second host", preferences), Optional.of(secondOwner));
                String foreignSubagentId = "thr_foreign_subagent";
                TaskModels.Lineage foreignSubagentLineage = new TaskModels.Lineage(foreignSubagentId,
                        second.lineage().taskThreadId(), "thr_parent", "turn_parent", "foreign subagent", 1,
                        TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED, "seed_foreign", NOW);
                TaskModels.Projection foreignSubagentProjection = new TaskModels.Projection(foreignSubagentId,
                        "thr_parent", 1, TaskModels.State.IDLE, 1, null, 1, 0, 0, 0,
                        "idle", null, null, NOW);
                persistedTasks.put(foreignSubagentId,
                        new TaskModels.Summary(foreignSubagentLineage, foreignSubagentProjection));
                assertThrows(TaskRepositoryException.class, () -> coordinator.createSideTask(
                        new TaskUseCase.CreateCommand(foreignSubagentId, "turn_parent", 7,
                                "cross owner nested", preferences), Optional.of(firstOwner)));

                failNextClose.set(true);
                AtomicReference<Thread> waiterThread = new AtomicReference<>();
                CountDownLatch waiterStarted = new CountDownLatch(1);
                try (java.util.concurrent.ExecutorService closers =
                             java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    CompletableFuture<TaskRepositoryException> leader = CompletableFuture.supplyAsync(() -> {
                        try {
                            coordinator.closeNativeClient(firstOwner,
                                    System.nanoTime() + TimeUnit.SECONDS.toNanos(8));
                            return null;
                        } catch (TaskRepositoryException failure) {
                            return failure;
                        }
                    }, closers);
                    assertTrue(closeFailureEntered.await(2, TimeUnit.SECONDS), "close leader did not reach repository");
                    CompletableFuture<TaskRepositoryException> waiter = CompletableFuture.supplyAsync(() -> {
                        waiterThread.set(Thread.currentThread());
                        waiterStarted.countDown();
                        try {
                            coordinator.closeNativeClient(firstOwner,
                                    System.nanoTime() + TimeUnit.SECONDS.toNanos(8));
                            return null;
                        } catch (TaskRepositoryException failure) {
                            return failure;
                        }
                    }, closers);
                    assertTrue(waiterStarted.await(2, TimeUnit.SECONDS), "close waiter did not start");
                    long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    boolean joinedCloseAttempt = false;
                    while (System.nanoTime() < waitDeadline && !joinedCloseAttempt) {
                        Thread currentWaiter = waiterThread.get();
                        joinedCloseAttempt = currentWaiter != null
                                && java.util.Arrays.stream(currentWaiter.getStackTrace()).anyMatch(frame ->
                                frame.getClassName().equals(TaskCoordinator.class.getName())
                                        && frame.getMethodName().equals("awaitNativeClientClose"));
                        if (!joinedCloseAttempt) {
                            Thread.sleep(1);
                        }
                    }
                    try {
                        assertTrue(joinedCloseAttempt, "close waiter did not join the single-flight receipt");
                    } finally {
                        releaseCloseFailure.countDown();
                    }
                    TaskRepositoryException leaderFailure = leader.get(3, TimeUnit.SECONDS);
                    TaskRepositoryException waiterFailure = waiter.get(3, TimeUnit.SECONDS);
                    assertSame(injectedCloseFailure.get(), leaderFailure);
                    assertSame(leaderFailure, waiterFailure);
                    assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, waiterFailure.code());
                }
                assertFalse(context.nativeClientAcceptsRequests(firstOwner));
                assertFalse(context.nativeClientClosed(firstOwner));
                assertTrue(admissions.containsKey(first.lineage().taskThreadId()));

                coordinator.closeNativeClient(firstOwner, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));

                assertTrue(context.nativeClientClosed(firstOwner));
                assertSame(firstOwner, context.registerNativeClient(processId, firstLifetime));
                assertFalse(context.nativeClientAcceptsRequests(firstOwner));
                assertTrue(context.nativeClientAcceptsRequests(secondOwner));
                assertFalse(admissions.containsKey(first.lineage().taskThreadId()));
                assertFalse(admissions.containsKey(nested.lineage().taskThreadId()));
                assertTrue(admissions.containsKey(second.lineage().taskThreadId()));
                assertEquals(List.of(first.lineage().taskThreadId(), nested.lineage().taskThreadId()), closedRoots);

                coordinator.closeNativeClient(secondOwner, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));

                assertTrue(context.nativeClientClosed(secondOwner));
                assertFalse(admissions.containsKey(second.lineage().taskThreadId()));
                assertEquals(List.of(first.lineage().taskThreadId(), nested.lineage().taskThreadId(),
                        second.lineage().taskThreadId()), closedRoots);
            }
        } finally {
            nativeHost.destroyForcibly();
            assertTrue(nativeHost.waitFor(2, TimeUnit.SECONDS), "Native host process fixture failed to exit");
        }
    }

    /**
     * 持久准入已提交但回读失败时保留 reservation，后续 owner close 仍能精确收口该 root。
     * @author kongweiguang
     */
    @Test
    void nativeCreateCommitWithReadFailureKeepsReservationForClose() throws Exception {
        Process nativeHost = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"), NativeHostProcessFixture.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext context =
                    io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext.shared();
            String lifetimeId = "native_" + java.util.UUID.randomUUID().toString().replace("-", "");
            NativeClientOwner owner = context.registerNativeClient(nativeHost.pid(), lifetimeId);
            AtomicReference<String> committedThreadId = new AtomicReference<>();
            AtomicBoolean failAdmissionAfterCommit = new AtomicBoolean(true);
            AtomicBoolean failReconciliationRead = new AtomicBoolean(true);
            java.util.concurrent.ConcurrentMap<String, TaskModels.Summary> persisted =
                    new java.util.concurrent.ConcurrentHashMap<>();
            IllegalStateException admissionFailure = new IllegalStateException("injected post-commit acknowledgement failure");
            IllegalStateException readFailure = new IllegalStateException("injected reconciliation read failure");
            TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
                case "listTree" -> List.of();
                case "freezeEffectiveContext" -> new TaskModels.EffectiveContextSnapshot("thr_parent", 7,
                        null, new io.github.kongweiguang.ja.agent.domain.json.JsonArray(List.of()),
                        JsonObjects.builder().putText("version", "task_access_v1")
                                .putText("accessMode", "approval_required").build());
                case "admitIdleChild" -> {
                    TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                    TaskModels.Summary summary = summary(child);
                    committedThreadId.set(child.childThread().threadId());
                    persisted.put(child.childThread().threadId(), summary);
                    if (failAdmissionAfterCommit.compareAndSet(true, false)) {
                        throw admissionFailure;
                    }
                    yield summary;
                }
                case "findTask" -> {
                    String threadId = (String) args[0];
                    if (threadId.equals(committedThreadId.get())
                            && failReconciliationRead.compareAndSet(true, false)) {
                        throw readFailure;
                    }
                    yield Optional.ofNullable(persisted.get(threadId));
                }
                case "beginSideChatClose" -> {
                    String root = (String) args[0];
                    yield new TaskModels.SideChatCloseState(root, "thr_parent",
                            TaskModels.SideChatClosePhase.CLOSING, List.of(root));
                }
                case "deleteClosedSideChat" -> {
                    String root = (String) args[0];
                    String ownerRoot = (String) args[1];
                    @SuppressWarnings("unchecked")
                    List<String> subtree = (List<String>) args[2];
                    subtree.forEach(persisted::remove);
                    yield new TaskModels.SideChatCloseReceipt(root, ownerRoot, subtree);
                }
                case "listTemporarySideChats" -> List.of();
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            Object turnOwner = passiveTurnOwner();
            TaskUseCase.CreatePreferences preferences = new TaskUseCase.CreatePreferences(
                    "provider_side", "model_side", "medium", AccessMode.APPROVAL_REQUIRED,
                    io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT);
            try (TaskCoordinator coordinator = coordinator(repository, threads(),
                    (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, workspaces(),
                    Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
                coordinator.bindSideChatOwnerController((ignoredRoots, deadlineNanos) -> { });
                IllegalStateException observed = assertThrows(IllegalStateException.class,
                        () -> coordinator.createSideTask(new TaskUseCase.CreateCommand(
                                "thr_parent", "turn_parent", 7, "committed then unacknowledged", preferences),
                                Optional.of(owner)));
                assertSame(admissionFailure, observed);
                assertEquals(1, observed.getSuppressed().length);
                assertSame(readFailure, observed.getSuppressed()[0]);
                String committed = committedThreadId.get();
                assertTrue(persisted.containsKey(committed));

                coordinator.closeNativeClient(owner, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));

                assertTrue(context.nativeClientClosed(owner));
                assertFalse(persisted.containsKey(committed));
            }
        } finally {
            nativeHost.destroyForcibly();
            assertTrue(nativeHost.waitFor(2, TimeUnit.SECONDS), "Native host process fixture failed to exit");
        }
    }

    /**
     * 真实 Host 退出只清理其 owner roots；另一真实 Host 的资源与 OPEN 代际继续保留。
     * @author kongweiguang
     */
    @Test
    void nativeProcessExitPurgesOnlyItsOwnerRoots() throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process firstHost = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                NativeHostProcessFixture.class.getName()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Process secondHost = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                NativeHostProcessFixture.class.getName()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext context =
                    io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext.shared();
            NativeClientOwner firstOwner = context.registerNativeClient(firstHost.pid(),
                    "native_" + java.util.UUID.randomUUID().toString().replace("-", ""));
            NativeClientOwner secondOwner = context.registerNativeClient(secondHost.pid(),
                    "native_" + java.util.UUID.randomUUID().toString().replace("-", ""));
            java.util.concurrent.ConcurrentMap<String, TaskModels.Summary> persisted =
                    new java.util.concurrent.ConcurrentHashMap<>();
            AtomicReference<String> firstRoot = new AtomicReference<>();
            CountDownLatch firstRootClosed = new CountDownLatch(1);
            TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
                case "listTree" -> List.of();
                case "freezeEffectiveContext" -> new TaskModels.EffectiveContextSnapshot("thr_parent", 7,
                        null, new io.github.kongweiguang.ja.agent.domain.json.JsonArray(List.of()),
                        JsonObjects.builder().putText("version", "task_access_v1")
                                .putText("accessMode", "approval_required").build());
                case "admitIdleChild" -> {
                    TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                    TaskModels.Summary created = summary(child);
                    persisted.put(child.childThread().threadId(), created);
                    yield created;
                }
                case "findTask" -> Optional.ofNullable(persisted.get((String) args[0]));
                case "beginSideChatClose" -> {
                    String root = (String) args[0];
                    yield new TaskModels.SideChatCloseState(root, "thr_parent",
                            TaskModels.SideChatClosePhase.CLOSING, List.of(root));
                }
                case "deleteClosedSideChat" -> {
                    String root = (String) args[0];
                    String ownerRoot = (String) args[1];
                    @SuppressWarnings("unchecked")
                    List<String> subtree = (List<String>) args[2];
                    subtree.forEach(persisted::remove);
                    if (subtree.contains(firstRoot.get())) {
                        firstRootClosed.countDown();
                    }
                    yield new TaskModels.SideChatCloseReceipt(root, ownerRoot, subtree);
                }
                case "listTemporarySideChats" -> List.of();
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            Object turnOwner = passiveTurnOwner();
            TaskUseCase.CreatePreferences preferences = new TaskUseCase.CreatePreferences(
                    "provider_side", "model_side", "medium", AccessMode.APPROVAL_REQUIRED,
                    io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT);
            try (TaskCoordinator coordinator = coordinator(repository, threads(),
                    (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, workspaces(),
                    Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
                coordinator.bindSideChatOwnerController((ignoredRoots, deadlineNanos) -> { });
                TaskModels.Summary first = coordinator.createSideTask(new TaskUseCase.CreateCommand(
                        "thr_parent", "turn_parent", 7, "first process root", preferences), Optional.of(firstOwner));
                firstRoot.set(first.lineage().taskThreadId());
                TaskModels.Summary second = coordinator.createSideTask(new TaskUseCase.CreateCommand(
                        "thr_parent", "turn_parent", 7, "second process root", preferences), Optional.of(secondOwner));

                firstHost.destroyForcibly();
                assertTrue(firstHost.waitFor(2, TimeUnit.SECONDS), "first Native Host process did not exit");
                assertTrue(firstRootClosed.await(5, TimeUnit.SECONDS), "Host exit did not purge its temporary root");
                long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!context.nativeClientClosed(firstOwner) && System.nanoTime() < closeDeadline) {
                    Thread.sleep(10);
                }

                assertTrue(context.nativeClientClosed(firstOwner));
                assertFalse(persisted.containsKey(first.lineage().taskThreadId()));
                assertTrue(persisted.containsKey(second.lineage().taskThreadId()));
                assertTrue(context.nativeClientAcceptsRequests(secondOwner));
                coordinator.closeNativeClient(secondOwner, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
                assertFalse(persisted.containsKey(second.lineage().taskThreadId()));
            }
        } finally {
            firstHost.destroyForcibly();
            secondHost.destroyForcibly();
            assertTrue(firstHost.waitFor(2, TimeUnit.SECONDS), "first Native Host process fixture failed to exit");
            assertTrue(secondHost.waitFor(2, TimeUnit.SECONDS), "second Native Host process fixture failed to exit");
        }
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
                if (existing == null) {
                    yield Optional.empty();
                }
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
            case "findInitialContextSeedForFirstTurn" -> Optional.empty();
            case "rootTurnIdForAdmission" -> "turn_parent";
            case "readTask" -> Optional.empty();
            case "applyFollowUpAdmission" -> {
                TaskModels.FollowUpAdmission admission = (TaskModels.FollowUpAdmission) args[0];
                assertTaskAdmissionUsesCurrentStoreTransaction();
                TurnAdmission turn = admission.turn();
                TaskModels.MailboxEnvelope mailbox = admission.mailbox();
                AdmissionReceipt turnReceipt = new AdmissionReceipt(
                        turn.threadId(), turn.turnId(), 6, 0, null);
                TaskModels.MailboxMessage message = new TaskModels.MailboxMessage(
                        1, mailbox.messageId(), "thr_parent", mailbox.senderThreadId(), "父会话", mailbox.targetThreadId(),
                        mailbox.causalTurnId(), mailbox.kind(), mailbox.content(), mailbox.operationKey(),
                        TaskModels.MailboxState.CONSUMED, turn.turnId(), mailbox.createdAt(), mailbox.createdAt(),
                        mailbox.createdAt());
                TaskModels.FollowUpAdmissionReceipt receipt =
                        new TaskModels.FollowUpAdmissionReceipt(turnReceipt, message);
                persisted.set(receipt);
                admissions.incrementAndGet();
                revision.incrementAndGet();
                yield null;
            }
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
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
        AtomicReference<AdmissionProbe> probe = new AtomicReference<>();
        JaAdmissionParticipants participants = new JaAdmissionParticipants();
        Object owner = turnOwner(participants, starts, probe);
        TaskUseCase.FollowUpCommand command = new TaskUseCase.FollowUpCommand(
                new TaskUseCase.MessageCommand("thr_parent", "thr_target",
                        new UserContent(List.of(new TextContent("继续完成"))),
                        "follow-public-retry", "turn_parent"), 3);

        try (TaskCoordinator coordinator = coordinator(repository, childThreads,
                (TurnUseCase) owner, (ChildTurnScheduler) owner, workspaces(),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies(), participants)) {
            TaskUseCase.FollowUpResult first = coordinator.followUp(command);
            TaskUseCase.FollowUpResult replay = coordinator.followUp(command);
            TaskRepositoryException collision = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.followUp(new TaskUseCase.FollowUpCommand(
                            new TaskUseCase.MessageCommand("thr_parent", "thr_target",
                                    new UserContent(List.of(new TextContent("不同内容"))),
                                    "follow-public-retry", "turn_parent"),
                            3)));

            assertEquals(first.messageId(), replay.messageId());
            assertEquals(first.turnId(), replay.turnId());
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, collision.code());
        }

        assertEquals(1, starts.get());
        assertEquals(1, admissions.get());
        assertAdmissionCommittedThroughOneConnection(probe.get());
    }

    /** Child 到 root 的 QueueOnly 重试复用 Repository 回执，且不会制造 Task 事件。 */
    @Test
    void publishesChildToRootMessageOnlyForInsertWinner() {
        TaskModels.MailboxMessage mailbox = new TaskModels.MailboxMessage(7, "msg_stored", "thr_root",
                "thr_child", "子任务", "thr_root", "turn_child", TaskModels.MailboxKind.MESSAGE,
                new UserContent(List.of(new TextContent("阶段结果"))), "child-root-key",
                TaskModels.MailboxState.PENDING, null, NOW, NOW, null);
        AtomicInteger enqueues = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "enqueueMessage" -> new TaskModels.MessageEnqueueReceipt(
                    mailbox, enqueues.incrementAndGet() == 1);
            case "readTask" -> Optional.empty();
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        AtomicInteger events = new AtomicInteger();
        TaskEventSink sink = new TaskEventSink() {
            /** QueueOnly 不应向 Task timeline 发布事件。 */
            @Override public java.util.concurrent.CompletionStage<Void> publish(TaskEvent event) {
                events.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }

            /** 本测试不启动 Child Turn，Timeline sink 保持显式空实现。 */
            @Override public io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink turnEvents() {
                return ConversationTurnEventSink.noop();
            }
        };
        Object turnOwner = passiveTurnOwner();
        TaskUseCase.MessageCommand command = new TaskUseCase.MessageCommand(
                "thr_child", "thr_root", new UserContent(List.of(new TextContent("阶段结果"))),
                "child-root-key", "turn_child");

        try (TaskCoordinator coordinator = coordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies()); AutoCloseable ignored = coordinator.subscribe(sink)) {
            TaskUseCase.MessageReceipt first = coordinator.sendMessage(command);
            TaskUseCase.MessageReceipt replay = coordinator.sendMessage(command);

            assertEquals(first, replay);
            assertEquals(0, events.get());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    /** 同目标的两个客户端各自拥有观察句柄，释放一个后另一个仍收到真实投影进度。 */
    @Test
    void isolatesObservationsOfTheSameTask() throws Exception {
        TaskModels.Summary task = taskWithProjection(TaskModels.State.RUNNING, 3, 1);
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> Optional.of(task);
            case "listTemporarySideChats" -> List.of();
            default -> throw new UnsupportedOperationException(method.getName());
        });
        List<TaskEvent.Progress> progress = new ArrayList<>();
        TaskEventSink subscriber = new TaskEventSink() {
            /** 保存实际发布的句柄与序号，避免只断言订阅 ID 而漏掉后续投递。 */
            @Override public java.util.concurrent.CompletionStage<Void> publish(TaskEvent event) {
                progress.add(assertInstanceOf(TaskEvent.Progress.class, event));
                return CompletableFuture.completedFuture(null);
            }

            /** 进度通过公开续跑桥驱动，无需调度额外的 Child Turn。 */
            @Override public ConversationTurnEventSink turnEvents() {
                return ConversationTurnEventSink.noop();
            }
        };
        Object owner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) owner, (ChildTurnScheduler) owner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies());
             AutoCloseable ignored = coordinator.subscribe(subscriber)) {
            TaskUseCase.Observation first = coordinator.observe("thr_target", 3);
            TaskUseCase.Observation second = coordinator.observe("thr_target", 3);
            assertTrue(!first.observationId().equals(second.observationId()));
            ConversationTurnEventSink bridge = coordinator.projectContinuationEvents(
                    "thr_target", ConversationTurnEventSink.noop());

            bridge.publish(new TurnEvent.TextDelta("turn_target", 1, "first", "request_first"))
                    .toCompletableFuture().join();
            assertEquals(2, progress.size());
            assertEquals(Set.of(first.observationId(), second.observationId()),
                    Set.of(progress.get(0).observationId(), progress.get(1).observationId()));
            coordinator.unobserve(first.observationId());
            TaskRepositoryException duplicate = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.unobserve(first.observationId()));
            assertEquals(TaskRepositoryException.Code.OBSERVATION_INVALID, duplicate.code());

            bridge.publish(new TurnEvent.TextDelta("turn_target", 2, "second", "request_first"))
                    .toCompletableFuture().join();
            assertEquals(3, progress.size());
            assertEquals(second.observationId(), progress.getLast().observationId());
            assertEquals(2, progress.getLast().progressRevision());
            assertEquals("second", progress.getLast().safeSummary());
            coordinator.unobserve(second.observationId());
            bridge.publish(new TurnEvent.TextDelta("turn_target", 3, "unobserved", "request_first"))
                    .toCompletableFuture().join();
            assertEquals(3, progress.size());
        }
    }

    /** Plan 执行的隐藏 Turn 与普通子任务 Turn 共用 Task 投影桥；有序状态只生成必要 Activity，终态后不回退。
     * @author kongweiguang
     */
    @Test
    void projectsContinuationEventsIntoTaskActivity() {
        AtomicReference<TaskModels.Summary> current = new AtomicReference<>(taskWithProjection(
                TaskModels.State.QUEUED, 1, 1));
        AtomicReference<Optional<TaskModels.Detail>> detail = new AtomicReference<>(Optional.empty());
        AtomicInteger downstreamEvents = new AtomicInteger();
        AtomicInteger activityWrites = new AtomicInteger();
        AtomicReference<TurnProjectionFailure> downstreamFailure = new AtomicReference<>();
        AtomicReference<ContextCompactionEvent> downstreamCompaction = new AtomicReference<>();
        AtomicReference<TaskEvent> taskEvent = new AtomicReference<>();
        AtomicReference<TaskModels.ActivityMutation> lastMutation = new AtomicReference<>();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> Optional.of(current.get());
            case "recordActivity" -> {
                TaskModels.ActivityMutation mutation = (TaskModels.ActivityMutation) args[0];
                activityWrites.incrementAndGet();
                lastMutation.set(mutation);
                TaskModels.Summary before = current.get();
                TaskModels.Summary updated = taskWithProjection(mutation.state(),
                        before.projection().revision() + 1,
                        before.projection().latestActivitySequence() + 1);
                current.set(updated);
                yield updated;
            }
            case "readTask" -> detail.get();
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        TaskEventSink subscriber = new TaskEventSink() {
            /** 终态回读只需观察 Task Activity，不复用 Turn Timeline sink。 */
            @Override public java.util.concurrent.CompletionStage<Void> publish(TaskEvent event) {
                taskEvent.set(event);
                return CompletableFuture.completedFuture(null);
            }

            /** 本测试只验证下游桥的调用，不启动真实 Child Turn。 */
            @Override public io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink turnEvents() {
                return ConversationTurnEventSink.noop();
            }
        };
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies());
             AutoCloseable ignored = coordinator.subscribe(subscriber)) {
            TurnEventSink timeline = new TurnEventSink() {
                /** 统计原始生命周期事件，投影故障单独断言且不进入 Task 状态机。 */
                @Override public java.util.concurrent.CompletionStage<Void> publish(TurnEvent event) {
                    downstreamEvents.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }

                /** 保留收到的恢复对象，保证投影桥不改写 Thread 重同步身份。 */
                @Override public java.util.concurrent.CompletionStage<Void> publishProjectionFailure(
                        TurnProjectionFailure failure) {
                    downstreamFailure.set(failure);
                    return CompletableFuture.completedFuture(null);
                }

                /** 压缩不属于 Task Activity，但仍须透传给原 Timeline 观察端。 */
                @Override public java.util.concurrent.CompletionStage<Void> publish(ContextCompactionEvent event) {
                    downstreamCompaction.set(event);
                    return CompletableFuture.completedFuture(null);
                }
            };
            ConversationTurnEventSink downstream = ConversationTurnEventSink.turnEventsOnly(timeline);
            ConversationTurnEventSink bridge = coordinator.projectContinuationEvents("thr_target", downstream);
            ContextCompactionEvent compaction = new ContextCompactionEvent.Started(
                    new ContextCompactionEvent.Context("evt_compaction_started", "ws_test", "thr_target",
                            "turn_hidden", 4, NOW, "cmp_test", ContextCompactionEvent.Trigger.AUTOMATIC,
                            3, 10L, null, ContextCompactionEvent.STRATEGY_VERSION, 3L));
            bridge.publish(compaction).toCompletableFuture().join();
            assertSame(compaction, downstreamCompaction.get(), "Child wrapper must preserve compaction events");
            bridge.publish(new TurnEvent.StateChanged(new TurnEvent.Context("evt_queued_running", "thr_target",
                    "turn_hidden", 1, 1, NOW), io.github.kongweiguang.ja.agent.domain.turn.TurnState.QUEUED,
                    io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING)).toCompletableFuture().join();
            assertEquals(TaskModels.State.RUNNING, current.get().projection().state());
            bridge.publish(new TurnEvent.StateChanged(new TurnEvent.Context("evt_running_waiting_input", "thr_target",
                    "turn_hidden", 2, 2, NOW), io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING,
                    io.github.kongweiguang.ja.agent.domain.turn.TurnState.WAITING_INPUT)).toCompletableFuture().join();
            assertEquals(TaskModels.State.WAITING_INPUT, lastMutation.get().state());
            assertEquals(TaskModels.ActivityKind.WAITING_INPUT, lastMutation.get().kind());

            bridge.publish(new TurnEvent.StateChanged(new TurnEvent.Context("evt_waiting_input_running", "thr_target",
                    "turn_hidden", 3, 3, NOW), io.github.kongweiguang.ja.agent.domain.turn.TurnState.WAITING_INPUT,
                    io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING)).toCompletableFuture().join();
            assertEquals(TaskModels.State.RUNNING, current.get().projection().state());
            int writesAfterResume = activityWrites.get();
            bridge.publish(new TurnEvent.StateChanged(new TurnEvent.Context("evt_duplicate_resume", "thr_target",
                    "turn_hidden", 3, 3, NOW), io.github.kongweiguang.ja.agent.domain.turn.TurnState.WAITING_INPUT,
                    io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING)).toCompletableFuture().join();
            assertEquals(writesAfterResume, activityWrites.get(), "duplicate target state must not add an Activity");

            bridge.publish(new TurnEvent.StateChanged(new TurnEvent.Context("evt_running_approval", "thr_target",
                    "turn_hidden", 4, 4, NOW), io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING,
                    io.github.kongweiguang.ja.agent.domain.turn.TurnState.WAITING_APPROVAL))
                    .toCompletableFuture().join();
            bridge.publish(new TurnEvent.ApprovalRequested(new TurnEvent.Context("evt_approval_requested",
                    "thr_target", "turn_hidden", 4, 4, NOW), "appr_bridge", "call_bridge", "shell",
                    new ToolPresentation(ToolPresentation.Kind.SHELL, "执行命令", ToolPresentation.Status.PENDING,
                            null, null, null, List.of(), List.of(), null, null,
                            null, null, null, null, false, null),
                    "需要确认", NOW.plusSeconds(60))).toCompletableFuture().join();
            assertEquals(writesAfterResume + 1, activityWrites.get(),
                    "approval StateChanged followed by ApprovalRequested must project one Task Activity");
            assertEquals(TaskModels.State.WAITING_APPROVAL, lastMutation.get().state());
            assertEquals(TaskModels.ActivityKind.WAITING_APPROVAL, lastMutation.get().kind());
            bridge.publish(new TurnEvent.ApprovalResolved(new TurnEvent.Context("evt_approval_resolved",
                    "thr_target", "turn_hidden", 5, 5, NOW), "appr_bridge",
                    io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision.APPROVE)).toCompletableFuture().join();

            TaskModels.Summary terminal = taskWithProjection(TaskModels.State.COMPLETED, 5, 5);
            current.set(terminal);
            TaskModels.Activity activity = new TaskModels.Activity(5, "activity_terminal", "thr_root", "thr_target",
                    "thr_target", "turn_hidden", TaskModels.ActivityKind.COMPLETED,
                    JsonObjects.builder().putText("text", "完成").build(), NOW);
            TaskModels.ContextSeed seed = new TaskModels.ContextSeed("seed_target", "thr_root", "turn_origin", 1,
                    TaskModels.InheritanceMode.BRIEF_ONLY, new UserContent(List.of(new TextContent("brief"))), null,
                    new JsonArray(List.of()), JsonObjects.builder().putText("version", "task_access_v1").build(),
                    "a".repeat(64), NOW);
            ThreadSummary childThread = new ThreadSummary("thr_target", "ws_test", "Target", "project", PREFERENCES, ThreadSummary.Status.ACTIVE, false, io.github.kongweiguang.ja.agent.domain.turn.TurnState.COMPLETED, true, 5, NOW, NOW);
            detail.set(Optional.of(new TaskModels.Detail(terminal, ConversationAcl.snapshot(childThread), seed,
                    List.of(activity), List.of())));
            bridge.publish(new TurnEvent.Terminal(new TurnEvent.Context("evt_terminal", "thr_target", "turn_hidden",
                    6, 6, NOW), io.github.kongweiguang.ja.agent.domain.turn.TurnState.COMPLETED, "完成", null,
                    null, new TurnEvent.FinalMessage("item_done", "完成"), null,
                    TurnChangeSet.emptyComplete())).toCompletableFuture().join();
            bridge.publish(new TurnEvent.StateChanged(new TurnEvent.Context("evt_late_after_terminal", "thr_target",
                    "turn_hidden", 5, 5, NOW), io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING,
                    io.github.kongweiguang.ja.agent.domain.turn.TurnState.WAITING_INPUT)).toCompletableFuture().join();
            assertEquals(TaskModels.State.COMPLETED, current.get().projection().state());
            assertEquals(5, activityWrites.get(), "terminal Task state must reject late lifecycle projection");
            TurnProjectionFailure failure = new TurnProjectionFailure("thr_target", "turn_hidden",
                    TurnProjectionFailure.Reason.PROJECTION_FAILED);
            bridge.publishProjectionFailure(failure).toCompletableFuture().join();
            assertSame(failure, downstreamFailure.get());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }

        assertEquals(9, downstreamEvents.get());
        assertEquals(TaskModels.State.COMPLETED, current.get().projection().state());
        assertEquals(TaskEvent.Activity.class, taskEvent.get().getClass());
        assertEquals("activity_terminal", ((TaskEvent.Activity) taskEvent.get()).activity().activityId());
    }

    /** Child 的私有适配器只过滤 UI 事件，投影失败必须抵达已登记的 RPC sink 触发权威重读。 */
    @Test
    void preparedChildSinkForwardsProjectionFailureToRegisteredTimeline() throws Exception {
        AtomicReference<TurnProjectionFailure> received = new AtomicReference<>();
        TurnEventSink timeline = new TurnEventSink() {
            /** 本测试不发布业务事件，只核验恢复通知的路由边界。 */
            @Override public java.util.concurrent.CompletionStage<Void> publish(TurnEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** 捕获原始恢复通知，确认它没有落入适配器的默认空实现。 */
            @Override public java.util.concurrent.CompletionStage<Void> publishProjectionFailure(
                    TurnProjectionFailure failure) {
                received.set(failure);
                return CompletableFuture.completedFuture(null);
            }
        };
        ConversationTurnEventSink downstream = ConversationTurnEventSink.turnEventsOnly(timeline);
        TaskEventSink subscriber = new TaskEventSink() {
            /** Task 层在本路径没有额外事件。 */
            @Override public java.util.concurrent.CompletionStage<Void> publish(TaskEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** Child Timeline 使用当前订阅代际，重同步不能跨连接投递。 */
            @Override public ConversationTurnEventSink turnEvents() { return downstream; }
        };
        Object owner = passiveTurnOwner();
        TurnStartRequest request = new TurnStartRequest("thr_projection", "turn_projection", "ws_test",
                Path.of("C:\\ja-task-projection"), new UserContent(List.of(new TextContent("child"))),
                "provider_test", "model_test", "medium", AccessMode.FULL_ACCESS,
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                0, 0, NOW, Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false);
        TurnProjectionFailure failure = new TurnProjectionFailure(request.threadId(), request.turnId(),
                TurnProjectionFailure.Reason.PROJECTION_FAILED);

        try (TaskCoordinator coordinator = coordinator(repository(new AtomicReference<>()),
                unsupported(ThreadUseCase.class), (TurnUseCase) owner, (ChildTurnScheduler) owner,
                unsupported(WorkspaceUseCase.class), Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies());
             AutoCloseable ignored = coordinator.subscribe(subscriber)) {
            Method prepare = TaskCoordinator.class.getDeclaredMethod("prepareTurn", TurnStartRequest.class);
            prepare.setAccessible(true);
            Object binding = prepare.invoke(coordinator, request);
            Method eventsAccessor = binding.getClass().getDeclaredMethod("turnEvents");
            eventsAccessor.setAccessible(true);
            ConversationTurnEventSink prepared = (ConversationTurnEventSink) eventsAccessor.invoke(binding);

            prepared.publishProjectionFailure(failure).toCompletableFuture().join();
        }

        assertSame(failure, received.get());
    }

    /** 终态若发生在首次检查与 waiter 登记之间，登记后的二次检查必须立即补获而非超时。 */
    @Test
    void waitAgentClosesCheckThenRegisterLostWakeup() {
        AtomicInteger reads = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> Optional.of(task("thr_wait", "thr_root", reads.incrementAndGet() == 1
                    ? TaskModels.State.RUNNING : TaskModels.State.COMPLETED, 1));
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
            TaskUseCase.WaitResult result = coordinator.waitAgents(Set.of("thr_wait"), Duration.ofSeconds(1),
                    CancellationToken.none()).toCompletableFuture().join();

            assertEquals(List.of("thr_wait"), result.tasks().stream()
                    .map(value -> value.lineage().taskThreadId()).toList());
            assertEquals(false, result.timedOut());
        }
    }

    /** 空取消令牌必须在读取目标或登记异步 waiter 前拒绝，避免遗留观察和超时任务。 */
    @Test
    void rejectsMissingWaitCancellationBeforeReadingTargets() {
        AtomicInteger reads = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> {
                reads.incrementAndGet();
                yield Optional.of(task("thr_wait", "thr_root", TaskModels.State.RUNNING, 1));
            }
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
            assertThrows(NullPointerException.class,
                    () -> coordinator.waitAgents(Set.of("thr_wait"), Duration.ofSeconds(1), null));
        }

        assertEquals(0, reads.get());
    }

    /** 关闭先完成、目标读取后返回时，不得在已关闭的调度器上登记 waiter。 */
    @Test
    void waitAgentRejectsAdmissionWhenCloseWinsDuringTargetRead() throws Exception {
        CountDownLatch targetRead = new CountDownLatch(1);
        CountDownLatch releaseTargetRead = new CountDownLatch(1);
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> {
                targetRead.countDown();
                try {
                    if (!releaseTargetRead.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("target read was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("target read was interrupted", failure);
                }
                yield Optional.of(task("thr_wait", "thr_root", TaskModels.State.RUNNING, 1));
            }
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
            CompletableFuture<TaskUseCase.WaitResult> waiting = CompletableFuture.supplyAsync(() ->
                    coordinator.waitAgents(Set.of("thr_wait"), Duration.ofSeconds(1), CancellationToken.none())
                            .toCompletableFuture().join());
            try {
                assertTrue(targetRead.await(1, TimeUnit.SECONDS));
                coordinator.close();
            } finally {
                releaseTargetRead.countDown();
            }

            java.util.concurrent.CompletionException failure = assertThrows(
                    java.util.concurrent.CompletionException.class, waiting::join);
            assertInstanceOf(IllegalStateException.class, failure.getCause());
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
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
            TaskRepositoryException wait = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.waitAgentsFrom("thr_requester", Set.of("thr_other"),
                            Duration.ofSeconds(1), CancellationToken.none()));
            TaskRepositoryException cancel = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.cancelFrom("thr_requester", "thr_other", 1));

            assertEquals(TaskRepositoryException.Code.PERMISSION_DENIED, wait.code());
            assertEquals(TaskRepositoryException.Code.PERMISSION_DENIED, cancel.code());
        }
    }

    /** 同根兄弟可以通信，但共享来源不能让一个任务等待或取消另一个任务。 */
    @Test
    void rejectsSiblingControlWithinSameRoot() {
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> Optional.of(task((String) args[0], "thr_root", TaskModels.State.RUNNING, 1));
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
            TaskRepositoryException wait = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.waitAgentsFrom("thr_sibling", Set.of("thr_target"),
                            Duration.ofSeconds(1), CancellationToken.none()));
            TaskRepositoryException cancel = assertThrows(TaskRepositoryException.class,
                    () -> coordinator.cancelFrom("thr_sibling", "thr_target", 1));
            assertEquals(TaskRepositoryException.Code.PERMISSION_DENIED, wait.code());
            assertEquals(TaskRepositoryException.Code.PERMISSION_DENIED, cancel.code());
        }
    }

    /** Task 取消逐次重读 Thread revision，并把运行中与所有排队 Turn 全部提交为取消终态。 */
    @Test
    void cancelsActiveAndQueuedTurnsWithFreshRevision() {
        AtomicReference<ThreadSnapshot> snapshot = new AtomicReference<>(threadWithTurns(5,
                List.of(turn("turn_active", "running"), turn("turn_queued", "queued"))));
        AtomicInteger cancellations = new AtomicInteger();
        Object owner = Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if (!"cancel".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String turnId = (String) args[0];
                    ThreadSnapshot before = snapshot.get();
                    long nextRevision = before.thread().revision() + 1;
                    List<ThreadSnapshot.Turn> turns = before.turns().stream().map(value ->
                            new ThreadSnapshot.Turn(value.turnId(), value.turnId().equals(turnId)
                                    ? "CANCELLED" : value.status(), value.requestedAt(), NOW,
                                    value.turnId().equals(turnId) ? NOW : value.completedAt(),
                                    value.errorCode(), value.changeSet(), value.mutationVersion(),
                                    value.modelRound(), value.sourceMessageId())).toList();
                    snapshot.set(threadWithTurns(nextRevision, turns));
                    cancellations.incrementAndGet();
                    return new TurnUseCase.CancelResult(true, turnId,
                            io.github.kongweiguang.ja.agent.domain.turn.TurnState.CANCELLED, nextRevision,
                            CompletableFuture.completedFuture(cancelledTurnResult(
                                    "thr_target", turnId, nextRevision)));
                });
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> Optional.of(task("thr_target", "thr_root", TaskModels.State.RUNNING, 3));
            case "attachedDescendants" -> List.of();
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase threadUseCase = proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.of(snapshot.get());
            }
            throw new UnsupportedOperationException(method.getName());
        });
        try (TaskCoordinator coordinator = coordinator(repository, threadUseCase,
                (TurnUseCase) owner, (ChildTurnScheduler) owner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
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
        AtomicReference<AdmissionProbe> probe = new AtomicReference<>();
        JaAdmissionParticipants participants = new JaAdmissionParticipants();
        AtomicInteger attempts = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTree" -> List.of();
            case "findTask" -> admitted.get() == null ? Optional.empty() : Optional.of(summary(admitted.get()));
            case "readTask" -> Optional.empty();
            case "applyChildAdmission" -> {
                TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                assertTaskAdmissionUsesCurrentStoreTransaction();
                admitted.set(child);
                yield null;
            }
            case "recordActivity" -> {
                attempts.incrementAndGet();
                throw new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT, "fixture race");
            }
            case "rootTurnIdForAdmission" -> "turn_parent";
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object owner = eventPublishingTurnOwner(participants, probe);
        try (TaskCoordinator coordinator = coordinator(repository, threads(),
                (TurnUseCase) owner, (ChildTurnScheduler) owner, workspaces(),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies(), participants)) {
            TaskUseCase.StartResult result = coordinator.spawnAgent(new TaskUseCase.SpawnCommand(
                    "thr_parent", "turn_parent", "review", new UserContent(List.of(new TextContent("review"))),
                    PREFERENCES, capabilityCeiling(PREFERENCES, Set.of())));

            assertEquals("thr_parent", result.task().lineage().rootThreadId());
            assertEquals(8, attempts.get());
        }
        assertAdmissionCommittedThroughOneConnection(probe.get());
    }

    /** 首个 StateChanged 成功投影后，startChild 返回前应已能读到 RUNNING 的 Task revision。
     * @author kongweiguang
     */
    @Test
    void projectsFirstRunningStateBeforeChildDispatchReturns() throws Exception {
        AtomicReference<TaskModels.Summary> projection = new AtomicReference<>();
        AtomicReference<AdmissionProbe> probe = new AtomicReference<>();
        AtomicBoolean stateVisibleBeforeDispatchReturn = new AtomicBoolean();
        AtomicInteger activityWrites = new AtomicInteger();
        AtomicReference<AgentIdentity> revisionIdentity = new AtomicReference<>();
        AtomicReference<Long> observedRevision = new AtomicReference<>();
        AtomicReference<CommittedTurnObservation> committedObservation = new AtomicReference<>();
        List<String> observerOrder = new ArrayList<>();
        JaAdmissionParticipants participants = new JaAdmissionParticipants();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTree" -> List.of();
            case "findTask" -> {
                TaskModels.Summary current = projection.get();
                yield current == null || !current.lineage().taskThreadId().equals(args[0])
                        ? Optional.empty() : Optional.of(current);
            }
            case "readTask" -> Optional.empty();
            case "applyChildAdmission" -> {
                TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                assertTaskAdmissionUsesCurrentStoreTransaction();
                TaskModels.Summary created = summary(child);
                projection.set(new TaskModels.Summary(created.lineage(), new TaskModels.Projection(
                        created.lineage().taskThreadId(), created.lineage().rootThreadId(), 0,
                        TaskModels.State.QUEUED, 5, null, 1, 0, 0, 0, "queued", null, null, NOW)));
                yield null;
            }
            case "recordActivity" -> {
                TaskModels.ActivityMutation mutation = (TaskModels.ActivityMutation) args[0];
                TaskModels.Summary current = projection.get();
                assertEquals(TaskModels.State.QUEUED, current.projection().state());
                assertEquals(0, mutation.expectedTaskRevision());
                assertEquals(TaskModels.State.RUNNING, mutation.state());
                assertEquals(TaskModels.ActivityKind.RESUMED, mutation.kind());
                assertEquals(1, activityWrites.incrementAndGet());
                TaskModels.Projection running = new TaskModels.Projection(
                        current.lineage().taskThreadId(), current.lineage().rootThreadId(), 1,
                        TaskModels.State.RUNNING, 6, null, 1, 0, 0, 0, "正在运行", NOW, null, NOW);
                TaskModels.Summary updated = new TaskModels.Summary(current.lineage(), running);
                projection.set(updated);
                yield updated;
            }
            case "rootTurnIdForAdmission" -> "turn_parent";
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object owner = eventPublishingTurnOwner(participants, probe, () -> {
            assertEquals(TaskModels.State.RUNNING, projection.get().projection().state());
            stateVisibleBeforeDispatchReturn.set(true);
        });
        TaskEventSink subscriber = new TaskEventSink() {
            /** Task Activity 仅用于断言同步投影已提交，不影响 Child 生命周期 sink。 */
            @Override public java.util.concurrent.CompletionStage<Void> publish(TaskEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** 捕获真实 prepareTurn 包装后的两个隐藏 fence 与可见事件顺序。 */
            @Override public ConversationTurnEventSink turnEvents() {
                return new ConversationTurnEventSink() {
                    /** 首个状态事件必须在其提交观察回调之后到达客户端 sink。 */
                    @Override public java.util.concurrent.CompletionStage<Void> publish(TurnEvent event) {
                        observerOrder.add("event");
                        return CompletableFuture.completedFuture(null);
                    }

                    /** 此场景不产生标题更新，元数据发布不参与提交 fence 断言。 */
                    @Override public java.util.concurrent.CompletionStage<Void> publish(
                            io.github.kongweiguang.ja.conversation.domain.model.ThreadMetadataEvent event) {
                        return CompletableFuture.completedFuture(null);
                    }

                    /** Admission revision 建立活动流水位，必须先于事件投影送达。 */
                    @Override public void onThreadRevision(AgentIdentity identity, long revision) {
                        revisionIdentity.set(identity);
                        observedRevision.set(revision);
                        observerOrder.add("revision");
                    }

                    /** 持久事件的精确同事务边界必须从 Child wrapper 原样透传。 */
                    @Override public void observeCommittedTurn(String threadId, String turnId, long threadRevision,
                                                               long turnMutationVersion, int modelRound,
                                                               TurnEvent event, Instant occurredAt) {
                        committedObservation.set(new CommittedTurnObservation(threadId, turnId, threadRevision,
                                turnMutationVersion, modelRound, event, occurredAt));
                        observerOrder.add("committed");
                    }
                };
            }
        };
        try (TaskCoordinator coordinator = coordinator(repository, threads(),
                (TurnUseCase) owner, (ChildTurnScheduler) owner, workspaces(),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies(), participants);
             AutoCloseable ignored = coordinator.subscribe(subscriber)) {
            TaskUseCase.StartResult result = coordinator.spawnAgent(new TaskUseCase.SpawnCommand(
                    "thr_parent", "turn_parent", "review", new UserContent(List.of(new TextContent("review"))),
                    PREFERENCES, capabilityCeiling(PREFERENCES, Set.of())));

            assertTrue(stateVisibleBeforeDispatchReturn.get());
            assertEquals(TaskModels.State.RUNNING, result.task().projection().state());
            assertEquals(1, activityWrites.get());
            AgentIdentity expectedIdentity = JaAdmissionParticipants.identityFor(
                    result.task().lineage().taskThreadId(), result.turnId());
            assertEquals(expectedIdentity, revisionIdentity.get());
            assertEquals(1L, observedRevision.get());
            assertEquals(List.of("revision", "committed", "event"), observerOrder,
                    "Admission and committed-turn fences must precede the event sent to the Child timeline");
            assertEquals(new CommittedTurnObservation(result.task().lineage().taskThreadId(), result.turnId(),
                    1L, 2L, 0, new TurnEvent.StateChanged(new TurnEvent.Context("evt_running",
                    result.task().lineage().taskThreadId(), result.turnId(), 1, 2, NOW),
                    io.github.kongweiguang.ja.agent.domain.turn.TurnState.QUEUED,
                    io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING), NOW),
                    committedObservation.get());
        }
        assertAdmissionCommittedThroughOneConnection(probe.get());
    }

    /**
     * 固定隐藏提交回调的全部参数，避免只验证 wrapper 曾被调用而漏掉版本水位。
     *
     * @param threadId 提交所属会话标识
     * @param turnId 提交所属执行标识
     * @param threadRevision 提交后的会话修订水位
     * @param turnMutationVersion 提交后的执行变更水位
     * @param modelRound 提交对应的模型轮次
     * @param event 已提交的权威执行事件
     * @param occurredAt 已提交事件的发生时间
     * @author kongweiguang
     */
    private record CommittedTurnObservation(String threadId,
                                            String turnId,
                                            long threadRevision,
                                            long turnMutationVersion,
                                            int modelRound,
                                            TurnEvent event,
                                            Instant occurredAt) {
    }

    /**
     * TurnService 的 shutdown fence 触发取消回调时，TaskCoordinator 尚未完成侧聊收口，
     * 该回调必须继续可消费；完全关闭后迟到回调则应安静退出而不是制造重试。
     */
    @Test
    void acceptsCancellationCallbackDuringCloseAndIgnoresLateCallbackAfterClose() {
        AtomicInteger descendantReads = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTemporarySideChats" -> List.of();
            case "attachedDescendants" -> {
                descendantReads.incrementAndGet();
                yield List.of();
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, unsupported(ThreadUseCase.class),
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
            coordinator.close();
            assertDoesNotThrow(() -> coordinator.cancellationClaimed("thr_parent", "turn_parent"));
        }

        assertEquals(0, descendantReads.get());
    }

    /**
     * 关闭前置 hook 必须先完成持久 closing、Thread 取消和 owner 停止，再允许 Task purge，
     * 这样侧聊临时数据不会在隐藏执行仍持有资源时被物理清除。
     */
    @Test
    void closesTemporarySideChatsBeforePurgingTheirTaskTree() {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<ThreadSnapshot> thread = new AtomicReference<>(sideThreadWithTurn("RUNNING", 1));
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTemporarySideChats" -> List.of(new TaskRepository.TemporarySideChat(
                    "thr_side", TaskRepository.TemporarySideChatState.OPEN));
            case "findTask" -> Optional.of(sideTask(TaskModels.State.RUNNING, 1));
            case "beginSideChatClose" -> {
                order.add("begin");
                yield new TaskModels.SideChatCloseState("thr_side", "thr_parent",
                        TaskModels.SideChatClosePhase.CLOSING, List.of("thr_side"));
            }
            case "deleteClosedSideChat" -> {
                order.add("purge");
                yield new TaskModels.SideChatCloseReceipt("thr_side", "thr_parent", List.of("thr_side"));
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase threads = proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.of(thread.get());
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if (!"cancel".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    order.add("cancel");
                    thread.set(sideThreadWithTurn("CANCELLED", 2));
                    return new TurnUseCase.CancelResult(true, "turn_side",
                            io.github.kongweiguang.ja.agent.domain.turn.TurnState.CANCELLED, 2,
                            CompletableFuture.completedFuture(cancelledTurnResult(
                                    "thr_side", "turn_side", 2)));
                });
        try (TaskCoordinator coordinator = coordinator(repository, threads,
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
            coordinator.bindSideChatOwnerController((owners, deadlineNanos) -> order.add("owner"));
            coordinator.closeTemporarySideChats(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
            assertEquals(List.of("begin", "owner", "cancel", "purge"), order);
        }
    }

    /** 一次关机枚举含父子两个临时 root 时，父回执覆盖的普通 Task 与子 root 都只关闭一次。 */
    @Test
    void shutdownSkipsNestedRootsAlreadyPurgedByParentReceipt() {
        AtomicInteger begins = new AtomicInteger();
        AtomicInteger purges = new AtomicInteger();
        AtomicReference<Set<String>> stoppedOwners = new AtomicReference<>();
        AtomicReference<TaskModels.SideChatCloseReceipt> committed = new AtomicReference<>();
        List<String> closedThreadIds = List.of("thr_a_side", "thr_agent", "thr_b_nested");
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTemporarySideChats" -> List.of(
                    new TaskRepository.TemporarySideChat("thr_a_side", TaskRepository.TemporarySideChatState.OPEN),
                    new TaskRepository.TemporarySideChat("thr_b_nested", TaskRepository.TemporarySideChatState.OPEN));
            case "beginSideChatClose" -> {
                begins.incrementAndGet();
                assertEquals("thr_a_side", args[0], "nested root should be covered by the parent receipt");
                yield new TaskModels.SideChatCloseState("thr_a_side", "thr_parent",
                        TaskModels.SideChatClosePhase.CLOSING, closedThreadIds);
            }
            case "deleteClosedSideChat" -> {
                purges.incrementAndGet();
                TaskModels.SideChatCloseReceipt receipt = new TaskModels.SideChatCloseReceipt(
                        "thr_a_side", "thr_parent", closedThreadIds);
                committed.set(receipt);
                yield receipt;
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase threads = proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.empty();
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, threads, (TurnUseCase) turnOwner,
                (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
            coordinator.bindSideChatOwnerController((owners, deadlineNanos) -> stoppedOwners.set(Set.copyOf(owners)));

            coordinator.closeTemporarySideChats(System.nanoTime() + TimeUnit.SECONDS.toNanos(2));

            assertEquals(1, begins.get());
            assertEquals(1, purges.get());
            assertEquals(Set.copyOf(closedThreadIds), stoppedOwners.get());
            assertEquals(closedThreadIds, committed.get().closedThreadIds());
        }
    }

    /** 进程关闭等待已开始的侧聊关闭时也必须受全局截止线约束，超时期间不能提前 purge。 */
    @Test
    void shutdownWaitForExistingSideChatCloseHonorsItsDeadline() throws Exception {
        java.util.concurrent.CountDownLatch ownerStopEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseOwnerStop = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<ThreadSnapshot> thread = new AtomicReference<>(sideThreadWithTurn("RUNNING", 1));
        AtomicInteger purges = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTemporarySideChats" -> List.of(new TaskRepository.TemporarySideChat(
                    "thr_side", TaskRepository.TemporarySideChatState.OPEN));
            case "findTask" -> Optional.of(sideTask(TaskModels.State.RUNNING, 1));
            case "beginSideChatClose" -> new TaskModels.SideChatCloseState(
                    "thr_side", "thr_parent", TaskModels.SideChatClosePhase.CLOSING, List.of("thr_side"));
            case "deleteClosedSideChat" -> {
                purges.incrementAndGet();
                yield new TaskModels.SideChatCloseReceipt("thr_side", "thr_parent", List.of("thr_side"));
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase threads = proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.of(thread.get());
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if (!"cancel".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    thread.set(sideThreadWithTurn("CANCELLED", 2));
                    return new TurnUseCase.CancelResult(true, "turn_side",
                            io.github.kongweiguang.ja.agent.domain.turn.TurnState.CANCELLED, 2,
                            CompletableFuture.completedFuture(cancelledTurnResult(
                                    "thr_side", "turn_side", 2)));
                });
        TaskCoordinator coordinator = coordinator(repository, threads, (TurnUseCase) turnOwner,
                (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies());
        AtomicReference<RuntimeException> closeFailure = new AtomicReference<>();
        try (java.util.concurrent.ExecutorService executor =
                     java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Future<?> explicitClose = null;
            try {
                coordinator.bindSideChatOwnerController((owners, deadlineNanos) -> {
                    ownerStopEntered.countDown();
                    try {
                        releaseOwnerStop.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("fixture owner stop was interrupted", failure);
                    }
                });
                explicitClose = executor.submit(() -> coordinator.closeSideChat("thr_side"));
                assertTrue(ownerStopEntered.await(1, TimeUnit.SECONDS));

                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
                java.util.concurrent.Future<?> shutdownClose = executor.submit(
                        () -> coordinator.closeTemporarySideChats(deadline));
                java.util.concurrent.ExecutionException failure = assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> shutdownClose.get(1, TimeUnit.SECONDS));

                assertInstanceOf(TaskRepositoryException.class, failure.getCause());
                assertEquals(TaskRepositoryException.Code.INVALID_STATE,
                        ((TaskRepositoryException) failure.getCause()).code());
                assertEquals(0, purges.get());
            } finally {
                releaseOwnerStop.countDown();
                if (explicitClose != null) {
                    explicitClose.get(2, TimeUnit.SECONDS);
                }
                try {
                    coordinator.close();
                } catch (RuntimeException failure) {
                    closeFailure.set(failure);
                }
            }
        }

        assertInstanceOf(TaskRepositoryException.class, closeFailure.get());
        assertEquals(TaskRepositoryException.Code.INVALID_STATE,
                ((TaskRepositoryException) closeFailure.get()).code());
        assertEquals(1, purges.get());
    }

    /** 并发关闭共享数据库的完整回执，避免同一树被重复取消、purge 或只向一个调用方确认。 */
    @Test
    void concurrentSideChatClosuresShareCompleteReceipt() throws Exception {
        java.util.concurrent.CountDownLatch ownerStopEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseOwnerStop = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger ownerStops = new AtomicInteger();
        AtomicInteger purges = new AtomicInteger();
        List<String> closedThreadIds = List.of("thr_child", "thr_side");
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "beginSideChatClose" -> new TaskModels.SideChatCloseState("thr_side", "thr_parent",
                    closed.get() ? TaskModels.SideChatClosePhase.CLOSED : TaskModels.SideChatClosePhase.CLOSING,
                    List.of("thr_side", "thr_child"));
            case "deleteClosedSideChat" -> {
                purges.incrementAndGet();
                closed.set(true);
                yield new TaskModels.SideChatCloseReceipt("thr_side", "thr_parent", closedThreadIds);
            }
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase threads = proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.of(sideThreadWithTurn("COMPLETED", 1));
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = passiveTurnOwner();
        try (TaskCoordinator coordinator = coordinator(repository, threads, (TurnUseCase) turnOwner,
                (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies());
             java.util.concurrent.ExecutorService executor =
                     java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            coordinator.bindSideChatOwnerController((owners, deadlineNanos) -> {
                ownerStops.incrementAndGet();
                ownerStopEntered.countDown();
                try {
                    releaseOwnerStop.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture owner stop was interrupted", failure);
                }
            });
            java.util.concurrent.Future<TaskModels.SideChatCloseReceipt> first =
                    executor.submit(() -> coordinator.closeSideChat("thr_side"));
            assertTrue(ownerStopEntered.await(1, TimeUnit.SECONDS));
            java.util.concurrent.Future<TaskModels.SideChatCloseReceipt> second =
                    executor.submit(() -> coordinator.closeSideChat("thr_side"));
            releaseOwnerStop.countDown();

            TaskModels.SideChatCloseReceipt expected =
                    new TaskModels.SideChatCloseReceipt("thr_side", "thr_parent", closedThreadIds);
            assertEquals(expected, first.get(2, TimeUnit.SECONDS));
            assertEquals(expected, second.get(2, TimeUnit.SECONDS));
            assertEquals(1, ownerStops.get());
            assertEquals(1, purges.get());
        } finally {
            releaseOwnerStop.countDown();
        }
    }

    /**
     * 正常退出必须走与显式关闭相同的 owner 停止和 purge 顺序，重复 close 只能复用已完成结果，
     * 不能再次枚举、停止或删除侧聊，避免应用生命周期回调重复释放资源。
     */
    @Test
    void normalCloseStopsSideChatOwnersAndPurgesExactlyOnce() {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<ThreadSnapshot> thread = new AtomicReference<>(sideThreadWithTurn("RUNNING", 1));
        AtomicReference<Set<String>> stoppedOwners = new AtomicReference<>();
        AtomicInteger listed = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTemporarySideChats" -> {
                listed.incrementAndGet();
                yield List.of(new TaskRepository.TemporarySideChat(
                        "thr_side", TaskRepository.TemporarySideChatState.OPEN));
            }
            case "findTask" -> Optional.of(sideTask(TaskModels.State.RUNNING, 1));
            case "beginSideChatClose" -> {
                order.add("begin");
                yield new TaskModels.SideChatCloseState("thr_side", "thr_parent",
                        TaskModels.SideChatClosePhase.CLOSING, List.of("thr_side"));
            }
            case "deleteClosedSideChat" -> {
                order.add("purge");
                yield new TaskModels.SideChatCloseReceipt("thr_side", "thr_parent", List.of("thr_side"));
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase threads = proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.of(thread.get());
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Object turnOwner = Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if (!"cancel".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    order.add("cancel");
                    thread.set(sideThreadWithTurn("CANCELLED", 2));
                    return new TurnUseCase.CancelResult(true, "turn_side",
                            io.github.kongweiguang.ja.agent.domain.turn.TurnState.CANCELLED, 2,
                            CompletableFuture.completedFuture(cancelledTurnResult(
                                    "thr_side", "turn_side", 2)));
                });
        try (TaskCoordinator coordinator = coordinator(repository, threads,
                (TurnUseCase) turnOwner, (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies())) {
            coordinator.bindSideChatOwnerController((owners, deadlineNanos) -> {
                stoppedOwners.set(Set.copyOf(owners));
                order.add("owner");
            });

            coordinator.close();
            coordinator.close();

            assertEquals(List.of("begin", "owner", "cancel", "purge"), order);
            assertEquals(Set.of("thr_side"), stoppedOwners.get());
            assertEquals(1, listed.get());
            assertThrows(IllegalStateException.class, () -> coordinator.closeSideChat("thr_side"));
        }
    }

    /** 关闭只在 purge 成功后发布；通知失败保留权威回执，CLOSED 重试再次广播同一集合。 */
    @Test
    void purgeFailureKeepsSideChatClosingAndCloseNotificationsReplayTheCommittedReceipt() throws Exception {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Set<String>> stoppedOwners = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<TaskEvent.Closed> closedEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<TaskRepository.TemporarySideChatState> marker = new AtomicReference<>(
                TaskRepository.TemporarySideChatState.OPEN);
        AtomicBoolean failFirstNotification = new AtomicBoolean(true);
        AtomicInteger purges = new AtomicInteger();
        Set<String> expectedOwners = Set.of("thr_side", "thr_child");
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "findTask" -> Optional.of(sideTask(TaskModels.State.RUNNING, 1));
            case "beginSideChatClose" -> {
                order.add("begin");
                if (marker.get() == null) {
                    yield new TaskModels.SideChatCloseState("thr_side", "thr_parent",
                            TaskModels.SideChatClosePhase.CLOSED, List.of("thr_side", "thr_child"));
                }
                marker.set(TaskRepository.TemporarySideChatState.CLOSING);
                yield new TaskModels.SideChatCloseState("thr_side", "thr_parent",
                        TaskModels.SideChatClosePhase.CLOSING, List.of("thr_side", "thr_child"));
            }
            case "deleteClosedSideChat" -> {
                order.add("purge");
                assertEquals("thr_parent", args[1], "purge must revalidate the frozen event owner root");
                assertEquals(List.of("thr_child", "thr_side"), args[2]);
                if (purges.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient purge failure");
                }
                marker.set(null);
                yield new TaskModels.SideChatCloseReceipt("thr_side", "thr_parent",
                        List.of("thr_side", "thr_child"));
            }
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ThreadUseCase threads = proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.empty();
            }
            throw new UnsupportedOperationException(method.getName());
        });

        Object turnOwner = passiveTurnOwner();
        TaskCoordinator coordinator = coordinator(repository, threads, (TurnUseCase) turnOwner,
                (ChildTurnScheduler) turnOwner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies());
        TaskEventSink sink = new TaskEventSink() {
            /**
             * 首次通知故意返回失败 Future，验证异步观察者故障不会反向否定已提交的 purge；后续成功用于验证回执重放。
             */
            @Override
            public java.util.concurrent.CompletionStage<Void> publish(TaskEvent event) {
                if (event instanceof TaskEvent.Closed closed) {
                    closedEvents.add(closed);
                    order.add("event");
                    if (failFirstNotification.compareAndSet(true, false)) {
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("simulated event transport failure"));
                    }
                }
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            /** 本测试只验证 Task 关闭通知，不创建 Child Turn；使用空 Timeline sink 避免引入无关流式状态。 */
            @Override
            public ConversationTurnEventSink turnEvents() {
                return ConversationTurnEventSink.noop();
            }
        };
        AutoCloseable subscription = coordinator.subscribe(sink);
        try (coordinator; subscription) {
            coordinator.bindSideChatOwnerController((owners, deadlineNanos) -> {
                stoppedOwners.add(Set.copyOf(owners));
                order.add("owner");
            });

            assertThrows(IllegalStateException.class, () -> coordinator.closeSideChat("thr_side"));

            assertTrue(closedEvents.isEmpty(), "a failed purge must not publish a closed tombstone");
            assertEquals(TaskRepository.TemporarySideChatState.CLOSING, marker.get());
            assertTrue(coordinator.isSideChatClosing("thr_side"),
                    "Plan admission must remain closed while the persistent marker is CLOSING");
            TaskModels.SideChatCloseReceipt receipt = coordinator.closeSideChat("thr_side");
            assertEquals(List.of("thr_child", "thr_side"), receipt.closedThreadIds());
            assertEquals("thr_parent", receipt.ownerRootThreadId());

            TaskModels.SideChatCloseReceipt repeated = coordinator.closeSideChat("thr_side");
            assertEquals(receipt, repeated);
            assertEquals(List.of(new TaskEvent.Closed(receipt), new TaskEvent.Closed(receipt)), closedEvents);
            assertEquals(List.of("begin", "owner", "purge", "begin", "owner", "purge", "event",
                    "begin", "event"), order);
            assertEquals(List.of(expectedOwners, expectedOwners), stoppedOwners);
            assertEquals(2, purges.get());
            assertNull(marker.get());
            assertFalse(coordinator.isSideChatClosing("thr_side"));
        }
    }

    /** 运行已注册的两阶段准入，并让通用投影与 Task 写入共享同一未提交连接。 */
    private static JaAdmissionReceipt runPreparedAdmission(JaAdmissionParticipants admissions,
                                                            TurnStartRequest request,
                                                            AtomicReference<AdmissionProbe> lastAdmission) {
        AgentIdentity identity = JaAdmissionParticipants.identityFor(request.threadId(), request.turnId());
        JaAdmissionParticipants.PreparedAdmission prepared = admissions.find(identity).orElseThrow(() ->
                new IllegalStateException("Child admission was not registered before scheduling"));
        AdmissionProbe probe = new AdmissionProbe();
        probe.identity = identity;
        prepared.runtimeSnapshot().ifPresent(snapshot -> {
            assertEquals(identity, snapshot.requestIdentity());
            assertEquals(request.threadId(), snapshot.taskIdentity().orElseThrow().taskThreadId());
            assertEquals(TaskCapabilityCeilingPort.Kind.SUBAGENT,
                    snapshot.taskIdentity().orElseThrow().kind());
            TaskAgentToolGateway gateway = new TaskAgentToolGateway(ignored -> Optional.empty(), admissions);
            assertSame(snapshot, gateway.read(identity));
            for (AgentIdentity other : List.of(
                    new AgentIdentity("ja.other", identity.threadId(), identity.turnId(), identity.userId()),
                    new AgentIdentity(identity.tenantId(), identity.threadId() + "_wrong",
                            identity.turnId(), identity.userId()),
                    new AgentIdentity(identity.tenantId(), identity.threadId(),
                            identity.turnId() + "_wrong", identity.userId()),
                    new AgentIdentity(identity.tenantId(), identity.threadId(), identity.turnId(), "user_other"))) {
                assertTrue(admissions.find(other).isEmpty());
                assertThrows(TaskRepositoryException.class, () -> gateway.read(other));
            }
        });
        lastAdmission.set(probe);
        ACTIVE_ADMISSION.set(probe);
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local"), identity.turnId());
        TurnExecutionState.Ready initial = new TurnExecutionState.Ready(
                new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
        AgentStore.RecordWrite user = RECORDS.encodeUser("record_" + request.turnId(),
                "message_" + request.turnId(), request.content(), request.requestedAt());
        AgentStoreTransactionContext transaction = new AgentStoreTransactionContext(TransactionResource.of(probe.connection), execution,
                0, request.expectedThreadRevision(), -1, request.expectedThreadRevision() + 1,
                request.initialTurnMutationVersion() + 1, 0, 0, AgentStore.TransactionKind.ADMISSION,
                "QUEUED", CURSORS.schemaVersion(), CURSORS.write(initial), List.of(user), List.of(),
                Optional.empty(), null, null, request.requestedAt());
        try {
            AgentStoreConnectionScope.call(probe.connection, () -> {
                if (JdbcTransactionResources.connection(transaction) != probe.connection) {
                    throw new IllegalStateException("admission transaction changed its JDBC connection");
                }
                prepared.transactionParticipant().beforeProjection(transaction);
                return null;
            });
            probe.phases.add("host-before-projection");

            // 模拟 Ja 通用 Thread、Turn 与唯一 USER 事实已经在 AgentStore 事务内投影。
            RECORDS.decodeUser(new AgentStore.StoredRecord(user.recordId(), user.recordType(),
                    user.payloadSchemaVersion(), 1, user.payload(), user.occurredAt()));
            probe.phases.add("core-ja-projection");

            AgentStoreConnectionScope.call(probe.connection, () -> {
                if (JdbcTransactionResources.connection(transaction) != probe.connection) {
                    throw new IllegalStateException("admission transaction changed its JDBC connection");
                }
                prepared.transactionParticipant().afterProjection(transaction);
                probe.phases.add("task-after-projection");
                return null;
            });
            if (probe.commits != 0 || probe.rollbacks != 0 || probe.closes != 0) {
                throw new AssertionError("Ja admission participant changed AgentStore transaction ownership");
            }
            return new JaAdmissionReceipt(request.threadId(), request.turnId(),
                    request.expectedThreadRevision() + 1, request.initialTurnMutationVersion() + 1, null);
        } catch (Exception failure) {
            throw new IllegalStateException("fake AgentStore admission failed", failure);
        } finally {
            ACTIVE_ADMISSION.remove();
        }
    }

    /** 活跃消息按稳定 Mailbox 身份入 Core MESSAGE；模型正文也必须携带真实发送方来源封套。 */
    @Test
    void routesPendingMailboxMessageToActiveTurnWithStableProvenance() {
        TaskModels.MailboxMessage pending = mailboxMessage(TaskModels.MailboxState.PENDING, null, null);
        AtomicReference<TaskModels.MailboxMessage> persisted = new AtomicReference<>(pending);
        TaskMailboxPort mailboxes = new TaskMailboxPort() {
            /** 同一已提交幂等行供成功后校验BOUND owner。 */
            @Override public Optional<TaskModels.MailboxMessage> findByIdempotency(
                    String senderThreadId, String operationKey) {
                TaskModels.MailboxMessage current = persisted.get();
                return current.senderThreadId().equals(senderThreadId)
                        && current.operationKey().equals(operationKey) ? Optional.of(current) : Optional.empty();
            }

            /** 此测试验证活动投递，不预加载 idle admission 队列。 */
            @Override public List<TaskModels.MailboxMessage> listPending(String targetThreadId, int limit) {
                return List.of();
            }
        };
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "enqueueMessage" -> new TaskModels.MessageEnqueueReceipt(pending, true);
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        AtomicInteger queued = new AtomicInteger();
        TurnUseCase.ActiveTurn active = new TurnUseCase.ActiveTurn("thr_root", "turn_active",
                io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING, 6, 3);
        Object owner = Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if ("findActiveTurn".equals(method.getName())) {
                        return Optional.of(active);
                    }
                    if ("enqueueTaskMessage".equals(method.getName())) {
                        queued.incrementAndGet();
                        AgentInputCommand.Enqueue command = (AgentInputCommand.Enqueue) args[1];
                        AgentInputCommand.Enqueue expected = TaskMailboxQueueCommandMapper.toQueueCommand(
                                pending, JSON);
                        assertEquals(expected, command);
                        persisted.set(mailboxMessage(TaskModels.MailboxState.BOUND, active.turnId(), null));
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        TaskUseCase.MessageCommand request = new TaskUseCase.MessageCommand(
                pending.senderThreadId(), pending.targetThreadId(), pending.content(),
                pending.operationKey(), pending.causalTurnId());

        try (TaskCoordinator coordinator = coordinator(repository, mailboxes, unsupported(ThreadUseCase.class),
                (TurnUseCase) owner, (ChildTurnScheduler) owner, unsupported(WorkspaceUseCase.class),
                Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies(), new JaAdmissionParticipants())) {
            TaskUseCase.MessageReceipt receipt = coordinator.sendMessage(request);

            assertEquals(new TaskUseCase.MessageReceipt(pending.messageId(), pending.sequence()), receipt);
        }

        assertEquals(1, queued.get());
        assertEquals(TaskModels.MailboxState.BOUND, persisted.get().state());
        assertEquals("turn_active", persisted.get().boundTurnId());
    }

    /** 活跃队列满时只保留已接纳的PENDING Mailbox事实，不阻塞发送方等待Turn容量。 */
    @Test
    void leavesMailboxPendingWhenActiveTurnQueueIsFull() {
        TaskModels.MailboxMessage pending = mailboxMessage(TaskModels.MailboxState.PENDING, null, null);
        AtomicInteger attempts = new AtomicInteger();
        TaskRepository repository = proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "enqueueMessage" -> new TaskModels.MessageEnqueueReceipt(pending, true);
            case "listTemporarySideChats" -> List.of();
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        TurnUseCase.ActiveTurn active = new TurnUseCase.ActiveTurn("thr_root", "turn_active",
                io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING, 6, 3);
        Object owner = Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if ("findActiveTurn".equals(method.getName())) {
                        return Optional.of(active);
                    }
                    if ("enqueueTaskMessage".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw TurnUseCase.InputMutationException.of(TurnUseCase.InputMutationFailure.QUEUE_FULL);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        TaskUseCase.MessageCommand request = new TaskUseCase.MessageCommand(
                pending.senderThreadId(), pending.targetThreadId(), pending.content(),
                pending.operationKey(), pending.causalTurnId());

        try (TaskCoordinator coordinator = coordinator(repository, emptyMailboxes(),
                unsupported(ThreadUseCase.class), (TurnUseCase) owner, (ChildTurnScheduler) owner,
                unsupported(WorkspaceUseCase.class), Clock.fixed(NOW, ZoneOffset.UTC), enabledPolicies(),
                new JaAdmissionParticipants())) {
            assertEquals(pending.messageId(), coordinator.sendMessage(request).messageId());
        }

        assertEquals(1, attempts.get());
        assertEquals(TaskModels.MailboxState.PENDING, pending.state());
    }

    /** Task 持久适配必须在 JaAgentStore 绑定的同连接 scope 内，且只能发生在通用投影之后。 */
    private static void assertTaskAdmissionUsesCurrentStoreTransaction() {
        AdmissionProbe probe = ACTIVE_ADMISSION.get();
        if (probe == null) {
            throw new AssertionError("Task admission ran outside AgentStore callback");
        }
        assertSame(probe.connection, AgentStoreConnectionScope.current().orElseThrow());
        assertEquals(List.of("host-before-projection", "core-ja-projection"), probe.phases);
    }

    /** 证明模拟 Ja admission 的所有业务阶段只借用连接，事务提交与关闭仍属于 AgentStore。 */
    private static void assertAdmissionCommittedThroughOneConnection(AdmissionProbe probe) {
        org.junit.jupiter.api.Assertions.assertNotNull(probe);
        assertEquals(List.of("host-before-projection", "core-ja-projection", "task-after-projection"),
                probe.phases);
        assertEquals(0, probe.commits);
        assertEquals(0, probe.rollbacks);
        assertEquals(0, probe.closes);
    }

    /** 测试仍装配生产构造器要求的显式准入注册表和严格存储 codec。 */
    private static TaskCoordinator coordinator(TaskRepository tasks, ThreadUseCase threads,
                                               TurnUseCase turns, ChildTurnScheduler scheduler,
                                               WorkspaceUseCase workspaces, Clock clock,
                                               SubagentPolicyRepository policies) {
        return coordinator(tasks, threads, turns, scheduler, workspaces, clock, policies,
                new JaAdmissionParticipants());
    }

    /** 模拟 Store 开启的 JDBC 事务，并记录宿主参与者是否越权提交、回滚或关闭连接。 */
    private static final class AdmissionProbe {
        private AgentIdentity identity;
        private final java.util.ArrayList<String> phases = new java.util.ArrayList<>();
        private int commits;
        private int rollbacks;
        private int closes;
        private final Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAutoCommit" -> false;
                        case "commit" -> { commits++; yield null; }
                        case "rollback" -> { rollbacks++; yield null; }
                        case "close" -> { closes++; yield null; }
                        case "isClosed" -> closes > 0;
                        case "toString" -> "FakeAgentStoreConnection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    /** 活跃 Child 用例共享同一 registry，使队列 owner 只读取本次同步提交的冻结准入参与者。 */
    private static TaskCoordinator coordinator(TaskRepository tasks, ThreadUseCase threads,
                                               TurnUseCase turns, ChildTurnScheduler scheduler,
                                               WorkspaceUseCase workspaces, Clock clock,
                                               SubagentPolicyRepository policies,
                                               JaAdmissionParticipants admissions) {
        return coordinator(tasks, emptyMailboxes(), threads, turns, scheduler, workspaces, clock,
                policies, admissions);
    }

    /** 显式 Mailbox 查询 fixture 供通信路由测试验证幂等与同事务绑定。 */
    private static TaskCoordinator coordinator(TaskRepository tasks, TaskMailboxPort mailboxes,
                                               ThreadUseCase threads, TurnUseCase turns,
                                               ChildTurnScheduler scheduler, WorkspaceUseCase workspaces,
                                               Clock clock, SubagentPolicyRepository policies,
                                               JaAdmissionParticipants admissions) {
        return new TaskCoordinator(tasks, mailboxes, threads, turns, scheduler, workspaces, clock,
                policies, new TaskAdmissionGatewayAdapter(tasks, admissions, RECORDS, CURSORS), JSON,
                new ConversationBoundaryAdapter());
    }

    /** 仅在不覆盖通信流程的用例中提供显式空 Mailbox 读模型，避免测试隐含本地存储降级。 */
    private static TaskMailboxPort emptyMailboxes() {
        return new TaskMailboxPort() {
            /** 这些用例不创建 Mailbox 事实；未观察到身份必须明确返回不存在。 */
            @Override public Optional<TaskModels.MailboxMessage> findByIdempotency(
                    String senderThreadId, String operationKey) {
                return Optional.empty();
            }

            /** 无通信测试不携带预留消息，保持独立 admission 用例的输入边界。 */
            @Override public List<TaskModels.MailboxMessage> listPending(String targetThreadId, int limit) {
                return List.of();
            }
        };
    }

    /** Repository fake 只实现 spawn 路径，并从同一 Store participant 写入 Task 投影。 */
    private static TaskRepository repository(AtomicReference<TaskModels.ChildAdmission> admitted) {
        return proxy(TaskRepository.class, (method, args) -> switch (method.getName()) {
            case "listTree" -> List.of();
            case "findTask" -> {
                TaskModels.ChildAdmission child = admitted.get();
                yield child == null || !child.childThread().threadId().equals(args[0])
                        ? Optional.empty() : Optional.of(summary(child));
            }
            case "rootTurnIdForAdmission" -> "turn_parent";
            case "readTask" -> Optional.empty();
            case "beginSideChatClose", "deleteClosedSideChat" ->
                    throw new UnsupportedOperationException("side chat close is outside this fixture");
            case "listTemporarySideChats" -> List.of();
            case "applyChildAdmission" -> {
                TaskModels.ChildAdmission child = (TaskModels.ChildAdmission) args[0];
                assertTaskAdmissionUsesCurrentStoreTransaction();
                TurnAdmission turn = (TurnAdmission) args[1];
                admitted.set(child);
                yield null;
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** Thread fake 返回创建 Subagent 所需的父元数据，不提供历史正文。 */
    private static ThreadUseCase threads() {
        ThreadSummary parent = new ThreadSummary("thr_parent", "ws_test", "Parent", "project", PREFERENCES, ThreadSummary.Status.ACTIVE, false, null, true, 7, NOW, NOW);
        ThreadSnapshot snapshot = new ThreadSnapshot(parent, List.of(), List.of(), null, null, null);
        return proxy(ThreadUseCase.class, (method, args) -> {
            if ("readThread".equals(method.getName())) {
                return Optional.of(snapshot);
            }
            throw new UnsupportedOperationException(method.getName());
        });
    }

    /** 带计数版本用于证明公开幂等重试不会再次进入 TurnQueue 调度边界。 */
    private static Object turnOwner(AtomicInteger starts) {
        return turnOwner(new JaAdmissionParticipants(), starts, new AtomicReference<>());
    }

    /** 模拟同步 Store admission：Ja host 前置、通用投影、Task 后置都持有完全相同的 JDBC 连接。 */
    private static Object turnOwner(JaAdmissionParticipants admissions, AtomicInteger starts,
                                    AtomicReference<AdmissionProbe> lastAdmission) {
        return Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if ("startChild".equals(method.getName())) {
                        starts.incrementAndGet();
                        TurnStartRequest request = (TurnStartRequest) args[0];
                        JaAdmissionReceipt receipt = runPreparedAdmission(admissions, request, lastAdmission);
                        return new TurnUseCase.Accepted(request.threadId(), request.turnId(),
                                receipt.threadRevision(), true, CompletableFuture.completedFuture(null));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Workspace fake 只返回已绑定路径，确保测试不触碰真实文件系统。 */
    private static WorkspaceUseCase workspaces() {
        Workspace workspace = new Workspace("ws_test", Path.of("C:\\ja-task-coordinator"), "Fixture", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, 1);
        return proxy(WorkspaceUseCase.class, (method, args) -> {
            if ("requireOpenWorkspace".equals(method.getName())) {
                return workspace;
            }
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
                1, TaskModels.State.IDLE, 1, null, 1, 0, 0, 0,
                "idle", null, null, NOW);
        return new TaskModels.Summary(lineage, projection);
    }

    /** 构造包含首个 CREATED activity 的详情回读，并允许投影状态与 admission 回执刻意不同。 */
    private static TaskModels.Detail detail(TaskModels.ChildAdmission child, TaskModels.Summary task) {
        io.github.kongweiguang.ja.collaboration.domain.model.ThreadDefinition thread = child.childThread();
        TaskModels.ContextSeedDraft draft = child.contextSeed();
        TaskModels.ContextSeed seed = new TaskModels.ContextSeed(draft.contextSeedId(), draft.parentThreadId(),
                draft.parentTurnId(), draft.parentRevision(), draft.inheritanceMode(), draft.taskBrief(),
                draft.initialContextSeed(), draft.references(), draft.permissionCeiling(), "a".repeat(64),
                draft.createdAt());
        ThreadSummary threadSummary = new ThreadSummary(thread.threadId(), thread.workspaceId(), thread.title(), "project", ConversationAcl.toConversation(thread.preferences()), ThreadSummary.Status.ACTIVE, false, null, true, task.projection().revision(), thread.createdAt(), thread.createdAt());
        TaskModels.Activity activity = new TaskModels.Activity(
                task.projection().latestActivitySequence(), child.activityId(), task.lineage().rootThreadId(),
                task.lineage().taskThreadId(), child.parentThreadId(), child.originTurnId(),
                TaskModels.ActivityKind.CREATED, child.activitySummary(), draft.createdAt());
        return new TaskModels.Detail(task, ConversationAcl.snapshot(threadSummary), seed, List.of(activity), List.of());
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

    /** 构造独立侧聊的最小投影，确保关闭测试不会把普通 Subagent 当成可清理目标。 */
    private static TaskModels.Summary sideTask(TaskModels.State state, long revision) {
        TaskModels.Lineage lineage = new TaskModels.Lineage("thr_side", "thr_parent", "thr_parent",
                "turn_parent", "Side", 1, TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT,
                "seed_side", NOW);
        boolean terminal = state == TaskModels.State.COMPLETED || state == TaskModels.State.FAILED
                || state == TaskModels.State.CANCELLED;
        return new TaskModels.Summary(lineage, new TaskModels.Projection("thr_side", "thr_parent", revision,
                state, 1, null, 1, 0, 0, 0, state.name(), NOW, terminal ? NOW : null, NOW));
    }

    /** 关闭测试使用独立 Thread identity，取消回执必须推动同一快照进入终态。 */
    private static ThreadSnapshot sideThreadWithTurn(String state, long revision) {
        ThreadSummary thread = new ThreadSummary("thr_side", "ws_test", "Side", "project", PREFERENCES, ThreadSummary.Status.ACTIVE, false, null, true, revision, NOW, NOW);
        ThreadSnapshot.Turn turn = new ThreadSnapshot.Turn("turn_side", state, NOW, NOW,
                state.equals("CANCELLED") ? NOW : null, null, null, 0, 0, null);
        return new ThreadSnapshot(thread, List.of(turn), List.of(), null, null, null);
    }

    /** 取消回执包含可供调用方等待的权威终态，且事件身份与 Thread/Turn 归属一致。 */
    private static TurnResult cancelledTurnResult(String threadId, String turnId, long sequence) {
        TurnEvent.Context context = new TurnEvent.Context(
                "evt_cancel_" + turnId, threadId, turnId, sequence, 0, NOW);
        TurnEvent.Terminal terminal = new TurnEvent.Terminal(context,
                io.github.kongweiguang.ja.agent.domain.turn.TurnState.CANCELLED,
                "cancelled", null, null, null, null, null);
        return new TurnResult(terminal.state(), terminal.summary(), terminal);
    }

    /** 续跑桥测试使用可控 revision/sequence，模拟同一 SQLite projection 的逐次 CAS 结果。 */
    private static TaskModels.Summary taskWithProjection(TaskModels.State state, long revision, long sequence) {
        TaskModels.Lineage lineage = new TaskModels.Lineage("thr_target", "thr_root", "thr_root",
                "turn_origin", "Target", 1, TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                "seed_target", NOW);
        boolean terminal = state == TaskModels.State.COMPLETED || state == TaskModels.State.FAILED
                || state == TaskModels.State.CANCELLED;
        return new TaskModels.Summary(lineage, new TaskModels.Projection("thr_target", "thr_root", revision,
                state, sequence, null, 1, 0, 0, 0, state.name(), state == TaskModels.State.QUEUED ? null : NOW,
                terminal ? NOW : null, NOW));
    }

    /** 构造包含完整 Turn metadata 的 Child Thread 快照，消息正文保持为空。 */
    private static ThreadSnapshot threadWithTurns(long revision, List<ThreadSnapshot.Turn> turns) {
        ThreadSummary thread = new ThreadSummary("thr_target", "ws_test", "Target", "project", PREFERENCES, ThreadSummary.Status.ACTIVE, false, null, true, revision, NOW, NOW);
        return new ThreadSnapshot(thread, List.copyOf(turns), List.of(), null, null, null);
    }

    /** Turn fixture 只携带取消循环读取的状态和 revision。 */
    private static ThreadSnapshot.Turn turn(String turnId, String state) {
        return new ThreadSnapshot.Turn(turnId, state, NOW, NOW, null, null, null, 0, 0, null);
    }

    /** 使用 Ja 工具目录身份创建能力 ceiling，测试不复制版本化 JSON 字段。 */
    private static JsonObject capabilityCeiling(ThreadPreferences preferences, Set<String> skillIds) {
        return new TaskAgentToolGateway(threadId -> Optional.of(SubagentPolicy.defaultPolicy()),
                new JaAdmissionParticipants())
                .create(preferences, "cfg_parent",
                new JaToolCatalogIdentity("a".repeat(64), "mcp_parent", skillIds));
    }

    /** 不参与调度的测试使用同一双接口 owner，仅保留 TaskCoordinator 构造不变量。 */
    private static Object passiveTurnOwner() {
        return Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class},
                (proxy, method, args) -> {
                    if ("findActiveTurn".equals(method.getName())) {
                        return Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 在 admission 后同步发布 QUEUED→RUNNING，直接覆盖 Task Activity 投影与调度隔离边界。
     * @author kongweiguang
     */
    private static Object eventPublishingTurnOwner(JaAdmissionParticipants admissions,
                                                   AtomicReference<AdmissionProbe> lastAdmission) {
        return eventPublishingTurnOwner(admissions, lastAdmission, () -> { });
    }

    /** 模拟真实 admission 先更新通知 revision，再传递同一提交的观察边界与公开事件，最后才返回调度结果。
     * @author kongweiguang
     */
    private static Object eventPublishingTurnOwner(JaAdmissionParticipants admissions,
                                                   AtomicReference<AdmissionProbe> lastAdmission,
                                                   Runnable afterStatePublication) {
        return Proxy.newProxyInstance(TaskCoordinatorTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, args) -> {
                    if (!"startChild".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    TurnStartRequest request = (TurnStartRequest) args[0];
                    io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink sink =
                            (io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink) args[1];
                    JaAdmissionReceipt receipt = runPreparedAdmission(admissions, request, lastAdmission);
                    AgentIdentity identity = JaAdmissionParticipants.identityFor(request.threadId(), request.turnId());
                    sink.onThreadRevision(identity, receipt.threadRevision());
                    TurnEvent.StateChanged state = new TurnEvent.StateChanged(
                            new TurnEvent.Context("evt_running", request.threadId(), request.turnId(),
                                    receipt.threadRevision(), receipt.turnMutationVersion() + 1, NOW),
                            io.github.kongweiguang.ja.agent.domain.turn.TurnState.QUEUED,
                            io.github.kongweiguang.ja.agent.domain.turn.TurnState.RUNNING);
                    sink.observeCommittedTurn(request.threadId(), request.turnId(), receipt.threadRevision(),
                            receipt.turnMutationVersion() + 1, 0, state, NOW);
                    sink.publish(state).toCompletableFuture().join();
                    afterStatePublication.run();
                    return new TurnUseCase.Accepted(request.threadId(), request.turnId(),
                            receipt.threadRevision(), true, CompletableFuture.completedFuture(null));
                });
    }

    /** 固定 Mailbox 行让活动投递测试精确复核同一 message ID、发送方和消费状态。 */
    private static TaskModels.MailboxMessage mailboxMessage(TaskModels.MailboxState state,
                                                             String boundTurnId, Instant consumedAt) {
        return new TaskModels.MailboxMessage(7, "msg_stored", "thr_root", "thr_child", "子任务",
                "thr_root", "turn_child", TaskModels.MailboxKind.MESSAGE,
                new UserContent(List.of(new TextContent("阶段结果"))), "child-root-key", state,
                boundTurnId, NOW, NOW, consumedAt);
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
