// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 子智能体设置的 Windows 真窗验收 runner。
 *
 * 该 runner 自己创建隔离的 Ja Home、WebView2 UDF、Vite/Tauri 端口和 loopback
 * Provider，避免复用用户实例或修改其它桌面 smoke 的全局环境。所有设置和会话
 * 操作均通过真实 WebView2 DOM 完成；Provider 只记录模型/Tool schema 摘要，
 * 不保存 prompt、凭据或完整请求体。
 */

import { execFile, spawn } from "node:child_process";
import { createServer } from "node:http";
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { chromium, expect } from "@playwright/test";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const timeoutMs = 60_000;
const repoPath = (relativePath) => join(repoRoot, relativePath);

/**
 * 只允许 Windows 隔离运行；默认不接受外部 endpoint，避免误连已有用户窗口。
 * 外部调用可通过 `JA_E2E_SUBAGENT_SETTINGS_CDP_ENDPOINT` 仅执行 UI 阶段，便于
 * 已由其它隔离 launcher 启动时复用本 runner 的断言。
 */
function validateEnvironment() {
  if (process.platform !== "win32") throw new Error("子智能体设置验收仅支持 Windows 11");
  if (process.env.JA_E2E_SUBAGENT_SETTINGS_ISOLATED !== "1") {
    throw new Error("必须设置 JA_E2E_SUBAGENT_SETTINGS_ISOLATED=1");
  }
  const endpoint = process.env.JA_E2E_SUBAGENT_SETTINGS_CDP_ENDPOINT?.trim();
  if (endpoint !== undefined && !/^http:\/\/(?:127\.0\.0\.1|localhost):\d+\/?$/u.test(endpoint)) {
    throw new Error("JA_E2E_SUBAGENT_SETTINGS_CDP_ENDPOINT 必须是 loopback CDP 地址");
  }
}

