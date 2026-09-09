// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEventSink;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** 把压缩状态机事实统一转换为 Thread 级事件，供自动与手动入口复用。 */
public final class ContextCompactionLifecycle {
    private final String workspaceId;
    private final String threadId;
    private final String turnId;
    private final long sourceRevision;
    private final String compactionId;
    private final ContextCompactionEventSink sink;
    private final Clock clock;
    private Long before;
    private ContextCompactionEvent.Trigger activeTrigger;
    private boolean terminated;

    /** 固定一次压缩操作身份；同一操作的 started/terminal 事件不得跨 Thread 或 revision。 */
    public ContextCompactionLifecycle(String workspaceId, String threadId, String turnId,
                                      long sourceRevision, String compactionId,
                                      ContextCompactionEventSink sink, Clock clock) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.threadId = Objects.requireNonNull(threadId, "threadId");
        this.turnId = turnId;
        this.sourceRevision = sourceRevision;
        this.compactionId = Objects.requireNonNull(compactionId, "compactionId");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 在已确认确需压缩且取得官方计量后发布 started；同一 attempt 只允许开始一次。 */
    public void started(ContextCompactionEvent.Trigger trigger, long inputTokensBefore) {
        if (activeTrigger != null || terminated) throw new IllegalStateException("context compaction attempt already started");
        activeTrigger = Objects.requireNonNull(trigger, "trigger");
        before = inputTokensBefore;
        publish(new ContextCompactionEvent.Started(context(sourceRevision, trigger, before, null)));
    }

    /** Checkpoint 已提交并可回读后发布完成事实，随后清空 attempt 状态允许 overflow recovery。 */
    public void compacted(CheckpointStore.CommittedCheckpoint receipt, long inputTokensAfter) {
        Objects.requireNonNull(receipt, "receipt");
        ContextCompactionEvent.Trigger trigger = requireActive();
        publish(new ContextCompactionEvent.Compacted(
                context(receipt.threadRevision(), trigger, before, inputTokensAfter),
                receipt.checkpoint().checkpointId()));
        terminated = true;
        clearAttempt();
    }

    /** 在尚未提交 Checkpoint 的关闭失败上发布稳定错误；计量前失败由 failBeforeStart 表达。 */
    public void failed(ContextException.Code code) {
        ContextCompactionEvent.Trigger trigger = requireActive();
        publish(new ContextCompactionEvent.Failed(context(sourceRevision, trigger, before, null), wire(code)));
        terminated = true;
        clearAttempt();
    }

    /** 首次官方计量失败时仍发布可关联失败事件，但 Token before 合法为空。 */
    public void failBeforeStart(ContextCompactionEvent.Trigger trigger, ContextException.Code code) {
        if (activeTrigger != null || terminated) throw new IllegalStateException("context compaction attempt already started");
        publish(new ContextCompactionEvent.Failed(context(sourceRevision, trigger, null, null), wire(code)));
        terminated = true;
    }

    /** 取消使用公开稳定类别，并根据是否已 started 保留已有 before Token 证据。 */
    public void cancelled(ContextCompactionEvent.Trigger trigger) {
        if (terminated) return;
        ContextCompactionEvent.Trigger effective = activeTrigger == null ? trigger : requireActive();
        publish(new ContextCompactionEvent.Failed(context(sourceRevision, effective, before, null),
                ContextCompactionEvent.ErrorCode.CANCELLED));
        terminated = true;
        clearAttempt();
    }

    /** 返回当前 attempt 是否已经进入 started，供恢复状态机避免为普通 Provider 失败误报。 */
    public boolean active() {
        return activeTrigger != null;
    }

    /** 返回操作是否已有 compacted/failed 终态，外层资源失败不得重复发布。 */
    public boolean terminated() {
        return terminated;
    }

    /** 返回 started 记录的官方 before 计量，用于手动 RPC 结果复用同一证据。 */
    public long inputTokensBefore() {
        if (before == null) throw new IllegalStateException("context compaction did not start");
        return before;
    }

    /** 为每条通知分配事件身份，但复用同一操作与源 revision。 */
    private ContextCompactionEvent.Context context(long threadRevision, ContextCompactionEvent.Trigger trigger,
                                                   Long inputTokensBefore, Long inputTokensAfter) {
        return new ContextCompactionEvent.Context("evt_" + UUID.randomUUID(), workspaceId, threadId, turnId,
                threadRevision, clock.instant(), compactionId, trigger, sourceRevision,
                inputTokensBefore, inputTokensAfter, ContextCompactionEvent.STRATEGY_VERSION);
    }

    /** 同步等待事件入队，保证 started 先于摘要副作用、compacted 先于后续 Provider send。 */
    private void publish(ContextCompactionEvent event) {
        try {
            sink.publish(event).toCompletableFuture().join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    /** 将内部错误收敛为合同允许的稳定机器码。 */
    private static ContextCompactionEvent.ErrorCode wire(ContextException.Code code) {
        return switch (Objects.requireNonNull(code, "code")) {
            case CAS_CONFLICT -> ContextCompactionEvent.ErrorCode.CONFLICT;
            case SUMMARY_FAILURE -> ContextCompactionEvent.ErrorCode.SUMMARY_FAILURE;
            case CONTEXT_LIMIT -> ContextCompactionEvent.ErrorCode.CONTEXT_LIMIT;
            case INVALID_STATE -> ContextCompactionEvent.ErrorCode.INVALID_STATE;
        };
    }

    /** 要求失败或完成严格配对到当前 started。 */
    private ContextCompactionEvent.Trigger requireActive() {
        if (activeTrigger == null) throw new IllegalStateException("context compaction attempt is not active");
        return activeTrigger;
    }

    /** 一个 attempt 只有一个终态，overflow recovery 会以新 trigger 重新开始。 */
    private void clearAttempt() {
        activeTrigger = null;
    }
}
