// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证宿主不会把缺失或未知的持久等待分类降级成普通输入。 */
final class ChatServiceWaitKindTest {
    /**
     * 输入与审批分类正常投影；缺失、错误 JSON 类型及未知标签均以完整性异常失败关闭。
     */
    @Test
    void onlyProjectsKnownWaitKinds() {
        assertEquals("input", ChatService.waitKind(wait(Map.of("kind", new JsonText("input")))));
        assertEquals("approval", ChatService.waitKind(wait(Map.of("kind", new JsonText("approval")))));

        AgentStore.StoreIntegrityException missing = assertThrows(AgentStore.StoreIntegrityException.class,
                () -> ChatService.waitKind(wait(Map.of())));
        assertEquals("invalid_interaction_wait_kind", missing.getMessage());

        AgentStore.StoreIntegrityException wrongType = assertThrows(AgentStore.StoreIntegrityException.class,
                () -> ChatService.waitKind(wait(Map.of("kind", new JsonBoolean(true)))));
        assertEquals("invalid_interaction_wait_kind", wrongType.getMessage());

        AgentStore.StoreIntegrityException unknown = assertThrows(AgentStore.StoreIntegrityException.class,
                () -> ChatService.waitKind(wait(Map.of("kind", new JsonText("unknown")))));
        assertEquals("invalid_interaction_wait_kind", unknown.getMessage());
    }

    /**
     * 构造最小有效的等待聚合，测试只关注 attributes 契约而不启动引擎或持久层。
     */
    private static InteractionWait wait(Map<String, ? extends JsonValue> attributes) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new InteractionWait("interaction_test", null, "operation_test", new JsonObject(attributes),
                List.of(new InteractionQuestion("question_kind", "Provide the requested input",
                        InteractionQuestionType.TEXT, List.of(), true, true)), List.of(),
                InteractionStatus.PENDING, 0, now, now);
    }
}
