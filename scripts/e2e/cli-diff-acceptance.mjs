// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { mkdir, readFile, readdir, realpath, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import {
  compileConPtyHarness,
  createRunDirectories,
  removeOwnedProfile,
  resolveArtifacts,
  startConPtyCli,
  writeScreenEvidence,
} from "./cli-usage-conpty.mjs";
import { JsonlSession, providerConfigurationDocument } from "./real-provider-smoke.mjs";
import { providerStream } from "./media-schema-loopback.mjs";
import { fullscreenInitializeParams } from "./cli-fullscreen-acceptance.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const evidenceRoot = join(repoRoot, "target", "cli-diff-acceptance");
const profileSecret = "ja-cli-diff-acceptance-placeholder";
const providerId = "provider_cli_diff_acceptance";
const modelId = "model_cli_diff_acceptance";
const credentialId = "cred_cli_diff_acceptance";
const noChangeMarker = "JA_DIFF_NO_CHANGE_ACCEPTANCE";
const writeMarker = "JA_DIFF_WRITE_ACCEPTANCE";
const largeMarker = "JA_DIFF_LARGE_ACCEPTANCE";
const noChangeFinal = "JA_DIFF_NO_CHANGE_FINAL";
const writeFinal = "JA_DIFF_WRITE_FINAL";
const largeFinal = "JA_DIFF_LARGE_FINAL";
const writePath = "diff-验收-文件.txt";
const largePath = "diff-长文-验收.txt";
const writeContent = "Ja 本轮差异：中文和 Emoji 🙂\n精确显示第二行 β✅\n";
const largeContent = "差异正文🙂春日β\n".repeat(12_000);
const writeCallId = "call_ja_cli_diff_write";
const largeCallId = "call_ja_cli_diff_large";
const exec = process.argv[1] ? resolve(process.argv[1]) : undefined;
const execFileAsync = promisify(execFile);

const previousEvidenceRoot = process.env.JA_TEST_EVIDENCE_ROOT;
process.env.JA_TEST_EVIDENCE_ROOT ??= evidenceRoot;
const terminalHarness = await import("./cli-usage-acceptance.mjs");
if (previousEvidenceRoot === undefined) delete process.env.JA_TEST_EVIDENCE_ROOT;

/** 只将 RPC 稳定错误码放入失败信息，避免把隔离凭据、Provider 请求正文写入日志。 @author kongweiguang */
function requireRpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = frame.error?.data?.errorCode ?? frame.error?.data?.category ?? "RPC_ERROR";
    throw new Error(`${operation} failed: ${String(code)}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 失败快照仅来自隔离运行，并在写入前清除 fixture credential、loopback endpoint 与临时 profile 路径。 @author kongweiguang */
function sanitizeDiagnosticText(value, secrets) {
  let safeText = String(value ?? "");
  for (const secret of secrets) {
    if (typeof secret === "string" && secret.length > 0) safeText = safeText.replaceAll(secret, "<redacted>");
  }
  return safeText
    .replace(/(authorization\s*[:=]\s*bearer\s+)[^\s,;]+/giu, "$1<redacted>")
    .replace(/((?:api[_-]?key|credential|secret|token)\s*[:=]\s*)[^\s,;]+/giu, "$1<redacted>");
}

/** 仅复制本次 JA_HOME/logs 内有限的日志文件，供 native bootstrap 失败定位并保留脱敏后的回读证据。 @author kongweiguang */
async function captureIsolatedBootstrapLogs(directories, secrets) {
  const root = join(directories.home, "logs");
  const collected = [];
  const visit = async (directory, depth) => {
    if (depth > 3 || collected.length >= 32) return;
    let entries;
    try {
      entries = await readdir(directory, { withFileTypes: true });
    } catch (error) {
      if (error?.code === "ENOENT") return;
      throw error;
    }
    for (const entry of entries) {
      if (collected.length >= 32) break;
      const path = join(directory, entry.name);
      if (entry.isDirectory()) await visit(path, depth + 1);
      else if (entry.isFile() && /\.(?:log|txt|jsonl)$/iu.test(entry.name)) {
        const metadata = await stat(path);
        if (metadata.size <= 1024 * 1024) {
          const text = await readFile(path, "utf8");
          collected.push({ path: path.slice(root.length + 1), bytes: metadata.size, text: sanitizeDiagnosticText(text, secrets) });
        }
      }
    }
  };
  await visit(root, 0);
  const outputPath = join(evidenceRoot, "native-bootstrap-diagnostics.txt");
  const sections = collected.map((item) => `--- ${item.path} (${item.bytes} bytes) ---\n${item.text}`);
  await writeFile(outputPath, `${sections.join("\n\n")}\n`, "utf8");
  return { path: outputPath, files: collected.map(({ path, bytes }) => ({ path, bytes })) };
}

/** 只向当前隔离 JA_HOME 的 bundled native 服务请求正常关闭，并按同一随机 home 身份等待其退出。 @author kongweiguang */
async function stopBundledNativeServer(artifacts, directories) {
  const extendedHome = resolve(directories.home).startsWith("\\\\?\\")
    ? resolve(directories.home)
    : `\\\\?\\${resolve(directories.home)}`;
  const homeToken = Buffer.from(extendedHome, "utf8").toString("base64url");
  const queryOwnedSidecarCount = async () => {
    const query = `$token='${homeToken}'; $count=@(Get-CimInstance Win32_Process -Filter "Name='ja-app-server-x86_64-pc-windows-msvc.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('--home-dir-base64=' + $token) }).Count; $count`;
    const result = await execFileAsync("pwsh.exe", ["-NoLogo", "-NoProfile", "-Command", query], {
      windowsHide: true,
      timeout: 5_000,
      maxBuffer: 16 * 1024,
    });
    const count = Number(result.stdout.trim());
    assert.ok(Number.isSafeInteger(count) && count >= 0, "isolated native sidecar process query must return a count");
    return count;
  };
  const initialCount = await queryOwnedSidecarCount();
  if (initialCount === 0) return { status: "already-stopped", remainingOwnedSidecars: 0 };

  const environment = { ...process.env, JA_HOME: directories.home };
  delete environment.JA_DEBUG_JAVA;
  delete environment.JA_DEBUG_JAR;
  await execFileAsync(artifacts.cli, ["server", "stop"], {
    cwd: directories.workspace,
    env: environment,
    windowsHide: true,
    timeout: 20_000,
    maxBuffer: 32 * 1024,
  });

  const deadline = Date.now() + 15_000;
  let remainingOwnedSidecars = initialCount;
  while (Date.now() < deadline) {
    remainingOwnedSidecars = await queryOwnedSidecarCount();
    if (remainingOwnedSidecars === 0) {
      return { status: "gracefully-stopped", remainingOwnedSidecars: 0 };
    }
    await delay(200);
  }
  throw new Error(`isolated native App Server did not exit after server stop (${remainingOwnedSidecars} process(es) remain)`);
}

