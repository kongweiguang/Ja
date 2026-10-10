// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.interfaces.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolCatalogIdentity;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolRequestContext;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import io.github.kongweiguang.ja.agent.infrastructure.tool.JsonSchemaToolArgumentValidator;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.PlanReadOnlyToolCatalog;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Plan 能力在一个适配器内保持可见目录、CAS 身份与真实 UseCase 调用一致。 */
final class PlanToolFactoryTest {
    private static final Instant NOW = Instant.parse("2026-09-04T08:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-09-04T09:00:00Z");

    /** 物化后必须仍通过生产只读目录筛选，防止工厂可见集合正确而模型实际收不到提案入口。 */
    @Test
    void materializedPlanningToolsSurviveReadOnlyCatalog() {
        List<JaTool> tools = tools(fixture().factory(), CollaborationMode.PLAN, TurnOrigin.USER);

        assertEquals(List.of("plan_propose", "plan_draft_update"),
                PlanReadOnlyToolCatalog.filter(tools).stream().map(value -> value.spec().name()).toList());
        for (JaTool tool : tools) {
            assertEquals(tool.planAccess(), tool.planAccess(invocation(tool.spec().name())));
            assertEquals(JaTool.ApprovalRequirement.TRUSTED_INTERNAL, tool.approvalRequirement());
        }
        assertTrue(PlanReadOnlyToolCatalog.filter(tools(fixture().factory(),
                CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION)).isEmpty());
    }

    /** 真正执行 Schema 校验，确保缺少草稿身份或证据项的参数在持久派发前就被拒绝。 */
    @Test
    void rejectsIncompleteDraftAndEvidenceArgumentsBeforeDispatch() throws Exception {
        JsonSchemaToolArgumentValidator validator =
                new JsonSchemaToolArgumentValidator(JsonValueCodecFactory.standard());
        JaTool draft = tool(fixture().factory(), "plan_draft_update", CollaborationMode.PLAN, TurnOrigin.USER);
        JsonObject validDraft = (JsonObject) JacksonJsonValues.fromNode(new ObjectMapper().readTree("""
                {"planId":"plan_test","expectedPlanRevision":4,"expectedDraftRevision":0,
                 "definition":{"objective":"完成计划","scope":[],"nonGoals":[],"constraints":[],
                 "dependencies":[],"steps":[{"stepId":"step_1","title":"实现","description":"",
                 "required":true,"dependsOn":[]}],"acceptanceCriteria":[{"criterionId":"criterion_1",
                 "description":"通过测试","required":true}],"risks":[],"verificationStrategy":["unit"]},
                 "basedOnPlanRevisionId":null,"operationKey":"draft_test"}
                """));
        assertTrue(validator.invalidReason(draft.spec().inputSchema(), validDraft).isEmpty());
        assertTrue(validator.invalidReason(draft.spec().inputSchema(), JsonObject.empty()).isPresent());
        JaTool proposal = tool(fixture().factory(), "plan_propose", CollaborationMode.PLAN, TurnOrigin.USER);
        assertTrue(validator.invalidReason(proposal.spec().inputSchema(), JsonObjects.builder()
                .putText("planId", "plan_test").putNumber("expectedPlanRevision", 4)
                .put("definition", JsonObject.empty()).putText("operationKey", "proposal_test").build()).isPresent());

        JaTool step = tool(fixture().factory(), "plan_step_update",
                CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION);
        JsonObject incompleteEvidence = (JsonObject) JacksonJsonValues.fromNode(new ObjectMapper().readTree("""
                {"planId":"plan_test","expectedPlanRevision":4,"runId":"run_test","stepId":"step_1",
                 "expectedStatus":"pending","status":"succeeded","failureSignature":null,
                 "evidenceClaims":[{}],"operationKey":"step_test"}
                """));
        assertTrue(validator.invalidReason(step.spec().inputSchema(), incompleteEvidence).isPresent());
    }

