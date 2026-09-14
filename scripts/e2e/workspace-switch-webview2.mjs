// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Windows Tauri/WebView2 workspace 切换验收 runner。
 *
 * 该 runner 复用 review-redesign-production 的真实 JVM JAR、隔离 profile、WebView2
 * CDP/EdgeDriver 和进程清理边界，额外建立两个真实 Git workspace 与持久 Thread，
 * 通过 loopback Responses Provider 产生历史正文，再测量 A/B 热切换和滚动恢复。
 * baseline 阶段允许记录旧行为；after 阶段才对标题行 spinner、非空 Timeline、
 * 显式 Thread identity 和隐藏 Review snapshot 增量执行改后门禁。
 */

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { responseStream } from "./conversation-recovery-fixture.mjs";
import {
  createReviewGitFixture,
  parseArguments as parseProductionArguments,
  runProduction,
  validateEdgeDriver,
  validateToolchain,
} from "./review-redesign-production.mjs";
import { installReviewInvokeProbe } from "./review-redesign-webview2-driver.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_SAMPLES = 5;
const MIN_SAMPLES = 3;
const MAX_SAMPLES = 20;
const REQUIRED_IGNORED_FILES = 4_500;
const REQUIRED_UNTRACKED_FILES = 2_000;
const SWITCH_P95_BUDGET_MS = 2_000;
const HISTORY_TURNS = 4;
const PROJECT_LABELS = Object.freeze({ a: "切换验收 A", b: "切换验收 B" });

const BASE_NO_VALUE_ARGUMENTS = new Set(["--preflight-only", "--hidden-window"]);

/**
 * 合并生产 runner 的 launch/attach 参数与本 runner 的阶段参数；本验收必须由隔离 launch
 * 提供 loopback 配置，拒绝 attach 以免误连用户已有 Provider 或 profile。
 */
export function parseArguments(argv) {
  const baseArguments = [];
  let samples = DEFAULT_SAMPLES;
  let phase = "after";
  let baselineReport;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--samples" || argument === "--phase" || argument === "--baseline-report") {
      const value = argv[index + 1];
      if (value === undefined || value.startsWith("--"))
        throw new Error(`missing value for ${argument}`);
      if (argument === "--samples") samples = Number(value);
      else if (argument === "--phase") phase = value;
      else baselineReport = resolve(value);
      index += 1;
      continue;
    }
    baseArguments.push(argument);
    if (!BASE_NO_VALUE_ARGUMENTS.has(argument)) {
      const value = argv[index + 1];
      if (value !== undefined && !value.startsWith("--")) {
        baseArguments.push(value);
        index += 1;
      }
    }
  }
  if (!baseArguments.includes("--scope")) baseArguments.push("--scope", "git");
  const production = parseProductionArguments(baseArguments);
  if (production.cdpEndpoint !== undefined)
    throw new Error("workspace switch runner requires an isolated launch, not attach mode");
  if (production.fixture !== "full")
    throw new Error("workspace switch runner requires the full Git pressure fixture");
  if (!Number.isSafeInteger(samples) || samples < MIN_SAMPLES || samples > MAX_SAMPLES) {
    throw new Error(`--samples must be an integer between ${MIN_SAMPLES} and ${MAX_SAMPLES}`);
  }
  if (!(["baseline", "after"].includes(phase)))
    throw new Error("--phase must be baseline or after");
  if (production.ignoredFiles < REQUIRED_IGNORED_FILES) {
    throw new Error(`--ignored-files must be at least ${REQUIRED_IGNORED_FILES}`);
  }
  if (production.untrackedFiles < REQUIRED_UNTRACKED_FILES) {
    throw new Error(`--untracked-files must be at least ${REQUIRED_UNTRACKED_FILES}`);
  }
  if (!baseArguments.includes("--cargo-target-directory")) {
    production.cargoTargetDirectory = join(repoRoot, "target", "codex-workspace-switch");
  }
  return { ...production, samples, phase, baselineReport };
}

/**
 * 使用 nearest-rank 计算小样本端到端分位数，确保 p50/p95 都对应真实观测样本，
 * 不用插值制造并未发生过的耗时。
 */
export function percentile(values, percentileValue) {
  assert.ok(values.length > 0, "percentile requires samples");
  assert.ok(percentileValue > 0 && percentileValue <= 1, "percentile must be within (0, 1]");
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(sorted.length * percentileValue) - 1)];
}

/**
 * 汇总实际 click 到目标正文出现的耗时；保留 raw samples 以便改前/改后在同一机器重算。
 */
export function summarize(values) {
  assert.ok(values.length > 0, "summarize requires samples");
  assert.ok(values.every((value) => Number.isFinite(value) && value >= 0));
  return {
    count: values.length,
    minMs: Math.min(...values),
    p50Ms: percentile(values, 0.5),
    p95Ms: percentile(values, 0.95),
    maxMs: Math.max(...values),
    samples: [...values],
  };
}

/**
 * 校验跨阶段报告的闭集字段；baseline 允许暴露旧 loading/scroll 事实，after 才要求
 * 新的标题行 spinner、非空恢复、滚动位置和 ChatTimeline Thread identity 全部闭环。
 */
