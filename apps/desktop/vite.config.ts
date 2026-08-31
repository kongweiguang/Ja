// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { fileURLToPath } from "node:url";
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

const host = process.env.TAURI_DEV_HOST;
const requestedDevPort = Number.parseInt(process.env.JA_E2E_DEV_PORT ?? "1420", 10);
const devPort =
  Number.isInteger(requestedDevPort) && requestedDevPort >= 1 && requestedDevPort <= 65_535
    ? requestedDevPort
    : 1420;
const e2eDevServer = process.env.JA_E2E_DEV_PORT !== undefined;
const productionTauriAdapterRoot = fileURLToPath(new URL("./src/api/tauri", import.meta.url));
const e2eEntryPath = "/tests/app/e2e/main.tsx";
const e2eNativeInvokePath = fileURLToPath(
  new URL("./tests/app/e2e/nativeInvoke.ts", import.meta.url),
);

/**
 * 将 Vite 的 Windows 路径统一为可比较形式；只用于识别精确的生产 adapter 目录，
 * 避免宽泛 alias 把其它同名模块误替换成测试支撑。
 */
function comparableVitePath(value: string): string {
  return value
    .split("?", 1)[0]!
    .replaceAll("\\", "/")
    .replace(/^\/(?=[A-Za-z]:\/)/u, "")
    .toLowerCase();
}

/**
 * E2E 使用 tests 包内的 composition entry 和 invoke probe；生产源码无需感知测试环境，
 * 普通 dev/build 也不会解析或打包这些模块。精确 importer 限制防止替换扩散到 feature。
 */
function createE2eCompositionPlugin(): Plugin {
  const comparableAdapterRoot = comparableVitePath(productionTauriAdapterRoot);
  return {
    name: "ja-e2e-composition",
    enforce: "pre",
    /** 只替换已知 HTML 入口，入口漂移时立即失败，不能悄悄退回生产 composition。 */
    transformIndexHtml(html) {
      const productionEntry = '<script type="module" src="/src/main.tsx"></script>';
      if (!html.includes(productionEntry)) {
        throw new Error("Ja E2E production entry marker is missing");
      }
      return html.replace(productionEntry, `<script type="module" src="${e2eEntryPath}"></script>`);
    },
    /** 只把 Tauri adapter 的相对 invoke 边界换成测试观察器，不改变其它模块解析。 */
    resolveId(source, importer) {
      if (source !== "./nativeInvoke" || importer === undefined) return null;
      const comparableImporter = comparableVitePath(importer);
      if (!comparableImporter.startsWith(`${comparableAdapterRoot}/`)) return null;
      return e2eNativeInvokePath;
    },
  };
}

// 保留异步配置入口，是为了让 Tauri 开发环境可以读取外部注入的 HMR 主机并保持浏览器预览配置一致。
export default defineConfig(async () => ({
  // 桌面应用以自身目录作为构建根，但产物统一输出到 Tauri `frontendDist`
  // 指向的仓库级 dist，避免开发预览与原生打包读取不同资源。
  root: fileURLToPath(new URL(".", import.meta.url)),
  plugins: [react(), ...(e2eDevServer ? [createE2eCompositionPlugin()] : [])],

  resolve: {
    // 统一 UI 层的导入根，避免后续组件在相对路径深层嵌套时产生脆弱依赖。
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },

  build: {
    // Windows WebView2 与 macOS WKWebView 均支持该目标，避免引入不必要的旧浏览器转译成本。
    target: "es2022",
    outDir: "../../dist",
    emptyOutDir: true,
  },

  // 下列开发服务器约束只服务 Tauri dev/build，确保原生宿主与 Vite 使用同一入口。
  //
  // 保留 Rust 错误输出，避免 Vite 清屏掩盖原生编译失败。
  clearScreen: false,
  // Tauri 依赖固定端口；端口被占用时立即失败，不能静默切换到错误页面。
  server: {
    port: devPort,
    strictPort: true,
    host: host || false,
    hmr: e2eDevServer
      ? false
      : host
        ? {
            protocol: "ws",
            host,
            port: 1421,
          }
        : undefined,
    watch: {
      // Rust 变更由 Cargo 负责；Vite 忽略 `src-tauri` 可避免重复重载与资源竞争。
      ignored: e2eDevServer ? ["**/*"] : ["**/src-tauri/**"],
    },
  },
}));
