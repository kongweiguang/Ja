// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Columns3,
  FileDiff,
  LoaderCircle,
  MoreHorizontal,
  Rows3,
  ShieldAlert,
  X,
} from "lucide-react";
import * as AlertDialog from "@radix-ui/react-alert-dialog";
import { useMemo, useRef, useState, type ReactElement, type ReactNode } from "react";
import type { ReviewAction, ReviewFileDiff, ReviewSource, ReviewTarget } from "../domain/types";
import { DiffViewer } from "@/features/workbench/editor";
import {
  Button,
  IconButton,
  Menu,
  MenuContent,
  MenuItem,
  MenuLabel,
  MenuSeparator,
  MenuTrigger,
  Select,
} from "@/shared/ui/primitives";
import type { ReviewActions, ReviewViewModel } from "../application/useReviewController";
import {
  actionLabel,
  canRenderUnifiedDiff,
  canRenderTextDiff,
  canReviewAction,
  sourceKey,
  sourceLabel,
} from "../domain/model";
import "./ReviewPanel.css";
import {
  ReviewFileTree,
  type ReviewNavigationState,
  type ReviewTreeNavigationState,
} from "./ReviewFileTree";
import { ReviewShell } from "./ReviewShell";
import { ReviewUnifiedDiff, type ReviewUnifiedDiffHunk } from "./ReviewUnifiedDiff";

export interface ReviewPanelViewProps {
  viewModel: ReviewViewModel;
  actions: ReviewActions;
  onCopyText?: (text: string) => Promise<void>;
  sourceNavigation?: ReactNode;
  navigationState?: ReviewNavigationState;
  onNavigationStateChange?: (state: ReviewNavigationState) => void;
}

/** 使用统一 Radix Select 渲染来源，避免原生下拉在 WebView2 中脱离主题和焦点规范。 */
function SourceSelector({
  source,
  sources,
  branchLabels,
  onChange,
}: {
  source: ReviewSource;
  sources: ReviewSource[];
  branchLabels: Map<string, string>;
  onChange: (source: ReviewSource) => void;
}): ReactElement {
  const options = sources.map((candidate) => ({
    value: sourceKey(candidate),
    label: sourceLabel(candidate, branchLabels.get(sourceKey(candidate))),
  }));
  return (
    <div className="ja-review-source-select">
      <Select
        ariaLabel="审查来源"
        className="ja-review-source-control"
        size="compact"
        value={sourceKey(source)}
        options={options}
        onValueChange={(value) => {
          const next = sources.find((candidate) => sourceKey(candidate) === value);
          if (next !== undefined) onChange(next);
        }}
      />
    </div>
  );
}

/** 文本不可用时说明原因，不渲染具有误导性的空编辑器。 */
function UnavailableDiff({ diff }: { diff: ReviewFileDiff | undefined }): ReactElement {
  const label =
    diff?.binary === true
      ? "二进制文件不提供文本 Diff。"
      : diff?.truncated === true
        ? "文件超过内容上限，无法显示文本 Diff。"
        : "当前文件没有可用的安全 Diff。";
  return (
    <div className="ja-review-empty is-compact">
      <ShieldAlert aria-hidden="true" />
      <p>{label}</p>
    </div>
  );
}

/** 为 empty、loading 与 native read 失败提供聚焦状态行。 */
function ReviewStatus({
  kind,
  message,
  onRetry,
}: {
  kind: "loading" | "empty" | "error";
  message: string;
  onRetry?: () => void;
}): ReactElement {
  return (
    <div className={`ja-review-empty is-${kind}`} role={kind === "loading" ? "status" : undefined}>
      {kind === "loading" ? (
        <LoaderCircle className="ja-review-spin" aria-hidden="true" />
      ) : kind === "error" ? (
        <ShieldAlert aria-hidden="true" />
      ) : (
        <FileDiff aria-hidden="true" />
      )}
      <p>{message}</p>
      {kind === "error" && onRetry === undefined ? null : kind === "error" ? (
        <button type="button" onClick={onRetry}>
          重新读取
        </button>
      ) : null}
    </div>
  );
}

