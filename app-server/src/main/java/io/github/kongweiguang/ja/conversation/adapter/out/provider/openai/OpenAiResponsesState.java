// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderJsonValues;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderSseReader;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderStreamResult;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort.FinishReason;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 负责公开投影、终态校验与 Tool 校验的 Responses 事件状态机。
 */
final class OpenAiResponsesState {
    private final Map<String, ToolAccumulator> tools = new LinkedHashMap<>();
    private final Map<String, EmittedTool> emittedItems = new LinkedHashMap<>();
    private final Map<String, EmittedTool> emittedCalls = new LinkedHashMap<>();
    private final Map<TextKey, TextAccumulator> texts = new LinkedHashMap<>();
    private final Set<String> doneItems = new HashSet<>();
    private String responseId;
    private FinishReason finishReason;
    private ModelUsage usage;
    private long lastSequence = -1;
    private int nextOrdinal;
    private boolean terminal;

    /**
     * SSE 完成名称和类型校验后归约事件，并返回待提交效果而不执行外部 IO。
     */
    List<ModelPort.ModelEvent> reduce(
            ProviderSseReader.Event event, Function<String, ToolSpec> toolLookup) {
        if (terminal) throw protocol("OpenAI emitted data after a terminal event");
        JsonNode data = event.data();
        List<ModelPort.ModelEvent> effects = new ArrayList<>();
        if (!"error".equals(event.name())) checkSequence(data);
        checkOptionalIndex(data, "output_index");
        checkOptionalIndex(data, "content_index");
        checkOptionalIndex(data, "summary_index");
        switch (event.name()) {
            case "response.created", "response.queued", "response.in_progress" ->
                    capture(requiredObject(data, "response"));
            case "response.output_text.delta" -> textDelta(data, TextKind.OUTPUT_TEXT, effects);
            case "response.output_text.done" -> textDone(data, TextKind.OUTPUT_TEXT);
            case "response.refusal.delta" -> textDelta(data, TextKind.REFUSAL, effects);
            case "response.refusal.done" -> textDone(data, TextKind.REFUSAL);
            case "response.reasoning_summary_text.delta" -> effects.add(
                    new ModelPort.ReasoningSummaryDelta(requiredText(data, "delta", false)));
            case "response.output_item.added" -> itemAdded(requiredObject(data, "item"));
            case "response.function_call_arguments.delta" -> tool(requiredText(data, "item_id", false))
                    .append(requiredText(data, "delta", true));
            case "response.function_call_arguments.done" -> {
                ToolAccumulator tool = tool(requiredText(data, "item_id", false));
                String name = optionalText(data, "name");
                // 当前 Responses 流可以省略这个冗余字段；如果存在，它仍必须与 item.added 一致，
                // 后续 output_item.done 再执行权威的完整回放校验。
                if (name != null && !tool.name().equals(name)) {
                    throw protocol("OpenAI changed a function-call name");
                }
                tool.finish(requiredText(data, "arguments", true));
                emitTool(tool, toolLookup, effects);
            }
            case "response.output_item.done" -> itemDone(
                    requiredObject(data, "item"), toolLookup, effects);
            case "response.completed" -> completed(requiredObject(data, "response"));
            case "response.incomplete" -> incomplete(requiredObject(data, "response"));
            case "response.failed", "error" -> providerError(data);
            case "response.reasoning_summary_text.done", "response.reasoning_summary_part.added",
                 "response.reasoning_summary_part.done", "response.reasoning_text.delta",
                 "response.reasoning_text.done", "response.content_part.added",
                 "response.content_part.done" -> {
                // 协议记载的非语义事件不携带 Ja 状态；私有推理直接丢弃。
            }
            default -> throw protocol("OpenAI emitted an unsupported Responses event");
        }
        return List.copyOf(effects);
    }

    /**
     * 流干净结束后生成最终结果和 usage 效果；响应身份只用于本次流内对账，不发布为远端续传
     * 状态，下一轮必须从 Ja 的权威历史重建完整原生 input。
     */
    ProviderStreamResult finish() {
        if (!terminal || finishReason == null || !tools.isEmpty()) {
            throw new ProviderProtocolException(
                    "STREAM_TRUNCATED", "OpenAI stream ended without explicit completion", true);
        }
        List<ModelPort.ModelEvent> effects = usage == null
                ? List.of() : List.of(new ModelPort.UsageEvent(usage));
        return new ProviderStreamResult(
                new ModelPort.ModelOutcome(finishReason, null, usage), effects);
    }

