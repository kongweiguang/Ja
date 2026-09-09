// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.transport;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 基于 OkHttp 的有界 Streamable HTTP MCP 传输。
 *
 * <p>MCP SDK 虽然暴露传输 SPI，但默认 HTTP 实现不共享 Ja 的网络资源所有权。
 * 因此本实现追踪每个活动 Call，并由 OkHttp SSE 负责帧解析，使取消和关闭直接作用于真实 Socket。</p>
 */
public final class JaBoundedHttpTransport extends BoundedMcpTransport {
    private static final MediaType JSON = MediaType.get("application/json");
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String PROTOCOL_HEADER = "MCP-Protocol-Version";
    private final URI endpoint;
    private final Map<String, String> headers;
    private final McpLimits limits;
    private final McpDeadline deadline;
    private final OkHttpClient client;
    private final ConcurrentHashMap<Call, Boolean> activeCalls = new ConcurrentHashMap<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;
    private volatile String sessionId;

    /**
     * 创建独占 OkHttp 资源的传输，所有局部超时都不得突破外层 MCP Deadline。
     */
    public JaBoundedHttpTransport(URI endpoint, Map<String, String> headers,
                           List<String> protocolVersions, McpJsonMapper jsonMapper, McpLimits limits,
                           McpDeadline deadline) {
        this(endpoint, headers, protocolVersions, jsonMapper, limits, deadline, () -> {
        });
    }

    /**
     * 生产 Session 注入目录失效观察者；回调只执行原子标记，不进入 OkHttp 或 SDK 的阻塞路径。
     */
    public JaBoundedHttpTransport(URI endpoint, Map<String, String> headers,
                                  List<String> protocolVersions, McpJsonMapper jsonMapper, McpLimits limits,
                                  McpDeadline deadline, Runnable toolsChanged) {
        super(jsonMapper, protocolVersions, toolsChanged);
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.headers = Map.copyOf(headers);
        this.limits = Objects.requireNonNull(limits, "limits");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        java.time.Duration startupTimeout = deadline.remaining(
                limits.startupTimeout(), "mcp_http_deadline_elapsed");
        java.time.Duration requestTimeout = deadline.remaining(
                limits.requestTimeout(), "mcp_http_deadline_elapsed");
        this.client = new OkHttpClient.Builder()
                .connectTimeout(startupTimeout)
                .readTimeout(requestTimeout)
                .writeTimeout(requestTimeout)
                .callTimeout(requestTimeout)
                .retryOnConnectionFailure(false)
                .build();
    }

