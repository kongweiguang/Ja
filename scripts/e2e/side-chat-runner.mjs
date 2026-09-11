// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 侧聊 Windows/WebView2 两阶段真窗 runner。
 *
 * 本 runner 为一次验收创建独立 profile、workspace、Vite 端口、WebView2 CDP
 * 端口和 Cargo target；initial driver 完成侧聊创建/运行/关闭并留下最小状态，
 * 随后在同一 profile 上重启 Tauri，由 restart driver 验证主会话和文件可恢复、
 * 已关闭侧聊不可恢复。进程清理只按本轮 launcher、专属二进制路径和专属
 * WebView2 profile 进行，不触碰其它 Ja 或用户进程。
 */

import { execFile, spawn } from "node:child_process";
import { createServer } from "node:net";
import { access, mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const sideChatScript = join(repoRoot, "scripts", "e2e", "side-chat-webview2.mjs");
const defaultTargetDirectory = join(repoRoot, "src-tauri", "target", "side-chat");
const edgeDebugArguments =
  "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --remote-debugging-address=127.0.0.1 --remote-debugging-port=";

/** 从非动态端口区申请一个本轮专用 loopback 端口，避免接管其它应用 listener。 */
async function reservePort(excluded = new Set()) {
  for (let port = 41_000; port < 49_000; port += 1) {
    if (excluded.has(port)) continue;
    const server = createServer();
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
  throw new Error("无法申请侧聊真窗 loopback 端口");
}

/** 写入最小隔离配置，使应用可以进入真实 Composer，再由 driver 配置 loopback Provider。 */
async function writeIsolatedHome(homeRoot) {
  const jaHome = join(homeRoot, ".ja");
  await mkdir(jaHome, { recursive: true });
  const config = [
    "schema_version = 1",
    "config_revision = 1",
    'default_access_mode = "full_access"',
    'default_provider_id = "provider_side_chat"',
    'default_model_id = "model_side_chat"',
    "default_reasoning_level = { __ja_null = true }",
    "subagents = { enabled = true, provider_id = { __ja_null = true }, model_id = { __ja_null = true }, reasoning_level = { __ja_null = true } }",
    "mcp_servers = []",
    "skills = []",
    "[interaction]",
    "clarification_enabled = true",
    "",
    "[[providers]]",
    'provider_id = "provider_side_chat"',
    'name = "Side Chat Bootstrap"',
    'api = "anthropic_messages"',
    'base_url = "http://127.0.0.1:9"',
    'credential_id = "cred_side_chat"',
    "[providers.network_timeouts]",
    "connect_timeout_ms = 1000",
    "request_timeout_ms = 30000",
    "[providers.agent_defaults]",
    "[providers.agent_defaults.context]",
    "auto_compact = true",
    "[providers.agent_defaults.turn_limits]",
    "max_model_rounds = 8",
    "max_tool_calls = 16",
    "wall_timeout_ms = 30000",
    "[[providers.models]]",
    'model_id = "model_side_chat"',
    'name = "Side Chat Bootstrap Model"',
    'model = "ja-side-bootstrap"',
    "reasoning_level_map = {}",
    "default_reasoning_level = { __ja_null = true }",
    "[providers.models.capabilities]",
    "context_window_tokens = 128000",
    "max_output_tokens = 8192",
    "",
  ].join("\n");
  await writeFile(join(jaHome, "config.toml"), config, "utf8");
  const authPath = join(jaHome, "auth.json");
  await writeFile(
    join(jaHome, "auth.json"),
    '{"cred_side_chat":"isolated-bootstrap-token"}\n',
    "utf8",
  );
  const account = `${process.env.USERDOMAIN ?? "."}\\${process.env.USERNAME ?? ""}`;
  if (account.endsWith("\\")) throw new Error("无法确定 Windows 隔离 profile ACL 用户");
  await execFileAsync("icacls.exe", [authPath, "/inheritance:r", "/grant:r", `${account}:(F)`], {
    windowsHide: true,
    timeout: 10_000,
    maxBuffer: 64 * 1024,
  });
}

/** 为 Tauri 继承生产窗口事实，只替换 devUrl、隔离 identifier 与本轮 CSP。 */
async function writeTauriOverlay(runtimeRoot, frontendPort) {
  const [base, windows] = await Promise.all([
    readFile(join(repoRoot, "src-tauri", "tauri.conf.json"), "utf8").then(JSON.parse),
    readFile(join(repoRoot, "src-tauri", "tauri.windows.conf.json"), "utf8").then(JSON.parse),
  ]);
  const window = {
    ...base.app.windows.find((candidate) => candidate.label === "main"),
    ...(windows.app?.windows?.find((candidate) => candidate.label === "main") ?? {}),
  };
  const origin = `http://127.0.0.1:${frontendPort}`;
  const config = {
    ...base,
    identifier: `io.github.kongweiguang.ja.side_chat_e2e_${frontendPort}`,
    build: { ...base.build, devUrl: origin, beforeDevCommand: "pnpm dev" },
    app: {
      ...base.app,
      windows: [window],
      security: {
        ...(base.app?.security ?? {}),
        csp: `default-src 'self'; connect-src 'self' ipc: http://ipc.localhost ${origin} ws://127.0.0.1:${frontendPort}; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`,
      },
    },
  };
  const path = join(runtimeRoot, "tauri.side-chat.conf.json");
  await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return path;
}

/** 在固定 CDP 端口等待本轮 Tauri/WebView2 listener，launcher 提前退出时立即失败。 */
async function waitForCdp(port, launch, deadline) {
  while (Date.now() < deadline) {
    if (launch?.child?.exitCode !== null || launch?.child?.signalCode !== null) {
      throw new Error(`隔离 Tauri 在 CDP 就绪前退出：${launch.output.slice(-4).join("\n")}`);
    }
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`, {
        signal: AbortSignal.timeout(1_000),
      });
      if (response.ok) return;
    } catch {
      // Tauri、renderer 和 WebView2 browser listener 分阶段启动，期限内继续等待。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error(`隔离 WebView2 CDP ${port} 未在期限内启动`);
}

/**
 * 查询 Windows 进程的窄身份投影；CreationDate 用稳定 ISO 字符串保存，防止
 * cleanup 时 PID 被系统复用后被误当成本轮 Tauri/Java 后代。命令不返回命令行或路径。
 */
async function readProcessInventory() {
  const command =
    "@(Get-CimInstance Win32_Process | Select-Object @{Name='pid';Expression={$_.ProcessId}}, @{Name='parentPid';Expression={$_.ParentProcessId}}, @{Name='creationDate';Expression={if ($_.CreationDate) {$_.CreationDate.ToUniversalTime().ToString('o')} else {''}}}, Name) | ConvertTo-Json -Compress";
  const result = await execFileAsync(
    "pwsh.exe",
    ["-NoProfile", "-NonInteractive", "-Command", command],
    {
      windowsHide: true,
      timeout: 15_000,
      maxBuffer: 512 * 1024,
    },
  );
  const parsed = JSON.parse(String(result.stdout ?? "").trim() || "[]");
  const values = Array.isArray(parsed) ? parsed : [parsed];
  return values
    .map((value) => ({
      pid: Number(value?.pid),
      parentPid: Number(value?.parentPid),
      creationDate: typeof value?.creationDate === "string" ? value.creationDate : "",
      name: typeof value?.Name === "string" ? value.Name : "",
    }))
    .filter(
      (value) =>
        Number.isSafeInteger(value.pid) &&
        value.pid > 0 &&
        Number.isSafeInteger(value.parentPid) &&
        value.parentPid >= 0,
    );
}

/** 读取本轮唯一 WebView2 profile 的窄身份；profile 是额外 owner 边界，不是 executable path。 */
async function readWebView2ProfileInventory(profile) {
  const escaped = profile.replace(/'/gu, "''");
  const command = `$profile='${escaped}'; @(Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'msedgewebview2.exe' -and $_.CommandLine -like ('*' + $profile + '*') } | Select-Object @{Name='pid';Expression={$_.ProcessId}}, @{Name='creationDate';Expression={if ($_.CreationDate) {$_.CreationDate.ToUniversalTime().ToString('o')} else {''}}}) | ConvertTo-Json -Compress`;
  const result = await execFileAsync(
    "pwsh.exe",
    ["-NoProfile", "-NonInteractive", "-Command", command],
    {
      windowsHide: true,
      timeout: 15_000,
      maxBuffer: 128 * 1024,
    },
  );
  const parsed = JSON.parse(String(result.stdout ?? "").trim() || "[]");
  const values = Array.isArray(parsed) ? parsed : [parsed];
  return values
    .map((value) => ({ pid: Number(value?.pid), creationDate: value?.creationDate }))
    .filter(
      (value) =>
        Number.isSafeInteger(value.pid) &&
        value.pid > 0 &&
        typeof value.creationDate === "string" &&
        value.creationDate.length > 0,
    );
}

/** 从一次完整进程快照收集 launcher 自身及全部可见后代，避免依赖 executable path。 */
function processTree(inventory, rootPid) {
  const children = new Map();
  for (const processInfo of inventory) {
    const siblings = children.get(processInfo.parentPid) ?? [];
    siblings.push(processInfo);
    children.set(processInfo.parentPid, siblings);
  }
  const byPid = new Map(inventory.map((processInfo) => [processInfo.pid, processInfo]));
  const result = [];
  const pending = [rootPid];
  const visited = new Set();
  while (pending.length > 0) {
    const pid = pending.shift();
    if (visited.has(pid)) continue;
    visited.add(pid);
    const processInfo = byPid.get(pid);
    if (processInfo !== undefined) result.push(processInfo);
    for (const child of children.get(pid) ?? []) pending.push(child.pid);
  }
  return result;
}

/** 记录真实 PID/CreationDate 身份；没有根身份时重试，不使用任何路径 fallback。 */
async function rememberOwnedProcessTree(launch, deadline = Date.now() + 15_000) {
  const rootPid = launch?.child?.pid;
  if (!Number.isSafeInteger(rootPid) || rootPid < 1) return false;
  while (Date.now() < deadline) {
    try {
      const inventory = await readProcessInventory();
      const tree = processTree(inventory, rootPid);
      const root = tree.find((processInfo) => processInfo.pid === rootPid);
      if (root?.creationDate) {
        launch.rootIdentity = { pid: root.pid, creationDate: root.creationDate };
        for (const processInfo of tree) {
          if (!processInfo.creationDate) continue;
          launch.ownedProcesses.set(processInfo.pid, {
            pid: processInfo.pid,
            creationDate: processInfo.creationDate,
          });
        }
        for (const processInfo of await readWebView2ProfileInventory(launch.webviewProfile).catch(
          () => [],
        )) {
          launch.ownedProcesses.set(processInfo.pid, processInfo);
        }
        return true;
      }
    } catch {
      // WMI 启动阶段可能暂时不可读；只在有界期限内重试。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  launch.cleanupWarnings.push(`launcher PID ${rootPid} 没有可靠 CreationDate 身份`);
  return false;
}

/** 预热本轮 WebView2 UDF，确保正式阶段可复用已初始化的 CDP profile。 */
async function primeWebViewProfile(
  directories,
  frontendPort,
  cdpPort,
  configPath,
  java,
  jar,
  targetDirectory,
) {
  const launch = startTauri(
    directories,
    frontendPort,
    cdpPort,
    configPath,
    java,
    jar,
    targetDirectory,
  );
  let cleanup;
  let failure;
  try {
    await rememberOwnedProcessTree(launch);
    const deadline = Date.now() + 120_000;
    const preferences = join(directories.webview, "EBWebView", "Default", "Preferences");
    let ready = false;
    while (Date.now() < deadline) {
      if (launch.child.exitCode !== null || launch.child.signalCode !== null) {
        throw new Error(
          `WebView2 profile 预热时 Tauri 退出：${launch.output.slice(-4).join("\n")}`,
        );
      }
      try {
        await access(preferences);
        ready = true;
        break;
      } catch {
        // WebView2 profile 在首轮启动中分阶段创建，期限内继续等待。
      }
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
    }
    if (!ready) throw new Error("隔离 WebView2 profile 未在期限内完成预热");
  } catch (error) {
    failure = error;
  } finally {
    cleanup = await stopTauri(launch);
  }
  if (cleanup.warnings.length > 0)
    throw new Error(`WebView2 profile 预热 cleanup 未闭合：${cleanup.warnings.join("；")}`);
  if (failure !== undefined) throw failure;
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 2_000));
}

/** 启动本轮唯一 launcher root；环境绑定隔离 profile、Java 25 与专属 Cargo target。 */
function startTauri(directories, frontendPort, cdpPort, configPath, java, jar, targetDirectory) {
  const inheritedPath = process.env.PATH ?? process.env.Path ?? "";
  const hostUserProfile = process.env.USERPROFILE ?? process.env.HOME ?? "";
  const env = {
    ...process.env,
    APPDATA: directories.appdata,
    LOCALAPPDATA: directories.localappdata,
    USERPROFILE: directories.settings,
    JA_E2E_RUNTIME_ROOT: directories.runtime,
    JA_E2E_EXIT_TRACE_PATH: join(directories.runtime, "ja-exit-trace.log"),
    JA_E2E_DEV_PORT: String(frontendPort),
    VITE_JA_E2E_PROJECT_PATH: directories.workspace,
    CARGO_TARGET_DIR: targetDirectory,
    CARGO_HOME: process.env.CARGO_HOME ?? join(hostUserProfile, ".cargo"),
    RUSTUP_HOME: process.env.RUSTUP_HOME ?? join(hostUserProfile, ".rustup"),
    JAVA_HOME: dirname(dirname(java)),
    JA_JAVA25_HOME: dirname(dirname(java)),
    JA_E2E_JAVA_HOME: dirname(dirname(java)),
    JA_TEST_JAVA: java,
    JA_DEBUG_JAVA: java,
    JA_DEBUG_JAR: jar,
    WEBVIEW2_USER_DATA_FOLDER: directories.webview,
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `${edgeDebugArguments}${cdpPort}`,
    PATH: [dirname(java), inheritedPath].filter(Boolean).join(";"),
  };
  const command = process.env.ComSpec ?? process.env.COMSPEC ?? "cmd.exe";
  // cmd.exe 的 /s /c 在内部命令和路径都带引号时需要最外层引号，避免首个路径被剥离。
  const line = `""pnpm.cmd" tauri dev --no-watch --config "${configPath}""`;
  const child = spawn(command, ["/d", "/s", "/c", line], {
    cwd: repoRoot,
    env,
    windowsHide: true,
    windowsVerbatimArguments: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  const output = [];
  child.stdout?.on("data", (chunk) => output.push(String(chunk).slice(-2_000)));
  child.stderr?.on("data", (chunk) => output.push(String(chunk).slice(-2_000)));
  return {
    child,
    output,
    webviewProfile: directories.webview,
    rootIdentity: undefined,
    ownedProcesses: new Map(),
    cleanupWarnings: [],
  };
}

/**
 * 按本轮 launcher 的 PID/CreationDate 身份收口进程；正常退出验收只观察残留，
 * 只有阶段失败时才强制终止已核实的本轮进程，避免 cleanup 掩盖原始退出证据。
 */
async function stopTauri(launch, { force = true } = {}) {
  const pid = launch?.child?.pid;
  if (!Number.isSafeInteger(pid) || pid < 1) {
    const warnings = [...(launch?.cleanupWarnings ?? []), "launcher PID 不可用"];
    return { warnings, residual: [] };
  }
  const wasRunning = launch.child.exitCode === null && launch.child.signalCode === null;
  if (wasRunning && force) await rememberOwnedProcessTree(launch, Date.now() + 3_000);
  for (const processInfo of await readWebView2ProfileInventory(launch.webviewProfile).catch(
    () => [],
  )) {
    launch.ownedProcesses.set(processInfo.pid, processInfo);
  }
  const inventory = await readProcessInventory().catch(() => []);
  const rootMatches =
    launch.rootIdentity !== undefined &&
    inventory.some(
      (processInfo) =>
        processInfo.pid === launch.rootIdentity.pid &&
        processInfo.creationDate === launch.rootIdentity.creationDate,
    );
  // 先递归回收经过 PID+CreationDate 核验的 launcher 子树，避免先杀 root 后子树脱离。
  if (force && wasRunning && rootMatches) {
    await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
      windowsHide: true,
      timeout: 15_000,
      maxBuffer: 64 * 1024,
    }).catch(() => undefined);
    if (launch.child.exitCode === null && launch.child.signalCode === null) launch.child.kill();
  } else if (force && wasRunning) {
    launch.cleanupWarnings.push(`launcher PID ${pid} 未通过 CreationDate 核验，未强杀`);
  }
  if (force) await new Promise((resolvePromise) => setTimeout(resolvePromise, 1_000));
  const remaining = await readProcessInventory().catch(() => []);
  const verifiedRemaining = remaining.filter((processInfo) => {
    const owned = launch.ownedProcesses.get(processInfo.pid);
    return owned !== undefined && owned.creationDate === processInfo.creationDate;
  });
  if (force) {
    for (const processInfo of verifiedRemaining) {
      if (processInfo.pid === pid && rootMatches) continue;
      await execFileAsync("taskkill.exe", ["/PID", String(processInfo.pid), "/F"], {
        windowsHide: true,
        timeout: 15_000,
        maxBuffer: 64 * 1024,
      }).catch(() => undefined);
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 500));
  }
  // taskkill 返回时 Windows 可能仍在回收已终止进程；用有界复核避免把退出尾声误报为泄漏。
  const cleanupDeadline = Date.now() + 8_000;
  let residual;
  do {
    const finalInventory = await readProcessInventory().catch((error) => {
      launch.cleanupWarnings.push(`无法核验退出进程：${String(error)}`);
      return [];
    });
    residual = finalInventory.filter((processInfo) => {
      const owned = launch.ownedProcesses.get(processInfo.pid);
      return owned !== undefined && owned.creationDate === processInfo.creationDate;
    });
    if (residual.length === 0 || Date.now() >= cleanupDeadline) break;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  } while (residual.length > 0 && Date.now() < cleanupDeadline);
  if (residual.length > 0) {
    launch.cleanupWarnings.push(
      force
        ? `本轮仍有 ${residual.length} 个经身份核验的进程残留`
        : `正常退出后仍有 ${residual.length} 个经身份核验的进程残留`,
    );
  }
  return { warnings: [...launch.cleanupWarnings], residual };
}

/** 校验 JDK 25 与可复用 App Server JAR；不让 runner 静默使用默认 JDK 或旧路径。 */
async function resolveJavaAndJar() {
  const javaHome =
    process.env.JA_E2E_SIDE_CHAT_JAVA_HOME?.trim() ||
    process.env.JA_E2E_JAVA_HOME?.trim() ||
    "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
  const java = join(javaHome, "bin", "java.exe");
  const jar = resolve(
    process.env.JA_E2E_SIDE_CHAT_APP_SERVER_JAR?.trim() ||
      process.env.JA_E2E_APP_SERVER_JAR?.trim() ||
      process.env.JA_DEBUG_JAR?.trim() ||
      join(repoRoot, "app-server", "target", "ja-app-server.jar"),
  );
  await access(java);
  await access(jar);
  const version = await execFileAsync(java, ["-version"], {
    windowsHide: true,
    timeout: 10_000,
    maxBuffer: 64 * 1024,
  });
  if (!`${version.stderr ?? ""}${version.stdout ?? ""}`.includes('version "25')) {
    throw new Error("侧聊 runner 必须使用 JDK 25");
  }
  return { java, jar };
}

/** 启动 driver 并传递固定 all-scope 会话发现合同；返回 driver 的真实退出码。 */
async function runDriver(directories, cdpPort, phase) {
  const retainedFile = join(directories.workspace, "side-chat-retained.txt");
  const child = spawn(process.execPath, [sideChatScript], {
    cwd: repoRoot,
    env: {
      ...process.env,
      JA_E2E_SIDE_CHAT_ISOLATED: "1",
      JA_E2E_SIDE_CHAT_CDP_ENDPOINT: `http://127.0.0.1:${cdpPort}`,
      JA_E2E_SIDE_CHAT_ARTIFACT_DIR: directories.artifacts,
      JA_E2E_SIDE_CHAT_RETAINED_FILE: retainedFile,
      JA_E2E_SIDE_CHAT_PHASE: phase,
      JA_E2E_SIDE_CHAT_NORMAL_EXIT: phase === "restart" ? "1" : "0",
      // 会话发现是独立的跨 Workspace API；runner 不允许 workspace scope 降级通过。
      JA_E2E_SIDE_CHAT_THREAD_LIST_SCOPE: "all",
      JA_E2E_SIDE_CHAT_THREAD_LIST_COMMAND:
        process.env.JA_E2E_SIDE_CHAT_THREAD_LIST_COMMAND?.trim() || "ja_thread_discover",
    },
    stdio: "inherit",
    windowsHide: true,
  });
  return new Promise((resolvePromise, rejectPromise) => {
    child.once("error", rejectPromise);
    child.once("exit", (code, signal) => resolvePromise(code ?? (signal === null ? 1 : 1)));
  });
}

