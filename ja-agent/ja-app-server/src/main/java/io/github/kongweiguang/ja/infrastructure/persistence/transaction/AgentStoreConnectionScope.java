// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.transaction;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransaction;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Ja Repository 的同步写入限制在 AgentStore 当前事务连接内。
 */
public final class AgentStoreConnectionScope {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    /**
     * 事务连接作用域只通过同步 call 建立，禁止外部创建无效实例。
     */
    private AgentStoreConnectionScope() {
    }

    /**
     * 临时绑定 Store 已开启的连接；同连接可嵌套，任何独立连接都会被拒绝以防止跨库半提交。
     * 内部回调只暴露 JDBC 受检故障，业务运行时失败和 Error 保持原样由 Store 回滚。
     */
    public static <T> T call(Connection connection, Work<T> work) throws SQLException {
        Connection active = Objects.requireNonNull(connection, "connection");
        Work<T> operation = Objects.requireNonNull(work, "work");
        if (active.getAutoCommit()) {
            throw new SQLException("AgentStore participant requires an active transaction");
        }
        Scope previous = CURRENT.get();
        if (previous != null && previous.connection() != active) {
            throw new IllegalStateException("nested Ja persistence must use the AgentStore connection");
        }
        CURRENT.set(new Scope(active));
        try {
            return operation.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Core participant 不依赖 JDBC 异常；只在适配边界包装 SQL 故障，原样保留其余失败及外层回滚权。
     */
    public static <T> T participate(Connection connection, Work<T> work) {
        try {
            return call(connection, work);
        } catch (SQLException failure) {
            throw new AgentStore.StoreParticipantException("Ja transaction participant failed", failure);
        }
    }

    /**
     * 返回本线程当前 AgentStore 连接，调用方只能在同步 participant callback 期间使用。
     */
    public static Optional<Connection> current() {
        Scope scope = CURRENT.get();
        return scope == null ? Optional.empty() : Optional.of(scope.connection());
    }

    /**
     * 使用 Solon 注册的 MyBatis 配置为 Store 连接创建 session；Solon 事务工厂拒绝外部连接，
     * 因而这里显式用 MyBatis 的 JDBC Transaction 包装借用代理。真实连接归 AgentStore，调用方必须关闭返回的
     * SqlSession，代理会拒绝把提交、回滚或关闭权转移给 MyBatis。
     * PMD 将借用的连接代理误判为本方法取得的资源，仅在此处抑制该误报。
     */
    @SuppressWarnings("PMD.CloseResource")
    public static SqlSession openSession(SqlSessionFactory sessions) {
        SqlSessionFactory factory = Objects.requireNonNull(sessions, "sessions");
        Connection connection = current().orElseThrow(
                () -> new IllegalStateException("AgentStore connection scope is not active"));
        Connection borrowed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new BorrowedConnection(connection));
        Configuration configuration = factory.getConfiguration();
        Executor executor = configuration.newExecutor(new JdbcTransaction(borrowed), ExecutorType.SIMPLE);
        return new DefaultSqlSession(configuration, executor, false);
    }

    /**
     * 同步应用同连接业务变更的最小回调。
     */
    @FunctionalInterface
    public interface Work<T> {
        /**
         * 返回前必须完成所有 SQL；只声明 JDBC 故障，异步保存连接会越过 Store 事务生命周期。
         */
        T run() throws SQLException;
    }

    /**
     * 只在调用栈内维护连接身份和嵌套深度，连接本身始终归 AgentStore 所有。
     *
     * @param connection 连接
     */
    private record Scope(Connection connection) {
    }

    /**
         * 防止 MyBatis session 的默认 close、rollback 或 autocommit 重置触碰外层 Store 事务。
     *
     * @param delegate 委派执行者
     */
        private record BorrowedConnection(Connection delegate) implements InvocationHandler {
            /**
             * 代理只持有连接借用权，不转移生命周期、提交或回滚权。
             */
            private BorrowedConnection(Connection delegate) {
                this.delegate = Objects.requireNonNull(delegate, "delegate");
            }

            /**
             * 拒绝 JDBC 事务控制入口，其余 SQL 能力仍委托给 Store 正在使用的连接。
             */
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                String name = method.getName();
                if (name.equals("close") || name.equals("commit") || name.equals("rollback")
                    || name.equals("setAutoCommit") || name.equals("abort")
                    || name.equals("setSavepoint") || name.equals("releaseSavepoint")) {
                    return null;
                }
                if (name.equals("unwrap")) {
                    Class<?> requested = (Class<?>) arguments[0];
                    if (requested.isInstance(proxy)) {
                        return proxy;
                    }
                    throw new SQLException("borrowed AgentStore connection cannot be unwrapped");
                }
                if (name.equals("isWrapperFor")) {
                    return ((Class<?>) arguments[0]).isInstance(proxy);
                }
                if (method.getDeclaringClass() == Object.class) {
                    return switch (name) {
                        case "toString" -> "BorrowedAgentStoreConnection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new UnsupportedOperationException("unsupported connection object method");
                    };
                }
                try {
                    return method.invoke(delegate, arguments);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
        }
}
