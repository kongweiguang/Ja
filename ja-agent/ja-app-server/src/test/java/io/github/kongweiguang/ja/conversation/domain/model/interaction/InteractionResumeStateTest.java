// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model.interaction;

import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 固定失效后的交互恢复视图，避免过期请求仍被客户端当成可回答或可续跑状态。 */
final class InteractionResumeStateTest {
    /** 已到期状态在历史投影中关闭，但不伪造取消或用户裁决。 */
    @Test
    void projectsExpiredInteractionAsClosed() {
        Instant now = Instant.parse("2026-09-26T00:00:00Z");
        InteractionRequest expired = new InteractionRequest("interaction_expired", "thr_expired", "turn_expired",
                "call_expired", null, null, "op_expired", List.of(
                new io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion(
                        "question_expired", "选择继续与否",
                        io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType.TEXT,
                        List.of(), false, true)), InteractionStatus.EXPIRED, List.of(), 1, now, now);

        assertEquals(InteractionResumeState.CLOSED,
                InteractionResumeState.from(Optional.of(expired), "SUSPENDED"));
    }
}
