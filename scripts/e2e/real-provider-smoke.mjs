// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Production-sidecar smoke test for an explicitly authorized loopback endpoint
 * implementing Anthropic Messages or OpenAI Responses. The API key is accepted only through the parent
 * environment and is removed from the Java child's environment; it reaches
 * the sidecar solely inside the v2 credential/set request on stdin.
 */

import { execFile, spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { mkdir, mkdtemp, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { createInterface } from "node:readline";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDirectory, "..", "..");
const errorCatalog = JSON.parse(readFileSync(
  join(repoRoot, "contracts", "ja-rpc", "v2", "error-catalog.json"), "utf8",
));
const errorCategories = new Set(errorCatalog.categories);
const errorsByNumericCode = new Map(errorCatalog.errors.map((entry) => [entry.code, entry]));
const errorIdPattern = /^err_[0-9a-f]{32}$/u;
const temporaryPrefix = "ja-real-provider-";
const readyToken = "0123456789abcdef0123456789abcdef";
const providerId = "provider_real_provider_smoke";
const modelId = "model_real_provider_smoke";
const credentialId = "cred_real_provider_smoke";
const requestTimeoutMs = 120_000;
const exitTimeoutMs = 20_000;
const methods = [
  "runtime/initialize", "runtime/health", "runtime/shutdown", "workspace/open", "workspace/open-general", "workspace/list",
  "workspace/set-trust", "workspace/unregister", "thread/create", "thread/list",
  "thread/read", "thread/archive", "thread/delete", "turn/start", "turn/cancel", "turn/steer", "turn/follow-up",
  "approval/respond", "configuration/read", "configuration/patch", "configuration/replace",
  "configuration/reset", "credential/set", "credential/delete", "skill/list", "mcp/list",
  "mcp/test", "mcp/list-tools",
];
const events = [
  "runtime/status-changed", "turn/state-changed", "assistant/model-step-committed",
  "assistant/text-delta", "assistant/reasoning-summary-delta", "tool/batch-committed",
  "approval/requested", "approval/resolved", "context/compacted", "workspace/dirty",
  "turn/terminal", "configuration/changed",
];

/** Reads one mandatory environment value without ever including its value in an error. */
export function requiredEnvironment(name) {
  const value = process.env[name];
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${name} is required`);
  }
  return value;
}

/** Rejects ASCII controls without placing control escapes in a diagnostic-facing regex. */
function containsAsciiControl(value) {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && (codePoint <= 0x1f || codePoint === 0x7f);
  });
}

/**
 * Restricts this secret-bearing smoke test to a loopback HTTP endpoint. This
 * prevents an inherited typo from sending the explicitly supplied key to a
 * remote host while still accepting localhost and both IP loopback forms.
 */
export function validatedLoopbackBaseUrl(value, variableName = "provider base URL") {
  const url = new URL(value);
  const loopbackHosts = new Set(["localhost", "127.0.0.1", "[::1]", "::1"]);
  if (url.protocol !== "http:" || !loopbackHosts.has(url.hostname)
      || url.username !== "" || url.password !== "" || url.search !== "" || url.hash !== "") {
    throw new Error(`${variableName} must be a credential-free loopback HTTP URL`);
  }
  return url.toString().replace(/\/+$/, "");
}

/** Resolves the exact freshly built sidecar artifact and rejects a missing or empty jar. */
async function resolveJar() {
  const jar = resolve(process.env.JA_REAL_PROVIDER_JAR
    ?? join(repoRoot, "app-server", "target", "ja-app-server.jar"));
  const metadata = await stat(jar);
  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error("the production sidecar jar is missing or empty");
  }
  return jar;
}

/** Selects the runner-propagated Java executable before any ordinary PATH fallback. */
function resolveJava() {
  if (process.env.JA_TEST_JAVA) return process.env.JA_TEST_JAVA;
  if (process.env.JA_REAL_PROVIDER_JAVA) {
    return process.env.JA_REAL_PROVIDER_JAVA;
  }
  if (process.env.JAVA_HOME) {
    return join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java");
  }
  return process.platform === "win32" ? "java.exe" : "java";
}

/** Proves the real JAR path uses Java 25 without persisting the vendor version banner. */
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
  if (match === null || Number(match[1]) !== 25) {
    throw new Error("real provider JAR execution requires Java major version 25");
  }
}

/** Creates four distinct Unicode roots so production argv exercises canonical UTF-8 ownership. */
export async function createIsolatedDirectories() {
  const root = await mkdtemp(join(tmpdir(), temporaryPrefix));
  const home = join(root, "home-家");
  const data = join(root, "data-数据");
  const run = join(root, "run-运行");
  const logs = join(root, "log-日志");
  const workspace = join(root, "workspace-工作区");
  await Promise.all([home, data, run, logs, workspace].map((path) => mkdir(path)));
  return { root, home, data, run, logs, workspace };
}

/**
 * Uses the actual Ja home requested by an operator and keeps configuration,
 * credential state, SQLite history, and logs after the smoke completes.
 */
async function createPersistentDirectories() {
  const home = join(homedir(), ".ja");
  const data = join(home, "data");
  const run = join(home, "run");
  const logs = join(home, "logs");
  await Promise.all([home, data, run, logs].map((path) => mkdir(path, { recursive: true })));
  return { root: home, home, data, run, logs, workspace: repoRoot };
}

/**
 * Removes only the exact mkdtemp child created by this script. The resolved
 * parent/prefix checks make recursive cleanup fail closed if ownership drifts.
 */
async function cleanupIsolatedDirectories(root) {
  const target = resolve(root);
  if (dirname(target) !== resolve(tmpdir()) || !target.split(/[\\/]/).at(-1)?.startsWith(temporaryPrefix)) {
    throw new Error("refusing to clean a non-owned smoke directory");
  }
  await rm(target, { recursive: true, force: false });
}

/** Encodes the canonical data path with the same unpadded Base64URL contract as Rust. */
export function encodedDirectory(directory) {
  return Buffer.from(resolve(directory), "utf8").toString("base64url");
}

/** Replaces every secret-bearing value before a diagnostic can reach stderr. */
function redact(value, secrets) {
  let output = String(value ?? "");
  for (const secret of secrets) {
    if (secret) {
      output = output.replaceAll(secret, "<redacted>");
    }
  }
  return output;
}

/** Extracts only bounded exception classes plus Ja-owned provider codes/details from redacted stderr. */
function stderrDiagnosticSignals(value) {
  const signals = new Set();
  const providerPattern = /provider_failure_code=([A-Z][A-Z0-9_]{1,63}) provider_failure_detail=([A-Za-z0-9 _.-]{1,160}) semantic_accepted=/gu;
  for (const match of String(value ?? "").matchAll(providerPattern)) {
    signals.add(`${match[1]}:${match[2].replaceAll(" ", "_")}`);
    if (signals.size >= 24) return [...signals].join(",");
  }
  const pattern = /\b(?:[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error)|[A-Z][A-Z0-9_]{2,})\b/gu;
  for (const match of String(value ?? "").matchAll(pattern)) {
    const signal = match[0];
    if (signal.length <= 96 && !signal.startsWith("JA_REAL_PROVIDER")) signals.add(signal);
    if (signals.size >= 24) break;
  }
  return [...signals].join(",");
}

/** Admits only a path-free, body-free terminal summary suitable for live smoke diagnostics. */
function safeTerminalMessage(value) {
  if (typeof value !== "string" || value.length === 0 || value.length > 256) return "NO_SAFE_MESSAGE";
  return /^[A-Za-z0-9 _-]+$/u.test(value) ? value : "UNSAFE_ERROR_MESSAGE";
}

/** Returns only the bounded JA-RPC method sequence for one Turn, without retaining event payloads. */
function turnEventMethods(events, turnId) {
  return events.filter((frame) => frame?.params?.turnId === turnId && typeof frame.method === "string")
    .slice(-24).map((frame) => frame.method).join(",");
}

/** Returns only the bounded durable item-kind sequence, never message or Tool payload data. */
function durableItemKinds(history) {
  if (!Array.isArray(history?.items)) return "unavailable";
  return history.items.slice(-24).map((item) =>
    typeof item?.kind === "string" && /^[a-z_]{1,48}$/u.test(item.kind) ? item.kind : "invalid").join(",");
}

/** Scans only smoke-owned ordinary files under a fixed byte budget for a persisted credential. */
async function assertSecretAbsentFromOwnedFiles(root, secret) {
  const entries = await readdir(root, { recursive: true, withFileTypes: true });
  let scannedBytes = 0;
  const needle = Buffer.from(secret, "utf8");
  for (const entry of entries) {
    if (!entry.isFile()) continue;
    const path = join(entry.parentPath, entry.name);
    const metadata = await stat(path);
    scannedBytes += metadata.size;
    if (scannedBytes > 128 * 1024 * 1024) {
      throw new Error("smoke-owned persistence exceeded the credential scan budget");
    }
    if ((await readFile(path)).includes(needle)) {
      throw new Error("provider credential escaped into smoke-owned persistence");
    }
  }
}

/**
 * Records only JSON paths whose values have the handshake token's 32-hex
 * shape; values themselves never enter diagnostics, and the legal readyToken
 * field is excluded so production events can expose false-positive guards.
 */
function tokenShapedPaths(value, path = "$", output = []) {
  if (Array.isArray(value)) {
    value.forEach((child, index) => tokenShapedPaths(child, `${path}[${index}]`, output));
    return output;
  }
  if (value !== null && typeof value === "object") {
    for (const [key, child] of Object.entries(value)) {
      if (key !== "readyToken") tokenShapedPaths(child, `${path}.${key}`, output);
    }
    return output;
  }
  if (typeof value === "string" && /^[0-9a-fA-F]{32}$/u.test(value)) output.push(path);
  return output;
}

/** Creates a bounded promise without introducing arbitrary stabilization sleeps. */
function withTimeout(promise, timeoutMs, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} timed out`)), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/**
 * 按冻结目录验证错误字段和元组，只返回可安全进入诊断的稳定 errorCode。
 * 严格字段集合会让旧键、额外细节和不合法 retryAfterMs 直接失败，避免 smoke 掩盖协议漂移。
 */
