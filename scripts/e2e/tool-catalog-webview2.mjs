// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 真实 JVM JAR + Tauri/WebView2 的内置 Tool 目录验收 runner。
 *
 * 每次运行复用现有 conversation-progress 的参数约定与 review production 的隔离 profile、
 * CDP 和进程清理，只把 Provider 替换成本轮 loopback fixture。它不代表 Native Image 或付费
 * Provider 验收，且始终使用隐藏窗口，避免把验收抢到用户前台。
 */

import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { parseArguments as parseProgressArguments } from "./conversation-progress-webview2.mjs";
import { runProduction } from "./review-redesign-production.mjs";
import {
  startToolCatalogFixture,
  toolCatalogFixtureMarkers,
} from "./fixtures/tool-catalog.mjs";

const REQUIRED_BASE_TOOLS = Object.freeze(["read", "write", "edit", "shell", "grep", "find", "ls"]);
const FORBIDDEN_DEFAULT_TOOLS = Object.freeze(["workspace_search", "read_attachment", "tool_search"]);
const TOOL_ACTIONS = Object.freeze({
  grep: "搜索内容",
  find: "查找文件",
  ls: "列出目录",
});
const SCHEMA_TOOL_NAMES = Object.freeze(["grep", "find", "ls"]);
const SCHEMA_FIELD_DIFFERENCES = Object.freeze([
  "tool",
  "strict",
  "root_type",
  "properties",
  "required",
  "additional_properties",
  "non_nullable",
  "nullable",
]);
const PROMPT = "请使用基础文件工具验收隔离目录：先纠正 grep 参数，再执行 find 和 ls，最后总结。";
const TITLE = "Tool Catalog E2E";
const HIDDEN_CONTENT = "JA_TOOL_CATALOG_HIDDEN_CONTENT";

/** 将真窗异常归入固定类别，诊断只保留故障面而不复制 selector 或响应正文。 */
function runnerErrorCategory(error) {
  const message = String(error?.message ?? error);
  if (error?.name === "AssertionError" || error?.code === "ERR_ASSERTION") return "assertion";
  if (/超时|timeout/u.test(message)) return "timeout";
  if (/locator|selector/u.test(message)) return "selector";
  if (/protocol|webview|cdp/u.test(message)) return "runtime_protocol";
  return "runner";
}

/** 将 fixture 快照压缩为有限 stage/step/tool 数量，并保留预定义 schema 契约差异。 */
function safeFixtureFailureSnapshot(snapshot) {
  const stages = Array.isArray(snapshot?.stages) ? snapshot.stages.slice(-16) : [];
  const attempts = Array.isArray(snapshot?.attempts)
    ? snapshot.attempts.slice(-8).map((attempt) => ({
        kind: attempt?.kind === "title" ? "title" : "turn",
        step: Number.isSafeInteger(attempt?.step) ? attempt.step : null,
        toolCount: Array.isArray(attempt?.toolNames) ? attempt.toolNames.length : null,
      }))
    : [];
  const failure =
    snapshot?.failure?.errorCategory === "schema_contract"
      ? {
          errorCategory: "schema_contract",
          toolName: SCHEMA_TOOL_NAMES.includes(snapshot.failure.toolName)
            ? snapshot.failure.toolName
            : "unknown",
          fieldDifference: SCHEMA_FIELD_DIFFERENCES.includes(snapshot.failure.fieldDifference)
            ? snapshot.failure.fieldDifference
            : "tool",
        }
      : null;
  return {
    stage: stages.at(-1) ?? "none",
    stages,
    attempts,
    failure,
  };
}

/** 在真窗清理前记录可脱敏的 fixture 进度与错误类别，避免 selector 超时吞掉 Provider 根因。 */
function reportFixtureFailure(fixture, error) {
  const snapshot = safeFixtureFailureSnapshot(fixture.snapshot());
  console.error(
    `JA_TOOL_CATALOG_FIXTURE_DIAGNOSTIC ${JSON.stringify({
      errorCategory: snapshot.failure?.errorCategory ?? runnerErrorCategory(error),
      ...snapshot,
    })}`,
  );
}

