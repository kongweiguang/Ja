// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;

import java.util.Objects;

/**
 * Ja TurnState 到 JA-RPC 状态词汇的显式映射，避免枚举重命名静默改变协议。
 */
public final class TurnStateWire {
    /**
     * 仅通过静态显式映射暴露状态，避免实例化出可漂移的配置对象。
     */
    private TurnStateWire() {
    }

    /**
     * 显式固定所有公开状态拼写，使新领域状态必须经过协议评审后才能输出。
     */
    public static String value(TurnState state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case QUEUED -> "queued";
            case RUNNING -> "running";
            case WAITING_APPROVAL -> "waiting_approval";
            case WAITING_INPUT -> "waiting_input";
            case SUSPENDED -> "suspended";
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }
}
