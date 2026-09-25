// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalEvaluatorPort;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Goal 验收的瞬时重试与 Stop 传播不依赖真实付费 Provider。 */
final class GoalEvaluatorRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);

    /** 超过旧三次上限仍只结算一次 Goal，Provider 每次由出站端口重新打开。 */
    @Test
    void retriesSixTransientFailuresAndSettlesOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger settlements = new AtomicInteger();
        GoalEvaluatorPort provider = request -> {
            if (attempts.incrementAndGet() <= 6) {
                return CompletableFuture.failedFuture(new ProviderProtocolException(
                        "HTTP_STATUS", "provider unavailable", true, Duration.ZERO));
            }
            return CompletableFuture.completedFuture(new GoalEvaluatorPort.Result(
                    GoalModels.EvaluationVerdict.NOT_MET,
                    List.of(new GoalModels.CriterionEvaluation("criterion_test",
                            GoalModels.EvaluationVerdict.NOT_MET, "missing evidence")), "missing evidence"));
        };
        GoalEvaluator evaluator = new GoalEvaluator(repository(settlements), provider, CLOCK);
        CancellationSource cancellation = new CancellationSource();

        GoalRepository.CompleteEvaluation completed = evaluator.evaluateRequested(
                "evaluation_test", 1, request(cancellation), "completion_test")
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(7, attempts.get());
        assertEquals(1, settlements.get());
        assertEquals(GoalModels.EvaluationVerdict.NOT_MET, completed.verdict());
    }

    /** Stop 必须唤醒退避并阻止下一次模型请求及完成写入。 */
    @Test
    void cancellationDuringRetryWaitPreventsRestartAndSettlement() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger settlements = new AtomicInteger();
        GoalEvaluatorPort provider = request -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(new ProviderProtocolException(
                    "HTTP_STATUS", "provider unavailable", true, Duration.ofSeconds(30)));
        };
        GoalEvaluator evaluator = new GoalEvaluator(repository(settlements), provider, CLOCK);
        CancellationSource cancellation = new CancellationSource();
        CompletableFuture<GoalRepository.CompleteEvaluation> stage = evaluator.evaluateRequested(
                "evaluation_test", 1, request(cancellation), "completion_test").toCompletableFuture();

        cancellation.cancel("user_stopped");

        assertThrows(ExecutionException.class, () -> stage.get(2, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());
        assertEquals(0, settlements.get());
    }

    /** 测试输入只含一项未满足条件，绕开完成门并聚焦重试身份。 */
    private static GoalEvaluatorPort.Request request(CancellationSource cancellation) {
        return new GoalEvaluatorPort.Request("goal_test", "thr_test", 1, null, "run_test",
                "provider_test", "model_test", "finish the task", null,
                List.of(new GoalEvaluatorPort.Criterion("criterion_test", "evidence", true)),
                List.of(), cancellation);
    }

    /** 只观察完成事务次数，任何其它仓储操作都表示重试扩大了状态所有权。 */
    private static GoalRepository repository(AtomicInteger settlements) {
        return (GoalRepository) Proxy.newProxyInstance(GoalRepository.class.getClassLoader(),
                new Class<?>[]{GoalRepository.class}, (proxy, method, args) -> {
                    if ("completeEvaluation".equals(method.getName())) {
                        settlements.incrementAndGet();
                        return null;
                    }
                    throw new AssertionError("unexpected Goal repository call: " + method.getName());
                });
    }
}
