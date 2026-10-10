// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.infrastructure.model.summary;

import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.SummaryContextMessage;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ModelTransport;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.support.ModelAdapterTestSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证摘要准入、绝对 Deadline 与清理失败都不能放行无主请求或覆盖权威失败。 */
final class HttpSummaryModelAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    /** 取消注册不可用时不得产生 HTTP 副作用，也必须释放活动 Controller。 */
    @Test
    void registrationFailurePreventsProviderDispatch() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        IllegalStateException rejected = new IllegalStateException("registration rejected");
        CancellationToken cancellation = new ProbeCancellation() {
            /** 若实现提前派发，就等待该真实请求暴露竞态；正确实现无需派发就返回失败。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                try {
                    received.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture interrupted", failure);
                }
                throw rejected;
            }
        };
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            received.countDown();
            ModelAdapterTestSupport.status(exchange, 400);
        }); ModelTransport transport = new ModelTransport();
             HttpSummaryModel model = model(server, transport, cancellation, Clock.fixed(NOW, ZoneOffset.UTC))) {
            SummaryModel.SummaryPrompt prompt = prompt();
            model.estimateInputTokens(prompt);

            ContextException failure = assertThrows(ContextException.class, () -> model.summarize(prompt));

            assertSame(rejected, failure.getCause());
            assertEquals(0, server.calls());
            assertEquals(0, activeControllers(transport).size());
        }
    }

    /** 外部登记关闭失败作为补充诊断，不能覆盖 Provider 失败或阻断 Controller 清理。 */
    @Test
    void cleanupFailureRetainsProviderFailureAndReleasesController() throws Exception {
        IllegalStateException cleanup = new IllegalStateException("registration close failed");
        CancellationToken cancellation = new ProbeCancellation() {
            /** 故障只发生在退出路径，模型请求仍经过真实 HTTP 与状态机。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                return () -> { throw cleanup; };
            }
        };
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.status(exchange, 400));
             ModelTransport transport = new ModelTransport();
             HttpSummaryModel model = model(server, transport, cancellation, Clock.fixed(NOW, ZoneOffset.UTC))) {
            SummaryModel.SummaryPrompt prompt = prompt();
            model.estimateInputTokens(prompt);

            ContextException failure = assertThrows(ContextException.class, () -> model.summarize(prompt));

            assertInstanceOf(ProviderProtocolException.class, failure.getCause());
            assertEquals(List.of(cleanup), List.of(failure.getSuppressed()));
            assertEquals(1, server.calls());
            assertEquals(0, activeControllers(transport).size());
        }
    }

    /** 估算后等待消耗真实 Turn Deadline，不能从正式发送时重新开启超时窗口。 */
    @Test
    void expiredPreparedRequestNeverCallsProvider() throws Exception {
        AtomicReference<Instant> current = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            /** 时区固定以保持绝对预算与测试预期一致。 */
            @Override
            public ZoneId getZone() { return ZoneOffset.UTC; }

            /** 测试只调整时刻，不引入另一份时区状态。 */
            @Override
            public Clock withZone(ZoneId zone) { return this; }

            /** 从显式可控时刻模拟估算和发送之间的排队延迟。 */
            @Override
            public Instant instant() { return current.get(); }
        };
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.status(exchange, 400));
             ModelTransport transport = new ModelTransport();
             HttpSummaryModel model = model(server, transport, CancellationToken.none(), clock)) {
            SummaryModel.SummaryPrompt prompt = prompt();
            model.estimateInputTokens(prompt);
            current.set(NOW.plusSeconds(31));

            ContextException failure = assertThrows(ContextException.class, () -> model.summarize(prompt));

            assertEquals("summary model deadline expired", failure.getMessage());
            assertEquals(0, server.calls());
            assertEquals(0, activeControllers(transport).size());
        }
    }

    /** 唯一共享 transport owner 留在测试，摘要对象仅借用传输资源。 */
    private static HttpSummaryModel model(ModelAdapterTestSupport.Loopback server, ModelTransport transport,
                                          CancellationToken cancellation, Clock clock) {
        ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(
                server.baseUri(), ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
        return new HttpSummaryModel(new SummaryModel.TurnBinding("thread_summary", configuration,
                NOW.plusSeconds(30), cancellation), transport, clock);
    }

    /** 提示只包含最小有效证据，故障验证聚焦准入而非内容格式。 */
    private static SummaryModel.SummaryPrompt prompt() {
        return new SummaryModel.SummaryPrompt("ja-context-summary-v1", "strategy-v1", "thread_summary",
                Optional.empty(), List.of(new SummaryContextMessage("message_summary", "turn_summary", 1,
                        SummaryContextMessage.Role.USER, List.of(new SummaryContextMessage.Text("evidence")))),
                Optional.empty(), 500, List.of());
    }

    /** 只观察私有生命周期集合，不为白盒验证扩大生产 API。 */
    private static Set<?> activeControllers(ModelTransport transport) throws Exception {
        Field controllers = ModelTransport.class.getDeclaredField("activeControllers");
        controllers.setAccessible(true);
        return (Set<?>) controllers.get(transport);
    }

    /** 无取消事实的测试 token，仅允许各用例单独注入登记或关闭故障。 */
    private abstract static class ProbeCancellation implements CancellationToken {
        /** 本测试没有用户取消，失败只由资源注册路径产生。 */
        @Override
        public boolean isCancellationRequested() { return false; }

        /** 未取消的 token 不编造停止原因。 */
        @Override
        public Optional<String> reason() { return Optional.empty(); }
    }
}