/** 给 fixture 事件写入严格 Responses SSE envelope，保持生产 Adapter 的解析边界。 */
function responseEvent(type, sequence, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequence, ...payload })}\n\n`;
}

/** 生成最小 Responses completed envelope；只携带确定的模型和 usage 字段。 */
function responseEnvelope(id, model, output, status = "completed") {
  return {
    id,
    created_at: 0,
    model,
    object: "response",
    output,
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    status,
    usage: {
      input_tokens: 5,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens: 5,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 10,
    },
  };
}

/** 返回自然结束文本，防止测试 fixture 自身制造额外 Tool 或会话状态。 */
function textStream(model, ordinal, text) {
  const responseId = `resp_subagent_e2e_${ordinal}`;
  const itemId = `message_subagent_e2e_${ordinal}`;
  const item = {
    id: itemId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    responseEvent("response.created", 0, {
      response: responseEnvelope(responseId, model, [], "in_progress"),
    }),
    responseEvent("response.output_text.delta", 1, {
      content_index: 0,
      delta: text,
      item_id: itemId,
      output_index: 0,
    }),
    responseEvent("response.output_text.done", 2, {
      content_index: 0,
      item_id: itemId,
      output_index: 0,
      text,
    }),
    responseEvent("response.completed", 3, {
      response: responseEnvelope(responseId, model, [item]),
    }),
  ].join("");
}

/** 返回一次真实 `spawn_agent` call，子任务 brief 的 marker 用于避免递归 spawn。 */
function spawnStream(model, ordinal) {
  const item = {
    id: `item_subagent_e2e_${ordinal}`,
    type: "function_call",
    call_id: `call_subagent_e2e_${ordinal}`,
    name: "spawn_agent",
    arguments: JSON.stringify({
      taskName: "subagent-settings-e2e-child",
      brief: "SUBAGENT_SETTINGS_E2E_CHILD",
      accessMode: "full_access",
      timeoutMs: 30_000,
    }),
  };
  return [
    responseEvent("response.output_item.added", 0, {
      output_index: 0,
      item: { ...item, arguments: "" },
    }),
    responseEvent("response.function_call_arguments.done", 1, {
      item_id: item.id,
      arguments: item.arguments,
      output_index: 0,
    }),
    responseEvent("response.output_item.done", 2, { output_index: 0, item }),
    responseEvent("response.completed", 3, {
      response: responseEnvelope(`resp_subagent_e2e_${ordinal}`, model, [item]),
    }),
  ].join("");
}

/** 返回一次发现工具调用；后续请求必须依据真实发现结果再决定是否派发子智能体。 */
function toolSearchStream(model, ordinal) {
  const item = {
    id: `item_subagent_e2e_search_${ordinal}`,
    type: "function_call",
    call_id: `call_subagent_e2e_search_${ordinal}`,
    name: "tool_search",
    arguments: JSON.stringify({ query: "spawn_agent" }),
  };
  return [
    responseEvent("response.output_item.added", 0, {
      output_index: 0,
      item: { ...item, arguments: "" },
    }),
    responseEvent("response.function_call_arguments.done", 1, {
      item_id: item.id,
      arguments: item.arguments,
      output_index: 0,
    }),
    responseEvent("response.output_item.done", 2, { output_index: 0, item }),
    responseEvent("response.completed", 3, {
      response: responseEnvelope(`resp_subagent_e2e_search_${ordinal}`, model, [item]),
    }),
  ].join("");
}

/** 读取有界请求，仅保留后续断言所需的模型、Tool 名称和 child marker。 */
async function readProviderPayload(request) {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of request) {
    bytes += chunk.length;
    if (bytes > 4 * 1024 * 1024) throw new Error("Provider fixture 请求超过 4 MiB 限制");
    chunks.push(chunk);
  }
  const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  const serialized = JSON.stringify(payload);
  const inputItems = Array.isArray(payload.input) ? payload.input : [];
  const hasDiscoveredSpawnAgent = inputItems.some((item) => {
    if (item?.type !== "function_call_output" && item?.type !== "tool_result") return false;
    const outputValue = item.output ?? item.content ?? item;
    const output = typeof outputValue === "string" ? outputValue : JSON.stringify(outputValue);
    return output.includes('"tools"') && output.includes('"name":"spawn_agent"');
  });
  return {
    model: typeof payload.model === "string" ? payload.model : "",
    reasoningEffort:
      typeof payload.reasoning?.effort === "string" ? payload.reasoning.effort : null,
    toolNames: Array.isArray(payload.tools)
      ? payload.tools.map((tool) => String(tool?.name ?? "")).filter(Boolean)
      : [],
    childMarker: serialized.includes("SUBAGENT_SETTINGS_E2E_CHILD"),
    hasToolResult:
      serialized.includes("tool_result") || serialized.includes("function_call_output"),
    hasDiscoveredSpawnAgent,
  };
}

/** 启动只绑定 127.0.0.1 的 fake Provider；不会连接外部网络或保留请求正文。 */
async function startProvider() {
  const attempts = [];
  const pendingChildModels = [];
  let ordinal = 0;
  const server = createServer(async (request, response) => {
    if (request.method !== "POST" || !request.url?.endsWith("/responses")) {
      response.writeHead(404).end();
      return;
    }
    let summary;
    try {
      summary = await readProviderPayload(request);
    } catch {
      response.writeHead(400).end();
      return;
    }
    const pendingChildIndex = summary.hasToolResult
      ? -1
      : pendingChildModels.indexOf(summary.model);
    const childRequest = pendingChildIndex >= 0;
    if (childRequest) pendingChildModels.splice(pendingChildIndex, 1);
    const attempt = {
      ordinal: ++ordinal,
      ...summary,
      childMarker: summary.childMarker || childRequest,
    };
    attempts.push(attempt);
    const shouldSpawn =
      !childRequest &&
      ((summary.hasToolResult && summary.hasDiscoveredSpawnAgent) ||
        (!summary.hasToolResult && summary.toolNames.includes("spawn_agent")));
    const shouldSearch =
      !childRequest &&
      !summary.hasToolResult &&
      summary.toolNames.includes("tool_search") &&
      !summary.toolNames.includes("spawn_agent");
    if (shouldSpawn) pendingChildModels.push(summary.model);
    const stream = shouldSpawn
      ? spawnStream(summary.model, attempt.ordinal)
      : shouldSearch
        ? toolSearchStream(summary.model, attempt.ordinal)
        : textStream(
            summary.model,
            attempt.ordinal,
            attempt.childMarker ? "SUBAGENT_SETTINGS_CHILD_OK" : "SUBAGENT_SETTINGS_REPLY_OK",
          );
    const body = Buffer.from(stream, "utf8");
    response.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-store",
      "content-length": String(body.length),
    });
    response.end(body);
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("fake Provider 未绑定端口");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    attempts,
    close: async () => {
      server.closeAllConnections?.();
      await new Promise((resolvePromise) => server.close(resolvePromise));
    },
  };
}

/** 以生产 config.toml schema 写入两个同 Provider 模型，供设置选择器真实投影。 */
async function writeSettings(directories, provider) {
  const toml = [
    "schema_version = 1",
    "config_revision = 1",
    'default_access_mode = "full_access"',
    'default_provider_id = "provider_subagent_e2e"',
    'default_model_id = "model_parent"',
    "default_reasoning_level = { __ja_null = true }",
    "clarification_enabled = true",
    "subagents = { enabled = true, provider_id = { __ja_null = true }, model_id = { __ja_null = true }, reasoning_level = { __ja_null = true } }",
    "mcp_servers = []",
    "skills = []",
    "",
    "[[providers]]",
    'provider_id = "provider_subagent_e2e"',
    'name = "Subagent Settings E2E Provider"',
    'api = "openai_responses"',
    `base_url = ${JSON.stringify(provider.baseUrl)}`,
    'credential_id = "cred_subagent_e2e"',
    "[providers.network_timeouts]",
    "connect_timeout_ms = 5000",
    "request_timeout_ms = 30000",
    "[providers.agent_defaults]",
    "[providers.agent_defaults.context]",
    "auto_compact = true",
    "[providers.agent_defaults.turn_limits]",
    "max_model_rounds = 8",
    "max_tool_calls = 16",
    "wall_timeout_ms = 30000",
    "[[providers.models]]",
    'model_id = "model_parent"',
    'name = "Parent Model"',
    'model = "e2e-parent"',
    "reasoning_level_map = {}",
    "default_reasoning_level = { __ja_null = true }",
    "[providers.models.capabilities]",
    "context_window_tokens = 128000",
    "max_output_tokens = 8192",
    "[[providers.models]]",
    'model_id = "model_child"',
    'name = "Child Model"',
    'model = "e2e-child"',
    'reasoning_level_map = { low = "low", high = "high" }',
    'default_reasoning_level = "low"',
    "[providers.models.capabilities]",
    "context_window_tokens = 128000",
    "max_output_tokens = 8192",
    "",
  ].join("\n");
  await mkdir(directories.home, { recursive: true });
  const configPath = join(directories.home, "config.toml");
  const authPath = join(directories.home, "auth.json");
  await writeFile(configPath, `${toml}\n`, "utf8");
  await writeFile(authPath, '{"cred_subagent_e2e":"loopback-only"}\n', "utf8");
  const account = `${process.env.USERDOMAIN ?? "."}\\${process.env.USERNAME ?? ""}`;
  if (!account.endsWith("\\")) {
    await execFileAsync("icacls.exe", [authPath, "/inheritance:r", "/grant:r", `${account}:(F)`], {
      windowsHide: true,
      timeout: 15_000,
      maxBuffer: 512 * 1024,
    });
  }
  return { configPath, authPath };
}

/** 选择非动态范围 loopback 端口，避免误用已有 Vite/Provider listener。 */
async function reservePort(used = new Set()) {
  for (let offset = 0; offset < 1000; offset += 1) {
    const port = 41_000 + ((Date.now() + process.pid + offset) % 8_000);
    if (used.has(port)) continue;
    const probe = createServer();
    const available = await new Promise((resolvePromise) => {
      probe.once("error", () => resolvePromise(false));
      probe.listen(port, "127.0.0.1", () => resolvePromise(true));
    });
    await new Promise((resolvePromise) => probe.close(resolvePromise));
    if (available) return port;
  }
  throw new Error("无法分配隔离回环端口");
}

/** 目录只属于本轮，和现有桌面 smoke 一致让 Java 的 home/data/runtime 一一对应。 */
async function createDirectories() {
  const root = await mkdtemp(join(tmpdir(), "ja-subagent-settings-e2e-"));
  const directories = {
    root,
    workspace: join(root, "workspace"),
    settings: join(root, "settings"),
    home: join(root, "settings", ".ja"),
    data: join(root, "settings", ".ja", "data"),
    webview: join(root, "webview"),
    runtime: join(root, "runtime"),
    roaming: join(root, "appdata", "roaming"),
    local: join(root, "appdata", "local"),
    artifacts: resolve(
      process.env.JA_E2E_SUBAGENT_SETTINGS_ARTIFACT_DIR ??
        repoPath("src-tauri/target/subagent-settings-evidence"),
    ),
  };
  await Promise.all(Object.values(directories).map((path) => mkdir(path, { recursive: true })));
  return directories;
}

/** 只接受 JDK 25，并把实际 executable/home 同时传给 Rust 与 Java launcher。 */
async function resolveJava25() {
  const home = process.env.JA_E2E_JAVA_HOME?.trim() || process.env.JAVA_HOME?.trim();
  const executable =
    process.env.JA_TEST_JAVA?.trim() || (home ? join(home, "bin", "java.exe") : "");
  if (!executable) throw new Error("需要 JA_E2E_JAVA_HOME 或 JA_TEST_JAVA");
  const result = await execFileAsync(executable, ["-version"], {
    windowsHide: true,
    timeout: 10_000,
  });
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  const major = Number(output.match(/version\s+"?(\d+)/iu)?.[1] ?? 0);
  if (major !== 25) throw new Error(`需要 JDK 25，实际 Java major=${major}`);
  return {
    home: resolve(home ?? join(dirname(executable), "..")),
    executable: resolve(executable),
  };
}

/** 读取生产窗口事实，只替换本轮 dev origin/CSP/identifier。 */
async function writeTauriOverlay(directories, frontendPort, useEdgeDriver) {
  const base = JSON.parse(await readFile(repoPath("src-tauri/tauri.conf.json"), "utf8"));
  const windows = JSON.parse(await readFile(repoPath("src-tauri/tauri.windows.conf.json"), "utf8"));
  const baseWindow = base?.app?.windows?.find((candidate) => candidate?.label === "main");
  const windowsWindow = windows?.app?.windows?.find((candidate) => candidate?.label === "main");
  if (!baseWindow) throw new Error("生产 main window 配置缺失");
  const origin = `http://127.0.0.1:${frontendPort}`;
  const websocket = `ws://127.0.0.1:${frontendPort}`;
  const devCsp = `default-src 'self'; connect-src 'self' ipc: http://ipc.localhost ${origin} ${websocket}; img-src 'self' data: blob: ja-attachment: http://ja-attachment.localhost; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self' data:; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`;
  const config = {
    identifier: `io.github.kongweiguang.ja.e2e.subagent${frontendPort}`,
    build: {
      devUrl: origin,
      ...(useEdgeDriver ? { runner: repoPath("scripts/e2e/webview2-edgedriver-runner.cmd") } : {}),
    },
    app: { windows: [{ ...baseWindow, ...(windowsWindow ?? {}) }], security: { devCsp } },
  };
  const path = join(directories.runtime, "tauri.subagent-settings.conf.json");
  await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return path;
}