/** 检查内建 write 工具的严格参数契约后再发本地 Responses Tool 调用。 @author kongweiguang */
function assertWriteTool(payload) {
  const tool = Array.isArray(payload?.tools)
    ? payload.tools.find((candidate) => candidate?.type === "function" && candidate?.name === "write")
    : undefined;
  const schema = tool?.parameters;
  assert.equal(tool?.strict, true, "write acceptance requires the native strict Tool schema");
  assert.ok(Array.isArray(schema?.required) && schema.required.includes("path") && schema.required.includes("content"));
  assert.equal(schema.properties?.path?.type, "string");
  assert.equal(schema.properties?.content?.type, "string");
}

/** 用受限 loopback OpenAI Responses 服务覆盖普通回答、短文件写入及超过界面的长差异。 @author kongweiguang */
async function startDiffProvider() {
  const server = createServer(async (request, response) => {
    try {
      if (request.method !== "POST" || request.url !== "/v1/responses") {
        response.writeHead(404).end();
        return;
      }
      if (request.headers.authorization !== `Bearer ${profileSecret}`) {
        response.writeHead(401).end();
        return;
      }
      const chunks = [];
      let requestBytes = 0;
      for await (const chunk of request) {
        requestBytes += chunk.length;
        if (requestBytes > 8 * 1024 * 1024) throw new Error("fixture_request_too_large");
        chunks.push(chunk);
      }
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const inputText = JSON.stringify(payload.input ?? []);
      const toolResultCallIds = new Set(
        (Array.isArray(payload.input) ? payload.input : [])
          .filter((item) => item?.type === "function_call_output")
          .map((item) => item.call_id),
      );
      let stream;
      if (inputText.includes(largeMarker) && !toolResultCallIds.has(largeCallId) && Array.isArray(payload.tools)) {
        assertWriteTool(payload);
        stream = providerStream("openai_responses", "resp_ja_cli_diff_large", {
          callId: largeCallId,
          name: "write",
          arguments: { path: largePath, content: largeContent },
        });
      } else if (inputText.includes(writeMarker) && !toolResultCallIds.has(writeCallId) && Array.isArray(payload.tools)) {
        assertWriteTool(payload);
        stream = providerStream("openai_responses", "resp_ja_cli_diff_write", {
          callId: writeCallId,
          name: "write",
          arguments: { path: writePath, content: writeContent },
        });
      } else {
        const finalText = inputText.includes(largeMarker)
          ? largeFinal
          : inputText.includes(writeMarker)
            ? writeFinal
            : inputText.includes(noChangeMarker)
              ? noChangeFinal
              : "Ja 本地差异验收";
        stream = providerStream("openai_responses", `resp_ja_cli_diff_text_${Date.now()}`, undefined, finalText);
      }
      response.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-cache",
        connection: "close",
      });
      response.end(stream);
    } catch {
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: { message: "isolated diff fixture rejected the request" } }));
    }
  });
  server.requestTimeout = 30_000;
  server.headersTimeout = 10_000;
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");
  return {
    endpoint: `http://127.0.0.1:${address.port}`,
    close: async () => {
      server.closeAllConnections();
      await new Promise((resolvePromise) => server.close(() => resolvePromise()));
    },
  };
}