/** 把 native action 收进单个上下文菜单，避免高频阅读区出现重复按钮阵列。 */
function ActionMenu({
  label,
  actions,
  target,
  pending,
  onAction,
}: {
  label: string;
  actions: readonly ReviewAction[];
  target: ReviewTarget;
  pending: boolean;
  onAction: (action: ReviewAction, target: ReviewTarget) => void;
}): ReactElement | null {
  if (actions.length === 0) return null;
  return (
    <Menu>
      <MenuTrigger asChild>
        <IconButton
          label={label}
          tooltip={label}
          disabled={pending}
          aria-busy={pending || undefined}
        >
          {pending ? (
            <LoaderCircle className="ja-review-spin" aria-hidden="true" />
          ) : (
            <MoreHorizontal aria-hidden="true" />
          )}
        </IconButton>
      </MenuTrigger>
      <MenuContent align="end">
        {actions.map((action) => (
          <MenuItem key={action} onSelect={() => onAction(action, target)}>
            {actionLabel(action, target)}
          </MenuItem>
        ))}
      </MenuContent>
    </Menu>
  );
}

/** 文件与全范围操作共享一个菜单，危险的“全部撤销”与当前文件动作保持分区。 */
function FileActionMenu({
  selectedActions,
  selectedTarget,
  allActions,
  pending,
  onAction,
}: {
  selectedActions: readonly ReviewAction[];
  selectedTarget: ReviewTarget | undefined;
  allActions: readonly ReviewAction[];
  pending: boolean;
  onAction: (action: ReviewAction, target: ReviewTarget) => void;
}): ReactElement | null {
  if ((selectedTarget === undefined || selectedActions.length === 0) && allActions.length === 0)
    return null;
  return (
    <Menu>
      <MenuTrigger asChild>
        <IconButton
          label="变更操作"
          tooltip="变更操作"
          disabled={pending}
          aria-busy={pending || undefined}
        >
          {pending ? (
            <LoaderCircle className="ja-review-spin" aria-hidden="true" />
          ) : (
            <MoreHorizontal aria-hidden="true" />
          )}
        </IconButton>
      </MenuTrigger>
      <MenuContent align="end">
        {selectedTarget === undefined || selectedActions.length === 0 ? null : (
          <>
            <MenuLabel>当前文件</MenuLabel>
            {selectedActions.map((action) => (
              <MenuItem key={`file:${action}`} onSelect={() => onAction(action, selectedTarget)}>
                {actionLabel(action, selectedTarget)}
              </MenuItem>
            ))}
          </>
        )}
        {allActions.length === 0 ? null : (
          <>
            {selectedTarget === undefined || selectedActions.length === 0 ? null : (
              <MenuSeparator />
            )}
            <MenuLabel>全部文件</MenuLabel>
            {allActions.map((action) => (
              <MenuItem key={`all:${action}`} onSelect={() => onAction(action, { kind: "all" })}>
                {actionLabel(action, { kind: "all" })}
              </MenuItem>
            ))}
          </>
        )}
      </MenuContent>
    </Menu>
  );
}

type ReviewCapabilitiesLike = { stage: boolean; unstage: boolean; revert: boolean };

/** 在保留 native capability 校验的前提下派生 source 可见 action。 */
function availableActions(
  capabilities: ReviewCapabilitiesLike | undefined,
  source: ReviewSource,
  layer?: "staged" | "unstaged" | "untracked" | "comparison",
): ReviewAction[] {
  if (capabilities === undefined) return [];
  const candidates: ReviewAction[] =
    source.kind === "uncommitted"
      ? layer === "staged"
        ? ["unstage", "revert"]
        : layer === "unstaged" || layer === "untracked"
          ? ["stage", "revert"]
          : []
      : source.kind === "unstaged"
        ? ["stage", "revert"]
        : source.kind === "staged"
          ? ["unstage", "revert"]
          : [];
  return candidates.filter((action) => canReviewAction(capabilities, action));
}

