// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.in;

import io.github.kongweiguang.ja.goal.domain.GoalModels.Plan;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PublicEvent;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanProgress;

import java.util.Objects;

/** 已提交 Plan mutation 的轻量观察投影；完整 revision/evidence 仍按需读取。 */
public record PlanEvent(Plan plan, long eventSequence, PublicEvent activity, PlanProgress progress) {
    /** 发布同一事务后的快照与对应 activity，避免订阅者看到跨 revision 拼接数据。 */
    public PlanEvent {
        Objects.requireNonNull(plan, "plan");
        if (eventSequence < 1) throw new IllegalArgumentException("invalid Plan event sequence");
        Objects.requireNonNull(activity, "activity");
    }
}
