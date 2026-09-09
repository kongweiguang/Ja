// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { isTauri } from "@tauri-apps/api/core";
import { relaunch } from "@tauri-apps/plugin-process";
import { check, type DownloadEvent, type Update } from "@tauri-apps/plugin-updater";

export type DesktopUpdateCheckResult =
  | { kind: "unavailable" }
  | { kind: "up-to-date" }
  | {
      kind: "available";
      currentVersion: string;
      version: string;
      publishedAt?: string;
    };

export interface DesktopUpdateProgress {
  readonly downloadedBytes: number;
  readonly contentLength?: number;
  readonly percent?: number;
}

let pendingUpdate: Update | undefined;
let activeCheck: Promise<DesktopUpdateCheckResult> | undefined;

/** 释放被新检查替换的 native Resource，避免反复手动检查在 WebView 生命周期内累积句柄。 */
async function releasePendingUpdate(): Promise<void> {
  const update = pendingUpdate;
  pendingUpdate = undefined;
  if (update !== undefined) await update.close();
}

/** 将插件的增量下载事件转换为稳定累计进度，未知总长度时不伪造百分比。 */
function projectDownloadEvent(
  event: DownloadEvent,
  downloadedBytes: number,
  contentLength?: number,
): DesktopUpdateProgress {
  if (event.event === "Started") {
    return {
      downloadedBytes: 0,
      contentLength: event.data.contentLength,
      percent: event.data.contentLength === undefined ? undefined : 0,
    };
  }
  const nextDownloaded =
    event.event === "Progress" ? downloadedBytes + event.data.chunkLength : downloadedBytes;
  const percent =
    contentLength === undefined || contentLength <= 0
      ? undefined
      : Math.min(100, Math.round((nextDownloaded / contentLength) * 100));
  return { downloadedBytes: nextDownloaded, contentLength, percent };
}

/**
 * 检查 GitHub 静态更新端点，并把并发调用合并为一次网络请求；浏览器预览显式返回不可用，
 * 避免开发页面产生误导性的“已是最新”状态。
 */
export async function checkForDesktopUpdate(): Promise<DesktopUpdateCheckResult> {
  if (!isTauri()) return { kind: "unavailable" };
  if (activeCheck !== undefined) return activeCheck;
  activeCheck = (async () => {
    await releasePendingUpdate();
    const update = await check({ timeout: 15_000 });
    if (update === null) return { kind: "up-to-date" };
    pendingUpdate = update;
    return {
      kind: "available",
      currentVersion: update.currentVersion,
      version: update.version,
      publishedAt: update.date,
    };
  })();
  try {
    return await activeCheck;
  } finally {
    activeCheck = undefined;
  }
}

/**
 * 只安装最近一次检查持有的签名更新；Windows installer 会在 install 阶段接管退出，
 * macOS 在 install 返回后由 UI 明确请求重启，避免把下载失败误报为待重启。
 */
export async function installPendingDesktopUpdate(
  onProgress: (progress: DesktopUpdateProgress) => void,
): Promise<void> {
  const update = pendingUpdate;
  if (update === undefined) throw new Error("desktop update is not available");
  let downloadedBytes = 0;
  let contentLength: number | undefined;
  try {
    await update.download((event) => {
      const progress = projectDownloadEvent(event, downloadedBytes, contentLength);
      downloadedBytes = progress.downloadedBytes;
      contentLength = progress.contentLength;
      onProgress(progress);
    });
    await update.install();
  } finally {
    if (pendingUpdate === update) pendingUpdate = undefined;
    try {
      await update.close();
    } catch {
      // Windows installer 可能已先释放进程级 resource；清理失败不能把已完成的安装改写为失败。
    }
  }
}

/** 安装完成但平台未能自动重启时，仅重启当前 Ja 进程，不暴露任意 exit code。 */
export async function relaunchAfterDesktopUpdate(): Promise<void> {
  if (!isTauri()) throw new Error("desktop update relaunch is unavailable");
  await relaunch();
}
