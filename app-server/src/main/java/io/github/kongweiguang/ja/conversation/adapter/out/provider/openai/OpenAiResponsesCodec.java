// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderJsonValues;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 只负责 OpenAI Responses 请求映射的 Codec，不承担网络、重试或生命周期职责。
 */
final class OpenAiResponsesCodec {
    private static final long MAX_NATIVE_REQUEST_BYTES = 50_000_000L;
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
                        io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent.Kind.IMAGE,
                        Set.of("image/png", "image/jpeg", "image/gif", "image/webp"),
                        MAX_NATIVE_REQUEST_BYTES),
                new ModelPort.NativeAttachmentRule(
                        io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent.Kind.PDF,
                        Set.of("application/pdf"), MAX_NATIVE_REQUEST_BYTES)),
                MAX_NATIVE_REQUEST_BYTES);
    }

    /**
     * 将稳定 Ja 请求映射为冻结的 Responses wire contract。
     */
    static ObjectNode encodeRequest(ModelPort.ModelRequest request) {
        ObjectNode root = AbstractStreamingModelAdapter.JSON.createObjectNode();
        root.put("model", request.configuration().model());
        root.put("instructions", request.prompt().systemPrompt());
        ModelPort.Continuation continuation = request.continuation();
        if (continuation != null) {
            if (!"openai_responses".equals(continuation.protocol())) {
                throw new IllegalArgumentException("OpenAI Responses continuation protocol is required");
            }
            root.put("previous_response_id", continuation.opaqueState());
            root.set("input", continuationInput(request.messages()));
        } else {
            root.set("input", input(request.messages()));
        }
        applyGeneration(root, request.configuration().generation());
        OpenAiProviderSupport.applyToolsAndStreaming(root, request.tools(),
                OpenAiResponsesCodec::tool);
        return root;
    }

    /**
     * 只发送续接响应之后产生的 Tool 结果，因为 previous_response_id 已在 Provider 端关联更早的
     * user、assistant 和 function-call 条目。
     */
    private static ArrayNode continuationInput(List<ModelMessage> messages) {
        int assistantIndex = -1;
        Set<String> expectedCalls = Set.of();
        for (int index = messages.size() - 1; index >= 0; index--) {
            ModelMessage message = messages.get(index);
            if (message.role() != ModelRole.ASSISTANT) continue;
            Set<String> calls = new HashSet<>();
            for (ModelContent block : message.content()) {
                if (block instanceof ToolCallContent call && !calls.add(call.callId())) {
                    throw continuationFailure();
                }
            }
            if (!calls.isEmpty()) {
                assistantIndex = index;
                expectedCalls = Set.copyOf(calls);
                break;
            }
        }
        if (assistantIndex < 0 || assistantIndex == messages.size() - 1) {
            throw continuationFailure();
        }
        ArrayNode result = AbstractStreamingModelAdapter.JSON.createArrayNode();
        Set<String> actualCalls = new HashSet<>();
        for (int index = assistantIndex + 1; index < messages.size(); index++) {
            ModelMessage message = messages.get(index);
            if (message.role() != ModelRole.TOOL || message.content().isEmpty()) {
                throw continuationFailure();
            }
            for (ModelContent block : message.content()) {
                if (!(block instanceof ToolResultContent output)
                    || !expectedCalls.contains(output.callId()) || !actualCalls.add(output.callId())) {
                    throw continuationFailure();
                }
                ObjectNode item = result.addObject();
                item.put("type", "function_call_output");
                item.put("call_id", output.callId());
                item.put("output", output.content());
                if (output.error()) item.put("status", "incomplete");
            }
        }
        if (!actualCalls.equals(expectedCalls)) throw continuationFailure();
        return result;
    }

    /**
     * 当完整历史无法证明续接后缀时，生成一条已脱敏的请求失败。
     */
    private static ProviderProtocolException continuationFailure() {
        return new ProviderProtocolException(
                "CONTINUATION_INPUT", "OpenAI continuation Tool results are invalid", false);
    }

    /**
     * 在文本、函数调用和函数结果条目之间保持消息及内容顺序。
     */
    private static ArrayNode input(List<ModelMessage> messages) {
        ArrayNode result = AbstractStreamingModelAdapter.JSON.createArrayNode();
        for (ModelMessage message : messages) {
            List<ModelContent> pending = new ArrayList<>();
            for (ModelContent block : message.content()) {
                if (block instanceof TextContent || block instanceof NativeAttachmentContent) {
                    pending.add(block);
                } else {
                    flushContent(result, message.role(), pending);
                    if (block instanceof ToolCallContent call) {
                        ObjectNode item = result.addObject();
                        item.put("type", "function_call");
                        item.put("call_id", call.callId());
                        item.put("name", call.name());
                        item.put("arguments", writeArguments(call.arguments()));
                    } else if (block instanceof ToolResultContent output) {
                        ObjectNode item = result.addObject();
                        item.put("type", "function_call_output");
                        item.put("call_id", output.callId());
                        item.put("output", output.content());
                        if (output.error()) item.put("status", "incomplete");
                    }
                }
            }
            flushContent(result, message.role(), pending);
        }
        return result;
    }

    /**
     * assistant 历史使用 EasyInput，因为强类型 input-message 角色不接受 assistant；原生附件只允许归属用户消息。
     */
    private static void flushContent(ArrayNode result, ModelRole role, List<ModelContent> pending) {
        if (pending.isEmpty()) return;
        if (role == ModelRole.ASSISTANT) {
            ObjectNode item = result.addObject();
            item.put("role", "assistant");
            StringBuilder text = new StringBuilder();
            for (ModelContent block : pending) {
                if (!(block instanceof TextContent content)) {
                    throw new ProviderProtocolException(
                            "REQUEST_ENCODING", "Assistant history cannot contain native attachments", false);
                }
                text.append(content.text());
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
            if (block instanceof TextContent text) {
                content.addObject().put("type", "input_text").put("text", text.text());
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
                    "TOOL_SCHEMA_INVALID", "OpenAI Tool schema could not be encoded", false);
        }
        ObjectNode strictSchema;
        try {
            strictSchema = OpenAiStrictSchemaNormalizer.normalizeRoot(schema);
        } catch (IllegalArgumentException failure) {
            // 不回显源 Schema，防止畸形或恶意 Tool 元数据进入日志。
            throw new ProviderProtocolException(
                    "TOOL_SCHEMA_INVALID", "OpenAI Tool schema is not strict-compatible", false);
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
        if (options.temperature() != null) root.put("temperature", options.temperature());
        if (options.topP() != null) root.put("top_p", options.topP());
        if (options.maxOutputTokens() != null) root.put("max_output_tokens", options.maxOutputTokens());
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
}