export function validateWorkspaceSwitchReport(report) {
  assert.equal(report?.schemaVersion, 1);
  assert.equal(report?.runtime?.surface, "tauri_webview2");
  assert.equal(report?.runtime?.platform, "win32");
  assert.equal(report?.runtime?.boundary, "jvm_jar");
  assert.equal(report?.runtime?.nativeImageVerified, false);
  assert.equal(report?.provider?.kind, "deterministic_loopback");
  assert.equal(report?.provider?.externalCalls, 0);
  assert.equal(report?.fixture?.projectCount, 2);
  assert.equal(report?.fixture?.ignoredFiles, REQUIRED_IGNORED_FILES);
  assert.equal(report?.fixture?.untrackedFiles, REQUIRED_UNTRACKED_FILES);
  assert.equal(report?.correctness?.hiddenReviewSnapshotDelta, 0);
  assert.equal(report?.correctness?.noBlankTimeline, true);
  assert.ok(Number.isInteger(report?.correctness?.blankFrameCount) && report.correctness.blankFrameCount >= 0, `blankFrameCount=${report?.correctness?.blankFrameCount}`);
  assert.ok(Number.isInteger(report?.correctness?.paintFrameCount) && report.correctness.paintFrameCount > 0, `paintFrameCount=${report?.correctness?.paintFrameCount}`);
  assert.equal(report?.correctness?.projectsScoped, true);
  assert.ok(report?.metrics?.aToB?.count >= MIN_SAMPLES);
  assert.ok(report?.metrics?.bToA?.count >= MIN_SAMPLES);
  assert.ok(Number.isFinite(report.metrics.aToB.p50Ms));
  assert.ok(Number.isFinite(report.metrics.aToB.p95Ms));
  assert.ok(Number.isFinite(report.metrics.bToA.p50Ms));
  assert.ok(Number.isFinite(report.metrics.bToA.p95Ms));
  if (report.phase === "after") {
    assert.equal(report.correctness.spinnerLegacyPlaceholder, false);
    assert.equal(report.correctness.spinnerInRecentHeading, true);
    assert.equal(report.correctness.explicitThreadIdentity, true);
    assert.equal(report.correctness.scrollRestored, true);
    assert.equal(report.correctness.blankFrameCount, 0, `blankFrameCount=${report.correctness.blankFrameCount}`);
    assert.ok(report.correctness.paintFrameCount > 0, `paintFrameCount=${report.correctness.paintFrameCount}`);
    assert.ok(report.metrics.aToB.p95Ms <= SWITCH_P95_BUDGET_MS);
    assert.ok(report.metrics.bToA.p95Ms <= SWITCH_P95_BUDGET_MS);
  } else {
    assert.equal(report.phase, "baseline");
  }
  assert.equal(report.baseline?.absoluteOnly, report.baseline?.available !== true);
  return report;
}

/**
 * 读取同一 runner 产生的基线摘要，只接纳无路径、无正文依赖的 metrics 形状；基线读取
 * 是只读比较，不把旧报告当作当前产品的通过证据。
 */
export async function readBaselineReport(path) {
  const candidate = JSON.parse(await readFile(path, "utf8"));
  const aToB = candidate?.metrics?.aToB;
  const bToA = candidate?.metrics?.bToA;
  if (
    !Number.isFinite(aToB?.p50Ms) ||
    !Number.isFinite(aToB?.p95Ms) ||
    !Number.isFinite(bToA?.p50Ms) ||
    !Number.isFinite(bToA?.p95Ms)
  ) {
    throw new Error("baseline report has no workspace switch metrics");
  }
  return {
    metrics: {
      aToB: { p50Ms: aToB.p50Ms, p95Ms: aToB.p95Ms },
      bToA: { p50Ms: bToA.p50Ms, p95Ms: bToA.p95Ms },
    },
  };
}

/**
 * 接收一个完整 Responses 请求并限制 body 大小；测试只把最后一个 marker 分类，避免
 * Provider fixture 将历史正文或绝对路径写入报告。
 */
async function readRequestBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("loopback request exceeds 4 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/**
 * 启动仅绑定 127.0.0.1 的确定性 Responses Provider；所有成功回复复用真实 Java adapter
 * 能消费的 SSE 结构，外部网络请求永远不会进入本轮测试。
 */
export async function startSwitchFixture() {
  const attempts = [];
  const server = createServer(async (request, response) => {
    try {
      assert.equal(request.method, "POST");
      assert.equal(request.url, "/v1/responses");
      const payload = await readRequestBody(request);
      const input = typeof payload.input === "string" ? payload.input : JSON.stringify(payload.input);
      const matches = [...input.matchAll(/JA_SWITCH_HISTORY_([AB])_TURN_(\d+)/gu)];
      const latest = matches.at(-1);
      const project = latest?.[1] === "B" ? "b" : latest?.[1] === "A" ? "a" : "unknown";
      const turn = latest === undefined ? 0 : Number(latest[2]);
      const marker = latest === undefined ? "JA_SWITCH_HISTORY_UNKNOWN" : latest[0];
      attempts.push({ project, turn, status: 200 });
      response.writeHead(200, {
        "content-type": "text/event-stream",
        "cache-control": "no-store",
      });
      response.end(responseStream(marker, `switch_${attempts.length}`));
    } catch {
      attempts.push({ project: "unknown", turn: 0, status: 400 });
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: "loopback fixture rejected request" }));
    }
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  if (typeof address !== "object" || address === null) {
    server.close();
    throw new Error("loopback fixture failed to bind");
  }
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    attempts,
    /** 只关闭本 fixture listener 和其连接，不影响其它进程。 */
    async close() {
      server.closeAllConnections();
      await new Promise((resolvePromise) => server.close(resolvePromise));
    },
  };
}

/**
 * 通过真实 Git porcelain 统计压力目录；这一步验证 fixture 的 ignored/untracked 分类，
 * 防止只把配置参数写进报告却没有实际文件规模。
 */
export async function countPressureFiles(workspaceRoot) {
  const run = async (args) =>
    execFileAsync("git.exe", args, {
      cwd: workspaceRoot,
      windowsHide: true,
      timeout: 30_000,
      maxBuffer: 8 * 1024 * 1024,
    });
  const [ignored, untracked] = await Promise.all([
    run(["ls-files", "--others", "--ignored", "--exclude-standard", "-z", "--", ".review-ignored"]),
    run(["ls-files", "--others", "--exclude-standard", "-z", "--", "zz-review-untracked"]),
  ]);
  const count = (output) => output.split("\0").filter((entry) => entry.length > 0).length;
  return { ignoredFiles: count(ignored.stdout), untrackedFiles: count(untracked.stdout) };
}

/**
 * 在 runProduction 已创建的隔离根旁建立第二个真实 Git workspace；只使用本轮 temp root，
 * 让两个项目都拥有相同压力规模而不触碰用户目录。
 */
