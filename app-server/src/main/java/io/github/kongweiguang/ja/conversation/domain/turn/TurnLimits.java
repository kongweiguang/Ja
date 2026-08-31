// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.turn;

import java.time.Duration;
import java.util.Objects;

/**
 * 单个 Turn 在模型、Tool、Token 与墙钟时间上的不可扩大预算。
 */
public record TurnLimits(int maxModelRounds, int maxToolCalls, int maxInputTokens,
                         int maxOutputTokens, Duration wallTimeout) {
    /**
     * 在配置解析边界固定硬上限，避免运行循环依赖 Provider 自行终止。
     */
    public TurnLimits {
        if (maxModelRounds < 1 || maxModelRounds > 128
            || maxToolCalls < 0 || maxToolCalls > 1_024
            || maxInputTokens < 1 || maxInputTokens > 4_000_000
            || maxOutputTokens < 1 || maxOutputTokens > 1_000_000) {
            throw new IllegalArgumentException("turn limits are outside supported bounds");
        }
        Objects.requireNonNull(wallTimeout, "wallTimeout");
        if (wallTimeout.isNegative() || wallTimeout.isZero()
            || wallTimeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("wallTimeout must be in (0, 24h]");
        }
    }

    /**
     * 为测试与最小配置提供正式基线，而不是兼容旧配置的回退值。
     */
    public static TurnLimits defaults() {
        return new TurnLimits(32, 128, 256_000, 64_000, Duration.ofMinutes(30));
    }
}
