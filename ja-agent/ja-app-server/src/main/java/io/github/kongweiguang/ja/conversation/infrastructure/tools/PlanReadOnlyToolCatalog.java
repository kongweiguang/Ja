// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.Invocation;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;

import java.util.List;
import java.util.Objects;

/**
 * 提供规划阶段可见的受控只读目录；MCP/外部路由即使自报 READ_ONLY 也不会进入该目录。
 */
public final class PlanReadOnlyToolCatalog {
    /**
     * 禁止绕过统一 PlanToolPolicy 构造第二份规划准入规则。
     */
    private PlanReadOnlyToolCatalog() {
    }

    /**
     * 只保留内建只读且没有工作区修改能力的 Tool，返回新列表以隔离原始请求目录。
     */
    public static List<JaTool> filter(List<? extends JaTool> tools) {
        Objects.requireNonNull(tools, "tools");
        return tools.stream().filter(PlanReadOnlyToolCatalog::admitted).map(tool -> (JaTool) tool).toList();
    }

    /**
     * 规划阶段的安全证明必须同时满足路由、静态副作用和工作区可观察性三个条件。
     */
    public static boolean admitted(JaTool tool) {
        Objects.requireNonNull(tool, "tool");
        boolean readOnly = tool.sideEffect() == ToolSideEffect.READ_ONLY
                           && tool.workspaceMutationMode() == JaTool.WorkspaceMutationMode.NONE;
        return tool.bindingDescriptor().routeKind() == JaTool.RouteKind.BUILTIN
               && (readOnly || tool.planAccess() == JaTool.PlanAccess.INTERNAL_MUTATION);
    }

    /**
     * 动态网关按已校验 action 复核，只有完整冻结的本地查询才能借用可信读取通道。
     */
    public static boolean admitted(JaTool tool, Invocation invocation) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(invocation, "invocation");
        boolean builtIn = tool.bindingDescriptor(invocation).routeKind() == JaTool.RouteKind.BUILTIN;
        boolean readOnly = tool.sideEffect(invocation) == ToolSideEffect.READ_ONLY
                           && tool.workspaceMutationMode(invocation) == JaTool.WorkspaceMutationMode.NONE;
        return builtIn && (readOnly
                           || tool.planAccess(invocation) == JaTool.PlanAccess.INTERNAL_MUTATION)
               || tool.isTrustedLocalReadAction(invocation);
    }
}
