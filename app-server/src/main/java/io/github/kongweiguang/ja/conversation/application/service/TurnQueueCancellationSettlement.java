// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.loop.TerminalCoordinator;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 串行收口尚未执行即被取消的 Turn，避免取消线程与运行 Lane 竞争终态提交。
 */
final class TurnQueueCancellationSettlement {
    private final ExecutorService terminalExecutor;
    private final CancellationCoordinator cancellations;
    private final Map<TurnService.Key, TurnOwnership> active;
    private final TurnTerminalSettlement terminal;

    /**
     * 注入唯一终态执行器及活动所有权表，保证排队取消由单一 owner 释放资源。
     */
    TurnQueueCancellationSettlement(ExecutorService terminalExecutor,
                                    CancellationCoordinator cancellations,
                                    Map<TurnService.Key, TurnOwnership> active,
                                    TurnTerminalSettlement terminal) {
        this.terminalExecutor = Objects.requireNonNull(terminalExecutor, "terminalExecutor");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.active = Objects.requireNonNull(active, "active");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    /**
     * 把排队取消提交给串行终态执行器；拒绝提交时记录欠账并立即失败收口。
     */
    void schedule(TurnService.Key key, TurnOwnership turn, Throwable cancellationFailure) {
        Runnable settle = () -> finish(key, turn, cancellationFailure);
        try {
            terminalExecutor.execute(settle);
        } catch (RejectedExecutionException rejected) {
            turn.cancellationDebt.compareAndSet(null, rejected);
            fail(key, turn, cancellationFailure, rejected);
        }
    }

    /**
     * 由 CAS 选出拒绝路径的唯一收口者，并把完成信号放在资源释放之后。
     * 该顺序让 completion 同时成为所有权释放屏障，避免关闭线程观察到已失败但仍活跃的 Turn。
     */
    private void fail(TurnService.Key key, TurnOwnership turn, Throwable cancellationFailure,
                      RejectedExecutionException rejected) {
        if (!turn.queuedSettlement.compareAndSet(false, true)) return;
        if (cancellationFailure != null && cancellationFailure != rejected) {
            rejected.addSuppressed(cancellationFailure);
        }
        Throwable releaseFailure = release(key, turn);
        if (releaseFailure != null && releaseFailure != rejected) {
            rejected.addSuppressed(releaseFailure);
        }
        turn.completion.completeExceptionally(rejected);
    }

    /**
     * 由 CAS 保证只提交一次取消终态，并在释放全部所有权后才兑现完成 Future。
     * 终态、取消欠账和释放失败会合并为单一可观察结果，调用方无需再与关闭线程竞争。
     */
    private void finish(TurnService.Key key, TurnOwnership turn, Throwable cancellationFailure) {
        if (!turn.queuedSettlement.compareAndSet(false, true)) return;
        Throwable debt = cancellationFailure != null ? cancellationFailure : turn.cancellationDebt.get();
        TurnResult result = null;
        Throwable settlementFailure = debt;
        try {
            TerminalCoordinator.Outcome outcome = terminal.commitUnexpectedTerminal(turn,
                    TurnState.CANCELLED, "CANCELLED", "turn cancelled");
            if (debt == null) {
                result = new TurnResult(
                        outcome.event().state(), outcome.event().summary(), outcome.event());
            }
        } catch (Throwable failure) {
            if (debt != null && debt != failure) failure.addSuppressed(debt);
            settlementFailure = failure;
        }
        Throwable releaseFailure = release(key, turn);
        if (releaseFailure != null) {
            if (settlementFailure == null) settlementFailure = releaseFailure;
            else if (settlementFailure != releaseFailure) {
                settlementFailure.addSuppressed(releaseFailure);
            }
        }
        if (settlementFailure == null) {
            turn.completion.complete(Objects.requireNonNull(result, "turn result"));
        } else {
            turn.completion.completeExceptionally(settlementFailure);
        }
    }

    /**
     * 按 Scope、协调器索引、活动表、运行时租约顺序尽力释放全部资源。
     * 任一步失败都不能阻断后续释放；聚合后的异常交给最终 completion 统一传播。
     */
    private Throwable release(TurnService.Key key, TurnOwnership turn) {
        Throwable failure = runReleaseStep(null, turn.cancellation::close);
        failure = runReleaseStep(failure,
                () -> cancellations.complete(key.threadId(), key.turnId()));
        failure = runReleaseStep(failure, () -> active.remove(key, turn));
        return runReleaseStep(failure, turn.runtimeLease::close);
    }

    /**
     * 执行一个资源释放步骤并保留首个失败，后续失败作为 suppressed 追加以保持根因稳定。
     */
    private static Throwable runReleaseStep(Throwable previous, Runnable step) {
        try {
            step.run();
            return previous;
        } catch (Throwable failure) {
            if (previous == null) return failure;
            if (previous != failure) previous.addSuppressed(failure);
            return previous;
        }
    }
}
