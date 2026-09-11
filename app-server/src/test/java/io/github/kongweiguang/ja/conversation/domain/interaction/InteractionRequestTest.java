// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定 Interaction 的结构化答案闭集与不可逆状态边界。 */
class InteractionRequestTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    /** 自填单选与多行文本必须保留真实答案语义，不伪造选项身份。 */
    @Test
    void acceptsSingleChoiceFreeTextAndMultilineText() {
        InteractionRequest request = request(new InteractionQuestion("question_scope", "选择实现范围\n并说明理由",
                InteractionQuestionType.SINGLE,
                List.of(new InteractionOption("option_default", "默认", "保持当前设置", true)), true, true));
        InteractionRequest answered = request.answer(List.of(new InteractionAnswer("question_scope", List.of(),
                "保留默认\n并记录原因", false)), NOW.plusSeconds(1));
        assertEquals(InteractionStatus.ANSWERED, answered.status());
        assertEquals(1, answered.revision());
    }

    /** 必答约束由后端强制执行，绕过 UI 也不能提交 skipped。 */
    @Test
    void requiredQuestionCannotBeSkipped() {
        InteractionRequest request = request(new InteractionQuestion("question_required", "必须回答",
                InteractionQuestionType.TEXT, List.of(), true, true));
        assertThrows(IllegalArgumentException.class,
                () -> request.answer(List.of(new InteractionAnswer("question_required", List.of(), null, true)),
                        NOW.plusSeconds(1)));
    }

    /** 取消是权威终态，迟到答案不能复活已关闭请求。 */
    @Test
    void closedRequestCannotBeAnsweredAgain() {
        InteractionRequest request = request(new InteractionQuestion("question_optional", "可选",
                InteractionQuestionType.TEXT, List.of(), false, true));
        InteractionRequest cancelled = request.close(InteractionStatus.CANCELLED, NOW.plusSeconds(1));
        assertThrows(IllegalStateException.class,
                () -> cancelled.answer(List.of(new InteractionAnswer("question_optional", List.of(), "late", false)),
                        NOW.plusSeconds(2)));
    }

    /** 固定时间和身份使纯状态测试不依赖 Provider 或数据库。 */
    private static InteractionRequest request(InteractionQuestion question) {
        return new InteractionRequest("interaction_test", "thr_test", "turn_test", "call_test",
                null, null, null, "request-idempotency", List.of(question), InteractionStatus.PENDING,
                List.of(), 0, NOW, NOW);
    }
}
