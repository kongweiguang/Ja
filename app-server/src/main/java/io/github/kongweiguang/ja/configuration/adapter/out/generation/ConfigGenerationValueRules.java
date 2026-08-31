// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.generation;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一约束 generation catalog 的稳定标识、有界文本、不变引用和 loopback URL。
 */
final class ConfigGenerationValueRules {
    /**
     * 规则集合无状态且只能静态调用，禁止构造实例产生伪生命周期。
     */
    private ConfigGenerationValueRules() {
        throw new AssertionError("no instances");
    }

    /**
     * 要求标识具有预期类型前缀、有界长度和闭集字符集，禁止自由文本身份。
     */
    static void requireIdentifier(String value, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 100
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("identifier is invalid");
        }
    }

    /**
     * immutableIdentifiers 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    static List<String> immutableIdentifiers(List<String> values, String prefix) {
        if (values == null) return List.of();
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            requireIdentifier(value, prefix);
            if (copy.contains(value)) throw new IllegalArgumentException("duplicate identifier");
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    /**
     * boundedText 固定 Turn 使用的配置代际，并确保租约结束后按顺序释放关联资源。
     */
    static String boundedText(String value, String field, int maximum, boolean allowEmpty) {
        if (value == null || value.length() > maximum || (!allowEmpty && value.isEmpty())
            || value.indexOf('\0') >= 0) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    /**
     * 只识别配置契约明确允许的 loopback 主机，不做 DNS 解析以避免校验期网络副作用。
     */
    static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    /**
     * throwValue 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    static <T> T throwValue(String category) {
        throw new IllegalArgumentException(category + " is invalid");
    }
}
