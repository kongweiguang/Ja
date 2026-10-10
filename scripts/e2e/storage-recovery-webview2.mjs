// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { createServer } from "node:http";
import { mkdir, mkdtemp, readFile, readdir, realpath, rm, rmdir, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { DatabaseSync } from "node:sqlite";
import {
  buildLaunchEnvironment, createReviewGitFixture, runProduction, writeIsolatedSettings,
} from "./review-redesign-production.mjs";
import { responseStream } from "./conversation-recovery-fixture.mjs";
import { createLargePngFixture, pasteImageAttachment } from "./image-history-production.mjs";

const JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const REPLY = "JA STORAGE RECOVERY VERIFIED";
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const execFileAsync = promisify(execFile);

/** 显式选择 debug JAR 或已暂存 Native EXE，禁止同一次验收混用两条启动链路。 */
function argumentsFor(argv) {
  const values = Object.fromEntries(Array.from({ length: argv.length / 2 }, (_, index) => [argv[index * 2], argv[index * 2 + 1]]));
  const nativeSidecarDirectory = process.env.JA_E2E_NATIVE_SIDECAR_DIRECTORY?.trim();
  assert.ok(values["--evidence-directory"]);
  assert.notEqual(Boolean(values["--jar"]), Boolean(nativeSidecarDirectory), "select exactly one sidecar artifact");
  if (nativeSidecarDirectory !== undefined) assert.ok(isAbsolute(nativeSidecarDirectory), "Native stage must be absolute");
  const scenario = values["--scenario"] ?? "corrupt";
  assert.ok(["v15", "corrupt", "blocked"].includes(scenario));
  if (scenario === "v15") assert.ok(values["--fixture"]);
  return { scenario, jar: values["--jar"] === undefined ? undefined : resolve(values["--jar"]),
    nativeSidecarDirectory: nativeSidecarDirectory === undefined ? undefined : resolve(nativeSidecarDirectory),
    fixture: values["--fixture"], evidenceDirectory: resolve(values["--evidence-directory"]) };
}

/** 只记录计数与已完成工具数量，不把用户正文复制到验收报告。 */
function databaseFacts(path) {
  const db = new DatabaseSync(path, { readOnly: true });
  try {
    return Object.fromEntries(["threads", "messages", "attachments", "tools"].map((name) => [name, db.prepare(`SELECT count(*) AS count FROM ${name}`).get().count]));
  } finally { db.close(); }
}

/** SQLite 自身生成包含已提交 WAL 的一致副本，后续修改仅限隔离 home。 */
async function prepareHome(home, options) {
  const data = join(home, "data");
  await mkdir(data, { recursive: true });
  const path = join(data, "ja.db");
  if (options.scenario === "v15") {
    const source = new DatabaseSync(resolve(options.fixture), { readOnly: true });
    try { source.prepare("VACUUM INTO ?").run(path); } finally { source.close(); }
    const db = new DatabaseSync(path);
    try {
      db.prepare("UPDATE workspaces SET root_path=? WHERE kind='LEGACY_SHARED'").run(join(data, "general-workspace"));
    } finally { db.close(); }
    await mkdir(join(data, "general-workspace"), { recursive: true });
    options.before = databaseFacts(path);
  } else if (options.scenario === "corrupt") {
    await writeFile(path, "JA CORRUPT DATABASE FIXTURE");
  } else {
    await mkdir(path);
    await writeFile(join(path, "obstruction"), "owned fixture");
  }
}

/** 保持完整 Responses 信封以经过生产解析；仅原生结构化 Tool 帧触发真实 shell。 */
function shellStream(ordinal) {
  const item = { id: `fc_storage_${ordinal}`, type: "function_call", call_id: `call_storage_${ordinal}`, name: "exec_command",
    arguments: JSON.stringify({ cmd: "Add-Content -LiteralPath storage-recovery-tool.txt -Value 'executed'; Get-Content -LiteralPath storage-recovery-tool.txt", timeout_ms: 5000 }) };
  const response = { id: `resp_storage_${ordinal}`, object: "response", created_at: 0, model: "review-e2e",
    status: "completed", parallel_tool_calls: false, tool_choice: "auto", tools: [], output: [item],
    usage: { input_tokens: 300, output_tokens: 12, total_tokens: 312, input_tokens_details: { cached_tokens: 0 }, output_tokens_details: { reasoning_tokens: 0 } } };
  return [["response.created", { response: { ...response, status: "in_progress", output: [], usage: undefined } }],
    ["response.output_item.added", { output_index: 0, item: { ...item, arguments: "" } }],
    ["response.function_call_arguments.done", { item_id: item.id, output_index: 0, arguments: item.arguments }],
    ["response.output_item.done", { output_index: 0, item }], ["response.completed", { response }]]
    .map(([type, payload], sequence_number) => `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number, ...payload })}\n\n`).join("");
}

/** 所有模型请求只发往本机；工具结果返回后才生成终态回复，不产生付费调用。 */
async function providerFixture() {
  let ordinal = 0;
  let toolRequests = 0;
  const server = createServer(async (request, response) => {
    try {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const inputs = payload.input ?? [];
      const user = inputs.filter((item) => item.role === "user").at(-1);
      const prompt = JSON.stringify(user?.content ?? "");
      const title = JSON.stringify(inputs).includes("<assistant_reply>");
      const needsTool = !title && prompt.includes("JA STORAGE FILE IO") && !inputs.some((item) => item.type === "function_call_output");
      ordinal += 1;
      if (needsTool) toolRequests += 1;
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-store" });
      response.end(needsTool ? shellStream(ordinal) : responseStream(title ? "Storage recovery" : REPLY, `storage_${ordinal}`));
    } catch {
      response.writeHead(400); response.end();
    }
  });
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  return { baseUrl: `http://127.0.0.1:${server.address().port}/v1`, toolRequests: () => toolRequests,
    close: async () => { server.closeAllConnections(); await new Promise((done) => server.close(done)); } };
}

/** 只接受权威状态变化，避免固定等待把尚未就绪当作成功。 */
async function until(label, predicate, timeoutMs = 45000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((done) => setTimeout(done, 100));
  }
  throw new Error(`${label} timed out`);
}

