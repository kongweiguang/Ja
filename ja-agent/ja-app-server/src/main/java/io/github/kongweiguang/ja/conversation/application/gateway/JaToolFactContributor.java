// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;

import java.util.List;
import java.util.Objects;

/**
 * 为所有 JaTool 提供可组合的应用事实贡献点，避免工具适配器依赖 Plan 等业务包。
 */
public interface JaToolFactContributor {
    /**
     * 返回注册时用于拒绝重复贡献者的稳定身份。
     */
    String id();

    /**
     * 在调用意图事务前构造贡献事实；该钩子不可执行外部动作或修改业务数据。
     */
    default List<AgentTool.ExtensionFact> prepared(
            AgentTool.Invocation invocation, JaTool.ExecutionContext context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        return List.of();
    }

    /**
     * 在持久 Started 边界生成运行阶段事实，确保 prepared 与 dispatch 之间的状态可恢复。
     */
    default List<AgentTool.ExtensionFact> started(
            AgentTool.Invocation invocation, JaTool.ExecutionContext context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        return List.of();
    }

    /**
     * 在真实 JaTool 结果已产生后构造结算事实，事实由 ToolResult 同一事务原子保存。
     */
    default List<AgentTool.ExtensionFact> settled(
            AgentTool.Invocation invocation, JaTool.ExecutionContext context, JaTool.ToolResult result) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(result, "result");
        return List.of();
    }
}
