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
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const STEP_TIMEOUT_MS = 30_000;
const FILE_SOURCE_PROJECT = "Thread Workbench Source";
const FILE_TARGET_PROJECT = "Thread Workbench Save Target";

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

/** 健康连接文案可隐藏；读取生产 typed Host 状态后选中 runner 的唯一隔离项目。 */
async function prepareApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.waitForFunction(
    async () => {
      try {
        const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
        const state = await new TauriRuntimeHostAdapter().state();
        return (state.status === "ready" || state.status === "busy") &&
          typeof state.serverInstanceId === "string" && state.serverInstanceId.length > 0;
      } catch {
        return false;
      }
    },
    undefined,
    { timeout: timeout(deadline) },
  );
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
      hasPreview: current.querySelector('[aria-label="浏览器地址"]') !== null,
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
 * Workbench 首次打开 Terminal 时已有一个休眠的默认 Pane；只等待它在当前会话的可见 Tab
 * 中绑定 native PTY，避免额外创建第二个会话后把布局计数误判为隔离失败。
 */
async function openNativeTerminal(page, workbench, deadline, evidenceDirectory) {
  await openCapability(page, workbench, "terminal", "终端", deadline);
  const terminal = workbench.getByRole("region", { name: "终端工作区", exact: true });
  await terminal.waitFor({ state: "visible", timeout: timeout(deadline) });
  const activePanel = terminal.locator('[role="tabpanel"]:not([hidden])');
  const pane = activePanel.locator(".ja-terminal-pane:visible").first();
  try {
    await activePanel.waitFor({ state: "visible", timeout: timeout(deadline) });
    await pane.waitFor({ state: "visible", timeout: timeout(deadline) });
    await pane
      .locator(".ja-terminal-pane-state")
      .getByText("运行中", { exact: true })
      .waitFor({ state: "visible", timeout: timeout(deadline) });
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
        path: join(evidenceDirectory, "terminal-open-failure.png"),
        animations: "disabled",
      })
      .catch(() => undefined);
    throw new Error(
      `active Terminal pane did not reach running state; pane states=${JSON.stringify(paneStates)}; ${String(error?.message ?? error)}`,
      { cause: error },
    );
  }
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
  const address = workbench.getByRole("textbox", { name: "浏览器地址", exact: true });
  const target = new URL("/favicon.png?thread-workbench=B", page.url()).href;
  const before = traceCount(await commandTrace(page), "ja_preview_open", "resolved");
  await address.fill(target);
  await workbench.getByRole("button", { name: "访问地址", exact: true }).click({
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

/** 两个隔离项目先登记并独立授信，Thread 创建依赖此 setup；实际编辑与切换仍从 UI 触发。 */
async function prepareThreadFilesFixtures(page, workspaceRoot) {
  const fixtures = [
    { relativePath: "thread-a-draft.txt", original: "original A\n", draft: "saved Thread A draft\n" },
    { relativePath: "thread-b-draft.txt", original: "original B\n", draft: "saved Thread B draft\n" },
  ];
  const targetRoot = join(dirname(workspaceRoot), "thread-files-switch-target");
  await mkdir(targetRoot, { recursive: true });
  await Promise.all(fixtures.map((fixture) =>
    writeFile(join(workspaceRoot, fixture.relativePath), fixture.original, "utf8"),
  ));
  await page.evaluate(async ({ sourceRoot, switchRoot, sourceLabel, targetLabel }) => {
    const { TauriHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const history = new TauriHistoryAdapter();
    const source = await history.workspaceOpen({ cwd: sourceRoot, displayName: sourceLabel });
    await history.workspaceSetTrust({ workspaceId: source.workspaceId, trust: "trusted" });
    const target = await history.workspaceOpen({ cwd: switchRoot, displayName: targetLabel });
    await history.workspaceSetTrust({ workspaceId: target.workspaceId, trust: "trusted" });
  }, { sourceRoot: workspaceRoot, switchRoot: targetRoot,
    sourceLabel: FILE_SOURCE_PROJECT, targetLabel: FILE_TARGET_PROJECT });
  return fixtures;
}

/**
 * 两个保留 Thread 各有真实 Files 草稿；一次 transport 故障必须取消聚合切换且保留草稿。
 * 用户取消关闭文件后再显式重试项目切换，所有保存必须经 native CAS 落盘后才释放资源。
 */
async function exerciseRetainedThreadFiles(page, threads, fixtures, workspaceRoot, deadline, evidenceDirectory) {
  await page.evaluate((paths) => {
    const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const state = { block: true, failed: [], saved: [] };
    globalThis.__JA_THREAD_FILES_SAVE_FAULT__ = state;
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
      const path = request?.args?.input?.relativePath;
      if (request?.command !== "ja_workspace_save_file" || !paths.includes(path))
        return previous === undefined ? delegate() : previous(request, delegate);
      if (state.block) {
        state.failed.push(path);
        throw { code: "WORKSPACE_IO_ERROR" };
      }
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      state.saved.push(path);
      return result;
    };
    state.restore = () => { globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = previous; };
  }, fixtures.map((fixture) => fixture.relativePath));
  try {
    for (let index = 0; index < fixtures.length; index += 1) {
      await selectThread(page, threads[index].threadId, deadline);
      const workbench = await ensureWorkbenchVisible(page, deadline);
      await openCapability(page, workbench, "files", "文件", deadline);
      const files = workbench.locator('[aria-label="文件工作区"]');
      await files.locator(`.ja-file-tree-host [data-path="${fixtures[index].relativePath}"]`).dblclick({
        timeout: timeout(deadline),
      });
      const editor = files.locator('.cm-content[contenteditable="true"]');
      await editor.waitFor({ state: "visible", timeout: timeout(deadline) });
      await editor.fill(fixtures[index].draft);
      await files.locator('.ja-files-editor-status[data-status="saveError"]').waitFor({
        state: "visible", timeout: timeout(deadline),
      });
      assert.equal(await readFile(join(workspaceRoot, fixtures[index].relativePath), "utf8"), fixtures[index].original);
    }
    const workbench = currentInspector(page).locator(".ja-workbench:visible");
    const files = workbench.locator('[aria-label="文件工作区"]');
    await files.getByRole("button", { name: `关闭 ${fixtures[1].relativePath}`, exact: true }).click();
    const closeDialog = page.getByRole("alertdialog", { name: "关闭未保存文件", exact: true });
    await closeDialog.waitFor({ state: "visible", timeout: timeout(deadline) });
    await closeDialog.getByRole("button", { name: "取消", exact: true }).click();
    await closeDialog.waitFor({ state: "detached", timeout: timeout(deadline) });
    assert.equal(await files.locator(`[data-file-tab-path="${fixtures[1].relativePath}"]`).count(), 1);
    assert.ok((await files.locator(".cm-content").innerText()).includes(fixtures[1].draft.trim()));

    const projects = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]');
    const target = projects.filter({ hasText: FILE_TARGET_PROJECT });
    const source = projects.filter({ hasText: FILE_SOURCE_PROJECT });
    const before = await page.evaluate(() => globalThis.__JA_THREAD_FILES_SAVE_FAULT__.failed.length);
    const cleanupBefore = await commandTrace(page);
    await target.click({ timeout: timeout(deadline) });
    await waitForCondition("失败保存取消项目切换", async () =>
      (await page.evaluate(() => globalThis.__JA_THREAD_FILES_SAVE_FAULT__.failed.length)) >= before + 2 &&
        await target.isEnabled() && (await source.getAttribute("aria-current")) === "page", deadline);
    assert.notEqual(await target.getAttribute("aria-current"), "page");
    const cleanupAfter = await commandTrace(page);
    for (const command of ["ja_terminal_close", "ja_preview_close"]) {
      assert.equal(traceCount(cleanupAfter, command, "start"), traceCount(cleanupBefore, command, "start"));
    }
    for (let index = 0; index < fixtures.length; index += 1) {
      await selectThread(page, threads[index].threadId, deadline);
      assert.ok((await currentInspector(page).locator('.ja-workbench:visible [aria-label="文件工作区"] .cm-content')
        .innerText()).includes(fixtures[index].draft.trim()), "取消切换后丢失了隐藏 Thread 的草稿");
      assert.equal(await readFile(join(workspaceRoot, fixtures[index].relativePath), "utf8"), fixtures[index].original);
    }
    await page.screenshot({ path: join(evidenceDirectory, "thread-files-failed-switch-draft-retained.png"), animations: "disabled" });
    await page.evaluate(() => { globalThis.__JA_THREAD_FILES_SAVE_FAULT__.block = false; });
    await target.click({ timeout: timeout(deadline) });
    await waitForCondition("保存后完成项目切换", async () => (await target.getAttribute("aria-current")) === "page", deadline);
    const saved = await page.evaluate(() => globalThis.__JA_THREAD_FILES_SAVE_FAULT__.saved);
    assert.deepEqual([...saved].sort(), fixtures.map((fixture) => fixture.relativePath).sort());
    for (const fixture of fixtures) {
      assert.equal(await readFile(join(workspaceRoot, fixture.relativePath), "utf8"), fixture.draft);
    }
    return { retainedThreadCount: 2, dirtyDocumentCount: 2, injectedFailure: "transport_before_commit",
      closeCancelledDraftRetained: true, failedSwitchDraftsRetained: true,
      failedSwitchResourcesRetained: true, savedNativeFiles: 2, switchedAfterSave: true };
  } finally {
    await page.evaluate(() => {
      globalThis.__JA_THREAD_FILES_SAVE_FAULT__?.restore?.();
      delete globalThis.__JA_THREAD_FILES_SAVE_FAULT__;
    }).catch(() => undefined);
  }
}

/**
 * 从真实 Window.close 进入原生退出握手；先确认两份隐藏/可见草稿阻止退出，再验证重试保存和窗口销毁。
 * 只在 native 保存之前注入失败，Node 保留 ACK 事实，避免成功退出后依赖已经销毁的 renderer 回读。
 */
async function exerciseRetainedThreadExit(page, threads, fixtures, workspaceRoot, deadline, evidenceDirectory) {
  const acknowledgements = [];
  await page.exposeFunction("__JA_THREAD_EXIT_ACK__", (entry) => {
    acknowledgements.push(entry);
  });
  const source = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: FILE_SOURCE_PROJECT });
  await source.click({ timeout: timeout(deadline) });
  await waitForCondition("返回退出验收项目", async () =>
    (await source.getAttribute("aria-current")) === "page", deadline);
  await page.evaluate((paths) => {
    const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const state = { block: true };
    globalThis.__JA_THREAD_EXIT_SAVE_FAULT__ = state;
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
      const path = request?.args?.input?.relativePath;
      const save = request?.command === "ja_workspace_save_file" && paths.includes(path);
      const exit = ["ja_app_exit_cancel", "ja_app_exit_commit"].includes(request?.command);
      if (!save && !exit) return previous === undefined ? delegate() : previous(request, delegate);
      await globalThis.__JA_THREAD_EXIT_ACK__({ command: request.command, phase: "start", ...(save ? { path } : {}) });
      if (save && state.block) {
        await globalThis.__JA_THREAD_EXIT_ACK__({ command: request.command, phase: "rejected", path });
        throw { code: "WORKSPACE_IO_ERROR" };
      }
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      await globalThis.__JA_THREAD_EXIT_ACK__({ command: request.command, phase: "resolved", ...(save ? { path } : {}) });
      return result;
    };
    state.restore = () => { globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = previous; };
  }, fixtures.map((fixture) => fixture.relativePath));
  const exitDrafts = fixtures.map((fixture, index) => ({ ...fixture, exitDraft: `saved before exit Thread ${index === 0 ? "A" : "B"}\n` }));
  try {
    for (let index = 0; index < exitDrafts.length; index += 1) {
      await selectThread(page, threads[index].threadId, deadline);
      const workbench = await ensureWorkbenchVisible(page, deadline);
      await openCapability(page, workbench, "files", "文件", deadline);
      const files = workbench.locator('[aria-label="文件工作区"]');
      await files.locator(`.ja-file-tree-host [data-path="${exitDrafts[index].relativePath}"]`).dblclick({ timeout: timeout(deadline) });
      const editor = files.locator('.cm-content[contenteditable="true"]');
      await editor.waitFor({ state: "visible", timeout: timeout(deadline) });
      await editor.fill(exitDrafts[index].exitDraft);
      await files.locator('.ja-files-editor-status[data-status="saveError"]').waitFor({ state: "visible", timeout: timeout(deadline) });
    }
    const failedBefore = acknowledgements.filter((entry) => entry.command === "ja_workspace_save_file" && entry.phase === "rejected").length;
    await page.evaluate(async () => {
      const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
      if (typeof invoke !== "function") throw new Error("Tauri bridge unavailable");
      await invoke("ja_desktop_close_behavior_save", { value: "exit" });
      await invoke("plugin:window|close", { label: "main" });
    });
    await page.getByText("Ja 未能安全退出，请处理未保存或冲突的文件后重试。", { exact: true })
      .waitFor({ state: "visible", timeout: timeout(deadline) });
    await waitForCondition("原生退出请求已取消", async () =>
      acknowledgements.some((entry) => entry.command === "ja_app_exit_cancel" && entry.phase === "resolved"), deadline);
    assert.equal(page.isClosed(), false);
    assert.ok(acknowledgements.filter((entry) => entry.command === "ja_workspace_save_file" && entry.phase === "rejected").length >= failedBefore + 2);
    assert.equal(acknowledgements.filter((entry) => entry.command === "ja_app_exit_commit").length, 0);
    for (let index = 0; index < exitDrafts.length; index += 1) {
      await selectThread(page, threads[index].threadId, deadline);
      assert.ok((await currentInspector(page).locator('.ja-workbench:visible [aria-label="文件工作区"] .cm-content')
        .innerText()).includes(exitDrafts[index].exitDraft.trim()), "退出失败后丢失了保留 Thread 的草稿");
      assert.equal(await readFile(join(workspaceRoot, exitDrafts[index].relativePath), "utf8"), exitDrafts[index].draft);
    }
    const failureScreenshot = join(evidenceDirectory, "thread-files-exit-save-failed.png");
    await page.screenshot({ path: failureScreenshot, animations: "disabled" });
    await page.evaluate(() => { globalThis.__JA_THREAD_EXIT_SAVE_FAULT__.block = false; });
    const closed = page.waitForEvent("close", { timeout: timeout(deadline) }).then(() => true, () => false);
    let closeFailure;
    try {
      await page.evaluate(async () => {
        await globalThis.__TAURI_INTERNALS__.invoke("plugin:window|close", { label: "main" });
      });
    } catch (error) {
      closeFailure = error;
    }
    if (!(await closed)) throw closeFailure ?? new Error("保存重试后真实 WebView2 窗口未销毁");
    const saved = acknowledgements.filter((entry) => entry.command === "ja_workspace_save_file" && entry.phase === "resolved").map((entry) => entry.path);
    assert.deepEqual([...saved].sort(), exitDrafts.map((fixture) => fixture.relativePath).sort());
    assert.equal(acknowledgements.filter((entry) => entry.command === "ja_app_exit_commit" && entry.phase === "start").length, 1);
    for (const fixture of exitDrafts) {
      assert.equal(await readFile(join(workspaceRoot, fixture.relativePath), "utf8"), fixture.exitDraft);
    }
    return { retainedThreadCount: 2, dirtyDocumentCount: 2, entry: "native_window_close",
      injectedFailure: "transport_before_commit", failedExitDraftsRetained: true,
      failedExitCancelled: true, savedNativeFiles: 2, committedAfterSave: true,
      windowDestroyed: true, failureScreenshot };
  } finally {
    if (!page.isClosed()) await page.evaluate(() => {
      globalThis.__JA_THREAD_EXIT_SAVE_FAULT__?.restore?.();
      delete globalThis.__JA_THREAD_EXIT_SAVE_FAULT__;
    }).catch(() => undefined);
  }
}

