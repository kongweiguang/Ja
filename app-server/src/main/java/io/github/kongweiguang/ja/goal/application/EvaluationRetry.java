// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Goal 与独立 Plan 验收共用瞬时 Provider 故障判定和可取消退避，避免两套恢复语义漂移。 */
final class EvaluationRetry {
    /** 恢复退避由调用方的取消令牌管理，本工具类不拥有线程或计时器。 */
    private EvaluationRetry() { }

    /** 只恢复上游瞬时故障；认证、模型或确定性协议错误由各自验收状态收口。 */
    static ModelPort.ModelUnavailableException retryableProvider(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelPort.ModelUnavailableException provider) {
                return switch (provider.terminalErrorCode()) {
                    case "MODEL_UNAVAILABLE", "MODEL_STREAM_INVALID", "MODEL_IDLE_TIMEOUT" -> provider;
                    default -> null;
                };
            }
        }
        return null;
    }

    /** 尊重有效 Retry-After，否则在 2–60 秒区间指数退避并加入抖动；次数不成为截止条件。 */
    static Duration delay(ModelPort.ModelUnavailableException failure, int attempt) {
        var server = failure.retryAfterHint();
        if (server.isPresent()) return server.get();
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        long millis = Math.min(60_000L, 2_000L * multiplier);
        return Duration.ofMillis((long) (millis * ThreadLocalRandom.current().nextDouble(0.75, 1.0)));
    }

    /** Stop/Plan pause 立即唤醒等待，不让迟到计时器重新取得模型租约。 */
    static void await(Duration delay, CancellationToken cancellation) {
        cancellation.throwIfCancellationRequested();
        CountDownLatch wakeup = new CountDownLatch(1);
        try (CancellationToken.Registration ignored = cancellation.onCancellation(wakeup::countDown)) {
            try {
                if (wakeup.await(delay.toNanos(), TimeUnit.NANOSECONDS))
                    cancellation.throwIfCancellationRequested();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("evaluator retry interrupted");
            }
        }
        cancellation.throwIfCancellationRequested();
    }
}
