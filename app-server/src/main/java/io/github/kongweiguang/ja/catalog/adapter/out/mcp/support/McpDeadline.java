// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * MCP 作用域内所有操作和传输共享的单调绝对生命周期边界。
 */
public final class McpDeadline {
    private final long absoluteNanos;
    private final LongSupplier nanoTime;

    /**
     * 保存不可变绝对边界及与其匹配的单调时钟。
     */
    private McpDeadline(long absoluteNanos, LongSupplier nanoTime) {
        this.absoluteNanos = absoluteNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * 只转换一次已准入 Turn 墙钟 Deadline；后续阶段只能消费该预算。
     */
    public static McpDeadline forTurn(Instant deadline, Clock clock, LongSupplier nanoTime) {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(clock, "clock");
        LongSupplier ticker = Objects.requireNonNull(nanoTime, "nanoTime");
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalStateException("turn_mcp_deadline_elapsed");
        }
        return after(remaining, ticker);
    }

    /**
     * 为 Settings/探测 Runtime 提供与 Turn 无关的显式生命周期作用域。
     */
    public static McpDeadline forOperation(McpLimits limits, LongSupplier nanoTime) {
        Objects.requireNonNull(limits, "limits");
        Duration lifecycle = saturatedPlus(
                saturatedPlus(limits.startupTimeout(), limits.requestTimeout()), limits.closeTimeout());
        return after(lifecycle, nanoTime);
    }

    /**
     * 同时按局部策略和作用域绝对边界限制单个阶段。
     */
    public long phaseDeadline(Duration cap) {
        Objects.requireNonNull(cap, "cap");
        return Math.min(absoluteNanos, saturatedAdd(nanoTime.getAsLong(), cap.toNanos()));
    }

    /**
     * 返回阶段剩余预算，并在作用域过期后拒绝创建传输。
     */
    public Duration remaining(Duration cap, String elapsedCode) {
        long phase = phaseDeadline(cap);
        long now = nanoTime.getAsLong();
        if (phase <= now) {
            throw new IllegalStateException(Objects.requireNonNull(elapsedCode, "elapsedCode"));
        }
        return Duration.ofNanos(phase - now);
    }

    /**
     * 使用建立边界的同一 Ticker 计算非负剩余值。
     */
    public long remainingNanos(long deadlineNanos) {
        long now = nanoTime.getAsLong();
        return deadlineNanos <= now ? 0 : deadlineNanos - now;
    }

    /**
     * 只向包内测试与生命周期 Adapter 暴露稳定边界值。
     */
    public long absoluteNanos() {
        return absoluteNanos;
    }

    /**
     * 启动有限单调作用域，并阻止纳秒溢出导致边界回绕。
     */
    private static McpDeadline after(Duration budget, LongSupplier nanoTime) {
        LongSupplier ticker = Objects.requireNonNull(nanoTime, "nanoTime");
        long now = ticker.getAsLong();
        return new McpDeadline(saturatedAdd(now, budget.toNanos()), ticker);
    }

    /**
     * 对 Duration 加法执行饱和处理，因为探测上限已独立校验为有限值。
     */
    private static Duration saturatedPlus(Duration left, Duration right) {
        try {
            return left.plus(right);
        } catch (ArithmeticException overflow) {
            return Duration.ofNanos(Long.MAX_VALUE);
        }
    }

    /**
     * 对单调时钟加法执行饱和处理，防止长生命周期进程使合法 Deadline 回绕。
     */
    private static long saturatedAdd(long now, long budget) {
        return now > Long.MAX_VALUE - budget ? Long.MAX_VALUE : now + budget;
    }
}
