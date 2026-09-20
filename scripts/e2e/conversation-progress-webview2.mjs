// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 真实 JVM JAR + Tauri/WebView2 的公开工作过程验收 runner。
 *
 * 每次运行复用 review-redesign-production 的隔离 profile、CDP 和进程清理，只把 Provider
 * 替换成本轮 loopback fixture。它验证公开 summary/commentary 与 read/shell Tool 的实时顺序、
 * SQLite 历史 reload 顺序及 Tool 去重，不代表 Native Image 或付费 Provider 验收。
 */

import assert from "node:assert/strict";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";
import {
  conversationProgressFixtureMarkers,
  startConversationProgressFixture,
} from "./fixtures/conversation-progress.mjs";

const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const PROMPT = "请先读取隔离 fixture，再执行一次低影响 Shell 回显，最后总结结果。";
const TITLE = "Conversation Progress E2E";
const PROGRESS_TURN_WALL_TIMEOUT_MS = 120_000;
const PROGRESS_INVOKE_LOG_KEY = "__JA_CONVERSATION_PROGRESS_INVOKES__";

/** 将真窗阶段限制在统一 deadline 内，避免 selector 漂移隐藏真正失败阶段。 */
function timeout(deadline) {
  return Math.max(1, Math.min(30_000, deadline - Date.now()));
}

/** 将真窗异常归入固定类别，避免失败报告携带易变的 selector 或路径细节。 */
function runnerErrorCategory(error) {
  const message = String(error?.message ?? error);
  if (error?.name === "AssertionError" || error?.code === "ERR_ASSERTION") return "assertion";
  if (/超时|timeout/u.test(message)) return "timeout";
  if (/locator|selector/u.test(message)) return "selector";
  if (/protocol|webview|cdp/u.test(message)) return "runtime_protocol";
  return "runner";
}

/** 只保留 fixture 的阶段与已知回合，防止诊断意外记录 Provider 请求或 Tool 正文。 */
function safeFixtureFailureSnapshot(snapshot) {
  const stages = Array.isArray(snapshot?.stages) ? snapshot.stages.slice(-16) : [];
  const attempts = Array.isArray(snapshot?.attempts)
    ? snapshot.attempts.slice(-8).map((attempt) => ({
        kind: attempt?.kind === "title" ? "title" : "turn",
        step: Number.isSafeInteger(attempt?.step) ? attempt.step : null,
        progressInstruction: attempt?.progressInstruction === true,
      }))
    : [];
  return { stage: stages.at(-1) ?? "none", stages, attempts };
}

/**
 * 在隔离 WebView2 内观测 native command 的生命周期顺序；代理完全透传原调用，且只保存命令名、
 * 阶段和封闭 runtime 状态，避免诊断复制参数、路径、会话身份或 Tool 内容。
 */
async function instrumentRuntimeInvocations(page) {
  // reload 后 WebView2 的 Tauri bridge 可能晚于 DOMContentLoaded 注入；先等待受信 bridge，
  // 才能确保本轮诊断覆盖应用自动恢复与 driver 恢复的全部 native 调用。
  await page.waitForFunction(
    () => typeof globalThis.__TAURI_INTERNALS__?.invoke === "function",
    undefined,
    { timeout: 30_000 },
  );
  await page.evaluate((key) => {
    const internals = globalThis.__TAURI_INTERNALS__;
    if (internals === undefined || typeof internals.invoke !== "function") return;
    if (globalThis[key] !== undefined) return;
    const records = [];
    const original = internals.invoke.bind(internals);
    globalThis[key] = records;
    internals.invoke = async (command, payload) => {
      const watched = typeof command === "string" && command.startsWith("ja_");
      if (watched) records.push({ command, phase: "start" });
      try {
        const result = await original(command, payload);
        if (watched) {
          const state = result !== null && typeof result === "object" ? result : {};
          records.push({
            command,
            phase: "resolved",
            status: typeof state.status === "string" ? state.status : undefined,
          });
        }
        return result;
      } catch (error) {
        if (watched) {
          const value = error !== null && typeof error === "object" ? error : {};
          records.push({
            command,
            phase: "rejected",
            errorCode: typeof value.code === "string" ? value.code.slice(0, 96) : undefined,
          });
        }
        throw error;
      }
    };
  }, PROGRESS_INVOKE_LOG_KEY);
}

