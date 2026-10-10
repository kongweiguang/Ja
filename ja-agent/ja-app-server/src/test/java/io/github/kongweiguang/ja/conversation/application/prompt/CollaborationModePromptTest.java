// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模式说明必须来自原生身份，避免执行 Turn 被保留的 Plan 偏好重新锁回规划。 */
class CollaborationModePromptTest {
    /** 规划约束与可发现事实优先调研同时生效，不能仅靠隐藏按钮约束模型。 */
    @Test void planningUsesExplorationBeforeQuestionsAndRequiresExplicitExecution() {
        String prompt = CollaborationModePrompt.forRequest(TurnOrigin.USER, CollaborationMode.PLAN, false);
        assertTrue(prompt.contains("First inspect"));
        assertTrue(prompt.contains("Treat clarification as a last resort"));
        assertTrue(prompt.contains("decide reversible implementation details"));
        assertTrue(prompt.contains("do not repeat answered questions"));
        assertTrue(prompt.contains("clicks Execute"));
        assertTrue(prompt.contains("plan_propose"));
    }

    /** 已授权的执行来源不继承规划提示，也不能因普通模式偏好关闭必需的决策补齐。 */
    @Test void executionDoesNotReenterPlanningAndRespectsQuestionPreference() {
        String prompt = CollaborationModePrompt.forRequest(TurnOrigin.PLAN_EXECUTION, CollaborationMode.PLAN, false);
        assertFalse(prompt.contains("You are planning"));
        assertTrue(prompt.isEmpty());
    }

    /** 即使 Thread 保留 DEFAULT 模式，Plan-owned 执行也必须保留 request_user_input 能力。 */
    @Test void planExecutionCanClarifyWithDefaultMode() {
        String prompt = CollaborationModePrompt.forRequest(TurnOrigin.PLAN_EXECUTION, CollaborationMode.DEFAULT, false);
        assertTrue(prompt.isEmpty());
    }

}
