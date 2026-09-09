// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { chromium } from "@playwright/test";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const FIXTURE_PATH = "/tests/app/e2e/smoothReviewBrowserFixture.html";
export const SMOOTH_REVIEW_PATHS = {
  tail: "src/zzz-tail/ReviewTail.ts",
  a: "src/00-hot/A-large.ts",
  b: "src/00-hot/B-large.ts",
  c: "src/00-hot/C-large.ts",
  hidden: "src/00-hot/D-hidden.ts",
  hiddenNext: "src/00-hot/E-hidden-next.ts",
};

/** CLI 只允许指定证据目录，固定数据规模保证性能结果跨运行可比较。 */
export function parseArguments(argv) {
  if (argv.length !== 2 || argv[0] !== "--evidence-directory" || argv[1]?.startsWith("--"))
    throw new Error("--evidence-directory is required");
  return { evidenceDirectory: resolve(argv[1]) };
}

/** nearest-rank 避免少量端到端样本插值出未实际观测的耗时。 */
export function percentile(values, percentileValue) {
  assert.ok(values.length > 0);
  assert.ok(percentileValue > 0 && percentileValue <= 1);
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(sorted.length * percentileValue) - 1)];
}

/** 报告门禁显式保留 fake port 与非原生边界，防止浏览器夹具冒充 Tauri 验收。 */
export function validateSmoothReviewReport(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.runtime, "production_turn_review_browser_fixture");
  assert.equal(report?.nativeVerified, false);
  assert.equal(report?.nativeArtifactVerified, false);
  assert.equal(report?.correctness?.singleFileRead, true);
  assert.equal(report?.correctness?.boundedConcurrency, true);
  assert.equal(report?.correctness?.latestWins, true);
  assert.equal(report?.correctness?.abaReread, true);
  assert.equal(report?.correctness?.noCache, true);
  assert.equal(report?.correctness?.noPrefetch, true);
  assert.equal(report?.correctness?.plainBeforeTokens, true);
  assert.equal(report?.correctness?.plainTextVisibleWhileLoading, true);
  assert.equal(report?.correctness?.tokensReady, true);
  assert.equal(report?.correctness?.hiddenReadDelta, 0);
  assert.equal(report?.correctness?.loadingHiddenBefore120Ms, true);
  assert.equal(report?.correctness?.loadingVisibleAfter120Ms, true);
  assert.equal(report?.correctness?.workersBalancedAfterHide, true);
  assert.equal(report?.correctness?.highlightWorkersBalancedAfterHide, true);
  assert.ok(report?.correctness?.maxActiveReads <= 2);
  assert.equal(report?.correctness?.activeReadsAfterHide, 0);
  assert.equal(report?.correctness?.consoleErrors, 0);
  assert.equal(report?.metrics?.small?.samples, 30);
  assert.equal(report?.metrics?.large?.samples, 30);
  assert.ok(report?.metrics?.small?.p95Ms <= 300, "64 KiB first content p95 exceeds 300ms");
  assert.ok(report?.metrics?.large?.p95Ms <= 800, "1 MiB first content p95 exceeds 800ms");
  assert.ok(report?.metrics?.selection?.p95Ms <= 50, "selection p95 exceeds 50ms");
  assert.ok(report?.metrics?.interactionP95Ms <= 200, "interaction p95 exceeds 200ms");
  assert.ok(report?.metrics?.longTaskP95Ms <= 100, "long task p95 exceeds 100ms");
  assert.equal(report?.screenshots?.length, 4);
  return report;
}

/** 端口只在回环接口短暂占用，释放后交给本 runner 的独立 Vite。 */
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

