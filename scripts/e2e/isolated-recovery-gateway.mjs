// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 通过临时 Ja home 与 loopback Responses bridge 核验失败 Tool 的继续请求。
 * Java 只能访问本机 listener；本文件是唯一能向配置网关建立 HTTP 请求的 owner。
 */

import { execFile } from "node:child_process";
import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { constants as fsConstants } from "node:fs";
import { copyFile, lstat, mkdir, open, readFile, realpath, rm, stat } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import process from "node:process";
import { createServer } from "node:http";
import { randomBytes } from "node:crypto";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  createIsolatedDirectories,
  initializeParams,
  JsonlSession,
} from "./real-provider-smoke.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const readyToken = "0123456789abcdef0123456789abcdef";
const handshakeHandlerPath = join(
  repoRoot,
  "app-server",
  "src",
  "main",
  "java",
  "io",
  "github",
  "kongweiguang",
  "ja",
  "transport",
  "rpc",
  "handler",
  "HandshakeHandler.java",
);
const maxRequestBytes = 4 * 1024 * 1024;
const maxErrorBytes = 64 * 1024;
const allowedStages = new Set(["baseline", "fixed"]);
const allowedCheckpoints = new Set([
  "copy-config",
  "java-and-jar",
  "app-server-start",
  "runtime-initialize",
  "runtime-ready",
  "configuration-read",
  "profile-selection",
  "credential-set",
  "credential-readback",
  "bridge-start",
  "configuration-replace",
  "configuration-readback",
  "workspace-open",
  "thread-create",
  "seed-turn-start",
  "seed-turn-terminal",
  "seed-history",
  "continuation-start",
  "continuation-terminal",
]);
const allowedStatuses = new Set([
  200, 201, 202, 400, 401, 403, 404, 408, 409, 422, 429, 500, 502, 503, 504,
]);
const allowedErrorCodes = new Set([
  "invalid_value",
  "invalid_request_error",
  "server_error",
  "bad_request",
  "unsupported_value",
  "invalid_api_key",
]);
const egressOverrideEnvironmentNames = [
  "HTTP_PROXY",
  "HTTPS_PROXY",
  "ALL_PROXY",
  "NO_PROXY",
  "http_proxy",
  "https_proxy",
  "all_proxy",
  "no_proxy",
  "JAVA_TOOL_OPTIONS",
  "_JAVA_OPTIONS",
  "JDK_JAVA_OPTIONS",
];
const paramPathPattern =
  /^[A-Za-z][A-Za-z0-9_]*(?:(?:\.[A-Za-z][A-Za-z0-9_]*)|(?:\[[0-9]{1,6}\])){0,12}$/u;

/**
 * 确认真实请求阶段由主任务逐轮授权；仅设置 baseline 或 fixed 参数不构成授权。
 */
export function requireRealRoundAuthorization(stage, environment = process.env) {
  if (!allowedStages.has(stage)) throw new Error("stage must be baseline or fixed");
  const expected = "GO_" + stage.toUpperCase();
  if (environment.JA_ISOLATED_RECOVERY_GATEWAY_GO !== expected) {
    throw new Error("JA_ISOLATED_RECOVERY_GATEWAY_GO=" + expected + " is required");
  }
}

/**
 * 只接受无凭据、无查询的 HTTP(S) base URL，同时保留用户实际网关配置的 HTTP/HTTPS。
 */
export function validateUpstreamBaseUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error("configured Provider endpoint is invalid");
  }
  if (
    !["http:", "https:"].includes(url.protocol) ||
    url.hostname.length === 0 ||
    url.username !== "" ||
    url.password !== "" ||
    url.search !== "" ||
    url.hash !== ""
  ) {
    throw new Error("configured Provider endpoint has an unsafe shape");
  }
  return url;
}

/** Mirrors the production OpenAI endpoint rule so the evidence bridge targets the same path. */
export function configuredResponsesEndpoint(baseUri) {
  let value = validateUpstreamBaseUrl(baseUri).href;
  while (value.endsWith("/")) value = value.slice(0, -1);
  const suffix = "/v1/responses";
  if (value.endsWith(suffix)) return new URL(value);
  if (value.endsWith("/v1")) return new URL(value + "/responses");
  return new URL(value + suffix);
}

/**
 * 只选择 config.toml 当前默认的 OpenAI Responses Provider 与模型，不尝试供应商回退。
 */
export function selectConfiguredResponsesProfile(document) {
  if (document === null || typeof document !== "object" || Array.isArray(document)) {
    throw new Error("isolated configuration has an invalid shape");
  }
  const providerId = document.default_provider_id;
  const modelId = document.default_model_id;
  if (typeof providerId !== "string" || !/^provider_[A-Za-z0-9_-]{1,96}$/u.test(providerId)) {
    throw new Error("isolated configuration has no valid default Provider");
  }
  if (typeof modelId !== "string" || !/^model_[A-Za-z0-9_-]{1,96}$/u.test(modelId)) {
    throw new Error("isolated configuration has no valid default model");
  }
  const providers = Array.isArray(document.providers) ? document.providers : [];
  const provider = providers.find((item) => item?.provider_id === providerId);
  const model = Array.isArray(provider?.models)
    ? provider.models.find((item) => item?.model_id === modelId)
    : undefined;
  if (
    provider === undefined ||
    provider.api !== "openai_responses" ||
    typeof provider.credential_id !== "string" ||
    !/^cred_[A-Za-z0-9_-]{1,96}$/u.test(provider.credential_id) ||
    model === undefined ||
    typeof model.model !== "string" ||
    model.model.length === 0
  ) {
    throw new Error("default Provider is not a complete Responses profile");
  }
  return {
    providerId,
    modelId,
    provider,
    model,
    upstream: validateUpstreamBaseUrl(provider.base_url),
  };
}

/**
 * 将隔离副本限制为单一 Provider 和需审批权限；MCP 清空，Skills 由隔离目录发现。
 */
export function isolatedProviderDocument(document, profile, loopbackBaseUrl) {
  return {
    ...document,
    schema_version: 2,
    config_revision: Number.isSafeInteger(document.config_revision)
      ? document.config_revision + 1
      : 0,
    default_access_mode: "approval_required",
    default_provider_id: profile.providerId,
    default_model_id: profile.modelId,
    providers: [{ ...profile.provider, base_url: loopbackBaseUrl, models: [profile.model] }],
    mcp_servers: [],
    disabled_skills: [],
  };
}

/**
 * 对网关错误只保留有限 status、code、param；错误 message 与完整 body 不会进入报告。
 */
export function safeGatewayFailure(status, body) {
  const safeStatus = allowedStatuses.has(status) ? status : "unavailable";
  let payload;
  try {
    payload = JSON.parse(Buffer.from(body).subarray(0, maxErrorBytes).toString("utf8"));
  } catch {
    payload = undefined;
  }
  const error =
    payload?.error !== null && typeof payload?.error === "object" ? payload.error : payload;
  const code =
    typeof error?.code === "string" && allowedErrorCodes.has(error.code)
      ? error.code
      : "unavailable";
  const param =
    typeof error?.param === "string" && paramPathPattern.test(error.param)
      ? error.param
      : "unavailable";
  return { status: safeStatus, code, param };
}

/** Removes proxy and JVM option injection from the isolated Java child without changing the parent process. */
export function sanitizedJavaEnvironment(environment = process.env) {
  const sanitized = { ...environment };
  for (const name of egressOverrideEnvironmentNames) delete sanitized[name];
  return sanitized;
}

/** Uses the Java-owned order while checking each advertised capability against the schema's closed enum. */
export async function recoveryInitializeParams() {
  const initialize = initializeParams();
  const schemaPath = join(repoRoot, "contracts", "ja-rpc", "v1", "schema", "ja-rpc-v1.schema.json");
  const [handlerSource, schemaSource] = await Promise.all([
    readFile(handshakeHandlerPath, "utf8"),
    readFile(schemaPath, "utf8"),
  ]);
  const schema = JSON.parse(schemaSource);
  const methods = javaCapabilityList(handlerSource, "METHODS");
  const events = javaCapabilityList(handlerSource, "EVENTS");
  assertSchemaMembership(methods, schema?.$defs?.methodName?.enum);
  assertSchemaMembership(events, schema?.$defs?.eventName?.enum);
  initialize.capabilities.methods = methods;
  initialize.capabilities.events = events;
  return initialize;
}

