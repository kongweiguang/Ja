// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.context;

import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;

import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;

import java.util.List;
import java.util.Optional;

/**
 * 统一从 Provider adapter 获取完整提示的基础展示估计与保守准入上界，禁止上下文策略另建口径或冒充实际 Usage。
 */
@FunctionalInterface
public interface ContextTokenMeter {
    /**
     * 计量 System、Messages、Tool Schema、结构化输出和 continuation 共同构成的完整 envelope。
     */
    Measurement measure(List<ContextMessage> messages, SummaryDocument summary,
                        Optional<ModelContinuation> continuation, boolean localCompaction);

    /**
     * 将准入上界、基础展示估计与 Provider envelope 指纹绑定；预算只用上界，checkpoint 展示只用基础估计。
     *
     * @param inputTokens 保守准入上界，供窗口校验与压缩策略使用
     * @param estimatedTokens 不含安全余量的基础估算，供用户展示与 checkpoint 持久化
     * @param fingerprint 当前内容或配置的校验指纹
     */
    record Measurement(long inputTokens,
                       long estimatedTokens,
                       String fingerprint) {
        /**
         * 拒绝上界低于基础估算、负数和非 SHA-256 指纹，避免展示值越过安全预算口径。
         */
        public Measurement {
            if (estimatedTokens < 0 || inputTokens < estimatedTokens
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid context token measurement");
            }
        }
    }
}
