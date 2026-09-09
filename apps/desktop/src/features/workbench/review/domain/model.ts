// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  ReviewAction,
  ReviewCapabilities,
  ReviewFile,
  ReviewFileDiff,
  ReviewSnapshot,
  ReviewSource,
  ReviewTarget,
} from "./types";

export type ReviewFilter = "all" | ReviewFile["status"];
export type ReviewViewMode = "split" | "unified";

/** 使用确定性的来源身份，让 application 能拒绝异步返回的旧快照。 */
export function sourceKey(source: ReviewSource): string {
  switch (source.kind) {
    case "uncommitted":
      return "uncommitted";
    case "unstaged":
      return "unstaged";
    case "staged":
      return "staged";
    case "branch":
      return `branch:${source.refId}`;
    case "commit":
      return `commit:${source.commitId}`;
  }
}

/** 保持来源标签紧凑，使窄窗口中的选择器仍可完整操作。 */
export function sourceLabel(source: ReviewSource, branchLabel?: string): string {
  switch (source.kind) {
    case "uncommitted":
      return "未提交";
    case "unstaged":
      return "未暂存";
    case "staged":
      return "已暂存";
    case "branch":
      return branchLabel ?? source.refId;
    case "commit":
      return `提交 ${source.commitId.slice(0, 8)}`;
  }
}

/** 只从 native capability 派生安全操作，不能信任 UI 自己推断的权限。 */
export function canReviewAction(
  capabilities: ReviewCapabilities | undefined,
  action: ReviewAction,
): boolean {
  if (capabilities === undefined) return false;
  return action === "stage"
    ? capabilities.stage
    : action === "unstage"
      ? capabilities.unstage
      : capabilities.revert;
}

/** Branch 与 commit 必须保持只读，即使响应错误携带可变 capability 也不能放行。 */
export function sourceAllowsMutation(source: ReviewSource): boolean {
  return source.kind === "uncommitted" || source.kind === "unstaged" || source.kind === "staged";
}

/** 仅按权威文件元数据筛选，不从文件名或 patch 文本猜测状态。 */
export function filterReviewFiles(
  files: readonly ReviewFile[],
  filter: ReviewFilter,
  query: string,
): ReviewFile[] {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  return files.filter(
    (file) =>
      (filter === "all" || file.status === filter) &&
      (normalizedQuery.length === 0 ||
        file.path.toLocaleLowerCase().includes(normalizedQuery) ||
        file.oldPath?.toLocaleLowerCase().includes(normalizedQuery) === true),
  );
}

/** 只在既有选择仍属于当前快照时保留，避免跨 revision 指向旧文件。 */
export function retainFileSelection(
  files: readonly ReviewFile[],
  selectedFileId: string | undefined,
): string | undefined {
  if (selectedFileId !== undefined && files.some((file) => file.fileId === selectedFileId))
    return selectedFileId;
  return files[0]?.fileId;
}

/** 从权威快照中解析当前文件，找不到时保持未选择而不伪造结果。 */
export function findReviewFile(
  snapshot: ReviewSnapshot | undefined,
  fileId: string | undefined,
): ReviewFile | undefined {
  if (snapshot === undefined || fileId === undefined) return undefined;
  return snapshot.files.find((file) => file.fileId === fileId);
}

/** 渲染前核对 Diff 仍属于当前来源与 revision，防止旧请求覆盖新选择。 */
export function diffMatchesSnapshot(
  diff: ReviewFileDiff | undefined,
  snapshot: ReviewSnapshot | undefined,
): boolean {
  const file = snapshot?.files.find((candidate) => candidate.fileId === diff?.fileId);
  return (
    diff !== undefined &&
    snapshot !== undefined &&
    file !== undefined &&
    diff.workspaceId === snapshot.workspaceId &&
    sourceKey(diff.source) === sourceKey(snapshot.source) &&
    diff.revision === snapshot.revision &&
    diff.path === file.path &&
    diff.oldPath === file.oldPath &&
    diff.layer === file.layer &&
    diff.status === file.status
  );
}

/** 生成规范 target key，确保区块操作不会误指向另一个文件。 */
export function targetKey(target: ReviewTarget): string {
  switch (target.kind) {
    case "all":
      return "all";
    case "file":
      return `file:${target.fileId}`;
    case "hunk":
      return `hunk:${target.fileId}:${target.hunkId}`;
  }
}

/** 为操作按钮和确认提示生成稳定文案，保持无障碍名称一致。 */
export function actionLabel(action: ReviewAction, target: ReviewTarget): string {
  const noun = target.kind === "all" ? "全部" : target.kind === "file" ? "文件" : "区块";
  if (action === "stage") return `暂存${noun}`;
  if (action === "unstage") return `取消暂存${noun}`;
  return `撤销${noun}`;
}

/** 把封闭文件状态映射为可读中文，不透传 Git 原始状态码。 */
export function fileStatusLabel(status: ReviewFile["status"]): string {
  switch (status) {
    case "added":
      return "新增";
    case "modified":
      return "修改";
    case "deleted":
      return "删除";
    case "renamed":
      return "重命名";
    case "copied":
      return "复制";
    case "conflicted":
      return "冲突";
    case "untracked":
      return "未跟踪";
  }
}

/** 把文件状态映射为紧凑标记，让高密度文件树仍易扫描。 */
export function fileStatusMark(status: ReviewFile["status"]): string {
  switch (status) {
    case "added":
      return "A";
    case "modified":
      return "M";
    case "deleted":
      return "D";
    case "renamed":
      return "R";
    case "copied":
      return "C";
    case "conflicted":
      return "U";
    case "untracked":
      return "?";
  }
}

/** 只有完整非二进制文本才能进入 DiffViewer，避免把截断内容伪装成完整 Diff。 */
export function canRenderTextDiff(diff: ReviewFileDiff | undefined): boolean {
  return (
    diff !== undefined &&
    !diff.binary &&
    !diff.truncated &&
    diff.original !== null &&
    diff.modified !== null
  );
}

/**
 * native adapter 只承诺返回有界 unified/line 投影，不承诺物化完整前后文件；只要响应仍是
 * 非二进制、未截断且含任一结构化 Diff 表示，就允许视图安全降级为统一模式。
 */
export function canRenderUnifiedDiff(diff: ReviewFileDiff | undefined): boolean {
  return (
    diff !== undefined &&
    !diff.binary &&
    !diff.truncated &&
    (diff.unified !== null || diff.lines.length > 0)
  );
}