/** Fails before startup if Java's strict handshake source changed after the packaged JAR. */
export async function assertHandshakeJarFreshness(jarPath, sourcePath = handshakeHandlerPath) {
  const [jarInfo, sourceInfo] = await Promise.all([stat(jarPath), stat(sourcePath)]);
  if (!jarInfo.isFile() || !sourceInfo.isFile() || jarInfo.mtimeMs < sourceInfo.mtimeMs) {
    throw new Error("app-server jar predates the current handshake source");
  }
}

/** Reads string literals only from one fixed Java List.of declaration; source is never evaluated. */
function javaCapabilityList(source, name) {
  const declaration = new RegExp(
    "static\\s+final\\s+List<String>\\s+" + name + "\\s*=\\s*List\\.of\\(([^;]*?)\\);",
    "u",
  );
  const body = declaration.exec(source)?.[1];
  if (typeof body !== "string")
    throw new Error("JA-RPC Java capability declaration is unavailable");
  const values = [...body.matchAll(/"([^"\\]*)"/gu)].map((match) => match[1]);
  const residue = body.replace(/"[^"\\]*"/gu, "").replace(/[\s,]/gu, "");
  if (
    residue !== "" ||
    values.length === 0 ||
    values.some((value) => !/^[a-z][a-z0-9]*(?:[-_/][a-z0-9]+)*$/u.test(value)) ||
    new Set(values).size !== values.length
  ) {
    throw new Error("JA-RPC Java capability declaration is invalid");
  }
  return values;
}

/** Rejects Java-owned values absent from the JSON Schema enum without reordering either list. */
function assertSchemaMembership(values, allowedValues) {
  if (
    !Array.isArray(allowedValues) ||
    allowedValues.some((value) => typeof value !== "string") ||
    values.some((value) => !allowedValues.includes(value))
  ) {
    throw new Error("JA-RPC capability is outside the closed schema enum");
  }
}

/**
 * 检查续答确实带回原问题和目标失败 ToolResult，且没有新增“继续”用户消息。
 */
export function continuationEvidence(payload, { callId, promptMarker }) {
  const input = payload?.input;
  if (!Array.isArray(input)) {
    return { eligible: false, failedOutput: false, visibleContinue: false };
  }
  const outputs = input.filter(
    (item) => item?.type === "function_call_output" && item.call_id === callId,
  );
  const calls = input.filter((item) => item?.type === "function_call" && item.call_id === callId);
  const visibleContinue = input.some((item) => {
    if (item?.role !== "user") return false;
    const text =
      typeof item.content === "string"
        ? item.content
        : Array.isArray(item.content)
          ? item.content
              .map((block) => (typeof block?.text === "string" ? block.text : ""))
              .join("\n")
          : "";
    return text.trim() === "继续";
  });
  const failedOutput = outputs.length === 1 && typeof outputs[0].output === "string";
  const hasOriginalPrompt = JSON.stringify(input).includes(promptMarker);
  return {
    eligible: failedOutput && calls.length === 1 && hasOriginalPrompt && !visibleContinue,
    failedOutput,
    visibleContinue,
    output: outputs[0],
  };
}

/**
 * baseline 阳性对照只插入目标 output 的 incomplete 字段；其它 JSON 值保持不变。
 */
export function baselineBody(rawBody, identity) {
  const payload = JSON.parse(rawBody);
  const evidence = continuationEvidence(payload, identity);
  if (!evidence.eligible) throw new Error("baseline request is not the seeded continuation");
  if (evidence.output.status !== undefined && evidence.output.status !== "incomplete") {
    throw new Error("baseline Tool output already has an unexpected status");
  }
  const inserted = evidence.output.status === undefined;
  evidence.output.status = "incomplete";
  return { body: Buffer.from(JSON.stringify(payload), "utf8"), inserted };
}

/**
 * fixed 轮直接透传 Ja 原始 body；如果 incomplete 仍存在则在出站前失败关闭。
 */
export function fixedBody(rawBody, identity) {
  const payload = JSON.parse(rawBody);
  const evidence = continuationEvidence(payload, identity);
  if (!evidence.eligible || evidence.output.status !== undefined) {
    throw new Error("fixed request does not contain the repaired failed Tool output");
  }
  return rawBody;
}

/**
 * 建立仅位于本轮 temp/evidence root 的两槽预算；slot 在任何 socket 写入之前原子占用。
 */
export async function createExchangeBudget(directory, stage) {
  if (!allowedStages.has(stage) || !isAbsolute(directory)) {
    throw new Error("an absolute budget directory and a valid stage are required");
  }
  const tempRoot = await realpath(tmpdir());
  const target = resolve(directory);
  const parent = await realpath(dirname(target)).catch(() => undefined);
  if (parent === undefined || !isWithin(tempRoot, parent)) {
    throw new Error("budget directory must be inside the current temp evidence root");
  }
  const leaf = target.split(/[\\/]/u).at(-1);
  if (typeof leaf !== "string" || leaf.length === 0)
    throw new Error("budget directory name is invalid");
  const resolved = join(parent, leaf);
  await mkdir(resolved, { recursive: false }).catch((error) => {
    if (error?.code !== "EEXIST") throw new Error("budget directory could not be created");
  });
  const directoryInfo = await lstat(resolved);
  if (!directoryInfo.isDirectory() || directoryInfo.isSymbolicLink()) {
    throw new Error("budget directory must be an ordinary directory");
  }
  const slotPath = (slot) => join(resolved, "slot-" + slot + ".json");
  return {
    /** Reserves the stage's only exchange before any socket can be opened. */
    async reserve() {
      const firstPath = slotPath(1);
      const secondPath = slotPath(2);
      if (stage === "baseline") {
        if ((await exists(firstPath)) || (await exists(secondPath))) {
          throw new Error("baseline exchange slot is already consumed");
        }
        await reserveSlot(firstPath, "baseline");
        return 1;
      }
      if (!(await exists(firstPath)) || (await exists(secondPath))) {
        throw new Error("fixed exchange requires the unused second slot after baseline");
      }
      if ((await readSlot(firstPath)).stage !== "baseline") {
        throw new Error("baseline exchange slot is invalid");
      }
      await reserveSlot(secondPath, "fixed");
      return 2;
    },
  };
}

/**
 * 启动 loopback fixture 与计数 bridge；只有 real 阶段且请求包含目标历史时才能转发。
 */
export async function startRecoveryGatewayBridge({
  upstreamBaseUrl,
  stage,
  budget,
  callId,
  promptMarker,
}) {
  if (!allowedStages.has(stage)) throw new Error("bridge stage must be baseline or fixed");
  const upstreamBase = validateUpstreamBaseUrl(upstreamBaseUrl);
  const identity = { callId, promptMarker };
  let phase = "seed";
  let fixtureCallIssued = false;
  let fixtureRequestClass = "unavailable";
  let externalRequestStarted = false;
  let loopbackProviderRequestCount = 0;
  let seedFailureRequests = 0;
  let gatewayEvidence;
  let externalExchangeCount = 0;
  const server = createServer(async (request, response) => {
    loopbackProviderRequestCount += 1;
    try {
      const body = await readBoundedBody(request);
      if (request.method !== "POST" || request.url !== "/v1/responses") {
        sendJson(response, 404, { error: { code: "fixture_route_not_found" } });
        return;
      }
      const payload = JSON.parse(body.toString("utf8"));
      if (phase === "seed" || phase === "local-continuation") {
        await respondFromFixture(
          payload,
          response,
          phase,
          callId,
          (classification) => {
            fixtureRequestClass = classification;
          },
          () => {
            if (fixtureCallIssued) return false;
            fixtureCallIssued = true;
            return true;
          },
          () => {
            seedFailureRequests += 1;
          },
        );
        return;
      }
      if (phase !== "real") {
        sendJson(response, 409, { error: { code: "fixture_phase_invalid" } });
        return;
      }
      if (externalRequestStarted) {
        sendJson(response, 429, { error: { code: "JA_RECOVERY_EGRESS_BUDGET_EXHAUSTED" } });
        return;
      }
      const outboundBody =
        stage === "baseline" ? baselineBody(body, identity).body : fixedBody(body, identity);
      await budget.reserve();
      externalRequestStarted = true;
      externalExchangeCount += 1;
      await forwardExactlyOnce({
        request,
        response,
        upstreamBase,
        outboundBody,
        record: (entry) => {
          gatewayEvidence = entry;
        },
      });
    } catch {
      if (!response.headersSent)
        sendJson(response, 400, { error: { code: "JA_RECOVERY_GATE_BLOCKED" } });
      else response.destroy();
    }
  });
  await new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("loopback listener failed");
  return {
    baseUrl: "http://127.0.0.1:" + address.port + "/v1",
    get externalExchangeCount() {
      return externalExchangeCount;
    },
    get fixtureCallIssued() {
      return fixtureCallIssued;
    },
    get fixtureRequestClass() {
      return fixtureRequestClass;
    },
    get loopbackProviderRequestCount() {
      return loopbackProviderRequestCount;
    },
    get gatewayEvidence() {
      return gatewayEvidence;
    },
    get seedFailureRequests() {
      return seedFailureRequests;
    },
    /** Keeps preflight continuation bound to the local fixture. */
    setLocalContinuation() {
      phase = "local-continuation";
    },
    /** Enables the one authorized gateway continuation after the fixture history is verified. */
    setRealContinuation() {
      phase = "real";
    },
    /** Closes loopback connections so cleanup cannot leave an active request handler behind. */
    async close() {
      server.closeAllConnections();
      await new Promise((resolveClose) => server.close(resolveClose));
    },
  };
}

