// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.prompt;

import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.instruction.AgentInstructionCatalog;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ContextTransform;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** 以 AGENTS catalog 和 Skill 元数据目录创建逐 Turn Prompt Session。 */
public final class DefaultAgentPromptSessionFactory implements AgentPromptSessionFactory {
    private final AgentInstructionCatalog instructions;
    private final List<ContextTransform> contextTransforms;

    /**
     * 共享 catalog 只持有无状态 NIO 策略和持久仓储；变换器在组合根一次冻结，逐 Turn 状态留在 Session。
     */
    public DefaultAgentPromptSessionFactory(
            AgentInstructionCatalog instructions, List<ContextTransform> contextTransforms) {
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.contextTransforms = freezeTransforms(contextTransforms);
    }

    /** 将配置代际输入映射到独占 Session，禁止不同 Turn 共享激活 Skill 或 Prompt revision。 */
    @Override
    public AgentPromptSession open(SessionRequest request) {
        Objects.requireNonNull(request, "request");
        AgentInstructionCatalog.Session instructionSession = instructions.open(
                new AgentInstructionCatalog.SessionRequest(request.threadId(), request.workspaceRoot(),
                        request.jaHome(), request.trusted()));
        return new DefaultSession(request, instructionSession, contextTransforms);
    }

    /** 固定确定顺序并拒绝重复身份，避免同一变换器因容器枚举漂移而改变 Prompt revision。 */
    private static List<ContextTransform> freezeTransforms(List<ContextTransform> transforms) {
        List<ContextTransform> ordered = new ArrayList<>(List.copyOf(
                Objects.requireNonNull(transforms, "contextTransforms")));
        HashSet<String> identities = new HashSet<>();
        for (ContextTransform transform : ordered) {
            if (transform == null) {
                throw new IllegalArgumentException("contextTransforms must not contain null values");
            }
            String identity = ContractChecks.identifier(transform.id(), "context transform id");
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("duplicate context transform identity");
            }
        }
        ordered.sort(Comparator.comparingInt(ContextTransform::order).thenComparing(ContextTransform::id));
        return List.copyOf(ordered);
    }

    /** Turn 内串行使用的 Prompt 状态机；私有锁防止外部能力引用参与 monitor 协议。 */
    private static final class DefaultSession implements AgentPromptSession {
        private final SessionRequest request;
        private final AgentInstructionCatalog.Session instructions;
        private final List<ContextTransform> contextTransforms;
        private final Object stateLock = new Object();
        private final LinkedHashMap<String, AgentPromptAssembler.ActiveSkill> activeSkills = new LinkedHashMap<>();
        private long skillVersion;
        private String lastSummary = "";
        private AgentPromptSnapshot current;

        /** 构造首个无 summary 快照，使任何 Tool 边界都有可比较的 revision。 */
        private DefaultSession(SessionRequest request, AgentInstructionCatalog.Session instructions,
                               List<ContextTransform> contextTransforms) {
            this.request = request;
            this.instructions = instructions;
            this.contextTransforms = contextTransforms;
            current = assemble(instructions.snapshot(), "");
        }

        /** 每次模型调用先刷新已知文件，再原子冻结 Prompt；精确窗口准入由 Provider token counting 负责。 */
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
        }

        /** Tool 后刷新能够捕获 AGENTS 自修改；失败结果不猜测文件是否保持旧内容。 */
        @Override
        public void afterTool(AgentTool.Invocation invocation, AgentTool.ToolResult result) {
            synchronized (stateLock) {
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
        }

        /** Tool 选 Skill 时先解析稳定 ID，再原子发布正文，避免名称成为恢复身份。 */
        @Override
        public SkillActivation activateSkill(SkillCatalog.SkillDocument document) {
            synchronized (stateLock) {
                Objects.requireNonNull(document, "document");
                if (!"SKILL.md".equals(document.resourcePath()) || document.truncated()
                        || document.content().isBlank()) {
                    return SkillActivation.rejected("SKILL_DOCUMENT_INVALID");
                }
                String skillId = skillIdForName(document.skillName());
                if (skillId == null) return SkillActivation.rejected("SKILL_UNAVAILABLE");
                AgentPromptAssembler.ActiveSkill skill = new AgentPromptAssembler.ActiveSkill(
                        document.skillName(), document.content());
                AgentPromptAssembler.ActiveSkill existing = activeSkills.get(skillId);
                if (skill.equals(existing)) {
                    return SkillActivation.activated(receipt(existing));
                }
                LinkedHashMap<String, AgentPromptAssembler.ActiveSkill> candidate =
                        new LinkedHashMap<>(activeSkills);
                candidate.put(skillId, skill);
                AgentPromptSnapshot proposed = assemble(instructions.snapshot(), lastSummary,
                        List.copyOf(candidate.values()));
                activeSkills.put(skillId, skill);
                current = proposed;
                skillVersion++;
                return SkillActivation.activated(receipt(skill));
            }
        }

        /** 身份来自冻结配置代际，名称或默认 `skill_` 拼接都不能替代显式映射。 */
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

        /** 全部实时读取和 Prompt 组装先形成不可变候选；SQLite 消费成功前不得改变 active set。 */
        @Override
        public SkillReplacement prepareSkillReplacement(List<String> skillIds) {
            synchronized (stateLock) {
                validateSkillReferences(skillIds);
                LinkedHashMap<String, AgentPromptAssembler.ActiveSkill> candidate = new LinkedHashMap<>();
                try {
                    for (String skillId : skillIds) {
                        String name = request.skillNamesById().get(skillId);
                        SkillCatalog.SkillDocument document = request.skillCatalog().read(request.skills(),
                                new SkillCatalog.SkillReadRequest(name, "SKILL.md", 4_000_000));
                        if (!"SKILL.md".equals(document.resourcePath()) || document.truncated()
                                || document.content().isBlank()) {
                            throw SkillSelectionException.loadFailed();
                        }
                        candidate.put(skillId, new AgentPromptAssembler.ActiveSkill(name, document.content()));
                    }
                    AgentPromptSnapshot proposed = assemble(instructions.snapshot(), lastSummary,
                            List.copyOf(candidate.values()));
                    List<TurnExecutionState.ActiveSkill> references = candidate.keySet().stream()
                            .map(TurnExecutionState.ActiveSkill::new)
                            .toList();
                    return new DefaultSkillReplacement(skillVersion, candidate, proposed, references);
                } catch (SkillSelectionException failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    throw SkillSelectionException.loadFailed();
                }
            }
        }

        /** 返回内存中最近一次完整组装 revision，不触发隐式 IO。 */
        @Override
        public String currentRevision() {
            synchronized (stateLock) {
                return current.revision();
            }
        }

        /** 只导出已激活稳定 ID；恢复时重新读取正文，避免持久化或锁定历史 Skill 字节。 */
        @Override
        public List<TurnExecutionState.ActiveSkill> activeSkillReferences() {
            synchronized (stateLock) {
                return activeSkills.keySet().stream()
                        .map(TurnExecutionState.ActiveSkill::new)
                        .toList();
            }
        }

        /** 恢复按稳定 ID 实时读取当前 Skill；缺失或无效仍失败，但合法内容变化立即生效。 */
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

        /** 由显示名称反查冻结代际中的稳定 ID；仅 Tool 激活路径需要此映射。 */
        private String skillIdForName(String name) {
            return request.skillNamesById().entrySet().stream()
                    .filter(entry -> entry.getValue().equals(name))
                    .map(java.util.Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }

        /**
         * 候选捕获准备时的 Session 版本；Loop 串行 owner 使成功 CAS 后 commit 不会失败，
         * 版本门仍阻止未来误用陈旧候选覆盖 Tool 激活等已发布变化。
         */
        private final class DefaultSkillReplacement implements SkillReplacement {
            private final long expectedVersion;
            private final LinkedHashMap<String, AgentPromptAssembler.ActiveSkill> candidate;
            private final AgentPromptSnapshot proposed;
            private final List<TurnExecutionState.ActiveSkill> references;
            private boolean committed;

            /** 保存不可变候选，不在准备期触碰当前 Prompt。 */
            private DefaultSkillReplacement(long expectedVersion,
                                            LinkedHashMap<String, AgentPromptAssembler.ActiveSkill> candidate,
                                            AgentPromptSnapshot proposed,
                                            List<TurnExecutionState.ActiveSkill> references) {
                this.expectedVersion = expectedVersion;
                this.candidate = new LinkedHashMap<>(candidate);
                this.proposed = Objects.requireNonNull(proposed, "proposed");
                this.references = List.copyOf(references);
            }

            /** 返回消费成功后应持久化的 Prompt revision。 */
            @Override
            public String promptRevision() {
                return proposed.revision();
            }

            /** 返回与候选正文一一对应的稳定 Skill ID。 */
            @Override
            public List<TurnExecutionState.ActiveSkill> activeSkillReferences() {
                return references;
            }

            /** SQLite CAS 成功后一次发布候选；同一候选重复提交保持幂等。 */
            @Override
            public void commit() {
                synchronized (stateLock) {
                    if (committed) return;
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

        /** 使用发现时的 Skill 元数据顺序构建模型可见 catalog，正文不会在此被预读。 */
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
                    + "\ninstructions=" + instructionSnapshot.revision();
            ContextTransform.DerivedContext derivedContext = ContextTransform.DerivedContext.empty();
            for (ContextTransform transform : contextTransforms) {
                derivedContext = Objects.requireNonNull(transform.transform(derivedContext),
                        "context transform result");
            }
            return AgentPromptAssembler.assemble(new AgentPromptAssembler.Material(
                     request.baseBudget().contextWindowTokens(), request.environment(),
                    instructionSnapshot.guidance(), skills, summary, active, derivedContext,
                    diagnostics, revisionMaterial));
        }

        /** 激活回执只暴露稳定名称，不复制正文或制造内容版本承诺。 */
        private static String receipt(AgentPromptAssembler.ActiveSkill skill) {
            return "activated skill " + skill.name();
        }
    }

    /** Prompt 边界只暴露 Skill 不可用和实时加载失败两类，禁止上层识别文件系统异常文本。 */
    public static final class SkillSelectionException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final Code code;

        /** 稳定分类构造不保留路径、Skill 名称或底层异常。 */
        private SkillSelectionException(Code code) {
            super("skill selection failed", null, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** 当前冻结代际没有该已启用身份。 */
        public static SkillSelectionException unavailable() {
            return new SkillSelectionException(Code.SKILL_UNAVAILABLE);
        }

        /** 身份有效但 SKILL.md 实时读取或校验失败。 */
        public static SkillSelectionException loadFailed() {
            return new SkillSelectionException(Code.SKILL_LOAD_FAILED);
        }

        /** 返回 application 可穷举的稳定分类。 */
        public Code code() {
            return code;
        }

        /** 与 JA-RPC 错误目录一致的两态闭集。 */
        public enum Code {
            /** Skill ID 不属于当前冻结且已启用的配置代际。 */
            SKILL_UNAVAILABLE,
            /** Skill 身份有效，但当前 SKILL.md 无法安全完整读取。 */
            SKILL_LOAD_FAILED
        }
    }
}