/**
 * fixture 的显式授信必须先于 A/B Thread 创建；随后验收隔离、native 恢复与实际保存边界。
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
  const filesFixtures = await prepareThreadFilesFixtures(page, workspaceRoot);

  const titleA = "Thread Workbench A";
  const titleB = "Thread Workbench B";
  const threadA = await createThreadFixture(page, workspaceRoot, titleA);
  const threadB = await createThreadFixture(page, workspaceRoot, titleB);
  assert.notEqual(threadA.threadId, threadB.threadId);
  assert.equal(threadA.title, titleA);
  assert.equal(threadB.title, titleB);

  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await prepareApplication(page, deadline);
  const sourceProject = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: FILE_SOURCE_PROJECT });
  if ((await sourceProject.getAttribute("aria-current")) !== "page")
    await sourceProject.click({ timeout: timeout(deadline) });
  const history = page.getByRole("list", { name: "最近对话列表", exact: true });
  await history
    .getByText(titleA, { exact: true })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await history
    .getByText(titleB, { exact: true })
    .waitFor({ state: "visible", timeout: timeout(deadline) });

  await selectThread(page, threadA.threadId, deadline);
  let workbench = await ensureWorkbenchVisible(page, deadline);
  const sessionA = await openNativeTerminal(page, workbench, deadline, evidenceDirectory);
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
  const sessionB = await openNativeTerminal(page, workbench, deadline, evidenceDirectory);
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
    await workbench.getByRole("textbox", { name: "浏览器地址", exact: true }).inputValue(),
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
    await workbench.getByRole("textbox", { name: "浏览器地址", exact: true }).inputValue(),
    previewTarget,
  );

  await selectThread(page, threadA.threadId, deadline);
  const aSecondRestore = await assertBoundState(page, { tab: "terminal" });
  assert.equal(aSecondRestore.terminalSessionCount, 1);
  await selectThread(page, threadB.threadId, deadline);
  const bSecondRestore = await assertBoundState(page, { tab: "preview" });
  assert.equal(
    await workbench.getByRole("textbox", { name: "浏览器地址", exact: true }).inputValue(),
    previewTarget,
  );

  const retainedFiles = await exerciseRetainedThreadFiles(
    page, [threadA, threadB], filesFixtures, workspaceRoot, deadline, evidenceDirectory,
  );

  const trace = await commandTrace(page);
  assert.equal(traceCount(trace, "ja_turn_start", "start"), 0);
  assert.deepEqual(pageErrors, []);
  const retainedExit = await exerciseRetainedThreadExit(
    page, [threadA, threadB], filesFixtures, workspaceRoot, deadline, evidenceDirectory,
  );
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
    retainedFiles,
    retainedExit,
    screenshots: [aScreenshot, bScreenshot],
    pageErrors,
  };
}

/** 双 Thread 隔离、全部 Files 保存及原生退出都需完整证据，缺失任一失败恢复边界即拒绝 PASS。 */
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
  assert.equal(report?.retainedFiles?.retainedThreadCount, 2);
  assert.equal(report?.retainedFiles?.dirtyDocumentCount, 2);
  assert.equal(report?.retainedFiles?.injectedFailure, "transport_before_commit");
  assert.equal(report?.retainedFiles?.closeCancelledDraftRetained, true);
  assert.equal(report?.retainedFiles?.failedSwitchDraftsRetained, true);
  assert.equal(report?.retainedFiles?.failedSwitchResourcesRetained, true);
  assert.equal(report?.retainedFiles?.savedNativeFiles, 2);
  assert.equal(report?.retainedFiles?.switchedAfterSave, true);
  assert.equal(report?.retainedExit?.retainedThreadCount, 2);
  assert.equal(report?.retainedExit?.dirtyDocumentCount, 2);
  assert.equal(report?.retainedExit?.entry, "native_window_close");
  assert.equal(report?.retainedExit?.injectedFailure, "transport_before_commit");
  assert.equal(report?.retainedExit?.failedExitDraftsRetained, true);
  assert.equal(report?.retainedExit?.failedExitCancelled, true);
  assert.equal(report?.retainedExit?.savedNativeFiles, 2);
  assert.equal(report?.retainedExit?.committedAfterSave, true);
  assert.equal(report?.retainedExit?.windowDestroyed, true);
  assert.deepEqual(report?.pageErrors, []);
  return report;
}

/** 解析窄 CLI、固定独立 Cargo target，并可为真实窗口启动故障保留隔离诊断现场。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-thread-workbench"),
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
    preserveFailedProfile: options.preserveFailedProfile,
    prewarmWebview: true,
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