export function requireRpcErrorCode(error) {
  const data = error?.data;
  const hasRetryAfter = data !== null && typeof data === "object"
    && Object.hasOwn(data, "retryAfterMs");
  const expectedErrorKeys = ["code", "data", "message"];
  const expectedDataKeys = hasRetryAfter
    ? ["category", "errorCode", "errorId", "retryAfterMs", "retryable"]
    : ["category", "errorCode", "errorId", "retryable"];
  const catalogEntry = Number.isSafeInteger(error?.code) ? errorsByNumericCode.get(error.code) : undefined;
  const validRetryAfter = !hasRetryAfter || (data.retryable === true
    && Number.isSafeInteger(data.retryAfterMs) && data.retryAfterMs >= 1 && data.retryAfterMs <= 3_600_000);
  if (data === null || typeof data !== "object" || Array.isArray(data)
      || JSON.stringify(Object.keys(error).sort()) !== JSON.stringify(expectedErrorKeys)
      || JSON.stringify(Object.keys(data).sort()) !== JSON.stringify(expectedDataKeys)
      || typeof error.message !== "string" || error.message.length < 1 || error.message.length > 512
      || catalogEntry === undefined || data.errorCode !== catalogEntry.errorCode
      || !errorCategories.has(data.category) || data.category !== catalogEntry.category
      || data.retryable !== catalogEntry.retryable || !errorIdPattern.test(data.errorId)
      || !validRetryAfter) {
    throw new Error("sidecar returned an invalid JA-RPC error contract");
  }
  return data.errorCode;
}

/** 对失败响应只暴露验证后的稳定 errorCode，不保留原始错误载荷。 */
function assertRpcSuccess(frame, label) {
  if (frame?.error !== undefined) {
    const code = requireRpcErrorCode(frame.error);
    throw new Error(`${label} failed: ${code}`);
  }
  return frame?.result;
}

/**
 * Owns one Java child and its JSONL correlation tables. It retains bounded
 * event projections only. The direct gate proves the Java-owned read and shell
 * path without retaining the removed reverse Host Tool protocol.
 */
