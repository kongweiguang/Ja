// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.policy;

import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Plan 规划只读门与 PLAN_EXECUTION 权限恢复的边界。 */
final class PlanToolPolicyTest {
    /** 规划阶段只允许内建只读 Tool，不能被外部/MCP 自报只读绕过。 */
    @Test
    void deniesExternalToolEvenWhenItClaimsReadOnly() {
        var decision = PlanToolPolicy.validate(
                tool(AgentTool.RouteKind.MCP, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE),
                TurnOrigin.USER, CollaborationMode.PLAN);

        assertFalse(decision.proceed());
        assertTrue(decision.code().equals("PLAN_READ_ONLY_REQUIRED"));
    }

    /** 用户执行已冻结计划时恢复原目录权限，避免规划策略挡住实际实施。 */
    @Test
    void allowsPlanExecutionOrigin() {
        var decision = PlanToolPolicy.validate(
                tool(AgentTool.RouteKind.BUILTIN, ToolSideEffect.EXTERNAL,
                        AgentTool.WorkspaceMutationMode.EXACT_TEXT),
                TurnOrigin.PLAN_EXECUTION, CollaborationMode.PLAN);

        assertTrue(decision.proceed());
    }

    /** Goal 续跑可以保留 Thread 的 Plan 偏好，但隐藏运行仍必须保留执行能力。 */
    @Test
    void allowsGoalContinuationOriginInPlanMode() {
        var decision = PlanToolPolicy.validate(
                tool(AgentTool.RouteKind.BUILTIN, ToolSideEffect.EXTERNAL,
                        AgentTool.WorkspaceMutationMode.EXACT_TEXT),
                TurnOrigin.GOAL_CONTINUATION, CollaborationMode.PLAN);

        assertTrue(decision.proceed());
    }

    /** 只有公开输入来源进入只读规划策略；内部执行来源必须显式保留执行语义。 */
    @Test
    void classifiesOnlyUserAndChildTaskAsReadOnlyPlanning() {
        assertTrue(PlanToolPolicy.isReadOnlyPlanning(TurnOrigin.USER, CollaborationMode.PLAN));
        assertTrue(PlanToolPolicy.isReadOnlyPlanning(TurnOrigin.CHILD_TASK, CollaborationMode.PLAN));
        assertFalse(PlanToolPolicy.isReadOnlyPlanning(TurnOrigin.GOAL_CONTINUATION, CollaborationMode.PLAN));
        assertFalse(PlanToolPolicy.isReadOnlyPlanning(TurnOrigin.PLAN_EXECUTION, CollaborationMode.PLAN));
        assertFalse(PlanToolPolicy.isReadOnlyPlanning(TurnOrigin.USER, CollaborationMode.DEFAULT));
    }

    /** 普通模式不受 Plan 只读策略影响，保持既有访问策略的唯一 owner。 */
    @Test
    void leavesDefaultModeUntouched() {
        var decision = PlanToolPolicy.validate(
                tool(AgentTool.RouteKind.BUILTIN, ToolSideEffect.EXTERNAL,
                        AgentTool.WorkspaceMutationMode.EXACT_TEXT),
                TurnOrigin.USER, CollaborationMode.DEFAULT);

        assertTrue(decision.proceed());
    }

    /** 构造最小静态 Tool 夹具，不启动进程或访问工作区。 */
    private static AgentTool tool(
            AgentTool.RouteKind route, ToolSideEffect sideEffect,
            AgentTool.WorkspaceMutationMode mutationMode) {
        ToolSpec spec = new ToolSpec("fixture_tool", "fixture", JsonObjects.builder().build());
        AgentTool.ToolBindingDescriptor descriptor = new AgentTool.ToolBindingDescriptor(
                route, "fixture_tool", route == AgentTool.RouteKind.BUILTIN ? "builtin" : "server",
                "remote", "0000000000000000000000000000000000000000000000000000000000000000",
                "1111111111111111111111111111111111111111111111111111111111111111");
        return new AgentTool() {
            /** 返回夹具的稳定 schema，测试只验证策略不重写 Tool。 */
            @Override public ToolSpec spec() { return spec; }
            /** 返回夹具声明的副作用分类。 */
            @Override public ToolSideEffect sideEffect() { return sideEffect; }
            /** 返回夹具声明的工作区可观察性。 */
            @Override public WorkspaceMutationMode workspaceMutationMode() { return mutationMode; }
            /** 返回与路由种类对应的固定绑定身份。 */
            @Override public ToolBindingDescriptor bindingDescriptor() { return descriptor; }
            /** 以成功结果完成夹具调用，避免测试触发真实 IO。 */
            @Override public CompletionStage<ToolResult> execute(
                    Invocation invocation, ExecutionContext context, CancellationToken token) {
                return CompletableFuture.completedFuture(
                        new ToolResult(ToolOutcome.SUCCEEDED, "", Optional.empty(), null));
            }
        };
    }
}
