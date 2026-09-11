// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { mkdir } from "node:fs/promises";
import { isAbsolute, join } from "node:path";
import { chromium, expect } from "@playwright/test";

const endpoint = process.env.JA_E2E_SETTINGS_CDP_ENDPOINT?.trim();
const artifacts = process.env.JA_E2E_SETTINGS_ARTIFACT_DIR?.trim();
const labels = ["通用", "外观", "模型", "子智能体", "执行确认", "Skills", "MCP", "关于"];

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

/** 不创建会话或发起模型请求，直接验收首次设置中的真实分类、搜索与原生通知端口。 */
async function verifySettings(page) {
  await page.reload();
  const settings = page.locator(".ja-settings");
  await expect(settings).toBeVisible({ timeout: 60000 });
  await expect(settings).not.toHaveAttribute("inert", "", { timeout: 60000 });
  await expect(settings.getByRole("tab")).toHaveText(labels);
  await expect(settings.getByRole("tab", { name: "模型", exact: true })).toHaveAttribute(
    "data-state",
    "active",
  );
  for (const [index, label] of labels.entries()) {
    await settings.getByRole("tab", { name: label, exact: true }).click();
    await expect(settings.getByRole("tabpanel", { name: label, exact: true })).toBeVisible();
    await capture(page, `wide-${index + 1}`);
  }

  await settings.getByRole("tab", { name: "通用", exact: true }).click();
  await page.keyboard.press("ArrowDown");
  await expect(settings.getByRole("tab", { name: "外观", exact: true })).toHaveAttribute(
    "data-state",
    "active",
  );
  await expect(
    settings.getByRole("tabpanel", { name: "外观" }).getByRole("switch", { name: "桌面通知" }),
  ).toHaveCount(0);
  await settings.getByRole("textbox", { name: "搜索设置" }).fill("桌面通知");
  await expect(settings.getByRole("option")).toHaveCount(1);
  await settings.getByRole("option", { name: /桌面通知/ }).click();
  const toggle = settings.getByRole("switch", { name: "桌面通知", exact: true });
  await expect(toggle).toBeFocused();
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await expect(settings.getByRole("status")).toContainText("桌面通知已开启");
  await page.reload();
  await expect(settings).not.toHaveAttribute("inert", "", { timeout: 60000 });
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
      `JA_SETTINGS_NAVIGATION_OK seven-tabs notification-roundtrip search-focus keyboard narrow-layout pageErrors=0 evidence=${artifacts}\n`,
    );
  } finally {
    await browser.close();
  }
}

await main();