export async function createSecondaryWorkspace(workspaceRoot) {
  const secondaryRoot = join(dirname(workspaceRoot), "workspace-b");
  const startedAt = performance.now();
  const fixture = await createReviewGitFixture(secondaryRoot, {
    fixture: "full",
    ignoredFiles: REQUIRED_IGNORED_FILES,
    untrackedFiles: REQUIRED_UNTRACKED_FILES,
  });
  const pressure = await countPressureFiles(secondaryRoot);
  assert.equal(pressure.ignoredFiles, REQUIRED_IGNORED_FILES);
  assert.equal(pressure.untrackedFiles, REQUIRED_UNTRACKED_FILES);
  return {
    root: secondaryRoot,
    setupMs: performance.now() - startedAt,
    fixture,
    pressure,
  };
}

/**
 * 经 typed History adapter 请求两个 server-owned workspace 与 Thread；直接调用只用于准备
 * 持久 fixture，真正切换仍必须点击侧栏项目按钮并经过 workspace controller。
 */
export async function createProjectsAndThreads(page, workspaceRoot, secondaryRoot) {
  return page.evaluate(
    async ({ firstRoot, secondRoot, labels }) => {
      const { TauriHistoryAdapter } = await import("/src/api/tauri/history.ts");
      const history = new TauriHistoryAdapter();
      const create = async (cwd, displayName, title) => {
        const workspace = await history.workspaceOpen({ cwd, displayName });
        const thread = await history.threadCreate({
          cwd: workspace.root,
          title,
          providerId: "provider_e2e",
          modelId: "model_e2e",
          reasoningLevel: null,
          accessMode: "full_access",
          collaborationMode: "default",
        });
        return {
          workspaceId: workspace.workspaceId,
          threadId: thread.threadId,
          displayName: workspace.displayName,
        };
      };
      return {
        a: await create(firstRoot, labels.a, "切换验收 A 历史会话"),
        b: await create(secondRoot, labels.b, "切换验收 B 历史会话"),
      };
    },
    {
      firstRoot: workspaceRoot,
      secondRoot: secondaryRoot,
      labels: PROJECT_LABELS,
    },
  );
}

/**
 * 等待真实 Ja Shell、runtime status 和最近对话列表，排除静态 Vite preview 或半初始化窗口。
 */
async function waitForApplication(page, deadline = Date.now() + 60_000) {
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({ state: "visible", timeout: timeout() });
  try {
    await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
      state: "visible",
      timeout: timeout(),
    });
  } catch (error) {
    const diagnostic = await page.evaluate(async () => {
      const statuses = [...globalThis.document.querySelectorAll('[role="status"]')].map((node) => ({
        name: node.getAttribute("aria-label"),
        text: node.textContent?.trim() ?? "",
      }));
      let nativeState;
      try {
        const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
        nativeState = await new TauriRuntimeHostAdapter().state();
      } catch (failure) {
        nativeState = { error: String(failure?.message ?? failure).slice(0, 500) };
      }
      return { statuses, nativeState };
    });
    throw new Error(`runtime did not reach ready: ${JSON.stringify(diagnostic)}`, { cause: error });
  }
  await ensureRecentHistoryOpen(page, timeout());
}

/**
 * 最近对话分区可能被隔离 profile 的 UI 偏好收起；只通过真实标题栏控件恢复，不直接写 DOM
 * 或 localStorage，保证后续 spinner/row 断言仍经过生产导航组件。
 */
async function ensureRecentHistoryOpen(page, timeoutMs = 30_000) {
  const list = page.getByRole("list", { name: "最近对话列表", exact: true });
  if ((await list.count()) === 0) {
    await page.getByRole("button", { name: "展开最近对话", exact: true }).click({ timeout: timeoutMs });
  }
  await list.waitFor({ state: "visible", timeout: timeoutMs });
}

/** 确保项目分区真实展开；隔离 profile 的 UI 偏好不能让项目按钮从验收 DOM 中消失。 */
async function ensureProjectCatalogOpen(page, timeoutMs = 30_000) {
  const list = page.getByRole("list", { name: "项目列表", exact: true });
  if ((await list.count()) === 0) {
    await page.getByRole("button", { name: "展开项目", exact: true }).click({ timeout: timeoutMs });
  }
  await list.waitFor({ state: "visible", timeout: timeoutMs });
}

/** 等待两个真实项目行同时进入可见目录，并在失败时保留脱敏 DOM/服务端计数诊断。 */
async function waitForProjectCatalog(page, timeoutMs = 60_000) {
  await ensureProjectCatalogOpen(page, timeoutMs);
  try {
    await page.waitForFunction(
      () =>
        globalThis.document.querySelectorAll(
          '[aria-label="项目列表"] button[data-scope-kind="project"]',
        ).length === 2,
      undefined,
      { timeout: timeoutMs },
    );
  } catch (error) {
    const diagnostic = await page.evaluate(async () => {
      const rows = [...globalThis.document.querySelectorAll(
        '[aria-label="项目列表"] button[data-scope-kind="project"]',
      )].map((node) => ({
        text: node.textContent?.trim() ?? "",
        current: node.getAttribute("aria-current"),
        disabled: node.hasAttribute("disabled"),
      }));
      let nativeCount;
      try {
        const { TauriHistoryAdapter } = await import("/src/api/tauri/history.ts");
        const listed = await new TauriHistoryAdapter().workspaceList({ limit: 200 });
        nativeCount = listed.items.length;
      } catch {
        nativeCount = "unavailable";
      }
      return { rows, nativeCount };
    });
    throw new Error(`project catalog did not expose two rows: ${JSON.stringify(diagnostic)}`, {
      cause: error,
    });
  }
}

/**
 * 按显示名称读取项目按钮；aria-label 会随 selected 状态变化，文本 label 则保持稳定，
 * 因此不把状态文案当作项目 identity。
 */
function projectButton(page, displayName) {
  return page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: displayName });
}

/**
 * 通过项目按钮完成一次真实 scope 选择，并等待 server-owned workspace identity 进入 aria-current。
 */
