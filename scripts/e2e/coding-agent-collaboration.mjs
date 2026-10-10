// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { mkdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  committedTerminalReply,
  createOperationKey,
  createIsolatedDirectories,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
  requireRpcErrorCode,
} from "./real-provider-smoke.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const providerId = "provider_coding_agent_collaboration";
const modelId = "model_coding_agent_collaboration";
const credentialId = "cred_coding_agent_collaboration";
const placeholderSecret = "coding-agent-collaboration-loopback-placeholder";
const requestTimeoutMs = 2_000;
const mainMarker = "JA_CODING_AGENT_COLLABORATION_MAIN";
const childMarker = "JA_CODING_AGENT_COLLABORATION_CHILD";
const mainFinalMarker = "CODING_AGENT_COLLABORATION_MAIN_FINAL_OK";
const childFinalMarker = "CODING_AGENT_COLLABORATION_CHILD_FINAL_OK";
const childFileMarker = "CHILD_WORKSPACE_WRITE_OK";
const mainFileMarker = "MAIN_WORKSPACE_WRITE_OK";
const responseDelayMs = 1_200;
const callIds = Object.freeze({
  warmRead: "call_collaboration_warm_read",
  spawn: "call_collaboration_spawn",
  wait: "call_collaboration_wait",
  list: "call_collaboration_list",
  readChild: "call_collaboration_read_child",
  childWrite: "call_collaboration_child_write",
  mainWrite: "call_collaboration_main_write",
});
const mainExpectedCalls = Object.freeze([
  [callIds.warmRead, "read"],
  [callIds.spawn, "spawn_agent"],
  [callIds.wait, "wait_agent"],
  [callIds.list, "list_agents"],
  [callIds.readChild, "read"],
  [callIds.mainWrite, "write"],
]);
const childExpectedCalls = Object.freeze([[callIds.childWrite, "write"]]);

