// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    // 测试必须复用生产构建的别名，否则组件单测会在 CI 中出现与应用不同的模块解析结果。
    alias: {
      "@": fileURLToPath(new URL("./apps/desktop/src", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    // 浏览器能力替身集中放在独立 tests 包，生产 src 不携带任何测试环境分支。
    setupFiles: ["apps/desktop/tests/shared/testEnvironment.ts"],
    // 测试包与生产源码物理隔离，避免 src 重新混入测试体或测试支撑代码。
    include: ["apps/desktop/tests/**/*.{test,spec}.{ts,tsx}"],
    clearMocks: true,
    restoreMocks: true,
    unstubGlobals: true,
    css: true,
  },
});
