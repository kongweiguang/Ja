// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.middleware;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;

/** 把两档权限模式收敛为 Tool 前置短路，不建立 SessionGrant 或隐式授权缓存。 */
public final class ApprovalMiddleware implements AgentMiddleware {
    /**
     * full_access 直接继续；approval_required 每次调用内部 ApprovalGate，拒绝只返回 ToolResult。
     */
    @Override
    public ToolDecision beforeTool(ToolContext context) {
        if (context.execution().accessMode() == AccessMode.FULL_ACCESS) return ToolDecision.allow();
        return context.approvals().request()
                ? ToolDecision.allow()
                : ToolDecision.deny("TOOL_DENIED", "Tool denied by user");
    }
}