/** 生成完整 Responses function-call 生命周期，保证验收走生产 Provider 解码器。 */
function toolStream(responseId, calls) {
  let sequenceNumber = 1;
  const frames = [
    event("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
  ];
  const push = (type, payload) => frames.push(event(type, sequenceNumber++, payload));
  const items = calls.map((call, index) => {
    const item = {
      id: `item_${call.callId}`,
      type: "function_call",
      call_id: call.callId,
      name: call.name,
      arguments: JSON.stringify(call.arguments),
    };
    push("response.output_item.added", { output_index: index, item: { ...item, arguments: "" } });
    push("response.function_call_arguments.delta", {
      item_id: item.id,
      delta: item.arguments,
      output_index: index,
    });
    push("response.function_call_arguments.done", {
      item_id: item.id,
      name: item.name,
      arguments: item.arguments,
      output_index: index,
    });
    push("response.output_item.done", { output_index: index, item });
    return item;
  });
  push("response.completed", { response: responseEnvelope(responseId, "completed", items) });
  return frames.join("");
}

/** 生成已提交的最终答复；测试标记用于区分主任务与子任务的持久历史。 */
function finalStream(responseId, text) {
  const message = {
    id: `message_${responseId}`,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
    event("response.output_text.delta", 1, {
      content_index: 0,
      delta: text,
      item_id: message.id,
      output_index: 0,
      logprobs: [],
    }),
    event("response.output_text.done", 2, {
      content_index: 0,
      item_id: message.id,
      output_index: 0,
      text,
    }),
    event("response.completed", 3, {
      response: responseEnvelope(responseId, "completed", [message]),
    }),
  ].join("");
}

/** 编码一帧响应事件，保持逐帧递增序号与生产 SSE 消费方的契约一致。 */
function event(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequenceNumber, ...payload })}\n\n`;
}

/** 构造 OpenAI Responses adapter 接受的最小完整响应对象。 */
function responseEnvelope(responseId, status, output, includeUsage = true) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "coding-agent-collaboration-fixture",
    object: "response",
    output,
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 32,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens: 16,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 48,
    };
  }
  return response;
}

/** 将有界请求体解码为 JSON，避免本地测试服务无上限保留模型上下文。 */
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

/** 仅投影 Responses 请求中的函数调用结果，按稳定 call_id 推进本地模型脚本。 */
function outputsByCall(payload) {
  const outputs = Array.isArray(payload?.input)
    ? payload.input.filter(
        (item) => item?.type === "function_call_output" && typeof item.call_id === "string",
      )
    : [];
  return new Map(outputs.map((item) => [item.call_id, item.output]));
}

/** 按生产完整 Tool 投影的审计尾注验明正文，再用 JSON parser 读取回执；截断或摘要不符不算通过。 */
export function decodeTaskReceipt(value) {
  assert.equal(typeof value, "string", "Task Tool output must be text");
  const footer = value.match(/\n\[characters=(\d+) sha256=([a-f0-9]{64})\]$/u);
  assert.ok(footer, "Task Tool output must carry its complete audit footer");
  const content = value.slice(0, footer.index);
  assert.equal(Array.from(content).length, Number(footer[1]), "Task receipt length must match");
  assert.equal(
    createHash("sha256").update(content, "utf8").digest("hex"),
    footer[2],
    "Task receipt digest must match",
  );
  return JSON.parse(content);
}

/** 只查看最新用户消息，避免父线程历史中的 child brief 污染后续路由。 */
function latestUserHasMarker(payload, marker) {
  if (typeof payload?.input === "string") return payload.input.includes(marker);
  const entries = Array.isArray(payload?.input) ? payload.input : [];
  for (let index = entries.length - 1; index >= 0; index -= 1) {
    const entry = entries[index];
    if (entry?.role !== "user") continue;
    const content = entry.content;
    if (typeof content === "string") return content.includes(marker);
    return (
      Array.isArray(content) &&
      content.some((part) => typeof part?.text === "string" && part.text.includes(marker))
    );
  }
  return false;
}

/** 从 spawn_agent 的文本或结构化结果读取服务端 Thread identity，不合成测试身份。 */
function findThreadId(value) {
  if (typeof value === "string") {
    const match = value.match(/\bthr_[A-Za-z0-9_-]{8,128}\b/u);
    if (match !== null) return match[0];
    try {
      return findThreadId(JSON.parse(value));
    } catch {
      return null;
    }
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const threadId = findThreadId(item);
      if (threadId !== null) return threadId;
    }
    return null;
  }
  if (value !== null && typeof value === "object") {
    if (typeof value.threadId === "string" && /^thr_[A-Za-z0-9_-]{8,128}$/u.test(value.threadId)) {
      return value.threadId;
    }
    for (const item of Object.values(value)) {
      const threadId = findThreadId(item);
      if (threadId !== null) return threadId;
    }
  }
  return null;
}

/** 从结构化或 JSON 编码的 Tool 回执中定位目标 Agent 投影。 */
function findAgentRecord(value, threadId) {
  if (typeof value === "string") {
    try {
      return findAgentRecord(decodeTaskReceipt(value), threadId);
    } catch {
      return null;
    }
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findAgentRecord(item, threadId);
      if (found !== null) return found;
    }
    return null;
  }
  if (value !== null && typeof value === "object") {
    if (value.threadId === threadId) return value;
    for (const item of Object.values(value)) {
      const found = findAgentRecord(item, threadId);
      if (found !== null) return found;
    }
  }
  return null;
}

/** 根据最新用户 marker 推进父子状态机；两次短 Provider 延时累计跨过旧请求窗口。 */
async function collaborationResponse(payload, observations) {
  const serialized = JSON.stringify(payload?.input ?? []);
  if (serialized.includes("<user_request>") && serialized.includes("<assistant_reply>")) {
    return finalStream("resp_collaboration_title", "Coding Agent collaboration acceptance");
  }
  const outputs = outputsByCall(payload);
  const isMain = latestUserHasMarker(payload, mainMarker);
  const isChild = latestUserHasMarker(payload, childMarker);
  if (!isMain && !isChild)
    throw new Error("Provider request did not carry a collaboration identity marker");

  if (isChild) {
    if (!outputs.has(callIds.childWrite)) {
      observations.push({ role: "child", stage: "write" });
      return toolStream("resp_collaboration_child_write", [
        {
          callId: callIds.childWrite,
          name: "write",
          arguments: { path: "src/collaboration-child.txt", content: `${childFileMarker}\n` },
        },
      ]);
    }
    observations.push({ role: "child", stage: "final" });
    return finalStream("resp_collaboration_child_final", childFinalMarker);
  }

  if (!outputs.has(callIds.warmRead)) {
    observations.push({ role: "main", stage: "warm_read", atMs: Date.now() });
    await new Promise((resolveDelay) => setTimeout(resolveDelay, responseDelayMs));
    return toolStream("resp_collaboration_warm_read", [
      {
        callId: callIds.warmRead,
        name: "read",
        arguments: { path: "README.md" },
      },
    ]);
  }
  if (!outputs.has(callIds.spawn)) {
    await new Promise((resolveDelay) => setTimeout(resolveDelay, responseDelayMs));
    observations.push({ role: "main", stage: "spawn", atMs: Date.now() });
    return toolStream("resp_collaboration_spawn", [
      {
        callId: callIds.spawn,
        name: "spawn_agent",
        arguments: {
          taskName: "collaboration-writer",
          brief:
            `Write ${childFileMarker} to src/collaboration-child.txt using the workspace write tool, ` +
            `then finish with ${childFinalMarker}. ${childMarker}`,
          accessMode: "full_access",
        },
      },
    ]);
  }
  if (!outputs.has(callIds.wait)) {
    const spawnOutput = outputs.get(callIds.spawn);
    const childThreadId = findThreadId(spawnOutput);
    if (childThreadId === null) {
      observations.push({
        role: "main",
        stage: "spawn_failed",
        spawnOutput: JSON.stringify(spawnOutput)
          .replaceAll(placeholderSecret, "[REDACTED]")
          .slice(0, 2_000),
      });
      return finalStream(
        "resp_collaboration_spawn_failed",
        "CODING_AGENT_COLLABORATION_SPAWN_FAILED",
      );
    }
    observations.push({ role: "main", stage: "wait", childThreadId });
    return toolStream("resp_collaboration_wait", [
      {
        callId: callIds.wait,
        name: "wait_agent",
        arguments: { taskThreadIds: [childThreadId], timeoutMs: 30_000 },
      },
    ]);
  }
  const childThreadId = findThreadId(outputs.get(callIds.spawn));
  if (childThreadId === null) {
    observations.push({
      role: "main",
      stage: "spawn_failed",
      spawnOutput: JSON.stringify(outputs.get(callIds.spawn))
        .replaceAll(placeholderSecret, "[REDACTED]")
        .slice(0, 2_000),
    });
    return finalStream(
      "resp_collaboration_spawn_failed",
      "CODING_AGENT_COLLABORATION_SPAWN_FAILED",
    );
  }
  if (!observations.some((item) => item.role === "main" && item.stage === "wait_result")) {
    const waitData = outputs.get(callIds.wait);
    const waitReceipt = decodeTaskReceipt(waitData);
    const child = findAgentRecord(waitReceipt, childThreadId);
    observations.push({
      role: "main",
      stage: "wait_result",
      waitTimedOut: waitReceipt?.timedOut ?? null,
      childStatus: child?.status ?? null,
    });
  }
  if (!outputs.has(callIds.list)) {
    observations.push({ role: "main", stage: "list" });
    return toolStream("resp_collaboration_list", [
      { callId: callIds.list, name: "list_agents", arguments: {} },
    ]);
  }
  if (!observations.some((item) => item.role === "main" && item.stage === "list_result")) {
    const child = findAgentRecord(outputs.get(callIds.list), childThreadId);
    observations.push({ role: "main", stage: "list_result", childStatus: child?.status ?? null });
  }
  if (!outputs.has(callIds.readChild)) {
    observations.push({ role: "main", stage: "read_child" });
    return toolStream("resp_collaboration_read_child", [
      {
        callId: callIds.readChild,
        name: "read",
        arguments: { path: "src/collaboration-child.txt" },
      },
    ]);
  }
  if (!outputs.has(callIds.mainWrite)) {
    observations.push({ role: "main", stage: "main_write" });
    return toolStream("resp_collaboration_main_write", [
      {
        callId: callIds.mainWrite,
        name: "write",
        arguments: { path: "src/collaboration-main.txt", content: `${mainFileMarker}\n` },
      },
    ]);
  }
  observations.push({ role: "main", stage: "final" });
  return finalStream("resp_collaboration_main_final", mainFinalMarker);
}

/** 启动只监听回环地址的 Responses fixture；未知请求被记录为验收失败并拒绝。 */
async function startCollaborationProvider() {
  const observations = [];
  let failure = null;
  const server = createServer(async (request, response) => {
    response.setHeader("cache-control", "no-store");
    response.setHeader("connection", "close");
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    try {
      const payload = await readBoundedJson(request);
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      response.end(await collaborationResponse(payload, observations));
    } catch (error) {
      failure ??= String(error?.message ?? error).slice(0, 240);
      if (!response.headersSent) {
        response.writeHead(500, { "content-type": "application/json" });
        response.end('{"error":"coding_agent_collaboration_fixture_failed"}');
      } else {
        response.destroy();
      }
    }
  });
  await new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    observations,
    failure: () => failure,
    close: () =>
      new Promise((resolveClose, rejectClose) =>
        server.close((error) => (error ? rejectClose(error) : resolveClose())),
      ),
  };
}

/** 解析仅包含本地 JAR、Java 与外部证据目录的验收参数。 */
export function parseArguments(argv = process.argv.slice(2)) {
  const parsed = {
    jar: process.env.JA_CODING_AGENT_JAR,
    java: process.env.JA_TEST_JAVA,
    evidenceDirectory: process.env.JA_CODING_AGENT_EVIDENCE_DIR,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (!["--jar", "--java", "--evidence-directory"].includes(argument)) {
      throw new Error(`unknown coding-agent collaboration argument: ${argument}`);
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`${argument} requires a value`);
    index += 1;
    if (argument === "--jar") parsed.jar = value;
    if (argument === "--java") parsed.java = value;
    if (argument === "--evidence-directory") parsed.evidenceDirectory = value;
  }
  return parsed;
}

/** 解析显式或仓库默认的 App Server JAR，并拒绝空文件。 */
async function resolveJar(explicitJar) {
  const jar = resolve(
    explicitJar ?? join(repoRoot, "ja-agent", "ja-app-server", "target", "ja-app-server.jar"),
  );
  const metadata = await stat(jar);
  if (!metadata.isFile() || metadata.size === 0)
    throw new Error("coding-agent collaboration JAR is unavailable");
  return jar;
}

/** 优先使用调用方指定 Java、JA_TEST_JAVA 或 JAVA_HOME，避免 PATH 隐式降级。 */
function resolveJava(explicitJava) {
  if (explicitJava) return explicitJava;
  if (process.env.JAVA_HOME) {
    return join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java");
  }
  return process.platform === "win32" ? "java.exe" : "java";
}

/** 限定真实 JAR 验收使用 JDK 25，并避免将供应商 banner 留入报告。 */
async function assertJava25(command) {
  let output = "";
  try {
    const result = await execFileAsync(command, ["-version"], {
      windowsHide: true,
      maxBuffer: 64 * 1024,
    });
    output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  } catch (error) {
    output = `${error?.stdout ?? ""}\n${error?.stderr ?? ""}`;
    if (output.trim() === "") throw new Error("selected Java executable could not be started");
  }
  const match = output.match(/\bversion\s+"?(\d+)/iu) ?? output.match(/\bopenjdk\s+(\d+)/iu);
  if (match === null || Number(match[1]) !== 25)
    throw new Error("coding-agent collaboration acceptance requires Java 25");
  return Number(match[1]);
}

/** 将协议错误压缩为稳定错误码，避免把任意服务端消息复制到验收报告。 */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = requireRpcErrorCode(frame.error);
    throw new Error(`${operation} failed: ${code}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 初始化真实 sidecar，并等待 Ja Kernel 报告 ready 后才开放验收请求。 */
