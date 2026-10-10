// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.policy;

import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
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
        JaTool tool = tool(JaTool.RouteKind.MCP, ToolSideEffect.READ_ONLY,
                JaTool.WorkspaceMutationMode.NONE, JaTool.PlanAccess.READ_ONLY, false);
        PlanToolPolicy.Decision decision = PlanToolPolicy.validate(
                tool, invocation(), TurnOrigin.USER, CollaborationMode.PLAN);

        assertFalse(decision.proceed());
        assertTrue(decision.code().equals("PLAN_READ_ONLY_REQUIRED"));
    }

    /** 用户执行已冻结计划时恢复原目录权限，避免规划策略挡住实际实施。 */
    @Test
    void allowsPlanExecutionOrigin() {
        PlanToolPolicy.Decision decision = PlanToolPolicy.validate(tool(JaTool.RouteKind.BUILTIN, ToolSideEffect.EXTERNAL,
                        JaTool.WorkspaceMutationMode.EXACT_TEXT, JaTool.PlanAccess.DISALLOWED, false),
                invocation(), TurnOrigin.PLAN_EXECUTION, CollaborationMode.PLAN);

        assertTrue(decision.proceed());
    }

    /** 只有公开输入来源进入只读规划策略；内部执行来源必须显式保留执行语义。 */
    @Test
    void classifiesOnlyUserAndChildTaskAsReadOnlyPlanning() {
        assertTrue(PlanToolPolicy.isReadOnlyPlanning(TurnOrigin.USER, CollaborationMode.PLAN));
        assertTrue(PlanToolPolicy.isReadOnlyPlanning(TurnOrigin.CHILD_TASK, CollaborationMode.PLAN));
        assertFalse(PlanToolPolicy.isReadOnlyPlanning(TurnOrigin.PLAN_EXECUTION, CollaborationMode.PLAN));
        assertFalse(PlanToolPolicy.isReadOnlyPlanning(TurnOrigin.USER, CollaborationMode.DEFAULT));
    }

    /** 普通模式不受 Plan 只读策略影响，保持既有访问策略的唯一 owner。 */
    @Test
    void leavesDefaultModeUntouched() {
        PlanToolPolicy.Decision decision = PlanToolPolicy.validate(tool(JaTool.RouteKind.BUILTIN, ToolSideEffect.EXTERNAL,
                        JaTool.WorkspaceMutationMode.EXACT_TEXT, JaTool.PlanAccess.DISALLOWED, false),
                invocation(), TurnOrigin.USER, CollaborationMode.DEFAULT);

        assertTrue(decision.proceed());
    }

    /** 允许经应用显式配置且逐调用证明为本地只读的 MCP 路由。 */
    @Test
    void allowsTrustedLocalReadGateway() {
        PlanToolPolicy.Decision decision = PlanToolPolicy.validate(tool(JaTool.RouteKind.MCP, ToolSideEffect.EXTERNAL,
                        JaTool.WorkspaceMutationMode.UNOBSERVABLE, JaTool.PlanAccess.DISALLOWED, true),
                invocation(), TurnOrigin.USER, CollaborationMode.PLAN);

        assertTrue(decision.proceed());
    }

    /** Plan 内部聚合变更须由内建工具显式声明，普通 Workspace 写入仍被拒绝。 */
    @Test
    void onlyExplicitBuiltinPlanMutationIsAdmitted() {
        assertTrue(PlanToolPolicy.admitted(tool(JaTool.RouteKind.BUILTIN, ToolSideEffect.EXTERNAL,
                JaTool.WorkspaceMutationMode.UNOBSERVABLE, JaTool.PlanAccess.INTERNAL_MUTATION, false), invocation()));
        assertFalse(PlanToolPolicy.admitted(tool(JaTool.RouteKind.BUILTIN, ToolSideEffect.EXTERNAL,
                JaTool.WorkspaceMutationMode.EXACT_TEXT, JaTool.PlanAccess.DISALLOWED, false), invocation()));
    }

    /** 同一 call API 提供最终解析参数，策略测试不伪造工具运行或读写工作区。 */
    private static AgentTool.Invocation invocation() {
        return new AgentTool.Invocation("call_" + "a".repeat(64), "fixture_tool", JsonObject.empty(), 0);
    }

    /** 用真实 JaTool 元数据形状验证规划策略，避免测试依赖已删除的 Core Ja 专属字段。 */
    private static JaTool tool(JaTool.RouteKind route, ToolSideEffect sideEffect,
                               JaTool.WorkspaceMutationMode mutationMode, JaTool.PlanAccess planAccess,
                               boolean trustedRead) {
        ToolSpec spec = new ToolSpec("fixture_tool", "fixture", JsonObject.empty());
        JaTool.ToolBindingDescriptor descriptor = new JaTool.ToolBindingDescriptor(route, "fixture_tool",
                route == JaTool.RouteKind.BUILTIN ? "builtin" : "server", "remote",
                "0".repeat(64), "1".repeat(64));
        return new JaTool() {
            /** 返回固定 Schema，让用例只关注 Ja 规划授权边界。 */
            @Override public ToolSpec spec() { return spec; }
            /** 返回夹具声明的副作用分类。 */
            @Override public ToolSideEffect sideEffect() { return sideEffect; }
            /** 返回夹具声明的 Workspace 可观察性。 */
            @Override public WorkspaceMutationMode workspaceMutationMode() { return mutationMode; }
            /** 返回规划阶段的明确访问分类。 */
            @Override public PlanAccess planAccess() { return planAccess; }
            /** 返回冻结的路由身份。 */
            @Override public ToolBindingDescriptor bindingDescriptor() { return descriptor; }
            /** 只有该标志为 true 的动态网关才通过受信本地只读门。 */
            @Override public boolean isTrustedLocalReadAction(AgentTool.Invocation ignored) { return trustedRead; }
            /** 返回成功夹具结果，不启动真实能力。 */
            @Override public CompletionStage<ToolResult> execute(
                    AgentTool.Invocation ignored, ExecutionContext context, CancellationToken token) {
                return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.SUCCEEDED,
                        "", Optional.empty(), null));
            }
        };
    }
}
