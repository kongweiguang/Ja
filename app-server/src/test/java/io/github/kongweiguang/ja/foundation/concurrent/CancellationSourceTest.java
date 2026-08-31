// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证连接级取消源的单调发布、竞态注册与及时解除语义。 */
final class CancellationSourceTest {
    /** 取消只允许一个胜者，且所有取消前注册回调恰好执行一次。 */
    @Test
    void cancellationIsMonotonicAndRunsCallbacksOnce() {
        CancellationSource source = new CancellationSource();
        AtomicInteger callbacks = new AtomicInteger();
        source.onCancellation(callbacks::incrementAndGet);

        assertTrue(source.cancel("runtime_closed").changed());
        assertFalse(source.cancel("second_reason").changed());
        assertEquals(1, callbacks.get());
        assertEquals("runtime_closed", source.reason().orElseThrow());
    }

    /** 已取消源立即执行迟到注册，而关闭 registration 会解除尚未触发的引用。 */
    @Test
    void lateRegistrationRunsImmediatelyAndClosedRegistrationIsRemoved() {
        CancellationSource source = new CancellationSource();
        AtomicInteger callbacks = new AtomicInteger();
        CancellationToken.Registration removed = source.onCancellation(callbacks::incrementAndGet);
        removed.close();
        source.cancel("done");
        source.onCancellation(callbacks::incrementAndGet);

        assertEquals(1, callbacks.get());
    }

    /** 一个资源清理失败不能阻断其它回调；故障交给连接关闭编排统一聚合。 */
    @Test
    void callbackFailureIsReportedAfterRemainingCallbacksRun() {
        CancellationSource source = new CancellationSource();
        AtomicInteger callbacks = new AtomicInteger();
        source.onCancellation(() -> {
            throw new IllegalStateException("close failed");
        });
        source.onCancellation(callbacks::incrementAndGet);

        CancellationSource.CancelResult result = source.cancel("runtime_closed");

        assertTrue(result.changed());
        assertEquals(1, callbacks.get());
        assertEquals("close failed", result.callbackFailure().orElseThrow().getMessage());
    }
}
