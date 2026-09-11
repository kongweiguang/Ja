// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 侧边任务偏好真窗 runner：创建一次性 Windows profile、启动独立 Tauri/WebView2
 * 进程树，再调用 side-task-preferences-webview2.mjs。该文件只拥有本轮 launcher，
 * cleanup 使用已核验的 launcher PID，不触碰其它 Ja 或用户 WebView2 实例。
 */

import { execFile, spawn } from "node:child_process";
import { createServer } from "node:net";
import { access, mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const sideTaskScript = join(repoRoot, "scripts", "e2e", "side-task-preferences-webview2.mjs");
const edgeDebugArguments =
  "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --remote-debugging-address=127.0.0.1 --remote-debugging-port=";

/** 从非动态区申请本轮唯一 loopback 端口，避免借用其它应用的 listener。 */
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
  throw new Error("无法申请侧边任务真窗 loopback 端口");
}

/** 写入最小隔离配置，让应用先能进入真实 Composer，再由验收脚本通过设置页配置 fixture。 */
async function writeIsolatedHome(homeRoot) {
  const jaHome = join(homeRoot, ".ja");
  await mkdir(jaHome, { recursive: true });
  const config = [
    "schema_version = 1",
    "config_revision = 1",
    'default_access_mode = "full_access"',
    'default_provider_id = "provider_side_task"',
    'default_model_id = "model_side_task"',
    "default_reasoning_level = { __ja_null = true }",
    "subagents = { enabled = true, provider_id = { __ja_null = true }, model_id = { __ja_null = true }, reasoning_level = { __ja_null = true } }",
    "mcp_servers = []",
    "skills = []",
    "[interaction]",
    "clarification_enabled = true",
    "",
    "[[providers]]",
    'provider_id = "provider_side_task"',
    'name = "Side Task Bootstrap"',
    'api = "anthropic_messages"',
    'base_url = "http://127.0.0.1:9"',
    'credential_id = "cred_side_task"',
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
    'model_id = "model_side_task"',
    'name = "Side Task Bootstrap Model"',
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
  await writeFile(join(jaHome, "auth.json"), '{"cred_side_task":"isolated-bootstrap-token"}\n', "utf8");
  const account = `${process.env.USERDOMAIN ?? "."}\\${process.env.USERNAME ?? ""}`;
  if (account.endsWith("\\")) throw new Error("无法确定 Windows 隔离 profile ACL 用户");
  await execFileAsync("icacls.exe", [authPath, "/inheritance:r", "/grant:r", `${account}:(F)`], {
    windowsHide: true,
    timeout: 10_000,
    maxBuffer: 64 * 1024,
  });
}

/** 为 Tauri 继承生产窗口事实，只替换 devUrl、唯一 identifier 与隔离 CSP。 */
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
    identifier: `io.github.kongweiguang.ja.side_task_e2e_${frontendPort}`,
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
  const path = join(runtimeRoot, "tauri.side-task.conf.json");
  await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return path;
}

