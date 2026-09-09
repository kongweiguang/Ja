// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Review redesign 的独立 Windows Tauri/WebView2 runner。默认启动隔离 JDK 25 debug JAR
 * 链路；它证明真实 Rust Git command 与 UI，明确不等同于 Native Image 产物验收。
 */

import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, mkdtemp, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import process from "node:process";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { chromium } from "@playwright/test";
import {
  runReviewRedesignWebView2,
  validateReviewRedesignReport,
} from "./review-redesign-webview2-driver.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const DEFAULT_IGNORED_FILES = 4_500;
const DEFAULT_UNTRACKED_FILES = 1_800;
const STABLE_PORT_RANGE = Object.freeze({ start: 41_000, size: 8_000 });

/** 只接受命名参数，scope 必须显式声明，避免 Git-only 被误认为完整验收。 */
export function parseArguments(argv) {
  const parsed = {
    evidenceDirectory: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    jar: undefined,
    cargoTargetDirectory: join(repoRoot, "target", "codex-review-redesign"),
    scope: undefined,
    fixture: "full",
    cdpEndpoint: undefined,
    edgeDriver: undefined,
    frontendPort: undefined,
    workspaceRoot: undefined,
    ownedProfileRoot: undefined,
    preflightOnly: false,
    ignoredFiles: DEFAULT_IGNORED_FILES,
    untrackedFiles: DEFAULT_UNTRACKED_FILES,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--preflight-only") {
      parsed.preflightOnly = true;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") parsed.evidenceDirectory = resolve(value);
    else if (argument === "--java-home") parsed.javaHome = resolve(value);
    else if (argument === "--jar") parsed.jar = resolve(value);
    else if (argument === "--cargo-target-directory") parsed.cargoTargetDirectory = resolve(value);
    else if (argument === "--scope") parsed.scope = value;
    else if (argument === "--fixture") parsed.fixture = value;
    else if (argument === "--cdp-endpoint") parsed.cdpEndpoint = value;
    else if (argument === "--edge-driver") parsed.edgeDriver = resolve(value);
    else if (argument === "--frontend-port") parsed.frontendPort = Number(value);
    else if (argument === "--workspace-root") parsed.workspaceRoot = resolve(value);
    else if (argument === "--owned-profile-root") parsed.ownedProfileRoot = resolve(value);
    else if (argument === "--ignored-files") parsed.ignoredFiles = Number(value);
    else if (argument === "--untracked-files") parsed.untrackedFiles = Number(value);
    else throw new Error(`unknown argument: ${argument}`);
    index += 1;
  }
  if (parsed.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (!["git", "full"].includes(parsed.scope)) throw new Error("--scope must be git or full");
  if (!["full", "no-head"].includes(parsed.fixture)) {
    throw new Error("--fixture must be full or no-head");
  }
  for (const [name, value] of [
    ["--ignored-files", parsed.ignoredFiles],
    ["--untracked-files", parsed.untrackedFiles],
  ]) {
    if (!Number.isSafeInteger(value) || value < 0 || value > 10_000) {
      throw new Error(`${name} must be an integer between 0 and 10000`);
    }
  }
  const attaching = parsed.cdpEndpoint !== undefined;
  if (attaching) {
    if (
      parsed.workspaceRoot === undefined ||
      parsed.ownedProfileRoot === undefined ||
      !Number.isSafeInteger(parsed.frontendPort) ||
      parsed.frontendPort < 1 ||
      parsed.frontendPort > 65_535
    ) {
      throw new Error(
        "attach mode requires --workspace-root, --owned-profile-root and --frontend-port",
      );
    }
    if (parsed.edgeDriver !== undefined)
      throw new Error("attach mode does not accept --edge-driver");
  } else if (parsed.jar === undefined) {
    throw new Error("launch mode requires --jar");
  }
  return parsed;
}

/** 确认目标是系统临时目录内的具体子目录，拒绝用户目录、workspace 根和 broad target。 */
export function assertOwnedTemporaryPath(path, label) {
  const root = resolve(tmpdir());
  const target = resolve(path);
  const relation = relative(root, target);
  if (relation === "" || relation.startsWith("..") || isAbsolute(relation)) {
    throw new Error(`${label} must be a child of the OS temp directory`);
  }
  return target;
}

/** 创建一次性 profile、Ja home、WebView UDF、runtime 与真实 Git workspace。 */
async function createRunDirectories() {
  const root = await mkdtemp(join(tmpdir(), "ja-review-redesign-"));
  const settings = join(root, "profile");
  const paths = {
    root,
    settings,
    home: join(settings, ".ja"),
    workspace: join(root, "workspace"),
    webview: join(root, "webview"),
    runtime: join(root, "runtime"),
    roaming: join(root, "appdata", "roaming"),
    local: join(root, "appdata", "local"),
  };
  await Promise.all(
    Object.values(paths)
      .filter((path) => path !== root)
      .map((path) => mkdir(path, { recursive: true })),
  );
  return paths;
}

/** 在 Windows 命令 shim 前固定 token，拒绝 cmd 展开字符。 */
function quoteWindowsToken(value) {
  if (typeof value !== "string" || value.length === 0 || /[\0\r\n"%]/u.test(value)) {
    throw new Error("invalid Windows command token");
  }
  return `"${value}"`;
}

/** 运行真实 Git，并为 merge conflict 之外的所有退出码故障关闭。 */
async function runGit(workspace, args, { allowFailure = false } = {}) {
  try {
    return await execFileAsync("git.exe", args, {
      cwd: workspace,
      windowsHide: true,
      timeout: 30_000,
      maxBuffer: 4 * 1024 * 1024,
    });
  } catch (error) {
    if (allowFailure) return error;
    throw error;
  }
}

/** 以有界 batch 写入压力文件，避免 fixture 自身制造无界 Promise 峰值。 */
async function writePressureFiles(directory, count) {
  const batchSize = 64;
  await mkdir(directory, { recursive: true });
  for (let start = 0; start < count; start += batchSize) {
    const end = Math.min(count, start + batchSize);
    await Promise.all(
      Array.from({ length: end - start }, (_, offset) => {
        const index = start + offset;
        const shard = join(directory, `s${String(Math.floor(index / 250)).padStart(3, "0")}`);
        return mkdir(shard, { recursive: true }).then(() =>
          writeFile(join(shard, `f${String(index).padStart(6, "0")}.txt`), "x\n", "utf8"),
        );
      }),
    );
  }
}

/**
 * 构造部分暂存、同路径双层、冲突、重命名、删除、二进制、特殊字符与压力数据。
 * no-head 只 git init 且保留未跟踪文件，用于独立的无 HEAD 安全态场景。
 */
export async function createReviewGitFixture(
  workspace,
  {
    fixture = "full",
    ignoredFiles = DEFAULT_IGNORED_FILES,
    untrackedFiles = DEFAULT_UNTRACKED_FILES,
  } = {},
) {
  await mkdir(workspace, { recursive: true });
  await runGit(workspace, ["init", "--initial-branch=main"]);
  await runGit(workspace, ["config", "user.name", "Ja Review E2E"]);
  await runGit(workspace, ["config", "user.email", "ja-review-e2e@localhost"]);
  await writeFile(join(workspace, "no-head-untracked.txt"), "no head\n", "utf8");
  if (fixture === "no-head") {
    return { fixture, head: false, ignoredFiles: 0, untrackedFiles: 1 };
  }
  await Promise.all([
    mkdir(join(workspace, "src", "domain", "entity"), { recursive: true }),
    mkdir(join(workspace, "src", "domain", "value"), { recursive: true }),
    mkdir(join(workspace, "assets"), { recursive: true }),
  ]);
  const initial = new Map([
    [".gitignore", "/.review-ignored/\n"],
    ["src/domain/entity/partially-staged.ts", "export const alpha = 1;\nexport const beta = 1;\n"],
    ["src/domain/entity/modified.ts", "export const state = 'before';\n"],
    ["src/domain/value/deleted.ts", "export const removed = true;\n"],
    ["src/renamed-before.ts", "export const renamed = true;\n"],
    ["src/conflict.ts", "export const conflict = 'base';\n"],
  ]);
  for (const [path, content] of initial) {
    await writeFile(join(workspace, path), content, "utf8");
  }
  await writeFile(join(workspace, "assets", "pixel.bin"), Buffer.from([0, 1, 2, 3]));
  await runGit(workspace, ["add", "."]);
  await runGit(workspace, ["commit", "-m", "review baseline"]);
  await runGit(workspace, ["branch", "conflict-side"]);
  await runGit(workspace, ["checkout", "conflict-side"]);
  await writeFile(
    join(workspace, "src", "conflict.ts"),
    "export const conflict = 'side';\n",
    "utf8",
  );
  await runGit(workspace, ["add", "src/conflict.ts"]);
  await runGit(workspace, ["commit", "-m", "conflict side"]);
  await runGit(workspace, ["checkout", "main"]);
  await writeFile(
    join(workspace, "src", "conflict.ts"),
    "export const conflict = 'main';\n",
    "utf8",
  );
  await runGit(workspace, ["add", "src/conflict.ts"]);
  await runGit(workspace, ["commit", "-m", "conflict main"]);
  const merge = await runGit(workspace, ["merge", "conflict-side"], { allowFailure: true });
  assert.notEqual(merge?.code, 0, "fixture merge must conflict");

  const partial = join(workspace, "src", "domain", "entity", "partially-staged.ts");
  await writeFile(partial, "export const alpha = 2;\nexport const beta = 1;\n", "utf8");
  await runGit(workspace, ["add", "src/domain/entity/partially-staged.ts"]);
  await writeFile(partial, "export const alpha = 2;\nexport const beta = 2;\n", "utf8");
  await writeFile(
    join(workspace, "src", "domain", "entity", "modified.ts"),
    "export const state = 'after';\n",
    "utf8",
  );
  await rm(join(workspace, "src", "domain", "value", "deleted.ts"));
  await runGit(workspace, ["mv", "src/renamed-before.ts", "src/renamed-after.ts"]);
  await writeFile(join(workspace, "assets", "pixel.bin"), Buffer.from([0, 9, 8, 7, 6]));
  await mkdir(join(workspace, "src", "new", "feature"), { recursive: true });
  await writeFile(
    join(workspace, "src", "new", "feature", "created.ts"),
    "export const created = true;\n",
    "utf8",
  );
  await mkdir(join(workspace, "src", "空 格"), { recursive: true });
  await writeFile(
    join(workspace, "src", "空 格", "delta.ts"),
    "export const unicode = true;\n",
    "utf8",
  );
  await writePressureFiles(join(workspace, ".review-ignored"), ignoredFiles);
  await writePressureFiles(join(workspace, "zz-review-untracked"), untrackedFiles);
  const { stdout } = await runGit(workspace, ["status", "--porcelain=v2", "--branch"]);
  return {
    fixture,
    head: true,
    ignoredFiles,
    untrackedFiles,
    conflict: stdout.split(/\r?\n/u).some((line) => line.startsWith("u ")),
    partialStaging: stdout
      .split(/\r?\n/u)
      .some((line) => line.startsWith("1 MM") && line.includes("partially-staged.ts")),
  };
}

/** 写入只供 runtime 启动的最小合法配置；Git 验收不会向 dummy Provider 发请求。 */
async function writeIsolatedSettings(home) {
  const config = [
    "schema_version = 1",
    "config_revision = 1",
    'default_access_mode = "approval_required"',
    'default_provider_id = "provider_e2e"',
    'default_model_id = "model_e2e"',
    "default_reasoning_level = { __ja_null = true }",
    "mcp_servers = []",
    "skills = []",
    "",
    "[[providers]]",
    'provider_id = "provider_e2e"',
    'name = "Review E2E"',
    'api = "openai_responses"',
    'base_url = "http://127.0.0.1:9/v1"',
    'credential_id = "cred_e2e"',
    "[providers.network_timeouts]",
    "connect_timeout_ms = 1000",
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
    'name = "Review E2E Model"',
    'model = "review-e2e"',
    "reasoning_level_map = {}",
    "default_reasoning_level = { __ja_null = true }",
    "[providers.models.capabilities]",
    "context_window_tokens = 128000",
    "max_output_tokens = 8192",
    "",
  ].join("\n");
  await mkdir(home, { recursive: true });
  await writeFile(join(home, "config.toml"), config, "utf8");
  const auth = join(home, "auth.json");
  await writeFile(auth, '{"cred_e2e":"unused-review-fixture"}\n', "utf8");
  const account = `${process.env.USERDOMAIN ?? "."}\\${process.env.USERNAME ?? ""}`;
  if (!account.endsWith("\\")) {
    await execFileAsync("icacls.exe", [auth, "/inheritance:r", "/grant:r", `${account}:(F)`], {
      windowsHide: true,
      timeout: 15_000,
      maxBuffer: 512 * 1024,
    });
  }
}

/**
 * 在 Windows 动态客户端端口范围之外选择回环端口；excludedPorts 防止 Vite 与 CDP 在释放
 * 探针 listener 后复用同一端口，最终 owner 仍由 WebView2 endpoint 握手确认。
 */
async function reservePort(excludedPorts = new Set()) {
  const offset = (Date.now() + process.pid) % STABLE_PORT_RANGE.size;
  for (let index = 0; index < STABLE_PORT_RANGE.size; index += 1) {
    const port = STABLE_PORT_RANGE.start + ((offset + index) % STABLE_PORT_RANGE.size);
    if (excludedPorts.has(port)) continue;
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
  throw new Error("failed to reserve a stable loopback port");
}

/** 读取 Tauri 基础与 Windows 覆盖后的主窗口，避免验收悄悄启动另一套窗口配置。 */
async function readProductionMainWindowConfig() {
  const [baseConfig, windowsConfig] = await Promise.all([
    readFile(join(repoRoot, "src-tauri", "tauri.conf.json"), "utf8").then(JSON.parse),
    readFile(join(repoRoot, "src-tauri", "tauri.windows.conf.json"), "utf8").then(JSON.parse),
  ]);
  const baseWindow = baseConfig?.app?.windows?.find((window) => window?.label === "main");
  const windowsWindow = windowsConfig?.app?.windows?.find((window) => window?.label === "main");
  if (baseWindow === undefined || baseWindow === null || typeof baseWindow !== "object") {
    throw new Error("production main window configuration is missing");
  }
  return { ...baseWindow, ...(windowsWindow ?? {}) };
}

/** 生成唯一 identifier、生产窗口和 dev origin 的私有 overlay，不修改仓库配置。 */
async function writeTauriOverlay(directories, frontendPort, useEdgeDriver) {
  const origin = `http://localhost:${frontendPort}`;
  const websocket = `ws://localhost:${frontendPort}`;
  const path = join(directories.runtime, "tauri.review-redesign.conf.json");
  const mainWindow = await readProductionMainWindowConfig();
  const config = {
    identifier: `io.github.kongweiguang.ja.review${randomUUID().replaceAll("-", "")}`,
    build: {
      devUrl: origin,
      ...(useEdgeDriver
        ? { runner: join(repoRoot, "scripts", "e2e", "webview2-edgedriver-runner.cmd") }
        : {}),
    },
    app: {
      windows: [{ ...mainWindow }],
      security: {
        devCsp: `default-src 'self'; connect-src 'self' ipc: http://ipc.localhost ${origin} ${websocket}; img-src 'self' data: blob: ja-attachment: http://ja-attachment.localhost; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self' data:; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`,
      },
    },
  };
  await writeFile(path, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return path;
}

/** 验证 JDK/Node/pnpm 与 debug JAR，防止从默认 JDK 21 或旧构建启动。 */
export async function validateToolchain({ javaHome, jar }) {
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
    throw new Error("Review runner requires JDK 25");
  if (!/^v24\./u.test(nodeResult.stdout.trim())) throw new Error("Review runner requires Node 24");
  if (pnpmResult.stdout.trim() !== "10.33.0")
    throw new Error("Review runner requires pnpm 10.33.0");
  if (!jarStat.isFile() || jarStat.size < 1) throw new Error("Review runner JAR is empty");
  return { java, jarSize: jarStat.size };
}

/** 验证调用方显式选择的 Microsoft EdgeDriver，并返回版本与文件身份。 */
export async function validateEdgeDriver(path) {
  const metadata = await stat(path);
  if (!metadata.isFile()) throw new Error("EdgeDriver path is not a file");
  const { stdout } = await execFileAsync(path, ["--version"], {
    windowsHide: true,
    timeout: 15_000,
    maxBuffer: 64 * 1024,
  });
  const match = /^Microsoft Edge WebDriver ([0-9]+(?:\.[0-9]+){3})\b/u.exec(stdout.trim());
  if (match === null) throw new Error("EdgeDriver version output is invalid");
  return { path, version: match[1], size: metadata.size, sha256: await sha256(path) };
}

/** 在隔离 USERPROFILE 前解析当前 Toolchain 的真实 Cargo，避免 rustup 读取空 profile。 */
async function locateCargoCommand() {
  const { stdout } = await execFileAsync("rustup.exe", ["which", "cargo"], {
    windowsHide: true,
    timeout: 15_000,
    maxBuffer: 1 * 1024 * 1024,
  });
  const cargo = stdout.trim();
  if (!isAbsolute(cargo) || !cargo.toLowerCase().endsWith("\\cargo.exe")) {
    throw new Error("rustup did not resolve an absolute cargo.exe");
  }
  return cargo;
}

/** 流式计算 JAR 身份，报告只保留 SHA-256 与大小。 */
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

/** 构造隔离 dev 进程环境；真实用户 Ja home、AppData 与 WebView UDF 均不可见。 */
export function buildLaunchEnvironment({
  directories,
  java,
  jar,
  frontendPort,
  cdpPort,
  cargoTargetDirectory,
  edgeDriver,
  edgeDriverPort,
  edgeDriverSessionPath,
  cargo,
}) {
  const inheritedPath = process.env.PATH ?? process.env.Path ?? "";
  const env = {
    ...process.env,
    APPDATA: directories.roaming,
    LOCALAPPDATA: directories.local,
    USERPROFILE: directories.settings,
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
    ...(edgeDriver === undefined
      ? {
          WEBVIEW2_USER_DATA_FOLDER: directories.webview,
          WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --remote-debugging-port=${cdpPort}`,
        }
      : {
          JA_E2E_CARGO_COMMAND: cargo,
          JA_E2E_EDGEDRIVER_PATH: edgeDriver,
          JA_E2E_EDGEDRIVER_PORT: String(edgeDriverPort),
          JA_E2E_EDGEDRIVER_SESSION_PATH: edgeDriverSessionPath,
          JA_E2E_WEBVIEW_DATA_DIR: join(directories.local, "main", "ja-review-edge-profile"),
        }),
    PATH: [dirname(java), inheritedPath].filter(Boolean).join(";"),
  };
  delete env.Path;
  for (const name of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]) delete env[name];
  for (const name of Object.keys(env)) {
    if (/^JA_(?:E2E_)?REAL_PROVIDER/u.test(name)) delete env[name];
  }
  return env;
}

/** 启动真实 `tauri dev --no-watch`，仅捕获有界输出尾部用于失败归因。 */
function launchTauri({ overlay, environment }) {
  const comspec = process.env.ComSpec ?? process.env.COMSPEC ?? "cmd.exe";
  const commandLine = `"${quoteWindowsToken("pnpm.cmd")} tauri dev --no-watch --config ${quoteWindowsToken(overlay)}"`;
  const child = spawn(comspec, ["/d", "/s", "/c", commandLine], {
    cwd: repoRoot,
    env: environment,
    windowsHide: true,
    windowsVerbatimArguments: true,
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

/** 等待 CDP version endpoint，launcher 提前退出时立即失败而非耗尽完整期限。 */
async function waitForCdp(endpoint, launch, deadline = Date.now() + 120_000) {
  const url = new URL("/json/version", endpoint).href;
  while (Date.now() < deadline) {
    if (launch?.child.exitCode !== null) {
      throw new Error(`Tauri launcher exited before CDP: ${launch.output.stderr.slice(-1000)}`);
    }
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(2_000) });
      const payload = await response.json();
      if (response.ok && typeof payload.webSocketDebuggerUrl === "string") return endpoint;
    } catch {
      // CDP cold start is expected to refuse connections until WebView2 owns the port.
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  }
  const launchTail = launch?.output.stderr.trim().slice(-2_000);
  throw new Error(
    launchTail
      ? `Tauri WebView2 CDP startup timed out: ${launchTail}`
      : "Tauri WebView2 CDP startup timed out",
  );
}

/** 等待 EdgeDriver runner 原子发布 session，再复用相同 CDP version 握手。 */
async function waitForEdgeDriverSession(path, launch, deadline = Date.now() + 120_000) {
  while (Date.now() < deadline) {
    if (launch?.child.exitCode !== null) {
      throw new Error(`Tauri EdgeDriver launcher exited: ${launch.output.stderr.slice(-2_000)}`);
    }
    try {
      const session = JSON.parse(await readFile(path, "utf8"));
      if (
        Number.isSafeInteger(session.debuggerPort) &&
        session.debuggerPort > 0 &&
        session.debuggerPort <= 65_535 &&
        Number.isSafeInteger(session.appPid) &&
        session.appPid > 0
      ) {
        const endpoint = `http://127.0.0.1:${session.debuggerPort}`;
        await waitForCdp(endpoint, launch, deadline);
        return { endpoint, session };
      }
    } catch {
      // Runner 只原子发布完整 JSON；构建与 session 创建期间继续等待。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 200));
  }
  throw new Error(`Tauri EdgeDriver session timed out: ${launch.output.stderr.slice(-2_000)}`);
}

/** 从 CDP contexts 中只选择 Ja Tauri dev/production main page。 */
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

/** 只终止本 runner 启动的 launcher PID 整棵树，不枚举或触碰其它 Ja 进程。 */
async function terminateOwnedLauncher(launch) {
  const pid = launch?.child?.pid;
  if (!Number.isSafeInteger(pid) || launch.child.exitCode !== null) return;
  await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
    windowsHide: true,
    timeout: 15_000,
    maxBuffer: 512 * 1024,
  }).catch(() => undefined);
}

/** 清理前再次解析真实路径，确保 recursive remove 仍局限于本轮随机 temp root。 */
async function removeOwnedRunRoot(root) {
  const expected = assertOwnedTemporaryPath(root, "run root");
  const actual = await realpath(root).catch(() => expected);
  assert.equal(resolve(actual), resolve(expected));
  await rm(actual, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });
}

/** 把失败原因限制为单行并脱敏本轮临时根。 */
function safeFailure(error, runRoot) {
  return String(error?.message ?? error)
    .replaceAll(runRoot ?? "<no-run-root>", "<RUN_ROOT>")
    .replace(/[\r\n]+/gu, " ")
    .slice(0, 2_000);
}

/**
 * 执行 attach 或自主 launch 模式，并原子发布 JSON 报告。非 CLI 调用可注入窄场景 driver，
 * 以复用相同的 profile、进程与清理所有权；未注入时保持 Review 的驱动和校验合同不变。
 */
export async function runProduction(options) {
  await mkdir(options.evidenceDirectory, { recursive: true });
  const reportFileName = options.reportFileName ?? "review-redesign-report.json";
  const driver = options.driver ?? runReviewRedesignWebView2;
  const validateReport =
    options.validateReport ??
    (options.driver === undefined
      ? (candidate) => validateReviewRedesignReport(candidate, { requestedScope: options.scope })
      : undefined);
  let directories;
  let launch;
  let browser;
  let report;
  let frontendPort;
  let fixtureFacts;
  let edgeDriverIdentity;
  let edgeDriverSession;
  try {
    let endpoint = options.cdpEndpoint;
    if (endpoint === undefined) {
      directories = await createRunDirectories();
      assert.ok(!options.evidenceDirectory.startsWith(`${directories.root}\\`));
      const [toolchain, cargo, validatedEdgeDriver] = await Promise.all([
        validateToolchain(options),
        options.edgeDriver === undefined ? Promise.resolve(undefined) : locateCargoCommand(),
        options.edgeDriver === undefined
          ? Promise.resolve(undefined)
          : validateEdgeDriver(options.edgeDriver),
      ]);
      edgeDriverIdentity = validatedEdgeDriver;
      [fixtureFacts] = await Promise.all([
        createReviewGitFixture(directories.workspace, options),
        writeIsolatedSettings(directories.home),
      ]);
      frontendPort = await reservePort();
      const automationPort = await reservePort(new Set([frontendPort]));
      const edgeDriverSessionPath = join(directories.runtime, "edgedriver-session.json");
      const overlay = await writeTauriOverlay(
        directories,
        frontendPort,
        options.edgeDriver !== undefined,
      );
      const environment = buildLaunchEnvironment({
        directories,
        java: toolchain.java,
        jar: options.jar,
        frontendPort,
        cdpPort: automationPort,
        cargoTargetDirectory: options.cargoTargetDirectory,
        edgeDriver: options.edgeDriver,
        edgeDriverPort: automationPort,
        edgeDriverSessionPath,
        cargo,
      });
      launch = launchTauri({ overlay, environment });
      if (options.edgeDriver === undefined) {
        endpoint = `http://127.0.0.1:${automationPort}`;
        await waitForCdp(endpoint, launch);
      } else {
        const attached = await waitForEdgeDriverSession(edgeDriverSessionPath, launch);
        endpoint = attached.endpoint;
        edgeDriverSession = attached.session;
      }
    } else {
      assertOwnedTemporaryPath(options.workspaceRoot, "workspace root");
      assertOwnedTemporaryPath(options.ownedProfileRoot, "owned profile root");
      directories = {
        root: options.ownedProfileRoot,
        workspace: options.workspaceRoot,
      };
      frontendPort = options.frontendPort;
      await waitForCdp(endpoint, undefined);
    }
    browser = await chromium.connectOverCDP(endpoint);
    const page = await findTauriPage(browser, frontendPort);
    report = await driver({
      page,
      workspaceRoot: directories.workspace,
      evidenceDirectory: join(options.evidenceDirectory, "screenshots"),
      scope: options.scope,
    });
    report.execution = {
      boundary: options.cdpEndpoint === undefined ? "debug_jar" : "external_cdp",
      nativeImageVerified: false,
      fixture: options.fixture,
      fixtureFacts,
      edgeDriver:
        edgeDriverIdentity === undefined
          ? undefined
          : {
              version: edgeDriverIdentity.version,
              sha256: edgeDriverIdentity.sha256,
              size: edgeDriverIdentity.size,
              browserVersion: edgeDriverSession?.browserVersion,
            },
      jar:
        options.jar === undefined
          ? undefined
          : { sha256: await sha256(options.jar), size: (await stat(options.jar)).size },
    };
    await validateReport?.(report);
    await writeFile(
      join(options.evidenceDirectory, reportFileName),
      `${JSON.stringify(report, null, 2)}\n`,
      "utf8",
    );
    return report;
  } catch (error) {
    const failure = {
      contractVersion: 1,
      verdict: "FAIL",
      scope: options.scope,
      runtimeBoundary: options.cdpEndpoint === undefined ? "debug_jar" : "external_cdp",
      nativeImageVerified: false,
      error: safeFailure(error, directories?.root),
    };
    await writeFile(
      join(options.evidenceDirectory, reportFileName),
      `${JSON.stringify(failure, null, 2)}\n`,
      "utf8",
    ).catch(() => undefined);
    throw error;
  } finally {
    await browser?.close().catch(() => undefined);
    await terminateOwnedLauncher(launch);
    if (launch !== undefined && directories?.root !== undefined) {
      await removeOwnedRunRoot(directories.root).catch(() => undefined);
    }
  }
}

/** 只做工具链、路径与 fixture 参数预检，不启动 Tauri 或写 Git workspace。 */
async function runPreflight(options) {
  if (options.cdpEndpoint === undefined) {
    await validateToolchain(options);
    if (options.edgeDriver !== undefined) {
      await Promise.all([validateEdgeDriver(options.edgeDriver), locateCargoCommand()]);
    }
  } else {
    assertOwnedTemporaryPath(options.workspaceRoot, "workspace root");
    assertOwnedTemporaryPath(options.ownedProfileRoot, "owned profile root");
  }
  return {
    status: "ready",
    scope: options.scope,
    runtimeBoundary: "debug_jar",
    nativeImageVerified: false,
  };
}

/** CLI 入口只输出稳定阶段标记，详细证据统一写入 report JSON。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.preflightOnly) {
    const result = await runPreflight(options);
    console.log(`JA_REVIEW_REDESIGN_PREFLIGHT_OK ${JSON.stringify(result)}`);
    return;
  }
  const report = await runProduction(options);
  console.log(
    report.verdict === "PASS"
      ? "JA_REVIEW_REDESIGN_PASS"
      : "JA_REVIEW_REDESIGN_GIT_ONLY_NOT_VERIFIED",
  );
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(`JA_REVIEW_REDESIGN_FAIL ${String(error?.message ?? error).slice(0, 2000)}`);
    process.exitCode = 1;
  });
}
