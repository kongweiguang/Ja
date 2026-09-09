// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import io.github.kongweiguang.ja.goal.port.out.GoalRepositoryException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 continuation admission 与控制提交共享同一 Goal 串行边界。 */
final class GoalContinuationGateTest {
    /** 暂停路径必须先记录持久提交，再调用当前 Turn 的取消能力。 */
    @Test
    void cancelsOnlyAfterPersistedTransition() {
        GoalContinuationGate gate = new GoalContinuationGate();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        gate.serialized("goal_one", () -> {
            gate.activate("goal_one", "turn_one", 1, () -> order.add("cancelled"));
            return null;
        });

        gate.serialized("goal_one", () -> {
            order.add("persisted");
            gate.cancelActive("goal_one");
            return null;
        });

        assertEquals(List.of("persisted", "cancelled"), order);
    }

    /** 旧 Turn completion 只能清除自己的 identity，不能删除随后登记的新 continuation。 */
    @Test
    void staleCompletionDoesNotClearNewContinuation() {
        GoalContinuationGate gate = new GoalContinuationGate();
        List<String> cancellations = new ArrayList<>();
        gate.activate("goal_one", "turn_old", 1, () -> cancellations.add("old"));
        gate.complete("goal_one", "turn_old", 1);
        gate.activate("goal_one", "turn_new", 2, () -> cancellations.add("new"));

        gate.complete("goal_one", "turn_old", 1);
        gate.cancelActive("goal_one");

        assertEquals(List.of("new"), cancellations);
    }

    /** resume 在旧 continuation 收口前失败关闭，且错误码保持公开稳定。 */
    @Test
    void rejectsResumeUntilMatchingContinuationSettles() {
        GoalContinuationGate gate = new GoalContinuationGate();
        gate.activate("goal_one", "turn_old", 7, () -> { });

        GoalRepositoryException active = assertThrows(GoalRepositoryException.class,
                () -> gate.serialized("goal_one", () -> {
                    gate.requireSettled("goal_one");
                    return null;
                }));
        assertEquals(GoalRepositoryException.Code.GOAL_INVALID_STATE, active.code());

        gate.complete("goal_one", "turn_old", 6);
        assertThrows(GoalRepositoryException.class, () -> gate.requireSettled("goal_one"));
        gate.complete("goal_one", "turn_old", 7);
        gate.requireSettled("goal_one");
    }
}
