// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 配置可用性的隔离 Windows Tauri/WebView2 验收。它只使用 runner 创建的临时 Ja home 和
 * loopback Provider，覆盖“外部改坏 MCP 附近字段后，正常模型仍可对话、问题可见、重启仍可用”。
 */

import assert from "node:assert/strict";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";
import { startRecoveryFixture } from "./conversation-recovery-fixture.mjs";

const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const RESPONSE_MARKER = "JA_RECOVERY_SUCCESS_other";

/** 命令行必须显式指定产物和证据目录，避免误连安装版、用户目录或陈旧 JAR。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-configuration-availability"),
  };
  for (let index = 0; index < argv.length; index += 2) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (argument === "--jar") options.jar = resolve(value);
    else if (argument === "--java-home") options.javaHome = resolve(value);
    else if (argument === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else throw new Error(`unknown argument: ${argument}`);
  }
  if (options.evidenceDirectory === undefined || options.jar === undefined)
    throw new Error("--evidence-directory and --jar are required");
  return options;
}

/** 只等待权威状态变化，避免靠固定 sleep 掩盖 Watcher、重载或 WebView2 时序问题。 */
async function until(label, predicate, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((done) => setTimeout(done, 100));
  }
  throw new Error(`${label} timed out`);
}

/** 使用真实 typed adapter 创建一条隔离 Thread，模型和凭据只来自 runner 写入的临时配置。 */
async function createThread(page, workspaceRoot, endpoint) {
  return page.evaluate(
    async ({ cwd, baseUrl }) => {
      const [{ TauriSettingsAdapter }, { createHistoryAdapter }] = await Promise.all([
        import("/src/api/tauri/settings.ts"),
        import("/src/api/tauri/history.ts"),
      ]);
      const settings = await new TauriSettingsAdapter().snapshot();
      const provider = settings.document.providers.find(
        (item) => item.providerId === "provider_e2e",
      );
      if (provider?.baseUrl !== baseUrl || provider.models[0] === undefined)
        throw new Error("isolated provider fixture is unavailable");
      const history = createHistoryAdapter();
      const workspace = await history.workspaceOpen({
        cwd,
        displayName: "Configuration Availability E2E",
      });
      const thread = await history.threadCreate({
        cwd: workspace.root,
        title: "配置可用性真窗验收",
        providerId: provider.providerId,
        modelId: provider.models[0].modelId,
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
      return { threadId: thread.threadId, workspaceName: workspace.displayName };
    },
    { cwd: workspaceRoot, baseUrl: endpoint },
  );
}

/** 通过真实导航选择 Java 创建的对象，避免 driver 直接调用绕过 Composer 的可用性门禁。 */
async function selectThread(page, workspaceName, threadId) {
  const project = page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: workspaceName });
  await project.waitFor({ state: "visible", timeout: 30_000 });
  if ((await project.getAttribute("aria-current")) !== "page") await project.click();
  const thread = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await thread.waitFor({ state: "visible", timeout: 30_000 });
  if ((await thread.getAttribute("aria-current")) !== "page") await thread.click();
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({ timeout: 30_000 });
}

/** 每个真实 Composer 提交都等待终态正文，确保“可用”不是仅能读取模型目录。 */
async function sendAndWait(page, text, expectedResponses) {
  await page.getByRole("textbox", { name: "消息", exact: true }).fill(text);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await until(
    "provider response",
    async () =>
      (await page.getByText(RESPONSE_MARKER, { exact: true }).count()) >= expectedResponses,
  );
}

/**
 * 在真实外部编辑场景制造根 schema 落入 MCP table 的 TOML；不调用任何设置保存接口，确保测试的是
 * Watcher 与读取容错而非 UI 自动规范化。
 */
async function writeMisplacedMcpSchema(home) {
  const path = join(home, "config.toml");
  const source = await readFile(path, "utf8");
  assert.ok(source.includes("schema_version = 2\n"));
  assert.ok(source.includes("mcp_servers = []\n"));
  const damaged = `${source
    .replace("schema_version = 2\n", "")
    .replace(
      "mcp_servers = []\n",
      "",
    )}\n[[mcp_servers]]\nmcp_id = "mcp_kerminal"\nname = "Kerminal"\ntransport = "stdio"\nendpoint = "kerminal"\nargs = []\nenv = {}\nheaders = {}\nauth = { kind = "none" }\nenabled = true\nschema_version = 2\n`;
  await writeFile(path, damaged, "utf8");
  return damaged;
}

