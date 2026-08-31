// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 向 conversation 暴露冻结 MCP Tool 目录和有界调用的出站 SPI。
 */
public interface McpGateway extends AutoCloseable {
    /**
     * 返回与当前会话连接绑定的不可变 Tool 快照。
     */
    McpSnapshot snapshot();

    /**
     * 使用同一快照调用远端 Tool，禁止按名称重新解析到另一配置代际。
     */
    CompletionStage<McpResult> invoke(
            McpSnapshot snapshot,
            McpInvocation invocation,
            CancellationToken cancellationToken);

    /**
     * 释放 MCP 会话及其子进程或传输资源；实现必须幂等。
     */
    @Override
    void close();

    /**
     * MCP 会话创建时冻结的目录与修订。
     */
    record McpSnapshot(String revision, List<McpTool> tools, Instant createdAt) {
        /**
         * 冻结一次 MCP 连接看到的 Tool 集合，后续调用必须复用同一修订。
         */
        public McpSnapshot {
            revision = ContractChecks.identifier(revision, "revision");
            tools = ContractChecks.immutableList(tools, "tools");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * 本地 Tool 名称与远端 MCP 身份之间的稳定绑定。
     */
    record McpTool(String serverId, String remoteName, ToolSpec spec) {
        /**
         * 绑定本地规范名称与远端身份，禁止执行阶段重新按名称发现 Tool。
         */
        public McpTool {
            serverId = ContractChecks.identifier(serverId, "serverId");
            remoteName = ContractChecks.identifier(remoteName, "remoteName");
            Objects.requireNonNull(spec, "spec");
        }
    }

    /**
     * 传给单个 MCP 会话的有序调用。
     */
    record McpInvocation(String callId, String localToolName, JsonObject arguments, int ordinal) {
        /**
         * 固化调用参数与顺序，保证并发执行后仍可按原序提交结果。
         */
        public McpInvocation {
            callId = ContractChecks.identifier(callId, "callId");
            localToolName = ContractChecks.identifier(localToolName, "localToolName");
            Objects.requireNonNull(arguments, "arguments");
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("MCP ordinal is outside turn limits");
            }
        }
    }

    /**
     * MCP 结果的 Provider 中立投影，结构化内容必须保持严格 JSON。
     */
    record McpResult(
            boolean error,
            String content,
            Optional<JsonValue> structuredContent,
            ToolOutcome outcome) {
        /**
         * 把远端响应限制为可持久化的 Provider 中立值，不允许传递 SDK 对象。
         */
        public McpResult {
            content = ContractChecks.text(content == null ? "" : content, "content", 4_000_000, true);
            structuredContent = Objects.requireNonNull(structuredContent, "structuredContent");
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
