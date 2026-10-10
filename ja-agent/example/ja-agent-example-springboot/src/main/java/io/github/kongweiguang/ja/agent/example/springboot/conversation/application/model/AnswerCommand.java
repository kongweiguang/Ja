// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;

import java.util.List;

/**
 * 答案绑定等待标识及修订号，不按文案猜测问题身份。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param interactionId 交互标识
 * @param expectedInteractionRevision 预期交互修订版本
 * @param answers 答案集合
 */
public record AnswerCommand(String threadId,
                            String turnId,
                            String operationKey,
                            String interactionId,
                            long expectedInteractionRevision,
                            List<InteractionAnswer> answers) {
    /**
     * 异步控制提交只能读取冻结的答案集合。
     */
    public AnswerCommand {
        new TurnCommand(threadId, turnId);
        if (operationKey == null || interactionId == null || answers == null) {
            throw new IllegalArgumentException("answer identity and answers are required");
        }
        answers = List.copyOf(answers);
    }
}
