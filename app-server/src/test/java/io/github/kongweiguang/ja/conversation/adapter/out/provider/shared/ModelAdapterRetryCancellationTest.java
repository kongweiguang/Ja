// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ModelAdapterFactory;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.openai.OpenAiResponsesAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/** 传输 Policy 测试证明重试会在语义提交后停止，且活动交换遵守取消与 Deadline。 */
final class ModelAdapterRetryCancellationTest {
    private static final String COMPLETE = """
            event: response.created
            data: {"type":"response.created","sequence_number":0,"response":%s}

            event: response.completed
            data: {"type":"response.completed","sequence_number":1,"response":%s}

            """.formatted(
                    ModelAdapterTestSupport.openAiResponse("resp_ok", "in_progress"),
                    ModelAdapterTestSupport.openAiResponse(
                            "resp_ok", "completed", new ModelUsage(1, 1, 2)));

    /** 在首个语义事件被接纳前重试 429 和可恢复 5xx，随后只成功一次。 */
    @Test
    void retriesTransientStatusesOnlyBeforeCommit() throws Exception {
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback((call, exchange) -> {
            if (call == 1) ModelAdapterTestSupport.status(exchange, 429, "0");
            else if (call == 2) ModelAdapterTestSupport.status(exchange, 503, "0");
            else ModelAdapterTestSupport.sse(exchange, COMPLETE, 9);
        })) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ModelPort.ModelOutcome outcome = adapter.start(ModelAdapterTestSupport.request(configuration),
                                event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.STOP, outcome.finishReason());
            }
            assertEquals(3, server.calls());
        }
    }

    /** 第一次 loopback 连接被拒绝后等待代理恢复，证明应用退避而非 OkHttp 隐式重放完成恢复。 */
    @Test
    void retriesAfterTransientConnectionRefusalWhenLoopbackRecovers() throws Exception {
        MockWebServer unavailable = new MockWebServer();
        unavailable.start(InetAddress.getByName("127.0.0.1"), 0);
        int port = unavailable.getPort();
        unavailable.close();

        MockWebServer recovered = new MockWebServer();
        recovered.setDispatcher(new mockwebserver3.Dispatcher() {
            /** 恢复后的本地端点只返回一次完整 SSE，隔离旧重试窗口与新退避窗口的真实请求次数。 */
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse.Builder().code(200)
                        .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                        .body(COMPLETE)
                        .build();
            }
        });
        ScheduledExecutorService starter = Executors.newSingleThreadScheduledExecutor();
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        try {
            URI baseUri = URI.create("http://127.0.0.1:" + port);
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(
                    baseUri, ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                long requestStartedAt = System.nanoTime();
                CompletableFuture<ModelPort.ModelOutcome> future = adapter.start(
                                ModelAdapterTestSupport.request(configuration),
                                event -> CompletableFuture.completedFuture(null), CancellationToken.none())
                        .toCompletableFuture();
                starter.schedule(() -> {
                    try {
                        recovered.start(InetAddress.getByName("127.0.0.1"), port);
                    } catch (IOException failure) {
                        startupFailure.set(failure);
                    }
                }, 750, TimeUnit.MILLISECONDS);
                ModelPort.ModelOutcome outcome = future.get(8, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.STOP, outcome.finishReason());
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedAt) >= 700);
            }
            assertNull(startupFailure.get());
            assertEquals(1, recovered.getRequestCount());
        } finally {
            starter.shutdownNow();
            recovered.close();
        }
    }

    /** 连接预算较短时，首个 SSE 正文仍可等待到请求总预算，避免慢首 token 被误判为断连。 */
    @Test
    void allowsFirstSseBodyAfterConnectTimeoutWithinRequestTimeout() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new mockwebserver3.Dispatcher() {
                /** 将首个正文延迟到连接预算之后，验证读超时使用请求总预算而非连接预算。 */
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse.Builder().code(200)
                            .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                            .body(COMPLETE)
                            .bodyDelay(2_500, TimeUnit.MILLISECONDS)
                            .build();
                }
            });
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(
                    URI.create("http://127.0.0.1:" + server.getPort()),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ModelPort.ModelOutcome outcome = adapter.start(ModelAdapterTestSupport.request(configuration),
                                event -> CompletableFuture.completedFuture(null), CancellationToken.none())
                        .toCompletableFuture().get(8, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.STOP, outcome.finishReason());
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    /** 连接、读取和写入分别遵守对应预算，防止流式请求用连接超时提前截断。 */
    @Test
    void requestClientUsesRequestTimeoutForBodyIo() throws Exception {
        ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(
                URI.create("http://127.0.0.1:1"), ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(17));
        try (ModelTransport transport = new ModelTransport()) {
            OkHttpClient client = transport.clientFor(configuration);
            assertEquals(configuration.connectTimeout(), client.connectTimeoutMillis() == 0
                    ? Duration.ZERO : Duration.ofMillis(client.connectTimeoutMillis()));
            assertEquals(configuration.requestTimeout(), Duration.ofMillis(client.readTimeoutMillis()));
            assertEquals(configuration.requestTimeout(), Duration.ofMillis(client.writeTimeoutMillis()));
        }
    }

    /** 自动标题等自带本地回退的请求必须在首个瞬时失败后结束，不能触发共享三次重试。 */
    @Test
    void singleAttemptPolicyDisablesTransientRetry() throws Exception {
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.status(exchange, 503, "0"))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            ModelPort.ModelRequest base = ModelAdapterTestSupport.request(configuration);
            ModelPort.ModelRequest singleAttempt = new ModelPort.ModelRequest(
                    base.configuration(), base.prompt(), base.messages(), base.tools(), base.continuation(),
                    base.round(), ModelPort.RetryPolicy.SINGLE_ATTEMPT);
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(singleAttempt,
                                        event -> CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof ProviderProtocolException);
            }
            assertEquals(1, server.calls());
        }
    }

    /** 文本被接纳后即使剩余流被截断，也拒绝重放请求。 */
    @Test
    void doesNotRetryAfterFirstSemanticEventIsAccepted() throws Exception {
        String committedThenTruncated = """
                event: response.created
                data: {"type":"response.created","sequence_number":0,"response":%s}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","content_index":0,"delta":"persisted","item_id":"message_1","logprobs":[],"output_index":0,"sequence_number":1}

                """.formatted(ModelAdapterTestSupport.openAiResponse(
                        "resp_partial", "in_progress"));
        AtomicInteger textEvents = new AtomicInteger();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, committedThenTruncated, 5))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration), event -> {
                                    if (event instanceof ModelPort.TextDelta) textEvents.incrementAndGet();
                                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                                }, CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("STREAM_TRUNCATED",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
            }
            assertEquals(1, textEvents.get());
            assertEquals(1, server.calls());
        }
    }

    /** 在解析 Provider JSON 或发布到 sink 前拒绝解码后超限的 SSE 事件。 */
    @Test
    void rejectsOversizedEvent() throws Exception {
        String oversized = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\""
                + "x".repeat(AbstractStreamingModelAdapter.MAX_EVENT_BYTES)
                + "\"}\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, oversized, 4096))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> CompletableFuture.completedFuture(null), CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("EVENT_LIMIT",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 将未知原生事件视为终止协议错误，不静默丢失状态。 */
    @Test
    void rejectsUnknownProviderEvent() throws Exception {
        String unknown = "event: response.future_event\ndata: {\"type\":\"response.future_event\"}\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, unknown, 3))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> CompletableFuture.completedFuture(null), CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("OPENAI_EVENT",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 覆盖面向组合根的 Factory ModelPort 路径，并证明请求 Codec 生命周期仅限单次请求。 */
    @Test
    void factoryStartsRequestScopedAdapter() throws Exception {
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, COMPLETE, 5))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            try (ModelAdapterFactory factory = new ModelAdapterFactory()) {
                ModelPort.ModelOutcome outcome = factory.start(ModelAdapterTestSupport.request(configuration),
                                event -> CompletableFuture.completedFuture(null), CancellationToken.none())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(ModelPort.FinishReason.STOP, outcome.finishReason());
                assertEquals(1, server.calls());
            }
        }
    }

    /** 关闭 Factory 持有的共享传输，并取消 Adapter 创建的活动流。 */
    @Test
    void factoryCloseStopsActiveSharedTransport() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.stallingSse(exchange, headersSent))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            ModelAdapterFactory factory = new ModelAdapterFactory();
            ModelAdapter adapter = factory.create(configuration);
            try {
                CompletableFuture<ModelPort.ModelOutcome> future = adapter.start(
                                ModelAdapterTestSupport.request(configuration),
                                event -> CompletableFuture.completedFuture(null), CancellationToken.none())
                        .toCompletableFuture();
                assertTrue(headersSent.await(2, TimeUnit.SECONDS));
                factory.close();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> future.get(2, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, failure.getCause());
                assertSharedTransportClosed(factory);
            } finally {
                adapter.close();
            }
        }
    }

    /** 校验 Factory 关闭屏障覆盖 OkHttp Call、空闲连接和自有工作线程。 */
    private static void assertSharedTransportClosed(ModelAdapterFactory factory) throws Exception {
        Field transportField = ModelAdapterFactory.class.getDeclaredField("transport");
        transportField.setAccessible(true);
        ModelTransport transport = (ModelTransport) transportField.get(factory);
        Field clientField = ModelTransport.class.getDeclaredField("client");
        clientField.setAccessible(true);
        OkHttpClient client = (OkHttpClient) clientField.get(transport);
        Dispatcher dispatcher = client.dispatcher();
        ConnectionPool pool = client.connectionPool();
        assertEquals(0, dispatcher.queuedCallsCount());
        assertEquals(0, dispatcher.runningCallsCount());
        assertEquals(0, pool.connectionCount());
        assertEquals(0, pool.idleConnectionCount());
        assertTrue(dispatcher.executorService().isTerminated());
        Field requestsField = ModelTransport.class.getDeclaredField("requests");
        requestsField.setAccessible(true);
        assertTrue(((java.util.concurrent.ExecutorService) requestsField.get(transport)).isTerminated());
        Field deadlinesField = ModelTransport.class.getDeclaredField("deadlines");
        deadlinesField.setAccessible(true);
        assertTrue(((java.util.concurrent.ExecutorService) deadlinesField.get(transport)).isTerminated());
    }

    /** 即使此前没有语义事件，也将明确 Provider 错误事件视为不可重试。 */
    @Test
    void doesNotRetryExplicitProviderErrorEvent() throws Exception {
        String error = "event: error\ndata: {\"type\":\"error\",\"error\":{\"message\":\"sensitive detail\"}}\n\n";
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, error, 4))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                ProviderProtocolException protocol =
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause());
                assertEquals("PROVIDER_ERROR", protocol.code());
                assertTrue(!protocol.getMessage().contains("sensitive detail"));
                assertNull(protocol.getCause());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 将 sink 拒绝视为本地持久化失败，绝不重放 Provider 输出。 */
    @Test
    void doesNotRetryWhenEventSinkRejectsPersistence() throws Exception {
        String text = """
                event: response.created
                data: {"type":"response.created","sequence_number":0,"response":%s}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","content_index":0,"delta":"cannot persist","item_id":"message_1","logprobs":[],"output_index":0,"sequence_number":1}

                """.formatted(ModelAdapterTestSupport.openAiResponse(
                        "resp_sink", "in_progress"));
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, text, 8))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.failedFuture(
                                                new IllegalStateException("database unavailable")),
                                        CancellationToken.none())
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertEquals("EVENT_SINK",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 取消阻塞的正文读取，并证明活动 OkHttp Call 在一秒内关闭。 */
    @Test
    void cancellationClosesActiveStreamAndStopsRetries() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.stallingSse(exchange, headersSent))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            ModelAdapterTestSupport.TestCancellation cancellation = new ModelAdapterTestSupport.TestCancellation();
            try (ModelTransport transport = new ModelTransport();
                 OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration, transport)) {
                OkHttpClient client = transport.clientFor(configuration);
                CompletableFuture<ModelPort.ModelOutcome> future = adapter.start(
                                ModelAdapterTestSupport.request(configuration),
                                event -> java.util.concurrent.CompletableFuture.completedFuture(null), cancellation)
                        .toCompletableFuture();
                assertTrue(headersSent.await(2, TimeUnit.SECONDS));
                cancellation.cancel();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> future.get(1, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, failure.getCause());
                assertEquals(0, client.dispatcher().runningCallsCount());
                assertEquals(0, client.dispatcher().queuedCallsCount());
            }
            assertEquals(1, server.calls());
        }
    }

    /** 仅在活动 sink Future 已取消且后续 SSE 事件无法到达后完成取消。 */
    @Test
    void cancellationBarrierStopsSinkFutureAndLateEvents() throws Exception {
        String twoEvents = """
                event: response.created
                data: {"type":"response.created","sequence_number":0,"response":%s}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","content_index":0,"delta":"first","item_id":"message_1","logprobs":[],"output_index":0,"sequence_number":1}

                event: response.output_text.delta
                data: {"type":"response.output_text.delta","content_index":0,"delta":"late","item_id":"message_1","logprobs":[],"output_index":0,"sequence_number":2}

                event: response.completed
                data: {"type":"response.completed","sequence_number":3,"response":%s}

                """.formatted(
                        ModelAdapterTestSupport.openAiResponse("resp_barrier", "in_progress"),
                        ModelAdapterTestSupport.openAiResponse(
                                "resp_barrier", "completed", new ModelUsage(1, 2, 3)));
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CompletableFuture<Void> pendingSink = new CompletableFuture<>();
        AtomicInteger sinkCalls = new AtomicInteger();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, twoEvents, 1))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            ModelAdapterTestSupport.TestCancellation cancellation = new ModelAdapterTestSupport.TestCancellation();
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                CompletableFuture<ModelPort.ModelOutcome> future = adapter.start(
                        ModelAdapterTestSupport.request(configuration), event -> {
                    sinkCalls.incrementAndGet();
                    sinkEntered.countDown();
                    return pendingSink;
                }, cancellation).toCompletableFuture();
                assertTrue(sinkEntered.await(2, TimeUnit.SECONDS));
                cancellation.cancel();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> future.get(2, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, failure.getCause());
                assertTrue(pendingSink.isCancelled());
                assertEquals(1, sinkCalls.get());
                assertEquals(0, cancellation.callbackCount());
            }
        }
    }

    /** 在 HTTP 启动前拒绝预取消 Token，并在完成时移除立即触发的回调。 */
    @Test
    void preCancelledTokenNeverStartsHttp() throws Exception {
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.sse(exchange, COMPLETE, 4))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofSeconds(5));
            ModelAdapterTestSupport.TestCancellation cancellation = new ModelAdapterTestSupport.TestCancellation();
            cancellation.cancel();
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> CompletableFuture.completedFuture(null), cancellation)
                                .toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, failure.getCause());
                assertEquals(0, cancellation.callbackCount());
            }
            assertEquals(0, server.calls());
        }
    }

    /** 将整体 Deadline 转换为有界超时失败，并关闭活动响应流。 */
    @Test
    void timeoutClosesActiveStream() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.stallingSse(exchange, headersSent))) {
            ModelPort.ModelConfiguration configuration = ModelAdapterTestSupport.configuration(server.baseUri(),
                    ModelPort.Api.OPENAI_RESPONSES, Duration.ofMillis(150));
            try (OpenAiResponsesAdapter adapter = new OpenAiResponsesAdapter(configuration)) {
                ExecutionException failure = assertThrows(ExecutionException.class, () ->
                        adapter.start(ModelAdapterTestSupport.request(configuration),
                                        event -> java.util.concurrent.CompletableFuture.completedFuture(null),
                                        CancellationToken.none())
                                .toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertEquals("REQUEST_TIMEOUT",
                        assertInstanceOf(ProviderProtocolException.class, failure.getCause()).code());
            }
            assertTrue(headersSent.await(1, TimeUnit.SECONDS));
            assertEquals(1, server.calls());
        }
    }
}
