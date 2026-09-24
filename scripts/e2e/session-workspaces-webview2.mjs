// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* global document, HTMLTextAreaElement */

/**
 * Windows Tauri/WebView2 真窗验收每个无项目主会话独立工作目录。
 * 生产启动器为本轮随机创建 Ja home、Runtime、WebView UDF 与 Git 项目，任何文件写入只落在这些隔离目录。
 */

import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const STEP_TIMEOUT_MS = 30_000;
const SESSION_CWD_BEGIN = "JA_SESSION_CWD_BEGIN";
const SESSION_CWD_END = "JA_SESSION_CWD_END";

/** 将 selector 与 UI 转场限制在单步期限内，失败能定位到具体会话动作。 */
function timeout(deadline) {
  return Math.max(1, Math.min(STEP_TIMEOUT_MS, deadline - Date.now()));
}

/** 捕获工作区 id 路由与 native Terminal 创建，禁止把路径或 shell 输入写入 trace。 */
export function installSessionWorkspaceProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  globalThis.__JA_SESSION_WORKSPACE_TRACE__ = [];
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = request?.command;
    const observed = new Set([
      "ja_thread_create",
      "ja_runtime_workspace_activate",
      "ja_runtime_workspace_open",
      "ja_workspace_open",
      "ja_runtime_task_create",
      "ja_terminal_open",
      "ja_terminal_close",
    ]);
    if (!observed.has(command))
      return previous === undefined ? delegate() : previous(request, delegate);
    const input = request?.args?.input;
    const item = {
      command,
      workspaceId: typeof input?.workspaceId === "string" ? input.workspaceId : undefined,
      phase: "start",
    };
    globalThis.__JA_SESSION_WORKSPACE_TRACE__.push(item);
    try {
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      if (command === "ja_runtime_workspace_open" && typeof result?.workspaceId === "string") {
        item.resultWorkspaceId = result.workspaceId;
      }
      item.phase = "resolved";
      return result;
    } catch (error) {
      item.phase = "rejected";
      throw error;
    }
  };
}

/** 等待 Tauri 主页面与真实 Runtime ready；无项目空白态允许没有项目按钮或 active workspace。 */
async function prepareApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("list", { name: "最近对话列表", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
}

/** 新建接口不带 cwd 才是 session 意图；Provider/Model 只保存创建偏好，不启动 Turn。 */
async function createSessionThread(page, title) {
  return page.evaluate(async (threadTitle) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    return createHistoryAdapter().threadCreate({
      title: threadTitle,
      providerId: "provider_e2e",
      modelId: "model_e2e",
      reasoningLevel: null,
      accessMode: "approval_required",
      collaborationMode: "default",
    });
  }, title);
}

/** 通过 typed Native adapter 激活服务端登记的目录，只把根路径交给 runner 检查隔离。 */
async function activateSessionWorkspace(page, workspaceId) {
  return page.evaluate(async (id) => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    return createRuntimeHostAdapter().activateWorkspace(id);
  }, workspaceId);
}

/** 规范化扩展长度 Windows 路径，便于 runner 校验 Rust 返回的 canonical root。 */
function comparablePath(value) {
  return resolve(value.replace(/^\\\\\?\\/u, ""));
}

/** 只解码成对标记间的 PowerShell cwd，避免窄 WebView 终端换行破坏路径比较。 */
export function terminalTextContainsWorkspaceRoot(
  text,
  expectedRoot,
  beginMarker = SESSION_CWD_BEGIN,
  endMarker = SESSION_CWD_END,
) {
  const candidate = terminalCwdValue(text, beginMarker, endMarker);
  if (candidate === undefined) return false;
  return comparablePath(candidate).toLowerCase() === comparablePath(expectedRoot).toLowerCase();
}

/** xterm DOM 每个视觉行单独成节点；去掉折行后只取最后一组标记，避开命令回显。 */
function terminalCwdValue(text, beginMarker, endMarker) {
  const flattened = text.replace(/\r?\n/gu, "");
  const beginIndex = flattened.lastIndexOf(beginMarker);
  if (beginIndex < 0) return undefined;
  const bodyStart = beginIndex + beginMarker.length;
  const endIndex = flattened.indexOf(endMarker, bodyStart);
  if (endIndex < 0) return undefined;
  const encoded = flattened.slice(bodyStart, endIndex).trim();
  if (!/^[A-Za-z0-9+/]+={0,2}$/u.test(encoded)) return undefined;
  const candidate = Buffer.from(encoded, "base64").toString("utf8");
  return /^(?:\\\\\?\\)?[a-z]:\\.+$/iu.test(candidate) ? candidate : undefined;
}

