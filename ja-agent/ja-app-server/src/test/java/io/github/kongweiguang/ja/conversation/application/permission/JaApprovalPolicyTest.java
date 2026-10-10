// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.permission;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 固定 Ja 的访问模式与经过路由核验的内部工具审批矩阵。 */
final class JaApprovalPolicyTest {
    /** FULL_ACCESS 是显式会话选择，即使工具声明通常需要用户确认也不额外挂起。 */
    @Test
    void fullAccessDoesNotRequestAnAdditionalToolApproval() {
        assertEquals(AgentTool.ApprovalRequirement.NOT_REQUIRED,
                JaApprovalPolicy.requirement(AccessMode.FULL_ACCESS, JaTool.ApprovalRequirement.USER_REQUIRED,
                        Optional.empty(), false));
    }

    /** APPROVAL_REQUIRED 下普通用户工具必须有显式 Wait，副作用标签不改变该决策。 */
    @Test
    void approvalRequiredWaitsForUserRequiredTools() {
        assertEquals(AgentTool.ApprovalRequirement.REQUIRED,
                JaApprovalPolicy.requirement(AccessMode.APPROVAL_REQUIRED, JaTool.ApprovalRequirement.USER_REQUIRED,
                        Optional.of(binding(JaTool.RouteKind.BUILTIN)), false));
    }

    /** 内部声明只在精确的本地 Builtin 路由下免于重复审批。 */
    @Test
    void approvalRequiredAllowsDeclaredInternalBuiltin() {
        assertEquals(AgentTool.ApprovalRequirement.NOT_REQUIRED,
                JaApprovalPolicy.requirement(AccessMode.APPROVAL_REQUIRED, JaTool.ApprovalRequirement.TRUSTED_INTERNAL,
                        Optional.of(binding(JaTool.RouteKind.BUILTIN)), false));
    }

    /** MCP 内部声明仅对验证过的可信本地读取动作豁免，其他远端动作仍要求用户确认。 */
    @Test
    void approvalRequiredAllowsOnlyVerifiedTrustedMcpRead() {
        assertEquals(AgentTool.ApprovalRequirement.NOT_REQUIRED,
                JaApprovalPolicy.requirement(AccessMode.APPROVAL_REQUIRED, JaTool.ApprovalRequirement.TRUSTED_INTERNAL,
                        Optional.of(binding(JaTool.RouteKind.MCP)), true));
        assertEquals(AgentTool.ApprovalRequirement.REQUIRED,
                JaApprovalPolicy.requirement(AccessMode.APPROVAL_REQUIRED, JaTool.ApprovalRequirement.TRUSTED_INTERNAL,
                        Optional.of(binding(JaTool.RouteKind.MCP)), false));
    }

    /** 缺失路由不能证明可信来源；内部声明不得单独作为免审批凭据。 */
    @Test
    void approvalRequiredFailsClosedWithoutAResolvedRoute() {
        assertEquals(AgentTool.ApprovalRequirement.REQUIRED,
                JaApprovalPolicy.requirement(AccessMode.APPROVAL_REQUIRED, JaTool.ApprovalRequirement.TRUSTED_INTERNAL,
                        Optional.empty(), true));
    }

    /** 为策略测试构造具有稳定散列的路由，不依赖运行时目录或真实 MCP 配置。 */
    private static JaTool.ToolBindingDescriptor binding(JaTool.RouteKind kind) {
        String serverId = kind == JaTool.RouteKind.BUILTIN ? "builtin" : "mcp-test";
        return new JaTool.ToolBindingDescriptor(kind, "fixture", serverId, "fixture", "a".repeat(64),
                "b".repeat(64));
    }
}
