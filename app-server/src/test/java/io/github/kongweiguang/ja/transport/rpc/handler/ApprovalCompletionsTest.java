// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** 验证审批响应尝试被拒绝后必须释放独占门闩，避免同一审批永久失去重试机会。 */
final class ApprovalCompletionsTest {
    /** 固定“拒绝只撤销本次占用”的约束，确保后续合法响应仍能认领同一审批。 */
    @Test
    void rejectedResponseCanBeRetried() {
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        ApprovalCompletions completions = new ApprovalCompletions(
                Clock.fixed(now, ZoneOffset.UTC), 8, 8);
        completions.requested("appr_test", "thr_test", "turn_test", now.plusSeconds(60));
        ApprovalCompletions.Pending first = completions.begin("appr_test", "turn_test");
        completions.rejected(first);
        assertDoesNotThrow(() -> completions.begin("appr_test", "turn_test"));
        completions.close();
    }
}
