// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.util.Objects;

/**
 * 全局 Thread 发现的最小安全投影；它不携带正文、模型偏好或任务上下文。
 */
public record ThreadDiscovery(String threadId, String title, Kind kind, String workspaceId, Status status) {
    /**
     * 发现结果在领域边界重新校验身份和可见枚举，避免损坏的 SQL 行进入 Wire 层。
     */
    public ThreadDiscovery {
        threadId = identifier(threadId, "thr_", "threadId");
        title = title(title);
        Objects.requireNonNull(kind, "kind");
        workspaceId = identifier(workspaceId, "ws_", "workspaceId");
        Objects.requireNonNull(status, "status");
    }

    /**
     * discovery 仅允许一个明确的全局 scope；其余过滤条件由服务端在同一查询中应用。
     */
    public record Query(String scope, String query, String cursor, int limit, String workspaceId) {
        /**
         * 固定查询上限和 opaque 参数形状，避免发现入口变成无界正文或全库扫描。
         */
        public Query {
            if (!"all".equals(scope)) throw new IllegalArgumentException("unsupported thread discovery scope");
            if (query != null && (query.length() > 256 || query.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("invalid thread discovery query");
            }
            if (cursor != null && (cursor.isBlank() || cursor.length() > 512 || cursor.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("invalid thread discovery cursor");
            }
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("invalid thread discovery limit");
            if (workspaceId != null) workspaceId = identifier(workspaceId, "ws_", "workspaceId");
        }

        /**
         * 常用的无 Workspace 过滤构造保持调用方只关心全局搜索语义。
         */
        public Query(String scope, String query, String cursor, int limit) {
            this(scope, query, cursor, limit, null);
        }

        /**
         * 缺少 limit 时使用协议约定的最大一页，仍由 canonical constructor 做边界校验。
         */
        public Query(String scope, String query, String cursor, String workspaceId) {
            this(scope, query, cursor, 200, workspaceId);
        }

        /**
         * 首屏无筛选查询的简化构造；cursor 和 query 均可显式传 null。
         */
        public Query(String scope, String query, String cursor) {
            this(scope, query, cursor, 200, null);
        }
    }

    /** 发现条目的产品类型，Wire 层转换为 main、side_chat 或 subagent。 */
    public enum Kind {
        /** 用户独立创建且保留在主历史中的会话。 */
        MAIN,
        /** 仅临时存在的旁支对话，不承担来源任务的委派义务。 */
        SIDE_CHAT,
        /** 沿明确委派关系管理执行与结果的子任务。 */
        SUBAGENT
    }

    /** 主 Thread 使用最新 Turn 状态；没有 Turn 时显式返回 IDLE。 */
    public enum Status {
        /** 尚无运行轮次，发现操作本身不会启动执行。 */
        IDLE,
        /** 已接纳但正在等待执行资源。 */
        QUEUED,
        /** 当前存在正在推进的执行轮次。 */
        RUNNING,
        /** 等待真实权限决定，消息投递不会代替批准。 */
        WAITING_APPROVAL,
        /** 执行暂停，需要既有恢复入口处理。 */
        SUSPENDED,
        /** 最近轮次成功结束，仍可接收普通消息。 */
        COMPLETED,
        /** 最近轮次失败，发现摘要不物化错误正文。 */
        FAILED,
        /** 最近轮次已取消，不等同于会话被删除。 */
        CANCELLED
    }

    /**
     * 统一校验发现结果的 opaque identity，避免复用 transport 参数解析器造成方向依赖。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 标题只允许用户可见自然语言，不把 NUL 或无界数据库值交给展示层。
     */
    private static String title(String value) {
        Objects.requireNonNull(value, "title");
        if (value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid title");
        }
        return value;
    }
}