/**
 * Reduces child stderr to Java exception identities and Ja-owned source frames so preflight
 * diagnosis can identify the failing layer without exposing log messages or environment data.
 */
export function safePreflightDiagnostics(stderr) {
  const source = String(stderr ?? "").slice(-maxErrorBytes);
  const rpcFailurePattern =
    /Unexpected JA-RPC failure errorId=[A-Za-z0-9_-]{1,80} type=([A-Za-z_$][A-Za-z0-9_$.]{1,255}) causeType=(none|[A-Za-z_$][A-Za-z0-9_$.]{1,255}) origin=(unknown|io\.github\.kongweiguang\.ja\.[A-Za-z_$][A-Za-z0-9_$.]*#[A-Za-z_$][A-Za-z0-9_$]*:[0-9]{1,7})/gu;
  let rpcFailure;
  for (const match of source.matchAll(rpcFailurePattern)) rpcFailure = match;
  const exceptions = Array.from(
    source.matchAll(
      /\b([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*){0,16}(?:Exception|Error))\s*:/gu,
    ),
    (match) => match[1],
  );
  const exception = [...new Set(exceptions)].slice(-1)[0] ?? "unavailable";
  const frames = [];
  const framePattern =
    /^\s*at\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+)\.([A-Za-z_$][A-Za-z0-9_$]*)\((?:[^()\r\n]*[\\/])?[A-Za-z_$][A-Za-z0-9_$]*\.java:([0-9]{1,7})\)\s*$/gmu;
  for (const match of source.matchAll(framePattern)) {
    if (!match[1].startsWith("io.github.kongweiguang.ja.")) continue;
    frames.push(match[1] + "." + match[2] + ":" + match[3]);
    if (frames.length === 4) break;
  }
  const rpcType = rpcFailure?.[1] ?? "unavailable";
  const rpcCauseType = rpcFailure?.[2] ?? "unavailable";
  const rpcOrigin = rpcFailure?.[3] ?? "unavailable";
  return (
    "rpcType=" +
    rpcType +
    ";rpcCauseType=" +
    rpcCauseType +
    ";rpcOrigin=" +
    rpcOrigin +
    ";exception=" +
    exception +
    ";jaFrames=" +
    (frames.join(",") || "unavailable")
  );
}

/**
 * Describes JA-RPC error fields by fixed names and value classes while reducing messages to
 * known categories, allowing local diagnosis without copying an error message into output.
 */
export function safeRpcErrorShape(error) {
  if (error === null || typeof error !== "object" || Array.isArray(error)) {
    return "rpcErrorFields=unavailable;rpcErrorValueTypes=unavailable;rpcMessageClass=unavailable";
  }
  const own = (name) => Object.hasOwn(error, name);
  const fields = ["code", "message", "data"].filter(own);
  const valueType = (name) => {
    if (!own(name)) return "absent";
    if (name === "code") return Number.isSafeInteger(error.code) ? "integer" : typeof error.code;
    if (name === "message") return typeof error.message;
    if (error[name] === null) return "null";
    if (Array.isArray(error[name])) return "array";
    return typeof error[name];
  };
  const message = typeof error.message === "string" ? error.message : "";
  const messageClass = /SQLITE_CONSTRAINT_CHECK|CHECK constraint failed/iu.test(message)
    ? "sqlite_check_constraint"
    : /SQLITE_CONSTRAINT/iu.test(message)
      ? "sqlite_constraint"
      : /SQLITE_BUSY|database is locked/iu.test(message)
        ? "sqlite_busy"
        : typeof error.message === "string"
          ? "opaque_text"
          : "unavailable";
  return (
    "rpcErrorFields=" +
    (fields.join(",") || "none") +
    ";rpcErrorValueTypes=code:" +
    valueType("code") +
    ",message:" +
    valueType("message") +
    ",data:" +
    valueType("data") +
    ";rpcMessageClass=" +
    messageClass +
    ";rpcUnknownFieldCount=" +
    Object.keys(error).filter((name) => !["code", "message", "data"].includes(name)).length
  );
}

/**
 * 项目要求排查 loopback seed 失败原因；只提取闭集状态码和已知事件计数，避免导出事件正文。
 */
export function safeSeedTurnEvidence(events, turnId) {
  const matching =
    typeof turnId === "string" && Array.isArray(events)
      ? events.filter((frame) => frame?.params?.turnId === turnId)
      : [];
  const terminal = [...matching].reverse().find((frame) => frame?.method === "turn/terminal");
  const state = ["completed", "failed", "cancelled"].includes(terminal?.params?.state)
    ? terminal.params.state
    : "unavailable";
  const errorCode =
    typeof terminal?.params?.errorCode === "string" &&
    /^[A-Z][A-Z0-9_]{1,63}$/u.test(terminal.params.errorCode)
      ? terminal.params.errorCode
      : "unavailable";
  return {
    state,
    errorCode,
    approvalRequested: matching.some((frame) => frame?.method === "approval/requested"),
  };
}

/**
 * 只统计隔离 seed Turn 中与本次合成 callId 相同的已提交 Tool 历史项，不读取其正文或元数据。
 */
export function seedToolResultHistoryCount(history, turnId, callId) {
  if (!Array.isArray(history?.items) || typeof turnId !== "string" || typeof callId !== "string") {
    return "unavailable";
  }
  return history.items.filter(
    (item) => item?.kind === "tool_call" && item.turnId === turnId && item.callId === callId,
  ).length;
}

/**
 * 只允许通过 JA-RPC 对 fixture 预置的唯一缺失文件 read 调用审批，不扩展 Thread 级访问权限。
 */
export function matchesSeedApproval(approvalEvent, history, expected) {
  const approval = approvalEvent?.params ?? approvalEvent;
  if (approval?.method !== undefined && approval.method !== "approval/requested") {
    return false;
  }
  if (
    typeof expected?.threadId !== "string" ||
    typeof expected?.turnId !== "string" ||
    typeof expected?.callId !== "string" ||
    typeof expected?.relativePath !== "string" ||
    history?.threadId !== expected.threadId ||
    !Array.isArray(history?.items) ||
    !Number.isSafeInteger(history?.revision) ||
    approval?.threadId !== expected.threadId ||
    approval?.turnId !== expected.turnId ||
    approval?.threadRevision !== history.revision ||
    typeof approval?.approvalId !== "string" ||
    !/^appr_[A-Za-z0-9_-]{1,96}$/u.test(approval.approvalId) ||
    approval?.callId !== expected.callId ||
    approval?.toolName !== "read"
  ) {
    return false;
  }
  const turnCalls = history.items.filter(
    (item) => item?.kind === "tool_call" && item.turnId === expected.turnId,
  );
  if (turnCalls.length !== 1) return false;
  const call = turnCalls[0];
  const relativePaths = call.presentation?.relativePaths;
  return (
    call.callId === expected.callId &&
    call.toolName === "read" &&
    call.presentation?.kind === "read" &&
    call.presentation?.status === "waiting_approval" &&
    Array.isArray(relativePaths) &&
    relativePaths.length === 1 &&
    relativePaths[0] === expected.relativePath
  );
}

/**
 * Reads one referenced secret from the protected host auth file only for a local credential/set call,
 * then removes parsed values and zeroes file bytes so credentials never enter copied temp files.
 */
