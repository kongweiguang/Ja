// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.startup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Approval;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolPrepared;
import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestFingerprint;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswerSubmission;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationBoundary;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskAdmissionGateway;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxPort;
import io.github.kongweiguang.ja.collaboration.application.service.TaskCoordinator;
import io.github.kongweiguang.ja.conversation.application.api.ChildTurnScheduler;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.SubagentPolicyRepository;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证启动恢复只从 SQLite Core typed wait 读取审批，不依赖普通 Interaction 表或恢复 Driver。 */
final class ApprovalExpiryRecoveryListenerTest extends PersistenceTestSupport {
    private static final String THREAD_ID = "thr_approval_recovery";
    private static final String TURN_ID = "turn_approval_recovery";
    private static final String INTERACTION_ID = "interaction_approval_recovery";
    private static final String CALL_ID = "call_approval_recovery";
    private static final String WORKSPACE_ID = "ws_approval_recovery";
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
    private static final AgentIdentity IDENTITY = AppServerIdentity.local().execution(THREAD_ID, TURN_ID);

    /** 从真实 SQLite 固定水位恢复 Core approval wait；普通 Interaction 表为空时仍须重挂原 expiry。 */
    @Test
    void restoresFuturePendingApprovalFromPersistedCoreWaitWithoutResumingDriver() throws Exception {
        try (Harness harness = new Harness("approval-recovery-pending", current ->
                new CursorPage<>(List.of(discovery(ThreadDiscovery.Status.WAITING_APPROVAL)), null), true)) {
            assertEquals(1, harness.start());

            assertEquals(List.of(IDENTITY), harness.restoredIdentities);
            assertEquals(List.of("restoreApprovalExpiry"), harness.engineCalls);
            assertEquals("WAITING_APPROVAL", harness.store.loadExecution(harness.fixture.execution())
                    .orElseThrow().state());
            assertEquals(1, harness.persistedWaitCount());
            assertEquals(0, interactionRequestCount(harness.database, INTERACTION_ID),
                    "approval belongs to the Core typed wait and must not be copied into generic interactions");
            assertTrue(harness.taskRepository.findTask(THREAD_ID).isEmpty());
        }
    }

    /** 到期只接受启动 claim 的下一交互修订；重复事件不再读取或投影同一代际。 */
    @Test
    void requiresExactExpiredInteractionGenerationAndRetiresDuplicateClaim() throws Exception {
        try (Harness harness = new Harness("approval-recovery-expired-generation", current ->
                new CursorPage<>(List.of(discovery(ThreadDiscovery.Status.WAITING_APPROVAL)), null), true)) {
            harness.start();
            harness.controlReason.set("EXPIRED");
            int beforeStale = harness.storeReads.get();
            harness.listener.onEvent(harness.waitResolved("expired_stale"));
            assertTrue(harness.storeReads.get() > beforeStale,
                    "the event must verify the current durable Core wait before accepting expiry");
            assertTrue(harness.taskRepository.findTask(THREAD_ID).isEmpty());

            harness.persistWait(harness.currentWait.get().close(InteractionStatus.EXPIRED, NOW.plusSeconds(4)),
                    TurnState.SUSPENDED);
            harness.listener.onEvent(harness.waitResolved("expired_exact"));
            int readsAfterExactSettlement = harness.storeReads.get();
            harness.listener.onEvent(harness.waitResolved("expired_duplicate"));

            assertEquals(readsAfterExactSettlement, harness.storeReads.get(),
                    "the exact expiry retires the claim before duplicate event delivery");
            assertEquals(List.of("restoreApprovalExpiry"), harness.engineCalls);
            assertTrue(harness.taskRepository.findTask(THREAD_ID).isEmpty(),
                    "the listener must not invent a Task or activity for a missing persisted Task");
        }
    }

