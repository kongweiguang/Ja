// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

/**
 * 一次模型响应中可审计的输入、输出与总 Token 计量。
 */
public record ModelUsage(long inputTokens, long outputTokens, long totalTokens) {
    /**
     * 总量允许包含缓存或推理开销，但不得小于显式输入与输出之和。
     */
    public ModelUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < inputTokens + outputTokens) {
            throw new IllegalArgumentException("invalid model usage");
        }
    }
}
