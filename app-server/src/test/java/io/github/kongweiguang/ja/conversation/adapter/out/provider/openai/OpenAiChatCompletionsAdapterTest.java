// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 以真实 loopback HTTP 覆盖 Chat Completions 请求、Tool delta、usage 与终态。 */
final class OpenAiChatCompletionsAdapterTest {
    private static final String TOOL_STREAM =
            chunk("[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                    + "\"content\":\"I will read it.\"},\"finish_reason\":null}]", null)
            + chunk("[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"id\":\"call_1\",\"type\":\"function\",\"function\":{"
                    + "\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\"}}]},"
                    + "\"finish_reason\":null}]", null)
            + chunk("[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                    + "\"function\":{\"arguments\":\"\\\"README.md\\\"}\"}}]},"
                    + "\"finish_reason\":null}]", null)
            + chunk("[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]", null)
            + chunk("[]", "{\"prompt_tokens\":5,\"completion_tokens\":9,\"total_tokens\":14,"
                    + "\"prompt_tokens_details\":{\"cached_tokens\":4},"
                    + "\"completion_tokens_details\":{\"reasoning_tokens\":8}}")
            + "data: [DONE]\n\n";

    /** 目录错误、Schema 不匹配和 stop 标签差异都交给工具循环，不在已完整组装后拒绝调用。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"unknown", "invalid_schema_arguments", "stop"})
    void preservesRecoverableToolCalls(String scenario) throws Exception {
        String stream = scenario.equals("stop")
                ? TOOL_STREAM.replace("\"finish_reason\":\"tool_calls\"", "\"finish_reason\":\"stop\"")
                : TOOL_STREAM;
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, stream, 3))) {
            ModelPort.ModelConfiguration configuration = configuration(server);
            ModelPort.ModelRequest base = ModelAdapterTestSupport.request(configuration);
            var tools = scenario.equals("unknown") ? List.<io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec>of()
                    : scenario.equals("invalid_schema_arguments")
                    ? List.of(new io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec(
                            "read_file", "Read", io.github.kongweiguang.ja.foundation.json.JsonObjects.builder()
                                    .putText("type", "object")
                                    .put("properties", io.github.kongweiguang.ja.foundation.json.JsonObject.empty())
                                    .putBoolean("additionalProperties", false).build()))
                    : base.tools();
            try (OpenAiChatCompletionsAdapter adapter = new OpenAiChatCompletionsAdapter(configuration)) {
                ModelPort.ModelOutcome outcome = adapter.start(new ModelPort.ModelRequest(configuration,
                                base.prompt(), base.messages(), tools, null, 1), event -> {
                                    events.add(event);
                                    return CompletableFuture.completedFuture(null);
                                }, CancellationToken.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.TOOL_CALLS, outcome.finishReason());
            }
            ModelPort.ToolCallReady tool = events.stream().filter(ModelPort.ToolCallReady.class::isInstance)
                    .map(ModelPort.ToolCallReady.class::cast).findFirst().orElseThrow();
            assertEquals("README.md", assertInstanceOf(JsonText.class, tool.arguments().get("path")).value());
            assertEquals(1, server.calls());
        }
    }

    /** 请求前估算必须零网络，并与随后发送的完整冻结正文绑定同一字节上界。 */
    @Test
    void mapsNativeRequestToolDeltasAndUsageOnlyChunk() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> {
                    path.set(exchange.getRequestURI().getPath());
                    requestBody.set(new String(
                            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    ModelAdapterTestSupport.sse(exchange, TOOL_STREAM, 5);
                })) {
            ModelPort.ModelConfiguration configuration = configuration(server);
            ModelPort.ModelRequest request = ModelAdapterTestSupport.request(configuration);
            try (OpenAiChatCompletionsAdapter adapter =
                         new OpenAiChatCompletionsAdapter(configuration)) {
                ModelPort.InputTokenEstimate estimate =
                        adapter.estimateInputTokens(request, CancellationToken.none());
                assertEquals(0, server.calls());
                ModelPort.ModelOutcome outcome = adapter.start(request, event -> {
                            events.add(event);
                            return CompletableFuture.completedFuture(null);
                        }, CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);

                assertEquals(ModelPort.FinishReason.TOOL_CALLS, outcome.finishReason());
                assertNull(outcome.continuation());
                assertEquals(new ModelUsage(5, 9, 14), outcome.usage());
                assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(requestBody.get().getBytes(StandardCharsets.UTF_8))), estimate.fingerprint());
                assertTrue(estimate.conservativeUpperBound() > 0);
                assertTrue(estimate.conservativeUpperBound() < requestBody.get().getBytes(StandardCharsets.UTF_8).length);
            }
            assertEquals(1, server.calls());
        }

        assertEquals("/v1/chat/completions", path.get());
        var root = AbstractStreamingModelAdapter.JSON.readTree(requestBody.get());
        assertEquals("test-model", root.path("model").textValue());
        assertEquals("system", root.path("messages").get(0).path("role").textValue());
        assertEquals("user", root.path("messages").get(1).path("role").textValue());
        assertTrue(root.path("stream").booleanValue());
        assertTrue(root.path("stream_options").path("include_usage").booleanValue());
        assertEquals(1024, root.path("max_completion_tokens").intValue());
        assertFalse(root.has("max_tokens"));
        assertEquals("I will read it.",
                assertInstanceOf(ModelPort.TextDelta.class, events.get(0)).text());
        ModelPort.ToolCallReady tool =
                assertInstanceOf(ModelPort.ToolCallReady.class, events.get(1));
        assertEquals("call_1", tool.callId());
        assertEquals("README.md", assertInstanceOf(JsonText.class,
                tool.arguments().get("path")).value());
        assertEquals(new ModelUsage(5, 9, 14),
                assertInstanceOf(ModelPort.UsageEvent.class, events.get(2)).usage());
    }

    /**
     * 兼容网关用 stop 结束完整未知 Tool 时仍发布结构化调用；未知名称由 Runner 回注错误，Adapter
     * 不得把合法调用误判为整轮协议失败。
     */
    @Test
    void stopWithCompleteUnknownToolPublishesRecoverableCall() throws Exception {
        String stream = chunk("[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_unknown\",\"type\":\"function\",\"function\":{"
                        + "\"name\":\"missing_tool\",\"arguments\":\"{}\"}}]},"
                        + "\"finish_reason\":\"stop\"}]", null)
                + "data: [DONE]\n\n";
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        ModelPort.ModelOutcome outcome;
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, stream, 7))) {
            ModelPort.ModelConfiguration configuration = configuration(server);
            try (OpenAiChatCompletionsAdapter adapter = new OpenAiChatCompletionsAdapter(configuration)) {
                outcome = adapter.start(ModelAdapterTestSupport.request(configuration), event -> {
                            events.add(event);
                            return CompletableFuture.completedFuture(null);
                        }, CancellationToken.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(ModelPort.FinishReason.TOOL_CALLS, outcome.finishReason());
        ModelPort.ToolCallReady tool = assertInstanceOf(ModelPort.ToolCallReady.class, events.getFirst());
        assertEquals("missing_tool", tool.name());
        assertEquals(JsonObjects.builder().build(), tool.arguments());
    }

    /** Schema 不匹配的合法 JSON 对象仍完整发布，执行前由统一 Runner 校验并保证零副作用。 */
    @Test
    void publishesSchemaInvalidArgumentsForRunnerValidation() throws Exception {
        String stream = chunk("[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_invalid\",\"type\":\"function\",\"function\":{"
                        + "\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":7}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]", null)
                + "data: [DONE]\n\n";
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, stream, 7))) {
            ModelPort.ModelConfiguration configuration = configuration(server);
            try (OpenAiChatCompletionsAdapter adapter = new OpenAiChatCompletionsAdapter(configuration)) {
                ModelPort.ModelOutcome outcome = adapter.start(
                                ModelAdapterTestSupport.request(configuration), event -> {
                                    events.add(event);
                                    return CompletableFuture.completedFuture(null);
                                }, CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.TOOL_CALLS, outcome.finishReason());
            }
        }
        ModelPort.ToolCallReady tool = assertInstanceOf(ModelPort.ToolCallReady.class, events.getFirst());
        assertEquals(JsonObjects.builder().putNumber("path", 7).build(), tool.arguments());
    }

    /** stop 标签不能让畸形 JSON 或缺失调用身份越过完整性边界。 */
    @Test
    void rejectsMalformedOrIncompleteToolCallsWithStopReason() throws Exception {
        List<String> invalidStreams = List.of(
                chunk("[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                                + "\"id\":\"call_bad_json\",\"type\":\"function\",\"function\":{"
                                + "\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\"}}]},"
                                + "\"finish_reason\":\"stop\"}]", null),
                chunk("[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                                + "\"type\":\"function\",\"function\":{\"name\":\"read_file\","
                                + "\"arguments\":\"{}\"}}]},\"finish_reason\":\"stop\"}]", null));
        for (String stream : invalidStreams) {
            Exception failure = assertThrows(Exception.class, () -> execute(stream + "data: [DONE]\n\n"));
            assertTrue(List.of("TOOL_ARGUMENTS", "OPENAI_CHAT_EVENT")
                    .contains(providerFailure(failure).code()));
        }
    }

    /**
     * Tool 的稳定错误码和安全说明必须原样进入下一轮 Chat 请求；否则模型只看到空失败，
     * 会重复猜路径或命令并最终耗尽轮次预算。
     */
    @Test
    void projectsSafeToolFailureIntoFollowingChatRound() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String stream = chunk(
                "[{\"index\":0,\"delta\":{\"content\":\"已根据失败原因收口。\"},"
                        + "\"finish_reason\":\"stop\"}]",
                "{\"prompt_tokens\":8,\"completion_tokens\":4,\"total_tokens\":12}")
                + "data: [DONE]\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> {
                    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    ModelAdapterTestSupport.sse(exchange, stream, 7);
                })) {
            ModelPort.ModelConfiguration configuration = configuration(server);
            ModelPort.ModelRequest base = ModelAdapterTestSupport.request(configuration);
            String safeFailure = "Tool failed: path_not_found. The requested path does not exist; "
                    + "verify the path before retrying.";
            List<ModelMessage> messages = List.of(
                    base.messages().getFirst(),
                    new ModelMessage(ModelRole.ASSISTANT, List.of(
                            new ToolCallContent("call_missing", "read_file",
                                    JsonObjects.builder().putText("path", "missing.txt").build()))),
                    new ModelMessage(ModelRole.TOOL, List.of(
                            new ToolResultContent("call_missing", safeFailure, true))));
            ModelPort.ModelRequest nextRound = new ModelPort.ModelRequest(
                    configuration, base.prompt(), messages, base.tools(), null, 2);

            try (OpenAiChatCompletionsAdapter adapter = new OpenAiChatCompletionsAdapter(configuration)) {
                adapter.start(nextRound, ignored -> CompletableFuture.completedFuture(null),
                                CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
            }

            var root = AbstractStreamingModelAdapter.JSON.readTree(requestBody.get());
            var toolMessage = root.path("messages").get(3);
            assertEquals("tool", toolMessage.path("role").textValue());
            assertEquals("call_missing", toolMessage.path("tool_call_id").textValue());
            assertEquals(safeFailure, toolMessage.path("content").textValue());
        }
    }

    /** usage 与 finish_reason 同 chunk 时先接纳计量，再完成语义终态。 */
    @Test
    void acceptsUsageAndFinishInSameChunk() throws Exception {
        String stream = chunk(
                "[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}]",
                "{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}")
                + "data: [DONE]\n\n";
        ModelPort.ModelOutcome outcome = execute(stream);
        assertEquals(ModelPort.FinishReason.STOP, outcome.finishReason());
        assertEquals(new ModelUsage(2, 1, 3), outcome.usage());
    }

    /** 多个累计快照只保留最后值并仅发布一次，防止按 chunk 重复累计 Token。 */
    @Test
    void keepsLastMonotonicUsageSnapshotWithoutAccumulation() throws Exception {
        String stream = chunk(
                "[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":null}]",
                "{\"prompt_tokens\":5,\"completion_tokens\":1,\"total_tokens\":6}")
                + chunk(
                "[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]",
                "{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}")
                + "data: [DONE]\n\n";
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, stream, 7))) {
            ModelPort.ModelConfiguration configuration = configuration(server);
            try (OpenAiChatCompletionsAdapter adapter =
                         new OpenAiChatCompletionsAdapter(configuration)) {
                ModelPort.ModelOutcome outcome = adapter.start(
                                ModelAdapterTestSupport.request(configuration), event -> {
                                    events.add(event);
                                    return CompletableFuture.completedFuture(null);
                                }, CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(new ModelUsage(5, 3, 8), outcome.usage());
            }
        }
        assertEquals(1, events.stream().filter(ModelPort.UsageEvent.class::isInstance).count());
        assertEquals(new ModelUsage(5, 3, 8),
                assertInstanceOf(ModelPort.UsageEvent.class,
                        events.stream().filter(ModelPort.UsageEvent.class::isInstance)
                                .findFirst().orElseThrow()).usage());
    }

    /** 显式 finish_reason 后允许兼容端以 clean EOF 结束，并保留 usage-only 尾帧。 */
    @Test
    void acceptsCleanEofAfterFinishAndUsage() throws Exception {
        String stream = chunk(
                "[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}]", null)
                + chunk("[]",
                "{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}");
        ModelPort.ModelOutcome outcome = execute(stream);
        assertEquals(ModelPort.FinishReason.STOP, outcome.finishReason());
        assertEquals(new ModelUsage(2, 1, 3), outcome.usage());
    }

    /** 缺少 finish_reason 的 clean EOF 属于截断，不能被网络 EOF 冒充语义完成。 */
    @Test
    void rejectsCleanEofWithoutFinishReason() throws Exception {
        String stream = chunk(
                "[{\"index\":0,\"delta\":{\"content\":\"partial\"},"
                        + "\"finish_reason\":null}]", null);
        Exception failure = assertThrows(Exception.class,
                () -> execute(stream));
        assertEquals("STREAM_TRUNCATED", providerFailure(failure).code());
    }

    /** 普通对话只要显式完成即可成功；缺失 usage 必须保持 UNKNOWN，而不是伪造零。 */
    @Test
    void completesWithoutUsageAsUnknown() throws Exception {
        String stream = chunk(
                "[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}]", null)
                + "data: [DONE]\n\n";
        ModelPort.ModelOutcome outcome = execute(stream);
        assertEquals(ModelPort.FinishReason.STOP, outcome.finishReason());
        assertNull(outcome.usage());
    }

    /** 名为 DeepSeek 的自定义供应商选择 Chat API 时复用协议 Adapter，不触发探测接口。 */
    @Test
    void supportsDeepSeekChatWithoutCountEndpoint() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        String stream = chunk(
                "[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}]",
                "{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}")
                + "data: [DONE]\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    ModelAdapterTestSupport.sse(exchange, stream, 9);
                })) {
            ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                    "provider_deepseek", "model_deepseek", "cfg_test",
                    ModelPort.Api.OPENAI_CHAT_COMPLETIONS, "deepseek-chat",
                    server.baseUri(), "deepseek-secret", Duration.ofSeconds(2), Duration.ofSeconds(5),
                    java.util.Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
            ModelPort.ModelRequest request = ModelAdapterTestSupport.request(configuration);
            try (OpenAiChatCompletionsAdapter adapter =
                         new OpenAiChatCompletionsAdapter(configuration)) {
                adapter.estimateInputTokens(request, CancellationToken.none());
                assertEquals(0, server.calls());
                ModelPort.ModelOutcome outcome = adapter.start(request,
                                ignored -> CompletableFuture.completedFuture(null),
                                CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(new ModelUsage(3, 2, 5), outcome.usage());
            }
            assertEquals(1, server.calls());
            assertEquals("Bearer " + "deepseek-secret", authorization.get());
        }
    }

    /** Chat 兼容字段为空时继续探测后续别名，并在同一 delta 只展示一个 reasoning 来源。 */
    @Test
    void selectsFirstNonEmptyReasoningAliasAndPreservesItsWireField() throws Exception {
        String stream = chunk(
                "[{\"index\":0,\"delta\":{"
                        + "\"reasoning_content\":\"\",\"reasoning\":\"plan\","
                        + "\"reasoning_text\":\"duplicate\"},\"finish_reason\":\"stop\"}]", null)
                + "data: [DONE]\n\n";
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, stream, 3))) {
            ModelPort.ModelConfiguration configuration = configuration(server);
            try (OpenAiChatCompletionsAdapter adapter = new OpenAiChatCompletionsAdapter(configuration)) {
                adapter.start(ModelAdapterTestSupport.request(configuration), event -> {
                            events.add(event);
                            return CompletableFuture.completedFuture(null);
                        }, CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(2, events.size());
        assertEquals("plan", assertInstanceOf(ModelPort.ReasoningSummaryDelta.class, events.get(0)).text());
        ReasoningContent content = assertInstanceOf(ModelPort.ReasoningBlockReady.class, events.get(1))
                .content();
        assertEquals("reasoning", content.wireField());
        assertEquals("plan", AbstractStreamingModelAdapter.JSON.readTree(content.nativeJson())
                .path("reasoning").textValue());
        assertFalse(content.nativeJson().contains("duplicate"));
    }

    /** 即使本轮没有普通正文，Chat reasoning 也必须形成完整历史块，供下一轮原生回传。 */
    @Test
    void persistsReasoningOnlyChatTurnWithoutAssistantText() throws Exception {
        String stream = chunk(
                "[{\"index\":0,\"delta\":{\"reasoning_content\":\"inspect files\"},"
                        + "\"finish_reason\":\"stop\"}]", null)
                + "data: [DONE]\n\n";
        List<ModelPort.ModelEvent> events = new CopyOnWriteArrayList<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, stream, 5))) {
            ModelPort.ModelConfiguration configuration = configuration(server);
            try (OpenAiChatCompletionsAdapter adapter = new OpenAiChatCompletionsAdapter(configuration)) {
                adapter.start(ModelAdapterTestSupport.request(configuration), event -> {
                            events.add(event);
                            return CompletableFuture.completedFuture(null);
                        }, CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, events.stream().filter(ModelPort.ReasoningSummaryDelta.class::isInstance).count());
        ReasoningContent content = assertInstanceOf(ModelPort.ReasoningBlockReady.class,
                events.stream().filter(ModelPort.ReasoningBlockReady.class::isInstance)
                        .findFirst().orElseThrow()).content();
        assertEquals("reasoning_content", content.wireField());
        assertEquals("inspect files", AbstractStreamingModelAdapter.JSON.readTree(content.nativeJson())
                .path("reasoning_content").textValue());
    }

    /** Chat 下一轮按当前配置恢复原始 reasoning 字段，不把历史 opaque 内容改写成普通文本。 */
    @Test
    void replaysMatchingChatReasoningFieldInAssistantHistory() throws Exception {
        ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(
                java.net.URI.create("http://127.0.0.1:9"),
                ModelPort.Api.OPENAI_CHAT_COMPLETIONS, Duration.ofSeconds(1));
        ModelPort.ModelRequest base = ModelAdapterTestSupport.request(configuration);
        ReasoningContent reasoning = new ReasoningContent(
                configuration.providerId(), configuration.modelId(), "openai_chat_completions",
                configuration.model(), ReasoningContent.endpointFingerprint(configuration.baseUri()),
                "reasoning_content", "{\"reasoning_content\":\"prior plan\"}");
        ModelPort.ModelRequest next = new ModelPort.ModelRequest(
                configuration, base.prompt(), List.of(base.messages().getFirst(),
                new ModelMessage(ModelRole.ASSISTANT, List.of(reasoning,
                        new io.github.kongweiguang.ja.conversation.domain.model.TextContent("answer")))),
                List.of(), null, 2);

        var assistant = OpenAiChatCompletionsCodec.encodeRequest(next).path("messages").path(2);
        assertEquals("prior plan", assistant.path("reasoning_content").textValue());
        assertEquals("answer", assistant.path("content").textValue());
        assertFalse(assistant.has("reasoning"));
        assertFalse(assistant.has("reasoning_text"));
    }

    /** 通过最小 loopback 请求复用统一异步执行与错误解包。 */
    private static ModelPort.ModelOutcome execute(String stream) throws Exception {
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, stream, 7))) {
            ModelPort.ModelConfiguration configuration = configuration(server);
            try (OpenAiChatCompletionsAdapter adapter =
                         new OpenAiChatCompletionsAdapter(configuration)) {
                return adapter.start(ModelAdapterTestSupport.request(configuration),
                                ignored -> CompletableFuture.completedFuture(null),
                                CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    /** 固定每个 chunk 的流身份，仅让测试显式传入 choices 与可选 usage。 */
    private static String chunk(String choices, String usage) {
        return "data: {\"id\":\"chatcmpl_1\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"test-model\",\"choices\":" + choices
                + (usage == null ? "" : ",\"usage\":" + usage) + "}\n\n";
    }

    /** 构造明确的 Chat 配置；供应商显示名称不参与 Wire 路由。 */
    private static ModelPort.ModelConfiguration configuration(ModelAdapterTestSupport.Loopback server) {
        return ModelAdapterTestSupport.configuration(server.baseUri(),
                ModelPort.Api.OPENAI_CHAT_COMPLETIONS, Duration.ofSeconds(5));
    }

    /** 解开 Future 包装，只断言 Adapter 的稳定协议错误码。 */
    private static ProviderProtocolException providerFailure(Throwable source) throws IOException {
        Throwable failure = source;
        while (failure.getCause() != null && !(failure instanceof ProviderProtocolException)) {
            failure = failure.getCause();
        }
        if (failure instanceof ProviderProtocolException provider) return provider;
        throw new IOException("expected provider failure", source);
    }
}
