// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createServer } from "node:http";
import { mkdir } from "node:fs/promises";
import { isAbsolute, join } from "node:path";
import { chromium, expect } from "@playwright/test";

const endpoint = process.env.JA_E2E_SETTINGS_CDP_ENDPOINT;
const artifacts = process.env.JA_E2E_SETTINGS_ARTIFACT_DIR;
const restart = process.env.JA_E2E_SETTINGS_PHASE === "restart";

/** 原生生命周期测试只能操作显式隔离的回环实例，不能连接用户主窗口。 */
function validateEnvironment() {
  if (
    process.platform !== "win32" ||
    process.env.JA_E2E_SETTINGS_ISOLATED !== "1" ||
    !/^http:\/\/(127\.0\.0\.1|localhost):\d+\/?$/.test(endpoint ?? "") ||
    !artifacts ||
    !isAbsolute(artifacts)
  )
    throw new Error("需要隔离 Windows 实例、回环 CDP 地址及绝对证据目录");
}

/** 用本地 Anthropic fixture 验证真实发送链路，不触发外部模型或计费请求。 */
async function startProvider() {
  let sequence = 0;
  const server = createServer(async (request, response) => {
    let raw = "";
    for await (const chunk of request) {
      raw += chunk;
      if (raw.length > 2_000_000) {
        response.writeHead(413).end();
        return;
      }
    }
    const body = JSON.parse(raw);
    sequence += 1;
    const text = `PREFERENCES_REPLY_${sequence}\n\n\`\`\`javascript\nconst size = 16;\n\`\`\``;
    const message = {
      id: `msg_preferences_${sequence}`,
      type: "message",
      role: "assistant",
      model: body.model,
      content: [{ type: "text", text }],
      stop_reason: "end_turn",
      stop_sequence: null,
      usage: { input_tokens: 20, output_tokens: 20 },
    };
    if (body.stream !== true) {
      response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(message));
      return;
    }
    const events = [
      ["message_start", { message: { ...message, content: [], stop_reason: null } }],
      ["content_block_start", { index: 0, content_block: { type: "text", text: "" } }],
      ["content_block_delta", { index: 0, delta: { type: "text_delta", text } }],
      ["content_block_stop", { index: 0 }],
      [
        "message_delta",
        { delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 20 } },
      ],
      ["message_stop", {}],
    ];
    response.writeHead(200, { "content-type": "text/event-stream" });
    response.end(
      events
        .map(([type, value]) => `event: ${type}\ndata: ${JSON.stringify({ type, ...value })}\n\n`)
        .join(""),
    );
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

/** 通过用户可见入口打开设置；首次模型缺失时沿用真实强制设置页。 */
async function openSettings(page, section) {
  const settings = page.locator(".ja-settings");
  if (!(await settings.isVisible())) {
    const reveal = page.getByRole("button", { name: "显示侧边栏", exact: true });
    if (await reveal.isVisible()) await reveal.click();
    await page.getByRole("button", { name: "设置", exact: true }).click();
  }
  await expect(settings).toBeVisible({ timeout: 60000 });
  await expect(settings).not.toHaveAttribute("inert", "", { timeout: 60000 });
  await settings.getByRole("tab", { name: section, exact: true }).click();
  return settings;
}

/** 所有设置选择都经过真实 Radix 控件，保存与错误反馈不能被直接改 store 绕过。 */
async function select(page, label, option) {
  const control = page.getByRole("combobox", { name: label, exact: true });
  await expect(control).toBeEnabled();
  await control.click();
  await page.getByRole("option", { name: option, exact: true }).click();
  await expect(control).toContainText(option);
}

/** 使用真实全局样式截图，并等待有限过渡完成。 */
async function capture(page, name) {
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
  await page.screenshot({ path: join(artifacts, `${name}.png`), animations: "disabled" });
}

/** 使用隔离配置创建本地模型，准备真实 Composer；不调用“验证模型”付费入口。 */
async function configureProvider(page, url) {
  const settings = await openSettings(page, "模型");
  const editing =
    (await settings.getByRole("button", { name: "编辑供应商", exact: true }).count()) > 0;
  const action = editing ? "编辑供应商" : "新增供应商";
  await settings.getByRole("button", { name: action, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: action, exact: true });
  await dialog.getByLabel("供应商名称").fill("Settings fixture");
  await select(page, "API 规范", "Anthropic Messages");
  await dialog.getByLabel("Base URL").fill(url);
  await dialog.getByLabel("API key / token").fill("isolated-no-billing-token");
  await dialog.getByLabel("上游模型标识").fill("settings-fixture");
  await dialog
    .getByRole("button", { name: editing ? "保存更改" : "保存供应商", exact: true })
    .click();
  await expect(dialog).not.toBeVisible();
  const returnToApp = settings.getByRole("button", { name: "返回应用", exact: true });
  if (await returnToApp.isVisible()) await returnToApp.click();
  await expect(page.getByRole("textbox", { name: "消息", exact: true })).toBeEnabled({
    timeout: 60000,
  });
}

/** 以进程正常关闭作为退出完成条件，不杀进程来伪造资源清理成功。 */
async function closeApplication(page) {
  const closed = page.waitForEvent("close", { timeout: 35000 });
  await page
    .getByRole("button", { name: "关闭", exact: true })
    .click()
    .catch((error) => {
      if (!page.isClosed()) throw error;
    });
  await closed;
}

/** 验证快捷键、真实字号、权限来源与关闭偏好，最后正常退出并供第二次冷启动回读。 */
async function verify(page, provider) {
  await page.reload();
  await expect(page.locator(".ja-settings, .ja-composer textarea").first()).toBeVisible({
    timeout: 60000,
  });
  await openSettings(page, "通用");
  await expect(page.getByRole("combobox", { name: "关闭窗口时" })).toContainText("留在后台");
  await select(page, "发送快捷键", "Enter 发送");
  await capture(page, "01-general-default");
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "关闭窗口时" })).toBeVisible();

  await configureProvider(page, provider.url);
  await page.getByRole("button", { name: "新会话", exact: true }).click();
  const composer = page.getByRole("textbox", { name: "消息", exact: true });
  await composer.fill("Preference first");
  await composer.press("Shift+Enter");
  await expect(composer).toHaveValue("Preference first\n");
  await composer.press("Enter");
  await expect(composer).toHaveValue("");
  await expect(
    page
      .getByRole("article", { name: "最终答复", exact: true })
      .filter({ hasText: "PREFERENCES_REPLY" })
      .first(),
  ).toBeVisible({ timeout: 30000 });

  await openSettings(page, "通用");
  await select(page, "发送快捷键", "Ctrl + Enter 发送");
  await capture(page, "02-general-modifier");
  await page.getByRole("button", { name: "返回应用", exact: true }).click();
  await composer.fill("Preference second");
  await composer.press("Enter");
  await expect(composer).toHaveValue("Preference second\n");
  await composer.press("Control+Enter");
  await expect(composer).toHaveValue("");
  await expect(
    page
      .getByRole("article", { name: "最终答复", exact: true })
      .filter({ hasText: "PREFERENCES_REPLY" }),
  ).toHaveCount(2, { timeout: 30000 });

  await openSettings(page, "外观");
  await select(page, "界面字号", "大");
  await select(page, "代码与终端字号", "16px");
  await expect
    .poll(() =>
      page.evaluate(
        () => globalThis.getComputedStyle(globalThis.document.documentElement).fontSize,
      ),
    )
    .toBe("18px");
  await capture(page, "03-appearance-large");
  await page.getByRole("button", { name: "返回应用", exact: true }).click();
  await capture(page, "04-conversation-large-code");
  const code = page.locator(".ja-markdown__code-block pre code").first();
  await expect(code).toBeVisible();
  await expect
    .poll(() => code.evaluate((element) => globalThis.getComputedStyle(element).fontSize))
    .toBe("16px");

  await openSettings(page, "执行确认");
  const note = page.getByRole("note", { name: "执行确认生效范围" });
  await expect(note).toContainText("当前会话：全部执行（会话选择）");
  await page.getByText("需要确认", { exact: true }).click();
  await expect(page.getByRole("radio", { name: /需要确认/ })).toBeChecked();
  await expect(note).toContainText("当前会话：全部执行（会话选择）");
  await capture(page, "05-execution-scope");

  await page.reload();
  await openSettings(page, "通用");
  await expect(page.getByRole("combobox", { name: "发送快捷键" })).toContainText("Ctrl + Enter");
  await expect
    .poll(() =>
      page.evaluate(
        () => globalThis.getComputedStyle(globalThis.document.documentElement).fontSize,
      ),
    )
    .toBe("18px");
  await select(page, "关闭窗口时", "退出 Ja");
  await page.setViewportSize({ width: 720, height: 640 });
  await capture(page, "06-general-narrow-large");
  await openSettings(page, "外观");
  await expect(page.getByRole("combobox", { name: "代码与终端字号" })).toContainText("16px");
  await expect
    .poll(() =>
      page
        .locator(".ja-settings")
        .evaluate((element) => element.scrollWidth <= element.clientWidth),
    )
    .toBe(true);
  await capture(page, "07-appearance-narrow-large");
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await capture(page, "08-appearance-dark");
  await closeApplication(page);
}

