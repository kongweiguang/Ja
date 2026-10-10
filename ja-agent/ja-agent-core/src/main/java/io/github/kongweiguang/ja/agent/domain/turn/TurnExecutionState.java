// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.turn;

import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.List;
import java.util.Objects;

/**
 * Turn 即 Operation 的完整持久执行游标；任一非终态提交都必须整体替换该值，禁止从历史猜测下一步。
 */
public sealed interface TurnExecutionState permits TurnExecutionState.Ready,
        TurnExecutionState.ProviderPending, TurnExecutionState.Tools {
    /**
     * 当前执行游标格式；任何其他版本都由严格 codec 显式拒绝或迁移。
     */
    int SCHEMA_VERSION = 4;

    /**
     * SHA-256 指纹必须是固定小写十六进制，恢复时不做宽松归一化。
     */
    private static String fingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 普通文本只承担必要恢复状态，不允许控制字符或无界内容。
     */
    private static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 所有状态共享的不可变恢复基线和累计游标。
     */
    Common common();

    /**
     * Summary Provider 子阶段闭集，恢复不得自行跳转、重发 repair 或采用未验证的回退摘要。
     */
    enum SummaryStage {
        /**
         * 当前块尚未结算 candidate。
         */
        CANDIDATE,
        /**
         * candidate 已结算且非法，只允许发送冻结 repair prompt。
         */
        REPAIR,
        /**
         * repair 已结算且非法；本块明确失败，恢复不得退回原提示或生成替代摘要。
         */
        FAILED
    }

    /**
     * READY 的显式工作种类。
     */
    enum Next {
        /**
         * 下一步调用普通 Assistant Provider。
         */
        ASSISTANT,
        /**
         * 下一步继续当前 Turn 的自动 Summary。
         */
        SUMMARY,
        /**
         * 手动压缩已准入，恢复时先重新规划并提交 SUMMARY 游标，再允许摘要请求。
         */
        COMPACTION,
        /**
         * Assistant 正文已和游标提交；恢复只执行完成钩子，不得再次调用模型。
         */
        COMPLETION
    }

    /**
     * Provider 用量和恢复策略必须区分普通 Assistant 与 Summary。
     */
    enum ProviderPurpose {
        /**
         * 面向用户回答或 Tool continuation 的普通模型请求。
         */
        ASSISTANT,
        /**
         * 只用于上下文压缩的滚动摘要请求。
         */
        SUMMARY
    }

    /**
     * READY 的下一步由游标明确指向 Assistant、Summary、手动压缩规划或完成钩子。
     *
     * @param common 公共状态
     * @param next 下一执行步骤
     * @param summary 摘要
     */
    record Ready(Common common,
                 Next next,
                 SummaryProgress summary) implements TurnExecutionState {
        /**
         * 强制 Summary 仅在 SUMMARY 分支出现，避免恢复时存在两个候选下一步。
         */
        public Ready {
            Objects.requireNonNull(common, "common");
            Objects.requireNonNull(next, "next");
            if ((next == Next.SUMMARY) == (summary == null)) {
                throw new IllegalArgumentException("summary progress must match READY next step");
            }
        }

        /**
         * UNKNOWN Provider 已占用请求 ordinal，只推进该游标并保留尚未执行的 READY 工作。
         */
        public Ready advanceProviderOrdinal() {
            Common advanced = new Common(common.modelRound(), common.usedToolCalls(),
                    Math.addExact(common.nextProviderOrdinal(), 1), common.promptRevision(),
                    common.activeSkills());
            return new Ready(advanced, next, summary);
        }
    }

    /**
     * Provider intent 已提交但响应尚未原子结算；恢复只能先登记 UNKNOWN usage。
     *
     * @param common 公共状态
     * @param modelRequestId 一次真实模型请求的标识
     * @param messageId 消息标识
     * @param purpose 用途
     * @param profile 配置档案
     * @param envelopeFingerprint 协议封装指纹
     * @param resume 续跑
     */
    record ProviderPending(Common common,
                           String modelRequestId,
                           String messageId,
                           ProviderPurpose purpose,
                           ModelRequestIdentity profile,
                           String envelopeFingerprint,
                           Ready resume) implements TurnExecutionState {
        /**
         * 请求身份、完整 Profile 与原 READY 工作必须同时提交，且用途必须匹配恢复分支，避免重启后走错请求路径。
         */
        public ProviderPending {
            Objects.requireNonNull(common, "common");
            modelRequestId = ContractChecks.identifier(modelRequestId, "modelRequestId");
            messageId = ContractChecks.identifier(messageId, "messageId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(profile, "profile");
            envelopeFingerprint = fingerprint(envelopeFingerprint, "envelopeFingerprint");
            Objects.requireNonNull(resume, "resume");
            if (!common.equals(resume.common())) {
                throw new IllegalArgumentException("provider resume common state changed");
            }
            boolean matchesPurpose = switch (purpose) {
                case ASSISTANT -> resume.next() == Next.ASSISTANT && resume.summary() == null;
                case SUMMARY -> resume.next() == Next.SUMMARY && resume.summary() != null;
            };
            if (!matchesPurpose) {
                throw new IllegalArgumentException("provider purpose does not match resume state");
            }
        }
    }

    /**
     * 持久化 Tool batch 及下一个 ordinal；每次只允许结算当前位置并推进一个游标。
     *
     * @param common 公共状态
     * @param batchId 批次标识
     * @param assistantMessageId 助手消息标识
     * @param firstOrdinal 区间的首个顺序位置
     * @param lastOrdinal 区间的最后顺序位置
     * @param nextOrdinal 下一顺序位置
     */
    record Tools(Common common,
                 String batchId,
                 String assistantMessageId,
                 int firstOrdinal,
                 int lastOrdinal,
                 int nextOrdinal) implements TurnExecutionState {
        /**
         * 限制 ordinal 为闭区间加完成哨兵，恢复无需扫描消息判断 batch 是否结束。
         */
        public Tools {
            Objects.requireNonNull(common, "common");
            batchId = ContractChecks.identifier(batchId, "batchId");
            assistantMessageId = ContractChecks.identifier(assistantMessageId, "assistantMessageId");
            if (firstOrdinal < 0 || lastOrdinal < firstOrdinal || nextOrdinal < firstOrdinal
                || (long) nextOrdinal > (long) lastOrdinal + 1) {
                throw new IllegalArgumentException("invalid Tool ordinal cursor");
            }
        }
    }

    /**
     * 累计游标与 Skill 身份跨进程恢复；promptRevision 是已发送模型请求的材料指纹，不是摘要检查点 ID。
     *
     * @param modelRound 模型调用轮次
     * @param usedToolCalls 已发生的工具调用次数
     * @param nextProviderOrdinal 下一供应商调用的顺序位置
     * @param promptRevision 提示词修订版本
     * @param activeSkills 当前激活的 Skill 列表
     */
    record Common(int modelRound,
                  int usedToolCalls,
                  int nextProviderOrdinal,
                  String promptRevision,
                  List<ActiveSkill> activeSkills) {
        /**
         * 计数只前进；Ja 来源身份由宿主扩展状态保存，内核不据 Plan 身份选择执行路径。
         */
        public Common {
            if (modelRound < 0 || usedToolCalls < 0 || nextProviderOrdinal < 1) {
                throw new IllegalArgumentException("invalid execution counters");
            }
            if (promptRevision != null) {
                promptRevision = text(promptRevision, "promptRevision", 256);
            }
            activeSkills = List.copyOf(Objects.requireNonNull(activeSkills, "activeSkills"));
            if (activeSkills.size() > 256 || activeSkills.stream().distinct().count() != activeSkills.size()) {
                throw new IllegalArgumentException("invalid active Skill references");
            }
        }
    }

    /**
     * 保存本次实际激活的 Skill 身份与内容指纹，不把新正文伪装成历史调用依据。
     *
     * @param skillId Skill 标识
     * @param contentFingerprint 内容指纹
     */
    record ActiveSkill(String skillId,
                       java.util.Optional<String> contentFingerprint) {
        /**
         * 迁移前未保存的指纹显式保留为空，不能用当前正文补造历史证据。
         */
        public ActiveSkill {
            skillId = ContractChecks.runtimeSkillIdentifier(skillId);
            contentFingerprint = java.util.Objects.requireNonNull(contentFingerprint, "contentFingerprint");
            contentFingerprint.ifPresent(value -> {
                if (!value.matches("sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("invalid Skill content fingerprint");
                }
            });
        }
    }

    /**
     * 滚动 Summary 的恢复游标、已知 usage 与显式子阶段；已结算 candidate/repair 的下一步
     * 和 prompt 身份必须一起持久化，不能从消息或 usage 行反推。
     *
     * @param summaryJson 摘要 JSON 表示
     * @param throughOrdinal 覆盖到的顺序位置
     * @param nextChunk 下一内容片段的位置
     * @param planFingerprint Plan 内容指纹
     * @param stage 阶段
     * @param targetNextChunk 目标游标的下一内容片段位置
     * @param targetThroughOrdinal 目标快照覆盖到的顺序位置
     * @param violations 违规项
     * @param promptFingerprint 提示词指纹
     * @param usage 用量
     */
    record SummaryProgress(String summaryJson,
                           long throughOrdinal,
                           int nextChunk,
                           String planFingerprint,
                           SummaryStage stage,
                           int targetNextChunk,
                           long targetThroughOrdinal,
                           List<String> violations,
                           String promptFingerprint,
                           KnownUsage usage) {
        /**
         * 子阶段字段按闭集校验，任何缺失都禁止降级为重新发送 candidate。
         */
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
            if (!candidate) {
                promptFingerprint = fingerprint(promptFingerprint, "promptFingerprint");
            }
            Objects.requireNonNull(usage, "usage");
        }

        /**
         * 构造无待处理子阶段的已接纳游标；调用名显式表明下一步可发送 candidate。
         */
        public static SummaryProgress candidate(String summaryJson, long throughOrdinal, int nextChunk,
                                                String planFingerprint, KnownUsage usage) {
            return new SummaryProgress(summaryJson, throughOrdinal, nextChunk, planFingerprint,
                    SummaryStage.CANDIDATE, nextChunk, throughOrdinal, List.of(), null, usage);
        }
    }

    /**
     * Summary 已结算用量；UNKNOWN Provider 请求只进入 usage 表，不混入已知累计。
     *
     * @param inputTokens 输入 Token 数
     * @param outputTokens 输出 Token 数
     * @param totalTokens 输入与输出的 Token 总数
     * @param cacheReadTokens 命中缓存的输入 Token 数
     * @param cacheWriteTokens 写入缓存的 Token 数
     */
    record KnownUsage(long inputTokens,
                      long outputTokens,
                      long totalTokens,
                      long cacheReadTokens,
                      long cacheWriteTokens) {
        /**
         * Token 使用量不得为负或小于输入输出之和。
         */
        public KnownUsage {
            if (inputTokens < 0 || outputTokens < 0 || totalTokens < inputTokens || totalTokens - inputTokens < outputTokens
                || cacheReadTokens < 0 || cacheWriteTokens < 0) {
                throw new IllegalArgumentException("invalid known usage");
            }
        }
    }
}
