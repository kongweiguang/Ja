// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as AlertDialog from "@radix-ui/react-alert-dialog";
import { Files, Search, X } from "lucide-react";
import { lazy, Suspense, useCallback, useLayoutEffect, useRef, type ReactElement } from "react";
import { EmptyState, IconButton, LoadingState } from "@/shared/ui/primitives";
import { FileTree } from "./FileTree";
import { SaveAsDialog } from "./SaveAsDialog";
import { SearchPanel } from "./SearchPanel";
import { TrashConfirmDialog } from "./TrashConfirmDialog";
import type { FilesWorkspaceViewProps } from "./types";
import type { OpenDocument } from "../application/types";
import { entryName } from "../domain/filesModel";
import type { FileRevision } from "../domain/types";
import "./files.css";

const CodeEditor = lazy(() =>
  import("@/features/workbench/editor").then((module) => ({ default: module.CodeEditor })),
);
const DiffViewer = lazy(() =>
  import("@/features/workbench/editor").then((module) => ({ default: module.DiffViewer })),
);

/** 生成稳定且可读的状态文案，不把 native 原始错误或内部状态泄露到视图。 */
function statusLabel(document: OpenDocument): string {
  if (document.readOnly) return document.readOnlyReason ?? "只读文件";
  switch (document.status) {
    case "dirty":
      return "未保存";
    case "saving":
      return "保存中…";
    case "saveError":
      return "保存失败";
    case "conflict":
      return "外部冲突";
    case "clean":
      return "已保存";
  }
}

/** 只为渲染派生 key；native CAS 写入仍由 controller 保留完整 revision。 */
function revisionKey(revision: FileRevision): string {
  return (
    revision.sha256 ??
    `${revision.kind}:${revision.size}:${revision.modifiedUnixMillis ?? "unknown"}`
  );
}

/**
 * 纯视图只消费 view model/actions；资源读取、保存、Watcher、Move、Trash 与 Drop 的
 * generation/事务规则全部留在唯一 controller，避免 JSX 形成第二个状态 owner。
 */
