// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 图片消息历史的独立 Windows Tauri/WebView2 验收 runner。它只连接本机 loopback
 * Responses fixture，验证 debug JAR 生产链路，不代表 Native Image 或正式打包产物验收。
 */

import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, mkdtemp, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { createServer as createHttpServer } from "node:http";
import { createServer as createNetServer } from "node:net";
import { tmpdir } from "node:os";
import process from "node:process";
import { basename, dirname, isAbsolute, join, relative, resolve } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { deflateSync } from "node:zlib";
import { chromium } from "@playwright/test";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const PNG_MINIMUM_BYTES = 300 * 1024;
const IMAGE_FILE_NAME = "image-history-e2e.png";
const SUCCESS_PROMPT = "__JA_IMAGE_HISTORY_SUCCESS__ 请确认收到这张图片。";
const FAILURE_PROMPT = "__JA_IMAGE_HISTORY_FAILURE__ 请触发受控失败。";
const SUCCESS_REPLY = "JA_IMAGE_HISTORY_SUCCESS_REPLY";
const STABLE_PORT_RANGE = Object.freeze({ start: 41_000, size: 8_000 });
const STEP_TIMEOUT_MS = 30_000;

/** 解析窄命令行合同，避免 runner 静默使用默认 JAR 或把证据写入临时根后随清理丢失。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    jar: undefined,
    cargoTargetDirectory: join(repoRoot, "target", "codex-image-history"),
    preflightOnly: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--preflight-only") {
      options.preflightOnly = true;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new Error(`missing value for ${argument}`);
    }
    if (argument === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (argument === "--java-home") options.javaHome = resolve(value);
    else if (argument === "--jar") options.jar = resolve(value);
    else if (argument === "--cargo-target-directory") {
      options.cargoTargetDirectory = resolve(value);
    } else throw new Error(`unknown argument: ${argument}`);
    index += 1;
  }
  if (options.evidenceDirectory === undefined) {
    throw new Error("--evidence-directory is required");
  }
  if (options.jar === undefined) throw new Error("--jar is required");
  return options;
}

/** 确认递归清理目标是 OS temp 下的具体随机目录，拒绝 workspace、用户目录或 temp 根。 */
export function assertOwnedTemporaryPath(path, label) {
  const temporaryRoot = resolve(tmpdir());
  const target = resolve(path);
  const relation = relative(temporaryRoot, target);
  if (relation === "" || relation.startsWith("..") || isAbsolute(relation)) {
    throw new Error(`${label} must be a child of the OS temp directory`);
  }
  return target;
}

/** 生成标准 CRC32 表；PNG fixture 自包含可避免依赖系统图像工具或修改 package manifest。 */
function createCrc32Table() {
  return Uint32Array.from({ length: 256 }, (_, value) => {
    let crc = value;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc & 1) === 1 ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1;
    }
    return crc >>> 0;
  });
}

const CRC32_TABLE = createCrc32Table();

