// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.TurnCommand;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 消息引用来自模型或工具完成事件；Usage 只用于关联公开 modelRequestId。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param recordId 记录标识
 * @param usageRecordId 用量记录标识
 */
public record MessageQuery(String threadId,
                           String turnId,
                           String recordId,
                           String usageRecordId) {
    /**
     * 记录标识必须明确，Usage 引用允许省略。
     */
    public MessageQuery {
        new TurnCommand(threadId, turnId);
        recordId = ContractChecks.identifier(recordId, "recordId");
        if (usageRecordId != null) {
            usageRecordId = ContractChecks.identifier(usageRecordId, "usageRecordId");
        }
    }
}
