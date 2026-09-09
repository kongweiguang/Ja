// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.filesystem;

import java.nio.file.Path;
import java.util.Objects;

/** 为跨进程边界传入的 Windows namespaced 路径提供唯一的词法身份。 */
public final class PathIdentities {
    /** 纯静态身份规则不允许被实例化或持有运行时状态。 */
    private PathIdentities() {
    }

    /**
     * Windows sidecar 与 WebView 可分别交付 namespaced 和普通盘符路径；去掉命名空间前缀
     * 仅用于同机身份比较，实际文件 IO 仍使用准入层返回的原始路径。
     */
    public static Path normalized(Path value) {
        String normalized = Objects.requireNonNull(value, "path").toAbsolutePath().normalize().toString();
        if (normalized.regionMatches(true, 0, "\\\\?\\UNC\\", 0, 8)) {
            normalized = "\\\\" + normalized.substring(8);
        } else if (normalized.regionMatches(true, 0, "\\\\?\\", 0, 4)) {
            normalized = normalized.substring(4);
        }
        return Path.of(normalized).toAbsolutePath().normalize();
    }
}
