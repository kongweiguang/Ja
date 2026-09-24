// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 右键入口的隔离 Windows Tauri/WebView2 验收。使用真实 App Server、临时 workspace/profile
 * 与 loopback Provider；截图、报告和操作目标都只属于本次随机测试运行。
 */

import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";
import {
  conversationProgressFixtureMarkers,
  startConversationProgressFixture,
} from "./fixtures/conversation-progress.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const STEP_TIMEOUT_MS = 30_000;
const TURN_TIMEOUT_MS = 120_000;
const MESSAGE_PROMPT = "请先读取隔离 fixture，再执行一次低影响 Shell 回显，最后总结结果。";

/** 把每个浏览器等待限制在单步骤预算内，超时报告因此能指向具体菜单交互。 */
function timeout(deadline) {
  return Math.max(1, Math.min(STEP_TIMEOUT_MS, deadline - Date.now()));
}

/** 记录闭集调用轨迹；剪贴板原生入口受保护，验收脚本不接触宿主剪贴板。 */
export function installContextMenuInvokeProbe() {
  const allowlistedWorkspaceErrorCodes = new Set([
    "NOT_CONFIGURED",
    "UNKNOWN_WORKSPACE",
    "INVALID_INPUT",
    "INVALID_PATH",
    "PATH_REJECTED",
    "NOT_FOUND",
    "NOT_DIRECTORY",
    "NOT_FILE",
    "STALE_CURSOR",
    "LIMIT_EXCEEDED",
    "CHANGED_DURING_READ",
    "ALREADY_EXISTS",
    "REVISION_CONFLICT",
    "WORKSPACE_RECOVERY_REQUIRED",
    "MUTATION_ALREADY_USED",
    "INVALID_MUTATION_ID",
    "UNSUPPORTED_CONTENT",
    "TRASH_TOKEN_INVALID",
    "TRASH_TOKEN_EXPIRED",
    "RECYCLE_UNAVAILABLE",
    "DROP_TOKEN_INVALID",
    "WATCH_UNAVAILABLE",
    "IO",
    "RUNTIME_UNAVAILABLE",
  ]);
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  globalThis.__JA_CONTEXT_MENU_INVOKES__ = [];
  globalThis.__JA_CONTEXT_MENU_READ_FILE_CALL_ID__ = 0;
  globalThis.__JA_CONTEXT_MENU_EXPECTED_WORKSPACE_ID__ ??= null;
  globalThis.__JA_CONTEXT_MENU_EXPECTED_RELATIVE_PATH__ ??= null;
  globalThis.__JA_CONTEXT_MENU_RUNTIME_START__ = null;
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = typeof request?.command === "string" ? request.command : "unknown";
    const trace = globalThis.__JA_CONTEXT_MENU_INVOKES__;
    const readFileCommand = command === "ja_workspace_read_file";
    const callId = readFileCommand
      ? (globalThis.__JA_CONTEXT_MENU_READ_FILE_CALL_ID__ ??= 0) + 1
      : undefined;
    if (readFileCommand) globalThis.__JA_CONTEXT_MENU_READ_FILE_CALL_ID__ = callId;
    const input = request?.args?.input;
    const readFileIdentity = readFileCommand
      ? {
          callId,
          workspaceMatchesFixture:
            typeof globalThis.__JA_CONTEXT_MENU_EXPECTED_WORKSPACE_ID__ === "string" &&
            input?.workspaceId === globalThis.__JA_CONTEXT_MENU_EXPECTED_WORKSPACE_ID__,
          pathMatchesExpected:
            typeof globalThis.__JA_CONTEXT_MENU_EXPECTED_RELATIVE_PATH__ === "string" &&
            input?.relativePath === globalThis.__JA_CONTEXT_MENU_EXPECTED_RELATIVE_PATH__,
        }
      : {};
    trace.push({ command, phase: "start", ...readFileIdentity });
    try {
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      trace.push({ command, phase: "resolved", ...readFileIdentity });
      return result;
    } catch (error) {
      const rawCode =
        error !== null && typeof error === "object" && typeof error.code === "string"
          ? error.code
          : undefined;
      trace.push({
        command,
        phase: "rejected",
        ...readFileIdentity,
        ...(readFileCommand
          ? {
              errorCode:
                rawCode !== undefined && allowlistedWorkspaceErrorCodes.has(rawCode)
                  ? rawCode
                  : "UNCLASSIFIED",
            }
          : {}),
      });
      throw error;
    }
  };
}

/** 只导出 workspace read 的调用阶段、脱敏 error code 与身份匹配布尔值。 */
async function readWorkspaceReadSamples(page, afterCallId, deadline) {
  await page
    .waitForFunction(
      (previousCallId) =>
        (globalThis.__JA_CONTEXT_MENU_INVOKES__ ?? []).some(
          (entry) =>
            entry.command === "ja_workspace_read_file" &&
            entry.callId > previousCallId &&
            entry.phase !== "start",
        ),
      afterCallId,
      { timeout: Math.min(5_000, timeout(deadline)) },
    )
    .catch(() => undefined);
  return page.evaluate(
    (previousCallId) =>
      (globalThis.__JA_CONTEXT_MENU_INVOKES__ ?? [])
        .filter(
          (entry) => entry.command === "ja_workspace_read_file" && entry.callId > previousCallId,
        )
        .map((entry) => ({
          callId: entry.callId,
          command: "ja_workspace_read_file",
          phase: entry.phase,
          workspaceMatchesFixture: entry.workspaceMatchesFixture === true,
          pathMatchesExpected: entry.pathMatchesExpected === true,
          ...(typeof entry.errorCode === "string" ? { errorCode: entry.errorCode } : {}),
        })),
    afterCallId,
  );
}

/** 读取单调 read-call 身份以便将树行和搜索动作的调用分别归档。 */
async function latestWorkspaceReadCallId(page) {
  return page.evaluate(() => globalThis.__JA_CONTEXT_MENU_READ_FILE_CALL_ID__ ?? 0);
}

/** 等待隔离应用可交互，并确认真实 App Server 已连接。 */
async function waitForApplication(page, deadline, { retryFailedRuntime = false } = {}) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await ensureSidebarVisible(page, deadline);
  const runtimeStatus = page.locator('.ja-navigation-runtime[role="status"]');
  await runtimeStatus.waitFor({ state: "visible", timeout: timeout(deadline) });
  const runtimeReady = await page
    .waitForFunction(
      () =>
        globalThis.document
          .querySelector('.ja-navigation-runtime[role="status"]')
          ?.getAttribute("aria-label") === "本地运行时：已连接",
      undefined,
      { timeout: Math.min(10_000, timeout(deadline)) },
    )
    .then(() => true)
    .catch(() => false);
  if (!runtimeReady) {
    const label = await runtimeStatus.getAttribute("aria-label").catch(() => null);
    const text = (await runtimeStatus.textContent().catch(() => null))?.trim() ?? "";
    const nativeState = await readRuntimeHostDiagnostic(page);
    let issue = null;
    const issueButton = page.getByRole("button", { name: "运行时异常详情", exact: true });
    if (await issueButton.isVisible().catch(() => false)) {
      await issueButton.click({ timeout: timeout(deadline) }).catch(() => undefined);
      const popover = page.locator(".ja-navigation-runtime-popover");
      await popover.waitFor({ state: "visible", timeout: 1_000 }).catch(() => undefined);
      issue = (await popover.textContent().catch(() => null))?.trim() ?? null;
      if (retryFailedRuntime) {
        // The current WebView predates init scripts, so wrap the already-loaded typed bridge before its explicit retry.
        const runtimeProbeInstalled = await installRuntimeStartProbe(page);
        assert.equal(runtimeProbeInstalled, true, "the typed runtime probe must arm before retry");
        await popover.getByRole("button", { name: "重新启动", exact: true }).click({
          timeout: timeout(deadline),
        });
        const recovered = await page
          .waitForFunction(
            () =>
              globalThis.document
                .querySelector('.ja-navigation-runtime[role="status"]')
                ?.getAttribute("aria-label") === "本地运行时：已连接",
            undefined,
            { timeout: Math.min(15_000, timeout(deadline)) },
          )
          .then(() => true)
          .catch(() => false);
        const runtimeStart = await page.evaluate(
          () => globalThis.__JA_CONTEXT_MENU_RUNTIME_START__ ?? null,
        );
        if (recovered) {
          await page.keyboard.press("Escape").catch(() => undefined);
          return {
            recoveredAfterRetry: true,
            initial: { label, text, nativeState, issue },
            runtimeStart,
          };
        }
        const retryState = await readRuntimeHostDiagnostic(page);
        const retryLabel = await runtimeStatus.getAttribute("aria-label").catch(() => null);
        throw new Error(
          `isolated runtime retry failed: initial=${JSON.stringify({ label, text, nativeState, issue })}; retryLabel=${retryLabel ?? "missing"}; retryState=${JSON.stringify(retryState)}; runtimeStart=${JSON.stringify(runtimeStart)}`,
        );
      }
    }
    throw new Error(
      `isolated runtime not ready: aria-label=${label ?? "missing"}; text=${text}; nativeState=${JSON.stringify(nativeState)}; issue=${issue ?? "unavailable"}`,
    );
  }
  return { recoveredAfterRetry: false };
}

/** 通过只读 typed Host state 区分 React 连接文案与 Rust sidecar 生命周期结果。 */
async function readRuntimeHostDiagnostic(page) {
  return page.evaluate(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    try {
      const state = await new TauriRuntimeHostAdapter().state();
      return {
        status: state.status,
        generation: state.generation,
        featureCount: state.features.length,
      };
    } catch (error) {
      return {
        errorCode: error?.code === "RUNTIME_UNAVAILABLE" ? error.code : "UNCLASSIFIED",
      };
    }
  });
}

