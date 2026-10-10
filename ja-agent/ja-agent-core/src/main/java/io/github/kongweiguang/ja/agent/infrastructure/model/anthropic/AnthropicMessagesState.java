// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort.FinishReason;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderJsonValues;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderReasoningSupport;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderStreamResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责事件顺序、usage 和 Tool 聚合的 Anthropic Messages 状态机。
 */
final class AnthropicMessagesState {
    private final ModelPort.ModelConfiguration configuration;
    private final Map<Long, ToolAccumulator> tools = new LinkedHashMap<>();
    private final Map<Long, BlockKind> openBlocks = new HashMap<>();
    private final List<ReadyTool> readyTools = new ArrayList<>();
    private final Map<Long, ThinkingAccumulator> thinking = new HashMap<>();
    private final Map<Long, ObjectNode> redactedThinking = new HashMap<>();
    private long startInputTokens;
    /* 缺失的缓存明细必须保持 null，不能因 Java 基本类型默认值伪造一次零缓存计量。 */
    private Long startCacheCreationTokens;
    private Long startCacheReadTokens;
    private boolean startUsageSeen;
    private int nextOrdinal;
    private FinishReason finishReason;
    private ModelUsage usage;
    private boolean messageStarted;
    private boolean messageDeltaSeen;
    private boolean messageStopped;
    private JsonNode messageStopPayload;

    /**
     * 绑定不可变请求身份，使完整原生 thinking 只能被同一 Provider 配置回放。
     */
    AnthropicMessagesState(ModelPort.ModelConfiguration configuration) {
        this.configuration = java.util.Objects.requireNonNull(configuration, "configuration");
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
     * Provider 内容索引只作为稀疏 Map 键，保留 long 范围而不按索引预分配资源。
     */
    private static long requiredIndex(JsonNode event) {
        JsonNode value = event.get("index");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ProviderProtocolException(
                    "CONTENT_INDEX", "Anthropic content index is invalid", false);
        }
        long index = value.longValue();
        if (index < 0) {
            throw new ProviderProtocolException(
                    "CONTENT_INDEX", "Anthropic content index is invalid", false);
        }
        return index;
    }