async function selectProject(page, displayName, deadline = Date.now() + 60_000) {
  await ensureProjectCatalogOpen(page, Math.max(1, deadline - Date.now()));
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  const button = projectButton(page, displayName);
  await button.first().waitFor({ state: "visible", timeout: timeout() });
  if ((await button.first().getAttribute("aria-current")) !== "page") {
    await button.first().click({ timeout: timeout() });
  }
  try {
    await page.waitForFunction(
      (expected) =>
        [...globalThis.document.querySelectorAll(
          '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
        )].some((candidate) => candidate.textContent?.trim() === expected),
      displayName,
      { timeout: timeout() },
    );
  } catch (error) {
    const current = await page.locator(
      '[aria-label="项目列表"] button[data-scope-kind="project"]',
    ).evaluateAll((nodes) => nodes.map((node) => ({
      text: node.textContent?.trim() ?? "",
      current: node.getAttribute("aria-current"),
    })));
    throw new Error(`project selection did not settle: ${JSON.stringify({ displayName, current })}`, {
      cause: error,
    });
  }
  await ensureRecentHistoryOpen(page, timeout());
}

/**
 * 等待 Thread 行选中并让真实 Composer 进入可提交状态；新建空 Thread 可能暂时只有空态，
 * 因此 Timeline identity 默认留给已有历史的路径断言；seed 空 Thread 可显式关闭该等待，
 * 产生首条持久事件后再断言，避免把合法空态误报为加载失败。
 */
async function selectThread(
  page,
  threadId,
  strictIdentity,
  deadline = Date.now() + 60_000,
  expectHistory = true,
) {
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: timeout() });
  if ((await row.getAttribute("aria-current")) !== "page") await row.click({ timeout: timeout() });
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: timeout() },
  );
  await waitForComposerReady(page, deadline);
  if (expectHistory) await waitForTimelineIdentity(page, threadId, strictIdentity, deadline);
}

/**
 * 等待发送表单与当前 Thread 绑定；这是空 Thread 能够接受第一条真实历史事件的最小条件，
 * 不依赖 ChatTimeline 是否已经从空态切换为事件列表。
 */
async function waitForComposerReady(page, deadline) {
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  await page.waitForFunction(
    () => {
      const input = globalThis.document.querySelector('textarea[aria-label="消息"]');
      const form = globalThis.document.querySelector('form[aria-label="发送消息"]');
      return input instanceof globalThis.HTMLTextAreaElement && !input.disabled && form?.getAttribute("data-state") === "ready";
    },
    undefined,
    { timeout: timeout() },
  );
}

/**
 * ChatTimeline 必须拥有首条持久历史；after 额外要求 data-thread-id 与侧栏选择一致，防止
 * 旧项目正文短暂留在新项目壳层中。调用方只应在 Composer 已提交首条事件后使用该门禁。
 */
async function waitForTimelineIdentity(page, threadId, strictIdentity, deadline) {
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  try {
    await page.waitForFunction(
      ({ expected, strict }) => {
        const timeline = globalThis.document.querySelector(".ja-chat-timeline");
        if (timeline === null || timeline.querySelector(".ja-chat-timeline__empty") !== null)
          return false;
        const actual = timeline.getAttribute("data-thread-id");
        return strict ? actual === expected : actual === null || actual === expected;
      },
      { expected: threadId, strict: strictIdentity },
      { timeout: timeout() },
    );
  } catch (error) {
    const state = await timelineState(page);
    throw new Error(`Timeline identity did not settle: ${JSON.stringify({ expected: threadId, state })}`, {
      cause: error,
    });
  }
}

/**
 * 提交一个真实 UI Turn 以产生持久历史正文；padding 让 Timeline 成为可滚动长对话，
 * 但 Provider 只返回 marker，报告不会携带大段用户正文。
 */
