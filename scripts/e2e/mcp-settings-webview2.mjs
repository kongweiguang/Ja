// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createServer } from "node:http";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { realpathSync } from "node:fs";
import { homedir } from "node:os";
import { isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium, expect } from "@playwright/test";

/** 本地无认证 MCP fixture 走真实 Java SDK；可延迟 loopback tools/list 覆盖 UI pending 生命周期。 */
export async function startMcpFixture({ slowToolListDelayMs = 0 } = {}) {
  if (
    !Number.isSafeInteger(slowToolListDelayMs) ||
    slowToolListDelayMs < 0 ||
    slowToolListDelayMs > 10_000
  ) {
    throw new Error("slowToolListDelayMs must be an integer between 0 and 10000");
  }
  const calls = [];
  let slowToolListRequests = 0;
  let activeSlowToolListRequests = 0;
  const tools = Array.from({ length: 68 }, (_, index) => ({
    name: index === 0 ? "settings_echo" : `settings_tool_${String(index + 1).padStart(2, "0")}`,
    description: `本地目录验收工具 ${index + 1}`,
    inputSchema: {
      type: "object",
      properties: { value: { type: "string" } },
      required: ["value"],
      additionalProperties: false,
    },
  }));
  let catalogFailureToolLists = 0;
  const server = createServer(async (request, response) => {
    if (request.method !== "POST") {
      response.writeHead(405).end();
      return;
    }
    let raw = "";
    for await (const chunk of request) raw += chunk;
    const frame = JSON.parse(raw);
    const pathname = new URL(request.url ?? "/", "http://127.0.0.1").pathname;
    calls.push({ method: frame.method, pathname });
    if (frame.id === undefined) {
      response.writeHead(202).end();
      return;
    }
    if (pathname === "/probe-failure") {
      response.writeHead(503).end("fixture probe unavailable");
      return;
    }
    if (pathname === "/catalog-failure" && frame.method === "tools/list") {
      catalogFailureToolLists += 1;
      // testMcp 的第一次快照必须成功，只有 UI 随后单独读取目录时才失败。
      if (catalogFailureToolLists > 1) {
        response.writeHead(200, { "content-type": "application/json" });
        response.end(
          JSON.stringify({
            jsonrpc: "2.0",
            id: frame.id,
            error: { code: -32603, message: "fixture tool catalog unavailable" },
          }),
        );
        return;
      }
    }
    if (pathname === "/slow" && frame.method === "tools/list" && slowToolListDelayMs > 0) {
      slowToolListRequests += 1;
      activeSlowToolListRequests += 1;
      try {
        await new Promise((resolve) => setTimeout(resolve, slowToolListDelayMs));
      } finally {
        activeSlowToolListRequests -= 1;
      }
    }
    const result =
      frame.method === "initialize"
        ? {
            protocolVersion: "2025-06-18",
            capabilities: { tools: {} },
            serverInfo: { name: "ja-settings-fixture", version: "1" },
          }
        : frame.method === "tools/list"
          ? { tools }
          : {};
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ jsonrpc: "2.0", id: frame.id, result }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  return {
    server,
    calls,
    tools,
    get slowToolListRequests() {
      return slowToolListRequests;
    },
    get activeSlowToolListRequests() {
      return activeSlowToolListRequests;
    },
    url: `${origin}/mcp`,
    urlFor: (name) => `${origin}/${name}`,
  };
}

/** 窄视口返回桌面宽度后侧栏可能仍收起；只走真实顶栏开关恢复设置入口。 */
async function section(page, name) {
  if (!(await page.locator(".ja-settings").isVisible())) {
    const settings = page.getByRole("button", { name: "设置", exact: true });
    for (let attempt = 0; attempt < 2 && !(await settings.isVisible()); attempt += 1) {
      await page
        .getByRole("button", { name: /侧边栏$/, exact: false })
        .first()
        .click();
    }
    await settings.click();
  }
  await expect(page.locator(".ja-settings")).not.toHaveAttribute("inert", "");
  await page.getByRole("tab", { name, exact: true }).click();
}

/** 主题用真实设置保存，避免修改 DOM 模拟一个实际无法达到的外观。 */
async function theme(page, value) {
  await section(page, "外观");
  const control = page.getByRole("combobox", { name: "外观模式", exact: true });
  await control.click();
  await page.getByRole("option", { name: value, exact: true }).click();
  await section(page, "MCP");
}

/** 截图前等待有限动画，并对实际组件及全局样式检查横向溢出。 */
async function capture(page, name, evidenceDirectory) {
  await page.evaluate(async () => {
    await Promise.all(
      globalThis.document
        .getAnimations()
        .filter((animation) =>
          Number.isFinite(animation.effect?.getComputedTiming().endTime ?? Infinity),
        )
        .map((animation) => animation.finished.catch(() => undefined)),
    );
  });
  const overflow = await page.evaluate(
    () => globalThis.document.documentElement.scrollWidth > globalThis.innerWidth + 1,
  );
  expect(overflow).toBe(false);
  await page.screenshot({
    path: join(evidenceDirectory, `${name}.png`),
    animations: "disabled",
  });
}

/** 在窄视口校验弹层边界和固定底部入口，清单首行应可直接看到。 */
async function captureResponsivePopover(
  page,
  name,
  evidenceDirectory,
  width,
  height,
  showMcp = false,
) {
  await page.setViewportSize({ width, height });
  await expect(page.getByRole("heading", { name: "MCP", exact: true })).toBeInViewport();
  if (showMcp) {
    await expect(page.locator(".ja-conversation-summary-mcp-server").first()).toBeInViewport();
  }
  const bounds = await page.locator(".ja-conversation-summary-popover").evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom };
  });
  expect(bounds.left).toBeGreaterThanOrEqual(0);
  expect(bounds.top).toBeGreaterThanOrEqual(0);
  expect(bounds.right).toBeLessThanOrEqual(width);
  expect(bounds.bottom).toBeLessThanOrEqual(height);
  await capture(page, name, evidenceDirectory);
}

