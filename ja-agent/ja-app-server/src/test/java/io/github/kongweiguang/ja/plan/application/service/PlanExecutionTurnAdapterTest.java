// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 Plan Turn 只有真实终态结果经过 ledger 提交后才成为可确认完成。 */
final class PlanExecutionTurnAdapterTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    /** CANCELLED 是有效终态结果，ledger 提交之后仍原样返回给 Stop owner。 */
    @Test
    void preservesCancelledResultAfterLedgerSettlement() {
        TurnResult cancelled = cancelledResult();
        AtomicInteger settlementCalls = new AtomicInteger();

        TurnResult settled = PlanExecutionTurnAdapter.settleCompletion(
                CompletableFuture.completedFuture(cancelled), settlementCalls::incrementAndGet)
                .toCompletableFuture().join();

        assertSame(cancelled, settled);
        assertEquals(1, settlementCalls.get());
    }

    /** Ledger 写入失败必须阻断取消回执，避免 Coordinator 或用户界面看到虚假的 STOPPED。 */
    @Test
    void ledgerSettlementFailureBlocksTerminalResult() {
        RuntimeException settlementFailure = new IllegalStateException("Ledger settlement failed");
        AtomicInteger settlementCalls = new AtomicInteger();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                PlanExecutionTurnAdapter.settleCompletion(CompletableFuture.completedFuture(cancelledResult()), () -> {
                    settlementCalls.incrementAndGet();
                    throw settlementFailure;
                }).toCompletableFuture().join());

        assertSame(settlementFailure, failure.getCause());
        assertEquals(1, settlementCalls.get());
    }

    /** completion 异常没有可核对的 TurnResult，不能把未知终态写成已结算 claim。 */
    @Test
    void missingTerminalResultDoesNotSettleLedger() {
        RuntimeException completionFailure = new IllegalStateException("Turn terminal is unknown");
        AtomicInteger settlementCalls = new AtomicInteger();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                PlanExecutionTurnAdapter.settleCompletion(
                        CompletableFuture.<TurnResult>failedFuture(completionFailure),
                        settlementCalls::incrementAndGet).toCompletableFuture().join());

        assertSame(completionFailure, failure.getCause());
        assertEquals(0, settlementCalls.get());
    }

    /** 生成状态与终态事件一致的 CANCELLED 结果，禁止测试用不完整 DTO 代替权威 Turn 事实。 */
    private static TurnResult cancelledResult() {
        TurnEvent.Context context = new TurnEvent.Context(
                "evt_cancel_adapter", "thr_plan_adapter", "turn_plan_adapter", 1, 0, NOW);
        TurnEvent.Terminal terminal = new TurnEvent.Terminal(context, TurnState.CANCELLED,
                "cancelled", null, null, null, null, null);
        return new TurnResult(TurnState.CANCELLED, terminal.summary(), terminal);
    }
}
