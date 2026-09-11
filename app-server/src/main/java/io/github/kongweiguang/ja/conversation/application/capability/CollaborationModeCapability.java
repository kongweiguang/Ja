// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.capability;

import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.policy.PlanToolPolicy;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;

import java.util.List;

/** 协作说明从请求冻结的模式派生，不通过用户文本标记授予写入权限或创建计划。 */
public final class CollaborationModeCapability implements AgentCapability {
    private static final String PLANNING = """
            You are planning, not implementing. First inspect the actual workspace using the available
            controlled read-only tools. Do not ask the user for facts you can discover. Then clarify
            only decisions that materially change the scope, behavior, constraints or acceptance.
            Use request_user_input for those decisions when available; do not repeat answered questions.
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
            never bypasses tool permissions, required approval or explicit Plan execution authorization.
            """;

    /** 固定身份让说明与其余内建能力在同一请求中确定排序。 */
    @Override public String id() { return "builtin.collaboration_mode"; }

    /** 模式边界先于具体 Plan 工具身份说明，避免模型将目录存在误认为执行授权。 */
    @Override public int order() { return 10; }

    /** 内部执行来源使用执行上下文，不能被 Thread 保留的 Plan 偏好误导回规划。 */
    @Override public Prepared prepare(Request request) {
        if (PlanToolPolicy.isReadOnlyPlanning(
                request.origin(), request.preferences().collaborationMode())) {
            return new Prepared(PLANNING, List.of());
        }
        return request.origin() == TurnOrigin.PLAN_EXECUTION || request.clarificationEnabled() ? Prepared.empty()
                : new Prepared(NO_CLARIFICATION, List.of());
    }
}
