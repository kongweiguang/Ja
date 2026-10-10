// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.admission;

import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;

import java.sql.SQLException;
import java.util.Objects;

/**
 * 将同一 admission 中的通用 Core 输入解码给 Ja 业务投影，禁止再从 RPC 草稿复制一份。
 */
public final class JaAdmissionFactsCodec {
    /**
     * 该类型只提供无状态解码入口，不能实例化以免引入可变解码上下文。
     */
    private JaAdmissionFactsCodec() {
        throw new AssertionError("no instances");
    }

    /**
     * 只解码唯一普通 USER 事实；同事务排队消息由队列投影处理，不能冒充初始输入或清除 Skills。
     */
    public static UserMessage decodeUserMessage(ExecutionRecordCodec codec,
                                                AgentStoreTransactionContext transaction) throws SQLException {
        ExecutionRecordCodec decoder = Objects.requireNonNull(codec, "codec");
        AgentStoreTransactionContext tx = Objects.requireNonNull(transaction, "transaction");
        if (tx.kind() != AgentStore.TransactionKind.ADMISSION) {
            throw new SQLException("Ja user message can only be projected during admission");
        }
        UserMessage found = null;
        long sequence = 0;
        for (AgentStore.RecordWrite record : tx.records()) {
            sequence++;
            if (!"agent.user.message".equals(record.recordType())) {
                continue;
            }
            if (found != null) {
                throw new SQLException("Ja admission contains multiple user messages");
            }
            try {
                found = decoder.decodeUser(new AgentStore.StoredRecord(record.recordId(), record.recordType(),
                        record.payloadSchemaVersion(), sequence, record.payload(), record.occurredAt()));
            } catch (RuntimeException invalid) {
                throw new SQLException("Ja admission user message is invalid", invalid);
            }
        }
        if (found == null) {
            throw new SQLException("Ja admission has no user message");
        }
        return found;
    }
}

