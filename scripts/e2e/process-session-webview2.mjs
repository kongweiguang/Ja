// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { realpath } from "node:fs";
import { lstat, mkdir, readFile, readdir, stat, unlink, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { runProduction } from "./review-redesign-production.mjs";
import { providerStream } from "./media-schema-loopback.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const defaultJavaHome = process.env.JAVA_HOME?.trim() ?? "";
const defaultJar = process.env.JA_E2E_APP_SERVER_JAR?.trim()
  ? resolve(repoRoot, process.env.JA_E2E_APP_SERVER_JAR.trim())
  : join(repoRoot, "ja-agent", "ja-app-server", "target", "ja-app-server.jar");
const defaultCargoTarget = join(repoRoot, "target", "codex-review-redesign");
const processPromptMarker = "JA_PROCESS_SESSION_WEBVIEW2_START";
const eofPromptMarker = "JA_PROCESS_SESSION_WEBVIEW2_EOF";
const exitPromptMarker = "JA_PROCESS_SESSION_WEBVIEW2_EXIT_23";
const approvalPromptMarker = "JA_PROCESS_SESSION_WEBVIEW2_APPROVAL";
const deniedPromptMarker = "JA_PROCESS_SESSION_WEBVIEW2_DENIED";
const processCallId = "call_process_session_webview2_start";
const eofCallId = "call_process_session_webview2_eof";
const exitCallId = "call_process_session_webview2_exit_23";
const approvalCallId = "call_process_session_webview2_approval";
const deniedCallId = "call_process_session_webview2_denied";
const processFinalMarker = "PROCESS_SESSION_WEBVIEW2_FINAL_OK";
const eofFinalMarker = "PROCESS_SESSION_WEBVIEW2_EOF_FINAL_OK";
const exitFinalMarker = "PROCESS_SESSION_WEBVIEW2_EXIT_23_FINAL_OK";
const approvalFinalMarker = "PROCESS_SESSION_WEBVIEW2_APPROVAL_FINAL_OK";
const deniedFinalMarker = "PROCESS_SESSION_WEBVIEW2_DENIED_FINAL_OK";
const threadTitle = "Process Session WebView2";

const providerScenarios = [
  {
    name: "process",
    marker: processPromptMarker,
    callId: processCallId,
    final: processFinalMarker,
    command:
      "[Console]::Out.WriteLine('JA_PROCESS_SESSION_READY'); " +
      "$line = [Console]::In.ReadLine(); " +
      "[Console]::Out.WriteLine('JA_PROCESS_SESSION_INPUT:' + $line); " +
      "[void][Console]::In.ReadLine()",
  },
  {
    name: "eof",
    marker: eofPromptMarker,
    callId: eofCallId,
    final: eofFinalMarker,
    command:
      "[Console]::Out.WriteLine('JA_PROCESS_SESSION_EOF_READY'); " +
      "$line = [Console]::In.ReadLine(); " +
      "if ($null -eq $line) { [Console]::Out.WriteLine('JA_PROCESS_SESSION_EOF_OBSERVED'); exit 0 }; " +
      "[Console]::Out.WriteLine('JA_PROCESS_SESSION_EOF_MISSED')",
  },
  {
    name: "exit_23",
    marker: exitPromptMarker,
    callId: exitCallId,
    final: exitFinalMarker,
    command: "[Console]::Out.WriteLine('JA_PROCESS_SESSION_EXIT_23'); exit 23",
  },
  {
    name: "approval",
    marker: approvalPromptMarker,
    callId: approvalCallId,
    final: approvalFinalMarker,
    command: "Write-Output 'JA_APPROVAL_COMMAND_EXECUTED'",
  },
  {
    name: "denied",
    marker: deniedPromptMarker,
    callId: deniedCallId,
    final: deniedFinalMarker,
    command: "Write-Output 'JA_APPROVAL_DENIED_COMMAND_EXECUTED'",
  },
];

/** 命令会话交互只在 Playwright deadline 内轮询真实 DOM，避免固定延时掩盖事件顺序。 */
function timeout(deadline) {
  return Math.max(1, Math.min(30_000, deadline - Date.now()));
}

/** 以单一截止时间轮询页面状态，超时信息保留具体用户可见阶段。 */
async function waitForCondition(label, predicate, deadline) {
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} timed out`);
}

/** 等待给定真实 DOM 控件获得焦点；不通过 Tab 或脚本主动移动焦点制造成功证据。 */
async function waitForFocus(locator, label, deadline) {
  await waitForCondition(
    label,
    () => locator.evaluate((element) => globalThis.document.activeElement === element),
    deadline,
  );
}

/** 把 CLI 证据限制在仓库 `.tmp` 子目录，避免同级、根目录或跨卷目标逃逸。 */
function assertEvidencePath(path) {
  const root = resolve(repoRoot, ".tmp");
  const target = resolve(path);
  const relation = relative(root, target);
  if (relation === "" || relation.split(sep).includes("..") || isAbsolute(relation)) {
    throw new Error("evidence directory must be a child of repository .tmp");
  }
  return target;
}

/** 为报告生成当前 Maven 输入树指纹，并用最新源文件时间戳阻止复用过期 JAR。 */
export async function collectSourceBuildIdentity(root = repoRoot) {
  const digest = createHash("sha256");
  const excludedDirectories = new Set([".git", "node_modules", "target"]);
  let fileCount = 0;
  let latestSourceMtimeMs = 0;

  /** 排序并跳过链接/生成目录，保证摘要稳定且不会读取仓库外文件。 */
  async function visit(path) {
    const metadata = await lstat(path);
    if (metadata.isSymbolicLink()) return;
    if (metadata.isDirectory()) {
      const entries = await readdir(path, { withFileTypes: true });
      entries.sort((left, right) => (left.name < right.name ? -1 : left.name > right.name ? 1 : 0));
      for (const entry of entries) {
        if (entry.isSymbolicLink() || (entry.isDirectory() && excludedDirectories.has(entry.name)))
          continue;
        await visit(join(path, entry.name));
      }
      return;
    }
    if (!metadata.isFile()) return;

    const relativePath = relative(root, path).replaceAll("\\", "/");
    const contents = await readFile(path);
    digest.update(relativePath).update("\0").update(String(contents.byteLength)).update("\0");
    digest.update(contents).update("\0");
    fileCount += 1;
    latestSourceMtimeMs = Math.max(latestSourceMtimeMs, metadata.mtimeMs);
  }

  const sourceRoots = [
    "package.json",
    "ja-agent/pom.xml",
    "ja-agent/ja-agent-core",
    "ja-agent/extensions",
    "ja-agent/ja-app-server",
    "contracts/ja-rpc",
  ];
  for (const sourceRoot of sourceRoots) await visit(join(root, sourceRoot));
  if (fileCount === 0) throw new Error("current App Server source identity is empty");
  return {
    sha256: digest.digest("hex"),
    fileCount,
    latestSourceMtimeMs,
  };
}

/** 解析隔离真窗运行参数；Cargo target 默认复用仓库共享 target，避免重复编译整套依赖。 */
export function parseArguments(argv = process.argv.slice(2)) {
  const stamp = new Date().toISOString().replace(/[:.]/gu, "-");
  const options = {
    evidenceDirectory: join(
      repoRoot,
      ".tmp",
      "daily-coding-20261006",
      `process-session-webview2-${stamp}-${process.pid}`,
    ),
    javaHome: defaultJavaHome,
    jar: defaultJar,
    cargoTargetDirectory: defaultCargoTarget,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) throw new Error(`missing value for ${flag}`);
    if (flag === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (flag === "--java-home") options.javaHome = resolve(value);
    else if (flag === "--jar") options.jar = resolve(value);
    else if (flag === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else throw new Error(`unknown argument: ${flag}`);
    index += 1;
  }
  options.evidenceDirectory = assertEvidencePath(options.evidenceDirectory);
  if (options.cargoTargetDirectory !== resolve(defaultCargoTarget)) {
    throw new Error(
      "process-session WebView2 must use the shared codex-review-redesign Cargo target",
    );
  }
  return Object.freeze(options);
}

/** 递归提取 Provider 最近用户消息文本，以唯一 marker 路由隔离的确定性 UI 场景。 */
function collectText(value, output = []) {
  if (typeof value === "string") output.push(value);
  else if (Array.isArray(value)) value.forEach((item) => collectText(item, output));
  else if (value !== null && typeof value === "object") {
    if (typeof value.text === "string") output.push(value.text);
    else Object.values(value).forEach((item) => collectText(item, output));
  }
  return output.join(" ");
}

/** 只检查最新用户消息，避免同一 Thread 历史中的旧 marker 误触发前一场景。 */
function latestUserText(payload) {
  if (typeof payload?.input === "string") return payload.input;
  const items = Array.isArray(payload?.input) ? payload.input : [];
  const latest = [...items].reverse().find((item) => item?.role === "user");
  return latest === undefined ? "" : collectText(latest.content);
}

/** 限制 loopback Provider 请求体大小，不将完整模型上下文写入运行报告。 */
async function readBoundedJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("Provider request exceeded 4 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 按固定场景建立一次 Tool 调用与终答，确认 UI 的动作实际经过 Java Agent 循环。 */
function responseFor(payload, observations) {
  const serialized = JSON.stringify(payload);
  if (serialized.includes("<user_request>") && serialized.includes("<assistant_reply>")) {
    observations.push({ scenario: "title", phase: "final" });
    return providerStream("openai_responses", "resp_process_session_title", undefined, threadTitle);
  }

  const userText = latestUserText(payload);
  const scenario = providerScenarios.find(({ marker }) => userText.includes(marker));
  if (scenario === undefined)
    throw new Error("unexpected Provider request outside the acceptance prompts");

  const hasResult = (Array.isArray(payload?.input) ? payload.input : []).some(
    (item) => item?.type === "function_call_output" && item.call_id === scenario.callId,
  );
  observations.push({ scenario: scenario.name, phase: hasResult ? "final" : "tool" });
  if (hasResult) {
    return providerStream(
      "openai_responses",
      `resp_process_session_${scenario.name}_final`,
      undefined,
      scenario.final,
    );
  }

  const call = {
    callId: scenario.callId,
    name: "exec_command",
    arguments: {
      cmd: scenario.command,
      timeout_ms: scenario.name === "approval" || scenario.name === "denied" ? 10_000 : 120_000,
      yield_time_ms: 250,
    },
  };
  return providerStream("openai_responses", `resp_process_session_${scenario.name}_tool`, call, "");
}

/** 启动仅监听 loopback 的 OpenAI Responses fixture，并记录不含 Prompt/命令的场景阶段。 */
export async function startProcessSessionProvider() {
  const observations = [];
  let failure;
  const server = createServer((request, response) => {
    void handleProviderRequest(request, response, observations, (error) => {
      failure = error;
    });
  });
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    observations,
    failure: () => failure,
    close: () =>
      new Promise((resolvePromise, reject) => {
        server.close((error) => (error ? reject(error) : resolvePromise()));
      }),
  };
}

/** 响应 Provider 请求；错误只返回稳定类别文本，不回显上下文或宿主路径。 */
async function handleProviderRequest(request, response, observations, recordFailure) {
  response.setHeader("cache-control", "no-store");
  response.setHeader("connection", "close");
  if (request.method !== "POST" || request.url !== "/v1/responses") {
    response.writeHead(404, { "content-type": "application/json" });
    response.end('{"error":"not_found"}');
    return;
  }
  try {
    const payload = await readBoundedJson(request);
    const stream = responseFor(payload, observations);
    response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
    response.end(stream);
  } catch {
    recordFailure("provider_fixture_unexpected_request");
    response.writeHead(500, { "content-type": "application/json" });
    response.end('{"error":"process_session_fixture_failed"}');
  }
}

/** 等待 Runtime Ready，并验证对话输入框属于生产 Tauri composition 页面。 */
async function waitForApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({
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
}

/**
 * 在隔离空 profile 中通过“添加项目”入口选中 runner 创建的唯一 workspace，
 * 并等待 UI 与 App Server 同时确认项目绑定完成，避免首个 Turn 早于 workspace/open。
 * 原生 realpath 与 Java 的 toRealPath 对齐 Windows 8.3 路径；普通 Node realpath 不会展开短名称。
 */
async function bootstrapProject(page, workspaceRoot, deadline) {
  const canonicalRoot = await promisify(realpath.native)(workspaceRoot);
  const selected = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await selected.count()) === 0) {
    const existing = page
      .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
      .first();
    if ((await existing.count()) > 0) await existing.click({ timeout: timeout(deadline) });
    else
      await page.getByRole("button", { name: "添加项目", exact: true }).click({
        timeout: timeout(deadline),
      });
  }
  await selected.waitFor({ state: "visible", timeout: timeout(deadline) });
  const workspaceId = await selected.evaluate((element) =>
    element.closest("[data-workspace-id]")?.getAttribute("data-workspace-id"),
  );
  assert.ok(workspaceId, "selected project row must expose its durable workspace identity");
  let workspaceProbe;
  /** 复用尚未完成的 native 读取，避免轮询超时后堆积不可取消的 RPC 请求。 */
  const probeWorkspace = () => {
    if (workspaceProbe === undefined) {
      workspaceProbe = page
        .evaluate(
          async ({ id, rootPath }) => {
            const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
            const normalizePath = (value) =>
              value.replaceAll("\\", "/").replace(/\/+$/u, "").toLowerCase();
            const history = createHistoryAdapter();
            if (history.workspaceRead === undefined) return false;
            const record = await history.workspaceRead({ workspaceId: id });
            return (
              record.workspaceId === id &&
              record.kind === "project" &&
              normalizePath(record.root) === normalizePath(rootPath)
            );
          },
          { id: workspaceId, rootPath: canonicalRoot },
        )
        .catch(() => false)
        .finally(() => {
          workspaceProbe = undefined;
        });
    }
    return workspaceProbe;
  };
  await waitForCondition(
    "project workspace binding",
    async () => {
      if (!(await selected.isEnabled())) return false;
      // 超时只结束这一轮等待；native 请求真正完成前仍复用同一个 probe。
      return Promise.race([
        probeWorkspace(),
        new Promise((resolvePromise) => setTimeout(() => resolvePromise(false), 1_000)),
      ]);
    },
    deadline,
  );
  return workspaceId;
}

/**
 * @author kongweiguang
 * 占位凭据由生产 Settings/Java owner 创建并回读，避免手写 auth.json 的 ACL 在 elevated runner 上失效。
 * Provider 与模型仍来自隔离配置，Thread 继续通过真实对话控件创建。
 */
async function verifyFixtureProvider(page, endpoint) {
  return page.evaluate(async (providerEndpoint) => {
    const { TauriSettingsAdapter } = await import("/src/api/tauri/settings.ts");
    const settings = new TauriSettingsAdapter();
    const snapshot = await settings.snapshot();
    const provider = snapshot.document.providers.find(
      (candidate) => candidate.providerId === "provider_e2e",
    );
    if (provider === undefined || provider.baseUrl !== providerEndpoint) {
      throw new Error("isolated loopback Provider is unavailable");
    }
    if (provider.models[0]?.modelId === undefined) {
      throw new Error("isolated loopback model is unavailable");
    }
    await settings.setCredential(
      provider.credentialId,
      "unused-review-fixture",
      snapshot.cas.credentialVersion,
    );
    const verified = await settings.snapshot();
    if (
      verified.document.providers.find((candidate) => candidate.providerId === provider.providerId)
        ?.credentialConfigured !== true
    ) {
      throw new Error("isolated loopback credential is unavailable after owner provisioning");
    }
    return { providerId: provider.providerId, modelId: provider.models[0].modelId };
  }, endpoint);
}

/** 通过真实“新会话”控件让生产 controller 创建、观察并选中 Thread，再读取导航身份。 */
async function createThreadFromUI(page, deadline) {
  const action = page.getByRole("button", { name: "新会话", exact: true });
  await action.waitFor({ state: "visible", timeout: timeout(deadline) });
  await action.click({ timeout: timeout(deadline) });
  const composer = page.getByRole("textbox", { name: "消息", exact: true });
  await waitForCondition(
    "Composer focus after the new-conversation action",
    () => composer.evaluate((element) => globalThis.document.activeElement === element),
    deadline,
  );
  const activeThread = page.locator(
    '[aria-label="最近对话列表"] button[aria-current="page"][data-thread-id]',
  );
  await activeThread.waitFor({ state: "visible", timeout: timeout(deadline) });
  const threadId = await activeThread.getAttribute("data-thread-id");
  assert.ok(threadId, "the real new-conversation flow must expose its selected Thread identity");
  return threadId;
}

/** 打开 Composer 的真实访问模式菜单并确认两个用户可见选项。 */
async function chooseAccessMode(page, label, deadline) {
  const selector = page.getByRole("combobox", { name: "访问模式", exact: true });
  await selector.waitFor({ state: "visible", timeout: timeout(deadline) });
  await selector.click({ timeout: timeout(deadline) });
  const approval = page.getByRole("option", { name: "需要审批", exact: true });
  const fullAccess = page.getByRole("option", { name: "完全访问", exact: true });
  await approval.waitFor({ state: "visible", timeout: timeout(deadline) });
  await fullAccess.waitFor({ state: "visible", timeout: timeout(deadline) });
  const availableLabels = [await approval.textContent(), await fullAccess.textContent()].map(
    (value) => value?.trim(),
  );
  await page.getByRole("option", { name: label, exact: true }).click({
    timeout: timeout(deadline),
  });
  await waitForCondition(
    `Composer access mode ${label}`,
    async () => (await selector.textContent())?.trim() === label,
    deadline,
  );
  return availableLabels;
}

/** 提交 Composer 消息并等待该 Prompt 进入用户可见 Timeline。 */
async function submitPrompt(page, prompt, deadline) {
  await page.getByRole("textbox", { name: "消息", exact: true }).fill(prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: timeout(deadline),
  });
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: prompt })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
}

/** 仅接受已经完成的终答 marker，保证后续进程操作确实发生在 Turn 收口后。 */
async function waitForFinal(page, marker, deadline) {
  const final = page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .filter({ hasText: marker })
    .last();
  await final.waitFor({ state: "visible", timeout: timeout(deadline) });
  return final;
}

/** 展开最后一个真实 WorkProcess 与指定 Tool，供会话面板操作和输出断言使用。 */
async function expandTool(page, toolName, deadline) {
  const process = page.locator("section.ja-work-process").last();
  await process.waitFor({ state: "visible", timeout: timeout(deadline) });
  const processTrigger = process.locator(".ja-work-process__trigger");
  if ((await processTrigger.getAttribute("aria-expanded")) !== "true") {
    await processTrigger.click({ timeout: timeout(deadline) });
  }
  const details = process.locator(`.ja-tool-details[data-tool-name="${toolName}"]`).last();
  const trigger = details.locator(".ja-tool-details__trigger");
  await trigger.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await trigger.getAttribute("aria-expanded")) !== "true") {
    await trigger.click({ timeout: timeout(deadline) });
  }
  return { process, details };
}

/** 按关联命令锁定唯一公开审批卡，避免历史审批记录让状态和焦点断言串卡。 */
async function waitForApprovalCard(page, command, deadline) {
  const card = page.locator("section.ja-approval-card").filter({ hasText: command });
  await card.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await card.count(), 1, "approval command must identify one visible card");
  const heading = card.getByRole("heading", { name: "工具调用需要确认", exact: true });
  await heading.waitFor({ state: "visible", timeout: timeout(deadline) });
  await card.getByText("执行命令", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await card.getByText(command, { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  return card;
}

/** 从同一 Thread 的历史和受控进程接口核对真实 ToolResult，避免 Provider fixture 终答掩盖执行失败。 */
async function readFirstTurnRuntimeEvidence(page, { threadId, workspaceId, callId }) {
  return page.evaluate(
    async ({ selectedThreadId, selectedWorkspaceId, startedCallId }) => {
      const [{ createHistoryAdapter }, { createProcessSessionAdapter }] = await Promise.all([
        import("/src/api/tauri/history.ts"),
        import("/src/api/tauri/process.ts"),
      ]);
      const history = createHistoryAdapter();
      const processSessions = createProcessSessionAdapter();
      const [thread, workspace] = await Promise.all([
        history.threadRead({ threadId: selectedThreadId, limit: 200 }),
        selectedWorkspaceId === undefined || history.workspaceRead === undefined
          ? Promise.resolve(undefined)
          : history.workspaceRead({ workspaceId: selectedWorkspaceId }).catch(() => undefined),
      ]);
      const toolCalls = thread.items
        .filter((item) => item.kind === "tool_call" && item.callId === startedCallId)
        .map((item) => ({
          turnId: item.turnId,
          callId: item.callId,
          toolName: item.toolName,
          presentation: {
            kind: item.presentation.kind,
            status: item.presentation.status,
            command: item.presentation.command,
            summary: item.presentation.summary,
            outputPreview: item.presentation.outputPreview,
            stdout: item.presentation.stdout,
            stderr: item.presentation.stderr,
            exitCode: item.presentation.exitCode,
            recovery: item.presentation.recovery,
          },
        }));
      if (workspace === undefined) {
        return {
          thread: {
            threadId: thread.threadId,
            turns: thread.turns.map(({ turnId, status, errorCode }) => ({
              turnId,
              status,
              errorCode,
            })),
            toolCalls,
          },
          workspaceFound: false,
          process: null,
        };
      }
      try {
        const page = await processSessions.list({
          workspaceId: workspace.workspaceId,
          threadId: selectedThreadId,
          limit: 20,
          cursor: null,
          startedCallId,
        });
        const session = page.sessions[0];
        const snapshot =
          session === undefined
            ? null
            : await processSessions.read({
                workspaceId: workspace.workspaceId,
                threadId: selectedThreadId,
                generation: page.generation,
                sessionId: session.sessionId,
                afterSequence: 0,
                maxBytes: 65_536,
              });
        return {
          thread: {
            threadId: thread.threadId,
            turns: thread.turns.map(({ turnId, status, errorCode }) => ({
              turnId,
              status,
              errorCode,
            })),
            toolCalls,
          },
          workspaceFound: true,
          process: {
            workspaceId: workspace.workspaceId,
            threadId: selectedThreadId,
            generation: page.generation,
            sessions: page.sessions.map(
              ({
                workspaceId: ownerWorkspaceId,
                threadId: ownerThreadId,
                generation,
                sessionId,
                startedCallId: ownerCallId,
                startedTurnId,
                state,
                outputSequence,
              }) => ({
                workspaceId: ownerWorkspaceId,
                threadId: ownerThreadId,
                generation,
                sessionId,
                startedCallId: ownerCallId,
                startedTurnId,
                state,
                outputSequence,
              }),
            ),
            snapshot:
              snapshot === null
                ? null
                : {
                    workspaceId: snapshot.workspaceId,
                    threadId: snapshot.threadId,
                    generation: snapshot.generation,
                    sessionId: snapshot.sessionId,
                    startedCallId: snapshot.startedCallId,
                    state: snapshot.state,
                    outputSequence: snapshot.outputSequence,
                    output: snapshot.output,
                    nextSequence: snapshot.nextSequence,
                  },
          },
        };
      } catch (error) {
        return {
          thread: {
            threadId: thread.threadId,
            turns: thread.turns.map(({ turnId, status, errorCode }) => ({
              turnId,
              status,
              errorCode,
            })),
            toolCalls,
          },
          workspaceFound: true,
          process: {
            error: String(error?.code ?? error?.message ?? error).slice(0, 500),
          },
        };
      }
    },
    { selectedThreadId: threadId, selectedWorkspaceId: workspaceId, startedCallId: callId },
  );
}

/** 失败时保存准入阶段与受限 Tool DOM，区分乐观消息和真实回执；不覆盖更早快照或落盘完整正文。 */
async function captureFailureArtifacts(
  page,
  evidenceDirectory,
  threadId,
  error,
  pageErrors,
  consoleErrors,
) {
  let runtimeEvidenceStatus = "thread_unavailable";
  if (threadId !== undefined) {
    const runtimeEvidencePath = join(
      evidenceDirectory,
      "process-session-first-turn-runtime-evidence.json",
    );
    try {
      await stat(runtimeEvidencePath);
      runtimeEvidenceStatus = "preserved_before_failure";
    } catch {
      try {
        const workspaceId = await page
          .locator('[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]')
          .evaluate((element) =>
            element.closest("[data-workspace-id]")?.getAttribute("data-workspace-id"),
          );
        const runtimeEvidence = await readFirstTurnRuntimeEvidence(page, {
          threadId,
          workspaceId,
          callId: processCallId,
        });
        await writeFile(
          runtimeEvidencePath,
          `${JSON.stringify(runtimeEvidence, null, 2)}\n`,
          "utf8",
        );
        runtimeEvidenceStatus = "captured_at_failure";
      } catch (captureError) {
        runtimeEvidenceStatus = `capture_failed:${String(captureError?.message ?? captureError).slice(0, 300)}`;
      }
    }
  }
  await page
    .screenshot({
      path: join(evidenceDirectory, "failure-current-view.png"),
      animations: "disabled",
    })
    .catch(() => undefined);
  const dom = await page
    .evaluate(() => ({
      activeThreadId: globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"][data-thread-id]')
        ?.getAttribute("data-thread-id"),
      composerState: globalThis.document
        .querySelector('[aria-label="消息"]')
        ?.closest("[data-state]")
        ?.getAttribute("data-state"),
      alerts: Array.from(globalThis.document.querySelectorAll('[role="alert"]'))
        .slice(0, 8)
        .map((element) => (element.textContent ?? "").trim().slice(0, 500)),
      nativeInvokes: globalThis.__jaProcessSessionAdmissionInvokes ?? [],
      workProcesses: Array.from(
        globalThis.document.querySelectorAll("section.ja-work-process"),
      ).map((process) => ({
        text: (process.textContent ?? "").trim().slice(0, 1_500),
        tools: Array.from(
          process.querySelectorAll('.ja-tool-details[data-tool-name="exec_command"]'),
        ).map((details) => ({
          status: details.getAttribute("data-status"),
          presentationKind: details.getAttribute("data-tool-kind"),
          expanded: details
            .querySelector(".ja-tool-details__trigger")
            ?.getAttribute("aria-expanded"),
          processPanelPresent: details.querySelector(".ja-process-session") !== null,
          regions: Array.from(details.querySelectorAll('[role="region"]')).map((region) =>
            region.getAttribute("aria-label"),
          ),
          processPanelText:
            details.querySelector(".ja-process-session")?.textContent?.trim().slice(0, 500) ?? null,
        })),
      })),
    }))
    .catch((captureError) => ({ captureError: String(captureError?.message ?? captureError) }));
  const exportTrace = await page
    .evaluate(() => {
      const picker = globalThis.__jaProcessSessionSavePickerCapture;
      return {
        historyInvokes: globalThis.__jaProcessSessionExportInvokes ?? [],
        saveHandle: picker
          ? {
              stage: picker.stage,
              events: picker.events,
              writeCount: picker.writeCount,
              bytesWritten: picker.bytesWritten,
              suggestedName: picker.suggestedName,
            }
          : null,
      };
    })
    .catch((captureError) => ({ captureError: String(captureError?.message ?? captureError) }));
  await writeFile(
    join(evidenceDirectory, "process-session-webview2-failure-diagnostics.json"),
    `${JSON.stringify(
      {
        threadId,
        runtimeEvidenceStatus,
        error: String(error?.message ?? error).slice(0, 2_000),
        dom,
        exportTrace,
        pageErrors,
        consoleErrors,
      },
      null,
      2,
    )}\n`,
    "utf8",
  );
}

/** 记录页面运行错误，区分测试的 JS/console 故障与预期工具业务状态。 */
function observePageErrors(page, pageErrors, consoleErrors) {
  page.__jaProcessSessionPageErrors = pageErrors;
  page.__jaProcessSessionConsoleErrors = consoleErrors;
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text().slice(0, 500));
  });
}

/**
 * 只在 E2E delegate 接缝记录有界命令身份与回执阶段，区分乐观消息和真实准入；
 * 请求正文、配置与原生错误全文不进入诊断，原始结果和异常仍交给生产 adapter。
 */
function installAdmissionInvokeProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  const records = [];
  globalThis.__jaProcessSessionAdmissionInvokes = records;
  /** 前置保留首批请求，避免后续轮询覆盖导致首轮未受理的证据。 */
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const input = request?.args?.input;
    const record = {
      command: request?.command,
      threadId: typeof input?.threadId === "string" ? input.threadId : undefined,
      workspaceId: typeof input?.workspaceId === "string" ? input.workspaceId : undefined,
      startedAt: performance.now(),
      state: "pending",
    };
    if (records.length < 128) records.push(record);
    try {
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      record.state = "resolved";
      record.completedAt = performance.now();
      record.turnId = typeof result?.turnId === "string" ? result.turnId : undefined;
      return result;
    } catch (error) {
      record.state = "rejected";
      record.completedAt = performance.now();
      record.errorCode = typeof error?.code === "string" ? error.code : "native_error";
      throw error;
    }
  };
}

/** 复用真实 Thread 和隔离 Provider；诊断先观察准入，拒绝场景核对 ProcessSessionPanel 保留的 ToolResult。 */
export async function runProcessSessionWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  fixture,
}) {
  assert.ok(page, "page is required");
  assert.ok(workspaceRoot, "workspaceRoot is required");
  assert.ok(fixture, "Provider fixture is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const pageErrors = [];
  const consoleErrors = [];
  observePageErrors(page, pageErrors, consoleErrors);
  const deadline = Date.now() + 10 * 60_000;

  await page.addInitScript(installAdmissionInvokeProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  const workspaceId = await bootstrapProject(page, workspaceRoot, deadline);
  await verifyFixtureProvider(page, fixture.baseUrl);
  const threadId = await createThreadFromUI(page, deadline);
  const pageIdentity = { url: page.url(), title: await page.title() };
  assert.match(pageIdentity.url, /localhost:/u);
  assert.equal(pageIdentity.title, "Ja · Coding Harness");
  assert.equal((await page.locator("#root > *").count()) > 0, true);

  const permissionLabels = await chooseAccessMode(page, "完全访问", deadline);
  await page.screenshot({
    path: join(evidenceDirectory, "01-permission-full-access.png"),
    animations: "disabled",
  });
  const processPrompt = `Start a persistent process for ${processPromptMarker}.`;
  await submitPrompt(page, processPrompt, deadline);
  await waitForFinal(page, processFinalMarker, deadline);
  const firstTurnEvidence = await readFirstTurnRuntimeEvidence(page, {
    threadId,
    workspaceId,
    callId: processCallId,
  });
  await writeFile(
    join(evidenceDirectory, "process-session-first-turn-runtime-evidence.json"),
    `${JSON.stringify(firstTurnEvidence, null, 2)}\n`,
    "utf8",
  );
  const firstTool = firstTurnEvidence.thread.toolCalls[0];
  assert.equal(
    firstTool?.callId,
    processCallId,
    "thread/read must retain the provider call identity",
  );
  assert.equal(firstTool?.toolName, "exec_command", "thread/read must contain the real shell call");
  assert.equal(firstTool?.presentation.status, "success", "thread/read must confirm Tool success");
  assert.equal(
    firstTurnEvidence.process?.sessions?.length,
    1,
    "process/list must return this call's session",
  );
  const listedSession = firstTurnEvidence.process.sessions[0];
  const processSnapshot = firstTurnEvidence.process.snapshot;
  assert.equal(firstTurnEvidence.process.threadId, threadId);
  assert.equal(firstTurnEvidence.process.workspaceId, listedSession.workspaceId);
  assert.equal(listedSession.threadId, threadId);
  assert.equal(listedSession.startedCallId, processCallId);
  assert.equal(listedSession.state, "running");
  assert.equal(processSnapshot.workspaceId, listedSession.workspaceId);
  assert.equal(processSnapshot.threadId, threadId);
  assert.equal(processSnapshot.generation, listedSession.generation);
  assert.equal(processSnapshot.startedCallId, processCallId);
  assert.equal(processSnapshot.sessionId, listedSession.sessionId);
  assert.match(processSnapshot.sessionId, /^proc_[0-9a-f]{32}$/u);
  assert.equal(processSnapshot.state, "running");
  assert.ok(processSnapshot.output.includes("JA_PROCESS_SESSION_READY"));
  const { details: processDetails } = await expandTool(page, "exec_command", deadline);
  assert.equal(await processDetails.getAttribute("data-status"), "success");
  assert.equal(firstTool?.presentation.kind, "shell");
  assert.equal(
    await processDetails.locator(".ja-process-session").count(),
    1,
    "the confirmed shell Tool must render exactly one persistent process panel",
  );
  assert.equal(
    await processDetails.locator(".ja-tool-details__output").count(),
    0,
    "a matched process snapshot must replace the duplicate static ToolResult output",
  );
  const sessionPanel = processDetails.getByRole("region", { name: "持续命令会话", exact: true });
  await sessionPanel.waitFor({ state: "visible", timeout: timeout(deadline) });
  const output = sessionPanel.getByLabel("命令输出", { exact: true });
  await output.getByText("JA_PROCESS_SESSION_READY", { exact: false }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  assert.equal(
    (await page
      .locator('.ja-chat-message-final[data-response-state="completed"]')
      .filter({ hasText: processFinalMarker })
      .count()) > 0,
    true,
    "the process must remain available after its Agent Turn has completed",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "02-process-after-turn.png"),
    animations: "disabled",
  });

  const processInput = sessionPanel.getByRole("textbox", { name: "输入到命令", exact: true });
  await processInput.fill("JA_PROCESS_SESSION_WEBVIEW2_INPUT\n");
  await sessionPanel.getByRole("button", { name: "发送输入", exact: true }).click({
    timeout: timeout(deadline),
  });
  await output
    .getByText("JA_PROCESS_SESSION_INPUT:JA_PROCESS_SESSION_WEBVIEW2_INPUT", {
      exact: false,
    })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await page.screenshot({
    path: join(evidenceDirectory, "03-process-input-observed.png"),
    animations: "disabled",
  });

  const stop = sessionPanel.getByRole("button", { name: "停止进程", exact: true });
  await stop.click({ timeout: timeout(deadline) });
  const exitNotice = sessionPanel.getByText(/^进程已结束，退出码 -?\d+。$/u);
  await exitNotice.waitFor({ state: "visible", timeout: timeout(deadline) });
  const exitNoticeText = (await exitNotice.textContent())?.trim() ?? "";
  const exitCode = Number(exitNoticeText.match(/退出码 (-?\d+)/u)?.[1]);
  assert.ok(
    Number.isSafeInteger(exitCode),
    "the process panel must show an authoritative exit code",
  );
  assert.equal(await sessionPanel.getByRole("textbox", { name: "输入到命令" }).count(), 0);
  await waitForFocus(output, "focus returns to command output after stop", deadline);
  const focusAfterStop = await page.evaluate(() => ({
    tag: globalThis.document.activeElement?.tagName ?? null,
    label: globalThis.document.activeElement?.getAttribute("aria-label") ?? null,
    className: globalThis.document.activeElement?.getAttribute("class") ?? null,
    text: globalThis.document.activeElement?.textContent?.trim().slice(0, 80) ?? "",
  }));
  const keyboardFocusRecoveredAfterStop = focusAfterStop.label === "命令输出";
  assert.equal(keyboardFocusRecoveredAfterStop, true);
  await page.screenshot({
    path: join(evidenceDirectory, "04-process-stopped.png"),
    animations: "disabled",
  });

  const eofPrompt = `Close stdin without sending text. ${eofPromptMarker}`;
  await submitPrompt(page, eofPrompt, deadline);
  await waitForFinal(page, eofFinalMarker, deadline);
  const { details: eofDetails } = await expandTool(page, "exec_command", deadline);
  assert.equal(await eofDetails.getAttribute("data-status"), "success");
  const eofPanel = eofDetails.getByRole("region", { name: "持续命令会话", exact: true });
  const eofOutput = eofPanel.getByLabel("命令输出", { exact: true });
  await eofOutput.getByText("JA_PROCESS_SESSION_EOF_READY", { exact: false }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await eofPanel.getByLabel("发送后关闭输入", { exact: true }).check({
    timeout: timeout(deadline),
  });
  await eofPanel.getByRole("button", { name: "关闭输入", exact: true }).click({
    timeout: timeout(deadline),
  });
  await eofOutput.getByText("JA_PROCESS_SESSION_EOF_OBSERVED", { exact: false }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const eofExit = eofPanel.getByText("进程已结束，退出码 0。", { exact: true });
  await eofExit.waitFor({ state: "visible", timeout: timeout(deadline) });
  await waitForFocus(eofOutput, "focus returns to command output after stdin EOF", deadline);
  await page.screenshot({
    path: join(evidenceDirectory, "05-process-stdin-eof.png"),
    animations: "disabled",
  });

  const exitPrompt = `Run a command that exits naturally with code 23. ${exitPromptMarker}`;
  await submitPrompt(page, exitPrompt, deadline);
  await waitForFinal(page, exitFinalMarker, deadline);
  const { details: exitDetails } = await expandTool(page, "exec_command", deadline);
  assert.equal(await exitDetails.getAttribute("data-status"), "success");
  const exitPanel = exitDetails.getByRole("region", { name: "持续命令会话", exact: true });
  const exitOutput = exitPanel.getByLabel("命令输出", { exact: true });
  await exitOutput.getByText("JA_PROCESS_SESSION_EXIT_23", { exact: false }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const naturalExitNotice = exitPanel.getByText("进程已结束，退出码 23。", { exact: true });
  await naturalExitNotice.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await exitPanel.getByRole("textbox", { name: "输入到命令" }).count(), 0);
  await page.screenshot({
    path: join(evidenceDirectory, "06-process-natural-exit-23.png"),
    animations: "disabled",
  });

  const approvalLabels = await chooseAccessMode(page, "需要审批", deadline);
  const approvalPrompt = `Run one harmless command after approval. ${approvalPromptMarker}`;
  await submitPrompt(page, approvalPrompt, deadline);
  const approvalCard = await waitForApprovalCard(
    page,
    "Write-Output 'JA_APPROVAL_COMMAND_EXECUTED'",
    deadline,
  );
  assert.equal(
    await approvalCard.getByText(approvalCallId, { exact: true }).count(),
    0,
    "the approval card must not expose the internal call identity",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "07-approval-pending.png"),
    animations: "disabled",
  });
  await approvalCard.getByRole("button", { name: "批准", exact: true }).click({
    timeout: timeout(deadline),
  });
  await approvalCard.getByText("已批准", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const approvedStatus = approvalCard.getByText("已批准", { exact: true });
  assert.equal(await approvedStatus.getAttribute("role"), "status");
  await waitForFocus(approvedStatus, "focus returns to the approved decision status", deadline);
  await waitForFinal(page, approvalFinalMarker, deadline);
  const { details: approvedDetails } = await expandTool(page, "exec_command", deadline);
  assert.equal(await approvedDetails.getAttribute("data-status"), "success");
  await approvedDetails.getByText("JA_APPROVAL_COMMAND_EXECUTED", { exact: false }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await approvalCard.scrollIntoViewIfNeeded();
  assert.equal(
    await approvedStatus.isVisible(),
    true,
    "approved card status must be in the screenshot",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "08-approval-completed.png"),
    animations: "disabled",
  });

  const deniedPrompt = `Do not run the command after denial. ${deniedPromptMarker}`;
  await submitPrompt(page, deniedPrompt, deadline);
  const deniedCard = await waitForApprovalCard(
    page,
    "Write-Output 'JA_APPROVAL_DENIED_COMMAND_EXECUTED'",
    deadline,
  );
  assert.equal(
    await deniedCard.getByText(deniedCallId, { exact: true }).count(),
    0,
    "the denied approval card must not expose the internal call identity",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "09-approval-denied-pending.png"),
    animations: "disabled",
  });
  await deniedCard.getByRole("button", { name: "拒绝", exact: true }).click({
    timeout: timeout(deadline),
  });
  const deniedStatus = deniedCard.getByText("已拒绝", { exact: true });
  await deniedStatus.waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  assert.equal(await deniedStatus.getAttribute("role"), "status");
  await waitForFocus(deniedStatus, "focus returns to the denied decision status", deadline);
  await waitForFinal(page, deniedFinalMarker, deadline);
  const { details: deniedDetails } = await expandTool(page, "exec_command", deadline);
  const deniedToolStatus = await deniedDetails.getAttribute("data-status");
  assert.equal(deniedToolStatus, "error", "a denied command must not be recorded as successful");
  const deniedOutput = deniedDetails.locator(".ja-process-session__output");
  await deniedOutput.waitFor({ state: "visible", timeout: timeout(deadline) });
  const deniedOutputText = (await deniedOutput.textContent())?.trim() ?? "";
  assert.match(deniedOutputText, /did not approve this Tool call/u);
  assert.doesNotMatch(deniedOutputText, /JA_APPROVAL_DENIED_COMMAND_EXECUTED/u);
  await deniedCard.scrollIntoViewIfNeeded();
  assert.equal(
    await deniedStatus.isVisible(),
    true,
    "denied card status must be in the screenshot",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "10-approval-denied.png"),
    animations: "disabled",
  });

  const exportMenu = page.getByRole("button", { name: "打开对话操作", exact: true });
  await exportMenu.waitFor({ state: "visible", timeout: timeout(deadline) });
  // 自动标题允许按真实超时保留首问短标题；导出必须对应当前会话显示标题，不能依赖后台标题生成必然成功。
  const exportTitle = await page
    .locator(
      `[aria-label="最近对话列表"] button[aria-current="page"][data-thread-id="${threadId}"]`,
    )
    .getAttribute("aria-label");
  assert.ok(exportTitle?.trim(), "the exported Thread must expose its current title");
  await exportMenu.click({ timeout: timeout(deadline) });
  const exportItem = page.getByRole("menuitem", { name: "导出对话 Markdown", exact: true });
  await exportItem.waitFor({ state: "visible", timeout: timeout(deadline) });
  const savePickerAvailable = await page.evaluate(
    () => typeof globalThis.window.showSaveFilePicker === "function",
  );
  let markdownBytes;
  let filename;
  let exportFeedback;
  let pickerBoundary;
  if (savePickerAvailable) {
    await page.evaluate(() => {
      globalThis.__jaProcessSessionSavePickerOriginal = Object.getOwnPropertyDescriptor(
        globalThis,
        "showSaveFilePicker",
      );
      const capture = (globalThis.__jaProcessSessionSavePickerCapture = {
        stage: "installed",
        writeCount: 0,
        bytesWritten: 0,
        events: [],
      });
      Object.defineProperty(globalThis, "showSaveFilePicker", {
        configurable: true,
        value: async (options) => {
          capture.stage = "picker_requested";
          capture.events.push("picker_requested");
          capture.suggestedName = options.suggestedName;
          return {
            createWritable: async () => {
              capture.stage = "writer_opened";
              capture.events.push("writer_opened");
              const chunks = [];
              return {
                write: async (chunk) => {
                  capture.stage = "writing";
                  capture.writeCount += 1;
                  capture.bytesWritten += chunk.byteLength;
                  capture.events.push("write_started");
                  chunks.push(chunk);
                },
                close: async () => {
                  capture.events.push("close_started");
                  const decoder = new TextDecoder();
                  let content = "";
                  for (const chunk of chunks) content += decoder.decode(chunk, { stream: true });
                  content += decoder.decode();
                  capture.stage = "closed";
                  capture.content = content;
                  capture.events.push("closed");
                },
                abort: async () => capture.events.push("aborted"),
              };
            },
          };
        },
      });
    });
    await page.evaluate(() => {
      globalThis.__jaProcessSessionExportInvokes = [];
      const previousProbe = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
      globalThis.__jaProcessSessionPreviousInvokeProbe = previousProbe;
      globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
        const command = request?.command;
        if (command !== "ja_thread_read" && command !== "ja_thread_item_content_read") {
          return previousProbe === undefined ? delegate() : previousProbe(request, delegate);
        }
        const input = request?.args?.input ?? {};
        const observation = {
          command,
          threadId: typeof input.threadId === "string" ? input.threadId : null,
          cursor: typeof input.cursor === "string" ? input.cursor : null,
          itemId: typeof input.itemId === "string" ? input.itemId : null,
          offsetCharacters: Number.isSafeInteger(input.offsetCharacters)
            ? input.offsetCharacters
            : null,
          limit: Number.isSafeInteger(input.limit) ? input.limit : null,
          limitCharacters: Number.isSafeInteger(input.limitCharacters)
            ? input.limitCharacters
            : null,
          state: "started",
        };
        globalThis.__jaProcessSessionExportInvokes.push(observation);
        try {
          const result =
            previousProbe === undefined ? await delegate() : await previousProbe(request, delegate);
          observation.state = "succeeded";
          observation.result =
            command === "ja_thread_read"
              ? {
                  threadId: result?.threadId ?? null,
                  revision: Number.isSafeInteger(result?.revision) ? result.revision : null,
                  itemCount: Array.isArray(result?.items) ? result.items.length : null,
                  nextCursor: typeof result?.nextCursor === "string" ? result.nextCursor : null,
                }
              : {
                  itemId: result?.itemId ?? null,
                  offsetCharacters: Number.isSafeInteger(result?.offsetCharacters)
                    ? result.offsetCharacters
                    : null,
                  nextOffsetCharacters: Number.isSafeInteger(result?.nextOffsetCharacters)
                    ? result.nextOffsetCharacters
                    : null,
                  totalCharacters: Number.isSafeInteger(result?.totalCharacters)
                    ? result.totalCharacters
                    : null,
                  contentCharacters:
                    typeof result?.content === "string" ? Array.from(result.content).length : null,
                };
          return result;
        } catch (error) {
          observation.state = "failed";
          observation.error = {
            name: typeof error?.name === "string" ? error.name : null,
            code: typeof error?.code === "string" ? error.code : null,
            message: String(error?.message ?? error).slice(0, 1_000),
          };
          throw error;
        }
      };
    });
    let pickerCapture;
    let saved = false;
    try {
      await exportItem.click({ timeout: timeout(deadline) });
      const savedFeedback = page.getByText("对话已导出。", { exact: true });
      const failedFeedback = page.getByText("导出失败，请重试。", { exact: true });
      await waitForCondition(
        "Markdown export success or failure feedback",
        async () => (await savedFeedback.isVisible()) || (await failedFeedback.isVisible()),
        deadline,
      );
      saved = await savedFeedback.isVisible();
      pickerCapture = await page.evaluate(() => ({
        ...globalThis.__jaProcessSessionSavePickerCapture,
        exportInvokes: globalThis.__jaProcessSessionExportInvokes ?? [],
      }));
    } finally {
      await page.evaluate(() => {
        const original = globalThis.__jaProcessSessionSavePickerOriginal;
        const previousProbe = globalThis.__jaProcessSessionPreviousInvokeProbe;
        if (previousProbe === undefined)
          Reflect.deleteProperty(globalThis, "__JA_E2E_NATIVE_INVOKE_PROBE__");
        else globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = previousProbe;
        if (original) Object.defineProperty(globalThis, "showSaveFilePicker", original);
        else Reflect.deleteProperty(globalThis, "showSaveFilePicker");
        Reflect.deleteProperty(globalThis, "__jaProcessSessionPreviousInvokeProbe");
        Reflect.deleteProperty(globalThis, "__jaProcessSessionSavePickerOriginal");
      });
    }
    if (!saved) throw new Error("Markdown export failed at " + JSON.stringify(pickerCapture));
    assert.ok(pickerCapture && typeof pickerCapture.content === "string");
    filename = pickerCapture.suggestedName;
    markdownBytes = Buffer.from(pickerCapture.content, "utf8");
    await writeFile(join(evidenceDirectory, filename), markdownBytes);
    exportFeedback = "对话已导出。";
    pickerBoundary = "cdp_test_file_handle";
    await page.evaluate(() =>
      Reflect.deleteProperty(globalThis, "__jaProcessSessionSavePickerCapture"),
    );
  } else {
    const downloadPromise = page.waitForEvent("download", { timeout: timeout(deadline) });
    await exportItem.click({ timeout: timeout(deadline) });
    const download = await downloadPromise;
    filename = download.suggestedFilename();
    const markdownPath = join(evidenceDirectory, filename);
    await download.saveAs(markdownPath);
    markdownBytes = await readFile(markdownPath);
    exportFeedback = "Markdown 文件已开始下载。";
    pickerBoundary = "browser_download";
  }
  const markdown = markdownBytes.toString("utf8");
  assert.equal(markdown.split(/\r?\n/u)[0], `# ${exportTitle}`);
  assert.ok(markdown.split(/\r?\n/u).includes(`Thread: ${threadId}`));
  assert.match(markdown, /^Revision: \d+$/mu);
  const messageHeadings = [...markdown.matchAll(/^## (用户|助手) · [^\r\n]*\r?\n\r?\n/gmu)];
  assert.deepEqual(
    messageHeadings.map((heading) => heading[1]),
    ["用户", "助手", "用户", "助手", "用户", "助手", "用户", "助手", "用户", "助手"],
    "the whole-session export must contain every complete user/assistant turn in order",
  );
  const messageBodies = messageHeadings.map((heading, index) =>
    markdown
      .slice(
        heading.index + heading[0].length,
        messageHeadings[index + 1]?.index ?? markdown.length,
      )
      .trim(),
  );
  assert.deepEqual(messageBodies, [
    processPrompt,
    processFinalMarker,
    eofPrompt,
    eofFinalMarker,
    exitPrompt,
    exitFinalMarker,
    approvalPrompt,
    approvalFinalMarker,
    deniedPrompt,
    deniedFinalMarker,
  ]);
  await page.getByText(exportFeedback, { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const markdownPath = join(evidenceDirectory, filename);
  const bytes = await readFile(markdownPath);
  assert.deepEqual(bytes, markdownBytes, "saved export bytes must match the captured file handle");
  const exportEvidence = {
    status: "verified",
    menuActionVisible: true,
    savePickerAvailable,
    pickerBoundary,
    nativePickerSelectionVerified: false,
    filename,
    size: bytes.length,
    sha256: createHash("sha256").update(bytes).digest("hex"),
    markdownContainsAllAcceptanceTurns: true,
    messageSections: messageBodies.length,
    successFeedback: true,
  };
  await page.screenshot({
    path: join(evidenceDirectory, "11-markdown-exported.png"),
    animations: "disabled",
  });

  const errorOverlays = await page
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
        .map((node) => (node.textContent ?? "").trim().slice(0, 300)),
    );
  assert.deepEqual(errorOverlays, []);
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(consoleErrors, []);
  assert.equal(fixture.failure(), undefined);
  const screenshots = (await readdir(evidenceDirectory)).filter((name) => name.endsWith(".png"));
  return {
    contractVersion: 1,
    status: "passed",
    page: pageIdentity,
    threadId,
    browserPlugin: "not_available_regular_playwright_cdp",
    runtimeBoundary: "windows_tauri_webview2_debug_jar",
    viewport: page.viewportSize(),
    process: {
      turnCompletedBeforeInput: true,
      firstTurnEvidence,
      initialOutputVisible: true,
      inputProducedIncrementalOutput: true,
      stopReachedExitState: true,
      exitCode,
      focusAfterStop,
      keyboardFocusRecoveredAfterStop,
      stdinEof: {
        emptyInputSubmitted: true,
        eofObserved: true,
        exitCode: 0,
        focusReturnedToOutput: true,
      },
      naturalExit: {
        outputMarkerVisible: true,
        exitCode: 23,
        inputControlsRemoved: true,
      },
    },
    permissions: {
      labels: [...new Set([...permissionLabels, ...approvalLabels])],
      fullAccessStartedProcess: true,
      approvalRequiredToolWasApproved: true,
      approvedToolProducedExpectedOutput: true,
      approvedDecisionFocusReturned: true,
      approvalRequiredToolWasDenied: true,
      deniedToolStatus,
      deniedCommandDidNotRun: !/JA_APPROVAL_DENIED_COMMAND_EXECUTED/u.test(deniedOutputText),
      deniedDecisionFocusReturned: true,
    },
    export: exportEvidence,
    providerObservations: fixture.observations,
    errorOverlays,
    pageErrors,
    consoleErrors,
    screenshots,
  };
}

/** 验证成功报告只包含真实 UI/运行时证据，允许受原生保存面板限制的明确 partial。 */
export function validateProcessSessionWebView2Report(report) {
  assert.equal(report?.contractVersion, 1);
  assert.ok(["passed", "partial"].includes(report?.status));
  assert.equal(report?.runtimeBoundary, "windows_tauri_webview2_debug_jar");
  assert.match(report?.threadId ?? "", /^thr_[A-Za-z0-9][A-Za-z0-9._-]*$/u);
  const threadEvidence = report?.process?.firstTurnEvidence?.thread;
  const firstTool = threadEvidence?.toolCalls?.[0];
  const processEvidence = report?.process?.firstTurnEvidence?.process;
  const listedSession = processEvidence?.sessions?.[0];
  assert.equal(threadEvidence?.threadId, report.threadId);
  assert.equal(firstTool?.callId, processCallId);
  assert.equal(firstTool?.toolName, "exec_command");
  assert.equal(firstTool?.presentation?.status, "success");
  assert.equal(firstTool?.presentation?.kind, "shell");
  assert.equal(processEvidence?.sessions?.length, 1);
  const processSnapshot = processEvidence?.snapshot;
  assert.equal(listedSession?.startedCallId, firstTool?.callId);
  assert.equal(listedSession?.startedTurnId, firstTool?.turnId);
  assert.match(processEvidence?.workspaceId ?? "", /^ws_[A-Za-z0-9][A-Za-z0-9._-]*$/u);
  assert.equal(processEvidence?.threadId, report.threadId);
  assert.equal(Number.isSafeInteger(processEvidence?.generation), true);
  assert.equal(listedSession?.threadId, report.threadId);
  assert.equal(processEvidence?.workspaceId, listedSession?.workspaceId);
  assert.equal(listedSession?.generation, processEvidence?.generation);
  assert.match(listedSession?.sessionId ?? "", /^proc_[0-9a-f]{32}$/u);
  assert.equal(processSnapshot?.workspaceId, listedSession?.workspaceId);
  assert.equal(processSnapshot?.threadId, report.threadId);
  assert.equal(processSnapshot?.generation, listedSession?.generation);
  assert.equal(processSnapshot?.startedCallId, listedSession?.startedCallId);
  assert.equal(processSnapshot?.sessionId, listedSession?.sessionId);
  assert.equal(listedSession?.state, "running");
  assert.equal(processSnapshot?.state, "running");
  assert.ok(processSnapshot?.output?.includes("JA_PROCESS_SESSION_READY"));
  assert.equal(report?.process?.turnCompletedBeforeInput, true);
  assert.equal(report?.process?.initialOutputVisible, true);
  assert.equal(report?.process?.inputProducedIncrementalOutput, true);
  assert.equal(report?.process?.stopReachedExitState, true);
  assert.ok(Number.isSafeInteger(report?.process?.exitCode));
  assert.equal(report?.process?.focusAfterStop?.label, "命令输出");
  assert.equal(report?.process?.keyboardFocusRecoveredAfterStop, true);
  assert.equal(report?.process?.stdinEof?.emptyInputSubmitted, true);
  assert.equal(report?.process?.stdinEof?.eofObserved, true);
  assert.equal(report?.process?.stdinEof?.exitCode, 0);
  assert.equal(report?.process?.stdinEof?.focusReturnedToOutput, true);
  assert.equal(report?.process?.naturalExit?.outputMarkerVisible, true);
  assert.equal(report?.process?.naturalExit?.exitCode, 23);
  assert.equal(report?.process?.naturalExit?.inputControlsRemoved, true);
  assert.deepEqual(report?.permissions?.labels, ["需要审批", "完全访问"]);
  assert.equal(report?.permissions?.fullAccessStartedProcess, true);
  assert.equal(report?.permissions?.approvalRequiredToolWasApproved, true);
  assert.equal(report?.permissions?.approvedToolProducedExpectedOutput, true);
  assert.equal(report?.permissions?.approvedDecisionFocusReturned, true);
  assert.equal(report?.permissions?.approvalRequiredToolWasDenied, true);
  assert.equal(report?.permissions?.deniedToolStatus, "error");
  assert.equal(report?.permissions?.deniedCommandDidNotRun, true);
  assert.equal(report?.permissions?.deniedDecisionFocusReturned, true);
  assert.equal(report?.export?.status, "verified");
  assert.equal(report.export.successFeedback, true);
  assert.equal(report.export.markdownContainsAllAcceptanceTurns, true);
  assert.equal(report.export.messageSections, 10);
  assert.ok(Number.isSafeInteger(report.export.size) && report.export.size > 0);
  assert.match(report.export.sha256, /^[a-f0-9]{64}$/u);
  assert.ok(["cdp_test_file_handle", "browser_download"].includes(report.export.pickerBoundary));
  assert.equal(report.export.nativePickerSelectionVerified, false);
  assert.equal(report.status, "passed");
  assert.deepEqual(report?.pageErrors, []);
  assert.deepEqual(report?.consoleErrors, []);
  assert.deepEqual(report?.errorOverlays, []);
  return report;
}

/** 启动当前源码构建的隔离真窗；同时记录源码/JAR 身份并在 finally 中关闭 fixture 与 Tauri 进程。 */
async function main() {
  if (process.platform !== "win32")
    throw new Error("process-session WebView2 acceptance requires Windows");
  const options = parseArguments();
  if (!options.javaHome) {
    throw new Error("Java 25 is required; set JAVA_HOME or pass --java-home");
  }
  await mkdir(options.evidenceDirectory, { recursive: true });
  const jarMetadata = await stat(options.jar);
  if (!jarMetadata.isFile() || jarMetadata.size === 0) {
    throw new Error(
      "current App Server JAR is missing or empty; build ja-agent/ja-app-server first",
    );
  }
  const sourceBuildIdentity = await collectSourceBuildIdentity();
  if (jarMetadata.mtimeMs < sourceBuildIdentity.latestSourceMtimeMs) {
    throw new Error("App Server JAR is older than the current Ja source tree; rebuild it first");
  }
  const jarHash = createHash("sha256")
    .update(await readFile(options.jar))
    .digest("hex");
  const fixture = await startProcessSessionProvider();
  try {
    const report = await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      hiddenWindow: true,
      prewarmWebview: true,
      preserveFailedProfile: true,
      /** 本轮一次性 home 移除通用 runner 手写的占位凭据，由上面的 Java lifecycle 负责创建受保护文件。 */
      prepareIsolatedHome: async ({ home }) => {
        await unlink(join(home, "auth.json"));
      },
      driver: async (driverOptions) => {
        let threadId;
        try {
          return await runProcessSessionWebView2({
            ...driverOptions,
            fixture,
          });
        } catch (error) {
          threadId = await driverOptions.page
            .locator('[aria-label="最近对话列表"] button[aria-current="page"][data-thread-id]')
            .getAttribute("data-thread-id")
            .catch(() => undefined);
          await captureFailureArtifacts(
            driverOptions.page,
            options.evidenceDirectory,
            threadId,
            error,
            driverOptions.page.__jaProcessSessionPageErrors ?? [],
            driverOptions.page.__jaProcessSessionConsoleErrors ?? [],
          );
          throw error;
        }
      },
      validateReport: validateProcessSessionWebView2Report,
      reportFileName: "process-session-webview2-report.json",
    });
    const result = {
      ...report,
      appServerJar: {
        name: "ja-app-server.jar",
        size: jarMetadata.size,
        sha256: jarHash,
        sourceBuildIdentitySha256: sourceBuildIdentity.sha256,
        sourceFileCount: sourceBuildIdentity.fileCount,
        sourceLatestModifiedAt: new Date(sourceBuildIdentity.latestSourceMtimeMs).toISOString(),
        artifactModifiedAt: new Date(jarMetadata.mtimeMs).toISOString(),
      },
    };
    await writeFile(
      join(options.evidenceDirectory, "process-session-webview2-summary.json"),
      `${JSON.stringify(result, null, 2)}\n`,
      "utf8",
    );
    process.stdout.write(
      `PROCESS_SESSION_WEBVIEW2_${result.status.toUpperCase()} ${JSON.stringify({ status: result.status, evidenceDirectory: options.evidenceDirectory })}\n`,
    );
  } finally {
    await fixture.close();
  }
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    process.stderr.write(
      `PROCESS_SESSION_WEBVIEW2_FAIL ${String(error?.message ?? error).slice(0, 2_000)}\n`,
    );
    process.exitCode = 1;
  });
}
