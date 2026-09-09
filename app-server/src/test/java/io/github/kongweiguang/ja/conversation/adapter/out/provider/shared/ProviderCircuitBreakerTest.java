// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证共享 Provider 熔断的阈值、隔离键、半开单探测与取消语义。 */
final class ProviderCircuitBreakerTest {
    /** 三次失败后开路五分钟，到期只允许一个探测，成功后恢复普通流量。 */
    @Test
    void opensThenAllowsOneHalfOpenProbe() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-29T00:00:00Z"));
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(clock);
        ModelPort.ModelConfiguration configuration = configuration();
        for (int failure = 0; failure < 3; failure++) {
            breaker.acquire(configuration, ProviderCircuitBreaker.Operation.SEND).failure();
        }
        assertThrows(ProviderCircuitBreaker.CircuitOpenException.class,
                () -> breaker.acquire(configuration, ProviderCircuitBreaker.Operation.SEND));
        assertDoesNotThrow(() -> breaker.acquire(configuration, ProviderCircuitBreaker.Operation.SUMMARY)
                .cancelled());

        clock.advance(Duration.ofMinutes(5));
        ProviderCircuitBreaker.Permit probe = breaker.acquire(
                configuration, ProviderCircuitBreaker.Operation.SEND);
        assertThrows(ProviderCircuitBreaker.CircuitOpenException.class,
                () -> breaker.acquire(configuration, ProviderCircuitBreaker.Operation.SEND));
        probe.success();
        assertDoesNotThrow(() -> breaker.acquire(configuration, ProviderCircuitBreaker.Operation.SEND)
                .success());
    }

    /** 用户取消不累计失败，避免交互式停止误伤同端点后续请求。 */
    @Test
    void cancellationDoesNotOpenCircuit() {
        ProviderCircuitBreaker breaker = new ProviderCircuitBreaker(
                Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));
        for (int cancellation = 0; cancellation < 10; cancellation++) {
            breaker.acquire(configuration(), ProviderCircuitBreaker.Operation.SUMMARY).cancelled();
        }
        assertDoesNotThrow(() -> breaker.acquire(configuration(), ProviderCircuitBreaker.Operation.SUMMARY)
                .success());
    }

    /** 构造不含真实凭据的 loopback Provider/Model 快照，测试不会触发网络访问。 */
    private static ModelPort.ModelConfiguration configuration() {
        return new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test",
                ModelPort.Api.OPENAI_RESPONSES, "test-model",
                URI.create("http://127.0.0.1/v1"), "fixture-only-api-key", Duration.ofSeconds(1),
                Duration.ofSeconds(5), java.util.Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
    }

    /** 测试时钟只允许显式前进，避免真实等待五分钟。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        /** 固定初始瞬间，所有读写均在当前测试线程内完成。 */
        private MutableClock(Instant now) {
            this.now = now;
        }

        /** 熔断测试统一使用 UTC，不验证时区转换。 */
        @Override public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        /** 返回共享当前值；测试不需要派生另一时区实例。 */
        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        /** 返回当前可控瞬间。 */
        @Override public Instant instant() {
            return now;
        }

        /** 显式推进时间，不允许负时长倒退熔断窗口。 */
        private void advance(Duration duration) {
            if (duration.isNegative()) throw new IllegalArgumentException("duration must be non-negative");
            now = now.plus(duration);
        }
    }
}