export class JsonlSession {
  /** Starts the sidecar with a sanitized environment and exact owned data directory. */
  constructor({ command, prefixArgs, directories, apiKey, endpoint }) {
    const childEnvironment = { ...process.env };
    delete childEnvironment.JA_REAL_PROVIDER_API_KEY;
    delete childEnvironment.JA_REAL_PROVIDER_BASE_URL;
    delete childEnvironment.JA_REAL_PROVIDER_MODEL;
    delete childEnvironment.JA_REAL_PROVIDER_OPENAI_API_KEY;
    delete childEnvironment.JA_REAL_PROVIDER_ANTHROPIC_API_KEY;
    delete childEnvironment.JA_REAL_PROVIDER_OPENAI_BASE_URL;
    delete childEnvironment.JA_REAL_PROVIDER_ANTHROPIC_BASE_URL;
    delete childEnvironment.JA_REAL_PROVIDER_OPENAI_MODEL;
    delete childEnvironment.JA_REAL_PROVIDER_ANTHROPIC_MODEL;
    delete childEnvironment.JA_REAL_PROVIDER_AUTHORIZED;
    const args = [
      ...prefixArgs,
      `--home-dir-base64=${encodedDirectory(directories.home)}`,
      `--data-dir-base64=${encodedDirectory(directories.data)}`,
      `--run-dir-base64=${encodedDirectory(directories.run)}`,
      `--log-dir-base64=${encodedDirectory(directories.logs)}`,
    ];
    this.apiKey = apiKey;
    this.endpoint = endpoint;
    this.pending = new Map();
    this.waiters = [];
    this.events = [];
    this.requestSequence = 0;
    this.tokenMarkerPaths = [];
    this.stderr = "";
    this.exited = false;
    this.protocolFailure = undefined;
    this.exitResult = undefined;
    this.child = spawn(command, args, {
      cwd: repoRoot,
      env: childEnvironment,
      windowsHide: true,
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.exitPromise = new Promise((resolveExit) => {
      this.child.once("exit", (code, signal) => {
        this.exited = true;
        this.exitResult = { code, signal };
        this.failOutstanding(new Error(`production sidecar exited with code ${code ?? "none"}`));
        resolveExit(this.exitResult);
      });
    });
    this.child.once("error", (error) => this.failOutstanding(error));
    this.child.stderr.on("data", (chunk) => {
      const safe = redact(chunk, [this.apiKey, this.endpoint]);
      this.stderr = `${this.stderr}${safe}`.slice(-4_096);
    });
    this.lines = createInterface({ input: this.child.stdout, crlfDelay: Infinity });
    this.lines.on("line", (line) => this.acceptLine(line));
  }

  /** Writes one complete JSONL frame and refuses requests after process exit. */
  send(frame) {
    if (this.protocolFailure !== undefined) {
      throw this.protocolFailure;
    }
    if (this.exited || !this.child.stdin.writable) {
      throw new Error("production sidecar stdin is unavailable");
    }
    this.child.stdin.write(`${JSON.stringify(frame)}\n`);
  }

  /** Issues one correlated client request with a finite protocol deadline. */
  request(method, params, timeoutMs = requestTimeoutMs) {
    const id = `c:${++this.requestSequence}`;
    const response = new Promise((resolveResponse, rejectResponse) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        rejectResponse(new Error(`${method} response timed out`));
      }, timeoutMs);
      this.pending.set(id, { resolve: resolveResponse, reject: rejectResponse, timer });
    });
    this.send({ jsonrpc: "2.0", id, method, params });
    return response;
  }

  /** Emits the one initialized notification after the negotiated response. */
  notifyInitialized() {
    this.send({ jsonrpc: "2.0", method: "runtime/initialized", params: { readyToken } });
  }

  /** Waits for an authoritative event predicate and first checks already buffered frames. */
  waitForEvent(method, predicate = () => true, timeoutMs = requestTimeoutMs) {
    const buffered = this.events.find((frame) => frame.method === method && predicate(frame));
    if (buffered !== undefined) {
      return Promise.resolve(buffered);
    }
    return new Promise((resolveEvent, rejectEvent) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((waiter) => waiter.timer !== timer);
        rejectEvent(new Error(`${method} event timed out`));
      }, timeoutMs);
      this.waiters.push({ method, predicate, resolve: resolveEvent, reject: rejectEvent, timer });
    });
  }

  /** Waits for one of several authoritative events so a failed Turn is reported before the full deadline. */
  waitForAnyEvent(predicate = () => true, timeoutMs = requestTimeoutMs) {
    const buffered = this.events.find((frame) => predicate(frame));
    if (buffered !== undefined) {
      return Promise.resolve(buffered);
    }
    return new Promise((resolveEvent, rejectEvent) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((waiter) => waiter.timer !== timer);
        rejectEvent(new Error("required sidecar event timed out"));
      }, timeoutMs);
      this.waiters.push({ method: undefined, predicate, resolve: resolveEvent, reject: rejectEvent, timer });
    });
  }

  /** Parses one bounded child frame and routes responses, requests, and notifications separately. */
  acceptLine(line) {
    if (line.length === 0 || line.length > 4 * 1024 * 1024) {
      this.failOutstanding(new Error("invalid JSONL frame length"));
      return;
    }
    let frame;
    try {
      frame = JSON.parse(line);
    } catch {
      this.failOutstanding(new Error("invalid JSONL frame"));
      return;
    }
    for (const path of tokenShapedPaths(frame)) {
      const descriptor = `${typeof frame?.method === "string" ? frame.method : "response"}:${path}`;
      if (!this.tokenMarkerPaths.includes(descriptor) && this.tokenMarkerPaths.length < 32) {
        this.tokenMarkerPaths.push(descriptor);
      }
    }
    if (typeof frame?.method === "string" && frame.id !== undefined) {
      this.acceptServerRequest(frame);
      return;
    }
    if (frame?.id !== undefined) {
      const pending = this.pending.get(String(frame.id));
      if (pending !== undefined) {
        clearTimeout(pending.timer);
        this.pending.delete(String(frame.id));
        pending.resolve(frame);
      }
      return;
    }
    if (typeof frame?.method === "string") {
      if (frame.method === "host-tool/cancel") {
        this.failProtocol(new Error("direct real-provider smoke does not execute reverse requests"));
        return;
      }
      this.events.push(frame);
      if (this.events.length > 2_048) {
        this.events.shift();
      }
      for (const waiter of [...this.waiters]) {
        if ((waiter.method === undefined || waiter.method === frame.method) && waiter.predicate(frame)) {
          clearTimeout(waiter.timer);
          this.waiters = this.waiters.filter((candidate) => candidate !== waiter);
          waiter.resolve(frame);
        }
      }
    }
  }

  /**
   * Closes on every reverse request without manufacturing an acknowledgement;
   * any server request here proves the direct Provider boundary has drifted.
   */
  acceptServerRequest(frame) {
    const reverseRequest = typeof frame?.id === "string" && frame.id.startsWith("h:")
      && frame.method === "host-tool/invoke";
    this.failProtocol(new Error(reverseRequest
      ? "direct real-provider smoke does not execute reverse requests"
      : "unexpected sidecar server request"));
  }

  /** Makes a protocol violation sticky so no later frame can revive the failed session. */
  failProtocol(error) {
    if (this.protocolFailure === undefined) this.protocolFailure = error;
    this.failOutstanding(this.protocolFailure);
  }

  /** Rejects every blocked waiter without exposing a raw child frame or credential. */
  failOutstanding(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
    for (const waiter of this.waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
    this.waiters = [];
  }

  /** Requests the normal drain, closes stdin, and returns the real process exit identity. */
  async shutdown() {
    const response = await this.request("runtime/shutdown", {}, exitTimeoutMs);
    const result = assertRpcSuccess(response, "runtime/shutdown");
    if (result?.status !== "shutting_down") {
      throw new Error("shutdown returned an unexpected status");
    }
    this.child.stdin.end();
    return withTimeout(this.exitPromise, exitTimeoutMs, "production sidecar exit");
  }

  /** Terminates only this exact child when a failed assertion prevents graceful shutdown. */
  async forceClose() {
    this.lines?.close();
    if (!this.exited) {
      this.child.kill("SIGKILL");
      await withTimeout(this.exitPromise, 5_000, "forced sidecar exit").catch(() => undefined);
    }
  }
}

