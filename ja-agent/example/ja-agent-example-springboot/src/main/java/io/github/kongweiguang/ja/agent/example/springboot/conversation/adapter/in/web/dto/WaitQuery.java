// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.TurnCommand;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 等待快照使用事件引用读取，客户端不能自行构造问题内容。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param recordId 记录标识
 */
public record WaitQuery(String threadId,
                        String turnId,
                        String recordId) {
    /**
     * 拒绝缺失记录引用，避免回读其它等待版本。
     */
    public WaitQuery {
        new TurnCommand(threadId, turnId);
        recordId = ContractChecks.identifier(recordId, "recordId");
    }
}