/** 在隔离页包裹已加载的 typed bridge，只记录 runtime start 的稳定状态或允许列表错误码。 */
async function installRuntimeStartProbe(page) {
  return page.evaluate(async () => {
    const { defaultNativeBridge } = await import("/src/api/tauri/runtime.ts");
    const bridge = defaultNativeBridge;
    if (globalThis.__JA_CONTEXT_MENU_TYPED_RUNTIME_PROBE_INSTALLED__ === true) return true;
    const originalInvoke = bridge.invoke;
    const allowedCodes = new Set([
      "RUNTIME_CONFIG_INVALID",
      "INVALID_PARAMS",
      "RUNTIME_UNAVAILABLE",
      "RUNTIME_QUEUE_FULL",
      "RUNTIME_COMMAND_DEADLINE",
      "RUNTIME_SHUTDOWN_TIMEOUT",
      "RUNTIME_EVENT_DELIVERY_FAILED",
      "RECOVERY_REQUIRED",
      "RECOVERY_STALE",
      "PROTOCOL_INCOMPATIBLE",
      "RUNTIME_FAULTED",
      "RUNTIME_BACKOFF",
      "SHUTTING_DOWN",
      "RUNTIME_NOT_READY",
      "RUNTIME_TIMEOUT",
      "SIDECAR_CRASHED",
      "RUNTIME_PROTOCOL_ERROR",
    ]);
    bridge.invoke = async function (command, args) {
      if (command !== "ja_runtime_start") return originalInvoke.call(this, command, args);
      try {
        const result = await originalInvoke.call(this, command, args);
        globalThis.__JA_CONTEXT_MENU_RUNTIME_START__ = {
          phase: "resolved",
          status: typeof result?.status === "string" ? result.status : "unknown",
          generation: Number.isSafeInteger(result?.generation) ? result.generation : null,
        };
        return result;
      } catch (error) {
        const rawCode =
          error !== null && typeof error === "object" && typeof error.code === "string"
            ? error.code
            : undefined;
        globalThis.__JA_CONTEXT_MENU_RUNTIME_START__ = {
          phase: "rejected",
          errorCode: rawCode !== undefined && allowedCodes.has(rawCode) ? rawCode : "UNCLASSIFIED",
        };
        throw error;
      }
    };
    globalThis.__JA_CONTEXT_MENU_RUNTIME_START__ = null;
    globalThis.__JA_CONTEXT_MENU_TYPED_RUNTIME_PROBE_INSTALLED__ = true;
    return true;
  });
}

/** 经生产 typed adapter 创建 Thread，并验证服务端将两者持久绑定到隔离 Workspace identity。 */
async function createNavigationFixture(page, workspaceRoot, providerBaseUrl) {
  return page.evaluate(
    async ({ cwd, endpoint }) => {
      const [{ TauriSettingsAdapter }, { createHistoryAdapter }] = await Promise.all([
        import("/src/api/tauri/settings.ts"),
        import("/src/api/tauri/history.ts"),
      ]);
      const settings = new TauriSettingsAdapter();
      const snapshot = await settings.snapshot();
      const provider = snapshot.document.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (provider === undefined || provider.baseUrl !== endpoint)
        throw new Error("isolated loopback Provider was not staged");
      const modelId = provider.models[0]?.modelId;
      if (modelId === undefined) throw new Error("isolated Provider model is missing");

      const history = createHistoryAdapter();
      const workspace = await history.workspaceOpen({ cwd, displayName: "右键菜单 E2E" });
      const createThread = (title) =>
        history.threadCreate({
          cwd: workspace.root,
          title,
          providerId: provider.providerId,
          modelId,
          reasoningLevel: null,
          accessMode: "full_access",
          collaborationMode: "default",
        });
      const [primary, secondary] = await Promise.all([
        createThread("右键菜单消息会话"),
        createThread("右键菜单导航会话"),
      ]);
      if (
        primary.workspaceId !== workspace.workspaceId ||
        secondary.workspaceId !== workspace.workspaceId
      )
        throw new Error("isolated conversation fixture workspace identity mismatch");
      return {
        workspaceId: workspace.workspaceId,
        workspaceName: workspace.displayName,
        primaryThreadId: primary.threadId,
        secondaryThreadId: secondary.threadId,
      };
    },
    { cwd: workspaceRoot, endpoint: providerBaseUrl },
  );
}

/** 选择真实项目并等待异步 Workspace identity 落到 aria-current，避免菜单场景命中上一会话状态。 */
async function selectProject(page, workspaceName, deadline) {
  const project = page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: workspaceName });
  await project.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await project.getAttribute("aria-current")) !== "page")
    await project.click({ timeout: timeout(deadline) });
  await page.waitForFunction(
    (expectedName) =>
      [
        ...globalThis.document.querySelectorAll(
          '[aria-label="项目列表"] button[data-scope-kind="project"]',
        ),
      ].some(
        (row) =>
          row.textContent?.includes(expectedName) && row.getAttribute("aria-current") === "page",
      ),
    workspaceName,
    { timeout: timeout(deadline) },
  );
}

/** 按真实 Thread ID 选择会话，避免用重名标题路由右键目标。 */
async function selectThread(page, threadId, deadline) {
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await row.getAttribute("aria-current")) !== "page")
    await row.click({ timeout: timeout(deadline) });
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: timeout(deadline) },
  );
  return row;
}

/** 响应式断点或 Settings 路由会卸载导航；通过标题栏公开按钮重开真实侧栏，而非操作隐藏 DOM。 */
async function ensureSidebarVisible(page, deadline) {
  const sidebar = page.getByRole("complementary", { name: "项目与对话导航", exact: true });
  if (!(await sidebar.isVisible().catch(() => false))) {
    await page.getByRole("button", { name: "显示侧边栏", exact: true }).click({
      timeout: timeout(deadline),
    });
  }
  await sidebar.waitFor({ state: "visible", timeout: timeout(deadline) });
}

/** 窄布局侧栏是覆盖式 Drawer，会遮住工作面；完成导航行验收后用真实按钮收起它。 */
async function hideSidebar(page, deadline) {
  const sidebar = page.getByRole("complementary", { name: "项目与对话导航", exact: true });
  if (!(await sidebar.isVisible().catch(() => false))) return;
  await page.getByRole("button", { name: "隐藏侧边栏", exact: true }).click({
    timeout: timeout(deadline),
  });
  await sidebar.waitFor({ state: "detached", timeout: timeout(deadline) });
}

/** 等媒体查询状态提交后再打开紧凑导航，避免 800px 刚设置时短暂读到旧宽屏侧栏。 */
async function openCompactSidebar(page, deadline) {
  await page.waitForFunction(
    () =>
      globalThis.document
        .querySelector(".ja-layout")
        ?.classList.contains("is-compact-navigation") === true,
    undefined,
    { timeout: timeout(deadline) },
  );
  await ensureSidebarVisible(page, deadline);
}

/** 主题页返回后导航分组可能仍折叠；通过真实 SectionToggle 恢复目标可见性并留下可诊断断言。 */
async function restoreNavigationContext(page, navigation, deadline) {
  await ensureSidebarVisible(page, deadline);
  const sidebar = page.getByRole("complementary", { name: "项目与对话导航", exact: true });
  const expandProjects = sidebar.getByRole("button", { name: "展开项目", exact: true });
  if (await expandProjects.count()) await expandProjects.click({ timeout: timeout(deadline) });
  const expandHistory = sidebar.getByRole("button", { name: "展开最近对话", exact: true });
  if (await expandHistory.count()) await expandHistory.click({ timeout: timeout(deadline) });

  const projectRows = sidebar.locator('[aria-label="项目列表"] button[data-scope-kind="project"]');
  const threadRows = sidebar.locator('[aria-label="最近对话列表"] button[data-thread-id]');
  await projectRows.first().waitFor({ state: "visible", timeout: timeout(deadline) });
  await threadRows.first().waitFor({ state: "visible", timeout: timeout(deadline) });
  await selectProject(page, navigation.workspaceName, deadline);
  await selectThread(page, navigation.primaryThreadId, deadline);
  // selectConversation can finish its controller-side workspace activation after the row click;
  // settle the explicit project scope last so aria-current reflects the same stable workspace.
  await selectProject(page, navigation.workspaceName, deadline);

  const [activeProject, activeThread] = await Promise.all([
    projectRows.filter({ hasText: navigation.workspaceName }).getAttribute("aria-current"),
    page
      .locator(`[aria-label="最近对话列表"] button[data-thread-id="${navigation.primaryThreadId}"]`)
      .getAttribute("aria-current"),
  ]);
  const diagnostics = await page.evaluate(() => ({
    compact: globalThis.document
      .querySelector('[aria-label="项目与对话导航"]')
      ?.getAttribute("data-compact"),
    projectSectionToggle: globalThis.document
      .querySelector(
        '[aria-label="项目与对话导航"] .ja-navigation-projects button.ja-navigation-section-toggle',
      )
      ?.getAttribute("aria-label"),
    projectRows: [
      ...globalThis.document.querySelectorAll(
        '[aria-label="项目列表"] button[data-scope-kind="project"]',
      ),
    ].map((row) => ({
      label: row.getAttribute("aria-label"),
      current: row.getAttribute("aria-current"),
      active: row.getAttribute("data-active"),
      disabled: row.hasAttribute("disabled"),
    })),
    generalScope: globalThis.document
      .querySelector('[aria-label="项目列表"] button[data-scope-kind="general"]')
      ?.getAttribute("aria-current"),
    currentThreadId: globalThis.document
      .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
      ?.getAttribute("data-thread-id"),
    settingsRoute: globalThis.document.querySelector(".ja-settings-layer") !== null,
  }));
  assert.equal(
    activeProject,
    "page",
    `workspace selection did not restore in wide viewport; ${JSON.stringify(diagnostics)}`,
  );
  assert.equal(
    activeThread,
    "page",
    `thread selection did not restore in wide viewport; ${JSON.stringify(diagnostics)}`,
  );
}

/** 复用当前 Thread 的可见 Workbench；旧 Thread Host 会保留但 aria-hidden，需按 active 可见 Host 定位。 */
async function ensureWorkbenchVisible(page, deadline) {
  const inspector = page.locator(
    '.ja-inspector[aria-label="工作区面板"][data-visible="true"]:not([aria-hidden="true"])',
  );
  if ((await inspector.count()) === 0) {
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click({
      timeout: timeout(deadline),
    });
  }
  await inspector.waitFor({ state: "visible", timeout: timeout(deadline) });
  return inspector.locator(".ja-workbench");
}

/** 等当前 Thread Host 上的 active key；旧 Workbench 保留在 DOM，不能用全局同名标签做断言。 */
async function waitForWorkbenchTabActive(page, key, deadline) {
  await page
    .locator(
      `.ja-inspector[data-visible="true"]:not([aria-hidden="true"]) .ja-workbench[data-active-tab="${key}"]`,
    )
    .waitFor({ state: "visible", timeout: timeout(deadline) });
}

/** 通过真实新建菜单打开 Workbench 能力并等待当前 Host active key；标签出现不等于面板已切换。 */
async function openCapability(page, workbench, key, label, deadline) {
  const tab = workbench.locator(`[data-workbench-tab="${key}"]`);
  if ((await tab.count()) === 1) {
    await tab.click({ timeout: timeout(deadline) });
  } else {
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
    await tab.waitFor({ state: "visible", timeout: timeout(deadline) });
  }
  await waitForWorkbenchTabActive(page, key, deadline);
  return tab;
}