async function startReadySession({ command, jar, directories }) {
  const session = new JsonlSession({
    command,
    prefixArgs: ["-jar", jar],
    directories,
    apiKey: placeholderSecret,
    endpoint: "http://127.0.0.1",
  });
  try {
    const initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams()),
      "runtime/initialize",
    );
    if (
      initialized?.runtime?.engine !== "ja-kernel" ||
      typeof initialized?.runtime?.engineVersion !== "string"
    ) {
      throw new Error("collaboration acceptance did not start the Ja Kernel runtime");
    }
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    return session;
  } catch (error) {
    await session.forceClose();
    throw error;
  }
}

/** 以唯一 operation identity 接纳主 Turn，并读取同一 turnId 的权威终态答复。 */
async function startAcceptanceTurn(session, threadId, content) {
  const accepted = rpcResult(
    await session.request("turn/start", {
      threadId,
      operationKey: createOperationKey(),
      content: [{ type: "text", text: content }],
    }),
    "turn/start collaboration acceptance",
  );
  assert.ok(typeof accepted.turnId === "string" && accepted.turnId.startsWith("turn_"));
  const terminal = await session.waitForEvent(
    "turn/terminal",
    (frame) => frame.params?.turnId === accepted.turnId,
    120_000,
  );
  return {
    turnId: accepted.turnId,
    terminal,
    finalText: committedTerminalReply(terminal, accepted.turnId),
  };
}

