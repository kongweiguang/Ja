// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.context;

import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;

import java.util.Objects;
import java.util.Optional;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore.ContextCheckpoint;

/**
 * 请求实际采用的上下文基线；checkpoint 与初始 seed 摘要共享压缩规划语义。
 *
 * @param checkpoint 检查点（可选）
 * @param previousSummary 前一次摘要
 */
public record ContextBaseline(Optional<CheckpointStore.ContextCheckpoint> checkpoint,
                              SummaryDocument previousSummary) {
    /**
     * 固定来源和摘要正文，防止旧工具批次恢复时被会话最新 checkpoint 替换。
     */
    public ContextBaseline {
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        previousSummary = Objects.requireNonNull(previousSummary, "previousSummary");
        if (checkpoint.isPresent()) {
            ContextCheckpoint value = checkpoint.orElseThrow();
            if (!value.summary().equals(previousSummary)) {
                throw new IllegalArgumentException("checkpoint summary differs from context baseline");
            }
        }
    }
}
