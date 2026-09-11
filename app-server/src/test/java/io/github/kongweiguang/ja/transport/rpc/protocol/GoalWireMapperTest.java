// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证 Goal 查询与事件共享的严格 JA-RPC 投影。 */
final class GoalWireMapperTest {
    private static final Instant NOW = Instant.parse("2026-09-04T08:00:00Z");
    private final GoalWireMapper wire = new GoalWireMapper(new ObjectMapper());

    /** Goal 只公开冻结 link，Plan 的完整 revision 必须经独立聚合查询，避免两个 CAS 边界混合。 */
    @Test
    void projectsLinkedGoalAndFrozenPlanAsSeparateAggregates() {
        GoalModels.GoalSnapshot goalSnapshot = snapshot(GoalModels.GoalPhase.WORKING);
        GoalModels.PlanSnapshot planSnapshot = planSnapshot();

        ObjectNode goalResult = wire.snapshot(goalSnapshot);
        ObjectNode planResult = wire.planSnapshot(planSnapshot);

        assertEquals("planrev_test", goalResult.path("goal").path("planLink")
                .path("planRevisionId").asText());
        assertEquals("a".repeat(64), goalResult.path("goal").path("planLink").path("planHash").asText());
        assertEquals("working", goalResult.path("goal").path("phase").asText());
        assertFalse(goalResult.has("plan"));
        assertEquals("plan_test", planResult.path("plan").path("planId").asText());
        assertEquals("planrev_test", planResult.path("currentRevision")
                .path("planRevisionId").asText());
    }

    /** Goal 只保留等待阶段，问题与回答由公共 Interaction 读取，避免双重权威。 */
    @Test
    void projectsWaitingInputWithoutInteractionContent() {
        ObjectNode result = wire.snapshot(snapshot(GoalModels.GoalPhase.WAITING_INPUT));
        assertEquals("waiting_input", result.path("goal").path("phase").asText());
        assertFalse(result.path("goal").has("pendingInput"));
    }

    /** Activity 的 stepId 只在当前步骤可证明时出现，状态使用公开小写词汇。 */
    @Test
    void projectsStepActivityFromCommittedSnapshot() {
        GoalModels.GoalSnapshot snapshot = snapshot(GoalModels.GoalPhase.WORKING);
        GoalModels.PublicEvent event = new GoalModels.PublicEvent(
                9, 3, "step_changed", "正在验证合同", NOW);

        ObjectNode result = wire.activity(snapshot, event);

        assertEquals("step", result.path("activity").path("kind").asText());
        assertEquals("working", result.path("activity").path("status").asText());
        assertEquals("step_test", result.path("activity").path("stepId").asText());
        assertEquals(9, result.path("eventSequence").asLong());
    }

    /** 构造冻结 Goal definition 与版本 link，问答正文不属于 Goal 投影。 */
    private static GoalModels.GoalSnapshot snapshot(GoalModels.GoalPhase phase) {
        GoalModels.AcceptanceCriterion criterion = new GoalModels.AcceptanceCriterion(
                "criterion_test", "合同 Gate 通过", true);
        GoalModels.Goal goal = new GoalModels.Goal("goal_test", "thr_test", GoalModels.OwnerKind.ROOT_THREAD,
                "交付 Plan Goal", 1, GoalModels.GoalStatus.ACTIVE, phase, 3, "run_test",
                0, 0, null, false, NOW, NOW);
        GoalModels.GoalDefinition definition = new GoalModels.GoalDefinition(
                "goal_test", 1, "交付 Plan Goal", List.of(criterion), NOW);
        GoalModels.GoalPlanLink link = new GoalModels.GoalPlanLink(
                "goal_test", "plan_test", "planrev_test", "a".repeat(64), 1, NOW);
        return new GoalModels.GoalSnapshot(goal, definition, link, "step_test",
                0, 1, null, null, null, null, 9);
    }

    /** 构造独立 Plan 投影，证明冻结 revision、批准与步骤执行不再嵌入 Goal 响应。 */
    private static GoalModels.PlanSnapshot planSnapshot() {
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("交付 Plan Goal", List.of("协议"),
                List.of(), List.of("不发布"), List.of(),
                List.of(new GoalModels.PlanStep("step_test", "实现合同", "同步三端合同", true, List.of())),
                List.of(new GoalModels.AcceptanceCriterion(
                        "criterion_test", "合同 Gate 通过", true)), List.of(), List.of("运行合同 Gate"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_test", "plan_test", 1,
                definition, "{}", "a".repeat(64), "USER_UI", NOW);
        GoalModels.Plan plan = new GoalModels.Plan("plan_test", "thr_test", "交付 Plan Goal",
                GoalModels.PlanStatus.EXECUTING, 3, revision.planRevisionId(), "run_test", NOW, NOW);
        GoalModels.PlanApproval approval = new GoalModels.PlanApproval(
                "approval_test", plan.planId(), revision.planRevisionId(), revision.planHash(), NOW);
        GoalModels.StepExecution execution = new GoalModels.StepExecution("step_test", "run_test",
                GoalModels.StepStatus.RUNNING, 1, null, null, NOW, null);
        return new GoalModels.PlanSnapshot(plan, null, revision, approval, List.of(execution), 9);
    }
}