/** 在单独 profile 使用当前 Java 握手词汇表配置 loopback Provider，并创建相互独立的只读和写入线程。 @author kongweiguang */
async function seedDiffThreads(artifacts, directories, endpoint) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: profileSecret,
    endpoint,
  });
  let ready = false;
  try {
    const initialized = requireRpcResult(
      await session.request("runtime/initialize", await fullscreenInitializeParams()),
      "runtime/initialize",
    );
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    ready = true;

    const configuration = requireRpcResult(await session.request("configuration/read", {}), "configuration/read");
    const document = providerConfigurationDocument({
      endpoint,
      name: "Ja CLI /diff isolated loopback",
      api: "openai_responses",
      model: "ja-cli-diff-acceptance",
      selectedProviderId: providerId,
      selectedModelId: modelId,
      selectedCredentialId: credentialId,
      reasoningLevel: "medium",
    });
    document.providers[0].models[0].capabilities.context_window_tokens = 1_000_000;
    document.providers[0].agent_defaults.context.auto_compact = false;
    requireRpcResult(await session.request("configuration/replace", {
      scope: "user",
      expectedVersion: configuration.cas.userVersion,
      document,
    }), "configuration/replace");
    requireRpcResult(await session.request("credential/set", {
      credentialId,
      secret: profileSecret,
      expectedVersion: configuration.cas.credentialVersion,
    }), "credential/set");
    const workspace = requireRpcResult(await session.request("workspace/open", {
      cwd: directories.workspace,
      displayName: "Ja CLI /diff isolated workspace",
    }), "workspace/open");
    requireRpcResult(await session.request("workspace/set-trust", {
      workspaceId: workspace.workspaceId,
      trust: "trusted",
    }), "workspace/set-trust");

    /** 所有样例共享已信任的隔离工作区，但以不同 Thread 固定各自的 Turn 历史。 @author kongweiguang */
    const createThread = async (title) => {
      const result = requireRpcResult(await session.request("thread/create", {
        cwd: directories.workspace,
        title,
        providerId,
        modelId,
        reasoningLevel: "medium",
        accessMode: "full_access",
        collaborationMode: "default",
      }), "thread/create");
      assert.match(result.threadId, /^thr_[A-Za-z0-9_.-]+$/u);
      return result.threadId;
    };
    const threads = {
      empty: await createThread("Ja /diff 空会话验收"),
      noChange: await createThread("Ja /diff 无文件修改验收"),
      write: await createThread("Ja /diff 中文文件验收"),
      large: await createThread("Ja /diff 长文截断验收"),
    };
    return threads;
  } finally {
    if (ready && !session.exited) await session.shutdown().catch(() => undefined);
    await session.forceClose();
  }
}

/** 使用有界轮询等待完成态，避免把工具响应或 Provider 延迟当成 TUI 绘制完成。 @author kongweiguang */
async function waitForCliScreen(cli, predicate, label, timeoutMs = 45_000) {
  return cli.waitForScreen(predicate, label, timeoutMs);
}

/** 先确认 `/diff` 已出现在 Composer 行，再提交命令，避免终端输入节奏让首字符被重绘吞掉。 @author kongweiguang */
async function enterDiffCommand(cli, name) {
  cli.sendKeys("/diff");
  await waitForCliScreen(
    cli,
    (_visible, lines) => lines.some((line) => /^\s*›\s*\/diff\s*$/u.test(line)),
    `${name} /diff command in Composer`,
  );
  cli.sendKeys("\r");
}

/** 单独打开真实 WT shell 并启动当前目标 CLI，允许开发态或安装版 sidecar 通过同一终端验收。 @author kongweiguang */
async function launchDiffWindowsTerminal(wt, artifacts, directories, threadId, helper, runRoot, useBundledSidecar) {
  const title = `JaDiff-${process.pid}-${Date.now()}-Campbell`;
  const started = join(runRoot, `${title}.started`);
  const finished = join(runRoot, `${title}.finished.json`);
  const cliState = join(runRoot, `${title}.cli-state.json`);
  const launcher = join(runRoot, `${title}.ps1`);
  const debugEnvironment = useBundledSidecar
    ? ["Remove-Item Env:JA_DEBUG_JAVA, Env:JA_DEBUG_JAR -ErrorAction SilentlyContinue"]
    : [`$env:JA_DEBUG_JAVA = ${powershellLiteral(artifacts.java)}`, `$env:JA_DEBUG_JAR = ${powershellLiteral(artifacts.jar)}`];
  const script = [
    "$ErrorActionPreference = 'Stop'",
    `$env:JA_HOME = ${powershellLiteral(directories.home)}`,
    "Remove-Item Env:JA_REAL_PROVIDER_API_KEY, Env:JA_REAL_PROVIDER_BASE_URL, Env:JA_REAL_PROVIDER_MODEL, Env:JA_REAL_PROVIDER_NAME, Env:JA_REAL_PROVIDER_API, Env:JA_REAL_PROVIDER_REASONING_LEVEL, Env:JA_REAL_PROVIDER_AUTHORIZED -ErrorAction SilentlyContinue",
    "$env:TERM = 'xterm-256color'",
    ...debugEnvironment,
    "[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)",
    "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)",
    `[System.IO.File]::WriteAllText(${powershellLiteral(started)}, [string]$PID, [System.Text.UTF8Encoding]::new($false))`,
    `$cli = Start-Process -FilePath ${powershellLiteral(artifacts.cli)} -ArgumentList @('resume', ${powershellLiteral(threadId)}) -NoNewWindow -PassThru -WorkingDirectory ${powershellLiteral(directories.workspace)}`,
    `[System.IO.File]::WriteAllText(${powershellLiteral(cliState)}, (@{ processId = $cli.Id; executablePath = ${powershellLiteral(artifacts.cli)} } | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))`,
    "$cli.WaitForExit()",
    `[System.IO.File]::WriteAllText(${powershellLiteral(finished)}, (@{ processId = $PID; exitCode = $cli.ExitCode } | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))`,
  ].join("\r\n");
  await writeFile(launcher, `${script}\r\n`, "utf8");
  const child = spawn(wt, [
    "-w", "new", "new-tab", "--title", title, "--suppressApplicationTitle", "--colorScheme", "Campbell",
    "--startingDirectory", directories.workspace,
    "pwsh.exe", "-NoLogo", "-NoProfile", "-NoExit", "-File", launcher,
  ], { cwd: directories.workspace, windowsHide: false, stdio: "ignore" });
  const spawnFailure = new Promise((_, rejectPromise) => child.once("error", rejectPromise));
  await Promise.race([delay(500), spawnFailure]);
  return { title, started, finished, cliState, cliPath: artifacts.cli, launcher, child, helper };
}

