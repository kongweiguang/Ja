// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.port.in;

import io.github.kongweiguang.ja.goal.domain.GoalModels.GoalSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PublicEvent;

import java.util.Objects;

/** 已提交 Goal mutation 的进程内投影；SQLite event 仍是重连后的唯一恢复来源。 */
public record GoalEvent(GoalSnapshot snapshot, PublicEvent activity) {
    /** 发布前冻结完整状态和对应持久 activity，禁止订阅者自行跨事务补读。 */
    public GoalEvent {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(activity, "activity");
    }
}
