// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 把已提交 Goal 事件唤醒 continuation，不持有任何 Goal 投影副本。 */
public final class GoalContinuationSubscription implements AutoCloseable {
    private final AutoCloseable subscription;

    /** 只在事件提交后尝试调度；资格和单飞仍由 coordinator/SQLite 复核。 */
    public GoalContinuationSubscription(GoalUseCase goals, GoalContinuationCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator");
        this.subscription = Objects.requireNonNull(goals, "goals").subscribe(event -> {
            coordinator.continueIfEligible(event.snapshot().goal().goalId());
            return CompletableFuture.completedFuture(null);
        });
    }

    /** 关闭顺序先解除事件源，避免 coordinator 关闭后收到新唤醒。 */
    @Override public void close() {
        try {
            subscription.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Goal continuation subscription close failed", failure);
        }
    }
}
