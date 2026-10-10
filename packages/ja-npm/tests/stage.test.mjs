// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { createRequire } from "node:module";
import { copyFile, mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test, { afterEach } from "node:test";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const stageScript = path.join(packageRoot, "scripts", "stage.mjs");
const sourceCommit = "a".repeat(40);
const fixtureDirectories = new Set();
const require = createRequire(import.meta.url);
const { searchToolContract } = require("../bin/verify.cjs");

/** 生成仅具备机器头的测试产物，验证打包准入但不执行伪造程序。 */
function pe(machine, marker) {
  const buffer = Buffer.alloc(256, marker);
  buffer.write("MZ", 0, "ascii");
  buffer.writeUInt32LE(0x80, 0x3c);
  buffer.write("PE\0\0", 0x80, "ascii");
  buffer.writeUInt16LE(machine, 0x84);
  return buffer;
}

/** 在系统临时目录构造闭合的原生与工具清单，避免测试借用 runner PATH 掩盖漏包。 */
async function fixture(machine = 0x8664) {
  const directory = await mkdtemp(path.join(tmpdir(), "ja-npm-stage-"));
  fixtureDirectories.add(directory);
  const cli = path.join(directory, "ja.exe");
  const server = path.join(directory, "ja-app-server.exe");
  const report = path.join(directory, "build-report.json");
  const searchTools = path.join(directory, "sidecars", "tools");
  await mkdir(searchTools, { recursive: true });
  const cliBytes = pe(machine, 1);
  const serverBytes = pe(0x8664, 2);
  await writeFile(cli, cliBytes);
  await writeFile(server, serverBytes);
  const tools = [];
  for (const expected of searchToolContract) {
    const binaryName = expected.name + ".exe";
    const binaryBytes = pe(0x8664, expected.name === "fd" ? 3 : 4);
    const binaryPath = path.join(searchTools, binaryName);
    await writeFile(binaryPath, binaryBytes);
    const licenses = [];
    for (const sourceName of expected.licenseNames) {
      const fileName = expected.name + "-" + sourceName;
      const licenseBytes = Buffer.from("license fixture for " + fileName, "utf8");
      const licensePath = path.join(searchTools, fileName);
      await writeFile(licensePath, licenseBytes);
      licenses.push({
        sourceName,
        relativePath: "tools/" + fileName,
        fileName,
        sizeBytes: licenseBytes.length,
        sha256: createHash("sha256").update(licenseBytes).digest("hex"),
      });
    }
    tools.push({
      name: expected.name,
      version: expected.version,
      repository: expected.repository,
      releaseTag: expected.releaseTag,
      asset: expected.asset,
      binary: {
        relativePath: "tools/" + binaryName,
        versionOutput: (expected.name === "rg" ? "ripgrep " : "fd ") + expected.version,
        fileName: binaryName,
        sizeBytes: binaryBytes.length,
        sha256: createHash("sha256").update(binaryBytes).digest("hex"),
      },
      licenses,
    });
  }
  await writeFile(path.join(searchTools, "tools-manifest.json"), JSON.stringify({
    schemaVersion: 1,
    product: "Ja",
    stagingMode: "copy",
    sourceCommit,
    target: { platform: "windows", arch: "x86_64", targetTriple: "x86_64-pc-windows-msvc" },
    tools,
  }));
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
  return { directory, cli, server, report, searchTools, out: path.join(directory, "staged") };
}

/** 每项测试后移除大体积 tarball 和原生产物，避免 Windows 本机验收污染用户临时目录。 */
afterEach(async () => {
  await Promise.all([...fixtureDirectories].map((directory) => rm(directory, { recursive: true, force: true })));
  fixtureDirectories.clear();
});

/** 把工具目录作为必需输入，确保 stage 不能静默退回到发布 runner 的 PATH。 */
function runStage(input, extra = []) {
  return spawnSync(process.execPath, [stageScript,
    "--cli", input.cli,
    "--app-server", input.server,
    "--search-tools", input.searchTools,
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
  const expectedToolFiles = ["tools-manifest.json"];
  for (const tool of searchToolContract) {
    expectedToolFiles.push(tool.name + ".exe");
    expectedToolFiles.push(...tool.licenseNames.map((name) => tool.name + "-" + name));
  }
  for (const fileName of expectedToolFiles) {
    const packagePath = "runtime/bin/sidecars/tools/" + fileName;
    const stagedPath = path.join(input.out, ...packagePath.split("/"));
    const sourcePath = path.join(input.searchTools, fileName);
    const stagedBytes = await readFile(stagedPath);
    assert.deepEqual(stagedBytes, await readFile(sourcePath));
    assert.equal(runtimeManifest.artifacts[packagePath].sizeBytes, stagedBytes.length);
    assert.equal(runtimeManifest.artifacts[packagePath].sha256, createHash("sha256").update(stagedBytes).digest("hex"));
  }
  const verify = spawnSync(process.execPath, [path.join(input.out, "bin", "verify.cjs")], { encoding: "utf8" });
  assert.equal(verify.status, 0, verify.stderr);
  const pack = runNpm("pack --dry-run --json", input.out);
  assert.equal(pack.status, 0, pack.stderr);
  const packed = new Set(JSON.parse(pack.stdout)[0].files.map((file) => file.path));
  for (const file of [
    "package.json", "README.md", "LICENSE", "NOTICE.md", "third_party/codex-LICENSE", "bin/ja.cjs", "bin/verify.cjs",
    "runtime/manifest.json", "runtime/bin/ja.exe",
    "runtime/bin/sidecars/ja-app-server-x86_64-pc-windows-msvc.exe",
    ...expectedToolFiles.map((fileName) => "runtime/bin/sidecars/tools/" + fileName),
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
  assert.ok((await stat(path.join(moduleRoot, "runtime", "bin", "sidecars", "tools", "fd.exe"))).isFile());
  assert.ok((await stat(path.join(moduleRoot, "runtime", "bin", "sidecars", "tools", "rg.exe"))).isFile());
  if (process.platform === "win32") {
    assert.ok((await stat(path.join(install, "node_modules", ".bin", "ja.cmd"))).isFile());
  }
});

/** 丢失一个 manifest 工具时必须在创建包目录前失败，防止 PATH 偶然补齐发布输入。 */
test("stage rejects an incomplete pinned search-tool manifest", async () => {
  const input = await fixture();
  const manifestPath = path.join(input.searchTools, "tools-manifest.json");
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  manifest.tools = manifest.tools.filter((tool) => tool.name !== "rg");
  await writeFile(manifestPath, JSON.stringify(manifest));
  const result = runStage(input);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /exactly the pinned fd and rg tools/);
  await assert.rejects(stat(input.out), { code: "ENOENT" });
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

/** 包内搜索二进制必须与工具 manifest 和 runtime manifest 双重摘要一致。 */
test("publish verification rejects a changed packaged search tool", async () => {
  const input = await fixture();
  assert.equal(runStage(input).status, 0);
  const stagedRg = path.join(input.out, "runtime", "bin", "sidecars", "tools", "rg.exe");
  const changed = await readFile(stagedRg);
  changed[100] ^= 1;
  await writeFile(stagedRg, changed);
  const verify = spawnSync(process.execPath, [path.join(input.out, "bin", "verify.cjs")], { encoding: "utf8" });
  assert.notEqual(verify.status, 0);
  assert.match(verify.stderr, /does not match its manifest/);
});
