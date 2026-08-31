// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;

/** Provider 响应生命周期测试确保成功、协议失败和回调失败都由同一边界关闭正文。 */
final class ProviderResponseLifecycleTest {
    /** 正常读到 EOF 时只关闭一次正文源，避免连接池资源泄漏或重复所有权。 */
    @Test
    void closesSuccessfulResponseBodyExactlyOnce() {
        AtomicInteger closeCount = new AtomicInteger();
        OkHttpClient client = client(responseBody(
                "event: ping\ndata: {\"type\":\"ping\"}\n\n",
                MediaType.get("text/event-stream"), closeCount));
        RequestController controller = new RequestController(CancellationToken.none());

        AbstractStreamingModelAdapter.executeSse(
                client, request(), Set.of("ping"), "TEST_EVENT", controller,
                (status, headers, error) -> unexpectedStatus(),
                ignored -> { });

        controller.complete();
        assertEquals(1, closeCount.get());
    }

    /** Content-Type 缺失时在读取前失败，但仍关闭已经取得的响应正文。 */
    @Test
    void closesResponseBodyWhenContentTypeIsMissing() {
        AtomicInteger closeCount = new AtomicInteger();
        OkHttpClient client = client(responseBody("ignored", null, closeCount));
        RequestController controller = new RequestController(CancellationToken.none());

        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> AbstractStreamingModelAdapter.executeSse(
                        client, request(), Set.of("ping"), "TEST_EVENT", controller,
                        (status, headers, error) -> unexpectedStatus(),
                        ignored -> { }));

        controller.complete();
        assertEquals("CONTENT_TYPE", failure.code());
        assertEquals(1, closeCount.get());
    }

    /** 状态归约器拒绝事件时也必须关闭正文，不能把连接释放交给 GC。 */
    @Test
    void closesResponseBodyWhenReducerRejectsEvent() {
        AtomicInteger closeCount = new AtomicInteger();
        OkHttpClient client = client(responseBody(
                "event: ping\ndata: {\"type\":\"ping\"}\n\n",
                MediaType.get("text/event-stream"), closeCount));
        RequestController controller = new RequestController(CancellationToken.none());

        ProviderProtocolException failure = assertThrows(ProviderProtocolException.class,
                () -> AbstractStreamingModelAdapter.executeSse(
                        client, request(), Set.of("ping"), "TEST_EVENT", controller,
                        (status, headers, error) -> unexpectedStatus(),
                        ignored -> {
                            throw new ProviderProtocolException(
                                    "REDUCER_REJECTED", "test reducer rejected event", false);
                        }));

        controller.complete();
        assertEquals("REDUCER_REJECTED", failure.code());
        assertEquals(1, closeCount.get());
    }

    /** 用应用层 Interceptor 构造确定性响应，不打开真实网络连接。 */
    private static OkHttpClient client(ResponseBody body) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build())
                .build();
    }

    /** 创建只用于本地 Interceptor 的请求，URL 不会被实际访问。 */
    private static Request request() {
        return new Request.Builder().url("http://127.0.0.1/provider-test").build();
    }

    /** 返回不含响应正文的测试异常；成功响应不应触发该分支。 */
    private static ProviderProtocolException unexpectedStatus() {
        return new ProviderProtocolException(
                "UNEXPECTED_STATUS", "test received an unexpected status", false);
    }

    /** 将正文源包装为可观测关闭次数的 ResponseBody，并保留 contentType 空值场景。 */
    private static ResponseBody responseBody(
            String content, MediaType contentType, AtomicInteger closeCount) {
        Buffer buffer = new Buffer().writeUtf8(content);
        long length = buffer.size();
        BufferedSource source = Okio.buffer(new ForwardingSource(buffer) {
            /** 只记录底层 source 的真实关闭调用，便于识别重复所有权。 */
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        });
        return ResponseBody.create(source, contentType, length);
    }
}
