// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Production-sidecar smoke test for an explicitly authorized loopback endpoint implementing one of the
 * supported API specifications. The custom provider name never selects a wire adapter. The API key is accepted only through
 * the parent environment and is removed from the Java child's environment; it reaches the sidecar solely
 * inside the v1 credential/set request on stdin.
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
const errorCatalog = JSON.parse(
  readFileSync(join(repoRoot, "contracts", "ja-rpc", "v1", "error-catalog.json"), "utf8"),
);
const errorCategories = new Set(errorCatalog.categories);
const errorsByNumericCode = new Map(errorCatalog.errors.map((entry) => [entry.code, entry]));
const errorIdPattern = /^err_[0-9a-f]{32}$/u;
const temporaryPrefix = "ja-real-provider-";
const readyToken = "0123456789abcdef0123456789abcdef";
const providerId = "provider_real_provider_smoke";
const modelId = "model_real_provider_smoke";
const credentialId = "cred_real_provider_smoke";
const reasoningLevels = new Set(["off", "minimal", "low", "medium", "high", "xhigh", "max"]);
const requestTimeoutMs = 120_000;
const exitTimeoutMs = 20_000;
const methods = [
  "runtime/initialize",
  "runtime/health",
  "runtime/shutdown",
  "workspace/open",
  "workspace/open-general",
  "workspace/list",
  "workspace/path/search",
  "workspace/set-trust",
  "workspace/unregister",
  "thread/create",
  "thread/list",
  "thread/search",
  "thread/read",
  "thread/rename",
  "thread/pin",
  "thread/seen",
  "thread/preferences/update",
  "thread/archive",
  "thread/restore",
  "thread/delete",
  "thread/compact",
  "interaction/read",
  "interaction/observe",
  "interaction/unobserve",
  "interaction/draft/save",
  "interaction/respond",
  "interaction/cancel",
  "goal/read",
  "goal/events/read",
  "goal/observe",
  "goal/unobserve",
  "plan/read",
  "plan/revisions/list",
  "plan/current/read",
  "plan/events/read",
  "plan/observe",
  "plan/unobserve",
  "plan/evidence/list",
  "goal/evidence/list",
  "goal/create",
  "goal/plan/attach",
  "goal/plan/detach",
  "goal/pause",
  "goal/resume",
  "goal/stop",
  "plan/create",
  "plan/draft/save",
  "plan/draft/discard",
  "plan/propose",
  "plan/execute",
  "plan/reject",
  "plan/pause",
  "plan/resume",
  "plan/stop",
  "task/create",
  "task/list",
  "task/read",
  "task/observe",
  "task/unobserve",
  "task/seen",
  "thread/message/send",
  "task/followup",
  "task/cancel",
  "task/tree/delete",
  "task/close",
  "attachment/import",
  "attachment/discard",
  "attachment/preview/open",
  "attachment/preview/read",
  "attachment/preview/close",
  "turn/start",
  "turn/resume",
  "turn/cancel",
  "turn/input/enqueue",
  "turn/input/prioritize",
  "turn/input/update",
  "turn/input/delete",
  "turn/change-set/read",
  "approval/respond",
  "configuration/read",
  "configuration/patch",
  "configuration/replace",
  "configuration/reset",
  "credential/set",
  "credential/delete",
  "skill/list",
  "mcp/list",
  "mcp/test",
  "model/test",
  "mcp/list-tools",
  "tool/artifact/read",
];
const events = [
  "runtime/status-changed",
  "turn/state-changed",
  "turn/input-queue-changed",
  "turn/input-consumed",
  "turn/messages_received",
  "assistant/model-step-committed",
  "assistant/text-delta",
  "assistant/reasoning-summary-delta",
  "tool/started",
  "tool/batch-committed",
  "approval/requested",
  "approval/resolved",
  "context/compaction-started",
  "context/compacted",
  "context/compaction-failed",
  "workspace/dirty",
  "turn/terminal",
  "thread/metadata-changed",
  "configuration/changed",
  "task/activity",
  "task/progress",
  "task/mailbox-changed",
  "goal/changed",
  "goal/activity",
  "interaction/changed",
  "plan/changed",
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
  if (
    url.protocol !== "http:" ||
    !loopbackHosts.has(url.hostname) ||
    url.username !== "" ||
    url.password !== "" ||
    url.search !== "" ||
    url.hash !== ""
  ) {
    throw new Error(`${variableName} must be a credential-free loopback HTTP URL`);
  }
  return url.toString().replace(/\/+$/, "");
}

