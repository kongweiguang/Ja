// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证独立 Plan 快照、实时变化与历史事件使用各自稳定的 JA-RPC 投影。 */
final class PlanWireMapperTest {
    private static final Instant NOW = Instant.parse("2026-09-04T08:00:00Z");
    private final PlanWireMapper wire = new PlanWireMapper(new ObjectMapper());

    /** Plan 响应只映射 Plan 自身 revision 与执行状态，不再嵌入其它聚合字段。 */
    @Test
    void projectsIndependentPlanSnapshot() {
        ObjectNode result = wire.planSnapshot(planSnapshot());

        assertEquals("plan_test", result.path("plan").path("planId").asText());
        assertEquals("thread", result.path("plan").path("owner").path("kind").asText());
        assertEquals("thr_test", result.path("plan").path("owner").path("threadId").asText());
        assertEquals("planrev_test", result.path("currentRevision").path("planRevisionId").asText());
        assertEquals("approval_test", result.path("approval").path("approvalId").asText());
        assertEquals(9, result.path("eventSequence").asLong());
        assertFalse(result.has("goal"));
        assertFalse(result.path("plan").has("goalId"));
    }

    /** 实时变化只携带 Plan 行与有界进度，避免跨聚合身份或私有执行明细泄漏。 */
    @Test
    void projectsPlanChangeWithBoundedProgress() {
        PlanModels.Plan plan = planSnapshot().plan();
        PlanModels.PublicEvent activity = new PlanModels.PublicEvent(
                10, plan.revision(), "step_changed", "正在验证合同", NOW);
        PlanEvent event = new PlanEvent(plan, 10, activity,
                new PlanModels.PlanProgress("step_test", "实现合同", 2, 3));

        ObjectNode result = wire.planChanged(event);

        assertEquals("thr_test", result.path("ownerThreadId").asText());
        assertEquals("plan_test", result.path("planId").asText());
        assertEquals(10, result.path("eventSequence").asLong());
        assertEquals("step_test", result.path("progress").path("currentStepId").asText());
        assertEquals(2, result.path("progress").path("completedRequiredSteps").asInt());
        assertFalse(result.has("goal"));
    }

    /** 历史页保留 aggregate 水位与 opaque cursor，条目只公开可审计摘要。 */
    @Test
    void projectsPlanEventPage() {
        PlanModels.PublicEvent activity = new PlanModels.PublicEvent(
                10, 3, "step_changed", "正在验证合同", NOW);
        PlanModels.Page<PlanModels.PublicEvent> page = new PlanModels.Page<>(
                "plan_test", 3, 10, List.of(activity), "events_after_10");

        ObjectNode result = wire.planEvents(page);

        assertEquals("plan_test", result.path("planId").asText());
        assertEquals(3, result.path("planRevision").asLong());
        assertEquals(10, result.path("eventSequence").asLong());
        assertEquals("step_changed", result.path("items").get(0).path("kind").asText());
        assertEquals("正在验证合同", result.path("items").get(0).path("summary").asText());
        assertEquals("events_after_10", result.path("nextCursor").asText());
    }

    /** 独立 Plan 快照绑定冻结 revision、批准与步骤执行，避免 fixture 混入历史 Goal 结构。 */
    private static PlanModels.PlanSnapshot planSnapshot() {
        PlanModels.PlanDefinition definition = new PlanModels.PlanDefinition("交付 Plan", List.of("协议"),
                List.of(), List.of("不发布"), List.of(),
                List.of(new PlanModels.PlanStep("step_test", "实现合同", "同步三端合同", true, List.of())),
                List.of(new PlanModels.AcceptanceCriterion(
                        "criterion_test", "合同 Gate 通过", true)), List.of(), List.of("运行合同 Gate"));
        PlanModels.PlanRevision revision = new PlanModels.PlanRevision("planrev_test", "plan_test", 1,
                definition, "{}", "a".repeat(64), "USER_UI", NOW);
        PlanModels.Plan plan = new PlanModels.Plan("plan_test", "thr_test", "交付 Plan",
                PlanModels.PlanStatus.EXECUTING, 3, revision.planRevisionId(), "run_test", NOW, NOW);
        PlanModels.PlanApproval approval = new PlanModels.PlanApproval(
                "approval_test", plan.planId(), revision.planRevisionId(), revision.planHash(), NOW);
        PlanModels.StepExecution execution = new PlanModels.StepExecution("step_test", "run_test",
                PlanModels.StepStatus.RUNNING, 1, null, null, NOW, null);
        return new PlanModels.PlanSnapshot(plan, null, revision, approval, List.of(execution), 9);
    }
}
