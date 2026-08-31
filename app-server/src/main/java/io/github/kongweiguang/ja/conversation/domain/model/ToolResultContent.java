// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

/**
 * Tool 消息中与既有调用标识配对的结果内容块。
 */
public record ToolResultContent(String callId, String content, boolean error) implements ModelContent {
    /**
     * 保留空结果但限制体积，避免错误正文穿透既定模型上下文预算。
     */
    public ToolResultContent {
        callId = ContractChecks.identifier(callId, "callId");
        content = ContractChecks.text(content, "content", 4_000_000, true);
    }
}