    /** 捕获流内响应身份以拒绝同一次物理请求中途切换对象，不把该身份带入下一轮。 */
    private void capture(JsonNode response) {
        String id = requiredText(response, "id", false);
        if (!"response".equals(requiredText(response, "object", false))
            || !response.path("output").isArray()
            || !response.path("tools").isArray()
            || !response.path("parallel_tool_calls").isBoolean()
            || !response.path("created_at").isNumber()
            || optionalText(response, "model") == null) {
            throw new ProviderProtocolException(
                    "PROVIDER_FIELD", "OpenAI response shape is invalid", false);
        }
        if (responseId != null && !responseId.equals(id)) {
            throw new ProviderProtocolException(
                    "PROVIDER_FIELD", "OpenAI changed response identity", false);
        }
        responseId = id;
    }

    /**
     * 按 item id 登记函数元数据，并忽略已支持的消息及推理输出条目。
     */
    private void itemAdded(JsonNode item) {
        String type = requiredText(item, "type", false);
        if ("message".equals(type) || "reasoning".equals(type)) return;
        if (!"function_call".equals(type)) {
            throw new ProviderProtocolException(
                    "OPENAI_ITEM", "OpenAI emitted an unsupported output item", false);
        }
        String itemId = requiredText(item, "id", false);
        ToolAccumulator accumulator = new ToolAccumulator(
                itemId, requiredText(item, "call_id", false),
                requiredText(item, "name", false), nextOrdinal++);
        if (tools.putIfAbsent(itemId, accumulator) != null) {
            throw new ProviderProtocolException(
                    "TOOL_DUPLICATE", "OpenAI repeated a function-call item", false);
        }
        String arguments = optionalText(item, "arguments");
        if (arguments != null && !arguments.isEmpty()) accumulator.seed(arguments);
    }

    /**
     * 冗余 arguments-done 缺失时，以权威 completed item 完成 Tool 对账。
     */
    private void itemDone(JsonNode item, Function<String, ToolSpec> toolLookup,
                          List<ModelPort.ModelEvent> effects) {
        String type = requiredText(item, "type", false);
        if ("message".equals(type) || "reasoning".equals(type)) return;
        if (!"function_call".equals(type)) {
            throw new ProviderProtocolException(
                    "OPENAI_ITEM", "OpenAI emitted an unsupported output item", false);
        }
        String itemId = requiredText(item, "id", false);
        ToolAccumulator accumulator = tools.get(itemId);
        if (accumulator == null) {
            EmittedTool emitted = emittedItems.get(itemId);
            if (emitted == null) throw protocol("OpenAI completed a function call without metadata");
            verifyCompletedCall(emitted, item);
            markItemDone(itemId);
            return;
        }
        if (!accumulator.callId().equals(requiredText(item, "call_id", false))
            || !accumulator.name().equals(requiredText(item, "name", false))) {
            throw protocol("OpenAI changed function-call metadata");
        }
        String arguments = optionalText(item, "arguments");
        if (arguments != null && !arguments.isEmpty()) accumulator.finish(arguments);
        emitTool(accumulator, toolLookup, effects);
        markItemDone(itemId);
    }

