// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ModelTransport;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderRequestEnvelope;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.RequestController;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.StreamContext;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import okhttp3.Request;

import java.util.Set;

/**
 * 基于组合根所持 OkHttp 传输的 OpenAI Responses Adapter。
 */
public final class OpenAiResponsesAdapter extends AbstractStreamingModelAdapter {
    private static final Set<String> ALLOWED_EVENTS = Set.of(
            "response.created", "response.queued", "response.in_progress",
            "response.output_text.delta", "response.output_text.done",
            "response.refusal.delta", "response.refusal.done",
            "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
            "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
            "response.reasoning_text.delta", "response.reasoning_text.done",
            "response.content_part.added", "response.content_part.done",
            "response.output_item.added", "response.output_item.done",
            "response.function_call_arguments.delta", "response.function_call_arguments.done",
            "response.completed", "response.incomplete", "response.failed", "error");

    /**
     * 发布当前 Responses Codec 已实现的图片/PDF闭集；50,000,000 字节总量匹配官方 file-input request 上限。
     */
    public static ModelPort.NativeAttachmentSupport nativeAttachmentSupport() {
        return OpenAiResponsesCodec.nativeAttachmentSupport();
    }

    /**
     * 接纳传输请求前固定 Responses Provider/Model 配置。
     */
    public OpenAiResponsesAdapter(ModelPort.ModelConfiguration configuration) {
        super(requireOpenAi(configuration));
    }

    /**
     * 借用组合根所持传输，禁止该 Adapter 创建第二个连接池。
     */
    public OpenAiResponsesAdapter(ModelPort.ModelConfiguration configuration, ModelTransport transport) {
        super(requireOpenAi(configuration), transport);
    }

    /**
     * 创建请求状态或外部资源前拒绝 Provider 别名。
     */
    private static ModelPort.ModelConfiguration requireOpenAi(
            ModelPort.ModelConfiguration configuration) {
        if (configuration == null || configuration.api() != ModelPort.Api.OPENAI_RESPONSES) {
            throw new IllegalArgumentException("OpenAI Responses configuration is required");
        }
        return configuration;
    }

    /**
     * 对冻结 Responses envelope 执行纯本地保守估算，reasoning/cache 子项不会参与预算重复相加。
     */
    @Override
    public ModelPort.InputTokenEstimate estimateInputTokens(
            ModelPort.ModelRequest request,
            io.github.kongweiguang.ja.foundation.concurrent.CancellationToken cancellationToken) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancellationRequested();
        return withFrozenEnvelope(request, () -> OpenAiResponsesCodec.encodeRequest(request),
                frozen -> new ModelPort.InputTokenEstimate(
                        frozen.inputTokenEstimate(), frozen.fingerprint()));
    }

    /**
     * 执行严格 Responses 流，同时由 Ja 统一掌握重试、取消和生命周期。
     */
    @Override
    protected ModelPort.ModelOutcome executeProviderAttempt(
            ModelPort.ModelRequest request, StreamContext context, RequestController controller) {
        return withFrozenEnvelope(request, () -> OpenAiResponsesCodec.encodeRequest(request),
                frozen -> executeEncoded(frozen, context, controller));
    }

    /** 使用 Summary 已冻结的 envelope 发送，保持预算指纹与发送正文一致。 */
    public ModelPort.ModelOutcome executeEncoded(
            ProviderRequestEnvelope envelope, StreamContext context, RequestController controller) {
        return executeEncoded(envelope.sendBody(), context, controller);
    }

    /**
     * 发送已经冻结的 envelope 正文，避免 Summary 以外的主请求再次序列化。
     */
    private ModelPort.ModelOutcome executeEncoded(
            byte[] encoded, StreamContext context, RequestController controller) {
        OpenAiResponsesState state = new OpenAiResponsesState();
        Request request = request(encoded);
        executeSse(httpClient(), request, ALLOWED_EVENTS, "OPENAI_EVENT", controller,
                OpenAiProviderSupport::serviceFailure,
                event -> state.reduce(event, context::tool).forEach(context::emit));
        return state.finish().emitTo(context);
    }

    /** 复用 Responses 原生生成字段映射，避免 Summary 复制或放宽 Provider 约束。 */
    public static void applyGeneration(ObjectNode root, ModelPort.GenerationOptions generation) {
        OpenAiResponsesCodec.applyGeneration(root, generation);
    }

    /**
     * 构造受限 POST，未配置 Key 的 loopback 不发送占位凭据。
     */
    private Request request(byte[] encoded) {
        return OpenAiProviderSupport.ssePost(configuration(), encoded, "/v1/responses");
    }
}
