// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.adapter.in.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.in.GoalUseCase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Plan/Goal 能力在一个适配器内保持可见目录、CAS 身份与真实 UseCase 调用一致。 */
final class PlanGoalAgentCapabilityTest {
    private static final Instant NOW = Instant.parse("2026-09-04T08:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-09-04T09:00:00Z");

    /** origin 决定 Tool 闭集，普通 Default Turn 即使存在 Plan 也不暴露内部能力。 */
    @Test
    void exposesToolsForExactTurnOrigin() {
        Fixture fixture = fixture();

        assertEquals(List.of("plan_propose", "plan_draft_update"),
                names(fixture.capability(), CollaborationMode.PLAN, TurnOrigin.USER));
        assertEquals(List.of("plan_step_update", "goal_request_evaluation"),
                names(fixture.capability(), CollaborationMode.DEFAULT, TurnOrigin.GOAL_CONTINUATION));
        assertEquals(List.of("plan_step_update"),
                names(fixture.capability(), CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION));
        assertEquals(List.of(), names(fixture.capability(), CollaborationMode.DEFAULT, TurnOrigin.USER));

        AgentTool proposal = tool(fixture.capability(), "plan_propose", CollaborationMode.PLAN, TurnOrigin.USER);
        assertEquals(AgentTool.PlanAccess.INTERNAL_MUTATION, proposal.planAccess());
        AgentTool stepUpdate = tool(fixture.capability(), "plan_step_update",
                CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION);
        assertEquals(AgentTool.PlanAccess.DISALLOWED, stepUpdate.planAccess());
        AgentTool draftUpdate = tool(fixture.capability(), "plan_draft_update",
                CollaborationMode.PLAN, TurnOrigin.USER);
        assertEquals(AgentTool.PlanAccess.INTERNAL_MUTATION, draftUpdate.planAccess());
        assertTrue(((io.github.kongweiguang.ja.foundation.json.JsonObject)
                draftUpdate.spec().inputSchema().get("properties")).containsKey("expectedDraftRevision"));
    }

    /** plan_propose 的 schema 和执行命令只使用 standalone Plan identity。 */
    @Test
    void proposesStandalonePlanThroughFrozenIdentity() throws Exception {
        Fixture fixture = fixture();
        AgentTool tool = tool(fixture.capability(), "plan_propose", CollaborationMode.PLAN, TurnOrigin.USER);
        assertFalse(tool.spec().inputSchema().containsKey("goalId"));
        JsonObject definition = (JsonObject) JacksonJsonValues.fromNode(new ObjectMapper().readTree("""
                {"objective":"完成计划","scope":["core"],"nonGoals":[],"constraints":[],
                 "dependencies":[],"steps":[{"stepId":"step_1","title":"实现","description":"",
                 "required":true,"dependsOn":[]}],"acceptanceCriteria":[{"criterionId":"criterion_1",
                 "description":"通过测试","required":true}],"risks":[],"verificationStrategy":["unit"]}
                """));
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_propose", "plan_propose",
                JsonObjects.builder().putText("planId", "plan_test").putNumber("expectedPlanRevision", 4)
                        .put("definition", definition).putText("idempotencyKey", "idem_propose").build(), 0);

        AgentTool.ToolResult result = tool.execute(invocation, context("turn_test"), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome(), result::toString);
        assertNotNull(fixture.propose().get());
        assertEquals("plan_test", fixture.propose().get().planId());
    }

    /** 动态代理只实现本测试的最窄 GoalUseCase 路径。 */
    private static Fixture fixture() {
        AtomicReference<GoalUseCase.Propose> propose = new AtomicReference<>();
        GoalUseCase goals = (GoalUseCase) Proxy.newProxyInstance(GoalUseCase.class.getClassLoader(),
                new Class<?>[]{GoalUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "currentPlanContext" -> Optional.of(new GoalUseCase.PlanTurnContext(
                            "plan_test", 4, GoalModels.PlanStatus.DRAFT, null, null));
                    case "planExecutionContext" -> Optional.of(new GoalUseCase.PlanTurnContext(
                            "plan_test", 5, GoalModels.PlanStatus.EXECUTING, "planrev_test", "run_plan"));
                    case "goalContinuationContext" -> Optional.of(new GoalUseCase.GoalTurnContext(
                            "goal_test", 7, "run_test", GoalModels.GoalStatus.ACTIVE,
                            GoalModels.GoalPhase.WORKING, "plan_test", "planrev_test"));
                    case "propose" -> {
                        GoalUseCase.Propose captured = (GoalUseCase.Propose) arguments[0];
                        propose.set(captured);
                        yield new GoalModels.PlanRevision("planrev_test", captured.planId(), 1,
                                captured.definition(), "{}", "a".repeat(64), "AGENT", NOW);
                    }
                    case "toString" -> "GoalUseCaseFixture";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError("unexpected GoalUseCase call: " + method.getName());
                });
        return new Fixture(new PlanGoalAgentCapability(goals, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)), propose);
    }

    /** 通过标准 AgentCapability prepare/binder 取得唯一 Tool。 */
    private static AgentTool tool(PlanGoalAgentCapability capability, String name,
                                  CollaborationMode mode, TurnOrigin origin) {
        List<AgentTool> matches = tools(capability, mode, origin).stream()
                .filter(candidate -> candidate.spec().name().equals(name)).toList();
        assertEquals(1, matches.size());
        return matches.getFirst();
    }

    /** 提取请求级可见 Tool 名称。 */
    private static List<String> names(PlanGoalAgentCapability capability,
                                      CollaborationMode mode, TurnOrigin origin) {
        return tools(capability, mode, origin).stream().map(value -> value.spec().name()).toList();
    }

    /** prepare 只执行一次，binder 仅注入最终目录摘要。 */
    private static List<AgentTool> tools(PlanGoalAgentCapability capability,
                                         CollaborationMode mode, TurnOrigin origin) {
        AgentCapability.Prepared prepared = capability.prepare(request(mode, origin));
        AgentCapability.CatalogIdentity identity = new AgentCapability.CatalogIdentity(
                "a".repeat(64), "mcp_test", Set.of());
        return prepared.tools().stream().map(item -> item.binder().apply(identity)).toList();
    }

    /** 构造请求冻结身份，模式与 origin 由测试显式选择。 */
    private static AgentCapability.Request request(CollaborationMode mode, TurnOrigin origin) {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", null,
                AccessMode.APPROVAL_REQUIRED, mode, ThreadPreferences.TitleSource.MANUAL);
        return new AgentCapability.Request("thr_test", "turn_test",
                Path.of("C:\\ja-plan-goal-tools").toAbsolutePath(), "ws_test", preferences,
                "cfg_test", true, DEADLINE, origin);
    }

    /** 构造与 prepare 完全一致的执行上下文。 */
    private static AgentTool.ExecutionContext context(String turnId) {
        return new AgentTool.ExecutionContext("thr_test", turnId,
                Path.of("C:\\ja-plan-goal-tools").toAbsolutePath(), AccessMode.APPROVAL_REQUIRED,
                "cfg_test", DEADLINE, "ws_test");
    }

    /** 聚合能力和唯一被观察的领域命令。 */
    private record Fixture(PlanGoalAgentCapability capability,
                           AtomicReference<GoalUseCase.Propose> propose) {
    }
}
