// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  MediaPreviewSessionHintStorage,
  type PreviewSessionHintMedia,
} from "@/features/workbench/preview";

/** 用内存介质验证 application adapter，不把浏览器全局对象带入架构测试。 */
function createMedia(): PreviewSessionHintMedia & { values: Map<string, string> } {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
}

describe("MediaPreviewSessionHintStorage", () => {
  it("只恢复当前 UUID hint，并以 compare-before-delete 保护较新的 session", () => {
    const media = createMedia();
    const storage = new MediaPreviewSessionHintStorage(() => media);
    const workspaceId = "ws_fixture";
    const first = "00000000-0000-4000-8000-000000000002";
    const second = "00000000-0000-4000-8000-000000000003";

    storage.remember(workspaceId, first);
    expect(storage.read(workspaceId)).toBe(first);
    storage.remember(workspaceId, second);
    storage.forget(workspaceId, first);
    expect(storage.read(workspaceId)).toBe(second);
    storage.forget(workspaceId, second);
    expect(storage.read(workspaceId)).toBeUndefined();
  });

  it("介质异常与损坏值均降级为无 hint，不改变 Rust 权威 session", () => {
    const throwing = new MediaPreviewSessionHintStorage(() => {
      throw new Error("storage unavailable");
    });
    expect(throwing.read("ws_fixture")).toBeUndefined();
    expect(() =>
      throwing.remember("ws_fixture", "00000000-0000-4000-8000-000000000002"),
    ).not.toThrow();

    const media = createMedia();
    media.values.set("ja-preview-session-v1:ws_fixture", "not-a-session");
    const storage = new MediaPreviewSessionHintStorage(() => media);
    expect(storage.read("ws_fixture")).toBeUndefined();
  });
});