/** 冷启动验证原生关闭模式和UI偏好均恢复，退出仍经过相同的原生握手。 */
async function verifyRestart(page) {
  await page.reload();
  await expect(page.locator(".ja-settings, .ja-composer textarea").first()).toBeVisible({
    timeout: 60000,
  });
  await openSettings(page, "通用");
  await expect(page.getByRole("combobox", { name: "关闭窗口时" })).toContainText("退出 Ja");
  await expect(page.getByRole("combobox", { name: "发送快捷键" })).toContainText("Ctrl + Enter");
  await capture(page, "09-cold-restart");
  await verifyTerminalFont(page);
  await closeApplication(page);
}

/** 以真实 PTY 输出验证 xterm 字号原位变化与 scrollback 保留，避免只验证外层 CSS。 */
async function verifyTerminalFont(page) {
  await page.getByRole("button", { name: "返回应用", exact: true }).click();
  const showWorkbench = page.getByRole("button", { name: "显示工作区面板", exact: true });
  if (await showWorkbench.isVisible()) await showWorkbench.click();
  await page.getByRole("button", { name: /^终端/ }).click();
  const rows = page.locator(".xterm-rows");
  await expect(rows).toBeVisible({ timeout: 30000 });
  await expect
    .poll(() => rows.evaluate((element) => globalThis.getComputedStyle(element).fontSize))
    .toBe("16px");
  await page.locator(".xterm-helper-textarea").focus();
  await page.keyboard.type("Write-Output SETTINGS_FONT_MARKER");
  await page.keyboard.press("Enter");
  await expect(rows).toContainText("SETTINGS_FONT_MARKER");
  await openSettings(page, "外观");
  await select(page, "代码与终端字号", "14px");
  await page.getByRole("button", { name: "返回应用", exact: true }).click();
  await expect(rows).toBeVisible();
  await expect
    .poll(() => rows.evaluate((element) => globalThis.getComputedStyle(element).fontSize))
    .toBe("14px");
  await expect(rows).toContainText("SETTINGS_FONT_MARKER");
  await capture(page, "10-terminal-font-roundtrip");
  await openSettings(page, "外观");
  await select(page, "代码与终端字号", "16px");
}

/** 测试仅保留截图证据；fixture server与CDP transport在失败时也立即释放。 */
async function main() {
  validateEnvironment();
  await mkdir(artifacts, { recursive: true });
  const provider = restart ? undefined : await startProvider();
  const browser = await chromium.connectOverCDP(endpoint);
  try {
    const page = browser.contexts().flatMap((context) => context.pages())[0];
    if (!page) throw new Error("没有隔离 WebView2 页面");
    await page.setViewportSize({ width: 1280, height: 820 });
    await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
    if (restart) await verifyRestart(page);
    else await verify(page, provider);
    process.stdout.write(
      `JA_SETTINGS_PREFERENCES_OK phase=${restart ? "restart" : "controls"} evidence=${artifacts}\n`,
    );
  } finally {
    await browser.close();
    provider?.server.closeAllConnections();
    if (provider) await new Promise((resolve) => provider.server.close(resolve));
  }
}

await main();
