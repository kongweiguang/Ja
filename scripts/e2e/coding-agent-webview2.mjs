// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { access, mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { parseArguments as parseProgressArguments } from "./conversation-progress-webview2.mjs";
import { runProduction } from "./review-redesign-production.mjs";
import {
  codingAgentDeadlineMarkers,
  startCodingAgentDeadlineProvider,
} from "./coding-agent-deadline-provider.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_CARGO_TARGET_DIRECTORY = join(repoRoot, "target", "codex-review-redesign");
const TITLE = "Coding Agent WebView2 E2E";
const MAIN_PROMPT = `Create, inspect, search, edit, and verify a workspace file. ${codingAgentDeadlineMarkers.deadline}`;
const CORRECTION_PROMPT = `Correct invalid file tool arguments and finish the write. ${codingAgentDeadlineMarkers.correction}`;
const TRUNCATION_PROMPT = `Regenerate a write call after the previous model response was truncated. ${codingAgentDeadlineMarkers.truncation}`;
const TOOL_NAMES = Object.freeze(["write", "read", "grep", "find", "ls", "edit", "exec_command"]);
const FINAL_MARKERS = Object.freeze([
  "CODING_AGENT_DEADLINE_FINAL_OK",
  "CODING_AGENT_CORRECTION_FINAL_OK",
  "CODING_AGENT_TRUNCATION_FINAL_OK",
]);

/** 把真窗操作约束在单一绝对 deadline 内，避免每一步重新获得完整等待预算。 */
function timeout(deadline) {
  return Math.max(1, Math.min(30_000, deadline - Date.now()));
}

