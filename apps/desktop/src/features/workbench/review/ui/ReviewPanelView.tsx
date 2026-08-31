// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Archive,
  Columns3,
  FileCode2,
  FileDiff,
  FilePlus2,
  FileX2,
  GitCompareArrows,
  History,
  Layers3,
  ListFilter,
  LoaderCircle,
  PanelRight,
  PanelRightClose,
  RefreshCw,
  Rows3,
  Search,
  ShieldAlert,
  Undo2,
  X,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState, type ReactElement } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import type {
  ReviewAction,
  ReviewFile,
  ReviewFileDiff,
  ReviewSource,
  ReviewTarget,
} from "../domain/types";
import { DiffViewer } from "@/features/workbench/editor";
import { CopyTextButton } from "@/shared/ui/CopyTextButton";
import { useMediaQuery } from "@/shared/hooks/useMediaQuery";
import { IconButton, Select } from "@/shared/ui/primitives";
import type { ReviewActions, ReviewViewModel } from "../application/useReviewController";
import {
  actionLabel,
  canRenderTextDiff,
  canReviewAction,
  fileStatusLabel,
  fileStatusMark,
  sourceKey,
  sourceLabel,
  type ReviewFilter,
} from "../domain/model";
import "./ReviewPanel.css";

const REVIEW_DRAWER_QUERY = "(max-width: 760px)";

export interface ReviewPanelViewProps {
  viewModel: ReviewViewModel;
  actions: ReviewActions;
  onCopyText?: (text: string) => Promise<void>;
}

interface ActionButtonProps {
  action: ReviewAction;
  target: ReviewTarget;
  disabled: boolean;
  pending: boolean;
  onAction: (action: ReviewAction, target: ReviewTarget) => void;
}

interface ReviewFileRowProps {
  file: ReviewFile;
  selected: boolean;
  pending: boolean;
  primaryAction: ReviewAction | undefined;
  secondaryAction: ReviewAction | undefined;
  onSelect: () => void;
  onAction: (action: ReviewAction, target: ReviewTarget) => void;
}

/** 为键盘用户渲染带稳定标签和 pending 状态的单个图标 action。 */
function ActionButton({
  action,
  target,
  disabled,
  pending,
  onAction,
}: ActionButtonProps): ReactElement {
  const Icon = action === "stage" ? Layers3 : action === "unstage" ? Archive : Undo2;
  const label = actionLabel(action, target);
  return (
    <IconButton
      className={`ja-review-action is-${action}`}
      label={label}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      onClick={() => onAction(action, target)}
    >
      {pending ? (
        <LoaderCircle className="ja-review-spin" aria-hidden="true" />
      ) : (
        <Icon aria-hidden="true" />
      )}
    </IconButton>
  );
}

/** 把状态映射为真实 lucide 文件图标，使文件树保持易扫描。 */
function FileStatusIcon({ status }: { status: ReviewFile["status"] }): ReactElement {
  const Icon =
    status === "added" || status === "untracked"
      ? FilePlus2
      : status === "deleted"
        ? FileX2
        : status === "renamed"
          ? GitCompareArrows
          : FileCode2;
  return <Icon aria-hidden="true" />;
}

