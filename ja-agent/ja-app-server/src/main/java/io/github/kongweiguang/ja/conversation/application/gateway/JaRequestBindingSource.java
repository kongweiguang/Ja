// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;

import java.util.Objects;
import java.util.Optional;

/**
 * 把 Core 的稳定运行身份绑定到当前 Ja Thread、Workspace 与事务参与者。
 */
@FunctionalInterface
public interface JaRequestBindingSource {
    /**
     * 从当前 Ja 持久事实解析配置选择器及业务身份；恢复游标只作一致性输入，不承载路径、环境或凭据。
     */
    Binding bind(AgentRuntimeRequest request, Optional<AgentStore.ExecutionSnapshot> recoveredExecution);

    /**
     * 一次解析同时冻结 Ja 配置请求与同事务业务参与者；取消令牌由 Core 请求统一持有。
     *
     * @param runtimeRequest 运行时请求
     * @param transactionParticipant 参与同一存储事务的业务结算组件
     */
    record Binding(TurnRuntimeRequest runtimeRequest,
                   AgentStoreTransactionParticipant transactionParticipant) {
        /**
         * 要求完整 Ja 运行上下文；不复制 Core 的取消状态或注入第二个生命周期令牌。
         */
        public Binding {
            Objects.requireNonNull(runtimeRequest, "runtimeRequest");
            Objects.requireNonNull(transactionParticipant, "transactionParticipant");
        }
    }
}
