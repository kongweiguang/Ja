// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 真实 JVM JAR + Tauri/WebView2 的双 Shell 环境验收 runner。
 *
 * Agent Shell 经过 App Server 的真实 Tool 链路，右侧终端经过 Tauri typed IPC/ConPTY；
 * 两条链路只执行只读环境探针。runner 自己负责将 GH_CONFIG_DIR 指回宿主配置，避免隔离
 * APPDATA 让 gh 误判未登录；生产代码仍不能读取用户配置或 token。
 */

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";
import {
  buildEnvironmentProbeCommand,
  parseEnvironmentFacts,
  shellEnvironmentFixtureMarkers,
  startShellEnvironmentFixture,
} from "./fixtures/shell-environment.mjs";

const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const TITLE = "Shell Environment E2E";
const ENV_FACT_KEYS = [
  "appdata_digest",
  "localappdata_digest",
  "userprofile_digest",
  "gh_config_dir_present",
  "gh_installed",
  "gh_logged_in",
  "gh_account",
];

/** 将单步 Playwright 超时限制在当前总 deadline 内，避免 selector 漂移吞掉整轮证据。 */
function timeout(deadline) {
  return Math.max(1, Math.min(30_000, deadline - Date.now()));
}

/** 等待跨进程事实收敛，所有轮询均有硬 deadline，不用任意 sleep 猜测状态。 */
async function waitForCondition(label, predicate, deadline) {
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} 超时`);
}

/** 计算宿主环境值的短 digest，只用于证明隔离 profile 与宿主值不同，不保存路径正文。 */
function environmentDigest(value) {
  return createHash("sha256").update(value ?? "", "utf8").digest("hex").slice(0, 16);
}

/** 取宿主 GitHub CLI 配置目录；缺省值与 gh 的 Windows 默认位置一致。 */
function hostGhConfigDirectory() {
  const configured = process.env.GH_CONFIG_DIR?.trim();
  if (configured !== undefined && configured.length > 0) return configured;
  const appData = process.env.APPDATA?.trim();
  return appData === undefined || appData.length === 0 ? undefined : join(appData, "GitHub CLI");
}

/** 隔离真窗连接后立即隐藏本轮窗口，CDP 仍可继续操作而不触碰用户现有窗口。 */
async function hideOwnedWindow(page) {
  await page.evaluate(async () => {
    const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
    if (typeof invoke !== "function") throw new Error("Tauri bridge unavailable");
    await invoke("plugin:window|hide", { label: "main" });
  });
}

/** 等待真实应用、App Server handshake 与 Composer 就绪；初次启动不要求已有项目。 */
async function waitForApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
}

/**
 * 在隔离配置内通过 typed Settings/Runtime/History adapter 配置 loopback Provider 并创建真实
 * Thread；cwd 必须来自当前 App 的 general workspace，避免 runner 注入项目路径或依赖项目 picker。
 */
async function configureFixtureAndCreateThread(page, baseUrl) {
  return page.evaluate(
    async ({ endpoint, title }) => {
      const [{ TauriSettingsAdapter }, { createHistoryAdapter }, { createRuntimeHostAdapter }] =
        await Promise.all([
          import("/src/api/tauri/settings.ts"),
          import("/src/api/tauri/history.ts"),
          import("/src/api/tauri/runtime.ts"),
        ]);
      const settings = new TauriSettingsAdapter();
      const loaded = await settings.snapshot();
      const userDocument = JSON.parse(JSON.stringify(loaded.userDocument));
      const provider = userDocument.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (provider === undefined) throw new Error("isolated provider_e2e is missing");
      provider.baseUrl = endpoint;
      await settings.save(userDocument, loaded.cas.userVersion);
      const refreshed = await settings.snapshot();
      const current = refreshed.document.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (current?.baseUrl !== endpoint) throw new Error("fixture Provider endpoint was not staged");
      const modelId = current.models[0]?.modelId;
      if (modelId === undefined) throw new Error("isolated provider_e2e model is missing");
      const workspace = await createRuntimeHostAdapter().generalWorkspace();
      const created = await createHistoryAdapter().threadCreate({
        cwd: workspace.rootPath,
        title,
        providerId: "provider_e2e",
        modelId,
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
      return { threadId: created.threadId, modelId };
    },
    { endpoint: baseUrl, title: TITLE },
  );
}

/** 通过真实侧栏恢复本轮 Thread identity，避免用标题或 DOM 顺序猜测归属。 */
async function selectThread(page, threadId, deadline) {
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await row.getAttribute("aria-current")) !== "page") {
    await row.click({ timeout: timeout(deadline) });
  }
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: timeout(deadline) },
  );
}

/** 经真实 Composer 提交一条用户消息，并等待消息 ACK 进入当前时间线。 */
async function submitPrompt(page, prompt, deadline) {
  const input = page.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: timeout(deadline),
  });
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: prompt })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
}

/** 只通过真实 Workbench launcher 打开终端能力，确保终端由当前 Thread scope 挂载。 */
async function openTerminal(page, deadline) {
  const showWorkbench = page.getByRole("button", { name: "显示工作区面板", exact: true });
  if (await showWorkbench.isVisible().catch(() => false)) {
    await showWorkbench.click({ timeout: timeout(deadline) });
  }
  const workbench = page.locator('.ja-inspector[aria-label="工作区面板"]');
  await workbench.waitFor({ state: "visible", timeout: timeout(deadline) });
  const tablist = workbench.getByRole("tablist", { name: "工作区标签", exact: true });
  await tablist.waitFor({ state: "visible", timeout: timeout(deadline) });
  let terminalTab = tablist.getByRole("tab", { name: "终端", exact: true });
  if ((await terminalTab.count()) === 0) {
    await workbench.getByRole("button", { name: "新建标签页", exact: true }).click({
      timeout: timeout(deadline),
    });
    await page
      .getByRole("menuitem")
      .filter({ hasText: "终端" })
      .first()
      .click({
      timeout: timeout(deadline),
      });
    terminalTab = tablist.getByRole("tab").filter({ hasText: "终端" }).first();
  }
  await terminalTab.waitFor({ state: "visible", timeout: timeout(deadline) });
  await terminalTab.click({ timeout: timeout(deadline) });
  await workbench.locator('[data-tab-panel="terminal"]:not([hidden])').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const terminalWorkspace = workbench.getByRole("region", { name: "终端工作区", exact: true });
  await terminalWorkspace.waitFor({ state: "visible", timeout: timeout(deadline) });
  const createTerminal = terminalWorkspace
    .getByRole("button", { name: /新建终端(?:标签页)?/u })
    .first();
  if (await createTerminal.isVisible().catch(() => false)) {
    await createTerminal.click({ timeout: timeout(deadline) });
    const creator = terminalWorkspace.getByRole("dialog", { name: "新建终端", exact: true });
    await creator.waitFor({ state: "visible", timeout: timeout(deadline) });
    await creator.getByRole("combobox", { name: "Shell profile", exact: true }).click({
      timeout: timeout(deadline),
    });
    await page.getByRole("option", { name: "PowerShell", exact: true }).click({
      timeout: timeout(deadline),
    });
    await creator.getByRole("button", { name: "创建终端", exact: true }).click({
      timeout: timeout(deadline),
    });
    await creator.waitFor({ state: "detached", timeout: timeout(deadline) });
  }
  const pane = terminalWorkspace
    .locator('[role="tabpanel"]:not([hidden]) .ja-terminal-pane')
    .first();
  await pane.locator(".ja-terminal-pane-state").getByText("运行中", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const xterm = pane.locator(".xterm");
  await xterm.waitFor({ state: "visible", timeout: timeout(deadline) });
  return { workbench, pane, xterm };
}

/** 在 xterm 真表面发送命令，证明输入经 typed IPC 到达真实 ConPTY，而非写入 DOM。 */
async function runTerminalProbe(page, terminal, deadline) {
  const command = buildEnvironmentProbeCommand("JA_TERMINAL_ENV");
  await terminal.xterm.click({ timeout: timeout(deadline) });
  await page.keyboard.type(command);
  await page.keyboard.press("Enter");
  const rows = terminal.xterm.locator(".xterm-rows");
  await rows.waitFor({ state: "visible", timeout: timeout(deadline) });
  let facts;
  await waitForCondition(
    "ConPTY environment facts",
    async () => {
      // xterm 将换行渲染成多个 visual row，直接读 textContent 会把命令回显和输出粘成一行，
      // 而命令本身也包含 END marker；按 row 重建文本并交给完整 block parser 才能避免提前命中。
      const text = await rows.locator(":scope > div").evaluateAll((items) =>
        items.map((item) => item.textContent ?? "").join("\n"),
      );
      facts = parseEnvironmentFacts(text, "JA_TERMINAL_ENV");
      return facts !== undefined;
    },
    deadline,
  );
  if (facts === undefined) throw new Error("ConPTY output did not contain a safe fact block");
  return facts;
}

/** 等待并读取 Provider 已过滤的 Agent Shell facts，不读取或复制原始 Tool output。 */
async function waitForAgentFacts(fixture, deadline) {
  let facts;
  await waitForCondition(
    "Agent Shell facts",
    () => {
      facts = fixture
        .snapshot()
        .attempts.find((attempt) => attempt.kind === "agent_shell")?.facts;
      return facts !== undefined;
    },
    deadline,
  );
  return facts;
}

/** 比较两条链路的闭集环境事实，并确认隔离 APPDATA 未悄悄回退到宿主值。 */
function compareEnvironmentFacts(agent, terminal, hostAppDataDigest) {
  for (const key of ENV_FACT_KEYS) {
    assert.ok(Object.hasOwn(agent, key), `Agent facts missing ${key}`);
    assert.ok(Object.hasOwn(terminal, key), `ConPTY facts missing ${key}`);
    assert.deepEqual(terminal[key], agent[key], `${key} differs between Shell paths`);
  }
  assert.notEqual(agent.appdata_digest, hostAppDataDigest, "APPDATA unexpectedly used host profile");
  assert.notEqual(agent.appdata_digest, "", "isolated APPDATA is missing");
  assert.equal(agent.gh_config_dir_present, true, "GH_CONFIG_DIR was not inherited");
  assert.equal(agent.gh_installed, true, "gh CLI is unavailable in inherited PATH");
  assert.equal(agent.gh_logged_in, true, "gh auth status is not logged in");
  assert.match(agent.gh_account, /^[A-Za-z0-9_.-]{1,128}$/u);
}

/** 对外报告做闭集验收；报告不允许携带绝对路径、Provider body 或 Secret。 */
export function validateShellEnvironmentReport(report) {
  assert.equal(report?.schemaVersion, 1);
  assert.equal(report?.status, "passed");
  assert.equal(report?.runtime?.platform, "win32");
  assert.equal(report?.runtime?.surface, "tauri_webview2");
  assert.equal(report?.runtime?.boundary, "jvm_jar");
  assert.equal(report?.runtime?.nativeImageVerified, false);
  assert.equal(report?.provider?.kind, "deterministic_loopback");
  assert.equal(report?.provider?.externalCalls, 0);
  assert.equal(report?.appServerShell?.transport, "app_server_shell_tool");
  assert.equal(report?.appServerShell?.toolCallObserved, true);
  assert.equal(report?.interactiveTerminal?.transport, "tauri_typed_ipc_conpty");
  assert.equal(report?.interactiveTerminal?.typedInputObserved, true);
  assert.equal(report?.equivalence?.sameEnvironment, true);
  assert.equal(report?.equivalence?.isolatedAppData, true);
  assert.equal(report?.gh?.loggedIn, true);
  assert.match(report?.gh?.account ?? "", /^[A-Za-z0-9_.-]{1,128}$/u);
  assert.equal(
    report?.cancellation?.providerRequestClosed,
    true,
    "providerRequestClosed must be true",
  );
  assert.equal(report?.cancellation?.uiTerminalState, "cancelled", "uiTerminalState must be cancelled");
  assert.equal(report?.secrets?.rawOutputPersisted, false, "rawOutputPersisted must be false");
  return report;
}

/**
 * 驱动一轮隐藏真窗：Agent Shell、ConPTY、取消都绑定当前隔离 Thread 与 Provider；Thread
 * 直接使用 App-owned general workspace，不要求项目 picker 先建立 UI selection。
 */
export async function runShellEnvironmentWebView2({ page, evidenceDirectory, fixture }) {
  assert.ok(page, "page is required");
  assert.ok(fixture, "fixture is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + 6 * 60_000;
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  await hideOwnedWindow(page);
  await waitForApplication(page, deadline);
  const created = await configureFixtureAndCreateThread(page, fixture.baseUrl);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  await selectThread(page, created.threadId, deadline);
  console.log("JA_SHELL_ENV_STAGE ready");

  await submitPrompt(page, "请执行一次隔离环境探针并返回结果。", deadline);
  const workProcess = page.locator("section.ja-work-process").last();
  await workProcess.locator('.ja-tool-details[data-tool-kind="shell"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const agentFacts = await waitForAgentFacts(fixture, deadline);
  console.log("JA_SHELL_ENV_STAGE agentFacts");
  await page.getByText(shellEnvironmentFixtureMarkers.final, { exact: false }).last().waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.locator('.ja-chat-message-final[data-response-state="completed"]').last().waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });

  const terminal = await openTerminal(page, deadline);
  console.log("JA_SHELL_ENV_STAGE terminalOpened");
  const terminalFacts = await runTerminalProbe(page, terminal, deadline);
  console.log("JA_SHELL_ENV_STAGE terminalFacts");

  await submitPrompt(page, shellEnvironmentFixtureMarkers.cancelPrompt, deadline);
  await waitForCondition(
    "Provider cancel request",
    () => fixture.snapshot().attempts.some((attempt) => attempt.kind === "cancel"),
    deadline,
  );
  console.log("JA_SHELL_ENV_STAGE cancelRequested");
  await page.getByRole("button", { name: "停止生成", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("button", { name: "停止生成", exact: true }).click({
    timeout: timeout(deadline),
  });
  await waitForCondition(
    "Provider cancel request closure",
    () => fixture.snapshot().attempts.some((attempt) => attempt.kind === "cancel" && attempt.cancelled),
    deadline,
  );
  console.log("JA_SHELL_ENV_STAGE cancel");
  await page.locator('[data-response-state="cancelled"]').last().waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const hostAppDataDigest = environmentDigest(process.env.APPDATA ?? "");
  compareEnvironmentFacts(agentFacts, terminalFacts, hostAppDataDigest);
  assert.deepEqual(pageErrors, [], `WebView2 page errors: ${pageErrors.join(" | ")}`);
  const report = {
    schemaVersion: 1,
    status: "passed",
    runtime: {
      platform: process.platform,
      surface: "tauri_webview2",
      boundary: "jvm_jar",
      nativeImageVerified: false,
    },
    provider: {
      kind: "deterministic_loopback",
      externalCalls: 0,
      agentShellFactsObserved: true,
    },
    appServerShell: {
      transport: "app_server_shell_tool",
      toolCallObserved: true,
      facts: agentFacts,
    },
    interactiveTerminal: {
      transport: "tauri_typed_ipc_conpty",
      typedInputObserved: true,
      facts: terminalFacts,
    },
    equivalence: {
      sameEnvironment: true,
      isolatedAppData: agentFacts.appdata_digest !== hostAppDataDigest,
      comparedFields: ENV_FACT_KEYS,
    },
    gh: {
      configDirectoryInherited: agentFacts.gh_config_dir_present,
      installed: agentFacts.gh_installed,
      loggedIn: agentFacts.gh_logged_in,
      account: agentFacts.gh_account,
    },
    cancellation: {
      requested: true,
      providerRequestClosed: true,
      uiTerminalState: "cancelled",
    },
    secrets: { rawOutputPersisted: false, tokenOutputRequested: false },
    diagnostics: { pageErrors },
  };
  validateShellEnvironmentReport(report);
  await page.screenshot({ path: join(evidenceDirectory, "shell-environment.png"), animations: "disabled" });
  return report;
}

/** 解析 runner CLI；JAR、证据目录和独立 Cargo target 均必须显式或使用安全默认值。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-shell-environment"),
  };
  for (let index = 0; index < argv.length; index += 2) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (argument === "--jar") options.jar = resolve(value);
    else if (argument === "--java-home") options.javaHome = resolve(value);
    else if (argument === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else throw new Error(`unknown argument: ${argument}`);
  }
  if (options.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (options.jar === undefined) throw new Error("--jar is required");
  return options;
}

/** 启动隔离 fixture 和 production runner，并在 finally 恢复 runner 注入的宿主 GH_CONFIG_DIR。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const fixture = await startShellEnvironmentFixture();
  const previousGhConfigDir = process.env.GH_CONFIG_DIR;
  const ghConfigDir = hostGhConfigDirectory();
  if (ghConfigDir !== undefined) process.env.GH_CONFIG_DIR = ghConfigDir;
  try {
    const report = await runProduction({
      ...options,
      prewarmWebview: true,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      driver: async (driverOptions) => {
        try {
          return await runShellEnvironmentWebView2({ ...driverOptions, fixture });
        } catch (error) {
          await driverOptions.page
            .screenshot({ path: join(options.evidenceDirectory, "failure.png") })
            .catch(() => undefined);
          throw error;
        }
      },
      validateReport: validateShellEnvironmentReport,
      reportFileName: "shell-environment-report.json",
    });
    console.log(`JA_SHELL_ENVIRONMENT_PASS ${JSON.stringify({ status: report.status })}`);
  } finally {
    await fixture.close();
    if (previousGhConfigDir === undefined) delete process.env.GH_CONFIG_DIR;
    else process.env.GH_CONFIG_DIR = previousGhConfigDir;
  }
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(`JA_SHELL_ENVIRONMENT_FAIL ${String(error?.message ?? error).slice(0, 2000)}`);
    process.exitCode = 1;
  });
}
