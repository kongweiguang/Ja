// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

/**
 * 一次模型响应中可审计的原始与规范化 Token 计量。
 *
 * <p>不同供应商对 input 是否包含缓存的定义并不相同，因此保留原始字段及其口径；只有
 * 口径和必需的缓存子项可信时才计算新输入，不能把缺失或冲突的缓存事实写成零。</p>
 *
 * @param inputTokens 输入 Token 数
 * @param outputTokens 输出 Token 数
 * @param totalTokens 输入与输出的 Token 总数
 * @param cacheReadTokens 命中缓存的输入 Token 数
 * @param cacheWriteTokens 写入缓存的 Token 数
 * @param inputAccounting 输入用量的计量方式
 */
public record ModelUsage(long inputTokens,
                         long outputTokens,
                         long totalTokens,
                         Long cacheReadTokens,
                         Long cacheWriteTokens,
                         InputAccounting inputAccounting) {
    /**
     * 保持旧 Provider 适配器的三字段构造路径，但明确其缓存口径未知，避免旧实现静默制造零缓存。
     */
    public ModelUsage(long inputTokens, long outputTokens, long totalTokens) {
        this(inputTokens, outputTokens, totalTokens, null, null, InputAccounting.UNKNOWN);
    }

    /**
     * 总量允许包含缓存或推理开销；inclusive input 的缓存子项不得超过原始 input，且用减法
     * 校验避免上游超大计数在 long 加法中回绕。
     */
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < inputTokens
            || outputTokens > totalTokens - inputTokens
            || cacheReadTokens != null && cacheReadTokens < 0
            || cacheWriteTokens != null && cacheWriteTokens < 0) {
            throw new IllegalArgumentException("invalid model usage");
        }
        inputAccounting = java.util.Objects.requireNonNull(inputAccounting, "inputAccounting");
        if (inputAccounting == InputAccounting.INPUT_INCLUDES_CACHE) {
            if (cacheReadTokens != null && cacheReadTokens > inputTokens
                || cacheWriteTokens != null && cacheWriteTokens > inputTokens
                || cacheReadTokens != null && cacheWriteTokens != null
                    && cacheWriteTokens > inputTokens - cacheReadTokens) {
                throw new IllegalArgumentException("cache tokens exceed input tokens");
            }
        }
    }

    /**
     * 仅在输入口径和缓存读取可信时生成新输入；明确报告的写缓存再扣除一次，缺失的写字段
     * 沿用该 Provider 的 inclusive-input 无写分项语义。返回 null 表示拆分未知而非零消耗。
     */
    public Long newInputTokens() {
        return switch (inputAccounting) {
            case INPUT_EXCLUDES_CACHE -> inputTokens;
            case INPUT_INCLUDES_CACHE -> cacheReadTokens == null ? null
                    : inputTokens - cacheReadTokens - (cacheWriteTokens == null ? 0 : cacheWriteTokens);
            case UNKNOWN -> null;
        };
    }

    /**
     * 输入字段与缓存读取之间的供应商原始口径，UNKNOWN 时不提供推算的新输入。
     */
    public enum InputAccounting {
        /**
         * inputTokens 已经排除缓存读取，因此它本身就是新输入。
         */
        INPUT_EXCLUDES_CACHE,
        /**
         * inputTokens 包含缓存子项；cacheReadTokens 必须已知，明确存在的 cacheWriteTokens 也需减去。
         */
        INPUT_INCLUDES_CACHE,
        /**
         * 供应商没有公布足以安全归一化的口径。
         */
        UNKNOWN
    }
}
