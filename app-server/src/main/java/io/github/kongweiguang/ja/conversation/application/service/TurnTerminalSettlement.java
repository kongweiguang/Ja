// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.conversation.application.loop.TerminalCoordinator;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.in.TurnResult;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 在意外退出路径以存储 CAS 和终态协调器提交唯一权威终态，并隔离通知失败。
 */
final class TurnTerminalSettlement {
    private final ConversationRepository store;
    private final Clock clock;

    /**
     * 固定权威 Repository 与时钟，使终态回执和事件使用同一持久化边界。
     */
    TurnTerminalSettlement(ConversationRepository store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 将异常执行收敛为取消或失败终态；已有取消 CAS 时取消语义优先。
     */
    void settleEmergency(TurnOwnership turn, TurnState state, String code, String message,
                         Throwable originalFailure) {
        TurnState terminalState = turn.cancellationClaim.get() == null ? state : TurnState.CANCELLED;
        String terminalCode = terminalState == TurnState.CANCELLED ? "CANCELLED" : code;
        String terminalMessage = terminalState == TurnState.CANCELLED ? "turn cancelled" : message;
        try {
            TerminalCoordinator.Outcome outcome = commitUnexpectedTerminal(turn, terminalState,
                    terminalCode, terminalMessage);
            Throwable completionFailure = originalFailure == null
                    ? turn.cancellationDebt.get() : originalFailure;
            if (completionFailure == null) completeFromOutcome(turn, outcome);
            else completeExceptionally(turn, completionFailure);
        } catch (Throwable emergencyFailure) {
            /*
             * 已接纳 Future 会跨越 RPC 边界，不得保留 Provider、数据库、文件系统或取消原因；
             * 这些原因可能包含请求数据，也不是可恢复的客户端契约。恢复流程应重新读取权威 Turn。
             */
            completeExceptionally(turn, emergencyFailure);
        }
    }

    /**
     * 读取最新 mutation version 后经终态协调器提交一次 CAS，并仅由提交者发布事件。
     */
    TerminalCoordinator.Outcome commitUnexpectedTerminal(TurnOwnership turn, TurnState state,
                                                         String code, String message) {
        if (turn.cancellationClaim.get() != null) {
            state = TurnState.CANCELLED;
            code = "CANCELLED";
            message = "turn cancelled";
        }
        TurnState terminalState = state;
        String terminalCode = code;
        String terminalMessage = message;
        TerminalCoordinator.Finish finish = turn.terminalCoordinator.finish(
                () -> {
                    ConversationRepository.TurnSnapshot current = store.findTurn(
                            turn.command().threadId(), turn.command().turnId()).orElseThrow();
                    return store.commitTerminal(new ConversationRepository.TerminalCommit(
                            turn.command().threadId(), turn.command().turnId(), terminalState, "",
                            terminalCode, terminalMessage, null, null, List.of(),
                            current.turnMutationVersion(), clock.instant()));
                },
                committed -> new TurnEvent.Terminal(new TurnEvent.Context(
                        "evt_" + UUID.randomUUID(), turn.command().threadId(), turn.command().turnId(),
                        committed.threadRevision(), clock.instant()), terminalState, "",
                        terminalState == TurnState.FAILED ? terminalCode : null,
                        terminalState == TurnState.FAILED ? terminalMessage : null, null, null),
                event -> turn.sink.publish(event));
        return finish.outcome();
    }

    /**
     * 用权威终态回执完成已接纳 Future，避免从异常文本推导客户端状态。
     */
    private static void completeFromOutcome(TurnOwnership turn, TerminalCoordinator.Outcome outcome) {
        turn.completion.complete(new TurnResult(
                outcome.event().state(), outcome.event().summary(), outcome.event()));
    }

    /**
     * 对 RPC 屏蔽底层敏感失败，仅暴露可稳定识别的终态收口异常。
     */
    private static void completeExceptionally(TurnOwnership turn, Throwable ignoredFailure) {
        turn.completion.completeExceptionally(new TerminalSettlementFailure());
    }

    /**
     * 表示权威终态无法确认，调用方应重新读取 Turn，而非信任本地推断。
     */
    static final class TerminalSettlementFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /**
         * 使用固定安全消息，禁止携带 Provider、SQL、路径或取消原因。
         */
        TerminalSettlementFailure() {
            super("turn terminal settlement failed");
        }
    }

}
