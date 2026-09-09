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
import { mkdir } from "node:fs/promises";
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

/** 将真窗阶段限制在统一 deadline 内，避免 selector 漂移隐藏真正失败阶段。 */
function timeout(deadline) {
  return Math.max(1, Math.min(30_000, deadline - Date.now()));
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
      const created = await createHistoryAdapter().threadCreate({
        cwd,
        title,
        providerId: "provider_e2e",
        modelId,
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
      return { threadId: created.threadId, modelId };
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

/** 等待真实应用 ready、运行时连接和消息 Composer，排除静态页面 preview。 */
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
  const selectedProject = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await selectedProject.count()) === 0) {
    const existingProject = page
      .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
      .first();
    if ((await existingProject.count()) > 0)
      await existingProject.click({ timeout: timeout(deadline) });
    else
      await page
        .getByRole("button", { name: "添加项目", exact: true })
        .click({ timeout: timeout(deadline) });
  }
  await selectedProject.waitFor({ state: "visible", timeout: timeout(deadline) });
}

/** 读取当前工作过程的公开 DOM projection，只返回 commentary/tool kind 和安全文本摘要。 */
async function processItems(page, deadline) {
  const process = page.locator("section.ja-work-process").last();
  await process.waitFor({ state: "visible", timeout: timeout(deadline) });
  return process.locator(".ja-work-process__steps > li").evaluateAll((items) =>
    items.map((item) => {
      if (item.classList.contains("ja-work-step--commentary")) {
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

/** 保留普通正文与公开摘要的独立条目，并确认两个真实工具成功且没有重复。 */
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

/** 对外报告执行闭集校验，防止仅凭退出码或截图宣称真实 WebView2 通过。 */
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
  assert.equal(report?.live?.commentaryBeforeFirstTool, true);
  assert.deepEqual(report?.live?.sequence, [
    "commentary",
    "tool:read",
    "commentary",
    "commentary",
    "tool:shell",
    "commentary",
  ]);
  assert.equal(report?.live?.noDuplicateTools, true);
  assert.deepEqual(report?.reload?.sequence, report?.live?.sequence);
  assert.equal(report?.reload?.sameThread, true);
  assert.equal(report?.finalVisible, true);
  return report;
}

/** 在真实窗口提交 Turn；完成后先展开过程再断言，避免把成功态自动折叠误判为摘要丢失。 */
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
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  const created = await configureFixtureAndCreateThread(page, workspaceRoot, fixture.baseUrl);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
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

  const firstPublicText = page.locator(".ja-chat-message-final").filter({
    hasText: conversationProgressFixtureMarkers.commentary1,
  });
  await firstPublicText.waitFor({ state: "visible", timeout: timeout(deadline) });
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
  await workProcess
    .locator(".ja-work-step--commentary")
    .filter({ hasText: conversationProgressFixtureMarkers.commentary1 })
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  await workProcess
    .locator(".ja-work-step--commentary")
    .filter({ hasText: conversationProgressFixtureMarkers.commentary2 })
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  await workProcess
    .locator('.ja-tool-details[data-tool-kind="shell"]')
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await page
    .getByText(conversationProgressFixtureMarkers.final, { exact: false })
    .last()
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  await waitForCondition(
    "completed turn",
    () =>
      page
        .locator('.ja-chat-message-final[data-response-state="completed"]')
        .count()
        .then((count) => count > 0),
    deadline,
  );
  await workProcess
    .locator(".ja-work-step--commentary")
    .filter({ hasText: conversationProgressFixtureMarkers.commentary3 })
    .waitFor({
      state: "attached",
      timeout: timeout(deadline),
    });
  await expandProcess(page, deadline);
  const liveItems = await processItems(page, deadline);
  assertProcessSequence(liveItems, "live");
  const liveSequence = liveItems.map((item) =>
    item.kind === "tool" ? `tool:${item.toolKind}` : item.kind,
  );
  await page.screenshot({
    path: join(evidenceDirectory, "conversation-progress-live.png"),
    animations: "disabled",
  });

  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  await selectThread(page, created.threadId, deadline);
  await page
    .getByText(conversationProgressFixtureMarkers.final, { exact: false })
    .last()
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  await expandProcess(page, deadline);
  const restoredItems = await processItems(page, deadline);
  assertProcessSequence(restoredItems, "reload");
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
      commentaryBeforeFirstTool: true,
      sequence: liveSequence,
      noDuplicateTools: liveItems.filter((item) => item.kind === "tool").length === 2,
    },
    reload: { sameThread: true, sequence: restoredSequence },
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
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      // 失败截图必须在 runner 回收 WebView2 前保存，使等待超时仍保留可核验的真实界面。
      driver: async (driverOptions) => {
        try {
          return await runConversationProgressWebView2({ ...driverOptions, fixture });
        } catch (error) {
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
