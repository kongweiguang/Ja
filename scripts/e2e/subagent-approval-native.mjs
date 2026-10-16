// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createReadStream } from "node:fs";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import {
  mkdir,
  mkdtemp,
  readFile,
  realpath,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  createIsolatedDirectories,
  createOperationKey,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
  requireRpcErrorCode,
} from "./real-provider-smoke.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const providerId = "provider_subagent_approval_native";
const modelId = "model_subagent_approval_native";
const credentialId = "cred_subagent_approval_native";
const placeholderSecret = "subagent-approval-native-loopback-placeholder";
const parentMarker = "JA_NATIVE_SUBAGENT_APPROVAL_PARENT";
const childMarker = "JA_NATIVE_SUBAGENT_APPROVAL_CHILD";
const finalMarker = "JA_NATIVE_SUBAGENT_APPROVAL_FINAL_OK";
const parentSpawnCallId = "call_native_approval_spawn";
const expiredCallId = "call_native_approval_expired_attempt";
const resumedCallId = "call_native_approval_resumed_attempt";
const approvalValidityMs = 5 * 60 * 1_000;
const eventWaitSliceMs = 60_000;

/**
 * 只接收显式 Native、证据路径和 expiry 场景；默认 hot-expiry 保留已有验收语义。
 * @author kongweiguang
 */
export function parseArguments(argv = process.argv.slice(2)) {
  const names = new Map([
    ["--native-executable", "nativeExecutable"],
    ["--evidence-directory", "evidenceDirectory"],
    ["--expected-native-sha256", "expectedNativeSha256"],
    ["--scenario", "scenario"],
  ]);
  const scenarios = new Set(["hot-expiry", "cold-expiry", "cold-expired"]);
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = names.get(argv[index]);
    if (key === undefined) throw new Error(`unknown argument: ${argv[index]}`);
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--") || value.trim() === "") {
      throw new Error(`${argv[index]} requires a value`);
    }
    if (options[key] !== undefined) throw new Error(`${argv[index]} can only be specified once`);
    if (key === "scenario") {
      if (!scenarios.has(value)) throw new Error("--scenario must be hot-expiry, cold-expiry, or cold-expired");
      options[key] = value;
    } else {
      options[key] = key === "expectedNativeSha256" ? value.toLowerCase() : resolve(value);
    }
    index += 1;
  }
  for (const [flag, key] of names) {
    if (["expectedNativeSha256", "scenario"].includes(key)) continue;
    if (options[key] === undefined) throw new Error(`missing ${flag}`);
  }
  if (options.expectedNativeSha256 !== undefined && !/^[a-f0-9]{64}$/u.test(options.expectedNativeSha256)) {
    throw new Error("--expected-native-sha256 must be a 64-character SHA-256");
  }
  options.scenario ??= "hot-expiry";
  return options;
}

/** 将生产 Responses adapter 接受的 Tool-call 完整生命周期写成 SSE 帧。 */
function toolStream(responseId, calls) {
  let sequenceNumber = 1;
  const frames = [event("response.created", 0, { response: responseEnvelope(responseId, "in_progress", [], false) })];
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

/** 将已提交的模型最终答复编码为完整 Responses SSE 流。 */
function finalStream(responseId, text) {
  const message = {
    id: `message_${responseId}`,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, { response: responseEnvelope(responseId, "in_progress", [], false) }),
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
    event("response.completed", 3, { response: responseEnvelope(responseId, "completed", [message]) }),
  ].join("");
}