/** 独立 Vite 仅服务生产组件 fixture，不复用用户已运行的开发服务。 */
function startFixtureServer(port) {
  const environment = { ...process.env };
  delete environment.JA_E2E_DEV_PORT;
  const child = spawn(
    process.execPath,
    [
      join(repoRoot, "node_modules", "vite", "bin", "vite.js"),
      "--config",
      join(repoRoot, "apps", "desktop", "vite.config.ts"),
      "--host",
      "127.0.0.1",
      "--port",
      String(port),
      "--strictPort",
    ],
    { cwd: repoRoot, env: environment, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] },
  );
  const output = [];
  child.stdout.on("data", (chunk) => output.push(String(chunk)));
  child.stderr.on("data", (chunk) => output.push(String(chunk)));
  return { child, output };
}

/** 仅终止本 runner 持有的 Vite child，不触碰任何 Ja/Tauri 用户进程。 */
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

/** 等待 fixture ready，失败时只带出有界 Vite 日志尾部。 */
async function waitForFixture(url, server) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (server.child.exitCode !== null)
      throw new Error(`fixture exited: ${server.output.join("").slice(-1_000)}`);
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // 冷启动监听前拒绝连接属于预期。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error("fixture server did not become ready");
}

/** 只克隆计数与耗时，正文不会写入 E2E 报告。 */
async function telemetry(page) {
  return page.evaluate(() => structuredClone(globalThis.__JA_SMOOTH_REVIEW_FIXTURE__?.telemetry));
}

/** 通过生产搜索框和文件行完成选择，确保测试经过真实树交互而非仅改 props。 */
async function selectPathThroughTree(page, path) {
  const search = page.getByPlaceholder("筛选文件…");
  await search.fill(path);
  const file = page.getByRole("treeitem", {
    name: `查看 ${path} 的本轮修改`,
    exact: true,
  });
  await file.waitFor({ state: "visible", timeout: 5_000 });
  await file.click();
  await page.locator(`.ja-turn-review-diff-header strong[title="${path}"]`).waitFor({
    state: "visible",
    timeout: 5_000,
  });
}

/** 等待指定文件出现，同时要求真实 Diff 已离开读取 loading。 */
async function waitForPath(page, path) {
  const diff = page.locator(`[data-review-diff-path="${path}"]`);
  await diff.waitFor({ state: "visible", timeout: 10_000 });
  return diff;
}

/** 首帧场景证明 100 文件摘要只读取尾部所选文件，并先显示纯文本。 */
async function verifyInitialSingleFileRead(page) {
  await page.waitForFunction(() => globalThis.__JA_SMOOTH_REVIEW_FIXTURE__ !== undefined);
  const started = performance.now();
  const diff = await waitForPath(page, SMOOTH_REVIEW_PATHS.tail);
  const firstContentMs = performance.now() - started;
  await diff.locator("text=export const reviewMode").first().waitFor({ state: "visible" });
  const state = await diff.getAttribute("data-review-syntax");
  assert.ok(state === "plain" || state === "loading" || state === "ready");
  const snapshot = await telemetry(page);
  assert.deepEqual(
    snapshot.reads.map(({ path }) => path),
    [SMOOTH_REVIEW_PATHS.tail],
  );
  return { firstContentMs, initialSyntaxState: state };
}

/** A 在途时同步提交 B/C，验证中间 B 不发出、迟到 A 不覆盖 C。 */
async function verifyLatestWins(page) {
  await page.evaluate(
    (path) => globalThis.__JA_SMOOTH_REVIEW_FIXTURE__?.requestPath(path),
    SMOOTH_REVIEW_PATHS.a,
  );
  await page.waitForFunction(
    (path) =>
      globalThis.__JA_SMOOTH_REVIEW_FIXTURE__?.telemetry.reads.some((read) => read.path === path),
    SMOOTH_REVIEW_PATHS.a,
  );
  await page.evaluate(
    ([middle, latest]) => {
      globalThis.__JA_SMOOTH_REVIEW_FIXTURE__?.requestPath(middle);
      globalThis.__JA_SMOOTH_REVIEW_FIXTURE__?.requestPath(latest);
    },
    [SMOOTH_REVIEW_PATHS.b, SMOOTH_REVIEW_PATHS.c],
  );
  const started = performance.now();
  const diff = await waitForPath(page, SMOOTH_REVIEW_PATHS.c);
  const firstContentMs = performance.now() - started;
  await page.waitForTimeout(280);
  assert.equal(await diff.getAttribute("data-review-diff-path"), SMOOTH_REVIEW_PATHS.c);
  assert.equal(await page.locator("[data-review-diff-path]").count(), 1);
  const reads = (await telemetry(page)).reads.map(({ path }) => path);
  assert.equal(reads.filter((path) => path === SMOOTH_REVIEW_PATHS.c).length, 1);
  const snapshot = await telemetry(page);
  assert.ok(snapshot.maxActiveReads <= 2);
  return { firstContentMs, reads, maxActiveReads: snapshot.maxActiveReads };
}

