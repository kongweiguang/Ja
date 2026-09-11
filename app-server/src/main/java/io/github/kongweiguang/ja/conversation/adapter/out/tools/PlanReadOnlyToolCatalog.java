// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.application.policy.PlanToolPolicy;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import java.util.List;
import java.util.Objects;

/**
 * 提供规划阶段可见的受控只读目录；MCP/外部路由即使自报 READ_ONLY 也不会进入该目录。
 */
public final class PlanReadOnlyToolCatalog {
    /** 禁止绕过统一 PlanToolPolicy 构造第二份规划准入规则。 */
    private PlanReadOnlyToolCatalog() {
    }

    /**
     * 只保留内建只读且没有工作区修改能力的 Tool，返回新列表以隔离原始请求目录。
     */
    public static List<AgentTool> filter(List<? extends AgentTool> tools) {
        Objects.requireNonNull(tools, "tools");
        return tools.stream().filter(PlanReadOnlyToolCatalog::admitted).map(tool -> (AgentTool) tool).toList();
    }

    /**
     * 规划阶段的安全证明必须同时满足路由、静态副作用和工作区可观察性三个条件。
     */
    public static boolean admitted(AgentTool tool) {
        return PlanToolPolicy.admitted(tool);
    }
}
