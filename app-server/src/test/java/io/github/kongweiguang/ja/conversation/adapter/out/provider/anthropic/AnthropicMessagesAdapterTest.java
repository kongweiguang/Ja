// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.anthropic;

import com.sun.net.httpserver.HttpServer;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;

import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;

import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Anthropic loopback fixture 覆盖原生请求形状、字节流、Tool 校验、usage 与终止规则。 */
final class AnthropicMessagesAdapterTest {
    private static final String BASE_REQUEST = """
            {"model":"test-model","max_tokens":1024,
            "system":"You are Ja, a coding agent.\\n\\nWork in the user's workspace with the available tools.\\nBe concise, follow applicable workspace guidance, verify material changes, and report results truthfully.\\n\\n<environment>\\nEnvironment: Windows 11\\n</environment>",
            "messages":[{"role":"user","content":[
            {"type":"text","text":"你好, model"}]}],
            "output_config":{"effort":"medium"},"tools":[{"name":"read_file",
            "description":"Read one file","input_schema":{"type":"object","properties":{
            "path":{"type":"string","minLength":1}},"required":["path"],
            "additionalProperties":false},"strict":true}],"stream":true}
            """;
    private static final String SUCCESS = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_test","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":7,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"call-1","name":"read_file","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{\\"pa"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"th\\":\\"README.md\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":3}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":11}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    /**
     * 延迟 message_stop 并要求首个 TextDelta 先到达 sink，锁定真实 Anthropic SSE 不能在
     * Adapter 内被聚合成最终整段；latch 门禁刻意不以最终事件顺序替代实时可见性。
     */
    @Test
    void publishesTextDeltaBeforeMessageStopArrives() throws Exception {
        String prefix = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_live","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":2,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"实时"}}

                """ + "\n";
        String suffix = """
                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        CountDownLatch releaseStop = new CountDownLatch(1);
        CountDownLatch textObserved = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(prefix.getBytes(StandardCharsets.UTF_8));
                output.flush();
                try {
                    if (!releaseStop.await(5, TimeUnit.SECONDS)) return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                output.write(suffix.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        });
        server.start();
        try {
            URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(baseUri,
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES, Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                var completion = adapter.start(ModelAdapterTestSupport.request(configuration), event -> {
                    if (event instanceof ModelPort.TextDelta delta && "实时".equals(delta.text())) {
                        textObserved.countDown();
                    }
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }, CancellationToken.none()).toCompletableFuture();
                assertTrue(textObserved.await(2, TimeUnit.SECONDS),
                        "TextDelta must be observable before message_stop is released");
                assertTrue(!completion.isDone());
                releaseStop.countDown();
                assertEquals(ModelPort.FinishReason.STOP,
                        completion.get(5, TimeUnit.SECONDS).finishReason());
            } finally {
                releaseStop.countDown();
            }
        } finally {
            server.stop(0);
        }
    }