export function FilesWorkspace({ viewModel, actions }: FilesWorkspaceViewProps): ReactElement {
  const {
    nodes,
    selectedPath,
    treeLoading,
    treeError,
    mode,
    searchQuery,
    searchResults,
    searchSummary,
    searchLoading,
    searchError,
    documents,
    openPaths,
    activePath,
    activeDocument,
    comparePath,
    conflictAction,
    saveAsRequest,
    trashRequest,
    closeDocumentRequest,
    closeRequestedDocument,
    lifecycleClosing,
    mutationRecoveryRequired,
    openTargets,
  } = viewModel;
  const editorTabsRef = useRef<HTMLDivElement>(null);
  const openPathsKey = openPaths.join("\u0000");

  /** 让程序恢复的 Tab 保持可见，但不抢走编辑器键盘焦点。 */
  useLayoutEffect(() => {
    if (activePath === undefined) return;
    const activeTab = Array.from(
      editorTabsRef.current?.querySelectorAll<HTMLElement>("[data-file-tab-path]") ?? [],
    ).find((tab) => tab.dataset["fileTabPath"] === activePath);
    activeTab?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  }, [activePath, openPathsKey]);

  /** 对话框关闭后把焦点恢复到来源行；目标已删除时退回文件树，保持键盘操作闭环。 */
  const restoreTrashFocus = useCallback((): void => {
    const relativePath = trashRequest?.relativePath ?? "";
    window.requestAnimationFrame(() => {
      const tree = document.querySelector<HTMLElement>('.ja-file-tree-host [role="tree"]');
      const pathElement = [
        ...document.querySelectorAll<HTMLElement>(".ja-file-tree-host [data-path]"),
      ].find((element) => element.dataset["path"] === relativePath);
      const target = pathElement?.closest<HTMLElement>('[role="treeitem"]') ?? pathElement ?? tree;
      target?.focus();
    });
  }, [trashRequest?.relativePath]);

  return (
    <section className="ja-files-workspace" aria-label="文件工作区">
      <div className="ja-files-workspace-toolbar">
        <div className="ja-files-workspace-mode" role="tablist" aria-label="文件工作区视图">
          <button
            type="button"
            role="tab"
            aria-selected={mode === "files"}
            className={mode === "files" ? "is-active" : ""}
            onClick={actions.showFiles}
          >
            <Files aria-hidden="true" />
            文件
          </button>
          {actions.showSearch === undefined ? null : (
            <button
              type="button"
              role="tab"
              aria-selected={mode === "search"}
              className={mode === "search" ? "is-active" : ""}
              onClick={actions.showSearch}
            >
              <Search aria-hidden="true" />
              搜索
            </button>
          )}
        </div>
        <span className="ja-files-workspace-count">
          {openPaths.length > 0 ? `${openPaths.length} 个打开的文件` : ""}
        </span>
      </div>
      {mutationRecoveryRequired ? (
        <div className="ja-files-recovery-gate" role="alert">
          <strong>工作区写入已暂停</strong>
          <span>
            原生文件事务需要人工核对。你仍可查看和外部打开文件；重新打开工作区或重启 Ja
            后再继续修改。
          </span>
        </div>
      ) : null}
      <div
        className={`ja-files-workspace-body${openPaths.length === 0 ? " is-browser-only" : " has-open-document"}`}
      >
        <aside
          className="ja-files-workspace-explorer"
          aria-label="工作区资源管理器"
          hidden={mode !== "files"}
        >
          <FileTree
            nodes={nodes}
            selectedPath={selectedPath}
            loading={treeLoading}
            error={treeError}
            onSelect={actions.selectNode}
            onDirectoryToggle={actions.toggleDirectory}
            onRetry={actions.retryTree}
            onCreateFile={actions.createFile}
            onCreateDirectory={actions.createDirectory}
            onRename={actions.rename}
            onMove={actions.move}
            onTrash={actions.trash}
            onRefresh={actions.refreshTree}
            onNativeDropToken={actions.importDrop}
            openTargets={openTargets}
            onOpenTarget={actions.openTarget}
          />
        </aside>
        {mode === "search" ? (
          <div className="ja-files-workspace-search">
            <SearchPanel
              query={searchQuery}
              results={searchResults}
              summary={searchSummary}
              loading={searchLoading}
              error={searchError}
              onQueryChange={actions.changeSearchQuery}
              onOpenResult={actions.openSearchResult}
            />
          </div>
        ) : null}
        <div className="ja-files-workspace-editor">
          {openPaths.length === 0 ? (
            <EmptyState
              className="ja-files-workspace-empty"
              title="从左侧文件树选择文件开始编辑。"
            />
          ) : (
            <>
              <div
                ref={editorTabsRef}
                className="ja-files-editor-tabs"
                role="tablist"
                aria-label="打开的文件"
              >
                {openPaths.map((path) => {
                  const openDocument = documents[path];
                  if (openDocument === undefined) return null;
                  return (
                    <div
                      className={`ja-files-editor-tab${path === activePath ? " is-active" : ""}`}
                      data-file-tab-path={path}
                      key={path}
                      role="presentation"
                    >
                      <button
                        type="button"
                        role="tab"
                        aria-selected={path === activePath}
                        onClick={() => actions.selectDocument(path)}
                        title={path}
                      >
                        <span className="ja-files-editor-tab-name">{entryName(path)}</span>
                        {openDocument.status !== "clean" ? (
                          <span
                            className="ja-files-editor-tab-dirty"
                            aria-label={statusLabel(openDocument)}
                          />
                        ) : null}
                      </button>
                      <IconButton
                        className="ja-files-editor-tab-close"
                        label={`关闭 ${entryName(path)}`}
                        onClick={() => actions.closeDocument(path)}
                      >
                        <X aria-hidden="true" />
                      </IconButton>
                    </div>
                  );
                })}
              </div>
              {activeDocument === undefined ? (
                <EmptyState className="ja-files-workspace-empty" title="选择一个打开的文件。" />
              ) : (
                <div className="ja-files-editor-content">
                  <div className="ja-files-editor-status" data-status={activeDocument.status}>
                    <span>{statusLabel(activeDocument)}</span>
                    <span>{activeDocument.encoding}</span>
                    <span>{activeDocument.newline.toUpperCase()}</span>
                  </div>
                  {activeDocument.status === "conflict" ? (
                    <div className="ja-files-conflict" role="alert">
                      <span>{activeDocument.error}</span>
                      <button
                        type="button"
                        disabled={conflictAction !== undefined}
                        onClick={() => actions.compareConflict(activeDocument.path)}
                      >
                        {conflictAction?.path === activeDocument.path &&
                        conflictAction.kind === "compare"
                          ? "读取中…"
                          : "比较"}
                      </button>
                      <button
                        type="button"
                        disabled={conflictAction !== undefined}
                        onClick={() => actions.reloadConflict(activeDocument.path)}
                      >
                        {conflictAction?.path === activeDocument.path &&
                        conflictAction.kind === "reload"
                          ? "重新加载中…"
                          : "重新加载"}
                      </button>
                      {actions.beginSaveAs === undefined ? null : (
                        <button
                          type="button"
                          disabled={saveAsRequest?.pending === true}
                          onClick={() => actions.beginSaveAs?.(activeDocument.path)}
                        >
                          另存为
                        </button>
                      )}
                    </div>
                  ) : null}
                  {activeDocument.status === "saveError" ? (
                    <div className="ja-files-save-error" role="alert">
                      <span>{activeDocument.error}</span>
                      {mutationRecoveryRequired ? null : (
                        <button
                          type="button"
                          onClick={() => actions.retrySave(activeDocument.path)}
                        >
                          重试保存
                        </button>
                      )}
                    </div>
                  ) : null}
                  {comparePath === activeDocument.path &&
                  activeDocument.externalContent !== undefined ? (
                    <div className="ja-files-conflict-diff" aria-label="文件冲突比较">
                      <div className="ja-files-conflict-diff-heading">
                        <span>外部版本</span>
                        <span>本地草稿</span>
                        <button type="button" onClick={actions.hideComparison}>
                          返回编辑
                        </button>
                      </div>
                      <Suspense
                        fallback={
                          <LoadingState className="ja-feature-state" label="正在加载 Diff…" />
                        }
                      >
                        <DiffViewer
                          filePath={activeDocument.path}
                          original={activeDocument.externalContent}
                          modified={activeDocument.content}
                          revision={revisionKey(
                            activeDocument.externalRevision ?? activeDocument.revision,
                          )}
                        />
                      </Suspense>
                    </div>
                  ) : (
                    <Suspense
                      fallback={
                        <LoadingState className="ja-feature-state" label="正在加载编辑器…" />
                      }
                    >
                      <CodeEditor
                        filePath={activeDocument.path}
                        content={activeDocument.content}
                        revision={revisionKey(activeDocument.revision)}
                        readOnly={
                          activeDocument.readOnly || lifecycleClosing || mutationRecoveryRequired
                        }
                        reveal={activeDocument.reveal}
                        onChange={(content) => actions.editDocument(activeDocument.path, content)}
                        onSave={() => actions.saveDocument(activeDocument.path)}
                        onBlur={() => actions.saveDocument(activeDocument.path)}
                      />
                    </Suspense>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      </div>
      <SaveAsDialog
        open={saveAsRequest !== undefined}
        sourcePath={saveAsRequest?.sourcePath ?? ""}
        value={saveAsRequest?.targetPath ?? ""}
        error={saveAsRequest?.error}
        pending={saveAsRequest?.pending ?? false}
        onValueChange={actions.changeSaveAsTarget}
        onCancel={actions.cancelSaveAs}
        onSubmit={actions.submitSaveAs}
      />
      <AlertDialog.Root
        open={closeDocumentRequest !== undefined}
        onOpenChange={(open) => {
          if (!open) actions.dismissCloseDocument();
        }}
      >
        <AlertDialog.Portal>
          <AlertDialog.Overlay className="ja-files-trash-overlay" />
          <AlertDialog.Content className="ja-files-trash-dialog">
            <div className="ja-files-trash-heading">
              <div className="ja-files-trash-title-copy">
                <AlertDialog.Title>关闭未保存文件</AlertDialog.Title>
                <AlertDialog.Description>
                  关闭会丢弃尚未保存的本地草稿；已经开始的原生保存必须先结束。
                </AlertDialog.Description>
              </div>
              <AlertDialog.Cancel asChild>
                <IconButton className="ja-files-trash-close" label="关闭文件确认">
                  <X aria-hidden="true" />
                </IconButton>
              </AlertDialog.Cancel>
            </div>
            <div className="ja-files-trash-body">
              <code className="ja-files-trash-path" title={closeDocumentRequest?.path}>
                {closeDocumentRequest?.path}
              </code>
              <p>
                {closeRequestedDocument?.status === "conflict"
                  ? "该草稿与外部文件冲突；请取消后比较、重新加载或另存为。"
                  : closeRequestedDocument?.status === "saving"
                    ? "正在等待当前保存完成；期间不会启动第二个保存请求。"
                    : "该文件仍有未完成的本地修改。"}
              </p>
            </div>
            <div className="ja-files-trash-actions">
              <AlertDialog.Cancel asChild>
                <button type="button">取消</button>
              </AlertDialog.Cancel>
              <AlertDialog.Action asChild>
                <button
                  type="button"
                  className="is-danger"
                  disabled={closeRequestedDocument?.status === "saving"}
                  onClick={actions.discardCloseDocument}
                >
                  {closeRequestedDocument?.status === "saving" ? "正在保存…" : "放弃草稿并关闭"}
                </button>
              </AlertDialog.Action>
            </div>
          </AlertDialog.Content>
        </AlertDialog.Portal>
      </AlertDialog.Root>
      <TrashConfirmDialog
        open={trashRequest !== undefined}
        relativePath={trashRequest?.relativePath ?? ""}
        phase={trashRequest?.phase ?? "preparing"}
        fileCount={trashRequest?.fileCount}
        totalBytes={trashRequest?.totalBytes}
        error={trashRequest?.error}
        onCancel={actions.cancelTrash}
        onConfirm={actions.confirmTrash}
        onRestoreFocus={restoreTrashFocus}
      />
    </section>
  );
}
