// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { WorkspacePickerPort } from "@/features/workspace";

export interface JaE2eEnvironment {
  readonly VITE_JA_E2E_PROJECT_PATH?: string;
}

const WINDOWS_DRIVE_ABSOLUTE = /^[A-Za-z]:[\\/](?:.*)?$/;
const WINDOWS_UNC_ABSOLUTE = /^\\\\[^\\/]+[\\/][^\\/]+(?:[\\/].*)?$/;
const UNIX_ABSOLUTE = /^\/(?:.*)?$/;

/**
 * 只接纳可原样交给 native workspace contract 的路径；拒绝首尾和控制空白，避免测试
 * 静默规范化目标后验证了错误目录，同时仍允许目录名称内部包含空格。
 */
export function isAbsoluteProjectPath(path: string): boolean {
  if (
    path.length === 0 ||
    path.trim().length === 0 ||
    path !== path.trim() ||
    path.includes("\0") ||
    /[\r\n\t]/.test(path)
  ) {
    return false;
  }
  return (
    WINDOWS_DRIVE_ABSOLUTE.test(path) || WINDOWS_UNC_ABSOLUTE.test(path) || UNIX_ABSOLUTE.test(path)
  );
}

/**
 * E2E 入口必须取得一个精确隔离项目；缺失或畸形路径直接终止启动，不能退回会阻塞
 * 自动化的系统 Dialog，也不能让测试误用真实用户目录。
 */
export function createE2eProjectPicker(environment: JaE2eEnvironment): WorkspacePickerPort {
  const projectPath = environment.VITE_JA_E2E_PROJECT_PATH;
  if (projectPath === undefined || !isAbsoluteProjectPath(projectPath)) {
    throw new Error("Ja E2E project path is missing or invalid");
  }

  /** 返回已验证的精确路径，不在 picker 层再次改变 workspace identity。 */
  const pick = async (): Promise<string> => projectPath;
  return { pick };
}
