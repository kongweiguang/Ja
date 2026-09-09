// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
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
});