/**
 * Rejects capability drift locally before a paid smoke can start. Exact array
 * comparison intentionally removes stale change surfaces instead of silently
 * negotiating a subset with a stale sidecar.
 */
export function assertDirectProviderCapabilities(capabilities) {
  const expectedKeys = ["accessModes", "events", "methods"];
  if (capabilities === null || typeof capabilities !== "object"
      || JSON.stringify(Object.keys(capabilities).sort()) !== JSON.stringify(expectedKeys)) {
    throw new Error("direct provider capabilities have an invalid shape");
  }
  if (JSON.stringify(capabilities.methods) !== JSON.stringify(methods)
      || JSON.stringify(capabilities.events) !== JSON.stringify(events)
      || JSON.stringify(capabilities.accessModes) !== JSON.stringify(["approval_required", "full_access"])) {
    throw new Error("direct provider capabilities do not match JA-RPC v2");
  }
  return capabilities;
}

/** Builds the strict capability/limit offer used by the direct provider gate. */
export function initializeParams() {
  const capabilities = assertDirectProviderCapabilities({
    methods: [...methods],
    events: [...events],
    accessModes: ["approval_required", "full_access"],
  });
  return {
    protocolMajor: 2,
    protocolMinor: 0,
    clientVersion: "real-provider-smoke",
    capabilities,
    limits: {
      maxFrameBytes: 4_194_304,
      maxInFlightRequests: 64,
      maxInboundQueueFrames: 256,
      maxControlOutboundQueueFrames: 64,
      maxDataOutboundQueueFrames: 1_024,
      maxConcurrentTurns: 8,
      maxAdmittedTurns: 64,
      maxThreadQueuedTurns: 8,
      maxSnapshotPageItems: 200,
      maxToolBatchConcurrency: 8,
    },
  };
}

/** 构造完整的无 Secret Provider 配置；凭据字节只通过 credential/set 传输。 */
export function providerConfigurationDocument({ endpoint, provider, api, model,
  selectedProviderId = providerId, selectedModelId = modelId, revision = 0 }) {
  const supported = (provider === "openai" && api === "openai_responses")
    || (provider === "anthropic" && api === "anthropic_messages");
  if (!supported) {
    throw new Error("unsupported API for v3 provider document");
  }
  return {
    schema_version: 3,
    config_revision: revision,
    default_access_mode: "approval_required",
    default_provider_id: selectedProviderId,
    default_model_id: selectedModelId,
    default_reasoning_effort: "high",
    providers: [{
      provider_id: selectedProviderId,
      name: "Authorized loopback provider",
      provider,
      api,
      base_url: endpoint,
      credential_id: credentialId,
      network_timeouts: { connect_timeout_ms: 5_000, request_timeout_ms: 120_000 },
      agent_defaults: {
        context: { auto_compact: true },
        turn_limits: { max_model_rounds: 32, max_tool_calls: 128, wall_timeout_ms: 120_000 },
        skill_ids: [],
        mcp_ids: [],
      },
      models: [{
        model_id: selectedModelId,
        name: "Authorized loopback model",
        model,
        capabilities: {
          context_window_tokens: 128_000,
          max_output_tokens: 8_192,
          input_modalities: ["text"],
        },
        reasoning_efforts: ["low", "medium", "high"],
        default_reasoning_effort: "high",
      }],
    }],
    mcp_servers: [],
    skills: [],
  };
}

/**
 * Preserves unrelated real-home providers and catalog entries while replacing
 * only the explicitly selected smoke provider in the authoritative document.
 */
