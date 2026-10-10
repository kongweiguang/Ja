// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.time.Instant;
import java.util.Objects;

/**
 * 只读执行状态，不暴露持久游标、Provider 凭据或工具正文。
 *
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param state 当前状态
 * @param threadRevision 对话修订版本
 * @param mutationVersion 状态变更版本，用于并发提交校验
 * @param updatedAt 最后更新时间
 */
public record AgentExecutionStatus(AgentIdentity identity,
                                   String state,
                                   long threadRevision,
                                   long mutationVersion,
                                   Instant updatedAt) {
    /**
     * 暴露会话 CAS 水位，宿主可以识别过期历史与队列视图。
     */
    public AgentExecutionStatus {
        Objects.requireNonNull(identity, "identity");
        if (state == null || state.isBlank() || state.length() > 128) {
            throw new IllegalArgumentException("invalid execution state");
        }
        if (threadRevision < 1 || mutationVersion < 1) {
            throw new IllegalArgumentException("invalid execution revision");
        }
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
