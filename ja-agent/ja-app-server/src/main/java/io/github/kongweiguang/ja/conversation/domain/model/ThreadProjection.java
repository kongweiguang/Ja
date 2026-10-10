// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;


import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Ja 对话用例读取配置与 Turn 状态的最小一致视图。
 * <p>模型历史由 AgentStore 负责，UI 时间线由对话查询用例负责；此投影不复制两者的数据。</p>
 *
 * @param threadId 所属对话的稳定标识
 * @param workspaceId 工作区稳定标识
 * @param preferences 偏好配置
 * @param revision 当前修订版本
 * @param turns 执行轮次列表
 */
public record ThreadProjection(String threadId,
                               String workspaceId,
                               ThreadPreferences preferences,
                               long revision,
                               List<TurnProjection> turns) {
    /**
     * 拒绝不完整的存储投影并冻结 Turn 列表，避免调用方在一次决策中观察到不同版本。
     */
    public ThreadProjection {
        requireIdentifier(threadId, "thr_", "threadId");
        requireIdentifier(workspaceId, "ws_", "workspaceId");
        Objects.requireNonNull(preferences, "preferences");
        if (revision < 0) {
            throw new IllegalArgumentException("invalid thread revision");
        }
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        HashSet<String> turnIds = new HashSet<>();
        for (TurnProjection turn : turns) {
            Objects.requireNonNull(turn, "turn");
            if (!turnIds.add(turn.turnId())) {
                throw new IllegalArgumentException("duplicate turn projection");
            }
        }
    }

    /**
     * 复用持久化 Thread ID 的稳定语法，错误中不回显可能敏感的标识值。
     */
    private static void requireIdentifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
