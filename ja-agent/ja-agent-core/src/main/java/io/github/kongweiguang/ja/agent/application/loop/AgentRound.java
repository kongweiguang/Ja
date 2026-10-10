// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.TurnEvent;
import io.github.kongweiguang.ja.agent.application.api.TurnEventSink;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;

/**
 * 汇集单次模型响应的完整事实；只有该响应终止且协议有效时，外层状态机才提交消息。
 */
final class AgentRound implements ModelEventSink {
    private static final int TEXT_BLOCK_CHARS = 262_144;
    private static final int STREAM_FRAME_CHARS = 65_536;

    private final AgentIdentity identity;
    private final String modelRequestId;
    private final CancellationToken cancellation;
    private final TurnEventSink stream;
    private final Clock clock;
    private final LongSupplier streamSequence;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder publicReasoning = new StringBuilder();
    private final StringBuilder activeText = new StringBuilder();
    private final List<ModelContent> content = new ArrayList<>();
    private final Map<String, ReasoningSlot> deferredReasoningSlots = new HashMap<>();
    private final List<AgentTool.Invocation> calls = new ArrayList<>();
    private final Set<String> callIds = new HashSet<>();
    private final Set<Integer> ordinals = new HashSet<>();
    private long lastStreamSequence;
    private boolean publishedStream;
    private ModelUsage usage;
    private boolean closed;

