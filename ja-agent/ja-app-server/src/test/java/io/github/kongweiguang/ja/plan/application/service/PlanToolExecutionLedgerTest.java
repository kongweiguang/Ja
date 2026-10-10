// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Plan ledger 只构造 Core 同阶段持久化的事实，不在 Tool hook 中写业务数据库。 */
final class PlanToolExecutionLedgerTest {
    private static final Instant NOW = Instant.parse("2026-09-26T01:00:00Z");

    /** 准备、执行与结果阶段使用同一 callKey/attempt，成功结果也只形成待原子应用的 facts。 */
    @Test
    void emitsStableAttemptFactsForEveryDurableToolBoundary() {
        AgentIdentity identity = identity("turn_plan");
        PlanRepository repository = repository(turnId -> Optional.of(planBinding(turnId, "run_plan")));
        PlanToolExecutionLedger ledger = new PlanToolExecutionLedger(repository, 9);
        AgentTool.Invocation invocation = invocation("call_plan", 2);
        JaTool.ExecutionContext context = context(identity, "call_plan", TurnOrigin.PLAN_EXECUTION,
                "revision_one", "run_plan");

        AgentTool.ExtensionFact prepared = ledger.prepared(invocation, context).getFirst();
        AgentTool.ExtensionFact started = ledger.started(invocation, context).getFirst();
        AgentTool.ExtensionFact settled = ledger.settled(invocation, context,
                new JaTool.ToolResult(ToolOutcome.SUCCEEDED, "ok", Optional.empty(), null)).getFirst();

        assertEquals("ja.plan", prepared.namespace());
        assertEquals("tool-attempt", prepared.type());
        assertEquals(1, prepared.schemaVersion());
        String attemptId = text(prepared.payload(), "attemptId");
        assertEquals(attemptId, text(started.payload(), "attemptId"));
        assertEquals(attemptId, text(settled.payload(), "attemptId"));
        assertEquals("PREPARED", text(prepared.payload(), "stage"));
        assertEquals("STARTED", text(started.payload(), "stage"));
        assertEquals("SETTLED", text(settled.payload(), "stage"));
        assertEquals("revision_one", text(settled.payload(), "planRevisionId"));
        assertEquals("run_plan", text(settled.payload(), "runId"));
        assertEquals("SUCCEEDED", text(settled.payload(), "resultOutcome"));
    }

    /** 普通 USER Tool 不查 Plan 狀态也不产生 Plan ledger 事实。 */
    @Test
    void leavesOrdinaryUserToolsOutsidePlanLedger() {
        AtomicInteger reads = new AtomicInteger();
        PlanToolExecutionLedger ledger = new PlanToolExecutionLedger(repository(turnId -> {
            reads.incrementAndGet();
            return Optional.empty();
        }), 9);
        AgentIdentity identity = identity("turn_user");
        AgentTool.Invocation invocation = invocation("call_user", 0);

        assertTrue(ledger.prepared(invocation, context(identity, "call_user", TurnOrigin.USER,
                null, null)).isEmpty());
        assertEquals(0, reads.get());
    }

    /** 旧 Run 或被替换的 Plan revision 不得生成可以落库的调用意图。 */
    @Test
    void rejectsInternalCallWhenFrozenBindingChanged() {
        AgentIdentity identity = identity("turn_plan");
        PlanToolExecutionLedger ledger = new PlanToolExecutionLedger(
                repository(turnId -> Optional.of(planBinding(turnId, "run_replaced"))), 9);
        AgentTool.Invocation invocation = invocation("call_plan", 0);
        JaTool.ExecutionContext context = context(identity, "call_plan", TurnOrigin.PLAN_EXECUTION,
                "revision_one", "run_plan");

        assertThrows(IllegalStateException.class, () -> ledger.prepared(invocation, context));
    }

    /** 已确认取消仍记录真实取消结果，不能把副作用结算伪装成普通失败。 */
    @Test
    void preservesCancelledToolOutcomeInTheSettledFact() {
        AgentIdentity identity = identity("turn_plan");
        PlanToolExecutionLedger ledger = new PlanToolExecutionLedger(
                repository(turnId -> Optional.of(planBinding(turnId, "run_plan"))), 9);
        AgentTool.Invocation invocation = invocation("call_cancelled", 0);
        JaTool.ExecutionContext context = context(identity, "call_cancelled", TurnOrigin.PLAN_EXECUTION,
                "revision_one", "run_plan");

        AgentTool.ExtensionFact settled = ledger.settled(invocation, context,
                new JaTool.ToolResult(ToolOutcome.CANCELLED, "", Optional.empty(), "CANCELLED")).getFirst();

        assertEquals("CANCELLED", text(settled.payload(), "resultOutcome"));
        assertTrue(text(settled.payload(), "resultDigest").matches("[0-9a-f]{64}"));
    }

    /** Plan repository 只允许 ledger 查询持久 internal binding，任何写方法都让聚焦测试失败。 */
    private static PlanRepository repository(java.util.function.Function<String,
            Optional<PlanRepository.InternalTurnBinding>> bindings) {
        return (PlanRepository) java.lang.reflect.Proxy.newProxyInstance(PlanRepository.class.getClassLoader(),
                new Class<?>[]{PlanRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findInternalTurnBinding")) {
                        return bindings.apply((String) args[0]);
                    }
                    throw new AssertionError("Unexpected Plan repository call: " + method.getName());
                });
    }

    /** 构造身份与 owner binding 对齐的 Plan continuation。 */
    private static PlanRepository.InternalTurnBinding planBinding(String turnId, String runId) {
        return new PlanRepository.InternalTurnBinding(turnId, TurnOrigin.PLAN_EXECUTION.name(),
                "plan_one", runId, "revision_one", "a".repeat(64));
    }

    /** Tool call identity 在 app/core 两侧复用同一个 AgentIdentity 计算规则。 */
    private static AgentIdentity identity(String turnId) {
        return new AgentIdentity("ja.local", "thr_owner", turnId, "user_local");
    }

    /** Ja Tool 上下文冻结所有身份，测试不依赖线程局部或运行时配置。 */
    private static JaTool.ExecutionContext context(AgentIdentity identity, String callId, TurnOrigin origin,
                                                   String planRevisionId, String runId) {
        return new JaTool.ExecutionContext(identity, callId, identity.toolCallKey(callId),
                identity.threadId(), identity.turnId(), Path.of("C:/workspace"), AccessMode.FULL_ACCESS,
                "cfg_test", NOW.plusSeconds(60), "ws_test", planRevisionId, runId, origin);
    }

    /** Tool invocation 的 callId/ordinal 与被冻结上下文一致。 */
    private static AgentTool.Invocation invocation(String callId, int ordinal) {
        return new AgentTool.Invocation(callId, "shell", JsonObjects.builder().putText("cmd", "pwd").build(), ordinal);
    }

    /** JSON 字符串断言不接受 coercion，确保 ledger 的持久 schema 可被严格比较。 */
    private static String text(JsonObject payload, String field) {
        assertTrue(payload.get(field) instanceof JsonText, field + " should be text");
        return ((JsonText) payload.get(field)).value();
    }
}

