// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { DesktopPlatform } from "../domain/navigationModels";

/**
 * 平台检测只依赖 Navigator 中这段可注入的最小边界，避免测试耦合 jsdom user-agent，
 * 也避免把浏览器预览误判为 Tauri 桌面窗口。
 */
export interface NavigatorLike {
  readonly platform?: string;
  readonly userAgent?: string;
  readonly userAgentData?: {
    readonly platform?: string;
  };
}

/**
 * 不探测 Tauri、也不引入平台插件即可识别宿主族；UA 字符串只用于布局提示，
 * 绝不能作为原生能力的权威依据。
 */
export function detectDesktopPlatform(navigatorLike?: NavigatorLike): DesktopPlatform {
  const candidate: NavigatorLike =
    navigatorLike ?? (typeof navigator === "undefined" ? {} : navigator);
  const platform = [candidate.platform, candidate.userAgentData?.platform, candidate.userAgent]
    .filter((value): value is string => typeof value === "string")
    .join(" ")
    .toLowerCase();

  // Android 的 UA 常报告 Linux，但它不属于此桌面标题栏合同，因此明确回退到安全的 unknown UI。
  if (platform.includes("android")) return "unknown";
  if (/(?:macintosh|macintel|macppc|mac68k|mac os x|macos)/u.test(platform)) return "macos";
  if (/(?:windows|win32|win64)/u.test(platform)) return "windows";
  if (/(?:linux|x11|wayland)/u.test(platform)) return "linux";
  return "unknown";
}