export async function withSelectedCredentialSecret(authPath, credentialId, useSecret) {
  let source;
  let auth;
  let secret;
  try {
    const metadata = await lstat(authPath);
    if (
      !metadata.isFile() ||
      metadata.isSymbolicLink() ||
      metadata.size <= 0 ||
      metadata.size > 16 * 1024 * 1024
    ) {
      return { credentialIdPresent: false, secretAvailable: false, result: undefined };
    }
    source = await readFile(authPath);
    if (source.length > 16 * 1024 * 1024) {
      return { credentialIdPresent: false, secretAvailable: false, result: undefined };
    }
    auth = JSON.parse(source.toString("utf8"));
    const credentialIdPresent =
      auth !== null &&
      typeof auth === "object" &&
      !Array.isArray(auth) &&
      typeof credentialId === "string" &&
      Object.hasOwn(auth, credentialId);
    if (!credentialIdPresent) {
      return { credentialIdPresent: false, secretAvailable: false, result: undefined };
    }
    secret = auth[credentialId];
    if (
      typeof secret !== "string" ||
      secret.trim().length === 0 ||
      secret.length > 8192 ||
      typeof useSecret !== "function"
    ) {
      return { credentialIdPresent: true, secretAvailable: false, result: undefined };
    }
    const result = await useSecret(secret);
    return { credentialIdPresent: true, secretAvailable: true, result };
  } finally {
    secret = undefined;
    if (source instanceof Buffer) source.fill(0);
    source = undefined;
    if (auth !== null && typeof auth === "object" && !Array.isArray(auth)) {
      for (const name of Object.keys(auth)) delete auth[name];
    }
    auth = undefined;
  }
}

/**
 * 仅拷贝 config.toml；宿主 Secret 经内存回调写入隔离 auth.json，再用生产 JA-RPC 和隔离 SQLite。
 */
