// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ModelTransport;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderRequestEnvelope;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.RequestController;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.StreamContext;
import okhttp3.Request;

import java.util.Objects;

/**
 * 与 Responses 平级实现 OpenAI Chat Completions 流式协议的 Provider Adapter。
 */
public final class OpenAiChatCompletionsAdapter extends AbstractStreamingModelAdapter {
    /**
     * 接纳传输请求前固定 Chat Completions API。
     */
    public OpenAiChatCompletionsAdapter(ModelPort.ModelConfiguration configuration) {
        super(requireChat(configuration));
    }

    /**
     * 借用组合根共享传输，禁止该 Adapter 创建第二个连接池。
     */
    public OpenAiChatCompletionsAdapter(
            ModelPort.ModelConfiguration configuration, ModelTransport transport) {
        super(requireChat(configuration), transport);
    }

    /**
     * 在任何外部资源创建前拒绝非 Chat API；供应商名称不参与协议路由。
     */
    private static ModelPort.ModelConfiguration requireChat(
            ModelPort.ModelConfiguration configuration) {
        if (configuration == null || configuration.api() != ModelPort.Api.OPENAI_CHAT_COMPLETIONS) {
            throw new IllegalArgumentException("OpenAI Chat Completions configuration is required");
        }
        return configuration;
    }

    /**
     * Chat 当前不声明原生附件能力，附件由既有 read_attachment Tool 路径处理。
     */
    public static ModelPort.NativeAttachmentSupport nativeAttachmentSupport() {
        return ModelPort.NativeAttachmentSupport.none();
    }

    /**
     * 供 Summary Adapter 复用 Chat 原生 generation 字段映射。
     */
    public static void applyGeneration(ObjectNode root, ModelPort.GenerationOptions options) {
        OpenAiChatCompletionsCodec.applyGeneration(root, options);
    }

    /**
     * 对完整冻结 Chat envelope 计算展示基础值与发送上界；不会创建 OkHttp Call。
     */
    @Override
    public ModelPort.InputTokenEstimate estimateInputTokens(
            ModelPort.ModelRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancellationRequested();
        return withFrozenEnvelope(request, () -> OpenAiChatCompletionsCodec.encodeRequest(request),
                frozen -> new ModelPort.InputTokenEstimate(
                        frozen.displayInputTokenEstimate(), frozen.inputTokenEstimate(), frozen.fingerprint()));
    }

    /**
     * 执行 Chat 原生流，并复用 Ja 的统一重试、取消和语义提交门禁。
     */
    @Override
    protected ModelPort.ModelOutcome executeProviderAttempt(
            ModelPort.ModelRequest request, StreamContext context, RequestController controller) {
        return withFrozenEnvelope(request, () -> OpenAiChatCompletionsCodec.encodeRequest(request),
                frozen -> executeEncoded(frozen, context, controller));
    }

    /**
     * 复用 OkHttp SSE；finish_reason 后继续收取 Usage，只在 [DONE] 或 EOF 后进行业务结算。
     */
    public ModelPort.ModelOutcome executeEncoded(
            ProviderRequestEnvelope envelope, StreamContext context, RequestController controller) {
        OpenAiChatCompletionsState state = new OpenAiChatCompletionsState(configuration());
        Request request = OpenAiProviderSupport.ssePost(
                configuration(), envelope.requestBody(), "/v1/chat/completions");
        executeSse(httpClient(), request, controller, OpenAiProviderSupport::serviceFailure,
                (name, data) -> {
                    if ("[DONE]".equals(data)) {
                        return true;
                    }
                    state.reduce(providerObject(data, "OPENAI_CHAT_EVENT"), context::tool).forEach(context::emit);
                    return false;
                });
        return state.finish().emitTo(context);
    }
}