/** 在浏览器内保留 typed adapter 的安全错误码，避免 Playwright 跨进程包装抹掉启动失败原因。 */
async function storage(page) {
  const result = await page.evaluate(async () => {
    try {
      const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
      return { info: await createRuntimeHostAdapter().storageInfo() };
    } catch (error) {
      return { errorCode: typeof error?.code === "string" ? error.code : "UNKNOWN" };
    }
  });
  if (result.info !== undefined) return result.info;
  const failure = new Error("storage_info failed");
  failure.code = result.errorCode;
  throw failure;
}

/** Journal 可先建立恢复 ID 空目录；验收只拒绝未知 ID 和实际备份文件。 */
async function backupArtifacts(home, recoveryId) {
  const root = join(home, "data", "db-backups");
  const entries = await readdir(root).catch((error) => {
    if (error.code === "ENOENT") return [];
    throw error;
  });
  assert.ok(entries.every((entry) => entry === recoveryId), "unexpected backup recovery identity");
  return entries.length === 0 ? [] : readdir(join(root, recoveryId));
}

/** 窄窗后的折叠选择会持久保留；先用真实标题栏恢复导航，再选择服务端 Thread。 */
async function selectThread(page, created) {
  const sidebar = page.getByRole("complementary", { name: "项目与对话导航", exact: true });
  if (!await sidebar.isVisible()) {
    await page.getByRole("button", { name: "显示侧边栏", exact: true }).click();
  }
  await sidebar.waitFor({ state: "visible", timeout: 30000 });
  const project = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]').filter({ hasText: "Storage Recovery" });
  await project.waitFor({ timeout: 30000 });
  if (await project.getAttribute("aria-current") !== "page") await project.click();
  const thread = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${created.threadId}"]`);
  await thread.waitFor({ timeout: 30000 });
  if (await thread.getAttribute("aria-current") !== "page") await thread.click();
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor();
}

/** 在恢复后的完整服务图中创建并回读独立 Plan，确认恢复后写入仍可用。 */
async function exercisePlan(page, threadId) {
  return page.evaluate(async (id) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const { TauriPlanAdapter } = await import("/src/api/tauri/plans.ts");
    const history = createHistoryAdapter();
    const thread = await history.threadRead({ threadId: id });
    const adapter = new TauriPlanAdapter();
    const plan = await adapter.create({ owner: { kind: "thread", threadId: id }, objective: "Storage recovery plan", expectedThreadRevision: thread.revision, idempotencyKey: "storage-plan-create" });
    const reread = await adapter.read({ threadId: id, planId: plan.plan.planId });
    return { planReadable: reread.plan.planId === plan.plan.planId };
  }, threadId);
}

