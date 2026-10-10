// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.transaction;

import io.github.kongweiguang.ja.agent.application.gateway.store.ConversationTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.TransactionResource;

import java.sql.Connection;
import java.util.Objects;

/**
 * App Server JDBC 适配器的唯一资源解包入口。
 *
 * <p>application.gateway.store 只携带无技术类型的资源句柄；所有 Connection 获取集中在此处，
 * 便于验证 participant 仍在 Store 借用的同一事务内执行，也避免基础设施类型向领域传播。</p>
 */
public final class JdbcTransactionResources {
    /**
     * 禁止构造工具类，避免把连接生命周期误建模成可实例化服务。
     */
    private JdbcTransactionResources() {
    }

    /**
     * 从执行事务上下文解包 Store 当前持有的连接；连接仍由 Store 负责关闭和事务控制。
     *
     * @param transaction 执行事务上下文
     * @return 当前 JDBC 连接
     */
    public static Connection connection(AgentStoreTransactionContext transaction) {
        return connection(Objects.requireNonNull(transaction, "transaction").resources());
    }

    /**
     * 从会话事务上下文解包 Store 当前持有的连接，供同事务会话投影使用。
     *
     * @param transaction 会话事务上下文
     * @return 当前 JDBC 连接
     */
    public static Connection connection(ConversationTransactionContext transaction) {
        return connection(Objects.requireNonNull(transaction, "transaction").resources());
    }

    /**
     * 对底层资源句柄做一次受约束的 JDBC 解包，拒绝空句柄和错误类型。
     *
     * @param resources 应用事务资源句柄
     * @return 当前 JDBC 连接
     */
    public static Connection connection(TransactionResource resources) {
        return Objects.requireNonNull(resources, "resources").unwrap(Connection.class);
    }
}