/** 从终端可见文本提取 drive 路径用于归属检查；调用方只能输出 hash 和布尔属性。 */
function extractTerminalWindowsPaths(text) {
  return [...text.matchAll(/(?:\\\\\?\\)?[a-z]:\\[^\r\n<>]+/giu)].map(([candidate]) =>
    candidate.trim(),
  );
}

/** 计算路径是否落在隔离根下，调用方不必把真实路径写入证据。 */
function pathOwnership(candidate, isolatedRuntimeHome, projectRoot, expectedRoot) {
  const normalizedPath = comparablePath(candidate).toLowerCase();
  const isWithin = (root) => {
    const relation = relative(comparablePath(root).toLowerCase(), normalizedPath);
    return (
      relation === "" ||
      (!relation.startsWith(`..${sep}`) && relation !== ".." && !isAbsolute(relation))
    );
  };
  return {
    pathHash: createHash("sha256").update(normalizedPath, "utf8").digest("hex"),
    isAbsolute: isAbsolute(candidate),
    insideIsolatedJaHome: isWithin(isolatedRuntimeHome),
    insideProjectRoot: isWithin(projectRoot),
    insideExpectedSessionRoot: isWithin(expectedRoot),
    equalsExpectedSessionRoot: normalizedPath === comparablePath(expectedRoot).toLowerCase(),
  };
}

/** 失败诊断只保留路径归属、cwd 标记和输出摘要哈希，不把用户目录写入 E2E 报告。 */
export function terminalOutputDiagnostics(
  text,
  expectedRoot,
  isolatedRuntimeHome,
  projectRoot,
  terminalOpenWorkspaceMatches,
  beginMarker = SESSION_CWD_BEGIN,
  endMarker = SESSION_CWD_END,
) {
  const beginMarkerCount = text.split(beginMarker).length - 1;
  const endMarkerCount = text.split(endMarker).length - 1;
  const cwdCandidate = terminalCwdValue(text, beginMarker, endMarker);
  const candidates = extractTerminalWindowsPaths(text);
  if (cwdCandidate !== undefined) candidates.push(cwdCandidate);
  return {
    expectedHasExtendedPrefix: /^\\\\\?\\/u.test(expectedRoot),
    visibleHasExtendedPrefix: /\\\\\?\\/u.test(text),
    extractedWindowsPathCount: candidates.length,
    pathOwnership: candidates.map((candidate) =>
      pathOwnership(candidate, isolatedRuntimeHome, projectRoot, expectedRoot),
    ),
    beginMarkerVisible: beginMarkerCount > 0,
    endMarkerVisible: endMarkerCount > 0,
    probeCommandWasExecuted: cwdCandidate !== undefined,
    cwdValueBetweenMarkers: cwdCandidate !== undefined,
    cwdValueOwnership:
      cwdCandidate === undefined
        ? null
        : pathOwnership(cwdCandidate, isolatedRuntimeHome, projectRoot, expectedRoot),
    terminalOpenWorkspaceMatchesCurrentThread: terminalOpenWorkspaceMatches,
    queryOutputMatchesExpectedRoot: terminalTextContainsWorkspaceRoot(
      text,
      expectedRoot,
      beginMarker,
      endMarker,
    ),
    visibleTextLength: text.length,
    visibleTextFingerprint: createHash("sha256").update(text.slice(-1024), "utf8").digest("hex"),
  };
}

/** 只允许在隔离 Ja home 下创建 fixture，阻止错误 activation 将文件写入真实用户目录。 */
function assertOwnedSessionRoot(rootPath, isolatedRuntimeHome, threadId) {
  assert.ok(isAbsolute(rootPath), "native activation must return an absolute root");
  const expected = comparablePath(join(isolatedRuntimeHome, "workspaces", threadId));
  const actual = comparablePath(rootPath);
  const relation = relative(comparablePath(join(isolatedRuntimeHome, "workspaces")), actual);
  assert.equal(actual.toLowerCase(), expected.toLowerCase());
  assert.equal(relation.toLowerCase(), threadId.toLowerCase());
  assert.ok(!relation.startsWith(`..${sep}`) && relation !== "..");
  return actual;
}

/** 选择既有 Thread 并等待真实 aria-current 更新，确保 workbench owner 跟随该会话。 */
async function selectSessionThread(page, threadId, deadline) {
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
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
}

/** 当前可见 host 作为查询边界；保留的隐藏 Host 不参加 files 或 terminal 断言。 */
function currentInspector(page) {
  return page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
}

/** 打开会话右侧面板并返回其唯一可见 Workbench。 */
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
  assert.equal(await workbench.count(), 1);
  return workbench;
}

