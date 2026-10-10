// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.util.Objects;

/**
 * 一条与授权执行的用户身份关联的持久化执行事件。
 * 持久事件保留 Store 分配的水位，订阅者据此断点续读且不会把临时流当成已提交事实。
 *
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param persistedEvent 持久化事件
 */
public record AgentEvent(AgentIdentity identity,
                         AgentStore.PersistedEvent persistedEvent) {
    /**
     * 必须使用存储分配的序列，使订阅者可以按序续读，无需猜测本地事件顺序。
     * 校验事件执行身份与调用身份一致，避免把跨租户或跨会话事实投递给订阅者。
     */
    public AgentEvent {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(persistedEvent, "persistedEvent");
        if (!identity.tenantId().equals(persistedEvent.conversation().tenantId())
            || !identity.threadId().equals(persistedEvent.conversation().threadId())
            || !identity.turnId().equals(persistedEvent.execution().turnId())) {
            throw new IllegalArgumentException("event identity does not match persisted execution");
        }
    }
}
