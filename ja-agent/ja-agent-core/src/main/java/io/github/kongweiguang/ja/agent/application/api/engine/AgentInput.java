// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Objects;

/**
 * 追加到会话的不可变用户输入，使用独立幂等身份。
 *
 * @param operationKey 业务操作标识，重试时复用以识别同一操作
 * @param content 内容
 */
public record AgentInput(String operationKey,
                         UserContent content) {
    /**
     * 要求稳定操作键，使确认丢失后的重试不会重复追加排队输入。
     */
    public AgentInput {
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        Objects.requireNonNull(content, "content");
    }
}
