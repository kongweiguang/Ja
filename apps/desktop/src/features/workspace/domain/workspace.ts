// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

interface HistoryWorkspaceInput {
  workspaceId: string;
  root: string;
  displayName: string;
  trust: "trusted" | "untrusted";
}

interface GeneralWorkspaceInput {
  workspaceId: string;
  rootPath: string;
  displayName: string;
  trust: "trusted" | "untrusted";
}

/**
 * 表示 React 侧可见的 workspace 投影；这里只保留服务端身份和展示所需字段，
 * 避免把 Rust capability 或 Java 配置事实复制进前端领域对象。
 */
export interface WorkspaceProjection {
  kind: "general" | "project";
  workspaceId: string;
  rootPath: string;
  displayName: string;
  trust: "trusted" | "untrusted";
}

/**
 * 将 Java 历史目录投影为 workspace 值对象；转换位于 domain，是因为它不执行 IO，
 * 并统一保证 workspaceId 始终来自服务端而不是由 React 推导。
 */
export function workspaceFromHistory(workspace: HistoryWorkspaceInput): WorkspaceProjection {
  return {
    kind: "project",
    workspaceId: workspace.workspaceId,
    rootPath: workspace.root,
    displayName: workspace.displayName,
    trust: workspace.trust,
  };
}

/**
 * 将原生 general workspace 映射到同一投影；保留单独入口是为了让调用方显式区分
 * 固定范围与持久项目，禁止通过路径或名称猜测 general 身份。
 */
export function workspaceFromGeneral(workspace: GeneralWorkspaceInput): WorkspaceProjection {
  return {
    kind: "general",
    workspaceId: workspace.workspaceId,
    rootPath: workspace.rootPath,
    displayName: workspace.displayName,
    trust: workspace.trust,
  };
}
