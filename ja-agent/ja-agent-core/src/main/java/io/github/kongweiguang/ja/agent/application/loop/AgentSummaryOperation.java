// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.application.context.summary.ModelSummaryGenerator;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 将自动摘要的模型意图、用量和滚动进度纳入同一可恢复执行游标。
 */
final class AgentSummaryOperation implements ModelSummaryGenerator.SummaryOperation {
    private final Host host;

    /**
     * 仅依赖当前执行宿主和无状态编解码器，避免内存字段成为恢复事实来源。
     */
    AgentSummaryOperation(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * 只接受由执行状态机保存的 SUMMARY Provider pending，拒绝错误用途与游标组合。
     */
    private static TurnExecutionState.ProviderPending pending(TurnExecutionState state) {
        if (!(state instanceof TurnExecutionState.ProviderPending pending)
            || pending.purpose() != TurnExecutionState.ProviderPurpose.SUMMARY
            || pending.resume().next() != TurnExecutionState.Next.SUMMARY
            || pending.resume().summary() == null) {
            throw invalidState("summary settlement requires a persisted SUMMARY provider intent");
        }
        return pending;
    }

    /**
     * 读取摘要步骤的唯一 Ready 游标，不从消息历史或配置推断当前阶段。
     */
    private static TurnExecutionState.Ready ready(TurnExecutionState state) {
        if (!(state instanceof TurnExecutionState.Ready ready)) {
            throw invalidState("summary operation requires a READY execution cursor");
        }
        return ready;
    }

    /**
     * 为尚未派发的模型请求生成独立审计身份；调用身份在 Provider intent 提交后保持不变。
     */
    private static String modelRequestId() {
        return "modelreq_summary_" + UUID.randomUUID();
    }

    /**
     * 为摘要请求的非对话消息槽生成中立身份，避免伪造用户可见助手消息。
     */
    private static String requestItemId() {
        return "summary-item-" + UUID.randomUUID();
    }

    /**
     * 将持久游标错误统一归类，调用方不需要解析异常文本判断恢复资格。
     */
    private static ContextException invalidState(String message) {
        return new ContextException(ContextException.Code.INVALID_STATE, message);
    }

    /**
     * 以 READY(ASSISTANT/COMPACTION) 建立摘要计划，或校验 READY(SUMMARY) 恢复时的冻结指纹。
     */
    @Override
    public Progress start(String planFingerprint, SummaryDocument initial) {
        Objects.requireNonNull(initial, "initial");
        TurnExecutionState.Ready ready = ready(host.cursor());
        if (ready.next() == TurnExecutionState.Next.ASSISTANT
            || ready.next() == TurnExecutionState.Next.COMPACTION) {
            if (ready.summary() != null) {
                throw invalidState("initial cursor unexpectedly contains summary progress");
            }
            Progress initialProgress = Progress.candidate(
                    initial, CheckpointUsage.none(), 0, 0, planFingerprint);
            TurnExecutionState.Ready next = new TurnExecutionState.Ready(
                    ready.common(), TurnExecutionState.Next.SUMMARY, persist(initialProgress));
            host.commit(TurnState.RUNNING, next, List.of(), List.of());
            return initialProgress;
        }
        if (ready.next() != TurnExecutionState.Next.SUMMARY || ready.summary() == null) {
            throw invalidState("summary operation requires READY(ASSISTANT), READY(COMPACTION), or READY(SUMMARY)");
        }
        if (!ready.summary().planFingerprint().equals(planFingerprint)) {
            throw new ContextException(ContextException.Code.SUMMARY_FAILURE,
                    "persisted Summary plan no longer matches the frozen context");
        }
        return restore(ready.summary());
    }

    /**
     * 在模型派发前验证租约并持久化 SUMMARY 意图，恢复可识别请求是否曾完成。
     */
    @Override
    public void begin(String promptFingerprint, Optional<ExecutionFacts.ModelRequestIdentity> profile) {
        TurnExecutionState.Ready ready = ready(host.cursor());
        if (ready.next() != TurnExecutionState.Next.SUMMARY || ready.summary() == null) {
            throw invalidState("summary Provider requires READY(SUMMARY)");
        }
        ExecutionFacts.ModelRequestIdentity model = Objects.requireNonNull(profile, "profile")
                .orElseThrow(() -> invalidState("summary model identity is unavailable"));
        host.assertDispatchAuthority();
        TurnExecutionState.ProviderPending pending = new TurnExecutionState.ProviderPending(
                ready.common(), modelRequestId(), requestItemId(), TurnExecutionState.ProviderPurpose.SUMMARY,
                model, promptFingerprint, ready);
        host.commit(TurnState.RUNNING, pending, List.of(), List.of());
    }

    /**
     * 将已返回的摘要、KNOWN 用量和推进后的 Provider ordinal 原子提交。
     */
    @Override
    public void settle(CheckpointUsage callUsage, Progress accepted) {
        TurnExecutionState.ProviderPending pending = pending(host.cursor());
        Progress progress = Objects.requireNonNull(accepted, "accepted");
        TurnExecutionState.SummaryProgress previous = pending.resume().summary();
        if (previous == null || !previous.planFingerprint().equals(progress.planFingerprint())) {
            throw invalidState("summary settlement changed its frozen plan");
        }
        TurnExecutionState.Ready next = new TurnExecutionState.Ready(
                pending.resume().advanceProviderOrdinal().common(), TurnExecutionState.Next.SUMMARY,
                persist(progress));
        Instant at = host.services().clock().instant();
        AgentStore.RecordWrite usage = usage(pending, Objects.requireNonNull(callUsage, "callUsage"), at);
        host.commit(TurnState.RUNNING, next, List.of(usage), List.of());
    }

    /**
     * 将可能已计费但无可信响应的调用记为 UNKNOWN，并恢复原摘要阶段后推进 ordinal。
     */
    @Override
    public void retry() {
        TurnExecutionState.ProviderPending pending = pending(host.cursor());
        TurnExecutionState.Ready next = pending.resume().advanceProviderOrdinal();
        Instant at = host.services().clock().instant();
        AgentStore.RecordWrite unknown = usage(pending, null, at);
        host.commit(TurnState.RUNNING, next, List.of(unknown), List.of());
    }

    /**
     * 使用最近一次执行提交确认的会话版本，避免 checkpoint 引用旧快照。
     */
    @Override
    public long checkpointSourceRevision(long originalRevision) {
        return host.restored().conversation().revision();
    }

    /**
     * 将自动摘要或手动压缩完成游标与 checkpoint 绑定到同一执行身份和 mutation CAS。
     */
    @Override
    public Optional<CheckpointStore.TurnOperation> checkpointTurnOperation() {
        TurnExecutionState.Ready current = ready(host.cursor());
        if (current.next() == TurnExecutionState.Next.ASSISTANT) {
            if (current.summary() != null) {
                throw invalidState("READY(ASSISTANT) cannot retain summary progress");
            }
            return Optional.of(new CheckpointStore.TurnOperation(host.identity().turnId(),
                    host.restored().execution().mutationVersion(), current));
        }
        if (current.next() == TurnExecutionState.Next.COMPACTION) {
            if (current.summary() != null) {
                throw invalidState("READY(COMPACTION) cannot retain summary progress");
            }
            TurnExecutionState.Ready completed = new TurnExecutionState.Ready(
                    current.common(), TurnExecutionState.Next.ASSISTANT, null);
            return Optional.of(new CheckpointStore.TurnOperation(host.identity().turnId(),
                    host.restored().execution().mutationVersion(), completed));
        }
        if (current.next() != TurnExecutionState.Next.SUMMARY) {
            return Optional.empty();
        }
        if (current.summary() == null || current.summary().stage() != TurnExecutionState.SummaryStage.CANDIDATE) {
            throw invalidState("only a fully accepted summary can complete the execution cursor");
        }
        TurnExecutionState.Ready completed = new TurnExecutionState.Ready(
                current.common(), TurnExecutionState.Next.ASSISTANT, null);
        return Optional.of(new CheckpointStore.TurnOperation(host.identity().turnId(),
                host.restored().execution().mutationVersion(), completed));
    }

    /**
     * 将应用层摘要进度编码为执行游标值，并显式拒绝已移除的回退阶段。
     */
    private TurnExecutionState.SummaryProgress persist(Progress value) {
        CheckpointUsage usage = value.usage();
        TurnExecutionState.SummaryStage stage = switch (value.stage().name()) {
            case "CANDIDATE" -> TurnExecutionState.SummaryStage.CANDIDATE;
            case "REPAIR" -> TurnExecutionState.SummaryStage.REPAIR;
            case "FAILED" -> TurnExecutionState.SummaryStage.FAILED;
            default -> throw new ContextException(ContextException.Code.SUMMARY_FAILURE,
                    "unsupported automatic summary stage");
        };
        return new TurnExecutionState.SummaryProgress(host.services().records().encodeSummaryDocument(value.document()),
                value.throughOrdinal(),
                value.nextTurn(), value.planFingerprint(), stage, value.targetNextTurn(),
                value.targetThroughOrdinal(), value.violations(), value.promptFingerprint(),
                new TurnExecutionState.KnownUsage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                        usage.cacheReadTokens(), usage.cacheWriteTokens()));
    }

