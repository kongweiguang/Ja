// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { WorkspaceFileNode } from "./types";

/** 统一派生 Create 与 Native Drop 使用的父目录，避免各调用方形成不同路径语义。 */
export function parentPath(path: string): string {
  const separator = path.lastIndexOf("/");
  return separator < 0 ? "" : path.slice(0, separator);
}

/** 只从受控相对路径派生叶子名，展示层不自行解释平台分隔符。 */
export function entryName(path: string): string {
  const separator = path.lastIndexOf("/");
  return separator < 0 ? path : path.slice(separator + 1);
}

/** 在不可变树投影中按权威相对路径查找节点，递归只读取而不修改输入。 */
export function findTreeNode(
  nodes: readonly WorkspaceFileNode[],
  path: string,
): WorkspaceFileNode | undefined {
  for (const node of nodes) {
    if (node.path === path) return node;
    if (node.children !== undefined) {
      const found = findTreeNode(node.children, path);
      if (found !== undefined) return found;
    }
  }
  return undefined;
}

/**
 * 判断后代 page 是否晚于祖先 page 发起，让更近的懒加载胜出，同时避免全局串行化 IO。
 */
export function hasNewerTreeRequest(
  path: string,
  requestEpoch: number,
  latestRequests: ReadonlyMap<string, number>,
): boolean {
  for (const [candidate, epoch] of latestRequests) {
    if (epoch > requestEpoch && (candidate === path || candidate.startsWith(`${path}/`)))
      return true;
  }
  return false;
}

/**
 * 父目录 page 只对直属条目有权威性，不能凭请求先后抹掉另一次真实读取到的子目录
 * page；只要目录路径仍存在就保留其独立 children/loading/error 投影，目录被删除时
 * incoming 不再包含该路径，旧子树仍会自然消失。
 */
export function mergeTreePageWithLoadedDescendants(
  current: readonly WorkspaceFileNode[],
  incoming: readonly WorkspaceFileNode[],
): WorkspaceFileNode[] {
  return incoming.map((node) => {
    const previous = findTreeNode(current, node.path);
    if (
      previous === undefined ||
      previous.id !== node.id ||
      previous.name !== node.name ||
      previous.kind !== node.kind ||
      previous.hasChildren !== node.hasChildren
    )
      return node;
    // React Arborist 会从受控 data 重建节点；复用 UI 投影未变化的对象，既不会
    // 丢失独立读取的 children，也为调用方保留整页引用稳定性的判断依据。
    return previous;
  });
}

/** 按 Arborist 稳定 id 定位节点，使懒加载回调不依赖易变化的数组位置。 */
export function findTreeNodeById(
  nodes: readonly WorkspaceFileNode[],
  id: string,
): WorkspaceFileNode | undefined {
  for (const node of nodes) {
    if (node.id === id) return node;
    if (node.children !== undefined) {
      const found = findTreeNodeById(node.children, id);
      if (found !== undefined) return found;
    }
  }
  return undefined;
}

/** 只替换目标目录子树，保持无关展开行与对象投影不受一次 page 刷新影响。 */
export function replaceTreeChildren(
  nodes: readonly WorkspaceFileNode[],
  path: string,
  children: readonly WorkspaceFileNode[],
  loading = false,
  error?: string,
): WorkspaceFileNode[] {
  return nodes.map((node) => {
    if (node.path === path) return { ...node, children, loading, error };
    if (node.children === undefined) return node;
    return {
      ...node,
      children: replaceTreeChildren(node.children, path, children, loading, error),
    };
  });
}

/** 仅在 native mutation 成功后移除目标节点，并保持其余树分支不可变。 */
export function removeTreeNode(
  nodes: readonly WorkspaceFileNode[],
  path: string,
): WorkspaceFileNode[] {
  return nodes
    .filter((node) => node.path !== path)
    .map((node) =>
      node.children === undefined
        ? node
        : { ...node, children: removeTreeNode(node.children, path) },
    );
}

/**
 * 在 native 调用前拒绝自移动和后代移动；Rust adapter 仍负责权威 containment 与冲突校验。
 */
export function canMoveEntry(
  source: WorkspaceFileNode,
  target: WorkspaceFileNode,
  siblingNames: readonly string[] = [],
): boolean {
  if (source.path === target.path || target.kind !== "directory") return false;
  if (target.path.startsWith(`${source.path}/`)) return false;
  return !siblingNames.includes(source.name);
}

/** 创建一次性 mutation id，避免把 native 或 session 身份写入浏览器持久化。 */
export function createMutationId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function")
    return crypto.randomUUID();
  return `ja-mutation-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** 识别可安全保留的换行风格；mixed/unknown 继续作为只读元数据而不猜测转换。 */
export function detectNewlineStyle(content: string): "lf" | "crlf" | "cr" | "mixed" | "unknown" {
  const matches = content.match(/\r\n|\r|\n/g);
  // 无换行的文件没有需要保留的样式；LF 是中性写入表示，可让普通单行文件保持可编辑。
  if (matches === null || matches.length === 0) return "lf";
  const kinds = new Set(matches);
  if (kinds.size > 1) return "mixed";
  const only = matches[0];
  return only === "\r\n" ? "crlf" : only === "\r" ? "cr" : "lf";
}
