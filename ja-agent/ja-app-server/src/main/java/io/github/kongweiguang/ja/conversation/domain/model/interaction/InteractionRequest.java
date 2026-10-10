// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.model.interaction;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Ja Thread 内唯一活动交互请求的聚合；Plan 关联和存储状态属于宿主业务边界。
 *
 * @param interactionId 交互标识
 * @param threadId 所属对话的稳定标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param toolCallId 工具调用标识
 * @param planRevisionId Plan 修订版本标识
 * @param runId 运行标识
 * @param operationKey 重复提交同一业务操作时复用的幂等键
 * @param questions 问题列表
 * @param status 当前状态分类
 * @param answers 答案集合
 * @param interactionRevision 交互修订版本
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record InteractionRequest(String interactionId,
                                 String threadId,
                                 String turnId,
                                 String toolCallId,
                                 String planRevisionId,
                                 String runId,
                                 String operationKey,
                                 List<InteractionQuestion> questions,
                                 InteractionStatus status,
                                 List<InteractionAnswer> answers,
                                 long interactionRevision,
                                 Instant createdAt,
                                 Instant updatedAt) {
    /**
     * 请求身份、题数、答案身份与 interactionRevision 是恢复和 CAS 的核心事实。
     */
    public InteractionRequest {
        id(interactionId, "interaction_");
        id(threadId, "thr_");
        id(turnId, "turn_");
        id(toolCallId, null);
        optionalId(planRevisionId, "plan_");
        optionalId(runId, "run_");
        operationKey = bounded(operationKey, "operationKey", 256);
        questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
        if (questions.isEmpty() || questions.size() > 3
            || new HashSet<>(questions.stream().map(InteractionQuestion::questionId).toList()).size() != questions.size()) {
            throw new IllegalArgumentException("interaction request must contain one to three unique questions");
        }
        status = Objects.requireNonNull(status, "status");
        answers = List.copyOf(Objects.requireNonNull(answers, "answers"));
        if (answers.size() > questions.size() || answers.stream().map(InteractionAnswer::questionId).distinct().count() != answers.size()) {
            throw new IllegalArgumentException("invalid interaction answers");
        }
        if (interactionRevision < 0) {
            throw new IllegalArgumentException("invalid interaction revision");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("interaction timestamps reversed");
        }
    }

    /**
     * 要求跨请求稳定的身份格式，防止把不同资源的 ID 混用。
     */
    private static String id(String value, String prefix) {
        String result = bounded(value, prefix, 128);
        if (prefix != null && !result.startsWith(prefix))
            {
                throw new IllegalArgumentException("invalid " + prefix + " id");
            }
        return result;
    }

    /**
     * 可选关联仅在出现时校验，缺失表示该请求不挂接对应聚合。
     */
    private static void optionalId(String value, String prefix) {
        if (value != null) {
            id(value, prefix);
        }
    }

    /**
     * 统一限制协议文本，避免问题正文或幂等键形成无界持久数据。
     */
    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max
            || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("invalid " + field);
            }
        return value;
    }

    /**
     * 对所有题目做一次原子答案校验，避免部分答案先落库形成不可恢复中间态。
     */
    public InteractionRequest answer(List<InteractionAnswer> submitted, Instant answeredAt) {
        Objects.requireNonNull(answeredAt, "answeredAt");
        if (status != InteractionStatus.PENDING) {
            throw new IllegalStateException("interaction is no longer pending");
        }
        List<InteractionAnswer> values = List.copyOf(Objects.requireNonNull(submitted, "submitted"));
        InteractionQuestion.validateCompleteAnswers(questions, values);
        return new InteractionRequest(interactionId, threadId, turnId, toolCallId, planRevisionId, runId, operationKey,
                questions, InteractionStatus.ANSWERED, values, interactionRevision + 1, createdAt, answeredAt);
    }

    /**
     * 取消/替代保持显式状态，迟到响应只会遇到 CAS 冲突而不能复活旧请求。
     */
    public InteractionRequest close(InteractionStatus next, Instant occurredAt) {
        if (next != InteractionStatus.CANCELLED && next != InteractionStatus.SUPERSEDED) {
            throw new IllegalArgumentException("invalid interaction close status");
        }
        if (status != InteractionStatus.PENDING) {
            throw new IllegalStateException("interaction is no longer pending");
        }
        return new InteractionRequest(interactionId, threadId, turnId, toolCallId, planRevisionId, runId, operationKey,
                questions, next, answers, interactionRevision + 1, createdAt, occurredAt);
    }
}
