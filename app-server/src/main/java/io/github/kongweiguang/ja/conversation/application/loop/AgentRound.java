// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ModelEventSink;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 持有单个 Provider 流式轮次可丢弃的回调状态。
 */
final class AgentRound implements ModelEventSink {
    private static final int MAX_PUBLIC_TEXT = 1_000_000;
    private static final int MAX_ASSISTANT_TEXT_BLOCK = 4_000_000;
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(2);

    private final String turnId;
    private final CancellationToken cancellation;
    private final TurnEventSink sink;
    private final BooleanSupplier loopClosed;
    private final SequenceAllocator sequences;
    private final Duration drainTimeout;
    private final int round;
    private final StreamingDeltaBatcher deltas;
    private final CancellationToken.Registration cancellationRegistration;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoningSummary = new StringBuilder();
    private final StringBuilder activeTextBlock = new StringBuilder();
    private final List<ModelContent> assistantContent = new ArrayList<>();
    private final List<AgentTool.Invocation> calls = new ArrayList<>();
    private final Set<String> callIds = new HashSet<>();
    private final Set<Integer> ordinals = new HashSet<>();
    private RoundPhase phase = RoundPhase.OPEN;
    private ModelUsage usage;
    private boolean ordinalsAllocated;
    private int textBlockMaterializations;

    /**
     * 使用运行态序号分配器和共享 Scheduler 的隔离 Timer 创建轮次，保证序号连续且关闭互不影响。
     */
    AgentRound(
            String turnId,
            CancellationToken cancellation,
            TurnEventSink sink,
            BooleanSupplier loopClosed,
            AgentLoop.RuntimeState state,
            int round,
            StreamingDeltaBatcher.Timer timer) {
        this(
                turnId,
                cancellation,
                sink,
                loopClosed,
                round,
                Objects.requireNonNull(timer, "timer"),
                runtimeSequences(state),
                DRAIN_TIMEOUT);
    }

    /**
     * 注入 Timer 与序号分配器以验证批处理和排序边界，同时沿用生产排空期限。
     */
    AgentRound(
            String turnId,
            CancellationToken cancellation,
            TurnEventSink sink,
            BooleanSupplier loopClosed,
            int round,
            StreamingDeltaBatcher.Timer timer,
            SequenceAllocator sequences) {
        this(turnId, cancellation, sink, loopClosed, round, timer, sequences, DRAIN_TIMEOUT);
    }

    /**
     * 完整绑定轮次依赖并注册取消时丢弃草稿；排空期限必须非负且轮次从一开始。
     */
    AgentRound(
            String turnId,
            CancellationToken cancellation,
            TurnEventSink sink,
            BooleanSupplier loopClosed,
            int round,
            StreamingDeltaBatcher.Timer timer,
            SequenceAllocator sequences,
            Duration drainTimeout) {
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.loopClosed = Objects.requireNonNull(loopClosed, "loopClosed");
        this.sequences = Objects.requireNonNull(sequences, "sequences");
        this.drainTimeout = Objects.requireNonNull(drainTimeout, "drainTimeout");
        if (drainTimeout.isNegative()) {
            throw new IllegalArgumentException("drainTimeout must not be negative");
        }
        if (round < 1) {
            throw new IllegalArgumentException("round must be positive");
        }
        this.round = round;
        this.deltas =
                new StreamingDeltaBatcher(
                        Objects.requireNonNull(timer, "timer"), this::publishDelta, this::discardDeltas);
        this.cancellationRegistration = cancellation.onCancellation(deltas::discard);
    }

