// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { invokeNativeCommand } from "@/api/tauri/nativeInvoke";

describe("native invoke boundary", () => {
  /** 生产边界始终只委派一次，不读取构建环境或页面全局变量。 */
  it("always delegates exactly once", async () => {
    const delegate = vi.fn(async () => "native-result");

    await expect(invokeNativeCommand("ja_fixture", { value: 1 }, delegate)).resolves.toBe(
      "native-result",
    );
    expect(delegate).toHaveBeenCalledOnce();
  });
});
