// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;

import java.util.Objects;

/**
 * 会话路径决策所需的 Ja Turn 元数据，不承载执行游标或消息正文。
 *
 * @param turnId 本次可恢复执行的稳定标识
 * @param state 当前状态
 * @param mutationVersion 状态变更版本，用于并发提交校验
 * @param currentPath 是否为当前路径
 * @param sourceMessageId 来源消息标识
 */
public record TurnProjection(String turnId,
                             TurnState state,
                             long mutationVersion,
                             boolean currentPath,
                             String sourceMessageId) {
    /**
     * 拒绝无效身份与版本，使计划协调和当前历史筛选只依赖完整的持久化事实。
     */
    public TurnProjection {
        if (turnId == null || !turnId.startsWith("turn_") || turnId.length() > 128
            || !turnId.substring("turn_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid turnId");
        }
        Objects.requireNonNull(state, "state");
        if (mutationVersion < 0) {
            throw new IllegalArgumentException("invalid turn mutation version");
        }
        if (sourceMessageId != null && (!sourceMessageId.startsWith("item_") || sourceMessageId.length() > 128
                                        || !sourceMessageId.substring("item_".length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*"))) {
            throw new IllegalArgumentException("invalid source message identity");
        }
    }
}
