// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 右侧 Workbench Tab 菜单的隔离 Windows Tauri/WebView2 验收。场景只访问本轮 Vite
 * 静态资源并启动本轮 PTY，不发送 Provider 请求，也不接触任何非本 runner 所有的进程。
 */

import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const STEP_TIMEOUT_MS = 30_000;

/** 将每个可见交互限制在单独期限内，使 selector 漂移不会耗尽整个真窗阶段。 */
function timeout(deadline) {
  return Math.max(1, Math.min(STEP_TIMEOUT_MS, deadline - Date.now()));
}

/**
 * 在 E2E composition 的既有 probe 接缝记录资源生命周期。参数只保留 Preview visible
 * 布尔值，绝不记录 terminal input、URL、workspace 或其它用户数据。
 */
export function installWorkbenchLifecycleProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  globalThis.__JA_WORKBENCH_TAB_CONTEXT_TRACE__ = [];
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = request?.command;
    const observed = new Set([
      "ja_terminal_open",
      "ja_terminal_close_all",
      "ja_preview_open",
      "ja_preview_layout",
      "ja_preview_close",
    ]);
    /** 每个阶段只写闭集命令和可见性，避免测试证据意外保留 native 参数。 */
    const append = (phase) => {
      if (!observed.has(command)) return;
      globalThis.__JA_WORKBENCH_TAB_CONTEXT_TRACE__.push({
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

/** 等待真实 App Server 连接并选择 runner 创建的隔离项目。 */
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

/** 打开可能处于收起状态的右侧栏，并返回真实 Workbench Shell。 */
async function ensureWorkbenchVisible(page, deadline) {
  const inspector = page.locator('.ja-inspector[aria-label="工作区面板"]');
  if ((await inspector.getAttribute("data-visible")) !== "true") {
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click({
      timeout: timeout(deadline),
    });
  }
  await inspector.waitFor({ state: "visible", timeout: timeout(deadline) });
  return inspector.locator(".ja-workbench");
}

/** 读取按 DOM 顺序发布的稳定 Tab key，避免用可变展示名判断身份。 */
async function tabKeys(workbench) {
  return workbench
    .locator(".ja-workbench-tab-shell[data-tab]")
    .evaluateAll((elements) => elements.map((element) => element.getAttribute("data-tab")));
}

/** 读取活动 Tab 的稳定 key；缺失 active 是受控状态损坏，立即失败。 */
async function activeTabKey(workbench) {
  const active = await workbench.getAttribute("data-active-tab");
  if (active === null || active === "") throw new Error("Workbench 缺少活动 Tab identity");
  return active;
}

/** 通过产品 `+` 菜单打开或聚焦 singleton capability，不直接写 React/localStorage 状态。 */
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

/** 从同一 `+` 菜单创建未持久化侧边任务草稿，证明菜单不需要 Provider 或 Task 写入。 */
async function createSideTaskDraft(page, workbench, deadline) {
  await workbench.getByRole("button", { name: "新建标签页", exact: true }).click({
    timeout: timeout(deadline),
  });
  await page.getByRole("menuitem", { name: "新建侧边任务", exact: true }).click({
    timeout: timeout(deadline),
  });
  const draft = workbench.locator('[data-workbench-tab^="side-task:draft_"]').last();
  await draft.waitFor({ state: "visible", timeout: timeout(deadline) });
  return draft;
}

/** 返回 probe 的脱敏副本，供动作前后比较真实 native ACK。 */
async function lifecycleTrace(page) {
  return page.evaluate(() =>
    (globalThis.__JA_WORKBENCH_TAB_CONTEXT_TRACE__ ?? []).map((entry) => ({ ...entry })),
  );
}

/** 统计特定命令阶段，并可按 Preview visibility 进一步限定。 */
function traceCount(trace, command, phase, visible) {
  return trace.filter(
    (entry) =>
      entry.command === command &&
      entry.phase === phase &&
      (visible === undefined || entry.visible === visible),
  ).length;
}

/** 用最终条件轮询替代任意 sleep，所有资源 ACK 均有明确超时。 */
async function waitForCondition(label, predicate, deadline) {
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} 超时`);
}

/** 在真实终端表单中选择 PowerShell 并等待 native session 进入运行态。 */
async function openNativeTerminal(page, workbench, deadline) {
  await openCapability(page, workbench, "terminal", "终端", deadline);
  const terminal = workbench.getByRole("region", { name: "终端工作区", exact: true });
  await terminal.waitFor({ state: "visible", timeout: timeout(deadline) });
  const trigger = terminal.getByRole("button", { name: /新建终端(?:标签页)?/u }).first();
  await trigger.focus();
  await trigger.press("Enter", { timeout: timeout(deadline) });
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
  await terminal
    .locator(".ja-terminal-pane-state")
    .getByText("运行中", { exact: true })
    .first()
    .waitFor({ state: "visible", timeout: timeout(deadline) });
}

/**
 * 加载本轮 Vite 的静态 PNG 以创建真实 child WebView；同源只用于本机 loopback，既不会
 * 执行第二份 App，也不会产生 Provider、互联网或用户数据访问。
 */
async function openNativePreview(page, workbench, deadline) {
  await openCapability(page, workbench, "preview", "浏览器", deadline);
  const address = workbench.getByRole("textbox", { name: "Preview 地址", exact: true });
  const target = new URL("/favicon.png", page.url()).href;
  const before = traceCount(await lifecycleTrace(page), "ja_preview_open", "resolved");
  await address.fill(target);
  await workbench.getByRole("button", { name: "刷新或访问", exact: true }).click({
    timeout: timeout(deadline),
  });
  await waitForCondition(
    "Preview open ACK",
    async () => traceCount(await lifecycleTrace(page), "ja_preview_open", "resolved") > before,
    deadline,
  );
  await waitForCondition(
    "Preview child WebView target",
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

/** 从鼠标或键盘打开目标 Tab 菜单，且返回按目标标签命名的唯一 menu。 */
async function openTabMenu(page, tab, label, deadline, input = "mouse") {
  if (input === "keyboard") {
    await tab.focus();
    await tab.press("Shift+F10", { timeout: timeout(deadline) });
  } else {
    await tab.click({ button: "right", timeout: timeout(deadline) });
  }
  const menu = page.getByRole("menu", { name: `${label} 标签页操作`, exact: true });
  await menu.waitFor({ state: "visible", timeout: timeout(deadline) });
  return menu;
}

/** 断言当前 Tab 闭集与活动身份，错误报告不含 workspace 等敏感值。 */
async function assertTabState(workbench, expectedTabs, expectedActive) {
  assert.deepEqual(await tabKeys(workbench), expectedTabs);
  assert.equal(await activeTabKey(workbench), expectedActive);
}

/**
 * 执行完整菜单序列：右键不切 active、Shift+F10/Escape、rename、关闭右侧/其他/单个/全部，
 * 并验证 active Browser 菜单显隐能真实暂停和恢复 native child WebView。
 */
export async function runWorkbenchTabContextWebView2({ page, evidenceDirectory }) {
  assert.ok(page, "page is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + 5 * 60_000;
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  await page.context().addInitScript(installWorkbenchLifecycleProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await prepareApplication(page, deadline);
  let workbench = await ensureWorkbenchVisible(page, deadline);

  await openCapability(page, workbench, "files", "文件", deadline);
  await openNativeTerminal(page, workbench, deadline);
  assert.ok(traceCount(await lifecycleTrace(page), "ja_terminal_open", "resolved") >= 1);
  const previewTarget = await openNativePreview(page, workbench, deadline);
  const draft = await createSideTaskDraft(page, workbench, deadline);
  const draftKey = await draft.getAttribute("data-workbench-tab");
  assert.ok(draftKey?.startsWith("side-task:draft_"));

  const browserTab = workbench.locator('[data-workbench-tab="preview"]');
  await browserTab.click({ timeout: timeout(deadline) });
  const visibleBeforeMenu = traceCount(
    await lifecycleTrace(page),
    "ja_preview_layout",
    "resolved",
    true,
  );
  const activeBeforeContext = await activeTabKey(workbench);
  let menu = await openTabMenu(page, browserTab, "浏览器", deadline);
  assert.equal(await activeTabKey(workbench), activeBeforeContext, "右击不应改变活动 Tab");
  await waitForCondition(
    "active Browser 菜单隐藏 native child WebView",
    async () => traceCount(await lifecycleTrace(page), "ja_preview_layout", "resolved", false) >= 1,
    deadline,
  );
  const menuBox = await menu.boundingBox();
  const viewportBox = await workbench.locator(".ja-preview-viewport").boundingBox();
  assert.ok(
    menuBox !== null && viewportBox !== null,
    "Browser menu 与 Preview viewport 必须可测量",
  );
  const overlapHeight = Math.max(
    0,
    Math.min(menuBox.y + menuBox.height, viewportBox.y + viewportBox.height) -
      Math.max(menuBox.y, viewportBox.y),
  );
  assert.ok(overlapHeight > 0, "Browser menu 必须覆盖 child viewport 区域才能证明 occlusion 门控");
  const menuScreenshot = join(evidenceDirectory, "active-browser-context-menu.png");
  await page.screenshot({ path: menuScreenshot, animations: "disabled" });
  await page.keyboard.press("Escape");
  await menu.waitFor({ state: "detached", timeout: timeout(deadline) });
  assert.equal(
    await browserTab.evaluate((element) => globalThis.document.activeElement === element),
    true,
  );
  await waitForCondition(
    "Browser 菜单关闭恢复 native child WebView",
    async () =>
      traceCount(await lifecycleTrace(page), "ja_preview_layout", "resolved", true) >
      visibleBeforeMenu,
    deadline,
  );

  menu = await openTabMenu(page, browserTab, "浏览器", deadline);
  await menu.getByRole("menuitem", { name: "关闭右侧标签页", exact: true }).click({
    timeout: timeout(deadline),
  });
  await draft.waitFor({ state: "detached", timeout: timeout(deadline) });
  await assertTabState(workbench, ["files", "terminal", "preview"], "preview");

  const renamedDraft = await createSideTaskDraft(page, workbench, deadline);
  const renamedDraftKey = await renamedDraft.getAttribute("data-workbench-tab");
  menu = await openTabMenu(page, renamedDraft, "新侧边任务", deadline, "keyboard");
  await menu.getByRole("menuitem", { name: "重命名", exact: true }).click({
    timeout: timeout(deadline),
  });
  const renameInput = workbench.getByRole("textbox", { name: "侧边任务名称", exact: true });
  await renameInput.fill("原生菜单侧边任务");
  await renameInput.press("Enter");
  const renamedTab = workbench.locator(`[data-workbench-tab="${renamedDraftKey}"]`);
  await renamedTab.getByText("原生菜单侧边任务", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  assert.equal(await renamedTab.getAttribute("data-workbench-tab"), renamedDraftKey);

  const terminalCloseBeforeOthers = traceCount(
    await lifecycleTrace(page),
    "ja_terminal_close_all",
    "resolved",
  );
  menu = await openTabMenu(page, browserTab, "浏览器", deadline);
  await menu.getByRole("menuitem", { name: "关闭其他标签页", exact: true }).click({
    timeout: timeout(deadline),
  });
  await waitForCondition(
    "关闭其他标签页完成 Terminal teardown",
    async () =>
      traceCount(await lifecycleTrace(page), "ja_terminal_close_all", "resolved") >
      terminalCloseBeforeOthers,
    deadline,
  );
  await assertTabState(workbench, ["preview"], "preview");

  await openCapability(page, workbench, "files", "文件", deadline);
  await openCapability(page, workbench, "terminal", "终端", deadline);
  const previewCloseBefore = traceCount(await lifecycleTrace(page), "ja_preview_close", "resolved");
  menu = await openTabMenu(page, browserTab, "浏览器", deadline);
  await menu.getByRole("menuitem", { name: "关闭", exact: true }).click({
    timeout: timeout(deadline),
  });
  await browserTab.waitFor({ state: "detached", timeout: timeout(deadline) });
  await waitForCondition(
    "关闭 Browser Tab 完成 native child teardown",
    async () =>
      traceCount(await lifecycleTrace(page), "ja_preview_close", "resolved") > previewCloseBefore &&
      !page
        .context()
        .pages()
        .some(
          (candidate) =>
            candidate !== page && !candidate.isClosed() && candidate.url() === previewTarget,
        ),
    deadline,
  );
  await assertTabState(workbench, ["files", "terminal"], "terminal");

  await openCapability(page, workbench, "preview", "浏览器", deadline);
  await createSideTaskDraft(page, workbench, deadline);
  const filesTab = workbench.locator('[data-workbench-tab="files"]');
  const activeBeforeInactiveContext = await activeTabKey(workbench);
  menu = await openTabMenu(page, filesTab, "文件", deadline);
  assert.equal(
    await activeTabKey(workbench),
    activeBeforeInactiveContext,
    "右击非活动 Tab 不应选中它",
  );
  await menu.getByRole("menuitem", { name: "关闭全部标签页", exact: true }).click({
    timeout: timeout(deadline),
  });
  await waitForCondition(
    "关闭全部标签页",
    async () => (await tabKeys(workbench)).length === 0,
    deadline,
  );
  await page.getByRole("button", { name: "显示工作区面板", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const finalScreenshot = join(evidenceDirectory, "all-tabs-closed.png");
  await page.screenshot({ path: finalScreenshot, animations: "disabled" });

  assert.deepEqual(pageErrors, []);
  const trace = await lifecycleTrace(page);
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    verdict: "PASS",
    menu: {
      rightClickPreservesActive: true,
      keyboardEntry: "Shift+F10",
      escapeRestoresFocus: true,
      renamePreservesIdentity: true,
      closeActions: ["close", "close_others", "close_right", "close_all"],
    },
    lifecycle: {
      terminalOpenResolved: traceCount(trace, "ja_terminal_open", "resolved"),
      terminalCloseAllResolved: traceCount(trace, "ja_terminal_close_all", "resolved"),
      previewOpenResolved: traceCount(trace, "ja_preview_open", "resolved"),
      previewHiddenLayoutResolved: traceCount(trace, "ja_preview_layout", "resolved", false),
      previewVisibleLayoutResolved: traceCount(trace, "ja_preview_layout", "resolved", true),
      previewCloseResolved: traceCount(trace, "ja_preview_close", "resolved"),
      childOcclusionGate: "hidden_while_menu_open_restored_after_escape",
    },
    screenshots: [menuScreenshot, finalScreenshot],
    pageErrors,
  };
}

/** 防止一组布尔标记冒充真窗闭环，校验菜单动作与两类 native teardown 的最小证据。 */
export function validateWorkbenchTabContextReport(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.runtime, "tauri_webview2");
  assert.equal(report?.verdict, "PASS");
  assert.deepEqual(report?.menu?.closeActions, [
    "close",
    "close_others",
    "close_right",
    "close_all",
  ]);
  assert.ok(report?.menu?.rightClickPreservesActive);
  assert.ok(report?.menu?.escapeRestoresFocus);
  assert.ok(report?.menu?.renamePreservesIdentity);
  assert.ok(report?.lifecycle?.terminalOpenResolved >= 1);
  assert.ok(report?.lifecycle?.terminalCloseAllResolved >= 1);
  assert.ok(report?.lifecycle?.previewOpenResolved >= 1);
  assert.ok(report?.lifecycle?.previewHiddenLayoutResolved >= 1);
  assert.ok(report?.lifecycle?.previewVisibleLayoutResolved >= 2);
  assert.ok(report?.lifecycle?.previewCloseResolved >= 1);
  assert.equal(
    report?.lifecycle?.childOcclusionGate,
    "hidden_while_menu_open_restored_after_escape",
  );
  assert.deepEqual(report?.pageErrors, []);
  return report;
}

/** 解析本 runner 的窄 CLI，不把 production runner 的 Review scope 暴露为用户可选项。 */
function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-review-redesign"),
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
  if (options.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (options.jar === undefined) throw new Error("--jar is required");
  return options;
}

/** 复用 production 隔离启动器，场景报告写入独立文件且不经过 Review 报告校验。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const report = await runProduction({
    ...options,
    scope: "git",
    fixture: "no-head",
    ignoredFiles: 0,
    untrackedFiles: 0,
    driver: runWorkbenchTabContextWebView2,
    validateReport: validateWorkbenchTabContextReport,
    reportFileName: "workbench-tab-context-report.json",
  });
  console.log(`JA_WORKBENCH_TAB_CONTEXT_PASS ${JSON.stringify({ verdict: report.verdict })}`);
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(
      `JA_WORKBENCH_TAB_CONTEXT_FAIL ${String(error?.message ?? error).slice(0, 2_000)}`,
    );
    process.exitCode = 1;
  });
}
