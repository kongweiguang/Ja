// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

/**
 * 一次模型响应中可审计的原始与规范化 Token 计量。
 *
 * <p>不同供应商对 input 是否包含缓存读取的定义并不相同，因此保留原始字段及其口径；只有
 * 口径和缓存读取同时可信时才计算新输入，不能把缺失的缓存数据写成零。</p>
 */
public record ModelUsage(long inputTokens, long outputTokens, long totalTokens,
                         Long cacheReadTokens, Long cacheWriteTokens,
                         InputAccounting inputAccounting) {
    /** 输入字段与缓存读取之间的供应商原始口径，UNKNOWN 时不提供推算的新输入。 */
    public enum InputAccounting {
        /** inputTokens 已经排除缓存读取，因此它本身就是新输入。 */
        INPUT_EXCLUDES_CACHE,
        /** inputTokens 包含缓存读取，需减去可靠的 cacheReadTokens 才能得到新输入。 */
        INPUT_INCLUDES_CACHE,
        /** 供应商没有公布足以安全归一化的口径。 */
        UNKNOWN
    }

    /**
     * 保持旧 Provider 适配器的三字段构造路径，但明确其缓存口径未知，避免旧实现静默制造零缓存。
     */
    public ModelUsage(long inputTokens, long outputTokens, long totalTokens) {
        this(inputTokens, outputTokens, totalTokens, null, null, InputAccounting.UNKNOWN);
    }

    /**
     * 总量允许包含缓存或推理开销；用减法校验避免上游超大计数在 long 加法中回绕。
     */
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < inputTokens
                || outputTokens > totalTokens - inputTokens
                || cacheReadTokens != null && cacheReadTokens < 0
                || cacheWriteTokens != null && cacheWriteTokens < 0) {
            throw new IllegalArgumentException("invalid model usage");
        }
        inputAccounting = java.util.Objects.requireNonNull(inputAccounting, "inputAccounting");
        if (inputAccounting == InputAccounting.INPUT_INCLUDES_CACHE
                && cacheReadTokens != null && cacheReadTokens > inputTokens) {
            throw new IllegalArgumentException("cache read tokens exceed input tokens");
        }
    }

    /**
     * 仅在供应商明示输入口径时生成规范化的新输入；返回 null 表示未测得而非免费或零消耗。
     */
    public Long newInputTokens() {
        return switch (inputAccounting) {
            case INPUT_EXCLUDES_CACHE -> inputTokens;
            case INPUT_INCLUDES_CACHE -> cacheReadTokens == null ? null : inputTokens - cacheReadTokens;
            case UNKNOWN -> null;
        };
    }
}
