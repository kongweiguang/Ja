// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 文件树节点是 Files 领域的只读投影；相对路径是唯一身份，UI 不得根据名称重新拼接路径。
 */
export interface WorkspaceFileNode {
  id: string;
  name: string;
  path: string;
  kind: WorkspaceEntryKind;
  children?: readonly WorkspaceFileNode[];
  hasChildren?: boolean;
  loading?: boolean;
  error?: string;
}

/** 完整 CAS 身份必须跨读取、Watcher 与写入闭环传递，任何层都不能自行裁剪字段。 */
export interface FileRevision {
  kind: "file" | "directory" | "symlink" | "reparse_point" | "other";
  size: number;
  modifiedUnixMillis: number | null;
  sha256: string | null;
}

/** Tree 与 revision 共用同一个原生节点闭集，禁止 adapter 把特殊节点伪装成普通文件。 */
export type WorkspaceEntryKind = FileRevision["kind"];

/** 编码闭集只表达 native 已确认的文本格式，不允许 UI 猜测未知编码。 */
export type FileEncoding = "utf8" | "utf8_bom" | "utf16_le" | "utf16_be";

/** mixed/unknown 是必须显式处理的只读状态，不能在保存时静默归一化。 */
export type NewlineStyle = "lf" | "crlf" | "cr" | "mixed" | "unknown";

/** 内容类型来自有界 native 读取结果，非 text 类型不得进入可写编辑链路。 */
export type FileContentKind = "text" | "binary" | "unknown_encoding" | "too_large";