export async function runIsolatedRecovery({ stage, budgetDirectory, mode = "preflight" }) {
  if (mode === "real") {
    requireRealRoundAuthorization(stage);
    if (typeof budgetDirectory !== "string")
      throw new Error("a task temp evidence budget is required");
  } else if (mode !== "preflight") {
    throw new Error("mode must be preflight or real");
  }
  const directories = await createIsolatedDirectories();
  let bridge;
  let session;
  let checkpoint = "copy-config";
  let callId;
  let threadId;
  let seedTurnId;
  let seedHistory;
  let seedToolResultCount = "unavailable";
  let seedApprovalMatch = "not_seen";
  const credentialEvidence = {
    authIdPresent: "unknown",
    initialRpcConfigured: "unknown",
    credentialSetRpcConfigured: "unknown",
    readbackRpcConfigured: "unknown",
    credentialSetAccepted: "unknown",
    userVersionRefreshed: "unknown",
    credentialVersionRefreshed: "unknown",
    credentialVersionStableAfterReplace: "unknown",
    authSecretAvailable: "unknown",
    providerDefaultMatches: "unknown",
    modelDefaultMatches: "unknown",
    providerCredentialMatches: "unknown",
    threadProviderMatches: "unknown",
    threadModelMatches: "unknown",
  };
  try {
    await copyJaInputs(directories.home);
    checkpoint = "java-and-jar";
    const java = resolveJava();
    await assertJava25(java);
    const jar = resolve(
      process.env.JA_RECOVERY_GATEWAY_JAR ??
        join(repoRoot, "app-server", "target", "ja-app-server.jar"),
    );
    const jarInfo = await stat(jar).catch(() => undefined);
    if (jarInfo === undefined || !jarInfo.isFile() || jarInfo.size === 0) {
      throw new Error("current app-server jar is missing");
    }
    await assertHandshakeJarFreshness(jar);
    checkpoint = "app-server-start";
    session = createLoopbackBoundSession({ command: java, jar, directories });
    const initialize = await recoveryInitializeParams();
    checkpoint = "runtime-initialize";
    const initialized = resultOf(
      await session.request("runtime/initialize", initialize),
      "runtime/initialize",
    );
    if (initialized?.runtime?.engine !== "ja-kernel")
      throw new Error("Ja production runtime did not initialize");
    session.notifyInitialized();
    checkpoint = "runtime-ready";
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) =>
        frame.params?.status === "ready" &&
        frame.params?.readyToken === readyToken &&
        frame.params?.generation > 0,
      20_000,
    );
    checkpoint = "configuration-read";
    const configuration = resultOf(
      await session.request("configuration/read", {}),
      "configuration/read",
    );
    const sourceDocument = configuration?.user?.document;
    checkpoint = "profile-selection";
    const profile = selectConfiguredResponsesProfile(sourceDocument);
    const credentialId = profile.provider.credential_id;
    checkpoint = "credential-set";
    const expectedCredentialVersion = configuration?.cas?.credentialVersion;
    if (typeof expectedCredentialVersion !== "string") {
      throw new Error("credential CAS version is unavailable");
    }
    const credentialProvision = await withSelectedCredentialSecret(
      join(homedir(), ".ja", "auth.json"),
      credentialId,
      (secret) =>
        session.request("credential/set", {
          credentialId,
          secret,
          expectedVersion: expectedCredentialVersion,
        }),
    );
    credentialEvidence.authIdPresent = credentialProvision.credentialIdPresent ? "true" : "false";
    credentialEvidence.authSecretAvailable = credentialProvision.secretAvailable ? "true" : "false";
    if (!credentialProvision.credentialIdPresent || !credentialProvision.secretAvailable) {
      throw new Error("selected Provider credential is unavailable in the host auth file");
    }
    const provisionedCredential = resultOf(credentialProvision.result, "credential/set isolated");
    credentialEvidence.credentialSetAccepted =
      provisionedCredential?.accepted === true && provisionedCredential?.configured === true
        ? "true"
        : "false";
    if (credentialEvidence.credentialSetAccepted !== "true") {
      throw new Error("isolated Provider credential was not accepted");
    }
    const initialCredentialStatus = configuration?.credentials?.[credentialId]?.configured;
    credentialEvidence.initialRpcConfigured =
      typeof initialCredentialStatus === "boolean" ? String(initialCredentialStatus) : "unknown";
    checkpoint = "credential-readback";
    const credentialReadback = resultOf(
      await session.request("configuration/read", {}),
      "configuration/read credential",
    );
    const credentialSetStatus = credentialReadback?.credentials?.[credentialId]?.configured;
    credentialEvidence.credentialSetRpcConfigured =
      typeof credentialSetStatus === "boolean" ? String(credentialSetStatus) : "unknown";
    const initialCredentialVersion = configuration?.cas?.credentialVersion;
    const credentialSetVersion = credentialReadback?.cas?.credentialVersion;
    credentialEvidence.credentialVersionRefreshed =
      typeof initialCredentialVersion === "string" && typeof credentialSetVersion === "string"
        ? String(initialCredentialVersion !== credentialSetVersion)
        : "unknown";
    credentialEvidence.providerDefaultMatches =
      sourceDocument?.default_provider_id === profile.providerId ? "true" : "false";
    credentialEvidence.modelDefaultMatches =
      sourceDocument?.default_model_id === profile.modelId ? "true" : "false";
    const promptMarker = "JA_RECOVERY_GATEWAY_SEED_" + randomBytes(8).toString("hex");
    callId = "call_" + randomBytes(8).toString("hex");
    const budget = mode === "real" ? await createExchangeBudget(budgetDirectory, stage) : undefined;
    checkpoint = "bridge-start";
    bridge = await startRecoveryGatewayBridge({
      upstreamBaseUrl: profile.upstream.href,
      stage: mode === "real" ? stage : "baseline",
      budget: budget ?? {
        reserve: async () => {
          throw new Error("preflight cannot reserve real exchanges");
        },
      },
      callId,
      promptMarker,
    });
    session.endpoint = bridge.baseUrl;
    checkpoint = "configuration-replace";
    resultOf(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        document: isolatedProviderDocument(sourceDocument, profile, bridge.baseUrl),
      }),
      "configuration/replace",
    );
    checkpoint = "configuration-readback";
    const installedConfiguration = resultOf(
      await session.request("configuration/read", {}),
      "configuration/read isolated",
    );
    const installedDocument = installedConfiguration?.user?.document;
    const installedProvider = installedDocument?.providers?.find(
      (provider) => provider?.provider_id === profile.providerId,
    );
    credentialEvidence.providerDefaultMatches =
      installedDocument?.default_provider_id === profile.providerId ? "true" : "false";
    credentialEvidence.modelDefaultMatches =
      installedDocument?.default_model_id === profile.modelId ? "true" : "false";
    credentialEvidence.providerCredentialMatches =
      installedProvider?.credential_id === credentialId ? "true" : "false";
    const installedCredentialStatus =
      installedConfiguration?.credentials?.[credentialId]?.configured;
    credentialEvidence.readbackRpcConfigured =
      typeof installedCredentialStatus === "boolean"
        ? String(installedCredentialStatus)
        : "unknown";
    const initialUserVersion = configuration?.cas?.userVersion;
    const installedUserVersion = installedConfiguration?.cas?.userVersion;
    credentialEvidence.userVersionRefreshed =
      typeof initialUserVersion === "string" && typeof installedUserVersion === "string"
        ? String(initialUserVersion !== installedUserVersion)
        : "unknown";
    const installedCredentialVersion = installedConfiguration?.cas?.credentialVersion;
    credentialEvidence.credentialVersionStableAfterReplace =
      typeof credentialSetVersion === "string" && typeof installedCredentialVersion === "string"
        ? String(credentialSetVersion === installedCredentialVersion)
        : "unknown";
    checkpoint = "workspace-open";
    const workspace = resultOf(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Isolated recovery gateway",
      }),
      "workspace/open",
    );
    if (typeof workspace?.workspaceId !== "string" || !workspace.workspaceId.startsWith("ws_")) {
      throw new Error("isolated workspace failed to open");
    }
    checkpoint = "thread-create";
    const thread = resultOf(
      await session.request("thread/create", {
        cwd: directories.workspace,
        title: "Isolated recovery gateway",
        providerId: profile.providerId,
        modelId: profile.modelId,
        reasoningLevel: profile.model.default_reasoning_level ?? "medium",
        accessMode: "approval_required",
        collaborationMode: "default",
      }),
      "thread/create",
    );
    credentialEvidence.threadProviderMatches =
      profile.providerId === installedDocument?.default_provider_id ? "true" : "false";
    credentialEvidence.threadModelMatches =
      profile.modelId === installedDocument?.default_model_id ? "true" : "false";
    if (typeof thread?.threadId !== "string" || !thread.threadId.startsWith("thr_")) {
      throw new Error("isolated Thread failed to create");
    }
    threadId = thread.threadId;
    const seedRelativePath = "ja-recovery-fixture-missing-" + callId + ".txt";
    checkpoint = "seed-turn-start";
    const seed = resultOf(
      await session.request("turn/start", {
        threadId: thread.threadId,
        content: [
          {
            type: "text",
            text:
              promptMarker +
              ". Use exactly one built-in read tool on the missing path " +
              seedRelativePath +
              ". After its expected error, do not retry or call any other tool; then summarize the failure briefly.",
          },
        ],
      }),
      "turn/start seed",
    );
    if (typeof seed?.turnId !== "string" || !seed.turnId.startsWith("turn_")) {
      throw new Error("seed Turn failed to start");
    }
    seedTurnId = seed.turnId;
    checkpoint = "seed-turn-terminal";
    const seedEvent = await session.waitForAnyEvent(
      (frame) =>
        frame.params?.turnId === seed.turnId &&
        ["turn/terminal", "approval/requested"].includes(frame.method),
    );
    if (seedEvent.method === "approval/requested") {
      const approvalHistory = resultOf(
        await session.request("thread/read", { threadId, cursor: null, limit: 200 }),
        "thread/read seed approval",
      );
      seedHistory = approvalHistory;
      const approvalMatches = matchesSeedApproval(seedEvent, approvalHistory, {
        threadId,
        turnId: seed.turnId,
        callId,
        relativePath: seedRelativePath,
      });
      seedApprovalMatch = approvalMatches ? "matched" : "mismatch";
      if (!approvalMatches) {
        await session
          .request("turn/cancel", { threadId, turnId: seed.turnId }, 5_000)
          .catch(() => undefined);
        await session
          .waitForEvent("turn/terminal", (frame) => frame.params?.turnId === seed.turnId, 5_000)
          .catch(() => undefined);
        seedToolResultCount = seedToolResultHistoryCount(approvalHistory, seed.turnId, callId);
        throw new Error("seed approval did not match the fixed missing read");
      }
      const approvalResponse = resultOf(
        await session.request("approval/respond", {
          approvalId: seedEvent.params.approvalId,
          turnId: seed.turnId,
          decision: "approve",
          expectedThreadRevision: approvalHistory.revision,
        }),
        "approval/respond seed",
      );
      if (
        approvalResponse?.accepted !== true ||
        approvalResponse.approvalId !== seedEvent.params.approvalId ||
        approvalResponse.turnId !== seed.turnId ||
        approvalResponse.decision !== "approve" ||
        !Number.isSafeInteger(approvalResponse.threadRevision)
      ) {
        throw new Error("seed approval response was invalid");
      }
      await session.waitForEvent(
        "approval/resolved",
        (frame) =>
          frame.params?.turnId === seed.turnId &&
          frame.params?.approvalId === seedEvent.params.approvalId &&
          frame.params?.decision === "approve",
      );
      const seedTerminal = await session.waitForEvent(
        "turn/terminal",
        (frame) => frame.params?.turnId === seed.turnId,
      );
      if (seedTerminal.params?.state !== "failed") {
        throw new Error("loopback fixture did not stop the approved seed Tool after ToolResult");
      }
      checkpoint = "seed-history";
      seedHistory = resultOf(
        await session.request("thread/read", { threadId, cursor: null, limit: 200 }),
        "thread/read seed",
      );
      const failedCalls =
        seedHistory?.items?.filter(
          (item) =>
            item?.kind === "tool_call" &&
            item.turnId === seed.turnId &&
            item.callId === callId &&
            item.toolName === "read" &&
            item.presentation?.status === "error",
        ) ?? [];
      seedToolResultCount = seedToolResultHistoryCount(seedHistory, seed.turnId, callId);
      if (failedCalls.length !== 1 || seedToolResultCount !== 1) {
        throw new Error("isolated history lacks exactly one failed read ToolResult");
      }
      if (bridge.seedFailureRequests < 1) {
        throw new Error("loopback did not stop the approved seed Turn after ToolResult");
      }
    } else {
      const seedTerminal = seedEvent;
      if (seedTerminal.params?.state !== "failed") {
        throw new Error("loopback fixture did not stop the seeded Turn after ToolResult");
      }
    }
    if (seedApprovalMatch === "not_seen") {
      checkpoint = "seed-history";
      seedHistory = resultOf(
        await session.request("thread/read", { threadId, cursor: null, limit: 200 }),
        "thread/read seed",
      );
      const failedCalls =
        seedHistory?.items?.filter(
          (item) =>
            item?.kind === "tool_call" &&
            item.turnId === seed.turnId &&
            item.toolName === "read" &&
            item.presentation?.status === "error",
        ) ?? [];
      seedToolResultCount = seedToolResultHistoryCount(seedHistory, seed.turnId, callId);
      if (failedCalls.length !== 1 || failedCalls[0].callId !== callId) {
        throw new Error("isolated history lacks exactly one failed read ToolResult");
      }
      if (bridge.seedFailureRequests < 1) throw new Error("loopback did not stop the seed Turn");
    }

    checkpoint = "continuation-start";
    if (mode === "real") bridge.setRealContinuation();
    else bridge.setLocalContinuation();
    const continued = resultOf(
      await session.request("turn/continue", {
        threadId: thread.threadId,
        expectedThreadRevision: seedHistory.revision,
      }),
      "turn/continue",
    );
    if (typeof continued?.turnId !== "string" || !continued.turnId.startsWith("turn_")) {
      throw new Error("continuation Turn failed to start");
    }
    checkpoint = "continuation-terminal";
    const terminalOrApproval = await session.waitForAnyEvent(
      (frame) =>
        frame.params?.turnId === continued.turnId &&
        ["turn/terminal", "approval/requested"].includes(frame.method),
    );
    if (terminalOrApproval.method === "approval/requested") {
      await session
        .request("turn/cancel", {
          threadId: thread.threadId,
          turnId: continued.turnId,
        })
        .catch(() => undefined);
      throw new Error("continuation requested approval and was cancelled");
    }
    if (mode !== "real" && terminalOrApproval.params?.state !== "completed") {
      throw new Error("loopback continuation did not complete");
    }
    return {
      stage: mode === "real" ? stage : "preflight",
      seededFailedTool: true,
      continuationStatus: terminalOrApproval.params?.state ?? "unknown",
      fixtureProviderRequests: bridge.loopbackProviderRequestCount,
      fixtureRequestClass: bridge.fixtureRequestClass,
      fixtureCallIssued: bridge.fixtureCallIssued,
      seedToolResultHistoryCount: seedToolResultCount,
      seedTerminalState: safeSeedTurnEvidence(session.events, seedTurnId).state,
      seedTerminalErrorCode: safeSeedTurnEvidence(session.events, seedTurnId).errorCode,
      seedApprovalMatch,
      externalHttpExchanges: bridge.externalExchangeCount,
      gateway: bridge.gatewayEvidence,
    };
  } catch (error) {
    if (
      mode === "preflight" &&
      session !== undefined &&
      threadId !== undefined &&
      seedTurnId !== undefined &&
      seedToolResultCount === "unavailable"
    ) {
      const historyResponse = await session
        .request("thread/read", { threadId, cursor: null, limit: 200 }, 5_000)
        .catch(() => undefined);
      const history = historyResponse?.result;
      seedToolResultCount = seedToolResultHistoryCount(history, seedTurnId, callId);
    }
    const preflightStage = mode === "preflight" ? " at " + checkpoint : "";
    const rpcCode =
      mode === "preflight" && typeof error?.message === "string"
        ? /; rpcCode=(-?[0-9]{1,10})(?:;|$)/u.exec(error.message)?.[1]
        : undefined;
    const rpcErrorShape =
      mode === "preflight" && typeof error?.message === "string"
        ? /; (rpcErrorFields=(?:none|code(?:,message)?(?:,data)?|message(?:,data)?|data);rpcErrorValueTypes=code:(?:integer|number|string|boolean|object|undefined|absent),message:(?:string|number|boolean|object|undefined|absent),data:(?:absent|null|string|number|boolean|object|array);rpcMessageClass=(?:sqlite_check_constraint|sqlite_constraint|sqlite_busy|opaque_text|unavailable);rpcUnknownFieldCount=[0-9]{1,3})$/u.exec(
            error.message,
          )?.[1]
        : undefined;
    const javaProcessExit =
      session === undefined
        ? "not_started"
        : session.exited
          ? "exit-" +
            (Number.isInteger(session.exitResult?.code) ? session.exitResult.code : "unknown")
          : "running";
    const preflightEvidence =
      mode !== "preflight"
        ? ""
        : "; loopbackProviderRequests=" +
          (bridge?.loopbackProviderRequestCount ?? 0) +
          "; seedFailureRequests=" +
          (bridge?.seedFailureRequests ?? 0) +
          "; fixtureRequestClass=" +
          (bridge?.fixtureRequestClass ?? "unavailable") +
          "; fixtureCallIssued=" +
          (bridge?.fixtureCallIssued ?? false) +
          "; seedToolResultHistoryCount=" +
          seedToolResultCount +
          "; seedTerminalState=" +
          safeSeedTurnEvidence(session?.events, seedTurnId).state +
          "; seedTerminalErrorCode=" +
          safeSeedTurnEvidence(session?.events, seedTurnId).errorCode +
          "; seedApprovalRequested=" +
          safeSeedTurnEvidence(session?.events, seedTurnId).approvalRequested +
          "; seedApprovalMatch=" +
          seedApprovalMatch +
          "; externalHttpExchanges=" +
          (bridge?.externalExchangeCount ?? 0) +
          "; credentialEvidence=authIdPresent=" +
          credentialEvidence.authIdPresent +
          ",authSecretAvailable=" +
          credentialEvidence.authSecretAvailable +
          ",initialRpcConfigured=" +
          credentialEvidence.initialRpcConfigured +
          ",credentialSetRpcConfigured=" +
          credentialEvidence.credentialSetRpcConfigured +
          ",credentialSetAccepted=" +
          credentialEvidence.credentialSetAccepted +
          ",readbackRpcConfigured=" +
          credentialEvidence.readbackRpcConfigured +
          ",userVersionRefreshed=" +
          credentialEvidence.userVersionRefreshed +
          ",credentialVersionRefreshed=" +
          credentialEvidence.credentialVersionRefreshed +
          ",credentialVersionStableAfterReplace=" +
          credentialEvidence.credentialVersionStableAfterReplace +
          ",providerDefaultMatches=" +
          credentialEvidence.providerDefaultMatches +
          ",modelDefaultMatches=" +
          credentialEvidence.modelDefaultMatches +
          ",providerCredentialMatches=" +
          credentialEvidence.providerCredentialMatches +
          ",threadProviderMatches=" +
          credentialEvidence.threadProviderMatches +
          ",threadModelMatches=" +
          credentialEvidence.threadModelMatches +
          "; javaProcess=" +
          javaProcessExit +
          "; javaDiagnostic=" +
          safePreflightDiagnostics(session?.stderr);
    if (mode === "real") {
      const gateway = bridge?.gatewayEvidence;
      const gatewayStatus = allowedStatuses.has(gateway?.status) ? gateway.status : "unavailable";
      const gatewayCode = allowedErrorCodes.has(gateway?.code) ? gateway.code : "unavailable";
      const gatewayParam =
        typeof gateway?.param === "string" && paramPathPattern.test(gateway.param)
          ? gateway.param
          : "unavailable";
      const safeCheckpoint = allowedCheckpoints.has(checkpoint)
        ? checkpoint
        : "continuation-terminal";
      throw new Error(
        "isolated recovery gateway check failed stage=" +
          stage +
          " checkpoint=" +
          safeCheckpoint +
          " externalExchangeCount=" +
          (bridge?.externalExchangeCount ?? 0) +
          " gatewayStatus=" +
          gatewayStatus +
          " gatewayCode=" +
          gatewayCode +
          " gatewayParam=" +
          gatewayParam +
          "; sensitive runtime output was suppressed",
      );
    }
    throw new Error(
      "isolated recovery gateway check failed" +
        preflightStage +
        (rpcCode === undefined ? "" : "; rpcCode=" + rpcCode) +
        (rpcErrorShape === undefined ? "" : "; " + rpcErrorShape) +
        preflightEvidence +
        "; sensitive runtime output was suppressed",
    );
  } finally {
    await bridge?.close().catch(() => undefined);
    await session?.forceClose().catch(() => undefined);
    await removeOwnedDirectories(directories.root).catch(() => {
      throw new Error("isolated recovery files could not be removed");
    });
  }
}