    /** 已回答的启动代际先退役；同一 Turn 后续交互的过期通知不能借旧 claim 触发 Task 恢复。 */
    @Test
    void retiresAnsweredClaimBeforeLaterInteractionGeneration() throws Exception {
        try (Harness harness = new Harness("approval-recovery-answered-generation", current ->
                new CursorPage<>(List.of(discovery(ThreadDiscovery.Status.WAITING_APPROVAL)), null), true)) {
            harness.start();
            harness.answerCurrentApproval();
            harness.controlReason.set("ANSWERED");
            harness.listener.onEvent(harness.waitResolved("answered"));
            int readsAfterRetirement = harness.storeReads.get();

            harness.controlReason.set("EXPIRED");
            harness.fixture.commitRecords(TurnState.QUEUED, cursor(), List.of(), List.of(), NOW.plusSeconds(4));
            harness.fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(), List.of(), NOW.plusMillis(4500));
            harness.persistWait(approval("interaction_approval_next", InteractionStatus.PENDING, 0,
                            "call_approval_recovery_next", NOW.plusSeconds(5)),
                    TurnState.WAITING_APPROVAL);
            harness.listener.onEvent(harness.waitResolved("next_generation_expired"));

            assertEquals(readsAfterRetirement, harness.storeReads.get(),
                    "events from a later interaction generation must not reuse a retired startup claim");
            assertEquals(List.of("restoreApprovalExpiry"), harness.engineCalls);
            assertTrue(harness.taskRepository.findTask(THREAD_ID).isEmpty());
        }
    }

    /** discovery cursor 必须前进；重复页游标使启动失败，不能静默漏掉后续等待审批。 */
    @Test
    void rejectsRepeatedDiscoveryCursor() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        try (Harness harness = new Harness("approval-recovery-cursor", query -> {
            pages.incrementAndGet();
            return new CursorPage<>(List.of(), "cursor_approval_repeated");
        }, false)) {
            assertThrows(IllegalStateException.class, harness::start);
            assertEquals(2, pages.get());
            assertTrue(harness.restoredIdentities.isEmpty());
        }
    }

    /** 构造真实 Ja approval typed wait；其类别来自 opaque kind 属性，不复制成普通 interaction。 */
    private static InteractionWait approval(String interactionId, InteractionStatus status, long revision) {
        return approval(interactionId, status, revision, CALL_ID, NOW.plusSeconds(2));
    }

    /** 每个后续审批代际配独立 Tool call 身份，保持 ja_tool_call 与 ja_approval 外键语义真实。 */
    private static InteractionWait approval(String interactionId, InteractionStatus status, long revision,
                                            String callId) {
        return approval(interactionId, status, revision, callId, NOW.plusSeconds(2));
    }

    /** 后续 generation 使用真实递增时间，避免测试 wait 时间戳早于显式 resume 提交。 */
    private static InteractionWait approval(String interactionId, InteractionStatus status, long revision,
                                            String callId, Instant createdAt) {
        InteractionQuestion question = new InteractionQuestion("question_approval", "是否允许执行工具？",
                InteractionQuestionType.SINGLE,
                List.of(new InteractionOption("option_approve", "允许", "继续本次工具", true),
                        new InteractionOption("option_deny", "拒绝", "停止本次工具", false)), true, false);
        return new InteractionWait(interactionId, callId, "operation_" + interactionId,
                JsonObjects.builder().putText("kind", "approval")
                        .putText("expiresAt", NOW.plusSeconds(300).toString()).build(),
                List.of(question), List.of(), status, revision, createdAt,
                status == InteractionStatus.PENDING ? createdAt : createdAt.plusSeconds(revision));
    }

    /** 启动恢复只接受当前最新 Turn 的安全发现摘要，不依赖会话正文或 UI 状态。 */
    private static ThreadDiscovery discovery(ThreadDiscovery.Status status) {
        return new ThreadDiscovery(THREAD_ID, "审批恢复测试", ThreadDiscovery.Kind.SUBAGENT,
                WORKSPACE_ID, status, TURN_ID);
    }

    /** 只允许构造期间用到的本地端口；意外调用暴露测试装配扩大了恢复职责。 */
    private static <T> T unusedPort(Class<T> type) {
        return proxy(type, (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, arguments);
            }
            throw new AssertionError("unexpected startup fixture call: " + type.getSimpleName() + "." + method.getName());
        });
    }

    /** 为真实 TaskCoordinator 提供共享 Turn/Child scheduler identity，但禁止测试启动任何 Turn。 */
    private static TaskCoordinator coordinator(MybatisTaskRepository tasks, ThreadUseCase threads,
                                               ObjectMapper mapper) {
        Object owner = Proxy.newProxyInstance(ApprovalExpiryRecoveryListenerTest.class.getClassLoader(),
                new Class<?>[]{TurnUseCase.class, ChildTurnScheduler.class}, (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method, arguments);
                    }
                    throw new AssertionError("startup recovery must not call Turn owner: " + method.getName());
                });
        return new TaskCoordinator(tasks, (TaskMailboxPort) tasks, threads,
                (TurnUseCase) owner, (ChildTurnScheduler) owner,
                unusedPort(WorkspaceUseCase.class), CLOCK,
                unusedPort(SubagentPolicyRepository.class), unusedPort(TaskAdmissionGateway.class),
                new JacksonJsonValueCodec(mapper), unusedPort(ConversationBoundary.class));
    }

    /** 普通 SQL 计数作为负断言，固定 Core approval 的 owner 不能是 generic interaction projection。 */
    private static long interactionRequestCount(TestDatabase database, String interactionId) throws Exception {
        try (SqlSession session = database.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(
                     "SELECT COUNT(*) FROM ja_interaction_request WHERE interaction_id=?")) {
            query.setString(1, interactionId);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("interaction count query returned no row");
                }
                return rows.getLong(1);
            }
        }
    }

    /** Java Proxy 的 Object 契约稳定返回，业务方法仍由每个测试端口显式处理。 */
    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "toString" -> proxy.getClass().getInterfaces()[0].getSimpleName() + " test proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> throw new AssertionError("unexpected Object method: " + method.getName());
        };
    }

    /** 动态代理仅包住 listener 的读取边界，真实 SQLite Store 仍负责等待记录与游标。 */
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    /** 已绑定 WAL checkpoint 的 PersistenceTestSupport 不重复初始化该 factory，只就绪启动屏障。 */
    private static StartupMaintenanceService initializedMaintenanceFixture() {
        try {
            java.lang.reflect.Constructor<StartupMaintenanceService> constructor =
                    StartupMaintenanceService.class.getDeclaredConstructor(boolean.class);
            if (!constructor.trySetAccessible()) {
                throw new IllegalStateException("startup maintenance fixture constructor is inaccessible");
            }
            return constructor.newInstance(true);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot create initialized startup maintenance fixture", failure);
        }
    }

    /** 每个 harness 以真实 SQLite admission/typed wait 建立 Core 执行，listener 不触碰模型或工具。 */
    private final class Harness implements AutoCloseable {
        private final TestDatabase database;
        private final AgentStore store;
        private final AgentStoreProjectionFixture fixture;
        private final MybatisTaskRepository taskRepository;
        private final java.util.concurrent.atomic.AtomicReference<InteractionWait> currentWait =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final AtomicReference<String> controlReason = new AtomicReference<>("EXPIRED");
        private final AtomicInteger storeReads = new AtomicInteger();
        private final AtomicLong eventSequence = new AtomicLong();
        private final List<AgentIdentity> restoredIdentities = new ArrayList<>();
        private final List<String> engineCalls = new ArrayList<>();
        private final ThreadUseCase threads;
        private final AgentEngine engine;
        private final TaskCoordinator taskCoordinator;
        private final ApprovalExpiryRecoveryListener listener;
        private final StartupMaintenanceService maintenance;
        private final ExecutionControlCodec controls;

        /** 独立 SQLite、真实 Core records 和内存 Engine 观察口隔离资源，不复用用户数据库或 Driver。 */
        private Harness(String name, Function<ThreadDiscovery.Query, CursorPage<ThreadDiscovery>> discovery,
                        boolean persistPendingWait) throws Exception {
            database = database(name);
            store = database.jaAgentStore();
            taskRepository = database.taskRepository();
            controls = new ControlPayloadCodec(database.mapper());
            fixture = admissionFixture(database, store);
            fixture.commitRecords(TurnState.RUNNING, cursor(), List.of(), List.of(), NOW.plusSeconds(1));
            if (persistPendingWait) {
                persistWait(approval(INTERACTION_ID, InteractionStatus.PENDING, 0), TurnState.WAITING_APPROVAL);
            }
            threads = proxy(ThreadUseCase.class, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(proxy, method, arguments);
                }
                if (method.getName().equals("discoverThreads")) {
                    return discovery.apply((ThreadDiscovery.Query) arguments[0]);
                }
                throw new AssertionError("unexpected ThreadUseCase call: " + method.getName());
            });
            ExecutionEventCodec events = proxy(ExecutionEventCodec.class, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(proxy, method, arguments);
                }
                if (method.getName().equals("decode")) {
                    String suffix = "event" + eventSequence.incrementAndGet();
                    return new ExecutionEvent.ControlRecorded(ExecutionEvent.ControlAction.WAIT_RESOLVED,
                            "control_" + suffix, "operation_" + suffix, controlReason.get());
                }
                throw new AssertionError("unexpected ExecutionEventCodec call: " + method.getName());
            });
            engine = proxy(AgentEngine.class, (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return objectMethod(proxy, method, arguments);
                }
                engineCalls.add(method.getName());
                if (method.getName().equals("restoreApprovalExpiry")) {
                    restoredIdentities.add((AgentIdentity) arguments[0]);
                    return null;
                }
                if (method.getName().equals("readEvents")) {
                    return new AgentEventPage(List.of(), (long) arguments[1], false);
                }
                throw new AssertionError("startup recovery must not dispatch Engine method: " + method.getName());
            });
            AgentStore observedStore = proxy(AgentStore.class, this::observeStoreRead);
            taskCoordinator = coordinator(taskRepository, threads, database.mapper());
            listener = new ApprovalExpiryRecoveryListener(observedStore, controls, events, taskRepository, CLOCK);
            maintenance = initializedMaintenanceFixture();
        }

        /** 将审批 wait 与独立 Core Approval 事实同事务写入，满足 Ja 投影核验的双重身份约束。 */
        private void persistWait(InteractionWait wait, TurnState state) {
            currentWait.set(wait);
            AgentStore.RecordWrite record = controls.encodeWait(
                    fixture.recordId(ExecutionControlCodec.WAIT_RECORD_TYPE,
                            "wait:" + wait.interactionId() + ":" + wait.interactionRevision()), wait,
                    wait.updatedAt());
            List<AgentStore.RecordWrite> records = new ArrayList<>();
            if (wait.status() == InteractionStatus.PENDING && wait.interactionRevision() == 0
                    && wait.attributes().get("kind") instanceof io.github.kongweiguang.ja.agent.domain.json.JsonText kind
                    && "approval".equals(kind.value())) {
                Instant expiresAt = Instant.parse(((io.github.kongweiguang.ja.agent.domain.json.JsonText)
                        wait.attributes().get("expiresAt")).value());
                AgentTool.Invocation invocation = new AgentTool.Invocation(wait.toolCallId(), "exec_command",
                        JsonObjects.builder().putText("cmd", "echo approval fixture").build(), 0);
                ToolPresentation presentation = ToolPresentationProjector.prepared(invocation, Path.of("."), List.of());
                records.add(fixture.record("agent.tool.prepared", "prepared:" + wait.toolCallId(),
                        new ToolPrepared("batch_" + wait.toolCallId(), wait.toolCallId(), "exec_command",
                                invocation.arguments(), 0, ToolSideEffect.EXTERNAL, null, true), wait.updatedAt()));
                records.add(fixture.extensionFact("agent.extension.fact",
                        "presentation:" + wait.toolCallId() + ":0", "ja.tool", "presentation", 1,
                        JsonObjects.builder().putText("callId", wait.toolCallId()).putNumber("revision", 0)
                                .putText("state", ToolState.PREPARED.name()).putText("presentationJson",
                                        new ToolPresentationCodec(database.mapper()).write(presentation)).build(),
                        wait.updatedAt()));
                records.add(fixture.record("agent.approval", "approval:" + wait.interactionId(),
                        new Approval(wait.interactionId(), wait.toolCallId(), null, expiresAt), wait.updatedAt()));
            }
            records.add(record);
            List<ExecutionEvent> events = wait.status() == InteractionStatus.PENDING
                    && wait.interactionRevision() == 0
                    ? List.of(new ExecutionEvent.WaitRequested(wait.interactionId(), "approval", record.recordId()))
                    : List.of();
            fixture.commitRecords(state, cursor(), records, events, wait.updatedAt());
        }

        /** 以真实 CONTROL settlement 写入 ANSWERED，避免普通执行提交伪造 Ja 审批决议或回执。 */
        private void answerCurrentApproval() {
            InteractionWait pending = currentWait.get();
            Instant answeredAt = NOW.plusSeconds(3);
            String clientOperationId = "op_0123456789abcdef0123456789abcdef";
            long expectedThreadRevision = fixture.conversationSnapshot().revision();
            String requestFingerprint = "d".repeat(64);
            InteractionAnswer answer = new InteractionAnswer("question_approval",
                    List.of("option_approve"), null, false);
            Map<String, String> hostMetadata = Map.of(
                    "ja.operationKey", clientOperationId,
                    "ja.requestFingerprint", requestFingerprint,
                    "ja.expectedThreadRevision", Long.toString(expectedThreadRevision),
                    "ja.approvalId", pending.interactionId(),
                    "ja.decision", "approve");
            AgentAnswer request = new AgentAnswer(pending.interactionId(), clientOperationId,
                    pending.interactionRevision(), List.of(answer), hostMetadata);
            AgentStore.OperationIdentity operation = AgentRequestFingerprint.prepareAnswer(IDENTITY, request);
            String answerId = fixture.recordId(ExecutionControlCodec.ANSWER_SUBMISSION_RECORD_TYPE,
                    operation.operationKey());
            InteractionAnswerSubmission submission = new InteractionAnswerSubmission(answerId,
                    pending.interactionId(), pending.interactionRevision(), operation.operationKey(), List.of(answer),
                    hostMetadata, answeredAt);
            InteractionWait answered = pending.answer(List.of(answer), answeredAt);
            AgentStore.RecordWrite answerRecord = controls.encodeAnswerSubmission(answerId, submission);
            AgentStore.RecordWrite answeredWait = controls.encodeWait(
                    fixture.recordId(ExecutionControlCodec.WAIT_RECORD_TYPE,
                            "answered:" + pending.interactionId()), answered, answeredAt);
            AgentStore.RecordWrite approvalDecision = fixture.record("agent.approval",
                    "decision:" + pending.interactionId(), new Approval(pending.interactionId(),
                            pending.toolCallId(), ApprovalDecision.APPROVE,
                            Instant.parse(((io.github.kongweiguang.ja.agent.domain.json.JsonText)
                                    pending.attributes().get("expiresAt")).value())), answeredAt);
            AgentStore.ConversationSnapshot conversation = store.loadConversation(fixture.execution().conversation())
                    .orElseThrow();
            AgentStore.ExecutionSnapshot execution = store.loadExecution(fixture.execution()).orElseThrow();
            AgentStore.WaitControl waitControl = new AgentStore.WaitControl(pending.interactionId(),
                    pending.interactionRevision(), Optional.of(answerId));
            store.controlCommit(new AgentStore.ControlCommit(fixture.execution(), conversation.revision(),
                    execution.state(), execution.mutationVersion(), AgentStore.ControlKind.WAIT_SETTLEMENT,
                    List.of(answerRecord, answeredWait, approvalDecision),
                    List.of(fixture.event("answer-state", new ExecutionEvent.StateChanged(
                                    TurnState.WAITING_APPROVAL, TurnState.SUSPENDED), answeredAt),
                            fixture.event("answer-resolved", new ExecutionEvent.ControlRecorded(
                                    ExecutionEvent.ControlAction.WAIT_RESOLVED, answerId,
                                    operation.operationKey(), null), answeredAt)),
                    operation, Optional.of(waitControl), answeredAt));
            currentWait.set(answered);
        }

        /** 为 listener 计数但仍委托真实 Store，断言 claim 退役后重复事件不再查询历史。 */
        private Object observeStoreRead(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, arguments);
            }
            if (method.getName().equals("loadExecution") || method.getName().equals("readRecords")) {
                storeReads.incrementAndGet();
            }
            try {
                return method.invoke(store, arguments);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        /** 运行与生产相同的有界 discovery 入口，且只在 SideChat maintenance barrier 后启动。 */
        private int start() {
            return listener.start(engine, threads, taskCoordinator, maintenance);
        }

        /** 事件 ID 保持本地精确身份；解码出的 settlement reason 由测试控制。 */
        private AgentEvent waitResolved(String suffix) {
            AgentStore.ConversationKey conversation = AppServerIdentity.local().conversation(THREAD_ID);
            AgentStore.ExecutionKey execution = AppServerIdentity.local().executionKey(IDENTITY);
            AgentStore.PersistedEvent persisted = new AgentStore.PersistedEvent(conversation, execution,
                    "event_" + suffix, "agent.execution.control", 1,
                    eventSequence.incrementAndGet(), 1, 1, 1, JsonObject.empty(), NOW.plusSeconds(5));
            return new AgentEvent(IDENTITY, persisted);
        }

        /** 断言审批 wait 确实从 Core 执行记录的固定水位解码，而非测试内存里的 domain 对象。 */
        private long persistedWaitCount() {
            AgentStore.ExecutionSnapshot snapshot = store.loadExecution(fixture.execution()).orElseThrow();
            AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                    fixture.execution(), 0, snapshot.lastRecordSequence(), 100));
            return page.records().stream().filter(record -> ExecutionControlCodec.WAIT_RECORD_TYPE
                    .equals(record.recordType())).filter(record -> INTERACTION_ID.equals(
                    controls.decodeWait(record).interactionId())).count();
        }

        /** 先释放 fixture lease 与 Coordinator，再关闭 Core Store 和临时 SQLite datasource。 */
        @Override
        public void close() throws Exception {
            listener.close();
            try {
                taskCoordinator.close();
            } finally {
                try {
                    fixture.close();
                    store.close();
                } finally {
                    database.close();
                }
            }
        }
    }

    /** 用 JaAgentStore 保证 admission participant 与 Core 投影的阶段顺序，后续 wait 仍写同一 SQLite 执行。 */
    private AgentStoreProjectionFixture admissionFixture(TestDatabase database, AgentStore store) throws Exception {
        Path root = Files.createDirectories(temp.resolve("workspace_approval_recovery"));
        database.history().register(new Workspace.Registration(WORKSPACE_ID, root.toAbsolutePath(),
                "审批恢复测试工作区", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, NOW.minusSeconds(10)));
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.FULL_ACCESS, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        database.history().createThread(new ThreadSummary.Creation(THREAD_ID, WORKSPACE_ID,
                "审批恢复测试", preferences, NOW.minusSeconds(5)));
        AgentExecutionRequest request = new AgentExecutionRequest(IDENTITY,
                AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(new UserContent(List.of(new TextContent("inspect")))), "item_" + TURN_ID,
                Optional.empty(), new AgentStore.OperationIdentity("operation_" + TURN_ID, "turn/start",
                "a".repeat(64), new JacksonJsonValueCodec(database.mapper()).decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), NOW, List.of(), Optional.empty(),
                Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(IDENTITY, WORKSPACE_ID, TurnOrigin.USER, "turn/start", NOW,
                null, null, null, null, null, null, null, 0, Optional.empty());
        return AgentStoreProjectionFixture.open(store, database.mapper(), request, facts, preferences, 0);
    }

    /** 使用干净 READY cursor 表达无模型派发等待，测试只关注 Core typed wait 的存储读取。 */
    private static TurnExecutionState.Ready cursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
    }
}
