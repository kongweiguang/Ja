// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDocument;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.service.AgentInstructionCatalog;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRootChangePort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 以 AGENTS catalog 和 Skill 元数据目录创建逐 Turn Prompt Session。
 */
public final class DefaultAgentPromptSessionFactory implements AgentPromptSessionFactory {
    private final AgentInstructionCatalog instructions;
    private final WorkspaceRootChangePort rootChanges;

    /**
     * 工厂只持有权威指令目录；请求 Prompt 由指令、环境、摘要和 Skill 组成，避免无来源校验的自由变换。
     */
    public DefaultAgentPromptSessionFactory(AgentInstructionCatalog instructions) {
        this(instructions, WorkspaceRootChangePort.empty());
    }

    /**
     * 同时注入进程内根变更账本；旧路径提示只在请求意图提交后按 Thread 消费。
     */
    public DefaultAgentPromptSessionFactory(AgentInstructionCatalog instructions,
                                            WorkspaceRootChangePort rootChanges) {
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.rootChanges = Objects.requireNonNull(rootChanges, "rootChanges");
    }

    /**
     * 将配置代际输入映射到独占 Session，禁止不同 Turn 共享激活 Skill 或 Prompt revision。
     */
    @Override
    public AgentPromptSession open(SessionRequest request) {
        Objects.requireNonNull(request, "request");
        AgentInstructionCatalog.Session instructionSession = instructions.open(
            new AgentInstructionCatalog.SessionRequest(request.threadId(), request.workspaceRoot(),
                request.jaHome(), request.trusted()));
        return new DefaultSession(request, instructionSession,
            rootChanges.pending(request.workspaceId(), request.threadId()).orElse(null), rootChanges);
    }

    /**
     * Turn 内串行使用的 Prompt 状态机；私有锁防止外部能力引用参与 monitor 协议。
     */
    private static final class DefaultSession implements AgentPromptSession {
        private final SessionRequest request;
        private final AgentInstructionCatalog.Session instructions;
        private final WorkspaceRootChangePort rootChanges;
        private final Object stateLock = new Object();
        private final LinkedHashMap<String, ActivatedSkill> activeSkills = new LinkedHashMap<>();
        private long skillVersion;
        private String lastSummary = "";
        private AgentPromptSnapshot current;
        private WorkspaceRootChangePort.Change pendingRootChange;

        /**
         * 首个快照只根据冻结的业务输入构造，确保任意 Tool 边界都有可比较的 Prompt revision。
         */
        private DefaultSession(SessionRequest request, AgentInstructionCatalog.Session instructions,
                               WorkspaceRootChangePort.Change pendingRootChange,
                               WorkspaceRootChangePort rootChanges) {
            this.request = request;
            this.instructions = instructions;
            this.pendingRootChange = pendingRootChange;
            this.rootChanges = rootChanges;
            current = assemble(instructions.snapshot(), "");
        }

        /**
         * 把 Catalog 决策映射为稳定 Tool 错误；CONTINUE 不改变其它权限边界。
         */
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

        /**
         * 只对 Ja 的三个结构化文件 Tool 解析 path，Shell 与 MCP 保持明确的不解析限制。
         */
        private static boolean isStructuredFileTool(String toolName) {
            return "read".equals(toolName) || "edit".equals(toolName) || "write".equals(toolName);
        }

        /**
         * 正文投影与持久化指纹一起保留，确保 Prompt 组装不会丢失内容审计身份。
         */
        private static List<AgentPromptAssembler.ActiveSkill> promptSkills(
            Map<String, ActivatedSkill> active) {
            return active.values().stream().map(ActivatedSkill::promptSkill).toList();
        }

        /**
         * 激活回执只暴露稳定名称，不复制正文或制造内容版本承诺。
         */
        private static String receipt(AgentPromptAssembler.ActiveSkill skill) {
            return "activated skill " + skill.name();
        }

        /**
         * 每次模型调用先刷新已知文件，再原子冻结 Prompt；精确窗口准入由 Provider token counting 负责。
         */
        @Override
        public PreparedPrompt prepare(String summary, List<ToolSpec> tools) {
            synchronized (stateLock) {
                lastSummary = Objects.requireNonNullElse(summary, "");
                Objects.requireNonNull(tools, "tools");
                current = assemble(instructions.refresh(), lastSummary);
                return new PreparedPrompt(current, request.baseBudget());
            }
        }