/**
 * 仅服务 title、seed Tool call、seed 停止响应或本地续答；未知请求一律返回本地 400。
 */
async function respondFromFixture(
  payload,
  response,
  phase,
  callId,
  recordClassification,
  issueCall,
  recordSeedFailure,
) {
  const input = Array.isArray(payload?.input) ? payload.input : [];
  const toolOutputs = input.filter((item) => item?.type === "function_call_output");
  const serialized = JSON.stringify(input);
  if (serialized.includes("<user_request>") && serialized.includes("<assistant_reply>")) {
    recordClassification("title");
    sendSse(response, textResponse("Recovery check", "title_" + Date.now()));
    return;
  }
  if (phase === "seed" && toolOutputs.length > 0) {
    recordClassification("seedToolResult");
    recordSeedFailure();
    sendJson(response, 400, { error: { code: "fixture_seed_stop", param: "input" } });
    return;
  }
  if (phase === "local-continuation" && toolOutputs.some((item) => item?.call_id === callId)) {
    recordClassification("localContinuation");
    sendSse(response, textResponse("JA_RECOVERY_LOCAL_CONTINUE_OK", "local_" + Date.now()));
    return;
  }
  if (phase === "seed" && toolOutputs.length === 0 && issueCall()) {
    recordClassification("seedToolCall");
    const missingPath = "ja-recovery-fixture-missing-" + callId + ".txt";
    sendSse(response, functionCallResponse(callId, missingPath));
    return;
  }
  recordClassification("unexpected");
  sendJson(response, 400, { error: { code: "fixture_request_unexpected" } });
}

/**
 * 构造真实 Responses function_call 输出项；参数只引用临时 workspace 中不存在的文件。
 */