/** 读取真窗失败时的最小运行时投影，只保留状态和计数，不物化正文、路径或 Thread identity。 */
async function readToolCatalogRuntimeDiagnostics(page) {
  if (page === undefined) return { status: "page_unavailable" };
  return page
    .evaluate(async () => {
      const internals = globalThis.__TAURI_INTERNALS__;
      const projectList = globalThis.document.querySelector('[aria-label="项目列表"]');
      const threadList = globalThis.document.querySelector('[aria-label="最近对话列表"]');
      const errorProjection = (error) => {
        const value = error !== null && typeof error === "object" ? error : {};
        return {
          code: typeof value.code === "string" ? value.code.slice(0, 96) : undefined,
          message: typeof value.message === "string" ? value.message.slice(0, 256) : undefined,
          retryable: typeof value.retryable === "boolean" ? value.retryable : undefined,
        };
      };
      const runtimeState =
        internals === undefined || typeof internals.invoke !== "function"
          ? { status: "invoke_unavailable" }
          : await (async () => {
              try {
                const value = await internals.invoke("ja_runtime_state", {});
                const state = value !== null && typeof value === "object" ? value : {};
                return {
                  status: typeof state.status === "string" ? state.status : "invalid",
                  generation: Number.isSafeInteger(state.generation) ? state.generation : undefined,
                  serverInstanceIdPresent:
                    typeof state.serverInstanceId === "string" && state.serverInstanceId.length > 0,
                };
              } catch (error) {
                return { error: errorProjection(error) };
              }
            })();
      return {
        appReady:
          globalThis.document.querySelector(".ja-shell")?.getAttribute("data-app-ready") ?? null,
        runtimeStatus:
          globalThis.document.querySelector('[aria-label^="本地运行时："]')?.getAttribute("aria-label") ??
          null,
        projectListPresent: projectList !== null,
        projectButtonCount: projectList?.querySelectorAll("button").length ?? 0,
        projectCount: projectList?.querySelectorAll('button[data-scope-kind="project"]').length ?? 0,
        selectedProjectPresent:
          projectList !== null &&
          projectList.querySelector('button[data-scope-kind="project"][aria-current="page"]') !== null,
        threadListPresent: threadList !== null,
        threadCount: threadList?.querySelectorAll("button[data-thread-id]").length ?? 0,
        selectedThreadPresent:
          threadList !== null &&
          threadList.querySelector('button[data-thread-id][aria-current="page"]') !== null,
        runtimeState,
      };
    })
    .catch(() => ({ status: "evaluate_failed" }));
}

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

/** 写入仅属于本轮 temp workspace 的分层文件，正文 marker 用于证明发现工具没有读正文。 */
async function writeToolCatalogWorkspaceFixture(workspaceRoot) {
  const nested = join(workspaceRoot, "catalog", "nested");
  const deep = join(nested, "deep");
  await mkdir(deep, { recursive: true });
  await Promise.all([
    writeFile(join(workspaceRoot, "catalog", "root.txt"), `${toolCatalogFixtureMarkers.grep}\n`, "utf8"),
    writeFile(join(nested, "nested.txt"), `${HIDDEN_CONTENT}\n`, "utf8"),
    writeFile(join(deep, "deep.txt"), `${HIDDEN_CONTENT}_DEEP\n`, "utf8"),
  ]);
  return {
    root: "catalog/root.txt",
    nested: "catalog/nested/nested.txt",
    deep: "catalog/nested/deep/deep.txt",
    hiddenContent: HIDDEN_CONTENT,
  };
}