/** 计算 PNG chunk 的 CRC32，保证 fixture 会被 Rust image decoder 与 WebView2 同时接纳。 */
function crc32(buffer) {
  let crc = 0xffffffff;
  for (const value of buffer) crc = CRC32_TABLE[(crc ^ value) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

/** 构造一个带长度和 CRC 的 PNG chunk，不引入测试专用图像依赖。 */
function pngChunk(type, data) {
  const typeBytes = Buffer.from(type, "ascii");
  const payload = Buffer.concat([typeBytes, data]);
  const chunk = Buffer.allocUnsafe(12 + data.length);
  chunk.writeUInt32BE(data.length, 0);
  payload.copy(chunk, 4);
  chunk.writeUInt32BE(crc32(payload), 8 + data.length);
  return chunk;
}

/**
 * 生成 320x320 RGBA PNG，并故意使用无压缩 DEFLATE 让有效图片稳定超过 300 KiB；像素仍是
 * 确定性图案，报告无需保存或泄漏任何用户图片。
 */
export function createLargePngFixture() {
  const width = 320;
  const height = 320;
  const raw = Buffer.allocUnsafe(height * (1 + width * 4));
  for (let y = 0; y < height; y += 1) {
    const row = y * (1 + width * 4);
    raw[row] = 0;
    for (let x = 0; x < width; x += 1) {
      const offset = row + 1 + x * 4;
      raw[offset] = (x * 17 + y * 31) & 0xff;
      raw[offset + 1] = (x * 47 + y * 13) & 0xff;
      raw[offset + 2] = (x * 7 + y * 61) & 0xff;
      raw[offset + 3] = 0xff;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  const bytes = Buffer.concat([
    Buffer.from("89504e470d0a1a0a", "hex"),
    pngChunk("IHDR", ihdr),
    pngChunk("IDAT", deflateSync(raw, { level: 0 })),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
  assert.ok(bytes.length >= PNG_MINIMUM_BYTES, "PNG fixture must be at least 300 KiB");
  return { bytes, width, height };
}

/** 从请求中寻找携带目标 prompt 的原生用户项，并严格核对同一项中的 input_image。 */
export function inspectNativeImageRequest(payload, marker, expectedBytes) {
  if (!Array.isArray(payload?.input)) return { kind: "non_turn" };
  const userItem = payload.input.findLast(
    (item) =>
      item?.role === "user" &&
      Array.isArray(item.content) &&
      item.content.some(
        (block) => block?.type === "input_text" && String(block.text ?? "").includes(marker),
      ),
  );
  if (userItem === undefined) return { kind: "unrelated" };
  const imageBlocks = userItem.content.filter((block) => block?.type === "input_image");
  if (imageBlocks.length === 0) return { kind: "title" };
  assert.equal(imageBlocks.length, 1, "target user item must contain exactly one native image");
  const imageUrl = imageBlocks[0].image_url;
  assert.equal(typeof imageUrl, "string", "input_image.image_url must be a string");
  const match = /^data:image\/png;base64,([A-Za-z0-9+/]+={0,2})$/u.exec(imageUrl);
  assert.ok(match, "input_image must use a PNG data URL");
  const encoded = match[1];
  const decoded = Buffer.from(encoded, "base64");
  assert.equal(decoded.equals(expectedBytes), true, "Provider image bytes changed before request");
  assert.equal(encoded, expectedBytes.toString("base64"), "Provider image Base64 changed");
  return {
    kind: "turn",
    byteLength: decoded.length,
    base64Preserved: true,
    detail: imageBlocks[0].detail,
  };
}

/** 编码严格 Responses SSE 帧，避免 fixture 依赖客户端对缺失 event 名的宽松兼容。 */
function responseEvent(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequenceNumber, ...payload })}\n\n`;
}

/** 构造与 OpenAI Responses adapter 严格终态形状一致的最小响应对象。 */
function responseEnvelope(responseId, status, output, includeUsage) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "gpt-5.6-sol",
    object: "response",
    output,
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 100,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens: 5,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 105,
    };
  }
  return response;
}

/** 生成确定性成功文本流，供正文回复与自动标题请求共同使用。 */
function successfulTextStream(text, ordinal) {
  const responseId = `resp_image_history_${ordinal}`;
  const itemId = `message_image_history_${ordinal}`;
  const item = {
    id: itemId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    responseEvent("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
    responseEvent("response.output_text.delta", 1, {
      content_index: 0,
      delta: text,
      item_id: itemId,
      logprobs: [],
      output_index: 0,
    }),
    responseEvent("response.output_text.done", 2, {
      content_index: 0,
      item_id: itemId,
      output_index: 0,
      text,
    }),
    responseEvent("response.completed", 3, {
      response: responseEnvelope(responseId, "completed", [item], true),
    }),
  ].join("");
}

/** 生成不可重试的 Provider 失败事件，稳定覆盖失败 Turn 的持久历史而不等待网络重试。 */
function failedStream() {
  return responseEvent("response.failed", 0, {
    response: {
      id: "resp_image_history_failure",
      error: { code: "image_history_fixture_failure", message: "fixture failure" },
      status: "failed",
    },
  });
}

/** 有界读取 Provider JSON；上限覆盖两张 300 KiB 图片的完整历史但拒绝无界请求。 */
async function readBoundedJson(request) {
  const chunks = [];
  let total = 0;
  for await (const chunk of request) {
    total += chunk.length;
    if (total > 8 * 1024 * 1024) throw new Error("Provider fixture request exceeded 8 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 启动只监听 127.0.0.1 的 Responses fixture，并仅保留图片完整性与请求类别证据。 */
async function startProviderFixture(expectedBytes) {
  const attempts = [];
  let ordinal = 0;
  const server = createHttpServer(async (request, response) => {
    try {
      if (request.method !== "POST" || request.url !== "/v1/responses") {
        response.writeHead(404, { "content-type": "application/json" });
        response.end('{"error":"not_found"}');
        return;
      }
      const payload = await readBoundedJson(request);
      const serialized = JSON.stringify(payload?.input ?? []);
      const marker = serialized.includes("__JA_IMAGE_HISTORY_FAILURE__")
        ? "__JA_IMAGE_HISTORY_FAILURE__"
        : serialized.includes("__JA_IMAGE_HISTORY_SUCCESS__")
          ? "__JA_IMAGE_HISTORY_SUCCESS__"
          : undefined;
      if (marker === undefined) throw new Error("unclassified Provider request");
      const inspection = inspectNativeImageRequest(payload, marker, expectedBytes);
      ordinal += 1;
      attempts.push({ marker, ...inspection });
      response.writeHead(200, {
        "cache-control": "no-store",
        "content-type": "text/event-stream; charset=utf-8",
      });
      if (marker.includes("FAILURE") && inspection.kind === "turn") {
        response.end(failedStream());
      } else {
        response.end(
          successfulTextStream(
            inspection.kind === "turn" ? SUCCESS_REPLY : "图片历史验收",
            ordinal,
          ),
        );
      }
    } catch (error) {
      response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: String(error?.message ?? error).slice(0, 200) }));
    }
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    attempts,
    /** 关闭唯一 loopback listener；fixture 不创建其它网络连接。 */
    async close() {
      await new Promise((resolvePromise) => server.close(resolvePromise));
    },
  };
}

/** 在 Windows 动态客户端范围之外探测空闲端口，释放后由 Vite/CDP 立即取得所有权。 */
async function reservePort(excluded = new Set()) {
  const offset = (Date.now() + process.pid) % STABLE_PORT_RANGE.size;
  for (let index = 0; index < STABLE_PORT_RANGE.size; index += 1) {
    const port = STABLE_PORT_RANGE.start + ((offset + index) % STABLE_PORT_RANGE.size);
    if (excluded.has(port)) continue;
    const server = createNetServer();
    const bound = await new Promise((resolvePromise) => {
      server.once("error", () => resolvePromise(false));
      server.listen(port, "127.0.0.1", () => resolvePromise(true));
    });
    if (!bound) {
      server.close();
      continue;
    }
    await new Promise((resolvePromise) => server.close(resolvePromise));
    return port;
  }
  throw new Error("failed to reserve a stable loopback port");
}

/** 创建本轮唯一的 profile、Ja home、AppData、UDF、runtime 和项目目录。 */
async function createRunDirectories() {
  const root = await mkdtemp(join(tmpdir(), "ja-image-history-"));
  const profile = join(root, "profile");
  const directories = {
    root,
    profile,
    home: join(profile, ".ja"),
    workspace: join(root, "workspace"),
    webview: join(root, "webview"),
    runtime: join(root, "runtime"),
    roaming: join(root, "appdata", "roaming"),
    local: join(root, "appdata", "local"),
  };
  await Promise.all(
    Object.values(directories)
      .filter((path) => path !== root)
      .map((path) => mkdir(path, { recursive: true })),
  );
  return directories;
}

/** 写入仅指向本轮 loopback Provider 的最小合法配置，并收紧 auth 文件 ACL。 */
async function writeIsolatedSettings(home, baseUrl) {
  const config = [
    "schema_version = 1",
    "config_revision = 1",
    'default_access_mode = "full_access"',
    'default_provider_id = "provider_e2e"',
    'default_model_id = "model_e2e"',
    "default_reasoning_level = { __ja_null = true }",
    "mcp_servers = []",
    "skills = []",
    "",
    "[[providers]]",
    'provider_id = "provider_e2e"',
    'name = "Image History E2E"',
    'api = "openai_responses"',
    `base_url = ${JSON.stringify(baseUrl)}`,
    'credential_id = "cred_e2e"',
    "[providers.network_timeouts]",
    "connect_timeout_ms = 5000",
    "request_timeout_ms = 30000",
    "[providers.agent_defaults]",
    "[providers.agent_defaults.context]",
    "auto_compact = true",
    "[providers.agent_defaults.turn_limits]",
    "max_model_rounds = 4",
    "max_tool_calls = 8",
    "wall_timeout_ms = 30000",
    "[[providers.models]]",
    'model_id = "model_e2e"',
    'name = "Image History E2E Model"',
    // 模型标识必须命中 Ja 的已知 vision capability；实际 HTTP 仍只到本轮 loopback。
    'model = "gpt-5.6-sol"',
    "reasoning_level_map = {}",
    "default_reasoning_level = { __ja_null = true }",
    "[providers.models.capabilities]",
    "context_window_tokens = 128000",
    "max_output_tokens = 8192",
    "",
  ].join("\n");
  await mkdir(home, { recursive: true });
  await writeFile(join(home, "config.toml"), config, "utf8");
  const authPath = join(home, "auth.json");
  await writeFile(authPath, '{"cred_e2e":"loopback-only"}\n', "utf8");
  const account = `${process.env.USERDOMAIN ?? "."}\\${process.env.USERNAME ?? ""}`;
  if (account.endsWith("\\")) throw new Error("unable to resolve Windows account for ACL");
  await execFileAsync("icacls.exe", [authPath, "/inheritance:r", "/grant:r", `${account}:(F)`], {
    windowsHide: true,
    timeout: 15_000,
    maxBuffer: 512 * 1024,
  });
}

/** 读取生产主窗口与 Windows 覆盖，防止 E2E 用另一套窗口结构规避真实布局。 */
async function readProductionMainWindowConfig() {
  const [base, windows] = await Promise.all([
    readFile(join(repoRoot, "src-tauri", "tauri.conf.json"), "utf8").then(JSON.parse),
    readFile(join(repoRoot, "src-tauri", "tauri.windows.conf.json"), "utf8").then(JSON.parse),
  ]);
  const baseWindow = base?.app?.windows?.find((candidate) => candidate?.label === "main");
  const windowsWindow = windows?.app?.windows?.find((candidate) => candidate?.label === "main");
  if (baseWindow === undefined) throw new Error("production main window is missing");
  return { ...baseWindow, ...(windowsWindow ?? {}) };
}

/** 生成唯一 identifier、生产窗口与图片 custom protocol CSP 的私有 Tauri overlay。 */
async function writeTauriOverlay(directories, frontendPort) {
  const origin = `http://localhost:${frontendPort}`;
  const websocket = `ws://localhost:${frontendPort}`;
  const overlayPath = join(directories.runtime, "tauri.image-history.conf.json");
  const config = {
    identifier: `io.github.kongweiguang.ja.image${randomUUID().replaceAll("-", "")}`,
    build: { devUrl: origin },
    app: {
      windows: [{ ...(await readProductionMainWindowConfig()) }],
      security: {
        devCsp: `default-src 'self'; connect-src 'self' ipc: http://ipc.localhost ${origin} ${websocket}; img-src 'self' data: blob: ja-attachment: http://ja-attachment.localhost; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self' data:; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`,
      },
    },
  };
  await writeFile(overlayPath, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return overlayPath;
}

/** 验证 Node 24、pnpm 10.33、JDK 25 与非空 debug JAR，避免旧工具链形成假结果。 */
export async function validateToolchain({ javaHome, jar }) {
  const java = join(javaHome, "bin", "java.exe");
  const [javaResult, nodeResult, pnpmResult, jarMetadata] = await Promise.all([
    execFileAsync(java, ["-version"], { windowsHide: true, timeout: 15_000 }),
    execFileAsync("node.exe", ["--version"], { windowsHide: true, timeout: 15_000 }),
    execFileAsync("pwsh.exe", ["-NoProfile", "-Command", "& pnpm.cmd --version"], {
      windowsHide: true,
      timeout: 15_000,
    }),
    stat(jar),
  ]);
  if (!/version "25(?:\.|"|\s)/u.test(`${javaResult.stdout}\n${javaResult.stderr}`)) {
    throw new Error("image history runner requires JDK 25");
  }
  if (!/^v24\./u.test(nodeResult.stdout.trim())) {
    throw new Error("image history runner requires Node 24");
  }
  if (pnpmResult.stdout.trim() !== "10.33.0") {
    throw new Error("image history runner requires pnpm 10.33.0");
  }
  if (!jarMetadata.isFile() || jarMetadata.size < 1) throw new Error("debug JAR is empty");
  return { java, jarSize: jarMetadata.size };
}

/** 构造只暴露本轮私有 profile/runtime 的启动环境，并清除所有真实 Provider 开关。 */
export function buildLaunchEnvironment({
  directories,
  java,
  jar,
  frontendPort,
  cdpPort,
  cargoTargetDirectory,
}) {
  const inheritedPath = process.env.PATH ?? process.env.Path ?? "";
  const env = {
    ...process.env,
    APPDATA: directories.roaming,
    LOCALAPPDATA: directories.local,
    USERPROFILE: directories.profile,
    CARGO_HOME: process.env.CARGO_HOME?.trim() || join(process.env.USERPROFILE, ".cargo"),
    RUSTUP_HOME: process.env.RUSTUP_HOME?.trim() || join(process.env.USERPROFILE, ".rustup"),
    JAVA_HOME: dirname(dirname(java)),
    JA_JAVA25_HOME: dirname(dirname(java)),
    JA_DEBUG_JAVA: java,
    JA_DEBUG_JAR: jar,
    JA_E2E_RUNTIME_ROOT: directories.runtime,
    JA_E2E_DEV_PORT: String(frontendPort),
    VITE_JA_E2E_PROJECT_PATH: directories.workspace,
    CARGO_TARGET_DIR: cargoTargetDirectory,
    WEBVIEW2_USER_DATA_FOLDER: directories.webview,
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --remote-debugging-port=${cdpPort}`,
    NO_PROXY: ["127.0.0.1", "localhost", "::1", process.env.NO_PROXY]
      .filter((value) => typeof value === "string" && value.trim().length > 0)
      .join(","),
    PATH: [dirname(java), inheritedPath].filter(Boolean).join(";"),
  };
  delete env.Path;
  delete env.no_proxy;
  for (const name of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]) delete env[name];
  for (const name of Object.keys(env)) {
    if (/^JA_(?:E2E_)?REAL_PROVIDER/u.test(name)) delete env[name];
  }
  return env;
}

/** 启动真实 `tauri dev --no-watch`，cmd 仅作为 Windows .cmd 兼容边界且窗口始终隐藏。 */
function launchTauri(overlayPath, environment) {
  const comspec = process.env.ComSpec ?? process.env.COMSPEC ?? "cmd.exe";
  const commandLine = `pnpm.cmd tauri dev --no-watch --config "${overlayPath}"`;
  const child = spawn(comspec, ["/d", "/s", "/c", `"${commandLine}"`], {
    cwd: repoRoot,
    env: environment,
    windowsHide: true,
    windowsVerbatimArguments: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const output = { stdout: "", stderr: "" };
  const append = (key, chunk) => {
    output[key] = `${output[key]}${String(chunk)}`.slice(-12_000);
  };
  child.stdout?.on("data", (chunk) => append("stdout", chunk));
  child.stderr?.on("data", (chunk) => append("stderr", chunk));
  return { child, output };
}

/** 把 CDP 探测异常压缩为稳定字段，保留 TCP 拒绝、超时与代理故障的底层错误码。 */
function describeCdpProbeError(error) {
  const name = error instanceof Error ? error.name : typeof error;
  const code = error?.code ?? error?.cause?.code;
  return `error=${name}${code === undefined ? "" : ` code=${String(code)}`}`;
}

/**
 * 等待 CDP version endpoint；超时会带回最后一次 HTTP 状态、JSON 字段或网络错误码，
 * 使 fresh WebView2 UDF 未监听与代理/响应结构问题可以被证据区分。
 */
async function waitForCdp(endpoint, launch, deadline = Date.now() + 5 * 60_000) {
  const versionUrl = new URL("/json/version", endpoint).href;
  let lastProbe = "not_attempted";
  while (Date.now() < deadline) {
    if (launch.child.exitCode !== null) {
      throw new Error(`Tauri exited before CDP: ${launch.output.stderr.slice(-2_000)}`);
    }
    try {
      const response = await fetch(versionUrl, { signal: AbortSignal.timeout(2_000) });
      const payload = await response.json();
      const keys =
        payload !== null && typeof payload === "object"
          ? Object.keys(payload).sort().slice(0, 16).join(",")
          : typeof payload;
      lastProbe = `status=${response.status} ok=${response.ok} keys=${keys || "<none>"}`;
      if (response.ok && typeof payload.webSocketDebuggerUrl === "string") return;
    } catch (error) {
      // WebView2 在 Rust/Vite 冷启动完成前拒绝连接是预期状态。
      lastProbe = describeCdpProbeError(error);
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error(
    `Tauri WebView2 CDP startup timed out (${lastProbe}): ${launch.output.stderr.slice(-2_000)}`,
  );
}

/** 从 CDP contexts 中只选择本轮 Vite origin 或 Tauri main page。 */
async function findTauriPage(browser, frontendPort, deadline = Date.now() + 30_000) {
  while (Date.now() < deadline) {
    for (const context of browser.contexts()) {
      for (const page of context.pages()) {
        if (
          page.url().includes(`localhost:${frontendPort}`) ||
          page.url().includes("tauri://localhost")
        ) {
          return page;
        }
      }
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error("Ja Tauri main WebView was not found");
}

/** 只终止本 runner 启动的 launcher PID 树，不枚举或触碰其它 Ja/Vite/Cargo 进程。 */
async function terminateOwnedLauncher(launch) {
  const pid = launch?.child?.pid;
  if (!Number.isSafeInteger(pid) || launch.child.exitCode !== null) return;
  await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
    windowsHide: true,
    timeout: 20_000,
    maxBuffer: 1 * 1024 * 1024,
  }).catch(() => undefined);
}

/** 重新解析真实路径后清理私有 temp root，阻止 junction 或变量漂移扩大递归删除范围。 */
async function removeOwnedRunRoot(root) {
  const expected = assertOwnedTemporaryPath(root, "run root");
  const actual = await realpath(root).catch(() => expected);
  assert.equal(resolve(actual), resolve(expected));
  await rm(actual, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });
}

/** 流式计算证据身份，只写摘要而不复制 JAR 或图片正文。 */
async function sha256(path) {
  const digest = createHash("sha256");
  await new Promise((resolvePromise, rejectPromise) => {
    const stream = createReadStream(path);
    stream.on("data", (chunk) => digest.update(chunk));
    stream.once("error", rejectPromise);
    stream.once("end", resolvePromise);
  });
  return digest.digest("hex");
}

/** 启动共享 STA clipboard broker；broker 自己保存并恢复用户原始 IDataObject。 */
async function startClipboardBroker() {
  const script = join(repoRoot, "scripts", "e2e", "windows-clipboard-fixture.ps1");
  const child = spawn(
    "powershell.exe",
    ["-NoLogo", "-NoProfile", "-NonInteractive", "-STA", "-File", script],
    { windowsHide: true, stdio: ["pipe", "pipe", "pipe"] },
  );
  const lines = createInterface({ input: child.stdout });
  const queue = [];
  const waiters = [];
  let terminalError;
  let stderr = "";
  let restored = false;
  child.stderr.on("data", (chunk) => {
    stderr = `${stderr}${String(chunk)}`.slice(-4_000);
  });
  lines.on("line", (line) => {
    try {
      const value = JSON.parse(line);
      const waiter = waiters.shift();
      if (waiter === undefined) queue.push(value);
      else waiter.resolve(value);
    } catch {
      terminalError = new Error("clipboard broker returned malformed JSON");
      while (waiters.length > 0) waiters.shift().reject(terminalError);
    }
  });
  child.once("exit", (code) => {
    terminalError ??= new Error(`clipboard broker exited code=${String(code)} ${stderr}`);
    while (waiters.length > 0) waiters.shift().reject(terminalError);
  });

  /** 等待下一条严格 FIFO ACK，防止 clipboard owner 异常时耗尽全局场景期限。 */
  const next = (label) => {
    if (queue.length > 0) return Promise.resolve(queue.shift());
    if (terminalError !== undefined) return Promise.reject(terminalError);
    return new Promise((resolvePromise, rejectPromise) => {
      const waiter = {
        resolve(value) {
          clearTimeout(timer);
          resolvePromise(value);
        },
        reject(error) {
          clearTimeout(timer);
          rejectPromise(error);
        },
      };
      const timer = setTimeout(() => {
        const index = waiters.indexOf(waiter);
        if (index >= 0) waiters.splice(index, 1);
        rejectPromise(new Error(`${label} clipboard ACK timed out`));
      }, 10_000);
      waiters.push(waiter);
    });
  };

  /** 发送一条单行 JSON 命令并等待 drain，禁止 broker 退出后的 write-after-end。 */
  const writeCommand = async (command) => {
    if (terminalError !== undefined) throw terminalError;
    await new Promise((resolvePromise, rejectPromise) => {
      child.stdin.write(`${JSON.stringify(command)}\n`, (error) => {
        if (error === null || error === undefined) resolvePromise();
        else rejectPromise(error);
      });
    });
  };

  const ready = await next("startup");
  if (ready.status !== "ready") throw new Error("clipboard broker did not become ready");
  return {
    /** 把本轮私有 PNG 作为 CF_HDROP 写入剪贴板，真实 Composer paste 再调用 Rust import。 */
    async setFileDrop(path) {
      await writeCommand({ action: "set", kind: "file_drop", path });
      const result = await next("file_drop");
      assert.equal(result.status, "set");
      assert.equal(result.kind, "file_drop");
    },
    /** 恢复原始格式集合后才允许结束 broker；重复 cleanup 保持幂等。 */
    async restore() {
      if (restored) return;
      restored = true;
      try {
        await writeCommand({ action: "restore" });
        const result = await next("restore");
        assert.equal(result.status, "restored");
        assert.equal(result.formatsMatch, true);
      } finally {
        child.stdin.end();
      }
    },
  };
}

/** 将单步 Playwright 超时限制在局部预算内，缺少 selector 时快速给出阶段归因。 */
function timeout(deadline) {
  return Math.max(1, Math.min(STEP_TIMEOUT_MS, deadline - Date.now()));
}

/** 等待产品 Shell、真实 App Server 连接与可提交 Composer 同时就绪。 */
async function waitForApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const project = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await project.count()) === 0) {
    await page.getByRole("button", { name: "添加项目", exact: true }).click({
      timeout: timeout(deadline),
    });
  }
  await project.waitFor({ state: "visible", timeout: timeout(deadline) });
  await page.waitForFunction(
    () => {
      const form = globalThis.document.querySelector('form[aria-label="发送消息"]');
      const input = globalThis.document.querySelector('textarea[aria-label="消息"]');
      return form?.getAttribute("data-state") === "ready" && !input?.hasAttribute("disabled");
    },
    undefined,
    { timeout: timeout(deadline) },
  );
}

/** 返回当前侧栏选中的 durable Thread identity，报告只保留是否恢复同一 identity。 */
async function currentThreadId(page) {
  const value = await page
    .locator('[aria-label="最近对话列表"] button[aria-current="page"][data-thread-id]')
    .getAttribute("data-thread-id");
  if (value === null) throw new Error("active thread identity is unavailable");
  return value;
}

/** 用真实侧栏控件恢复指定 Thread，避免 reload 后从 DOM 猜测历史归属。 */
async function selectThread(page, threadId, deadline) {
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await row.getAttribute("aria-current")) !== "page") {
    await row.click({ timeout: timeout(deadline) });
  }
}

/** 通过 Ctrl+V 触发真实 Composer paste，再等待 Rust/App Server 返回可见草稿附件。 */
async function pasteImageAttachment(page, imagePath, deadline) {
  const broker = await startClipboardBroker();
  try {
    await broker.setFileDrop(imagePath);
    const input = page.getByRole("textbox", { name: "消息", exact: true });
    await input.focus();
    await page.keyboard.press("Control+V");
    const preview = page.getByRole("button", {
      name: `预览附件 ${basename(imagePath)}`,
      exact: true,
    });
    await preview.waitFor({ state: "visible", timeout: timeout(deadline) });
    return preview;
  } finally {
    // 每次导入 ACK 后立即恢复，避免编译、Provider 等待和后续 UI 操作期间占用用户剪贴板。
    await broker.restore();
  }
}

/** 定位包含指定 prompt 的唯一用户消息，保证附件断言绑定到正确 Turn 而非历史任意图片。 */
async function userMessageFor(page, prompt, deadline) {
  const message = page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: prompt });
  await message.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(await message.count(), 1, "prompt must identify exactly one user message");
  return message;
}

/**
 * 校验历史缩略图真实解码且只走 Tauri custom protocol；blob/data URL 即使能显示也不能证明
 * Thread scope、session token 和 native image pipeline。
 */
async function historyThumbnailFacts(message, deadline) {
  const image = message.locator("[data-attachment-id] img").first();
  await image.waitFor({ state: "visible", timeout: timeout(deadline) });
  await image.evaluate(
    (element, maximumWaitMs) =>
      new Promise((resolvePromise, rejectPromise) => {
        if (element.complete && element.naturalWidth > 0) {
          resolvePromise(undefined);
          return;
        }
        const timer = setTimeout(
          () => rejectPromise(new Error("history thumbnail decode timed out")),
          maximumWaitMs,
        );
        const finish = () => {
          clearTimeout(timer);
          resolvePromise(undefined);
        };
        const fail = () => {
          clearTimeout(timer);
          rejectPromise(new Error("history thumbnail decode failed"));
        };
        element.addEventListener("load", finish, { once: true });
        element.addEventListener("error", fail, { once: true });
      }),
    timeout(deadline),
  );
  const facts = await image.evaluate((element) => ({
    naturalWidth: element.naturalWidth,
    naturalHeight: element.naturalHeight,
    currentSrc: element.currentSrc || element.src,
  }));
  assert.ok(facts.naturalWidth > 0 && facts.naturalHeight > 0, "history thumbnail did not decode");
  const url = new URL(facts.currentSrc);
  const nativeProtocol =
    url.protocol === "ja-attachment:" ||
    (url.protocol === "http:" && url.hostname === "ja-attachment.localhost");
  assert.equal(nativeProtocol, true, "history thumbnail must use the native attachment scheme");
  return {
    naturalWidth: facts.naturalWidth,
    naturalHeight: facts.naturalHeight,
    scheme: url.protocol === "ja-attachment:" ? "ja-attachment" : "ja-attachment.localhost",
  };
}

/** 点击历史附件的可见预览动作，并校验 Preview 中的图片也完成自然尺寸解码。 */
async function openHistoryPreview(page, message, deadline) {
  const button = message.getByRole("button", { name: `预览附件 ${IMAGE_FILE_NAME}`, exact: true });
  await button.click({ timeout: timeout(deadline) });
  const preview = page.locator(".ja-attachment-preview");
  await preview.waitFor({ state: "visible", timeout: timeout(deadline) });
  const image = preview.getByRole("img", { name: IMAGE_FILE_NAME, exact: true });
  await page.waitForFunction(
    (name) => {
      const candidate = [...globalThis.document.images].find((value) => value.alt === name);
      return candidate !== undefined && candidate.naturalWidth > 0 && candidate.naturalHeight > 0;
    },
    IMAGE_FILE_NAME,
    { timeout: timeout(deadline) },
  );
  const currentSrc = await image.evaluate((element) => element.currentSrc || element.src);
  const url = new URL(currentSrc);
  assert.ok(
    url.protocol === "ja-attachment:" ||
      (url.protocol === "http:" && url.hostname === "ja-attachment.localhost"),
    "preview must use the native attachment scheme",
  );
  return { opened: true, decoded: true };
}

/** 点击可见发送按钮，并等待目标用户消息 ACK 落入时间线。 */
async function submitWithImage(page, prompt, deadline) {
  await page.getByRole("textbox", { name: "消息", exact: true }).fill(prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: timeout(deadline),
  });
  return await userMessageFor(page, prompt, deadline);
}

/** 等待成功 Turn 的公开回复与 completed 状态，并拒绝任何 CONTEXT_LIMIT 可见失败。 */
async function waitForSuccessfulTurn(page, message, deadline) {
  const row = message.locator("xpath=ancestor::div[@data-turn-id][1]");
  await row.locator('.ja-chat-message-final[data-response-state="completed"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await row.getByText(SUCCESS_REPLY, { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  assert.equal((await row.innerText()).includes("CONTEXT_LIMIT"), false);
}

/** 等待受控 Provider 失败进入 failed 状态，并确认错误没有被错误归因为上下文超限。 */
async function waitForFailedTurn(message, deadline) {
  const row = message.locator("xpath=ancestor::div[@data-turn-id][1]");
  await row.locator('.ja-chat-message-final[data-response-state="failed"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const text = await row.innerText();
  assert.equal(
    text.includes("CONTEXT_LIMIT"),
    false,
    "controlled Provider failure became CONTEXT_LIMIT",
  );
  return { retained: true, contextLimitVisible: false };
}

/** 等待 fixture 观察到目标 Turn 图片请求，不使用任意 sleep 判断跨进程收敛。 */
async function waitForProviderTurn(fixture, marker, deadline) {
  while (Date.now() < deadline) {
    const attempt = fixture.attempts.find(
      (candidate) => candidate.marker === marker && candidate.kind === "turn",
    );
    if (attempt !== undefined) return attempt;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`Provider did not observe turn ${marker}`);
}

/**
 * 对报告执行闭集断言；退出码、截图或可见图片都不能单独替代 Base64、刷新恢复和失败历史事实。
 */
export function validateImageHistoryReport(report) {
  const failures = [];
  const require = (condition, code) => {
    if (!condition) failures.push(code);
  };
  require(report?.schemaVersion === 1, "schema-version");
  require(report?.status === "passed", "status");
  require(report?.runtime?.platform === "win32", "windows");
  require(report?.runtime?.surface === "tauri_webview2", "webview2");
  require(report?.runtime?.boundary === "debug_jar", "debug-jar");
  require(report?.provider?.kind === "deterministic_loopback", "loopback-provider");
  require(report?.provider?.externalCalls === 0, "external-provider");
  require(report?.image?.sizeBytes >= PNG_MINIMUM_BYTES, "png-size");
  require(report?.image?.validPng === true, "png-valid");
  require(report?.successTurn?.submittedViaUi === true, "success-submit-ui");
  require(report?.successTurn?.base64Preserved === true, "success-base64");
  require(report?.successTurn?.contextLimitVisible === false, "success-context-limit");
  require(report?.successTurn?.immediateThumbnail?.naturalWidth > 0, "immediate-thumbnail");
  require(report?.successTurn?.immediateThumbnail?.scheme !== "blob", "immediate-native-scheme");
  require(report?.preview?.opened === true && report?.preview?.decoded === true, "preview");
  require(report?.reload?.sameThread === true, "reload-thread");
  require(report?.reload?.thumbnail?.naturalWidth > 0, "reload-thumbnail");
  require(report?.failureTurn?.terminalState === "failed", "failed-terminal");
  require(report?.failureTurn?.base64Preserved === true, "failed-base64");
  require(report?.failureTurn?.attachmentRetained === true, "failed-image-retained");
  require(report?.failureTurn?.contextLimitVisible === false, "failed-context-limit");
  return { passed: failures.length === 0, failures };
}

/** 运行完整真窗场景并在外部证据目录发布报告；私有运行目录无论成功失败都会清理。 */
export async function runProduction(options) {
  await mkdir(options.evidenceDirectory, { recursive: true });
  const reportPath = join(options.evidenceDirectory, "image-history-report.json");
  let directories;
  let provider;
  let launch;
  let browser;
  let page;
  let stage = "toolchain";
  const pageErrors = [];
  try {
    const toolchain = await validateToolchain(options);
    stage = "fixture";
    directories = await createRunDirectories();
    assert.ok(!options.evidenceDirectory.startsWith(`${directories.root}\\`));
    const png = createLargePngFixture();
    const imagePath = join(directories.workspace, IMAGE_FILE_NAME);
    await writeFile(imagePath, png.bytes);
    provider = await startProviderFixture(png.bytes);
    await writeIsolatedSettings(directories.home, provider.baseUrl);
    const frontendPort = await reservePort();
    const cdpPort = await reservePort(new Set([frontendPort]));
    const overlay = await writeTauriOverlay(directories, frontendPort);
    const environment = buildLaunchEnvironment({
      directories,
      java: toolchain.java,
      jar: options.jar,
      frontendPort,
      cdpPort,
      cargoTargetDirectory: options.cargoTargetDirectory,
    });
    launch = launchTauri(overlay, environment);
    stage = "webview_startup";
    const endpoint = `http://127.0.0.1:${cdpPort}`;
    console.log(`JA_IMAGE_HISTORY_STAGE webview_startup cdp_port=${cdpPort}`);
    await waitForCdp(endpoint, launch);
    browser = await chromium.connectOverCDP(endpoint);
    page = await findTauriPage(browser, frontendPort);
    page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
    const deadline = Date.now() + 5 * 60_000;
    stage = "application_ready";
    await waitForApplication(page, deadline);
    const threadId = await currentThreadId(page);

    stage = "success_paste";
    await pasteImageAttachment(page, imagePath, deadline);
    stage = "success_submit";
    const successMessage = await submitWithImage(page, SUCCESS_PROMPT, deadline);
    const successAttempt = await waitForProviderTurn(
      provider,
      "__JA_IMAGE_HISTORY_SUCCESS__",
      deadline,
    );
    const immediateThumbnail = await historyThumbnailFacts(successMessage, deadline);
    await waitForSuccessfulTurn(page, successMessage, deadline);
    await page.screenshot({
      path: join(options.evidenceDirectory, "01-success-history.png"),
      animations: "disabled",
    });
    stage = "success_preview";
    const preview = await openHistoryPreview(page, successMessage, deadline);
    await page.screenshot({
      path: join(options.evidenceDirectory, "02-image-preview.png"),
      animations: "disabled",
    });

    stage = "reload_restore";
    await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
    await waitForApplication(page, deadline);
    await selectThread(page, threadId, deadline);
    const restoredMessage = await userMessageFor(page, SUCCESS_PROMPT, deadline);
    const restoredThumbnail = await historyThumbnailFacts(restoredMessage, deadline);
    await openHistoryPreview(page, restoredMessage, deadline);

    stage = "failure_paste";
    await pasteImageAttachment(page, imagePath, deadline);
    stage = "failure_submit";
    const failedMessage = await submitWithImage(page, FAILURE_PROMPT, deadline);
    const failureAttempt = await waitForProviderTurn(
      provider,
      "__JA_IMAGE_HISTORY_FAILURE__",
      deadline,
    );
    const failureState = await waitForFailedTurn(failedMessage, deadline);
    const failureThumbnail = await historyThumbnailFacts(failedMessage, deadline);
    await page.screenshot({
      path: join(options.evidenceDirectory, "03-failed-history-retained.png"),
      animations: "disabled",
    });
    assert.deepEqual(pageErrors, [], `WebView2 page errors: ${pageErrors.join(" | ")}`);

    const report = {
      schemaVersion: 1,
      status: "passed",
      runtime: {
        platform: process.platform,
        surface: "tauri_webview2",
        boundary: "debug_jar",
        nativeImageVerified: false,
        jar: { sha256: await sha256(options.jar), sizeBytes: toolchain.jarSize },
      },
      provider: {
        kind: "deterministic_loopback",
        externalCalls: 0,
        turnAttempts: provider.attempts.filter(({ kind }) => kind === "turn").length,
        titleAttempts: provider.attempts.filter(({ kind }) => kind === "title").length,
      },
      image: {
        fileName: IMAGE_FILE_NAME,
        sizeBytes: png.bytes.length,
        validPng: png.bytes.subarray(0, 8).equals(Buffer.from("89504e470d0a1a0a", "hex")),
        dimensions: { width: png.width, height: png.height },
      },
      successTurn: {
        submittedViaUi: true,
        base64Preserved: successAttempt.base64Preserved === true,
        contextLimitVisible: false,
        immediateThumbnail,
      },
      preview,
      reload: {
        sameThread: (await currentThreadId(page)) === threadId,
        thumbnail: restoredThumbnail,
        previewReopened: true,
      },
      failureTurn: {
        terminalState: "failed",
        base64Preserved: failureAttempt.base64Preserved === true,
        attachmentRetained: failureState.retained && failureThumbnail.naturalWidth > 0,
        contextLimitVisible: failureState.contextLimitVisible,
        thumbnail: failureThumbnail,
      },
      diagnostics: { pageErrors },
    };
    const verdict = validateImageHistoryReport(report);
    assert.equal(verdict.passed, true, `incomplete report: ${verdict.failures.join(",")}`);
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
    return report;
  } catch (error) {
    await page
      ?.screenshot({
        path: join(options.evidenceDirectory, "failure.png"),
        animations: "disabled",
      })
      .catch(() => undefined);
    const safeError = String(error?.message ?? error)
      .replaceAll(directories?.root ?? "<RUN_ROOT>", "<RUN_ROOT>")
      .replace(/[\r\n]+/gu, " ")
      .slice(0, 2_000);
    await writeFile(
      reportPath,
      `${JSON.stringify(
        {
          schemaVersion: 1,
          status: "failed",
          stage,
          error: safeError,
          diagnostics: { pageErrors },
        },
        null,
        2,
      )}\n`,
      "utf8",
    ).catch(() => undefined);
    throw error;
  } finally {
    await browser?.close().catch(() => undefined);
    await terminateOwnedLauncher(launch);
    await provider?.close().catch(() => undefined);
    if (directories?.root !== undefined) {
      await removeOwnedRunRoot(directories.root).catch(() => undefined);
    }
  }
}

/** CLI 入口把详细事实保留到 JSON，仅输出稳定状态标记便于主任务收集。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.preflightOnly) {
    await validateToolchain(options);
    console.log("JA_IMAGE_HISTORY_PREFLIGHT_OK");
    return;
  }
  await runProduction(options);
  console.log("JA_IMAGE_HISTORY_PASS");
}

const invokedPath =
  process.argv[1] === undefined ? undefined : pathToFileURL(resolve(process.argv[1])).href;
if (invokedPath === import.meta.url) {
  main().catch((error) => {
    console.error(`JA_IMAGE_HISTORY_FAIL ${String(error?.message ?? error).slice(0, 2_000)}`);
    process.exitCode = 1;
  });
}
