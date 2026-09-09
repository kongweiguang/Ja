// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { SaveTimerPort } from "./FilesSaveCoordinator";
import type { FileReadDto, FilesOpenTarget, FilesWorkspaceOperations } from "./ports";
import type { FileEncoding, FileRevision, NewlineStyle, WorkspaceFileNode } from "../domain/types";

type DocumentStatus = "clean" | "dirty" | "saving" | "saveError" | "conflict";
export type TrashDialogPhase = "preparing" | "ready" | "committing" | "error";

/** 编辑文档是 controller 对 native 读取与本地草稿的唯一投影，UI 不复制保存事实。 */
export interface OpenDocument {
  path: string;
  content: string;
  savedContent: string;
  revision: FileRevision;
  encoding: FileEncoding;
  newline: NewlineStyle;
  kind: FileReadDto["kind"];
  readOnly: boolean;
  readOnlyReason?: string;
  status: DocumentStatus;
  error?: string;
  externalContent?: string;
  externalRevision?: FileRevision;
  draftGeneration: number;
  lastMutationId?: string;
  reveal?: { line: number; column?: number };
}

/** Save As 请求只保存相对路径和交互状态，native mutation 仍由 controller 持有。 */
export interface SaveAsRequest {
  sourcePath: string;
  targetPath: string;
  pending: boolean;
  error?: string;
}

/** Trash 视图只接收摘要；operation token 被隔离在 controller 私有状态。 */
export interface TrashRequest {
  workspaceId: string;
  requestId: number;
  relativePath: string;
  phase: TrashDialogPhase;
  fileCount?: number;
  totalBytes?: number;
  error?: string;
}

/** 关闭确认只携带文档身份，具体草稿状态从同一 view model 读取。 */
export interface CloseDocumentRequest {
  path: string;
}

/** 工作区切换成功前持有编辑冻结；调用方失败时必须 release，让旧 UI 可继续恢复编辑。 */
export interface FilesWorkspaceCloseLease {
  release: () => void;
}

/** 只暴露切换所需的 flush fence，不把编辑 buffer 或保存内部状态提升到 App。 */
export interface FilesWorkspaceLifecycle {
  workspaceId: string;
  flushForWorkspaceChange: () => Promise<FilesWorkspaceCloseLease>;
}

/** Public feature composition 只接收 typed operations，绝不在 Files 内创建 Tauri adapter。 */
export interface FilesWorkspaceProps {
  workspaceId: string;
  operations: FilesWorkspaceOperations;
  /** 隐藏面板仍保留草稿与 lifecycle owner，但不启动 Tree、Watcher 或窗口订阅等原生 IO。 */
  activityEnabled?: boolean;
  initialNodes?: readonly WorkspaceFileNode[];
  onNotice?: (message: string) => void;
  onRegisterLifecycle?: (lifecycle: FilesWorkspaceLifecycle | undefined) => void;
}

/** 浏览器能力作为窄端口注入，使 application controller 不直接依赖 DOM 或全局计时器。 */
export interface FilesControllerPorts {
  timer: SaveTimerPort;
  resolveNativeDropTarget: (
    x: number,
    y: number,
    nodes: readonly WorkspaceFileNode[],
  ) => string | undefined;
  subscribeBrowserReconciliation: (listener: () => void) => () => void;
}

/** controller 合并业务输入与浏览器窄端口，便于 composition 和测试显式完成依赖注入。 */
export interface FilesControllerProps extends FilesWorkspaceProps, FilesControllerPorts {}

/** Search 结果是 application 面向 UI 的稳定投影，不暴露 adapter DTO。 */
export interface FilesSearchResult {
  id: string;
  path: string;
  line: number;
  column?: number;
  preview: string;
  matchStart?: number;
  matchLength?: number;
}

/** 搜索摘要公开扫描边界而不泄露 Runtime 细节，避免用户把截断结果误认为完整事实。 */
export interface FilesSearchSummary {
  truncated: boolean;
  scannedEntries: number;
  skippedFiles: number;
}

/** 唯一 controller 发布的只读视图模型；UI 不再持有并行领域状态。 */
export interface FilesViewModel {
  nodes: readonly WorkspaceFileNode[];
  selectedPath?: string;
  treeLoading: boolean;
  treeError?: string;
  searchQuery: string;
  searchResults: readonly FilesSearchResult[];
  searchSummary?: FilesSearchSummary;
  searchLoading: boolean;
  searchError?: string;
  documents: Readonly<Record<string, OpenDocument>>;
  openPaths: readonly string[];
  activePath?: string;
  activeDocument?: OpenDocument;
  comparePath?: string;
  conflictAction?: { path: string; kind: "compare" | "reload" };
  saveAsRequest?: SaveAsRequest;
  trashRequest?: TrashRequest;
  closeDocumentRequest?: CloseDocumentRequest;
  closeRequestedDocument?: OpenDocument;
  lifecycleClosing: boolean;
  mutationRecoveryRequired: boolean;
  openTargets: readonly FilesOpenTarget[];
}

/**
 * UI 只能发出语义化 intent；保存、Watcher、Move、Trash 与 Drop 的竞态和事务仍由
 * 同一个 controller 线性化，不能在组件内新增 mutation owner。
 */
export interface FilesActions {
  selectNode: (node: WorkspaceFileNode) => void;
  toggleDirectory: (node: WorkspaceFileNode) => void;
  retryTree: () => void;
  createFile?: (parent: string, name: string) => void;
  createDirectory?: (parent: string, name: string) => void;
  rename?: (node: WorkspaceFileNode, name: string) => void;
  move?: (node: WorkspaceFileNode, targetDirectory: string) => void;
  trash?: (node: WorkspaceFileNode) => void;
  refreshTree: (path?: string) => void;
  importDrop?: (dropToken: string, targetDirectory: string) => void;
  openTarget?: (target: FilesOpenTarget["target"], relativePath: string) => void;
  changeSearchQuery: (query: string) => void;
  openSearchResult: (result: FilesSearchResult) => void;
  selectDocument: (path: string) => void;
  closeDocument: (path: string) => void;
  compareConflict: (path: string) => void;
  reloadConflict: (path: string) => void;
  beginSaveAs?: (path: string) => void;
  retrySave: (path: string) => void;
  editDocument: (path: string, content: string) => void;
  saveDocument: (path: string) => Promise<void>;
  hideComparison: () => void;
  changeSaveAsTarget: (targetPath: string) => void;
  cancelSaveAs: () => void;
  submitSaveAs: () => Promise<void>;
  dismissCloseDocument: () => void;
  discardCloseDocument: () => void;
  cancelTrash: () => void;
  confirmTrash: () => Promise<void>;
}

/** controller 只有这一组输出契约，禁止另建并行 store 或第二组 actions。 */
export interface FilesController {
  viewModel: FilesViewModel;
  actions: FilesActions;
}