/** 验证显式指定的 EdgeDriver 版本，避免驱动漂移后悄悄改变 WebView2 会话语义。 */
async function resolveEdgeDriver() {
  const configured = process.env.JA_E2E_EDGEDRIVER_PATH?.trim();
  if (!configured) return undefined;
  const path = resolve(configured);
  if (!(await stat(path)).isFile()) throw new Error("JA_E2E_EDGEDRIVER_PATH 不是文件");
  const result = await execFileAsync(path, ["--version"], {
    windowsHide: true,
    timeout: 10_000,
    maxBuffer: 64 * 1024,
  });
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`.trim();
  const version = output.match(/^Microsoft Edge WebDriver ([0-9]+(?:\.[0-9]+){3})\b/u)?.[1];
  if (!version) throw new Error("EdgeDriver 版本输出无效");
  return { path, version };
}

/** 在切换隔离 USERPROFILE 前解析 Cargo 实体路径，让 runner 不受 rustup shim 配置影响。 */
async function resolveCargoCommand() {
  const configured = process.env.JA_E2E_CARGO_COMMAND?.trim();
  if (configured) return resolve(configured);
  const result = await execFileAsync("where.exe", ["cargo.exe"], {
    windowsHide: true,
    timeout: 10_000,
    maxBuffer: 128 * 1024,
  });
  const command = result.stdout
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .find(Boolean);
  if (!command) throw new Error("无法定位 cargo.exe");
  return resolve(command);
}

/** 组装完整隔离环境；保留宿主 Cargo/Rustup 缓存但不继承应用配置目录。 */
function buildEnvironment(directories, ports, java, provider, tauriConfig, edgeDriver) {
  const inheritedPath = process.env.PATH ?? process.env.Path ?? "";
  const cargoHome =
    process.env.CARGO_HOME?.trim() || join(process.env.USERPROFILE ?? repoRoot, ".cargo");
  const rustupHome =
    process.env.RUSTUP_HOME?.trim() || join(process.env.USERPROFILE ?? repoRoot, ".rustup");
  const env = {
    ...process.env,
    APPDATA: directories.roaming,
    LOCALAPPDATA: directories.local,
    USERPROFILE: directories.settings,
    HOME: directories.settings,
    CARGO_HOME: cargoHome,
    RUSTUP_HOME: rustupHome,
    JA_E2E_RUNTIME_ROOT: directories.runtime,
    JA_E2E_EXIT_TRACE_PATH: join(directories.runtime, "ja-exit-trace.jsonl"),
    JA_E2E_DEV_PORT: String(ports.frontend),
    VITE_JA_E2E_PROJECT_PATH: directories.workspace,
    JA_E2E_JAVA_HOME: java.home,
    JA_JAVA25_HOME: java.home,
    JA_TEST_JAVA: java.executable,
    JAVA_HOME: java.home,
    JA_DEBUG_JAVA: java.executable,
    JA_DEBUG_JAR: resolve(
      process.env.JA_E2E_APP_SERVER_JAR?.trim() || repoPath("app-server/target/ja-app-server.jar"),
    ),
    CARGO_TARGET_DIR: resolve(
      process.env.JA_E2E_CARGO_TARGET_DIR?.trim() || repoPath("src-tauri/target/subagent-settings"),
    ),
    NO_PROXY: ["127.0.0.1", "localhost", "::1", process.env.NO_PROXY]
      .filter((value) => typeof value === "string" && value.trim().length > 0)
      .join(","),
    PATH: [
      join(java.home, "bin"),
      dirname(process.env.npm_execpath ?? ""),
      dirname(process.env.CARGO ?? ""),
      inheritedPath,
    ]
      .filter(Boolean)
      .join(";"),
    JA_E2E_SUBAGENT_SETTINGS_PROVIDER: provider.baseUrl,
    JA_E2E_SUBAGENT_SETTINGS_TAURI_CONFIG: tauriConfig,
  };
  delete env.Path;
  delete env.no_proxy;
  for (const name of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]) delete env[name];
  if (edgeDriver === undefined) {
    env.WEBVIEW2_USER_DATA_FOLDER = directories.webview;
    env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = `--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --remote-debugging-address=127.0.0.1 --remote-debugging-port=${ports.cdp}`;
  } else {
    env.JA_E2E_CARGO_COMMAND = edgeDriver.cargo;
    env.JA_E2E_EDGEDRIVER_PATH = edgeDriver.path;
    env.JA_E2E_EDGEDRIVER_PORT = String(ports.edgeDriver);
    env.JA_E2E_EDGEDRIVER_SESSION_PATH = ports.sessionPath;
    env.JA_E2E_WEBVIEW_DATA_DIR = directories.webview;
    delete env.WEBVIEW2_USER_DATA_FOLDER;
    delete env.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;
  }
  return env;
}

/** 用 pnpm.cmd 启动真实 Tauri；launcher PID 是本轮唯一可清理的进程树根。 */
async function startTauri(env, tauriConfig) {
  const configured = process.env.JA_E2E_PNPM_COMMAND?.trim();
  const pnpm =
    configured ||
    (
      await execFileAsync("where.exe", ["pnpm.cmd"], {
        windowsHide: true,
        timeout: 10_000,
        maxBuffer: 128 * 1024,
      })
    ).stdout
      .split(/\r?\n/u)
      .map((line) => line.trim())
      .find(Boolean);
  if (!pnpm) throw new Error("无法定位 pnpm.cmd");
  // cmd.exe 的 /s /c 合同要求最外层引号包住整条命令，内部再保留两个绝对路径引号。
  const command = `""${pnpm}" tauri dev --no-watch --config "${tauriConfig}""`;
  const child = spawn(process.env.ComSpec ?? "cmd.exe", ["/d", "/s", "/c", command], {
    cwd: repoRoot,
    env,
    windowsHide: true,
    windowsVerbatimArguments: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const output = [];
  child.stdout?.on("data", (chunk) => output.push(String(chunk).slice(-2000)));
  child.stderr?.on("data", (chunk) => output.push(String(chunk).slice(-2000)));
  return { child, output };
}

/** 只探测本轮固定 CDP 端口，页面/进程未就绪前有界重试。 */
async function waitForCdp(port, launch) {
  const deadline = Date.now() + 120_000;
  let lastProbe = "not_attempted";
  while (Date.now() < deadline) {
    if (launch.child.exitCode !== null) {
      throw new Error(`Tauri 在 CDP 就绪前退出：${launch.output.join("").slice(-4000)}`);
    }
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`, {
        signal: AbortSignal.timeout(1000),
      });
      lastProbe = `status=${response.status} ok=${response.ok}`;
      if (response.ok) return `http://127.0.0.1:${port}`;
    } catch (error) {
      // WebView2 browser/renderer 分阶段启动，在 deadline 内继续尝试。
      lastProbe = `error=${error instanceof Error ? error.name : typeof error}`;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error(
    `WebView2 CDP 未在 deadline 内启动 (${lastProbe})：${launch.output.join("").slice(-4000)}`,
  );
}

