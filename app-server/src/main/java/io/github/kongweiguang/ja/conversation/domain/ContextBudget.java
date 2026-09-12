// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

/**
 * 根据 Provider 能力与用户压缩选择计算当前请求的上下文预算。
 */
public record ContextBudget(
        long contextWindowTokens,
        long maxOutputTokens,
        boolean autoCompact) {
    private static final long DEFAULT_COMPACTION_RESERVE_TOKENS = 16_384L;

    /**
     * 在构造期拒绝不可能的 Provider 能力，避免负数或输出预算吞噬整个上下文窗口。
     */
    public ContextBudget {
        if (contextWindowTokens < 1 || maxOutputTokens < 0 || maxOutputTokens > contextWindowTokens) {
            throw new IllegalArgumentException("invalid context budget");
        }
    }

    /** 创建只含 Provider 当前能力与自动压缩选择的预算，输入用量始终由官方计量接口提供。 */
    public static ContextBudget capabilities(
            long contextWindowTokens, long maxOutputTokens, boolean autoCompact) {
        return new ContextBudget(contextWindowTokens, maxOutputTokens, autoCompact);
    }

    /**
     * 只扣除最大输出，因为官方输入计量已经包含 System 与 Tool Schema，不能再次扣减。
     */
    public long sendCeilingTokens() {
        return contextWindowTokens - maxOutputTokens;
    }

    /**
     * 返回自动压缩专用的显式 reserve；最大 16K，且小窗口最多预留 40%，保证压缩目标仍可达。
     */
    public long compactionReserveTokens() {
        long ceiling = sendCeilingTokens();
        return Math.min(DEFAULT_COMPACTION_RESERVE_TOKENS, ceiling * 2L / 5L);
    }

    /**
     * 输出预算和压缩 reserve 共享窗口尾部，取较严格上限而不双扣；小窗口仍有可发送输入空间。
     */
    public long automaticCompactionThreshold() {
        long ceiling = sendCeilingTokens();
        return Math.min(ceiling, contextWindowTokens - compactionReserveTokens());
    }

    /** 压缩后优先降到可发送窗口 60%，为后续多轮 Tool 交互保留增长空间。 */
    public long compactedTargetTokens() {
        return sendCeilingTokens() * 3L / 5L;
    }

    /**
     * 根据可用窗口计算动态最近消息尾部目标，避免固定阈值浪费大窗口。
     */
    public long recentTailTokens() {
        long ceiling = sendCeilingTokens();
        long quarter = ceiling / 4L;
        return Math.min(ceiling, Math.min(20_000L, Math.max(8_000L, quarter)));
    }

}
