// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.port.out;

import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 隔离 Workspace application 与 NIO 枚举、链接检测和 deadline 实现。
 */
public interface WorkspacePathPort {
    /** 只扫描条目名称与相对路径，不打开普通文件或读取正文。 */
    SearchOutcome search(Path workspaceRoot, String query, int limit);

    /** 在消息真正接纳或消费前重验引用目标及声明类型。 */
    ValidatedPath validate(Path workspaceRoot, String relativePath, WorkspaceEntryKind kind);

    /** Adapter 返回已按产品顺序裁剪的条目及诚实截断标记。 */
    record SearchOutcome(List<PathEntry> entries, boolean truncated, int scannedEntries) {
        /** 复制结果并保留内部扫描计数，计数不进入公开 Wire。 */
        public SearchOutcome {
            entries = List.copyOf(entries);
            if (scannedEntries < 0) {
                throw new IllegalArgumentException("scannedEntries must not be negative");
            }
        }
    }

    /** 文件系统适配器只能返回规范相对路径和闭集类型。 */
    record PathEntry(String relativePath, WorkspaceEntryKind kind) {
        /** 防止错误 adapter 把绝对或空路径带回应用层。 */
        public PathEntry {
            relativePath = Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** 引用准入结果保留标准化相对路径，不泄露底层物理 Path。 */
    record ValidatedPath(String relativePath, WorkspaceEntryKind kind) {
        /** 冻结已经由 adapter 核验的路径与类型。 */
        public ValidatedPath {
            relativePath = Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(kind, "kind");
        }
    }
}
