// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderJsonValues;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderReasoningSupport;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ProviderStreamResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 归约 ChatCompletionChunk 的 choice、文本、Tool delta、usage 与可选 [DONE] 终态。
 */
final class OpenAiChatCompletionsState {
    private static final List<String> REASONING_FIELDS = List.of(
            "reasoning_content", "reasoning", "reasoning_text");
    private final TreeMap<Integer, ToolAccumulator> tools = new TreeMap<>();
    private final ModelPort.ModelConfiguration configuration;
    private final StringBuilder reasoning = new StringBuilder();
    private String reasoningField;
    private boolean reasoningBlockEmitted;
    private String completionId;
    private String model;
    private ModelPort.FinishReason finishReason;
    private String rawFinishReason;
    private ModelUsage usage;

    /**
     * 绑定本次 Chat 请求的 Provider 身份，令完成后的 reasoning opaque 块只能回到同一端点。
     */
    OpenAiChatCompletionsState(ModelPort.ModelConfiguration configuration) {
        this.configuration = java.util.Objects.requireNonNull(configuration, "configuration");
    }

    /**
     * 发布非空文本字段；null 表示当前 chunk 没有该通道。
     */
    private static void appendText(
            JsonNode delta, String name, List<ModelPort.ModelEvent> effects) {
        JsonNode value = delta.get(name);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual()) {
            throw field("OpenAI Chat text delta is invalid");
        }
        if (!value.textValue().isEmpty()) {
            effects.add(new ModelPort.TextDelta(value.textValue()));
        }
    }

    /**
     * 多个缓存字段仅在数值一致时归一化，避免代理扩展差异静默改变上下文计量。
     */
    private static boolean conflictingCacheRead(Long standard, Long compatible, Long topLevel) {
        Long selected = standard != null ? standard : compatible != null ? compatible : topLevel;
        return standard != null && !standard.equals(selected)
               || compatible != null && !compatible.equals(selected)
               || topLevel != null && !topLevel.equals(selected);
    }

    /**
     * 返回可精确表示的非负 wire 索引；Tool 索引只作 TreeMap 键，不按数值分配数组。
     */
    private static int requiredIndex(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
            || value.intValue() < 0) {
            throw field("OpenAI Chat index is invalid");
        }
        return value.intValue();
    }

    /**
     * 返回必填非负 long，用于不丢精度地接收 Token 计量。
     */
    private static long requiredLong(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
            || value.longValue() < 0) {
            throw new IllegalArgumentException("invalid usage field");
        }
        return value.longValue();
    }

    /**
     * 缓存明细为可选供应商字段；存在时仍必须是可表示的非负整数。
     */
    private static Long optionalLong(JsonNode root, String name) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("invalid usage details");
        }
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalArgumentException("invalid usage detail");
        }
        return value.longValue();
    }

    /**
     * 返回必填文本，并按调用方约束决定是否允许空串。
     */
    private static String requiredText(JsonNode root, String name, boolean emptyAllowed) {
        String value = optionalText(root, name);
        if (value == null || !emptyAllowed && value.isEmpty()) {
            throw field("OpenAI Chat required field is invalid");
        }
        return value;
    }

    /**
     * 返回可选文本；存在但非文本时直接拒绝。
     */
    private static String optionalText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw field("OpenAI Chat optional field is invalid");
        }
        return value.textValue();
    }

    /**
     * Tool 身份缺失或类型错误时重新生成请求，不从索引或工具名合成调用标识。
     */
    private static String optionalToolIdentity(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw incompleteToolIdentity();
        }
        return value.textValue();
    }

    /**
     * 身份错误使用固定脱敏子码进入可取消的会话重试流程。
     */
    private static ProviderProtocolException incompleteToolIdentity() {
        return new ProviderProtocolException(
                "TOOL_IDENTITY_INCOMPLETE", "Provider Tool identity is incomplete", true, "MODEL_UNAVAILABLE");
    }

    /**
     * 创建稳定字段错误，不回显 Provider 数据。
     */
    private static ProviderProtocolException field(String message) {
        return new ProviderProtocolException("PROVIDER_FIELD", message, false);
    }

    /**
     * 创建稳定序列错误，不回显 Provider 数据。
     */
    private static ProviderProtocolException protocol(String message) {
        return new ProviderProtocolException("OPENAI_CHAT_EVENT", message, false);
    }

    /**
     * 仅归约 JSON chunk；SSE 的 [DONE] 边界由 Adapter 处理，业务完成仍要求 finish_reason。
     */
    List<ModelPort.ModelEvent> reduce(
            JsonNode chunk, Function<String, ToolSpec> toolLookup) {
        capture(chunk);
        List<ModelPort.ModelEvent> effects = new ArrayList<>();
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray()) {
            throw field("OpenAI Chat choices are invalid");
        }
        if (chunk.hasNonNull("usage")) {
            acceptUsage(chunk.get("usage"));
        }
        if (choices.isEmpty()) {
            return effects;
        }
        if (finishReason != null) {
            JsonNode duplicate = choices.get(0);
            if (choices.size() == 1 && duplicate.isObject()
                && rawFinishReason.equals(duplicate.path("finish_reason").textValue())
                && duplicate.path("delta").isObject() && duplicate.path("delta").isEmpty()) {
                return effects;
            }
            throw protocol("OpenAI Chat emitted a choice after finish_reason");
        }
        if (choices.size() != 1) {
            throw field("OpenAI Chat returned multiple choices");
        }
        JsonNode choice = choices.get(0);
        if (!choice.isObject() || requiredIndex(choice, "index") != 0) {
            throw field("OpenAI Chat choice index is invalid");
        }
        JsonNode delta = choice.get("delta");
        if (delta == null || !delta.isObject()) {
            throw field("OpenAI Chat delta is invalid");
        }
        acceptDelta(delta, effects);
        if (choice.hasNonNull("finish_reason")) {
            JsonNode reason = choice.get("finish_reason");
            if (!reason.isTextual()) {
                throw field("OpenAI Chat finish reason is invalid");
            }
            finish(reason.textValue(), toolLookup, effects);
        }
        return List.copyOf(effects);
    }

    /**
     * clean EOF 或 [DONE] 后要求显式 finish_reason；usage 缺失保留 null，不能伪造成零。
     */
    ProviderStreamResult finish() {
        if (finishReason == null || !tools.isEmpty()) {
            throw new ProviderProtocolException(
                    "STREAM_TRUNCATED", "OpenAI Chat stream ended without explicit completion", true);
        }
        List<ModelPort.ModelEvent> effects = new ArrayList<>();
        appendReasoningBlock(effects);
        if (usage != null) {
            effects.add(new ModelPort.UsageEvent(usage));
        }
        return new ProviderStreamResult(
                new ModelPort.ModelOutcome(finishReason, null, usage), effects);
    }

    /**
     * 固定 completion id、object 与 model，防止跨请求 chunk 被拼接为同一结果。
     */
    private void capture(JsonNode chunk) {
        String id = requiredText(chunk, "id", false);
        String currentModel = requiredText(chunk, "model", false);
        if (!"chat.completion.chunk".equals(requiredText(chunk, "object", false))) {
            throw field("OpenAI Chat object is invalid");
        }
        if (completionId != null && !completionId.equals(id)
            || model != null && !model.equals(currentModel)) {
            throw field("OpenAI Chat changed stream identity");
        }
        completionId = id;
        model = currentModel;
    }

    /**
     * 文本可立即发布；Tool 参数必须完整组装并校验后才对 Agent Loop 可见。
     */
    private void acceptDelta(JsonNode delta, List<ModelPort.ModelEvent> effects) {
        if (delta.hasNonNull("role")
            && !"assistant".equals(requiredText(delta, "role", false))) {
            throw field("OpenAI Chat delta role is invalid");
        }
        acceptReasoning(delta, effects);
        appendText(delta, "content", effects);
        appendText(delta, "refusal", effects);
        JsonNode calls = delta.get("tool_calls");
        if (calls == null || calls.isNull()) {
            return;
        }
        if (!calls.isArray()) {
            throw field("OpenAI Chat tool_calls are invalid");
        }
        for (JsonNode call : calls) {
            acceptToolDelta(call);
        }
    }

    /**
     * Chat 兼容接口没有统一 reasoning 字段；同一 delta 只接纳首个非空字段，避免网关同时填充别名
     * 时把同一段推理重复展示，同时记录原字段供下一轮 assistant 历史精确回传。
     */
    private void acceptReasoning(JsonNode delta, List<ModelPort.ModelEvent> effects) {
        for (String wireField : REASONING_FIELDS) {
            JsonNode value = delta.get(wireField);
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isTextual()) {
                throw field("OpenAI Chat reasoning field is invalid");
            }
            String text = value.textValue();
            if (text.isEmpty()) {
                continue;
            }
            if (reasoningField == null) {
                reasoningField = wireField;
            }
            if (!reasoningField.equals(wireField)) {
                continue;
            }
            reasoning.append(text);
            effects.add(new ModelPort.ReasoningSummaryDelta(text));
            break;
        }
    }

    /**
     * 在 Chat 流完成且 reasoning 已有正文时冻结一个最小原生 assistant 字段对象；空推理不制造
     * 占位块，避免把模型没有返回的思考伪造成历史事实。
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // 已分类的 ProviderProtocolException 原样重抛，保留固定错误码与受限 cause。
    private void appendReasoningBlock(List<ModelPort.ModelEvent> effects) {
        if (reasoningBlockEmitted || reasoningField == null || reasoning.isEmpty()) {
            return;
        }
        ObjectNode nativeValue = AbstractStreamingModelAdapter.JSON.createObjectNode()
                .put(reasoningField, reasoning.toString());
        String nativeJson;
        try {
            nativeJson = AbstractStreamingModelAdapter.JSON.writeValueAsString(nativeValue);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException failure) {
            throw new ProviderProtocolException(
                    "PROVIDER_JSON", "OpenAI Chat reasoning could not be captured", false);
        }
        effects.add(new ModelPort.ReasoningBlockReady(
                ProviderReasoningSupport.capture(configuration, reasoningField, nativeJson)));
        reasoningBlockEmitted = true;
    }

    /**
     * 按官方 index 关联分片；id/type/name 只允许首次出现或以相同值冗余出现。
     */
    private void acceptToolDelta(JsonNode call) {
        if (!call.isObject()) {
            throw field("OpenAI Chat tool delta is invalid");
        }
        int index = requiredIndex(call, "index");
        ToolAccumulator accumulator = tools.computeIfAbsent(index, ToolAccumulator::new);
        accumulator.acceptId(optionalToolIdentity(call, "id"));
        String type = optionalText(call, "type");
        if (type != null && !"function".equals(type)) {
            throw field("OpenAI Chat tool type is unsupported");
        }
        JsonNode function = call.get("function");
        if (function == null || function.isNull()) {
            return;
        }
        if (!function.isObject()) {
            throw field("OpenAI Chat tool function is invalid");
        }
        accumulator.acceptName(optionalToolIdentity(function, "name"));
        String arguments = optionalText(function, "arguments");
        if (arguments != null) {
            accumulator.append(arguments);
        }
    }

    /**
     * finish_reason 决定规范结果；length 下保留调用身份并由 Agent 结算截断失败。
     */
    private void finish(String reason, Function<String, ToolSpec> toolLookup,
                        List<ModelPort.ModelEvent> effects) {
        if (finishReason != null) {
            throw protocol("OpenAI Chat repeated finish_reason");
        }
        rawFinishReason = reason;
        appendReasoningBlock(effects);
        switch (reason) {
            case "stop" -> {
                /* OpenAI-compatible 网关可能用 stop 结束已经完整发送的 Tool delta；完整性仍由
                 * finishTools 校验，不能仅因标签差异丢弃一次合法原生调用。 */
                if (tools.isEmpty()) {
                    finishReason = ModelPort.FinishReason.STOP;
                }
                else {
                    finishTools(toolLookup, effects);
                }
            }
            case "tool_calls" -> finishTools(toolLookup, effects);
            case "length" -> {
                if (tools.isEmpty()) {
                    finishReason = ModelPort.FinishReason.MAX_OUTPUT_TOKENS;
                } else {
                    finishTools(toolLookup, effects, ModelPort.FinishReason.MAX_OUTPUT_TOKENS, true);
                }
            }
            case "content_filter" -> throw new ProviderProtocolException(
                    "PROVIDER_ERROR", "OpenAI Chat content filter stopped the response", false);
            default -> throw protocol("OpenAI Chat finish_reason is unsupported");
        }
    }

    /**
     * 按 Provider 索引排序并校验调用身份唯一，再映射为连续本地 ordinal 发布可结算调用。
     */
    private void finishTools(Function<String, ToolSpec> toolLookup,
                             List<ModelPort.ModelEvent> effects) {
        finishTools(toolLookup, effects, ModelPort.FinishReason.TOOL_CALLS, false);
    }

    /**
     * 在 length 终态保留可配对身份，但用空参数标记截断，确保残缺调用永不进入执行器。
     */
    private void finishTools(Function<String, ToolSpec> toolLookup,
                             List<ModelPort.ModelEvent> effects,
                             ModelPort.FinishReason terminalReason,
                             boolean truncated) {
        if (tools.isEmpty()) {
            throw protocol("OpenAI Chat reported Tool calls without deltas");
        }
        Set<String> callIds = new HashSet<>();
        int ordinal = 0;
        for (ToolAccumulator tool : tools.values()) {
            ModelPort.ToolCallReady ready = tool.finish(toolLookup, ordinal++, truncated);
            if (!callIds.add(ready.callId())) {
                throw protocol("OpenAI Chat repeated a Tool call id");
            }
            effects.add(ready);
        }
        tools.clear();
        finishReason = terminalReason;
    }

    /**
     * 保留 Provider 最后的非递减完整快照；缓存读写字段只影响新输入拆分，原始 input/total 不重算。
     * 部分兼容端会重复发送 usage，因此不能按帧求和。
     */
    private void acceptUsage(JsonNode value) {
        if (!value.isObject()) {
            usage = null;
            return;
        }
        try {
            long input = requiredLong(value, "prompt_tokens");
            long output = requiredLong(value, "completion_tokens");
            long total = requiredLong(value, "total_tokens");
            JsonNode details = value.get("prompt_tokens_details");
            Long standardCacheRead = null;
            Long compatibleCacheRead = null;
            Long topLevelCacheRead = null;
            boolean cacheReadValid = true;
            try {
                standardCacheRead = details == null || details.isNull()
                        ? null : optionalLong(details, "cached_tokens");
                compatibleCacheRead = optionalLong(value, "prompt_cache_hit_tokens");
                topLevelCacheRead = optionalLong(value, "cached_tokens");
                cacheReadValid = !conflictingCacheRead(
                        standardCacheRead, compatibleCacheRead, topLevelCacheRead);
            } catch (IllegalArgumentException malformedRead) {
                cacheReadValid = false;
            }
            Long cacheRead = null;
            if (cacheReadValid) {
                cacheRead = standardCacheRead != null ? standardCacheRead
                        : compatibleCacheRead != null ? compatibleCacheRead : topLevelCacheRead;
            }
            Long cacheWrite = null;
            boolean cacheWriteValid = true;
            if (details != null && !details.isNull()) {
                try {
                    cacheWrite = optionalLong(details, "cache_write_tokens");
                } catch (IllegalArgumentException malformedWrite) {
                    cacheWriteValid = false;
                }
            }
            boolean cacheWithinInput = cacheRead == null || cacheRead <= input;
            if (cacheWrite != null && (!cacheWithinInput || cacheWrite > input
                    || cacheRead != null && cacheWrite > input - cacheRead)) {
                cacheWriteValid = false;
            }
            if (!cacheWithinInput) {
                cacheRead = null;
            }
            if (!cacheWriteValid) {
                cacheWrite = null;
            }
            ModelUsage.InputAccounting accounting = cacheReadValid && cacheWriteValid && cacheWithinInput
                    && (cacheRead != null || cacheWrite != null)
                    ? ModelUsage.InputAccounting.INPUT_INCLUDES_CACHE
                    : ModelUsage.InputAccounting.UNKNOWN;
            ModelUsage snapshot = new ModelUsage(input, output, total, cacheRead, cacheWrite, accounting);
            if (usage != null && (snapshot.inputTokens() < usage.inputTokens()
                                  || snapshot.outputTokens() < usage.outputTokens()
                                  || snapshot.totalTokens() < usage.totalTokens())) {
                throw new IllegalArgumentException("usage snapshot moved backwards");
            }
            usage = snapshot;
        } catch (IllegalArgumentException failure) {
            usage = null;
        }
    }

    /**
     * 累积单个 Tool 的稳定元数据与 arguments 分片，完成前不产生外部语义效果。
     */
    private static final class ToolAccumulator {
        private final int index;
        private final StringBuilder arguments = new StringBuilder();
        private String id;
        private String name;

        /**
         * 绑定上游 index 仅用于排序；Ja ordinal 在完整批次后独立连续分配。
         */
        ToolAccumulator(int index) {
            this.index = index;
        }

        /**
         * 解析完整 arguments JSON object，不接纳数组、标量或尾随数据。
         */
        private static JsonNode parseArguments(String value) {
            try {
                JsonNode parsed = AbstractStreamingModelAdapter.JSON.readTree(value);
                if (parsed == null || !parsed.isObject()) {
                    throw new IOException("not object");
                }
                return parsed;
            } catch (IOException | RuntimeException failure) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENTS", "OpenAI Chat Tool arguments are invalid JSON", false, failure);
            }
        }

        /**
         * 同一调用身份允许相同值冗余出现，但禁止中途改写。
         */
        void acceptId(String value) {
            if (value == null) {
                return;
            }
            if (id != null && !id.equals(value)) {
                throw protocol("OpenAI Chat changed Tool id");
            }
            id = value;
        }

        /**
         * 同一 Tool 名允许相同值冗余出现，但禁止中途改写。
         */
        void acceptName(String value) {
            if (value == null) {
                return;
            }
            if (name != null && !name.equals(value)) {
                throw protocol("OpenAI Chat changed Tool name");
            }
            name = value;
        }

        /**
         * 累积 Provider 参数分片；总量不作为任务停机条件，发送背压由事件消费路径承接。
         */
        void append(String value) {
            arguments.append(value);
        }

        /**
         * 截断调用不解析参数；完整调用坏 JSON 则携带调用身份交给 Runner 回传失败结果。
         */
        ModelPort.ToolCallReady finish(Function<String, ToolSpec> toolLookup, int ordinal, boolean truncated) {
            if (id == null || id.isEmpty() || name == null || name.isEmpty()) {
                throw incompleteToolIdentity();
            }
            if (truncated) {
                return new ModelPort.ToolCallReady(id, name, JsonObject.empty(), ordinal, "TOOL_CALL_TRUNCATED");
            }
            JsonNode wire;
            try {
                wire = parseArguments(arguments.toString());
            } catch (ProviderProtocolException invalidArguments) {
                return new ModelPort.ToolCallReady(
                        id, name, JsonObject.empty(), ordinal, "TOOL_ARGUMENTS_INVALID");
            }
            ToolSpec spec = toolLookup.apply(name);
            JsonNode restored = wire;
            if (spec != null) {
                JsonNode sourceSchema = ProviderJsonValues.toNode(spec.inputSchema());
                try {
                    restored = OpenAiStrictArgumentRestorer.restore(sourceSchema, wire);
                } catch (IllegalArgumentException ignored) {
                    // 保留原始对象，Runner 会返回参数错误而不会越过 Tool 执行边界。
                }
            }
            return new ModelPort.ToolCallReady(
                    id, name, ProviderJsonValues.toObject(restored), ordinal);
        }

        /**
         * 返回冻结的 Provider Tool 顺序。
         */
        int index() {
            return index;
        }

    }
}
