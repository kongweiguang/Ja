// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.error;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * 仅生成脱敏异常摘要，避免各边界层重复遍历 cause 或把请求载荷写入日志。
 */
public final class FailureDiagnostics {
    private static final String APPLICATION_PREFIX = "io.github.kongweiguang.ja.";

    /**
     * 工具类只提供静态脱敏摘要，不允许创建无状态实例。
     */
    private FailureDiagnostics() {
    }

    /**
     * 取 cause 链中未重复的最深异常与最多八个应用栈帧；用身份集合阻止第三方间接 cause 环挂住错误处理。
     * 有界调用位置区分共享取消/CAS 入口的实际调用方，不包含 message、文件名、路径、SQL 或请求载荷。
     */
    public static Summary summarize(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Throwable root = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (visited.add(root)) {
            Throwable cause = root.getCause();
            if (cause == null) {
                break;
            }
            root = cause;
        }
        String origin = Arrays.stream(root.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith(APPLICATION_PREFIX))
                .limit(8)
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                .collect(java.util.stream.Collectors.joining(" <- "));
        return new Summary(root.getClass().getName(), origin.isEmpty() ? "unknown" : origin);
    }

    /**
     * 日志边界使用的固定两字段异常摘要。
     *
     * @param type 类型标识
     * @param origin 来源分类
     */
    public record Summary(String type,
                          String origin) {
        /**
         * 摘要字段必须完整，避免日志调用方再次处理 null。
         */
        public Summary {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(origin, "origin");
        }
    }
}
