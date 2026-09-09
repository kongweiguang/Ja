// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useTerminalLayoutPersistence } from "@/features/workbench/terminal/application/useTerminalLayoutPersistence";
import {
  createDefaultTerminalLayout,
  splitTerminalPane,
  type TerminalLayoutV1,
} from "@/features/workbench/terminal/domain/terminalLayout";
import type { TerminalLayoutStorage } from "@/features/workbench/terminal/application/terminalLayoutStorage";

/** 构造只记录严格布局的最小介质，避免 hook 测试依赖浏览器 localStorage 全局。 */
function storageWith(layouts: Readonly<Record<string, TerminalLayoutV1>>): TerminalLayoutStorage {
  return {
    load: vi.fn((workspaceId: string) => layouts[workspaceId]),
    save: vi.fn(),
  };
}

describe("useTerminalLayoutPersistence", () => {
  /** controller 提交必须立即成为同一 WorkbenchHost 的重挂输入，不能回退到启动快照。 */
  it("projects the latest saved dormant layout for same-process controller remounts", () => {
    const initial = createDefaultTerminalLayout("ws_fixture");
    const tab = initial.tabs[0]!;
    const latest = splitTerminalPane(initial, tab.tabId, tab.activePaneId, "horizontal");
    const storage = storageWith({ ws_fixture: initial });
    const { result } = renderHook(() => useTerminalLayoutPersistence("ws_fixture", storage));

    act(() => result.current[1](latest));

    expect(result.current[0]).toEqual(latest);
    expect(storage.save).toHaveBeenCalledWith(latest);
  });

  /** 介质故障不应破坏当前会话已确认布局，但新 workspace 必须重新读取自己的严格快照。 */
  it("retains the live projection on save failure and reloads when workspace ownership changes", () => {
    const first = createDefaultTerminalLayout("ws_first");
    const firstTab = first.tabs[0]!;
    const latest = splitTerminalPane(first, firstTab.tabId, firstTab.activePaneId, "vertical");
    const second = createDefaultTerminalLayout("ws_second");
    const storage = storageWith({ ws_first: first, ws_second: second });
    vi.mocked(storage.save).mockImplementation(() => {
      throw new Error("quota");
    });
    const { result, rerender } = renderHook(
      ({ workspaceId }) => useTerminalLayoutPersistence(workspaceId, storage),
      { initialProps: { workspaceId: "ws_first" } },
    );

    act(() => result.current[1](latest));
    expect(result.current[0]).toEqual(latest);

    rerender({ workspaceId: "ws_second" });
    expect(result.current[0]).toEqual(second);
    expect(storage.load).toHaveBeenLastCalledWith("ws_second");
  });
});
