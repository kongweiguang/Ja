// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson;
import io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer;
import io.github.kongweiguang.ja.plan.domain.PlanModels.AcceptanceCriterion;
import io.github.kongweiguang.ja.plan.domain.PlanModels.CompletionSnapshot;
import io.github.kongweiguang.ja.plan.domain.PlanModels.EvaluationVerdict;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanDefinition;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStep;
import io.github.kongweiguang.ja.plan.domain.PlanModels.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖 Plan DAG、canonical hash、完成门与步骤依赖推进策略。 */
final class PlanPolicyTest {
    /** 同一结构化定义必须生成稳定 canonical JSON/hash，字段变化必须改变 hash。 */
    @Test
    void producesStableCanonicalHash() {
        CanonicalPlanJson canonical = new CanonicalPlanJson(new ObjectMapper());
        PlanCanonicalizer.Encoded first = canonical.encode(definition("目标"));
        PlanCanonicalizer.Encoded replay = canonical.encode(definition("目标"));
        PlanCanonicalizer.Encoded changed = canonical.encode(definition("新目标"));

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

    /** Plan 步骤只能在依赖收口后进入 READY，终态不能被重试路径绕过。 */
    @Test
    void readiesPlanStepsOnlyAfterDependenciesSucceed() {
        PlanStep dependent = new PlanStep("step_dependent", "后续", "后续工作", true, List.of("step_setup"));

        assertFalse(PlanPolicy.canBecomeReady(dependent, Map.of("step_setup", StepStatus.RUNNING)));
        assertTrue(PlanPolicy.canBecomeReady(dependent, Map.of("step_setup", StepStatus.SUCCEEDED)));
        assertTrue(PlanPolicy.mayTransition(StepStatus.FAILED, StepStatus.READY));
        assertFalse(PlanPolicy.mayTransition(StepStatus.SUCCEEDED, StepStatus.RUNNING));
    }

    /** 生产测试共用一条必要步骤和一条必要验收。 */
    private static PlanDefinition definition(String objective) {
        return new PlanDefinition(objective, List.of("范围"), List.of("非目标"), List.of("约束"),
                List.of("依赖"), List.of(new PlanStep("step_work", "实施", "完成实施", true, List.of())),
                List.of(new AcceptanceCriterion("criterion_done", "验证完成", true)),
                List.of("风险"), List.of("运行测试"));
    }
}




