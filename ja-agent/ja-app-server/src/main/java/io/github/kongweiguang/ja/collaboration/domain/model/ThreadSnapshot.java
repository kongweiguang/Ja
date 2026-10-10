// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.domain.model;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;

import java.time.Instant;
import java.util.Objects;

/**
 * Task 详情使用的 Thread 元数据快照；它只保留协作所需的投影字段，不暴露 conversation 聚合。
 *
 * @param threadId                Thread 标识
 * @param workspaceId             Workspace 标识
 * @param title                   当前标题
 * @param workspaceKind           Workspace 类型
 * @param preferences             冻结的协作偏好
 * @param status                  Thread 生命周期状态
 * @param pinned                  是否置顶
 * @param latestTurnStatus        最近 Turn 状态
 * @param latestTurnSeen          是否已读最近 Turn
 * @param revision                投影版本
 * @param createdAt               创建时间
 * @param updatedAt               更新时间
 * @param latestTurnId            最近 Turn 标识，可为空
 */
public record ThreadSnapshot(String threadId,
                             String workspaceId,
                             String title,
                             String workspaceKind,
                             CollaborationPreferences preferences,
                             Status status,
                             boolean pinned,
                             TurnState latestTurnStatus,
                             boolean latestTurnSeen,
                             long revision,
                             Instant createdAt,
                             Instant updatedAt,
                             String latestTurnId) {
    /**
     * 读取投影时重新校验状态和身份，损坏数据不能穿透到 Task 观察者。
     */
    public ThreadSnapshot {
        threadId = identifier(threadId, "thr_", "threadId");
        workspaceId = identifier(workspaceId, "ws_", "workspaceId");
        if (title == null || title.isBlank() || title.length() > 512 || title.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid title");
        }
        if (!java.util.Set.of("project", "session").contains(workspaceKind)) {
            throw new IllegalArgumentException("invalid workspace kind");
        }

        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(status, "status");
        if (latestTurnStatus == null && !latestTurnSeen) {
            throw new IllegalArgumentException("thread without turns must be seen");
        }
        if (latestTurnId != null) {
            latestTurnId = identifier(latestTurnId, "turn_", "latestTurnId");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("invalid revision");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 只允许稳定前缀和受限字符，保证快照可安全回读并用于 CAS。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** Thread 可见生命周期只在投影闭集内传播。 */
    public enum Status {
        /** 可继续接纳 Turn。 */
        ACTIVE,
        /** 只读归档。 */
        ARCHIVED
    }
}
