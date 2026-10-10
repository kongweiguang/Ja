// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.domain.model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 表示已经通过文件系统适配器真实性与约束检查的规范目录。
 *
 * @param root 根
 * @param kind 类型分类
 */
public record WorkspaceDirectory(Path root,
                                 Kind kind) {
    /**
     * 冻结绝对规范路径，防止应用层重新解释相对路径。
     */
    public WorkspaceDirectory {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * 区分项目目录和会话私有目录，固定各自的创建、重验与信任边界。
     */
    public enum Kind {
        /**
         * 用户明确打开的项目目录，可启用项目配置、Skill 与 MCP。
         */
        PROJECT,
        /**
         * Java 按 Thread identity 在 Ja Home 下创建的独立目录。
         */
        SESSION
    }
}
