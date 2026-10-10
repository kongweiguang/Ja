// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Objects;
import java.util.Optional;

/**
 * 手动压缩同次持久提交的结果，供宿主回放原回执而不查询“最新”检查点。
 *
 * @param outcome 执行结果
 * @param compactionId 上下文压缩标识
 * @param checkpointId 检查点标识（可选）
 * @param sourceRevision 来源修订版本
 * @param threadRevision 对话修订版本
 * @param inputTokensBefore 处理前的输入 Token 数
 * @param inputTokensAfter 处理后的输入 Token 数
 */
public record AgentCompactionReceipt(Outcome outcome,
                                     String compactionId,
                                     Optional<String> checkpointId,
                                     long sourceRevision,
                                     long threadRevision,
                                     long inputTokensBefore,
                                     long inputTokensAfter) {
    /**
     * 只允许检查点身份与实际压缩结果匹配，并保留 commit 返回的精确会话修订。
     */
    public AgentCompactionReceipt {
        Objects.requireNonNull(outcome, "outcome");
        compactionId = ContractChecks.identifier(compactionId, "compactionId");
        checkpointId = Objects.requireNonNull(checkpointId, "checkpointId");
        checkpointId.ifPresent(value -> ContractChecks.identifier(value, "checkpointId"));
        if (sourceRevision < 0 || threadRevision < 0 || inputTokensBefore < 0 || inputTokensAfter < 0) {
            throw new IllegalArgumentException("invalid compaction receipt watermarks");
        }
        if ((outcome == Outcome.COMPACTED) != checkpointId.isPresent()) {
            throw new IllegalArgumentException("checkpoint identity must match compaction outcome");
        }
        if (outcome == Outcome.COMPACTED && inputTokensAfter >= inputTokensBefore) {
            throw new IllegalArgumentException("compacted context must reduce input tokens");
        }
    }

    /**
     * 两种明确终态；UNCHANGED 不伪造 checkpoint 身份。
     */
    public enum Outcome {
        /**
         * 摘要检查点已在同次 Store 提交中写入，且上下文输入 token 数实际下降。
         */
        COMPACTED,
        /**
         * 当前历史无需改写，因此没有检查点身份但仍返回真实前后计量。
         */
        UNCHANGED
    }
}
