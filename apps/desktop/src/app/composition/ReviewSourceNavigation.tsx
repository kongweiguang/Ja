// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Check, ChevronDown, ChevronRight, GitCompareArrows, History } from "lucide-react";
import type { ReactElement } from "react";
import type {
  ReviewActions,
  ReviewLayerFilter,
  ReviewSource,
  ReviewViewModel,
} from "@/features/workbench/review";
import {
  Menu,
  MenuContent,
  MenuItem,
  MenuSub,
  MenuSubContent,
  MenuSubTrigger,
  MenuTrigger,
} from "@/shared/ui/primitives";
import "./ReviewSourceNavigation.css";

export interface ReviewSourceNavigationProps {
  readonly currentLabel: string;
  readonly turnSelected: boolean;
  readonly retainedTurnLabel?: string;
  readonly latestTurnAvailable: boolean;
  readonly viewModel: ReviewViewModel;
  readonly actions: Pick<ReviewActions, "setSource" | "setLayerFilter">;
  readonly onShowRetainedTurn: () => void;
  readonly onShowLatestTurn: () => void;
  readonly onShowWorkspaceReview: () => void;
}

const WORKTREE_FILTERS: readonly { value: ReviewLayerFilter; label: string }[] = [
  { value: "all", label: "全部" },
  { value: "unstaged", label: "未暂存" },
  { value: "staged", label: "已暂存" },
  { value: "untracked", label: "未跟踪" },
];

/** 比较来源只采用 native Catalog 已发布的标签，不从 ref identity 猜测分支含义。 */
function comparisonLabel(source: ReviewSource, viewModel: ReviewViewModel): string {
  if (source.kind === "branch") {
    return (
      viewModel.state.catalog?.baseRefs.find((entry) => entry.refId === source.refId)?.label ??
      source.refId
    );
  }
  return source.kind === "commit" ? `提交 ${source.commitId.slice(0, 8)}` : "";
}

/** 单选勾选只表达当前审阅范围，不用高饱和背景争夺 Diff 内容的视觉焦点。 */
function SelectionMark({ selected }: { selected: boolean }): ReactElement | null {
  return selected ? <Check className="ja-review-source-check" aria-hidden="true" /> : null;
}

/**
 * 范围菜单只编排 Turn 与 Git controller 已有动作。选择未提交子范围时先恢复聚合来源，
 * 再设置 layer 投影；两步均不自行触发 IO，真正的读取仍受 Review 可见性门禁控制。
 */
export function ReviewSourceNavigation({
  currentLabel,
  turnSelected,
  retainedTurnLabel,
  latestTurnAvailable,
  viewModel,
  actions,
  onShowRetainedTurn,
  onShowLatestTurn,
  onShowWorkspaceReview,
}: ReviewSourceNavigationProps): ReactElement {
  const comparisonSources = viewModel.sourceOptions.filter(
    (source) => source.kind === "branch" || source.kind === "commit",
  );
  const selectedSource = viewModel.state.source;
  const selectedLayer = viewModel.state.layerFilter;

  /** 显式切换才改变范围；后续终态通知不能抢走当前 Git 或历史选择。 */
  const showUncommitted = (filter: ReviewLayerFilter): void => {
    actions.setSource({ kind: "uncommitted" });
    actions.setLayerFilter(filter);
    onShowWorkspaceReview();
  };

  /** Branch/commit 比较复用 native Catalog identity，并清除不适用于比较范围的 layer 筛选。 */
  const showComparison = (source: ReviewSource): void => {
    actions.setLayerFilter("all");
    actions.setSource(source);
    onShowWorkspaceReview();
  };

  const hasSeparateLatestTurn = retainedTurnLabel !== undefined && retainedTurnLabel !== "最后一轮";
  const gitAvailable = viewModel.state.catalog !== undefined;

  return (
    <Menu>
      <MenuTrigger asChild>
        <button
          type="button"
          className="ja-review-source-trigger"
          aria-label={`审阅范围：${currentLabel}`}
        >
          <span>{currentLabel}</span>
          <ChevronDown aria-hidden="true" />
        </button>
      </MenuTrigger>
      <MenuContent className="ja-review-source-menu" align="start" aria-label="选择审阅范围">
        {gitAvailable ? (
          <MenuSub>
            <MenuSubTrigger>
              <GitCompareArrows aria-hidden="true" />
              <span>未提交</span>
              <ChevronRight className="ja-review-source-chevron" aria-hidden="true" />
            </MenuSubTrigger>
            <MenuSubContent className="ja-review-source-menu" aria-label="未提交范围">
              {WORKTREE_FILTERS.map((filter) => (
                <MenuItem key={filter.value} onSelect={() => showUncommitted(filter.value)}>
                  <span>{filter.label}</span>
                  <SelectionMark
                    selected={
                      !turnSelected &&
                      selectedSource.kind === "uncommitted" &&
                      selectedLayer === filter.value
                    }
                  />
                </MenuItem>
              ))}
            </MenuSubContent>
          </MenuSub>
        ) : null}
        {gitAvailable && comparisonSources.length > 0 ? (
          <MenuSub>
            <MenuSubTrigger>
              <GitCompareArrows aria-hidden="true" />
              <span>比较</span>
              <ChevronRight className="ja-review-source-chevron" aria-hidden="true" />
            </MenuSubTrigger>
            <MenuSubContent className="ja-review-source-menu" aria-label="比较范围">
              {comparisonSources.map((source) => {
                const key =
                  source.kind === "branch" ? `branch:${source.refId}` : `commit:${source.commitId}`;
                const selected =
                  !turnSelected && JSON.stringify(selectedSource) === JSON.stringify(source);
                return (
                  <MenuItem key={key} onSelect={() => showComparison(source)}>
                    <span>{comparisonLabel(source, viewModel)}</span>
                    <SelectionMark selected={selected} />
                  </MenuItem>
                );
              })}
            </MenuSubContent>
          </MenuSub>
        ) : null}
        {retainedTurnLabel !== undefined ? (
          <MenuItem onSelect={onShowRetainedTurn}>
            <History aria-hidden="true" />
            <span>{retainedTurnLabel}</span>
            <SelectionMark selected={turnSelected} />
          </MenuItem>
        ) : null}
        {latestTurnAvailable && (retainedTurnLabel === undefined || hasSeparateLatestTurn) ? (
          <MenuItem onSelect={onShowLatestTurn}>
            <History aria-hidden="true" />
            <span>最后一轮</span>
            <SelectionMark selected={turnSelected && retainedTurnLabel === undefined} />
          </MenuItem>
        ) : null}
      </MenuContent>
    </Menu>
  );
}