function functionCallResponse(callId, path) {
  const itemId = "item_" + callId.slice(5);
  const item = {
    id: itemId,
    type: "function_call",
    call_id: callId,
    name: "read",
    arguments: JSON.stringify({ path }),
  };
  return responseSse("resp_" + itemId, [item]);
}

/**
 * 构造一条无 Tool 的 Responses 答复，供 title 与无网络 preflight 的续答使用。
 */
function textResponse(text, id) {
  const item = {
    id: "msg_" + id,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return responseSse("resp_" + id, [item], text);
}

/**
 * 将最小 SSE envelope 交给生产 OpenAI Responses adapter，测试仍走 Java 真实解码器。
 */
function responseSse(responseId, output, text) {
  const envelope = {
    id: responseId,
    object: "response",
    created_at: 0,
    model: "isolated-recovery-fixture",
    status: "completed",
    output,
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    usage: {
      input_tokens: 1,
      output_tokens: 1,
      total_tokens: 2,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens_details: { reasoning_tokens: 0 },
    },
  };
  const frames = [
    [
      "response.created",
      { response: { ...envelope, status: "in_progress", output: [], usage: undefined } },
    ],
  ];
  output.forEach((item, index) => {
    frames.push([
      "response.output_item.added",
      {
        output_index: index,
        item: item.type === "function_call" ? { ...item, arguments: "" } : item,
      },
    ]);
    if (item.type === "function_call") {
      frames.push([
        "response.function_call_arguments.done",
        {
          output_index: index,
          item_id: item.id,
          arguments: item.arguments,
        },
      ]);
    } else if (text !== undefined) {
      frames.push([
        "response.output_text.delta",
        {
          output_index: index,
          item_id: item.id,
          content_index: 0,
          delta: text,
          logprobs: [],
        },
      ]);
      frames.push([
        "response.output_text.done",
        {
          output_index: index,
          item_id: item.id,
          content_index: 0,
          text,
        },
      ]);
    }
    frames.push(["response.output_item.done", { output_index: index, item }]);
  });
  frames.push(["response.completed", { response: envelope }]);
  return frames
    .map(
      (frame, sequence) =>
        "event: " +
        frame[0] +
        "\ndata: " +
        JSON.stringify({ type: frame[0], sequence_number: sequence, ...frame[1] }) +
        "\n\n",
    )
    .join("");
}

/**
 * Sends one direct HTTP(S) request. Native Node clients do not follow redirects or retry;
 * the already-consumed slot therefore bounds raw HTTP request messages at one per stage.
 */
async function forwardExactlyOnce({ request, response, upstreamBase, outboundBody, record }) {
  const remote = configuredResponsesEndpoint(upstreamBase.href);
  const transport = remote.protocol === "https:" ? httpsRequest : httpRequest;
  const headers = { ...request.headers };
  for (const name of [
    "host",
    "connection",
    "content-length",
    "transfer-encoding",
    "keep-alive",
    "proxy-authorization",
  ])
    delete headers[name];
  headers["content-length"] = String(outboundBody.length);
  await new Promise((resolveForward) => {
    const upstream = transport(
      remote,
      {
        method: "POST",
        headers,
        timeout: 120_000,
      },
      (upstreamResponse) => {
        const status = upstreamResponse.statusCode ?? 0;
        const captureErrorBody = status >= 400 && status < 600;
        const captured = [];
        let capturedBytes = 0;
        upstreamResponse.on("data", (chunk) => {
          if (!captureErrorBody || capturedBytes >= maxErrorBytes) return;
          const bounded = Buffer.from(chunk).subarray(0, maxErrorBytes - capturedBytes);
          captured.push(bounded);
          capturedBytes += bounded.length;
        });
        const forwardedHeaders = {};
        for (const name of ["content-type", "cache-control", "retry-after"]) {
          const value = upstreamResponse.headers[name];
          if (typeof value === "string") forwardedHeaders[name] = value;
        }
        response.writeHead(status, forwardedHeaders);
        upstreamResponse.pipe(response);
        upstreamResponse.once("end", () => {
          record(
            safeGatewayFailure(
              status,
              captureErrorBody ? Buffer.concat(captured) : Buffer.alloc(0),
            ),
          );
          resolveForward();
        });
        upstreamResponse.once("error", () => {
          record(safeGatewayFailure(0, Buffer.alloc(0)));
          response.destroy();
          resolveForward();
        });
      },
    );
    upstream.once("error", () => {
      record(safeGatewayFailure(0, Buffer.alloc(0)));
      if (!response.headersSent)
        sendJson(response, 502, { error: { code: "JA_RECOVERY_UPSTREAM_UNAVAILABLE" } });
      else response.destroy();
      resolveForward();
    });
    upstream.once("timeout", () => upstream.destroy(new Error("request timeout")));
    upstream.end(outboundBody);
  });
}

/**
 * 只复制不含 Secret 的配置文档；auth.json 仅在内存中选取一个 credential 后通过安全 RPC 写入。
 */
async function copyJaInputs(isolatedHome) {
  const sourceHome = join(homedir(), ".ja");
  await copyRegularFile(
    join(sourceHome, "config.toml"),
    join(isolatedHome, "config.toml"),
    2 * 1024 * 1024,
  );
}

/**
 * 通过 lstat 拒绝链接并限制输入尺寸，避免从用户目录复制出隔离目录的边界。
 */
async function copyRegularFile(source, destination, maximumBytes) {
  const sourceInfo = await lstat(source).catch(() => undefined);
  if (
    sourceInfo === undefined ||
    !sourceInfo.isFile() ||
    sourceInfo.isSymbolicLink() ||
    sourceInfo.size > maximumBytes
  )
    throw new Error("required Ja input file is not a bounded ordinary file");
  if ((await lstat(destination).catch(() => undefined)) !== undefined) {
    throw new Error("isolated Ja input destination is not empty");
  }
  await copyFile(source, destination, fsConstants.COPYFILE_EXCL);
}

/**
 * 清理仅由现有 smoke helper 创建的随机 temp 子目录，并复核物理父目录与命名前缀。
 */
async function removeOwnedDirectories(root) {
  const physicalTemp = await realpath(tmpdir());
  const actual = await realpath(resolve(root));
  const leaf = actual.split(/[\\/]/u).at(-1);
  if (dirname(actual) !== physicalTemp || !leaf?.startsWith("ja-real-provider-")) {
    throw new Error("refusing to remove a non-owned isolation directory");
  }
  await rm(actual, { recursive: true, force: false });
}

/**
 * 按最大帧读取 loopback body；超过 4 MiB 时不解析、不转发、不写入任何诊断。
 */
async function readBoundedBody(request) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request) {
    bytes += chunk.length;
    if (bytes > maxRequestBytes) throw new Error("request exceeded the fixture limit");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

/**
 * 回送本地 JSON 错误，不反射请求内容或底层异常文本。
 */
function sendJson(response, status, payload) {
  if (response.headersSent) return response.destroy();
  response.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  response.end(JSON.stringify(payload));
}

/**
 * 回送 fixture SSE，确保 Java 走生产 HTTP 与 Responses 解码路径。
 */
function sendSse(response, body) {
  response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-store" });
  response.end(body);
}

/**
 * 将 budget ledger 限定在真实 temp 根之下，并拒绝 temp 根本身。
 */
function isWithin(parent, child) {
  const rel = relative(parent, child);
  return (
    rel !== "" &&
    rel !== ".." &&
    !rel.startsWith(".." + (process.platform === "win32" ? "\\" : "/")) &&
    !isAbsolute(rel)
  );
}

/**
 * 先独占创建再落盘；写入失败时仍保留已创建槽，避免不确定请求后重用额度。
 */
async function reserveSlot(path, stage) {
  let handle;
  try {
    handle = await open(path, "wx", 0o600);
    await handle.writeFile(JSON.stringify({ version: 1, stage }));
    await handle.sync();
  } catch {
    throw new Error("real HTTP exchange slot could not be reserved");
  } finally {
    await handle?.close().catch(() => undefined);
  }
}

/**
 * 验证先前 slot 的闭合格式；损坏记录不能被解释成可用预算。
 */
async function readSlot(path) {
  try {
    const value = JSON.parse(await readFile(path, "utf8"));
    if (value?.version !== 1 || !allowedStages.has(value.stage)) throw new Error();
    return value;
  } catch {
    throw new Error("existing real HTTP exchange slot is invalid");
  }
}

/**
 * 只把 ENOENT 当作槽位不存在，其他文件系统状态均失败关闭。
 */
async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw new Error("exchange budget state could not be inspected");
  }
}

