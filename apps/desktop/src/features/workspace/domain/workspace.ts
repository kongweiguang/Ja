// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type WorkspaceKind = "project" | "session" | "legacy_shared";

interface WorkspaceProjectionInput {
  workspaceId: string;
  kind: WorkspaceKind;
  legacySharedWorkspaceId: string | null;
  root: string;
  displayName: string;
  trust: "trusted" | "untrusted";
}

/**
 * 表示 React 侧可见的 Java/Rust 权威 workspace 投影；目录和关联身份只能来自服务端，
 * renderer 不会根据 thread id 或路径名推导工作目录。
 */
export interface WorkspaceProjection {
  kind: WorkspaceKind;
  workspaceId: string;
  legacySharedWorkspaceId: string | null;
  rootPath: string;
  displayName: string;
  trust: "trusted" | "untrusted";
}

/** 将持久 workspace projection 映射到值对象，同时完整保留服务端发出的 kind 与关联字段。 */
export function workspaceFromHistory(workspace: WorkspaceProjectionInput): WorkspaceProjection {
  return {
    kind: workspace.kind,
    workspaceId: workspace.workspaceId,
    legacySharedWorkspaceId: workspace.legacySharedWorkspaceId,
    rootPath: workspace.root,
    displayName: workspace.displayName,
    trust: workspace.trust,
  };
}

/** 将 Rust activation 的 canonical root 转成 WorkspaceProjection，不复制或合成目录身份。 */
export function workspaceFromActivation(
  workspace: Omit<WorkspaceProjectionInput, "root"> & { rootPath: string },
): WorkspaceProjection {
  return {
    kind: workspace.kind,
    workspaceId: workspace.workspaceId,
    legacySharedWorkspaceId: workspace.legacySharedWorkspaceId,
    rootPath: workspace.rootPath,
    displayName: workspace.displayName,
    trust: workspace.trust,
  };
}
