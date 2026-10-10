// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderJsonValues;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 Ja Provider 中立消息映射为 OpenAI Chat Completions 当前 wire contract。
 */
final class OpenAiChatCompletionsCodec {
    private static final java.util.Set<String> REASONING_FIELDS = java.util.Set.of(
            "reasoning_content", "reasoning", "reasoning_text");

    /**
     * 禁止创建有状态 Codec，确保预算估算和每次重试使用相同映射。
     */
    private OpenAiChatCompletionsCodec() {
    }

    /**
     * 构造单 choice 流请求，空系统提示不生成消息；Chat 不接受其它协议的不透明续传状态。
     */
    static ObjectNode encodeRequest(ModelPort.ModelRequest request) {
        if (request.continuation() != null) {
            throw new ProviderProtocolException(
                    "CONTINUATION_UNSUPPORTED", "OpenAI Chat does not accept continuation state", false);
        }
        ObjectNode root = AbstractStreamingModelAdapter.JSON.createObjectNode();
        root.put("model", request.configuration().model());
        ArrayNode messages = root.putArray("messages");
        if (!request.prompt().systemPrompt().isEmpty()) {
            messages.addObject().put("role", "system").put("content", request.prompt().systemPrompt());
        }
        request.messages().forEach(message -> encodeMessage(messages, message, request.configuration()));
        applyGeneration(root, request.configuration().generation());
        request.outputSchema().ifPresent(schema -> {
            ObjectNode responseFormat = root.putObject("response_format");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", "ja_output").put("strict", true);
            jsonSchema.set("schema", strictOutputSchema(schema));
            responseFormat.put("type", "json_schema");
        });
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            request.tools().forEach(tool -> tools.add(tool(tool)));
            root.put("tool_choice", "auto");
            root.put("parallel_tool_calls", true);
        }
        root.put("stream", true);
        root.putObject("stream_options").put("include_usage", true);
        return root;
    }

    /**
     * 通过共享归一化器满足 OpenAI strict Schema 约束，并将不兼容输入映射为稳定错误码。
     */
    private static ObjectNode strictOutputSchema(io.github.kongweiguang.ja.agent.domain.json.JsonObject schema) {
        try {
            return OpenAiStrictSchemaNormalizer.normalizeRoot(ProviderJsonValues.toNode(schema));
        } catch (IllegalArgumentException failure) {
            throw new ProviderProtocolException(
                    "OUTPUT_SCHEMA_INVALID", "OpenAI output schema is not strict-compatible", false, failure);
        }
    }

    /**
     * 按 role 保持消息与 Tool 配对，不把 Tool 结果折叠为普通 assistant/user 文本。
     */
    private static void encodeMessage(
            ArrayNode messages, ModelMessage message, ModelPort.ModelConfiguration configuration) {
        if (message.role() == ModelRole.USER) {
            messages.addObject().put("role", "user").put("content", textOnly(message));
            return;
        }
        if (message.role() == ModelRole.TOOL) {
            for (ModelContent block : message.content()) {
                if (!(block instanceof ToolResultContent result) || !result.media().isEmpty()) {
                    throw requestEncoding();
                }
                messages.addObject().put("role", "tool")
                        .put("tool_call_id", result.callId()).put("content", result.content());
            }
            return;
        }
        ObjectNode assistant = messages.addObject().put("role", "assistant");
        List<String> text = new ArrayList<>();
        ArrayNode calls = null;
        for (ModelContent block : message.content()) {
            if (block instanceof TextContent(String text1)) {
                text.add(text1);
            } else if (block instanceof ReasoningContent reasoning) {
                if (!matches(reasoning, configuration)) {
                    continue;
                }
                putReasoning(assistant, reasoning);
            } else if (block instanceof ToolCallContent call) {
                if (calls == null) {
                    calls = assistant.putArray("tool_calls");
                }
                ObjectNode functionCall = calls.addObject();
                functionCall.put("id", call.callId()).put("type", "function");
                functionCall.putObject("function").put("name", call.name())
                        .put("arguments", ProviderJsonValues.write(call.arguments()));
            } else {
                throw requestEncoding();
            }
        }
        if (text.isEmpty()) {
            assistant.putNull("content");
        }
        else {
            assistant.put("content", String.join("", text));
        }
    }

    /**
     * 仅回传当前 Provider 身份产生的 Chat reasoning；身份不匹配的 opaque 块已由上下文层过滤，
     * Codec 再次检查可防止直接调用方把其它模型的签名材料混入请求。
     */
    private static boolean matches(
            ReasoningContent reasoning, ModelPort.ModelConfiguration configuration) {
        return reasoning.matches(configuration.providerId(), configuration.modelId(),
                configuration.api().name().toLowerCase(java.util.Locale.ROOT), configuration.model(),
                ReasoningContent.endpointFingerprint(configuration.baseUri()));
    }

    /**
     * 从受控 native JSON 恢复原字段值，保留 reasoning_content/reasoning/reasoning_text 的供应商拼写。
     */
    private static void putReasoning(ObjectNode assistant, ReasoningContent reasoning) {
        try {
            if (!REASONING_FIELDS.contains(reasoning.wireField())) {
                throw requestEncoding();
            }
            JsonNode nativeValue = AbstractStreamingModelAdapter.JSON.readTree(reasoning.nativeJson());
            if (nativeValue == null || !nativeValue.isObject()
                || !nativeValue.has(reasoning.wireField())) {
                throw requestEncoding();
            }
            JsonNode value = nativeValue.get(reasoning.wireField());
            if (!value.isTextual() || value.textValue().isEmpty()) {
                throw requestEncoding();
            }
            JsonNode existing = assistant.get(reasoning.wireField());
            if (existing != null && !existing.equals(value)) {
                throw requestEncoding();
            }
            assistant.set(reasoning.wireField(), value.deepCopy());
        } catch (ProviderProtocolException failure) {
            throw failure;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException failure) {
            throw requestEncoding(failure);
        }
    }

    /**
     * 用户消息只接纳文本；原生附件能力为空时附件应在更早的双门中转为 Tool。
     */
    private static String textOnly(ModelMessage message) {
        List<String> values = new ArrayList<>();
        for (ModelContent block : message.content()) {
            if (!(block instanceof TextContent(String text))) {
                throw requestEncoding();
            }
            values.add(text);
        }
        return String.join("", values);
    }

    /**
     * 将冻结 Tool Schema 包装为 Chat function，并沿用 OpenAI strict schema 约束。
     */
    private static ObjectNode tool(ToolSpec tool) {
        JsonNode source = ProviderJsonValues.toNode(tool.inputSchema());
        final ObjectNode parameters;
        try {
            parameters = OpenAiStrictSchemaNormalizer.normalizeRoot(source);
        } catch (IllegalArgumentException failure) {
            throw new ProviderProtocolException(
                    "TOOL_SCHEMA_INVALID", "OpenAI Tool schema is not strict-compatible", false, failure);
        }
        ObjectNode node = AbstractStreamingModelAdapter.JSON.createObjectNode();
        node.put("type", "function");
        ObjectNode function = node.putObject("function");
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.set("parameters", parameters);
        function.put("strict", true);
        return node;
    }

    /**
     * 只发 Chat 当前字段；max_tokens 已弃用，因此使用 max_completion_tokens。
     */
    static void applyGeneration(ObjectNode root, ModelPort.GenerationOptions options) {
        OpenAiProviderSupport.applyCommonGeneration(root, options);
        if (options.maxOutputTokens() != null) {
            root.put("max_completion_tokens", options.maxOutputTokens());
        }
        if (options.reasoningLevel() != null) {
            root.put("reasoning_effort", options.reasoningLevel());
        }
    }

    /**
     * 构造不回显消息内容的稳定请求编码失败。
     */
    private static ProviderProtocolException requestEncoding() {
        return new ProviderProtocolException(
                "REQUEST_ENCODING", "OpenAI Chat message history is invalid", false);
    }

    /**
     * 保留请求 JSON 编码根因，但错误文本不暴露消息历史或附件正文。
     */
    private static ProviderProtocolException requestEncoding(Throwable cause) {
        return new ProviderProtocolException(
                "REQUEST_ENCODING", "OpenAI Chat message history is invalid", false, cause);
    }
}
