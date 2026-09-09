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
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import okhttp3.Request;

import java.util.Objects;

/** 与 Responses 平级实现 OpenAI Chat Completions 流式协议的 Provider Adapter。 */
public final class OpenAiChatCompletionsAdapter extends AbstractStreamingModelAdapter {
    /** 接纳传输请求前固定 Chat Completions API。 */
    public OpenAiChatCompletionsAdapter(ModelPort.ModelConfiguration configuration) {
        super(requireChat(configuration));
    }

    /** 借用组合根共享传输，禁止该 Adapter 创建第二个连接池。 */
    public OpenAiChatCompletionsAdapter(
            ModelPort.ModelConfiguration configuration, ModelTransport transport) {
        super(requireChat(configuration), transport);
    }

    /** 在任何外部资源创建前拒绝非 Chat API；供应商名称不参与协议路由。 */
    private static ModelPort.ModelConfiguration requireChat(
            ModelPort.ModelConfiguration configuration) {
        if (configuration == null || configuration.api() != ModelPort.Api.OPENAI_CHAT_COMPLETIONS) {
            throw new IllegalArgumentException("OpenAI Chat Completions configuration is required");
        }
        return configuration;
    }

    /** Chat 当前不声明原生附件能力，附件由既有 read_attachment Tool 路径处理。 */
    public static ModelPort.NativeAttachmentSupport nativeAttachmentSupport() {
        return ModelPort.NativeAttachmentSupport.none();
    }

    /** 对完整冻结 Chat envelope 做纯本地保守估算；不会创建 OkHttp Call。 */
    @Override
    public ModelPort.InputTokenEstimate estimateInputTokens(
            ModelPort.ModelRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancellationRequested();
        return withFrozenEnvelope(request, () -> OpenAiChatCompletionsCodec.encodeRequest(request),
                frozen -> new ModelPort.InputTokenEstimate(
                        frozen.inputTokenEstimate(), frozen.fingerprint()));
    }

    /** 执行 Chat 原生流，并复用 Ja 的统一重试、取消和语义提交门禁。 */
    @Override
    protected ModelPort.ModelOutcome executeProviderAttempt(
            ModelPort.ModelRequest request, StreamContext context, RequestController controller) {
        return withFrozenEnvelope(request, () -> OpenAiChatCompletionsCodec.encodeRequest(request),
                frozen -> executeEncoded(frozen, context, controller));
    }

    /** 通过唯一共享传输路径执行预编码的普通或 Summary Chat 请求。 */
    public ModelPort.ModelOutcome executeEncoded(
            ProviderRequestEnvelope envelope, StreamContext context, RequestController controller) {
        OpenAiChatCompletionsState state = new OpenAiChatCompletionsState();
        Request request = OpenAiProviderSupport.ssePost(
                configuration(), envelope.sendBody(), "/v1/chat/completions");
        executeChatSse(httpClient(), request, controller, OpenAiProviderSupport::serviceFailure,
                event -> state.reduce(event, context::tool).forEach(context::emit));
        return state.finish().emitTo(context);
    }

    /** 供 Summary Adapter 复用 Chat 原生 generation 字段映射。 */
    public static void applyGeneration(ObjectNode root, ModelPort.GenerationOptions options) {
        OpenAiChatCompletionsCodec.applyGeneration(root, options);
    }
}