/** 恢复后显式授信隔离项目才验证真实文件工具，避免存储验收借普通登记跳过权限边界。 */
async function driver(context, options, fixture) {
  const { page, workspaceRoot, evidenceDirectory, isolatedRuntimeHome: home } = context;
  await mkdir(evidenceDirectory, { recursive: true });
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.name));
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({ timeout: 45000 });
  let initial;
  let lastStorageFailure;
  try {
    await until("storage startup projection", async () => {
      try { initial = await storage(page); return initial.storageRecovery !== undefined; }
      catch (error) { lastStorageFailure = error; return false; }
    });
  } catch (timeout) {
    const reason = lastStorageFailure?.code ?? lastStorageFailure?.name
      ?? (initial === undefined ? "no_projection" : "missing_recovery");
    throw new Error(`storage startup projection timed out (${reason})`, { cause: timeout });
  }
  const database = join(home, "data", "ja.db");
  if (options.scenario === "v15" || options.scenario === "corrupt") {
    assert.equal(initial.storageRecovery?.phase, "blocked");
    if (options.before) assert.deepEqual(databaseFacts(database), options.before);
    if (options.scenario === "corrupt") {
      assert.equal(await readFile(database, "utf8"), "JA CORRUPT DATABASE FIXTURE");
    }
    assert.deepEqual(await backupArtifacts(home, initial.storageRecovery.id), []);
    await page.getByRole("button", { name: "查看详情", exact: true }).click();
    await page.screenshot({ path: join(evidenceDirectory, "01-preserved-blocked.png") });
    await page.getByRole("tab", { name: "数据与存储", exact: true }).waitFor({ timeout: 10000 });
    assert.deepEqual(errors, []);
    return { verdict: "PASS", scenario: options.scenario, blocked: true, originalPreserved: true,
      browser: "Windows Tauri/WebView2 via Playwright CDP", paidProviderVerified: false, pageErrors: errors.length };
  }
  if (options.scenario === "blocked") {
    assert.equal(initial.storageRecovery?.phase, "blocked");
    await page.getByRole("button", { name: "查看详情", exact: true }).click();
    await page.getByRole("tab", { name: "数据与存储", exact: true }).waitFor();
    await page.screenshot({ path: join(evidenceDirectory, "01-blocked.png") });
    await rm(join(home, "data", "ja.db", "obstruction"));
    await rmdir(join(home, "data", "ja.db"));
    await page.getByRole("button", { name: "重试", exact: true }).last().click();
    await until("Native storage ready after retry", async () => {
      try { return (await storage(page)).storageRecovery?.phase === "ready"; }
      catch { return false; }
    });
    assert.match(await readFile(join(home, "data", "ja.db.recovery.properties"), "utf8"), /^stage=COMPLETE$/mu);
    await until("blocked UI cleared", async () => {
      try { return await page.getByText("本地数据暂不可用，请查看详情后重试", { exact: true }).count() === 0; }
      catch { return false; }
    });
    await page.screenshot({ path: join(evidenceDirectory, "02-ready.png") });
    await page.getByRole("button", { name: "返回应用", exact: true }).click();
  }
  const info = await storage(page);
  assert.equal(info.storageRecovery.phase, "ready");
  const created = await page.evaluate(async (cwd) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const history = createHistoryAdapter();
    const workspace = await history.workspaceOpen({ cwd, displayName: "Storage Recovery" });
    await history.workspaceSetTrust({ workspaceId: workspace.workspaceId, trust: "trusted" });
    return history.threadCreate({ cwd, title: "Storage recovery verification", providerId: "provider_e2e", modelId: "model_e2e", reasoningLevel: null, accessMode: "full_access", collaborationMode: "default" });
  }, workspaceRoot);
  await page.reload();
  await selectThread(page, created);
  const imagePath = join(workspaceRoot, "storage-recovery.png");
  await writeFile(imagePath, createLargePngFixture().bytes);
  const preview = await pasteImageAttachment(page, imagePath, Date.now() + 45000);
  await preview.click();
  const image = page.locator(".ja-attachment-image-stage img");
  await image.waitFor({ timeout: 30000 });
  await until("attachment image decoded", () => image.evaluate((element) => element.complete && element.naturalWidth > 0));
  await page.screenshot({ path: join(evidenceDirectory, "03-attachment.png") });
  await page.getByRole("button", { name: "关闭浏览器", exact: true }).click();
  await image.waitFor({ state: "detached" });
  const composer = page.getByRole("textbox", { name: "消息", exact: true });
  await composer.fill("JA STORAGE FILE IO");
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await page.getByText(REPLY, { exact: true }).first().waitFor({ timeout: 60000 });
  assert.equal((await readFile(join(workspaceRoot, "storage-recovery-tool.txt"), "utf8")).trim(), "executed");
  assert.equal(fixture.toolRequests(), 1);
  /** 用户层基线与 CAS 必须配对，并改变真实值才能证明保存链路完成。 */
  const settingsSaved = await page.evaluate(async () => {
    const { TauriSettingsAdapter } = await import("/src/api/tauri/settings.ts");
    const adapter = new TauriSettingsAdapter();
    const baseline = await adapter.snapshot();
    const defaultAccessMode = baseline.userDocument.defaultAccessMode === "full_access" ? "approval_required" : "full_access";
    const version = await adapter.save({ ...baseline.userDocument, defaultAccessMode }, baseline.cas.userVersion, baseline.userDocument);
    const reread = await adapter.snapshot();
    return reread.userDocument.defaultAccessMode === defaultAccessMode
      && reread.cas.userVersion === version && version !== baseline.cas.userVersion;
  });
  assert.ok(settingsSaved);
  const plan = await exercisePlan(page, created.threadId);
  assert.ok(plan.planReadable);
  await composer.fill("保留输入草稿");
  await page.getByRole("button", { name: "设置", exact: true }).click();
  await page.getByRole("tab", { name: "数据与存储", exact: true }).click();
  await page.screenshot({ path: join(evidenceDirectory, "04-storage-details.png") });
  await page.getByRole("button", { name: "返回应用", exact: true }).click();
  assert.equal(await composer.inputValue(), "保留输入草稿");
  await composer.focus();
  assert.ok(await composer.evaluate((element) => element === element.ownerDocument.activeElement));
  await page.setViewportSize({ width: 720, height: 760 });
  await page.screenshot({ path: join(evidenceDirectory, "05-narrow.png") });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.evaluate(async () => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    await createRuntimeHostAdapter().stop();
  });
  await page.reload();
  let reopenedRuntime = { observed: false };
  try {
    await until("Native runtime ready after reconnect", async () => {
      reopenedRuntime = await page.evaluate(async () => {
        const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
        try { return await createRuntimeHostAdapter().state(); }
        catch (error) {
          const errorCode = typeof error?.code === "string" ? error.code : "UNKNOWN";
          try {
            const raw = await globalThis.__TAURI_INTERNALS__.invoke("ja_runtime_state");
            return { errorCode, native: { status: raw.status, generation: raw.generation,
              features: raw.features, fields: Object.keys(raw) } };
          } catch (nativeError) {
            return { errorCode, nativeErrorCode: typeof nativeError?.code === "string" ? nativeError.code : "UNKNOWN" };
          }
        }
      });
      return reopenedRuntime.status === "ready";
    });
  } finally {
    await writeFile(join(evidenceDirectory, "06-reconnect-runtime.json"), `${JSON.stringify(reopenedRuntime, null, 2)}\n`, "utf8");
  }
  const reopenedCatalog = await page.evaluate(async () => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const result = await createHistoryAdapter().workspaceList({ limit: 50 });
    return { count: result.items.length, workspaceIds: result.items.map((item) => item.workspaceId) };
  });
  await writeFile(join(evidenceDirectory, "06-reconnect-catalog.json"), `${JSON.stringify(reopenedCatalog, null, 2)}\n`, "utf8");
  await selectThread(page, created);
  await page.getByText(REPLY, { exact: true }).first().waitFor();
  const reopened = await storage(page);
  assert.equal(reopened.storageRecovery?.id, info.storageRecovery?.id);
  const backups = await backupArtifacts(home, info.storageRecovery.id);
  assert.equal(backups.length, 0);
  assert.equal((await readFile(join(workspaceRoot, "storage-recovery-tool.txt"), "utf8")).trim(), "executed");
  await page.screenshot({ path: join(evidenceDirectory, "06-reopened.png") });
  assert.deepEqual(errors, []);
  return { verdict: "PASS", scenario: options.scenario,
    modelReply: true, shellReadWrite: true, noRepeatedSideEffects: true, attachmentPreview: true,
    settingsSaved, ...plan, draftPreserved: true, focusVerified: true, reopened: true, backupCount: backups.length,
    browser: "Windows Tauri/WebView2 via Playwright CDP", paidProviderVerified: false, pageErrors: errors.length };
}