    /**
     * Tool 调用可观察前只恢复已知 strict 参数；未知 Tool 与参数错误交给 Runner 回传 ToolResult。
     */
    private void emitTool(ToolAccumulator tool, Function<String, ToolSpec> toolLookup,
                          List<ModelPort.ModelEvent> effects) {
        if (tool.emitted()) return;
        JsonNode wireArguments = parseArguments(tool.arguments());
        ToolSpec spec = toolLookup.apply(tool.name());
        JsonNode arguments = wireArguments;
        if (spec != null) {
            JsonNode sourceSchema = ProviderJsonValues.toNode(spec.inputSchema());
            try {
                arguments = OpenAiStrictArgumentRestorer.restore(sourceSchema, wireArguments);
            } catch (IllegalArgumentException ignored) {
                // 原始对象仍需进入 Runner，后者会形成可恢复失败且绝不执行非法参数。
            }
        }
        JsonObject values = ProviderJsonValues.toObject(arguments);
        if (emittedItems.containsKey(tool.itemId()) || emittedCalls.containsKey(tool.callId())) {
            throw new ProviderProtocolException(
                    "TOOL_DUPLICATE", "OpenAI repeated a function-call identity", false);
        }
        effects.add(new ModelPort.ToolCallReady(
                tool.callId(), tool.name(), values, tool.ordinal()));
        EmittedTool emitted = new EmittedTool(
                tool.itemId(), tool.callId(), tool.name(), wireArguments);
        emittedItems.put(tool.itemId(), emitted);
        emittedCalls.put(tool.callId(), emitted);
        tool.markEmitted();
        tools.remove(tool.itemId());
    }

    /**
     * 映射完整响应，同时执行严格 usage 计量和 Tool 状态迁移。
     */
    private void completed(JsonNode response) {
        requireFirstTerminal();
        capture(response);
        String status = optionalText(response, "status");
        if (status != null && !"completed".equals(status)) {
            throw new ProviderProtocolException(
                    "RESPONSE_STATUS", "OpenAI response status is not completed", false);
        }
        validateFinalOutput(response, true);
        usage = response.hasNonNull("usage") ? usage(requiredObject(response, "usage")) : null;
        finishReason = emittedCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
        terminal = true;
    }

    /**
     * 只把达到最大输出 Token 导致的未完成接纳为正常 Agent Loop 结果。
     */
    private void incomplete(JsonNode response) {
        requireFirstTerminal();
        capture(response);
        JsonNode details = response.path("incomplete_details");
        if (!details.isObject() || !"max_output_tokens".equals(details.path("reason").textValue())) {
            providerError(response);
        }
        String status = optionalText(response, "status");
        if (status != null && !"incomplete".equals(status)) {
            throw new ProviderProtocolException(
                    "RESPONSE_STATUS", "OpenAI response status is not incomplete", false);
        }
        validateFinalOutput(response, false);
        usage = response.hasNonNull("usage") ? usage(requiredObject(response, "usage")) : null;
        finishReason = FinishReason.MAX_OUTPUT_TOKENS;
        terminal = true;
    }

    /**
     * 只映射稳定的强类型上下文代码，其余 Provider 错误保持通用分类。
     */
    private static void providerError(JsonNode value) {
        String code = value.path("error").path("code").textValue();
        if (code == null) code = value.path("code").textValue();
        if (code == null) code = value.path("response").path("error").path("code").textValue();
        if ("context_length_exceeded".equals(code)) {
            throw new ModelPort.ContextOverflowException(null);
        }
        throw new ProviderProtocolException(
                "PROVIDER_ERROR", "OpenAI reported a stream error", false);
    }

    /**
     * 在重复终态事件覆盖 outcome 或 usage 前拒绝它。
     */
    private void requireFirstTerminal() {
        if (terminal) throw protocol("OpenAI repeated a terminal event");
    }

    /**
     * 转换 Token 计数器，同时保持非负值与总量不变量。
     */
    private static ModelUsage usage(JsonNode value) {
        try {
            long input = requiredLong(value, "input_tokens");
            long output = requiredLong(value, "output_tokens");
            long expectedTotal = Math.addExact(input, output);
            long total = requiredLong(value, "total_tokens");
            if (total != expectedTotal) throw new IllegalArgumentException("invalid usage");
            return new ModelUsage(input, output, total);
        } catch (ArithmeticException | IllegalArgumentException failure) {
            throw new ProviderProtocolException("USAGE", "OpenAI usage is invalid", false);
        }
    }

