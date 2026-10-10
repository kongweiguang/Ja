// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.NativeToolResultContent;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderJsonValues;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 不承担传输、重试或生命周期职责的 Anthropic Messages 请求 Codec。
 */
final class AnthropicMessagesCodec {
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 16_384;
    private static final long MAX_NATIVE_ATTACHMENT_BYTES = 32_000_000L;

    /**
     * 禁止创建有状态 Codec，确保每次 Ja 重试都绑定同一不可变请求快照。
     */
    private AnthropicMessagesCodec() {
    }

    /**
     * Messages 当前 SDK 的 Base64 image/PDF source 类型是唯一原生闭集；32 MB 同时收紧单项与请求总量。
     */
    static ModelPort.NativeAttachmentSupport nativeAttachmentSupport() {
        return new ModelPort.NativeAttachmentSupport(List.of(
                new ModelPort.NativeAttachmentRule(
                        io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent.Kind.IMAGE,
                        Set.of("image/jpeg", "image/png", "image/gif", "image/webp"),
                        MAX_NATIVE_ATTACHMENT_BYTES),
                new ModelPort.NativeAttachmentRule(
                        io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent.Kind.PDF,
                        Set.of("application/pdf"), MAX_NATIVE_ATTACHMENT_BYTES)),
                MAX_NATIVE_ATTACHMENT_BYTES, true);
    }

