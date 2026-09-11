// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { mkdir } from "node:fs/promises";
import { isAbsolute, join } from "node:path";
import { chromium } from "@playwright/test";

const endpoint = process.env.JA_E2E_PROVIDER_SETTINGS_CDP_ENDPOINT?.trim();
const evidenceDirectory = process.env.JA_E2E_PROVIDER_SETTINGS_ARTIFACT_DIR?.trim();

/** 该 runner 只允许连接隔离 WebView2 的回环 CDP，避免误操作用户当前桌面实例。 */
function requireIsolatedEndpoint() {
  if (process.platform !== "win32") throw new Error("Provider 设置验收仅支持 Windows 11");
  if (process.env.JA_E2E_PROVIDER_SETTINGS_ISOLATED !== "1") {
    throw new Error("必须设置 JA_E2E_PROVIDER_SETTINGS_ISOLATED=1");
  }
  if (endpoint === undefined || !/^https?:\/\/(127\.0\.0\.1|localhost):\d+\/?$/.test(endpoint)) {
    throw new Error("JA_E2E_PROVIDER_SETTINGS_CDP_ENDPOINT 必须是 loopback CDP endpoint");
  }
  if (evidenceDirectory === undefined || !isAbsolute(evidenceDirectory)) {
    throw new Error("JA_E2E_PROVIDER_SETTINGS_ARTIFACT_DIR 必须是绝对目录");
  }
}

/** 统一保存有意义的状态截图，路径不进入应用或 Provider 请求。 */
async function capture(page, name) {
  // 等待有限的主题/交互过渡完成，避免把中间颜色当成稳定视觉验收。
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
  await page.screenshot({
    path: join(evidenceDirectory, `${name}.png`),
    fullPage: false,
    animations: "disabled",
  });
}

/** 在真实 Settings 页面打开供应商编辑 sheet，并确认焦点落在真实表单。 */
async function openProviderSheet(page, actionName) {
  const settings = page.getByRole("region", { name: "设置页面", exact: true });
  if ((await settings.count()) === 0) {
    const sidebar = page.getByRole("button", { name: "显示侧边栏", exact: true });
    if (await sidebar.isVisible()) await sidebar.click();
    const openSettings = page.getByRole("button", { name: "设置", exact: true });
    await openSettings.waitFor({ state: "visible" });
    await openSettings.click();
  }
  await settings.waitFor({ state: "visible" });
  await settings.getByRole("tab", { name: "模型", exact: true }).click();
  await settings.getByRole("button", { name: actionName, exact: true }).click();
  const dialog = page.getByRole("dialog", {
    name: actionName === "新增供应商" ? "新增供应商" : "编辑供应商",
    exact: true,
  });
  await dialog.waitFor({ state: "visible" });
  await dialog.getByLabel(actionName === "新增供应商" ? "供应商名称" : "供应商名称").focus();
  return { settings, dialog };
}

/** 读取统一 sheet 中的模型行，避免通过实现细节猜测 ID 或顺序。 */
function modelRows(dialog) {
  return dialog.locator(".ja-provider-model-row");
}

/** 填写模型行的用户可见字段，并允许显式覆盖推荐预算以证明推荐不是强制值。 */
async function fillModel(row, displayName, upstream, context) {
  await row.getByLabel("显示名称").fill(displayName);
  await row.getByLabel("上游模型标识").fill(upstream);
  await row.getByLabel("上下文 Tokens").fill(String(context));
  await row.getByLabel("最大输出 Tokens").fill("8192");
}