/** 用流式摘要验证 stage 中真实 EXE，避免把 manifest 声明当成 Native 身份。 */
async function sha256(path) {
  const digest = createHash("sha256");
  for await (const chunk of createReadStream(path)) digest.update(chunk);
  return digest.digest("hex");
}

/** Native-only stage 必须完整匹配生产资源相对路径、大小和 SHA，拒绝 JAR 回退。 */
async function nativeArtifact(directory) {
  assert.ok(isAbsolute(directory));
  const fileName = "ja-app-server-x86_64-pc-windows-msvc.exe";
  const manifest = JSON.parse(await readFile(join(directory, "sidecar-manifest.json"), "utf8"));
  const executable = join(directory, "sidecars", fileName);
  const details = await stat(executable);
  const digest = await sha256(executable);
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.product, "Ja");
  assert.equal(manifest.nativeImageOnly, true);
  assert.equal(manifest.noFallback, true);
  assert.equal(manifest.stagingMode, "copy");
  assert.equal(manifest.sidecar?.relativePath, `sidecars/${fileName}`);
  assert.equal(manifest.sidecar?.sourceArtifact?.sha256, digest);
  assert.equal(manifest.sidecar?.stagedArtifact?.sha256, digest);
  assert.equal(manifest.sidecar?.sourceArtifact?.sizeBytes, details.size);
  assert.equal(manifest.sidecar?.stagedArtifact?.sizeBytes, details.size);
  assert.ok(details.isFile());
  return { executable, fileName, sizeBytes: details.size, sha256: digest };
}

