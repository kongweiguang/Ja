// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReviewSourceNavigation } from "@/app/composition/ReviewSourceNavigation";
import type { ReviewViewModel } from "@/features/workbench/review";

afterEach(() => cleanup());

/** 只构造范围菜单消费的 controller 投影，避免测试复制 Diff 和 mutation 状态机。 */
function viewModel(overrides: Partial<ReviewViewModel["state"]> = {}): ReviewViewModel {
  return {
    state: {
      source: { kind: "uncommitted" },
      layerFilter: "all",
      catalog: {
        workspaceId: "ws_one",
        repositoryName: "ja",
        currentBranch: "main",
        headCommitId: "abcdef12",
        baseRefs: [{ refId: "main", label: "main", kind: "local" }],
        commits: [],
      },
      ...overrides,
    } as ReviewViewModel["state"],
    sourceOptions: [{ kind: "uncommitted" }, { kind: "branch", refId: "main" }],
    visibleFiles: [],
    selectedFile: undefined,
  };
}

describe("ReviewSourceNavigation", () => {
  it("从 Turn 切换到未跟踪聚合范围并释放 Turn 正文", async () => {
    const user = userEvent.setup();
    const setSource = vi.fn();
    const setLayerFilter = vi.fn();
    const showWorkspace = vi.fn();
    render(
      <ReviewSourceNavigation
        currentLabel="最后一轮"
        turnSelected
        latestTurnAvailable
        viewModel={viewModel()}
        actions={{ setSource, setLayerFilter }}
        onShowRetainedTurn={vi.fn()}
        onShowLatestTurn={vi.fn()}
        onShowWorkspaceReview={showWorkspace}
      />,
    );

    await user.click(screen.getByRole("button", { name: "审阅范围：最后一轮" }));
    await user.hover(screen.getByText("未提交"));
    const untracked = await screen.findByRole("menuitem", { name: "未跟踪" });
    untracked.focus();
    await user.keyboard("{Enter}");

    expect(setSource).toHaveBeenCalledWith({ kind: "uncommitted" });
    expect(setLayerFilter).toHaveBeenCalledWith("untracked");
    expect(showWorkspace).toHaveBeenCalledTimes(1);
  });

  it("保留历史轮次入口，并把最后一轮作为独立可选范围", async () => {
    const user = userEvent.setup();
    const showRetained = vi.fn();
    const showLatest = vi.fn();
    render(
      <ReviewSourceNavigation
        currentLabel="未提交"
        turnSelected={false}
        retainedTurnLabel="第 2 轮"
        latestTurnAvailable
        viewModel={viewModel()}
        actions={{ setSource: vi.fn(), setLayerFilter: vi.fn() }}
        onShowRetainedTurn={showRetained}
        onShowLatestTurn={showLatest}
        onShowWorkspaceReview={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "审阅范围：未提交" }));
    await user.click(screen.getByText("第 2 轮"));
    expect(showRetained).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "审阅范围：未提交" }));
    await user.click(await screen.findByText("最后一轮"));
    await waitFor(() => expect(showLatest).toHaveBeenCalledTimes(1));
  });

  it("非 Git 项目只保留真实冻结历史，且无最近轮次时不显示占位入口", async () => {
    const user = userEvent.setup();
    const showRetained = vi.fn();
    render(
      <ReviewSourceNavigation
        currentLabel="第 2 轮修改"
        turnSelected
        retainedTurnLabel="第 2 轮修改"
        latestTurnAvailable={false}
        viewModel={viewModel({ catalog: undefined })}
        actions={{ setSource: vi.fn(), setLayerFilter: vi.fn() }}
        onShowRetainedTurn={showRetained}
        onShowLatestTurn={vi.fn()}
        onShowWorkspaceReview={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "审阅范围：第 2 轮修改" }));
    expect(screen.queryByRole("menuitem", { name: "未提交" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "比较" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "最后一轮" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: "第 2 轮修改" }));
    expect(showRetained).toHaveBeenCalledTimes(1);
  });
});
