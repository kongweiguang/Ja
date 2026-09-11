// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderJsonValues;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderReasoningSupport;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderSseReader;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ProviderStreamResult;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
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
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 负责公开投影、终态校验与 Tool 校验的 Responses 事件状态机。
 */
final class OpenAiResponsesState {
    private final Map<String, ToolAccumulator> tools = new LinkedHashMap<>();
    private final Map<String, EmittedTool> emittedItems = new LinkedHashMap<>();
    private final Map<String, EmittedTool> emittedCalls = new LinkedHashMap<>();
    private final Map<TextKey, TextAccumulator> texts = new LinkedHashMap<>();
    private final Map<String, ReasoningAccumulator> reasoning = new LinkedHashMap<>();
    private final Map<Integer, ItemSlot> itemSlots = new TreeMap<>();
    private final Map<Integer, List<ModelPort.ModelEvent>> pendingEffects = new TreeMap<>();
    private final ModelPort.ModelConfiguration configuration;
    private final Set<String> doneItems = new HashSet<>();
    private String responseId;
    private FinishReason finishReason;
    private ModelUsage usage;
    private long lastSequence = -1;
    private int nextOrdinal;
    private int nextOutputIndex;
    private boolean terminal;

    /**
     * 绑定本次 Responses 请求身份，令 reasoning 原生块只能回传给同一 provider/model/endpoint。
     */
    OpenAiResponsesState(ModelPort.ModelConfiguration configuration) {
        this.configuration = java.util.Objects.requireNonNull(configuration, "configuration");
    }

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
            case "response.output_text.delta" -> textDelta(data, TextKind.OUTPUT_TEXT);
            case "response.output_text.done" -> textDone(data, TextKind.OUTPUT_TEXT);
            case "response.refusal.delta" -> textDelta(data, TextKind.REFUSAL);
            case "response.refusal.done" -> textDone(data, TextKind.REFUSAL);
            case "response.reasoning_summary_text.delta" -> reasoningSummaryDelta(data, effects);
            case "response.reasoning_summary_text.done" -> reasoningSummaryDone(data, effects);
            case "response.reasoning_summary_part.added" -> reasoningSummaryPartAdded(data);
            case "response.reasoning_summary_part.done" -> reasoningSummaryPartDone(data, effects);
            case "response.reasoning_text.delta" -> reasoningTextDelta(data, effects);
            case "response.reasoning_text.done" -> reasoningTextDone(data, effects);
            case "response.output_item.added" -> itemAdded(
                    requiredObject(data, "item"), requiredIndex(data, "output_index"));
            case "response.function_call_arguments.delta" -> tool(
                    requiredText(data, "item_id", false)).append(requiredText(data, "delta", true));
            case "response.function_call_arguments.done" -> {
                ToolAccumulator tool = tool(requiredText(data, "item_id", false));
                String name = optionalText(data, "name");
                // 当前 Responses 流可以省略这个冗余字段；如果存在，它仍必须与 item.added 一致，
                // 后续 output_item.done 再执行权威的完整回放校验。
                if (name != null && !tool.name().equals(name)) {
                    throw protocol("OpenAI changed a function-call name");
                }
                tool.finish(requiredText(data, "arguments", true));
                emitTool(tool, requiredIndex(data, "output_index"), toolLookup);
            }
            case "response.output_item.done" -> itemDone(
                    requiredObject(data, "item"), requiredIndex(data, "output_index"), toolLookup);
            case "response.completed" -> completed(requiredObject(data, "response"), effects);
            case "response.incomplete" -> incomplete(requiredObject(data, "response"), effects);
            case "response.failed", "error" -> providerError(data);
            case "response.content_part.added", "response.content_part.done" -> {
                // content part 本身只宣布结构；文本和 refusal 的 delta/done 承担语义校验。
            }
            default -> throw protocol("OpenAI emitted an unsupported Responses event");
        }
        effects.addAll(flushOrderedEffects());
        return List.copyOf(effects);
    }

    /**
     * 流干净结束后生成最终结果和 usage 效果；响应身份只用于本次流内对账，不发布为远端续传
     * 状态，下一轮必须从 Ja 的权威历史重建完整原生 input。
     */
    ProviderStreamResult finish() {
        if (!terminal || finishReason == null || !tools.isEmpty()
                || !pendingEffects.isEmpty() || !itemSlots.isEmpty()) {
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
    private void itemAdded(JsonNode item, int outputIndex) {
        observeIndex(outputIndex);
        itemSlots.get(outputIndex).explicitItem = true;
        String type = requiredText(item, "type", false);
        if ("message".equals(type)) return;
        if ("reasoning".equals(type)) {
            reasoningFor(requiredText(item, "id", false), outputIndex).acceptAdded(item);
            return;
        }
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
    private void itemDone(JsonNode item, int outputIndex,
                          Function<String, ToolSpec> toolLookup) {
        observeIndex(outputIndex);
        String type = requiredText(item, "type", false);
        if ("message".equals(type)) {
            completeIndex(outputIndex);
            return;
        }
        if ("reasoning".equals(type)) {
            ReasoningAccumulator accumulator = reasoningFor(requiredText(item, "id", false), outputIndex);
            accumulator.acceptDone(item);
            if (accumulator.readyForHistory()) {
                queueEffect(outputIndex, accumulator.emitBlock());
                completeIndex(outputIndex);
            }
            return;
        }
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
            completeIndex(outputIndex);
            return;
        }
        if (!accumulator.callId().equals(requiredText(item, "call_id", false))
            || !accumulator.name().equals(requiredText(item, "name", false))) {
            throw protocol("OpenAI changed function-call metadata");
        }
        String arguments = optionalText(item, "arguments");
        if (arguments != null && !arguments.isEmpty()) accumulator.finish(arguments);
        emitTool(accumulator, outputIndex, toolLookup);
        markItemDone(itemId);
        completeIndex(outputIndex);
    }

    /**
     * Tool 调用可观察前只恢复已知 strict 参数；未知 Tool 与参数错误交给 Runner 回传 ToolResult。
     */
    private void emitTool(ToolAccumulator tool, int outputIndex,
                          Function<String, ToolSpec> toolLookup) {
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
        queueEffect(outputIndex, new ModelPort.ToolCallReady(
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
    private void completed(JsonNode response, List<ModelPort.ModelEvent> effects) {
        requireFirstTerminal();
        capture(response);
        String status = optionalText(response, "status");
        if (status != null && !"completed".equals(status)) {
            throw new ProviderProtocolException(
                    "RESPONSE_STATUS", "OpenAI response status is not completed", false);
        }
        validateFinalOutput(response, true, effects);
        usage = response.hasNonNull("usage") ? usage(requiredObject(response, "usage")) : null;
        finishReason = emittedCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
        effects.addAll(flushOrderedEffects());
        if (!itemSlots.isEmpty() || !pendingEffects.isEmpty()) {
            throw protocol("OpenAI final output item order is incomplete");
        }
        terminal = true;
    }

    /**
     * 只把达到最大输出 Token 导致的未完成接纳为正常 Agent Loop 结果。
     */
    private void incomplete(JsonNode response, List<ModelPort.ModelEvent> effects) {
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
        validateFinalOutput(response, false, effects);
        usage = response.hasNonNull("usage") ? usage(requiredObject(response, "usage")) : null;
        finishReason = FinishReason.MAX_OUTPUT_TOKENS;
        effects.addAll(flushOrderedEffects());
        if (!itemSlots.isEmpty() || !pendingEffects.isEmpty()) {
            throw protocol("OpenAI final output item order is incomplete");
        }
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
    private void validateFinalOutput(
            JsonNode response, boolean allowTools, List<ModelPort.ModelEvent> effects) {
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
            observeIndex(outputIndex);
            String type = requiredText(item, "type", false);
            if ("message".equals(type)) {
                validateFinalMessage(item, outputIndex, finalTexts);
                completeIndex(outputIndex);
                continue;
            }
            if ("reasoning".equals(type)) {
                reconcileTerminalReasoning(item, outputIndex, effects);
                continue;
            }
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
            completeIndex(outputIndex);
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
    private void textDelta(JsonNode event, TextKind kind) {
        TextKey key = textKey(event, kind);
        String delta = requiredText(event, "delta", false);
        texts.computeIfAbsent(key, ignored -> new TextAccumulator()).append(delta);
        int outputIndex = key.outputIndex();
        observeIndex(outputIndex);
        queueEffect(outputIndex, new ModelPort.TextDelta(delta));
    }

    /**
     * 终态对账前要求每个 done 事件证明精确的拼接文本。
     */
    private void textDone(JsonNode event, TextKind kind) {
        TextKey key = textKey(event, kind);
        String field = kind == TextKind.OUTPUT_TEXT ? "text" : "refusal";
        texts.computeIfAbsent(key, ignored -> new TextAccumulator())
                .finish(requiredText(event, field, true));
        int outputIndex = key.outputIndex();
        observeIndex(outputIndex);
        ItemSlot slot = itemSlots.get(outputIndex);
        if (slot != null && !slot.explicitItem()) completeIndex(outputIndex);
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
     * 累积公开 summary delta，并按原生 output slot 排队；同一 item 的私有 reasoning_text 由累加器去重。
     */
    private void reasoningSummaryDelta(
            JsonNode event, List<ModelPort.ModelEvent> effects) {
        int outputIndex = requiredIndex(event, "output_index");
        ReasoningAccumulator accumulator = reasoningFor(
                requiredText(event, "item_id", false), outputIndex);
        accumulator.appendSummary(requiredIndex(event, "summary_index"),
                requiredText(event, "delta", true));
        queuePublicReasoning(outputIndex, accumulator, effects);
    }

    /**
     * 用 summary_text.done 补齐未到达的 delta；已完整拼接的内容不会再次发布。
     */
    private void reasoningSummaryDone(
            JsonNode event, List<ModelPort.ModelEvent> effects) {
        int outputIndex = requiredIndex(event, "output_index");
        ReasoningAccumulator accumulator = reasoningFor(
                requiredText(event, "item_id", false), outputIndex);
        accumulator.finishSummary(requiredIndex(event, "summary_index"),
                requiredText(event, "text", true));
        queuePublicReasoning(outputIndex, accumulator, effects);
    }

    /**
     * 登记 summary part 的段落边界；文本在 done 或 terminal item 中确认后才进入公开事件。
     */
    private void reasoningSummaryPartAdded(JsonNode event) {
        int outputIndex = requiredIndex(event, "output_index");
        ReasoningAccumulator accumulator = reasoningFor(
                requiredText(event, "item_id", false), outputIndex);
        JsonNode part = requiredObject(event, "part");
        accumulator.acceptSummaryPart(requiredIndex(event, "summary_index"), part);
    }

    /**
     * 处理 part.done 携带的最终文本；兼容仅在 part.done 提供文本的网关实现。
     */
    private void reasoningSummaryPartDone(
            JsonNode event, List<ModelPort.ModelEvent> effects) {
        int outputIndex = requiredIndex(event, "output_index");
        ReasoningAccumulator accumulator = reasoningFor(
                requiredText(event, "item_id", false), outputIndex);
        int summaryIndex = requiredIndex(event, "summary_index");
        JsonNode part = event.get("part");
        String text = part != null && part.isObject()
                ? optionalText(part, "text") : optionalText(event, "text");
        if (text != null) accumulator.finishSummary(summaryIndex, text);
        queuePublicReasoning(outputIndex, accumulator, effects);
    }

    /**
     * 接纳兼容接口公开的 reasoning_text 增量；其 canonical candidate 与 summary 共用展示账本。
     */
    private void reasoningTextDelta(
            JsonNode event, List<ModelPort.ModelEvent> effects) {
        int outputIndex = requiredIndex(event, "output_index");
        ReasoningAccumulator accumulator = reasoningFor(
                requiredText(event, "item_id", false), outputIndex);
        accumulator.appendReasoningText(requiredText(event, "delta", true));
        queuePublicReasoning(outputIndex, accumulator, effects);
    }

    /**
     * 用 reasoning_text.done 补齐兼容端遗漏的增量，但不把同一段内容和 summary 重复展示。
     */
    private void reasoningTextDone(
            JsonNode event, List<ModelPort.ModelEvent> effects) {
        int outputIndex = requiredIndex(event, "output_index");
        ReasoningAccumulator accumulator = reasoningFor(
                requiredText(event, "item_id", false), outputIndex);
        String text = requiredText(event, "text", true);
        accumulator.finishReasoningText(text);
        queuePublicReasoning(outputIndex, accumulator, effects);
    }

    /**
     * 把 summary/公开 reasoning text 的 canonical candidate 只追加未展示后缀，并保持段落分隔。
     */
    private void queuePublicReasoning(
            int outputIndex, ReasoningAccumulator accumulator,
            List<ModelPort.ModelEvent> immediateEffects) {
        String suffix = accumulator.nextPublicSuffix();
        if (!suffix.isEmpty()) {
            ModelPort.ReasoningSummaryDelta effect = new ModelPort.ReasoningSummaryDelta(suffix);
            if (outputIndex < nextOutputIndex) immediateEffects.add(effect);
            else queueEffect(outputIndex, effect);
        }
    }

    /**
     * 以 item id 关联交错事件，并拒绝同一原生 item 改写 output_index。
     */
    private ReasoningAccumulator reasoningFor(String itemId, int outputIndex) {
        ReasoningAccumulator accumulator = reasoning.get(itemId);
        if (accumulator == null) {
            accumulator = new ReasoningAccumulator(itemId, outputIndex);
            reasoning.put(itemId, accumulator);
        } else if (accumulator.outputIndex() != outputIndex) {
            throw protocol("OpenAI reasoning item changed output index");
        }
        observeIndex(outputIndex);
        return accumulator;
    }

    /**
     * 在 response.completed/incomplete 的权威 output 中回补完整 reasoning item 和 summary。
     */
    private void reconcileTerminalReasoning(
            JsonNode item, int outputIndex, List<ModelPort.ModelEvent> immediateEffects) {
        String itemId = requiredText(item, "id", false);
        ReasoningAccumulator accumulator = reasoningFor(itemId, outputIndex);
        accumulator.acceptTerminal(item);
        JsonNode summary = item.get("summary");
        if (summary != null && !summary.isNull()) {
            if (!summary.isArray()) throw protocol("OpenAI reasoning summary is invalid");
            for (int summaryIndex = 0; summaryIndex < summary.size(); summaryIndex++) {
                JsonNode part = summary.get(summaryIndex);
                if (!part.isObject() || !"summary_text".equals(requiredText(part, "type", false))) {
                    throw protocol("OpenAI reasoning summary part is invalid");
                }
                accumulator.finishSummary(summaryIndex, requiredText(part, "text", true));
            }
        }
        queuePublicReasoning(outputIndex, accumulator, immediateEffects);
        ModelPort.ModelEvent blockEffect = accumulator.terminalBlockEffect();
        if (blockEffect != null) {
            if (outputIndex < nextOutputIndex) immediateEffects.add(blockEffect);
            else queueEffect(outputIndex, blockEffect);
        }
        completeIndex(outputIndex);
    }

    /**
     * 标记一个 output slot 已出现；map 中的 slot 本身就是出现事实，缺失的前序 slot 会在
     * terminal output 中创建后再释放后续事件。
     */
    private void observeIndex(int outputIndex) {
        if (outputIndex < nextOutputIndex) return;
        itemSlots.computeIfAbsent(outputIndex, ignored -> new ItemSlot());
    }

    /**
     * 关闭 output slot，只有前序 slot 已关闭时才允许 effects 越过顺序屏障。
     */
    private void completeIndex(int outputIndex) {
        if (outputIndex < nextOutputIndex) return;
        observeIndex(outputIndex);
        itemSlots.get(outputIndex).complete = true;
    }

    /**
     * 将文本、摘要或 Tool 事件绑定到其 output_index，避免 terminal 回补把事件插到错误 item 后面。
     */
    private void queueEffect(int outputIndex, ModelPort.ModelEvent effect) {
        observeIndex(outputIndex);
        pendingEffects.computeIfAbsent(outputIndex, ignored -> new ArrayList<>()).add(effect);
    }

    /**
     * 仅释放当前连续 item 的待提交事件；未出现的前序 item 会阻止后续事件提前可见。
     */
    private List<ModelPort.ModelEvent> flushOrderedEffects() {
        List<ModelPort.ModelEvent> effects = new ArrayList<>();
        while (true) {
            ItemSlot slot = itemSlots.get(nextOutputIndex);
            if (slot == null) break;
            List<ModelPort.ModelEvent> pending = pendingEffects.remove(nextOutputIndex);
            if (pending != null) effects.addAll(pending);
            if (!slot.complete) break;
            itemSlots.remove(nextOutputIndex++);
        }
        return effects;
    }

    /**
     * 记录 output slot 的显式条目状态；文本事件没有 output_item.added 时仍可在 done 处关闭 slot。
     */
    private static final class ItemSlot {
        private boolean explicitItem;
        private boolean complete;

        /** 返回是否收到 output_item.added，决定 text.done 能否独立关闭该 slot。 */
        boolean explicitItem() {
            return explicitItem;
        }
    }

    /**
     * 以稳定坐标累积 Responses reasoning summary 与原生 item，并把展示文本和 opaque 历史严格分开。
     */
    private final class ReasoningAccumulator {
        private final String itemId;
        private final int outputIndex;
        private final Map<Integer, StringBuilder> summaryParts = new TreeMap<>();
        private final StringBuilder reasoningText = new StringBuilder();
        private String publicDisplayed = "";
        private DisplaySource displaySource = DisplaySource.UNSELECTED;
        private JsonNode nativeItem;
        private boolean emitted;
        private ReasoningContent emittedContent;

        /** 绑定上游 item 身份与 output 顺序，防止交错事件串到其它 reasoning 块。 */
        private ReasoningAccumulator(String itemId, int outputIndex) {
            this.itemId = itemId;
            this.outputIndex = outputIndex;
        }

        /** 登记 output_item.added 的初始原生对象，但不把未关闭 encrypted 状态写入历史。 */
        private void acceptAdded(JsonNode item) {
            if (nativeItem != null && !nativeItem.equals(item)) {
                throw protocol("OpenAI reasoning item metadata changed");
            }
            nativeItem = item.deepCopy();
        }

        /** 接受 output_item.done 的完整对象，并复用其 summary 作为无 delta 时的回补来源。 */
        private void acceptDone(JsonNode item) {
            nativeItem = item.deepCopy();
            acceptSummaryArray(item.get("summary"));
        }

        /** 用 response terminal output 覆盖早期不完整对象，确保 encrypted_content 只在完整时持久化。 */
        private void acceptTerminal(JsonNode item) {
            nativeItem = item.deepCopy();
            acceptSummaryArray(item.get("summary"));
        }

        /** 累积一个 summary_text delta，段落编号只由外层严格索引校验后传入。 */
        private void appendSummary(int summaryIndex, String value) {
            summaryParts.computeIfAbsent(summaryIndex, ignored -> new StringBuilder()).append(value);
        }

        /** 用 done/terminal 的最终 part 文本补齐缺失 delta，禁止非前缀的内容篡改。 */
        private void finishSummary(int summaryIndex, String value) {
            StringBuilder current = summaryParts.computeIfAbsent(summaryIndex, ignored -> new StringBuilder());
            String existing = current.toString();
            if (existing.equals(value)) return;
            if (existing.isEmpty()) {
                current.append(value);
            } else if (value.startsWith(existing)) {
                current.append(value.substring(existing.length()));
            } else {
                throw protocol("OpenAI reasoning summary disagrees with its deltas");
            }
        }

        /** 登记 summary part 的类型和可选初始文本，保留多 part 段落结构。 */
        private void acceptSummaryPart(int summaryIndex, JsonNode part) {
            if (!"summary_text".equals(requiredText(part, "type", false))) {
                throw protocol("OpenAI reasoning summary part type is invalid");
            }
            String text = optionalText(part, "text");
            if (text != null && !text.isEmpty()) {
                summaryParts.computeIfAbsent(summaryIndex, ignored -> new StringBuilder()).append(text);
            }
        }

        /** 收集兼容网关的公开 reasoning_text，后续与 summary 共用展示去重账本。 */
        private void appendReasoningText(String value) {
            if ((long) reasoningText.length() + value.length() > 4_000_000L) {
                throw new ProviderProtocolException(
                        "REASONING_LIMIT", "OpenAI reasoning exceeds the limit", false);
            }
            reasoningText.append(value);
        }

        /** 用 reasoning_text.done 补齐缺失分片，并拒绝内容回退或篡改。 */
        private void finishReasoningText(String value) {
            String existing = reasoningText.toString();
            if (existing.equals(value)) return;
            if (existing.isEmpty()) {
                reasoningText.append(value);
            } else if (value.startsWith(existing)) {
                reasoningText.append(value.substring(existing.length()));
            } else {
                throw protocol("OpenAI reasoning text disagrees with its deltas");
            }
        }

        /** 从完整 reasoning item 中回补全部 summary part，encrypted_content 不参与公开展示。 */
        private void acceptSummaryArray(JsonNode summary) {
            if (summary == null || summary.isNull()) return;
            if (!summary.isArray()) throw protocol("OpenAI reasoning summary is invalid");
            for (int index = 0; index < summary.size(); index++) {
                JsonNode part = summary.get(index);
                if (!part.isObject() || !"summary_text".equals(requiredText(part, "type", false))) {
                    throw protocol("OpenAI reasoning summary part is invalid");
                }
                finishSummary(index, requiredText(part, "text", true));
            }
        }

        /** 首个非空公开通道即成为该 item 的稳定展示来源，避免另一通道迟到时重复拼接。 */
        private String nextPublicSuffix() {
            String summary = summaryText();
            if (displaySource == DisplaySource.UNSELECTED) {
                if (!summary.isEmpty()) displaySource = DisplaySource.SUMMARY;
                else if (!reasoningText.isEmpty()) displaySource = DisplaySource.REASONING_TEXT;
                else return "";
            }
            String candidate = displaySource == DisplaySource.SUMMARY
                    ? summary : reasoningText.toString();
            if (candidate.isEmpty()) return "";
            if (candidate.startsWith(publicDisplayed)) {
                String suffix = candidate.substring(publicDisplayed.length());
                publicDisplayed = candidate;
                return suffix;
            }
            if (publicDisplayed.startsWith(candidate)) return "";
            throw protocol("OpenAI reasoning display source regressed");
        }

        /** 将多个 summary part 拼接成带段落分隔的公开文本，避免 part 边界被 UI 吞掉。 */
        private String summaryText() {
            StringBuilder result = new StringBuilder();
            for (StringBuilder part : summaryParts.values()) {
                if (part.isEmpty()) continue;
                if (!result.isEmpty()) result.append("\n\n");
                result.append(part);
            }
            return result.toString();
        }

        /** 只有 Provider 返回非空 encrypted_content 时才允许把 reasoning 当作可回放历史。 */
        private boolean readyForHistory() {
            if (nativeItem == null || !nativeItem.isObject()
                    || !"reasoning".equals(nativeItem.path("type").textValue())
                    || !itemId.equals(nativeItem.path("id").textValue())) {
                return false;
            }
            JsonNode encrypted = nativeItem.get("encrypted_content");
            return encrypted != null && encrypted.isTextual() && !encrypted.textValue().isEmpty();
        }

        /** 将完整原生 item 封装为 identity-bound ReasoningBlockReady，不把 JSON 投影到 UI。 */
        private ModelPort.ReasoningBlockReady block() {
            if (nativeItem == null || !nativeItem.isObject()
                    || !"reasoning".equals(nativeItem.path("type").textValue())
                    || !itemId.equals(nativeItem.path("id").textValue())) {
                throw protocol("OpenAI reasoning item is incomplete");
            }
            try {
                String nativeJson = AbstractStreamingModelAdapter.JSON.writeValueAsString(nativeItem);
                ReasoningContent content = ProviderReasoningSupport.capture(
                        configuration, "reasoning", nativeJson);
                return new ModelPort.ReasoningBlockReady(content);
            } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException failure) {
                if (failure instanceof ProviderProtocolException protocol) throw protocol;
                throw new ProviderProtocolException(
                        "PROVIDER_JSON", "OpenAI reasoning item could not be captured", false);
            }
        }

        /**
         * 首次发布完整 reasoning block 并记录其 identity-bound 内容，供 terminal 对账识别原位更新。
         * replacement 不能重新追加 block，否则会改变 assistant content 顺序并产生重复历史。
         */
        private ModelPort.ReasoningBlockReady emitBlock() {
            ModelPort.ReasoningBlockReady block = block();
            if (emitted) throw protocol("OpenAI reasoning block was emitted twice");
            emitted = true;
            emittedContent = block.content();
            return block;
        }

        /**
         * terminal output 到达后生成一次性 block 或原位 replacement；未变化时不产生噪声事件。
         */
        private ModelPort.ModelEvent terminalBlockEffect() {
            // Responses 的 terminal item 可能只有公开 summary。此时它不能覆盖此前有效的
            // opaque block，也不能凭空创建一个下一轮无法被 Provider 接受的 reasoning input。
            if (!readyForHistory()) return null;
            ModelPort.ReasoningBlockReady terminalBlock = block();
            if (!emitted) {
                emitted = true;
                emittedContent = terminalBlock.content();
                return terminalBlock;
            }
            if (emittedContent.equals(terminalBlock.content())) return null;
            ModelPort.ReasoningBlockReplaced replacement =
                    new ModelPort.ReasoningBlockReplaced(emittedContent, terminalBlock.content());
            emittedContent = terminalBlock.content();
            return replacement;
        }

        /** 返回 item 在 output 中的稳定坐标，供外层顺序屏障校验。 */
        private int outputIndex() {
            return outputIndex;
        }

    }

    /** 公开 reasoning 只从一个上游通道投影，防止 summary 和兼容全文重复展示。 */
    private enum DisplaySource {
        /** 尚未收到可展示的 reasoning 摘要或兼容文本，等待首个非空来源锁定。 */
        UNSELECTED,
        /** 已锁定 Provider 的公开 summary 通道，后续只追加该通道的增量。 */
        SUMMARY,
        /** 已锁定兼容 reasoning_text 通道，忽略迟到的另一公开来源以避免重复。 */
        REASONING_TEXT
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
