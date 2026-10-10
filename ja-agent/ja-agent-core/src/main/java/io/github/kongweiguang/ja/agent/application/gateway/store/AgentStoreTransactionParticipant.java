// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

/**
 * 同步加入 AgentStore 的提交事务；存储实现始终拥有提交、回滚和资源关闭权。
 */
@FunctionalInterface
public interface AgentStoreTransactionParticipant {
    /**
     * 空参与者表示本次提交没有宿主事实需要原子写入。
     */
    AgentStoreTransactionParticipant NONE = transaction -> {
    };

    /**
     * 借用当前 Store 事务资源完成宿主写入；失败以运行时异常传播供 Store 回滚，且实现不得控制资源生命周期。
     */
    void apply(AgentStoreTransactionContext transaction);
}
