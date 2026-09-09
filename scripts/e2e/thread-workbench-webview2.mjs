// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Thread 级 Workbench 状态的独立 Windows Tauri/WebView2 验收 runner。
 *
 * 会话通过生产 typed History adapter 写入隔离 App Server，场景不启动 Turn，因而不会访问
 * Provider。A 打开真实 Terminal，B 必须先呈现默认空右栏，再打开真实 native Preview；
 * 来回切换后每个会话只恢复自己的 Tab、内容与 native resource。
 */

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const STEP_TIMEOUT_MS = 30_000;

/** 将每个可见交互限制在独立期限内，避免 selector 漂移耗尽整个真窗阶段。 */
function timeout(deadline) {
  return Math.max(1, Math.min(STEP_TIMEOUT_MS, deadline - Date.now()));
}

/**
 * 只记录本场景需要的 typed/native command 生命周期；不保留 cwd、URL、标题或其它用户数据。
 * 函数不闭包 Node 状态，才能安全交给 Playwright addInitScript。
 */
export function installThreadWorkbenchProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  globalThis.__JA_THREAD_WORKBENCH_TRACE__ = [];
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = request?.command;
    const observed = new Set([
      "ja_thread_create",
      "ja_turn_start",
      "ja_terminal_open",
      "ja_terminal_close",
      "ja_preview_open",
      "ja_preview_layout",
      "ja_preview_close",
    ]);
    /** 证据只保留命令、阶段和 Preview 可见性，不复制 command 参数或返回载荷。 */
    const append = (phase) => {
      if (!observed.has(command)) return;
      globalThis.__JA_THREAD_WORKBENCH_TRACE__.push({
        command,
        phase,
        ...(command === "ja_preview_layout"
          ? { visible: request?.args?.input?.viewport?.visible === true }
          : {}),
      });
    };
    append("start");
    try {
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      append("resolved");
      return result;
    } catch (error) {
      append("rejected");
      throw error;
    }
  };
}

/** 等待真实 App Server 连接并选中 runner 创建的唯一隔离项目。 */
async function prepareApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const selectedProject = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await selectedProject.count()) === 0) {
    await page.getByRole("button", { name: "添加项目", exact: true }).click({
      timeout: timeout(deadline),
    });
  }
  await selectedProject.waitFor({ state: "visible", timeout: timeout(deadline) });
}

/**
 * 通过前端生产 typed adapter 创建具名 durable Thread；固定隔离 Provider/Model 只作为偏好，
 * runner 不调用 turn/start，所以不会产生 Provider 请求。
 */
async function createThreadFixture(page, workspaceRoot, title) {
  return page.evaluate(
    async ({ cwd, threadTitle }) => {
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      return createHistoryAdapter().threadCreate({
        cwd,
        title: threadTitle,
        providerId: "provider_e2e",
        modelId: "model_e2e",
        reasoningLevel: null,
        accessMode: "approval_required",
        collaborationMode: "default",
      });
    },
    { cwd: workspaceRoot, threadTitle: title },
  );
}

/** 按服务端 identity 选择会话，并等待可访问列表的唯一 current 状态稳定。 */
async function selectThread(page, threadId, deadline) {
  assert.match(threadId, /^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u);
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: timeout(deadline) });
  await row.click({ timeout: timeout(deadline) });
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: timeout(deadline) },
  );
}

/** 只命中当前 Thread session，后台保活 Host 不得进入交互或断言范围。 */
function currentInspector(page) {
  return page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
}

/** 读取当前会话可观察的右栏状态，不接触 React state 或浏览器存储。 */
async function workbenchSnapshot(page) {
  const inspector = currentInspector(page);
  const visible = (await inspector.getAttribute("data-visible")) === "true";
  const workbench = inspector.locator(".ja-workbench:visible");
  if ((await workbench.count()) === 0) {
    return {
      visible,
      activeTab: null,
      tabs: [],
      hasFiles: false,
      hasTerminal: false,
      hasPreview: false,
      terminalSessionCount: 0,
    };
  }
  assert.equal(await workbench.count(), 1, "当前会话必须只有一个可见 Workbench");
  return workbench.evaluate(
    (current, inspectorVisible) => ({
      visible: inspectorVisible,
      activeTab: current.getAttribute("data-active-tab"),
      tabs: Array.from(current.querySelectorAll(".ja-workbench-tab-shell[data-tab]")).map((tab) =>
        tab.getAttribute("data-tab"),
      ),
      hasFiles: current.querySelector('[aria-label="文件工作区"]') !== null,
      hasTerminal: current.querySelector('[aria-label="终端工作区"]') !== null,
      hasPreview: current.querySelector('[aria-label="Preview 地址"]') !== null,
      terminalSessionCount: current.querySelectorAll("[data-terminal-session-id]").length,
    }),
    visible,
  );
}