        /**
         * 持久请求意图提交后才消费根变更；预算预检和未提交请求不会让 Thread 丢失提示。
         */
        @Override
        public void requestCommitted() {
            synchronized (stateLock) {
                if (pendingRootChange == null) {
                    return;
                }
                rootChanges.consume(request.workspaceId(), request.threadId(), pendingRootChange.revision());
                pendingRootChange = null;
                current = assemble(instructions.snapshot(), lastSummary);
            }
        }

        /**
         * structured file Tool 先执行目录发现；所有 Tool 都刷新已知规则，随后才比较生成 batch
         * 的 revision。READ_ONLY 可携带新上下文继续，EXTERNAL 必须让模型重新决策。
         */
        @Override
        public ToolGuard beforeTool(AgentTool.Invocation invocation,
                                    ToolSideEffect sideEffect, String batchRevision) {
            synchronized (stateLock) {
                Objects.requireNonNull(invocation, "invocation");
                Objects.requireNonNull(sideEffect, "sideEffect");
                Objects.requireNonNull(batchRevision, "batchRevision");
                AgentInstructionCatalog.Snapshot instructionSnapshot;
                if (isStructuredFileTool(invocation.toolName())) {
                    JsonValue rawPath = invocation.arguments().members().get("path");
                    if (!(rawPath instanceof JsonText(String path)) || path.isBlank()) {
                        return ToolGuard.deny("TOOL_ARGUMENTS_INVALID", "Tool path is unavailable");
                    }
                    AgentInstructionCatalog.Preflight preflight = instructions.preflight(invocation.toolName(), path);
                    instructionSnapshot = preflight.snapshot();
                    current = assemble(instructionSnapshot, lastSummary);
                    ToolGuard discovery = discoveryDecision(preflight.decision());
                    if (!discovery.proceed()) {
                        return discovery;
                    }
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
        }

        /**
         * Tool 后刷新能够捕获 AGENTS 自修改；失败结果不猜测文件是否保持旧内容。
         */
        @Override
        public void afterTool(AgentTool.Invocation invocation, AgentTool.ToolResult result) {
            synchronized (stateLock) {
                Objects.requireNonNull(invocation, "invocation");
                Objects.requireNonNull(result, "result");
                if (isStructuredFileTool(invocation.toolName()) && result.outcome() == ToolOutcome.SUCCEEDED
                    && invocation.arguments().members().get("path") instanceof JsonText(String value)) {
                    instructions.preflight("read", value);
                } else {
                    instructions.refresh();
                }
                current = assemble(instructions.snapshot(), lastSummary);
            }
        }

        /**
         * Tool 选 Skill 时先解析稳定 ID，再原子发布正文，避免名称成为恢复身份。
         */
        @Override
        public SkillActivation activateSkill(SkillDocument document) {
            synchronized (stateLock) {
                Objects.requireNonNull(document, "document");
                if (!"SKILL.md".equals(document.resourcePath()) || document.truncated()
                    || document.content().isBlank()) {
                    return SkillActivation.rejected("SKILL_DOCUMENT_INVALID");
                }
                String skillId = skillIdForName(document.skillName());
                if (skillId == null) {
                    return SkillActivation.rejected("SKILL_UNAVAILABLE");
                }
                AgentPromptAssembler.ActiveSkill promptSkill = new AgentPromptAssembler.ActiveSkill(
                    document.skillName(), document.content());
                ActivatedSkill skill = new ActivatedSkill(promptSkill, document.contentFingerprint());
                ActivatedSkill existing = activeSkills.get(skillId);
                if (skill.equals(existing)) {
                    return SkillActivation.activated(receipt(existing.promptSkill()));
                }
                LinkedHashMap<String, ActivatedSkill> candidate =
                    new LinkedHashMap<>(activeSkills);
                candidate.put(skillId, skill);
                AgentPromptSnapshot proposed = assemble(instructions.snapshot(), lastSummary,
                    promptSkills(candidate));
                activeSkills.put(skillId, skill);
                current = proposed;
                skillVersion++;
                return SkillActivation.activated(receipt(promptSkill));
            }
        }

        /**
         * 身份来自冻结配置代际，名称或默认 `skill_` 拼接都不能替代显式映射。
         */
        @Override
        public void validateSkillReferences(List<String> skillIds) {
            synchronized (stateLock) {
                List<String> ids = List.copyOf(Objects.requireNonNull(skillIds, "skillIds"));
                if (ids.size() != new java.util.LinkedHashSet<>(ids).size()
                    || ids.stream().anyMatch(id -> !request.skillNamesById().containsKey(id))) {
                    throw SkillSelectionException.unavailable();
                }
            }
        }

        /**
         * 全部实时读取和 Prompt 组装先形成不可变候选；SQLite 消费成功前不得改变 active set。
         */
        @Override
        public SkillReplacement prepareSkillReplacement(List<String> skillIds) {
            synchronized (stateLock) {
                validateSkillReferences(skillIds);
                LinkedHashMap<String, ActivatedSkill> candidate = new LinkedHashMap<>();
                try {
                    for (String skillId : skillIds) {
                        String name = request.skillNamesById().get(skillId);
                        SkillDocument document = request.skillCatalog().read(request.skills(),
                            new SkillReadRequest(name, "SKILL.md", 4_000_000));
                        if (!"SKILL.md".equals(document.resourcePath()) || document.truncated()
                            || document.content().isBlank()) {
                            throw SkillSelectionException.loadFailed();
                        }
                        candidate.put(skillId, new ActivatedSkill(
                            new AgentPromptAssembler.ActiveSkill(name, document.content()),
                            document.contentFingerprint()));
                    }
                    AgentPromptSnapshot proposed = assemble(instructions.snapshot(), lastSummary,
                        promptSkills(candidate));
                    List<TurnExecutionState.ActiveSkill> references = candidate.entrySet().stream()
                        .map(entry -> new TurnExecutionState.ActiveSkill(entry.getKey(),
                            Optional.of(entry.getValue().contentFingerprint())))
                        .toList();
                    return new DefaultSkillReplacement(skillVersion, candidate, proposed, references);
                } catch (SkillSelectionException failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    throw SkillSelectionException.loadFailed(failure);
                }
            }
        }

        /**
         * 返回内存中最近一次完整组装 revision，不触发隐式 IO。
         */
        @Override
        public String currentRevision() {
            synchronized (stateLock) {
                return current.revision();
            }
        }

        /**
         * 只导出已激活稳定 ID；恢复时重新读取正文，避免持久化或锁定历史 Skill 字节。
         */
        @Override
        public List<TurnExecutionState.ActiveSkill> activeSkillReferences() {
            synchronized (stateLock) {
                return activeSkills.entrySet().stream()
                    .map(entry -> new TurnExecutionState.ActiveSkill(entry.getKey(),
                        Optional.of(entry.getValue().contentFingerprint())))
                    .toList();
            }
        }

        /**
         * 恢复按稳定 ID 实时读取当前 Skill；缺失或无效仍失败，但合法内容变化立即生效。
         */
        @Override
        public void restoreActiveSkills(
            String summary, List<TurnExecutionState.ActiveSkill> references) {
            synchronized (stateLock) {
                lastSummary = Objects.requireNonNull(summary, "summary");
                references = List.copyOf(Objects.requireNonNull(references, "references"));
                if (!activeSkills.isEmpty()) {
                    throw new IllegalStateException("Prompt Session already contains active Skills");
                }
                prepareSkillReplacement(references.stream().map(TurnExecutionState.ActiveSkill::skillId).toList())
                    .commit();
                current = assemble(instructions.refresh(), lastSummary);
            }
        }

        /**
         * 由显示名称反查冻结代际中的稳定 ID；仅 Tool 激活路径需要此映射。
         */
        private String skillIdForName(String name) {
            return request.skillNamesById().entrySet().stream()
                .filter(entry -> entry.getValue().equals(name))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        }

        /**
         * 使用发现时的 Skill 元数据顺序构建模型可见 catalog，正文不会在此被预读。
         */
        private AgentPromptSnapshot assemble(AgentInstructionCatalog.Snapshot instructionSnapshot,
                                             String summary) {
            return assemble(instructionSnapshot, summary, promptSkills(activeSkills));
        }

        /**
         * 只把权威指令、环境与 Skill 投影到纯 Prompt material，不泄露路径对象或 Repository。
         */
        private AgentPromptSnapshot assemble(AgentInstructionCatalog.Snapshot instructionSnapshot,
                                             String summary,
                                             List<AgentPromptAssembler.ActiveSkill> active) {
            List<AgentPromptAssembler.SkillEntry> skills = request.skills().skills().stream()
                .map(item -> new AgentPromptAssembler.SkillEntry(item.name(), item.description()))
                .toList();
            List<String> diagnostics = new ArrayList<>(instructionSnapshot.diagnostics());
            if (!request.trusted()) {
                diagnostics.add("Workspace project guidance and Skills are disabled by trust.");
            }
            String revisionMaterial = "trusted=" + request.trusted()
                                      + "\ninstructions=" + instructionSnapshot.revision();
            String environment = request.environment() + "\n\n当前项目工作目录为 `"
                    + request.workspaceRoot() + "`。历史绝对路径保持原目标，相对路径以当前目录为准。";
            if (pendingRootChange != null) {
                environment += "\n工作目录已从 `" + pendingRootChange.oldRoot() + "` 重绑到 `"
                        + pendingRootChange.newRoot() + "`。";
            }
            return AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                request.baseBudget().contextWindowTokens(), environment,
                instructionSnapshot.guidance(), skills, summary, active, diagnostics, revisionMaterial));
        }