    /**
     * 从持久化 JSON 重建不可变摘要进度，未知或损坏阶段都以摘要失败关闭。
     */
    private Progress restore(TurnExecutionState.SummaryProgress value) {
        TurnExecutionState.KnownUsage usage = value.usage();
        try {
            ModelSummaryGenerator.SummaryOperation.Stage stage =
                    ModelSummaryGenerator.SummaryOperation.Stage.valueOf(value.stage().name());
            return new Progress(host.services().records().decodeSummaryDocument(value.summaryJson()), new CheckpointUsage(
                    usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                    usage.cacheReadTokens(), usage.cacheWriteTokens()), value.nextChunk(),
                    value.throughOrdinal(), value.planFingerprint(), stage, value.targetNextChunk(),
                    value.targetThroughOrdinal(), value.violations(), value.promptFingerprint());
        } catch (IllegalArgumentException failure) {
            throw new ContextException(ContextException.Code.SUMMARY_FAILURE,
                    "persisted automatic summary stage is unsupported", failure);
        }
    }

    /**
     * 将模型调用事实编码为同事务持久记录，未知用量不会污染累计的已知摘要用量。
     */
    private AgentStore.RecordWrite usage(TurnExecutionState.ProviderPending pending,
                                         CheckpointUsage checkpointUsage, Instant at) {
        ModelUsage modelUsage = checkpointUsage == null ? null : new ModelUsage(
                checkpointUsage.inputTokens(), checkpointUsage.outputTokens(), checkpointUsage.totalTokens(),
                checkpointUsage.cacheReadTokens(), checkpointUsage.cacheWriteTokens(),
                ModelUsage.InputAccounting.UNKNOWN);
        ExecutionFacts.Usage fact = new ExecutionFacts.Usage(pending.modelRequestId(), modelUsage,
                Math.max(1, Math.addExact(pending.common().modelRound(), 1)),
                pending.common().nextProviderOrdinal(), ExecutionFacts.UsagePurpose.SUMMARY,
                modelUsage == null ? ExecutionFacts.UsageCertainty.UNKNOWN : ExecutionFacts.UsageCertainty.KNOWN,
                pending.profile(), null);
        String recordId = AgentRecordIds.derive(executionKey(), "agent.usage", pending.modelRequestId());
        return host.services().records().encode(recordId, fact, at);
    }