/** 新建 HTTP fixture 配置供探测回归使用，所有数据只写入当前隔离 App Server。 */
async function createHttpServer(page, name, endpoint) {
  await page.getByRole("button", { name: "新增服务", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("radio", { name: "Streamable HTTP", exact: true }).click();
  await dialog.getByLabel("名称", { exact: true }).fill(name);
  await dialog.getByLabel("服务地址").fill(endpoint);
  await dialog.getByRole("button", { name: "保存服务", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  return page.locator(".ja-mcp-row").filter({ hasText: name });
}

/** 从本机已保存配置只取 Kerminal 的地址用于隔离窗口展示；不读取或复制凭据。 */
async function installedKerminalEndpoint() {
  const source = await readFile(join(homedir(), ".ja", "config.toml"), "utf8");
  const entries = source.split(/^\[\[mcp_servers\]\]\s*$/mu).slice(1);
  const entry = entries.find((candidate) => /^name\s*=\s*"Kerminal"\s*$/mu.test(candidate));
  const endpoint = entry?.match(/^endpoint\s*=\s*"([^"\r\n]+)"\s*$/mu)?.[1];
  if (endpoint === undefined || !/^https?:\/\//u.test(endpoint)) {
    throw new Error("本机已保存的 Kerminal MCP 配置不可用");
  }
  return endpoint;
}

/** 健康探测或目录读取失败后必须离开未检查状态，并显示对应的脱敏错误类别。 */
async function verifyFailedCheck(page, fixture, evidenceDirectory, name, route, expectedError) {
  const row = await createHttpServer(page, name, fixture.urlFor(route));
  await expect(row.locator(".ja-mcp-status")).toHaveText("未检查");
  await expect(row.locator(".ja-mcp-row-details")).toContainText("工具未检查");
  await row.getByRole("button", { name: /测试/ }).click();
  await expect(row.locator(".ja-mcp-status")).toHaveText("不可用", { timeout: 45_000 });
  await expect(row.locator(".ja-mcp-row-details")).toContainText("工具不可用");
  await expect(row.locator(".ja-mcp-error")).toHaveText(expectedError);
  await expect(row).not.toContainText("未检查");
  await capture(page, `${route}-failed`, evidenceDirectory);
  return row;
}

/** 验收 MCP 成功目录与失败投影、开关持久化、编辑、删除和真实 STDIO 连接。 */
async function verify(page, fixture, evidenceDirectory) {
  await page.reload();
  await section(page, "MCP");
  // 重跑只清理本 runner 在隔离配置中创建的命名对象，避免累积失败现场导致选择器歧义。
  for (const name of ["本地连接验收", "本地探测失败验收", "本地目录失败验收", "本地 STDIO 验收"]) {
    const previous = page.locator(".ja-mcp-row").filter({ hasText: name });
    while (await previous.count()) {
      const count = await previous.count();
      await previous
        .first()
        .getByRole("button", { name: /更多操作/ })
        .click();
      await page.getByRole("menuitem", { name: "删除", exact: true }).click();
      await page
        .getByRole("alertdialog")
        .getByRole("button", { name: "删除", exact: true })
        .click();
      await expect(page.getByRole("alertdialog")).not.toBeVisible();
      await expect(previous).toHaveCount(count - 1);
    }
  }
  await capture(page, "01-empty", evidenceDirectory);
  await page.getByRole("button", { name: "新增服务", exact: true }).click();
  let dialog = page.getByRole("dialog");
  await expect(dialog.getByLabel("名称", { exact: true })).toBeFocused();
  await capture(page, "02-local-editor", evidenceDirectory);
  await dialog.getByRole("radio", { name: "Streamable HTTP", exact: true }).click();
  await dialog.getByLabel("名称", { exact: true }).fill("本地连接验收");
  await dialog.getByLabel("服务地址").fill("不是网址");
  await dialog.getByRole("button", { name: "保存服务", exact: true }).click();
  await expect(dialog.getByRole("alert").first()).toBeVisible();
  await expect(dialog.getByLabel("名称", { exact: true })).toHaveValue("本地连接验收");
  await dialog.getByLabel("服务地址").fill(fixture.url);
  await capture(page, "03-http-editor", evidenceDirectory);
  await dialog.getByRole("button", { name: "保存服务", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  const row = page.locator(".ja-mcp-row").filter({ hasText: "本地连接验收" });
  await expect(row.getByRole("switch")).toBeChecked();
  await expect(row.locator(".ja-mcp-status")).toHaveText("未检查");
  await expect(row.locator(".ja-mcp-row-details")).toContainText("工具未检查");
  await row.getByRole("button", { name: /测试/ }).click();
  await expect(row.locator(".ja-mcp-status")).toHaveText("服务已连接", { timeout: 45_000 });
  await expect(row.locator(".ja-mcp-row-details")).toContainText("68 个工具");
  await expect(row.locator(".ja-mcp-tools")).toContainText("settings_echo");
  await expect(row.locator(".ja-mcp-tools .ja-mcp-chip")).toHaveCount(68);
  expect(fixture.calls).toContainEqual({ method: "initialize", pathname: "/mcp" });
  expect(fixture.calls).toContainEqual({ method: "tools/list", pathname: "/mcp" });
  await capture(page, "04-connected-68-tools", evidenceDirectory);
  await verifyFailedCheck(
    page,
    fixture,
    evidenceDirectory,
    "本地探测失败验收",
    "probe-failure",
    "MCP Server 不可用。",
  );
  await verifyFailedCheck(
    page,
    fixture,
    evidenceDirectory,
    "本地目录失败验收",
    "catalog-failure",
    "MCP 工具目录读取失败。",
  );
  expect(fixture.calls).toContainEqual({ method: "initialize", pathname: "/probe-failure" });
  expect(fixture.calls).toContainEqual({ method: "tools/list", pathname: "/catalog-failure" });
  expect(
    fixture.calls.filter(
      (call) => call.method === "tools/list" && call.pathname === "/catalog-failure",
    ),
  ).toHaveLength(2);
  await row.getByRole("switch").click();
  await expect(row.getByRole("switch")).not.toBeChecked();
  await expect(row).not.toContainText("服务已连接");
  await page.reload();
  await section(page, "MCP");
  await expect(row.getByRole("switch")).not.toBeChecked();
  await capture(page, "05-disabled-reloaded", evidenceDirectory);
  await row.getByRole("switch").click();
  await expect(row.getByRole("switch")).toBeChecked();
  await row.getByRole("button", { name: /测试/ }).click();
  await expect(row).toContainText("服务已连接", { timeout: 45000 });
  await theme(page, "深色");
  await capture(page, "06-dark-list", evidenceDirectory);
  await page.getByRole("button", { name: "新增服务", exact: true }).click();
  await capture(page, "07-dark-editor", evidenceDirectory);
  await page.getByRole("dialog").getByRole("button", { name: "高级设置", exact: true }).click();
  await capture(page, "08-dark-advanced", evidenceDirectory);
  await page.keyboard.press("Escape");
  await theme(page, "浅色");
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: 760,
    height: 720,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await capture(page, "09-narrow-list", evidenceDirectory);
  await page.getByRole("button", { name: "新增服务", exact: true }).click();
  await capture(page, "10-narrow-editor", evidenceDirectory);
  await page.keyboard.press("Escape");
  await cdp.send("Emulation.clearDeviceMetricsOverride");
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await row.getByRole("button", { name: /更多操作/ }).click();
  await page.getByRole("menuitem", { name: "编辑", exact: true }).click();
  dialog = page.getByRole("dialog");
  await expect(dialog.getByLabel("服务地址")).toHaveValue(fixture.url);
  await dialog.getByLabel("名称", { exact: true }).fill("本地连接验收 · 已编辑");
  await dialog.getByRole("button", { name: "保存服务", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(row).toContainText("已编辑");
  await row.getByRole("button", { name: /更多操作/ }).click();
  await page.getByRole("menuitem", { name: "删除", exact: true }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "取消", exact: true }).click();
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: /更多操作/ }).click();
  await page.getByRole("menuitem", { name: "删除", exact: true }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "删除", exact: true }).click();
  await expect(row).not.toBeVisible();
  await page.getByRole("button", { name: "新增服务", exact: true }).click();
  dialog = page.getByRole("dialog");
  await dialog.getByLabel("名称", { exact: true }).fill("本地 STDIO 验收");
  await dialog.getByLabel("启动命令").fill(realpathSync(process.execPath));
  await dialog
    .getByLabel("进程参数")
    .fill(join(process.cwd(), "scripts/e2e/fixtures/settings-mcp.mjs"));
  await dialog.getByRole("button", { name: "保存服务", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  const localRow = page.locator(".ja-mcp-row").filter({ hasText: "本地 STDIO 验收" });
  await localRow.getByRole("button", { name: /测试/ }).click();
  await expect(localRow).toContainText("服务已连接", { timeout: 45000 });
  await capture(page, "11-stdio-connected", evidenceDirectory);
  await localRow.getByRole("switch").click();
  await expect(localRow.getByRole("switch")).not.toBeChecked();
  await theme(page, "跟随系统");
  return {
    calls: fixture.calls,
    toolCatalog: {
      items: fixture.tools.length,
      nextCursor: null,
      itemFields: ["name", "description", "inputSchema"],
      allItemsHaveRequiredFields: fixture.tools.every(
        (tool) =>
          typeof tool.name === "string" &&
          typeof tool.description === "string" &&
          tool.inputSchema?.type === "object",
      ),
      renderedCardItems: 68,
      resultSchemaPath: "mcp/list-tools -> parseMethodResult -> SettingsRuntimePort.listMcpTools",
    },
    checks: [
      "create",
      "inline-validation",
      "real-http-probe-and-tool-count",
      "probe-failure-leaves-unchecked-state",
      "catalog-failure-leaves-unchecked-state",
      "real-stdio-probe",
      "disable",
      "reload-persistence",
      "enable",
      "edit",
      "delete-cancel",
      "delete",
      "light",
      "dark",
      "system",
      "narrow",
      "reduced-motion",
    ],
  };
}

/** 独立视觉阶段不伪造配置成功，集成受阻时仍检查真实组件、主题、键盘及窄窗口。 */
async function verifyVisuals(page, evidenceDirectory) {
  await page.reload();
  for (const [mode, label] of [
    ["light", "浅色"],
    ["dark", "深色"],
  ]) {
    await theme(page, label);
    await page.getByRole("button", { name: "新增服务", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByLabel("名称", { exact: true })).toBeFocused();
    await capture(page, `visual-${mode}-stdio`, evidenceDirectory);
    await dialog.getByRole("radio", { name: "Streamable HTTP", exact: true }).click();
    await capture(page, `visual-${mode}-http`, evidenceDirectory);
    await dialog.getByRole("button", { name: "高级设置", exact: true }).click();
    await capture(page, `visual-${mode}-advanced`, evidenceDirectory);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("button", { name: "新增服务", exact: true })).toBeFocused();
  }
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: 760,
    height: 720,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.getByRole("button", { name: "新增服务", exact: true }).click();
  await capture(page, "visual-narrow-reduced", evidenceDirectory);
  await page.keyboard.press("Escape");
  await cdp.send("Emulation.clearDeviceMetricsOverride");
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await theme(page, "跟随系统");
  return {
    checks: [
      "real-webview2-components",
      "focus-return",
      "light",
      "dark",
      "system",
      "narrow",
      "reduced-motion",
    ],
    integration: "NOT_VERIFIED",
  };
}

/** 运行器可由隔离 Tauri 启动流程直接注入真实 Page，报告与截图写入该流程的证据目录。 */
export async function runMcpSettingsAcceptance({ page, evidenceDirectory, visualOnly = false }) {
  if (!page) throw new Error("page is required");
  if (!evidenceDirectory || !isAbsolute(evidenceDirectory)) {
    throw new Error("evidenceDirectory must be absolute");
  }
  await mkdir(evidenceDirectory, { recursive: true });
  const fixture = visualOnly ? undefined : await startMcpFixture();
  const pageErrors = [];
  const onPageError = (error) => pageErrors.push(String(error?.message ?? error));
  page.on("pageerror", onPageError);
  try {
    page.setDefaultTimeout(20_000);
    const result = visualOnly
      ? await verifyVisuals(page, evidenceDirectory)
      : await verify(page, fixture, evidenceDirectory);
    expect(pageErrors).toEqual([]);
    const report = { ...result, pageErrors, status: "PASS" };
    await writeFile(
      join(evidenceDirectory, visualOnly ? "visual-report.json" : "report.json"),
      JSON.stringify(report, null, 2),
    );
    return report;
  } catch (error) {
    await capture(page, "failure", evidenceDirectory).catch(() => undefined);
    await writeFile(
      join(evidenceDirectory, "failure-report.json"),
      JSON.stringify(
        {
          status: "FAIL",
          error: String(error?.message ?? error).slice(0, 2_000),
          pageErrors,
          fixtureCalls: fixture?.calls ?? [],
        },
        null,
        2,
      ),
    );
    console.error("MCP fixture requests", fixture?.calls ?? []);
    console.error(
      await page
        .locator("body")
        .innerText()
        .catch(() => "<body unavailable>"),
    );
    throw error;
  } finally {
    page.off("pageerror", onPageError);
    if (fixture !== undefined) {
      await new Promise((resolvePromise) => fixture.server.close(resolvePromise));
    }
  }
}

/** 空会话会被“新会话”复用；另建一条 Java 持有的会话再重载，才能真实验收 Thread 隔离。 */
async function createMcpHeaderThreads(page) {
  const activeThread = page.locator(
    '[aria-label="最近对话列表"] button[data-thread-id][aria-current="page"]',
  );
  await page.getByRole("button", { name: "新会话", exact: true }).click();
  await expect(activeThread).toBeVisible({ timeout: 30_000 });
  const firstThreadId = await activeThread.getAttribute("data-thread-id");
  if (!firstThreadId) throw new Error("新会话没有生成可见的 Thread 身份");
  const secondThreadId = await page.evaluate(async (threadId) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const history = createHistoryAdapter();
    const sessions = await history.threadList({ workspaceKind: "session", limit: 200 });
    const first = sessions.items.find((thread) => thread.threadId === threadId);
    if (first?.preferences === null || first?.preferences === undefined) {
      throw new Error("isolated session thread preferences are unavailable");
    }
    const created = await history.threadCreate({
      title: "MCP 会话隔离验收 B",
      providerId: first.preferences.providerId,
      modelId: first.preferences.modelId,
      reasoningLevel: first.preferences.reasoningLevel,
      accessMode: first.preferences.accessMode,
      collaborationMode: first.preferences.collaborationMode,
    });
    return created.threadId;
  }, firstThreadId);
  if (secondThreadId === firstThreadId) throw new Error("会话隔离验收必须使用不同 Thread 身份");
  await page.reload({ waitUntil: "domcontentloaded" });
  await page
    .locator(`[aria-label="最近对话列表"] button[data-thread-id="${firstThreadId}"]`)
    .waitFor();
  await page
    .locator(`[aria-label="最近对话列表"] button[data-thread-id="${secondThreadId}"]`)
    .waitFor();
  return { threadIds: [firstThreadId, secondThreadId] };
}

/** 借用隔离项目目录新建可信项目会话，让项目 MCP 通过真实工作区身份进入设置与概览。 */
async function createProjectMcpHeaderThread(page, sourceThreadId, workspaceRoot) {
  if (!workspaceRoot || !isAbsolute(workspaceRoot)) throw new Error("isolated project path is missing");
  const project = await page.evaluate(async ({ cwd, threadId }) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const history = createHistoryAdapter();
    const source = await history.threadRead({ threadId, limit: 1 });
    const threads = await history.threadList({ workspaceKind: "session", limit: 200 });
    const preferences = threads.items.find((item) => item.threadId === threadId)?.preferences;
    if (source.threadId !== threadId || !preferences) throw new Error("source preferences unavailable");
    const workspace = await history.workspaceOpen({ cwd });
    const thread = await history.threadCreate({
      cwd: workspace.root, title: "项目 MCP 概览验收", providerId: preferences.providerId,
      modelId: preferences.modelId, reasoningLevel: preferences.reasoningLevel,
      accessMode: preferences.accessMode, collaborationMode: preferences.collaborationMode,
    });
    return { workspace, thread };
  }, { cwd: workspaceRoot, threadId: sourceThreadId });
  if (project.workspace.kind !== "project" || project.workspace.trust !== "trusted") {
    throw new Error("isolated project workspace is not trusted");
  }
  await page.reload({ waitUntil: "domcontentloaded" });
  const workspaceRow = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: project.workspace.displayName });
  await workspaceRow.click();
  await selectMcpHeaderThread(page, project.thread.threadId);
  return project;
}

/** 通过会话列表切换到精确 Thread，确保 UI 消费事件而非旁路读取原生历史。 */
async function selectMcpHeaderThread(page, threadId) {
  const thread = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await thread.waitFor({ state: "visible", timeout: 30_000 });
  if ((await thread.getAttribute("aria-current")) !== "page") await thread.click();
  await expect(thread).toHaveAttribute("aria-current", "page", { timeout: 30_000 });
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({ timeout: 30_000 });
  // 空会话不挂载 ChatTimeline；留给 Thread 恢复与 Header 的异步效果一个稳定绘制帧。
  await page.waitForTimeout(350);
}

/** 包装共享 native bridge 对象，让 History adapter 与 UI 调用计数经过同一入口。 */
async function installMcpCommandObserver(page) {
  const installed = await page.evaluate(async () => {
    const { defaultNativeBridge } = await import("/src/api/tauri/runtime.ts");
    const internals = globalThis.__TAURI_INTERNALS__;
    if (
      internals === undefined ||
      typeof defaultNativeBridge.invoke !== "function" ||
      internals.__jaMcpE2eCalls !== undefined
    ) {
      return false;
    }
    const original = defaultNativeBridge.invoke;
    const calls = [];
    const wrapped = function (command, args) {
      const result = original.call(this, command, args);
      if (command === "ja_thread_mcp_read") {
        const call = { command, input: args?.input ?? null };
        calls.push(call);
        void Promise.resolve(result).then(
          (value) => {
            call.result = {
              source: value?.source ?? null,
              serviceCount: Array.isArray(value?.servers) ? value.servers.length : null,
            };
          },
          () => {
            call.result = { error: true };
          },
        );
      }
      return result;
    };
    defaultNativeBridge.invoke = wrapped;
    if (defaultNativeBridge.invoke !== wrapped) return false;
    internals.__jaMcpE2eCalls = calls;
    internals.__jaMcpE2eOriginalInvoke = original;
    return true;
  });
  if (!installed)
    throw new Error("isolated WebView2 did not install the shared native bridge observer");
}

/** 执行真实 Thread MCP 顶栏链路并保留关键状态截图与脱敏计数报告。 */
export async function runMcpConversationHeaderAcceptance({ page, evidenceDirectory, projectPath }) {
  if (!page) throw new Error("page is required");
  if (!evidenceDirectory || !isAbsolute(evidenceDirectory)) {
    throw new Error("evidenceDirectory must be absolute");
  }
  await mkdir(evidenceDirectory, { recursive: true });
  const fixture = await startMcpFixture({ slowToolListDelayMs: 2_500 });
  const pageErrors = [];
  const onPageError = (error) => pageErrors.push(String(error?.message ?? error));
  page.on("pageerror", onPageError);
  try {
    page.setDefaultTimeout(20_000);
    await section(page, "MCP");
    await page.getByRole("tab", { name: "全局", exact: true }).click();
    const realKerminalEndpoint = await installedKerminalEndpoint();
    await createHttpServer(page, "Kerminal", realKerminalEndpoint);
    await page.getByRole("button", { name: "返回应用", exact: true }).click();
    const threads = await createMcpHeaderThreads(page);
    const project = await createProjectMcpHeaderThread(page, threads.threadIds[0], projectPath);
    await installMcpCommandObserver(page);

    await section(page, "MCP");
    await expect(page.getByRole("tab", { name: "当前项目" })).toBeVisible();
    await page.getByRole("tab", { name: "当前项目" }).click();
    const projectSettingsRow = await createHttpServer(page, "项目测试服务", fixture.url);
    await expect(projectSettingsRow).toBeVisible();
    await page.getByRole("button", { name: "返回应用", exact: true }).click();

    const trigger = page.getByRole("button", { name: "打开上下文信息", exact: true });
    await trigger.click();
    const overview = page.getByRole("dialog", { name: "会话概览", exact: true });
    const list = page.getByRole("list", { name: "当前会话的 MCP 服务", exact: true });
    await expect(list).toBeVisible();
    const kerminal = list.locator(".ja-conversation-summary-mcp-server").filter({ hasText: "Kerminal" });
    const projectServer = list.locator(".ja-conversation-summary-mcp-server").filter({ hasText: "项目测试服务" });
    await expect(kerminal).toContainText("全局");
    await expect(projectServer).toContainText("当前项目");
    await expect(projectServer).toContainText("未检查");
    await expect(overview).not.toContainText("个工具");
    await expect(overview.getByRole("button", { name: /检查连接/ })).toHaveCount(0);
    expect(fixture.calls).toEqual([]);
    await capture(page, "01-project-list-light", evidenceDirectory);
    await captureResponsivePopover(page, "02-project-list-narrow-light", evidenceDirectory, 420, 720, true);
    await page.setViewportSize({ width: 1280, height: 820 });
    await page.keyboard.press("Escape");
    await expect(trigger).toBeFocused();
    const hiddenRpcBaseline = await page.evaluate(() => globalThis.__TAURI_INTERNALS__.__jaMcpE2eCalls.length);
    await page.waitForTimeout(750);
    const hiddenRpcAfterWait = await page.evaluate(() => globalThis.__TAURI_INTERNALS__.__jaMcpE2eCalls.length);
    expect(hiddenRpcAfterWait).toBe(hiddenRpcBaseline);
    expect(fixture.calls).toEqual([]);

    await trigger.click();
    await page.getByRole("button", { name: "管理 MCP", exact: true }).click();
    await expect(page.locator(".ja-settings")).toBeVisible();
    await page.getByRole("tab", { name: "当前项目" }).click();
    await projectSettingsRow.getByRole("switch").click();
    await expect(projectSettingsRow.getByRole("switch")).not.toBeChecked();
    await page.getByRole("button", { name: "返回应用", exact: true }).click();
    await trigger.click();
    await expect(projectServer).toContainText("已停用");
    await capture(page, "03-project-disabled", evidenceDirectory);
    await page.keyboard.press("Escape");

    await page.locator('[aria-label="项目列表"] button[data-scope-kind="general"]').click();
    await selectMcpHeaderThread(page, threads.threadIds[0]);
    await trigger.click();
    await expect(list).toBeVisible();
    await expect(kerminal).toBeVisible();
    await expect(list).not.toContainText("项目测试服务");
    await capture(page, "04-general-thread", evidenceDirectory);
    await page.keyboard.press("Escape");

    await section(page, "MCP");
    await theme(page, "深色");
    await page.getByRole("button", { name: "返回应用", exact: true }).click();
    await trigger.click();
    await capture(page, "05-general-dark", evidenceDirectory);
    await captureResponsivePopover(page, "06-general-narrow-dark", evidenceDirectory, 420, 720, true);
    await page.keyboard.press("Escape");
    const mcpRpcCalls = await page.evaluate(() => globalThis.__TAURI_INTERNALS__.__jaMcpE2eCalls);
    expect(mcpRpcCalls.every((call) => call.command === "ja_thread_mcp_read")).toBe(true);
    expect(pageErrors).toEqual([]);
    const report = {
      status: "PASS", threadIds: [...threads.threadIds, project.thread.threadId],
      kerminalSource: "saved local configuration", projectService: "explicit test fixture",
      mcpRpcCalls, hiddenReadDelta: hiddenRpcAfterWait - hiddenRpcBaseline,
      serverIoCount: fixture.calls.length, pageErrors,
      checks: ["project-and-global-list", "real-kerminal-name", "disabled", "thread-switch",
        "read-only-overview", "hidden-zero-probe", "manage-settings", "keyboard-focus", "light-dark-narrow"],
    };
    await writeFile(join(evidenceDirectory, "header-report.json"), JSON.stringify(report, null, 2));
    return report;  } catch (error) {
    await capture(page, "header-failure", evidenceDirectory).catch(() => undefined);
    await writeFile(
      join(evidenceDirectory, "header-failure-report.json"),
      JSON.stringify(
        {
          status: "FAIL",
          error: String(error?.message ?? error).slice(0, 2_000),
          pageErrors,
          fixtureCalls: fixture.calls,
          mcpRpcCalls: await page
            .evaluate(() => globalThis.__TAURI_INTERNALS__?.__jaMcpE2eCalls ?? [])
            .catch(() => []),
        },
        null,
        2,
      ),
    );
    throw error;
  } finally {
    page.off("pageerror", onPageError);
    await page
      .evaluate(() => {
        const internals = globalThis.__TAURI_INTERNALS__;
        if (internals?.__jaMcpE2eOriginalInvoke === undefined) return;
        internals.invoke = internals.__jaMcpE2eOriginalInvoke;
        delete internals.__jaMcpE2eOriginalInvoke;
        delete internals.__jaMcpE2eCalls;
      })
      .catch(() => undefined);
    await new Promise((resolvePromise) => fixture.server.close(resolvePromise));
  }
}

/** 直接调用模式只允许附着到显式隔离的 loopback CDP，不接受用户运行中的桌面实例。 */
async function runStandalone() {
  const endpoint = process.env.JA_E2E_MCP_CDP_ENDPOINT;
  const evidenceDirectory = process.env.JA_E2E_MCP_ARTIFACT_DIR;
  if (
    process.platform !== "win32" ||
    process.env.JA_E2E_MCP_ISOLATED !== "1" ||
    !/^http:\/\/127\.0\.0\.1:\d+$/.test(endpoint ?? "") ||
    !evidenceDirectory ||
    !isAbsolute(evidenceDirectory)
  ) {
    throw new Error("必须指定隔离 Windows CDP 地址和绝对证据目录");
  }
  const browser = await chromium.connectOverCDP(endpoint);
  try {
    const page = browser.contexts().flatMap((context) => context.pages())[0];
    if (page === undefined) throw new Error("isolated WebView2 page is unavailable");
    const visualOnly = process.env.JA_E2E_MCP_VISUAL_ONLY === "1";
    const report = await runMcpSettingsAcceptance({ page, evidenceDirectory, visualOnly });
    process.stdout.write(
      `${visualOnly ? "JA_MCP_VISUAL_PASS" : "JA_MCP_SETTINGS_PASS"} ${JSON.stringify(report)}\n`,
    );
  } finally {
    await browser.close();
  }
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await runStandalone();
}
