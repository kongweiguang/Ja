// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.util.Objects;

/**
 * 以完整执行者和稳定工具调用键推导 attempt identity，避免恢复时生成重复账本行。
 */
public final class PlanToolAttemptIdentity {
    /**
     * 工具类仅提供纯身份派生，不持有容器、数据库或缓存状态。
     */
    private PlanToolAttemptIdentity() {
        throw new AssertionError("no instances");
    }

    /**
     * 同一 callKey 在不同 tenant、thread、turn 或 user 下不能指向同一 Plan attempt。
     */
    public static String id(AgentIdentity identity, String callKey) {
        AgentIdentity actor = Objects.requireNonNull(identity, "identity");
        String key = Objects.requireNonNull(callKey, "callKey");
        if (!key.matches("call_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid callKey");
        }
        return "toolattempt_" + PlanDigest.sha256(String.join("\0", actor.tenantId(), actor.threadId(),
                actor.turnId(), actor.userId(), key));
    }
}