async function sendHistoryTurn(page, project, turn, deadline) {
  const marker = `JA_SWITCH_HISTORY_${project.toUpperCase()}_TURN_${turn}`;
  const prompt = `${marker} 请保留这段历史上下文。\n${"长历史上下文用于切换恢复验收。".repeat(320)}`;
  const timeout = () => Math.max(1, Math.min(30_000, deadline - Date.now()));
  await page.waitForFunction(
    () => {
      const input = globalThis.document.querySelector('textarea[aria-label="消息"]');
      const form = globalThis.document.querySelector('form[aria-label="发送消息"]');
      return input instanceof globalThis.HTMLTextAreaElement && !input.disabled && form?.getAttribute("data-state") === "ready";
    },
    undefined,
    { timeout: timeout() },
  );
  await page.getByRole("textbox", { name: "消息", exact: true }).fill(prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click({ timeout: timeout() });
  await page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .filter({ hasText: marker })
    .waitFor({ state: "visible", timeout: timeout() });
  return marker;
}

/**
 * 为指定项目建立多轮真实历史，并检查最后 marker 可见；首次 seed 经过生产 Composer、
 * turn/start、Java Provider adapter、SQLite event 和 Timeline reducer 全链路。
 */
async function seedProjectHistory(page, project, threadId, strictIdentity, evidenceDirectory, deadline) {
  await selectThread(page, threadId, strictIdentity, deadline, false);
  const markers = [];
  for (let turn = 1; turn <= HISTORY_TURNS; turn += 1)
    markers.push(await sendHistoryTurn(page, project, turn, deadline));
  await waitForTimelineIdentity(page, threadId, strictIdentity, deadline);
  await page.screenshot({
    path: join(evidenceDirectory, `workspace-switch-${project}-seeded.png`),
    animations: "disabled",
  });
  return markers;
}

/**
 * 读取当前 Timeline 的安全几何摘要，不回传正文；rowCount、thread identity 和 scroll range
 * 足以证明目标历史非空且可进入中部滚动。
 */
async function timelineState(page) {
  return page.evaluate(() => {
    const timeline = globalThis.document.querySelector(".ja-chat-timeline");
    const viewport = timeline?.querySelector(".ja-chat-timeline__scroll");
    return {
      explicitThreadId: timeline?.getAttribute("data-thread-id") ?? null,
      rowCount: timeline?.querySelectorAll(".ja-chat-timeline__row").length ?? 0,
      scrollTop: viewport?.scrollTop ?? 0,
      scrollHeight: viewport?.scrollHeight ?? 0,
      clientHeight: viewport?.clientHeight ?? 0,
    };
  });
}

/**
 * 将当前长 Timeline 滚到中部并等待两帧布局稳定；返回的 ratio 是跨项目 A→B→A 的
 * scroll restoration 参照，不把绝对像素写进固定 fixture。
 */
async function scrollTimelineToMiddle(page, deadline) {
  const state = await page.evaluate(async () => {
    const viewport = globalThis.document.querySelector(".ja-chat-timeline__scroll");
    if (!(viewport instanceof globalThis.HTMLElement)) throw new Error("Timeline viewport missing");
    const maxScrollTop = viewport.scrollHeight - viewport.clientHeight;
    if (maxScrollTop < 120) throw new Error("historical Timeline is not scrollable");
    const target = Math.round(maxScrollTop * 0.42);
    viewport.scrollTop = target;
    await new Promise((resolvePromise) =>
      globalThis.requestAnimationFrame(() => globalThis.requestAnimationFrame(resolvePromise)),
    );
    return {
      target,
      maxScrollTop: viewport.scrollHeight - viewport.clientHeight,
      actual: viewport.scrollTop,
      ratio: viewport.scrollTop / Math.max(1, viewport.scrollHeight - viewport.clientHeight),
    };
  });
  assert.ok(state.ratio > 0.2 && state.ratio < 0.7);
  void deadline;
  return state;
}

/**
 * 在浏览器自身 performance 时钟内测量项目点击到目标 Thread/正文出现，并同步观察 loading
 * projection 与每个 paint frame 的 Timeline 空态；这样 Playwright/CDP 往返不会污染主样本，
 * spinner 几何仍来自真实 DOM。
 */
async function measureProjectSwitch(page, displayName, threadId, marker, mode, strictIdentity) {
  return page.evaluate(
    ({ displayName: expectedName, expectedThreadId, expectedMarker, visibility, strict }) =>
      new Promise((resolvePromise, rejectPromise) => {
        const startedAt = globalThis.performance.now();
        let spinnerSeen = false;
        let spinnerInRecentHeading = true;
        let spinnerOutsideHistoryList = true;
        let legacyPlaceholderSeen = false;
        let blankFrameCount = 0;
        let paintFrameCount = 0;
        let paintRaf = 0;
        let settled = false;
        let settleScheduled = false;
        let lastState = {};
        const timeout = globalThis.setTimeout(() => {
          settled = true;
          globalThis.cancelAnimationFrame(paintRaf);
          observer.disconnect();
          rejectPromise(new Error(`workspace switch timed out: ${JSON.stringify(lastState)}`));
        }, 20_000);
        const visible = (node) => {
          if (!(node instanceof globalThis.HTMLElement)) return false;
          const style = globalThis.getComputedStyle(node);
          const box = node.getBoundingClientRect();
          return style.visibility !== "hidden" && style.display !== "none" && box.width > 0 && box.height > 0;
        };
        const project = () =>
          [...globalThis.document.querySelectorAll(
            '[aria-label="项目列表"] button[data-scope-kind="project"]',
          )].find((candidate) => candidate.textContent?.trim() === expectedName);
        const captureLoading = () => {
          const history = globalThis.document.querySelector(".ja-navigation-history");
          const heading = history?.querySelector(".ja-navigation-section-heading");
          const spinner = heading?.querySelector(".ja-navigation-history-loading");
          if (spinner instanceof globalThis.HTMLElement) {
            spinnerSeen = true;
            spinnerInRecentHeading &&= spinner.parentElement === heading;
            spinnerOutsideHistoryList &&= spinner.closest(".ja-navigation-history-list") === null;
          }
          const list = history?.querySelector(".ja-navigation-history-list");
          if (/正在读取(?:会话|历史)/u.test(list?.textContent ?? "")) legacyPlaceholderSeen = true;
        };
        // 用浏览器 paint 时钟观察切换中实际可见的 Timeline；DOM mutation 本身可能在一帧内
        // 经历空态，只有被绘制出来才算用户可见的 blank frame。
        const sampleTimelinePaint = () => {
          if (settled) return;
          paintFrameCount += 1;
          const timeline = globalThis.document.querySelector(".ja-chat-timeline");
          if (!(timeline instanceof globalThis.HTMLElement) || !visible(timeline)) {
            blankFrameCount += 1;
          } else {
            const rowCount = timeline.querySelectorAll(".ja-chat-timeline__row").length;
            if (rowCount === 0 || timeline.querySelector(".ja-chat-timeline__empty") !== null)
              blankFrameCount += 1;
          }
          paintRaf = globalThis.requestAnimationFrame(sampleTimelinePaint);
        };
        const finishAfterPaint = () => {
          if (settled || settleScheduled) return;
          settleScheduled = true;
          globalThis.requestAnimationFrame(() => {
            globalThis.requestAnimationFrame(() => {
              settleScheduled = false;
              finished(true);
            });
          });
        };
        const finished = (afterPaint = false) => {
          captureLoading();
          const selectedProject = project();
          const row = globalThis.document.querySelector(
            `[aria-label="最近对话列表"] button[data-thread-id="${expectedThreadId}"]`,
          );
          const timeline = globalThis.document.querySelector(".ja-chat-timeline");
          const identity = timeline?.getAttribute("data-thread-id");
          const final = [...globalThis.document.querySelectorAll(".ja-chat-message-final")].find(
            (candidate) => candidate.textContent?.includes(expectedMarker),
          );
          const ready =
            selectedProject?.getAttribute("aria-current") === "page" &&
            visible(row) &&
            timeline !== null &&
            timeline.querySelector(".ja-chat-timeline__empty") === null &&
            (strict ? identity === expectedThreadId : identity === null || identity === expectedThreadId) &&
            (visibility === "visible" ? visible(final) : final !== undefined);
          lastState = {
            selectedProject: selectedProject?.getAttribute("aria-current") === "page",
            rowVisible: visible(row),
            timelineIdentity: identity ?? null,
            markerAttached: final !== undefined,
          };
          if (!ready) return;
          if (!afterPaint) {
            finishAfterPaint();
            return;
          }
          settled = true;
          globalThis.clearTimeout(timeout);
          globalThis.cancelAnimationFrame(paintRaf);
          observer.disconnect();
          resolvePromise({
            durationMs: globalThis.performance.now() - startedAt,
            timelineIdentity: identity === expectedThreadId,
            timelineNonEmpty: timeline.querySelectorAll(".ja-chat-timeline__row").length > 0,
            spinnerSeen,
            spinnerInRecentHeading,
            spinnerOutsideHistoryList,
            legacyPlaceholderSeen,
            blankFrameCount,
            paintFrameCount,
          });
        };
        const observer = new globalThis.MutationObserver(() => finished());
        observer.observe(globalThis.document.body, {
          subtree: true,
          childList: true,
          attributes: true,
          characterData: true,
        });
        captureLoading();
        const target = project();
        if (!(target instanceof globalThis.HTMLButtonElement)) {
          globalThis.clearTimeout(timeout);
          observer.disconnect();
          rejectPromise(new Error(`project button missing: ${expectedName}`));
          return;
        }
        target.click();
        sampleTimelinePaint();
        finished();
      }),
    {
      displayName,
      expectedThreadId: threadId,
      expectedMarker: marker,
      visibility: mode,
      strict: strictIdentity,
    },
  );
}

/**
 * 读取切换完成后的 scroll ratio；after 要求 A 的中部位置在 B 往返后保持，baseline 仅记录
 * 是否自然恢复，避免改前报告因为目标缺陷丢失其它性能样本。
 */
async function readScrollRestoration(page, expected, strict) {
  const deadline = Date.now() + 3_000;
  while (Date.now() < deadline) {
    const state = await timelineState(page);
    const ratio = state.scrollTop / Math.max(1, state.scrollHeight - state.clientHeight);
    const restored =
      state.rowCount > 0 &&
      state.scrollHeight > state.clientHeight &&
      Math.abs(ratio - expected.ratio) <= 0.12;
    if (restored) return { restored: true, ...state, ratio };
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 50));
  }
  const state = await timelineState(page);
  const ratio = state.scrollTop / Math.max(1, state.scrollHeight - state.clientHeight);
  if (strict) throw new Error(`Timeline scroll position was not restored: ${JSON.stringify({ expected: expected.ratio, ratio })}`);
  return { restored: false, ...state, ratio };
}