/** 展示单个文件行；action 只指向当前 snapshot 的精确 file id。 */
function ReviewFileRow({
  file,
  selected,
  pending,
  primaryAction,
  secondaryAction,
  onSelect,
  onAction,
}: ReviewFileRowProps): ReactElement {
  const target: ReviewTarget = { kind: "file", fileId: file.fileId };
  return (
    <div
      className="ja-review-file-row"
      data-selected={selected || undefined}
      data-status={file.status}
    >
      <button
        type="button"
        className="ja-review-file-select"
        aria-label={`${file.path}，${fileStatusLabel(file.status)}`}
        aria-pressed={selected}
        onClick={onSelect}
      >
        <span className="ja-review-file-kind" data-status={file.status}>
          <FileStatusIcon status={file.status} />
        </span>
        <span className="ja-review-file-name" title={file.path}>
          {file.path}
        </span>
        <span className="ja-review-file-status" aria-label={fileStatusLabel(file.status)}>
          {fileStatusMark(file.status)}
        </span>
        <span
          className="ja-review-file-delta"
          aria-label={`增加 ${file.additions ?? 0} 行，删除 ${file.deletions ?? 0} 行`}
        >
          <span className="is-added">+{file.additions ?? 0}</span>
          <span className="is-removed">-{file.deletions ?? 0}</span>
        </span>
      </button>
      <div className="ja-review-file-actions" aria-label={`${file.path} 操作`}>
        {primaryAction === undefined ? null : (
          <ActionButton
            action={primaryAction}
            target={target}
            disabled={false}
            pending={pending}
            onAction={onAction}
          />
        )}
        {secondaryAction === undefined ? null : (
          <ActionButton
            action={secondaryAction}
            target={target}
            disabled={false}
            pending={pending}
            onAction={onAction}
          />
        )}
      </div>
    </div>
  );
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

const REVIEW_FILTER_OPTIONS = [
  { value: "all", label: "全部" },
  { value: "added", label: "新增" },
  { value: "modified", label: "修改" },
  { value: "deleted", label: "删除" },
  { value: "renamed", label: "重命名" },
  { value: "conflicted", label: "冲突" },
  { value: "untracked", label: "未跟踪" },
] satisfies ReadonlyArray<{ value: ReviewFilter; label: string }>;

/** 保持 header 中 file/add/remove 计数一眼可读。 */
function ReviewStats({
  files,
  additions,
  deletions,
  binaryFiles,
}: {
  files: number;
  additions: number;
  deletions: number;
  binaryFiles: number;
}): ReactElement {
  return (
    <div className="ja-review-stats" aria-label="变更统计">
      <span className="ja-review-stat">
        <strong>{files}</strong>
        <small>文件</small>
      </span>
      <span className="ja-review-stat is-added">
        <strong>+{additions}</strong>
        <small>增加</small>
      </span>
      <span className="ja-review-stat is-removed">
        <strong>-{deletions}</strong>
        <small>删除</small>
      </span>
      <span className="ja-review-stat">
        <strong>{binaryFiles}</strong>
        <small>二进制</small>
      </span>
    </div>
  );
}

/** 限制 unified mode 的初始上下文，同时允许用户按需展开。 */
function UnifiedDiffView({
  diff,
  showUnchanged,
  onToggleUnchanged,
  onCopyText,
}: {
  diff: ReviewFileDiff;
  showUnchanged: boolean;
  onToggleUnchanged: () => void;
  onCopyText?: (text: string) => Promise<void>;
}): ReactElement {
  const visibleLines = showUnchanged
    ? diff.lines
    : diff.lines.filter((line) => line.kind !== "context");
  const copyText =
    diff.unified ??
    diff.lines
      .map(
        (line) =>
          `${line.kind === "addition" ? "+" : line.kind === "deletion" ? "-" : " "}${line.text}`,
      )
      .join("\n");
  return (
    <section className="ja-review-unified" aria-label={`统一 Diff ${diff.path}`}>
      <div className="ja-review-unified-toolbar">
        <span>{showUnchanged ? "显示未变化区域" : "未变化区域已折叠"}</span>
        <div className="ja-review-unified-actions">
          <button type="button" className="ja-review-text-button" onClick={onToggleUnchanged}>
            {showUnchanged ? "折叠" : "展开"}
          </button>
          {onCopyText === undefined ? null : (
            <CopyTextButton text={copyText} label="复制 Diff" onCopyText={onCopyText} />
          )}
        </div>
      </div>
      <pre className="ja-review-unified-code">
        {visibleLines.length === 0 ? (
          <code>未变化区域已折叠</code>
        ) : (
          visibleLines.map((line, index) => (
            <code
              className={`ja-review-diff-line is-${line.kind}`}
              key={`${line.oldLine ?? "-"}:${line.newLine ?? "-"}:${index}`}
            >
              <span className="ja-review-line-number">{line.oldLine ?? ""}</span>
              <span className="ja-review-line-number">{line.newLine ?? ""}</span>
              <span className="ja-review-line-marker">
                {line.kind === "addition" ? "+" : line.kind === "deletion" ? "-" : " "}
              </span>
              <span>{line.text}</span>
              {"\n"}
            </code>
          ))
        )}
      </pre>
    </section>
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

/** 根据 native hunk id 渲染 hunk 级控件，客户端不自行合成 patch。 */
function HunkActions({
  diff,
  capabilities,
  pending,
  onAction,
}: {
  diff: ReviewFileDiff;
  capabilities: ReviewCapabilitiesLike;
  pending: boolean;
  onAction: (action: ReviewAction, target: ReviewTarget) => void;
}): ReactElement | null {
  if (diff.hunks.length === 0) return null;
  const actions = availableActions(capabilities, diff.source);
  return (
    <div className="ja-review-hunks" aria-label="Diff 区块操作">
      <span className="ja-review-section-label">区块</span>
      {diff.hunks.slice(0, 200).map((hunk) => (
        <div className="ja-review-hunk" key={hunk.hunkId}>
          <code>{hunk.header}</code>
          <div className="ja-review-hunk-actions">
            {actions.map((action) => (
              <ActionButton
                key={action}
                action={action}
                target={{ kind: "hunk", fileId: diff.fileId, hunkId: hunk.hunkId }}
                disabled={false}
                pending={pending}
                onAction={onAction}
              />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

type ReviewCapabilitiesLike = { stage: boolean; unstage: boolean; revert: boolean };

/** 在保留 native capability 校验的前提下派生 source 可见 action。 */
function availableActions(
  capabilities: ReviewCapabilitiesLike | undefined,
  source: ReviewSource,
): ReviewAction[] {
  if (capabilities === undefined) return [];
  const candidates: ReviewAction[] =
    source.kind === "unstaged"
      ? ["stage", "revert"]
      : source.kind === "staged"
        ? ["unstage", "revert"]
        : [];
  return candidates.filter((action) => canReviewAction(capabilities, action));
}

/** 对破坏性 file/all 操作返回防御性确认文案。 */
function confirmRevert(target: ReviewTarget): boolean {
  if (target.kind === "hunk" || typeof window === "undefined") return true;
  return window.confirm(
    target.kind === "all"
      ? "撤销全部变更？此操作不可自动恢复。"
      : "撤销这个文件的变更？此操作不可自动恢复。",
  );
}

/** 纯视图只消费 controller 的 view model/actions，不直接读取 Tauri 或创建业务状态 owner。 */
export function ReviewPanelView({
  viewModel,
  actions,
  onCopyText,
}: ReviewPanelViewProps): ReactElement {
  const { state } = viewModel;
  const fileListRef = useRef<HTMLDivElement>(null);
  const drawerTriggerRef = useRef<HTMLButtonElement>(null);
  const wasDrawerOpen = useRef(state.drawerOpen);
  const [showUnchanged, setShowUnchanged] = useState(false);
  const drawerMode = useMediaQuery(REVIEW_DRAWER_QUERY);
  const filesInteractive = !drawerMode || state.drawerOpen;
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
  // TanStack Virtual 返回的命令函数不可安全 memoize，此处只把它留在当前纯视图内部。
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count: viewModel.visibleFiles.length,
    getScrollElement: () => fileListRef.current,
    estimateSize: () => 45,
    overscan: 8,
  });
  const virtualItems = virtualizer.getVirtualItems();
  const renderedItems =
    virtualItems.length > 0 || viewModel.visibleFiles.length === 0
      ? virtualItems
      : [
          {
            index: 0,
            start: 0,
            size: 45,
            end: 45,
            key: viewModel.visibleFiles[0]?.fileId ?? "empty",
            lane: 0,
          },
        ];
  const capabilities = state.snapshot?.capabilities;
  const availableReviewActions = availableActions(capabilities, state.source);
  const primaryAction = availableReviewActions[0];
  const secondaryAction = availableReviewActions[1];
  const selectedDiff = state.diff;

  /** 窄屏抽屉关闭后恢复焦点，并把键盘导航留在当前 Review。 */
  useEffect(() => {
    if (wasDrawerOpen.current && !state.drawerOpen) drawerTriggerRef.current?.focus();
    wasDrawerOpen.current = state.drawerOpen;
  }, [state.drawerOpen]);

  /** 允许 Escape 关闭文件抽屉，同时不劫持 editor 或 input 焦点。 */
  useEffect(() => {
    if (!state.drawerOpen) return undefined;
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === "Escape") actions.setDrawerOpen(false);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [actions, state.drawerOpen]);

  /** 仅在产品合同定义的破坏性 all/file action 上增加确认。 */
  const onAction = (action: ReviewAction, target: ReviewTarget): void => {
    if (action === "revert" && !confirmRevert(target)) return;
    void actions.applyAction(action, target);
  };

  /** 把紧凑状态通知解析为安全的可见文案。 */
  const notice = state.error?.message ?? state.notice;
  return (
    <section className="ja-review-panel" aria-label="审查" data-source={state.source.kind}>
      <header className="ja-review-header">
        <div className="ja-review-title-wrap">
          <span className="ja-review-title-icon">
            <GitCompareArrows aria-hidden="true" />
          </span>
          <div className="ja-review-heading-copy">
            <h2>审查</h2>
            <SourceSelector
              source={state.source}
              sources={viewModel.sourceOptions}
              branchLabels={branchLabels}
              onChange={actions.setSource}
            />
          </div>
        </div>
        <div className="ja-review-header-actions">
          <IconButton
            className="ja-review-icon-button"
            label="重新读取审查"
            tooltip="重新读取"
            onClick={actions.refresh}
            disabled={state.loading || state.catalogLoading}
          >
            <RefreshCw
              className={state.loading || state.catalogLoading ? "ja-review-spin" : undefined}
              aria-hidden="true"
            />
          </IconButton>
          <IconButton
            ref={drawerTriggerRef}
            className="ja-review-icon-button ja-review-drawer-trigger"
            label={state.drawerOpen ? "关闭文件列表" : "打开文件列表"}
            aria-expanded={state.drawerOpen}
            onClick={() => actions.setDrawerOpen(!state.drawerOpen)}
          >
            <PanelRight aria-hidden="true" />
          </IconButton>
        </div>
      </header>

      <ReviewStats
        files={state.snapshot?.stats.files ?? 0}
        additions={state.snapshot?.stats.additions ?? 0}
        deletions={state.snapshot?.stats.deletions ?? 0}
        binaryFiles={state.snapshot?.stats.binaryFiles ?? 0}
      />
      {notice === undefined ? null : (
        <button
          type="button"
          className={`ja-review-notice${state.error === undefined ? "" : " is-error"}`}
          onClick={state.error === undefined ? actions.clearNotice : actions.refresh}
        >
          <span>{notice}</span>
          <X aria-hidden="true" />
        </button>
      )}
      <div className="ja-review-toolbar">
        <div className="ja-review-toolbar-actions" aria-label="审查操作">
          {availableReviewActions.map((action) => (
            <ActionButton
              key={action}
              action={action}
              target={{ kind: "all" }}
              disabled={false}
              pending={state.pendingOperationIds.size > 0}
              onAction={onAction}
            />
          ))}
        </div>
        <div className="ja-review-view-toggle" role="group" aria-label="Diff 布局">
          <IconButton
            label="双栏 Diff"
            tooltip="双栏"
            aria-pressed={state.viewMode === "split"}
            onClick={() => actions.setViewMode("split")}
          >
            <Columns3 aria-hidden="true" />
          </IconButton>
          <IconButton
            label="统一 Diff"
            tooltip="统一"
            aria-pressed={state.viewMode === "unified"}
            onClick={() => actions.setViewMode("unified")}
          >
            <Rows3 aria-hidden="true" />
          </IconButton>
        </div>
        <div className="ja-review-toolbar-source">
          <span className="ja-review-source-meta">
            <History aria-hidden="true" />
            {state.catalog?.repositoryName ?? "工作区"}
          </span>
          <span className="ja-review-source-meta">
            {state.catalog?.currentBranch ?? "未命名分支"}
          </span>
        </div>
      </div>

      <div className="ja-review-body">
        <main className="ja-review-diff" aria-label="变更 Diff">
          {state.loading ? (
            <ReviewStatus kind="loading" message="正在读取审查…" />
          ) : state.diffError !== undefined ? (
            <ReviewStatus
              kind="error"
              message={state.diffError.message}
              onRetry={actions.refresh}
            />
          ) : state.snapshot === undefined ? (
            <ReviewStatus kind="empty" message="没有可用的审查快照。" />
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
              <HunkActions
                diff={selectedDiff}
                capabilities={capabilities ?? { stage: false, unstage: false, revert: false }}
                pending={state.pendingOperationIds.size > 0}
                onAction={onAction}
              />
              {state.diffLoading ? (
                <ReviewStatus kind="loading" message="正在读取文件 Diff…" />
              ) : canRenderTextDiff(selectedDiff) && state.viewMode === "split" ? (
                <DiffViewer
                  filePath={selectedDiff.path}
                  original={selectedDiff.original ?? ""}
                  modified={selectedDiff.modified ?? ""}
                  revision={selectedDiff.revision}
                  onCopyText={onCopyText}
                />
              ) : canRenderTextDiff(selectedDiff) ? (
                <UnifiedDiffView
                  diff={selectedDiff}
                  showUnchanged={showUnchanged}
                  onToggleUnchanged={() => setShowUnchanged((value) => !value)}
                  onCopyText={onCopyText}
                />
              ) : (
                <UnavailableDiff diff={selectedDiff} />
              )}
            </>
          )}
        </main>

        {state.drawerOpen ? (
          <button
            type="button"
            className="ja-review-drawer-backdrop"
            aria-label="关闭文件列表"
            onClick={() => actions.setDrawerOpen(false)}
          />
        ) : null}
        <aside
          className={`ja-review-files${state.drawerOpen ? " is-drawer-open" : ""}`}
          aria-label="变更文件列表"
          aria-hidden={filesInteractive ? undefined : true}
          inert={!filesInteractive}
        >
          <div className="ja-review-files-header">
            <strong>变更文件</strong>
            <span>{viewModel.visibleFiles.length}</span>
            <IconButton
              className="ja-review-icon-button ja-review-files-close"
              label="关闭文件列表"
              tooltip="关闭"
              onClick={() => actions.setDrawerOpen(false)}
            >
              <PanelRightClose aria-hidden="true" />
            </IconButton>
          </div>
          <div className="ja-review-files-tools">
            <label className="ja-review-search">
              <Search aria-hidden="true" />
              <span className="ja-visually-hidden">筛选文件</span>
              <input
                type="search"
                value={state.query}
                onChange={(event) => actions.setQuery(event.target.value)}
                placeholder="筛选文件…"
              />
            </label>
            <div className="ja-review-filter">
              <ListFilter aria-hidden="true" />
              <Select
                ariaLabel="变更类型"
                className="ja-review-filter-control"
                size="compact"
                value={state.filter}
                options={REVIEW_FILTER_OPTIONS}
                onValueChange={(value) => actions.setFilter(value as ReviewFilter)}
              />
            </div>
          </div>
          <div
            className="ja-review-file-list"
            ref={fileListRef}
            role="listbox"
            aria-label="审查文件"
          >
            <div
              className="ja-review-file-list-spacer"
              style={{ height: virtualizer.getTotalSize() }}
            >
              {state.loading ? (
                <ReviewStatus kind="loading" message="正在读取文件…" />
              ) : state.snapshot === undefined ? (
                <ReviewStatus kind="empty" message="没有可审查的文件。" />
              ) : viewModel.visibleFiles.length === 0 ? (
                <ReviewStatus
                  kind="empty"
                  message={
                    state.query.length > 0 || state.filter !== "all"
                      ? "没有匹配的文件。"
                      : "当前来源没有变更。"
                  }
                />
              ) : (
                renderedItems.map((virtualItem) => {
                  const file = viewModel.visibleFiles[virtualItem.index];
                  if (file === undefined) return null;
                  return (
                    <div
                      className="ja-review-file-virtual-row"
                      key={file.fileId}
                      data-index={virtualItem.index}
                      style={{ transform: `translateY(${virtualItem.start}px)` }}
                    >
                      <ReviewFileRow
                        file={file}
                        selected={file.fileId === state.selectedFileId}
                        pending={state.pendingOperationIds.size > 0}
                        primaryAction={primaryAction}
                        secondaryAction={secondaryAction}
                        onSelect={() => actions.selectFile(file.fileId)}
                        onAction={onAction}
                      />
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </aside>
      </div>
    </section>
  );
}
