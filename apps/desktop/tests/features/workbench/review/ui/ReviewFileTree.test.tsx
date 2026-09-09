// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type {
  ReviewTreeFile,
  ReviewTreeLayer,
} from "@/features/workbench/review/domain/reviewTree";
import {
  ReviewFileTree,
  type ReviewTreeNavigationState,
} from "@/features/workbench/review/ui/ReviewFileTree";

/** 构造稳定的树文件身份，layer 始终参与 id，避免测试掩盖同路径双层变更。 */
function reviewFile(
  path: string,
  layer: ReviewTreeLayer = "unstaged",
  status = "modified",
): ReviewTreeFile {
  return {
    id: `${path}:${layer}`,
    path,
    layer,
    status,
    additions: 2,
    deletions: 1,
    binary: false,
  };
}

interface TreeHarnessProps {
  readonly files: readonly ReviewTreeFile[];
  readonly initialSelectedId?: string;
  readonly initialNavigationState?: ReviewTreeNavigationState;
  readonly onSelect?: (file: ReviewTreeFile) => void;
  readonly onNavigationStateChange?: (state: ReviewTreeNavigationState) => void;
}

/** 用真实受控 query/selection 承接组件意图，覆盖重新投影后的选择连续性。 */
function TreeHarness({
  files,
  initialSelectedId,
  initialNavigationState,
  onSelect,
  onNavigationStateChange,
}: TreeHarnessProps): ReactElement {
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState(initialSelectedId);
  return (
    <ReviewFileTree
      files={files}
      selectedId={selectedId}
      query={query}
      onQueryChange={setQuery}
      onSelect={(file) => {
        setSelectedId(file.id);
        onSelect?.(file);
      }}
      navigationState={initialNavigationState}
      onNavigationStateChange={onNavigationStateChange}
    />
  );
}

beforeEach(() => {
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
    callback(performance.now());
    return 1;
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ReviewFileTree", () => {
  it("搜索只临时展开命中祖先，清空后恢复用户折叠状态", async () => {
    const user = userEvent.setup();
    render(<TreeHarness files={[reviewFile("packages/client/src/Needle.tsx")]} />);

    const folder = screen.getByRole("treeitem", { name: "packages/client/src 1" });
    expect(folder).toHaveAttribute("aria-expanded", "true");
    await user.click(folder);
    expect(screen.queryByRole("treeitem", { name: /Needle\.tsx/u })).not.toBeInTheDocument();

    const search = screen.getByRole("searchbox", { name: "筛选文件" });
    await user.type(search, "client/src/needle");
    expect(screen.getByRole("treeitem", { name: "packages/client/src 1" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.getByRole("treeitem", { name: /Needle\.tsx/u })).toBeVisible();

    await user.clear(search);
    expect(screen.getByRole("treeitem", { name: "packages/client/src 1" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(screen.queryByRole("treeitem", { name: /Needle\.tsx/u })).not.toBeInTheDocument();
  });

  it("切换分组不改变选择，全部折叠也不触发文件选择", async () => {
    const user = userEvent.setup();
    const selected = reviewFile("src/main.ts");
    const onSelect = vi.fn();
    render(
      <TreeHarness
        files={[selected, reviewFile("README.md", "staged")]}
        initialSelectedId={selected.id}
        onSelect={onSelect}
      />,
    );

    const selectedRow = screen.getByRole("treeitem", { name: /查看 src\/main\.ts/u });
    expect(selectedRow).toHaveAttribute("aria-selected", "true");
    await user.click(screen.getByRole("button", { name: "文件分组：状态与目录" }));
    await user.click(screen.getByRole("menuitemradio", { name: "目录" }));

    expect(screen.getByRole("treeitem", { name: /查看 src\/main\.ts/u })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(onSelect).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "全部折叠" }));
    expect(screen.queryByRole("treeitem", { name: /查看 src\/main\.ts/u })).not.toBeInTheDocument();
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("同路径不同暂存层渲染为两个可独立点击的行", async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    const unstaged = reviewFile("src/shared.ts", "unstaged");
    const staged = reviewFile("src/shared.ts", "staged");
    render(<TreeHarness files={[unstaged, staged]} onSelect={onSelect} />);

    const unstagedRow = screen.getByRole("treeitem", {
      name: "查看 src/shared.ts 的未暂存变更",
    });
    const stagedRow = screen.getByRole("treeitem", {
      name: "查看 src/shared.ts 的已暂存变更",
    });
    expect(unstagedRow).toHaveAttribute("data-review-file-id", unstaged.id);
    expect(stagedRow).toHaveAttribute("data-review-file-id", staged.id);

    await user.click(stagedRow);
    expect(onSelect).toHaveBeenLastCalledWith(staged);
    expect(stagedRow).toHaveAttribute("aria-selected", "true");
    expect(unstagedRow).toHaveAttribute("aria-selected", "false");
  });

  it("提供树标准方向键、父子导航、Home、End 与 Enter 激活", () => {
    const onSelect = vi.fn();
    const nested = reviewFile("src/nested.ts");
    const root = reviewFile("README.md");
    render(<TreeHarness files={[nested, root]} onSelect={onSelect} />);

    const items = screen.getAllByRole("treeitem");
    expect(items).toHaveLength(4);
    items[0]!.focus();
    expect(items[0]).toHaveAttribute("tabindex", "0");

    fireEvent.keyDown(items[0]!, { key: "ArrowRight" });
    expect(items[1]).toHaveFocus();
    fireEvent.keyDown(items[1]!, { key: "ArrowRight" });
    expect(items[2]).toHaveFocus();
    fireEvent.keyDown(items[2]!, { key: "ArrowLeft" });
    expect(items[1]).toHaveFocus();
    fireEvent.keyDown(items[1]!, { key: "End" });
    expect(items[3]).toHaveFocus();
    fireEvent.keyDown(items[3]!, { key: "Home" });
    expect(items[0]).toHaveFocus();

    fireEvent.keyDown(items[2]!, { key: "Enter" });
    expect(onSelect).toHaveBeenCalledWith(nested);
  });

  it("大量平铺文件只挂载可视窗口并保留完整滚动高度", () => {
    const files = Array.from({ length: 500 }, (_, index) =>
      reviewFile(`src/generated/file-${index.toString().padStart(3, "0")}.ts`, "comparison"),
    );
    render(
      <ReviewFileTree
        files={files}
        query=""
        onQueryChange={vi.fn()}
        onSelect={vi.fn()}
        initialGrouping="flat"
        allowedGroupings={["flat"]}
      />,
    );

    expect(screen.getAllByRole("treeitem")).toHaveLength(40);
    expect(document.querySelector(".ja-review-tree-spacer")).toHaveStyle({ height: "16000px" });
    expect(screen.queryByText("src/generated/file-499.ts")).not.toBeInTheDocument();
  });
});
