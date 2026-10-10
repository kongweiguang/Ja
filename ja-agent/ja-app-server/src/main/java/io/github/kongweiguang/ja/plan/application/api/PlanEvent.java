// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.api;

import io.github.kongweiguang.ja.plan.domain.PlanModels.Plan;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanProgress;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PublicEvent;

import java.util.Objects;

/**
 * 已提交 Plan mutation 的轻量观察投影；完整 revision/evidence 仍按需读取。
 *
 * @param plan 执行计划
 * @param eventSequence 事件序号
 * @param activity 活动
 * @param progress 进度
 */
public record PlanEvent(Plan plan,
                        long eventSequence,
                        PublicEvent activity,
                        PlanProgress progress) {
    /**
     * 发布同一事务后的快照与对应 activity，避免订阅者看到跨 revision 拼接数据。
     */
    public PlanEvent {
        Objects.requireNonNull(plan, "plan");
        if (eventSequence < 1) {
            throw new IllegalArgumentException("invalid Plan event sequence");
        }
        Objects.requireNonNull(activity, "activity");
    }
}

