// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** 验证澄清策略将冻结问题和 Plan owner 一并交给 Core 持久等待。 */
final class JaClarificationWaitMiddlewareTest {
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    /** Plan owner 与规范化题目一并进入 wait payload，恢复无需从进程内状态重建。 */
    @Test
    void freezesPlanOwnerAndCanonicalQuestionsInCoreWait() {
        TurnRuntimeRequest turn = turn(TurnOrigin.PLAN_EXECUTION, "plan_revision_test", "run_test");
        JaClarificationWaitMiddleware middleware = new JaClarificationWaitMiddleware(
                turn, Set.of("request_user_input"));
        AgentMiddleware.ToolContext context = toolContext(middleware, "request_user_input", questionArguments());

        AgentMiddleware.ToolWait wait = assertInstanceOf(AgentMiddleware.ToolWait.class,
                middleware.beforeTool(context).decision());

        assertEquals("clarification", wait.request().kind());
        JsonObject payload = wait.request().payload();
        assertEquals("question_color", text(payload, "questions", 0, "questionId"));
        assertEquals("SINGLE", text(payload, "questions", 0, "type"));
        JsonObject owner = assertInstanceOf(JsonObject.class, payload.get("ja.owner"));
        assertEquals("plan_revision_test", ((JsonText) owner.get("planRevisionId")).value());
        assertEquals("run_test", ((JsonText) owner.get("runId")).value());
        assertEquals(Set.of("planRevisionId", "runId"), owner.members().keySet());
    }

    /** 普通 Turn 仍明确持久化两项空 Plan owner，不能把缺字段解释为另一种身份。 */
    @Test
    void writesExplicitEmptyOwnerForOrdinaryTurn() {
        JaClarificationWaitMiddleware middleware = new JaClarificationWaitMiddleware(
                turn(TurnOrigin.USER, null, null), Set.of("request_user_input"));

        AgentMiddleware.ToolWait wait = assertInstanceOf(AgentMiddleware.ToolWait.class,
                middleware.beforeTool(toolContext(middleware, "request_user_input", questionArguments()))
                        .decision());
        JsonObject owner = assertInstanceOf(JsonObject.class, wait.request().payload().get("ja.owner"));

        assertEquals(JsonNull.INSTANCE, owner.get("planRevisionId"));
        assertEquals(JsonNull.INSTANCE, owner.get("runId"));
        assertEquals(Set.of("planRevisionId", "runId"), owner.members().keySet());
    }

    /** 只有当前可见的提问调用会创建等待，其他或隐藏 Tool 保持 Core 原始参数决策。 */
    @Test
    void leavesOtherAndHiddenToolsUnchanged() {
        TurnRuntimeRequest turn = turn(TurnOrigin.USER, null, null);
        JaClarificationWaitMiddleware middleware = new JaClarificationWaitMiddleware(turn, Set.of());
        AgentMiddleware.ToolContext context = toolContext(middleware, "read", JsonObject.empty());

        AgentMiddleware.ToolProceed decision = assertInstanceOf(AgentMiddleware.ToolProceed.class,
                middleware.beforeTool(context).decision());

        assertEquals(JsonObject.empty(), decision.arguments());
    }

    /** 显式构造 Plan 或普通 Turn 的 owner 形状，避免测试通过工具名猜业务身份。 */
    private static TurnRuntimeRequest turn(TurnOrigin origin, String planRevisionId, String runId) {
        return new TurnRuntimeRequest("thr_clarification", "turn_clarification", Path.of("C:/workspace"),
                "ws_clarification", "provider_clarification", "model_clarification", null,
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, origin, planRevisionId, runId, NOW, null, java.util.Optional.empty());
    }

    /** 为单次 middleware 调用构造 Core 已冻结身份与能力状态，不引入 app 工具执行。 */
    private static AgentMiddleware.ToolContext toolContext(JaClarificationWaitMiddleware middleware,
                                                            String toolName, JsonObject arguments) {
        AgentIdentity identity = new AgentIdentity("tenant_clarification", "thread_clarification",
                "turn_clarification", "user_clarification");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_clarification", toolName, arguments, 0);
        AgentTool.ExecutionContext execution = new AgentTool.ExecutionContext(identity,
                identity.toolCallKey(invocation.callId()), Map.of());
        return new AgentMiddleware.ToolContext(identity, execution, invocation, ToolSideEffect.READ_ONLY,
                AgentTool.ApprovalRequirement.NOT_REQUIRED,
                AgentMiddleware.ExtensionStateView.empty(middleware.id(), middleware.stateSchemaVersion()));
    }

    /** 构造 TEXT 与 SINGLE 混合问题，验证 Core 接收的是同一领域 parser 的规范结果。 */
    private static JsonObject questionArguments() {
        JsonObject option = JsonObjects.builder().putText("optionId", "option_blue")
                .putText("label", "蓝色").putText("description", "偏冷色")
                .putBoolean("recommended", true).build();
        JsonObject question = JsonObjects.builder().putText("questionId", "question_color")
                .putText("prompt", "选择颜色").putText("type", "single")
                .put("options", new JsonArray(List.of(option))).putBoolean("required", true)
                .putBoolean("allowFreeText", true).build();
        return JsonObjects.builder().put("questions", new JsonArray(List.of(question))).build();
    }

    /** 按 payload 的稳定数组顺序取题目字段，避免测试依赖 JSON 对象遍历顺序。 */
    private static String text(JsonObject payload, String arrayKey, int index, String field) {
        JsonArray array = assertInstanceOf(JsonArray.class, payload.get(arrayKey));
        JsonObject question = assertInstanceOf(JsonObject.class, array.values().get(index));
        return assertInstanceOf(JsonText.class, question.get(field)).value();
    }
}
