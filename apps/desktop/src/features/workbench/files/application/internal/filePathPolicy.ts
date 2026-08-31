// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { entryName, parentPath } from "../../domain/filesModel";

export interface ValidatedRelativePath {
  path?: string;
  error?: string;
}

/** 组合相对父目录与叶子名，统一使用斜杠，避免 application 产生平台相关路径。 */
export function joinRelativePath(parent: string, name: string): string {
  return parent.length === 0 ? name : `${parent}/${name}`;
}

/** 按路径段判断后代关系，避免 `foo` 与 `foobar` 的字符串前缀碰撞。 */
export function isSameOrDescendantPath(path: string, root: string): boolean {
  return path === root || path.startsWith(`${root}/`);
}

/** 移动目录时只替换所属前缀，并保留后代的未变后缀和非目标路径身份。 */
export function remapMovedPath(path: string, from: string, to: string): string {
  return isSameOrDescendantPath(path, from) ? `${to}${path.slice(from.length)}` : path;
}

/** 建议同目录副本名并保留原扩展名；存在性仍由 native CAS create 权威判定。 */
export function suggestedSaveAsPath(path: string): string {
  const parent = parentPath(path);
  const name = entryName(path);
  const extensionIndex = name.lastIndexOf(".");
  const copyName =
    extensionIndex > 0
      ? `${name.slice(0, extensionIndex)}.copy${name.slice(extensionIndex)}`
      : `${name}.copy`;
  return joinRelativePath(parent, copyName);
}

/**
 * 在类型化 IPC 前拒绝绝对路径、穿越、控制字符和歧义相对路径；此处只负责
 * renderer fail-fast，containment 与目标冲突仍必须由 Rust CAS 再次权威校验。
 */
export function validateSaveAsPath(value: string, sourcePath: string): ValidatedRelativePath {
  const path = value.trim();
  if (path.length === 0) return { error: "请输入工作区相对路径。" };
  const components = path.split("/");
  const containsControlCharacter = [...path].some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint <= 31 || codePoint === 127;
  });
  /** 一次性拒绝所有可能改变 containment 语义的输入，不在后续阶段尝试修复。 */
  if (
    path.length > 4_096 ||
    path.startsWith("/") ||
    path.includes("\\") ||
    path.includes(":") ||
    containsControlCharacter ||
    components.some(
      (component) => component.length === 0 || component === "." || component === "..",
    )
  ) {
    return { error: "路径必须位于当前工作区内，且不能包含绝对路径或 ..。" };
  }
  if (path === sourcePath) return { error: "另存为不能覆盖当前文件，请输入新路径。" };
  return { path };
}