    /**
     * 注入 execution 级共享序号源，确保同一 lease epoch 内跨 model round 仍严格递增，
     * 且由 Session 统一按 fencing token 初始化，不在每个响应收集器内重置计数。
     */
    AgentRound(AgentIdentity identity, String modelRequestId, CancellationToken cancellation,
               TurnEventSink stream, LongSupplier streamSequence, Clock clock) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.modelRequestId = required(modelRequestId);
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.stream = Objects.requireNonNull(stream, "stream");
        this.streamSequence = Objects.requireNonNull(streamSequence, "streamSequence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 代理载荷允许变化，但端点、模型和协议字段必须保持同一原生块身份。
     */
    private static boolean sameReasoningIdentity(ReasoningContent left, ReasoningContent right) {
        return left.providerId().equals(right.providerId()) && left.modelId().equals(right.modelId())
               && left.api().equals(right.api()) && left.upstreamModel().equals(right.upstreamModel())
               && left.endpointFingerprint().equals(right.endpointFingerprint())
               && left.wireField().equals(right.wireField());
    }

    /**
     * Provider 流协议损坏立即阻止该轮继续消费任何迟到事件。
     */
    private static AgentRoundFailure protocol(String message) {
        return new AgentRoundFailure("MODEL_PROTOCOL_ERROR", message);
    }

    /**
     * 稳定标识检查不接受空请求身份，临时事件可据此与重连恢复边界关联。
     */
    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("modelRequestId is required");
        }
        return value;
    }

    /**
     * Provider 回调串行归并为模型内容；未完成 reasoning 只占内部顺序槽，正文仍可立即公开流出。
     */
    @Override
    public synchronized CompletionStage<Void> onEvent(ModelPort.ModelEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed || cancellation.isCancellationRequested()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            switch (event) {
                case ModelPort.TextDelta delta -> {
                    appendText(delta.text());
                    publish(delta.text(), false);
                }
                case ModelPort.ReasoningSummaryDelta delta -> {
                    publicReasoning.append(delta.text());
                    publish(delta.text(), true);
                }
                case ModelPort.ReasoningBlockReady ready -> {
                    freezeText();
                    content.add(ready.content());
                }
                case ModelPort.ReasoningBlockDeferred deferred -> deferReasoning(deferred);
                case ModelPort.ReasoningBlockReplaced replaced -> replaceReasoning(replaced);
                case ModelPort.ToolCallReady call -> recordCall(call);
                case ModelPort.UsageEvent snapshot -> usage = snapshot.usage();
                default -> throw protocol("provider emitted an unsupported event");
            }
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException failure) {
            closed = true;
            throw failure;
        }
    }

    /**
     * 冻结本轮最后一个正文块，确保请求调用返回后迟到回调不能改变已提交内容。
     */
    synchronized void close() {
        if (closed) {
            return;
        }
        freezeText();
        closed = true;
    }

    /**
     * 发布用于丢弃半截响应的重置事件；持久历史不因该瞬时清理而改变。
     */
    synchronized void reset(String reasonCode) {
        if (!publishedStream) {
            return;
        }
        stream.publish(new TurnEvent.Reset(context(), reasonCode));
    }

    /**
     * 返回完整助手消息并剔除仍未填充的内部 reasoning 槽；空响应交给执行器判为协议失败。
     */
    synchronized ModelMessage message() {
        freezeText();
        List<ModelContent> completeContent = materializedContent();
        return completeContent.isEmpty() ? null : new ModelMessage(ModelRole.ASSISTANT, completeContent);
    }

    /**
     * 返回模型原始正文供最终摘要，不能把 Tool 中间轮的文本当成最终答复。
     */
    synchronized String terminalText(boolean hasToolCalls) {
        return hasToolCalls ? "" : text.toString();
    }

    /**
     * 返回本次 Provider 响应中供应商明确给出的公开摘要。
     */
    synchronized String reasoningSummary() {
        return publicReasoning.isEmpty() ? null : publicReasoning.toString();
    }

    /**
     * 返回冻结前的模型调用顺序；批次身份与全局顺序由持久执行游标负责。
     */
    synchronized List<AgentTool.Invocation> calls() {
        return List.copyOf(calls);
    }

    /**
     * 返回最终 Usage 快照，未报告时明确保持 null。
     */
    synchronized ModelUsage usage() {
        return usage;
    }

    /**
     * 返回已收到的纯正文，用于无完整响应时生成脱敏诊断，不进入模型历史。
     */
    synchronized String partialText() {
        return text.toString();
    }

    /**
     * 按 UTF-16 代理对边界切块，避免 Provider 单个 delta 超过订阅帧约束或拆坏字符。
     */
    private void publish(String value, boolean reasoning) {
        for (int start = 0; start < value.length(); ) {
            int end = Math.min(value.length(), start + STREAM_FRAME_CHARS);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
                end--;
            }
            String fragment = value.substring(start, end);
            TurnEvent.Context eventContext = context();
            stream.publish(reasoning
                ? new TurnEvent.ReasoningSummaryDelta(eventContext, fragment)
                : new TurnEvent.TextDelta(eventContext, fragment));
            publishedStream = true;
            start = end;
        }
    }

    /**
     * 分块保存完整正文，降低消息块规模且保持模型给出的原始字符顺序。
     */
    private void appendText(String value) {
        text.append(value);
        for (int start = 0; start < value.length(); ) {
            int room = TEXT_BLOCK_CHARS - activeText.length();
            int end = Math.min(value.length(), start + room);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
                end--;
            }
            if (end == start) {
                freezeText();
                continue;
            }
            activeText.append(value, start, end);
            start = end;
            if (activeText.length() == TEXT_BLOCK_CHARS) {
                freezeText();
            }
        }
    }

    /**
     * Tool 边界之前冻结正文，保持 Assistant 消息内文本、推理块和 Tool call 次序。
     */
    private void recordCall(ModelPort.ToolCallReady ready) {
        freezeText();
        if (!callIds.add(ready.callId()) || !ordinals.add(ready.ordinal())) {
            throw protocol("duplicate Tool call identity");
        }
        AgentTool.Invocation invocation = new AgentTool.Invocation(ready.callId(), ready.name(),
            ready.arguments(), ready.ordinal(), ready.preflightFailureCode());
        calls.add(invocation);
        content.add(new ToolCallContent(ready.callId(), ready.name(), ready.arguments(),
            ready.preflightFailureCode()));
    }

    /**
     * 只有身份完全匹配的原生推理块才能被供应商终态版本替换；查找时跳过未填充槽，不移动其保留位置。
     */
    private void replaceReasoning(ModelPort.ReasoningBlockReplaced replacement) {
        ReasoningContent prior = replacement.previous();
        ReasoningContent next = replacement.replacement();
        if (!sameReasoningIdentity(prior, next)) {
            throw protocol("reasoning identity changed");
        }
        int index = -1;
        for (int i = 0; i < content.size(); i++) {
            ModelContent block = content.get(i);
            if (block instanceof ReasoningContent current && current.equals(prior)) {
                if (index >= 0) {
                    throw protocol("reasoning replacement is ambiguous");
                }
                index = i;
            }
        }
        if (index < 0) {
            throw protocol("reasoning replacement target is missing");
        }
        content.set(index, next);
    }

    /**
     * 先冻结前序正文并保留 reasoning 原位；只有同一已声明槽能接收唯一完整块，避免终态回填改变消息顺序。
     */
    private void deferReasoning(ModelPort.ReasoningBlockDeferred deferred) {
        String slotId = deferred.slotId();
        ReasoningContent value = deferred.content();
        ReasoningSlot slot = deferredReasoningSlots.get(slotId);
        if (value == null) {
            if (slot != null) {
                throw protocol("reasoning slot was declared more than once");
            }
            freezeText();
            deferredReasoningSlots.put(slotId, new ReasoningSlot(content.size(), false));
            content.add(null);
            return;
        }
        if (slot == null) {
            throw protocol("reasoning slot was not declared");
        }
        if (slot.filled() || slot.contentIndex() >= content.size() || content.get(slot.contentIndex()) != null) {
            throw protocol("reasoning slot was already filled");
        }
        for (ModelContent block : content) {
            if (block instanceof ReasoningContent existing && existing.equals(value)) {
                throw protocol("reasoning block identity was repeated");
            }
        }
        content.set(slot.contentIndex(), value);
        deferredReasoningSlots.put(slotId, new ReasoningSlot(slot.contentIndex(), true));
    }

    /**
     * 生成无 null 的不可变模型内容视图；未补齐的 reasoning 位置只服务本轮排序，不能进入请求或存储。
     */
    private List<ModelContent> materializedContent() {
        List<ModelContent> result = new ArrayList<>(content.size());
        for (ModelContent block : content) {
            if (block != null) {
                result.add(block);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 只实体化非空文本块，避免 Tool 边界制造无效 ModelContent。
     */
    private void freezeText() {
        if (!activeText.isEmpty()) {
            content.add(new TextContent(activeText.toString()));
            activeText.setLength(0);
        }
    }

    /** 记录内部 reasoning 槽的固定内容索引与一次性填充状态。 */
    private record ReasoningSlot(int contentIndex, boolean filled) {
    }

    /**
     * 通过共享分配器取得下一序号，并校验本 round 观察到的序号保持严格递增。
     */
    private TurnEvent.Context context() {
        long sequence = streamSequence.getAsLong();
        if (sequence < 1 || sequence <= lastStreamSequence) {
            throw new IllegalStateException("model stream sequence must increase within a response");
        }
        lastStreamSequence = sequence;
        return new TurnEvent.Context(identity, modelRequestId, sequence, clock.instant());
    }

    /**
     * 供唯一执行状态机分类协议失败，不保留 Provider 响应正文。
     */
    static final class AgentRoundFailure extends RuntimeException {
        private final String code;

        /**
         * 保存稳定错误码和固定诊断语义，避免工具或 Provider 异常穿透到用户面。
         */
        AgentRoundFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        /**
         * 返回持久失败事实中的稳定协议分类。
         */
        String code() {
            return code;
        }
    }
}
