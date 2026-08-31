// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { parseTerminalLayout, type TerminalLayoutV1 } from "../domain/terminalLayout";

const STORAGE_KEY = "ja-terminal-layouts-v1";
const MAX_WORKSPACES = 24;
const MAX_LAYOUT_BYTES = 64 * 1024;
const MAX_TOTAL_BYTES = 512 * 1024;
const WORKSPACE_ID_PATTERN = /^ws_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/;

interface TerminalLayoutEnvelope {
  version: 1;
  layouts: Record<string, unknown>;
}

/** 只接受当前 envelope 的精确字段，未知或旧版本介质不会被重新解释。 */
function parseEnvelope(raw: string | null): TerminalLayoutEnvelope | undefined {
  if (raw === null) return undefined;
  try {
    const value = JSON.parse(raw) as unknown;
    if (value === null || typeof value !== "object" || Array.isArray(value)) return undefined;
    const candidate = value as Record<string, unknown>;
    const keys = Object.keys(candidate);
    if (keys.length !== 2 || !keys.includes("version") || !keys.includes("layouts"))
      return undefined;
    if (candidate["version"] !== 1) return undefined;
    const layouts = candidate["layouts"];
    if (layouts === null || typeof layouts !== "object" || Array.isArray(layouts)) return undefined;
    return { version: 1, layouts: layouts as Record<string, unknown> };
  } catch {
    return undefined;
  }
}

/** 用 UTF-8 字节预算约束单项和总缓存，避免 UTF-16 字符绕过 localStorage 上限。 */
function serializedBytes(value: unknown): number | undefined {
  try {
    return new TextEncoder().encode(JSON.stringify(value)).byteLength;
  } catch {
    return undefined;
  }
}

/** Terminal persistence 只暴露严格 load/save，不复制领域 schema 或执行读时迁移。 */
export interface TerminalLayoutStorage {
  load(workspaceId: string): TerminalLayoutV1 | undefined;
  save(layout: TerminalLayoutV1): void;
}

/** application 只依赖布局持久化所需的最小键值端口，浏览器介质由 composition 显式注入。 */
export interface TerminalLayoutMedia {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export class LocalTerminalLayoutStorage implements TerminalLayoutStorage {
  /** 依赖介质工厂而不捕获浏览器全局，使 application 可在无 DOM 环境验证严格 schema。 */
  constructor(private readonly storage: () => TerminalLayoutMedia | undefined) {}

  /** 读取只返回当前严格 schema；损坏值保持原样，等待用户下一次真实保存覆盖。 */
  load(workspaceId: string): TerminalLayoutV1 | undefined {
    if (!WORKSPACE_ID_PATTERN.test(workspaceId)) return undefined;
    const storage = this.storage();
    if (storage === undefined) return undefined;
    const envelope = parseEnvelope(storage.getItem(STORAGE_KEY));
    return parseTerminalLayout(envelope?.layouts[workspaceId], workspaceId);
  }

  /**
   * 保存时重建当前版本闭集并按插入顺序驱逐最旧项；旧/损坏条目不会被迁移或带入新介质。
   */
  save(layout: TerminalLayoutV1): void {
    if (!WORKSPACE_ID_PATTERN.test(layout.workspaceId)) return;
    const canonical = parseTerminalLayout(layout, layout.workspaceId);
    const canonicalBytes = canonical === undefined ? undefined : serializedBytes(canonical);
    if (
      canonical === undefined ||
      canonicalBytes === undefined ||
      canonicalBytes > MAX_LAYOUT_BYTES
    )
      return;
    const storage = this.storage();
    if (storage === undefined) return;
    const existing = parseEnvelope(storage.getItem(STORAGE_KEY));
    const retained: Array<[string, TerminalLayoutV1, number]> = [];
    for (const [workspaceId, rawLayout] of Object.entries(existing?.layouts ?? {})) {
      if (workspaceId === canonical.workspaceId || !WORKSPACE_ID_PATTERN.test(workspaceId))
        continue;
      const parsed = parseTerminalLayout(rawLayout, workspaceId);
      const bytes = parsed === undefined ? undefined : serializedBytes(parsed);
      if (parsed !== undefined && bytes !== undefined && bytes <= MAX_LAYOUT_BYTES) {
        retained.push([workspaceId, parsed, bytes]);
      }
    }
    retained.push([canonical.workspaceId, canonical, canonicalBytes]);
    let totalBytes = retained.reduce((total, entry) => total + entry[2], 0);
    while (retained.length > MAX_WORKSPACES || totalBytes > MAX_TOTAL_BYTES) {
      const removed = retained.shift();
      if (removed !== undefined) totalBytes -= removed[2];
    }
    storage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        version: 1,
        layouts: Object.fromEntries(retained.map(([id, value]) => [id, value])),
      }),
    );
  }
}
