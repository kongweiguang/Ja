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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 目录发现走真实 loopback HTTP，坏协议和准入失败都不能返回可用模型或遗留请求。 */
final class ModelCatalogClientTest {
    /** 空正文、非对象、重复字段和尾随对象必须明确失败，不能当成空目录或任取其中一个值。 */
    @Test
    void rejectsAmbiguousAndEmptyDirectoryPayloads() throws Exception {
        for (String body : java.util.List.of("", "null", "[]", "{\"data\":[],\"data\":[]}",
                "{\"data\":[]} {}")) {
            try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                    (call, exchange) -> ModelAdapterTestSupport.json(exchange, 200, body));
                 ModelTransport transport = new ModelTransport()) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        ModelCatalogClient.discover(transport, request(server.baseUri()), CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                ProviderProtocolException protocol = assertInstanceOf(ProviderProtocolException.class,
                        failure.getCause());
                assertEquals("MODEL_DISCOVERY_PROTOCOL", protocol.code());
                assertFalse(protocol.retryable());
                assertEquals(1, server.calls());
                assertEquals(0, activeControllers(transport).size());
            }
        }
    }

    /** 取消资源登记失败必须发生在 HTTP 派发前，保持原失败诊断和零活动请求。 */
    @Test
    void failedRegistrationDoesNotDispatchHttpOrRetainController() throws Exception {
        RuntimeException rejected = new IllegalStateException("cancellation registry unavailable");
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.json(exchange, 200, "{\"data\":[]}"));
             ModelTransport transport = new ModelTransport()) {
            ProviderProtocolException failure = assertThrows(ProviderProtocolException.class, () ->
                    ModelCatalogClient.discover(transport, request(server.baseUri()), new RegistrationFailure(rejected)));
            assertSame(rejected, failure.getCause());
            assertEquals(0, activeControllers(transport).size());
            assertEquals(0, server.calls());
        }
    }

    /** 请求使用隔离 Provider，只验证目录协议而不读取用户配置或凭据。 */
    private static ModelPort.ModelDiscoveryRequest request(URI endpoint) {
        return new ModelPort.ModelDiscoveryRequest("provider_fixture", "cfg_fixture",
                ModelPort.Api.OPENAI_RESPONSES, endpoint, "fixture-key", Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    /** 观察唯一 owner 的私有资源集合，避免为测试新增生产查询 API。 */
    private static Set<?> activeControllers(ModelTransport transport) throws Exception {
        Field controllers = ModelTransport.class.getDeclaredField("activeControllers");
        controllers.setAccessible(true);
        return (Set<?>) controllers.get(transport);
    }

    /**
     * 登记失败 fixture 不发布取消事实，唯一失败点是宿主监听资源接纳。
     *
     * @param failure 保持身份不变的登记失败，不能被派发或清理失败覆盖
     */
    private record RegistrationFailure(RuntimeException failure) implements CancellationToken {
        /** 未发布取消，避免测试误落入预取消分支。 */
        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        /** 没有取消事实就没有取消原因。 */
        @Override
        public Optional<String> reason() {
            return Optional.empty();
        }

        /** 拒绝登记并保留相同异常身份，用于验证清理不覆盖原因。 */
        @Override
        public Registration onCancellation(Runnable callback) {
            throw failure;
        }
    }
}