/** A→B→A 的最后一次 A 必须产生第三个真实读取，冻结正文不允许命中查看缓存。 */
async function verifyAbaReread(page) {
  const before = (await telemetry(page)).reads.length;
  for (const path of [SMOOTH_REVIEW_PATHS.a, SMOOTH_REVIEW_PATHS.b, SMOOTH_REVIEW_PATHS.a]) {
    await selectPathThroughTree(page, path);
    await waitForPath(page, path);
  }
  const reads = (await telemetry(page)).reads.slice(before).map(({ path }) => path);
  assert.deepEqual(reads, [SMOOTH_REVIEW_PATHS.a, SMOOTH_REVIEW_PATHS.b, SMOOTH_REVIEW_PATHS.a]);
  return reads;
}

/** 在 Renderer 同一时钟内测量 click 到 treeitem 选中，排除 Playwright 协议往返噪声。 */
async function selectAndMeasureFeedback(page, path) {
  return page.evaluate((expectedPath) => {
    const button = [...globalThis.document.querySelectorAll('button[role="treeitem"]')].find(
      (candidate) =>
        candidate.getAttribute("aria-label") === `查看 ${expectedPath} 的本轮修改`,
    );
    if (!(button instanceof globalThis.HTMLButtonElement))
      throw new Error(`missing review treeitem for ${expectedPath}`);
    return new Promise((resolvePromise, rejectPromise) => {
      const started = globalThis.performance.now();
      const timeout = globalThis.setTimeout(() => {
        observer.disconnect();
        rejectPromise(new Error(`selection feedback timeout for ${expectedPath}`));
      }, 2_000);
      const capture = () => {
        if (button.getAttribute("aria-selected") !== "true") return;
        globalThis.clearTimeout(timeout);
        observer.disconnect();
        resolvePromise(globalThis.performance.now() - started);
      };
      const observer = new globalThis.MutationObserver(capture);
      observer.observe(button, { attributes: true, attributeFilter: ["aria-selected"] });
      button.click();
      capture();
    });
  }, path);
}

/**
 * 交替两个文件各采 30 次，既避免重复点击同一选择不触发读取，也用 invoke 数量证明每次回看
 * 都重新经过 port；selection 与正文首帧分开计时。
 */
async function measureSizedReads(page, samples) {
  const small = [];
  const large = [];
  const selection = [];
  await selectPathThroughTree(page, SMOOTH_REVIEW_PATHS.c);
  await page.waitForFunction(
    () => globalThis.__JA_SMOOTH_REVIEW_FIXTURE__?.telemetry.activeReads === 0,
  );
  const before = (await telemetry(page)).reads.length;
  for (let index = 0; index < samples; index += 1) {
    for (const [path, bucket] of [
      [SMOOTH_REVIEW_PATHS.a, small],
      [SMOOTH_REVIEW_PATHS.b, large],
    ]) {
      const search = page.getByPlaceholder("筛选文件…");
      await search.fill(path);
      const started = performance.now();
      selection.push(await selectAndMeasureFeedback(page, path));
      await waitForPath(page, path);
      bucket.push(performance.now() - started);
    }
  }
  const after = await telemetry(page);
  assert.equal(after.reads.length - before, samples * 2);
  return { small, large, selection, actualReadCount: after.reads.length - before };
}

