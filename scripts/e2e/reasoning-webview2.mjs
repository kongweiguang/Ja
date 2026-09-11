// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 三种 Provider 原生 reasoning 的 Windows Tauri/WebView2 真窗验收。
 *
 * 每个协议都启动一套独立的 debug JAR、Vite、WebView2 profile、Ja home、runtime、workspace
 * 和 loopback Provider。fixture 只模拟上游 HTTP SSE；JA-RPC、Java persistence、Rust bridge
 * 与 React DOM 均走真实生产链路。此 runner 不连接用户已有 Ja，不改仓库配置，也不使用付费请求。
 */

import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { chromium } from "@playwright/test";
import {
  ALL_PROTOCOLS,
  FINAL_MARKERS,
  PRIVATE_MARKERS,
  REASONING_MARKERS,
  startReasoningProviderFixture,
} from "./fixtures/reasoning-provider.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const DEFAULT_CARGO_TARGET_DIRECTORY = join(repoRoot, "target", "codex-reasoning-webview2");
const STABLE_PORT_RANGE = Object.freeze({ start: 41_000, size: 8_000 });

/** 解析 CLI；协议必须显式属于三种当前 Provider API，避免验收错连其它端点。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: DEFAULT_CARGO_TARGET_DIRECTORY,
    protocol: undefined,
    preflightOnly: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--preflight-only") {
      options.preflightOnly = true;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (argument === "--jar") options.jar = resolve(value);
    else if (argument === "--java-home") options.javaHome = resolve(value);
    else if (argument === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else if (argument === "--protocol") options.protocol = value;
    else throw new Error(`unknown argument: ${argument}`);
    index += 1;
  }
  if (options.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (options.jar === undefined) throw new Error("--jar is required");
  if (options.protocol !== undefined && !ALL_PROTOCOLS.includes(options.protocol)) {
    throw new Error(`--protocol must be one of ${ALL_PROTOCOLS.join(", ")}`);
  }
  return options;
}

/** 拒绝 broad 目录，保证 cleanup 只作用于本轮随机 OS temp 子目录。 */
export function assertOwnedTemporaryPath(path, label) {
  const root = resolve(tmpdir());
  const target = resolve(path);
  const relation = relative(root, target);
  if (relation === "" || relation.startsWith("..") || isAbsolute(relation)) {
    throw new Error(`${label} must be a child of the OS temp directory`);
  }
  return target;
}

/** 在固定的回环端口区间选择可用端口；调用方随后仍以 CDP/HTTP owner 复验。 */
async function reservePort(excludedPorts = new Set()) {
  const offset = (Date.now() + process.pid) % STABLE_PORT_RANGE.size;
  for (let index = 0; index < STABLE_PORT_RANGE.size; index += 1) {
    const port = STABLE_PORT_RANGE.start + ((offset + index) % STABLE_PORT_RANGE.size);
    if (excludedPorts.has(port)) continue;
    const server = createServer();
    const available = await new Promise((resolvePromise) => {
      server.once("error", () => resolvePromise(false));
      server.listen(port, "127.0.0.1", () => resolvePromise(true));
    });
    if (!available) {
      server.close();
      continue;
    }
    await new Promise((resolvePromise) => server.close(resolvePromise));
    return port;
  }
  throw new Error("failed to reserve a stable loopback port");
}

/** 创建协议专属临时目录，所有 native/user data 路径均离开真实 Ja profile。 */
async function createRunDirectories(protocol) {
  const root = await mkdtemp(join(tmpdir(), `ja-reasoning-${protocol}-`));
  const directories = {
    root,
    settings: join(root, "profile"),
    home: join(root, "profile", ".ja"),
    workspace: join(root, "workspace"),
    webview: join(root, "webview2"),
    runtime: join(root, "runtime"),
    roaming: join(root, "appdata", "roaming"),
    local: join(root, "appdata", "local"),
    evidence: join(root, "evidence"),
  };
  await Promise.all(Object.values(directories).map((path) => mkdir(path, { recursive: true })));
  await writeFile(
    join(directories.workspace, "reasoning-fixture.txt"),
    "JA_REASONING_FIXTURE_OK\n",
    "utf8",
  );
  return directories;
}

