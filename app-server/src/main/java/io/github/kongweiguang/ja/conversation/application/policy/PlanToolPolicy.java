// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.policy;

import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ToolPolicy;
import java.util.Objects;

/**
 * Plan 阶段的第二道 Tool 安全门；它只判断已物化 Tool 的静态证明，不执行 Tool 或读取外部状态。
 *
 * <p>只有公开用户请求和创建子任务的首轮输入在 PLAN 协作模式下属于只读规划。Goal 续跑和
 * 用户执行已冻结计划虽然可能保留 Thread 的 PLAN 偏好，但它们已经拥有独立的执行来源，必须
 * 保持执行目录和权限边界。</p>
 */
public final class PlanToolPolicy {
    /** 纯静态策略不持有运行态，避免权限判断绕过当前 Turn 上下文。 */
    private PlanToolPolicy() {
    }

    /**
     * 在 Runner 调用前复核规划阶段的 Tool；调用方应把拒绝作为普通 ToolResult 返回模型纠正。
     */
    public static ToolPolicy.Decision validate(
            AgentTool tool, TurnOrigin origin, CollaborationMode mode) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(mode, "mode");
        if (!isReadOnlyPlanning(origin, mode)) {
            return ToolPolicy.Decision.allow();
        }
        if (!admitted(tool)) {
            return ToolPolicy.Decision.deny(
                    "PLAN_READ_ONLY_REQUIRED",
                    "Plan 阶段只允许受控内建只读 Tool；请先形成计划，再由用户执行。");
        }
        return ToolPolicy.Decision.allow();
    }

    /**
     * 集中定义 Planner 的来源边界，避免 Goal continuation 因复用 Thread 的 PLAN 偏好被误判为只读。
     * 新的内部执行来源必须显式加入此规则，不能通过“不是 PLAN_EXECUTION”获得规划身份。
     */
    public static boolean isReadOnlyPlanning(TurnOrigin origin, CollaborationMode mode) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(mode, "mode");
        return mode == CollaborationMode.PLAN
                && (origin == TurnOrigin.USER || origin == TurnOrigin.CHILD_TASK);
    }

    /** 安全证明来自编译期内建实现，外部工具自述或同名标签不能获得规划写入豁免。 */
    public static boolean admitted(AgentTool tool) {
        Objects.requireNonNull(tool, "tool");
        boolean readOnly = tool.sideEffect() == ToolSideEffect.READ_ONLY
                && tool.workspaceMutationMode() == AgentTool.WorkspaceMutationMode.NONE;
        return tool.bindingDescriptor().routeKind() == AgentTool.RouteKind.BUILTIN
                && (readOnly || tool.planAccess() == AgentTool.PlanAccess.INTERNAL_MUTATION);
    }
}
