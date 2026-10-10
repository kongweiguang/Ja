// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 查询和恢复只需 Thread/Turn，用户身份不接受客户端输入。
 *
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 */
public record TurnCommand(String threadId,
                          String turnId) {
    /**
     * 标识沿用 Core 的字符与长度约束，避免请求体产生歧义身份。
     */
    public TurnCommand {
        threadId = ContractChecks.identifier(threadId, "threadId");
        turnId = ContractChecks.identifier(turnId, "turnId");
    }
}