/** 使用有界轮询检查真实 DOM 条件，避免固定延时掩盖 UI 或事件顺序问题。 */
async function waitForCondition(label, predicate, deadline) {
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} timed out`);
}

/** 只检查阻断整页的错误/恢复视图，避免把预期 ToolResult 警报当成应用覆盖层。 */
async function visibleApplicationErrorOverlays(page) {
  return page
    .locator(
      '#root > [role="alert"][aria-live="assertive"], #root .ja-error-state, #root .ja-recovery-card',
    )
    .evaluateAll((nodes) =>
      nodes
        .filter((node) => {
          const style = globalThis.getComputedStyle(node);
          return (
            node.getClientRects().length > 0 &&
            style.display !== "none" &&
            style.visibility !== "hidden" &&
            style.opacity !== "0"
          );
        })
        .map((node) => ({
          className: typeof node.className === "string" ? node.className : "",
          text: (node.textContent ?? "").trim().slice(0, 500),
        })),
    );
}

/** 确认真正的 Tauri runtime 已握手，并从生产 typed adapter 读取 Ready generation。 */
async function waitForApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await waitForCondition(
    "native runtime ready",
    () =>
      page.evaluate(async () => {
        const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
        const state = await createRuntimeHostAdapter().state();
        return state.status === "ready" && state.generation > 0;
      }),
    deadline,
  );
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
}

/** 空隔离 profile 经真实“添加项目”入口选择 runner 注入的唯一 workspace。 */
async function bootstrapProject(page, deadline) {
  const selected = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await selected.count()) === 0) {
    const existing = page
      .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
      .first();
    if ((await existing.count()) > 0) {
      await existing.click({ timeout: timeout(deadline) });
    } else {
      await page.getByRole("button", { name: "添加项目", exact: true }).click({
        timeout: timeout(deadline),
      });
    }
  }
  await selected.waitFor({ state: "visible", timeout: timeout(deadline) });
}

/** 校验 runner 预置 Provider 后创建 full-access Thread，使 shell 与文件工具经过真实授权链。 */
async function createFixtureThread(page, workspaceRoot, endpoint) {
  return page.evaluate(
    async ({ cwd, providerEndpoint, title }) => {
      const [{ TauriSettingsAdapter }, { createHistoryAdapter }] = await Promise.all([
        import("/src/api/tauri/settings.ts"),
        import("/src/api/tauri/history.ts"),
      ]);
      const settings = await new TauriSettingsAdapter().snapshot();
      const provider = settings.document.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (provider === undefined || provider.baseUrl !== providerEndpoint) {
        throw new Error("isolated loopback Provider is unavailable");
      }
      const modelId = provider.models[0]?.modelId;
      if (modelId === undefined) throw new Error("isolated loopback model is unavailable");
      const thread = await createHistoryAdapter().threadCreate({
        cwd,
        title,
        providerId: provider.providerId,
        modelId,
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
      return { threadId: thread.threadId, modelId };
    },
    { cwd: workspaceRoot, providerEndpoint: endpoint, title: TITLE },
  );
}

/** 按 Java 返回的 Thread ID 恢复会话，避免仅凭标题命中同名历史。 */
async function selectThread(page, threadId, deadline) {
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await row.getAttribute("aria-current")) !== "page") {
    await row.click({ timeout: timeout(deadline) });
  }
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: timeout(deadline) },
  );
}

/** reload 后先恢复唯一项目，再恢复精确 Thread，确保历史核对落在同一会话。 */
async function restoreProjectAndThread(page, threadId, deadline) {
  const project = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]').first();
  await project.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await project.getAttribute("aria-current")) !== "page") {
    await project.click({ timeout: timeout(deadline) });
  }
  await selectThread(page, threadId, deadline);
}

/** 返回当前 Turn 的可访问工作过程区，默认折叠时通过用户可见 disclosure 展开。 */
async function expandLatestProcess(page, deadline) {
  const section = page.locator("section.ja-work-process").last();
  await section.waitFor({ state: "visible", timeout: timeout(deadline) });
  const trigger = section.locator(".ja-work-process__trigger");
  if ((await trigger.getAttribute("aria-expanded")) !== "true") {
    await trigger.click({ timeout: timeout(deadline) });
  }
  await section.locator(".ja-work-process__steps").waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  return section;
}

/** 等待进行中进程的行内记录；活动 Turn 按设计不提供展开入口。 */
async function waitForLiveProcess(page, deadline) {
  const section = page.locator("section.ja-work-process--inline").last();
  await section.waitFor({ state: "visible", timeout: timeout(deadline) });
  await section.locator(".ja-work-process__steps").waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  return section;
}

/** 使用 UI 投影在根节点公开的真实工具名定位调用，不依赖展示标题或模型回放次序。 */
function toolDetails(section, name) {
  return section.locator(`.ja-tool-details[data-tool-name="${name}"]`);
}

/** 展开 Tool 的真实过程项，读取根状态和当前 UI 允许展示的摘要/结果文本。 */
async function readToolOutput(details, expectedStatus, deadline) {
  const trigger = details.locator(".ja-tool-details__trigger");
  await trigger.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await trigger.getAttribute("aria-expanded")) !== "true") {
    await trigger.click({ timeout: timeout(deadline) });
  }
  assert.equal(await details.getAttribute("data-status"), expectedStatus);
  const visibleResult = details.locator(
    ".ja-tool-details__overview, .ja-tool-details__output, .ja-tool-result-list",
  );
  if ((await visibleResult.count()) > 0) {
    await visibleResult.first().waitFor({ state: "visible", timeout: timeout(deadline) });
  }
  return (await details.textContent())?.trim() ?? "";
}

/** 提交 Composer 输入并等待对应的用户消息落入真实 Timeline。 */
async function submitPrompt(page, prompt, deadline) {
  const input = page.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(prompt);
  await page
    .getByRole("button", { name: "发送", exact: true })
    .click({ timeout: timeout(deadline) });
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: prompt })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
}

/** 等待带唯一 marker 的 assistant 消息达到已完成终态，而非仅观察到 Provider 请求返回。 */
async function waitForFinal(page, marker, deadline) {
  const final = page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .filter({ hasText: marker })
    .last();
  await final.waitFor({ state: "visible", timeout: timeout(deadline) });
  return final;
}

/** 采集工作过程的工具名、动作、目标和状态，字段来自页面可见 DOM。 */
async function projectTools(section) {
  return section.locator(".ja-tool-details").evaluateAll((items) =>
    items.map((item) => {
      const identity = item.querySelector(".ja-tool-details__identity");
      return {
        name: item.getAttribute("data-tool-name") ?? "",
        action: identity?.querySelector(".ja-tool-details__label")?.textContent?.trim() ?? "",
        target: item.querySelector(".ja-tool-details__target")?.textContent?.trim() ?? "",
        status: item.getAttribute("data-status") ?? "",
      };
    }),
  );
}

/** 测量真实进程区和 Composer 的矩形，避免截图掩盖重叠或溢出。 */
async function measureLiveLayout(section) {
  return section.evaluate((processSection) => {
    const rect = (element) => {
      const bounds = element.getBoundingClientRect();
      return {
        left: bounds.left,
        top: bounds.top,
        right: bounds.right,
        bottom: bounds.bottom,
        width: bounds.width,
        height: bounds.height,
        visible: element.getClientRects().length > 0,
      };
    };
    const composer = globalThis.document.querySelector(".ja-composer");
    if (composer === null) throw new Error("live Composer is missing");
    const viewport = { width: globalThis.innerWidth, height: globalThis.innerHeight };
    const process = rect(processSection);
    const composerBounds = rect(composer);
    const toolRows = Array.from(processSection.querySelectorAll(".ja-tool-details")).map(
      (item) => ({
        name: item.getAttribute("data-tool-name") ?? "",
        status: item.getAttribute("data-status") ?? "",
        ...rect(item),
      }),
    );
    const toolRowsDoNotOverlap = toolRows.every(
      (item, index) => index === 0 || item.top >= toolRows[index - 1].bottom - 1,
    );
    const toolRowsFitViewport = toolRows.every(
      (item) => item.visible && item.left >= -1 && item.right <= viewport.width + 1,
    );
    const composerDoesNotOverlapTools = toolRows.every(
      (item) =>
        item.right <= composerBounds.left + 1 ||
        composerBounds.right <= item.left + 1 ||
        item.bottom <= composerBounds.top + 1 ||
        composerBounds.bottom <= item.top + 1,
    );
    return {
      viewport,
      process,
      composer: composerBounds,
      toolRows,
      toolRowsDoNotOverlap,
      toolRowsFitViewport,
      composerDoesNotOverlapTools,
    };
  });
}

/** 向隔离证据目录保存失败前的状态摘要与真窗截图，便于定位 fixture 或 selector 根因。 */
async function captureFailure(page, fixture, evidenceDirectory, error) {
  const snapshot = {
    error: String(error?.message ?? error)
      .replace(/[\r\n]+/gu, " ")
      .slice(0, 1_000),
    fixtureFailure: fixture.failure(),
    fixtureObservations: fixture.observations.slice(-16),
    page: await page
      .evaluate(async () => {
        try {
          const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
          const state = await createRuntimeHostAdapter().state();
          return {
            appReady:
              globalThis.document.querySelector(".ja-shell")?.getAttribute("data-app-ready") ??
              null,
            runtime: { status: state.status, generation: state.generation },
            projectCount: globalThis.document.querySelectorAll(
              '[aria-label="项目列表"] button[data-scope-kind="project"]',
            ).length,
            threadCount: globalThis.document.querySelectorAll(
              '[aria-label="最近对话列表"] button[data-thread-id]',
            ).length,
          };
        } catch {
          return { status: "unavailable" };
        }
      })
      .catch(() => ({ status: "page_unavailable" })),
  };
  await mkdir(evidenceDirectory, { recursive: true });
  await writeFile(
    join(evidenceDirectory, "coding-agent-webview2-failure.json"),
    `${JSON.stringify(snapshot, null, 2)}\n`,
    "utf8",
  );
  await page
    .screenshot({ path: join(evidenceDirectory, "coding-agent-webview2-failure.png") })
    .catch(() => undefined);
}

/** 通过 Composer 完成七工具长 Turn、参数纠错、截断恢复和 reload 历史闭环。 */
export async function runCodingAgentWebView2({ page, workspaceRoot, evidenceDirectory, fixture }) {
  assert.ok(page, "page is required");
  assert.ok(workspaceRoot, "workspaceRoot is required");
  assert.ok(fixture, "fixture is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const pageErrors = [];
  const consoleErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  page.on("console", (message) => {
    if (message.type() !== "error") return;
    const location = message.location();
    consoleErrors.push({
      text: message.text().slice(0, 500),
      url: location.url || null,
    });
  });
  const deadline = Date.now() + 10 * 60_000;

  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  await bootstrapProject(page, deadline);
  const created = await createFixtureThread(page, workspaceRoot, fixture.baseUrl);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  await restoreProjectAndThread(page, created.threadId, deadline);
  const desktopViewport = page.viewportSize() ?? { width: 1280, height: 800 };
  const narrowViewport = { width: 520, height: 900 };

  const turnResults = [];
  const startedMain = Date.now();
  await submitPrompt(page, MAIN_PROMPT, deadline);
  let liveProcessFacts;
  try {
    await fixture.waitForEditResponseBarrier();
    const liveProcess = await waitForLiveProcess(page, deadline);
    assert.equal(await liveProcess.getAttribute("data-state"), "active");
    const activeComposer = page.locator('.ja-composer[data-state="active"][aria-busy="true"]');
    await activeComposer.waitFor({ state: "visible", timeout: timeout(deadline) });
    const workingStatus = page
      .locator(".ja-chat-response__status")
      .filter({ hasText: "正在工作" })
      .last();
    await workingStatus.waitFor({ state: "visible", timeout: timeout(deadline) });
    assert.match((await workingStatus.textContent())?.trim() ?? "", /^正在工作/u);
    await waitForCondition(
      "committed write and read/search rows while the Turn is active",
      async () => {
        const rows = await projectTools(liveProcess);
        return (
          rows.length === 5 &&
          rows.every((item) => item.status === "success") &&
          rows.map((item) => item.name).join(",") === "write,read,grep,find,ls"
        );
      },
      deadline,
    );
    const readStage = fixture.observations.find(
      (item) => item.scenario === "deadline" && item.stage === "edit",
    );
    assert.equal(readStage?.checks?.length, 4);
    assert.ok(readStage.checks.every((item) => item.present && item.containsExpectedText));
    assert.equal(
      await page
        .locator('.ja-chat-message-final[data-response-state="completed"]')
        .filter({ hasText: FINAL_MARKERS[0] })
        .count(),
      0,
      "the final answer must remain uncommitted while the read results are inspected",
    );
    const liveWriteOutput = await readToolOutput(
      toolDetails(liveProcess, "write").first(),
      "success",
      deadline,
    );
    const liveReadOutput = await readToolOutput(
      toolDetails(liveProcess, "read").first(),
      "success",
      deadline,
    );
    assert.ok(liveWriteOutput.length > 0);
    assert.match(liveReadOutput, /ORIGINAL_MARKER/u);
    const liveLayout = await measureLiveLayout(liveProcess);
    assert.equal(liveLayout.toolRowsDoNotOverlap, true);
    assert.equal(liveLayout.toolRowsFitViewport, true);
    assert.equal(liveLayout.composerDoesNotOverlapTools, true);
    liveProcessFacts = {
      visible: true,
      turnState: await liveProcess.getAttribute("data-state"),
      composerState: await activeComposer.getAttribute("data-state"),
      composerBusy: await activeComposer.getAttribute("aria-busy"),
      readSearchOutputsCommitted: true,
      finalAnswerAbsent: true,
      tools: await projectTools(liveProcess),
      layout: liveLayout,
    };
    await page.screenshot({
      path: join(evidenceDirectory, "00-coding-agent-live-process.png"),
      animations: "disabled",
    });
  } finally {
    fixture.releaseEditResponseBarrier();
  }
  await waitForFinal(page, FINAL_MARKERS[0], deadline);
  const mainSection = await expandLatestProcess(page, deadline);
  await page.screenshot({
    path: join(evidenceDirectory, "01-coding-agent-process.png"),
    animations: "disabled",
  });
  for (const name of TOOL_NAMES) {
    const details = toolDetails(mainSection, name).first();
    const output = await readToolOutput(details, "success", deadline);
    assert.ok(output.length > 0, `${name} must return visible output`);
  }
  const mainTools = await projectTools(mainSection);
  assert.deepEqual(
    mainTools.map((item) => item.name),
    TOOL_NAMES,
  );
  assert.ok(mainTools.every((item) => item.action.length > 0 && item.target.length > 0));
  const mainElapsedMs = Date.now() - startedMain;
  assert.ok(
    mainElapsedMs > fixture.requestTimeoutMs,
    "the complete Composer Turn must exceed one fixture response window",
  );
  turnResults.push({
    elapsedMs: mainElapsedMs,
    terminalState: "completed",
    tools: mainTools,
    liveProcess: liveProcessFacts,
  });
  await page.screenshot({
    path: join(evidenceDirectory, "02-coding-agent-main-complete.png"),
    animations: "disabled",
  });
  await page.setViewportSize(narrowViewport);
  const measuredNarrowViewport = await page.evaluate(() => ({
    width: globalThis.innerWidth,
    height: globalThis.innerHeight,
  }));
  assert.equal(measuredNarrowViewport.width, narrowViewport.width);
  await page.screenshot({
    path: join(evidenceDirectory, "03-coding-agent-main-narrow.png"),
    animations: "disabled",
  });
  await page.setViewportSize(desktopViewport);

  await submitPrompt(page, CORRECTION_PROMPT, deadline);
  await waitForFinal(page, FINAL_MARKERS[1], deadline);
  const correctionSection = await expandLatestProcess(page, deadline);
  assert.equal(await toolDetails(correctionSection, "write").count(), 2);
  const invalidWrite = toolDetails(correctionSection, "write").first();
  assert.equal(
    await invalidWrite.locator(".ja-tool-details__trigger").getAttribute("aria-expanded"),
    "true",
    "a failed write must open its diagnostic without another user action",
  );
  const invalidOutput = await readToolOutput(invalidWrite, "error", deadline);
  assert.match(invalidOutput, /content/iu);
  const invalidTrigger = invalidWrite.locator(".ja-tool-details__trigger");
  assert.equal(await invalidTrigger.getAttribute("aria-expanded"), "true");
  const correctedOutput = await readToolOutput(
    toolDetails(correctionSection, "write").nth(1),
    "success",
    deadline,
  );
  assert.match(correctedOutput, /corrected\.txt/iu);
  await page.screenshot({
    path: join(evidenceDirectory, "04-coding-agent-corrected.png"),
    animations: "disabled",
  });
  turnResults.push({
    terminalState: "completed",
    tools: ["write:error", "write:success"],
    invalidWriteExpanded: true,
  });

  await submitPrompt(page, TRUNCATION_PROMPT, deadline);
  await waitForFinal(page, FINAL_MARKERS[2], deadline);
  const truncationSection = await expandLatestProcess(page, deadline);
  assert.equal(await toolDetails(truncationSection, "write").count(), 2);
  const truncatedWrite = toolDetails(truncationSection, "write").first();
  assert.equal(
    await truncatedWrite.locator(".ja-tool-details__trigger").getAttribute("aria-expanded"),
    "true",
    "a truncated write must open its diagnostic without another user action",
  );
  const truncatedOutput = await readToolOutput(truncatedWrite, "error", deadline);
  assert.match(truncatedOutput, /(truncated|incomplete)/iu);
  const recoveredOutput = await readToolOutput(
    toolDetails(truncationSection, "write").nth(1),
    "success",
    deadline,
  );
  assert.match(recoveredOutput, /truncation-recovered\.txt/iu);
  turnResults.push({ terminalState: "completed", tools: ["write:error", "write:success"] });
  await page.screenshot({
    path: join(evidenceDirectory, "05-coding-agent-truncation-recovered.png"),
    animations: "disabled",
  });

  const workspaceFiles = {
    main: await readFile(join(workspaceRoot, "src", "deadline-proof.txt"), "utf8"),
    corrected: await readFile(join(workspaceRoot, "corrected.txt"), "utf8"),
    recovered: await readFile(join(workspaceRoot, "truncation-recovered.txt"), "utf8"),
    invalidWriteExists: await access(join(workspaceRoot, "must-not-exist-invalid.txt")).then(
      () => true,
      () => false,
    ),
    truncatedWriteExists: await access(join(workspaceRoot, "must-not-exist.txt")).then(
      () => true,
      () => false,
    ),
  };
  assert.equal(workspaceFiles.main, "EDITED_MARKER\nshell-target\n");
  assert.equal(workspaceFiles.corrected, "CORRECTED_WRITE_OK\n");
  assert.equal(workspaceFiles.recovered, "TRUNCATION_RECOVERED_OK\n");
  assert.equal(workspaceFiles.invalidWriteExists, false);
  assert.equal(workspaceFiles.truncatedWriteExists, false);

  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  await restoreProjectAndThread(page, created.threadId, deadline);
  for (const marker of FINAL_MARKERS) await waitForFinal(page, marker, deadline);
  const restoredRows = page.locator(
    '[aria-label="最近对话列表"] button[data-thread-id][aria-current="page"]',
  );
  assert.equal(await restoredRows.getAttribute("data-thread-id"), created.threadId);
  const restoredProcesses = page.locator("section.ja-work-process");
  assert.ok((await restoredProcesses.count()) >= 3, "all three Turn processes must survive reload");
  for (const marker of FINAL_MARKERS) {
    const final = page
      .locator('.ja-chat-message-final[data-response-state="completed"]')
      .filter({ hasText: marker });
    await final.waitFor({ state: "visible", timeout: timeout(deadline) });
  }
  const restoredTruncation = await expandLatestProcess(page, deadline);
  const restoredError = await readToolOutput(
    toolDetails(restoredTruncation, "write").first(),
    "error",
    deadline,
  );
  assert.equal(restoredError, truncatedOutput);
  assert.equal(await toolDetails(restoredTruncation, "write").nth(1).count(), 1);
  await readToolOutput(toolDetails(restoredTruncation, "write").nth(1), "success", deadline);
  await page.screenshot({
    path: join(evidenceDirectory, "06-coding-agent-reloaded-history.png"),
    animations: "disabled",
  });
  await page.setViewportSize(narrowViewport);
  const restoredNarrowViewport = await page.evaluate(() => ({
    width: globalThis.innerWidth,
    height: globalThis.innerHeight,
  }));
  assert.equal(restoredNarrowViewport.width, narrowViewport.width);
  await page.screenshot({
    path: join(evidenceDirectory, "07-coding-agent-reloaded-narrow.png"),
    animations: "disabled",
  });
  await page.setViewportSize(desktopViewport);
  assert.deepEqual(pageErrors, [], `WebView2 page errors: ${pageErrors.join(" | ")}`);
  assert.deepEqual(consoleErrors, [], `WebView2 console errors: ${JSON.stringify(consoleErrors)}`);
  const errorOverlays = await visibleApplicationErrorOverlays(page);
  assert.deepEqual(
    errorOverlays,
    [],
    `WebView2 application error overlays: ${JSON.stringify(errorOverlays)}`,
  );

  const observations = fixture.observations;
  assert.equal(fixture.failure(), null);
  const mainFinal = observations.find(
    (item) => item.scenario === "deadline" && item.stage === "final",
  );
  assert.equal(mainFinal?.shellSawEditedContent, true);
  const checks =
    observations.find((item) => item.scenario === "deadline" && item.stage === "edit")?.checks ??
    [];
  assert.equal(checks.length, 4);
  assert.ok(checks.every((item) => item.present && item.containsExpectedText));
  assert.ok(
    observations.some(
      (item) =>
        item.scenario === "correction" &&
        item.stage === "corrected_write" &&
        item.invalidCallReturned,
    ),
  );
  assert.ok(
    observations.some(
      (item) =>
        item.scenario === "truncation" &&
        item.stage === "recovered_write" &&
        item.truncatedCallReturned,
    ),
  );
  const catalog =
    observations.find((item) => item.scenario === "deadline" && item.stage === "write")
      ?.toolCatalog ?? [];
  for (const name of TOOL_NAMES)
    assert.ok(
      catalog.some((tool) => tool.name === name),
      `${name} missing from live catalog`,
    );

  return {
    schemaVersion: 1,
    status: "passed",
    runtime: {
      platform: process.platform,
      surface: "tauri_webview2",
      boundary: "jvm_jar",
      nativeImageVerified: false,
    },
    provider: {
      kind: "deterministic_loopback",
      externalCalls: 0,
      requestTimeoutMs: fixture.requestTimeoutMs,
      responseDelayMs: fixture.responseDelayMs,
      defaultCatalog: catalog.map((tool) => tool.name),
      observations,
      failure: fixture.failure(),
    },
    turns: {
      main: { ...turnResults[0], finalVisible: true },
      correction: { ...turnResults[1], invalidWriteExpanded: true },
      truncation: { ...turnResults[2], truncatedCallErrorVisible: true },
    },
    workspace: workspaceFiles,
    process: {
      visibleAfterCompletion: true,
      restoredCount: await restoredProcesses.count(),
    },
    reload: { sameThread: true, allFinalsVisible: true, truncationErrorPreserved: true },
    viewports: {
      desktop: desktopViewport,
      narrow: measuredNarrowViewport,
      reloadNarrow: restoredNarrowViewport,
    },
    screenshots: [
      "00-coding-agent-live-process.png",
      "01-coding-agent-process.png",
      "02-coding-agent-main-complete.png",
      "03-coding-agent-main-narrow.png",
      "04-coding-agent-corrected.png",
      "05-coding-agent-truncation-recovered.png",
      "06-coding-agent-reloaded-history.png",
      "07-coding-agent-reloaded-narrow.png",
    ],
    pageErrors,
    consoleErrors,
    errorOverlays,
  };
}

/** 拒绝缺少运行中工具进度、真实七工具、三类终态、零副作用或同 Thread reload 的报告。 */
export function validateCodingAgentWebView2Report(report) {
  assert.equal(report?.schemaVersion, 1);
  assert.equal(report?.status, "passed");
  assert.equal(report?.runtime?.platform, "win32");
  assert.equal(report?.runtime?.surface, "tauri_webview2");
  assert.equal(report?.runtime?.boundary, "jvm_jar");
  assert.equal(report?.runtime?.nativeImageVerified, false);
  assert.match(report?.execution?.jar?.sha256 ?? "", /^[a-f0-9]{64}$/u);
  assert.ok(report?.execution?.jar?.size > 0);
  assert.equal(report?.provider?.kind, "deterministic_loopback");
  assert.equal(report?.provider?.externalCalls, 0);
  for (const name of TOOL_NAMES) assert.ok(report?.provider?.defaultCatalog?.includes(name));
  assert.deepEqual(
    report?.turns?.main?.tools?.map((item) => item.name),
    TOOL_NAMES,
  );
  assert.equal(report?.turns?.main?.liveProcess?.visible, true);
  assert.equal(report?.turns?.main?.liveProcess?.turnState, "active");
  assert.equal(report?.turns?.main?.liveProcess?.composerState, "active");
  assert.equal(report?.turns?.main?.liveProcess?.composerBusy, "true");
  assert.equal(report?.turns?.main?.liveProcess?.readSearchOutputsCommitted, true);
  assert.equal(report?.turns?.main?.liveProcess?.finalAnswerAbsent, true);
  assert.ok(report?.turns?.main?.liveProcess?.layout?.viewport?.width > 0);
  assert.equal(report?.turns?.main?.liveProcess?.layout?.toolRowsDoNotOverlap, true);
  assert.equal(report?.turns?.main?.liveProcess?.layout?.toolRowsFitViewport, true);
  assert.equal(report?.turns?.main?.liveProcess?.layout?.composerDoesNotOverlapTools, true);
  assert.deepEqual(
    report?.turns?.main?.liveProcess?.tools?.map((item) => `${item.name}:${item.status}`),
    ["write:success", "read:success", "grep:success", "find:success", "ls:success"],
  );
  assert.ok(report?.turns?.main?.elapsedMs > report?.provider?.requestTimeoutMs);
  assert.equal(report?.turns?.main?.terminalState, "completed");
  assert.equal(report?.turns?.correction?.terminalState, "completed");
  assert.equal(report?.turns?.correction?.invalidWriteExpanded, true);
  assert.equal(report?.turns?.truncation?.terminalState, "completed");
  assert.equal(report?.turns?.truncation?.truncatedCallErrorVisible, true);
  assert.equal(report?.workspace?.main, "EDITED_MARKER\nshell-target\n");
  assert.equal(report?.workspace?.corrected, "CORRECTED_WRITE_OK\n");
  assert.equal(report?.workspace?.recovered, "TRUNCATION_RECOVERED_OK\n");
  assert.equal(report?.workspace?.invalidWriteExists, false);
  assert.equal(report?.workspace?.truncatedWriteExists, false);
  assert.equal(report?.process?.visibleAfterCompletion, true);
  assert.equal(report?.reload?.sameThread, true);
  assert.equal(report?.reload?.allFinalsVisible, true);
  assert.equal(report?.reload?.truncationErrorPreserved, true);
  assert.equal(report?.viewports?.narrow?.width, 520);
  assert.equal(report?.viewports?.reloadNarrow?.width, 520);
  assert.deepEqual(report?.pageErrors, []);
  assert.deepEqual(report?.consoleErrors, []);
  assert.deepEqual(report?.errorOverlays, []);
  return report;
}

/** 解析通用 runner 参数，并默认复用指定的隔离 Tauri Cargo target。 */
export function parseArguments(argv = process.argv.slice(2)) {
  const options = parseProgressArguments(argv);
  if (!argv.includes("--cargo-target-directory")) {
    options.cargoTargetDirectory = DEFAULT_CARGO_TARGET_DIRECTORY;
  }
  return options;
}

/** 启动确定性 loopback Provider 与隐藏真窗，fixture 与 Ja launcher 都在 finally 中关闭。 */
async function main() {
  const options = parseArguments();
  const fixture = await startCodingAgentDeadlineProvider({ holdEditResponse: true });
  try {
    const report = await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      hiddenWindow: true,
      preserveFailedProfile: true,
      prewarmWebview: true,
      driver: async (driverOptions) => {
        try {
          return await runCodingAgentWebView2({ ...driverOptions, fixture });
        } catch (error) {
          await captureFailure(driverOptions.page, fixture, options.evidenceDirectory, error);
          throw error;
        }
      },
      validateReport: validateCodingAgentWebView2Report,
      reportFileName: "coding-agent-webview2-report.json",
    });
    process.stdout.write(
      `CODING_AGENT_WEBVIEW2_PASS ${JSON.stringify({ status: report.status })}\n`,
    );
  } finally {
    await fixture.close();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    process.stderr.write(
      `CODING_AGENT_WEBVIEW2_FAIL ${String(error?.message ?? error).slice(0, 2_000)}\n`,
    );
    process.exitCode = 1;
  });
}
