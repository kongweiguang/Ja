// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.discovery;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;

import java.util.List;
import java.util.Objects;

/** Provider 声明始终保留请求目录；固定 MCP 网关内部完成有界发现和路由。 */
public final class McpToolExposure {
    private static final String READ_ATTACHMENT = "read_attachment";

    /** 无状态声明策略不再从历史搜索结果重建模型工具集合。 */
    private McpToolExposure() { }

    /** MCP 内部目录搜索不改变执行目录，Provider 所见工具声明保持与请求快照一致。 */
    public static List<AgentTool> catalog(List<AgentTool> tools) {
        return List.copyOf(Objects.requireNonNull(tools, "tools"));
    }

    /** 仅在当前上下文有结构化附件时声明真实内建附件读取能力。 */
    public static List<ToolSpec> modelTools(List<AgentTool> catalog, List<ContextMessage> messages) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(messages, "messages");
        boolean attachmentPresent = messages.stream().anyMatch(message -> message.blocks().stream()
                .anyMatch(ContextMessage.AttachmentBlock.class::isInstance));
        return catalog.stream()
                .filter(tool -> attachmentPresent || !READ_ATTACHMENT.equals(tool.spec().name())
                        || tool.bindingDescriptor().routeKind() != AgentTool.RouteKind.BUILTIN)
                .map(AgentTool::spec)
                .toList();
    }
}
