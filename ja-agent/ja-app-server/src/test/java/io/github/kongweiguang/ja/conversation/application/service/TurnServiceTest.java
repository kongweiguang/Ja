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
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
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
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
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

    /** 构造只用于 admission 水位测试的服务实例，不启动 Core 执行或外部端口。 */
    private static TurnService admissionTestService(AgentEngine engine, AgentExecutionEvents events) {
        return new TurnService(engine, unusedPort(AgentStore.class), unusedPort(ExecutionRecordCodec.class),
                unusedPort(ExecutionControlCodec.class), unusedPort(JaOperationReceiptRepository.class),
                unusedPort(ConversationProjectionRepository.class), new JaExecutionContexts(),
                new JaAdmissionParticipants(), thread -> Optional.empty(), (thread, limit) -> List.of(), events,
                unusedPort(ConversationAttachmentPort.class), unusedPort(ConversationWorkspaceReferencePort.class),
                unusedPort(WorkspaceBindingResolver.class), unusedPort(TurnRuntimeResolver.class),
                AutomaticThreadTitleScheduler.disabled(), java.time.Clock.systemUTC());
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

