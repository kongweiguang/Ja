// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderJsonValues;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderSseReader;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderStreamResult;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.NetworkntToolArgumentValidator;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.ToolSchemaException;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort.FinishReason;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 负责事件顺序、usage 和 Tool 聚合的 Anthropic Messages 状态机。
 */
final class AnthropicMessagesState {
    private final Map<Long, ToolAccumulator> tools = new LinkedHashMap<>();
    private final Map<Long, BlockKind> openBlocks = new HashMap<>();
    private final List<ReadyTool> readyTools = new ArrayList<>();
    private final Map<Long, ThinkingAccumulator> thinking = new HashMap<>();
    private final List<ObjectNode> privateBlocks = new ArrayList<>();
    private long startInputTokens;
    private long startCacheCreationTokens;
    private long startCacheReadTokens;
    private int nextOrdinal;
    private FinishReason finishReason;
    private ModelUsage usage;
    private boolean messageStarted;
    private boolean messageDeltaSeen;
    private boolean messageStopped;

    /**
     * 归约文档化的 Messages 事件并返回语义效果，不在状态迁移中执行外部 IO。
     */
    List<ModelPort.ModelEvent> reduce(
            ProviderSseReader.Event event, Function<String, ToolSpec> toolLookup) {
        if (messageStopped) throw protocol("Anthropic emitted data after message_stop");
        if (messageDeltaSeen && !"message_stop".equals(event.name())) {
            throw protocol("Anthropic emitted data after message_delta");
        }
        JsonNode data = event.data();
        List<ModelPort.ModelEvent> effects = new ArrayList<>();
        switch (event.name()) {
            case "message_start" -> messageStart(data);
            case "content_block_start" -> contentStart(data, effects);
            case "content_block_delta" -> contentDelta(data, effects);
            case "content_block_stop" -> contentStop(requiredIndex(data), toolLookup);
            case "message_delta" -> messageDelta(data);
            case "message_stop" -> messageStop();
            case "ping" -> {
                // Keepalive 不携带语义状态，但上游仍限制其帧大小和数量。
            }
            case "error" -> throw new ProviderProtocolException(
                    "PROVIDER_ERROR", "Anthropic reported a stream error", false);
            default -> throw protocol("Anthropic emitted an unsupported Messages event");
        }
        return List.copyOf(effects);
    }

    /**
     * 要求唯一 start 事件，并捕获全部 input token 类别。
     */
    private void messageStart(JsonNode event) {
        if (messageStarted) throw protocol("Anthropic repeated message_start");
        JsonNode message = requiredObject(event, "message");
        if (!"message".equals(requiredText(message, "type", false))
            || !"assistant".equals(requiredText(message, "role", false))
            || requiredText(message, "id", false).isEmpty()
            || requiredText(message, "model", false).isEmpty()
            || !message.path("content").isArray() || !message.path("content").isEmpty()) {
            throw protocol("Anthropic message_start shape is invalid");
        }
        JsonNode usage = requiredObject(message, "usage");
        startInputTokens = requiredNonNegative(usage, "input_tokens");
        requiredNonNegative(usage, "output_tokens");
        startCacheCreationTokens = optionalNonNegative(usage, "cache_creation_input_tokens");
        startCacheReadTokens = optionalNonNegative(usage, "cache_read_input_tokens");
        messageStarted = true;
    }