/** 设置主题只经现有控件完成，并等待语义主题与应用路由稳定后再恢复导航目标。 */
async function setThemeMode(page, mode, deadline) {
  await page.getByRole("button", { name: "设置", exact: true }).click({
    timeout: timeout(deadline),
  });
  await page.getByRole("tab", { name: "外观", exact: true }).click({
    timeout: timeout(deadline),
  });
  const theme = page.getByRole("combobox", { name: "外观模式", exact: true });
  await theme.click({ timeout: timeout(deadline) });
  await page.getByRole("option", { name: mode === "light" ? "浅色" : "深色", exact: true }).click({
    timeout: timeout(deadline),
  });
  await page.waitForFunction(
    (expected) => globalThis.document.documentElement.dataset.theme === expected,
    mode,
    { timeout: timeout(deadline) },
  );
  await page.getByRole("button", { name: "返回应用", exact: true }).click({
    timeout: timeout(deadline),
  });
  await waitForApplication(page, deadline);
}

/** 以鼠标、ContextMenu 键或 Shift+F10 打开菜单并验证其可访问名称。 */
async function openContextMenu(page, target, label, deadline, input = "mouse", position) {
  if (input === "mouse") {
    await target.click({ button: "right", position, timeout: timeout(deadline) });
  } else {
    await target.focus({ timeout: timeout(deadline) });
    await target.press(input, { timeout: timeout(deadline) });
  }
  const menu = page.getByRole("menu", { name: label, exact: true });
  await menu.waitFor({ state: "visible", timeout: timeout(deadline) });
  return menu;
}

/** 确认菜单必需动作确实可见，禁用态仍属于可见且不可触发的真实入口。 */
async function requireMenuItem(menu, name, deadline) {
  const item = menu.getByRole("menuitem", { name, exact: true });
  await item.waitFor({ state: "visible", timeout: timeout(deadline) });
  return item;
}

/** 验证菜单保持在当前窄视口内，且报告不依赖屏幕绝对坐标。 */
async function assertMenuInsideViewport(page, target, menu) {
  const [targetBox, menuBox, viewport] = await Promise.all([
    target.boundingBox(),
    menu.boundingBox(),
    page.evaluate(() => ({ width: globalThis.innerWidth, height: globalThis.innerHeight })),
  ]);
  assert.ok(targetBox && menuBox, "target and context menu must have measurable bounds");
  assert.ok(menuBox.x >= 0 && menuBox.y >= 0, "context menu must not start outside viewport");
  assert.ok(
    menuBox.x + menuBox.width <= viewport.width + 1,
    "context menu must fit viewport width",
  );
  assert.ok(
    menuBox.y + menuBox.height <= viewport.height + 1,
    "context menu must fit viewport height",
  );
  return {
    targetNearEdge:
      viewport.width - (targetBox.x + targetBox.width) < 240 ||
      viewport.height - (targetBox.y + targetBox.height) < 180,
  };
}

/** 保存合成后的真实 Tauri WebView，不额外创建静态 HTML 代替产品界面。 */
async function captureMenu(page, menu, target, evidenceDirectory, name) {
  const edge = await assertMenuInsideViewport(page, target, menu);
  const screenshot = join(evidenceDirectory, name);
  await page.screenshot({ path: screenshot, animations: "disabled" });
  const background = await menu.evaluate(
    (element) => globalThis.getComputedStyle(element).backgroundColor,
  );
  assert.notEqual(background, "rgba(0, 0, 0, 0)", "floating menu must use a visible theme surface");
  return { screenshot, background, targetNearEdge: edge.targetNearEdge };
}

/** 单个菜单区域失败时保留当前真窗 DOM/截图并继续其他独立区域，避免首个缺陷吞掉剩余验收。 */
async function captureScenarioFailure(page, area, error, evidenceDirectory, screenshots) {
  const filename = area + "-failure.png";
  const screenshot = join(evidenceDirectory, filename);
  let screenshotCaptured = false;
  try {
    await page.screenshot({ path: screenshot, animations: "disabled" });
    screenshots.push({ screenshot, background: "", targetNearEdge: false });
    screenshotCaptured = true;
  } catch {
    // A failed/closed WebView can prevent screenshot capture; preserve the structured failure regardless.
  }
  const diagnostics = await page
    .evaluate(() => ({
      activeWorkbenchTab: globalThis.document
        .querySelector(".ja-inspector[data-visible='true'] .ja-workbench")
        ?.getAttribute("data-active-tab"),
      openMenus: [...globalThis.document.querySelectorAll('[role="menu"]')].map((menu) =>
        menu.getAttribute("aria-label"),
      ),
      reviewRows: [...globalThis.document.querySelectorAll("[data-review-file-id]")].map((row) => ({
        id: row.getAttribute("data-review-file-id"),
        layer: row.getAttribute("data-review-layer"),
        selected: row.getAttribute("aria-selected"),
        pressed: row.getAttribute("aria-pressed"),
      })),
    }))
    .catch(() => ({ diagnosticUnavailable: true }));
  const reviewSelection =
    error !== null && typeof error === "object" && "reviewSelection" in error
      ? error.reviewSelection
      : undefined;
  return {
    area,
    error: String(error?.message ?? error).slice(0, 1_000),
    screenshot: screenshotCaptured ? filename : null,
    diagnostics,
    ...(reviewSelection === undefined ? {} : { reviewSelection }),
  };
}

/** 按区域先落脱敏阶段记录，避免后续场景失败时丢失已观察的原生菜单与 read code。 */
async function writeStageEvidence(evidenceDirectory, area, coverage, failure, screenshots) {
  const stage = {
    area,
    coverage,
    failure,
    screenshots: screenshots.map((shot) => shot.screenshot.split(/[\\/]/u).at(-1)),
  };
  await writeFile(
    join(evidenceDirectory, `${area}-stage.json`),
    `${JSON.stringify(stage, null, 2)}\n`,
    "utf8",
  );
}

/** 每个场景以独立 catch 记录真窗失败，解除残留菜单焦点后再运行后续能力。 */
async function runScenarioStage(page, area, evidenceDirectory, screenshots, operation) {
  try {
    return { result: await operation(), failure: null };
  } catch (error) {
    const failure = await captureScenarioFailure(page, area, error, evidenceDirectory, screenshots);
    await page.keyboard.press("Escape").catch(() => undefined);
    return { result: undefined, failure };
  }
}

/** Escape 必须关闭 Portal 并把焦点还给来源对象；等待菜单实现排队的下一帧焦点恢复。 */
async function closeMenuAndRestoreFocus(page, target, menu, deadline) {
  await page.keyboard.press("Escape", { timeout: timeout(deadline) });
  await menu.waitFor({ state: "detached", timeout: timeout(deadline) });
  const targetElement = await target.elementHandle({ timeout: timeout(deadline) });
  assert.ok(targetElement, "the context-menu target must remain mounted through Escape");
  await page.waitForFunction(
    (expectedTarget) => globalThis.document.activeElement === expectedTarget,
    targetElement,
    { timeout: timeout(deadline) },
  );
  assert.equal(
    await target.evaluate((element) => globalThis.document.activeElement === element),
    true,
    "Escape must restore focus to the original context-menu target",
  );
}

/** 验证项目操作按指针下的 Workspace，且系统菜单键提供同样的 Escape 焦点闭环。 */
async function verifyProjectMenu(page, evidenceDirectory, deadline, screenshots) {
  const project = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  await project.waitFor({ state: "visible", timeout: timeout(deadline) });
  const activeThread = page.locator('[aria-label="最近对话列表"] button[aria-current="page"]');
  const activeThreadId = await activeThread.getAttribute("data-thread-id");
  let menu = await openContextMenu(page, project, "项目操作：右键菜单 E2E", deadline);
  await requireMenuItem(menu, "在资源管理器中打开项目目录", deadline);
  assert.equal(await activeThread.getAttribute("data-thread-id"), activeThreadId);
  screenshots.push(
    await captureMenu(page, menu, project, evidenceDirectory, "project-menu-light.png"),
  );
  await closeMenuAndRestoreFocus(page, project, menu, deadline);

  menu = await openContextMenu(page, project, "项目操作：右键菜单 E2E", deadline, "ContextMenu");
  await requireMenuItem(menu, "在资源管理器中打开项目目录", deadline);
  await closeMenuAndRestoreFocus(page, project, menu, deadline);
  return { pointerTargetStable: true, menuKey: "ContextMenu", escapeRestoresFocus: true };
}