/**
 * 读取 Review invoke probe 的窄计数；Review 未显示时任何 snapshot 增量都代表隐藏能力误触发。
 */
async function reviewInvokeCounts(page) {
  return page.evaluate(() => {
    const source = globalThis.__JA_REVIEW_REDESIGN_COUNTS__ ?? {};
    return Object.fromEntries(
      Object.entries(source).map(([command, phases]) => [command, { start: Number(phases?.start ?? 0), resolved: Number(phases?.resolved ?? 0), rejected: Number(phases?.rejected ?? 0) }]),
    );
  });
}

/**
 * 组合双向 warm switch 样本，并额外执行一次长历史 A→B→A；切换触发和目标可见均来自真实
 * project/history controls，样本不包含初次 fixture 建立或 page reload 冷启动。
 */
async function measureSwitches(page, projects, markers, strictIdentity, samples, evidenceDirectory) {
  const deadline = Date.now() + 10 * 60_000;
  // reload 后先把两个已有历史项目各访问一次；这段冷进入只用于建立可复用缓存，不进入
  // click->目标正文的热切样本，避免把首次读取的合法 loading 误判为切换回归。
  await selectProject(page, PROJECT_LABELS.b, deadline);
  await selectThread(page, projects.b.threadId, strictIdentity, deadline);
  await selectProject(page, PROJECT_LABELS.a, deadline);
  await selectThread(page, projects.a.threadId, strictIdentity, deadline);
  const scrollBefore = await scrollTimelineToMiddle(page, deadline);
  const aToB = [];
  const bToA = [];
  const restoration = [];
  for (let index = 0; index < samples; index += 1) {
    const switchedToB = await measureProjectSwitch(
      page,
      PROJECT_LABELS.b,
      projects.b.threadId,
      markers.b.at(-1),
      "visible",
      strictIdentity,
    );
    aToB.push(switchedToB);
    const switchedToA = await measureProjectSwitch(
      page,
      PROJECT_LABELS.a,
      projects.a.threadId,
      markers.a.at(-1),
      "attached",
      strictIdentity,
    );
    bToA.push(switchedToA);
    const restored = await readScrollRestoration(page, scrollBefore, strictIdentity);
    restoration.push(restored);
  }
  await page.screenshot({
    path: join(evidenceDirectory, "workspace-switch-a-restored.png"),
    animations: "disabled",
  });
  return {
    aToB,
    bToA,
    scrollBefore,
    restoration,
  };
}

/**
 * 执行完整真实窗口场景，并发布只含脱敏事实的 JSON；strict after 失败时仍由外层 runner
 * 写入失败报告并按既有 runProduction 所有权保留隔离 profile 供诊断。
 */
