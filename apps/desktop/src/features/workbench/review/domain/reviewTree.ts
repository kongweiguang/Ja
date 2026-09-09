// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type ReviewTreeGrouping = "status" | "directory" | "flat";
export type ReviewTreeLayer = "staged" | "unstaged" | "untracked" | "comparison";

export interface ReviewTreeFile {
  readonly id: string;
  readonly path: string;
  readonly status: string;
  readonly layer: ReviewTreeLayer;
  readonly additions: number | null;
  readonly deletions: number | null;
  readonly binary: boolean;
}

export type ReviewTreeRow =
  | {
      readonly kind: "group";
      readonly id: string;
      readonly label: string;
      readonly depth: 0;
      readonly count: number;
      readonly group: string;
    }
  | {
      readonly kind: "folder";
      readonly id: string;
      readonly label: string;
      readonly depth: number;
      readonly count: number;
      readonly parentId?: string;
    }
  | {
      readonly kind: "file";
      readonly id: string;
      readonly label: string;
      readonly depth: number;
      readonly parentId?: string;
      readonly file: ReviewTreeFile;
    };

interface DirectoryNode {
  readonly name: string;
  readonly path: string;
  readonly directories: Map<string, DirectoryNode>;
  readonly files: DirectoryFile[];
  count: number;
}

interface DirectoryFile {
  readonly name: string;
  readonly file: ReviewTreeFile;
}

const GROUP_ORDER = ["conflicted", "unstaged", "staged", "untracked", "comparison"] as const;
const GROUP_LABELS: Readonly<Record<(typeof GROUP_ORDER)[number], string>> = {
  conflicted: "冲突",
  unstaged: "未暂存",
  staged: "已暂存",
  untracked: "未跟踪",
  comparison: "比较",
};

/** 只规范 UI 树使用的分隔符；native path 仍作为文件身份原样保留。 */
function pathParts(path: string): string[] {
  return path.replaceAll("\\", "/").split("/").filter(Boolean);
}

/** 冲突优先于暂存层分组，避免需要处理的文件被分散到普通状态中。 */
function statusGroup(file: ReviewTreeFile): (typeof GROUP_ORDER)[number] {
  return file.status === "conflicted" ? "conflicted" : file.layer;
}

/** 构造纯内存目录索引；分组和搜索不得触发目录扫描或正文读取。 */
function directoryRoot(files: readonly ReviewTreeFile[]): DirectoryNode {
  const root: DirectoryNode = {
    name: "",
    path: "",
    directories: new Map(),
    files: [],
    count: 0,
  };
  for (const file of files) {
    const parts = pathParts(file.path);
    const fileName = parts.pop() ?? file.path;
    let current = root;
    current.count += 1;
    for (const part of parts) {
      const childPath = current.path.length === 0 ? part : `${current.path}/${part}`;
      let child = current.directories.get(part);
      if (child === undefined) {
        child = {
          name: part,
          path: childPath,
          directories: new Map(),
          files: [],
          count: 0,
        };
        current.directories.set(part, child);
      }
      current = child;
      current.count += 1;
    }
    current.files.push({ name: fileName, file });
  }
  return root;
}

/** 单子目录链压缩为 IDEA 风格路径，同时保留最终节点的稳定展开身份。 */
function compressedDirectory(node: DirectoryNode): { node: DirectoryNode; label: string } {
  let current = node;
  const labels = [current.name];
  while (current.files.length === 0 && current.directories.size === 1) {
    current = current.directories.values().next().value as DirectoryNode;
    labels.push(current.name);
  }
  return { node: current, label: labels.join("/") };
}

