// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createServer } from "node:http";
import { mkdir, writeFile } from "node:fs/promises";
import { realpathSync } from "node:fs";
import { isAbsolute, join } from "node:path";
import { chromium, expect } from "@playwright/test";

const endpoint = process.env.JA_E2E_MCP_CDP_ENDPOINT;
const root = process.env.JA_E2E_MCP_ARTIFACT_DIR;
if (
  process.platform !== "win32" ||
  process.env.JA_E2E_MCP_ISOLATED !== "1" ||
  !/^http:\/\/127\.0\.0\.1:\d+$/.test(endpoint ?? "") ||
  !root ||
  !isAbsolute(root)
) {
  throw new Error("必须指定隔离 Windows CDP 地址和绝对证据目录");
}

/** 本地无认证 MCP fixture 走真实 Java SDK，禁止向外部服务器或计费能力发请求。 */
async function startMcp() {
  const calls = [];
  const server = createServer(async (request, response) => {
    if (request.method !== "POST") {
      response.writeHead(405).end();
      return;
    }
    let raw = "";
    for await (const chunk of request) raw += chunk;
    const frame = JSON.parse(raw);
    calls.push(frame.method);
    if (frame.id === undefined) {
      response.writeHead(202).end();
      return;
    }
    const result =
      frame.method === "initialize"
        ? {
            protocolVersion: "2025-06-18",
            capabilities: { tools: {} },
            serverInfo: { name: "ja-settings-fixture", version: "1" },
          }
        : frame.method === "tools/list"
          ? {
              tools: [
                {
                  name: "settings_echo",
                  description: "本地连接验收",
                  inputSchema: { type: "object", properties: {} },
                },
              ],
            }
          : {};
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ jsonrpc: "2.0", id: frame.id, result }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { server, calls, url: `http://127.0.0.1:${server.address().port}/mcp` };
}

/** 只通过真实设置入口导航，保持 App Server 配置与组件状态的完整调用链。 */
async function section(page, name) {
  if (!(await page.locator(".ja-settings").isVisible())) {
    const reveal = page.getByRole("button", { name: "显示侧边栏", exact: true });
    if (await reveal.isVisible()) await reveal.click();
    await page.getByRole("button", { name: "设置", exact: true }).click();
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
  const overflow = await page.evaluate(
    () => globalThis.document.documentElement.scrollWidth > globalThis.innerWidth + 1,
  );
  expect(overflow).toBe(false);
  await page.screenshot({ path: join(root, `${name}.png`), animations: "disabled" });
}

/** 验收开关保存、重载回读、真实连接、编辑、删除和取消，所有结果由可见状态断言。 */
async function verify(page, fixture) {
  await page.reload();
  await section(page, "MCP");
  // 重跑只清理本 runner 在隔离配置中创建的命名对象，避免累积失败现场导致选择器歧义。
  for (const name of ["本地连接验收", "本地 STDIO 验收"]) {
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
  await capture(page, "01-empty");
  await page.getByRole("button", { name: "新增 Server", exact: true }).click();
  let dialog = page.getByRole("dialog");
  await expect(dialog.getByLabel("名称", { exact: true })).toBeFocused();
  await capture(page, "02-local-editor");
  await dialog.getByRole("radio", { name: "Streamable HTTP", exact: true }).click();
  await dialog.getByLabel("名称", { exact: true }).fill("本地连接验收");
  await dialog.getByLabel("服务地址").fill("不是网址");
  await dialog.getByRole("button", { name: "保存 Server", exact: true }).click();
  await expect(dialog.getByRole("alert").first()).toBeVisible();
  await expect(dialog.getByLabel("名称", { exact: true })).toHaveValue("本地连接验收");
  await dialog.getByLabel("服务地址").fill(fixture.url);
  await capture(page, "03-http-editor");
  await dialog.getByRole("button", { name: "保存 Server", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  const row = page.locator(".ja-mcp-row").filter({ hasText: "本地连接验收" });
  await expect(row.getByRole("switch")).toBeChecked();
  await row.getByRole("button", { name: /测试/ }).click();
  await expect(row).toContainText("已连接", { timeout: 45000 });
  expect(fixture.calls).toContain("initialize");
  expect(fixture.calls).toContain("tools/list");
  await capture(page, "04-connected");
  await row.getByRole("switch").click();
  await expect(row.getByRole("switch")).not.toBeChecked();
  await expect(row).not.toContainText("已连接");
  await page.reload();
  await section(page, "MCP");
  await expect(row.getByRole("switch")).not.toBeChecked();
  await capture(page, "05-disabled-reloaded");
  await row.getByRole("switch").click();
  await expect(row.getByRole("switch")).toBeChecked();
  await row.getByRole("button", { name: /测试/ }).click();
  await expect(row).toContainText("已连接", { timeout: 45000 });
  await theme(page, "深色");
  await capture(page, "06-dark-list");
  await page.getByRole("button", { name: "新增 Server", exact: true }).click();
  await capture(page, "07-dark-editor");
  await page.getByRole("dialog").getByRole("button", { name: "高级设置", exact: true }).click();
  await capture(page, "08-dark-advanced");
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
  await capture(page, "09-narrow-list");
  await page.getByRole("button", { name: "新增 Server", exact: true }).click();
  await capture(page, "10-narrow-editor");
  await page.keyboard.press("Escape");
  await cdp.send("Emulation.clearDeviceMetricsOverride");
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await row.getByRole("button", { name: /更多操作/ }).click();
  await page.getByRole("menuitem", { name: "编辑", exact: true }).click();
  dialog = page.getByRole("dialog");
  await expect(dialog.getByLabel("服务地址")).toHaveValue(fixture.url);
  await dialog.getByLabel("名称", { exact: true }).fill("本地连接验收 · 已编辑");
  await dialog.getByRole("button", { name: "保存 Server", exact: true }).click();
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
  await page.getByRole("button", { name: "新增 Server", exact: true }).click();
  dialog = page.getByRole("dialog");
  await dialog.getByLabel("名称", { exact: true }).fill("本地 STDIO 验收");
  await dialog.getByLabel("启动命令").fill(realpathSync(process.execPath));
  await dialog
    .getByLabel("进程参数")
    .fill(join(process.cwd(), "scripts/e2e/fixtures/settings-mcp.mjs"));
  await dialog.getByRole("button", { name: "保存 Server", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  const localRow = page.locator(".ja-mcp-row").filter({ hasText: "本地 STDIO 验收" });
  await localRow.getByRole("button", { name: /测试/ }).click();
  await expect(localRow).toContainText("已连接", { timeout: 45000 });
  await capture(page, "11-stdio-connected");
  await localRow.getByRole("switch").click();
  await expect(localRow.getByRole("switch")).not.toBeChecked();
  await theme(page, "跟随系统");
  return {
    calls: fixture.calls,
    checks: [
      "create",
      "inline-validation",
      "real-http-probe",
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
async function verifyVisuals(page) {
  await page.reload();
  for (const [mode, label] of [
    ["light", "浅色"],
    ["dark", "深色"],
  ]) {
    await theme(page, label);
    await page.getByRole("button", { name: "新增 Server", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByLabel("名称", { exact: true })).toBeFocused();
    await capture(page, `visual-${mode}-stdio`);
    await dialog.getByRole("radio", { name: "Streamable HTTP", exact: true }).click();
    await capture(page, `visual-${mode}-http`);
    await dialog.getByRole("button", { name: "高级设置", exact: true }).click();
    await capture(page, `visual-${mode}-advanced`);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("button", { name: "新增 Server", exact: true })).toBeFocused();
  }
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Emulation.setDeviceMetricsOverride", {
    width: 760,
    height: 720,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.getByRole("button", { name: "新增 Server", exact: true }).click();
  await capture(page, "visual-narrow-reduced");
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

await mkdir(root, { recursive: true });
const fixture = await startMcp();
const browser = await chromium.connectOverCDP(endpoint);
const page = browser.contexts()[0].pages()[0];
page.setDefaultTimeout(20000);
const errors = [];
page.on("pageerror", (error) => errors.push(error.message));
try {
  const visualOnly = process.env.JA_E2E_MCP_VISUAL_ONLY === "1";
  const result = visualOnly ? await verifyVisuals(page) : await verify(page, fixture);
  expect(errors).toEqual([]);
  await writeFile(
    join(root, visualOnly ? "visual-report.json" : "report.json"),
    JSON.stringify({ ...result, errors, status: "PASS" }, null, 2),
  );
  console.log(visualOnly ? "JA_MCP_VISUAL_PASS" : "JA_MCP_SETTINGS_PASS", JSON.stringify(result));
} catch (error) {
  await capture(page, "failure").catch(() => undefined);
  console.error("MCP fixture methods", fixture.calls);
  console.error(await page.locator("body").innerText());
  throw error;
} finally {
  await browser.close();
  await new Promise((resolve) => fixture.server.close(resolve));
}
