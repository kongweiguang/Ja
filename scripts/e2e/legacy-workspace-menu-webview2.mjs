// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* global document */

/** V6 旧共享会话只在随机 Temp Ja home 中升级，真窗逐项点击新旧两个文件夹入口。 */
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile, readdir, mkdir, stat } from "node:fs/promises";
import { dirname, join, relative, resolve, sep } from "node:path";
import { promisify } from "node:util";
import { fileURLToPath, pathToFileURL } from "node:url";
import process from "node:process";
import { runProduction } from "./review-redesign-production.mjs";
import { installSessionWorkspaceProbe } from "./session-workspaces-webview2.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const fixtureSource = join(repoRoot, "scripts/e2e/java/io/github/kongweiguang/ja/infrastructure/persistence/database/LegacyWorkspaceMenuFixture.java");
const fixtureClass = "io.github.kongweiguang.ja.infrastructure.persistence.database.LegacyWorkspaceMenuFixture";
const threadId = "thr_legacy_menu";
const title = "Legacy Menu Thread";
const expectedOldFile = Buffer.from("legacy file stays in the shared folder\r\n", "utf8");

/** 编译和运行真实 V6 Flyway fixture；强制 home 归属于本轮 Temp profile。 */
async function prepareLegacyFixture({ root, home, runtime, java }, classpathFile) {
  const temp = resolve(process.env.TEMP ?? process.env.TMP ?? "");
  const runRoot = resolve(root);
  const relation = relative(temp, runRoot);
  assert.ok(relation && !relation.startsWith(`..${sep}`) && relation !== "..");
  assert.match(runRoot.split(/[\\/]/u).at(-1) ?? "", /^ja-review-redesign-/u);
  assert.equal(resolve(home), resolve(root, "profile", ".ja"));
  const dependencies = (await readFile(classpathFile, "utf8")).trim();
  assert.ok(dependencies.length > 0, "Maven runtime classpath is required");
  const classes = join(runtime, "legacy-fixture-classes");
  await mkdir(classes, { recursive: true });
  const classpath = [join(repoRoot, "app-server", "target", "classes"), dependencies].join(";");
  const javac = join(dirname(java), "javac.exe");
  await execFileAsync(javac, ["-cp", classpath, "-d", classes, fixtureSource], {
    cwd: repoRoot,
    windowsHide: true,
    timeout: 60_000,
    maxBuffer: 128 * 1024,
  });
  await execFileAsync(java, ["-cp", [classes, classpath].join(";"), fixtureClass, home], {
    cwd: repoRoot,
    windowsHide: true,
    timeout: 60_000,
    maxBuffer: 128 * 1024,
  });
}

/** 保存展开态以检查旧文件入口是否易懂，再以 ID-only IPC 核对原生打开动作。 */
async function clickFolderMenu(page, workspaceId, action, deadline, evidenceDirectory) {
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.hover({ timeout: deadline - Date.now() });
  await page.getByRole("button", { name: `对话菜单：${title}`, exact: true }).click({
    timeout: deadline - Date.now(),
  });
  const menu = page.getByRole("menuitem", { name: action, exact: true });
  await menu.waitFor({ state: "visible", timeout: deadline - Date.now() });
  await page.screenshot({
    path: join(evidenceDirectory, action === "打开旧共享文件夹" ? "02-legacy-menu.png" : "01-work-folder-menu.png"),
    animations: "disabled",
  });
  const before = await page.evaluate(() =>
    (globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? []).filter(
      (entry) => entry.command === "ja_workspace_open",
    ).length,
  );
  await menu.click({ timeout: deadline - Date.now() });
  await page.waitForFunction(
    ({ count, id }) => {
      const entries = (globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? []).filter(
        (entry) => entry.command === "ja_workspace_open",
      );
      return entries.length > count && entries.at(-1)?.phase === "resolved" &&
        entries.at(-1)?.workspaceId === id;
    },
    { count: before, id: workspaceId },
    { timeout: deadline - Date.now() },
  );
}