/** 保持逐帧递增序号和 Provider 实际消费的 SSE data envelope。 */
function event(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequenceNumber, ...payload })}\n\n`;
}

/** 构造符合 OpenAI Responses wire contract 的最小模型响应。 */
function responseEnvelope(responseId, status, output, includeUsage = true) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "subagent-approval-native-fixture",
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

/** 限制 loopback Provider 请求体大小，避免 Fixture 意外积累完整对话。 */
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

/** 对冻结 Native 产物流式计算 SHA，避免把大型 PE 文件整体读入内存。 */
async function sha256File(path) {
  const digest = createHash("sha256");
  for await (const chunk of createReadStream(path)) digest.update(chunk);
  return digest.digest("hex");
}

/** 仅读取模型请求中最新 user 消息，区分 child 首次 Turn 与原 Turn 恢复。 */
function latestUserText(payload) {
  if (typeof payload?.input === "string") return payload.input;
  const input = Array.isArray(payload?.input) ? payload.input : [];
  for (let index = input.length - 1; index >= 0; index -= 1) {
    const entry = input[index];
    if (entry?.role !== "user") continue;
    if (typeof entry.content === "string") return entry.content;
    if (Array.isArray(entry.content)) {
      return entry.content
        .map((part) => (typeof part?.text === "string" ? part.text : ""))
        .join("\n");
    }
    return "";
  }
  return "";
}

/** 提取真实 child identity；不从调用顺序或随机 fixture 值合成 Thread ID。 */
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
    for (const entry of value) {
      const found = findThreadId(entry);
      if (found !== null) return found;
    }
    return null;
  }
  if (value !== null && typeof value === "object") {
    if (typeof value.threadId === "string" && /^thr_[A-Za-z0-9_-]{8,128}$/u.test(value.threadId)) {
      return value.threadId;
    }
    for (const entry of Object.values(value)) {
      const found = findThreadId(entry);
      if (found !== null) return found;
    }
  }
  return null;
}

/** 按真实 Provider 输入中的 function_call_output 推进父子模型脚本。 */
function outputsByCall(payload) {
  const outputs = Array.isArray(payload?.input)
    ? payload.input.filter(
        (item) => item?.type === "function_call_output" && typeof item.call_id === "string",
      )
    : [];
  return new Map(outputs.map((item) => [item.call_id, item.output]));
}

/** 提供父模型委派与 child 两次审批 Tool 请求，所有请求只到本进程 loopback。 */
async function startApprovalProvider(workspace) {
  const observations = [];
  let failure = null;
  let parentSpawnIssued = false;
  let parentSpawnResultObserved = false;
  const expiredTarget = join(workspace, "approval-expired-attempt.txt");
  const approvedTarget = join(workspace, "approval-approved-once.txt");
  const quote = (value) => `'${value.replaceAll("'", "''")}'`;
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
      const userText = latestUserText(payload);
      const outputs = outputsByCall(payload);
      let body;
      const serializedInput = JSON.stringify(payload?.input ?? []);
      if (serializedInput.includes("<user_request>") && serializedInput.includes("<assistant_reply>")) {
        body = finalStream("resp_native_approval_title", "Native approval expiry acceptance");
      } else if (userText.includes(parentMarker)) {
        if (!parentSpawnIssued) {
          parentSpawnIssued = true;
          observations.push({ role: "parent", stage: "spawn_requested" });
          body = toolStream("resp_native_approval_spawn", [
            {
              callId: parentSpawnCallId,
              name: "spawn_agent",
              arguments: {
                taskName: "native-approval-expiry",
                brief:
                  `Request exec_command once and pause for approval. ${childMarker} ` +
                  "Do not try to approve or resume yourself.",
                accessMode: "approval_required",
              },
            },
          ]);
        } else if (outputs.has(parentSpawnCallId) && !parentSpawnResultObserved) {
          const childThreadId = findThreadId(outputs.get(parentSpawnCallId));
          if (childThreadId === null) throw new Error("spawn result had no child Thread identity");
          parentSpawnResultObserved = true;
          observations.push({ role: "parent", stage: "spawn_committed", childThreadId });
          body = finalStream("resp_native_approval_parent_final", "NATIVE_SUBAGENT_APPROVAL_PARENT_STARTED");
        } else {
          observations.push({ role: "parent", stage: "followup_without_new_spawn" });
          body = finalStream("resp_native_approval_parent_final", "NATIVE_SUBAGENT_APPROVAL_PARENT_STARTED");
        }
      } else if (userText.includes(childMarker)) {
        if (outputs.has(resumedCallId)) {
          observations.push({ role: "child", stage: "approved_tool_result_observed" });
          body = finalStream("resp_native_approval_child_final", finalMarker);
        } else if (outputs.has(expiredCallId)) {
          observations.push({ role: "child", stage: "expired_tool_result_resumed" });
          body = toolStream("resp_native_approval_second_request", [
            {
              callId: resumedCallId,
              name: "exec_command",
              arguments: {
                cmd: `Add-Content -LiteralPath ${quote(approvedTarget)} -Value 'NATIVE_APPROVED_TOOL_EXECUTED'`,
                workdir: workspace,
              },
            },
          ]);
        } else {
          observations.push({ role: "child", stage: "first_approval_requested" });
          body = toolStream("resp_native_approval_first_request", [
            {
              callId: expiredCallId,
              name: "exec_command",
              arguments: {
                cmd: `Add-Content -LiteralPath ${quote(expiredTarget)} -Value 'NATIVE_EXPIRED_TOOL_MUST_NOT_RUN'`,
                workdir: workspace,
              },
            },
          ]);
        }
      } else {
        throw new Error("Provider request did not match the parent or child scenario marker");
      }
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      response.end(body);
    } catch (error) {
      failure ??= String(error?.message ?? error).slice(0, 240);
      if (!response.headersSent) {
        response.writeHead(500, { "content-type": "application/json" });
        response.end('{"error":"native_approval_fixture_failed"}');
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
    expiredTarget,
    approvedTarget,
    failure: () => failure,
    close: () =>
      new Promise((resolveClose, rejectClose) =>
        server.close((error) => (error ? rejectClose(error) : resolveClose())),
      ),
  };
}

/** 把 JA-RPC 失败缩减到稳定错误码，不将数据库、路径或调用内容写入证据。 */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) throw new Error(`${operation} failed: ${requireRpcErrorCode(frame.error)}`);
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/**
 * 启动同一隔离 profile 的指定 Native 代际；ready generation 必须与启动参数一致，避免重启证据串代。
 * @author kongweiguang
 */
async function startReadySession(nativeExecutable, directories, endpoint, runtimeGeneration = 1) {
  const session = new JsonlSession({
    command: nativeExecutable,
    prefixArgs: [],
    directories,
    apiKey: placeholderSecret,
    endpoint,
    runtimeGeneration,
  });
  try {
    const initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams(), 30_000),
      "runtime/initialize",
    );
    if (initialized?.runtime?.engine !== "ja-kernel") {
      throw new Error("Native bootstrap did not start the Ja Kernel runtime");
    }
    session.notifyInitialized();
    const ready = await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    assert.equal(ready.params.generation, runtimeGeneration, "Native runtime generation did not match its requested identity");
    session.runtimeGeneration = ready.params.generation;
    return session;
  } catch (error) {
    await session.forceClose();
    if (!session.exited && error !== null && typeof error === "object") error.nativeSessionMayExist = true;
    throw error;
  }
}

