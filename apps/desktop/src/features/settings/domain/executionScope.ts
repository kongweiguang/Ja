// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { AccessMode } from "./types";

/**
 * 执行确认页只接收已由 application 层确认过的设置与会话投影；不在 domain
 * 层从默认值推导当前 Turn 权限，避免把项目限制或旧会话事实冒充运行时快照。
 */
export interface ExecutionScope {
  scopedDefault: AccessMode;
  projectOverride: boolean;
  scopeReady: boolean;
  workspaceKind?: "general" | "project";
  threadAccessMode?: AccessMode;
}

/** 使用设置页已有的两档公开名称，保持与权限选择控件一致。 */
export function accessModeLabel(mode: AccessMode): string {
  return mode === "full_access" ? "全部执行" : "需要确认";
}

/**
 * 将权限来源压缩成少量可读句子。项目与会话事实只有在 scopeReady 后才显示，
 * 因为切换 Workspace 时旧的 effective snapshot 仍可能暂时保留在 React 查询缓存中。
 */
export function describeExecutionScope(scope: ExecutionScope): string[] {
  const projectReady = scope.scopeReady && scope.workspaceKind === "project";

  if (scope.workspaceKind === "project" && !scope.scopeReady) {
    return ["正在同步当前项目设置…"];
  }

  const lines = ["新会话采用默认值；已有会话保留选择，执行时以更严格的权限为准。"];
  const facts: string[] = [];

  if (projectReady && scope.projectOverride) {
    facts.push(`当前项目：${accessModeLabel(scope.scopedDefault)}（项目限制）`);
  }

  const threadAccessMode = scope.threadAccessMode;
  const threadReady = scope.scopeReady && threadAccessMode !== undefined;
  if (threadReady) {
    facts.push(`当前会话：${accessModeLabel(threadAccessMode)}（会话选择）`);
  }

  if (facts.length > 0) lines.push(facts.join(" · "));

  return lines;
}
