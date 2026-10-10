// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定中间件扩展状态的 namespace、revision、不可变快照和可恢复 patch 契约。 */
final class AgentMiddlewareTest {
    /** Patch 只改指定顶层字段，并保留未触及状态；两类集合在创建时都必须防御性复制。 */
    @Test
    void appliesIsolatedPatchAgainstItsExpectedRevision() {
        AgentMiddleware.ExtensionStateView state = state(4, Map.of(
                "mode", new JsonText("safe"), "count", new JsonText("one")));
        Map<String, io.github.kongweiguang.ja.agent.domain.json.JsonValue> put = new LinkedHashMap<>();
        put.put("mode", new JsonText("strict"));
        put.put("enabled", new JsonText("yes"));
        Set<String> remove = new LinkedHashSet<>(Set.of("count"));

        AgentMiddleware.ExtensionStatePatch patch =
                new AgentMiddleware.ExtensionStatePatch(4, put, remove);
        put.clear();
        remove.clear();

        assertEquals(new JsonObject(Map.of("mode", new JsonText("strict"),
                "enabled", new JsonText("yes"))), patch.apply(state));
        assertThrows(IllegalStateException.class, () -> patch.apply(state(5, Map.of())));
    }

    /** 全量替换会生成最小差异；相同快照返回空 patch，避免没有状态变化的持久写入。 */
    @Test
    void replacementOmitsUnchangedValues() {
        AgentMiddleware.ExtensionStateView state = state(2, Map.of(
                "same", new JsonText("value"), "obsolete", new JsonText("old")));

        AgentMiddleware.ExtensionStatePatch patch = AgentMiddleware.ExtensionStatePatch.replace(state,
                new JsonObject(Map.of("same", new JsonText("value"), "fresh", new JsonText("new"))));

        assertEquals(Set.of("fresh"), patch.put().keySet());
        assertEquals(Set.of("obsolete"), patch.remove());
        assertEquals(new JsonObject(Map.of("same", new JsonText("value"), "fresh", new JsonText("new"))),
                patch.apply(state));
        assertTrue(AgentMiddleware.ExtensionStatePatch.replace(state, state.value()).isEmpty());
    }

