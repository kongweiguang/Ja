// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.shared;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.ModelAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 多种原生 Provider wire adapter 的共享执行边界。
 *
 * <p>Provider 子类只映射请求和事件 JSON；本类统一持有接纳、重试 Policy、Deadline 与生命周期，
 * 避免协议代码创建第二个传输池或回放 sink 已接纳的语义输出。</p>
 */
public abstract class AbstractStreamingModelAdapter implements ModelAdapter {
    public static final int MAX_ERROR_BODY_BYTES = 1024 * 1024;
    public static final ObjectMapper JSON = strictJsonMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractStreamingModelAdapter.class);
    private final ModelPort.ModelConfiguration configuration;
    private final ModelTransport transport;
    private final boolean ownsTransport;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<RequestController> activeControllers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();

    /**
     * 先校验冻结配置再创建隔离传输，避免配置失败时遗失尚未交给 owner 的资源。
     */
    protected AbstractStreamingModelAdapter(ModelPort.ModelConfiguration configuration) {
        this(requireSafeConfiguration(configuration), new ModelTransport(), true);
    }

    /**
     * 先校验冻结配置再借用组合根传输，不创建额外 Dispatcher 或连接池。
     */
    protected AbstractStreamingModelAdapter(ModelPort.ModelConfiguration configuration, ModelTransport transport) {
        this(requireSafeConfiguration(configuration), transport, false);
    }

    /**
     * 只接纳委托构造器已经校验的不可变配置；后续字段赋值不会再触发资源外部副作用。
     */
    private AbstractStreamingModelAdapter(ModelPort.ModelConfiguration configuration,
                                          ModelTransport transport, boolean ownsTransport) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.ownsTransport = ownsTransport;
    }

    /**
     * 只分类当前 Provider 状态机发出的规范断流码；重试由会话根据持久事实判定，不接受历史别名。
     */
    private static boolean recoverableShape(ProviderProtocolException failure) {
        return switch (failure.code()) {
            case "STREAM_TRUNCATED" -> true;
            default -> false;
        };
    }

    /**
     * 将可恢复流错误标为外层 Agent 可识别的类别，并保留原始诊断链和退避提示。
     */
    private static ProviderProtocolException terminalFailure(ProviderProtocolException failure) {
        if (!recoverableShape(failure)) {
            return failure;
        }
        return new ProviderProtocolException(failure.code(), failure.getMessage(), true,
                failure, failure.retryAfter().orElse(null), "MODEL_STREAM_INVALID");
    }

    /**
     * 与 MCP 一样由 OkHttp 解析 SSE；同步回调保持 sink 背压和既有虚拟线程所有权。
     * 消费者返回 true 表示协议终止边界已接受，仍须由 Provider 状态机校验最终业务结果。
     */
    protected static void executeSse(
            OkHttpClient client, Request request, RequestController controller,
            ProviderErrorMapper errorMapper, BiPredicate<String, String> eventConsumer) {
        Objects.requireNonNull(eventConsumer, "eventConsumer");
        executeHttp(client, request, controller, errorMapper, "provider network exchange failed", response -> {
            requireEventStream(response);
            ProviderEventListener listener = new ProviderEventListener(controller, eventConsumer);
            EventSources.processResponse(response, listener);
            listener.throwIfFailed();
            return null;
        });
    }

    /**
     * 仅解释 Provider 事件语义；扩展事件不进入状态机，SSE 字段与 UTF-8 解码全部沿用 OkHttp。
     */
    protected static JsonNode namedEvent(
            String name, String data, Set<String> allowedEvents, String providerCode) {
        if (name == null) {
            throw new ProviderProtocolException(providerCode, "provider event name is missing", false);
        }
        if (!allowedEvents.contains(name)) {
            return null;
        }
        JsonNode root = providerObject(data, providerCode);
        if (!name.equals(root.path("type").textValue())) {
            throw new ProviderProtocolException(providerCode, "provider event type does not match", false);
        }
        return root;
    }

    /**
     * 已闭合 SSE 事件中的 JSON 损坏属于协议错误；网络 EOF 的缺失终态由状态机另行判断。
     */
    protected static JsonNode providerObject(String data, String providerCode) {
        try (JsonParser parser = JSON.createParser(data)) {
            JsonNode root = JSON.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new ProviderProtocolException(providerCode, "provider event payload is invalid", false);
            }
            return root;
        } catch (IOException failure) {
            throw new ProviderProtocolException(providerCode, "provider event payload is invalid", false, failure);
        }
    }

    /**
     * 单一 HTTP exchange owner 统一 Call/Response 注册、错误映射和资源释放，并把响应读取超时映射为
     * 可重试的空闲超时，避免将它和服务端拒绝或结构损坏混为一类。
     */
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
                throw classifyHttpFailure(response.code(),
                        errorMapper.map(response.code(), response.headers(), error));
            }
            T value;
            try {
                value = bodyConsumer.consume(response);
            } catch (SocketTimeoutException idleTimeout) {
                controller.throwIfStopped();
                throw new ProviderProtocolException(
                        "REQUEST_IDLE_TIMEOUT", "provider response stream exceeded its idle timeout", true,
                        idleTimeout, "MODEL_IDLE_TIMEOUT");
            }
            controller.throwIfStopped();
            return value;
        } catch (IOException failure) {
            controller.throwIfStopped();
            if (failure instanceof SocketTimeoutException) {
                throw new ProviderProtocolException(
                        "NETWORK_TIMEOUT", "provider request exceeded a network timeout", true, failure);
            }
            throw new ProviderProtocolException(
                    "NETWORK_ERROR", networkFailureMessage, true, failure);
        } finally {
            if (streamBound) {
                controller.clearStream(response);
            }
            if (response != null) {
                response.close();
            }
            controller.clearCall(call);
        }
    }

    /**
     * 保留 Provider mapper 判定的重试位，并只将终态分类归一为暂态不可用或确定性上游拒绝。
     */
    private static RuntimeException classifyHttpFailure(int status, RuntimeException mappedFailure) {
        if (status < 400 || status > 499 || status == 429
            || !(mappedFailure instanceof ProviderProtocolException failure)) {
            return mappedFailure;
        }
        String terminalErrorCode = failure.retryable() ? "MODEL_UNAVAILABLE" : "MODEL_UPSTREAM_REJECTED";
        if (terminalErrorCode.equals(failure.terminalErrorCode())) {
            return mappedFailure;
        }
        return new ProviderProtocolException(failure.code(), failure.getMessage(), failure.retryable(),
                failure, failure.retryAfter().orElse(null), terminalErrorCode);
    }

    /**
     * 序列化冻结的 Provider 请求，不施加 Ja 自定义传输字节上限。
     */
    @SuppressWarnings("PMD.CloseResource")
    public static byte[] serializeRequest(JsonNode request) {
        Objects.requireNonNull(request, "request");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(8_192);
            JSON.writeValue(output, request);
            return output.toByteArray();
        } catch (IOException | RuntimeException failure) {
            throw new ProviderProtocolException(
                    "REQUEST_ENCODING", "provider request could not be encoded", false, failure);
        }
    }

    /**
     * 解析 Provider 端点，同时保留显式反向代理路径前缀。
     */
    public static String endpoint(URI baseUri, String suffix) {
        String value = Objects.requireNonNull(baseUri, "baseUri").toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith(suffix)) {
            return value;
        }
        if (value.endsWith("/v1")) {
            return value + suffix.substring("/v1".length());
        }
        return value + suffix;
    }

    /**
     * 构造 Provider 请求前再次校验 URI，凭据完整性已由冻结模型配置的构造边界保证。
     */
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
    }

    /**
     * 错误正文只是可选诊断；超大正文不读取也不覆盖 HTTP 状态分类，避免 429/5xx 被误判为永久失败。
     */
    private static JsonNode readErrorBody(Response response) throws IOException {
        long declared = response.body().contentLength();
        if (declared > MAX_ERROR_BODY_BYTES) {
            return null;
        }
        byte[] bytes;
        try (InputStream input = response.body().byteStream()) {
            bytes = input.readNBytes(MAX_ERROR_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_ERROR_BODY_BYTES) {
            return null;
        }
        if (bytes.length == 0) {
            return null;
        }
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
        if (root == null) {
            return detail.toString();
        }
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
     * 构造严格 Mapper，使重复键和恶意嵌套在状态变更前失败，并保留 Tool JSON 的十进制 scale。
     */
    private static ObjectMapper strictJsonMapper() {
        com.fasterxml.jackson.core.JsonFactory factory = com.fasterxml.jackson.core.JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(64)
                        .maxNumberLength(1_000)
                        .maxStringLength(Integer.MAX_VALUE)
                        .maxDocumentLength(Long.MAX_VALUE)
                        .maxTokenCount(Long.MAX_VALUE)
                        .build())
                .build();
        return JsonMapper.builder(factory)
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
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
                controller -> executeOnce(request, eventSink, controller));
    }

    /**
     * 依据显式 deadline policy 统一请求接纳与清理：短内部调用保留 Adapter 总时长上限，普通 Turn
     * 只受逐次读取空闲上限和调用方取消控制。先登记取消和 Deadline 再调度，准入失败释放自身资源。
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
        ScheduledFuture<?> timeout = null;
        CancellationToken.Registration registration = CancellationToken.Registration.noop();
        synchronized (lifecycle) {
            if (closed.get()) {
                throw new IllegalStateException("model adapter is closed");
            }
            activeControllers.add(controller);
            try {
                transport.register(controller);
                registration = Objects.requireNonNull(cancellationToken.onCancellation(controller::cancel),
                        "cancellation registration");
                if (request.deadlinePolicy() == ModelPort.RequestDeadlinePolicy.CALL_BOUNDED) {
                    timeout = transport.deadlineExecutor().schedule(controller::timeout,
                            configuration.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
                }
                result = CompletableFuture.supplyAsync(() -> operation.apply(controller),
                        transport.requestExecutor());
            } catch (RuntimeException failure) {
                try {
                    controller.shutdown();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                try {
                    completeRequest(controller, registration, timeout);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
        ScheduledFuture<?> deadline = timeout;
        CancellationToken.Registration cancellation = registration;
        return result.whenComplete((ignored, failure) -> completeRequest(controller, cancellation, deadline));
    }

    /** 登记和执行两类退出共用清理，外部取消句柄关闭失败也不能遗留请求引用。 */
    private void completeRequest(RequestController controller, CancellationToken.Registration registration,
                                 ScheduledFuture<?> deadline) {
        try {
            if (deadline != null) {
                deadline.cancel(false);
            }
            try {
                registration.close();
            } finally {
                controller.complete();
            }
        } finally {
            activeControllers.remove(controller);
            transport.unregister(controller);
        }
    }

    /**
     * Adapter 只拥有一次网络交换；可恢复分类交由持久会话决定下一次请求身份与退避。
     */
    private ModelPort.ModelOutcome executeOnce(ModelPort.ModelRequest request,
                                               ModelEventSink eventSink,
                                               RequestController controller) {
        controller.bindThread(Thread.currentThread());
        controller.throwIfStopped();
        try {
            return executeProviderAttempt(request, new StreamContext(eventSink, controller, request), controller);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (ProviderProtocolException failure) {
            ProviderProtocolException classified = terminalFailure(failure);
            ModelPort.RequestDiagnostics diagnostics = request.diagnostics();
            LOGGER.warn("Provider request stopped threadId={} turnId={} modelRequestId={} modelRequestOrdinal={} "
                        + "providerId={} modelId={} api={} provider_failure_code={} terminal_error_code={}",
                    diagnostics == null ? null : diagnostics.threadId(),
                    diagnostics == null ? null : diagnostics.turnId(),
                    diagnostics == null ? null : diagnostics.modelRequestId(),
                    diagnostics == null ? null : diagnostics.modelRequestOrdinal(),
                    request.configuration().providerId(), request.configuration().modelId(),
                    request.configuration().api(), failure.code(), classified.terminalErrorCode());
            throw classified;
        }
    }

    /**
     * 各 Provider 只负责一次 wire 映射，取消共用请求 Controller。
     */
    protected abstract ModelPort.ModelOutcome executeProviderAttempt(
            ModelPort.ModelRequest request, StreamContext context, RequestController controller);

    /**
     * 关闭活动请求及隔离传输所有者；共享传输继续由 Factory 持有。
     */
    @Override
    public void close() {
        Set<RequestController> snapshot;
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            snapshot = Set.copyOf(activeControllers);
        }
        snapshot.forEach(RequestController::shutdown);
        if (ownsTransport) {
            transport.close();
        }
    }

    /**
     * 交换层把仍受 owner 管理的 Response 借给有界正文解析器，不允许正文消费者关闭共享 Call。
     */
    @FunctionalInterface
    private interface ResponseBodyConsumer<T> {
        /**
         * 在 Response 生命周期内完成解析；IO 失败由交换层统一映射。
         */
        T consume(Response response) throws IOException;
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
     * OkHttp 会把回调异常交给 onFailure；在同一线程保存首个失败并显式回传，避免伪造成功。
     */
    private static final class ProviderEventListener extends EventSourceListener {
        private final RequestController controller;
        private final BiPredicate<String, String> consumer;
        private Throwable failure;
        private boolean ended;

        /**
         * Listener 只借用当前请求控制器，不拥有第二个 Call 或取消状态机。
         */
        private ProviderEventListener(RequestController controller, BiPredicate<String, String> consumer) {
            this.controller = controller;
            this.consumer = consumer;
        }

        /**
         * 只有归约与 sink 提交均成功后才停止解析，避免丢掉终态效果或继续等待服务端关闭。
         */
        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            try {
                controller.throwIfStopped();
                if (consumer.test(type, data)) {
                    ended = true;
                    eventSource.cancel();
                }
            } catch (RuntimeException rejected) {
                if (failure == null) {
                    failure = rejected;
                }
                throw rejected;
            }
        }

        /**
         * 仅忽略主动结束通知；OkHttp 对缺少行结尾的 data 报参数异常，归为可恢复解析截断。
         * 回调失败已在 onEvent 保存，不能因异常类型相同而覆盖业务错误。
         */
        @Override
        public void onFailure(EventSource eventSource, Throwable thrown, Response response) {
            if (ended && thrown instanceof IOException && thrown.getCause() == null
                && "canceled".equals(thrown.getMessage())) {
                    return;
                }
            if (failure == null) {
                if (thrown instanceof IllegalArgumentException
                    && "byteCount < 0: -1".equals(thrown.getMessage())) {
                    failure = new ProviderProtocolException("STREAM_TRUNCATED",
                            "provider SSE ended inside an event", true, thrown);
                } else {
                    failure = thrown == null ? new IOException("provider SSE exchange failed") : thrown;
                }
            }
        }

        /**
         * 取消与 Deadline 优先；其余失败保留原类别，IO 仍交给共享 HTTP 边界分类。
         */
        private void throwIfFailed() throws IOException {
            controller.throwIfStopped();
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw new IOException("provider SSE exchange failed", failure);
            }
        }
    }

}
