// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.domain.model;

import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 Collaboration 领域值在脱离 conversation 类型后仍保持稳定边界。 */
final class CollaborationDomainModelsTest {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    /**
     * 领域值拒绝不带类型前缀的关联键，防止 ACL 把外部文本当作 Task 身份。
     */
    @Test
    void rejectsUntypedThreadIdentity() {
        CollaborationPreferences preferences = preferences();
        assertThrows(IllegalArgumentException.class,
                () -> new ThreadDefinition("thread_bad", "ws_test", "task", preferences, NOW));
    }

    /**
     * admission 保留 USER 角色和 attachment 上限，避免出站转换放宽 Core 事实。
     */
    @Test
    void admissionKeepsUserRoleAndAttachments() {
        ModelMessage message = new ModelMessage(ModelRole.USER, List.of(new TextContent("body")));
        TurnAdmission admission = new TurnAdmission("thr_task", "turn_task", "item_user", message,
                List.of("att_one"), 3, NOW, ready());
        assertEquals("thr_task", admission.threadId());
        assertEquals(List.of("att_one"), admission.attachmentIds());
        assertThrows(IllegalArgumentException.class,
                () -> new TurnAdmission("thr_task", "turn_task", "item_user",
                        new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("body"))), List.of(), 3, NOW,
                        ready()));
    }

    /**
     * 标题所有权替换只创建新值，不修改此前冻结的协作偏好。
     */
    @Test
    void titleSourceReplacementPreservesExecutionSelection() {
        CollaborationPreferences original = preferences();
        CollaborationPreferences manual = original.withTitleSource(CollaborationPreferences.TitleSource.MANUAL);
        assertEquals(original.providerId(), manual.providerId());
        assertEquals(CollaborationPreferences.TitleSource.MANUAL, manual.titleSource());
    }

    /** 生成测试用的稳定偏好快照。 */
    private static CollaborationPreferences preferences() {
        return new CollaborationPreferences("provider_test", "model_test", "medium",
                CollaborationPreferences.AccessMode.FULL_ACCESS,
                CollaborationPreferences.CollaborationMode.DEFAULT,
                CollaborationPreferences.TitleSource.PLACEHOLDER);
    }

    /** 构造最小合法 READY 游标，测试只关心 admission 的角色与附件边界。 */
    private static TurnExecutionState.Ready ready() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
    }
}

