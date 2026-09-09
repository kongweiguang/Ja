// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.Objects;

/** 空闲 Thread 的显式上下文压缩入口；实现只生成 Checkpoint，不执行普通模型发送。 */
public interface ContextCompactionUseCase {
    /** 基于调用方观察到的 Thread revision 执行一次幂等手动压缩。 */
    Result compact(Command command, ContextCompactionEventSink events, CancellationToken cancellation);

    /** 冻结目标 Thread 与 CAS revision，拒绝 transport 层隐式读取最新值。 */
    record Command(String threadId, long expectedThreadRevision) {
        /** 限制身份与 revision，具体不存在语义由应用服务返回。 */
        public Command {
            if (threadId == null || !threadId.matches("thr_[A-Za-z0-9._-]{1,124}")
                || expectedThreadRevision < 0) {
                throw new IllegalArgumentException("invalid context compaction command");
            }
        }
    }

    /** 返回压缩或无变化结果；Token 均来自 Provider 官方计量。 */
    record Result(Outcome outcome, String compactionId, String checkpointId,
                  long threadRevision, long inputTokensBefore, long inputTokensAfter) {
        /** changed 必须带完整身份，unchanged 不伪造 Checkpoint。 */
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            if (threadRevision < 0 || inputTokensBefore < 0 || inputTokensAfter < 0
                || outcome == Outcome.COMPACTED && (compactionId == null || checkpointId == null)
                || outcome == Outcome.UNCHANGED && (compactionId != null || checkpointId != null)) {
                throw new IllegalArgumentException("invalid context compaction result");
            }
        }
    }

    /** 手动入口只有真正提交和无新事实两个成功结果。 */
    enum Outcome {
        /** 新 Checkpoint 已通过 Thread revision CAS 提交。 */
        COMPACTED,
        /** 最新 Checkpoint 已覆盖全部永久消息，没有新事实需要摘要。 */
        UNCHANGED
    }

    /** 暴露给 transport 的稳定失败闭集，消息不包含 Prompt、路径或 Provider 正文。 */
    final class Failure extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final Code code;

        /** 固定稳定类别并关闭本地堆栈，根因只留给服务内部日志边界。 */
        public Failure(Code code) {
            super("context compaction failed", null, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** 返回可安全映射到 JA-RPC 的失败类别。 */
        public Code code() {
            return code;
        }
    }

    /** 手动压缩的公开错误类别。 */
    enum Code {
        /** 目标 Thread 不存在。 */
        THREAD_NOT_FOUND,
        /** 调用方 revision 已过期。 */
        CONFLICT,
        /** Thread 仍有活动 Turn。 */
        THREAD_BUSY,
        /** 摘要模型未产生可提交结果。 */
        SUMMARY_FAILURE,
        /** 压缩后仍无法满足上下文预算。 */
        CONTEXT_LIMIT,
        /** 连接关闭或宿主取消了仍在执行的手动压缩。 */
        CANCELLED,
        /** 持久状态不满足压缩前置条件。 */
        INVALID_STATE
    }
}