        /**
         * 将实际 Prompt 正文与来源指纹绑定，后续替换只能提交同一份内容事实。
         *
         * @param promptSkill        用于提示词的 Skill
         * @param contentFingerprint 内容指纹
         */
        private record ActivatedSkill(AgentPromptAssembler.ActiveSkill promptSkill,
                                      String contentFingerprint) {
            /**
             * 拒绝缺少 SHA-256 指纹的激活状态，避免保存无法追溯实际正文的 Skill 引用。
             */
            private ActivatedSkill {
                Objects.requireNonNull(promptSkill, "promptSkill");
                if (contentFingerprint == null || !contentFingerprint.matches("sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("invalid active Skill content fingerprint");
                }
            }
        }

        /**
         * 候选捕获准备时的 Session 版本；Loop 串行 owner 使成功 CAS 后 commit 不会失败，
         * 版本门仍阻止未来误用陈旧候选覆盖 Tool 激活等已发布变化。
         */
        private final class DefaultSkillReplacement implements SkillReplacement {
            private final long expectedVersion;
            private final LinkedHashMap<String, ActivatedSkill> candidate;
            private final AgentPromptSnapshot proposed;
            private final List<TurnExecutionState.ActiveSkill> references;
            private boolean committed;

            /**
             * 保存不可变候选，不在准备期触碰当前 Prompt。
             */
            private DefaultSkillReplacement(long expectedVersion,
                                            LinkedHashMap<String, ActivatedSkill> candidate,
                                            AgentPromptSnapshot proposed,
                                            List<TurnExecutionState.ActiveSkill> references) {
                this.expectedVersion = expectedVersion;
                this.candidate = new LinkedHashMap<>(candidate);
                this.proposed = Objects.requireNonNull(proposed, "proposed");
                this.references = List.copyOf(references);
            }

            /**
             * 返回消费成功后应持久化的 Prompt revision。
             */
            @Override
            public String promptRevision() {
                return proposed.revision();
            }

            /**
             * 返回与候选正文一一对应的稳定 Skill ID。
             */
            @Override
            public List<TurnExecutionState.ActiveSkill> activeSkillReferences() {
                return references;
            }

            /**
             * SQLite CAS 成功后一次发布候选；同一候选重复提交保持幂等。
             */
            @Override
            public void commit() {
                synchronized (stateLock) {
                    if (committed) {
                        return;
                    }
                    if (skillVersion != expectedVersion) {
                        throw new IllegalStateException("Prompt Skill replacement became stale");
                    }
                    activeSkills.clear();
                    activeSkills.putAll(candidate);
                    current = proposed;
                    skillVersion++;
                    committed = true;
                }
            }
        }
    }

