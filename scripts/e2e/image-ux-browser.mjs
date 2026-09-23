// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdir, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const REPOSITORY_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const FIXTURE_PATH = "/tests/app/e2e/imageUxBrowserFixture.html";
const DEFAULT_EVIDENCE_DIRECTORY = join(REPOSITORY_ROOT, ".tmp", "ux-closure-browser");

/** 解析证据目录，避免脚本修改工作区内除明确输出目录之外的文件。 */
function parseArguments(argv) {
  const options = { evidenceDirectory: DEFAULT_EVIDENCE_DIRECTORY };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument !== "--evidence-directory") throw new Error(`unknown argument: ${argument}`);
    const value = argv[index + 1];
    if (value === undefined) throw new Error("--evidence-directory requires a value");
    options.evidenceDirectory = resolve(value);
    index += 1;
  }
  return options;
}

/** 申请一次性本地端口，避免与并行开发服务器竞争固定端口。 */
async function reservePort() {
  const server = createServer();
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  const port = address.port;
  await new Promise((resolvePromise, reject) =>
    server.close((error) => (error === undefined ? resolvePromise() : reject(error))),
  );
  return port;
}

/** 启动只服务浏览器 fixture 的 Vite，清除 Tauri E2E 注入避免切换到其它 composition。 */
function startFixtureServer(port) {
  const environment = { ...process.env };
  delete environment.JA_E2E_DEV_PORT;
  const child = spawn(
    process.execPath,
    [
      join(REPOSITORY_ROOT, "node_modules", "vite", "bin", "vite.js"),
      "--config",
      join(REPOSITORY_ROOT, "apps", "desktop", "vite.config.ts"),
      "--host",
      "127.0.0.1",
      "--port",
      String(port),
      "--strictPort",
    ],
    {
      cwd: REPOSITORY_ROOT,
      env: environment,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let output = "";
  child.stdout.on("data", (chunk) => {
    output = `${output}${chunk}`.slice(-12_000);
  });
  child.stderr.on("data", (chunk) => {
    output = `${output}${chunk}`.slice(-12_000);
  });
  return { child, output: () => output };
}

/** 有界等待 Vite HTML 可访问，启动失败时保留最近诊断而不是继续截图空白页。 */
async function waitForFixture(url, fixtureServer) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (fixtureServer.child.exitCode !== null)
      throw new Error(`fixture server exited early\n${fixtureServer.output()}`);
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // Vite 尚未监听，继续有界探测。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`fixture server did not become ready\n${fixtureServer.output()}`);
}

/** 结束本脚本创建的 Vite 进程树，不触碰并行任务启动的进程。 */
async function stopFixtureServer(fixtureServer) {
  const { child } = fixtureServer;
  if (child.exitCode !== null) return;
  child.kill();
  const exited = await Promise.race([
    new Promise((resolvePromise) => child.once("exit", () => resolvePromise(true))),
    new Promise((resolvePromise) => setTimeout(() => resolvePromise(false), 2_000)),
  ]);
  if (exited || process.platform !== "win32" || child.pid === undefined) return;
  await new Promise((resolvePromise) => {
    const killer = spawn("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], {
      windowsHide: true,
      stdio: "ignore",
    });
    killer.once("exit", resolvePromise);
  });
}

/** 从真实 DOM 读取附件、正文和草稿的几何事实，禁止用固定像素猜测布局是否正确。 */
async function readLayoutFacts(page, viewportWidth) {
  return page.evaluate((width) => {
    const rect = (element) => {
      if (element === null) return null;
      const value = element.getBoundingClientRect();
      return {
        left: value.left,
        right: value.right,
        top: value.top,
        bottom: value.bottom,
        width: value.width,
        height: value.height,
      };
    };
    const multi = globalThis.document.querySelector('[data-item-id="item_multi_image"]');
    const pure = globalThis.document.querySelector('[data-item-id="item_pure_image"]');
    const attachments = multi?.querySelector(".ja-chat-attachments") ?? null;
    const body = multi?.querySelector(".ja-chat-message__body") ?? null;
    const imageElements =
      attachments === null
        ? []
        : [...attachments.querySelectorAll(".ja-chat-attachment--image img")];
    const draftList = globalThis.document.querySelector(
      '.ja-composer__attachments[aria-label="待发送附件"]',
    );
    const draftImages = draftList === null ? [] : [...draftList.querySelectorAll("img")];
    const documentWidth = Math.max(
      globalThis.document.documentElement.scrollWidth,
      globalThis.document.body.scrollWidth,
    );
    const allBoxes = [attachments, body, pure, draftList, ...imageElements, ...draftImages]
      .map(rect)
      .filter(Boolean);
    const maxRight = allBoxes.reduce((value, box) => Math.max(value, box.right), 0);
    const minLeft = allBoxes.reduce((value, box) => Math.min(value, box.left), width);
    const attachmentsBox = rect(attachments);
    const bodyBox = rect(body);
    const imageBoxes = imageElements.map(rect).filter(Boolean);
    const lastImageRight = imageBoxes.reduce((value, box) => Math.max(value, box.right), 0);
    return {
      viewportWidth: width,
      documentWidth,
      horizontalOverflow: documentWidth > width + 1 || minLeft < -1 || maxRight > width + 1,
      imagesAboveText:
        attachmentsBox !== null && bodyBox !== null && attachmentsBox.bottom <= bodyBox.top + 1,
      rightAligned:
        attachmentsBox !== null &&
        imageBoxes.length > 0 &&
        attachmentsBox.right - lastImageRight <= 1,
      multiAttachmentCount: attachments?.querySelectorAll(".ja-chat-attachment--image").length ?? 0,
      multiAttachmentBox: attachmentsBox,
      bodyBox,
      imageBoxes,
      pureImageBodyCount: pure?.querySelectorAll(".ja-chat-message__body").length ?? 0,
      pureImageClass: pure?.classList.contains("ja-chat-message-user--attachments-only") ?? false,
      draftCount: draftList?.querySelectorAll(":scope > li").length ?? 0,
      draftImageNaturalWidths: draftImages.map((image) => image.naturalWidth),
    };
  }, viewportWidth);
}