    /**
     * 解析完整 Tool JSON，且不在诊断中保留 Provider 可控内容。
     */
    private static JsonNode parseArguments(String value) {
        try {
            JsonNode arguments = AbstractStreamingModelAdapter.JSON.readTree(value);
            if (arguments == null || !arguments.isObject()) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENTS", "OpenAI Tool arguments must be a JSON object", false);
            }
            return arguments;
        } catch (ProviderProtocolException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new ProviderProtocolException(
                    "PROVIDER_JSON", "OpenAI Tool arguments are invalid JSON", false);
        }
    }

    /**
     * 校验完成条目不能篡改已经发布的 Tool 元数据或参数。
     */
    private static void verifyCompletedCall(EmittedTool emitted, JsonNode call) {
        if (!emitted.callId().equals(requiredText(call, "call_id", false))
            || !emitted.name().equals(requiredText(call, "name", false))
            || !emitted.arguments().equals(parseArguments(requiredText(call, "arguments", true)))) {
            throw new ProviderProtocolException(
                    "TOOL_SEQUENCE", "OpenAI changed a completed function call", false);
        }
    }

    /**
     * 拒绝重复完成条目，但允许 arguments-done 先于 item-done。
     */
    private void markItemDone(String itemId) {
        if (!doneItems.add(itemId)) {
            throw new ProviderProtocolException(
                    "TOOL_DUPLICATE", "OpenAI repeated function-call completion", false);
        }
    }

    /**
     * 将权威最终响应与每个流式文本及 Tool 条目逐一对账。
     */
    private void validateFinalOutput(JsonNode response, boolean allowTools) {
        if (!tools.isEmpty() || doneItems.size() != emittedItems.size()) {
            throw new ProviderProtocolException(
                    "TOOL_SEQUENCE", "OpenAI ended before completing function-call items", false);
        }
        JsonNode output = response.get("output");
        if (output == null || !output.isArray()) {
            throw new ProviderProtocolException(
                    "PROVIDER_FIELD", "OpenAI omitted final output", false);
        }
        Set<String> finalCalls = new HashSet<>();
        Set<TextKey> finalTexts = new HashSet<>();
        for (int outputIndex = 0; outputIndex < output.size(); outputIndex++) {
            JsonNode item = output.get(outputIndex);
            String type = requiredText(item, "type", false);
            if ("message".equals(type)) {
                validateFinalMessage(item, outputIndex, finalTexts);
                continue;
            }
            if ("reasoning".equals(type)) continue;
            if (!"function_call".equals(type)) {
                throw new ProviderProtocolException(
                        "OPENAI_ITEM", "OpenAI completed with an unsupported output item", false);
            }
            String callId = requiredText(item, "call_id", false);
            EmittedTool emitted = emittedCalls.get(callId);
            if (!allowTools || emitted == null || !finalCalls.add(callId)) {
                throw new ProviderProtocolException(
                        "TOOL_SEQUENCE", "OpenAI final Tool output disagrees with its stream", false);
            }
            verifyCompletedCall(emitted, item);
        }
        if (finalCalls.size() != emittedCalls.size()) {
            throw new ProviderProtocolException(
                    "TOOL_SEQUENCE", "OpenAI final Tool output is incomplete", false);
        }
        if (!finalTexts.equals(texts.keySet())) {
            throw protocol("OpenAI final text output disagrees with its stream");
        }
    }

    /**
     * 按不可变 output/content 坐标聚合公开文本，并返回对应语义效果。
     */
    private void textDelta(JsonNode event, TextKind kind, List<ModelPort.ModelEvent> effects) {
        TextKey key = textKey(event, kind);
        String delta = requiredText(event, "delta", false);
        texts.computeIfAbsent(key, ignored -> new TextAccumulator()).append(delta);
        effects.add(new ModelPort.TextDelta(delta));
    }

    /**
     * 终态对账前要求每个 done 事件证明精确的拼接文本。
     */
    private void textDone(JsonNode event, TextKind kind) {
        TextKey key = textKey(event, kind);
        String field = kind == TextKind.OUTPUT_TEXT ? "text" : "refusal";
        texts.computeIfAbsent(key, ignored -> new TextAccumulator())
                .finish(requiredText(event, field, true));
    }

    /**
     * 仅在共享数值校验成功后构造一个有界文本坐标。
     */
    private static TextKey textKey(JsonNode event, TextKind kind) {
        return new TextKey(requiredText(event, "item_id", false),
                requiredIndex(event, "output_index"), requiredIndex(event, "content_index"), kind);
    }

    /**
     * 使用所有对应 done 事件校验一条最终 assistant 消息。
     */
    private void validateFinalMessage(JsonNode message, int outputIndex, Set<TextKey> finalTexts) {
        String itemId = requiredText(message, "id", false);
        if (!"assistant".equals(requiredText(message, "role", false))) {
            throw protocol("OpenAI final message role is invalid");
        }
        JsonNode content = message.get("content");
        if (content == null || !content.isArray()) {
            throw protocol("OpenAI final message content is invalid");
        }
        for (int contentIndex = 0; contentIndex < content.size(); contentIndex++) {
            JsonNode part = content.get(contentIndex);
            String type = requiredText(part, "type", false);
            TextKind kind;
            String field;
            if ("output_text".equals(type)) {
                kind = TextKind.OUTPUT_TEXT;
                field = "text";
            } else if ("refusal".equals(type)) {
                kind = TextKind.REFUSAL;
                field = "refusal";
            } else {
                throw protocol("OpenAI final message content is unsupported");
            }
            TextKey key = new TextKey(itemId, outputIndex, contentIndex, kind);
            TextAccumulator streamed = texts.get(key);
            if (!finalTexts.add(key) || streamed == null || !streamed.done()
                || !streamed.value().equals(requiredText(part, field, true))) {
                throw protocol("OpenAI final text output disagrees with its stream");
            }
        }
    }

    /**
     * 只查找既有元数据，避免参数片段凭空制造 Tool 调用。
     */
    private ToolAccumulator tool(String itemId) {
        ToolAccumulator tool = tools.get(itemId);
        if (tool == null) throw protocol("OpenAI emitted arguments before function-call metadata");
        return tool;
    }

    /**
     * 读取必填对象，不允许 Jackson 类型强制转换。
     */
    private static JsonNode requiredObject(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) {
            throw new ProviderProtocolException(
                    "PROVIDER_FIELD", "OpenAI omitted a required object", false);
        }
        return value;
    }

    /**
     * 读取一个必填文本字段，并按调用方约束决定是否允许空值。
     */
    private static String requiredText(JsonNode root, String field, boolean allowEmpty) {
        String value = optionalText(root, field);
        if (value == null || !allowEmpty && value.isEmpty()) {
            throw new ProviderProtocolException(
                    "PROVIDER_FIELD", "OpenAI omitted a required field", false);
        }
        return value;
    }

    /**
     * 读取一个可选文本值，不转换其它 JSON 类型。
     */
    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    /**
     * 读取非负 long 整数，不接受浮点数或字符串强制转换。
     */
    private static long requiredLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
            || value.longValue() < 0) {
            throw new IllegalArgumentException("invalid usage");
        }
        return value.longValue();
    }

    /**
     * 在状态变更前要求流序号非负且严格递增。
     */
    private void checkSequence(JsonNode event) {
        JsonNode value = event.get("sequence_number");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw protocol("OpenAI omitted a stream sequence");
        }
        long sequence = value.longValue();
        if (sequence < 0 || sequence <= lastSequence) {
            throw protocol("OpenAI stream sequence is invalid");
        }
        lastSequence = sequence;
    }

    /**
     * 在可选 output/content/summary 索引影响聚合前限制其边界。
     */
    private static void checkOptionalIndex(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null) return;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
            || value.intValue() < 0 || value.intValue() > 1_023) {
            throw protocol("OpenAI stream index is invalid");
        }
    }

    /**
     * 返回一个必填有界流索引，不允许 Jackson 数值强制转换。
     */
    private static int requiredIndex(JsonNode event, String field) {
        checkOptionalIndex(event, field);
        JsonNode value = event.get(field);
        if (value == null) throw protocol("OpenAI stream index is invalid");
        return value.intValue();
    }

    /**
     * 创建一个稳定且不含载荷的 OpenAI 事件顺序失败。
     */
    private static ProviderProtocolException protocol(String message) {
        return new ProviderProtocolException("OPENAI_EVENT", message, false);
    }

    /**
     * 只保留请求内已校验的 Tool 元数据，用于最终对账。
     */
    private record EmittedTool(String itemId, String callId, String name, JsonNode arguments) {
    }

    /**
     * 不可变文本坐标防止不同输出部分的 delta 被混合。
     */
    private record TextKey(String itemId, int outputIndex, int contentIndex, TextKind kind) {
    }

    /**
     * 在最终响应对账时区分普通助手文本和拒绝文本。
     */
    private enum TextKind {
        /**
         * 普通助手输出文本。
         */
        OUTPUT_TEXT,

        /**
         * Provider 明确拒绝请求时的公开文本。
         */
        REFUSAL
    }

    /**
     * 请求内累加器要求恰好一个 done 事件以及精确的最终值。
     */
    private static final class TextAccumulator {
        private final StringBuilder value = new StringBuilder();
        private boolean done;

        /**
         * 仅当 Provider 尚未关闭文本部分时追加 delta。
         */
        void append(String delta) {
            if (done) throw protocol("OpenAI emitted text after its done event");
            value.append(delta);
        }

        /**
         * 仅当 done 载荷与此前全部 delta 精确一致时关闭该部分。
         */
        void finish(String finalValue) {
            if (done || !value.toString().equals(finalValue)) {
                throw protocol("OpenAI text done event disagrees with its deltas");
            }
            done = true;
        }

        /**
         * 提供已经对账的请求内文本，用于权威最终响应校验。
         */
        String value() {
            return value.toString();
        }

        /**
         * 返回是否已有明确 done 事件关闭该文本部分。
         */
        boolean done() {
            return done;
        }
    }

    /**
     * 有界函数累加器在交错 delta 流之间保持 Provider 顺序。
     */
    private static final class ToolAccumulator {
        private final String itemId;
        private final String callId;
        private final String name;
        private final int ordinal;
        private final StringBuilder arguments = new StringBuilder();
        private boolean emitted;

        /**
         * 接受任何参数片段前捕获不可变元数据。
         */
        ToolAccumulator(String itemId, String callId, String name, int ordinal) {
            this.itemId = itemId;
            this.callId = callId;
            this.name = name;
            this.ordinal = ordinal;
        }

        /**
         * 追加一个有界片段，不允许无界内存分配。
         */
        void append(String value) {
            if ((long) arguments.length() + value.length() > 4_000_000L) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENT_LIMIT", "OpenAI Tool arguments exceed the limit", false);
            }
            arguments.append(value);
        }

        /**
         * 在固定上限内、任何 delta 到达前写入 Provider 提供的初始参数。
         */
        void seed(String value) {
            replace(value);
        }

        /**
         * 要求权威 done 值与已经聚合的参数片段一致。
         */
        void finish(String value) {
            if (!arguments.isEmpty() && !arguments.toString().equals(value)) {
                throw new ProviderProtocolException(
                        "TOOL_SEQUENCE", "OpenAI Tool argument fragments disagree", false);
            }
            replace(value);
        }

        /**
         * 校验分配上限后才替换参数缓冲区。
         */
        private void replace(String value) {
            if (value.length() > 4_000_000L) {
                throw new ProviderProtocolException(
                        "TOOL_ARGUMENT_LIMIT", "OpenAI Tool arguments exceed the limit", false);
            }
            arguments.setLength(0);
            arguments.append(value);
        }

        /**
         * 返回只用于聚合查找的 Provider 条目身份。
         */
        String itemId() {
            return itemId;
        }

        /**
         * 返回校验后发布的函数调用身份。
         */
        String callId() {
            return callId;
        }

        /**
         * 返回用于精确目录查找的冻结 Tool 名称。
         */
        String name() {
            return name;
        }

        /**
         * 返回确定性的 Provider 输出序号。
         */
        int ordinal() {
            return ordinal;
        }

        /**
         * 返回由当前全部片段组成的完整参数 JSON。
         */
        String arguments() {
            return arguments.toString();
        }

        /**
         * 返回该累加器是否已经发布唯一语义 Tool 事件。
         */
        boolean emitted() {
            return emitted;
        }

        /**
         * 在 sink 接纳后标记单向发布迁移。
         */
        void markEmitted() {
            emitted = true;
        }
    }
}
