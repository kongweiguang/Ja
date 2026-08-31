// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.instruction.AgentInstructionCatalog;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** 以 AGENTS catalog 和冻结 Skill snapshot 创建逐 Turn Prompt Session。 */
public final class DefaultAgentPromptSessionFactory implements AgentPromptSessionFactory {
    private final AgentInstructionCatalog instructions;

    /** 共享 catalog 只持有无状态 NIO 策略和持久仓储，逐 Turn 可变事实留在 Session。 */
    public DefaultAgentPromptSessionFactory(AgentInstructionCatalog instructions) {
        this.instructions = Objects.requireNonNull(instructions, "instructions");
    }

    /** 将配置代际输入映射到独占 Session，禁止不同 Turn 共享激活 Skill 或 Prompt revision。 */
    @Override
    public AgentPromptSession open(SessionRequest request) {
        Objects.requireNonNull(request, "request");
        AgentInstructionCatalog.Session instructionSession = instructions.open(
                new AgentInstructionCatalog.SessionRequest(request.threadId(), request.workspaceRoot(),
                        request.jaHome(), request.trusted()));
        return new DefaultSession(request, instructionSession);
    }

    /** Turn 内串行使用的 Prompt 状态机；同步方法也防止未来 Tool 调度扩大后产生撕裂。 */
    private static final class DefaultSession implements AgentPromptSession {
        private final SessionRequest request;
        private final AgentInstructionCatalog.Session instructions;
        private final LinkedHashMap<String, AgentPromptAssembler.ActiveSkill> activeSkills = new LinkedHashMap<>();
        private String lastSummary = "";
        private AgentPromptSnapshot current;

        /** 构造首个无 summary 快照，使任何 Tool 边界都有可比较的 revision。 */
        private DefaultSession(SessionRequest request, AgentInstructionCatalog.Session instructions) {
            this.request = request;
            this.instructions = instructions;
            current = assemble(instructions.snapshot(), "");
        }

        /** 每次模型调用先刷新已知文件，再原子冻结 Prompt；精确窗口准入由 Provider token counting 负责。 */
        @Override
        public synchronized PreparedPrompt prepare(String summary, List<ToolSpec> tools) {
            lastSummary = Objects.requireNonNullElse(summary, "");
            Objects.requireNonNull(tools, "tools");
            current = assemble(instructions.refresh(), lastSummary);
            return new PreparedPrompt(current, request.baseBudget());
        }

        /**
         * structured file Tool 先执行目录发现；所有 Tool 都刷新已知规则，随后才比较生成 batch
         * 的 revision。READ_ONLY 可携带新上下文继续，EXTERNAL 必须让模型重新决策。
         */
        @Override
        public synchronized ToolGuard beforeTool(AgentTool.Invocation invocation,
                                                 ToolSideEffect sideEffect, String batchRevision) {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(batchRevision, "batchRevision");
            AgentInstructionCatalog.Snapshot instructionSnapshot;
            if (isStructuredFileTool(invocation.toolName())) {
                JsonValue rawPath = invocation.arguments().members().get("path");
                if (!(rawPath instanceof JsonText pathValue) || pathValue.value().isBlank()) {
                    return ToolGuard.deny("TOOL_ARGUMENTS_INVALID", "Tool path is unavailable");
                }
                String path = pathValue.value();
                AgentInstructionCatalog.Preflight preflight = instructions.preflight(invocation.toolName(), path);
                instructionSnapshot = preflight.snapshot();
                current = assemble(instructionSnapshot, lastSummary);
                ToolGuard discovery = discoveryDecision(preflight.decision());
                if (!discovery.proceed()) return discovery;
            } else {
                instructionSnapshot = instructions.refresh();
                current = assemble(instructionSnapshot, lastSummary);
            }
            if (instructionSnapshot.unsafe() && sideEffect == ToolSideEffect.EXTERNAL) {
                return ToolGuard.deny("INSTRUCTION_CONTEXT_UNAVAILABLE",
                        "Workspace guidance is unavailable; external side effects are blocked");
            }
            if (sideEffect == ToolSideEffect.EXTERNAL && !batchRevision.equals(current.revision())) {
                return ToolGuard.deny("INSTRUCTION_CONTEXT_REFRESH_REQUIRED",
                        "Workspace guidance changed; review the refreshed context before retrying");
            }
            return ToolGuard.allow();
        }

        /** Tool 后刷新能够捕获 AGENTS 自修改；失败结果不猜测文件是否保持旧内容。 */
        @Override
        public synchronized void afterTool(AgentTool.Invocation invocation, AgentTool.ToolResult result) {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(result, "result");
            if (isStructuredFileTool(invocation.toolName()) && result.outcome() == ToolOutcome.SUCCEEDED
                && invocation.arguments().members().get("path") instanceof JsonText path) {
                instructions.preflight("read", path.value());
            } else {
                instructions.refresh();
            }
            current = assemble(instructions.snapshot(), lastSummary);
        }

