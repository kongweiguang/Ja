// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import java.sql.SQLException;

/**
 * 让 Ja admission 在一次 Store 事务内明确安排投影前后的宿主事实。
 */
public interface JaAdmissionTransactionParticipant extends AgentStoreTransactionParticipant {
    /**
     * 在通用 Ja 投影前创建 Thread/Turn 锚点；SQL 故障由 Ja adapter 转为中立 Store 参与者错误。
     */
    void beforeProjection(AgentStoreTransactionContext transaction) throws SQLException;

    /**
     * 通用 USER/Turn 投影完成后追加 Task 等业务事实；仅传播 SQL 受检失败以保留明确适配边界。
     */
    void afterProjection(AgentStoreTransactionContext transaction) throws SQLException;

    /**
     * 两阶段参与者必须由 JaAgentStore 安排；直接调用会失去中间投影边界，因此明确失败。
     */
    @Override
    default void apply(AgentStoreTransactionContext transaction) {
        throw new IllegalStateException("Ja admission participant requires JaAgentStore phase ordering");
    }
}
