// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;

/** 审批等待是权限边界，必须验证问题构造和领域回答共同拒绝自由文本授权。 */
class AgentWaitRequestsTest {
    private static final Instant NOW = Instant.parse("2026-09-26T00:00:00Z");

    /** 宿主未声明 TTL 时不能由 Core 猜测默认有效期。 */
    @Test void rejectsMissingOrExpiredApprovalPolicy() {
        assertThrows(IllegalArgumentException.class, () -> AgentWaitRequests.create("approval_test", "call_test",
                new AgentMiddleware.WaitRequest("approval", "批准？", new JsonObject(Map.of())), NOW));
        assertThrows(IllegalArgumentException.class, () -> AgentWaitRequests.create("approval_test", "call_test",
                approval(NOW), NOW));
    }

    /** 批准只对应已冻结工具调用，完成前普通问题不能冒充无调用身份的审批。 */
    @Test void requiresToolIdentity() {
        assertThrows(IllegalArgumentException.class, () -> AgentWaitRequests.create("approval_test", null,
                approval(NOW.plusSeconds(60)), NOW));
    }

    /** 身份仅由 typed interactionId/toolCallId 承载，审批属性只保留 kind 与显式有效期。 */
    @Test void approvalAcceptsOnlyExplicitChoice() {
        InteractionWait wait = AgentWaitRequests.create("approval_test", "call_test", approval(NOW.plusSeconds(60)), NOW);
        assertEquals("approval_test", wait.interactionId());
        assertEquals("call_test", wait.toolCallId());
        assertEquals(Set.of("kind", "expiresAt"), wait.attributes().members().keySet());
        assertEquals(new JsonText("approval"), wait.attributes().members().get("kind"));
        assertEquals(new JsonText(NOW.plusSeconds(60).toString()), wait.attributes().members().get("expiresAt"));
        assertFalse(wait.questions().getFirst().allowFreeText());
        assertThrows(IllegalArgumentException.class, () -> wait.answer(List.of(
                new InteractionAnswer("question_approval", List.of(), "同意", false)), NOW.plusSeconds(1)));
        InteractionWait answered = wait.answer(List.of(new InteractionAnswer("question_approval", List.of("option_approve"),
                null, false)), NOW.plusSeconds(1));
        assertEquals(1, answered.interactionRevision());
        assertEquals("call_test", answered.toolCallId());
    }

    /** 测试显式配置审批有效期，不依赖机器时间或业务默认值。 */
    private static AgentMiddleware.WaitRequest approval(Instant expiry) {
        return new AgentMiddleware.WaitRequest("approval", "是否批准此次操作？", new JsonObject(Map.of(
                "expiresAt", new JsonText(expiry.toString()))));
    }
}
