// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;

import java.util.List;
import java.util.Optional;

/**
 * 对完整 Provider 相关提示执行权威输入计量，禁止上下文策略再按字符推测 Token。
 */
@FunctionalInterface
public interface ContextTokenMeter {
    /**
     * 计量 System、Messages、Tool Schema、结构化输出和 continuation 共同构成的完整 envelope。
     */
    Measurement measure(List<ContextMessage> messages, SummaryDocument summary,
                        Optional<ModelContinuation> continuation, boolean localCompaction);

    /** 将权威 Token 值与 Provider envelope 指纹绑定，供提交后重建校验。 */
    record Measurement(long inputTokens, String fingerprint) {
        /** 拒绝负值和非 SHA-256 指纹，缺失计量必须通过异常表达。 */
        public Measurement {
            if (inputTokens < 0 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid context token measurement");
            }
        }
    }
}
