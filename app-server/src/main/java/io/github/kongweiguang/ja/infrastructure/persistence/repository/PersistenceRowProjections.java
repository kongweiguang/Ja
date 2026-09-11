// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;

import java.time.Instant;
import java.util.Objects;

/**
 * 集中恢复由多个仓储共同消费的持久化行值，避免同一 SQLite 事实在不同读取入口产生语义漂移。
 */
public final class PersistenceRowProjections {
    /** 纯投影器不允许实例化，确保它不持有 session、mapper 或可变事务状态。 */
    private PersistenceRowProjections() {
    }

    /**
     * 从 Thread 行恢复完整下一轮偏好；首版 schema 的必需列缺失即属于存储损坏。
     */
    public static ThreadPreferences threadPreferences(PersistenceRecords.ThreadRow row) {
        Objects.requireNonNull(row, "row");
        return new ThreadPreferences(requiredText(row.providerId(), "provider_id"),
                requiredText(row.modelId(), "model_id"), row.reasoningLevel(),
                AccessMode.valueOf(requiredText(row.accessMode(), "access_mode")),
                CollaborationMode.valueOf(requiredText(row.collaborationMode(), "collaboration_mode")),
                titleSource(requiredText(row.titleSource(), "title_source")));
    }

    /**
     * 所有 Thread 读取入口共享同一摘要投影，避免主会话与 Child 会话对损坏行或状态枚举采取不同策略。
     */
    public static ThreadSummary threadSummary(PersistenceRecords.ThreadRow row) {
        Objects.requireNonNull(row, "row");
        ThreadSummary.Status status = row.archivedAt() == null
                ? ThreadSummary.Status.ACTIVE : ThreadSummary.Status.ARCHIVED;
        return new ThreadSummary(requiredText(row.threadId(), "thread_id"),
                requiredText(row.workspaceId(), "workspace_id"), requiredText(row.title(), "title"),
                threadPreferences(row), status, row.pinnedAt() != null,
                row.latestTurnStatus() == null ? null
                        : TurnState.valueOf(requiredText(row.latestTurnStatus(), "latest_turn_status")),
                row.latestTurnSeen(), row.activeGoalId(), row.revision(),
                Instant.parse(requiredText(row.createdAt(), "created_at")),
                Instant.parse(requiredText(row.updatedAt(), "updated_at")));
    }

    /** SQLite 与产品共享唯一闭集，未知标题来源必须作为损坏状态失败关闭。 */
    private static ThreadPreferences.TitleSource titleSource(String stored) {
        try {
            return ThreadPreferences.TitleSource.valueOf(stored);
        } catch (IllegalArgumentException invalidSource) {
            throw invalid("invalid thread title source");
        }
    }

    /** 公共行投影只接受非空必需文本，避免两个仓储形成不同的损坏恢复策略。 */
    private static String requiredText(String value, String column) {
        if (value == null || value.isBlank()) throw invalid("missing " + column);
        return value;
    }

    /** 持久化损坏错误固定脱敏，不携带行内容、路径或 Secret。 */
    private static StorageException invalid(String message) {
        return new StorageException(StorageException.Code.INVALID_STATE, message);
    }
}