export async function runWorkspaceSwitchWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  samples = DEFAULT_SAMPLES,
  phase = "after",
  baseline,
  maven,
}) {
  const strictIdentity = phase === "after";
  await mkdir(evidenceDirectory, { recursive: true });
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  page.on("console", (message) => {
    if (message.type() === "error") pageErrors.push(message.text().slice(0, 500));
  });
  await page.context().addInitScript(installReviewInvokeProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
  await waitForApplication(page);
  const secondary = await createSecondaryWorkspace(workspaceRoot);
  const projects = await createProjectsAndThreads(page, workspaceRoot, secondary.root);
  await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
  await waitForApplication(page);
  await waitForProjectCatalog(page, 60_000);
  await selectProject(page, PROJECT_LABELS.a);
  const markersA = await seedProjectHistory(
    page,
    "a",
    projects.a.threadId,
    strictIdentity,
    evidenceDirectory,
    Date.now() + 10 * 60_000,
  );
  await selectProject(page, PROJECT_LABELS.b);
  const markersB = await seedProjectHistory(
    page,
    "b",
    projects.b.threadId,
    strictIdentity,
    evidenceDirectory,
    Date.now() + 10 * 60_000,
  );
  await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
  await waitForApplication(page);
  await waitForProjectCatalog(page, 60_000);
  const hiddenBefore = await reviewInvokeCounts(page);
  const switches = await measureSwitches(
    page,
    projects,
    { a: markersA, b: markersB },
    strictIdentity,
    samples,
    evidenceDirectory,
  );
  const hiddenAfter = await reviewInvokeCounts(page);
  const hiddenReviewSnapshotDelta =
    Number(hiddenAfter.ja_review_snapshot?.start ?? 0) - Number(hiddenBefore.ja_review_snapshot?.start ?? 0);
  const switchRecords = [...switches.aToB, ...switches.bToA];
  const metrics = {
    aToB: summarize(switches.aToB.map((entry) => entry.durationMs)),
    bToA: summarize(switches.bToA.map((entry) => entry.durationMs)),
  };
  const spinnerRecords = switchRecords.map((entry) => entry);
  const spinnerLegacyPlaceholder = spinnerRecords.some((entry) => entry.legacyPlaceholderSeen);
  const spinnerInRecentHeading = spinnerRecords.every(
    (entry) => entry.spinnerSeen && entry.spinnerInRecentHeading && entry.spinnerOutsideHistoryList,
  );
  const explicitThreadIdentity = switchRecords.every((entry) => entry.timelineIdentity);
  const blankFrameCount = switchRecords.reduce((total, entry) => total + entry.blankFrameCount, 0);
  const paintFrameCount = switchRecords.reduce((total, entry) => total + entry.paintFrameCount, 0);
  const noBlankTimeline = switchRecords.every(
    (entry) => entry.timelineNonEmpty && entry.blankFrameCount === 0 && entry.paintFrameCount > 0,
  );
  const scrollRestored = switches.restoration.every((entry) => entry.restored);
  const baselineMetrics = baseline?.metrics;
  const baselineEvidence =
    baselineMetrics === undefined
      ? {
          available: false,
          absoluteOnly: true,
          reason:
            phase === "baseline"
              ? "baseline 阶段未提供更早报告，当前值作为修改前绝对基线"
              : "未提供独立修改前报告，当前 checkout 并行修改期间只记录绝对值",
        }
      : {
          available: true,
          absoluteOnly: false,
          source: "provided_report",
          comparison: {
            aToBP50DeltaMs: metrics.aToB.p50Ms - baselineMetrics.aToB.p50Ms,
            aToBP95DeltaMs: metrics.aToB.p95Ms - baselineMetrics.aToB.p95Ms,
            bToAP50DeltaMs: metrics.bToA.p50Ms - baselineMetrics.bToA.p50Ms,
            bToAP95DeltaMs: metrics.bToA.p95Ms - baselineMetrics.bToA.p95Ms,
          },
        };
  const report = {
    schemaVersion: 1,
    phase,
    runtime: {
      platform: process.platform,
      surface: "tauri_webview2",
      boundary: "jvm_jar",
      nativeImageVerified: false,
      toolchain: { jdkMajor: 25, mavenJavaMajor: maven.javaMajor, nodeMajor: 24, pnpm: "10.33.0" },
    },
    provider: {
      kind: "deterministic_loopback",
      externalCalls: 0,
      attempts: pageErrors.length === 0 ? undefined : undefined,
    },
    fixture: {
      projectCount: 2,
      ignoredFiles: REQUIRED_IGNORED_FILES,
      untrackedFiles: REQUIRED_UNTRACKED_FILES,
      setupMs: secondary.setupMs,
      projects: [
        { key: "a", historicalText: markersA.length === HISTORY_TURNS, pressure: await countPressureFiles(workspaceRoot) },
        { key: "b", historicalText: markersB.length === HISTORY_TURNS, pressure: secondary.pressure },
      ],
    },
    metrics,
    correctness: {
      projectsScoped: projects.a.workspaceId !== projects.b.workspaceId,
      noBlankTimeline,
      blankFrameCount,
      paintFrameCount,
      explicitThreadIdentity,
      scrollRestored,
      spinnerLegacyPlaceholder,
      spinnerInRecentHeading,
      hiddenReviewSnapshotDelta,
      pageErrors: pageErrors.length,
      persistenceReload: true,
      sampleCount: switchRecords.length,
    },
    baseline: baselineEvidence,
    screenshots: [
      "workspace-switch-a-seeded.png",
      "workspace-switch-b-seeded.png",
      "workspace-switch-a-restored.png",
    ],
  };
  if (phase === "after") {
    assert.equal(
      report.correctness.hiddenReviewSnapshotDelta,
      0,
      `hiddenReviewSnapshotDelta=${report.correctness.hiddenReviewSnapshotDelta}`,
    );
    assert.equal(
      report.correctness.noBlankTimeline,
      true,
      `noBlankTimeline=${report.correctness.noBlankTimeline} blankFrameCount=${report.correctness.blankFrameCount} paintFrameCount=${report.correctness.paintFrameCount}`,
    );
    assert.equal(report.correctness.blankFrameCount, 0, `blankFrameCount=${report.correctness.blankFrameCount}`);
    assert.ok(report.correctness.paintFrameCount > 0, `paintFrameCount=${report.correctness.paintFrameCount}`);
    assert.equal(report.correctness.explicitThreadIdentity, true, `explicitThreadIdentity=${report.correctness.explicitThreadIdentity}`);
    assert.equal(report.correctness.scrollRestored, true, `scrollRestored=${report.correctness.scrollRestored}`);
    assert.equal(report.correctness.spinnerLegacyPlaceholder, false, `spinnerLegacyPlaceholder=${report.correctness.spinnerLegacyPlaceholder}`);
    assert.equal(report.correctness.spinnerInRecentHeading, true, `spinnerInRecentHeading=${report.correctness.spinnerInRecentHeading}`);
    assert.equal(report.correctness.pageErrors, 0, `pageErrors=${report.correctness.pageErrors}`);
  }
  validateWorkspaceSwitchReport(report);
  return report;
}

/**
 * 以 JDK25 置前的 PATH 执行 Maven 版本核对；production runner 自身还会再次核对 Java/Node/pnpm，
 * 这里补充任务单要求的 Maven Java 版本证据，避免 JAR 启动和构建使用不同 JDK。
 */
export async function validateMavenToolchain(javaHome) {
  const home = resolve(javaHome);
  const java = join(home, "bin", "java.exe");
  const inheritedPath = process.env.PATH ?? process.env.Path ?? "";
  const environment = { ...process.env, JAVA_HOME: home, PATH: [dirname(java), inheritedPath].filter(Boolean).join(";") };
  delete environment.Path;
  for (const name of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]) delete environment[name];
  const javaVersion = await execFileAsync(java, ["-version"], {
    env: environment,
    windowsHide: true,
    timeout: 30_000,
    maxBuffer: 512 * 1024,
  });
  const javaOutput = `${javaVersion.stdout}\n${javaVersion.stderr}`;
  if (!/version\s+"25(?:[.]|\s)/iu.test(javaOutput)) throw new Error("JAVA_HOME must resolve JDK 25");
  // Windows .cmd wrappers are launched through the host PowerShell so Node does not
  // return EINVAL on installations where cmd shims are not directly executable.
  const { stdout, stderr } = await execFileAsync("pwsh.exe", ["-NoProfile", "-NonInteractive", "-Command", "& mvn.cmd -version"], {
    env: environment,
    windowsHide: true,
    timeout: 30_000,
    maxBuffer: 512 * 1024,
  });
  const output = `${stdout}\n${stderr}`;
  const match = /Java version:\s*([0-9]+)/u.exec(output);
  if (match?.[1] !== "25") throw new Error("Maven must resolve JDK 25");
  return { javaMajor: Number(match[1]) };
}

/** 读取并比对 JAR 内嵌构建版本，避免前端/宿主升级后误启动旧 App Server。 */
export async function validateEmbeddedJarVersion(jarPath, javaHome) {
  const extractionRoot = await mkdtemp(join(tmpdir(), "ja-workspace-switch-jar-"));
  try {
    const jarExecutable = join(resolve(javaHome), "bin", "jar.exe");
    await execFileAsync(
      jarExecutable,
      [
        "xf",
        resolve(jarPath),
        "BOOT-INF/classes/ja-build.properties",
        "BOOT-INF/classes/META-INF/maven/io.github.kongweiguang/ja-app-server/pom.properties",
      ],
      { cwd: extractionRoot, windowsHide: true, timeout: 30_000, maxBuffer: 512 * 1024 },
    );
    const buildProperties = await readFile(
      join(extractionRoot, "BOOT-INF", "classes", "ja-build.properties"),
      "utf8",
    );
    const pomProperties = await readFile(
      join(
        extractionRoot,
        "BOOT-INF",
        "classes",
        "META-INF",
        "maven",
        "io.github.kongweiguang",
        "ja-app-server",
        "pom.properties",
      ),
      "utf8",
    );
    const packageDocument = JSON.parse(await readFile(join(repoRoot, "package.json"), "utf8"));
    const embeddedProductVersion = /^product\.version=([^\r\n]+)$/mu.exec(buildProperties)?.[1];
    const embeddedPomVersion = /^version=([^\r\n]+)$/mu.exec(pomProperties)?.[1];
    const packageVersion = typeof packageDocument.version === "string" ? packageDocument.version : undefined;
    if (
      embeddedProductVersion === undefined ||
      embeddedPomVersion === undefined ||
      packageVersion === undefined ||
      embeddedProductVersion !== embeddedPomVersion ||
      embeddedProductVersion !== packageVersion
    ) {
      throw new Error(
        `JAR version mismatch: jar=${embeddedProductVersion ?? "missing"} pom=${embeddedPomVersion ?? "missing"} package=${packageVersion ?? "missing"}`,
      );
    }
    return { embeddedProductVersion, embeddedPomVersion, packageVersion };
  } finally {
    await rm(extractionRoot, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 }).catch(
      () => undefined,
    );
  }
}