/** 等待 EdgeDriver runner 原子发布 ACK，并验证其 debugger 地址仍是 loopback。 */
async function waitForDriverCdp(sessionPath, launch) {
  const deadline = Date.now() + 120_000;
  let lastProbe = "session ack not observed";
  while (Date.now() < deadline) {
    if (launch.child.exitCode !== null) {
      throw new Error(`Tauri 在 EdgeDriver ACK 就绪前退出：${launch.output.join("").slice(-4000)}`);
    }
    try {
      const session = JSON.parse(await readFile(sessionPath, "utf8"));
      const debuggerPort = session?.debuggerPort;
      if (
        typeof session?.sessionId !== "string" ||
        !/^[a-f0-9]{16,128}$/u.test(session.sessionId) ||
        !Number.isSafeInteger(debuggerPort) ||
        debuggerPort < 1 ||
        debuggerPort > 65_535 ||
        !Number.isSafeInteger(session?.appPid) ||
        session.appPid < 1
      ) {
        throw new Error("EdgeDriver session ACK 值无效");
      }
      const endpoint = `http://127.0.0.1:${debuggerPort}`;
      const response = await fetch(`${endpoint}/json/version`, {
        signal: AbortSignal.timeout(1_000),
      });
      lastProbe = `status=${response.status} ok=${response.ok}`;
      if (response.ok) return endpoint;
    } catch (error) {
      lastProbe = `error=${error instanceof Error ? error.name : typeof error}`;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error(
    `EdgeDriver WebView2 CDP 未在 deadline 内启动 (${lastProbe})：${launch.output.join("").slice(-4000)}`,
  );
}

/** 连接页面并等待真实 Vite origin，避免把欢迎页或旧 WebView 页面当作产品页面。 */
async function waitForPage(browser, frontendPort) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const page = browser
      .contexts()
      .flatMap((context) => context.pages())
      .find((candidate) => !candidate.isClosed());
    if (page && page.url().includes(`127.0.0.1:${frontendPort}`)) return page;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error("隔离 WebView2 没有加载本轮 Vite 页面");
}

/** 进入设置分类；真实入口缺失时立即失败，不退化为直接改配置或 store。 */
async function openSettings(page, section) {
  const settings = page.getByRole("region", { name: "设置页面", exact: true });
  if (!(await settings.isVisible().catch(() => false))) {
    const sidebar = page.getByRole("button", { name: "显示侧边栏", exact: true });
    if (await sidebar.isVisible().catch(() => false)) await sidebar.click();
    await page.getByRole("button", { name: "设置", exact: true }).click();
  }
  await settings.waitFor({ state: "visible", timeout: timeoutMs });
  await expect(settings).not.toHaveAttribute("inert", "", { timeout: timeoutMs });
  await settings.getByRole("tab", { name: section, exact: true }).click();
  return settings;
}

/** 找到新设置的稳定标签；允许实现采用“策略”后缀但拒绝模糊匹配。 */
function subagentControls(settings) {
  const toggle = settings.getByRole("switch", { name: "启用子智能体", exact: true });
  const model = settings.getByRole("combobox", { name: /子智能体模型/u }).first();
  const reasoning = settings
    .getByRole("combobox", { name: /子智能体.*(?:思考|推理)|(?:思考|推理).*子智能体/u })
    .first();
  return { toggle, model, reasoning };
}

/** 发送真实 Composer 消息并等待 fake Provider 的可见最终答复。 */
async function sendMessage(page, text, provider) {
  const baseline =
    provider === undefined
      ? 0
      : Math.max(0, ...provider.attempts.map((attempt) => attempt.ordinal));
  const composer = page.getByRole("textbox", { name: "消息", exact: true });
  await composer.waitFor({ state: "visible", timeout: timeoutMs });
  await composer.fill(text);
  await composer.press("Enter");
  await expect(composer).toHaveValue("", { timeout: timeoutMs });
  await expect(
    page
      .getByRole("article", { name: "最终答复", exact: true })
      .filter({ hasText: /SUBAGENT_SETTINGS_/u })
      .last(),
  ).toBeVisible({ timeout: timeoutMs });
  return baseline;
}

/** 等待指定 fixture 请求，避免用固定 sleep 掩盖 Java/Provider/Tool 链路未完成。 */
async function waitForAttempt(provider, baseline, predicate, description) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const match = provider.attempts.find(
      (attempt) => attempt.ordinal > baseline && predicate(attempt),
    );
    if (match) return match;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 150));
  }
  throw new Error(
    `未观察到 Provider 请求：${description} baseline=${baseline} attempts=${JSON.stringify(provider.attempts)}`,
  );
}