    /**
     * 注册受限内容索引，并只把可公开的初始文本加入效果列表。
     */
    private void contentStart(JsonNode event, List<ModelPort.ModelEvent> effects) {
        requireStarted();
        long index = requiredIndex(event);
        JsonNode block = requiredObject(event, "content_block");
        String type = requiredText(block, "type", false);
        BlockKind kind = switch (type) {
            case "text" -> BlockKind.TEXT;
            case "tool_use" -> BlockKind.TOOL;
            case "thinking" -> BlockKind.THINKING;
            case "redacted_thinking" -> BlockKind.REDACTED_THINKING;
            default -> throw new ProviderProtocolException(
                    "ANTHROPIC_BLOCK", "Anthropic emitted an unsupported content block", false);
        };
        if (openBlocks.putIfAbsent(index, kind) != null) {
            throw protocol("Anthropic repeated a content index");
        }
        if (kind == BlockKind.TEXT) {
            String text = requiredText(block, "text", true);
            if (!text.isEmpty()) effects.add(new ModelPort.TextDelta(text));
        } else if (kind == BlockKind.TOOL) {
            ToolAccumulator accumulator = new ToolAccumulator(
                    requiredText(block, "id", false), requiredText(block, "name", false), nextOrdinal++);
            JsonNode initial = block.get("input");
            if (initial == null || !initial.isObject()) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENTS", "Anthropic Tool input must be an object", false);
            }
            if (!initial.isEmpty()) accumulator.setInitial(writeJson(initial));
            tools.put(index, accumulator);
        } else if (kind == BlockKind.THINKING) {
            thinking.put(index, new ThinkingAccumulator(
                    optionalText(block, "thinking"), optionalText(block, "signature")));
        } else if (kind == BlockKind.REDACTED_THINKING) {
            ObjectNode value = AbstractStreamingModelAdapter.JSON.createObjectNode();
            value.put("type", "redacted_thinking");
            value.put("data", requiredText(block, "data", false));
            privateBlocks.add(value);
        }
    }

    /**
     * 在生成文本效果或接收 Tool 参数前校验对应内容块的增量语法。
     */
    private void contentDelta(JsonNode event, List<ModelPort.ModelEvent> effects) {
        requireStarted();
        long index = requiredIndex(event);
        BlockKind kind = openBlocks.get(index);
        if (kind == null) {
            throw new ProviderProtocolException(
                    "TOOL_SEQUENCE", "Anthropic emitted a delta outside an open block", false);
        }
        JsonNode delta = requiredObject(event, "delta");
        String type = requiredText(delta, "type", false);
        switch (kind) {
            case TEXT -> {
                if ("text_delta".equals(type)) {
                    effects.add(new ModelPort.TextDelta(requiredText(delta, "text", false)));
                } else if (!"citations_delta".equals(type)) {
                    throw protocol("Anthropic changed a text block delta type");
                }
            }
            case TOOL -> {
                if (!"input_json_delta".equals(type)) {
                    throw protocol("Anthropic changed a Tool block delta type");
                }
                ToolAccumulator tool = tools.get(index);
                if (tool == null) {
                    throw new ProviderProtocolException(
                            "TOOL_SEQUENCE", "Anthropic emitted Tool arguments before metadata", false);
                }
                tool.append(requiredText(delta, "partial_json", true));
            }
            case THINKING -> {
                if (!("thinking_delta".equals(type) || "signature_delta".equals(type))) {
                    throw protocol("Anthropic changed a thinking block delta type");
                }
                ThinkingAccumulator accumulator = thinking.get(index);
                if (accumulator == null) throw protocol("Anthropic omitted thinking metadata");
                if ("thinking_delta".equals(type)) {
                    accumulator.appendThinking(requiredText(delta, "thinking", true));
                } else {
                    accumulator.appendSignature(requiredText(delta, "signature", true));
                }
            }
            case REDACTED_THINKING -> throw protocol("Anthropic emitted a delta for redacted thinking");
        }
    }

    /**
     * 校验 Tool 并私下保存，只有完整流证明安全后才允许 Adapter 提交。
     */
    private void contentStop(long index, Function<String, ToolSpec> toolLookup) {
        requireStarted();
        BlockKind kind = openBlocks.remove(index);
        if (kind == null) {
            throw new ProviderProtocolException(
                    "TOOL_SEQUENCE", "Anthropic stopped an unknown content block", false);
        }
        ToolAccumulator tool = tools.remove(index);
        ThinkingAccumulator privateThinking = thinking.remove(index);
        if (kind != BlockKind.TOOL) {
            if (tool != null) throw protocol("Anthropic changed content block metadata");
            if (kind == BlockKind.THINKING) {
                if (privateThinking == null) throw protocol("Anthropic omitted thinking metadata");
                privateBlocks.add(privateThinking.finish());
            } else if (privateThinking != null) {
                throw protocol("Anthropic changed thinking block metadata");
            }
            return;
        }
        if (tool == null) throw protocol("Anthropic omitted Tool block metadata");
        JsonNode arguments = parseArguments(tool.arguments());
        ToolSpec spec = toolLookup.apply(tool.name());
        try {
            JsonNode sourceSchema = ProviderJsonValues.toNode(spec.inputSchema());
            new NetworkntToolArgumentValidator(writeJson(sourceSchema)).validate(writeJson(arguments));
        } catch (ToolSchemaException failure) {
            throw new ProviderProtocolException(
                    "TOOL_SCHEMA_INVALID", "Anthropic Tool arguments do not match the declared schema",
                    false);
        }
        JsonObject values = ProviderJsonValues.toObject(arguments);
        readyTools.add(new ReadyTool(tool.id(), tool.name(), values, tool.ordinal()));
    }

    /**
     * 所有内容块关闭且 Tool 原因一致后才映射终止 delta。
     */
    private void messageDelta(JsonNode event) {
        requireStarted();
        if (messageDeltaSeen || !openBlocks.isEmpty()) {
            throw protocol("Anthropic emitted message_delta out of order");
        }
        String reason = requiredText(requiredObject(event, "delta"), "stop_reason", false);
        if ("model_context_window_exceeded".equals(reason)) {
            throw new ModelPort.ContextOverflowException(null);
        }
        finishReason = mapFinishReason(reason);
        if ((finishReason == FinishReason.TOOL_CALLS) != !readyTools.isEmpty()) {
            throw new ProviderProtocolException(
                    "FINISH_REASON", "Anthropic Tool output disagrees with stop reason", false);
        }
        JsonNode value = requiredObject(event, "usage");
        long input = checkedAdd(
                optionalOr(value, "input_tokens", startInputTokens),
                optionalOr(value, "cache_creation_input_tokens", startCacheCreationTokens),
                optionalOr(value, "cache_read_input_tokens", startCacheReadTokens));
        long output = requiredNonNegative(value, "output_tokens");
        usage = new ModelUsage(input, output, checkedAdd(input, output));
        messageDeltaSeen = true;
    }

    /**
     * 仅接受显式 stop 标记；到达干净 EOF 前不发布 usage。
     */
    private void messageStop() {
        if (!messageDeltaSeen || !openBlocks.isEmpty()) {
            throw protocol("Anthropic stopped before completing message state");
        }
        messageStopped = true;
    }

    /**
     * 流干净结束后生成最终结果和有序效果，提交职责由 Adapter 承担。
     */
    ProviderStreamResult finish() {
        if (!messageStarted || !messageStopped || finishReason == null || usage == null
            || !openBlocks.isEmpty() || !tools.isEmpty() || !thinking.isEmpty()) {
            throw new ProviderProtocolException(
                    "STREAM_TRUNCATED", "Anthropic stream ended without explicit completion", true);
        }
        List<ModelPort.ModelEvent> effects = new ArrayList<>();
        for (ReadyTool tool : readyTools) {
            effects.add(new ModelPort.ToolCallReady(
                    tool.id(), tool.name(), tool.arguments(), tool.ordinal()));
        }
        effects.add(new ModelPort.UsageEvent(usage));
        ModelPort.Continuation continuation = finishReason == FinishReason.TOOL_CALLS
                ? AnthropicMessagesContinuation.encode(privateBlocks) : null;
        return new ProviderStreamResult(
                new ModelPort.ModelOutcome(finishReason, continuation, usage), effects);
    }

    /**
     * 任何内容或终止事件前必须先收到 message_start。
     */
    private void requireStarted() {
        if (!messageStarted) throw protocol("Anthropic emitted data before message_start");
    }

    /**
     * 仅接纳 Provider-neutral Agent Loop 能表达的结束原因。
     */
    private static FinishReason mapFinishReason(String reason) {
        return switch (reason) {
            case "end_turn", "stop_sequence", "refusal" -> FinishReason.STOP;
            case "tool_use" -> FinishReason.TOOL_CALLS;
            case "max_tokens" -> FinishReason.MAX_OUTPUT_TOKENS;
            default -> throw new ProviderProtocolException(
                    "FINISH_REASON", "Anthropic returned an unsupported finish reason", false);
        };
    }

    /**
     * Provider 控制的内容索引用作 Map Key 前先读取并限制范围。
     */
    private static long requiredIndex(JsonNode event) {
        JsonNode value = event.get("index");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ProviderProtocolException(
                    "CONTENT_INDEX", "Anthropic content index is invalid", false);
        }
        long index = value.longValue();
        if (index < 0 || index > 1_023) {
            throw new ProviderProtocolException(
                    "CONTENT_INDEX", "Anthropic content index is invalid", false);
        }
        return index;
    }

    /**
     * 解析完整 Tool JSON，且不把 Provider 内容复制到诊断信息。
     */
    private static JsonNode parseArguments(String value) {
        try {
            JsonNode parsed = AbstractStreamingModelAdapter.JSON.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENTS", "Anthropic Tool arguments must be an object", false);
            }
            return parsed;
        } catch (ProviderProtocolException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new ProviderProtocolException(
                    "PROVIDER_JSON", "Anthropic Tool arguments are invalid JSON", false);
        }
    }

    /**
     * 序列化初始 Tool input，不保留 Mapper 诊断内容。
     */
    private static String writeJson(JsonNode value) {
        try {
            return AbstractStreamingModelAdapter.JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new ProviderProtocolException(
                    "PROVIDER_JSON", "Anthropic Tool input could not be read", false);
        }
    }

    /**
     * 不做类型强制转换地读取必需对象。
     */
    private static JsonNode requiredObject(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) {
            throw new ProviderProtocolException(
                    "PROVIDER_FIELD", "Anthropic omitted a required object", false);
        }
        return value;
    }

    /**
     * 读取必需文本字段，并按约束决定是否允许空值。
     */
    private static String requiredText(JsonNode root, String field, boolean allowEmpty) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || !allowEmpty && value.textValue().isEmpty()) {
            throw new ProviderProtocolException(
                    "PROVIDER_FIELD", "Anthropic omitted a required field", false);
        }
        return value.textValue();
    }

    /**
     * Thinking 起始块允许省略尚未生成的正文或签名，但字段一旦出现仍必须是文本；完整签名继续在
     * content_block_stop 时强制校验，兼容原生 signature_delta 顺序而不放松续轮安全边界。
     */
    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) return "";
        if (!value.isTextual()) {
            throw new ProviderProtocolException(
                    "PROVIDER_FIELD", "Anthropic emitted an invalid optional field", false);
        }
        return value.textValue();
    }

    /**
     * 读取必需的非负整数 usage 值。
     */
    private static long requiredNonNegative(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
            || value.longValue() < 0) {
            throw new ProviderProtocolException("USAGE", "Anthropic usage is invalid", false);
        }
        return value.longValue();
    }

    /**
     * usage 类别缺失时按零处理，存在但畸形时拒绝。
     */
    private static long optionalNonNegative(JsonNode root, String field) {
        return root.has(field) ? requiredNonNegative(root, field) : 0L;
    }

    /**
     * 除非 message-delta 显式替换，否则沿用 message-start 计数。
     */
    private static long optionalOr(JsonNode root, String field, long fallback) {
        return root.has(field) ? requiredNonNegative(root, field) : fallback;
    }

    /**
     * 将 usage 溢出转换为稳定计数异常。
     */
    private static long checkedAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException failure) {
            throw new ProviderProtocolException("USAGE", "Anthropic usage is invalid", false);
        }
    }

    /**
     * 在不发生有符号溢出的前提下累加全部 input token 类别。
     */
    private static long checkedAdd(long first, long second, long third) {
        return checkedAdd(checkedAdd(first, second), third);
    }

    /**
     * 创建不包含 Provider payload 文本的稳定事件顺序异常。
     */
    private static ProviderProtocolException protocol(String message) {
        return new ProviderProtocolException("ANTHROPIC_EVENT", message, false);
    }

    /**
     * 干净 message_stop 关闭流前，暂存经 schema 校验的公开 Tool 数据。
     */
    private record ReadyTool(String id, String name, JsonObject arguments, int ordinal) {
        /**
         * 冻结已解码参数，避免后续请求内变更影响事件。
         */
        private ReadyTool {
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * 受限 Tool 累加器在校验前保存元数据和部分 JSON。
     */
    private static final class ToolAccumulator {
        private final String id;
        private final String name;
        private final int ordinal;
        private final StringBuilder arguments = new StringBuilder();
        private boolean initial;

        /**
         * 接纳任何参数片段前捕获稳定元数据。
         */
        ToolAccumulator(String id, String name, int ordinal) {
            this.id = id;
            this.name = name;
            this.ordinal = ordinal;
        }

        /**
         * 保存非空初始对象，并拒绝语义含混的后续 delta。
         */
        void setInitial(String value) {
            replace(value);
            initial = true;
        }

        /**
         * 仅在未提供初始对象时追加受限片段。
         */
        void append(String value) {
            if (initial) {
                throw new ProviderProtocolException(
                        "TOOL_SEQUENCE", "Anthropic mixed initial and streamed Tool input", false);
            }
            if ((long) arguments.length() + value.length() > 4_000_000L) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENT_LIMIT", "Anthropic Tool arguments exceed the limit", false);
            }
            arguments.append(value);
        }

        /**
         * 在同一分配上限内替换累加器内容。
         */
        private void replace(String value) {
            if (value.length() > 4_000_000L) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENT_LIMIT", "Anthropic Tool arguments exceed the limit", false);
            }
            arguments.setLength(0);
            arguments.append(value);
        }

        /**
         * 内容块关闭后返回 Provider call 标识。
         */
        String id() {
            return id;
        }

        /**
         * 返回冻结 Tool 名称，用于精确目录查找。
         */
        String name() {
            return name;
        }

        /**
         * 返回确定性的 Provider 内容顺序。
         */
        int ordinal() {
            return ordinal;
        }

        /**
         * 无 delta 的 Tool 块按 block start 提供的类型化空对象处理。
         */
        String arguments() {
            return arguments.isEmpty() ? "{}" : arguments.toString();
        }
    }

    /**
     * 有界累积普通 thinking 及其签名，既保证下一轮可原样续接，也阻止 Provider 无限占用内存。
     */
    private static final class ThinkingAccumulator {
        private static final int MAX_PRIVATE_CHARACTERS = 4_000_000;
        private final StringBuilder value = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();

        /** 捕获 content_block_start 中可能已携带的私有字段。 */
        ThinkingAccumulator(String value, String signature) {
            append(this.value, value);
            append(this.signature, signature);
        }

        /** 追加私有思考片段，但不生成任何公开 ModelEvent。 */
        void appendThinking(String delta) {
            append(value, delta);
        }

        /** 追加 Provider 签名片段，供下一轮 Messages 请求验证原生 thinking。 */
        void appendSignature(String delta) {
            append(signature, delta);
        }

        /** 在块关闭时要求签名完整，并冻结为 Anthropic 原生块。 */
        ObjectNode finish() {
            if (signature.isEmpty()) {
                throw new ProviderProtocolException(
                        "ANTHROPIC_CONTINUATION", "Anthropic thinking signature is missing", false);
            }
            ObjectNode block = AbstractStreamingModelAdapter.JSON.createObjectNode();
            block.put("type", "thinking");
            block.put("thinking", value.toString());
            block.put("signature", signature.toString());
            return block;
        }

        /** 对 thinking 与 signature 共享单块内存上限，避免分片绕过边界。 */
        private void append(StringBuilder target, String delta) {
            if ((long) value.length() + signature.length() + delta.length() > MAX_PRIVATE_CHARACTERS) {
                throw new ProviderProtocolException(
                        "ANTHROPIC_CONTINUATION", "Anthropic continuation state exceeds the limit", false);
            }
            target.append(delta);
        }
    }

    /**
     * 冻结内容块类别，防止私有推理或 Tool 增量被误投影为公开文本。
     */
    private enum BlockKind {
        /**
         * 可公开给用户的助手文本块。
         */
        TEXT,

        /**
         * 需要聚合并校验参数的 Tool 调用块。
         */
        TOOL,

        /**
         * 仅用于 Provider 内部连续性的私有推理块。
         */
        THINKING,

        /**
         * Provider 已脱敏且不允许增量内容的私有推理块。
         */
        REDACTED_THINKING
    }
}
