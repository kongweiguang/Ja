// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactNode } from "react";
import type {
  FilesActions,
  FilesSearchResult,
  FilesSearchSummary,
  FilesViewModel,
  TrashDialogPhase,
} from "../application/types";
import type { FilesOpenTarget } from "../application/ports";
import type { WorkspaceFileNode } from "../domain/types";
import type { MarkdownFileTarget } from "@/shared/ui/markdown/SharedMarkdown";

/** FilesWorkspace 纯视图只接收 controller 输出，不接收 operations、timer 或 native port。 */
export interface FilesWorkspaceViewProps {
  viewModel: FilesViewModel;
  actions: FilesActions;
  onAddToConversation?: (node: Pick<WorkspaceFileNode, "path" | "kind">) => void;
  /** Markdown 文档中的本机链接仍通过 Workbench Host 解析，不给 Renderer 文件系统能力。 */
  onOpenMarkdownFile?: (
    target: MarkdownFileTarget,
    source: HTMLElement,
    mode?: "explorer" | "browser",
  ) => void | Promise<void>;
  /** Markdown 文档内 HTTP(S) 链接复用 Thread 当前的隔离 Preview。 */
  onOpenMarkdownLink?: (url: string, source?: HTMLElement) => void | Promise<void>;
  /** 代码块和路径菜单复制沿用桌面 Clipboard capability。 */
  onCopyText?: (text: string) => Promise<void>;
}

/** SearchPanel 只消费 application projection 和语义 intent，不直接读取 Runtime。 */
export interface SearchPanelProps {
  query?: string;
  results: readonly FilesSearchResult[];
  summary?: FilesSearchSummary;
  loading?: boolean;
  error?: string;
  onQueryChange?: (query: string) => void;
  onOpenResult?: (result: FilesSearchResult) => void;
  onAddToConversation?: (result: FilesSearchResult) => void;
  onRetry?: () => void;
  idleContent?: ReactNode;
}

/** Save As 对话框只表达相对路径 intent，不接触 workspace 或 native adapter。 */
export interface SaveAsDialogProps {
  open: boolean;
  sourcePath: string;
  value: string;
  error?: string;
  pending: boolean;
  onValueChange: (value: string) => void;
  onCancel: () => void;
  onSubmit: () => void | Promise<void>;
}

/** Trash 对话框只展示 prepare 摘要并提交确认，不持有 operation token。 */
export interface TrashConfirmDialogProps {
  open: boolean;
  relativePath: string;
  phase: TrashDialogPhase;
  fileCount?: number;
  totalBytes?: number;
  error?: string;
  onCancel: () => void;
  onConfirm: () => void | Promise<void>;
  onRestoreFocus: () => void;
}

/** FileTree 只接收领域节点与语义化 intent，所有 mutation 仍回到唯一 controller。 */
export interface FileTreeProps {
  nodes: readonly WorkspaceFileNode[];
  selectedPath?: string;
  /** 仅显式打开的目录请求会展开必要祖先并滚动定位，不触发全树扫描。 */
  revealRequest?: { path: string; sequence: number };
  loading?: boolean;
  error?: string;
  onSelect?: (node: WorkspaceFileNode) => void;
  onDirectoryToggle?: (node: WorkspaceFileNode) => void;
  onRetry?: () => void;
  onCreateFile?: (parentPath: string, name: string) => void | Promise<void>;
  onCreateDirectory?: (parentPath: string, name: string) => void | Promise<void>;
  onRename?: (node: WorkspaceFileNode, newName: string) => void | Promise<void>;
  onMove?: (node: WorkspaceFileNode, targetDirectory: string) => void | Promise<void>;
  onTrash?: (node: WorkspaceFileNode) => void | Promise<void>;
  onRefresh?: (relativePath?: string) => void | Promise<void>;
  onContextMenu?: (node: WorkspaceFileNode, event: MouseEvent) => void;
  onAddToConversation?: (node: WorkspaceFileNode) => void;
  onNativeDropToken?: (dropToken: string, targetDirectory: string) => void | Promise<void>;
  openTargets?: readonly FilesOpenTarget[];
  onOpenTarget?: (target: FilesOpenTarget["target"], relativePath: string) => void | Promise<void>;
}
