// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import process from "node:process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { chromium } from "@playwright/test";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const FIXTURE_PATH = "/tests/app/e2e/reviewPerformanceBrowserFixture.html";
const DEFAULT_FILE_COUNT = 2_500;
const DEFAULT_SAMPLES = 5;
const DEFAULT_ADAPTER_DELAY_MS = 5;

/** CLI 只接受证据目录与有界规模参数，避免压力数据失控或报告写入仓库外未知位置。 */
export function parseArguments(argv) {
  const parsed = {
    evidenceDirectory: undefined,
    fileCount: DEFAULT_FILE_COUNT,
    samples: DEFAULT_SAMPLES,
    adapterDelayMs: DEFAULT_ADAPTER_DELAY_MS,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") parsed.evidenceDirectory = resolve(value);
    else if (argument === "--files") parsed.fileCount = Number(value);
    else if (argument === "--samples") parsed.samples = Number(value);
    else if (argument === "--adapter-delay-ms") parsed.adapterDelayMs = Number(value);
    else throw new Error(`unknown argument: ${argument}`);
    index += 1;
  }
  if (parsed.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (!Number.isSafeInteger(parsed.fileCount) || parsed.fileCount < 4 || parsed.fileCount > 10_000)
    throw new Error("--files must be an integer between 4 and 10000");
  if (!Number.isSafeInteger(parsed.samples) || parsed.samples < 3 || parsed.samples > 20)
    throw new Error("--samples must be an integer between 3 and 20");
  if (
    !Number.isSafeInteger(parsed.adapterDelayMs) ||
    parsed.adapterDelayMs < 0 ||
    parsed.adapterDelayMs > 100
  )
    throw new Error("--adapter-delay-ms must be an integer between 0 and 100");
  return parsed;
}

/** nearest-rank 百分位适合少量端到端样本，避免插值生成从未真实观测的耗时。 */
export function percentile(values, percentileValue) {
  assert.ok(values.length > 0, "percentile requires samples");
  assert.ok(percentileValue > 0 && percentileValue <= 1, "percentile must be within (0, 1]");
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(sorted.length * percentileValue) - 1)];
}

/** 汇总真实样本分布，同时保留 min/max 便于识别预热抖动。 */
export function summarize(values) {
  assert.ok(values.every((value) => Number.isFinite(value) && value >= 0));
  return {
    count: values.length,
    minMs: Math.min(...values),
    p50Ms: percentile(values, 0.5),
    p95Ms: percentile(values, 0.95),
    maxMs: Math.max(...values),
  };
}

/**
 * 浏览器报告只证明 production controller/view 的调度与渲染；验证器强制 native=false，
 * 防止把 fake ReviewPort 的快速结果误报成 Git/Tauri 性能。
 */
export function validateBrowserPerformanceReport(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.runtime, "production_controller_browser_fixture");
  assert.equal(report?.nativeVerified, false);
  assert.equal(report?.nativeGitVerified, false);
  assert.ok(report?.fileCount >= 4);
  assert.ok(report?.samples >= 3);
  assert.equal(report?.correctness?.allSamplesPassed, true);
  assert.equal(report?.correctness?.hiddenSnapshotDelta, 0);
  assert.equal(report?.correctness?.hiddenFileDiffDelta, 0);
  assert.equal(report?.correctness?.hiddenCatalogDelta, 0);
  assert.equal(report?.correctness?.hiddenSubscribeDelta, 0);
  assert.equal(report?.correctness?.subscriptionsBalanced, true);
  assert.equal(report?.correctness?.latestWins, true);
  assert.ok(report?.metrics?.coldReady?.p95Ms <= 1_500, "cold-ready p95 exceeds browser budget");
  assert.ok(report?.metrics?.fileSwitch?.p95Ms <= 500, "file-switch p95 exceeds browser budget");
  assert.ok(report?.metrics?.fileRevisit?.p95Ms <= 500, "file-revisit p95 exceeds browser budget");
  return report;
}

/** 在临时监听后释放唯一回环端口，不接触已占用的 1436 预览。 */
async function reservePort() {
  const server = createServer();
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("failed to reserve port");
  await new Promise((resolvePromise) => server.close(resolvePromise));
  return address.port;
}