/** 等待本轮固定 CDP listener 和目标 WebView 页面出现，不接受任意其它端口。 */
async function waitForCdp(port, deadline) {
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`, { signal: AbortSignal.timeout(1000) });
      if (response.ok) return;
    } catch {
      // Tauri/WebView2 的 browser listener 与 renderer 页面分阶段启动。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  throw new Error(`隔离 WebView2 CDP ${port} 未在期限内启动`);
}

/**
 * 预热同一隔离 UDF，满足 Windows WebView2 首次 profile 初始化不会开放 CDP 的运行时约束。
 * 预热只等待 profile 文件落盘并随后回收自有进程；正式启动复用该 UDF，才能获得可验收的 CDP。
 */
async function primeWebViewProfile(directories, frontendPort, cdpPort, configPath, java, jar) {
  const launch = startTauri(directories, frontendPort, cdpPort, configPath, java, jar);
  try {
    const deadline = Date.now() + 120_000;
    const preferences = join(directories.webview, "EBWebView", "Default", "Preferences");
    let ready = false;
    while (Date.now() < deadline) {
      if (launch.child.exitCode !== null || launch.child.signalCode !== null) {
        throw new Error(`WebView2 profile 预热时 Tauri 退出：${launch.output.slice(-4).join("\\n")}`);
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
  } finally {
    await stopTauri(launch);
  }
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 2_000));
}

/** 启动唯一 launcher root；子进程环境只包含本轮 profile、Java 25 与专属 Cargo target。 */
function startTauri(directories, frontendPort, cdpPort, configPath, java, jar) {
  const inheritedPath = process.env.PATH ?? process.env.Path ?? "";
  const hostUserProfile = process.env.USERPROFILE ?? process.env.HOME ?? "";
  const targetDirectory = join(repoRoot, "src-tauri", "target", "side-task-preferences");
  const target = join(targetDirectory, "debug", "ja.exe");
  const env = {
    ...process.env,
    APPDATA: directories.appdata,
    LOCALAPPDATA: directories.localappdata,
    USERPROFILE: directories.settings,
    JA_E2E_RUNTIME_ROOT: directories.runtime,
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
  child.stdout?.on("data", (chunk) => output.push(String(chunk).slice(-2000)));
  child.stderr?.on("data", (chunk) => output.push(String(chunk).slice(-2000)));
  return { child, output, target, webviewProfile: directories.webview };
}

/** 只终止本轮 launcher root 的进程树；PID 和命令行不完整时不向外扩大 cleanup。 */
async function stopTauri(launch) {
  const pid = launch?.child?.pid;
  if (!Number.isSafeInteger(pid) || pid < 1) return;
  const wasRunning = launch.child.exitCode === null && launch.child.signalCode === null;
  // 先让 taskkill 在 launcher 仍存活时递归回收 Vite/Tauri 子树，避免先 kill 根进程后子树脱离。
  if (wasRunning) {
    await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
      windowsHide: true,
      timeout: 15_000,
      maxBuffer: 64 * 1024,
    }).catch(() => undefined);
    if (launch.child.exitCode === null && launch.child.signalCode === null) launch.child.kill();
  }
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 1_000));
  await stopOwnedTauriBinary(launch.target);
  await stopOwnedWebView2(launch.webviewProfile);
}

/** launcher 退出后 Tauri 子进程可能脱离 cmd；只按本轮专属 target 的绝对路径回收。 */
async function stopOwnedTauriBinary(target) {
  if (typeof target !== "string" || target.length === 0) return;
  const escaped = target.replace(/'/gu, "''");
  const command = `$target='${escaped}'; Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -eq $target } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }`;
  await execFileAsync("pwsh.exe", ["-NoProfile", "-NonInteractive", "-Command", command], {
    windowsHide: true,
    timeout: 15_000,
    maxBuffer: 64 * 1024,
  }).catch(() => undefined);
}

/** WebView2 子进程不一定挂在 Tauri PID 下，只按本轮独立 profile 回收。 */
async function stopOwnedWebView2(profile) {
  if (typeof profile !== "string" || profile.length === 0) return;
  const escaped = profile.replace(/'/gu, "''");
  const command = `$profile='${escaped}'; Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'msedgewebview2.exe' -and $_.CommandLine -like ('*' + $profile + '*') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }`;
  await execFileAsync("pwsh.exe", ["-NoProfile", "-NonInteractive", "-Command", command], {
    windowsHide: true,
    timeout: 15_000,
    maxBuffer: 64 * 1024,
  }).catch(() => undefined);
}

/** 检查 JDK 25 与已有 app-server jar，避免 runner 误用默认 JDK 或进入 Maven 构建竞争。 */
async function resolveJavaAndJar() {
  const javaHome = process.env.JA_E2E_JAVA_HOME?.trim() || "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
  const java = join(javaHome, "bin", "java.exe");
  const jar = resolve(process.env.JA_E2E_APP_SERVER_JAR?.trim() || join(repoRoot, "app-server", "target", "ja-app-server.jar"));
  const version = await execFileAsync(java, ["-version"], { windowsHide: true, timeout: 10_000, maxBuffer: 64 * 1024 });
  if (!`${version.stderr ?? ""}${version.stdout ?? ""}`.includes('version "25')) throw new Error("侧边任务 runner 必须使用 JDK 25");
  return { java, jar };
}

/** 创建隔离目录、启动真窗、运行 driver，并在成功或失败后回收自有进程树。 */
async function main() {
  if (process.platform !== "win32") throw new Error("侧边任务 runner 仅支持 Windows 11");
  const { java, jar } = await resolveJavaAndJar();
  const root = await mkdtemp(join(tmpdir(), "ja-side-task-preferences-"));
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
  let launch;
  let exitCode = 1;
  try {
    await primeWebViewProfile(directories, frontendPort, cdpPort, configPath, java, jar);
    launch = startTauri(directories, frontendPort, cdpPort, configPath, java, jar);
    await waitForCdp(cdpPort, Date.now() + 120_000);
    const driver = spawn(process.execPath, [sideTaskScript], {
      cwd: repoRoot,
      env: {
        ...process.env,
        JA_E2E_SIDE_TASK_ISOLATED: "1",
        JA_E2E_SIDE_TASK_CDP_ENDPOINT: `http://127.0.0.1:${cdpPort}`,
        JA_E2E_SIDE_TASK_ARTIFACT_DIR: directories.artifacts,
      },
      stdio: "inherit",
      windowsHide: true,
    });
    exitCode = await new Promise((resolvePromise) => driver.once("exit", (code) => resolvePromise(code ?? 1)));
  } catch (error) {
    process.stderr.write(`JA_SIDE_TASK_RUNNER_FAILED ${error instanceof Error ? error.message : String(error)}\n`);
    if (launch?.output?.length) process.stderr.write(`${launch.output.slice(-4).join("\n")}\n`);
  } finally {
    await stopTauri(launch);
  }
  process.stdout.write(`JA_SIDE_TASK_RUNNER_EXIT code=${exitCode} artifacts=${directories.artifacts} cdp=${cdpPort}\n`);
  if (exitCode !== 0) process.exitCode = exitCode;
}

await main();
