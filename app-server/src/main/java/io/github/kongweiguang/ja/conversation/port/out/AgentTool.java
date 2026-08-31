// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 执行 Provider 选择的 Tool，并把外部副作用隔离在出站适配器。
 */
public interface AgentTool {
    /**
     * 返回权限、Schema 与并发调度共享的不可变领域描述。
     */
    ToolSpec spec();

    /**
     * 在给定取消作用域内执行一次调用，并返回可安全持久化的结果。
     */
    CompletionStage<ToolResult> execute(
            Invocation invocation,
            ExecutionContext context,
            CancellationToken cancellationToken);

    /**
     * 一次模型 Tool 调用的稳定身份、参数和顺序。
     */
    record Invocation(String callId, String toolName, JsonObject arguments, int ordinal) {
        /**
         * 冻结调用身份、参数与批次序号，避免 Adapter 在排队后观察到可变参数。
         */
        public Invocation {
            callId = ContractChecks.identifier(callId, "callId");
            toolName = ContractChecks.identifier(toolName, "toolName");
            Objects.requireNonNull(arguments, "arguments");
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("tool ordinal is outside turn limits");
            }
        }
    }

    /**
     * Tool 执行所需的最小 Turn、工作区、权限和 Deadline 上下文。
     */
    record ExecutionContext(
            String threadId,
            String turnId,
            Path workspaceRoot,
            AccessMode accessMode,
            String configGeneration,
            Instant deadline,
            String workspaceId) {
        /**
         * 固化权限判断和路径约束所需的最小上下文，避免 Tool 反向读取全局运行时。
         */
        public ExecutionContext {
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
            Objects.requireNonNull(accessMode, "accessMode");
            configGeneration = ContractChecks.configurationGeneration(configGeneration);
            Objects.requireNonNull(deadline, "deadline");
            workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
            if (!workspaceId.startsWith("ws_")) throw new IllegalArgumentException("invalid workspaceId");
        }
    }

    /**
     * Tool Adapter 可提交给领域循环的安全结果，不携带异常或资源句柄。
     */
    record ToolResult(
            ToolOutcome outcome,
            String content,
            Optional<JsonValue> structuredContent,
            String errorCode) {
        /**
         * 将 Adapter 输出收敛为有界不可变值，异常与资源句柄不得越过端口。
         */
        public ToolResult {
            Objects.requireNonNull(outcome, "outcome");
            content = ContractChecks.text(content == null ? "" : content, "content", 4_000_000, true);
            structuredContent = Objects.requireNonNull(structuredContent, "structuredContent");
            if (errorCode != null) {
                errorCode = ContractChecks.identifier(errorCode, "errorCode");
            }
        }

        /**
         * 构造没有额外元数据的成功结果，避免调用方重复拼装闭集字段。
         */
        public static ToolResult success(String content) {
            return new ToolResult(ToolOutcome.SUCCEEDED, content, Optional.empty(), null);
        }

        /**
         * 只输出长度与键集合，防止 Tool 正文或敏感元数据进入日志。
         */
        @Override
        public String toString() {
            return "ToolResult[outcome=" + outcome
                   + ", contentLength=" + content.length()
                   + ", structuredContent=" + structuredContent.isPresent()
                   + ", errorCode=" + errorCode + "]";
        }
    }

}