/** 运行供应商配置的真实闭环：保存、校验失败恢复、重开回读及 Composer 选择。 */
async function runAcceptance(page) {
  const { settings, dialog } = await openProviderSheet(page, "新增供应商");
  await capture(page, "01-new-provider-light");
  await dialog.getByLabel("供应商名称").fill(`E2E Isolated Gateway ${Date.now()}`);
  await dialog.getByLabel("Base URL").fill("https://gateway.example.test/v1");
  await dialog.getByLabel("API key / token").fill("isolated-test-secret");
  const rows = modelRows(dialog);
  if ((await rows.count()) !== 1) throw new Error("新增供应商应默认包含一个模型行");
  await fillModel(rows.nth(0), "E2E Primary", "e2e-primary", 777777);
  await dialog.getByRole("button", { name: "添加模型", exact: true }).click();
  if ((await rows.count()) !== 2) throw new Error("添加模型后应包含两个模型行");
  await fillModel(rows.nth(1), "E2E Secondary", "e2e-primary", 256000);
  await dialog.getByRole("button", { name: "保存供应商", exact: true }).click();
  const duplicateAlert = dialog.getByRole("alert");
  await duplicateAlert.waitFor({ state: "visible" });
  if (!(await duplicateAlert.innerText()).includes("重复")) {
    throw new Error(`重复模型未被拒绝: ${await duplicateAlert.innerText()}`);
  }
  await rows.nth(1).getByLabel("上游模型标识").fill("e2e-secondary");
  await rows.nth(1).getByLabel("上下文 Tokens").fill("4096");
  await rows.nth(1).getByLabel("最大输出 Tokens").fill("8192");
  await dialog.getByRole("button", { name: "保存供应商", exact: true }).click();
  const budgetAlert = dialog.getByRole("alert");
  await budgetAlert.waitFor({ state: "visible" });
  if (!(await budgetAlert.innerText()).includes("最大输出预算无效")) {
    throw new Error(`非法预算未被拒绝: ${await budgetAlert.innerText()}`);
  }
  await rows.nth(1).getByLabel("上下文 Tokens").fill("256000");
  await dialog.getByRole("button", { name: "保存供应商", exact: true }).click();
  await dialog.waitFor({ state: "hidden" });
  await capture(page, "02-duplicate-or-budget-recovery");
  await openProviderSheet(page, "编辑供应商");
  const editDialog = page.getByRole("dialog", { name: "编辑供应商", exact: true });
  const savedRows = modelRows(editDialog);
  await savedRows
    .nth(0)
    .getByLabel("上游模型标识")
    .inputValue()
    .then((value) => {
      if (value !== "e2e-primary") throw new Error(`模型回读失败: ${value}`);
    });
  await savedRows
    .nth(0)
    .getByLabel("上下文 Tokens")
    .inputValue()
    .then((value) => {
      if (value !== "777777") throw new Error(`自定义上下文预算未保留: ${value}`);
    });
  if ((await savedRows.count()) !== 2) throw new Error("保存后模型数量未保留");
  await capture(page, "03-edit-roundtrip-light");
  await editDialog.getByRole("button", { name: "取消", exact: true }).click();

  await settings.getByRole("button", { name: "返回应用", exact: true }).click();
  const composerModel = page.locator(".ja-composer__model-trigger");
  await composerModel.waitFor({ state: "visible" });
  await composerModel.focus();
  await page.keyboard.press("Space");
  await page.locator(".ja-composer__selection-menu").waitFor({ state: "visible" });
  const secondary = page.getByRole("menuitemradio", { name: /e2e-secondary/ }).last();
  await secondary.waitFor({ state: "visible" });
  await capture(page, "04-composer-model-menu-narrow");
  await secondary.click();
  if (!(await composerModel.innerText()).includes("e2e-secondary"))
    throw new Error("模型选择未生效");
}

/** CDP 连接关闭只释放 Playwright transport，不触碰 Tauri/Java/用户进程生命周期。 */
async function main() {
  requireIsolatedEndpoint();
  await mkdir(evidenceDirectory, { recursive: true });
  const browser = await chromium.connectOverCDP(endpoint);
  try {
    const pages = browser.contexts().flatMap((context) => context.pages());
    const page = pages.find((candidate) => !candidate.isClosed());
    if (page === undefined) throw new Error("隔离 WebView2 没有可用页面");
    await page.reload();
    await page
      .locator(".ja-settings, .ja-composer__model-trigger:not([disabled])")
      .first()
      .waitFor({ state: "visible" });
    await page.setViewportSize({ width: 720, height: 820 });
    await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
    await runAcceptance(page);
    await page.setViewportSize({ width: 1280, height: 820 });
    await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
    const { dialog } = await openProviderSheet(page, "编辑供应商");
    await capture(page, "05-provider-dark-desktop");
    await page.setViewportSize({ width: 1280, height: 1000 });
    await dialog
      .locator(".ja-provider-models-section")
      .evaluate((element) => element.scrollIntoView());
    await capture(page, "05b-provider-models-dark");
    await page.setViewportSize({ width: 1280, height: 820 });
    await dialog.getByLabel("供应商名称").focus();
    await page.emulateMedia({ colorScheme: "light", reducedMotion: "no-preference" });
    await capture(page, "06-provider-light-desktop");
    await page.setViewportSize({ width: 720, height: 640 });
    await page.emulateMedia({ reducedMotion: "reduce", forcedColors: "active" });
    await capture(page, "07-provider-forced-colors-narrow");
    const overflow = await dialog.evaluate((element) => element.scrollWidth > element.clientWidth);
    if (overflow) throw new Error("供应商表单出现横向溢出");
    await dialog.getByRole("button", { name: "取消", exact: true }).click();
    await page.emulateMedia({ forcedColors: "none" });
    process.stdout.write(`JA_PROVIDER_SETTINGS_ACCEPTANCE_OK evidence=${evidenceDirectory}\n`);
  } finally {
    await browser.close();
  }
}

await main();