    /** origin 决定 Tool 闭集，普通 Default Turn 即使存在 Plan 也不暴露内部能力。 */
    @Test
    void exposesToolsForExactTurnOrigin() {
        Fixture fixture = fixture();

        assertEquals(List.of("plan_propose", "plan_draft_update"),
                names(fixture.factory(), CollaborationMode.PLAN, TurnOrigin.USER));
        assertEquals(List.of(),
                names(fixture.factory(), CollaborationMode.DEFAULT, TurnOrigin.USER_CONTINUATION));
        assertEquals(List.of("plan_step_update"),
                names(fixture.factory(), CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION));
        assertEquals(List.of(), names(fixture.factory(), CollaborationMode.DEFAULT, TurnOrigin.USER));

        JaTool proposal = tool(fixture.factory(), "plan_propose", CollaborationMode.PLAN, TurnOrigin.USER);
        assertEquals(JaTool.PlanAccess.INTERNAL_MUTATION, proposal.planAccess(invocation("plan_propose")));
        JaTool stepUpdate = tool(fixture.factory(), "plan_step_update",
                CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION);
        assertEquals(JaTool.PlanAccess.DISALLOWED, stepUpdate.planAccess(invocation("plan_step_update")));
        JsonObject stepProperties = (JsonObject) stepUpdate.spec().inputSchema().get("properties");
        assertFalse(stepProperties.containsKey("target"));
        assertFalse(stepProperties.containsKey("goalId"));
        assertTrue(stepProperties.containsKey("planId"));
        JaTool draftUpdate = tool(fixture.factory(), "plan_draft_update",
                CollaborationMode.PLAN, TurnOrigin.USER);
        assertEquals(JaTool.PlanAccess.INTERNAL_MUTATION, draftUpdate.planAccess(invocation("plan_draft_update")));
        assertTrue(((io.github.kongweiguang.ja.agent.domain.json.JsonObject)
                draftUpdate.spec().inputSchema().get("properties")).containsKey("expectedDraftRevision"));
    }

    /** Plan Tool 必须与真实状态机对齐：无 Plan、执行中、验证中和终态均不得暴露编辑入口。 */
    @Test
    void filtersPlanMutationToolsByPersistedPlanStage() {
        assertEquals(List.of(), names(fixture(null).factory(), CollaborationMode.PLAN, TurnOrigin.USER));
        assertEquals(List.of("plan_propose", "plan_draft_update"),
                names(fixture(PlanModels.PlanStatus.DRAFT).factory(), CollaborationMode.PLAN, TurnOrigin.USER));
        assertEquals(List.of("plan_draft_update"),
                names(fixture(PlanModels.PlanStatus.AWAITING_APPROVAL).factory(),
                        CollaborationMode.PLAN, TurnOrigin.USER));
        assertEquals(List.of("plan_draft_update"),
                names(fixture(PlanModels.PlanStatus.APPROVED).factory(), CollaborationMode.PLAN, TurnOrigin.USER));
        assertEquals(List.of("plan_draft_update"),
                names(fixture(PlanModels.PlanStatus.PAUSED).factory(), CollaborationMode.PLAN, TurnOrigin.USER));
        for (PlanModels.PlanStatus status : List.of(PlanModels.PlanStatus.EXECUTING,
                PlanModels.PlanStatus.VERIFYING, PlanModels.PlanStatus.COMPLETED,
                PlanModels.PlanStatus.STOPPED)) {
            assertEquals(List.of(), names(fixture(status).factory(), CollaborationMode.PLAN, TurnOrigin.USER),
                    "unexpected Plan Tool exposure for " + status);
        }
    }

    /** origin 或持久状态只改变暴露集合，三个 Plan 安全定义必须保持完整且稳定。 */
    @Test
    void keepsCompleteDefinitionsWhenVisibilityChanges() {
        Fixture fixture = fixture();

        JaTool.Selection hidden = fixture.factory().toolsFor(
                request(CollaborationMode.DEFAULT, TurnOrigin.USER));
        JaTool.Selection planning = fixture.factory().toolsFor(
                request(CollaborationMode.PLAN, TurnOrigin.USER));

        assertEquals(Set.of(), hidden.visibleNames());
        assertEquals(List.of("plan_propose", "plan_draft_update"),
                planning.definitions().stream().map(value -> value.spec().name())
                        .filter(planning.visibleNames()::contains).toList());
        assertEquals(List.of("plan_propose", "plan_draft_update", "plan_step_update"),
                hidden.definitions().stream().map(value -> value.spec().name()).toList());
        assertEquals(hidden.definitions().stream().map(value -> value.spec().name()).toList(),
                planning.definitions().stream().map(value -> value.spec().name()).toList());
    }

