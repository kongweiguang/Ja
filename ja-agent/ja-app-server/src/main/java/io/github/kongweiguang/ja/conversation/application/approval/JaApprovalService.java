// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.approval;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionService.ResumeScheduler;
import io.github.kongweiguang.ja.conversation.application.service.JaTurnRequestMapper;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.NativeExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 审批决定进入 Core 的持久等待事务；RPC 连接只承载结果，不拥有审批生命周期。
 */
public final class JaApprovalService implements ApprovalUseCase {
    private final AgentStore store;
    private final ExecutionControlCodec codec;
    private final AgentEngine engine;
    private final Function<String, Optional<ClientOperationReceipt>> receipts;
    private final AtomicReference<ResumeScheduler> scheduler = new AtomicReference<>();

    /**
     * 回执读取与执行存储由宿主显式注入，服务不会建立第二份内存审批队列。
     */
    public JaApprovalService(AgentStore store, ExecutionControlCodec codec, AgentEngine engine,
                             Function<String, Optional<ClientOperationReceipt>> receipts) {
        this.store = Objects.requireNonNull(store, "store");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
    }

    /**
     * 回读必须匹配完整提交身份，不能把同键的其它操作当作本次审批成功。
     */
    private static Resolution resolution(ClientOperationReceipt receipt, String threadId, String turnId,
                                         String approvalId, String fingerprint) {
        if (!receipt.matches("approval/respond", fingerprint) || !receipt.threadId().equals(threadId)
            || !receipt.turnId().equals(turnId) || !approvalId.equals(receipt.approvalId())) {
            throw new ApprovalException(Failure.CONFLICT);
        }
        return new Resolution(receipt.threadRevision());
    }

    /**
     * 已关闭等待只有过期与其它确定结算两种用户动作，不能把期限结束显示成重复回答。
     */
    static Failure closedWaitFailure(io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus status) {
        return status == io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus.EXPIRED
                ? Failure.EXPIRED : Failure.ALREADY_RESOLVED;
    }

    /**
     * 后置绑定打破组合根构造环；重复绑定必须暴露接线错误。
     */
    public void bindResumeScheduler(ResumeScheduler value) {
        if (!scheduler.compareAndSet(null, Objects.requireNonNull(value, "scheduler"))) {
            throw new IllegalStateException("approval resume scheduler already bound");
        }
    }

    /**
     * 原操作可重放原回执；首次回答的等待版本和 Ja 会话版本由提交事务再次校验。
     */
    @Override
    public CompletionStage<Resolution> resolve(String threadId, String turnId, String approvalId,
                                               long expectedThreadRevision, ApprovalDecision decision, String operationKey,
                                               String requestFingerprint, ConversationTurnEventSink sink) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(sink, "sink");
        ResumeScheduler resume = Objects.requireNonNull(scheduler.get(), "approval resume scheduler not bound");
        NativeExecutionContext nativeContext = NativeExecutionContext.shared();
        NativeExecutionSnapshot nativeSnapshot = nativeContext.current().orElse(null);
        if (nativeContext.sharedMode() && nativeSnapshot == null) {
            throw new IllegalStateException("authenticated native context required to resume approval");
        }
        Optional<ClientOperationReceipt> existing = receipts.apply(operationKey);
        if (existing.isPresent()) {
            Resolution committed = resolution(existing.orElseThrow(), threadId, turnId, approvalId, requestFingerprint);
            resume.schedule(threadId, turnId, committed.threadRevision(), sink, nativeSnapshot);
            return CompletableFuture.completedFuture(committed);
        }
        AgentIdentity identity = JaTurnRequestMapper.identity(threadId, turnId);
        AgentStore.ExecutionKey key = AppServerIdentity.local().executionKey(identity);
        InteractionWait wait = readWait(key, approvalId);
        if (wait.status() != io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus.PENDING) {
            Optional<ClientOperationReceipt> committedDuringRead = receipts.apply(operationKey);
            if (committedDuringRead.isPresent()) {
                Resolution committed = resolution(committedDuringRead.orElseThrow(),
                        threadId, turnId, approvalId, requestFingerprint);
                resume.schedule(threadId, turnId, committed.threadRevision(), sink, nativeSnapshot);
                return CompletableFuture.completedFuture(committed);
            }
            throw new ApprovalException(closedWaitFailure(wait.status()));
        }
        String wire = switch (decision) {
            case APPROVE -> "approve";
            case DENY -> "deny";
            default -> throw new IllegalArgumentException("unsupported approval decision");
        };
        AgentAnswer answer = new AgentAnswer(approvalId, operationKey, wait.interactionRevision(),
                List.of(new InteractionAnswer("question_approval", List.of("option_" + wire), null, false)),
                Map.of("ja.operationKey", operationKey, "ja.requestFingerprint", requestFingerprint,
                        "ja.expectedThreadRevision", Long.toString(expectedThreadRevision),
                        "ja.approvalId", approvalId, "ja.decision", wire));
        return engine.answer(identity, answer).thenApply(ignored -> {
            Resolution committed = resolution(receipts.apply(operationKey).orElseThrow(
                            () -> new IllegalStateException("committed approval receipt missing")),
                    threadId, turnId, approvalId, requestFingerprint);
            resume.schedule(threadId, turnId, committed.threadRevision(), sink, nativeSnapshot);
            return committed;
        });
    }

    /**
     * 固定记录水位分页寻找最新等待，避免并发提交混入恢复视图或无界物化历史。
     */
    private InteractionWait readWait(AgentStore.ExecutionKey key, String approvalId) {
        try {
            AgentStore.ExecutionSnapshot snapshot = store.loadExecution(key)
                    .orElseThrow(() -> new ApprovalException(Failure.NOT_FOUND));
            InteractionWait latest = null;
            long after = 0;
            while (after < snapshot.lastRecordSequence()) {
                AgentStore.RecordPage page = store.readRecords(
                        AgentStore.RecordQuery.withDefaultMaxBytes(key, after, snapshot.lastRecordSequence(), 128));
                for (AgentStore.StoredRecord record : page.records()) {
                    if ("agent.interaction.wait".equals(record.recordType())) {
                        InteractionWait candidate = codec.decodeWait(record);
                        if (candidate.interactionId().equals(approvalId)) {
                            latest = candidate;
                        }
                    }
                }
                if (!page.hasMore()) {
                    break;
                }
                if (page.nextSequence() <= after) {
                    throw new IllegalStateException("wait record page did not advance");
                }
                after = page.nextSequence();
            }
            if (latest == null) {
                throw new ApprovalException(Failure.NOT_FOUND);
            }
            if (!(latest.attributes().get("kind") instanceof io.github.kongweiguang.ja.agent.domain.json.JsonText(
                    String value
            )) || !"approval".equals(value)) {
                throw new ApprovalException(Failure.NOT_FOUND);
            }
            return latest;
        } catch (ApprovalException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ApprovalException(Failure.UNAVAILABLE, failure);
        }
    }
}

