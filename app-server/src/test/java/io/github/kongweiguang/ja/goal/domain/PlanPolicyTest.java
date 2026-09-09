// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.goal.domain.GoalModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.goal.domain.GoalModels.CompletionSnapshot;
import io.github.kongweiguang.ja.goal.domain.GoalModels.EvaluationVerdict;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanDefinition;
import io.github.kongweiguang.ja.goal.domain.GoalModels.PlanStep;
import io.github.kongweiguang.ja.goal.domain.GoalModels.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖 Plan DAG、canonical hash、完成门和三次无进展熔断。 */
final class PlanPolicyTest {
    /** 同一结构化定义必须生成稳定 canonical JSON/hash，字段变化必须改变 hash。 */
    @Test
    void producesStableCanonicalHash() {
        CanonicalPlanJson canonical = new CanonicalPlanJson(new ObjectMapper());
        CanonicalPlanJson.Encoded first = canonical.encode(definition("目标"));
        CanonicalPlanJson.Encoded replay = canonical.encode(definition("目标"));
        CanonicalPlanJson.Encoded changed = canonical.encode(definition("新目标"));

        assertEquals(first, replay);
        assertFalse(first.sha256().equals(changed.sha256()));
        assertTrue(first.json().contains("\"dependencies\""));
        assertTrue(first.json().contains("\"verificationStrategy\":["));
    }

    /** 未知依赖、自依赖和循环都会在 revision 冻结前被拒绝。 */
    @Test
    void rejectsInvalidDag() {
        PlanDefinition cycle = new PlanDefinition("目标", List.of("范围"), List.of(), List.of(), List.of(),
                List.of(new PlanStep("step_a", "A", "执行 A", true, List.of("step_b")),
                        new PlanStep("step_b", "B", "执行 B", true, List.of("step_a"))),
                List.of(new AcceptanceCriterion("criterion_done", "完成", true)), List.of(), List.of("测试"));
        assertThrows(IllegalArgumentException.class, () -> PlanPolicy.validate(cycle));
    }

    /** 全部步骤绿色仍不足以达成，必要证据和独立 MET 必须同时存在。 */
    @Test
    void requiresEvidenceAndIndependentEvaluator() {
        PlanDefinition definition = definition("目标");
        CompletionSnapshot withoutEvidence = new CompletionSnapshot(definition.steps(),
                definition.acceptanceCriteria(), List.of(StepStatus.SUCCEEDED), List.of(),
                false, false, false, false, EvaluationVerdict.MET);
        CompletionSnapshot complete = new CompletionSnapshot(definition.steps(),
                definition.acceptanceCriteria(), List.of(StepStatus.SUCCEEDED), List.of("criterion_done"),
                false, false, false, false, EvaluationVerdict.MET);
        assertFalse(PlanPolicy.isAchieved(withoutEvidence));
        assertTrue(PlanPolicy.isAchieved(complete));
    }

    /** 熔断只基于连续无进展，不把 token、轮次或耗时当成终止预算。 */
    @Test
    void pausesAfterThreeIdenticalNonProgressFailures() {
        assertFalse(GoalStateMachine.shouldPauseForRepeatedFailure("digest", 1, "digest", false));
        assertTrue(GoalStateMachine.shouldPauseForRepeatedFailure("digest", 2, "digest", false));
        assertFalse(GoalStateMachine.shouldPauseForRepeatedFailure("digest", 2, "digest", true));
        assertTrue(GoalStateMachine.shouldPauseForNoProgress(2, false));
        assertFalse(GoalStateMachine.shouldPauseForNoProgress(100, true));
        assertTrue(GoalStateMachine.mayTransition(
                GoalModels.GoalStatus.ACTIVE, GoalModels.GoalStatus.PAUSED));
    }

    /** 生产测试共用一条必要步骤和一条必要验收。 */
    private static PlanDefinition definition(String objective) {
        return new PlanDefinition(objective, List.of("范围"), List.of("非目标"), List.of("约束"),
                List.of("依赖"), List.of(new PlanStep("step_work", "实施", "完成实施", true, List.of())),
                List.of(new AcceptanceCriterion("criterion_done", "验证完成", true)),
                List.of("风险"), List.of("运行测试"));
    }
}
