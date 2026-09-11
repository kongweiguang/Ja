// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 为一次请求贡献模型说明与真实可执行 Tool；扩展只拥有适配，不接管 Turn、权限或持久化。
 */
public interface AgentCapability {
    /** 返回稳定能力 ID，用于确定排序、冲突诊断和目录身份。 */
    String id();

    /** 返回显式顺序；同序能力再按稳定 ID 排列，避免依赖注入容器顺序。 */
    int order();

    /**
     * 一次读取并冻结领域状态；最终目录摘要随后仅用于物化 Tool，不得触发第二次领域查询。
     */
    Prepared prepare(Request request);

    /** 能力绑定只接收当前请求已经解析出的稳定身份，不反向读取配置或 Turn 聚合。 */
    record Request(String threadId, String turnId, Path workspaceRoot, String workspaceId,
                   ThreadPreferences preferences, String configGeneration, boolean clarificationEnabled,
                   Instant deadline, TurnOrigin origin,
                   Optional<TaskCapabilityCeilingPort.Kind> taskKind) {
        /** 保持既有能力调用面的默认身份；只有 Child lineage 解析出 Kind 时才收紧子代理边界。 */
        public Request(String threadId, String turnId, Path workspaceRoot, String workspaceId,
                       ThreadPreferences preferences, String configGeneration, boolean clarificationEnabled,
                       Instant deadline, TurnOrigin origin) {
            this(threadId, turnId, workspaceRoot, workspaceId, preferences, configGeneration,
                    clarificationEnabled, deadline, origin, Optional.empty());
        }

        /** 防御性冻结请求安全点，允许无 turnId 的模型目录预解析自然得到空 Tool。 */
        public Request {
            threadId = ContractChecks.identifier(threadId, "threadId");
            if (turnId != null) turnId = ContractChecks.identifier(turnId, "turnId");
            workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
            workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
            preferences = Objects.requireNonNull(preferences, "preferences");
            configGeneration = ContractChecks.configurationGeneration(configGeneration);
            deadline = Objects.requireNonNull(deadline, "deadline");
            origin = Objects.requireNonNull(origin, "origin");
            taskKind = Objects.requireNonNull(taskKind, "taskKind");
        }
    }

    /**
     * prepare 的不可变结果同时持有说明与 Tool 安全描述，Schema 不再与真实执行实现分属两套目录。
     */
    record Prepared(String promptFragment, List<ToolContribution> tools) {
        /** 防御性复制请求级贡献，空说明表示该能力当前不需要模型指令。 */
        public Prepared {
            promptFragment = promptFragment == null ? "" : promptFragment;
            if (promptFragment.length() > 65_536 || promptFragment.chars().anyMatch(ch -> ch == 0)) {
                throw new IllegalArgumentException("invalid capability prompt fragment");
            }
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        }

        /** 无说明、无 Tool 的稳定空贡献避免具体能力自行返回 null。 */
        public static Prepared empty() {
            return new Prepared("", List.of());
        }
    }

    /**
     * ToolContribution 使用现有 ToolSpec，而工厂只注入最终目录身份；其余执行上下文仍由 AgentToolRunner 提供。
     */
    record ToolContribution(ToolSpec spec, ToolSideEffect sideEffect,
                            AgentTool.WorkspaceMutationMode workspaceMutationMode,
                            AgentTool.ToolBindingDescriptor bindingDescriptor,
                            AgentTool.PlanAccess planAccess,
                            AgentTool.ApprovalRequirement approvalRequirement,
                            Function<CatalogIdentity, AgentTool> binder) {
        /** 保持普通能力的默认闭锁，只有明确标记的内部能力可进入 Plan 目录。 */
        public ToolContribution(ToolSpec spec, ToolSideEffect sideEffect,
                                AgentTool.WorkspaceMutationMode workspaceMutationMode,
                                AgentTool.ToolBindingDescriptor bindingDescriptor,
                                Function<CatalogIdentity, AgentTool> binder) {
            this(spec, sideEffect, workspaceMutationMode, bindingDescriptor,
                    AgentTool.PlanAccess.DISALLOWED, AgentTool.ApprovalRequirement.USER_REQUIRED, binder);
        }

        /** 为已有的 PlanAccess 调用面保留构造重载；新审批边界默认收紧为必须用户确认。 */
        public ToolContribution(ToolSpec spec, ToolSideEffect sideEffect,
                                AgentTool.WorkspaceMutationMode workspaceMutationMode,
                                AgentTool.ToolBindingDescriptor bindingDescriptor,
                                AgentTool.PlanAccess planAccess,
                                Function<CatalogIdentity, AgentTool> binder) {
            this(spec, sideEffect, workspaceMutationMode, bindingDescriptor,
                    planAccess, AgentTool.ApprovalRequirement.USER_REQUIRED, binder);
        }

        /** 冻结全部安全描述，Catalog 会在物化后逐项复核而不是信任工厂。 */
        public ToolContribution {
            spec = Objects.requireNonNull(spec, "spec");
            sideEffect = Objects.requireNonNull(sideEffect, "sideEffect");
            workspaceMutationMode = Objects.requireNonNull(workspaceMutationMode, "workspaceMutationMode");
            bindingDescriptor = Objects.requireNonNull(bindingDescriptor, "bindingDescriptor");
            planAccess = Objects.requireNonNull(planAccess, "planAccess");
            approvalRequirement = Objects.requireNonNull(approvalRequirement, "approvalRequirement");
            binder = Objects.requireNonNull(binder, "binder");
            if (!spec.name().equals(bindingDescriptor.localName())) {
                throw new IllegalArgumentException("capability Tool binding name mismatch");
            }
        }
    }

    /** 最终目录身份只包含恢复与 Child ceiling 所需摘要，不携带配置正文或 Secret。 */
    record CatalogIdentity(String toolCatalogDigest, String mcpCatalogRevision, Set<String> skillIds) {
        /** 固定摘要与有序无关 Skill ID 集，避免 Tool 工厂观察后续可变集合。 */
        public CatalogIdentity {
            if (toolCatalogDigest == null || !toolCatalogDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid toolCatalogDigest");
            }
            if (mcpCatalogRevision == null || mcpCatalogRevision.isBlank()
                    || mcpCatalogRevision.length() > 256
                    || mcpCatalogRevision.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("invalid mcpCatalogRevision");
            }
            skillIds = Set.copyOf(Objects.requireNonNull(skillIds, "skillIds"));
            if (skillIds.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("invalid skillIds");
            }
        }
    }

    /** 一次请求最终使用的不可变说明和 AgentTool 集合。 */
    record Binding(String promptFragment, List<AgentTool> tools) {
        /** 防御性复制最终 Tool 列表，运行期间的配置刷新只能影响下一次 prepare。 */
        public Binding {
            promptFragment = promptFragment == null ? "" : promptFragment;
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        }
    }
}
