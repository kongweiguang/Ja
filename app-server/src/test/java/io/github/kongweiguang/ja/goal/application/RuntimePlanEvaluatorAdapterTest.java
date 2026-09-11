// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.PlanEvaluatorPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Plan evaluator 不以步骤/证据存在替代独立模型验收，且严格绑定当前 revision/run。 */
final class RuntimePlanEvaluatorAdapterTest {
    /** 完整输入时模型 verdict 与逐 criterion 结论必须被保留到既有 Evaluation.summary。 */
    @Test
    void acceptsStrictCriteriaAndProjectsTheirVerdicts() throws Exception {
        GoalModels.AcceptanceCriterion criterion = new GoalModels.AcceptanceCriterion(
                "criterion_tests", "测试必须通过", true);
        String output = "{\"verdict\":\"met\",\"summary\":\"所有验证通过\","
                + "\"criteria\":[{\"criterionId\":\"criterion_tests\",\"verdict\":\"met\","
                + "\"reason\":\"报告中的测试全部通过\"}]}";

        PlanEvaluationSupport.Parsed parsed = PlanEvaluationSupport.decode(new ObjectMapper(), output,
                List.of(criterion), true, true);

        assertEquals(GoalModels.EvaluationVerdict.MET, parsed.verdict());
        assertEquals("criterion_tests", parsed.criteria().getFirst().criterionId());
        assertTrue(parsed.summary().contains("criterion_tests=met"));
    }

    /** 缺少必要证据时，模型不能用 met 覆盖事实门；严格解析直接拒绝该响应。 */
    @Test
    void rejectsMetWhenRequiredEvidenceIsMissing() {
        GoalModels.AcceptanceCriterion criterion = new GoalModels.AcceptanceCriterion(
                "criterion_tests", "测试必须通过", true);
        String output = "{\"verdict\":\"met\",\"summary\":\"看起来完成\","
                + "\"criteria\":[{\"criterionId\":\"criterion_tests\",\"verdict\":\"met\","
                + "\"reason\":\"模型声称通过\"}]}";

        assertThrows(IllegalArgumentException.class, () -> PlanEvaluationSupport.decode(new ObjectMapper(), output,
                List.of(criterion), false, true));
    }

    /** 未知字段、未知 criterion 或缺失 criterion 都拒绝，防止宽松 JSON 伪造完成门。 */
    @Test
    void rejectsNonExactEvaluatorShape() {
        GoalModels.AcceptanceCriterion criterion = new GoalModels.AcceptanceCriterion(
                "criterion_tests", "测试必须通过", true);
        String output = "{\"verdict\":\"met\",\"summary\":\"ok\",\"extra\":true,"
                + "\"criteria\":[{\"criterionId\":\"criterion_tests\",\"verdict\":\"met\","
                + "\"reason\":\"ok\"}]}";

        assertThrows(IllegalArgumentException.class, () -> PlanEvaluationSupport.decode(new ObjectMapper(), output,
                List.of(criterion), true, true));
    }

    /** evidence 绑定使用当前 Plan、active Run 和 frozen revision，历史证据不能混入模型输入。 */
    @Test
    void preparesOnlyCurrentPlanIdentity() {
        GoalModels.PlanStep step = new GoalModels.PlanStep("step_tests", "运行测试", "", true, List.of());
        GoalModels.AcceptanceCriterion criterion = new GoalModels.AcceptanceCriterion(
                "criterion_tests", "测试必须通过", true);
        GoalModels.PlanDefinition definition = new GoalModels.PlanDefinition("完成测试", List.of(), List.of(),
                List.of(), List.of(), List.of(step), List.of(criterion), List.of(), List.of("运行测试并核对报告"));
        GoalModels.PlanRevision revision = new GoalModels.PlanRevision("planrev_test", "plan_test", 1,
                definition, "{\"objective\":\"完成测试\"}", "a".repeat(64), "AGENT", Instant.EPOCH);
        GoalModels.Plan plan = new GoalModels.Plan("plan_test", "thr_test", "完成测试",
                GoalModels.PlanStatus.VERIFYING, 3, "planrev_test", "run_test", Instant.EPOCH, Instant.EPOCH);
        GoalModels.StepExecution execution = new GoalModels.StepExecution("step_tests", "run_test",
                GoalModels.StepStatus.SUCCEEDED, 1, null, "通过", Instant.EPOCH, Instant.EPOCH);
        GoalModels.Evidence evidence = new GoalModels.Evidence("evidence_test", null, "plan_test", null,
                "run_test", "planrev_test", "criterion_tests", "step_tests",
                GoalModels.EvidenceSource.TEST_REPORT, "report_test", "测试通过", "b".repeat(64),
                Instant.EPOCH, Instant.EPOCH);
        GoalModels.PlanSnapshot snapshot = new GoalModels.PlanSnapshot(plan, null, revision, null,
                List.of(execution), 1);
        GoalModels.Evidence automatic = new GoalModels.Evidence("evidence_tool", null, "plan_test", null,
                "run_test", "planrev_test", null, null, GoalModels.EvidenceSource.TOOL_RESULT,
                "call_test", "原始工具结果", "c".repeat(64), Instant.EPOCH, Instant.EPOCH);

        PlanEvaluationSupport.Prepared prepared = PlanEvaluationSupport.prepare(new ObjectMapper(), snapshot,
                List.of(automatic, evidence));
        assertEquals(false, PlanEvaluationSupport.prepare(new ObjectMapper(), snapshot,
                List.of(automatic)).evidenceComplete());

        assertTrue(prepared.evidenceComplete());
        assertTrue(prepared.stepsComplete());
        assertTrue(prepared.json().contains("run_test"));
        assertEquals(64, prepared.digest().length());
    }

    /** 默认无 Tool evaluator 必须尊重 Coordinator 的取消，不得在暂停后继续完成门判断。 */
    @Test
    void deterministicEvaluatorHonorsCancellationContext() {
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel("plan_pause");
        PlanEvaluatorPort.EvaluationContext context = new PlanEvaluatorPort.EvaluationContext(
                cancellation, Duration.ofSeconds(5));

        assertThrows(CancellationException.class, () -> new DeterministicPlanEvaluator()
                .evaluate(null, List.of(), context).toCompletableFuture().join());
    }
}
