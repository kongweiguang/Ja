// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test, { afterEach } from "node:test";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const stageScript = path.join(packageRoot, "scripts", "stage.mjs");
const sourceCommit = "a".repeat(40);
const fixtureDirectories = new Set();

/** 生成仅具备机器头的测试产物，验证打包准入但不执行伪造程序。 */
function pe(machine, marker) {
  const buffer = Buffer.alloc(256, marker);
  buffer.write("MZ", 0, "ascii");
  buffer.writeUInt32LE(0x80, 0x3c);
  buffer.write("PE\0\0", 0x80, "ascii");
  buffer.writeUInt16LE(machine, 0x84);
  return buffer;
}

/** 让每个测试在系统临时目录隔离输入、报告和输出，不触碰仓库并行修改。 */
async function fixture(machine = 0x8664) {
  const directory = await mkdtemp(path.join(tmpdir(), "ja-npm-stage-"));
  fixtureDirectories.add(directory);
  const cli = path.join(directory, "ja.exe");
  const server = path.join(directory, "ja-app-server.exe");
  const report = path.join(directory, "build-report.json");
  const cliBytes = pe(machine, 1);
  const serverBytes = pe(0x8664, 2);
  await writeFile(cli, cliBytes);
  await writeFile(server, serverBytes);
  await writeFile(report, JSON.stringify({
    schemaVersion: 1,
    product: "Ja",
    sourceCommit,
    target: { platform: "windows", arch: "x86_64" },
    toolchain: { nativeImageOnly: true, noFallback: true },
    smoke: { status: "passed" },
    artifacts: {
      nativeExecutable: {
        fileName: "ja-app-server.exe",
        sizeBytes: serverBytes.length,
        sha256: createHash("sha256").update(serverBytes).digest("hex"),
      },
    },
  }));
  return { directory, cli, server, report, out: path.join(directory, "staged") };
}

/** 每项测试后移除大体积 tarball 和原生产物，避免 Windows 本机验收污染用户临时目录。 */
afterEach(async () => {
  await Promise.all([...fixtureDirectories].map((directory) => rm(directory, { recursive: true, force: true })));
  fixtureDirectories.clear();
});

/** 故意只测打包进程边界，确保实际发布命令所用参数和错误码受验收。 */
function runStage(input, extra = []) {
  return spawnSync(process.execPath, [stageScript,
    "--cli", input.cli,
    "--app-server", input.server,
    "--native-report", input.report,
    "--source-commit", sourceCommit,
    "--out", input.out,
    ...extra,
  ], { encoding: "utf8" });
}

/** npm.cmd 仅执行固定或已验证的本地测试命令，保持 Windows 与 Unix 验收入口一致。 */
function runNpm(command, cwd) {
  return process.platform === "win32"
    ? spawnSync("cmd.exe", ["/d", "/c", `npm ${command}`], { cwd, encoding: "utf8" })
    : spawnSync("npm", command.split(" "), { cwd, encoding: "utf8" });
}

