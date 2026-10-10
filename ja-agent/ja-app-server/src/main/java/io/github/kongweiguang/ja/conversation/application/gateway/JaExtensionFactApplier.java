// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;

import java.sql.SQLException;

/**
 * 将一个已持久化的 Ja 扩展事实应用到宿主数据库；事实应用与 AgentStore 提交共用同一事务。
 * 实现必须按固定 namespace、type 和 schemaVersion 识别操作，不得解释为任意方法调用。
 */
public interface JaExtensionFactApplier {
    /**
     * 返回唯一拥有此事实类型的 Ja 命名空间。
     */
    String namespace();

    /**
     * 返回该命名空间内稳定且显式支持的事实类型。
     */
    String type();

    /**
     * 返回唯一支持的事实结构版本；未知版本必须由调用方拒绝。
     */
    int schemaVersion();

    /**
     * 同步应用当前执行事务内的业务事实；只能使用上下文提供的连接，异常必须向外传播以回滚整次提交。
     */
    void apply(AgentStoreTransactionContext transaction, JsonObject payload) throws SQLException;

    /**
     * 在同一提交已精确配对 ToolCompleted 与 terminal ToolResult 后，允许业务 ledger 根据 Core 结果结算
     * 本地未启动 intent；默认不投影，避免扩展事实 applier 被迫接受无关 Tool 生命周期。
     */
    default void applyToolCompletion(AgentStoreTransactionContext transaction,
                                     ExecutionEvent.ToolCompleted completed,
                                     ExecutionFacts.ToolResult result) throws SQLException {
        // 仅拥有对应业务 intent 的 applier 才需要消费此已验证 Core 结果。
    }
}
