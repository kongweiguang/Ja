// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** application 只需要 Host 已校验过的 workspace 目录字段，不依赖具体 Tauri DTO。 */
interface WorkspaceCatalogRecord {
  workspaceId: string;
  root: string;
  displayName: string;
  trust: "trusted" | "untrusted";
  revision: number;
}

/** general workspace 由 Ja App Server 签发身份，React 不通过路径推导它。 */
export interface GeneralWorkspaceRecord {
  workspaceId: string;
  rootPath: string;
  displayName: string;
  trust: "trusted";
}

/** Runtime 投影只携带 workspace 自动打开所需的生命周期栅栏。 */
export interface WorkspaceRuntimeState {
  status:
    | "starting"
    | "ready"
    | "busy"
    | "stopping"
    | "stopped"
    | "recovery_required"
    | "crashed"
    | "incompatible"
    | "faulted";
  generation: number;
  serverInstanceId?: string | null;
}

/**
 * 历史端口只暴露 workspace 用例，避免 controller 因复用宽 HistoryAdapter 而获得 Thread
 * mutation 能力；production adapter 通过 TypeScript 结构类型直接注入。
 */
export interface WorkspaceHistoryPort {
  workspaceOpen?: (input: { cwd: string; displayName?: string }) => Promise<WorkspaceCatalogRecord>;
  workspaceList(input?: { cursor?: string; limit?: number }): Promise<{
    items: WorkspaceCatalogRecord[];
    nextCursor?: string | null;
  }>;
}
