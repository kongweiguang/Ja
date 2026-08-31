// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.checkpoint;

/**
 * 记录摘要生成阶段的 Token 消耗，使 Checkpoint 能保留与正文压缩分离的审计证据。
 */
public record CheckpointUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long cacheReadTokens,
        long cacheWriteTokens) {

    /**
     * 拒绝负计数，避免异常 Provider 用量污染持久化统计和后续预算判断。
     */
    public CheckpointUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0
            || cacheReadTokens < 0 || cacheWriteTokens < 0) {
            throw new IllegalArgumentException("checkpoint usage counters must be non-negative");
        }
    }

    /**
     * 在无需调用摘要模型时提供显式零用量，避免以空值表达“未消费”。
     */
    public static CheckpointUsage none() {
        return new CheckpointUsage(0, 0, 0, 0, 0);
    }
}