/**
 * 选取 Ja 任务显式提供的 Java 路径，然后才回退到 JAVA_HOME 或系统 PATH。
 */
function resolveJava() {
  if (process.env.JA_TEST_JAVA) return process.env.JA_TEST_JAVA;
  if (process.env.JAVA_HOME) {
    return join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java");
  }
  return process.platform === "win32" ? "java.exe" : "java";
}

/**
 * 严格要求 Java 25；版本横幅只留在内存中，不进入 stdout 或 stderr。
 */
async function assertJava25(command) {
  let output = "";
  try {
    const result = await execFileAsync(command, ["-version"], {
      env: sanitizedJavaEnvironment(),
      windowsHide: true,
      maxBuffer: 64 * 1024,
    });
    output = (result.stdout ?? "") + "\n" + (result.stderr ?? "");
  } catch (error) {
    output = (error?.stdout ?? "") + "\n" + (error?.stderr ?? "");
    if (output.trim() === "") throw new Error("Java 25 could not be started");
  }
  const match = output.match(/\bversion\s+"?(\d+)/iu) ?? output.match(/\bopenjdk\s+(\d+)/iu);
  if (match === null || Number(match[1]) !== 25) throw new Error("app-server requires Java 25");
}

/**
 * Starts the sidecar with proxy auto-detection and inherited JVM option injection disabled;
 * the helper snapshots process.env synchronously, so values are restored before other work resumes.
 */
function createLoopbackBoundSession({ command, jar, directories }) {
  const priorValues = new Map();
  for (const name of egressOverrideEnvironmentNames) {
    if (process.env[name] !== undefined) {
      priorValues.set(name, process.env[name]);
      delete process.env[name];
    }
  }
  try {
    return new JsonlSession({
      command,
      prefixArgs: [
        "-Djava.net.useSystemProxies=false",
        "-Dhttp.proxyHost=",
        "-Dhttp.proxyPort=0",
        "-Dhttp.nonProxyHosts=127.0.0.1|localhost",
        "-Dhttps.proxyHost=",
        "-Dhttps.proxyPort=0",
        "-Dhttps.nonProxyHosts=127.0.0.1|localhost",
        "-jar",
        jar,
      ],
      directories,
      apiKey: "",
      endpoint: "",
    });
  } finally {
    for (const [name, value] of priorValues) process.env[name] = value;
  }
}

/**
 * JA-RPC 错误只折叠成固定调用标签，避免 Provider message 或内部路径外泄。
 */
function resultOf(frame, label) {
  if (
    frame === null ||
    typeof frame !== "object" ||
    frame.error !== undefined ||
    frame.result === undefined
  ) {
    const code = Number.isSafeInteger(frame?.error?.code) ? "; rpcCode=" + frame.error.code : "";
    const errorShape = frame?.error === undefined ? "" : "; " + safeRpcErrorShape(frame.error);
    throw new Error(label + " failed" + code + errorShape);
  }
  return frame.result;
}

/**
 * 解析只支持 preflight、baseline、fixed 三种入口；真实轮次需要单轮 go 和共享预算目录。
 */
async function main() {
  const args = process.argv.slice(2);
  const stage = cliValue(args, "stage") ?? "preflight";
  const mode = stage === "preflight" ? "preflight" : "real";
  if (mode === "real") requireRealRoundAuthorization(stage);
  const budgetDirectory = cliValue(args, "budget-dir");
  if (mode === "real" && budgetDirectory === undefined) {
    throw new Error("--budget-dir in this task's temp evidence root is required");
  }
  const result = await runIsolatedRecovery({ stage, budgetDirectory, mode });
  process.stdout.write(JSON.stringify(result) + "\n");
}

/**
 * 读取 --name=value 参数，不把其他命令行内容复制到诊断。
 */
function cliValue(args, name) {
  const prefix = "--" + name + "=";
  const item = args.find((value) => value.startsWith(prefix));
  return item === undefined ? undefined : item.slice(prefix.length);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    const safePreflightFailure =
      typeof error?.message === "string" &&
      /^isolated recovery gateway check failed at (?:copy-config|java-and-jar|app-server-start|runtime-initialize|runtime-ready|configuration-read|profile-selection|credential-set|credential-readback|bridge-start|configuration-replace|configuration-readback|workspace-open|thread-create|seed-turn-start|seed-turn-terminal|seed-history|continuation-start|continuation-terminal)(?:; rpcCode=-?[0-9]{1,10})?(?:; rpcErrorFields=(?:none|code(?:,message)?(?:,data)?|message(?:,data)?|data);rpcErrorValueTypes=code:(?:integer|number|string|boolean|object|undefined|absent),message:(?:string|number|boolean|object|undefined|absent),data:(?:absent|null|string|number|boolean|object|array);rpcMessageClass=(?:sqlite_check_constraint|sqlite_constraint|sqlite_busy|opaque_text|unavailable);rpcUnknownFieldCount=[0-9]{1,3})?; loopbackProviderRequests=[0-9]{1,10}; seedFailureRequests=[0-9]{1,10}; fixtureRequestClass=(?:title|seedToolCall|seedToolResult|localContinuation|unexpected|unavailable); fixtureCallIssued=(?:true|false); seedToolResultHistoryCount=(?:[0-9]{1,5}|unavailable); seedTerminalState=(?:completed|failed|cancelled|unavailable); seedTerminalErrorCode=(?:[A-Z][A-Z0-9_]{1,63}|unavailable); seedApprovalRequested=(?:true|false); seedApprovalMatch=(?:matched|mismatch|not_seen); externalHttpExchanges=[0-9]{1,10}; credentialEvidence=authIdPresent=(?:true|false|unknown),authSecretAvailable=(?:true|false|unknown),initialRpcConfigured=(?:true|false|unknown),credentialSetRpcConfigured=(?:true|false|unknown),credentialSetAccepted=(?:true|false|unknown),readbackRpcConfigured=(?:true|false|unknown),userVersionRefreshed=(?:true|false|unknown),credentialVersionRefreshed=(?:true|false|unknown),credentialVersionStableAfterReplace=(?:true|false|unknown),providerDefaultMatches=(?:true|false|unknown),modelDefaultMatches=(?:true|false|unknown),providerCredentialMatches=(?:true|false|unknown),threadProviderMatches=(?:true|false|unknown),threadModelMatches=(?:true|false|unknown); javaProcess=(?:not_started|running|exit-(?:unknown|-?[0-9]{1,3})); javaDiagnostic=rpcType=(?:[A-Za-z0-9_$.]{1,256}|unavailable);rpcCauseType=(?:[A-Za-z0-9_$.]{1,256}|unavailable);rpcOrigin=(?:[A-Za-z0-9_$.#:-]{1,512}|unavailable);exception=(?:[A-Za-z0-9_$.]{1,256}|unavailable);jaFrames=(?:[A-Za-z0-9_$.:,]{1,1200}|unavailable); sensitive runtime output was suppressed$/u.test(
        error.message,
      );
    const safeRealFailure =
      typeof error?.message === "string" &&
      /^isolated recovery gateway check failed stage=(?:baseline|fixed) checkpoint=(?:copy-config|java-and-jar|app-server-start|runtime-initialize|runtime-ready|configuration-read|profile-selection|credential-set|credential-readback|bridge-start|configuration-replace|configuration-readback|workspace-open|thread-create|seed-turn-start|seed-turn-terminal|seed-history|continuation-start|continuation-terminal) externalExchangeCount=[0-9]{1,10} gatewayStatus=(?:200|201|202|400|401|403|404|408|409|422|429|500|502|503|504|unavailable) gatewayCode=(?:invalid_value|invalid_request_error|server_error|bad_request|unsupported_value|invalid_api_key|unavailable) gatewayParam=(?:[A-Za-z][A-Za-z0-9_]*(?:(?:\.[A-Za-z][A-Za-z0-9_]*)|(?:\[[0-9]{1,6}\])){0,12}|unavailable); sensitive runtime output was suppressed$/u.test(
        error.message,
      );
    const message =
      typeof error?.message === "string" &&
      (/^JA_ISOLATED_RECOVERY_GATEWAY_GO=|^stage must /u.test(error.message) ||
        safePreflightFailure ||
        safeRealFailure)
        ? error.message
        : "isolated recovery gateway stopped before safe completion";
    process.stderr.write(message + "\n");
    process.exitCode = 1;
  });
}
