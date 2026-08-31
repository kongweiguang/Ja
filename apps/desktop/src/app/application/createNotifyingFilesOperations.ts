// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { FilesWorkspaceOperations } from "@/features/workbench/files";
import { createFilesWorkspaceOperations } from "../workbenchFilesAdapter";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";

/**
 * Watcher 与 native drop 的 setup 失败会被 Files controller 作为 best-effort 处理，因此
 * composition 在端口外层先发布脱敏反馈；普通文件操作错误仍由 Files controller 自己呈现。
 */
export function createNotifyingFilesOperations(
  adapter: JaWorkbenchAdapters["workspace"],
  onNotice: (message: string) => void,
): FilesWorkspaceOperations {
  const operations = createFilesWorkspaceOperations(adapter);
  return {
    ...operations,
    /** Watcher setup 失败时先通知 renderer，再保留 rejected Promise 给 controller 收口。 */
    watchStart: async (input, listener) => {
      try {
        return await operations.watchStart!(input, listener);
      } catch {
        onNotice("文件监控启动失败，请手动刷新。");
        throw new Error("workspace watcher unavailable");
      }
    },
    /** native drop listener 失败只报告稳定用户文案，不泄漏 Tauri 诊断或宿主路径。 */
    subscribeNativeDrop: async (listener) => {
      try {
        return await operations.subscribeNativeDrop!(listener);
      } catch {
        onNotice("系统拖入监听失败，请重新打开工作区。");
        throw new Error("workspace native drop unavailable");
      }
    },
  };
}