/** 在临时 JA_HOME 写入 loopback Provider/占位凭据，并登记隔离工作区信任。 */
async function seedIsolatedProfile(session, fixture, directories) {
  const current = rpcResult(await session.request("configuration/read", {}, 20_000), "configuration/read");
  const document = providerConfigurationDocument({
    endpoint: fixture.baseUrl,
    api: "openai_responses",
    model: "subagent-approval-native-fixture",
    name: "Ja Native approval lifecycle fixture",
    selectedProviderId: providerId,
    selectedModelId: modelId,
    selectedCredentialId: credentialId,
  });
  document.providers[0].network_timeouts.request_timeout_ms = 120_000;
  const configured = rpcResult(
    await session.request(
      "configuration/replace",
      { scope: "user", expectedVersion: current.cas.userVersion, document },
      20_000,
    ),
    "configuration/replace",
  );
  assert.equal(configured.accepted, true);
  const credential = rpcResult(
    await session.request(
      "credential/set",
      {
        credentialId,
        secret: placeholderSecret,
        expectedVersion: current.cas.credentialVersion,
      },
      20_000,
    ),
    "credential/set",
  );
  assert.equal(credential.configured, true);
  const workspace = rpcResult(
    await session.request(
      "workspace/open",
      { cwd: directories.workspace, displayName: "Ja Native approval lifecycle" },
      20_000,
    ),
    "workspace/open",
  );
  assert.ok(typeof workspace.workspaceId === "string" && workspace.workspaceId.startsWith("ws_"));
  const trust = rpcResult(
    await session.request(
      "workspace/set-trust",
      { workspaceId: workspace.workspaceId, trust: "trusted" },
      20_000,
    ),
    "workspace/set-trust",
  );
  assert.equal(trust.accepted, true);
  return workspace.workspaceId;
}

/**
 * 冷重启只重建 runtime，不会自动恢复当前激活的 Workspace；恢复同一 Turn 前按原路径重新打开并锁定身份。
 * @author kongweiguang
 */
export async function reopenIsolatedWorkspace(session, directories, expectedWorkspaceId) {
  if (typeof expectedWorkspaceId !== "string" || !expectedWorkspaceId.startsWith("ws_")) {
    throw new Error("expected isolated workspace identity is invalid");
  }
  const workspace = rpcResult(
    await session.request(
      "workspace/open",
      { cwd: directories.workspace, displayName: "Ja Native approval lifecycle" },
      20_000,
    ),
    "workspace/open after cold restart",
  );
  assert.equal(
    workspace.workspaceId,
    expectedWorkspaceId,
    "reopened isolated workspace identity changed across cold restart",
  );
  return workspace;
}

/**
 * 只被动等待既有 expiry deadline；签发时间用于固定五分钟期限，即使调用发生在冷重启之后。
 * @author kongweiguang
 */
async function waitForExpiryEvent(session, approval, onProgress = () => {}, requestedAt = Date.now()) {
  const expiresAtMs = Date.parse(approval.expiresAt);
  const requestedAtMs = typeof requestedAt === "number" ? requestedAt : Date.parse(requestedAt);
  if (!Number.isFinite(expiresAtMs) || !Number.isFinite(requestedAtMs)) {
    throw new Error("approval expiry or request timestamp is invalid");
  }
  const lifetime = expiresAtMs - requestedAtMs;
  if (lifetime < approvalValidityMs - 15_000 || lifetime > approvalValidityMs + 15_000) {
    throw new Error("Native approval expiry was not the expected five-minute deadline");
  }
  const deadline = expiresAtMs + 90_000;
  while (Date.now() < deadline) {
    const remainingMs = deadline - Date.now();
    try {
      return await session.waitForEvent(
        "approval/closed",
        (frame) =>
          frame.params?.approvalId === approval.approvalId && frame.params?.reason === "expired",
        Math.min(eventWaitSliceMs, remainingMs),
      );
    } catch (error) {
      if (!String(error?.message ?? error).includes("event timed out")) throw error;
      onProgress(Math.max(0, expiresAtMs - Date.now()));
    }
  }
  return null;
}

/**
 * 等待审批 deadline 真正过去且不运行 Native；cold-expired 用此阶段区分启动结算与在线调度。
 * @author kongweiguang
 */
async function waitPastApprovalDeadline(expiresAt, onProgress = () => {}) {
  const deadline = Date.parse(expiresAt) + 250;
  if (!Number.isFinite(deadline)) throw new Error("approval expiry timestamp is invalid");
  while (Date.now() < deadline) {
    const remainingMs = deadline - Date.now();
    onProgress(remainingMs);
    await new Promise((resolveDelay) => setTimeout(resolveDelay, Math.min(eventWaitSliceMs, remainingMs)));
  }
}

