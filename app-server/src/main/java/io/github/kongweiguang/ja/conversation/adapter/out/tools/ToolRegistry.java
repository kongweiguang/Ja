// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.port.out.AgentTool;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内置 Tool 的不可变确定性注册表，按名称提供快照和调用路由。
 */
public final class ToolRegistry {
    private final Map<String, AgentTool> tools;
    private final List<AgentTool> snapshot;

    /**
     * 拒绝重复名称并冻结排序快照，Provider 路由和提示输出不得依赖注册顺序。
     */
    public ToolRegistry(List<? extends AgentTool> tools) {
        Objects.requireNonNull(tools, "tools");
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        List<? extends AgentTool> ordered = tools.stream()
                .map(tool -> Objects.requireNonNull(tool, "tool"))
                .sorted(Comparator.comparing(tool -> tool.spec().name()))
                .toList();
        for (AgentTool tool : ordered) {
            AgentTool previous = indexed.put(tool.spec().name(), tool);
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
    public List<AgentTool> snapshot() {
        return snapshot;
    }

    /**
     * 仅在模型回显名称已注册时解析调用，未知名称立即失败关闭。
     */
    public AgentTool require(AgentTool.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        AgentTool tool = tools.get(invocation.toolName());
        if (tool == null) {
            throw new IllegalArgumentException("tool_not_found: " + invocation.toolName());
        }
        return tool;
    }
}