/** 仅通过 Win32 helper 读取与随机 CLI PID 和映像路径匹配的真实终端 console，避免跨进程请求文件锁。 @author kongweiguang */
async function readDiffTerminalConsole(helper, window) {
  const cliState = JSON.parse(await readFile(window.cliState, "utf8"));
  const output = await terminalHarness.runTerminalAction(helper, [
    "-Action", "console-snapshot", "-Title", window.title,
    "-ExpectedCliProcessId", String(cliState.processId), "-ExpectedCliPath", window.cliPath,
  ]);
  return JSON.parse(output);
}

/** 当 WT 改写窗口标题时按先前探测到的唯一 HWND 和 owner PID 捕图，短时前置并恢复截图前焦点。 @author kongweiguang */
async function captureOwnedTerminalByHandle(window, screenshot) {
  const scriptPath = join(evidenceRoot, "capture-owned-terminal.ps1");
  const source = String.raw`# @author kongweiguang
param([long]$WindowHandle, [uint32]$ExpectedOwnerProcessId, [string]$OutputPath)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class JaDiffCaptureWin32 {
  [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left; public int Top; public int Right; public int Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct Point { public int X; public int Y; }
  [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr handle);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr handle);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr handle);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr handle);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr handle, int command);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr handle);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr handle, out Rect rect);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr handle, out Rect rect);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr handle, ref Point point);
  [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(Point point);
  [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr handle, uint flags);
  [DllImport("user32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint first, uint second, bool attach);
  [DllImport("user32.dll", SetLastError=true)] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
}
'@
[void][JaDiffCaptureWin32]::SetThreadDpiAwarenessContext([IntPtr]::new(-4))
$target = [IntPtr]::new($WindowHandle)
if (-not [JaDiffCaptureWin32]::IsWindow($target)) { throw 'the previously probed terminal HWND no longer exists' }
if (-not [JaDiffCaptureWin32]::IsWindowVisible($target) -or [JaDiffCaptureWin32]::IsIconic($target)) { throw 'the owned terminal HWND is not visible and restored' }
[uint32]$ownerProcessId = 0
$targetThread = [JaDiffCaptureWin32]::GetWindowThreadProcessId($target, [ref]$ownerProcessId)
if ($ownerProcessId -ne $ExpectedOwnerProcessId) { throw 'the terminal HWND owner changed; refusing screenshot' }
$previous = [JaDiffCaptureWin32]::GetForegroundWindow()
$currentThread = [JaDiffCaptureWin32]::GetCurrentThreadId()
[uint32]$previousProcessId = 0
$previousThread = [JaDiffCaptureWin32]::GetWindowThreadProcessId($previous, [ref]$previousProcessId)
$attached = [System.Collections.Generic.List[uint32]]::new()
foreach ($thread in @($targetThread, $previousThread)) {
  if ($thread -ne 0 -and $thread -ne $currentThread -and -not $attached.Contains($thread)) {
    if ([JaDiffCaptureWin32]::AttachThreadInput($currentThread, $thread, $true)) { $attached.Add($thread) }
  }
}
try {
  if ([JaDiffCaptureWin32]::GetForegroundWindow() -ne $target) {
    [void][JaDiffCaptureWin32]::ShowWindow($target, 9)
    [void][JaDiffCaptureWin32]::BringWindowToTop($target)
    [void][JaDiffCaptureWin32]::SetForegroundWindow($target)
    Start-Sleep -Milliseconds 120
  }
  if ([JaDiffCaptureWin32]::GetForegroundWindow() -ne $target) { throw 'owned terminal could not be foregrounded for immediate screenshot' }
  $rect = [JaDiffCaptureWin32+Rect]::new()
  if (-not [JaDiffCaptureWin32]::GetWindowRect($target, [ref]$rect)) { throw 'cannot read owned terminal physical bounds' }
  if ($rect.Right -le $rect.Left -or $rect.Bottom -le $rect.Top) { throw 'owned terminal has empty physical bounds' }
  $client = [JaDiffCaptureWin32+Rect]::new()
  if (-not [JaDiffCaptureWin32]::GetClientRect($target, [ref]$client)) { throw 'cannot inspect owned terminal client area' }
  foreach ($fraction in @(0.25, 0.5, 0.75)) {
    $point = [JaDiffCaptureWin32+Point]::new()
    $point.X = [int](($client.Right - $client.Left) * $fraction)
    $point.Y = [int](($client.Bottom - $client.Top) * $fraction)
    if (-not [JaDiffCaptureWin32]::ClientToScreen($target, [ref]$point)) { throw 'cannot map owned terminal client pixels' }
    $covered = [JaDiffCaptureWin32]::GetAncestor([JaDiffCaptureWin32]::WindowFromPoint($point), 2)
    if ($covered -ne $target) { throw 'owned terminal screenshot area is occluded' }
  }
  $bitmap = [System.Drawing.Bitmap]::new($rect.Right - $rect.Left, $rect.Bottom - $rect.Top)
  try {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try { $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size) } finally { $graphics.Dispose() }
    $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
  } finally { $bitmap.Dispose() }
  [pscustomobject]@{ handle = $WindowHandle; ownerProcessId = $ownerProcessId; physicalRect = @{ left = $rect.Left; top = $rect.Top; right = $rect.Right; bottom = $rect.Bottom }; coordinateSpace = 'physical-per-monitor-v2'; screenshot = $OutputPath } | ConvertTo-Json -Compress
} finally {
  if ([JaDiffCaptureWin32]::GetForegroundWindow() -eq $target -and $previous -ne $target) { [void][JaDiffCaptureWin32]::SetForegroundWindow($previous) }
  foreach ($thread in $attached) { [void][JaDiffCaptureWin32]::AttachThreadInput($currentThread, $thread, $false) }
}`;
  await writeFile(scriptPath, `${source}\r\n`, "utf8");
  const result = await execFileAsync("pwsh.exe", [
    "-NoLogo", "-NoProfile", "-File", scriptPath,
    "-WindowHandle", String(window.windowHandle),
    "-ExpectedOwnerProcessId", String(window.ownerProcessId),
    "-OutputPath", screenshot,
  ], { windowsHide: true, timeout: 10_000, maxBuffer: 16 * 1024 });
  return JSON.parse(result.stdout.trim());
}

