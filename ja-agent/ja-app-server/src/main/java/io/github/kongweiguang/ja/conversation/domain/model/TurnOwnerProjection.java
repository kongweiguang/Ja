// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;


import java.util.Objects;

/**
 * 以唯一 Turn ID 定位宿主会话配置和 CAS 锚点，不扫描 Thread 或复制执行事实。
 *
 * @param threadId 所属对话的稳定标识
 * @param workspaceId 工作区稳定标识
 * @param preferences 偏好配置
 * @param threadRevision 对话修订版本
 * @param turn 执行轮次
 */
public record TurnOwnerProjection(String threadId,
                                  String workspaceId,
                                  ThreadPreferences preferences,
                                  long threadRevision,
                                  TurnProjection turn) {
    /**
     * 防止丢失 Turn 所有者或跨会话组装 revision，并冻结当前状态投影。
     */
    public TurnOwnerProjection {
        requireIdentifier(threadId, "thr_", "threadId");
        requireIdentifier(workspaceId, "ws_", "workspaceId");
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(turn, "turn");
        if (threadRevision < 0 || !turn.turnId().startsWith("turn_")) {
            throw new IllegalArgumentException("invalid Turn owner projection");
        }
    }

    /**
     * 宿主归属身份采用与现有 Thread/Workspace 投影相同的稳定字符集。
     */
    private static void requireIdentifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
