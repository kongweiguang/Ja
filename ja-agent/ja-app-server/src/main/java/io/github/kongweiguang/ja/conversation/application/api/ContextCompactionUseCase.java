// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEventSink;

import java.util.Objects;

/**
 * Ja 手动压缩入口；具体执行必须通过唯一 AgentEngine 的准入与持久事件。
 */
public interface ContextCompactionUseCase {
    /**
     * 使用调用方观察到的 Thread revision 接纳一次手动压缩。
     */
    Result compact(Command command, ContextCompactionEventSink events, CancellationToken cancellation);

    /**
     * 手动压缩的成功闭集。
     */
    enum Outcome {
        /**
         * 已提交真实 Checkpoint 并降低当前上下文 Token 数。
         */
        COMPACTED,
        /**
         * 当前历史无可压缩增量，保持既有上下文与修订。
         */
        UNCHANGED
    }

    /**
     * 手动压缩公开失败闭集。
     */
    enum Code {
        /**
         * Thread 身份不存在。
         */
        THREAD_NOT_FOUND,
        /**
         * 观察到的 Thread 修订已过期。
         */
        CONFLICT,
        /**
         * Thread 尚有活动 Turn。
         */
        THREAD_BUSY,
        /**
         * 摘要模型或摘要内容失败。
         */
        SUMMARY_FAILURE,
        /**
         * 压缩后上下文仍超出模型限制。
         */
        CONTEXT_LIMIT,
        /**
         * 调用方取消了本次压缩。
         */
        CANCELLED,
        /**
         * Engine 的持久事实不满足压缩协议。
         */
        INVALID_STATE
    }

    /**
     * 冻结目标 Thread 与 CAS revision，不允许 transport 隐式读取最新修订。
     *
     * @param threadId 所属对话的稳定标识
     * @param expectedThreadRevision 预期对话修订版本
     */
    record Command(String threadId,
                   long expectedThreadRevision) {
        /**
         * 限制身份与 revision，缺失 Thread 的业务语义由服务端返回。
         */
        public Command {
            if (threadId == null || !threadId.matches("thr_[A-Za-z0-9._-]{1,124}")
                || expectedThreadRevision < 0) {
                throw new IllegalArgumentException("invalid context compaction command");
            }
        }
    }

    /**
     * 返回真实压缩或无变化结果；Token 仅表示输入估算，不冒充 Provider Usage。
     *
     * @param outcome 执行结果
     * @param compactionId 上下文压缩标识
     * @param checkpointId 检查点标识
     * @param threadRevision 对话修订版本
     * @param inputTokensBefore 处理前的输入 Token 数
     * @param inputTokensAfter 处理后的输入 Token 数
     */
    record Result(Outcome outcome,
                  String compactionId,
                  String checkpointId,
                  long threadRevision,
                  long inputTokensBefore,
                  long inputTokensAfter) {
        /**
         * 成功结果的 Checkpoint 身份必须与压缩 outcome 一致。
         */
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            if (threadRevision < 0 || inputTokensBefore < 0 || inputTokensAfter < 0
                || outcome == Outcome.COMPACTED && (compactionId == null || checkpointId == null)
                || outcome == Outcome.UNCHANGED && (compactionId != null || checkpointId != null)) {
                throw new IllegalArgumentException("invalid context compaction result");
            }
        }
    }

    /**
     * 应用与 transport 之间稳定、脱敏的失败类型。
     */
    final class Failure extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final Code code;

        /**
         * 保留稳定错误码；底层 cause 只供进程内诊断，RPC 只能读取 code。
         */
        public Failure(Code code) {
            this(code, null);
        }

        /**
         * 诊断保留原始故障链，但禁用本异常堆栈，避免分类异常伪装为故障现场。
         */
        public Failure(Code code, Throwable cause) {
            super("context compaction failed", cause, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * 返回可安全序列化的错误类别。
         */
        public Code code() {
            return code;
        }
    }
}