    /** 以单字节网络分块传输，证明只有公开文本和校验后的 Tool 调用能进入 Kernel。 */
    @Test
    void streamsMessagesToolCallAndUsageWithoutPrivateThinking() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> version = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            ModelAdapterTestSupport.sse(exchange, SUCCESS, 1);
        })) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES, Duration.ofSeconds(5));
            List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ModelPort.ModelOutcome outcome = adapter.start(ModelAdapterTestSupport.request(configuration),
                                event -> { events.add(event); return java.util.concurrent.CompletableFuture.completedFuture(null); },
                                CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.TOOL_CALLS, outcome.finishReason());
                assertEquals(new ModelUsage(7, 11, 18), outcome.usage());
            }
            assertEquals("/v1/messages", requestPath.get());
            assertEquals("text/event-stream", accept.get());
            assertEquals("application/json; charset=utf-8", contentType.get());
            assertEquals("2023-06-01", version.get());
            assertEquals("test-secret", apiKey.get());
            ModelAdapterTestSupport.assertJsonEquals(BASE_REQUEST, requestBody.get());
            com.fasterxml.jackson.databind.JsonNode encodedRequest =
                    AbstractStreamingModelAdapter.JSON.readTree(requestBody.get());
            assertEquals(ModelAdapterTestSupport.SYSTEM_PROMPT,
                    encodedRequest.path("system").textValue());
            assertEquals(1, encodedRequest.path("messages").size());
            assertEquals(1, encodedRequest.path("messages").get(0).path("content").size());
            assertTrue(!encodedRequest.has("revision"));
            assertEquals(3, events.size());
            assertEquals("你好", assertInstanceOf(ModelPort.TextDelta.class, events.get(0)).text());
            ModelPort.ToolCallReady call = assertInstanceOf(ModelPort.ToolCallReady.class, events.get(1));
            assertEquals("README.md", assertInstanceOf(JsonText.class,
                    call.arguments().get("path")).value());
            assertEquals(new ModelUsage(7, 11, 18),
                    assertInstanceOf(ModelPort.UsageEvent.class, events.get(2)).usage());
            assertTrue(events.stream().noneMatch(event -> event.toString().contains("private chain")
                    || event.toString().contains("private signature")));
        }
    }

    /** 对终止文本响应丢弃私有 thinking，且不将其暴露为公开事件。 */
    @Test
    void discardsPrivateThinkingForTerminalResponse() throws Exception {
        String thinking = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_thinking","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":2,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private chain"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"private signature"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, thinking, 2))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ModelPort.ModelOutcome outcome = adapter.start(
                                ModelAdapterTestSupport.request(configuration), event -> {
                                    events.add(event);
                                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                                }, CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.STOP, outcome.finishReason());
            }
            assertEquals(1, events.size());
            assertInstanceOf(ModelPort.UsageEvent.class, events.getFirst());
            assertTrue(events.stream().noneMatch(event -> event.toString().contains("private")));
        }
    }

    /** 私有 thinking 仅进入不透明续传，并在下一轮原样放回对应 Tool assistant 块。 */
    @Test
    void continuesPrivateThinkingToolRoundWithoutPublishingIt() throws Exception {
        String thinkingTool = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_thinking_tool","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":2,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private chain"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"private signature"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call-private","name":"read_file","input":{"path":"README.md"}}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":2}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<String> secondRequest = new AtomicReference<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (call == 1) {
                        ModelAdapterTestSupport.sse(exchange, thinkingTool, 3);
                    } else {
                        secondRequest.set(body);
                        ModelAdapterTestSupport.sse(exchange, SUCCESS, 3);
                    }
                })) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ModelPort.ModelRequest first = ModelAdapterTestSupport.request(configuration);
                ModelPort.ModelOutcome outcome = adapter.start(first, event -> {
                            events.add(event);
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        }, CancellationToken.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.TOOL_CALLS, outcome.finishReason());
                assertEquals("anthropic_messages", outcome.continuation().protocol());
                assertTrue(!outcome.continuation().toString().contains("private chain"));

                ModelPort.ModelRequest continued = new ModelPort.ModelRequest(
                        configuration, first.prompt(), List.of(
                        first.messages().getFirst(),
                        new ModelMessage(ModelRole.ASSISTANT, List.of(
                                new ToolCallContent("call-private", "read_file",
                                        JsonObjects.builder().putText("path", "README.md").build()))),
                        new ModelMessage(ModelRole.TOOL, List.of(
                                new ToolResultContent("call-private", "contents", false)))),
                        first.tools(), outcome.continuation(), 2);
                adapter.start(continued, event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            assertTrue(events.stream().anyMatch(ModelPort.ToolCallReady.class::isInstance));
            assertTrue(events.stream().noneMatch(event -> event.toString().contains("private chain")
                    || event.toString().contains("private signature")));
            com.fasterxml.jackson.databind.JsonNode sent =
                    AbstractStreamingModelAdapter.JSON.readTree(secondRequest.get());
            com.fasterxml.jackson.databind.JsonNode assistant = sent.path("messages").get(1).path("content");
            assertEquals("thinking", assistant.get(0).path("type").asText());
            assertEquals("private chain", assistant.get(0).path("thinking").asText());
            assertEquals("private signature", assistant.get(0).path("signature").asText());
            assertEquals("tool_use", assistant.get(1).path("type").asText());
            assertEquals(2, server.calls());
        }
    }

    /** 在私有内容进入 sink 前拒绝 thinking 块中的文本 delta。 */
    @Test
    void rejectsTextDeltaInsideThinkingBlock() throws Exception {
        String mismatched = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_private","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"private-mismatch-sentinel"}}

                """;
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, mismatched, 3))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration), event -> {
                                    events.add(event);
                                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                                }, CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                ProviderProtocolException protocol =
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause());
                assertEquals("ANTHROPIC_EVENT", protocol.code());
                assertTrue(!protocol.getMessage().contains("private-mismatch-sentinel"));
            }
            assertTrue(events.isEmpty());
            assertEquals(1, server.calls());
        }
    }

    /** 未发布已校验 Tool 块时拒绝 tool_use 结束语义。 */
    @Test
    void rejectsToolStopReasonWithoutToolOutput() throws Exception {
        String mismatched = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_reason","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, mismatched, 7))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("FINISH_REASON",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 将 Anthropic 强类型终态停止原因映射为 Provider 无关的溢出信号。 */
    @Test
    void mapsTypedContextWindowStopReason() throws Exception {
        String overflow = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_overflow","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"model_context_window_exceeded"},"usage":{"output_tokens":0}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, overflow, 5))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                ModelPort.ContextOverflowException mapped = assertInstanceOf(
                        ModelPort.ContextOverflowException.class, failure.getCause());
                assertNull(mapped.getCause());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 对含糊的 Anthropic invalid_request 文本保持通用分类，不猜测上下文溢出。 */
    @Test
    void doesNotGuessOverflowFromErrorMessage() throws Exception {
        String error = "{\"type\":\"error\",\"error\":{"
                + "\"type\":\"invalid_request_error\","
                + "\"message\":\"private-context-message-sentinel\"},"
                + "\"request_id\":\"req_test\"}\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.json(exchange, 400, error))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                ProviderProtocolException protocol =
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause());
                assertEquals("HTTP_STATUS", protocol.code());
                assertTrue(protocol.getMessage().contains("HTTP status 400"));
                assertTrue(protocol.getMessage().contains("type invalid_request_error"));
                assertTrue(!protocol.getMessage().contains("private-context-message-sentinel"));
                assertNull(protocol.getCause());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 证明仅供 loopback 使用的占位凭据会在 Bridge 发送 HTTP 前移除。 */
    @Test
    void omitsPlaceholderApiKeyForKeylessLoopback() throws Exception {
        AtomicReference<String> apiKey = new AtomicReference<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            ModelAdapterTestSupport.sse(exchange, SUCCESS, 19);
        })) {
            ModelPort.ModelConfiguration configured = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES, Duration.ofSeconds(5));
            ModelPort.ModelConfiguration keyless = ModelAdapterTestSupport.withoutCredential(configured);
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(keyless)) {
                adapter.start(ModelAdapterTestSupport.request(keyless),
                                event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            assertNull(apiKey.get());
        }
    }

    /** 在发布 ToolCallReady 前拒绝违反冻结 Tool Schema 的已组装参数。 */
    @Test
    void rejectsToolArgumentsBeforePublication() throws Exception {
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, SUCCESS, 7))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES, Duration.ofSeconds(5));
            ModelPort.ModelRequest base = ModelAdapterTestSupport.request(configuration);
            io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec incompatible =
                    new io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec(
                            "read_file", "Read one file", JsonObjects.builder()
                                    .putText("type", "object")
                                    .put("properties", JsonObjects.builder()
                                            .put("path", JsonObjects.builder().putText("type", "integer").build())
                                            .build())
                                    .put("required", new JsonArray(List.of(new JsonText("path"))))
                                    .putBoolean("additionalProperties", false)
                                    .build());
            ModelPort.ModelRequest request = new ModelPort.ModelRequest(configuration, base.prompt(),
                    base.messages(), List.of(incompatible), null, 1);
            List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(request,
                                        event -> { events.add(event); return java.util.concurrent.CompletableFuture.completedFuture(null); },
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("TOOL_SCHEMA_INVALID",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
            }
            assertTrue(events.stream().noneMatch(ModelPort.ToolCallReady.class::isInstance));
        }
    }

    /** 将 message_stop 前的 EOF 视为可重试截断，不伪造成功结束原因。 */
    @Test
    void rejectsTruncatedStream() throws Exception {
        String truncated = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":"
                + "{\"id\":\"msg_truncated\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[],\"model\":\"claude-test\",\"stop_reason\":null,"
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, truncated, 3))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES, Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("STREAM_TRUNCATED",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
                assertEquals(3, server.calls());
            }
        }
    }

    /** 拒绝 Anthropic Core 可能静默忽略的事件名。 */
    @Test
    void rejectsUnknownSseEventName() throws Exception {
        String unknown = "event: future_private_event\ndata: {\"type\":\"future_private_event\"}\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, unknown, 1))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES, Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                ProviderProtocolException protocol =
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause());
                assertEquals("ANTHROPIC_EVENT", protocol.code());
                assertTrue(!protocol.getMessage().contains("future_private_event"));
            }
            assertEquals(1, server.calls());
        }
    }

    /** 在发布任何内容前拒绝非 assistant 或预填内容的 message_start 形状。 */
    @Test
    void rejectsInvalidMessageStartShape() throws Exception {
        String valid = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":"
                + "{\"id\":\"msg_shape\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[],\"model\":\"claude-test\",\"stop_reason\":null,"
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n";
        List<String> invalidFixtures = List.of(
                valid.replace("\"role\":\"assistant\"", "\"role\":\"user\""),
                valid.replace("\"content\":[]", "\"content\":[{\"type\":\"text\",\"text\":\"private\"}]")
        );
        for (String invalid : invalidFixtures) {
            try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                    (call, exchange) -> ModelAdapterTestSupport.sse(exchange, invalid, 2))) {
                ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                        ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                        Duration.ofSeconds(5));
                try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                    ExecutionException failure = assertThrows(ExecutionException.class, () ->
                            adapter.start(ModelAdapterTestSupport.request(configuration),
                                            event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                            CancellationToken.none())
                                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
                    assertEquals("ANTHROPIC_EVENT",
                            assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
                }
                assertEquals(1, server.calls());
            }
        }
    }

    /** 映射明确的 Provider 流错误，同时不保留 Provider 正文文本。 */
    @Test
    void rejectsProviderErrorWithoutSensitiveBody() throws Exception {
        String error = "event: error\ndata: {\"type\":\"error\",\"error\":"
                + "{\"type\":\"api_error\",\"message\":\"sensitive provider detail\"}}\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, error, 2))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES, Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                ProviderProtocolException protocol =
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause());
                assertEquals("PROVIDER_ERROR", protocol.code());
                assertTrue(!protocol.getMessage().contains("sensitive provider detail"));
                assertNull(protocol.getCause());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 通过原生块映射此前 Tool 历史，并完整保留反向代理路径前缀。 */
    @Test
    void mapsToolHistoryAndProxyBasePath() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestPath = new AtomicReference<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ModelAdapterTestSupport.sse(exchange, SUCCESS, 13);
        })) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(
                    java.net.URI.create(server.baseUri() + "/proxy/v1/messages"),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            ModelPort.ModelRequest base = ModelAdapterTestSupport.request(configuration);
            ModelPort.ModelRequest request = new ModelPort.ModelRequest(
                    configuration, base.prompt(), List.of(
                    base.messages().getFirst(),
                    new ModelMessage(ModelRole.ASSISTANT, List.of(
                            new ToolCallContent("old_call", "read_file",
                                    JsonObjects.builder().putText("path", "old.txt").build()))),
                    new ModelMessage(ModelRole.TOOL, List.of(
                            new ToolResultContent("old_call", "old contents", true)))),
                    base.tools(), null, 2);
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                adapter.start(request, event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            assertEquals("/proxy/v1/messages", requestPath.get());
            com.fasterxml.jackson.databind.node.ObjectNode expected =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            AbstractStreamingModelAdapter.JSON.readTree(BASE_REQUEST);
            com.fasterxml.jackson.databind.node.ArrayNode messages = expected.putArray("messages");
            com.fasterxml.jackson.databind.node.ArrayNode firstUserContent =
                    messages.addObject().put("role", "user").putArray("content");
            firstUserContent.addObject().put("type", "text").put("text", "你好, model");
            messages.addObject().put("role", "assistant").putArray("content").addObject()
                    .put("type", "tool_use").put("id", "old_call").put("name", "read_file")
                    .set("input", AbstractStreamingModelAdapter.JSON.readTree("{\"path\":\"old.txt\"}"));
            messages.addObject().put("role", "user").putArray("content").addObject()
                    .put("type", "tool_result").put("tool_use_id", "old_call")
                    .put("content", "old contents").put("is_error", true);
            assertEquals(expected, AbstractStreamingModelAdapter.JSON.readTree(requestBody.get()));
        }
    }

    /** 拒绝负数强类型 usage，不执行重试也不发布 usage 事件。 */
    @Test
    void rejectsInvalidUsage() throws Exception {
        String invalid = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":"
                + "{\"id\":\"msg_invalid_usage\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[],\"model\":\"claude-test\",\"stop_reason\":null,"
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":-1,\"output_tokens\":0}}}\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, invalid, 3))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("USAGE",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 在打开 HTTP 前拒绝当前 Anthropic API 已弃用的采样参数。 */
    @Test
    void rejectsDeprecatedSamplingControls() throws Exception {
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, SUCCESS, 3))) {
            ModelPort.ModelConfiguration base = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                    base.providerId(), base.modelId(), base.configGeneration(),
                    base.provider(), base.api(), base.model(),
                    base.baseUri(), base.apiKey(), base.connectTimeout(), base.requestTimeout(),
                    base.inputModalities(),
                    new ModelPort.GenerationOptions(0.2, null, 1_024, "medium"));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("GENERATION_OPTIONS",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
            }
            assertEquals(0, server.calls());
        }
    }

    /** 官方计量和正式发送复用同一冻结请求，除 transport-only stream 字段外 Token 正文完全相等。 */
    @Test
    void tokenCountAndSendShareOneFrozenEnvelope() throws Exception {
        AtomicReference<String> countBody = new AtomicReference<>();
        AtomicReference<String> sendBody = new AtomicReference<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (exchange.getRequestURI().getPath().endsWith("/count_tokens")) {
                countBody.set(body);
                ModelAdapterTestSupport.json(exchange, 200, "{\"input_tokens\":12}");
            } else {
                sendBody.set(body);
                ModelAdapterTestSupport.sse(exchange, SUCCESS, 11);
            }
        })) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES, Duration.ofSeconds(5));
            ModelPort.ModelRequest request = ModelAdapterTestSupport.request(configuration);
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ModelPort.InputTokenCount count = adapter.countInputTokens(request, CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                adapter.start(request, ignored -> java.util.concurrent.CompletableFuture.completedFuture(null),
                        CancellationToken.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(12, count.tokens());
            }
            com.fasterxml.jackson.databind.JsonNode counted = AbstractStreamingModelAdapter.JSON.readTree(countBody.get());
            com.fasterxml.jackson.databind.node.ObjectNode sent = (com.fasterxml.jackson.databind.node.ObjectNode)
                    AbstractStreamingModelAdapter.JSON.readTree(sendBody.get());
            sent.remove("stream");
            assertEquals(counted, sent);
            assertEquals(2, server.calls());
        }
    }

    /** 三次官方计量均失败时只访问 count_tokens，正式 Messages 请求必须保持零发送。 */
    @Test
    void tokenCountFailureNeverSendsModelRequest() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            paths.add(exchange.getRequestURI().getPath());
            ModelAdapterTestSupport.status(exchange, 503);
        })) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES, Duration.ofSeconds(5));
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () -> adapter
                        .countInputTokens(ModelAdapterTestSupport.request(configuration), CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertInstanceOf(ModelPort.TokenCountUnavailableException.class, failure.getCause());
            }
            assertEquals(3, paths.size());
            assertTrue(paths.stream().allMatch(path -> path.endsWith("/count_tokens")));
        }
    }

    /** 当 Provider 未发送 JSON delta 时接纳强类型空 Tool 输入。 */
    @Test
    void acceptsZeroArgumentTool() throws Exception {
        String zeroArgument = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_zero","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":2,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call-zero","name":"zero_tool","input":{}}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, zeroArgument, 4))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Provider.ANTHROPIC, ModelPort.Api.ANTHROPIC_MESSAGES,
                    Duration.ofSeconds(5));
            ModelPort.ModelRequest base = ModelAdapterTestSupport.request(configuration);
            io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec tool =
                    new io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec(
                            "zero_tool", "No arguments", JsonObjects.builder()
                                    .putText("type", "object")
                                    .put("properties", JsonObject.empty())
                                    .putBoolean("additionalProperties", false)
                                    .build());
            ModelPort.ModelRequest request = new ModelPort.ModelRequest(
                    configuration, base.prompt(), base.messages(), List.of(tool), null, 1);
            List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
            try (AnthropicMessagesAdapter adapter = new AnthropicMessagesAdapter(configuration)) {
                ModelPort.ModelOutcome outcome = adapter.start(request, event -> {
                            events.add(event);
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        }, CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.TOOL_CALLS, outcome.finishReason());
            }
            ModelPort.ToolCallReady call = assertInstanceOf(
                    ModelPort.ToolCallReady.class, events.getFirst());
            assertTrue(call.arguments().isEmpty());
        }
    }
}
