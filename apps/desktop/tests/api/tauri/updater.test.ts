// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it, vi } from "vitest";

const native = vi.hoisted(() => ({
  isTauri: vi.fn(() => true),
  check: vi.fn(),
  relaunch: vi.fn(() => Promise.resolve()),
}));

vi.mock("@tauri-apps/api/core", () => ({ isTauri: native.isTauri }));
vi.mock("@tauri-apps/plugin-updater", () => ({ check: native.check }));
vi.mock("@tauri-apps/plugin-process", () => ({ relaunch: native.relaunch }));

describe("desktop updater adapter", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    native.isTauri.mockReturnValue(true);
  });

  it("projects browser preview as unavailable without invoking the plugin", async () => {
    native.isTauri.mockReturnValue(false);
    const { checkForDesktopUpdate } = await import("@/api/tauri/updater");

    await expect(checkForDesktopUpdate()).resolves.toEqual({ kind: "unavailable" });
    expect(native.check).not.toHaveBeenCalled();
  });

  it("keeps one pending update and reports cumulative download progress", async () => {
    const update = {
      currentVersion: "0.1.0",
      version: "0.2.0",
      date: "2026-09-01T00:00:00Z",
      close: vi.fn(() => Promise.resolve()),
      install: vi.fn(() => Promise.resolve()),
      download: vi.fn(async (onEvent: (event: unknown) => void) => {
        onEvent({ event: "Started", data: { contentLength: 100 } });
        onEvent({ event: "Progress", data: { chunkLength: 40 } });
        onEvent({ event: "Progress", data: { chunkLength: 60 } });
        onEvent({ event: "Finished" });
      }),
    };
    native.check.mockResolvedValue(update);
    const { checkForDesktopUpdate, installPendingDesktopUpdate } = await import(
      "@/api/tauri/updater"
    );

    await expect(checkForDesktopUpdate()).resolves.toEqual({
      kind: "available",
      currentVersion: "0.1.0",
      version: "0.2.0",
      publishedAt: "2026-09-01T00:00:00Z",
    });
    const progress = vi.fn();
    await installPendingDesktopUpdate(progress);

    expect(progress.mock.calls.map(([value]) => value.percent)).toEqual([0, 40, 100, 100]);
    expect(update.install).toHaveBeenCalledOnce();
    expect(update.close).toHaveBeenCalledOnce();
    expect(native.relaunch).not.toHaveBeenCalled();
  });

  it("uses the process plugin only for an explicit post-install relaunch", async () => {
    const { relaunchAfterDesktopUpdate } = await import("@/api/tauri/updater");
    await relaunchAfterDesktopUpdate();
    expect(native.relaunch).toHaveBeenCalledOnce();
  });
});
