// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 表示一个 Thread 的权威元数据投影。
 */
public record ThreadSummary(String threadId, String workspaceId, String title,
                            ThreadPreferences preferences, Status status, long revision,
                            Instant createdAt, Instant updatedAt) {
    /**
     * 在离开应用层前校验身份、状态和时间，避免 Wire 层修复损坏数据。
     */
    public ThreadSummary {
        threadId = identifier(threadId, "thr_", 96);
        workspaceId = identifier(workspaceId, "ws_", 96);
        title = title(title);
        // v2 升级前的历史 Thread 没有可证明的 Provider/Model 映射；null 只表达这一只读事实。
        Objects.requireNonNull(status, "status");
        if (revision < 0) throw new IllegalArgumentException("invalid thread revision");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 创建 Thread 时冻结所属工作区、下一轮偏好和发生时间；标题初始来源必须是 placeholder。
     */
    public record Creation(String threadId, String workspaceId, String title, ThreadPreferences preferences,
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
     * 统一校验领域身份，不复用任何 JSON-RPC 参数解析器。
     */
    private static String identifier(String value, String prefix, int bodyMaximum) {
        if (value == null || value.length() > prefix.length() + bodyMaximum
            || !value.matches(java.util.regex.Pattern.quote(prefix) + "[A-Za-z0-9_-]{1," + bodyMaximum + "}")) {
            throw new IllegalArgumentException("invalid thread identity");
        }
        return value;
    }

    /** 标题允许自然语言文本，但拒绝 NUL、空白和无界内容。 */
    private static String title(String value) {
        value = Objects.requireNonNull(value, "title");
        if (value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid thread title");
        }
        return value;
    }
}
