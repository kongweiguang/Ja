// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.permission;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 权限裁决使用的完整、规范化且与单个 Turn 绑定的领域请求。
 */
public record PermissionRequest(String threadId, String turnId, String configGeneration,
                                AccessMode accessMode, PermissionAction actionKind, String toolName, Path workspaceRoot,
                                List<String> normalizedResources, String commandSummary) {
    /**
     * 冻结规范路径和资源集合，使 Policy 与审批记录评估同一份事实。
     */
    public PermissionRequest {
        threadId = ContractChecks.identifier(threadId, "threadId");
        turnId = ContractChecks.identifier(turnId, "turnId");
        configGeneration = ContractChecks.configurationGeneration(configGeneration);
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(actionKind, "actionKind");
        toolName = ContractChecks.identifier(toolName, "toolName");
        workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
        normalizedResources = ContractChecks.immutableList(normalizedResources, "normalizedResources");
        if (commandSummary != null) {
            commandSummary = ContractChecks.text(commandSummary, "commandSummary", 65_536, true);
        }
    }
}
