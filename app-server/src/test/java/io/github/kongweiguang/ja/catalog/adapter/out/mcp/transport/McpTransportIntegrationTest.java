// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.transport;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.runtime.McpRuntime;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.testsupport.McpStdioFixture;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockResponseBody;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.BufferedSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

    /** 在无外部网络与用户凭据的条件下验证真实 MCP SDK 适配边界。 */
final class McpTransportIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 验证 stdio 清空继承环境、只解析显式引用、完成调用并回收子进程。 */
    @Test
    void stdioIsEnvironmentConfinedAndLeavesNoOrphan(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("report.txt");
        Path classes = Path.of(McpStdioFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = List.of(
                javaExecutable(),
                "-cp",
                classes.toString(),
                McpStdioFixture.class.getName(),
                report.toString());
        McpServerDefinition definition = McpServerDefinition.stdio(
                "stdio-fixture",
                command,
                directory,
                Map.of("JA_ALLOWED", "yes", "JA_SECRET", "resolved"),
                List.of("2025-06-18"));
        long pid;
        try (McpRuntime runtime = new McpRuntime(
                List.of(definition), McpLimits.DEFAULT, new ObjectMapper())) {
            McpGateway.McpSnapshot snapshot = runtime.snapshot();
            McpGateway.McpResult result = runtime.invoke(
                    snapshot,
                    new McpGateway.McpInvocation(
                            "stdio-call", snapshot.tools().getFirst().spec().name(),
                            JsonObjects.builder().putText("value", "ok").build(), 0),
                    CancellationToken.none()).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
            assertTrue(awaitFile(report));
            List<String> observations = Files.readAllLines(report, StandardCharsets.UTF_8);
            pid = Long.parseLong(observations.getFirst());
            assertTrue(observations.contains("parent=false"));
            assertTrue(observations.contains("allowed=true"));
            assertTrue(observations.contains("secret=true"));
            assertFalse(String.join("\n", observations).contains("resolved"));
        }
        assertTrue(awaitExit(pid));
    }

    /** 验证 Streamable HTTP 使用显式 Mapper，且只在创建请求时注入已解析 Header。 */
    @Test
    void streamableHttpListsAndCallsWithResolvedHeader() throws Exception {
        try (HttpFixture fixture = HttpFixture.start()) {
            McpServerDefinition definition = McpServerDefinition.streamableHttp(
                    "http-fixture",
                    URI.create(fixture.url()),
                    Map.of("Authorization", "http-secret"),
                    List.of("2025-06-18"));
            try (McpRuntime runtime = new McpRuntime(
                    List.of(definition), McpLimits.DEFAULT, new ObjectMapper())) {
                McpGateway.McpSnapshot snapshot = runtime.snapshot();
                McpGateway.McpResult result = runtime.invoke(
                        snapshot,
                        new McpGateway.McpInvocation(
                                "http-call", snapshot.tools().getFirst().spec().name(),
                                JsonObjects.builder().putText("value", "ok").build(), 0),
                        CancellationToken.none()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
                assertEquals("http-secret", fixture.authorization.get());
            }
        }
    }

    /** 验证随机 UTF-8 传输分片与多行数据只由 okhttp-sse 解码。 */
    @Test
    void streamableHttpSseUsesOkHttpEventSourceAcrossRandomFragments() throws Exception {
        try (HttpFixture fixture = HttpFixture.start(true)) {
            McpServerDefinition definition = McpServerDefinition.streamableHttp(
                    "http-sse-fixture",
                    URI.create(fixture.url()),
                    Map.of("Authorization", "http-secret"),
                    List.of("2025-06-18"));
            try (McpRuntime runtime = new McpRuntime(
                    List.of(definition), McpLimits.DEFAULT, new ObjectMapper())) {
                McpGateway.McpSnapshot snapshot = runtime.snapshot();
                McpGateway.McpResult result = runtime.invoke(
                        snapshot,
                        new McpGateway.McpInvocation(
                                "http-sse-call", snapshot.tools().getFirst().spec().name(),
                                JsonObjects.builder().putText("value", "ok").build(), 0),
                        CancellationToken.none()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
                assertEquals("http-secret", fixture.authorization.get());
            }
        }
    }

    /** 验证取消进行中的 MCP 调用会关闭活动 OkHttp socket，并及时返回 CANCELLED。 */
    @Test
    void streamableHttpCancellationClosesActiveCall() throws Exception {
        try (HttpFixture fixture = HttpFixture.startStallingSse()) {
            McpServerDefinition definition = McpServerDefinition.streamableHttp(
                    "http-cancel-fixture",
                    URI.create(fixture.url()),
                    Map.of(),
                    List.of("2025-06-18"));
            TestToken token = new TestToken();
            try (McpRuntime runtime = new McpRuntime(
                    List.of(definition), McpLimits.DEFAULT, new ObjectMapper())) {
                McpGateway.McpSnapshot snapshot = runtime.snapshot();
                java.util.concurrent.CompletableFuture<McpGateway.McpResult> pending = runtime.invoke(
                        snapshot,
                        new McpGateway.McpInvocation(
                                "http-cancel-call", snapshot.tools().getFirst().spec().name(),
                                JsonObjects.builder().putText("value", "ok").build(), 0),
                        token).toCompletableFuture();
                assertTrue(fixture.callStarted.await(2, TimeUnit.SECONDS));
                token.cancel();
                assertEquals(ToolOutcome.CANCELLED, pending.get(3, TimeUnit.SECONDS).outcome());
                assertTrue(fixture.clientDisconnected.await(3, TimeUnit.SECONDS));
            }
        }
    }

    /** 验证原始 SSE 注释即使不形成 JSON 数据，也计入聚合上限。 */
    @Test
    void streamableHttpCapsRawSseBytesBeforeParsing() throws Exception {
        try (HttpFixture fixture = HttpFixture.startOversizedSse()) {
            McpServerDefinition definition = McpServerDefinition.streamableHttp(
                    "http-limit-fixture", URI.create(fixture.url()), Map.of(), List.of("2025-06-18"));
            McpLimits defaults = McpLimits.DEFAULT;
            McpLimits limits = new McpLimits(
                    defaults.maxPages(), defaults.maxTools(), defaults.maxCursorBytes(),
                    defaults.maxSchemaBytes(), defaults.maxResultBytes(), 1024,
                    defaults.maxStderrBytes(), defaults.outboundQueueCapacity(),
                    defaults.startupTimeout(), defaults.requestTimeout(), defaults.closeTimeout());

            try (McpRuntime runtime = new McpRuntime(List.of(definition), limits, new ObjectMapper())) {
                assertThrows(IllegalStateException.class, runtime::snapshot);
            }
        }
    }

    /** 缺失 Content-Type 时必须拒绝响应，避免把任意载荷误判为 JSON。 */
    @Test
    void streamableHttpRejectsMissingContentType() throws Exception {
        try (HttpFixture fixture = HttpFixture.startWithoutContentType()) {
            McpServerDefinition definition = McpServerDefinition.streamableHttp(
                    "http-content-type-fixture", URI.create(fixture.url()), Map.of(), List.of("2025-06-18"));
            try (McpRuntime runtime = new McpRuntime(
                    List.of(definition), McpLimits.DEFAULT, new ObjectMapper())) {
                assertThrows(IllegalStateException.class, runtime::snapshot);
            }
        }
    }

    /** 验证持续写入 stderr 的子进程会在初始化期间关闭，且不遗留孤儿进程。 */
    @Test
    void stdioStderrFloodClosesChild(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("stderr-report.txt");
        Path classes = Path.of(McpStdioFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        McpServerDefinition definition = McpServerDefinition.stdio(
                "stderr-fixture",
                List.of(
                        javaExecutable(),
                        "-cp",
                        classes.toString(),
                        McpStdioFixture.class.getName(),
                        report.toString(),
                        "stderr"),
                directory,
                Map.of(),
                List.of("2025-06-18"));
        McpLimits defaults = McpLimits.DEFAULT;
        McpLimits limits = new McpLimits(
                defaults.maxPages(), defaults.maxTools(), defaults.maxCursorBytes(), defaults.maxSchemaBytes(),
                defaults.maxResultBytes(), defaults.maxMessageBytes(), 1024, defaults.outboundQueueCapacity(),
                Duration.ofSeconds(3), defaults.requestTimeout(), defaults.closeTimeout());
        try (McpRuntime runtime = new McpRuntime(
                List.of(definition), limits, new ObjectMapper())) {
            assertThrows(IllegalStateException.class, runtime::snapshot);
        }
        assertTrue(awaitFile(report));
        long pid = Long.parseLong(Files.readAllLines(report, StandardCharsets.UTF_8).getFirst());
        assertTrue(awaitExit(pid));
    }

    /** 解析当前运行的 Java 二进制，避免 stdio 依赖继承的 PATH。 */
    private static String javaExecutable() {
        String binary = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", binary).toString();
    }

    /** 有界等待夹具发布 PID 与环境观测值，避免测试无限阻塞。 */
    private static boolean awaitFile(Path path) throws InterruptedException, IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (Files.isRegularFile(path) && Files.size(path) > 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** 通过操作系统进程表确认清理，不根据 Runtime close 返回推断进程已退出。 */
    private static boolean awaitExit(long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** 最小可取消令牌用于断言公开 MCP 边界上的传输清理竞争。 */
    private static final class TestToken implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

        /** 只发布一次取消信号，并运行当前全部已注册清理回调。 */
        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                callbacks.forEach(Runnable::run);
            }
        }

        /** 读取单次取消标记，不等待回调清理完成。 */
        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        /** 提供静态原因，避免夹具保存实现细节。 */
        @Override
        public Optional<String> reason() {
            return cancelled.get() ? Optional.of("test_cancelled") : Optional.empty();
        }

        /** 注册清理回调，并封闭取消先于注册获胜的竞争窗口。 */
        @Override
        public Registration onCancellation(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            if (cancelled.get()) {
                callback.run();
                return Registration.noop();
            }
            callbacks.add(callback);
            if (cancelled.get() && callbacks.remove(callback)) {
                callback.run();
                return Registration.noop();
            }
            return () -> callbacks.remove(callback);
        }
    }

    /** 返回两个本地夹具共同允许的三种 MCP 结果结构。 */
    private static String resultFor(String method) {
        return switch (method) {
            case "initialize" -> "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                    + "\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
            case "tools/list" -> "{\"tools\":[{\"name\":\"echo\",\"description\":\"echo\","
                    + "\"inputSchema\":{\"type\":\"object\"}}]}";
            case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}";
            default -> "{}";
        };
    }

    /** 不依赖外部服务的 loopback Streamable HTTP 夹具。 */
    private static final class HttpFixture implements AutoCloseable {
        private final MockWebServer server;
        private final boolean sse;
        private final boolean stallToolCall;
        private final int commentBytes;
        private final boolean omitContentType;
        private final CountDownLatch callStarted = new CountDownLatch(1);
        private final CountDownLatch clientDisconnected = new CountDownLatch(1);
        private final AtomicReference<String> authorization = new AtomicReference<>();

        /** 独占 loopback 服务与有界生命周期 Executor，确保测试退出不遗留线程。 */
        private HttpFixture(
                MockWebServer server,
                boolean sse,
                boolean stallToolCall,
                int commentBytes,
                boolean omitContentType) {
            this.server = server;
            this.sse = sse;
            this.stallToolCall = stallToolCall;
            this.commentBytes = commentBytes;
            this.omitContentType = omitContentType;
        }

        /** 使用临时 loopback 端口启动，避免并行测试发生端口冲突。 */
        private static HttpFixture start() throws IOException {
            return start(false);
        }

        /** 启动 JSON 或 SSE 夹具，使传输测试不依赖外部服务。 */
        private static HttpFixture start(boolean sse) throws IOException {
            return start(sse, false);
        }

        /** 启动持续到客户端取消的流，以暴露 socket 清理证据。 */
        private static HttpFixture startStallingSse() throws IOException {
            return start(true, true);
        }

        /** 启动一个合法事件，并在其前写入超过原始字节上限的注释。 */
        private static HttpFixture startOversizedSse() throws IOException {
            return start(true, false, 2_048);
        }

        /** 启动缺失 Content-Type 的响应端点，用于锁定严格媒体类型边界。 */
        private static HttpFixture startWithoutContentType() throws IOException {
            return start(false, false, 0, true);
        }

        /** 以显式响应与取消行为启动 loopback fixture，避免测试场景依赖隐式默认值。 */
        private static HttpFixture start(boolean sse, boolean stallToolCall) throws IOException {
            return start(sse, stallToolCall, 0);
        }

        /** 以显式响应与取消行为启动 loopback 夹具，避免场景依赖隐式默认值。 */
        private static HttpFixture start(boolean sse, boolean stallToolCall, int commentBytes) throws IOException {
            return start(sse, stallToolCall, commentBytes, false);
        }

        /** 按测试场景显式控制媒体类型，避免 fixture 默认行为掩盖协议错误。 */
        private static HttpFixture start(
                boolean sse, boolean stallToolCall, int commentBytes, boolean omitContentType) throws IOException {
            MockWebServer server = new MockWebServer();
            HttpFixture fixture = new HttpFixture(server, sse, stallToolCall, commentBytes, omitContentType);
            server.setDispatcher(new Dispatcher() {
                /** 让成熟服务器负责连接与分帧，fixture 只按 JSON-RPC 请求选择响应。 */
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return fixture.response(request);
                }
            });
            server.start();
            return fixture;
        }

        /** 返回定义实际消费的精确 Streamable HTTP endpoint。 */
        private String url() {
            return server.url("/mcp").toString();
        }

        /** 响应 POST JSON-RPC，并拒绝可选 GET 流，避免阻塞重连循环。 */
        private MockResponse response(RecordedRequest request) {
            authorization.set(request.getHeaders().get("Authorization"));
            if ("DELETE".equals(request.getMethod())) {
                return new MockResponse.Builder().code(204).build();
            }
            if (!"POST".equals(request.getMethod())) {
                return new MockResponse.Builder().code(405).build();
            }
            final JsonNode document;
            try {
                document = JSON.readTree(request.getBody().toByteArray());
            } catch (IOException failure) {
                return new MockResponse.Builder().code(400).build();
            }
            if (!document.has("id")) return new MockResponse.Builder().code(202).build();
            String method = document.path("method").asText();
            if (sse && stallToolCall && "tools/call".equals(method)) {
                return new MockResponse.Builder().code(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setHeader("MCP-Protocol-Version", "2025-06-18")
                        .body(new StallingSseBody(callStarted, clientDisconnected)).build();
            }
            String body = "{\"jsonrpc\":\"2.0\",\"id\":" + document.get("id")
                    + ",\"result\":" + resultFor(method) + "}";
            MockResponse.Builder response = new MockResponse.Builder().code(200)
                    .setHeader("MCP-Protocol-Version", "2025-06-18")
                    .setHeader("Mcp-Session-Id", "fixture-session");
            if (!omitContentType) {
                response.setHeader("Content-Type", sse ? "text/event-stream" : "application/json");
            }
            int split = body.indexOf(",\"result\"");
            String responseBody = sse
                    ? (commentBytes == 0 ? "" : ":" + "x".repeat(commentBytes) + "\r\n")
                            + "event: message\r\ndata: " + body.substring(0, split)
                            + "\r\ndata: " + body.substring(split) + "\r\n\r\n"
                    : body;
            return (sse ? response.chunkedBody(responseBody, 3) : response.body(responseBody)).build();
        }

        /** 停止监听与 Worker 线程，使 JVM 能证明测试清理具备确定性。 */
        @Override
        public void close() {
            server.close();
        }
    }

    /** 让 MockWebServer 在真实 socket 上持续写心跳，直至客户端取消关闭连接。 */
    private record StallingSseBody(CountDownLatch started, CountDownLatch disconnected)
            implements MockResponseBody {
        /** 未知长度用于保持 SSE 连接流式开放。 */
        @Override
        public long getContentLength() {
            return -1;
        }

        /** 首次正文写入发布已开始证据，断连与关闭都归约到同一完成锁存器。 */
        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            started.countDown();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    sink.writeUtf8(": keepalive\r\n\r\n");
                    sink.flush();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                disconnected.countDown();
            }
        }
    }
}
