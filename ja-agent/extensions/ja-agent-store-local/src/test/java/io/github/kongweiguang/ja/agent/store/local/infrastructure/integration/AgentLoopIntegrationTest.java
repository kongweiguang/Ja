// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.store.local.infrastructure.integration;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventWatch;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInput;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputMutationReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Usage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsageCertainty;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.WaitRequest;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.api.TurnResult;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在真实事务存储上贯穿公开 Engine、模型、工具和上下文，避免用 mock Store 掩盖游标断点。 */
final class AgentLoopIntegrationTest {
    @TempDir Path directory;

    /**
     * 首次持久准入前的零游标 Watch 必须通过真实 SQLite 提交收到完整、有序的执行状态事件。
     *
     * @author kongweiguang
     */
    @Test
    void watchRegisteredBeforeFirstAdmissionObservesCommittedSQLiteEventsInOrder() throws Exception {
        AgentIdentity identity = identity("execution-watch-before-admission");
        SQLiteAgentStore store = sqlite();
        GatedModel model = new GatedModel();
        try (AgentEngine engine = engine(model, new LookupTool(new AtomicInteger()), store);
             AgentEventWatch watch = engine.watchEvents(identity, 0)) {
            AgentExecutionHandle handle = engine.submit(
                    AgentRequest.text(identity, "在真实准入前建立事件订阅", "op-watch-before-admission"));
            assertTrue(model.started.await(10, TimeUnit.SECONDS), "Provider dispatch must follow admission");

            long notifiedSequence = watch.awaitNext(0, Duration.ofSeconds(10)).orElseThrow();
            AgentEventPage page;
            while (true) {
                page = engine.readEvents(identity, 0, 1_000);
                if (notifiedSequence >= page.nextSequence()) {
                    break;
                }
                notifiedSequence = watch.awaitNext(notifiedSequence, Duration.ofSeconds(10)).orElseThrow();
            }

            List<AgentStore.PersistedEvent> beforeProviderCompletes = page.events();
            assertFalse(beforeProviderCompletes.isEmpty());
            assertEquals("agent.execution.admitted", beforeProviderCompletes.getFirst().eventType());
            AgentStore.PersistedEvent firstState = beforeProviderCompletes.stream()
                    .filter(event -> event.eventType().equals("agent.execution.state"))
                    .findFirst().orElseThrow();
            assertEquals("RUNNING", assertInstanceOf(JsonText.class, firstState.payload().get("to")).value());
            assertTrue(firstState.payload().get("from") instanceof JsonText,
                    "state transition must preserve its complete source value");
            assertEquals(page.nextSequence(), beforeProviderCompletes.getLast().sequence());
            assertEquals(beforeProviderCompletes.size(), beforeProviderCompletes.stream()
                    .map(AgentStore.PersistedEvent::eventId).distinct().count(),
                    "commit notifications must not duplicate durable event identities");
            for (int index = 0; index < beforeProviderCompletes.size(); index++) {
                assertEquals(index + 1L, beforeProviderCompletes.get(index).sequence(),
                        "first admission events must remain contiguous and ordered");
            }

            model.complete();
            AgentExecutionOutcome.Completed completed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, completed.result().state());
            List<AgentStore.PersistedEvent> allEvents = engine.readEvents(identity, 0, 1_000).events();
            assertEquals(beforeProviderCompletes,
                    allEvents.subList(0, beforeProviderCompletes.size()),
                    "later commits must preserve the exact already-observed prefix");
            assertEquals(allEvents.size(), allEvents.stream()
                    .map(AgentStore.PersistedEvent::eventId).distinct().count());
        }
    }

    /** STOP 标签不能丢掉已完整返回的工具调用，最终回复必须读到同一调用的已提交结果。 */
    @Test
    void commitsToolBeforeTheFollowingModelRequest() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel("lookup", false);
        try (AgentEngine engine = engine(model, executions)) {
            AgentExecutionOutcome result = engine.submit(AgentRequest.text(identity("execution-tool"), "查询", "op-tool"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            AgentExecutionOutcome.Completed completed = assertInstanceOf(AgentExecutionOutcome.Completed.class, result);
            assertEquals(TurnState.COMPLETED, completed.result().state());
            assertEquals(1, executions.get());
            assertEquals(2, model.requests.get());
            assertEquals("call-native:1", model.result.callId());
            assertEquals("""
                业务结果
                [characters=4 sha256=\
                b26b508795fe35e772a1072ea282e0d4a354c2dcf7cb8b51159373e55e62475d]\
                """,
                    model.result.content());
            assertFalse(model.result.error());
        }
    }

    /** 真实 SQLite 在每次 Provider 调用前都能读到同一提交的 UNKNOWN 估算、事件和恢复水位。 */
    @Test
    void commitsUnknownUsageEstimateBeforeEveryProviderDispatchAndKeepsToolEffectAcrossRestart() throws Exception {
        AgentIdentity executionIdentity = identity("execution-usage-dispatch");
        SQLiteAgentStore store = sqlite();
        List<AgentStore.ExecutionCommit> usageCommits = new CopyOnWriteArrayList<>();
        AgentStore observedStore = observeUsageDispatchCommits(store, usageCommits);
        AtomicInteger toolExecutions = new AtomicInteger();
        UsageDispatchModel model = new UsageDispatchModel(store, executionIdentity, usageCommits);
        AgentTool tool = new LookupTool(toolExecutions, ToolSideEffect.EXTERNAL);
        AgentEngine current = engine(model, tool, observedStore);
        AgentExecutionOutcome.Completed completed;
        List<AgentStore.StoredRecord> committedRecords;
        List<AgentStore.PersistedEvent> committedEvents;
        try {
            AgentExecutionOutcome outcome = current.submit(AgentRequest.text(
                    executionIdentity, "执行一次外部查询", "op-usage-dispatch"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            completed = assertInstanceOf(AgentExecutionOutcome.Completed.class, outcome);
            assertEquals(TurnState.COMPLETED, completed.result().state());
            assertEquals(2, model.requests.get());
            assertEquals(1, toolExecutions.get());
            assertEquals("COMPLETED", current.query(executionIdentity).orElseThrow().state());

            committedRecords = executionRecords(store, executionIdentity);
            committedEvents = current.readEvents(executionIdentity, 0, 1000).events();
            assertFinalUsageFacts(executionIdentity, committedRecords);
            assertEquals(1, committedRecords.stream()
                    .filter(record -> record.recordType().equals("agent.tool.result")).count());
            assertEquals(1, committedEvents.stream()
                    .filter(event -> event.eventType().equals("agent.execution.terminal")).count());
        } finally {
            current.close();
        }

        SQLiteAgentStore reopenedStore = sqlite();
        AgentEngine recovered = engine(model, tool, reopenedStore);
        try {
            AgentExecutionOutcome resumed = recovered.resume(executionIdentity).completion()
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(completed.result(), assertInstanceOf(AgentExecutionOutcome.Completed.class, resumed).result());
            assertEquals(2, model.requests.get(), "terminal recovery must not dispatch another Provider request");
            assertEquals(1, toolExecutions.get(), "committed external effect must not be replayed");
            assertEquals(committedRecords, executionRecords(reopenedStore, executionIdentity));
            assertEquals(committedEvents, recovered.readEvents(executionIdentity, 0, 1000).events());
        } finally {
            recovered.close();
        }
    }

    /** 关闭发生在工具结果已落库、下一次 Provider 尚未结算时，重建 Engine 也不能重放工具。 */
    @Test
    void doesNotReplayCommittedToolWhenShutdownInterruptsFollowingProviderRequest() throws Exception {
        AgentIdentity executionIdentity = identity("execution-tool-provider-restart");
        SQLiteAgentStore store = sqlite();
        AtomicInteger toolExecutions = new AtomicInteger();
        PendingAfterToolModel model = new PendingAfterToolModel();
        AgentEngine first = engine(model, new LookupTool(toolExecutions), store);
        AgentExecutionHandle handle = first.submit(AgentRequest.text(
                executionIdentity, "执行一次查询", "op-tool-provider-restart"));
        try {
            assertTrue(model.followingRequest.await(10, TimeUnit.SECONDS),
                    "the Provider request after the committed tool result must start");
            assertEquals("RUNNING", first.query(executionIdentity).orElseThrow().state());
            assertEquals(1, executionRecords(store, executionIdentity).stream()
                    .filter(record -> record.recordType().equals("agent.tool.result")).count());
            assertEquals(1, toolExecutions.get());

            first.close();
            AgentExecutionOutcome.Completed cancelled = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            assertEquals(TurnState.CANCELLED, cancelled.result().state());
            List<AgentStore.StoredRecord> committedRecords = executionRecords(store, executionIdentity);

            SQLiteAgentStore reopenedStore = sqlite();
            AgentEngine recovered = engine(model, new LookupTool(toolExecutions), reopenedStore);
            try {
                AgentExecutionOutcome.Completed resumed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                        recovered.resume(executionIdentity).completion().toCompletableFuture()
                                .get(20, TimeUnit.SECONDS));
                assertEquals(TurnState.CANCELLED, resumed.result().state());
                assertEquals(2, model.requests.get(), "recovery must not dispatch after the persisted cancellation");
                assertEquals(1, toolExecutions.get(), "the committed tool result must never be replayed");
                assertEquals(committedRecords, executionRecords(reopenedStore, executionIdentity));
            } finally {
                recovered.close();
            }
        } finally {
            first.close();
        }
    }

    /** 公开 Engine 将 Started 边界传给结果 hook，拒绝不会被误认为已派发的外部动作。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void toolResultContextReportsWhetherExecutionStarted(boolean rejectBeforeStart) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Boolean> observedStarted = new AtomicReference<>();
        ScriptedModel model = new ScriptedModel("lookup", rejectBeforeStart);
        StartedBoundaryProbe middleware = new StartedBoundaryProbe(rejectBeforeStart, observedStarted);
        try (AgentEngine engine = engine(model, new LookupTool(executions), sqlite(), middleware)) {
            AgentExecutionOutcome outcome = engine.submit(AgentRequest.text(
                            identity("execution-result-started-" + rejectBeforeStart), "查询", "op-result-started"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, outcome).result().state());
        }
        assertEquals(!rejectBeforeStart, observedStarted.get());
        assertEquals(rejectBeforeStart ? 0 : 1, executions.get());
    }

    /** 未知工具是模型可修正的配对失败，不终结整个任务，也不执行名称相近的注册工具。 */
    @Test
    void returnsUnknownToolFailureToTheModel() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel("missing", true);
        try (AgentEngine engine = engine(model, executions)) {
            AgentExecutionOutcome result = engine.submit(AgentRequest.text(identity("execution-unknown"), "查询", "op-unknown"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, assertInstanceOf(AgentExecutionOutcome.Completed.class, result).result().state());
            assertEquals(0, executions.get());
            assertTrue(model.result.error());
            assertEquals(2, model.requests.get());
        }
    }

    /** 注册工具真实返回 FAILED 时，模型应收到错误 ToolResult 并能据此完成修正答复。 */
    @Test
    void returnsRegisteredToolFailureToTheModelForCorrection() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolFailureCorrectionModel model = new ToolFailureCorrectionModel();
        try (AgentEngine engine = engine(model, new FailingLookupTool(executions), sqlite())) {
            AgentExecutionOutcome.Completed completed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.submit(AgentRequest.text(identity("execution-tool-failed"), "查询记录", "op-tool-failed"))
                            .completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            assertEquals(TurnState.COMPLETED, completed.result().state());
            assertEquals(2, model.requests.get());
            assertEquals(1, executions.get());
            assertTrue(model.receivedFailure);
            assertTrue(model.result.error());
            assertTrue(model.result.content().contains("上游目录暂不可用"));
            assertTrue(completed.result().summary().contains("我会根据工具故障修正答复"));
        }
    }

    /** 业务 Skill 来源显式选中后必须真正进入模型 Prompt，不能只做到目录发现可用。 */
    @Test
    void selectsASkillFromABusinessDefinedSource() throws Exception {
        SkillAwareModel model = new SkillAwareModel(List.of("原用户请求"));
        try (AgentEngine engine = engine(model, new LookupTool(new AtomicInteger()), sqlite(), List.of(new FixedSkillSource()))) {
            UserContent input = new UserContent(List.of(new SkillReferenceContent("business.docs:guide"), new TextContent("原用户请求")));
            AgentRequest request = new AgentRequest(identity("execution-business-skill"), input, "op-business-skill", Optional.empty(), Map.of());
            AgentExecutionOutcome completed = engine.submit(request).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, assertInstanceOf(AgentExecutionOutcome.Completed.class, completed).result().state());
            assertEquals(1, model.requests.get());
        }
    }

    /** 初始投递消息在首个模型请求前消费，但不能清空主用户已经选中的 Skill。 */
    @Test
    void initialMessagePreservesTheUsersSelectedSkill() throws Exception {
        SkillAwareModel model = new SkillAwareModel(List.of("原用户请求", "外部投递消息"));
        try (AgentEngine engine = engine(model, new LookupTool(new AtomicInteger()), sqlite(), List.of(new FixedSkillSource()))) {
            UserContent input = new UserContent(List.of(new SkillReferenceContent("business.docs:guide"), new TextContent("原用户请求")));
            AgentInputCommand.Enqueue message = new AgentInputCommand.Enqueue("delivery-operation", "delivery-input", AgentInputCommand.Kind.MESSAGE,
                    new UserContent(List.of(new TextContent("外部投递消息"))), Map.of("business.source", "another-session"));
            AgentRequest request = new AgentRequest(identity("execution-message-skill"), input, "op-message-skill", Optional.empty(), Map.of())
                    .withInitialQueuedInputs(List.of(message));
            AgentExecutionOutcome completed = engine.submit(request).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, assertInstanceOf(AgentExecutionOutcome.Completed.class, completed).result().state());
            assertEquals(1, model.requests.get());
        }
    }

    /** Skill 必须跨工具轮次保留，重新创建 Engine 后审批恢复也不能重复初始化或丢失正文。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void retainsSelectedSkillAcrossToolWaitAndRestart(boolean restart) throws Exception {
        ScriptedModel scripted = new ScriptedModel("lookup", false);
        SkillToolModel model = new SkillToolModel(scripted);
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-skill-restart");
        AgentEngine first = engine(model, new LookupTool(executions, ToolSideEffect.EXTERNAL), sqlite(), List.of(new FixedSkillSource()), new ApprovalWait());
        try {
            UserContent input = new UserContent(List.of(new SkillReferenceContent("business.docs:guide"), new TextContent("按指导查询")));
            AgentExecutionOutcome waiting = first.submit(new AgentRequest(identity, input, "op-skill-restart", Optional.empty(), Map.of()))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            String interactionId = assertInstanceOf(AgentExecutionOutcome.Suspended.class, waiting).interactionId().orElseThrow();
            if (restart) {
                first.close();
            }
            AgentEngine current = restart
                    ? engine(model, new LookupTool(executions, ToolSideEffect.EXTERNAL), sqlite(), List.of(new FixedSkillSource()), new ApprovalWait()) : first;
            try {
                current.answer(identity, new AgentAnswer(interactionId, "answer-skill-restart", 0,
                        List.of(new InteractionAnswer("question_approval", List.of("option_approve"), null, false)), Map.of()))
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                AgentExecutionOutcome completed = current.resume(identity).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(TurnState.COMPLETED, assertInstanceOf(AgentExecutionOutcome.Completed.class, completed).result().state());
                assertEquals(2, scripted.requests.get());
                assertEquals(1, executions.get());
            } finally {
                if (restart) {
                    current.close();
                }
            }
        } finally {
            first.close();
        }
    }

    /** 截断或坏 JSON 的调用保留原始身份并返回失败，不能因替换为空对象后通过 Schema 而真正执行。 */
    @ParameterizedTest
    @ValueSource(strings = {"TOOL_CALL_TRUNCATED", "TOOL_ARGUMENTS_INVALID"})
    void returnsPreflightFailureWithoutDispatch(String code) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel("lookup", true, code);
        try (AgentEngine engine = engine(model, executions)) {
            AgentExecutionOutcome result = engine.submit(AgentRequest.text(identity("execution-preflight"), "查询", "op-preflight"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, result).result().state());
            assertEquals(0, executions.get());
            assertTrue(model.result.error());
            assertEquals("call-native:1", model.result.callId());
        }
    }

    /** 瞬时流故障重试同一模型，已展示半截正文不能进入下一次请求历史或重复执行后续工具。 */
    @Test
    void retriesTransientFailureWithoutKeepingPartialAssistantText() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        RetryModel model = new RetryModel();
        try (AgentEngine engine = engine(model, executions)) {
            AgentExecutionOutcome result = engine.submit(AgentRequest.text(identity("execution-retry"), "查询", "op-retry"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, result).result().state());
            assertEquals(3, model.requests.get());
            assertEquals(1, executions.get());
        }
    }

    /** 仅在 RetryScheduled 已提交后取消，并验证退避退出、终态持久化及重启不再派发。 */
    @Test
    void cancellationDuringProviderRetryBackoffDoesNotRedispatchOrResume() throws Exception {
        AgentIdentity executionIdentity = identity("execution-cancel-provider-backoff");
        SQLiteAgentStore store = sqlite();
        BackoffModel model = new BackoffModel();
        AgentEngine first = engine(model, new LookupTool(new AtomicInteger()), store);
        AgentExecutionHandle handle = first.submit(AgentRequest.text(
                executionIdentity, "等待模型服务恢复", "op-cancel-provider-backoff"));
        try {
            assertTrue(model.requestFailed.await(10, TimeUnit.SECONDS), "the first Provider request must fail");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean retryScheduled = false;
            while (!retryScheduled && System.nanoTime() < deadline) {
                retryScheduled = first.readEvents(executionIdentity, 0, 1000).events().stream()
                        .anyMatch(event -> event.eventType().equals("agent.model.retry"));
                if (!retryScheduled) {
                    Thread.sleep(10);
                }
            }
            assertTrue(retryScheduled, "the retry wait must be based on its durable event");

            first.cancel(executionIdentity, "cancel-provider-backoff", "user requested cancellation")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            AgentExecutionOutcome.Completed cancelled = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(TurnState.CANCELLED, cancelled.result().state());
            assertEquals(1, model.requests.get(), "cancellation during backoff must prevent a second request");
            List<AgentStore.StoredRecord> committedRecords = executionRecords(store, executionIdentity);
            List<AgentStore.PersistedEvent> committedEvents = first.readEvents(executionIdentity, 0, 1000).events();

            SQLiteAgentStore reopenedStore = sqlite();
            AgentEngine recovered = engine(model, new LookupTool(new AtomicInteger()), reopenedStore);
            try {
                AgentExecutionOutcome.Completed resumed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                        recovered.resume(executionIdentity).completion().toCompletableFuture()
                                .get(10, TimeUnit.SECONDS));
                assertEquals(TurnState.CANCELLED, resumed.result().state());
                assertEquals(1, model.requests.get(), "a terminal cancelled execution must not revive on recovery");
                assertEquals(committedRecords, executionRecords(reopenedStore, executionIdentity));
                assertEquals(committedEvents, recovered.readEvents(executionIdentity, 0, 1000).events());
            } finally {
                recovered.close();
            }
        } finally {
            first.close();
        }
    }

    /** 无可缩减历史时返回持久 UNCHANGED 回执，手动压缩不能顺带启动普通模型回复。 */
    @Test
    void manualCompactionDoesNotSendAnAssistantRequest() throws Exception {
        ScriptedModel model = new ScriptedModel("lookup", false);
        try (AgentEngine engine = engine(model, new AtomicInteger())) {
            AgentIdentity identity = AgentCompactionRequest.compactionIdentity(identity("unused"), "compact-empty");
            AgentExecutionOutcome result = engine.compact(new AgentCompactionRequest(identity, "compact-empty"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            AgentExecutionOutcome.Compacted compacted = assertInstanceOf(AgentExecutionOutcome.Compacted.class, result);
            assertEquals(AgentCompactionReceipt.Outcome.UNCHANGED, compacted.receipt().outcome());
            assertTrue(compacted.receipt().checkpointId().isEmpty());
            assertEquals(0, model.requests.get());
        }
    }

    /** 关闭旧 Engine 后重建扩展实例仍能回答并恢复，证明等待和中间件状态不依赖内存字段。 */
    @Test
    void restoresACompletionWaitAcrossEngineInstances() throws Exception {
        ScriptedModel model = new ScriptedModel("lookup", false);
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-wait");
        String interactionId;
        try (AgentEngine first = engine(model, executions, new CompletionWait())) {
            AgentExecutionOutcome result = first.submit(AgentRequest.text(identity, "查询后确认", "op-wait"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            interactionId = assertInstanceOf(AgentExecutionOutcome.Suspended.class, result).interactionId().orElseThrow();
            assertEquals("WAITING_INPUT", first.query(identity).orElseThrow().state());
        }
        try (AgentEngine second = engine(model, executions, new CompletionWait())) {
            second.answer(identity, new AgentAnswer(interactionId, "answer-wait", 0,
                    List.of(new InteractionAnswer("question_confirm", List.of(), "确认", false)), Map.of()))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            AgentExecutionOutcome result = second.resume(identity).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, assertInstanceOf(AgentExecutionOutcome.Completed.class, result).result().state());
            assertEquals(1, executions.get());
            assertEquals(2, model.requests.get());
        }
    }

    /** 等待前的候选事实只保留状态补丁；回答后同一模型候选重入并原子提交最终事实。 */
    @Test
    void completionWaitFactsAreCommittedOnlyAfterAnswerAndReentry() throws Exception {
        ScriptedModel model = new ScriptedModel("lookup", false);
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-wait-facts");
        SQLiteAgentStore store = sqlite();
        String interactionId;
        try (AgentEngine first = engine(model, new LookupTool(executions), store, new AwaitFactsOnce())) {
            AgentExecutionOutcome outcome = first.submit(AgentRequest.text(identity, "查询后确认", "op-wait-facts"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            interactionId = assertInstanceOf(AgentExecutionOutcome.Suspended.class, outcome).interactionId().orElseThrow();
            assertTrue(middlewareFactEnvelopes(store, identity).isEmpty());
        }
        try (AgentEngine second = engine(model, new LookupTool(executions), store, new AwaitFactsOnce())) {
            second.answer(identity, new AgentAnswer(interactionId, "answer-wait-facts", 0,
                    List.of(new InteractionAnswer("question_confirm", List.of(), "确认", false)), Map.of()))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            AgentExecutionOutcome outcome = second.resume(identity).completion()
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, outcome).result().state());
            assertEquals(2, model.requests.get(), "resuming a completion wait must reuse its assistant candidate");
            assertEquals(1, executions.get());
        }
        List<com.fasterxml.jackson.databind.JsonNode> facts = middlewareFactEnvelopes(store, identity);
        assertEquals(1, facts.size());
        assertEquals("已完成", facts.getFirst().path("payload").path("candidate").asText());
    }

    /** 后置中间件要求继续时丢弃整批候选 facts，工具和最终回复随后各自按正常循环提交。 */
    @Test
    void completionContinueDropsCandidateFactsBeforeToolAndFinalCompletion() throws Exception {
        ContinuationToolModel model = new ContinuationToolModel();
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-completion-continue");
        SQLiteAgentStore store = sqlite();
        try (AgentEngine engine = engine(model, new LookupTool(executions), store,
                new CompletionFact("test-completion-primary"), new ContinueOnceWithFact())) {
            AgentExecutionOutcome outcome = engine.submit(AgentRequest.text(identity, "开始", "op-completion-continue"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, outcome).result().state());
        }
        assertEquals(3, model.requests.get());
        assertEquals(1, executions.get());
        List<com.fasterxml.jackson.databind.JsonNode> facts = middlewareFactEnvelopes(store, identity);
        assertEquals(2, facts.size(), "only the final Complete decisions may persist facts");
        assertEquals(Set.of("test-completion-primary", "test-late-continue"), facts.stream()
                .map(envelope -> envelope.path("namespace").asText()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(facts.stream().allMatch(envelope ->
                "最终答复".equals(envelope.path("payload").path("candidate").asText())));
        assertTrue(facts.stream().noneMatch(envelope -> envelope.path("payload")
                .path("hasUnverifiedToolEffects").asBoolean()));
    }

    /** 显式跳过仍未知的外部副作用后重建 Engine，完成扩展只能依据持久恢复状态看到风险。 */
    @Test
    void completionContextPreservesSkippedUnknownToolEffectsAfterRestart() throws Exception {
        UnknownEffectModel model = new UnknownEffectModel();
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-skipped-unknown-effect");
        SQLiteAgentStore store = sqlite();
        try (AgentEngine first = engine(model, new UnknownEffectTool(executions), store,
                new CompletionFact("test-recovery-observer"))) {
            AgentExecutionOutcome outcome = first.submit(AgentRequest.text(identity, "执行外部动作", "op-unknown-effect"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertInstanceOf(AgentExecutionOutcome.Suspended.class, outcome);
        }
        ToolRecoveryDecision pending = latestToolRecovery(store, identity);
        assertEquals(ToolRecoveryDecision.Status.PENDING, pending.status());
        try (AgentEngine restarted = engine(model, new UnknownEffectTool(executions), store,
                new CompletionFact("test-recovery-observer"))) {
            restarted.resolveToolRecovery(identity, "op-skip-unknown-effect", pending.recoveryId(), pending.callId(),
                    pending.revision(), RecoveryCommand.Decision.SKIP, "authorization-skip-unknown-effect")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            AgentExecutionOutcome outcome = restarted.resume(identity).completion()
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, outcome).result().state());
            assertEquals(2, model.requests.get());
            assertEquals(1, executions.get(), "unknown external action must not be replayed");
        }
        List<com.fasterxml.jackson.databind.JsonNode> facts = middlewareFactEnvelopes(store, identity);
        assertEquals(1, facts.size());
        assertTrue(facts.getFirst().path("payload").path("hasUnverifiedToolEffects").asBoolean());
    }

    /** 终态重启恢复只回读原结果；逐条补读事件不能制造第二份终态或重放业务工具。 */
    @Test
    void resumesACommittedTerminalWithoutNewEventsOrSideEffects() throws Exception {
        ScriptedModel model = new ScriptedModel("lookup", false);
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-terminal");
        List<AgentStore.PersistedEvent> committed;
        AgentExecutionOutcome.Completed original;
        try (AgentEngine first = engine(model, executions)) {
            original = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    first.submit(AgentRequest.text(identity, "查询", "op-terminal"))
                            .completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            committed = first.readEvents(identity, 0, 1000).events();
            assertFalse(committed.isEmpty());
        }
        try (AgentEngine second = engine(model, executions)) {
            AgentExecutionOutcome.Completed restored = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    second.resume(identity).completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            assertEquals(original.result(), restored.result());
            List<AgentStore.PersistedEvent> replayed = new java.util.ArrayList<>();
            long after = 0;
            while (true) {
                AgentEventPage page = second.readEvents(identity, after, 1);
                replayed.addAll(page.events());
                assertTrue(page.nextSequence() >= after);
                if (!page.hasMore()) {
                    break;
                }
                assertTrue(page.nextSequence() > after, "event replay must advance");
                after = page.nextSequence();
            }
            assertEquals(committed, replayed);
            assertEquals(1, executions.get());
            assertEquals(2, model.requests.get());
        }
    }

    /** 多次并发输入连续推进 Thread revision 时，取消仍落库一次且不重放已派发工具。 */
    @Test
    void retriesCancellationAfterConcurrentInputAdvancesConversationRevision() throws Exception {
        SQLiteAgentStore delegate = sqlite();
        int concurrentInputRevisions = 5;
        AtomicInteger cancelControlCommits = new AtomicInteger();
        AtomicInteger inputControlCommits = new AtomicInteger();
        AtomicReference<AgentStore.CommitReceipt> inputCommit = new AtomicReference<>();
        AtomicReference<String> cancellationOperationKey = new AtomicReference<>();
        AtomicReference<AgentEngine> revisionAdvancer = new AtomicReference<>();
        AgentIdentity identity = identity("execution-cancel-revision-race");
        AgentStore store = (AgentStore) java.lang.reflect.Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    AgentStore.ControlCommit commit = method.getName().equals("controlCommit")
                            ? (AgentStore.ControlCommit) arguments[0] : null;
                    if (commit != null && commit.kind() == AgentStore.ControlKind.CANCEL_REQUEST) {
                        int cancellationAttempt = cancelControlCommits.incrementAndGet();
                        cancellationOperationKey.compareAndSet(null, commit.operation().operationKey());
                        if (cancellationAttempt <= concurrentInputRevisions) {
                            revisionAdvancer.get().appendInput(identity,
                                    new AgentInput("input-revision-race-" + cancellationAttempt,
                                            new UserContent(List.of(new TextContent("并发输入 " + cancellationAttempt)))))
                                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
                        }
                    }
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (commit != null && commit.kind() == AgentStore.ControlKind.INPUT) {
                            inputCommit.set((AgentStore.CommitReceipt) result);
                            inputControlCommits.incrementAndGet();
                        }
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        PausedTool tool = new PausedTool();
        ScriptedModel model = new ScriptedModel("lookup", false);
        AgentEngine current = engine(model, tool, store);
        AgentEngine inputWriter = engine(new ScriptedModel("lookup", false), new PausedTool(), store);
        revisionAdvancer.set(inputWriter);
        try (inputWriter; current) {
            AgentExecutionHandle handle = current.submit(AgentRequest.text(identity, "执行查询", "op-cancel-revision-race"));
            try {
                assertTrue(tool.started.await(10, TimeUnit.SECONDS));
                assertEquals("RUNNING", current.query(identity).orElseThrow().state());

                current.cancel(identity, "cancel-revision-race", "用户取消")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);

                assertEquals(concurrentInputRevisions, inputControlCommits.get(),
                        "each competing input must advance the committed conversation revision");
                assertEquals(concurrentInputRevisions + 1, cancelControlCommits.get(),
                        "cancellation must retry until it commits after sustained revision changes");
                AgentStore.CommitReceipt inputReceipt = inputCommit.get();
                assertTrue(inputReceipt != null, "the concurrent appendInput must commit before cancellation");
                AgentStore.OperationKey cancellationKey = new AgentStore.OperationKey(
                        identity.tenantId(), identity.userId(), cancellationOperationKey.get());
                AgentStore.OperationReceipt cancellationReceipt = delegate.readReceipt(cancellationKey).orElseThrow();
                assertTrue(inputReceipt.threadRevision() < cancellationReceipt.threadRevision(),
                        "the latest competing input must commit before cancellation");
                assertEquals(inputReceipt.firstEventSequence(), inputReceipt.lastEventSequence(),
                        "one input operation must write one control event");
                assertEquals(cancellationReceipt.firstEventSequence(), cancellationReceipt.lastEventSequence(),
                        "one cancellation operation must write one control event");
                assertEquals(1L, executionRecords(delegate, identity).stream()
                                .filter(record -> record.recordType().equals(ExecutionControlCodec.CANCEL_REQUEST_RECORD_TYPE))
                                .count(),
                        "repeated CAS conflicts must still persist exactly one cancellation request");
                long revisionBeforeReplay = delegate.loadConversation(cancellationReceipt.conversation()).orElseThrow().revision();

                try (AgentEngine replay = engine(new ScriptedModel("lookup", false), new PausedTool(), store)) {
                    replay.cancel(identity, "cancel-revision-race", "用户取消")
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
                }

                assertEquals(cancellationReceipt, delegate.readReceipt(cancellationKey).orElseThrow());
                assertEquals(revisionBeforeReplay,
                        delegate.loadConversation(cancellationReceipt.conversation()).orElseThrow().revision(),
                        "same-key replay must not append another durable event");
                assertEquals(concurrentInputRevisions + 1, cancelControlCommits.get(),
                        "same-key replay must not issue another control commit");
            } finally {
                tool.finish();
            }

            TurnResult result = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS)).result();
            assertEquals(TurnState.CANCELLED, result.state());
            assertEquals("CANCELLED", current.query(identity).orElseThrow().state());
            assertEquals(1, model.requests.get(), "cancelled execution must not dispatch another Provider request");
            assertEquals(1, tool.executions.get(), "the in-flight tool must not be dispatched more than once");
        }
    }

    /** 取消与完成在真实提交边界竞争，先提交的一方获胜且只能留下一个持久终态。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellationAndCompletionShareOneTerminalFence(boolean completionWins) throws Exception {
        TerminalCommitGate gate = new TerminalCommitGate(sqlite(), completionWins, false);
        ScriptedModel model = new ScriptedModel("lookup", false);
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-terminal-race");
        try (AgentEngine engine = engine(model, new LookupTool(executions), gate.store())) {
            try {
                AgentExecutionHandle handle = engine.submit(AgentRequest.text(identity, "查询", "op-terminal-race"));
                assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
                if (completionWins) {
                    assertThrows(java.util.concurrent.ExecutionException.class, () ->
                            engine.cancel(identity, "cancel-terminal-race", "用户取消")
                                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
                } else {
                    engine.cancel(identity, "cancel-terminal-race", "用户取消")
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
                gate.release.countDown();
                TurnResult result = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                        handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS)).result();
                assertEquals(completionWins ? TurnState.COMPLETED : TurnState.CANCELLED, result.state());
                assertEquals(result.state().name(), engine.query(identity).orElseThrow().state());
                assertEquals(1, engine.readEvents(identity, 0, 1000).events().stream()
                        .filter(event -> event.eventType().equals("agent.execution.terminal")).count());
                assertEquals(1, executions.get());
                assertEquals(2, model.requests.get());
            } finally {
                gate.release.countDown();
            }
        }
    }

    /** Started 提交后、外部派发前取消必须结算为未执行，不能因持久意图存在而永远挂起。 */
    @Test
    void cancelsAfterStartedCommitWithoutDispatchingTheTool() throws Exception {
        AgentStore delegate = sqlite();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean gated = new AtomicBoolean();
        AgentStore store = (AgentStore) java.lang.reflect.Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    boolean selected = method.getName().equals("commit")
                            && ((AgentStore.ExecutionCommit) arguments[0]).records().stream()
                            .anyMatch(record -> record.recordType().equals("agent.tool.started"))
                            && gated.compareAndSet(false, true);
                    Object result;
                    try { result = method.invoke(delegate, arguments); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    if (selected) {
                        started.countDown();
                        assertTrue(release.await(15, TimeUnit.SECONDS), "started test gate was not released");
                    }
                    return result;
                });
        ScriptedModel model = new ScriptedModel("lookup", false);
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-cancel-before-dispatch");
        try (AgentEngine engine = engine(model, new LookupTool(executions), store)) {
            try {
                AgentExecutionHandle handle = engine.submit(AgentRequest.text(identity, "查询", "op-cancel-before-dispatch"));
                assertTrue(started.await(10, TimeUnit.SECONDS));
                engine.cancel(identity, "cancel-before-dispatch", "用户取消")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                release.countDown();
                TurnResult result = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                        handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS)).result();
                assertEquals(TurnState.CANCELLED, result.state());
                assertEquals("CANCELLED", engine.query(identity).orElseThrow().state());
                assertEquals(0, executions.get());
                assertEquals(1, model.requests.get());
            } finally {
                release.countDown();
            }
        }
    }

    /** NotApplied 重试的 Started 已提交后取消必须结算结果并释放核验租约，不能留下待恢复调用。 */
    @Test
    void cancelsNotAppliedRetryAfterStartedCommitWithoutRedispatchingTheTool() throws Exception {
        AgentStore delegate = sqlite();
        CountDownLatch retryStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean gated = new AtomicBoolean();
        AgentStore store = (AgentStore) java.lang.reflect.Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    boolean selected = method.getName().equals("commit")
                            && ((AgentStore.ExecutionCommit) arguments[0]).records().stream()
                                    .anyMatch(record -> record.recordType().equals("agent.tool.retry.intent"))
                            && ((AgentStore.ExecutionCommit) arguments[0]).records().stream()
                                    .anyMatch(record -> record.recordType().equals("agent.tool.started"))
                            && gated.compareAndSet(false, true);
                    Object result;
                    try { result = method.invoke(delegate, arguments); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    if (selected) {
                        retryStarted.countDown();
                        assertTrue(release.await(15, TimeUnit.SECONDS), "retry Started test gate was not released");
                    }
                    return result;
                });
        UnknownEffectModel model = new UnknownEffectModel();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger retryDispatches = new AtomicInteger();
        AtomicInteger settlementReleases = new AtomicInteger();
        RetryNotAppliedTool tool = new RetryNotAppliedTool(dispatches, retryDispatches, settlementReleases, false);
        AgentIdentity identity = identity("execution-cancel-not-applied-retry");
        try (AgentEngine engine = engine(model, tool, store)) {
            try {
                AgentExecutionOutcome initial = engine.submit(AgentRequest.text(
                                identity, "执行外部动作", "op-cancel-not-applied-retry"))
                        .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertInstanceOf(AgentExecutionOutcome.Suspended.class, initial,
                        "the first dispatched attempt must remain unknown until recovery");
                ToolRecoveryDecision pending = latestToolRecovery(store, identity);
                assertEquals(ToolRecoveryDecision.Status.PENDING, pending.status());
                engine.resolveToolRecovery(identity, "op-authorize-not-applied-retry", pending.recoveryId(),
                                pending.callId(), pending.revision(), RecoveryCommand.Decision.RETRY,
                                "authorize-not-applied-retry")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);

                AgentExecutionHandle resumed = engine.resume(identity);
                assertTrue(retryStarted.await(10, TimeUnit.SECONDS), "retry Started commit was not observed");
                assertEquals(0, settlementReleases.get(), "the NotApplied claim must survive until result commit");
                engine.cancel(identity, "cancel-not-applied-retry", "用户取消")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                release.countDown();

                TurnResult result = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                        resumed.completion().toCompletableFuture().get(20, TimeUnit.SECONDS)).result();
                assertEquals(TurnState.CANCELLED, result.state());
                assertEquals("CANCELLED", engine.query(identity).orElseThrow().state());
                assertEquals(1, dispatches.get(), "cancellation before the retry hook must not add a dispatch");
                assertEquals(0, retryDispatches.get());
                assertEquals(1, model.requests.get(), "a cancelled execution must not ask the model to continue");
                assertEquals(1, settlementReleases.get(), "the recovery claim must close after result commit");
            } finally {
                release.countDown();
            }
        }

        List<AgentStore.StoredRecord> records = executionRecords(store, identity);
        assertEquals(2, records.stream().filter(record -> record.recordType().equals("agent.tool.started")).count());
        ToolRecoveryDecision retried = latestToolRecovery(store, identity);
        assertEquals(ToolRecoveryDecision.Status.RETRIED, retried.status());
        assertEquals(ToolRecoveryDecision.Disposition.RETRY, retried.disposition());
        assertEquals(1, retried.attempt());
        AgentStore.StoredRecord resultRecord = records.stream()
                .filter(record -> record.recordId().equals(retried.toolResultRecordId())).findFirst().orElseThrow();
        assertEquals("agent.tool.result", resultRecord.recordType());
        ExecutionFacts.ToolResult result = (ExecutionFacts.ToolResult)
                new StorePayloadCodec(new ObjectMapper()).decode(resultRecord);
        assertEquals("CANCELLED", result.state().name());
        assertTrue(result.error());
    }

    /** 工具 Hook 已进入后即使抛 CancellationException 也必须保留未知结果，不能伪装成未派发取消。 */
    @Test
    void cancellationThrownInsideNotAppliedRetryHookRemainsUnknown() throws Exception {
        AgentStore store = sqlite();
        UnknownEffectModel model = new UnknownEffectModel();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger retryDispatches = new AtomicInteger();
        AtomicInteger settlementReleases = new AtomicInteger();
        RetryNotAppliedTool tool = new RetryNotAppliedTool(dispatches, retryDispatches, settlementReleases, true);
        AgentIdentity identity = identity("execution-hook-cancel-not-applied-retry");
        try (AgentEngine engine = engine(model, tool, store)) {
            AgentExecutionOutcome initial = engine.submit(AgentRequest.text(
                            identity, "执行外部动作", "op-hook-cancel-not-applied-retry"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertInstanceOf(AgentExecutionOutcome.Suspended.class, initial);
            ToolRecoveryDecision pending = latestToolRecovery(store, identity);
            engine.resolveToolRecovery(identity, "op-authorize-hook-cancel-retry", pending.recoveryId(),
                            pending.callId(), pending.revision(), RecoveryCommand.Decision.RETRY,
                            "authorize-hook-cancel-retry")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            AgentExecutionOutcome resumed = engine.resume(identity).completion()
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertInstanceOf(AgentExecutionOutcome.Suspended.class, resumed,
                    "a hook exception may follow an external effect and must remain recoverable");
            assertEquals("SUSPENDED", engine.query(identity).orElseThrow().state());
            assertEquals(2, dispatches.get());
            assertEquals(1, retryDispatches.get());
            assertEquals(1, model.requests.get());
            assertEquals(1, settlementReleases.get());
            ToolRecoveryDecision retryPending = latestToolRecovery(store, identity);
            assertEquals(ToolRecoveryDecision.Status.PENDING, retryPending.status());
            assertEquals(ToolRecoveryDecision.Disposition.RETRY, retryPending.disposition());
            assertEquals(1, retryPending.attempt());
            assertNull(retryPending.toolResultRecordId());
        }
    }

    /** 数据库已提交而应答丢失时按原 commit 身份核验，不能重写终态或重放工具副作用。 */
    @Test
    void reconcilesALostTerminalCommitResponse() throws Exception {
        TerminalCommitGate gate = new TerminalCommitGate(sqlite(), true, true);
        gate.release.countDown();
        ScriptedModel model = new ScriptedModel("lookup", false);
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-terminal-response");
        try (AgentEngine engine = engine(model, new LookupTool(executions), gate.store(),
                new CompletionFact("test-lost-terminal-response"))) {
            TurnResult result = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.submit(AgentRequest.text(identity, "查询", "op-terminal-response"))
                            .completion().toCompletableFuture().get(20, TimeUnit.SECONDS)).result();
            assertEquals(TurnState.COMPLETED, result.state());
            assertTrue(gate.triggered.get(), "the committed response must actually be lost");
            List<AgentStore.PersistedEvent> events = engine.readEvents(identity, 0, 1000).events();
            assertEquals(1, events.stream().filter(event -> event.eventType().equals("agent.execution.terminal")).count());
            assertEquals(result, assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.resume(identity).completion().toCompletableFuture().get(10, TimeUnit.SECONDS)).result());
            assertEquals(events, engine.readEvents(identity, 0, 1000).events());
            assertEquals(1, executions.get());
            assertEquals(2, model.requests.get());
            assertEquals(1, middlewareFactEnvelopes(gate.store(), identity).size(),
                    "retrying a lost response must not append the terminal fact twice");
        }
    }

    /** 运行中追加必须等当前模型或工具结算后消费，FOLLOW_UP 不能抢在 STEERING 续答前。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void consumesLiveInputsOnlyAfterSettlingTheActiveStep(boolean duringTool) throws Exception {
        QueueOrderModel model = new QueueOrderModel(duringTool);
        PausedTool tool = new PausedTool();
        AgentIdentity identity = identity("execution-live-input");
        try (AgentEngine engine = engine(model, tool, sqlite())) {
            try {
                AgentExecutionHandle execution = engine.submit(AgentRequest.text(identity, "original", "op-live-input"));
                assertTrue((duringTool ? tool.started : model.started).await(10, TimeUnit.SECONDS));
                for (AgentInputCommand.Kind kind : List.of(AgentInputCommand.Kind.STEERING, AgentInputCommand.Kind.FOLLOW_UP)) {
                    String label = kind == AgentInputCommand.Kind.STEERING ? "steering" : "follow-up";
                    engine.mutateInput(identity, new AgentInputCommand.Enqueue("op-" + label, "input-" + label, kind,
                            new UserContent(List.of(new TextContent(label))), Map.of()))
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
                assertEquals(2, engine.inspectInputQueue(identity).entries().size());
                tool.finish();
                model.finishFirst();
                AgentExecutionOutcome completed = execution.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(TurnState.COMPLETED, assertInstanceOf(AgentExecutionOutcome.Completed.class, completed).result().state());
                assertEquals(3, model.requests.get());
                assertEquals(duringTool ? 1 : 0, tool.executions.get());
                assertTrue(engine.inspectInputQueue(identity).entries().isEmpty());
            } finally {
                tool.finish();
                model.finishFirst();
            }
        }
    }

    /** 自动压缩必须真正提交 checkpoint 后继续；摘要失败不得偷偷发送未压缩历史。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void commitsAutomaticCompactionBeforeContinuingOrFailsWithoutFallback(boolean summaryFails) throws Exception {
        CompactionModel model = new CompactionModel(summaryFails);
        SQLiteAgentStore store = sqlite();
        try (AgentEngine engine = engine(model, new LookupTool(new AtomicInteger()), store, List.of(),
                new ContextBudget(6_000, 1_000, true), Optional.of(binding -> model))) {
            AgentExecutionOutcome first = engine.submit(AgentRequest.text(identity("execution-before-compaction"),
                    "ORIGINAL_USER_MARKER" + "a".repeat(2_000), "op-before-compaction"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, assertInstanceOf(AgentExecutionOutcome.Completed.class, first).result().state());
            AgentIdentity identity = identity("execution-auto-compaction");
            AgentExecutionOutcome second = engine.submit(AgentRequest.text(identity, "CURRENT_USER_MARKER" + "b".repeat(2_000), "op-auto-compaction"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            TurnResult result = assertInstanceOf(AgentExecutionOutcome.Completed.class, second).result();
            assertEquals(summaryFails ? TurnState.FAILED : TurnState.COMPLETED, result.state());
            assertEquals(1, model.summaries.get());
            assertEquals(summaryFails ? 1 : 2, model.requests.get());
            AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                    new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()),
                    identity.turnId());
            AgentStore.ExecutionSnapshot snapshot = store.loadExecution(key).orElseThrow();
            AgentStore.RecordPage records = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                    key, 0, snapshot.lastRecordSequence(), 1000));
            assertFalse(records.hasMore());
            assertEquals(summaryFails ? 0 : 1, records.records().stream()
                    .filter(record -> record.recordType().equals("agent.context.checkpoint")).count());
            assertEquals(summaryFails ? 0 : 1, engine.readEvents(identity, 0, 1000).events().stream()
                    .filter(event -> event.eventType().equals("agent.context.compaction.completed"))
                    .filter(event -> JsonValueCodecFactory.standard().encode(event.payload()).contains("COMPACTED"))
                    .count());
        }
    }

    /** 模型准备失败仍持久化正确分类，避免用户误查业务扩展。 */
    @Test
    void preservesModelPreparationFailureClassification() throws Exception {
        try (AgentEngine engine = engine(new PreparationFailureModel(), new AtomicInteger())) {
            AgentExecutionOutcome.Completed completed = assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.submit(AgentRequest.text(identity("execution-model-preparation"), "查询", "op-model-preparation"))
                            .completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            assertEquals(TurnState.FAILED, completed.result().state());
            assertEquals("MODEL_PROTOCOL_ERROR", completed.result().errorCode());
        }
    }

    /** 已成功受理的投递重试返回原回执，终态后的新投递必须失败且不能增加持久消息。 */
    @Test
    void rejectsNewInputAfterTerminalButReplaysAcceptedReceipt() throws Exception {
        AgentIdentity identity = identity("execution-terminal-input");
        try (AgentEngine engine = engine(new ScriptedModel("lookup", false), new AtomicInteger(), new CompletionWait())) {
            assertInstanceOf(AgentExecutionOutcome.Suspended.class,
                    engine.submit(AgentRequest.text(identity, "查询后等待", "op-terminal-input"))
                            .completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            AgentInputCommand.Enqueue command = new AgentInputCommand.Enqueue("accepted-message", "accepted-input", AgentInputCommand.Kind.MESSAGE,
                    new UserContent(List.of(new TextContent("等待期间的消息"))), Map.of());
            AgentInputMutationReceipt accepted = engine.mutateInput(identity, command).toCompletableFuture().get(10, TimeUnit.SECONDS);
            engine.cancel(identity, "finish-before-next-input", "用户取消").toCompletableFuture().get(10, TimeUnit.SECONDS);
            AgentExecutionStatus terminal = engine.query(identity).orElseThrow();
            assertEquals("CANCELLED", terminal.state());
            assertEquals(accepted, engine.mutateInput(identity, command).toCompletableFuture().get(10, TimeUnit.SECONDS));
            AgentInputCommand.Enqueue rejected = new AgentInputCommand.Enqueue("new-message", "new-input", AgentInputCommand.Kind.MESSAGE,
                    new UserContent(List.of(new TextContent("终态后的消息"))), Map.of());
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> engine.mutateInput(identity, rejected).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertInstanceOf(AgentStore.StoreConflictException.class, failure.getCause());
            assertEquals(terminal, engine.query(identity).orElseThrow());
        }
    }

    /** 等待已释放执行者后取消仍应直接完成，不能要求用户再次恢复任务才能撤销等待。 */
    @Test
    void cancelsAnIdleWaitWithoutResumingTheModel() throws Exception {
        ScriptedModel model = new ScriptedModel("lookup", false);
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-cancel-wait");
        try (AgentEngine engine = engine(model, executions, new CompletionWait())) {
            AgentExecutionOutcome waiting = engine.submit(AgentRequest.text(identity, "查询后确认", "op-cancel-wait"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            String interactionId = assertInstanceOf(AgentExecutionOutcome.Suspended.class, waiting).interactionId().orElseThrow();
            engine.cancel(identity, "cancel-wait", "用户取消").toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals("CANCELLED", engine.query(identity).orElseThrow().state());
            assertThrows(Exception.class, () -> engine.answer(identity,
                    new AgentAnswer(interactionId, "late-answer", 0,
                            List.of(new InteractionAnswer("question_confirm", List.of(), "确认", false)), Map.of()))
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(2, model.requests.get());
            assertEquals(1, executions.get());
        }
    }

    /** 回答事务失败不能在内存中提前消费问题；同一回答身份重试成功后才允许恢复。 */
    @Test
    void failedAnswerRemainsPendingAndRetryable() throws Exception {
        AgentStore store = failingOnce(sqlite(), AgentStore.ControlKind.WAIT_SETTLEMENT);
        AgentIdentity identity = identity("execution-answer-failure");
        try (AgentEngine engine = engine(new ScriptedModel("lookup", false),
                new LookupTool(new AtomicInteger()), store, new CompletionWait())) {
            AgentExecutionOutcome waiting = engine.submit(AgentRequest.text(identity, "确认", "op-answer-failure"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            String interactionId = assertInstanceOf(AgentExecutionOutcome.Suspended.class, waiting).interactionId().orElseThrow();
            AgentAnswer answer = new AgentAnswer(interactionId, "answer-retry", 0,
                    List.of(new InteractionAnswer("question_confirm", List.of(), "确认", false)), Map.of());
            assertThrows(Exception.class, () -> engine.answer(identity, answer).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals("WAITING_INPUT", engine.query(identity).orElseThrow().state());
            engine.answer(identity, answer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, assertInstanceOf(AgentExecutionOutcome.Completed.class,
                    engine.resume(identity).completion().toCompletableFuture().get(20, TimeUnit.SECONDS)).result().state());
        }
    }

    /**
     * 恢复时状态提交与持久暂停意图竞争，只重读无副作用的启动 CAS，不越过暂停边界派发模型或工具。
     *
     * @author kongweiguang
     */
    @Test
    void convergesAStartupStateConflictToTheDurablePauseRequest() throws Exception {
        ScriptedModel model = new ScriptedModel("lookup", false);
        AtomicInteger executions = new AtomicInteger();
        AgentIdentity identity = identity("execution-resume-pause-race");
        SQLiteAgentStore durableStore = sqlite();
        CountDownLatch runningCommitEntered = new CountDownLatch(1);
        CountDownLatch suspendRequestCommitted = new CountDownLatch(1);
        CountDownLatch releaseRunningCommit = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean();
        AtomicReference<AgentStore.ExecutionCommit> staleRunningCommit = new AtomicReference<>();
        AtomicReference<Throwable> staleRunningFailure = new AtomicReference<>();
        AgentStore gatedStore = (AgentStore) java.lang.reflect.Proxy.newProxyInstance(
                AgentStore.class.getClassLoader(), new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("commit") && arguments[0] instanceof AgentStore.ExecutionCommit commit
                        && commit.expectedState().equals("QUEUED") && commit.state().equals("RUNNING")
                        && armed.compareAndSet(true, false)) {
                        staleRunningCommit.set(commit);
                        runningCommitEntered.countDown();
                        if (!releaseRunningCommit.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("startup state commit gate was not released");
                        }
                        try {
                            return method.invoke(durableStore, arguments);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            staleRunningFailure.set(failure.getCause());
                            throw failure.getCause();
                        }
                    }
                    Object result;
                    try {
                        result = method.invoke(durableStore, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (method.getName().equals("controlCommit")
                        && ((AgentStore.ControlCommit) arguments[0]).kind() == AgentStore.ControlKind.SUSPEND_REQUEST) {
                        suspendRequestCommitted.countDown();
                    }
                    return result;
                });

        try (AgentEngine engine = engine(model, new LookupTool(executions), gatedStore, new CompletionWait())) {
            try {
                AgentExecutionOutcome waiting = engine.submit(AgentRequest.text(identity,
                        "在本地完成一次查询并等待确认", "op-resume-pause-race"))
                        .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                String interactionId = assertInstanceOf(AgentExecutionOutcome.Suspended.class, waiting)
                        .interactionId().orElseThrow();
                engine.answer(identity, new AgentAnswer(interactionId, "answer-resume-pause-race", 0,
                        List.of(new InteractionAnswer("question_confirm", List.of(), "确认", false)), Map.of()))
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                int providerRequestsBeforeResume = model.requests.get();
                int toolExecutionsBeforeResume = executions.get();
                assertEquals(2, providerRequestsBeforeResume);
                assertEquals(1, toolExecutionsBeforeResume);

                armed.set(true);
                AgentExecutionHandle resumed = engine.resume(identity);
                assertTrue(runningCommitEntered.await(10, TimeUnit.SECONDS),
                        "resume must reach its real QUEUED-to-RUNNING SQLite commit");
                CompletableFuture<CompletionStage<Void>> pauseRequest = CompletableFuture.supplyAsync(() ->
                        engine.suspend(identity, "suspend-resume-pause-race", "PLAN_PAUSED"));
                CompletionStage<Void> pauseCompletion;
                try {
                    assertTrue(suspendRequestCommitted.await(10, TimeUnit.SECONDS),
                            "the standard suspend path must commit its durable request before releasing the stale CAS");
                    pauseCompletion = pauseRequest.get(10, TimeUnit.SECONDS);
                    assertFalse(pauseCompletion.toCompletableFuture().isDone(),
                            "suspend must have published cancellation and be waiting for the blocked owner to settle");
                } finally {
                    releaseRunningCommit.countDown();
                }

                AgentExecutionOutcome paused = resumed.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertInstanceOf(AgentExecutionOutcome.Suspended.class, paused);
                pauseCompletion.toCompletableFuture().get(20, TimeUnit.SECONDS);
                AgentStore.ExecutionSnapshot suspended = durableStore.loadExecution(new AgentStore.ExecutionKey(
                        new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()),
                        identity.turnId())).orElseThrow();
                AgentStore.ExecutionCommit attempted = staleRunningCommit.get();
                assertEquals("QUEUED", attempted.expectedState());
                assertEquals("RUNNING", attempted.state());
                assertInstanceOf(AgentStore.StoreConflictException.class, staleRunningFailure.get());
                assertEquals("SUSPENDED", suspended.state());
                assertEquals(attempted.cursor(), suspended.cursor(), "pause must preserve the current execution cursor");
                assertTrue(durableStore.loadConversation(suspended.key().conversation()).orElseThrow().revision()
                        > attempted.expectedThreadRevision(),
                        "the durable pause request must invalidate the stale revision");

                List<AgentStore.StoredRecord> suspendRequests = executionRecords(durableStore, identity).stream()
                        .filter(record -> record.recordType().equals(ExecutionControlCodec.SUSPEND_REQUEST_RECORD_TYPE))
                        .toList();
                assertEquals(1, suspendRequests.size(), "one accepted operation must persist one pause request");
                assertEquals("PLAN_PAUSED", new ControlPayloadCodec(new ObjectMapper())
                        .decodeSuspendRequest(suspendRequests.getFirst()).reasonCode());
                assertEquals(providerRequestsBeforeResume, model.requests.get(),
                        "a conflicted startup state transition must not redispatch the Provider");
                assertEquals(toolExecutionsBeforeResume, executions.get(),
                        "a conflicted startup state transition must not replay the Tool");

                AgentExecutionOutcome completed = engine.resume(identity).completion().toCompletableFuture()
                        .get(20, TimeUnit.SECONDS);
                assertEquals(TurnState.COMPLETED,
                        assertInstanceOf(AgentExecutionOutcome.Completed.class, completed).result().state());
                assertEquals(providerRequestsBeforeResume, model.requests.get());
                assertEquals(toolExecutionsBeforeResume, executions.get());
            } finally {
                releaseRunningCommit.countDown();
            }
        }
    }

    /** 取消请求未持久化时等待仍可用；重试取消必须成功关闭等待，不依赖旧进程内 Future。 */
    @Test
    void failedCancellationRemainsRetryable() throws Exception {
        AgentStore store = failingOnce(sqlite(), AgentStore.ControlKind.CANCEL_REQUEST);
        AgentIdentity identity = identity("execution-cancel-failure");
        try (AgentEngine engine = engine(new ScriptedModel("lookup", false),
                new LookupTool(new AtomicInteger()), store, new CompletionWait())) {
            assertInstanceOf(AgentExecutionOutcome.Suspended.class,
                    engine.submit(AgentRequest.text(identity, "确认", "op-cancel-failure"))
                            .completion().toCompletableFuture().get(20, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> engine.cancel(identity, "cancel-retry", "用户取消")
                    .toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals("WAITING_INPUT", engine.query(identity).orElseThrow().state());
            engine.cancel(identity, "cancel-retry", "用户取消").toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals("CANCELLED", engine.query(identity).orElseThrow().state());
        }
    }

    /** 中间件可收紧原本无需审批的工具，拒绝后仍必须返回配对失败，重复回答不能重复派发。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enforcesPersistedApprovalBeforeDispatch(boolean approved) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel("lookup", !approved);
        AgentIdentity identity = identity("execution-approval");
        try (AgentEngine engine = engine(model, executions, new ApprovalWait())) {
            AgentExecutionOutcome waiting = engine.submit(AgentRequest.text(identity, "审批后查询", "op-approval"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            String interactionId = assertInstanceOf(AgentExecutionOutcome.Suspended.class, waiting).interactionId().orElseThrow();
            assertEquals("WAITING_APPROVAL", engine.query(identity).orElseThrow().state());
            assertEquals(0, executions.get());
            AgentAnswer answer = new AgentAnswer(interactionId, "answer-approval", 0,
                    List.of(new InteractionAnswer("question_approval",
                            List.of(approved ? "option_approve" : "option_deny"), null, false)), Map.of());
            engine.answer(identity, answer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            engine.answer(identity, answer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            AgentExecutionOutcome completed = engine.resume(identity).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED, assertInstanceOf(AgentExecutionOutcome.Completed.class, completed).result().state());
            assertEquals(approved ? 1 : 0, executions.get());
            assertEquals(2, model.requests.get());
        }
    }

    /** 到期的授权不能因重启或迟到回答复活；过期关闭后显式恢复只把失败交回模型。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void expiresApprovalWithoutInventingAUserDecision(boolean restart) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel("lookup", true);
        AgentIdentity identity = identity("execution-expired");
        AgentEngine first = engine(model, executions, new ApprovalWait(Duration.ofSeconds(2)));
        try {
            AgentExecutionOutcome waiting = first.submit(AgentRequest.text(identity, "等待授权", "op-expired"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            String interactionId = assertInstanceOf(AgentExecutionOutcome.Suspended.class, waiting).interactionId().orElseThrow();
            if (restart) {
                first.close();
            }
            Thread.sleep(Duration.ofSeconds(3));
            AgentEngine current = restart ? engine(model, executions, new ApprovalWait()) : first;
            try {
                AgentAnswer lateAnswer = new AgentAnswer(interactionId, "answer-expired", 0,
                        List.of(new InteractionAnswer("question_approval", List.of("option_approve"), null, false)), Map.of());
                assertThrows(Exception.class, () -> current.answer(identity, lateAnswer)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS));
                assertEquals("SUSPENDED", current.query(identity).orElseThrow().state());
                AgentExecutionOutcome completed = current.resume(identity).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(TurnState.COMPLETED,
                        assertInstanceOf(AgentExecutionOutcome.Completed.class, completed).result().state());
                assertTrue(model.result.content().contains("approval expired"), model.result.content());
                assertEquals(0, executions.get());
                assertEquals(2, model.requests.get());
                assertExpiredApprovalFacts(current, identity);
            } finally {
                if (restart) {
                    current.close();
                }
            }
        } finally {
            first.close();
        }
    }

    /** 冷恢复只接管持久 Wait 的截止时间；自然到期与已过期重扫都不得启动 Driver 或重复关闭事件。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoresApprovalExpiryWithoutResumingExecution(boolean alreadyExpiredAtRestore) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ScriptedModel model = new ScriptedModel("lookup", true);
        AgentIdentity identity = identity(alreadyExpiredAtRestore
                ? "execution-restore-expired" : "execution-restore-future");
        AgentEngine first = engine(model, executions, new ApprovalWait(Duration.ofSeconds(5)));
        try {
            AgentExecutionOutcome initial = first.submit(AgentRequest.text(identity, "等待授权", "op-restore-expiry"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertInstanceOf(AgentExecutionOutcome.Suspended.class, initial);
            assertEquals("WAITING_APPROVAL", first.query(identity).orElseThrow().state());
            int modelRequestsAtWait = model.requests.get();
            first.close();

            if (alreadyExpiredAtRestore) {
                Thread.sleep(Duration.ofSeconds(6));
            }

            AgentEngine restored = engine(model, executions, new ApprovalWait());
            try {
                restored.restoreApprovalExpiry(identity);
                restored.restoreApprovalExpiry(identity);
                if (!alreadyExpiredAtRestore) {
                    awaitExecutionState(restored, identity, "SUSPENDED", Duration.ofSeconds(10));
                }

                assertEquals("SUSPENDED", restored.query(identity).orElseThrow().state());
                assertEquals(modelRequestsAtWait, model.requests.get(),
                        "expiry restoration must not resolve a Driver or issue another Provider request");
                assertEquals(0, executions.get(), "expiry restoration must never dispatch the protected Tool");
                assertSingleExpiredApproval(identity);

                long closeEvents = resolvedWaitEventCount(restored, identity);
                assertEquals(1, closeEvents, "repeated restoration must not commit a second expiry event");
                restored.restoreApprovalExpiry(identity);
                assertEquals(closeEvents, resolvedWaitEventCount(restored, identity));
            } finally {
                restored.close();
            }
        } finally {
            first.close();
        }
    }

    /** 只轮询只读状态投影，确认真实调度器结算且不以显式 resume 推进执行。 */
    private static void awaitExecutionState(AgentEngine engine, AgentIdentity identity,
                                            String state, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (engine.query(identity).map(status -> status.state().equals(state)).orElse(false)) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(state, engine.query(identity).orElseThrow().state(), "execution did not reach expected state");
    }

    /** 对真实 SQLite 事实确认自然 expiry 留空 decision/answer，且持久等待只关闭一次。 */
    private void assertSingleExpiredApproval(AgentIdentity identity) {
        try (SQLiteAgentStore store = sqlite()) {
            List<AgentStore.StoredRecord> records = executionRecords(store, identity);
            StorePayloadCodec payloads = new StorePayloadCodec(new ObjectMapper());
            List<ExecutionFacts.Approval> approvals = records.stream()
                    .filter(record -> record.recordType().equals("agent.approval"))
                    .map(payloads::decode).map(ExecutionFacts.Approval.class::cast).toList();
            assertEquals(1, approvals.size());
            assertNull(approvals.getFirst().decision());

            ControlPayloadCodec controls = new ControlPayloadCodec(new ObjectMapper());
            List<InteractionWait> expired = records.stream()
                    .filter(record -> record.recordType().equals("agent.interaction.wait"))
                    .map(controls::decodeWait).filter(wait -> wait.status() == InteractionStatus.EXPIRED).toList();
            assertEquals(1, expired.size());
            assertTrue(expired.getFirst().answers().isEmpty());
        }
    }

    /** 从真实事件 codec 统计等待关闭提交，防止重复恢复伪造第二份控制事件。 */
    private long resolvedWaitEventCount(AgentEngine engine, AgentIdentity identity) {
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        return engine.readEvents(identity, 0, 1_000).events().stream()
                .map(codec::decode)
                .filter(io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent.ControlRecorded.class::isInstance)
                .map(io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent.ControlRecorded.class::cast)
                .filter(event -> event.action()
                        == io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent.ControlAction.WAIT_RESOLVED)
                .count();
    }

    /** 直接核对真实 SQLite 事实，过期是系统关闭而非用户拒绝，恢复也不能重复生成结果。 */
    private void assertExpiredApprovalFacts(AgentEngine engine, AgentIdentity identity) throws Exception {
        SQLiteAgentStore store = sqlite();
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()),
                identity.turnId());
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(key).orElseThrow();
        AgentStore.RecordPage records = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                key, 0, snapshot.lastRecordSequence(), 1000));
        assertFalse(records.hasMore());
        StorePayloadCodec payloads = new StorePayloadCodec(new ObjectMapper());
        List<ExecutionFacts.Approval> approvals = records.records().stream().filter(record -> record.recordType().equals("agent.approval"))
                .map(payloads::decode).map(ExecutionFacts.Approval.class::cast).toList();
        assertEquals(1, approvals.size());
        assertNull(approvals.getFirst().decision());
        ControlPayloadCodec controls = new ControlPayloadCodec(new ObjectMapper());
        List<InteractionWait> expired = records.records().stream().filter(record -> record.recordType().equals("agent.interaction.wait"))
                .map(controls::decodeWait).filter(wait -> wait.status() == InteractionStatus.EXPIRED).toList();
        assertEquals(1, expired.size());
        assertTrue(expired.getFirst().answers().isEmpty());
        for (String type : List.of("agent.tool.result", "agent.tool.message")) {
            assertEquals(1, records.records().stream().filter(record -> record.recordType().equals(type)).count(), type);
        }
        List<AgentStore.PersistedEvent> events = engine.readEvents(identity, 0, 1000).events();
        assertEquals(1, events.stream().filter(event -> event.eventType().equals("agent.tool.completed")).count());
        AgentStore.PersistedEvent completed = events.stream().filter(event -> event.eventType().equals("agent.tool.completed")).findFirst().orElseThrow();
        assertEquals("TOOL_APPROVAL_EXPIRED", new ObjectMapper().readTree(
                JsonValueCodecFactory.standard().encode(completed.payload())).path("errorCode").asText());
        engine.resume(identity).completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        assertEquals(snapshot, store.loadExecution(key).orElseThrow());
        assertEquals(events, engine.readEvents(identity, 0, 1000).events());
    }

    /** 取消和扩展失败都必须补齐整批原生调用结果，否则后续 Turn 会带着无结果调用请求供应商。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void pairsUnstartedCallsBeforeTerminating(boolean cancelled) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        BatchModel model = new BatchModel();
        try (AgentEngine engine = engine(model, executions, new StopBeforeTool(cancelled))) {
            AgentExecutionOutcome first = engine.submit(AgentRequest.text(identity("execution-batch"), "查询两项", "op-batch"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(cancelled ? TurnState.CANCELLED : TurnState.FAILED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, first).result().state());
            AgentExecutionOutcome second = engine.submit(AgentRequest.text(identity("execution-after-batch"), "继续", "op-after-batch"))
                    .completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(TurnState.COMPLETED,
                    assertInstanceOf(AgentExecutionOutcome.Completed.class, second).result().state());
            assertEquals(0, executions.get());
            assertEquals(2, model.requests.get());
        }
    }

    /** 固定执行水位读取实际扩展事实，验证提交边界而不依赖中间件对象的内存观察值。 */
    private List<com.fasterxml.jackson.databind.JsonNode> middlewareFactEnvelopes(
            AgentStore store, AgentIdentity identity) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<com.fasterxml.jackson.databind.JsonNode> envelopes = new ArrayList<>();
        for (AgentStore.StoredRecord record : executionRecords(store, identity)) {
            if (ExtensionFactRecordCodec.RECORD_TYPE.equals(record.recordType())) {
                envelopes.add(mapper.readTree(JsonValueCodecFactory.standard().encode(record.payload())));
            }
        }
        return List.copyOf(envelopes);
    }

    /** 只读取指定执行的当前有序记录，避免其他会话或执行的事实影响断言。 */
    private List<AgentStore.StoredRecord> executionRecords(AgentStore store, AgentIdentity identity) {
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()),
                identity.turnId());
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(key).orElseThrow();
        AgentStore.RecordPage page = store.readRecords(
                AgentStore.RecordQuery.withDefaultMaxBytes(key, 0, snapshot.lastRecordSequence(), 1_000));
        assertFalse(page.hasMore(), "test execution records exceed the bounded read");
        return page.records();
    }

    /** Provider 用量结算保留 UNKNOWN 派发估算，并以独立记录追加最终已知值。 */
    private void assertFinalUsageFacts(AgentIdentity identity, List<AgentStore.StoredRecord> records) {
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()),
                identity.turnId());
        StorePayloadCodec payloads = new StorePayloadCodec(new ObjectMapper());
        List<UsageRecord> usageRecords = records.stream()
                .filter(record -> record.recordType().equals("agent.usage"))
                .map(record -> new UsageRecord(record, payloads.decode(record)))
                .filter(value -> value.fact() instanceof Usage)
                .toList();
        List<UsageRecord> estimates = usageRecords.stream()
                .filter(value -> ((Usage) value.fact()).certainty() == UsageCertainty.UNKNOWN)
                .filter(value -> ((Usage) value.fact()).estimatedContextTokens() != null)
                .toList();
        List<UsageRecord> settlements = usageRecords.stream()
                .filter(value -> ((Usage) value.fact()).certainty() == UsageCertainty.KNOWN)
                .toList();

        assertEquals(2, estimates.size());
        assertEquals(2, settlements.size());
        for (UsageRecord estimateRecord : estimates) {
            Usage estimate = (Usage) estimateRecord.fact();
            String stableId = AgentRecordIds.derive(key, "agent.usage", "estimate:" + estimate.modelRequestId());
            assertEquals(stableId, estimateRecord.record().recordId());
            assertTrue(estimate.estimatedContextTokens() > 0);
            assertTrue(settlements.stream().anyMatch(settlement ->
                    ((Usage) settlement.fact()).modelRequestId().equals(estimate.modelRequestId())));
        }
    }

    /** 仅观察真实 SQLite 成功接收的估算提交，所有读取和事务仍委托给生产 Store。 */
    private static AgentStore observeUsageDispatchCommits(AgentStore delegate,
                                                          List<AgentStore.ExecutionCommit> observed) {
        return (AgentStore) java.lang.reflect.Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (method.getName().equals("commit") && arguments != null
                            && arguments.length >= 1 && arguments[0] instanceof AgentStore.ExecutionCommit commit
                            && commit.events().stream().anyMatch(event ->
                                    event.eventType().equals("agent.context.usage.estimated"))) {
                        observed.add(commit);
                    }
                    return result;
                });
    }

    /** 按固定执行键解码提交水位，确认 Provider 观察到的估算记录及事件已经落入 SQLite。 */
    private static void assertUsageDispatchVisible(SQLiteAgentStore store, AgentIdentity identity,
                                                   List<AgentStore.ExecutionCommit> observed, int ordinal,
                                                   StorePayloadCodec payloads) {
        assertEquals(ordinal, observed.size(), "each Provider call must follow one successful usage commit");
        AgentStore.ExecutionCommit commit = observed.get(ordinal - 1);
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()),
                identity.turnId());
        assertEquals(key, commit.execution());
        AgentStore.EventWrite estimatedEvent = commit.events().stream()
                .filter(event -> event.eventType().equals("agent.context.usage.estimated"))
                .findFirst().orElseThrow();
        String usageRecordId = usageRecordId(JsonValueCodecFactory.standard().encode(estimatedEvent.payload()));
        AgentStore.RecordWrite usageWrite = commit.records().stream()
                .filter(record -> record.recordId().equals(usageRecordId))
                .findFirst().orElseThrow();
        assertEquals("agent.usage", usageWrite.recordType());

        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(key).orElseThrow();
        assertEquals(TurnState.RUNNING.name(), snapshot.state());
        AgentStore.StoredRecord persistedUsage = store.readRecord(key, usageRecordId).orElseThrow();
        Usage usage = assertInstanceOf(Usage.class, payloads.decode(persistedUsage));
        assertEquals(UsageCertainty.UNKNOWN, usage.certainty());
        assertTrue(usage.estimatedContextTokens() != null && usage.estimatedContextTokens() > 0);
        assertEquals(AgentRecordIds.derive(key, "agent.usage", "estimate:" + usage.modelRequestId()), usageRecordId);

        AgentStore.EventPage events = store.readEvents(AgentStore.EventQuery.latestWithDefaultMaxBytes(
                key.conversation(), 0, 1000));
        AgentStore.PersistedEvent persistedEvent = events.events().stream()
                .filter(event -> event.eventId().equals(estimatedEvent.eventId()))
                .findFirst().orElseThrow();
        assertEquals(estimatedEvent.eventType(), persistedEvent.eventType());
        assertEquals(usageRecordId, usageRecordId(JsonValueCodecFactory.standard().encode(persistedEvent.payload())));
        assertTrue(persistedEvent.lastRecordSequence() >= persistedUsage.sequence());
    }

    /** 解析严格事件 JSON，避免用文本包含关系掩盖 Usage 与 Event 的身份错配。 */
    private static String usageRecordId(String payloadJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().readTree(payloadJson);
            String usageRecordId = payload.path("usageRecordId").textValue();
            if (usageRecordId == null || usageRecordId.isBlank()) {
                throw new AssertionError("context usage event has no usageRecordId");
            }
            return usageRecordId;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError("context usage event payload is invalid", failure);
        }
    }

    /** 将 Store 记录及严格解码后的领域事实并列保存，断言不会误用 JSON 文本推断状态。
     *
     * @param record 持久化的执行记录，提供可回放的记录序列与原始身份
     * @param fact 从记录解码得到的领域事实，供业务断言使用
     */
    private record UsageRecord(AgentStore.StoredRecord record,
                               ExecutionFact fact) {
    }

    /** 读取最新持久恢复修订，供重启后的显式裁决使用而不派生或猜测 recovery 身份。 */
    private ToolRecoveryDecision latestToolRecovery(AgentStore store, AgentIdentity identity) {
        ControlPayloadCodec codec = new ControlPayloadCodec(new ObjectMapper());
        ToolRecoveryDecision latest = null;
        for (AgentStore.StoredRecord record : executionRecords(store, identity)) {
            if (!record.recordType().equals(ExecutionControlCodec.TOOL_RECOVERY_RECORD_TYPE)) {
                continue;
            }
            ToolRecoveryDecision decision = codec.decodeToolRecoveryDecision(record);
            if (latest == null || decision.revision() > latest.revision()) {
                latest = decision;
            }
        }
        if (latest == null) {
            throw new AssertionError("execution has no durable Tool recovery decision");
        }
        return latest;
    }

    /** 各测试使用独立 SQLite 文件，并通过公开 Builder 显式注入受控模型及业务工具。 */
    private AgentEngine engine(ModelPort model, AtomicInteger executions, AgentMiddleware... middleware) {
        return engine(model, new LookupTool(executions), sqlite(), middleware);
    }

    /** 测试先显式创建 Core schema，构造器继续只验证宿主已初始化的数据库。 */
    private SQLiteAgentStore sqlite() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("loop.sqlite"));
        SQLiteAgentStore.bootstrap(dataSource);
        return new SQLiteAgentStore(dataSource);
    }

    /** 允许在真实存储边界注入一次确定故障，模型、工具及恢复代码保持生产实现。 */
    private AgentEngine engine(ModelPort model, AgentTool tool, AgentStore store, AgentMiddleware... middleware) {
        return engine(model, tool, store, List.of(), middleware);
    }

    /** 自定义 Skill 来源由测试显式注册，普通无 Skill 的接入仍沿用空集合。 */
    private AgentEngine engine(ModelPort model, AgentTool tool, AgentStore store, List<SkillSource> sources,
                               AgentMiddleware... middleware) {
        return engine(model, tool, store, sources, new ContextBudget(32_768, 4_096, true), Optional.empty(), middleware);
    }

    /** 压缩测试显式绑定同一供应商的摘要能力与小窗口，不绕过真实 Context/Store 编排。 */
    private AgentEngine engine(ModelPort model, AgentTool tool, AgentStore store, List<SkillSource> sources,
                               ContextBudget budget, Optional<SummaryModel.Factory> summaries, AgentMiddleware... middleware) {
        return new AgentEngineBuilder().store(store)
                .model(new ModelPort.ModelConfiguration("test-provider", "test-model", "cfg_test",
                        ModelPort.Api.OPENAI_RESPONSES, "test-model", URI.create("https://example.invalid/v1"),
                        "test-key", Duration.ofSeconds(2), Duration.ofSeconds(10),
                        Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults()))
                .contextBudget(budget)
                .extensions(registry -> {
                    if (summaries.isPresent()) {
                        registry.registerModelProvider("test-provider", model, summaries.orElseThrow());
                    } else {
                        registry.registerModelProvider("test-provider", model);
                    }
                    registry.registerTool(tool);
                    sources.forEach(registry::registerSkillSource);
                    for (AgentMiddleware extension : middleware) {
                        registry.registerMiddleware(extension);
                    }
                }).build();
    }

    /** 只在选定控制事务进入数据库前失败一次，其余接口透传真实 SQLite，不用内存 Store 代替事务。 */
    private static AgentStore failingOnce(AgentStore delegate, AgentStore.ControlKind kind) {
        AtomicBoolean failed = new AtomicBoolean();
        return (AgentStore) java.lang.reflect.Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("controlCommit")
                            && ((AgentStore.ControlCommit) arguments[0]).kind() == kind
                            && failed.compareAndSet(false, true)) {
                        throw new AgentStore.StoreUnavailableException("injected control persistence failure", null);
                    }
                    try { return method.invoke(delegate, arguments); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    /** 只在真实 SQLite 完成提交的前后暂停或丢弃响应，业务执行和 CAS 都保持生产实现。 */
    private static final class TerminalCommitGate {
        private final AgentStore delegate;
        private final boolean afterCommit;
        private final boolean loseResponse;
        private final AtomicBoolean triggered = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        /** 明确指定故障发生在事务提交前还是后，避免测试靠线程调度概率得出结论。 */
        private TerminalCommitGate(AgentStore delegate, boolean afterCommit, boolean loseResponse) {
            this.delegate = delegate;
            this.afterCommit = afterCommit;
            this.loseResponse = loseResponse;
        }

        /** 所有读写仍透传同一个数据库，只有首次自然终态提交被注入确定的并发窗口。 */
        private AgentStore store() {
            return (AgentStore) java.lang.reflect.Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                    new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                        boolean selected = method.getName().equals("commit")
                                && ((AgentStore.ExecutionCommit) arguments[0]).state().equals("COMPLETED")
                                && triggered.compareAndSet(false, true);
                        if (selected && !afterCommit) {
                            awaitRelease();
                        }
                        Object result;
                        try { result = method.invoke(delegate, arguments); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        if (selected && afterCommit) {
                            awaitRelease();
                        }
                        if (selected && loseResponse) {
                            throw new AgentStore.StoreUnavailableException("injected lost commit response", null);
                        }
                        return result;
                    });
        }

        /** 有界等待防止回归失败挂住整套测试，释放本身由测试 finally 保证。 */
        private void awaitRelease() throws InterruptedException {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS), "terminal test gate was not released");
        }
    }

    /** 使用不带 Ja 前缀的业务身份，验证接入不依赖工作区或 Turn 专属标识。 */
    private static AgentIdentity identity(String turnId) {
        return new AgentIdentity("tenant", "thread", turnId, "user");
    }

    /** 使用非 Ja 本地枚举的来源标识，模拟从业务数据库按租户读取纯文本 Skill。 */
    private static final class FixedSkillSource implements SkillSource {
        /** 来源身份保持稳定，不能靠文件路径参与匹配。 */
        @Override public String id() { return "business.docs"; }

        /** 唯一来源无需覆盖其它来源。 */
        @Override public int priority() { return 1; }

        /** 一页元数据即可定位测试包，正文仍在选择后按需读取。 */
        @Override public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
            return new SkillPage(List.of(new SkillDescriptor("guide", "业务处理指导", id(), 1)), null);
        }

        /** 检查租户传递并返回可辨识正文，防止只验证到目录标签而没有实际激活。 */
        @Override public SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor, SkillReadRequest request) {
            assertEquals("tenant", context.tenantId());
            return new SkillResourceContent("GUIDANCE_KEEP_ALIVE", false);
        }
    }

    /** 直接检查实际模型信封，避免仅通过内存 Skill 列表断言掩盖 Prompt 装配错误。 */
    private static final class SkillAwareModel implements ModelPort {
        private final List<String> expectedMessages;
        private final AtomicInteger requests = new AtomicInteger();

        /** 每个场景显式列出必须进入模型历史的正文。 */
        private SkillAwareModel(List<String> expectedMessages) { this.expectedMessages = List.copyOf(expectedMessages); }

        /** 固定估计避免这个来源测试触发额外的摘要模型调用。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "c".repeat(64));
        }

        /** 选中 Skill 正文与消息都必须存在于真实请求，再给出无工具的自然完成。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink, CancellationToken cancellation) {
            requests.incrementAndGet();
            assertTrue(request.prompt().systemPrompt().contains("GUIDANCE_KEEP_ALIVE"));
            List<String> text = request.messages().stream().flatMap(message -> message.content().stream())
                    .filter(TextContent.class::isInstance).map(TextContent.class::cast).map(TextContent::text).toList();
            for (String expected : expectedMessages) {
                assertTrue(text.stream().anyMatch(value -> value.contains(expected)), expected);
            }
            sink.onEvent(new TextDelta("已按指导处理")).toCompletableFuture().join();
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null, new ModelUsage(100, 10, 110)));
        }
    }

    /** 模型和工具安全点共用同一历史顺序断言，不能用本地队列替代持久消费。 */
    private static final class QueueOrderModel implements ModelPort {
        private final boolean duringTool;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<ModelOutcome> first = new CompletableFuture<>();
        private final AtomicInteger requests = new AtomicInteger();

        /** 显式选择暂停位置，避免靠 sleep 猜测输入与副作用的交错时序。 */
        private QueueOrderModel(boolean duringTool) { this.duringTool = duringTool; }

        /** 队列测试使用充足窗口，避免压缩改变检查的消息序列。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "f".repeat(64));
        }

        /** 在实际第二、第三次模型请求核对已结算事实和输入顺序，保持工具结果在追加输入之前。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink, CancellationToken cancellation) {
            int round = requests.incrementAndGet();
            if (round == 1) {
                if (duringTool) {
                    sink.onEvent(new ToolCallReady("queued-call", "lookup", JsonObject.empty(), 0, null)).toCompletableFuture().join();
                    finishFirst();
                } else {
                    sink.onEvent(new TextDelta("first-assistant")).toCompletableFuture().join();
                }
                started.countDown();
                return first;
            }
            List<String> userText = request.messages().stream()
                    .filter(message -> message.role() == io.github.kongweiguang.ja.agent.domain.model.ModelRole.USER)
                    .flatMap(message -> message.content().stream()).filter(TextContent.class::isInstance)
                    .map(TextContent.class::cast).map(TextContent::text).toList();
            assertEquals(round == 2 ? List.of("original", "steering") : List.of("original", "steering", "follow-up"), userText);
            List<String> ordered = request.messages().stream().flatMap(message -> message.content().stream()).map(content -> switch (content) {
                case TextContent text -> text.text();
                case ToolResultContent result -> "settled-tool";
                default -> "other";
            }).toList();
            int prior = ordered.indexOf(duringTool ? "settled-tool" : "first-assistant");
            assertTrue(prior >= 0 && prior < ordered.indexOf("steering"), ordered.toString());
            sink.onEvent(new TextDelta("response-" + round)).toCompletableFuture().join();
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null, new ModelUsage(100, 10, 110)));
        }

        /** 始终可重复释放，测试失败清理也不能把 Engine 留在不可取消的测试 Future 上。 */
        private void finishFirst() {
            first.complete(new ModelOutcome(FinishReason.STOP, null, new ModelUsage(100, 10, 110)));
        }
    }

    /** 暂停工具只为确定复现已派发未结算窗口，不访问外部系统。 */
    private static final class PausedTool implements AgentTool {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<ExecutionResult> result = new CompletableFuture<>();
        private final AtomicInteger executions = new AtomicInteger();

        /** 参数沿用严格空对象，避免测试绕过实际 Runner 校验。 */
        @Override public ToolSpec spec() { return new LookupTool(executions).spec(); }

        /** 测试仅检查结算顺序，不产生真实外部副作用。 */
        @Override public ToolSideEffect sideEffect() { return ToolSideEffect.READ_ONLY; }

        /** 无审批策略参与，暂停位置由工具本身明确控制。 */
        @Override public ApprovalRequirement approvalRequirement() { return ApprovalRequirement.NOT_REQUIRED; }

        /** 已开始但暂不返回结果，使用户输入可以真实落在工具执行中间。 */
        @Override public CompletionStage<ExecutionResult> execute(Invocation invocation, ExecutionContext context, CancellationToken cancellation) {
            executions.incrementAndGet();
            started.countDown();
            return result;
        }

        /** 释放后仍由生产内核提交结果，测试不写 ToolResult 数据库行。 */
        private void finish() {
            result.complete(ExecutionResult.of(new ToolResult(ToolOutcome.SUCCEEDED, "settled-tool", Optional.empty(), null)));
        }
    }

    /** 用内容长度触发真实自动压缩，摘要只压缩已淘汰证据，执行与 checkpoint 都由生产内核完成。 */
    private static final class CompactionModel implements ModelPort, SummaryModel {
        private final boolean fail;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger summaries = new AtomicInteger();

        /** 故障分支只改变摘要响应，不能改动存储或上下文选择策略。 */
        private CompactionModel(boolean fail) { this.fail = fail; }

        /** 可控计量随完整消息长度变化，压缩后的缩短必须反映到同一真实信封。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            long size = 100 + request.prompt().systemPrompt().length() + request.messages().stream()
                    .flatMap(message -> message.content().stream()).filter(TextContent.class::isInstance)
                    .map(TextContent.class::cast).mapToLong(value -> value.text().length()).sum();
            return new InputTokenEstimate(size, size, "d".repeat(64));
        }

        /** 第二轮只能看见摘要和当前用户消息，淘汰消息仍留在存储而不重复进入模型。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink, CancellationToken cancellation) {
            int round = requests.incrementAndGet();
            if (round == 2) {
                assertTrue(request.prompt().systemPrompt().contains("SUMMARY_PRESERVED"));
                assertFalse(request.messages().stream().flatMap(message -> message.content().stream())
                        .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                        .anyMatch(value -> value.text().contains("ORIGINAL_USER_MARKER")));
            }
            sink.onEvent(new TextDelta(round == 1 ? "c".repeat(2_000) : "已根据摘要继续")).toCompletableFuture().join();
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null, new ModelUsage(100, 10, 110)));
        }

        /** 摘要入口本身有独立容量测量，不使用助手请求的累计计数。 */
        @Override public InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
            return new InputTokenEstimate(100, 100, "e".repeat(64));
        }

        /** 保留每个来源序号的短证据，以通过实际覆盖校验而非关闭校验器。 */
        @Override public Result summarize(SummaryPrompt prompt) {
            summaries.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("injected summary failure");
            }
            List<SummaryDocument.Fact> facts = prompt.evictedMessages().stream()
                    .map(message -> new SummaryDocument.Fact("SUMMARY_PRESERVED_" + message.ordinal(), message.ordinal())).toList();
            SummaryDocument document = new SummaryDocument(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), facts, List.of(), List.of(), List.of());
            return new Result(document, new CheckpointUsage(100, 10, 110, 0, 0));
        }
    }

    /**
     * 在现有真实工具轮次夹具上检查 Skill，不另造一套循环或恢复状态。
     *
     * @param delegate 委派执行者
     */
    private record SkillToolModel(ScriptedModel delegate) implements ModelPort {
        /** 沿用工具测试的固定估算，隔离压缩模型对 Skill 恢复的影响。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return delegate.estimateInputTokens(request, cancellation);
        }

        /** 首轮与恢复后第二轮都必须包含已选择的指导正文。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink, CancellationToken cancellation) {
            assertTrue(request.prompt().systemPrompt().contains("GUIDANCE_KEEP_ALIVE"));
            return delegate.start(request, sink, cancellation);
        }
    }

    /** 模拟请求编码阶段失败，模型准备异常也必须使用 Provider 中立分类。 */
    private static final class PreparationFailureModel implements ModelPort {
        /** 本地信封准备失败不能被报告为业务中间件异常。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            throw new PreparationFailure();
        }

        /** 编码失败后不得产生网络或模型副作用。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink, CancellationToken cancellation) {
            throw new AssertionError("preparation failure must prevent dispatch");
        }
    }

    /** 通过公开扩展异常表达稳定分类，不依赖某个内置 HTTP 实现。 */
    private static final class PreparationFailure extends ModelPort.ModelUnavailableException {
        private static final long serialVersionUID = 1L;

        /** 固定诊断不携带请求正文或模型凭据。 */
        private PreparationFailure() { super("request cannot be encoded", null, "MODEL_PROTOCOL_ERROR"); }
    }

    /**
     * 在派发前制造确定失败，验证取消不会被异步包装改判成扩展失败，也不会产生业务副作用。
     *
     * @param cancelled 是否已取消
     */
    private record StopBeforeTool(boolean cancelled) implements AgentMiddleware {
        /** 稳定身份只用于本测试的拦截器命名空间。 */
        @Override public String id() { return "stop-before-tool"; }

        /** 两种异常共享同一工具边界，原始批次中的后续调用也必须由内核收尾。 */
        @Override public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
            if (cancelled) {
                throw new java.util.concurrent.CancellationException("test cancellation");
            }
            throw new IllegalStateException("test extension failure");
        }
    }

    /** 宿主只声明审批策略与有效期，等待持久化和重启后的授权检查由 Core 负责。 */
    private static final class ApprovalWait implements AgentMiddleware {
        private final Duration validity;

        /** 普通审批测试保留充足窗口，避免把测试机调度延迟误判成权限过期。 */
        private ApprovalWait() { this(Duration.ofMinutes(10)); }

        /** 到期测试显式缩短业务策略窗口，运行时不额外设置工具或任务预算。 */
        private ApprovalWait(Duration validity) { this.validity = validity; }

        /** 稳定策略身份使恢复沿用原授权意图。 */
        @Override public String id() { return "test-approval"; }

        /** 即使工具声明无需审批，业务策略仍可显式要求用户对本次冻结调用进行裁决。 */
        @Override public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
            return ExtensionDecision.unchanged(ToolDecision.waitFor(new WaitRequest("approval", "允许查询？",
                    JsonObjects.builder().putText("expiresAt", java.time.Instant.now().plus(validity).toString()).build())),
                    context.extensionState());
        }
    }

    /** 首轮返回一批调用，下一 Turn 直接检查真实持久历史中的配对结果。 */
    private static final class BatchModel implements ModelPort {
        private final AtomicInteger requests = new AtomicInteger();

        /** 固定估计隔离上下文压缩，使测试只针对终态历史完整性。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "b".repeat(64));
        }

        /** 第二次请求若丢掉任一失败结果便直接失败，而不是让脚本无条件返回成功。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                              CancellationToken cancellation) {
            int round = requests.incrementAndGet();
            if (round == 1) {
                for (int ordinal = 0; ordinal < 2; ordinal++) {
                    sink.onEvent(new ToolCallReady("batch-call-" + ordinal, "lookup", JsonObject.empty(), ordinal))
                            .toCompletableFuture().join();
                }
            } else {
                assertEquals(2, round);
                List<ToolResultContent> results = request.messages().stream().flatMap(message -> message.content().stream())
                        .filter(ToolResultContent.class::isInstance).map(ToolResultContent.class::cast).toList();
                assertEquals(List.of("batch-call-0", "batch-call-1"), results.stream().map(ToolResultContent::callId).toList());
                assertTrue(results.stream().allMatch(ToolResultContent::error));
                sink.onEvent(new TextDelta("已确认取消前的调用均未执行")).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }
    }

    /** 首次请求故障后的其余请求由同一个模型实例处理，测试不允许自动切换 Provider。 */
    private static final class RetryModel implements ModelPort {
        private final AtomicInteger requests = new AtomicInteger();
        private final ScriptedModel successful = new ScriptedModel("lookup", false);

        /** 使用同一计量信封，避免故障重试被混淆成上下文重新选择。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return successful.estimateInputTokens(request, cancellation);
        }

        /** 下一轮必须读不到失败请求的半截正文；第二、三次请求仍走真实工具闭环。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                              CancellationToken cancellation) {
            if (requests.incrementAndGet() == 1) {
                sink.onEvent(new TextDelta("未完成的敏感半截正文")).toCompletableFuture().join();
                return CompletableFuture.failedFuture(new ModelUnavailableException("test transient failure", null));
            }
            assertFalse(request.messages().stream().flatMap(message -> message.content().stream())
                    .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                    .anyMatch(text -> text.text().contains("未完成的敏感半截正文")));
            return successful.start(request, sink, cancellation);
        }
    }

    /** 在已提交工具结果后的 Provider 请求保持未完成，关闭回调使测试稳定覆盖该生命周期边界。 */
    private static final class PendingAfterToolModel implements ModelPort {
        private final AtomicInteger requests = new AtomicInteger();
        private final CountDownLatch followingRequest = new CountDownLatch(1);

        /** 固定估算与业务上下文无关，测试只观察持久工具结果和后续派发。 */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "c".repeat(64));
        }

        /** 首轮提交可执行调用；第二轮只在取消时结束，保证关闭发生在 Provider 未结算窗口。 */
        @Override
        public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                   CancellationToken cancellation) {
            int ordinal = requests.incrementAndGet();
            if (ordinal == 1) {
                sink.onEvent(new ToolCallReady("call-restart:1", "lookup", JsonObject.empty(), 0))
                        .toCompletableFuture().join();
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                        new ModelUsage(100, 10, 110)));
            }
            assertEquals(2, ordinal, "the execution must not dispatch a third Provider request");
            followingRequest.countDown();
            CompletableFuture<ModelOutcome> pending = new CompletableFuture<>();
            CancellationToken.Registration registration = cancellation.onCancellation(() ->
                    pending.completeExceptionally(new java.util.concurrent.CancellationException("engine shutdown")));
            pending.whenComplete((outcome, failure) -> registration.close());
            return pending;
        }
    }

    /** 首次请求提供可控的长退避提示，使取消测试不依赖随机退避窗口或线程睡眠时序。 */
    private static final class BackoffModel implements ModelPort {
        private final AtomicInteger requests = new AtomicInteger();
        private final CountDownLatch requestFailed = new CountDownLatch(1);

        /** 固定估算避免测试涉及上下文压缩。 */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "d".repeat(64));
        }

        /** 真实循环负责将 Provider 故障保存成 RetryScheduled，再由取消打断其等待。 */
        @Override
        public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                   CancellationToken cancellation) {
            requests.incrementAndGet();
            requestFailed.countDown();
            return CompletableFuture.failedFuture(new LongBackoffUnavailable());
        }
    }

    /** Provider 中立测试异常只覆盖 retry-after 提示，失败分类仍走生产可恢复路径。 */
    private static final class LongBackoffUnavailable extends ModelPort.ModelUnavailableException {
        private static final long serialVersionUID = 1L;

        /** 三十秒窗口显著长于事件轮询，测试自身不等待该窗口到期。 */
        private LongBackoffUnavailable() {
            super("test provider unavailable", null);
        }

        /** 固定后续派发的最早时间，避免依赖生产退避的随机抖动。 */
        @Override
        public Optional<Duration> retryAfterHint() {
            return Optional.of(Duration.ofSeconds(30));
        }
    }

    /** 模型第二轮断言失败结果进入真实历史后再修正答复，避免只检查工具内部状态。 */
    private static final class ToolFailureCorrectionModel implements ModelPort {
        private final AtomicInteger requests = new AtomicInteger();
        private boolean receivedFailure;
        private ToolResultContent result;

        /** 固定小估算，使闭环只依赖工具失败与模型可见结果。 */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "f".repeat(64));
        }

        /** 首轮调用注册工具，次轮必须从持久历史读到 FAILED 投影后才能生成修正答复。 */
        @Override
        public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                   CancellationToken cancellation) {
            int ordinal = requests.incrementAndGet();
            if (ordinal == 1) {
                sink.onEvent(new ToolCallReady("call-failed:1", "lookup", JsonObject.empty(), 0))
                        .toCompletableFuture().join();
            } else {
                assertEquals(2, ordinal, "unexpected extra Provider request");
                result = request.messages().stream().flatMap(message -> message.content().stream())
                        .filter(ToolResultContent.class::isInstance).map(ToolResultContent.class::cast)
                        .findFirst().orElseThrow();
                receivedFailure = result.error() && result.content().contains("上游目录暂不可用");
                assertTrue(receivedFailure, "the model must receive the registered tool failure");
                sink.onEvent(new TextDelta("我会根据工具故障修正答复")).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }
    }

    /** 完成拦截器只以持久状态决定是否已提问；重新创建 Java 实例不能改变判断。 */
    private static final class CompletionWait implements AgentMiddleware {
        /** 稳定身份对应唯一持久命名空间，重启时必须相同。 */
        @Override public String id() { return "test-completion-wait"; }

        /** 提问与 asked 标志同事务提交，回答后的重复完成检查因此不会再次等待。 */
        @Override public ExtensionDecision<CompletionDecision> beforeCompletion(CompletionContext context) {
            if (context.extensionState().value().get("asked") instanceof JsonBoolean asked && asked.value()) {
                return ExtensionDecision.unchanged(CompletionDecision.complete(), context.extensionState());
            }
            JsonObject question = JsonObjects.builder().putText("questionId", "question_confirm")
                    .putText("prompt", "确认结果").putText("type", "TEXT")
                    .put("options", new JsonArray(List.of())).putBoolean("required", true)
                    .putBoolean("allowFreeText", true).build();
            WaitRequest wait = new WaitRequest("clarification", "确认结果",
                    new JsonObject(Map.of("questions", new JsonArray(List.of(question)))));
            return new ExtensionDecision<>(CompletionDecision.await(wait),
                    ExtensionStatePatch.replace(context.extensionState(),
                            JsonObjects.builder().putBoolean("asked", true).build()), List.of());
        }
    }

    /** 记录内核传来的 Started 凭据，覆盖准入拒绝与真实执行结果两条分界。 */
    private static final class StartedBoundaryProbe implements AgentMiddleware {
        private final boolean rejectBeforeStart;
        private final AtomicReference<Boolean> observedStarted;

        /** 测试显式决定是否在 Started 提交前拒绝，并共享只读观测结果。 */
        private StartedBoundaryProbe(boolean rejectBeforeStart, AtomicReference<Boolean> observedStarted) {
            this.rejectBeforeStart = rejectBeforeStart;
            this.observedStarted = observedStarted;
        }

        /** 固定测试策略身份，使两个参数化分支使用同一注册契约。 */
        @Override
        public String id() {
            return "test-started-boundary";
        }

        /** 拒绝分支必须止于派发前；允许分支保留原参数继续执行。 */
        @Override
        public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
            if (rejectBeforeStart) {
                return ExtensionDecision.unchanged(
                        ToolDecision.reject("TEST_TOOL_REJECTED", "测试在派发前拒绝工具调用"),
                        context.extensionState());
            }
            return ExtensionDecision.unchanged(
                    ToolDecision.proceed(context.invocation().arguments()), context.extensionState());
        }

        /** 仅观测执行边界并原样投影结果，避免测试策略改写业务结局。 */
        @Override
        public ExtensionDecision<ToolResultProjection> projectToolResult(ToolResultContext context) {
            observedStarted.set(context.executionStarted());
            ToolResultProjection projection = new ToolResultProjection(context.content(), context.structuredContent());
            return ExtensionDecision.unchanged(projection, context.extensionState());
        }
    }

    /** 终态事实携带冻结的摘要和恢复裁决，让断言直接检查数据库中的边界信息。 */
    private static AgentMiddleware.MiddlewareFact candidateFact(AgentMiddleware.CompletionContext context) {
        JsonObject payload = JsonObjects.builder().putText("candidate", context.summary())
                .putBoolean("hasUnverifiedToolEffects", context.hasUnverifiedToolEffects()).build();
        return new AgentMiddleware.MiddlewareFact("completion-candidate", 1, payload);
    }

    /** 显式等待一次并为等待候选返回 fact，回答重入后才由最终提交持久化事实。 */
    private static final class AwaitFactsOnce implements AgentMiddleware {
        /** 重启前后保持同一命名空间，状态只能从 Store 读取。 */
        @Override public String id() { return "test-await-facts-once"; }

        /** 等待只提交 asked 状态；与等待候选绑定的 fact 留到用户回答后接受终态时再写。 */
        @Override public ExtensionDecision<CompletionDecision> beforeCompletion(CompletionContext context) {
            AgentMiddleware.MiddlewareFact fact = candidateFact(context);
            if (context.extensionState().value().get("asked") instanceof JsonBoolean asked && asked.value()) {
                return new ExtensionDecision<>(CompletionDecision.complete(),
                        ExtensionStatePatch.unchanged(context.extensionState()), List.of(fact));
            }
            JsonObject question = JsonObjects.builder().putText("questionId", "question_confirm")
                    .putText("prompt", "确认结果").putText("type", "TEXT")
                    .put("options", new JsonArray(List.of())).putBoolean("required", true)
                    .putBoolean("allowFreeText", true).build();
            WaitRequest wait = new WaitRequest("clarification", "确认结果",
                    new JsonObject(Map.of("questions", new JsonArray(List.of(question)))));
            JsonObject nextState = JsonObjects.builder().putBoolean("asked", true).build();
            return new ExtensionDecision<>(CompletionDecision.await(wait),
                    ExtensionStatePatch.replace(context.extensionState(), nextState), List.of(fact));
        }
    }

    /** 第一次要求同一循环继续并返回候选 fact；终态重入后明确改为 Complete。 */
    private static final class ContinueOnceWithFact implements AgentMiddleware {
        /** 持久 state namespace 隔离继续标志，不使用 Java 实例字段恢复。 */
        @Override public String id() { return "test-late-continue"; }

        /** Continue 的 statePatch 随当前提交保存，但本次 fact 应丢弃；下一候选才接受终态事实。 */
        @Override public ExtensionDecision<CompletionDecision> beforeCompletion(CompletionContext context) {
            AgentMiddleware.MiddlewareFact fact = candidateFact(context);
            if (context.extensionState().value().get("continued") instanceof JsonBoolean continued
                    && continued.value()) {
                return new ExtensionDecision<>(CompletionDecision.complete(),
                        ExtensionStatePatch.unchanged(context.extensionState()), List.of(fact));
            }
            JsonObject nextState = JsonObjects.builder().putBoolean("continued", true).build();
            return new ExtensionDecision<>(CompletionDecision.continueWith("请继续处理"),
                    ExtensionStatePatch.replace(context.extensionState(), nextState), List.of(fact));
        }
    }

    /** 每次终态决策返回一条观测 fact，验证真实 loop 只提交最终接受的候选。 */
    private static final class CompletionFact implements AgentMiddleware {
        private final String namespace;

        /** 测试显式提供稳定 namespace，避免多个策略共用同一恢复状态。 */
        private CompletionFact(String namespace) {
            this.namespace = namespace;
        }

        /** 返回在 Engine 重建后保持不变的扩展身份。 */
        @Override public String id() { return namespace; }

        /** 仅接受当前候选，并将其摘要与恢复风险标志放进同事务事实。 */
        @Override public ExtensionDecision<CompletionDecision> beforeCompletion(CompletionContext context) {
            return new ExtensionDecision<>(CompletionDecision.complete(),
                    ExtensionStatePatch.unchanged(context.extensionState()), List.of(candidateFact(context)));
        }
    }

    /** 首轮产生普通候选，之后产生真实工具调用，最终从 Store 重建工具结果并回答。 */
    private static final class ContinuationToolModel implements ModelPort {
        private final AtomicInteger requests = new AtomicInteger();

        /** 测试只约束循环，不向模型供应商发送数据。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "b".repeat(64));
        }

        /** 三个请求分别触发 Continue、工具派发和最终回答，固定证明分支顺序。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                              CancellationToken cancellation) {
            int round = requests.incrementAndGet();
            if (round == 1) {
                sink.onEvent(new TextDelta("先继续处理")).toCompletableFuture().join();
            } else if (round == 2) {
                sink.onEvent(new ToolCallReady("call-native:continue-tool", "lookup",
                        JsonObject.empty(), 0, null)).toCompletableFuture().join();
            } else {
                assertEquals(3, round, "unexpected model request after final candidate");
                ToolResultContent result = request.messages().stream().flatMap(message -> message.content().stream())
                        .filter(ToolResultContent.class::isInstance).map(ToolResultContent.class::cast)
                        .findFirst().orElseThrow();
                assertFalse(result.error());
                sink.onEvent(new TextDelta("最终答复")).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }
    }

    /** 第一次执行后明确返回未知结果；恢复命令只能跳过，不能再次调用外部副作用。 */
    private static final class UnknownEffectTool implements AgentTool {
        private final AtomicInteger executions;

        /** 计数仅用于证明重启和显式 skip 没有重放工具动作。 */
        private UnknownEffectTool(AtomicInteger executions) {
            this.executions = executions;
        }

        /** 使用已注册的 lookup 名称，使测试经过常规工具路由与参数校验。 */
        @Override public ToolSpec spec() {
            return new ToolSpec("lookup", "模拟结果未知的外部动作", JsonObjects.builder().putText("type", "object")
                    .putBoolean("additionalProperties", false).build());
        }

        /** 声明可能产生外部变化，未知结果不能按只读工具处理。 */
        @Override public ToolSideEffect sideEffect() { return ToolSideEffect.EXTERNAL; }

        /** 测试显式关闭审批，直接进入待恢复状态以覆盖 skip 语义。 */
        @Override public ApprovalRequirement approvalRequirement() { return ApprovalRequirement.NOT_REQUIRED; }

        /** 模拟副作用已越过派发边界但结果响应丢失，内核必须持久化为未知。 */
        @Override public CompletionStage<ExecutionResult> execute(Invocation invocation, ExecutionContext context,
                                                                  CancellationToken cancellation) {
            executions.incrementAndGet();
            return CompletableFuture.<ExecutionResult>failedFuture(
                    new IllegalStateException("external result is unknown"));
        }
    }

    /** 驱动真实 SQLite 的 NotApplied 恢复路径，并分别观测未派发取消与 Hook 内不确定异常。 */
    private static final class RetryNotAppliedTool implements AgentTool {
        private final AtomicInteger dispatches;
        private final AtomicInteger retryDispatches;
        private final AtomicInteger settlementReleases;
        private final boolean cancelInsideRetryHook;

        /** 共享可见计数使外层屏障能断言持久 Started 与实际业务 Hook 的顺序。 */
        private RetryNotAppliedTool(AtomicInteger dispatches, AtomicInteger retryDispatches,
                AtomicInteger settlementReleases, boolean cancelInsideRetryHook) {
            this.dispatches = dispatches;
            this.retryDispatches = retryDispatches;
            this.settlementReleases = settlementReleases;
            this.cancelInsideRetryHook = cancelInsideRetryHook;
        }

        /** 沿用标准空对象契约，恢复测试仍经过生产工具路由和参数校验。 */
        @Override public ToolSpec spec() {
            return new ToolSpec("lookup", "模拟可核验重试的外部动作", JsonObjects.builder().putText("type", "object")
                    .putBoolean("additionalProperties", false).build());
        }

        /** 外部副作用分类要求首轮失败后先核验，内核不得直接重放。 */
        @Override public ToolSideEffect sideEffect() { return ToolSideEffect.EXTERNAL; }

        /** 测试只针对恢复和取消边界，不额外引入审批等待。 */
        @Override public ApprovalRequirement approvalRequirement() { return ApprovalRequirement.NOT_REQUIRED; }

        /** 首轮已进入外部动作但未获得结果，确定产生一个待核验恢复事实。 */
        @Override public CompletionStage<ExecutionResult> execute(
                Invocation invocation, ExecutionContext context, CancellationToken cancellation) {
            dispatches.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("external result is unknown"));
        }

        /** 宿主只在显式恢复时证明前次未执行，并将 claim 交给结果事务释放。 */
        @Override public CompletionStage<RecoveryResolution> reconcile(
                Invocation invocation, ExecutionContext context, Optional<RecoveryEvidence> evidence,
                CancellationToken cancellation) {
            return CompletableFuture.completedFuture(new RecoveryResolution.NotApplied("d".repeat(64),
                    settlementReleases::incrementAndGet));
        }

        /** Hook 入口是副作用不确定性边界；其取消异常不能被解释成未派发。 */
        @Override public CompletionStage<ExecutionResult> executeAfterNotApplied(
                Invocation invocation, ExecutionContext context, CancellationToken cancellation,
                SettlementResource settlementResource) {
            dispatches.incrementAndGet();
            retryDispatches.incrementAndGet();
            if (cancelInsideRetryHook) {
                throw new java.util.concurrent.CancellationException("retry hook cancellation is uncertain");
            }
            return CompletableFuture.completedFuture(new ExecutionResult(
                    new ToolResult(ToolOutcome.SUCCEEDED, "retried", Optional.empty(), null),
                    List.of(), settlementResource));
        }
    }

    /** 初次返回真实工具调用，恢复后的下一请求验证已持久化失败结果且不再执行工具。 */
    private static final class UnknownEffectModel implements ModelPort {
        private final AtomicInteger requests = new AtomicInteger();

        /** 固定小窗口估计，避免测试触发与副作用恢复无关的压缩路径。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "c".repeat(64));
        }

        /** 首轮制造调用，次轮只在恢复结果配对后完成当前循环。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                              CancellationToken cancellation) {
            int round = requests.incrementAndGet();
            if (round == 1) {
                sink.onEvent(new ToolCallReady("call-native:unknown-effect", "lookup",
                        JsonObject.empty(), 0, null)).toCompletableFuture().join();
            } else {
                assertEquals(2, round, "unknown external action must suspend before another model request");
                ToolResultContent result = request.messages().stream().flatMap(message -> message.content().stream())
                        .filter(ToolResultContent.class::isInstance).map(ToolResultContent.class::cast)
                        .findFirst().orElseThrow();
                assertTrue(result.error());
                sink.onEvent(new TextDelta("已记录恢复裁决")).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }
    }

    /**
     * 只阻塞 Provider 结算，确保测试可在真实 admission 与 RUNNING 事件之后检查 SQLite 前缀。
     *
     * @author kongweiguang
     */
    private static final class GatedModel implements ModelPort {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<ModelOutcome> outcome = new CompletableFuture<>();
        private final AtomicReference<ModelEventSink> sink = new AtomicReference<>();

        /**
         * 固定输入估算，避免集成断言受外部 tokenizer 或上下文窗口变化影响。
         *
         * @author kongweiguang
         */
        @Override
        public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "e".repeat(64));
        }

        /**
         * 保留生产 admission、Scheduler 与 Store 路径，只将最终 Provider 结果交给测试显式释放。
         *
         * @author kongweiguang
         */
        @Override
        public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink eventSink,
                                                   CancellationToken cancellation) {
            sink.set(eventSink);
            started.countDown();
            return outcome;
        }

        /**
         * 通过正常公开文本事件和 STOP 结果完成执行，不直接写 Store 或合成监听器通知。
         *
         * @author kongweiguang
         */
        private void complete() {
            sink.get().onEvent(new TextDelta("事件前缀已验证")).toCompletableFuture().join();
            outcome.complete(new ModelOutcome(FinishReason.STOP, null, new ModelUsage(100, 10, 110)));
        }
    }

    /** 可控模型只替代网络，仍让生产代码构造信封、保存调用、执行工具并重建下一轮历史。 */
    private static final class ScriptedModel implements ModelPort {
        private final String toolName;
        private final boolean expectError;
        private final String preflightFailure;
        private final AtomicInteger requests = new AtomicInteger();
        private ToolResultContent result;

        /** 明确当前脚本的预期错误，避免失败 Tool 被测试误认为业务成功。 */
        private ScriptedModel(String toolName, boolean expectError) {
            this(toolName, expectError, null);
        }

        /** 显式携带 Provider 预检失败，用真实 Runner 验证不可派发边界。 */
        private ScriptedModel(String toolName, boolean expectError, String preflightFailure) {
            this.toolName = toolName;
            this.expectError = expectError;
            this.preflightFailure = preflightFailure;
        }

        /** 固定小输入用来隔离循环协议；压缩测试明确验证没有更小信封的分支。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "a".repeat(64));
        }

        /** 第二轮从真实恢复的消息读取 ToolResult，不以测试内存伪造循环已结算的证据。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                              CancellationToken cancellation) {
            int round = requests.incrementAndGet();
            if (round == 1) {
                sink.onEvent(new ToolCallReady("call-native:1", toolName, new JsonObject(Map.of()), 0, preflightFailure))
                        .toCompletableFuture().join();
            } else {
                assertEquals(2, round, "unexpected extra Provider request");
                result = request.messages().stream().flatMap(message -> message.content().stream())
                        .filter(ToolResultContent.class::isInstance).map(ToolResultContent.class::cast)
                        .findFirst().orElseThrow();
                assertEquals(expectError, result.error());
                sink.onEvent(new TextDelta("已完成")).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }
    }

    /** 每轮 Provider 开始前从真实 SQLite 回读 Usage 与 Event，固定派发先于外部请求的提交边界。 */
    private static final class UsageDispatchModel implements ModelPort {
        private final SQLiteAgentStore store;
        private final AgentIdentity identity;
        private final List<AgentStore.ExecutionCommit> observed;
        private final StorePayloadCodec payloads = new StorePayloadCodec(new ObjectMapper());
        private final AtomicInteger requests = new AtomicInteger();

        /** 只持有真实 SQLite 与已成功提交的事务观察结果，不引入替代 Store。 */
        private UsageDispatchModel(SQLiteAgentStore store, AgentIdentity identity,
                                   List<AgentStore.ExecutionCommit> observed) {
            this.store = store;
            this.identity = identity;
            this.observed = observed;
        }

        /** 固定上下文输入大小，确保两次 Provider 请求都只覆盖同一用量派发流程。 */
        @Override public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellation) {
            return new InputTokenEstimate(100, 100, "d".repeat(64));
        }

        /** 首轮执行外部工具，次轮读取其持久结果；两次调用均先验证 UNKNOWN 估算已提交。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                              CancellationToken cancellation) {
            int ordinal = requests.incrementAndGet();
            assertUsageDispatchVisible(store, identity, observed, ordinal, payloads);
            if (ordinal == 1) {
                sink.onEvent(new ToolCallReady("call-usage:1", "lookup", JsonObject.empty(), 0))
                        .toCompletableFuture().join();
            } else {
                assertEquals(2, ordinal, "unexpected extra Provider request");
                List<ToolResultContent> results = request.messages().stream()
                        .flatMap(message -> message.content().stream())
                        .filter(ToolResultContent.class::isInstance)
                        .map(ToolResultContent.class::cast)
                        .toList();
                assertEquals(1, results.size());
                assertFalse(results.getFirst().error());
                sink.onEvent(new TextDelta("外部查询已完成")).toCompletableFuture().join();
            }
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(100, 10, 110)));
        }
    }

    /**
     * 工具执行计数能发现重放；审批恢复测试显式声明外部副作用以验证 Prompt 门禁。
     *
     * @param executions 执行列表
     * @param effect 副作用
     */
    private record LookupTool(AtomicInteger executions,
                              ToolSideEffect effect) implements AgentTool {
        /** 普通循环测试只需只读能力；副作用测试必须显式传入对应分类。 */
        private LookupTool(AtomicInteger executions) { this(executions, ToolSideEffect.READ_ONLY); }

        /** Schema 固定为空对象，工具参数仍经过生产校验器。 */
        @Override public ToolSpec spec() {
            return new ToolSpec("lookup", "查询测试业务记录", JsonObjects.builder().putText("type", "object")
                    .putBoolean("additionalProperties", false).build());
        }
        /** 使用场景明确声明的副作用类型，不能由测试结果反推权限。 */
        @Override public ToolSideEffect sideEffect() { return effect; }
        /** 测试工具声明无需审批，与实际业务工具的默认审批策略区分。 */
        @Override public ApprovalRequirement approvalRequirement() { return ApprovalRequirement.NOT_REQUIRED; }
        /** 返回真实执行结果，由生产 ToolStep 原子写入后才会进入第二轮模型消息。 */
        @Override public CompletionStage<ExecutionResult> execute(Invocation invocation, ExecutionContext context,
                                                                  CancellationToken cancellation) {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ExecutionResult.of(
                    new ToolResult(ToolOutcome.SUCCEEDED, "业务结果", Optional.empty(), null)));
        }
    }

    /** 注册的只读查询工具稳定返回业务失败，用于验证失败结果经过 Store 回到模型上下文。 */
    private static final class FailingLookupTool implements AgentTool {
        private final AtomicInteger executions;

        /** 只记录调用次数，确认失败分支既执行一次也不会被误判后重放。 */
        private FailingLookupTool(AtomicInteger executions) {
            this.executions = executions;
        }

        /** 与真实查询入口使用相同工具名和空对象 Schema，经过生产路由及参数校验。 */
        @Override
        public ToolSpec spec() {
            return new ToolSpec("lookup", "查询测试业务记录", JsonObjects.builder().putText("type", "object")
                    .putBoolean("additionalProperties", false).build());
        }

        /** 失败是业务返回，不产生需要人工核验的外部副作用。 */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /** 该只读测试能力无需审批，避免把交互等待混入工具错误闭环。 */
        @Override
        public ApprovalRequirement approvalRequirement() {
            return ApprovalRequirement.NOT_REQUIRED;
        }

        /** 返回带稳定错误码的 FAILED 结果，验证内核保留失败语义并回传给模型。 */
        @Override
        public CompletionStage<ExecutionResult> execute(Invocation invocation, ExecutionContext context,
                                                        CancellationToken cancellation) {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ExecutionResult.of(new ToolResult(ToolOutcome.FAILED,
                    "上游目录暂不可用", Optional.empty(), "UPSTREAM_UNAVAILABLE")));
        }
    }
}


