// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { readFile, mkdir, copyFile, writeFile, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(packageRoot, "..", "..");
const target = "win32-x64";
const nativePaths = {
  cli: "runtime/bin/ja.exe",
  appServer: "runtime/bin/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
};

/** 只接受明确的构建产物与源码身份；包名来自私有模板，版本来自根清单。 */
function parseArgs(argv) {
  const allowed = new Set(["--cli", "--app-server", "--native-report", "--source-commit", "--out"]);
  const args = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    if (!allowed.has(flag) || !argv[index + 1] || args[flag]) {
      throw new Error(`invalid or missing value for ${flag ?? "argument"}`);
    }
    args[flag] = argv[index + 1];
  }
  for (const flag of allowed) {
    if (!args[flag]) throw new Error(`missing required ${flag}`);
  }
  if (!/^[0-9a-f]{40}$/.test(args["--source-commit"])) {
    throw new Error("--source-commit must be the exact 40-character source commit");
  }
  return args;
}

/** 校验 PE/COFF 机器类型，防止把 JAR、损坏文件或 ARM 产物标为 Windows x64。 */
async function readWindowsX64Artifact(file) {
  const content = await readFile(file);
  if (content.length < 0x90 || content.toString("ascii", 0, 2) !== "MZ") {
    throw new Error(`${path.basename(file)} is not a Windows executable`);
  }
  const peOffset = content.readUInt32LE(0x3c);
  if (peOffset + 6 > content.length || content.toString("ascii", peOffset, peOffset + 4) !== "PE\0\0"
      || content.readUInt16LE(peOffset + 4) !== 0x8664) {
    throw new Error(`${path.basename(file)} is not a Windows x64 executable`);
  }
  return {
    content,
    sizeBytes: content.length,
    sha256: createHash("sha256").update(content).digest("hex"),
  };
}

/** 原生 App Server 必须与已通过 smoke 的同一源码构建报告完全对应。 */
function verifyNativeReport(report, server, sourceCommit, serverName) {
  const native = report?.artifacts?.nativeExecutable;
  if (report?.schemaVersion !== 1 || report?.product !== "Ja"
      || report?.sourceCommit !== sourceCommit || report?.target?.platform !== "windows"
      || report?.target?.arch !== "x86_64" || report?.toolchain?.nativeImageOnly !== true
      || report?.toolchain?.noFallback !== true || report?.smoke?.status !== "passed"
      || native?.fileName !== serverName || native?.sizeBytes !== server.sizeBytes
      || native?.sha256 !== server.sha256) {
    throw new Error("native App Server report does not match the verified Windows x64 artifact");
  }
}

/** 校验固定 npm 身份与 Ja 版本，并仅创建新输出目录以隔离发布候选。 */
async function stage(args) {
  const rootManifest = JSON.parse(await readFile(path.join(repositoryRoot, "package.json"), "utf8"));
  if (!/^\d+\.\d+\.\d+$/.test(rootManifest.version)) {
    throw new Error("repository version must be a stable x.y.z version");
  }
  const cliPath = path.resolve(args["--cli"]);
  const serverPath = path.resolve(args["--app-server"]);
  const out = path.resolve(args["--out"]);
  if (cliPath === serverPath) throw new Error("CLI and App Server must be different artifacts");
  const [cli, server, report] = await Promise.all([
    readWindowsX64Artifact(cliPath),
    readWindowsX64Artifact(serverPath),
    readFile(path.resolve(args["--native-report"]), "utf8").then(JSON.parse),
  ]);
  verifyNativeReport(report, server, args["--source-commit"], path.basename(serverPath));
  const sourcePackage = JSON.parse(await readFile(path.join(packageRoot, "package.json"), "utf8"));
  if (sourcePackage.private !== true || sourcePackage.name !== "@kongweiguang/ja") {
    throw new Error("source package must be the private @kongweiguang/ja template");
  }
  if (sourcePackage.version !== rootManifest.version) {
    throw new Error("npm template version must match the root Ja version; run pnpm version:sync");
  }
  const publishPackage = {
    ...sourcePackage,
    version: rootManifest.version,
    publishConfig: { access: "public" },
  };
  delete publishPackage.private;
  delete publishPackage.scripts.test;
  await mkdir(path.dirname(out), { recursive: true });
  await mkdir(out, { recursive: false });
  await mkdir(path.join(out, "bin"));
  await mkdir(path.join(out, "third_party"));
  await mkdir(path.join(out, "runtime", "bin", "sidecars"), { recursive: true });
  await copyFile(path.join(packageRoot, "bin", "ja.cjs"), path.join(out, "bin", "ja.cjs"));
  await copyFile(path.join(packageRoot, "bin", "verify.cjs"), path.join(out, "bin", "verify.cjs"));
  await copyFile(path.join(packageRoot, "README.md"), path.join(out, "README.md"));
  await copyFile(path.join(repositoryRoot, "LICENSE"), path.join(out, "LICENSE"));
  await copyFile(path.join(repositoryRoot, "apps", "cli", "NOTICE.md"), path.join(out, "NOTICE.md"));
  await copyFile(path.join(repositoryRoot, "apps", "cli", "third_party", "codex-LICENSE"), path.join(out, "third_party", "codex-LICENSE"));
  await copyFile(cliPath, path.join(out, ...nativePaths.cli.split("/")));
  await copyFile(serverPath, path.join(out, ...nativePaths.appServer.split("/")));
  await writeFile(path.join(out, "package.json"), `${JSON.stringify(publishPackage, null, 2)}\n`);
  await writeFile(path.join(out, "runtime", "manifest.json"), `${JSON.stringify({
    schemaVersion: 1,
    version: rootManifest.version,
    sourceCommit: args["--source-commit"],
    target,
    artifacts: {
      [nativePaths.cli]: { sizeBytes: cli.sizeBytes, sha256: cli.sha256 },
      [nativePaths.appServer]: { sizeBytes: server.sizeBytes, sha256: server.sha256 },
    },
  }, null, 2)}\n`);
  const verify = spawnSync(process.execPath, [path.join(out, "bin", "verify.cjs")], {
    cwd: out, encoding: "utf8", windowsHide: true,
  });
  if (verify.status !== 0) throw new Error(verify.stderr.trim() || "staged package verification failed");
  const staged = await stat(path.join(out, ...nativePaths.appServer.split("/")));
  if (staged.size !== server.sizeBytes) throw new Error("staged native artifact changed size");
  process.stdout.write(`${out}\n`);
}

try {
  await stage(parseArgs(process.argv.slice(2)));
} catch (error) {
  console.error(`Ja npm staging failed: ${error.message}`);
  process.exitCode = 1;
}
