// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.OpenAiChatSseReader;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderJsonValues;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderStreamResult;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** 归约 ChatCompletionChunk 的 choice、文本、Tool delta、usage 与可选 [DONE] 终态。 */
final class OpenAiChatCompletionsState {
    private static final int MAX_ARGUMENT_CHARACTERS = 4_000_000;
    private final TreeMap<Integer, ToolAccumulator> tools = new TreeMap<>();
    private String completionId;
    private String model;
    private ModelPort.FinishReason finishReason;
    private ModelUsage usage;
    private boolean done;

    /** 在单个 chunk 内先校验身份和 choice 形状，再产生待提交的 Provider 中立效果。 */
    List<ModelPort.ModelEvent> reduce(
            OpenAiChatSseReader.Event event, Function<String, ToolSpec> toolLookup) {
        if (done) throw protocol("OpenAI Chat emitted an event after [DONE]");
        if (event.done()) {
            done = true;
            return List.of();
        }
        JsonNode chunk = event.data();
        capture(chunk);
        List<ModelPort.ModelEvent> effects = new ArrayList<>();
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray()) throw field("OpenAI Chat choices are invalid");
        if (chunk.hasNonNull("usage")) acceptUsage(chunk.get("usage"));
        if (choices.isEmpty()) return effects;
        if (finishReason != null) throw protocol("OpenAI Chat emitted a choice after finish_reason");
        if (choices.size() != 1) throw field("OpenAI Chat returned multiple choices");
        JsonNode choice = choices.get(0);
        if (!choice.isObject() || requiredIndex(choice, "index") != 0) {
            throw field("OpenAI Chat choice index is invalid");
        }
        JsonNode delta = choice.get("delta");
        if (delta == null || !delta.isObject()) throw field("OpenAI Chat delta is invalid");
        acceptDelta(delta, effects);
        if (choice.hasNonNull("finish_reason")) {
            JsonNode reason = choice.get("finish_reason");
            if (!reason.isTextual()) throw field("OpenAI Chat finish reason is invalid");
            finish(reason.textValue(), toolLookup, effects);
        }
        return List.copyOf(effects);
    }

    /** clean EOF 或 [DONE] 后要求显式 finish_reason；usage 缺失保留 null，不能伪造成零。 */
    ProviderStreamResult finish() {
        if (finishReason == null || !tools.isEmpty()) {
            throw new ProviderProtocolException(
                    "STREAM_TRUNCATED", "OpenAI Chat stream ended without explicit completion", true);
        }
        List<ModelPort.ModelEvent> effects = usage == null
                ? List.of() : List.of(new ModelPort.UsageEvent(usage));
        return new ProviderStreamResult(
                new ModelPort.ModelOutcome(finishReason, null, usage), effects);
    }

    /** 固定 completion id、object 与 model，防止跨请求 chunk 被拼接为同一结果。 */
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

    /** 文本可立即发布；Tool 参数必须完整组装并校验后才对 Agent Loop 可见。 */
    private void acceptDelta(JsonNode delta, List<ModelPort.ModelEvent> effects) {
        if (delta.hasNonNull("role")
            && !"assistant".equals(requiredText(delta, "role", false))) {
            throw field("OpenAI Chat delta role is invalid");
        }
        appendText(delta, "content", effects);
        appendText(delta, "refusal", effects);
        JsonNode calls = delta.get("tool_calls");
        if (calls == null || calls.isNull()) return;
        if (!calls.isArray()) throw field("OpenAI Chat tool_calls are invalid");
        for (JsonNode call : calls) acceptToolDelta(call);
    }

    /** 发布非空文本字段；null 表示当前 chunk 没有该通道。 */
    private static void appendText(
            JsonNode delta, String name, List<ModelPort.ModelEvent> effects) {
        JsonNode value = delta.get(name);
        if (value == null || value.isNull()) return;
        if (!value.isTextual()) throw field("OpenAI Chat text delta is invalid");
        if (!value.textValue().isEmpty()) effects.add(new ModelPort.TextDelta(value.textValue()));
    }

    /** 按官方 index 关联分片；id/type/name 只允许首次出现或以相同值冗余出现。 */
    private void acceptToolDelta(JsonNode call) {
        if (!call.isObject()) throw field("OpenAI Chat tool delta is invalid");
        int index = requiredIndex(call, "index");
        if (index > 1_023) throw field("OpenAI Chat tool index exceeds turn bounds");
        ToolAccumulator accumulator = tools.computeIfAbsent(index, ToolAccumulator::new);
        accumulator.acceptId(optionalText(call, "id"));
        String type = optionalText(call, "type");
        if (type != null && !"function".equals(type)) {
            throw field("OpenAI Chat tool type is unsupported");
        }
        JsonNode function = call.get("function");
        if (function == null || function.isNull()) return;
        if (!function.isObject()) throw field("OpenAI Chat tool function is invalid");
        accumulator.acceptName(optionalText(function, "name"));
        String arguments = optionalText(function, "arguments");
        if (arguments != null) accumulator.append(arguments);
    }

    /** finish_reason 决定规范结果；Tool calls 在该边界一次性校验并按 index 顺序发布。 */
    private void finish(String reason, Function<String, ToolSpec> toolLookup,
                        List<ModelPort.ModelEvent> effects) {
        if (finishReason != null) throw protocol("OpenAI Chat repeated finish_reason");
        switch (reason) {
            case "stop" -> {
                /* OpenAI-compatible 网关可能用 stop 结束已经完整发送的 Tool delta；完整性仍由
                 * finishTools 校验，不能仅因标签差异丢弃一次合法原生调用。 */
                if (tools.isEmpty()) finishReason = ModelPort.FinishReason.STOP;
                else finishTools(toolLookup, effects);
            }
            case "tool_calls" -> finishTools(toolLookup, effects);
            case "length" -> {
                if (!tools.isEmpty()) throw protocol("OpenAI Chat truncated a Tool call");
                finishReason = ModelPort.FinishReason.MAX_OUTPUT_TOKENS;
            }
            case "content_filter" -> throw new ProviderProtocolException(
                    "PROVIDER_ERROR", "OpenAI Chat content filter stopped the response", false);
            default -> throw protocol("OpenAI Chat finish_reason is unsupported");
        }
    }

    /** 要求 Tool 索引连续、调用身份唯一，再按 Provider 顺序发布完整调用。 */
    private void finishTools(Function<String, ToolSpec> toolLookup,
                             List<ModelPort.ModelEvent> effects) {
        if (tools.isEmpty()) throw protocol("OpenAI Chat reported Tool calls without deltas");
        int expectedIndex = 0;
        Set<String> callIds = new HashSet<>();
        for (ToolAccumulator tool : tools.values()) {
            if (tool.index() != expectedIndex++) {
                throw protocol("OpenAI Chat Tool indices are not contiguous");
            }
            ModelPort.ToolCallReady ready = tool.finish(toolLookup);
            if (!callIds.add(ready.callId())) {
                throw protocol("OpenAI Chat repeated a Tool call id");
            }
            effects.add(ready);
        }
        tools.clear();
        finishReason = ModelPort.FinishReason.TOOL_CALLS;
    }

    /**
     * 保留 Provider 最后的非递减完整快照；cache/reasoning 明细是子集，不参与再次累加。
     * 部分兼容端会在多个 chunk 重复累计 usage，因此不能按帧求和，也不能拒绝合法后续快照。
     */
    private void acceptUsage(JsonNode value) {
        if (!value.isObject()) throw field("OpenAI Chat usage is invalid");
        try {
            long input = requiredLong(value, "prompt_tokens");
            long output = requiredLong(value, "completion_tokens");
            long total = requiredLong(value, "total_tokens");
            ModelUsage snapshot = new ModelUsage(input, output, total);
            if (usage != null && (snapshot.inputTokens() < usage.inputTokens()
                || snapshot.outputTokens() < usage.outputTokens()
                || snapshot.totalTokens() < usage.totalTokens())) {
                throw new IllegalArgumentException("usage snapshot moved backwards");
            }
            usage = snapshot;
        } catch (IllegalArgumentException failure) {
            throw new ProviderProtocolException("USAGE", "OpenAI Chat usage is invalid", false);
        }
    }

    /** 返回必填非负整数并拒绝浮点或越界表示。 */
    private static int requiredIndex(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
            || value.intValue() < 0) {
            throw field("OpenAI Chat index is invalid");
        }
        return value.intValue();
    }

    /** 返回必填非负 long，用于不丢精度地接收 Token 计量。 */
    private static long requiredLong(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
            || value.longValue() < 0) {
            throw new IllegalArgumentException("invalid usage field");
        }
        return value.longValue();
    }

    /** 返回必填文本，并按调用方约束决定是否允许空串。 */
    private static String requiredText(JsonNode root, String name, boolean emptyAllowed) {
        String value = optionalText(root, name);
        if (value == null || !emptyAllowed && value.isEmpty()) {
            throw field("OpenAI Chat required field is invalid");
        }
        return value;
    }

    /** 返回可选文本；存在但非文本时直接拒绝。 */
    private static String optionalText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw field("OpenAI Chat optional field is invalid");
        return value.textValue();
    }

    /** 创建稳定字段错误，不回显 Provider 数据。 */
    private static ProviderProtocolException field(String message) {
        return new ProviderProtocolException("PROVIDER_FIELD", message, false);
    }

    /** 创建稳定序列错误，不回显 Provider 数据。 */
    private static ProviderProtocolException protocol(String message) {
        return new ProviderProtocolException("OPENAI_CHAT_EVENT", message, false);
    }

    /** 累积单个 Tool 的稳定元数据与 arguments 分片，完成前不产生外部语义效果。 */
    private static final class ToolAccumulator {
        private final int index;
        private final StringBuilder arguments = new StringBuilder();
        private String id;
        private String name;

        /** 绑定官方 tool_calls index，作为 Ja 的确定性 ordinal。 */
        ToolAccumulator(int index) {
            this.index = index;
        }

        /** 同一调用身份允许相同值冗余出现，但禁止中途改写。 */
        void acceptId(String value) {
            if (value == null) return;
            if (id != null && !id.equals(value)) throw protocol("OpenAI Chat changed Tool id");
            id = value;
        }

        /** 同一 Tool 名允许相同值冗余出现，但禁止中途改写。 */
        void acceptName(String value) {
            if (value == null) return;
            if (name != null && !name.equals(value)) throw protocol("OpenAI Chat changed Tool name");
            name = value;
        }

        /** 在追加前检查字符上限，防止畸形分片制造无界 StringBuilder。 */
        void append(String value) {
            if (value.length() > MAX_ARGUMENT_CHARACTERS - arguments.length()) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENT_LIMIT", "OpenAI Chat Tool arguments exceed the limit", false);
            }
            arguments.append(value);
        }

        /** 只恢复已知 strict null 占位；未知 Tool 与参数错误由 Runner 回传可恢复结果。 */
        ModelPort.ToolCallReady finish(Function<String, ToolSpec> toolLookup) {
            if (id == null || id.isEmpty() || name == null || name.isEmpty()) {
                throw protocol("OpenAI Chat Tool metadata is incomplete");
            }
            JsonNode wire = parseArguments(arguments.toString());
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
                    id, name, ProviderJsonValues.toObject(restored), index);
        }

        /** 返回冻结的 Provider Tool 顺序。 */
        int index() {
            return index;
        }

        /** 解析完整 arguments JSON object，不接纳数组、标量或尾随数据。 */
        private static JsonNode parseArguments(String value) {
            try {
                JsonNode parsed = AbstractStreamingModelAdapter.JSON.readTree(value);
                if (parsed == null || !parsed.isObject()) throw new IOException("not object");
                return parsed;
            } catch (IOException | RuntimeException failure) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENTS", "OpenAI Chat Tool arguments are invalid JSON", false);
            }
        }

    }
}
