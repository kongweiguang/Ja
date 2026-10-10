// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.interaction;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 一道可由宿主交互请求复用的结构化问题及其回答约束。
 *
 * @param questionId 问题标识
 * @param prompt 提示词
 * @param type 类型标识
 * @param options 选项列表
 * @param required 是否必需
 * @param allowFreeText 是否允许自由文本输入
 */
public record InteractionQuestion(String questionId,
                                  String prompt,
                                  InteractionQuestionType type,
                                  List<InteractionOption> options,
                                  boolean required,
                                  boolean allowFreeText) {
    /**
     * 严格约束选项闭集，防止 MULTIPLE/SINGLE 通过错误 payload 绕过 UI 语义。
     */
    public InteractionQuestion {
        questionId = ContractChecks.identifier(questionId, "questionId");
        if (!questionId.startsWith("question_")) {
            throw new IllegalArgumentException("invalid questionId");
        }
        prompt = text(prompt, "prompt", 4_000, true);
        type = Objects.requireNonNull(type, "type");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        if (options.size() > 32 || options.stream().map(InteractionOption::optionId).count()
                                   != new HashSet<>(options.stream().map(InteractionOption::optionId).toList()).size()) {
            throw new IllegalArgumentException("duplicate or excessive interaction options");
        }
        if (type == InteractionQuestionType.TEXT && !options.isEmpty()) {
            throw new IllegalArgumentException("text question cannot have options");
        }
        if (type != InteractionQuestionType.TEXT && options.isEmpty() && !allowFreeText) {
            throw new IllegalArgumentException("choice question needs options or free text");
        }
    }

    /**
     * 对不可变问题集合校验完整回答，供通用等待与 Ja 请求聚合复用同一答案规则。
     */
    public static void validateCompleteAnswers(List<InteractionQuestion> questions, List<InteractionAnswer> answers) {
        if (answers.size() != questions.size()
            || answers.stream().map(InteractionAnswer::questionId).distinct().count() != answers.size()) {
            throw new IllegalArgumentException("all interaction questions require one answer");
        }
        for (InteractionQuestion question : questions) {
            InteractionAnswer answer = answers.stream()
                    .filter(candidate -> candidate.questionId().equals(question.questionId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("missing interaction answer"));
            validateAnswer(question, answer);
        }
    }

    /**
     * 由不可变题目定义执行类型、选项、跳过与自填规则。
     */
    private static void validateAnswer(InteractionQuestion question, InteractionAnswer answer) {
        if (answer.skipped()) {
            if (question.required()) {
                throw new IllegalArgumentException("required interaction question was skipped");
            }
            return;
        }
        if (question.type() == InteractionQuestionType.TEXT) {
            if (answer.freeText() == null || answer.freeText().isBlank() || !answer.optionIds().isEmpty()) {
                throw new IllegalArgumentException("text answer is required");
            }
            return;
        }
        if (!answer.optionIds().stream().allMatch(id -> question.options().stream()
                .anyMatch(option -> option.optionId().equals(id)))) {
            throw new IllegalArgumentException("unknown interaction option");
        }
        if (question.type() == InteractionQuestionType.SINGLE && answer.optionIds().size() > 1) {
            throw new IllegalArgumentException("single choice requires one option");
        }
        if (question.type() == InteractionQuestionType.MULTIPLE
            && answer.optionIds().size() != answer.optionIds().stream().distinct().count()) {
            throw new IllegalArgumentException("multiple choice contains duplicate options");
        }
        if (!answer.optionIds().isEmpty() && answer.freeText() != null && !answer.freeText().isBlank()) {
            throw new IllegalArgumentException("choice answer cannot combine option and free text");
        }
        if (answer.optionIds().isEmpty() && (!question.allowFreeText()
                                             || answer.freeText() == null || answer.freeText().isBlank())) {
            throw new IllegalArgumentException("choice answer is required");
        }
    }

    /**
     * 仅题目 prompt 允许自然多行文本，仍限制长度和控制字符集合。
     */
    private static String text(String value, String field, int max, boolean allowWhitespace) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > max || value.chars().anyMatch(valueAt ->
                Character.isISOControl(valueAt) && (!allowWhitespace
                                                    || (valueAt != '\n' && valueAt != '\r' && valueAt != '\t')))) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