function persistentConfigurationDocument(current, selected) {
  const base = current !== null && typeof current === "object" && !Array.isArray(current)
    ? current : providerConfigurationDocument(selected);
  const selectedDocument = providerConfigurationDocument({
    ...selected,
    revision: Number.isSafeInteger(base.config_revision) ? base.config_revision + 1 : 0,
  });
  const providers = Array.isArray(base.providers)
    ? base.providers.filter((entry) => entry?.provider_id !== selected.selectedProviderId)
    : [];
  return {
    ...base,
    schema_version: 3,
    config_revision: selectedDocument.config_revision,
    default_access_mode: base.default_access_mode === "full_access" ? "full_access" : "approval_required",
    default_provider_id: selected.selectedProviderId,
    default_model_id: selected.selectedModelId,
    default_reasoning_effort: "high",
    providers: [...providers, selectedDocument.providers[0]],
    mcp_servers: Array.isArray(base.mcp_servers) ? base.mcp_servers : [],
    skills: Array.isArray(base.skills) ? base.skills : [],
  };
}

/** Builds the only Tool turn accepted by this gate: a read plus an explicitly approved shell echo. */
export function providerToolTurnInput({ inputPath, inputMarker, shellMarker, finalMarker }) {
  for (const value of [inputPath, inputMarker, shellMarker, finalMarker]) {
    if (typeof value !== "string" || value.length === 0 || value.length > 256
        || /[\r\n]/u.test(value)) {
      throw new Error("provider Tool fixture values are invalid");
    }
  }
  const shellCommand = process.platform === "win32"
    ? `Write-Output '${shellMarker}'`
    : `printf '%s\\n' '${shellMarker}'`;
  return [
    "Use exactly two built-in tools in this order and no others.",
    `First call read with the JSON arguments {"path":"${inputPath}"}; the file content must contain ${inputMarker}.`,
    `Then call shell with the JSON arguments ${JSON.stringify({ command: shellCommand })}; wait for the human approval request and do not substitute another command.`,
    `After both Tool results, reply with exactly ${finalMarker} and no other text.`,
  ].join(" ");
}

/** Returns bounded public Tool projections for one Turn without retaining provider request bodies. */
export function collectToolEvidence(events, turnId) {
  if (!Array.isArray(events) || typeof turnId !== "string") {
    throw new Error("Tool evidence input is invalid");
  }
  const toolNames = new Map();
  for (const frame of events) {
    if (frame?.method !== "assistant/model-step-committed" || frame.params?.turnId !== turnId
        || !Array.isArray(frame.params.toolCalls)) continue;
    for (const call of frame.params.toolCalls) {
      if (typeof call?.callId !== "string" || !call.callId.startsWith("call_")
          || typeof call.toolName !== "string" || call.toolName.length === 0) {
        throw new Error("assistant/model-step-committed contained an invalid Tool call projection");
      }
      toolNames.set(call.callId, call.toolName);
    }
  }
  const evidence = [];
  for (const frame of events) {
    if (frame?.method !== "tool/batch-committed" || frame.params?.turnId !== turnId) continue;
    if (!Array.isArray(frame.params.results) || frame.params.results.length === 0) {
      throw new Error("tool/batch-committed did not contain a non-empty result batch");
    }
    for (const result of frame.params.results) {
      const toolName = toolNames.get(result?.callId);
      if (typeof result?.callId !== "string" || !result.callId.startsWith("call_")
          || typeof toolName !== "string" || typeof result.outcome !== "string") {
        throw new Error("tool/batch-committed contained an invalid Tool projection");
      }
      evidence.push({
        callId: result.callId,
        toolName,
        outcome: result.outcome,
        turnId,
      });
    }
  }
  return evidence;
}

/** Waits for read and approved-shell evidence, failing immediately if the Turn reaches another terminal state. */
async function waitForRequiredToolEvidence(session, turnId, timeoutMs = requestTimeoutMs) {
  const deadline = Date.now() + timeoutMs;
  const expected = ["read", "shell"];
  while (Date.now() < deadline) {
    const evidence = collectToolEvidence(session.events, turnId);
    const names = evidence.map((entry) => entry.toolName);
    if (names.length > expected.length || names.some((name, index) => name !== expected[index])) {
      throw new Error("provider Tool turn invoked an unexpected Tool or order");
    }
    if (names.length === expected.length) {
      if (evidence.some((entry) => entry.outcome !== "succeeded")) {
        throw new Error("provider Tool turn did not complete every required Tool");
      }
      return evidence;
    }
    const observedCallIds = new Set(evidence.map((entry) => entry.callId));
    const remaining = Math.max(1, deadline - Date.now());
    const event = await session.waitForAnyEvent((frame) =>
      frame.params?.turnId === turnId
        && (frame?.method === "turn/terminal"
          || (frame?.method === "tool/batch-committed" && Array.isArray(frame.params?.results)
            && frame.params.results.some((result) => !observedCallIds.has(result?.callId)))), remaining);
    if (event.method === "turn/terminal") {
      throw new Error(`provider Tool turn ended before required Tools: ${event.params?.state ?? "unknown"}`);
    }
  }
  throw new Error("provider Tool evidence timed out");
}

/** Verifies that the durable Thread projection contains both Tool pairs and the committed approval decision. */
export function assertDurableToolHistory(history, threadId, turnId, approvalId) {
  if (history?.threadId !== threadId || !Number.isSafeInteger(history.revision)
      || !Array.isArray(history.items)) {
    throw new Error("thread/read did not return a durable snapshot");
  }
  const calls = history.items.filter((item) => item.kind === "tool_call");
  const results = history.items.filter((item) => item.kind === "tool_result");
  const names = [...calls, ...results].map((item) => item.toolName);
  if (calls.length !== 2 || results.length !== 2
      || JSON.stringify(names) !== JSON.stringify(["read", "shell", "read", "shell"])) {
    throw new Error("thread/read did not persist the required Tool call/result pairs");
  }
  if (calls.some((item) => typeof item.callId !== "string" || !item.callId.startsWith("call_"))
      || results.some((item) => typeof item.callId !== "string" || !item.callId.startsWith("call_"))
      || calls.some((item, index) => item.callId !== results[index].callId)) {
    throw new Error("thread/read did not preserve Tool call/result correlation");
  }
  const approval = history.items.find((item) => item.kind === "approval"
    && item.approvalId === approvalId && item.turnId === turnId);
  if (approval?.decision !== "approve") {
    throw new Error("thread/read did not persist the approved shell decision");
  }
  return { items: history.items.length, toolCalls: calls.length, toolResults: results.length };
}

