// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.domain;

/**
 * Workspace 引用允许进入公开合同的稳定条目类型，链接与其它特殊文件不属于该闭集。
 */
public enum WorkspaceEntryKind {
    /** 普通常规文件。 */
    FILE,
    /** 普通物理目录。 */
    DIRECTORY
}
