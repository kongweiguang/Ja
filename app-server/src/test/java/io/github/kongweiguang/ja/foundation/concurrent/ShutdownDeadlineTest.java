// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 验证整条关闭链共享同一单调期限，并保持稳定的异常传播边界。 */
final class ShutdownDeadlineTest {
    /** 拒绝非正数和可能破坏单调期限计算的过大预算，避免关闭过程退化为无界等待。 */
    @Test
    void rejectsInvalidBudgets() {
        assertThrows(IllegalArgumentException.class, () -> ShutdownDeadline.start(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ShutdownDeadline.start(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> ShutdownDeadline.start(Duration.ofNanos(Long.MAX_VALUE / 2)));
        assertThrows(IllegalArgumentException.class, () -> ShutdownDeadline.at(0));
    }

    /** 毫秒接口必须对尚未耗尽的纳秒预算向上取整，避免下游把正预算误判为立即超时。 */
    @Test
    void roundsPositiveRemainingBudgetUpToMilliseconds() {
        ShutdownDeadline deadline = ShutdownDeadline.start(Duration.ofMillis(100));

        long remainingMillis = deadline.remainingMillis();

        assertTrue(remainingMillis >= 1 && remainingMillis <= 100);
    }

    /** 已完成任务应直接返回结果，避免为无需等待的结果消耗剩余关闭预算。 */
    @Test
    void returnsCompletedStageResult() {
        ShutdownDeadline deadline = ShutdownDeadline.start(Duration.ofSeconds(1));

        String result = deadline.await(CompletableFuture.completedFuture("closed"), "provider");

        assertEquals("closed", result);
    }

    /** 异步包装异常必须被剥离到真实运行时根因，保持关闭失败的诊断语义。 */
    @Test
    void unwrapsCompletedStageFailure() {
        IllegalStateException rootCause = new IllegalStateException("provider close failed");
        CompletableFuture<Void> failed = CompletableFuture.failedFuture(new CompletionException(rootCause));
        ShutdownDeadline deadline = ShutdownDeadline.start(Duration.ofSeconds(1));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> deadline.await(failed, "provider"));

        assertSame(rootCause, failure);
    }

    /** 期限耗尽后不得继续等待未完成任务，必须把控制权交回进程级强制终止 owner。 */
    @Test
    void rejectsAwaitAfterDeadlineExpires() {
        ShutdownDeadline deadline = ShutdownDeadline.at(1);

        assertThrows(ShutdownDeadline.ForcedTerminationException.class,
                () -> deadline.await(new CompletableFuture<>(), "rpc ingress"));
    }

    /** 空 owner 会让强制终止日志失去归属，因此在进入等待前就必须拒绝。 */
    @Test
    void rejectsBlankOwner() {
        ShutdownDeadline deadline = ShutdownDeadline.start(Duration.ofSeconds(1));

        assertThrows(IllegalArgumentException.class,
                () -> deadline.await(CompletableFuture.completedFuture(null), " "));
    }
}
