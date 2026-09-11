// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.application.approval.InMemoryApprovalBroker;
import io.github.kongweiguang.ja.conversation.application.discovery.McpToolSearch;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.NetworkntToolArgumentValidation;
import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.cancellation.DefaultCancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.observation.ExecutionObservers;
import io.github.kongweiguang.ja.conversation.application.policy.ToolPolicyChain;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionSuspendedException;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.ExecutionObserver;
import io.github.kongweiguang.ja.conversation.port.out.GoalToolExecutionPort;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.conversation.port.out.ToolPolicy;
import io.github.kongweiguang.ja.conversation.port.out.TurnToolSessionFactory;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.execution;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 聚焦验证 Tool 恢复游标与原审批 ID 的持久权威顺序。 */
final class AgentToolRunnerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 构造无附加策略和观察器的 Runner；审批仍由内核根据冻结 AccessMode 必经执行。 */
    private static AgentToolRunner runner(InMemoryApprovalBroker broker) {
        return runner(broker, List.of(), List.of());
    }

    /** 构造显式策略与观察器组合，测试不会通过全局注册改变其它 Runner。 */
    private static AgentToolRunner runner(
            InMemoryApprovalBroker broker,
            List<? extends ToolPolicy> policies,
            List<? extends ExecutionObserver> observers) {
        TestJsonValueCodec codec = new TestJsonValueCodec();
        return new AgentToolRunner(broker, CLOCK,
                new ToolPolicyChain(policies), new ExecutionObservers(observers),
                new NetworkntToolArgumentValidation(codec));
    }

    /** 未知 Tool 必须作为当前调用失败回给模型，且不进入审批、策略或任何执行端口。 */
    @Test
    void unknownToolReturnsRecoverableResult() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = new EditTool(executions);
        List<List<ConversationRepository.Fact>> commits = new ArrayList<>();
        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            AgentTool.ToolResult result = runner.execute(
                    runnerExecution(plan(tool), tool,
                            (target, event, facts, next) -> commits.add(List.copyOf(facts))),
                    List.of(new AgentTool.Invocation(
                            "call_unknown", "missing_tool", JsonObjects.builder().build(), 0))).getFirst();

            assertEquals(ToolOutcome.FAILED, result.outcome());
            assertEquals("TOOL_BINDING_UNAVAILABLE", result.errorCode());
            assertTrue(result.content().contains("Tool 'missing_tool' is unavailable for this call"));
            assertEquals(0, executions.get());
            assertEquals(1, commits.size());
        }
    }

    /** 可信内核搜索在审批模式下直接执行，但同批真实 MCP 调用仍必须进入用户审批。 */
    @Test
    void trustedSearchSkipsApprovalButMcpStillWaits() throws Exception {
        SearchMcpTool mcpTool = new SearchMcpTool("mcp_action");
        McpToolSearch search = new McpToolSearch(List.of(mcpTool), new TestJsonValueCodec());
        List<List<ConversationRepository.Fact>> commits = new ArrayList<>();

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            // Broker 只在拒绝已持久化后唤醒等待者，测试不能用未绑定存储的取消冒充审批结果。
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> decision == ApprovalDecision.DENY);
            AgentToolRunner.Execution execution = runnerExecutionWithBindings(plan(search),
                    Map.of(McpToolSearch.NAME, search, "mcp_action", mcpTool),
                    Map.of("call_search", toolBinding("call_search", search),
                            "call_mcp", toolBinding("call_mcp", mcpTool)),
                    (target, event, facts, next) -> commits.add(List.copyOf(facts)));
            CompletableFuture<List<AgentTool.ToolResult>> result = CompletableFuture.supplyAsync(() ->
                    runner.execute(execution, List.of(
                            new AgentTool.Invocation("call_search", McpToolSearch.NAME,
                                    JsonObjects.builder().putText("query", "").build(), 0),
                            new AgentTool.Invocation("call_mcp", "mcp_action",
                                    JsonObjects.builder().build(), 1))));

            awaitPendingRegistration(broker);
            assertEquals(0, mcpTool.executions.get());
            broker.cancelTurn("thr_test", "turn_test", "deny MCP fixture");
            List<AgentTool.ToolResult> results = result.get(1, TimeUnit.SECONDS);

            assertEquals(ToolOutcome.SUCCEEDED, results.get(0).outcome());
            assertEquals(ToolOutcome.FAILED, results.get(1).outcome());
            assertEquals("TOOL_DENIED", results.get(1).errorCode());
            assertTrue(commits.size() >= 2);
        }
    }

    /** 同名但非可信实现不能借用 tool_search 名称绕过审批，权限判断必须依赖真实类型。 */
    @Test
    void mcpToolNamedToolSearchStillRequiresApproval() throws Exception {
        SearchMcpTool spoof = new SearchMcpTool(McpToolSearch.NAME);
        List<List<ConversationRepository.Fact>> commits = new ArrayList<>();

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            // 保留 persist-before-wake 语义，确保下面观察到的是明确拒绝而非等待超时。
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> decision == ApprovalDecision.DENY);
            AgentToolRunner.Execution execution = runnerExecutionWithBindings(plan(spoof),
                    Map.of(McpToolSearch.NAME, spoof),
                    Map.of("call_spoof", toolBinding("call_spoof", spoof)),
                    (target, event, facts, next) -> commits.add(List.copyOf(facts)));
            CompletableFuture<List<AgentTool.ToolResult>> result = CompletableFuture.supplyAsync(() ->
                    runner.execute(execution, List.of(new AgentTool.Invocation(
                            "call_spoof", McpToolSearch.NAME, JsonObjects.builder().build(), 0))));

            awaitPendingRegistration(broker);
            assertEquals(0, spoof.executions.get());
            broker.cancelTurn("thr_test", "turn_test", "deny spoof fixture");
            AgentTool.ToolResult denied = result.get(1, TimeUnit.SECONDS).getFirst();

            assertEquals(ToolOutcome.FAILED, denied.outcome());
            assertEquals("TOOL_DENIED", denied.errorCode());
            assertTrue(commits.size() >= 1);
        }
    }

    /** Schema 参数错误只生成可操作 ToolResult，不得触达需要副作用的 Tool 实现。 */
    @Test
    void invalidArgumentsReturnRecoverableResultBeforeExecution() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = new RequiredTextTool(executions);
        List<List<ConversationRepository.Fact>> commits = new ArrayList<>();
        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            AgentTool.ToolResult result = runner.execute(
                    runnerExecution(plan(tool), tool,
                            (target, event, facts, next) -> commits.add(List.copyOf(facts))),
                    List.of(new AgentTool.Invocation(
                            "call_invalid", "write", JsonObjects.builder().build(), 0))).getFirst();

            assertEquals(ToolOutcome.FAILED, result.outcome());
            assertEquals("TOOL_ARGUMENTS_INVALID", result.errorCode());
            assertTrue(result.content().contains("required Tool field"));
            assertTrue(result.content().contains("Correct the arguments and retry this Tool"));
            assertEquals(0, executions.get());
            assertEquals(1, commits.size());
        }
    }

    /**
     * 审批等待期间取消必须产出 CANCELLED Tool result 且不执行 Tool；持久 writer 因而仍有机会
     * 补齐 Assistant Tool call 的配对消息，后续 Turn 不会收到损坏的原生 Tool 历史。
     */
    @Test
    void cancellationWhileWaitingApprovalProducesPairedToolResult() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        List<TurnEvent> committedEvents = new ArrayList<>();
        List<List<ConversationRepository.Fact>> committedFacts = new ArrayList<>();
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                execution("cfg_test").common(), "batch_fixture", "item_assistant", 0, 0, 0);
        AgentTool tool = new EditTool(executions);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        CancellationCoordinator.CancellationScope scope = cancellations.open("thr_test", "turn_test");

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> true);
            AgentToolRunner.Execution execution = new AgentToolRunner.Execution(
                    plan(tool), Map.of("edit", tool), scope,
                    () -> new TurnEvent.Context("evt_cancel", "thr_test", "turn_test", 4, NOW),
                    () -> cursor,
                    (target, event, facts, nextExecution) -> {
                        committedEvents.add(event);
                        committedFacts.add(List.copyOf(facts));
                    },
                    callId -> Optional.empty(), callId -> Optional.of(binding(callId)),
                    () -> { }, (approvalId, decision) -> { });
            CompletableFuture<List<AgentTool.ToolResult>> result = CompletableFuture.supplyAsync(
                    () -> runner.execute(execution, List.of(new AgentTool.Invocation(
                            "call_cancel", "edit", JsonObjects.builder().build(), 0))));

            awaitPendingRegistration(broker);
            assertEquals(CancellationCoordinator.CancelOutcome.REQUESTED,
                    cancellations.cancel("thr_test", "turn_test", "test cancellation")
                            .toCompletableFuture().get(1, TimeUnit.SECONDS));
            List<AgentTool.ToolResult> results = result.get(1, TimeUnit.SECONDS);

            assertEquals(ToolOutcome.CANCELLED, results.getFirst().outcome());
            assertEquals(0, executions.get());
            assertEquals(List.of(TurnEvent.ApprovalRequested.class, TurnEvent.ToolBatchCommitted.class),
                    committedEvents.stream().map(TurnEvent::getClass).toList());
            assertTrue(committedFacts.get(1).stream()
                    .anyMatch(ConversationRepository.ToolResultMessageFact.class::isInstance));
        } finally {
            cancellations.complete("thr_test", "turn_test");
            cancellations.close();
        }
    }

    /**
     * 外部取消在 WAITING_APPROVAL 的刷新与 CAS 之间获胜时，Runner 必须把冲突解释为已确认取消，
     * 且沿既有 Tool batch 路径同时提交结果事实和 TOOL 消息，不能登记审批或执行外部 Tool。
     */
    @Test
    void cancellationWinningWaitingApprovalCommitProducesPairedToolResult() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger durableMutationVersion = new AtomicInteger(3);
        AtomicInteger localMutationVersion = new AtomicInteger(3);
        AtomicInteger authorityRefreshes = new AtomicInteger();
        AtomicInteger approvalCommitAttempts = new AtomicInteger();
        CountDownLatch approvalCommitEntered = new CountDownLatch(1);
        CountDownLatch releaseApprovalCommit = new CountDownLatch(1);
        List<TurnEvent> committedEvents = new ArrayList<>();
        List<List<ConversationRepository.Fact>> committedFacts = new ArrayList<>();
        RuntimeException casConflict = new IllegalStateException("fixture CAS conflict");
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                execution("cfg_test").common(), "batch_fixture", "item_assistant", 0, 0, 0);
        AgentTool tool = new EditTool(executions);
        DefaultCancellationCoordinator cancellations = new DefaultCancellationCoordinator();
        CancellationCoordinator.CancellationScope scope = cancellations.open("thr_test", "turn_test");

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> true);
            AgentToolRunner.Execution execution = new AgentToolRunner.Execution(
                    plan(tool), Map.of("edit", tool), scope,
                    () -> new TurnEvent.Context("evt_cancel_cas", "thr_test", "turn_test", 4, NOW),
                    () -> cursor,
                    (target, event, facts, nextExecution) -> {
                        if (event instanceof TurnEvent.ApprovalRequested) {
                            approvalCommitAttempts.incrementAndGet();
                            approvalCommitEntered.countDown();
                            try {
                                if (!releaseApprovalCommit.await(1, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("approval commit barrier timed out");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("approval commit barrier interrupted", interrupted);
                            }
                            if (localMutationVersion.get() != durableMutationVersion.get()) throw casConflict;
                        }
                        committedEvents.add(event);
                        committedFacts.add(List.copyOf(facts));
                    },
                    callId -> Optional.empty(), callId -> Optional.of(binding(callId)),
                    () -> {
                        authorityRefreshes.incrementAndGet();
                        localMutationVersion.set(durableMutationVersion.get());
                    },
                    (approvalId, decision) -> { });
            CompletableFuture<List<AgentTool.ToolResult>> result = CompletableFuture.supplyAsync(
                    () -> runner.execute(execution, List.of(new AgentTool.Invocation(
                            "call_cancel_cas", "edit", JsonObjects.builder().build(), 0))));

            assertTrue(approvalCommitEntered.await(1, TimeUnit.SECONDS));
            durableMutationVersion.incrementAndGet();
            assertEquals(CancellationCoordinator.CancelOutcome.REQUESTED,
                    cancellations.cancel("thr_test", "turn_test", "test CAS cancellation")
                            .toCompletableFuture().get(1, TimeUnit.SECONDS));
            releaseApprovalCommit.countDown();
            List<AgentTool.ToolResult> results = result.get(1, TimeUnit.SECONDS);

            assertEquals(ToolOutcome.CANCELLED, results.getFirst().outcome());
            assertEquals(0, executions.get());
            assertEquals(1, approvalCommitAttempts.get());
            assertEquals(2, authorityRefreshes.get());
            assertEquals(0, broker.pendingCount());
            assertEquals(List.of(TurnEvent.ToolBatchCommitted.class),
                    committedEvents.stream().map(TurnEvent::getClass).toList());
            assertTrue(committedFacts.getFirst().stream()
                    .anyMatch(ConversationRepository.ToolResultFact.class::isInstance));
            assertTrue(committedFacts.getFirst().stream()
                    .anyMatch(ConversationRepository.ToolResultMessageFact.class::isInstance));
        } finally {
            releaseApprovalCommit.countDown();
            cancellations.complete("thr_test", "turn_test");
            cancellations.close();
        }
    }

    /**
     * WAITING_APPROVAL 的非取消 CAS 故障必须保留原异常身份；二次 authority 刷新仅用于判定取消，
     * 不能重试提交、登记 waiter 或把持久化错误降级成 Tool 拒绝。
     */
    @Test
    void nonCancellationWaitingApprovalCommitFailureIsRethrownUnchanged() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger authorityRefreshes = new AtomicInteger();
        AtomicInteger approvalCommitAttempts = new AtomicInteger();
        RuntimeException casConflict = new IllegalStateException("fixture CAS conflict");
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                execution("cfg_test").common(), "batch_fixture", "item_assistant", 0, 0, 0);
        AgentTool tool = new EditTool(executions);

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            AgentToolRunner.Execution execution = new AgentToolRunner.Execution(
                    plan(tool), Map.of("edit", tool), CancellationToken.none(),
                    () -> new TurnEvent.Context("evt_cas", "thr_test", "turn_test", 4, NOW),
                    () -> cursor,
                    (target, event, facts, nextExecution) -> {
                        if (event instanceof TurnEvent.ApprovalRequested) {
                            approvalCommitAttempts.incrementAndGet();
                            throw casConflict;
                        }
                    },
                    callId -> Optional.empty(), callId -> Optional.of(binding(callId)),
                    authorityRefreshes::incrementAndGet,
                    (approvalId, decision) -> { });

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> runner.execute(execution, List.of(new AgentTool.Invocation(
                            "call_cas", "edit", JsonObjects.builder().build(), 0))));

            assertSame(casConflict, thrown);
            assertEquals(0, executions.get());
            assertEquals(1, approvalCommitAttempts.get());
            assertEquals(2, authorityRefreshes.get());
            assertEquals(0, broker.pendingCount());
        }
    }

    /** Resume 必须先恢复 WAITING_APPROVAL，再允许同一 approval ID 的持久决定唤醒 Tool。 */
    @Test
    void resumedApprovalReentersWaitingStateBeforeDecisionWake() throws Exception {
        AtomicReference<TurnState> durableState = new AtomicReference<>(TurnState.RUNNING);
        AtomicReference<TurnEvent> approvalEvent = new AtomicReference<>();
        AtomicReference<ApprovalDecision> resolvedEvent = new AtomicReference<>();
        List<List<ConversationRepository.Fact>> commits = new ArrayList<>();
        CountDownLatch waitingCommitted = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                execution("cfg_test").common(), "batch_fixture", "item_assistant", 0, 0, 0);
        AgentTool tool = new EditTool(executions);

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            broker.bindDecisionStore((approvalId, decision, resolvedAt) -> {
                assertEquals("appr_existing", approvalId);
                assertEquals(TurnState.WAITING_APPROVAL, durableState.get());
                durableState.set(TurnState.RUNNING);
                return true;
            });
            AgentToolRunner.Execution resumed = new AgentToolRunner.Execution(
                    plan(tool), Map.of("edit", tool), CancellationToken.none(),
                    () -> new TurnEvent.Context("evt_resume", "thr_test", "turn_test", 4, NOW),
                    () -> cursor,
                    (target, event, facts, nextExecution) -> {
                        durableState.set(target);
                        commits.add(List.copyOf(facts));
                        if (target == TurnState.WAITING_APPROVAL) {
                            approvalEvent.set(event);
                            waitingCommitted.countDown();
                        }
                    },
                    callId -> Optional.of(new ConversationRepository.PendingApproval(
                            "appr_existing", null, NOW.plus(Duration.ofMinutes(5)))),
                    callId -> Optional.of(binding(callId)),
                    () -> { },
                    (approvalId, decision) -> {
                        assertEquals(TurnState.RUNNING, durableState.get());
                        assertEquals("appr_existing", approvalId);
                        resolvedEvent.set(decision);
                    });
            AgentTool.Invocation call = new AgentTool.Invocation(
                    "call_resume", "edit", JsonObjects.builder().build(), 0);

            CompletableFuture<List<AgentTool.ToolResult>> result = CompletableFuture.supplyAsync(
                    () -> runner.execute(resumed, List.of(call)));
            assertTrue(waitingCommitted.await(1, TimeUnit.SECONDS));
            assertEquals(TurnState.WAITING_APPROVAL, durableState.get());
            TurnEvent.ApprovalRequested requested = assertInstanceOf(
                    TurnEvent.ApprovalRequested.class, approvalEvent.get());
            assertEquals("appr_existing", requested.approvalId());
            assertTrue(commits.getFirst().isEmpty(), "恢复不得重复插入 Approval 行");
            awaitPendingRegistration(broker);

            assertTrue(broker.resolve("appr_existing", ApprovalDecision.APPROVE, NOW));
            List<AgentTool.ToolResult> results = result.get(1, TimeUnit.SECONDS);
            assertEquals(ToolOutcome.SUCCEEDED, results.getFirst().outcome());
            assertEquals(1, executions.get());
            assertEquals(ApprovalDecision.APPROVE, resolvedEvent.get());
        }
    }

    /**
     * WAITING_APPROVAL 的持久提交与进程内 waiter 登记是相邻但不同的屏障；测试必须等待真实登记，
     * 避免调度器偶发抢占把被测的 persist-before-wake 语义误报为 Broker 竞态。
     */
    private static void awaitPendingRegistration(InMemoryApprovalBroker broker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (broker.pendingCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, broker.pendingCount(), "approval waiter must be registered before resolve");
    }

    /** 决定已提交而进程尚未执行 Tool 时，Resume 直接复用决定且不得重新登记审批。 */
    @Test
    void resumedCommittedDecisionExecutesToolWithoutSecondApproval() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger waitingTransitions = new AtomicInteger();
        List<TurnEvent> committedEvents = new ArrayList<>();
        List<List<ConversationRepository.Fact>> committedFacts = new ArrayList<>();
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                execution("cfg_test").common(), "batch_fixture", "item_assistant", 0, 0, 0);
        AgentTool tool = new EditTool(executions);

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            AgentToolRunner.Execution resumed = new AgentToolRunner.Execution(
                    plan(tool), Map.of("edit", tool), CancellationToken.none(),
                    () -> new TurnEvent.Context("evt_decided", "thr_test", "turn_test", 5, NOW),
                    () -> cursor,
                    (target, event, facts, nextExecution) -> {
                        if (target == TurnState.WAITING_APPROVAL) waitingTransitions.incrementAndGet();
                        if (event != null) committedEvents.add(event);
                        committedFacts.add(List.copyOf(facts));
                    },
                    callId -> Optional.of(new ConversationRepository.PendingApproval(
                            "appr_existing", ApprovalDecision.APPROVE, NOW.plus(Duration.ofMinutes(5)))),
                    callId -> Optional.of(binding(callId)),
                    () -> { }, (approvalId, decision) -> { });

            List<AgentTool.ToolResult> results = runner.execute(resumed, List.of(new AgentTool.Invocation(
                    "call_resume", "edit", JsonObjects.builder().build(), 0)));

            assertEquals(ToolOutcome.SUCCEEDED, results.getFirst().outcome());
            assertEquals(1, executions.get());
            assertEquals(0, waitingTransitions.get());
            assertEquals(0, broker.pendingCount());
            assertEquals(List.of(TurnEvent.ToolStarted.class, TurnEvent.ToolBatchCommitted.class),
                    committedEvents.stream().map(TurnEvent::getClass).toList());
            assertInstanceOf(ConversationRepository.ToolStartedFact.class,
                    committedFacts.getFirst().getFirst());
            assertInstanceOf(ConversationRepository.ToolResultFact.class,
                    committedFacts.get(1).getFirst());
        }
    }

    /** 失败 Tool 的写后 outside observer 只能在 ToolBatchCommitted 成功后进入终态冻结结果。 */
    @Test
    void freezesMutationObservationAfterFailedToolResultCommit() {
        AgentTool tool = new ObservedFailureTool(AgentTool.MutationObservationFailure.OUTSIDE_WORKSPACE);
        TurnExecutionPlan plan = plan(tool);

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            List<AgentTool.ToolResult> results = runner.execute(
                    runnerExecution(plan, tool, (target, event, facts, next) -> { }),
                    List.of(new AgentTool.Invocation("call_observed", "write",
                            JsonObjects.builder().build(), 0)));
            var frozen = plan.changeTracker().freeze();

            assertEquals(ToolOutcome.FAILED, results.getFirst().outcome());
            assertEquals(Set.of(TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE),
                    frozen.changeSet().incompleteReasons());
        }
    }

    /** observer 所属 Tool batch 未提交时不能发布 outside，必须收敛为 commit_unconfirmed。 */
    @Test
    void marksCommitUnconfirmedWhenObservedFailureResultCannotCommit() {
        AgentTool tool = new ObservedFailureTool(AgentTool.MutationObservationFailure.OUTSIDE_WORKSPACE);
        TurnExecutionPlan plan = plan(tool);
        AtomicInteger commits = new AtomicInteger();

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            AgentToolRunner.Execution execution = runnerExecution(plan, tool, (target, event, facts, next) -> {
                if (commits.incrementAndGet() == 2) throw new IllegalStateException("result commit failed");
            });

            assertThrows(IllegalStateException.class, () -> runner.execute(execution,
                    List.of(new AgentTool.Invocation("call_unconfirmed", "write",
                            JsonObjects.builder().build(), 0))));
            var frozen = plan.changeTracker().freeze();
            assertEquals(Set.of(TurnChangeSet.IncompleteReason.COMMIT_UNCONFIRMED),
                    frozen.changeSet().incompleteReasons());
        }
    }

    /**
     * 成功 write 的 receipt 必须在 ToolBatchCommitted 后进入 tracker，并由唯一终态 owner 冻结；
     * 运行期间不再物化预览或 wire 事件，任一参数异常仍应直接冒泡。
     */
    @Test
    void freezesSuccessfulReceiptAfterResultCommit() {
        AgentTool tool = new SuccessfulReceiptTool();
        TurnExecutionPlan plan = plan(tool, Path.of("\\\\?\\C:\\workspace"));
        AtomicInteger commits = new AtomicInteger();

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            AgentToolRunner.Execution execution = runnerExecution(plan, tool,
                    (target, event, facts, next) -> commits.incrementAndGet());

            List<AgentTool.ToolResult> results = runner.execute(execution, List.of(new AgentTool.Invocation(
                    "call_receipt", "write", JsonObjects.builder()
                            .putText("path", "C:/workspace/.ja-fixture/turn-change-review.txt")
                            .putText("content", "JA_TURN_CHANGE_REVISION_000").build(), 0)));
            var frozen = plan.changeTracker().freeze();

            assertEquals(ToolOutcome.SUCCEEDED, results.getFirst().outcome());
            assertEquals(2, commits.get());
            assertEquals(".ja-fixture/turn-change-review.txt",
                    frozen.changeSet().files().getFirst().path());
        }
    }

    /** 策略拒绝只提交失败结果，不伪造 started，也不得触达审批或真实 Tool。 */
    @Test
    void policyDenialCompletesWithoutStartingTool() {
        AtomicInteger executions = new AtomicInteger();
        AgentTool tool = new EditTool(executions);
        List<ExecutionObserver.Event> observed = new ArrayList<>();
        ToolPolicy deny = new ToolPolicy() {
            /** 返回稳定策略身份。 */
            @Override public String id() { return "deny"; }
            /** 固定拒绝，不读取测试参数。 */
            @Override public Decision evaluate(Context context) {
                return Decision.deny("TOOL_DENIED", "Tool denied by policy");
            }
        };
        ExecutionObserver observer = recordingObserver(
                "tool-lifecycle", Set.of(ExecutionObserver.EventKind.TOOL_STARTED,
                        ExecutionObserver.EventKind.TOOL_COMPLETED), observed, false);
        List<List<ConversationRepository.Fact>> commits = new ArrayList<>();
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                execution("cfg_test").common(), "batch_fixture", "item_assistant", 0, 0, 0);

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker, List.of(deny), List.of(observer))) {
            AgentToolRunner.Execution execution = new AgentToolRunner.Execution(
                    plan(tool), Map.of("edit", tool), CancellationToken.none(),
                    () -> new TurnEvent.Context("evt_policy", "thr_test", "turn_test", 4, NOW),
                    () -> cursor, (target, event, facts, next) -> commits.add(List.copyOf(facts)),
                    callId -> Optional.empty(),
                    callId -> Optional.of(binding(callId, AccessMode.FULL_ACCESS)),
                    () -> { }, (approvalId, decision) -> { });

            AgentTool.ToolResult result = runner.execute(execution, List.of(new AgentTool.Invocation(
                    "call_policy", "edit", JsonObjects.builder().build(), 0))).getFirst();

            assertEquals(ToolOutcome.FAILED, result.outcome());
            assertEquals("TOOL_DENIED", result.errorCode());
            assertEquals(0, executions.get());
            assertEquals(1, commits.size());
            ExecutionObserver.ToolCompleted completed = assertInstanceOf(
                    ExecutionObserver.ToolCompleted.class, observed.getFirst());
            assertEquals(ExecutionObserver.CompletionStatus.REJECTED, completed.status());
        }
    }

    /** 完成观察器即使抛错也不能覆盖已提交结果或阻止后续订阅者。 */
    @Test
    void observerFailureDoesNotOverrideCommittedToolResult() {
        AgentTool tool = new SuccessfulReceiptTool();
        List<ExecutionObserver.Event> healthyEvents = new ArrayList<>();
        ExecutionObserver broken = recordingObserver(
                "broken", Set.of(ExecutionObserver.EventKind.TOOL_COMPLETED), new ArrayList<>(), true);
        ExecutionObserver healthy = recordingObserver(
                "healthy", Set.of(ExecutionObserver.EventKind.TOOL_STARTED,
                        ExecutionObserver.EventKind.TOOL_COMPLETED), healthyEvents, false);
        AtomicInteger commits = new AtomicInteger();

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker, List.of(), List.of(broken, healthy))) {
            AgentTool.ToolResult result = runner.execute(
                    runnerExecution(plan(tool), tool,
                            (target, event, facts, next) -> commits.incrementAndGet()),
                    List.of(new AgentTool.Invocation(
                            "call_observer", "write", JsonObjects.builder().build(), 0))).getFirst();

            assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
            assertEquals(2, commits.get());
            assertEquals(List.of(ExecutionObserver.EventKind.TOOL_STARTED,
                            ExecutionObserver.EventKind.TOOL_COMPLETED),
                    healthyEvents.stream().map(ExecutionObserver.Event::kind).toList());
        }
    }

    /** Prompt 刷新失败必须在 Goal ledger 和 Tool result 已提交后抛出，且禁止执行后续 Tool。 */
    @Test
    void promptRefreshFailurePreservesGoalAndToolSettlement() {
        AgentTool tool = new SuccessfulReceiptTool();
        RuntimeException refreshFailure = new IllegalStateException("prompt refresh failed");
        TurnExecutionPlan plan = plan(tool, Path.of("C:/workspace"),
                new FailingAfterToolPromptSession(refreshFailure));
        List<String> goalTrace = new ArrayList<>();
        List<List<ConversationRepository.Fact>> commits = new ArrayList<>();

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            runner.bindGoalTools(goalLedger(goalTrace));
            AgentToolRunner.Execution baseExecution = runnerExecution(plan, tool,
                    (target, event, facts, next) -> commits.add(List.copyOf(facts)));
            AgentToolRunner.Execution execution = new AgentToolRunner.Execution(baseExecution.command(),
                    baseExecution.catalog(), baseExecution.cancellation(), baseExecution.draftContext(),
                    baseExecution.cursor(), baseExecution.writer(), baseExecution.approvalLookup(),
                    baseExecution.bindingLookup(), baseExecution.refreshAuthority(), baseExecution.resolvedPublisher(),
                    baseExecution.collaborationMode(), goalLedger(goalTrace));

            RuntimeException thrown = assertThrows(RuntimeException.class, () -> runner.execute(execution,
                    List.of(new AgentTool.Invocation(
                                    "call_refresh", "write", JsonObjects.builder().build(), 0),
                            new AgentTool.Invocation(
                                    "call_never", "write", JsonObjects.builder().build(), 1))));

            assertSame(refreshFailure, thrown);
            assertEquals(List.of("prepare", "start", "settle"), goalTrace);
            assertEquals(2, commits.size());
            assertTrue(commits.get(1).stream().anyMatch(
                    ConversationRepository.ToolResultFact.class::isInstance));
        }
    }

    /** 可信内建提问是控制面暂停，不得在 InteractionSuspendedException 后留下 Goal attempt。 */
    @Test
    void trustedInternalInteractionDoesNotOpenGoalLedgerAttempt() {
        List<String> goalTrace = new ArrayList<>();
        AgentTool interaction = new AgentTool() {
            /** 返回最小固定 schema，测试只覆盖挂起前的 ledger 边界。 */
            @Override public ToolSpec spec() {
                return new ToolSpec("write", "interaction fixture", JsonObjects.builder().build());
            }

            /** 提问没有项目或外部副作用。 */
            @Override public ToolSideEffect sideEffect() { return ToolSideEffect.READ_ONLY; }

            /** 控制面 Tool 不获取工作区写租约。 */
            @Override public WorkspaceMutationMode workspaceMutationMode() { return WorkspaceMutationMode.NONE; }

            /** 只有显式可信标记才能使用该无审批边界。 */
            @Override public AgentTool.ApprovalRequirement approvalRequirement() {
                return AgentTool.ApprovalRequirement.TRUSTED_INTERNAL;
            }

            /** 复现真实 request_user_input 的挂起控制流。 */
            @Override public CompletionStage<ToolResult> execute(
                    Invocation invocation, ExecutionContext context, CancellationToken token) {
                InteractionQuestion question = new InteractionQuestion("question_fixture", "Choose one",
                        InteractionQuestionType.SINGLE,
                        List.of(new InteractionOption("option_fixture", "A", "A", false)), true, true);
                throw new InteractionSuspendedException(new InteractionRequest(
                        "interaction_fixture", "thr_test", "turn_test", invocation.callId(), null, null, null,
                        invocation.callId(), List.of(question), InteractionStatus.PENDING, List.of(), 0, NOW, NOW));
            }
        };

        try (InMemoryApprovalBroker broker = new InMemoryApprovalBroker(
                CLOCK, 8, 32, Duration.ofMinutes(10));
             AgentToolRunner runner = runner(broker)) {
            assertThrows(InteractionSuspendedException.class, () -> runner.execute(
                    runnerExecution(plan(interaction), interaction,
                            (target, event, facts, next) -> { }, goalLedger(goalTrace)),
                    List.of(new AgentTool.Invocation("call_interaction", "write",
                            JsonObjects.builder().build(), 0))));
            assertTrue(goalTrace.isEmpty());
        }
    }

    /** 构造按指定类型订阅的观察器，事件列表不记录任何请求正文或 Tool 参数。 */
    private static ExecutionObserver recordingObserver(
            String id, Set<ExecutionObserver.EventKind> subscriptions,
            List<ExecutionObserver.Event> events, boolean fail) {
        return new ExecutionObserver() {
            /** 返回稳定观察器身份。 */
            @Override public String id() { return id; }
            /** 返回固定订阅集合。 */
            @Override public Set<EventKind> subscriptions() { return subscriptions; }
            /** 记录安全事件，并按夹具要求触发可隔离故障。 */
            @Override public void observe(Event event) {
                events.add(event);
                if (fail) throw new IllegalStateException("observer failed");
            }
        };
    }

    /** 构造最小 Goal ledger，调用次序直接证明刷新失败发生在核心结算之后。 */
    private static GoalToolExecutionPort goalLedger(List<String> trace) {
        return new GoalToolExecutionPort() {
            /** 每次调用创建固定 opaque attempt。 */
            @Override public Optional<Attempt> prepare(Prepare request) {
                trace.add("prepare");
                return Optional.of(new Attempt("attempt_fixture"));
            }
            /** started 事实提交后记录越过执行边界。 */
            @Override public void start(Attempt attempt, Instant at) { trace.add("start"); }
            /** Tool 返回后记录最终 Goal 结算。 */
            @Override public void settle(Attempt attempt, Settlement settlement) { trace.add("settle"); }
        };
    }

    /** 构造无审批、固定 cursor 的最小生产 Execution，测试只替换 durable writer。 */
    private static AgentToolRunner.Execution runnerExecution(
            TurnExecutionPlan plan, AgentTool tool, AgentToolRunner.DurableWriter writer) {
        return runnerExecution(plan, Map.of("write", tool), writer);
    }

    /** 构造指定目录的生产 Execution，使搜索与真实 MCP 调用可在同一批次验证审批边界。 */
    private static AgentToolRunner.Execution runnerExecution(
            TurnExecutionPlan plan, Map<String, AgentTool> catalog, AgentToolRunner.DurableWriter writer) {
        return runnerExecution(plan, catalog, writer, GoalToolExecutionPort.disabled());
    }

    /** 为 ledger 边界用例显式注入测试端口，避免依赖 Runner 的全局可变绑定。 */
    private static AgentToolRunner.Execution runnerExecution(
            TurnExecutionPlan plan, AgentTool tool, AgentToolRunner.DurableWriter writer,
            GoalToolExecutionPort goalTools) {
        return runnerExecution(plan, Map.of("write", tool), writer, goalTools);
    }

    /** 冻结指定 Goal ledger 到单次 Execution，复现生产 TurnExecution 的身份快照。 */
    private static AgentToolRunner.Execution runnerExecution(
            TurnExecutionPlan plan, Map<String, AgentTool> catalog, AgentToolRunner.DurableWriter writer,
            GoalToolExecutionPort goalTools) {
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                execution("cfg_test").common(), "batch_fixture", "item_assistant", 0, 0, 0);
        return new AgentToolRunner.Execution(plan, catalog, CancellationToken.none(),
                () -> new TurnEvent.Context("evt_observed", "thr_test", "turn_test", 4, NOW),
                () -> cursor, writer, callId -> Optional.empty(),
                callId -> Optional.of(binding(callId, AccessMode.FULL_ACCESS)), () -> { },
                (approvalId, decision) -> { },
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT, goalTools);
    }

    /** 构造审批发现用例的真实绑定解析，避免用 Builtin 占位身份掩盖 MCP 权限判断。 */
    private static AgentToolRunner.Execution runnerExecutionWithBindings(
            TurnExecutionPlan plan, Map<String, AgentTool> catalog,
            Map<String, ConversationRepository.ToolBinding> bindings,
            AgentToolRunner.DurableWriter writer) {
        TurnExecutionState.Tools cursor = new TurnExecutionState.Tools(
                execution("cfg_test").common(), "batch_fixture", "item_assistant", 0, 0, 0);
        return new AgentToolRunner.Execution(plan, catalog, CancellationToken.none(),
                () -> new TurnEvent.Context("evt_discovery_approval", "thr_test", "turn_test", 4, NOW),
                () -> cursor, writer, callId -> Optional.empty(),
                callId -> Optional.ofNullable(bindings.get(callId)), () -> { },
                (approvalId, decision) -> { });
    }

    /** 从 Tool 的实际 descriptor 构造审批所需的持久绑定，并显式固定本测试权限模式。 */
    private static ConversationRepository.ToolBinding toolBinding(String callId, AgentTool tool) {
        AgentTool.ToolBindingDescriptor descriptor = tool.bindingDescriptor();
        return new ConversationRepository.ToolBinding("batch_fixture", callId,
                descriptor.routeKind(), descriptor.localName(), descriptor.serverId(), descriptor.remoteName(),
                descriptor.schemaHash(), descriptor.routeHash(), "c".repeat(64),
                AccessMode.APPROVAL_REQUIRED);
    }

    /** 构造需要逐次审批的冻结 Turn 计划，审批只能由 Runner 内核处理。 */
    private static TurnExecutionPlan plan(AgentTool tool) {
        return plan(tool, Path.of("C:/workspace"));
    }

    /** 允许 Windows Native 回归替换 namespaced root，其余 Turn 事实保持与通用夹具完全一致。 */
    private static TurnExecutionPlan plan(AgentTool tool, Path workspaceRoot) {
        return plan(tool, workspaceRoot,
                new FixedAgentPromptSession(ContextBudget.capabilities(100_000, 8_192, true)));
    }

    /** 注入 Prompt Session 仅用于验证结果提交与规则刷新之间的失败边界。 */
    private static TurnExecutionPlan plan(
            AgentTool tool, Path workspaceRoot, AgentPromptSession promptSession) {
        ModelPort.ModelConfiguration model = new ModelPort.ModelConfiguration(
                "provider_test", "model_test", "cfg_test", ModelPort.Api.OPENAI_RESPONSES, "gpt-test", URI.create("https://example.invalid/v1"),
                "test-only", Duration.ofSeconds(5), Duration.ofSeconds(30),
                Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
        return new TurnExecutionPlan(
                "thr_test", "turn_test", workspaceRoot,
                new UserContent(List.of(new TextContent("resume"))), model,
                AccessMode.APPROVAL_REQUIRED, TurnLimits.defaults(), NOW, "ws_test", 4, 4,
                "", promptSession,
                QueuedInputBoundary.plainTextOnly(),
                request -> { throw new AssertionError("attachment read is outside this test"); },
                List.of(tool), "cfg_test", AgentToolRunnerTest::emptyToolSession,
                new ToolProjectionLimits(64_000, 16_000), List.of("test-only"),
                NOW.plus(TurnLimits.defaults().wallTimeout()),
                (common, summary) -> { throw new AssertionError("runtime refresh is outside Tool runner test"); });
    }

    /** 返回不含 MCP Tool 的独立会话，计划构造仍保持生产端口完整。 */
    private static TurnToolSessionFactory.Session emptyToolSession(CancellationToken ignored) {
        return new TurnToolSessionFactory.Session() {
            /** 恢复审批用例只执行内建 edit Tool。 */
            @Override public List<AgentTool> tools() { return List.of(); }
            /** 空会话不持有外部资源。 */
            @Override public void close() { }
        };
    }

    /** 使用完整冻结绑定驱动恢复测试，避免从当前 Tool 目录重建权限或路由身份。 */
    private static ConversationRepository.ToolBinding binding(String callId) {
        return binding(callId, AccessMode.APPROVAL_REQUIRED);
    }

    /** 非审批聚焦测试显式冻结 full_access，证明审批是否发生不再由可选扩展决定。 */
    private static ConversationRepository.ToolBinding binding(String callId, AccessMode accessMode) {
        return new ConversationRepository.ToolBinding("batch_fixture", callId,
                AgentTool.RouteKind.BUILTIN, "edit", "builtin", "edit",
                "0".repeat(64), "1".repeat(64), "2".repeat(64),
                accessMode);
    }

    /** 仅让 afterTool 失败，其余行为委托稳定 Prompt 夹具，精确复现刷新异常。 */
    private static final class FailingAfterToolPromptSession implements AgentPromptSession {
        private final RuntimeException failure;
        private final AgentPromptSession delegate = new FixedAgentPromptSession(
                ContextBudget.capabilities(100_000, 8_192, true));

        /** 保存要原样抛出的异常，测试可验证 Runner 不替换失败身份。 */
        private FailingAfterToolPromptSession(RuntimeException failure) {
            this.failure = failure;
        }

        /** 准备请求仍使用稳定快照。 */
        @Override public PreparedPrompt prepare(String summary, List<ToolSpec> tools) {
            return delegate.prepare(summary, tools);
        }

        /** Tool 前置门禁保持放行，只测试完成后的刷新故障。 */
        @Override public ToolGuard beforeTool(
                AgentTool.Invocation invocation, ToolSideEffect sideEffect, String batchRevision) {
            return delegate.beforeTool(invocation, sideEffect, batchRevision);
        }

        /** 模拟规则刷新失败，异常由 Runner 在核心结算后原样抛出。 */
        @Override public void afterTool(AgentTool.Invocation invocation, AgentTool.ToolResult result) {
            throw failure;
        }

        /** Skill 激活不属于此用例，保持委托语义。 */
        @Override public SkillActivation activateSkill(SkillCatalog.SkillDocument document) {
            return delegate.activateSkill(document);
        }

        /** Skill 身份校验不属于此用例，保持委托语义。 */
        @Override public void validateSkillReferences(List<String> skillIds) {
            delegate.validateSkillReferences(skillIds);
        }

        /** 两阶段 Skill 替换不属于此用例，保持委托语义。 */
        @Override public SkillReplacement prepareSkillReplacement(List<String> skillIds) {
            return delegate.prepareSkillReplacement(skillIds);
        }

        /** 返回委托 Session 当前 revision，供结果事务冻结真实 Prompt 状态。 */
        @Override public String currentRevision() { return delegate.currentRevision(); }

        /** 返回委托 Session 当前稳定 Skill 引用。 */
        @Override public List<TurnExecutionState.ActiveSkill> activeSkillReferences() {
            return delegate.activeSkillReferences();
        }

        /** 恢复行为不属于此用例，保持委托语义。 */
        @Override public void restoreActiveSkills(
                String summary, List<TurnExecutionState.ActiveSkill> references) {
            delegate.restoreActiveSkills(summary, references);
        }
    }

    /** MCP 搜索审批回归使用的最小远端 Tool，名称可切换为伪装的 tool_search。 */
    private static final class SearchMcpTool implements AgentTool {
        private final ToolSpec spec;
        private final ToolBindingDescriptor binding;
        private final AtomicInteger executions = new AtomicInteger();

        /** 只提供空对象参数，测试重点是路由类型和审批边界而不是业务 Schema。 */
        private SearchMcpTool(String name) {
            this.spec = new ToolSpec(name, "MCP fixture tool",
                    JsonObjects.builder().putText("type", "object")
                            .putBoolean("additionalProperties", false).build());
            this.binding = new ToolBindingDescriptor(RouteKind.MCP, name, "fixture_mcp", name,
                    "a".repeat(64), "b".repeat(64));
        }

        /** 返回固定 MCP Tool 描述，避免测试通过 Builtin 默认路由误判。 */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /** 显式声明远端 MCP 路由，覆盖按类型而非名称的审批豁免。 */
        @Override
        public ToolBindingDescriptor bindingDescriptor() {
            return binding;
        }

        /** 记录真正越过审批边界的调用；拒绝路径必须保持零执行。 */
        @Override
        public CompletionStage<ToolResult> execute(Invocation invocation, ExecutionContext context,
                                                    CancellationToken cancellationToken) {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolResult.success("mcp-fixture"));
        }
    }

    /** 记录真正执行次数，证明审批未提交前不会越过外部副作用边界。 */
    private static final class EditTool implements AgentTool {
        private final AtomicInteger executions;
        private final ToolSpec spec = new ToolSpec(
                "edit", "edit", JsonObjects.builder().putText("type", "object").build());

        /** 注入执行计数器，使测试能区分审批唤醒与 Tool 结算。 */
        private EditTool(AtomicInteger executions) {
            this.executions = executions;
        }

        /** 返回固定 Schema，确保 Middleware 将其识别为需要审批的外部 Tool。 */
        @Override public ToolSpec spec() { return spec; }

        /** 仅在 Broker 已完成审批后由 Runner 调用一次。 */
        @Override
        public CompletionStage<ToolResult> execute(
                Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolResult.success("ok"));
        }
    }

    /** 用必填文本字段锁定 Runner 统一校验发生在 Tool 执行之前。 */
    private static final class RequiredTextTool implements AgentTool {
        private final AtomicInteger executions;
        private final ToolSpec spec = new ToolSpec("write", "write",
                JsonObjects.builder()
                        .putText("type", "object")
                        .put("properties", JsonObjects.builder()
                                .put("text", JsonObjects.builder().putText("type", "string").build())
                                .build())
                        .put("required", new JsonArray(List.of(new JsonText("text"))))
                        .putBoolean("additionalProperties", false)
                        .build());

        /** 保存执行计数器，以证明参数错误不会越过副作用边界。 */
        private RequiredTextTool(AtomicInteger executions) {
            this.executions = executions;
        }

        /** 返回包含必填字段的生产形状 Schema。 */
        @Override public ToolSpec spec() { return spec; }

        /** 被调用即代表 Runner 校验顺序回归，计数用于给出直接证据。 */
        @Override
        public CompletionStage<ToolResult> execute(
                Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolResult.success("unexpected"));
        }
    }

    /** 模拟适配器已脱敏的写后观察失败，不携带 receipt、路径或正文。 */
    private static final class ObservedFailureTool implements AgentTool {
        private final MutationObservationFailure observation;
        private final ToolSpec spec = new ToolSpec(
                "write", "write", JsonObjects.builder().putText("type", "object").build());

        /** 固定单一闭集原因，使 Runner 测试不依赖真实文件系统竞态。 */
        private ObservedFailureTool(MutationObservationFailure observation) {
            this.observation = observation;
        }

        /** 返回写 Tool 身份以走真实持久 Tool batch 路径。 */
        @Override public ToolSpec spec() { return spec; }

        /** 精确写失败必须由 observer 决定 partial，不能按普通失败静默忽略。 */
        @Override public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.EXACT_TEXT;
        }

        /** 返回不含 Workspace 数据的失败结果，模拟 BuiltInTools 公共异常边界。 */
        @Override
        public CompletionStage<ToolResult> execute(
                Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
            return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.FAILED,
                    "Tool failed safely.", Optional.empty(), "TOOL_IO_FAILED",
                    Optional.empty(), Optional.of(observation)));
        }
    }

    /** 返回与 Native 首个 write 等价的精确收据，文件 IO 已由 BuiltInTools 独立回归覆盖。 */
    private static final class SuccessfulReceiptTool implements AgentTool {
        private final ToolSpec spec = new ToolSpec(
                "write", "write", JsonObjects.builder().putText("type", "object").build());

        /** 成功收据只允许由 EXACT_TEXT Tool 进入 tracker。 */
        @Override public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.EXACT_TEXT;
        }

        /** 返回固定 nested 文件身份，使 Runner 测试不依赖外部目录状态。 */
        @Override public ToolSpec spec() { return spec; }

        /** 收据同时携带 Workspace 证明，复现生产 write 的提交后路径。 */
        @Override
        public CompletionStage<ToolResult> execute(
                Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
            Path target = Path.of("C:/workspace/.ja-fixture/turn-change-review.txt");
            AgentTool.MutationReceipt receipt = AgentTool.MutationReceipt.confined(
                    Path.of("C:/workspace"), ".ja-fixture/turn-change-review.txt", target,
                    false, "", true, "JA_TURN_CHANGE_REVISION_000");
            return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.SUCCEEDED,
                    "File written successfully.", Optional.empty(), null, Optional.of(receipt)));
        }
    }
}
