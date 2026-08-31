// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Retry-After Policy 测试覆盖两种 wire 形式和有界畸形输入。 */
final class RetryAfterTest {
    /** 解析 delta-seconds，并把不可信服务端延迟限制在传输上限内。 */
    @Test
    void parsesAndBoundsSeconds() {
        assertEquals(Duration.ofSeconds(2), RetryAfter.parse("2"));
        assertEquals(Duration.ofSeconds(60), RetryAfter.parse("999999"));
        assertNull(RetryAfter.parse("-1"));
    }

    /** 拒绝畸形 Header 文本，使重试 Policy 回退到本地 jitter。 */
    @Test
    void rejectsMalformedHint() {
        assertNull(RetryAfter.parse("not-a-delay"));
        assertNull(RetryAfter.parse(""));
    }
}