    /**
     * 仅绑定 SDK 回调而不提前联网，避免 initialize 之前出现不可归属的网络副作用。
     */
    @Override
    public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> callback) {
        handler = observeNotifications(Objects.requireNonNull(callback, "callback"));
        if (closing.get()) {
            return Mono.error(new IllegalStateException("mcp_http_closed"));
        }
        return Mono.empty();
    }

    /**
     * 发送一个有界 JSON-RPC 请求并分派 JSON 或 SSE 响应。
     * MCP 通知的 HTTP 202 是无响应体终态，因此读取到 Header 后立即结束；否则未声明零长度的
     * HTTP/1.1 对端可能让客户端等待协议并不要求的 EOF。普通请求仍必须返回可关联的 JSON-RPC 消息。
     */
    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        Objects.requireNonNull(message, "message");
        return Mono.fromRunnable(() -> sendBlocking(message));
    }

    /**
     * 执行并完整分派一次 OkHttp 响应后才释放 Socket，确保响应体所有权不外泄。
     */
    @SuppressWarnings("PMD.CloseResource")
    private void sendBlocking(McpSchema.JSONRPCMessage message) {
        if (closing.get()) {
            throw new IllegalStateException("mcp_http_closed");
        }
        try {
            byte[] encoded = jsonMapper.writeValueAsBytes(message);
            if (encoded.length > limits.maxMessageBytes()) {
                throw new IllegalStateException("mcp_http_message_limit");
            }
            Request.Builder builder = new Request.Builder()
                    .url(endpoint.toString())
                    .header("Content-Type", JSON.toString())
                    .header("Accept", "application/json, text/event-stream")
                    .header(PROTOCOL_HEADER, protocolVersions.getFirst())
                    .post(RequestBody.create(encoded, JSON));
            headers.forEach(builder::header);
            String currentSession = sessionId;
            if (currentSession != null) {
                builder.header(SESSION_HEADER, currentSession);
            }
            Call call = client.newCall(builder.build());
            admitCall(call);
            try (Response response = call.execute()) {
                String responseSession = response.header(SESSION_HEADER);
                if (responseSession != null && !responseSession.isBlank()) {
                    sessionId = responseSession;
                }
                if (!response.isSuccessful()) {
                    throw new IOException("mcp_http_status_" + response.code());
                }
                if (response.code() == 202) {
                    if (message instanceof McpSchema.JSONRPCNotification) {
                        return;
                    }
                    throw new IOException("mcp_http_response_missing");
                }
                ResponseBody body = response.body();
                MediaType contentType = body.contentType();
                if (contentType == null) {
                    throw new IOException("mcp_http_content_type_missing");
                }
                if ("text".equalsIgnoreCase(contentType.type())
                    && "event-stream".equalsIgnoreCase(contentType.subtype())) {
                    dispatchSse(response);
                } else if ("application".equalsIgnoreCase(contentType.type())
                           && "json".equalsIgnoreCase(contentType.subtype())) {
                    dispatchJson(readBounded(body.byteStream()));
                } else {
                    throw new IOException("mcp_http_content_type_invalid");
                }
            } finally {
                activeCalls.remove(call);
            }
        } catch (RuntimeException | IOException failure) {
            if (!closing.get()) {
                reportTransportFailure(failure);
            }
            throw new IllegalStateException("mcp_http_exchange_failed", failure);
        }
    }

    /**
     * 关闭 Call 创建与登记之间的竞态，防止 shutdown 漏掉刚创建的请求。
     * 登记后必须再次检查状态，因为 close 可能在插入 Map 与执行之间获胜；此时取消该 Call 才能保持所有权确定。
     */
    private void admitCall(Call call) {
        if (closing.get()) {
            call.cancel();
            throw new IllegalStateException("mcp_http_closed");
        }
        activeCalls.put(call, Boolean.TRUE);
        if (closing.get() && activeCalls.remove(call) != null) {
            call.cancel();
            throw new IllegalStateException("mcp_http_closed");
        }
    }

    /**
     * 在 Jackson 分配对象前按字节限制读取单个 JSON 响应。
     */
    private byte[] readBounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(limits.maxMessageBytes() + 1);
        if (bytes.length > limits.maxMessageBytes()) {
            throw new IOException("mcp_http_message_limit");
        }
        return bytes;
    }

    /**
     * 同步解码并交付一个 JSON-RPC 响应，避免响应体释放后仍有异步读取。
     */
    private void dispatchJson(byte[] bytes) throws IOException {
        if (bytes.length == 0) {
            return;
        }
        Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> callback = handler;
        if (callback == null) {
            throw new IOException("mcp_http_handler_missing");
        }
        McpSchema.JSONRPCMessage decoded = McpSchema.deserializeJsonRpcMessage(
                jsonMapper, new String(bytes, StandardCharsets.UTF_8));
        callback.apply(Mono.just(decoded)).block(
                deadline.remaining(limits.requestTimeout(), "mcp_http_deadline_elapsed"));
    }

    /**
     * 将 SSE 语法与 UTF-8 分帧交给 OkHttp，同时保留 Ja 的总响应预算。
     */
    private void dispatchSse(Response response) throws IOException {
        Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> callback = handler;
        if (callback == null) {
            throw new IOException("mcp_http_handler_missing");
        }
        AtomicReference<IOException> failure = new AtomicReference<>();
        try (Response bounded = boundedSseResponse(response)) {
            EventSources.processResponse(bounded,
                    new BoundedMcpEventListener(callback, limits.maxMessageBytes(), failure));
        }
        IOException observed = failure.get();
        if (observed != null) {
            throw observed;
        }
    }

    /**
     * 在 OkHttp 解析 comment、id 与 data 之前限制原始 SSE 字节。
     * 若只统计解码后的 data，对端可用非 data 帧绕过消息预算并占用任意内存。
     */
    @SuppressWarnings("PMD.CloseResource")
    private Response boundedSseResponse(Response response) throws IOException {
        ResponseBody body = Objects.requireNonNull(response.body(), "response body");
        if (body.contentLength() > limits.maxMessageBytes()) {
            throw new IOException("mcp_http_message_limit");
        }
        BufferedSource bounded = Okio.buffer(new ForwardingSource(body.source()) {
            private long observed;

            /** 在解析器分配内存前拒绝超过总响应上限的第一个字节。 */
            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                long allowed = Math.min(byteCount, limits.maxMessageBytes() - observed + 1L);
                long read = super.read(sink, allowed);
                if (read > 0) {
                    observed += read;
                    if (observed > limits.maxMessageBytes()) {
                        throw new IOException("mcp_http_message_limit");
                    }
                }
                return read;
            }
        });
        return response.newBuilder()
                .body(ResponseBody.create(bounded, body.contentType(), body.contentLength()))
                .build();
    }

    /**
     * 将 OkHttp 解码后的完整 SSE data 作为单个 JSON-RPC 消息分派。
     */
    private void dispatchEventData(
            Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> callback,
            String data) throws IOException {
        if (data.isEmpty()) {
            return;
        }
        McpSchema.JSONRPCMessage decoded = McpSchema.deserializeJsonRpcMessage(jsonMapper, data);
        callback.apply(Mono.just(decoded)).block(
                deadline.remaining(limits.requestTimeout(), "mcp_http_deadline_elapsed"));
    }

    /**
     * 将 OkHttp SSE 回调转成有界同步 SDK 分派，避免引入第二套解析器。
     */
    private final class BoundedMcpEventListener extends EventSourceListener {
        private final Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> callback;
        private final int maximumBytes;
        private final AtomicReference<IOException> failure;
        private final AtomicInteger observedBytes = new AtomicInteger();

        /**
         * 仅保存单个响应流所需的回调与字节上限。
         */
        private BoundedMcpEventListener(
                Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> callback,
                int maximumBytes,
                AtomicReference<IOException> failure) {
            this.callback = callback;
            this.maximumBytes = maximumBytes;
            this.failure = failure;
        }

        /**
         * 在事件进入 MCP SDK 回调前拒绝超限或格式错误的数据。
         */
        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            try {
                int next = Math.addExact(observedBytes.get(), data.getBytes(StandardCharsets.UTF_8).length);
                if (next > maximumBytes) {
                    throw new IOException("mcp_http_message_limit");
                }
                observedBytes.set(next);
                dispatchEventData(callback, data);
            } catch (IOException | RuntimeException eventFailure) {
                IOException bounded = eventFailure instanceof IOException ioFailure
                        ? ioFailure : new IOException("mcp_http_sse_dispatch_failed", eventFailure);
                if (failure.compareAndSet(null, bounded)) {
                    eventSource.cancel();
                }
            }
        }

        /**
         * 只保留首个传输失败，并从诊断信息中丢弃响应体。
         */
        @Override
        public void onFailure(EventSource eventSource, Throwable cause, Response failedResponse) {
            IOException bounded = cause instanceof IOException ioFailure
                    ? ioFailure : new IOException("mcp_http_sse_failed", cause);
            failure.compareAndSet(null, bounded);
        }
    }

    /**
     * 先取消活动 Call，再释放连接池与 Dispatcher，并在公共模板下保证关闭幂等。
     */
    @Override
    protected void closeTransport() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        long closeDeadline = deadline.phaseDeadline(limits.closeTimeout());
        activeCalls.keySet().forEach(Call::cancel);
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdownNow();
        awaitDispatcher(closeDeadline);
        client.connectionPool().evictAll();
        awaitActiveCalls(closeDeadline);
        if (!activeCalls.isEmpty()) {
            throw new IllegalStateException("mcp_http_close_timeout");
        }
    }

    /**
     * 只在关闭预算内等待，防止 Dispatcher 线程长于 Session 所有者存活。
     */
    private void awaitDispatcher(long closeDeadline) {
        long remaining = deadline.remainingNanos(closeDeadline);
        if (remaining <= 0) {
            return;
        }
        try {
            client.dispatcher().executorService().awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 等待每个已取消 Call 离开 finally 后再报告关闭完成，确保资源所有权已收敛。
     */
    private void awaitActiveCalls(long closeDeadline) {
        while (!activeCalls.isEmpty()) {
            long remaining = deadline.remainingNanos(closeDeadline);
            if (remaining <= 0) {
                return;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
