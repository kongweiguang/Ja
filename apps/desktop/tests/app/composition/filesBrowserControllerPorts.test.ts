// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from "vitest";
import { filesBrowserControllerPorts } from "@/app/composition/filesBrowserControllerPorts";
import type { WorkspaceFileNode } from "@/features/workbench/files";

describe("filesBrowserControllerPorts", () => {
  afterEach(() => {
    vi.useRealTimers();
    document.body.replaceChildren();
    vi.restoreAllMocks();
  });

  it("只在窗口恢复或页面重新可见时请求权威对账，并在 cleanup 后停止投递", () => {
    const listener = vi.fn();
    const visibility = Object.getOwnPropertyDescriptor(document, "visibilityState");
    const cleanup = filesBrowserControllerPorts.subscribeBrowserReconciliation(listener);
    try {
      window.dispatchEvent(new Event("focus"));
      Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
      document.dispatchEvent(new Event("visibilitychange"));
      Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
      document.dispatchEvent(new Event("visibilitychange"));
      expect(listener).toHaveBeenCalledTimes(2);

      cleanup();
      window.dispatchEvent(new Event("focus"));
      document.dispatchEvent(new Event("visibilitychange"));
      expect(listener).toHaveBeenCalledTimes(2);
    } finally {
      cleanup();
      if (visibility === undefined) Reflect.deleteProperty(document, "visibilityState");
      else Object.defineProperty(document, "visibilityState", visibility);
    }
  });

  it("把文件命中收窄为父目录，并把树根命中投影为空相对路径", () => {
    const host = document.createElement("div");
    host.className = "ja-file-tree-host";
    const file = document.createElement("button");
    file.dataset["path"] = "src/main.ts";
    host.append(file);
    document.body.append(host);
    const elementFromPoint = Object.getOwnPropertyDescriptor(document, "elementFromPoint");
    const findElement = vi.fn((): Element | null => file);
    Object.defineProperty(document, "elementFromPoint", {
      configurable: true,
      value: findElement,
    });
    const nodes: readonly WorkspaceFileNode[] = [
      {
        id: "root",
        path: "",
        name: "workspace",
        kind: "directory",
        children: [
          {
            id: "src",
            path: "src",
            name: "src",
            kind: "directory",
            children: [
              {
                id: "main",
                path: "src/main.ts",
                name: "main.ts",
                kind: "file",
              },
            ],
          },
        ],
      },
    ];
    try {
      expect(filesBrowserControllerPorts.resolveNativeDropTarget(10, 10, nodes)).toBe("src");
      findElement.mockReturnValue(host);
      expect(filesBrowserControllerPorts.resolveNativeDropTarget(10, 10, nodes)).toBe("");
    } finally {
      if (elementFromPoint === undefined) Reflect.deleteProperty(document, "elementFromPoint");
      else Object.defineProperty(document, "elementFromPoint", elementFromPoint);
    }
  });

  it("通过可取消计时端口调度 autosave", () => {
    vi.useFakeTimers();
    const callback = vi.fn();
    const handle = filesBrowserControllerPorts.timer.set(300, callback);
    vi.advanceTimersByTime(299);
    expect(callback).not.toHaveBeenCalled();
    filesBrowserControllerPorts.timer.clear(handle);
    vi.advanceTimersByTime(1);
    expect(callback).not.toHaveBeenCalled();
  });
});