    /** 状态输入以 middleware ID 作为命名空间，且禁止 revision/schema 的未初始化值。 */
    @Test
    void stateSnapshotValidatesItsIdentityAndVersion() {
        assertEquals("request-policy", state(0, Map.of()).namespace());
        assertThrows(IllegalArgumentException.class,
                () -> new AgentMiddleware.ExtensionStateView("request-policy", -1, 1, JsonObject.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentMiddleware.ExtensionStateView("request-policy", 0, 0, JsonObject.empty()));
    }

    /** 决策对象复制事实列表并拒绝空事实，确保提交内容在 hook 返回后保持稳定。 */
    @Test
    void extensionDecisionFreezesFacts() {
        AgentMiddleware.ExtensionStateView state = state(0, Map.of());
        List<AgentMiddleware.MiddlewareFact> facts = new java.util.ArrayList<>();
        facts.add(new AgentMiddleware.MiddlewareFact("audit", 1, JsonObject.empty()));

        AgentMiddleware.ExtensionDecision<String> decision = new AgentMiddleware.ExtensionDecision<>(
                "continue", AgentMiddleware.ExtensionStatePatch.unchanged(state), facts);
        facts.clear();

        assertEquals(1, decision.facts().size());
        assertThrows(UnsupportedOperationException.class, () -> decision.facts().clear());
        assertTrue(decision.statePatch().isEmpty());
        assertThrows(NullPointerException.class, () -> new AgentMiddleware.MiddlewareFact("audit", 1, null));
    }

    /** 结果 hook 读取工具同事务写入的命名空间事实，且拿到的事实列表不能被回调方改写。 */
    @Test
    void toolResultContextFreezesCommittedExtensionFacts() {
        AgentIdentity identity = new AgentIdentity("tenant_test", "thread_test", "turn_test", "user_test");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_test", "lookup", JsonObject.empty(), 0);
        AgentMiddleware.ExtensionStateView state = state(0, Map.of());
        List<AgentTool.ExtensionFact> facts = new java.util.ArrayList<>();
        facts.add(new AgentTool.ExtensionFact("ja.tool", "receipt", 1, JsonObject.empty()));
        AgentMiddleware.ToolResultContext context = new AgentMiddleware.ToolResultContext(identity, invocation,
                ToolOutcome.SUCCEEDED, true, "result", Optional.empty(), facts, state);
        facts.clear();

        assertTrue(context.executionStarted());
        assertEquals(1, context.extensionFacts().size());
        assertThrows(UnsupportedOperationException.class, () -> context.extensionFacts().clear());
    }

    /** Patch 冲突属于调用契约错误，不能通过先写后删的顺序把歧义带进存储。 */
    @Test
    void rejectsConflictingPatchOperations() {
        assertThrows(IllegalArgumentException.class, () -> new AgentMiddleware.ExtensionStatePatch(
                1, Map.of("same", new JsonText("value")), Set.of("same")));
        assertThrows(IllegalArgumentException.class, () -> new AgentMiddleware.ExtensionStatePatch(
                1, Map.of("", new JsonText("value")), Set.of()));
    }

    /** 四个默认拦截点都返回无操作决策，确保新持久态能力对既有扩展保持显式且轻量的默认行为。 */
    @Test
    void defaultHooksReturnDecisionAndEmptyStateChanges() {
        AgentIdentity identity = new AgentIdentity("tenant_test", "thread_test", "turn_test", "user_test");
        AgentMiddleware middleware = () -> "request-policy";
        AgentMiddleware.ExtensionStateView state = state(0, Map.of());
        AgentMiddleware.RequestContext request = new AgentMiddleware.RequestContext(
                identity, "", List.of(), List.of(), state);
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_test", "lookup", JsonObject.empty(), 0);
        AgentTool.ExecutionContext execution = new AgentTool.ExecutionContext(
                identity, identity.toolCallKey(invocation.callId()), Map.of());

        AgentMiddleware.ExtensionDecision<AgentMiddleware.RequestPatch> beforeRequest =
                middleware.beforeRequest(request);
        AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolDecision> beforeTool = middleware.beforeTool(
                new AgentMiddleware.ToolContext(identity, execution, invocation, ToolSideEffect.READ_ONLY,
                        AgentTool.ApprovalRequirement.NOT_REQUIRED, state));
        AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolResultProjection> result = middleware.projectToolResult(
                new AgentMiddleware.ToolResultContext(identity, invocation, ToolOutcome.SUCCEEDED,
                        false, "result", Optional.empty(), List.of(), state));
        AgentMiddleware.ExtensionDecision<AgentMiddleware.CompletionDecision> completion =
                middleware.beforeCompletion(new AgentMiddleware.CompletionContext(identity, "done", false, false, state));

        assertEquals(AgentMiddleware.RequestPatch.unchanged(), beforeRequest.decision());
        assertEquals(new AgentMiddleware.ToolProceed(invocation.arguments()), beforeTool.decision());
        assertEquals(new AgentMiddleware.ToolResultProjection("result", Optional.empty()), result.decision());
        assertEquals(new AgentMiddleware.Complete(), completion.decision());
        for (AgentMiddleware.ExtensionDecision<?> decision : List.of(beforeRequest, beforeTool, result, completion)) {
            assertTrue(decision.statePatch().isEmpty());
            assertEquals(List.of(), decision.facts());
        }
    }

    /** 构造一个只读策略快照，避免测试对默认拦截点引入额外状态。 */
    private static AgentMiddleware.ExtensionStateView state(
            long revision, Map<String, ? extends JsonValue> value) {
        return new AgentMiddleware.ExtensionStateView("request-policy", revision, 1, new JsonObject(value));
    }

    /** 防止测试容器实例化契约测试类。 */
    private AgentMiddlewareTest() {
    }
}
