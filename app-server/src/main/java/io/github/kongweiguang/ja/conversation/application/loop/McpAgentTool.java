// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 将单次 Provider 请求看到的 MCP Tool 快照适配为 Agent Tool，确保已生成 batch 不会改路由。
 */
public final class McpAgentTool implements AgentTool {
    private final McpGateway gateway;
    private final McpGateway.McpSnapshot snapshot;
    private final McpGateway.McpTool tool;
    private final McpGateway.RouteIdentity routeIdentity;

    /**
     * 同时绑定 Gateway、快照与 Tool 描述，使调用始终落到发现该 Tool 的同一代会话。
     */
    private McpAgentTool(McpGateway gateway, McpGateway.McpSnapshot snapshot,
                         McpGateway.McpTool tool,
                         McpGateway.RouteIdentity routeIdentity) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.routeIdentity = Objects.requireNonNull(routeIdentity, "routeIdentity");
        if (!tool.spec().name().equals(routeIdentity.localName())
                || !snapshot.revision().equals(routeIdentity.catalogRevision())) {
            throw new IllegalArgumentException("MCP route identity does not match catalog snapshot");
        }
    }

    /**
     * 批量适配快照中的 Tool，并保留快照顺序供模型声明与后续执行共同使用。
     */
    public static List<AgentTool> adapt(McpGateway gateway, McpGateway.McpSnapshot snapshot,
                                        Map<String, McpGateway.RouteIdentity> routeIdentities) {
        Objects.requireNonNull(gateway, "gateway");
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, McpGateway.RouteIdentity> identities =
                Map.copyOf(Objects.requireNonNull(routeIdentities, "routeIdentities"));
        if (identities.size() != snapshot.tools().size()) {
            throw new IllegalArgumentException("MCP route identities do not match catalog tools");
        }
        return snapshot.tools().stream()
                .map(tool -> (AgentTool) new McpAgentTool(gateway, snapshot, tool,
                        Objects.requireNonNull(identities.get(tool.spec().name()),
                                "MCP route identity")))
                .toList();
    }

    /**
     * 使用 MCP 目录 owner 已规范化的服务、远端名和摘要，禁止 conversation 重新猜测路由身份。
     */
    @Override
    public ToolBindingDescriptor bindingDescriptor() {
        return new ToolBindingDescriptor(RouteKind.MCP, routeIdentity.localName(), routeIdentity.serverId(),
                routeIdentity.remoteName(), routeIdentity.schemaHash(), routeIdentity.routeHash());
    }

    /**
     * 暴露快照中的不可变 Tool 规格，避免运行时再次查询 MCP 服务。
     */
    @Override
    public ToolSpec spec() {
        return tool.spec();
    }

    /**
     * 校验持久绑定路由后委托 Gateway，并把 MCP 结果归一为 Agent Tool 的结果与错误分类。
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
