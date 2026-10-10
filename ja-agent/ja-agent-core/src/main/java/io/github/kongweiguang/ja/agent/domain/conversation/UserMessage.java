// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.conversation;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Objects;

/**
 * 用户原始输入独立于模型和工具事实，重放历史时不能混淆消息身份或角色。
 *
 * @param messageId 消息标识
 * @param content 内容
 */
public record UserMessage(String messageId,
                          UserContent content) {
    /**
     * 稳定消息标识参与幂等与恢复，正文保持原有块顺序且不解释业务引用。
     */
    public UserMessage {
        messageId = ContractChecks.identifier(messageId, "messageId");
        Objects.requireNonNull(content, "content");
    }
}