    /** 动态提示仅跟随 Plan 绑定，普通继续对话不带独立计划提示。 */
    @Test
    void scopesInstructionsToBoundPlan() {
        String plan = fixture().factory().toolsFor(
                request(CollaborationMode.PLAN, TurnOrigin.USER)).promptFragment();
        assertTrue(plan.contains("Execute action"));
        assertFalse(plan.contains("goal_request_evaluation"));
        assertTrue(fixture().factory().toolsFor(
                request(CollaborationMode.DEFAULT, TurnOrigin.USER)).promptFragment().isEmpty());
    }

    /** deadline 刷新后仍生成待同事务应用的命令事实，且只携带 standalone Plan identity。
     *
     * @author kongweiguang
     */
    @Test
    void proposesStandalonePlanThroughFrozenIdentity() throws Exception {
        Fixture fixture = fixture();
        JaTool tool = tool(fixture.factory(), "plan_propose", CollaborationMode.PLAN, TurnOrigin.USER);
        assertFalse(tool.spec().inputSchema().containsKey("goalId"));
        JsonObject definition = (JsonObject) JacksonJsonValues.fromNode(new ObjectMapper().readTree("""
                {"objective":"完成计划","scope":["core"],"nonGoals":[],"constraints":[],
                 "dependencies":[],"steps":[{"stepId":"step_1","title":"实现","description":"",
                 "required":true,"dependsOn":[]}],"acceptanceCriteria":[{"criterionId":"criterion_1",
                 "description":"通过测试","required":true}],"risks":[],"verificationStrategy":["unit"]}
                """));
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_propose", "plan_propose",
                JsonObjects.builder().putText("planId", "plan_test").putNumber("expectedPlanRevision", 4)
                        .put("definition", definition).putText("operationKey", "idem_propose").build(), 0);

