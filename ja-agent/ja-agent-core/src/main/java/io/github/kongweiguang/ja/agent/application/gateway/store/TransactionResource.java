// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

import java.util.Objects;

/**
 * 应用事务上下文携带的基础设施资源句柄。
 *
 * <p>应用契约只保存这个无技术类型的句柄，避免把 JDBC {@code Connection} 传播到
 * application/domain 包；具体基础设施在同步 participant 回调中按自身需要解包，并承担
 * 连接的生命周期、提交和回滚约束。</p>
 */
@FunctionalInterface
public interface TransactionResource {
    /**
     * 按调用方声明的基础设施类型取得当前事务资源；资源不存在或类型不匹配时立即失败，
     * 避免 participant 静默创建第二条连接而破坏原子提交。
     *
     * @param resourceType 期望的基础设施类型
     * @param <T> 资源类型
     * @return 当前事务资源
     */
    <T> T unwrap(Class<T> resourceType);

    /**
     * 将 Store 当前持有的资源封装成应用契约句柄；对象本身仍由 infrastructure 管理，
     * 该工厂只提供类型校验，不转移关闭、提交或回滚所有权。
     *
     * @param resource 当前事务资源
     * @return 只读解包句柄
     */
    static TransactionResource of(Object resource) {
        Object value = Objects.requireNonNull(resource, "resource");
        return new TransactionResource() {
            /** 按请求类型返回同一事务资源，避免 lambda 无法表达泛型方法而引入不安全强转。 */
            @Override
            public <T> T unwrap(Class<T> resourceType) {
                return Objects.requireNonNull(resourceType, "resourceType").cast(value);
            }
        };
    }
}
