// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from "vitest";
import { exportPublicText } from "@/features/conversation/ui/timeline/exportPublicText";

describe("exportPublicText", () => {
  afterEach(() => {
    Reflect.deleteProperty(window, "showSaveFilePicker");
  });

  /** 导出按 UTF-16 安全边界分片，完整 Unicode 文本写入用户选择的浏览器原生句柄。 */
  it("streams complete text without splitting a surrogate pair", async () => {
    const chunks: Uint8Array[] = [];
    const close = vi.fn(async () => {});
    const picker = vi.fn(async () => ({
      createWritable: async () => ({
        write: async (chunk: Uint8Array) => {
          chunks.push(chunk);
        },
        close,
      }),
    }));
    Object.defineProperty(window, "showSaveFilePicker", { configurable: true, value: picker });
    const content = `${"a".repeat(32_767)}😀终`;
    await expect(exportPublicText(content, "Ja-回复.txt")).resolves.toBe("saved");
    expect(picker).toHaveBeenCalledOnce();
    expect(close).toHaveBeenCalledOnce();
    expect(chunks.length).toBe(2);
    expect(new TextDecoder().decode(Uint8Array.from(chunks.flatMap((chunk) => [...chunk])))).toBe(
      content,
    );
  });

  /** 原生保存面板取消或平台缺席是明确结果，不制造残留文件或伪成功反馈。 */
  it("distinguishes cancellation from unsupported platforms", async () => {
    await expect(exportPublicText("正文", "Ja-回复.txt")).resolves.toBe("unsupported");
    Object.defineProperty(window, "showSaveFilePicker", {
      configurable: true,
      value: async () => {
        throw new DOMException("cancel", "AbortError");
      },
    });
    await expect(exportPublicText("正文", "Ja-回复.txt")).resolves.toBe("cancelled");
  });
});
