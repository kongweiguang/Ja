// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 验证并发清理的异步包装拆解与共享 Deadline 行为。 */
final class CompletionFailuresTest {
    /** 已完成阶段直接返回结果，不应因为清理阶段已无等待需求而消耗预算。 */
    @Test
    void returnsCompletedStageResult() {
        assertEquals("closed", DeadlineBudget.after(Duration.ofSeconds(1))
                .await(CompletableFuture.completedFuture("closed"), "provider"));
    }

    /** 包装异常必须还原真实 RuntimeException 对象，保持清理失败身份可判断。 */
    @Test
    void preservesWrappedRuntimeFailure() {
        IllegalStateException root = new IllegalStateException("close failed");
        CompletableFuture<Void> failed = CompletableFuture.failedFuture(new CompletionException(root));

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> DeadlineBudget.after(Duration.ofSeconds(1)).await(failed, "provider"));

        assertSame(root, actual);
        assertSame(root, CompletionFailures.unwrap(new CompletionException(root)));
    }

    /** 过期阶段必须立即停止等待并返回稳定的 Deadline 异常。 */
    @Test
    void rejectsAwaitAfterExpiry() {
        assertThrows(DeadlineBudget.DeadlineExceededException.class,
                () -> DeadlineBudget.at(1).await(new CompletableFuture<>(), "rpc"));
    }

    /** 空 owner 会让清理诊断失去归属，必须在等待前拒绝。 */
    @Test
    void rejectsBlankOwner() {
        assertThrows(IllegalArgumentException.class,
                () -> DeadlineBudget.after(Duration.ofSeconds(1))
                        .await(CompletableFuture.completedFuture(null), " "));
    }
}
