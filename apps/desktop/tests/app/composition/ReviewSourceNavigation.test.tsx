// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useEffect, useRef, useState, type ReactElement } from "react";
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

/** 与真实 Host 一样按来源重挂载视图，确保菜单不会把焦点返回已卸载的旧触发器。 */
function KeyedSourceNavigationFixture({
  hideAfterSelection = false,
  moveFocusAfterSelection = false,
}: {
  hideAfterSelection?: boolean;
  moveFocusAfterSelection?: boolean;
}): ReactElement {
  const [selected, setSelected] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const otherControlRef = useRef<HTMLButtonElement>(null);
  /** 模拟用户在提交新视图后已操作其它控件，迟到的菜单关闭应保留该焦点。 */
  useEffect(() => {
    if (selected && moveFocusAfterSelection) otherControlRef.current?.focus();
  }, [selected, moveFocusAfterSelection]);
  return (
    <>
      <button ref={otherControlRef} type="button">
        其他操作
      </button>
      <section hidden={selected && hideAfterSelection}>
        <ReviewSourceNavigation
          triggerRef={triggerRef}
          key={selected ? "branch:main" : "uncommitted"}
          currentLabel={selected ? "main" : "未提交"}
          turnSelected={false}
          latestTurnAvailable={false}
          viewModel={viewModel({
            source: selected ? { kind: "branch", refId: "main" } : { kind: "uncommitted" },
          })}
          actions={{
            setSource: () => setSelected(true),
            setLayerFilter: vi.fn(),
            refresh: vi.fn(),
          }}
          onShowRetainedTurn={vi.fn()}
          onShowLatestTurn={vi.fn()}
          onShowWorkspaceReview={vi.fn()}
        />
      </section>
    </>
  );
}

describe("ReviewSourceNavigation", () => {
  /** 比较选择导致旧菜单卸载后，键盘操作须从新来源的真实触发器继续。 */
  it("比较来源重挂载后将焦点返回新触发器", async () => {
    const user = userEvent.setup();
    render(<KeyedSourceNavigationFixture />);
    await user.click(screen.getByRole("button", { name: "审阅范围：未提交" }));
    await user.hover(screen.getByRole("menuitem", { name: "比较" }));
    (await screen.findByRole("menuitem", { name: "main" })).focus();
    await user.keyboard("{Enter}");
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "审阅范围：main" })).toHaveFocus(),
    );
  });

  /** 用户已进入其它操作时，旧菜单的迟到关闭不能覆盖新焦点。 */
  it("来源重挂载后保留用户已移动的焦点", async () => {
    const user = userEvent.setup();
    render(<KeyedSourceNavigationFixture moveFocusAfterSelection />);
    await user.click(screen.getByRole("button", { name: "审阅范围：未提交" }));
    await user.hover(screen.getByRole("menuitem", { name: "比较" }));
    (await screen.findByRole("menuitem", { name: "main" })).focus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(screen.getByRole("button", { name: "其他操作" })).toHaveFocus());
  });

  /** 工作面隐藏后的触发器仍可挂载，关闭菜单不得将焦点送入隐藏区域。 */
  it("来源重挂载后不聚焦隐藏工作面", async () => {
    const user = userEvent.setup();
    render(<KeyedSourceNavigationFixture hideAfterSelection />);
    await user.click(screen.getByRole("button", { name: "审阅范围：未提交" }));
    await user.hover(screen.getByRole("menuitem", { name: "比较" }));
    (await screen.findByRole("menuitem", { name: "main" })).focus();
    await user.keyboard("{Enter}");
    await waitFor(() =>
      expect(screen.queryByRole("menuitem", { name: "main" })).not.toBeInTheDocument(),
    );
    expect(screen.getByRole("button", { name: "审阅范围：main", hidden: true })).not.toHaveFocus();
  });
  /** 首次目录读取慢于打开菜单时，状态反馈和真实动作应在原菜单内更新，不要求用户重开。 */
  it("来源加载期间显示反馈，就绪后原菜单出现真实 Git 入口", async () => {
    const user = userEvent.setup();
    const props = {
      currentLabel: "未提交",
      turnSelected: false,
      latestTurnAvailable: false,
      actions: { setSource: vi.fn(), setLayerFilter: vi.fn(), refresh: vi.fn() },
      onShowRetainedTurn: vi.fn(),
      onShowLatestTurn: vi.fn(),
      onShowWorkspaceReview: vi.fn(),
    };
    const { rerender } = render(
      <ReviewSourceNavigation
        {...props}
        viewModel={viewModel({ catalog: undefined, catalogLoading: true })}
      />,
    );

    await user.click(screen.getByRole("button", { name: "审阅范围：未提交" }));
    expect(screen.getByRole("status")).toHaveTextContent("正在读取 Git 来源");
    expect(screen.queryByRole("menuitem", { name: "未提交" })).not.toBeInTheDocument();
    rerender(<ReviewSourceNavigation {...props} viewModel={viewModel()} />);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(await screen.findByRole("menuitem", { name: "未提交" })).toBeVisible();
  });

  /** Git 读取失败不隐藏真实原因，也不阻断与 Git 无关的冻结历史入口。 */
  it("来源失败保留真实提示和可用历史，不伪造 Git 动作", async () => {
    const user = userEvent.setup();
    const showRetained = vi.fn();
    const refresh = vi.fn();
    render(
      <ReviewSourceNavigation
        currentLabel="第 2 轮"
        turnSelected
        retainedTurnLabel="第 2 轮"
        latestTurnAvailable={false}
        viewModel={viewModel({
          catalog: undefined,
          catalogLoading: false,
          error: {
            code: "RUNTIME_NOT_READY",
            message: "运行时暂不可用，请重试。",
            retryable: true,
          },
        })}
        actions={{ setSource: vi.fn(), setLayerFilter: vi.fn(), refresh }}
        onShowRetainedTurn={showRetained}
        onShowLatestTurn={vi.fn()}
        onShowWorkspaceReview={vi.fn()}
      />,
    );

    await user.click(screen.getByRole("button", { name: "审阅范围：第 2 轮" }));
    expect(screen.getByRole("status")).toHaveTextContent("运行时暂不可用，请重试。");
    expect(screen.queryByRole("menuitem", { name: "比较" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: "重新读取 Git 来源" }));
    expect(refresh).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "审阅范围：第 2 轮" })).toHaveFocus(),
    );
    await user.click(screen.getByRole("button", { name: "审阅范围：第 2 轮" }));
    await user.click(screen.getByRole("menuitem", { name: "第 2 轮" }));
    expect(showRetained).toHaveBeenCalledTimes(1);
  });

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
        actions={{ setSource, setLayerFilter, refresh: vi.fn() }}
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
        actions={{ setSource: vi.fn(), setLayerFilter: vi.fn(), refresh: vi.fn() }}
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
        actions={{ setSource: vi.fn(), setLayerFilter: vi.fn(), refresh: vi.fn() }}
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