/** 把用户可见协议名转成 TOML 安全字符串，拒绝控制字符与引号逃逸。 */
function tomlString(value) {
  if (typeof value !== "string" || value.length === 0 || /[\0\r\n"]/u.test(value)) {
    throw new Error("invalid TOML string");
  }
  return JSON.stringify(value);
}

/** 为每个协议写入只含 loopback Provider 的严格 v1 配置；credential 只保留不透明引用。 */
async function writeIsolatedSettings(home, protocol, baseUrl) {
  const model = `reasoning-${protocol}`;
  const config = [
    "schema_version = 1",
    "config_revision = 1",
    'default_access_mode = "full_access"',
    'default_provider_id = "provider_e2e"',
    'default_model_id = "model_e2e"',
    'default_reasoning_level = "high"',
    "subagents = { enabled = true, provider_id = { __ja_null = true }, model_id = { __ja_null = true }, reasoning_level = { __ja_null = true } }",
    "interaction = { clarification_enabled = true }",
    "mcp_servers = []",
    "skills = []",
    "",
    "[[providers]]",
    'provider_id = "provider_e2e"',
    `name = ${tomlString(`Reasoning ${protocol}`)}`,
    `api = ${tomlString(protocol)}`,
    `base_url = ${tomlString(baseUrl)}`,
    'credential_id = "cred_e2e"',
    "[providers.network_timeouts]",
    "connect_timeout_ms = 5000",
    "request_timeout_ms = 120000",
    "[providers.agent_defaults]",
    "[providers.agent_defaults.context]",
    "auto_compact = true",
    "[providers.agent_defaults.turn_limits]",
    "max_model_rounds = 8",
    "max_tool_calls = 8",
    "wall_timeout_ms = 120000",
    "[[providers.models]]",
    'model_id = "model_e2e"',
    `name = ${tomlString(`Reasoning ${protocol} model`)}`,
    `model = ${tomlString(model)}`,
    'reasoning_level_map = { high = "high" }',
    'default_reasoning_level = "high"',
    "[providers.models.capabilities]",
    "context_window_tokens = 128000",
    "max_output_tokens = 8192",
    "",
  ].join("\n");
  await writeFile(join(home, "config.toml"), config, "utf8");
}

/** 读取生产窗口并生成唯一 identifier/CSP overlay，不修改 src-tauri 配置文件。 */
async function writeTauriOverlay(directories, frontendPort) {
  const [base, windows] = await Promise.all([
    readFile(join(repoRoot, "src-tauri", "tauri.conf.json"), "utf8").then(JSON.parse),
    readFile(join(repoRoot, "src-tauri", "tauri.windows.conf.json"), "utf8").then(JSON.parse),
  ]);
  const baseWindow = base?.app?.windows?.find((window) => window?.label === "main");
  const windowsWindow = windows?.app?.windows?.find((window) => window?.label === "main");
  if (baseWindow === undefined) throw new Error("production main window configuration is missing");
  const origin = `http://localhost:${frontendPort}`;
  const websocket = `ws://localhost:${frontendPort}`;
  const overlay = {
    identifier: `io.github.kongweiguang.ja.reasoning${randomUUID().replaceAll("-", "")}`,
    build: { devUrl: origin },
    app: {
      windows: [{ ...baseWindow, ...(windowsWindow ?? {}) }],
      security: {
        devCsp: `default-src 'self'; connect-src 'self' ipc: http://ipc.localhost ${origin} ${websocket}; img-src 'self' data: blob: ja-attachment: http://ja-attachment.localhost; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self' data:; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`,
      },
    },
  };
  const path = join(directories.runtime, "tauri.reasoning.conf.json");
  await writeFile(path, `${JSON.stringify(overlay, null, 2)}\n`, "utf8");
  return path;
}

/** 验证 JDK25、Node24、pnpm10.33 与待验收 JAR，禁止隐式使用环境 JDK21。 */
async function validateToolchain({ javaHome, jar }) {
  const java = join(javaHome, "bin", "java.exe");
  const [javaResult, nodeResult, pnpmResult, jarStat] = await Promise.all([
    execFileAsync(java, ["-version"], { windowsHide: true, timeout: 15_000 }),
    execFileAsync("node.exe", ["--version"], { windowsHide: true, timeout: 15_000 }),
    execFileAsync("pwsh.exe", ["-NoProfile", "-Command", "& pnpm.cmd --version"], {
      windowsHide: true,
      timeout: 15_000,
    }),
    stat(jar),
  ]);
  const javaVersion = `${javaResult.stdout}\n${javaResult.stderr}`;
  if (!/version "25(?:\.|"|\s)/u.test(javaVersion))
    throw new Error("reasoning runner requires JDK25");
  if (!/^v24\./u.test(nodeResult.stdout.trim()))
    throw new Error("reasoning runner requires Node24");
  if (pnpmResult.stdout.trim() !== "10.33.0")
    throw new Error("reasoning runner requires pnpm10.33.0");
  if (!jarStat.isFile() || jarStat.size < 1) throw new Error("reasoning runner JAR is empty");
  return { java, javaHome, jarSize: jarStat.size };
}

