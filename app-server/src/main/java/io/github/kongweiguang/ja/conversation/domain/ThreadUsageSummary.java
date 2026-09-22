// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

/**
 * 一个 Thread 已持久化 Provider 用量的只读汇总。
 *
 * <p>每个 Token 数值都与其覆盖的请求数量成对返回：缺少 Provider 计量时，调用方必须展示
 * 未知而不是把它归零。缓存命中率的分子和分母仅覆盖输入、缓存读、缓存写均已报告的请求，
 * 防止不同 Provider 的缓存口径混入同一百分比。</p>
 */
public record ThreadUsageSummary(
        long snapshotRevision,
        long requestCount,
        long measuredRequestCount,
        long newInputRequestCount,
        long newInputTokens,
        long outputRequestCount,
        long outputTokens,
        long totalRequestCount,
        long totalTokens,
        long cacheReadRequestCount,
        long cacheReadTokens,
        long cacheWriteRequestCount,
        long cacheWriteTokens,
        long cacheCompleteRequestCount,
        long cacheCompleteInputTokens,
        long cacheCompleteReadTokens) {

    /**
     * 汇总值来自单个 SQLite 快照；在领域边界拒绝负数、覆盖范围倒置和无法关联的缓存样本，
     * 让破损持久化数据不能被 UI 误呈现为可信费用或命中率。
     */
    public ThreadUsageSummary {
        if (snapshotRevision < 0 || requestCount < 0 || measuredRequestCount < 0
                || measuredRequestCount > requestCount) {
            throw new IllegalArgumentException("invalid usage summary coverage");
        }
        validateMetric(newInputRequestCount, newInputTokens, measuredRequestCount);
        validateMetric(outputRequestCount, outputTokens, measuredRequestCount);
        validateMetric(totalRequestCount, totalTokens, measuredRequestCount);
        validateMetric(cacheReadRequestCount, cacheReadTokens, measuredRequestCount);
        validateMetric(cacheWriteRequestCount, cacheWriteTokens, measuredRequestCount);
        validateMetric(cacheCompleteRequestCount, cacheCompleteInputTokens, measuredRequestCount);
        if (cacheCompleteReadTokens < 0 || cacheCompleteReadTokens > cacheCompleteInputTokens) {
            throw new IllegalArgumentException("invalid complete cache accounting");
        }
    }

    /** 缓存完整样本的 Token 量不能脱离其对应的已计量请求范围。 */
    private static void validateMetric(long coveredRequests, long tokens, long measuredRequests) {
        if (coveredRequests < 0 || coveredRequests > measuredRequests || tokens < 0) {
            throw new IllegalArgumentException("invalid usage summary metric");
        }
    }
}
