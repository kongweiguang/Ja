// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;

import java.util.concurrent.CompletionStage;

/**
 * Ja 审批入口保留客户端幂等身份，Core 仅接收通用等待答案。
 */
@FunctionalInterface
public interface ApprovalUseCase {
    /**
     * 以持久等待为权威完成审批，断线或重启不要求原连接仍持有内存门闩。
     */
    CompletionStage<Resolution> resolve(String threadId, String turnId, String approvalId,
                                        long expectedThreadRevision, ApprovalDecision decision, String operationKey,
                                        String requestFingerprint, ConversationTurnEventSink sink);

    /**
     * 业务冲突与系统故障分开，RPC 才能提示刷新审批而非盲目重复提交。
     */
    enum Failure {
        /**
         * 等待或审批标识不存在，客户端应刷新审批列表。
         */
        NOT_FOUND,
        /**
         * 等待已超过策略期限，原回答不能再授权执行。
         */
        EXPIRED,
        /**
         * 持久等待无法读取或损坏，客户端不得把存储故障当作用户裁决。
         */
        UNAVAILABLE,
        /**
         * 已有确定裁决，新的操作必须读取原回执而不是覆盖答案。
         */
        ALREADY_RESOLVED,
        /**
         * 请求基于过期修订，客户端应刷新后再表达当前意图。
         */
        CONFLICT
    }

    /**
     * 回执版本取自审批提交，不能用恢复执行后继续变化的实时版本代替。
     *
     * @param threadRevision 对话修订版本
     */
    record Resolution(long threadRevision) {
        /**
         * 拒绝无法参与客户端 CAS 的伪造版本。
         */
        public Resolution {
            if (threadRevision < 0) {
                throw new IllegalArgumentException("invalid thread revision");
            }
        }
    }

    /**
     * 只携带封闭错误分类，不把数据库、审批正文或执行环境交给传输层。
     */
    final class ApprovalException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final Failure failure;

        /**
         * 封闭类型让传输映射不依赖异常消息文本。
         */
        public ApprovalException(Failure failure) {
            super(java.util.Objects.requireNonNull(failure, "failure").name());
            this.failure = failure;
        }

        /**
         * 保留本地诊断根因，同时只把稳定分类映射到 RPC。
         */
        public ApprovalException(Failure failure, Throwable cause) {
            super(java.util.Objects.requireNonNull(failure, "failure").name(), cause);
            this.failure = failure;
        }

        /**
         * 返回可映射的业务分类，不暴露底层实现。
         */
        public Failure failure() {
            return failure;
        }
    }
}