/** 每轮 Native 验收拥有独立用户目录、WebView UDF、runtime 和 Git workspace。 */
async function nativeDirectories() {
  const root = await mkdtemp(join(tmpdir(), "ja-storage-native-"));
  const settings = join(root, "profile");
  const directories = { root, settings, home: join(settings, ".ja"), workspace: join(root, "workspace"),
    webview: join(root, "webview"), runtime: join(root, "runtime"),
    roaming: join(root, "appdata", "roaming"), local: join(root, "appdata", "local") };
  await Promise.all(Object.values(directories).filter((path) => path !== root)
    .map((path) => mkdir(path, { recursive: true })));
  return directories;
}

/** 前端和 CDP 从稳定回环段分别占位探测，防止两者选到同一端口。 */
async function reservePort(excluded = new Set()) {
  for (let offset = 0; offset < 8000; offset += 1) {
    const port = 41000 + ((Date.now() + process.pid + offset) % 8000);
    if (excluded.has(port)) continue;
    const server = createServer();
    const available = await new Promise((done) => {
      server.once("error", () => done(false));
      server.listen(port, "127.0.0.1", () => done(true));
    });
    if (!available) { server.close(); continue; }
    await new Promise((done) => server.close(done));
    return port;
  }
  throw new Error("no isolated loopback port available");
}

