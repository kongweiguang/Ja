// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.session;

import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 从精确冻结的配置代际租约打开一个 Turn 作用域 MCP 能力。
 */
@FunctionalInterface
public interface TurnMcpSessionFactory {
    /**
     * 使用已固定的 Turn 工作区、取消令牌与剩余墙钟 Deadline 打开 Session。
     */
    Session open(
            Context context,
            ConfigurationGenerationPort.Lease generation,
            CancellationToken cancellationToken);

    /**
     * MCP 打开阶段所需的 Provider/Model 身份、工作区与绝对 Deadline，不暴露应用执行计划。
     */
    record Context(String providerId, String modelId, Path workspaceRoot, Instant deadlineAt) {
        /**
         * 固定代际解析键与路径，防止会话工厂重新解释入站 DTO。
         */
        public Context {
            providerId = ContractChecks.identifier(providerId, "providerId");
            modelId = ContractChecks.identifier(modelId, "modelId");
            workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
            Objects.requireNonNull(deadlineAt, "deadlineAt");
        }
    }

    /**
     * Turn 独占的 Gateway/快照组合，必须在 AgentLoop 终态 finally 中关闭。
     */
    interface Session extends AutoCloseable {
        /**
         * 返回此 Turn 唯一获授权的 Gateway。
         */
        McpGateway gateway();

        /**
         * 返回模型轮次开始前已由 initialize/discovery 冻结的目录。
         */
        McpGateway.McpSnapshot snapshot();

        /**
         * 取消调用并关闭所有 Turn 独占的 HTTP/stdio 资源。
         */
        @Override
        void close();
    }
}
