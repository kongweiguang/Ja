// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.gateway;

import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;

import java.util.Map;

/**
 * 将 Core 唯一终态提交映射为 Task 状态、活动和跨 Thread 答复的同事务端口。
 */
public interface TaskTerminalProjection {
    /**
     * 只使用本次事务的精确记录引用；实现必须借用 transaction.connection 并让 AgentStore 独占提交权。
     */
    void settleTerminal(AgentStoreTransactionContext transaction, ExecutionEvent.Terminal terminal,
                        Map<String, ExecutionFact> factsByRecordId);
}