    /**
     * Prompt 边界只暴露 Skill 不可用和实时加载失败两类，禁止上层识别文件系统异常文本。
     */
    public static final class SkillSelectionException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final Code code;

        /**
         * 稳定分类不保留路径或 Skill 名称；cause 仅供进程内诊断。
         */
        private SkillSelectionException(Code code, Throwable cause) {
            super("skill selection failed", cause, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        /**
         * 当前冻结代际没有该已启用身份。
         */
        public static SkillSelectionException unavailable() {
            return new SkillSelectionException(Code.SKILL_UNAVAILABLE, null);
        }

        /**
         * 身份有效但 SKILL.md 实时读取或校验失败。
         */
        public static SkillSelectionException loadFailed() {
            return loadFailed(null);
        }

        /**
         * 保留 Skill 读取或校验故障链，但继续通过 code 隐藏用户路径与文件内容。
         */
        public static SkillSelectionException loadFailed(Throwable cause) {
            return new SkillSelectionException(Code.SKILL_LOAD_FAILED, cause);
        }

        /**
         * 返回 application 可穷举的稳定分类。
         */
        public Code code() {
            return code;
        }

        /**
         * 与 JA-RPC 错误目录一致的两态闭集。
         */
        public enum Code {
            /**
             * Skill ID 不属于当前冻结且已启用的配置代际。
             */
            SKILL_UNAVAILABLE,
            /**
             * Skill 身份有效，但当前 SKILL.md 无法安全完整读取。
             */
            SKILL_LOAD_FAILED
        }
    }
}
