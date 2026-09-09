// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.port.out.ModelEventSink;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 多种原生 Provider wire adapter 的共享执行边界。
 *
 * <p>Provider 子类只映射请求和事件 JSON；本类统一持有接纳、重试 Policy、Deadline 与生命周期，
 * 避免协议代码创建第二个传输池或回放 sink 已接纳的语义输出。</p>
 */
public abstract class AbstractStreamingModelAdapter implements ModelAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractStreamingModelAdapter.class);
    public static final int MAX_ATTEMPTS = 3;
    static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    static final int MAX_EVENT_BYTES = 2 * 1024 * 1024;
    static final long MAX_RESPONSE_BYTES = 64L * 1024L * 1024L;
    static final int MAX_EVENT_COUNT = 8_192;
    public static final int MAX_ERROR_BODY_BYTES = 1024 * 1024;
    public static final ObjectMapper JSON = strictJsonMapper();

    private final ModelPort.ModelConfiguration configuration;
    private final ModelTransport transport;
    private final boolean ownsTransport;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<RequestController> activeControllers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();

    /**
     * 为直接调用方创建隔离 Adapter，并把传输所有权交给该实例。
     */
    protected AbstractStreamingModelAdapter(ModelPort.ModelConfiguration configuration) {
        this(configuration, new ModelTransport(), true);
    }

    /**
     * 借用组合根持有的传输，不创建额外 Dispatcher 或连接池。
     */
    protected AbstractStreamingModelAdapter(ModelPort.ModelConfiguration configuration, ModelTransport transport) {
        this(configuration, transport, false);
    }

    /**
     * 一次性校验不可变 Provider/Model 配置，确保每次重试使用相同端点和凭据快照。
     */
    private AbstractStreamingModelAdapter(ModelPort.ModelConfiguration configuration,
                                          ModelTransport transport, boolean ownsTransport) {
        this.configuration = requireSafeConfiguration(configuration);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.ownsTransport = ownsTransport;
    }

    /**
     * 向 Provider codec 暴露不可变模型配置，但不泄露可变传输状态。
     */
    protected final ModelPort.ModelConfiguration configuration() {
        return configuration;
    }

    /**
     * 返回请求配置作用域的 OkHttp 视图，底层复用共享 Dispatcher 和连接池。
     */
    protected final OkHttpClient httpClient() {
        return transport.clientFor(configuration);
    }

    /**
     * 按请求身份冻结唯一 envelope，并在正常、取消、协议失败和下游回调失败后
     * 统一释放完整 Prompt。Adapter 只能在这个边界内借用 envelope，避免复制 finally 时
     * 遗漏异常分支或把共享传输变成无上限内容缓存。
     */
    protected final <T> T withFrozenEnvelope(
            ModelPort.ModelRequest request,
            Supplier<com.fasterxml.jackson.databind.node.ObjectNode> encoder,
            Function<ProviderRequestEnvelope, T> operation) {
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(operation, "operation");
        ProviderRequestEnvelope frozen = transport.envelope(request,
                () -> ProviderRequestEnvelope.freeze(
                        Objects.requireNonNull(encoder.get(), "encoded request"), request.configuration().api()));
        try {
            return operation.apply(frozen);
        } finally {
            transport.releaseEnvelope(request);
        }
    }

    /**
     * 启动单次请求，并在发布完成回调中关闭取消 Registration、超时任务及请求 Controller。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public CompletionStage<ModelPort.ModelOutcome> start(ModelPort.ModelRequest request,
                                                         ModelEventSink eventSink,
                                                         CancellationToken cancellationToken) {
        Objects.requireNonNull(eventSink, "eventSink");
        return submit(request, cancellationToken,
                controller -> executeGoverned(request, eventSink, controller));
    }

    /**
     * 统一请求接纳、Deadline、取消注册与完成清理；泛型只承载结果，不允许两类调用复制生命周期。
     */
    @SuppressWarnings("PMD.CloseResource")
    private <T> CompletionStage<T> submit(
            ModelPort.ModelRequest request, CancellationToken cancellationToken,
            Function<RequestController, T> operation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        Objects.requireNonNull(operation, "operation");
        if (!configuration.equals(request.configuration())) {
            throw new IllegalArgumentException("model request does not match the adapter's pinned configuration");
        }
        RequestController controller = new RequestController(cancellationToken);
        CompletableFuture<T> result;
        ScheduledFuture<?> timeout;
        CancellationToken.Registration registration;
        synchronized (lifecycle) {
            if (closed.get()) throw new IllegalStateException("model adapter is closed");
            activeControllers.add(controller);
            transport.register(controller);
            try {
                result = CompletableFuture.supplyAsync(() -> operation.apply(controller),
                        transport.requestExecutor());
                timeout = transport.deadlineExecutor().schedule(controller::timeout,
                        configuration.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
                registration = cancellationToken.onCancellation(controller::cancel);
            } catch (RuntimeException failure) {
                activeControllers.remove(controller);
                transport.unregister(controller);
                controller.shutdown();
                throw failure;
            }
        }
        return result.whenComplete((ignored, failure) -> {
            timeout.cancel(false);
            registration.close();
            controller.complete();
            activeControllers.remove(controller);
            transport.unregister(controller);
        });
    }

    /**
     * 仅在首个语义事件被 sink 接受前重试已分类故障。
     */
    private ModelPort.ModelOutcome executeWithRetry(ModelPort.ModelRequest request,
                                                    ModelEventSink eventSink,
                                                    RequestController controller) {
        controller.bindThread(Thread.currentThread());
        AtomicBoolean semanticAccepted = new AtomicBoolean();
        Throwable last = null;
        int attempts = request.retryPolicy() == ModelPort.RetryPolicy.SINGLE_ATTEMPT ? 1 : MAX_ATTEMPTS;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            controller.throwIfStopped();
            try {
                StreamContext context = new StreamContext(eventSink, semanticAccepted, controller, request);
                return executeProviderAttempt(request, context, controller);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (ProviderProtocolException failure) {
                last = failure;
                if (!failure.retryable() || semanticAccepted.get() || attempt == attempts) {
                    /*
                     * 只有已校验机器码进入诊断；Provider 正文、端点、异常消息和 cause 均保持隔离，
                     * 使线上故障可分类且不削弱脱敏边界。
                     */
                    LOGGER.warn(
                            "Provider request stopped provider_failure_code={} provider_failure_detail={} "
                            + "semantic_accepted={} attempt={}",
                            failure.code(), failure.getMessage(), semanticAccepted.get(), attempt);
                    throw failure;
                }
                awaitBackoff(attempt, failure.retryAfter().orElse(null), controller);
            }
        }
        throw new ProviderProtocolException("NETWORK_ERROR", "provider request failed before a response", true, last);
    }

    /** 将一次完整发送（含内部重试）计为一个熔断样本，避免单请求三次重试立即开路。 */
    private ModelPort.ModelOutcome executeGoverned(ModelPort.ModelRequest request,
                                                   ModelEventSink eventSink,
                                                   RequestController controller) {
        ProviderCircuitBreaker.Permit permit = transport.acquireCircuit(
                configuration, ProviderCircuitBreaker.Operation.SEND);
        try {
            ModelPort.ModelOutcome result = executeWithRetry(request, eventSink, controller);
            permit.success();
            return result;
        } catch (CancellationException cancelled) {
            permit.cancelled();
            throw cancelled;
        } catch (RuntimeException failure) {
            permit.failure();
            throw failure;
        }
    }

    /**
     * 各 Provider 只负责 wire 映射，重试和取消仍共享同一门禁。
     */
    protected abstract ModelPort.ModelOutcome executeProviderAttempt(
            ModelPort.ModelRequest request, StreamContext context, RequestController controller);

    /**
     * 在既有虚拟线程协调器上执行同步流请求。严格 Reader 到达 EOF 或协议失败关闭正文前，
     * Response 所有权始终附着于 Call，使取消只有一条权威清理路径。
     */
    protected static void executeSse(OkHttpClient client, Request request, Set<String> allowedEvents,
                           String providerCode, RequestController controller,
                           ProviderErrorMapper errorMapper,
                           Consumer<ProviderSseReader.Event> eventConsumer) {
        Objects.requireNonNull(allowedEvents, "allowedEvents");
        Objects.requireNonNull(providerCode, "providerCode");
        Objects.requireNonNull(eventConsumer, "eventConsumer");
        executeSseBody(client, request, controller, errorMapper, bounded -> {
            ProviderSseReader reader = new ProviderSseReader(
                    bounded, allowedEvents, providerCode);
            ProviderSseReader.Event event;
            while ((event = reader.next()) != null) {
                controller.throwIfStopped();
                eventConsumer.accept(event);
            }
        });
    }

    /** 使用独立 data-only Reader 执行 Chat SSE，不放宽命名 SSE 的事件字段白名单。 */
    protected static void executeChatSse(
            OkHttpClient client, Request request, RequestController controller,
            ProviderErrorMapper errorMapper, Consumer<OpenAiChatSseReader.Event> eventConsumer) {
        Objects.requireNonNull(eventConsumer, "eventConsumer");
        executeSseBody(client, request, controller, errorMapper, bounded -> {
            OpenAiChatSseReader reader = new OpenAiChatSseReader(bounded);
            OpenAiChatSseReader.Event event;
            while ((event = reader.next()) != null) {
                controller.throwIfStopped();
                eventConsumer.accept(event);
            }
        });
    }

    /**
     * 统一单次 SSE HTTP 交换、受限流、取消与资源释放；协议 Reader 只消费已经通过状态和
     * Content-Type 校验的 bounded body；子类只获得一次性输入流，不能绕过容量和清理边界。
     */
    protected static void executeSseBody(
            OkHttpClient client, Request request, RequestController controller,
            ProviderErrorMapper errorMapper, SseBodyConsumer bodyConsumer) {
        Objects.requireNonNull(bodyConsumer, "bodyConsumer");
        executeHttp(client, request, controller, errorMapper, "provider network exchange failed", response -> {
            requireEventStream(response);
            InputStream source = response.body().byteStream();
            try (BoundedSseInputStream bounded = new BoundedSseInputStream(
                    source, MAX_EVENT_BYTES, MAX_RESPONSE_BYTES, MAX_EVENT_COUNT)) {
                bodyConsumer.consume(bounded);
            }
            return null;
        });
    }

    /** 单一 HTTP exchange owner 统一 Call/Response 注册、错误映射、取消复核和确定性释放。 */
    private static <T> T executeHttp(
            OkHttpClient client, Request request, RequestController controller,
            ProviderErrorMapper errorMapper, String networkFailureMessage,
            ResponseBodyConsumer<T> bodyConsumer) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(errorMapper, "errorMapper");
        Objects.requireNonNull(networkFailureMessage, "networkFailureMessage");
        Objects.requireNonNull(bodyConsumer, "bodyConsumer");
        controller.throwIfStopped();
        Call call = client.newCall(request);
        controller.bindCall(call);
        Response response = null;
        boolean streamBound = false;
        try {
            response = call.execute();
            controller.bindStream(response);
            streamBound = true;
            if (!response.isSuccessful()) {
                JsonNode error = readErrorBody(response);
                throw errorMapper.map(response.code(), response.headers(), error);
            }
            T value = bodyConsumer.consume(response);
            controller.throwIfStopped();
            return value;
        } catch (IOException failure) {
            controller.throwIfStopped();
            throw new ProviderProtocolException(
                    "NETWORK_ERROR", networkFailureMessage, true, failure);
        } finally {
            if (streamBound) controller.clearStream(response);
            if (response != null) response.close();
            controller.clearCall(call);
        }
    }

    /** 允许协议 Reader 抛出 IO 截断，同时禁止共享交换层了解事件类型。 */
    @FunctionalInterface
    protected interface SseBodyConsumer {
        /** 消费一次有界响应正文；返回后共享层再次检查取消并释放 Response。 */
        void consume(InputStream input) throws IOException;
    }

    /** 交换层把仍受 owner 管理的 Response 借给有界正文解析器，不允许正文消费者关闭共享 Call。 */
    @FunctionalInterface
    private interface ResponseBodyConsumer<T> {
        /** 在 Response 生命周期内完成解析；IO 失败由交换层统一映射。 */
        T consume(Response response) throws IOException;
    }

    /**
     * 在所有 Provider 协议共用的上限内序列化冻结请求；LimitedOutput 只持有请求内内存。
     */
    @SuppressWarnings("PMD.CloseResource")
    public static byte[] serializeRequest(JsonNode request) {
        Objects.requireNonNull(request, "request");
        try {
            LimitedOutput output = new LimitedOutput(MAX_REQUEST_BYTES);
            JSON.writeValue(output, request);
            return output.toByteArray();
        } catch (IOException | RuntimeException failure) {
            throw new ProviderProtocolException(
                    "REQUEST_ENCODING", "provider request could not be encoded", false);
        }
    }

    /**
     * 解析 Provider 端点，同时保留显式反向代理路径前缀。
     */
    public static String endpoint(URI baseUri, String suffix) {
        String value = Objects.requireNonNull(baseUri, "baseUri").toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.endsWith(suffix)) return value;
        if (value.endsWith("/v1")) return value + suffix.substring("/v1".length());
        return value + suffix;
    }

    /**
     * 使用 1s/2s 的有界指数退避等待，给短暂代理重启留下恢复窗口；jitter 只打散并发 Turn，
     * Controller 仍在每个切片前复核取消与请求总 Deadline，且不改变三次尝试和语义接纳门禁。
     */
    public static void awaitBackoff(int attempt, Duration retryAfter, RequestController controller) {
        long baseMillis = attempt == 1 ? 1_000L : 2_000L;
        long hintMillis = retryAfter == null ? 0L : retryAfter.toMillis();
        long delayMillis = Math.min(60_000L,
                Math.max(baseMillis, hintMillis) + ThreadLocalRandom.current().nextLong(0L, 26L));
        long remaining = TimeUnit.MILLISECONDS.toNanos(delayMillis);
        while (remaining > 0) {
            controller.throwIfStopped();
            long slice = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25));
            long before = System.nanoTime();
            java.util.concurrent.locks.LockSupport.parkNanos(slice);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                controller.throwIfStopped();
                throw new ProviderProtocolException(
                        "NETWORK_INTERRUPTED", "provider retry wait was interrupted", true);
            }
            remaining -= Math.max(1L, System.nanoTime() - before);
        }
    }

    /**
     * 关闭活动请求及隔离传输所有者；共享传输继续由 Factory 持有。
     */
    @Override
    public void close() {
        Set<RequestController> snapshot;
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) return;
            snapshot = Set.copyOf(activeControllers);
        }
        snapshot.forEach(RequestController::shutdown);
        if (ownsTransport) transport.close();
    }

    /** 构造 Provider 请求前再次校验 URI，凭据完整性已由冻结模型配置的构造边界保证。 */
    private static ModelPort.ModelConfiguration requireSafeConfiguration(
            ModelPort.ModelConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        URI baseUri = configuration.baseUri();
        if (baseUri.getUserInfo() != null || baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("model baseUri must not contain userinfo, query, or fragment");
        }
        return configuration;
    }

    /**
     * 要求成功流响应声明精确 SSE media type。
     */
    private static void requireEventStream(Response response) {
        MediaType contentType = response.body().contentType();
        if (contentType == null || !"text".equalsIgnoreCase(contentType.type())
            || !"event-stream".equalsIgnoreCase(contentType.subtype())) {
            throw new ProviderProtocolException(
                    "CONTENT_TYPE", "provider streaming response has an invalid content type", false);
        }
        long declared = response.body().contentLength();
        if (declared > MAX_RESPONSE_BYTES) {
            throw new ProviderProtocolException(
                    "RESPONSE_LIMIT", "provider response exceeds the size limit", false);
        }
    }

    /**
     * 只读取受限错误文档，并丢弃畸形或非对象载荷。
     */
    private static JsonNode readErrorBody(Response response) throws IOException {
        long declared = response.body().contentLength();
        if (declared > MAX_ERROR_BODY_BYTES) {
            throw new ProviderProtocolException(
                    "RESPONSE_LIMIT", "provider error response exceeds the size limit", false);
        }
        byte[] bytes;
        try (InputStream input = response.body().byteStream()) {
            bytes = readLimited(input, MAX_ERROR_BODY_BYTES);
        }
        if (bytes.length == 0) return null;
        try {
            JsonNode value = JSON.readTree(bytes);
            return value != null && value.isObject() ? value : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 生成两种 Provider 共用的脱敏 HTTP 状态说明，只保留受限 code/type 标识符，绝不拼接正文。
     */
    public static String serviceFailureDetail(int status, JsonNode root) {
        StringBuilder detail = new StringBuilder("provider returned HTTP status ").append(status);
        if (root == null) return detail.toString();
        appendSafeErrorToken(detail, "code", root.path("error").path("code").textValue());
        appendSafeErrorToken(detail, "type", root.path("error").path("type").textValue());
        return detail.toString();
    }

    /**
     * 追加受限诊断标识符，并丢弃攻击者可控值或自由文本。
     */
    private static void appendSafeErrorToken(StringBuilder detail, String label, String value) {
        if (value != null && value.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            detail.append(' ').append(label).append(' ').append(value);
        }
    }

    /**
     * 非流式正文超过上限前停止分配；LimitedOutput 不持有文件、Socket 等外部资源。
     */
    @SuppressWarnings("PMD.CloseResource")
    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        LimitedOutput output = new LimitedOutput(limit);
        byte[] buffer = new byte[8_192];
        while (true) {
            int read = input.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1));
            if (read < 0) return output.toByteArray();
            try {
                output.write(buffer, 0, read);
            } catch (LimitExceeded failure) {
                throw new ProviderProtocolException(
                        "RESPONSE_LIMIT", "provider error response exceeds the size limit", false);
            }
        }
    }

    /**
     * 构造严格 Mapper，使重复键和恶意嵌套在状态变更前失败，并保留 Tool JSON 的十进制 scale。
     */
    private static ObjectMapper strictJsonMapper() {
        com.fasterxml.jackson.core.JsonFactory factory = com.fasterxml.jackson.core.JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(64)
                        .maxStringLength(4_000_000)
                        .maxNumberLength(1_000)
                        .build())
                .build();
        return JsonMapper.builder(factory)
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
    }

    /**
     * Provider 状态映射不接收原始正文、URL 或凭据材料。
     */
    @FunctionalInterface
    protected interface ProviderErrorMapper {
        /**
         * 映射稳定状态、Header 和可选的受限 JSON 对象。
         */
        RuntimeException map(int status, Headers headers, JsonNode error);
    }

    /**
     * 在复制字节前检查增长量的受限请求或错误缓冲区。
     */
    private static final class LimitedOutput extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(8_192);

        /**
         * 将硬上限与普通缓冲容量分离。
         */
        LimitedOutput(int limit) {
            this.limit = limit;
        }

        /**
         * 复制单字节前拒绝越界增长。
         */
        @Override
        public void write(int value) {
            ensureRemaining(1);
            output.write(value);
        }

        /**
         * 批量复制前拒绝越界增长。
         */
        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureRemaining(length);
            output.write(bytes, offset, length);
        }

        /**
         * 返回完成的受限字节数组。
         */
        byte[] toByteArray() {
            return output.toByteArray();
        }

        /**
         * 返回当前字节数，用于错误正文读取上限。
         */
        int size() {
            return output.size();
        }

        /**
         * 将算术或容量溢出转换为本地控制流。
         */
        private void ensureRemaining(int additional) {
            if (additional > limit - output.size()) throw new LimitExceeded();
        }
    }

    /**
     * 内部容量信号会立即转换为已脱敏 Provider 异常。
     */
    private static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
