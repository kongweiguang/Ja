// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.infrastructure.model.shared;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.support.ModelAdapterTestSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 准入失败必须保持零执行和零活动保留，异常重新分类必须保持可追溯诊断。 */
final class ModelAdapterAdmissionTest {
    /** 共享传输先关闭后，反复失败准入也不能累计 Controller 或执行 Provider。 */
    @Test
    void closedTransportDoesNotRetainRejectedRequests() throws Exception {
        ModelPort.ModelRequest request = request();
        AtomicInteger attempts = new AtomicInteger();
        try (ModelTransport transport = new ModelTransport();
             ProbeAdapter adapter = new ProbeAdapter(request.configuration(), transport, attempts)) {
            transport.close();
            for (int index = 0; index < 8; index++) {
                assertThrows(IllegalStateException.class, () -> adapter.start(request,
                        ignored -> CompletableFuture.completedFuture(null), CancellationToken.none()));
            }
            assertEquals(0, activeControllers(adapter).size());
            assertEquals(0, attempts.get());
        }
    }

    /** 取消监听器登记失败时仍未调度 Provider，原始失败身份不能被清理覆盖。 */
    @Test
    void registrationFailureNeverStartsProviderAndReleasesReservation() throws Exception {
        ModelPort.ModelRequest request = request();
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException rejected = new IllegalStateException("cancellation registry unavailable");
        CancellationToken cancellation = new CancellationToken() {
            /** 本用例定位登记失败，不发布取消事实。 */
            @Override
            public boolean isCancellationRequested() {
                return false;
            }

            /** 没有取消事实就不生成原因。 */
            @Override
            public Optional<String> reason() {
                return Optional.empty();
            }

            /** 模拟宿主取消资源准入失败，不能触发模型执行。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                throw rejected;
            }
        };
        try (ModelTransport transport = new ModelTransport();
             ProbeAdapter adapter = new ProbeAdapter(request.configuration(), transport, attempts)) {
            assertSame(rejected, assertThrows(IllegalStateException.class, () -> adapter.start(request,
                    ignored -> CompletableFuture.completedFuture(null), cancellation)));
            assertEquals(0, activeControllers(adapter).size());
            assertEquals(0, attempts.get());
        }
    }

    /** 规范 STREAM_TRUNCATED 进入可恢复类别，且保留原协议异常及其底层诊断。 */
    @Test
    void streamClassificationRetainsOriginalFailureAndRetryHint() throws Exception {
        ModelPort.ModelRequest request = request();
        RuntimeException cause = new IllegalStateException("fixture stream failure");
        ProviderProtocolException original = new ProviderProtocolException("STREAM_TRUNCATED", "stream ended",
                false, cause, Duration.ofSeconds(2), "MODEL_PROTOCOL_ERROR");
        try (ModelTransport transport = new ModelTransport();
             ProbeAdapter adapter = new ProbeAdapter(request.configuration(), transport, new AtomicInteger()) {
                 /** 在共享分类入口前注入协议失败，仍经过真实调度和生命周期清理。 */
                 @Override
                 protected ModelPort.ModelOutcome executeProviderAttempt(ModelPort.ModelRequest modelRequest,
                         StreamContext context, RequestController controller) {
                     throw original;
                 }
             }) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> adapter.start(request,
                    ignored -> CompletableFuture.completedFuture(null), CancellationToken.none())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            ProviderProtocolException classified = (ProviderProtocolException) failure.getCause();
            assertEquals("MODEL_STREAM_INVALID", classified.terminalErrorCode());
            assertTrue(classified.retryable());
            assertSame(original, classified.getCause());
            assertSame(cause, classified.getCause().getCause());
            assertEquals(Optional.of(Duration.ofSeconds(2)), classified.retryAfter());
            assertEquals(0, activeControllers(adapter).size());
        }
    }

    /** 仅接受当前 Provider 流状态机发出的规范码，避免旧别名继续触发会话重试。 */
    @Test
    void doesNotClassifyLegacyStreamErrorAliasesAsRecoverable() throws Exception {
        ModelPort.ModelRequest request = request();
        for (String alias : List.of("TRUNCATED_STREAM", "INCOMPLETE_STREAM", "INCOMPLETE_RESPONSE")) {
            ProviderProtocolException original = new ProviderProtocolException(
                    alias, "stream ended", false, "MODEL_PROTOCOL_ERROR");
            try (ModelTransport transport = new ModelTransport();
                 ProbeAdapter adapter = new ProbeAdapter(request.configuration(), transport, new AtomicInteger()) {
                     /** 在共享分类入口前注入旧错误码，验证它不会得到额外重试语义。 */
                     @Override
                     protected ModelPort.ModelOutcome executeProviderAttempt(ModelPort.ModelRequest modelRequest,
                             StreamContext context, RequestController controller) {
                         throw original;
                     }
                 }) {
                ExecutionException failure = assertThrows(ExecutionException.class, () -> adapter.start(request,
                        ignored -> CompletableFuture.completedFuture(null), CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS));
                ProviderProtocolException classified = (ProviderProtocolException) failure.getCause();
                assertSame(original, classified);
                assertEquals("MODEL_PROTOCOL_ERROR", classified.terminalErrorCode());
                assertFalse(classified.retryable());
                assertEquals(0, activeControllers(adapter).size());
            }
        }
    }

    /** 请求仅提供冻结配置，测试不需要真实凭据或外部 Provider。 */
    private static ModelPort.ModelRequest request() {
        return ModelAdapterTestSupport.request(ModelAdapterTestSupport.configuration(
                URI.create("http://127.0.0.1:1"), ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5)));
    }

    /** 只观察私有资源集合的存活数量，不为测试扩展生产公开能力。 */
    private static Set<?> activeControllers(AbstractStreamingModelAdapter adapter) throws Exception {
        Field controllers = AbstractStreamingModelAdapter.class.getDeclaredField("activeControllers");
        controllers.setAccessible(true);
        return (Set<?>) controllers.get(adapter);
    }

    /** 保留真实共享传输与请求调度，协议尝试只记录是否发生副作用。 */
    private static class ProbeAdapter extends AbstractStreamingModelAdapter {
        private final AtomicInteger attempts;

        /** 测试借用共享传输，生命周期仍由测试的唯一 transport owner 关闭。 */
        private ProbeAdapter(ModelPort.ModelConfiguration configuration, ModelTransport transport,
                             AtomicInteger attempts) {
            super(configuration, transport);
            this.attempts = attempts;
        }

        /** 准入夹具不依赖 tokenizer，估算结果固定且不触发网络访问。 */
        @Override
        public ModelPort.InputTokenEstimate estimateInputTokens(ModelPort.ModelRequest request,
                CancellationToken cancellationToken) {
            return new ModelPort.InputTokenEstimate(1, 1, "0".repeat(64));
        }

        /** 准入测试不解码网络响应，只记录是否越过执行边界。 */
        @Override
        protected ModelPort.ModelOutcome executeProviderAttempt(ModelPort.ModelRequest request,
                StreamContext context, RequestController controller) {
            attempts.incrementAndGet();
            throw new AssertionError("rejected request must not execute a provider attempt");
        }
    }
}
