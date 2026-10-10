// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;

import java.time.Instant;
import java.util.Objects;

/**
 * Ja 自有 Thread 首次创建投影，不把应用数据库写模型放回可复用 Agent Core。
 *
 * @param threadId 所属对话的稳定标识
 * @param workspaceId 工作区稳定标识
 * @param title 显示标题
 * @param preferences 偏好配置
 * @param createdAt 创建时间
 */
public record JaThreadDefinition(String threadId,
                                 String workspaceId,
                                 String title,
                                 ThreadPreferences preferences,
                                 Instant createdAt) {
    /**
     * 首次 Thread 投影固定由 Ja admission 提供完整偏好，禁止存储层补默认值。
     */
    public JaThreadDefinition {
        threadId = prefixed(threadId, "thr_", "threadId");
        workspaceId = prefixed(workspaceId, "ws_", "workspaceId");
        title = ContractChecks.text(title, "title", 4096, true);
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 只接受 Ja 当前稳定前缀，避免把任意外部 ID 写进本地关联键。
     */
    private static String prefixed(String value, String prefix, String field) {
        String checked = ContractChecks.identifier(value, field);
        if (!checked.startsWith(prefix)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return checked;
    }
}
