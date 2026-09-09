// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.TerminalCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.TurnExecutionPlan;
import io.github.kongweiguang.ja.conversation.application.change.TurnChangeTracker;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聚合已接纳 Turn 的队列槽位、取消 Scope、绝对 Deadline 和唯一终态完成权。
 */
final class TurnOwnership {
    final TurnExecutionPlan request;
    final TurnEventSink sink;
    final CancellationCoordinator.CancellationScope cancellation;
    final TerminalCoordinator terminalCoordinator;
    final CompletableFuture<TurnResult> completion;
    final boolean provisionalTitleCreated;
    final AtomicBoolean cancelRequested = new AtomicBoolean();
    final AtomicReference<ConversationRepository.CancellationClaim> cancellationClaim = new AtomicReference<>();
    final AtomicReference<Throwable> cancellationDebt = new AtomicReference<>();
    final AtomicBoolean queuedSettlement = new AtomicBoolean();
    private final AtomicBoolean cancellationPropagationPublished = new AtomicBoolean();
    volatile long cancellationExpectedThreadRevision = -1;
    final Instant deadlineAt;
    final TurnExecutionState execution;
    final TurnChangeTracker changeTracker;
    volatile ScheduledFuture<?> deadline;

    /**
     * 在准入成功点固定 Operation 所有权；请求级 RuntimeLease 不得进入这个长生命周期对象。
     */
    TurnOwnership(TurnExecutionPlan request, TurnEventSink sink,
                  CancellationCoordinator.CancellationScope cancellation,
                  TerminalCoordinator terminalCoordinator,
                  CompletableFuture<TurnResult> completion,
                  boolean provisionalTitleCreated,
                  Instant deadlineAt,
                  TurnExecutionState execution) {
        this.request = Objects.requireNonNull(request, "request");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.terminalCoordinator = Objects.requireNonNull(terminalCoordinator, "terminalCoordinator");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.provisionalTitleCreated = provisionalTitleCreated;
        this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.changeTracker = request.changeTracker();
    }

    /**
     * 返回同一个执行计划视图，终态协调器不得另存可能漂移的命令副本。
     */
    TurnExecutionPlan command() {
        return request;
    }

    /** 同一活动 Turn 只允许一个调用者取得 Attached Child 取消传播权。 */
    boolean claimCancellationPropagation() {
        return cancellationPropagationPublished.compareAndSet(false, true);
    }

}
