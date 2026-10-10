// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.runtime;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionKey;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.util.Objects;

/**
 * App Server 当前以单一本地用户运行；统一持有其产品身份，避免调用点各自补默认租户或用户。
 *
 * @param tenantId 本地宿主声明的租户标识
 * @param userId Thread 创建时冻结的本地主体标识
 */
public record AppServerIdentity(String tenantId,
                                String userId) {
    private static final AppServerIdentity LOCAL = new AppServerIdentity("ja.local", "user_local");

    /**
     * 返回本地宿主明确声明的主体；该值是本机应用身份，不表示远端已认证账号。
     */
    public static AppServerIdentity local() {
        return LOCAL;
    }

    /**
     * 身份必须完整且不可变，Thread owner 不得在 Turn 之间切换。
     */
    public AppServerIdentity {
        if (Objects.requireNonNull(tenantId, "tenantId").isBlank()
            || Objects.requireNonNull(userId, "userId").isBlank()) {
            throw new IllegalArgumentException("invalid App Server identity");
        }
    }

    /**
     * 从 App Server 唯一本地主体建立 Turn 身份，Wire 不接受调用方提供 owner。
     */
    public AgentIdentity execution(String threadId, String turnId) {
        return new AgentIdentity(tenantId, threadId, turnId, userId);
    }

    /**
     * Core 执行键继承持久化本地主体，拒绝其他租户或用户身份进入同一 Thread。
     */
    public ExecutionKey executionKey(AgentIdentity identity) {
        AgentIdentity value = Objects.requireNonNull(identity, "identity");
        if (!tenantId.equals(value.tenantId()) || !userId.equals(value.userId())) {
            throw new IllegalArgumentException("execution belongs to a different App Server identity");
        }
        return new ExecutionKey(conversation(value.threadId(), value.userId()), value.turnId());
    }

    /**
     * Thread owner 是创建时冻结的权威值；不匹配时拒绝跨主体读取 Core conversation。
     */
    public ConversationKey conversation(String threadId, String ownerUserId) {
        if (!userId.equals(Objects.requireNonNull(ownerUserId, "ownerUserId"))) {
            throw new IllegalArgumentException("Thread belongs to a different App Server owner");
        }
        return new ConversationKey(tenantId, threadId, ownerUserId);
    }

    /**
     * 创建或准入新的本地 Thread 时，Core 与产品投影共用同一 owner 身份。
     */
    public ConversationKey conversation(String threadId) {
        return conversation(threadId, userId);
    }
}
