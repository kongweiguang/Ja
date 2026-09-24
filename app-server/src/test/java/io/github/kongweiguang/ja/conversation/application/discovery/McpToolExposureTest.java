// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 固定网关不因远端目录大小切换 Provider 声明，附件读取仍按结构化上下文显隐。 */
final class McpToolExposureTest {
    /** 目录大小不再把远端工具直出或注入第二个 tool_search 入口。 */
    @Test
    void catalogPreservesSingleGatewayWithoutSearchToolSwitch() {
        List<AgentTool> input = java.util.stream.IntStream.range(0, 68)
                .mapToObj(index -> tool("mcp_tool_" + index, AgentTool.RouteKind.MCP))
                .toList();

        List<AgentTool> catalog = McpToolExposure.catalog(input);
        List<ToolSpec> modelTools = McpToolExposure.modelTools(catalog, List.of());

        assertEquals(input, catalog);
        assertEquals(68, modelTools.size());
        assertFalse(modelTools.stream().anyMatch(spec -> spec.name().equals("tool_search")));
    }

    /** 当前上下文没有附件时隐藏真实内建读取入口，存在类型化附件时再声明。 */
    @Test
    void attachmentToolRequiresTypedAttachmentInCurrentContext() {
        AgentTool attachmentReader = tool("read_attachment", AgentTool.RouteKind.BUILTIN);
        AgentTool ordinary = tool("read", AgentTool.RouteKind.BUILTIN);
        List<AgentTool> catalog = McpToolExposure.catalog(List.of(attachmentReader, ordinary));

        assertFalse(hasTool(McpToolExposure.modelTools(catalog, List.of()), attachmentReader));
        assertTrue(hasTool(McpToolExposure.modelTools(catalog, List.of(attachmentMessage())), attachmentReader));
        assertTrue(hasTool(McpToolExposure.modelTools(catalog, List.of()), ordinary));
    }

    /** MCP 的同名远端能力不因内建附件读取入口隐藏而丢失。 */
    @Test
    void mcpRouteNamedReadAttachmentRemainsVisible() {
        AgentTool mcp = tool("read_attachment", AgentTool.RouteKind.MCP);

        assertTrue(hasTool(McpToolExposure.modelTools(List.of(mcp), List.of()), mcp));
    }

    /** 创建只具备声明和路由身份的测试 Tool，保证可见性由 RouteKind 决定。 */
    private static AgentTool tool(String name, AgentTool.RouteKind routeKind) {
        ToolSpec spec = new ToolSpec(name, "Fixture", JsonObjects.builder().putText("type", "object").build());
        return new AgentTool() {
            /** 返回 fixture 的不可变 Tool 声明。 */
            @Override
            public ToolSpec spec() {
                return spec;
            }

            /** 返回测试固定路由，防止通过名称推断 MCP 类型。 */
            @Override
            public ToolBindingDescriptor bindingDescriptor() {
                return new ToolBindingDescriptor(routeKind, name,
                        routeKind == RouteKind.MCP ? "mcp_fixture" : "builtin", name,
                        "a".repeat(64), "b".repeat(64));
            }

            /** 可见性测试不执行副作用，最小实现仅满足 Tool 接口契约。 */
            @Override
            public CompletionStage<ToolResult> execute(Invocation invocation, ExecutionContext context,
                                                       CancellationToken cancellationToken) {
                return CompletableFuture.completedFuture(ToolResult.success("fixture"));
            }
        };
    }

    /** 使用 AttachmentBlock 作为唯一真实附件事实，避免文本提及激活 Tool。 */
    private static ContextMessage attachmentMessage() {
        return new ContextMessage("message_attachment", "turn_1", 1, ContextMessage.Role.USER,
                List.of(new ContextMessage.AttachmentBlock("att_fixture")), 1);
    }

    /** 仅比较公开 Provider 声明名称，避免测试依赖列表内部顺序。 */
    private static boolean hasTool(List<ToolSpec> specs, AgentTool tool) {
        return specs.stream().anyMatch(spec -> spec.name().equals(tool.spec().name()));
    }
}
