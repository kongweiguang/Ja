// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;

import java.util.Objects;

/**
 * 发布 Thread 元数据提交后的最小权威事实，使 UI 无需轮询或猜测异步标题完成时间。
 */
public record ThreadMetadataEvent(String threadId, String workspaceId, long revision,
                                  String title, ThreadPreferences.TitleSource titleSource) {
    /**
     * 事件刻意排除模型提示、响应、端点和凭据，只携带列表投影确定刷新所需字段。
     */
    public ThreadMetadataEvent {
        threadId = identifier(threadId, "thr_", "threadId");
        workspaceId = identifier(workspaceId, "ws_", "workspaceId");
        if (revision < 0) throw new IllegalArgumentException("invalid thread revision");
        if (title == null || title.isBlank() || title.length() > 512 || title.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid thread title");
        }
        Objects.requireNonNull(titleSource, "titleSource");
    }

    /** Thread 与 Workspace 沿用当前存储基线的稳定身份词汇。 */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