/** 断言搜索路由到真实子智能体分类，并验证默认值及提示文案。 */
async function verifySettings(page) {
  const settings = await openSettings(page, "子智能体");
  await expect(settings.getByRole("tab", { name: "模型", exact: true })).toBeVisible();
  const { toggle, model } = subagentControls(settings);
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await expect(model).toContainText("跟随父任务");
  await expect(settings).toContainText("仅对保存后新建的会话生效，已有会话保持原设置。");
  const search = settings.getByRole("textbox", { name: "搜索设置", exact: true });
  await search.fill("子智能体");
  await expect(settings.getByRole("option").filter({ hasText: "子智能体" }).first()).toBeVisible();
  await settings.getByRole("option").filter({ hasText: "子智能体" }).first().click();
  await expect(toggle).toBeFocused();
  await search.fill("");
  return { settings, toggle, model };
}

/** 通过真实选择器选择指定上游模型，且主标签必须是真实 model 标识。 */
async function selectChildModel(page, model) {
  await model.click();
  const option = page.getByRole("option").filter({ hasText: "e2e-child" }).first();
  await expect(option).toBeVisible({ timeout: timeoutMs });
  await expect(option).toContainText("e2e-child");
  await option.click();
  await expect(model).toContainText("e2e-child");
}

/** 选择指定子模型的思考档位；选项经 Radix Portal 渲染在设置区域之外，必须从 page 查询。 */
async function selectChildReasoning(page, reasoning, level) {
  await expect(reasoning).toContainText("low");
  await reasoning.click();
  const option = page.getByRole("option", { name: new RegExp(`^${level}$`, "u") }).last();
  await expect(option).toBeVisible({ timeout: timeoutMs });
  await option.click();
  await expect(reasoning).toContainText(level);
}

