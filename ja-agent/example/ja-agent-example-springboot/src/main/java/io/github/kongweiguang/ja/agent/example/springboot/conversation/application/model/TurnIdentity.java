// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

/**
 * 用户是宿主业务身份；Thread 和 Turn 对应 Core 会话和执行。
 *
 * @param userId 发起执行的用户标识
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 */
public record TurnIdentity(String userId,
                           String threadId,
                           String turnId) {
    /**
     * 显式映射业务命名，不改变 Core 的租户隔离合同。
     */
    public static TurnIdentity from(AgentIdentity identity) {
        return new TurnIdentity(identity.userId(), identity.threadId(), identity.turnId());
    }
}
