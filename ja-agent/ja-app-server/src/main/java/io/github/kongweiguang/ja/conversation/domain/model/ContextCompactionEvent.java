// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Ja RPC 可见的线程压缩事件；事实由 AgentEngine 的持久执行事件适配而来。
 */
public sealed interface ContextCompactionEvent permits ContextCompactionEvent.Started,
        ContextCompactionEvent.Compacted, ContextCompactionEvent.Failed {
    /**
     * 公开事件与 Store 投影共同采用的 Ja 压缩策略版本。
     */
    String STRATEGY_VERSION = "ja-context-v1";

    /**
     * 对 started/compacted 阶段校验唯一允许的 Token 证据组合。
     */
    private static void requirePhase(Context context, boolean beforeRequired, boolean afterRequired) {
        if (beforeRequired == (context.inputTokensBefore() == null)
            || afterRequired == (context.inputTokensAfter() == null)) {
            throw new IllegalArgumentException("invalid context compaction phase tokens");
        }
    }

    /**
     * 失败可以在第一次计量之前发生，但不能携带成功后才成立的 after 计量。
     */
    private static void requireFailurePhase(Context context) {
        if (context.inputTokensAfter() != null) {
            throw new IllegalArgumentException("invalid context compaction failure tokens");
        }
    }

    /**
     * 标识语法与 Ja 持久身份保持一致，拒绝 transport 无法编码的值。
     */
    private static String identifier(String value, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid context compaction identity");
        }
        return value;
    }

    /**
     * 返回三阶段共享的不可变关联字段。
     */
    Context context();

    /**
     * UI 公开的触发来源。
     */
    enum Trigger {
        /**
         * 普通 Turn 内依策略自动压缩上下文。
         */
        AUTOMATIC,
        /**
         * 用户显式请求压缩当前 Thread 历史。
         */
        MANUAL,
        /**
         * Provider 报告上下文溢出后恢复执行时压缩。
         */
        OVERFLOW_RECOVERY
    }

    /**
     * 仅包含稳定、脱敏的手动压缩失败类别。
     */
    enum ErrorCode {
        /**
         * Thread 身份不存在。
         */
        THREAD_NOT_FOUND,
        /**
         * Thread revision 已变化，压缩请求未应用。
         */
        CONFLICT,
        /**
         * Thread 仍有未终结 Turn。
         */
        THREAD_BUSY,
        /**
         * 摘要模型或摘要内容失败。
         */
        SUMMARY_FAILURE,
        /**
         * 压缩后仍不能满足模型上下文限制。
         */
        CONTEXT_LIMIT,
        /**
         * 调用方取消或连接关闭导致压缩停止。
         */
        CANCELLED,
        /**
         * 执行或持久事件不满足压缩协议。
         */
        INVALID_STATE
    }

    /**
     * 官方输入计量已提交、摘要尚未结束的开始事实。
     *
     * @param context 当前操作的上下文
     */
    record Started(Context context) implements ContextCompactionEvent {
        /**
         * 开始事件必须具有 before 计量且不能提前携带 after。
         */
        public Started {
            requirePhase(Objects.requireNonNull(context, "context"), true, false);
        }
    }

    /**
     * Checkpoint 同事务提交后投影的压缩结果。
     *
     * @param context 当前操作的上下文
     * @param checkpointId 检查点标识
     */
    record Compacted(Context context,
                     String checkpointId) implements ContextCompactionEvent {
        /**
         * 完成事件必须降低输入规模并带真实 Checkpoint 身份。
         */
        public Compacted {
            requirePhase(Objects.requireNonNull(context, "context"), true, true);
            if (context.inputTokensAfter() >= context.inputTokensBefore()
                || context.threadRevision() <= context.sourceRevision()) {
                throw new IllegalArgumentException("compacted context must reduce tokens and advance revision");
            }
            checkpointId = identifier(checkpointId, "checkpoint_");
        }
    }

    /**
     * Checkpoint 提交前失败的脱敏事件。
     *
     * @param context 当前操作的上下文
     * @param errorCode 错误代码
     */
    record Failed(Context context,
                  ErrorCode errorCode) implements ContextCompactionEvent {
        /**
         * 失败允许没有 before 计量，但 after 必须为空。
         */
        public Failed {
            requireFailurePhase(Objects.requireNonNull(context, "context"));
            Objects.requireNonNull(errorCode, "errorCode");
        }
    }

    /**
     * 冻结 Thread、Workspace、操作、版本、Token 与策略字段。
     *
     * @param eventId 事件标识
     * @param workspaceId 工作区稳定标识
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param threadRevision 对话修订版本
     * @param occurredAt 事件发生时间
     * @param compactionId 上下文压缩标识
     * @param trigger 触发原因
     * @param sourceRevision 来源修订版本
     * @param inputTokensBefore 处理前的输入 Token 数
     * @param inputTokensAfter 处理后的输入 Token 数
     * @param strategyVersion 策略版本
     * @param turnMutationVersion 当前执行的状态变更版本
     */
    record Context(String eventId,
                   String workspaceId,
                   String threadId,
                   String turnId,
                   long threadRevision,
                   Instant occurredAt,
                   String compactionId,
                   Trigger trigger,
                   long sourceRevision,
                   Long inputTokensBefore,
                   Long inputTokensAfter,
                   String strategyVersion,
                   Long turnMutationVersion) {
        /**
         * 手动压缩没有 turnId；自动压缩可携带 turn mutation 水位。
         */
        public Context {
            eventId = identifier(eventId, "evt_");
            workspaceId = identifier(workspaceId, "ws_");
            threadId = identifier(threadId, "thr_");
            if (turnId != null) {
                turnId = identifier(turnId, "turn_");
            }
            compactionId = identifier(compactionId, "cmp_");
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (threadRevision < 0 || sourceRevision < 0
                || inputTokensBefore != null && inputTokensBefore < 0
                || inputTokensAfter != null && inputTokensAfter < 0
                || turnMutationVersion != null && turnMutationVersion < 0
                || turnMutationVersion != null && turnId == null) {
                throw new IllegalArgumentException("invalid context compaction counters");
            }
            if (!STRATEGY_VERSION.equals(strategyVersion)) {
                throw new IllegalArgumentException("invalid context compaction strategy");
            }
        }
    }
}
