// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
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
        if (configuration == null || configuration.provider() != ModelPort.Provider.OPENAI
            || configuration.api() != ModelPort.Api.OPENAI_RESPONSES) {
            throw new IllegalArgumentException("OpenAI Responses configuration is required");
        }
        return configuration;
    }

    /**
     * 执行严格 Responses 流，同时由 Ja 统一掌握重试、取消和生命周期。
     */
    @Override
    protected ModelPort.ModelOutcome executeProviderAttempt(
            ModelPort.ModelRequest request, StreamContext context, RequestController controller) {
        ProviderRequestEnvelope envelope = envelope(request, () -> OpenAiResponsesCodec.encodeRequest(request));
        try {
            return executeEncoded(envelope.sendBody(), context, controller);
        } finally {
            releaseEnvelope(request);
        }
    }

    /**
     * 调用 Responses 官方 input_tokens 端点，并把权威计量与冻结 envelope 指纹绑定。
     */
    @Override
    protected ModelPort.InputTokenCount executeProviderTokenCountAttempt(
            ModelPort.ModelRequest request, RequestController controller) {
        ProviderRequestEnvelope envelope = envelope(request, () -> OpenAiResponsesCodec.encodeRequest(request));
        try {
            return completeTokenCount(request, envelope,
                    () -> executeJson(httpClient(),
                            OpenAiProviderSupport.tokenCountPost(configuration(), envelope.countBody()),
                            controller, OpenAiProviderSupport::tokenCountFailure), "OpenAI");
        } catch (ProviderProtocolException failure) {
            if (!"TOKEN_COUNT_UNSUPPORTED".equals(failure.code())) throw failure;
            ProviderRequestEnvelope fallback = envelope(request,
                    () -> OpenAiResponsesCodec.encodeRequest(withoutContinuation(request)));
            return new ModelPort.InputTokenCount(
                    fallback.conservativeInputTokenUpperBound(), fallback.fingerprint());
        }
    }

    /**
     * 通过唯一共享传输路径执行预编码的普通请求或 Summary 请求。
     */
    public ModelPort.ModelOutcome executeEncoded(
            ObjectNode encoded, StreamContext context, RequestController controller) {
        return executeEncoded(serializeRequest(encoded), context, controller);
    }

    /** 使用 Summary 已冻结的 envelope 发送，确保正式请求与先前官方计量共享同一 Token 正文。 */
    public ModelPort.ModelOutcome executeEncoded(
            ProviderRequestEnvelope envelope, StreamContext context, RequestController controller) {
        return executeEncoded(envelope.sendBody(), context, controller);
    }

    /** 对 Summary 已冻结的 envelope 调用官方计量端点，不经过普通 Agent Codec 二次映射。 */
    public ModelPort.InputTokenCount countEncoded(
            ProviderRequestEnvelope envelope, RequestController controller) {
        final com.fasterxml.jackson.databind.JsonNode response;
        try {
            response = executeJson(
                    httpClient(), OpenAiProviderSupport.tokenCountPost(configuration(), envelope.countBody()),
                    controller, OpenAiProviderSupport::tokenCountFailure);
        } catch (ProviderProtocolException failure) {
            if (!"TOKEN_COUNT_UNSUPPORTED".equals(failure.code())) throw failure;
            return new ModelPort.InputTokenCount(
                    envelope.conservativeInputTokenUpperBound(), envelope.fingerprint());
        }
        com.fasterxml.jackson.databind.JsonNode value = response.get("input_tokens");
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw new io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException(
                    "TOKEN_COUNT_RESPONSE", "OpenAI token count response is invalid", false);
        }
        return new ModelPort.InputTokenCount(value.longValue(), envelope.fingerprint());
    }

    /**
     * 在代理缺少官方计量能力时移除不透明续接，确保保守字节上界覆盖实际发送的全部历史，
     * 而不是只覆盖 previous_response_id 之后的 Tool 结果。
     */
    private static ModelPort.ModelRequest withoutContinuation(ModelPort.ModelRequest request) {
        return new ModelPort.ModelRequest(
                request.configuration(), request.prompt(), request.messages(), request.tools(), null,
                request.round(), request.retryPolicy());
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
