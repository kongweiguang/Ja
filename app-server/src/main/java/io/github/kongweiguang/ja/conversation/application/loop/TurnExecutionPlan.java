// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.port.out.TurnToolSessionFactory;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 准入后由 application 独占的完整 Turn 执行计划，不跨越入站或出站端口。
 */
public record TurnExecutionPlan(String threadId, String turnId, Path workspaceRoot, String userInput,
                                List<String> attachmentIds,
                                ModelPort.ModelConfiguration model, AccessMode accessMode, TurnLimits limits,
                                Instant requestedAt,
                                String workspaceId, long initialThreadRevision, long initialTurnMutationVersion,
                                 AgentPromptSession promptSession,
                                 ManagedAttachmentReader attachments,
                                 List<AgentTool> tools, String configRevision, TurnToolSessionFactory toolSessions,
                                ToolProjectionLimits outputLimits, List<String> presentationSecrets) {

    /**
     * 冻结准入身份、配置代际和能力集合，使热更新不能改变已接纳 Turn。
     */
    public TurnExecutionPlan {
        threadId = ContractChecks.identifier(threadId, "threadId");
        turnId = ContractChecks.identifier(turnId, "turnId");
        workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
        userInput = ContractChecks.text(userInput, "userInput", 4_000_000, true);
        attachmentIds = List.copyOf(Objects.requireNonNull(attachmentIds, "attachmentIds"));
        if (userInput.isBlank() && attachmentIds.isEmpty()) {
            throw new IllegalArgumentException("turn content must not be empty");
        }
        if (attachmentIds.size() > 10 || new java.util.HashSet<>(attachmentIds).size() != attachmentIds.size()) {
            throw new IllegalArgumentException("invalid attachmentIds");
        }
        for (String attachmentId : attachmentIds) {
            String value = ContractChecks.identifier(attachmentId, "attachmentId");
            if (!value.startsWith("att_")) throw new IllegalArgumentException("invalid attachmentIds");
        }
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(requestedAt, "requestedAt");
        workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
        if (!workspaceId.startsWith("ws_")) throw new IllegalArgumentException("invalid workspaceId");
        if (initialThreadRevision < 0 || initialTurnMutationVersion < 0) {
            throw new IllegalArgumentException("turn revisions must be non-negative");
        }
        Objects.requireNonNull(promptSession, "promptSession");
        Objects.requireNonNull(attachments, "attachments");
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (configRevision == null || !configRevision.startsWith("cfg_")) {
            throw new IllegalArgumentException("invalid configRevision");
        }
        Objects.requireNonNull(toolSessions, "toolSessions");
        Objects.requireNonNull(outputLimits, "outputLimits");
        presentationSecrets = List.copyOf(Objects.requireNonNull(presentationSecrets, "presentationSecrets"));
        if (presentationSecrets.stream().anyMatch(value -> value == null || value.isEmpty())) {
            throw new IllegalArgumentException("presentationSecrets must contain only non-empty values");
        }
        createToolCatalog(tools);
    }

    /**
     * 合并内建与 MCP Tool 时拒绝重名，执行阶段不能依赖集合插入顺序选实现。
     */
    public static Map<String, AgentTool> createToolCatalog(List<AgentTool> source) {
        Map<String, AgentTool> result = new HashMap<>();
        for (AgentTool tool : source) {
            Objects.requireNonNull(tool, "tool");
            if (result.putIfAbsent(tool.spec().name(), tool) != null) {
                throw new IllegalArgumentException("duplicate Tool name");
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 只替换数据库准入回执，保持身份、配置代际、预算与 Tool 快照不变。
     */
    public TurnExecutionPlan withAdmissionReceipt(long revision, long turnMutationVersion) {
        return new TurnExecutionPlan(threadId, turnId, workspaceRoot, userInput, attachmentIds,
                model, accessMode, limits,
                requestedAt, workspaceId, revision, turnMutationVersion, promptSession, attachments, tools,
                configRevision, toolSessions, outputLimits, presentationSecrets);
    }

    /**
     * 日志只保留 Turn 身份和非敏感执行边界；默认 record 输出会泄漏用户输入及 presentationSecrets。
     */
    @Override
    public String toString() {
        return "TurnExecutionPlan[threadId=" + threadId + ", turnId=" + turnId
                + ", workspaceId=" + workspaceId + ", model=" + model
                + ", accessMode=" + accessMode + ", limits=" + limits
                + ", requestedAt=" + requestedAt + ", attachmentCount=" + attachmentIds.size()
                + ", toolCount=" + tools.size() + ", configRevision=" + configRevision
                + ", outputLimits=" + outputLimits + ", userInput=<redacted>, presentationSecrets=<redacted>]";
    }
}
