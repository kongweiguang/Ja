// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;

import java.util.Objects;

/**
 * 集中恢复由多个仓储共同消费的持久化行值，避免同一 SQLite 事实在不同读取入口产生语义漂移。
 */
final class PersistenceRowProjections {
    /** 纯投影器不允许实例化，确保它不持有 session、mapper 或可变事务状态。 */
    private PersistenceRowProjections() {
    }

    /**
     * 从 Thread 行恢复下一轮偏好；整组列均缺失表示旧记录没有偏好，其余情况必须完整且闭集。
     */
    static ThreadPreferences threadPreferences(PersistenceRecords.ThreadRow row) {
        Objects.requireNonNull(row, "row");
        if (row.providerId() == null && row.modelId() == null && row.accessMode() == null
            && row.titleSource() == null) return null;
        return new ThreadPreferences(requiredText(row.providerId(), "provider_id"),
                requiredText(row.modelId(), "model_id"), row.reasoningLevel(),
                AccessMode.valueOf(requiredText(row.accessMode(), "access_mode")),
                titleSource(requiredText(row.titleSource(), "title_source")));
    }

    /**
     * 从 Turn 行恢复冻结运行快照；base URI 与 Secret 从未持久化，因此这里只接纳公开选择事实。
     */
    static TurnRuntimeSnapshot turnRuntime(PersistenceRecords.TurnRow row) {
        Objects.requireNonNull(row, "row");
        if (row.providerId() == null && row.modelId() == null && row.provider() == null
            && row.api() == null && row.upstreamModel() == null && row.accessMode() == null) return null;
        return new TurnRuntimeSnapshot(requiredText(row.providerId(), "provider_id"),
                requiredText(row.modelId(), "model_id"), requiredText(row.provider(), "provider"),
                requiredText(row.api(), "api"), requiredText(row.upstreamModel(), "upstream_model"),
                row.reasoningLevel(), AccessMode.valueOf(requiredText(row.accessMode(), "access_mode")),
                requiredText(row.configGeneration(), "config_generation"));
    }

    /** SQLite 事实名与产品枚举显式转换，未知或旧别名必须作为损坏状态失败关闭。 */
    private static ThreadPreferences.TitleSource titleSource(String stored) {
        return switch (stored) {
            case "PLACEHOLDER" -> ThreadPreferences.TitleSource.PLACEHOLDER;
            case "AUTOMATIC" -> ThreadPreferences.TitleSource.AUTO;
            case "USER" -> ThreadPreferences.TitleSource.MANUAL;
            default -> throw invalid("invalid thread title source");
        };
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
