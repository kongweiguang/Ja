// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { mkdir } from "node:fs/promises";
import { isAbsolute, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { chromium, expect } from "@playwright/test";

const endpoint = process.env.JA_E2E_SETTINGS_CDP_ENDPOINT?.trim();
let artifacts = process.env.JA_E2E_SETTINGS_ARTIFACT_DIR?.trim();
const labels = ["通用", "外观", "模型", "子智能体", "执行确认", "Skills", "MCP", "数据与存储", "关于"];
let navigationStep;

/** 仅允许显式指定的隔离 Windows WebView2，避免接触用户主实例与真实设置。 */
function validateEnvironment() {
  if (process.platform !== "win32" || process.env.JA_E2E_SETTINGS_ISOLATED !== "1") {
    throw new Error("必须使用隔离 Windows 实例并设置 JA_E2E_SETTINGS_ISOLATED=1");
  }
  if (!/^http:\/\/(127\.0\.0\.1|localhost):\d+\/?$/.test(endpoint ?? "")) {
    throw new Error("JA_E2E_SETTINGS_CDP_ENDPOINT 必须是回环 CDP 地址");
  }
  if (!artifacts || !isAbsolute(artifacts)) {
    throw new Error("JA_E2E_SETTINGS_ARTIFACT_DIR 必须是绝对目录");
  }
}

/** 截图使用真实组件和全局样式；只等待有限过渡，避免无限动效阻塞验收。 */
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

/** 首屏保持对话工作区，设置必须由用户可见入口打开；reload 后不依赖内存导航状态。 */
async function openSettings(page) {
  navigationStep = "open_visible_settings_entry";
  const settings = page.locator(".ja-settings");
  if (!(await settings.isVisible())) {
    const reveal = page.getByRole("button", { name: "显示侧边栏", exact: true });
    if (await reveal.isVisible()) await reveal.click();
    await page.getByRole("button", { name: "设置", exact: true }).click();
  }
  navigationStep = "wait_visible_settings";
  await expect(settings).toBeVisible({ timeout: 60000 });
  navigationStep = "wait_settings_enabled";
  await expect(settings).not.toHaveAttribute("inert", "", { timeout: 60000 });
  return settings;
}

/** 不创建会话或发起模型请求，通过真实设置入口验收分类、搜索与原生通知端口。 */
async function verifySettings(page) {
  await page.reload();
  const settings = await openSettings(page);
  navigationStep = "verify_nine_categories";
  await expect(settings.getByRole("tab")).toHaveText(labels);
  navigationStep = "verify_initial_models_category";
  await expect(settings.getByRole("tab", { name: "模型", exact: true })).toHaveAttribute(
    "data-state",
    "active",
  );
  for (const [index, label] of labels.entries()) {
    navigationStep = `wide_category_${index + 1}`;
    await settings.getByRole("tab", { name: label, exact: true }).click();
    await expect(settings.getByRole("tabpanel", { name: label, exact: true })).toBeVisible();
    await capture(page, `wide-${index + 1}`);
  }

  navigationStep = "keyboard_navigation";
  await settings.getByRole("tab", { name: "通用", exact: true }).click();
  await expect(settings.getByRole("tab", { name: "通用", exact: true })).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(settings.getByRole("tab", { name: "外观", exact: true })).toHaveAttribute(
    "data-state",
    "active",
  );
  await expect(
    settings.getByRole("tabpanel", { name: "外观" }).getByRole("switch", { name: "桌面通知" }),
  ).toHaveCount(0);
  navigationStep = "notification_search_focus";
  await settings.getByRole("textbox", { name: "搜索设置" }).fill("桌面通知");
  await expect(settings.getByRole("option")).toHaveCount(1);
  await settings.getByRole("option", { name: /桌面通知/ }).click();
  const toggle = settings.getByRole("switch", { name: "桌面通知", exact: true });
  await expect(toggle).toBeFocused();
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  navigationStep = "notification_enable";
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await expect(settings.getByRole("status")).toContainText("桌面通知已开启");
  await page.reload();
  await openSettings(page);
  navigationStep = "notification_readback";
  await settings.getByRole("tab", { name: "通用", exact: true }).click();
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  await expect(settings.getByRole("status")).toContainText("桌面通知已关闭");

  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await capture(page, "wide-general-dark");
  await page.setViewportSize({ width: 720, height: 640 });
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  for (const [index, label] of labels.entries()) {
    navigationStep = `narrow_category_${index + 1}`;
    await settings.getByRole("tab", { name: label, exact: true }).click();
    await expect(settings.getByRole("tabpanel", { name: label, exact: true })).toBeVisible();
    await expect
      .poll(() => settings.evaluate((element) => element.scrollWidth <= element.clientWidth))
      .toBe(true);
    await capture(page, `narrow-${index + 1}`);
  }
  await settings.getByRole("tab", { name: "通用", exact: true }).click();
  await page.emulateMedia({ forcedColors: "active" });
  await capture(page, "narrow-general-forced-colors");
  await page.emulateMedia({ forcedColors: "none", colorScheme: "light", reducedMotion: "reduce" });
  await settings.getByRole("textbox", { name: "搜索设置" }).fill("");
}

/** 启动器已验证本轮进程与隔离 profile；导航复用生产控件，禁止创建替代 HTML 或 mock 端口。 */
export async function runSettingsNavigation(page, evidenceDirectory) {
  artifacts = evidenceDirectory;
  await mkdir(artifacts, { recursive: true });
  await page.setViewportSize({ width: 1280, height: 820 });
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  try { await verifySettings(page); }
  catch (error) { error.settingsNavigationStep = navigationStep; throw error; }
  await page.setViewportSize({ width: 1280, height: 820 });
  return { categories: labels.length, keyboard: true, searchFocus: true, notificationReadback: true };
}

/** 仅断开 CDP transport；实例生命周期由创建它的隔离启动器负责。 */
async function main() {
  validateEnvironment();
  await mkdir(artifacts, { recursive: true });
  const browser = await chromium.connectOverCDP(endpoint);
  try {
    const page = browser.contexts().flatMap((context) => context.pages())[0];
    if (!page) throw new Error("找不到隔离 WebView2 页面");
    const pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await page.setViewportSize({ width: 1280, height: 820 });
    await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
    await verifySettings(page);
    expect(pageErrors).toEqual([]);
    process.stdout.write(
      `JA_SETTINGS_NAVIGATION_OK categories=${labels.length} notification-roundtrip search-focus keyboard narrow-layout pageErrors=0 evidence=${artifacts}\n`,
    );
  } finally {
    await browser.close();
  }
}

if (process.argv[1] !== undefined && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