/** 到期事件缺失时按顺序读取公开投影，区分 Core 状态与 Task/Thread 通知是否脱节。 */
async function capturePostExpiryReads(session, rootThreadId, childThreadId, approvalId) {
  const probes = [];
  const eventCount = () =>
    session.events.filter((frame) =>
      ["approval/closed", "turn/state-changed", "turn/terminal"].includes(frame.method),
    ).length;
  const record = async (method, request, summarize) => {
    const beforeEventCount = eventCount();
    try {
      const result = rpcResult(await session.request(method, request, 20_000), method);
      probes.push({ method, ...summarize(result), eventCountDelta: eventCount() - beforeEventCount });
    } catch (error) {
      probes.push({
        method,
        errorCode: String(error?.message ?? error).match(/\b[A-Z][A-Z0-9_]{2,63}\b/u)?.[0] ?? "READ_FAILED",
        eventCountDelta: eventCount() - beforeEventCount,
      });
    }
  };
  await record("task/list", { rootThreadId }, (result) => {
    const task = findTaskSummary(result, childThreadId);
    return { state: task.state, revision: task.revision };
  });
  await record("task/read", { taskThreadId: childThreadId, limit: 200 }, (result) => ({
    state: result.task?.state ?? null,
    revision: result.task?.revision ?? null,
  }));
  await record("thread/read", { threadId: childThreadId, cursor: null, limit: 200 }, (result) => ({
    revision: result.revision ?? null,
    itemCount: Array.isArray(result.items) ? result.items.length : null,
  }));
  await record("interaction/read", { threadId: childThreadId, interactionId: approvalId }, (result) => ({
    interactionStatus: result.request?.status ?? null,
    resumeState: result.resumeState ?? null,
    interactionRevision: result.request?.interactionRevision ?? null,
  }));
  return probes;
}

/** 在真实 task/list 快照中定位唯一 child，不从实时通知或调用顺序推断状态。 */
function findTaskSummary(taskList, taskThreadId) {
  const matches = Array.isArray(taskList?.items)
    ? taskList.items.filter((item) => item.taskThreadId === taskThreadId)
    : [];
  if (matches.length !== 1) throw new Error("task/list did not return one authoritative child projection");
  return matches[0];
}

/** 在有界 deadline 内读取 Task 投影，验证指定审批阶段的真实服务端状态。 */
async function waitForTaskState(session, rootThreadId, childThreadId, expectedState, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  let latest;
  while (Date.now() < deadline) {
    latest = rpcResult(
      await session.request("task/list", { rootThreadId }, 20_000),
      "task/list",
    );
    const task = findTaskSummary(latest, childThreadId);
    if (task.state === expectedState) return task;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
  }
  throw new Error(`Task projection did not reach ${expectedState}`);
}

/** 限定证据目录在仓库外；只豁免失败后保留的隔离 profile 路径供现场核验。 */
async function persistEvidence(directory, report, redactions, retainedPaths = []) {
  const target = resolve(directory);
  const relativePath = relative(repoRoot, target);
  if (!isAbsolute(relativePath) && relativePath !== ".." && !relativePath.startsWith(`..${sep}`)) {
    throw new Error("evidence directory must be outside the source repository");
  }
  await mkdir(target, { recursive: true });
  const path = join(target, `subagent-approval-native-${new Date().toISOString().replaceAll(/[:.]/gu, "-")}.json`);
  report.evidencePath = path;
  let json = JSON.stringify(report, null, 2);
  const retainedPathMarkers = retainedPaths.map((value, index) => {
    const marker = `__JA_RETAINED_PROFILE_PATH_${index}__`;
    json = json.replaceAll(value, marker);
    return { marker, value };
  });
  for (const value of redactions) {
    if (typeof value === "string" && value.length > 0) json = json.replaceAll(value, "[redacted]");
  }
  for (const { marker, value } of retainedPathMarkers) json = json.replaceAll(marker, value);
  await writeFile(path, `${json}\n`, "utf8");
  return path;
}

/** 只清理本次 runner 创建、位于真实 OS temp 下的隔离 JA_HOME。 */
async function cleanupOwnedProfile(root) {
  const physicalTemp = await realpath(tmpdir());
  const target = resolve(root);
  if (dirname(target) !== physicalTemp || !basename(target).startsWith("ja-real-provider-")) {
    throw new Error("refusing to clean a profile not owned by this acceptance runner");
  }
  await rm(target, { recursive: true, force: false });
}

/**
 * 用唯一隔离 profile 验证 hot 或冷启动 expiry；冷场景只杀本 runner 的 stdio 子进程，不重放审批或 Turn。
 * @author kongweiguang
 */
