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
     * 区分项目目录、会话私有目录和只用于找回旧文件的共享目录。
     */
    public enum Kind {
        /**
         * 用户明确打开的项目目录，可启用项目配置、Skill 与 MCP。
         */
        PROJECT,
        /** Java 按 Thread identity 在 Ja Home 下创建的独立目录。 */
        SESSION,
        /** 升级前的共享目录，只由显式 workspaceId 重开。 */
        LEGACY_SHARED
    }
}
