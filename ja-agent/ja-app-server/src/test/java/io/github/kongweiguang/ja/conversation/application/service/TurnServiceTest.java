// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.service;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAdmissionReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.test.ModelPortTestSupport;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOwnerProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.InitialContextSeedSource;
import io.github.kongweiguang.ja.conversation.application.gateway.PendingQueueInputSource;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentExecutionEvents;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.JaOperationReceiptRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.WorkspaceBindingResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationAttachmentPort;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationWorkspaceReferencePort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ja USER 准入边界回归集；执行循环语义由 ja-agent-core 与 SQLite 集成用例覆盖。 */
final class TurnServiceTest {
    @TempDir
    Path temporary;

    /** 缺失输入类型必须在读取 Thread、验证 Skill 或写入 Engine command 前拒绝。 */
    @Test
    void enqueueInputRejectsMissingKindBeforeReadingTurn() throws Exception {
        AgentRequestRuntimeResolver unusedResolver = (request, extensions, recovered) -> {
            throw new AssertionError("input validation must not dispatch an execution");
        };
        try (SQLiteAgentStore store = SQLiteAgentStore.open(temporary.resolve("input-validation.sqlite"));
             AgentEngine engine = new AgentEngineBuilder().store(store).runtimeResolver(unusedResolver).build()) {
            TurnService service = new TurnService(engine, store, unusedPort(ExecutionRecordCodec.class),
                    unusedPort(ExecutionControlCodec.class), unusedPort(JaOperationReceiptRepository.class),
                    unusedPort(ConversationProjectionRepository.class), new JaExecutionContexts(),
                    new JaAdmissionParticipants(), thread -> Optional.empty(), (thread, limit) -> List.of(),
                    unusedPort(AgentExecutionEvents.class), unusedPort(ConversationAttachmentPort.class),
                    unusedPort(ConversationWorkspaceReferencePort.class), unusedPort(WorkspaceBindingResolver.class),
                    unusedPort(TurnRuntimeResolver.class), AutomaticThreadTitleScheduler.disabled(),
                    java.time.Clock.systemUTC());

            assertThrows(NullPointerException.class,
                    () -> service.enqueueInput(null, null, null, null, null, null));
        }
    }

