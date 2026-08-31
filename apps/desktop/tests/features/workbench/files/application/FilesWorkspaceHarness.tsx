// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactElement } from "react";
import {
  FilesWorkspace,
  findTreeNode,
  parentPath,
  useFilesController,
  type FilesControllerPorts,
  type FilesWorkspaceProps,
  type WorkspaceFileNode,
} from "@/features/workbench/files";

/** 测试时钟仍使用 Vitest 可控的 window timer，使 autosave 不依赖生产 composition。 */
function setTestTimer(delayMillis: number, callback: () => void): number {
  return window.setTimeout(callback, delayMillis);
}

/** 对称释放测试时钟，保证 fake timer 与真实 timer 两种运行方式都不泄漏任务。 */
function clearTestTimer(handle: unknown): void {
  window.clearTimeout(handle as number);
}

/**
 * 测试端口保留真实 DOM 命中规则，验证 controller 只接收目录投影；原生 drop token
 * 和 revision 仍由测试 operations 提供，不能由 Harness 伪造。
 */
function resolveTestDropTarget(
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
 * 测试订阅模拟浏览器 focus/visibility 生命周期，并返回真实 cleanup，确保卸载竞态仍被覆盖。
 */
function subscribeTestReconciliation(listener: () => void): () => void {
  /** focus 只表达需要重新读取权威状态，不携带浏览器事件对象。 */
  const handleWindowFocus = (): void => listener();
  /** hidden 不触发 IO，恢复 visible 才执行一次 reconciliation。 */
  const handleVisibilityChange = (): void => {
    if (document.visibilityState === "visible") listener();
  };
  window.addEventListener("focus", handleWindowFocus);
  document.addEventListener("visibilitychange", handleVisibilityChange);
  /** Harness 卸载必须同时释放两个监听，避免测试间共享全局事件。 */
  return () => {
    window.removeEventListener("focus", handleWindowFocus);
    document.removeEventListener("visibilitychange", handleVisibilityChange);
  };
}

const FILES_TEST_CONTROLLER_PORTS: FilesControllerPorts = {
  timer: { set: setTestTimer, clear: clearTestTimer },
  resolveNativeDropTarget: resolveTestDropTarget,
  subscribeBrowserReconciliation: subscribeTestReconciliation,
};

/**
 * application 集成测试在测试边界完成与生产 App 相同的依赖注入；它不是生产 façade，
 * 因而不会为迁移后的 API 保留第二条运行路径。
 */
export function FilesWorkspaceHarness(props: FilesWorkspaceProps): ReactElement {
  const controller = useFilesController({ ...props, ...FILES_TEST_CONTROLLER_PORTS });
  return <FilesWorkspace viewModel={controller.viewModel} actions={controller.actions} />;
}
