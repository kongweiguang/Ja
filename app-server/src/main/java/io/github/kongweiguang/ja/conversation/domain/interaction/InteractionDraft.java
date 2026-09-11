// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

import java.time.Instant;
import java.util.List;

/** 可恢复 UI 草稿；保存草稿不改变 Interaction 的回答状态或 Turn 恢复资格。 */
public record InteractionDraft(String threadId, String requestId, List<InteractionAnswer> answers,
                               int page, boolean collapsed, String idempotencyKey,
                               long revision, Instant updatedAt) {
    /** 草稿是可恢复编辑事实，独立于回答状态并通过 revision 支持断线后的乐观并发控制。 */
    public InteractionDraft {
        if (threadId == null || requestId == null || answers == null || answers.size() > 3
                || page < 0 || idempotencyKey == null || idempotencyKey.isBlank()
                || revision < 0 || updatedAt == null) throw new IllegalArgumentException("invalid interaction draft");
        answers = List.copyOf(answers);
    }
}