/** 正常退出阶段必须等待 launcher 自行结束，避免后续 cleanup 的强杀掩盖应用退出结果。 */
async function waitForLauncherExit(launch, deadline = Date.now() + 30_000) {
  while (Date.now() < deadline) {
    if (launch.child.exitCode !== null || launch.child.signalCode !== null) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error("正常退出请求后 Tauri launcher 未自行结束");
}

/** 校验 initial driver 已留下重启所需身份事实，防止空证据进入 restart 阶段。 */
async function requireInitialEvidence(artifactDirectory) {
  const statePath = join(artifactDirectory, "side-chat-state.json");
  const state = JSON.parse(await readFile(statePath, "utf8"));
  for (const key of [
    "rootThreadId",
    "sideThreadId",
    "liveSideThreadId",
    "btwSideThreadId",
    "btwIdleSideThreadId",
    "btwRequestTargetThreadId",
  ]) {
    if (typeof state[key] !== "string" || state[key].length === 0) {
      throw new Error(`initial 证据缺少 ${key}`);
    }
  }
  if (state.btwRequestTargetThreadId !== state.btwSideThreadId) {
    throw new Error("initial 证据中的 /btw 请求目标不是带正文侧聊");
  }
}

/** 正常窗口关闭必须留下完整原生退出轨迹，不能用 runner 强杀的进程结果替代。 */
async function requireNormalExitEvidence(runtimeRoot) {
  const tracePath = join(runtimeRoot, "ja-exit-trace.log");
  const trace = await readFile(tracePath, "utf8");
  const expected = [
    "stage=exit_requested_enter",
    "stage=exit_requested_return",
    "stage=exit_enter",
    "stage=exit_return",
  ];
  let offset = -1;
  for (const line of expected) {
    const next = trace.indexOf(line, offset + 1);
    if (next < 0) throw new Error(`正常退出轨迹缺少 ${line}`);
    offset = next;
  }
}

/** 运行单个真窗阶段并保证阶段结束后只清理本轮 Tauri/WebView2 进程树。 */
async function runPhase(
  directories,
  frontendPort,
  cdpPort,
  configPath,
  java,
  jar,
  targetDirectory,
  phase,
) {
  const launch = startTauri(
    directories,
    frontendPort,
    cdpPort,
    configPath,
    java,
    jar,
    targetDirectory,
  );
  let cleanup;
  let failure;
  let normalExitCompleted = false;
  try {
    await rememberOwnedProcessTree(launch);
    await waitForCdp(cdpPort, launch, Date.now() + 120_000);
    await rememberOwnedProcessTree(launch, Date.now() + 5_000);
    const exitCode = await runDriver(directories, cdpPort, phase);
    if (exitCode !== 0) throw new Error(`${phase} 侧聊 driver 失败 code=${exitCode}`);
    if (phase === "restart") {
      await waitForLauncherExit(launch);
      normalExitCompleted = true;
    }
  } catch (error) {
    failure = error;
  } finally {
    cleanup = await stopTauri(launch, { force: phase !== "restart" || !normalExitCompleted });
  }
  if (cleanup.warnings.length > 0)
    throw new Error(`${phase} cleanup 未闭合：${cleanup.warnings.join("；")}`);
  if (failure !== undefined) throw failure;
}

/** 创建隔离目录、预热 profile、串行执行 initial/restart，并收口本轮真实进程。 */
async function main() {
  if (process.platform !== "win32") throw new Error("侧聊 runner 仅支持 Windows 11");
  const { java, jar } = await resolveJavaAndJar();
  const configuredTarget =
    process.env.JA_E2E_SIDE_CHAT_TARGET_DIR?.trim() ||
    process.env.JA_E2E_SIDE_CHAT_CARGO_TARGET_DIR?.trim();
  const targetDirectory = resolve(configuredTarget || defaultTargetDirectory);
  const root = await mkdtemp(join(tmpdir(), "ja-side-chat-"));
  const directories = {
    root,
    settings: join(root, "profile"),
    appdata: join(root, "appdata"),
    localappdata: join(root, "localappdata"),
    runtime: join(root, "runtime"),
    workspace: join(root, "workspace"),
    webview: join(root, "webview"),
    artifacts: join(root, "artifacts"),
  };
  await Promise.all(Object.values(directories).map((path) => mkdir(path, { recursive: true })));
  await writeIsolatedHome(directories.settings);
  const frontendPort = await reservePort();
  const cdpPort = await reservePort(new Set([frontendPort]));
  const configPath = await writeTauriOverlay(directories.runtime, frontendPort);
  let exitCode = 1;
  try {
    await primeWebViewProfile(
      directories,
      frontendPort,
      cdpPort,
      configPath,
      java,
      jar,
      targetDirectory,
    );
    await runPhase(
      directories,
      frontendPort,
      cdpPort,
      configPath,
      java,
      jar,
      targetDirectory,
      "initial",
    );
    await requireInitialEvidence(directories.artifacts);
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 2_000));
    await runPhase(
      directories,
      frontendPort,
      cdpPort,
      configPath,
      java,
      jar,
      targetDirectory,
      "restart",
    );
    await requireNormalExitEvidence(directories.runtime);
    // 退出后只读精确隔离数据库，验证退出前仍存活的侧聊确实由正常生命周期清除。
    const restart = JSON.parse(
      await readFile(join(directories.artifacts, "side-chat-restart.json"), "utf8"),
    );
    const database = new DatabaseSync(join(directories.settings, ".ja", "data", "ja.db"), {
      readOnly: true,
    });
    try {
      if (!restart.normalExitSideThreadId) throw new Error("正常退出缺少存活侧聊身份");
      const remaining = database
        .prepare("SELECT COUNT(*) AS count FROM threads WHERE thread_id=?")
        .get(restart.normalExitSideThreadId);
      const markers = database.prepare("SELECT COUNT(*) AS count FROM temporary_side_chats").get();
      if (remaining.count !== 0 || markers.count !== 0)
        throw new Error("正常退出未清除存活临时侧聊");
    } finally {
      database.close();
    }
    exitCode = 0;
  } catch (error) {
    process.stderr.write(
      `JA_SIDE_CHAT_RUNNER_FAILED ${error instanceof Error ? error.message : String(error)}\n`,
    );
  }
  process.stdout.write(
    `JA_SIDE_CHAT_RUNNER_EXIT code=${exitCode} artifacts=${directories.artifacts} target=${targetDirectory} cdp=${cdpPort}\n`,
  );
  if (exitCode !== 0) process.exitCode = exitCode;
}

await main();