/** 读取失败时的最小 Tauri 投影，确认 UI 受限是否由 native runtime 状态造成而非 DOM 选择器漂移。 */
async function readConversationProgressRuntimeDiagnostics(page) {
  if (page === undefined) return { status: "page_unavailable" };
  return page
    .evaluate(async () => {
      const internals = globalThis.__TAURI_INTERNALS__;
      const errorProjection = (error) => {
        const value = error !== null && typeof error === "object" ? error : {};
        return {
          code: typeof value.code === "string" ? value.code.slice(0, 96) : undefined,
          message: typeof value.message === "string" ? value.message.slice(0, 256) : undefined,
          retryable: typeof value.retryable === "boolean" ? value.retryable : undefined,
        };
      };
      const invoke = async (command, project) => {
        if (internals === undefined || typeof internals.invoke !== "function") {
          return { status: "invoke_unavailable" };
        }
        try {
          return project(await internals.invoke(command, {}));
        } catch (error) {
          return { error: errorProjection(error) };
        }
      };
      return {
        appReady:
          globalThis.document.querySelector(".ja-shell")?.getAttribute("data-app-ready") ?? null,
        runtimeLabel:
          globalThis.document
            .querySelector('[aria-label^="本地运行时："]')
            ?.getAttribute("aria-label") ?? null,
        runtimeState: await invoke("ja_runtime_state", (value) => {
          const state = value !== null && typeof value === "object" ? value : {};
          return {
            status: typeof state.status === "string" ? state.status : "invalid",
            generation: Number.isSafeInteger(state.generation) ? state.generation : undefined,
            serverInstanceIdPresent:
              typeof state.serverInstanceId === "string" && state.serverInstanceId.length > 0,
          };
        }),
        recoveryState: await invoke("ja_runtime_recovery_state", (value) => {
          const recovery = value !== null && typeof value === "object" ? value : {};
          return {
            required: recovery.required === true,
            acknowledgeable: recovery.acknowledgeable === true,
            recoveryIdPresent:
              typeof recovery.recoveryId === "string" && recovery.recoveryId.length > 0,
            revision: Number.isSafeInteger(recovery.revision) ? recovery.revision : undefined,
          };
        }),
        invocations: Array.isArray(globalThis["__JA_CONVERSATION_PROGRESS_INVOKES__"])
          ? globalThis["__JA_CONVERSATION_PROGRESS_INVOKES__"].slice(-48)
          : [],
      };
    })
    .catch(() => ({ status: "evaluate_failed" }));
}

/** 读取隔离 Java 日志的有限尾部并脱敏 fixture 根与凭据样式字段，保留超时根因的异常类型。 */
async function readIsolatedRuntimeLogs(home) {
  if (typeof home !== "string") return { status: "unavailable" };
  const logDirectory = join(home, "logs", "java");
  const logs = {};
  for (const name of ["app-server-error.log", "app-server.log"]) {
    try {
      const path = join(logDirectory, name);
      const metadata = await stat(path);
      if (!metadata.isFile() || metadata.size > 4 * 1024 * 1024) {
        logs[name] = { status: "invalid_size" };
        continue;
      }
      logs[name] = {
        status: "available",
        tail: (await readFile(path, "utf8"))
          .split(/\r?\n/u)
          .filter((line) => line.length > 0)
          .slice(-120)
          .map((line) =>
            line
              .replaceAll(home, "<JA_HOME>")
              .replace(/https?:\/\/[^\s]+/gu, "<URL>")
              .replace(
                /\b(?:api[_-]?key|authorization|token|credential)\s*[=:]\s*\S+/giu,
                "<REDACTED>",
              )
              .slice(0, 800),
          ),
      };
    } catch {
      logs[name] = { status: "unavailable" };
    }
  }
  return logs;
}

