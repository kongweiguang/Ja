// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.TurnCommand;

/**
 * 暂停和压缩均需要固定幂等键，重试不创建额外执行。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 */
public record ControlCommand(String threadId,
                             String turnId,
                             String operationKey) {
    /**
     * 缺失操作键在 HTTP 准入时拒绝。
     */
    public ControlCommand {
        new TurnCommand(threadId, turnId);
        if (operationKey == null || operationKey.isBlank())
            {
                throw new IllegalArgumentException("operation key required");
            }
    }
}