/**
 * CLI 只输出稳定阶段标记，详细性能和 UI 事实写入 evidence JSON；失败不吞掉 runner 的退出码。
 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const embeddedJar = await validateEmbeddedJarVersion(options.jar, options.javaHome);
  const maven = await validateMavenToolchain(options.javaHome);
  if (options.preflightOnly) {
    const toolchain = await validateToolchain(options);
    const edgeDriver = options.edgeDriver === undefined ? undefined : await validateEdgeDriver(options.edgeDriver);
    console.log(`JA_WORKSPACE_SWITCH_PREFLIGHT_OK ${JSON.stringify({ toolchain, maven, embeddedJar, edgeDriver })}`);
    return;
  }
  const baseline = options.baselineReport === undefined ? undefined : await readBaselineReport(options.baselineReport);
  const fixture = await startSwitchFixture();
  try {
    await runProduction({
      ...options,
      hiddenWindow: true,
      prewarmWebview: true,
      providerBaseUrl: fixture.baseUrl,
      ignoredFiles: Math.max(options.ignoredFiles, REQUIRED_IGNORED_FILES),
      untrackedFiles: Math.max(options.untrackedFiles, REQUIRED_UNTRACKED_FILES),
      fixture: "full",
      preserveFailedProfile: true,
      reportFileName: "workspace-switch-webview2-report.json",
      driver: (driverOptions) =>
        runWorkspaceSwitchWebView2({
          ...driverOptions,
          samples: options.samples,
          phase: options.phase,
          baseline,
          maven: { ...maven, embeddedJar },
        }),
      validateReport: validateWorkspaceSwitchReport,
    });
    const expectedAttempts = HISTORY_TURNS * 2;
    const actualAttempts = fixture.attempts.filter((attempt) => attempt.status === 200).length;
    if (actualAttempts < expectedAttempts)
      throw new Error(`loopback fixture attempts incomplete: ${actualAttempts}/${expectedAttempts}`);
    console.log(`JA_WORKSPACE_SWITCH_WEBVIEW2_${options.phase.toUpperCase()} ${JSON.stringify({ samples: options.samples })}`);
  } finally {
    await fixture.close();
  }
}

if (process.argv[1] !== undefined && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    console.error(`JA_WORKSPACE_SWITCH_WEBVIEW2_FAIL ${String(error?.message ?? error).slice(0, 2_000)}`);
    process.exitCode = 1;
  });
}
