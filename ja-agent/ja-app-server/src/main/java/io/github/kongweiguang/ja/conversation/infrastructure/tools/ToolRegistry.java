// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内置 Tool 的不可变确定性注册表，按名称提供快照和调用路由。
 */
public final class ToolRegistry {
    private final Map<String, JaTool> tools;
    private final List<JaTool> snapshot;

    /**
     * 拒绝重复名称并冻结排序快照，Provider 路由和提示输出不得依赖注册顺序。
     */
    public ToolRegistry(List<? extends JaTool> tools) {
        Objects.requireNonNull(tools, "tools");
        Map<String, JaTool> indexed = new LinkedHashMap<>();
        List<? extends JaTool> ordered = tools.stream()
                .map(tool -> Objects.requireNonNull(tool, "tool"))
                .sorted(Comparator.comparing(tool -> tool.spec().name()))
                .toList();
        for (JaTool tool : ordered) {
            JaTool previous = indexed.put(tool.spec().name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate_tool_name: " + tool.spec().name());
            }
        }
        this.tools = Map.copyOf(indexed);
        this.snapshot = List.copyOf(ordered);
    }

    /**
     * 返回按名称冻结的模型可见 Tool，保证提示词与 fixture 输出稳定。
     */
    public List<JaTool> snapshot() {
        return snapshot;
    }

    /**
     * 仅在模型回显名称已注册时解析调用，未知名称立即失败关闭。
     */
    public JaTool require(AgentTool.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        JaTool tool = tools.get(invocation.toolName());
        if (tool == null) {
            throw new IllegalArgumentException("tool_not_found: " + invocation.toolName());
        }
        return tool;
    }
}
