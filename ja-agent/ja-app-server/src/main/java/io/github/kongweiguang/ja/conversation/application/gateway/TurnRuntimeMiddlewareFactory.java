// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;

import java.util.List;

/**
 * 由组合根注入的请求级中间件端口；Conversation 只消费能力，不依赖各业务模块的实现包。
 */
@FunctionalInterface
public interface TurnRuntimeMiddlewareFactory {
    /**
     * 按本次冻结的 Turn 身份创建中间件，调用方不得跨请求复用有状态实例。
     */
    List<AgentMiddleware> create(TurnRuntimeRequest request);
}