/** 私有 overlay 只替换 Native 资源映射和 dev origin，窗口及 CSP 仍取生产配置。 */
async function nativeOverlay(directories, artifact, frontendPort) {
  const [base, windows] = await Promise.all([
    readFile(join(REPO_ROOT, "apps", "desktop", "src-tauri", "tauri.conf.json"), "utf8").then(JSON.parse),
    readFile(join(REPO_ROOT, "apps", "desktop", "src-tauri", "tauri.windows.conf.json"), "utf8").then(JSON.parse),
  ]);
  const main = base.app.windows.find((item) => item.label === "main");
  const win = windows.app?.windows?.find((item) => item.label === "main");
  assert.ok(main);
  const origin = `http://localhost:${frontendPort}`;
  const overlay = join(directories.runtime, "tauri.storage-native.conf.json");
  const config = {
    identifier: `io.github.kongweiguang.ja.storage${randomUUID().replaceAll("-", "")}`,
    build: { devUrl: origin },
    app: { windows: [{ ...main, ...win, visible: true, focus: false, skipTaskbar: true }],
      security: { devCsp: `default-src 'self'; connect-src 'self' ipc: http://ipc.localhost ${origin} ws://localhost:${frontendPort}; img-src 'self' data: blob: ja-attachment: http://ja-attachment.localhost; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self' data:; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'` } },
    bundle: { resources: { [artifact.executable]: `sidecars/${artifact.fileName}` } },
  };
  await writeFile(overlay, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return overlay;
}

/** 与现有 Tauri dev runner 一样经 cmd shim 启动，并只保留有界诊断尾部。 */
function launchNativeTauri(overlay, environment) {
  assert.ok(!/[\r\n"%]/u.test(overlay), "invalid Tauri overlay path");
  const command = `"pnpm.cmd tauri dev --no-watch --config "${overlay}""`;
  const child = spawn(process.env.ComSpec ?? "cmd.exe", ["/d", "/s", "/c", command], {
    cwd: REPO_ROOT, env: environment, windowsHide: true, windowsVerbatimArguments: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const output = { stdout: "", stderr: "" };
  child.on("error", (error) => { output.stderr = `launcher spawn failed: ${error.code ?? "UNKNOWN"}`; });
  child.stdout.on("data", (chunk) => { output.stdout = `${output.stdout}${chunk}`.slice(-8192); });
  child.stderr.on("data", (chunk) => { output.stderr = `${output.stderr}${chunk}`.slice(-8192); });
  return { child, output };
}

/** 冷 Cargo 构建与 WebView2 首启共用五分钟窗口，launcher 先退出时仍及时暴露失败。 */
async function waitForNativeCdp(port, launch) {
  const deadline = Date.now() + 300000;
  while (Date.now() < deadline) {
    if (!Number.isSafeInteger(launch.child.pid)) throw new Error("Native Tauri launcher could not spawn");
    if (launch.child.exitCode !== null) throw new Error("Native Tauri launcher exited before CDP");
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`, { signal: AbortSignal.timeout(2000) });
      if (response.ok && typeof (await response.json()).webSocketDebuggerUrl === "string") return;
    } catch { /* WebView2 冷启动初期可能拒绝该端点的连接。 */ }
    await new Promise((done) => setTimeout(done, 250));
  }
  throw new Error("Native Tauri WebView2 CDP startup timed out");
}

/** 同时核对隔离 runtime 的 Native sidecar，防止首启强制退出后沿用残留 owner。 */
async function nativeWebviewProcessFacts(profile, port, launcherPid, runtimeRoot, artifact) {
  const isolatedRuntime = await realpath(runtimeRoot);
  const normalizedRuntime = isolatedRuntime.startsWith("\\\\?\\")
    ? isolatedRuntime.slice(4) : isolatedRuntime;
  const runDirectoryArgs = [normalizedRuntime, `\\\\?\\${normalizedRuntime}`]
    .map((path) => `--run-dir-base64=${Buffer.from(path, "utf8").toString("base64url")}`);
  const script = [
    "$profilePath = $env:JA_E2E_WEBVIEW_PROFILE",
    "$portArg = $env:JA_E2E_CDP_PORT_ARGUMENT",
    "$items = @(Get-CimInstance Win32_Process -Filter \"Name='msedgewebview2.exe'\" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($profilePath) })",
    "$renderers = @($items | Where-Object { $_.CommandLine.Contains('--type=renderer') })",
    "$withPort = @($items | Where-Object { $_.CommandLine.Contains($portArg) })",
    "$apps = @(Get-CimInstance Win32_Process -Filter \"Name='ja-desktop.exe'\" | Where-Object { $_.ExecutablePath -eq $env:JA_E2E_NATIVE_APP_EXE })",
    "$sidecars = @(Get-CimInstance Win32_Process -Filter \"Name='ja-app-server-x86_64-pc-windows-msvc.exe'\" | Where-Object { $_.CommandLine -and (@($env:JA_E2E_NATIVE_SIDECAR_EXE, $env:JA_E2E_STAGED_SIDECAR_EXE) -contains $_.ExecutablePath) -and ($_.CommandLine.Contains($env:JA_E2E_RUNTIME_ARG) -or $_.CommandLine.Contains($env:JA_E2E_EXT_RUNTIME_ARG)) })",
    "$launchers = @()",
    "if ([int]$env:JA_E2E_LAUNCHER_PID -gt 0) { $launchers = @(Get-CimInstance Win32_Process -Filter \"ProcessId=$env:JA_E2E_LAUNCHER_PID\") }",
    "[pscustomobject]@{ webviewProcesses = $items.Count; rendererProcesses = $renderers.Count; debugPortArgumentProcesses = $withPort.Count; appProcesses = $apps.Count; sidecarProcesses = $sidecars.Count; launcherProcesses = $launchers.Count } | ConvertTo-Json -Compress",
  ].join("; ");
  const { stdout } = await execFileAsync("pwsh.exe", ["-NoProfile", "-Command", script], {
    env: { ...process.env, JA_E2E_WEBVIEW_PROFILE: profile,
      JA_E2E_CDP_PORT_ARGUMENT: `--remote-debugging-port=${port}`,
      JA_E2E_NATIVE_APP_EXE: resolve("target/storage-recovery-native/debug/ja-desktop.exe"),
      JA_E2E_NATIVE_SIDECAR_EXE: resolve("target/storage-recovery-native/debug/sidecars", artifact.fileName),
      JA_E2E_STAGED_SIDECAR_EXE: artifact.executable,
      JA_E2E_RUNTIME_ARG: runDirectoryArgs[0], JA_E2E_EXT_RUNTIME_ARG: runDirectoryArgs[1],
      JA_E2E_LAUNCHER_PID: String(launcherPid ?? 0) },
    windowsHide: true, timeout: 10_000, maxBuffer: 64 * 1024,
  });
  return JSON.parse(stdout);
}

/** 只终止本轮保存的 launcher PID 整棵树，不枚举或清理其它 Ja 实例。 */
async function stopNativeTauri(launch) {
  const pid = launch?.child?.pid;
  if (!Number.isSafeInteger(pid) || launch.child.exitCode !== null) return;
  await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"],
    { windowsHide: true, timeout: 15000, maxBuffer: 512 * 1024 }).catch(() => undefined);
}

/** 以随机 runtime 身份确认整轮 Native owner 已退出，保留未清理现场供定位。 */
async function waitForNativeProcessesGone(directories, port, launcherPid, artifact) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const facts = await nativeWebviewProcessFacts(directories.webview, port, launcherPid,
      directories.runtime, artifact);
    if (facts.webviewProcesses === 0 && facts.appProcesses === 0
        && facts.sidecarProcesses === 0 && facts.launcherProcesses === 0) return;
    await new Promise((done) => setTimeout(done, 200));
  }
  throw new Error("Native Tauri or sidecar process cleanup timed out");
}

/** 首启后确认 sidecar 一并退出才开 CDP，避免旧后台端点与正式阶段交错。 */
async function prewarmNativeWebviewProfile(overlay, environment, directories, port, artifact) {
  const primeEnvironment = { ...environment };
  delete primeEnvironment.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;
  const prime = launchNativeTauri(overlay, primeEnvironment);
  const deadline = Date.now() + 120_000;
  try {
    while (Date.now() < deadline) {
      if (prime.child.exitCode !== null) {
        throw new Error(`Native WebView2 profile prime exited: ${prime.output.stderr.slice(-1000)}`);
      }
      try {
        const [localState, preferences] = await Promise.all([
          stat(join(directories.webview, "EBWebView", "Local State")),
          stat(join(directories.webview, "EBWebView", "Default", "Preferences")),
        ]);
        if (localState.isFile() && localState.size > 0 && preferences.isFile() && preferences.size > 0) break;
      } catch { /* 浏览器进程启动后，WebView2 才会生成这两个 profile 文件。 */ }
      await new Promise((done) => setTimeout(done, 200));
    }
    const [localState, preferences] = await Promise.all([
      stat(join(directories.webview, "EBWebView", "Local State")),
      stat(join(directories.webview, "EBWebView", "Default", "Preferences")),
    ]).catch(() => []);
    if (!localState?.isFile() || !localState.size || !preferences?.isFile() || !preferences.size) {
      throw new Error(`Native WebView2 profile prime timed out: ${prime.output.stderr.slice(-1000)}`);
    }
  } finally {
    await stopNativeTauri(prime);
    await waitForNativeProcessesGone(directories, port, prime.child.pid, artifact);
  }
}

/** PASS 只在正式阶段进程归零后成立，清理失败仍由诊断分支记录并保留隔离现场。 */
async function runNativeProduction(options, fixture) {
  const artifact = await nativeArtifact(options.nativeSidecarDirectory);
  const directories = await nativeDirectories();
  let launch;
  let cdpPort;
  let profilePrime = "pending";
  let passed = false;
  try {
    await createReviewGitFixture(directories.workspace, { fixture: "full", ignoredFiles: 0, untrackedFiles: 0 });
    await writeIsolatedSettings(directories.home, fixture.baseUrl);
    await prepareHome(directories.home, options);
    const frontendPort = await reservePort();
    cdpPort = await reservePort(new Set([frontendPort]));
    const overlay = await nativeOverlay(directories, artifact, frontendPort);
    const environment = buildLaunchEnvironment({ directories, java: join(JAVA_HOME, "bin", "java.exe"),
      jar: undefined, frontendPort, cdpPort, cargoTargetDirectory: resolve("target/storage-recovery-native") });
    environment.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS =
      `--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --remote-debugging-port=${cdpPort}`;
    delete environment.JA_DEBUG_JAVA;
    delete environment.JA_DEBUG_JAR;
    delete environment.JA_E2E_APP_SERVER_JAR;
    profilePrime = "initializing";
    await prewarmNativeWebviewProfile(overlay, environment, directories, cdpPort, artifact);
    profilePrime = "ready_and_cleaned";
    launch = launchNativeTauri(overlay, environment);
    await waitForNativeCdp(cdpPort, launch);
    await runProduction({ ...options, cdpEndpoint: `http://127.0.0.1:${cdpPort}`,
      ownedProfileRoot: directories.root, workspaceRoot: directories.workspace, frontendPort,
      scope: "full", fixture: "full", reportFileName: "storage-recovery-report.json",
      driver: (context) => driver({ ...context, isolatedRuntimeHome: directories.home }, options, fixture),
      validateReport: (report) => { report.execution = { ...report.execution,
        boundary: "native_image_debug_tauri", nativeImageVerified: true,
        nativeSidecar: { ...artifact, executable: undefined, noFallback: true, identityMatched: true } }; } });
    await stopNativeTauri(launch);
    await waitForNativeProcessesGone(directories, cdpPort, launch.child.pid, artifact);
    passed = true;
  } catch (error) {
    const cdpDiagnostics = cdpPort === undefined ? { port: null } : {
      port: cdpPort, launcherPid: launch?.child.pid ?? null,
      launcherExitCode: launch?.child.exitCode ?? null, profilePrime,
      processFacts: await nativeWebviewProcessFacts(directories.webview, cdpPort, launch?.child.pid,
        directories.runtime, artifact)
        .catch((inspectionError) => ({ inspectionError: inspectionError.code ?? "UNKNOWN" })),
    };
    const safe = String(error?.message ?? error).replaceAll(directories.root, "<RUN_ROOT>")
      .replaceAll(options.nativeSidecarDirectory, "<STAGE>").slice(0, 1000);
    await mkdir(options.evidenceDirectory, { recursive: true });
    await writeFile(join(options.evidenceDirectory, "failed-profile-path.txt"), directories.root, "utf8");
    await writeFile(join(options.evidenceDirectory, "launch-diagnostic.log"),
      `${launch?.output.stdout ?? ""}\n${launch?.output.stderr ?? ""}\nCDP_DIAGNOSTIC ${JSON.stringify(cdpDiagnostics)}\n`.replaceAll(directories.root, "<RUN_ROOT>")
        .replaceAll(options.nativeSidecarDirectory, "<STAGE>"), "utf8");
    await writeFile(join(options.evidenceDirectory, "storage-recovery-report.json"),
      `${JSON.stringify({ verdict: "FAIL", runtimeBoundary: "native_image_debug_tauri",
        nativeImageVerified: false, nativeSidecarSha256: artifact.sha256, error: safe,
        cdpDiagnostics }, null, 2)}\n`, "utf8");
    throw error;
  } finally {
    await stopNativeTauri(launch);
    if (launch !== undefined) {
      await waitForNativeProcessesGone(directories, cdpPort, launch.child.pid, artifact);
    }
    if (passed) {
      const actual = await realpath(directories.root);
      const child = relative(resolve(tmpdir()), actual);
      assert.ok(child.startsWith("ja-storage-native-") && !child.includes("..") && !isAbsolute(child));
      await rm(actual, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });
    }
  }
}

/** 复用既有隔离 Windows runner 的进程所有权与清理，不触碰用户安装版。 */
async function main() {
  const options = argumentsFor(process.argv.slice(2));
  const fixture = await providerFixture();
  try {
    if (options.nativeSidecarDirectory !== undefined) {
      await runNativeProduction(options, fixture);
      console.log(`JA_STORAGE_RECOVERY_PASS ${options.scenario} native_image`);
      return;
    }
    await runProduction({ ...options, javaHome: JAVA_HOME, cargoTargetDirectory: resolve("target/storage-recovery"),
      scope: "full", fixture: "full", ignoredFiles: 0, untrackedFiles: 0, hiddenWindow: true,
      prewarmWebview: true, preserveFailedProfile: true, providerBaseUrl: fixture.baseUrl,
      reportFileName: "storage-recovery-report.json", prepareIsolatedHome: ({ home }) => prepareHome(home, options),
      driver: (context) => driver(context, options, fixture) });
    console.log(`JA_STORAGE_RECOVERY_PASS ${options.scenario}`);
  } finally { await fixture.close(); }
}

main().catch((error) => { console.error(error.message); process.exitCode = 1; });