/** 后台无遮挡时直接复制窗口像素；否则用唯一 HWND 短时激活本次 WT 捕图并恢复截图前焦点。 @author kongweiguang */
async function captureDiffTerminalScreenshot(helper, window, filename) {
  const screenshot = join(evidenceRoot, filename);
  const foregroundBeforeCapture = await terminalHarness.readForegroundHandle(helper);
  const args = [
    "-Action", "capture-background", "-Title", window.title, "-OutputPath", screenshot,
    "-ExpectedOwnerProcessId", String(window.ownerProcessId), "-ExpectedForegroundHandle", String(foregroundBeforeCapture),
  ];
  let metadata;
  let captureMode = "background-screen-copy";
  try {
    metadata = JSON.parse(await terminalHarness.runTerminalAction(helper, args));
  } catch (error) {
    const message = String(error?.message ?? error);
    if (!/foreground|overlap|occlusion|monitor bounds|wholly visible|occluded|window was not found/iu.test(message)) throw error;
    captureMode = "short-foreground-screen-copy";
    try {
      try {
        metadata = JSON.parse(await terminalHarness.runTerminalAction(helper, ["-Action", "capture", "-Title", window.title, "-OutputPath", screenshot]));
      } catch (captureError) {
        if (!/window was not found/iu.test(String(captureError?.message ?? captureError))) throw captureError;
        captureMode = "short-foreground-screen-copy-by-owned-hwnd";
        metadata = await captureOwnedTerminalByHandle(window, screenshot);
      }
    } finally {
      const foregroundAfterCapture = await terminalHarness.readForegroundHandle(helper);
      assert.equal(foregroundAfterCapture, foregroundBeforeCapture, "foreground screenshot must immediately restore the window that was active before capture");
    }
  }
  const file = await stat(screenshot);
  assert.ok(file.size > 10_000, "Windows Terminal screenshot must contain captured rendered pixels");
  assert.equal(metadata.coordinateSpace, "physical-per-monitor-v2");
  return { screenshot, bytes: file.size, captureMode, ...metadata };
}

/** 恢复刚启动或刚操作的自有 WT 窗口焦点；若用户已切到其他窗口则保留其当前选择。 @author kongweiguang */
async function restoreDiffForeground(helper, window, baselineForegroundHandle) {
  const foregroundBeforeRestore = await terminalHarness.readForegroundHandle(helper);
  let restoredToBaseline = false;
  if (foregroundBeforeRestore === window.windowHandle && foregroundBeforeRestore !== baselineForegroundHandle) {
    await terminalHarness.runTerminalAction(helper, [
      "-Action", "restore-focus", "-Title", "", "-PreviousHandle", String(baselineForegroundHandle),
    ]);
    restoredToBaseline = true;
  }
  const foregroundAfterRestore = await terminalHarness.readForegroundHandle(helper);
  if (restoredToBaseline) {
    assert.equal(foregroundAfterRestore, baselineForegroundHandle, "the original foreground must be restored immediately after a WT action");
  }
  return { foregroundBeforeRestore, foregroundAfterRestore, restoredToBaseline };
}

/** 使用精确 CLI PID 与已核实映像路径投递 /diff/F5/Esc，避免标题变化和焦点竞争影响真实 WT 验收。 @author kongweiguang */
async function sendDiffConsoleAction(helper, window, action, payload) {
  const cliState = JSON.parse(await readFile(window.cliState, "utf8"));
  assert.ok(Number.isSafeInteger(cliState.processId) && cliState.processId > 0, "WT input requires the exact recorded CLI PID");
  assert.equal(resolve(cliState.executablePath).toLowerCase(), resolve(window.cliPath).toLowerCase(), "WT input must target the verified release CLI image");
  return terminalHarness.runTerminalAction(helper, [
    "-Action", action,
    "-ExpectedCliProcessId", String(cliState.processId),
    "-ExpectedCliPath", window.cliPath,
    ...(payload === undefined ? [] : ["-Payload", payload]),
  ]);
}

/** 转义 PowerShell 单引号字符串中的路径，避免临时 profile 或安装目录改变 launcher 命令结构。 @author kongweiguang */
function powershellLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

/** 锁定 release CLI 相邻的架构匹配 App Server sidecar 身份，防止把 bundled-native 验收误报为 JVM 路径。 @author kongweiguang */
async function inspectBundledNativeSidecar(cliPath) {
  const path = join(dirname(cliPath), "sidecars", "ja-app-server-x86_64-pc-windows-msvc.exe");
  const metadata = await stat(path);
  assert.ok(metadata.isFile() && metadata.size > 0, "bundled Windows native App Server sidecar must exist beside the release CLI");
  const sha256 = createHash("sha256").update(await readFile(path)).digest("hex").toUpperCase();
  return { path, bytes: metadata.size, sha256 };
}

