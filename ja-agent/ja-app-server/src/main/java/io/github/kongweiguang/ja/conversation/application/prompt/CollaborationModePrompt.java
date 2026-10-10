// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.conversation.application.policy.PlanToolPolicy;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;

import java.util.Objects;

/**
 * 协作说明从请求冻结的模式派生，不通过用户文本标记授予写入权限或创建计划。
 */
public final class CollaborationModePrompt {
    private static final String PLANNING = """
            You are planning, not implementing. First inspect the actual workspace using the available
            controlled read-only ja_tool_call. Work autonomously on the plan: discover facts, follow existing
            architecture and conventions, and decide reversible implementation details using reasonable
            project-consistent assumptions. Treat clarification as a last resort. Ask only when a missing
            user-owned decision cannot be discovered, inferred or safely defaulted, blocks a decision-complete
            plan, and the alternatives materially change scope, behavior, constraints or acceptance. Consolidate
            the minimum unanswered decisions into one request_user_input call; do not repeat answered questions
            or ask permission to keep planning.
            Finally produce a decision-complete structured Plan with objective, scope, non-goals,
            constraints, ordered dependent steps, risks, acceptance criteria and verification strategy.
            Save draft changes with the native Plan draft tool and freeze the final revision with
            plan_propose. Until the user clicks Execute, do not modify workspace files or external
            systems, execute arbitrary shell commands, or delegate implementation. Conversation text,
            switching modes and answering questions do not authorize execution. Explain assumptions
            and unresolved decisions honestly; never pretend a tool or test was run.
            """;
    private static final String NO_CLARIFICATION = """
            Clarification questions are disabled by the user for this mode. Do not ask optional
            preference questions in cards or ordinary chat. Proceed using reasonable, clearly stated
            assumptions where the task permits. If essential information is missing, explain the
            concrete blocker and stop dependent work without inventing an answer. This preference
            never bypasses tool permissions, is_required approval or explicit Plan execution authorization.
            """;

    /**
     * 按本次已解析来源和偏好选择说明；执行来源不能被 Thread 保留的 PLAN 偏好误导回规划。
     */
    public static String forRequest(TurnOrigin origin, CollaborationMode mode, boolean clarificationEnabled) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(mode, "mode");
        if (PlanToolPolicy.isReadOnlyPlanning(origin, mode)) {
            return PLANNING;
        }
        return origin == TurnOrigin.PLAN_EXECUTION || clarificationEnabled ? "" : NO_CLARIFICATION;
    }
}
