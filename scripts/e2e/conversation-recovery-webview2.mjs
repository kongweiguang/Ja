// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* global document, getComputedStyle */

import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";
import { parseArguments as parseBaseArguments } from "./conversation-progress-webview2.mjs";
import { startRecoveryFixture } from "./conversation-recovery-fixture.mjs";

/** 显式选择已验证的 EdgeDriver 替代无监听的直接 CDP，保持共享 launcher 的隔离与清理所有权。 */
export function parseArguments(argv) {
  const base = [];
  let edgeDriver;
  for (let index = 0; index < argv.length; index += 2) {
    if (argv[index] === "--edge-driver") {
      if (!argv[index + 1] || argv[index + 1].startsWith("--"))
        throw new Error("missing value for --edge-driver");
      edgeDriver = resolve(argv[index + 1]);
    } else base.push(argv[index], argv[index + 1]);
  }
  return { ...parseBaseArguments(base), ...(edgeDriver ? { edgeDriver } : {}) };
}

/** 条件轮询只等待可核验状态，失败始终落在固定 deadline。 */
async function until(label, predicate) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((done) => setTimeout(done, 100));
  }
  throw new Error(`${label} timed out`);
}

/** 初始化隔离配置通过真实 settings owner 保存，禁止读取或修改用户 profile。 */
async function prepare(page, workspaceRoot, endpoint) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor();
  return page.evaluate(
    async ({ cwd, endpoint }) => {
      const { TauriSettingsAdapter } = await import("/src/api/tauri/settings.ts");
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      const adapter = new TauriSettingsAdapter();
      const settings = await adapter.snapshot();
      if (settings.document.providers[0]?.baseUrl !== "http://127.0.0.1:9/v1")
        throw new Error("not an isolated fixture profile");
      settings.document.providers[0].baseUrl = endpoint;
      await adapter.save(settings.document, settings.cas.userVersion);
      return await createHistoryAdapter().threadCreate({
        cwd,
        title: "恢复真窗验收",
        providerId: "provider_e2e",
        modelId: "model_e2e",
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
    },
    { cwd: workspaceRoot, endpoint },
  );
}

/** 重载后重新选择真实 Thread identity，确保不是另开会话掩盖故障。 */
async function restore(page, threadId) {
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor();
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor();
  await page.evaluate(() => {
    globalThis.__recoveryInvokeFailures = [];
    const original = globalThis.__TAURI_INTERNALS__.invoke.bind(globalThis.__TAURI_INTERNALS__);
    globalThis.__TAURI_INTERNALS__.invoke = async (...args) => {
      try {
        return await original(...args);
      } catch (error) {
        globalThis.__recoveryInvokeFailures.push({
          command: args[0],
          error: String(error?.message ?? error),
          code: error?.code,
        });
        throw error;
      }
    };
  });
  await page.evaluate(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const original = TauriRuntimeHostAdapter.prototype.turnStart;
    TauriRuntimeHostAdapter.prototype.turnStart = async function (input) {
      globalThis.__recoveryTurnInput = {
        keys: Object.keys(input),
        threadId: input.threadId,
        content: input.content.map((item) => ({
          type: item.type,
          keys: Object.keys(item),
          textLength: item.text?.length,
        })),
        deadlineMs: input.deadlineMs,
      };
      return original.call(this, input);
    };
  });
  const project = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]').first();
  await project.waitFor();
  await project.click();
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor();
  await row.click();
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor();
}

/** 每次失败以新 Turn 提交同一 Thread，避免重试按钮改变问题的业务语义。 */
async function send(page, step, padding = "") {
  await page.getByRole("button", { name: "发送", exact: true }).waitFor();
  await page
    .getByRole("textbox", { name: "消息", exact: true })
    .fill(`JA_RECOVERY_TURN_${step} 请回复当前恢复状态。${padding}`);
  await page.getByRole("button", { name: "发送", exact: true }).click();
}