    /**
     * 将稳定 Ja 请求映射为冻结的 Messages wire contract；空系统提示省略 system，保持纯用户消息语义。
     */
    static ObjectNode encodeRequest(ModelPort.ModelRequest request) {
        ModelPort.GenerationOptions generation = request.configuration().generation();
        if (generation.temperature() != null || generation.topP() != null) {
            throw new ProviderProtocolException(
                    "GENERATION_OPTIONS",
                    "Anthropic sampling controls are unsupported by the current Messages API", false);
        }
        ObjectNode root = AbstractStreamingModelAdapter.JSON.createObjectNode();
        root.put("model", request.configuration().model());
        root.put("max_tokens", generation.maxOutputTokens() == null
                ? DEFAULT_MAX_OUTPUT_TOKENS : generation.maxOutputTokens());
        if (!request.prompt().systemPrompt().isEmpty()) {
            root.put("system", request.prompt().systemPrompt());
        }
        root.set("messages", messages(request.messages(), request.configuration()));
        applyThinking(root, generation.reasoningLevel());
        request.outputSchema().ifPresent(schema -> {
            ObjectNode format = AbstractStreamingModelAdapter.JSON.createObjectNode();
            format.put("type", "json_schema");
            format.set("schema", ProviderJsonValues.toNode(schema));
            root.withObject("output_config").set("format", format);
        });
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            request.tools().forEach(tool -> tools.add(tool(tool)));
            root.putObject("tool_choice").put("type", "auto");
        }
        root.put("stream", true);
        return root;
    }

    /**
     * 将 Ja 的 reasoning 档位映射为 Anthropic 当前 Messages 思考配置；仅发送 effort
     * 不会开启 thinking，且 disabled 必须与 output_config 互斥，否则 Provider 会拒绝请求。
     */
    private static void applyThinking(ObjectNode root, String reasoningLevel) {
        if (reasoningLevel == null) {
            return;
        }
        String normalized = reasoningLevel.trim();
        if (Set.of("off", "none", "disabled").contains(normalized.toLowerCase(Locale.ROOT))) {
            root.putObject("thinking").put("type", "disabled");
            return;
        }
        Integer budgetTokens = budgetTokens(normalized);
        if (budgetTokens != null) {
            int maxTokens = root.path("max_tokens").intValue();
            if (budgetTokens < 1_024 || budgetTokens >= maxTokens) {
                throw new ProviderProtocolException(
                        "GENERATION_OPTIONS",
                        "Anthropic thinking budget must be between 1024 and max_tokens", false);
            }
            root.putObject("thinking")
                    .put("type", "enabled")
                    .put("budget_tokens", budgetTokens)
                    .put("display", "summarized");
            return;
        }
        root.putObject("thinking")
                .put("type", "adaptive")
                .put("display", "summarized");
        root.putObject("output_config").put("effort", reasoningLevel);
    }

    /**
     * 将当前仍支持手动预算的 Anthropic 模型配置解释为原生十进制 token 数；不接受 Ja 私有前缀别名。
     */
    private static Integer budgetTokens(String reasoningLevel) {
        if (reasoningLevel.isEmpty()
                || reasoningLevel.chars().anyMatch(character -> !Character.isDigit(character))) {
            return null;
        }
        try {
            return Integer.valueOf(reasoningLevel);
        } catch (NumberFormatException failure) {
            throw new ProviderProtocolException(
                    "GENERATION_OPTIONS", "Anthropic thinking budget is invalid", false, failure);
        }
    }

    /**
     * 按 Provider-neutral 顺序保留每条消息和内容块，并把同身份原生 reasoning 原样回放。
     *
     * <p>reasoning 是 assistant 内容块的一部分，不再依赖只在当前进程存在的 Continuation；
     * origin 不匹配的 opaque 块被跳过而不是降级成普通文本，避免把签名材料发送给错误端点。</p>
     */
    private static ArrayNode messages(List<ModelMessage> messages,
                                      ModelPort.ModelConfiguration configuration) {
        ArrayNode result = AbstractStreamingModelAdapter.JSON.createArrayNode();
        String endpointFingerprint = ReasoningContent.endpointFingerprint(configuration.baseUri());
        String api = configuration.api().name().toLowerCase(Locale.ROOT);
        for (ModelMessage message : messages) {
            String role = message.role() == ModelRole.ASSISTANT ? "assistant" : "user";
            for (ModelContent block : message.content()) {
                ObjectNode encoded = null;
                if (block instanceof TextContent(String text1)) {
                    encoded = AbstractStreamingModelAdapter.JSON.createObjectNode()
                            .put("type", "text").put("text", text1);
                } else if (block instanceof NativeAttachmentContent attachment) {
                    if (message.role() != ModelRole.USER) {
                        throw new ProviderProtocolException(
                                "REQUEST_ENCODING", "Only user messages can contain native attachments", false);
                    }
                    encoded = AbstractStreamingModelAdapter.JSON.createObjectNode();
                    encoded.put("type", attachment.kind() == NativeAttachmentContent.Kind.IMAGE
                            ? "image" : "document");
                    encoded.putObject("source").put("type", "base64")
                            .put("media_type", attachment.mediaType())
                            .put("data", attachment.base64Data());
                } else if (block instanceof ReasoningContent reasoning) {
                    if (message.role() != ModelRole.ASSISTANT) {
                        throw new ProviderProtocolException(
                                "REQUEST_ENCODING", "Only assistant messages can contain reasoning blocks", false);
                    }
                    if (reasoning.matches(configuration.providerId(), configuration.modelId(), api,
                            configuration.model(), endpointFingerprint)) {
                        encoded = decodeReasoning(reasoning);
                    }
                } else if (block instanceof ToolCallContent call) {
                    encoded = AbstractStreamingModelAdapter.JSON.createObjectNode();
                    encoded.put("type", "tool_use");
                    encoded.put("id", call.callId());
                    encoded.put("name", call.name());
                    encoded.set("input", ProviderJsonValues.toNode(call.arguments()));
                } else if (block instanceof ToolResultContent resultContent && resultContent.media().isEmpty()) {
                    encoded = AbstractStreamingModelAdapter.JSON.createObjectNode();
                    encoded.put("type", "tool_result");
                    encoded.put("tool_use_id", resultContent.callId());
                    encoded.put("content", resultContent.content());
                    encoded.put("is_error", resultContent.error());
                } else if (block instanceof NativeToolResultContent resultContent) {
                    encoded = AbstractStreamingModelAdapter.JSON.createObjectNode();
                    encoded.put("type", "tool_result");
                    encoded.put("tool_use_id", resultContent.callId());
                    encoded.put("is_error", resultContent.error());
                    ArrayNode content = encoded.putArray("content");
                    if (!resultContent.content().isEmpty()) {
                        content.addObject().put("type", "text").put("text", resultContent.content());
                    }
                    for (NativeAttachmentContent image : resultContent.images()) {
                        content.addObject().put("type", "image").putObject("source")
                                .put("type", "base64").put("media_type", image.mediaType())
                                .put("data", image.base64Data());
                    }
                }
                if (encoded != null) {
                    appendMessage(result, role).withArray("content").add(encoded);
                }
            }
        }
        return result;
    }

    /**
     * 解析并严格校验已匹配 origin 的原生 reasoning，防止 opaque 状态降级或注入额外字段。
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // 已分类的 ProviderProtocolException 原样重抛，保留固定错误码与受限 cause。
    private static ObjectNode decodeReasoning(ReasoningContent reasoning) {
        try {
            JsonNode parsed = AbstractStreamingModelAdapter.JSON.readTree(reasoning.nativeJson());
            if (!(parsed instanceof ObjectNode object)) {
                throw reasoningEncodingFailure();
            }
            if ("thinking".equals(reasoning.wireField())) {
                if (object.size() != 3 || !"thinking".equals(object.path("type").textValue())
                    || !object.path("thinking").isTextual()
                    || !object.path("signature").isTextual()
                    || object.path("signature").textValue().isEmpty()) {
                    throw reasoningEncodingFailure();
                }
            } else if ("redacted_thinking".equals(reasoning.wireField())) {
                if (object.size() != 2 || !"redacted_thinking".equals(object.path("type").textValue())
                    || !object.path("data").isTextual()
                    || object.path("data").textValue().isEmpty()) {
                    throw reasoningEncodingFailure();
                }
            } else {
                throw reasoningEncodingFailure();
            }
            return object.deepCopy();
        } catch (ProviderProtocolException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw reasoningEncodingFailure(failure);
        }
    }

    /**
     * 生成不包含 opaque 原文的稳定 reasoning 编码错误。
     */
    private static ProviderProtocolException reasoningEncodingFailure() {
        return new ProviderProtocolException(
                "REQUEST_ENCODING", "Anthropic reasoning block is invalid", false);
    }

    /**
     * 保留 reasoning JSON 的解析根因，同时不把 opaque reasoning 内容写入错误消息。
     */
    private static ProviderProtocolException reasoningEncodingFailure(Throwable cause) {
        return new ProviderProtocolException(
                "REQUEST_ENCODING", "Anthropic reasoning block is invalid", false, cause);
    }

    /**
     * 复用末尾相同角色的消息容器，保持原内容块顺序并满足 Anthropic 的角色交替约束。
     */
    private static ObjectNode appendMessage(ArrayNode messages, String role) {
        if (!messages.isEmpty()) {
            JsonNode last = messages.get(messages.size() - 1);
            if (role.equals(last.path("role").textValue()) && last instanceof ObjectNode object) {
                return object;
            }
        }
        return messages.addObject().put("role", role);
    }

    /**
     * 将冻结 JSON Schema 映射为 Anthropic 公开 Tool 字段闭集，避免发送未公开扩展字段造成兼容性漂移。
     */
    private static ObjectNode tool(ToolSpec specification) {
        JsonNode schema = ProviderJsonValues.toNode(specification.inputSchema());
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
            throw new ProviderProtocolException(
                    "TOOL_SCHEMA_INVALID", "Anthropic Tool schema root must be object", false);
        }
        ObjectNode node = AbstractStreamingModelAdapter.JSON.createObjectNode();
        node.put("name", specification.name());
        node.put("description", specification.description());
        node.set("input_schema", schema);
        return node;
    }
}
