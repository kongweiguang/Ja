// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import java.time.Instant;
import java.util.Objects;

/** 自动、溢出恢复与手动压缩共享的 Thread 级生命周期事实。 */
public sealed interface ContextCompactionEvent permits ContextCompactionEvent.Started,
        ContextCompactionEvent.Compacted, ContextCompactionEvent.Failed {
    /** 首版策略标识由事件不变量唯一拥有，生产者与消费者不得各自维护版本常量。 */
    String STRATEGY_VERSION = "ja-context-v1";

    /** 返回三类通知共享且不可变的关联字段。 */
    Context context();

    /** 压缩开始事件在第一次摘要或 Checkpoint 副作用之前发布。 */
    record Started(Context context) implements ContextCompactionEvent {
        /** started 必须已有官方 before 计量，但尚不存在 after。 */
        public Started {
            requirePhase(Objects.requireNonNull(context, "context"), true, false);
        }
    }

    /** Checkpoint CAS 成功且可回读后发布的完成事实。 */
    record Compacted(Context context, String checkpointId) implements ContextCompactionEvent {
        /** 完成事件必须同时提供 before/after 与真实 Checkpoint 身份。 */
        public Compacted {
            requirePhase(Objects.requireNonNull(context, "context"), true, true);
            if (context.inputTokensAfter() >= context.inputTokensBefore()
                || context.threadRevision() <= context.sourceRevision()) {
                throw new IllegalArgumentException("compacted context must reduce tokens and advance revision");
            }
            checkpointId = identifier(checkpointId, "checkpoint_");
        }
    }

    /** 压缩在提交 Checkpoint 前关闭失败时发布的稳定脱敏事实。 */
    record Failed(Context context, ErrorCode errorCode) implements ContextCompactionEvent {
        /** 失败允许发生在首次计量前，因此 before 可空；after 必须为空。 */
        public Failed {
            requireFailurePhase(Objects.requireNonNull(context, "context"));
            Objects.requireNonNull(errorCode, "errorCode");
        }
    }

    /** 冻结 Thread、Workspace、触发原因、revision、Token 与策略版本。 */
    record Context(String eventId, String workspaceId, String threadId, String turnId,
                   long threadRevision, Instant occurredAt, String compactionId, Trigger trigger,
                   long sourceRevision, Long inputTokensBefore, Long inputTokensAfter,
                   String strategyVersion) {
        /** nullable turnId 只表达手动 Thread 操作，其余身份与安全整数必须完整。 */
        public Context {
            eventId = identifier(eventId, "evt_");
            workspaceId = identifier(workspaceId, "ws_");
            threadId = identifier(threadId, "thr_");
            if (turnId != null) turnId = identifier(turnId, "turn_");
            compactionId = identifier(compactionId, "cmp_");
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (threadRevision < 0 || sourceRevision < 0
                || inputTokensBefore != null && inputTokensBefore < 0
                || inputTokensAfter != null && inputTokensAfter < 0) {
                throw new IllegalArgumentException("invalid context compaction counters");
            }
            if (!STRATEGY_VERSION.equals(strategyVersion)) {
                throw new IllegalArgumentException("invalid context compaction strategy");
            }
        }
    }

    /** 公开触发原因不暴露内部重试阶段或 Provider 类型。 */
    enum Trigger {
        /** 上下文预算在普通 Turn 内触发压缩。 */
        AUTOMATIC,
        /** 用户对空闲 Thread 显式请求压缩。 */
        MANUAL,
        /** Provider 报告超限后进入一次受控恢复。 */
        OVERFLOW_RECOVERY
    }

    /** 失败通知只暴露手动压缩合同定义的稳定闭集。 */
    enum ErrorCode {
        /** 目标 Thread 不存在。 */
        THREAD_NOT_FOUND,
        /** Thread revision 已变化。 */
        CONFLICT,
        /** Thread 仍有活动 Turn。 */
        THREAD_BUSY,
        /** 摘要模型未产生可提交结果。 */
        SUMMARY_FAILURE,
        /** 压缩后仍无法满足上下文预算。 */
        CONTEXT_LIMIT,
        /** 连接关闭或宿主取消了仍在执行的压缩。 */
        CANCELLED,
        /** 持久状态不满足压缩前置条件。 */
        INVALID_STATE
    }

    /** 校验各生命周期阶段允许出现的 Token 证据组合。 */
    private static void requirePhase(Context context, boolean beforeRequired, boolean afterRequired) {
        if (beforeRequired != (context.inputTokensBefore() != null)
            || afterRequired != (context.inputTokensAfter() != null)) {
            throw new IllegalArgumentException("invalid context compaction phase tokens");
        }
    }

    /**
     * 失败既可能发生在首次计量前，也可能发生在 started 之后；因此保留可选 before 证据，
     * 但禁止只有 Checkpoint 提交成功后才成立的 after 计量越过失败终态。
     */
    private static void requireFailurePhase(Context context) {
        if (context.inputTokensAfter() != null) {
            throw new IllegalArgumentException("invalid context compaction failure tokens");
        }
    }

    /** 使用既有 opaque identity 语法，避免事件模型接受 transport 无法编码的身份。 */
    private static String identifier(String value, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid context compaction identity");
        }
        return value;
    }
}
