// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ModelTransport;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderRequestEnvelope;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.RequestController;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.StreamContext;
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
     * 发布当前 Responses Codec 已实现的图片/PDF闭集；50,000,000 字节总量匹配官方 file-input request 上限。
     */
    public static ModelPort.NativeAttachmentSupport nativeAttachmentSupport() {
        return OpenAiResponsesCodec.nativeAttachmentSupport();
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
     * 复用 Responses 原生生成字段映射，避免 Summary 复制或放宽 Provider 约束。
     */
    public static void applyGeneration(ObjectNode root, ModelPort.GenerationOptions generation) {
        OpenAiResponsesCodec.applyGeneration(root, generation);
    }

    /**
     * 对冻结 Responses envelope 计算展示基础值与发送上界，reasoning/cache 子项不会重复相加。
     */
    @Override
    public ModelPort.InputTokenEstimate estimateInputTokens(
            ModelPort.ModelRequest request,
            io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellationToken) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancellationRequested();
        return withFrozenEnvelope(request, () -> OpenAiResponsesCodec.encodeRequest(request),
                frozen -> new ModelPort.InputTokenEstimate(
                        frozen.displayInputTokenEstimate(), frozen.inputTokenEstimate(), frozen.fingerprint()));
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

    /**
     * 使用 Summary 已冻结的 envelope 发送，保持预算指纹与发送正文一致。
     */
    public ModelPort.ModelOutcome executeEncoded(
            ProviderRequestEnvelope envelope, StreamContext context, RequestController controller) {
        return executeEncoded(envelope.requestBody(), context, controller);
    }

    /**
     * 统一通过 OkHttp SSE 归约冻结正文，终态效果提交成功后结束读取以免等待额外 EOF。
     */
    private ModelPort.ModelOutcome executeEncoded(
            okhttp3.RequestBody encoded, StreamContext context, RequestController controller) {
        OpenAiResponsesState state = new OpenAiResponsesState(configuration());
        Request request = request(encoded);
        executeSse(httpClient(), request, controller,
                OpenAiProviderSupport::serviceFailure,
                (name, data) -> {
                    JsonNode event = namedEvent(name, data, ALLOWED_EVENTS, "OPENAI_EVENT");
                    if (event == null) {
                        return false;
                    }
                    state.reduce(name, event, context::tool).forEach(context::emit);
                    return state.isTerminal();
                });
        return state.finish().emitTo(context);
    }

    /**
     * 构造受限 POST，未配置 Key 的 loopback 不发送占位凭据。
     */
    private Request request(okhttp3.RequestBody encoded) {
        return OpenAiProviderSupport.ssePost(configuration(), encoded, "/v1/responses");
    }
}
