// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 原生打开目标使用封闭枚举，Renderer 不能把它扩张为任意可执行文件。 */
export type WorkspaceOpenTarget =
  | "vscode"
  | "visual_studio"
  | "zed"
  | "file_explorer"
  | "terminal"
  | "git_bash"
  | "wsl"
  | "pycharm"
  | "webstorm";

type WorkspaceOpenUnavailableReason = "not_installed" | "unsupported_platform";

/** 可见 target 投影只包含稳定名称和可用性，不携带 executable path。 */
export interface WorkspaceOpenTargetInfo {
  target: WorkspaceOpenTarget;
  displayName: string;
  available: boolean;
  reason: WorkspaceOpenUnavailableReason | null;
}
