// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定自动与手动上下文压缩失败阶段允许携带的 Token 证据。 */
final class ContextCompactionLifecycleTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);

    /** 首次计量前失败没有 before/after，仍必须发布唯一 Failed 终态。 */
    @Test
    void publishesFailureBeforeMeasurementWithoutTokenEvidence() {
        List<ContextCompactionEvent> events = new ArrayList<>();
        ContextCompactionLifecycle lifecycle = lifecycle(events);

        lifecycle.failBeforeStart(ContextCompactionEvent.Trigger.AUTOMATIC,
                ContextException.Code.INVALID_STATE);

        ContextCompactionEvent.Failed failed = assertInstanceOf(
                ContextCompactionEvent.Failed.class, events.getFirst());
        assertEquals(null, failed.context().inputTokensBefore());
        assertEquals(null, failed.context().inputTokensAfter());
        assertEquals(ContextCompactionEvent.ErrorCode.INVALID_STATE, failed.errorCode());
    }

    /**
     * Tool continuation 可能在官方 before 计量后才发生摘要失败；该证据必须保留在 Failed 事件中，
     * 不能由 phase 校验抛出 IllegalArgumentException 并遮蔽原始 Context failure。
     */
    @Test
    void publishesFailureAfterStartedWithBeforeTokenEvidence() {
        List<ContextCompactionEvent> events = new ArrayList<>();
        ContextCompactionLifecycle lifecycle = lifecycle(events);

        lifecycle.started(ContextCompactionEvent.Trigger.AUTOMATIC, 1_048_576);
        lifecycle.failed(ContextException.Code.SUMMARY_FAILURE);

        assertInstanceOf(ContextCompactionEvent.Started.class, events.getFirst());
        ContextCompactionEvent.Failed failed = assertInstanceOf(
                ContextCompactionEvent.Failed.class, events.getLast());
        assertEquals(1_048_576L, failed.context().inputTokensBefore());
        assertEquals(null, failed.context().inputTokensAfter());
        assertEquals(ContextCompactionEvent.ErrorCode.SUMMARY_FAILURE, failed.errorCode());
    }

    /** 自动回退后只有新 attempt 可再发终态，外层取消不得为已结束的尝试补发第二次失败。 */
    @Test
    void automaticFallbackAllowsNewAttemptWithoutDuplicateTerminalFailures() {
        List<ContextCompactionEvent> events = new ArrayList<>();
        ContextCompactionLifecycle lifecycle = lifecycle(events);
        lifecycle.started(ContextCompactionEvent.Trigger.AUTOMATIC, 30000);
        lifecycle.failedForAutomaticFallback(ContextException.Code.SUMMARY_FAILURE);
        lifecycle.cancelled(ContextCompactionEvent.Trigger.AUTOMATIC);
        assertEquals(2, events.size());
        lifecycle.started(ContextCompactionEvent.Trigger.OVERFLOW_RECOVERY, 30000);
        lifecycle.failed(ContextException.Code.SUMMARY_FAILURE);
        lifecycle.cancelled(ContextCompactionEvent.Trigger.OVERFLOW_RECOVERY);
        assertEquals(4, events.size());
        assertEquals(2, events.stream().filter(ContextCompactionEvent.Failed.class::isInstance).count());
        assertThrows(IllegalStateException.class,
                () -> lifecycle.started(ContextCompactionEvent.Trigger.AUTOMATIC, 30000));
    }

    /** Failed 不能携带只属于成功 Checkpoint 的 after 计量，避免 phase 闭集被可选 before 一并放宽。 */
    @Test
    void rejectsFailureWithAfterTokenEvidence() {
        ContextCompactionEvent.Context context = new ContextCompactionEvent.Context(
                "evt_failed", "ws_test", "thr_test", "turn_test", 4, CLOCK.instant(),
                "cmp_test", ContextCompactionEvent.Trigger.AUTOMATIC, 4,
                1_048_576L, 512L, ContextCompactionEvent.STRATEGY_VERSION);

        assertThrows(IllegalArgumentException.class,
                () -> new ContextCompactionEvent.Failed(
                        context, ContextCompactionEvent.ErrorCode.SUMMARY_FAILURE));
    }

    /** 首版策略身份必须严格匹配，开发期策略值不能被当作当前 Checkpoint 事实接收。 */
    @Test
    void rejectsNonCurrentStrategyVersion() {
        assertEquals("ja-context-v1", ContextCompactionEvent.STRATEGY_VERSION);
        assertThrows(IllegalArgumentException.class,
                () -> new ContextCompactionEvent.Context(
                        "evt_version", "ws_test", "thr_test", "turn_test", 4, CLOCK.instant(),
                        "cmp_test", ContextCompactionEvent.Trigger.AUTOMATIC, 4,
                        1_048_576L, null, "ja-context-v3"));
    }

    /** 构造固定身份与同步 Sink，使测试只观察 phase 合同而不引入 transport 调度。 */
    private static ContextCompactionLifecycle lifecycle(List<ContextCompactionEvent> events) {
        return new ContextCompactionLifecycle(
                "ws_test", "thr_test", "turn_test", 4, "cmp_test",
                event -> {
                    events.add(event);
                    return CompletableFuture.completedFuture(null);
                }, CLOCK);
    }
}
