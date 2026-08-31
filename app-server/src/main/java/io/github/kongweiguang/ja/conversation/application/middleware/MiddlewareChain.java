// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.middleware;

import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按构造顺序执行 before、逆序执行 after，并隔离提交后观察器故障。 */
public final class MiddlewareChain {
    private static final Logger LOGGER = LoggerFactory.getLogger(MiddlewareChain.class);
    private final List<AgentMiddleware> middleware;

    /** 复制 Composition Root 提供的固定列表，运行中不可注册、删除或重排。 */
    public MiddlewareChain(List<? extends AgentMiddleware> middleware) {
        this.middleware = List.copyOf(Objects.requireNonNull(middleware, "middleware"));
    }

    /** 正序执行模型前置观察器。 */
    public void beforeModel(ModelPort.ModelRequest request) {
        middleware.forEach(item -> item.beforeModel(request));
    }

    /** 逆序执行模型后置观察器，形成稳定的栈式包裹语义。 */
    public void afterModel(ModelPort.ModelRequest request, ModelPort.ModelOutcome outcome) {
        for (int index = middleware.size() - 1; index >= 0; index--) middleware.get(index).afterModel(request, outcome);
    }

    /** 正序执行 Tool 前置链，并在首次拒绝时立即短路。 */
    public AgentMiddleware.ToolDecision beforeTool(AgentMiddleware.ToolContext context) {
        for (AgentMiddleware item : middleware) {
            AgentMiddleware.ToolDecision decision = item.beforeTool(context);
            if (!decision.proceed()) return decision;
        }
        return AgentMiddleware.ToolDecision.allow();
    }

    /** 逆序执行 Tool 后置观察器。 */
    public void afterTool(AgentMiddleware.ToolContext context, AgentTool.ToolResult result) {
        for (int index = middleware.size() - 1; index >= 0; index--) middleware.get(index).afterTool(context, result);
    }

    /** 提交后观察器失败只记录类型，不影响已提交事实或客户端事件。 */
    public void onEventCommitted(TurnEvent event) {
        for (AgentMiddleware item : middleware) {
            try {
                item.onEventCommitted(event);
            } catch (RuntimeException failure) {
                LOGGER.warn("Agent Middleware onEventCommitted failed cause={}",
                        failure.getClass().getSimpleName());
            }
        }
    }
}