/** 从 renderer 的真实 typed adapter 读取当前源状态，验证局部问题没有让 Provider/Model 列表消失。 */
async function waitForTolerantSnapshot(page) {
  let snapshot;
  await until("tolerant configuration read", async () => {
    snapshot = await page.evaluate(async () => {
      const { TauriSettingsAdapter } = await import("/src/api/tauri/settings.ts");
      const loaded = await new TauriSettingsAdapter().snapshot();
      return {
        providerCount: loaded.document.providers.length,
        modelCount: loaded.document.providers.reduce(
          (count, provider) => count + provider.models.length,
          0,
        ),
        issue: loaded.issues.some(
          (issue) =>
            issue.entityId === "mcp_kerminal" &&
            issue.field === "schema_version" &&
            issue.impact === "ignored",
        ),
      };
    });
    return snapshot.providerCount === 1 && snapshot.modelCount === 1 && snapshot.issue;
  });
  return snapshot;
}

/**
 * 在真实 WebView2 通过同一 typed host adapter 观察脱敏失效事件，避免把一次直接读取成功误当成
 * 设置界面会自动收敛。观察者只保留 scope 与版本，并由调用方在任何通过或失败路径注销。
 */
async function observeConfigurationChanges(page) {
  await page.evaluate(async () => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const observed = [];
    const unsubscribe = await createRuntimeHostAdapter().subscribe((event) => {
      if (event.kind !== "timeline" || event.event.method !== "configuration/changed") return;
      observed.push({ scope: event.event.params.scope, version: event.event.params.version });
    });
    globalThis.__JA_CONFIGURATION_E2E_EVENT_OBSERVER__ = { observed, unsubscribe };
  });
  return async () => {
    await page.evaluate(async () => {
      const observer = globalThis.__JA_CONFIGURATION_E2E_EVENT_OBSERVER__;
      if (observer === undefined) return;
      delete globalThis.__JA_CONFIGURATION_E2E_EVENT_OBSERVER__;
      await observer.unsubscribe();
    });
  };
}

/** 只接受本轮外部用户配置变更的已校验通知，防止旧运行时事件被误当成当前文件的刷新。 */
async function waitForObservedUserConfigurationChange(page) {
  let change;
  await until("external configuration event", async () => {
    change = await page.evaluate(() =>
      globalThis.__JA_CONFIGURATION_E2E_EVENT_OBSERVER__?.observed.find(
        (event) => event.scope === "user",
      ),
    );
    return change !== undefined;
  });
  return change;
}

/**
 * 先停止隔离 App Server，再重载真实桌面 renderer，让 RuntimeProvider 通过生产 lifecycle
 * 启动新 generation；不能由测试绕过 React 生命周期直接拉起侧车，否则 UI 不会收到 admission。
 */
async function restartRuntime(page) {
  await page.evaluate(async () => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const runtime = createRuntimeHostAdapter();
    await runtime.stop();
  });
  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({ timeout: 30_000 });
  await page
    .getByRole("status", { name: "本地运行时：已连接", exact: true })
    .waitFor({ timeout: 30_000 });
}