    /**
     * 串行处理 Provider 回调，并把草稿事件背压传递到 transport。
     */
    @Override
    public synchronized CompletionStage<Void> onEvent(ModelPort.ModelEvent event) {
        if (phase != RoundPhase.OPEN || loopClosed.getAsBoolean()
            || cancellation.isCancellationRequested()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            if (event instanceof ModelPort.TextDelta delta) {
                appendText(delta.text());
                return deltas.append(StreamingDeltaBatcher.Kind.TEXT, delta.text());
            }
            if (event instanceof ModelPort.ReasoningSummaryDelta delta) {
                appendReasoningSummary(delta.text());
                return deltas.append(StreamingDeltaBatcher.Kind.REASONING_SUMMARY, delta.text());
            }
            if (event instanceof ModelPort.ReasoningBlockReady reasoning) {
                /*
                 * 原生块没有公开事件，但它仍是 assistant 内容顺序的一部分；先冻结前一个文本块，
                 * 再写入完整 opaque block，保证 text -> reasoning -> Tool 的顺序在历史中不变。
                 */
                freezeActiveTextBlock();
                assistantContent.add(reasoning.content());
                return deltas.flush();
            }
            if (event instanceof ModelPort.ReasoningBlockReplaced replacement) {
                replaceReasoningBlock(replacement);
                return deltas.flush();
            }
            if (event instanceof ModelPort.ToolCallReady ready) {
                freezeActiveTextBlock();
                recordToolCall(ready);
                return deltas.flush();
            }
            if (event instanceof ModelPort.UsageEvent usage) {
                recordUsage(usage.usage());
                return deltas.flush();
            }
            throw new AgentLoop.LoopFailure(
                    "MODEL_PROTOCOL_ERROR", "provider emitted an unknown model event");
        } catch (RuntimeException failure) {
            phase = RoundPhase.FAILED;
            deltas.discard();
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Provider 完成后立即停止接收回调，使迟到事件只能被丢弃而不能改变已冻结结果。
     */
    void close() {
        CompletionStage<Void> drained;
        synchronized (this) {
            if (phase == RoundPhase.CLOSED) {
                return;
            }
            boolean failed = phase == RoundPhase.FAILED;
            phase = RoundPhase.CLOSED;
            freezeActiveTextBlock();
            if (failed || discardDeltas()) {
                drained = deltas.discard();
            } else {
                drained = deltas.closeNormally();
            }
        }
        try {
            await(drained, drainTimeout);
        } finally {
            cancellationRegistration.close();
        }
    }

    /**
     * 当流事件未携带 Usage 时接纳 Provider 终局 Usage；已有值时保持首次事实。
     */
    synchronized void recordOutcomeUsage(ModelUsage usage) {
        if (usage != null && this.usage == null) {
            recordUsage(usage);
        }
    }

    /**
     * 返回本轮唯一 Usage 快照，调用方只能在轮次冻结后用于持久化。
     */
    synchronized ModelUsage usage() {
        return usage;
    }

    /**
     * 返回创建时固定的模型轮次编号，用于 Usage 与持久化事实关联。
     */
    int round() {
        return round;
    }

    /**
     * 先冻结尾部文本块再返回不可变内容，避免 Tool 边界两侧文本被错误合并。
     */
    synchronized List<ModelContent> assistantContent() {
        freezeActiveTextBlock();
        return List.copyOf(assistantContent);
    }

    /**
     * 暴露文本块实体化次数，供批处理回归测试确认没有按 delta 产生对象。
     */
    synchronized int textBlockMaterializations() {
        return textBlockMaterializations;
    }

    /**
     * 仅 STOP 轮次返回最终可见文本；包含 Tool call 的中间轮次不能冒充终态回答。
     */
    synchronized String terminalText() {
        return calls.isEmpty() ? text.toString() : "";
    }

    /** 返回 Provider 明确标注的公开摘要；隐藏 reasoning 从未进入该聚合器。 */
    synchronized String reasoningSummary() {
        return reasoningSummary.isEmpty() ? null : reasoningSummary.toString();
    }

    /**
     * 校验 Provider ordinal 连续且只分配一次，再映射到 Turn 全局连续 Tool 序号。
     */
    synchronized List<AgentTool.Invocation> orderedCalls() {
        if (ordinalsAllocated) {
            throw new AgentLoop.LoopFailure(
                    "MODEL_PROTOCOL_ERROR", "Tool ordinals were already allocated");
        }
        List<AgentTool.Invocation> ordered =
                calls.stream().sorted(Comparator.comparingInt(AgentTool.Invocation::ordinal)).toList();
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).ordinal() != index) {
                throw new AgentLoop.LoopFailure("MODEL_PROTOCOL_ERROR", "Tool ordinals are not contiguous");
            }
        }
        int base = sequences.allocateToolOrdinals(ordered.size());
        ordinalsAllocated = true;
        return ordered.stream()
                .map(
                        call ->
                                new AgentTool.Invocation(
                                        call.callId(), call.toolName(), call.arguments(), base + call.ordinal()))
                .toList();
    }

    /**
     * 拒绝重复 callId 或 ordinal，并同时记录执行清单和 assistant 上下文中的调用块。
     */
    private void recordToolCall(ModelPort.ToolCallReady ready) {
        if (!callIds.add(ready.callId()) || !ordinals.add(ready.ordinal())) {
            throw new AgentLoop.LoopFailure("MODEL_PROTOCOL_ERROR", "duplicate Tool call identity");
        }
        calls.add(
                new AgentTool.Invocation(ready.callId(), ready.name(), ready.arguments(), ready.ordinal()));
        assistantContent.add(
                new ToolCallContent(ready.callId(), ready.name(), ready.arguments()));
    }

    /**
     * 将终态补全块原位替换到历史，避免旧 opaque JSON 被追加成第二个 reasoning 块或改变 Tool 顺序。
     * 旧块按完整 equals 唯一定位，新旧块身份必须一致；任何歧义都按协议错误关闭本轮。
     */
    private void replaceReasoningBlock(ModelPort.ReasoningBlockReplaced replacement) {
        ReasoningContent previous = replacement.previous();
        ReasoningContent next = replacement.replacement();
        if (!sameReasoningIdentity(previous, next)) {
            throw new AgentLoop.LoopFailure(
                    "MODEL_PROTOCOL_ERROR", "reasoning replacement identity changed");
        }
        int match = -1;
        for (int index = 0; index < assistantContent.size(); index++) {
            ModelContent content = assistantContent.get(index);
            if (content instanceof ReasoningContent reasoning && reasoning.equals(previous)) {
                if (match >= 0) {
                    throw new AgentLoop.LoopFailure(
                            "MODEL_PROTOCOL_ERROR", "reasoning replacement is ambiguous");
                }
                match = index;
            }
        }
        if (match < 0) {
            throw new AgentLoop.LoopFailure(
                    "MODEL_PROTOCOL_ERROR", "reasoning replacement target is missing");
        }
        assistantContent.set(match, next);
    }

    /**
     * opaque 载荷可以变化，但继续回传的 Provider 身份和字段协议必须与已记录块完全相同。
     */
    private static boolean sameReasoningIdentity(ReasoningContent left, ReasoningContent right) {
        return left.providerId().equals(right.providerId())
                && left.modelId().equals(right.modelId())
                && left.api().equals(right.api())
                && left.upstreamModel().equals(right.upstreamModel())
                && left.endpointFingerprint().equals(right.endpointFingerprint())
                && left.wireField().equals(right.wireField());
    }

    /**
     * 只接受一个 Usage 事实，重复事件视为 Provider 协议错误而不是覆盖。
     */
    private void recordUsage(ModelUsage usage) {
        if (this.usage != null) {
            throw new AgentLoop.LoopFailure("MODEL_PROTOCOL_ERROR", "duplicate usage event");
        }
        this.usage = Objects.requireNonNull(usage, "usage");
    }

    /**
     * 分别维护完整上下文文本与有界公开文本，并避免在 UTF-16 代理对中间截断。
     */
    private void appendText(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_ASSISTANT_TEXT_BLOCK - activeTextBlock.length()) {
            throw new AgentLoop.LoopFailure(
                    "MODEL_PROTOCOL_ERROR", "assistant text block exceeds its context bound");
        }
        activeTextBlock.append(value);
        int remaining = MAX_PUBLIC_TEXT - text.length();
        if (remaining > 0) {
            int end = Math.min(remaining, value.length());
            if (end < value.length()
                && end > 0
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
                end--;
            }
            text.append(value, 0, end);
        }
    }

    /** 公开摘要沿用可见文本硬上限，避免摘要流成为无界持久化通道。 */
    private void appendReasoningSummary(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_PUBLIC_TEXT - reasoningSummary.length()) {
            throw new AgentLoop.LoopFailure(
                    "MODEL_PROTOCOL_ERROR", "reasoning summary exceeds its public bound");
        }
        reasoningSummary.append(value);
    }

    /**
     * 在 Tool 或轮次边界把累积文本实体化一次，减少 delta 级对象并保持原始内容顺序。
     */
    private void freezeActiveTextBlock() {
        if (activeTextBlock.isEmpty()) {
            return;
        }
        assistantContent.add(new TextContent(activeTextBlock.toString()));
        activeTextBlock.setLength(0);
        textBlockMaterializations++;
    }

    /**
     * 发布带 Turn 全局单调序号的批次，令不同 delta 类型共享同一可观察顺序。
     */
    private CompletionStage<Void> publishDelta(StreamingDeltaBatcher.Kind kind, String value) {
        long sequence = sequences.allocateStreamSequence();
        return switch (kind) {
            case TEXT -> sink.publish(new TurnEvent.TextDelta(turnId, sequence, value));
            case REASONING_SUMMARY -> sink.publish(new TurnEvent.ReasoningSummaryDelta(turnId, sequence, value));
        };
    }

    /**
     * Loop 关闭或 Turn 取消后立即放弃草稿，禁止迟到批次越过终态边界。
     */
    private boolean discardDeltas() {
        return loopClosed.getAsBoolean() || cancellation.isCancellationRequested();
    }

    /**
     * 将轮次所需的两类序号委托给 Turn 运行态，避免每轮重新从零分配。
     */
    private static SequenceAllocator runtimeSequences(AgentLoop.RuntimeState state) {
        AgentLoop.RuntimeState runtime = Objects.requireNonNull(state, "state");
        return new SequenceAllocator() {
            /** 从 Turn 运行态获取下一个草稿事件序号。 */
            @Override
            public long allocateStreamSequence() {
                return runtime.allocateStreamSequence();
            }

            /** 从 Turn 运行态连续预留本轮 Tool 序号。 */
            @Override
            public int allocateToolOrdinals(int count) {
                return runtime.allocateToolOrdinals(count);
            }
        };
    }

    /**
     * 单轮回调生命周期只允许 OPEN 单向进入 FAILED 或 CLOSED。
     */
    private enum RoundPhase {
        /**
         * 正常接收 Provider 事件并允许批次刷新。
         */
        OPEN,

        /**
         * 回调协议或发布已失败，剩余增量必须丢弃。
         */
        FAILED,

        /**
         * 轮次结果已经冻结并释放定时器、取消注册。
         */
        CLOSED
    }

    /**
     * 有界等待草稿排空，并把超时、中断和 Sink 失败归约为终态协调可识别的类别。
     */
    private static void await(CompletionStage<Void> stage, Duration timeout) {
        try {
            stage.toCompletableFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            throw new DeltaDrainException(DeltaDrainException.Code.TIMEOUT, failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new DeltaDrainException(DeltaDrainException.Code.INTERRUPTED, failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof DeltaDrainException typed) throw typed;
            throw new DeltaDrainException(DeltaDrainException.Code.SINK_FAILURE, cause);
        }
    }

    /**
     * 隔离 Turn 全局序号分配，使单轮聚合不持有或复制完整运行态。
     */
    interface SequenceAllocator {
        /**
         * 分配下一个草稿流序号，要求在整个 Turn 内单调且不重复。
         */
        long allocateStreamSequence();

        /**
         * 为一个 Tool batch 连续预留序号，失败时不得部分消费区间。
         */
        int allocateToolOrdinals(int count);
    }

    /**
     * 草稿批次无法安全排空时的类型化失败，阻止终态在未决 delta 之前提交。
     */
    static final class DeltaDrainException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Code code;

        /**
         * 根据稳定类别生成不含敏感细节的消息，同时保留底层原因供受控诊断。
         */
        private DeltaDrainException(Code code, Throwable cause) {
            super(
                    switch (Objects.requireNonNull(code, "code")) {
                        case TIMEOUT -> "delta sink drain deadline exceeded";
                        case INTERRUPTED -> "delta sink drain interrupted";
                        case SINK_FAILURE -> "delta sink publication failed";
                    },
                    cause);
            this.code = code;
        }

        /**
         * 返回终态协调器用于决策的稳定排空失败类别。
         */
        Code code() {
            return code;
        }

        /**
         * 单轮增量排空只保留终态协调所需的稳定失败类别。
         */
        enum Code {
            /**
             * 等待增量写出超过单轮排空期限。
             */
            TIMEOUT,
            /**
             * 排空等待被中断，调用线程的中断状态已恢复。
             */
            INTERRUPTED,
            /**
             * 增量 Sink 已失败并失去顺序所有权。
             */
            SINK_FAILURE
        }
    }

}
