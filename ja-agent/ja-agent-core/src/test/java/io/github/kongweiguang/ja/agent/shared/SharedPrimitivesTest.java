// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 验证共享原语的确定性摘要、输入边界和单调时间语义。 */
final class SharedPrimitivesTest {
    /** UTF-8 摘要必须跨平台稳定，并且短摘要只截取完整摘要的前缀。 */
    @Test
    void hashesUtf8Deterministically() {
        String full = Hashing.sha256Hex("中文");

        assertEquals(64, full.length());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Hashing.sha256Hex("hello"));
        assertEquals(full, Hashing.sha256HexBytes("中文".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(full.substring(0, 16), Hashing.sha256HexPrefix("中文", 8));
        assertNotEquals(Hashing.sha256Hex("a"), Hashing.sha256Hex("a\0"));
    }

    /** 字段 framing 必须区分不同字段组合，避免无分隔拼接的边界碰撞。 */
    @Test
    void framesHashFields() {
        assertNotEquals(Hashing.sha256FramedHex("ab", "c"), Hashing.sha256FramedHex("a", "bc"));
        assertEquals(Hashing.sha256FramedHex("a", "b"), Hashing.sha256FramedHex("a", "b"));
    }

    /** 标识和文本边界复用同一规则，异常消息只包含字段名。 */
    @Test
    void enforcesTextBoundaries() {
        assertEquals("thread-1", TextBoundary.identifier("thread-1", "threadId"));
        assertThrows(IllegalArgumentException.class, () -> TextBoundary.identifier(" bad", "threadId"));
        assertThrows(IllegalArgumentException.class, () -> TextBoundary.text("a\0b", "body", 10, true));
        assertThrows(IllegalArgumentException.class, () -> TextBoundary.singleLine("a\nb", "label", 10, false));
        assertEquals("", TextBoundary.text("", "body", 10, true));
    }

    /** 通用校验原语必须在边界失败并返回合法输入，便于调用方组合构造对象。 */
    @Test
    void validatesPreconditions() {
        assertEquals("value", Validation.required("value", "field"));
        assertEquals(2, Validation.positive(2, "limit"));
        assertEquals(0, Validation.nonNegative(0, "offset"));
        assertThrows(NullPointerException.class, () -> Validation.required(null, "field"));
        assertThrows(IllegalArgumentException.class, () -> Validation.positive(0, "limit"));
        assertThrows(IllegalArgumentException.class, () -> Validation.nonNegative(-1, "offset"));
        assertThrows(IllegalArgumentException.class, () -> Validation.require(false, "invalid"));
    }

    /** Deadline 只消费单调剩余时间，已过期值不能被下游重新解释为可等待。 */
    @Test
    void tracksMonotonicDeadline() {
        DeadlineBudget active = DeadlineBudget.after(Duration.ofMillis(100));

        assertFalse(active.expired());
        assertTrue(active.remainingNanos() > 0);
        assertTrue(active.remainingMillis() >= 1);
        assertEquals(active.deadlineNanos(), DeadlineBudget.at(active.deadlineNanos()).deadlineNanos());
        assertTrue(DeadlineBudget.at(1).expired());
        assertThrows(IllegalArgumentException.class, () -> DeadlineBudget.after(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> DeadlineBudget.after(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    /** 类型前缀和 owner 必须参与稳定身份，避免跨生命周期复用裸摘要。 */
    @Test
    void createsScopedStableIds() {
        String id = StableId.prefixed("evt", "execution-1", "record-1");

        assertTrue(id.startsWith("evt_"));
        assertEquals(id, StableId.prefixed("evt", "execution-1", "record-1"));
        assertNotEquals(id, StableId.prefixed("evt", "execution-2", "record-1"));
        assertThrows(IllegalArgumentException.class, () -> StableId.prefixed("bad prefix", "owner", "key"));
    }
}