/** 通过真实 typed settings/history adapter 创建本轮 Thread，不热改用户配置或密钥。 */
async function configureFixtureAndCreateThread(page, workspaceRoot, baseUrl) {
  return page.evaluate(
    async ({ cwd, endpoint, title }) => {
      const [{ TauriSettingsAdapter }, { createHistoryAdapter }] = await Promise.all([
        import("/src/api/tauri/settings.ts"),
        import("/src/api/tauri/history.ts"),
      ]);
      const loaded = await new TauriSettingsAdapter().snapshot();
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

/** 通过真实侧栏恢复 runner Thread，断言绑定 Java 返回的 identity 而不是标题猜测。 */
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

/** 等待真实应用 ready、运行时连接和消息 Composer，排除静态页面 preview；项目选择由生命周期 helper 负责。 */
async function waitForApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
}

/** 首次启动时建立项目选择；只有没有任何既有项目时才允许打开添加项目入口。 */
async function bootstrapProject(page, deadline) {
  const selectedProject = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await selectedProject.count()) === 0) {
    const existingProject = page
      .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
      .first();
    if ((await existingProject.count()) > 0) {
      await existingProject.click({ timeout: timeout(deadline) });
    } else {
      await page.getByRole("button", { name: "添加项目", exact: true }).click({
        timeout: timeout(deadline),
      });
    }
  }
  await selectedProject.waitFor({ state: "visible", timeout: timeout(deadline) });
}

/** reload 只恢复已有项目和真实 Thread，避免异步历史列表尚未恢复时误触发添加项目。 */
async function restoreProjectAndThread(page, threadId, deadline) {
  const project = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]').first();
  await project.waitFor({ state: "visible", timeout: timeout(deadline) });
  await project.click({ timeout: timeout(deadline) });
  await selectThread(page, threadId, deadline);
}

/** 展开工作过程唯一 Disclosure，失败状态自动展开时也先验证其公开状态。 */
async function expandProcess(page, deadline) {
  const process = page.locator("section.ja-work-process").last();
  await process.waitFor({ state: "visible", timeout: timeout(deadline) });
  const trigger = process.locator(".ja-work-process__trigger");
  if ((await trigger.getAttribute("aria-expanded")) !== "true") {
    await trigger.click({ timeout: timeout(deadline) });
  }
  await process.locator(".ja-work-process__steps").waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  return process;
}

/** 按真实 toolName 定位 Tool 详情；presentation kind 可以继续把只读工具归到 READ。 */
function toolDetails(processLocator, name) {
  return processLocator.locator(
    `.ja-tool-details:has(.ja-tool-details__identity code[title="${name}"])`,
  );
}

/** 读取真实 DOM 的动作、工具名、目标、状态和公开诊断正文，避免只按截图或退出码验收。 */
async function projectToolDetails(processLocator) {
  return processLocator.locator(".ja-tool-details").evaluateAll((items) =>
    items.map((item) => {
      const trigger = item.querySelector(".ja-tool-details__trigger");
      const identity = item.querySelector(".ja-tool-details__identity");
      const tool = identity?.querySelector("code[title]");
      const action = identity?.querySelector(".ja-tool-details__label");
      const target = item.querySelector(".ja-tool-details__target");
      const output = item.querySelector(".ja-tool-details__output");
      return {
        toolName: tool?.getAttribute("title") ?? tool?.textContent?.trim() ?? "",
        action: action?.textContent?.trim() ?? "",
        target: target?.textContent?.trim() ?? "",
        status: output?.getAttribute("data-status") ?? "",
        triggerLabel: trigger?.getAttribute("aria-label") ?? "",
        expanded: trigger?.getAttribute("aria-expanded") ?? "",
        output: output?.textContent?.trim() ?? "",
      };
    }),
  );
}

/** Tool 行必须把动作、真实名称与首个目标放在同一可访问入口，并保留稳定状态。 */
async function assertToolIdentity(details, name, targetPattern) {
  const trigger = details.locator(".ja-tool-details__trigger");
  await trigger.waitFor({ state: "visible" });
  const label = await trigger.getAttribute("aria-label");
  const action = await details.locator(".ja-tool-details__label").textContent();
  const expectedAction = TOOL_ACTIONS[name];
  const visibleName = details.locator(`.ja-tool-details__identity code[title="${name}"]`);
  assert.ok((await visibleName.count()) > 0, `${name} must be visible in its Tool identity`);
  assert.notEqual(expectedAction, undefined, `${name} must have a closed action contract`);
  assert.equal(action?.trim(), expectedAction, `${name} must expose its exact action label`);
  assert.ok(label?.includes(name), `${name} must be in the accessible Tool summary`);
  assert.match(label ?? "", targetPattern, `${name} must expose its target`);
}

