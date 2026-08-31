// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { writeClipboardText } from "@/api/tauri/clipboard";
import { recordUiDiagnostic } from "@/api/tauri/diagnostics";
import { enableDesktopNotifications, sendDesktopNotification } from "@/api/tauri/notification";
import { normalizeExternalHttpUrl, openExternalHttpUrl } from "@/api/tauri/opener";

const native = vi.hoisted(() => ({
  openUrl: vi.fn(() => Promise.resolve()),
  writeText: vi.fn(() => Promise.resolve()),
  isPermissionGranted: vi.fn(() => Promise.resolve(false)),
  requestPermission: vi.fn(() => Promise.resolve("denied" as const)),
  sendNotification: vi.fn(),
  error: vi.fn(() => Promise.resolve()),
}));

vi.mock("@tauri-apps/plugin-opener", () => ({ openUrl: native.openUrl }));
vi.mock("@tauri-apps/plugin-clipboard-manager", () => ({ writeText: native.writeText }));
vi.mock("@tauri-apps/plugin-notification", () => ({
  isPermissionGranted: native.isPermissionGranted,
  requestPermission: native.requestPermission,
  sendNotification: native.sendNotification,
}));
vi.mock("@tauri-apps/plugin-log", () => ({ error: native.error }));

describe("official desktop plugin adapters", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    native.isPermissionGranted.mockResolvedValue(false);
    native.requestPermission.mockResolvedValue("denied");
  });

  it("opens only normalized HTTP(S) URLs and rejects paths, credentials and custom schemes", async () => {
    expect(normalizeExternalHttpUrl(" https://example.com/docs ")).toBe("https://example.com/docs");
    await openExternalHttpUrl("http://example.com/path?q=1#result");
    expect(native.openUrl).toHaveBeenCalledWith("http://example.com/path?q=1#result");

    for (const rejected of [
      "file:///C:/private.txt",
      "javascript:alert(1)",
      "ja://thread/one",
      "https://user:secret@example.com",
      "C:\\private.txt",
    ]) {
      await expect(openExternalHttpUrl(rejected)).rejects.toThrow("not allowed");
    }
    expect(native.openUrl).toHaveBeenCalledOnce();
  });

  it("writes bounded text without exposing a clipboard read path", async () => {
    await writeClipboardText("复制内容");
    expect(native.writeText).toHaveBeenCalledWith("复制内容");
    await expect(writeClipboardText("x".repeat(4_194_305))).rejects.toThrow("supported size");
    expect(native.writeText).toHaveBeenCalledOnce();
  });

  it("requests notification permission only from the explicit enable action", async () => {
    await expect(enableDesktopNotifications()).resolves.toBe(false);
    expect(native.requestPermission).toHaveBeenCalledOnce();

    native.isPermissionGranted.mockResolvedValueOnce(true);
    await expect(enableDesktopNotifications()).resolves.toBe(true);
    expect(native.requestPermission).toHaveBeenCalledOnce();
  });

  it("sends only fixed non-sensitive notification summaries", async () => {
    native.isPermissionGranted.mockResolvedValue(true);
    await sendDesktopNotification("completed");
    await sendDesktopNotification("failed");
    await sendDesktopNotification("approval");

    expect(native.sendNotification.mock.calls).toEqual([
      [{ title: "Ja 已完成", body: "后台任务已完成。" }],
      [{ title: "Ja 任务失败", body: "后台任务未能完成，请返回 Ja 查看。" }],
      [{ title: "Ja 需要确认", body: "有一项操作等待你的确认。" }],
    ]);
  });

  it("forwards only the closed UI diagnostic vocabulary", async () => {
    await recordUiDiagnostic("react_error_boundary");
    expect(native.error).toHaveBeenCalledWith("ui.react_error_boundary");
  });
});

describe("desktop capability manifest", () => {
  /** 以数据方式读取受版本管理的策略，使断言覆盖真实 Tauri capability，
   * 而不是复制一份可能漂移的 TypeScript 权限列表。 */
  function capability(): {
    windows?: string[];
    webviews?: string[];
    platforms?: string[];
    permissions: Array<string | { identifier: string; allow?: Array<Record<string, string>> }>;
  } {
    return JSON.parse(
      readFileSync(resolve(process.cwd(), "src-tauri/capabilities/default.json"), "utf8"),
    ) as ReturnType<typeof capability>;
  }

  /**
   * 标题栏的“关闭”语义是隐藏到托盘，因此显式窗口权限必须与真实动作一致；
   * 不保留未使用的 WebView close 权限，避免后续绕过应用拥有的退出握手。
   */
  it("grants the titlebar hide action instead of a destructive close action", () => {
    const identifiers = capability()
      .permissions.map((permission) =>
        typeof permission === "string" ? permission : permission.identifier,
      )
      .filter((identifier) => identifier.startsWith("core:window:"))
      .sort();

    expect(identifiers).toEqual(
      [
        "core:window:allow-hide",
        "core:window:allow-minimize",
        "core:window:allow-start-dragging",
        "core:window:allow-toggle-maximize",
      ].sort(),
    );
  });

  it("grants official plugin commands only to the trusted main WebView", () => {
    const policy = capability();
    expect(policy.windows).toBeUndefined();
    expect(policy.webviews).toEqual(["main"]);
    expect(policy.platforms).toEqual(["windows", "macOS"]);
    const identifiers = policy.permissions.map((permission) =>
      typeof permission === "string" ? permission : permission.identifier,
    );
    expect(identifiers).toEqual(
      expect.arrayContaining([
        "notification:allow-is-permission-granted",
        "notification:allow-request-permission",
        "notification:allow-notify",
        "clipboard-manager:allow-write-text",
        "log:allow-log",
        "opener:allow-open-url",
      ]),
    );
    expect(identifiers).not.toEqual(
      expect.arrayContaining([
        "notification:default",
        "clipboard-manager:default",
        "clipboard-manager:allow-read-text",
        "clipboard-manager:allow-read-image",
        "opener:default",
        "opener:allow-open-path",
        "opener:allow-reveal-item-in-dir",
      ]),
    );
    const opener = policy.permissions.find(
      (permission) =>
        typeof permission !== "string" && permission.identifier === "opener:allow-open-url",
    );
    expect(opener).toEqual({
      identifier: "opener:allow-open-url",
      allow: [{ url: "http://*" }, { url: "https://*" }],
    });
  });

  it("registers single-instance before every other plugin", () => {
    const source = readFileSync(resolve(process.cwd(), "src-tauri/src/lib.rs"), "utf8");
    const singleInstance = source.indexOf("builder.plugin(tauri_plugin_single_instance::init");
    expect(singleInstance).toBeGreaterThan(0);
    for (const registration of [
      "tauri_plugin_dialog::init",
      "tauri_plugin_opener::init",
      "tauri_plugin_notification::init",
      "tauri_plugin_window_state::Builder",
    ]) {
      expect(source.indexOf(registration, singleInstance + 1)).toBeGreaterThan(singleInstance);
    }
  });
});
