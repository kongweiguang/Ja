// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  findTreeNode,
  parentPath,
  type FilesControllerPorts,
  type WorkspaceFileNode,
} from "@/features/workbench/files";

/** 使用浏览器单次计时器实现 application 时钟端口，避免 Files controller 直接依赖 window。 */
function setBrowserTimer(delayMillis: number, callback: () => void): number {
  return window.setTimeout(callback, delayMillis);
}

/** 清理 composition 创建的计时器；unknown handle 只在浏览器边界收窄为 number。 */
function clearBrowserTimer(handle: unknown): void {
  window.clearTimeout(handle as number);
}

/**
 * 将 native drop 的窗口坐标收窄成文件树目标目录。DOM 命中属于浏览器适配，
 * controller 只接收已验证相对目录，不查询 document 或接触绝对路径。
 */
function resolveNativeDropTarget(
  x: number,
  y: number,
  nodes: readonly WorkspaceFileNode[],
): string | undefined {
  const target = document.elementFromPoint(x, y);
  if (target?.closest<HTMLElement>(".ja-file-tree-host") === null || target === null)
    return undefined;
  const path = target.closest<HTMLElement>("[data-path]")?.dataset["path"];
  const node = path === undefined ? undefined : findTreeNode(nodes, path);
  return node === undefined ? "" : node.kind === "directory" ? node.path : parentPath(node.path);
}

/**
 * 同时监听 window focus 与可见性恢复，因为多窗口切回不一定触发 visibilitychange；
 * 返回统一 cleanup，controller 卸载或 workspace 切换时不会遗留重复监听。
 */
function subscribeBrowserReconciliation(listener: () => void): () => void {
  /** Window 恢复即触发一次权威对账，不读取焦点事件的非稳定细节。 */
  const handleWindowFocus = (): void => listener();
  /** 只有页面重新可见才对账，后台隐藏事件不产生无意义 native IO。 */
  const handleVisibilityChange = (): void => {
    if (document.visibilityState === "visible") listener();
  };
  window.addEventListener("focus", handleWindowFocus);
  document.addEventListener("visibilitychange", handleVisibilityChange);
  /** 同一 cleanup 对称释放两个监听，防止 workspace 切换形成重复 reconciliation owner。 */
  return () => {
    window.removeEventListener("focus", handleWindowFocus);
    document.removeEventListener("visibilitychange", handleVisibilityChange);
  };
}

/**
 * App composition 实现 Files application 所需的浏览器端口；feature ui 只消费 view model/actions，
 * 不再创建计时器、DOM 命中或恢复订阅等具体 adapter。
 */
export const filesBrowserControllerPorts: FilesControllerPorts = {
  timer: {
    set: setBrowserTimer,
    clear: clearBrowserTimer,
  },
  resolveNativeDropTarget,
  subscribeBrowserReconciliation,
};