        JaTool.ToolResult result = tool.execute(invocation,
                        context("turn_test", invocation.callId(), DEADLINE.plusSeconds(30)),
                        CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome(), result::toString);
        AgentTool.ExtensionFact command = result.extensionFacts().getFirst();
        assertEquals("ja.plan", command.namespace());
        assertEquals("tool-command", command.type());
        assertEquals("plan_propose", ((JsonText) command.payload().get("toolName")).value());
        JsonObject arguments = (JsonObject) command.payload().get("arguments");
        assertEquals("plan_test", ((JsonText) arguments.get("planId")).value());
    }

    /** Turn identity 漂移仍在命令事实生成前失败，即使新执行窗口有效。
     *
     * @author kongweiguang
     */
    @Test
    void rejectsPlanMutationWhenTurnIdentityChanges() {
        Fixture fixture = fixture();
        JaTool tool = tool(fixture.factory(), "plan_propose", CollaborationMode.PLAN, TurnOrigin.USER);
        AgentTool.Invocation invocation = invocation("plan_propose");

        JaTool.ToolResult result = tool.execute(invocation,
                        context("turn_other", invocation.callId(), DEADLINE.plusSeconds(30)),
                        CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("PLAN_INVALID_STATE", result.errorCode());
        assertTrue(result.extensionFacts().isEmpty());
    }

    /** 动态代理只实现本测试的最窄 PlanUseCase 路径。 */
    private static Fixture fixture() {
        return fixture(PlanModels.PlanStatus.DRAFT);
    }

    /** 以可空 Plan 状态覆盖独立 Plan 和各规划阶段，内部执行身份仍保持真实 origin 约束。 */
    private static Fixture fixture(PlanModels.PlanStatus planStatus) {
        PlanUseCase plans = (PlanUseCase) Proxy.newProxyInstance(PlanUseCase.class.getClassLoader(),
                new Class<?>[]{PlanUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "currentPlanContext" -> planStatus == null ? Optional.empty()
                            : Optional.of(new PlanUseCase.PlanTurnContext(
                                    "plan_test", 4, planStatus, planStatus == PlanModels.PlanStatus.APPROVED
                                    || planStatus == PlanModels.PlanStatus.EXECUTING
                                    || planStatus == PlanModels.PlanStatus.VERIFYING
                                    || planStatus == PlanModels.PlanStatus.PAUSED
                                    || planStatus == PlanModels.PlanStatus.COMPLETED
                                    ? "planrev_test" : null,
                                    planStatus == PlanModels.PlanStatus.PAUSED
                                            || planStatus == PlanModels.PlanStatus.EXECUTING
                                            || planStatus == PlanModels.PlanStatus.VERIFYING
                                            || planStatus == PlanModels.PlanStatus.COMPLETED
                                            ? "run_plan" : null));
                    case "planExecutionContext" -> Optional.of(new PlanUseCase.PlanTurnContext(
                            "plan_test", 5, PlanModels.PlanStatus.EXECUTING, "planrev_test", "run_plan"));
                    case "toString" -> "PlanUseCaseFixture";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError("unexpected PlanUseCase call: " + method.getName());
                });
        return new Fixture(new PlanToolFactory(plans, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    /** 通过单次业务工具快照取得唯一 Tool。 */
    private static JaTool tool(PlanToolFactory factory, String name,
                                   CollaborationMode mode, TurnOrigin origin) {
        List<JaTool> matches = tools(factory, mode, origin).stream()
                .filter(candidate -> candidate.spec().name().equals(name)).toList();
        assertEquals(1, matches.size());
        return matches.getFirst();
    }

    /** 提取请求级可见 Tool 名称。 */
    private static List<String> names(PlanToolFactory factory,
                                      CollaborationMode mode, TurnOrigin origin) {
        return tools(factory, mode, origin).stream().map(value -> value.spec().name()).toList();
    }

    /** 工厂只读取一次领域状态，绑定阶段仅补入最终目录摘要。 */
    private static List<JaTool> tools(PlanToolFactory factory,
                                          CollaborationMode mode, TurnOrigin origin) {
        JaTool.Selection tools = factory.toolsFor(request(mode, origin));
        JaToolCatalogIdentity identity = new JaToolCatalogIdentity(
                "a".repeat(64), "mcp_test", Set.of());
        return tools.definitions().stream()
                .filter(definition -> tools.visibleNames().contains(definition.spec().name()))
                .map(definition -> definition.materialize(identity)).toList();
    }

    /** 只为 Plan 执行来源提供持久 run identity，避免测试绕过 TurnRuntimeRequest 的来源闭集。 */
    private static JaToolRequestContext request(CollaborationMode mode, TurnOrigin origin) {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", null,
                AccessMode.APPROVAL_REQUIRED, mode, ThreadPreferences.TitleSource.MANUAL);
        String planRevisionId = origin == TurnOrigin.PLAN_EXECUTION ? "planrev_test" : null;
        String runId = origin == TurnOrigin.PLAN_EXECUTION ? "run_test" : null;
        TurnRuntimeRequest turn = new TurnRuntimeRequest("thr_test", "turn_test",
                Path.of("C:\\ja-plan-tools").toAbsolutePath(), "ws_test", "provider_test", "model_test",
                null, AccessMode.APPROVAL_REQUIRED, mode, origin, planRevisionId, runId, NOW, null, java.util.Optional.empty());
        return new JaToolRequestContext(turn, preferences, "cfg_test", true, DEADLINE, Optional.empty());
    }

    /** 以实际测试 Turn 冻结 Core/Ja 同一身份，并允许单独改变每次执行的短时 deadline。
     *
     * @author kongweiguang
     */
    private static JaTool.ExecutionContext context(String turnId, String callId) {
        return context(turnId, callId, DEADLINE);
    }

    /** 使用给定 IO deadline，身份漂移测试仍从相同的冻结请求快照派生上下文。
     *
     * @author kongweiguang
     */
    private static JaTool.ExecutionContext context(String turnId, String callId, Instant deadline) {
        AgentIdentity identity = new AgentIdentity("tenant_test", "thr_test", turnId, "user_test");
        return new JaTool.ExecutionContext(identity, callId, identity.toolCallKey(callId),
                "thr_test", turnId, Path.of("C:\\ja-plan-tools").toAbsolutePath(),
                AccessMode.APPROVAL_REQUIRED, "cfg_test", deadline, "ws_test", null, null,
                TurnOrigin.USER);
    }

    /** 制造只用于读取冻结安全元数据的有效调用身份，不执行任何 PlanUseCase 操作。 */
    private static AgentTool.Invocation invocation(String toolName) {
        return new AgentTool.Invocation("call_capability_metadata", toolName, JsonObjects.builder().build(), 0);
    }

    /**
     * 聚合能力和唯一被观察的领域命令。
     *
     * @param factory 工厂
     */
    private record Fixture(PlanToolFactory factory) {
    }
}
