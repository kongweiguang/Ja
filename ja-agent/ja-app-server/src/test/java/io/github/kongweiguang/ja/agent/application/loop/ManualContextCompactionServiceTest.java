// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.concurrent.CancellationSource;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.application.loop.ManualContextCompactionService;
import io.github.kongweiguang.ja.conversation.application.service.JaExecutionContexts;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjection;
import io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证手动压缩只从 Ja 投影准入，模型循环与持久压缩事实唯一由 AgentEngine 管理。 */
final class ManualContextCompactionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);

    /** 真实 Engine 准入在共享模式下须看到请求环境，解析失败也不能留下执行身份的环境租约。 */
    @Test
    void bindsSharedNativeContextBeforeEngineAdmissionAndReleasesOnFailure() {
        NativeExecutionContext nativeContexts = NativeExecutionContext.shared();
        nativeContexts.enableSharedMode();
        String connection = nativeContexts.register(Map.of("PATH", "compaction-client-path"), "pwsh",
                java.util.Optional.empty());
        String rpcRequestId = "manual-compaction-context-test";
        nativeContexts.bindRpcRequest(rpcRequestId, connection);
        JaExecutionContexts contexts = new JaExecutionContexts();
        AtomicReference<AgentIdentity> resolved = new AtomicReference<>();
        AgentStore store = store(7);
        try (NativeExecutionContext.Scope scope = nativeContexts.enterRpcRequest(rpcRequestId);
             AgentEngine engine = new AgentEngineBuilder().store(store).runtimeResolver((request, extensions, recovered) -> {
                 NativeExecutionSnapshot snapshot = contexts.find(request.identity());
                 assertSame(nativeContexts.current().orElseThrow(), snapshot);
                 resolved.set(request.identity());
                 throw new IllegalArgumentException("intentional runtime admission rejection");
             }).build()) {
            ManualContextCompactionService service = service(projection(7, List.of(
                    new TurnProjection("turn_source", TurnState.COMPLETED, 2, true, "item_source"))),
                    store, engine, contexts);
            ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                    () -> service.compact(command(7), event -> {
                        throw new AssertionError("rejected admission cannot publish events");
                    }, CancellationToken.none()));
            assertEquals(ContextCompactionUseCase.Code.INVALID_STATE, failure.code());
            assertEquals("thr_test", resolved.get().threadId());
            assertThrows(IllegalStateException.class, () -> contexts.find(resolved.get()));
        } finally {
            nativeContexts.unbindRpcRequest(rpcRequestId);
            nativeContexts.release(connection);
            nativeContexts.disableSharedMode();
        }
    }

    /** 活动 Turn 必须在 Store/Engine 之前被拒绝，避免压缩抢占同会话执行权。 */
    @Test
    void rejectsBusyThreadBeforeExternalWork() {
        try (AgentEngine engine = dormantEngine()) {
            ManualContextCompactionService service = service(projection(7, List.of(
                    new TurnProjection("turn_test", TurnState.RUNNING, 1, true, null))),
                    unsupported(AgentStore.class), engine);
            ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                    () -> service.compact(command(7), event -> {
                        throw new AssertionError("busy Thread cannot publish compaction events");
                    }, CancellationToken.none()));
            assertEquals(ContextCompactionUseCase.Code.THREAD_BUSY, failure.code());
        }
    }

    /** Thread revision 冲突优先于执行状态检查，避免调用方把旧路径误认为当前路径。 */
    @Test
    void rejectsRevisionConflictBeforeExternalWork() {
        try (AgentEngine engine = dormantEngine()) {
            ManualContextCompactionService service = service(projection(8, List.of()),
                    unsupported(AgentStore.class), engine);
            ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                    () -> service.compact(command(7), event -> {
                        throw new AssertionError("stale revision cannot publish compaction events");
                    }, CancellationToken.none()));
            assertEquals(ContextCompactionUseCase.Code.CONFLICT, failure.code());
        }
    }

    /** 不存在的 Thread 返回稳定错误，不借配置、工作区或 Store 补猜归属。 */
    @Test
    void rejectsMissingThreadBeforeExternalWork() {
        try (AgentEngine engine = dormantEngine()) {
            ManualContextCompactionService service = service(null, unsupported(AgentStore.class), engine);
            ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                    () -> service.compact(command(0), event -> {
                        throw new AssertionError("missing Thread cannot publish compaction events");
                    }, CancellationToken.none()));
            assertEquals(ContextCompactionUseCase.Code.THREAD_NOT_FOUND, failure.code());
        }
    }

    /** 预先取消的入口不读取 Ja 投影或打开 Engine 执行。 */
    @Test
    void rejectsPreCancelledRequestBeforeExternalWork() {
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel("runtime_closed");
        try (AgentEngine engine = dormantEngine()) {
            ManualContextCompactionService service = service(projection(7, List.of()),
                    unsupported(AgentStore.class), engine);
            ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                    () -> service.compact(command(7), event -> {
                        throw new AssertionError("cancelled request cannot publish compaction events");
                    }, cancellation));
            assertEquals(ContextCompactionUseCase.Code.CANCELLED, failure.code());
        }
    }

    /** 取消回调无法注册时不提交 Engine admission，避免留下无法取消的孤儿执行。 */
    @Test
    void rejectsWhenCancellationRegistrationFailsBeforeEngineAdmission(@TempDir Path directory) {
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey("ja.local", "thr_test", "user_local");
        AtomicBoolean runtimeResolved = new AtomicBoolean();
        CancellationToken registrationFailure = new CancellationToken() {
            /** 注册失败用例保持未取消，以隔离失败来源。 */
            @Override
            public boolean isCancellationRequested() {
                return false;
            }

            /** 注册失败前没有已发布的取消事实。 */
            @Override
            public Optional<String> reason() {
                return Optional.empty();
            }

            /** 证明 callback 注册是 admission 的必要前置条件。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                throw new IllegalStateException("cancellation registration unavailable");
            }
        };

        try (SQLiteAgentStore store = SQLiteAgentStore.open(directory.resolve("compaction-registration.sqlite3"))) {
            store.initializeConversation(new AgentStore.ConversationInitialization(conversation, CLOCK.instant()));
            long revision = store.loadConversation(conversation).orElseThrow().revision();
            AgentRequestRuntimeResolver resolver = (request, extensions, recovered) -> {
                runtimeResolved.set(true);
                throw new AssertionError("Engine runtime cannot be resolved before cancellation registration");
            };
            try (AgentEngine engine = new AgentEngineBuilder().store(store).runtimeResolver(resolver).build()) {
                ManualContextCompactionService service = service(projection(revision, List.of(
                        new TurnProjection("turn_source", TurnState.COMPLETED, 2, true, "item_source"))),
                        store, engine, new JaExecutionContexts(), new JacksonExecutionEventCodec(new ObjectMapper()));
                ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                        () -> service.compact(command(revision), event -> {
                            throw new AssertionError("failed admission cannot publish events");
                        }, registrationFailure));

                assertEquals(ContextCompactionUseCase.Code.INVALID_STATE, failure.code());
                assertFalse(runtimeResolved.get());
                AgentStore.ConversationSnapshot after = store.loadConversation(conversation).orElseThrow();
                assertEquals(revision, after.revision());
                assertEquals(0, after.eventSequence());
                assertTrue(store.readEvents(AgentStore.EventQuery.withDefaultMaxBytes(conversation, 0, Long.MAX_VALUE, 10))
                        .events().isEmpty());
            }
        }
    }

    /** Ja 与 AgentStore revision 不一致时拒绝混用历史，且不调用运行时解析器。 */
    @Test
    void rejectsAgentHistoryRevisionMismatchBeforeRuntimeResolution() {
        try (AgentEngine engine = dormantEngine()) {
            ManualContextCompactionService service = service(projection(7, List.of(
                            new TurnProjection("turn_source", TurnState.COMPLETED, 2, true, "item_source"))),
                    store(6), engine);
            ContextCompactionUseCase.Failure failure = assertThrows(ContextCompactionUseCase.Failure.class,
                    () -> service.compact(command(7), event -> {
                        throw new AssertionError("revision mismatch cannot publish compaction events");
                    }, CancellationToken.none()));
            assertEquals(ContextCompactionUseCase.Code.CONFLICT, failure.code());
        }
    }

    /** 没有当前执行路径时结果明确为 unchanged，且不伪造 Engine 事件或写入 Checkpoint。 */
    @Test
    void returnsUnchangedWithoutStartingExecutionForEmptyPath() {
        try (AgentEngine engine = dormantEngine()) {
            ManualContextCompactionService service = service(projection(7, List.of()),
                    unsupported(AgentStore.class), engine);
            ContextCompactionUseCase.Result result = service.compact(command(7), event -> {
                throw new AssertionError("unchanged result cannot publish synthetic lifecycle events");
            }, CancellationToken.none());
            assertEquals(ContextCompactionUseCase.Outcome.UNCHANGED, result.outcome());
            assertNull(result.compactionId());
            assertNull(result.checkpointId());
            assertEquals(7, result.threadRevision());
            assertEquals(0, result.inputTokensBefore());
            assertEquals(0, result.inputTokensAfter());
        }
    }

    /** 三条真实 SQLite Turn 推进准入 revision 后，压缩事件与 receipt 必须保留 Engine 的实际来源版本。 */
    @Test
    void compactsCompletedHistoryFromRevisionAfterEngineAdmission(@TempDir Path directory) throws Exception {
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey("ja.local", "thr_test", "user_local");
        AtomicReference<AgentIdentity> compactionIdentity = new AtomicReference<>();
        List<ContextCompactionEvent> events = new ArrayList<>();

        try (SQLiteAgentStore store = SQLiteAgentStore.open(directory.resolve("manual-compaction-success.sqlite3"))) {
            store.initializeConversation(new AgentStore.ConversationInitialization(conversation, CLOCK.instant()));
            JaExecutionContexts contexts = new JaExecutionContexts();
            try (AgentEngine engine = liveEngine(store, completedHistoryModel(), compactionIdentity,
                    fixedSummaryModels())) {
                List<TurnProjection> turns = seedCompletedTurns(engine, 3);

                long expectedRevision = store.loadConversation(conversation).orElseThrow().revision();
                ManualContextCompactionService service = service(projection(expectedRevision, turns), store,
                        engine, contexts, new JacksonExecutionEventCodec(new ObjectMapper()));
                ContextCompactionUseCase.Result result = service.compact(command(expectedRevision), event -> {
                    events.add(event);
                    return CompletableFuture.completedFuture(null);
                }, CancellationToken.none());

                assertEquals(ContextCompactionUseCase.Outcome.COMPACTED, result.outcome());
                assertNotNull(result.checkpointId());
                assertEquals(2, events.size());
                ContextCompactionEvent.Context started = assertInstanceOf(ContextCompactionEvent.Started.class,
                        events.get(0)).context();
                ContextCompactionEvent.Compacted compacted = assertInstanceOf(ContextCompactionEvent.Compacted.class,
                        events.get(1));
                assertEquals(ContextCompactionEvent.Trigger.MANUAL, started.trigger());
                assertTrue(started.sourceRevision() > expectedRevision,
                        "Engine admission must advance beyond the command CAS before Started is recorded");
                assertEquals(started.sourceRevision(), compacted.context().sourceRevision(),
                        "Started and committed receipt projection must use one Engine source revision");
                assertEquals(result.threadRevision(), compacted.context().threadRevision());
                assertEquals(result.compactionId(), compacted.context().compactionId());
                assertEquals(result.checkpointId(), compacted.checkpointId());
                assertEquals(result.inputTokensBefore(), started.inputTokensBefore());
                assertEquals(result.inputTokensBefore(), compacted.context().inputTokensBefore());
                assertEquals(result.inputTokensAfter(), compacted.context().inputTokensAfter());
                assertTrue(result.inputTokensAfter() < result.inputTokensBefore());
                assertNull(contexts.find(compactionIdentity.get()), "the execution context lease must be released on return");
            }
        }
    }

    /**
     * 让真实摘要模型进入可控阻塞后再取消压缩，避免把跨会话的异步 Provider 阶段误当成线程池占用；
     * 只有 Engine 已提交 CANCELLED 终态且上下文绑定释放，服务才可返回并恢复中断标记。
     */
    @ParameterizedTest
    @EnumSource(CancellationMode.class)
    void waitsForEngineCancellationBeforeReturning(CancellationMode cancellationMode,
                                                   @TempDir Path directory) throws Exception {
        CountDownLatch summaryStarted = new CountDownLatch(1);
        CountDownLatch summaryCancellationObserved = new CountDownLatch(1);
        CountDownLatch releaseSummary = new CountDownLatch(1);
        CountDownLatch cancellationControlFinished = new CountDownLatch(1);
        CountDownLatch releaseCancellationCommit = new CountDownLatch(1);
        CancellationSource cancellationSource = new CancellationSource();
        AtomicReference<AgentStore.ExecutionKey> compactionExecution = new AtomicReference<>();
        AtomicReference<AgentIdentity> compactionIdentity = new AtomicReference<>();
        AtomicReference<AgentStore.ControlCommit> cancellationRequest = new AtomicReference<>();
        AtomicReference<AgentStore.CommitReceipt> cancellationReceipt = new AtomicReference<>();
        AtomicReference<String> cancellationCommitFailure = new AtomicReference<>();
        AtomicBoolean interruptedOnReturn = new AtomicBoolean();
        AgentStore.ConversationKey targetConversation = new AgentStore.ConversationKey("ja.local", "thr_test", "user_local");
        Thread cancellationThread = null;

        try (SQLiteAgentStore backingStore = SQLiteAgentStore.open(directory.resolve("manual-compaction-interrupt.sqlite3"))) {
            backingStore.initializeConversation(new AgentStore.ConversationInitialization(targetConversation, CLOCK.instant()));
            List<TurnProjection> sourceTurns;
            try (AgentEngine seedEngine = liveEngine(backingStore, completedHistoryModel(),
                    new AtomicReference<>())) {
                sourceTurns = seedCompletedTurns(seedEngine, 3);
            }
            AgentStore observedStore = observingCompactionCancellation(backingStore, targetConversation,
                    compactionExecution, cancellationControlFinished,
                    releaseCancellationCommit, cancellationRequest, cancellationReceipt, cancellationCommitFailure);
            ModelPort historyEstimator = completedHistoryModel();
            ModelPort model = new ModelPort() {
                /** 复用成功压缩的正文计量，让真实策略选择可淘汰历史并进入摘要阶段。 */
                @Override
                public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
                    return historyEstimator.estimateInputTokens(request, cancellation);
                }

                /** 手动压缩只调用摘要模型，不应进入常规对话 Provider。 */
                @Override
                public CompletionStage<ModelOutcome> start(
                        ModelRequest request, io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink sink,
                        CancellationToken cancellation) {
                    throw new AssertionError("manual compaction must not start a conversational model request");
                }
            };
            JaExecutionContexts contexts = new JaExecutionContexts();
            SummaryModel.Factory summaryModels = blockingSummaryModels(
                    summaryStarted, summaryCancellationObserved, releaseSummary);
            try (AgentEngine engine = liveEngine(observedStore, model, compactionIdentity, summaryModels)) {
                long revision = backingStore.loadConversation(targetConversation).orElseThrow().revision();
                ManualContextCompactionService service = service(projection(revision, sourceTurns),
                        observedStore, engine, contexts, new JacksonExecutionEventCodec(new ObjectMapper()));
                FutureTask<ContextCompactionUseCase.Code> result = new FutureTask<>(() -> {
                    try {
                        service.compact(command(revision), event -> CompletableFuture.completedFuture(null),
                                cancellationMode == CancellationMode.THREAD_INTERRUPT
                                        ? CancellationToken.none() : cancellationSource);
                        return null;
                    } catch (ContextCompactionUseCase.Failure failure) {
                        return failure.code();
                    } finally {
                        interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                    }
                });
                Thread compactionThread = new Thread(result, "manual-compaction-interrupt-test");
                compactionThread.start();
                try {
                    assertTrue(summaryStarted.await(5, TimeUnit.SECONDS),
                            () -> "manual compaction must enter its real summary operation before cancellation: "
                                    + cancellationDiagnostic(backingStore, targetConversation,
                                    compactionExecution.get(), cancellationRequest.get(), cancellationReceipt.get(),
                                    cancellationCommitFailure.get()));
                    assertTrue(hasPersistedCompactionStarted(
                                    backingStore, targetConversation, compactionExecution.get()),
                            "summary invocation must follow a durable ContextCompactionStarted event");
                    if (cancellationMode == CancellationMode.THREAD_INTERRUPT) {
                        compactionThread.interrupt();
                    } else {
                        cancellationThread = new Thread(() -> cancellationSource.cancel("test_cancelled"),
                                "manual-compaction-token-cancel-test");
                        cancellationThread.start();
                    }
                    assertTrue(cancellationControlFinished.await(5, TimeUnit.SECONDS),
                            "Engine cancellation control must finish before completion is released");
                    assertNotNull(cancellationReceipt.get(), () -> "Engine cancellation control was not committed: "
                            + cancellationDiagnostic(backingStore, targetConversation, compactionExecution.get(),
                            cancellationRequest.get(), null, cancellationCommitFailure.get()));
                    assertEquals(AgentStore.ControlKind.CANCEL_REQUEST, cancellationRequest.get().kind());
                    assertEquals("RUNNING", cancellationRequest.get().expectedState(),
                            "the summary barrier must keep cancellation within the running execution state");
                    assertFalse(result.isDone(),
                            "the service must not return while the Engine cancellation completion is held");
                    releaseCancellationCommit.countDown();
                    assertTrue(summaryCancellationObserved.await(5, TimeUnit.SECONDS),
                            "the active summary must observe the Engine cancellation token before release");
                    releaseSummary.countDown();
                    ContextCompactionUseCase.Code outcome = result.get(5, TimeUnit.SECONDS);
                    assertEquals(ContextCompactionUseCase.Code.CANCELLED, outcome,
                            () -> cancellationDiagnostic(backingStore, targetConversation, compactionExecution.get(),
                                    cancellationRequest.get(), cancellationReceipt.get(),
                                    cancellationCommitFailure.get()));
                    assertEquals(cancellationMode == CancellationMode.THREAD_INTERRUPT, interruptedOnReturn.get(),
                            "only thread interruption is restored after resource cleanup");
                    AgentStore.ExecutionKey execution = compactionExecution.get();
                    assertEquals("CANCELLED", backingStore.loadExecution(execution).orElseThrow().state());
                    assertNull(contexts.find(compactionIdentity.get()), "the execution context lease must be released on return");
                    assertFalse(backingStore.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                                    execution, 0, Long.MAX_VALUE, 1_000)).records().stream()
                                    .anyMatch(record -> record.recordType().contains("checkpoint")),
                            "cancellation before the checkpoint commit must not produce checkpoint records");
                } finally {
                    releaseCancellationCommit.countDown();
                    releaseSummary.countDown();
                    compactionThread.interrupt();
                    compactionThread.join(TimeUnit.SECONDS.toMillis(5));
                    if (cancellationThread != null) {
                        cancellationThread.join(TimeUnit.SECONDS.toMillis(5));
                    }
                }
            }
        }
    }

    /**
     * 客户端断流只停止通知，坏分页游标只停止观察；二者都必须等真实 Engine 完成并释放上下文 owner。
     */
    @ParameterizedTest
    @EnumSource(ObservationFailureMode.class)
    void observationFailureKeepsEngineOwnerUntilDurableTerminal(
            ObservationFailureMode failureMode, @TempDir Path directory) throws Exception {
        CountDownLatch summaryStarted = new CountDownLatch(1);
        CountDownLatch observationFailed = new CountDownLatch(1);
        CountDownLatch releaseSummary = new CountDownLatch(1);
        AtomicReference<AgentIdentity> compactionIdentity = new AtomicReference<>();
        AtomicReference<ContextCompactionUseCase.Result> successfulResult = new AtomicReference<>();
        AtomicReference<NativeExecutionSnapshot> requestSnapshot = new AtomicReference<>();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey("ja.local", "thr_test", "user_local");
        NativeExecutionContext nativeContexts = NativeExecutionContext.shared();
        nativeContexts.enableSharedMode();
        String connection = nativeContexts.register(Map.of("PATH", "manual-compaction-observation-path"), "pwsh",
                java.util.Optional.empty());
        String rpcRequestId = "manual-compaction-observation-test";
        nativeContexts.bindRpcRequest(rpcRequestId, connection);

        try (SQLiteAgentStore backingStore = SQLiteAgentStore.open(directory.resolve("manual-compaction-observation.sqlite3"))) {
            backingStore.initializeConversation(new AgentStore.ConversationInitialization(conversation, CLOCK.instant()));
            List<TurnProjection> sourceTurns;
            try (AgentEngine seedEngine = liveEngine(backingStore, completedHistoryModel(), new AtomicReference<>())) {
                sourceTurns = seedCompletedTurns(seedEngine, 3);
            }

            AgentStore serviceStore = failureMode == ObservationFailureMode.STALLED_CURSOR
                    ? stalledTailEvents(backingStore, observationFailed) : backingStore;
            ModelPort historyEstimator = completedHistoryModel();
            ModelPort model = new ModelPort() {
                /** 手动压缩只估算历史，不应进入普通对话 Provider。 */
                @Override
                public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
                    return historyEstimator.estimateInputTokens(request, cancellation);
                }

                /** 手动压缩通过摘要端口执行，意外发起聊天请求属于装配错误。 */
                @Override
                public CompletionStage<ModelOutcome> start(
                        ModelRequest request, io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink sink,
                        CancellationToken cancellation) {
                    throw new AssertionError("manual compaction must not start a conversational model request");
                }
            };
            JaExecutionContexts contexts = new JaExecutionContexts();
            try (AgentEngine engine = liveEngine(serviceStore, model, compactionIdentity,
                    blockingSummaryModels(summaryStarted, new CountDownLatch(1), releaseSummary))) {
                long revision = backingStore.loadConversation(conversation).orElseThrow().revision();
                ManualContextCompactionService service = service(projection(revision, sourceTurns), serviceStore,
                        engine, contexts, new JacksonExecutionEventCodec(new ObjectMapper()));
                ContextCompactionEventSink sink = failureMode == ObservationFailureMode.SINK_FAILURE
                        ? event -> {
                            observationFailed.countDown();
                            return CompletableFuture.failedFuture(new IllegalStateException("observer disconnected"));
                        }
                        : event -> CompletableFuture.completedFuture(null);
                FutureTask<ContextCompactionUseCase.Code> result = new FutureTask<>(() -> {
                    try (NativeExecutionContext.Scope scope = nativeContexts.enterRpcRequest(rpcRequestId)) {
                        requestSnapshot.set(nativeContexts.current().orElseThrow());
                        try {
                            successfulResult.set(service.compact(command(revision), sink, CancellationToken.none()));
                            return null;
                        } catch (ContextCompactionUseCase.Failure failure) {
                            return failure.code();
                        }
                    }
                });
                Thread compactionThread = new Thread(result, "manual-compaction-observation-test");
                compactionThread.start();
                try {
                    assertTrue(summaryStarted.await(5, TimeUnit.SECONDS), "the real summary operation must be held");
                    assertTrue(observationFailed.await(5, TimeUnit.SECONDS),
                            "the selected observation failure must occur while summary is blocked");
                    AgentIdentity identity = compactionIdentity.get();
                    AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                            new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local"), identity.turnId());
                    assertEquals("RUNNING", backingStore.loadExecution(execution).orElseThrow().state());
                    assertSame(requestSnapshot.get(), contexts.find(identity),
                            "the Engine owner must retain the request context during blocked summary");
                    assertFalse(result.isDone(), "observation failure must not release an active Engine execution");

                    releaseSummary.countDown();
                    ContextCompactionUseCase.Code failure = result.get(10, TimeUnit.SECONDS);
                    if (failureMode == ObservationFailureMode.SINK_FAILURE) {
                        assertNull(failure);
                        assertEquals(ContextCompactionUseCase.Outcome.COMPACTED, successfulResult.get().outcome());
                    } else {
                        assertEquals(ContextCompactionUseCase.Code.INVALID_STATE, failure);
                    }
                    assertEquals("COMPLETED", backingStore.loadExecution(execution).orElseThrow().state());
                    assertTrue(hasPersistedCompactionOutcome(backingStore, conversation, execution));
                    assertThrows(IllegalStateException.class, () -> contexts.find(identity),
                            "the execution context owner must release after terminal");
                } finally {
                    releaseSummary.countDown();
                    compactionThread.interrupt();
                    compactionThread.join(TimeUnit.SECONDS.toMillis(5));
                }
            }
        } finally {
            nativeContexts.unbindRpcRequest(rpcRequestId);
            nativeContexts.release(connection);
            nativeContexts.disableSharedMode();
        }
    }

    /** 仅允许历史水位与准入回执读取；运行时失败测试不得意外进入持久写入。 */
    private static AgentStore store(long revision) {
        AgentStore.ConversationKey key = new AgentStore.ConversationKey("ja.local", "thr_test", "user_local");
        AgentStore.ConversationSnapshot conversation = new AgentStore.ConversationSnapshot(
                key, revision, 0, 0, CLOCK.instant(), CLOCK.instant(), 0);
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, args) -> {
                    if ("loadConversation".equals(method.getName())) {
                        return Optional.of(conversation);
                    }
                    if ("readReceipt".equals(method.getName())) {
                        return Optional.empty();
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new AssertionError("unexpected AgentStore call: " + method.getName());
                });
    }

    /** 组合最小只读 Thread 投影；执行正文、模型能力和当前 history 均不由 Ja 投影承载。 */
    private static ManualContextCompactionService service(ThreadProjection projection, AgentStore store,
                                                          AgentEngine engine) {
        return service(projection, store, engine, new JaExecutionContexts());
    }

    /** 允许准入测试共用真实内存 owner，确保检查的是 Engine 解析时可见的身份绑定。 */
    private static ManualContextCompactionService service(ThreadProjection projection, AgentStore store,
                                                          AgentEngine engine, JaExecutionContexts contexts) {
        return service(projection, store, engine, contexts, unsupported(ExecutionEventCodec.class));
    }

    /** 允许真实 Engine 回归使用生产事件 codec，门禁用例仍拒绝任何不应发生的事件读取。 */
    private static ManualContextCompactionService service(ThreadProjection projection, AgentStore store,
                                                          AgentEngine engine, JaExecutionContexts contexts,
                                                          ExecutionEventCodec eventCodec) {
        ConversationProjectionRepository conversations = new ConversationProjectionRepository() {
            /** 测试只查询压缩目标 Thread。 */
            @Override
            public Optional<ThreadProjection> readThread(String threadId) {
                return "thr_test".equals(threadId) ? Optional.ofNullable(projection) : Optional.empty();
            }

            /** 手动压缩不按单个 Turn 定位。 */
            @Override
            public Optional<io.github.kongweiguang.ja.conversation.domain.model.TurnOwnerProjection> readTurn(String turnId) {
                return Optional.empty();
            }
        };
        return new ManualContextCompactionService(conversations, store, eventCodec, engine, contexts, CLOCK);
    }

    /**
     * 以首次事件分页确认服务已取得 handle，再记录真实控制 CAS 与回执；提交后短暂阻塞通知，
     * 让断言能证明服务不会在 Engine 持久取消终态前提前返回。
     */
    private static AgentStore observingCompactionCancellation(
            AgentStore delegate, AgentStore.ConversationKey target,
            AtomicReference<AgentStore.ExecutionKey> execution,
            CountDownLatch cancellationControlFinished, CountDownLatch releaseCancellationCommit,
            AtomicReference<AgentStore.ControlCommit> cancellationRequest,
            AtomicReference<AgentStore.CommitReceipt> cancellationReceipt,
            AtomicReference<String> cancellationCommitFailure) {
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, args) -> {
                    AgentStore.ControlCommit control = "controlCommit".equals(method.getName())
                            && args != null && args.length > 0
                            && args[0] instanceof AgentStore.ControlCommit request
                            && request.execution().equals(execution.get()) ? request : null;
                    if (control != null) {
                        cancellationRequest.set(control);
                    }
                    try {
                        Object result = method.invoke(delegate, args);
                        if ("admit".equals(method.getName()) && args != null && args.length > 0
                                && args[0] instanceof AgentStore.Admission request
                                && request.execution().conversation().equals(target)) {
                            execution.set(request.execution());
                        }
                        if (control != null) {
                            cancellationReceipt.set((AgentStore.CommitReceipt) result);
                            cancellationControlFinished.countDown();
                            if (!releaseCancellationCommit.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("test did not release the persisted cancellation barrier");
                            }
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        if (control != null) {
                            cancellationCommitFailure.set(failure.getCause().getClass().getSimpleName());
                            cancellationControlFinished.countDown();
                        }
                        throw failure.getCause();
                    }
                });
    }

    /**
     * 只在取消结果不符时读取本次 execution 的持久事件，输出控制 CAS 水位、回执和压缩失败 reason，
     * 避免测试因公开错误码映射而丢失 Engine 侧的权威失败事实；准入未产出身份时直接说明而不查询 Store。
     */
    private static String cancellationDiagnostic(
            AgentStore store, AgentStore.ConversationKey conversation, AgentStore.ExecutionKey execution,
            AgentStore.ControlCommit request, AgentStore.CommitReceipt receipt, String commitFailure) {
        String requestDetails = request == null ? "missing" : request.kind() + "/state=" + request.expectedState()
                + "/threadRevision=" + request.expectedThreadRevision()
                + "/mutationVersion=" + request.expectedMutationVersion();
        String receiptDetails = receipt == null ? "missing" : "revision=" + receipt.threadRevision()
                + ", mutationVersion=" + receipt.mutationVersion() + ", eventSequence="
                + receipt.firstEventSequence() + ".." + receipt.lastEventSequence()
                + ", newlyCommitted=" + receipt.newlyCommitted();
        if (execution == null) {
            return "execution=not-admitted, controlRequest=" + requestDetails
                    + ", controlReceipt=" + receiptDetails + ", controlFailure=" + commitFailure;
        }
        AgentStore.ConversationSnapshot snapshot = store.loadConversation(conversation).orElseThrow();
        AgentStore.EventPage page = store.readEvents(AgentStore.EventQuery.withDefaultMaxBytes(
                conversation, 0, snapshot.eventSequence(), 10_000));
        List<String> compactionFailures = new ArrayList<>();
        List<String> eventTypes = new ArrayList<>();
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        for (AgentStore.PersistedEvent persisted : page.events()) {
            if (!persisted.execution().equals(execution)) {
                continue;
            }
            eventTypes.add(persisted.eventType());
            ExecutionEvent decoded = codec.decode(persisted);
            if (decoded instanceof io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent.ContextCompactionFailed failed) {
                compactionFailures.add("reason=" + failed.reasonCode() + ", sourceRevision="
                        + failed.sourceRevision() + ", inputTokensBefore=" + failed.inputTokensBefore());
            }
        }
        String executionState = store.loadExecution(execution).map(AgentStore.ExecutionSnapshot::state)
                .orElse("missing");
        return "controlRequest=" + requestDetails + ", controlReceipt=" + receiptDetails
                + ", controlFailure=" + commitFailure + ", executionState=" + executionState
                + ", eventTypes=" + eventTypes + ", compactionFailures=" + compactionFailures;
    }

    /** 核验屏障确实位于持久 Started 与 Checkpoint 写入之间，避免取消测试落到 UNCHANGED 快路径。 */
    private static boolean hasPersistedCompactionStarted(
            AgentStore store, AgentStore.ConversationKey conversation, AgentStore.ExecutionKey execution) {
        long throughSequence = store.loadConversation(conversation).orElseThrow().eventSequence();
        AgentStore.EventPage page = store.readEvents(AgentStore.EventQuery.withDefaultMaxBytes(conversation, 0, throughSequence, 10_000));
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        return page.events().stream().filter(event -> event.execution().equals(execution))
                .map(codec::decode)
                .anyMatch(io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent.ContextCompactionStarted.class::isInstance);
    }

    /** 终态断言直接读 SQLite 事件，避免把观察失败映射误认为 Engine 未提交。 */
    private static boolean hasPersistedCompactionOutcome(
            AgentStore store, AgentStore.ConversationKey conversation, AgentStore.ExecutionKey execution) {
        long throughSequence = store.loadConversation(conversation).orElseThrow().eventSequence();
        AgentStore.EventPage page = store.readEvents(AgentStore.EventQuery.withDefaultMaxBytes(conversation, 0, throughSequence, 10_000));
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        return page.events().stream().filter(event -> event.execution().equals(execution))
                .map(codec::decode)
                .anyMatch(io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent.ContextCompacted.class::isInstance);
    }

    /** 注入只对 Engine 实时尾读可见的零进度页，确保分页停滞由真实读取链路触发。 */
    private static AgentStore stalledTailEvents(AgentStore delegate, CountDownLatch observed) {
        AtomicBoolean firstTailRead = new AtomicBoolean(true);
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(), new Class<?>[]{AgentStore.class},
                (proxy, method, args) -> {
                    if ("readEvents".equals(method.getName()) && args[0] instanceof AgentStore.EventQuery query
                        && query.throughSequence() == Long.MAX_VALUE && firstTailRead.compareAndSet(true, false)) {
                        observed.countDown();
                        return new AgentStore.EventPage(List.of(), query.afterSequence(), true);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /**
     * 复用真实固定摘要内容，但在摘要调用内建立可控屏障；取消 token 到达后仍由测试决定何时返回，
     * 使 ContextCompactionService 的取消检查与 Checkpoint CAS 之间不存在测试调度竞速。
     */
    private static SummaryModel.Factory blockingSummaryModels(
            CountDownLatch started, CountDownLatch cancellationObserved, CountDownLatch release) {
        return binding -> {
            SummaryModel fixed = fixedSummaryModels().bind(binding);
            return new SummaryModel() {
                /** 固定摘要估算沿用同一测试口径，不发起外部 Tokenizer 调用。 */
                @Override
                public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                    return fixed.estimateInputTokens(prompt);
                }

                /** 记录真实运行态和取消令牌到达时刻，释放闸门后再交回可追溯的固定摘要。 */
                @Override
                public Result summarize(SummaryPrompt prompt) {
                    started.countDown();
                    while (true) {
                        if (binding.cancellationToken().isCancellationRequested()) {
                            cancellationObserved.countDown();
                        }
                        try {
                            if (release.await(10, TimeUnit.MILLISECONDS)) {
                                return fixed.summarize(prompt);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("summary fixture must be released explicitly", interrupted);
                        }
                    }
                }
            };
        };
    }

    /** 复用当前 Engine Loop，以本地 Provider stage 与 SQLite 事务保证等待路径不是 mock handle。 */
    private static AgentEngine liveEngine(AgentStore store, ModelPort model,
                                          AtomicReference<AgentIdentity> compactionIdentity) {
        return liveEngine(store, model, compactionIdentity, binding -> {
            throw new AssertionError("queued compaction must not start summary generation");
        });
    }

    /** 成功压缩回归只替换摘要端口，其余仍使用真实 Engine 调度、SQLite 与事件事务。 */
    private static AgentEngine liveEngine(AgentStore store, ModelPort model,
                                          AtomicReference<AgentIdentity> compactionIdentity,
                                          SummaryModel.Factory summaryModels) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration("provider_test", "model_test",
                "cfg_test", ModelPort.Api.OPENAI_RESPONSES, "upstream_test", URI.create("https://example.invalid"),
                "secret_test", Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 32, null));
        ExtensionRegistry.Snapshot extensions = new ExtensionRegistry().freeze();
        AgentPromptSession prompt = new FixedAgentPromptSession(ContextBudget.capabilities(100_000, 8_192, true));
        ManagedAttachmentReader attachments = request -> {
            throw new AssertionError("scheduler fixture must not read attachments");
        };
        AgentRequestRuntimeResolver resolver = (request, base, recovered) -> {
            if (request.kind() == io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest.Kind.COMPACTION) {
                compactionIdentity.set(request.identity());
            }
            return new AgentRequestRuntime(configuration, model,
                    summaryModels,
                    extensions, prompt, "b".repeat(64), AgentStoreTransactionParticipant.NONE, attachments,
                    new ToolProjectionLimits(64_000, 16_000), Map.of(), () -> { });
        };
        return new AgentEngineBuilder().store(store).runtimeResolver(resolver).parallelism(1).build();
    }

    /** 为成功压缩与排队取消生成同一类真实短答案历史，压缩差异只来自调用时长和摘要模型。 */
    private static ModelPort completedHistoryModel() {
        return new ModelPort() {
            /** 按完整消息正文计量，让长历史确实跨过当前会话的压缩保留目标。 */
            @Override
            public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
                long tokens = 100 + request.prompt().systemPrompt().length() + request.messages().stream()
                        .flatMap(message -> message.content().stream())
                        .filter(TextContent.class::isInstance)
                        .map(TextContent.class::cast)
                        .mapToLong(value -> value.text().length()).sum();
                return new InputTokenEstimate(tokens, tokens, "c".repeat(64));
            }

            /** 每个 seed Turn 只提交一条短答案，历史体积来自用户事实而非伪造的超长模型响应。 */
            @Override
            public CompletionStage<ModelOutcome> start(ModelRequest request,
                    io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink sink, CancellationToken cancellation) {
                sink.onEvent(new TextDelta("seeded history turn completed")).toCompletableFuture().join();
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                        new ModelUsage(100, 10, 110)));
            }
        };
    }

    /** 通过公开 Engine 使用本地 Store owner 提交完成态 Turn，保证压缩历史和预建会话处于同一归属边界。 */
    private static List<TurnProjection> seedCompletedTurns(AgentEngine engine, int count) throws Exception {
        List<TurnProjection> turns = new ArrayList<>();
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            String turnId = "turn_seed_" + ordinal;
            AgentIdentity identity = new AgentIdentity("ja.local", "thr_test", turnId, "user_local");
            AgentExecutionOutcome outcome = engine.submit(AgentRequest.text(identity,
                            "history-" + ordinal + ":" + "x".repeat(12_000), "op_seed_" + ordinal))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, outcome).result().state());
            turns.add(new TurnProjection(turnId, TurnState.COMPLETED, 2, true, "item_seed_" + ordinal));
        }
        return List.copyOf(turns);
    }

    /** 固定摘要逐条保留淘汰消息的来源 ordinal，满足真实压缩规划器的证据覆盖验证。 */
    private static SummaryModel.Factory fixedSummaryModels() {
        return binding -> new SummaryModel() {
            /** 小而稳定的计量确保 checkpoint 摘要不会抵消长历史带来的压缩收益。 */
            @Override
            public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                return new ModelPort.InputTokenEstimate(100, 100, "e".repeat(64));
            }

            /** 只输出可追溯的来源事实；压缩规划与写入继续由生产 Context 和 Store 实现执行。 */
            @Override
            public Result summarize(SummaryPrompt prompt) {
                List<SummaryDocument.Fact> facts = prompt.evictedMessages().stream()
                        .map(message -> new SummaryDocument.Fact(
                                "SUMMARY_PRESERVED_" + message.ordinal(), message.ordinal()))
                        .toList();
                if (facts.isEmpty()) {
                    throw new AssertionError("manual compaction must evict source history");
                }
                SummaryDocument document = new SummaryDocument(List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), facts, List.of(), List.of(), List.of());
                return new Result(document, new CheckpointUsage(100, 10, 110, 0, 0));
            }
        };
    }

    /** 构造无效模型侧效应解析器的 Engine，门禁测试若越过 Ja 预检会立即失败。 */
    private static AgentEngine dormantEngine() {
        AgentStore store = unsupported(AgentStore.class);
        AgentRequestRuntimeResolver resolver = (request, extensions, recovered) -> {
            throw new AssertionError("preflight must not resolve a model runtime");
        };
        return new AgentEngineBuilder().store(store).runtimeResolver(resolver).build();
    }

    /** 固定测试身份与 CAS revision，防止用例把默认读取最新状态当成合法行为。 */
    private static ContextCompactionUseCase.Command command(long revision) {
        return new ContextCompactionUseCase.Command("thr_test", revision);
    }

    /** 固定 Ja 偏好和 Thread 投影，测试只改变状态、revision 或当前历史路径。 */
    private static ThreadProjection projection(long revision, List<TurnProjection> turns) {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        return new ThreadProjection("thr_test", "ws_test", preferences, revision, turns);
    }

    /** 动态拒绝代理保证测试不因接口新增方法而静默获得伪成功结果。 */
    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new AssertionError("unexpected call: " + method.getName());
                });
    }

    /** 将两种请求取消入口共用同一真实 Engine 终态屏障，避免重复测试装配漂移。 */
    private enum CancellationMode {
        /** 服务等待期间线程收到 interrupt。 */
        THREAD_INTERRUPT,
        /** 服务等待期间调用方取消令牌。 */
        TOKEN_CANCEL
    }

    /** 观察故障分支保持真实 Engine 与 SQLite，只替换客户端 sink 或一次实时尾读。 */
    private enum ObservationFailureMode {
        /** 客户端断开后停止推送，但压缩仍正常提交。 */
        SINK_FAILURE,
        /** Store 尾读游标停滞后报告观察状态未知，仍等待 Engine 持久终态。 */
        STALLED_CURSOR
    }
}

