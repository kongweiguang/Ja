// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用性质输入锁定 Provider 边界的资源上限、失败闭合与 Retry-After 预算。 */
final class ProviderInputFuzzTest {
    /** 任意 Header 都只能被拒绝或收敛到零至六十秒，不能产生负值、越界或解析崩溃。 */
    @FuzzTest(maxDuration = "30s", maxExecutions = 100_000)
    void retryAfterAlwaysStaysWithinTransportBudget(byte[] input) {
        String header = new String(Arrays.copyOf(input, Math.min(input.length, 4_096)), StandardCharsets.UTF_8);
        Duration parsed = RetryAfter.parse(header);
        if (parsed == null) return;
        assertFalse(parsed.isNegative());
        assertTrue(parsed.compareTo(Duration.ofSeconds(60)) <= 0);
    }

    /** 任意 SSE 字节只能形成有界事件、受控 IO 或稳定协议错误，不能逃逸其它运行时异常。 */
    @FuzzTest(maxDuration = "30s", maxExecutions = 100_000)
    void strictSseReaderFailsClosedForArbitraryBytes(byte[] input) throws IOException {
        byte[] bounded = Arrays.copyOf(input, Math.min(input.length, 16_384));
        ProviderSseReader reader = new ProviderSseReader(
                new BoundedSseInputStream(new ByteArrayInputStream(bounded), 2_048, 16_384, 32),
                Set.of("ping"), "FUZZ_EVENT");
        try {
            for (int event = 0; event < 32 && reader.next() != null; event++) {
                // 事件闭集和输入流自身预算共同阻止任意输入制造无界解析循环。
            }
        } catch (ProviderProtocolException expected) {
            assertTrue(expected.code().matches("[A-Z][A-Z0-9_]{0,63}"));
        }
    }
}
