// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;

/**
 * 为一个明确的 Ja 请求身份创建同 Store 事务参与者，不暴露连接或基础设施实现。
 */
@FunctionalInterface
public interface AdmissionParticipantFactory {
    /**
     * 冻结本次 admission 的业务身份，返回只在 AgentStore 事务回调内工作的参与者。
     */
    AgentStoreTransactionParticipant create(AdmissionFacts facts);
}
