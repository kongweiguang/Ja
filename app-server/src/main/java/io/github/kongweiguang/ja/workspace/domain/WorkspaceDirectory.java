// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 表示已经通过文件系统适配器真实性与约束检查的规范目录。
 */
public record WorkspaceDirectory(Path root, Kind kind) {
    /**
     * 冻结绝对规范路径，防止应用层重新解释相对路径。
     */
    public WorkspaceDirectory {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * 区分有项目配置覆盖层的目录与 Java 自建通用目录。
     */
    public enum Kind {
        /**
         * 用户明确打开的项目目录，可启用项目配置、Skill 与 MCP。
         */
        PROJECT,
        /**
         * Java 数据目录内的无项目工作区，不读取项目配置、Skill 或 MCP。
         */
        GENERAL
    }
}
