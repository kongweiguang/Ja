// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ja 压缩 wire 投影只接受真实阶段可表达的 token 与 revision 证据。 */
final class ContextCompactionEventTest {
    /** Started 只携带压缩前的真实计量，Compacted 必须降低 token 并推进 Thread revision。 */
    @Test
    void acceptsOnlyEvidenceSupportedByEachCommittedPhase() {
        ContextCompactionEvent.Context started = context(4, 4, 100L, null);
        ContextCompactionEvent.Context compacted = context(5, 4, 100L, 40L);

        assertDoesNotThrow(() -> new ContextCompactionEvent.Started(started));
        assertDoesNotThrow(() -> new ContextCompactionEvent.Compacted(compacted, "checkpoint_test"));
    }

    /** Failed 可保留已经测得的 before，但不能将尚未提交的压缩结果伪装为 after。 */
    @Test
    void rejectsAfterMeasurementOnFailedProjection() {
        ContextCompactionEvent.Context failed = context(4, 4, 100L, 40L);

        assertThrows(IllegalArgumentException.class,
                () -> new ContextCompactionEvent.Failed(failed, ContextCompactionEvent.ErrorCode.SUMMARY_FAILURE));
    }

    /** 手动压缩 wire 事件必须与已提交策略版本一致，避免客户端误解释不同版本的边界。 */
    @Test
    void rejectsUnknownStrategyVersion() {
        assertThrows(IllegalArgumentException.class, () -> new ContextCompactionEvent.Context(
                "evt_test", "ws_test", "thr_test", null, 4, Instant.EPOCH,
                "cmp_test", ContextCompactionEvent.Trigger.MANUAL, 4,
                100L, null, "ja-context-v2", null));
    }

    /** 构造只含 Ja wire 事件字段的固定上下文，避免测试接回已删除的 Core 生命周期对象。 */
    private static ContextCompactionEvent.Context context(long revision, long sourceRevision,
                                                           Long before, Long after) {
        return new ContextCompactionEvent.Context("evt_test", "ws_test", "thr_test", null,
                revision, Instant.EPOCH, "cmp_test", ContextCompactionEvent.Trigger.MANUAL,
                sourceRevision, before, after, ContextCompactionEvent.STRATEGY_VERSION, null);
    }
}
