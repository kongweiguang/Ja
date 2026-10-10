// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.shared;

import java.util.Objects;

/**
 * 提供不依赖领域类型的前置条件和数值约束，保持非法输入在边界处失败。
 */
public final class Validation {
    /**
     * 禁止实例化无状态校验工具类，避免调用方保存不必要的校验器实例。
     */
    private Validation() {
    }

    /**
     * 校验引用存在，统一空值异常字段名并返回原值以便构造器链式使用。
     *
     * @param value 待校验引用
     * @param name 字段名
     * @param <T> 引用类型
     * @return 非空原值
     */
    public static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, Objects.requireNonNull(name, "name"));
    }

    /**
     * 校验布尔前置条件，避免在每个调用点重复构造同类非法参数异常。
     *
     * @param condition 条件
     * @param message 稳定诊断消息
     */
    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(Objects.requireNonNull(message, "message"));
        }
    }

    /**
     * 校验正整数容量或序号，零值必须由调用方显式选择其它语义。
     *
     * @param value 数值
     * @param name 字段名
     * @return 原数值
     */
    public static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(Objects.requireNonNull(name, "name") + " must be positive");
        }
        return value;
    }

    /**
     * 校验非负计数，防止持久化计量被负数污染。
     *
     * @param value 数值
     * @param name 字段名
     * @return 原数值
     */
    public static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(Objects.requireNonNull(name, "name") + " must not be negative");
        }
        return value;
    }
}