/** 真窗测量实际状态节点与行中心，移除旋转 transform 后读取 SVG intrinsic box。 */
async function spinnerGeometry(page, threadId) {
  await page.getByRole("textbox", { name: "消息", exact: true }).hover();
  const state = page.locator(`button[data-thread-id="${threadId}"] .ja-navigation-thread-state`);
  await state.waitFor();
  return state.evaluate((element) => {
    const row = element.closest(".ja-navigation-thread-row") ?? element.closest("button");
    const box = element.getBoundingClientRect();
    const rowBox = row.getBoundingClientRect();
    const sidebarBox = element.closest("aside").getBoundingClientRect();
    const svg = element.querySelector("svg");
    const old = svg.style.transform;
    svg.style.transform = "none";
    const svgBox = svg.getBoundingClientRect();
    svg.style.transform = old;
    return {
      width: box.width,
      height: box.height,
      rowOffset: Math.abs(box.y + box.height / 2 - rowBox.y - rowBox.height / 2),
      svgOffset: Math.abs(svgBox.y + svgBox.height / 2 - box.y - box.height / 2),
      insideRow: box.left >= rowBox.left && box.right <= rowBox.right + 1,
      insideSidebar: box.left >= sidebarBox.left && box.right <= sidebarBox.right + 1,
      hitVisible: element.contains(
        document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2),
      ),
    };
  });
}

/** 实际手动压缩失败提示使用局部主题按钮，不能出现浏览器默认白底，并由真实点击关闭。 */
async function closeToast(page) {
  const button = page.locator(
    '.ja-thread-compaction-feedback button[aria-label="关闭上下文压缩提示"]',
  );
  await button.waitFor();
  const geometry = await button.evaluate((element) => {
    const style = getComputedStyle(element);
    const box = element.getBoundingClientRect();
    const iconBox = element.querySelector("svg").getBoundingClientRect();
    return {
      width: box.width,
      height: box.height,
      radius: style.borderRadius,
      background: style.backgroundColor,
      appearance: style.appearance,
      rootFontSize: parseFloat(getComputedStyle(document.documentElement).fontSize),
      iconOffsetX: Math.abs(iconBox.x + iconBox.width / 2 - box.x - box.width / 2),
      iconOffsetY: Math.abs(iconBox.y + iconBox.height / 2 - box.y - box.height / 2),
    };
  });
  assert.ok(Math.abs(geometry.width - 1.55 * geometry.rootFontSize) < 0.1);
  assert.equal(geometry.width, geometry.height);
  assert.equal(geometry.appearance, "none");
  assert.ok(geometry.iconOffsetX <= 1 && geometry.iconOffsetY <= 1);
  assert.equal(geometry.background, "rgba(0, 0, 0, 0)");
  await button.hover();
  await until(
    "feedback close hover theme",
    async () =>
      (await button.evaluate((element) => getComputedStyle(element).backgroundColor)) !==
      "rgba(0, 0, 0, 0)",
  );
  const hoverBackground = await button.evaluate(
    (element) => getComputedStyle(element).backgroundColor,
  );
  assert.notEqual(hoverBackground, "rgba(0, 0, 0, 0)");
  await button.click();
  await button.waitFor({ state: "hidden" });
  return {
    ...geometry,
    hoverBackground,
    trigger: "manual_compaction_failure",
    closedByClick: true,
  };
}