/** 真窗闭环：对话成功、外部改坏、问题 Sheet、重启、继续对话。 */
export async function runConfigurationAvailability({
  page,
  workspaceRoot,
  evidenceDirectory,
  isolatedRuntimeHome,
}) {
  await mkdir(evidenceDirectory, { recursive: true });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({ timeout: 30_000 });
  await page
    .getByRole("status", { name: "本地运行时：已连接", exact: true })
    .waitFor({ timeout: 30_000 });
  const fixture = globalThis.__JA_CONFIGURATION_E2E_FIXTURE__;
  assert.ok(fixture, "loopback fixture is unavailable");
  const created = await createThread(page, workspaceRoot, fixture.baseUrl);
  // 建立数据只为准备可复用项目；随后的 reload 令产品自身的目录发现控制器重读 SQLite，实际选择
  // 仍由侧栏完成，避免 fixture 的直接 IPC 调用伪造 UI 已刷新这一不存在的事件。
  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({ timeout: 30_000 });
  await page
    .getByRole("status", { name: "本地运行时：已连接", exact: true })
    .waitFor({ timeout: 30_000 });
  await selectThread(page, created.workspaceName, created.threadId);
  await sendAndWait(page, "JA configuration availability before external edit", 1);
  const stopObservingConfiguration = await observeConfigurationChanges(page);
  try {
    const source = await writeMisplacedMcpSchema(isolatedRuntimeHome);
    const observedChange = await waitForObservedUserConfigurationChange(page);
    const snapshot = await waitForTolerantSnapshot(page);
    await sendAndWait(page, "JA configuration availability after external edit", 2);

    await page.getByRole("button", { name: "设置", exact: true }).click();
    const issuesTrigger = page.getByRole("button", { name: "配置问题", exact: true });
    await issuesTrigger.waitFor({ state: "visible", timeout: 30_000 });
    await issuesTrigger.click();
    const issuesDialog = page.getByRole("dialog");
    await issuesDialog.getByText("配置问题", { exact: true }).waitFor({ timeout: 30_000 });
    await page.screenshot({ path: join(evidenceDirectory, "configuration-issues-light.png") });
    // 明确向已打开的 Sheet 投递按键，避免隐藏 WebView2 窗口把页面级 keyboard action 落到宿主。
    await issuesDialog.press("Escape");
    await issuesDialog.waitFor({ state: "hidden", timeout: 30_000 });
    await page.screenshot({
      path: join(evidenceDirectory, "configuration-issues-light-closed.png"),
    });
    await until("issues sheet focus return", () =>
      issuesTrigger.evaluate((element) => element.ownerDocument.activeElement === element),
    );

    await restartRuntime(page);
    const restarted = await waitForTolerantSnapshot(page);
    await selectThread(page, created.workspaceName, created.threadId);
    await sendAndWait(page, "JA configuration availability after restart", 3);
    await page.screenshot({
      path: join(evidenceDirectory, "configuration-available-after-restart.png"),
    });
    assert.equal(await readFile(join(isolatedRuntimeHome, "config.toml"), "utf8"), source);
    return {
      verdict: "PASS",
      runtime: { surface: "tauri_webview2", boundary: "debug_jar", nativeImageVerified: false },
      provider: { kind: "deterministic_loopback", externalCalls: 0 },
      configuration: {
        beforeProviderCount: 1,
        observedChange,
        afterExternalEdit: snapshot,
        afterRestart: restarted,
      },
      conversation: { threadIdRetained: true, successfulTurns: 3 },
      ui: { issuesSheet: true, focusReturned: true },
    };
  } finally {
    await stopObservingConfiguration();
  }
}

/**
 * 将隔离运行的结果收敛为配置可用性不变量，避免 runner 仅因页面没有崩溃就把不完整场景写成通过。
 * 该校验只消费无密钥的报告投影，因此可以安全落盘供失败定位与回归门禁复用。
 */
export function validateConfigurationAvailabilityReport(report) {
  assert.equal(report?.verdict, "PASS", "configuration availability verdict is missing");
  assert.equal(report?.runtime?.surface, "tauri_webview2", "WebView2 runtime was not exercised");
  assert.equal(report?.runtime?.boundary, "debug_jar", "isolated JAR boundary is missing");
  assert.equal(report?.provider?.kind, "deterministic_loopback", "external provider was used");
  assert.equal(report?.provider?.externalCalls, 0, "external provider calls are not allowed");
  assert.equal(
    report?.configuration?.beforeProviderCount,
    1,
    "initial provider catalog is incomplete",
  );
  assert.equal(
    report?.configuration?.observedChange?.scope,
    "user",
    "external user change was not observed",
  );
  for (const snapshot of [
    report?.configuration?.afterExternalEdit,
    report?.configuration?.afterRestart,
  ]) {
    assert.equal(snapshot?.providerCount, 1, "tolerant read lost a provider");
    assert.equal(snapshot?.modelCount, 1, "tolerant read lost a model");
    assert.equal(snapshot?.issue, true, "misplaced MCP schema was not diagnosed");
  }
  assert.equal(report?.conversation?.threadIdRetained, true, "thread identity was not retained");
  assert.equal(report?.conversation?.successfulTurns, 3, "normal model did not complete all turns");
  assert.equal(report?.ui?.issuesSheet, true, "configuration issues Sheet was not displayed");
  assert.equal(report?.ui?.focusReturned, true, "issues Sheet did not return focus");
}

/** CLI 只创建和清理自己的 loopback server；隔离 launcher 继续拥有临时 profile 和进程清理。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const fixture = await startRecoveryFixture();
  globalThis.__JA_CONFIGURATION_E2E_FIXTURE__ = fixture;
  try {
    await runProduction({
      ...options,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      providerBaseUrl: fixture.baseUrl,
      reportFileName: "configuration-availability-report.json",
      driver: runConfigurationAvailability,
      validateReport: validateConfigurationAvailabilityReport,
      // 新建 WebView2 UDF 首次启动可能忽略调试端口；先在同一隔离 profile 预热，随后才连接 CDP。
      prewarmWebview: true,
      preserveFailedProfile: true,
      // 验收经 CDP 连接真实 WebView2；隐藏临时窗口避免占用用户的前台工作区。
      hiddenWindow: true,
    });
    console.log("JA_CONFIGURATION_AVAILABILITY_PASS");
  } finally {
    delete globalThis.__JA_CONFIGURATION_E2E_FIXTURE__;
    await fixture.close();
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
