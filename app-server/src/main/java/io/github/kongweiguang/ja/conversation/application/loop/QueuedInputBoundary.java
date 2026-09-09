// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;

import java.util.List;
import java.util.Objects;

/**
 * 排队用户输入真正消费前的无副作用准入边界；Workspace 路径规则与 Skill 文件读取由外层 owner 注入，
 * Loop 只理解可消费或需要用户修复两种结果。
 */
@FunctionalInterface
public interface QueuedInputBoundary {
    /** 重新校验引用并准备本消息 Skill 集；返回值在 SQLite 消费成功前不得改变 Prompt Session。 */
    Prepared prepare(UserContent content);

    /**
     * 仅供不涉及引用与 Skill 的 Loop 单元测试显式使用，生产组合必须注入真实 owner。
     */
    static QueuedInputBoundary plainTextOnly() {
        return content -> {
            Objects.requireNonNull(content, "content");
            if (!content.workspaceReferences().isEmpty() || !content.skillIds().isEmpty()) {
                throw new IllegalStateException("structured references require a real queued input boundary");
            }
            return Prepared.noChange();
        };
    }

    /**
     * 消费候选携带将持久化的 Prompt 身份与延迟提交动作；noChange 仅供无结构化能力的 Loop 测试。
     */
    final class Prepared {
        private final String promptRevision;
        private final List<TurnExecutionState.ActiveSkill> activeSkills;
        private final Runnable commitAction;
        private final boolean changesPrompt;

        /** 复制稳定引用并隐藏延迟动作，防止调用方取得可变执行对象后绕过消费成功门。 */
        private Prepared(String promptRevision, List<TurnExecutionState.ActiveSkill> activeSkills,
                         Runnable commitAction, boolean changesPrompt) {
            this.commitAction = Objects.requireNonNull(commitAction, "commitAction");
            if (changesPrompt) {
                this.promptRevision = Objects.requireNonNull(promptRevision, "promptRevision");
                this.activeSkills = List.copyOf(Objects.requireNonNull(activeSkills, "activeSkills"));
            } else if (promptRevision != null || activeSkills != null) {
                throw new IllegalArgumentException("no-change queued boundary cannot replace Prompt material");
            } else {
                this.promptRevision = null;
                this.activeSkills = null;
            }
            this.changesPrompt = changesPrompt;
        }

        /** 生产边界使用延迟动作包装已完成实时读取的候选。 */
        public static Prepared replacement(String promptRevision,
                                           List<TurnExecutionState.ActiveSkill> activeSkills,
                                           Runnable commit) {
            return new Prepared(promptRevision, activeSkills, commit, true);
        }

        /** 仅供不具备结构化引用的 Loop 单元测试保留现有 Prompt。 */
        public static Prepared noChange() {
            return new Prepared(null, null, () -> { }, false);
        }

        /** Prompt revision 是准备阶段冻结的不可变身份，不暴露任何可变 owner。 */
        public String promptRevision() {
            return promptRevision;
        }

        /** 每次返回不可变快照，禁止调用方通过集合引用改写已校验的 Skill 集。 */
        public List<TurnExecutionState.ActiveSkill> activeSkills() {
            return activeSkills == null ? null : List.copyOf(activeSkills);
        }

        /** 标识候选是否应替换当前 Prompt material。 */
        public boolean changesPrompt() {
            return changesPrompt;
        }

        /** 只允许在 SQLite 消费成功后执行延迟动作，不把内部 Runnable 暴露给调用方。 */
        public void commit() {
            commitAction.run();
        }
    }

    /** 用稳定公开问题封装消费期失效，禁止 Loop 解析底层异常文本。 */
    class Rejected extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final transient InputQueue.Issue issue;

        /**
         * 问题文本已脱敏且有界，可直接持久化到队列修复入口；issue 只服务当前调用栈，
         * 不作为 Java 异常序列化合同的一部分。
         */
        public Rejected(InputQueue.Issue issue) {
            super(Objects.requireNonNull(issue, "issue").message());
            this.issue = issue;
        }

        /** 返回队列公开合同中的稳定错误事实。 */
        public InputQueue.Issue issue() {
            return issue;
        }
    }
}
