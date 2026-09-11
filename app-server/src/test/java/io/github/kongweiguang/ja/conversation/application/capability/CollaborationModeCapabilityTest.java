// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.capability;

import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模式说明必须来自原生身份，避免执行 Turn 被保留的 Plan 偏好重新锁回规划。 */
class CollaborationModeCapabilityTest {
    /** 规划约束与可发现事实优先调研同时生效，不能仅靠隐藏按钮约束模型。 */
    @Test void planningUsesExplorationBeforeQuestionsAndRequiresExplicitExecution() {
        String prompt = new CollaborationModeCapability().prepare(request(CollaborationMode.PLAN, TurnOrigin.USER, false)).promptFragment();
        assertTrue(prompt.contains("First inspect"));
        assertTrue(prompt.contains("clicks Execute"));
        assertTrue(prompt.contains("plan_propose"));
    }

    /** 已授权的执行来源不继承规划提示，也不能因普通模式偏好关闭必需的决策补齐。 */
    @Test void executionDoesNotReenterPlanningAndRespectsQuestionPreference() {
        String prompt = new CollaborationModeCapability().prepare(request(CollaborationMode.PLAN, TurnOrigin.PLAN_EXECUTION, false)).promptFragment();
        assertFalse(prompt.contains("You are planning"));
        assertTrue(prompt.isEmpty());
    }

    /** 即使 Thread 保留 DEFAULT 模式，Plan-owned 执行也必须保留 request_user_input 能力。 */
    @Test void planExecutionCanClarifyWithDefaultMode() {
        String prompt = new CollaborationModeCapability().prepare(
                request(CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION, false)).promptFragment();
        assertTrue(prompt.isEmpty());
    }

    /** Goal 续跑不能因保留 PLAN 偏好重新得到规划提示，否则执行目录会与模型说明矛盾。 */
    @Test void goalContinuationDoesNotReenterPlanning() {
        String prompt = new CollaborationModeCapability().prepare(
                request(CollaborationMode.PLAN, TurnOrigin.GOAL_CONTINUATION, true)).promptFragment();
        assertFalse(prompt.contains("You are planning"));
        assertTrue(prompt.isEmpty());
    }

    /** 测试只构造不可变请求，不创建工作区、Provider 或持久状态。 */
    private static AgentCapability.Request request(CollaborationMode mode, TurnOrigin origin, boolean clarification) {
        return new AgentCapability.Request("thr_plan_prompt", "turn_plan_prompt", Path.of(".").toAbsolutePath(),
                "ws_plan_prompt", new ThreadPreferences("provider_plan_prompt", "model_plan_prompt", null, AccessMode.FULL_ACCESS,
                mode, ThreadPreferences.TitleSource.MANUAL), "cfg_" + "a".repeat(64), clarification,
                Instant.parse("2026-09-10T12:00:00Z"), origin);
    }
}