/** 在真实 ConPTY 验证空会话、已完成差异、F5 重读、Esc 返回及草稿输入恢复。 @author kongweiguang */
async function runConPtyScenario(artifacts, directories, harness, threadId, options) {
  const cli = startConPtyCli(
    artifacts,
    directories,
    harness,
    threadId,
    options.prompt,
    join(options.runRoot, `${options.name}.vt`),
    undefined,
    options.useBundledSidecar,
  );
  const evidence = { state: options.name, diffObserved: false, refreshObserved: false, escapeReturned: false };
  let scenarioFailure;
  try {
    await cli.harnessReady;
    if (options.finalMarker) {
      await waitForCliScreen(cli, (visible) => visible.includes(options.finalMarker), `${options.name} completed reply`);
      await waitForCliScreen(cli, (visible) => visible.includes("工作用时") && !visible.includes("正在工作"), `${options.name} idle state`);
    } else {
      await waitForCliScreen(cli, (visible) => /^\s*›\s*$/mu.test(visible), `${options.name} empty composer`);
    }

    await enterDiffCommand(cli, options.name);
    const matches = options.matches instanceof RegExp
      ? (visible) => options.matches.test(visible)
      : options.matches;
    const diff = await waitForCliScreen(cli, matches, `${options.name} frozen diff`);
    evidence.diffObserved = true;
    await writeScreenEvidence(options.runRoot, `${options.name}-diff`, cli.screen);
    if (options.large) {
      cli.sendKeys("\u001b[4~");
      const bottom = await waitForCliScreen(cli, (visible) => options.truncationMatches.test(visible), `${options.name} bounded diff notice`);
      evidence.truncationNoticeObserved = true;
      evidence.visibleCharsAtNotice = bottom.text.length;
      await writeScreenEvidence(options.runRoot, `${options.name}-truncated-diff`, cli.screen);
    }
    assert.match(diff.text, options.matches, `${options.name} should display the expected frozen diff state`);

    cli.sendKeys("\u001b[15~");
    const refreshed = await waitForCliScreen(
      cli,
      options.large ? (visible) => options.truncationMatches.test(visible) : matches,
      `${options.name} F5 reread`,
    );
    evidence.refreshObserved = true;
    assert.match(refreshed.text, options.large ? options.truncationMatches : options.matches);
    cli.sendKeys("\u001b");
    await waitForCliScreen(cli, (visible) => !visible.includes("本次变更") && /^\s*›\s*$/mu.test(visible), `${options.name} Esc return`);
    evidence.escapeReturned = true;
    cli.sendKeys("草稿返回🙂");
    const draft = await waitForCliScreen(cli, (visible) => visible.includes("草稿返回🙂"), `${options.name} composer after Esc`);
    evidence.composerAvailableAfterEscape = draft.text.includes("草稿返回🙂");
    cli.sendKeys("\u007f".repeat("草稿返回🙂".length));
    await waitForCliScreen(cli, (visible) => /^\s*›\s*$/mu.test(visible), `${options.name} cleared draft`);
  } catch (error) {
    scenarioFailure = error instanceof Error ? error : new Error(String(error));
  }
  try {
    evidence.close = await cli.close();
    assert.equal(evidence.close.forced, false, `${options.name} ConPTY should close through the CLI's normal terminal path`);
  } catch (error) {
    if (!scenarioFailure) scenarioFailure = error instanceof Error ? error : new Error(String(error));
    else scenarioFailure.cleanupFailure = String(error?.message ?? error);
  }
  if (scenarioFailure) {
    const diagnosticPath = join(options.runRoot, `${options.name}-conpty-failure.json`);
    const diagnostics = {
      scenario: options.name,
      error: sanitizeDiagnosticText(scenarioFailure.message, options.redactionSecrets ?? []),
      cleanupFailure: sanitizeDiagnosticText(scenarioFailure.cleanupFailure, options.redactionSecrets ?? []),
      lastVisibleScreen: sanitizeDiagnosticText(cli.screen.text(), options.redactionSecrets ?? []),
      harnessStdoutTail: sanitizeDiagnosticText(cli.messages.slice(-80).join("\n"), options.redactionSecrets ?? []),
      harnessStderrTail: sanitizeDiagnosticText(cli.stderr(), options.redactionSecrets ?? []).slice(-8_000),
      diagnosticPath,
    };
    await writeFile(diagnosticPath, `${JSON.stringify(diagnostics, null, 2)}\n`, "utf8");
    scenarioFailure.diagnosticPath = diagnosticPath;
    throw scenarioFailure;
  }
  return evidence;
}

