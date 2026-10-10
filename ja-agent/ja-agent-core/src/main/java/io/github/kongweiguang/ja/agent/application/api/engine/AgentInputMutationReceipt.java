// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 输入队列操作的稳定确认，保留已提交版本而不要求按正文推测条目身份。
 *
 * @param inputId 输入标识
 * @param queueRevision 队列修订版本
 */
public record AgentInputMutationReceipt(String inputId,
                                        long queueRevision) {
    /**
     * ACK 返回调用方生成的输入身份和 reducer 分配的真实队列水位。
     */
    public AgentInputMutationReceipt {
        inputId = ContractChecks.identifier(inputId, "inputId");
        if (queueRevision < 1) {
            throw new IllegalArgumentException("queueRevision must be positive");
        }
    }
}
