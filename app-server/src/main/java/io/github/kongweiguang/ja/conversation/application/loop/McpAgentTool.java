// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 将 Turn 启动时冻结的 MCP Tool 快照适配为 Agent Tool，确保执行期间路由不会漂移。
 */
public final class McpAgentTool implements AgentTool {
    private final McpGateway gateway;
    private final McpGateway.McpSnapshot snapshot;
    private final McpGateway.McpTool tool;

    /**
     * 同时绑定 Gateway、快照与 Tool 描述，使调用始终落到发现该 Tool 的同一代会话。
     */
    private McpAgentTool(McpGateway gateway, McpGateway.McpSnapshot snapshot,
                         McpGateway.McpTool tool) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.tool = Objects.requireNonNull(tool, "tool");
    }

    /**
     * 批量适配快照中的 Tool，并保留快照顺序供模型声明与后续执行共同使用。
     */
    public static List<AgentTool> adapt(McpGateway gateway, McpGateway.McpSnapshot snapshot) {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.tools().stream()
                .map(tool -> (AgentTool) new McpAgentTool(gateway, snapshot, tool)).toList();
    }

    /**
     * 暴露快照中的不可变 Tool 规格，避免运行时再次查询 MCP 服务。
     */
    @Override
    public ToolSpec spec() {
        return tool.spec();
    }

    /**
     * 校验冻结路由后委托 Gateway，并把 MCP 结果归一为 Agent Tool 的结果与错误分类。
     */
    @Override
    public CompletionStage<ToolResult> execute(Invocation invocation, ExecutionContext context,
                                               CancellationToken cancellationToken) {
        if (!tool.spec().name().equals(invocation.toolName())) {
            throw new IllegalArgumentException("MCP tool route changed");
        }
        return gateway.invoke(snapshot, new McpGateway.McpInvocation(invocation.callId(),
                        invocation.toolName(), invocation.arguments(), invocation.ordinal()), cancellationToken)
                .thenApply(result -> new ToolResult(result.outcome(), result.content(),
                        result.structuredContent(),
                        result.error() ? "MCP_TOOL_FAILED" : null));
    }
}
