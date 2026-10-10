// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定缓存子项缺失、显式零与 inclusive input 边界的归一化语义。 */
final class ModelUsageTest {
    /** Inclusive input 只扣明确存在的写缓存，缺读缓存时不得推测新输入。 */
    @Test
    void computesNewInputFromKnownInclusiveCacheFacts() {
        assertEquals(8L, new ModelUsage(10, 2, 12, 2L, null,
                ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE).newInputTokens());
        assertEquals(8L, new ModelUsage(10, 2, 12, 2L, 0L,
                ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE).newInputTokens());
        assertEquals(5L, new ModelUsage(10, 2, 12, 2L, 3L,
                ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE).newInputTokens());
        assertNull(new ModelUsage(10, 2, 12, null, 3L,
                ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE).newInputTokens());
    }

    /** 缓存读写之和不能超过原始 inclusive input；减法校验不依赖溢出的相加结果。 */
    @Test
    void rejectsCacheSubitemsBeyondInclusiveInput() {
        assertThrows(IllegalArgumentException.class, () -> new ModelUsage(5, 1, 6, 4L, 2L,
                ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE));
    }
}