/** 一次成功 staging 必须带上 CLI、匹配的 App Server、版本与可重新校验的摘要。 */
test("stage creates a complete public Windows x64 package", async () => {
  const input = await fixture();
  const result = runStage(input);
  assert.equal(result.status, 0, result.stderr);
  const pkg = JSON.parse(await readFile(path.join(input.out, "package.json"), "utf8"));
  assert.equal(pkg.name, "@kongweiguang/ja");
  assert.equal(pkg.private, undefined);
  assert.equal(pkg.bin.ja, "bin/ja.cjs");
  assert.equal(pkg.os[0], "win32");
  const rootPackage = JSON.parse(await readFile(path.join(packageRoot, "..", "..", "package.json"), "utf8"));
  assert.equal(pkg.version, rootPackage.version);
  const runtimeManifest = JSON.parse(await readFile(path.join(input.out, "runtime", "manifest.json"), "utf8"));
  assert.equal(runtimeManifest.version, rootPackage.version);
  assert.deepEqual(
    await readFile(path.join(input.out, "NOTICE.md")),
    await readFile(path.join(packageRoot, "..", "..", "apps", "cli", "NOTICE.md")),
  );
  assert.deepEqual(
    await readFile(path.join(input.out, "third_party", "codex-LICENSE")),
    await readFile(path.join(packageRoot, "..", "..", "apps", "cli", "third_party", "codex-LICENSE")),
  );
  const stagedCli = await readFile(path.join(input.out, "runtime", "bin", "ja.exe"));
  const stagedServer = await readFile(path.join(input.out, "runtime", "bin", "sidecars", "ja-app-server-x86_64-pc-windows-msvc.exe"));
  assert.deepEqual(stagedCli, await readFile(input.cli));
  assert.deepEqual(stagedServer, await readFile(input.server));
  const verify = spawnSync(process.execPath, [path.join(input.out, "bin", "verify.cjs")], { encoding: "utf8" });
  assert.equal(verify.status, 0, verify.stderr);
  const pack = runNpm("pack --dry-run --json", input.out);
  assert.equal(pack.status, 0, pack.stderr);
  const packed = new Set(JSON.parse(pack.stdout)[0].files.map((file) => file.path));
  for (const file of [
    "package.json", "README.md", "LICENSE", "NOTICE.md", "third_party/codex-LICENSE", "bin/ja.cjs", "bin/verify.cjs",
    "runtime/manifest.json", "runtime/bin/ja.exe",
    "runtime/bin/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
  ]) {
    assert.ok(packed.has(file), `npm tarball omits ${file}`);
  }
  if (process.platform !== "win32" || process.arch !== "x64") return;
  const realPack = runNpm("pack --json", input.out);
  assert.equal(realPack.status, 0, realPack.stderr);
  const archiveName = JSON.parse(realPack.stdout)[0].filename;
  assert.match(archiveName, /^[A-Za-z0-9._-]+\.tgz$/);
  const install = path.join(input.directory, "install");
  await mkdir(install);
  await copyFile(path.join(input.out, archiveName), path.join(install, archiveName));
  const installed = runNpm(`install --offline --ignore-scripts --no-audit --no-fund ${archiveName}`, install);
  assert.equal(installed.status, 0, installed.stderr);
  const moduleRoot = path.join(install, "node_modules", "@kongweiguang", "ja");
  assert.ok((await stat(path.join(moduleRoot, "runtime", "bin", "ja.exe"))).isFile());
  assert.ok((await stat(path.join(moduleRoot, "runtime", "bin", "sidecars", "ja-app-server-x86_64-pc-windows-msvc.exe"))).isFile());
  if (process.platform === "win32") {
    assert.ok((await stat(path.join(install, "node_modules", ".bin", "ja.cmd"))).isFile());
  }
});

/** 错架构输入必须在创建发布目录前失败，防止 CI 留下貌似完整的包。 */
test("stage rejects an ARM CLI mislabeled as Windows x64", async () => {
  const input = await fixture(0xaa64);
  const result = runStage(input);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /not a Windows x64 executable/);
});

/** 报告与文件不一致时，即使 PE 机器头合法也不能进入发布包。 */
test("stage rejects a mismatched Native Image report", async () => {
  const input = await fixture();
  const report = JSON.parse(await readFile(input.report, "utf8"));
  report.artifacts.nativeExecutable.sha256 = "0".repeat(64);
  await writeFile(input.report, JSON.stringify(report));
  const result = runStage(input);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /report does not match/);
});

/** staging 后的任意原生产物替换都必须在 prepublish 校验时被发现。 */
test("publish verification rejects a changed native executable", async () => {
  const input = await fixture();
  assert.equal(runStage(input).status, 0);
  const stagedCli = path.join(input.out, "runtime", "bin", "ja.exe");
  const changed = await readFile(stagedCli);
  changed[100] ^= 1;
  await writeFile(stagedCli, changed);
  const verify = spawnSync(process.execPath, [path.join(input.out, "bin", "verify.cjs")], { encoding: "utf8" });
  assert.notEqual(verify.status, 0);
  assert.match(verify.stderr, /native artifact verification failed/);
});
