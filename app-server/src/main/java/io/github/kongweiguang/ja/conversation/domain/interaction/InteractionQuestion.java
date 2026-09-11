// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 一道结构化问题及其回答约束；每批最多三题由 InteractionRequest 统一限制。 */
public record InteractionQuestion(String questionId, String prompt, InteractionQuestionType type,
                                  List<InteractionOption> options, boolean required,
                                  boolean allowFreeText) {
    /** 严格约束选项闭集，防止 MULTIPLE/SINGLE 通过错误 payload 绕过 UI 语义。 */
    public InteractionQuestion {
        questionId = ContractChecks.identifier(questionId, "questionId");
        if (!questionId.startsWith("question_")) throw new IllegalArgumentException("invalid questionId");
        prompt = text(prompt, "prompt", 4_000, true);
        type = Objects.requireNonNull(type, "type");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        /* 选择题的“其他答案”是公共交互契约的一部分，避免某个 Provider 关闭用户表达出口。 */
        if (type != InteractionQuestionType.TEXT) allowFreeText = true;
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

    /** 普通题面不允许控制字符，避免换行和终端控制序列污染卡片布局。 */
    private static String text(String value, String field, int max) {
        return text(value, field, max, false);
    }

    /** 仅题目 prompt 允许自然多行文本，仍限制长度和控制字符集合。 */
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
