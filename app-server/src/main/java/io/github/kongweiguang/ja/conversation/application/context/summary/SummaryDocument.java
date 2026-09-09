// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.summary;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 版本化全量滚动摘要；每次生成结果完整替换上一版，不提供追加合并语义。
 */
public record SummaryDocument(
        List<Fact> goals,
        List<Fact> constraints,
        List<Fact> completedProgress,
        List<Fact> currentProgress,
        List<Fact> blockers,
        List<Fact> decisions,
        List<Fact> nextSteps,
        List<Fact> criticalFacts,
        List<Fact> files,
        List<Fact> pendingEffects,
        List<Retirement> retirements) {
    private static final int MAX_ITEMS = 512;
    private static final int MAX_TEXT = 64_000;

    /** 冻结全部分区并拒绝重复事实，防止滚动摘要在多次压缩中单调膨胀。 */
    public SummaryDocument {
        goals = facts(goals, "goals", 8);
        constraints = facts(constraints, "constraints", MAX_ITEMS);
        completedProgress = facts(completedProgress, "completedProgress", MAX_ITEMS);
        currentProgress = facts(currentProgress, "currentProgress", MAX_ITEMS);
        blockers = facts(blockers, "blockers", MAX_ITEMS);
        decisions = facts(decisions, "decisions", MAX_ITEMS);
        nextSteps = facts(nextSteps, "nextSteps", MAX_ITEMS);
        criticalFacts = facts(criticalFacts, "criticalFacts", MAX_ITEMS);
        files = facts(files, "files", MAX_ITEMS);
        pendingEffects = facts(pendingEffects, "pendingEffects", MAX_ITEMS);
        retirements = retirements(retirements);
    }

    /** 表达尚无摘要事实的起点；退休记录也不跨无事实 Checkpoint 保留。 */
    public static SummaryDocument empty() {
        return new SummaryDocument(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** 只有至少一个有效事实才构成可提交摘要，单独退休清单不能替代恢复信息。 */
    public boolean hasNoFacts() {
        return allFacts().isEmpty();
    }

    /** 返回全部当前有效事实，验证器据此检查来源、退休和重复覆盖。 */
    public List<Fact> allFacts() {
        List<Fact> result = new ArrayList<>();
        result.addAll(goals);
        result.addAll(constraints);
        result.addAll(completedProgress);
        result.addAll(currentProgress);
        result.addAll(blockers);
        result.addAll(decisions);
        result.addAll(nextSteps);
        result.addAll(criticalFacts);
        result.addAll(files);
        result.addAll(pendingEffects);
        return List.copyOf(result);
    }

    /**
     * 构造确定性 evidence ledger：保留旧有效事实，并把新淘汰用户文本和 Tool 错误作为关键事实。
     */
    public static SummaryDocument evidenceLedger(
            SummaryDocument previous, List<ContextMessage> evictedMessages) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(evictedMessages, "evictedMessages");
        List<Fact> evidence = new ArrayList<>(previous.criticalFacts());
        for (ContextMessage message : evictedMessages) {
            for (ContextMessage.Block block : message.blocks()) {
                if (message.role() == ContextMessage.Role.USER
                    && block instanceof ContextMessage.TextBlock text) {
                    evidence.add(new Fact(message.role().name().toLowerCase(java.util.Locale.ROOT)
                            + ": " + boundedEvidence(text.value()), message.ordinal()));
                } else if (message.role() == ContextMessage.Role.USER
                           && block instanceof ContextMessage.AttachmentBlock attachment) {
                    evidence.add(new Fact("user attachment: " + attachment.attachmentId(), message.ordinal()));
                } else if (block instanceof ContextMessage.ToolResultBlock result
                           && result.output().error() != null) {
                    evidence.add(new Fact("Tool " + result.name() + " failed; artifact="
                            + Objects.toString(result.output().artifactReference(), "unavailable"),
                            message.ordinal()));
                }
            }
        }
        return new SummaryDocument(previous.goals(), previous.constraints(), previous.completedProgress(),
                previous.currentProgress(), previous.blockers(), previous.decisions(), previous.nextSteps(),
                distinctFacts(evidence), previous.files(), previous.pendingEffects(), List.of());
    }

    /** 按固定标题序列化当前有效事实；退休清单只用于验证和审计，不进入普通 Agent Prompt。 */
    public String toPromptText() {
        StringBuilder text = new StringBuilder("Ja Context Summary v1\n");
        append(text, "Goals", goals);
        append(text, "Effective Constraints", constraints);
        append(text, "Completed Progress", completedProgress);
        append(text, "Current Progress", currentProgress);
        append(text, "Blockers", blockers);
        append(text, "Decisions", decisions);
        append(text, "Next Steps", nextSteps);
        append(text, "Critical Facts", criticalFacts);
        append(text, "Files", files);
        append(text, "Unfinished Side Effects or Approvals", pendingEffects);
        return text.toString();
    }

    /** 使用来源 ordinal 标记每条事实，使恢复内容可追溯到不可变历史。 */
    public record Fact(String text, long sourceOrdinal) {
        /** 拒绝空事实、非法来源和控制字符，避免无法验证的模型输出进入 Checkpoint。 */
        public Fact {
            text = bounded(text, "fact");
            if (sourceOrdinal < 1) throw new IllegalArgumentException("fact source ordinal is invalid");
        }
    }

    /** 显式记录旧事实为何不再有效，防止 blocker、next step 和 pending effect 永久残留。 */
    public record Retirement(String text, long sourceOrdinal, Status status) {
        /** 要求退休项精确指向旧事实文本与来源，并选择封闭状态。 */
        public Retirement {
            text = bounded(text, "retirement");
            if (sourceOrdinal < 1 || status == null) {
                throw new IllegalArgumentException("retirement source or status is invalid");
            }
        }
    }

    /** 旧事实只允许以解决、取代或取消三种明确原因退休。 */
    public enum Status {
        /** 事实对应的问题或工作已经完成，不应继续出现在活动上下文。 */
        RESOLVED,
        /** 新事实已经取代旧事实，二者不能同时作为当前真相。 */
        SUPERSEDED,
        /** 用户或运行时明确取消了旧事实对应的意图或副作用。 */
        CANCELLED
    }

    /** 冻结一个事实分区并拒绝同分区重复，避免多轮模型输出制造摘要膨胀。 */
    private static List<Fact> facts(List<Fact> source, String field, int maximum) {
        Objects.requireNonNull(source, field);
        if (source.size() > maximum) throw new IllegalArgumentException(field + " exceeds item bound");
        List<Fact> values = List.copyOf(source);
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " contains duplicate facts");
        }
        return values;
    }

    /** 冻结退休清单并拒绝重复退休证据。 */
    private static List<Retirement> retirements(List<Retirement> source) {
        Objects.requireNonNull(source, "retirements");
        if (source.size() > MAX_ITEMS || new LinkedHashSet<>(source).size() != source.size()) {
            throw new IllegalArgumentException("invalid retirements");
        }
        return List.copyOf(source);
    }

    /** 稳定去重确定性 ledger，优先保留旧事实与更早证据顺序。 */
    private static List<Fact> distinctFacts(List<Fact> source) {
        return List.copyOf(new LinkedHashSet<>(source).stream().limit(MAX_ITEMS).toList());
    }

    /** 将极长原文截为有界证据而不按 UTF-16 拆坏补充字符。 */
    private static String boundedEvidence(String value) {
        int count = value.codePointCount(0, value.length());
        if (count <= 4_096) return value;
        return value.substring(value.offsetByCodePoints(0, count - 4_096));
    }

    /** 对事实正文实施统一硬上限并拒绝 NUL。 */
    private static String bounded(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** 按来源 ordinal 输出事实，既便于模型使用，也为人工诊断保留可追溯边界。 */
    private static void append(StringBuilder target, String title, List<Fact> values) {
        target.append(title).append(":\n");
        for (Fact fact : values) {
            target.append("- [source:").append(fact.sourceOrdinal()).append("] ")
                    .append(fact.text()).append('\n');
        }
    }
}
