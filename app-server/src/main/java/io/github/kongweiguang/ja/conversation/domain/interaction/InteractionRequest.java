// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Thread 内唯一活动交互请求的聚合；答案校验在此完成后才进入持久层。 */
public record InteractionRequest(String requestId, String threadId, String turnId, String toolCallId,
                                 String planRevisionId, String runId, String goalId,
                                 String idempotencyKey, List<InteractionQuestion> questions,
                                 InteractionStatus status, List<InteractionAnswer> answers,
                                 long revision, Instant createdAt, Instant updatedAt) {
    /** 请求身份、题数、答案身份与 revision 是恢复和 CAS 的核心事实。 */
    public InteractionRequest {
        id(requestId, "interaction_");
        id(threadId, "thr_");
        id(turnId, "turn_");
        id(toolCallId, null);
        optionalId(planRevisionId, "plan_");
        optionalId(runId, "run_");
        optionalId(goalId, "goal_");
        idempotencyKey = bounded(idempotencyKey, "idempotencyKey", 256);
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
        if (revision < 0) throw new IllegalArgumentException("invalid interaction revision");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("interaction timestamps reversed");
    }

    /** 对所有题目做一次原子答案校验，避免部分答案先落库形成不可恢复中间态。 */
    public InteractionRequest answer(List<InteractionAnswer> submitted, Instant answeredAt) {
        Objects.requireNonNull(answeredAt, "answeredAt");
        if (status != InteractionStatus.PENDING) throw new IllegalStateException("interaction is no longer pending");
        List<InteractionAnswer> values = List.copyOf(Objects.requireNonNull(submitted, "submitted"));
        if (values.size() != questions.size()) throw new IllegalArgumentException("all questions require an answer");
        for (InteractionQuestion question : questions) {
            InteractionAnswer answer = values.stream().filter(v -> v.questionId().equals(question.questionId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("missing interaction answer"));
            validate(question, answer);
        }
        return new InteractionRequest(requestId, threadId, turnId, toolCallId, planRevisionId, runId, goalId, idempotencyKey,
                questions, InteractionStatus.ANSWERED, values, revision + 1, createdAt, answeredAt);
    }

    /** 取消/替代保持显式状态，迟到响应只会遇到 CAS 冲突而不能复活旧请求。 */
    public InteractionRequest close(InteractionStatus next, Instant occurredAt) {
        if (next != InteractionStatus.CANCELLED && next != InteractionStatus.SUPERSEDED) {
            throw new IllegalArgumentException("invalid interaction close status");
        }
        if (status != InteractionStatus.PENDING) throw new IllegalStateException("interaction is no longer pending");
        return new InteractionRequest(requestId, threadId, turnId, toolCallId, planRevisionId, runId, goalId, idempotencyKey,
                questions, next, answers, revision + 1, createdAt, occurredAt);
    }

    /** 按题目类型校验答案闭集，服务端不信任 Renderer 的预校验结果。 */
    private static void validate(InteractionQuestion question, InteractionAnswer answer) {
        if (answer.skipped()) {
            if (question.required()) throw new IllegalArgumentException("required interaction question was skipped");
            return;
        }
        if (question.type() == InteractionQuestionType.TEXT) {
            if (answer.freeText() == null || answer.freeText().isBlank() || !answer.optionIds().isEmpty()) {
                throw new IllegalArgumentException("text answer is required");
            }
            return;
        }
        if (!answer.optionIds().stream().allMatch(id -> question.options().stream().anyMatch(o -> o.optionId().equals(id)))) {
            throw new IllegalArgumentException("unknown interaction option");
        }
        if (question.type() == InteractionQuestionType.SINGLE && answer.optionIds().size() > 1) {
            throw new IllegalArgumentException("single choice requires one option");
        }
        if (question.type() == InteractionQuestionType.MULTIPLE
                && answer.optionIds().size() != answer.optionIds().stream().distinct().count()) {
            throw new IllegalArgumentException("multiple choice contains duplicate options");
        }
        if (question.type() == InteractionQuestionType.MULTIPLE && answer.optionIds().isEmpty()
                && !question.allowFreeText()) throw new IllegalArgumentException("multiple choice requires an option");
        if (!answer.optionIds().isEmpty() && answer.freeText() != null && !answer.freeText().isBlank()) {
            throw new IllegalArgumentException("choice answer cannot combine option and free text");
        }
        if (answer.optionIds().isEmpty() && (!question.allowFreeText()
                || answer.freeText() == null || answer.freeText().isBlank())) {
            throw new IllegalArgumentException("choice answer is required");
        }
    }

    /** 要求跨请求稳定的身份格式，防止把不同资源的 ID 混用。 */
    private static String id(String value, String prefix) {
        String result = bounded(value, prefix, 128);
        if (prefix != null && !result.startsWith(prefix)) throw new IllegalArgumentException("invalid " + prefix + " id");
        return result;
    }

    /** 可选关联仅在出现时校验，缺失表示该请求不挂接对应聚合。 */
    private static void optionalId(String value, String prefix) {
        if (value != null) id(value, prefix);
    }

    /** 统一限制协议文本，避免问题正文或幂等键形成无界持久数据。 */
    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid " + field);
        return value;
    }
}
