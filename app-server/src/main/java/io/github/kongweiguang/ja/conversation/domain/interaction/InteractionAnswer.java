// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.domain.interaction;

import java.util.List;
import java.util.Objects;

/** 用户提交的一题答案；明确区分选项、自填和 skipped，禁止由空字符串推断跳过。 */
public record InteractionAnswer(String questionId, List<String> optionIds, String freeText, boolean skipped) {
    /** 结构化答案在进入 Repository 前冻结，具体题目约束由请求聚合再次校验。 */
    public InteractionAnswer {
        questionId = bounded(questionId, "questionId", 128);
        optionIds = List.copyOf(Objects.requireNonNull(optionIds, "optionIds"));
        if (optionIds.size() > 32 || optionIds.stream().anyMatch(value -> !validId(value, "option_"))) {
            throw new IllegalArgumentException("invalid optionIds");
        }
        if (freeText != null && (freeText.length() > 16_000
                || freeText.chars().anyMatch(InteractionAnswer::forbiddenControl))) {
            throw new IllegalArgumentException("invalid freeText");
        }
        if (skipped && (!optionIds.isEmpty() || (freeText != null && !freeText.isBlank()))) {
            throw new IllegalArgumentException("skipped answer cannot contain a value");
        }
    }

    /** 答案字段统一执行长度和控制字符边界，避免持久层收到不可展示内容。 */
    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid " + field);
        return value;
    }

    /** 选项身份只接受固定前缀和可审计字符，防止显示文本成为隐式主键。 */
    private static boolean validId(String value, String prefix) {
        return value != null && value.startsWith(prefix) && value.length() <= 128
                && value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    /** 多行自由文本允许常见空白控制符，其余控制字符仍禁止进入模型和持久层。 */
    private static boolean forbiddenControl(int value) {
        return Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t';
    }
}