/** 成功 Tool 的内容只在用户展开后挂载；先验证默认折叠，再读取真实输出状态。 */
async function expandSuccessfulTool(details, name, targetPattern, deadline) {
  await assertToolIdentity(details, name, targetPattern);
  const trigger = details.locator(".ja-tool-details__trigger");
  assert.equal(
    await trigger.getAttribute("aria-expanded"),
    "false",
    `${name} must be collapsed before the runner opens it`,
  );
  await trigger.click({ timeout: timeout(deadline) });
  const output = details.locator('.ja-tool-details__output[data-status="success"]');
  await output.waitFor({ state: "visible", timeout: timeout(deadline) });
  return output;
}

/** 真实回放单次 Tool 目录验收，并在空 query 失败后停在 UI 让 runner 观察。 */
export async function runToolCatalogWebView2({ page, workspaceRoot, evidenceDirectory, fixture }) {
  assert.ok(page, "page is required");
  assert.ok(workspaceRoot, "workspaceRoot is required");
  assert.ok(fixture, "fixture is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const files = await writeToolCatalogWorkspaceFixture(workspaceRoot);
  const deadline = Date.now() + 5 * 60_000;
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  await bootstrapProject(page, deadline);
  const created = await configureFixtureAndCreateThread(page, workspaceRoot, fixture.baseUrl);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  await restoreProjectAndThread(page, created.threadId, deadline);

  const input = page.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(PROMPT);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: timeout(deadline),
  });
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: PROMPT })
    .waitFor({ state: "visible", timeout: timeout(deadline) });

  const liveProcess = page.locator("section.ja-work-process").last();
  const invalidGrep = toolDetails(liveProcess, "grep").first();
  await invalidGrep.waitFor({ state: "visible", timeout: timeout(deadline) });
  const invalidOutput = invalidGrep.locator('.ja-tool-details__output[data-status="error"]');
  await invalidOutput.waitFor({ state: "visible", timeout: timeout(deadline) });
  const invalidTrigger = invalidGrep.locator(".ja-tool-details__trigger");
  assert.equal(
    await invalidTrigger.getAttribute("aria-expanded"),
    "true",
    "failed grep must be automatically expanded",
  );
  const invalidOutputText = await invalidOutput.textContent();
  assert.match(invalidOutputText, /query/iu);
  assert.match(invalidOutputText, /(minLength|non-empty)/iu);
  assert.match(invalidOutputText, /(correct.*argument|retry)/iu);
  await assertToolIdentity(invalidGrep, "grep", /\.|catalog/u);
  fixture.releaseInvalidCorrection();

  for (const name of ["grep", "find", "ls"]) {
    await toolDetails(liveProcess, name).last().waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  }
  await page.getByText(toolCatalogFixtureMarkers.final, { exact: false }).last().waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await waitForCondition(
    "completed tool catalog turn",
    () =>
      page
        .locator('.ja-chat-message-final[data-response-state="completed"]')
        .count()
        .then((count) => count > 0),
    deadline,
  );

  const expandedLiveProcess = await expandProcess(page, deadline);
  const liveSuccessfulDetails = [
    ["grep", toolDetails(expandedLiveProcess, "grep").last()],
    ["find", toolDetails(expandedLiveProcess, "find").last()],
    ["ls", toolDetails(expandedLiveProcess, "ls").last()],
  ];
  for (const [name, details] of liveSuccessfulDetails) {
    await expandSuccessfulTool(details, name, /\S/u, deadline);
  }
  const liveTools = await projectToolDetails(expandedLiveProcess);
  assert.deepEqual(
    liveTools.map((item) => item.toolName),
    ["grep", "grep", "find", "ls"],
    "live UI must preserve the invalid-then-corrected Tool order",
  );
  const liveInvalid = liveTools[0];
  assert.equal(liveInvalid.status, "error");
  assert.match(liveInvalid.output, /query/iu);
  assert.match(liveInvalid.output, /(minLength|non-empty)/iu);
  assert.match(liveInvalid.output, /(correct.*argument|retry)/iu);
  assert.equal(liveInvalid.expanded, "true");
  assert.ok(liveTools.slice(1).every((item) => item.status === "success"));
  const findOutput = liveTools.find((item) => item.toolName === "find")?.output ?? "";
  const lsOutput = liveTools.find((item) => item.toolName === "ls")?.output ?? "";
  assert.match(findOutput, /catalog[\\/]root\.txt/u);
  assert.match(findOutput, /nested[\\/]nested\.txt/u);
  assert.match(findOutput, /deep[\\/]deep\.txt/u);
  assert.doesNotMatch(findOutput, new RegExp(HIDDEN_CONTENT, "u"));
  assert.match(lsOutput, /catalog/u);
  assert.doesNotMatch(lsOutput, /nested[\\/]nested\.txt/u);
  assert.doesNotMatch(lsOutput, new RegExp(HIDDEN_CONTENT, "u"));
  await page.screenshot({
    path: join(evidenceDirectory, "tool-catalog-live.png"),
    animations: "disabled",
  });

  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  await restoreProjectAndThread(page, created.threadId, deadline);
  await page.getByText(toolCatalogFixtureMarkers.final, { exact: false }).last().waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const restoredProcess = await expandProcess(page, deadline);
  const restoredSuccessfulDetails = [
    ["grep", toolDetails(restoredProcess, "grep").last()],
    ["find", toolDetails(restoredProcess, "find").last()],
    ["ls", toolDetails(restoredProcess, "ls").last()],
  ];
  for (const [name, details] of restoredSuccessfulDetails) {
    await expandSuccessfulTool(details, name, /\S/u, deadline);
  }
  const restoredTools = await projectToolDetails(restoredProcess);
  assert.deepEqual(
    restoredTools.map((item) => item.toolName),
    ["grep", "grep", "find", "ls"],
    "reload must preserve Tool identities and order",
  );
  assert.equal(restoredTools[0].status, "error");
  assert.equal(restoredTools[0].toolName, liveInvalid.toolName);
  assert.equal(restoredTools[0].expanded, "true");
  assert.equal(restoredTools[0].triggerLabel, liveInvalid.triggerLabel);
  assert.equal(restoredTools[0].output, liveInvalid.output);
  await page.screenshot({
    path: join(evidenceDirectory, "tool-catalog-reload.png"),
    animations: "disabled",
  });
  assert.deepEqual(pageErrors, [], `WebView2 page errors: ${pageErrors.join(" | ")}`);

  const provider = fixture.snapshot();
  const turns = provider.attempts.filter((attempt) => attempt.kind === "turn");
  assert.equal(turns.length, 5);
  assert.deepEqual(
    turns.map((attempt) => attempt.step),
    [0, 1, 2, 3, 4],
    "Provider continuation must progress through all four Tool calls",
  );
  const catalogNames = turns[0]?.toolNames ?? [];
  for (const name of REQUIRED_BASE_TOOLS) assert.ok(catalogNames.includes(name), `${name} missing`);
  for (const name of FORBIDDEN_DEFAULT_TOOLS) {
    assert.equal(catalogNames.includes(name), false, `${name} must not be exposed by default`);
  }
  assert.equal(new Set(catalogNames).has("list_threads"), false);
  assert.equal(catalogNames.filter((name) => name === "grep").length, 1);
  assert.equal(catalogNames.filter((name) => name === "find").length, 1);
  assert.equal(catalogNames.filter((name) => name === "ls").length, 1);
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
      toolCalls: 4,
      attempts: provider.attempts,
      defaultCatalog: catalogNames,
    },
    live: {
      sequence: liveTools.map((item) => `${item.toolName}:${item.status}`),
      failedGrepExpanded: liveInvalid.expanded === "true",
      actionsVisible: liveTools.every((item) => item.action.length > 0),
      targetsVisible: liveTools.every((item) => item.triggerLabel.includes(item.toolName)),
    },
    reload: {
      sameThread: true,
      sequence: restoredTools.map((item) => `${item.toolName}:${item.status}`),
      failedGrepExpanded: restoredTools[0].expanded === "true",
      errorIdentityPreserved: restoredTools[0].triggerLabel === liveInvalid.triggerLabel,
    },
    workspace: {
      findReturnsPathsOnly: !findOutput.includes(HIDDEN_CONTENT),
      lsIsNonRecursive: !lsOutput.includes("nested/nested.txt") && !lsOutput.includes("nested\\nested.txt"),
      fixtureFiles: [files.root, files.nested, files.deep],
    },
    finalVisible: true,
    screenshots: ["tool-catalog-live.png", "tool-catalog-reload.png"],
    pageErrors,
  };
}

