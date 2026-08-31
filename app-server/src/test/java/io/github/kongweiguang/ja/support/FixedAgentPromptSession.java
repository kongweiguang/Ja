// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.support;

import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
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

    /** 返回固定 revision，使连续 Tool batch 能验证循环逻辑而不受文件 IO 干扰。 */
    @Override
    public String currentRevision() {
        return REVISION;
    }
}