/** 各 Thread 拥有自己的工具历史；核对精确次数、次序、成功状态与同一 Turn 的最终答复。 */
export function assertThreadHistory(history, threadId, expectedCalls, finalMarker) {
  assert.equal(history?.threadId, threadId);
  assert.ok(Number.isSafeInteger(history?.revision));
  assert.ok(Array.isArray(history?.items));
  const final = history.items.find(
    (item) =>
      item.kind === "final_answer" &&
      typeof item.text === "string" &&
      item.text.includes(finalMarker),
  );
  assert.ok(final, `thread/read must persist ${finalMarker}`);
  const calls = history.items.filter(
    (item) => item.kind === "tool_call" && item.turnId === final.turnId,
  );
  assert.deepEqual(
    calls.map((item) => [item.callId, item.toolName]),
    expectedCalls,
  );
  for (const call of calls) {
    assert.equal(call.presentation?.status, "success", `${call.toolName} must persist success`);
  }
  return {
    turnId: final.turnId,
    revision: history.revision,
    itemCount: history.items.length,
    toolCallCount: calls.length,
  };
}

/** 校验每个预期工具恰好提交一次且服务端结果成功。 */
function assertToolOutcomes(events, expectedCalls) {
  const expected = new Map(expectedCalls);
  const calls = events
    .filter((frame) => frame?.method === "assistant/model-step-committed")
    .flatMap((frame) => (Array.isArray(frame.params?.toolCalls) ? frame.params.toolCalls : []));
  const results = events
    .filter((frame) => frame?.method === "tool/batch-committed")
    .flatMap((frame) => (Array.isArray(frame.params?.results) ? frame.params.results : []));
  for (const [callId, toolName] of expected) {
    assert.equal(
      calls.filter((call) => call.callId === callId && call.toolName === toolName).length,
      1,
    );
    const matchingResults = results.filter((result) => result.callId === callId);
    assert.equal(matchingResults.length, 1, `${toolName} must have one committed result`);
    assert.equal(matchingResults[0].outcome, "succeeded", `${toolName} must succeed`);
  }
  return { callCount: calls.length, resultCount: results.length };
}