/** Starts one sanitized production child and completes the v2 ready handshake before returning it. */
async function startProductionSession({ command, prefixArgs, directories, apiKey, endpoint }) {
  const session = new JsonlSession({ command, prefixArgs, directories, apiKey, endpoint });
  try {
    const initialized = assertRpcSuccess(await session.request("runtime/initialize", initializeParams()), "runtime/initialize");
    if (initialized?.runtime?.engine !== "ja-kernel" || typeof initialized?.runtime?.engineVersion !== "string") {
      throw new Error("sidecar did not start as the Ja Kernel production runtime");
    }
    session.notifyInitialized();
    await session.waitForEvent("runtime/status-changed", (frame) => frame.params?.status === "ready"
      && frame.params?.readyToken === readyToken && frame.params?.generation > 0, 20_000);
    return { session, initialized };
  } catch (error) {
    await session.forceClose();
    throw error;
  }
}

/** Runs text plus approved Tool Turns, reopens the same SQLite home, and checks durable recovery. */
export async function runSmoke({ command, prefixArgs, silent = false } = {}) {
  if (process.env.JA_REAL_PROVIDER_AUTHORIZED !== "1") {
    throw new Error("JA_REAL_PROVIDER_AUTHORIZED=1 is required for paid provider traffic");
  }
  const provider = (process.env.JA_REAL_PROVIDER_PROVIDER ?? "openai").trim();
  if (provider !== "openai" && provider !== "anthropic") {
    throw new Error("JA_REAL_PROVIDER_PROVIDER must be openai or anthropic");
  }
  const apiKeyName = provider === "openai"
    ? "JA_REAL_PROVIDER_OPENAI_API_KEY"
    : "JA_REAL_PROVIDER_ANTHROPIC_API_KEY";
  const endpointName = provider === "openai"
    ? "JA_REAL_PROVIDER_OPENAI_BASE_URL"
    : "JA_REAL_PROVIDER_ANTHROPIC_BASE_URL";
  const modelName = provider === "openai"
    ? "JA_REAL_PROVIDER_OPENAI_MODEL"
    : "JA_REAL_PROVIDER_ANTHROPIC_MODEL";
  const apiKey = requiredEnvironment(apiKeyName);
  if (apiKey.length > 8_192 || containsAsciiControl(apiKey)) {
    throw new Error(`${apiKeyName} is invalid`);
  }
  // Minimize the inheritance window before any child is created; the local
  // variable remains available only for the correlated stdin response.
  delete process.env.JA_REAL_PROVIDER_API_KEY;
  delete process.env.JA_REAL_PROVIDER_OPENAI_API_KEY;
  delete process.env.JA_REAL_PROVIDER_ANTHROPIC_API_KEY;
  const endpoint = validatedLoopbackBaseUrl(requiredEnvironment(endpointName), endpointName);
  const defaultApi = provider === "openai" ? "openai_responses" : "anthropic_messages";
  const api = (process.env.JA_REAL_PROVIDER_API ?? defaultApi).trim();
  const validApis = provider === "openai"
    ? new Set(["openai_responses"])
    : new Set(["anthropic_messages"]);
  if (!validApis.has(api)) {
    throw new Error("JA_REAL_PROVIDER_API does not match JA_REAL_PROVIDER_PROVIDER");
  }
  delete process.env.JA_REAL_PROVIDER_API;
  const model = process.env[modelName]?.trim() ?? "";
  if (model.length === 0 || model.length > 128 || /[\r\n]/u.test(model)) {
    throw new Error(`${modelName} is invalid`);
  }
  const persistent = process.env.JA_REAL_PROVIDER_PERSIST_HOME === "1";
  delete process.env.JA_REAL_PROVIDER_PERSIST_HOME;
  const directories = persistent
    ? await createPersistentDirectories()
    : await createIsolatedDirectories();
  const selectedProviderId = `provider_real_${api}`;
  const selectedModelId = `model_real_${api}`;
  const selectedCommand = command ?? resolveJava();
  if (command === undefined) await assertJava25(selectedCommand);
  const jar = command === undefined ? await resolveJar() : undefined;
  const launch = {
    command: selectedCommand,
    prefixArgs: prefixArgs ?? ["-jar", jar],
    directories,
    apiKey,
    endpoint,
  };
  let session;
  let initialized;
  let toolFixturePath;
  try {
    ({ session, initialized } = await startProductionSession(launch));

    const configuration = assertRpcSuccess(await session.request("configuration/read", {}), "configuration/read");
    if (!persistent && (configuration?.user?.version !== "cfg_missing"
        || configuration?.credentialVersion !== "cfg_missing")) {
      throw new Error("fresh smoke home did not report missing configuration generations");
    }
    const selectedConfiguration = { endpoint, provider, api, model, selectedProviderId, selectedModelId };
    const document = persistent
      ? persistentConfigurationDocument(configuration?.user?.document, selectedConfiguration)
      : providerConfigurationDocument(selectedConfiguration);
    const configured = assertRpcSuccess(await session.request("configuration/replace", {
      scope: "user",
      expectedVersion: configuration.user.version,
      document,
    }), "configuration/replace");
    if (configured?.accepted !== true || configured?.scope !== "user"
        || typeof configured?.version !== "string") {
      throw new Error("configuration/replace returned an invalid result");
    }
    const credential = assertRpcSuccess(await session.request("credential/set", {
      credentialId,
      secret: apiKey,
      expectedVersion: configuration.credentialVersion,
    }), "credential/set");
    if (credential?.accepted !== true || credential?.credentialId !== credentialId
        || credential?.configured !== true || typeof credential?.version !== "string") {
      throw new Error("credential/set returned an invalid redacted projection");
    }

    const workspace = assertRpcSuccess(await session.request("workspace/open", {
      cwd: directories.workspace,
      displayName: "Real provider smoke",
    }), "workspace/open");
    if (typeof workspace?.workspaceId !== "string" || !workspace.workspaceId.startsWith("ws_")) {
      throw new Error("workspace/open returned an invalid workspace identity");
    }

    const created = assertRpcSuccess(await session.request("thread/create", {
      cwd: directories.workspace,
      title: "Real provider smoke",
      providerId: selectedProviderId,
      modelId: selectedModelId,
      reasoningEffort: "high",
      accessMode: "approval_required",
    }), "thread/create");
    const threadId = created?.threadId;
    if (typeof threadId !== "string" || !threadId.startsWith("thr_")) {
      throw new Error("thread/create returned an invalid thread identity");
    }
    if (created.workspaceId !== workspace.workspaceId) {
      throw new Error("thread/create did not retain Java's workspace identity");
    }
    const textMarker = `JA_REAL_PROVIDER_TEXT_${Date.now().toString(36)}`;
    const textAccepted = assertRpcSuccess(await session.request("turn/start", {
      threadId,
      input: [{ type: "text", text: `Reply with exactly ${textMarker}. Do not call tools.` }],
    }), "turn/start");
    const textTurnId = textAccepted?.turnId;
    if (typeof textTurnId !== "string" || !textTurnId.startsWith("turn_")) {
      throw new Error("text turn/start returned an invalid turn identity");
    }
    const textTerminal = await session.waitForEvent("turn/terminal", (frame) => frame.params?.turnId === textTurnId);
    if (textTerminal.params?.state !== "completed") {
      // Only the closed error code is safe to expose here; Provider messages and stderr remain
      // bounded inside the smoke so a failed live call cannot turn diagnostics into a body leak.
      throw new Error(`real provider text turn ended as ${textTerminal.params?.state ?? "unknown"}`
        + ` with ${textTerminal.params?.errorCode ?? "UNKNOWN_ERROR"}`
        + ` ${safeTerminalMessage(textTerminal.params?.errorMessage)}`
        + ` events=${turnEventMethods(session.events, textTurnId)}`);
    }
    const textTerminalCount = session.events.filter((frame) => frame.method === "turn/terminal"
      && frame.params?.turnId === textTurnId).length;
    if (textTerminalCount !== 1 || !JSON.stringify(session.events).includes(textMarker)) {
      throw new Error("real provider text stream did not publish one marker-bearing terminal turn");
    }

    const inputPath = `provider-tool-input-${Date.now().toString(36)}.txt`;
    const inputMarker = `JA_REAL_PROVIDER_TOOL_INPUT_${Date.now().toString(36)}`;
    const shellMarker = `JA_REAL_PROVIDER_SHELL_${Date.now().toString(36)}`;
    const toolFinalMarker = `JA_REAL_PROVIDER_TOOL_FINAL_${Date.now().toString(36)}`;
    toolFixturePath = join(directories.workspace, inputPath);
    await writeFile(toolFixturePath, `${inputMarker}\n`, {
      encoding: "utf8",
      flag: "wx",
    });
    const toolAccepted = assertRpcSuccess(await session.request("turn/start", {
      threadId,
      input: [{ type: "text", text: providerToolTurnInput({
        inputPath, inputMarker, shellMarker, finalMarker: toolFinalMarker,
      }) }],
    }), "turn/start Tool");
    const toolTurnId = toolAccepted?.turnId;
    if (typeof toolTurnId !== "string" || !toolTurnId.startsWith("turn_")) {
      throw new Error("Tool turn/start returned an invalid turn identity");
    }
    const approvedTools = [];
    const handledApprovalIds = new Set();
    for (const expectedToolName of ["read", "shell"]) {
      const approvalOrTerminal = await session.waitForAnyEvent((frame) =>
        frame.params?.turnId === toolTurnId
          && (frame?.method === "turn/terminal"
            || (frame?.method === "approval/requested"
              && !handledApprovalIds.has(frame.params?.approvalId))), requestTimeoutMs);
      if (approvalOrTerminal.method === "turn/terminal") {
        let historyKinds = "unavailable";
        try {
          historyKinds = durableItemKinds(assertRpcSuccess(await session.request("thread/read", {
            threadId, cursor: null, limit: 200,
          }), "thread/read failed Tool turn"));
        } catch {
          // The original terminal remains primary; history diagnostics must never hide it.
        }
        throw new Error(`provider Tool turn ended before all approvals: ${approvalOrTerminal.params?.state ?? "unknown"}`
          + ` with ${approvalOrTerminal.params?.errorCode ?? "UNKNOWN_ERROR"}`
          + ` ${safeTerminalMessage(approvalOrTerminal.params?.errorMessage)}`
          + ` events=${turnEventMethods(session.events, toolTurnId)}`
          + ` history=${historyKinds}`);
      }
      const approval = approvalOrTerminal.params;
      if (typeof approval?.approvalId !== "string" || !approval.approvalId.startsWith("appr_")
          || typeof approval.callId !== "string" || !approval.callId.startsWith("call_")
          || approval.toolName !== expectedToolName || typeof approval.reason !== "string"
          || typeof approval.expiresAt !== "string" || !Number.isSafeInteger(approval.threadRevision)) {
        throw new Error(`provider Tool approval had an invalid v2 projection ${JSON.stringify({
          approvalId: typeof approval?.approvalId === "string" && approval.approvalId.startsWith("appr_"),
          callId: typeof approval?.callId === "string" && approval.callId.startsWith("call_"),
          tool: approval?.toolName === expectedToolName,
          reason: typeof approval?.reason === "string",
          expiresAt: typeof approval?.expiresAt === "string",
          threadRevision: Number.isSafeInteger(approval?.threadRevision),
        })}`);
      }
      handledApprovalIds.add(approval.approvalId);
      const approvalResponse = assertRpcSuccess(await session.request("approval/respond", {
        approvalId: approval.approvalId,
        turnId: toolTurnId,
        decision: "approve",
        expectedThreadRevision: approval.threadRevision,
      }), "approval/respond");
      if (approvalResponse?.accepted !== true || approvalResponse.approvalId !== approval.approvalId
          || approvalResponse.turnId !== toolTurnId || approvalResponse.decision !== "approve"
          || !Number.isSafeInteger(approvalResponse.threadRevision)) {
        throw new Error("approval/respond returned an invalid committed projection");
      }
      const resolved = await session.waitForEvent("approval/resolved", (frame) =>
        frame.params?.turnId === toolTurnId && frame.params?.approvalId === approval.approvalId);
      if (resolved.params?.decision !== "approve") {
        throw new Error("provider Tool approval resolved with an unexpected decision");
      }
      approvedTools.push(approval);
    }
    const shellApproval = approvedTools.find((approval) => approval.toolName === "shell");
    const toolEvidence = await waitForRequiredToolEvidence(session, toolTurnId);
    const readEvidence = toolEvidence.find((entry) => entry.toolName === "read");
    const shellEvidence = toolEvidence.find((entry) => entry.toolName === "shell");
    const toolEventsJson = JSON.stringify(session.events);
    if (readEvidence === undefined || shellEvidence === undefined
        || !toolEventsJson.includes(inputMarker) || !toolEventsJson.includes(shellMarker)) {
      throw new Error("provider Tool results did not prove the bounded fixture inputs");
    }
    const toolTerminal = await session.waitForEvent("turn/terminal", (frame) => frame.params?.turnId === toolTurnId);
    if (toolTerminal.params?.state !== "completed" || !JSON.stringify(session.events).includes(toolFinalMarker)) {
      throw new Error("provider Tool turn did not publish one marker-bearing completed terminal");
    }

    const history = assertRpcSuccess(await session.request("thread/read", { threadId }), "thread/read");
    if (!history.items.some((item) => item.kind === "user_input" && item.text?.includes(textMarker))
        || !history.items.some((item) => item.kind === "assistant_message" && item.text?.includes(textMarker))
        || !history.items.some((item) => item.kind === "assistant_message" && item.text?.includes(toolFinalMarker))) {
      throw new Error("thread/read did not restore both real provider text turns");
    }
    const durable = assertDurableToolHistory(history, threadId, toolTurnId, shellApproval.approvalId);
    const firstExit = await session.shutdown();
    if (firstExit.code !== 0 || firstExit.signal !== null) {
      throw new Error(`production sidecar first exit had code ${firstExit.code ?? "none"}`);
    }

    let restartedInitialized;
    ({ session, initialized: restartedInitialized } = await startProductionSession(launch));
    if (restartedInitialized?.runtime?.engine !== "ja-kernel") {
      throw new Error("restarted sidecar did not negotiate the Ja Kernel runtime");
    }
    const afterRestart = assertRpcSuccess(await session.request("configuration/read", {}), "configuration/read after restart");
    if (afterRestart?.user?.version !== configured.version
        || afterRestart?.credentialVersion !== credential.version
        || afterRestart?.credentials?.[credentialId]?.configured !== true) {
      throw new Error("sidecar restart did not recover the Java-owned configuration and credential generation");
    }
    const recoveredHistory = assertRpcSuccess(await session.request("thread/read", { threadId }),
      "thread/read after restart");
    const recovered = assertDurableToolHistory(recoveredHistory, threadId, toolTurnId, shellApproval.approvalId);
    if (recoveredHistory.revision < history.revision) {
      throw new Error("sidecar restart recovered an older Thread revision");
    }
    if (!persistent) {
      const cleared = assertRpcSuccess(await session.request("credential/delete", {
        credentialId,
        expectedVersion: afterRestart.credentialVersion,
      }), "credential/delete after restart");
      if (cleared?.accepted !== true || cleared?.credentialId !== credentialId
          || cleared?.configured !== false || typeof cleared?.version !== "string") {
        throw new Error("credential/delete returned an invalid redacted projection");
      }
    }
    const exit = await session.shutdown();
    if (exit.code !== 0 || exit.signal !== null) {
      throw new Error(`production sidecar restart exit had code ${exit.code ?? "none"}`);
    }
    const logMetadata = await stat(join(directories.logs, "app-server.log"));
    if (!logMetadata.isFile() || logMetadata.size === 0 || logMetadata.size > 16 * 1024 * 1024) {
      throw new Error("production sidecar runtime log was not persisted");
    }
    const retainedOutput = `${JSON.stringify(session.events)}\n${await readFile(
      join(directories.logs, "app-server.log"), "utf8",
    )}`;
    if (retainedOutput.includes(apiKey) || toolEventsJson.includes(apiKey)
        || retainedOutput.includes(endpoint) || toolEventsJson.includes(endpoint)) {
      throw new Error("provider credential or endpoint escaped the redacted runtime boundary");
    }
    if (!persistent) await assertSecretAbsentFromOwnedFiles(directories.root, apiKey);
    const report = {
      status: "passed",
      runtime: "production",
      engineVersion: initialized.runtime.engineVersion,
      provider,
      api,
      model,
      textTurnStatus: textTerminal.params.state,
      toolTurnStatus: toolTerminal.params.state,
      toolNames: toolEvidence.map((entry) => entry.toolName),
      approvedToolNames: approvedTools.map((approval) => approval.toolName),
      approvalResolved: true,
      historyItems: durable.items,
      recoveredHistoryItems: recovered.items,
      restartRecovered: true,
      exitCode: exit.code,
      firstExitCode: firstExit.code,
      tokenMarkerPaths: session.tokenMarkerPaths,
      logBytes: logMetadata.size,
    };
    if (!silent) process.stdout.write(`${JSON.stringify(report)}\n`);
    return report;
  } catch (error) {
    const diagnostic = session?.stderr === "" || session === undefined
      ? "" : " (sidecar stderr was captured and redacted)";
    const signals = stderrDiagnosticSignals(session?.stderr);
    const classified = signals === "" ? "" : ` [signals=${signals}]`;
    throw new Error(`${redact(error?.message ?? error, [apiKey, endpoint])}${classified}${diagnostic}`);
  } finally {
    await session?.forceClose();
    if (toolFixturePath !== undefined) await rm(toolFixturePath, { force: true });
    if (!persistent) await cleanupIsolatedDirectories(directories.root);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await runSmoke().catch((error) => {
    process.stderr.write(`real-provider-smoke failed: ${redact(error?.message ?? error, [process.env.JA_REAL_PROVIDER_API_KEY])}\n`);
    process.exitCode = 1;
  });
}
