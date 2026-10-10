// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.shared;

import com.sun.net.httpserver.HttpServer;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.infrastructure.model.ModelAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.openai.OpenAiChatCompletionsAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.openai.OpenAiResponsesAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.anthropic.AnthropicMessagesAdapter;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.support.ModelAdapterTestSupport;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 真正保持 HTTP 连接打开，证明三协议业务终态与网络 EOF 已解除耦合。 */
final class ProviderTerminalBoundaryTest {
    /** 终态已经提交时必须返回，Chat 同时保留 finish_reason 后的 Usage 尾帧。 */
    @Test
    void completesAllProtocolsBeforeServerClosesConnection() throws Exception {
        for (ModelPort.Api api : ModelPort.Api.values()) {
            CountDownLatch release = new CountDownLatch(1);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(stream(api).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()), api, Duration.ofSeconds(15));
                List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
                try (ModelAdapter adapter = adapter(configuration)) {
                    ModelPort.ModelOutcome outcome = adapter.start(ModelAdapterTestSupport.request(configuration),
                            event -> {
                                events.add(event);
                                return CompletableFuture.completedFuture(null);
                            }, CancellationToken.none()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals(ModelPort.FinishReason.STOP, outcome.finishReason(), api.name());
                    assertEquals(1, events.stream().filter(ModelPort.UsageEvent.class::isInstance).count(), api.name());
                    assertEquals(1, release.getCount(), "server must still be waiting, not at EOF");
                }
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }

    /** 按真实 API 创建 Adapter，不借助反射或替代测试实现绕过生产 HTTP 生命周期。 */
    private static ModelAdapter adapter(ModelPort.ModelConfiguration configuration) {
        return switch (configuration.api()) {
            case OPENAI_RESPONSES -> new OpenAiResponsesAdapter(configuration);
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatCompletionsAdapter(configuration);
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesAdapter(configuration);
        };
    }

    /** 最小完整终态保持各厂商协议独立；Chat 的 Usage 刻意放在 finish_reason 后。 */
    private static String stream(ModelPort.Api api) {
        return switch (api) {
            case OPENAI_RESPONSES -> """
                event: response.completed
                data: {"type":"response.completed",\
                "sequence_number":0,"response":\
                """ + ModelAdapterTestSupport.openAiResponse(
                            "resp_terminal", "completed", new io.github.kongweiguang.ja.agent.domain.model.ModelUsage(1, 1, 2))
                    + "}\n\n";
            case ANTHROPIC_MESSAGES -> """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_terminal","type":"message","role":"assistant","content":[],"model":"test-model","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """;
            case OPENAI_CHAT_COMPLETIONS -> """
                    data: {"id":"chatcmpl_terminal","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}

                    data: {"id":"chatcmpl_terminal","object":"chat.completion.chunk","created":1,"model":"test-model","choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

                    data: [DONE]

                    """;
        };
    }
}
