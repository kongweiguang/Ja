// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;

import java.util.Objects;

/**
 * 表示唯一终态事务提交且终态事件完成投影后的 Turn 结果。
 */
public record TurnResult(TurnState state, String summary, TurnEvent.Terminal terminal) {
    /**
     * 关联领域状态和终态事件，拒绝应用层拼装互相矛盾的完成结果。
     */
    public TurnResult {
        Objects.requireNonNull(state, "state");
        if (!state.terminal() || terminal == null || terminal.state() != state) {
            throw new IllegalArgumentException("invalid turn result");
        }
        summary = summary == null ? "" : summary;
    }
}
