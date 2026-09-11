// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ModelTransport;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.RequestController;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderRequestEnvelope;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.RetryAfter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.StreamContext;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.Set;

/**
 * 基于组合根所持 OkHttp 传输的 Anthropic Messages Adapter。
 */
public final class AnthropicMessagesAdapter extends AbstractStreamingModelAdapter {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "message_start", "message_delta", "message_stop",
            "content_block_start", "content_block_delta", "content_block_stop",
            "ping", "error");

    /** 发布当前 Messages Codec 严格实现的 Base64 image/document 媒体闭集。 */
    public static ModelPort.NativeAttachmentSupport nativeAttachmentSupport() {
        return AnthropicMessagesCodec.nativeAttachmentSupport();
    }

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
     * 对冻结 Messages envelope 执行纯本地保守估算；完成后释放缓存，正式发送会按同一纯 Codec 重建。
     */
    @Override
    public ModelPort.InputTokenEstimate estimateInputTokens(
            ModelPort.ModelRequest request,
            io.github.kongweiguang.ja.foundation.concurrent.CancellationToken cancellationToken) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancellationRequested();
        return withFrozenEnvelope(request, () -> AnthropicMessagesCodec.encodeRequest(request),
                frozen -> new ModelPort.InputTokenEstimate(
                        frozen.inputTokenEstimate(), frozen.fingerprint()));
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

    /** 使用 Summary 已冻结的 envelope 发送，保持预算指纹与发送正文一致。 */
    public ModelPort.ModelOutcome executeEncoded(
            ProviderRequestEnvelope envelope, StreamContext context, RequestController controller) {
        return executeEncoded(envelope.sendBody(), context, controller);
    }

    /** 发送冻结正文，主模型请求不再重复执行 JSON 序列化。 */
    private ModelPort.ModelOutcome executeEncoded(
            byte[] encoded, StreamContext context, RequestController controller) {
        AnthropicMessagesState state = new AnthropicMessagesState(configuration());
        Request request = request(encoded);
        executeSse(httpClient(), request, ALLOWED_EVENTS, "ANTHROPIC_EVENT", controller,
                AnthropicMessagesAdapter::serviceFailure,
                event -> state.reduce(event).forEach(context::emit));
        return state.finish().emitTo(context);
    }

    /**
     * 使用固定 Anthropic 版本构造受限 POST，loopback 不发送占位 Key。
     */
    private Request request(byte[] body) {
        Request.Builder builder = new Request.Builder()
                .url(endpoint(configuration().baseUri(), "/v1/messages"))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .post(RequestBody.create(body, JSON_MEDIA_TYPE));
        builder.header("x-api-key", configuration().apiKey());
        return builder.build();
    }

    /**
     * 映射 HTTP 状态，但绝不依据自由文本判断 Anthropic 上下文溢出。
     */
    private static RuntimeException serviceFailure(int status, Headers headers, JsonNode error) {
        boolean retryable = status == 429 || status >= 500 && status <= 599;
        return new ProviderProtocolException(
                "HTTP_STATUS", serviceFailureDetail(status, error), retryable,
                RetryAfter.parse(headers.get("Retry-After")));
    }
}