/** 验证对话右键菜单与可逆置顶、重命名、归档动作仍绑定原 Thread identity。 */
async function verifyConversationMenu(
  page,
  secondaryThreadId,
  evidenceDirectory,
  deadline,
  screenshots,
) {
  const activeBefore = await page
    .locator('[aria-label="最近对话列表"] button[aria-current="page"]')
    .getAttribute("data-thread-id");
  let row = page.locator(
    `[aria-label="最近对话列表"] button[data-thread-id="${secondaryThreadId}"]`,
  );
  let menu = await openContextMenu(page, row, "对话操作：右键菜单导航会话", deadline);
  for (const label of ["置顶", "重命名", "打开工作文件夹", "归档"])
    await requireMenuItem(menu, label, deadline);
  assert.equal(
    await page
      .locator('[aria-label="最近对话列表"] button[aria-current="page"]')
      .getAttribute("data-thread-id"),
    activeBefore,
    "right-clicking another Thread must not select it",
  );
  screenshots.push(
    await captureMenu(page, menu, row, evidenceDirectory, "conversation-menu-light.png"),
  );
  await closeMenuAndRestoreFocus(page, row, menu, deadline);

  menu = await openContextMenu(page, row, "对话操作：右键菜单导航会话", deadline, "Shift+F10");
  await requireMenuItem(menu, "置顶", deadline);
  await closeMenuAndRestoreFocus(page, row, menu, deadline);

  menu = await openContextMenu(page, row, "对话操作：右键菜单导航会话", deadline);
  await (await requireMenuItem(menu, "置顶", deadline)).click({ timeout: timeout(deadline) });
  await row.getByRole("img", { name: "已置顶", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${secondaryThreadId}"]`);
  menu = await openContextMenu(page, row, "对话操作：右键菜单导航会话", deadline);
  await (await requireMenuItem(menu, "取消置顶", deadline)).click({ timeout: timeout(deadline) });
  await row.getByRole("img", { name: "已置顶", exact: true }).waitFor({
    state: "detached",
    timeout: timeout(deadline),
  });

  menu = await openContextMenu(page, row, "对话操作：右键菜单导航会话", deadline);
  await (await requireMenuItem(menu, "重命名", deadline)).click({ timeout: timeout(deadline) });
  const dialog = page.getByRole("dialog", { name: "重命名对话", exact: true });
  await dialog.waitFor({ state: "visible", timeout: timeout(deadline) });
  await dialog
    .getByRole("textbox", { name: "会话标题", exact: true })
    .fill("已重命名的右键菜单会话");
  await dialog
    .getByRole("button", { name: "保存", exact: true })
    .click({ timeout: timeout(deadline) });
  row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${secondaryThreadId}"]`);
  await row.getByText("已重命名的右键菜单会话", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  assert.equal(await row.getAttribute("data-thread-id"), secondaryThreadId);
  return { rightClickTargetStable: true, pinAndUnpin: true, renamePreservesIdentity: true };
}

/** 顶层 Workbench 菜单只验证目标绑定与键盘闭环；关闭动作留给各能力的资源清理路径。 */
async function verifyWorkbenchTabMenus(page, workbench, evidenceDirectory, deadline, screenshots) {
  const tabs = [
    ["files", "文件"],
    ["preview", "浏览器"],
    ["terminal", "终端"],
  ];
  for (const [key, label] of tabs) await openCapability(page, workbench, key, label, deadline);
  const filesTab = workbench.locator('[data-workbench-tab="files"]');
  await filesTab.click({ timeout: timeout(deadline) });
  await waitForWorkbenchTabActive(page, "files", deadline);
  const activeBefore = await workbench.getAttribute("data-active-tab");
  for (const [key, label] of tabs) {
    const tab = workbench.locator(`[data-workbench-tab="${key}"]`);
    const menuLabel = `${label} 标签页操作`;
    const menu = await openContextMenu(page, tab, menuLabel, deadline);
    assert.equal(await workbench.getAttribute("data-active-tab"), activeBefore);
    await requireMenuItem(menu, "关闭", deadline);
    screenshots.push(
      await captureMenu(page, menu, tab, evidenceDirectory, `workbench-${key}-menu-light.png`),
    );
    await closeMenuAndRestoreFocus(page, tab, menu, deadline);
    const keyboardMenu = await openContextMenu(
      page,
      tab,
      menuLabel,
      deadline,
      key === "files" ? "ContextMenu" : "Shift+F10",
    );
    await closeMenuAndRestoreFocus(page, tab, keyboardMenu, deadline);
  }
  return {
    tabKinds: tabs.map(([key]) => key),
    rightClickPreservesActive: true,
    keyboardEntry: true,
    escapeRestoresFocus: true,
  };
}

/** 验证代码正文保留原交互，以及文件标签的键盘、Escape 与关闭清理。 */
async function verifyFileTabMenu(
  page,
  files,
  filePath,
  evidenceDirectory,
  deadline,
  screenshots,
  captureScreenshot = true,
) {
  const fileName = filePath.split("/").at(-1);
  const fileTab = files.locator('[data-file-tab-path="' + filePath + '"]');
  await fileTab.waitFor({ state: "visible", timeout: timeout(deadline) });
  const editorBody = files.locator(".ja-files-editor-content .cm-content").first();
  await editorBody.waitFor({ state: "visible", timeout: timeout(deadline) });
  await editorBody.click({
    button: "right",
    position: { x: 12, y: 12 },
    timeout: timeout(deadline),
  });
  assert.equal(await page.getByRole("menu").count(), 0, "code editing must not open a Ja menu");
  const fileTabButton = fileTab.getByRole("tab");
  const menuLabel = fileName + " 标签操作";
  const fileTabMenu = await openContextMenu(page, fileTabButton, menuLabel, deadline);
  await requireMenuItem(fileTabMenu, "关闭", deadline);
  if (captureScreenshot)
    screenshots.push(
      await captureMenu(
        page,
        fileTabMenu,
        fileTabButton,
        evidenceDirectory,
        "file-tab-menu-light.png",
      ),
    );
  await closeMenuAndRestoreFocus(page, fileTabButton, fileTabMenu, deadline);
  const fileTabKeyboardMenu = await openContextMenu(
    page,
    fileTabButton,
    menuLabel,
    deadline,
    "Shift+F10",
  );
  await closeMenuAndRestoreFocus(page, fileTabButton, fileTabKeyboardMenu, deadline);
  const closeFileTabMenu = await openContextMenu(page, fileTabButton, menuLabel, deadline);
  await (
    await requireMenuItem(closeFileTabMenu, "关闭", deadline)
  ).click({
    timeout: timeout(deadline),
  });
  await fileTab.waitFor({ state: "detached", timeout: timeout(deadline) });
}

/** 在真实 Files workspace 中覆盖短菜单、二级打开方式、搜索与标签；打开失败时留证并继续独立场景。 */
async function verifyFilesMenus(page, workbench, evidenceDirectory, deadline, screenshots) {
  await openCapability(page, workbench, "files", "文件", deadline);
  const files = page.getByRole("region", { name: "文件工作区", exact: true });
  const tree = files.getByRole("tree", { name: "工作区文件", exact: true });
  await tree.waitFor({ state: "visible", timeout: timeout(deadline) });
  const srcDisclosure = files.getByRole("button", { name: "展开src", exact: true });
  if (await srcDisclosure.isVisible().catch(() => false))
    await srcDisclosure.click({ timeout: timeout(deadline) });
  const fileRow = files.locator('.ja-file-tree-row[data-path="src/conflict.ts"]');
  await fileRow.waitFor({ state: "visible", timeout: timeout(deadline) });
  const treeMenu = await openContextMenu(page, fileRow, "conflict.ts 文件操作", deadline);
  for (const label of ["新建文件", "新建目录", "重命名", "移入回收站", "刷新此目录"])
    await requireMenuItem(treeMenu, label, deadline);
  await requireMenuItem(treeMenu, "在文件资源管理器中显示", deadline);
  const openWithTrigger = await requireMenuItem(treeMenu, "使用其他应用打开", deadline);
  assert.ok(
    (await treeMenu.getByRole("menuitem").count()) <= 8,
    "file tree context menu should stay short even when several native apps are available",
  );
  screenshots.push(
    await captureMenu(page, treeMenu, fileRow, evidenceDirectory, "file-tree-menu-light.png"),
  );
  await openWithTrigger.click({ timeout: timeout(deadline) });
  const openWithMenu = page.getByRole("menu", { name: "使用其他应用打开", exact: true });
  await openWithMenu.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.ok(
    (await openWithMenu.getByRole("menuitem").count()) > 0,
    "discovered native apps should remain reachable in the open-with submenu",
  );
  screenshots.push(
    await captureMenu(
      page,
      openWithMenu,
      openWithTrigger,
      evidenceDirectory,
      "file-tree-open-with-menu-light.png",
    ),
  );
  await page.keyboard.press("Escape", { timeout: timeout(deadline) });
  await openWithMenu.waitFor({ state: "detached", timeout: timeout(deadline) });
  const treeItem = fileRow.locator("xpath=ancestor::*[@role='treeitem'][1]");
  if (await treeMenu.isVisible().catch(() => false))
    await closeMenuAndRestoreFocus(page, treeItem, treeMenu, deadline);
  // FileTree 的菜单键作用于已选择节点；鼠标右键特意不切换选择，故先走真实行点击建立键盘上下文。
  await page.evaluate((relativePath) => {
    globalThis.__JA_CONTEXT_MENU_EXPECTED_RELATIVE_PATH__ = relativePath;
  }, "src/conflict.ts");
  const treeReadStart = await latestWorkspaceReadCallId(page);
  await fileRow.click({ timeout: timeout(deadline) });
  const treeReadSamples = await readWorkspaceReadSamples(page, treeReadStart, deadline);
  const treeFileTab = files.locator('[data-file-tab-path="src/conflict.ts"]');
  const treeFileOpen = await treeFileTab
    .waitFor({ state: "visible", timeout: Math.min(timeout(deadline), 3_000) })
    .then(() => true)
    .catch(() => false);
  let fileTabMenu = false;
  let openedTabPath = null;
  if (treeFileOpen) {
    await verifyFileTabMenu(
      page,
      files,
      "src/conflict.ts",
      evidenceDirectory,
      deadline,
      screenshots,
    );
    fileTabMenu = true;
    openedTabPath = "src/conflict.ts";
  }
  await openContextMenu(page, treeItem, "conflict.ts 文件操作", deadline, "ContextMenu");
  const treeKeyboardMenu = page.getByRole("menu", { name: "conflict.ts 文件操作", exact: true });
  await treeKeyboardMenu.waitFor({ state: "visible", timeout: timeout(deadline) });
  await closeMenuAndRestoreFocus(page, treeItem, treeKeyboardMenu, deadline);

  const search = files.getByRole("searchbox", { name: "搜索工作区", exact: true });
  await search.fill("conflict");
  const matchingResults = files
    .getByRole("list", { name: "搜索结果", exact: true })
    .getByRole("listitem")
    .filter({ hasText: "src/conflict.ts" });
  const result = matchingResults.first().getByRole("button");
  await result.waitFor({ state: "visible", timeout: timeout(deadline) });
  const searchResultMatchCount = await matchingResults.count();
  assert.ok(
    searchResultMatchCount > 0,
    "the isolated fixture must produce a conflict.ts search hit",
  );
  const searchResultPath = await result.locator(".ja-search-result-path").textContent();
  assert.equal(
    searchResultPath,
    "src/conflict.ts",
    "the selected hit must identify the fixture file",
  );
  await result.evaluate((element) => element.scrollIntoView({ block: "end", inline: "end" }));
  const searchMenu = await openContextMenu(page, result, "src/conflict.ts 文件操作", deadline);
  await requireMenuItem(searchMenu, "打开", deadline);
  await requireMenuItem(searchMenu, "添加到对话", deadline);
  const searchShot = await captureMenu(
    page,
    searchMenu,
    result,
    evidenceDirectory,
    "file-search-menu-light.png",
  );
  assert.ok(searchShot.targetNearEdge, "the search result trigger must approach a viewport edge");
  screenshots.push(searchShot);
  await closeMenuAndRestoreFocus(page, result, searchMenu, deadline);
  await result.press("Shift+F10", { timeout: timeout(deadline) });
  const keyboardMenu = page.getByRole("menu", { name: "src/conflict.ts 文件操作", exact: true });
  await keyboardMenu.waitFor({ state: "visible", timeout: timeout(deadline) });
  await closeMenuAndRestoreFocus(page, result, keyboardMenu, deadline);

  await page.evaluate((relativePath) => {
    globalThis.__JA_CONTEXT_MENU_EXPECTED_RELATIVE_PATH__ = relativePath;
  }, searchResultPath);
  const conflictReadStart = await latestWorkspaceReadCallId(page);
  const openFileMenu = await openContextMenu(page, result, "src/conflict.ts 文件操作", deadline);
  const readFailureNotice = page.getByText("文件读取失败，请重试。", { exact: true });
  const fileOpenNoticeBeforeAction = await readFailureNotice.isVisible().catch(() => false);
  await (
    await requireMenuItem(openFileMenu, "打开", deadline)
  ).click({
    timeout: timeout(deadline),
  });
  const conflictFileTab = files.locator('[data-file-tab-path="src/conflict.ts"]');
  const conflictSearchOpen = await conflictFileTab
    .waitFor({ state: "visible", timeout: Math.min(timeout(deadline), 10_000) })
    .then(() => true)
    .catch(() => false);
  const conflictSearchReadSamples = await readWorkspaceReadSamples(
    page,
    conflictReadStart,
    deadline,
  );
  const fileOpenNoticeAfterAction = await readFailureNotice.isVisible().catch(() => false);
  if (conflictSearchOpen) {
    await verifyFileTabMenu(
      page,
      files,
      "src/conflict.ts",
      evidenceDirectory,
      deadline,
      screenshots,
      !fileTabMenu,
    );
    if (!fileTabMenu) {
      fileTabMenu = true;
      openedTabPath = "src/conflict.ts";
    }
  }

  await search.fill("no head");
  const plainMatches = files
    .getByRole("list", { name: "搜索结果", exact: true })
    .getByRole("listitem")
    .filter({ hasText: "no-head-untracked.txt" });
  const plainResult = plainMatches.first().getByRole("button");
  await plainResult
    .waitFor({ state: "visible", timeout: Math.min(timeout(deadline), 5_000) })
    .catch(() => undefined);
  const plainResultMatchCount = await plainMatches.count();
  const plainResultPath =
    plainResultMatchCount > 0
      ? await plainResult.locator(".ja-search-result-path").textContent()
      : "no-head-untracked.txt";
  let plainTextSearchOpen = false;
  let plainTextReadSamples = [];
  if (plainResultMatchCount > 0) {
    assert.equal(plainResultPath, "no-head-untracked.txt");
    await page.evaluate((relativePath) => {
      globalThis.__JA_CONTEXT_MENU_EXPECTED_RELATIVE_PATH__ = relativePath;
    }, plainResultPath);
    const plainReadStart = await latestWorkspaceReadCallId(page);
    const plainOpenMenu = await openContextMenu(
      page,
      plainResult,
      "no-head-untracked.txt 文件操作",
      deadline,
    );
    await (
      await requireMenuItem(plainOpenMenu, "打开", deadline)
    ).click({
      timeout: timeout(deadline),
    });
    const plainFileTab = files.locator('[data-file-tab-path="no-head-untracked.txt"]');
    plainTextSearchOpen = await plainFileTab
      .waitFor({ state: "visible", timeout: Math.min(timeout(deadline), 10_000) })
      .then(() => true)
      .catch(() => false);
    plainTextReadSamples = await readWorkspaceReadSamples(page, plainReadStart, deadline);
  }
  if (plainTextSearchOpen) {
    await verifyFileTabMenu(
      page,
      files,
      "no-head-untracked.txt",
      evidenceDirectory,
      deadline,
      screenshots,
      !fileTabMenu,
    );
    if (!fileTabMenu) {
      fileTabMenu = true;
      openedTabPath = "no-head-untracked.txt";
    }
  }
  const fileTabOpen = openedTabPath !== null;
  if (!fileTabOpen) {
    const screenshot = join(evidenceDirectory, "file-open-failure-light.png");
    await page.screenshot({ path: screenshot, animations: "disabled" });
    screenshots.push({ screenshot, background: "", targetNearEdge: false });
  }

  await search.fill("conflict");
  await result.waitFor({ state: "visible", timeout: timeout(deadline) });
  const addReferenceMenu = await openContextMenu(
    page,
    result,
    "src/conflict.ts 文件操作",
    deadline,
  );
  await (
    await requireMenuItem(addReferenceMenu, "添加到对话", deadline)
  ).click({
    timeout: timeout(deadline),
  });
  const reference = page
    .locator('.ja-composer-context__chip[data-reference-type="workspace"]')
    .filter({ hasText: "conflict.ts" });
  await reference.waitFor({ state: "visible", timeout: timeout(deadline) });
  const referenceMenu = await openContextMenu(page, reference, "引用操作", deadline);
  await requireMenuItem(referenceMenu, "打开文件", deadline);
  await requireMenuItem(referenceMenu, "移除引用", deadline);
  screenshots.push(
    await captureMenu(
      page,
      referenceMenu,
      reference,
      evidenceDirectory,
      "composer-reference-menu-light.png",
    ),
  );
  // The chip handler records the nested open button as the pointer target, so Escape returns focus there rather than to its list item.
  const referenceOpener = reference.getByRole("button", { name: /^在文件中预览 /u });
  await closeMenuAndRestoreFocus(page, referenceOpener, referenceMenu, deadline);
  const removeReferenceMenu = await openContextMenu(page, reference, "引用操作", deadline);
  await (
    await requireMenuItem(removeReferenceMenu, "移除引用", deadline)
  ).click({
    timeout: timeout(deadline),
  });
  await reference.waitFor({ state: "detached", timeout: timeout(deadline) });
  return {
    treeMenu: true,
    trashCancelReopen: true,
    treeFileOpen,
    treeReadSamples,
    fileTabOpen,
    fileTabMenu: fileTabOpen,
    openedTabPath,
    fileOpenNoticeBeforeAction,
    fileOpenNoticeAfterAction,
    searchResultMenu: true,
    searchResultMatchCount,
    searchResultPath,
    conflictSearchOpen,
    conflictSearchReadSamples,
    plainResultMatchCount,
    plainResultPath,
    plainResultFound: plainResultMatchCount > 0,
    plainTextSearchOpen,
    plainTextReadSamples,
    searchKeyboardEntry: true,
    composerReferenceMenu: true,
  };
}

/** 浏览器页面标签菜单复制原生 Preview 地址，关闭仍走 Preview resource cleanup。 */
async function verifyPreviewPageMenu(page, workbench, evidenceDirectory, deadline, screenshots) {
  await openCapability(page, workbench, "preview", "浏览器", deadline);
  const address = workbench.getByRole("textbox", { name: "浏览器地址", exact: true });
  const target = new URL("/favicon.png?context-menu=preview", page.url()).href;
  await address.fill(target);
  await workbench.getByRole("button", { name: "访问地址", exact: true }).click({
    timeout: timeout(deadline),
  });
  const pageTab = workbench.locator('.ja-preview-tabs [role="tab"]').last();
  await pageTab.waitFor({ state: "visible", timeout: timeout(deadline) });
  const tabName = await pageTab.getAttribute("aria-label");
  assert.ok(tabName, "Preview page tab must expose an accessible name");
  const label = `浏览器标签 ${tabName}`;
  let menu = await openContextMenu(page, pageTab, label, deadline);
  await requireMenuItem(menu, "复制地址", deadline);
  await requireMenuItem(menu, "关闭", deadline);
  screenshots.push(
    await captureMenu(page, menu, pageTab, evidenceDirectory, "preview-page-menu-light.png"),
  );
  await closeMenuAndRestoreFocus(page, pageTab, menu, deadline);
  menu = await openContextMenu(page, pageTab, label, deadline);
  await (await requireMenuItem(menu, "关闭", deadline)).click({ timeout: timeout(deadline) });
  await pageTab.waitFor({ state: "detached", timeout: timeout(deadline) });
  return { pageMenu: true, copyAddressItem: true, closeCleansPageTab: true };
}

/** Review 失效时走现有刷新入口，再核对右键目标和当前 Diff，避免旧快照干扰菜单验收。 */
async function verifyReviewMenu(page, workbench, evidenceDirectory, deadline, screenshots) {
  await openCapability(page, workbench, "review", "审查", deadline);
  const review = page.getByRole("tree", { name: "审查文件", exact: true });
  const refresh = page.getByRole("button", { name: "重新获取", exact: true });
  if (await refresh.isVisible().catch(() => false))
    await refresh.click({ timeout: timeout(deadline) });
  await review.waitFor({ state: "visible", timeout: timeout(deadline) });
  const rows = review.locator('[data-review-file-id][data-review-layer="unstaged"]');
  await rows.first().waitFor({ state: "visible", timeout: timeout(deadline) });
  const selectedIndex = await rows.evaluateAll((items) =>
    items.findIndex((row) => row.getAttribute("aria-selected") === "true"),
  );
  assert.ok(selectedIndex >= 0, "Review must expose the currently viewed Diff row");
  const selectedRow = rows.nth(selectedIndex);
  const selectedBefore = await selectedRow.getAttribute("data-review-file-id");
  const target = rows.nth(selectedIndex === 0 ? 1 : 0);
  await target.waitFor({ state: "visible", timeout: timeout(deadline) });
  const targetName = (await target.getAttribute("aria-label")) ?? "";
  const menuLabel = `${targetName.replace(/^查看 /u, "").replace(/ 的未暂存变更$/u, "")} 文件操作`;
  const menu = await openContextMenu(page, target, menuLabel, deadline);
  await requireMenuItem(menu, "暂存文件", deadline);
  await requireMenuItem(menu, "撤销文件", deadline);
  assert.equal(await selectedRow.getAttribute("aria-selected"), "true");
  assert.equal(await selectedRow.getAttribute("data-review-file-id"), selectedBefore);
  screenshots.push(
    await captureMenu(page, menu, target, evidenceDirectory, "review-file-menu-light.png"),
  );
  await (await requireMenuItem(menu, "撤销文件", deadline)).click({ timeout: timeout(deadline) });
  const confirmation = page.getByRole("alertdialog", { name: "撤销文件变更？", exact: true });
  await confirmation.waitFor({ state: "visible", timeout: timeout(deadline) });
  await confirmation.getByRole("button", { name: "取消", exact: true }).click({
    timeout: timeout(deadline),
  });
  await confirmation.waitFor({ state: "detached", timeout: timeout(deadline) });
  assert.equal(
    await selectedRow.getAttribute("data-review-file-id"),
    selectedBefore,
    "opening and cancelling a file revert must preserve the selected Diff",
  );
  return { rowMenu: true, diffTargetPreserved: true, revertUsesConfirmation: true };
}

/** 打开真实 PowerShell PTY，检查标签、窗格和有无选区正文菜单；剪贴板写入由端口测试验证。 */
async function verifyTerminalMenus(page, workbench, evidenceDirectory, deadline, screenshots) {
  await openCapability(page, workbench, "terminal", "终端", deadline);
  const terminal = page.getByRole("region", { name: "终端工作区", exact: true });
  await terminal.waitFor({ state: "visible", timeout: timeout(deadline) });
  const tab = terminal.getByRole("tab", { name: /PowerShell/u }).first();
  if (!(await tab.isVisible().catch(() => false))) {
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
  }
  const activeTab = terminal.getByRole("tab", { name: /PowerShell/u }).first();
  await activeTab.waitFor({ state: "visible", timeout: timeout(deadline) });
  const tabMenu = await openContextMenu(page, activeTab, "终端标签页操作", deadline);
  await requireMenuItem(tabMenu, "关闭终端标签页", deadline);
  screenshots.push(
    await captureMenu(page, tabMenu, activeTab, evidenceDirectory, "terminal-tab-menu-light.png"),
  );
  await closeMenuAndRestoreFocus(page, activeTab, tabMenu, deadline);

  const pane = terminal.locator(".ja-terminal-pane:visible").first();
  await pane
    .locator(".ja-terminal-pane-state")
    .getByText("运行中", { exact: true })
    .waitFor({ state: "attached", timeout: timeout(deadline) });
  await page.waitForFunction(
    () => {
      const activePane = [...globalThis.document.querySelectorAll(".ja-terminal-pane")].find(
        (candidate) => candidate.getClientRects().length > 0,
      );
      return (
        (activePane?.getAttribute("data-terminal-session-id")?.length ?? 0) > 0 &&
        (activePane?.getAttribute("data-terminal-session-generation")?.length ?? 0) > 0
      );
    },
    undefined,
    { timeout: timeout(deadline) },
  );
  assert.ok(
    (await pane.getAttribute("data-terminal-session-id"))?.length,
    "running terminal pane must have a native session identity",
  );
  const toolbarAction = pane.getByRole("button", { name: "横向分屏", exact: true });
  const paneMenu = await openContextMenu(page, toolbarAction, "终端窗格操作", deadline);
  for (const label of ["横向分屏", "纵向分屏", "关闭终端窗格"])
    await requireMenuItem(paneMenu, label, deadline);
  const paneScreenshot = await captureMenu(
    page,
    paneMenu,
    toolbarAction,
    evidenceDirectory,
    "terminal-pane-menu-dark.png",
  );
  screenshots.push(paneScreenshot);
  await closeMenuAndRestoreFocus(page, toolbarAction, paneMenu, deadline);
  const body = pane.locator(".ja-terminal-pane-body");
  const bodyMenu = await openContextMenu(page, body, "终端操作", deadline);
  await requireMenuItem(bodyMenu, "粘贴到终端", deadline);
  const copyAvailable =
    (await bodyMenu.getByRole("menuitem", { name: "复制选中内容", exact: true }).count()) > 0;
  screenshots.push(
    await captureMenu(page, bodyMenu, body, evidenceDirectory, "terminal-body-menu-dark.png"),
  );
  await page.keyboard.press("Escape", { timeout: timeout(deadline) });
  await bodyMenu.waitFor({ state: "detached", timeout: timeout(deadline) });
  await page.waitForFunction(
    () => globalThis.document.activeElement?.classList.contains("xterm-helper-textarea") === true,
    undefined,
    { timeout: timeout(deadline) },
  );
  await page.keyboard.type("Write-Output JA_CONTEXT_SELECTION_OK", {
    delay: 4,
  });
  await page.keyboard.press("Enter", { timeout: timeout(deadline) });
  const outputRow = pane
    .locator(".xterm-rows > div")
    .filter({ hasText: "JA_CONTEXT_SELECTION_OK" })
    .last();
  await outputRow.waitFor({ state: "visible", timeout: timeout(deadline) });
  const outputBounds = await outputRow.boundingBox();
  if (outputBounds === null) throw new Error("terminal output row has no screen geometry");
  const selectionY = outputBounds.y + outputBounds.height / 2;
  await page.mouse.move(outputBounds.x + 8, selectionY);
  await page.mouse.down();
  await page.mouse.move(outputBounds.x + Math.min(130, outputBounds.width - 8), selectionY, {
    steps: 8,
  });
  await page.mouse.up();
  const selectedMenu = await openContextMenu(page, body, "终端操作", deadline, "mouse", {
    x: 8,
    y: 8,
  });
  await requireMenuItem(selectedMenu, "复制选中内容", deadline);
  screenshots.push(
    await captureMenu(
      page,
      selectedMenu,
      body,
      evidenceDirectory,
      "terminal-selection-menu-dark.png",
    ),
  );
  await page.keyboard.press("Escape", { timeout: timeout(deadline) });
  await selectedMenu.waitFor({ state: "detached", timeout: timeout(deadline) });
  return {
    tabMenu: true,
    paneMenu: true,
    bodyMenu: true,
    copyAbsentWithoutSelection: !copyAvailable,
    copyAvailableWithSelection: true,
    clipboardFixtureRoundTrip: false,
    clipboardExecution: "not_executed",
  };
}

/** 在 Provider 首段仍等待时创建并删除真实排队消息，验证右键动作不会误改当前 Turn。 */
async function verifyQueuedMessageMenu(page, composer, evidenceDirectory, deadline, screenshots) {
  const queueText = "隔离右键排队验证";
  await page.getByRole("button", { name: "停止生成", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await composer.fill(queueText);
  await page.getByRole("button", { name: "排队发送", exact: true }).click({
    timeout: timeout(deadline),
  });
  const row = page.locator(".ja-composer-queue__item").filter({ hasText: queueText });
  await row.waitFor({ state: "visible", timeout: timeout(deadline) });
  await page.waitForFunction(
    (text) =>
      [...globalThis.document.querySelectorAll(".ja-composer-queue__item")].some(
        (item) => item.textContent?.includes(text) && item.getAttribute("data-state") === "ready",
      ),
    queueText,
    { timeout: timeout(deadline) },
  );
  const menuLabel = `排队消息操作：第 1 条消息：${queueText}`;
  const menu = await openContextMenu(page, row, menuLabel, deadline, "mouse", { x: 8, y: 8 });
  for (const label of ["调整方向", "编辑消息", "删除消息"])
    await requireMenuItem(menu, label, deadline);
  screenshots.push(
    await captureMenu(page, menu, row, evidenceDirectory, "queued-message-menu-dark.png"),
  );
  await (await requireMenuItem(menu, "编辑消息", deadline)).click({ timeout: timeout(deadline) });
  const editor = row.getByRole("textbox", { name: /编辑第 1 条消息/u });
  await editor.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await editor.inputValue(), queueText);
  await editor.press("Escape", { timeout: timeout(deadline) });
  await editor.waitFor({ state: "detached", timeout: timeout(deadline) });
  const deleteMenu = await openContextMenu(page, row, menuLabel, deadline, "mouse", {
    x: 8,
    y: 8,
  });
  await (
    await requireMenuItem(deleteMenu, "删除消息", deadline)
  ).click({
    timeout: timeout(deadline),
  });
  await row.waitFor({ state: "detached", timeout: timeout(deadline) });
  return { menu: true, editCancelPreservesText: true, deleteRemovesTarget: true };
}

/** 用受控 Provider fixture 生成真实消息，并在流式阶段验证排队右键。 */
async function createMessageFixture(
  page,
  workspaceRoot,
  navigation,
  fixture,
  evidenceDirectory,
  deadline,
  screenshots,
) {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline).catch((error) => {
    throw new Error(`message_application_ready: ${String(error?.message ?? error)}`);
  });
  await ensureSidebarVisible(page, deadline).catch((error) => {
    throw new Error(`message_sidebar_ready: ${String(error?.message ?? error)}`);
  });
  await selectProject(page, navigation.workspaceName, deadline).catch((error) => {
    throw new Error(`message_project_selected: ${String(error?.message ?? error)}`);
  });
  await selectThread(page, navigation.primaryThreadId, deadline).catch((error) => {
    throw new Error(`message_thread_selected: ${String(error?.message ?? error)}`);
  });
  await page.setViewportSize({ width: 800, height: 640 });
  await hideSidebar(page, deadline);

  const fileUrl = pathToFileURL(join(workspaceRoot, "src", "domain", "entity", "modified.ts")).href;
  const prompt = `${MESSAGE_PROMPT}\n\n[隔离文件链接](${fileUrl})`;
  const composer = page.getByRole("textbox", { name: "消息", exact: true });
  await composer.fill(prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: timeout(deadline),
  });
  const userMessage = page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: MESSAGE_PROMPT })
    .last();
  await userMessage.waitFor({ state: "visible", timeout: timeout(deadline) });
  const queuedMessage = await verifyQueuedMessageMenu(
    page,
    composer,
    evidenceDirectory,
    deadline,
    screenshots,
  );
  fixture.releaseFirstText();
  fixture.releaseSecondNarrative();
  fixture.releaseFinalNarrative();
  fixture.releaseFinalText();
  await page.getByText(conversationProgressFixtureMarkers.final, { exact: false }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await userMessage.locator("[data-file-reference]").waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  return { threadId: navigation.primaryThreadId, prompt, userMessage, composer, queuedMessage };
}

/** 成功消息仅可复制；随后用受控失败回合验证重问，选区和文件链接保留原生语义。 */
async function verifyMessageMenu(page, messageFixture, evidenceDirectory, deadline, screenshots) {
  const { userMessage, composer, queuedMessage } = messageFixture;
  const menu = await openContextMenu(page, userMessage, "消息操作", deadline, "mouse", {
    x: 8,
    y: 8,
  });
  await requireMenuItem(menu, "复制正文", deadline);
  assert.equal(
    await menu.getByRole("menuitem", { name: "编辑问题", exact: true }).count(),
    0,
    "a completed question must not offer Edit question",
  );
  screenshots.push(
    await captureMenu(page, menu, userMessage, evidenceDirectory, "message-menu-dark.png"),
  );

  const fileLink = userMessage.locator("[data-file-reference]").first();
  await fileLink.click({ button: "right", timeout: timeout(deadline) });
  assert.equal(
    await page.getByRole("menu", { name: "消息操作", exact: true }).count(),
    0,
    "right-clicking a file reference must preserve the browser/WebView native context menu",
  );

  await page.evaluate(() => {
    const message = globalThis.document.querySelector('.ja-chat-message-user[data-role="user"]');
    const markdown = message?.querySelector(".ja-markdown");
    if (markdown === null || markdown === undefined)
      throw new Error("message Markdown is unavailable");
    const range = globalThis.document.createRange();
    range.selectNodeContents(markdown);
    const selection = globalThis.window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  });
  await userMessage.click({
    button: "right",
    position: { x: 8, y: 8 },
    timeout: timeout(deadline),
  });
  assert.equal(
    await page.getByRole("menu", { name: "消息操作", exact: true }).count(),
    0,
    "a selected text range must retain the native context menu",
  );
  await page.evaluate(() => globalThis.window.getSelection()?.removeAllRanges());

  const failurePrompt = "请再次检查隔离 fixture。";
  await composer.fill(failurePrompt);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: timeout(deadline),
  });
  const failedQuestion = page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: failurePrompt })
    .last();
  await failedQuestion.waitFor({ state: "visible", timeout: timeout(deadline) });
  await failedQuestion.getByRole("button", { name: "编辑问题", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const editMenu = await openContextMenu(page, failedQuestion, "消息操作", deadline, "mouse", {
    x: 8,
    y: 8,
  });
  await requireMenuItem(editMenu, "复制正文", deadline);
  await (
    await requireMenuItem(editMenu, "编辑问题", deadline)
  ).click({
    timeout: timeout(deadline),
  });
  await page
    .waitForFunction(
      (expected) => globalThis.document.querySelector('[aria-label="消息"]')?.value === expected,
      failurePrompt,
      { timeout: timeout(deadline) },
    )
    .catch((error) => {
      throw new Error(`message_edit_prefill: ${String(error?.message ?? error)}`);
    });
  await composer.fill("");
  return {
    copyItem: true,
    editQuestion: true,
    selectedTextNativeMenu: true,
    fileLinkNativeMenu: true,
    queuedMessage,
  };
}

/** 执行右键矩阵并只返回脱敏布尔证据、截图文件名和未覆盖剪贴板限制。 */
export async function runContextMenusWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  isolatedRuntimeHome,
  providerBaseUrl,
  fixture,
}) {
  assert.ok(page, "page is required");
  assert.ok(workspaceRoot, "workspaceRoot is required");
  assert.ok(isolatedRuntimeHome, "isolatedRuntimeHome is required");
  assert.ok(providerBaseUrl, "isolated loopback Provider endpoint is required");
  assert.ok(fixture, "loopback Provider fixture is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + 10 * 60_000;
  const screenshots = [];
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  await page.context().addInitScript(installContextMenuInvokeProbe);
  let runtimeStartupFailure = null;
  try {
    const startup = await waitForApplication(page, deadline, { retryFailedRuntime: true });
    if (startup.recoveredAfterRetry) {
      runtimeStartupFailure = {
        area: "runtime_startup",
        action: "visible_retry",
        recovered: true,
        detail: startup.initial,
        runtimeStart: startup.runtimeStart,
      };
      await writeStageEvidence(
        evidenceDirectory,
        "runtime-startup",
        { firstAttempt: "failed", afterVisibleRetry: "ready", runtimeStart: startup.runtimeStart },
        null,
        screenshots,
      );
    }
  } catch (error) {
    const detail = String(error?.message ?? error).slice(0, 1_000);
    await writeStageEvidence(
      evidenceDirectory,
      "runtime-startup",
      { firstAttempt: "failed", detail },
      null,
      screenshots,
    );
    throw error;
  }

  const navigation = await createNavigationFixture(page, workspaceRoot, providerBaseUrl);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  await page.evaluate((workspaceId) => {
    globalThis.__JA_CONTEXT_MENU_EXPECTED_WORKSPACE_ID__ = workspaceId;
  }, navigation.workspaceId);
  await selectProject(page, navigation.workspaceName, deadline);
  await selectThread(page, navigation.primaryThreadId, deadline);

  // 设置入口在紧凑布局中会收进导航栏；主题切换先在宽视口完成，再回到窄视口验菜单。
  await page.setViewportSize({ width: 1280, height: 900 });
  await setThemeMode(page, "light", deadline);
  await restoreNavigationContext(page, navigation, deadline);
  await page.setViewportSize({ width: 800, height: 640 });
  await openCompactSidebar(page, deadline);
  const project = await verifyProjectMenu(page, evidenceDirectory, deadline, screenshots);
  const conversation = await verifyConversationMenu(
    page,
    navigation.secondaryThreadId,
    evidenceDirectory,
    deadline,
    screenshots,
  );
  await hideSidebar(page, deadline);

  const workbench = await ensureWorkbenchVisible(page, deadline);
  const workbenchTabs = await verifyWorkbenchTabMenus(
    page,
    workbench,
    evidenceDirectory,
    deadline,
    screenshots,
  );
  const files = await verifyFilesMenus(page, workbench, evidenceDirectory, deadline, screenshots);
  const productFailures = runtimeStartupFailure === null ? [] : [runtimeStartupFailure];
  for (const [area, action, path, opened, samples] of [
    [
      "file_tree_row_open",
      "tree_row_click",
      "src/conflict.ts",
      files.treeFileOpen,
      files.treeReadSamples,
    ],
    [
      "file_search_open",
      "search_result_context_menu_open",
      files.searchResultPath,
      files.conflictSearchOpen,
      files.conflictSearchReadSamples,
    ],
    [
      "file_search_open",
      files.plainResultFound ? "search_result_context_menu_open" : "search_result_missing",
      files.plainResultPath,
      files.plainTextSearchOpen,
      files.plainTextReadSamples,
    ],
  ]) {
    if (opened) continue;
    productFailures.push({
      area,
      action,
      path,
      opened,
      readSamples: samples,
      ...(area === "file_search_open"
        ? {
            readFailureNoticeBeforeAction: files.fileOpenNoticeBeforeAction,
            readFailureNoticeAfterAction: files.fileOpenNoticeAfterAction,
          }
        : {}),
      ...(action === "search_result_missing" ? { searchResultFound: false } : {}),
    });
  }
  await writeStageEvidence(evidenceDirectory, "files", files, null, screenshots);

  const previewStage = await runScenarioStage(page, "preview", evidenceDirectory, screenshots, () =>
    verifyPreviewPageMenu(page, workbench, evidenceDirectory, deadline, screenshots),
  );
  const previewPage = previewStage.result ?? {
    pageMenu: false,
    copyAddressItem: false,
    closeCleansPageTab: false,
  };
  if (previewStage.failure !== null) productFailures.push(previewStage.failure);
  await writeStageEvidence(
    evidenceDirectory,
    "preview",
    previewPage,
    previewStage.failure,
    screenshots,
  );

  const reviewStage = await runScenarioStage(page, "review", evidenceDirectory, screenshots, () =>
    verifyReviewMenu(page, workbench, evidenceDirectory, deadline, screenshots),
  );
  const review = reviewStage.result ?? {
    rowMenu: false,
    diffTargetPreserved: false,
    revertUsesConfirmation: false,
  };
  if (reviewStage.failure !== null) productFailures.push(reviewStage.failure);
  await writeStageEvidence(evidenceDirectory, "review", review, reviewStage.failure, screenshots);

  await page.setViewportSize({ width: 1280, height: 900 });
  await setThemeMode(page, "dark", deadline);
  await restoreNavigationContext(page, navigation, deadline);
  await page.setViewportSize({ width: 800, height: 640 });
  await openCompactSidebar(page, deadline);
  await hideSidebar(page, deadline);
  const terminalStage = await runScenarioStage(
    page,
    "terminal",
    evidenceDirectory,
    screenshots,
    () => verifyTerminalMenus(page, workbench, evidenceDirectory, deadline, screenshots),
  );
  const terminal = terminalStage.result ?? {
    tabMenu: false,
    paneMenu: false,
    bodyMenu: false,
    copyAbsentWithoutSelection: false,
    copyAvailableWithSelection: false,
    clipboardFixtureRoundTrip: false,
    clipboardExecution: "not_executed",
  };
  if (terminalStage.failure !== null) productFailures.push(terminalStage.failure);
  await writeStageEvidence(
    evidenceDirectory,
    "terminal",
    terminal,
    terminalStage.failure,
    screenshots,
  );

  await ensureSidebarVisible(page, deadline);
  const projectRow = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  const darkMenu = await openContextMenu(page, projectRow, "项目操作：右键菜单 E2E", deadline);
  const darkSurface = await captureMenu(
    page,
    darkMenu,
    projectRow,
    evidenceDirectory,
    "project-menu-dark.png",
  );
  screenshots.push(darkSurface);
  await page.keyboard.press("Escape", { timeout: timeout(deadline) });
  await darkMenu.waitFor({ state: "detached", timeout: timeout(deadline) });
  assert.notEqual(
    screenshots.find((shot) => shot.screenshot.endsWith("project-menu-light.png"))?.background,
    darkSurface.background,
    "light and dark menus must render distinct semantic surfaces",
  );

  const messageStage = await runScenarioStage(
    page,
    "messages",
    evidenceDirectory,
    screenshots,
    async () => {
      const messageFixture = await createMessageFixture(
        page,
        workspaceRoot,
        navigation,
        fixture,
        evidenceDirectory,
        deadline,
        screenshots,
      );
      return verifyMessageMenu(page, messageFixture, evidenceDirectory, deadline, screenshots);
    },
  );
  const messages = messageStage.result ?? {
    copyItem: false,
    editQuestion: false,
    selectedTextNativeMenu: false,
    fileLinkNativeMenu: false,
    queuedMessage: { menu: false, editCancelPreservesText: false, deleteRemovesTarget: false },
  };
  if (messageStage.failure !== null) productFailures.push(messageStage.failure);
  await writeStageEvidence(
    evidenceDirectory,
    "messages",
    messages,
    messageStage.failure,
    screenshots,
  );
  const optionalAreasNotCovered = [
    {
      area: "terminal_clipboard_execution",
      reason: "真窗已验证终端粘贴入口；原生剪贴板读取未执行，以免触碰宿主剪贴板。",
    },
    ...(!files.treeFileOpen
      ? [
          {
            area: "file_tree_row_open",
            reason: "树行单击未创建文件标签；已记录对应的 workspace read 阶段和脱敏错误码。",
          },
        ]
      : []),
    ...(!files.conflictSearchOpen || !files.plainTextSearchOpen || !files.plainResultFound
      ? [
          {
            area: "file_search_open",
            reason: "搜索结果缺失或打开动作未创建文件标签；逐次 read 样本见 coverage.files。",
          },
        ]
      : []),
    ...(files.fileTabMenu
      ? []
      : [
          {
            area: "file_tab_menu",
            reason: "没有文件标签可供右键验收。",
          },
        ]),
    {
      area: "composer_attachment",
      reason: "本轮没有待发送附件 fixture；已覆盖排队消息和工作区引用的对象菜单。",
    },
    {
      area: "clipboard_write_execution",
      reason: "为避免修改宿主剪贴板，只检查消息、Preview 和终端菜单的复制入口，不触发写入。",
    },
  ];
  if (pageErrors.length > 0) {
    productFailures.push({ area: "webview", action: "page_error", messages: pageErrors });
  }
  return {
    contractVersion: 1,
    runtime: "tauri_webview2",
    verdict: productFailures.length === 0 ? "PASS" : "FAIL",
    coverage: {
      navigation: { projectMenu: project, conversationMenu: conversation },
      workbenchTabs: {
        tabKinds: workbenchTabs.tabKinds,
        topLevelMenu: {
          rightClickPreservesActive: workbenchTabs.rightClickPreservesActive,
          keyboardEntry: workbenchTabs.keyboardEntry,
          escapeRestoresFocus: workbenchTabs.escapeRestoresFocus,
        },
        previewPageMenu: previewPage,
      },
      files,
      review,
      messages,
      terminal,
      keyboard: { contextMenuKey: true, shiftF10: true, escapeRestoresFocus: true },
      positioning: { narrowViewport: true, edgeCollisionChecked: true },
      themes: ["light", "dark"],
    },
    notCovered: optionalAreasNotCovered,
    productFailures,
    screenshots: screenshots.map((shot) => shot.screenshot.split(/[\\/]/u).at(-1)),
    pageErrors,
  };
}

/** 拒绝只有声明性布尔值的报告；所有指定入口、主题、视口与可选未覆盖范围都必须闭合。 */
export function validateContextMenusWebView2Report(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.runtime, "tauri_webview2");
  assert.equal(report?.coverage?.navigation?.projectMenu?.pointerTargetStable, true);
  assert.equal(report?.coverage?.navigation?.projectMenu?.escapeRestoresFocus, true);
  assert.equal(report?.coverage?.navigation?.conversationMenu?.rightClickTargetStable, true);
  assert.equal(report?.coverage?.navigation?.conversationMenu?.pinAndUnpin, true);
  assert.equal(report?.coverage?.navigation?.conversationMenu?.renamePreservesIdentity, true);
  assert.deepEqual(report?.coverage?.workbenchTabs?.tabKinds, ["files", "preview", "terminal"]);
  assert.deepEqual(report?.coverage?.workbenchTabs?.topLevelMenu, {
    rightClickPreservesActive: true,
    keyboardEntry: true,
    escapeRestoresFocus: true,
  });
  const previewPageMenu = report?.coverage?.workbenchTabs?.previewPageMenu;
  for (const field of ["pageMenu", "copyAddressItem", "closeCleansPageTab"])
    assert.equal(typeof previewPageMenu?.[field], "boolean");
  const files = report?.coverage?.files;
  assert.equal(files?.treeMenu, true);
  assert.equal(files?.trashCancelReopen, true);
  for (const field of ["treeFileOpen", "conflictSearchOpen", "plainTextSearchOpen"])
    assert.equal(typeof files?.[field], "boolean");
  assert.equal(files?.treeReadSamples?.every(isSanitizedWorkspaceReadSample), true);
  assert.equal(files?.conflictSearchReadSamples?.every(isSanitizedWorkspaceReadSample), true);
  assert.equal(files?.plainTextReadSamples?.every(isSanitizedWorkspaceReadSample), true);
  assert.equal(files?.plainResultPath, "no-head-untracked.txt");
  assert.equal(typeof files?.plainResultFound, "boolean");
  assert.ok(Number.isInteger(files?.plainResultMatchCount) && files.plainResultMatchCount >= 0);
  assert.equal(files?.plainResultFound, files?.plainResultMatchCount > 0);
  assert.equal(typeof files?.fileTabOpen, "boolean");
  assert.equal(files?.fileTabMenu, files?.fileTabOpen);
  assert.equal(
    files?.fileTabOpen,
    files?.treeFileOpen || files?.conflictSearchOpen || files?.plainTextSearchOpen,
  );
  assert.equal(typeof files?.fileOpenNoticeBeforeAction, "boolean");
  assert.equal(typeof files?.fileOpenNoticeAfterAction, "boolean");
  assert.equal(files?.searchResultMenu, true);
  assert.ok(Number.isInteger(files?.searchResultMatchCount) && files.searchResultMatchCount > 0);
  assert.equal(files?.searchResultPath, "src/conflict.ts");
  assert.equal(files?.searchKeyboardEntry, true);
  assert.equal(files?.composerReferenceMenu, true);
  const expectedFileFailures = [
    [
      "file_tree_row_open",
      "tree_row_click",
      "src/conflict.ts",
      files.treeFileOpen,
      files.treeReadSamples,
    ],
    [
      "file_search_open",
      "search_result_context_menu_open",
      files.searchResultPath,
      files.conflictSearchOpen,
      files.conflictSearchReadSamples,
    ],
    [
      "file_search_open",
      files.plainResultFound ? "search_result_context_menu_open" : "search_result_missing",
      files.plainResultPath,
      files.plainTextSearchOpen,
      files.plainTextReadSamples,
    ],
  ]
    .filter(([, , , opened]) => !opened)
    .map(([area, action, path, opened, readSamples]) => ({
      area,
      action,
      path,
      opened,
      readSamples,
      ...(area === "file_search_open"
        ? {
            readFailureNoticeBeforeAction: files.fileOpenNoticeBeforeAction,
            readFailureNoticeAfterAction: files.fileOpenNoticeAfterAction,
          }
        : {}),
      ...(action === "search_result_missing" ? { searchResultFound: false } : {}),
    }));
  assert.deepEqual(
    report?.productFailures?.filter((failure) =>
      ["file_tree_row_open", "file_search_open"].includes(failure.area),
    ),
    expectedFileFailures,
  );
  assert.ok(Array.isArray(report?.productFailures));
  assert.ok(report.productFailures.every((failure) => typeof failure?.area === "string"));
  for (const [coverage, fields] of [
    [previewPageMenu, ["pageMenu", "copyAddressItem", "closeCleansPageTab"]],
    [report?.coverage?.review, ["rowMenu", "diffTargetPreserved", "revertUsesConfirmation"]],
    [
      report?.coverage?.messages,
      ["copyItem", "editQuestion", "selectedTextNativeMenu", "fileLinkNativeMenu"],
    ],
    [
      report?.coverage?.terminal,
      [
        "tabMenu",
        "paneMenu",
        "bodyMenu",
        "copyAbsentWithoutSelection",
        "copyAvailableWithSelection",
        "clipboardFixtureRoundTrip",
      ],
    ],
  ]) {
    for (const field of fields) assert.equal(typeof coverage?.[field], "boolean");
  }
  for (const field of ["menu", "editCancelPreservesText", "deleteRemovesTarget"])
    assert.equal(typeof report?.coverage?.messages?.queuedMessage?.[field], "boolean");
  assert.equal(report?.coverage?.terminal?.clipboardFixtureRoundTrip, false);
  assert.equal(report?.coverage?.terminal?.clipboardExecution, "not_executed");
  assert.deepEqual(report?.coverage?.keyboard, {
    contextMenuKey: true,
    shiftF10: true,
    escapeRestoresFocus: true,
  });
  assert.deepEqual(report?.coverage?.positioning, {
    narrowViewport: true,
    edgeCollisionChecked: true,
  });
  assert.deepEqual(report?.coverage?.themes, ["light", "dark"]);
  assert.deepEqual(report?.notCovered, [
    {
      area: "terminal_clipboard_execution",
      reason: "真窗已验证终端粘贴入口；原生剪贴板读取未执行，以免触碰宿主剪贴板。",
    },
    ...(!files.treeFileOpen
      ? [
          {
            area: "file_tree_row_open",
            reason: "树行单击未创建文件标签；已记录对应的 workspace read 阶段和脱敏错误码。",
          },
        ]
      : []),
    ...(!files.conflictSearchOpen || !files.plainTextSearchOpen || !files.plainResultFound
      ? [
          {
            area: "file_search_open",
            reason: "搜索结果缺失或打开动作未创建文件标签；逐次 read 样本见 coverage.files。",
          },
        ]
      : []),
    ...(files.fileTabMenu
      ? []
      : [
          {
            area: "file_tab_menu",
            reason: "没有文件标签可供右键验收。",
          },
        ]),
    {
      area: "composer_attachment",
      reason: "本轮没有待发送附件 fixture；已覆盖排队消息和工作区引用的对象菜单。",
    },
    {
      area: "clipboard_write_execution",
      reason: "为避免修改宿主剪贴板，只检查消息、Preview 和终端菜单的复制入口，不触发写入。",
    },
  ]);
  assert.ok(report?.screenshots?.length >= 6);
  assert.ok(Array.isArray(report?.pageErrors));
  const allMenuAssertionsPassed = [
    ...Object.values(previewPageMenu),
    ...Object.values(report?.coverage?.review ?? {}),
    ...Object.entries(report?.coverage?.messages ?? {})
      .filter(([key]) => key !== "queuedMessage")
      .map(([, value]) => value),
    ...Object.values(report?.coverage?.messages?.queuedMessage ?? {}),
    ...Object.entries(report?.coverage?.terminal ?? {})
      .filter(([key]) => key !== "clipboardExecution" && key !== "clipboardFixtureRoundTrip")
      .map(([, value]) => value),
  ].every((value) => value === true);
  const expectedVerdict =
    report.productFailures.length === 0 && report.pageErrors.length === 0 && allMenuAssertionsPassed
      ? "PASS"
      : "FAIL";
  assert.equal(report?.verdict, expectedVerdict);
  return report;
}

