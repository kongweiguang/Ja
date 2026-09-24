// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as AlertDialog from "@radix-ui/react-alert-dialog";
import { Files, FilePlus2, Save, X } from "lucide-react";
import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactElement,
} from "react";
import {
  EmptyState,
  IconButton,
  LoadingState,
  MenuItem,
  MenuSeparator,
  PointerContextMenu,
} from "@/shared/ui/primitives";
import { FileTree } from "./FileTree";
import { SaveAsDialog } from "./SaveAsDialog";
import { SearchPanel } from "./SearchPanel";
import { TrashConfirmDialog } from "./TrashConfirmDialog";
import type { FilesWorkspaceViewProps } from "./types";
import type { OpenDocument } from "../application/types";
import { entryName } from "../domain/filesModel";
import type { FileRevision } from "../domain/types";
import "./files.css";

interface FileTabContextMenuSession {
  readonly sequence: number;
  readonly path: string;
  readonly x: number;
  readonly y: number;
  readonly trigger?: HTMLElement;
}

type FileTabContextAction = "save" | "retry-save" | "save-as" | "close";

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

/** 只统计当前权威树已载入的普通文件，避免把目录或未展开后代虚报为文件总量。 */
function loadedFileCount(nodes: FilesWorkspaceViewProps["viewModel"]["nodes"]): number {
  return nodes.reduce(
    (total, node) =>
      total +
      (node.kind === "file" ? 1 : 0) +
      (node.children === undefined ? 0 : loadedFileCount(node.children)),
    0,
  );
}

/**
 * 纯视图只消费 view model/actions；标签右键也只路由到同一 controller，避免 JSX 形成
 * 第二个状态 owner 或让右键误选当前文件。每次 Trash 请求重建 Dialog 实例，确保上一轮
 * Radix 关闭层不会吞掉用户紧接着打开的文件菜单。
 */