/** 执行核心恢复闭环，报告仅含已完成断言，付费供应商与 Native Image 不在本证据内。 */
export async function runRecovery({ page, workspaceRoot, evidenceDirectory, fixture }) {
  page.__recoveryPageErrors = [];
  page.on("pageerror", (error) =>
    page.__recoveryPageErrors.push(String(error.message).slice(0, 700)),
  );
  await mkdir(evidenceDirectory, { recursive: true });
  const created = await prepare(page, workspaceRoot, fixture.baseUrl);
  await restore(page, created.threadId);
  const debuggerSession = await page.context().newCDPSession(page);
  page.__recoveryCaught = [];
  debuggerSession.on("Debugger.paused", async (event) => {
    const properties = event.data?.objectId
      ? await debuggerSession
          .send("Runtime.getProperties", { objectId: event.data.objectId, ownProperties: true })
          .catch(() => ({ result: [] }))
      : { result: [] };
    page.__recoveryCaught.push({
      reason: event.reason,
      error: event.data?.description,
      details: properties.result
        .filter((item) => ["code", "message", "data", "retryable"].includes(item.name))
        .map((item) => ({ name: item.name, value: item.value?.value ?? item.value?.description })),
      frames: event.callFrames.slice(0, 5).map((frame) => ({
        function: frame.functionName,
        url: frame.url,
        line: frame.location.lineNumber,
      })),
    });
    await debuggerSession.send("Debugger.resume");
  });
  await debuggerSession.send("Debugger.enable");
  await debuggerSession.send("Debugger.setPauseOnExceptions", { state: "all" });
  for (let step = 1; step <= 3; step++) {
    await send(page, step);
    await until(`failed turn ${step}`, async () => {
      if (await page.getByText("发送失败，请检查运行时连接后重试。", { exact: true }).count())
        throw new Error("Composer submission rejected before Provider request");
      return (await page.locator('[data-response-state="failed"]').count()) === step;
    });
    assert.ok(fixture.attempts.some((attempt) => attempt.step === String(step)));
  }
  await send(page, 4);
  await until("fourth actual HTTP request", () =>
    fixture.attempts.some((attempt) => attempt.step === "4"),
  );
  const spinner = await spinnerGeometry(page, created.threadId);
  assert.ok(spinner.rowOffset <= 1 && spinner.svgOffset <= 1, JSON.stringify(spinner));
  assert.ok(
    spinner.insideRow && spinner.insideSidebar && spinner.hitVisible,
    JSON.stringify(spinner),
  );
  await page.screenshot({ path: join(evidenceDirectory, "recovery-running.png") });
  fixture.release();
  await page.getByText("JA_RECOVERY_SUCCESS_4", { exact: true }).waitFor();
  await restore(page, created.threadId);
  await page.getByText("JA_RECOVERY_SUCCESS_4", { exact: true }).waitFor();
  assert.equal(await page.locator('[data-response-state="failed"]').count(), 3);
  await send(page, 5);
  await page.getByText("JA_RECOVERY_SUCCESS_5", { exact: true }).waitFor();
  await page.getByRole("progressbar", { name: "上下文使用量", exact: true }).waitFor();
  await page.screenshot({ path: join(evidenceDirectory, "recovery-reloaded.png") });
  const compaction = await verifyCompaction(page, fixture, evidenceDirectory);
  return {
    schemaVersion: 1,
    status: "passed",
    runtime: {
      surface: "tauri_webview2",
      platform: process.platform,
      boundary: "jvm_jar",
      nativeImageVerified: false,
    },
    provider: { kind: "deterministic_loopback", externalCalls: 0, attempts: fixture.attempts },
    recovery: {
      failedTurns: 3,
      fourthSucceeded: true,
      reloadSameThread: true,
      continuedAfterReload: true,
    },
    spinner,
    compaction,
  };
}

