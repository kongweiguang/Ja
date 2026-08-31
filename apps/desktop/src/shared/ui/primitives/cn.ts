// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/**
 * 使用已安装 helper 合并 utility class，避免 feature 组合 primitive 状态时
 * 出现临时 class string 优先级错误。
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
