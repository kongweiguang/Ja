// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

import { z } from "zod";
import {
  defaultNativeBridge,
  normalizeRuntimeError,
  RuntimeHostError,
  type RuntimeNativeBridge,
} from "./runtime";

/**
 * 前端路径相对于原生 workspace binding；空字符串是唯一例外，Rust 用它表示
 * tree/search 的 workspace root，而文件读取与 Git 仍要求具体条目。
 */
const WorkspaceRelativePathSchema = z
  .string()
  .max(4096)
  .refine(
    (value) => value === "" || isSafeWorkspaceRelativePath(value),
    "relative path is invalid",
  );

/**
 * 镜像原生路径语法：只准入空 root 或普通斜杠组件；drive prefix、traversal、
 * dot component 与 Windows separator 必须在 IPC 前拒绝。
 */
function isSafeWorkspaceRelativePath(value: string): boolean {
  if (value.includes("\u0000") || value.includes("\\") || value.includes(":")) {
    return false;
  }
  if (value.startsWith("/")) {
    return false;
  }
  return value.split("/").every((component) => component !== "." && component !== "..");
}

/** 文件读取与 Git pathspec 不能直接指向 workspace root。 */
export const WorkspaceNonEmptyRelativePathSchema = WorkspaceRelativePathSchema.refine(
  (value) => value.length > 0,
  "relative path is required",
);

const WorkspaceIdWireSchema = z
  .string()
  .regex(/^ws_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(99);

const WorkspaceTreeInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    relativePath: WorkspaceRelativePathSchema,
    cursor: z.string().max(256).optional(),
    pageSize: z.number().int().min(1).max(10_000).optional(),
    snapshotToken: z.string().max(128).optional(),
  })
  .strict();

const WorkspaceReadFileInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    relativePath: WorkspaceNonEmptyRelativePathSchema,
  })
  .strict();

const WorkspaceSearchInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    relativePath: WorkspaceRelativePathSchema,
    query: z
      .string()
      .min(1)
      .max(8192)
      .refine(
        (value) => !value.includes("\u0000") && !value.includes("\r") && !value.includes("\n"),
        "search query contains control characters",
      ),
  })
  .strict();

/** 封闭 target id 防止 renderer 把 open-with 扩张为通用 RPC。 */
const WorkspaceOpenTargetSchema = z.enum([
  "vscode",
  "visual_studio",
  "zed",
  "file_explorer",
  "terminal",
  "git_bash",
  "wsl",
  "pycharm",
  "webstorm",
]);
const WorkspaceOpenUnavailableReasonSchema = z.enum(["not_installed", "unsupported_platform"]);
const WorkspaceOpenTargetsInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
  })
  .strict();
const WorkspaceOpenInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    target: WorkspaceOpenTargetSchema,
    relativePath: WorkspaceRelativePathSchema.optional(),
  })
  .strict();
const WorkspaceOpenTargetSchemaDto = z
  .object({
    target: WorkspaceOpenTargetSchema,
    displayName: z.string().min(1).max(64),
    available: z.boolean(),
    reason: WorkspaceOpenUnavailableReasonSchema.nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.available && value.reason !== null) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        message: "available target cannot have an unavailable reason",
      });
    }
    if (!value.available && value.reason === null) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        message: "unavailable target requires a stable reason",
      });
    }
  });
const WorkspaceOpenTargetsSchema = z
  .object({
    targets: z.array(WorkspaceOpenTargetSchemaDto).length(9),
  })
  .strict();
const WorkspaceOpenResultSchema = z
  .object({
    opened: z.literal(true),
    target: WorkspaceOpenTargetSchema,
    relativePath: WorkspaceRelativePathSchema,
    entryKind: z.enum(["file", "directory"]),
  })
  .strict();

const EntryKindSchema = z.enum(["file", "directory", "symlink", "reparse_point", "other"]);
const ContentKindSchema = z.enum(["text", "binary", "unknown_encoding", "too_large"]);
const TextEncodingSchema = z.enum(["utf8", "utf8_bom", "utf16_le", "utf16_be"]);
const LineEndingSchema = z.enum(["lf", "crlf", "cr", "mixed"]);
const MAX_WORKSPACE_TEXT_LENGTH = 4 * 1024 * 1024;
const FileRevisionSchema = z
  .object({
    kind: EntryKindSchema,
    size: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    modifiedUnixMillis: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).nullable(),
    sha256: z.string().max(128).nullable(),
  })
  .strict();