    /**
     * 由租户、用户、会话和原始执行身份构造 Store 记录键，不伪造 Ja Turn 前缀。
     */
    private AgentStore.ExecutionKey executionKey() {
        AgentIdentity identity = host.identity();
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId()), identity.turnId());
    }

    /**
     * 提供本次 Loop 所需的稳定身份和同事务提交入口，资源所有权继续留在执行宿主。
     */
    interface Host {
        /**
         * 返回构造当前执行时冻结的租户、会话、执行和调用方身份。
         */
        AgentIdentity identity();

        /**
         * 返回由统一组合根创建的 codec、store 和时钟视图。
         */
        AgentExecutionDriver.Services services();

        /**
         * 返回最近一次提交后重新读取的持久游标。
         */
        TurnExecutionState cursor();

        /**
         * 返回最近一次确认的会话 revision 与执行 mutation 水位。
         */
        AgentLoopPersistence.Restored restored();

        /**
         * 将目标状态、游标、用量事实和事件按同一执行 CAS 提交。
         */
        AgentStore.CommitReceipt commit(TurnState target, TurnExecutionState next,
                                        List<AgentStore.RecordWrite> records,
                                        List<ExecutionEvent> events);

        /**
         * 在形成 Provider intent 前确认本节点仍持有有效执行权。
         */
        void assertDispatchAuthority();
    }
}