/** 将目录索引展开为稳定行；搜索态由调用方决定是否忽略折叠集合。 */
function appendDirectoryRows(
  rows: ReviewTreeRow[],
  node: DirectoryNode,
  depth: number,
  namespace: string,
  parentId: string | undefined,
  expanded: ReadonlySet<string>,
  forceExpanded: boolean,
): void {
  const directories = [...node.directories.values()].sort((left, right) =>
    left.name.localeCompare(right.name),
  );
  for (const child of directories) {
    const compressed = compressedDirectory(child);
    const id = `${namespace}:folder:${compressed.node.path}`;
    rows.push({
      kind: "folder",
      id,
      label: compressed.label,
      depth,
      count: compressed.node.count,
      parentId,
    });
    if (forceExpanded || expanded.has(id)) {
      appendDirectoryRows(rows, compressed.node, depth + 1, namespace, id, expanded, forceExpanded);
    }
  }
  for (const entry of [...node.files].sort((left, right) =>
    left.file.path.localeCompare(right.file.path),
  )) {
    rows.push({
      kind: "file",
      id: `${namespace}:file:${entry.file.id}`,
      label: entry.name,
      depth,
      parentId,
      file: entry.file,
    });
  }
}

/**
 * 依据用户选择生成可虚拟化的扁平行；查询只过滤完整路径并强制展开命中祖先，
 * 清空查询后仍使用原 expanded 集合，因此不会破坏用户原来的折叠状态。
 */
export function buildReviewTreeRows(
  files: readonly ReviewTreeFile[],
  grouping: ReviewTreeGrouping,
  query: string,
  expanded: ReadonlySet<string>,
): ReviewTreeRow[] {
  const normalizedQuery = query.trim().toLocaleLowerCase();
  const visibleFiles =
    normalizedQuery.length === 0
      ? files
      : files.filter((file) => file.path.toLocaleLowerCase().includes(normalizedQuery));
  if (grouping === "flat")
    return [...visibleFiles]
      .sort((left, right) => left.path.localeCompare(right.path))
      .map((file) => ({
        kind: "file" as const,
        id: `flat:file:${file.id}`,
        label: file.path,
        depth: 0,
        file,
      }));

  const rows: ReviewTreeRow[] = [];
  if (grouping === "directory") {
    appendDirectoryRows(
      rows,
      directoryRoot(visibleFiles),
      0,
      "directory",
      undefined,
      expanded,
      normalizedQuery.length > 0,
    );
    return rows;
  }

  for (const group of GROUP_ORDER) {
    const grouped = visibleFiles.filter((file) => statusGroup(file) === group);
    if (grouped.length === 0) continue;
    const groupId = `group:${group}`;
    rows.push({
      kind: "group",
      id: groupId,
      label: GROUP_LABELS[group],
      depth: 0,
      count: grouped.length,
      group,
    });
    if (normalizedQuery.length > 0 || expanded.has(groupId))
      appendDirectoryRows(
        rows,
        directoryRoot(grouped),
        1,
        groupId,
        groupId,
        expanded,
        normalizedQuery.length > 0,
      );
  }
  return rows;
}

/** 默认展开顶层分组和目录第一层，让首次进入即可看见文件而不展开整棵大树。 */
export function defaultExpandedReviewTree(
  files: readonly ReviewTreeFile[],
  grouping: ReviewTreeGrouping,
): Set<string> {
  if (grouping === "flat") return new Set();
  if (grouping === "status") {
    const expanded = new Set<string>();
    for (const group of GROUP_ORDER) {
      const grouped = files.filter((file) => statusGroup(file) === group);
      if (grouped.length === 0) continue;
      const groupId = `group:${group}`;
      expanded.add(groupId);
      for (const child of directoryRoot(grouped).directories.values()) {
        const compressed = compressedDirectory(child);
        expanded.add(`${groupId}:folder:${compressed.node.path}`);
      }
    }
    return expanded;
  }
  const root = directoryRoot(files);
  return new Set(
    [...root.directories.values()].map((child) => {
      const compressed = compressedDirectory(child);
      return `directory:folder:${compressed.node.path}`;
    }),
  );
}
