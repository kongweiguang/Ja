// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.turn;

import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Turn 即 Operation 的完整持久执行游标；任一非终态提交都必须整体替换该值，禁止从历史猜测下一步。
 */
public sealed interface TurnExecutionState permits TurnExecutionState.Ready,
        TurnExecutionState.ProviderPending, TurnExecutionState.Tools {
    /** 首版执行状态格式；任何非当前版本都按损坏状态失败关闭，不猜测磁盘语义。 */
    int SCHEMA_VERSION = 1;

    /** 所有状态共享的不可变恢复基线和累计游标。 */
    Common common();

    /**
     * 在挂起边界冻结剩余活动预算；恢复会重新生成绝对截止线，但不会重置累计游标。
     */
    default TurnExecutionState withActiveBudget(Duration budget) {
        Common updated = common().withActiveBudget(budget);
        return switch (this) {
            case Ready ready -> new Ready(updated, ready.next(), ready.summary());
            case ProviderPending pending -> new ProviderPending(updated, pending.requestId(), pending.messageId(),
                    pending.purpose(), pending.profile(), pending.envelopeFingerprint(),
                    new Ready(updated, pending.resume().next(), pending.resume().summary()));
            case Tools tools -> new Tools(updated, tools.batchId(), tools.assistantMessageId(),
                    tools.firstOrdinal(), tools.lastOrdinal(), tools.nextOrdinal());
        };
    }

    /** 恢复时只替换本轮活动截止线，保留已冻结的剩余预算和其它执行事实。 */
    default TurnExecutionState withDeadline(Instant deadline) {
        Common updated = common().withDeadline(deadline);
        return switch (this) {
            case Ready ready -> new Ready(updated, ready.next(), ready.summary());
            case ProviderPending pending -> new ProviderPending(updated, pending.requestId(), pending.messageId(),
                    pending.purpose(), pending.profile(), pending.envelopeFingerprint(),
                    new Ready(updated, pending.resume().next(), pending.resume().summary()));
            case Tools tools -> new Tools(updated, tools.batchId(), tools.assistantMessageId(),
                    tools.firstOrdinal(), tools.lastOrdinal(), tools.nextOrdinal());
        };
    }

    /** READY 的下一步只能是普通 Assistant Provider 调用或自动 Summary。 */
    record Ready(Common common, Next next, SummaryProgress summary) implements TurnExecutionState {
        /** 强制 Summary 仅在 SUMMARY 分支出现，避免恢复时存在两个候选下一步。 */
        public Ready {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(next, "next");
            if ((next == Next.SUMMARY) != (summary != null)) {
                throw new IllegalArgumentException("summary progress must match READY next step");
            }
        }

        /** UNKNOWN Provider 已占用请求 ordinal，只推进该游标并保留尚未执行的 READY 工作。 */
        public Ready advanceProviderOrdinal() {
            Common advanced = new Common(common.modelRound(), common.usedToolCalls(),
                    Math.addExact(common.nextProviderOrdinal(), 1), common.promptCheckpointId(),
                    common.activeSkills(), common.deadlineAt(), common.origin(), common.activeBudget());
            return new Ready(advanced, next, summary);
        }
    }

    /** Provider intent 已提交但响应尚未原子结算；恢复只能先登记 UNKNOWN usage。 */
    record ProviderPending(Common common, String requestId, String messageId, ProviderPurpose purpose,
                           ProviderRequestProfile profile, String envelopeFingerprint,
                           Ready resume) implements TurnExecutionState {
        /** 请求身份、完整 Profile 与原 READY 工作必须同时提交，恢复不再读取 Turn 级环境指纹。 */
        public ProviderPending {
            Objects.requireNonNull(common, "common");
            requestId = identifier(requestId, "request_", "requestId");
            messageId = identifier(messageId, "item_", "messageId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(profile, "profile");
            envelopeFingerprint = fingerprint(envelopeFingerprint, "envelopeFingerprint");
            Objects.requireNonNull(resume, "resume");
            if (!common.equals(resume.common())) {
                throw new IllegalArgumentException("provider resume common state changed");
            }
        }
    }

    /** 持久化 Tool batch 及下一个 ordinal；每次只允许结算当前位置并推进一个游标。 */
    record Tools(Common common, String batchId, String assistantMessageId, int firstOrdinal,
                 int lastOrdinal, int nextOrdinal) implements TurnExecutionState {
        /** 限制 ordinal 为闭区间加完成哨兵，恢复无需扫描消息判断 batch 是否结束。 */
        public Tools {
            Objects.requireNonNull(common, "common");
            batchId = identifier(batchId, "batch_", "batchId");
            assistantMessageId = identifier(assistantMessageId, "item_", "assistantMessageId");
            if (firstOrdinal < 0 || lastOrdinal < firstOrdinal || nextOrdinal < firstOrdinal
                || nextOrdinal > lastOrdinal + 1) {
                throw new IllegalArgumentException("invalid Tool ordinal cursor");
            }
        }
    }

    /** Operation 保存累计游标、Skill 身份、当前绝对 Deadline 及暂停时冻结的活动预算。 */
    record Common(int modelRound, int usedToolCalls, int nextProviderOrdinal,
                  String promptCheckpointId, List<ActiveSkill> activeSkills, Instant deadlineAt,
                  TurnOrigin origin, Duration activeBudget) {
        /** 保留旧的进程内构造便利形式；持久化前会把当前绝对截止线折算为活动预算。 */
        public Common(int modelRound, int usedToolCalls, int nextProviderOrdinal,
                      String promptCheckpointId, List<ActiveSkill> activeSkills, Instant deadlineAt,
                      TurnOrigin origin) {
            this(modelRound, usedToolCalls, nextProviderOrdinal, promptCheckpointId, activeSkills, deadlineAt,
                    origin, remainingFromNow(deadlineAt));
        }
        /**
         * 计数只前进；来源属于恢复所需的 Operation 事实，运行环境仍由下一 Provider 安全点重新解析。
         */
        public Common {
            if (modelRound < 0 || modelRound > 128 || usedToolCalls < 0 || usedToolCalls > 1_024
                || nextProviderOrdinal < 1 || nextProviderOrdinal > 1_024) {
                throw new IllegalArgumentException("invalid execution counters");
            }
            if (promptCheckpointId != null) {
                promptCheckpointId = text(promptCheckpointId, "promptCheckpointId", 256);
            }
            activeSkills = List.copyOf(Objects.requireNonNull(activeSkills, "activeSkills"));
            if (activeSkills.size() > 256 || activeSkills.stream().distinct().count() != activeSkills.size()) {
                throw new IllegalArgumentException("invalid active Skill references");
            }
            Objects.requireNonNull(deadlineAt, "deadlineAt");
            Objects.requireNonNull(origin, "origin");
            if (activeBudget == null || activeBudget.isNegative() || activeBudget.toMillis() > 86_400_000L) {
                throw new IllegalArgumentException("invalid active budget");
            }
        }

        /** 只在挂起时改变预算，避免用 wall-clock 等待时间消耗执行额度。 */
        public Common withActiveBudget(Duration budget) {
            return new Common(modelRound, usedToolCalls, nextProviderOrdinal, promptCheckpointId,
                    activeSkills, deadlineAt, origin, budget);
        }

        /** 恢复时重建 wall-clock 截止线，activeBudget 仍是同一份持久事实。 */
        public Common withDeadline(Instant deadline) {
            return new Common(modelRound, usedToolCalls, nextProviderOrdinal, promptCheckpointId,
                    activeSkills, deadline, origin, activeBudget);
        }

        /** 旧构造只用于非生产 fixture；按当前系统时钟计算兼容默认预算，生产恢复传显式 activeBudget。 */
        private static Duration remainingFromNow(Instant deadline) {
            long millis = Math.max(0L, Duration.between(Instant.now(), deadline).toMillis());
            return Duration.ofMillis(Math.min(millis, 86_400_000L));
        }
    }

    /** 激活 Skill 的稳定身份引用；正文由恢复时当前 catalog 按需读取。 */
    record ActiveSkill(String skillId) {
        /** Skill ID 必须采用公开合同格式，禁止显示名称或路径冒充恢复身份。 */
        public ActiveSkill {
            skillId = text(skillId, "skillId", 256);
            if (!skillId.startsWith("skill_")) {
                throw new IllegalArgumentException("invalid skillId");
            }
        }
    }

    /**
     * 滚动 Summary 的恢复游标、已知 usage 与显式子阶段；已结算 candidate/repair 的下一步
     * 和 prompt 身份必须一起持久化，不能从消息或 usage 行反推。
     */
    record SummaryProgress(String summaryJson, long throughOrdinal, int nextChunk,
                           String planFingerprint, SummaryStage stage,
                           int targetNextChunk, long targetThroughOrdinal,
                           List<String> violations, String promptFingerprint, KnownUsage usage) {
        /** 子阶段字段按闭集校验，任何缺失都禁止降级为重新发送 candidate。 */
        public SummaryProgress {
            summaryJson = text(summaryJson, "summaryJson", 4_000_000);
            if (throughOrdinal < 0 || nextChunk < 0 || targetNextChunk < nextChunk
                || targetThroughOrdinal < throughOrdinal) {
                throw new IllegalArgumentException("invalid summary cursor");
            }
            planFingerprint = fingerprint(planFingerprint, "planFingerprint");
            Objects.requireNonNull(stage, "stage");
            violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
            if (violations.size() > 64 || violations.stream().anyMatch(value ->
                    value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}"))) {
                throw new IllegalArgumentException("invalid summary violations");
            }
            boolean candidate = stage == SummaryStage.CANDIDATE;
            if (candidate != (targetNextChunk == nextChunk && targetThroughOrdinal == throughOrdinal
                && violations.isEmpty() && promptFingerprint == null)) {
                throw new IllegalArgumentException("invalid summary candidate stage");
            }
            if (!candidate && (targetNextChunk == nextChunk || violations.isEmpty())) {
                throw new IllegalArgumentException("invalid summary pending stage");
            }
            if (!candidate) promptFingerprint = fingerprint(promptFingerprint, "promptFingerprint");
            Objects.requireNonNull(usage, "usage");
        }

        /** 构造无待处理子阶段的已接纳游标；调用名显式表明下一步可发送 candidate。 */
        public static SummaryProgress candidate(String summaryJson, long throughOrdinal, int nextChunk,
                                                String planFingerprint, KnownUsage usage) {
            return new SummaryProgress(summaryJson, throughOrdinal, nextChunk, planFingerprint,
                    SummaryStage.CANDIDATE, nextChunk, throughOrdinal, List.of(), null, usage);
        }
    }

    /** Summary Provider 子阶段闭集，恢复不得自行跳转或回退。 */
    enum SummaryStage {
        /** 当前块尚未结算 candidate。 */
        CANDIDATE,
        /** candidate 已结算且非法，只允许发送冻结 repair prompt。 */
        REPAIR,
        /** repair 已结算且非法，只允许 deterministic fallback。 */
        FALLBACK_PENDING
    }

    /** Summary 已结算用量；UNKNOWN Provider 请求只进入 usage 表，不混入已知累计。 */
    record KnownUsage(long inputTokens, long outputTokens, long totalTokens,
                      long cacheReadTokens, long cacheWriteTokens) {
        /** Token 使用量不得为负或小于输入输出之和。 */
        public KnownUsage {
            if (inputTokens < 0 || outputTokens < 0 || totalTokens < inputTokens + outputTokens
                || cacheReadTokens < 0 || cacheWriteTokens < 0) {
                throw new IllegalArgumentException("invalid known usage");
            }
        }
    }

    /** READY 的显式工作种类。 */
    enum Next {
        /** 下一步调用普通 Assistant Provider。 */
        ASSISTANT,
        /** 下一步继续当前 Turn 的自动 Summary。 */
        SUMMARY
    }

    /** Provider 用量和恢复策略必须区分普通 Assistant 与 Summary。 */
    enum ProviderPurpose {
        /** 面向用户回答或 Tool continuation 的普通模型请求。 */
        ASSISTANT,
        /** 只用于上下文压缩的滚动摘要请求。 */
        SUMMARY
    }

    /** SHA-256 指纹必须是固定小写十六进制，恢复时不做宽松归一化。 */
    private static String fingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** 持久身份保持有界且禁止控制字符；前缀使不同事实域不可混用。 */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || value.length() > 128 || !value.startsWith(prefix)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        String suffix = value.substring(prefix.length());
        if (!suffix.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IllegalArgumentException("invalid " + field);
        return value;
    }

    /** 普通文本只承担必要恢复状态，不允许控制字符或无界内容。 */
    private static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
