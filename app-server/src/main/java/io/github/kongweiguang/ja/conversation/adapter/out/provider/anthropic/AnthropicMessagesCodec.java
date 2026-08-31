// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.anthropic;

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

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * 不承担传输、重试或生命周期职责的 Anthropic Messages 请求 Codec。
 */
final class AnthropicMessagesCodec {
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 8_192;
    private static final long MAX_NATIVE_REQUEST_BYTES = 32_000_000L;

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
                        io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent.Kind.IMAGE,
                        Set.of("image/jpeg", "image/png", "image/gif", "image/webp"),
                        MAX_NATIVE_REQUEST_BYTES),
                new ModelPort.NativeAttachmentRule(
                        io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent.Kind.PDF,
                        Set.of("application/pdf"), MAX_NATIVE_REQUEST_BYTES)),
                MAX_NATIVE_REQUEST_BYTES);
    }

    /**
     * 将稳定 Ja 请求映射为冻结的 Messages wire contract。
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
        root.put("system", request.prompt().systemPrompt());
        root.set("messages", messages(request.messages(), request.continuation()));
        if (generation.reasoningLevel() != null) {
            root.putObject("output_config").put("effort", generation.reasoningLevel());
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            request.tools().forEach(tool -> tools.add(tool(tool)));
        }
        root.put("stream", true);
        return root;
    }

    /**
     * 按 Provider-neutral 顺序保留每条消息和内容块。
     */
    private static ArrayNode messages(java.util.List<ModelMessage> messages,
                                      ModelPort.Continuation continuation) {
        ArrayNode result = AbstractStreamingModelAdapter.JSON.createArrayNode();
        int continuationMessage = continuation == null ? -1 : continuationMessage(messages);
        List<ObjectNode> privateBlocks = continuation == null ? List.of()
                : AnthropicMessagesContinuation.decode(continuation);
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            ModelMessage message = messages.get(messageIndex);
            String role = message.role() == ModelRole.ASSISTANT ? "assistant" : "user";
            ArrayNode content = appendMessage(result, role).withArray("content");
            if (messageIndex == continuationMessage) {
                privateBlocks.forEach(block -> content.add(block.deepCopy()));
            }
            for (ModelContent block : message.content()) {
                if (block instanceof TextContent text) {
                    content.addObject().put("type", "text").put("text", text.text());
                } else if (block instanceof NativeAttachmentContent attachment) {
                    if (message.role() != ModelRole.USER) {
                        throw new ProviderProtocolException(
                                "REQUEST_ENCODING", "Only user messages can contain native attachments", false);
                    }
                    ObjectNode item = content.addObject();
                    item.put("type", attachment.kind() == NativeAttachmentContent.Kind.IMAGE
                            ? "image" : "document");
                    item.putObject("source").put("type", "base64")
                            .put("media_type", attachment.mediaType())
                            .put("data", attachment.base64Data());
                } else if (block instanceof ToolCallContent call) {
                    ObjectNode item = content.addObject();
                    item.put("type", "tool_use");
                    item.put("id", call.callId());
                    item.put("name", call.name());
                    item.set("input", ProviderJsonValues.toNode(call.arguments()));
                } else if (block instanceof ToolResultContent output) {
                    ObjectNode item = content.addObject();
                    item.put("type", "tool_result");
                    item.put("tool_use_id", output.callId());
                    item.put("content", output.content());
                    item.put("is_error", output.error());
                }
            }
        }
        return result;
    }

    /**
     * 将私有块只绑定到最近一组已完成 Tool 调用，防止畸形上下文把签名重放到错误助手消息。
     */
    private static int continuationMessage(java.util.List<ModelMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ModelMessage message = messages.get(index);
            if (message.role() != ModelRole.ASSISTANT) continue;
            Set<String> expectedCalls = new HashSet<>();
            for (ModelContent block : message.content()) {
                if (block instanceof ToolCallContent call && !expectedCalls.add(call.callId())) {
                    throw continuationFailure();
                }
            }
            if (expectedCalls.isEmpty()) continue;
            Set<String> actualCalls = new HashSet<>();
            for (int suffix = index + 1; suffix < messages.size(); suffix++) {
                ModelMessage result = messages.get(suffix);
                if (result.role() != ModelRole.TOOL || result.content().isEmpty()) {
                    throw continuationFailure();
                }
                for (ModelContent block : result.content()) {
                    if (!(block instanceof ToolResultContent output)
                        || !expectedCalls.contains(output.callId()) || !actualCalls.add(output.callId())) {
                        throw continuationFailure();
                    }
                }
            }
            if (!actualCalls.equals(expectedCalls)) throw continuationFailure();
            return index;
        }
        throw continuationFailure();
    }

    /** 生成不包含 opaqueState 内容的稳定续传错误。 */
    private static ProviderProtocolException continuationFailure() {
        return new ProviderProtocolException(
                "ANTHROPIC_CONTINUATION", "Anthropic continuation Tool history is invalid", false);
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
     * 将冻结 JSON Schema 转换为 Anthropic strict Tool 对象。
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
        node.put("strict", true);
        return node;
    }
}
