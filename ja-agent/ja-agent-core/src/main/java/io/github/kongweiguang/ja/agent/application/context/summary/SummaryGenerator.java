// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.context.summary;

import io.github.kongweiguang.ja.agent.application.context.ContextPolicy;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 隔离慢摘要副作用，使压缩服务只依赖结构化输入输出而不感知具体 Provider。
 */
public interface SummaryGenerator {
    /**
     * 根据淘汰前缀和既有摘要生成结构化增量；实现必须遵守取消与容量边界。
     */
    SummaryResult generate(SummaryRequest request);

    /**
     * Summary Operation 内部提交会推进会话 revision，checkpoint 必须绑定推进后的同一快照。
     */
    long checkpointSourceRevision(long originalRevision);

    /**
     * 返回同一会话执行的事务 CAS；没有 owner 时拒绝提交检查点。
     */
    Optional<CheckpointStore.TurnOperation> checkpointTurnOperation();

    /**
     * 将结构化摘要与本次模型用量绑定，便于同一 Checkpoint 原子保存审计证据。
     *
     * @param document 文档
     * @param usage 用量
     */
    record SummaryResult(SummaryDocument document,
                         CheckpointUsage usage) {
        /**
         * 禁止缺失摘要或用量对象，避免持久层用空值猜测是否调用过模型。
         */
        public SummaryResult {
            document = Objects.requireNonNull(document, "document");
            usage = Objects.requireNonNull(usage, "usage");
        }
    }

    /**
     * 冻结旧摘要、淘汰消息和 split-turn 证据，保证慢模型调用基于同一源快照。
     *
     * @param threadId 所属对话的稳定标识
     * @param previousSummary 前一次摘要（可选）
     * @param evictedMessages 被移除消息列表
     * @param splitTurn 被拆分的执行轮次（可选）
     * @param strategyVersion 策略版本
     * @param budget 预算
     */
    record SummaryRequest(String threadId,
                          Optional<SummaryDocument> previousSummary,
                          List<ContextMessage> evictedMessages,
                          Optional<ContextPolicy.TurnSplit> splitTurn,
                          String strategyVersion,
                          ContextBudget budget) {
        /**
         * 校验 Thread 与策略版本并复制集合，避免生成期间输入被并发修改。
         */
        public SummaryRequest {
            if (threadId == null || threadId.isBlank() || strategyVersion == null
                || strategyVersion.isBlank()) {
                throw new IllegalArgumentException("invalid summary request");
            }
            previousSummary = Objects.requireNonNull(previousSummary, "previousSummary");
            evictedMessages = List.copyOf(Objects.requireNonNull(evictedMessages, "evictedMessages"));
            splitTurn = Objects.requireNonNull(splitTurn, "splitTurn");
            budget = Objects.requireNonNull(budget, "budget");
        }
    }
}