/** 通过长历史跨过保留尾部预算；网络失败不能被结构化修复 fallback 当成成功。 */
async function verifyCompaction(page, fixture, evidenceDirectory) {
  for (const step of [6, 7, 8]) {
    await send(page, step, " bounded historical evidence ".repeat(800));
    await page.getByText(`JA_RECOVERY_SUCCESS_${step}`, { exact: true }).waitFor();
  }
  const original = await page.locator('.ja-chat-message-user[data-role="user"]').allTextContents();
  await page.getByRole("button", { name: "打开对话操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "压缩上下文", exact: true }).click();
  await page.locator(".ja-thread-compaction-feedback.is-error").waitFor({ timeout: 30_000 });
  assert.ok(
    fixture.attempts.some((attempt) => attempt.step === "summary" && attempt.status === 503),
  );
  assert.deepEqual(
    await page.locator('.ja-chat-message-user[data-role="user"]').allTextContents(),
    original,
  );
  await page.getByRole("progressbar", { name: "上下文使用量", exact: true }).waitFor();
  await page.screenshot({ path: join(evidenceDirectory, "compaction-failed.png") });
  await page.emulateMedia({ colorScheme: "dark" });
  await until("system dark theme", () =>
    page.evaluate(
      () =>
        document.documentElement.classList.contains("dark") ||
        document.documentElement.dataset.theme === "dark",
    ),
  );
  await page.screenshot({ path: join(evidenceDirectory, "compaction-failed-dark.png") });
  const toast = await closeToast(page);
  await page.getByRole("button", { name: "打开对话操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "压缩上下文", exact: true }).click();
  await page.locator(".ja-thread-compaction-feedback.is-error").waitFor({ timeout: 30_000 });
  fixture.recoverSummary();
  await page
    .locator(".ja-thread-compaction-feedback")
    .getByRole("button", { name: "重试", exact: true })
    .click();
  await page
    .getByRole("status", { name: "上下文使用量未知", exact: true })
    .waitFor({ timeout: 30_000 });
  assert.ok(
    fixture.attempts.some((attempt) => attempt.step === "summary" && attempt.status === 200),
  );
  assert.deepEqual(
    await page.locator('.ja-chat-message-user[data-role="user"]').allTextContents(),
    original,
  );
  await page.screenshot({ path: join(evidenceDirectory, "compaction-recovered-unknown.png") });
  await send(page, 9);
  await until("last response after compaction", async () => {
    const latest = page.getByRole("button", { name: "回到最新", exact: true });
    if (await latest.isVisible()) await latest.click();
    return await page.getByText("JA_RECOVERY_SUCCESS_9", { exact: true }).isVisible();
  });
  await page.getByRole("progressbar", { name: "上下文使用量", exact: true }).waitFor();
  await page.screenshot({ path: join(evidenceDirectory, "compaction-next-response-known.png") });
  return {
    verified: true,
    toast,
    failedRetainedHistory: true,
    retrySucceeded: true,
    unknownAfterCompaction: true,
    knownAfterNextResponse: true,
  };
}

/** CLI 复用唯一隔离 launcher；失败截图在真窗清理前生成。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const fixture = await startRecoveryFixture();
  try {
    await runProduction({
      ...options,
      prewarmWebview: options.edgeDriver === undefined,
      preserveFailedProfile: true,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      reportFileName: "conversation-recovery-report.json",
      driver: async (driverOptions) => {
        try {
          return await runRecovery({ ...driverOptions, fixture });
        } catch (error) {
          const diagnostic = {
            attempts: fixture.attempts,
            pageErrors: driverOptions.page.__recoveryPageErrors,
            caught: driverOptions.page.__recoveryCaught,
            invokes: await driverOptions.page
              .evaluate(async () => ({
                input: globalThis.__recoveryTurnInput,
                failures: globalThis.__recoveryInvokeFailures,
                state: await globalThis.__TAURI_INTERNALS__.invoke("ja_runtime_state", {}),
                recovery: await globalThis.__TAURI_INTERNALS__.invoke(
                  "ja_runtime_recovery_state",
                  {},
                ),
              }))
              .catch((error) => String(error)),
          };
          await writeFile(
            join(options.evidenceDirectory, "diagnostics.json"),
            JSON.stringify(diagnostic, null, 2),
          );
          console.error("RECOVERY_DIAGNOSTIC", JSON.stringify(diagnostic));
          await driverOptions.page
            .screenshot({ path: join(options.evidenceDirectory, "failure.png") })
            .catch(() => {});
          throw error;
        }
      },
    });
    console.log("JA_CONVERSATION_RECOVERY_PASS");
  } finally {
    await fixture.close();
  }
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
