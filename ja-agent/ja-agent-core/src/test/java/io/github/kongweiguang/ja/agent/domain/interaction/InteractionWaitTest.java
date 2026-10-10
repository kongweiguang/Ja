// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.interaction;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证过期只关闭持久等待，不会伪造用户答案或审批决定。 */
final class InteractionWaitTest {
    private static final Instant CREATED = Instant.parse("2026-09-26T00:00:00Z");

    /** 到期关闭只能从 PENDING 发生一次，并保留问题与无答案事实供审计。 */
    @Test
    void expiryClosesPendingWaitWithoutInventingAnAnswer() {
        InteractionWait pending = new InteractionWait("interaction_test", "call_test", "op_test",
                new JsonObject(Map.of()), List.of(new InteractionQuestion("question_test", "确认？",
                InteractionQuestionType.SINGLE, List.of(new InteractionOption("option_yes", "是", "确认操作", false)),
                true, false)), List.of(),
                InteractionStatus.PENDING, 0, CREATED, CREATED);

        InteractionWait expired = pending.close(InteractionStatus.EXPIRED, CREATED.plusSeconds(60));

        assertEquals(InteractionStatus.EXPIRED, expired.status());
        assertEquals(1, expired.interactionRevision());
        assertEquals(pending.questions(), expired.questions());
        assertEquals(List.of(), expired.answers());
        assertThrows(IllegalStateException.class,
                () -> expired.close(InteractionStatus.CANCELLED, CREATED.plusSeconds(61)));
    }

    /** 到期本身不是用户回答，失效等待不能借 close API 改成 ANSWERED。 */
    @Test
    void expiryCannotBeUsedAsAnAnswer() {
        InteractionWait pending = new InteractionWait("interaction_test", null, "op_test", new JsonObject(Map.of()),
                List.of(new InteractionQuestion("question_test", "输入？", InteractionQuestionType.TEXT,
                        List.of(), true, true)), List.of(), InteractionStatus.PENDING, 0, CREATED, CREATED);
        InteractionWait expired = pending.close(InteractionStatus.EXPIRED, CREATED.plusSeconds(60));

        assertThrows(IllegalStateException.class, () -> expired.answer(
                List.of(new InteractionAnswer("question_test", List.of(), "迟到答案", false)),
                CREATED.plusSeconds(61)));
    }
}
