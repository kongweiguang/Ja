// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  createPreviewAddressState,
  projectPreviewUrl,
  reducePreviewAddress,
  resolvePreviewNavigation,
} from "@/features/workbench/preview/domain/previewModel";

describe("previewModel", () => {
  it("用纯 reducer 管理草稿、同步与校验状态", () => {
    const initial = createPreviewAddressState("https://example.com");
    const changed = reducePreviewAddress(initial, { type: "change", draft: "https://openai.com" });
    const invalid = reducePreviewAddress(changed, { type: "validation", message: "地址无效" });
    const synced = reducePreviewAddress(invalid, { type: "sync", url: "https://ja.local" });

    expect(changed.draft).toBe("https://openai.com");
    expect(invalid.validationError).toBe("地址无效");
    expect(synced).toEqual({ draft: "https://ja.local" });
  });

  it.each(["javascript:alert(1)", "data:text/html,hello", "tauri://localhost"])(
    "拒绝不支持的协议 %s",
    (unsafeUrl) => {
      expect(resolvePreviewNavigation(unsafeUrl, "")).toEqual({
        kind: "invalid",
        message: "请输入有效的 http(s) 地址或本机文件路径。",
      });
    },
  );

  it.each(["index.html", "src/pages/演示 文件.html", "file:///C:/workspace/index.html"])(
    "将本机文件目标 %s 交给 Rust 按 workspace 与权限解析",
    (path) => {
      expect(resolvePreviewNavigation(path, "")).toEqual({ kind: "open_file", path });
    },
  );

  it("区分同地址刷新与新地址导航", () => {
    expect(
      resolvePreviewNavigation(" https://example.com/path ", "https://example.com/path"),
    ).toEqual({ kind: "reload" });
    expect(resolvePreviewNavigation("https://openai.com", "https://example.com")).toEqual({
      kind: "navigate",
      url: "https://openai.com/",
    });
  });

  it("只为安全地址生成 DOM 投影", () => {
    expect(projectPreviewUrl("file:///tmp/demo")).toEqual({
      href: "file:///tmp/demo",
      origin: "本机文件",
    });
    expect(projectPreviewUrl("https://example.com/path")).toEqual({
      href: "https://example.com/path",
      origin: "https://example.com",
    });
  });
});