/** 限定报告中的 workspace read 采样字段，避免误写原始参数或文件正文。 */
function isSanitizedWorkspaceReadSample(sample) {
  if (sample === null || typeof sample !== "object") return false;
  const allowedKeys = new Set([
    "callId",
    "command",
    "phase",
    "workspaceMatchesFixture",
    "pathMatchesExpected",
    "errorCode",
  ]);
  return (
    Object.keys(sample).every((key) => allowedKeys.has(key)) &&
    Number.isInteger(sample.callId) &&
    sample.command === "ja_workspace_read_file" &&
    ["start", "resolved", "rejected"].includes(sample.phase) &&
    typeof sample.workspaceMatchesFixture === "boolean" &&
    typeof sample.pathMatchesExpected === "boolean" &&
    (sample.errorCode === undefined ||
      [
        "NOT_CONFIGURED",
        "UNKNOWN_WORKSPACE",
        "INVALID_INPUT",
        "INVALID_PATH",
        "PATH_REJECTED",
        "NOT_FOUND",
        "NOT_DIRECTORY",
        "NOT_FILE",
        "STALE_CURSOR",
        "LIMIT_EXCEEDED",
        "CHANGED_DURING_READ",
        "ALREADY_EXISTS",
        "REVISION_CONFLICT",
        "WORKSPACE_RECOVERY_REQUIRED",
        "MUTATION_ALREADY_USED",
        "INVALID_MUTATION_ID",
        "UNSUPPORTED_CONTENT",
        "TRASH_TOKEN_INVALID",
        "TRASH_TOKEN_EXPIRED",
        "RECYCLE_UNAVAILABLE",
        "DROP_TOKEN_INVALID",
        "WATCH_UNAVAILABLE",
        "IO",
        "RUNTIME_UNAVAILABLE",
        "UNCLASSIFIED",
      ].includes(sample.errorCode))
  );
}

