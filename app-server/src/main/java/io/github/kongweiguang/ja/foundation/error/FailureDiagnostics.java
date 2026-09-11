// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.error;

import java.util.Arrays;
import java.util.Objects;

/** 仅生成脱敏异常摘要，避免各边界层重复遍历 cause 或把请求载荷写入日志。 */
public final class FailureDiagnostics {
    private static final String APPLICATION_PREFIX = "io.github.kongweiguang.ja.";

    /** 工具类只提供静态脱敏摘要，不允许创建无状态实例。 */
    private FailureDiagnostics() {
    }

    /**
     * 取最深 cause 与首个应用栈帧；摘要只包含类型和代码位置，不包含异常 message、路径或 SQL。
     */
    public static Summary summarize(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String origin = Arrays.stream(root.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith(APPLICATION_PREFIX))
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                .orElse("unknown");
        return new Summary(root.getClass().getName(), origin);
    }

    /** 日志边界使用的固定两字段异常摘要。 */
    public record Summary(String type, String origin) {
        /** 摘要字段必须完整，避免日志调用方再次处理 null。 */
        public Summary {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(origin, "origin");
        }
    }
}
