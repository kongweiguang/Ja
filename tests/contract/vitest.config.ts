// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { defineConfig } from "vitest/config";

/** 合同 Gate 只加载跨语言 consumer；完整 React 测试统一由根 Vitest 配置扫描独立 tests 目录。 */
export default defineConfig({
  cacheDir: process.env.JA_VITEST_CACHE_DIR ?? join(tmpdir(), "ja-vitest-contract-cache"),
  resolve: {
    alias: { "@": fileURLToPath(new URL("../../apps/desktop/src", import.meta.url)) },
  },
  test: {
    environment: "jsdom",
    include: ["tests/contract/ts_consumer.test.ts"],
    // Keep Vitest from rewriting the existing repository cache; only the OS-temp transform cache is allowed.
    cache: false,
    clearMocks: true,
    restoreMocks: true,
    unstubGlobals: true,
    css: true,
  },
});
