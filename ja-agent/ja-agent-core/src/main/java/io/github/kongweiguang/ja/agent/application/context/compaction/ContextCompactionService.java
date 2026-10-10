// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.context.compaction;

import io.github.kongweiguang.ja.agent.application.context.ContextBaseline;
import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.application.context.ContextPolicy;
import io.github.kongweiguang.ja.agent.application.context.ContextRequestValidation;
import io.github.kongweiguang.ja.agent.application.context.ContextTokenMeter;
import io.github.kongweiguang.ja.agent.application.context.ModelContinuation;
import io.github.kongweiguang.ja.agent.application.context.summary.SummaryGenerator;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.RetainedSplit;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.context.ToolOutputProjector;
import io.github.kongweiguang.ja.agent.domain.context.ToolProjection;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * 协调纯策略、慢摘要生成和唯一窄 Checkpoint CAS 边界。
 */
public final class ContextCompactionService {
    /**
     * 持久 checkpoint 的策略版本由领域实现维护，不依赖应用层压缩事件协议。
     */
    public static final String STRATEGY_VERSION = "ja-context-v1";

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
     * 重投影幂等事务竞态返回的获胜者，不发布第二份回执；保留上界做发送门禁，并用基础估算展示既有 Checkpoint。
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
                        (int) Math.min(Integer.MAX_VALUE, measurement.estimatedTokens()), Optional.empty(), true),
                Optional.of(checkpoint), plan, receipt);
    }

    /**
     * 压缩一个冻结源快照，并仅在 CAS 成功后清除 Provider continuation。
     */
    public CompactionResult compact(CompactionRequest request) {
        return compact(request, ignored -> {
        });
    }

    /**
     * 在策略确认确需压缩并取得完整 envelope 计量后同步通知 started；安全上界负责准入，基础估算负责提示与 Checkpoint 展示。
     * 基础估算还必须严格下降，才能满足 Checkpoint 与压缩事件合同；在唯一 Checkpoint 事务前完成双口径核对，避免提交无法发布的事实。
     * 没有可淘汰事实且压缩投影不更小时直接保留原提示，避免短对话误报窗口不足或产生空检查点。
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
        Optional<CheckpointStore.ContextCheckpoint> baseCheckpoint = request.baseline().isPresent()
                ? request.baseline().orElseThrow().checkpoint() : snapshot.checkpoint();
        SummaryDocument previous = request.baseline().map(ContextBaseline::previousSummary)
                .orElseGet(() -> snapshot.checkpoint().map(CheckpointStore.ContextCheckpoint::summary)
                        .orElseGet(SummaryDocument::empty));
        if (baseCheckpoint.filter(checkpoint -> !checkpoint.threadId().equals(request.threadId())
                                                || checkpoint.sourceRevision() > request.sourceRevision()).isPresent()) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "context baseline checkpoint does not match the frozen history");
        }
        long baseThrough = baseCheckpoint
                .map(CheckpointStore.ContextCheckpoint::throughOrdinal).orElse(0L);
        long baseRetainedFrom = baseCheckpoint
                .map(CheckpointStore.ContextCheckpoint::retainedFromOrdinal).orElse(0L);
        Optional<RetainedSplit> previousRetainedSplit = baseCheckpoint
                .flatMap(CheckpointStore.ContextCheckpoint::retainedSplit);
        CheckpointStore.ProjectionRequest projectionRequest = new CheckpointStore.ProjectionRequest(
                request.threadId(), request.sourceRevision(), request.projectionBinding(), clock.instant());
        CheckpointStore.ProjectionSnapshot projection = checkpoints.prepareProjection(projectionRequest);
        ContextPolicy.PlanningInput input = new ContextPolicy.PlanningInput(request.threadId(),
                request.messages(), previous, request.sourceRevision(), baseThrough, baseRetainedFrom,
                previousRetainedSplit, request.budget(),
                request.forceCompaction(), request.continuation(), request.outputLimits(), projection.selections());
        ContextPolicy.Plan plan = policy.plan(input, request.meter());
        checkpoints.persistProjection(projection, plan.projectionChoices());
        boolean noSmallerEnvelope = plan.summaryInput().isEmpty() && plan.fullPromptFits()
                                    && plan.compactedPromptTokens() >= plan.fullPromptTokens();
        if (!plan.requiresCompaction() || noSmallerEnvelope) {
            if (!plan.fullPromptFits()) {
                throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                        "context exceeds the provider send ceiling while automatic compaction is disabled");
            }
            boolean usesCheckpoint = baseCheckpoint.isPresent();
            Optional<ModelContinuation> continuation = usesCheckpoint
                    ? Optional.empty() : plan.continuation();
            ContextTokenMeter.Measurement fullMeasurement = request.meter().measure(
                    plan.fullPrompt(), previous, continuation, false);
            if (fullMeasurement.inputTokens() > request.budget().sendCeilingTokens()) {
                throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                        "prepared prompt exceeds the provider send ceiling");
            }
            return new CompactionResult(usesCheckpoint,
                    new PromptContext(plan.fullPrompt(), previous,
                            (int) Math.min(Integer.MAX_VALUE, fullMeasurement.estimatedTokens()),
                            continuation, usesCheckpoint), baseCheckpoint, plan, Optional.empty());
        }
        /*
         * Checkpoint 事务本身会推进 Thread revision。下一轮相同不可变消息源因此具有新 revision
         * 但没有新事实；策略没有新摘要输入时复用持久摘要，不为同一源分配第二个 revision 或回执。
         */
        Optional<CheckpointStore.ContextCheckpoint> currentSourceCheckpoint = baseCheckpoint
                .filter(checkpoint -> checkpoint.sourceRevision() == request.sourceRevision()
                                      || (checkpoint.sourceRevision() < request.sourceRevision()
                                          && plan.summaryInput().isEmpty()));
        if (currentSourceCheckpoint.isPresent()) {
            CheckpointStore.ContextCheckpoint checkpoint = currentSourceCheckpoint.orElseThrow();
            boolean sameSource = checkpoint.sourceRevision() == request.sourceRevision();
            ensureCheckpointProjection(projectionRequest, plan);
            return reuseCurrentSourceCheckpoint(request, plan, checkpoint, sameSource,
                    Optional.empty());
        }

        ContextTokenMeter.Measurement fullMeasurement = request.meter().measure(
                plan.fullPrompt(), previous, plan.continuation(), false);
        observer.started(fullMeasurement.estimatedTokens());
        request.cancellation().throwIfCancellationRequested();

        SummaryGenerator.SummaryResult generated = plan.summaryInput().isEmpty()
                ? new SummaryGenerator.SummaryResult(SummaryDocument.empty(), CheckpointUsage.none())
                : generateSummary(request.threadId(), previous, plan, request.budget());

        SummaryDocument replacement = generated.document();
        request.cancellation().throwIfCancellationRequested();
        ContextTokenMeter.Measurement compactedMeasurement = request.meter().measure(
                plan.retainedPrompt(), replacement, Optional.empty(), true);
        request.cancellation().throwIfCancellationRequested();
        long compactedTokens = compactedMeasurement.inputTokens();
        long compactedEstimatedTokens = compactedMeasurement.estimatedTokens();
        if (compactedTokens > request.budget().sendCeilingTokens()
            || compactedTokens >= plan.fullPromptTokens()
            || compactedEstimatedTokens >= fullMeasurement.estimatedTokens()) {
            throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                    "compacted context did not produce a smaller sendable envelope");
        }
        long checkpointSourceRevision = generator.checkpointSourceRevision(request.sourceRevision());
        CheckpointStore.ContextCheckpoint checkpoint = new CheckpointStore.ContextCheckpoint(
                checkpointIds.get(), request.threadId(), plan.throughOrdinal(),
                plan.retainedFromOrdinal(), checkpointSourceRevision, plan.retainedSplit(), replacement,
                (int) Math.min(Integer.MAX_VALUE, compactedEstimatedTokens), compactedMeasurement.fingerprint(),
                STRATEGY_VERSION,
                generated.usage(), clock.instant());
        CheckpointStore.CommittedCheckpoint committed;
        try {
            /*
             * 这是新 Checkpoint 的唯一持久边界。Adapter 同时提交 Checkpoint 行、Thread revision
             * 和确定性压缩回执，因此之后的 Provider 失败或进程退出不会留下半可见事实。
             */
            request.cancellation().throwIfCancellationRequested();
            Optional<CheckpointStore.TurnOperation> turnOperation = Objects.requireNonNull(
                    generator.checkpointTurnOperation(), "checkpointTurnOperation");
            if (turnOperation.isEmpty()) {
                throw new ContextException(ContextException.Code.CAS_CONFLICT,
                        "checkpoint commit requires a persistent execution owner");
            }
            committed = checkpoints.commit(new CheckpointStore.CommitRequest(
                    request.threadId(), checkpointSourceRevision, checkpoint,
                    turnOperation));
        } catch (CheckpointStore.CommitConflict failure) {
            throw new ContextException(ContextException.Code.CAS_CONFLICT,
                    "context source changed while summary was generated", failure);
        }
        if (!committed.newlyCommitted()) {
            ensureCheckpointProjection(projectionRequest, plan);
            return reuseCurrentSourceCheckpoint(request, plan, committed.checkpoint(), false,
                    Optional.of(committed));
        }
        ensureCheckpointProjection(projectionRequest, plan);
        verifyPersistedFingerprint(request, plan.retainedPrompt(), committed.checkpoint());
        return new CompactionResult(true,
                new PromptContext(plan.retainedPrompt(), committed.checkpoint().summary(),
                        (int) Math.min(Integer.MAX_VALUE, compactedEstimatedTokens), Optional.empty(), true),
                Optional.of(committed.checkpoint()), plan, Optional.of(committed));
    }

    /**
     * Checkpoint 可能在进程退出后先于阶段写入落库；以 source/binding 幂等地补齐新阶段并在 Provider
     * IO 前持久选择，不能把已有摘要误当作旧阶段仍然可重投影的许可。
     */
    private void ensureCheckpointProjection(CheckpointStore.ProjectionRequest request,
                                            ContextPolicy.Plan plan) {
        CheckpointStore.ProjectionSnapshot checkpointProjection = checkpoints.advanceProjection(
                request, CheckpointStore.ProjectionReason.CHECKPOINT);
        checkpoints.persistProjection(checkpointProjection, plan.projectionChoices());
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
                    previousInput, plan.summaryInput(), plan.splitTurn(), STRATEGY_VERSION,
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
     * 为 overflow 重试重投影已经 Checkpoint 化的尾部，不为同一源创建第二行。摘要与规划仍绑定
     * 原冻结请求；持久 Checkpoint 的 CAS revision 可更晚，因为摘要阶段本身会提交执行进度事实。
     */
    CompactionResult reprojectCommittedForOverflow(CompactionRequest request, CompactionResult committed) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(committed, "committed");
        CheckpointStore.ContextCheckpoint checkpoint = committed.checkpoint().orElseThrow(() ->
                new IllegalArgumentException("overflow reprojection requires a committed checkpoint"));
        if (!committed.compacted()) {
            throw new IllegalArgumentException("overflow reprojection requires a compacted context");
        }
        if (!request.threadId().equals(checkpoint.threadId())) {
            throw new IllegalArgumentException("overflow reprojection checkpoint belongs to another thread");
        }
        if (request.sourceRevision() != committed.plan().sourceRevision()) {
            throw new IllegalArgumentException("overflow reprojection plan does not match the frozen source revision");
        }
        CheckpointStore.Snapshot current = checkpoints.read(request.threadId());
        if (!request.threadId().equals(current.threadId())
            || current.threadRevision() < checkpoint.sourceRevision()) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "overflow checkpoint is not present in the confirmed conversation revision");
        }
        CheckpointStore.ProjectionRequest projectionRequest = new CheckpointStore.ProjectionRequest(
                request.threadId(), current.threadRevision(), request.projectionBinding(), clock.instant());
        CheckpointStore.ProjectionSnapshot overflowProjection = checkpoints.advanceProjection(
                projectionRequest, CheckpointStore.ProjectionReason.OVERFLOW);
        Map<String, ToolProjection> selections = committed.plan().retained().stream()
                .filter(ContextMessage::hasToolResult)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ContextMessage::messageId,
                        ignored -> ToolProjection.ARTIFACT));
        checkpoints.persistProjection(overflowProjection, selections);
        ToolOutputProjector projector = new ToolOutputProjector(ToolProjectionLimits.artifactOnlyProjection());
        List<ContextMessage> reprojected = committed.plan().retained().stream()
                .map(message -> message.project(projector)).toList();
        ContextTokenMeter.Measurement measurement = request.meter().measure(
                reprojected, checkpoint.summary(), Optional.empty(), true);
        long tokens = measurement.inputTokens();
        if (tokens > request.budget().sendCeilingTokens()) {
            throw new ContextException(ContextException.Code.CONTEXT_LIMIT,
                    "overflow retry prompt still exceeds the provider window");
        }
        return new CompactionResult(true,
                new PromptContext(reprojected, checkpoint.summary(),
                        (int) Math.min(Integer.MAX_VALUE, measurement.estimatedTokens()), Optional.empty(), true),
                Optional.of(checkpoint), committed.plan(), Optional.empty());
    }


    /**
     * 只暴露首次精确计量，生命周期终态仍由持久回执 owner 发布。
     */
    @FunctionalInterface
    public interface CompactionStartObserver {
        /**
         * 在任何摘要或 Checkpoint 写入之前接收本次完整提示的基础估算；准入上界仍由 ContextPolicy 单独校验。
         */
        void started(long inputTokensBefore);
    }

    /**
     * 模型摘要前捕获的输入；所有失败路径都保持源消息不可变。
     *
     * @param threadId 所属对话的稳定标识
     * @param sourceRevision 来源修订版本
     * @param messages 消息列表
     * @param budget 预算
     * @param forceCompaction 是否强制压缩上下文
     * @param continuation 续跑（可选）
     * @param outputLimits 工具结果投影的容量约束
     * @param projectionBinding 投影绑定
     * @param meter 计量器
     * @param cancellation 取消控制
     * @param baseline 基线（可选）
     */
    public record CompactionRequest(String threadId,
                                    long sourceRevision,
                                    List<ContextMessage> messages,
                                    ContextBudget budget,
                                    boolean forceCompaction,
                                    Optional<ModelContinuation> continuation,
                                    ToolProjectionLimits outputLimits,
                                    CheckpointStore.ProjectionBinding projectionBinding,
                                    ContextTokenMeter meter,
                                    CancellationToken cancellation,
                                    Optional<ContextBaseline> baseline) {
        /**
         * 冻结源列表，并为 overflow 重试显式携带输出投影限制。
         */
        public CompactionRequest {
            ContextRequestValidation.validate(threadId, sourceRevision, messages, budget, continuation,
                    outputLimits, projectionBinding, meter, cancellation, baseline);
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }

        /**
         * 创建执行一次显式收缩并强制本地压缩的请求。
         */
        public CompactionRequest shrinkForOverflow() {
            return new CompactionRequest(threadId, sourceRevision, messages, budget, true, continuation,
                    ToolProjectionLimits.artifactOnlyProjection(), projectionBinding, meter, cancellation, baseline);
        }
    }

    /**
     * 返回给执行层的提示物化结果，不暴露存储或 Provider 实现类型。
     *
     * @param messages 消息列表
     * @param summary 摘要
     * @param estimatedTokens 估算的 Token 数
     * @param continuation 续跑（可选）
     * @param localCompaction 是否使用本地上下文压缩
     */
    public record PromptContext(List<ContextMessage> messages,
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
     *
     * @param compacted 是否已压缩上下文
     * @param prompt 提示词
     * @param checkpoint 检查点（可选）
     * @param plan 上下文保留与压缩计划
     * @param committedReceipt 已提交回执（可选）
     */
    public record CompactionResult(boolean compacted,
                                   PromptContext prompt,
                                   Optional<CheckpointStore.ContextCheckpoint> checkpoint,
                                   ContextPolicy.Plan plan,
                                   Optional<CheckpointStore.CommittedCheckpoint> committedReceipt) {
        /**
         * 冻结全部结果数据，防止后续 Provider 调用改变 Checkpoint 证据。
         */
        public CompactionResult {
            prompt = Objects.requireNonNull(prompt, "prompt");
            checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            plan = Objects.requireNonNull(plan, "plan");
            committedReceipt = Objects.requireNonNull(committedReceipt, "committedReceipt");
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
