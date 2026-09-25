// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.turn;

import java.time.Duration;
import java.util.Objects;

/** 模型窗口与单次请求执行窗口；不决定一个任务可运行多少轮或多久。 */
public record TurnLimits(int maxInputTokens, int maxOutputTokens, Duration requestWindow) {
    /**
     * 在配置解析边界固定硬上限，避免运行循环依赖 Provider 自行终止。
     */
    public TurnLimits {
        if (maxInputTokens < 1 || maxInputTokens > 4_000_000
            || maxOutputTokens < 1 || maxOutputTokens > 1_000_000) {
            throw new IllegalArgumentException("model token capabilities are outside supported bounds");
        }
        Objects.requireNonNull(requestWindow, "requestWindow");
        if (requestWindow.isNegative() || requestWindow.isZero()) {
            throw new IllegalArgumentException("request window must be positive");
        }
    }

    /**
     * 为测试与最小配置提供正式基线，而不是兼容旧配置的回退值。
     */
    public static TurnLimits defaults() {
        return new TurnLimits(256_000, 64_000, Duration.ofMinutes(5));
    }
}
