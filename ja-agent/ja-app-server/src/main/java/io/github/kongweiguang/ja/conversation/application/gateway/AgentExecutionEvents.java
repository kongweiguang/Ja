// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;

/**
 * 隔离对话用例与 Engine 事件到 Ja 展示协议的具体投影器。
 */
public interface AgentExecutionEvents {
    /**
     * 在提交前订阅短暂流并追读持久事件，返回值只释放本次订阅。
     */
    AutoCloseable attach(AgentIdentity identity, ConversationTurnEventSink sink);

    /**
     * 校验 Engine 完成回执与持久终态水位一致后，组装 Ja 对话结果。
     */
    TurnResult result(AgentIdentity identity,
                      io.github.kongweiguang.ja.agent.application.api.TurnResult completedResult);

    /**
     * 取消与完成竞争时从持久 Store 精确重建已提交终态。
     */
    TurnResult terminalResult(AgentIdentity identity);
}