/** 仅在隔离的随机标题 WT 窗口中打开 /diff，以原生 console cell 与真实像素共同核对显示。 @author kongweiguang */
async function runWindowsTerminalScenario(artifacts, directories, threadId, helper, wt, options) {
  const baselineForeground = await terminalHarness.readForegroundHandle(helper);
  const window = await launchDiffWindowsTerminal(
    wt, artifacts, directories, threadId, helper, options.runRoot, options.useBundledSidecar,
  );
  let result;
  try {
    await waitForFile(window.started, `${options.name} Windows Terminal launcher`);
    await waitForFile(window.cliState, `${options.name} CLI process identity`);
    await terminalHarness.waitForTerminalWindow(helper, window);
    await terminalHarness.waitForTerminalWindow(helper, window);
    result = await restoreDiffForeground(helper, window, baselineForeground);
    /** 每次读取目标 CLI 的原生 Console cell 并设置总期限，避免跨进程文件握手竞争或固定等待。 @author kongweiguang */
    const screenUntil = async (predicate, label) => {
      const deadline = Date.now() + 30_000;
      let snapshot;
      while (Date.now() < deadline) {
        snapshot = await readDiffTerminalConsole(helper, window);
        const visible = snapshot.visibleRows.join("\n");
        if (predicate(visible)) return { snapshot, visible };
        await delay(200);
      }
      throw new Error(`${options.name} Windows Terminal did not reach ${label}`);
    };
    await screenUntil((visible) => visible.split("\n").some((line) => line.trim() === "›"), "composer-ready");
    await sendDiffConsoleAction(helper, window, "console-type", "/diff");
    await sendDiffConsoleAction(helper, window, "console-enter", "");
    const visibleDiff = await screenUntil(options.matches, "diff-visible");
    const screenshot = await captureDiffTerminalScreenshot(
      helper, window, `${options.name}-windows-terminal.png`,
    );
    // ReadConsoleOutputW omits trailing wide-cell placeholders; the owned pixel capture remains the acceptance evidence for supplementary emoji glyphs.
    assert.match(visibleDiff.visible.replaceAll(/\s+/gu, ""), options.windowsTerminalMatches ?? options.normalizedMatches);
    result = {
      ...result,
      screenshot: screenshot.screenshot,
      screenshotBytes: screenshot.bytes,
      screenshotMode: screenshot.captureMode,
      foregroundUnchanged: screenshot.foregroundUnchanged ?? true,
      consoleWindow: { width: visibleDiff.snapshot.columns, height: visibleDiff.snapshot.rows },
      diffVisibleInNativeConsole: true,
    };
    await sendDiffConsoleAction(helper, window, "console-key", "F5");
    const refreshed = await screenUntil(options.matches, "f5-reread");
    assert.match(refreshed.visible.replaceAll(/\s+/gu, ""), options.windowsTerminalMatches ?? options.normalizedMatches);
    await sendDiffConsoleAction(helper, window, "console-key", "ESC");
    const returned = await screenUntil(
      (visible) => !visible.includes("本次变更") && visible.split("\n").some((line) => line.trim() === "›"),
      "escape-return",
    );
    result.escapeReturned = true;
    assert.match(returned.visible, /›/u);
  } finally {
    let closure;
    try {
      const identity = JSON.parse(await readFile(window.cliState, "utf8"));
      const dispatch = await terminalHarness.runTerminalAction(helper, [
        "-Action", "console-ctrl-d", "-Title", window.title,
        "-ExpectedCliProcessId", String(identity.processId), "-ExpectedCliPath", window.cliPath,
      ]);
      const finished = JSON.parse((await waitForFile(window.finished, `${options.name} clean CLI exit`, 45_000)).replace(/^\uFEFF/u, ""));
      assert.equal(finished.processId, Number((await readFile(window.started, "utf8")).trim()));
      assert.equal(finished.exitCode, 0);
      await terminalHarness.runTerminalAction(helper, [
        "-Action", "close", "-Title", window.title, "-ExpectedOwnerProcessId", String(window.ownerProcessId),
      ]);
      closure = { normalExit: true, exitCode: finished.exitCode, dispatch: JSON.parse(dispatch), windowClosed: true };
    } catch (error) {
      closure = await terminalHarness.forceCloseOwnedTerminal(window, helper).catch((cleanupError) => ({
        cleanupFailure: String(cleanupError?.message ?? cleanupError),
      }));
      closure.normalExit = false;
      closure.closeFailure = String(error?.message ?? error);
    }
    result = { ...(result ?? {}), closure };
  }
  return result;
}

/** 以唯一 run root 轮询文件事实，避免固定等待时间掩盖 Windows Terminal 启动慢的问题。 @author kongweiguang */
async function waitForFile(path, label, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      return await readFile(path, "utf8");
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    await delay(100);
  }
  throw new Error(`${label} timed out`);
}