    /**
     * 解析完整 Tool JSON，且不把 Provider 内容复制到诊断信息。
     */
    private static ParsedArguments parseArguments(String value) {
        try {
            JsonNode parsed = AbstractStreamingModelAdapter.JSON.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                return invalidArguments();
            }
            return new ParsedArguments(ProviderJsonValues.toObject(parsed), null);
        } catch (IOException | RuntimeException failure) {
            return invalidArguments();
        }
    }

    /**
     * 把无法解码的参数变成空对象加稳定失败码，避免畸形 JSON 进入任何 Tool 执行路径。
     */
    private static ParsedArguments invalidArguments() {
        return new ParsedArguments(JsonObject.empty(), "TOOL_ARGUMENTS_INVALID");
    }

    /**
     * 序列化初始 Tool input，不保留 Mapper 诊断内容。
     */
    private static String writeJson(JsonNode value) {
        try {
            return AbstractStreamingModelAdapter.JSON.writeValueAsString(value);
        } catch (Exception failure) {
            throw new ProviderProtocolException(
                    "PROVIDER_JSON", "Anthropic Tool input could not be read", false, failure);
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
        if (value == null) {
            return "";
        }
        if (!value.isTextual()) {
            throw new ProviderProtocolException(
                    "PROVIDER_FIELD", "Anthropic emitted an invalid optional field", false);
        }
        return value.textValue();
    }

    /**
     * Tool 身份缺失或类型错误时重新生成请求，不伪造调用标识来结算失败结果。
     */
    private static String requiredToolIdentity(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new ProviderProtocolException(
                    "TOOL_IDENTITY_INCOMPLETE", "Provider Tool identity is incomplete", true, "MODEL_UNAVAILABLE");
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
     * 仅在 Provider 明示字段时返回数值；缺席必须保留未知，避免将不可观测的缓存量计入零消耗。
     */
    private static Long optionalNonNegative(JsonNode root, String field) {
        return root.has(field) ? requiredNonNegative(root, field) : null;
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
            throw new ProviderProtocolException("USAGE", "Anthropic usage is invalid", false, failure);
        }
    }

    /**
     * 创建不包含 Provider payload 文本的稳定事件顺序异常。
     */
    private static ProviderProtocolException protocol(String message) {
        return new ProviderProtocolException("ANTHROPIC_EVENT", message, false);
    }

    /**
     * 归约 Adapter 已校验的 Messages JSON，不依赖底层 SSE 解析类型或执行外部 IO。
     */
    List<ModelPort.ModelEvent> reduce(String eventName, JsonNode data) {
        if (messageStopped) {
            if ("message_stop".equals(eventName)
                && data.equals(messageStopPayload)) {
                    return List.of();
                }
            throw protocol("Anthropic emitted data after message_stop");
        }
        if (messageDeltaSeen && !"message_stop".equals(eventName)) {
            throw protocol("Anthropic emitted data after message_delta");
        }
        List<ModelPort.ModelEvent> effects = new ArrayList<>();
        switch (eventName) {
            case "message_start" -> messageStart(data);
            case "content_block_start" -> contentStart(data, effects);
            case "content_block_delta" -> contentDelta(data, effects);
            case "content_block_stop" -> contentStop(requiredIndex(data), effects);
            case "message_delta" -> messageDelta(data, effects);
            case "message_stop" -> {
                messageStop();
                messageStopPayload = data.deepCopy();
            }
            case "ping" -> {
                // Keepalive 不携带语义状态，不为合法长响应增加帧数量限制。
            }
            case "error" -> throw new ProviderProtocolException(
                    "PROVIDER_ERROR", "Anthropic reported a stream error", false);
            default -> throw protocol("Anthropic emitted an unsupported Messages event");
        }
        return List.copyOf(effects);
    }

    /**
     * message_stop 通过状态校验后关闭流，不等待服务端额外发送 EOF。
     */
    boolean isTerminal() {
        return messageStopped;
    }

    /**
     * 要求唯一 start 事件，并捕获全部 input token 类别。
     */
    private void messageStart(JsonNode event) {
        if (messageStarted) {
            throw protocol("Anthropic repeated message_start");
        }
        JsonNode message = requiredObject(event, "message");
        if (!"message".equals(requiredText(message, "type", false))
            || !"assistant".equals(requiredText(message, "role", false))
            || requiredText(message, "id", false).isEmpty()
            || requiredText(message, "model", false).isEmpty()
            || !message.path("content").isArray() || !message.path("content").isEmpty()) {
            throw protocol("Anthropic message_start shape is invalid");
        }
        JsonNode usage = message.get("usage");
        if (usage != null && !usage.isNull()) {
            try {
                if (!usage.isObject()) {
                    throw protocol("Anthropic message_start usage is invalid");
                }
                startInputTokens = requiredNonNegative(usage, "input_tokens");
                requiredNonNegative(usage, "output_tokens");
                startCacheCreationTokens = optionalNonNegative(usage, "cache_creation_input_tokens");
                startCacheReadTokens = optionalNonNegative(usage, "cache_read_input_tokens");
                startUsageSeen = true;
            } catch (ProviderProtocolException invalidUsage) {
                startUsageSeen = false;
                startCacheCreationTokens = null;
                startCacheReadTokens = null;
            }
        }
        messageStarted = true;
    }

    /**
     * 注册受限内容索引；thinking 的初始摘要可公开展示，但签名仍只留在状态机。
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
            if (!text.isEmpty()) {
                effects.add(new ModelPort.TextDelta(text));
            }
        } else if (kind == BlockKind.TOOL) {
            ToolAccumulator accumulator = new ToolAccumulator(
                    requiredToolIdentity(block, "id"), requiredToolIdentity(block, "name"), nextOrdinal++);
            JsonNode initial = block.get("input");
            if (initial == null || !initial.isObject()) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENTS", "Anthropic Tool input must be an object", false);
            }
            if (!initial.isEmpty()) {
                accumulator.setInitial(writeJson(initial));
            }
            tools.put(index, accumulator);
        } else if (kind == BlockKind.THINKING) {
            String initialThinking = optionalText(block, "thinking");
            thinking.put(index, new ThinkingAccumulator(
                    initialThinking, optionalText(block, "signature")));
            if (!initialThinking.isEmpty()) {
                effects.add(new ModelPort.ReasoningSummaryDelta(initialThinking));
            }
        } else if (kind == BlockKind.REDACTED_THINKING) {
            ObjectNode value = AbstractStreamingModelAdapter.JSON.createObjectNode();
            value.put("type", "redacted_thinking");
            value.put("data", requiredText(block, "data", false));
            redactedThinking.put(index, value);
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
                if (accumulator == null) {
                    throw protocol("Anthropic omitted thinking metadata");
                }
                if ("thinking_delta".equals(type)) {
                    String value = requiredText(delta, "thinking", true);
                    accumulator.appendThinking(value);
                    if (!value.isEmpty()) {
                        effects.add(new ModelPort.ReasoningSummaryDelta(value));
                    }
                } else {
                    accumulator.appendSignature(requiredText(delta, "signature", true));
                }
            }
            case REDACTED_THINKING -> throw protocol("Anthropic emitted a delta for redacted thinking");
        }
    }

    /**
     * 内容块结束时冻结调用身份和参数；暂存结果以等待终态决定参数错误还是整批输出截断。
     */
    private void contentStop(long index, List<ModelPort.ModelEvent> effects) {
        requireStarted();
        BlockKind kind = openBlocks.remove(index);
        if (kind == null) {
            throw new ProviderProtocolException(
                    "TOOL_SEQUENCE", "Anthropic stopped an unknown content block", false);
        }
        ToolAccumulator tool = tools.remove(index);
        ThinkingAccumulator privateThinking = thinking.remove(index);
        ObjectNode redacted = redactedThinking.remove(index);
        if (kind != BlockKind.TOOL) {
            if (tool != null || kind == BlockKind.REDACTED_THINKING && redacted == null) {
                throw protocol("Anthropic changed content block metadata");
            }
            if (kind == BlockKind.THINKING) {
                if (privateThinking == null) {
                    throw protocol("Anthropic omitted thinking metadata");
                }
                effects.add(reasoningBlockReady("thinking", privateThinking.finish()));
            } else if (kind == BlockKind.REDACTED_THINKING) {
                if (privateThinking != null) {
                    throw protocol("Anthropic changed thinking block metadata");
                }
                effects.add(reasoningBlockReady("redacted_thinking", redacted));
            } else if (privateThinking != null || redacted != null) {
                throw protocol("Anthropic changed thinking block metadata");
            }
            return;
        }
        if (tool == null || privateThinking != null || redacted != null) {
            throw protocol("Anthropic omitted Tool block metadata");
        }
        ParsedArguments parsed = parseArguments(tool.arguments());
        ReadyTool ready = new ReadyTool(
                tool.id(), tool.name(), parsed.arguments(), tool.ordinal(), parsed.failureCode());
        readyTools.add(ready);
    }

    /**
     * 等到 stop_reason 与 usage 均校验后才发布 Tool；max_tokens 时丢弃参数但保留调用身份，防止
     * 看似完整的 JSON 在截断回合中触发副作用。
     */
    private void messageDelta(JsonNode event, List<ModelPort.ModelEvent> effects) {
        requireStarted();
        if (messageDeltaSeen || !openBlocks.isEmpty()) {
            throw protocol("Anthropic emitted message_delta out of order");
        }
        String reason = requiredText(requiredObject(event, "delta"), "stop_reason", false);
        if ("model_context_window_exceeded".equals(reason)) {
            throw new ModelPort.ContextOverflowException(null);
        }
        FinishReason reportedReason = mapFinishReason(reason);
        if (!readyTools.isEmpty() && reportedReason == FinishReason.STOP) {
            /* Anthropic-compatible 网关可能以 end_turn 结束完整 tool_use；完整调用身份优先于 stop 标签。 */
            finishReason = FinishReason.TOOL_CALLS;
        } else {
            finishReason = reportedReason;
        }
        if ((finishReason == FinishReason.TOOL_CALLS) == readyTools.isEmpty()
            && !(finishReason == FinishReason.MAX_OUTPUT_TOKENS && !readyTools.isEmpty())) {
            throw new ProviderProtocolException(
                    "FINISH_REASON", "Anthropic Tool output disagrees with stop reason", false);
        }
        JsonNode value = event.get("usage");
        if (value != null && !value.isNull()) {
            try {
                if (!value.isObject()) {
                    throw protocol("Anthropic message_delta usage is invalid");
                }
                long output = requiredNonNegative(value, "output_tokens");
                if (startUsageSeen || value.has("input_tokens")) {
                    long input = optionalOr(value, "input_tokens", startInputTokens);
                    Long cacheWrite = optionalNonNegative(value, "cache_creation_input_tokens");
                    Long cacheRead = optionalNonNegative(value, "cache_read_input_tokens");
                    if (cacheWrite == null) {
                        cacheWrite = startCacheCreationTokens;
                    }
                    if (cacheRead == null) {
                        cacheRead = startCacheReadTokens;
                    }
                    long total = checkedAdd(input, output);
                    if (cacheWrite != null) {
                        total = checkedAdd(total, cacheWrite);
                    }
                    if (cacheRead != null) {
                        total = checkedAdd(total, cacheRead);
                    }
                    /* Anthropic 的缓存读写量独立于常规 input，不能重复计算。 */
                    usage = cacheRead == null && cacheWrite == null
                            ? new ModelUsage(input, output, total)
                            : new ModelUsage(input, output, total, cacheRead, cacheWrite,
                            ModelUsage.InputAccounting.INPUT_EXCLUDES_CACHE);
                }
            } catch (ProviderProtocolException | IllegalArgumentException invalidUsage) {
                usage = null;
            }
        }
        for (ReadyTool ready : readyTools) {
            boolean truncated = finishReason == FinishReason.MAX_OUTPUT_TOKENS;
            String failureCode = truncated
                    ? "TOOL_CALL_TRUNCATED" : ready.failureCode();
            effects.add(new ModelPort.ToolCallReady(
                    ready.id(), ready.name(), truncated ? JsonObject.empty() : ready.arguments(),
                    ready.ordinal(), failureCode));
        }
        readyTools.clear();
        messageDeltaSeen = true;
    }

    /**
     * 仅接受完整消息的显式 stop 标记；Usage 在最终结果结算时发布。
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
        if (!messageStarted || !messageStopped || finishReason == null
            || !openBlocks.isEmpty() || !tools.isEmpty() || !thinking.isEmpty()
            || !redactedThinking.isEmpty()) {
            throw new ProviderProtocolException(
                    "STREAM_TRUNCATED", "Anthropic stream ended without explicit completion", true);
        }
        List<ModelPort.ModelEvent> effects = new ArrayList<>();
        if (usage != null) {
            effects.add(new ModelPort.UsageEvent(usage));
        }
        return new ProviderStreamResult(
                new ModelPort.ModelOutcome(finishReason, null, usage), effects);
    }

    /**
     * 只在原生内容块关闭后发布完整 opaque 块；签名和 redacted data 不进入公开文本或日志。
     */
    private ModelPort.ReasoningBlockReady reasoningBlockReady(String wireField, ObjectNode block) {
        try {
            String nativeJson = AbstractStreamingModelAdapter.JSON.writeValueAsString(block);
            ReasoningContent content = ProviderReasoningSupport.capture(configuration, wireField, nativeJson);
            return new ModelPort.ReasoningBlockReady(content);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new ProviderProtocolException(
                    "PROVIDER_JSON", "Anthropic reasoning block could not be encoded", false, failure);
        }
    }

    /**
     * 任何内容或终止事件前必须先收到 message_start。
     */
    private void requireStarted() {
        if (!messageStarted) {
            throw protocol("Anthropic emitted data before message_start");
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
         * 仅以原生 opaque 块形式回放的 thinking 内容。
         */
        THINKING,

        /**
         * Provider 已脱敏且不允许增量内容的私有推理块。
         */
        REDACTED_THINKING
    }

    /**
     * 干净 message_stop 关闭流前暂存 Tool 身份、可安全参数和解析状态。
     *
     * @param id 当前实体的稳定标识
     * @param name 名称
     * @param arguments 工具调用参数
     * @param ordinal 当前项的顺序位置
     * @param failureCode 失败代码
     */
    private record ReadyTool(String id,
                             String name,
                             JsonObject arguments,
                             int ordinal,
                             String failureCode) {
        /**
         * 冻结参数投影；实际执行仍必须等待完整 ModelOutcome 的结束原因。
         */
        private ReadyTool {
            java.util.Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * 解析成功参数或生成安全失败结果所需的空参数和稳定错误码。
     *
     * @param arguments 工具调用参数
     * @param failureCode 失败代码
     */
    private record ParsedArguments(JsonObject arguments,
                                   String failureCode) {
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
            arguments.append(value);
        }

        /**
         * 在同一分配上限内替换累加器内容。
         */
        private void replace(String value) {
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
     * 累积原生 thinking 及其签名以保证完整块可回放；单帧解析和模型窗口负责资源边界。
     */
    private static final class ThinkingAccumulator {
        private final StringBuilder value = new StringBuilder();
        private final StringBuilder signature = new StringBuilder();

        /**
         * 捕获 content_block_start 中可能已携带的私有字段。
         */
        ThinkingAccumulator(String value, String signature) {
            append(this.value, value);
            append(this.signature, signature);
        }

        /**
         * 累积 thinking 原文；公开展示由状态机以摘要增量事件单独发布。
         */
        void appendThinking(String delta) {
            append(value, delta);
        }

        /**
         * 追加 Provider 签名片段，供同身份下一次 Messages 请求验证原生 thinking。
         */
        void appendSignature(String delta) {
            append(signature, delta);
        }

        /**
         * 在块关闭时要求签名完整，并冻结为 Anthropic 原生块。
         */
        ObjectNode finish() {
            if (signature.isEmpty()) {
                throw new ProviderProtocolException(
                        "ANTHROPIC_REASONING", "Anthropic thinking signature is missing", false);
            }
            ObjectNode block = AbstractStreamingModelAdapter.JSON.createObjectNode();
            block.put("type", "thinking");
            block.put("thinking", value.toString());
            block.put("signature", signature.toString());
            return block;
        }

        /**
         * 分片保持原始顺序；不能把累计长度误判为协议错误。
         */
        private void append(StringBuilder target, String delta) {
            target.append(delta);
        }
    }
}
