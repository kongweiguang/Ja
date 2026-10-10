// @author kongweiguang
import { fileURLToPath } from "node:url";
import path from "node:path";
import { env } from "node:process";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const root = path.dirname(fileURLToPath(import.meta.url));

/** Maven 提供独立的类目录；直接用 Vite 构建时使用前端标准 dist 目录。 */
export default defineConfig({
  root,
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: { "/api": { target: env.JA_EXAMPLE_BACKEND_URL ?? "http://127.0.0.1:8087", changeOrigin: false } },
  },
  build: {
    outDir: env.JA_SPRING_BUILD_OUTPUT_DIR ?? "dist",
    emptyOutDir: true,
    sourcemap: false,
  },
});
