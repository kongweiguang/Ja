// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.compaction.ContextCompactionService;
import io.github.kongweiguang.ja.conversation.application.context.summary.ModelSummaryGenerator;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryGenerator;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel;

import java.time.Clock;
import java.util.Objects;

/**
 * 在组合根边界为单个 Turn 装配摘要模型、压缩服务和 Thread 绑定编排器。
 */
public final class ContextOrchestratorFactory {
    private final CheckpointStore checkpoints;
    private final Clock clock;
    private final SummaryModel.Factory summaryModels;
    private final ModelSummaryGenerator.Limits limits;

    /**
     * 使用生产默认摘要上限，避免普通调用方重复选择安全参数。
     */
    public ContextOrchestratorFactory(CheckpointStore checkpoints, Clock clock,
                                      SummaryModel.Factory summaryModels) {
        this(checkpoints, clock, summaryModels,
                ModelSummaryGenerator.Limits.defaults());
    }

    /**
     * 固定存储、时钟、模型工厂和摘要上限，便于测试替换外部边界而不改变策略。
     */
    public ContextOrchestratorFactory(CheckpointStore checkpoints, Clock clock,
                                      SummaryModel.Factory summaryModels,
                                      ModelSummaryGenerator.Limits limits) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.summaryModels = Objects.requireNonNull(summaryModels, "summaryModels");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * 在绑定仍有效时创建专属编排器；过期或取消的 Turn 不得启动摘要模型。
     */
    public ContextOrchestrator create(SummaryModel.TurnBinding binding) {
        return create(binding, ModelSummaryGenerator.SummaryOperation.none());
    }

    /**
     * 自动 Turn 压缩注入持久 Summary Operation；手动压缩继续使用无 Operation 的公开入口。
     */
    public ContextOrchestrator create(SummaryModel.TurnBinding binding,
                                       ModelSummaryGenerator.SummaryOperation operation) {
        Objects.requireNonNull(binding, "binding");
        binding.cancellationToken().throwIfCancellationRequested();
        if (!clock.instant().isBefore(binding.deadline())) {
            throw new ContextException(ContextException.Code.SUMMARY_FAILURE,
                    "summary model deadline expired before Turn binding");
        }
        return create(binding.threadId(), () -> ModelSummaryGenerator.RequestRuntime.unprofiled(binding), operation);
    }

    /**
     * 自动压缩在每个摘要计量和 Provider 发送安全点调用 runtimeFactory，避免 ContextOrchestrator
     * 的生命周期把 Provider、模型、Prompt、Skill 或 Tool catalog 固定到整个 assistant round。
     */
    public ContextOrchestrator create(String threadId,
                                      ModelSummaryGenerator.RequestRuntimeFactory runtimeFactory,
                                      ModelSummaryGenerator.SummaryOperation operation) {
        if (threadId == null || threadId.isBlank()) throw new IllegalArgumentException("invalid threadId");
        Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        Objects.requireNonNull(operation, "operation");
        SummaryGenerator generator = new ModelSummaryGenerator(
                summaryModels, runtimeFactory, clock, limits, operation);
        return new ContextOrchestrator(new ContextCompactionService(
                checkpoints, new ContextPolicy(), generator, clock,
                ContextCompactionService::newCheckpointId), threadId);
    }
}