const MutationIdSchema = z
  .string()
  .min(1)
  .max(128)
  .refine(
    (value) => ![...value].some((character) => /\p{Cc}/u.test(character)),
    "mutation id contains control characters",
  );
const WorkspaceTextContentSchema = z
  .object({
    text: z.string().max(MAX_WORKSPACE_TEXT_LENGTH),
    encoding: TextEncodingSchema,
    lineEnding: LineEndingSchema,
  })
  .strict();
const FileMetadataSchema = z
  .object({
    kind: EntryKindSchema,
    size: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    modifiedUnixMillis: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).nullable(),
    revision: FileRevisionSchema,
  })
  .strict();
const TreeEntrySchema = z
  .object({
    name: z.string().min(1).max(4096),
    relativePath: WorkspaceRelativePathSchema,
    metadata: FileMetadataSchema,
    canExpand: z.boolean(),
  })
  .strict();
const WorkspaceTreePageSchema = z
  .object({
    entries: z.array(TreeEntrySchema).max(10_000),
    directoryRevision: FileRevisionSchema,
    nextCursor: z.string().max(256).nullable(),
    snapshotToken: z.string().min(1).max(128),
    totalEntries: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    depth: z.number().int().min(0).max(256),
  })
  .strict();
const WorkspaceFileContentSchema = z
  .object({
    metadata: FileMetadataSchema,
    kind: ContentKindSchema,
    encoding: TextEncodingSchema.nullable(),
    lineEnding: LineEndingSchema.nullable().optional(),
    text: z.string().max(MAX_WORKSPACE_TEXT_LENGTH).nullable(),
    bytesRead: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    truncated: z.boolean(),
  })
  .strict();

const WorkspaceCreateEntryInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    relativePath: WorkspaceNonEmptyRelativePathSchema,
    kind: z.enum(["file", "directory"]),
    expectedRevision: FileRevisionSchema.nullable(),
    mutationId: MutationIdSchema,
    content: WorkspaceTextContentSchema.optional(),
  })
  .strict();
const WorkspaceFileSaveInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    relativePath: WorkspaceNonEmptyRelativePathSchema,
    expectedRevision: FileRevisionSchema,
    mutationId: MutationIdSchema,
    text: z.string().max(MAX_WORKSPACE_TEXT_LENGTH),
    encoding: TextEncodingSchema,
    lineEnding: LineEndingSchema,
  })
  .strict();
const WorkspaceMoveEntryInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    fromRelativePath: WorkspaceNonEmptyRelativePathSchema,
    toRelativePath: WorkspaceNonEmptyRelativePathSchema,
    expectedRevision: FileRevisionSchema,
    mutationId: MutationIdSchema,
  })
  .strict();
const WorkspaceTrashPrepareInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    relativePath: WorkspaceNonEmptyRelativePathSchema,
    expectedRevision: FileRevisionSchema,
    mutationId: MutationIdSchema,
  })
  .strict();
const WorkspaceTrashCommitInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    relativePath: WorkspaceNonEmptyRelativePathSchema,
    expectedRevision: FileRevisionSchema,
    operationToken: z.string().min(1).max(128),
    mutationId: MutationIdSchema,
  })
  .strict();
const WorkspaceDropImportInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    destinationRelativePath: WorkspaceRelativePathSchema,
    expectedRevision: FileRevisionSchema,
    dropToken: z.string().min(1).max(128),
    mutationId: MutationIdSchema,
  })
  .strict();
const WorkspaceWatchStartInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    generation: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();
const WorkspaceWatchStopInputSchema = z
  .object({
    workspaceId: WorkspaceIdWireSchema,
    generation: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();
const WorkspaceWatchRescanInputSchema = WorkspaceWatchStartInputSchema;
const WorkspaceCreateEntryResultSchema = z
  .object({
    relativePath: WorkspaceNonEmptyRelativePathSchema,
    kind: EntryKindSchema,
    revision: FileRevisionSchema,
  })
  .strict();
const WorkspaceFileSaveResultSchema = z
  .object({
    relativePath: WorkspaceNonEmptyRelativePathSchema,
    revision: FileRevisionSchema,
  })
  .strict();
const WorkspaceMoveEntryResultSchema = z
  .object({
    fromRelativePath: WorkspaceNonEmptyRelativePathSchema,
    toRelativePath: WorkspaceNonEmptyRelativePathSchema,
    revision: FileRevisionSchema,
  })
  .strict();
const WorkspaceTrashPrepareResultSchema = z
  .object({
    operationToken: z.string().min(1).max(128),
    fileCount: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    totalBytes: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    expiresAtUnixMillis: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();
const WorkspaceTrashCommitResultSchema = z
  .object({ committed: z.literal(true), revision: FileRevisionSchema.nullable() })
  .strict();
const WorkspaceDropImportResultSchema = z
  .object({
    importedRelativePaths: z.array(WorkspaceNonEmptyRelativePathSchema).max(100_000),
  })
  .strict();
const WorkspaceWatchStartResultSchema = z
  .object({
    started: z.boolean(),
    generation: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();
const WorkspaceWatchStopResultSchema = z.object({ stopped: z.boolean() }).strict();
const WorkspaceWatchRescanResultSchema = z
  .object({
    generation: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER),
    requiresRescan: z.boolean(),
    emittedPaths: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();
const WorkspaceChangedEventSchema = z
  .object({
    relativePath: WorkspaceRelativePathSchema,
    generation: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER),
    revision: FileRevisionSchema.nullable(),
    requiresRescan: z.boolean(),
  })
  .strict();
/** 原生窗口 drag/drop 只携带一次性 token 与 screen point，不传路径。 */
const WorkspaceNativeDropEventSchema = z
  .object({
    dropToken: z.string().min(1).max(128),
    x: z.number().finite().min(-1e9).max(1e9),
    y: z.number().finite().min(-1e9).max(1e9),
  })
  .strict();
const WorkspaceSearchHitSchema = z
  .object({
    relativePath: WorkspaceRelativePathSchema,
    line: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    column: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    snippet: z.string().max(16 * 1024),
    encoding: TextEncodingSchema,
  })
  .strict();
const WorkspaceSearchResultSchema = z
  .object({
    hits: z.array(WorkspaceSearchHitSchema).max(100_000),
    truncated: z.boolean(),
    scannedEntries: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    skippedFiles: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();

export type WorkspaceTreeInput = z.infer<typeof WorkspaceTreeInputSchema>;
export type WorkspaceReadFileInput = z.infer<typeof WorkspaceReadFileInputSchema>;
export type WorkspaceSearchInput = z.infer<typeof WorkspaceSearchInputSchema>;
export type WorkspaceOpenTargetsInput = z.infer<typeof WorkspaceOpenTargetsInputSchema>;
export type WorkspaceOpenInput = z.infer<typeof WorkspaceOpenInputSchema>;
export type WorkspaceOpenTargets = z.infer<typeof WorkspaceOpenTargetsSchema>;
export type WorkspaceOpenResult = z.infer<typeof WorkspaceOpenResultSchema>;
export type WorkspaceFileRevision = z.infer<typeof FileRevisionSchema>;
export type WorkspaceTreePage = z.infer<typeof WorkspaceTreePageSchema>;
export type WorkspaceFileContent = z.infer<typeof WorkspaceFileContentSchema>;
export type WorkspaceSearchResult = z.infer<typeof WorkspaceSearchResultSchema>;
export type WorkspaceCreateEntryInput = z.infer<typeof WorkspaceCreateEntryInputSchema>;
export type WorkspaceFileSaveInput = z.infer<typeof WorkspaceFileSaveInputSchema>;
export type WorkspaceMoveEntryInput = z.infer<typeof WorkspaceMoveEntryInputSchema>;
export type WorkspaceTrashPrepareInput = z.infer<typeof WorkspaceTrashPrepareInputSchema>;
export type WorkspaceTrashCommitInput = z.infer<typeof WorkspaceTrashCommitInputSchema>;
export type WorkspaceDropImportInput = z.infer<typeof WorkspaceDropImportInputSchema>;
export type WorkspaceWatchStartInput = z.infer<typeof WorkspaceWatchStartInputSchema>;
export type WorkspaceWatchStopInput = z.infer<typeof WorkspaceWatchStopInputSchema>;
export type WorkspaceWatchRescanInput = z.infer<typeof WorkspaceWatchRescanInputSchema>;
export type WorkspaceCreateEntryResult = z.infer<typeof WorkspaceCreateEntryResultSchema>;
export type WorkspaceFileSaveResult = z.infer<typeof WorkspaceFileSaveResultSchema>;
export type WorkspaceMoveEntryResult = z.infer<typeof WorkspaceMoveEntryResultSchema>;
export type WorkspaceTrashPrepareResult = z.infer<typeof WorkspaceTrashPrepareResultSchema>;
export type WorkspaceTrashCommitResult = z.infer<typeof WorkspaceTrashCommitResultSchema>;
export type WorkspaceDropImportResult = z.infer<typeof WorkspaceDropImportResultSchema>;
export type WorkspaceWatchStartResult = z.infer<typeof WorkspaceWatchStartResultSchema>;
export type WorkspaceWatchStopResult = z.infer<typeof WorkspaceWatchStopResultSchema>;
export type WorkspaceWatchRescanResult = z.infer<typeof WorkspaceWatchRescanResultSchema>;
export type WorkspaceChangedEvent = z.infer<typeof WorkspaceChangedEventSchema>;
export type WorkspaceChangedListener = (event: WorkspaceChangedEvent) => void;
export type WorkspaceNativeDropEvent = z.infer<typeof WorkspaceNativeDropEventSchema>;
export type WorkspaceNativeDropListener = (event: WorkspaceNativeDropEvent) => void;
export type WorkspaceUnsubscribe = () => void | Promise<void>;

/** Tauri command 名称保持封闭，调用方不能执行通用 RPC。 */
export const JA_WORKSPACE_COMMANDS = {
  tree: "ja_workspace_tree",
  readFile: "ja_workspace_read_file",
  search: "ja_workspace_search",
  openTargets: "ja_workspace_open_targets",
  open: "ja_workspace_open",
  createEntry: "ja_workspace_create_entry",
  saveFile: "ja_workspace_save_file",
  moveEntry: "ja_workspace_move_entry",
  trashPrepare: "ja_workspace_trash_prepare",
  trashCommit: "ja_workspace_trash_commit",
  watchStart: "ja_workspace_watch_start",
  watchRescan: "ja_workspace_watch_rescan",
  watchStop: "ja_workspace_watch_stop",
  importDrop: "ja_workspace_import_drop",
} as const;

/** 固定 workspace event 名称，防止 renderer 订阅任意 channel。 */
export const JA_WORKSPACE_EVENTS = {
  changed: "ja://workspace-changed",
  nativeDrop: "ja://workspace-native-drop",
} as const;

/** Workspace adapter 必须同时获得 command 与 event 能力，禁止用不完整 bridge 制造运行时分支。 */
export type WorkspaceNativeBridge = Pick<RuntimeNativeBridge, "invoke" | "listen">;

/**
 * 将畸形 UI 输入转换为与 Rust 相同的稳定原生 error code；刻意不返回 Zod
 * field path，因为其中可能包含用户提供的路径文本。
 */
function parseInput<T>(schema: z.ZodType<T>, input: unknown): T {
  try {
    return schema.parse(input);
  } catch {
    throw new RuntimeHostError("INVALID_INPUT", "请求参数无效", false);
  }
}

/**
 * 调用固定 workspace command 并校验完整 camelCase DTO；原生路径与子进程诊断
 * 只留在 Rust 稳定 error code 内部。
 */
async function invokeWorkspace<T>(
  bridge: WorkspaceNativeBridge,
  command: string,
  input: unknown,
  inputSchema: z.ZodType<unknown>,
  resultSchema: z.ZodType<T>,
): Promise<T> {
  const parsedInput = parseInput(inputSchema, input);
  try {
    const result = await bridge.invoke<unknown>(command, { input: parsedInput });
    return resultSchema.parse(result);
  } catch (error) {
    if (error instanceof RuntimeHostError) {
      throw normalizeRuntimeError(error);
    }
    if (error instanceof z.ZodError) {
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时暂不可用", true);
    }
    throw normalizeRuntimeError(error);
  }
}

interface WorkspaceHostAdapter {
  tree(input: WorkspaceTreeInput): Promise<WorkspaceTreePage>;
  readFile(input: WorkspaceReadFileInput): Promise<WorkspaceFileContent>;
  search(input: WorkspaceSearchInput): Promise<WorkspaceSearchResult>;
}

/** 在只读文件能力之上增加受控 external opener，不向 feature 暴露原生 launcher。 */
interface WorkspaceOpenHostAdapter extends WorkspaceHostAdapter {
  openTargets(input: WorkspaceOpenTargetsInput): Promise<WorkspaceOpenTargets>;
  open(input: WorkspaceOpenInput): Promise<WorkspaceOpenResult>;
}

/** Files/editor 与 watcher host 使用的完整 typed workspace surface。 */
export interface WorkspaceMutationHostAdapter extends WorkspaceOpenHostAdapter {
  createEntry(input: WorkspaceCreateEntryInput): Promise<WorkspaceCreateEntryResult>;
  saveFile(input: WorkspaceFileSaveInput): Promise<WorkspaceFileSaveResult>;
  moveEntry(input: WorkspaceMoveEntryInput): Promise<WorkspaceMoveEntryResult>;
  trashPrepare(input: WorkspaceTrashPrepareInput): Promise<WorkspaceTrashPrepareResult>;
  trashCommit(input: WorkspaceTrashCommitInput): Promise<WorkspaceTrashCommitResult>;
  watchStart(input: WorkspaceWatchStartInput): Promise<WorkspaceWatchStartResult>;
  watchRescan(input: WorkspaceWatchRescanInput): Promise<WorkspaceWatchRescanResult>;
  watchStop(input: WorkspaceWatchStopInput): Promise<WorkspaceWatchStopResult>;
  importDrop(input: WorkspaceDropImportInput): Promise<WorkspaceDropImportResult>;
  subscribeChanged(listener: WorkspaceChangedListener): Promise<WorkspaceUnsubscribe>;
  subscribeNativeDrop(listener: WorkspaceNativeDropListener): Promise<WorkspaceUnsubscribe>;
}

/**
 * typed workspace bridge 只校验 wire 边界；canonical path containment 与
 * 有界 IO 仍由 Rust 拥有。
 */
export class TauriWorkspaceHostAdapter implements WorkspaceMutationHostAdapter {
  constructor(private readonly bridge: WorkspaceNativeBridge = defaultNativeBridge) {}

  /** 为虚拟化文件树读取一个有界目录页。 */
  async tree(input: WorkspaceTreeInput): Promise<WorkspaceTreePage> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.tree,
      input,
      WorkspaceTreeInputSchema,
      WorkspaceTreePageSchema,
    );
  }

  /** 读取一个有界文件投影，不暴露绝对路径。 */
  async readFile(input: WorkspaceReadFileInput): Promise<WorkspaceFileContent> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.readFile,
      input,
      WorkspaceReadFileInputSchema,
      WorkspaceFileContentSchema,
    );
  }

  /** 通过 Rust 有界 workspace reader 搜索字面文本。 */
  async search(input: WorkspaceSearchInput): Promise<WorkspaceSearchResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.search,
      input,
      WorkspaceSearchInputSchema,
      WorkspaceSearchResultSchema,
    );
  }

  /** 查询原生 discovery，但不向 renderer 暴露 executable 路径。 */
  async openTargets(input: WorkspaceOpenTargetsInput): Promise<WorkspaceOpenTargets> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.openTargets,
      input,
      WorkspaceOpenTargetsInputSchema,
      WorkspaceOpenTargetsSchema,
    );
  }

  /** 只通过封闭 enum 打开已验证的 workspace-relative target。 */
  async open(input: WorkspaceOpenInput): Promise<WorkspaceOpenResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.open,
      input,
      WorkspaceOpenInputSchema,
      WorkspaceOpenResultSchema,
    );
  }

  /** 通过 Rust workspace owner 创建一个有界文件或目录。 */
  async createEntry(input: WorkspaceCreateEntryInput): Promise<WorkspaceCreateEntryResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.createEntry,
      input,
      WorkspaceCreateEntryInputSchema,
      WorkspaceCreateEntryResultSchema,
    );
  }

  /** 使用显式 expected-revision CAS 保存 editor buffer。 */
  async saveFile(input: WorkspaceFileSaveInput): Promise<WorkspaceFileSaveResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.saveFile,
      input,
      WorkspaceFileSaveInputSchema,
      WorkspaceFileSaveResultSchema,
    );
  }

  /** 移动条目时不允许覆盖目标，冲突必须显式返回。 */
  async moveEntry(input: WorkspaceMoveEntryInput): Promise<WorkspaceMoveEntryResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.moveEntry,
      input,
      WorkspaceMoveEntryInputSchema,
      WorkspaceMoveEntryResultSchema,
    );
  }

  /** 准备有界 system-trash 操作，提交前不产生不可恢复副作用。 */
  async trashPrepare(input: WorkspaceTrashPrepareInput): Promise<WorkspaceTrashPrepareResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.trashPrepare,
      input,
      WorkspaceTrashPrepareInputSchema,
      WorkspaceTrashPrepareResultSchema,
    );
  }

  /** 提交短生命周期 system-trash operation token。 */
  async trashCommit(input: WorkspaceTrashCommitInput): Promise<WorkspaceTrashCommitResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.trashCommit,
      input,
      WorkspaceTrashCommitInputSchema,
      WorkspaceTrashCommitResultSchema,
    );
  }

  /** 启动原生 watcher；event listener 仍是独立 typed 边界。 */
  async watchStart(input: WorkspaceWatchStartInput): Promise<WorkspaceWatchStartResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.watchStart,
      input,
      WorkspaceWatchStartInputSchema,
      WorkspaceWatchStartResultSchema,
    );
  }

  /** 请求 overflow/focus 后使用的权威轮询 reconciliation。 */
  async watchRescan(input: WorkspaceWatchRescanInput): Promise<WorkspaceWatchRescanResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.watchRescan,
      input,
      WorkspaceWatchRescanInputSchema,
      WorkspaceWatchRescanResultSchema,
    );
  }

  /** 停止并 join 活动 workspace 的原生 watcher，避免后台资源跨域残留。 */
  async watchStop(input: WorkspaceWatchStopInput): Promise<WorkspaceWatchStopResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.watchStop,
      input,
      WorkspaceWatchStopInputSchema,
      WorkspaceWatchStopResultSchema,
    );
  }

  /** 导入单个原生 OS drop token，但不暴露其绝对路径。 */
  async importDrop(input: WorkspaceDropImportInput): Promise<WorkspaceDropImportResult> {
    return invokeWorkspace(
      this.bridge,
      JA_WORKSPACE_COMMANDS.importDrop,
      input,
      WorkspaceDropImportInputSchema,
      WorkspaceDropImportResultSchema,
    );
  }

  /** 订阅固定 Rust watcher 事件，并丢弃畸形 payload。 */
  async subscribeChanged(listener: WorkspaceChangedListener): Promise<WorkspaceUnsubscribe> {
    try {
      return await this.bridge.listen<unknown>(JA_WORKSPACE_EVENTS.changed, (payload) => {
        try {
          listener(parseWorkspaceChangedEvent(payload));
        } catch {
          // 原生事件 payload 在 WebView 边界不可信；非法事件必须丢弃，防止绝对或私有字段泄漏。
        }
      });
    } catch (error) {
      throw normalizeRuntimeError(error);
    }
  }

  /** 订阅固定原生 drag/drop 事件，但不转发路径。 */
  async subscribeNativeDrop(listener: WorkspaceNativeDropListener): Promise<WorkspaceUnsubscribe> {
    try {
      return await this.bridge.listen<unknown>(JA_WORKSPACE_EVENTS.nativeDrop, (payload) => {
        try {
          listener(parseWorkspaceNativeDropEvent(payload));
        } catch {
          // 非法事件 payload 在 UI callback 看到路径形状或 renderer 注入字段前丢弃。
        }
      });
    } catch (error) {
      throw normalizeRuntimeError(error);
    }
  }
}

/** 解析唯一 watcher event 形状，并脱敏畸形 payload 细节。 */
export function parseWorkspaceChangedEvent(payload: unknown): WorkspaceChangedEvent {
  try {
    return WorkspaceChangedEventSchema.parse(payload);
  } catch {
    throw new RuntimeHostError("INVALID_INPUT", "工作区变更事件无效", false);
  }
}

/** 在 WebView 边界解析只含 token 与 point 的原生 drop event。 */
export function parseWorkspaceNativeDropEvent(payload: unknown): WorkspaceNativeDropEvent {
  try {
    return WorkspaceNativeDropEventSchema.parse(payload);
  } catch {
    throw new RuntimeHostError("INVALID_INPUT", "原生拖入事件无效", false);
  }
}

/** 使用 Tauri 固定 invoke bridge 创建生产 workspace adapter。 */
export function createWorkspaceHostAdapter(): WorkspaceMutationHostAdapter {
  return new TauriWorkspaceHostAdapter();
}
