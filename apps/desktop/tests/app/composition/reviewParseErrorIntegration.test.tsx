// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { RuntimeNativeBridge } from "@/api/tauri/runtime";
import { JA_REVIEW_COMMANDS, TauriReviewAdapter } from "@/api/tauri/review";
import { ReviewPanelView, useReviewController, type ReviewPort } from "@/features/workbench/review";

vi.mock("@/features/workbench/editor", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/features/workbench/editor")>()),
  DiffViewer: () => <div role="region" aria-label="只读 Diff" />,
}));

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const catalog = {
  workspaceId: "ws_demo",
  repositoryName: "ja",
  currentBranch: "main",
  headCommitId: "abc123",
  baseRefs: [{ refId: "main", label: "main", kind: "base" }],
  commits: [
    { commitId: "abc123", subject: "Initial", author: "Ja", authoredAt: "2026-08-26T00:00:00Z" },
  ],
};

const conflictedFile = {
  fileId: "file_main",
  layer: "unstaged",
  path: "src/main.rs",
  oldPath: null,
  status: "conflicted",
  additions: 0,
  deletions: 0,
  binary: false,
  truncated: false,
  hunks: [],
} as const;

const snapshot = {
  workspaceId: "ws_demo",
  source: { kind: "uncommitted" },
  revision: "rev_1",
  files: [conflictedFile],
  stats: { files: 1, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
  capabilities: { stage: false, unstage: false, revert: false },
};

/** 用公开 feature port 贯通真实读取顺序；可选来源入口仅用于重试焦点的组合回归。 */
function ReviewPanelHarness({
  adapter,
  withBranchSelector = false,
}: {
  adapter: ReviewPort;
  withBranchSelector?: boolean;
}) {
  const controller = useReviewController({
    workspaceId: "ws_demo",
    snapshotEnabled: true,
    adapter,
  });
  return (
    <>
      {withBranchSelector ? (
        <button
          type="button"
          onClick={() => controller.actions.setSource({ kind: "branch", refId: "conflict-side" })}
        >
          选择本地分支
        </button>
      ) : null}
      <ReviewPanelView viewModel={controller.viewModel} actions={controller.actions} />
    </>
  );
}

describe("Review app composition error projection", () => {
  /** 两类读取独立完成，真实 controller 的后置 loading 不能提前消费显式重试焦点。 */
  it.each(["catalog", "snapshot"] as const)(
    "returns retry focus after both reads settle when %s finishes first",
    async (firstRead) => {
      const user = userEvent.setup();
      const branchCatalog = {
        ...catalog,
        baseRefs: [
          ...catalog.baseRefs,
          { refId: "conflict-side", label: "conflict-side", kind: "local" },
        ],
      };
      const emptySnapshot = {
        ...snapshot,
        files: [],
        stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
      };
      const branchSnapshot = {
        ...emptySnapshot,
        source: { kind: "branch", refId: "conflict-side" },
        revision: "rev_branch_retry",
      };
      let resolveCatalog!: (value: typeof branchCatalog) => void;
      let resolveSnapshot!: (value: typeof branchSnapshot) => void;
      const catalogRetry = new Promise<typeof branchCatalog>((resolve) => {
        resolveCatalog = resolve;
      });
      const snapshotRetry = new Promise<typeof branchSnapshot>((resolve) => {
        resolveSnapshot = resolve;
      });
      let catalogCalls = 0;
      let branchCalls = 0;
      const invoke = vi.fn(async (command: string, args?: Record<string, unknown>) => {
        if (command === JA_REVIEW_COMMANDS.catalog)
          return ++catalogCalls === 1 ? branchCatalog : catalogRetry;
        if (command === JA_REVIEW_COMMANDS.snapshot) {
          const input = args?.["input"] as { source: { kind: string } };
          if (input.source.kind !== "branch") return emptySnapshot;
          if (++branchCalls === 1)
            throw { code: "RUNTIME_UNAVAILABLE", message: "Retry the branch read" };
          return snapshotRetry;
        }
        throw new Error(`unexpected review command: ${command}`);
      });
      const bridge: RuntimeNativeBridge = {
        invoke: invoke as RuntimeNativeBridge["invoke"],
        listen: vi.fn(async () => () => undefined) as RuntimeNativeBridge["listen"],
      };
      render(<ReviewPanelHarness adapter={new TauriReviewAdapter(bridge)} withBranchSelector />);
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "重新读取审阅" })).toBeEnabled(),
      );
      await user.click(screen.getByRole("button", { name: "选择本地分支" }));
      const retry = await screen.findByRole("button", {
        name: "运行时暂不可用，请稍后重试。 重试",
      });
      await user.click(retry);
      await waitFor(() => {
        expect(catalogCalls).toBe(2);
        expect(branchCalls).toBe(2);
        expect(screen.getByRole("button", { name: "重新读取审阅" })).toBeDisabled();
      });
      await act(async () => {
        if (firstRead === "catalog") resolveCatalog(branchCatalog);
        else resolveSnapshot(branchSnapshot);
      });
      expect(screen.getByRole("button", { name: "重新读取审阅" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "重新读取审阅" })).not.toHaveFocus();
      await act(async () => {
        if (firstRead === "catalog") resolveSnapshot(branchSnapshot);
        else resolveCatalog(branchCatalog);
      });
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "重新读取审阅" })).toHaveFocus(),
      );
    },
  );

  /** 保留原生 invoke rejection 对象形状，证明 wire PARSE_ERROR 穿过 adapter 后落到领域恢复提示。 */
  it("renders the conflict recovery message for the native PARSE_ERROR object", async () => {
    const user = userEvent.setup();
    const invoke = vi.fn(async (command: string) => {
      if (command === JA_REVIEW_COMMANDS.catalog) return catalog;
      if (command === JA_REVIEW_COMMANDS.snapshot) return snapshot;
      if (command === JA_REVIEW_COMMANDS.fileDiff)
        throw { code: "PARSE_ERROR", message: "Review output could not be parsed" };
      throw new Error(`unexpected review command: ${command}`);
    });
    const bridge: RuntimeNativeBridge = {
      invoke: invoke as RuntimeNativeBridge["invoke"],
      listen: vi.fn(async () => () => undefined) as RuntimeNativeBridge["listen"],
    };
    const adapter = new TauriReviewAdapter(bridge);
    const port: ReviewPort = adapter;

    render(<ReviewPanelHarness adapter={port} />);
    await waitFor(() => {
      expect(invoke).toHaveBeenCalledWith(
        JA_REVIEW_COMMANDS.fileDiff,
        expect.objectContaining({
          input: expect.objectContaining({ fileId: conflictedFile.fileId }),
        }),
      );
      expect(
        screen.getByText("此文件有合并冲突，暂不支持显示组合 Diff。请解决冲突后重新读取。"),
      ).toBeVisible();
    });
    expect(screen.queryByText("运行时暂不可用，请稍后重试。")).not.toBeInTheDocument();
    const diffCallsBeforeRetry = invoke.mock.calls.filter(
      ([command]) => command === JA_REVIEW_COMMANDS.fileDiff,
    ).length;
    await user.click(screen.getByRole("button", { name: "重新读取" }));
    await waitFor(() => {
      expect(
        invoke.mock.calls.filter(([command]) => command === JA_REVIEW_COMMANDS.fileDiff).length,
      ).toBeGreaterThan(diffCallsBeforeRetry);
    });
  });

  /** snapshot 限额失败保留顶部恢复入口，不把未取得快照显示成空结果。 */
  it("keeps snapshot limit failures distinct from an empty review", async () => {
    const user = userEvent.setup();
    const invoke = vi.fn(async (command: string) => {
      if (command === JA_REVIEW_COMMANDS.catalog) return catalog;
      if (command === JA_REVIEW_COMMANDS.snapshot)
        throw { code: "REVIEW_LIMIT", message: "Review snapshot exceeds its safe size limit" };
      throw new Error(`unexpected review command: ${command}`);
    });
    const bridge: RuntimeNativeBridge = {
      invoke: invoke as RuntimeNativeBridge["invoke"],
      listen: vi.fn(async () => () => undefined) as RuntimeNativeBridge["listen"],
    };
    const adapter = new TauriReviewAdapter(bridge);
    const port: ReviewPort = adapter;

    render(<ReviewPanelHarness adapter={port} />);
    const limitMessage =
      "审阅内容超出当前安全上限。请切换到改动较少的审阅来源，再点顶部“重新读取审阅”按钮。";
    await waitFor(() => {
      expect(screen.getByText(limitMessage)).toBeVisible();
      expect(invoke).toHaveBeenCalledWith(
        JA_REVIEW_COMMANDS.snapshot,
        expect.objectContaining({ input: expect.objectContaining({ workspaceId: "ws_demo" }) }),
      );
    });

    expect(screen.getByText("文件列表尚未读取。")).toBeVisible();
    expect(screen.queryByText("没有匹配的文件。")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("变更统计")).not.toBeInTheDocument();
    expect(
      screen.queryByText("Review snapshot exceeds its safe size limit"),
    ).not.toBeInTheDocument();
    const snapshotCallsBeforeRetry = invoke.mock.calls.filter(
      ([command]) => command === JA_REVIEW_COMMANDS.snapshot,
    ).length;
    await user.click(screen.getByRole("button", { name: "重新读取审阅" }));
    await waitFor(() => {
      expect(
        invoke.mock.calls.filter(([command]) => command === JA_REVIEW_COMMANDS.snapshot).length,
      ).toBeGreaterThan(snapshotCallsBeforeRetry);
    });
  });
});
