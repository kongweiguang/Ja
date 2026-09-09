// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定协作模式与访问策略正交且随标题归属变化完整保留。 */
final class ThreadPreferencesTest {
    /** 标题异步提交只能改变来源，不能把计划模式悄然退回默认执行。 */
    @Test
    void preservesCollaborationModeWhenTitleOwnershipChanges() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", "high",
                AccessMode.FULL_ACCESS, CollaborationMode.PLAN, ThreadPreferences.TitleSource.PLACEHOLDER);

        ThreadPreferences renamed = preferences.withTitleSource(ThreadPreferences.TitleSource.MANUAL);

        assertEquals(CollaborationMode.PLAN, renamed.collaborationMode());
        assertEquals(AccessMode.FULL_ACCESS, renamed.accessMode());
    }

    /** 缺失协作模式不能默认成执行模式，否则旧调用方会绕过显式协议迁移。 */
    @Test
    void rejectsMissingCollaborationMode() {
        assertThrows(NullPointerException.class, () -> new ThreadPreferences(
                "provider_test", "model_test", null, AccessMode.APPROVAL_REQUIRED, null,
                ThreadPreferences.TitleSource.PLACEHOLDER));
    }
}