export async function runAcceptance(options = {}) {
  const nativeExecutable = resolve(options.nativeExecutable ?? "");
  const evidenceDirectory = resolve(options.evidenceDirectory ?? "");
  const scenario = options.scenario ?? "hot-expiry";
  if (!options.nativeExecutable || !options.evidenceDirectory) {
    throw new Error("native executable and external evidence directory are required");
  }
  if (!["hot-expiry", "cold-expiry", "cold-expired"].includes(scenario)) {
    throw new Error("scenario must be hot-expiry, cold-expiry, or cold-expired");
  }
  const directories = await createIsolatedDirectories();
  const actualNativeSha256 = await sha256File(nativeExecutable);
  const report = {
    schemaVersion: 1,
    status: "running",
    scenario,
    nativeExecutableSha256: actualNativeSha256,
    expectedNativeSha256: options.expectedNativeSha256 ?? null,
    phases: [],
    expiry: {},
    resume: {},
    toolExecution: {},
    cleanup: {},
  };
  const redactions = [placeholderSecret, directories.root, directories.home, directories.workspace];
  let fixture;
  let session;
  const nativeSessions = [];
  let profileMayExist = false;
  let phase = "verify_frozen_native_image";
  try {
    if (options.expectedNativeSha256 !== undefined) {
      assert.equal(actualNativeSha256, options.expectedNativeSha256, "Native Image SHA differs from the frozen artifact");
    }
    report.phases.push(phase);

    phase = "start_loopback_provider_and_isolated_native";
    fixture = await startApprovalProvider(directories.workspace);
    redactions.push(fixture.baseUrl);
    session = await startReadySession(nativeExecutable, directories, fixture.baseUrl);
    nativeSessions.push(session);
    profileMayExist = true;
    report.workspaceId = await seedIsolatedProfile(session, fixture, directories);
    report.phases.push(phase);

    phase = "spawn_approval_gated_child";
    const parent = rpcResult(
      await session.request(
        "thread/create",
        {
          cwd: directories.workspace,
          title: "Native approval expiry acceptance",
          providerId,
          modelId,
          reasoningLevel: "medium",
          accessMode: "full_access",
          collaborationMode: "default",
        },
        20_000,
      ),
      "thread/create",
    );
    const parentThreadId = parent.threadId;
    assert.ok(typeof parentThreadId === "string" && parentThreadId.startsWith("thr_"));
    const parentAccepted = rpcResult(
      await session.request(
        "turn/start",
        {
          threadId: parentThreadId,
          operationKey: createOperationKey(),
          content: [{ type: "text", text: `Delegate one approval-gated child. ${parentMarker}` }],
        },
        30_000,
      ),
      "turn/start parent",
    );
    const parentTurnId = parentAccepted.turnId;
    assert.ok(typeof parentTurnId === "string" && parentTurnId.startsWith("turn_"));
    phase = "observe_first_child_approval";
    const firstApprovalEvent = await session.waitForEvent(
      "approval/requested",
      (frame) =>
        frame.params?.threadId !== parentThreadId && frame.params?.toolName === "exec_command",
      60_000,
    );
    const firstApproval = firstApprovalEvent.params;
    const childThreadId = firstApproval.threadId;
    assert.ok(typeof childThreadId === "string" && childThreadId.startsWith("thr_"));
    const firstTurnId = firstApproval.turnId;
    assert.ok(typeof firstTurnId === "string" && firstTurnId.startsWith("turn_"));
    assert.ok(firstApproval.approvalId?.startsWith("appr_"));
    assert.equal(firstApproval.callId, expiredCallId);
    const firstWaitingTask = await waitForTaskState(
      session,
      parentThreadId,
      childThreadId,
      "waiting_approval",
    );
    report.expiry.requestedAt = firstApprovalEvent.params.occurredAt;
    report.expiry.expiresAt = firstApproval.expiresAt;
    report.expiry.approvalId = firstApproval.approvalId;
    report.expiry.turnId = firstTurnId;
    report.expiry.taskStateWhilePending = firstWaitingTask.state;
    report.expiry.taskRevisionWhilePending = firstWaitingTask.revision;
    report.parentThreadId = parentThreadId;
    report.parentTurnId = parentTurnId;
    report.childThreadId = childThreadId;
    report.phases.push(phase);

    const parentSpawnObservations = fixture.observations.filter(
      (entry) => entry.role === "parent" && entry.stage === "spawn_requested",
    );
    const parentSpawnResults = fixture.observations.filter(
      (entry) => entry.role === "parent" && entry.stage === "spawn_committed",
    );
    assert.equal(parentSpawnObservations.length, 1);
    assert.equal(parentSpawnResults.length, 1);
    assert.equal(parentSpawnResults[0].childThreadId, childThreadId);

    if (scenario !== "hot-expiry") {
      phase = "stop_native_with_original_approval_pending";
      const previousSession = session;
      const previousGeneration = previousSession.runtimeGeneration;
      const stoppedPid = previousSession.child.pid;
      assert.ok(Number.isSafeInteger(stoppedPid) && stoppedPid > 0, "Native child PID must be known before forceClose");
      const originalApprovalResolvedBeforeStop = previousSession.events.some(
        (frame) =>
          frame.method === "approval/resolved" &&
          frame.params?.approvalId === firstApproval.approvalId,
      );
      assert.equal(originalApprovalResolvedBeforeStop, false);
      report.coldRestart = {
        stoppedGeneration: previousGeneration,
        stoppedPid,
        originalApprovalResolvedBeforeStop,
      };
      await previousSession.forceClose();
      assert.equal(previousSession.exited, true, "runner-owned Native stdio process did not exit after forceClose");
      report.coldRestart.stoppedProcess = previousSession.exitResult;
      session = undefined;

      if (scenario === "cold-expired") {
        phase = "wait_for_deadline_with_native_stopped";
        await waitPastApprovalDeadline(firstApproval.expiresAt, (remainingMs) => {
          process.stdout.write(`WAITING_WITH_NATIVE_STOPPED remainingSeconds=${Math.ceil(remainingMs / 1000)}\n`);
        });
        report.coldRestart.deadlinePassedWhileStopped = true;
        report.phases.push(phase);
      }

      phase = "restart_same_profile_before_or_after_deadline";
      const nextGeneration = previousGeneration + 1;
      const restartedAt = new Date().toISOString();
      session = await startReadySession(nativeExecutable, directories, fixture.baseUrl, nextGeneration);
      nativeSessions.push(session);
      const reopenedWorkspace = await reopenIsolatedWorkspace(session, directories, report.workspaceId);
      report.coldRestart.workspaceIdAfterRestart = reopenedWorkspace.workspaceId;
      const remainingMs = Date.parse(firstApproval.expiresAt) - Date.now();
      if (scenario === "cold-expiry") {
        assert.ok(remainingMs > 0, "cold-expiry must restart before the original approval deadline");
        const restoredTask = rpcResult(
          await session.request("task/read", { taskThreadId: childThreadId, limit: 200 }, 20_000),
          "task/read after pre-deadline cold restart",
        );
        assert.equal(restoredTask.task?.state, "waiting_approval");
        assert.equal(restoredTask.task?.taskThreadId, childThreadId);
        const restoredThread = rpcResult(
          await session.request("thread/read", { threadId: childThreadId, cursor: null, limit: 200 }, 20_000),
          "thread/read after pre-deadline cold restart",
        );
        assert.equal(restoredThread.threadId, childThreadId);
        const restoredTurn = restoredThread.turns.find((turn) => turn.turnId === firstTurnId);
        assert.equal(restoredTurn?.status, "waiting_approval");
        report.coldRestart.pendingTaskStateAfterRestart = restoredTask.task.state;
        report.coldRestart.pendingThreadTurnStateAfterRestart = restoredTurn.status;
        report.coldRestart.pendingThreadRevisionAfterRestart = restoredThread.revision;
        report.coldRestart.deadlineRemainingAtRestartMs = remainingMs;
      } else {
        assert.ok(remainingMs <= 0, "cold-expired must restart after the original approval deadline");
        report.coldRestart.deadlineRemainingAtRestartMs = remainingMs;
      }
      const childProviderStages = fixture.observations
        .filter((entry) => entry.role === "child")
        .map((entry) => entry.stage);
      assert.deepEqual(childProviderStages, ["first_approval_requested"]);
      report.coldRestart.noProviderResumeBeforeExplicitTurnResume = true;
      report.coldRestart.restartedAt = restartedAt;
      report.coldRestart.restartedGeneration = session.runtimeGeneration;
      assert.ok(session.runtimeGeneration > previousGeneration);
      report.phases.push(phase);
    }

    phase = "wait_for_natural_five_minute_expiry";
    let expiredEvent = await waitForExpiryEvent(session, firstApproval, (remainingMs) => {
      process.stdout.write(`WAITING_FOR_NATIVE_APPROVAL_EXPIRY remainingSeconds=${Math.ceil(remainingMs / 1000)}\n`);
    }, Date.parse(firstApprovalEvent.params.occurredAt));
    if (expiredEvent === null) {
      report.expiry.timerDidNotPublishByGraceDeadline = true;
      report.expiry.publicReadProbes = await capturePostExpiryReads(
        session,
        parentThreadId,
        childThreadId,
        firstApproval.approvalId,
      );
      expiredEvent = session.events.find(
        (frame) =>
          frame.method === "approval/closed" &&
          frame.params?.approvalId === firstApproval.approvalId &&
          frame.params?.reason === "expired",
      ) ?? null;
      report.expiry.eventsAfterPublicReads = session.events
        .filter((frame) =>
          frame.params?.threadId === childThreadId &&
          ["approval/closed", "turn/state-changed", "turn/terminal"].includes(frame.method),
        )
        .slice(-12)
        .map((frame) => ({
          method: frame.method,
          state: frame.params?.state ?? frame.params?.to,
          reason: frame.params?.reason,
          revision: frame.params?.threadRevision,
        }));
      if (expiredEvent === null) {
        throw new Error("Natural approval expiry was not published before or after public read probes");
      }
      report.expiry.settlementObservedAfterPublicReads = true;
    } else {
      report.expiry.timerDidNotPublishByGraceDeadline = false;
      report.expiry.settlementObservedByTimer = true;
    }
    report.expiry.closedAt = expiredEvent.params.occurredAt;
    report.expiry.closeReason = expiredEvent.params.reason;
    report.expiry.closedThreadRevision = expiredEvent.params.threadRevision;
    assert.equal(expiredEvent.params.approvalId, firstApproval.approvalId);
    assert.equal(expiredEvent.params.reason, "expired");
    const originalApprovalResolved = nativeSessions.some((nativeSession) =>
      nativeSession.events.some(
        (frame) =>
          frame.method === "approval/resolved" &&
          frame.params?.approvalId === firstApproval.approvalId,
      ),
    );
    assert.equal(originalApprovalResolved, false, "expired approval must close without a user decision");
    report.expiry.closedWithoutUserDecision = true;
    const suspendedTask = await waitForTaskState(
      session,
      parentThreadId,
      childThreadId,
      "suspended",
      60_000,
    );
    const suspendedDetail = rpcResult(
      await session.request("task/read", { taskThreadId: childThreadId, limit: 200 }, 20_000),
      "task/read after natural expiry",
    );
    assert.equal(suspendedDetail.task.state, "suspended");
    assert.equal(suspendedDetail.task.revision, suspendedTask.revision);
    const expiredHistory = rpcResult(
      await session.request("thread/read", { threadId: childThreadId, cursor: null, limit: 200 }, 20_000),
      "thread/read after natural expiry",
    );
    assert.equal(expiredHistory.threadId, childThreadId);
    assert.equal(expiredHistory.revision, expiredEvent.params.threadRevision);
    const suspendedTurn = expiredHistory.turns.find((turn) => turn.turnId === firstTurnId);
    assert.equal(suspendedTurn?.status, "suspended", "Thread turn projection must suspend with its expired approval");
    await assertFileAbsent(fixture.expiredTarget);
    const childProviderStagesBeforeResume = fixture.observations
      .filter((entry) => entry.role === "child")
      .map((entry) => entry.stage);
    assert.deepEqual(
      childProviderStagesBeforeResume,
      ["first_approval_requested"],
      "expired child must stay suspended until an explicit turn/resume",
    );
    report.expiry.taskStateAfterNaturalExpiry = suspendedTask.state;
    report.expiry.threadTurnStateAfterNaturalExpiry = suspendedTurn.status;
    report.expiry.taskRevisionAfterNaturalExpiry = suspendedTask.revision;
    report.expiry.threadRevisionAfterNaturalExpiry = expiredHistory.revision;
    report.expiry.noToolSideEffectBeforeResume = true;
    report.phases.push(phase);

    phase = "resume_same_suspended_turn";
    const resume = rpcResult(
      await session.request(
        "turn/resume",
        { turnId: firstTurnId, expectedThreadRevision: expiredHistory.revision },
        30_000,
      ),
      "turn/resume child after natural expiry",
    );
    assert.equal(resume.accepted, true);
    assert.equal(resume.turnId, firstTurnId);
    const secondApprovalEvent = await session.waitForEvent(
      "approval/requested",
      (frame) =>
        frame.params?.threadId === childThreadId &&
        frame.params?.turnId === firstTurnId &&
        frame.params?.toolName === "exec_command" &&
        frame.params?.approvalId !== firstApproval.approvalId,
      60_000,
    );
    const secondApproval = secondApprovalEvent.params;
    assert.equal(secondApproval.callId, resumedCallId);
    const resumedWaitingTask = await waitForTaskState(
      session,
      parentThreadId,
      childThreadId,
      "waiting_approval",
      60_000,
    );
    assert.notEqual(secondApproval.approvalId, firstApproval.approvalId);
    assert.equal(secondApproval.turnId, firstTurnId);
    report.resume.acceptedTurnId = resume.turnId;
    report.resume.sameTurn = resume.turnId === firstTurnId;
    report.resume.newApprovalId = secondApproval.approvalId;
    report.resume.taskStateAtSecondApproval = resumedWaitingTask.state;
    report.resume.taskRevisionAtSecondApproval = resumedWaitingTask.revision;
    report.resume.approvalRequestedAt = secondApprovalEvent.params.occurredAt;
    report.phases.push(phase);

    phase = "approve_new_call_and_wait_for_terminal";
    const allow = rpcResult(
      await session.request(
        "approval/respond",
        {
          approvalId: secondApproval.approvalId,
          turnId: firstTurnId,
          decision: "approve",
          expectedThreadRevision: secondApproval.threadRevision,
          operationKey: createOperationKey(),
        },
        30_000,
      ),
      "approval/respond for new call",
    );
    assert.equal(allow.accepted, true);
    assert.equal(allow.approvalId, secondApproval.approvalId);
    assert.equal(allow.turnId, firstTurnId);
    assert.equal(allow.decision, "approve");
    const resolved = await session.waitForEvent(
      "approval/resolved",
      (frame) =>
        frame.params?.approvalId === secondApproval.approvalId && frame.params?.turnId === firstTurnId,
      30_000,
    );
    assert.equal(resolved.params?.decision, "approve");
    const terminal = await session.waitForEvent(
      "turn/terminal",
      (frame) => frame.params?.turnId === firstTurnId,
      120_000,
    );
    assert.equal(terminal.params?.state, "completed");
    const finalText = terminal.params?.finalMessage?.text;
    assert.ok(typeof finalText === "string" && finalText.includes(finalMarker));
    const finalTask = await waitForTaskState(
      session,
      parentThreadId,
      childThreadId,
      "completed",
      60_000,
    );
    const finalHistory = rpcResult(
      await session.request("thread/read", { threadId: childThreadId, cursor: null, limit: 200 }, 20_000),
      "thread/read after completed resume",
    );
    const successfulCalls = finalHistory.items.filter(
      (item) =>
        item.kind === "tool_call" &&
        item.turnId === firstTurnId &&
        item.toolName === "exec_command" &&
        item.presentation?.status === "success",
    );
    assert.equal(successfulCalls.length, 1);
    assert.equal(successfulCalls[0].callId, resumedCallId);
    const expiredResultObservations = fixture.observations.filter(
      (entry) => entry.role === "child" && entry.stage === "expired_tool_result_resumed",
    );
    assert.equal(expiredResultObservations.length, 1, "resumed Provider must receive the expired ToolResult exactly once");
    const parentTerminal = findTerminalAcrossSessions(nativeSessions, parentTurnId);
    assert.equal(parentTerminal?.params?.state, "completed");
    const parentHistory = rpcResult(
      await session.request("thread/read", { threadId: parentThreadId, cursor: null, limit: 200 }, 20_000),
      "thread/read parent after child completion",
    );
    const childParentActivities = parentHistory.taskActivities.filter(
      (entry) => entry.task.taskThreadId === childThreadId,
    ).sort((left, right) => left.activity.activitySequence - right.activity.activitySequence);
    assert.ok(childParentActivities.length > 0, "parent Thread must retain the child Task activity");
    assert.equal(childParentActivities.at(-1).activity.kind, "completed");
    assert.equal(childParentActivities.at(-1).task.state, "completed");
    await assertFileAbsent(fixture.expiredTarget);
    const sideEffect = await readFile(fixture.approvedTarget, "utf8");
    assertSingleApprovedWrite(sideEffect);
    assert.equal(fixture.failure(), null);
    report.resume.terminalTurnId = terminal.params.turnId;
    report.resume.terminalState = terminal.params.state;
    report.resume.finalAnswerMarkerCommitted = true;
    report.toolExecution.approvedCallId = successfulCalls[0].callId;
    report.toolExecution.successfulExecCommandCount = successfulCalls.length;
    report.toolExecution.appendedMarkerCount = sideEffect.trim().split(/\r?\n/u).length;
    report.toolExecution.expiredAttemptAbsent = true;
    report.taskFinal = { state: finalTask.state, revision: finalTask.revision };
    report.parentActivityFinal = {
      kind: childParentActivities.at(-1).activity.kind,
      taskState: childParentActivities.at(-1).task.state,
      activitySequence: childParentActivities.at(-1).activity.activitySequence,
    };
    report.fixtureStages = fixture.observations.map(({ role, stage, childThreadId: observedChild }) => ({
      role,
      stage,
      ...(observedChild === undefined ? {} : { childThreadId: observedChild }),
    }));
    report.phases.push(phase);
    report.status = "completed";
  } catch (error) {
    report.status = "failed";
    profileMayExist ||= error?.nativeSessionMayExist === true;
    report.failure = { phase, message: String(error?.message ?? error).slice(0, 300) };
    if (session !== undefined) {
      report.nativeStderrTail = session.stderr.slice(-4_096);
    }
    report.runtimeEvents = nativeSessions
      .flatMap((nativeSession) => nativeSession.events)
      .filter((frame) =>
        ["turn/state-changed", "approval/requested", "approval/resolved", "approval/closed", "turn/terminal"].includes(frame.method),
      )
      .slice(-48)
      .map((frame) => ({
        method: frame.method,
        threadId: frame.params?.threadId,
        turnId: frame.params?.turnId,
        state: frame.params?.state ?? frame.params?.to,
        approvalId: frame.params?.approvalId,
        reason: frame.params?.reason,
        toolName: frame.params?.toolName,
        errorCode: frame.params?.errorCode,
      }));
    if (fixture !== undefined) {
      report.fixtureFailure = fixture.failure();
      report.fixtureStages = fixture.observations.map(({ role, stage, childThreadId: observedChild }) => ({
        role,
        stage,
        ...(observedChild === undefined ? {} : { childThreadId: observedChild }),
      }));
    }
  } finally {
    if (session !== undefined) {
      if (scenario !== "hot-expiry" && report.status !== "completed") {
        await session.forceClose().catch(() => undefined);
        profileMayExist = !session.exited;
        report.cleanup.nativeShutdown = profileMayExist ? "unconfirmed" : "force_closed_after_cold_scenario_failure";
        report.status = "failed";
      } else {
        try {
          const exit = await session.shutdown();
          profileMayExist = false;
          report.cleanup.nativeExitCode = exit.code;
          report.cleanup.nativeShutdown = exit.code === 0 ? "confirmed" : "nonzero_exit";
          if (exit.code !== 0) report.status = "failed";
        } catch {
          await session.forceClose().catch(() => undefined);
          profileMayExist = !session.exited;
          report.cleanup.nativeShutdown = profileMayExist ? "unconfirmed" : "force_closed";
          report.status = "failed";
        }
      }
    }
    if (fixture !== undefined) {
      try {
        await fixture.close();
        report.cleanup.loopbackProvider = "closed";
      } catch {
        report.cleanup.loopbackProvider = "close_failed";
        report.status = "failed";
      }
    }
    if (!profileMayExist && (report.status === "completed" || session === undefined)) {
      try {
        await cleanupOwnedProfile(directories.root);
        report.cleanup.isolatedProfile = "removed_after_native_exit";
      } catch {
        report.cleanup.isolatedProfile = "cleanup_failed";
        report.status = "failed";
      }
    } else {
      report.cleanup.isolatedProfile = profileMayExist
        ? "retained_until_native_exit_is_confirmed"
        : "retained_for_failure_diagnostics";
      report.cleanup.retainedProfilePath = directories.root;
    }
    const retainedPaths = report.cleanup.retainedProfilePath === undefined
      ? []
      : [report.cleanup.retainedProfilePath];
    report.evidencePath = await persistEvidence(evidenceDirectory, report, redactions, retainedPaths).catch(() => null);
  }
  if (report.status !== "completed") {
    const phaseText = report.failure?.phase ?? "cleanup";
    throw new Error(`Native approval lifecycle failed at ${phaseText}; evidence=${report.evidencePath ?? "unavailable"}`);
  }
  return report;
}

/** 父 Turn 在停机前已完成；跨代际保留真实通知，不要求新连接重放旧终态。 */
export function findTerminalAcrossSessions(sessions, turnId) {
  return sessions.flatMap((session) => session.events).find(
    (frame) => frame.method === "turn/terminal" && frame.params?.turnId === turnId,
  );
}

/** PowerShell 原生换行随平台变化；只允许一个完整 marker，重复执行和额外内容仍必须失败。 */
export function assertSingleApprovedWrite(text) {
  assert.equal(text.replaceAll("\r\n", "\n"), "NATIVE_APPROVED_TOOL_EXECUTED\n");
}

/** 不存在的 expired-attempt 输出证明过期审批没有派发原始宿主副作用。 */
async function assertFileAbsent(path) {
  try {
    await stat(path);
  } catch (error) {
    if (error?.code === "ENOENT") return;
    throw error;
  }
  throw new Error("approval-gated command ran without a current explicit approval");
}

/** 直接执行仅供人工指定产物的 acceptance runner。 */
async function main() {
  const report = await runAcceptance(parseArguments());
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch((error) => {
    process.stderr.write(`${String(error?.message ?? error)}\n`);
    process.exitCode = 1;
  });
}
