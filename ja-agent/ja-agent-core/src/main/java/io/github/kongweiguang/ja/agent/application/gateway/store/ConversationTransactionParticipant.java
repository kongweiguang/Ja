// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

/**
 * 同步加入会话创建或 revision CAS；AgentStore 保留事务和资源生命周期所有权。
 */
@FunctionalInterface
public interface ConversationTransactionParticipant {
    /**
     * 空参与者表示会话事务没有宿主元数据要同步写入。
     */
    ConversationTransactionParticipant NONE = transaction -> {
    };

    /**
     * 借用 Store 当前事务资源写入宿主元数据；失败以运行时异常传播供 Store 回滚，且实现不得控制资源生命周期。
     */
    void apply(ConversationTransactionContext transaction);
}
