// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Objects;

/**
 * assistant 消息中与后续 Tool 结果配对的调用内容块。
 *
 * @param callId 模型生成的工具调用标识
 * @param name 名称
 * @param arguments 工具调用参数
 * @param preflightFailureCode 预检失败代码
 */
public record ToolCallContent(String callId,
                              String name,
                              JsonObject arguments,
                              String preflightFailureCode) implements ModelContent {
    /**
     * 普通 Tool 调用没有 Provider 参数解析标记，保留既有模型消息构造面。
     */
    public ToolCallContent(String callId, String name, JsonObject arguments) {
        this(callId, name, arguments, null);
    }

    /**
     * 冻结参数和 Provider 解析状态，使崩溃恢复不会把空占位误当成可执行参数。
     */
    public ToolCallContent {
        callId = ContractChecks.identifier(callId, "callId");
        name = ContractChecks.identifier(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        if (preflightFailureCode != null
            && !java.util.Set.of("TOOL_ARGUMENTS_INVALID", "TOOL_CALL_TRUNCATED").contains(preflightFailureCode)) {
            throw new IllegalArgumentException("unsupported Tool preflight failure");
        }
    }
}
