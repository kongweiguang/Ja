// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, render, renderHook, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { WorkspaceOpenTargetInfo } from "@/features/conversation/domain/openTarget";
import { latestReplyFilePaths } from "@/features/conversation/domain/replyFiles";
import type { TimelineItemAdapter } from "@/features/conversation/domain/timelineTypes";
import type { WorkspaceOpenPort } from "@/features/conversation/application/ports";
import { useReplyFileOpen } from "@/features/conversation/application/useReplyFileOpen";
import { ReplyFileOpenMenu } from "@/features/conversation/ui/reply-files/ReplyFileOpenMenu";

afterEach(() => cleanup());

/** 创建 Reply File 所有权测试所需的最小真实 Item 形状。 */
function item(
  itemId: string,
  kind: TimelineItemAdapter["kind"],
  relativePaths?: string[],
): TimelineItemAdapter {
  return {
    itemId,
    threadId: "thr_fixture",
    turnId: `turn_${itemId}`,
    kind,
    status: "completed",
    ...(relativePaths === undefined
      ? {}
      : {
          metadata: {
            presentation: {
              kind: "edit" as const,
              title: "修改文件",
              status: "success" as const,
              relativePaths,
              truncated: false,
            },
          },
        }),
  };
}

const targetDtos: WorkspaceOpenTargetInfo[] = [
  { target: "vscode", displayName: "VS Code", available: true, reason: null },
  {
    target: "visual_studio",
    displayName: "Visual Studio",
    available: false,
    reason: "not_installed",
  },
  { target: "zed", displayName: "Zed", available: false, reason: "not_installed" },
  { target: "file_explorer", displayName: "文件资源管理器", available: true, reason: null },
  { target: "terminal", displayName: "终端", available: true, reason: null },
  { target: "git_bash", displayName: "Git Bash", available: false, reason: "not_installed" },
  { target: "wsl", displayName: "WSL", available: false, reason: "not_installed" },
  { target: "pycharm", displayName: "PyCharm", available: true, reason: null },
  { target: "webstorm", displayName: "WebStorm", available: false, reason: "not_installed" },
];

/** 构建类型化 Adapter，同时让无关 Workspace Read 留在该 Feature 职责之外。 */
function createAdapter(): WorkspaceOpenPort {
  return {
    openTargets: vi.fn(async () => ({ targets: targetDtos })),
    open: vi.fn(async (input) => ({
      opened: true as const,
      target: input.target,
      relativePath: input.relativePath ?? "",
      entryKind: "file" as const,
    })),
  };
}

/** 为 Retry Generation 测试提供显式 Late Result 边界，避免依赖 Sleep。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

describe("reply file opening", () => {
  it("keeps only safe, unique files from the latest user turn", () => {
    const items = [
      item("user_old", "user_message"),
      item("file_old", "command", ["old.ts"]),
      item("user_new", "user_message"),
      item("file_new", "command", ["src/App.tsx", "src/App.tsx", "C:/private.txt", "../escape.ts"]),
      item("final_new", "agent_message"),
    ];
    expect(latestReplyFilePaths(items)).toEqual(["src/App.tsx"]);
    expect(latestReplyFilePaths([item("orphan_file", "command", ["src/orphan.ts"])])).toEqual([]);
  });

  it("discovers installed editors only and opens an authoritative reply path", async () => {
    const adapter = createAdapter();
    const items = [item("user_one", "user_message"), item("file_one", "command", ["src/App.tsx"])];
    const { result } = renderHook(() => useReplyFileOpen("ws_fixture", items, adapter));
    await waitFor(() => expect(result.current.discovering).toBe(false));
    expect(result.current.targets.map((target) => target.target)).toEqual(["vscode", "pycharm"]);

    await act(async () => result.current.onOpen("src/App.tsx", "vscode"));
    expect(adapter.open).toHaveBeenCalledWith({
      workspaceId: "ws_fixture",
      target: "vscode",
      relativePath: "src/App.tsx",
    });
    await act(async () => result.current.onOpen("old.ts", "vscode"));
    expect(adapter.open).toHaveBeenCalledTimes(1);
    expect(result.current.error).toBe("这个文件或编辑器当前不可用。");
  });

  it("retries failed discovery and ignores its late result after the workspace generation changes", async () => {
    const adapter = createAdapter();
    const staleRetry = deferred<{ targets: WorkspaceOpenTargetInfo[] }>();
    vi.mocked(adapter.openTargets)
      .mockRejectedValueOnce(new Error("private discovery failure"))
      .mockImplementationOnce(() => staleRetry.promise)
      .mockResolvedValueOnce({ targets: [targetDtos[7]!] });
    const items = [
      item("user_retry", "user_message"),
      item("file_retry", "command", ["src/App.tsx"]),
    ];
    const { result, rerender } = renderHook(
      ({ workspaceId }) => useReplyFileOpen(workspaceId, items, adapter),
      {
        initialProps: { workspaceId: "ws_old" },
      },
    );

    await waitFor(() => expect(result.current.error).toBe("无法读取本机编辑器，请稍后重试。"));
    expect(result.current.onRetryDiscovery).toEqual(expect.any(Function));
    act(() => result.current.onRetryDiscovery?.());
    await waitFor(() => expect(adapter.openTargets).toHaveBeenCalledTimes(2));

    rerender({ workspaceId: "ws_new" });
    await waitFor(() => expect(adapter.openTargets).toHaveBeenCalledTimes(3));
    await waitFor(() =>
      expect(result.current.targets.map((target) => target.target)).toEqual(["pycharm"]),
    );
    await act(async () => {
      staleRetry.resolve({ targets: [targetDtos[0]!] });
      await staleRetry.promise;
    });
    expect(result.current.targets.map((target) => target.target)).toEqual(["pycharm"]);
  });

  it("renders the middle-header menu and routes the selected installed editor", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    render(
      <ReplyFileOpenMenu files={["src/App.tsx"]} targets={[targetDtos[0]!]} onOpen={onOpen} />,
    );
    await user.click(screen.getByRole("button", { name: "打开回复中的文件" }));
    expect(screen.getByText("App.tsx")).toHaveAttribute("title", "src/App.tsx");
    await user.click(screen.getByRole("menuitem", { name: "VS Code" }));
    expect(onOpen).toHaveBeenCalledWith("src/App.tsx", "vscode");
  });

  it("renders a real retry action for discovery failures", async () => {
    const user = userEvent.setup();
    const onRetryDiscovery = vi.fn();
    render(
      <ReplyFileOpenMenu
        files={["src/App.tsx"]}
        targets={[]}
        error="无法读取本机编辑器，请稍后重试。"
        onRetryDiscovery={onRetryDiscovery}
        onOpen={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "打开回复中的文件" }));
    expect(screen.queryByText("没有检测到可用编辑器")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试查找编辑器" }));
    expect(onRetryDiscovery).toHaveBeenCalledOnce();
  });
});
