// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { run } from "@tauri-apps/cli";

/**
 * 根命令统一定位桌面应用，避免 Tauri 自动发现其它应用或旧产物；
 * 配置文件参数按调用方 cwd 解析后再切换目录；内联 JSON 和用户环境保持原样，
 * 构建钩子显式回到 workspace 根。
 */
async function main() {
  const args = process.argv.slice(2);
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--config" || argument === "-c") {
      const value = args[index + 1];
      if (value && !value.trimStart().startsWith("{") && !value.startsWith("-")) {
        args[index + 1] = resolve(value);
      }
      index += 1;
    } else if (argument.startsWith("--config=")) {
      const value = argument.slice("--config=".length);
      if (value && !value.trimStart().startsWith("{")) {
        args[index] = `--config=${resolve(value)}`;
      }
    }
  }
  process.chdir(fileURLToPath(new URL("../../apps/desktop/src-tauri", import.meta.url)));
  await run(args, "pnpm tauri");
}

// CLI 失败保留真实诊断和非零退出码，不能让根命令误报构建成功。
main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
