// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 为 Child Thread 暴露队列预留后的原子接纳接缝，避免 Task 先建 Thread 再启动 Turn。
 */
public interface ChildTurnScheduler {
    /**
     * 复用普通 Turn 的请求级运行时、取消、Deadline 与执行循环，但由 Task 事务提交完整 Child 事实。
     */
    TurnUseCase.Accepted startChild(TurnStartRequest request, TurnEventSink sink, Admission admission);

    /**
     * 回调只在 TurnService 已取得队列预留和取消作用域后执行；返回前必须提交全部 SQLite 事实。
     */
    @FunctionalInterface
    interface Admission {
        /**
         * Task Repository 接收与普通 Turn 相同的稳定 Operation 输入，并返回统一版本回执；具体出站
         * Repository DTO 由实现层映射，不能反向泄漏到入站合同。
         */
        AdmissionReceipt admit(AdmissionRequest admission);
    }

    /** Child 准入请求只携带已冻结的领域事实，不暴露任何持久化端口类型。 */
    record AdmissionRequest(String threadId, String turnId, String messageId,
                            ModelMessage userMessage, List<String> attachmentIds,
                            long expectedThreadRevision, Instant requestedAt,
                            TurnExecutionState initialExecution) {
        /** 列表在跨越调度边界前冻结，并拒绝非 USER 消息与非法 revision。 */
        public AdmissionRequest {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(userMessage, "userMessage");
            attachmentIds = List.copyOf(Objects.requireNonNull(attachmentIds, "attachmentIds"));
            if (userMessage.role() != ModelRole.USER || expectedThreadRevision < 0) {
                throw new IllegalArgumentException("child admission requires a USER message and valid revision");
            }
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(initialExecution, "initialExecution");
        }
    }

    /** Child 准入回执只公开调度继续运行所需的身份与两套 CAS 版本。 */
    record AdmissionReceipt(String threadId, String turnId, long threadRevision,
                            long turnMutationVersion, String provisionalTitle) {
        /** 非负版本由 SQLite 事务产生；可选标题只表示本次是否取得首次标题所有权。 */
        public AdmissionReceipt {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            if (threadRevision < 0 || turnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid child admission receipt revision");
            }
        }

        /** null 是未取得标题所有权的唯一表达，调用方不得在成功后补做标题生成。 */
        public boolean createdProvisionalTitle() {
            return provisionalTitle != null;
        }
    }

    /**
     * 在组合边界拒绝缺失回调，避免把持久化延迟到队列提交之后。
     */
    static Admission required(Admission admission) {
        return Objects.requireNonNull(admission, "admission");
    }
}