/** 为 file/all 撤销生成明确且不暴露路径的确认说明；区块撤销保持直接操作。 */
function revertConfirmationCopy(target: ReviewTarget): { title: string; description: string } {
  return target.kind === "all"
    ? { title: "撤销全部变更？", description: "工作区中的全部未提交变更将被移除。" }
    : target.kind === "hunk"
      ? { title: "撤销区块变更？", description: "这个变更区块将从工作区移除。" }
      : { title: "撤销文件变更？", description: "这个文件的未提交变更将被移除。" };
}

/** 布局偏好只改变当前快照的投影，不为双栏额外物化全文或触发原生读取。 */
export function ReviewPanelView({
  viewModel,
  actions,
  onCopyText,
  sourceNavigation,
  navigationState,
  onNavigationStateChange,
}: ReviewPanelViewProps): ReactElement {
  const { state } = viewModel;
  const selectedButtonRef = useRef<HTMLButtonElement | null>(null);
  const revertCancelRef = useRef<HTMLButtonElement>(null);
  const [detailOpen, setDetailOpen] = useState(navigationState?.detailOpen ?? false);
  const [treeNavigation, setTreeNavigation] = useState<ReviewTreeNavigationState | undefined>(
    navigationState?.tree,
  );
  const [pendingRevertTarget, setPendingRevertTarget] = useState<ReviewTarget>();
  const branchLabels = useMemo(
    () =>
      new Map(
        (state.catalog?.baseRefs ?? []).map((ref) => [
          sourceKey({ kind: "branch", refId: ref.refId }),
          ref.label,
        ]),
      ),
    [state.catalog?.baseRefs],
  );
  const capabilities = state.snapshot?.capabilities;
  const availableReviewActions = availableActions(capabilities, state.source);
  const selectedReviewActions = availableActions(
    capabilities,
    state.source,
    viewModel.selectedFile?.layer,
  );
  const selectedDiff = state.diff;
  const splitDiffAvailable = canRenderTextDiff(selectedDiff);
  const unifiedDiffAvailable = canRenderUnifiedDiff(selectedDiff);

  /** file/all 撤销进入应用内确认态，避免 Tauri WebView2 拦截 window.confirm 后静默失效。 */
  const onAction = (action: ReviewAction, target: ReviewTarget): void => {
    if (action === "revert") {
      setPendingRevertTarget(target);
      return;
    }
    void actions.applyAction(action, target);
  };

  /** 只消费当前冻结的 target 一次；先关闭 Dialog，再把真实 mutation 交回 controller。 */
  const confirmPendingRevert = (): void => {
    if (pendingRevertTarget === undefined) return;
    const target = pendingRevertTarget;
    setPendingRevertTarget(undefined);
    void actions.applyAction("revert", target);
  };

  const revertCopy =
    pendingRevertTarget === undefined ? undefined : revertConfirmationCopy(pendingRevertTarget);

  /** 把紧凑状态通知解析为安全的可见文案。 */
  const notice = state.error?.message ?? state.notice;
  const currentFileIndex = viewModel.visibleFiles.findIndex(
    (file) => file.fileId === state.selectedFileId,
  );
  const selectedFileTarget =
    viewModel.selectedFile === undefined
      ? undefined
      : ({ kind: "file", fileId: viewModel.selectedFile.fileId } as const);

  /** 文件导航复用 controller 的严格 fileId 选择，越界时保持当前 Diff。 */
  const selectRelativeFile = (offset: -1 | 1): void => {
    const next = viewModel.visibleFiles[currentFileIndex + offset];
    if (next !== undefined) {
      actions.selectFile(next.fileId);
      setDetailOpen(true);
      onNavigationStateChange?.({ detailOpen: true, query: state.query, tree: treeNavigation });
    }
  };

  const fallbackNavigation = (
    <SourceSelector
      source={state.source}
      sources={viewModel.sourceOptions}
      branchLabels={branchLabels}
      onChange={actions.setSource}
    />
  );
  const toolbar = (
    <>
      <FileActionMenu
        selectedActions={selectedReviewActions}
        selectedTarget={selectedFileTarget}
        allActions={availableReviewActions}
        pending={state.pendingOperationIds.size > 0}
        onAction={onAction}
      />
      <div className="ja-review-view-toggle" role="group" aria-label="Diff 布局">
        <IconButton
          label="统一 Diff"
          tooltip="统一"
          aria-pressed={state.viewMode === "unified"}
          onClick={() => actions.setViewMode("unified")}
        >
          <Rows3 aria-hidden="true" />
        </IconButton>
        <IconButton
          label="双栏 Diff"
          tooltip="双栏"
          aria-pressed={state.viewMode === "split"}
          onClick={() => actions.setViewMode("split")}
        >
          <Columns3 aria-hidden="true" />
        </IconButton>
      </div>
    </>
  );
  const treeFiles = useMemo(
    () =>
      viewModel.visibleFiles.map((file) => ({
        id: file.fileId,
        path: file.path,
        status: file.status,
        layer: file.layer,
        additions: file.additions,
        deletions: file.deletions,
        binary: file.binary,
      })),
    [viewModel.visibleFiles],
  );
  const tree = (
    <ReviewFileTree
      files={treeFiles}
      selectedId={state.selectedFileId}
      selectedButtonRef={selectedButtonRef}
      query={state.query}
      onQueryChange={actions.setQuery}
      onSelect={(file) => {
        actions.selectFile(file.id);
        setDetailOpen(true);
        onNavigationStateChange?.({ detailOpen: true, query: state.query, tree: treeNavigation });
      }}
      navigationState={treeNavigation}
      onNavigationStateChange={(next) => {
        setTreeNavigation(next);
        onNavigationStateChange?.({ detailOpen, query: state.query, tree: next });
      }}
      loading={state.loading}
      emptyMessage={state.snapshot === undefined ? "没有可审查的文件。" : "当前范围没有变更。"}
    />
  );
  const diff = (
    <div
      className="ja-review-diff"
      aria-label="变更 Diff"
      data-review-selected-file-id={viewModel.selectedFile?.fileId}
      data-review-selected-layer={viewModel.selectedFile?.layer}
    >
      {state.loading ? (
        <ReviewStatus kind="loading" message="正在读取审阅…" />
      ) : state.diffError !== undefined ? (
        <ReviewStatus kind="error" message={state.diffError.message} onRetry={actions.refresh} />
      ) : state.snapshot === undefined ? (
        <ReviewStatus kind="empty" message="没有可用的审阅快照。" />
      ) : viewModel.selectedFile !== undefined && state.diffLoading ? (
        <ReviewStatus kind="loading" message={`正在读取 ${viewModel.selectedFile.path}…`} />
      ) : viewModel.selectedFile?.binary ? (
        <ReviewStatus kind="empty" message="二进制文件不提供文本 Diff。" />
      ) : viewModel.selectedFile?.truncated ? (
        <ReviewStatus kind="empty" message="文件超过内容上限，无法显示文本 Diff。" />
      ) : selectedDiff === undefined ? (
        <ReviewStatus kind="empty" message="选择文件查看 Diff。" />
      ) : (
        <>
          <div className="ja-review-diff-header">
            <div>
              <strong title={selectedDiff.path}>{selectedDiff.path}</strong>
              {selectedDiff.oldPath === null ? null : (
                <small>从 {selectedDiff.oldPath} 重命名</small>
              )}
            </div>
            <span>{selectedDiff.status}</span>
          </div>
          {splitDiffAvailable && state.viewMode === "split" ? (
            <DiffViewer
              filePath={selectedDiff.path}
              original={selectedDiff.original ?? ""}
              modified={selectedDiff.modified ?? ""}
              revision={selectedDiff.revision}
              onCopyText={onCopyText}
            />
          ) : unifiedDiffAvailable ? (
            <ReviewUnifiedDiff
              file={selectedDiff}
              viewMode={state.viewMode}
              revision={`${selectedDiff.revision}:${selectedDiff.fileId}`}
              onCopyText={onCopyText}
              renderHunkActions={(hunk: ReviewUnifiedDiffHunk) =>
                hunk.hunkId === undefined ? null : (
                  <ActionMenu
                    label="区块操作"
                    actions={selectedReviewActions}
                    target={{ kind: "hunk", fileId: selectedDiff.fileId, hunkId: hunk.hunkId }}
                    pending={state.pendingOperationIds.size > 0}
                    onAction={onAction}
                  />
                )
              }
            />
          ) : splitDiffAvailable ? (
            <DiffViewer
              filePath={selectedDiff.path}
              original={selectedDiff.original ?? ""}
              modified={selectedDiff.modified ?? ""}
              revision={selectedDiff.revision}
              onCopyText={onCopyText}
            />
          ) : (
            <UnavailableDiff diff={selectedDiff} />
          )}
        </>
      )}
    </div>
  );
  return (
    <>
      <ReviewShell
        ariaLabel="审阅"
        scopeLabel={sourceLabel(state.source, branchLabels.get(sourceKey(state.source)))}
        sourceNavigation={sourceNavigation ?? fallbackNavigation}
        stats={{
          files: state.snapshot?.stats.files ?? 0,
          additions: state.snapshot?.stats.additions ?? 0,
          deletions: state.snapshot?.stats.deletions ?? 0,
        }}
        refreshing={state.loading || state.catalogLoading}
        onRefresh={actions.refresh}
        refreshLabel="重新读取审阅"
        notice={
          notice === undefined ? undefined : (
            <button
              type="button"
              className={`ja-review-notice${state.error === undefined ? "" : " is-error"}`}
              onClick={state.error === undefined ? actions.clearNotice : actions.refresh}
            >
              <span>{notice}</span>
              <X aria-hidden="true" />
            </button>
          )
        }
        toolbar={toolbar}
        tree={tree}
        diff={diff}
        detailOpen={detailOpen}
        onBack={() => {
          setDetailOpen(false);
          onNavigationStateChange?.({
            detailOpen: false,
            query: state.query,
            tree: treeNavigation,
          });
          requestAnimationFrame(() => selectedButtonRef.current?.focus());
        }}
        onPreviousFile={() => selectRelativeFile(-1)}
        onNextFile={() => selectRelativeFile(1)}
        previousDisabled={currentFileIndex <= 0}
        nextDisabled={currentFileIndex < 0 || currentFileIndex >= viewModel.visibleFiles.length - 1}
        dataAttributes={{ "data-source": state.source.kind }}
      />
      <AlertDialog.Root
        open={pendingRevertTarget !== undefined}
        onOpenChange={(open) => {
          if (!open) setPendingRevertTarget(undefined);
        }}
      >
        <AlertDialog.Portal>
          <AlertDialog.Overlay className="ja-review-confirm-overlay" />
          <AlertDialog.Content
            className="ja-review-confirm-dialog"
            onOpenAutoFocus={(event) => {
              event.preventDefault();
              revertCancelRef.current?.focus();
            }}
          >
            <AlertDialog.Title>{revertCopy?.title}</AlertDialog.Title>
            <AlertDialog.Description>{revertCopy?.description}</AlertDialog.Description>
            <div className="ja-review-confirm-actions">
              <AlertDialog.Cancel asChild>
                <button
                  ref={revertCancelRef}
                  type="button"
                  className="ja-button ja-button-secondary ja-button-sm"
                >
                  取消
                </button>
              </AlertDialog.Cancel>
              <AlertDialog.Action asChild>
                <Button type="button" variant="danger" size="sm" onClick={confirmPendingRevert}>
                  确认撤销
                </Button>
              </AlertDialog.Action>
            </div>
          </AlertDialog.Content>
        </AlertDialog.Portal>
      </AlertDialog.Root>
    </>
  );
}
