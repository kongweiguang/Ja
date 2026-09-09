// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 用固定条带锁串行化同一 Goal 的 continuation admission 与暂停/停止提交。 */
public final class GoalContinuationGate {
    private static final int STRIPE_COUNT = 64;
    private final Object[] stripes = new Object[STRIPE_COUNT];
    private final ConcurrentHashMap<String, ActiveContinuation> active = new ConcurrentHashMap<>();

    /** 固定数量的锁避免按 Goal 永久积累 monitor，同时不同 Goal 仍可并行。 */
    public GoalContinuationGate() {
        for (int index = 0; index < stripes.length; index++) stripes[index] = new Object();
    }

    /** 在对应条带内执行 admission 或状态提交，关闭“已暂停但刚好又启动”的竞态窗口。 */
    public <T> T serialized(String goalId, Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (stripe(goalId)) {
            return operation.get();
        }
    }

    /** admission 成功后记录 Turn 与 lease fencing identity，旧 completion 不能清除后续续跑。 */
    public void activate(String goalId, String turnId, long fencingToken, Runnable cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        ActiveContinuation candidate = new ActiveContinuation(turnId, fencingToken, cancellation);
        ActiveContinuation previous = active.putIfAbsent(requireGoalId(goalId), candidate);
        if (previous != null) throw new IllegalStateException("Goal continuation is already active");
    }

    /** lease 已持久释放后才清除匹配 identity，旧 Turn 回调不能误删后续 continuation。 */
    public void complete(String goalId, String turnId, long fencingToken) {
        active.computeIfPresent(requireGoalId(goalId), (ignored, current) ->
                current.turnId().equals(turnId) && current.fencingToken() == fencingToken ? null : current);
    }

    /** 状态已经持久化后调用当前取消动作；没有活动 Turn 视为幂等成功。 */
    public void cancelActive(String goalId) {
        ActiveContinuation current = active.get(requireGoalId(goalId));
        if (current != null) current.cancellation().run();
    }

    /** 恢复必须等旧 Turn 与 lease 都由 coordinator 收口，不能只依赖取消请求已写入。 */
    public void requireSettled(String goalId) {
        if (active.containsKey(requireGoalId(goalId))) {
            throw new GoalRepositoryException(GoalRepositoryException.Code.GOAL_INVALID_STATE,
                    "Goal continuation is still settling");
        }
    }

    /** 使用稳定非空 identity 选择条带，避免恶意 Goal ID 制造额外锁对象。 */
    private Object stripe(String goalId) {
        String value = requireGoalId(goalId);
        return stripes[(value.hashCode() & Integer.MAX_VALUE) % stripes.length];
    }

    /** Gate 是内部一致性边界，调用方不得用空 identity 合并不相关 Goal。 */
    private static String requireGoalId(String goalId) {
        if (goalId == null || goalId.isBlank()) throw new IllegalArgumentException("invalid Goal identity");
        return goalId;
    }

    /** 活动项同时冻结 Turn 与 lease identity，进程内 gate 不替代 SQLite fencing 权威。 */
    private record ActiveContinuation(String turnId, long fencingToken, Runnable cancellation) {
        /** 取消能力必须完整，避免暂停提交后才发现无法中断当前 Turn。 */
        private ActiveContinuation {
            Objects.requireNonNull(turnId, "turnId");
            if (fencingToken < 1) throw new IllegalArgumentException("invalid Goal continuation fencing token");
            Objects.requireNonNull(cancellation, "cancellation");
        }
    }
}
