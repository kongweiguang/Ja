// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;

import io.github.kongweiguang.ja.conversation.domain.model.TextContent;

import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;


import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolState;

import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.application.approval.ApprovalBroker;
import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.cancellation.DefaultCancellationCoordinator;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.ModelEventSink;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import io.github.kongweiguang.ja.conversation.port.out.TurnToolSessionFactory;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory;
import io.github.kongweiguang.ja.conversation.application.middleware.MiddlewareChain;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryGenerator;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.runtime;

/** Agent Loop 回归集，锁定多轮 Tool、审批、事件顺序、取消刷新与终态持久化。 */
final class AgentLoopTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC);

    /** 执行计划进入异常或调试日志时不得借 record 默认输出泄漏用户输入或安全投影 Secret。 */
    @Test
    void executionPlanToStringRedactsSensitiveContent() {
        String rendered = request(List.of(), new EmptyMcpFactory(new RecordingStore())).toString();

        assertFalse(rendered.contains("hello"));
        assertFalse(rendered.contains("test-only"));
        assertTrue(rendered.contains("userInput=<redacted>"));
        assertTrue(rendered.contains("presentationSecrets=<redacted>"));
    }

    /** 锁定 MCP 会话关闭后才提交完整终态 blocks，避免资源未收口便发布结果。 */
    @Test
    void terminalCommitsFullBlocksAfterMcpClose() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        ModelPort model = (request, sink, cancellation) -> {
            // Provider 契约感知背压；等待草稿 Sink，使测试失败在模型轮次内被观察，而非关闭时才暴露。
            sink.onEvent(new ModelPort.TextDelta("done")).toCompletableFuture().join();
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        try (AgentLoop loop = loop(model, store, mcp)) {
            List<TurnEvent> published = new ArrayList<>();
            TurnResult result = run(loop, request(List.of(), mcp), CancellationToken.none(), event -> {
            // 流式草稿刻意不含持久上下文，只有 Store 已提交事实参与 revision 顺序断言。
                if (event.context() != null) {
                    assertTrue(store.committedRevision >= event.context().threadRevision());
                }
                published.add(event);
                return CompletableFuture.completedFuture(null);
            }).toCompletableFuture().join();
            assertEquals(TurnState.COMPLETED, result.state(), () -> terminalFailure(result, store));
            assertTrue(mcp.closed.get());
            assertTrue(store.mcpClosedAtTerminal);
            assertEquals(ModelRole.ASSISTANT, store.terminal.finalMessage().role());
            assertEquals("done", ((TextContent)
                    store.terminal.finalMessage().content().getFirst()).text());
            TurnEvent.Terminal terminal = assertInstanceOf(TurnEvent.Terminal.class, published.getLast());
            assertEquals("done", terminal.finalMessage().text());
            assertEquals(2, terminal.usage().usage().totalTokens());
            assertEquals(1, terminal.usage().modelRound());
            assertEquals(1, published.stream().filter(TurnEvent.Terminal.class::isInstance).count());
        }
    }

    /** 锁定 Tool 事实一一对应且助手 blocks 保持结构化，防止多轮转换丢失关联。 */
    @Test
    void toolFactsRemainOneToOneAndAssistantBlocksStayStructured() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        ModelPort model = new TwoRoundToolModel();
        AgentTool tool = new EchoTool();
        try (AgentLoop loop = loop(model, store, mcp)) {
            TurnResult result = run(loop, request(List.of(tool), mcp), CancellationToken.none(),
                    event -> CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertEquals(TurnState.COMPLETED, result.state(), () -> terminalFailure(result, store));
            ConversationRepository.AssistantFact assistant = store.facts.stream()
                    .filter(ConversationRepository.AssistantFact.class::isInstance)
                    .map(ConversationRepository.AssistantFact.class::cast).findFirst().orElseThrow();
            assertTrue(assistant.message().content().stream()
                    .anyMatch(ToolCallContent.class::isInstance));
            ConversationRepository.ToolPreparedFact prepared = fact(store, ConversationRepository.ToolPreparedFact.class);
            ConversationRepository.ToolStartedFact started = fact(store, ConversationRepository.ToolStartedFact.class);
            ConversationRepository.ToolResultFact completed = fact(store, ConversationRepository.ToolResultFact.class);
            assertEquals("call_demo", prepared.callId());
            assertEquals(0, prepared.ordinal());
            assertEquals(prepared.callId(), started.callId());
            assertEquals(prepared.callId(), completed.callId());
            assertEquals(ToolState.SUCCEEDED, completed.state());
            List<ConversationRepository.Fact> modelStep = store.commits.stream()
                    .filter(facts -> facts.stream().anyMatch(ConversationRepository.AssistantFact.class::isInstance))
                    .findFirst().orElseThrow();
            assertTrue(modelStep.stream().anyMatch(ConversationRepository.UsageFact.class::isInstance));
            assertTrue(modelStep.stream().anyMatch(ConversationRepository.ToolPreparedFact.class::isInstance));
            List<ConversationRepository.Fact> toolBatch = store.commits.stream()
                    .filter(facts -> facts.stream().anyMatch(ConversationRepository.ToolResultFact.class::isInstance))
                    .findFirst().orElseThrow();
            assertTrue(toolBatch.stream().anyMatch(ConversationRepository.ToolStartedFact.class::isInstance));
        }
    }

    /** Tool 原始结果只能短暂存在于执行边界，SQLite facts 与模型续传消息必须共享脱敏正文。 */
    @Test
    void persistsOnlySanitizedToolResultContent() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        try (AgentLoop loop = loop(new TwoRoundToolModel(), store, mcp)) {
            TurnResult result = run(loop, request(List.of(new SecretEchoTool()), mcp), CancellationToken.none(),
                    event -> CompletableFuture.completedFuture(null)).toCompletableFuture().join();

            assertEquals(TurnState.COMPLETED, result.state(), () -> terminalFailure(result, store));
            ConversationRepository.ToolResultFact fact = fact(
                    store, ConversationRepository.ToolResultFact.class);
            ConversationRepository.ToolResultMessageFact messageFact = fact(
                    store, ConversationRepository.ToolResultMessageFact.class);
            String persistedMessage = messageFact.message().content().stream()
                    .map(ToolResultContent.class::cast).findFirst().orElseThrow().content();
            assertFalse(fact.content().contains("test-only"));
            assertFalse(fact.artifactContent().contains("test-only"));
            assertFalse(persistedMessage.contains("test-only"));
            assertEquals(fact.artifactContent(), persistedMessage);
            assertTrue(persistedMessage.contains("[REDACTED]"));
        }
    }

    /**
     * Tool 直接在 Turn owner 线程串行执行，不再创建并行 Tool executor。
     */
    @Test
    void serialToolRunsOnTurnOwnerThread() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        SerialThreadTool tool = new SerialThreadTool();
        try (AgentLoop loop = loop(new TwoRoundToolModel(), store, mcp)) {
            TurnResult result = run(loop, request(List.of(tool), mcp), CancellationToken.none(),
                    event -> CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertEquals(TurnState.COMPLETED, result.state(), () -> terminalFailure(result, store));
            assertFalse(tool.ranOnVirtualThread.get());
        }
    }

    /**
     * 合法的 1024 调用上限必须保持可执行，但窗口外调用不能预先扩张为等待虚拟线程。
     */
    @Test
    void maximumToolBatchCompletesThroughBoundedWindow() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        SlidingWindowTool tool = new SlidingWindowTool(0);
        List<TurnEvent> events = new ArrayList<>();
        try (AgentLoop loop = loop(new BatchToolModel(1_024), store, mcp)) {
            TurnResult result = run(loop, requestWithToolLimit(List.of(tool), mcp, 1_024),
                    CancellationToken.none(), event -> {
                        events.add(event);
                        return CompletableFuture.completedFuture(null);
                    }).toCompletableFuture().join();
            assertEquals(TurnState.COMPLETED, result.state(), () -> terminalFailure(result, store));
            assertEquals(1_024, tool.executions.get());
            TurnEvent.ToolBatchCommitted batch = events.stream()
                    .filter(TurnEvent.ToolBatchCommitted.class::isInstance)
                    .map(TurnEvent.ToolBatchCommitted.class::cast)
                    .findFirst().orElseThrow();
            assertEquals(1_024, batch.results().size());
            assertEquals(1_023, batch.results().getLast().ordinal());
        }
    }

    /** 锁定 Tool ordinal 与 stream sequence 在整个 Turn 内全局单调。 */
    @Test
    void allocatesTurnGlobalToolOrdinalsAndStreamSequences() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        List<TurnEvent> events = new ArrayList<>();
        try (AgentLoop loop = loop(new ThreeRoundToolModel(), store, mcp)) {
            TurnResult result = run(loop, request(List.of(new EchoTool()), mcp), CancellationToken.none(), event -> {
                events.add(event);
                return CompletableFuture.completedFuture(null);
            }).toCompletableFuture().join();
            assertEquals(TurnState.COMPLETED, result.state(), () -> terminalFailure(result, store));
            List<Integer> ordinals = store.facts.stream()
                    .filter(ConversationRepository.ToolPreparedFact.class::isInstance)
                    .map(ConversationRepository.ToolPreparedFact.class::cast)
                    .map(ConversationRepository.ToolPreparedFact::ordinal).toList();
            assertEquals(List.of(0, 1), ordinals);
            List<Long> streamSequences = events.stream()
                    .filter(event -> event instanceof TurnEvent.TextDelta
                            || event instanceof TurnEvent.ReasoningSummaryDelta)
                    .map(event -> event instanceof TurnEvent.TextDelta text
                            ? text.streamSeq() : ((TurnEvent.ReasoningSummaryDelta) event).streamSeq())
                    .toList();
            assertEquals(List.of(1L, 2L, 3L), streamSequences);
            List<String> committedTexts = events.stream()
                    .filter(TurnEvent.ModelStepCommitted.class::isInstance)
                    .map(TurnEvent.ModelStepCommitted.class::cast)
                    .map(TurnEvent.ModelStepCommitted::text)
                    .toList();
            assertEquals(List.of("one", ""), committedTexts);
            assertTrue(committedTexts.stream().noneMatch(text -> text.contains("[tool ")));
        }
    }

    /** 锁定同 Thread 后续准入不会破坏当前运行 Turn 的 mutation version。 */
    @Test
    void laterSameThreadAdmissionDoesNotBreakRunningTurn() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        try (AgentLoop loop = loop(new LaterAdmissionToolModel(store), store, mcp)) {
            TurnResult result = run(loop, request(List.of(new EchoTool()), mcp), CancellationToken.none(),
                    event -> CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertEquals(TurnState.COMPLETED, result.state(), () -> terminalFailure(result, store));
            assertTrue(store.laterAdmitted);
            assertEquals(ToolState.SUCCEEDED,
                    fact(store, ConversationRepository.ToolResultFact.class).state());
        }
    }

    /** 锁定终态通知失败会关闭完成屏障且不重试持久化，避免调用方误认事件已经可见。 */
    @Test
    void terminalPublishFailureClosesBarrierWithoutRetryingCommit() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        ModelPort model = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("done"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        try (AgentLoop loop = loop(model, store, mcp)) {
            java.util.concurrent.CompletionException failure = assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> run(loop, request(List.of(), mcp), CancellationToken.none(), event ->
                            event instanceof TurnEvent.Terminal
                                    ? CompletableFuture.failedFuture(new IllegalStateException("pipe closed"))
                                    : CompletableFuture.completedFuture(null)).toCompletableFuture().join());
            assertInstanceOf(TerminalCoordinator.ProjectionFailure.class, failure.getCause());
            assertEquals(1, store.terminalCommits);
        }
    }

    /** 锁定 delta sink 失败时 Turn 保持运行态供恢复，避免错误提交虚假终态。 */
    @Test
    void deltaSinkFailureLeavesRunningTurnForRecovery() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        ModelPort model = (request, sink, cancellation) -> {
            sink.onEvent(new ModelPort.TextDelta("unsafe draft"));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(1, 1, 2)));
        };
        List<TurnEvent> events = new ArrayList<>();
        try (AgentLoop loop = loop(model, store, mcp)) {
            java.util.concurrent.CompletionException failure = assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> run(loop, request(List.of(), mcp), CancellationToken.none(), event -> {
                        events.add(event);
                        return event instanceof TurnEvent.TextDelta
                                ? CompletableFuture.failedFuture(new IllegalStateException("pipe failed"))
                                : CompletableFuture.completedFuture(null);
                    }).toCompletableFuture().join());
            AgentLoop.UnsafeGenerationException unsafe = assertInstanceOf(
                    AgentLoop.UnsafeGenerationException.class, failure.getCause());
            assertEquals(AgentLoop.UnsafeGenerationException.Code.DELTA_SINK_FAILURE, unsafe.code());
        }
        assertEquals(TurnState.RUNNING, store.state);
        assertEquals(0, store.terminalCommits);
        assertFalse(events.stream().anyMatch(TurnEvent.Terminal.class::isInstance));
        assertTrue(mcp.closed.get());
    }

    /** 锁定终态提交前刷新取消声明，防止使用过期 mutation version 覆盖取消事实。 */
    @Test
    void cancellationClaimIsRefreshedBeforeTerminalCommit() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        SelfCancellingToken cancellation = new SelfCancellingToken();
        ModelPort model = (request, sink, token) -> {
            store.claimCancellation("thr_test", "turn_test", store.committedRevision,
                    "test cancellation", CLOCK.instant());
            cancellation.cancel();
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, null));
        };

        try (AgentLoop loop = loop(model, store, mcp)) {
            TurnResult result = run(loop, request(List.of(), mcp), cancellation,
                    event -> CompletableFuture.completedFuture(null)).toCompletableFuture().join();
            assertEquals(TurnState.CANCELLED, result.state(), () -> terminalFailure(result, store));
            assertEquals(1, store.terminalCommits);
            assertEquals(2L, store.terminal.expectedTurnMutationVersion());
        }
    }

    /**
     * 锁定 Tool 执行期间发布取消后的收口顺序：先提交完整 Tool batch，再直接提交唯一 cancelled 终态，
     * 不得进入下一轮 Provider 并依赖远端被动观察取消。
     */
    @Test
    void cancellationAfterToolBatchSkipsNextProviderRound() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        SelfCancellingToken cancellation = new SelfCancellingToken();
        AtomicInteger modelRounds = new AtomicInteger();
        ModelPort model = (request, sink, token) -> {
            assertEquals(1, modelRounds.incrementAndGet(), "取消后不得启动第二轮 Provider");
            sink.onEvent(new ModelPort.ToolCallReady(
                    "call_cancel", "echo", textArguments("x"), 0));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.TOOL_CALLS,
                    new ModelPort.Continuation("test", "cancelled"),
                    new ModelUsage(1, 1, 2)));
        };
        List<TurnEvent> events = new CopyOnWriteArrayList<>();

        try (AgentLoop loop = loop(model, store, mcp)) {
            TurnResult result = run(
                    loop,
                    request(List.of(new CancellingTool(store, cancellation::cancel)), mcp),
                    cancellation,
                    event -> {
                        events.add(event);
                        return CompletableFuture.completedFuture(null);
                    }).toCompletableFuture().join();

            assertEquals(TurnState.CANCELLED, result.state(), () -> terminalFailure(result, store));
            assertEquals(1, modelRounds.get());
            assertEquals(1, store.terminalCommits);
            int toolBatch = indexOfEvent(events, TurnEvent.ToolBatchCommitted.class);
            int terminal = indexOfEvent(events, TurnEvent.Terminal.class);
            assertTrue(toolBatch >= 0 && terminal > toolBatch,
                    "Tool batch 必须先于唯一取消终态对外可见");
            assertEquals(1, events.stream().filter(TurnEvent.Terminal.class::isInstance).count());
            assertFalse(store.terminal.facts().stream()
                    .anyMatch(ConversationRepository.UsageFact.class::isInstance),
                    "Tool 模型步已提交的 Usage 不得在取消终态重复持久化");
            TurnEvent.Terminal terminalEvent = assertInstanceOf(
                    TurnEvent.Terminal.class, events.get(terminal));
            assertEquals(1, terminalEvent.usage().modelRound());
            assertEquals(2, terminalEvent.usage().usage().totalTokens());
        }
    }

    /**
     * 锁定轮次间取消仍沿用上一轮已提交的 Usage 身份，不能在新 AgentRound 创建前提前重置计量边界。
     */
    @Test
    void cancellationBetweenModelRoundsDoesNotRepeatCommittedUsage() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        BetweenRoundCancellationToken cancellation = new BetweenRoundCancellationToken();
        AtomicInteger modelRounds = new AtomicInteger();
        ModelPort model = (request, sink, token) -> {
            assertEquals(1, modelRounds.incrementAndGet(), "轮次间取消不得启动第二轮 Provider");
            sink.onEvent(new ModelPort.ToolCallReady(
                    "call_between_rounds", "echo", textArguments("x"), 0));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.TOOL_CALLS,
                    new ModelPort.Continuation("test", "between-rounds"),
                    new ModelUsage(1, 1, 2)));
        };
        List<TurnEvent> events = new CopyOnWriteArrayList<>();

        try (AgentLoop loop = loop(model, store, mcp)) {
            TurnResult result = run(
                    loop,
                    request(List.of(new EchoTool()), mcp),
                    cancellation,
                    event -> {
                        events.add(event);
                        if (event instanceof TurnEvent.ToolBatchCommitted) {
                            store.claimCancellation("thr_test", "turn_test", store.committedRevision,
                                    "between rounds", CLOCK.instant());
                            cancellation.armAfterCurrentCheck();
                        }
                        return CompletableFuture.completedFuture(null);
                    }).toCompletableFuture().join();

            assertEquals(TurnState.CANCELLED, result.state(), () -> terminalFailure(result, store));
            assertEquals(1, modelRounds.get());
            assertFalse(store.terminal.facts().stream()
                    .anyMatch(ConversationRepository.UsageFact.class::isInstance));
            TurnEvent.Terminal terminal = assertInstanceOf(TurnEvent.Terminal.class, events.getLast());
            assertEquals(1, terminal.usage().modelRound());
        }
    }

    /**
     * 清理屏障异常仍必须在 Tool batch 后提交唯一 CANCELLED；失败债务不能诱发非法 FAILED 终态。
     */
    @Test
    void cancellationCleanupFailureStillCommitsCancelledTerminal() {
        RecordingStore store = new RecordingStore();
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator();
        CancellationCoordinator.CancellationScope cancellation =
                coordinator.open("thr_test", "turn_test");
        cancellation.onCancellation(() -> {
            throw new IllegalStateException("forced cleanup debt");
        });
        List<TurnEvent> events = new CopyOnWriteArrayList<>();
        ModelPort model = (request, sink, token) -> {
            sink.onEvent(new ModelPort.ToolCallReady(
                    "call_cancel", "echo", textArguments("x"), 0));
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.TOOL_CALLS,
                    new ModelPort.Continuation("test", "cancelled"),
                    new ModelUsage(1, 1, 2)));
        };

        try (AgentLoop loop = loop(model, store, mcp)) {
            TurnResult result = run(
                    loop,
                    request(List.of(new CancellingTool(store, () ->
                            coordinator.cancel("thr_test", "turn_test", "test cancellation"))), mcp),
                    cancellation,
                    event -> {
                        events.add(event);
                        return CompletableFuture.completedFuture(null);
                    }).toCompletableFuture().join();

            assertEquals(TurnState.CANCELLED, result.state(), () -> terminalFailure(result, store));
            assertEquals(1, store.terminalCommits);
            assertTrue(indexOfEvent(events, TurnEvent.Terminal.class)
                    > indexOfEvent(events, TurnEvent.ToolBatchCommitted.class));
        } finally {
            coordinator.complete("thr_test", "turn_test");
            coordinator.close();
        }
    }

    /** 锁定终态提交失败仍关闭协调作用域且不重试副作用。 */
    @Test
    void terminalCommitFailureClosesCoordinatorWithoutRetry() {
        TerminalCoordinator coordinator = new TerminalCoordinator();
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("database unavailable");

        TerminalCoordinator.CommitFailure first = assertThrows(TerminalCoordinator.CommitFailure.class,
                () -> coordinator.finish(() -> {
                    attempts.incrementAndGet();
                    throw failure;
                }, ignored -> {
                    throw new AssertionError("event projection must not run without a receipt");
                }, ignored -> CompletableFuture.completedFuture(null)));

        TerminalCoordinator.CommitFailure observed = assertThrows(TerminalCoordinator.CommitFailure.class,
                () -> coordinator.finish(() -> {
                    attempts.incrementAndGet();
                    throw new AssertionError("terminal commit retried");
                }, ignored -> {
                    throw new AssertionError("event projection must remain unreachable");
                }, ignored -> CompletableFuture.completedFuture(null)));
        assertSame(first, observed);
        assertEquals(1, attempts.get());
    }

    /** 锁定终态投影失败不重试提交，避免不确定映射制造重复终态。 */
    @Test
    void terminalProjectionFailureDoesNotRetryCommit() {
        TerminalCoordinator coordinator = new TerminalCoordinator();
        AtomicInteger attempts = new AtomicInteger();
        ConversationRepository.CommitReceipt receipt = new ConversationRepository.CommitReceipt(2, 2);
        TurnEvent.Terminal event = new TurnEvent.Terminal(
                new TurnEvent.Context("evt_projection_retry", "thr_test", "turn_test", 2, CLOCK.instant()),
                TurnState.FAILED, "failed", "INTERNAL_ERROR", "failed", null, null);

        assertThrows(TerminalCoordinator.ProjectionFailure.class,
                () -> coordinator.finish(() -> {
                    attempts.incrementAndGet();
                    return receipt;
                }, ignored -> {
                    throw new IllegalArgumentException("projection failed");
                }, ignored -> CompletableFuture.completedFuture(null)));
        assertThrows(TerminalCoordinator.ProjectionFailure.class,
                () -> coordinator.finish(() -> {
                    attempts.incrementAndGet();
                    return receipt;
                }, ignored -> event, ignored -> CompletableFuture.completedFuture(null)));
        assertEquals(1, attempts.get());
    }

    /** 并发终态调用方共享一个提交 owner 和同一结果，等待者不得提前返回或重复写入。 */
    @Test
    void concurrentTerminalCallersShareOneCommitAndOutcome() throws Exception {
        TerminalCoordinator coordinator = new TerminalCoordinator();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch commitStarted = new CountDownLatch(1);
        CountDownLatch commitRelease = new CountDownLatch(1);
        CountDownLatch publishStarted = new CountDownLatch(1);
        CompletableFuture<Void> publishRelease = new CompletableFuture<>();
        ConversationRepository.CommitReceipt receipt = new ConversationRepository.CommitReceipt(2, 2);
        TurnEvent.Terminal event = new TurnEvent.Terminal(
                new TurnEvent.Context("evt_concurrent_terminal", "thr_test", "turn_test", 2,
                        CLOCK.instant()), TurnState.FAILED, "", "INTERNAL_ERROR", "failed", null, null);

        CompletableFuture<TerminalCoordinator.Finish> owner = CompletableFuture.supplyAsync(
                () -> coordinator.finish(() -> {
                    attempts.incrementAndGet();
                    commitStarted.countDown();
                    try {
                        commitRelease.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return receipt;
                }, ignored -> event, ignored -> {
                    publishStarted.countDown();
                    return publishRelease;
                }));
        assertTrue(commitStarted.await(1, TimeUnit.SECONDS));
        CompletableFuture<TerminalCoordinator.Finish> observer = CompletableFuture.supplyAsync(
                () -> coordinator.finish(() -> {
                    attempts.incrementAndGet();
                    throw new AssertionError("observer repeated terminal commit");
                }, ignored -> event, ignored -> {
                    throw new AssertionError("observer repeated terminal publication");
                }));
        assertFalse(observer.isDone());
        commitRelease.countDown();
        assertTrue(publishStarted.await(1, TimeUnit.SECONDS));
        assertFalse(owner.isDone());
        assertFalse(observer.isDone());
        publishRelease.complete(null);

        TerminalCoordinator.Finish owned = owner.get(1, TimeUnit.SECONDS);
        TerminalCoordinator.Finish observed = observer.get(1, TimeUnit.SECONDS);
        assertTrue(owned.committedByCaller());
        assertFalse(observed.committedByCaller());
        assertSame(owned.outcome(), observed.outcome());
        assertEquals(1, attempts.get());
    }

    /** 锁定每个检查点只发布一条上下文压缩事件，避免恢复重放重复通知。 */
    @Test
    void publishesOneContextCompactedEventPerCheckpoint() {
        RecordingStore store = new RecordingStore("x".repeat(40_000));
        EmptyMcpFactory mcp = new EmptyMcpFactory(store);
        List<TurnEvent> events = new ArrayList<>();
        List<io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent> contextEvents =
                new ArrayList<>();
        List<CheckpointStore.ContextCheckpoint> checkpoints = new ArrayList<>();
        try (AgentLoop loop = new AgentLoop(withTokenCounting(new TwoRoundToolModel()),
                new NoopApprovalBroker(), store,
                compactingContextFactory(store, checkpoints), argumentsCodec(), new MiddlewareChain(List.of()), CLOCK)) {
            TurnResult result = run(loop, compactingRequest(mcp), CancellationToken.none(),
                    new io.github.kongweiguang.ja.conversation.port.in.TurnEventSink() {
                        /** 记录普通 Turn 事件以验证最终 revision 顺序。 */
                        @Override
                        public java.util.concurrent.CompletionStage<Void> publish(TurnEvent event) {
                            events.add(event);
                            return CompletableFuture.completedFuture(null);
                        }

                        /** 记录新的 Thread 级压缩生命周期，防止旧 Turn 事件形状回归。 */
                        @Override
                        public java.util.concurrent.CompletionStage<Void> publish(
                                io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent event) {
                            contextEvents.add(event);
                            return CompletableFuture.completedFuture(null);
                        }
                    }).toCompletableFuture().join();
            assertEquals(TurnState.COMPLETED, result.state(), () -> terminalFailure(result, store));
        }
        List<io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Compacted> compacted =
                contextEvents.stream()
                        .filter(io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Compacted.class::isInstance)
                        .map(io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Compacted.class::cast)
                        .toList();
        List<io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Started> started =
                contextEvents.stream()
                        .filter(io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Started.class::isInstance)
                        .map(io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Started.class::cast)
                        .toList();
        assertEquals(checkpoints.size(), compacted.size());
        assertEquals(checkpoints.size(), started.size());
        assertEquals(checkpoints.stream().map(CheckpointStore.ContextCheckpoint::checkpointId).toList(),
                compacted.stream().map(io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Compacted::checkpointId).toList());
        assertEquals(checkpoints.size(), checkpoints.stream()
                .map(CheckpointStore.ContextCheckpoint::sourceRevision).distinct().count());
        assertEquals(started.stream().map(value -> value.context().compactionId()).toList(),
                compacted.stream().map(value -> value.context().compactionId()).toList());
        assertTrue(compacted.stream().allMatch(value ->
                value.context().inputTokensAfter() < value.context().inputTokensBefore()
                && value.context().turnId().equals("turn_test")
                && value.context().trigger()
                == io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Trigger.AUTOMATIC));
        TurnEvent.Terminal terminal = events.stream()
                .filter(TurnEvent.Terminal.class::isInstance)
                .map(TurnEvent.Terminal.class::cast).findFirst().orElseThrow();
        assertEquals(2L, compacted.getFirst().context().threadRevision());
        assertTrue(terminal.context().threadRevision() > compacted.getFirst().context().threadRevision());
        assertEquals(store.committedRevision, terminal.context().threadRevision());
    }

    /** 组合真实 Agent Loop 与隔离端口假实现，保持用例集中验证编排顺序。 */
    private static AgentLoop loop(ModelPort model, RecordingStore store, TurnToolSessionFactory mcp) {
        return new AgentLoop(withTokenCounting(model), new NoopApprovalBroker(), store,
                contextFactory(store), argumentsCodec(), new MiddlewareChain(List.of()), CLOCK);
    }

    /** 为函数式模型 fake 补齐显式计量端口；计数随冻结 envelope 内容收缩，绝不回退生产实现。 */
    private static ModelPort withTokenCounting(ModelPort delegate) {
        return new ModelPort() {
            /** 以 fixture 字符规模产生确定性计量，使压缩与发送复用同一请求身份。 */
            @Override
            public java.util.concurrent.CompletionStage<InputTokenCount> countInputTokens(
                    ModelRequest request, CancellationToken cancellationToken) {
                long characters = request.prompt().systemPrompt().length();
                characters = Math.addExact(characters, request.messages().toString().length());
                characters = Math.addExact(characters, request.tools().toString().length());
                if (request.continuation() != null) {
                    characters = Math.addExact(characters, request.continuation().opaqueState().length());
                }
                long tokens = Math.max(1L, Math.addExact(characters, 3L) / 4L);
                return CompletableFuture.completedFuture(new InputTokenCount(tokens, "0".repeat(64)));
            }

            /** 模型事件与取消语义仍完全委托给各测试用例自己的 fake。 */
            @Override
            public java.util.concurrent.CompletionStage<ModelOutcome> start(
                    ModelRequest request, ModelEventSink eventSink,
                    CancellationToken cancellationToken) {
                return delegate.start(request, eventSink, cancellationToken);
            }
        };
    }

    /** 为每个直接循环测试创建独立终态 owner，模拟 TurnService 的准入所有权。 */
    private static java.util.concurrent.CompletionStage<TurnResult> run(
            AgentLoop loop, TurnExecutionPlan request, CancellationToken cancellation,
            TurnEventSink sink) {
        return loop.run(request, cancellation, sink, new TerminalCoordinator());
    }

    /** 构造基础执行请求，固定身份和限制以避免非目标输入漂移。 */
    private static TurnExecutionPlan request(List<AgentTool> tools, TurnToolSessionFactory toolSessions) {
        ModelPort.ModelConfiguration model = new ModelPort.ModelConfiguration(
                "provider_test", "model_test", "cfg_test",
                ModelPort.Provider.OPENAI, ModelPort.Api.OPENAI_RESPONSES, "gpt-test",
                URI.create("https://example.invalid/v1"), "test-only", Duration.ofSeconds(5),
                Duration.ofSeconds(30), java.util.Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
        ContextBudget budget = ContextBudget.capabilities(1_000_000, 8_192, true);
        return new TurnExecutionPlan("thr_test", "turn_test", Path.of("C:/workspace"),
                "hello", List.of(), model, AccessMode.APPROVAL_REQUIRED, TurnLimits.defaults(), CLOCK.instant(),
                "ws_agent_loop", 0, 0, new FixedAgentPromptSession(budget), request -> {
                    throw new AssertionError("text-only test must not read attachments");
                }, tools, "cfg_test", toolSessions,
                new ToolProjectionLimits(64_000, 16_000), List.of("test-only"));
    }

    /**
     * 只替换 Tool 预算以覆盖合法硬上限，其余身份、Provider 和输出边界保持基础请求不变。
     */
    private static TurnExecutionPlan requestWithToolLimit(
            List<AgentTool> tools, TurnToolSessionFactory toolSessions, int maxToolCalls) {
        TurnExecutionPlan base = request(tools, toolSessions);
        TurnLimits limits = new TurnLimits(base.limits().maxModelRounds(), maxToolCalls,
                base.limits().maxInputTokens(), base.limits().maxOutputTokens(), base.limits().wallTimeout());
        return new TurnExecutionPlan(base.threadId(), base.turnId(), base.workspaceRoot(), base.userInput(),
                base.attachmentIds(), base.model(), base.accessMode(), limits, base.requestedAt(), base.workspaceId(),
                base.initialThreadRevision(), base.initialTurnMutationVersion(), base.promptSession(), base.attachments(),
                base.tools(),
                base.configRevision(), base.toolSessions(), base.outputLimits(), base.presentationSecrets());
    }

    /** 构造必经压缩的执行请求，用于隔离检查点事件发布边界。 */
    private static TurnExecutionPlan compactingRequest(TurnToolSessionFactory toolSessions) {
        TurnExecutionPlan base = request(List.of(new EchoTool()), toolSessions);
        return new TurnExecutionPlan(base.threadId(), base.turnId(), base.workspaceRoot(), base.userInput(),
                base.attachmentIds(), base.model(), base.accessMode(), base.limits(), base.requestedAt(), base.workspaceId(),
                base.initialThreadRevision(), base.initialTurnMutationVersion(),
                new FixedAgentPromptSession(ContextBudget.capabilities(10_000, 1_000, true)), base.attachments(), base.tools(),
                base.configRevision(), base.toolSessions(), base.outputLimits(), base.presentationSecrets());
    }

    /** 构造禁止意外压缩的上下文工厂，使普通 Loop 用例快速暴露越界调用。 */
    private static ContextOrchestratorFactory contextFactory(RecordingStore store) {
        CheckpointStore checkpoints = new CheckpointStore() {
            /** 意外读取检查点即失败，证明普通执行不依赖恢复路径。 */
            @Override public Snapshot read(String threadId) {
                return Snapshot.empty(threadId, store.committedRevision);
            }
            /** 意外提交检查点即失败，防止普通 Loop 用例隐藏额外写入。 */
            @Override public CommittedCheckpoint commit(CheckpointStore.CommitRequest request) {
                throw new AssertionError("unexpected compaction");
            }
        };
        return new ContextOrchestratorFactory(checkpoints, CLOCK, binding -> summaryModel(prompt ->
                new SummaryGenerator.SummaryResult(SummaryDocument.empty(),
                        io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage.none())));
    }

    /** 构造带可记录检查点的压缩工厂，用于断言压缩事件与持久化一一对应。 */
    private static ContextOrchestratorFactory compactingContextFactory(
            RecordingStore store, List<CheckpointStore.ContextCheckpoint> checkpoints) {
        CheckpointStore checkpointStore = new CheckpointStore() {
            /** 返回固定来源历史，使压缩触发与摘要内容可重复。 */
            @Override public synchronized Snapshot read(String threadId) {
                Optional<ContextCheckpoint> latest = checkpoints.isEmpty()
                        ? Optional.empty() : Optional.of(checkpoints.getLast());
                return new Snapshot(threadId, store.committedRevision, latest);
            }

            /** 记录唯一检查点提交并推进 revision，复现 durable 压缩边界。 */
            @Override public synchronized CommittedCheckpoint commit(CheckpointStore.CommitRequest request) {
                assertEquals(store.committedRevision, request.expectedThreadRevision());
                if (checkpoints.stream().anyMatch(value ->
                        value.sourceRevision() == request.expectedThreadRevision())) {
                    throw new AssertionError("checkpoint source revision was committed twice");
                }
                checkpoints.add(request.checkpoint());
                long committedRevision = ++store.committedRevision;
                return CommittedCheckpoint.created(request.checkpoint(), committedRevision);
            }

        };
        return new ContextOrchestratorFactory(checkpointStore, CLOCK, binding -> summaryModel(prompt ->
                new SummaryGenerator.SummaryResult(new SummaryDocument(
                        List.of(new SummaryDocument.Fact("goal", 1)), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(),
                        List.of(new SummaryDocument.Fact("critical", 1)), List.of(), List.of(), List.of()),
                        io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage.none())));
    }

    /** Loop fixture 显式实现 Provider 精确计量，禁止生产 Summary 端口退回字符估算。 */
    private static io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel summaryModel(
            java.util.function.Function<
                    io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel.SummaryPrompt,
                    SummaryGenerator.SummaryResult> delegate) {
        return new io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel() {
            /** 返回合法且确定的 Provider 计量夹具，窗口判断不读取 estimatedTokens。 */
            @Override
            public ModelPort.InputTokenCount countInputTokens(SummaryPrompt prompt) {
                return new ModelPort.InputTokenCount(100, "0".repeat(64));
            }

            /** 委托各用例定义完整替换摘要结果。 */
            @Override
            public SummaryGenerator.SummaryResult summarize(SummaryPrompt prompt) {
                return delegate.apply(prompt);
            }
        };
    }

    /** 使用真实 JSON 形状编解码 Tool 参数，避免多轮用例绕过 wire 边界。 */
    private static JsonValueCodec argumentsCodec() {
        return new TestJsonValueCodec();
    }

    /** 从提交记录中提取指定事实，集中校验唯一性并拒绝缺失。 */
    private static <T extends ConversationRepository.Fact> T fact(RecordingStore store, Class<T> type) {
        return store.facts.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    /** 返回首个指定事件类型的位置，缺失时返回 -1，使顺序断言同时覆盖事件缺失。 */
    private static int indexOfEvent(List<TurnEvent> events, Class<? extends TurnEvent> type) {
        for (int index = 0; index < events.size(); index++) {
            if (type.isInstance(events.get(index))) {
                return index;
            }
        }
        return -1;
    }

    /** 构造可识别的终态存储失败，用于证明 Loop 不重试持久化副作用。 */
    private static String terminalFailure(TurnResult result, RecordingStore store) {
        return "terminal=" + result.state() + ", code=" + result.terminal().errorCode()
                + ", message=" + result.terminal().errorMessage() + ", commits="
                + store.commits.stream().map(commit -> commit.stream()
                        .map(fact -> fact.getClass().getSimpleName()).toList()).toList();
    }

    /** 首轮发出 Tool、次轮返回文本的模型假实现，用于覆盖基本 Agent Loop。 */
    private static final class TwoRoundToolModel implements ModelPort {
        /** 按调用次数发出 Tool 或最终文本，稳定控制两轮终止条件。 */
        @Override
        public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                                                   CancellationToken cancellationToken) {
            if (request.round() == 1) {
                sink.onEvent(new ToolCallReady("call_demo", "echo", textArguments("x"), 0));
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.TOOL_CALLS,
                        new Continuation("test", "next"), new ModelUsage(1, 1, 2)));
            }
            sink.onEvent(new TextDelta("complete"));
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(1, 1, 2)));
        }
    }

    /** 连续两轮 Tool 后结束的模型假实现，用于验证 Turn 全局序号。 */
    private static final class ThreeRoundToolModel implements ModelPort {
        /** 按轮次发出两个 Tool 与最终文本，保持多轮事件序列可预测。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                CancellationToken cancellationToken) {
            if (request.round() == 1) {
                sink.onEvent(new TextDelta("one"));
                sink.onEvent(new ToolCallReady("call_one", "echo", textArguments("1"), 0));
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.TOOL_CALLS,
                        new Continuation("test", "second"), new ModelUsage(1, 1, 2)));
            }
            if (request.round() == 2) {
                sink.onEvent(new ReasoningSummaryDelta("two"));
                sink.onEvent(new ToolCallReady("call_two", "echo", textArguments("2"), 0));
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.TOOL_CALLS,
                        new Continuation("test", "third"), new ModelUsage(1, 1, 2)));
            }
            sink.onEvent(new TextDelta("three"));
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(1, 1, 2)));
        }
    }

    /**
     * 首轮生成固定数量并行调用、次轮结束，用于隔离验证 Tool 窗口而不引入 Provider IO。
     */
    private static final class BatchToolModel implements ModelPort {
        private final int count;

        /**
         * 固定合法调用数量，使 16 并发窗口与 1024 预算上限可以复用同一模型夹具。
         */
        private BatchToolModel(int count) {
            this.count = count;
        }

        /**
         * 严格按 ordinal 发布 Tool，第二轮只返回终态文本，避免模型顺序影响 Tool 调度断言。
         */
        @Override
        public CompletionStage<ModelOutcome> start(
                ModelRequest request, ModelEventSink sink, CancellationToken cancellationToken) {
            if (request.round() == 1) {
                for (int index = 0; index < count; index++) {
                    sink.onEvent(new ToolCallReady(
                            "call_batch_" + index, "window",
                            JsonObjects.builder().putNumber("index", index).build(), index));
                }
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.TOOL_CALLS,
                        new Continuation("test", "window"), new ModelUsage(1, 1, 2)));
            }
            sink.onEvent(new TextDelta("complete"));
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(1, 1, 2)));
        }
    }

    /** 在模型执行中触发后续准入的假实现，用于复现同 Thread revision 竞争。 */
    private static final class LaterAdmissionToolModel implements ModelPort {
        private final RecordingStore store;

        /** 注入存储以在首轮内部准入后续 Turn，精确制造 revision 前进。 */
        private LaterAdmissionToolModel(RecordingStore store) { this.store = store; }

        /** 首轮推进同 Thread revision 后继续返回 Tool，验证运行 Turn 不被破坏。 */
        @Override public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink sink,
                CancellationToken cancellationToken) {
            if (request.round() == 1) {
                store.admitLaterTurn();
                sink.onEvent(new ToolCallReady("call_demo", "echo", textArguments("x"), 0));
                return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.TOOL_CALLS,
                        new Continuation("test", "next"), new ModelUsage(1, 1, 2)));
            }
            assertTrue(request.messages().stream().flatMap(message -> message.content().stream())
                    .filter(TextContent.class::isInstance)
                    .map(TextContent.class::cast)
                    .noneMatch(text -> text.text().equals("later")));
            sink.onEvent(new TextDelta("complete"));
            return CompletableFuture.completedFuture(new ModelOutcome(FinishReason.STOP, null,
                    new ModelUsage(1, 1, 2)));
        }
    }

    /** 回显输入的无副作用 Tool，用于验证调用、结果与消息结构一一对应。 */
    private static class EchoTool implements AgentTool {
        private final ToolSpec spec = new ToolSpec("echo", "echo",
                JsonObjects.builder().putText("type", "object").build());

        /** 返回固定严格 Schema，确保模型 Tool 声明与执行名称一致。 */
        @Override public ToolSpec spec() { return spec; }

        /** 原样回显参数内容，使断言能追踪每次调用的精确对应关系。 */
        @Override public CompletionStage<ToolResult> execute(Invocation invocation,
                ExecutionContext context, CancellationToken cancellationToken) {
            return CompletableFuture.completedFuture(ToolResult.success("ok"));
        }
    }

    /** 返回包含已知 Provider secret 的结果，验证 Runner 在任何持久事实前统一投影。 */
    private static final class SecretEchoTool extends EchoTool {
        /** 结果故意包含 request.presentationSecrets 中的值，只用于脱敏回归。 */
        @Override public CompletionStage<ToolResult> execute(Invocation invocation,
                ExecutionContext context, CancellationToken cancellationToken) {
            return CompletableFuture.completedFuture(ToolResult.success("token=test-only"));
        }
    }

    /** 记录 SERIAL Tool 的实际线程归属，防止串行语义绕过全局有界执行器。 */
    private static final class SerialThreadTool implements AgentTool {
        private final ToolSpec spec = new ToolSpec("echo", "echo",
                JsonObjects.builder().putText("type", "object").build());
        private final AtomicBoolean ranOnVirtualThread = new AtomicBoolean();

        /** 声明串行约束，使 Runner 采用窗口一但仍提交到共享 Tool executor。 */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /** 在用户代码边界采集线程类型，直接证明没有落回 JUnit 平台线程。 */
        @Override
        public CompletionStage<ToolResult> execute(
                Invocation invocation,
                ExecutionContext context,
                CancellationToken cancellationToken) {
            ranOnVirtualThread.set(Thread.currentThread().isVirtual());
            return CompletableFuture.completedFuture(ToolResult.success("ok"));
        }
    }

    /**
     * 记录并发峰值和线程类型的只读 Tool；可选屏障用于冻结首个八任务窗口。
     */
    private static final class SlidingWindowTool implements AgentTool {
        private final ToolSpec spec = new ToolSpec("window", "window",
                JsonObjects.builder().putText("type", "object").build());
        private final CountDownLatch firstWindowEntered;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicBoolean onlyVirtualThreads = new AtomicBoolean(true);

        /**
         * expectedBlocked 为零时不设置屏障，供 1024 调用快速验证预算兼容性。
         */
        private SlidingWindowTool(int expectedBlocked) {
            firstWindowEntered = new CountDownLatch(expectedBlocked);
            if (expectedBlocked == 0) release.countDown();
        }

        /**
         * 返回并行只读声明，使 Runner 选择滑动窗口而非整批串行路径。
         */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * 在 Tool 用户代码内部统计真实 active 峰值，并冻结首窗直到测试线程放行。
         */
        @Override
        public CompletionStage<ToolResult> execute(
                Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
            executions.incrementAndGet();
            onlyVirtualThreads.compareAndSet(true, Thread.currentThread().isVirtual());
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            firstWindowEntered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Tool window test timed out");
                }
                return CompletableFuture.completedFuture(ToolResult.success(
                        String.valueOf(invocation.arguments().get("index"))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Tool window interrupted");
            } finally {
                active.decrementAndGet();
            }
        }
    }

    /** 在执行中同步发布持久化取消事实，复现真实 Shell Tool 清理完成后的 Loop 归约边界。 */
    private static final class CancellingTool extends EchoTool {
        private final RecordingStore store;
        private final Runnable cancellationPublisher;

        /** 绑定同一存储与取消发布动作，使轻量 Token 和真实协调器复用相同执行顺序。 */
        private CancellingTool(RecordingStore store, Runnable cancellationPublisher) {
            this.store = store;
            this.cancellationPublisher = cancellationPublisher;
        }

        /** Tool 返回前先持久化取消声明并发布 Token，随后返回已取消结果。 */
        @Override
        public CompletionStage<ToolResult> execute(
                Invocation invocation,
                ExecutionContext context,
                CancellationToken cancellationToken) {
            store.claimCancellation(
                    "thr_test",
                    "turn_test",
                    store.committedRevision,
                    "test cancellation",
                    CLOCK.instant());
            cancellationPublisher.run();
            return CompletableFuture.completedFuture(new ToolResult(
                    io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome.CANCELLED,
                    "",
                    Optional.empty(),
                    "shell_cancelled"));
        }
    }

    /** 构造最小 echo 参数，集中表达测试中的强类型 JSON 边界。 */
    private static JsonObject textArguments(String value) {
        return JsonObjects.builder().putText("text", value).build();
    }

    /** 读取会话授权状态的 Tool 假实现，用于证明授权发生在执行之前。 */
    private static final class GrantAwareTool extends EchoTool {
        private final AtomicInteger grants;
        private final AtomicInteger executions;

        /** 注入授权表与精确请求，使执行时可直接断言绑定授权已存在。 */
        private GrantAwareTool(AtomicInteger grants, AtomicInteger executions) {
            this.grants = grants;
            this.executions = executions;
        }

        /** 仅在精确会话授权可见时成功，锁定审批提交、授权、执行顺序。 */
        @Override public CompletionStage<ToolResult> execute(Invocation invocation,
                ExecutionContext context, CancellationToken cancellationToken) {
            assertEquals(1, grants.get());
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolResult.success("ok"));
        }
    }

    /** 立即返回会话允许的审批假实现，用于隔离授权顺序而不等待用户输入。 */
    private static final class ImmediateSessionApproval implements ApprovalBroker {
        /** 立即完成会话允许响应，使 Loop 继续验证后续授权与执行。 */
        @Override public CompletionStage<Resolution> request(ApprovalRequest request,
                CancellationToken cancellationToken) {
            return CompletableFuture.completedFuture(new Resolution(request.approvalId(),
                    ApprovalDecision.APPROVE, CLOCK.instant()));
        }
        /** 无待决审批可由外部解析，响应入口保持无副作用。 */
        @Override public boolean resolve(String approvalId, ApprovalDecision response, Instant resolvedAt) { return false; }
        /** 即时审批不保留待决状态，因此 Turn 取消无需额外处理。 */
        @Override public void cancelTurn(String threadId, String turnId, String reason) { }
    }

    /** 返回空 MCP Tool 会话并记录关闭的工厂，用于验证资源先于终态收口。 */
    private static final class EmptyMcpFactory implements TurnToolSessionFactory {
        private final RecordingStore store;
        private final AtomicBoolean closed = new AtomicBoolean();

        /** 注入关闭标记，使终态提交可断言 MCP 会话已释放。 */
        private EmptyMcpFactory(RecordingStore store) { this.store = store; }

        /** 为每轮创建独立空会话，避免关闭状态跨请求复用。 */
        @Override public Session open(CancellationToken cancellationToken) {
            return new Session() {
                /** 固定返回空远程 Tool，保持模型只看到测试内置 Tool。 */
                @Override public List<AgentTool> tools() { return List.of(); }
                /** 记录会话已关闭，供终态提交顺序断言读取。 */
                @Override public void close() { closed.set(true); store.mcpClosed = true; }
            };
        }
    }

    /** 可由测试同步触发的取消 token，用于控制终态提交前的取消刷新。 */
    private static final class SelfCancellingToken implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** 原子标记取消并通知已注册监听器，模拟运行时单次取消。 */
        private void cancel() {
            cancelled.set(true);
        }

        /** 回读取消标记，使 Loop 在提交前观察测试触发的状态。 */
        @Override public boolean isCancellationRequested() {
            return cancelled.get();
        }

        /** 取消后返回固定原因，便于断言持久化声明使用同一语义。 */
        @Override public Optional<String> reason() {
            return cancelled.get() ? Optional.of("test cancellation") : Optional.empty();
        }

        /** 登记可撤销监听器，使夹具覆盖回调生命周期而不泄漏。 */
        @Override public Registration onCancellation(Runnable callback) {
            return Registration.noop();
        }
    }

    /** 在 Tool batch 发布后的第二次查询才暴露取消，用于稳定复现两个模型轮次之间的竞争窗口。 */
    private static final class BetweenRoundCancellationToken implements CancellationToken {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicInteger checksAfterArmed = new AtomicInteger();

        /**
         * 保留当前 batch 后的首次检查为未取消，使 Loop 恰好进入下一轮入口再观察取消。
         */
        private void armAfterCurrentCheck() {
            armed.set(true);
        }

        /** 第二次及后续查询返回取消，模拟 Tool batch 与下一轮 AgentRound 创建之间的竞态。 */
        @Override public boolean isCancellationRequested() {
            return armed.get() && checksAfterArmed.incrementAndGet() > 1;
        }

        /** 仅在取消已经可见后返回固定脱敏原因。 */
        @Override public Optional<String> reason() {
            return isCancellationRequested() ? Optional.of("between rounds") : Optional.empty();
        }

        /** 该确定性夹具不持有异步资源，因此无需保存回调引用。 */
        @Override public Registration onCancellation(Runnable callback) {
            return Registration.noop();
        }
    }

    /** 记录提交事实、revision 与消息的存储假实现，用于断言 Agent Loop 原子顺序。 */
    private static final class RecordingStore implements ConversationRepository {
        private long committedRevision;
        private long turnMutationVersion;
        private boolean mcpClosed;
        private boolean mcpClosedAtTerminal;
        private TerminalCommit terminal;
        private int terminalCommits;
        private TurnState state = TurnState.QUEUED;
        private boolean cancellationClaimed;
        private long cancellationExpectedRevision = -1;
        private CancellationClaim cancellationReceipt;
        private final List<Fact> facts = new ArrayList<>();
        private final List<List<Fact>> commits = new ArrayList<>();
        private boolean laterAdmitted;
        private final List<StoredMessage> messages;

        /** 创建空记录存储，适合不依赖 MCP 关闭顺序的基础用例。 */
        private RecordingStore() {
            this("hello");
        }

        /** 注入 MCP 关闭标记，使终态提交可验证资源释放前置条件。 */
        private RecordingStore(String initialText) {
            messages = new ArrayList<>(List.of(
                    new StoredMessage("item_user", "turn_test", 1,
                            new ModelMessage(ModelRole.USER,
                                    List.of(new TextContent(initialText))), CLOCK.instant())));
        }

        /** 禁止测试隐式创建 Thread，确保所有 Loop 用例从已准入状态开始。 */
        @Override public ThreadSnapshot createThread(ThreadDefinition thread) { throw new UnsupportedOperationException(); }
        /** 记录后续 Turn 准入并推进 Thread revision，用于制造并发 revision 变化。 */
        @Override public AdmissionReceipt admit(TurnAdmission admission) {
            return new AdmissionReceipt(
                    admission.threadId(), admission.turnId(), committedRevision, turnMutationVersion, null);
        }
        /** 校验 mutation version 并记录中间事实，复现生产 CAS 提交。 */
        @Override public CommitReceipt commit(CommitRequest request) {
            assertEquals(turnMutationVersion, request.expectedTurnMutationVersion());
            if (cancellationClaimed) {
                throw new AssertionError("non-terminal commit crossed cancellation claim");
            }
            state = request.state();
            facts.addAll(request.facts());
            commits.add(request.facts());
            appendMessages(request.facts());
            return new CommitReceipt(++committedRevision, ++turnMutationVersion);
        }
        /** 只允许取消后的完整 Tool batch 推进版本，继续拒绝其它非终态事实。 */
        @Override public CommitReceipt commitCancellationToolBatch(CancellationToolBatchCommit cancellationCommit) {
            CommitRequest request = cancellationCommit.request();
            assertTrue(cancellationClaimed, "cancellation Tool batch requires a durable claim");
            assertEquals(turnMutationVersion, request.expectedTurnMutationVersion());
            assertEquals(state, request.state());
            facts.addAll(request.facts());
            commits.add(request.facts());
            appendMessages(request.facts());
            return new CommitReceipt(++committedRevision, ++turnMutationVersion);
        }
        /** 在 MCP 已关闭后记录唯一终态提交，拒绝资源收口顺序倒置。 */
        @Override public CommitReceipt commitTerminal(TerminalCommit request) {
            assertEquals(turnMutationVersion, request.expectedTurnMutationVersion());
            if (cancellationClaimed) assertEquals(TurnState.CANCELLED, request.state());
            terminalCommits++;
            mcpClosedAtTerminal = mcpClosed;
            terminal = request;
            state = request.state();
            facts.addAll(request.facts());
            if (request.finalMessage() != null) {
                messages.add(new StoredMessage(request.finalMessageId(), request.turnId(),
                        messages.size() + 1L, request.finalMessage(), request.occurredAt()));
            }
            return new CommitReceipt(++committedRevision, ++turnMutationVersion);
        }
        /** 记录取消声明并推进版本，供终态提交刷新权威 CAS token。 */
        @Override public synchronized CancellationClaim claimCancellation(String threadId, String turnId,
                long expectedThreadRevision, String reason, Instant occurredAt) {
            if (state.terminal()) throw new AssertionError("terminal cancellation claim");
            if (cancellationClaimed) {
                assertEquals(cancellationExpectedRevision, expectedThreadRevision);
                return cancellationReceipt;
            }
            assertEquals(expectedThreadRevision, committedRevision);
            cancellationClaimed = true;
            cancellationExpectedRevision = expectedThreadRevision;
            cancellationReceipt = new CancellationClaim(true, state, ++committedRevision, ++turnMutationVersion);
            return cancellationReceipt;
        }
        /** 返回当前 Turn 快照，使 Loop 以持久状态恢复取消与 revision。 */
        @Override public Optional<TurnSnapshot> findTurn(String threadId, String turnId) {
            return Optional.of(new TurnSnapshot(threadId, turnId, state, runtime(),
                    CLOCK.instant(), CLOCK.instant(), state.terminal() ? CLOCK.instant() : null, committedRevision,
                    turnMutationVersion));
        }
        /** 返回消息与 Turn 的一致快照，供上下文编排构造模型历史。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId) {
            TurnSnapshot turn = new TurnSnapshot("thr_test", "turn_test", state, runtime(),
                    CLOCK.instant(), CLOCK.instant(), state.terminal() ? CLOCK.instant() : null, committedRevision,
                    turnMutationVersion);
            List<TurnSnapshot> turns = new ArrayList<>(List.of(turn));
            if (laterAdmitted) {
                turns.add(new TurnSnapshot("thr_test", "turn_later", TurnState.QUEUED, runtime(),
                        CLOCK.instant().plusSeconds(1), CLOCK.instant().plusSeconds(1), null,
                        committedRevision, 0));
            }
            return Optional.of(new ThreadSnapshot("thr_test", "ws_test", "test", preferences(),
                    committedRevision, turns, messages, CLOCK.instant(), CLOCK.instant()));
        }
        /** 夹具无外部资源，关闭保留记录供最终顺序断言。 */
        @Override public void close() { }

        /** 按顺序追加模型历史消息，保持上下文夹具的 ordinal 单调。 */
        private void appendMessages(List<Fact> committedFacts) {
            for (Fact fact : committedFacts) {
                if (fact instanceof AssistantFact assistant) {
                    messages.add(new StoredMessage(assistant.messageId(), "turn_test", messages.size() + 1L,
                            assistant.message(), CLOCK.instant()));
                } else if (fact instanceof ToolResultMessageFact result) {
                    messages.add(new StoredMessage(result.messageId(), "turn_test", messages.size() + 1L,
                            result.message(), CLOCK.instant()));
                }
            }
        }

        /** 在运行轮次中准入后续 Turn，专门复现同 Thread revision 前进。 */
        private void admitLaterTurn() {
            laterAdmitted = true;
            committedRevision++;
            messages.add(new StoredMessage("item_later", "turn_later", messages.size() + 1L,
                    new ModelMessage(ModelRole.USER,
                            List.of(new TextContent("later"))), CLOCK.instant().plusSeconds(1)));
        }
    }

    /** 禁止意外审批的假代理，使非危险 Tool 用例不会静默等待用户输入。 */
    private static final class NoopApprovalBroker implements ApprovalBroker {
        /** 任意审批请求立即失败，暴露 Tool 分类或权限策略偏差。 */
        @Override public CompletionStage<Resolution> request(ApprovalRequest request,
                CancellationToken cancellationToken) { return CompletableFuture.failedFuture(new AssertionError()); }
        /** 本夹具不创建待决审批，因此外部响应保持无副作用。 */
        @Override public boolean resolve(String approvalId, ApprovalDecision response, Instant resolvedAt) { return false; }
        /** 无待决审批可取消，使 Turn 取消只由协调器负责。 */
        @Override public void cancelTurn(String threadId, String turnId, String reason) { }
    }
}