/** 只通过产品标签菜单切换 Workbench capability，不直接写入用户会话状态。 */
async function openCapability(page, workbench, key, label, deadline) {
  const tab = workbench.locator(`[data-workbench-tab="${key}"]`);
  if ((await tab.count()) === 1) {
    await tab.click({ timeout: timeout(deadline) });
    return tab;
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
  await tab.waitFor({ state: "visible", timeout: timeout(deadline) });
  return tab;
}

/** Files 树必须在当前会话目录显示自己的 marker 且不显示另一会话 marker。 */
async function assertSessionFiles(page, workbench, ownMarker, otherMarker, deadline) {
  await openCapability(page, workbench, "files", "文件", deadline);
  const files = workbench.getByRole("region", { name: "文件工作区", exact: true });
  await files.waitFor({ state: "visible", timeout: timeout(deadline) });
  const ownPath = files.locator(`[data-path="${ownMarker}"]`);
  await ownPath.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await files.locator(`[data-path="${otherMarker}"]`).count(), 0);
  return files;
}

/** 只选当前 Terminal 标签中的可见 Pane，并验证终端 RPC 与 shell cwd 同属选中 Thread。 */
async function openTerminalAndReadCwd(
  page,
  workbench,
  expectedRoot,
  expectedThreadId,
  expectedWorkspaceId,
  isolatedRuntimeHome,
  projectRoot,
  deadline,
  evidenceDirectory,
) {
  await openCapability(page, workbench, "terminal", "终端", deadline);
  const terminal = workbench.getByRole("region", { name: "终端工作区", exact: true });
  await terminal.waitFor({ state: "visible", timeout: timeout(deadline) });
  await terminal
    .getByRole("button", { name: /新建终端(?:标签页)?/u })
    .first()
    .click({
      timeout: timeout(deadline),
    });
  const creator = terminal.getByRole("dialog", { name: "新建终端", exact: true });
  await creator.waitFor({ state: "visible", timeout: timeout(deadline) });
  await creator.getByRole("combobox", { name: "Shell profile", exact: true }).click({
    timeout: timeout(deadline),
  });
  await page.getByRole("option", { name: "PowerShell", exact: true }).click({
    timeout: timeout(deadline),
  });
  const terminalOpenCountBefore = await page.evaluate(
    () =>
      (globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? []).filter(
        (entry) => entry.command === "ja_terminal_open",
      ).length,
  );
  await creator.getByRole("button", { name: "创建终端", exact: true }).click({
    timeout: timeout(deadline),
  });
  await creator.waitFor({ state: "detached", timeout: timeout(deadline) });
  const activePanel = terminal.locator('[role="tabpanel"]:not([hidden])');
  const pane = activePanel.locator(".ja-terminal-pane:visible").first();
  try {
    await activePanel.waitFor({ state: "visible", timeout: timeout(deadline) });
    await pane.waitFor({ state: "visible", timeout: timeout(deadline) });
    await pane
      .locator(".ja-terminal-pane-state")
      .getByText("运行中", { exact: true })
      .waitFor({
        state: "visible",
        timeout: timeout(deadline),
      });
  } catch (error) {
    const paneStates = await terminal
      .locator(".ja-terminal-pane")
      .evaluateAll((panes) =>
        panes.map((element) => ({
          hidden: element.closest('[role="tabpanel"][hidden]') !== null,
          visible: element.getClientRects().length > 0,
          lifecycle: element.querySelector(".ja-terminal-pane-state")?.textContent?.trim() ?? null,
          hasSession: element.hasAttribute("data-terminal-session-id"),
        })),
      )
      .catch(() => []);
    await page
      .screenshot({
        path: join(evidenceDirectory, "session-terminal-open-failure.png"),
        animations: "disabled",
      })
      .catch(() => undefined);
    throw new Error(
      `active SESSION terminal pane did not reach running state; pane states=${JSON.stringify(paneStates)}; ${String(error?.message ?? error)}`,
      { cause: error },
    );
  }
  const terminalBinding = await page.evaluate(
    ({ expectedThreadId: threadId, expectedWorkspaceId: workspaceId, previousCount }) => {
      const entries = (globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? []).filter(
        (entry) => entry.command === "ja_terminal_open",
      );
      const latest = entries.at(-1);
      const selectedThreadId = document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id");
      return {
        traceAddedForPane: entries.length > previousCount,
        traceResolved: latest?.phase === "resolved",
        selectedThreadMatches: selectedThreadId === threadId,
        workspaceIdMatches: latest?.workspaceId === workspaceId,
        observedWorkspaceId: latest?.workspaceId,
      };
    },
    {
      expectedThreadId,
      expectedWorkspaceId,
      previousCount: terminalOpenCountBefore,
    },
  );
  const terminalOpenWorkspaceMatches =
    terminalBinding.traceAddedForPane &&
    terminalBinding.traceResolved &&
    terminalBinding.selectedThreadMatches &&
    terminalBinding.workspaceIdMatches;
  if (!terminalOpenWorkspaceMatches) {
    const bindingEvidence = {
      traceAddedForPane: terminalBinding.traceAddedForPane,
      traceResolved: terminalBinding.traceResolved,
      selectedThreadMatches: terminalBinding.selectedThreadMatches,
      workspaceIdPresent: typeof terminalBinding.observedWorkspaceId === "string",
      workspaceIdMatches: terminalBinding.workspaceIdMatches,
      expectedWorkspaceIdFingerprint: createHash("sha256")
        .update(expectedWorkspaceId, "utf8")
        .digest("hex"),
      observedWorkspaceIdFingerprint:
        typeof terminalBinding.observedWorkspaceId === "string"
          ? createHash("sha256").update(terminalBinding.observedWorkspaceId, "utf8").digest("hex")
          : null,
    };
    await page
      .screenshot({
        path: join(evidenceDirectory, "session-terminal-binding-failure.png"),
        animations: "disabled",
      })
      .catch(() => undefined);
    throw new Error(`terminal_open IPC binding mismatch; ${JSON.stringify(bindingEvidence)}`);
  }

  const paneId = await pane.getAttribute("data-pane-id");
  assert.ok(paneId, "active terminal pane must expose an identity");
  const xterm = pane.locator(".xterm");
  await xterm.waitFor({ state: "visible", timeout: timeout(deadline) });
  await xterm.click({ timeout: timeout(deadline) });
  await page.waitForFunction(
    (expectedPaneId) => {
      const activeElement = document.activeElement;
      return (
        activeElement instanceof HTMLTextAreaElement &&
        activeElement.classList.contains("xterm-helper-textarea") &&
        activeElement.closest(".ja-terminal-pane")?.getAttribute("data-pane-id") === expectedPaneId
      );
    },
    paneId,
    { timeout: timeout(deadline) },
  );
  const rows = xterm.locator(".xterm-rows");
  await rows.waitFor({ state: "visible", timeout: timeout(deadline) });
  const promptEnd = Math.min(deadline, Date.now() + 8_000);
  let shellPromptObserved = false;
  while (Date.now() < promptEnd) {
    const startupTail = await rows.locator(":scope > div").evaluateAll((items) =>
      items
        .map((item) => item.textContent ?? "")
        .map((line) => line.trimEnd())
        .filter((line) => line.trim().length > 0)
        .slice(-4)
        .join("")
        .trimEnd(),
    );
    shellPromptObserved = /\bPS\s+[A-Za-z]:\\/iu.test(startupTail) && />$/u.test(startupTail);
    if (shellPromptObserved) break;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  if (!shellPromptObserved) {
    await page
      .screenshot({
        path: join(evidenceDirectory, "session-terminal-prompt-failure.png"),
        animations: "disabled",
      })
      .catch(() => undefined);
    throw new Error("active PowerShell pane did not present its prompt before the cwd probe");
  }
  const markerSuffix = randomUUID().replaceAll("-", "");
  const beginMarker = `${SESSION_CWD_BEGIN}_${markerSuffix}`;
  const endMarker = `${SESSION_CWD_END}_${markerSuffix}`;
  await page.keyboard.type(
    `Write-Output '${beginMarker}'; [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((Get-Location).Path)); Write-Output '${endMarker}'`,
  );
  await page.keyboard.press("Enter");
  const end = Math.min(deadline, Date.now() + 10_000);
  let lastVisibleText = "";
  while (Date.now() < end) {
    lastVisibleText = await rows
      .locator(":scope > div")
      .evaluateAll((items) => items.map((item) => item.textContent ?? "").join("\n"));
    if (terminalTextContainsWorkspaceRoot(lastVisibleText, expectedRoot, beginMarker, endMarker)) {
      const sessionId = await pane.getAttribute("data-terminal-session-id");
      assert.ok(sessionId, "native terminal must expose a session identity");
      return {
        fingerprint: createHash("sha256").update(sessionId, "utf8").digest("hex"),
        workspaceBindingMatches: terminalOpenWorkspaceMatches,
      };
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  const diagnostics = terminalOutputDiagnostics(
    lastVisibleText,
    expectedRoot,
    isolatedRuntimeHome,
    projectRoot,
    terminalOpenWorkspaceMatches,
    beginMarker,
    endMarker,
  );
  diagnostics.xtermHelperFocused = true;
  diagnostics.shellPromptObserved = shellPromptObserved;
  diagnostics.probeInputSentToActivePane = true;
  await page
    .screenshot({
      path: join(evidenceDirectory, "session-terminal-cwd-failure.png"),
      animations: "disabled",
    })
    .catch(() => undefined);
  throw new Error(
    `PowerShell did not report the active session workspace cwd; diagnostics=${JSON.stringify(diagnostics)}`,
  );
}

/** 保留菜单展开态的可视证据，并检查 ID-only IPC 不会替换当前 Thread。 */
async function openThreadFolderWithoutSelecting(
  page,
  threadId,
  workspaceId,
  title,
  expectedActiveThreadId,
  deadline,
  evidenceDirectory,
) {
  await page
    .locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`)
    .hover({ timeout: timeout(deadline) });
  await page.getByRole("button", { name: `对话菜单：${title}`, exact: true }).click({
    timeout: timeout(deadline),
  });
  const legacyActionVisible =
    (await page.getByRole("menuitem", { name: "打开旧共享文件夹", exact: true }).count()) > 0;
  await page.screenshot({
    path: join(evidenceDirectory, "03-fresh-session-menu.png"),
    animations: "disabled",
  });
  const openCountBefore = await page.evaluate(
    () =>
      (globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? []).filter(
        (entry) => entry.command === "ja_workspace_open",
      ).length,
  );
  await page.getByRole("menuitem", { name: "打开工作文件夹", exact: true }).click({
    timeout: timeout(deadline),
  });
  await page.waitForFunction(
    (previousCount) => {
      const entries = (globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? []).filter(
        (entry) => entry.command === "ja_workspace_open",
      );
      return entries.length > previousCount && entries.at(-1)?.phase === "resolved";
    },
    openCountBefore,
    { timeout: timeout(deadline) },
  );
  await page.waitForFunction(
    (id) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === id,
    expectedActiveThreadId,
    { timeout: timeout(deadline) },
  );
  assert.notEqual(threadId, expectedActiveThreadId);
  const folderOpenWorkspaceMatches = await page.evaluate(
    ({ expectedWorkspaceId, previousCount }) => {
      const entries = (globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? []).filter(
        (entry) => entry.command === "ja_workspace_open",
      );
      const latest = entries.at(-1);
      return entries.length > previousCount && latest?.workspaceId === expectedWorkspaceId;
    },
    { expectedWorkspaceId: workspaceId, previousCount: openCountBefore },
  );
  return { legacyActionVisible, folderOpenWorkspaceMatches };
}

/** 显式重启 App Server generation 并等待同一 WebView 的生产 lifecycle 投影恢复 ready。 */
async function restartRuntimeGeneration(page, previousGeneration, deadline) {
  const transition = await page.evaluate(async () => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const runtime = createRuntimeHostAdapter();
    const stopped = await runtime.stop();
    const started = await runtime.start();
    const state = await runtime.state();
    return { stopped, started, state };
  });
  assert.equal(transition.stopped.status, "stopped");
  assert.equal(transition.started.status, "ready");
  assert.equal(transition.state.status, "ready");
  assert.ok(transition.started.generation > previousGeneration);
  assert.equal(transition.state.generation, transition.started.generation);
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  return {
    fromGeneration: previousGeneration,
    toGeneration: transition.started.generation,
    runtimeReady: transition.state.status === "ready",
  };
}

/** 执行两个 SESSION、一项 side task 与项目切换，并记录无路径的可复核行为摘要。 */
export async function runSessionWorkspacesWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  isolatedRuntimeHome,
}) {
  assert.ok(page, "page is required");
  assert.ok(workspaceRoot, "isolated project root is required");
  assert.ok(isolatedRuntimeHome, "isolated Ja home is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + 8 * 60_000;
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  await page.context().addInitScript(installSessionWorkspaceProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await prepareApplication(page, deadline);

  const titleA = "Session Workspace A";
  const titleB = "Session Workspace B";
  const threadA = await createSessionThread(page, titleA);
  const threadB = await createSessionThread(page, titleB);
  assert.equal(threadA.workspaceKind, "session");
  assert.equal(threadB.workspaceKind, "session");
  assert.notEqual(threadA.threadId, threadB.threadId);
  assert.notEqual(threadA.workspaceId, threadB.workspaceId);
  assert.equal(threadA.legacySharedWorkspaceId, null);
  assert.equal(threadB.legacySharedWorkspaceId, null);

  const activatedA = await activateSessionWorkspace(page, threadA.workspaceId);
  const activatedB = await activateSessionWorkspace(page, threadB.workspaceId);
  assert.equal(activatedA.kind, "session");
  assert.equal(activatedB.kind, "session");
  const rootA = assertOwnedSessionRoot(activatedA.rootPath, isolatedRuntimeHome, threadA.threadId);
  const rootB = assertOwnedSessionRoot(activatedB.rootPath, isolatedRuntimeHome, threadB.threadId);
  assert.notEqual(rootA.toLowerCase(), rootB.toLowerCase());
  const markerA = "ja-session-a.txt";
  const markerB = "ja-session-b.txt";
  await Promise.all([mkdir(rootA, { recursive: true }), mkdir(rootB, { recursive: true })]);
  await Promise.all([
    writeFile(join(rootA, markerA), "isolated session A\n", "utf8"),
    writeFile(join(rootB, markerB), "isolated session B\n", "utf8"),
  ]);

  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await prepareApplication(page, deadline);
  const history = page.getByRole("list", { name: "最近对话列表", exact: true });
  await history
    .getByText(titleA, { exact: true })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await history
    .getByText(titleB, { exact: true })
    .waitFor({ state: "visible", timeout: timeout(deadline) });

  await selectSessionThread(page, threadA.threadId, deadline);
  await page.screenshot({
    path: join(evidenceDirectory, "01-session-a-selected.png"),
    animations: "disabled",
  });
  let workbenchA = await ensureWorkbenchVisible(page, deadline);
  await assertSessionFiles(page, workbenchA, markerA, markerB, deadline);
  await page.screenshot({
    path: join(evidenceDirectory, "02-session-a-files.png"),
    animations: "disabled",
  });
  const terminalResultA = await openTerminalAndReadCwd(
    page,
    workbenchA,
    rootA,
    threadA.threadId,
    threadA.workspaceId,
    isolatedRuntimeHome,
    workspaceRoot,
    deadline,
    evidenceDirectory,
  );
  const folderAction = await openThreadFolderWithoutSelecting(
    page,
    threadB.threadId,
    threadB.workspaceId,
    titleB,
    threadA.threadId,
    deadline,
    evidenceDirectory,
  );
  assert.equal(folderAction.legacyActionVisible, false);
  assert.equal(folderAction.folderOpenWorkspaceMatches, true);
  const activeA = await history
    .locator('button[aria-current="page"]')
    .getAttribute("data-thread-id");
  assert.equal(activeA, threadA.threadId);

  const closesBeforeSwitch = await page.evaluate(
    () =>
      (globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? []).filter(
        (entry) => entry.command === "ja_terminal_close" && entry.phase === "resolved",
      ).length,
  );
  await selectSessionThread(page, threadB.threadId, deadline);
  const scopeB = `${threadB.workspaceId}:${threadB.threadId}`;
  await page.waitForFunction(
    (scope) => {
      const host = document.querySelector(
        `.ja-thread-workbench-session[data-thread-workbench-scope="${scope}"]`,
      );
      const inspector = host?.querySelector('.ja-inspector[aria-label="工作区面板"]');
      return (
        host !== null &&
        host !== undefined &&
        !host.hasAttribute("hidden") &&
        inspector?.getAttribute("data-visible") !== "true"
      );
    },
    scopeB,
    { timeout: timeout(deadline) },
  );
  let workbenchB = await ensureWorkbenchVisible(page, deadline);
  await assertSessionFiles(page, workbenchB, markerB, markerA, deadline);
  await page.screenshot({
    path: join(evidenceDirectory, "04-session-b-files.png"),
    animations: "disabled",
  });
  const terminalResultB = await openTerminalAndReadCwd(
    page,
    workbenchB,
    rootB,
    threadB.threadId,
    threadB.workspaceId,
    isolatedRuntimeHome,
    workspaceRoot,
    deadline,
    evidenceDirectory,
  );
  assert.notEqual(terminalResultA.fingerprint, terminalResultB.fingerprint);

  await selectSessionThread(page, threadA.threadId, deadline);
  workbenchA = currentInspector(page).locator(".ja-workbench:visible");
  const terminalAAfterSwitch = await workbenchA
    .locator(".ja-terminal-pane[data-terminal-session-id]")
    .first()
    .getAttribute("data-terminal-session-id");
  assert.ok(terminalAAfterSwitch, "switching to B must not close A's terminal");
  await assertSessionFiles(page, workbenchA, markerA, markerB, deadline);
  await page.screenshot({
    path: join(evidenceDirectory, "05-session-a-restored.png"),
    animations: "disabled",
  });
  const closesAfterSwitch = await page.evaluate(
    () =>
      (globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? []).filter(
        (entry) => entry.command === "ja_terminal_close" && entry.phase === "resolved",
      ).length,
  );
  assert.equal(closesAfterSwitch, closesBeforeSwitch);

  const task = await page.evaluate(
    async ({ parentThreadId, revision }) => {
      const { createTaskAdapter } = await import("/src/api/tauri/tasks.ts");
      return createTaskAdapter().create({
        parentThreadId,
        parentTurnId: null,
        expectedParentRevision: revision,
        taskName: "Session workspace child",
      });
    },
    { parentThreadId: threadA.threadId, revision: threadA.revision },
  );
  const sideTaskDetail = await page.evaluate(async (taskThreadId) => {
    const { createTaskAdapter } = await import("/src/api/tauri/tasks.ts");
    return createTaskAdapter().read({ taskThreadId, limit: 1 });
  }, task.task.taskThreadId);
  assert.equal(sideTaskDetail.task.parentThreadId, threadA.threadId);
  assert.equal(sideTaskDetail.thread.workspaceId, threadA.workspaceId);
  assert.equal(sideTaskDetail.thread.workspaceKind, "session");

  const generationBeforeRestart = await page.evaluate(async () => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    return (await createRuntimeHostAdapter().state()).generation;
  });
  const runtimeRestart = await restartRuntimeGeneration(page, generationBeforeRestart, deadline);
  await selectSessionThread(page, threadA.threadId, deadline);
  const restoredWorkbenchA = currentInspector(page).locator(".ja-workbench:visible");
  await assertSessionFiles(page, restoredWorkbenchA, markerA, markerB, deadline);
  const restoredTerminalA = await restoredWorkbenchA
    .locator(".ja-terminal-pane[data-terminal-session-id]")
    .first()
    .getAttribute("data-terminal-session-id");
  assert.equal(restoredTerminalA, terminalAAfterSwitch);
  await selectSessionThread(page, threadB.threadId, deadline);
  const restoredWorkbenchB = currentInspector(page).locator(".ja-workbench:visible");
  await assertSessionFiles(page, restoredWorkbenchB, markerB, markerA, deadline);

  const project = await page.evaluate(async (cwd) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const history = createHistoryAdapter();
    const workspace = await history.workspaceOpen({ cwd });
    const thread = await history.threadCreate({
      cwd: workspace.root,
      title: "Session runner project thread",
      providerId: "provider_e2e",
      modelId: "model_e2e",
      reasoningLevel: null,
      accessMode: "approval_required",
      collaborationMode: "default",
    });
    return { workspace, thread };
  }, workspaceRoot);
  assert.equal(project.workspace.kind, "project");
  assert.equal(project.thread.workspaceKind, "project");
  assert.equal(project.thread.workspaceId, project.workspace.workspaceId);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await prepareApplication(page, deadline);
  const projectRow = page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: project.workspace.displayName });
  await projectRow.waitFor({ state: "visible", timeout: timeout(deadline) });
  await projectRow.click({ timeout: timeout(deadline) });
  await page.waitForFunction(
    (displayName) => {
      const selected = document.querySelector(
        '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
      );
      return selected?.textContent?.includes(displayName) === true;
    },
    project.workspace.displayName,
    { timeout: timeout(deadline) },
  );
  await selectSessionThread(page, project.thread.threadId, deadline);
  await page.screenshot({
    path: join(evidenceDirectory, "06-project-session.png"),
    animations: "disabled",
  });
  const projectActivation = await page.evaluate(
    (workspaceId) =>
      [...(globalThis.__JA_SESSION_WORKSPACE_TRACE__ ?? [])]
        .reverse()
        .find(
          (entry) =>
            entry.command === "ja_runtime_workspace_open" &&
            entry.phase === "resolved" &&
            entry.resultWorkspaceId === workspaceId,
        ),
    project.workspace.workspaceId,
  );
  assert.ok(
    projectActivation,
    "selecting a project must reopen its server-issued workspace",
  );

  const report = {
    contractVersion: 1,
    runtime: "tauri_webview2",
    verdict: "PASS",
    fixture: {
      transport: "typed_tauri_session_adapter",
      sessionThreadCount: 2,
      distinctWorkspaceIds: threadA.workspaceId !== threadB.workspaceId,
      cwdOmittedForCreate: true,
      providerTurnInvokes: 0,
    },
    workspaces: {
      distinctCanonicalRoots: rootA.toLowerCase() !== rootB.toLowerCase(),
      fileMarkersScoped: true,
      terminalRootsScoped: true,
      terminalWorkspaceBindingMatches: [
        terminalResultA.workspaceBindingMatches,
        terminalResultB.workspaceBindingMatches,
      ].every(Boolean),
      terminalFingerprintsDistinct: terminalResultA.fingerprint !== terminalResultB.fingerprint,
      terminalSurvivesSessionSwitch: true,
      switchClosedTerminals: closesAfterSwitch - closesBeforeSwitch,
      folderActionDidNotSelectItsThread: true,
      folderActionWorkspaceMatches: folderAction.folderOpenWorkspaceMatches,
      legacyActionHiddenForFreshSession: !folderAction.legacyActionVisible,
    },
    sideTask: {
      created: true,
      inheritedParentWorkspace: sideTaskDetail.thread.workspaceId === threadA.workspaceId,
    },
    restart: {
      kind: "runtime_generation",
      fromGeneration: runtimeRestart.fromGeneration,
      toGeneration: runtimeRestart.toGeneration,
      advanced: runtimeRestart.toGeneration > runtimeRestart.fromGeneration,
      ready: runtimeRestart.runtimeReady,
      sessionDirectoriesRestored: true,
      terminalIdentityRestored: restoredTerminalA === terminalAAfterSwitch,
    },
    project: {
      selected: true,
      workspaceKind: "project",
      threadWorkspaceKind: project.thread.workspaceKind,
      identityMatches: projectActivation.resultWorkspaceId === project.thread.workspaceId,
    },
    pageErrors,
  };
  assert.deepEqual(pageErrors, []);
  return report;
}

/** 校验 runner 必须证明真实 SESSION 独立性、文件/终端范围和 side task 继承关系。 */
export function validateSessionWorkspacesReport(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.runtime, "tauri_webview2");
  assert.equal(report?.verdict, "PASS");
  assert.equal(report?.fixture?.transport, "typed_tauri_session_adapter");
  assert.equal(report?.fixture?.sessionThreadCount, 2);
  assert.equal(report?.fixture?.distinctWorkspaceIds, true);
  assert.equal(report?.fixture?.cwdOmittedForCreate, true);
  assert.equal(report?.fixture?.providerTurnInvokes, 0);
  assert.equal(report?.workspaces?.distinctCanonicalRoots, true);
  assert.equal(report?.workspaces?.fileMarkersScoped, true);
  assert.equal(report?.workspaces?.terminalRootsScoped, true);
  assert.equal(report?.workspaces?.terminalWorkspaceBindingMatches, true);
  assert.equal(report?.workspaces?.terminalFingerprintsDistinct, true);
  assert.equal(report?.workspaces?.terminalSurvivesSessionSwitch, true);
  assert.equal(report?.workspaces?.switchClosedTerminals, 0);
  assert.equal(report?.workspaces?.folderActionDidNotSelectItsThread, true);
  assert.equal(report?.workspaces?.folderActionWorkspaceMatches, true);
  assert.equal(report?.workspaces?.legacyActionHiddenForFreshSession, true);
  assert.equal(report?.sideTask?.created, true);
  assert.equal(report?.sideTask?.inheritedParentWorkspace, true);
  assert.equal(report?.restart?.kind, "runtime_generation");
  assert.ok(report?.restart?.toGeneration > report?.restart?.fromGeneration);
  assert.equal(report?.restart?.advanced, true);
  assert.equal(report?.restart?.ready, true);
  assert.equal(report?.restart?.sessionDirectoriesRestored, true);
  assert.equal(report?.restart?.terminalIdentityRestored, true);
  assert.equal(report?.project?.selected, true);
  assert.equal(report?.project?.workspaceKind, "project");
  assert.equal(report?.project?.threadWorkspaceKind, "project");
  assert.equal(report?.project?.identityMatches, true);
  assert.deepEqual(report?.pageErrors, []);
  return report;
}

/** 解析独立 Cargo target 与失败诊断选项，避免运行 Session runner 覆盖共享开发产物。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-session-workspaces"),
    edgeDriver: undefined,
    preserveFailedProfile: false,
  };
  for (let index = 0; index < argv.length; ) {
    const argument = argv[index];
    if (argument === "--preserve-failed-profile") {
      options.preserveFailedProfile = true;
      index += 1;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (argument === "--jar") options.jar = resolve(value);
    else if (argument === "--java-home") options.javaHome = resolve(value);
    else if (argument === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else if (argument === "--edge-driver") options.edgeDriver = resolve(value);
    else throw new Error(`unknown argument: ${argument}`);
    index += 2;
  }
  if (options.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (options.jar === undefined) throw new Error("--jar is required");
  return options;
}

/** 复用已验证的随机 Temp profile launcher，真窗失败时可选择保留隔离诊断目录。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const report = await runProduction({
    ...options,
    scope: "git",
    fixture: "no-head",
    ignoredFiles: 0,
    untrackedFiles: 0,
    driver: runSessionWorkspacesWebView2,
    validateReport: validateSessionWorkspacesReport,
    reportFileName: "session-workspaces-report.json",
    prewarmWebview: true,
  });
  console.log(`JA_SESSION_WORKSPACES_PASS ${JSON.stringify({ verdict: report.verdict })}`);
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(`JA_SESSION_WORKSPACES_FAIL ${String(error?.message ?? error).slice(0, 2000)}`);
    process.exitCode = 1;
  });
}
