// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
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
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderReasoningSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 只负责 OpenAI Responses 请求映射的 Codec，不承担网络、重试或生命周期职责。
 */
final class OpenAiResponsesCodec {
    private static final long MAX_NATIVE_ATTACHMENT_BYTES = 50_000_000L;

    /**
     * 禁止创建有状态 Codec，使每次 Ja 重试都绑定同一份不可变请求快照。
     */
    private OpenAiResponsesCodec() {
    }

    /**
     * Responses 官方输入块支持 Base64 data URL 图片和文件；媒体闭集与总量在读取内容前发布。
     */
    static ModelPort.NativeAttachmentSupport nativeAttachmentSupport() {
        return new ModelPort.NativeAttachmentSupport(List.of(
                new ModelPort.NativeAttachmentRule(
                        io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent.Kind.IMAGE,
                        Set.of("image/png", "image/jpeg", "image/gif", "image/webp"),
                        MAX_NATIVE_ATTACHMENT_BYTES),
                new ModelPort.NativeAttachmentRule(
                        io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent.Kind.PDF,
                        Set.of("application/pdf"), MAX_NATIVE_ATTACHMENT_BYTES)),
                MAX_NATIVE_ATTACHMENT_BYTES, true);
    }

    /**
     * 将稳定 Ja 请求映射为冻结的 Responses wire contract；每轮携带完整原生条目历史，避免把
     * Tool 续传正确性交给网关侧 response 存储和 call-id 关联状态。每次请求关闭远端存储并显式
     * 请求 encrypted reasoning，空系统提示省略 instructions；这样即使本轮未显式配置 reasoning，
     * 上游模型默认生成的原生 item 仍可安全进入下一轮完整历史。
     */
    static ObjectNode encodeRequest(ModelPort.ModelRequest request) {
        ObjectNode root = AbstractStreamingModelAdapter.JSON.createObjectNode();
        root.put("model", request.configuration().model());
        root.put("store", false);
        root.putArray("include").add("reasoning.encrypted_content");
        if (!request.prompt().systemPrompt().isEmpty()) {
            root.put("instructions", request.prompt().systemPrompt());
        }
        ModelPort.Continuation continuation = request.continuation();
        if (continuation != null) {
            throw new ProviderProtocolException(
                    "REMOTE_CONTINUATION_UNSUPPORTED",
                    "OpenAI Responses requests must carry complete native input history", false);
        }
        root.set("input", input(request.configuration(), request.messages()));
        applyGeneration(root, request.configuration().generation());
        request.outputSchema().ifPresent(schema -> {
            ObjectNode normalized = strictOutputSchema(schema);
            ObjectNode format = root.putObject("text").putObject("format");
            format.put("type", "json_schema").put("name", "ja_output").put("strict", true);
            format.set("schema", normalized);
        });
        OpenAiProviderSupport.applyToolsAndStreaming(root, request.tools(),
                OpenAiResponsesCodec::tool);
        return root;
    }

    /**
     * 通过共享归一化器满足 OpenAI strict Schema 约束，并将不兼容输入映射为稳定错误码。
     */
    private static ObjectNode strictOutputSchema(JsonObject schema) {
        try {
            return OpenAiStrictSchemaNormalizer.normalizeRoot(ProviderJsonValues.toNode(schema));
        } catch (IllegalArgumentException failure) {
            throw new ProviderProtocolException(
                    "OUTPUT_SCHEMA_INVALID", "OpenAI output schema is not strict-compatible", false, failure);
        }
    }

    /**
     * 在文本、函数调用和函数结果条目之间保持消息顺序；失败结果只编码真实错误正文，避免发送 Responses
     * 仅用于输出条目的 status 元数据而触发输入校验拒绝。
     */
    private static ArrayNode input(
            ModelPort.ModelConfiguration configuration, List<ModelMessage> messages) {
        ArrayNode result = AbstractStreamingModelAdapter.JSON.createArrayNode();
        for (ModelMessage message : messages) {
            List<ModelContent> pending = new ArrayList<>();
            for (ModelContent block : message.content()) {
                if (block instanceof TextContent || block instanceof NativeAttachmentContent) {
                    pending.add(block);
                } else {
                    if (block instanceof ReasoningContent reasoning && !matches(reasoning, configuration)) {
                        continue;
                    }
                    flushContent(result, message.role(), pending);
                    if (block instanceof ReasoningContent reasoning) {
                        result.add(nativeReasoningItem(reasoning));
                    } else if (block instanceof ToolCallContent call) {
                        ObjectNode item = result.addObject();
                        item.put("type", "function_call");
                        item.put("call_id", call.callId());
                        item.put("name", call.name());
                        item.put("arguments", writeArguments(call.arguments()));
                    } else if (block instanceof ToolResultContent output && output.media().isEmpty()) {
                        ObjectNode item = result.addObject();
                        item.put("type", "function_call_output");
                        item.put("call_id", output.callId());
                        item.put("output", output.content());
                    } else if (block instanceof NativeToolResultContent output) {
                        ObjectNode item = result.addObject();
                        item.put("type", "function_call_output");
                        item.put("call_id", output.callId());
                        ArrayNode content = item.putArray("output");
                        if (!output.content().isEmpty()) {
                            content.addObject().put("type", "input_text").put("text", output.content());
                        }
                        for (NativeAttachmentContent image : output.images()) {
                            content.addObject().put("type", "input_image")
                                    .put("image_url", "data:" + image.mediaType() + ";base64," + image.base64Data());
                        }
                    }
                }
            }
            flushContent(result, message.role(), pending);
        }
        return result;
    }

