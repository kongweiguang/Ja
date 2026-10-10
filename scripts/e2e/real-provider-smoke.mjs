// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 验证生产 sidecar 与经明确授权的 loopback 端点交互；端点须实现受支持的 API 规范之一。
 * 自定义 Provider 名称不会决定协议适配器。API key 只从父进程环境接收，并从 Java 子进程环境中移除；
 * 它只会通过 stdin 中的 v2 credential/set 请求传给 sidecar。
 */

import { randomBytes } from "node:crypto";
import { execFile, spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { mkdir, mkdtemp, readdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { createInterface } from "node:readline";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

/** 生成 v2 操作回执键；重试复用同一键以避免重复产生副作用。 */
export function createOperationKey() {
  return `op_${randomBytes(16).toString("hex")}`;
}

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDirectory, "..", "..");
const errorCatalog = JSON.parse(
  readFileSync(join(repoRoot, "contracts", "ja-rpc", "v2", "error-catalog.json"), "utf8"),
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
const itemContentPageLimit = 65_536;
const methods = [
  "runtime/initialize",
  "runtime/health",
  "runtime/storage/retry",
  "runtime/shutdown",
  "runtime/context/register",
  "runtime/client/close",
  "operation/read",
  "workspace/open",
  "workspace/read",
  "workspace/update",
  "workspace/list",
  "workspace/path/search",
  "workspace/set-trust",
  "workspace/unregister",
  "thread/create",
  "thread/fork",
  "thread/list",
  "thread/search",
  "thread/read",
  "thread/item-content/read",
  "history/input/search",
  "thread/observe",
  "thread/unobserve",
  "thread/usage/read",
  "thread/mcp/read",
  "thread/rename",
  "thread/pin",
  "thread/seen",
  "thread/preferences/update",
  "thread/archive",
  "thread/restore",
  "thread/delete",
  "thread/compact",
  "thread/compact/cancel",
  "interaction/read",
  "interaction/observe",
  "interaction/unobserve",
  "interaction/draft/save",
  "interaction/respond",
  "interaction/cancel",
  "plan/read",
  "plan/revisions/list",
  "plan/current/read",
  "plan/events/read",
  "plan/observe",
  "plan/unobserve",
  "plan/evidence/list",
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
  "turn/continue",
  "turn/reask",
  "turn/resume",
  "turn/recovery/respond",
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
  "credential/reveal-provider",
  "skill/list",
  "mcp/list",
  "mcp/test",
  "model/test",
  "model/discover",
  "mcp/list-tools",
  "tool/artifact/read",
  "process/list",
  "process/read",
  "process/write",
  "process/stop",
  "review/start",
  "review/read",
];
const events = [
  "runtime/status-changed",
  "turn/state-changed",
  "turn/input-queue-changed",
  "turn/input-consumed",
  "turn/retry-started",
  "turn/messages_received",
  "assistant/model-step-committed",
  "assistant/context-estimated",
  "assistant/text-delta",
  "assistant/reasoning-summary-delta",
  "tool/started",
  "tool/batch-committed",
  "tool/recovery-changed",
  "approval/requested",
  "approval/resolved",
  "approval/closed",
  "context/compaction-started",
  "context/compacted",
  "context/compaction-failed",
  "workspace/dirty",
  "workspace/metadata-changed",
  "turn/terminal",
  "turn/resync-required",
  "thread/metadata-changed",
  "configuration/changed",
  "task/activity",
  "task/progress",
  "task/mailbox-changed",
  "task/closed",
  "interaction/changed",
  "plan/changed",
];

/** 读取一个必需的环境变量，出错信息中绝不包含其值。 */
export function requiredEnvironment(name) {
  const value = process.env[name];
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${name} is required`);
  }
  return value;
}

/** 拒绝 ASCII 控制字符，同时避免在面向诊断的正则中放入控制字符转义。 */
function containsAsciiControl(value) {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && (codePoint <= 0x1f || codePoint === 0x7f);
  });
}

/**
 * 将此携带凭据的 smoke 限定为 loopback HTTP 端点，
 * 防止继承来的地址拼写错误把明确提供的 key 发往远程主机；
 * 同时仍接受 localhost 和两种 IP loopback 地址。
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

/** 定位刚构建的指定 sidecar 产物，并拒绝缺失或空的 JAR。 */
async function resolveJar() {
  const jar = resolve(
    process.env.JA_REAL_PROVIDER_JAR ??
      join(repoRoot, "ja-agent/ja-app-server", "target", "ja-app-server.jar"),
  );
  const metadata = await stat(jar);
  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error("the production sidecar jar is missing or empty");
  }
  return jar;
}

/** 优先使用 runner 传入的 Java 可执行文件，之后才考虑 PATH 回退。 */
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

/** 验证真实 JAR 使用 Java 25，同时不保存厂商版本横幅。 */
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

/**
 * 创建 fixture 前先解析操作系统临时目录，
 * 避免生产路径检查把 macOS 的 /var -> /private/var 系统别名误判为调用方提供的符号链接。
 */
async function physicalTemporaryDirectory() {
  return resolve(await realpath(tmpdir()));
}

/** 在实际临时父目录下创建四个不同的 Unicode 根目录，用于验证规范 UTF-8 路径归属。 */
export async function createIsolatedDirectories() {
  const root = await mkdtemp(join(await physicalTemporaryDirectory(), temporaryPrefix));
  const home = join(root, "home-家");
  const data = join(root, "data-数据");
  const run = join(root, "run-运行");
  const logs = join(root, "log-日志");
  const workspace = join(root, "workspace-工作区");
  await Promise.all([home, data, run, logs, workspace].map((path) => mkdir(path)));
  return { root, home, data, run, logs, workspace };
}

/**
 * 使用操作人员指定的真实 Ja home，并在 smoke 结束后保留配置、
 * 凭据状态、SQLite 历史和日志。
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
 * 只删除本脚本创建的那个 mkdtemp 子目录。实际父目录检查与 fixture 创建时一致，
 * 若归属发生变化，递归清理会拒绝继续。
 */
async function cleanupIsolatedDirectories(root) {
  const target = resolve(root);
  if (
    dirname(target) !== (await physicalTemporaryDirectory()) ||
    !target.split(/[\\/]/).at(-1)?.startsWith(temporaryPrefix)
  ) {
    throw new Error("refusing to clean a non-owned smoke directory");
  }
  await rm(target, { recursive: true, force: false });
}

/** 使用与 Rust 相同的无填充 Base64URL 约定编码规范数据路径。 */
export function encodedDirectory(directory) {
  return Buffer.from(resolve(directory), "utf8").toString("base64url");
}

/** 在诊断信息写入 stderr 前替换所有包含凭据的值。 */
function redact(value, secrets) {
  let output = String(value ?? "");
  for (const secret of secrets) {
    if (secret) {
      output = output.replaceAll(secret, "<redacted>");
    }
  }
  return output;
}

/** 从脱敏后的 stderr 中只提取有界的异常类型及 Ja 自有的 Provider 错误码和详情。 */
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

/** 只允许不含路径和正文的终态摘要进入实时 smoke 诊断。 */
function safeTerminalMessage(value) {
  if (typeof value !== "string" || value.length === 0 || value.length > 256)
    return "NO_SAFE_MESSAGE";
  return /^[A-Za-z0-9 _-]+$/u.test(value) ? value : "UNSAFE_ERROR_MESSAGE";
}

/** 只返回单个 Turn 的有界 JA-RPC 方法序列，不保留事件载荷。 */
function turnEventMethods(events, turnId) {
  return events
    .filter((frame) => frame?.params?.turnId === turnId && typeof frame.method === "string")
    .slice(-24)
    .map((frame) => frame.method)
    .join(",");
}

/** 按 v2 Unicode code point 游标读取公开 item 正文；任何身份、长度或进度漂移都立即失败。 */
export async function readCompleteItemContent(session, threadId, itemId) {
  if (
    !/^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u.test(threadId) ||
    !/^item_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u.test(itemId)
  ) {
    throw new Error("thread/item content identity is invalid");
  }
  let offsetCharacters = 0;
  let totalCharacters;
  const chunks = [];
  for (;;) {
    const page = assertRpcSuccess(
      await session.request("thread/item-content/read", {
        threadId,
        itemId,
        offsetCharacters,
        limitCharacters: itemContentPageLimit,
      }),
      "thread/item-content/read",
    );
    if (
      page?.itemId !== itemId ||
      page.offsetCharacters !== offsetCharacters ||
      !Number.isSafeInteger(page.totalCharacters) ||
      page.totalCharacters < 0 ||
      (totalCharacters !== undefined && page.totalCharacters !== totalCharacters) ||
      typeof page.content !== "string" ||
      typeof page.truncated !== "boolean"
    ) {
      throw new Error("thread/item content page identity or length is invalid");
    }
    const end = page.nextOffsetCharacters ?? page.totalCharacters;
    const pageCharacters = Array.from(page.content).length;
    if (
      page.truncated !== (page.nextOffsetCharacters !== null) ||
      !Number.isSafeInteger(end) ||
      (end <= offsetCharacters && page.truncated) ||
      end < offsetCharacters ||
      end > page.totalCharacters ||
      pageCharacters !== end - offsetCharacters ||
      pageCharacters > itemContentPageLimit
    ) {
      throw new Error("thread/item content page cursor is invalid");
    }
    chunks.push(page.content);
    offsetCharacters = end;
    totalCharacters = page.totalCharacters;
    if (!page.truncated) return chunks.join("");
  }
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

/** 只返回有界的持久化条目类型序列，不包含消息或 Tool 载荷。 */
function durableItemKinds(history) {
  if (!Array.isArray(history?.items)) return "unavailable";
  return history.items
    .slice(-24)
    .map((item) =>
      typeof item?.kind === "string" && /^[a-z_]{1,48}$/u.test(item.kind) ? item.kind : "invalid",
    )
    .join(",");
}

/** 在固定字节预算内，只扫描本 smoke 所有的普通文件以查找持久化凭据。 */
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
 * 只记录值符合握手 token 的 32 位十六进制格式的 JSON 路径；
 * 值本身不会进入诊断结果。同时排除合法的 readyToken 字段，
 * 以便生产事件能暴露误报防护是否生效。
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

/** 创建有期限的 Promise，不引入任意的稳定等待。 */
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
 * 管理一个 Java 子进程及其 JSONL 关联表，只保留有界的事件摘要。
 * 直接门禁验证 Java 所有的 read 和 shell 路径，
 * 不再保留已移除的反向 Host Tool 协议。
 */
export class JsonlSession {
  /** 固定隔离 profile 并显式选择服务代际，使同一 SQLite 可验证重启恢复；默认代际维持既有验收行为。 */
  constructor({ command, prefixArgs, directories, apiKey, endpoint, runtimeGeneration = 1 }) {
    if (!Number.isSafeInteger(runtimeGeneration) || runtimeGeneration < 1) {
      throw new Error("runtime generation must be a positive safe integer");
    }
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
      `--ja-runtime-generation=${runtimeGeneration}`,
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
        this.failOutstanding(new Error(this.exitDiagnostic(code)));
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

  /**
   * 返回有界且已脱敏的 stderr 末尾内容及退出标识，
   * 让 CI 中仅原生环境出现的故障仍可诊断，同时不暴露任意子进程输出或凭据。
   */
  exitDiagnostic(code) {
    const diagnostic = this.stderr.trim();
    const suffix = diagnostic.length === 0 ? "" : `: ${diagnostic.slice(-2_000)}`;
    return `production sidecar exited with code ${code ?? "none"}${suffix}`;
  }

  /** 写入一条完整 JSONL 帧，并在进程退出后拒绝请求。 */
  send(frame) {
    if (this.protocolFailure !== undefined) {
      throw this.protocolFailure;
    }
    if (this.exited || !this.child.stdin.writable) {
      throw new Error("production sidecar stdin is unavailable");
    }
    this.child.stdin.write(`${JSON.stringify(frame)}\n`);
  }

  /** 在有限协议期限内发送一条具有关联标识的客户端请求。 */
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

  /** 收到协商响应后，发送唯一的 initialized 通知。 */
  notifyInitialized() {
    this.send({ jsonrpc: "2.0", method: "runtime/initialized", params: { readyToken } });
  }

  /** 等待权威事件条件，并先检查已经缓冲的帧。 */
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

  /** 等待多个权威事件之一，使失败的 Turn 能在完整期限结束前报告。 */
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

  /** 解析一条有界的子进程帧，并分别分发响应、请求和通知。 */
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
   * 收到任何反向请求都会关闭连接，不伪造确认；
   * 这里出现服务端请求即证明直接 Provider 边界已发生偏移。
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

  /** 将协议违规设为不可恢复状态，避免后续帧重新激活已失败的会话。 */
  failProtocol(error) {
    if (this.protocolFailure === undefined) this.protocolFailure = error;
    this.failOutstanding(this.protocolFailure);
  }

  /** 拒绝所有等待中的请求，且不暴露原始子进程帧或凭据。 */
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

  /**
   * 仅当刚完成的 Turn 仍在释放协调器、服务端返回无副作用的 INVALID_STATE 拒绝时重试；
   * 所有重试共用现有的有界关闭时限。
   */
  async shutdown() {
    const deadline = Date.now() + exitTimeoutMs;
    let retryDelayMs = 25;
    let lastBusyResponse;
    let result;
    while (true) {
      const remainingMs = deadline - Date.now();
      if (remainingMs <= 0) {
        if (lastBusyResponse !== undefined) {
          assertRpcSuccess(lastBusyResponse, "runtime/shutdown");
        }
        throw new Error("runtime/shutdown exceeded its bounded drain budget");
      }
      const response = await this.request("runtime/shutdown", {}, remainingMs);
      if (response?.error !== undefined) {
        const code = requireRpcErrorCode(response.error);
        if (code !== "INVALID_STATE") assertRpcSuccess(response, "runtime/shutdown");
        lastBusyResponse = response;
        await new Promise((resolveDelay) =>
          setTimeout(resolveDelay, Math.min(retryDelayMs, remainingMs)),
        );
        retryDelayMs = Math.min(retryDelayMs * 2, 250);
        continue;
      }
      result = assertRpcSuccess(response, "runtime/shutdown");
      break;
    }
    if (result?.status !== "shutting_down") {
      throw new Error("shutdown returned an unexpected status");
    }
    this.child.stdin.end();
    return withTimeout(this.exitPromise, exitTimeoutMs, "production sidecar exit");
  }

  /** 断言失败导致无法正常关闭时，只终止当前这个子进程。 */
  async forceClose() {
    this.lines?.close();
    if (!this.exited) {
      this.child.kill("SIGKILL");
      await withTimeout(this.exitPromise, 5_000, "forced sidecar exit").catch(() => undefined);
    }
  }
}

/**
 * 付费 smoke 启动前在本地拒绝能力列表偏移。通过数组精确比较移除过期变更面，
 * 不与过期 sidecar 静默协商出一个子集。
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
      JSON.stringify(["task_threads", "plan", "interaction"])
  ) {
    throw new Error("direct provider capabilities do not match JA-RPC v2");
  }
  return capabilities;
}

/**
 * 使用服务端完整且有序的 v2 词汇表，因为握手校验将能力列表视为冻结的协议合同，
 * 即使本 smoke 只覆盖其中一部分方法也必须如此。
 */
export function initializeParams() {
  const capabilities = assertDirectProviderCapabilities({
    methods: [...methods],
    events: [...events],
    accessModes: ["approval_required", "full_access"],
    collaborationModes: ["default", "plan"],
    features: ["task_threads", "plan", "interaction"],
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
      maxTurnQueuedInputs: 8,
      maxTurnQueuedInputBytes: 524_288,
      maxSnapshotPageItems: 200,
      maxToolBatchConcurrency: 8,
    },
  };
}

/** 构造完整的 v2 无 Secret 供应商配置，确保真实验收不靠已移除的 schema 或配置别名启动。 */
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
    throw new Error("unsupported API specification for v2 provider document");
  }
  if (!reasoningLevels.has(reasoningLevel)) {
    throw new Error("unsupported reasoning level for v2 provider document");
  }
  return {
    schema_version: 2,
    config_revision: revision,
    default_access_mode: "approval_required",
    interaction: { clarification_enabled: true },
    default_provider_id: selectedProviderId,
    default_model_id: selectedModelId,
    default_reasoning_level: reasoningLevel,
    subagents: { enabled: true, provider_id: null, model_id: null, reasoning_level: null },
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
    disabled_skills: [],
  };
}

/**
 * 持久化 smoke 只替换当前选定 Provider，同时强制输出完整 v2 根文档，避免旧 schema 或缺失
 * subagents 字段被真实用户配置的残留内容重新带回验收路径。
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
    schema_version: 2,
    config_revision: selectedDocument.config_revision,
    default_access_mode:
      base.default_access_mode === "full_access" ? "full_access" : "approval_required",
    interaction:
      base.interaction !== null &&
      typeof base.interaction === "object" &&
      !Array.isArray(base.interaction)
        ? base.interaction
        : { clarification_enabled: true },
    default_provider_id: selected.selectedProviderId,
    default_model_id: selected.selectedModelId,
    default_reasoning_level: selected.reasoningLevel,
    subagents:
      base.subagents !== null &&
      typeof base.subagents === "object" &&
      !Array.isArray(base.subagents)
        ? base.subagents
        : { enabled: true, provider_id: null, model_id: null, reasoning_level: null },
    providers: [...providers, selectedDocument.providers[0]],
    mcp_servers: Array.isArray(base.mcp_servers) ? base.mcp_servers : [],
    disabled_skills: Array.isArray(base.disabled_skills) ? base.disabled_skills : [],
  };
}

/** 构造 Read + exec_command 验收，Shell 方言仍由模型依据注入环境自行选择。 */
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
    `Then call exec_command exactly once with cmd and yield_time_ms (use 10000 for the initial wait). Determine the command dialect only from the injected execution environment and the tool description; use that dialect's native output primitive to write exactly ${shellMarker} to stdout. Wait for the human approval request before execution.`,
    `After both Tool results, reply with exactly ${finalMarker} and no other text.`,
  ].join(" ");
}

/**
 * 证明 Windows 模型调用确实遵循注入的 PowerShell 方言，而不是恰好执行 Bash 也支持的
 * 通用命令；只检查脱敏后的持久 command，不读取 Provider 请求或原始参数。
 */
export function assertWindowsPowerShellSelection(history, turnId, shellMarker) {
  const execCommand = history?.items?.find(
    (item) => item.kind === "tool_call" && item.turnId === turnId && item.toolName === "exec_command",
  );
  const command = execCommand?.presentation?.command;
  const powerShellPrimitive =
    /\b(?:Write-Output|Write-Host|Out-String|Select-Object|Get-ChildItem)\b|\$PSVersionTable|\[Console\]::Write(?:Line)?/iu;
  const posixPrimitive = /\b(?:printf|head|tail|grep|sed|awk)\b/iu;
  if (
    execCommand?.presentation?.kind !== "shell" ||
    typeof command !== "string" ||
    !command.includes(shellMarker) ||
    !powerShellPrimitive.test(command) ||
    posixPrimitive.test(command)
  ) {
    throw new Error("model did not select the injected Windows PowerShell dialect");
  }
  return "powershell";
}

/** 构造一个确定失败的 exec_command 调用，模型须消费稳定错误后给出唯一最终回复。 */
export function providerFailingToolTurnInput({ shellMarker }) {
  if (
    typeof shellMarker !== "string" ||
    shellMarker.length === 0 ||
    shellMarker.length > 256 ||
    /[\r\n]/u.test(shellMarker)
  ) {
    throw new Error("provider failing Tool fixture value is invalid");
  }
  const command =
    process.platform === "win32"
      ? `Write-Error '${shellMarker}'; exit 23`
      : `printf '%s\n' '${shellMarker}' >&2; exit 23`;
  return [
    "Use exactly one built-in tool and do not use any other tool.",
    `Call exec_command with the JSON arguments ${JSON.stringify({ cmd: command, timeout_ms: 60_000, yield_time_ms: 10_000 })}.`,
    "The command is expected to fail. After receiving its Tool result, do not retry or call another tool.",
    "Give one concise user-visible final reply explaining that the command failed and what the user can do next.",
  ].join(" ");
}

/** 返回单个 Turn 的有界公开 Tool 摘要，不保留 Provider 请求正文。 */
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
  expected = ["read", "exec_command"],
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
      // 原始 turn/terminal 事件仍是主要依据；历史诊断不得将它掩盖。
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
    { toolName: "exec_command", status: "success" },
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

/** 启动环境经过净化的生产子进程，并完成严格 v2 ready 握手后再返回。 */
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

/**
 * 使用 v2 修改操作要求的唯一操作标识运行文本和已批准的 Tool Turn，
 * 然后重新打开同一个 SQLite home 并检查持久化恢复。
 */
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
  // 在创建子进程前尽量缩短环境继承窗口；本地变量
  // 只用于与请求关联的 stdin 响应。
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
    if (
      typeof workspace?.workspaceId !== "string" ||
      !workspace.workspaceId.startsWith("ws_") ||
      workspace.kind !== "project"
    ) {
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
    if (created.workspaceId !== workspace.workspaceId || created.workspaceKind !== "project") {
      throw new Error("thread/create did not retain Java's workspace identity");
    }
    const textMarker = `JA_REAL_PROVIDER_TEXT_${Date.now().toString(36)}`;
    const textAccepted = assertRpcSuccess(
      await session.request("turn/start", {
        threadId,
        operationKey: createOperationKey(),
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
      // 此处只允许暴露封闭集合中的错误码；Provider 消息和 stderr
      // 在 smoke 内始终有界，避免实时调用失败时诊断信息泄露正文。
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
    const textFinalContent = await readCompleteItemContent(
      session,
      threadId,
      textTerminal.params.finalMessage.messageId,
    );
    if (textFinalContent !== textFinalReply) {
      throw new Error("thread/item content did not reproduce the committed assistant reply");
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
        operationKey: createOperationKey(),
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
    for (const expectedToolName of ["read", "exec_command"]) {
      approvedTools.push(
        await approveExpectedTool(session, toolTurnId, expectedToolName, handledApprovalIds),
      );
    }
    const shellApproval = approvedTools.find((approval) => approval.toolName === "exec_command");
    const toolEvidence = await waitForRequiredToolEvidence(session, toolTurnId);
    const readEvidence = toolEvidence.find((entry) => entry.toolName === "read");
    const shellEvidence = toolEvidence.find((entry) => entry.toolName === "exec_command");
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
    if (
      (await readCompleteItemContent(
        session,
        threadId,
        toolTerminal.params.finalMessage.messageId,
      )) !== toolFinalReply
    ) {
      throw new Error("thread/item content did not reproduce the committed Tool-turn reply");
    }

    const failedShellMarker = `JA_REAL_PROVIDER_EXPECTED_FAILURE_${Date.now().toString(36)}`;
    const failedToolAccepted = assertRpcSuccess(
      await session.request("turn/start", {
        threadId,
        operationKey: createOperationKey(),
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
      "exec_command",
      handledApprovalIds,
    );
    approvedTools.push(failedToolApproval);
    const failedToolEvidence = await waitForRequiredToolEvidence(
      session,
      failedToolTurnId,
      ["exec_command"],
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
    if (
      (await readCompleteItemContent(
        session,
        threadId,
        failedToolTerminal.params.finalMessage.messageId,
      )) !== failedToolFinalReply
    ) {
      throw new Error("thread/item content did not reproduce the committed failed-Tool reply");
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
        calls: [{ toolName: "exec_command", status: "error", exitCode: 23 }],
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
        calls: [{ toolName: "exec_command", status: "error", exitCode: 23 }],
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