/** 验证真实图片已解码并等待虚拟时间线完成附件投影后再采集 bbox。 */
async function waitForImageProjection(page) {
  await page.getByRole("article", { name: "用户问题" }).first().waitFor({ state: "visible" });
  await page
    .locator('[data-item-id="item_multi_image"] .ja-chat-attachment--image img')
    .first()
    .waitFor({ state: "visible" });
  await page.waitForFunction(() => {
    const images = [
      ...globalThis.document.querySelectorAll(
        ".ja-chat-attachment--image img, .ja-composer-attachment img",
      ),
    ];
    return (
      images.length >= 4 &&
      images.every((image) => image.naturalWidth > 0 && image.naturalHeight > 0)
    );
  });
  await page.evaluate(() => globalThis.document.fonts?.ready);
}

/** 在桌面与窄屏分别截图并读取独立布局事实，保留相同 DOM 场景的可比证据。 */
async function captureViewport(page, evidenceDirectory, width, height, fileName) {
  await page.setViewportSize({ width, height });
  await page.waitForTimeout(80);
  const facts = await readLayoutFacts(page, width);
  await page.screenshot({ path: join(evidenceDirectory, fileName), animations: "disabled" });
  return facts;
}

/** 运行真实组件 fixture，失败时仍写出阶段与页面错误，便于主任务区分基础模块阻塞和布局失败。 */
export async function runImageUx(options) {
  await mkdir(options.evidenceDirectory, { recursive: true });
  const reportPath = join(options.evidenceDirectory, "image-ux-report.json");
  const frontendPort = await reservePort();
  const fixtureServer = startFixtureServer(frontendPort);
  const fixtureUrl = `http://127.0.0.1:${frontendPort}${FIXTURE_PATH}`;
  let browser;
  let page;
  let stage = "fixture_server";
  const pageErrors = [];
  try {
    await waitForFixture(fixtureUrl, fixtureServer);
    stage = "browser";
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage({ viewport: { width: 1180, height: 760 } });
    page.on("pageerror", (error) =>
      pageErrors.push(String(error?.stack ?? error?.message ?? error).slice(0, 1_500)),
    );
    await page.goto(fixtureUrl, { waitUntil: "domcontentloaded" });
    stage = "projection";
    await waitForImageProjection(page);
    stage = "capture";
    const desktop = await captureViewport(
      page,
      options.evidenceDirectory,
      1180,
      760,
      "desktop-1180x760.png",
    );
    const narrow = await captureViewport(
      page,
      options.evidenceDirectory,
      720,
      640,
      "narrow-720x640.png",
    );
    assert.deepEqual(pageErrors, [], `browser page errors: ${pageErrors.join(" | ")}`);
    for (const [name, facts] of Object.entries({ desktop, narrow })) {
      assert.equal(facts.multiAttachmentCount, 2, `${name}: expected two history images`);
      assert.equal(facts.imagesAboveText, true, `${name}: images must be above text bubble`);
      assert.equal(facts.rightAligned, true, `${name}: history images must be right aligned`);
      assert.equal(facts.horizontalOverflow, false, `${name}: horizontal overflow detected`);
      assert.equal(facts.pureImageBodyCount, 0, `${name}: pure image message has empty body`);
      assert.equal(facts.pureImageClass, true, `${name}: pure image class missing`);
      assert.equal(facts.draftCount, 2, `${name}: expected two draft images`);
      assert.equal(
        facts.draftImageNaturalWidths.every((value) => value > 0),
        true,
        `${name}: draft image did not decode`,
      );
    }
    const report = {
      schemaVersion: 1,
      status: "passed",
      fixture: FIXTURE_PATH,
      runtime: { browser: "chromium", viewportWidths: [1180, 720] },
      desktop,
      narrow,
      screenshots: ["desktop-1180x760.png", "narrow-720x640.png"],
      diagnostics: { pageErrors },
    };
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
    return report;
  } catch (error) {
    await page
      ?.screenshot({ path: join(options.evidenceDirectory, "failure.png"), animations: "disabled" })
      .catch(() => undefined);
    const report = {
      schemaVersion: 1,
      status: "failed",
      stage,
      error: String(error?.message ?? error)
        .replace(/[\r\n]+/gu, " ")
        .slice(0, 2_000),
      diagnostics: { pageErrors, fixtureOutput: fixtureServer.output().slice(-2_000) },
    };
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8").catch(
      () => undefined,
    );
    throw error;
  } finally {
    await browser?.close().catch(() => undefined);
    await stopFixtureServer(fixtureServer);
  }
}

/** CLI 入口只输出稳定状态标记，详细 bbox 与截图路径写入指定证据目录。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  await runImageUx(options);
  console.log("JA_IMAGE_UX_BROWSER_PASS");
}

const invokedPath = process.argv[1] === undefined ? undefined : resolve(process.argv[1]);
if (invokedPath === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(`JA_IMAGE_UX_BROWSER_FAIL ${String(error?.message ?? error).slice(0, 2_000)}`);
    process.exitCode = 1;
  });
}
