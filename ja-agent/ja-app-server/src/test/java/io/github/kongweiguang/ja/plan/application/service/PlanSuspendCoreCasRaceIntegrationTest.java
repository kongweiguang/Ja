// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaAdmissionParticipantFactory;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 Ja SQLite 与 AgentEngine 固定暂停快照和合法并发输入之间的 CAS 竞态。 */
final class PlanSuspendCoreCasRaceIntegrationTest extends PersistenceTestSupport {
    private static final String THREAD_ID = "thr_suspend_core_race";
    private static final String TURN_ID = "turn_suspend_core_race";
    private static final String WORKSPACE_ID = "ws_suspend_core_race";
    private static final String START_OPERATION = "op_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String START_MESSAGE_ID = "item_suspend_core_race_start";
    private static final Instant ACCEPTED_AT = START.plusSeconds(10);
    private static final ThreadPreferences PREFERENCES = new ThreadPreferences("provider_test", "model_test",
            "medium", AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
            ThreadPreferences.TitleSource.PLACEHOLDER);

    /**
     * 在暂停控制 CAS 已读取快照后提交真实队列修改，要求内部 revision 竞争被安全结算而不是泄漏给业务调用方。
     * 先释放 Store 屏障，再关闭 Engine，最后才以本地 Provider 完成作为清理兜底；不把清理声称为自然模型完成。
     *
     * @author kongweiguang
     */
    @Test
    void settlesSuspendAfterConcurrentInputCommitWithoutExposingStoreConflict() throws Exception {
        AgentIdentity identity = new AgentIdentity("ja.local", THREAD_ID, TURN_ID, "user_local");
        CountDownLatch suspendCommitReady = new CountDownLatch(1);
        CountDownLatch allowSuspendCommit = new CountDownLatch(1);
        AtomicReference<AgentStore.ControlCommit> suspendedControl = new AtomicReference<>();
        BlockingModel model = new BlockingModel();
        Throwable suspendFailure = null;
        Throwable completionFailure = null;
        AgentStore.ExecutionSnapshot executionAfterRace = null;
        long suspendRecordCount = -1;
        long suspendRecordCountBeforeReplay = -1;
        AgentInputQueue queueAfterReplay = null;
        AgentStore.ControlCommit capturedControl = null;
        long conversationRevisionAfterInput = -1;
        long mutationVersionAfterInput = -1;

        try (TestDatabase database = database("plan-suspend-core-cas-race")) {
            database.history().register(new Workspace.Registration(WORKSPACE_ID, temp.toAbsolutePath(),
                    "workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
            database.history().createThread(new ThreadSummary.Creation(THREAD_ID, WORKSPACE_ID,
                    "暂停并发控制回归", PREFERENCES, START));

            AgentStore durableStore = database.jaAgentStore();
            AgentStore store = pauseAtSuspendCommit(durableStore, suspendCommitReady, allowSuspendCommit,
                    suspendedControl);
            AgentExecutionHandle handle = null;
            CompletableFuture<Void> suspendAttempt = null;
            ExecutorService suspendExecutor = Executors.newSingleThreadExecutor();
            try {
                try (store; AgentEngine engine = new AgentEngineBuilder().store(store)
                        .runtimeResolver(runtimeResolver(model))
                        .parallelism(1)
                        .build()) {
                    try {
                        long threadRevision = durableStore.loadConversation(new AgentStore.ConversationKey(
                                identity.tenantId(), identity.threadId(), identity.userId())).orElseThrow().revision();
                        handle = engine.submit(userRequest(identity, threadRevision));
                        assertTrue(model.started.await(10, TimeUnit.SECONDS), "local Provider call did not start");
                        assertEquals(1, model.calls.get(),
                                "the real Engine must reach exactly one controlled model call");

                        suspendAttempt = CompletableFuture.runAsync(
                                () -> engine.suspend(identity, "op_suspend_core_race_pause", "PLAN_PAUSE")
                                        .toCompletableFuture().join(), suspendExecutor);
                        assertTrue(suspendCommitReady.await(10, TimeUnit.SECONDS),
                                "suspend did not reach the post-snapshot Store boundary");

                        capturedControl = suspendedControl.get();
                        assertNotNull(capturedControl,
                                "suspend CAS request must be captured before the competing write");
                        assertEquals(AgentStore.ControlKind.SUSPEND_REQUEST, capturedControl.kind());

                        AgentInputCommand.Enqueue queuedInput = new AgentInputCommand.Enqueue(
                                "op_suspend_core_race_input", "input_suspend_core_race",
                                AgentInputCommand.Kind.FOLLOW_UP,
                                new UserContent(List.of(new TextContent("queued while the Provider call is active"))),
                                Map.of());
                        engine.mutateInput(identity, queuedInput).toCompletableFuture().get(10, TimeUnit.SECONDS);
                        AgentStore.ExecutionSnapshot afterInput =
                                durableStore.loadExecution(execution(identity)).orElseThrow();
                        conversationRevisionAfterInput = durableStore.loadConversation(
                                execution(identity).conversation()).orElseThrow().revision();
                        mutationVersionAfterInput = afterInput.mutationVersion();
                        assertTrue(conversationRevisionAfterInput > capturedControl.expectedThreadRevision(),
                                "the input commit must advance the conversation revision after suspend read its snapshot");
                        assertTrue(mutationVersionAfterInput > capturedControl.expectedMutationVersion(),
                                "the input commit must advance the execution mutation version after suspend read its snapshot");

                        allowSuspendCommit.countDown();
                        try {
                            suspendAttempt.get(10, TimeUnit.SECONDS);
                        } catch (ExecutionException failure) {
                            suspendFailure = rootCause(failure);
                        }
                        suspendRecordCountBeforeReplay = recordCount(durableStore, execution(identity),
                                "agent.execution.suspend-request");
                        if (suspendFailure == null) {
                            engine.suspend(identity, "op_suspend_core_race_pause", "PLAN_PAUSE")
                                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
                            suspendRecordCount = recordCount(durableStore, execution(identity),
                                    "agent.execution.suspend-request");
                            queueAfterReplay = engine.inspectInputQueue(identity);
                        } else {
                            suspendRecordCount = suspendRecordCountBeforeReplay;
                        }
                        executionAfterRace = durableStore.loadExecution(execution(identity)).orElseThrow();
                    } finally {
                        // 先放开被控制的 CAS，再由 try-with-resources 关闭 Engine；Provider 兜底完成留给外层 finally。
                        allowSuspendCommit.countDown();
                    }
                }
            } finally {
                try {
                    if (suspendAttempt != null && !suspendAttempt.isDone()) {
                        try {
                            suspendAttempt.get(10, TimeUnit.SECONDS);
                        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
                            if (suspendFailure == null) {
                                suspendFailure = rootCause(failure);
                            }
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (suspendFailure == null) {
                        suspendFailure = interrupted;
                    }
                } finally {
                    model.completeNormally();
                    try {
                        if (handle != null) {
                            try {
                                handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                            } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
                                completionFailure = rootCause(failure);
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        completionFailure = interrupted;
                    } finally {
                        suspendExecutor.shutdownNow();
                    }
                }
            }
        }

        assertNotNull(executionAfterRace, "the test must capture durable state before cleanup completes the model");
        String failureType = suspendFailure == null ? "none" : suspendFailure.getClass().getSimpleName();
        if (suspendFailure != null) {
            assertTrue(suspendFailure instanceof AgentStore.StoreConflictException,
                    "the deterministic stale-fence race must expose the Store CAS conflict, not a fixture failure: "
                            + failureType);
        }
        boolean settled = settledState(executionAfterRace.state());
        String diagnostic = "suspendFailure=" + failureType
                + ", stateAtSuspendReturn=" + executionAfterRace.state()
                + ", capturedExpectedRevision=" + capturedControl.expectedThreadRevision()
                + ", revisionAfterInput=" + conversationRevisionAfterInput
                + ", capturedExpectedMutation=" + capturedControl.expectedMutationVersion()
                + ", mutationAfterInput=" + mutationVersionAfterInput
                + ", suspendRequestRecords=" + suspendRecordCount
                + ", suspendRequestRecordsBeforeReplay=" + suspendRecordCountBeforeReplay
                + ", preservedInputEntries=" + (queueAfterReplay == null ? -1 : queueAfterReplay.entries().size())
                + ", settled=" + settled
                + ", completionFailure=" + (completionFailure == null ? "none"
                        : completionFailure.getClass().getSimpleName());
        assertTrue(suspendFailure == null && settled && completionFailure == null,
                "accepted suspend must settle after a legal concurrent input commit: " + diagnostic);
        assertEquals(1, suspendRecordCountBeforeReplay,
                "the accepted first suspend must create exactly one durable request");
        assertEquals(1, suspendRecordCount,
                "same-key suspend replay must not duplicate the durable request");
        assertNotNull(queueAfterReplay, "the input queue must remain inspectable after suspend replay");
        assertEquals(1, queueAfterReplay.entries().size(), "the accepted follow-up input must remain queued");
        AgentInputQueue.Entry preservedInput = queueAfterReplay.entries().getFirst();
        assertEquals("input_suspend_core_race", preservedInput.inputId());
        assertEquals(AgentInputQueue.Kind.FOLLOW_UP, preservedInput.kind());
        assertEquals(AgentInputQueue.Status.PENDING, preservedInput.status());
    }

    /**
     * 仅暂停请求在真实 Store 之前等待；该点已越过 Snapshot 读取，输入控制仍直接提交至真实 Ja SQLite。
     *
     * @author kongweiguang
     */
    private static AgentStore pauseAtSuspendCommit(AgentStore delegate, CountDownLatch ready,
                                                   CountDownLatch release,
                                                   AtomicReference<AgentStore.ControlCommit> captured) {
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[] {AgentStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("controlCommit") && arguments != null
                            && arguments.length > 0 && arguments[0] instanceof AgentStore.ControlCommit request
                            && request.kind() == AgentStore.ControlKind.SUSPEND_REQUEST) {
                        captured.set(request);
                        ready.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("suspend CAS test barrier was not released");
                        }
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /**
     * 将 Ja Thread 的现有快照版本与合法 Core 请求一并冻结，使用真实 Ja admission participant 建立 Turn。
     *
     * @author kongweiguang
     */
    private AgentRequest userRequest(AgentIdentity identity, long threadRevision) {
        Map<String, String> attributes = Map.ofEntries(
                Map.entry("ja.workspaceId", WORKSPACE_ID),
                Map.entry("ja.origin", TurnOrigin.USER.name()),
                Map.entry("ja.providerId", PREFERENCES.providerId()),
                Map.entry("ja.modelId", PREFERENCES.modelId()),
                Map.entry("ja.accessMode", PREFERENCES.accessMode().name()),
                Map.entry("ja.collaborationMode", PREFERENCES.collaborationMode().name()),
                Map.entry("ja.expectedThreadRevision", Long.toString(threadRevision)),
                Map.entry("ja.acceptedAt", ACCEPTED_AT.toString()),
                Map.entry("ja.method", "turn/start"),
                Map.entry("ja.sourceMessageId", START_MESSAGE_ID),
                Map.entry("ja.operationKey", START_OPERATION),
                Map.entry("ja.requestFingerprint", "a".repeat(64)));
        return new AgentRequest(identity, new UserContent(List.of(new TextContent("run controlled suspend race"))),
                START_OPERATION, Optional.empty(), attributes,
                AgentExecutionRequest.HistorySelection.currentConversation(), ACCEPTED_AT,
                List.of(), Optional.empty(), Optional.empty());
    }

    /**
     * 只为真实 Engine admission 安装 Ja 同事务参与者；Provider 是本地可控替身，不具备网络发送实现。
     *
     * @author kongweiguang
     */
    private static io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver runtimeResolver(
            BlockingModel model) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                "provider_test", "model_test", "cfg_suspend_race", ModelPort.Api.OPENAI_RESPONSES,
                "model_test", URI.create("http://127.0.0.1:1"), "unused-test-secret", Duration.ofMinutes(1),
                Duration.ofMinutes(1), Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
        AgentPromptSession prompt = new FixedAgentPromptSession(ContextBudget.capabilities(100_000, 8_192, true));
        SummaryModel.Factory summaries = binding -> {
            throw new AssertionError("controlled suspend scenario must not invoke summary generation");
        };
        ManagedAttachmentReader attachments = request -> {
            throw new AssertionError("controlled suspend scenario has no attachments");
        };
        return (request, base, recovered) -> {
            Map<String, String> attributes = request.contextAttributes();
            AdmissionFacts facts = new AdmissionFacts(request.identity(), attributes.get("ja.workspaceId"),
                    TurnOrigin.valueOf(attributes.get("ja.origin")), attributes.get("ja.method"),
                    Instant.parse(attributes.get("ja.acceptedAt")), attributes.get("ja.sourceMessageId"),
                    null, null, null, null, attributes.get("ja.operationKey"),
                    attributes.get("ja.requestFingerprint"),
                    Long.parseLong(attributes.get("ja.expectedThreadRevision")), Optional.empty());
            AgentStoreTransactionParticipant admission = new JaAdmissionParticipantFactory().create(facts);
            AgentStoreTransactionParticipant admissionOnly = transaction -> {
                if (transaction.kind() == AgentStore.TransactionKind.ADMISSION) {
                    admission.apply(transaction);
                }
            };
            return new AgentRequestRuntime(configuration, model, summaries, base, prompt, "b".repeat(64),
                    admissionOnly, attachments, new ToolProjectionLimits(64_000, 16_000),
                    Map.of("accessMode", AccessMode.APPROVAL_REQUIRED.name(),
                            "collaborationMode", CollaborationMode.DEFAULT.name(),
                            "configGeneration", configuration.configGeneration()), () -> { });
        };
    }

    /**
     * 只读 Core Store 对该次执行的稳定记录页，断言仅依赖控制记录类型和数量而不读取 payload。
     *
     * @author kongweiguang
     */
    private static long recordCount(AgentStore store, AgentStore.ExecutionKey execution, String type) {
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(execution).orElseThrow();
        return store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(execution, 0,
                snapshot.lastRecordSequence(), 1_000)).records().stream()
                .filter(record -> record.recordType().equals(type))
                .count();
    }

    /**
     * 将显式测试 identity 转成 Core Store 完整键，避免用 Thread-only 查询误判执行范围。
     *
     * @author kongweiguang
     */
    private static AgentStore.ExecutionKey execution(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey(identity.tenantId(),
                identity.threadId(), identity.userId()), identity.turnId());
    }

    /**
     * 报告可见失败时去除异步包装，只保留异常类型用于测试诊断，不输出异常文本或请求数据。
     *
     * @author kongweiguang
     */
    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 暂停成功必须在执行层已无活跃派发时返回；等待态与已提交终态均为安全边界。
     *
     * @author kongweiguang
     */
    private static boolean settledState(String state) {
        return state.equals(TurnState.SUSPENDED.name()) || state.equals(TurnState.WAITING_INPUT.name())
                || state.equals(TurnState.WAITING_APPROVAL.name()) || TurnState.valueOf(state).terminal();
    }

    /**
     * Provider 调用由测试显式完成或响应取消，避免外部模型及请求竞速影响窗口。
     *
     * @author kongweiguang
     */
    private static final class BlockingModel implements ModelPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<ModelOutcome> response = new CompletableFuture<>();

        /**
         * 固定本地 Token 估算，不把测试接到 tokenizer 服务或真实 Provider。
         *
         * @author kongweiguang
         */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(16, 16, "c".repeat(64));
        }

        /**
         * 以取消回调唤醒受控模型等待，使真正接受的 suspend 可以走 Engine 原有安全结算路径。
         *
         * @author kongweiguang
         */
        @Override
        public java.util.concurrent.CompletionStage<ModelOutcome> start(
                ModelRequest request, ModelEventSink sink, CancellationToken cancellation) {
            cancellation.throwIfCancellationRequested();
            calls.incrementAndGet();
            CancellationToken.Registration registration = cancellation.onCancellation(() ->
                    response.completeExceptionally(new CancellationException("controlled provider cancelled")));
            response.whenComplete((result, failure) -> registration.close());
            started.countDown();
            sink.onEvent(new ModelPort.TextDelta("controlled response"));
            return response;
        }

        /**
         * 仅在回归清理阶段结束模型等待，保证预期失败也能释放真实 Engine 的运行租约。
         *
         * @author kongweiguang
         */
        private void completeNormally() {
            response.complete(new ModelOutcome(FinishReason.STOP, null, new ModelUsage(4, 1, 5)));
        }
    }
}
