// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.shared;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用性质输入锁定 Provider 边界的资源上限、失败闭合与 Retry-After 预算。 */
final class ProviderInputFuzzTest {
    /** 任意 Header 都只能被拒绝或收敛到零至六十秒，不能产生负值、越界或解析崩溃。 */
    @FuzzTest(maxDuration = "30s", maxExecutions = 100_000)
    void retryAfterAlwaysStaysWithinTransportBudget(byte[] input) {
        String header = new String(Arrays.copyOf(input, Math.min(input.length, 4_096)), StandardCharsets.UTF_8);
        Duration parsed = RetryAfter.parse(header);
        if (parsed == null) {
            return;
        }
        assertFalse(parsed.isNegative());
        assertTrue(parsed.compareTo(Duration.ofSeconds(60)) <= 0);
    }

    /** SSE 解码后的任意文本只能形成对象或稳定协议错误，不再维护自写分帧器的性质测试。 */
    @FuzzTest(maxDuration = "30s", maxExecutions = 100_000)
    void providerJsonFailsClosedForArbitraryBytes(byte[] input) {
        byte[] bounded = Arrays.copyOf(input, Math.min(input.length, 16_384));
        try {
            assertTrue(AbstractStreamingModelAdapter.providerObject(
                    new String(bounded, StandardCharsets.UTF_8), "FUZZ_EVENT").isObject());
        } catch (ProviderProtocolException expected) {
            assertTrue(expected.code().matches("[A-Z][A-Z0-9_]{0,63}"));
        }
    }
}
