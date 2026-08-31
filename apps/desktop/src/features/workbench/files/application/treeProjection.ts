// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { WorkspaceTreeEntryDto } from "./ports";
import type { WorkspaceFileNode } from "../domain/types";

/**
 * 在 application 边界把 native Tree DTO 映射为领域节点，是为了让 domain 不依赖端口
 * 形状，同时保留 runtime 权威相对路径，UI 不需要根据名称重建身份。
 */
export function mapTreeEntries(entries: readonly WorkspaceTreeEntryDto[]): WorkspaceFileNode[] {
  return entries.map((entry) => ({
    id: `${entry.kind}:${entry.relativePath || entry.name}`,
    name: entry.name,
    path: entry.relativePath,
    kind: entry.kind,
    hasChildren: entry.hasChildren ?? entry.kind === "directory",
  }));
}