        /** 先在临时集合组装完整 SKILL.md，再原子发布 active 集，避免组装失败留下半激活状态。 */
        @Override
        public synchronized SkillActivation activateSkill(SkillCatalog.SkillDocument document) {
            Objects.requireNonNull(document, "document");
            if (!"SKILL.md".equals(document.resourcePath()) || document.truncated() || document.content().isBlank()) {
                return SkillActivation.rejected("SKILL_DOCUMENT_INVALID");
            }
            AgentPromptAssembler.ActiveSkill skill = new AgentPromptAssembler.ActiveSkill(
                    document.skillName(), document.revision(), document.content());
            AgentPromptAssembler.ActiveSkill existing = activeSkills.get(document.skillName());
            if (existing != null) {
                if (!existing.revision().equals(document.revision())) {
                    return SkillActivation.rejected("SKILL_SNAPSHOT_STALE");
                }
                return SkillActivation.activated(receipt(existing));
            }
            LinkedHashMap<String, AgentPromptAssembler.ActiveSkill> candidate = new LinkedHashMap<>(activeSkills);
            candidate.put(skill.name(), skill);
            AgentPromptSnapshot proposed = assemble(instructions.snapshot(), lastSummary,
                    List.copyOf(candidate.values()));
            activeSkills.put(skill.name(), skill);
            current = proposed;
            return SkillActivation.activated(receipt(skill));
        }

        /** 返回内存中最近一次完整组装 revision，不触发隐式 IO。 */
        @Override
        public synchronized String currentRevision() {
            return current.revision();
        }

        /** 把 Catalog 决策映射为稳定 Tool 错误；CONTINUE 不改变其它权限边界。 */
        private static ToolGuard discoveryDecision(AgentInstructionCatalog.Decision decision) {
            return switch (decision) {
                case CONTINUE -> ToolGuard.allow();
                case REFRESH_REQUIRED -> ToolGuard.deny("INSTRUCTION_CONTEXT_REFRESH_REQUIRED",
                        "Workspace guidance changed; review the refreshed context before retrying");
                case SCOPE_LIMIT_EXCEEDED -> ToolGuard.deny("INSTRUCTION_SCOPE_LIMIT_EXCEEDED",
                        "Thread instruction scope limit reached");
                case UNSAFE -> ToolGuard.deny("INSTRUCTION_CONTEXT_UNAVAILABLE",
                        "Workspace guidance could not be read safely");
            };
        }

        /** 只对 Ja 的三个结构化文件 Tool 解析 path，Shell 与 MCP 保持明确的不解析限制。 */
        private static boolean isStructuredFileTool(String toolName) {
            return "read".equals(toolName) || "edit".equals(toolName) || "write".equals(toolName);
        }

        /** 使用最终 Skill snapshot 顺序构建模型可见 catalog，未配置 Skill 已由 Resolver 过滤。 */
        private AgentPromptSnapshot assemble(AgentInstructionCatalog.Snapshot instructionSnapshot,
                                             String summary) {
            return assemble(instructionSnapshot, summary, List.copyOf(activeSkills.values()));
        }

        /** 把来源 Adapter 的不可变事实投影到纯 Prompt material，不泄露路径对象或 Repository。 */
        private AgentPromptSnapshot assemble(AgentInstructionCatalog.Snapshot instructionSnapshot,
                                             String summary,
                                             List<AgentPromptAssembler.ActiveSkill> active) {
            List<AgentPromptAssembler.SkillEntry> skills = request.skills().skills().stream()
                    .map(item -> new AgentPromptAssembler.SkillEntry(item.name(), item.description()))
                    .toList();
            List<String> diagnostics = new ArrayList<>(instructionSnapshot.diagnostics());
            if (!request.trusted()) diagnostics.add("Workspace project guidance and Skills are disabled by trust.");
            String revisionMaterial = "trusted=" + request.trusted()
                    + "\ninstructions=" + instructionSnapshot.revision()
                    + "\nskills=" + request.skills().revision();
            return AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                    request.baseBudget().contextWindowTokens(), request.environment(),
                    instructionSnapshot.guidance(), skills, summary, active, diagnostics, revisionMaterial));
        }

        /** 激活回执只暴露稳定身份，不复制正文。 */
        private static String receipt(AgentPromptAssembler.ActiveSkill skill) {
            return "activated skill " + skill.name() + '@' + skill.revision();
        }
    }
}