/** 构造真实 Tauri dev 子进程环境；用户 profile、外部 Provider 环境变量和 Java options 全部隔离。 */
function buildEnvironment({ directories, java, jar, frontendPort, cdpPort, cargoTargetDirectory }) {
  const inheritedPath = process.env.PATH ?? process.env.Path ?? "";
  const inheritedUserProfile = process.env.USERPROFILE?.trim() || undefined;
  const inheritedCargoHome =
    process.env.CARGO_HOME?.trim() ||
    (inheritedUserProfile === undefined ? undefined : join(inheritedUserProfile, ".cargo"));
  const inheritedRustupHome =
    process.env.RUSTUP_HOME?.trim() ||
    (inheritedUserProfile === undefined ? undefined : join(inheritedUserProfile, ".rustup"));
  const environment = {
    ...process.env,
    APPDATA: directories.roaming,
    LOCALAPPDATA: directories.local,
    USERPROFILE: directories.settings,
    ...(inheritedCargoHome === undefined ? {} : { CARGO_HOME: inheritedCargoHome }),
    ...(inheritedRustupHome === undefined ? {} : { RUSTUP_HOME: inheritedRustupHome }),
    JA_DEBUG_JAVA: java,
    JA_DEBUG_JAR: jar,
    JA_E2E_RUNTIME_ROOT: directories.runtime,
    JA_E2E_DEV_PORT: String(frontendPort),
    VITE_JA_E2E_PROJECT_PATH: directories.workspace,
    CARGO_TARGET_DIR: cargoTargetDirectory,
    WEBVIEW2_USER_DATA_FOLDER: directories.webview,
    ...(cdpPort === undefined
      ? {}
      : {
          WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --remote-debugging-address=127.0.0.1 --remote-debugging-port=${cdpPort}`,
        }),
    JAVA_HOME: dirname(dirname(java)),
    JA_JAVA25_HOME: dirname(dirname(java)),
    PATH: [dirname(java), inheritedPath].filter(Boolean).join(";"),
  };
  if (cdpPort === undefined) delete environment.WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS;
  delete environment.Path;
  for (const name of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"])
    delete environment[name];
  for (const name of Object.keys(environment)) {
    if (/^JA_(?:E2E_)?REAL_PROVIDER/u.test(name)) delete environment[name];
  }
  return environment;
}

/** 启动真实 Tauri dev；仅保留尾部诊断，失败报告不写入用户配置或请求正文。 */
function launchTauri(overlay, environment) {
  // 直接执行仓库锁定版本的 CLI 脚本并传递 argv，绕开 .cmd/cmd.exe 的二次解析；这同时
  // 保证带空格的隔离 runtime 路径不会把引号或路径片段传成错误的 Tauri 参数。
  const tauriCli = join(repoRoot, "node_modules", "@tauri-apps", "cli", "tauri.js");
  const child = spawn(process.execPath, [tauriCli, "dev", "--no-watch", "--config", overlay], {
    cwd: repoRoot,
    env: environment,
    windowsHide: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const output = { stdout: "", stderr: "" };
  const append = (key, chunk) => {
    output[key] = `${output[key]}${String(chunk)}`.slice(-8_192);
  };
  child.stdout?.on("data", (chunk) => append("stdout", chunk));
  child.stderr?.on("data", (chunk) => append("stderr", chunk));
  return { child, output };
}

/** 等待 fresh WebView2 UDF 发布稳定文件；首次启动可能创建 renderer 但暂不开放 CDP。 */
async function waitForWebViewProfileReady(directories, launch, deadline) {
  const profileRoot = join(directories.webview, "EBWebView");
  while (Date.now() < deadline) {
    if (launch.child.exitCode !== null) {
      throw new Error(
        `Tauri exited during WebView2 profile prime: ${launch.output.stderr.slice(-2_000)}`,
      );
    }
    try {
      const [localState, preferences] = await Promise.all([
        stat(join(profileRoot, "Local State")),
        stat(join(profileRoot, "Default", "Preferences")),
      ]);
      if (
        localState.isFile() &&
        localState.size > 0 &&
        preferences.isFile() &&
        preferences.size > 0
      )
        return;
    } catch {
      // WebView2 创建 browser、renderer 与 profile 文件是分阶段完成的，期限内继续观察。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error("WebView2 profile prime timed out");
}

/** 等待预热 launcher 的 Vite 端口释放，避免正式 Tauri 启动争用旧 dev server。 */
async function waitForPortAvailable(port, deadline) {
  while (Date.now() < deadline) {
    const server = createServer();
    const available = await new Promise((resolvePromise) => {
      server.once("error", () => resolvePromise(false));
      server.listen(port, "127.0.0.1", () => resolvePromise(true));
    });
    if (available) {
      await new Promise((resolvePromise) => server.close(resolvePromise));
      return;
    }
    server.close();
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error(`Vite dev port ${port} remained occupied after WebView2 profile prime`);
}

/** 采集隔离 UDF 对应的 WebView2 进程与目标端口 owner，只返回启动归属布尔值。 */
async function inspectWebViewStartup(directories, cdpPort, launcherPid) {
  const profile = directories.webview.replaceAll("/", "\\").toLowerCase();
  const encodedProfile = Buffer.from(profile, "utf8").toString("base64");
  const script = [
    `$profile = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedProfile}'))`,
    "$processes = @(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.ToLowerInvariant().Replace('/', '\\').Contains($profile) } | ForEach-Object {",
    "  [pscustomobject]@{ pid = $_.ProcessId; parentPid = $_.ParentProcessId; name = $_.Name; commandLine = $_.CommandLine }",
    "})",
    `$listeners = @(Get-NetTCPConnection -LocalPort ${cdpPort} -ErrorAction SilentlyContinue | ForEach-Object { [pscustomobject]@{ pid = $_.OwningProcess; state = $_.State; address = $_.LocalAddress } })`,
    "[pscustomobject]@{ processes = $processes; listeners = $listeners } | ConvertTo-Json -Compress -Depth 4",
  ].join("\n");
  try {
    const result = await execFileAsync("pwsh.exe", ["-NoProfile", "-Command", script], {
      windowsHide: true,
      timeout: 10_000,
      maxBuffer: 256 * 1024,
    });
    const payload = JSON.parse(result.stdout.trim() || "{}");
    const processes = Array.isArray(payload.processes)
      ? payload.processes
      : payload.processes === undefined
        ? []
        : [payload.processes];
    const listeners = Array.isArray(payload.listeners)
      ? payload.listeners
      : payload.listeners === undefined
        ? []
        : [payload.listeners];
    return {
      launcherPid,
      expectedPort: cdpPort,
      processes: processes.map((processInfo) => {
        const commandLine = String(processInfo.commandLine ?? "").toLowerCase();
        return {
          pid: processInfo.pid,
          parentPid: processInfo.parentPid,
          name: processInfo.name,
          hasIsolatedUserData: commandLine.includes(profile),
          hasExpectedRemotePort: commandLine.includes(`--remote-debugging-port=${cdpPort}`),
          hasLoopbackRemoteAddress: commandLine.includes("--remote-debugging-address=127.0.0.1"),
        };
      }),
      listeners: listeners.map((listener) => ({
        pid: listener.pid,
        state: listener.state,
        address: listener.address,
        belongsToObservedProcess: processes.some(
          (processInfo) => Number(processInfo.pid) === Number(listener.pid),
        ),
      })),
    };
  } catch (error) {
    return {
      launcherPid,
      expectedPort: cdpPort,
      inspectionError: String(error?.message ?? error).slice(0, 500),
    };
  }
}

/** 等待本轮 WebView2 CDP `/json/version`，launcher 提前退出立即失败。 */
async function waitForCdp(endpoint, launch, deadline, directories) {
  while (Date.now() < deadline) {
    if (launch.child.exitCode !== null)
      throw new Error(`Tauri exited before CDP: ${launch.output.stderr.slice(-2_000)}`);
    try {
      const response = await fetch(`${endpoint}/json/version`, {
        signal: AbortSignal.timeout(2_000),
      });
      const payload = await response.json();
      if (response.ok && typeof payload.webSocketDebuggerUrl === "string") return endpoint;
    } catch {
      // WebView2 冷启动期间 endpoint 暂不可用，继续有界重试。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  const port = Number.parseInt(new URL(endpoint).port, 10);
  const startup = await inspectWebViewStartup(directories, port, launch.child.pid);
  throw new Error(
    `WebView2 CDP startup timed out: ${JSON.stringify(startup)} ${launch.output.stderr.slice(-2_000)}`,
  );
}

/** 从 CDP contexts 只选择本轮 localhost Vite 页面；禁止把其它浏览器 tab 当作 Ja 窗口。 */
async function findTauriPage(browser, frontendPort, deadline) {
  while (Date.now() < deadline) {
    for (const context of browser.contexts()) {
      for (const page of context.pages()) {
        if (
          page.url().includes(`localhost:${frontendPort}`) ||
          page.url().includes("tauri://localhost")
        )
          return page;
      }
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error("Ja Tauri main WebView was not found");
}

/** 只杀本 runner 的 cmd/Tauri 进程树；不按 ja.exe/java.exe 名称结束已有用户进程。 */
async function terminateOwnedLauncher(launch) {
  const pid = launch?.child?.pid;
  if (!Number.isSafeInteger(pid) || launch.child.exitCode !== null) return;
  await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
    windowsHide: true,
    timeout: 15_000,
    maxBuffer: 512 * 1024,
  }).catch(() => undefined);
  const deadline = Date.now() + 30_000;
  while (launch.child.exitCode === null && Date.now() < deadline) {
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
}

/** 等待真实应用 ready、运行时连接与 Composer；项目准入必须在 Provider 凭据就绪后单独执行。 */
async function waitForApplication(page, deadline) {
  await page
    .locator('.ja-shell[data-app-ready="true"]')
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await page
    .getByRole("status", { name: "本地运行时：已连接", exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await page
    .getByRole("textbox", { name: "消息", exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
}

/**
 * reload 后若观察到 RuntimeHost 为 stopped，这里通过生产 typed adapter 显式恢复 native
 * generation，再等待 React 重新完成 scope admission；验收只证明显式恢复后的历史可用，
 * 不把 reload 无需重连当作产品保证。
 */
async function restoreRuntimeAfterReload(page, deadline) {
  await page.waitForFunction(
    () => typeof globalThis.__TAURI_INTERNALS__?.invoke === "function",
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  await page.evaluate(
    async ({ timeoutMs }) => {
      const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
      const adapter = createRuntimeHostAdapter();
      const deadlineAt = Date.now() + timeoutMs;
      let state = await adapter.state();
      while (state.status === "starting" || state.status === "stopping") {
        if (Date.now() >= deadlineAt)
          throw new Error(`runtime restore timed out in ${state.status}`);
        await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
        state = await adapter.state();
      }
      if (state.status === "stopped") state = await adapter.start();
      if (state.status !== "ready" && state.status !== "busy") {
        throw new Error(`runtime restore did not reach ready: ${state.status}`);
      }
      const confirmed = await adapter.state();
      if (confirmed.status !== "ready" && confirmed.status !== "busy") {
        throw new Error(`runtime restore confirmation failed: ${confirmed.status}`);
      }
    },
    { timeoutMs: Math.max(1, deadline - Date.now()) },
  );
  await waitForApplication(page, deadline);
}

/** 通过真实导航动作建立隔离项目的 native workspace binding，避免把未配置 Provider 当作项目失败。 */
async function ensureProject(page, deadline) {
  const project = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]').first();
  if ((await project.count()) === 0) {
    await page
      .getByRole("button", { name: "添加项目", exact: true })
      .click({ timeout: Math.max(1, deadline - Date.now()) });
  }
  await page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .first()
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
}

/** 通过真实 typed Settings adapter 写入隔离凭据并确认 Java owner 已发布配置状态。 */
async function configureProviderCredential(page, providerBaseUrl, protocol) {
  return page.evaluate(
    async ({ endpoint, expectedApi }) => {
      const { TauriSettingsAdapter } = await import("/src/api/tauri/settings.ts");
      const settings = new TauriSettingsAdapter();
      const snapshot = await settings.snapshot();
      const provider = snapshot.document.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (provider === undefined || provider.api !== expectedApi || provider.baseUrl !== endpoint) {
        throw new Error("isolated reasoning Provider configuration mismatch");
      }
      // Windows auth.json 必须由 Java credential owner 原子写入并设置 ACL；直接写文件会被
      // CredentialStore 视为 IO_ERROR，即便 JSON 内容正确也会在首个 Turn 解析为无凭据。
      await settings.setCredential(
        provider.credentialId,
        "isolated-no-billing-token",
        snapshot.cas.credentialVersion,
      );
      const verified = await settings.snapshot();
      const verifiedProvider = verified.document.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (verifiedProvider?.credentialConfigured !== true) {
        throw new Error("isolated reasoning credential was not configured");
      }
      const model = verifiedProvider.models[0];
      if (
        model === undefined ||
        model.modelId !== "model_e2e" ||
        model.defaultReasoningLevel !== "high"
      ) {
        throw new Error("isolated reasoning model configuration mismatch");
      }
      return { credentialConfigured: true };
    },
    { endpoint: providerBaseUrl, expectedApi: protocol },
  );
}

/** 创建固定 reasoningLevel 的 durable Thread；凭据与 native workspace 已在前置阶段确认。 */
async function createReasoningThread(page, workspaceRoot, protocol) {
  return page.evaluate(
    async ({ cwd, expectedApi }) => {
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      const created = await createHistoryAdapter().threadCreate({
        cwd,
        title: `Reasoning ${expectedApi}`,
        providerId: "provider_e2e",
        modelId: "model_e2e",
        reasoningLevel: "high",
        accessMode: "full_access",
        collaborationMode: "default",
      });
      return { threadId: created.threadId };
    },
    { cwd: workspaceRoot, expectedApi: protocol },
  );
}

/** 选择 Java 返回的精确 Thread identity，避免标题或 DOM 顺序猜测导致跨会话误断言。 */
async function selectThread(page, threadId, deadline) {
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  if ((await row.getAttribute("aria-current")) !== "page")
    await row.click({ timeout: Math.max(1, deadline - Date.now()) });
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
}

/** 读取 reasoning/tool work process 的公开 DOM 序列，不读取 React store 或 Provider body。 */
async function processItems(page, deadline) {
  const process = page.locator("section.ja-work-process").last();
  await process.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  return process.locator(".ja-work-process__steps > li").evaluateAll((items) =>
    items.map((item) => {
      if (item.classList.contains("ja-work-step--reasoning")) {
        return { kind: "reasoning", text: item.textContent?.trim() ?? "" };
      }
      const tool = item.querySelector(".ja-tool-details");
      if (tool !== null)
        return {
          kind: "tool",
          text: item.textContent?.trim() ?? "",
          toolKind: tool.getAttribute("data-tool-kind") ?? "unknown",
        };
      return { kind: "other", text: item.textContent?.trim() ?? "" };
    }),
  );
}

/** 使用无 sleep 的公开 DOM 条件等待某一 reasoning marker 或 final marker。 */
async function waitForText(page, text, deadline) {
  await page
    .getByText(text, { exact: false })
    .last()
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
}

/** 等待 loopback fixture 已经发出指定 Tool 边界，避免用固定文本或可忽略超时掩盖流转停滞。 */
async function waitForFixtureStage(fixture, stage, deadline) {
  while (Date.now() < deadline) {
    if (fixture.stages.includes(stage)) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 25));
  }
  throw new Error(`reasoning fixture stage ${stage} timed out`);
}

/** 展开过程 disclosure；Reasoning 存在时生产组件默认保持展开，但断言不依赖 CSS 高度。 */
async function expandProcess(page, deadline) {
  const process = page.locator("section.ja-work-process").last();
  await process.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const trigger = process.locator(".ja-work-process__trigger");
  if ((await trigger.getAttribute("aria-expanded")) !== "true")
    await trigger.click({ timeout: Math.max(1, deadline - Date.now()) });
  await process
    .locator(".ja-work-process__steps")
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
}

/**
 * 展开当前历史中的每个 Work Process，确保公共 reasoning marker 不因 disclosure 状态缺失；
 * expectedCount 用于等待异步 history snapshot 完成，避免在终态先于历史投影时漏掉新 process。
 */
async function expandAllProcesses(page, deadline, expectedCount) {
  const processes = page.locator("section.ja-work-process");
  const requiredCount = expectedCount ?? 1;
  while (Date.now() < deadline && (await processes.count()) < requiredCount)
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  if ((await processes.count()) < requiredCount)
    throw new Error(`expected ${requiredCount} Work Processes, found ${await processes.count()}`);
  await processes.last().waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const count = await processes.count();
  for (let index = 0; index < count; index += 1) {
    const process = processes.nth(index);
    const trigger = process.locator(".ja-work-process__trigger");
    if ((await trigger.getAttribute("aria-expanded")) !== "true")
      await trigger.click({ timeout: Math.max(1, deadline - Date.now()) });
    await process
      .locator(".ja-work-process__steps")
      .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  }
}

/** 在真实 Tauri invoke 的最外层记录 ja_turn_start 稳定错误，保留原始 rejection 语义且不触碰参数。 */
async function installTauriInvokeDiagnostics(page) {
  return page
    .evaluate(() => {
      const internals = globalThis.__TAURI_INTERNALS__;
      if (internals === undefined || typeof internals.invoke !== "function") {
        return { installed: false, reason: "invoke_unavailable" };
      }
      const existing = globalThis.__JA_REASONING_INVOKE_DIAGNOSTICS__;
      if (existing?.wrappedInvoke === internals.invoke) return { installed: true, reused: true };
      const originalInvoke = internals.invoke;
      const diagnostic = {
        wrappedInvoke: undefined,
        turnStartRejections: [],
      };
      const wrappedInvoke = async (command, args) => {
        try {
          return await Reflect.apply(originalInvoke, internals, [command, args]);
        } catch (error) {
          if (command === "ja_turn_start") {
            const value = error !== null && typeof error === "object" ? error : {};
            diagnostic.turnStartRejections.push({
              code:
                typeof value.code === "string" && value.code.length <= 96 ? value.code : undefined,
              message:
                typeof value.message === "string" && value.message.length <= 256
                  ? value.message
                  : undefined,
              retryable: typeof value.retryable === "boolean" ? value.retryable : undefined,
            });
            diagnostic.turnStartRejections = diagnostic.turnStartRejections.slice(-8);
          }
          throw error;
        }
      };
      diagnostic.wrappedInvoke = wrappedInvoke;
      globalThis.__JA_REASONING_INVOKE_DIAGNOSTICS__ = diagnostic;
      try {
        internals.invoke = wrappedInvoke;
      } catch {
        return { installed: false, reason: "invoke_not_writable" };
      }
      return internals.invoke === wrappedInvoke
        ? { installed: true, reused: false }
        : { installed: false, reason: "invoke_assignment_ignored" };
    })
    .catch((error) => ({ installed: false, reason: String(error).slice(0, 256) }));
}

/** 只读 invoke 诊断闭集；原生错误之外的值全部丢弃，避免把 payload 或 Secret 写入证据。 */
async function readTauriInvokeDiagnostics(page) {
  if (page === undefined) return { installed: false, reason: "page_unavailable" };
  return page
    .evaluate(() => {
      const diagnostic = globalThis.__JA_REASONING_INVOKE_DIAGNOSTICS__;
      if (diagnostic === null || typeof diagnostic !== "object") {
        return { installed: false, reason: "diagnostic_unavailable", turnStartRejections: [] };
      }
      const turnStartRejections = Array.isArray(diagnostic.turnStartRejections)
        ? diagnostic.turnStartRejections.slice(-8).map((value) => ({
            code: typeof value?.code === "string" ? value.code : undefined,
            message: typeof value?.message === "string" ? value.message : undefined,
            retryable: typeof value?.retryable === "boolean" ? value.retryable : undefined,
          }))
        : [];
      return {
        installed: typeof diagnostic.wrappedInvoke === "function",
        turnStartRejections,
      };
    })
    .catch((error) => ({ installed: false, reason: String(error).slice(0, 256) }));
}

/** 读取最小 native admission 投影；路径、identity 值和原始异常不进入失败报告。 */
async function readRuntimeAdmissionDiagnostics(page) {
  if (page === undefined) return { status: "page_unavailable" };
  return page
    .evaluate(async () => {
      const internals = globalThis.__TAURI_INTERNALS__;
      if (internals === undefined || typeof internals.invoke !== "function") {
        return { status: "invoke_unavailable" };
      }
      const errorProjection = (error) => {
        const value = error !== null && typeof error === "object" ? error : {};
        return {
          code: typeof value.code === "string" ? value.code.slice(0, 96) : undefined,
          message: typeof value.message === "string" ? value.message.slice(0, 256) : undefined,
          retryable: typeof value.retryable === "boolean" ? value.retryable : undefined,
        };
      };
      const read = async (command) => {
        try {
          return { ok: true, value: await internals.invoke(command, {}) };
        } catch (error) {
          return { ok: false, error: errorProjection(error) };
        }
      };
      const state = await read("ja_runtime_state");
      const recovery = await read("ja_runtime_recovery_state");
      const storage = await read("ja_runtime_storage_info");
      const stateValue =
        state.ok && state.value !== null && typeof state.value === "object" ? state.value : {};
      const recoveryValue =
        recovery.ok && recovery.value !== null && typeof recovery.value === "object"
          ? recovery.value
          : {};
      const storageValue =
        storage.ok && storage.value !== null && typeof storage.value === "object"
          ? storage.value
          : {};
      return {
        state: state.ok
          ? {
              status: typeof stateValue.status === "string" ? stateValue.status : "invalid",
              generation: Number.isSafeInteger(stateValue.generation)
                ? stateValue.generation
                : undefined,
              serverInstanceIdPresent:
                typeof stateValue.serverInstanceId === "string" &&
                stateValue.serverInstanceId.length > 0,
              features: Array.isArray(stateValue.features)
                ? stateValue.features.filter((value) => typeof value === "string").slice(0, 8)
                : [],
            }
          : { error: state.error },
        recovery: recovery.ok
          ? {
              required: recoveryValue.required === true,
              acknowledgeable: recoveryValue.acknowledgeable === true,
              recoveryIdPresent:
                typeof recoveryValue.recoveryId === "string" && recoveryValue.recoveryId.length > 0,
              revision: Number.isSafeInteger(recoveryValue.revision)
                ? recoveryValue.revision
                : undefined,
            }
          : { error: recovery.error },
        storage: storage.ok
          ? {
              nativeImage: storageValue.nativeImage === true,
              dataPathPresent:
                typeof storageValue.dataPath === "string" && storageValue.dataPath.length > 0,
              logPathPresent:
                typeof storageValue.logPath === "string" && storageValue.logPath.length > 0,
              cachePathPresent:
                typeof storageValue.cachePath === "string" && storageValue.cachePath.length > 0,
              lastBackupPresent:
                typeof storageValue.lastBackup === "string" && storageValue.lastBackup.length > 0,
            }
          : { error: storage.error },
      };
    })
    .catch((error) => ({ status: "evaluate_failed", error: String(error).slice(0, 256) }));
}

/** 读取隔离 Java 日志的有限尾部并做路径/token 脱敏，保留准入错误的异常类型而不持久化请求正文。 */
async function readIsolatedRuntimeLogs(directories) {
  const logDirectory = join(directories.home, "logs", "java");
  const files = {};
  for (const name of ["app-server-error.log", "app-server.log"]) {
    try {
      const content = await readFile(join(logDirectory, name), "utf8");
      const lines = content
        .split(/\r?\n/u)
        .filter((line) => line.length > 0)
        .slice(-160)
        .map((line) =>
          line
            .replaceAll(directories.root, "[isolated-root]")
            .replace(/\b(?:https?|wss?):\/\/[^\s"']+/giu, "[url]")
            .replace(/\bBearer\s+[^\s"']+/giu, "Bearer [redacted]")
            .replace(/\b(?:api[_-]?key|token|authorization)\s*[=:]\s*[^\s"']+/giu, "$1=[redacted]")
            .slice(0, 800),
        );
      files[name] = { present: true, lines };
    } catch {
      files[name] = { present: false, lines: [] };
    }
  }
  return { files };
}

/** 提交一条真实 Composer 消息并等待该消息进入 durable DOM，调用方再等待对应 Provider marker。 */
async function sendPrompt(page, prompt, deadline) {
  await installTauriInvokeDiagnostics(page);
  const input = page.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: prompt })
    .last()
    .waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
  const submission = page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: prompt })
    .last();
  const submissionError = submission.getByRole("alert");
  try {
    await submissionError.waitFor({ state: "visible", timeout: 1_000 });
    throw new Error(
      `reasoning prompt submission failed: ${(await submissionError.innerText()).trim()}`,
    );
  } catch (error) {
    if (error instanceof Error && error.message.startsWith("reasoning prompt submission failed:"))
      throw error;
  }
}

/** 等待指定数量的 completed assistant rows，避免相同 final marker 的多轮响应相互误认。 */
async function waitForCompletedResponses(page, count, deadline) {
  while (Date.now() < deadline) {
    if (
      (await page.locator('.ja-chat-message-final[data-response-state="completed"]').count()) >=
      count
    )
      return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`completed assistant response count ${count} timed out`);
}

/** 在同一隔离 profile 上重启真实 Tauri/JVM 树，保留 SQLite/配置并返回新的 WebView2 page。 */
async function restartOwnedRuntime({
  browser,
  launch,
  overlay,
  environment,
  directories,
  frontendPort,
  cdpPort,
}) {
  await browser.close().catch(() => undefined);
  await terminateOwnedLauncher(launch);
  const restarted = launchTauri(overlay, environment);
  const endpoint = await waitForCdp(
    `http://127.0.0.1:${cdpPort}`,
    restarted,
    Date.now() + 120_000,
    directories,
  );
  const nextBrowser = await chromium.connectOverCDP(endpoint);
  const page = await findTauriPage(nextBrowser, frontendPort, Date.now() + 30_000);
  page.setDefaultTimeout(30_000);
  return { browser: nextBrowser, launch: restarted, page };
}

/** 只确认当前已完成 response 的公开 reasoning 可见，私有签名/encrypted/redacted 永不进入 UI。 */
function assertPublicReasoningOnly(
  pageText,
  contract,
  visibleResponseCount = contract.reasoningMarkers.length,
) {
  for (const marker of contract.reasoningMarkers.slice(0, visibleResponseCount))
    assert.match(pageText, new RegExp(marker, "u"));
  for (const marker of Object.values(contract.privateMarkers).flatMap((value) =>
    Array.isArray(value) ? value : [value],
  )) {
    if (marker !== undefined) assert.doesNotMatch(pageText, new RegExp(marker, "u"));
  }
}

/** 校验单轮 live/reload 的 reasoning、Tool 交错闭集与最终答复。 */
function assertReasoningSequence(items, label) {
  const actionableItems = items.filter((item) => item.kind === "reasoning" || item.kind === "tool");
  assert.deepEqual(
    actionableItems.map((item) => item.kind),
    ["reasoning", "tool", "reasoning", "tool", "reasoning"],
    `${label} reasoning/tool sequence mismatch`,
  );
  assert.deepEqual(
    actionableItems.filter((item) => item.kind === "tool").map((item) => item.toolKind),
    ["read", "shell"],
    `${label} Tool sequence mismatch`,
  );
}

/** 校验报告闭集，避免单凭 runner 退出码或截图宣称完整协议验收。 */
export function validateReasoningReport(report, expectedProtocol) {
  assert.equal(report?.schemaVersion, 1);
  assert.equal(report?.status, "passed");
  assert.equal(report?.protocol, expectedProtocol);
  assert.deepEqual(report?.runtime, {
    platform: "win32",
    surface: "tauri_webview2",
    boundary: "jvm_jar",
    nativeImageVerified: false,
  });
  assert.equal(report?.provider?.kind, "deterministic_loopback");
  assert.equal(report?.provider?.externalCalls, 0);
  assert.equal(report?.provider?.nativeContinuationVerified, true);
  assert.deepEqual(report?.live?.sequence, [
    "reasoning",
    "tool:read",
    "reasoning",
    "tool:shell",
    "reasoning",
  ]);
  assert.deepEqual(report?.reload?.sequence, report?.live?.sequence);
  assert.equal(report?.reload?.sameThread, true);
  assert.equal(report?.restart?.sameThread, true);
  assert.equal(report?.restart?.nativeContinuationVerified, true);
  assert.equal(report?.restart?.finalVisible, true);
  assert.equal(report?.publicOnly, true);
  assert.equal(report?.finalVisible, true);
  return report;
}

/** 运行一个协议的真实 Tauri/WebView2 端到端会话，并在 reload 后验证 durable reasoning history。 */
export async function runReasoningProtocol({ protocol, options, fixture }) {
  const directories = await createRunDirectories(protocol);
  let launch;
  let browser;
  let page;
  const pageErrors = [];
  const evidenceDirectory = join(options.evidenceDirectory, protocol);
  await mkdir(evidenceDirectory, { recursive: true });
  try {
    const toolchain = await validateToolchain(options);
    const providerBaseUrl = fixture.baseUrl;
    await writeIsolatedSettings(directories.home, protocol, providerBaseUrl);
    const frontendPort = await reservePort();
    const cdpPort = await reservePort(new Set([frontendPort]));
    const overlay = await writeTauriOverlay(directories, frontendPort);
    const primeEnvironment = buildEnvironment({
      directories,
      java: toolchain.java,
      jar: options.jar,
      frontendPort,
      cdpPort: undefined,
      // 三个协议串行复用同一 runner target，避免每个 profile 触发完整 Rust 重编译；
      // profile、Java runtime、Provider 与端口仍按协议完全隔离。
      cargoTargetDirectory: options.cargoTargetDirectory,
    });
    const primeLaunch = launchTauri(overlay, primeEnvironment);
    try {
      await waitForWebViewProfileReady(directories, primeLaunch, Date.now() + 120_000);
    } finally {
      await terminateOwnedLauncher(primeLaunch);
    }
    await waitForPortAvailable(frontendPort, Date.now() + 30_000);
    const environment = buildEnvironment({
      directories,
      java: toolchain.java,
      jar: options.jar,
      frontendPort,
      cdpPort,
      cargoTargetDirectory: options.cargoTargetDirectory,
    });
    launch = launchTauri(overlay, environment);
    const endpoint = await waitForCdp(
      `http://127.0.0.1:${cdpPort}`,
      launch,
      Date.now() + 120_000,
      directories,
    );
    browser = await chromium.connectOverCDP(endpoint);
    page = await findTauriPage(browser, frontendPort, Date.now() + 30_000);
    page.setDefaultTimeout(30_000);
    page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
    // findTauriPage 已经拿到真实 E2E entry；bootstrap 后无需额外 reload，保留一次真正的
    // durable history restore reload 作为后续验收边界。
    await waitForApplication(page, Date.now() + 120_000);
    await configureProviderCredential(page, providerBaseUrl, protocol);
    await ensureProject(page, Date.now() + 60_000);
    const created = await createReasoningThread(page, directories.workspace, protocol);
    // threadCreate 通过 typed history adapter 写入 Java owner 后，当前 React 侧栏没有独立的
    // history invalidation；一次 reload 只用于刷新新建 Thread 投影，随后仍显式确认 runtime。
    await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
    await restoreRuntimeAfterReload(page, Date.now() + 120_000);
    await ensureProject(page, Date.now() + 60_000);
    await selectThread(page, created.threadId, Date.now() + 60_000);
    const prompt = `请按顺序读取 fixture、执行低影响 Shell，并在每轮保留原生 reasoning。验收协议 ${protocol}。`;
    await sendPrompt(page, prompt, Date.now() + 60_000);
    const contract = {
      reasoningMarkers: REASONING_MARKERS[protocol],
      finalMarkers: FINAL_MARKERS[protocol],
      privateMarkers: PRIVATE_MARKERS[protocol],
    };
    for (let round = 0; round < 3; round += 1) {
      await waitForText(page, contract.reasoningMarkers[round], Date.now() + 120_000);
      const livePageText = await page.locator("body").innerText();
      assertPublicReasoningOnly(livePageText, contract, round + 1);
      fixture.release(protocol, round);
      if (round < 2)
        await waitForFixtureStage(fixture, `tool_${protocol}:${round}`, Date.now() + 30_000);
    }
    await waitForText(page, contract.finalMarkers[2], Date.now() + 120_000);
    await page
      .locator('.ja-chat-message-final[data-response-state="completed"]')
      .waitFor({ state: "visible", timeout: 120_000 });
    await expandProcess(page, Date.now() + 30_000);
    const liveItems = await processItems(page, Date.now() + 30_000);
    assertReasoningSequence(liveItems, "live");
    assertPublicReasoningOnly(await page.locator("body").innerText(), contract, 3);
    await page.screenshot({
      path: join(evidenceDirectory, "reasoning-live.png"),
      animations: "disabled",
    });
    await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
    await restoreRuntimeAfterReload(page, Date.now() + 120_000);
    await ensureProject(page, Date.now() + 60_000);
    await selectThread(page, created.threadId, Date.now() + 60_000);
    await waitForText(page, contract.finalMarkers[2], Date.now() + 120_000);
    await expandProcess(page, Date.now() + 30_000);
    const restoredItems = await processItems(page, Date.now() + 30_000);
    assertReasoningSequence(restoredItems, "reload");
    const restoredPageText = await page.locator("body").innerText();
    assertPublicReasoningOnly(restoredPageText, contract, 3);
    await page.screenshot({
      path: join(evidenceDirectory, "reasoning-reload.png"),
      animations: "disabled",
    });

    // 第二条用户消息要求模型再次消费上一轮原生 reasoning；随后重启同一隔离 profile，
    // 再提交第三条消息，证明 SQLite 历史与私有 continuation 不依赖当前进程内存。
    const followUpPrompt = `继续确认 ${protocol} 的 reasoning 历史已保留，并给出第二次简短结论。`;
    await sendPrompt(page, followUpPrompt, Date.now() + 60_000);
    await waitForFixtureStage(fixture, `reasoning_${protocol}:3`, Date.now() + 120_000);
    await expandAllProcesses(page, Date.now() + 30_000);
    await waitForText(page, contract.reasoningMarkers[3], Date.now() + 120_000);
    fixture.release(protocol, 3);
    await waitForText(page, contract.finalMarkers[3], Date.now() + 120_000);
    await waitForCompletedResponses(page, 2, Date.now() + 120_000);
    await expandAllProcesses(page, Date.now() + 30_000, 2);
    assertPublicReasoningOnly(await page.locator("body").innerText(), contract, 4);

    const restarted = await restartOwnedRuntime({
      browser,
      launch,
      overlay,
      environment,
      directories,
      frontendPort,
      cdpPort,
    });
    browser = restarted.browser;
    launch = restarted.launch;
    page = restarted.page;
    page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
    await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
    await restoreRuntimeAfterReload(page, Date.now() + 120_000);
    await ensureProject(page, Date.now() + 60_000);
    await selectThread(page, created.threadId, Date.now() + 60_000);
    await waitForCompletedResponses(page, 2, Date.now() + 120_000);
    await expandAllProcesses(page, Date.now() + 30_000, 2);
    assertPublicReasoningOnly(await page.locator("body").innerText(), contract, 4);
    const restartPrompt = `重启后继续验证 ${protocol} 的原生 reasoning 回传。`;
    await sendPrompt(page, restartPrompt, Date.now() + 60_000);
    await waitForFixtureStage(fixture, `reasoning_${protocol}:4`, Date.now() + 120_000);
    await expandAllProcesses(page, Date.now() + 30_000);
    await waitForText(page, contract.reasoningMarkers[4], Date.now() + 120_000);
    fixture.release(protocol, 4);
    await waitForText(page, contract.finalMarkers[4], Date.now() + 120_000);
    await waitForCompletedResponses(page, 3, Date.now() + 120_000);
    await expandAllProcesses(page, Date.now() + 30_000, 3);
    const restartPageText = await page.locator("body").innerText();
    assertPublicReasoningOnly(restartPageText, contract, 5);
    await page.screenshot({
      path: join(evidenceDirectory, "reasoning-restart.png"),
      animations: "disabled",
    });
    assert.deepEqual(pageErrors, [], `WebView2 page errors: ${pageErrors.join(" | ")}`);
    const snapshot = fixture.snapshot();
    const protocolAttempts = snapshot.attempts.filter(
      (attempt) => attempt.protocol === protocol && attempt.kind === "turn",
    );
    assert.deepEqual(
      protocolAttempts.map((attempt) => attempt.round),
      [0, 1, 2, 2, 2],
    );
    assert.deepEqual(
      protocolAttempts.map((attempt) => attempt.responseIndex),
      [0, 1, 2, 3, 4],
    );
    assert.equal(
      protocolAttempts.slice(1).every((attempt) => attempt.privateContinuationSeen === true),
      true,
    );
    assert.equal(
      protocolAttempts.slice(1).every((attempt) => attempt.continuationSequenceVerified === true),
      true,
    );
    assert.deepEqual(
      protocolAttempts.slice(1).map((attempt) => attempt.continuationCount),
      [1, 2, 3, 4],
    );
    return {
      schemaVersion: 1,
      status: "passed",
      protocol,
      runtime: {
        platform: process.platform,
        surface: "tauri_webview2",
        boundary: "jvm_jar",
        nativeImageVerified: false,
      },
      provider: {
        kind: "deterministic_loopback",
        externalCalls: 0,
        nativeContinuationVerified: true,
        attempts: protocolAttempts,
      },
      live: {
        sequence: liveItems.map((item) =>
          item.kind === "tool" ? `tool:${item.toolKind}` : item.kind,
        ),
      },
      reload: {
        sameThread: true,
        sequence: restoredItems.map((item) =>
          item.kind === "tool" ? `tool:${item.toolKind}` : item.kind,
        ),
      },
      restart: { sameThread: true, nativeContinuationVerified: true, finalVisible: true },
      publicOnly: true,
      finalVisible: true,
      screenshots: ["reasoning-live.png", "reasoning-reload.png", "reasoning-restart.png"],
      pageErrors,
    };
  } catch (error) {
    await writeFile(
      join(evidenceDirectory, "failure.json"),
      `${JSON.stringify(
        {
          schemaVersion: 1,
          status: "failed",
          protocol,
          error: String(error?.message ?? error),
          launcher: launch === undefined ? undefined : launch.output,
          tauriInvokeDiagnostics: await readTauriInvokeDiagnostics(page),
          runtimeAdmissionDiagnostics: await readRuntimeAdmissionDiagnostics(page),
          isolatedRuntimeLogs: await readIsolatedRuntimeLogs(directories),
          fixture: fixture.snapshot(),
          page: await page
            ?.evaluate(() => ({
              url: globalThis.location.href,
              bodyText: globalThis.document.body?.innerText?.slice(0, 12_000) ?? "",
              appReady: globalThis.document
                .querySelector(".ja-shell")
                ?.getAttribute("data-app-ready"),
              runtimeStatus: globalThis.document
                .querySelector('[aria-label^="本地运行时："]')
                ?.getAttribute("aria-label"),
              projectButtons: globalThis.document
                .querySelector('[aria-label="项目列表"]')
                ?.querySelectorAll("button")?.length,
              projectRows: globalThis.document
                .querySelector('[aria-label="项目列表"]')
                ?.querySelectorAll('button[data-scope-kind="project"]')?.length,
              alerts: [...globalThis.document.querySelectorAll('[role="alert"]')].map(
                (element) => element.textContent?.trim() ?? "",
              ),
            }))
            .catch((pageError) => ({ evaluateError: String(pageError) })),
          pageErrors,
        },
        null,
        2,
      )}\n`,
      "utf8",
    ).catch(() => undefined);
    const failureScreenshot = join(evidenceDirectory, "failure.png");
    try {
      const page = browser?.contexts().flatMap((context) => context.pages())[0];
      await page?.screenshot({ path: failureScreenshot, animations: "disabled" });
    } catch {
      // 失败路径截图是辅助证据，不能覆盖原始错误。
    }
    throw error;
  } finally {
    await browser?.close().catch(() => undefined);
    await terminateOwnedLauncher(launch);
    await rm(assertOwnedTemporaryPath(directories.root, "reasoning run root"), {
      recursive: true,
      force: true,
      maxRetries: 3,
      retryDelay: 200,
    }).catch(() => undefined);
  }
}

/** 聚合三协议报告；任一协议失败即失败关闭，不把其它协议的证据折算为全通过。 */
export function validateReasoningSuite(reports) {
  assert.deepEqual(Object.keys(reports).sort(), [...ALL_PROTOCOLS].sort());
  for (const protocol of ALL_PROTOCOLS) validateReasoningReport(reports[protocol], protocol);
  return reports;
}

/** CLI 入口；先建立 fixture，再顺序启动每套真窗，保证共享 JAR 只读且不并发 Maven/Cargo。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (process.platform !== "win32") throw new Error("reasoning WebView2 runner requires Windows");
  await validateToolchain(options);
  if (options.preflightOnly) {
    process.stdout.write(
      `JA_REASONING_PREFLIGHT_OK ${JSON.stringify({ protocols: options.protocol === undefined ? ALL_PROTOCOLS : [options.protocol] })}\n`,
    );
    return;
  }
  const protocols = options.protocol === undefined ? ALL_PROTOCOLS : [options.protocol];
  const reports = {};
  const fixture = await startReasoningProviderFixture({ protocols });
  try {
    for (const protocol of protocols) {
      reports[protocol] = await runReasoningProtocol({ protocol, options, fixture });
      validateReasoningReport(reports[protocol], protocol);
    }
  } finally {
    await fixture.close();
  }
  const summary = {
    schemaVersion: 1,
    status: "passed",
    protocols,
    reports,
    fixture: fixture.snapshot(),
  };
  await mkdir(options.evidenceDirectory, { recursive: true });
  await writeFile(
    join(options.evidenceDirectory, "reasoning-suite-report.json"),
    `${JSON.stringify(summary, null, 2)}\n`,
    "utf8",
  );
  process.stdout.write(`JA_REASONING_WEBVIEW2_PASS ${JSON.stringify({ protocols })}\n`);
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    process.stderr.write(
      `JA_REASONING_WEBVIEW2_FAIL ${String(error?.message ?? error).slice(0, 2_000)}\n`,
    );
    process.exitCode = 1;
  });
}
