// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.agent.domain.turn.TurnState;

import java.time.Instant;
import java.util.Objects;

/**
 * 表示一个 Thread 的权威元数据投影。
 *
 * @param threadId 所属对话的稳定标识
 * @param workspaceId 工作区稳定标识
 * @param title 显示标题
 * @param workspaceKind 工作区类型
 * @param preferences 偏好配置
 * @param status 当前状态分类
 * @param pinned 是否固定
 * @param latestTurnStatus 最近执行轮次状态
 * @param latestTurnSeen 是否已读最近一次执行
 * @param revision 当前修订版本
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 * @param latestTurnId 最近执行轮次标识
 */
public record ThreadSummary(String threadId,
                            String workspaceId,
                            String title,
                            String workspaceKind,
                            ThreadPreferences preferences,
                            Status status,
                            boolean pinned,
                            TurnState latestTurnStatus,
                            boolean latestTurnSeen,
                            long revision,
                            Instant createdAt,
                            Instant updatedAt,
                            String latestTurnId) {
    /**
     * 在离开应用层前校验身份、状态和时间；最新 Turn 身份只服务本地 owner 投影，不进入 RPC。
     */
    public ThreadSummary {
        threadId = identifier(threadId, "thr_", 96);
        workspaceId = identifier(workspaceId, "ws_", 96);
        title = title(title);
        if (!java.util.Set.of("project", "session").contains(workspaceKind)) {
            throw new IllegalArgumentException("invalid workspace kind");
        }
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(status, "status");
        if (latestTurnStatus == null && !latestTurnSeen) {
            throw new IllegalArgumentException("thread without turns must be seen");
        }
        if (latestTurnId != null) {
            latestTurnId = identifier(latestTurnId, "turn_", 96);
        }
        if (revision < 0) {
            throw new IllegalArgumentException("invalid thread revision");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 不含执行身份的摘要构造显式留空；持久列表投影应使用含 latestTurnId 的主构造器。
     */
    public ThreadSummary(String threadId, String workspaceId, String title,
                         String workspaceKind,
                         ThreadPreferences preferences, Status status, boolean pinned,
                         TurnState latestTurnStatus, boolean latestTurnSeen, long revision,
                         Instant createdAt, Instant updatedAt) {
        this(threadId, workspaceId, title, workspaceKind, preferences, status,
                pinned, latestTurnStatus, latestTurnSeen, revision, createdAt, updatedAt, null);
    }

    /**
     * 统一校验领域身份，不复用任何 JSON-RPC 参数解析器。
     */
    private static String identifier(String value, String prefix, int bodyMaximum) {
        if (value == null || value.length() > prefix.length() + bodyMaximum
            || !value.matches(java.util.regex.Pattern.quote(prefix) + "[A-Za-z0-9_-]{1," + bodyMaximum + "}")) {
            throw new IllegalArgumentException("invalid thread identity");
        }
        return value;
    }

    /**
     * 标题允许自然语言文本，但拒绝 NUL、空白和无界内容。
     */
    private static String title(String value) {
        value = Objects.requireNonNull(value, "title");
        if (value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid thread title");
        }
        return value;
    }

    /**
     * Thread 生命周期状态只表达当前存储基线支持的可见状态。
     */
    public enum Status {
        /**
         * Thread 可以继续接收新的 Turn。
         */
        ACTIVE,
        /**
         * Thread 已归档，只允许读取历史。
         */
        ARCHIVED
    }

    /**
     * 创建 Thread 时冻结所属工作区、下一轮偏好和发生时间；标题初始来源必须是 placeholder。
     *
     * @param threadId 所属对话的稳定标识
     * @param workspaceId 工作区稳定标识
     * @param title 显示标题
     * @param preferences 偏好配置
     * @param occurredAt 事件发生时间
     */
    public record Creation(String threadId,
                           String workspaceId,
                           String title,
                           ThreadPreferences preferences,
                           Instant occurredAt) {
        /**
         * 拒绝 transport 别名或缺省身份，确保创建命令只有一种规范形式。
         */
        public Creation {
            threadId = identifier(threadId, "thr_", 96);
            workspaceId = identifier(workspaceId, "ws_", 96);
            title = ThreadSummary.title(title);
            Objects.requireNonNull(preferences, "preferences");
            if (preferences.titleSource() != ThreadPreferences.TitleSource.PLACEHOLDER) {
                throw new IllegalArgumentException("new thread title must be placeholder-owned");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
