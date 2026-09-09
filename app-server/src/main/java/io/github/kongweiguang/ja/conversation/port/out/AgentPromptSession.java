// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;

import java.util.List;
import java.util.Objects;

/** Turn 内唯一的 Prompt 状态 owner，绑定规则刷新、Skill 按需激活与副作用 revision 门禁。 */
public interface AgentPromptSession {
    /**
     * 刷新当前规则并为一次 Provider 调用冻结 Prompt 与固定开销预算；summary 只进入返回快照，
     * 不进入持久消息或 Session 的跨 Turn 状态。
     */
    PreparedPrompt prepare(String summary, List<ToolSpec> tools);

    /** 在审批和 Tool 副作用之前执行路径发现与 batch revision 检查。 */
    ToolGuard beforeTool(AgentTool.Invocation invocation, ToolSideEffect sideEffect, String batchRevision);

    /** Tool 完成后刷新已知规则，使 Shell 或 AGENTS 自修改在下一模型调用前失效续传。 */
    void afterTool(AgentTool.Invocation invocation, AgentTool.ToolResult result);

    /** 把本次实时读取的 SKILL.md 正文加入当前 Turn；失败时不得改变 active Skill 集。 */
    SkillActivation activateSkill(SkillCatalog.SkillDocument document);

    /** 只校验本代际可选身份，不读取正文或改变当前消息的 active set。 */
    void validateSkillReferences(List<String> skillIds);

    /**
     * 在新用户消息边界实时读取全部 SKILL.md 并生成两阶段替换；准备阶段不得改变 Session，
     * 只有队列输入通过持久化 CAS 后才能 commit，避免并发编辑或删除把未消费 Skill 泄漏给下一模型调用。
     */
    SkillReplacement prepareSkillReplacement(List<String> skillIds);

    /** 首条消息没有排队 CAS，准备完成后立即提交；空列表明确清除上一条消息的 Skill。 */
    default void replaceActiveSkills(List<String> skillIds) {
        prepareSkillReplacement(skillIds).commit();
    }

    /** 返回最近一次材料变化后的 Prompt revision，供同 batch 后续 Tool 做零副作用拒绝。 */
    String currentRevision();

    /** 返回当前已激活 Skill 的稳定引用，Tool settlement 必须与最新 Prompt revision 一起持久化。 */
    List<TurnExecutionState.ActiveSkill> activeSkillReferences();

    /** 按稳定 skillId 重新读取当前 Skill 正文并恢复 Prompt，不把历史内容 revision 当成继续执行前提。 */
    void restoreActiveSkills(String summary, List<TurnExecutionState.ActiveSkill> references);

    /**
     * 两阶段 Skill 候选只暴露将持久化的 Prompt 身份与稳定引用；commit 由 Prompt Session 保证幂等，
     * 且在成功前不会发布候选内容。
     */
    interface SkillReplacement {
        /** 返回候选 Prompt revision，供消费事务与新的 USER message 同步持久化。 */
        String promptRevision();

        /** 返回候选 Skill 的稳定 ID，禁止将显示名称写入恢复状态。 */
        List<TurnExecutionState.ActiveSkill> activeSkillReferences();

        /** 在队列消费 CAS 成功后原子发布候选；重复调用不产生额外状态变化。 */
        void commit();
    }

    /** Prompt 快照与按该快照派生的 ContextBudget 必须作为同一原子结果使用。 */
    record PreparedPrompt(AgentPromptSnapshot snapshot, ContextBudget budget) {
        /** 拒绝拆散来源不同的 Prompt 与预算。 */
        public PreparedPrompt {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(budget, "budget");
        }
    }

    /** Tool 前置门禁只返回继续或稳定拒绝，不执行审批或文件副作用。 */
    record ToolGuard(boolean proceed, String code, String message) {
        /** 规则未变化且 Session 安全时继续。 */
        public static ToolGuard allow() {
            return new ToolGuard(true, null, null);
        }

        /** 把刷新、上限或 unsafe 状态映射为普通 Tool failure。 */
        public static ToolGuard deny(String code, String message) {
            return new ToolGuard(false, Objects.requireNonNull(code, "code"),
                    Objects.requireNonNull(message, "message"));
        }
    }

    /** Skill 激活结果携带给模型的短回执；正文只进入后续动态 System。 */
    record SkillActivation(boolean activated, String receipt, String errorCode) {
        /** 激活失败必须有稳定错误码，成功不得伪装错误。 */
        public SkillActivation {
            receipt = Objects.requireNonNullElse(receipt, "");
            if (activated == (errorCode != null)) {
                throw new IllegalArgumentException("invalid Skill activation result");
            }
        }

        /** 返回成功或幂等激活回执。 */
        public static SkillActivation activated(String receipt) {
            return new SkillActivation(true, receipt, null);
        }

        /** 返回不改变 Session 的预算或文档失败。 */
        public static SkillActivation rejected(String code) {
            return new SkillActivation(false, "", Objects.requireNonNull(code, "code"));
        }
    }
}
