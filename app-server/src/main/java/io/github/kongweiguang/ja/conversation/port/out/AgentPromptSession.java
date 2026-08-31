// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;

import java.util.List;
import java.util.Objects;

/** Turn 内唯一的 Prompt 状态 owner，绑定规则刷新、Skill 激活与副作用 revision 门禁。 */
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

    /** 把冻结 SKILL.md 正文加入当前 Turn；失败时不得改变 active Skill 集。 */
    SkillActivation activateSkill(SkillCatalog.SkillDocument document);

    /** 返回最近一次材料变化后的 Prompt revision，供同 batch 后续 Tool 做零副作用拒绝。 */
    String currentRevision();

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