    /**
     * 只回传与当前 Responses 身份完全匹配的 opaque 块，避免端点、模型或配置切换后重放签名材料。
     */
    private static boolean matches(
            ReasoningContent reasoning, ModelPort.ModelConfiguration configuration) {
        return "reasoning".equals(reasoning.wireField())
               && reasoning.matches(configuration.providerId(), configuration.modelId(),
                ProviderReasoningSupport.canonicalApi(configuration), configuration.model(),
                ReasoningContent.endpointFingerprint(configuration.baseUri()));
    }

    /**
     * 将完整 Responses reasoning item 原样放回 input；解析失败时拒绝请求而不发送残缺 opaque 状态。
     */
    private static JsonNode nativeReasoningItem(ReasoningContent reasoning) {
        try {
            JsonNode item = AbstractStreamingModelAdapter.JSON.readTree(reasoning.nativeJson());
            if (item == null || !item.isObject() || !"reasoning".equals(item.path("type").textValue())) {
                throw requestEncoding();
            }
            return item.deepCopy();
        } catch (ProviderProtocolException failure) {
            throw failure;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException failure) {
            throw requestEncoding(failure);
        }
    }

    /**
     * assistant 历史使用 EasyInput，因为强类型 input-message 角色不接受 assistant；原生附件只允许归属用户消息。
     */
    private static void flushContent(ArrayNode result, ModelRole role, List<ModelContent> pending) {
        if (pending.isEmpty()) {
            return;
        }
        if (role == ModelRole.ASSISTANT) {
            ObjectNode item = result.addObject();
            item.put("role", "assistant");
            StringBuilder text = new StringBuilder();
            for (ModelContent block : pending) {
                if (!(block instanceof TextContent(String text1))) {
                    throw new ProviderProtocolException(
                            "REQUEST_ENCODING", "Assistant history cannot contain native attachments", false);
                }
                text.append(text1);
            }
            item.put("content", text.toString());
        } else if (role == ModelRole.USER) {
            addUserContent(result, pending);
        } else {
            throw new ProviderProtocolException(
                    "REQUEST_ENCODING", "Tool history cannot contain ordinary text", false);
        }
        pending.clear();
    }

    /**
     * 使用独立 user input-message 按原顺序承载文本与原生附件，不把动态 System 或 Tool Schema 混入用户问题。
     */
    private static void addUserContent(ArrayNode result, List<ModelContent> blocks) {
        ObjectNode item = result.addObject();
        item.put("role", "user");
        ArrayNode content = item.putArray("content");
        for (ModelContent block : blocks) {
            if (block instanceof TextContent(String text1)) {
                content.addObject().put("type", "input_text").put("text", text1);
            } else if (block instanceof NativeAttachmentContent attachment) {
                if (attachment.kind() == NativeAttachmentContent.Kind.IMAGE) {
                    content.addObject().put("type", "input_image")
                            .put("image_url", dataUrl(attachment)).put("detail", "auto");
                } else {
                    content.addObject().put("type", "input_file")
                            .put("filename", attachment.displayName()).put("file_data", dataUrl(attachment));
                }
            }
        }
    }

    /**
     * Responses 的 Base64 输入字段要求完整 data URL；媒体类型已在 capability 门中精确匹配。
     */
    private static String dataUrl(NativeAttachmentContent attachment) {
        return "data:" + attachment.mediaType() + ";base64," + attachment.base64Data();
    }

    /**
     * 将一个冻结 Tool Schema 转换为 Responses function-tool 对象。
     */
    private static ObjectNode tool(ToolSpec tool) {
        JsonNode schema;
        try {
            schema = ProviderJsonValues.toNode(tool.inputSchema());
        } catch (RuntimeException failure) {
            throw new ProviderProtocolException(
                    "TOOL_SCHEMA_INVALID", "OpenAI Tool schema could not be encoded", false, failure);
        }
        ObjectNode strictSchema;
        try {
            strictSchema = OpenAiStrictSchemaNormalizer.normalizeRoot(schema);
        } catch (IllegalArgumentException failure) {
            // 不回显源 Schema，防止畸形或恶意 Tool 元数据进入日志。
            throw new ProviderProtocolException(
                    "TOOL_SCHEMA_INVALID", "OpenAI Tool schema is not strict-compatible", false, failure);
        }
        ObjectNode node = AbstractStreamingModelAdapter.JSON.createObjectNode();
        node.put("type", "function");
        node.put("name", tool.name());
        node.put("description", tool.description());
        node.set("parameters", strictSchema);
        node.put("strict", true);
        return node;
    }

    /**
     * 只应用具备原生 Responses 语义的生成控制项。
     */
    static void applyGeneration(ObjectNode root, ModelPort.GenerationOptions options) {
        OpenAiProviderSupport.applyCommonGeneration(root, options);
        if (options.maxOutputTokens() != null) {
            root.put("max_output_tokens", options.maxOutputTokens());
        }
        if (options.reasoningLevel() != null) {
            root.putObject("reasoning")
                    .put("effort", options.reasoningLevel())
                    .put("summary", "auto");
        }
    }

    /**
     * 序列化已经冻结的函数参数，且不通过诊断信息暴露其内容。
     */
    private static String writeArguments(JsonObject arguments) {
        return ProviderJsonValues.write(arguments);
    }

    /**
     * 构造不回显原生 reasoning 内容的稳定请求编码失败。
     */
    private static ProviderProtocolException requestEncoding() {
        return new ProviderProtocolException(
                "REQUEST_ENCODING", "OpenAI Responses message history is invalid", false);
    }

    /**
     * 保留请求 JSON 编码根因，但错误文本不暴露消息历史或原生 reasoning 内容。
     */
    private static ProviderProtocolException requestEncoding(Throwable cause) {
        return new ProviderProtocolException(
                "REQUEST_ENCODING", "OpenAI Responses message history is invalid", false, cause);
    }
}
