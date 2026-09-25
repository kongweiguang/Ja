// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单次原生客户端执行环境的完整不可变内存值；Run/Turn 只传引用，
 * 不把宿主变量投影给 React、SQLite 或普通日志。
 */
public record NativeExecutionSnapshot(Map<String, String> environment, String shell) {
    private static final int MAX_ENVIRONMENT_CHARACTERS = 2_000_000;

    /** 只拒绝原生进程无法表示的 NUL 和畸形名字，保留 PATH、代理及全部登录变量。 */
    public NativeExecutionSnapshot {
        Objects.requireNonNull(environment, "environment");
        Map<String, String> copy = new LinkedHashMap<>(environment.size());
        long characters = 0;
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || value == null
                    || value.indexOf('\0') >= 0 || name.indexOf('=') > 0) {
                throw new IllegalArgumentException("native execution environment is invalid");
            }
            characters += (long) name.length() + value.length();
            if (characters > MAX_ENVIRONMENT_CHARACTERS) {
                throw new IllegalArgumentException("native execution environment is too large");
            }
            copy.put(name, value);
        }
        environment = Map.copyOf(copy);
        if (shell != null && (shell.isBlank() || shell.indexOf('\0') >= 0 || shell.length() > 8_192)) {
            throw new IllegalArgumentException("native shell path is invalid");
        }
    }

    /** 值可能含凭据，诊断只能显示稳定类型而不能展开 record 默认字段。 */
    @Override
    public String toString() {
        return "NativeExecutionSnapshot[redacted]";
    }
}
