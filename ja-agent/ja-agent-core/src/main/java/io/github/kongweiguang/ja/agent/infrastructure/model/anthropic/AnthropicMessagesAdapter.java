// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ModelTransport;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderRequestEnvelope;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.RequestController;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.RetryAfter;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.StreamContext;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.Set;

/**
 * 基于组合根所持 OkHttp 传输的 Anthropic Messages Adapter。
 */
public final class AnthropicMessagesAdapter extends AbstractStreamingModelAdapter {
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "message_start", "message_delta", "message_stop",
            "content_block_start", "content_block_delta", "content_block_stop",
            "ping", "error");

    /**
     * 接纳传输请求前固定 Messages Provider/Model 配置。
     */
    public AnthropicMessagesAdapter(ModelPort.ModelConfiguration configuration) {
        super(requireAnthropic(configuration));
    }

    /**
     * 借用组合传输，使 Factory shutdown 始终是共享池唯一所有者。
     */
    public AnthropicMessagesAdapter(ModelPort.ModelConfiguration configuration, ModelTransport transport) {
        super(requireAnthropic(configuration), transport);
    }

    /**
     * 发布当前 Messages Codec 严格实现的 Base64 image/document 媒体闭集。
     */
    public static ModelPort.NativeAttachmentSupport nativeAttachmentSupport() {
        return AnthropicMessagesCodec.nativeAttachmentSupport();
    }

    /**
     * 在 Adapter 边界拒绝 Provider 别名和旧 API。
     */
    private static ModelPort.ModelConfiguration requireAnthropic(
            ModelPort.ModelConfiguration configuration) {
        if (configuration == null || configuration.api() != ModelPort.Api.ANTHROPIC_MESSAGES) {
            throw new IllegalArgumentException("Anthropic Messages configuration is required");
        }
        return configuration;
    }

    /**
     * 映射 HTTP 状态，但绝不依据自由文本判断 Anthropic 上下文溢出。
     */
    private static RuntimeException serviceFailure(int status, Headers headers, JsonNode error) {
        boolean retryable = status == 408 || status == 409 || status == 429
                            || status >= 500 && status <= 599;
        return new ProviderProtocolException(
                "HTTP_STATUS", serviceFailureDetail(status, error), retryable,
                RetryAfter.parse(headers.get("Retry-After")),
                retryable ? "MODEL_UNAVAILABLE" : "MODEL_UPSTREAM_REJECTED");
    }

    /**
     * 对冻结 Messages envelope 计算展示基础值与发送上界；完成后释放缓存，正式发送按同一纯 Codec 重建。
     */
    @Override
    public ModelPort.InputTokenEstimate estimateInputTokens(
            ModelPort.ModelRequest request,
            io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellationToken) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancellationRequested();
        return withFrozenEnvelope(request, () -> AnthropicMessagesCodec.encodeRequest(request),
                frozen -> new ModelPort.InputTokenEstimate(
                        frozen.displayInputTokenEstimate(), frozen.inputTokenEstimate(), frozen.fingerprint()));
    }

    /**
     * 执行严格 Messages 流，同时由 Ja 统一掌握重试、Deadline 和取消。
     */
    @Override
    protected ModelPort.ModelOutcome executeProviderAttempt(
            ModelPort.ModelRequest request, StreamContext context, RequestController controller) {
        return withFrozenEnvelope(request, () -> AnthropicMessagesCodec.encodeRequest(request),
                frozen -> executeEncoded(frozen, context, controller));
    }

    /**
     * 使用 Summary 已冻结的 envelope 发送，保持预算指纹与发送正文一致。
     */
    public ModelPort.ModelOutcome executeEncoded(
            ProviderRequestEnvelope envelope, StreamContext context, RequestController controller) {
        return executeEncoded(envelope.requestBody(), context, controller);
    }

    /**
     * 用统一 OkHttp SSE 入口归约冻结请求，合法 message_stop 后立即释放网络响应。
     */
    private ModelPort.ModelOutcome executeEncoded(
            RequestBody encoded, StreamContext context, RequestController controller) {
        AnthropicMessagesState state = new AnthropicMessagesState(configuration());
        Request request = request(encoded);
        executeSse(httpClient(), request, controller,
                AnthropicMessagesAdapter::serviceFailure,
                (name, data) -> {
                    JsonNode event = namedEvent(name, data, ALLOWED_EVENTS, "ANTHROPIC_EVENT");
                    if (event == null) {
                        return false;
                    }
                    state.reduce(name, event).forEach(context::emit);
                    return state.isTerminal();
                });
        return state.finish().emitTo(context);
    }

    /**
     * 使用固定 Anthropic 版本构造受限 POST，loopback 不发送占位 Key。
     */
    private Request request(RequestBody body) {
        Request.Builder builder = new Request.Builder()
                .url(endpoint(configuration().baseUri(), "/v1/messages"))
                .header("Accept", "text/event-stream")
                .header("anthropic-version", "2023-06-01")
                .post(body);
        builder.header("x-api-key", configuration().apiKey());
        return builder.build();
    }
}
