// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const acceptanceScript = path.join(repositoryRoot, "scripts", "e2e", "file-search-acceptance.mjs");

/** 要求 workflow 明确给出 npm 安装目录和仓库外证据目录，避免误测 staging 中的文件。 */
function parseArgs(argv) {
  const allowed = new Set(["--package-root", "--evidence-directory"]);
  const args = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    if (!allowed.has(flag) || !argv[index + 1] || args[flag]) {
      throw new Error("invalid or missing value for " + (flag ?? "argument"));
    }
    args[flag] = argv[index + 1];
  }
  for (const flag of allowed) {
    if (!args[flag]) throw new Error("missing required " + flag);
  }
  return {
    packageRoot: path.resolve(args["--package-root"]),
    evidenceDirectory: path.resolve(args["--evidence-directory"]),
  };
}

/** 保留子进程真实退出码；Windows runner 的 Node/PowerShell 初始化错误不能算作 smoke 成功。 */
function runStep(label, executable, args) {
  const result = spawnSync(executable, args, { stdio: "inherit", windowsHide: true });
  if (result.error) throw new Error(label + " could not start: " + result.error.message);
  if (result.status !== 0) throw new Error(label + " failed with exit code " + result.status);
}

/** 校验真实安装包后复用 JA-RPC 文件搜索验收，并从 PATH 移除 fd/rg 证明解析到包内工具。 */
function verifyRuntime(options) {
  const packageRoot = options.packageRoot;
  const installedLauncher = path.join(packageRoot, "bin", "ja.cjs");
  const installedServer = path.join(
    packageRoot,
    "runtime",
    "bin",
    "sidecars",
    "ja-app-server-x86_64-pc-windows-msvc.exe",
  );
  runStep("installed npm package integrity", process.execPath, [
    path.join(packageRoot, "bin", "verify.cjs"),
  ]);
  runStep("installed Ja launcher", process.execPath, [installedLauncher, "--help"]);
  runStep("installed App Server file-search acceptance", process.execPath, [
    acceptanceScript,
    "--executable",
    installedServer,
    "--evidence-directory",
    options.evidenceDirectory,
    "--stress-files",
    "1000",
    "--strip-search-tools",
    "--silent",
  ]);
  process.stdout.write("JA_NPM_RUNTIME_SMOKE_OK\n");
}

/** 主入口只把验证失败收敛为非零状态，便于 release workflow 将其作为发布门禁。 */
function main() {
  try {
    verifyRuntime(parseArgs(process.argv.slice(2)));
  } catch (error) {
    process.stderr.write("Ja npm runtime smoke failed: " + error.message + "\n");
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main();
}