    /** Engine query 可在受理后前进；首段草稿与 Accepted 仍固定使用调度前收到的 admission receipt。 */
    @Test
    void admissionReceiptFencesFirstDraftAndAcceptedRevision() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_admission", "turn_admission", "user_local");
        AgentAdmissionReceipt receipt = new AgentAdmissionReceipt(11, 7, 1, 2);
        AgentExecutionStatus current = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 12, 8,
                Instant.EPOCH);
        AgentEngine engine = (AgentEngine) Proxy.newProxyInstance(AgentEngine.class.getClassLoader(),
                new Class<?>[]{AgentEngine.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "query" -> Optional.of(current);
                    case "close" -> null;
                    case "toString" -> "AdmissionReceiptEngine";
                    default -> throw new AssertionError("unexpected Engine call: " + method.getName());
                });
        AgentExecutionEvents events = (AgentExecutionEvents) Proxy.newProxyInstance(
                AgentExecutionEvents.class.getClassLoader(), new Class<?>[]{AgentExecutionEvents.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "attach" -> (AutoCloseable) () -> { };
                    case "toString" -> "AdmissionReceiptEvents";
                    default -> throw new AssertionError("unexpected event call: " + method.getName());
                });
        AtomicReference<Long> observedRevision = new AtomicReference<>();
        AtomicReference<Long> draftRevision = new AtomicReference<>();
        ConversationTurnEventSink sink = new ConversationTurnEventSink() {
            /** 首段草稿在同步 admission baseline 建立后才可观察。 */
            @Override
            public CompletableFuture<Void> publish(TurnEvent event) {
                draftRevision.set(observedRevision.get());
                return CompletableFuture.completedFuture(null);
            }

            /** 测试不产生 Thread 元数据。 */
            @Override
            public CompletableFuture<Void> publish(
                    io.github.kongweiguang.ja.conversation.domain.model.ThreadMetadataEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** 记录 Engine 调度前交付的真实 receipt 水位。 */
            @Override
            public void onThreadRevision(AgentIdentity committed, long revision) {
                assertEquals(identity, committed);
                observedRevision.set(revision);
            }
        };
        AgentExecutionHandle handle = new AgentExecutionHandle(identity, new CompletableFuture<>(),
                CompletableFuture.completedFuture(Optional.of(receipt)));
        Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> admission = beforeSchedule -> {
            beforeSchedule.accept(receipt);
            sink.publish(new TurnEvent.TextDelta("turn_admission", 1, "首段输出", "mreq_admission"))
                    .toCompletableFuture().join();
            return handle;
        };

        TurnService service = new TurnService(engine, unusedPort(AgentStore.class),
                unusedPort(ExecutionRecordCodec.class), unusedPort(ExecutionControlCodec.class),
                unusedPort(JaOperationReceiptRepository.class), unusedPort(ConversationProjectionRepository.class),
                new JaExecutionContexts(), new JaAdmissionParticipants(), thread -> Optional.empty(),
                (thread, limit) -> List.of(), events, unusedPort(ConversationAttachmentPort.class),
                unusedPort(ConversationWorkspaceReferencePort.class), unusedPort(WorkspaceBindingResolver.class),
                unusedPort(TurnRuntimeResolver.class), AutomaticThreadTitleScheduler.disabled(),
                java.time.Clock.systemUTC());
        Method admit = java.util.Arrays.stream(TurnService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("admit") && method.getParameterCount() == 6
                        && method.getParameterTypes()[3] == Function.class)
                .findFirst().orElseThrow();
        admit.setAccessible(true);
        TurnUseCase.Accepted accepted;
        try {
            accepted = (TurnUseCase.Accepted) admit.invoke(service, identity, sink, null, admission,
                    (Consumer<TurnResult>) ignored -> { },
                    (Runnable) () -> { });
        } catch (InvocationTargetException failure) {
            throw (failure.getCause() instanceof Exception exception) ? exception : failure;
        }

        assertEquals(11L, draftRevision.get());
        assertEquals(11L, accepted.threadRevision());
        assertTrue(accepted.queued());
        assertEquals(12L, current.threadRevision());
    }

    /** 同身份重入复用尚未完成的准入时，ACK 和后续草稿必须等待并使用原始 receipt 水位。 */
    @Test
    void pendingAdmissionHandleWaitsForOriginalReceipt() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_pending_receipt", "turn_pending_receipt",
                "user_local");
        AgentAdmissionReceipt receipt = new AgentAdmissionReceipt(21, 4, 1, 1);
        AgentExecutionStatus current = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 22, 5,
                Instant.EPOCH);
        AtomicInteger queryCount = new AtomicInteger();
        AgentEngine engine = statusEngine(identity, current, queryCount);
        AgentExecutionEvents events = (AgentExecutionEvents) Proxy.newProxyInstance(
                AgentExecutionEvents.class.getClassLoader(), new Class<?>[]{AgentExecutionEvents.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "attach" -> (AutoCloseable) () -> { };
                    case "toString" -> "PendingReceiptEvents";
                    default -> throw new AssertionError("unexpected event call: " + method.getName());
                });
        CompletableFuture<Optional<AgentAdmissionReceipt>> receiptStage = new CompletableFuture<>();
        AgentExecutionHandle handle = new AgentExecutionHandle(identity, new CompletableFuture<>(), receiptStage);
        CompletableFuture<Void> handleReturned = new CompletableFuture<>();
        Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> admission = beforeSchedule -> {
            handleReturned.complete(null);
            return handle;
        };
        AtomicReference<Long> observedRevision = new AtomicReference<>();
        AtomicReference<Long> draftRevision = new AtomicReference<>();
        AtomicInteger revisionObservations = new AtomicInteger();
        ConversationTurnEventSink sink = revisionSink(identity, observedRevision, draftRevision,
                revisionObservations);
        TurnService service = admissionTestService(engine, events);
        CompletableFuture<TurnUseCase.Accepted> acceptedStage = new CompletableFuture<>();
        Thread.ofVirtual().name("turn-service-pending-receipt-test").start(() -> {
            try {
                acceptedStage.complete(invokeAdmission(service, identity, sink, admission));
            } catch (Throwable failure) {
                acceptedStage.completeExceptionally(failure);
            }
        });

        try {
            handleReturned.get(2, TimeUnit.SECONDS);
            assertThrows(TimeoutException.class, () -> acceptedStage.get(100, TimeUnit.MILLISECONDS));
        } finally {
            receiptStage.complete(Optional.of(receipt));
        }
        TurnUseCase.Accepted accepted = acceptedStage.get(2, TimeUnit.SECONDS);
        sink.publish(new TurnEvent.TextDelta(identity.turnId(), 1, "后续草稿", "mreq_pending_receipt"))
                .toCompletableFuture().join();

        assertEquals(21L, accepted.threadRevision());
        assertTrue(accepted.queued());
        assertEquals(21L, observedRevision.get());
        assertEquals(21L, draftRevision.get());
        assertEquals(1, revisionObservations.get());
        assertEquals(1, queryCount.get());
        assertEquals(22L, current.threadRevision());
    }

    /** 普通恢复没有新准入回执，应以查询到的当前状态作为 ACK 水位且不伪造准入事件。 */
    @Test
    void emptyAdmissionReceiptUsesCurrentResumeWatermark() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_resume_receipt", "turn_resume_receipt",
                "user_local");
        AgentExecutionStatus current = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 31, 6,
                Instant.EPOCH);
        AtomicInteger queryCount = new AtomicInteger();
        AgentEngine engine = statusEngine(identity, current, queryCount);
        AgentExecutionEvents events = (AgentExecutionEvents) Proxy.newProxyInstance(
                AgentExecutionEvents.class.getClassLoader(), new Class<?>[]{AgentExecutionEvents.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "attach" -> (AutoCloseable) () -> { };
                    case "toString" -> "ResumeReceiptEvents";
                    default -> throw new AssertionError("unexpected event call: " + method.getName());
                });
        AgentExecutionHandle handle = new AgentExecutionHandle(identity, new CompletableFuture<>(),
                CompletableFuture.completedFuture(Optional.empty()));
        Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> admission = beforeSchedule -> handle;
        AtomicReference<Long> observedRevision = new AtomicReference<>();
        AtomicReference<Long> draftRevision = new AtomicReference<>();
        AtomicInteger revisionObservations = new AtomicInteger();
        ConversationTurnEventSink sink = revisionSink(identity, observedRevision, draftRevision,
                revisionObservations);

        TurnUseCase.Accepted accepted = invokeAdmission(admissionTestService(engine, events), identity, sink,
                admission);
        sink.publish(new TurnEvent.TextDelta(identity.turnId(), 1, "恢复草稿", "mreq_resume_receipt"))
                .toCompletableFuture().join();

        assertEquals(31L, accepted.threadRevision());
        assertTrue(accepted.queued());
        assertEquals(1, queryCount.get());
        assertEquals(0, revisionObservations.get());
        assertNull(observedRevision.get());
        assertNull(draftRevision.get());
    }

    /** receipt 阶段失败必须同步释放本次 subscription 和 owner，随后同一执行身份仍可重试。 */
    @Test
    void failedAdmissionReceiptCleansUpBeforeRetry() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_failed_receipt", "turn_failed_receipt",
                "user_local");
        AgentExecutionStatus current = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 41, 7,
                Instant.EPOCH);
        AgentEngine engine = statusEngine(identity, current, new AtomicInteger());
        AtomicInteger closedSubscriptions = new AtomicInteger();
        AgentExecutionEvents events = (AgentExecutionEvents) Proxy.newProxyInstance(
                AgentExecutionEvents.class.getClassLoader(), new Class<?>[]{AgentExecutionEvents.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "attach" -> (AutoCloseable) closedSubscriptions::incrementAndGet;
                    case "toString" -> "FailedReceiptEvents";
                    default -> throw new AssertionError("unexpected event call: " + method.getName());
                });
        TurnService service = admissionTestService(engine, events);
        AgentExecutionHandle failedHandle = new AgentExecutionHandle(identity, new CompletableFuture<>(),
                CompletableFuture.failedFuture(new IllegalStateException("injected receipt failure")));
        Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> failedAdmission =
                beforeSchedule -> failedHandle;
        ConversationTurnEventSink sink = revisionSink(identity, new AtomicReference<>(), new AtomicReference<>(),
                new AtomicInteger());

        assertThrows(CompletionException.class,
                () -> invokeAdmission(service, identity, sink, failedAdmission));
        assertEquals(1, closedSubscriptions.get());

        AgentExecutionHandle retriedHandle = new AgentExecutionHandle(identity, new CompletableFuture<>(),
                CompletableFuture.completedFuture(Optional.empty()));
        TurnUseCase.Accepted accepted = invokeAdmission(service, identity, sink, beforeSchedule -> retriedHandle);
        assertEquals(41L, accepted.threadRevision());
        assertEquals(1, closedSubscriptions.get());
    }

    /**
     * 首个 Child 尚无持久 Thread 投影；租约归属只能取精确身份登记的 Child admission facts。
     * @author kongweiguang
     */
    @Test
    void preparedChildAdmissionUsesWorkspaceWithoutThreadProjection() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_child_admission", "turn_child_admission",
                "user_local");
        JaAdmissionParticipants admissions = new JaAdmissionParticipants();
        AtomicReference<String> acquiredWorkspace = new AtomicReference<>();
        AtomicInteger acquiredLeases = new AtomicInteger();
        AtomicInteger releasedLeases = new AtomicInteger();
        AtomicInteger projectionReads = new AtomicInteger();
        AtomicInteger admissionAttempts = new AtomicInteger();
        WorkspaceAdmissionPort workspaceGate = workspaceId -> {
            acquiredWorkspace.set(workspaceId);
            acquiredLeases.incrementAndGet();
            return () -> releasedLeases.incrementAndGet();
        };
        AgentExecutionStatus status = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 18, 3,
                Instant.EPOCH);
        AtomicInteger queryCount = new AtomicInteger();
        AgentEngine engine = statusEngine(identity, status, queryCount);
        AtomicInteger closedSubscriptions = new AtomicInteger();
        AgentExecutionEvents events = workspaceAdmissionEvents(closedSubscriptions);
        ConversationProjectionRepository projections = missingThreadProjections(projectionReads);
        TurnService service = workspaceAdmissionTestService(engine, events, admissions, projections, workspaceGate);
        AgentAdmissionReceipt receipt = new AgentAdmissionReceipt(17, 2, 1, 1);
        AgentExecutionHandle handle = new AgentExecutionHandle(identity, new CompletableFuture<>(),
                CompletableFuture.completedFuture(Optional.of(receipt)));
        Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle> admission = beforeSchedule -> {
            admissionAttempts.incrementAndGet();
            beforeSchedule.accept(receipt);
            return handle;
        };
        ConversationTurnEventSink sink = revisionSink(identity, new AtomicReference<>(), new AtomicReference<>(),
                new AtomicInteger());

        try (JaAdmissionParticipants.Registration ignored = admissions.register(identity,
                preparedChildAdmission(identity, "ws_child_admission"))) {
            TurnUseCase.Accepted accepted = invokeSubmit(service, identity, sink, admission);
            assertEquals(identity.threadId(), accepted.threadId());
            assertEquals(identity.turnId(), accepted.turnId());
            assertEquals(17L, accepted.threadRevision());
            assertTrue(accepted.queued());
        }

        assertEquals("ws_child_admission", acquiredWorkspace.get());
        assertEquals(1, acquiredLeases.get());
        assertEquals(1, releasedLeases.get());
        assertEquals(0, projectionReads.get());
        assertEquals(1, admissionAttempts.get());
        assertEquals(1, queryCount.get());
        assertEquals(0, closedSubscriptions.get());
    }

    /**
     * 普通已持久化会话没有临时 Child admission 时仍须使用其投影 Workspace 作为既有回退来源。
     * @author kongweiguang
     */
    @Test
    void existingThreadProjectionRemainsWorkspaceAdmissionFallback() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_projected_turn", "turn_projected_turn",
                "user_local");
        AtomicReference<String> acquiredWorkspace = new AtomicReference<>();
        AtomicInteger acquiredLeases = new AtomicInteger();
        AtomicInteger releasedLeases = new AtomicInteger();
        AtomicInteger projectionReads = new AtomicInteger();
        WorkspaceAdmissionPort workspaceGate = workspaceId -> {
            acquiredWorkspace.set(workspaceId);
            acquiredLeases.incrementAndGet();
            return () -> releasedLeases.incrementAndGet();
        };
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.MANUAL);
        ThreadProjection thread = new ThreadProjection(identity.threadId(), "ws_projected_turn", preferences, 4,
                List.of());
        ConversationProjectionRepository projections = presentThreadProjections(thread, projectionReads);
        AgentExecutionStatus status = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 5, 2,
                Instant.EPOCH);
        TurnService service = workspaceAdmissionTestService(statusEngine(identity, status, new AtomicInteger()),
                workspaceAdmissionEvents(new AtomicInteger()), new JaAdmissionParticipants(), projections,
                workspaceGate);
        AgentAdmissionReceipt receipt = new AgentAdmissionReceipt(5, 2, 1, 1);
        AgentExecutionHandle handle = new AgentExecutionHandle(identity, new CompletableFuture<>(),
                CompletableFuture.completedFuture(Optional.of(receipt)));
        ConversationTurnEventSink sink = revisionSink(identity, new AtomicReference<>(), new AtomicReference<>(),
                new AtomicInteger());

        TurnUseCase.Accepted accepted = invokeSubmit(service, identity, sink, beforeSchedule -> {
            beforeSchedule.accept(receipt);
            return handle;
        });

        assertEquals(identity.threadId(), accepted.threadId());
        assertEquals(5L, accepted.threadRevision());
        assertEquals("ws_projected_turn", acquiredWorkspace.get());
        assertEquals(1, acquiredLeases.get());
        assertEquals(1, releasedLeases.get());
        assertEquals(1, projectionReads.get());
    }

    /**
     * 没有 Child admission 且没有 Thread 投影时，Workspace 身份未知，准入必须在取锁前拒绝。
     * @author kongweiguang
     */
    @Test
    void missingAdmissionAndThreadProjectionRejectBeforeWorkspaceLease() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_unregistered_child", "turn_unregistered_child",
                "user_local");
        AtomicInteger acquiredLeases = new AtomicInteger();
        AtomicInteger projectionReads = new AtomicInteger();
        AtomicInteger admissionAttempts = new AtomicInteger();
        WorkspaceAdmissionPort workspaceGate = workspaceId -> {
            acquiredLeases.incrementAndGet();
            return () -> { };
        };
        AgentExecutionStatus status = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 1, 1,
                Instant.EPOCH);
        AgentExecutionEvents events = workspaceAdmissionEvents(new AtomicInteger());
        TurnService service = workspaceAdmissionTestService(statusEngine(identity, status, new AtomicInteger()),
                events, new JaAdmissionParticipants(), missingThreadProjections(projectionReads), workspaceGate);
        ConversationTurnEventSink sink = revisionSink(identity, new AtomicReference<>(), new AtomicReference<>(),
                new AtomicInteger());
        AgentExecutionHandle handle = new AgentExecutionHandle(identity, new CompletableFuture<>(),
                CompletableFuture.completedFuture(Optional.empty()));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> invokeSubmit(service, identity, sink, beforeSchedule -> {
                    admissionAttempts.incrementAndGet();
                    return handle;
                }));

        assertEquals("Turn Thread projection is missing", failure.getMessage());
        assertEquals(1, projectionReads.get());
        assertEquals(0, acquiredLeases.get());
        assertEquals(0, admissionAttempts.get());
    }

    /**
     * Child 已登记并取得正确 Workspace 租约后，同步 Engine 失败仍须释放租约和事件订阅。
     * @author kongweiguang
     */
    @Test
    void synchronousChildAdmissionFailureReleasesWorkspaceLease() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_failed_child_admission",
                "turn_failed_child_admission", "user_local");
        JaAdmissionParticipants admissions = new JaAdmissionParticipants();
        AtomicReference<String> acquiredWorkspace = new AtomicReference<>();
        AtomicInteger acquiredLeases = new AtomicInteger();
        AtomicInteger releasedLeases = new AtomicInteger();
        WorkspaceAdmissionPort workspaceGate = workspaceId -> {
            acquiredWorkspace.set(workspaceId);
            acquiredLeases.incrementAndGet();
            return () -> releasedLeases.incrementAndGet();
        };
        AgentExecutionStatus status = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 1, 1,
                Instant.EPOCH);
        AtomicInteger closedSubscriptions = new AtomicInteger();
        TurnService service = workspaceAdmissionTestService(statusEngine(identity, status, new AtomicInteger()),
                workspaceAdmissionEvents(closedSubscriptions), admissions, missingThreadProjections(new AtomicInteger()),
                workspaceGate);
        ConversationTurnEventSink sink = revisionSink(identity, new AtomicReference<>(), new AtomicReference<>(),
                new AtomicInteger());

        try (JaAdmissionParticipants.Registration ignored = admissions.register(identity,
                preparedChildAdmission(identity, "ws_failed_child_admission"))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> invokeSubmit(service, identity, sink, beforeSchedule -> {
                        throw new IllegalStateException("injected child scheduling failure");
                    }));
            assertEquals("injected child scheduling failure", failure.getMessage());
        }

        assertEquals("ws_failed_child_admission", acquiredWorkspace.get());
        assertEquals(1, acquiredLeases.get());
        assertEquals(1, releasedLeases.get());
        assertEquals(1, closedSubscriptions.get());
    }

    /** 注入订阅关闭故障，验证已提交终态仍返回且 owner 完成信号不被清理路径卡住。 */
    @Test
    void terminalProjectionSurvivesSubscriptionCloseFailure() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_cleanup", "turn_cleanup", "user_local");
        TurnEvent.Context eventContext = new TurnEvent.Context("evt_cleanup", identity.threadId(),
                identity.turnId(), 1, 0, Instant.EPOCH);
        TurnResult projected = new TurnResult(TurnState.COMPLETED, "persisted reply",
                new TurnEvent.Terminal(eventContext, TurnState.COMPLETED, "persisted reply", null, null,
                        new TurnEvent.FinalMessage("item_cleanup", "persisted reply"), null,
                        TurnChangeSet.emptyComplete()));
        AtomicBoolean closeAttempted = new AtomicBoolean();
        AgentExecutionEvents eventPort = new AgentExecutionEvents() {
            /** 用关闭即失败的订阅模拟资源释放异常，检查清理故障不会覆盖已提交终态。 */
            @Override
            public AutoCloseable attach(AgentIdentity attached, ConversationTurnEventSink sink) {
                return () -> {
                    closeAttempted.set(true);
                    throw new AssertionError("subscription close failure");
                };
            }

            /** 返回稳定的 Ja 终态投影，确保本测试验证的是既有完成事实。 */
            @Override
            public TurnResult result(AgentIdentity completed,
                                     io.github.kongweiguang.ja.agent.application.api.TurnResult coreResult) {
                assertSame(identity, completed);
                assertEquals(TurnState.COMPLETED, coreResult.state());
                return projected;
            }

            /** 禁止测试意外退化为从存储重新猜测本次已完成结果。 */
            @Override
            public TurnResult terminalResult(AgentIdentity ignored) {
                throw new AssertionError("already completed outcome must use result projection");
            }
        };
        AgentRequestRuntimeResolver unusedResolver = (request, extensions, recovered) -> {
            throw new AssertionError("settlement test does not dispatch an execution");
        };

        try (SQLiteAgentStore store = SQLiteAgentStore.open(temporary.resolve("settlement.sqlite"));
             AgentEngine engine = new AgentEngineBuilder().store(store).runtimeResolver(unusedResolver).build()) {
            TurnService service = new TurnService(engine, store, unusedPort(ExecutionRecordCodec.class),
                    unusedPort(ExecutionControlCodec.class), unusedPort(JaOperationReceiptRepository.class),
                    unusedPort(ConversationProjectionRepository.class), new JaExecutionContexts(),
                    new JaAdmissionParticipants(), thread -> Optional.empty(), (thread, limit) -> List.of(),
                    eventPort, unusedPort(ConversationAttachmentPort.class),
                    unusedPort(ConversationWorkspaceReferencePort.class),
                    unusedPort(WorkspaceBindingResolver.class), unusedPort(TurnRuntimeResolver.class),
                    AutomaticThreadTitleScheduler.disabled(), java.time.Clock.systemUTC());
            CompletableFuture<TurnResult> completion = new CompletableFuture<>();
            CompletableFuture<Void> owner = new CompletableFuture<>();
            JaExecutionContexts.Binding binding = new JaExecutionContexts().bind(identity, null);
            AutoCloseable subscription = eventPort.attach(identity, null);
            Method settle = TurnService.class.getDeclaredMethod("settleOutcome", AgentIdentity.class,
                    CompletableFuture.class, CompletableFuture.class, JaExecutionContexts.Binding.class,
                    AutoCloseable.class, AgentExecutionOutcome.class, Throwable.class);
            settle.setAccessible(true);

            try {
                settle.invoke(service, identity, owner, completion, binding, subscription,
                        new AgentExecutionOutcome.Completed(new io.github.kongweiguang.ja.agent.application.api.TurnResult(
                                TurnState.COMPLETED,
                                "persisted reply", null, null)), null);
            } catch (InvocationTargetException failure) {
                throw (failure.getCause() instanceof Exception exception) ? exception : failure;
            }

            assertSame(projected, completion.getNow(null));
            assertTrue(closeAttempted.get());
            assertTrue(owner.isDone());
        }
    }

    /**
     * 预关闭失败仍须封闭准入并尝试 Engine 的每个关闭步骤；成功重试后只消费一次 hook。
     */
    @Test
    void closeAtPreservesHookFailureAndRetriesUntilHookSucceeds() throws Exception {
        AtomicInteger hookAttempts = new AtomicInteger();
        IllegalStateException hookFailure = new IllegalStateException("temporary sessions did not close");
        AgentRequestRuntimeResolver unusedResolver = (request, extensions, recovered) -> {
            throw new AssertionError("shutdown test does not dispatch an execution");
        };
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_close", "turn_close", "user_local");

        try (SQLiteAgentStore store = SQLiteAgentStore.open(temporary.resolve("turn-close.sqlite"));
             AgentEngine engine = new AgentEngineBuilder().store(store).runtimeResolver(unusedResolver).build()) {
            TurnService service = new TurnService(engine, store, unusedPort(ExecutionRecordCodec.class),
                    unusedPort(ExecutionControlCodec.class), unusedPort(JaOperationReceiptRepository.class),
                    unusedPort(ConversationProjectionRepository.class), new JaExecutionContexts(),
                    new JaAdmissionParticipants(), thread -> Optional.empty(), (thread, limit) -> List.of(),
                    unusedPort(AgentExecutionEvents.class), unusedPort(ConversationAttachmentPort.class),
                    unusedPort(ConversationWorkspaceReferencePort.class), unusedPort(WorkspaceBindingResolver.class),
                    unusedPort(TurnRuntimeResolver.class), AutomaticThreadTitleScheduler.disabled(),
                    java.time.Clock.systemUTC());
            service.bindPreShutdownHook(deadline -> {
                if (hookAttempts.incrementAndGet() == 1) {
                    throw hookFailure;
                }
            });
            Field acceptingField = TurnService.class.getDeclaredField("accepting");
            acceptingField.setAccessible(true);
            AtomicBoolean accepting = (AtomicBoolean) acceptingField.get(service);

            IllegalStateException observed = assertThrows(IllegalStateException.class,
                    () -> service.closeAt(System.nanoTime() + Duration.ofSeconds(1).toNanos()));

            assertSame(hookFailure, observed);
            assertFalse(accepting.get());
            assertThrows(IllegalStateException.class, () -> engine.watchEvents(identity, 0));

            service.closeAt(System.nanoTime() + Duration.ofSeconds(1).toNanos());
            service.close();

            assertEquals(2, hookAttempts.get());
        }
    }

    /** 冷启动观察只接入仍在 Core 审批等待或挂起的 MAIN/CHILD Turn，并交由原 watcher 生命周期回收。 @author kongweiguang */
    @Test
    void observeApprovalWaitRetainsOnlyAuthoritativeApprovalWaits() throws Exception {
        for (TurnState observedState : List.of(TurnState.WAITING_APPROVAL, TurnState.SUSPENDED)) {
            AgentIdentity identity = new AgentIdentity("ja.local", "thr_observe_" + observedState.name().toLowerCase(),
                    "turn_observe_" + observedState.name().toLowerCase(), "user_local");
            AgentExecutionStatus status = new AgentExecutionStatus(identity, observedState.name(), 2, 3,
                    Instant.EPOCH);
            AtomicInteger queryCount = new AtomicInteger();
            AtomicInteger attachCount = new AtomicInteger();
            AtomicInteger closeCount = new AtomicInteger();
            AgentExecutionEvents events = lifecycleEvents((proxy, method, arguments) -> switch (method.getName()) {
                case "attach" -> {
                    assertEquals(identity, arguments[0]);
                    assertTrue(arguments[1] instanceof ConversationTurnEventSink);
                    attachCount.incrementAndGet();
                    yield (AutoCloseable) closeCount::incrementAndGet;
                }
                case "toString" -> "ApprovalObservationEvents";
                default -> throw new AssertionError("unexpected event call: " + method.getName());
            });
            TurnService service = approvalObservationTestService(identity, status, queryCount, events);

            assertTrue(service.observeApprovalWait(identity.turnId(), ConversationTurnEventSink.noop()));
            assertEquals(2, queryCount.get(), "the persisted Engine state must fence both sides of attach");
            assertEquals(1, attachCount.get());
            assertTrue(suspendedSubscriptions(service).containsKey(identity));

            invokeRelease(service, identity);
            assertEquals(1, closeCount.get());
            assertFalse(suspendedSubscriptions(service).containsKey(identity));
            assertFalse(suspendedReleases(service).containsKey(identity));
        }
    }

    /** 不为已前进或缺失的状态建立 durable observer；Core query 仍在 watcher fence 外执行。 @author kongweiguang */
    @Test
    void observeApprovalWaitRejectsStaleCoreState() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_observe_stale", "turn_observe_stale",
                "user_local");
        AgentExecutionStatus status = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 2, 3,
                Instant.EPOCH);
        AtomicInteger queryCount = new AtomicInteger();
        AtomicInteger attachCount = new AtomicInteger();
        AgentExecutionEvents events = lifecycleEvents((proxy, method, arguments) -> switch (method.getName()) {
            case "attach" -> {
                attachCount.incrementAndGet();
                yield (AutoCloseable) () -> { };
            }
            case "toString" -> "StaleApprovalObservationEvents";
            default -> throw new AssertionError("unexpected event call: " + method.getName());
        });
        TurnService service = approvalObservationTestService(identity, status, queryCount, events);

        assertFalse(service.observeApprovalWait(identity.turnId(), ConversationTurnEventSink.noop()));
        assertEquals(1, queryCount.get());
        assertEquals(0, attachCount.get());
        assertFalse(suspendedReleases(service).containsKey(identity));
    }

    /** 冷观察不得覆盖本进程执行、恢复 claim、完成回执或已存在的 watcher/drain。 @author kongweiguang */
    @Test
    void observeApprovalWaitRejectsExistingLocalLifecycle() throws Exception {
        for (String blocker : List.of("owner", "completion", "resume", "watcher", "release")) {
            AgentIdentity identity = new AgentIdentity("ja.local", "thr_observe_" + blocker,
                    "turn_observe_" + blocker, "user_local");
            AgentExecutionStatus status = new AgentExecutionStatus(identity,
                    TurnState.WAITING_APPROVAL.name(), 2, 3, Instant.EPOCH);
            AtomicInteger queryCount = new AtomicInteger();
            AtomicInteger attachCount = new AtomicInteger();
            AgentExecutionEvents events = lifecycleEvents((proxy, method, arguments) -> switch (method.getName()) {
                case "attach" -> {
                    attachCount.incrementAndGet();
                    yield (AutoCloseable) () -> { };
                }
                case "toString" -> "ExistingApprovalLifecycleEvents";
                default -> throw new AssertionError("unexpected event call: " + method.getName());
            });
            TurnService service = approvalObservationTestService(identity, status, queryCount, events);
            switch (blocker) {
                case "owner" -> owners(service).put(identity, new CompletableFuture<>());
                case "completion" -> completions(service).put(identity, new CompletableFuture<>());
                case "resume" -> resumeClaims(service).add(identity);
                case "watcher" -> suspendedSubscriptions(service).put(identity, () -> { });
                case "release" -> suspendedReleases(service).put(identity, CompletableFuture.completedFuture(null));
                default -> throw new AssertionError("unknown local lifecycle blocker");
            }

            assertFalse(service.observeApprovalWait(identity.turnId(), ConversationTurnEventSink.noop()), blocker);
            assertEquals(0, queryCount.get(), blocker + " must be rejected before reading Engine state");
            assertEquals(0, attachCount.get(), blocker + " must not create another durable bridge subscription");
        }
    }

    /** Terminal finish racing an out-of-lock Core query fences the late cold observer before retention. @author kongweiguang */
    @Test
    void approvalObservationQueryCannotRetainAfterTerminalFinish() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_observe_terminal_race",
                "turn_observe_terminal_race", "user_local");
        AgentExecutionStatus status = new AgentExecutionStatus(identity, TurnState.WAITING_APPROVAL.name(), 2, 3,
                Instant.EPOCH);
        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch allowQuery = new CountDownLatch(1);
        AtomicInteger queryCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger attachCount = new AtomicInteger();
        AgentEngine engine = (AgentEngine) Proxy.newProxyInstance(AgentEngine.class.getClassLoader(),
                new Class<?>[]{AgentEngine.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "query" -> {
                        int ordinal = queryCount.incrementAndGet();
                        if (ordinal == 1) {
                            queryEntered.countDown();
                            if (!allowQuery.await(3, TimeUnit.SECONDS)) {
                                throw new AssertionError("test did not release observation query");
                            }
                        }
                        yield Optional.of(status);
                    }
                    case "toString" -> "TerminalApprovalObservationEngine";
                    default -> throw new AssertionError("unexpected Engine call: " + method.getName());
                });
        AgentExecutionEvents events = lifecycleEvents((proxy, method, arguments) -> switch (method.getName()) {
            case "attach" -> {
                attachCount.incrementAndGet();
                yield (AutoCloseable) closeCount::incrementAndGet;
            }
            case "closeForShutdown" -> {
                ((AutoCloseable) arguments[0]).close();
                yield null;
            }
            case "toString" -> "TerminalApprovalObservationEvents";
            default -> throw new AssertionError("unexpected event call: " + method.getName());
        });
        TurnService service = approvalObservationTestService(identity, status, engine, events);
        CompletableFuture<Boolean> observation = CompletableFuture.supplyAsync(
                () -> service.observeApprovalWait(identity.turnId(), ConversationTurnEventSink.noop()));
        CompletableFuture<TurnResult> completion = new CompletableFuture<>();
        CompletableFuture<Void> finishing = new CompletableFuture<>();

        try {
            assertTrue(queryEntered.await(2, TimeUnit.SECONDS));
            Thread.ofVirtual().start(() -> {
                try {
                    invokeFinish(service, identity, completion, terminalResult(identity));
                    finishing.complete(null);
                } catch (Exception failure) {
                    finishing.completeExceptionally(failure);
                }
            });
            long fenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terminalizingCompletions(service).contains(identity) && System.nanoTime() < fenceDeadline) {
                Thread.onSpinWait();
            }
            assertTrue(terminalizingCompletions(service).contains(identity), "finish must publish its stripe fence");
            assertFalse(completion.isDone(), "finish must wait for the reserved attach lifecycle to settle");
        } finally {
            allowQuery.countDown();
        }

        assertFalse(observation.get(2, TimeUnit.SECONDS));
        finishing.get(2, TimeUnit.SECONDS);
        assertEquals(1, attachCount.get());
        assertEquals(1, closeCount.get());
        assertTrue(completion.isDone());
        assertFalse(suspendedSubscriptions(service).containsKey(identity));
        assertFalse(suspendedReleases(service).containsKey(identity));
    }

    /** 同身份的并发冷观察共享一个预留屏障，不能重复 attach 或覆盖已登记的 watcher。 @author kongweiguang */
    @Test
    void concurrentApprovalObservationRejectsDuplicateWithoutLeakingReservation() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_observe_duplicate", "turn_observe_duplicate",
                "user_local");
        AgentExecutionStatus status = new AgentExecutionStatus(identity, TurnState.WAITING_APPROVAL.name(), 2, 3,
                Instant.EPOCH);
        CountDownLatch firstQueryEntered = new CountDownLatch(1);
        CountDownLatch allowFirstQuery = new CountDownLatch(1);
        AtomicInteger queryCount = new AtomicInteger();
        AtomicInteger attachCount = new AtomicInteger();
        AgentEngine engine = (AgentEngine) Proxy.newProxyInstance(AgentEngine.class.getClassLoader(),
                new Class<?>[]{AgentEngine.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "query" -> {
                        assertEquals(identity, arguments[0]);
                        int ordinal = queryCount.incrementAndGet();
                        if (ordinal == 1) {
                            firstQueryEntered.countDown();
                            if (!allowFirstQuery.await(3, TimeUnit.SECONDS)) {
                                throw new AssertionError("test did not release first Engine query");
                            }
                        }
                        yield Optional.of(status);
                    }
                    case "toString" -> "ConcurrentApprovalObservationEngine";
                    default -> throw new AssertionError("unexpected Engine call: " + method.getName());
                });
        AgentExecutionEvents events = lifecycleEvents((proxy, method, arguments) -> switch (method.getName()) {
            case "attach" -> {
                attachCount.incrementAndGet();
                yield (AutoCloseable) () -> { };
            }
            case "toString" -> "ConcurrentApprovalObservationEvents";
            default -> throw new AssertionError("unexpected event call: " + method.getName());
        });
        TurnService service = approvalObservationTestService(identity, status, engine, events);
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(
                () -> service.observeApprovalWait(identity.turnId(), ConversationTurnEventSink.noop()));

        try {
            assertTrue(firstQueryEntered.await(2, TimeUnit.SECONDS));
            assertFalse(service.observeApprovalWait(identity.turnId(), ConversationTurnEventSink.noop()));
            assertEquals(0, attachCount.get(), "the reservation owner is still waiting on its first state query");
        } finally {
            allowFirstQuery.countDown();
        }

        assertTrue(first.get(2, TimeUnit.SECONDS));
        assertEquals(2, queryCount.get());
        assertEquals(1, attachCount.get());
        assertEquals(1, suspendedSubscriptions(service).size());
        invokeRelease(service, identity);
        assertTrue(suspendedReleases(service).isEmpty());
    }

    /** closeAt 不等待锁外 attach；迟到订阅在准入关闭后即时 detach 且不残留生命周期屏障。 @author kongweiguang */
    @Test
    void shutdownDuringApprovalObservationDetachesLateSubscription() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_observe_shutdown", "turn_observe_shutdown",
                "user_local");
        AgentExecutionStatus status = new AgentExecutionStatus(identity, TurnState.WAITING_APPROVAL.name(), 2, 3,
                Instant.EPOCH);
        CountDownLatch attachEntered = new CountDownLatch(1);
        CountDownLatch allowAttach = new CountDownLatch(1);
        AtomicInteger detached = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        AgentEngine engine = (AgentEngine) Proxy.newProxyInstance(AgentEngine.class.getClassLoader(),
                new Class<?>[]{AgentEngine.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "query" -> {
                        queries.incrementAndGet();
                        yield Optional.of(status);
                    }
                    case "closeAsync" -> CompletableFuture.completedFuture(null);
                    case "awaitClosed" -> null;
                    case "toString" -> "ShutdownApprovalObservationEngine";
                    default -> throw new AssertionError("unexpected Engine call: " + method.getName());
                });
        AgentExecutionEvents events = lifecycleEvents((proxy, method, arguments) -> switch (method.getName()) {
            case "attach" -> {
                attachEntered.countDown();
                if (!allowAttach.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release blocked attach");
                }
                yield (AutoCloseable) () -> { };
            }
            case "closeForShutdown" -> {
                detached.incrementAndGet();
                yield null;
            }
            case "toString" -> "ShutdownApprovalObservationEvents";
            default -> throw new AssertionError("unexpected event call: " + method.getName());
        });
        TurnService service = approvalObservationTestService(identity, status, engine, events);
        CompletableFuture<Boolean> observation = CompletableFuture.supplyAsync(
                () -> service.observeApprovalWait(identity.turnId(), ConversationTurnEventSink.noop()));

        try {
            assertTrue(attachEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            service.closeAt(started - TimeUnit.MILLISECONDS.toNanos(1));
            assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(500),
                    "shutdown must not wait for an in-flight attach");
        } finally {
            allowAttach.countDown();
        }

        assertFalse(observation.get(2, TimeUnit.SECONDS));
        assertEquals(1, queries.get(), "the closed admission fence prevents a post-attach state query");
        assertEquals(1, detached.get());
        assertFalse(suspendedSubscriptions(service).containsKey(identity));
        assertFalse(suspendedReleases(service).containsKey(identity));
    }

    /** 并发恢复共享唯一 drain；实际 close 在身份 stripe 与 admission gate 外执行。 @author kongweiguang */
    @Test
    void concurrentSuspendedReleaseDrainsOnceOutsideAdmissionLocks() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_watcher_drain", "turn_watcher_drain",
                "user_local");
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CountDownLatch stripeAcquired = new CountDownLatch(1);
        CountDownLatch gateProbed = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        AtomicBoolean admissionWriteLockAcquired = new AtomicBoolean();
        AgentExecutionEvents events = lifecycleEvents((proxy, method, arguments) -> switch (method.getName()) {
            case "attach" -> (AutoCloseable) () -> { };
            case "toString" -> "WatcherDrainEvents";
            default -> throw new AssertionError("unexpected event call: " + method.getName());
        });
        TurnService service = watcherLifecycleTestService(events);
        Object stripe = identityStripe(service, identity);
        java.util.concurrent.locks.ReentrantReadWriteLock gate = admissionGate(service);
        suspendedSubscriptions(service).put(identity, () -> {
            closeCount.incrementAndGet();
            Thread.ofVirtual().start(() -> {
                synchronized (stripe) {
                    stripeAcquired.countDown();
                }
            });
            Thread.ofVirtual().start(() -> {
                if (gate.writeLock().tryLock()) {
                    admissionWriteLockAcquired.set(true);
                    gate.writeLock().unlock();
                }
                gateProbed.countDown();
            });
            closeEntered.countDown();
            if (!allowClose.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release watcher close");
            }
        });

        CompletableFuture<Void> first = releaseAsync(service, identity);
        CompletableFuture<Void> second = new CompletableFuture<>();
        CountDownLatch secondStarted = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            secondStarted.countDown();
            try {
                invokeRelease(service, identity);
                second.complete(null);
            } catch (Throwable failure) {
                second.completeExceptionally(failure);
            }
        });
        try {
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertTrue(stripeAcquired.await(2, TimeUnit.SECONDS), "watcher close retained its identity stripe");
            assertTrue(gateProbed.await(2, TimeUnit.SECONDS));
            assertTrue(admissionWriteLockAcquired.get(), "watcher close retained the admission read gate");
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            assertEquals(1, closeCount.get(), "concurrent release must not close the same watcher twice");
        } finally {
            allowClose.countDown();
        }
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
        assertFalse(suspendedSubscriptions(service).containsKey(identity));
        assertFalse(suspendedReleases(service).containsKey(identity));
    }

    /** closeAt 必须在普通 drain 卡住时直接 detach，并拒绝后续迟到的 suspended retain。 @author kongweiguang */
    @Test
    void shutdownDetachesInFlightDrainAndClosesLateRetention() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_watcher_shutdown", "turn_watcher_shutdown",
                "user_local");
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        AtomicInteger ordinaryCloseCount = new AtomicInteger();
        AtomicInteger shutdownDetachCount = new AtomicInteger();
        AgentExecutionEvents events = lifecycleEvents((proxy, method, arguments) -> switch (method.getName()) {
            case "attach" -> (AutoCloseable) () -> { };
            case "closeForShutdown" -> {
                shutdownDetachCount.incrementAndGet();
                yield null;
            }
            case "toString" -> "WatcherShutdownEvents";
            default -> throw new AssertionError("unexpected event call: " + method.getName());
        });
        TurnService service = watcherLifecycleTestService(events);
        suspendedSubscriptions(service).put(identity, () -> {
            ordinaryCloseCount.incrementAndGet();
            closeEntered.countDown();
            if (!allowClose.await(3, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release ordinary drain");
            }
        });
        CompletableFuture<Void> draining = releaseAsync(service, identity);

        try {
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            service.closeAt(started - TimeUnit.MILLISECONDS.toNanos(1));
            assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(500),
                    "shutdown waited for an in-flight sink drain past its deadline");
            assertEquals(1, ordinaryCloseCount.get());
            assertEquals(1, shutdownDetachCount.get());
            assertFalse(suspendedSubscriptions(service).containsKey(identity));

            invokeRetain(service, identity, () -> {
                throw new AssertionError("late retention must use the shutdown detach port");
            });
            assertEquals(2, shutdownDetachCount.get());
            assertFalse(suspendedSubscriptions(service).containsKey(identity));
        } finally {
            allowClose.countDown();
        }
        draining.get(2, TimeUnit.SECONDS);
    }

    /** Engine 状态 query 已读到旧 RUNNING 后终态完成，迟到 retain 必须被 stripe 内的完成 fence 拒绝。 @author kongweiguang */
    @Test
    void staleSuspendedQueryCannotRetainWatcherAfterTerminalFinish() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_watcher_terminal_race",
                "turn_watcher_terminal_race", "user_local");
        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch allowQuery = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        AgentExecutionStatus stale = new AgentExecutionStatus(identity, TurnState.RUNNING.name(), 4, 3,
                Instant.EPOCH);
        AgentEngine engine = (AgentEngine) Proxy.newProxyInstance(AgentEngine.class.getClassLoader(),
                new Class<?>[]{AgentEngine.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "query" -> {
                        assertEquals(identity, arguments[0]);
                        queryEntered.countDown();
                        if (!allowQuery.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not release stale Engine query");
                        }
                        yield Optional.of(stale);
                    }
                    case "toString" -> "StaleSuspendedQueryEngine";
                    default -> throw new AssertionError("unexpected Engine call: " + method.getName());
                });
        AgentExecutionEvents events = lifecycleEvents((proxy, method, arguments) -> switch (method.getName()) {
            case "attach" -> (AutoCloseable) () -> { };
            case "toString" -> "TerminalRaceEvents";
            default -> throw new AssertionError("unexpected event call: " + method.getName());
        });
        TurnService service = admissionTestService(engine, events);
        CompletableFuture<TurnResult> completion = new CompletableFuture<>();
        completions(service).put(identity, completion);
        TurnResult result = terminalResult(identity);
        CompletableFuture<Void> retain = CompletableFuture.runAsync(() -> {
            try {
                invokeRetain(service, identity, () -> closeCount.incrementAndGet());
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });

        try {
            assertTrue(queryEntered.await(2, TimeUnit.SECONDS));
            invokeFinish(service, identity, completion, result);
            assertSame(result, completion.get(2, TimeUnit.SECONDS),
                    "finish must not wait for an Engine query outside the stripe");
        } finally {
            allowQuery.countDown();
        }

        retain.get(2, TimeUnit.SECONDS);
        assertEquals(1, closeCount.get(), "late watcher must be closed after terminal completion");
        assertFalse(suspendedSubscriptions(service).containsKey(identity));
        assertTrue(completions(service).get(identity).isDone(), "completed future remains the terminal fence");
        assertFalse(terminalizingCompletions(service).contains(identity), "terminalizing marker must be reclaimed");
    }

    /** 构造只用于 watcher 锁与关停回归的 Engine 代理，不启动 Core 驱动。 @author kongweiguang */
    private static TurnService watcherLifecycleTestService(AgentExecutionEvents events) {
        AgentEngine engine = (AgentEngine) Proxy.newProxyInstance(AgentEngine.class.getClassLoader(),
                new Class<?>[]{AgentEngine.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "query" -> Optional.empty();
                    case "closeAsync" -> CompletableFuture.completedFuture(null);
                    case "close", "awaitClosed" -> null;
                    case "toString" -> "WatcherLifecycleEngine";
                    default -> throw new AssertionError("unexpected Engine call: " + method.getName());
                });
        return admissionTestService(engine, events);
    }

    /** 把持久 owner 投影与指定 Core 状态配成真实 observeApprovalWait 可读的最小服务夹具。 @author kongweiguang */
    private static TurnService approvalObservationTestService(AgentIdentity identity, AgentExecutionStatus status,
                                                               AtomicInteger queryCount,
                                                               AgentExecutionEvents events) {
        return approvalObservationTestService(identity, status, statusEngine(identity, status, queryCount), events);
    }

    /** 使用显式 Engine 代理保留并发和关停场景的可控 query，而投影仍从唯一 Turn ID 精确解析身份。 @author kongweiguang */
    private static TurnService approvalObservationTestService(AgentIdentity identity, AgentExecutionStatus status,
                                                               AgentEngine engine, AgentExecutionEvents events) {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.MANUAL);
        TurnOwnerProjection owner = new TurnOwnerProjection(identity.threadId(), "ws_observe_test", preferences,
                status.threadRevision(), new TurnProjection(identity.turnId(), TurnState.valueOf(status.state()),
                status.mutationVersion(), true, null));
        ConversationProjectionRepository projections = (ConversationProjectionRepository) Proxy.newProxyInstance(
                ConversationProjectionRepository.class.getClassLoader(),
                new Class<?>[]{ConversationProjectionRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("readTurn")) {
                        assertEquals(identity.turnId(), arguments[0]);
                        return Optional.of(owner);
                    }
                    if (method.getName().equals("toString")) {
                        return "approval-observation-projection";
                    }
                    throw new AssertionError("unexpected projection call: " + method.getName());
                });
        return new TurnService(engine, unusedPort(AgentStore.class), unusedPort(ExecutionRecordCodec.class),
                unusedPort(ExecutionControlCodec.class), unusedPort(JaOperationReceiptRepository.class),
                projections, new JaExecutionContexts(), new JaAdmissionParticipants(), thread -> Optional.empty(),
                (thread, limit) -> List.of(), events, unusedPort(ConversationAttachmentPort.class),
                unusedPort(ConversationWorkspaceReferencePort.class), unusedPort(WorkspaceBindingResolver.class),
                unusedPort(TurnRuntimeResolver.class), AutomaticThreadTitleScheduler.disabled(),
                java.time.Clock.systemUTC());
    }

    /** 构造一个合法终态事件供生命周期测试直接驱动 TurnService.finish。 @author kongweiguang */
    private static TurnResult terminalResult(AgentIdentity identity) {
        TurnEvent.Context eventContext = new TurnEvent.Context("evt_" + identity.turnId(), identity.threadId(),
                identity.turnId(), 1, 0, Instant.EPOCH);
        return new TurnResult(TurnState.COMPLETED, "done",
                new TurnEvent.Terminal(eventContext, TurnState.COMPLETED, "done", null, null,
                        new TurnEvent.FinalMessage("item_" + identity.turnId(), "done"), null,
                        TurnChangeSet.emptyComplete()));
    }

    /** 创建可显式处理 shutdown default-method 的事件端口代理。 @author kongweiguang */
    private static AgentExecutionEvents lifecycleEvents(InvocationHandler handler) {
        return (AgentExecutionEvents) Proxy.newProxyInstance(AgentExecutionEvents.class.getClassLoader(),
                new Class<?>[]{AgentExecutionEvents.class}, handler);
    }

    /** 在测试中调用真实 stripe 认领及其锁外 drain 路径。 @author kongweiguang */
    private static CompletableFuture<Void> releaseAsync(TurnService service, AgentIdentity identity) {
        return CompletableFuture.runAsync(() -> {
            try {
                invokeRelease(service, identity);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    /** 反射读取已归属于当前 TurnService 的 watcher map，避免测试构造第二套生命周期状态。 @author kongweiguang */
    @SuppressWarnings("unchecked")
    private static java.util.concurrent.ConcurrentMap<AgentIdentity, AutoCloseable> suspendedSubscriptions(
            TurnService service) throws Exception {
        Field field = TurnService.class.getDeclaredField("suspendedSubscriptions");
        field.setAccessible(true);
        return (java.util.concurrent.ConcurrentMap<AgentIdentity, AutoCloseable>) field.get(service);
    }

    /** 读取release barrier以验证完成后不遗留身份级等待状态。 @author kongweiguang */
    @SuppressWarnings("unchecked")
    private static java.util.concurrent.ConcurrentMap<AgentIdentity, CompletableFuture<Void>> suspendedReleases(
            TurnService service) throws Exception {
        Field field = TurnService.class.getDeclaredField("suspendedSubscriptionReleases");
        field.setAccessible(true);
        return (java.util.concurrent.ConcurrentMap<AgentIdentity, CompletableFuture<Void>>) field.get(service);
    }

    /** 读取现有 Turn completion 回执，确认终态 future 在 retain fence 中仍可见。 @author kongweiguang */
    @SuppressWarnings("unchecked")
    private static java.util.concurrent.ConcurrentMap<AgentIdentity, CompletableFuture<TurnResult>> completions(
            TurnService service) throws Exception {
        Field field = TurnService.class.getDeclaredField("completions");
        field.setAccessible(true);
        return (java.util.concurrent.ConcurrentMap<AgentIdentity, CompletableFuture<TurnResult>>) field.get(service);
    }

    /** 读取活动执行 owner map，验证冷启动 watcher 不覆盖本进程持有的运行句柄。 @author kongweiguang */
    @SuppressWarnings("unchecked")
    private static java.util.concurrent.ConcurrentMap<AgentIdentity, CompletableFuture<Void>> owners(
            TurnService service) throws Exception {
        Field field = TurnService.class.getDeclaredField("owners");
        field.setAccessible(true);
        return (java.util.concurrent.ConcurrentMap<AgentIdentity, CompletableFuture<Void>>) field.get(service);
    }

    /** 读取 resume claim 集合，验证恢复任务与冷启动观察不会同时争夺同一 Turn 生命周期。 @author kongweiguang */
    @SuppressWarnings("unchecked")
    private static Set<AgentIdentity> resumeClaims(TurnService service) throws Exception {
        Field field = TurnService.class.getDeclaredField("resumeClaims");
        field.setAccessible(true);
        return (Set<AgentIdentity>) field.get(service);
    }

    /** 检查 finish 的临时终态 fence 在 future 完成后回收，不把 identity 永久留在过渡集合。 @author kongweiguang */
    @SuppressWarnings("unchecked")
    private static Set<AgentIdentity> terminalizingCompletions(TurnService service) throws Exception {
        Field field = TurnService.class.getDeclaredField("terminalizingCompletions");
        field.setAccessible(true);
        return (Set<AgentIdentity>) field.get(service);
    }

    /** 读取锁对象仅用于证明阻塞的 close 回调未持有 admission stripe。 @author kongweiguang */
    private static Object identityStripe(TurnService service, AgentIdentity identity) throws Exception {
        Field field = TurnService.class.getDeclaredField("admissionStripes");
        field.setAccessible(true);
        Object[] stripes = (Object[]) field.get(service);
        Method stripeIndex = TurnService.class.getDeclaredMethod("stripe", AgentIdentity.class);
        stripeIndex.setAccessible(true);
        return stripes[(int) stripeIndex.invoke(service, identity)];
    }

    /** 返回 admission gate 用来探测 read lock 是否被 watcher close 持有。 @author kongweiguang */
    private static java.util.concurrent.locks.ReentrantReadWriteLock admissionGate(TurnService service)
            throws Exception {
        Field field = TurnService.class.getDeclaredField("admissionGate");
        field.setAccessible(true);
        return (java.util.concurrent.locks.ReentrantReadWriteLock) field.get(service);
    }

    /** 只对齐 TurnService 内部 release 入口，保留其 stripe/barrier 实现作为被测对象。 @author kongweiguang */
    private static void invokeRelease(TurnService service, AgentIdentity identity) throws Exception {
        Method method = TurnService.class.getDeclaredMethod("releaseSuspendedSubscription", AgentIdentity.class);
        method.setAccessible(true);
        try {
            method.invoke(service, identity);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(failure.getCause());
        }
    }

    /** 使用生产 finish 路径建立终态标记并完成与 admitted identity 关联的 future。 @author kongweiguang */
    private static void invokeFinish(TurnService service, AgentIdentity identity,
                                    CompletableFuture<TurnResult> completion, TurnResult result) throws Exception {
        Method method = TurnService.class.getDeclaredMethod("finish", AgentIdentity.class,
                CompletableFuture.class, TurnResult.class);
        method.setAccessible(true);
        try {
            method.invoke(service, identity, completion, result);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(failure.getCause());
        }
    }

    /** 检验 accepting fence 后的迟到结果走 shutdown detach，不重新占用 suspended map。 @author kongweiguang */
    private static void invokeRetain(TurnService service, AgentIdentity identity, AutoCloseable subscription)
            throws Exception {
        Method method = TurnService.class.getDeclaredMethod("retainSuspendedSubscription", AgentIdentity.class,
                AutoCloseable.class);
        method.setAccessible(true);
        try {
            method.invoke(service, identity, subscription);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(failure.getCause());
        }
    }

    /** 用 Engine 状态代理隔离 query 水位验证，并记录其调用次数。 */
    private static AgentEngine statusEngine(AgentIdentity identity, AgentExecutionStatus status,
                                            AtomicInteger queryCount) {
        return (AgentEngine) Proxy.newProxyInstance(AgentEngine.class.getClassLoader(),
                new Class<?>[]{AgentEngine.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "query" -> {
                        assertEquals(identity, arguments[0]);
                        queryCount.incrementAndGet();
                        yield Optional.of(status);
                    }
                    case "close" -> null;
                    case "toString" -> "AdmissionStatusEngine";
                    default -> throw new AssertionError("unexpected Engine call: " + method.getName());
                });
    }

    /** 使用不可变水位记录首个 draft，验证 ACK 建立的 revision 基线能被后续输出观察。 */
    private static ConversationTurnEventSink revisionSink(AgentIdentity identity,
                                                           AtomicReference<Long> observedRevision,
                                                           AtomicReference<Long> draftRevision,
                                                           AtomicInteger revisionObservations) {
        return new ConversationTurnEventSink() {
            /** 记录 draft 到达时已建立的水位。 */
            @Override
            public CompletableFuture<Void> publish(TurnEvent event) {
                draftRevision.set(observedRevision.get());
                return CompletableFuture.completedFuture(null);
            }

            /** 本夹具不产生 Thread 元数据事件。 */
            @Override
            public CompletableFuture<Void> publish(
                    io.github.kongweiguang.ja.conversation.domain.model.ThreadMetadataEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** 只记录对应执行身份的 revision 回填次数与水位。 */
            @Override
            public void onThreadRevision(AgentIdentity committed, long revision) {
                assertEquals(identity, committed);
                revisionObservations.incrementAndGet();
                observedRevision.set(revision);
            }
        };
    }

    /**
     * 构造只用于 admission 水位测试的服务实例，不启动 Core 执行或外部端口。
     * @author kongweiguang
     */
    private static TurnService admissionTestService(AgentEngine engine, AgentExecutionEvents events) {
        return new TurnService(engine, unusedPort(AgentStore.class), unusedPort(ExecutionRecordCodec.class),
                unusedPort(ExecutionControlCodec.class), unusedPort(JaOperationReceiptRepository.class),
                unusedPort(ConversationProjectionRepository.class), new JaExecutionContexts(),
                new JaAdmissionParticipants(), thread -> Optional.empty(), (thread, limit) -> List.of(), events,
                unusedPort(ConversationAttachmentPort.class), unusedPort(ConversationWorkspaceReferencePort.class),
                unusedPort(WorkspaceBindingResolver.class), unusedPort(TurnRuntimeResolver.class),
                AutomaticThreadTitleScheduler.disabled(), java.time.Clock.systemUTC());
    }

    /**
     * 注入可观测的 Workspace gate 与空投影，让 Child 首次准入只依赖临时冻结 facts。
     * @author kongweiguang
     */
    private static TurnService workspaceAdmissionTestService(AgentEngine engine, AgentExecutionEvents events,
                                                              JaAdmissionParticipants admissions,
                                                              ConversationProjectionRepository projections,
                                                              WorkspaceAdmissionPort workspaceAdmissions) {
        return new TurnService(engine, unusedPort(AgentStore.class), unusedPort(ExecutionRecordCodec.class),
                unusedPort(ExecutionControlCodec.class), unusedPort(JaOperationReceiptRepository.class),
                projections, new JaExecutionContexts(), admissions, thread -> Optional.empty(),
                (thread, limit) -> List.of(), events, unusedPort(ConversationAttachmentPort.class),
                unusedPort(ConversationWorkspaceReferencePort.class), unusedPort(WorkspaceBindingResolver.class),
                unusedPort(TurnRuntimeResolver.class), AutomaticThreadTitleScheduler.disabled(),
                java.time.Clock.systemUTC(), workspaceAdmissions);
    }

    /**
     * 首个 Child 先创建 Thread，因此本场景必须明确返回缺失投影而不能从父 Thread 偷取 Workspace。
     * @author kongweiguang
     */
    private static ConversationProjectionRepository missingThreadProjections(AtomicInteger reads) {
        return (ConversationProjectionRepository) Proxy.newProxyInstance(
                ConversationProjectionRepository.class.getClassLoader(),
                new Class<?>[]{ConversationProjectionRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("readThread")) {
                        reads.incrementAndGet();
                        return Optional.empty();
                    }
                    if (method.getName().equals("toString")) {
                        return "missing-child-thread-projections";
                    }
                    throw new AssertionError("unexpected projection call: " + method.getName());
                });
    }

    /**
     * 普通执行以持久 Thread 投影提供 Workspace；只公开该路径会读取的字段来源。
     * @author kongweiguang
     */
    private static ConversationProjectionRepository presentThreadProjections(ThreadProjection thread,
                                                                              AtomicInteger reads) {
        return (ConversationProjectionRepository) Proxy.newProxyInstance(
                ConversationProjectionRepository.class.getClassLoader(),
                new Class<?>[]{ConversationProjectionRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("readThread")) {
                        reads.incrementAndGet();
                        assertEquals(thread.threadId(), arguments[0]);
                        return Optional.of(thread);
                    }
                    if (method.getName().equals("toString")) {
                        return "present-thread-projections";
                    }
                    throw new AssertionError("unexpected projection call: " + method.getName());
                });
    }

    /**
     * 准备具备首 Child 因果身份和新 Thread Workspace 的冻结 admission，不触碰持久 Store。
     * @author kongweiguang
     */
    private static JaAdmissionParticipants.PreparedAdmission preparedChildAdmission(AgentIdentity identity,
                                                                                   String workspaceId) {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.MANUAL);
        AdmissionFacts facts = new AdmissionFacts(identity, workspaceId, TurnOrigin.CHILD_TASK, "task/create",
                Instant.EPOCH, null, "turn_parent", "turn_root", null, null, null, null, 0,
                Optional.of(new AdmissionFacts.ThreadInitialization(workspaceId, "首个 Child", preferences,
                        Instant.EPOCH)));
        JaAdmissionTransactionParticipant participant = new JaAdmissionTransactionParticipant() {
            /**
             * 该单测只验证提交前的 Workspace gate；事务阶段不得进入伪造的持久层。
             * @author kongweiguang
             */
            @Override
            public void beforeProjection(
                    io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext tx) {
                throw new AssertionError("prepared admission must not write a Store in this unit test");
            }

            /**
             * 该单测不提交 AgentStore 事务，后投影 hook 同样必须保持不可达。
             * @author kongweiguang
             */
            @Override
            public void afterProjection(
                    io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext tx) {
                throw new AssertionError("prepared admission must not write a Store in this unit test");
            }
        };
        return new JaAdmissionParticipants.PreparedAdmission(facts, participant, Optional.empty());
    }

    /**
     * 最小事件端口允许 admission 清理链验证订阅的同步释放，不启动真实事件流。
     * @author kongweiguang
     */
    private static AgentExecutionEvents workspaceAdmissionEvents(AtomicInteger closedSubscriptions) {
        return (AgentExecutionEvents) Proxy.newProxyInstance(AgentExecutionEvents.class.getClassLoader(),
                new Class<?>[]{AgentExecutionEvents.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "attach" -> (AutoCloseable) closedSubscriptions::incrementAndGet;
                    case "toString" -> "WorkspaceAdmissionEvents";
                    default -> throw new AssertionError("unexpected event call: " + method.getName());
                });
    }

    /**
     * 绕过 Engine 编排只触达 submit 的 gate，保留真实 admission resource scope 与 cleanup。
     * @author kongweiguang
     */
    private static TurnUseCase.Accepted invokeSubmit(TurnService service, AgentIdentity identity,
                                                     ConversationTurnEventSink sink,
                                                     Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle>
                                                             admission) throws Exception {
        Method submit = java.util.Arrays.stream(TurnService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("submit") && method.getParameterCount() == 7
                        && method.getParameterTypes()[6] == boolean.class)
                .findFirst().orElseThrow();
        submit.setAccessible(true);
        try {
            return (TurnUseCase.Accepted) submit.invoke(service, identity, sink, null, admission,
                    (Consumer<TurnResult>) ignored -> { }, (Runnable) () -> { }, false);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(failure.getCause());
        }
    }

    /** 直接经过私有 admission 边界调用测试函数，避免重复反射装配与真实 Engine 调度。 */
    private static TurnUseCase.Accepted invokeAdmission(TurnService service, AgentIdentity identity,
                                                        ConversationTurnEventSink sink,
                                                        Function<Consumer<AgentAdmissionReceipt>, AgentExecutionHandle>
                                                                admission) throws Exception {
        Method admit = java.util.Arrays.stream(TurnService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("admit") && method.getParameterCount() == 6
                        && method.getParameterTypes()[3] == Function.class)
                .findFirst().orElseThrow();
        admit.setAccessible(true);
        try {
            return (TurnUseCase.Accepted) admit.invoke(service, identity, sink, null, admission,
                    (Consumer<TurnResult>) ignored -> { }, (Runnable) () -> { });
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(failure.getCause());
        }
    }

    /** 为 settlement-only 场景构造不应被调用的端口，避免搭建第二套执行循环。 */
    private static <T> T unusedPort(Class<T> type) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, arguments) -> {
                    throw new AssertionError("unexpected call to " + type.getSimpleName() + "." + method.getName());
                });
        return type.cast(proxy);
    }

    /** 普通 USER admission 把有限 pending 快照交给 Core 与原消息同事务准入。 */
    @Test
    void userAdmissionReadsOneBoundedImmutableMailboxSnapshot() {
        AgentInputCommand.Enqueue message = message("mailbox_message");
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> threadId = new AtomicReference<>();
        AtomicReference<Integer> limit = new AtomicReference<>();
        PendingQueueInputSource source = (requestedThread, requestedLimit) -> {
            reads.incrementAndGet();
            threadId.set(requestedThread);
            limit.set(requestedLimit);
            return List.of(message);
        };

        List<AgentInputCommand.Enqueue> inputs = TurnService.pendingMailboxInputs(
                TurnOrigin.USER, "thr_mailbox", source);

        assertEquals(List.of(message), inputs);
        assertEquals(1, reads.get());
        assertEquals("thr_mailbox", threadId.get());
        assertEquals(AgentInputQueue.MAX_ENTRIES, limit.get());
        assertThrows(UnsupportedOperationException.class, () -> inputs.add(message));
    }

    /** 仅普通 USER 与显式 Child admission 消费邮箱；隐藏执行来源不能借邮箱改写输入。 */
    @Test
    void hiddenOriginsDoNotReadMailbox() {
        AtomicInteger reads = new AtomicInteger();
        PendingQueueInputSource source = (threadId, limit) -> {
            reads.incrementAndGet();
            throw new AssertionError("hidden origins must not query the mailbox");
        };

        for (TurnOrigin origin : TurnOrigin.values()) {
            if (origin == TurnOrigin.USER || origin == TurnOrigin.CHILD_TASK) {
                continue;
            }
            assertTrue(TurnService.pendingMailboxInputs(origin, "thr_mailbox", source).isEmpty(),
                    origin.name());
        }

        assertEquals(0, reads.get());
    }

    /** Plan 执行与用户显式续答在首个模型请求前读取同一有限 MESSAGE 快照。 */
    @Test
    void internalAdmissionReadsOneBoundedMessageSnapshot() {
        AgentInputCommand.Enqueue message = message("internal_mailbox_message");
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> threadId = new AtomicReference<>();
        AtomicReference<Integer> limit = new AtomicReference<>();
        PendingQueueInputSource source = (requestedThread, requestedLimit) -> {
            reads.incrementAndGet();
            threadId.set(requestedThread);
            limit.set(requestedLimit);
            return List.of(message);
        };

        for (TurnOrigin origin : List.of(TurnOrigin.PLAN_EXECUTION, TurnOrigin.USER_CONTINUATION)) {
            assertEquals(List.of(message), TurnService.internalMailboxInputs(continuationRequest(origin), source));
        }

        assertEquals(2, reads.get());
        assertEquals("thr_task", threadId.get());
        assertEquals(AgentInputQueue.MAX_ENTRIES, limit.get());
    }

    /** Core admission 失败时查询快照不会 claim 邮箱，下一次尝试仍能读回原 PENDING 项。 */
    @Test
    void failedInternalAdmissionLeavesMailboxPending() {
        AgentIdentity identity = JaTurnRequestMapper.identity("thr_task", "turn_new");
        AtomicBoolean pending = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        AgentInputCommand.Enqueue message = message("pending_after_failure");
        PendingQueueInputSource source = (threadId, limit) -> {
            reads.incrementAndGet();
            return pending.get() ? List.of(message) : List.of();
        };
        AtomicInteger admissionAttempts = new AtomicInteger();
        AgentStore store = failingAdmissionStore(identity, 3, admissionAttempts);
        AgentRequestRuntime requestRuntime = internalRuntime();
        AgentRequestRuntimeResolver resolver = (request, extensions, recovered) -> requestRuntime;
        ConversationProjectionRepository projections = (ConversationProjectionRepository) Proxy.newProxyInstance(
                ConversationProjectionRepository.class.getClassLoader(),
                new Class<?>[]{ConversationProjectionRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("readThread")) {
                        return Optional.of(threadProjection(3, List.of()));
                    }
                    if (method.getName().equals("toString")) {
                        return "failed-admission-projections";
                    }
                    throw new AssertionError("unexpected projection call: " + method.getName());
                });
        AgentExecutionEvents events = (AgentExecutionEvents) Proxy.newProxyInstance(
                AgentExecutionEvents.class.getClassLoader(), new Class<?>[]{AgentExecutionEvents.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("attach")) {
                        return (AutoCloseable) () -> { };
                    }
                    if (method.getName().equals("toString")) {
                        return "failed-admission-events";
                    }
                    throw new AssertionError("unexpected event call: " + method.getName());
                });

        try (AgentEngine engine = new AgentEngineBuilder().store(store).runtimeResolver(resolver).build()) {
            TurnService service = new TurnService(engine, store, unusedPort(ExecutionRecordCodec.class),
                    unusedPort(ExecutionControlCodec.class), unusedPort(JaOperationReceiptRepository.class),
                    projections, new JaExecutionContexts(), new JaAdmissionParticipants(), thread -> Optional.empty(),
                    source, events, unusedPort(ConversationAttachmentPort.class),
                    unusedPort(ConversationWorkspaceReferencePort.class), unusedPort(WorkspaceBindingResolver.class),
                    unusedPort(TurnRuntimeResolver.class), AutomaticThreadTitleScheduler.disabled(),
                    java.time.Clock.systemUTC());

            assertThrows(IllegalStateException.class, () -> service.startContinuation(
                    continuationRequest(TurnOrigin.PLAN_EXECUTION), "continue plan", unusedPort(
                            ConversationTurnEventSink.class)));
        }

        assertTrue(pending.get());
        assertEquals(1, reads.get());
        assertEquals(1, admissionAttempts.get());
        assertEquals(List.of(message), TurnService.internalMailboxInputs(
                continuationRequest(TurnOrigin.PLAN_EXECUTION), source));
        assertEquals(2, reads.get());
        assertTrue(pending.get());
    }

    /** 宿主 source 若混入 steering/follow-up，内部请求必须整体拒绝而不是改变 USER 控制语义。 */
    @Test
    void internalAdmissionRejectsNonMessageQueueKinds() {
        AgentInputCommand.Enqueue steering = new AgentInputCommand.Enqueue("steering_op", "steering_input",
                AgentInputCommand.Kind.STEERING, new UserContent(List.of(new TextContent("user steering"))), Map.of());

        assertThrows(IllegalStateException.class, () -> TurnService.internalMailboxInputs(
                continuationRequest(TurnOrigin.PLAN_EXECUTION), (threadId, limit) -> List.of(steering)));
    }

    /** 宿主不能通过违反 limit 的实现悄悄截断或超额准入队列内容。 */
    @Test
    void rejectsMailboxSourceThatExceedsCoreQueueCapacity() {
        List<AgentInputCommand.Enqueue> oversized = java.util.Collections.nCopies(
                AgentInputQueue.MAX_ENTRIES + 1, message("mailbox_message"));
        PendingQueueInputSource source = (threadId, limit) -> oversized;

        assertThrows(IllegalStateException.class, () -> TurnService.pendingMailboxInputs(
                TurnOrigin.USER, "thr_mailbox", source));
    }

    /** Reask 持久锚点使用 Ja item ID，而 Core history 保存不透明 msg ID，比较前必须一致投影。 */
    @Test
    void matchesReaskSourceAfterProjectingCoreMessageIdentity() {
        String coreMessageId = "msg_core-source";
        String projectedItemId = JaMessageIds.toItemId(coreMessageId);

        assertTrue(TurnService.matchesSourceMessageId(projectedItemId, coreMessageId));
        assertFalse(TurnService.matchesSourceMessageId("item_other", coreMessageId));
    }

    /** 多个本地句柄按持久 FIFO 选择，避免同会话排队时将有效 owner 检测成歧义。 */
    @Test
    void selectsEarliestRunnableLocalOwnerFromThreadOrder() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.PLACEHOLDER);
        ThreadProjection thread = new ThreadProjection("thr_mailbox", "ws_mailbox", preferences, 7,
                List.of(new TurnProjection("turn_first", TurnState.RUNNING, 2, true, null),
                        new TurnProjection("turn_queued", TurnState.QUEUED, 0, true, null),
                        new TurnProjection("turn_waiting", TurnState.SUSPENDED, 4, true, null)));

        Optional<TurnProjection> selected = TurnService.selectLocalOwner(thread,
                Set.of("turn_waiting", "turn_queued", "turn_first"));

        assertEquals("turn_first", selected.orElseThrow().turnId());
        assertTrue(TurnService.selectLocalOwner(thread, Set.of("turn_waiting")).isEmpty());
    }

    /** 仅首次 Plan 执行准入读取冻结来源，普通用户继续不继承 Task 背景。 */
    @Test
    void readsFrozenSeedOnlyForFirstPlanExecution() {
        InitialContextSeed seed = frozenSeed();
        AtomicInteger reads = new AtomicInteger();
        InitialContextSeedSource source = threadId -> {
            reads.incrementAndGet();
            assertEquals("thr_task", threadId);
            return Optional.of(seed);
        };
        ThreadProjection emptyTask = threadProjection(3, List.of());

        assertEquals(Optional.of(seed), TurnService.firstTaskContinuationSeed(
                continuationRequest(TurnOrigin.PLAN_EXECUTION), emptyTask, source));
        assertEquals(1, reads.get());
    }

    /** 已有 Turn、普通继续或 revision 不匹配时，不读取 Seed，也不重放冻结来源。 */
    @Test
    void skipsFrozenSeedForExistingTurnOtherOriginsAndStaleProjection() {
        AtomicInteger reads = new AtomicInteger();
        InitialContextSeedSource source = threadId -> {
            reads.incrementAndGet();
            throw new AssertionError("only a first Plan execution may read its frozen seed");
        };
        ThreadProjection emptyTask = threadProjection(3, List.of());
        ThreadProjection existingTask = threadProjection(3,
                List.of(new TurnProjection("turn_existing", TurnState.COMPLETED, 2, true, null)));

        assertTrue(TurnService.firstTaskContinuationSeed(continuationRequest(TurnOrigin.USER_CONTINUATION),
                emptyTask, source).isEmpty());
        assertTrue(TurnService.firstTaskContinuationSeed(continuationRequest(TurnOrigin.PLAN_EXECUTION),
                existingTask, source).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> TurnService.firstTaskContinuationSeed(
                continuationRequest(TurnOrigin.PLAN_EXECUTION), threadProjection(4, List.of()), source));
        assertEquals(0, reads.get());
    }

    /** 固定来源水位和可见用户正文构造不可变 seed，确保断言针对实际快照而非空占位。 */
    private static InitialContextSeed frozenSeed() {
        return new InitialContextSeed(new InitialContextSeed.SourceIdentity("ja.local", "thr_parent", "turn_parent"),
                1, List.of(ContextMessage.text("msg_parent", "turn_parent", 1, ContextMessage.Role.USER,
                        "创建 Task 时冻结的背景", 4)), Optional.empty());
    }

    /** 使用稳定偏好与会话 revision 构造 Seed 路由所需的一致性投影。 */
    private static ThreadProjection threadProjection(long revision, List<TurnProjection> turns) {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        return new ThreadProjection("thr_task", "ws_task", preferences, revision, turns);
    }

    /** 每种内部来源都使用其合法绑定字段，避免测试绕过请求的领域约束。 */
    private static InternalTurnStartRequest continuationRequest(TurnOrigin origin) {
        boolean plan = origin == TurnOrigin.PLAN_EXECUTION;
        return new InternalTurnStartRequest("thr_task", "turn_new", "ws_task", Path.of("C:\\task-workspace"),
                "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, 3, 0, Instant.parse("2026-09-27T00:00:00Z"), origin,
                plan ? "plan_revision" : null, plan ? "run_test" : null);
    }

    /** 每条测试消息都使用合法 MESSAGE kind，确保用例验证的是 Ja 准入策略而非 DTO 校验。 */
    private static AgentInputCommand.Enqueue message(String inputId) {
        return new AgentInputCommand.Enqueue("operation_" + inputId, inputId, AgentInputCommand.Kind.MESSAGE,
                new UserContent(List.of(new TextContent("来自任务的消息"))),
                Map.of("messageId", inputId, "senderThreadId", "thr_sender"));
    }

    /** 用于证明 admission 事务抛错时只读邮箱 source 没有执行绑定副作用。 */
    private static AgentStore failingAdmissionStore(AgentIdentity identity, long revision, AtomicInteger attempts) {
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(), new Class<?>[]{AgentStore.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "readReceipt" -> Optional.empty();
                    case "loadConversation" -> Optional.of(new AgentStore.ConversationSnapshot(
                            new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local"), revision,
                            0, 0, Instant.EPOCH, Instant.EPOCH, 0));
                    case "admit" -> {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("injected Core admission failure");
                    }
                    case "toString" -> "failing-internal-admission-store";
                    default -> throw new AssertionError("unexpected Store call: " + method.getName());
                });
    }

    /** 给真实 AgentEngine 准入路径提供无网络租约，Provider 调用不应发生前即由 Store 抛错。 */
    private static AgentRequestRuntime internalRuntime() {
        AgentPromptSession prompt = (AgentPromptSession) Proxy.newProxyInstance(
                AgentPromptSession.class.getClassLoader(), new Class<?>[]{AgentPromptSession.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "currentRevision" -> "a".repeat(64);
                    case "activeSkillReferences" -> List.of();
                    case "toString" -> "internal-admission-prompt";
                    default -> throw new AssertionError("unexpected prompt call: " + method.getName());
                });
        ModelPort model = ModelPortTestSupport.of((request, sink, cancellation) -> CompletableFuture.failedFuture(
                new AssertionError("failed admission must not dispatch a Provider request")));
        ManagedAttachmentReader attachments = request -> {
            throw new AssertionError("failed admission must not inspect attachments");
        };
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration("provider_test", "model_test",
                "cfg_test", ModelPort.Api.OPENAI_RESPONSES, "upstream_test", URI.create("https://example.invalid"),
                "secret_test", Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 16, null));
        return new AgentRequestRuntime(configuration, model, binding -> {
            throw new AssertionError("failed admission must not build a summary model");
        }, new ExtensionRegistry().freeze(), prompt, "b".repeat(64), AgentStoreTransactionParticipant.NONE,
                attachments, new ToolProjectionLimits(64, 16), Map.of(), () -> { });
    }
}