/** 只使用 loopback Provider 和本仓库当前 JDK25 构建，生成 ConPTY 与 Windows Terminal 的独立验收证据。 @author kongweiguang */
export async function runCliDiffAcceptance() {
  await mkdir(evidenceRoot, { recursive: true });
  await terminalHarness.prepareEvidenceDirectory();
  if (process.env.JA_TEST_CLI === undefined) process.env.JA_TEST_CLI = join(repoRoot, "target", "ja-cli", "debug", "ja.exe");
  else process.env.JA_TEST_CLI = await realpath(resolve(process.env.JA_TEST_CLI));
  const artifacts = await resolveArtifacts();
  const isolated = await createRunDirectories();
  const provider = await startDiffProvider();
  let terminalHelper;
  let primaryFailure;
  let appServersStopped = [];
  let runtimeOwnersReleased = true;
  const useBundledSidecar = process.env.JA_DIFF_USE_BUNDLED_SIDECAR === "1";
  const evidence = {
    status: "running",
    provider: "loopback-only OpenAI Responses; no real or paid Provider",
    javaVersion: artifacts.javaVersion,
    cliSha256: artifacts.cliSha256,
    appServerJarSha256: artifacts.jarSha256,
    terminal: {},
  };
  try {
    const threads = await seedDiffThreads(artifacts, isolated.directories, provider.endpoint);
    const harness = await compileConPtyHarness(artifacts, isolated.runRoot);
    const conptyOptions = [
      {
        name: "empty-thread",
        matches: /当前会话还没有回复记录/u,
      },
      {
        name: "no-change-turn",
        prompt: noChangeMarker,
        finalMarker: noChangeFinal,
        matches: /本次回复没有文件修改/u,
      },
      {
        name: "unicode-file-diff",
        prompt: writeMarker,
        finalMarker: writeFinal,
        matches: /\+Ja 本轮差异：中文和 Emoji 🙂/u,
        windowsTerminalMatches: /\+Ja本轮差异：中文和Emoji/u,
      },
      {
        name: "large-file-diff",
        prompt: largeMarker,
        finalMarker: largeFinal,
        matches: /\+差异正文🙂春日β/u,
        truncationMatches: /差异详情已达 96 KiB 展示上限；后续文件未读取/u,
        large: true,
      },
    ];
    const runRoot = isolated.runRoot;
    const singleWriteOnly = process.env.JA_DIFF_SINGLE_WRITE_ONLY === "1";
    const selectedConptyOptions = singleWriteOnly
      ? conptyOptions.filter((options) => options.name === "unicode-file-diff")
      : conptyOptions;
    for (const options of conptyOptions) {
      options.runRoot = runRoot;
      options.useBundledSidecar = useBundledSidecar;
      options.redactionSecrets = [profileSecret, provider.endpoint, isolated.profileRoot, isolated.directories.home];
    }
    evidence.selectedScenarios = selectedConptyOptions.map(({ name }) => name);
    evidence.cliSidecarMode = useBundledSidecar ? "bundled-native" : "debug-java-jar";
    if (useBundledSidecar) evidence.nativeSidecar = await inspectBundledNativeSidecar(artifacts.cli);
    evidence.conpty = {};
    for (const options of selectedConptyOptions) {
      evidence.conpty[options.name] = await runConPtyScenario(
        artifacts,
        isolated.directories,
        harness,
        threads[options.name === "empty-thread" ? "empty" : options.name === "no-change-turn" ? "noChange" : options.name === "unicode-file-diff" ? "write" : "large"],
        options,
      );
    }
    const writeBack = await readFile(join(isolated.directories.workspace, writePath), "utf8");
    assert.equal(writeBack, writeContent, "the loopback write Tool must create the exact Unicode fixture");
    evidence.files = { unicodeWriteBytes: Buffer.byteLength(writeBack) };
    if (selectedConptyOptions.some((options) => options.name === "large-file-diff")) {
      const largeBack = await readFile(join(isolated.directories.workspace, largePath), "utf8");
      assert.equal(largeBack, largeContent, "the loopback write Tool must create the full long fixture");
      evidence.files.largeWriteBytes = Buffer.byteLength(largeBack);
    }

    const wt = await terminalHarness.resolveWindowsTerminal();
    terminalHelper = await terminalHarness.writeWindowHelper();
    evidence.terminal.unicodeFileDiff = await runWindowsTerminalScenario(
      artifacts,
      isolated.directories,
      threads.write,
      terminalHelper,
      wt,
      {
        name: "unicode-file-diff",
        matches: (visible) => visible.includes("Ja 本轮差异") && visible.includes("第二行 β✅"),
        normalizedMatches: /\+Ja本轮差异：中文和Emoji🙂/u,
        windowsTerminalMatches: /\+Ja本轮差异：中文和Emoji/u,
        runRoot,
        useBundledSidecar,
      },
    );
    evidence.status = "CLI_DIFF_CONPTY_AND_WINDOWS_TERMINAL_OK";
  } catch (error) {
    primaryFailure = error instanceof Error ? error : new Error(String(error));
    evidence.status = "failed";
    evidence.failure = sanitizeDiagnosticText(
      primaryFailure.message,
      [profileSecret, provider.endpoint, isolated.profileRoot, isolated.directories.home],
    );
    if (primaryFailure.diagnosticPath) evidence.conptyFailureDiagnosticsPath = primaryFailure.diagnosticPath;
  } finally {
    await provider.close().catch(() => undefined);
    if (primaryFailure) {
      try {
        evidence.nativeBootstrapDiagnostics = await captureIsolatedBootstrapLogs(
          isolated.directories,
          [profileSecret, provider.endpoint, isolated.profileRoot, isolated.directories.home],
        );
      } catch (error) {
        evidence.nativeBootstrapDiagnosticsFailure = String(error?.message ?? error);
      }
    }
    if (useBundledSidecar) {
      try {
        evidence.nativeSidecarShutdown = await stopBundledNativeServer(artifacts, isolated.directories);
      } catch (error) {
        const message = sanitizeDiagnosticText(
          error?.message ?? error,
          [profileSecret, provider.endpoint, isolated.profileRoot, isolated.directories.home],
        );
        evidence.nativeSidecarShutdownFailure = message;
        runtimeOwnersReleased = false;
        if (!primaryFailure) primaryFailure = error instanceof Error ? error : new Error(String(error));
        evidence.status = "failed";
      }
    }
    try {
      appServersStopped = await terminalHarness.stopIsolatedAppServers(artifacts, isolated.directories);
    } catch (error) {
      evidence.appServerCleanupFailure = String(error?.message ?? error);
      runtimeOwnersReleased = false;
      if (!primaryFailure) primaryFailure = error instanceof Error ? error : new Error(String(error));
      evidence.status = "failed";
    }
    evidence.isolatedAppServerProcessCountStopped = appServersStopped.length;
    if (runtimeOwnersReleased) {
      try {
        await removeOwnedProfile(isolated.runRoot, isolated.profileRoot);
        evidence.isolatedProfileRemoved = true;
      } catch (error) {
        evidence.isolatedProfileRemoved = false;
        evidence.profileCleanupFailure = String(error?.message ?? error);
        if (!primaryFailure) primaryFailure = error instanceof Error ? error : new Error(String(error));
        evidence.status = "failed";
      }
    } else {
      evidence.isolatedProfileRemoved = false;
      evidence.profileCleanupFailure = "deferred because an isolated runtime owner did not confirm shutdown";
    }
    evidence.evidenceDirectory = evidenceRoot;
    await writeFile(join(evidenceRoot, "acceptance-report.json"), `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  }
  if (primaryFailure) throw primaryFailure;
  return evidence;
}

/** 脚本直接运行时输出精简状态和证据路径；不打印隔离 endpoint、凭据或用户文件内容。 @author kongweiguang */
async function main() {
  const result = await runCliDiffAcceptance();
  process.stdout.write(`${JSON.stringify({ status: result.status, evidenceDirectory: result.evidenceDirectory, screenshots: Object.values(result.terminal).map((item) => item.screenshot).filter(Boolean) }, null, 2)}\n`);
}

if (exec === resolve(fileURLToPath(import.meta.url))) {
  await main();
}

/** 轮询真实进程和窗口状态时让出事件循环；每个等待点另有总时限。 @author kongweiguang */
function delay(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}