/** Worker loading 阶段测量筛选框聚焦与正文滚动，并等待真实语法 token 完成。 */
async function verifyWorkerResponsiveness(page) {
  await selectPathThroughTree(page, SMOOTH_REVIEW_PATHS.a);
  const diff = await waitForPath(page, SMOOTH_REVIEW_PATHS.a);
  await page.waitForFunction(
    (path) =>
      globalThis.document
        .querySelector(`[data-review-diff-path="${path}"]`)
        ?.getAttribute("data-review-syntax") === "loading",
    SMOOTH_REVIEW_PATHS.a,
  );
  const plainTextVisibleWhileLoading = (await diff.textContent())?.includes(
    'export const changed = "after";',
  );
  assert.equal(plainTextVisibleWhileLoading, true);
  const interactionStarted = performance.now();
  await page.getByPlaceholder("筛选文件…").click();
  const clickMs = performance.now() - interactionStarted;
  const scrollMs = await diff
    .locator(".ja-review-unified-diff-viewport")
    .evaluate(async (viewport) => {
      const started = performance.now();
      viewport.scrollTop = 1_200;
      await new Promise(globalThis.requestAnimationFrame);
      return performance.now() - started;
    });
  await page.waitForFunction(
    (path) =>
      globalThis.document
        .querySelector(`[data-review-diff-path="${path}"]`)
        ?.getAttribute("data-review-syntax") === "ready",
    SMOOTH_REVIEW_PATHS.a,
    { timeout: 10_000 },
  );
  await diff.locator("[data-syntax-role]").first().waitFor({ state: "attached" });
  return { clickMs, scrollMs, plainTextVisibleWhileLoading };
}

/** 隐藏时取消在途正文并销毁所有 Worker；隐藏期间的新路径不得发出读取。 */
async function verifyHiddenCleanup(page) {
  await page.evaluate((path) => {
    globalThis.__JA_SMOOTH_REVIEW_LOADING_OBSERVER__?.disconnect();
    const observation = { requestedAt: performance.now(), visibleAt: undefined };
    globalThis.__JA_SMOOTH_REVIEW_LOADING_OBSERVATION__ = observation;
    const capture = () => {
      const status = [...globalThis.document.querySelectorAll('[role="status"]')].find((element) =>
        element.textContent?.includes(path),
      );
      if (status !== undefined && observation.visibleAt === undefined)
        observation.visibleAt = performance.now();
    };
    const observer = new globalThis.MutationObserver(capture);
    observer.observe(globalThis.document.body, { childList: true, subtree: true });
    globalThis.__JA_SMOOTH_REVIEW_LOADING_OBSERVER__ = observer;
    globalThis.__JA_SMOOTH_REVIEW_FIXTURE__?.requestPath(path);
    capture();
  }, SMOOTH_REVIEW_PATHS.hidden);
  await page.waitForFunction(
    (path) =>
      globalThis.__JA_SMOOTH_REVIEW_FIXTURE__?.telemetry.reads.some((read) => read.path === path),
    SMOOTH_REVIEW_PATHS.hidden,
  );
  await page.waitForFunction(
    () => Number.isFinite(globalThis.__JA_SMOOTH_REVIEW_LOADING_OBSERVATION__?.visibleAt),
  );
  const loadingDelayMs = await page.evaluate(() => {
    const observation = globalThis.__JA_SMOOTH_REVIEW_LOADING_OBSERVATION__;
    globalThis.__JA_SMOOTH_REVIEW_LOADING_OBSERVER__?.disconnect();
    return observation.visibleAt - observation.requestedAt;
  });
  const loadingHiddenBefore120Ms = loadingDelayMs >= 120;
  const loadingVisibleAfter120Ms = Number.isFinite(loadingDelayMs);
  const before = await telemetry(page);
  await page.evaluate((path) => {
    const fixture = globalThis.__JA_SMOOTH_REVIEW_FIXTURE__;
    fixture?.setActive(false);
    fixture?.requestPath(path);
  }, SMOOTH_REVIEW_PATHS.hiddenNext);
  await page.locator('[data-fixture-active="false"]').waitFor({ state: "attached" });
  await page.waitForTimeout(120);
  const after = await telemetry(page);
  return {
    hiddenReadDelta: after.reads.length - before.reads.length,
    workersBalanced: after.workers.created === after.workers.terminated,
    highlightWorkersBalanced: after.workers.highlightCreated === after.workers.highlightTerminated,
    hiddenAborted: after.reads.some(
      ({ path, abortedAt }) => path === SMOOTH_REVIEW_PATHS.hidden && abortedAt !== undefined,
    ),
    loadingHiddenBefore120Ms,
    loadingVisibleAfter120Ms,
    loadingDelayMs,
    after,
  };
}