/** 对外报告执行闭集校验，防止仅凭退出码或截图宣称真实 WebView2 通过。 */
export function validateToolCatalogReport(report) {
  assert.equal(report?.schemaVersion, 1);
  assert.equal(report?.status, "passed");
  assert.equal(report?.runtime?.platform, "win32");
  assert.equal(report?.runtime?.surface, "tauri_webview2");
  assert.equal(report?.runtime?.boundary, "jvm_jar");
  assert.equal(report?.runtime?.nativeImageVerified, false);
  assert.equal(report?.provider?.kind, "deterministic_loopback");
  assert.equal(report?.provider?.externalCalls, 0);
  assert.equal(report?.provider?.toolCalls, 4);
  for (const name of REQUIRED_BASE_TOOLS) assert.ok(report?.provider?.defaultCatalog?.includes(name));
  for (const name of FORBIDDEN_DEFAULT_TOOLS) {
    assert.equal(report?.provider?.defaultCatalog?.includes(name), false);
  }
  assert.equal(report?.live?.failedGrepExpanded, true);
  assert.equal(report?.live?.actionsVisible, true);
  assert.equal(report?.live?.targetsVisible, true);
  assert.deepEqual(report?.live?.sequence, ["grep:error", "grep:success", "find:success", "ls:success"]);
  assert.equal(report?.reload?.sameThread, true);
  assert.equal(report?.reload?.failedGrepExpanded, true);
  assert.equal(report?.reload?.errorIdentityPreserved, true);
  assert.deepEqual(report?.reload?.sequence, report?.live?.sequence);
  assert.equal(report?.workspace?.findReturnsPathsOnly, true);
  assert.equal(report?.workspace?.lsIsNonRecursive, true);
  assert.equal(report?.finalVisible, true);
  return report;
}

