// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.support;

import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;

import java.util.List;
import java.util.Objects;

/** 为非 Prompt 聚焦测试提供稳定快照，避免旧字符串 fixture 绕过当前 Session 边界。 */
public final class FixedAgentPromptSession implements AgentPromptSession {
    private static final String REVISION = "prompt_fixture";
    private final ContextBudget budget;
    private final AgentPromptSnapshot snapshot =
            new AgentPromptSnapshot("fixture system", REVISION, 4);

    /** 调用方显式提供预算，使上下文压缩测试仍可控制真实决策输入。 */
    public FixedAgentPromptSession(ContextBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /** 返回同一不可变快照；Tool schema 计量不属于这些非 Prompt 用例的断言范围。 */
    @Override
    public PreparedPrompt prepare(String summary, List<ToolSpec> tools) {
        return new PreparedPrompt(snapshot, budget);
    }

    /** 测试 fixture 不模拟规则变化，所有 Tool 均保持可执行。 */
    @Override
    public ToolGuard beforeTool(AgentTool.Invocation invocation, ToolSideEffect sideEffect, String batchRevision) {
        return ToolGuard.allow();
    }

    /** 非 Prompt 用例不持有可刷新文件状态。 */
    @Override
    public void afterTool(AgentTool.Invocation invocation, AgentTool.ToolResult result) {
    }

    /** Skill 激活不是这些用例的目标，但保持稳定成功回执以避免引入额外失败面。 */
    @Override
    public SkillActivation activateSkill(SkillCatalog.SkillDocument document) {
        return SkillActivation.activated("activated fixture skill");
    }

    /** 固定夹具不暴露 Skill 目录；只有空选择可通过身份校验。 */
    @Override
    public void validateSkillReferences(List<String> skillIds) {
        if (!skillIds.isEmpty()) {
            throw new IllegalStateException("fixed prompt fixture has no selectable Skills");
        }
    }

    /** 固定夹具返回不改变 revision 的两阶段候选，使队列 CAS miss 可验证为零 Prompt 副作用。 */
    @Override
    public SkillReplacement prepareSkillReplacement(List<String> skillIds) {
        validateSkillReferences(skillIds);
        return new SkillReplacement() {
            /** 固定候选沿用测试 revision。 */
            @Override public String promptRevision() { return REVISION; }
            /** 固定候选不包含 Skill。 */
            @Override public List<TurnExecutionState.ActiveSkill> activeSkillReferences() { return List.of(); }
            /** 固定候选没有可发布的动态状态。 */
            @Override public void commit() { }
        };
    }

    /** 固定夹具没有 Skill 文件系统，空集合仅表达清除且无需改变 revision。 */
    @Override
    public void replaceActiveSkills(List<String> skillIds) {
        validateSkillReferences(skillIds);
    }

    /** 返回固定 revision，使连续 Tool batch 能验证循环逻辑而不受文件 IO 干扰。 */
    @Override
    public String currentRevision() {
        return REVISION;
    }

    /** 非 Prompt 聚焦测试没有 Skill 正文 owner，因此始终返回空引用。 */
    @Override
    public List<TurnExecutionState.ActiveSkill> activeSkillReferences() {
        return List.of();
    }

    /** 固定夹具只接受空 Skill；非 Prompt 测试不模拟实时文件恢复。 */
    @Override
    public void restoreActiveSkills(
            String summary, List<TurnExecutionState.ActiveSkill> references) {
        if (!summary.isEmpty() || !references.isEmpty()) {
            throw new IllegalStateException("fixed prompt fixture cannot restore dynamic Skill state");
        }
    }
}