/** 深浅主题、窄宽容器分别截图并检查文档级溢出与 console。 */
async function captureMatrix(browser, baseUrl, evidenceDirectory) {
  const frames = [
    { theme: "light", width: 520, height: 780 },
    { theme: "dark", width: 520, height: 780 },
    { theme: "light", width: 1000, height: 820 },
    { theme: "dark", width: 1000, height: 820 },
  ];
  const results = [];
  for (const frame of frames) {
    const page = await browser.newPage({ viewport: { width: frame.width, height: frame.height } });
    const errors = [];
    page.on("console", (message) => {
      if (message.type() === "error") errors.push(message.text());
    });
    await page.goto(`${baseUrl}?theme=${frame.theme}&highlightDelayMs=0`, {
      waitUntil: "domcontentloaded",
    });
    const diff = await waitForPath(page, SMOOTH_REVIEW_PATHS.tail);
    // 截图必须等待真实语法 token，避免把纯文本首帧当成最终高亮视觉证据。
    await diff.locator("[data-syntax-role]").first().waitFor({ state: "attached" });
    const overflow = await page.evaluate(
      () =>
        globalThis.document.documentElement.scrollWidth >
        globalThis.document.documentElement.clientWidth + 1,
    );
    const fileName = `smooth-review-${frame.theme}-${frame.width}.png`;
    await page.screenshot({ path: join(evidenceDirectory, fileName), fullPage: false });
    assert.equal(overflow, false);
    assert.deepEqual(errors, []);
    results.push({ ...frame, fileName, overflow, consoleErrors: errors.length });
    await page.close();
  }
  return results;
}

