// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 把扩展提出的等待意图规范化为持久问题；审批许可只能来自明确选项和宿主声明的有效期。
 */
final class AgentWaitRequests {
    /**
     * 静态转换不持有等待的内存状态，所有后续回答都由 InteractionWait 验证。
     */
    private AgentWaitRequests() {
    }

    /**
     * 审批身份只写入 typed wait 字段，attributes 仅承载策略数据，避免恢复时依赖身份副本。
     */
    static InteractionWait create(String interactionId, String callId, AgentMiddleware.WaitRequest request, Instant now) {
        LinkedHashMap<String, JsonValue> attributes = new LinkedHashMap<>(request.payload().members());
        attributes.put("kind", new JsonText(request.kind()));
        List<InteractionQuestion> questions;
        if (request.kind().equals("approval")) {
            if (callId == null) {
                throw new IllegalArgumentException("approval requires a Tool call identity");
            }
            Instant expiresAt = Instant.parse(text(request.payload(), "expiresAt"));
            if (!expiresAt.isAfter(now)) {
                throw new IllegalArgumentException("approval policy already expired");
            }
            questions = List.of(new InteractionQuestion("question_approval", request.prompt(),
                    InteractionQuestionType.SINGLE, List.of(
                    new InteractionOption("option_approve", "批准", "允许执行此次已冻结的工具调用", false),
                    new InteractionOption("option_deny", "拒绝", "不执行此次工具调用", false)), true, false));
        } else {
            questions = array(request.payload(), "questions").values().stream().map(value -> question(object(value))).toList();
        }
        return new InteractionWait(interactionId, callId, interactionId, new JsonObject(attributes), questions,
                List.of(), InteractionStatus.PENDING, 0, now, now);
    }

    /**
     * 问题结构严格读取；缺失字段不能静默获得默认许可或隐藏必答约束。
     */
    private static InteractionQuestion question(JsonObject source) {
        List<InteractionOption> options = array(source, "options").values().stream().map(value -> {
            JsonObject option = object(value);
            return new InteractionOption(text(option, "optionId"), text(option, "label"),
                    text(option, "description"), bool(option, "recommended"));
        }).toList();
        return new InteractionQuestion(text(source, "questionId"), text(source, "prompt"),
                InteractionQuestionType.valueOf(text(source, "type")), options,
                bool(source, "required"), bool(source, "allowFreeText"));
    }

    /**
     * 拒绝把任意 JSON 值转换为展示文本，防止类型错误掩盖授权字段缺失。
     */
    private static String text(JsonObject source, String key) {
        if (source.get(key) instanceof JsonText(String value1)) {
            return value1;
        }
        throw new IllegalArgumentException("wait requires text field: " + key);
    }

    /**
     * 明确布尔值控制题目约束，不接受字符串或数字真值。
     */
    private static boolean bool(JsonObject source, String key) {
        if (source.get(key) instanceof JsonBoolean jsonBoolean) {
            return jsonBoolean.value();
        }
        throw new IllegalArgumentException("wait requires boolean field: " + key);
    }

    /**
     * 顺序即用户看到的问题、选项顺序，不接受对象键遍历替代数组。
     */
    private static JsonArray array(JsonObject source, String key) {
        if (source.get(key) instanceof JsonArray value) {
            return value;
        }
        throw new IllegalArgumentException("wait requires array field: " + key);
    }

    /**
     * 每一项必须是完整对象，禁止空项绕过领域构造器的校验。
     */
    private static JsonObject object(JsonValue value) {
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new IllegalArgumentException("wait item must be an object");
    }
}