/** 关闭/开启开关仅走真实 Radix switch，重启前后都由设置页面读取确认。 */
async function setEnabled(toggle, enabled) {
  const current = (await toggle.getAttribute("aria-checked")) === "true";
  if (current !== enabled) await toggle.click();
  await expect(toggle).toHaveAttribute("aria-checked", String(enabled));
}

/** 校验一轮会话快照：旧会话仍使用创建时父模型，新会话使用指定子模型。 */
async function verifySessionSnapshots(page, provider, settings) {
  await settings.getByRole("button", { name: "返回应用", exact: true }).click();
  const followBaseline = await sendMessage(page, "SUBAGENT_SETTINGS_FOLLOW", provider);
  await waitForAttempt(
    provider,
    followBaseline,
    (attempt) => attempt.model === "e2e-parent" && attempt.toolNames.includes("spawn_agent"),
    "跟随父任务父请求",
  );
  const followChild = await waitForAttempt(
    provider,
    followBaseline,
    (attempt) => attempt.childMarker && attempt.model === "e2e-parent",
    "跟随父任务子请求",
  );
  if (followChild.reasoningEffort !== null) {
    throw new Error(`跟随父任务不应独立覆盖 reasoning：${followChild.reasoningEffort}`);
  }

  await openSettings(page, "子智能体");
  const selectedSettings = page.getByRole("region", { name: "设置页面", exact: true });
  const selectedControls = subagentControls(selectedSettings);
  await selectChildModel(page, selectedControls.model);
  await selectChildReasoning(page, selectedControls.reasoning, "high");
  await page
    .getByRole("region", { name: "设置页面", exact: true })
    .getByRole("button", { name: "返回应用", exact: true })
    .click();
  const oldSessionBaseline = await sendMessage(page, "SUBAGENT_SETTINGS_OLD_SESSION", provider);
  const oldSessionChild = await waitForAttempt(
    provider,
    oldSessionBaseline,
    (attempt) => attempt.childMarker && attempt.model === "e2e-parent",
    "旧会话保持父模型",
  );
  if (!oldSessionChild) throw new Error("旧会话未保持创建时子模型策略");
  if (oldSessionChild.reasoningEffort !== null) {
    throw new Error(`旧会话未保持创建时父 reasoning：${oldSessionChild.reasoningEffort}`);
  }

  await page.getByRole("button", { name: "新会话", exact: true }).click();
  const newSessionBaseline = await sendMessage(page, "SUBAGENT_SETTINGS_NEW_SESSION", provider);
  await waitForAttempt(
    provider,
    newSessionBaseline,
    (attempt) => attempt.model === "e2e-parent" && attempt.toolNames.includes("spawn_agent"),
    "新会话父请求",
  );
  const newChild = await waitForAttempt(
    provider,
    newSessionBaseline,
    (attempt) => attempt.childMarker && attempt.model === "e2e-child",
    "指定模型子请求",
  );
  if (newChild.reasoningEffort !== "high") {
    throw new Error(`指定模型子请求未使用 high reasoning：${newChild.reasoningEffort}`);
  }

  await openSettings(page, "子智能体");
  const controls = subagentControls(page.getByRole("region", { name: "设置页面", exact: true }));
  await setEnabled(controls.toggle, false);
  await page
    .getByRole("region", { name: "设置页面", exact: true })
    .getByRole("button", { name: "返回应用", exact: true })
    .click();
  await page.getByRole("button", { name: "新会话", exact: true }).click();
  const disabledBaseline = await sendMessage(page, "SUBAGENT_SETTINGS_DISABLED", provider);
  const disabled = await waitForAttempt(
    provider,
    disabledBaseline,
    (attempt) => attempt.model === "e2e-parent" && !attempt.toolNames.includes("spawn_agent"),
    "禁用新会话无 spawn_agent",
  );
  if (disabled.toolNames.includes("spawn_agent"))
    throw new Error("禁用子智能体的新会话仍暴露 spawn_agent");
  const disabledChild = provider.attempts.find(
    (attempt) => attempt.ordinal > disabledBaseline && attempt.childMarker,
  );
  if (disabledChild) throw new Error("禁用子智能体的新会话仍创建了子智能体请求");
}

