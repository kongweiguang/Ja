// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.context;

/**
 * 摘要 checkpoint 的模型用量凭证，与 Provider 协议和应用提交策略解耦。
 *
 * @param inputTokens 输入 Token 数
 * @param outputTokens 输出 Token 数
 * @param totalTokens 输入与输出的 Token 总数
 * @param cacheReadTokens 命中缓存的输入 Token 数
 * @param cacheWriteTokens 写入缓存的 Token 数
 */
public record CheckpointUsage(long inputTokens,
                              long outputTokens,
                              long totalTokens,
                              long cacheReadTokens,
                              long cacheWriteTokens) {
    /**
     * 拒绝负计数，防止异常用量污染持久审计和上下文预算判断。
     */
    public CheckpointUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0
            || cacheReadTokens < 0 || cacheWriteTokens < 0) {
            throw new IllegalArgumentException("checkpoint usage counters must be non-negative");
        }
    }

    /**
     * 对无需摘要模型调用的检查点提供显式零用量，而不是用 null 表示未消费。
     */
    public static CheckpointUsage none() {
        return new CheckpointUsage(0, 0, 0, 0, 0);
    }
}
