// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.interaction;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 租户中立的等待问题聚合；工具审批事实由执行事实记录，等待属性保留审批策略信封。
 *
 * @param interactionId 交互标识
 * @param toolCallId 工具调用标识
 * @param operationKey 重复提交同一业务操作时复用的稳定操作键
 * @param attributes 属性集合
 * @param questions 问题列表
 * @param answers 答案集合
 * @param status 当前状态分类
 * @param interactionRevision 交互修订版本
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record InteractionWait(String interactionId,
                              String toolCallId,
                              String operationKey,
                              JsonObject attributes,
                              List<InteractionQuestion> questions,
                              List<InteractionAnswer> answers,
                              InteractionStatus status,
                              long interactionRevision,
                              Instant createdAt,
                              Instant updatedAt) {
    /**
     * 只冻结通用问题生命周期，Plan 与 Thread 身份仍由宿主通过 opaque 属性关联。
     */
    public InteractionWait {
        interactionId = ContractChecks.identifier(interactionId, "interactionId");
        if (toolCallId != null) {
            toolCallId = ContractChecks.identifier(toolCallId, "toolCallId");
        }
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        Objects.requireNonNull(attributes, "attributes");
        questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
        answers = List.copyOf(Objects.requireNonNull(answers, "answers"));
        if (questions.isEmpty() || questions.size() > 3
            || new HashSet<>(questions.stream().map(InteractionQuestion::questionId).toList()).size() != questions.size()) {
            throw new IllegalArgumentException("wait must contain one to three unique questions");
        }
        status = Objects.requireNonNull(status, "status");
        if (interactionRevision < 0) {
            throw new IllegalArgumentException("invalid interaction revision");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("wait timestamps reversed");
        }
        if (status == InteractionStatus.ANSWERED) {
            InteractionQuestion.validateCompleteAnswers(questions, answers);
        }
        else if (!answers.isEmpty()) {
            throw new IllegalArgumentException("unanswered wait cannot contain answers");
        }
    }

    /**
     * 一次校验并冻结完整答案，禁止部分回答进入可恢复状态。
     */
    public InteractionWait answer(List<InteractionAnswer> submitted, Instant answeredAt) {
        Objects.requireNonNull(answeredAt, "answeredAt");
        if (status != InteractionStatus.PENDING) {
            throw new IllegalStateException("wait is no longer pending");
        }
        List<InteractionAnswer> values = List.copyOf(Objects.requireNonNull(submitted, "submitted"));
        InteractionQuestion.validateCompleteAnswers(questions, values);
        return new InteractionWait(interactionId, toolCallId, operationKey, attributes, questions, values,
                InteractionStatus.ANSWERED, interactionRevision + 1, createdAt, answeredAt);
    }

    /**
     * 关闭等待时只记录取消、替代或到期，不伪造用户回答或审批决定。
     */
    public InteractionWait close(InteractionStatus next, Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (next != InteractionStatus.CANCELLED && next != InteractionStatus.SUPERSEDED
            && next != InteractionStatus.EXPIRED) {
            throw new IllegalArgumentException("invalid wait close status");
        }
        if (status != InteractionStatus.PENDING) {
            throw new IllegalStateException("wait is no longer pending");
        }
        return new InteractionWait(interactionId, toolCallId, operationKey, attributes, questions, List.of(), next,
                interactionRevision + 1, createdAt, occurredAt);
    }

}
