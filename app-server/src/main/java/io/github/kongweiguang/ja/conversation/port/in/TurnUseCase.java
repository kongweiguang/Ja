// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 定义 Turn 接纳、取消和有界关闭用例，使入站 adapter 不依赖具体服务类。
 */
public interface TurnUseCase extends DeadlineCloseable {
    /**
     * 接纳已经完成配置与工具冻结的 Turn 请求。
     */
    Accepted start(TurnStartRequest request, TurnEventSink sink);

    /**
     * 通过预期 Thread revision 请求取消一个全局唯一 Turn。
     */
    CancelResult cancel(String turnId, long expectedThreadRevision);

    /** 将一条 steering 追加到活动 Turn 的持久 FIFO。 */
    default QueuedInput steer(String turnId, String text) {
        throw new UnsupportedOperationException("steering is unavailable");
    }

    /** 将一条 follow-up 追加到活动 Turn 的持久 FIFO。 */
    default QueuedInput followUp(String turnId, String text) {
        throw new UnsupportedOperationException("follow-up is unavailable");
    }

    /**
     * 在组合关闭前停止新 Turn 接纳。
     */
    void stopAccepting();

    /**
     * 在给定预算内等待所有已经接纳的 Turn 退出。
     */
    boolean awaitQuiescence(Duration timeout);

    /**
     * 接纳回执包含持久化 revision，并允许调用方在完成时释放配置代际。
     */
    record Accepted(String threadId, String turnId, long threadRevision, boolean queued,
                    CompletionStage<TurnResult> completion) {
        /**
         * 拒绝未持久化的负 revision 和缺失的完成阶段。
         */
        public Accepted {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            if (threadRevision < 0 || completion == null) throw new IllegalArgumentException("invalid acceptance");
        }
    }

    /**
     * 取消回执表达提交时观察到的状态，不承诺异步清理已经结束。
     */
    record CancelResult(boolean accepted, String turnId, TurnState status, long threadRevision) {
        /**
         * 保证取消关联和值域在跨 adapter 返回前完整。
         */
        public CancelResult {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(status, "status");
            if (threadRevision < 0) throw new IllegalArgumentException("invalid cancel result");
        }
    }

    /** 排队回执只公开稳定输入身份和类型，不承诺立即消费。 */
    record QueuedInput(String inputId, String turnId, String kind) {
        /** 拒绝缺失关联，RPC 可据此立即显示简单排队状态。 */
        public QueuedInput {
            Objects.requireNonNull(inputId, "inputId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * 取消查找失败只保留入站错误映射需要的稳定类别。
     */
    enum CancelFailure {
        /**
         * 请求引用的 Turn 不存在或已不可取消。
         */
        TURN_NOT_FOUND,
        /**
         * 调用方提供的 Thread revision 已过期。
         */
        CONFLICT
    }

    /**
     * 无堆栈应用异常防止内部存储与并发细节越过 transport。
     */
    final class TurnCancellationException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final CancelFailure failure;

        /**
         * 只保存稳定失败类别，禁止携带查找键或持久化消息。
         */
        private TurnCancellationException(CancelFailure failure) {
            super("turn cancellation failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /**
         * 由应用实现创建分类异常，入站 adapter 不能构造任意内部失败。
         */
        public static TurnCancellationException of(CancelFailure failure) {
            return new TurnCancellationException(failure);
        }

        /**
         * 返回用于稳定错误目录映射的取消失败类别。
         */
        public CancelFailure failure() {
            return failure;
        }
    }
}
