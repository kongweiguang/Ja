// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  FileContentKind,
  FileEncoding,
  FileRevision,
  NewlineStyle,
  WorkspaceEntryKind,
} from "../domain/types";

/** application 只接收类型化元数据，不依赖 Tauri adapter 或 wire schema 实现。 */
interface FileMetadataDto {
  revision: FileRevision;
  size?: number;
  encoding?: FileEncoding | null;
  newline?: NewlineStyle;
}

/** 有界读取结果保留 native 的只读原因，controller 只做脱敏视图投影。 */
export interface FileReadDto extends FileMetadataDto {
  path: string;
  kind: FileContentKind;
  content: string | null;
  readOnlyReason?: string;
}

/** 保存端口必须携带完整 CAS、mutation id、编码与换行，禁止宽泛覆盖写。 */
interface FileSaveInput {
  workspaceId: string;
  relativePath: string;
  expectedRevision: FileRevision;
  mutationId: string;
  content: string;
  encoding: FileEncoding;
  newline: NewlineStyle;
}

/** 保存 ACK 同时返回 revision 与 mutation id，供 Watcher 回声对账。 */
export interface FileSaveResult {
  revision: FileRevision;
  mutationId: string;
}

/** Create 只允许当前工作区内的文件或目录，初始正文同样保留编码语义。 */
interface CreateEntryInput {
  workspaceId: string;
  relativePath: string;
  expectedRevision: FileRevision | null;
  mutationId: string;
  kind: "file" | "directory";
  initialContent?: {
    content: string;
    encoding: FileEncoding;
    newline: NewlineStyle;
  };
}

/** Move 端口只接受相对目标目录，native 继续拥有 containment 与冲突校验。 */
interface MoveEntryInput {
  workspaceId: string;
  relativePath: string;
  expectedRevision: FileRevision;
  mutationId: string;
  targetDirectory: string;
  newName?: string;
}

/** Trash prepare 只创建短寿命计划，不产生删除副作用。 */
interface TrashPrepareInput {
  workspaceId: string;
  relativePath: string;
  expectedRevision: FileRevision;
  mutationId: string;
}

/** operation token 只在 application 内流转，UI 只能看到脱敏摘要。 */
export interface TrashPrepareResult {
  operationToken: string;
  fileCount: number;
  totalBytes: number;
  expiresAtUnixMillis: number;
}

/** Trash commit 必须复用 prepare 的身份和 CAS，不允许 UI 自行构造第二次计划。 */
interface TrashCommitInput {
  workspaceId: string;
  relativePath: string;
  expectedRevision: FileRevision;
  mutationId: string;
  operationToken: string;
}

/** Native Drop 只携带 opaque token，绝不把 OS 绝对路径暴露给 WebView。 */
interface DropImportInput {
  workspaceId: string;
  targetDirectory: string;
  dropToken: string;
  expectedRevision: FileRevision;
  mutationId: string;
}

/** Tree DTO 保留 runtime 权威相对路径，application 负责映射成领域节点。 */
export interface WorkspaceTreeEntryDto {
  name: string;
  relativePath: string;
  kind: WorkspaceEntryKind;
  hasChildren?: boolean;
  revision?: FileRevision;
  size?: number;
}

/** 分页目录快照必须共享 snapshot token 和目录 revision，防止跨快照拼页。 */
export interface WorkspaceTreePageDto {
  entries: readonly WorkspaceTreeEntryDto[];
  directoryRevision: FileRevision;
  nextCursor?: string | null;
  snapshotToken?: string;
}

/** Search 端口始终绑定当前 workspace 与相对根目录。 */
interface SearchFilesInput {
  workspaceId: string;
  relativePath: string;
  query: string;
}

/** 搜索命中只包含定位和有界预览，不把原生扫描对象扩散到 UI。 */
export interface FileSearchHit {
  id: string;
  path: string;
  line: number;
  column?: number;
  preview: string;
  matchStart?: number;
  matchLength?: number;
}

/** 搜索端口保留原生预算事实，UI 不得把有界扫描结果包装成完整索引命中。 */
interface FileSearchResultDto {
  hits: readonly FileSearchHit[];
  truncated: boolean;
  scannedEntries: number;
  skippedFiles: number;
}

/** 外部打开目标来自 Rust 固定闭集；feature 只接收当前机器真实可用项。 */
export interface FilesOpenTarget {
  target:
    | "vscode"
    | "visual_studio"
    | "zed"
    | "file_explorer"
    | "terminal"
    | "git_bash"
    | "wsl"
    | "pycharm"
    | "webstorm";
  displayName: string;
}

/** Watcher 事件只是对账 hint，controller 必须再读取权威 tree/content。 */
export interface WorkspaceChangedEvent {
  relativePath: string;
  generation: number;
  revision: FileRevision | null;
  requiresRescan: boolean;
}

/** Native 拖放只传 opaque token 与 WebView 逻辑坐标，不暴露 OS 路径。 */
export interface WorkspaceNativeDropEvent {
  dropToken: string;
  x: number;
  y: number;
}

/** Watcher 生命周期严格绑定 workspace identity。 */
interface WatchStartInput {
  workspaceId: string;
}

/** Subscription 只暴露幂等停止动作，不泄露底层 listener handle。 */
export interface WatchSubscription {
  stop: () => Promise<void>;
}

/**
 * Files 唯一 native port 集合；所有实现由 App composition 注入，controller 不直接创建
 * Tauri adapter，也不允许 UI 持有 mutation token 或 Watcher 生命周期。
 */
export interface FilesWorkspaceOperations {
  tree: (input: {
    workspaceId: string;
    relativePath: string;
    cursor?: string;
    snapshotToken?: string;
  }) => Promise<WorkspaceTreePageDto>;
  readFile: (input: { workspaceId: string; relativePath: string }) => Promise<FileReadDto>;
  saveFile: (input: FileSaveInput) => Promise<FileSaveResult>;
  createEntry?: (input: CreateEntryInput) => Promise<{ revision: FileRevision }>;
  moveEntry?: (input: MoveEntryInput) => Promise<{ revision: FileRevision }>;
  trashPrepare?: (input: TrashPrepareInput) => Promise<TrashPrepareResult>;
  trashCommit?: (input: TrashCommitInput) => Promise<{ revision: FileRevision | null }>;
  importDrop?: (input: DropImportInput) => Promise<void>;
  search?: (input: SearchFilesInput) => Promise<FileSearchResultDto>;
  openTargets?: (input: { workspaceId: string }) => Promise<readonly FilesOpenTarget[]>;
  openTarget?: (input: {
    workspaceId: string;
    target: FilesOpenTarget["target"];
    relativePath: string;
  }) => Promise<void>;
  watchStart?: (
    input: WatchStartInput,
    listener: (event: WorkspaceChangedEvent) => void,
  ) => Promise<WatchSubscription>;
  subscribeWindowFocus?: (
    listener: (focused: boolean) => void,
  ) => Promise<() => void | Promise<void>>;
  subscribeNativeDrop?: (
    listener: (event: WorkspaceNativeDropEvent) => void,
  ) => Promise<() => void | Promise<void>>;
  watchRescan?: (input: WatchStartInput) => Promise<void>;
  watchStop?: (input: WatchStartInput) => Promise<void>;
}