/** 等待最终 DOM 条件，使用有界轮询而不是任意 sleep 掩盖事件丢失。 */
async function waitForCondition(label, predicate, deadline) {
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} 超时`);
}

/** 校验启动时固定的隔离 Provider，再通过真实 typed adapter 建会话，不在验收中热改配置或密钥。 */
async function configureFixtureAndCreateThread(page, workspaceRoot, baseUrl) {
  return page.evaluate(
    async ({ cwd, endpoint, title }) => {
      const [{ TauriSettingsAdapter }, { createHistoryAdapter }] = await Promise.all([
        import("/src/api/tauri/settings.ts"),
        import("/src/api/tauri/history.ts"),
      ]);
      const settings = new TauriSettingsAdapter();
      const loaded = await settings.snapshot();
      const current = loaded.document.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (current === undefined) throw new Error("isolated provider_e2e is missing");
      const modelId = current.models[0]?.modelId;
      if (modelId === undefined) throw new Error("isolated provider_e2e model is missing");
      if (current.baseUrl !== endpoint) throw new Error("fixture Provider endpoint was not staged");
      const history = createHistoryAdapter();
      const workspace = await history.workspaceOpen({
        cwd,
        displayName: "Conversation Progress E2E",
      });
      const created = await history.threadCreate({
        cwd: workspace.root,
        title,
        providerId: "provider_e2e",
        modelId,
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
      return {
        threadId: created.threadId,
        workspaceId: workspace.workspaceId,
        workspaceName: workspace.displayName,
        modelId,
      };
    },
    { cwd: workspaceRoot, endpoint: baseUrl, title: TITLE },
  );
}

/** 通过真实侧栏恢复 runner Thread，绑定断言到 Java 返回的 identity 而非标题猜测。 */
async function selectThread(page, threadId, deadline) {
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await row.getAttribute("aria-current")) !== "page")
    await row.click({ timeout: timeout(deadline) });
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: timeout(deadline) },
  );
}

/** 等待真实应用 ready、运行时连接和消息 Composer；项目由后续 thread identity 恢复，避免隐藏窗口伪造目录选择。 */
async function waitForApplication(page, deadline) {
  await page
    .locator('.ja-shell[data-app-ready="true"]')
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
}

/** 选择已由 App Server `workspaceOpen` 建立的隔离项目，不把回复验收耦合到原生目录 picker。 */
async function selectProject(page, workspaceName, deadline) {
  const project = page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: workspaceName });
  await project.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await project.getAttribute("aria-current")) !== "page") {
    await project.click({ timeout: timeout(deadline) });
  }
}

/** reload 后经生产 typed adapter 恢复 stopped generation，并二次确认 ready identity。 */
async function restoreRuntimeAfterReload(page, deadline) {
  await page.waitForFunction(
    () => typeof globalThis.__TAURI_INTERNALS__?.invoke === "function",
    undefined,
    { timeout: timeout(deadline) },
  );
  await page.evaluate(
    async ({ timeoutMs }) => {
      const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
      const adapter = createRuntimeHostAdapter();
      const deadlineAt = Date.now() + timeoutMs;
      let state = await adapter.state();
      while (state.status === "starting" || state.status === "stopping") {
        if (Date.now() >= deadlineAt)
          throw new Error(`runtime restore timed out in ${state.status}`);
        await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
        state = await adapter.state();
      }
      if (state.status === "stopped") state = await adapter.start();
      if (state.status !== "ready" && state.status !== "busy") {
        throw new Error(`runtime restore did not reach ready: ${state.status}`);
      }
      const confirmed = await adapter.state();
      if (confirmed.status !== "ready" && confirmed.status !== "busy") {
        throw new Error(`runtime restore confirmation failed: ${confirmed.status}`);
      }
    },
    { timeoutMs: timeout(deadline) },
  );
  await waitForApplication(page, deadline);
}

/** 从工作过程读取已经持久化的 Commentary/Reasoning，避免把正在输出的最终正文误计入过程。 */
function publicNarrative(process, marker) {
  return process
    .locator(".ja-work-step--commentary, .ja-work-step--reasoning")
    .filter({ hasText: marker });
}

/** 读取当前工作过程的公开 DOM projection，只返回叙事/tool kind 和安全文本摘要。 */
async function processItems(page, deadline) {
  const process = page.locator("section.ja-work-process").last();
  await process.waitFor({ state: "visible", timeout: timeout(deadline) });
  return process.locator(".ja-work-process__steps > li").evaluateAll((items) =>
    items.map((item) => {
      if (
        item.classList.contains("ja-work-step--commentary") ||
        item.classList.contains("ja-work-step--reasoning")
      ) {
        return { kind: "commentary", text: item.textContent?.trim() ?? "" };
      }
      const tool = item.querySelector(".ja-tool-details");
      return {
        kind: "tool",
        toolKind: tool?.getAttribute("data-tool-kind") ?? "unknown",
        toolLabel:
          tool?.querySelector(".ja-tool-details__trigger")?.getAttribute("aria-label") ?? "",
        text: item.textContent?.trim() ?? "",
      };
    }),
  );
}

/** 用按钮的可访问展开状态判断折叠，CSS 高度裁剪不保证设置 hidden 属性。 */
async function expandProcess(page, deadline) {
  const process = page.locator("section.ja-work-process").last();
  await process.waitFor({ state: "visible", timeout: timeout(deadline) });
  const trigger = process.locator(".ja-work-process__trigger");
  if ((await trigger.getAttribute("aria-expanded")) !== "true") {
    await trigger.click({ timeout: timeout(deadline) });
  }
  await process
    .locator(".ja-work-process__steps")
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  return process;
}

/**
 * 展开真实 read 步骤并确认 Java metadata 驱动的摘要与实底正文同时出现；这覆盖新结果视图的真实
 * Tauri/WebView2 渲染，不以手写 DOM 或静态截图替代 Tool 执行链路。
 */
async function expandReadResult(process, deadline) {
  const read = process.locator('.ja-tool-details[data-tool-kind="read"]');
  await read.waitFor({ state: "visible", timeout: timeout(deadline) });
  const trigger = read.locator(".ja-tool-details__trigger");
  if ((await trigger.getAttribute("aria-expanded")) !== "true") {
    await trigger.click({ timeout: timeout(deadline) });
  }
  const summary = read.locator(".ja-tool-details__overview");
  await summary.waitFor({ state: "visible", timeout: timeout(deadline) });
  const text = (await summary.textContent())?.trim() ?? "";
  assert.match(text, /^已读取 \d+ 行，共 \d+ 行/u, "read summary must use authoritative metadata");
  await read
    .locator(".ja-tool-details__output")
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  return text;
}

/**
 * 断言已结算的过程仍按 Tool 交错，且最后一轮正文不回流到过程。
 *
 * 末尾 reasoning summary 在 terminal 后继续可审计，但最终 output_text 只属于 AssistantResponse；
 * live 与 reload 都必须保留相同的公开过程类型顺序。
 */
function assertProcessSequence(items, label) {
  assert.deepEqual(
    items.map((item) => item.kind),
    ["commentary", "tool", "commentary", "commentary", "tool", "commentary"],
    `${label} must interleave commentary and tools`,
  );
  assert.deepEqual(
    items.filter((item) => item.kind === "tool").map((item) => item.toolKind),
    ["read", "shell"],
    `${label} must contain exactly one read and one shell`,
  );
  assert.equal(
    new Set(items.filter((item) => item.kind === "tool").map((item) => item.toolKind)).size,
    2,
  );
  assert.equal(
    items.filter((item) => item.kind === "tool").every((item) => item.toolLabel.endsWith("完成")),
    true,
  );
}

/**
 * 终态 reasoning summary 由实时 Draft 保留到历史 read 接管；最终 output_text 无论何时都不能进入过程。
 */
function assertFinalBodyOutsideProcess(items, label) {
  assert.equal(
    items.some((item) => item.text.includes(conversationProgressFixtureMarkers.final)),
    false,
    `${label} WorkProcess must not contain the final response body`,
  );
}

/** 对外报告必须同时证明实时过程/回复分区、持续生命信号、terminal 原位校准与历史顺序。 */
export function validateConversationProgressReport(report) {
  assert.equal(report?.schemaVersion, 1);
  assert.equal(report?.status, "passed");
  assert.equal(report?.runtime?.platform, "win32");
  assert.equal(report?.runtime?.surface, "tauri_webview2");
  assert.equal(report?.runtime?.boundary, "jvm_jar");
  assert.equal(report?.runtime?.nativeImageVerified, false);
  assert.equal(report?.provider?.kind, "deterministic_loopback");
  assert.equal(report?.provider?.externalCalls, 0);
  assert.equal(report?.provider?.toolCalls, 2);
  assert.equal(report?.live?.responseBeforeFirstTool, true);
  assert.equal(report?.live?.workingStatusWithProcess, true);
  assert.equal(report?.live?.finalResponseBeforeTerminal, true);
  assert.equal(report?.live?.finalBodyOutsideProcess, true);
  assert.equal(report?.live?.processNodeStable, true);
  assert.equal(report?.live?.responseNodeStable, true);
  assert.equal(report?.live?.terminalCalibratedExistingResponse, true);
  assert.equal(report?.live?.completedProcessCollapsed, true);
  assert.equal(report?.live?.readSummaryVisible, true);
  assert.deepEqual(report?.live?.sequence, [
    "commentary",
    "tool:read",
    "commentary",
    "commentary",
    "tool:shell",
    "commentary",
  ]);
  assert.equal(report?.live?.noDuplicateTools, true);
  assert.equal(report?.reload?.finalBodyOutsideProcess, true);
  assert.deepEqual(report?.reload?.sequence, [
    "commentary",
    "tool:read",
    "commentary",
    "commentary",
    "tool:shell",
    "commentary",
  ]);
  assert.equal(report?.reload?.readSummaryVisible, true);
  assert.equal(report?.reload?.sameThread, true);
  assert.equal(report?.finalVisible, true);
  return report;
}

/**
 * 在真实窗口提交 Turn；当前 text delta 先进入外部回复区，携带 Tool 的 model step 提交后归档过程，
 * 无 Tool 的最后一轮则保持原位直到 terminal 权威校准。
 *
 * 受控 fixture gate 让每个结构化边界都可单独观察，不依赖任意等待时间或最后一个 Tool 的位置猜测。
 */
export async function runConversationProgressWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  fixture,
}) {
  assert.ok(page, "page is required");
  assert.ok(workspaceRoot, "workspaceRoot is required");
  assert.ok(fixture, "fixture is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + 5 * 60_000;
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  await instrumentRuntimeInvocations(page);
  // runner 已连接到新启动的真实窗口；这里额外 reload 会与旧 renderer 的 stop cleanup 竞争。
  // 历史恢复阶段仍执行一次真实 reload，因而不会削弱 reload + persisted history 的验收边界。
  await waitForApplication(page, deadline);
  const created = await configureFixtureAndCreateThread(page, workspaceRoot, fixture.baseUrl);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await instrumentRuntimeInvocations(page);
  await restoreRuntimeAfterReload(page, deadline);
  await selectProject(page, created.workspaceName, deadline);
  await selectThread(page, created.threadId, deadline);
  const input = page.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(PROMPT);
  await page
    .getByRole("button", { name: "发送", exact: true })
    .click({ timeout: timeout(deadline) });
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: PROMPT })
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });

  const responseShell = page.locator('.ja-chat-message-final[data-role="response"]').last();
  await responseShell.getByText("正在工作", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await responseShell
    .getByText(conversationProgressFixtureMarkers.commentary1, { exact: false })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  // 首个真实 delta 已归属权威 Turn；本地提交壳可能在 ACK 前存在，不能拿它冒充流式节点基线。
  const responseShellHandle = await responseShell.elementHandle();
  assert.ok(responseShellHandle, "the authoritative streaming response must have a DOM node");
  assert.equal(
    await page.locator("section.ja-work-process").count(),
    0,
    "text before the first Tool must start in the response surface",
  );
  assert.equal(
    await page.locator(".ja-tool-details").count(),
    0,
    "public output text must render before the first Tool row",
  );
  assert.equal(
    fixture.stages.includes("text_read"),
    true,
    "fixture did not stream first public text",
  );
  assert.equal(
    fixture.stages.includes("tool_read"),
    false,
    "fixture sent read Tool before text checkpoint",
  );
  fixture.releaseFirstText();

  const workProcess = page.locator("section.ja-work-process").last();
  await workProcess.locator('.ja-tool-details[data-tool-kind="read"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await publicNarrative(workProcess, conversationProgressFixtureMarkers.commentary1).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await responseShell.getByText("正在工作", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const responseWithProcessHandle = await responseShell.elementHandle();
  assert.ok(responseWithProcessHandle, "the response below WorkProcess must have a DOM node");
  assert.equal(
    await responseShellHandle.evaluate(
      (streamingNode, processNode) => streamingNode === processNode,
      responseWithProcessHandle,
    ),
    true,
    "Tool settlement must retain the authoritative response node",
  );
  const streamingProcessHandle = await workProcess.elementHandle();
  assert.ok(streamingProcessHandle, "the streaming WorkProcess must have a DOM node");
  await publicNarrative(workProcess, conversationProgressFixtureMarkers.commentary2).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  fixture.releaseSecondNarrative();
  await workProcess
    .locator('.ja-tool-details[data-tool-kind="shell"]')
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await publicNarrative(workProcess, conversationProgressFixtureMarkers.commentary3).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  fixture.releaseFinalNarrative();
  await responseShell
    .getByText(conversationProgressFixtureMarkers.final, { exact: false })
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  await responseShell.getByText("正在工作", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  assert.equal(
    await publicNarrative(workProcess, conversationProgressFixtureMarkers.final).count(),
    0,
    "the final output body must remain outside WorkProcess before terminal",
  );
  fixture.releaseFinalText();
  await waitForCondition(
    "completed turn",
    () =>
      page
        .locator('.ja-chat-message-final[data-response-state="completed"]')
        .count()
        .then((count) => count > 0),
    deadline,
  );
  const completedAnswer = page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .last();
  const completedAnswerHandle = await completedAnswer.elementHandle();
  assert.ok(completedAnswerHandle, "terminal must expose a completed final-answer node");
  const responseNodeStable = await responseShellHandle.evaluate(
    (streamingNode, completedNode) => streamingNode === completedNode,
    completedAnswerHandle,
  );
  assert.equal(responseNodeStable, true, "terminal must calibrate the existing response node");
  const completedProcessHandle = await workProcess.elementHandle();
  assert.ok(completedProcessHandle, "terminal must retain the WorkProcess node");
  const processNodeStable = await streamingProcessHandle.evaluate(
    (streamingNode, completedNode) => streamingNode === completedNode,
    completedProcessHandle,
  );
  assert.equal(processNodeStable, true, "terminal must not replace the WorkProcess container");
  await waitForCondition(
    "completed WorkProcess collapse",
    () =>
      workProcess
        .locator(".ja-work-process__trigger")
        .getAttribute("aria-expanded")
        .then((expanded) => expanded === "false"),
    deadline,
  );
  await expandProcess(page, deadline);
  const liveReadSummary = await expandReadResult(workProcess, deadline);
  const liveItems = await processItems(page, deadline);
  assertProcessSequence(liveItems, "live");
  assertFinalBodyOutsideProcess(liveItems, "live");
  const liveSequence = liveItems.map((item) =>
    item.kind === "tool" ? `tool:${item.toolKind}` : item.kind,
  );
  await page.screenshot({
    path: join(evidenceDirectory, "conversation-progress-live.png"),
    animations: "disabled",
  });

  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await restoreRuntimeAfterReload(page, deadline);
  await selectProject(page, created.workspaceName, deadline);
  await selectThread(page, created.threadId, deadline);
  await page
    .getByText(conversationProgressFixtureMarkers.final, { exact: false })
    .last()
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  await expandProcess(page, deadline);
  const reloadReadSummary = await expandReadResult(workProcess, deadline);
  const restoredItems = await processItems(page, deadline);
  assert.deepEqual(
    restoredItems.map((item) => item.kind),
    ["commentary", "tool", "commentary", "commentary", "tool", "commentary"],
    "reload must restore the persisted final-round reasoning summary",
  );
  assertFinalBodyOutsideProcess(restoredItems, "reload");
  const restoredSequence = restoredItems.map((item) =>
    item.kind === "tool" ? `tool:${item.toolKind}` : item.kind,
  );
  await page.screenshot({
    path: join(evidenceDirectory, "conversation-progress-reload.png"),
    animations: "disabled",
  });
  assert.deepEqual(pageErrors, [], `WebView2 page errors: ${pageErrors.join(" | ")}`);
  const provider = fixture.snapshot();
  assert.equal(provider.attempts.filter((attempt) => attempt.kind === "turn").length, 3);
  assert.equal(
    provider.attempts
      .filter((attempt) => attempt.kind === "turn")
      .every((attempt, index) => attempt.step === index),
    true,
  );
  assert.equal(
    provider.attempts
      .filter((attempt) => attempt.kind === "turn")
      .every((attempt) => attempt.progressInstruction),
    true,
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
      toolCalls: 2,
      attempts: provider.attempts,
    },
    live: {
      responseBeforeFirstTool: true,
      workingStatusWithProcess: true,
      finalResponseBeforeTerminal: true,
      finalBodyOutsideProcess: true,
      processNodeStable,
      responseNodeStable,
      terminalCalibratedExistingResponse: true,
      completedProcessCollapsed: true,
      readSummaryVisible: liveReadSummary.length > 0,
      sequence: liveSequence,
      noDuplicateTools: liveItems.filter((item) => item.kind === "tool").length === 2,
    },
    reload: {
      sameThread: true,
      readSummaryVisible: reloadReadSummary.length > 0,
      finalBodyOutsideProcess: true,
      sequence: restoredSequence,
    },
    finalVisible: true,
    screenshots: ["conversation-progress-live.png", "conversation-progress-reload.png"],
    pageErrors,
  };
}

/** 解析 runner CLI，强制 evidence/JAR 参数显式传入，避免误连真实用户 profile 或旧产物。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-conversation-progress"),
  };
  for (let index = 0; index < argv.length; index += 2) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (argument === "--jar") options.jar = resolve(value);
    else if (argument === "--java-home") options.javaHome = resolve(value);
    else if (argument === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else throw new Error(`unknown argument: ${argument}`);
  }
  if (options.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (options.jar === undefined) throw new Error("--jar is required");
  return options;
}

/** 启动 loopback fixture 与复用 production runner 的隔离真窗生命周期，并保证 listener 总能关闭。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const fixture = await startConversationProgressFixture();
  try {
    const report = await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      // 三轮 Provider + 两次 Tool continuation 在慢速 Windows WebView2 上合法超过 Review 的单轮预算；
      // 该值仅写入一次性 E2E profile，不能改变用户或生产 Provider 配置。
      wallTimeoutMs: PROGRESS_TURN_WALL_TIMEOUT_MS,
      scope: "git",
      fixture: "no-head",
      hiddenWindow: true,
      preserveFailedProfile: true,
      // Windows 新 UDF 首次启动不会稳定接受远程调试参数；先只完成 profile 初始化，
      // 再由同一隔离 profile 的受控实例承载 CDP 与业务验收，不会触及用户窗口或数据。
      prewarmWebview: true,
      ignoredFiles: 0,
      untrackedFiles: 0,
      // 失败截图必须在 runner 回收 WebView2 前保存，使等待超时仍保留可核验的真实界面。
      driver: async (driverOptions) => {
        try {
          return await runConversationProgressWebView2({ ...driverOptions, fixture });
        } catch (error) {
          const fixtureDiagnostic = safeFixtureFailureSnapshot(fixture.snapshot());
          const runtimeDiagnostic = await readConversationProgressRuntimeDiagnostics(
            driverOptions.page,
          );
          const diagnostic = {
            errorCategory: runnerErrorCategory(error),
            fixture: fixtureDiagnostic,
            runtime: runtimeDiagnostic,
            isolatedRuntimeLogs: await readIsolatedRuntimeLogs(driverOptions.isolatedRuntimeHome),
          };
          await writeFile(
            join(options.evidenceDirectory, "conversation-progress-runtime-diagnostic.json"),
            `${JSON.stringify(diagnostic, null, 2)}\n`,
            "utf8",
          ).catch(() => undefined);
          console.error(
            `JA_CONVERSATION_PROGRESS_RUNTIME_DIAGNOSTIC ${JSON.stringify(diagnostic)}`,
          );
          await driverOptions.page
            .screenshot({
              path: join(options.evidenceDirectory, "failure.png"),
            })
            .catch(() => {});
          throw error;
        }
      },
      validateReport: validateConversationProgressReport,
      reportFileName: "conversation-progress-report.json",
    });
    console.log(`JA_CONVERSATION_PROGRESS_PASS ${JSON.stringify({ status: report.status })}`);
  } finally {
    await fixture.close();
  }
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(
      `JA_CONVERSATION_PROGRESS_FAIL ${String(error?.message ?? error).slice(0, 2000)}`,
    );
    process.exitCode = 1;
  });
}