/** 读取配置 UI 的冷启动投影，证明设置保存不依赖当前 React 内存状态。 */
async function verifyRestart(page) {
  const settings = await openSettings(page, "子智能体");
  const { toggle, model, reasoning } = subagentControls(settings);
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  await expect(model).toContainText("e2e-child");
  await expect(reasoning).toContainText("high");
  await expect(settings.getByRole("tab", { name: "子智能体", exact: true })).toBeVisible();
}

/** 只终止当前 launcher 进程树；不按镜像名全局 taskkill，避免触碰用户已有 Ja。 */
async function stopOwnedTree(launch) {
  if (!launch?.child?.pid) return;
  await execFileAsync("taskkill.exe", ["/PID", String(launch.child.pid), "/T", "/F"], {
    windowsHide: true,
    timeout: 20_000,
    maxBuffer: 512 * 1024,
  }).catch(() => undefined);
}

/** 启动一次完整真实桌面阶段；phase=restart 复用同一目录而不重写配置。 */
async function runPhase({ directories, provider, java, edgeDriver, restart = false }) {
  const used = new Set();
  const frontend = await reservePort(used);
  used.add(frontend);
  const cdp = edgeDriver === undefined ? await reservePort(used) : undefined;
  if (cdp !== undefined) used.add(cdp);
  const edgeDriverPort = edgeDriver === undefined ? undefined : await reservePort(used);
  if (edgeDriverPort !== undefined) used.add(edgeDriverPort);
  const sessionPath =
    edgeDriver === undefined
      ? undefined
      : join(directories.runtime, `edgedriver-session-${restart ? "restart" : "first"}.json`);
  if (sessionPath !== undefined) await rm(sessionPath, { force: true });
  const tauriConfig = await writeTauriOverlay(directories, frontend, edgeDriver !== undefined);
  const env = buildEnvironment(
    directories,
    { frontend, cdp, edgeDriver: edgeDriverPort, sessionPath },
    java,
    provider,
    tauriConfig,
    edgeDriver,
  );
  const launch = await startTauri(env, tauriConfig);
  let browser;
  try {
    const endpoint =
      edgeDriver === undefined
        ? await waitForCdp(cdp, launch)
        : await waitForDriverCdp(sessionPath, launch);
    browser = await chromium.connectOverCDP(endpoint);
    const page = await waitForPage(browser, frontend);
    await page.setViewportSize({ width: 1280, height: 820 });
    await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
    if (restart) {
      await verifyRestart(page);
    } else {
      const { settings } = await verifySettings(page);
      await verifySessionSnapshots(page, provider, settings);
    }
    return { launch, browser };
  } catch (error) {
    await browser?.close().catch(() => undefined);
    await stopOwnedTree(launch);
    throw error;
  }
}

