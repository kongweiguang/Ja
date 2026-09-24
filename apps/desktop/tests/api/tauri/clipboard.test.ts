// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@tauri-apps/plugin-clipboard-manager", () => ({
  readText: vi.fn(),
  writeText: vi.fn(),
}));

import { readText } from "@tauri-apps/plugin-clipboard-manager";
import { readClipboardText } from "@/api/tauri/clipboard";

describe("readClipboardText", () => {
  /** 每例独立固定一次显式系统读取，避免 clipboard fixture 跨边界复用。 */
  beforeEach(() => {
    vi.mocked(readText).mockReset();
  });

  /** 模块加载不访问系统剪贴板，只有粘贴动作调用 wrapper 才进行一次读取。 */
  it("reads text once when the explicit paste action requests it", async () => {
    vi.mocked(readText).mockResolvedValueOnce("terminal input");

    expect(readText).not.toHaveBeenCalled();
    await expect(readClipboardText()).resolves.toBe("terminal input");
    expect(readText).toHaveBeenCalledOnce();
  });

  /** UTF-8 字节预算允许正好 64 KiB，并拒绝超限文本，避免返回值进入 PTY 输入队列。 */
  it("enforces the 64 KiB UTF-8 terminal paste boundary", async () => {
    const atLimit = "🙂".repeat(16_384);
    vi.mocked(readText).mockResolvedValueOnce(atLimit).mockResolvedValueOnce(`${atLimit}🙂`);

    await expect(readClipboardText()).resolves.toBe(atLimit);
    await expect(readClipboardText()).rejects.toThrow(RangeError);
    expect(readText).toHaveBeenCalledTimes(2);
  });

  /** 系统权限拒绝保持原始 rejection，UI 可以据此给出明确且不含原生细节的可见提示。 */
  it("propagates clipboard read failures", async () => {
    const failure = new Error("clipboard permission denied");
    vi.mocked(readText).mockRejectedValueOnce(failure);

    await expect(readClipboardText()).rejects.toBe(failure);
  });
});
