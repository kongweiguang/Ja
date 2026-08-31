// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;

import java.time.Instant;
import java.util.Objects;

/**
 * 为自动标题的额外 Provider 调用保存独立审计事实，禁止混入普通 Turn 或 Checkpoint 用量。
 */
public interface AutomaticTitleUsagePort {
    /**
     * 在外部模型调用前持久抢占 Thread 唯一生成权；重复完成或重启只能观察既有声明。
     */
    ClaimResult claim(GenerationClaim claim);

    /**
     * 保存 Provider 调用的真实结果；缺失 Usage 必须显式保留为空而不能伪造零用量。
     */
    void recordModelOutcome(ModelOutcome outcome);

    /** 标题生成权的原子抢占结果。 */
    enum ClaimResult {
        /** 当前调用方首次取得执行一次外部模型调用的资格。 */
        ACQUIRED,
        /** 该 Thread 已经尝试或正在尝试生成，调用方只能执行本地恢复。 */
        ALREADY_EXISTS
    }

    /** 外部调用发生前持久化的不可变归属事实。 */
    record GenerationClaim(String generationId, String threadId, String turnId,
                           String providerId, String modelId, String configGeneration,
                           Instant claimedAt) {
        /**
         * 绑定首次成功 Turn 与冻结模型身份，不保存端点、提示正文或凭据。
         */
        public GenerationClaim {
            generationId = AutomaticTitleUsageValues.identifier(generationId, "titlegen_", "generationId");
            threadId = AutomaticTitleUsageValues.identifier(threadId, "thr_", "threadId");
            turnId = AutomaticTitleUsageValues.identifier(turnId, "turn_", "turnId");
            providerId = AutomaticTitleUsageValues.identifier(providerId, "provider_", "providerId");
            modelId = AutomaticTitleUsageValues.identifier(modelId, "model_", "modelId");
            configGeneration = AutomaticTitleUsageValues.identifier(
                    configGeneration, "cfg_", "configGeneration");
            Objects.requireNonNull(claimedAt, "claimedAt");
        }
    }

    /** 一次已发起 Provider 调用的终局用量与失败分类。 */
    record ModelOutcome(String generationId, Result result, ModelUsage usage,
                        String failureCode, Instant completedAt) {
        /**
         * 成功必须携带权威 Usage；失败允许 Provider 未报告 Usage，但必须有稳定错误分类。
         */
        public ModelOutcome {
            generationId = AutomaticTitleUsageValues.identifier(generationId, "titlegen_", "generationId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(completedAt, "completedAt");
            if (result == Result.SUCCEEDED && (usage == null || failureCode != null)) {
                throw new IllegalArgumentException("successful title generation requires usage only");
            }
            if (result == Result.FAILED) {
                failureCode = AutomaticTitleUsageValues.code(failureCode);
            }
        }
    }

    /** Provider 调用是否产出了可用且可审计的短标题。 */
    enum Result {
        /** 模型标题与 Usage 均已通过应用层校验。 */
        SUCCEEDED,
        /** 模型失败、超时、返回非法标题或没有可审计 Usage。 */
        FAILED
    }

}

/** 同文件校验器避免把审计标识规则扩张为公共端口 API。 */
final class AutomaticTitleUsageValues {
    /** 禁止实例化纯校验器。 */
    private AutomaticTitleUsageValues() {
    }

    /** 复用各事实稳定前缀，并为配置 SHA-256 代际允许 Base64URL 首字符。 */
    static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128) {
            throw new IllegalArgumentException("invalid " + field);
        }
        String suffix = value.substring(prefix.length());
        boolean valid = "cfg_".equals(prefix)
                ? suffix.matches("[A-Za-z0-9_-]+")
                : suffix.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
        if (!valid) throw new IllegalArgumentException("invalid " + field);
        return value;
    }

    /** 失败分类仅允许稳定机器词汇，避免异常正文或用户内容进入审计列。 */
    static String code(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("invalid title generation failureCode");
        }
        return value;
    }
}
