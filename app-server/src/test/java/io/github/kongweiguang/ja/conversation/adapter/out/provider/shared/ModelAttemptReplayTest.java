// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回放账本的纯状态回归，避免协议重试修复被流式分块或结构化事件边界破坏。 */
final class ModelAttemptReplayTest {
    /** 重试可以跨任意文本分块跳过旧前缀，并只返回尚未送达的尾部。 */
    @Test
    void skipsAcceptedPrefixAcrossDifferentChunking() {
        ModelAttemptReplay replay = new ModelAttemptReplay();
        ModelPort.TextDelta first = new ModelPort.TextDelta("persisted");
        replay.accepted(first, false);

        replay.beginAttempt(true);
        assertNull(replay.prepare(new ModelPort.TextDelta("per"), true));
        assertNull(replay.prepare(new ModelPort.TextDelta("sisted"), true));
        ModelPort.ModelEvent suffix = replay.prepare(new ModelPort.TextDelta(" repaired"), true);

        assertEquals(new ModelPort.TextDelta(" repaired"), suffix);
        replay.accepted(suffix, true);
        assertTrue(replay.canRetryAfterSemantic());
    }

    /** 前缀不一致时不猜测模型意图，立即阻止继续重放。 */
    @Test
    void rejectsChangedAcceptedPrefix() {
        ModelAttemptReplay replay = new ModelAttemptReplay();
        replay.accepted(new ModelPort.TextDelta("persisted"), false);
        replay.beginAttempt(true);

        assertThrows(ModelAttemptReplay.ReplayMismatchException.class,
                () -> replay.prepare(new ModelPort.TextDelta("different"), true));
    }

    /** Tool call 等结构化事实一旦送达就关闭自动重试，防止未知副作用被重复执行。 */
    @Test
    void structuredEventDisablesReplay() {
        ModelAttemptReplay replay = new ModelAttemptReplay();
        replay.accepted(new ModelPort.ToolCallReady(
                "call_1", "echo", JsonObject.empty(), 0), false);

        assertFalse(replay.canRetryAfterSemantic());
    }

    /** 第一次重试已经接纳新尾部后再次失败，下一次重试仍只跳过完整累计前缀。 */
    @Test
    void doesNotAppendAcceptedRetrySuffixTwice() {
        ModelAttemptReplay replay = new ModelAttemptReplay();
        replay.accepted(new ModelPort.TextDelta("persisted"), false);

        replay.beginAttempt(true);
        assertNull(replay.prepare(new ModelPort.TextDelta("persisted"), true));
        ModelPort.ModelEvent firstSuffix = replay.prepare(new ModelPort.TextDelta(" repaired"), true);
        replay.accepted(firstSuffix, true);

        replay.beginAttempt(true);
        assertNull(replay.prepare(new ModelPort.TextDelta("persisted"), true));
        assertNull(replay.prepare(new ModelPort.TextDelta(" repaired"), true));
        assertTrue(replay.canRetryAfterSemantic());
    }

    /** 重试正文比已送达前缀更短时，即使 Provider 返回 STOP 也必须拒绝伪造完整答案。 */
    @Test
    void rejectsShortPrefixAtNormalCompletion() {
        ModelAttemptReplay replay = new ModelAttemptReplay();
        replay.accepted(new ModelPort.TextDelta("persisted"), false);
        replay.beginAttempt(true);

        assertNull(replay.prepare(new ModelPort.TextDelta("persis"), true));
        assertThrows(ModelAttemptReplay.ReplayMismatchException.class,
                () -> replay.verifyComplete(true));
    }

    /** 未消费旧正文时禁止 Tool/Usage 等结构化事实越过回放边界。 */
    @Test
    void rejectsStructuredEventBeforePrefixCompletion() {
        ModelAttemptReplay replay = new ModelAttemptReplay();
        replay.accepted(new ModelPort.TextDelta("persisted"), false);
        replay.beginAttempt(true);

        assertThrows(ModelAttemptReplay.ReplayMismatchException.class,
                () -> replay.prepare(new ModelPort.ToolCallReady(
                        "call_1", "echo", JsonObject.empty(), 0), true));
    }

    /** 两个公开通道完整复现后允许正常终局，避免把短前缀校验误判为所有重试失败。 */
    @Test
    void acceptsCompletePrefixAtNormalCompletion() {
        ModelAttemptReplay replay = new ModelAttemptReplay();
        replay.accepted(new ModelPort.TextDelta("persisted"), false);
        replay.beginAttempt(true);

        assertNull(replay.prepare(new ModelPort.TextDelta("persisted"), true));
        replay.verifyComplete(true);
    }
}
