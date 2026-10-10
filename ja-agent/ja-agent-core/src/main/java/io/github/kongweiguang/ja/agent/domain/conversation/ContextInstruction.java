// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.conversation;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 内部续跑指令可以进入模型上下文，但不表示用户发送了一条新的消息。
 *
 * @param messageId 消息标识
 * @param text 文本内容
 */
public record ContextInstruction(String messageId,
                                 String text) {
    /**
     * 稳定身份支持重启重建，正文只能是指令文本，不能伪造结构化工具调用。
     */
    public ContextInstruction {
        messageId = ContractChecks.identifier(messageId, "messageId");
        text = ContractChecks.text(text, "context instruction", Integer.MAX_VALUE, false);
    }
}
