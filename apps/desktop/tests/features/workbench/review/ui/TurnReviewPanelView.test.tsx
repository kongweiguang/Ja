// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  TurnReviewPanelView,
  type TurnReviewFileContent,
  type TurnReviewPort,
  type TurnReviewTarget,
} from "@/features/workbench/review";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const files = ["src/a.ts", "src/b.ts", "src/c.ts"].map((path) => ({
  path,
  status: "modified" as const,
  additions: 1,
  deletions: 1,
  binary: false,
  truncated: false,
}));

const target: TurnReviewTarget = {
  kind: "frozen_turn",
  workspaceId: "ws_one",
  threadId: "thr_one",
  turnId: "turn_latest",
  threadRevision: 3,
  completedAt: "2026-09-01T08:00:00Z",
  state: "complete",
  incompleteReasons: [],
  files,
  stats: { files: 3, additions: 3, deletions: 3, binaryFiles: 0, truncated: false },
  artifactId: "artifact_latest",
};

/** 构造可由严格 identity/length 门禁接受的单文件冻结结果。 */
function result(path: string, marker = path): TurnReviewFileContent {
  const content = `--- a/${path}\n+++ b/${path}\n@@ -1 +1 @@\n-old\n+${marker}\n`;
  return {
    artifactId: target.artifactId,
    filePath: path,
    byteLength: new TextEncoder().encode(content).byteLength,
    sha256: "a".repeat(64),
    content,
  };
}

/** 默认端口每次都生成新结果，测试不会借缓存绕过真实 read 调用。 */
function port(
  readFrozen: TurnReviewPort["readFrozen"] = vi.fn(async (_target, file) => result(file.path)),
): TurnReviewPort {
  return { readFrozen };
}

