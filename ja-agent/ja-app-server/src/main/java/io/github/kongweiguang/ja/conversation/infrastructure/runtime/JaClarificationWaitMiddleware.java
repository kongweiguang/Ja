// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionToolFactory;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 将 Ja 的澄清工具映射为 Core 可持久恢复的通用等待，不在工具执行中另建生命周期。
 */
public final class JaClarificationWaitMiddleware implements AgentMiddleware {
    private static final String TOOL_NAME = "request_user_input";
    private final TurnRuntimeRequest turn;
    private final Set<String> visibleTools;

    /**
     * 冻结本次 Turn 与实际可见目录，避免恢复策略从新请求或工具名称推导业务 owner。
     */
    public JaClarificationWaitMiddleware(TurnRuntimeRequest turn, Set<String> visibleTools) {
        this.turn = Objects.requireNonNull(turn, "turn");
        this.visibleTools = Set.copyOf(Objects.requireNonNull(visibleTools, "visibleTools"));
        if (turn.turnId() == null) {
            throw new IllegalArgumentException("clarification requires a Turn identity");
        }
    }

    /**
     * 用共享领域题目序列化等待问题，保持 UI 等待与恢复执行校验使用同一规范化快照。
     */
    private static JsonObject questionJson(InteractionQuestion question) {
        List<JsonValue> options = question.options().stream().map(option -> (JsonValue) JsonObjects.builder()
                .putText("optionId", option.optionId())
                .putText("label", option.label())
                .putText("description", option.description())
                .putBoolean("recommended", option.recommended())
                .build()).toList();
        return JsonObjects.builder()
                .putText("questionId", question.questionId())
                .putText("prompt", question.prompt())
                .putText("type", question.type().name())
                .put("options", new JsonArray(options))
                .putBoolean("required", question.required())
                .putBoolean("allowFreeText", question.allowFreeText())
                .build();
    }

    /**
     * Ja owner 的两个可空字段始终显式存在，null 与缺少字段保持不同的恢复契约。
     */
    private static JsonValue nullableText(String value) {
        return value == null ? JsonNull.INSTANCE : new JsonText(value);
    }

    /**
     * 提问策略使用独立恢复命名空间，当前状态为空且不保存易失执行状态。
     */
    @Override
    public String id() {
        return "ja.clarification-wait";
    }

    /**
     * 仅对本次模型实际可见的澄清工具创建等待，其他 Tool 保持原决策不变。
     */
    @Override
    public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
        Objects.requireNonNull(context, "context");
        if (!TOOL_NAME.equals(context.invocation().toolName()) || !visibleTools.contains(TOOL_NAME)) {
            return ExtensionDecision.unchanged(
                    ToolDecision.proceed(context.invocation().arguments()), context.extensionState());
        }
        List<InteractionQuestion> questions = InteractionToolFactory.parseQuestions(context.invocation().arguments());
        JsonObject payload = JsonObjects.builder()
                .put("questions", new JsonArray(questions.stream().map(JaClarificationWaitMiddleware::questionJson)
                        .map(JsonValue.class::cast).toList()))
                .put("ja.owner", JsonObjects.builder()
                        .put("planRevisionId", nullableText(turn.planRevisionId()))
                        .put("runId", nullableText(turn.runId()))
                        .build())
                .build();
        WaitRequest wait = new WaitRequest("clarification", "请回答这些澄清问题。", payload);
        return ExtensionDecision.unchanged(ToolDecision.waitFor(wait), context.extensionState());
    }
}
