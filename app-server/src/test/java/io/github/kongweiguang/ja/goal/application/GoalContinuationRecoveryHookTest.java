// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.GoalRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证重启恢复只重建 fence/回调，不自动启动模型或重复执行 Tool。 */
final class GoalContinuationRecoveryHookTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    /** PENDING 恢复重新登记回答回调，并严格使用新 fence 与原 Turn/run identity。 */
    @Test
    void registersPendingResumeWithNewFence() {
        AtomicReference<GoalContinuationCoordinator.ContinuationRequest> registered = new AtomicReference<>();
        AtomicReference<GoalRepository.ContinuationLease> released = new AtomicReference<>();
        AtomicReference<GoalRepository.ContinuationLease> acquired = new AtomicReference<>();
        GoalRepository repository = repository(candidate(InteractionStatus.PENDING), released, acquired);
        GoalContinuationCoordinator.ContinuationTurnPort turns = new GoalContinuationCoordinator.ContinuationTurnPort() {
            /** 恢复准备不依赖空闲触发，禁止测试意外启动新 Turn。 */
            @Override public boolean ownerIdle(String threadId) { return false; }
            /** 启动恢复只登记身份，不产生模型调用。 */
            @Override public java.util.concurrent.CompletionStage<Void> start(
                    GoalContinuationCoordinator.ContinuationRequest request) { throw new AssertionError("must not start"); }
            /** 捕获新 fence 与原 Turn，确认回调没有误绑定新 Run。 */
            @Override public void registerResumeContinuation(
                    GoalContinuationCoordinator.ContinuationRequest request,
                    java.util.function.Consumer<java.util.concurrent.CompletionStage<?>> continuation) {
                registered.set(request);
            }
        };

        new GoalContinuationRecoveryHook(repository, turns, Clock.fixed(NOW, ZoneOffset.UTC), 9, (request, waiting) -> { }).recover(
                lease("lease_old", 3, "ABANDONED"));

        assertEquals("turn_resume", registered.get().turnId());
        assertEquals("run_resume", registered.get().runId());
        assertEquals(8, registered.get().fencingToken());
        assertEquals(8, acquired.get().fencingToken());
        assertNull(released.get());
    }

    /** 已回答也必须登记结算回调，但不越过用户显式 Continue 入口调用模型。 */
    @Test
    void leavesAnsweredAsExplicitResumePlaceholder() {
        AtomicReference<GoalRepository.ContinuationLease> released = new AtomicReference<>();
        AtomicReference<GoalRepository.ContinuationLease> acquired = new AtomicReference<>();
        GoalRepository repository = repository(candidate(InteractionStatus.ANSWERED), released, acquired);
        AtomicReference<GoalContinuationCoordinator.ContinuationRequest> registered = new AtomicReference<>();
        GoalContinuationCoordinator.ContinuationTurnPort turns = new GoalContinuationCoordinator.ContinuationTurnPort() {
            /** 该恢复用例不启动新的自动调度。 */
            @Override public boolean ownerIdle(String threadId) { return false; }
            /** 已提交答案不等于启动权限，必须保持零模型调用。 */
            @Override public java.util.concurrent.CompletionStage<Void> start(
                    GoalContinuationCoordinator.ContinuationRequest request) { throw new AssertionError("must not start"); }
            /** 显式继续后仍需收口新 lease，不能只保存 fence 却丢失 completion。 */
            @Override public void registerResumeContinuation(GoalContinuationCoordinator.ContinuationRequest request,
                    java.util.function.Consumer<java.util.concurrent.CompletionStage<?>> continuation) { registered.set(request); }
        };

        new GoalContinuationRecoveryHook(repository, turns, Clock.fixed(NOW, ZoneOffset.UTC), 9, (request, waiting) -> { }).recover(
                lease("lease_old", 3, "ABANDONED"));

        assertEquals(8, acquired.get().fencingToken());
        assertEquals("turn_resume", registered.get().turnId());
        assertNull(released.get());
    }

    /** hook 所需的最小 Goal repository fake，未声明的写操作一律失败，防止隐式副作用。 */
    private static GoalRepository repository(GoalRepository.ContinuationRecoveryCandidate candidate,
                                             AtomicReference<GoalRepository.ContinuationLease> released,
                                             AtomicReference<GoalRepository.ContinuationLease> acquired) {
        GoalModels.Goal goal = new GoalModels.Goal("goal_resume", "thread_resume", GoalModels.OwnerKind.ROOT_THREAD,
                "恢复目标", 1, GoalModels.GoalStatus.ACTIVE, GoalModels.GoalPhase.WAITING_INPUT, 2,
                "run_resume", 0, 0, null, false, NOW, NOW);
        return (GoalRepository) Proxy.newProxyInstance(GoalRepository.class.getClassLoader(),
                new Class<?>[]{GoalRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findGoal" -> Optional.of(goal);
                    case "findContinuationRecovery" -> Optional.of(candidate);
                    case "tryAcquireLease" -> {
                        GoalRepository.ContinuationLease value = lease("lease_new", 8, "HELD");
                        acquired.set(value);
                        yield Optional.of(value);
                    }
                    case "releaseLease" -> {
                        released.set(lease((String) args[1], ((Number) args[2]).longValue(), "ABANDONED"));
                        yield Optional.empty();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 构造严格符合不可变 GOAL_CONTINUATION context 约束的恢复候选。 */
    private static GoalRepository.ContinuationRecoveryCandidate candidate(InteractionStatus status) {
        GoalRepository.InternalTurnBinding binding = new GoalRepository.InternalTurnBinding(
                "turn_resume", "GOAL_CONTINUATION", "goal_resume", null, "run_resume", 1L,
                null, null, 3L);
        return new GoalRepository.ContinuationRecoveryCandidate("goal_resume", "thread_resume", "turn_resume",
                "run_resume", 5, 4, "interaction_resume", status, binding);
    }

    /** lease identity 包含旧/新 fencing token，测试不允许通过字符串状态替代持久事实。 */
    private static GoalRepository.ContinuationLease lease(String id, long token, String state) {
        return new GoalRepository.ContinuationLease("goal_resume", id, 3, token, state, NOW, NOW,
                "HELD".equals(state) ? null : NOW);
    }
}
