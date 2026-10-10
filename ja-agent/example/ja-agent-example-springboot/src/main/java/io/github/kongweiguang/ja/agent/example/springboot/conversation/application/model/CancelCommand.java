// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

/**
 * 取消原因是审计事实，不代表业务回滚。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param reason 原因说明
 */
public record CancelCommand(String threadId,
                            String turnId,
                            String operationKey,
                            String reason) {
    /**
     * 缺失值不能隐式替换为默认审计内容。
     */
    public CancelCommand {
        new TurnCommand(threadId, turnId);
        if (operationKey == null || reason == null)
            {
                throw new IllegalArgumentException("cancel identity and reason are required");
            }
    }
}
