// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.validation;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 为跨端口的不可变值提供统一校验，避免各适配器形成不同的输入边界。
 */
public final class ContractChecks {
    /**
     * 纯静态约束不允许实例化，避免把无状态校验器误注册为运行时服务。
     */
    private ContractChecks() {
    }

    /**
     * 接受可安全进入日志键和协议字段的标识，拒绝空白与控制字符载体。
     */
    public static String identifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256
            || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]*")) {
            throw new IllegalArgumentException(name + " must be a safe non-blank identifier");
        }
        return value;
    }

    /**
     * 配置代际只接受配置 Owner 产生的 `cfg_` 身份；独立入口共享该约束，避免旧 Profile revision
     * 或任意安全字符串在 Tool、权限和模型调用链中重新取得代际语义。
     */
    public static String configurationGeneration(String value) {
        if (value == null || value.length() > 128 || !value.matches("cfg_[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("configGeneration must be a configuration generation identifier");
        }
        return value;
    }

    /**
     * 统一限制非可信文本的长度和 NUL，异常只报告字段名而不回显内容。
     */
    public static String text(String value, String name, int maxLength, boolean allowEmpty) {
        Objects.requireNonNull(value, name);
        if ((!allowEmpty && value.isEmpty()) || value.length() > maxLength || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is outside the contract limits");
        }
        return value;
    }

    /**
     * 将文件边界固定为绝对规范路径，禁止调用方依赖进程工作目录解释相对路径。
     */
    public static Path absolutePath(Path value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be absolute");
        }
        return value.normalize();
    }

    /**
     * 在跨线程发布前复制列表并拒绝空元素，避免持有适配器可变集合。
     */
    public static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " must not contain null values");
        }
        return List.copyOf(values);
    }

}