/** Resolves the exact freshly built sidecar artifact and rejects a missing or empty jar. */
async function resolveJar() {
  const jar = resolve(
    process.env.JA_REAL_PROVIDER_JAR ?? join(repoRoot, "app-server", "target", "ja-app-server.jar"),
  );
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
  if (
    dirname(target) !== resolve(tmpdir()) ||
    !target.split(/[\\/]/).at(-1)?.startsWith(temporaryPrefix)
  ) {
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
  const providerPattern =
    /provider_failure_code=([A-Z][A-Z0-9_]{1,63}) provider_failure_detail=([A-Za-z0-9 _.-]{1,160}) semantic_accepted=/gu;
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
  if (typeof value !== "string" || value.length === 0 || value.length > 256)
    return "NO_SAFE_MESSAGE";
  return /^[A-Za-z0-9 _-]+$/u.test(value) ? value : "UNSAFE_ERROR_MESSAGE";
}

/** Returns only the bounded JA-RPC method sequence for one Turn, without retaining event payloads. */
function turnEventMethods(events, turnId) {
  return events
    .filter((frame) => frame?.params?.turnId === turnId && typeof frame.method === "string")
    .slice(-24)
    .map((frame) => frame.method)
    .join(",");
}

/** 从完成终态读取非空答复；messageId 与 Turn 关联共同证明它不是临时 delta 或前序回合文本。 */
export function committedTerminalReply(frame, turnId) {
  const message = frame?.params?.finalMessage;
  if (
    frame?.method !== "turn/terminal" ||
    frame.params?.turnId !== turnId ||
    frame.params?.state !== "completed" ||
    typeof message?.messageId !== "string" ||
    message.messageId.trim().length === 0 ||
    message.messageId.length > 256 ||
    /[\0\r\n]/u.test(message.messageId) ||
    typeof message.text !== "string" ||
    message.text.trim().length === 0
  )
    return null;
  return message.text;
}

/**
 * 只从当前结构化 USER Message 契约读取文本块，避免 smoke 因沿用已删除的扁平 text 字段
 * 误报持久化失败；附件或其它内容块不会被字符串化后参与匹配。
 */
export function userInputContainsText(item, expectedText) {
  return (
    item?.kind === "user_input" &&
    typeof expectedText === "string" &&
    expectedText.length > 0 &&
    Array.isArray(item.content) &&
    item.content.some(
      (block) =>
        block?.type === "text" &&
        typeof block.text === "string" &&
        block.text.includes(expectedText),
    )
  );
}

/** Returns only the bounded durable item-kind sequence, never message or Tool payload data. */
function durableItemKinds(history) {
  if (!Array.isArray(history?.items)) return "unavailable";
  return history.items
    .slice(-24)
    .map((item) =>
      typeof item?.kind === "string" && /^[a-z_]{1,48}$/u.test(item.kind) ? item.kind : "invalid",
    )
    .join(",");
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
  const hasRetryAfter =
    data !== null && typeof data === "object" && Object.hasOwn(data, "retryAfterMs");
  const expectedErrorKeys = ["code", "data", "message"];
  const expectedDataKeys = hasRetryAfter
    ? ["category", "errorCode", "errorId", "retryAfterMs", "retryable"]
    : ["category", "errorCode", "errorId", "retryable"];
  const catalogEntry = Number.isSafeInteger(error?.code)
    ? errorsByNumericCode.get(error.code)
    : undefined;
  const validRetryAfter =
    !hasRetryAfter ||
    (data.retryable === true &&
      Number.isSafeInteger(data.retryAfterMs) &&
      data.retryAfterMs >= 1 &&
      data.retryAfterMs <= 3_600_000);
  if (
    data === null ||
    typeof data !== "object" ||
    Array.isArray(data) ||
    JSON.stringify(Object.keys(error).sort()) !== JSON.stringify(expectedErrorKeys) ||
    JSON.stringify(Object.keys(data).sort()) !== JSON.stringify(expectedDataKeys) ||
    typeof error.message !== "string" ||
    error.message.length < 1 ||
    error.message.length > 512 ||
    catalogEntry === undefined ||
    data.errorCode !== catalogEntry.errorCode ||
    !errorCategories.has(data.category) ||
    data.category !== catalogEntry.category ||
    data.retryable !== catalogEntry.retryable ||
    !errorIdPattern.test(data.errorId) ||
    !validRetryAfter
  ) {
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
    delete childEnvironment.JA_REAL_PROVIDER_NAME;
    delete childEnvironment.JA_REAL_PROVIDER_API;
    delete childEnvironment.JA_REAL_PROVIDER_REASONING_LEVEL;
    delete childEnvironment.JA_REAL_PROVIDER_AUTHORIZED;
    const args = [
      ...prefixArgs,
      `--home-dir-base64=${encodedDirectory(directories.home)}`,
      `--data-dir-base64=${encodedDirectory(directories.data)}`,
      `--run-dir-base64=${encodedDirectory(directories.run)}`,
      `--log-dir-base64=${encodedDirectory(directories.logs)}`,
      "--ja-runtime-generation=1",
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
      this.waiters.push({
        method: undefined,
        predicate,
        resolve: resolveEvent,
        reject: rejectEvent,
        timer,
      });
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
        this.failProtocol(
          new Error("direct real-provider smoke does not execute reverse requests"),
        );
        return;
      }
      this.events.push(frame);
      if (this.events.length > 2_048) {
        this.events.shift();
      }
      for (const waiter of [...this.waiters]) {
        if (
          (waiter.method === undefined || waiter.method === frame.method) &&
          waiter.predicate(frame)
        ) {
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
    const reverseRequest =
      typeof frame?.id === "string" &&
      frame.id.startsWith("h:") &&
      frame.method === "host-tool/invoke";
    this.failProtocol(
      new Error(
        reverseRequest
          ? "direct real-provider smoke does not execute reverse requests"
          : "unexpected sidecar server request",
      ),
    );
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
  const expectedKeys = ["accessModes", "collaborationModes", "events", "features", "methods"];
  if (
    capabilities === null ||
    typeof capabilities !== "object" ||
    JSON.stringify(Object.keys(capabilities).sort()) !== JSON.stringify(expectedKeys)
  ) {
    throw new Error("direct provider capabilities have an invalid shape");
  }
  if (
    JSON.stringify(capabilities.methods) !== JSON.stringify(methods) ||
    JSON.stringify(capabilities.events) !== JSON.stringify(events) ||
    JSON.stringify(capabilities.accessModes) !==
      JSON.stringify(["approval_required", "full_access"]) ||
    JSON.stringify(capabilities.collaborationModes) !== JSON.stringify(["default", "plan"]) ||
    JSON.stringify(capabilities.features) !==
      JSON.stringify(["task_threads_v1", "plan_goal_v1", "interaction_v1"])
  ) {
    throw new Error("direct provider capabilities do not match JA-RPC v1");
  }
  return capabilities;
}

/** Builds the strict capability/limit offer used by the direct provider gate. */
export function initializeParams() {
  const capabilities = assertDirectProviderCapabilities({
    methods: [...methods],
    events: [...events],
    accessModes: ["approval_required", "full_access"],
    collaborationModes: ["default", "plan"],
    features: ["task_threads_v1", "plan_goal_v1", "interaction_v1"],
  });
  return {
    protocolMajor: 1,
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
      maxTurnQueuedInputs: 8,
      maxTurnQueuedInputBytes: 524_288,
      maxSnapshotPageItems: 200,
      maxToolBatchConcurrency: 8,
    },
  };
}

/** 构造完整的无 Secret 自定义供应商配置；名称不参与路由，凭据字节只通过 credential/set 传输。 */
export function providerConfigurationDocument({
  endpoint,
  name = "Authorized loopback provider",
  api,
  model,
  selectedProviderId = providerId,
  selectedModelId = modelId,
  selectedCredentialId = credentialId,
  reasoningLevel = "medium",
  revision = 0,
}) {
  if (!new Set(["anthropic_messages", "openai_chat_completions", "openai_responses"]).has(api)) {
    throw new Error("unsupported API specification for v1 provider document");
  }
  if (!reasoningLevels.has(reasoningLevel)) {
    throw new Error("unsupported reasoning level for v1 provider document");
  }
  return {
    schema_version: 1,
    config_revision: revision,
    default_access_mode: "approval_required",
    default_provider_id: selectedProviderId,
    default_model_id: selectedModelId,
    default_reasoning_level: reasoningLevel,
    providers: [
      {
        provider_id: selectedProviderId,
        name,
        api,
        base_url: endpoint,
        credential_id: selectedCredentialId,
        network_timeouts: { connect_timeout_ms: 5_000, request_timeout_ms: 120_000 },
        agent_defaults: {
          context: { auto_compact: true },
          turn_limits: { max_model_rounds: 32, max_tool_calls: 128, wall_timeout_ms: 120_000 },
        },
        models: [
          {
            model_id: selectedModelId,
            name: "Authorized loopback model",
            model,
            capabilities: {
              context_window_tokens: 128_000,
              max_output_tokens: 8_192,
            },
            reasoning_level_map: { [reasoningLevel]: reasoningLevel },
            default_reasoning_level: reasoningLevel,
          },
        ],
      },
    ],
    mcp_servers: [],
    skills: [],
  };
}

/**
 * Preserves unrelated real-home providers and catalog entries while replacing
 * only the explicitly selected smoke provider in the authoritative document.
 */
function persistentConfigurationDocument(current, selected) {
  const base =
    current !== null && typeof current === "object" && !Array.isArray(current)
      ? current
      : providerConfigurationDocument(selected);
  const selectedDocument = providerConfigurationDocument({
    ...selected,
    revision: Number.isSafeInteger(base.config_revision) ? base.config_revision + 1 : 0,
  });
  const providers = Array.isArray(base.providers)
    ? base.providers.filter((entry) => entry?.provider_id !== selected.selectedProviderId)
    : [];
  return {
    ...base,
    schema_version: 1,
    config_revision: selectedDocument.config_revision,
    default_access_mode:
      base.default_access_mode === "full_access" ? "full_access" : "approval_required",
    default_provider_id: selected.selectedProviderId,
    default_model_id: selected.selectedModelId,
    default_reasoning_level: selected.reasoningLevel,
    providers: [...providers, selectedDocument.providers[0]],
    mcp_servers: Array.isArray(base.mcp_servers) ? base.mcp_servers : [],
    skills: Array.isArray(base.skills) ? base.skills : [],
  };
}

/** 构造 Read + Shell 验收，但让模型仅依据注入环境自行选择 Shell 方言。 */
export function providerToolTurnInput({ inputPath, inputMarker, shellMarker, finalMarker }) {
  for (const value of [inputPath, inputMarker, shellMarker, finalMarker]) {
    if (
      typeof value !== "string" ||
      value.length === 0 ||
      value.length > 256 ||
      /[\r\n]/u.test(value)
    ) {
      throw new Error("provider Tool fixture values are invalid");
    }
  }
  return [
    "Use exactly two built-in tools in this order and no others.",
    `First call read with the JSON arguments {"path":"${inputPath}"}; the file content must contain ${inputMarker}.`,
    `Then call shell exactly once. Determine its command dialect only from the injected execution environment and the shell tool description. Use that dialect's native output primitive, not a cross-shell alias, to write exactly ${shellMarker} to stdout. Wait for the human approval request before execution.`,
    `After both Tool results, reply with exactly ${finalMarker} and no other text.`,
  ].join(" ");
}

/**
 * 证明 Windows 模型调用确实遵循注入的 PowerShell 方言，而不是恰好执行 Bash 也支持的
 * 通用命令；只检查脱敏后的持久 command，不读取 Provider 请求或原始参数。
 */
export function assertWindowsPowerShellSelection(history, turnId, shellMarker) {
  const shell = history?.items?.find(
    (item) => item.kind === "tool_call" && item.turnId === turnId && item.toolName === "shell",
  );
  const command = shell?.presentation?.command;
  const powerShellPrimitive =
    /\b(?:Write-Output|Write-Host|Out-String|Select-Object|Get-ChildItem)\b|\$PSVersionTable|\[Console\]::Write(?:Line)?/iu;
  const posixPrimitive = /\b(?:printf|head|tail|grep|sed|awk)\b/iu;
  if (
    shell?.presentation?.kind !== "shell" ||
    typeof command !== "string" ||
    !command.includes(shellMarker) ||
    !powerShellPrimitive.test(command) ||
    posixPrimitive.test(command)
  ) {
    throw new Error("model did not select the injected Windows PowerShell dialect");
  }
  return "powershell";
}

/** 构造一个确定失败的本机 Shell 调用，模型必须消费稳定错误后给出唯一最终回复。 */
export function providerFailingToolTurnInput({ shellMarker }) {
  if (
    typeof shellMarker !== "string" ||
    shellMarker.length === 0 ||
    shellMarker.length > 256 ||
    /[\r\n]/u.test(shellMarker)
  ) {
    throw new Error("provider failing Tool fixture value is invalid");
  }
  const shellCommand =
    process.platform === "win32"
      ? `Write-Error '${shellMarker}'; exit 23`
      : `printf '%s\n' '${shellMarker}' >&2; exit 23`;
  return [
    "Use exactly one built-in tool and do not use any other tool.",
    `Call shell with the JSON arguments ${JSON.stringify({ command: shellCommand })}.`,
    "The command is expected to fail. After receiving its Tool result, do not retry or call another tool.",
    "Give one concise user-visible final reply explaining that the command failed and what the user can do next.",
  ].join(" ");
}

/** Returns bounded public Tool projections for one Turn without retaining provider request bodies. */
export function collectToolEvidence(events, turnId) {
  if (!Array.isArray(events) || typeof turnId !== "string") {
    throw new Error("Tool evidence input is invalid");
  }
  const toolNames = new Map();
  for (const frame of events) {
    if (
      frame?.method !== "assistant/model-step-committed" ||
      frame.params?.turnId !== turnId ||
      !Array.isArray(frame.params.toolCalls)
    )
      continue;
    for (const call of frame.params.toolCalls) {
      if (
        typeof call?.callId !== "string" ||
        !call.callId.startsWith("call_") ||
        typeof call.toolName !== "string" ||
        call.toolName.length === 0
      ) {
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
      if (
        typeof result?.callId !== "string" ||
        !result.callId.startsWith("call_") ||
        typeof toolName !== "string" ||
        typeof result.outcome !== "string"
      ) {
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

/** 等待精确 Tool 顺序与结果闭集；任何提前终态、额外调用或结果漂移都立即失败。 */
async function waitForRequiredToolEvidence(
  session,
  turnId,
  expected = ["read", "shell"],
  expectedOutcomes = ["succeeded", "succeeded"],
  timeoutMs = requestTimeoutMs,
) {
  const deadline = Date.now() + timeoutMs;
  if (expected.length === 0 || expected.length !== expectedOutcomes.length) {
    throw new Error("expected Tool evidence is invalid");
  }
  while (Date.now() < deadline) {
    const evidence = collectToolEvidence(session.events, turnId);
    const names = evidence.map((entry) => entry.toolName);
    if (names.length > expected.length || names.some((name, index) => name !== expected[index])) {
      throw new Error("provider Tool turn invoked an unexpected Tool or order");
    }
    if (names.length === expected.length) {
      if (evidence.some((entry, index) => entry.outcome !== expectedOutcomes[index])) {
        throw new Error("provider Tool turn outcomes did not match the required sequence");
      }
      return evidence;
    }
    const observedCallIds = new Set(evidence.map((entry) => entry.callId));
    const remaining = Math.max(1, deadline - Date.now());
    const event = await session.waitForAnyEvent(
      (frame) =>
        frame.params?.turnId === turnId &&
        (frame?.method === "turn/terminal" ||
          (frame?.method === "tool/batch-committed" &&
            Array.isArray(frame.params?.results) &&
            frame.params.results.some((result) => !observedCallIds.has(result?.callId)))),
      remaining,
    );
    if (event.method === "turn/terminal") {
      throw new Error(
        `provider Tool turn ended before required Tools: ${event.params?.state ?? "unknown"}`,
      );
    }
  }
  throw new Error("provider Tool evidence timed out");
}

/** 等待并提交一个精确 Tool 审批；所有公开字段先校验，随后才允许产生执行副作用。 */
async function approveExpectedTool(session, turnId, expectedToolName, handledApprovalIds) {
  const approvalOrTerminal = await session.waitForAnyEvent(
    (frame) =>
      frame.params?.turnId === turnId &&
      (frame?.method === "turn/terminal" ||
        (frame?.method === "approval/requested" &&
          !handledApprovalIds.has(frame.params?.approvalId))),
    requestTimeoutMs,
  );
  if (approvalOrTerminal.method === "turn/terminal") {
    let historyKinds = "unavailable";
    try {
      historyKinds = durableItemKinds(
        assertRpcSuccess(
          await session.request("thread/read", {
            threadId: approvalOrTerminal.params?.threadId,
            cursor: null,
            limit: 200,
          }),
          "thread/read failed Tool turn",
        ),
      );
    } catch {
      // The original terminal remains primary; history diagnostics must never hide it.
    }
    throw new Error(
      `provider Tool turn ended before all approvals: ${approvalOrTerminal.params?.state ?? "unknown"}` +
        ` with ${approvalOrTerminal.params?.errorCode ?? "UNKNOWN_ERROR"}` +
        ` ${safeTerminalMessage(approvalOrTerminal.params?.errorMessage)}` +
        ` events=${turnEventMethods(session.events, turnId)}` +
        ` history=${historyKinds}`,
    );
  }
  const approval = approvalOrTerminal.params;
  if (
    typeof approval?.approvalId !== "string" ||
    !approval.approvalId.startsWith("appr_") ||
    typeof approval.callId !== "string" ||
    !approval.callId.startsWith("call_") ||
    approval.toolName !== expectedToolName ||
    typeof approval.reason !== "string" ||
    typeof approval.expiresAt !== "string" ||
    !Number.isSafeInteger(approval.threadRevision)
  ) {
    throw new Error(
      `provider Tool approval had an invalid v1 projection ${JSON.stringify({
        approvalId:
          typeof approval?.approvalId === "string" && approval.approvalId.startsWith("appr_"),
        callId: typeof approval?.callId === "string" && approval.callId.startsWith("call_"),
        tool: approval?.toolName === expectedToolName,
        reason: typeof approval?.reason === "string",
        expiresAt: typeof approval?.expiresAt === "string",
        threadRevision: Number.isSafeInteger(approval?.threadRevision),
      })}`,
    );
  }
  handledApprovalIds.add(approval.approvalId);
  const approvalResponse = assertRpcSuccess(
    await session.request("approval/respond", {
      approvalId: approval.approvalId,
      turnId,
      decision: "approve",
      expectedThreadRevision: approval.threadRevision,
    }),
    "approval/respond",
  );
  if (
    approvalResponse?.accepted !== true ||
    approvalResponse.approvalId !== approval.approvalId ||
    approvalResponse.turnId !== turnId ||
    approvalResponse.decision !== "approve" ||
    !Number.isSafeInteger(approvalResponse.threadRevision)
  ) {
    throw new Error("approval/respond returned an invalid committed projection");
  }
  const resolved = await session.waitForEvent(
    "approval/resolved",
    (frame) => frame.params?.turnId === turnId && frame.params?.approvalId === approval.approvalId,
  );
  if (resolved.params?.decision !== "approve") {
    throw new Error("provider Tool approval resolved with an unexpected decision");
  }
  return approval;
}

/**
 * 按 Turn 身份验证规范 Tool 终态、审批与最终回复，避免同一 Thread 后续 Tool 污染恢复断言。
 * expectation 使用显式闭集而非兼容猜测，使成功与失败 Tool 共享同一条持久化门禁。
 */
export function assertDurableToolHistory(history, threadId, turnId, approvalId, expectation = {}) {
  if (
    history?.threadId !== threadId ||
    !Number.isSafeInteger(history.revision) ||
    !Array.isArray(history.items)
  ) {
    throw new Error("thread/read did not return a durable snapshot");
  }
  const expectedCalls = expectation.calls ?? [
    { toolName: "read", status: "success" },
    { toolName: "shell", status: "success" },
  ];
  if (
    !Array.isArray(expectedCalls) ||
    expectedCalls.length === 0 ||
    expectedCalls.some(
      (call) =>
        typeof call?.toolName !== "string" ||
        !new Set(["success", "error", "cancelled"]).has(call.status) ||
        (Object.hasOwn(call, "exitCode") && !Number.isSafeInteger(call.exitCode)),
    )
  ) {
    throw new Error("durable Tool expectation is invalid");
  }
  const calls = history.items.filter((item) => item.kind === "tool_call" && item.turnId === turnId);
  if (
    calls.length !== expectedCalls.length ||
    calls.some((item, index) => {
      const expected = expectedCalls[index];
      return (
        item.toolName !== expected.toolName ||
        item?.presentation?.status !== expected.status ||
        (Object.hasOwn(expected, "exitCode") && item?.presentation?.exitCode !== expected.exitCode)
      );
    })
  ) {
    throw new Error("thread/read did not persist the required Turn-scoped Tool presentations");
  }
  if (calls.some((item) => typeof item.callId !== "string" || !item.callId.startsWith("call_"))) {
    throw new Error("thread/read did not preserve Tool call identities");
  }
  const approval = history.items.find(
    (item) => item.kind === "approval" && item.approvalId === approvalId && item.turnId === turnId,
  );
  if (
    approval?.decision !== "approve" ||
    !calls.some((call) => call.callId === approval.callId && call.toolName === approval.toolName)
  ) {
    throw new Error("thread/read did not persist the approved shell decision");
  }
  if (
    expectation.finalText !== undefined &&
    (typeof expectation.finalText !== "string" ||
      expectation.finalText.trim().length === 0 ||
      !history.items.some(
        (item) =>
          item.kind === "final_answer" &&
          item.turnId === turnId &&
          item.text === expectation.finalText,
      ))
  ) {
    throw new Error("thread/read did not persist the exact committed Tool Turn reply");
  }
  return {
    items: history.items.length,
    toolCalls: calls.length,
    successfulTools: calls.filter((item) => item.presentation.status === "success").length,
    failedTools: calls.filter((item) => item.presentation.status === "error").length,
  };
}

/** Starts one sanitized production child and completes the v1 ready handshake before returning it. */
async function startProductionSession({ command, prefixArgs, directories, apiKey, endpoint }) {
  const session = new JsonlSession({ command, prefixArgs, directories, apiKey, endpoint });
  try {
    const initialized = assertRpcSuccess(
      await session.request("runtime/initialize", initializeParams()),
      "runtime/initialize",
    );
    if (
      initialized?.runtime?.engine !== "ja-kernel" ||
      typeof initialized?.runtime?.engineVersion !== "string"
    ) {
      throw new Error("sidecar did not start as the Ja Kernel production runtime");
    }
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) =>
        frame.params?.status === "ready" &&
        frame.params?.readyToken === readyToken &&
        frame.params?.generation > 0,
      20_000,
    );
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
  const apiKeyName = "JA_REAL_PROVIDER_API_KEY";
  const endpointName = "JA_REAL_PROVIDER_BASE_URL";
  const modelName = "JA_REAL_PROVIDER_MODEL";
  const apiKey = requiredEnvironment(apiKeyName);
  if (apiKey.length > 8_192 || containsAsciiControl(apiKey)) {
    throw new Error(`${apiKeyName} is invalid`);
  }
  // Minimize the inheritance window before any child is created; the local
  // variable remains available only for the correlated stdin response.
  delete process.env.JA_REAL_PROVIDER_API_KEY;
  const endpoint = validatedLoopbackBaseUrl(requiredEnvironment(endpointName), endpointName);
  const name = (process.env.JA_REAL_PROVIDER_NAME ?? "Authorized loopback provider").trim();
  delete process.env.JA_REAL_PROVIDER_NAME;
  if (name.length === 0 || name.length > 512 || containsAsciiControl(name)) {
    throw new Error("JA_REAL_PROVIDER_NAME is invalid");
  }
  const api = (process.env.JA_REAL_PROVIDER_API ?? "openai_responses").trim();
  if (!new Set(["anthropic_messages", "openai_chat_completions", "openai_responses"]).has(api)) {
    throw new Error("JA_REAL_PROVIDER_API is unsupported");
  }
  delete process.env.JA_REAL_PROVIDER_API;
  const model = process.env[modelName]?.trim() ?? "";
  if (model.length === 0 || model.length > 128 || /[\r\n]/u.test(model)) {
    throw new Error(`${modelName} is invalid`);
  }
  const reasoningLevel = (process.env.JA_REAL_PROVIDER_REASONING_LEVEL ?? "medium").trim();
  delete process.env.JA_REAL_PROVIDER_REASONING_LEVEL;
  if (!reasoningLevels.has(reasoningLevel)) {
    throw new Error("JA_REAL_PROVIDER_REASONING_LEVEL is unsupported");
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

    const configuration = assertRpcSuccess(
      await session.request("configuration/read", {}),
      "configuration/read",
    );
    if (
      !persistent &&
      (configuration?.cas?.userVersion !== "cfg_missing" ||
        configuration?.cas?.credentialVersion !== "cfg_missing")
    ) {
      throw new Error("fresh smoke home did not report missing configuration generations");
    }
    const selectedConfiguration = {
      endpoint,
      name,
      api,
      model,
      reasoningLevel,
      selectedProviderId,
      selectedModelId,
    };
    const document = persistent
      ? persistentConfigurationDocument(configuration?.user?.document, selectedConfiguration)
      : providerConfigurationDocument(selectedConfiguration);
    const configured = assertRpcSuccess(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        document,
      }),
      "configuration/replace",
    );
    if (
      configured?.accepted !== true ||
      configured?.scope !== "user" ||
      typeof configured?.version !== "string"
    ) {
      throw new Error("configuration/replace returned an invalid result");
    }
    const credential = assertRpcSuccess(
      await session.request("credential/set", {
        credentialId,
        secret: apiKey,
        expectedVersion: configuration.cas.credentialVersion,
      }),
      "credential/set",
    );
    if (
      credential?.accepted !== true ||
      credential?.credentialId !== credentialId ||
      credential?.configured !== true ||
      typeof credential?.version !== "string"
    ) {
      throw new Error("credential/set returned an invalid redacted projection");
    }

    const workspace = assertRpcSuccess(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Real provider smoke",
      }),
      "workspace/open",
    );
    if (typeof workspace?.workspaceId !== "string" || !workspace.workspaceId.startsWith("ws_")) {
      throw new Error("workspace/open returned an invalid workspace identity");
    }

    const created = assertRpcSuccess(
      await session.request("thread/create", {
        cwd: directories.workspace,
        title: "Real provider smoke",
        providerId: selectedProviderId,
        modelId: selectedModelId,
        reasoningLevel,
        accessMode: "approval_required",
        collaborationMode: "default",
      }),
      "thread/create",
    );
    const threadId = created?.threadId;
    if (typeof threadId !== "string" || !threadId.startsWith("thr_")) {
      throw new Error("thread/create returned an invalid thread identity");
    }
    if (created.workspaceId !== workspace.workspaceId) {
      throw new Error("thread/create did not retain Java's workspace identity");
    }
    const textMarker = `JA_REAL_PROVIDER_TEXT_${Date.now().toString(36)}`;
    const textAccepted = assertRpcSuccess(
      await session.request("turn/start", {
        threadId,
        content: [{ type: "text", text: `Reply with exactly ${textMarker}. Do not call tools.` }],
      }),
      "turn/start",
    );
    const textTurnId = textAccepted?.turnId;
    if (typeof textTurnId !== "string" || !textTurnId.startsWith("turn_")) {
      throw new Error("text turn/start returned an invalid turn identity");
    }
    const textTerminal = await session.waitForEvent(
      "turn/terminal",
      (frame) => frame.params?.turnId === textTurnId,
    );
    if (textTerminal.params?.state !== "completed") {
      // Only the closed error code is safe to expose here; Provider messages and stderr remain
      // bounded inside the smoke so a failed live call cannot turn diagnostics into a body leak.
      throw new Error(
        `real provider text turn ended as ${textTerminal.params?.state ?? "unknown"}` +
          ` with ${textTerminal.params?.errorCode ?? "UNKNOWN_ERROR"}` +
          ` ${safeTerminalMessage(textTerminal.params?.errorMessage)}` +
          ` events=${turnEventMethods(session.events, textTurnId)}`,
      );
    }
    const textTerminalCount = session.events.filter(
      (frame) => frame.method === "turn/terminal" && frame.params?.turnId === textTurnId,
    ).length;
    const textFinalReply = committedTerminalReply(textTerminal, textTurnId);
    if (textTerminalCount !== 1 || textFinalReply === null) {
      throw new Error(
        "real provider text stream did not publish one committed non-empty terminal reply",
      );
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
    const toolAccepted = assertRpcSuccess(
      await session.request("turn/start", {
        threadId,
        content: [
          {
            type: "text",
            text: providerToolTurnInput({
              inputPath,
              inputMarker,
              shellMarker,
              finalMarker: toolFinalMarker,
            }),
          },
        ],
      }),
      "turn/start Tool",
    );
    const toolTurnId = toolAccepted?.turnId;
    if (typeof toolTurnId !== "string" || !toolTurnId.startsWith("turn_")) {
      throw new Error("Tool turn/start returned an invalid turn identity");
    }
    const approvedTools = [];
    const handledApprovalIds = new Set();
    for (const expectedToolName of ["read", "shell"]) {
      approvedTools.push(
        await approveExpectedTool(session, toolTurnId, expectedToolName, handledApprovalIds),
      );
    }
    const shellApproval = approvedTools.find((approval) => approval.toolName === "shell");
    const toolEvidence = await waitForRequiredToolEvidence(session, toolTurnId);
    const readEvidence = toolEvidence.find((entry) => entry.toolName === "read");
    const shellEvidence = toolEvidence.find((entry) => entry.toolName === "shell");
    const toolEventsJson = JSON.stringify(session.events);
    if (
      readEvidence === undefined ||
      shellEvidence === undefined ||
      !toolEventsJson.includes(inputMarker) ||
      !toolEventsJson.includes(shellMarker)
    ) {
      throw new Error("provider Tool results did not prove the bounded fixture inputs");
    }
    const toolTerminal = await session.waitForEvent(
      "turn/terminal",
      (frame) => frame.params?.turnId === toolTurnId,
    );
    const toolFinalReply = committedTerminalReply(toolTerminal, toolTurnId);
    if (toolFinalReply === null) {
      throw new Error("provider Tool turn did not publish one committed non-empty terminal reply");
    }

    const failedShellMarker = `JA_REAL_PROVIDER_EXPECTED_FAILURE_${Date.now().toString(36)}`;
    const failedToolAccepted = assertRpcSuccess(
      await session.request("turn/start", {
        threadId,
        content: [
          {
            type: "text",
            text: providerFailingToolTurnInput({
              shellMarker: failedShellMarker,
            }),
          },
        ],
      }),
      "turn/start failing Tool",
    );
    const failedToolTurnId = failedToolAccepted?.turnId;
    if (typeof failedToolTurnId !== "string" || !failedToolTurnId.startsWith("turn_")) {
      throw new Error("failing Tool turn/start returned an invalid turn identity");
    }
    const failedToolApproval = await approveExpectedTool(
      session,
      failedToolTurnId,
      "shell",
      handledApprovalIds,
    );
    approvedTools.push(failedToolApproval);
    const failedToolEvidence = await waitForRequiredToolEvidence(
      session,
      failedToolTurnId,
      ["shell"],
      ["failed"],
    );
    const failedToolTerminal = await session.waitForEvent(
      "turn/terminal",
      (frame) => frame.params?.turnId === failedToolTurnId,
    );
    const failedToolEvents = collectToolEvidence(session.events, failedToolTurnId);
    const failedToolTerminalCount = session.events.filter(
      (frame) => frame.method === "turn/terminal" && frame.params?.turnId === failedToolTurnId,
    ).length;
    const failedToolEventsJson = JSON.stringify(
      session.events.filter((frame) => frame.params?.turnId === failedToolTurnId),
    );
    const failedToolFinalReply = committedTerminalReply(failedToolTerminal, failedToolTurnId);
    if (
      failedToolTerminal.params?.state !== "completed" ||
      failedToolTerminalCount !== 1 ||
      failedToolEvents.length !== 1 ||
      failedToolEvidence[0]?.outcome !== "failed" ||
      !failedToolEventsJson.includes(failedShellMarker) ||
      failedToolFinalReply === null
    ) {
      throw new Error(
        `failed Tool result did not converge to one marker-bearing completed Turn ${JSON.stringify({
          state: failedToolTerminal.params?.state ?? "unknown",
          terminalCount: failedToolTerminalCount,
          toolResultCount: failedToolEvents.length,
          outcomes: failedToolEvents.map((entry) => entry.outcome),
          shellMarkerObserved: failedToolEventsJson.includes(failedShellMarker),
          finalReplyCommitted: failedToolFinalReply !== null,
          methods: turnEventMethods(session.events, failedToolTurnId),
        })}`,
      );
    }

    const history = assertRpcSuccess(
      await session.request("thread/read", { threadId }),
      "thread/read",
    );
    if (
      !history.items.some((item) => userInputContainsText(item, textMarker)) ||
      !history.items.some(
        (item) =>
          item.kind === "final_answer" &&
          item.turnId === textTurnId &&
          item.text === textFinalReply,
      )
    ) {
      throw new Error("thread/read did not restore all real provider final answers");
    }
    const durable = assertDurableToolHistory(
      history,
      threadId,
      toolTurnId,
      shellApproval.approvalId,
      { finalText: toolFinalReply },
    );
    const shellDialect =
      process.platform === "win32"
        ? assertWindowsPowerShellSelection(history, toolTurnId, shellMarker)
        : "platform_not_checked";
    const failedDurable = assertDurableToolHistory(
      history,
      threadId,
      failedToolTurnId,
      failedToolApproval.approvalId,
      {
        calls: [{ toolName: "shell", status: "error", exitCode: 23 }],
        finalText: failedToolFinalReply,
      },
    );
    const firstSessionRetainedOutput = `${JSON.stringify(session.events)}\n${session.stderr}`;
    const firstTokenMarkerPaths = [...session.tokenMarkerPaths];
    const firstExit = await session.shutdown();
    if (firstExit.code !== 0 || firstExit.signal !== null) {
      throw new Error(`production sidecar first exit had code ${firstExit.code ?? "none"}`);
    }

    let restartedInitialized;
    ({ session, initialized: restartedInitialized } = await startProductionSession(launch));
    if (restartedInitialized?.runtime?.engine !== "ja-kernel") {
      throw new Error("restarted sidecar did not negotiate the Ja Kernel runtime");
    }
    const afterRestart = assertRpcSuccess(
      await session.request("configuration/read", {}),
      "configuration/read after restart",
    );
    if (
      afterRestart?.cas?.userVersion !== configured.version ||
      afterRestart?.cas?.credentialVersion !== credential.version ||
      afterRestart?.credentials?.[credentialId]?.configured !== true
    ) {
      throw new Error(
        "sidecar restart did not recover the Java-owned configuration and credential generation",
      );
    }
    const recoveredHistory = assertRpcSuccess(
      await session.request("thread/read", { threadId }),
      "thread/read after restart",
    );
    const recovered = assertDurableToolHistory(
      recoveredHistory,
      threadId,
      toolTurnId,
      shellApproval.approvalId,
      { finalText: toolFinalReply },
    );
    const failedRecovered = assertDurableToolHistory(
      recoveredHistory,
      threadId,
      failedToolTurnId,
      failedToolApproval.approvalId,
      {
        calls: [{ toolName: "shell", status: "error", exitCode: 23 }],
        finalText: failedToolFinalReply,
      },
    );
    if (recoveredHistory.revision < history.revision) {
      throw new Error("sidecar restart recovered an older Thread revision");
    }
    if (
      !recoveredHistory.items.some(
        (item) =>
          item.kind === "final_answer" &&
          item.turnId === textTurnId &&
          item.text === textFinalReply,
      )
    ) {
      throw new Error("sidecar restart did not recover the committed text reply");
    }
    if (!persistent) {
      const cleared = assertRpcSuccess(
        await session.request("credential/delete", {
          credentialId,
          expectedVersion: afterRestart.cas.credentialVersion,
        }),
        "credential/delete after restart",
      );
      if (
        cleared?.accepted !== true ||
        cleared?.credentialId !== credentialId ||
        cleared?.configured !== false ||
        typeof cleared?.version !== "string"
      ) {
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
    const retainedOutput = `${firstSessionRetainedOutput}\n${JSON.stringify(session.events)}\n${session.stderr}\n${await readFile(
      join(directories.logs, "app-server.log"),
      "utf8",
    )}`;
    if (retainedOutput.includes(apiKey) || retainedOutput.includes(endpoint)) {
      throw new Error("provider credential or endpoint escaped the redacted runtime boundary");
    }
    if (!persistent) await assertSecretAbsentFromOwnedFiles(directories.root, apiKey);
    const report = {
      status: "passed",
      runtime: "production",
      engineVersion: initialized.runtime.engineVersion,
      providerName: name,
      api,
      model,
      reasoningLevel,
      textTurnStatus: textTerminal.params.state,
      toolTurnStatus: toolTerminal.params.state,
      failedToolTurnStatus: failedToolTerminal.params.state,
      failedToolOutcome: failedToolEvidence[0].outcome,
      failedToolExitCode: 23,
      failedToolFinalReply: failedDurable.failedTools === 1 && failedRecovered.failedTools === 1,
      toolNames: toolEvidence.map((entry) => entry.toolName),
      shellDialect,
      approvedToolNames: approvedTools.map((approval) => approval.toolName),
      approvalResolved: true,
      historyItems: durable.items,
      recoveredHistoryItems: recovered.items,
      restartRecovered: true,
      exitCode: exit.code,
      firstExitCode: firstExit.code,
      tokenMarkerPaths: [...new Set([...firstTokenMarkerPaths, ...session.tokenMarkerPaths])],
      logBytes: logMetadata.size,
    };
    if (!silent) process.stdout.write(`${JSON.stringify(report)}\n`);
    return report;
  } catch (error) {
    const diagnostic =
      session?.stderr === "" || session === undefined
        ? ""
        : " (sidecar stderr was captured and redacted)";
    const signals = stderrDiagnosticSignals(session?.stderr);
    const classified = signals === "" ? "" : ` [signals=${signals}]`;
    throw new Error(
      `${redact(error?.message ?? error, [apiKey, endpoint])}${classified}${diagnostic}`,
    );
  } finally {
    await session?.forceClose();
    if (toolFixturePath !== undefined) await rm(toolFixturePath, { force: true });
    if (!persistent) await cleanupIsolatedDirectories(directories.root);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await runSmoke().catch((error) => {
    process.stderr.write(
      `real-provider-smoke failed: ${redact(error?.message ?? error, [process.env.JA_REAL_PROVIDER_API_KEY])}\n`,
    );
    process.exitCode = 1;
  });
}