/** 先执行 controls，再冷启动复用 Ja Home；最终清理仅作用于本轮目录与 PID。 */
async function main() {
  validateEnvironment();
  const externalEndpoint = process.env.JA_E2E_SUBAGENT_SETTINGS_CDP_ENDPOINT?.trim();
  const directories = await createDirectories();
  const provider = await startProvider();
  let java;
  let phase;
  try {
    if (externalEndpoint) {
      const browser = await chromium.connectOverCDP(externalEndpoint);
      try {
        const page = browser.contexts().flatMap((context) => context.pages())[0];
        if (!page) throw new Error("外部隔离 CDP 没有页面");
        await verifySettings(page);
      } finally {
        await browser.close();
      }
    } else {
      java = await resolveJava25();
      const configuredEdgeDriver = await resolveEdgeDriver();
      const edgeDriver =
        configuredEdgeDriver === undefined
          ? undefined
          : { ...configuredEdgeDriver, cargo: await resolveCargoCommand() };
      const jarPath = resolve(
        process.env.JA_E2E_APP_SERVER_JAR?.trim() ||
          repoPath("app-server/target/ja-app-server.jar"),
      );
      if (!(await stat(jarPath)).isFile()) throw new Error(`缺少 App Server JAR：${jarPath}`);
      await writeSettings(directories, provider);
      phase = await runPhase({ directories, provider, java, edgeDriver });
      await phase.browser.close();
      await stopOwnedTree(phase.launch);
      phase = await runPhase({ directories, provider, java, edgeDriver, restart: true });
      await phase.browser.close();
      await stopOwnedTree(phase.launch);
    }
    await mkdir(directories.artifacts, { recursive: true });
    await writeFile(
      join(directories.artifacts, "provider-attempts.json"),
      `${JSON.stringify(provider.attempts, null, 2)}\n`,
      "utf8",
    );
    process.stdout.write(`JA_SUBAGENT_SETTINGS_WEBVIEW2_OK evidence=${directories.artifacts}\n`);
  } finally {
    await phase?.browser?.close().catch(() => undefined);
    await stopOwnedTree(phase?.launch);
    await provider.close();
    if (process.env.JA_E2E_SUBAGENT_SETTINGS_KEEP_TEMP !== "1") {
      await rm(directories.root, { recursive: true, force: true }).catch(() => undefined);
    }
  }
}

await main();
