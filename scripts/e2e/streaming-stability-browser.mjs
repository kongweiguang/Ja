// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const REPOSITORY_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const FIXTURE_PATH = "/tests/app/e2e/streamingStabilityBrowserFixture.html";
const DEFAULT_EVIDENCE_DIRECTORY = join(
  REPOSITORY_ROOT,
  ".tmp",
  "streaming-stability-browser-evidence",
);

/** CLI 只允许选择证据目录，fixture 始终从独立 Vite 实例加载真实生产组件和全局样式。 */
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

/** 向系统申请临时端口，避免并行开发任务共享固定 Vite 端口。 */
async function reservePort() {
  const server = createServer();
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  await new Promise((resolvePromise, reject) =>
    server.close((error) => (error === undefined ? resolvePromise() : reject(error))),
  );
  return address.port;
}

/** 启动只服务 fixture 的 Vite；清理桌面 E2E 入口变量，避免进入 Tauri 专用 composition。 */
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

/** 等到 HTML 与 Vite transform 均可访问，避免用固定 sleep 掩盖启动失败。 */
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

/** 只回收本脚本创建的进程树；Windows 上 child.kill 无法覆盖孙进程时再使用 taskkill。 */
async function stopFixtureServer(child) {
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

/** 通过 fixture 暴露的公开 reducer 入口发送事件，不直接修改 React 私有状态。 */
async function invokeFixture(page, method, argument) {
  return page.evaluate(
    ({ requestedMethod, requestedArgument }) => {
      const fixture = globalThis.__JA_STREAMING_STABILITY__;
      if (fixture === undefined) throw new Error("streaming fixture is unavailable");
      return requestedArgument === undefined
        ? fixture[requestedMethod]()
        : fixture[requestedMethod](requestedArgument);
    },
    { requestedMethod: method, requestedArgument: argument },
  );
}

/** 主场景验证过程/回复分区、生命信号、DOM identity、长 Markdown、阅读锚点及 terminal 收口。 */
async function verifyStreamingLifecycle(page, fixtureUrl, evidenceDirectory) {
  await page.setViewportSize({ width: 1180, height: 760 });
  await page.emulateMedia({ reducedMotion: "no-preference", colorScheme: "light" });
  await page.goto(fixtureUrl, { waitUntil: "domcontentloaded" });

  const initialResponse = page.getByRole("article", { name: "回复状态" });
  await initialResponse.getByText("正在工作", { exact: true }).waitFor({ state: "visible" });
  assert.equal(await initialResponse.locator(".ja-chat-activity-dots").count(), 1);

  const reasoning = "先核对现有状态，再逐步完成流式稳定性验证。";
  const first = "当前回复从第一段开始就在过程区外持续生成。";
  assert.equal(await invokeFixture(page, "appendReasoning", reasoning), "applied");
  assert.equal(await invokeFixture(page, "appendDelta", first), "applied");
  const process = page.getByRole("region", { name: "工作过程" });
  await process.waitFor({ state: "visible" });
  const reasoningStep = process.locator('[data-role="reasoning"]').first();
  await reasoningStep.getByText(reasoning, { exact: false }).waitFor({ state: "visible" });
  const response = page.getByRole("article", { name: "回复状态" });
  await response.getByText(first, { exact: false }).waitFor({ state: "visible" });
  await response.getByText("正在工作", { exact: true }).waitFor({ state: "visible" });
  const processHandle = await process.elementHandle();
  const reasoningHandle = await reasoningStep.elementHandle();
  const responseHandle = await response.elementHandle();
  assert.ok(processHandle && reasoningHandle && responseHandle);

  assert.equal(await invokeFixture(page, "repeatLastDelta"), "duplicate");
  assert.equal(await response.getByText(first, { exact: false }).count(), 1);
  const markdown = [
    "\n\n## 长内容验证",
    ...Array.from(
      { length: 18 },
      (_, index) => `\n\n第 ${index + 1} 段用于确认用户上滚后不会被新增内容抢回底部。`,
    ),
    "\n\n```text\nTHIS_IS_A_VERY_LONG_CODE_LINE_" + "0123456789".repeat(24) + "\n```",
    "\n\n| 列一 | 列二 | 列三 | 列四 |\n| --- | --- | --- | --- |\n| " +
      "很长的中文内容".repeat(12) +
      " | B | C | D |",
  ].join("");
  assert.equal(await invokeFixture(page, "appendDelta", markdown), "applied");
  const sameNodes = await processHandle.evaluate(
    (processNode, handles) =>
      processNode === handles.process &&
      globalThis.document.querySelector('[data-role="reasoning"]') === handles.reasoning &&
      globalThis.document.querySelector('[data-role="response"]') === handles.response,
    { process: processHandle, reasoning: reasoningHandle, response: responseHandle },
  );
  assert.equal(
    sameNodes,
    true,
    "stream delta must retain process, reasoning, and response DOM identity",
  );
  assert.equal(await process.locator('[data-role="reasoning"]').count(), 1);
  assert.equal(await process.getByText("长内容验证", { exact: true }).count(), 0);
  const overflow = await response.evaluate((element) => {
    const code = element.querySelector("pre");
    const table = element.querySelector(".ja-markdown__table-wrap");
    return {
      codeScrollable: code !== null && code.scrollWidth > code.clientWidth,
      tableContained:
        table !== null &&
        globalThis.getComputedStyle(table).overflowX === "auto" &&
        table.getBoundingClientRect().right <= element.getBoundingClientRect().right + 1,
    };
  });
  assert.equal(overflow.codeScrollable, true, "long code must scroll inside its own region");
  assert.equal(overflow.tableContained, true, "wide table must remain inside its scroll region");

  const scroll = page.locator(".ja-chat-timeline__scroll");
  await scroll.evaluate((element) => {
    element.dispatchEvent(new globalThis.WheelEvent("wheel", { deltaY: -240, bubbles: true }));
    element.scrollTop = Math.min(200, Math.max(0, element.scrollHeight - element.clientHeight));
    element.dispatchEvent(new Event("scroll"));
  });
  await page.getByRole("button", { name: "回到最新", exact: true }).waitFor({ state: "visible" });
  const readingAnchor = response.getByText("第 6 段用于确认用户上滚后不会被新增内容抢回底部。", {
    exact: true,
  });
  const beforeAnchor = await readingAnchor.boundingBox();
  assert.ok(beforeAnchor, "reading anchor must be mounted before the next delta");
  assert.equal(
    await invokeFixture(page, "appendDelta", "\n\n末尾新增内容不应改变当前阅读锚点。"),
    "applied",
  );
  await page.waitForTimeout(100);
  const afterAnchor = await readingAnchor.boundingBox();
  assert.ok(afterAnchor, "reading anchor must stay mounted after the next delta");
  assert.ok(
    Math.abs(afterAnchor.y - beforeAnchor.y) <= 4,
    `stream stole reading anchor: before=${beforeAnchor.y} after=${afterAnchor.y}`,
  );
  await page.getByRole("button", { name: "回到最新", exact: true }).click();
  await page.waitForFunction(() => {
    const element = globalThis.document.querySelector(".ja-chat-timeline__scroll");
    return (
      element !== null && element.scrollHeight - element.scrollTop - element.clientHeight <= 64
    );
  });

  await page.screenshot({
    path: join(evidenceDirectory, "streaming-live-light.png"),
    animations: "disabled",
  });
  assert.equal(await response.getAttribute("data-response-state"), "streaming");
  const finalText = "最终答复只由 terminal 权威事实生成，并保持唯一阅读位置。";
  assert.equal(await invokeFixture(page, "complete", finalText), "applied");
  const finalResponse = page.getByRole("article", { name: "最终答复" });
  await finalResponse.getByText(finalText).waitFor();
  const finalResponseHandle = await finalResponse.elementHandle();
  assert.ok(finalResponseHandle);
  assert.equal(
    await responseHandle.evaluate((before, after) => before === after, finalResponseHandle),
    true,
    "terminal must calibrate the existing response node",
  );
  assert.equal(await page.getByText(finalText, { exact: true }).count(), 1);
  const trigger = process.locator(".ja-work-process__trigger");
  await trigger.getByText("查看工作过程", { exact: true }).waitFor();
  assert.equal(await trigger.getAttribute("aria-expanded"), "false");
  await trigger.click();
  assert.equal(await trigger.getAttribute("aria-expanded"), "true");
  await page.screenshot({
    path: join(evidenceDirectory, "streaming-complete-expanded.png"),
    animations: "disabled",
  });
  return {
    duplicateSuppressed: true,
    processNodeStable: true,
    responseNodeStable: true,
    workingStatusVisible: true,
    liveResponseBeforeTerminal: true,
    scrollAnchorStable: true,
  };
}

/** 视觉矩阵复用同一真实组件入口，分别验证主题、窄窗、缩放与减少动态效果。 */
async function captureVisualMatrix(page, fixtureUrl, evidenceDirectory) {
  const frames = [
    { name: "light-wide", theme: "light", width: 1180, height: 760, zoom: 1, reduced: false },
    { name: "dark-wide", theme: "dark", width: 1180, height: 760, zoom: 1, reduced: false },
    { name: "light-narrow", theme: "light", width: 720, height: 640, zoom: 1, reduced: false },
    {
      name: "dark-zoom-reduced",
      theme: "dark",
      width: 960,
      height: 720,
      zoom: 1.25,
      reduced: true,
    },
  ];
  const evidence = [];
  for (const frame of frames) {
    await page.setViewportSize({ width: frame.width, height: frame.height });
    await page.emulateMedia({
      reducedMotion: frame.reduced ? "reduce" : "no-preference",
      colorScheme: frame.theme,
    });
    await page.goto(fixtureUrl, { waitUntil: "domcontentloaded" });
    await page.evaluate(({ theme, zoom }) => {
      globalThis.document.documentElement.dataset.theme = theme;
      globalThis.document.documentElement.style.zoom = String(zoom);
    }, frame);
    assert.equal(
      await invokeFixture(page, "appendReasoning", "正在核对主题与布局边界。"),
      "applied",
    );
    assert.equal(
      await invokeFixture(page, "appendDelta", "正在验证主题、窄窗与缩放下的过程排版。"),
      "applied",
    );
    assert.equal(
      await invokeFixture(page, "complete", "视觉矩阵中的最终答复保持清晰且不与控件重叠。"),
      "applied",
    );
    await page.getByRole("article", { name: "最终答复" }).waitFor({ state: "visible" });
    const facts = await page.evaluate(() => {
      const root = globalThis.document.documentElement;
      const timeline = globalThis.document.querySelector(".ja-chat-timeline");
      const composer = globalThis.document.querySelector(".ja-conversation-composer-dock");
      const timelineRect = timeline?.getBoundingClientRect();
      const composerRect = composer?.getBoundingClientRect();
      return {
        reducedMotion: globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches,
        horizontalOverflow: root.scrollWidth > root.clientWidth + 1,
        overflowing: [...globalThis.document.querySelectorAll("body *")]
          .map((element) => {
            const rect = element.getBoundingClientRect();
            return {
              selector: `${element.tagName.toLowerCase()}.${[...element.classList].join(".")}`,
              left: Math.round(rect.left),
              right: Math.round(rect.right),
              width: Math.round(rect.width),
            };
          })
          .filter((entry) => entry.right > root.clientWidth + 1 || entry.left < -1)
          .slice(0, 12),
        overlap:
          timelineRect !== undefined &&
          composerRect !== undefined &&
          timelineRect.bottom > composerRect.top + 1,
      };
    });
    assert.equal(facts.reducedMotion, frame.reduced);
    assert.equal(
      facts.horizontalOverflow,
      false,
      `${frame.name} has page-level horizontal overflow: ${JSON.stringify(facts.overflowing)}`,
    );
    assert.equal(facts.overlap, false, `${frame.name} timeline overlaps composer`);
    const screenshot = `matrix-${frame.name}.png`;
    await page.screenshot({ path: join(evidenceDirectory, screenshot), animations: "disabled" });
    evidence.push({ ...frame, screenshot, ...facts });
  }
  return evidence;
}

/** 完整 runner 持有浏览器与 Vite 生命周期，失败时仍写入截图和精简诊断。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  await mkdir(options.evidenceDirectory, { recursive: true });
  const port = await reservePort();
  const fixtureServer = startFixtureServer(port);
  const fixtureUrl = `http://127.0.0.1:${port}${FIXTURE_PATH}`;
  let browser;
  let page;
  try {
    await waitForFixture(fixtureUrl, fixtureServer);
    browser = await chromium.launch({ headless: true });
    page = await browser.newPage();
    const pageErrors = [];
    page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
    const lifecycle = await verifyStreamingLifecycle(page, fixtureUrl, options.evidenceDirectory);
    const matrix = await captureVisualMatrix(page, fixtureUrl, options.evidenceDirectory);
    assert.deepEqual(pageErrors, []);
    const report = { schemaVersion: 1, status: "passed", lifecycle, matrix, pageErrors };
    const reportPath = join(options.evidenceDirectory, "streaming-stability-report.json");
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
    console.log(`JA_STREAMING_STABILITY_BROWSER_OK ${JSON.stringify({ report: reportPath })}`);
  } catch (error) {
    if (page !== undefined)
      await page
        .screenshot({ path: join(options.evidenceDirectory, "streaming-stability-failure.png") })
        .catch(() => undefined);
    throw error;
  } finally {
    await browser?.close();
    await stopFixtureServer(fixtureServer.child);
  }
}

if (process.argv[1] !== undefined && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  main().catch((error) => {
    console.error(`JA_STREAMING_STABILITY_BROWSER_FAIL ${String(error?.stack ?? error)}`);
    process.exitCode = 1;
  });
}