export function FilesWorkspace({
  viewModel,
  actions,
  onAddToConversation,
}: FilesWorkspaceViewProps): ReactElement {
  const {
    nodes,
    selectedPath,
    treeLoading,
    treeError,
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
  const [tabContextMenu, setTabContextMenu] = useState<FileTabContextMenuSession | undefined>();
  const tabContextMenuRef = useRef<FileTabContextMenuSession | undefined>(undefined);
  const tabContextMenuSequenceRef = useRef(0);
  const latestTabContextStateRef = useRef({
    documents,
    openPaths,
    lifecycleClosing,
    mutationRecoveryRequired,
    closeDocumentRequest,
    saveAsRequest,
    conflictAction,
    actions,
  });
  /** 事件动作复核提交后的 view model，避免在 render 阶段读写 ref。 */
  useLayoutEffect(() => {
    latestTabContextStateRef.current = {
      documents,
      openPaths,
      lifecycleClosing,
      mutationRecoveryRequired,
      closeDocumentRequest,
      saveAsRequest,
      conflictAction,
      actions,
    };
  }, [
    actions,
    closeDocumentRequest,
    conflictAction,
    documents,
    lifecycleClosing,
    mutationRecoveryRequired,
    openPaths,
    saveAsRequest,
  ]);
  const openPathsKey = openPaths.join("\u0000");
  const fileCount = loadedFileCount(nodes);

  /** 关闭后优先回到原控件；标签已移除时落到同标签或最近的剩余标签。 */
  const restoreFileTabFocus = useCallback((path: string, trigger?: HTMLElement): void => {
    if (trigger?.isConnected) {
      trigger.focus();
      return;
    }
    const tabNodes = [
      ...(editorTabsRef.current?.querySelectorAll<HTMLElement>("[data-file-tab-path]") ?? []),
    ];
    const sameTab = tabNodes.find((node) => node.dataset["fileTabPath"] === path);
    const target =
      sameTab?.querySelector<HTMLElement>('[role="tab"]') ??
      tabNodes[0]?.querySelector<HTMLElement>('[role="tab"]');
    target?.focus();
  }, []);

  /** 只用当前打开的 path 建立菜单会话，重复右键通过递增身份替换旧 Portal。 */
  const requestFileTabContextMenu = (
    path: string,
    x: number,
    y: number,
    trigger?: HTMLElement,
  ): boolean => {
    const current = latestTabContextStateRef.current;
    if (!current.openPaths.includes(path) || current.documents[path] === undefined) return false;
    tabContextMenuSequenceRef.current += 1;
    const nextSession = {
      sequence: tabContextMenuSequenceRef.current,
      path,
      x,
      y,
      trigger,
    };
    tabContextMenuRef.current = nextSession;
    setTabContextMenu(nextSession);
    return true;
  };

  /** 鼠右键只打开确实存在的文件标签菜单，并将原生浏览器菜单留给其它区域。 */
  const handleFileTabContextMenu = (event: ReactMouseEvent<HTMLDivElement>, path: string): void => {
    const eventTarget = event.target;
    const trigger = eventTarget instanceof HTMLElement ? eventTarget.closest("button") : null;
    if (requestFileTabContextMenu(path, event.clientX, event.clientY, trigger ?? undefined))
      event.preventDefault();
  };

  /** 标准菜单键和 Shift+F10 与鼠标共用定位、目标和焦点恢复路径。 */
  const handleFileTabKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>, path: string): void => {
    if (event.key !== "ContextMenu" && !(event.shiftKey && event.key === "F10")) return;
    const target = event.target instanceof HTMLElement ? event.target : event.currentTarget;
    const bounds = target.getBoundingClientRect();
    const trigger = target.closest("button");
    if (requestFileTabContextMenu(path, bounds.left, bounds.bottom, trigger ?? undefined)) {
      event.preventDefault();
      event.stopPropagation();
    }
  };

  /** 菜单动作先重查打开态、文档状态和写入门禁，再调用现有语义 action。 */
  const runFileTabContextAction = (path: string, action: FileTabContextAction): void => {
    const current = latestTabContextStateRef.current;
    const document = current.documents[path];
    if (document === undefined || !current.openPaths.includes(path) || current.lifecycleClosing)
      return;
    if (action === "close") {
      if (current.closeDocumentRequest === undefined) current.actions.closeDocument(path);
      return;
    }
    if (document.readOnly || current.mutationRecoveryRequired) return;
    if (action === "save" && document.status === "dirty") {
      void current.actions.saveDocument(path);
    } else if (action === "retry-save" && document.status === "saveError") {
      current.actions.retrySave(path);
    } else if (
      action === "save-as" &&
      document.status === "conflict" &&
      current.actions.beginSaveAs !== undefined &&
      current.conflictAction?.path !== path &&
      current.saveAsRequest?.pending !== true
    ) {
      current.actions.beginSaveAs(path);
    }
  };

  /** 只关闭对应 generation，旧菜单的延迟 close 不得关闭新右键目标。 */
  const closeFileTabContextMenu = (session: FileTabContextMenuSession): void => {
    if (tabContextMenuRef.current?.sequence !== session.sequence) return;
    tabContextMenuRef.current = undefined;
    setTabContextMenu(undefined);
  };

  /** 文件标签关闭或重载后清理悬空菜单；焦点退回仍存在的标签。 */
  useEffect(() => {
    if (tabContextMenu === undefined) return;
    const current = latestTabContextStateRef.current;
    if (current.openPaths.includes(tabContextMenu.path) && current.documents[tabContextMenu.path])
      return;
    if (tabContextMenuRef.current?.sequence !== tabContextMenu.sequence) return;
    window.requestAnimationFrame(() => {
      if (tabContextMenuRef.current?.sequence !== tabContextMenu.sequence) return;
      tabContextMenuRef.current = undefined;
      setTabContextMenu(undefined);
    });
  }, [documents, openPaths, tabContextMenu]);

  const tabContextDocument =
    tabContextMenu === undefined ? undefined : documents[tabContextMenu.path];
  const canShowTabContextMenu =
    tabContextMenu !== undefined &&
    tabContextDocument !== undefined &&
    openPaths.includes(tabContextMenu.path);

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
        <div className="ja-files-workspace-heading">
          <Files aria-hidden="true" />
          <span>文件</span>
        </div>
        <span className="ja-files-workspace-count">
          {fileCount > 0 ? `${fileCount} 个文件` : ""}
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
        <aside className="ja-files-workspace-explorer" aria-label="工作区资源管理器">
          <SearchPanel
            query={searchQuery}
            results={searchResults}
            summary={searchSummary}
            loading={searchLoading}
            error={searchError}
            onQueryChange={actions.changeSearchQuery}
            onOpenResult={actions.openSearchResult}
            onAddToConversation={
              onAddToConversation === undefined
                ? undefined
                : (result) => onAddToConversation({ path: result.path, kind: "file" })
            }
            idleContent={
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
                onAddToConversation={
                  onAddToConversation === undefined
                    ? undefined
                    : (node) => onAddToConversation({ path: node.path, kind: node.kind })
                }
                onNativeDropToken={actions.importDrop}
                openTargets={openTargets}
                onOpenTarget={actions.openTarget}
              />
            }
          />
        </aside>
        <div className="ja-files-workspace-editor">
          {openPaths.length === 0 ? (
            <EmptyState
              className="ja-files-workspace-empty"
              title="从右侧文件树选择文件开始编辑。"
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
                      data-external-file={openDocument.externalFile === true ? "true" : undefined}
                      key={path}
                      role="presentation"
                      onContextMenu={(event) => handleFileTabContextMenu(event, path)}
                      onKeyDown={(event) => handleFileTabKeyDown(event, path)}
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
                <div
                  className="ja-files-editor-content"
                  data-document-path={activeDocument.path}
                  data-document-read-only={activeDocument.readOnly}
                  data-document-truncated={activeDocument.truncated ?? false}
                  data-lifecycle-closing={lifecycleClosing}
                  data-mutation-recovery-required={mutationRecoveryRequired}
                >
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
      {canShowTabContextMenu && tabContextMenu !== undefined && tabContextDocument !== undefined ? (
        <PointerContextMenu
          key={tabContextMenu.sequence}
          x={tabContextMenu.x}
          y={tabContextMenu.y}
          label={`${entryName(tabContextMenu.path)} 标签操作`}
          className="ja-files-tab-context-menu"
          onOpenChange={(open) => {
            if (!open) closeFileTabContextMenu(tabContextMenu);
          }}
          onRestoreFocus={() => restoreFileTabFocus(tabContextMenu.path, tabContextMenu.trigger)}
        >
          {!tabContextDocument.readOnly &&
          (tabContextDocument.status === "dirty" || tabContextDocument.status === "saving") ? (
            <MenuItem
              disabled={
                tabContextDocument.status === "saving" ||
                lifecycleClosing ||
                mutationRecoveryRequired
              }
              onSelect={() => runFileTabContextAction(tabContextMenu.path, "save")}
            >
              <Save aria-hidden="true" />
              <span>{tabContextDocument.status === "saving" ? "保存中…" : "保存"}</span>
            </MenuItem>
          ) : null}
          {!tabContextDocument.readOnly && tabContextDocument.status === "saveError" ? (
            <MenuItem
              disabled={lifecycleClosing || mutationRecoveryRequired}
              onSelect={() => runFileTabContextAction(tabContextMenu.path, "retry-save")}
            >
              <Save aria-hidden="true" />
              <span>重试保存</span>
            </MenuItem>
          ) : null}
          {!tabContextDocument.readOnly &&
          tabContextDocument.status === "conflict" &&
          actions.beginSaveAs !== undefined ? (
            <MenuItem
              disabled={
                lifecycleClosing ||
                mutationRecoveryRequired ||
                saveAsRequest?.pending === true ||
                conflictAction?.path === tabContextMenu.path
              }
              onSelect={() => runFileTabContextAction(tabContextMenu.path, "save-as")}
            >
              <FilePlus2 aria-hidden="true" />
              <span>另存为</span>
            </MenuItem>
          ) : null}
          {tabContextDocument.status === "dirty" ||
          tabContextDocument.status === "saving" ||
          tabContextDocument.status === "saveError" ||
          (tabContextDocument.status === "conflict" && actions.beginSaveAs !== undefined) ? (
            <MenuSeparator />
          ) : null}
          <MenuItem
            disabled={lifecycleClosing || closeDocumentRequest !== undefined}
            onSelect={() => runFileTabContextAction(tabContextMenu.path, "close")}
          >
            <X aria-hidden="true" />
            <span>关闭</span>
          </MenuItem>
        </PointerContextMenu>
      ) : null}
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
        key={trashRequest?.requestId ?? "closed"}
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
