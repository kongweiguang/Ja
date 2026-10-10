// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 真实 loopback HTTP 经生产 OkHttp SSE 入口，验证原生分帧与 Ja 业务校验的边界。 */
final class ProviderSseTest {
    /** 原生换行、多行 data、重复 event 和元数据不应被 Ja 额外拒绝，未闭合尾帧不能补发。 */
    @Test
    void usesNativeFramingWithoutDeliveringUnterminatedTail() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        consume(": keepalive\r\nid: 1\rretry: 1000\nfuture-field\n\n"
                + "event: ignored\nevent: ping\r\ndata: {\"type\":\"ping\",\rdata: \"value\":\"中文\"}\n\n"
                + "event: ping\ndata: {\"type\":\"ping\",\"value\":\"丢弃\"}\n",
                (name, data) -> {
                    events.add(AbstractStreamingModelAdapter.namedEvent(name, data, Set.of("ping"), "TEST_EVENT"));
                    return false;
                });
        assertEquals(1, events.size());
        assertEquals("中文", events.getFirst().path("value").textValue());
    }

    /** OkHttp 的未完成 data 行可能报告参数异常，生产边界必须收敛为可恢复断流。 */
    @Test
    void classifiesNativeUnterminatedDataLineAsTruncation() {
        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> consume("data: {\"value\":", (name, data) -> false));
        assertEquals("STREAM_TRUNCATED", failure.code());
        org.junit.jupiter.api.Assertions.assertTrue(failure.retryable());
    }

    /** UTF-8 码点跨网络写入仍由 OkHttp 正确重组，允许 Chat 流携带 event 与扩展字段。 */
    @Test
    void readsChineseAcrossByteWritesAndAcceptsChatMetadata() throws Exception {
        List<String> texts = new ArrayList<>();
        consume("event: message\nid: 1\nretry: 10\nextra: ignored\ndata: {\"text\":\"驾驭 Agent\"}\n\n",
                (name, data) -> {
                    texts.add(AbstractStreamingModelAdapter.providerObject(data, "OPENAI_CHAT_EVENT")
                            .path("text").textValue());
                    return false;
                });
        assertEquals(List.of("驾驭 Agent"), texts);
    }

    /** 完整 SSE 帧中的坏 JSON、重复键、过深结构和多对象必须保持确定性失败。 */
    @Test
    void rejectsInvalidProviderJsonInCompleteFrames() {
        for (String payload : List.of("{\"type\":}", "{\"type\":\"ping\",\"type\":\"ping\"}",
                "{\"type\":\"ping\"} {}", "{\"type\":\"ping\",\"value\":"
                        + "[".repeat(65) + "0" + "]".repeat(65) + "}")) {
            ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                    () -> consume("event: ping\ndata: " + payload + "\n\n", (name, data) -> {
                        AbstractStreamingModelAdapter.namedEvent(name, data, Set.of("ping"), "TEST_EVENT");
                        return false;
                    }));
            assertEquals("TEST_EVENT", failure.code());
            assertFalse(failure.retryable());
        }
    }

    /** 扩展事件不改变业务状态，但已知事件仍要求 JSON type 与事件名一致。 */
    @Test
    void ignoresExtensionEventsAndRejectsTypeMismatch() throws Exception {
        List<JsonNode> events = new ArrayList<>();
        consume("event: future\ndata: opaque extension\n\nevent: ping\ndata: {\"type\":\"ping\"}\n\n",
                (name, data) -> {
                    JsonNode event = AbstractStreamingModelAdapter.namedEvent(name, data, Set.of("ping"), "TEST_EVENT");
                    if (event != null) {
                        events.add(event);
                    }
                    return false;
                });
        assertEquals(1, events.size());
        assertThrows(ProviderProtocolException.class,
                () -> consume("event: ping\ndata: {\"type\":\"other\"}\n\n", (name, data) -> {
                    AbstractStreamingModelAdapter.namedEvent(name, data, Set.of("ping"), "TEST_EVENT");
                    return false;
                }));
    }

    /** 原生 API 捕获的下游异常必须原样回传，不能被网络错误包装或当作 EOF 成功。 */
    @Test
    void propagatesOriginalCallbackFailure() {
        RuntimeException expected = new IllegalArgumentException("sink failed");
        RuntimeException observed = assertThrows(RuntimeException.class,
                () -> consume("data: {}\n\n", (name, data) -> { throw expected; }));
        assertSame(expected, observed);
    }

    /** 主动结束不会消费后续已缓冲帧，也不会把 OkHttp 的取消通知报告成用户取消。 */
    @Test
    void stopsAfterAcceptedTerminalBoundary() throws Exception {
        List<String> events = new ArrayList<>();
        consume("data: [DONE]\n\ndata: invalid later data\n\n", (name, data) -> {
            events.add(data);
            return "[DONE]".equals(data);
        });
        assertEquals(List.of("[DONE]"), events);
    }

    /** 长事件不按旧帧容量截断，Provider JSON 约束继续由同一个 mapper 提供。 */
    @Test
    void acceptsLargeEvent() throws Exception {
        String text = "x".repeat(3 * 1024 * 1024);
        List<Integer> lengths = new ArrayList<>();
        consume("data: {\"value\":\"" + text + "\"}\n\n", (name, data) -> {
            lengths.add(AbstractStreamingModelAdapter.providerObject(data, "TEST_EVENT").path("value").textValue().length());
            return false;
        }, false);
        assertEquals(List.of(text.length()), lengths);
    }

    /** 小输入逐字节写入并 flush，避免只验证一次性内存 Buffer 的中文解码。 */
    private static void consume(String body, BiPredicate<String, String> consumer) throws IOException {
        consume(body, consumer, true);
    }

    /** 一次测试拥有自己的 HTTP server、Call 与连接池，失败和主动结束均由 finally 回收。 */
    private static void consume(String body, BiPredicate<String, String> consumer, boolean split) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                if (split) {
                    for (byte value : bytes) {
                        output.write(value);
                        output.flush();
                    }
                } else {
                    output.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build();
        RequestController controller = new RequestController(CancellationToken.none());
        try {
            AbstractStreamingModelAdapter.executeSse(client,
                    new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort() + "/stream").build(),
                    controller, (status, headers, error) -> new IllegalStateException("unexpected HTTP status"), consumer);
        } finally {
            controller.complete();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
            server.stop(0);
        }
    }
}
