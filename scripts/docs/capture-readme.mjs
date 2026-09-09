// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import process from "node:process";
import { chromium } from "@playwright/test";

/** 截图只读取隔离 Vite 的示例页面，不连接 Tauri、真实会话或 Provider。 */
async function captureReadme() {
  const base = process.argv[2] ?? "http://127.0.0.1:1459";
  const output = resolve("docs/images");
  await mkdir(output, { recursive: true });
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 760 }, deviceScaleFactor: 1 });
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await page.goto(`${base}/tests/app/e2e/readmeBrowserFixture.html`);
    await page.getByText("首页基础已经清楚，建议先做三件事：").waitFor();
    const processToggle = page.getByRole("button", { name: /工作过程/ });
    if (await processToggle.getAttribute("aria-expanded") === "false") await processToggle.click();
    await page.getByText("执行命令", { exact: true }).waitFor();
    await page.evaluate(() => globalThis.document.fonts.ready);
    assert.equal(await page.evaluate(() => globalThis.document.compatMode), "CSS1Compat");
    await page.screenshot({ path: resolve(output, "conversation.png"), animations: "disabled" });
    await page.goto(`${base}/tests/app/e2e/reviewRedesignBrowserFixture.html?theme=light&demo=readme`);
    await page.getByText("src/pages/Home.tsx", { exact: true }).first().waitFor();
    await page.evaluate(() => globalThis.document.fonts.ready);
    await page.screenshot({ path: resolve(output, "review.png"), clip: { x: 0, y: 0, width: 1280, height: 500 }, animations: "disabled" });
    assert.deepEqual(errors, []);
    console.log("README_SCREENSHOTS_OK images=2 source=production-components data=demo");
  } finally {
    await browser.close();
  }
}

await captureReadme();
