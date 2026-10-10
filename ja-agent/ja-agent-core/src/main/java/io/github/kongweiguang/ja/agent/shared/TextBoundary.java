// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.shared;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 统一标识和非可信文本的边界检查；错误只暴露字段名，不回显输入内容。
 */
public final class TextBoundary {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]*");

    /**
     * 禁止实例化边界校验工具类，保持校验规则为无状态且可在所有入口复用。
     */
    private TextBoundary() {
    }

    /**
     * 校验可进入日志键、持久键或协议字段的稳定标识。
     *
     * @param value 待校验标识
     * @param name 字段名
     * @return 原始标识
     */
    public static String identifier(String value, String name) {
        String field = fieldName(name);
        if (value == null || value.isBlank() || value.length() > 256 || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a safe non-blank identifier");
        }
        return value;
    }

    /**
     * 校验一般文本的长度和 NUL 边界，可选择是否接受空字符串。
     *
     * @param value 待校验文本
     * @param name 字段名
     * @param maxLength 最大 Java 字符长度，不能为负
     * @param allowEmpty 是否允许空文本
     * @return 原始文本
     */
    public static String text(String value, String name, int maxLength, boolean allowEmpty) {
        String field = fieldName(name);
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must not be negative");
        }
        Objects.requireNonNull(value, field);
        if ((!allowEmpty && value.isEmpty()) || value.length() > maxLength || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is outside the contract limits");
        }
        return value;
    }

    /**
     * 校验单行文本，适合标识描述和错误消息等不能改变协议行边界的字段。
     *
     * @param value 待校验文本
     * @param name 字段名
     * @param maxLength 最大 Java 字符长度
     * @param allowEmpty 是否允许空文本
     * @return 原始文本
     */
    public static String singleLine(String value, String name, int maxLength, boolean allowEmpty) {
        String checked = text(value, name, maxLength, allowEmpty);
        if (checked.indexOf('\n') >= 0 || checked.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(fieldName(name) + " must be single-line text");
        }
        return checked;
    }

    /**
     * 确保字段名本身可用于异常消息，避免校验失败时丢失上下文。
     *
     * @param name 字段名
     * @return 非空字段名
     */
    private static String fieldName(String name) {
        return Objects.requireNonNull(name, "name");
    }
}