/** 等待一个最终状态谓词，使用有界轮询替代任意 sleep。 */
async function waitForCondition(label, predicate, deadline) {
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} 超时`);
}

/** 打开收起的右栏并返回当前会话真实 Workbench shell。 */
async function ensureWorkbenchVisible(page, deadline) {
  const inspector = currentInspector(page);
  if ((await inspector.getAttribute("data-visible")) !== "true") {
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click({
      timeout: timeout(deadline),
    });
  }
  await inspector.waitFor({ state: "visible", timeout: timeout(deadline) });
  const workbench = inspector.locator(".ja-workbench:visible");
  await workbench.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await workbench.count(), 1, "当前会话必须只有一个可见 Workbench");
  return workbench;
}

/** 通过产品 `+` 菜单打开 singleton capability，不直接写 Thread Workbench 状态。 */
async function openCapability(page, workbench, key, label, deadline) {
  const existing = workbench.locator(`[data-workbench-tab="${key}"]`);
  if ((await existing.count()) === 1) {
    await existing.click({ timeout: timeout(deadline) });
    return existing;
  }
  await workbench.getByRole("button", { name: "新建标签页", exact: true }).click({
    timeout: timeout(deadline),
  });
  await page
    .getByRole("menuitem")
    .filter({ hasText: label })
    .first()
    .click({
      timeout: timeout(deadline),
    });
  await existing.waitFor({ state: "visible", timeout: timeout(deadline) });
  return existing;
}

/** 返回 probe 的脱敏副本，供 Thread 切换前后比较真实 native ACK。 */
async function commandTrace(page) {
  return page.evaluate(() =>
    (globalThis.__JA_THREAD_WORKBENCH_TRACE__ ?? []).map((entry) => ({ ...entry })),
  );
}

/** 统计指定 command/phase，并可进一步限定 Preview visible 参数。 */
function traceCount(trace, command, phase, visible) {
  return trace.filter(
    (entry) =>
      entry.command === command &&
      entry.phase === phase &&
      (visible === undefined || entry.visible === visible),
  ).length;
}

/**
 * 在当前会话通过真实创建器打开 PowerShell PTY，并读取原生签发 identity；调用方只把 identity
 * 摘要写入报告，原值不会进入证据文件。
 */
async function openNativeTerminal(page, workbench, deadline) {
  await openCapability(page, workbench, "terminal", "终端", deadline);
  const terminal = workbench.getByRole("region", { name: "终端工作区", exact: true });
  await terminal.waitFor({ state: "visible", timeout: timeout(deadline) });
  const trigger = terminal.getByRole("button", { name: /新建终端(?:标签页)?/u }).first();
  await trigger.click({ timeout: timeout(deadline) });
  const creator = terminal.getByRole("dialog", { name: "新建终端", exact: true });
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
  const pane = terminal.locator("[data-terminal-session-id]").first();
  await pane
    .locator(".ja-terminal-pane-state")
    .getByText("运行中", { exact: true })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  const sessionId = await pane.getAttribute("data-terminal-session-id");
  const generation = await pane.getAttribute("data-terminal-session-generation");
  assert.ok(sessionId, "运行中终端缺少 native session identity");
  assert.ok(generation, "运行中终端缺少 native session generation");
  return { sessionId, generation };
}

/** 对原生 session identity 做单向摘要，报告能比较隔离性但不暴露真实运行标识。 */
function sessionFingerprint(session) {
  return createHash("sha256")
    .update(`${session.sessionId}:${session.generation}`, "utf8")
    .digest("hex");
}

/** 在 B 会话打开真实 native Preview 并等待 child WebView 完成同源静态资源加载。 */
async function openNativePreview(page, workbench, deadline) {
  await openCapability(page, workbench, "preview", "浏览器", deadline);
  const address = workbench.getByRole("textbox", { name: "Preview 地址", exact: true });
  const target = new URL("/favicon.png?thread-workbench=B", page.url()).href;
  const before = traceCount(await commandTrace(page), "ja_preview_open", "resolved");
  await address.fill(target);
  await workbench.getByRole("button", { name: "刷新或访问", exact: true }).click({
    timeout: timeout(deadline),
  });
  await waitForCondition(
    "B Preview open ACK",
    async () => traceCount(await commandTrace(page), "ja_preview_open", "resolved") > before,
    deadline,
  );
  await waitForCondition(
    "B Preview child WebView",
    async () =>
      page
        .context()
        .pages()
        .some(
          (candidate) => candidate !== page && !candidate.isClosed() && candidate.url() === target,
        ),
    deadline,
  );
  return target;
}

/** 断言当前会话恰好恢复预期 Tab 闭集与唯一内容面板。 */
async function assertBoundState(page, expected) {
  const snapshot = await workbenchSnapshot(page);
  assert.equal(snapshot.visible, true);
  assert.deepEqual(snapshot.tabs, [expected.tab]);
  assert.equal(snapshot.activeTab, expected.tab);
  assert.equal(snapshot.hasFiles, expected.tab === "files");
  assert.equal(snapshot.hasTerminal, expected.tab === "terminal");
  assert.equal(snapshot.hasPreview, expected.tab === "preview");
  return snapshot;
}

/**
 * 在同一项目的 A/B durable Thread 间执行空态、异构 Tab、native Preview 与双向恢复验收。
 */
export async function runThreadWorkbenchWebView2({ page, workspaceRoot, evidenceDirectory }) {
  assert.ok(page, "page is required");
  assert.ok(workspaceRoot, "workspaceRoot is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + 5 * 60_000;
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  await page.context().addInitScript(installThreadWorkbenchProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await prepareApplication(page, deadline);

  const titleA = "Thread Workbench A";
  const titleB = "Thread Workbench B";
  const threadA = await createThreadFixture(page, workspaceRoot, titleA);
  const threadB = await createThreadFixture(page, workspaceRoot, titleB);
  assert.notEqual(threadA.threadId, threadB.threadId);
  assert.equal(threadA.title, titleA);
  assert.equal(threadB.title, titleB);

  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await prepareApplication(page, deadline);
  const history = page.getByRole("list", { name: "最近对话列表", exact: true });
  await history
    .getByText(titleA, { exact: true })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await history
    .getByText(titleB, { exact: true })
    .waitFor({ state: "visible", timeout: timeout(deadline) });

  await selectThread(page, threadA.threadId, deadline);
  let workbench = await ensureWorkbenchVisible(page, deadline);
  const sessionA = await openNativeTerminal(page, workbench, deadline);
  const sessionAFingerprint = sessionFingerprint(sessionA);
  const aInitial = await assertBoundState(page, { tab: "terminal" });
  assert.equal(aInitial.terminalSessionCount, 1);
  const aScreenshot = join(evidenceDirectory, "thread-a-terminal.png");
  await page.screenshot({ path: aScreenshot, animations: "disabled" });

  await selectThread(page, threadB.threadId, deadline);
  await waitForCondition(
    "B 默认空右栏",
    async () => {
      const snapshot = await workbenchSnapshot(page);
      return !snapshot.visible && snapshot.tabs.length === 0 && snapshot.activeTab === null;
    },
    deadline,
  );
  const bDefault = await workbenchSnapshot(page);
  workbench = await ensureWorkbenchVisible(page, deadline);
  const previewTarget = await openNativePreview(page, workbench, deadline);
  const sessionB = await openNativeTerminal(page, workbench, deadline);
  const sessionBFingerprint = sessionFingerprint(sessionB);
  assert.notEqual(sessionAFingerprint, sessionBFingerprint);
  assert.equal((await workbenchSnapshot(page)).terminalSessionCount, 1);
  const terminalCloseBefore = traceCount(await commandTrace(page), "ja_terminal_close", "resolved");
  await workbench.getByRole("button", { name: "关闭终端", exact: true }).click({
    timeout: timeout(deadline),
  });
  await waitForCondition(
    "关闭 B Terminal ACK",
    async () =>
      traceCount(await commandTrace(page), "ja_terminal_close", "resolved") > terminalCloseBefore,
    deadline,
  );
  const bInitial = await assertBoundState(page, { tab: "preview" });
  assert.equal(bInitial.terminalSessionCount, 0);
  assert.equal(
    await workbench.getByRole("textbox", { name: "Preview 地址", exact: true }).inputValue(),
    previewTarget,
  );
  const bScreenshot = join(evidenceDirectory, "thread-b-preview.png");
  await page.screenshot({ path: bScreenshot, animations: "disabled" });

  const hiddenBefore = traceCount(await commandTrace(page), "ja_preview_layout", "resolved", false);
  await selectThread(page, threadA.threadId, deadline);
  await waitForCondition(
    "切回 A 时隐藏 B native Preview",
    async () =>
      traceCount(await commandTrace(page), "ja_preview_layout", "resolved", false) > hiddenBefore,
    deadline,
  );
  const aRestored = await assertBoundState(page, { tab: "terminal" });
  assert.equal(aRestored.terminalSessionCount, 1);
  const restoredSessionA = await currentInspector(page)
    .locator(".ja-workbench:visible [data-terminal-session-id]")
    .first()
    .evaluate((pane) => ({
      sessionId: pane.getAttribute("data-terminal-session-id"),
      generation: pane.getAttribute("data-terminal-session-generation"),
    }));
  assert.equal(sessionFingerprint(restoredSessionA), sessionAFingerprint);

  const visibleBefore = traceCount(await commandTrace(page), "ja_preview_layout", "resolved", true);
  await selectThread(page, threadB.threadId, deadline);
  await waitForCondition(
    "切回 B 时恢复 native Preview",
    async () =>
      traceCount(await commandTrace(page), "ja_preview_layout", "resolved", true) > visibleBefore,
    deadline,
  );
  workbench = currentInspector(page).locator(".ja-workbench:visible");
  const bRestored = await assertBoundState(page, { tab: "preview" });
  assert.equal(
    await workbench.getByRole("textbox", { name: "Preview 地址", exact: true }).inputValue(),
    previewTarget,
  );

  await selectThread(page, threadA.threadId, deadline);
  const aSecondRestore = await assertBoundState(page, { tab: "terminal" });
  assert.equal(aSecondRestore.terminalSessionCount, 1);
  await selectThread(page, threadB.threadId, deadline);
  const bSecondRestore = await assertBoundState(page, { tab: "preview" });
  assert.equal(
    await workbench.getByRole("textbox", { name: "Preview 地址", exact: true }).inputValue(),
    previewTarget,
  );

  const trace = await commandTrace(page);
  assert.equal(traceCount(trace, "ja_turn_start", "start"), 0);
  assert.deepEqual(pageErrors, []);
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    verdict: "PASS",
    fixture: {
      transport: "typed_tauri_history_adapter",
      durableThreadCount: 2,
      nonEmptyTitles: true,
      providerTurnInvokes: 0,
    },
    binding: {
      switchSequence: ["A", "B", "A", "B", "A", "B"],
      bDefaultEmpty: bDefault.visible === false && bDefault.tabs.length === 0,
      aInitial,
      bInitial,
      aRestored,
      bRestored,
      aSecondRestore,
      bSecondRestore,
      isolated: true,
    },
    nativePreview: {
      openResolved: traceCount(trace, "ja_preview_open", "resolved"),
      hiddenLayoutResolved: traceCount(trace, "ja_preview_layout", "resolved", false),
      visibleLayoutResolved: traceCount(trace, "ja_preview_layout", "resolved", true),
      targetRestored: true,
    },
    nativeTerminal: {
      aSessionFingerprint: sessionAFingerprint,
      bSessionFingerprint: sessionBFingerprint,
      distinctSessions: sessionAFingerprint !== sessionBFingerprint,
      bCloseResolved: traceCount(trace, "ja_terminal_close", "resolved"),
      aSessionSurvivedBClosure: true,
    },
    screenshots: [aScreenshot, bScreenshot],
    pageErrors,
  };
}

/** 校验最小闭环，防止只返回布尔 PASS 而缺少双 Thread、空态和 native Preview 证据。 */
export function validateThreadWorkbenchReport(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.runtime, "tauri_webview2");
  assert.equal(report?.verdict, "PASS");
  assert.equal(report?.fixture?.transport, "typed_tauri_history_adapter");
  assert.equal(report?.fixture?.durableThreadCount, 2);
  assert.equal(report?.fixture?.nonEmptyTitles, true);
  assert.equal(report?.fixture?.providerTurnInvokes, 0);
  assert.deepEqual(report?.binding?.switchSequence, ["A", "B", "A", "B", "A", "B"]);
  assert.equal(report?.binding?.bDefaultEmpty, true);
  for (const key of ["aInitial", "aRestored", "aSecondRestore"]) {
    assert.deepEqual(report?.binding?.[key]?.tabs, ["terminal"]);
    assert.equal(report?.binding?.[key]?.activeTab, "terminal");
    assert.equal(report?.binding?.[key]?.hasFiles, false);
    assert.equal(report?.binding?.[key]?.hasTerminal, true);
    assert.equal(report?.binding?.[key]?.hasPreview, false);
    assert.equal(report?.binding?.[key]?.terminalSessionCount, 1);
  }
  for (const key of ["bInitial", "bRestored", "bSecondRestore"]) {
    assert.deepEqual(report?.binding?.[key]?.tabs, ["preview"]);
    assert.equal(report?.binding?.[key]?.activeTab, "preview");
    assert.equal(report?.binding?.[key]?.hasFiles, false);
    assert.equal(report?.binding?.[key]?.hasTerminal, false);
    assert.equal(report?.binding?.[key]?.hasPreview, true);
    assert.equal(report?.binding?.[key]?.terminalSessionCount, 0);
  }
  assert.equal(report?.binding?.isolated, true);
  assert.ok(report?.nativePreview?.openResolved >= 1);
  assert.ok(report?.nativePreview?.hiddenLayoutResolved >= 1);
  assert.ok(report?.nativePreview?.visibleLayoutResolved >= 2);
  assert.equal(report?.nativePreview?.targetRestored, true);
  assert.match(report?.nativeTerminal?.aSessionFingerprint, /^[a-f0-9]{64}$/u);
  assert.match(report?.nativeTerminal?.bSessionFingerprint, /^[a-f0-9]{64}$/u);
  assert.notEqual(
    report?.nativeTerminal?.aSessionFingerprint,
    report?.nativeTerminal?.bSessionFingerprint,
  );
  assert.equal(report?.nativeTerminal?.distinctSessions, true);
  assert.ok(report?.nativeTerminal?.bCloseResolved >= 1);
  assert.equal(report?.nativeTerminal?.aSessionSurvivedBClosure, true);
  assert.deepEqual(report?.pageErrors, []);
  return report;
}

/** 解析本 runner 的窄 CLI，并固定独立 Cargo target，避免与用户构建竞争产物。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-thread-workbench"),
    edgeDriver: undefined,
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
    else if (argument === "--edge-driver") options.edgeDriver = resolve(value);
    else throw new Error(`unknown argument: ${argument}`);
  }
  if (options.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (options.jar === undefined) throw new Error("--jar is required");
  return options;
}

/** 复用 production 隔离启动器，仅替换 Thread Workbench 场景 driver 与报告合同。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const report = await runProduction({
    ...options,
    scope: "git",
    fixture: "no-head",
    ignoredFiles: 0,
    untrackedFiles: 0,
    driver: runThreadWorkbenchWebView2,
    validateReport: validateThreadWorkbenchReport,
    reportFileName: "thread-workbench-report.json",
  });
  console.log(`JA_THREAD_WORKBENCH_PASS ${JSON.stringify({ verdict: report.verdict })}`);
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(`JA_THREAD_WORKBENCH_FAIL ${String(error?.message ?? error).slice(0, 2000)}`);
    process.exitCode = 1;
  });
}
