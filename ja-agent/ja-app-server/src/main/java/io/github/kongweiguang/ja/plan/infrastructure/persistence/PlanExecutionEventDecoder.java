// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.persistence;

import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;

import java.sql.SQLException;

/**
 * 将 Plan 事务中尚未提交的事件写入适配为 Core 可校验的持久事件值。
 */
final class PlanExecutionEventDecoder {
    /**
     * 解码器只封装纯值转换，不保存请求或事务状态。
     */
    private PlanExecutionEventDecoder() {
        throw new AssertionError("no instances");
    }

    /**
     * 复用 Core 事件 codec 校验同一执行与提交版本，同时保留调用方的领域诊断。
     */
    static ExecutionEvent decode(ExecutionEventCodec codec, AgentStore.EventWrite event,
                                 AgentStoreTransactionContext transaction, long ordinal,
                                 String invalidMessage) throws SQLException {
        try {
            return codec.decode(new AgentStore.PersistedEvent(transaction.execution().conversation(),
                    transaction.execution(), event.eventId(), event.eventType(), event.payloadSchemaVersion(),
                    ordinal, transaction.committedConversationRevision(), transaction.committedMutationVersion(),
                    0, event.payload(), event.occurredAt()));
        } catch (RuntimeException invalid) {
            throw new SQLException(invalidMessage, invalid);
        }
    }
}


