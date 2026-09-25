// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 共用 HTTP 错误边界通过真实 loopback 验证，两种 OpenAI API 不接触付费 Provider。 */
final class OpenAiProviderSupportTest {
    /** HTTP 408/409 属于瞬时交换失败，固定拒绝仍保持不可重试分类。 */
    @Test
    void classifiesTransientStatusesWithoutRetryingDeterministicRejection() {
        for (int status : List.of(408, 409, 429, 503)) {
            ProviderProtocolException failure = assertInstanceOf(ProviderProtocolException.class,
                    OpenAiProviderSupport.serviceFailure(status, new okhttp3.Headers.Builder().build(), null));
            assertEquals(true, failure.retryable());
            assertEquals("MODEL_UNAVAILABLE", failure.terminalErrorCode());
        }
        ProviderProtocolException rejected = assertInstanceOf(ProviderProtocolException.class,
                OpenAiProviderSupport.serviceFailure(401, new okhttp3.Headers.Builder().build(), null));
        assertFalse(rejected.retryable());
    }

    /**
     * 上游 401 可能只有 type 而没有 code；两种适配器都应保持拒绝分类，
     * 未知字段和自由文本不能因诊断构造失败而变成 INTERNAL_ERROR 或写进日志。
     */
    @Test
    void unauthorizedWithoutCodeRemainsUpstreamRejection() throws Exception {
        String body = "{\"error\":{\"type\":\"invalid_api_key\","
                + "\"message\":\"private-upstream-sentinel\","
                + "\"trace\":\"private-trace-sentinel\"}}";
        for (ModelPort.Api api : List.of(ModelPort.Api.OPENAI_RESPONSES,
                ModelPort.Api.OPENAI_CHAT_COMPLETIONS)) {
            try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                    (call, exchange) -> ModelAdapterTestSupport.json(exchange, 401, body))) {
                ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(
                        server.baseUri(), api, Duration.ofSeconds(5));
                try (ModelAdapter adapter = api == ModelPort.Api.OPENAI_RESPONSES
                        ? new OpenAiResponsesAdapter(configuration)
                        : new OpenAiChatCompletionsAdapter(configuration)) {
                    ExecutionException failure = assertThrows(ExecutionException.class, () ->
                            adapter.start(ModelAdapterTestSupport.request(configuration),
                                            event -> CompletableFuture.completedFuture(null),
                                            CancellationToken.none())
                                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
                    ProviderProtocolException protocol = assertInstanceOf(
                            ProviderProtocolException.class, failure.getCause());
                    assertEquals("HTTP_STATUS", protocol.code());
                    assertEquals("MODEL_UPSTREAM_REJECTED", protocol.terminalErrorCode());
                    assertEquals("provider returned HTTP status 401", protocol.getMessage());
                    assertFalse(protocol.toString().contains("private-upstream-sentinel"));
                    assertFalse(protocol.toString().contains("private-trace-sentinel"));
                    assertFalse(protocol.toString().contains("invalid_api_key"));
                }
                assertEquals(1, server.calls());
            }
        }
    }
}
