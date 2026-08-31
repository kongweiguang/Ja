// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.Objects;

/**
 * assistant 消息中与后续 Tool 结果配对的调用内容块。
 */
public record ToolCallContent(String callId, String name, JsonObject arguments) implements ModelContent {
    /**
     * 冻结严格 JSON 参数，确保调用记录与实际执行输入保持同一事实。
     */
    public ToolCallContent {
        callId = ContractChecks.identifier(callId, "callId");
        name = ContractChecks.identifier(name, "name");
        Objects.requireNonNull(arguments, "arguments");
    }
}
