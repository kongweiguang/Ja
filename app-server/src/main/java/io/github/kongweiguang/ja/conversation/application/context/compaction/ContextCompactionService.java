// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.compaction;

import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.ContextTokenMeter;
import io.github.kongweiguang.ja.conversation.application.context.ModelContinuation;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryGenerator;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * 协调纯策略、慢摘要生成和唯一窄 Checkpoint CAS 边界。
 */
public final class ContextCompactionService {
    private final CheckpointStore checkpoints;
    private final ContextPolicy policy;
    private final SummaryGenerator generator;
    private final Clock clock;
    private final Supplier<String> checkpointIds;

    /**
     * 固定全部 owner，并要求组合根显式选择 Checkpoint 身份来源。
     */
    public ContextCompactionService(CheckpointStore checkpoints, ContextPolicy policy,
                                    SummaryGenerator generator, Clock clock,
                                    Supplier<String> checkpointIds) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.checkpointIds = Objects.requireNonNull(checkpointIds, "checkpointIds");
    }

    /**
     * 分配持久上下文事件契约唯一接受的不透明 Checkpoint ID。
     */
    public static String newCheckpointId() {
        return "checkpoint_" + UUID.randomUUID();
    }

    /**
     * 压缩一个冻结源快照，并仅在 CAS 成功后清除 Provider continuation。
     */
    public CompactionResult compact(CompactionRequest request) {
        return compact(request, ignored -> { });
    }

    /**
     * 在策略确认确需压缩并取得完整 envelope 官方计量后同步通知 started；观察器先于摘要调用，
     * 从而让自动和手动入口共享真实副作用边界，而不是由 RPC Handler 推测生命周期。
     */
    public CompactionResult compact(CompactionRequest request, CompactionStartObserver observer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observer, "observer");
        request.cancellation().throwIfCancellationRequested();
        CheckpointStore.Snapshot snapshot = checkpoints.read(request.threadId());
        if (!request.threadId().equals(snapshot.threadId())) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "checkpoint store returned a different thread");
        }
        if (snapshot.threadRevision() != request.sourceRevision()) {
            throw new ContextException(ContextException.Code.CAS_CONFLICT,
                    "context source changed before summary generation");
        }
        SummaryDocument previous = snapshot.checkpoint().map(CheckpointStore.ContextCheckpoint::summary)
                .orElseGet(SummaryDocument::empty);
        long baseThrough = snapshot.checkpoint()
                .map(CheckpointStore.ContextCheckpoint::throughOrdinal).orElse(0L);
        long baseRetainedFrom = snapshot.checkpoint()
                .map(CheckpointStore.ContextCheckpoint::retainedFromOrdinal).orElse(0L);
        Optional<ContextPolicy.RetainedSplit> previousRetainedSplit = snapshot.checkpoint()
                .flatMap(CheckpointStore.ContextCheckpoint::retainedSplit);
        ContextPolicy.PlanningInput input = new ContextPolicy.PlanningInput(request.threadId(),
                request.messages(), previous, request.sourceRevision(), baseThrough, baseRetainedFrom,
                previousRetainedSplit, request.budget(),
                request.forceCompaction(), request.continuation(), request.outputLimits());
        ContextPolicy.Plan plan = policy.plan(input, request.meter());
        if (!plan.requiresCompaction()) {
            if (!plan.fullPromptFits()) {
                throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                        "context exceeds the provider send ceiling while automatic compaction is disabled");
            }
            boolean usesCheckpoint = snapshot.checkpoint().isPresent();
            Optional<ModelContinuation> continuation = usesCheckpoint
                    ? Optional.empty() : plan.continuation();
            return new CompactionResult(usesCheckpoint,
                    new PromptContext(plan.fullPrompt(), previous, plan.fullPromptTokens(),
                            continuation, usesCheckpoint), snapshot.checkpoint(), plan, Optional.empty(), false);
        }
        /*
         * Checkpoint 事务本身会推进 Thread revision。下一轮相同不可变消息源因此具有新 revision
         * 但没有新事实；策略没有新摘要输入时复用持久摘要，不为同一源分配第二个 revision 或回执。
         */
        Optional<CheckpointStore.ContextCheckpoint> currentSourceCheckpoint = snapshot.checkpoint()
                .filter(checkpoint -> checkpoint.sourceRevision() == request.sourceRevision()
                                      || (checkpoint.sourceRevision() < request.sourceRevision()
                                          && plan.summaryInput().isEmpty()));
        if (currentSourceCheckpoint.isPresent()) {
            CheckpointStore.ContextCheckpoint checkpoint = currentSourceCheckpoint.orElseThrow();
            boolean sameSource = checkpoint.sourceRevision() == request.sourceRevision();
            return reuseCurrentSourceCheckpoint(request, plan, checkpoint, sameSource,
                    Optional.empty());
        }

        observer.started(plan.fullPromptTokens());
        request.cancellation().throwIfCancellationRequested();

        SummaryGenerator.SummaryResult generated;
        try {
            generated = plan.summaryInput().isEmpty()
                    ? new SummaryGenerator.SummaryResult(SummaryDocument.empty(), CheckpointUsage.none())
                    : generateSummary(request.threadId(), previous, plan, request.budget());
        } catch (ContextException failure) {
            if (failure.code() == ContextException.Code.SUMMARY_FAILURE
                    && !request.forceCompaction() && plan.fullPromptFits()) {
                return fallbackPromptAfterAutomaticSummaryFailure(snapshot, plan, previous);
            }
            throw failure;
        }

        SummaryDocument replacement = generated.document();
        request.cancellation().throwIfCancellationRequested();
        ContextTokenMeter.Measurement compactedMeasurement = request.meter().measure(
                plan.retainedPrompt(), replacement, Optional.empty(), true);
        request.cancellation().throwIfCancellationRequested();
        long compactedTokens = compactedMeasurement.inputTokens();
        if (compactedTokens > request.budget().sendCeilingTokens()
            || compactedTokens >= plan.fullPromptTokens()) {
            throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                    "compacted context did not produce a smaller sendable envelope");
        }
        long checkpointSourceRevision = generator.checkpointSourceRevision(request.sourceRevision());
        CheckpointStore.ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint(
                checkpointIds.get(), request.threadId(), plan.throughOrdinal(),
                plan.retainedFromOrdinal(), checkpointSourceRevision, plan.retainedSplit(), replacement,
                (int) Math.min(Integer.MAX_VALUE, compactedTokens), compactedMeasurement.fingerprint(),
                ContextCompactionEvent.STRATEGY_VERSION,
                generated.usage(), clock.instant());
        CheckpointStore.CommittedCheckpoint committed;
        try {
            /*
             * 这是新 Checkpoint 的唯一持久边界。Adapter 同时提交 Checkpoint 行、Thread revision
             * 和确定性压缩回执，因此之后的 Provider 失败或进程退出不会留下半可见事实。
             */
            request.cancellation().throwIfCancellationRequested();
            committed = checkpoints.commit(new CheckpointStore.CommitRequest(
                    request.threadId(), checkpointSourceRevision, checkpoint,
                    generator.checkpointTurnOperation()));
        } catch (CheckpointStore.CommitConflict failure) {
            throw new ContextException(ContextException.Code.CAS_CONFLICT,
                    "context source changed while summary was generated", failure);
        }
        if (!committed.newlyCommitted()) {
            return reuseCurrentSourceCheckpoint(request, plan, committed.checkpoint(), false,
                    Optional.of(committed));
        }
        verifyPersistedFingerprint(request, plan.retainedPrompt(), committed.checkpoint());
        return new CompactionResult(true,
                new PromptContext(plan.retainedPrompt(), committed.checkpoint().summary(),
                        (int) Math.min(Integer.MAX_VALUE, compactedTokens), Optional.empty(), true),
                Optional.of(committed.checkpoint()), plan, Optional.of(committed), false);
    }

    /**
     * 自动摘要失败时保留完整可发送 envelope；不伪造新 Checkpoint，并让恢复状态机知道本次只是跳过压缩。
     */
    private static CompactionResult fallbackPromptAfterAutomaticSummaryFailure(
            CheckpointStore.Snapshot snapshot, ContextPolicy.Plan plan, SummaryDocument previous) {
        boolean usesCheckpoint = snapshot.checkpoint().isPresent();
        Optional<ModelContinuation> continuation = usesCheckpoint
                ? Optional.empty() : plan.continuation();
        return new CompactionResult(usesCheckpoint,
                new PromptContext(plan.fullPrompt(), previous, plan.fullPromptTokens(),
                        continuation, usesCheckpoint), snapshot.checkpoint(), plan, Optional.empty(), true);
    }

    /** 只暴露首次精确计量，生命周期终态仍由持久回执 owner 发布。 */
    @FunctionalInterface
    public interface CompactionStartObserver {
        /** 在任何摘要或 Checkpoint 写入之前接收本次完整提示的官方 Token 数。 */
        void started(long inputTokensBefore);
    }

    /**
     * 重投影幂等事务竞态返回的获胜者，不发布第二份回执；只有复用既有行时要求摘要输入为空。
     */
    private static CompactionResult reuseCurrentSourceCheckpoint(CompactionRequest request,
                                                                 ContextPolicy.Plan plan,
                                                                 CheckpointStore.ContextCheckpoint checkpoint,
                                                                 boolean requireEmptySummaryInput,
                                                                 Optional<CheckpointStore.CommittedCheckpoint> receipt) {
        if (requireEmptySummaryInput && !plan.summaryInput().isEmpty()) {
            throw new ContextException(ContextException.Code.CAS_CONFLICT,
                    "context source revision already has a checkpoint");
        }
        // 复用 Checkpoint 必须沿用首次提交时仅统计动态内容的口径。
        ContextTokenMeter.Measurement measurement = request.meter().measure(
                plan.retainedPrompt(), checkpoint.summary(), Optional.empty(), true);
        if (!checkpoint.envelopeFingerprint().equals(measurement.fingerprint())) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "checkpoint envelope fingerprint did not match rebuilt provider request");
        }
        long tokens = measurement.inputTokens();
        if (tokens > request.budget().sendCeilingTokens()) {
            throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                    "existing checkpoint prompt exceeds the provider window");
        }
        return new CompactionResult(true,
                new PromptContext(plan.retainedPrompt(), checkpoint.summary(),
                        (int) Math.min(Integer.MAX_VALUE, tokens), Optional.empty(), true),
                Optional.of(checkpoint), plan, receipt, false);
    }

    /**
     * CAS 提交后从持久状态重新读取并重建 envelope；任何行或指纹漂移都在 Provider IO 前关闭。
     */
    private void verifyPersistedFingerprint(
            CompactionRequest request, List<ContextMessage> retainedPrompt,
            CheckpointStore.ContextCheckpoint committed) {
        CheckpointStore.ContextCheckpoint persisted = checkpoints.read(request.threadId()).checkpoint()
                .orElseThrow(() -> new ContextException(ContextException.Code.INVALID_STATE,
                        "committed checkpoint was not readable"));
        if (!persisted.equals(committed)) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "committed checkpoint changed during rebuild");
        }
        ContextTokenMeter.Measurement rebuilt = request.meter().measure(
                retainedPrompt, persisted.summary(), Optional.empty(), true);
        if (!persisted.envelopeFingerprint().equals(rebuilt.fingerprint())) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "committed checkpoint fingerprint did not match rebuilt provider request");
        }
    }

    /**
     * 在存储所有权之外调用慢生成器，并以 fail-closed 方式拒绝全部畸形结果。
     */
    private SummaryGenerator.SummaryResult generateSummary(String threadId, SummaryDocument previous,
                                                           ContextPolicy.Plan plan,
                                                           ContextBudget budget) {
        try {
            Optional<SummaryDocument> previousInput = previous.hasNoFacts()
                    ? Optional.empty() : Optional.of(previous);
            SummaryGenerator.SummaryResult result = generator.generate(new SummaryGenerator.SummaryRequest(threadId,
                    previousInput, plan.summaryInput(), plan.splitTurn(), ContextCompactionEvent.STRATEGY_VERSION,
                    budget));
            if (result == null || result.document().hasNoFacts()) {
                throw new ContextException(ContextException.Code.SUMMARY_FAILURE,
                        "summary generator returned an invalid structured result");
            }
            return result;
        } catch (CancellationException failure) {
            throw failure;
        } catch (ContextException failure) {
            if (failure.code() == ContextException.Code.SUMMARY_FAILURE) {
                throw failure;
            }
            throw new ContextException(ContextException.Code.SUMMARY_FAILURE,
                    "context summary generation failed", failure);
        } catch (RuntimeException failure) {
            throw new ContextException(ContextException.Code.SUMMARY_FAILURE,
                    "context summary generation failed", failure);
        }
    }


    /**
     * 为 overflow 重试重投影已经 Checkpoint 化的尾部，不为同一源创建第二行。首次压缩已持久化
     * 累积摘要；复用该 Checkpoint 且只收缩提示内 Tool 字节，可保持 append-only 唯一性且不丢事实。
     */
    CompactionResult reprojectCommittedForOverflow(CompactionRequest request, CompactionResult committed) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(committed, "committed");
        CheckpointStore.ContextCheckpoint checkpoint = committed.checkpoint().orElseThrow(() ->
                new IllegalArgumentException("overflow reprojection requires a committed checkpoint"));
        if (!committed.compacted() || !request.threadId().equals(checkpoint.threadId())
            || request.sourceRevision() != committed.plan().sourceRevision()
            || checkpoint.sourceRevision() > request.sourceRevision()) {
            throw new IllegalArgumentException("overflow reprojection source mismatch");
        }
        ToolOutputProjector projector = new ToolOutputProjector(
                ToolProjectionLimits.artifactOnlyProjection());
        List<ContextMessage> reprojected = committed.plan().retained().stream()
                .map(message -> message.project(projector)).toList();
        long tokens = request.meter().measure(
                reprojected, checkpoint.summary(), Optional.empty(), true).inputTokens();
        if (tokens > request.budget().sendCeilingTokens()) {
            throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                    "overflow retry prompt still exceeds the provider window");
        }
        return new CompactionResult(true,
                new PromptContext(reprojected, checkpoint.summary(),
                        (int) Math.min(Integer.MAX_VALUE, tokens), Optional.empty(), true),
                Optional.of(checkpoint), committed.plan(), Optional.empty(), false);
    }

    /**
     * 模型摘要前捕获的输入；所有失败路径都保持源消息不可变。
     */
    public record CompactionRequest(
            String threadId,
            long sourceRevision,
            List<ContextMessage> messages,
            ContextBudget budget,
            boolean forceCompaction,
            Optional<ModelContinuation> continuation,
            ToolProjectionLimits outputLimits,
            ContextTokenMeter meter,
            CancellationToken cancellation) {
        /**
         * 为不可取消的纯状态机调用保留兼容构造形状；生产编排统一传入连接或 Turn 令牌。
         */
        public CompactionRequest(String threadId, long sourceRevision, List<ContextMessage> messages,
                                 ContextBudget budget, boolean forceCompaction,
                                 Optional<ModelContinuation> continuation, ToolProjectionLimits outputLimits,
                                 ContextTokenMeter meter) {
            this(threadId, sourceRevision, messages, budget, forceCompaction, continuation,
                    outputLimits, meter, CancellationToken.none());
        }

        /**
         * 冻结源列表，并为 overflow 重试显式携带输出投影限制。
         */
        public CompactionRequest {
            if (threadId == null || threadId.isBlank() || sourceRevision < 0) {
                throw new IllegalArgumentException("invalid compaction thread");
            }
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            budget = Objects.requireNonNull(budget, "budget");
            continuation = Objects.requireNonNull(continuation, "continuation");
            outputLimits = Objects.requireNonNull(outputLimits, "outputLimits");
            meter = Objects.requireNonNull(meter, "meter");
            cancellation = Objects.requireNonNull(cancellation, "cancellation");
        }

        /**
         * 创建执行一次显式收缩并强制本地压缩的请求。
         */
        public CompactionRequest shrinkForOverflow() {
            return new CompactionRequest(threadId, sourceRevision, messages, budget, true, continuation,
                    ToolProjectionLimits.artifactOnlyProjection(), meter, cancellation);
        }
    }

    /**
     * 返回给 AgentLoop 的提示物化结果，不暴露存储或 Provider 实现类型。
     */
    public record PromptContext(
            List<ContextMessage> messages,
            SummaryDocument summary,
            int estimatedTokens,
            Optional<ModelContinuation> continuation,
            boolean localCompaction) {
        /**
         * 冻结提示消息，并让 Provider Adapter 可观察 continuation 重置。
         */
        public PromptContext {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            summary = Objects.requireNonNull(summary, "summary");
            if (estimatedTokens < 0) {
                throw new IllegalArgumentException("estimatedTokens must be non-negative");
            }
            continuation = Objects.requireNonNull(continuation, "continuation");
        }
    }

    /**
     * 区分成功追加与未改变提示，同时保留纯策略证据。
     */
    public record CompactionResult(
            boolean compacted,
            PromptContext prompt,
            Optional<CheckpointStore.ContextCheckpoint> checkpoint,
            ContextPolicy.Plan plan,
            Optional<CheckpointStore.CommittedCheckpoint> committedReceipt,
            boolean automaticSummaryFailed) {
        /**
         * 冻结全部结果数据，防止后续 Provider 调用改变 Checkpoint 证据。
         */
        public CompactionResult {
            prompt = Objects.requireNonNull(prompt, "prompt");
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            plan = Objects.requireNonNull(plan, "plan");
            committedReceipt = Objects.requireNonNull(committedReceipt, "committedReceipt");
            if (automaticSummaryFailed && (committedReceipt.isPresent() || !plan.requiresCompaction())) {
                throw new IllegalArgumentException("automatic summary fallback cannot contain a committed receipt");
            }
            if (compacted != prompt.localCompaction() || (compacted != checkpoint.isPresent())) {
                throw new IllegalArgumentException("compaction result state mismatch");
            }
            final Optional<CheckpointStore.ContextCheckpoint> committedCheckpoint = checkpoint;
            final Optional<CheckpointStore.CommittedCheckpoint> committedReceiptValue = committedReceipt;
            committedReceiptValue.ifPresent(receipt -> {
                if (committedCheckpoint.isEmpty()
                    || !committedCheckpoint.orElseThrow().equals(receipt.checkpoint())) {
                    throw new IllegalArgumentException("compaction receipt checkpoint mismatch");
                }
            });
        }
    }
}
