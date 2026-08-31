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
        Objects.requireNonNull(binding, "binding");
        binding.cancellationToken().throwIfCancellationRequested();
        if (!clock.instant().isBefore(binding.deadline())) {
            throw new ContextException(ContextException.Code.SUMMARY_FAILURE,
                    "summary model deadline expired before Turn binding");
        }
        SummaryModel model = Objects.requireNonNull(summaryModels.bind(binding),
                "summary model factory returned null");
        SummaryGenerator generator = new ModelSummaryGenerator(model, binding, clock, limits);
        return new ContextOrchestrator(new ContextCompactionService(
                checkpoints, new ContextPolicy(), generator, clock,
                ContextCompactionService::newCheckpointId), binding.threadId());
    }
}
