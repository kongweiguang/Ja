// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { defineConfig } from "vitest/config";

/** 合同门禁只加载跨语言校验端；完整 React 测试统一由根 Vitest 配置扫描独立 tests 目录。 */
export default defineConfig({
  cacheDir: process.env.JA_VITEST_CACHE_DIR ?? join(tmpdir(), "ja-vitest-contract-cache"),
  resolve: {
    alias: { "@": fileURLToPath(new URL("../../apps/desktop/src", import.meta.url)) },
  },
  test: {
    environment: "jsdom",
    include: ["tests/contract/ts_consumer.test.ts"],
    // 避免 Vitest 改写仓库现有缓存，只允许使用系统临时目录中的转换缓存。
    cache: false,
    clearMocks: true,
    restoreMocks: true,
    unstubGlobals: true,
    css: true,
  },
});
