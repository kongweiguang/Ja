// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.TerminalCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.TurnExecutionPlan;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.RuntimeLease;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 聚合已接纳 Turn 的队列槽位、取消 Scope、运行时代际租约和唯一终态完成权。
 */
final class TurnOwnership {
    final TurnExecutionPlan request;
    final TurnEventSink sink;
    final CancellationCoordinator.CancellationScope cancellation;
    final TerminalCoordinator terminalCoordinator;
    final RuntimeLease runtimeLease;
    final CompletableFuture<TurnResult> completion;
    final boolean provisionalTitleCreated;
    final AtomicBoolean cancelRequested = new AtomicBoolean();
    final AtomicReference<ConversationRepository.CancellationClaim> cancellationClaim = new AtomicReference<>();
    final AtomicReference<Throwable> cancellationDebt = new AtomicReference<>();
    final AtomicBoolean queuedSettlement = new AtomicBoolean();
    volatile long cancellationExpectedThreadRevision = -1;
    final Instant deadlineAt;
    volatile ScheduledFuture<?> deadline;

    /**
     * 在准入成功点冻结全部资源所有权，后续完成或取消路径必须成对释放这些句柄。
     */
    TurnOwnership(TurnExecutionPlan request, TurnEventSink sink,
                  CancellationCoordinator.CancellationScope cancellation,
                  TerminalCoordinator terminalCoordinator,
                  RuntimeLease runtimeLease,
                  CompletableFuture<TurnResult> completion,
                  boolean provisionalTitleCreated,
                  Instant deadlineAt) {
        this.request = Objects.requireNonNull(request, "request");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.terminalCoordinator = Objects.requireNonNull(terminalCoordinator, "terminalCoordinator");
        this.runtimeLease = Objects.requireNonNull(runtimeLease, "runtimeLease");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.provisionalTitleCreated = provisionalTitleCreated;
        this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
    }

    /**
     * 返回同一个执行计划视图，终态协调器不得另存可能漂移的命令副本。
     */
    TurnExecutionPlan command() {
        return request;
    }
}
