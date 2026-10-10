// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;

import java.time.Instant;
import java.util.Objects;

/**
 * 集中恢复由多个仓储共同消费的持久化行值，避免同一 SQLite 事实在不同读取入口产生语义漂移。
 */
public final class PersistenceRowProjections {
    /**
     * 纯投影器不允许实例化，确保它不持有 session、mapper 或可变事务状态。
     */
    private PersistenceRowProjections() {
    }

    /**
     * 从 Thread 行恢复完整下一轮偏好；首版 schema 的必需列缺失即属于存储损坏。
     */
    public static ThreadPreferences threadPreferences(PersistenceRecords.ThreadRow row) {
        Objects.requireNonNull(row, "row");
        return new ThreadPreferences(requiredText(row.providerId(), "provider_id"),
                requiredText(row.modelId(), "model_id"), row.reasoningLevel(),
                storedEnum(AccessMode.class, row.accessMode(), "access_mode", "access mode"),
                storedEnum(CollaborationMode.class, row.collaborationMode(), "collaboration_mode", "collaboration mode"),
                storedEnum(ThreadPreferences.TitleSource.class, row.titleSource(), "title_source", "thread title source"));
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
                requiredText(row.workspaceKind(), "workspace_kind").toLowerCase(java.util.Locale.ROOT),
                threadPreferences(row), status, row.pinnedAt() != null,
                row.latestTurnStatus() == null ? null
                        : storedEnum(TurnState.class, row.latestTurnStatus(), "latest_turn_status", "turn state"),
                row.latestTurnSeen(), row.revision(),
                Instant.parse(requiredText(row.createdAt(), "created_at")),
                Instant.parse(requiredText(row.updatedAt(), "updated_at")), row.latestTurnId());
    }

    /**
     * 持久枚举是闭集，未知值统一作为损坏状态失败关闭；不保留 Enum.valueOf 的原始文本或 cause，
     * 避免数据库垃圾值进入上层诊断，同时不猜测默认状态。
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static <E extends Enum<E>> E storedEnum(Class<E> enumType, String value, String column, String label) {
        String stored = requiredText(value, column);
        try {
            return Enum.valueOf(enumType, stored);
        } catch (IllegalArgumentException invalidValue) {
            throw invalid("invalid " + label);
        }
    }

    /**
     * 公共行投影只接受非空必需文本，避免两个仓储形成不同的损坏恢复策略。
     */
    private static String requiredText(String value, String column) {
        if (value == null || value.isBlank()) {
            throw invalid("missing " + column);
        }
        return value;
    }

    /**
     * 持久化损坏错误固定脱敏，不携带行内容、路径或 Secret。
     */
    private static StorageException invalid(String message) {
        return new StorageException(StorageException.Code.INVALID_STATE, message);
    }

}
