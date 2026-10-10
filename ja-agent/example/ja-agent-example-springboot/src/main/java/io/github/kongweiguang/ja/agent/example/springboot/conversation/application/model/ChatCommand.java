// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

/**
 * 操作键由客户端保存，重试必须复用原键与正文。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param content 正文、Skill 与附件组成的统一输入快照
 */
public record ChatCommand(String threadId,
                          String turnId,
                          String operationKey,
                          InputContent content) {
    /**
     * 固定操作身份和内容快照，使附件与正文能用同一幂等请求重试。
     */
    public ChatCommand {
        new TurnCommand(threadId, turnId);
        if (operationKey == null || operationKey.isBlank() || content == null) {
            throw new IllegalArgumentException("operationKey and content are required");
        }
    }
}
