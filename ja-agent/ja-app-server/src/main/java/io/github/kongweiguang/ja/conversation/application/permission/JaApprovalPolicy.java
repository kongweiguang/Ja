// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.permission;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Ja 的单一审批规则：把会话访问模式与工具声明、已验证路由合并成 Core 的通用审批要求。
 */
public final class JaApprovalPolicy {
    private static final Duration APPROVAL_VALIDITY = Duration.ofMinutes(5);

    /**
     * 规则只通过静态决策使用，避免运行时持有会话或工具状态。
     */
    private JaApprovalPolicy() {
    }

    /**
     * 按既有权限矩阵计算最终工具调用是否等待用户批准；不以 READ_ONLY 副作用标签推导豁免。
     * FULL_ACCESS 是会话显式选择，仍须先执行参数、路由、Plan 与工具自身边界校验。
     */
    public static AgentTool.ApprovalRequirement requirement(
            AccessMode accessMode, JaTool.ApprovalRequirement declaration,
            Optional<JaTool.ToolBindingDescriptor> binding, boolean trustedLocalMcpRead) {
        AccessMode mode = Objects.requireNonNull(accessMode, "accessMode");
        JaTool.ApprovalRequirement declared = Objects.requireNonNull(declaration, "declaration");
        Optional<JaTool.ToolBindingDescriptor> route = Objects.requireNonNull(binding, "binding");
        if (mode == AccessMode.FULL_ACCESS) {
            return AgentTool.ApprovalRequirement.NOT_REQUIRED;
        }
        if (declared == JaTool.ApprovalRequirement.USER_REQUIRED || route.isEmpty()) {
            return AgentTool.ApprovalRequirement.REQUIRED;
        }
        JaTool.ToolBindingDescriptor descriptor = route.orElseThrow();
        boolean trustedBuiltin = descriptor.routeKind() == JaTool.RouteKind.BUILTIN;
        boolean trustedMcpAction = descriptor.routeKind() == JaTool.RouteKind.MCP && trustedLocalMcpRead;
        return trustedBuiltin || trustedMcpAction
                ? AgentTool.ApprovalRequirement.NOT_REQUIRED
                : AgentTool.ApprovalRequirement.REQUIRED;
    }

    /**
     * 从现有五分钟审批有效期策略生成绝对期限，避免 Core 为 Ja 猜测 TTL 或使用准入时的旧时间。
     */
    public static Instant approvalExpiry(Clock clock) {
        return Objects.requireNonNull(clock, "clock").instant().plus(APPROVAL_VALIDITY);
    }
}
