// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.model.interaction;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;

import java.time.Instant;
import java.util.List;

/**
 * 可恢复 UI 草稿；保存草稿不改变 Interaction 的回答状态或 Turn 恢复资格。
 *
 * @param threadId 所属对话的稳定标识
 * @param interactionId 交互标识
 * @param answers 答案集合
 * @param page 分页结果
 * @param collapsed 是否处于折叠状态
 * @param operationKey 重复提交同一业务操作时复用的幂等键
 * @param draftRevision 草稿修订版本
 * @param updatedAt 最后更新时间
 */
public record InteractionDraft(String threadId,
                               String interactionId,
                               List<InteractionAnswer> answers,
                               int page,
                               boolean collapsed,
                               String operationKey,
                               long draftRevision,
                               Instant updatedAt) {
    /**
     * 草稿是可恢复编辑事实，独立于回答状态并通过 draftRevision 支持断线后的乐观并发控制。
     */
    public InteractionDraft {
        if (threadId == null || interactionId == null || answers == null || answers.size() > 3
            || page < 0 || operationKey == null || operationKey.isBlank()
            || draftRevision < 0 || updatedAt == null) {
                throw new IllegalArgumentException("invalid interaction draft");
            }
        answers = List.copyOf(answers);
    }
}