/** 验证启动迁移、空 SESSION 目录、备份和新旧菜单的原生路由。 */
export async function runLegacyWorkspaceMenuWebView2({ page, isolatedRuntimeHome, evidenceDirectory }) {
  const deadline = Date.now() + 3 * 60_000;
  const errors = [];
  page.on("pageerror", (error) => errors.push(String(error?.message ?? error).slice(0, 500)));
  await page.context().addInitScript(installSessionWorkspaceProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: deadline - Date.now() });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({ state: "visible", timeout: deadline - Date.now() });
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: deadline - Date.now(),
  });
  const history = page.getByRole("list", { name: "最近对话列表", exact: true });
  await history.getByText(title, { exact: true }).waitFor({ state: "visible", timeout: deadline - Date.now() });
  const thread = await page.evaluate(async () => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const result = await createHistoryAdapter().threadList({ workspaceKind: "session", limit: 200 });
    return result.items.find((item) => item.threadId === "thr_legacy_menu");
  });
  assert.equal(thread?.workspaceKind, "session");
  assert.ok(thread.legacySharedWorkspaceId);
  assert.notEqual(thread.workspaceId, thread.legacySharedWorkspaceId);
  const oldRoot = join(isolatedRuntimeHome, "data", "general-workspace");
  const sessionRoot = join(isolatedRuntimeHome, "workspaces", threadId);
  assert.deepEqual(await readdir(sessionRoot), []);
  const oldFile = join(oldRoot, "old-file.txt");
  const originalHash = createHash("sha256").update(await readFile(oldFile)).digest("hex");
  assert.equal(originalHash, createHash("sha256").update(expectedOldFile).digest("hex"));
  const backups = (await readdir(join(isolatedRuntimeHome, "data"))).filter((name) =>
    /^ja\.db\.pre-v7-.+\.bak$/u.test(name),
  );
  assert.equal(backups.length, 1);
  assert.ok((await stat(join(isolatedRuntimeHome, "data", backups[0]))).size > 0);

  const row = history.locator(`button[data-thread-id="${threadId}"]`);
  await row.click({ timeout: deadline - Date.now() });
  await page.waitForFunction(
    (id) => document.querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
      ?.getAttribute("data-thread-id") === id,
    threadId,
    { timeout: deadline - Date.now() },
  );
  await clickFolderMenu(page, thread.workspaceId, "打开工作文件夹", deadline, evidenceDirectory);
  await clickFolderMenu(page, thread.legacySharedWorkspaceId, "打开旧共享文件夹", deadline, evidenceDirectory);
  assert.deepEqual(await readdir(sessionRoot), []);
  assert.equal(createHash("sha256").update(await readFile(oldFile)).digest("hex"), originalHash);
  assert.deepEqual(errors, []);
  await page.screenshot({ path: join(evidenceDirectory, "legacy-workspace-menu.png"), animations: "disabled" });
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    verdict: "PASS",
    migratedSession: true,
    emptySessionDirectory: true,
    databaseBackup: true,
    workspaceFolderRoute: true,
    legacyFolderRoute: true,
    oldFileUnchanged: true,
    pageErrors: errors,
  };
}

/** 报告必须包含真实旧库升级与两个菜单点击，不接受仅组件测试。 */
export function validateLegacyWorkspaceMenuReport(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.runtime, "tauri_webview2");
  assert.equal(report?.verdict, "PASS");
  for (const key of ["migratedSession", "emptySessionDirectory", "databaseBackup", "workspaceFolderRoute", "legacyFolderRoute", "oldFileUnchanged"]) {
    assert.equal(report?.[key], true, key);
  }
  assert.deepEqual(report?.pageErrors, []);
  return report;
}

/** JDK25 与依赖 classpath 由调用方显式给出，所有输出保持在独立 evidence 目录。 */
export function parseArguments(argv) {
  const options = { javaHome: "C:\\Users\\24052\\.jdks\\liberica-25.0.2" };
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) throw new Error(`missing value for ${name}`);
    if (name === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (name === "--jar") options.jar = resolve(value);
    else if (name === "--classpath-file") options.classpathFile = resolve(value);
    else if (name === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else throw new Error(`unknown argument: ${name}`);
  }
  for (const key of ["evidenceDirectory", "jar", "classpathFile"]) {
    if (options[key] === undefined) throw new Error(`${key} is required`);
  }
  return options;
}

/** 复用隔离启动器，在首次 Java 启动之前注入 V6 数据副本。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const report = await runProduction({
    ...options,
    scope: "git",
    fixture: "no-head",
    ignoredFiles: 0,
    untrackedFiles: 0,
    prepareIsolatedHome: (context) => prepareLegacyFixture(context, options.classpathFile),
    driver: runLegacyWorkspaceMenuWebView2,
    validateReport: validateLegacyWorkspaceMenuReport,
    reportFileName: "legacy-workspace-menu-report.json",
    prewarmWebview: true,
  });
  console.log(`JA_LEGACY_WORKSPACE_MENU_PASS ${JSON.stringify({ verdict: report.verdict })}`);
}

if (process.argv[1] !== undefined && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    console.error(`JA_LEGACY_WORKSPACE_MENU_FAIL ${String(error?.message ?? error).slice(0, 2000)}`);
    process.exitCode = 1;
  });
}