/** 只清理由当前 createIsolatedDirectories 创建且仍位于物理临时目录的 profile。 */
async function cleanupOwnedProfile(root) {
  const physicalTemp = await realpath(tmpdir());
  const target = resolve(root);
  if (dirname(target) !== physicalTemp || !basename(target).startsWith("ja-real-provider-")) {
    throw new Error("refusing to clean a profile not owned by this acceptance runner");
  }
  await rm(target, { recursive: true, force: false });
}

/** 将去敏后的有限结果写入调用方指定的仓库外目录。 */
async function persistEvidence(directory, report) {
  if (!directory) return null;
  const target = resolve(directory);
  if (
    target === repoRoot ||
    target.startsWith(`${repoRoot}/`) ||
    target.startsWith(`${repoRoot}\\`)
  ) {
    throw new Error("coding-agent evidence must not be written inside the source repository");
  }
  await mkdir(target, { recursive: true });
  const file = join(
    target,
    `coding-agent-collaboration-${new Date().toISOString().replaceAll(/[:.]/gu, "-")}.json`,
  );
  await writeFile(file, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  return file;
}

/** 真实 JVM JAR、隔离 SQLite、回环 Provider 与两条 Thread 组成一次端到端协作验收。 */
export async function runAcceptance(options = {}) {
  const parsed = { ...parseArguments(options.argv), ...options };
  const jar = await resolveJar(parsed.jar);
  const java = resolveJava(parsed.java);
  const javaMajor = await assertJava25(java);
  const directories = await createIsolatedDirectories();
  const startedAt = Date.now();
  const report = {
    status: "running",
    jar: basename(jar),
    javaMajor,
    providerRequestTimeoutMs: requestTimeoutMs,
    phases: [],
  };
  let fixture;
  let session;
  try {
    report.phases.push("prepare_workspace");
    await mkdir(join(directories.workspace, "src"));
    await writeFile(
      join(directories.workspace, "README.md"),
      "Coding Agent collaboration acceptance.\n",
      "utf8",
    );
    fixture = await startCollaborationProvider();

    report.phases.push("start_app_server");
    session = await startReadySession({ command: java, jar, directories });
    const configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read",
    );
    const document = providerConfigurationDocument({
      endpoint: fixture.baseUrl,
      name: "Coding Agent collaboration loopback fixture",
      api: "openai_responses",
      model: "coding-agent-collaboration-fixture",
      selectedProviderId: providerId,
      selectedModelId: modelId,
      selectedCredentialId: credentialId,
    });
    document.providers[0].network_timeouts.request_timeout_ms = requestTimeoutMs;
    const configured = rpcResult(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        document,
      }),
      "configuration/replace",
    );
    assert.equal(configured.accepted, true);
    const credential = rpcResult(
      await session.request("credential/set", {
        credentialId,
        secret: placeholderSecret,
        expectedVersion: configuration.cas.credentialVersion,
      }),
      "credential/set",
    );
    assert.equal(credential.configured, true);

    report.phases.push("create_workspace_and_main_thread");
    const opened = rpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Coding Agent collaboration acceptance",
      }),
      "workspace/open",
    );
    assert.ok(typeof opened.workspaceId === "string" && opened.workspaceId.startsWith("ws_"));
    const thread = rpcResult(
      await session.request("thread/create", {
        cwd: directories.workspace,
        title: "Coding Agent collaboration acceptance",
        providerId,
        modelId,
        reasoningLevel: "medium",
        accessMode: "full_access",
        collaborationMode: "default",
      }),
      "thread/create",
    );
    assert.ok(typeof thread.threadId === "string" && thread.threadId.startsWith("thr_"));

    report.phases.push("run_collaboration_and_child_write");
    const mainTurn = await startAcceptanceTurn(
      session,
      thread.threadId,
      `Read the workspace, delegate a workspace write, wait, list, and read it. ${mainMarker}`,
    );
    assert.equal(mainTurn.terminal.params?.state, "completed");
    assert.match(mainTurn.finalText, new RegExp(mainFinalMarker, "u"));
    const childThreadId = fixture.observations.find((item) => item.stage === "wait")?.childThreadId;
    assert.ok(typeof childThreadId === "string" && childThreadId.startsWith("thr_"));
    report.mainThreadId = thread.threadId;
    report.childThreadId = childThreadId;
    report.mainTurnId = mainTurn.turnId;
    report.elapsedMs = Date.now() - startedAt;
    const delayAt = fixture.observations.find(
      (item) => item.role === "main" && item.stage === "warm_read",
    )?.atMs;
    const spawnAt = fixture.observations.find(
      (item) => item.role === "main" && item.stage === "spawn",
    )?.atMs;
    assert.ok(Number.isSafeInteger(delayAt) && Number.isSafeInteger(spawnAt));
    report.mainElapsedToSpawnMs = spawnAt - delayAt;
    report.mainElapsedToSpawnExceededProviderTimeout =
      report.mainElapsedToSpawnMs > requestTimeoutMs;
    assert.equal(
      report.mainElapsedToSpawnExceededProviderTimeout,
      true,
      "main task must resume after its previous 2 second Provider request window",
    );
    const waitResult = fixture.observations.find(
      (item) => item.role === "main" && item.stage === "wait_result",
    );
    const listResult = fixture.observations.find(
      (item) => item.role === "main" && item.stage === "list_result",
    );
    assert.ok(waitResult, "wait_agent result must be observed");
    assert.equal(waitResult.waitTimedOut, false, "wait_agent must complete rather than time out");
    assert.equal(waitResult.childStatus, "completed", "wait_agent must return the delegated child");
    assert.equal(
      listResult?.childStatus,
      "completed",
      "list_agents must return the completed delegated child",
    );

    report.phases.push("verify_writes_and_tool_results");
    const mainPostimage = await readFile(
      join(directories.workspace, "src", "collaboration-main.txt"),
      "utf8",
    );
    const childPostimage = await readFile(
      join(directories.workspace, "src", "collaboration-child.txt"),
      "utf8",
    );
    assert.equal(mainPostimage, `${mainFileMarker}\n`);
    assert.equal(childPostimage, `${childFileMarker}\n`);
    report.toolOutcomes = { main: assertToolOutcomes(session.events, mainExpectedCalls) };
    report.fixture = {
      observations: fixture.observations,
      failure: fixture.failure(),
    };
    assert.equal(fixture.failure(), null, "Provider fixture must not receive unexpected requests");

    report.phases.push("restart_and_read_both_histories");
    const preRestartMain = rpcResult(
      await session.request("thread/read", { threadId: thread.threadId }),
      "thread/read main before restart",
    );
    const preRestartChild = rpcResult(
      await session.request("thread/read", { threadId: childThreadId }),
      "thread/read child before restart",
    );
    const mainHistoryBeforeRestart = assertThreadHistory(
      preRestartMain,
      thread.threadId,
      mainExpectedCalls,
      mainFinalMarker,
    );
    const childHistoryBeforeRestart = assertThreadHistory(
      preRestartChild,
      childThreadId,
      childExpectedCalls,
      childFinalMarker,
    );
    report.toolOutcomes.child = {
      callCount: childHistoryBeforeRestart.toolCallCount,
      source: "child thread/read",
    };
    const exit = await session.shutdown();
    session = undefined;
    assert.equal(exit.code, 0);
    session = await startReadySession({ command: java, jar, directories });
    const [restoredMain, restoredChild] = await Promise.all([
      session
        .request("thread/read", { threadId: thread.threadId })
        .then((frame) => rpcResult(frame, "thread/read main after restart")),
      session
        .request("thread/read", { threadId: childThreadId })
        .then((frame) => rpcResult(frame, "thread/read child after restart")),
    ]);
    report.historyAfterRestart = {
      main: assertThreadHistory(restoredMain, thread.threadId, mainExpectedCalls, mainFinalMarker),
      child: assertThreadHistory(
        restoredChild,
        childThreadId,
        childExpectedCalls,
        childFinalMarker,
      ),
      mainRevisionDidNotRegress: restoredMain.revision >= mainHistoryBeforeRestart.revision,
      childRevisionDidNotRegress: restoredChild.revision >= childHistoryBeforeRestart.revision,
    };
    assert.equal(report.historyAfterRestart.mainRevisionDidNotRegress, true);
    assert.equal(report.historyAfterRestart.childRevisionDidNotRegress, true);
    report.status = "completed";
    report.evidenceScope =
      "JDK 25 JVM JAR, JA-RPC, loopback Responses, parent/child workspace writes, restart history";
    return report;
  } catch (error) {
    report.status = "failed";
    report.failure = String(error?.message ?? error).slice(0, 500);
    report.fixture = {
      observations: fixture?.observations ?? [],
      failure: fixture?.failure() ?? null,
    };
    report.stderrOrigin = (session?.stderr ?? "")
      .split(/\r?\n/u)
      .filter((line) => line.includes("Task operation rejected code=") && line.includes(" origin="))
      .slice(-10);
    report.rpcEvents = (session?.events ?? []).slice(-80).map((frame) => ({
      method: frame.method,
      turnId: frame.params?.turnId ?? null,
      threadId: frame.params?.threadId ?? null,
      state: frame.params?.state ?? null,
      toolNames: Array.isArray(frame.params?.toolCalls)
        ? frame.params.toolCalls.map((call) => call.toolName)
        : undefined,
      resultOutcomes: Array.isArray(frame.params?.results)
        ? frame.params.results.map((result) => ({
            outcome: result.outcome,
            errorCode: result.errorCode ?? null,
          }))
        : undefined,
    }));
    throw error;
  } finally {
    if (session) await session.shutdown().catch(() => session.forceClose());
    await fixture?.close().catch(() => undefined);
    await cleanupOwnedProfile(directories.root);
    report.evidenceFile = await persistEvidence(parsed.evidenceDirectory, report);
  }
}

/** 输出一行主结果，详细脱敏事实只写到用户明确指定的仓库外证据目录。 */
async function main() {
  try {
    const report = await runAcceptance();
    process.stdout.write(
      `CODING_AGENT_COLLABORATION_ACCEPTANCE_OK elapsedMs=${report.elapsedMs} ` +
        `mainTools=${mainExpectedCalls.length} childTools=${childExpectedCalls.length} restart=verified\n`,
    );
    if (report.evidenceFile) process.stdout.write(`evidence=${report.evidenceFile}\n`);
  } catch (error) {
    process.stderr.write(
      `CODING_AGENT_COLLABORATION_ACCEPTANCE_FAIL reason=${String(error?.message ?? error).slice(0, 300)}\n`,
    );
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await main();
}
