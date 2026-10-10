// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.context;

import java.util.Objects;

/**
 * 保存被压缩文本的保留后缀及其完整源消息身份，供恢复时重建上下文边界。
 *
 * @param sourceMessageId 来源消息标识
 * @param retainedMessage 保留消息
 */
public record RetainedSplit(String sourceMessageId,
                            ContextMessage retainedMessage) {
    /**
     * 保留源消息与后缀消息不同一性的约束，避免压缩后重复拼接完整正文。
     */
    public RetainedSplit {
        if (sourceMessageId == null || sourceMessageId.isBlank() || sourceMessageId.length() > 256
            || sourceMessageId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid retained split source");
        }
        retainedMessage = Objects.requireNonNull(retainedMessage, "retainedMessage");
        if (sourceMessageId.equals(retainedMessage.messageId())
            || retainedMessage.blocks().size() != 1
            || !(retainedMessage.blocks().getFirst() instanceof ContextMessage.TextBlock)) {
            throw new IllegalArgumentException("invalid retained split suffix");
        }
    }
}
