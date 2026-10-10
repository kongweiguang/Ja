// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.policy;

import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;

import java.util.Objects;

/**
 * Plan 阶段的第二道 Ja Tool 安全门；Core 不携带 Ja 来源或协作模式。
 */
public final class PlanToolPolicy {
    /**
     * 静态规则无需运行态，避免策略判断通过全局服务绕过本次 Turn 的冻结身份。
     */
    private PlanToolPolicy() {
        throw new AssertionError("no instances");
    }

    /**
     * 在最终参数校验后复核 Ja Tool 声明；决策留在 Ja，适配器再映射为 Core 的参数拒绝。
     */
    public static Decision validate(JaTool tool, AgentTool.Invocation invocation,
                                    TurnOrigin origin, CollaborationMode mode) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(mode, "mode");
        if (!isReadOnlyPlanning(origin, mode)) {
            return Decision.allow();
        }
        if (!admitted(tool, invocation)) {
            return Decision.deny("PLAN_READ_ONLY_REQUIRED",
                    "Plan 阶段只允许受控内建只读 Tool；请先形成计划，再由用户执行。");
        }
        return Decision.allow();
    }

    /**
     * 动态网关必须按本次已解析 action 复核副作用和路由，不能借工具级标签扩大规划权限。
     */
    public static boolean admitted(JaTool tool, AgentTool.Invocation invocation) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(invocation, "invocation");
        JaTool.RouteKind routeKind = tool.bindingDescriptor(invocation).routeKind();
        boolean builtIn = routeKind == JaTool.RouteKind.BUILTIN;
        boolean readOnly = tool.sideEffect(invocation) == ToolSideEffect.READ_ONLY
                           && tool.workspaceMutationMode(invocation) == JaTool.WorkspaceMutationMode.NONE;
        boolean trustedGatewayRead = tool.isTrustedLocalReadAction(invocation);
        return (builtIn && (readOnly || tool.planAccess(invocation) == JaTool.PlanAccess.INTERNAL_MUTATION))
               || trustedGatewayRead;
    }

    /**
     * 规划身份只由 USER、同一计划的澄清续答和 Child 首轮组成，不能从非执行态反向猜来源。
     */
    public static boolean isReadOnlyPlanning(TurnOrigin origin, CollaborationMode mode) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(mode, "mode");
        return mode == CollaborationMode.PLAN
               && (origin == TurnOrigin.USER || origin == TurnOrigin.USER_CONTINUATION
                   || origin == TurnOrigin.CHILD_TASK);
    }

    /**
     * Ja 侧策略结果不依赖 Core SPI；成功无错误字段，拒绝必须携带可纠正诊断。
     *
     * @param proceed 是否允许继续
     * @param code 结果或诊断代码
     * @param message 消息或诊断说明
     */
    public record Decision(boolean proceed,
                           String code,
                           String message) {
        /**
         * 避免成功决策残留拒绝原因，或失败决策缺少可向模型说明的错误。
         */
        public Decision {
            if (proceed ? code != null || message != null
                    : code == null || code.isBlank() || message == null || message.isBlank()) {
                throw new IllegalArgumentException("invalid Plan policy decision");
            }
        }

        /**
         * 通过本地策略且不制造空错误对象。
         */
        public static Decision allow() {
            return new Decision(true, null, null);
        }

        /**
         * 将安全拒绝固定为结构化且可呈现给模型的错误。
         */
        public static Decision deny(String code, String message) {
            return new Decision(false, Objects.requireNonNull(code, "code"),
                    Objects.requireNonNull(message, "message"));
        }
    }

}