/** 可控 Promise 用于精确推进竞态，不依赖不稳定的 wall-clock sleep。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((nextResolve) => {
    resolve = nextResolve;
  });
  return { promise, resolve };
}

describe("TurnReviewPanelView", () => {
  it("只读取冻结 artifact，并展示所选文件的纯文本 Diff", async () => {
    const readFrozen = vi.fn(async (_target, file: (typeof files)[number]) => result(file.path));
    render(
      <TurnReviewPanelView
        target={target}
        port={port(readFrozen)}
        active
        scopeLabel="第 2 轮修改"
        onShowWorkspaceReview={vi.fn()}
      />,
    );

    expect(screen.getByLabelText("本轮修改查看")).toBeVisible();
    expect(screen.getByText("第 2 轮修改")).toBeInTheDocument();
    await screen.findByText("src/a.ts", { selector: "code" });
    expect(readFrozen).toHaveBeenCalledWith(target, files[0], expect.any(AbortSignal));
    expect(screen.getByLabelText("统一 Diff src/a.ts")).toHaveTextContent("old");
  });

  it("A-B-A 每次选择都重新读取，完成结果不缓存", async () => {
    const readFrozen = vi.fn(async (_target, file: (typeof files)[number]) => result(file.path));
    render(
      <TurnReviewPanelView
        target={target}
        port={port(readFrozen)}
        active
        onShowWorkspaceReview={vi.fn()}
      />,
    );
    await waitFor(() => expect(readFrozen).toHaveBeenCalledTimes(1));
    const user = userEvent.setup();
    await user.click(screen.getByRole("treeitem", { name: "查看 src/b.ts 的本轮修改" }));
    await waitFor(() => expect(readFrozen).toHaveBeenCalledTimes(2));
    await user.click(screen.getByRole("treeitem", { name: "查看 src/a.ts 的本轮修改" }));
    await waitFor(() => expect(readFrozen).toHaveBeenCalledTimes(3));
    expect(readFrozen.mock.calls.map(([, file]) => file.path)).toEqual([
      "src/a.ts",
      "src/b.ts",
      "src/a.ts",
    ]);
  });

  /** 冻结回合只读，不应提供可修改 Git 状态的右键入口。 */
  it("冻结的本轮文件不显示变更操作菜单", async () => {
    render(
      <TurnReviewPanelView target={target} port={port()} active onShowWorkspaceReview={vi.fn()} />,
    );
    const fileRow = await screen.findByRole("treeitem", {
      name: "查看 src/a.ts 的本轮修改",
    });
    fireEvent.contextMenu(fileRow, { clientX: 28, clientY: 36 });

    expect(screen.queryByRole("menuitem")).not.toBeInTheDocument();
    expect(screen.getByRole("treeitem", { name: "查看 src/a.ts 的本轮修改" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  it("新标题立即替换旧正文，旧请求迟到也不能覆盖当前文件", async () => {
    const a = deferred<TurnReviewFileContent>();
    const b = deferred<TurnReviewFileContent>();
    const readFrozen = vi.fn(async (_target, file: (typeof files)[number]) =>
      file.path === "src/a.ts" ? a.promise : b.promise,
    );
    render(
      <TurnReviewPanelView
        target={target}
        port={port(readFrozen)}
        active
        onShowWorkspaceReview={vi.fn()}
      />,
    );
    await waitFor(() => expect(readFrozen).toHaveBeenCalledTimes(1));
    await userEvent
      .setup()
      .click(screen.getByRole("treeitem", { name: "查看 src/b.ts 的本轮修改" }));
    expect(screen.getByRole("main", { name: "本轮 Unified diff" })).toHaveTextContent("src/b.ts");
    expect(screen.queryByLabelText("统一 Diff src/a.ts")).not.toBeInTheDocument();

    b.resolve(result("src/b.ts", "new-B"));
    await screen.findByText("new-B");
    a.resolve(result("src/a.ts", "late-A"));
    await act(async () => Promise.resolve());
    expect(screen.getByText("new-B")).toBeVisible();
    expect(screen.queryByText("late-A")).not.toBeInTheDocument();
  });

  it("读取不足 120ms 时不闪 loading，超过阈值才展示轻量状态", async () => {
    vi.useFakeTimers();
    const pending = deferred<TurnReviewFileContent>();
    render(
      <TurnReviewPanelView
        target={target}
        port={port(vi.fn(() => pending.promise))}
        active
        onShowWorkspaceReview={vi.fn()}
      />,
    );
    await act(async () => Promise.resolve());
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(document.querySelectorAll(".ja-review-spin, .ja-turn-review-spin")).toHaveLength(0);
    expect(screen.getByRole("button", { name: "重新读取本轮修改" })).toBeEnabled();
    act(() => vi.advanceTimersByTime(119));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(document.querySelectorAll(".ja-review-spin, .ja-turn-review-spin")).toHaveLength(0);
    act(() => vi.advanceTimersByTime(1));
    expect(screen.getByRole("status")).toHaveTextContent("正在读取src/a.ts");
    expect(document.querySelectorAll(".ja-review-spin, .ja-turn-review-spin")).toHaveLength(2);
    expect(screen.getByRole("button", { name: "重新读取本轮修改" })).toBeDisabled();
    pending.resolve(result("src/a.ts"));
    await act(async () => Promise.resolve());
  });

  it("隐藏立即释放正文且不因路径选择或 rerender 发起新读取", async () => {
    const readFrozen = vi.fn(async (_target, file: (typeof files)[number]) => result(file.path));
    const view = render(
      <TurnReviewPanelView
        target={target}
        port={port(readFrozen)}
        active
        onShowWorkspaceReview={vi.fn()}
      />,
    );
    await waitFor(() => expect(readFrozen).toHaveBeenCalledTimes(1));
    view.rerender(
      <TurnReviewPanelView
        target={target}
        port={port(readFrozen)}
        active={false}
        requestedPath="src/b.ts"
        requestedPathRevision={1}
        onShowWorkspaceReview={vi.fn()}
      />,
    );
    await act(async () => Promise.resolve());
    expect(readFrozen).toHaveBeenCalledTimes(1);
    expect(screen.queryByLabelText("统一 Diff src/a.ts")).not.toBeInTheDocument();
  });

  it("拒绝 artifact、path 或 byteLength 不一致的响应并提供局部重试", async () => {
    const invalid = result("src/a.ts");
    const readFrozen = vi.fn(async () => ({ ...invalid, artifactId: "artifact_other" }));
    render(
      <TurnReviewPanelView
        target={target}
        port={port(readFrozen)}
        active
        onShowWorkspaceReview={vi.fn()}
      />,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("无法读取本轮修改");
    expect(screen.getByRole("button", { name: "重试" })).toBeVisible();
    expect(screen.getByRole("tree", { name: "审查文件" })).toBeVisible();
  });

  /** 重试按钮成功后会卸载；只在相同读取仍拥有空焦点时定位到可编程文件标题。 */
  it("键盘重试成功后把焦点交回当前文件标题", async () => {
    const retryRead = deferred<TurnReviewFileContent>();
    let attempts = 0;
    const readFrozen: TurnReviewPort["readFrozen"] = vi.fn(async () => {
      attempts += 1;
      if (attempts === 1) throw new Error("temporary read failure");
      return retryRead.promise;
    });
    render(
      <TurnReviewPanelView
        target={target}
        port={port(readFrozen)}
        active
        onShowWorkspaceReview={vi.fn()}
      />,
    );

    const user = userEvent.setup();
    const retry = await screen.findByRole("button", { name: "重试" });
    retry.focus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(readFrozen).toHaveBeenCalledTimes(2));
    await act(async () => retryRead.resolve(result("src/a.ts", "recovered")));

    expect(await screen.findByText("recovered")).toBeVisible();
    expect(screen.getByRole("heading", { name: "src/a.ts", level: 2 })).toHaveFocus();
  });

  /** 异步完成不能把焦点从用户后来选择的控件抢回到 Diff。 */
  it("重试等待期间焦点移到别处时不抢回焦点", async () => {
    const retryRead = deferred<TurnReviewFileContent>();
    let attempts = 0;
    const readFrozen: TurnReviewPort["readFrozen"] = vi.fn(async () => {
      attempts += 1;
      if (attempts === 1) throw new Error("temporary read failure");
      return retryRead.promise;
    });
    render(
      <>
        <button type="button">其他控件</button>
        <TurnReviewPanelView
          target={target}
          port={port(readFrozen)}
          active
          onShowWorkspaceReview={vi.fn()}
        />
      </>,
    );

    const user = userEvent.setup();
    const retry = await screen.findByRole("button", { name: "重试" });
    retry.focus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(readFrozen).toHaveBeenCalledTimes(2));
    const otherControl = screen.getByRole("button", { name: "其他控件" });
    otherControl.focus();
    await act(async () => retryRead.resolve(result("src/a.ts", "recovered")));

    expect(await screen.findByText("recovered")).toBeVisible();
    expect(otherControl).toHaveFocus();
  });

  /** target 切换后迟到的重试结果不得改变新目标或夺取当前页面焦点。 */
  it("目标切换后忽略迟到的重试结果和焦点恢复", async () => {
    const retryRead = deferred<TurnReviewFileContent>();
    const nextTarget: TurnReviewTarget = {
      ...target,
      turnId: "turn_next",
      artifactId: "artifact_next",
    };
    let attempts = 0;
    const readFrozen: TurnReviewPort["readFrozen"] = vi.fn(async (requestedTarget, file) => {
      attempts += 1;
      if (attempts === 1) throw new Error("temporary read failure");
      if (attempts === 2) return retryRead.promise;
      return {
        ...result(file.path, "new target"),
        artifactId: requestedTarget.artifactId,
      };
    });
    const view = render(
      <TurnReviewPanelView
        target={target}
        port={port(readFrozen)}
        active
        onShowWorkspaceReview={vi.fn()}
      />,
    );

    const user = userEvent.setup();
    const retry = await screen.findByRole("button", { name: "重试" });
    retry.focus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(readFrozen).toHaveBeenCalledTimes(2));
    view.rerender(
      <TurnReviewPanelView
        target={nextTarget}
        port={port(readFrozen)}
        active
        onShowWorkspaceReview={vi.fn()}
      />,
    );

    expect(await screen.findByText("new target")).toBeVisible();
    expect(document.activeElement).toBe(document.body);
    await act(async () =>
      retryRead.resolve({
        ...result("src/a.ts", "stale target"),
        artifactId: target.artifactId,
      }),
    );

    expect(screen.getByLabelText("统一 Diff src/a.ts")).toHaveTextContent("new target");
    expect(screen.queryByText("stale target")).not.toBeInTheDocument();
    expect(document.activeElement).toBe(document.body);
  });
});
