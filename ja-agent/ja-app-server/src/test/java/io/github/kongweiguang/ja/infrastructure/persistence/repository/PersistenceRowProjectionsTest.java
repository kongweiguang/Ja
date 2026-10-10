// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 错误持久化枚举必须归一到脱敏的状态损坏结果，不把原始行值带入诊断。 */
final class PersistenceRowProjectionsTest {
    private static final String SENTINEL = "PERSISTED_ENUM_SENTINEL_7F3A";

    /** Access、Collaboration 与 Turn 状态均为闭集，损坏值不得变成默认权限或被异常文本回显。 */
    @Test
    void rejectsUnknownPersistedEnumsWithoutLeakingTheirValues() {
        assertRedactedInvalid(() -> PersistenceRowProjections.threadPreferences(row(SENTINEL, "DEFAULT", null)));
        assertRedactedInvalid(() -> PersistenceRowProjections.threadPreferences(row("APPROVAL_REQUIRED", SENTINEL, null)));
        assertRedactedInvalid(() -> PersistenceRowProjections.threadSummary(row("APPROVAL_REQUIRED", "DEFAULT", SENTINEL)));
        assertRedactedInvalid(() -> PersistenceRowProjections.threadPreferences(row("APPROVAL_REQUIRED", "DEFAULT", null,
                "PLACEHOLDER_" + SENTINEL)));
    }

    /** 所有必需 Thread 列合法，只替換指定枚舉，確保失败来自对应解析路径。 */
    private static PersistenceRecords.ThreadRow row(String accessMode, String collaborationMode,
                                                    String turnStatus) {
        return row(accessMode, collaborationMode, turnStatus, "PLACEHOLDER");
    }

    /** 标题来源也复用同一闭集解码器，避免留下可回显原始 cause 的旧分支。 */
    private static PersistenceRecords.ThreadRow row(String accessMode, String collaborationMode,
                                                    String turnStatus, String titleSource) {
        return new PersistenceRecords.ThreadRow("thr_projection", "user_local", "ws_projection", "title", "project", "provider_projection", "model_projection", null, accessMode, collaborationMode, titleSource, 0, "2026-09-28T00:00:00Z", "2026-09-28T00:00:00Z", null, null, null, turnStatus, false, null);
    }

    /** 验证稳定 code 与完整 cause 链均不暴露 sentinel 持久值。 */
    private static void assertRedactedInvalid(Runnable projection) {
        StorageException failure = assertThrows(StorageException.class, projection::run);
        assertEquals(StorageException.Code.INVALID_STATE, failure.code());
        for (Throwable current = failure; current != null; current = current.getCause()) {
            assertFalse(String.valueOf(current.getMessage()).contains(SENTINEL));
        }
    }
}