/** CLI 只接受显式产物与证据目录，避免误连已安装应用或默认用户 profile。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-context-menus"),
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

/** 启动 loopback Provider 和隔离 production runner；产品行为失败先落报告再用退出码表示验收失败。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const fixture = await startConversationProgressFixture();
  try {
    const report = await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      wallTimeoutMs: TURN_TIMEOUT_MS,
      scope: "git",
      fixture: "full",
      hiddenWindow: true,
      prewarmWebview: true,
      preserveFailedProfile: true,
      ignoredFiles: 0,
      untrackedFiles: 0,
      driver: (driverOptions) =>
        runContextMenusWebView2({ ...driverOptions, providerBaseUrl: fixture.baseUrl, fixture }),
      validateReport: validateContextMenusWebView2Report,
      reportFileName: "context-menus-webview2-report.json",
    });
    console.log(
      "JA_CONTEXT_MENUS_WEBVIEW2_" +
        report.verdict +
        " " +
        JSON.stringify({ verdict: report.verdict }),
    );
    if (report.verdict !== "PASS") process.exitCode = 1;
  } finally {
    await fixture.close();
  }
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(
      `JA_CONTEXT_MENUS_WEBVIEW2_FAIL ${String(error?.message ?? error).slice(0, 2_000)}`,
    );
    process.exitCode = 1;
  });
}