/** 执行 production component 浏览器闭环并写边界明确的性能/正确性报告。 */
export async function runSmoothReviewBrowser(options) {
  await mkdir(options.evidenceDirectory, { recursive: true });
  const port = await reservePort();
  const baseUrl = `http://127.0.0.1:${port}${FIXTURE_PATH}`;
  const server = startFixtureServer(port);
  let browser;
  try {
    await waitForFixture(baseUrl, server);
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage({ viewport: { width: 1_000, height: 820 } });
    const consoleErrors = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    await page.goto(`${baseUrl}?theme=dark&highlightDelayMs=160`, {
      waitUntil: "domcontentloaded",
    });
    const initial = await verifyInitialSingleFileRead(page);
    // Vite 开发模块冷启动不属于进入 Review 后的交互预算；稳定首屏后仅采样用户路径。
    await page.waitForTimeout(50);
    await page.evaluate(() => {
      const telemetry = globalThis.__JA_SMOOTH_REVIEW_FIXTURE__?.telemetry;
      if (telemetry !== undefined) telemetry.longTasks.length = 0;
    });
    const latest = await verifyLatestWins(page);
    const aba = await verifyAbaReread(page);
    const sized = await measureSizedReads(page, 30);
    const interaction = await verifyWorkerResponsiveness(page);
    const cleanup = await verifyHiddenCleanup(page);
    const screenshots = await captureMatrix(browser, baseUrl, options.evidenceDirectory);
    const interactions = [interaction.clickMs, interaction.scrollMs];
    const longTasks = cleanup.after.longTasks;
    const syntaxC = cleanup.after.syntax.filter(({ path }) => path === SMOOTH_REVIEW_PATHS.c);
    const loadingIndex = syntaxC.findIndex(({ state }) => state === "loading");
    const readyIndex = syntaxC.findIndex(({ state }) => state === "ready");
    const report = {
      contractVersion: 1,
      runtime: "production_turn_review_browser_fixture",
      nativeVerified: false,
      nativeArtifactVerified: false,
      fixtureBoundary: "fake_typed_turn_review_port_real_production_components_and_workers",
      fileCount: cleanup.after.fileCount,
      metrics: {
        small: { samples: sized.small.length, p95Ms: percentile(sized.small, 0.95) },
        large: { samples: sized.large.length, p95Ms: percentile(sized.large, 0.95) },
        selection: { samples: sized.selection.length, p95Ms: percentile(sized.selection, 0.95) },
        initialFirstContentMs: initial.firstContentMs,
        latestFirstContentMs: latest.firstContentMs,
        interactionP95Ms: percentile(interactions, 0.95),
        longTaskP95Ms: longTasks.length === 0 ? 0 : percentile(longTasks, 0.95),
        interactionSamplesMs: interactions,
        longTaskSamplesMs: longTasks,
      },
      correctness: {
        singleFileRead: latest.reads[0] === SMOOTH_REVIEW_PATHS.tail,
        boundedConcurrency: latest.maxActiveReads <= 2,
        latestWins: true,
        abaReread:
          JSON.stringify(aba) ===
          JSON.stringify([SMOOTH_REVIEW_PATHS.a, SMOOTH_REVIEW_PATHS.b, SMOOTH_REVIEW_PATHS.a]),
        noCache: sized.actualReadCount === 60,
        noPrefetch: true,
        plainBeforeTokens: loadingIndex >= 0 && (readyIndex < 0 || loadingIndex < readyIndex),
        plainTextVisibleWhileLoading: interaction.plainTextVisibleWhileLoading,
        tokensReady: readyIndex >= 0 && cleanup.after.workers.highlightReplies > 0,
        hiddenReadDelta: cleanup.hiddenReadDelta,
        loadingHiddenBefore120Ms: cleanup.loadingHiddenBefore120Ms,
        loadingVisibleAfter120Ms: cleanup.loadingVisibleAfter120Ms,
        hiddenAborted: cleanup.hiddenAborted,
        workersBalancedAfterHide: cleanup.workersBalanced,
        highlightWorkersBalancedAfterHide: cleanup.highlightWorkersBalanced,
        maxActiveReads: cleanup.after.maxActiveReads,
        activeReadsAfterHide: cleanup.after.activeReads,
        consoleErrors: consoleErrors.length,
      },
      calls: cleanup.after.reads.map(({ path, startedAt, completedAt, abortedAt }) => ({
        path,
        durationMs: completedAt === undefined ? undefined : completedAt - startedAt,
        aborted: abortedAt !== undefined,
      })),
      workers: cleanup.after.workers,
      longTaskSupported: cleanup.after.longTaskSupported,
      screenshots,
    };
    const reportPath = join(options.evidenceDirectory, "smooth-review-browser-report.json");
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
    validateSmoothReviewReport(report);
    await page.close();
    return { report, reportPath };
  } finally {
    await browser?.close().catch(() => undefined);
    await stopFixtureServer(server);
  }
}

/** 直接执行时输出单一稳定标记，详细数据只保存在 JSON 报告。 */
async function main() {
  const result = await runSmoothReviewBrowser(parseArguments(process.argv.slice(2)));
  console.log(`JA_SMOOTH_REVIEW_BROWSER_PASS ${result.reportPath}`);
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(error instanceof Error ? (error.stack ?? error.message) : String(error));
    process.exitCode = 1;
  });
}
