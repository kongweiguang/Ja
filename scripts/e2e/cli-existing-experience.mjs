// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { execFile, spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { basename, delimiter, dirname, isAbsolute, join, relative, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import net from "node:net";
import {
  compileConPtyHarness,
  resolveArtifacts,
  startConPtyCli,
  writeScreenEvidence,
} from "./cli-usage-conpty.mjs";
import { startClipboardGuard } from "./cli-fullscreen-acceptance.mjs";
import {
  initializeParams,
  JsonlSession,
  readCompleteItemContent,
} from "./real-provider-smoke.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const evidenceRoot = join(repoRoot, "target", "cli-existing-experience-20261013");
const defaultSeedRoot = join(repoRoot, "target", "cli-markdown-20261013", "native");
const seedRoot = resolve(repoRoot, process.env.JA_CLI_EXPERIENCE_SEED_ROOT ?? defaultSeedRoot);
const targetRoot = join(repoRoot, "target");
const seedResultPath = join(seedRoot, "seed-result.json");
const seedDiagnosticPath = join(seedRoot, "seed-diagnostic.json");
const placeholderSecret = "ja-cli-usage-conpty-placeholder";
const execFileAsync = promisify(execFile);

/** 以有限等待观察终端与剪贴板状态，避免验收脚本留下无界轮询。 @author kongweiguang */
function delay(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

/** 只接纳隔离 seed 的 home 与 JAR 快照；路径关系校验避免脚本误碰真实 JA_HOME。 @author kongweiguang */
async function loadSeed() {
  const relativeSeedRoot = relative(targetRoot, seedRoot);
  assert.ok(
    relativeSeedRoot.length > 0 &&
      relativeSeedRoot !== ".." &&
      !relativeSeedRoot.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`) &&
      !isAbsolute(relativeSeedRoot),
    "JA_CLI_EXPERIENCE_SEED_ROOT must remain inside this repository's target directory",
  );
  assert.equal(basename(seedRoot), "native", "the seed root must be a native fixture directory");
  const seed = JSON.parse(await readFile(seedResultPath, "utf8"));
  const diagnostic = JSON.parse(await readFile(seedDiagnosticPath, "utf8"));
  assert.equal(seed.status, "seeded");
  assert.equal(resolve(seed.nativeRoot), resolve(seedRoot));
  assert.equal(typeof seed.userPrompt, "string");
  assert.equal(seed.threadId, diagnostic.threadId);
  assert.equal(resolve(seed.directories.home), resolve(seed.profileRoot, "home"));
  assert.equal(resolve(seed.directories.workspace), resolve(seed.profileRoot, "workspace"));
  assert.ok(resolve(seed.profileRoot).startsWith(`${resolve(seedRoot)}\\`));
  assert.equal(diagnostic.readCompleteItemContent?.error, null);
  assert.equal(diagnostic.readCompleteItemContent?.content, diagnostic.terminalContent);
  assert.equal(diagnostic.snapshotFinalItem?.kind, "final_answer");
  return { seed, diagnostic };
}

/** 把唯一子进程的 JAVA_HOME 与 PATH 固定到 seed 记录的 JDK25，并留存原环境供 finally 恢复。 @author kongweiguang */
function usePinnedJdk(javaHome) {
  const original = { JAVA_HOME: process.env.JAVA_HOME, PATH: process.env.PATH };
  const bin = join(javaHome, "bin");
  process.env.JAVA_HOME = javaHome;
  process.env.PATH = `${bin}${delimiter}${original.PATH ?? ""}`;
  return () => {
    if (original.JAVA_HOME === undefined) delete process.env.JAVA_HOME;
    else process.env.JAVA_HOME = original.JAVA_HOME;
    if (original.PATH === undefined) delete process.env.PATH;
    else process.env.PATH = original.PATH;
  };
}

/** 每个 Java/Maven 命令都由当前 JDK25 环境启动，并核实两个版本输出而非依赖宿主默认值。 @author kongweiguang */
async function verifyJdk25(javaHome, java) {
  assert.match(process.versions.node, /^24\./u, "the repository E2E runtime requires Node 24");
  assert.equal(resolve(process.env.JAVA_HOME), resolve(javaHome));
  assert.equal(resolve((process.env.PATH ?? "").split(delimiter)[0]), resolve(javaHome, "bin"));
  const javaVersion = await execFileAsync(java, ["-version"], {
    windowsHide: true,
    timeout: 5_000,
    maxBuffer: 64 * 1024,
    env: process.env,
  });
  const javaOutput = `${javaVersion.stdout}\n${javaVersion.stderr}`.trim();
  assert.match(javaOutput, /version\s+"25(?:\.|"|\s)/u);
  const mavenVersion = await execFileAsync(
    process.env.ComSpec ?? "cmd.exe",
    ["/d", "/s", "/c", "mvn.cmd -version"],
    { windowsHide: true, timeout: 10_000, maxBuffer: 64 * 1024, env: process.env },
  );
  const mavenOutput = `${mavenVersion.stdout}\n${mavenVersion.stderr}`.trim();
  assert.match(mavenOutput, /Java version:\s*25(?:\.|\s)/u);
  assert.match(
    mavenOutput,
    new RegExp(`(?:Java home:|runtime:)\\s*${escapeRegExp(javaHome)}`, "iu"),
  );
  return { nodeVersion: process.versions.node, javaVersion: javaOutput, mavenVersion: mavenOutput };
}

/** 转义 JDK 路径中的正则字符，使 Maven runtime 校验对应同一安装目录。 @author kongweiguang */
function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
}

/** 以 App Server 唯一 owner 身份读取 user 与终答全文，记录精简身份及正文哈希。 @author kongweiguang */
async function readThreadEvidence(artifacts, directories, threadId, expectedFinalItemId, expectedUserPrompt) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: placeholderSecret,
    endpoint: "http://127.0.0.1:50065",
  });
  let ready = false;
  let primaryFailure;
  let result;
  try {
    const initializedFrame = await session.request("runtime/initialize", initializeParams());
    const initialized = requireRpcResult(initializedFrame, "runtime/initialize");
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    ready = true;
    const config = requireRpcResult(await session.request("configuration/read", {}), "configuration/read");
    const document = config.user.document;
    assert.ok(document && Array.isArray(document.providers), "configuration/read must expose the active user document");
    const provider = document.providers.find((entry) => entry.provider_id === document.default_provider_id);
    assert.ok(provider?.base_url, "the isolated profile must retain its loopback fixture endpoint");
    const endpoint = new URL(provider.base_url);
    assert.ok(
      endpoint.hostname === "127.0.0.1" || endpoint.hostname === "localhost" || endpoint.hostname === "::1",
      "refusing any provider endpoint that is not loopback",
    );
    const endpointClosed = await assertLoopbackPortClosed(endpoint.hostname, Number(endpoint.port));
    const history = requireRpcResult(
      await session.request("thread/read", { threadId, tail: true, limit: 200 }),
      "thread/read",
    );
    assert.ok(Array.isArray(history.items), "thread/read must return its persisted items");
    const finalItem = history.items.find((item) => item.itemId === expectedFinalItemId);
    assert.ok(finalItem, "the seeded final answer must remain in the visible history page");
    const userItem = history.items.find((item) => item.kind === "user_input");
    assert.ok(userItem, "the seeded user message must remain in the visible history page");
    const finalText = await readCompleteItemContent(session, threadId, expectedFinalItemId);
    const userText = await readCompleteItemContent(session, threadId, userItem.itemId);
    assert.equal(userText, expectedUserPrompt, "the complete persisted user message must match the seed prompt");
    result = {
      userItemIds: history.items.filter((item) => item.kind === "user_input").map((item) => item.itemId),
      turnIds: [...new Set(history.items.map((item) => item.turnId).filter((id) => typeof id === "string"))].sort(),
      itemKinds: history.items.map((item) => item.kind),
      userItemId: userItem.itemId,
      userPreviewCharacters: Array.from(typeof userItem.text === "string" ? userItem.text : "").length,
      userItemTruncated: userItem.truncated === true,
      userTotalCharacters: userItem.totalCharacters ?? null,
      userPromptCharacters: Array.from(userText).length,
      userPromptSha256: createHash("sha256").update(userText).digest("hex").toUpperCase(),
      finalItemId: finalItem.itemId,
      finalAnswerCharacters: Array.from(finalText).length,
      finalAnswerSha256: createHash("sha256").update(finalText).digest("hex").toUpperCase(),
      providerLoopbackOnly: true,
      providerPortClosed: endpointClosed,
    };
  } catch (error) {
    primaryFailure = error;
  } finally {
    if (ready && !session.exited) {
      try {
        const exit = await session.shutdown();
        assert.equal(exit?.code, 0, "read-only App Server must exit cleanly");
      } catch (error) {
        if (primaryFailure) primaryFailure.message += `; read-only App Server shutdown failed: ${safeError(error)}`;
        else primaryFailure = error;
      }
    }
    try {
      await session.forceClose();
    } catch (error) {
      if (primaryFailure) primaryFailure.message += `; read-only App Server cleanup failed: ${safeError(error)}`;
      else primaryFailure = error;
    }
  }
  if (primaryFailure) throw primaryFailure;
  return result;
}

/** 无负载连接本地配置端口，确保端口被拒绝后才允许 UI 测试中可能误提交的请求发生。 @author kongweiguang */
async function assertLoopbackPortClosed(host, port) {
  assert.ok(Number.isInteger(port) && port > 0 && port <= 65_535, "loopback endpoint must name a real TCP port");
  await new Promise((resolvePromise, rejectPromise) => {
    const socket = net.createConnection({ host, port });
    const timer = setTimeout(() => {
      socket.destroy();
      rejectPromise(new Error("loopback Provider safety probe timed out; refusing to send test input"));
    }, 750);
    socket.once("connect", () => {
      clearTimeout(timer);
      socket.destroy();
      rejectPromise(new Error("loopback Provider port is listening; refusing to send test input"));
    });
    socket.once("error", (error) => {
      clearTimeout(timer);
      if (error.code === "ECONNREFUSED") resolvePromise();
      else rejectPromise(new Error(`loopback Provider safety probe failed: ${error.code ?? "network error"}`));
    });
  });
  return true;
}

/** RPC 错误只带稳定分类，避免验收记录意外包含配置正文或凭据。 @author kongweiguang */
function requireRpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = frame.error?.data?.errorCode ?? frame.error?.data?.category ?? "RPC_ERROR";
    throw new Error(`${operation} failed: ${String(code)}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 返回经占位凭据清理的简短错误，诊断不会写入 Provider 输入正文。 @author kongweiguang */
function safeError(error) {
  return String(error instanceof Error ? error.message : error)
    .replaceAll(placeholderSecret, "<redacted>")
    .slice(0, 1_000);
}

/** 等待唯一屏幕标记并返回当前 TUI 行，超时时间受本次验收上限约束。 @author kongweiguang */
async function waitVisible(cli, predicate, label, timeoutMs = 12_000) {
  return cli.waitForScreen(predicate, label, timeoutMs);
}

/** 只接受输出水位后的新终端帧，并要求目标画面稳定，避免旧画面抢先命中异步动作断言。 @author kongweiguang */
async function waitForFreshStableScreen(cli, vtLog, outputWatermark, predicate, label, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  let readySince = null;
  let previousScreen = null;
  let latest = null;
  while (Date.now() < deadline) {
    await cli.pullVt();
    const outputBytes = (await stat(vtLog)).size;
    const text = cli.screen.text();
    const lines = cli.screen.lines();
    const fresh = outputBytes > outputWatermark;
    const ready = fresh && predicate(text, lines);
    if (ready && text === previousScreen) {
      if (Date.now() - readySince >= 240) {
        return { text, lines, outputWatermark, outputBytes, stableForMs: Date.now() - readySince };
      }
    } else if (ready) {
      readySince = Date.now();
      previousScreen = text;
    } else {
      readySince = null;
      previousScreen = null;
    }
    latest = { text, outputBytes, fresh };
    if (cli.child.exitCode !== null) throw new Error(`CLI exited before ${label}`);
    await delay(80);
  }
  throw new Error(
    `timed out waiting for fresh, stable ${label}; freshOutput=${latest?.fresh ?? false}; outputBytes=${latest?.outputBytes ?? 0}; last visible screen:\n${cli.screen.text()}`,
  );
}

/** 只检查屏幕底部 Composer 区域，避免对话正文里的相同字母误判为草稿。 @author kongweiguang */
function composerTail(cli) {
  return cli.screen.lines().slice(-8).join("\n");
}

/** 用明确延迟制造逐键输入；快字符场景单独成批送入，避免控制管道往返变成模拟打字。 @author kongweiguang */
async function sendNativeText(cli, text, intervalMs = 24) {
  for (const character of text) {
    cli.sendKeys(character);
    await delay(intervalMs);
  }
}

/** 仅在确认已有草稿标记后用 Ctrl+C 清空编辑区，避免空态下触发退出确认。 @author kongweiguang */
async function clearComposer(cli, marker) {
  const current = await cli.waitForScreen(
    (_visible, lines) => lines.slice(-8).join("\n").includes(marker),
    `Composer draft marker ${marker}`,
  );
  assert.ok(current.lines.slice(-8).join("\n").includes(marker));
  cli.sendKeys("\u0003");
  await cli.waitForScreen(
    (_visible, lines) => !lines.slice(-8).join("\n").includes(marker),
    `clear Composer draft marker ${marker}`,
  );
}

/** 以期限包住 helper 进程的握手与释放等待；超时后 finally 仍会向子进程发送释放行。 @author kongweiguang */
function withDeadline(promise, timeoutMs, label) {
  let timer;
  const timeout = new Promise((_, rejectPromise) => {
    timer = setTimeout(() => rejectPromise(new Error(`${label} timed out`)), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** 仅锁定当前 Windows 剪贴板做失败注入；原生线程负责 Open、有限等待释放与 Close，避免跨线程释放失效。 @author kongweiguang */
async function createClipboardLockHelper(pwsh, runRoot) {
  const scriptPath = join(runRoot, "hold-native-clipboard.ps1");
  const source = String.raw`
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Text;
using System.Threading;
using System.Runtime.InteropServices;
public static class JaCliAcceptanceClipboardLock {
  [DllImport("user32.dll", SetLastError = true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  public static extern bool OpenClipboard(IntPtr owner);
  [DllImport("user32.dll", SetLastError = true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  public static extern bool CloseClipboard();
  [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "CreateWindowExW", SetLastError = true)]
  private static extern IntPtr CreateWindowEx(uint exStyle, string className, string windowName, uint style,
    int x, int y, int width, int height, IntPtr parent, IntPtr menu, IntPtr instance, IntPtr parameter);
  [DllImport("user32.dll", SetLastError = true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  private static extern bool DestroyWindow(IntPtr window);
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, EntryPoint = "GetModuleHandleW", SetLastError = true)]
  private static extern IntPtr GetModuleHandle(string moduleName);
  [DllImport("kernel32.dll", SetLastError = true)]
  private static extern IntPtr GetStdHandle(int handle);
  [DllImport("kernel32.dll", SetLastError = true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  private static extern bool PeekNamedPipe(IntPtr pipe, IntPtr buffer, uint bufferSize, IntPtr bytesRead, out uint available, IntPtr bytesLeft);
  [DllImport("kernel32.dll", SetLastError = true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  private static extern bool ReadFile(IntPtr file, byte[] buffer, uint count, out uint bytesRead, IntPtr overlapped);

  public static int Hold() {
    IntPtr owner = CreateWindowEx(0, "STATIC", "Ja clipboard acceptance lock", 0,
      0, 0, 0, 0, new IntPtr(-3), IntPtr.Zero, GetModuleHandle(null), IntPtr.Zero);
    if (owner == IntPtr.Zero) {
      Console.Out.WriteLine("OWNER_WINDOW_FAILED=" + Marshal.GetLastWin32Error());
      Console.Out.Flush();
      return 20;
    }
    bool opened = false;
    int exitCode = 0;
    try {
      long openDeadline = Stopwatch.GetTimestamp() + 5L * Stopwatch.Frequency;
      int openError = 0;
      while (!opened && Stopwatch.GetTimestamp() < openDeadline) {
        opened = OpenClipboard(owner);
        if (!opened) {
          openError = Marshal.GetLastWin32Error();
          Thread.Sleep(25);
        }
      }
      if (!opened) {
        Console.Out.WriteLine("OPEN_FAILED=" + openError);
        exitCode = 21;
      } else {
        Console.Out.WriteLine("OPEN=true");
        Console.Out.Flush();

        IntPtr input = GetStdHandle(-10);
        var line = new List<byte>();
        long releaseDeadline = Stopwatch.GetTimestamp() + 30L * Stopwatch.Frequency;
        bool lineReady = false;
        while (Stopwatch.GetTimestamp() < releaseDeadline && !lineReady) {
          uint available;
          if (!PeekNamedPipe(input, IntPtr.Zero, 0, IntPtr.Zero, out available, IntPtr.Zero)) {
            Console.Out.WriteLine("WAIT_FAILED=" + Marshal.GetLastWin32Error());
            Console.Out.Flush();
            exitCode = 25;
            break;
          }
          if (available > 0) {
            byte[] bytes = new byte[(int)Math.Min(available, 128u)];
            uint read;
            if (!ReadFile(input, bytes, (uint)bytes.Length, out read, IntPtr.Zero)) {
              Console.Out.WriteLine("WAIT_FAILED=" + Marshal.GetLastWin32Error());
              Console.Out.Flush();
              exitCode = 25;
              break;
            }
            for (int i = 0; i < (int)read; i++) {
              if (bytes[i] == (byte)'\n') { lineReady = true; break; }
              line.Add(bytes[i]);
            }
          } else {
            Thread.Sleep(20);
          }
        }
        if (exitCode == 0 && !lineReady) exitCode = 23;
        else if (exitCode == 0 && Encoding.UTF8.GetString(line.ToArray()).TrimEnd('\r') != "release") exitCode = 22;
      }
    }
    catch (Exception error) {
      Console.Out.WriteLine("WAIT_FAILED=" + error.GetType().Name);
      Console.Out.Flush();
      exitCode = 25;
    }
    finally {
      if (opened) {
        if (CloseClipboard()) Console.Out.WriteLine("CLOSED=true");
        else {
          Console.Out.WriteLine("CLOSE_FAILED=" + Marshal.GetLastWin32Error());
          if (exitCode == 0) exitCode = 24;
        }
      }
      if (!DestroyWindow(owner)) {
        Console.Out.WriteLine("OWNER_WINDOW_DESTROY_FAILED=" + Marshal.GetLastWin32Error());
        if (exitCode == 0) exitCode = 26;
      }
      Console.Out.Flush();
    }
    return exitCode;
  }
}
'@
$exitCode = [JaCliAcceptanceClipboardLock]::Hold()
exit $exitCode
`;
  await writeFile(scriptPath, source, "utf8");
  const child = spawn(pwsh, ["-NoLogo", "-NoProfile", "-NonInteractive", "-File", scriptPath], {
    cwd: runRoot,
    env: process.env,
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"],
  });
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdin.on("error", () => {});
  let partial = "";
  let stderr = "";
  let opened = false;
  let closed = false;
  let waitFailure = null;
  let releaseRequested = false;
  let resolveOpened;
  let rejectOpened;
  let resolveClosed;
  let rejectClosed;
  let resolveExit;
  const openedPromise = new Promise((resolvePromise, rejectPromise) => {
    resolveOpened = resolvePromise;
    rejectOpened = rejectPromise;
  });
  const closedPromise = new Promise((resolvePromise, rejectPromise) => {
    resolveClosed = resolvePromise;
    rejectClosed = rejectPromise;
  });
  void openedPromise.catch(() => {});
  void closedPromise.catch(() => {});
  const exitPromise = new Promise((resolvePromise) => {
    resolveExit = resolvePromise;
  });
  child.stdout.on("data", (chunk) => {
    partial += chunk;
    while (true) {
      const newline = partial.indexOf("\n");
      if (newline < 0) break;
      const line = partial.slice(0, newline).replace(/\r$/u, "");
      partial = partial.slice(newline + 1);
      if (line === "OPEN=true") {
        opened = true;
        resolveOpened(line);
      } else if (line.startsWith("OPEN_FAILED=")) {
        rejectOpened(new Error(`OpenClipboard failed with Win32 error ${line.slice("OPEN_FAILED=".length)}`));
      } else if (line === "CLOSED=true") {
        closed = true;
        resolveClosed(line);
      } else if (line.startsWith("CLOSE_FAILED=")) {
        rejectClosed(new Error(`CloseClipboard failed with Win32 error ${line.slice("CLOSE_FAILED=".length)}`));
      } else if (line.startsWith("WAIT_FAILED=")) {
        waitFailure = line.slice("WAIT_FAILED=".length);
      }
    }
  });
  child.stderr.on("data", (chunk) => {
    stderr = `${stderr}${chunk}`.slice(-2_000);
  });
  child.once("error", (error) => {
    rejectOpened(new Error(`clipboard lock helper could not start: ${error.code ?? "process error"}`));
    rejectClosed(new Error(`clipboard lock helper failed: ${error.code ?? "process error"}`));
  });
  child.once("exit", (code, signal) => {
    const result = { code, signal, stderr };
    resolveExit(result);
    if (!opened) rejectOpened(new Error(`clipboard lock helper exited before locking (${code ?? signal})`));
    if (!closed) rejectClosed(new Error(`clipboard lock helper exited before CloseClipboard (${code ?? signal})`));
  });
  return {
    pid: child.pid,
    scriptPath,
    ready: withDeadline(openedPromise, 8_000, "OpenClipboard acquisition"),
    status() {
      return { pid: child.pid, openConfirmed: opened, closeObserved: closed, waitFailure, processAlive: child.exitCode === null };
    },
    async release() {
      if (child.exitCode === null && !releaseRequested) {
        releaseRequested = true;
        child.stdin.write("release\n");
      }
      try {
        await withDeadline(closedPromise, 6_000, "CloseClipboard acknowledgement");
      } catch (error) {
        child.stdin.end();
        if (child.exitCode === null) child.kill();
        await withDeadline(exitPromise, 3_000, "clipboard lock helper cleanup").catch(() => {});
        throw error;
      }
      child.stdin.end();
      const result = await withDeadline(exitPromise, 3_000, "clipboard lock helper exit");
      assert.equal(result.code, 0, `clipboard lock helper exited abnormally${result.stderr ? `: ${result.stderr.trim()}` : ""}`);
      return {
        pid: child.pid,
        openConfirmed: opened,
        closedConfirmed: closed,
        waitFailure,
        exitCode: result.code,
      };
    },
  };
}

/** 由独立 PowerShell 进程只读尝试打开剪贴板；基线成功和持锁失败共同证明注入确实有效。 @author kongweiguang */
async function probeNativeClipboard(pwsh, runRoot, phase) {
  const scriptPath = join(runRoot, `clipboard-probe-${phase}.ps1`);
  const source = String.raw`
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class JaCliAcceptanceClipboardProbe {
  [DllImport("user32.dll", SetLastError = true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  public static extern bool OpenClipboard(IntPtr owner);
  [DllImport("user32.dll", SetLastError = true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  public static extern bool CloseClipboard();
  [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "CreateWindowExW", SetLastError = true)]
  private static extern IntPtr CreateWindowEx(uint exStyle, string className, string windowName, uint style,
    int x, int y, int width, int height, IntPtr parent, IntPtr menu, IntPtr instance, IntPtr parameter);
  [DllImport("user32.dll", SetLastError = true)]
  [return: MarshalAs(UnmanagedType.Bool)]
  private static extern bool DestroyWindow(IntPtr window);
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, EntryPoint = "GetModuleHandleW", SetLastError = true)]
  private static extern IntPtr GetModuleHandle(string moduleName);
  private static IntPtr CreateOwner() => CreateWindowEx(0, "STATIC", "Ja clipboard acceptance probe", 0,
    0, 0, 0, 0, new IntPtr(-3), IntPtr.Zero, GetModuleHandle(null), IntPtr.Zero);

  public static int Probe() {
    IntPtr owner = CreateOwner();
    if (owner == IntPtr.Zero) {
      Console.Out.WriteLine("PROBE_WINDOW_ERROR=" + Marshal.GetLastWin32Error());
      return 32;
    }
    try {
      if (!OpenClipboard(owner)) {
        int error = Marshal.GetLastWin32Error();
        Console.Out.WriteLine("PROBE_OPENED=false");
        Console.Out.WriteLine("PROBE_ERROR=" + error);
        return 0;
      }
      Console.Out.WriteLine("PROBE_OPENED=true");
      if (!CloseClipboard()) {
        Console.Out.WriteLine("PROBE_CLOSE_ERROR=" + Marshal.GetLastWin32Error());
        return 31;
      }
      return 0;
    }
    finally {
      if (!DestroyWindow(owner)) Console.Out.WriteLine("PROBE_OWNER_DESTROY_ERROR=" + Marshal.GetLastWin32Error());
    }
  }
}
'@
$exitCode = [JaCliAcceptanceClipboardProbe]::Probe()
exit $exitCode
`;
  await writeFile(scriptPath, source, "utf8");
  const { stdout } = await execFileAsync(
    pwsh,
    ["-NoLogo", "-NoProfile", "-NonInteractive", "-File", scriptPath],
    { cwd: runRoot, env: process.env, windowsHide: true, timeout: 8_000, maxBuffer: 16 * 1024 },
  );
  const lines = stdout.split(/\r?\n/u).map((line) => line.trim()).filter(Boolean);
  const openedLine = lines.find((line) => line.startsWith("PROBE_OPENED="));
  assert.ok(openedLine, `${phase} clipboard probe must report OpenClipboard outcome`);
  const errorLine = lines.find((line) => line.startsWith("PROBE_ERROR="));
  return {
    opened: openedLine === "PROBE_OPENED=true",
    win32Error: errorLine ? Number(errorLine.slice("PROBE_ERROR=".length)) : null,
    closeError: lines.find((line) => line.startsWith("PROBE_CLOSE_ERROR=")) ?? null,
    ownerError: lines.find((line) => line.startsWith("PROBE_WINDOW_ERROR=") || line.startsWith("PROBE_OWNER_DESTROY_ERROR=")) ?? null,
    scriptPath,
  };
}

/** 独立验证隐藏 HWND owner 能真实排斥另一个进程；调锁实现时不启动 CLI 或碰 profile。 @author kongweiguang */
async function runClipboardLockSelfTest() {
  assert.equal(process.platform, "win32", "the native clipboard lock check requires Windows");
  assert.match(process.versions.node, /^24\./u, "the repository E2E runtime requires Node 24");
  await mkdir(evidenceRoot, { recursive: true });
  const runRoot = await mkdtemp(join(evidenceRoot, "clipboard-lock-"));
  const evidencePath = join(runRoot, "clipboard-lock-self-test.json");
  const evidence = { status: "running", runtime: "native clipboard lock helper only; no CLI or profile" };
  let helper;
  let failure;
  try {
    const where = await execFileAsync("where.exe", ["pwsh.exe"], {
      cwd: repoRoot,
      env: process.env,
      windowsHide: true,
      timeout: 5_000,
      maxBuffer: 16 * 1024,
    });
    let pwsh;
    let pwshVersion;
    for (const candidate of where.stdout.split(/\r?\n/u).map((item) => item.trim()).filter(Boolean)) {
      try {
        const version = await execFileAsync(
          candidate,
          ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "[string]$PSVersionTable.PSVersion.Major"],
          { cwd: repoRoot, env: process.env, windowsHide: true, timeout: 5_000, maxBuffer: 16 * 1024 },
        );
        if (version.stdout.trim() === "7") {
          pwsh = candidate;
          pwshVersion = version.stdout.trim();
          break;
        }
      } catch {
        // Continue to the next discovered PowerShell 7 executable.
      }
    }
    assert.ok(pwsh, "clipboard lock self-test requires PowerShell 7 from PATH");
    evidence.pwsh = pwsh;
    evidence.pwshMajorVersion = pwshVersion;
    const baseline = await probeNativeClipboard(pwsh, runRoot, "self-test-unlocked");
    assert.equal(baseline.opened, true, "independent probe must open the clipboard before the helper owns it");
    evidence.unlockedProbe = { opened: baseline.opened, closeError: baseline.closeError, ownerError: baseline.ownerError };

    helper = await createClipboardLockHelper(pwsh, runRoot);
    await helper.ready;
    await delay(100);
    const beforeProbe = helper.status();
    assert.equal(beforeProbe.openConfirmed, true);
    assert.equal(beforeProbe.closeObserved, false);
    assert.equal(beforeProbe.processAlive, true);
    assert.equal(beforeProbe.waitFailure, null);
    const locked = await probeNativeClipboard(pwsh, runRoot, "self-test-locked");
    const afterProbe = helper.status();
    evidence.lockedProbe = { opened: locked.opened, win32Error: locked.win32Error, ownerError: locked.ownerError };
    evidence.ownerDuringProbe = afterProbe;
    assert.equal(locked.opened, false, "independent process must fail OpenClipboard while the helper holds it");
    assert.ok(Number.isInteger(locked.win32Error) && locked.win32Error > 0);
    assert.equal(afterProbe.closeObserved, false);
    assert.equal(afterProbe.processAlive, true);
    assert.equal(afterProbe.waitFailure, null);
    evidence.status = "completed";
  } catch (error) {
    failure = error instanceof Error ? error : new Error(String(error));
    evidence.status = "failed";
    evidence.failure = safeError(failure);
  } finally {
    if (helper) {
      try {
        evidence.cleanup = { clipboardLockReleased: await helper.release() };
      } catch (error) {
        evidence.cleanup = { clipboardLockReleaseError: safeError(error) };
        if (!failure) failure = error instanceof Error ? error : new Error(String(error));
        evidence.status = "failed";
      }
    }
    await writeFile(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  }
  if (failure) {
    process.stderr.write(`Native clipboard lock self-test failed. Evidence: ${runRoot}\n`);
    throw failure;
  }
  process.stdout.write(`${JSON.stringify({ evidenceDirectory: runRoot, ...evidence }, null, 2)}\n`);
}

/** 验证外部进程交接时关闭并恢复 alternate screen 与 bracketed paste，保证失败退出也还给 CLI。 @author kongweiguang */
function assertEditorTerminalRestored(vt, label) {
  assert.ok(vt.includes("\u001b[?1049l"), `${label}: editor handoff restores the shell screen`);
  assert.ok(
    vt.lastIndexOf("\u001b[?1049h") > vt.indexOf("\u001b[?1049l"),
    `${label}: CLI re-enters its alternate screen after the editor exits`,
  );
  assert.ok(
    vt.includes("\u001b[?2004l") && vt.includes("\u001b[?2004h"),
    `${label}: bracketed-paste mode is released and reacquired around the editor`,
  );
}

/** 通过受控 `code.cmd` shim 写回当前 draft 并记录 argv；路径包含中文和空格以验证 Windows 参数边界。 @author kongweiguang */
async function createEditorShim(runRoot) {
  const bin = join(runRoot, "假编辑器 路径");
  const temp = join(runRoot, "临时 编辑参数");
  const powershell = join(runRoot, "fake-code-editor.ps1");
  const modeFile = join(runRoot, "fake-code-editor.mode");
  const shimLog = join(runRoot, "fake-code-editor.json");
  await Promise.all([mkdir(bin, { recursive: true }), mkdir(temp, { recursive: true })]);
  const where = await execFileAsync("where.exe", ["pwsh.exe"], {
    cwd: repoRoot,
    env: process.env,
    windowsHide: true,
    timeout: 5_000,
    maxBuffer: 16 * 1024,
  });
  const pwshCandidates = where.stdout.split(/\r?\n/u).map((candidate) => candidate.trim()).filter(Boolean);
  let pwsh;
  let pwshVersion;
  for (const candidate of pwshCandidates) {
    try {
      const version = await execFileAsync(candidate, [
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "$PSVersionTable.PSVersion.Major",
      ], { cwd: repoRoot, env: process.env, windowsHide: true, timeout: 5_000, maxBuffer: 16 * 1024 });
      if (version.stdout.trim() === "7") {
        pwsh = candidate;
        pwshVersion = version.stdout.trim();
        break;
      }
    } catch {
      // A stale PATH candidate must not hide a later usable PowerShell 7 installation.
    }
  }
  assert.ok(pwsh, "PowerShell 7 must be discoverable through the inherited PATH");
  const psScript = String.raw`
$ErrorActionPreference = 'Stop'
$arguments = @($args)
if ($arguments.Count -lt 1) { exit 21 }
$target = [string]$arguments[$arguments.Count - 1]
$mode = [System.IO.File]::ReadAllText($env:JA_TEST_EDITOR_MODE_FILE).Trim()
$record = [ordered]@{
  argv = $arguments
  target = $target
  targetExists = [System.IO.File]::Exists($target)
  temp = $env:TEMP
  mode = $mode
}
[System.IO.File]::WriteAllText($env:JA_TEST_EDITOR_LOG, (ConvertTo-Json -InputObject $record -Compress -Depth 4), [System.Text.UTF8Encoding]::new($false))
if (-not $record.targetExists) { exit 22 }
if ($mode -eq 'fail') { exit 23 }
[System.IO.File]::AppendAllText($target, [Environment]::NewLine + '外部编辑器写回：中文🙂', [System.Text.UTF8Encoding]::new($false))
exit 0
`;
  await writeFile(powershell, psScript, "utf8");
  const cmd = [
    "@echo off",
    "chcp 65001 >nul",
    '"' + pwsh + '" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + powershell + '" %*',
    "exit /b %ERRORLEVEL%",
    "",
  ].join("\r\n");
  await writeFile(join(bin, "code.cmd"), cmd, "utf8");
  await writeFile(modeFile, "success\n", "utf8");
  return { bin, temp, powershell, pwsh, pwshVersion, modeFile, shimLog };
}

/** 暂时替换仅传给本次 ConPTY 子进程的编辑器搜索路径和 TEMP，并始终恢复 Node 的宿主环境。 @author kongweiguang */
function withEditorEnvironment(editor) {
  const keys = ["EDITOR", "VISUAL", "TEMP", "TMP", "JA_TEST_EDITOR_LOG", "JA_TEST_EDITOR_MODE_FILE", "PATH"];
  const original = Object.fromEntries(keys.map((key) => [key, process.env[key]]));
  process.env.EDITOR = "code";
  delete process.env.VISUAL;
  process.env.TEMP = editor.temp;
  process.env.TMP = editor.temp;
  process.env.JA_TEST_EDITOR_LOG = editor.shimLog;
  process.env.JA_TEST_EDITOR_MODE_FILE = editor.modeFile;
  process.env.PATH = `${editor.bin}${delimiter}${process.env.PATH ?? ""}`;
  return () => {
    for (const key of keys) {
      if (original[key] === undefined) delete process.env[key];
      else process.env[key] = original[key];
    }
  };
}

/** CopyState 已终态后仅作有界文本回读；绝不以读取轮询重发复制动作。 @author kongweiguang */
async function readClipboardUntil(guard, expected) {
  const deadline = Date.now() + 5_000;
  let actual = "";
  while (Date.now() < deadline) {
    actual = await guard.readText();
    if (actual === expected) return actual;
    await delay(100);
  }
  throw new Error(`clipboard text did not match the persisted seed answer (received ${actual.length} characters)`);
}

/** 原生格式快照失败时只重试纯读取的临时物化错误；不清空或替换剪贴板，也不接受不完整快照。 @author kongweiguang */
async function captureClipboardSnapshot(guard, maxAttempts = 3) {
  const failures = [];
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      return { snapshot: await guard.begin(), attempts: attempt, failures };
    } catch (error) {
      const message = safeError(error);
      failures.push({ attempt, error: message });
      if (!/clipboard begin failed: RuntimeException format=/u.test(message) || attempt === maxAttempts) {
        throw error;
      }
      await delay(180);
    }
  }
  throw new Error("clipboard snapshot did not complete after bounded read-only retries");
}

/** 按 ConPTY 新输出中的 LastReply 通知跟踪真实复制状态；观察窗结束只报告阶段，不合成产品超时或重试。 @author kongweiguang */
async function observeLastReplyCopyState(cli, vtLog, outputWatermark, timeoutMs = 15_000) {
  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  const states = [
    { state: "copied", notice: "最后一条完整回复已复制。" },
    { state: "failed", notice: "完整回复复制失败：" },
    { state: "read_failed", notice: "完整回复读取失败" },
    { state: "prepare_timed_out", notice: "完整回复复制准备超时。" },
    { state: "unavailable", notice: "无法复制完整回复" },
  ];
  const events = [];
  let deliveryAtMs = null;
  let lastObservedState = null;
  let latest = { outputBytes: outputWatermark, visible: false, notice: null };

  while (Date.now() < deadline) {
    await cli.pullVt();
    const output = await readFile(vtLog);
    const outputBytes = output.length;
    const freshOutput = outputBytes > outputWatermark;
    const appended = freshOutput ? output.subarray(outputWatermark).toString("utf8") : "";
    const visible = cli.screen.text();
    const observedStates = [];

    if (freshOutput) {
      const delivery = { state: "delivering", notice: "正在写入系统剪贴板…" };
      const deliveryIndex = appended.indexOf(delivery.notice);
      if (deliveryIndex >= 0) observedStates.push({ ...delivery, index: deliveryIndex });
      for (const terminal of states) {
        const index = appended.indexOf(terminal.notice);
        if (index >= 0) observedStates.push({ ...terminal, index });
      }
      observedStates.sort((left, right) => left.index - right.index);
    }

    let terminalResult;
    for (const observed of observedStates) {
      if (observed.state === lastObservedState) continue;
      const observedAtMs = Date.now() - startedAt;
      if (observed.state === "delivering" && deliveryAtMs === null) deliveryAtMs = observedAtMs;
      const event = {
        state: observed.state,
        notice: observed.notice,
        elapsedMs: observedAtMs,
        outputBytes,
        visible: visible.includes(observed.notice),
      };
      events.push(event);
      lastObservedState = observed.state;
      latest = { outputBytes, visible: event.visible, notice: observed.notice };
      if (states.some((terminal) => terminal.state === observed.state)) {
        terminalResult = {
          state: observed.state,
          notice: observed.notice,
          elapsedMs: observedAtMs,
          deliveringObserved: deliveryAtMs !== null,
          deliveryToTerminalMs: deliveryAtMs === null ? null : observedAtMs - deliveryAtMs,
          events,
          outputWatermark,
          outputBytes,
          finalVisible: latest.visible,
        };
      }
    }
    if (terminalResult) {
      return terminalResult;
    }
    if (observedStates.length === 0) {
      latest = {
        outputBytes,
        visible: lastObservedState ? visible.includes(latest.notice ?? "") : false,
        notice: latest.notice,
      };
    }

    if (cli.child.exitCode !== null) {
      return {
        state: "cli_exited",
        notice: latest.notice,
        elapsedMs: Date.now() - startedAt,
        deliveringObserved: deliveryAtMs !== null,
        deliveryToTerminalMs: null,
        events,
        outputWatermark,
        outputBytes,
        finalVisible: latest.visible,
        childExitCode: cli.child.exitCode,
      };
    }
    await delay(50);
  }

  const elapsedMs = Date.now() - startedAt;
  return {
    state: deliveryAtMs === null ? "no_terminal_state" : "still_delivering",
    notice: latest.notice,
    elapsedMs,
    deliveringObserved: deliveryAtMs !== null,
    deliveryToTerminalMs: null,
    events,
    outputWatermark,
    outputBytes: latest.outputBytes,
    finalVisible: latest.visible,
    observationWindowMs: timeoutMs,
  };
}

/** 仅在所有复制入口完成后调用一次，恢复原始剪贴板并比较全部原生格式集合。 @author kongweiguang */
async function restoreClipboardAndVerify(guard, expectedFormats, expectedHadData) {
  const restored = await guard.restore();
  assert.equal(restored.restored, true, "original native clipboard data must be restored after acceptance");
  const roundTrip = await guard.begin();
  assert.equal(roundTrip.ready, true, "clipboard guard must capture the restored native data again");
  assert.deepEqual([...roundTrip.formats].sort(), expectedFormats, "all original native clipboard formats survive restoration");
  assert.equal(roundTrip.hadData, expectedHadData, "the original clipboard empty/non-empty state survives restoration");
  return roundTrip;
}

/** 串接隔离 seed 的输入与恢复边界；复制就绪同时核对 C 与 Ctrl+Insert，失败保留证据且不调用真实 Provider。 @author kongweiguang */
async function runAcceptance() {
  assert.equal(process.platform, "win32", "this acceptance requires Windows ConPTY");
  await mkdir(evidenceRoot, { recursive: true });
  const runRoot = await mkdtemp(join(evidenceRoot, "run-"));
  const vtLog = join(runRoot, "conpty.vt");
  const evidencePath = join(runRoot, "acceptance.json");
  const { seed, diagnostic } = await loadSeed();
  const restoreJdk = usePinnedJdk(seed.javaHome);
  const evidence = {
    status: "running",
    runtime: "existing seed Thread + real Windows ConPTY; no real Provider call",
    seed: {
      seedRoot,
      threadId: seed.threadId,
      profileRoot: seed.profileRoot,
      workspace: seed.directories.workspace,
      jarSnapshot: seed.jarSnapshot,
      jarSnapshotSha256: seed.jarSnapshotSha256,
      finalAnswerCharacters: seed.finalAnswerCharacters,
      finalAnswerSha256: seed.finalAnswerSha256,
      userPromptCharacters: Array.from(seed.userPrompt).length,
      userPromptSha256: createHash("sha256").update(seed.userPrompt).digest("hex").toUpperCase(),
    },
    transcript: {},
    checks: {},
  };
  let cli;
  let clipboardGuard;
  let clipboardLock;
  let clipboardSnapshot;
  let clipboardCaptured = false;
  let processEnvironmentRestore;
  let failure;
  let artifacts;
  let directories;
  let before;
  try {
    const versions = await verifyJdk25(seed.javaHome, seed.java);
    evidence.runtimeVersions = versions;
    const previousCli = process.env.JA_TEST_CLI;
    const previousJar = process.env.JA_TEST_JAR;
    process.env.JA_TEST_CLI = seed.cliPath;
    process.env.JA_TEST_JAR = seed.jarSnapshot;
    try {
      artifacts = await resolveArtifacts();
    } finally {
      if (previousCli === undefined) delete process.env.JA_TEST_CLI;
      else process.env.JA_TEST_CLI = previousCli;
      if (previousJar === undefined) delete process.env.JA_TEST_JAR;
      else process.env.JA_TEST_JAR = previousJar;
    }
    assert.equal(artifacts.cli, seed.cliPath, "the artifact resolver must honor the isolated JA_TEST_CLI path");
    assert.equal(artifacts.jarSha256, seed.jarSnapshotSha256.toUpperCase());
    evidence.artifacts = {
      cli: artifacts.cli,
      cliSha256: artifacts.cliSha256,
      cliSource: "JA_TEST_CLI",
      java: artifacts.java,
      appServerJar: artifacts.jar,
      appServerJarSha256: artifacts.jarSha256,
      javaVersion: artifacts.javaVersion,
    };
    directories = {
      home: seed.directories.home,
      data: seed.directories.data,
      run: seed.directories.run,
      workspace: seed.directories.workspace,
      logs: seed.directories.logs,
    };
    before = await readThreadEvidence(
      artifacts,
      directories,
      seed.threadId,
      diagnostic.finalMessageId,
      seed.userPrompt,
    );
    assert.equal(before.finalAnswerSha256, seed.finalAnswerSha256.toUpperCase());
    assert.equal(
      before.userPromptSha256,
      createHash("sha256").update(seed.userPrompt).digest("hex").toUpperCase(),
    );
    assert.equal(before.providerLoopbackOnly, true);
    assert.equal(before.providerPortClosed, true);
    evidence.threadBefore = before;
    const editor = await createEditorShim(runRoot);
    const harness = await compileConPtyHarness(artifacts, runRoot);
    clipboardGuard = await startClipboardGuard();
    const capturedClipboard = await captureClipboardSnapshot(clipboardGuard);
    clipboardSnapshot = capturedClipboard.snapshot;
    clipboardCaptured = clipboardSnapshot.ready === true;
    assert.equal(clipboardCaptured, true, "the original native clipboard formats must be backed up before testing copy");
    evidence.clipboardSnapshotCapture = {
      attempts: capturedClipboard.attempts,
      transientFormatReadFailures: capturedClipboard.failures,
    };
    evidence.clipboardBefore = {
      hadData: clipboardSnapshot.hadData,
      formatCount: clipboardSnapshot.formats.length,
    };
    processEnvironmentRestore = withEditorEnvironment(editor);
    try {
      cli = startConPtyCli(
        artifacts,
        directories,
        harness,
        seed.threadId,
        undefined,
        vtLog,
      );
    } finally {
      processEnvironmentRestore();
      processEnvironmentRestore = undefined;
    }
    await cli.harnessReady;
    await cli.resize(120, 32);
    const seeded = await waitVisible(
      cli,
      (visible) => visible.includes("English wraps") && visible.includes("ja.dev"),
      "persisted Markdown reply in the live TUI",
    );
    await cli.pullVt();
    assert.equal(cli.screen.alternate, true, "the interactive CLI must own the alternate screen");
    let vt = await readFile(vtLog, "utf8");
    assert.ok(vt.includes("\u001b[?1049h"), "ConPTY captured alternate-screen entry");
    assert.ok(vt.includes("\u001b[?2004h"), "ConPTY captured bracketed-paste entry");
    evidence.checks.seededResume = {
      answerVisible: true,
      alternateScreen: cli.screen.alternate,
      bracketedPaste: true,
      visibleAnswerRows: seeded.lines.filter((line) => line.includes("English") || line.includes("Thread")).length,
    };
    evidence.transcript.initial = await writeScreenEvidence(runRoot, "initial-resume", cli.screen);

    const shortCases = [];
    for (const [name, payload, marker] of [
      ["ab-enter", "ab\r", "ab"],
      ["one-char-enter", "Q\r", "Q"],
      ["four-char-enter", "abcd\r", "abcd"],
    ]) {
      const start = cli.messages.length;
      cli.sendKeys(payload);
      await cli.waitForMessage("SENT_BYTES=", start, 3_000);
      await delay(220);
      await cli.pullVt();
      const visible = cli.screen.text();
      const composer = composerTail(cli);
      const sent = /正在工作|正在发送/u.test(visible);
      if (sent) {
        cli.sendKeys("\u0003");
        await delay(250);
        await cli.pullVt();
      }
      assert.equal(sent, false, `${name}: a short burst followed by Enter must remain a draft, not start a turn`);
      assert.ok(composer.includes(marker), `${name}: buffered short text remains in the Composer`);
      cli.sendKeys("\u0003");
      await cli.waitForScreen(
        (_text, lines) => !lines.slice(-8).join("\n").includes(marker),
        `${name} Composer draft clear`,
      );
      shortCases.push({ name, payloadCharacters: Array.from(marker).length, remainsDraft: true, workingStateShown: false });
    }
    evidence.checks.shortEnterBursts = shortCases;

    const tabStart = cli.messages.length;
    cli.sendKeys("ab\t");
    await cli.waitForMessage("SENT_BYTES=", tabStart, 3_000);
    await delay(220);
    await cli.pullVt();
    const tabScreen = cli.screen.text();
    assert.ok(composerTail(cli).includes("ab"), "fast `ab<Tab>` remains visible in the Composer");
    assert.doesNotMatch(tabScreen, /正在工作|正在发送/u, "fast `ab<Tab>` must not submit a turn");
    evidence.checks.shortTabBurst = { remainsDraft: true, workingStateShown: false };
    await clearComposer(cli, "ab");

    const multilinePaste = "粘贴中文🙂\n第二行";
    const pasteStart = cli.messages.length;
    cli.sendKeys(`\u001b[200~${multilinePaste}\u001b[201~`);
    await cli.waitForMessage("SENT_BYTES=", pasteStart, 3_000);
    const pasted = await waitVisible(
      cli,
      (visible) => visible.includes("粘贴中文") && visible.includes("第二行"),
      "bracketed multiline CJK and emoji paste",
    );
    assert.doesNotMatch(pasted.text, /正在工作|正在发送/u);
    evidence.checks.bracketedPaste = { multilineTextVisible: true, noTurnStarted: true };
    await clearComposer(cli, "粘贴中文");

    const ctrlJBase = "ctrlj-base";
    await sendNativeText(cli, ctrlJBase, 20);
    await waitVisible(cli, (visible) => visible.includes(ctrlJBase), "Ctrl+J probe prefix");
    cli.sendKeys("\u001b[106;5u");
    await delay(160);
    await sendNativeText(cli, "ctrlj-after", 20);
    await delay(100);
    await cli.pullVt();
    const ctrlJRows = cli.screen.lines();
    const baseRow = ctrlJRows.findIndex((line) => line.includes(ctrlJBase));
    const afterRow = ctrlJRows.findIndex((line) => line.includes("ctrlj-after"));
    const ctrlJSupported = baseRow >= 0 && afterRow > baseRow;
    assert.doesNotMatch(cli.screen.text(), /正在工作|正在发送/u, "Ctrl+J probe cannot start a turn");
    evidence.checks.ctrlJ = {
      inputEncoding: "CSI-u Ctrl+J probe through ConPTY",
      supportedByConPty: ctrlJSupported,
      insertedNewline: ctrlJSupported,
    };
    await clearComposer(cli, ctrlJBase);

    const shiftEnterBase = "shiftenter-base";
    await sendNativeText(cli, shiftEnterBase, 20);
    await waitVisible(cli, (visible) => visible.includes(shiftEnterBase), "CSI-u Shift+Enter probe prefix");
    cli.sendKeys("\u001b[13;2u");
    await delay(140);
    await sendNativeText(cli, "shiftenter-after", 20);
    await delay(100);
    await cli.pullVt();
    const shiftRows = cli.screen.lines();
    const shiftBaseRow = shiftRows.findIndex((line) => line.includes(shiftEnterBase));
    const shiftAfterRow = shiftRows.findIndex((line) => line.includes("shiftenter-after"));
    const shiftEnterSupported = shiftBaseRow >= 0 && shiftAfterRow > shiftBaseRow;
    assert.doesNotMatch(cli.screen.text(), /正在工作|正在发送/u, "Shift+Enter probe cannot start a turn");
    evidence.checks.csiUShiftEnter = {
      inputEncoding: "CSI-u Shift+Enter through ConPTY",
      supportedByConPty: shiftEnterSupported,
      insertedNewline: shiftEnterSupported,
    };
    await clearComposer(cli, shiftEnterBase);

    const historyDraft = "history-draft🙂";
    await sendNativeText(cli, historyDraft, 18);
    await waitVisible(cli, (visible) => visible.includes(historyDraft), "draft before Ctrl+R history search");
    cli.sendKeys("\u0012");
    const historyPanel = await waitVisible(cli, (visible) => visible.includes("搜索输入历史"), "Ctrl+R input history picker");
    await sendNativeText(cli, "CLI", 18);
    await delay(220);
    cli.sendKeys("\u001b");
    const historyReturned = await waitVisible(cli, (visible) => visible.includes(historyDraft), "history cancellation returns to draft");
    assert.ok(historyPanel.text.includes("搜索输入历史"));
    assert.doesNotMatch(historyReturned.text, /正在工作|正在发送/u);
    evidence.checks.ctrlRHistoryCancel = { pickerOpened: true, draftPreserved: true, noTurnStarted: true };
    await clearComposer(cli, historyDraft);

    const anchorBeforeDetails = await waitVisible(
      cli,
      (visible) => visible.includes("复现截图中") && visible.includes("English wraps"),
      "seeded transcript anchor before opening message detail",
    );
    const anchorLineBefore = anchorBeforeDetails.lines.find((line) => line.includes("复现截图中"));
    const answer = diagnostic.readCompleteItemContent.content;
    const userPrompt = seed.userPrompt;
    const expectedFormats = [...clipboardSnapshot.formats].sort();
    const unlockedClipboardProbe = await probeNativeClipboard(editor.pwsh, runRoot, "unlocked");
    assert.equal(unlockedClipboardProbe.opened, true, "independent clipboard probe must open before lock injection");
    evidence.checks.clipboardProbeBaseline = {
      opened: unlockedClipboardProbe.opened,
      closeError: unlockedClipboardProbe.closeError,
    };
    await cli.pullVt();
    const detailOutputWatermark = (await stat(vtLog)).size;
    const detailInputStart = cli.messages.length;
    cli.sendKeys("\u001bOQ");
    await cli.waitForMessage("SENT_BYTES=", detailInputStart, 3_000);
    const detail = await waitForFreshStableScreen(
      cli,
      vtLog,
      detailOutputWatermark,
      (visible) =>
        visible.includes("你的消息 · 完整正文") &&
        visible.includes("复现截图中") &&
        visible.includes("C 复制全文") && visible.includes("Ctrl+Insert") &&
        !visible.includes("读取全文中") &&
        !visible.includes("消息全文尚未确认读取，暂不能复制"),
      "F2 opens the anchored persisted user-message details",
    );
    clipboardLock = await createClipboardLockHelper(editor.pwsh, runRoot);
    await clipboardLock.ready;
    const clipboardLockPid = clipboardLock.pid;
    const clipboardLockScriptPath = clipboardLock.scriptPath;
    await delay(100);
    const lockBeforeProbe = clipboardLock.status();
    assert.equal(lockBeforeProbe.openConfirmed, true);
    assert.equal(lockBeforeProbe.closeObserved, false, "the lock owner must not close before the independent probe");
    assert.equal(lockBeforeProbe.processAlive, true, "the lock owner process must still be running before the probe");
    assert.equal(lockBeforeProbe.waitFailure, null, "the same native thread must remain in its bounded release wait");
    const lockedClipboardProbe = await probeNativeClipboard(editor.pwsh, runRoot, "locked");
    const lockDuringProbe = clipboardLock.status();
    assert.equal(lockedClipboardProbe.opened, false, "an independent process must not open the held native clipboard");
    assert.ok(Number.isInteger(lockedClipboardProbe.win32Error) && lockedClipboardProbe.win32Error > 0);
    assert.equal(lockDuringProbe.closeObserved, false, "the owner must retain the lock until an explicit release");
    assert.equal(lockDuringProbe.processAlive, true, "the owner must remain alive after the contention probe");
    assert.equal(lockDuringProbe.waitFailure, null, "the owner must not lose its release pipe during the probe");
    evidence.checks.clipboardLockProof = {
      helperPid: clipboardLockPid,
      helperProcessAlive: lockDuringProbe.processAlive,
      openClipboardConfirmed: lockDuringProbe.openConfirmed,
      closeNotObservedBeforeCopy: !lockDuringProbe.closeObserved,
      independentProbeOpened: lockedClipboardProbe.opened,
      independentProbeWin32Error: lockedClipboardProbe.win32Error,
      waitFailure: lockDuringProbe.waitFailure,
    };
    cli.sendKeys("\u001b[2;5~");
    const copyFailure = await waitVisible(
      cli,
      (visible) => visible.includes("完整消息详情复制失败"),
      "locked native clipboard reports a visible message-detail copy failure",
      8_000,
    );
    assert.ok(copyFailure.text.includes("复现截图中"), "copy failure must leave the user-message detail visible");
    evidence.transcript.clipboardFailure = await writeScreenEvidence(runRoot, "clipboard-failure", cli.screen);
    const clipboardLockRelease = await clipboardLock.release();
    clipboardLock = undefined;
    cli.sendKeys("\u001b[2;5~");
    await readClipboardUntil(clipboardGuard, userPrompt);
    evidence.checks.clipboardFailureRecovery = {
      helperPid: clipboardLockPid,
      helperScriptPath: clipboardLockScriptPath,
      openClipboardConfirmed: clipboardLockRelease.openConfirmed,
      closeClipboardConfirmed: clipboardLockRelease.closedConfirmed,
      failureWasVisible: true,
      detailRemainedOpenAndRetryable: true,
      manualCtrlInsertRetryCopiedExactUserMessage: true,
      originalClipboardRestoreDeferredUntilCleanup: true,
    };
    await cli.pullVt();
    const f5OutputWatermark = (await stat(vtLog)).size;
    const f5InputStart = cli.messages.length;
    cli.sendKeys("\u001b[15~");
    await cli.waitForMessage("SENT_BYTES=", f5InputStart, 3_000);
    const refreshedDetail = await waitForFreshStableScreen(
      cli,
      vtLog,
      f5OutputWatermark,
      (visible) =>
        visible.includes("你的消息 · 完整正文") &&
        visible.includes("复现截图中") &&
        visible.includes("C 复制全文") && visible.includes("Ctrl+Insert") &&
        !visible.includes("读取全文中") &&
        !visible.includes("消息全文尚未确认读取，暂不能复制"),
      "F5 retries the open message details",
    );
    const f5ReadyBoundary = {
      outputBytesBefore: refreshedDetail.outputWatermark,
      outputBytesAfter: refreshedDetail.outputBytes,
      stableForMs: refreshedDetail.stableForMs,
      copyReadyFooterVisible: true,
    };
    const detailCopyInputStart = cli.messages.length;
    const detailCopyOutputWatermark = (await stat(vtLog)).size;
    cli.sendKeys("\u001b[2;5~");
    await cli.waitForMessage("SENT_BYTES=", detailCopyInputStart, 3_000);
    const refreshedCopyReceipt = await waitForFreshStableScreen(
      cli,
      vtLog,
      detailCopyOutputWatermark,
      (visible) =>
        visible.includes("你的消息 · 完整正文") &&
        visible.includes("复现截图中") &&
        visible.includes("完整消息详情已复制。"),
      "F5 copy retry emits a new success receipt",
    );
    await readClipboardUntil(clipboardGuard, userPrompt);
    evidence.checks.messageDetailsCopy = {
      ctrlInsertCopiedExactPersistedUserMessage: true,
      copyReceiptFreshAfterInput: refreshedCopyReceipt.outputBytes > detailCopyOutputWatermark,
      f5ReadyBoundary,
      originalClipboardRestoreDeferredUntilCleanup: true,
      originalFormatCount: expectedFormats.length,
    };
    cli.sendKeys("\u001b");
    const anchorAfterDetails = await waitVisible(
      cli,
      (visible) => visible.includes("复现截图中") && visible.includes("English wraps"),
      "Esc closes message details and restores the transcript anchor",
    );
    assert.equal(anchorAfterDetails.lines.find((line) => line.includes("复现截图中")), anchorLineBefore);
    assert.ok(detail.text.includes("复现截图中"));
    assert.ok(refreshedDetail.text.includes("复现截图中"));
    evidence.checks.messageDetails = {
      f2Opened: true,
      f5Reopened: true,
      escRestoredAnchor: true,
      anchoredItemKind: "user_input",
      viewportBeforeDetails: "120x32",
    };

    await sendNativeText(cli, "/cop", 20);
    await waitVisible(cli, (visible) => visible.includes("/copy"), "slash command autocomplete");
    cli.sendKeys("\t");
    const completedCopy = await waitVisible(cli, (visible) => visible.includes("/copy"), "Tab completes copy command without sending");
    assert.doesNotMatch(completedCopy.text, /正在工作|正在发送/u);
    assert.ok(completedCopy.text.includes("/copy"), "Tab leaves the completed local command in the Composer");
    await cli.pullVt();
    const commandCopyOutputWatermark = (await stat(vtLog)).size;
    const commandCopyInputStart = cli.messages.length;
    cli.sendKeys("\r");
    await cli.waitForMessage("SENT_BYTES=", commandCopyInputStart, 3_000);
    const commandCopyState = await observeLastReplyCopyState(cli, vtLog, commandCopyOutputWatermark, 15_000);
    evidence.checks.copyCommand = {
      slashCopyExecutedLocally: true,
      state: commandCopyState.state,
      terminalNotice: commandCopyState.notice,
      stateEvents: commandCopyState.events,
      elapsedMs: commandCopyState.elapsedMs,
      deliveringObserved: commandCopyState.deliveringObserved,
      deliveryToTerminalMs: commandCopyState.deliveryToTerminalMs,
      outputWatermark: commandCopyState.outputWatermark,
      outputBytes: commandCopyState.outputBytes,
      finalStateVisible: commandCopyState.finalVisible,
      observationWindowMs: commandCopyState.observationWindowMs ?? 15_000,
      exactPersistedAnswerReadback: false,
      originalClipboardRestoreDeferredUntilCleanup: true,
      originalFormatCount: expectedFormats.length,
    };
    assert.equal(
      commandCopyState.state,
      "copied",
      commandCopyState.state === "still_delivering"
        ? "/copy remained in the observed Delivering phase at the 15-second observation boundary; no product timeout was inferred and no retry was sent"
        : `slash copy ended in ${commandCopyState.state}: ${commandCopyState.notice ?? "no terminal CopyState notice observed"}`,
    );
    await readClipboardUntil(clipboardGuard, answer);
    evidence.checks.copyCommand.exactPersistedAnswerReadback = true;

    const draftBeforeEditor = "编辑器参数往返🙂";
    await sendNativeText(cli, draftBeforeEditor, 18);
    await waitVisible(cli, (visible) => visible.includes(draftBeforeEditor), "draft before code.cmd editor handoff");
    await cli.pullVt();
    await writeFile(editor.modeFile, "fail\n", "utf8");
    const failedHandoffStart = (await readFile(vtLog, "utf8")).length;
    cli.sendKeys("\u0007");
    const editorMarker = "外部编辑器写回：中文🙂";
    const failedEditorReturn = await waitVisible(
      cli,
      (visible) => visible.includes(draftBeforeEditor) && visible.includes("编辑器已取消或退出异常，原草稿已保留"),
      "nonzero fake editor exit preserves the draft and returns control to the CLI",
      20_000,
    );
    const failedEditorRecord = JSON.parse(await readFile(editor.shimLog, "utf8"));
    assert.equal(failedEditorRecord.mode, "fail", "the target mode file selects the controlled nonzero exit");
    assert.equal(failedEditorRecord.targetExists, true);
    assert.ok(composerTail(cli).includes(draftBeforeEditor), "the exact original draft remains in the Composer after failure");
    assert.equal(cli.screen.alternate, true, "CLI reacquires its screen after a nonzero editor exit");
    evidence.transcript.editorFailure = await writeScreenEvidence(runRoot, "editor-failure", cli.screen);
    vt = await readFile(vtLog, "utf8");
    assertEditorTerminalRestored(vt.slice(failedHandoffStart), "failed editor");
    await sendNativeText(cli, "继续", 18);
    await cli.waitForScreen(
      (_visible, lines) => lines.slice(-8).join("\n").includes(draftBeforeEditor) && lines.slice(-8).join("\n").includes("继续"),
      "Composer accepts more text after an editor failure",
    );
    assert.doesNotMatch(cli.screen.text(), /正在工作|正在发送/u);
    cli.sendKeys("\u0003");
    await cli.waitForScreen(
      (_visible, lines) => !lines.slice(-8).join("\n").includes(draftBeforeEditor),
      "clear draft after the continued-input check",
    );
    await sendNativeText(cli, draftBeforeEditor, 18);
    await waitVisible(cli, (visible) => visible.includes(draftBeforeEditor), "restore original draft before editor success retry");
    await writeFile(editor.modeFile, "success\n", "utf8");
    await cli.pullVt();
    const editorFailureNotice = "编辑器已取消或退出异常，原草稿已保留。";
    const successfulEditorOutputWatermark = (await stat(vtLog)).size;
    const successfulHandoffStart = (await readFile(vtLog, "utf8")).length;
    cli.sendKeys("\u0007");
    const editorReturn = await waitForFreshStableScreen(
      cli,
      vtLog,
      successfulEditorOutputWatermark,
      (visible) =>
        visible.includes(draftBeforeEditor) &&
        visible.includes("外部编辑器写回") &&
        !visible.includes(editorFailureNotice),
      "fake code.cmd success writes a fresh Composer frame and clears the previous editor failure notice",
      20_000,
    );
    const editorRecord = JSON.parse(await readFile(editor.shimLog, "utf8"));
    assert.equal(editorRecord.mode, "success", "the second target mode enables the controlled successful roundtrip");
    assert.equal(editorRecord.targetExists, true, "fake editor receives one existing temporary draft path");
    assert.ok(editorRecord.target.includes("临时 编辑参数"), "temporary argv path includes Unicode and spaces");
    assert.ok(editorRecord.argv.includes("--wait"), "Ja supplies the VS Code wait flag");
    assert.equal(cli.screen.alternate, true, "CLI reacquires alternate screen after editor return");
    vt = await readFile(vtLog, "utf8");
    assertEditorTerminalRestored(vt.slice(successfulHandoffStart), "successful editor");
    evidence.checks.externalEditor = {
      nonzeroExitRetainedDraft: failedEditorReturn.text.includes(draftBeforeEditor),
      originalDraftStillEditableAfterFailure: true,
      additionalInputAcceptedAfterFailure: true,
      terminalRestoredAfterFailure: true,
      argvPathWithChineseSpaces: true,
      waitFlag: true,
      powershell7: editor.pwsh,
      powershell7MajorVersion: editor.pwshVersion,
      draftRoundTrip: editorReturn.text.includes(draftBeforeEditor) && editorReturn.text.includes(editorMarker),
      successFrameOutputBytesBefore: successfulEditorOutputWatermark,
      successFrameOutputBytesAfter: editorReturn.outputBytes,
      successFrameStableForMs: editorReturn.stableForMs,
      staleFailureNoticeClearedAfterSuccess: !editorReturn.text.includes(editorFailureNotice),
      terminalRestoredAfterSuccess: cli.screen.alternate,
      bracketedPasteModesRestoredAfterSuccess: true,
    };

    const resizeObservations = [];
    for (const [columns, rows] of [[120, 32], [80, 24], [40, 10], [120, 32]]) {
      await cli.resize(columns, rows);
      await delay(150);
      await cli.pullVt();
      assert.equal(cli.screen.columns, columns, `ConPTY reports ${columns} columns`);
      assert.equal(cli.screen.rowCount, rows, `ConPTY reports ${rows} rows`);
      assert.ok(cli.screen.text().includes(editorMarker), `${columns}x${rows} keeps the returned draft visible`);
      resizeObservations.push({ size: `${columns}x${rows}`, draftVisible: true });
    }
    evidence.checks.resize = resizeObservations;
    evidence.transcript.afterInteractions = await writeScreenEvidence(runRoot, "after-interactions", cli.screen);

    await clearComposer(cli, editorMarker);
    const exit = await cli.close();
    cli = undefined;
    assert.equal(exit.forced, false, "CLI closes through its normal terminal path");
    evidence.checks.terminalExit = { forced: false, cliExitCode: exit.childExitCode };
    const finalVt = await readFile(vtLog, "utf8");
    assert.ok(finalVt.includes("\u001b[?1049l"), "normal exit restores the primary screen");
    assert.ok(finalVt.includes("\u001b[?2004l"), "normal exit disables bracketed paste");

    evidence.status = "completed";
  } catch (error) {
    failure = error instanceof Error ? error : new Error(String(error));
    evidence.status = "failed";
    evidence.failure = safeError(failure);
    if (cli) {
      try {
        await cli.pullVt();
        evidence.transcript.failure = await writeScreenEvidence(runRoot, "failure", cli.screen);
      } catch (snapshotError) {
        evidence.failureSnapshotError = safeError(snapshotError);
      }
    }
  } finally {
    if (processEnvironmentRestore) processEnvironmentRestore();
    if (clipboardLock) {
      try {
        evidence.cleanup = {
          ...evidence.cleanup,
          clipboardLockReleased: await clipboardLock.release(),
        };
      } catch (error) {
        evidence.cleanup = { ...evidence.cleanup, clipboardLockReleaseError: safeError(error) };
        if (!failure) failure = error instanceof Error ? error : new Error(String(error));
        evidence.status = "failed";
      }
    }
    if (cli) {
      evidence.cliStderr = cli.stderr().slice(-3_000).replaceAll(placeholderSecret, "<redacted>");
      try {
        evidence.cleanup = { ...evidence.cleanup, ...(await cli.close()) };
        cli = undefined;
      } catch (error) {
        evidence.cleanup = { ...evidence.cleanup, cliCloseError: safeError(error) };
        if (!failure) failure = error instanceof Error ? error : new Error(String(error));
        evidence.status = "failed";
      }
    }
    if (before && artifacts && directories) {
      if (cli) {
        evidence.threadAfterSkipped = "CLI shutdown was not confirmed; App Server history was not queried concurrently";
      } else {
        try {
          const after = await readThreadEvidence(
            artifacts,
            directories,
            seed.threadId,
            diagnostic.finalMessageId,
            seed.userPrompt,
          );
          evidence.threadAfter = after;
          assert.deepEqual(after.userItemIds, before.userItemIds, "no short input test may persist a new user message");
          assert.deepEqual(after.turnIds, before.turnIds, "no short input test may create a new Turn");
          assert.equal(after.finalAnswerSha256, before.finalAnswerSha256, "the original persisted answer remains unchanged");
          evidence.checks.noProviderTurn = {
            newUserMessages: 0,
            newTurns: 0,
            providerEndpointLoopbackAndClosed: true,
          };
        } catch (error) {
          evidence.threadAfterError = safeError(error);
          if (!failure) failure = error instanceof Error ? error : new Error(String(error));
          evidence.status = "failed";
        }
      }
    }
    if (clipboardGuard) {
      try {
        if (clipboardCaptured) {
          const originalFormats = [...clipboardSnapshot.formats].sort();
          const roundTrip = await restoreClipboardAndVerify(
            clipboardGuard,
            originalFormats,
            clipboardSnapshot.hadData,
          );
          clipboardCaptured = false;
          evidence.cleanup = {
            ...evidence.cleanup,
            clipboardRestored: true,
            originalClipboardFormatSetPreserved: true,
            originalClipboardFormatCount: roundTrip.formats.length,
            originalClipboardHadDataPreserved: roundTrip.hadData,
          };
        }
      } catch (error) {
        evidence.cleanup = { ...evidence.cleanup, clipboardRestoreVerificationError: safeError(error) };
        try {
          const restored = await clipboardGuard.restore();
          evidence.cleanup.clipboardRestored = restored.restored === true;
          evidence.cleanup.clipboardRestoreRetriedAfterVerificationFailure = true;
        } catch (restoreError) {
          evidence.cleanup.clipboardRestoreError = safeError(restoreError);
        }
        if (!failure) {
          failure = error instanceof Error ? error : new Error(String(error));
          evidence.status = "failed";
        }
      }
      await clipboardGuard.close().catch((error) => {
        evidence.cleanup = { ...evidence.cleanup, clipboardGuardCloseError: safeError(error) };
      });
    }
    restoreJdk();
    await writeFile(evidencePath, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  }
  if (failure) {
    process.stderr.write(`CLI existing-experience ConPTY acceptance failed. Evidence: ${runRoot}\n`);
    throw failure;
  }
  process.stdout.write(`${JSON.stringify({ status: evidence.status, evidenceDirectory: runRoot, checks: evidence.checks }, null, 2)}\n`);
}

/** 它只在本文件作为命令运行时启动验收，导入时不编译或触碰用户剪贴板。 @author kongweiguang */
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv.includes("--clipboard-lock-self-test")) await runClipboardLockSelfTest();
  else await runAcceptance();
}
