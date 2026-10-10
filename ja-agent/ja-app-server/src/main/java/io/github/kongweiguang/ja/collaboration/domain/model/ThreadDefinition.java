// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Collaboration 创建 Child Thread 所需的最小事实；conversation 适配器在出站边界补充其内部类型。
 *
 * @param threadId     新 Thread 标识
 * @param workspaceId  归属 Workspace 标识
 * @param title        初始显示标题
 * @param preferences  冻结的协作偏好
 * @param createdAt    创建时间
 */
public record ThreadDefinition(String threadId,
                               String workspaceId,
                               String title,
                               CollaborationPreferences preferences,
                               Instant createdAt) {
    /**
     * 创建事实必须完整，禁止存储层根据缺省偏好偷偷生成另一份 Thread 身份。
     */
    public ThreadDefinition {
        threadId = identifier(threadId, "thr_", "threadId");
        workspaceId = identifier(workspaceId, "ws_", "workspaceId");
        if (title == null || title.isBlank() || title.length() > 4096 || title.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid title");
        }
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 关联键只接受 Collaboration 当前使用的前缀，避免把外部协议别名持久化。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
                || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