/** 启动 loopback fixture 与复用 production runner 的隔离真窗生命周期，并保证 listener 总能关闭。 */
async function main() {
  const options = parseProgressArguments(process.argv.slice(2));
  const fixture = await startToolCatalogFixture();
  try {
    const report = await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      maxModelRounds: 5,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      hiddenWindow: true,
      preserveFailedProfile: true,
      driver: async (driverOptions) => {
        try {
          return await runToolCatalogWebView2({ ...driverOptions, fixture });
        } catch (error) {
          reportFixtureFailure(fixture, error);
          const runtimeDiagnostic = await readToolCatalogRuntimeDiagnostics(driverOptions.page);
          await writeFile(
            join(options.evidenceDirectory, "tool-catalog-runtime-diagnostic.json"),
            `${JSON.stringify(runtimeDiagnostic, null, 2)}\n`,
            "utf8",
          ).catch(() => undefined);
          console.error(`JA_TOOL_CATALOG_RUNTIME_DIAGNOSTIC ${JSON.stringify(runtimeDiagnostic)}`);
          await driverOptions.page
            .screenshot({ path: join(options.evidenceDirectory, "tool-catalog-failure.png") })
            .catch(() => {});
          throw error;
        }
      },
      validateReport: validateToolCatalogReport,
      reportFileName: "tool-catalog-report.json",
    });
    console.log(`JA_TOOL_CATALOG_PASS ${JSON.stringify({ status: report.status })}`);
  } finally {
    await fixture.close();
  }
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(`JA_TOOL_CATALOG_FAIL ${String(error?.message ?? error).slice(0, 2000)}`);
    process.exitCode = 1;
  });
}