/** 启动独立 Vite，只服务 production component fixture，不启用 Tauri E2E composition。 */
function startFixtureServer(port) {
  const environment = { ...process.env };
  delete environment.JA_E2E_DEV_PORT;
  const viteBin = join(repoRoot, "node_modules", "vite", "bin", "vite.js");
  const child = spawn(
    process.execPath,
    [
      viteBin,
      "--config",
      join(repoRoot, "apps", "desktop", "vite.config.ts"),
      "--host",
      "127.0.0.1",
      "--port",
      String(port),
      "--strictPort",
    ],
    {
      cwd: repoRoot,
      env: environment,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  const output = [];
  child.stdout.on("data", (chunk) => output.push(String(chunk)));
  child.stderr.on("data", (chunk) => output.push(String(chunk)));
  return { child, output };
}

/** 只终止本 runner 创建的 Vite 子进程，并等待其退出。 */
async function stopFixtureServer(server) {
  if (server.child.exitCode !== null) return;
  server.child.kill();
  await new Promise((resolvePromise) => {
    const timer = setTimeout(resolvePromise, 5_000);
    server.child.once("exit", () => {
      clearTimeout(timer);
      resolvePromise();
    });
  });
}

/** 等待独立 fixture 可访问；失败只返回有界启动尾部，不泄漏宿主环境。 */
async function waitForFixture(url, server) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (server.child.exitCode !== null)
      throw new Error(
        `fixture server exited (${server.child.exitCode}): ${server.output.join("").slice(-1_000)}`,
      );
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // 监听建立前的连接拒绝是预期状态。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error("fixture server did not become ready within 20 seconds");
}

/** 读取 fixture 的闭集计数与计时，拒绝任何正文或 workspace 路径进入报告。 */
async function telemetry(page) {
  return page.evaluate(() => {
    const source = globalThis.__JA_REVIEW_PERFORMANCE_FIXTURE__?.telemetry;
    if (source === undefined) throw new Error("performance fixture telemetry is missing");
    return structuredClone(source);
  });
}

/** 文件完成渲染的条件同时绑定 fileId 和最终标题，兼容统一/双栏两种真实查看模式。 */
async function waitForDiff(page, fileId) {
  await page
    .locator(`[data-review-selected-file-id="${fileId}"] .ja-review-diff-header strong`)
    .waitFor({ state: "visible", timeout: 10_000 });
}

/** 切到生产文件树的平铺模式，使前四个固定 hot 文件处于同一虚拟窗口。 */
async function chooseFlatGrouping(page) {
  await page.getByRole("button", { name: /^文件分组：/u }).click();
  const menu = page.locator("[role='menu'][data-state='open']").last();
  await menu.waitFor({ state: "visible", timeout: 5_000 });
  await menu.getByRole("menuitemradio", { name: "平铺", exact: true }).click();
  await page.locator('[data-review-file-id="file-00003"]').waitFor({
    state: "visible",
    timeout: 5_000,
  });
}

/** 单轮覆盖冷打开、普通切换、新鲜回看、快速竞态与隐藏 workspace 切换。 */
async function measureSample(page, fixtureUrl) {
  await page.goto(fixtureUrl, { waitUntil: "domcontentloaded" });
  await waitForDiff(page, "file-00000");
  const cold = await page.evaluate(
    () => performance.now() - globalThis.__JA_REVIEW_PERFORMANCE_FIXTURE__.telemetry.startedAt,
  );
  await chooseFlatGrouping(page);

  const fileOne = page.locator('[data-review-file-id="file-00001"]');
  const switchStarted = performance.now();
  await fileOne.click();
  await waitForDiff(page, "file-00001");
  const fileSwitch = performance.now() - switchStarted;

  const fileZero = page.locator('[data-review-file-id="file-00000"]');
  const revisitStarted = performance.now();
  await fileZero.click();
  await waitForDiff(page, "file-00000");
  const fileRevisit = performance.now() - revisitStarted;

  await page.locator('[data-review-file-id="file-00002"]').click();
  await page.waitForFunction(
    () => globalThis.__JA_REVIEW_PERFORMANCE_FIXTURE__?.telemetry.counts.fileDiff === 4,
  );
  await page.locator('[data-review-file-id="file-00003"]').click();
  await waitForDiff(page, "file-00003");
  await page.waitForTimeout(latestWinsSettlingDelay());
  assert.equal(
    await page
      .locator("[data-review-selected-file-id]")
      .getAttribute("data-review-selected-file-id"),
    "file-00003",
  );

  const visibleTelemetry = await telemetry(page);
  const beforeHidden = { ...visibleTelemetry.counts };
  await page.evaluate(() => {
    const fixture = globalThis.__JA_REVIEW_PERFORMANCE_FIXTURE__;
    fixture.setActive(false);
    fixture.switchWorkspace();
  });
  await page.locator('[data-fixture-active="false"]').waitFor({ state: "attached" });
  await page.waitForTimeout(100);
  const afterHidden = await telemetry(page);
  const diffIds = afterHidden.samples
    .filter((sample) => sample.command === "fileDiff")
    .map((sample) => sample.fileId);
  assert.equal(diffIds.filter((fileId) => fileId === "file-00000").length, 2);
  assert.deepEqual(
    new Set(diffIds),
    new Set(["file-00000", "file-00001", "file-00002", "file-00003"]),
  );
  assert.equal(afterHidden.counts.catalog, 1);
  assert.equal(afterHidden.counts.snapshot, 1);
  assert.equal(afterHidden.counts.fileDiff, 5);
  assert.equal(afterHidden.counts.catalog - beforeHidden.catalog, 0);
  assert.equal(afterHidden.counts.snapshot - beforeHidden.snapshot, 0);
  assert.equal(afterHidden.counts.fileDiff - beforeHidden.fileDiff, 0);
  assert.equal(afterHidden.counts.subscribe - beforeHidden.subscribe, 0);
  assert.equal(afterHidden.counts.subscribe, afterHidden.counts.unsubscribe);
  return {
    coldReadyMs: cold,
    fileSwitchMs: fileSwitch,
    fileRevisitMs: fileRevisit,
    hiddenCatalogDelta: afterHidden.counts.catalog - beforeHidden.catalog,
    hiddenSnapshotDelta: afterHidden.counts.snapshot - beforeHidden.snapshot,
    hiddenFileDiffDelta: afterHidden.counts.fileDiff - beforeHidden.fileDiff,
    hiddenSubscribeDelta: afterHidden.counts.subscribe - beforeHidden.subscribe,
    latestWins: true,
    subscriptionsBalanced: afterHidden.counts.subscribe === afterHidden.counts.unsubscribe,
    invokeCounts: afterHidden.counts,
    invokeSamples: afterHidden.samples,
  };
}

/** 等待慢请求必然完成，再确认迟到结果没有覆盖当前文件；上限仍远小于单步超时。 */
function latestWinsSettlingDelay() {
  return 200;
}

/** 执行可重复浏览器性能采样并写入边界明确的 JSON 报告。 */
export async function runBrowserPerformance(options) {
  await mkdir(options.evidenceDirectory, { recursive: true });
  const port = await reservePort();
  const fixtureUrl = `http://127.0.0.1:${port}${FIXTURE_PATH}?files=${options.fileCount}&delayMs=${options.adapterDelayMs}`;
  const server = startFixtureServer(port);
  let browser;
  try {
    await waitForFixture(fixtureUrl, server);
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext({ viewport: { width: 1_000, height: 820 } });
    const page = await context.newPage();
    const samples = [];
    for (let index = 0; index < options.samples; index += 1) {
      samples.push(await measureSample(page, fixtureUrl));
    }
    await context.close();
    const report = {
      contractVersion: 1,
      runtime: "production_controller_browser_fixture",
      nativeVerified: false,
      nativeGitVerified: false,
      fileCount: options.fileCount,
      samples: options.samples,
      adapterDelayMs: options.adapterDelayMs,
      metrics: {
        coldReady: summarize(samples.map((sample) => sample.coldReadyMs)),
        fileSwitch: summarize(samples.map((sample) => sample.fileSwitchMs)),
        fileRevisit: summarize(samples.map((sample) => sample.fileRevisitMs)),
        snapshotAdapter: summarize(
          samples.flatMap((sample) =>
            sample.invokeSamples
              .filter((entry) => entry.command === "snapshot")
              .map((entry) => entry.durationMs),
          ),
        ),
        fileDiffAdapter: summarize(
          samples.flatMap((sample) =>
            sample.invokeSamples
              .filter((entry) => entry.command === "fileDiff")
              .map((entry) => entry.durationMs),
          ),
        ),
      },
      correctness: {
        allSamplesPassed: true,
        latestWins: samples.every((sample) => sample.latestWins),
        hiddenCatalogDelta: samples.reduce((sum, sample) => sum + sample.hiddenCatalogDelta, 0),
        hiddenSnapshotDelta: samples.reduce((sum, sample) => sum + sample.hiddenSnapshotDelta, 0),
        hiddenFileDiffDelta: samples.reduce((sum, sample) => sum + sample.hiddenFileDiffDelta, 0),
        hiddenSubscribeDelta: samples.reduce((sum, sample) => sum + sample.hiddenSubscribeDelta, 0),
        subscriptionsBalanced: samples.every((sample) => sample.subscriptionsBalanced),
        perSampleInvokeCounts: samples.map((sample) => sample.invokeCounts),
      },
    };
    validateBrowserPerformanceReport(report);
    const reportPath = join(options.evidenceDirectory, "review-performance-browser-report.json");
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
    return { report, reportPath };
  } finally {
    if (browser !== undefined) await browser.close();
    await stopFixtureServer(server);
  }
}

/** CLI 入口只在直接执行时运行，单测 import 不产生浏览器或文件副作用。 */
async function main() {
  const result = await runBrowserPerformance(parseArguments(process.argv.slice(2)));
  console.log(`JA_REVIEW_PERFORMANCE_BROWSER_PASS ${result.reportPath}`);
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
