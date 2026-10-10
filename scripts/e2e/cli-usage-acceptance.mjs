// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawn, execFile } from "node:child_process";
import { createServer } from "node:http";
import { mkdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { tmpdir } from "node:os";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  createIsolatedDirectories,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
} from "./real-provider-smoke.mjs";
import { providerStream } from "./media-schema-loopback.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const evidenceRoot = resolve(process.env.JA_TEST_EVIDENCE_ROOT ?? join(repoRoot, "target", "cli-usage-acceptance"));
const execFileAsync = promisify(execFile);
const placeholderSecret = "ja-cli-usage-loopback-placeholder";
const providerId = "provider_cli_usage_acceptance";
const modelId = "model_cli_usage_acceptance";
const credentialId = "cred_cli_usage_acceptance";
const userMarker = "JA_CLI_USAGE_ACCEPTANCE";
const finalText = "用量验收完成 🌱 中文与 Emoji 显示正常";
const usageFact = Object.freeze({
  input_tokens: 24_000,
  input_tokens_details: { cached_tokens: 22_080 },
  output_tokens: 449,
  total_tokens: 24_449,
});
const delay = (milliseconds) => new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));

/** 首个请求前固定栏应明确呈现未知占位符；该断言防止空线程被误显示为零用量或沿用上次请求数据。 @author kongweiguang */
function assertUnknownUsageFooter(visibleRows, columns, description) {
  assert.ok(columns >= 78, `${description} must use a wide terminal so the full fixed usage footer is visible`);
  const footer = visibleRows.slice(-4).join("").replaceAll(/\s/gu, "");
  assert.match(footer, /累计—.*输入—.*输出—.*含缓存读—.*上下文—\/——%/u,
    `${description} must keep every fixed field visible with unknown values before the first request`);
  assert.doesNotMatch(footer, /约|已报告|正在读取|待刷新|未知/u,
    `${description} must use value placeholders instead of transient status wording`);
  return footer;
}

/** 按窗口实测字符列断言固定字段档位，避免宽度变化或估算数值影响底栏布局验收。 @author kongweiguang */
function assertMeasuredUsageFooterLayout(visibleRows, columns, description) {
  const footer = visibleRows.slice(-4).join("").replaceAll(/\s/gu, "");
  assert.doesNotMatch(footer, /约|已报告|正在读取|待刷新|未知/u,
    `${description} footer must keep status wording in /status rather than the fixed usage row`);
  if (columns >= 78) {
    assert.match(footer, /累计24k输入24k输出449含缓存读22k.*上下文24k\/1\.0M2\.4%/u, `${description} wide footer must show all fixed fields`);
  } else if (columns >= 63) {
    assert.match(footer, /累计24k输入24k输出449.*上下文24k\/1\.0M2\.4%/u, `${description} footer must keep input/output and full context`);
    assert.doesNotMatch(footer, /含缓存读/u, `${description} footer must omit cache detail in the 63-77 column band`);
  } else if (columns >= 41) {
    assert.match(footer, /累计24k.*上下文24k\/1\.0M2\.4%/u, `${description} footer must show cumulative and full context`);
    assert.doesNotMatch(footer, /输入24k|输出449|含缓存读/u, `${description} footer must omit directional details in the 41-62 column band`);
  } else if (columns >= 29) {
    assert.match(footer, /累计24k.*上下文2\.4%/u, `${description} footer must show cumulative and context percentage`);
    assert.doesNotMatch(footer, /输入24k|输出449|含缓存读|上下文24k\/1\.0M/u, `${description} footer must use the compact fixed layout`);
  } else if (columns >= 17) {
    assert.match(footer, /上下文2\.4%/u, `${description} footer must show only context percentage`);
    assert.doesNotMatch(footer, /累计24k|输入24k|输出449|含缓存读|上下文24k\/1\.0M/u, `${description} footer must omit values that do not fit`);
  } else if (columns >= 11) {
    assert.match(footer, /2\.4%/u, `${description} footer must retain the percentage at minimum supported width`);
    assert.doesNotMatch(footer, /累计24k|输入24k|输出449|含缓存读|上下文/u, `${description} footer must show only the percentage`);
  } else {
    assert.doesNotMatch(footer, /累计24k|输入24k|输出449|含缓存读|上下文|2\.4%/u, `${description} footer must be empty below its minimum width`);
  }
}

/** 验收证据根可按轮次隔离，但必须位于仓库 target 下，避免脚本把截图或状态写到调用方目录。 @author kongweiguang */
export async function prepareEvidenceDirectory() {
  const targetRoot = resolve(repoRoot, "target");
  const evidenceRelativePath = relative(targetRoot, evidenceRoot);
  assert.ok(evidenceRelativePath && evidenceRelativePath !== ".." && !evidenceRelativePath.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`) && !isAbsolute(evidenceRelativePath), "CLI usage evidence must stay in a dedicated directory beneath repository target");
  await mkdir(evidenceRoot, { recursive: true });
  const root = await stat(evidenceRoot);
  assert.ok(root.isDirectory(), "CLI usage evidence root must be a directory");
  return evidenceRoot;
}

/** 显式锁定隔离 profile、当前 CLI 与 JDK 25 JAR，避免真实 Provider 和宿主配置进入验收。 @author kongweiguang */
async function resolveArtifacts() {
  const cli = resolve(process.env.JA_TEST_CLI ?? join(repoRoot, "target", "debug", "ja.exe"));
  const jar = resolve(
    process.env.JA_TEST_JAR ?? join(repoRoot, "ja-agent", "ja-app-server", "target", "ja-app-server.jar"),
  );
  const java = resolve(
    process.env.JA_TEST_JAVA ?? "C:\\Users\\24052\\.jdks\\liberica-25.0.2\\bin\\java.exe",
  );
  for (const path of [cli, jar, java]) {
    const metadata = await stat(path);
    assert.ok(metadata.isFile() && metadata.size > 0, "required CLI usage artifact is missing or empty");
  }
  const version = await execFileAsync(java, ["-version"], { windowsHide: true, maxBuffer: 64 * 1024 });
  assert.match(`${version.stdout}\n${version.stderr}`, /version\s+"25(?:[.]|"|\s)/u);
  const cliSha256 = createHash("sha256").update(await readFile(cli)).digest("hex").toUpperCase();
  const jarSha256 = createHash("sha256").update(await readFile(jar)).digest("hex").toUpperCase();
  return { cli, jar, java, cliSha256, jarSha256 };
}

/** 解析 JA-RPC 正常结果；失败时只报告稳定方法名和错误分类，不回显用户或凭据数据。 @author kongweiguang */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = frame.error?.data?.errorCode ?? frame.error?.data?.category ?? "RPC_ERROR";
    throw new Error(`${operation} failed: ${String(code)}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 只投影 thread/read 中的集合长度与 user/assistant 角色计数，不把消息正文写入诊断证据。 @author kongweiguang */
function projectThreadHistoryCounts(history) {
  const collectionNames = new Set(["turns", "items", "messages", "entries", "events", "timeline", "history"]);
  const roles = { user: 0, assistant: 0 };
  const collections = {};
  const visit = (value, path, depth) => {
    if (depth > 4 || value === null || typeof value !== "object") return;
    if (Array.isArray(value)) {
      if (path && collectionNames.has(path.split(".").at(-1))) collections[path] = value.length;
      for (const item of value.slice(0, 512)) visit(item, path, depth + 1);
      return;
    }
    if (value.role === "user" || value.role === "assistant") roles[value.role] += 1;
    for (const [key, nested] of Object.entries(value)) {
      if (collectionNames.has(key) && (Array.isArray(nested) || (nested && typeof nested === "object"))) {
        visit(nested, path ? `${path}.${key}` : key, depth + 1);
      }
    }
  };
  visit(history, "", 0);
  return { collections, roles };
}

/** 仅回收本验收创建的 JSONL App Server；ready 进程必须以退出码 0 优雅退出，失败操作仍记录强制回收结果。 @author kongweiguang */
async function closeUsageFixtureSession(session, stage, ready, operationFailed) {
  const processId = Number.isSafeInteger(session.child?.pid) ? session.child.pid : null;
  let shutdownErrorType;
  let shutdownRequested = false;
  if (ready && !session.exited) {
    shutdownRequested = true;
    try {
      await session.shutdown();
    } catch (error) {
      shutdownErrorType = error instanceof Error ? error.name : "UnknownError";
    }
  }

  const forcedTerminationRequested = !session.exited;
  let forceCloseErrorType;
  try {
    await session.forceClose();
  } catch (error) {
    forceCloseErrorType = error instanceof Error ? error.name : "UnknownError";
  }
  const exit = session.exitResult;
  const evidence = {
    stage,
    processId,
    ready,
    shutdownRequested,
    shutdownErrorType: shutdownErrorType ?? null,
    forcedTerminationRequested,
    forceCloseErrorType: forceCloseErrorType ?? null,
    processExited: session.exited,
    exitCode: exit?.code ?? null,
    signal: exit?.signal ?? null,
  };
  const gracefulExit = ready
    && shutdownRequested
    && shutdownErrorType === undefined
    && !forcedTerminationRequested
    && forceCloseErrorType === undefined
    && evidence.processExited
    && evidence.exitCode === 0
    && evidence.signal === null;
  evidence.gracefulExit = gracefulExit;
  if (!operationFailed && !gracefulExit) {
    const error = new Error(`${stage} App Server did not complete a graceful exit (pid=${processId ?? "unknown"}, exitCode=${evidence.exitCode ?? "unknown"}, signal=${evidence.signal ?? "none"}, forced=${forcedTerminationRequested})`);
    error.sessionLifecycle = evidence;
    throw error;
  }
  return evidence;
}

/** 用 Java 25 和随机 Unicode profile 创建可信 Thread；准备失败保留为主错误并附上清理证据，无准备失败时才报告真实关闭错误。 @author kongweiguang */
async function seedThread(artifacts, directories, endpoint) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: placeholderSecret,
    endpoint,
  });
  let ready = false;
  let result;
  let operationError;
  let cleanupError;
  try {
    const initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams()),
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

    const configuration = rpcResult(await session.request("configuration/read", {}), "configuration/read");
    const document = providerConfigurationDocument({
      endpoint,
      name: "Ja CLI usage isolated loopback",
      api: "openai_responses",
      model: "gpt-6-luna",
      selectedProviderId: providerId,
      selectedModelId: modelId,
      selectedCredentialId: credentialId,
      reasoningLevel: "xhigh",
    });
    document.providers[0].models[0].capabilities.context_window_tokens = 1_000_000;
    rpcResult(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        document,
      }),
      "configuration/replace",
    );
    rpcResult(
      await session.request("credential/set", {
        credentialId,
        secret: placeholderSecret,
        expectedVersion: configuration.cas.credentialVersion,
      }),
      "credential/set",
    );
    const opened = rpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Ja CLI 用量验收",
      }),
      "workspace/open",
    );
    rpcResult(
      await session.request("workspace/set-trust", {
        workspaceId: opened.workspaceId,
        trust: "trusted",
      }),
      "workspace/set-trust",
    );
    const created = rpcResult(
      await session.request("thread/create", {
        cwd: directories.workspace,
        title: "用量验收 · 中文 Emoji 🌱",
        providerId,
        modelId,
        reasoningLevel: "xhigh",
        accessMode: "approval_required",
        collaborationMode: "default",
      }),
      "thread/create",
    );
    assert.match(created.threadId, /^thr_[A-Za-z0-9_.-]+$/u);
    result = { threadId: created.threadId, workspaceId: opened.workspaceId };
  } catch (error) {
    operationError = error;
  } finally {
    try {
      const lifecycle = await closeUsageFixtureSession(session, "seed-thread", ready, operationError !== undefined);
      if (result) result.appServerSession = lifecycle;
      if (operationError) operationError.appServerSession = lifecycle;
    } catch (error) {
      if (operationError) operationError.appServerSession = error.sessionLifecycle ?? { cleanupErrorType: error.name };
      else cleanupError = error;
    }
  }
  if (operationError !== undefined) throw operationError;
  if (cleanupError !== undefined) throw cleanupError;
  return result;
}

/** 仅对 OpenAI Responses loopback 请求回送固定账本事实，并在发送前设置可控 gate。 @author kongweiguang */
async function startUsageProvider() {
  let releaseResponse;
  let notifyRequest;
  let completedResponses = 0;
  const responseWaiters = new Set();
  const released = new Promise((resolvePromise) => (releaseResponse = resolvePromise));
  const received = new Promise((resolvePromise) => (notifyRequest = resolvePromise));
  let requestCount = 0;
  const requestSummaries = [];
  const server = createServer(async (request, response) => {
    try {
      if (request.method !== "POST" || request.url !== "/v1/responses") {
        response.writeHead(404).end();
        return;
      }
      if (request.headers.authorization !== `Bearer ${placeholderSecret}`) {
        response.writeHead(401).end();
        return;
      }
      const chunks = [];
      let requestBytes = 0;
      for await (const chunk of request) {
        requestBytes += chunk.length;
        if (requestBytes > 8 * 1024 * 1024) throw new Error("loopback request exceeds 8 MiB");
        chunks.push(chunk);
      }
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      requestCount += 1;
      const serializedRequest = JSON.stringify(payload);
      const markerFound = serializedRequest.includes(userMarker);
      const summaryInstructionHint = /\b(?:summar(?:y|ize|ise)|compact(?:ion)?)\b/iu.test(serializedRequest);
      const requestSummary = {
        requestCount,
        requestBytes,
        markerFound,
        model: typeof payload.model === "string" ? payload.model.slice(0, 80) : null,
        inputItemCount: Array.isArray(payload.input) ? payload.input.length : null,
        requestPurpose: requestCount === 1 && markerFound
          ? "ordinary-user-turn"
          : summaryInstructionHint
          ? "summary-or-compaction-candidate"
          : "unclassified-secondary-call",
        summaryInstructionHint,
      };
      requestSummaries.push(requestSummary);
      notifyRequest(requestSummary);
      await released;
      const id = `resp_ja_cli_usage_${requestCount}`;
      const requestUsage = requestCount === 1
        ? usageFact
        : { input_tokens: 120, input_tokens_details: { cached_tokens: 0 }, output_tokens: 24, total_tokens: 144 };
      const stream = addUsageToCompletedResponse(
        providerStream("openai_responses", id, undefined, requestCount === 1 ? finalText : "已保留对话要点。"),
        requestUsage,
      );
      response.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-cache",
        connection: "keep-alive",
      });
      response.end(stream, () => {
        completedResponses = requestCount;
        for (const waiter of [...responseWaiters]) {
          if (completedResponses < waiter.count) continue;
          clearTimeout(waiter.timer);
          responseWaiters.delete(waiter);
          waiter.resolve({ requestCount: completedResponses });
        }
      });
    } catch {
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: { message: "isolated usage fixture rejected request" } }));
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
    received,
    waitForResponses(count, timeoutMs = 30_000) {
      if (completedResponses >= count) return Promise.resolve({ requestCount: completedResponses });
      return new Promise((resolvePromise, rejectPromise) => {
        const waiter = {
          count,
          resolve: resolvePromise,
          timer: setTimeout(() => {
            responseWaiters.delete(waiter);
            rejectPromise(new Error("Responses fixture did not finish streaming"));
          }, timeoutMs),
        };
        responseWaiters.add(waiter);
      });
    },
    release() {
      releaseResponse();
    },
    snapshotRequests() {
      return requestSummaries.map((requestSummary) => ({ ...requestSummary, responseCompleted: requestSummary.requestCount <= completedResponses }));
    },
    async close() {
      releaseResponse();
      server.closeAllConnections();
      await new Promise((resolvePromise) => server.close(() => resolvePromise()));
    },
  };
}

/** 给现有 Responses SSE 的唯一终态附加供应商计量，保留其协议事件顺序和消息内容。 @author kongweiguang */
function addUsageToCompletedResponse(stream, usage) {
  const frames = stream.split("\n\n");
  const finalIndex = frames.findLastIndex((frame) => frame.includes("response.completed"));
  assert.ok(finalIndex >= 0, "Responses loopback stream must end with response.completed");
  const dataLine = frames[finalIndex].split("\n").find((line) => line.startsWith("data: "));
  assert.ok(dataLine, "Responses completion event must include JSON data");
  const payload = JSON.parse(dataLine.slice("data: ".length));
  payload.response.usage = structuredClone(usage);
  frames[finalIndex] = frames[finalIndex].replace(dataLine, `data: ${JSON.stringify(payload)}`);
  return frames.join("\n\n");
}

/** 通过 PowerShell 7 验证短时独立 Windows Terminal 窗口能力，不回退到静态模拟截图。 @author kongweiguang */
export async function resolveWindowsTerminal() {
  assert.equal(process.platform, "win32", "real Windows Terminal acceptance runs only on Windows");
  const candidates = [
    process.env.WT_EXE,
    process.env.LOCALAPPDATA ? join(process.env.LOCALAPPDATA, "Microsoft", "WindowsApps", "wt.exe") : undefined,
  ].filter((value) => typeof value === "string" && value.length > 0);
  for (const candidate of candidates) {
    try {
      const metadata = await stat(candidate);
      if (metadata.isFile()) return resolve(candidate);
    } catch {
      // Continue through the explicit candidates; do not resolve another user's PATH alias implicitly.
    }
  }
  const lookup = await execFileAsync("pwsh.exe", ["-NoLogo", "-NoProfile", "-Command", "(Get-Command wt.exe -ErrorAction SilentlyContinue).Source"], {
    windowsHide: true,
    timeout: 5_000,
  }).catch(() => ({ stdout: "" }));
  const path = lookup.stdout.trim();
  if (path.length === 0) throw new Error("Windows Terminal wt.exe is unavailable on this host");
  return path;
}

/** 写入隔离 launcher；stdout 保留真实 Console，stderr 只暂存到独立文件以便失败时脱敏提取 Ja 诊断，并验证退出后的 Shell 基线。 @author kongweiguang */
async function writeTerminalLauncher(artifacts, directories, threadId, title, initialPrompt = undefined, captureCliStderr = false) {
  const script = join(evidenceRoot, `${title}.ps1`);
  const started = join(evidenceRoot, `${title}.started`);
  const finished = join(evidenceRoot, `${title}.finished.json`);
  const cliStderr = captureCliStderr ? join(evidenceRoot, `${title}.cli-stderr.raw`) : undefined;
  const cliErrorDiagnostic = captureCliStderr ? join(evidenceRoot, `${title}.cli-error-safe.json`) : undefined;
  const diagnostic = join(evidenceRoot, `${title}.console.json`);
  const cliState = join(evidenceRoot, `${title}.cli-state.json`);
  const bufferRequest = join(evidenceRoot, `${title}.buffer-request.json`);
  const bufferAcknowledgement = join(evidenceRoot, `${title}.buffer-ack.json`);
  const shellMarker = `Ja用量验收恢复_${Date.now()} 🌱`;
  const shellMarkerResult = join(evidenceRoot, `${title}.shell-marker.result`);
  const shellExitBufferSnapshot = join(evidenceRoot, `${title}.shell-exit-buffer.json`);
  const shellExitConsoleDiagnostic = join(evidenceRoot, `${title}.shell-exit-console.json`);
  const shellBufferSnapshot = join(evidenceRoot, `${title}.shell-restored-buffer.json`);
  const shellConsoleDiagnostic = join(evidenceRoot, `${title}.shell-restored-console.json`);
  const cliArguments = [
    "'resume'",
    psLiteral(threadId),
    ...(initialPrompt === undefined ? [] : ["'--prompt'", psLiteral(initialPrompt)]),
  ].join(", ");
  const launcher = [
    "$ErrorActionPreference = 'Stop'",
    `$env:JA_HOME = ${psLiteral(directories.home)}`,
    `$env:JA_DEBUG_JAVA = ${psLiteral(artifacts.java)}`,
    `$env:JA_DEBUG_JAR = ${psLiteral(artifacts.jar)}`,
    "Add-Type -TypeDefinition @'",
    "using System;",
    "using System.Text;",
    "using System.Collections.Generic;",
    "using System.Runtime.InteropServices;",
    "public static class JaUsageConsoleMode {",
    "  [StructLayout(LayoutKind.Sequential)] struct Coord { public short X; public short Y; }",
    "  [StructLayout(LayoutKind.Sequential)] struct SmallRect { public short Left; public short Top; public short Right; public short Bottom; }",
    "  [StructLayout(LayoutKind.Explicit, CharSet=CharSet.Unicode, Size=4)] struct CharInfo { [FieldOffset(0)] public char UnicodeChar; [FieldOffset(2)] public ushort Attributes; }",
    "  [StructLayout(LayoutKind.Sequential)] struct ConsoleScreenBufferInfo { public Coord Size; public Coord CursorPosition; public ushort Attributes; public SmallRect Window; public Coord MaximumWindowSize; }",
    "  [DllImport(\"kernel32.dll\", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateFileW(string name, uint access, uint shareMode, IntPtr securityAttributes, uint creationDisposition, uint flagsAndAttributes, IntPtr templateFile);",
    "  [DllImport(\"kernel32.dll\", SetLastError=true)] static extern bool CloseHandle(IntPtr handle);",
    "  [DllImport(\"kernel32.dll\", SetLastError = true)] static extern bool GetConsoleMode(IntPtr handle, out uint mode);",
    "  [DllImport(\"kernel32.dll\", SetLastError=true)] static extern bool GetConsoleScreenBufferInfo(IntPtr handle, out ConsoleScreenBufferInfo info);",
    "  [DllImport(\"kernel32.dll\", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool ReadConsoleOutputW(IntPtr handle, [Out] CharInfo[] buffer, Coord bufferSize, Coord bufferCoordinate, ref SmallRect readRegion);",
    "  static IntPtr Open(string name) { var handle = CreateFileW(name, 0x80000000 | 0x40000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero); if (handle == IntPtr.Zero || handle == new IntPtr(-1)) throw new InvalidOperationException(\"cannot open console device \" + name + \"; win32Error=\" + Marshal.GetLastWin32Error()); return handle; }",
    "  // @author kongweiguang",
    "  // Read mode through explicit console device handles because PowerShell's inherited standard handles may be redirected pipes.",
    "  public static object Read(int standardHandle) { var handle = Open(standardHandle == -10 ? \"CONIN$\" : \"CONOUT$\"); try { uint mode; if (!GetConsoleMode(handle, out mode)) throw new InvalidOperationException(\"cannot read console mode; win32Error=\" + Marshal.GetLastWin32Error()); return mode; } finally { CloseHandle(handle); } }",
    "  // @author kongweiguang",
    "  // Read the active visible console buffer directly so marker checks are independent of incomplete RawUI projections.",
    "  public static object Snapshot() { var handle = Open(\"CONOUT$\"); try { ConsoleScreenBufferInfo info; if (!GetConsoleScreenBufferInfo(handle, out info)) throw new InvalidOperationException(\"cannot read console buffer; win32Error=\" + Marshal.GetLastWin32Error()); int width = info.Window.Right - info.Window.Left + 1; int height = info.Window.Bottom - info.Window.Top + 1; var rows = new List<string>(height); for (short y = info.Window.Top; y <= info.Window.Bottom; y++) { var cells = new CharInfo[width]; var bufferSize = new Coord { X = (short)width, Y = 1 }; var bufferCoordinate = new Coord { X = 0, Y = 0 }; var readRegion = new SmallRect { Left = info.Window.Left, Top = y, Right = info.Window.Right, Bottom = y }; if (!ReadConsoleOutputW(handle, cells, bufferSize, bufferCoordinate, ref readRegion)) throw new InvalidOperationException(\"cannot read console row \" + y + \"; win32Error=\" + Marshal.GetLastWin32Error()); var line = new StringBuilder(width); foreach (var cell in cells) { if ((cell.Attributes & 0x0200) != 0) continue; line.Append(cell.UnicodeChar == '\\0' ? ' ' : cell.UnicodeChar); } rows.Add(line.ToString().TrimEnd()); } return new { windowSize = new { width, height }, bufferSize = new { width = info.Size.X, height = info.Size.Y }, cursorPosition = new { x = info.CursorPosition.X, y = info.CursorPosition.Y }, windowPosition = new { x = info.Window.Left, y = info.Window.Top }, visibleRows = rows.ToArray() }; } finally { CloseHandle(handle); } }",
    "}",
    "'@",
    "function Get-ConsoleDiagnostic {",
    "  # @author kongweiguang",
    "  # Native console dimensions and modes distinguish a renderer/input transport issue from a malformed WT launch.",
    "  $screen = [JaUsageConsoleMode]::Snapshot()",
    "  return [ordered]@{",
    "    windowSize = $screen.windowSize",
    "    bufferSize = $screen.bufferSize",
    "    cursorPosition = $screen.cursorPosition",
    "    inputMode = [JaUsageConsoleMode]::Read(-10)",
    "    outputMode = [JaUsageConsoleMode]::Read(-11)",
    "    inputRedirected = [Console]::IsInputRedirected",
    "    outputRedirected = [Console]::IsOutputRedirected",
    "    inputCodePage = [Console]::InputEncoding.CodePage",
    "    outputCodePage = [Console]::OutputEncoding.CodePage",
    "    inheritedConsoleEncoding = $script:inheritedConsoleEncoding",
    "    fixtureConsoleEncoding = $script:fixtureConsoleEncoding",
    "    inputEncoding = [Console]::InputEncoding.WebName",
    "    outputEncoding = [Console]::OutputEncoding.WebName",
    "    wtSessionPresent = -not [string]::IsNullOrWhiteSpace($env:WT_SESSION)",
    "    inheritedColorEnvironment = @{ noColorPresent = Test-Path Env:NO_COLOR; noColor = if (Test-Path Env:NO_COLOR) { $env:NO_COLOR } else { $null }; termPresent = Test-Path Env:TERM; term = $env:TERM }",
    "  }",
    "}",
    "function Get-ConsoleBufferSnapshot([string]$Label) {",
    "  # @author kongweiguang",
    "  # Read this isolated WT host's native visible rectangle to retain real console cells when RawUI returns an empty view.",
    "  $screen = [JaUsageConsoleMode]::Snapshot()",
    "  return [pscustomobject]@{ label = $Label; capturedAtUtc = [DateTime]::UtcNow.ToString('O'); windowSize = $screen.windowSize; bufferSize = $screen.bufferSize; windowPosition = $screen.windowPosition; cursorPosition = $screen.cursorPosition; visibleRows = @($screen.visibleRows) }",
    "}",
    "$script:inheritedConsoleEncoding = @{ inputCodePage = [Console]::InputEncoding.CodePage; outputCodePage = [Console]::OutputEncoding.CodePage }",
    "$script:fixtureConsoleEncoding = @{ inputCodePage = 65001; outputCodePage = 65001 }",
    "$fixtureUtf8 = [System.Text.UTF8Encoding]::new($false)",
    "[Console]::InputEncoding = $fixtureUtf8",
    "[Console]::OutputEncoding = $fixtureUtf8",
    "$consoleDiagnostic = Get-ConsoleDiagnostic",
    "$env:NO_COLOR = $null",
    "$env:TERM = 'xterm-256color'",
    "$consoleDiagnostic.colorEnvironmentForJa = @{ noColorPresent = Test-Path Env:NO_COLOR; term = $env:TERM }",
    `[System.IO.File]::WriteAllText(${psLiteral(diagnostic)}, ($consoleDiagnostic | ConvertTo-Json -Compress -Depth 4), [System.Text.UTF8Encoding]::new($false))`,
    `[System.IO.File]::WriteAllText(${psLiteral(started)}, [string]$PID, [System.Text.UTF8Encoding]::new($false))`,
    "$cliExit = 1",
    "try {",
    `  $cliProcess = Start-Process -FilePath ${psLiteral(artifacts.cli)} -ArgumentList @(${cliArguments}) -NoNewWindow -PassThru -WorkingDirectory ${psLiteral(directories.workspace)}${captureCliStderr ? ` -RedirectStandardError ${psLiteral(cliStderr)}` : ""}`,
    `  [System.IO.File]::WriteAllText(${psLiteral(cliState)}, (@{ processId = $cliProcess.Id; executablePath = ${psLiteral(artifacts.cli)} } | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))`,
    "  $lastBufferRequestId = 0",
    "  while ($true) {",
    "    $cliProcess.Refresh()",
    `    if (Test-Path -LiteralPath ${psLiteral(bufferRequest)}) {`,
    "      try {",
    `        $request = [System.IO.File]::ReadAllText(${psLiteral(bufferRequest)}) | ConvertFrom-Json`,
    "        $requestId = [long]$request.id",
    "        $label = [string]$request.label",
    "        if ($requestId -gt $lastBufferRequestId -and $label -match '^[A-Za-z0-9._-]{1,80}$') {",
    "          $snapshot = Get-ConsoleBufferSnapshot $label",
    `          $snapshotPath = Join-Path ${psLiteral(evidenceRoot)} (${psLiteral(`${title}.buffer-`)} + $label + '.json')`,
    "          [System.IO.File]::WriteAllText($snapshotPath, ($snapshot | ConvertTo-Json -Compress -Depth 5), [System.Text.UTF8Encoding]::new($false))",
    `          $acknowledgement = [ordered]@{ id = $requestId; path = $snapshotPath; error = $null }; [System.IO.File]::WriteAllText(${psLiteral(bufferAcknowledgement)}, ($acknowledgement | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))`,
    "          $lastBufferRequestId = $requestId",
    "        }",
    "      } catch {",
    `        $acknowledgement = [ordered]@{ id = [long]$request.id; path = $null; error = $_.Exception.Message }; [System.IO.File]::WriteAllText(${psLiteral(bufferAcknowledgement)}, ($acknowledgement | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))`,
    "      }",
    "    }",
    "    if ($cliProcess.HasExited) { break }",
    "    Start-Sleep -Milliseconds 100",
    "  }",
    "  $cliProcess.WaitForExit()",
    "  $cliExit = $cliProcess.ExitCode",
    // Run after the native CLI has returned so the same real PowerShell proves console restoration without long-path keystroke injection.
    "  if ($cliExit -eq 0) {",
    "    if ($consoleDiagnostic.inheritedColorEnvironment.noColorPresent) { $env:NO_COLOR = $consoleDiagnostic.inheritedColorEnvironment.noColor } else { Remove-Item Env:NO_COLOR -ErrorAction SilentlyContinue }",
    "    if ($consoleDiagnostic.inheritedColorEnvironment.termPresent) { $env:TERM = $consoleDiagnostic.inheritedColorEnvironment.term } else { Remove-Item Env:TERM -ErrorAction SilentlyContinue }",
    "    $shellExitConsole = Get-ConsoleDiagnostic",
    "    if ($shellExitConsole.inputCodePage -ne $script:fixtureConsoleEncoding.inputCodePage -or $shellExitConsole.outputCodePage -ne $script:fixtureConsoleEncoding.outputCodePage) { throw 'CLI exit did not restore the isolated UTF-8 console code-page baseline' }",
    "    $shellExitConsole.encodingRestoredToFixtureBaseline = $true",
    `    [System.IO.File]::WriteAllText(${psLiteral(shellExitConsoleDiagnostic)}, ($shellExitConsole | ConvertTo-Json -Compress -Depth 4), [System.Text.UTF8Encoding]::new($false))`,
    "    $shellExitBuffer = Get-ConsoleBufferSnapshot 'cli-exit-restored'",
    `    [System.IO.File]::WriteAllText(${psLiteral(shellExitBufferSnapshot)}, ($shellExitBuffer | ConvertTo-Json -Compress -Depth 5), [System.Text.UTF8Encoding]::new($false))`,
    `    [Console]::WriteLine(${psLiteral(shellMarker)})`,
    `    [System.IO.File]::WriteAllText(${psLiteral(shellMarkerResult)}, ${psLiteral(shellMarker)}, [System.Text.UTF8Encoding]::new($false))`,
    "    $shellConsoleDiagnostic = Get-ConsoleDiagnostic",
    `    [System.IO.File]::WriteAllText(${psLiteral(shellConsoleDiagnostic)}, ($shellConsoleDiagnostic | ConvertTo-Json -Compress -Depth 4), [System.Text.UTF8Encoding]::new($false))`,
    "    $shellBufferSnapshot = Get-ConsoleBufferSnapshot 'shell-restored'",
    `    [System.IO.File]::WriteAllText(${psLiteral(shellBufferSnapshot)}, ($shellBufferSnapshot | ConvertTo-Json -Compress -Depth 5), [System.Text.UTF8Encoding]::new($false))`,
    "  }",
    "} finally {",
    `  [System.IO.File]::WriteAllText(${psLiteral(finished)}, (@{ processId = $PID; exitCode = $cliExit } | ConvertTo-Json -Compress), [System.Text.UTF8Encoding]::new($false))`,
    "}",
  ].join("\r\n");
  await writeFile(script, launcher, "utf8");
  return { script, started, finished, diagnostic, cliState, ...(captureCliStderr ? { cliStderr, cliErrorDiagnostic } : {}), bufferRequest, bufferAcknowledgement, bufferRequestId: 0, shellMarker, shellMarkerResult, shellExitBufferSnapshot, shellExitConsoleDiagnostic, shellBufferSnapshot, shellConsoleDiagnostic, title, initialPrompt };
}

/** 用单引号 PS 字符串传递本地路径，防止空格、括号或反引号改变 launcher 的命令结构。 @author kongweiguang */
function psLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

/** 按随机标题打开短时 WT 窗口；只有全量用量验收捕获 stderr，stdout 仍走 Console，避免其他 runner 留下不必要的原始日志。 @author kongweiguang */
async function launchTerminal(wt, artifacts, directories, threadId, scheme, helper, initialPrompt = undefined, captureCliStderr = false) {
  const title = `JaUsage-${process.pid}-${Date.now()}-${scheme.replace(/[^A-Za-z]/gu, "")}`;
  const launcher = await writeTerminalLauncher(artifacts, directories, threadId, title, initialPrompt, captureCliStderr);
  const launchForegroundHandle = await readForegroundHandle(helper);
  const child = spawn(
    wt,
    [
      "-w", "new", "new-tab", "--title", title, "--suppressApplicationTitle", "--colorScheme", scheme,
      "--startingDirectory", directories.workspace,
      "pwsh.exe", "-NoLogo", "-NoProfile", "-NoExit", "-File", launcher.script,
    ],
    { cwd: directories.workspace, windowsHide: false, stdio: "ignore" },
  );
  const spawnFailure = new Promise((_, rejectPromise) => child.once("error", rejectPromise));
  await Promise.race([delay(500), spawnFailure]);
  const window = { ...launcher, child, launcherPid: child.pid, cliPath: artifacts.cli };
  const activeHandle = await readForegroundHandle(helper);
  const terminalState = JSON.parse(await runTerminalAction(helper, ["-Action", "probe", "-Title", title]));
  if (terminalState.exists && activeHandle === terminalState.handle) {
    await restoreOriginalForeground(helper, launchForegroundHandle).catch((error) => { window.focusRestoreError = error; });
  }
  return window;
}

/** 为深色验收隐藏启动请求创建 WT；不请求激活，但进程委托可能异步转移焦点，调用方在窗口就绪后恢复并核对原前台。 @author kongweiguang */
export async function launchTerminalWithoutActivation(wt, artifacts, directories, threadId, helper, initialPrompt) {
  const title = `JaUsage-${process.pid}-${Date.now()}-Campbell`;
  const launcher = await writeTerminalLauncher(artifacts, directories, threadId, title, initialPrompt);
  const child = spawn(
    wt,
    [
      "-w", "new", "new-tab", "--title", title, "--suppressApplicationTitle", "--colorScheme", "Campbell",
      "--startingDirectory", directories.workspace,
      "pwsh.exe", "-NoLogo", "-NoProfile", "-NoExit", "-File", launcher.script,
    ],
    { cwd: directories.workspace, windowsHide: true, stdio: "ignore" },
  );
  const spawnFailure = new Promise((_, rejectPromise) => child.once("error", rejectPromise));
  await Promise.race([delay(500), spawnFailure]);
  return { ...launcher, child, launcherPid: child.pid, cliPath: artifacts.cli };
}

/** 以绝对期限等待某个文件事实；失败时不留下隐式后台轮询。 @author kongweiguang */
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

/** 请求同一 WT Console 主机读取当前可视 cell；序号确认避免把上一次截图的 buffer 回执错配。 @author kongweiguang */
export async function readConsoleBuffer(window, label, timeoutMs = 10_000) {
  const requestId = ++window.bufferRequestId;
  const safeLabel = label.replace(/[^A-Za-z0-9._-]/gu, "-").slice(0, 80);
  await writeFile(window.bufferRequest, `${JSON.stringify({ id: requestId, label: safeLabel })}\n`, "utf8");
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const acknowledgement = JSON.parse(await readFile(window.bufferAcknowledgement, "utf8"));
      if (acknowledgement.id === requestId) {
        if (acknowledgement.error) throw new Error(`RawUI buffer capture failed: ${acknowledgement.error}`);
        const snapshot = JSON.parse(await readFile(acknowledgement.path, "utf8"));
        assert.equal(snapshot.label, safeLabel);
        assert.equal(snapshot.visibleRows.length, snapshot.windowSize.height);
        return snapshot;
      }
    } catch (error) {
      if (error?.code !== "ENOENT" && !(error instanceof SyntaxError)) throw error;
    }
    await delay(80);
  }
  throw new Error(`WT console buffer acknowledgement timed out for ${safeLabel}`);
}

/** 冷启动重连后按固定栏占位或真实上下文数字确认 UI 状态，避免标签常驻时把空值误判为用量就绪。 @author kongweiguang */
async function waitForTerminalConsoleState(window, label, requiredState, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  const readinessPath = join(evidenceRoot, `${label}-readiness.json`);
  let attempt = 0;
  let lastState;
  while (Date.now() < deadline) {
    const finished = await readFile(window.finished, "utf8").then((contents) => {
      try {
        return JSON.parse(contents);
      } catch {
        return undefined;
      }
    }).catch((error) => error?.code === "ENOENT" ? undefined : Promise.reject(error));
    if (Number.isInteger(finished?.exitCode)) {
      const error = new Error(`isolated CLI exited with code ${finished.exitCode} before reaching ${label}`);
      error.cliExit = { exitCode: finished.exitCode };
      throw error;
    }
    const snapshot = await readConsoleBuffer(window, `${label}-${attempt}`);
    const rows = snapshot.visibleRows.join("\n");
    const normalizedRows = rows.replaceAll(/\s/gu, "");
    lastState = {
      attempt: ++attempt,
      cursorPosition: snapshot.cursorPosition,
      windowSize: snapshot.windowSize,
      composerVisible: /^\s*›\s*$/mu.test(rows),
      modelVisible: /gpt-6-luna/u.test(rows),
      usageVisible: /上下文(?:\d+(?:\.\d+)?k|\d+)\/1\.0M(?:<0\.1|\d+(?:\.\d+)?)%/u.test(normalizedRows),
      unknownUsageVisible: /累计—/u.test(normalizedRows)
        && /输入—/u.test(normalizedRows)
        && /输出—/u.test(normalizedRows)
        && /含缓存读—/u.test(normalizedRows)
        && /上下文—\/——%/u.test(normalizedRows),
    };
    await writeFile(readinessPath, `${JSON.stringify(lastState, null, 2)}\n`, "utf8");
    if (requiredState.every((state) => lastState[`${state}Visible`] === true)) return snapshot;
    await delay(250);
  }
  throw new Error(`isolated CLI native buffer did not reach ${requiredState.join("+")} within ${timeoutMs}ms; safe readiness facts: ${readinessPath}`);
}

/** 仅在 CLI launcher 已报告退出后读取 stderr；ANSI CSI 的 ESC 字节动态构造正则，诊断保留脱敏 Ja 摘要后删除原文件以免携带正文或凭据。 @author kongweiguang */
async function preserveSafeCliDiagnostic(window) {
  if (!window?.finished || !window.cliStderr) return undefined;
  try {
    const existing = JSON.parse(await readFile(window.cliErrorDiagnostic, "utf8"));
    await rm(window.cliStderr, { force: true });
    return existing;
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
  let finished;
  try {
    finished = JSON.parse(await readFile(window.finished, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return undefined;
    if (error instanceof SyntaxError) return undefined;
    throw error;
  }

  let stderr = "";
  let stderrReadErrorType;
  try {
    stderr = await readFile(window.cliStderr, "utf8");
  } catch (error) {
    if (error?.code !== "ENOENT") stderrReadErrorType = error instanceof Error ? error.name : "UnknownError";
  }
  const jaLine = stderr
    .replaceAll(new RegExp(`${String.fromCharCode(0x1b)}\\[[0-?]*[ -/]*[@-~]`, "gu"), "")
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter((line) => /^Ja:\s*/u.test(line))
    .at(-1);
  let safeMessage;
  if (jaLine !== undefined) {
    safeMessage = jaLine.replace(/^Ja:\s*/u, "")
      .replaceAll(/https?:\/\/[^\s"'<>]+/giu, "[地址]")
      .replaceAll(/(?:[A-Za-z]:\\|\\\\)[^\r\n]*/gu, "[本地路径及后续详情已脱敏]")
      .replaceAll(/\b(?:thr|wrk|op)_[A-Za-z0-9_-]+\b/gu, "[会话标识]")
      .replaceAll(/\bBearer\s+[^\s,;]+/giu, "Bearer [凭据已脱敏]")
      .replaceAll(/\b(api[_ -]?key|authorization|secret|password|token)\b\s*(?:[:=]\s*|\s+)[^\s,;]+/giu, "$1=[凭据已脱敏]")
      .replaceAll(/(["'])[^"'\r\n]{1,500}\1/gu, "$1[引用内容已脱敏]$1")
      .slice(0, 600);
  }
  const messageCategory = safeMessage === undefined ? "no-safe-ja-message"
    : /连接失败/u.test(safeMessage) ? "transport"
      : /请求失败\s*\(-?\d+\)/u.test(safeMessage) ? "rpc"
        : /协议|必填字段|合同/u.test(safeMessage) ? "protocol"
          : /配置|未找到|无法读取/u.test(safeMessage) ? "configuration"
            : /提交结果待核实/u.test(safeMessage) ? "uncertain-operation"
              : /终答文件写入失败/u.test(safeMessage) ? "local-output"
                : "other";
  const record = {
    title: window.title,
    processId: Number.isSafeInteger(finished.processId) ? finished.processId : null,
    exitCode: Number.isInteger(finished.exitCode) ? finished.exitCode : null,
    stderrBytes: Buffer.byteLength(stderr),
    stderrReadErrorType: stderrReadErrorType ?? null,
    stableDiagnosticCodes: extractDiagnosticCodes(stderr),
    messageCategory,
    jaMessagePresent: jaLine !== undefined,
    safeMessage: safeMessage ?? null,
  };
  try {
    if (record.exitCode !== 0) {
      await writeFile(window.cliErrorDiagnostic, `${JSON.stringify(record, null, 2)}\n`, "utf8");
      record.diagnosticPath = window.cliErrorDiagnostic;
    }
  } finally {
    await rm(window.cliStderr, { force: true });
  }
  return record.exitCode === 0 ? undefined : record;
}

/** 调用短命 UI helper；由 helper 按动作前的实时焦点恢复，避免把测试启动时的旧句柄当成用户当前窗口。 @author kongweiguang */
export async function runTerminalAction(helper, args, timeout = 10_000) {
  const result = await execFileAsync("pwsh.exe", ["-NoLogo", "-NoProfile", "-File", helper, ...args], {
    windowsHide: true,
    timeout,
    maxBuffer: 64 * 1024,
  });
  return result.stdout.trim();
}

/** 后台轮询精确 CLI Console，直到原生 Unicode cell 中出现真实空 Composer；未观察到前不发送 Ctrl+D。 @author kongweiguang */
async function waitForNativeConsoleComposer(helper, terminal, timeoutMs = 15_000) {
  const cliState = JSON.parse(await readFile(terminal.cliState, "utf8"));
  const snapshotPath = join(evidenceRoot, `${terminal.title}.native-empty-composer.json`);
  const deadline = Date.now() + timeoutMs;
  let snapshot;
  while (Date.now() < deadline) {
    snapshot = JSON.parse(await runTerminalAction(helper, ["-Action", "console-snapshot", "-Title", terminal.title, "-ExpectedCliProcessId", String(cliState.processId), "-ExpectedCliPath", terminal.cliPath]));
    snapshot.cursorPosition = { x: snapshot.cursorX, y: snapshot.cursorY };
    snapshot.windowSize = { width: snapshot.columns, height: snapshot.rows };
    snapshot.snapshotMethod = "AttachConsole + ReadConsoleOutputW";
    snapshot.capturedAtUtc = new Date().toISOString();
    await writeFile(snapshotPath, `${JSON.stringify(snapshot, null, 2)}\n`, "utf8");
    const promptRow = snapshot.visibleRows[snapshot.cursorPosition.y] ?? "";
    const normalizedPromptRow = promptRow.trim();
    const promptMarker = normalizedPromptRow === "›" ? "›" : undefined;
    snapshot.cursorRow = promptMarker === undefined ? undefined : promptRow;
    snapshot.promptMarker = promptMarker;
    snapshot.promptMarkerEncodingFallback = false;
    if (snapshot.cursorRow && snapshot.cursorPosition.x <= 3) return { ...snapshot, snapshotPath };
    await delay(250);
  }
  throw new Error(`empty Composer was not visible in the exact CLI console within ${timeoutMs}ms; latest snapshot: ${snapshotPath}`);
}

/** 记录用户原前台窗口句柄，供 WT 新窗启动后立即归还焦点，不占用屏幕等待 Provider。 @author kongweiguang */
export async function readForegroundHandle(helper) {
  const handle = Number(await runTerminalAction(helper, ["-Action", "foreground"]));
  assert.ok(Number.isSafeInteger(handle) && handle > 0, "cannot capture original foreground window");
  return handle;
}

/** 捕获只读前台几何作为禁遮挡边界；只用于判断截图位置，不保存用户窗口内容。 @author kongweiguang */
async function readForegroundContext(helper) {
  const context = JSON.parse(await runTerminalAction(helper, ["-Action", "foreground-context"]));
  assert.ok(Number.isSafeInteger(context.handle) && context.handle > 0, "cannot read current foreground window identity");
  assert.ok(context.physicalRect.right > context.physicalRect.left, "foreground window must have a measurable physical rectangle");
  assert.ok(context.physicalRect.bottom > context.physicalRect.top, "foreground window must have a measurable physical rectangle");
  assert.equal(context.coordinateSpace, "physical-per-monitor-v2");
  return context;
}

/** 在有界期限内确认精确随机标题的 WT 窗口已可截图；启动失败时不把最小化窗口误报为真窗验收。 @author kongweiguang */
export async function waitForTerminalWindow(helper, window, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const state = JSON.parse(await runTerminalAction(helper, ["-Action", "probe", "-Title", window.title]));
    if (state.exists && !state.iconic && state.width >= 620 && state.height >= 420) {
      assert.equal(state.coordinateSpace, "physical-per-monitor-v2", "terminal probe coordinates must be physical before screenshot acceptance");
      assert.ok(Number.isSafeInteger(state.dpi) && state.dpi >= 96, "terminal HWND must report a valid physical DPI");
      window.ownerProcessId = state.ownerProcessId;
      window.windowHandle = state.handle;
      return state;
    }
    await delay(150);
  }
  throw new Error("isolated Windows Terminal window did not become visible at a usable size");
}

/** 等待随机标题对应的 WT 窗口创建；允许启动器先最小化，但不激活它，也不以此代替可见性验收。 @author kongweiguang */
async function waitForBackgroundTerminal(helper, window, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const state = JSON.parse(await runTerminalAction(helper, ["-Action", "probe", "-Title", window.title]));
    if (state.exists && state.width >= 620 && state.height >= 420) {
      assert.equal(state.coordinateSpace, "physical-per-monitor-v2");
      assert.ok(Number.isSafeInteger(state.dpi) && state.dpi >= 96);
      window.ownerProcessId = state.ownerProcessId;
      window.windowHandle = state.handle;
      return state;
    }
    await delay(150);
  }
  throw new Error("isolated Windows Terminal background window was not created at a usable size");
}

/** 用无激活定位把专用 WT 放入不覆盖当前前台窗的工作区，并让捕获前校验渲染区域处于顶层。 @author kongweiguang */
async function placeTerminalInBackground(helper, window, baselineForegroundHandle) {
  const placement = JSON.parse(await runTerminalAction(helper, [
    "-Action", "position-background", "-Title", window.title,
    "-ExpectedOwnerProcessId", String(window.ownerProcessId),
    "-ExpectedForegroundHandle", String(baselineForegroundHandle),
    "-Width", "1200", "-Height", "760",
  ]));
  assert.equal(placement.coordinateSpace, "physical-per-monitor-v2");
  assert.equal(placement.activationAttempted, false);
  assert.equal(placement.foregroundUnchanged, true);
  assert.equal(placement.occlusionVerified, true);
  assert.equal(placement.width, 1200);
  assert.equal(placement.height, 760);
  return placement;
}

/** 在 WT 短暂抢焦点后立刻恢复原 HWND，后续等待、账本读取和准备工作不遮挡用户桌面。 @author kongweiguang */
async function restoreOriginalForeground(helper, handle) {
  await runTerminalAction(helper, ["-Action", "restore-focus", "-Title", "", "-PreviousHandle", String(handle)]);
}

/** Windows Terminal 可能在 no-activate 启动期间短暂转移焦点；恢复原 HWND 并验证后才允许继续等待 Provider。 @author kongweiguang */
export async function recoverBackgroundTerminalForeground(helper, window, baselineHandle) {
  const foregroundAfterLaunch = await readForegroundContext(helper);
  let foregroundAfterRestore = foregroundAfterLaunch;
  let recoveryPlacement;
  let restoreError;

  if (foregroundAfterRestore.handle !== baselineHandle) {
    try {
      await restoreOriginalForeground(helper, baselineHandle);
    } catch (error) {
      restoreError = error;
    }
    foregroundAfterRestore = await readForegroundContext(helper);
  }

  if (foregroundAfterRestore.handle !== baselineHandle) {
    recoveryPlacement = await placeTerminalInBackground(helper, window, foregroundAfterRestore.handle);
    try {
      await restoreOriginalForeground(helper, baselineHandle);
    } catch (error) {
      restoreError = error;
    }
    foregroundAfterRestore = await readForegroundContext(helper);
  }

  if (foregroundAfterRestore.handle !== baselineHandle) {
    let rollbackError;
    try {
      await runTerminalAction(helper, [
        "-Action", "close", "-Title", window.title,
        "-ExpectedOwnerProcessId", String(window.ownerProcessId),
        "-ReturnHandle", String(baselineHandle),
      ]);
    } catch (error) {
      rollbackError = error;
    }
    try {
      await restoreOriginalForeground(helper, baselineHandle);
    } catch (error) {
      rollbackError ??= error;
    }
    const foregroundAfterRollback = await readForegroundContext(helper);
    assert.equal(foregroundAfterRollback.handle, baselineHandle,
      "closing the isolated test window must restore the original foreground before reporting startup failure");
    throw new Error("Windows Terminal startup could not restore focus without rolling back its isolated test window", {
      cause: restoreError ?? rollbackError,
    });
  }

  const placement = await placeTerminalInBackground(helper, window, baselineHandle);
  const foregroundAfterPlacement = await readForegroundContext(helper);
  assert.equal(foregroundAfterPlacement.handle, baselineHandle,
    "no-activation placement must preserve the restored original foreground");
  return {
    foregroundAfterLaunch,
    foregroundAfterRestore,
    foregroundAfterPlacement,
    focusTransferredDuringStartup: foregroundAfterLaunch.handle !== baselineHandle,
    recoveryPlacement,
    placement,
  };
}

/** 将专用窗口放入已核实的主屏幕工作区，确保真实快照包含底栏和屏幕边界。 @author kongweiguang */
async function placeTerminalForAcceptance(helper, window) {
  const placement = JSON.parse(await runTerminalAction(helper, [
    "-Action", "position", "-Title", window.title,
    "-Left", "40", "-Top", "40", "-Width", "1200", "-Height", "760",
  ]));
  assert.equal(placement.coordinateSpace, "physical-per-monitor-v2");
  assert.equal(placement.left, 40);
  assert.equal(placement.top, 40);
  assert.equal(placement.width, 1200);
  assert.equal(placement.height, 760);
  assert.ok(placement.workArea.left <= 40 && placement.workArea.top <= 40);
  assert.ok(placement.workArea.right >= 1240 && placement.workArea.bottom >= 800);
  assert.ok(placement.monitorBounds.right >= 1240 && placement.monitorBounds.bottom >= 800);
  return placement;
}

/** 启动 Windows API helper；普通短动作后核对原前台恢复，草稿只按测试标记发送有界 Backspace，退出仍走身份校验后的原生 console 注入。 @author kongweiguang */
export async function writeWindowHelper() {
  const helper = join(evidenceRoot, "window-control.ps1");
  const source = String.raw`# @author kongweiguang
param(
  [Parameter(Mandatory = $true)][ValidateSet('capture', 'capture-background', 'type', 'key', 'resize', 'restore', 'probe', 'position', 'position-background', 'foreground', 'foreground-context', 'restore-focus', 'close', 'console-ctrl-d', 'console-snapshot', 'console-type', 'console-enter', 'console-key')][string]$Action,
  [string]$Title = '',
  [long]$PreviousHandle = 0,
  [long]$ReturnHandle = 0,
  [long]$ExpectedForegroundHandle = 0,
  [uint32]$ExpectedOwnerProcessId = 0,
  [uint32]$ExpectedCliProcessId = 0,
  [string]$ExpectedCliPath = '',
  [string]$OutputPath,
  [string]$Payload,
  [int]$Width = 0,
  [int]$Height = 0,
  [int]$Left = 0,
  [int]$Top = 0
)
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public static class JaUsageWin32 {
  public delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);
  [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left; public int Top; public int Right; public int Bottom; }
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int maxCount);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextLength(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
  [DllImport("user32.dll", SetLastError=true)] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr dpiContext);
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int command);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hwnd, out Rect rect);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hwnd, ref Point point);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out Point point);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hwnd, IntPtr insertAfter, int x, int y, int width, int height, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(Point point);
  [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint currentThread, uint targetThread, bool attach);
  [DllImport("user32.dll")] public static extern bool GetGUIThreadInfo(uint threadId, ref GuiThreadInfo info);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr hwnd, StringBuilder className, int maxCount);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [StructLayout(LayoutKind.Sequential)] public struct Coord { public short X; public short Y; }
  [StructLayout(LayoutKind.Sequential)] public struct SmallRect { public short Left; public short Top; public short Right; public short Bottom; }
  [StructLayout(LayoutKind.Explicit, CharSet=CharSet.Unicode, Size=4)] public struct CharInfo { [FieldOffset(0)] public char UnicodeChar; [FieldOffset(2)] public ushort Attributes; }
  [StructLayout(LayoutKind.Sequential)] public struct ConsoleScreenBufferInfo { public Coord Size; public Coord CursorPosition; public ushort Attributes; public SmallRect Window; public Coord MaximumWindowSize; }
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct KeyEventRecord { public int KeyDown; public ushort RepeatCount; public ushort VirtualKeyCode; public ushort VirtualScanCode; public char UnicodeChar; public uint ControlKeyState; }
  [StructLayout(LayoutKind.Sequential)] public struct InputRecord { public ushort EventType; public KeyEventRecord KeyEvent; }
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool AttachConsole(uint processId);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool FreeConsole();
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateFileW(string name, uint access, uint shareMode, IntPtr securityAttributes, uint creationDisposition, uint flagsAndAttributes, IntPtr templateFile);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool CloseHandle(IntPtr handle);
  [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GetConsoleMode(IntPtr handle, out uint mode);
  [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetConsoleScreenBufferInfo(IntPtr handle, out ConsoleScreenBufferInfo info);
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool WriteConsoleInputW(IntPtr handle, InputRecord[] input, uint length, out uint written);
  [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool ReadConsoleOutputW(IntPtr handle, [Out] CharInfo[] buffer, Coord bufferSize, Coord bufferCoordinate, ref SmallRect readRegion);
  const uint genericRead = 0x80000000; const uint genericWrite = 0x40000000; const uint shareReadWrite = 3; const uint openExisting = 3;
  // @author kongweiguang
  // Open named console handles because AttachConsole does not replace inherited pipe standard handles.
  static IntPtr OpenConsoleDevice(string name) {
    var handle = CreateFileW(name, genericRead | genericWrite, shareReadWrite, IntPtr.Zero, openExisting, 0, IntPtr.Zero);
    if (handle == IntPtr.Zero || handle == new IntPtr(-1)) throw new InvalidOperationException("cannot open attached console device " + name + "; win32Error=" + Marshal.GetLastWin32Error());
    return handle;
  }
  // @author kongweiguang
  // Read the console's real cursor and window bounds so verification does not depend on PowerShell RawUI projections.
  static ConsoleScreenBufferInfo ReadConsoleInfo(IntPtr output) {
    ConsoleScreenBufferInfo info;
    if (!GetConsoleScreenBufferInfo(output, out info)) throw new InvalidOperationException("cannot read attached console cursor state; win32Error=" + Marshal.GetLastWin32Error());
    return info;
  }
  // @author kongweiguang
  // Inspect only the attached test console's visible rows; this avoids foreground capture and keeps the composer check grounded in cells.
  static string[] ReadVisibleRows(IntPtr output, ConsoleScreenBufferInfo screen) {
    var rows = new string[screen.Window.Bottom - screen.Window.Top + 1];
    var width = screen.Window.Right - screen.Window.Left + 1;
    for (short y = screen.Window.Top; y <= screen.Window.Bottom; y++) {
      var cells = new CharInfo[width]; var bufferSize = new Coord { X = (short)width, Y = 1 }; var bufferCoordinate = new Coord { X = 0, Y = 0 };
      var readRegion = new SmallRect { Left = screen.Window.Left, Top = y, Right = screen.Window.Right, Bottom = y };
      if (!ReadConsoleOutputW(output, cells, bufferSize, bufferCoordinate, ref readRegion)) throw new InvalidOperationException("cannot read attached console row " + y + "; win32Error=" + Marshal.GetLastWin32Error());
      var line = new StringBuilder(width);
      foreach (var cell in cells) { if ((cell.Attributes & 0x0200) != 0) continue; line.Append(cell.UnicodeChar == '\0' ? ' ' : cell.UnicodeChar); }
      rows[y - screen.Window.Top] = line.ToString().TrimEnd();
    }
    return rows;
  }
  // Console attachment can retain launcher pipe handles, so console modes and input records use explicit CONIN$/CONOUT$ handles.
  // @author kongweiguang
  // Verify an empty composer from the exact CLI console without changing its keyboard focus or console mode.
  public static object Snapshot(uint cliProcessId) {
    FreeConsole();
    if (!AttachConsole(cliProcessId)) throw new InvalidOperationException("AttachConsole failed; win32Error=" + Marshal.GetLastWin32Error());
    IntPtr input = IntPtr.Zero, output = IntPtr.Zero;
    try {
      input = OpenConsoleDevice("CONIN$"); output = OpenConsoleDevice("CONOUT$"); uint inputMode, outputMode;
      if (!GetConsoleMode(input, out inputMode) || !GetConsoleMode(output, out outputMode)) throw new InvalidOperationException("cannot read attached console modes; win32Error=" + Marshal.GetLastWin32Error());
      var screen = ReadConsoleInfo(output);
      return new { inputMode, outputMode, cursorX = screen.CursorPosition.X, cursorY = screen.CursorPosition.Y, columns = screen.Size.X, rows = screen.Size.Y, visibleRows = ReadVisibleRows(output, screen) };
    } finally { if (output != IntPtr.Zero) CloseHandle(output); if (input != IntPtr.Zero) CloseHandle(input); FreeConsole(); }
  }
  // @author kongweiguang
  // Attach only to the identity-checked CLI console and enqueue Ctrl+D records without moving the user's foreground window.
  public static object WriteCtrlD(uint cliProcessId) {
    FreeConsole();
    if (!AttachConsole(cliProcessId)) throw new InvalidOperationException("AttachConsole failed; win32Error=" + Marshal.GetLastWin32Error());
    IntPtr input = IntPtr.Zero, output = IntPtr.Zero;
    try {
      input = OpenConsoleDevice("CONIN$"); output = OpenConsoleDevice("CONOUT$"); uint inputMode; uint outputMode;
      if (!GetConsoleMode(input, out inputMode) || !GetConsoleMode(output, out outputMode)) throw new InvalidOperationException("cannot read attached console modes; win32Error=" + Marshal.GetLastWin32Error());
      var screen = ReadConsoleInfo(output);
      const uint leftControlPressed = 0x0008;
      var records = new[] {
        CreateRecord(true, 0x11, 0x1D, '\0', leftControlPressed),
        CreateRecord(true, 0x44, 0x20, '\u0004', leftControlPressed),
        CreateRecord(false, 0x44, 0x20, '\u0004', leftControlPressed),
        CreateRecord(false, 0x11, 0x1D, '\0', 0),
      };
      uint written;
      if (!WriteConsoleInputW(input, records, (uint)records.Length, out written) || written != records.Length) throw new InvalidOperationException("WriteConsoleInputW did not enqueue the complete Ctrl+D chord; win32Error=" + Marshal.GetLastWin32Error());
      return new { inputMode, outputMode, cursorX = screen.CursorPosition.X, cursorY = screen.CursorPosition.Y, columns = screen.Size.X, rows = screen.Size.Y, recordsWritten = written };
    } finally { if (output != IntPtr.Zero) CloseHandle(output); if (input != IntPtr.Zero) CloseHandle(input); FreeConsole(); }
  }
  // @author kongweiguang
  // Send bounded ASCII through the identity-checked CLI console to avoid IME/SendKeys translation and timing races.
  public static object WriteText(uint cliProcessId, string text, bool submit) {
    if (text == null || text.Length > 160) throw new ArgumentException("console text must be bounded");
    foreach (char value in text) if (value < 0x20 || value > 0x7E) throw new ArgumentException("console text must use printable ASCII");
    FreeConsole();
    if (!AttachConsole(cliProcessId)) throw new InvalidOperationException("AttachConsole failed for text input; win32Error=" + Marshal.GetLastWin32Error());
    IntPtr input = IntPtr.Zero, output = IntPtr.Zero;
    try {
      input = OpenConsoleDevice("CONIN$"); output = OpenConsoleDevice("CONOUT$"); uint inputMode; uint outputMode;
      if (!GetConsoleMode(input, out inputMode) || !GetConsoleMode(output, out outputMode)) throw new InvalidOperationException("cannot read attached console modes for text input; win32Error=" + Marshal.GetLastWin32Error());
      var screen = ReadConsoleInfo(output);
      var records = new List<InputRecord>((text.Length + (submit ? 1 : 0)) * 2);
      foreach (char value in text) { records.Add(CreateRecord(true, 0, 0, value, 0)); records.Add(CreateRecord(false, 0, 0, value, 0)); }
      if (submit) { records.Add(CreateRecord(true, 0x0D, 0x1C, '\r', 0)); records.Add(CreateRecord(false, 0x0D, 0x1C, '\r', 0)); }
      uint written = 0;
      if (records.Count > 0 && (!WriteConsoleInputW(input, records.ToArray(), (uint)records.Count, out written) || written != records.Count)) throw new InvalidOperationException("WriteConsoleInputW did not enqueue the complete text input; win32Error=" + Marshal.GetLastWin32Error());
      return new { inputMode, outputMode, cursorX = screen.CursorPosition.X, cursorY = screen.CursorPosition.Y, columns = screen.Size.X, rows = screen.Size.Y, recordsWritten = written };
    } finally { if (output != IntPtr.Zero) CloseHandle(output); if (input != IntPtr.Zero) CloseHandle(input); FreeConsole(); }
  }
  // @author kongweiguang
  // Route only a small, explicit set of CLI keys through its verified console so UI waits never depend on transient pane focus.
  public static object WriteKey(uint cliProcessId, string key) {
    ushort virtualKey, scanCode; char character; int repeats = 1;
    switch (key) {
      case "F5": virtualKey = 0x74; scanCode = 0x3F; character = '\0'; break;
      case "ESC": virtualKey = 0x1B; scanCode = 0x01; character = '\u001B'; break;
      case "HOME": virtualKey = 0x24; scanCode = 0x47; character = '\0'; break;
      case "END": virtualKey = 0x23; scanCode = 0x4F; character = '\0'; break;
      case "BACKSPACE19": virtualKey = 0x08; scanCode = 0x0E; character = '\b'; repeats = 19; break;
      case "BACKSPACE64": virtualKey = 0x08; scanCode = 0x0E; character = '\b'; repeats = 64; break;
      default: throw new ArgumentException("unsupported exact CLI key action");
    }
    FreeConsole();
    if (!AttachConsole(cliProcessId)) throw new InvalidOperationException("AttachConsole failed for key input; win32Error=" + Marshal.GetLastWin32Error());
    IntPtr input = IntPtr.Zero, output = IntPtr.Zero;
    try {
      input = OpenConsoleDevice("CONIN$"); output = OpenConsoleDevice("CONOUT$"); uint inputMode; uint outputMode;
      if (!GetConsoleMode(input, out inputMode) || !GetConsoleMode(output, out outputMode)) throw new InvalidOperationException("cannot read attached console modes for key input; win32Error=" + Marshal.GetLastWin32Error());
      var screen = ReadConsoleInfo(output);
      var records = new InputRecord[repeats * 2];
      for (int index = 0; index < repeats; index++) { records[index * 2] = CreateRecord(true, virtualKey, scanCode, character, 0); records[index * 2 + 1] = CreateRecord(false, virtualKey, scanCode, character, 0); }
      uint written;
      if (!WriteConsoleInputW(input, records, (uint)records.Length, out written) || written != records.Length) throw new InvalidOperationException("WriteConsoleInputW did not enqueue the complete CLI key action; win32Error=" + Marshal.GetLastWin32Error());
      return new { inputMode, outputMode, cursorX = screen.CursorPosition.X, cursorY = screen.CursorPosition.Y, columns = screen.Size.X, rows = screen.Size.Y, recordsWritten = written };
    } finally { if (output != IntPtr.Zero) CloseHandle(output); if (input != IntPtr.Zero) CloseHandle(input); FreeConsole(); }
  }
  // @author kongweiguang
  // Match the CONSOLE_KEY_EVENT_RECORD layout while preserving the lowercase d character and explicit control state.
  static InputRecord CreateRecord(bool keyDown, ushort virtualKey, ushort scanCode, char character, uint controlState) {
    return new InputRecord { EventType = 1, KeyEvent = new KeyEventRecord { KeyDown = keyDown ? 1 : 0, RepeatCount = 1, VirtualKeyCode = virtualKey, VirtualScanCode = scanCode, UnicodeChar = character, ControlKeyState = controlState } };
  }
  [StructLayout(LayoutKind.Sequential)] public struct Point { public int X; public int Y; }
  [StructLayout(LayoutKind.Sequential)] public struct GuiThreadInfo {
    public int Size; public uint Flags; public IntPtr Active; public IntPtr Focus; public IntPtr Capture;
    public IntPtr MenuOwner; public IntPtr MoveSize; public IntPtr Caret; public Rect CaretRect;
  }
}
'@
$script:previousDpiAwarenessContext = [JaUsageWin32]::SetThreadDpiAwarenessContext([IntPtr]::new(-4))
if ($script:previousDpiAwarenessContext -eq [IntPtr]::Zero) {
  throw "cannot set PerMonitorV2 DPI awareness for physical terminal capture; win32Error=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}
function Get-TargetWindow {
  # @author kongweiguang
  $matches = [System.Collections.Generic.List[object]]::new()
  $callback = [JaUsageWin32+EnumWindowsProc]{
    param([IntPtr]$WindowHandle, [IntPtr]$State)
    if ([JaUsageWin32]::IsWindowVisible($WindowHandle) -or $Action -in @('probe', 'position-background', 'capture-background', 'close')) {
      $length = [JaUsageWin32]::GetWindowTextLength($WindowHandle)
      if ($length -gt 0) {
        $text = [System.Text.StringBuilder]::new($length + 1)
        [void][JaUsageWin32]::GetWindowText($WindowHandle, $text, $text.Capacity)
        $visibleTitle = $text.ToString()
        if ($visibleTitle.Equals($Title, [StringComparison]::Ordinal)) {
          $matches.Add([pscustomobject]@{ Handle = $WindowHandle; Text = $visibleTitle })
        }
      }
    }
    return $true
  }
  [void][JaUsageWin32]::EnumWindows($callback, [IntPtr]::Zero)
  $matches | Sort-Object @{ Expression = { if ($_.Text -eq $Title) { 0 } else { 1 } } } | Select-Object -First 1
}
function Test-RectanglesOverlap([object]$First, [object]$Second) {
  # @author kongweiguang
  # Background capture is allowed only where the user's foreground pixels remain entirely untouched.
  return ($First.left -lt $Second.right) -and ($First.right -gt $Second.left) -and ($First.top -lt $Second.bottom) -and ($First.bottom -gt $Second.top)
}
function Assert-TargetWindowUnoccluded([IntPtr]$Handle) {
  # @author kongweiguang
  # Verify visible client points map back to this test HWND before using screen pixels as acceptance evidence.
  $client = [JaUsageWin32+Rect]::new()
  if (-not [JaUsageWin32]::GetClientRect($Handle, [ref]$client)) { throw 'cannot measure target client area for background capture' }
  $candidates = @(
    @{ x = [int](($client.Right - $client.Left) / 2); y = [int](($client.Bottom - $client.Top) / 2) },
    @{ x = [int](($client.Right - $client.Left) / 4); y = [int](($client.Bottom - $client.Top) / 4) },
    @{ x = [int](($client.Right - $client.Left) * 3 / 4); y = [int](($client.Bottom - $client.Top) * 3 / 4) }
  )
  foreach ($candidate in $candidates) {
    $point = [JaUsageWin32+Point]::new()
    $point.X = $candidate.x
    $point.Y = $candidate.y
    if (-not [JaUsageWin32]::ClientToScreen($Handle, [ref]$point)) { throw 'cannot map target client point to physical screen' }
    $covered = [JaUsageWin32]::WindowFromPoint($point)
    $root = [JaUsageWin32]::GetAncestor($covered, 2)
    if ($root -ne $Handle) { throw "test window is occluded at client point $($point.X),$($point.Y); refusing background screenshot" }
  }
}
function Set-TargetForeground([IntPtr]$Handle) {
  # @author kongweiguang
  # A DPI-aware helper must report failed foreground transitions with exact HWND/PID facts so retries cannot silently target another window.
  $currentThread = [JaUsageWin32]::GetCurrentThreadId()
  [uint32]$targetProcess = 0
  $targetThread = [JaUsageWin32]::GetWindowThreadProcessId($Handle, [ref]$targetProcess)
  $previousHandle = [JaUsageWin32]::GetForegroundWindow()
  [uint32]$previousProcess = 0
  $previousThread = [JaUsageWin32]::GetWindowThreadProcessId($previousHandle, [ref]$previousProcess)
  $attached = [System.Collections.Generic.List[uint32]]::new()
  foreach ($thread in @($targetThread, $previousThread)) {
    if ($thread -ne 0 -and $thread -ne $currentThread -and -not $attached.Contains($thread)) {
      if ([JaUsageWin32]::AttachThreadInput($currentThread, $thread, $true)) { $attached.Add($thread) }
    }
  }
  try {
    [void][JaUsageWin32]::ShowWindow($Handle, 9)
    [void][JaUsageWin32]::BringWindowToTop($Handle)
    [void][JaUsageWin32]::SetForegroundWindow($Handle)
    Start-Sleep -Milliseconds 150
    $actualForeground = [JaUsageWin32]::GetForegroundWindow()
    if ($actualForeground -ne $Handle) {
      [uint32]$actualProcess = 0
      [void][JaUsageWin32]::GetWindowThreadProcessId($actualForeground, [ref]$actualProcess)
      throw "isolated terminal did not become foreground; targetHwnd=$($Handle.ToInt64()) targetPid=$targetProcess actualHwnd=$($actualForeground.ToInt64()) actualPid=$actualProcess"
    }
  } finally {
    foreach ($thread in $attached) { [void][JaUsageWin32]::AttachThreadInput($currentThread, $thread, $false) }
  }
}
function Set-TargetInputFocus([IntPtr]$Handle) {
  # @author kongweiguang
  # Activating a WT top-level HWND does not guarantee the Composer accepts terminal keys; click its lower blank input area and verify the thread focus HWND.
  $client = [JaUsageWin32+Rect]::new()
  if (-not [JaUsageWin32]::GetClientRect($Handle, [ref]$client)) { throw 'cannot measure terminal client area' }
  $clickPoint = [JaUsageWin32+Point]::new()
  $clickPoint.X = [Math]::Min(100, [Math]::Max(40, ($client.Right - $client.Left) / 12))
  $clickPoint.Y = [Math]::Min(($client.Bottom - $client.Top - 40), [Math]::Max(40, (($client.Bottom - $client.Top) * 3 / 4)))
  if (-not [JaUsageWin32]::ClientToScreen($Handle, [ref]$clickPoint)) { throw 'cannot map terminal pane focus point' }
  $originalCursor = [JaUsageWin32+Point]::new()
  if (-not [JaUsageWin32]::GetCursorPos([ref]$originalCursor)) { throw 'cannot preserve cursor position before terminal input' }
  try {
    if (-not [JaUsageWin32]::SetCursorPos($clickPoint.X, $clickPoint.Y)) { throw 'cannot focus terminal pane' }
    [JaUsageWin32]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    [JaUsageWin32]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 100
    [uint32]$processId = 0
    $threadId = [JaUsageWin32]::GetWindowThreadProcessId($Handle, [ref]$processId)
    $info = [JaUsageWin32+GuiThreadInfo]::new()
    $info.Size = [Runtime.InteropServices.Marshal]::SizeOf([type][JaUsageWin32+GuiThreadInfo])
    if (-not [JaUsageWin32]::GetGUIThreadInfo($threadId, [ref]$info) -or $info.Focus -eq [IntPtr]::Zero) { throw 'terminal pane did not acquire keyboard focus' }
    $className = [System.Text.StringBuilder]::new(256)
    [void][JaUsageWin32]::GetClassName($info.Focus, $className, $className.Capacity)
    return [pscustomobject]@{ focusHandle = $info.Focus.ToInt64(); focusClass = $className.ToString(); terminalProcessId = $processId }
  } finally {
    [void][JaUsageWin32]::SetCursorPos($originalCursor.X, $originalCursor.Y)
  }
}
if ($Action -eq 'foreground') {
  [JaUsageWin32]::GetForegroundWindow().ToInt64()
  exit 0
}
if ($Action -eq 'foreground-context') {
  Add-Type -AssemblyName System.Windows.Forms
  $active = [JaUsageWin32]::GetForegroundWindow()
  if ($active -eq [IntPtr]::Zero) { throw 'no foreground window is available for the no-overlap check' }
  $rect = [JaUsageWin32+Rect]::new()
  [uint32]$processId = 0
  [void][JaUsageWin32]::GetWindowThreadProcessId($active, [ref]$processId)
  if (-not [JaUsageWin32]::GetWindowRect($active, [ref]$rect)) { throw 'cannot measure current foreground physical bounds' }
  $screens = @([System.Windows.Forms.Screen]::AllScreens | ForEach-Object { @{ deviceName = $_.DeviceName; bounds = @{ left = $_.Bounds.Left; top = $_.Bounds.Top; right = $_.Bounds.Right; bottom = $_.Bounds.Bottom }; workArea = @{ left = $_.WorkingArea.Left; top = $_.WorkingArea.Top; right = $_.WorkingArea.Right; bottom = $_.WorkingArea.Bottom } } })
  [pscustomobject]@{ handle = $active.ToInt64(); processId = $processId; physicalRect = @{ left = $rect.Left; top = $rect.Top; right = $rect.Right; bottom = $rect.Bottom }; screens = $screens; coordinateSpace = 'physical-per-monitor-v2' } | ConvertTo-Json -Compress -Depth 6
  exit 0
}
if ($Action -eq 'restore-focus') {
  if ($PreviousHandle -eq 0) { throw 'original foreground window handle is required' }
  Set-TargetForeground ([IntPtr]$PreviousHandle)
  'focus_restored'
  exit 0
}
if ($Action -eq 'console-ctrl-d') {
  if ($ExpectedCliProcessId -eq 0 -or [string]::IsNullOrWhiteSpace($ExpectedCliPath)) { throw 'console input requires an exact CLI PID and image path' }
  $cliIdentity = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedCliProcessId" -ErrorAction SilentlyContinue
  if ($null -eq $cliIdentity -or [string]::IsNullOrWhiteSpace($cliIdentity.ExecutablePath)) { throw 'the expected CLI PID is no longer running' }
  if (-not [string]::Equals([IO.Path]::GetFullPath($cliIdentity.ExecutablePath), [IO.Path]::GetFullPath($ExpectedCliPath), [StringComparison]::OrdinalIgnoreCase)) { throw 'CLI PID image changed; refusing console input' }
  $consoleInput = [JaUsageWin32]::WriteCtrlD($ExpectedCliProcessId)
  [pscustomobject]@{ cliProcessId = $ExpectedCliProcessId; cliImage = $cliIdentity.ExecutablePath; injection = 'WriteConsoleInputW'; chord = 'Ctrl+lowercase d'; console = $consoleInput } | ConvertTo-Json -Compress -EscapeHandling EscapeNonAscii
  exit 0
}
if ($Action -eq 'console-snapshot') {
  if ($ExpectedCliProcessId -eq 0 -or [string]::IsNullOrWhiteSpace($ExpectedCliPath)) { throw 'console inspection requires an exact CLI PID and image path' }
  $cliIdentity = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedCliProcessId" -ErrorAction SilentlyContinue
  if ($null -eq $cliIdentity -or [string]::IsNullOrWhiteSpace($cliIdentity.ExecutablePath)) { throw 'the expected CLI PID is no longer running' }
  if (-not [string]::Equals([IO.Path]::GetFullPath($cliIdentity.ExecutablePath), [IO.Path]::GetFullPath($ExpectedCliPath), [StringComparison]::OrdinalIgnoreCase)) { throw 'CLI PID image changed; refusing console inspection' }
  [JaUsageWin32]::Snapshot($ExpectedCliProcessId) | ConvertTo-Json -Compress -Depth 4 -EscapeHandling EscapeNonAscii
  exit 0
}
if ($Action -in @('console-type', 'console-enter')) {
  if ($ExpectedCliProcessId -eq 0 -or [string]::IsNullOrWhiteSpace($ExpectedCliPath)) { throw 'console text input requires an exact CLI PID and image path' }
  if ($Action -eq 'console-type' -and ($Payload -notmatch '^[A-Za-z0-9 _./-]{1,160}$')) { throw 'console text input must stay in the bounded printable ASCII subset' }
  if ($Action -eq 'console-enter' -and $Payload.Length -ne 0) { throw 'console Enter input cannot include text' }
  $cliIdentity = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedCliProcessId" -ErrorAction SilentlyContinue
  if ($null -eq $cliIdentity -or [string]::IsNullOrWhiteSpace($cliIdentity.ExecutablePath)) { throw 'the expected CLI PID is no longer running' }
  if (-not [string]::Equals([IO.Path]::GetFullPath($cliIdentity.ExecutablePath), [IO.Path]::GetFullPath($ExpectedCliPath), [StringComparison]::OrdinalIgnoreCase)) { throw 'CLI PID image changed; refusing console text input' }
  $foregroundBefore = [JaUsageWin32]::GetForegroundWindow().ToInt64()
  $consoleInput = [JaUsageWin32]::WriteText($ExpectedCliProcessId, $Payload, ($Action -eq 'console-enter'))
  $foregroundAfter = [JaUsageWin32]::GetForegroundWindow().ToInt64()
  [pscustomobject]@{ cliProcessId = $ExpectedCliProcessId; cliImage = $cliIdentity.ExecutablePath; injection = 'WriteConsoleInputW'; action = $Action; foregroundBefore = $foregroundBefore; foregroundAfter = $foregroundAfter; console = $consoleInput } | ConvertTo-Json -Compress -EscapeHandling EscapeNonAscii
  exit 0
}
if ($Action -eq 'console-key') {
  if ($ExpectedCliProcessId -eq 0 -or [string]::IsNullOrWhiteSpace($ExpectedCliPath)) { throw 'console key input requires an exact CLI PID and image path' }
  if ($Payload -notin @('F5', 'ESC', 'HOME', 'END', 'BACKSPACE19', 'BACKSPACE64')) { throw 'unsupported exact CLI key action' }
  $cliIdentity = Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedCliProcessId" -ErrorAction SilentlyContinue
  if ($null -eq $cliIdentity -or [string]::IsNullOrWhiteSpace($cliIdentity.ExecutablePath)) { throw 'the expected CLI PID is no longer running' }
  if (-not [string]::Equals([IO.Path]::GetFullPath($cliIdentity.ExecutablePath), [IO.Path]::GetFullPath($ExpectedCliPath), [StringComparison]::OrdinalIgnoreCase)) { throw 'CLI PID image changed; refusing console key input' }
  $foregroundBefore = [JaUsageWin32]::GetForegroundWindow().ToInt64()
  $consoleInput = [JaUsageWin32]::WriteKey($ExpectedCliProcessId, $Payload)
  $foregroundAfter = [JaUsageWin32]::GetForegroundWindow().ToInt64()
  [pscustomobject]@{ cliProcessId = $ExpectedCliProcessId; cliImage = $cliIdentity.ExecutablePath; injection = 'WriteConsoleInputW'; action = $Payload; foregroundBefore = $foregroundBefore; foregroundAfter = $foregroundAfter; console = $consoleInput } | ConvertTo-Json -Compress -EscapeHandling EscapeNonAscii
  exit 0
}
$target = Get-TargetWindow
if ($null -eq $target) {
  if ($Action -eq 'probe') {
    [pscustomobject]@{ exists = $false; title = $Title } | ConvertTo-Json -Compress
    exit 0
  }
  throw 'isolated Windows Terminal window was not found'
}
$previous = [JaUsageWin32]::GetForegroundWindow()
try {
  switch ($Action) {
    'probe' {
      $rect = [JaUsageWin32+Rect]::new()
      [uint32]$ownerProcessId = 0
      [void][JaUsageWin32]::GetWindowThreadProcessId($target.Handle, [ref]$ownerProcessId)
      if (-not [JaUsageWin32]::GetWindowRect($target.Handle, [ref]$rect)) { throw 'cannot measure terminal window' }
      Add-Type -AssemblyName System.Windows.Forms
      $screen = [System.Windows.Forms.Screen]::FromHandle($target.Handle)
      [pscustomobject]@{ exists = $true; title = $target.Text; handle = $target.Handle.ToInt64(); ownerProcessId = $ownerProcessId; dpi = [JaUsageWin32]::GetDpiForWindow($target.Handle); physicalRect = @{ left = $rect.Left; top = $rect.Top; right = $rect.Right; bottom = $rect.Bottom }; monitorBounds = @{ left = $screen.Bounds.Left; top = $screen.Bounds.Top; right = $screen.Bounds.Right; bottom = $screen.Bounds.Bottom }; workArea = @{ left = $screen.WorkingArea.Left; top = $screen.WorkingArea.Top; right = $screen.WorkingArea.Right; bottom = $screen.WorkingArea.Bottom }; visible = [JaUsageWin32]::IsWindowVisible($target.Handle); iconic = [JaUsageWin32]::IsIconic($target.Handle); width = $rect.Right - $rect.Left; height = $rect.Bottom - $rect.Top; coordinateSpace = 'physical-per-monitor-v2' } | ConvertTo-Json -Compress
    }
    'capture' {
      Set-TargetForeground $target.Handle
      $rect = [JaUsageWin32+Rect]::new()
      if (-not [JaUsageWin32]::GetWindowRect($target.Handle, [ref]$rect)) { throw 'cannot measure terminal window' }
      Add-Type -AssemblyName System.Drawing
      $bitmap = [System.Drawing.Bitmap]::new($rect.Right - $rect.Left, $rect.Bottom - $rect.Top)
      try {
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try { $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size) } finally { $graphics.Dispose() }
        $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
      } finally { $bitmap.Dispose() }
      [pscustomobject]@{ title = $target.Text; handle = $target.Handle.ToInt64(); dpi = [JaUsageWin32]::GetDpiForWindow($target.Handle); physicalRect = @{ left = $rect.Left; top = $rect.Top; right = $rect.Right; bottom = $rect.Bottom }; width = $rect.Right - $rect.Left; height = $rect.Bottom - $rect.Top; coordinateSpace = 'physical-per-monitor-v2'; screenshot = $OutputPath } | ConvertTo-Json -Compress
    }
    'capture-background' {
      Add-Type -AssemblyName System.Drawing
      Add-Type -AssemblyName System.Windows.Forms
      if ($ExpectedForegroundHandle -eq 0 -or [JaUsageWin32]::GetForegroundWindow().ToInt64() -ne $ExpectedForegroundHandle) { throw 'foreground HWND changed; background capture is cancelled without focus recovery' }
      [uint32]$ownerProcessId = 0
      [void][JaUsageWin32]::GetWindowThreadProcessId($target.Handle, [ref]$ownerProcessId)
      if ($ExpectedOwnerProcessId -eq 0 -or $ownerProcessId -ne $ExpectedOwnerProcessId) { throw 'exact test-window owner changed; refusing background screenshot' }
      if (-not [JaUsageWin32]::IsWindowVisible($target.Handle) -or [JaUsageWin32]::IsIconic($target.Handle)) { throw 'test window is not visible and restored for background screenshot' }
      $rect = [JaUsageWin32+Rect]::new()
      if (-not [JaUsageWin32]::GetWindowRect($target.Handle, [ref]$rect)) { throw 'cannot measure test-window physical bounds' }
      $screen = [System.Windows.Forms.Screen]::FromHandle($target.Handle)
      if ($rect.Left -lt $screen.Bounds.Left -or $rect.Top -lt $screen.Bounds.Top -or $rect.Right -gt $screen.Bounds.Right -or $rect.Bottom -gt $screen.Bounds.Bottom) { throw 'test window is not wholly visible inside its monitor bounds' }
      $foreground = [JaUsageWin32]::GetForegroundWindow()
      $foregroundRect = [JaUsageWin32+Rect]::new()
      if (-not [JaUsageWin32]::GetWindowRect($foreground, [ref]$foregroundRect)) { throw 'cannot recheck foreground bounds before capture' }
      $testBounds = @{ left = $rect.Left; top = $rect.Top; right = $rect.Right; bottom = $rect.Bottom }
      $frontBounds = @{ left = $foregroundRect.Left; top = $foregroundRect.Top; right = $foregroundRect.Right; bottom = $foregroundRect.Bottom }
      if (Test-RectanglesOverlap $testBounds $frontBounds) { throw 'test window overlaps the foreground window; refusing background screenshot' }
      Assert-TargetWindowUnoccluded $target.Handle
      $bitmap = [System.Drawing.Bitmap]::new($rect.Right - $rect.Left, $rect.Bottom - $rect.Top)
      try {
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try { $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size) } finally { $graphics.Dispose() }
        $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
      } finally { $bitmap.Dispose() }
      if ([JaUsageWin32]::GetForegroundWindow().ToInt64() -ne $ExpectedForegroundHandle) { throw 'foreground changed during background screenshot; capture is not accepted' }
      [pscustomobject]@{ title = $target.Text; handle = $target.Handle.ToInt64(); ownerProcessId = $ownerProcessId; dpi = [JaUsageWin32]::GetDpiForWindow($target.Handle); physicalRect = $testBounds; foregroundHandle = $ExpectedForegroundHandle; foregroundRect = $frontBounds; width = $rect.Right - $rect.Left; height = $rect.Bottom - $rect.Top; coordinateSpace = 'physical-per-monitor-v2'; captureMode = 'background-screen-copy'; activationAttempted = $false; foregroundUnchanged = $true; screenshot = $OutputPath } | ConvertTo-Json -Compress
    }
    'type' {
      if ([string]::IsNullOrWhiteSpace($Payload) -or $Payload -notmatch '^[A-Za-z0-9 _./-]+$') { throw 'test input must stay in a harmless ASCII subset' }
      Set-TargetForeground $target.Handle
      if ([JaUsageWin32]::GetForegroundWindow() -ne $target.Handle) { throw 'keyboard input requires the exact isolated terminal to own foreground focus' }
      $focus = Set-TargetInputFocus $target.Handle
      $shell = New-Object -ComObject WScript.Shell
      $shell.SendKeys($Payload)
      Start-Sleep -Milliseconds 100
      $shell.SendKeys('{ENTER}')
      [pscustomobject]@{ typed = $true; focusHandle = $focus.focusHandle; focusClass = $focus.focusClass; terminalProcessId = $focus.terminalProcessId } | ConvertTo-Json -Compress
    }
    'key' {
      $isShellMarker = $Payload -eq 'shell-marker'
      $isComposerDraft = $Payload -match '^draft:[A-Za-z0-9_-]{1,40}$'
      $isClearTestDraft = $Payload -eq 'clear-test-draft'
      $isClearTestPrompt = $Payload -eq 'clear-test-prompt'
      if ($Payload -notin @('^c', '^d', '{F5}', '{ESC}', '{HOME}', '^=', '^-', 'exit') -and -not $isShellMarker -and -not $isComposerDraft -and -not $isClearTestDraft -and -not $isClearTestPrompt) { throw 'unsupported terminal key action' }
      if ($isShellMarker -and ([IO.Path]::GetFullPath($OutputPath) -notlike (Join-Path ${psLiteral(evidenceRoot)} 'shell-marker-*.ps1'))) { throw 'shell marker script must stay in isolated acceptance evidence' }
      Set-TargetForeground $target.Handle
      $isWindowZoomShortcut = $Payload -in @('^=', '^-')
      if ($isWindowZoomShortcut) {
        if ([JaUsageWin32]::GetForegroundWindow() -ne $target.Handle) { throw 'real Windows Terminal zoom shortcut requires the exact isolated window in foreground' }
        $focus = [pscustomobject]@{ focusHandle = $target.Handle.ToInt64(); focusClass = 'exact Windows Terminal foreground'; terminalProcessId = 0 }
      } else { $focus = Set-TargetInputFocus $target.Handle }
      $shell = New-Object -ComObject WScript.Shell
      $injection = 'wscript-sendkeys'
      # Focus-checked COM input handles short UI chords; the exit gate uses console-ctrl-d with an exact PID/image guard.
      if ($Payload -eq '^d') { $shell.SendKeys('^d'); $injection = 'wscript-sendkeys-ctrl-d' }
      elseif ($isClearTestDraft) { $shell.SendKeys('{BACKSPACE 19}'); $injection = 'wscript-sendkeys-bounded-test-draft-backspace' }
      elseif ($isClearTestPrompt) { $shell.SendKeys('{BACKSPACE 64}'); $injection = 'wscript-sendkeys-bounded-test-prompt-backspace' }
      elseif ($isComposerDraft) { $shell.SendKeys($Payload.Substring(6)); $injection = 'wscript-sendkeys-composer-draft' }
      elseif ($isShellMarker -or $Payload -eq 'exit') {
        if ($isShellMarker) { $shell.SendKeys("& '$OutputPath'") } else { $shell.SendKeys($Payload) }
        Start-Sleep -Milliseconds 100
        $shell.SendKeys('{ENTER}')
        $injection = 'wscript-text-plus-enter'
      } else { $shell.SendKeys($Payload) }
      Start-Sleep -Milliseconds 200
      [pscustomobject]@{ keySent = $true; payload = $Payload; injection = $injection; targetHandle = $target.Handle.ToInt64(); foregroundHandle = [JaUsageWin32]::GetForegroundWindow().ToInt64(); focusHandle = $focus.focusHandle; focusClass = $focus.focusClass; terminalProcessId = $focus.terminalProcessId } | ConvertTo-Json -Compress
    }
    'resize' {
      Add-Type -AssemblyName System.Windows.Forms
      Set-TargetForeground $target.Handle
      $rect = [JaUsageWin32+Rect]::new()
      if (-not [JaUsageWin32]::GetWindowRect($target.Handle, [ref]$rect)) { throw 'cannot measure terminal window' }
      $screen = [System.Windows.Forms.Screen]::FromHandle($target.Handle)
      $screenWidth = $screen.Bounds.Width
      $screenHeight = $screen.Bounds.Height
      if ($Width -lt 620 -or $Height -lt 420 -or $Width -gt $screenWidth -or $Height -gt $screenHeight) { throw 'requested terminal size is outside safe screen bounds' }
      $newLeft = [Math]::Max(0, [int](($screenWidth - $Width) / 2))
      $newTop = [Math]::Max(0, [int](($screenHeight - $Height) / 2))
      if (-not [JaUsageWin32]::SetWindowPos($target.Handle, [IntPtr]::Zero, $newLeft, $newTop, $Width, $Height, 0x0004)) { throw 'cannot resize isolated terminal window' }
      [pscustomobject]@{ left = $rect.Left; top = $rect.Top; width = $rect.Right - $rect.Left; height = $rect.Bottom - $rect.Top; dpi = [JaUsageWin32]::GetDpiForWindow($target.Handle); coordinateSpace = 'physical-per-monitor-v2' } | ConvertTo-Json -Compress
    }
    'position' {
      Add-Type -AssemblyName System.Windows.Forms
      $rect = [JaUsageWin32+Rect]::new()
      if (-not [JaUsageWin32]::GetWindowRect($target.Handle, [ref]$rect)) { throw 'cannot measure terminal window' }
      $screen = [System.Windows.Forms.Screen]::FromHandle($target.Handle)
      $workArea = $screen.WorkingArea
      if ($Width -lt 620 -or $Height -lt 420 -or $Left -lt $workArea.Left -or $Top -lt $workArea.Top -or ($Left + $Width) -gt $workArea.Right -or ($Top + $Height) -gt $workArea.Bottom) {
        throw 'requested terminal rectangle is outside the primary monitor work area'
      }
      if (-not [JaUsageWin32]::SetWindowPos($target.Handle, [IntPtr]::Zero, $Left, $Top, $Width, $Height, 0x0004)) { throw 'cannot place isolated terminal window' }
      [pscustomobject]@{ left = $Left; top = $Top; width = $Width; height = $Height; dpi = [JaUsageWin32]::GetDpiForWindow($target.Handle); coordinateSpace = 'physical-per-monitor-v2'; workArea = @{ left = $workArea.Left; top = $workArea.Top; right = $workArea.Right; bottom = $workArea.Bottom }; monitorBounds = @{ left = $screen.Bounds.Left; top = $screen.Bounds.Top; right = $screen.Bounds.Right; bottom = $screen.Bounds.Bottom } } | ConvertTo-Json -Compress
    }
    'position-background' {
      Add-Type -AssemblyName System.Windows.Forms
      if ($ExpectedForegroundHandle -eq 0 -or [JaUsageWin32]::GetForegroundWindow().ToInt64() -ne $ExpectedForegroundHandle) { throw 'foreground HWND changed; no-activation placement is cancelled without focus recovery' }
      if ($ExpectedForegroundHandle -eq $target.Handle.ToInt64()) { throw 'test window owns foreground; refusing to reposition or capture it' }
      [uint32]$ownerProcessId = 0
      [void][JaUsageWin32]::GetWindowThreadProcessId($target.Handle, [ref]$ownerProcessId)
      if ($ExpectedOwnerProcessId -eq 0 -or $ownerProcessId -ne $ExpectedOwnerProcessId) { throw 'exact test-window owner changed; refusing no-activation placement' }
      $foreground = [JaUsageWin32]::GetForegroundWindow()
      $foregroundRect = [JaUsageWin32+Rect]::new()
      if (-not [JaUsageWin32]::GetWindowRect($foreground, [ref]$foregroundRect)) { throw 'cannot measure current foreground bounds' }
      $frontBounds = @{ left = $foregroundRect.Left; top = $foregroundRect.Top; right = $foregroundRect.Right; bottom = $foregroundRect.Bottom }
      $candidateScreens = [System.Windows.Forms.Screen]::AllScreens | Sort-Object @{ Expression = { if ($_.Primary) { 0 } else { 1 } } }
      $placed = $false
      foreach ($candidateScreen in $candidateScreens) {
        $work = $candidateScreen.WorkingArea
        if ($Width -gt $work.Width -or $Height -gt $work.Height) { continue }
        $margin = 20
        $positions = @(
          @{ x = $work.Left + $margin; y = $work.Top + $margin },
          @{ x = $work.Right - $Width - $margin; y = $work.Top + $margin },
          @{ x = $work.Left + $margin; y = $work.Bottom - $Height - $margin },
          @{ x = $work.Right - $Width - $margin; y = $work.Bottom - $Height - $margin },
          @{ x = $work.Left + [int](($work.Width - $Width) / 2); y = $work.Top + [int](($work.Height - $Height) / 2) }
        )
        foreach ($position in $positions) {
          if ($position.x -lt $work.Left -or $position.y -lt $work.Top -or ($position.x + $Width) -gt $work.Right -or ($position.y + $Height) -gt $work.Bottom) { continue }
          $candidate = @{ left = $position.x; top = $position.y; right = $position.x + $Width; bottom = $position.y + $Height }
          if (Test-RectanglesOverlap $candidate $frontBounds) { continue }
          [void][JaUsageWin32]::ShowWindow($target.Handle, 4)
          if (-not [JaUsageWin32]::SetWindowPos($target.Handle, [IntPtr]::new(-1), $position.x, $position.y, $Width, $Height, 0x0010)) { throw 'cannot position exact test window without activation' }
          Start-Sleep -Milliseconds 150
          if ([JaUsageWin32]::GetForegroundWindow().ToInt64() -ne $ExpectedForegroundHandle) { throw 'foreground changed during no-activation placement; refusing capture' }
          $actual = [JaUsageWin32+Rect]::new()
          if (-not [JaUsageWin32]::GetWindowRect($target.Handle, [ref]$actual)) { throw 'cannot verify test window physical bounds after placement' }
          $actualBounds = @{ left = $actual.Left; top = $actual.Top; right = $actual.Right; bottom = $actual.Bottom }
          if (Test-RectanglesOverlap $actualBounds $frontBounds) { throw 'test window placement overlaps the current foreground after DPI adjustment' }
          if ($actual.Left -lt $candidateScreen.Bounds.Left -or $actual.Top -lt $candidateScreen.Bounds.Top -or $actual.Right -gt $candidateScreen.Bounds.Right -or $actual.Bottom -gt $candidateScreen.Bounds.Bottom) { throw 'test window did not fit wholly inside the selected monitor' }
          if (-not [JaUsageWin32]::IsWindowVisible($target.Handle) -or [JaUsageWin32]::IsIconic($target.Handle)) { throw 'SW_SHOWNOACTIVATE did not make the test window visible and restored' }
          Assert-TargetWindowUnoccluded $target.Handle
          $placed = $true
          [pscustomobject]@{ title = $target.Text; handle = $target.Handle.ToInt64(); ownerProcessId = $ownerProcessId; left = $actual.Left; top = $actual.Top; width = $actual.Right - $actual.Left; height = $actual.Bottom - $actual.Top; dpi = [JaUsageWin32]::GetDpiForWindow($target.Handle); coordinateSpace = 'physical-per-monitor-v2'; foregroundHandle = $ExpectedForegroundHandle; foregroundRect = $frontBounds; foregroundUnchanged = $true; activationAttempted = $false; noActivationFlag = 'SWP_NOACTIVATE'; zOrder = 'HWND_TOP'; occlusionVerified = $true; workArea = @{ left = $work.Left; top = $work.Top; right = $work.Right; bottom = $work.Bottom }; monitorBounds = @{ left = $candidateScreen.Bounds.Left; top = $candidateScreen.Bounds.Top; right = $candidateScreen.Bounds.Right; bottom = $candidateScreen.Bounds.Bottom } } | ConvertTo-Json -Compress
          break
        }
        if ($placed) { break }
      }
      if (-not $placed) { throw ("no " + $Width + "x" + $Height + " monitor work-area rectangle avoids the current foreground window") }
    }
    'restore' {
      if (-not [JaUsageWin32]::SetWindowPos($target.Handle, [IntPtr]::Zero, $Left, $Top, $Width, $Height, 0x0004)) { throw 'cannot restore isolated terminal window size' }
      'restored'
    }
    'close' {
      [uint32]$ownerProcessId = 0
      [void][JaUsageWin32]::GetWindowThreadProcessId($target.Handle, [ref]$ownerProcessId)
      if ($ExpectedOwnerProcessId -eq 0 -or $ownerProcessId -ne $ExpectedOwnerProcessId) { throw 'exact test window owner changed; refusing to close an unverified window' }
      if (-not [JaUsageWin32]::PostMessage($target.Handle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)) { throw 'cannot request close for exact test window' }
      'close_requested'
    }
  }
} finally {
  $restoreHandle = if ($ReturnHandle -ne 0) { [IntPtr]::new($ReturnHandle) } else { $previous }
  $activeHandle = [JaUsageWin32]::GetForegroundWindow()
  $actionMovedFocus = $Action -in @('capture', 'type', 'key', 'resize') -and $activeHandle -eq $target.Handle
  $closingFocusedWindow = $Action -eq 'close' -and $previous -eq $target.Handle
  if (($actionMovedFocus -or $closingFocusedWindow) -and $restoreHandle -ne [IntPtr]::Zero -and $restoreHandle -ne $target.Handle) { Set-TargetForeground $restoreHandle }
}`;
  await writeFile(helper, source, "utf8");
  return helper;
}

/** 通过精确 CLI PID/image 的原生 Console 输入发送 ASCII 命令，绕开 SendKeys/IME 转换，并核对提交前真实 cell。 @author kongweiguang */
async function submitTuiPrompt(helper, window, prompt = userMarker) {
  const foregroundBefore = await readForegroundHandle(helper);
  const cliState = JSON.parse(await readFile(window.cliState, "utf8"));
  const identity = ["-ExpectedCliProcessId", String(cliState.processId), "-ExpectedCliPath", window.cliPath];
  const typed = JSON.parse(await runTerminalAction(helper, ["-Action", "console-type", "-Title", window.title, "-Payload", prompt, ...identity]));
  assert.equal(typed.injection, "WriteConsoleInputW");
  assert.equal(typed.foregroundBefore, foregroundBefore, "native text input must begin without changing the user's foreground");
  assert.equal(typed.foregroundAfter, foregroundBefore, "native text input must not take the user's foreground");

  const deadline = Date.now() + 8_000;
  let composerSnapshot;
  while (Date.now() < deadline) {
    composerSnapshot = JSON.parse(await runTerminalAction(helper, ["-Action", "console-snapshot", "-Title", window.title, ...identity]));
    const cursorRow = composerSnapshot.visibleRows[composerSnapshot.cursorY] ?? "";
    if (cursorRow.includes(prompt)) break;
    await delay(100);
  }
  const composerRow = composerSnapshot?.visibleRows[composerSnapshot.cursorY] ?? "";
  assert.ok(composerRow.includes(prompt), "the exact typed ASCII command must appear in the native Composer before Enter");
  const submitted = JSON.parse(await runTerminalAction(helper, ["-Action", "console-enter", "-Title", window.title, "-Payload", "", ...identity]));
  assert.equal(submitted.injection, "WriteConsoleInputW");
  assert.equal(submitted.foregroundBefore, foregroundBefore, "native Enter must begin without changing the user's foreground");
  assert.equal(submitted.foregroundAfter, foregroundBefore, "native Enter must leave the user's foreground unchanged");
  const submitDeadline = Date.now() + 5_000;
  let submittedSnapshot;
  while (Date.now() < submitDeadline) {
    submittedSnapshot = JSON.parse(await runTerminalAction(helper, ["-Action", "console-snapshot", "-Title", window.title, ...identity]));
    const submittedComposerRow = submittedSnapshot.visibleRows[submittedSnapshot.cursorY] ?? "";
    if (!submittedComposerRow.includes(prompt)) break;
    await delay(100);
  }
  const submittedComposerRow = submittedSnapshot?.visibleRows[submittedSnapshot.cursorY] ?? "";
  assert.equal(submittedComposerRow.includes(prompt), false, "Enter must consume the command from Composer before the UI wait begins");
  return {
    typed: true,
    inputMethod: "WriteConsoleInputW",
    focusHandle: null,
    focusClass: "exact CLI console input; no foreground focus transfer",
    terminalProcessId: cliState.processId,
    foregroundBefore,
    foregroundAfter: submitted.foregroundAfter,
    composerSnapshot,
    enterConsole: submitted.console,
    submittedSnapshot,
  };
}

/** 只向受路径校验的 CLI Console 发送已列举的刷新、导航或测试草稿按键，避免界面等待依赖焦点切换。 @author kongweiguang */
async function sendConsoleCliAction(helper, window, payload) {
  const cliState = JSON.parse(await readFile(window.cliState, "utf8"));
  const identity = ["-ExpectedCliProcessId", String(cliState.processId), "-ExpectedCliPath", window.cliPath];
  const foregroundBefore = await readForegroundHandle(helper);
  if (payload.startsWith("draft:")) {
    const draft = payload.slice("draft:".length);
    const action = JSON.parse(await runTerminalAction(helper, ["-Action", "console-type", "-Title", window.title, "-Payload", draft, ...identity]));
    assert.equal(action.foregroundBefore, foregroundBefore);
    assert.equal(action.foregroundAfter, foregroundBefore);
    const deadline = Date.now() + 5_000;
    let snapshot;
    while (Date.now() < deadline) {
      snapshot = JSON.parse(await runTerminalAction(helper, ["-Action", "console-snapshot", "-Title", window.title, ...identity]));
      if ((snapshot.visibleRows[snapshot.cursorY] ?? "").includes(draft)) break;
      await delay(100);
    }
    assert.ok((snapshot?.visibleRows[snapshot.cursorY] ?? "").includes(draft), "the exact test draft must appear in the native Composer");
    return { ...action, text: draft, snapshot };
  }
  const keyByPayload = new Map([
    ["{F5}", "F5"], ["{ESC}", "ESC"], ["{HOME}", "HOME"], ["{END}", "END"],
    ["clear-test-draft", "BACKSPACE19"], ["clear-test-prompt", "BACKSPACE64"],
  ]);
  const key = keyByPayload.get(payload);
  assert.ok(key, "only the acceptance's explicit CLI key actions may be queued");
  const action = JSON.parse(await runTerminalAction(helper, ["-Action", "console-key", "-Title", window.title, "-Payload", key, ...identity]));
  assert.equal(action.injection, "WriteConsoleInputW");
  assert.equal(action.foregroundBefore, foregroundBefore, "native CLI key input must not take foreground focus");
  assert.equal(action.foregroundAfter, foregroundBefore, "native CLI key input must leave foreground unchanged");
  return action;
}

/** 仅字体缩放走真实 WT 快捷键；要求操作时准确激活测试窗并在返回前恢复原前台，最终由 cell 网格变化验收。 @author kongweiguang */
async function sendWindowsTerminalShortcut(helper, window, shortcut) {
  assert.ok(["^=", "^-"].includes(shortcut), "only the acceptance's actual Windows Terminal zoom shortcuts are allowed");
  const foregroundBefore = await readForegroundHandle(helper);
  const state = JSON.parse(await runTerminalAction(helper, ["-Action", "key", "-Title", window.title, "-Payload", shortcut]));
  assert.equal(state.targetHandle, window.windowHandle, "zoom must target the exact isolated Windows Terminal window");
  assert.equal(state.foregroundHandle, window.windowHandle, "the real Windows Terminal shortcut must run while its exact window is foreground");
  const foregroundAfter = await readForegroundHandle(helper);
  assert.equal(foregroundAfter, foregroundBefore, "zoom key action must restore the original foreground before waiting");
  return { ...state, foregroundBefore, foregroundAfter };
}

/** 保存真实 WT 像素；截图路径以 Node 实际请求并核验存在的路径为准，避免 PowerShell stdout 编码破坏中文仓库目录。 @author kongweiguang */
async function captureTerminal(helper, window, filename, includeConsoleBuffer = true) {
  const path = join(evidenceRoot, filename);
  const output = await runTerminalAction(helper, ["-Action", "capture", "-Title", window.title, "-OutputPath", path]);
  const measured = JSON.parse(output);
  const metadata = await stat(path);
  assert.ok(metadata.size > 10_000, "captured Windows Terminal screenshot must contain real rendered pixels");
  assert.equal(measured.coordinateSpace, "physical-per-monitor-v2");
  assert.equal(measured.physicalRect.right - measured.physicalRect.left, measured.width);
  assert.equal(measured.physicalRect.bottom - measured.physicalRect.top, measured.height);
  const consoleBuffer = includeConsoleBuffer ? await readConsoleBuffer(window, filename.replace(/\.png$/u, "")) : undefined;
  return { ...measured, screenshot: path, bytes: metadata.size, ...(consoleBuffer ? { consoleBuffer } : {}) };
}

/** 只在前台未变化时复制屏幕像素，并以 Node 已核验的请求路径覆盖 PowerShell 返回值，防止仓库中文路径编码错误。 @author kongweiguang */
export async function captureTerminalInBackground(helper, window, filename, baselineForegroundHandle) {
  const path = join(evidenceRoot, filename);
  const output = await runTerminalAction(helper, [
    "-Action", "capture-background", "-Title", window.title, "-OutputPath", path,
    "-ExpectedOwnerProcessId", String(window.ownerProcessId), "-ExpectedForegroundHandle", String(baselineForegroundHandle),
  ]);
  const measured = JSON.parse(output);
  const metadata = await stat(path);
  assert.ok(metadata.size > 10_000, "background Windows Terminal screenshot must contain real rendered pixels");
  assert.equal(measured.captureMode, "background-screen-copy");
  assert.equal(measured.activationAttempted, false);
  assert.equal(measured.foregroundUnchanged, true);
  assert.equal(measured.coordinateSpace, "physical-per-monitor-v2");
  assert.equal(measured.physicalRect.right - measured.physicalRect.left, measured.width);
  assert.equal(measured.physicalRect.bottom - measured.physicalRect.top, measured.height);
  const consoleBuffer = await readConsoleBuffer(window, filename.replace(/\.png$/u, ""));
  return { ...measured, screenshot: path, bytes: metadata.size, consoleBuffer };
}

/** 在截图失败或窗口 resize 失败时也恢复原尺寸，避免验收留下被改动的桌面窗口。 @author kongweiguang */
async function captureResizedTerminal(helper, window, filename, width, height) {
  const originalRect = JSON.parse(
    await runTerminalAction(helper, ["-Action", "resize", "-Title", window.title, "-Width", String(width), "-Height", String(height)]),
  );
  let screenshot;
  let failure;
  try {
    await delay(350);
    screenshot = await captureTerminal(helper, window, filename);
  } catch (error) {
    failure = error;
  }
  try {
    await runTerminalAction(helper, [
      "-Action", "restore", "-Title", window.title,
      "-Left", String(originalRect.left), "-Top", String(originalRect.top),
      "-Width", String(originalRect.width), "-Height", String(originalRect.height),
    ]);
  } catch (restoreError) {
    if (failure) failure.cleanupFailure = restoreError;
    else failure = restoreError;
  }
  if (failure) throw failure;
  return screenshot;
}

/** 从当前 JA_HOME 读权威账本；保存关闭前安全日志，并核对 session 子进程真实退出，避免 owner 故障被恢复重试掩盖。 @author kongweiguang */
async function readLedger(artifacts, directories, endpoint, threadId, { verifyInitialFixture = true } = {}) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: placeholderSecret,
    endpoint,
  });
  let ready = false;
  let initialized;
  let readyEvent;
  let runtimeHealth;
  let ledgerResult;
  let operationError;
  let diagnosticPath;
  let diagnostics;
  try {
    initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams()),
      "runtime/initialize ledger",
    );
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    readyEvent = await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    ready = true;
    const healthFrame = await session.request("runtime/health", {});
    runtimeHealth = healthFrame?.error === undefined
      ? projectRuntimeHealth(healthFrame?.result)
      : { rpcError: projectRpcError(healthFrame.error) };
    const summaryFrame = await session.request("thread/usage/read", { threadId });
    if (summaryFrame?.error !== undefined) {
      const rpcDiagnostic = projectRpcError(summaryFrame.error);
      const failure = new Error(`thread/usage/read failed: ${rpcDiagnostic.stableCode}`);
      failure.rpcDiagnostic = rpcDiagnostic;
      throw failure;
    }
    const summary = rpcResult(summaryFrame, "thread/usage/read");
    const history = rpcResult(
      await session.request("thread/read", { threadId, tail: true }),
      "thread/read",
    );
    assert.equal(summary.threadId, threadId);
    if (verifyInitialFixture) {
      assert.equal(summary.measuredRequestCount, 1);
      assert.equal(summary.newInputTokens, usageFact.input_tokens - usageFact.input_tokens_details.cached_tokens);
      assert.equal(summary.outputTokens, usageFact.output_tokens);
      assert.equal(summary.cacheReadTokens, usageFact.input_tokens_details.cached_tokens);
      assert.equal(summary.cacheCompleteInputTokens, usageFact.input_tokens);
      assert.equal(summary.cacheCompleteReadTokens, usageFact.input_tokens_details.cached_tokens);
    }
    assert.equal(history.threadId, threadId);
    assert.ok(history.contextUsage, "completed ordinary request must persist its context usage");
    assert.equal(history.contextUsage.purpose, "assistant");
    assert.equal(history.contextUsage.certainty, "known");
    assert.equal(history.contextUsage.inputTokens, usageFact.input_tokens);
    assert.equal(history.contextUsage.outputTokens, usageFact.output_tokens);
    assert.equal(history.contextUsage.profile.contextWindowTokens, 1_000_000);
    assert.ok(Number.isSafeInteger(history.revision) && history.revision >= 0, "thread/read must provide its revision for compare-and-set compaction");
    ledgerResult = { summary, contextUsage: history.contextUsage, threadRevision: history.revision };
    return ledgerResult;
  } catch (error) {
    operationError = error;
    diagnosticPath = join(evidenceRoot, `ledger-read-diagnostic-${Date.now()}-${process.pid}.json`);
    diagnostics = {
      stage: "external-stdio-ledger-read",
      occurredAt: new Date().toISOString(),
      threadId,
      ready: {
        initializedEngine: initialized?.runtime?.engine ?? null,
        eventStatus: readyEvent?.params?.status ?? null,
        eventGeneration: readyEvent?.params?.generation ?? null,
      },
      runtimeHealth,
      rpcError: error?.rpcDiagnostic ?? { stableCode: String(error?.message ?? error).match(/\b[A-Z][A-Z0-9_]{2,63}\b/u)?.[0] ?? "UNCLASSIFIED" },
      javaStderrCodes: extractDiagnosticCodes(session.stderr),
      javaLog: await readSafeJavaDiagnosticLog(directories),
      javaProcessId: Number.isSafeInteger(session.child?.pid) ? session.child.pid : null,
      javaProcess: session.exitResult === undefined
        ? { exited: false }
        : { exited: true, exitCode: session.exitResult.code, signal: session.exitResult.signal },
    };
    await writeFile(diagnosticPath, `${JSON.stringify(diagnostics, null, 2)}\n`, "utf8");
    if (error instanceof Error) error.diagnosticPath = diagnosticPath;
    throw error;
  } finally {
    const lifecycle = await closeUsageFixtureSession(session, "usage-ledger-read", ready, operationError !== undefined);
    if (ledgerResult) ledgerResult.appServerSession = lifecycle;
    if (operationError) operationError.appServerSession = lifecycle;
    if (diagnostics && diagnosticPath) {
      diagnostics.appServerSession = lifecycle;
      diagnostics.javaProcess = { exited: lifecycle.processExited, exitCode: lifecycle.exitCode, signal: lifecycle.signal };
      await writeFile(diagnosticPath, `${JSON.stringify(diagnostics, null, 2)}\n`, "utf8");
    }
  }
}

/** 诊断仅保留稳定错误类别，不复制任意 Java stderr 文本或 Provider 请求内容。 @author kongweiguang */
function extractDiagnosticCodes(value) {
  const candidates = String(value ?? "").match(/\b(?:STORAGE_[A-Z_]+|SQLITE_[A-Z0-9_]+|SQLSTATE_[A-Z0-9]+|ERROR_[A-Z0-9_]+)\b/gu) ?? [];
  return [...new Set(candidates)].slice(-24);
}

/** 健康快照只输出稳定组件名称、状态和错误类别，不落盘任意错误文本。 @author kongweiguang */
function projectRuntimeHealth(value) {
  const allowedNames = new Set(["sidecar", "sqlite", "kernel", "configuration"]);
  const recoveryPhases = new Set(["rebuilding", "ready", "blocked"]);
  const recoveryReasons = new Set([
    "SCHEMA_VALIDATION_FAILED",
    "INITIALIZATION_FAILED",
    "CORRUPT_DATABASE",
    "DISK_FULL",
    "PERMISSION_DENIED",
    "INSTANCE_LOCKED",
    "IO_ERROR",
  ]);
  const components = Array.isArray(value?.components)
    ? value.components.filter((item) => allowedNames.has(item?.name)).map((item) => ({
      name: item.name,
      status: ["healthy", "unavailable", "degraded"].includes(item.status) ? item.status : "unknown",
      diagnostics: Array.isArray(item.diagnostics)
        ? item.diagnostics.filter((code) => typeof code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/u.test(code)).slice(0, 16)
        : [],
    }))
    : [];
  const recovery = value?.storageRecovery;
  return {
    status: ["ready", "blocked"].includes(value?.status) ? value.status : "unknown",
    components,
    ...(recovery && typeof recovery === "object" ? {
      storageRecovery: {
        phase: recoveryPhases.has(recovery.phase) ? recovery.phase : "unknown",
        reason: recovery.reason === null ? null : recoveryReasons.has(recovery.reason) ? recovery.reason : "unknown",
        historyAvailable: recovery.historyAvailable === true,
        backupAvailable: recovery.backupAvailable === true,
        rebuilt: recovery.rebuilt === true,
      },
    } : {}),
  };
}

/** JA-RPC 诊断只保留字段白名单中的关联 ID、类型名与来源标识，丢弃所有自由文本。 @author kongweiguang */
function projectRpcIdentityToken(field, value) {
  if (typeof value !== "string") return null;
  if (field === "errorId") return /^err_[a-f0-9]{32}$/iu.test(value) ? value : null;
  if (field === "origin") return /^[A-Za-z][A-Za-z0-9_.-]{0,95}$/u.test(value) ? value : null;
  return /^(?:[a-z][A-Za-z0-9_$]*\.)*[A-Z_$][A-Za-z0-9_$]*(?:\$[A-Za-z0-9_$]+)*$/u.test(value)
    ? value
    : null;
}

/** 保留 RPC 的稳定错误分类和数字码，避免以异常消息替代协议级诊断。 @author kongweiguang */
function projectRpcError(error) {
  const data = error?.data;
  return {
    numericCode: Number.isSafeInteger(error?.code) ? error.code : null,
    stableCode: typeof data?.errorCode === "string" && /^[A-Z][A-Z0-9_]{0,63}$/u.test(data.errorCode)
      ? data.errorCode
      : "UNCLASSIFIED",
    category: typeof data?.category === "string" && /^[a-z_]{1,48}$/u.test(data.category) ? data.category : "unknown",
    retryable: data?.retryable === true,
    errorId: projectRpcIdentityToken("errorId", data?.errorId),
    type: projectRpcIdentityToken("type", data?.type),
    causeType: projectRpcIdentityToken("causeType", data?.causeType),
    origin: projectRpcIdentityToken("origin", data?.origin),
  };
}

/** 在报告前把 Java 日志压缩为安全事件码和字节统计，绝不保存正文、路径或完整日志行。 @author kongweiguang */
async function readSafeJavaDiagnosticLog(directories) {
  const paths = [join(directories.logs, "app-server.log"), join(directories.logs, "app-server-error.log")];
  const files = [];
  for (const path of paths) {
    let content;
    try {
      content = await readFile(path, "utf8");
    } catch (error) {
      if (error?.code === "ENOENT") {
        files.push({ present: false, byteLength: 0, diagnosticCodes: [], warningOrErrorCount: 0, safeEntries: [] });
        continue;
      }
      files.push({ present: false, readFailure: error?.code ?? "IO_ERROR", diagnosticCodes: [], warningOrErrorCount: 0, safeEntries: [] });
      continue;
    }
    const rows = content.split(/\r?\n/u);
    const safeEntries = rows.flatMap((line) => {
      const match = line.match(/^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(WARN|ERROR)\s+\[[^\]]{1,80}\]\s+([\w.$]{1,128})\s+-\s+(.*)$/u);
      if (!match) return [];
      const message = match[4];
      const exceptionTypes = message.match(/\b[A-Za-z_$][\w$]*(?:Exception|Error)\b/gu) ?? [];
      const errorIdentity = Object.fromEntries(["errorId", "type", "causeType", "origin"].flatMap((field) => {
        const value = message.match(new RegExp(`\\b${field}=([A-Za-z0-9_.$-]{1,128})`, "u"))?.[1];
        const projected = projectRpcIdentityToken(field, value);
        return projected === null ? [] : [[field, projected]];
      }));
      const signals = [
        ["storage", /\bstorage\b/iu],
        ["sqlite", /\bsqlite\b/iu],
        ["locking", /\b(?:lock|locked|busy)\b/iu],
        ["recovery", /\brecovery\b/iu],
        ["availability", /\b(?:unavailable|available)\b/iu],
        ["failure", /\b(?:fail|failed|failure)\b/iu],
      ].filter(([, pattern]) => pattern.test(message)).map(([name]) => name);
      return [{
        timestamp: match[1],
        level: match[2],
        logger: match[3],
        diagnosticCodes: extractDiagnosticCodes(message),
        exceptionTypes: [...new Set(exceptionTypes)].slice(0, 4),
        ...(Object.keys(errorIdentity).length > 0 ? { errorIdentity } : {}),
        signals,
      }];
    });
    files.push({
      present: true,
      byteLength: Buffer.byteLength(content),
      lineCount: rows.length,
      diagnosticCodes: extractDiagnosticCodes(content),
      warningOrErrorCount: safeEntries.length,
      safeEntries: safeEntries.slice(-20),
    });
  }
  return { files };
}

/** 外部账本读取遇到活跃 owner 时，先保留首错诊断，再释放且核验本次唯一 owner 后重试。 @author kongweiguang */
async function readLedgerWithOwnerReleaseRecovery(artifacts, directories, endpoint, threadId, options) {
  try {
    return await readLedger(artifacts, directories, endpoint, threadId, options);
  } catch (firstError) {
    const sessionLifecycle = firstError?.appServerSession;
    if (firstError?.sessionLifecycle || (sessionLifecycle?.ready && !sessionLifecycle.gracefulExit)) throw firstError;
    const releasedOwnerProcessIds = await stopIsolatedAppServers(artifacts, directories);
    if (releasedOwnerProcessIds.length === 0) throw firstError;
    let ledger;
    try {
      ledger = await readLedger(artifacts, directories, endpoint, threadId, options);
    } catch (retryError) {
      if (retryError instanceof Error) {
        retryError.ownerReleaseRecovery = {
          firstAttempt: String(firstError?.message ?? firstError),
          firstAttemptDiagnosticPath: firstError?.diagnosticPath ?? null,
          releasedOwnerProcessIds,
          retry: "failed-after-exact-owner-exit",
        };
      }
      throw retryError;
    }
    ledger.ownerReleaseRecovery = {
      firstAttempt: String(firstError?.message ?? firstError),
      firstAttemptDiagnosticPath: firstError?.diagnosticPath ?? null,
      releasedOwnerProcessIds,
      retry: "succeeded-after-exact-owner-exit",
    };
    return ledger;
  }
}

/** 通过正式压缩 RPC 验证检查点；先在新 owner 中重开并信任原 workspace，再按 thread/read revision 做 CAS；失败诊断只保留历史计数、稳定错误与脱敏日志。ready 会话正常 drain，未完成初始化的子进程由当前 session 直接回收。 @author kongweiguang */
async function compactThread(artifacts, directories, endpoint, threadId, workspaceId, provider = undefined) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: placeholderSecret,
    endpoint,
  });
  let ready = false;
  let initialized;
  let readyEvent;
  let history;
  let result;
  let operationError;
  let compactDiagnosticPath;
  let compactDiagnostics;
  try {
    initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams()),
      "runtime/initialize compact",
    );
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    readyEvent = await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    ready = true;
    const opened = rpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Ja CLI 用量验收",
      }),
      "workspace/open before compact",
    );
    assert.equal(opened.workspaceId, workspaceId, "compaction must reopen the same isolated workspace identity");
    rpcResult(
      await session.request("workspace/set-trust", {
        workspaceId: opened.workspaceId,
        trust: "trusted",
      }),
      "workspace/set-trust before compact",
    );
    history = rpcResult(
      await session.request("thread/read", { threadId, tail: true }),
      "thread/read before compact",
    );
    assert.ok(Number.isSafeInteger(history.revision) && history.revision >= 0, "thread/read must provide its revision for compare-and-set compaction");
    const compactFrame = await session.request("thread/compact", {
        threadId,
        expectedThreadRevision: history.revision,
      }, 120_000);
    if (compactFrame?.error !== undefined) {
      const runtimeFrame = await session.request("runtime/health", {}).catch(() => undefined);
      const compactError = projectRpcError(compactFrame.error);
      compactDiagnosticPath = join(evidenceRoot, `thread-compact-diagnostic-${Date.now()}-${process.pid}.json`);
      compactDiagnostics = {
        stage: "thread-compact",
        occurredAt: new Date().toISOString(),
        threadId,
        ready: {
          initializedEngine: initialized?.runtime?.engine ?? null,
          eventStatus: readyEvent?.params?.status ?? null,
          eventGeneration: readyEvent?.params?.generation ?? null,
        },
        history: {
          revision: history.revision,
          collectionCounts: projectThreadHistoryCounts(history),
          contextPurpose: history.contextUsage?.purpose ?? null,
          contextTurnIdPresent: typeof history.contextUsage?.turnId === "string",
        },
        providerRequests: provider?.snapshotRequests?.() ?? null,
        runtimeHealth: runtimeFrame?.error === undefined ? projectRuntimeHealth(runtimeFrame?.result) : { rpcError: projectRpcError(runtimeFrame.error) },
        rpcError: compactError,
        javaStderrCodes: extractDiagnosticCodes(session.stderr),
        javaLog: await readSafeJavaDiagnosticLog(directories),
        javaProcessId: Number.isSafeInteger(session.child?.pid) ? session.child.pid : null,
      };
      const failure = new Error(`thread/compact failed: ${compactError.stableCode}`);
      failure.diagnosticPath = compactDiagnosticPath;
      throw failure;
    }
    const compacted = rpcResult(compactFrame, "thread/compact");
    result = {
      result: compacted,
      previousContextUsage: history.contextUsage,
      previousThreadRevision: history.revision,
      previousHistoryCounts: projectThreadHistoryCounts(history),
      providerRequests: provider?.snapshotRequests?.() ?? null,
    };
    return result;
  } catch (error) {
    operationError = error;
    throw error;
  } finally {
    const lifecycle = await closeUsageFixtureSession(session, "thread-compaction", ready, operationError !== undefined);
    if (result) result.appServerSession = lifecycle;
    if (operationError) operationError.appServerSession = lifecycle;
    if (compactDiagnostics && compactDiagnosticPath) {
      compactDiagnostics.appServerSession = lifecycle;
      compactDiagnostics.javaProcess = { exited: lifecycle.processExited, exitCode: lifecycle.exitCode, signal: lifecycle.signal };
      await writeFile(compactDiagnosticPath, `${JSON.stringify(compactDiagnostics, null, 2)}\n`, "utf8");
    }
  }
}

/** 只按 launcher 回执终止本次 Ja 子树，再按随机标题与窗口宿主 PID 关闭新建 WT 窗口。 @author kongweiguang */
export async function forceCloseOwnedTerminal(window, helper) {
  const state = JSON.parse(await readFile(window.cliState, "utf8"));
  const cliPid = Number(state.processId);
  assert.ok(Number.isSafeInteger(cliPid) && cliPid > 0, "isolated launcher must record the exact CLI process id before cleanup");
  const cliImage = resolve(String(state.executablePath));
  assert.equal(cliImage.toLowerCase(), resolve(window.cliPath).toLowerCase(), "cleanup must target the CLI image started by this fixture");
  const runningImage = (await execFileAsync("pwsh.exe", [
    "-NoLogo", "-NoProfile", "-Command",
    `(Get-CimInstance Win32_Process -Filter 'ProcessId=${cliPid}' -ErrorAction SilentlyContinue).ExecutablePath`,
  ], { windowsHide: true, timeout: 5_000, maxBuffer: 32 * 1024 }).catch(() => ({ stdout: "" }))).stdout.trim();
  let cliTerminated = false;
  if (runningImage.length > 0) {
    assert.equal(resolve(runningImage).toLowerCase(), cliImage.toLowerCase(), "PID reuse must not redirect cleanup to another executable");
    await execFileAsync("taskkill.exe", ["/PID", String(cliPid), "/T", "/F"], {
      windowsHide: true,
      timeout: 5_000,
      maxBuffer: 32 * 1024,
    });
    cliTerminated = true;
  }
  let windowCleanupMethod = "not-needed";
  if (window.ownerProcessId) {
    await runTerminalAction(helper, [
      "-Action", "close", "-Title", window.title,
      "-ExpectedOwnerProcessId", String(window.ownerProcessId),
    ]).catch((error) => {
      if (/window was not found/iu.test(String(error?.message ?? error))) {
        windowCleanupMethod = "already-closed";
        return;
      }
      throw error;
    });
    if (windowCleanupMethod !== "already-closed") windowCleanupMethod = "wm-close-requested";
  }
  return { cliTerminated, windowCleanupMethod };
}

/** 清理仅绑定本次随机 JA_HOME、指定 JDK 25 与当前验收 JAR 的残留进程；路径只以 UTF-8 Base64 投影，绕过 Windows 控制台代码页造成的身份误判。 @author kongweiguang */
export async function stopIsolatedAppServers(artifacts, directories) {
  const absoluteHome = resolve(directories.home);
  const extendedHome = absoluteHome.startsWith("\\\\?\\") ? absoluteHome : `\\\\?\\${absoluteHome}`;
  const homeToken = Buffer.from(extendedHome, "utf8").toString("base64url");
  const jarPath = resolve(artifacts.jar);
  const query = async () => {
    const command = `$token='${homeToken}'; $items=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -and $_.CommandLine.Contains('--home-dir-base64=' + $token) } | ForEach-Object { $line=[string]$_.CommandLine; $jarMatch=[regex]::Match($line, '(?i)(?:^|\\s)-jar\\s+(?:"([^"]+)"|(\\S+))'); $jarArgument=if($jarMatch.Success){if($jarMatch.Groups[1].Success){$jarMatch.Groups[1].Value}else{$jarMatch.Groups[2].Value}}else{''}; [pscustomobject]@{processId=$_.ProcessId; executablePathBase64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$_.ExecutablePath)); homeTokenMatch=$line.Contains('--home-dir-base64=' + $token); jarArgumentBase64=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($jarArgument))} }); ConvertTo-Json -InputObject $items -Compress -Depth 3`;
    const result = await execFileAsync("pwsh.exe", ["-NoLogo", "-NoProfile", "-Command", command], {
      windowsHide: true,
      timeout: 5_000,
      maxBuffer: 64 * 1024,
    });
    return result.stdout.trim().length > 0 ? JSON.parse(result.stdout.trim()) : [];
  };
  const processes = await query();
  const stoppedProcessIds = [];
  for (const processInfo of Array.isArray(processes) ? processes : [processes]) {
    const processId = Number(processInfo.processId);
    const executablePath = Buffer.from(String(processInfo.executablePathBase64), "base64").toString("utf8");
    const jarArgumentPath = Buffer.from(String(processInfo.jarArgumentBase64), "base64").toString("utf8");
    assert.ok(Number.isSafeInteger(processId) && processId > 0, "isolated app-server cleanup requires a concrete PID");
    assert.equal(resolve(executablePath).toLowerCase(), resolve(artifacts.java).toLowerCase(), "app-server cleanup must target the selected JDK 25 image");
    assert.equal(processInfo.homeTokenMatch, true, "app-server PID must use this test's unique JA_HOME");
    assert.ok(jarArgumentPath.length > 0, "isolated app-server command line must expose its selected JAR argument");
    const unprefixedJarPath = jarArgumentPath.startsWith("\\\\?\\") ? jarArgumentPath.slice(4) : jarArgumentPath;
    assert.equal(resolve(unprefixedJarPath).toLowerCase(), jarPath.toLowerCase(), "app-server PID must use the selected acceptance JAR");
    await execFileAsync("taskkill.exe", ["/PID", String(processId), "/F"], {
      windowsHide: true,
      timeout: 5_000,
      maxBuffer: 32 * 1024,
    });
    stoppedProcessIds.push(processId);
  }
  assert.equal((await query()).length, 0, "isolated app-server must release its profile before deletion");
  return stoppedProcessIds;
}

/** 向精确校验的 CLI Console 输入队列写入原生 Ctrl+D；退出后核对终端模式与隔离 UTF-8 code-page 基线，并在关闭专用窗口前留下 Shell 真窗截图。 @author kongweiguang */
export async function closeTerminal(helper, window) {
  const windowState = JSON.parse(await runTerminalAction(helper, ["-Action", "probe", "-Title", window.title]));
  assert.equal(windowState.exists, true, "native console input requires the exact isolated Windows Terminal window");
  assert.equal(windowState.ownerProcessId, window.ownerProcessId, "isolated terminal owner PID must remain stable before console input");
  const cliState = JSON.parse(await readFile(window.cliState, "utf8"));
  const cliProcessId = Number(cliState.processId);
  assert.ok(Number.isSafeInteger(cliProcessId) && cliProcessId > 0, "native console input requires the recorded CLI PID");
  assert.equal(resolve(String(cliState.executablePath)).toLowerCase(), resolve(window.cliPath).toLowerCase(), "native input must target the final verified CLI image");
  let ctrlDDispatch;
  let ctrlDActionError;
  try {
    ctrlDDispatch = JSON.parse(await runTerminalAction(helper, ["-Action", "console-ctrl-d", "-Title", window.title, "-ExpectedCliProcessId", String(cliProcessId), "-ExpectedCliPath", window.cliPath]));
  } catch (error) {
    ctrlDActionError = String(error?.message ?? error);
  }
  let launcherState;
  try {
    // Drain any ordinary RPC queued before Quit; waiting does not keep the isolated test window in the foreground.
    launcherState = JSON.parse((await waitForFile(window.finished, "CLI shell close marker", 45_000)).replace(/^\uFEFF/u, ""));
  } catch {
    const cleanup = await forceCloseOwnedTerminal(window, helper).catch(() => ({ cliTerminated: false, windowCleanupMethod: "cleanup-failed" }));
    return { cliExited: false, exitCode: null, ctrlDDispatch, ctrlDActionError, shellMarkerInputSent: false, shellMarkerObserved: false, shellExitMethod: "not-reached", forcedCliTermination: cleanup.cliTerminated, windowCleanupMethod: cleanup.windowCleanupMethod };
  }
  const shellPid = Number((await readFile(window.started, "utf8")).trim());
  assert.equal(launcherState.processId, shellPid, "normal exit must be attributed to this isolated PowerShell shell");
  let shellMarkerObserved = false;
  let shellMarkerActionError;
  let shellExitBuffer;
  let shellExitConsole;
  let shellRestoredBuffer;
  let shellRestoredConsole;
  let shellRestoredScreenshot;
  let shellActiveArea;
  try {
    const markerOutput = await waitForFile(window.shellMarkerResult, "PowerShell recovery marker", 5_000);
    shellExitBuffer = JSON.parse(await readFile(window.shellExitBufferSnapshot, "utf8"));
    shellExitConsole = JSON.parse(await readFile(window.shellExitConsoleDiagnostic, "utf8"));
    shellRestoredBuffer = JSON.parse(await readFile(window.shellBufferSnapshot, "utf8"));
    shellRestoredConsole = JSON.parse(await readFile(window.shellConsoleDiagnostic, "utf8"));
    shellMarkerObserved = markerOutput === window.shellMarker && shellRestoredBuffer.visibleRows.join("\n").replace(/\s+/gu, "").includes(window.shellMarker.slice(0, window.shellMarker.indexOf(" 🌱")));
    assert.equal(shellMarkerObserved, true, "restored PowerShell must execute the unique Chinese and Emoji recovery marker");
    const initialConsole = JSON.parse(await readFile(window.diagnostic, "utf8"));
    assert.equal(shellExitConsole.inputMode, initialConsole.inputMode, "CLI exit must restore the shell console input mode before further output");
    assert.equal(shellExitConsole.outputMode, initialConsole.outputMode, "CLI exit must restore the shell console output mode before further output");
    assert.equal(shellExitConsole.inputCodePage, initialConsole.inputCodePage, "CLI exit must restore the isolated Shell input code-page baseline");
    assert.equal(shellExitConsole.outputCodePage, initialConsole.outputCodePage, "CLI exit must restore the isolated Shell output code-page baseline");
    assert.equal(shellRestoredConsole.inputMode, initialConsole.inputMode, "CLI exit must restore the shell console input mode");
    assert.equal(shellRestoredConsole.outputMode, initialConsole.outputMode, "CLI exit must restore the shell console output mode");
    assert.equal(shellRestoredConsole.inputCodePage, initialConsole.inputCodePage, "restored PowerShell must retain the isolated Shell input code-page baseline");
    assert.equal(shellRestoredConsole.outputCodePage, initialConsole.outputCodePage, "restored PowerShell must retain the isolated Shell output code-page baseline");
    assert.equal(shellExitBuffer.cursorPosition.x, 0, "CLI exit must return the Shell cursor to the cleared active area's left edge");
    assert.ok(shellExitBuffer.cursorPosition.y < shellExitBuffer.windowSize.height, "CLI exit cursor must remain inside the visible console height");
    assert.ok(shellRestoredBuffer.cursorPosition.x < shellRestoredBuffer.windowSize.width, "restored shell cursor must remain inside the visible console width");
    assert.ok(shellRestoredBuffer.cursorPosition.y < shellRestoredBuffer.windowSize.height, "restored shell cursor must remain inside the visible console height");
    shellRestoredScreenshot = await captureTerminal(helper, window, `${window.title}.shell-restored.png`, false);
    const markerPrefix = window.shellMarker.slice(0, window.shellMarker.indexOf(" 🌱"));
    const markerRowIndex = shellRestoredBuffer.visibleRows.findIndex((row) => row.includes(markerPrefix));
    assert.ok(markerRowIndex >= 0, "restored Shell marker must be visible in the post-exit console snapshot");
    const activeAreaText = shellExitBuffer.visibleRows.slice(shellExitBuffer.cursorPosition.y).join("\n");
    const staleUsage = /(?:↑\s*[\d<]|↓\s*[\d<]|缓存读|缓存写|命中\s*[\d<]|上下文)/u.test(activeAreaText);
    const staleOperationHint = /(?:查看快捷键|刷新用量|F5\s*核实|Esc\s*中断)/u.test(activeAreaText);
    const staleComposerPrompt = /(?:^|\n)\s*[›❯](?:\s|$)/u.test(activeAreaText);
    assert.equal(staleUsage, false, "restored Shell active area must not retain the TUI usage row");
    assert.equal(staleOperationHint, false, "restored Shell active area must not retain a TUI operation hint");
    assert.equal(staleComposerPrompt, false, "restored Shell active area must not retain a TUI Composer prompt");
    const archivedUserMessageVisible = window.initialPrompt === undefined
      ? undefined
      : shellExitBuffer.visibleRows.slice(0, shellExitBuffer.cursorPosition.y)
        .some((row) => row.trim().startsWith("›") && row.includes(window.initialPrompt));
    const archivedMessageWasVisibleBeforeExit = window.preExitArchivedUserMessageVisible;
    if (archivedMessageWasVisibleBeforeExit === true) {
      assert.equal(archivedUserMessageVisible, false,
        "TUI history visible before exit must stay in the alternate buffer and not leak into restored Shell scrollback");
    }
    const archivedHistoryCheck = archivedMessageWasVisibleBeforeExit === true
      ? "isolated-in-alternate-buffer"
      : archivedMessageWasVisibleBeforeExit === false
      ? "not-observed-before-exit"
      : "not-checked";
    shellActiveArea = { cursorY: shellExitBuffer.cursorPosition.y, markerRowIndex, staleUsage, staleOperationHint, staleComposerPrompt, archivedMessageWasVisibleBeforeExit, archivedUserMessageVisible, archivedHistoryCheck };
  } catch (error) {
    shellMarkerActionError = String(error?.message ?? error);
  }
  const cleanup = await forceCloseOwnedTerminal(window, helper).catch((error) => ({ cliTerminated: false, windowCleanupMethod: `cleanup-failed:${String(error?.message ?? error)}` }));
  return { cliExited: Number.isInteger(launcherState.exitCode), exitCode: launcherState.exitCode, ctrlDDispatch, ctrlDActionError, shellMarker: window.shellMarker, shellMarkerInputSent: false, shellMarkerExecutionMethod: "PowerShell launcher after CLI exit", shellMarkerObserved, shellMarkerResult: window.shellMarkerResult, shellMarkerActionError, shellExitBuffer, shellExitConsole, shellRestoredBuffer, shellRestoredConsole, shellRestoredScreenshot, shellActiveArea, shellExitMethod: "wm-close", forcedCliTermination: cleanup.cliTerminated, windowCleanupMethod: cleanup.windowCleanupMethod };
}

/** 串起首请求未知占位、循环 Provider 实测、深浅主题、服务端账本、F5 恢复和临时目录回收；以真实 Console 状态代替固定等待。 @author kongweiguang */
export async function runCliUsageAcceptance() {
  await prepareEvidenceDirectory();
  const artifacts = await resolveArtifacts();
  let provider;
  let isolated;
  let directories;
  let terminalHelper;
  let darkWindow;
  let lightWindow;
  let darkUnknownWindowClosure;
  let darkWindowClosure;
  let lightWindowClosure;
  const stoppedAppServerProcessIds = [];
  const appServerSessionLifecycle = [];
  const terminalWindows = [];
  const cliDiagnostics = [];
  let appServerOwnershipBeforeLight;
  let primaryFailure;
  const cleanupFailures = [];
  let outcome;
  try {
    provider = await startUsageProvider();
    isolated = await createIsolatedDirectories();
    directories = {
      ...isolated,
      data: join(isolated.home, "data"),
      run: join(isolated.home, "run"),
      logs: join(isolated.home, "logs", "java"),
    };
    await Promise.all([directories.data, directories.run, directories.logs].map((path) => mkdir(path, { recursive: true })));
    const thread = await seedThread(artifacts, directories, provider.endpoint);
    assert.equal(thread.appServerSession?.gracefulExit, true, "thread fixture App Server must exit normally before any CLI owns its JA_HOME");
    appServerSessionLifecycle.push(thread.appServerSession);
    const wt = await resolveWindowsTerminal();
    terminalHelper = await writeWindowHelper();
    darkWindow = await launchTerminal(wt, artifacts, directories, thread.threadId, "Campbell", terminalHelper, undefined, true);
    terminalWindows.push(darkWindow);
    assert.equal(darkWindow.focusRestoreError, undefined, "WT startup must return focus to the original window");
    await waitForFile(darkWindow.started, "Windows Terminal launcher startup", 20_000);
    const unknownConsoleDiagnostic = JSON.parse(await waitForFile(darkWindow.diagnostic, "dark unknown terminal console diagnostic", 20_000));
    const unknownWindowState = await waitForTerminalWindow(terminalHelper, darkWindow);
    const unknownPlacement = await placeTerminalForAcceptance(terminalHelper, darkWindow);
    const unknownSnapshot = await waitForTerminalConsoleState(
      darkWindow,
      "dark-initial-unknown",
      ["composer", "model", "unknownUsage"],
      30_000,
    );
    const unknownScreenshot = await captureTerminal(terminalHelper, darkWindow, "dark-initial-unknown-wide.png");
    assertUnknownUsageFooter(unknownScreenshot.consoleBuffer.visibleRows, unknownScreenshot.consoleBuffer.windowSize.width, "dark initial usage");
    assert.ok(unknownSnapshot.windowSize.width >= 78, "dark startup must expose the full fixed footer before input");
    darkUnknownWindowClosure = await closeTerminal(terminalHelper, darkWindow);
    assert.equal(darkUnknownWindowClosure.cliExited, true, "the empty dark TUI must exit normally before the request window opens");
    assert.equal(darkUnknownWindowClosure.exitCode, 0, "the empty dark TUI must report a successful normal exit");
    assert.equal(darkUnknownWindowClosure.shellMarkerObserved, true, "the empty dark TUI must restore PowerShell before reopening the same Thread");
    assert.equal(darkUnknownWindowClosure.shellMarkerActionError, undefined, "empty-thread Shell recovery must complete without a swallowed assertion");
    assert.equal(darkUnknownWindowClosure.forcedCliTermination, false, "the empty dark TUI must not require forced termination");
    assert.ok(darkUnknownWindowClosure.shellRestoredScreenshot?.bytes > 10_000, "empty-thread exit must include a real Shell-restored screenshot");
    darkWindow = undefined;

    darkWindow = await launchTerminal(wt, artifacts, directories, thread.threadId, "Campbell", terminalHelper, userMarker, true);
    terminalWindows.push(darkWindow);
    assert.equal(darkWindow.focusRestoreError, undefined, "request-window startup must return focus to the original window");
    await waitForFile(darkWindow.started, "dark request launcher startup", 20_000);
    const darkConsoleDiagnostic = JSON.parse(await waitForFile(darkWindow.diagnostic, "dark request terminal console diagnostic", 20_000));
    const darkWindowState = await waitForTerminalWindow(terminalHelper, darkWindow);
    const darkPlacement = await placeTerminalForAcceptance(terminalHelper, darkWindow);

    const heldRequest = await Promise.race([
      provider.received,
      delay(45_000).then(() => { throw new Error("isolated provider did not receive the CLI resume --prompt startup turn"); }),
    ]);
    assert.equal(heldRequest.requestCount, 1);
    assert.equal(heldRequest.markerFound, true, "local provider must see the exact UI-entered acceptance marker");
    await delay(500);
    const estimatedScreenshot = await captureTerminal(terminalHelper, darkWindow, "dark-estimated-wide.png");
    const estimateRows = estimatedScreenshot.consoleBuffer.visibleRows.join("").replaceAll(/\s/gu, "");
    assert.match(estimateRows, /上下文(?:\d+(?:\.\d+)?k|\d+)\/1\.0M\s*(?:<0\.1|\d+(?:\.\d+)?)%/u,
      "an in-flight local estimate must show its context size and share without an approximate label");
    assert.doesNotMatch(estimateRows, /约|已报告|正在读取|待刷新|未知/u,
      "the footer keeps transient usage state out of its stable layout");

    provider.release();
    const finished = await provider.waitForResponses(1);
    assert.equal(finished.requestCount, 1);
    await delay(2_500);
    const measuredScreenshot = await captureTerminal(terminalHelper, darkWindow, "dark-measured-wide.png");
    const measuredRows = measuredScreenshot.consoleBuffer.visibleRows.join("\n");
    const measuredFooter = measuredRows.replaceAll(/\s/gu, "");
    assert.match(measuredFooter, /累计24k/u, "measured footer must show total reported usage");
    assert.match(measuredFooter, /输入24k/u, "reported input must include cached tokens");
    assert.match(measuredFooter, /输出449/u, "measured footer must show output from the Provider ledger");
    assert.match(measuredFooter, /含缓存读22k/u, "measured footer must label cache reads within input");
    assert.match(measuredFooter, /上下文24k\/1\.0M2\.4%/u, "measured footer must use the ordinary assistant context sample");
    assert.doesNotMatch(measuredFooter, /约|已报告|正在读取|待刷新|未知/u,
      "the completed footer must show values without transient status wording");
    assertMeasuredUsageFooterLayout(measuredScreenshot.consoleBuffer.visibleRows, measuredScreenshot.consoleBuffer.windowSize.width, "dark measured-wide");
    assert.match(measuredRows, /\?\s*查\s*看\s*快\s*捷\s*键/u, "idle shortcut hint must occupy the operation row");
    const statusInputFocus = await submitTuiPrompt(terminalHelper, darkWindow, "/status");
    await delay(1_000);
    const statusScreenshot = await captureTerminal(terminalHelper, darkWindow, "dark-status-wide.png");
    const statusRows = statusScreenshot.consoleBuffer.visibleRows.join("\n");
    const statusText = statusRows.replaceAll(/\s+/gu, " ");
    assert.match(statusText, /输入（含缓存）：24,000 tokens/u, "status details must show provider-reported input including cache");
    assert.match(statusText, /新输入（未缓存）：1,920 tokens/u, "status details must explain uncached input precisely");
    assert.match(statusText, /输出：449 tokens/u, "status details must explain output precisely");
    assert.match(statusText, /总计（输入含缓存 \+ 输出）：24,449 tokens/u, "status details must explain the reported total precisely");
    assert.match(statusText, /缓存明细（已含在输入中，不重复累加）：读取 22,080 tokens/u,
      "status details must explain that cache reads are included in input, not additional tokens");
    await sendConsoleCliAction(terminalHelper, darkWindow, "{END}");
    await delay(300);
    const statusBottomScreenshot = await captureTerminal(terminalHelper, darkWindow, "dark-status-bottom-wide.png");
    const statusBottomText = statusBottomScreenshot.consoleBuffer.visibleRows.join("\n").replaceAll(/\s+/gu, " ");
    assert.match(statusBottomText, /最近请求上下文：24,449 \/ 1,000,000 tokens（Provider 实测/u,
      "status details must identify the latest completed context measurement on the scrolled status page");
    assert.match(statusBottomText, /发\s*送前显示本地输入估算，Provider 实测返回后\s*校正/u,
      "status details must explain why an estimate changes to the reported measurement");
    await sendConsoleCliAction(terminalHelper, darkWindow, "{HOME}");
    await delay(300);
    const statusTopScreenshot = await captureTerminal(terminalHelper, darkWindow, "dark-status-top-wide.png");
    await sendConsoleCliAction(terminalHelper, darkWindow, "{ESC}");
    await delay(500);
    const returnedScreenshot = await captureTerminal(terminalHelper, darkWindow, "dark-returned-wide.png");
    const narrowScreenshot = await captureResizedTerminal(terminalHelper, darkWindow, "dark-measured-narrow.png", 720, 740);
    assertMeasuredUsageFooterLayout(narrowScreenshot.consoleBuffer.visibleRows, narrowScreenshot.consoleBuffer.windowSize.width, "dark measured-narrow");
    const shortScreenshot = await captureResizedTerminal(terminalHelper, darkWindow, "dark-measured-short.png", 980, 490);
    const darkBeforeExitScreenshot = await captureTerminal(terminalHelper, darkWindow, "dark-before-exit-wide.png");
    const darkBeforeExitRows = darkBeforeExitScreenshot.consoleBuffer.visibleRows
      .slice(0, darkBeforeExitScreenshot.consoleBuffer.cursorPosition.y);
    darkWindow.preExitArchivedUserMessageVisible = darkBeforeExitRows
      .some((row) => row.trim().startsWith("›") && row.includes(userMarker));

    darkWindowClosure = await closeTerminal(terminalHelper, darkWindow);
    assert.equal(darkWindowClosure.cliExited, true, "CLI must exit before ledger recovery read");
    assert.equal(darkWindowClosure.exitCode, 0, "CLI must report a successful normal exit");
    assert.equal(darkWindowClosure.shellMarkerObserved, true, "PowerShell must execute and display its unique recovery marker after CLI exit");
    assert.equal(darkWindowClosure.shellMarkerExecutionMethod, "PowerShell launcher after CLI exit");
    assert.equal(darkWindowClosure.shellMarkerActionError, undefined, "Shell recovery marker must be visible in the dedicated terminal");
    assert.equal(darkWindowClosure.forcedCliTermination, false, "normal CLI exit must not need process termination");
    assert.ok(darkWindowClosure.shellRestoredScreenshot?.bytes > 10_000, "normal exit must include a real Shell-restored Windows Terminal screenshot");
    const beforeCompaction = await readLedgerWithOwnerReleaseRecovery(artifacts, directories, provider.endpoint, thread.threadId);
    assert.equal(beforeCompaction.appServerSession?.gracefulExit, true, "pre-compaction ledger reader must release its App Server owner normally");
    appServerSessionLifecycle.push(beforeCompaction.appServerSession);
    const compaction = await compactThread(artifacts, directories, provider.endpoint, thread.threadId, thread.workspaceId, provider);
    assert.equal(compaction.appServerSession?.gracefulExit, true, "compaction App Server must release its owner normally");
    appServerSessionLifecycle.push(compaction.appServerSession);
    if (compaction.result.outcome === "compacted") await provider.waitForResponses(2);
    const afterCompaction = await readLedgerWithOwnerReleaseRecovery(artifacts, directories, provider.endpoint, thread.threadId, {
      verifyInitialFixture: false,
    });
    assert.equal(afterCompaction.appServerSession?.gracefulExit, true, "post-compaction ledger reader must release its App Server owner normally");
    appServerSessionLifecycle.push(afterCompaction.appServerSession);
    assert.equal(afterCompaction.contextUsage.purpose, "assistant", "summary usage must not replace ordinary assistant context");
    assert.equal(afterCompaction.contextUsage.turnId, beforeCompaction.contextUsage.turnId);
    assert.equal(afterCompaction.contextUsage.profile.contextWindowTokens, 1_000_000);

    appServerOwnershipBeforeLight = {
      checkedAtUtc: new Date().toISOString(),
      javaLogBeforeOwnerCheck: await readSafeJavaDiagnosticLog(directories),
      ownedProcessIdsFoundAndStopped: [],
    };
    await writeFile(join(evidenceRoot, "app-server-before-light.json"), `${JSON.stringify(appServerOwnershipBeforeLight, null, 2)}\n`, "utf8");
    appServerOwnershipBeforeLight.ownedProcessIdsFoundAndStopped = await stopIsolatedAppServers(artifacts, directories);
    await writeFile(join(evidenceRoot, "app-server-before-light.json"), `${JSON.stringify(appServerOwnershipBeforeLight, null, 2)}\n`, "utf8");
    assert.deepEqual(appServerOwnershipBeforeLight.ownedProcessIdsFoundAndStopped, [],
      "no App Server owner from fixture seeding, ledger reads, compaction, or the dark CLI may remain before the light CLI starts");

    lightWindow = await launchTerminal(wt, artifacts, directories, thread.threadId, "One Half Light", terminalHelper, undefined, true);
    terminalWindows.push(lightWindow);
    assert.equal(lightWindow.focusRestoreError, undefined, "restored WT startup must return focus to the original window");
    await waitForFile(lightWindow.started, "restored Windows Terminal launcher startup", 20_000);
    const lightConsoleDiagnostic = JSON.parse(await waitForFile(lightWindow.diagnostic, "light terminal console diagnostic", 20_000));
    const lightWindowState = await waitForTerminalWindow(terminalHelper, lightWindow);
    const lightPlacement = await placeTerminalForAcceptance(terminalHelper, lightWindow);
    await waitForTerminalConsoleState(lightWindow, "light-restored", ["composer", "model"], 30_000);
    const lightScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-restored-wide.png");
    await sendConsoleCliAction(terminalHelper, lightWindow, "{F5}");
    await waitForTerminalConsoleState(lightWindow, "light-refreshed", ["composer", "model", "usage"], 30_000);
    const refreshedScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-refreshed-wide.png");
    const refreshedRows = refreshedScreenshot.consoleBuffer.visibleRows.join("\n");
    assert.match(refreshedRows.replaceAll(/\s/gu, ""), /累计24k/u, "F5 must restore cumulative usage after reconnecting the view");
    assert.match(refreshedRows.replaceAll(/\s/gu, ""), /上下文24k\/1\.0M2\.4%/u, "F5 must restore the latest assistant context usage");
    await sendConsoleCliAction(terminalHelper, lightWindow, "draft:preserved-across-f5");
    await delay(250);
    const draftBeforeRefreshScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-draft-before-f5.png");
    const draftBeforeRefreshRows = draftBeforeRefreshScreenshot.consoleBuffer.visibleRows.join("\n");
    assert.match(draftBeforeRefreshRows, /preserved-across-f5/u, "unsubmitted composer draft must be visible before refreshing");
    await sendConsoleCliAction(terminalHelper, lightWindow, "{F5}");
    await delay(750);
    const draftAfterRefreshScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-draft-after-f5.png");
    const draftAfterRefreshRows = draftAfterRefreshScreenshot.consoleBuffer.visibleRows.join("\n");
    assert.match(draftAfterRefreshRows, /preserved-across-f5/u, "F5 must preserve an unsubmitted composer draft");
    assert.match(draftAfterRefreshRows.replaceAll(/\s/gu, ""), /累计24k/u, "F5 with a draft must preserve cumulative usage");
    await sendConsoleCliAction(terminalHelper, lightWindow, "clear-test-draft");
    await delay(250);
    const draftClearedScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-draft-cleared.png");
    const draftClearedRows = draftClearedScreenshot.consoleBuffer.visibleRows.join("\n");
    assert.doesNotMatch(draftClearedRows, /preserved-across-f5/u, "bounded Backspace input must clear only the local draft before continuing the matrix");
    assert.match(draftClearedRows.replaceAll(/\s/gu, ""), /累计24k/u, "clearing a draft must not affect cumulative usage");
    const lightBaseSize = refreshedScreenshot.consoleBuffer.windowSize;
    await sendWindowsTerminalShortcut(terminalHelper, lightWindow, "^=");
    await delay(700);
    const scaledScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-scaled-wide.png");
    const scaledSize = scaledScreenshot.consoleBuffer.windowSize;
    assert.ok(scaledSize.width < lightBaseSize.width || scaledSize.height < lightBaseSize.height, "Windows Terminal zoom shortcut must change the visible cell grid");
    await sendWindowsTerminalShortcut(terminalHelper, lightWindow, "^-");
    await delay(700);
    const restoredScaleScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-scale-restored-wide.png");
    lightWindowClosure = await closeTerminal(terminalHelper, lightWindow);
    assert.equal(lightWindowClosure.cliExited, true, "restored CLI must exit normally");
    assert.equal(lightWindowClosure.exitCode, 0, "restored CLI must report a successful normal exit");
    assert.equal(lightWindowClosure.shellMarkerObserved, true, "PowerShell must execute and display its unique recovery marker after CLI exit");
    assert.equal(lightWindowClosure.shellMarkerExecutionMethod, "PowerShell launcher after CLI exit");
    assert.equal(lightWindowClosure.shellMarkerActionError, undefined, "Shell recovery marker must be visible in the dedicated terminal");
    assert.equal(lightWindowClosure.forcedCliTermination, false, "normal CLI exit must not need process termination");
    assert.ok(lightWindowClosure.shellRestoredScreenshot?.bytes > 10_000, "restored normal exit must include a real Shell-restored Windows Terminal screenshot");

    outcome = {
      status: "completed",
      runtime: "JDK 25 App Server JAR + isolated Windows Terminal CLI",
      provider: "loopback-only OpenAI Responses fixture; no paid or real Provider calls",
      artifactFingerprint: { cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256 },
      threadId: thread.threadId,
      expectedFromFixture: {
        newInputTokens: usageFact.input_tokens - usageFact.input_tokens_details.cached_tokens,
        outputTokens: usageFact.output_tokens,
        cacheReadTokens: usageFact.input_tokens_details.cached_tokens,
        cacheHitPercent: 92,
        contextWindowTokens: 1_000_000,
      },
      serverLedger: { beforeCompaction, afterCompaction },
      screenshots: {
        darkInitialUnknownWide: unknownScreenshot,
        darkUnknownShellRestored: darkUnknownWindowClosure.shellRestoredScreenshot,
        darkEstimatedWide: estimatedScreenshot,
        darkMeasuredWide: measuredScreenshot,
        darkStatusWide: statusScreenshot,
        darkStatusBottomWide: statusBottomScreenshot,
        darkStatusTopWide: statusTopScreenshot,
        darkReturnedWide: returnedScreenshot,
        darkMeasuredNarrow: narrowScreenshot,
        darkMeasuredShort: shortScreenshot,
        darkBeforeExitWide: darkBeforeExitScreenshot,
        lightRestoredWide: lightScreenshot,
        lightRefreshedWide: refreshedScreenshot,
        lightDraftBeforeF5: draftBeforeRefreshScreenshot,
        lightDraftAfterF5: draftAfterRefreshScreenshot,
        lightDraftCleared: draftClearedScreenshot,
        lightScaledWide: scaledScreenshot,
        lightScaleRestoredWide: restoredScaleScreenshot,
        darkShellRestored: darkWindowClosure.shellRestoredScreenshot,
        lightShellRestored: lightWindowClosure.shellRestoredScreenshot,
      },
      terminalWindowState: {
        darkUnknown: { window: unknownWindowState, placement: unknownPlacement, consoleDiagnostic: unknownConsoleDiagnostic },
        dark: darkWindowState,
        darkPlacement,
        darkConsoleDiagnostic,
        statusInputFocus,
        light: lightWindowState,
        lightPlacement,
        lightConsoleDiagnostic,
      },
      windowsTerminalExecutable: wt,
      cleanup: { darkUnknownWindow: darkUnknownWindowClosure, darkWindow: darkWindowClosure, lightWindow: lightWindowClosure },
      appServerSessionLifecycle,
      appServerOwnershipBeforeLight,
      compaction: compaction.result,
      compactionProducedCheckpoint: compaction.result.outcome === "compacted",
    };
  } catch (error) {
    primaryFailure = error instanceof Error ? error : new Error(String(error));
  } finally {
    if (primaryFailure) {
      for (const window of terminalWindows) {
        try {
          const diagnostic = await preserveSafeCliDiagnostic(window);
          if (diagnostic) cliDiagnostics.push(diagnostic);
        } catch (diagnosticError) {
          cleanupFailures.push(diagnosticError);
        }
      }
    }
    if (darkWindow && !darkWindowClosure && terminalHelper) {
      if (provider) provider.release();
      const closure = await closeTerminal(terminalHelper, darkWindow).catch((error) => {
        cleanupFailures.push(error);
        return { cliExited: false, shellClosed: false, forced: false };
      });
      if (darkWindow.initialPrompt === undefined) darkUnknownWindowClosure = closure;
      else darkWindowClosure = closure;
    }
    if (lightWindow && !lightWindowClosure && terminalHelper) {
      if (provider) provider.release();
      lightWindowClosure = await closeTerminal(terminalHelper, lightWindow).catch((error) => {
        cleanupFailures.push(error);
        return { cliExited: false, shellClosed: false, forced: false };
      });
    }
    for (const window of terminalWindows) {
      try {
        const diagnostic = await preserveSafeCliDiagnostic(window);
        if (diagnostic && !cliDiagnostics.some((entry) => entry.title === diagnostic.title)) cliDiagnostics.push(diagnostic);
      } catch (diagnosticError) {
        cleanupFailures.push(diagnosticError);
      }
    }
    if (primaryFailure && directories) {
      try {
        const diagnosticPath = join(evidenceRoot, "failure-app-server-diagnostic.json");
        await writeFile(diagnosticPath, `${JSON.stringify({
          stage: "Windows Terminal usage acceptance failure before App Server and profile cleanup",
          occurredAt: new Date().toISOString(),
          errorType: primaryFailure.name,
          stableDiagnosticCodes: extractDiagnosticCodes(primaryFailure.message),
          cliDiagnostics,
          javaLogBeforeOwnerStop: await readSafeJavaDiagnosticLog(directories),
          appServerSessionLifecycle,
          appServerOwnershipBeforeLight,
        }, null, 2)}\n`, "utf8");
        primaryFailure.runtimeDiagnosticPath = diagnosticPath;
      } catch (diagnosticError) {
        cleanupFailures.push(diagnosticError);
      }
    }
    if (provider) await provider.close().catch((error) => cleanupFailures.push(error));
    if (isolated) {
      try {
        stoppedAppServerProcessIds.push(...await stopIsolatedAppServers(artifacts, directories));
        const cleanupRoot = resolve(isolated.root);
        const physicalTempRoot = resolve(await realpath(tmpdir()));
        assert.equal(dirname(cleanupRoot).toLowerCase(), physicalTempRoot.toLowerCase(), "profile cleanup must stay directly inside this machine's temporary directory");
        assert.ok(cleanupRoot.split(/[\\/]/u).at(-1)?.startsWith("ja-real-provider-"), "profile cleanup must target this fixture's isolated directory");
        await rm(cleanupRoot, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
      } catch (error) {
        cleanupFailures.push(error);
      }
    }
  }
  if (cleanupFailures.length > 0) {
    if (!primaryFailure) primaryFailure = cleanupFailures[0] instanceof Error ? cleanupFailures[0] : new Error(String(cleanupFailures[0]));
    else primaryFailure.cleanupFailures = cleanupFailures.map((error) => String(error?.message ?? error));
  }
  if (primaryFailure) {
    const failedReport = {
      status: "failed",
      artifactFingerprint: { cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256 },
      error: String(primaryFailure.message),
      diagnosticPath: primaryFailure.diagnosticPath ?? primaryFailure.firstAttemptDiagnosticPath ?? null,
      runtimeDiagnosticPath: primaryFailure.runtimeDiagnosticPath ?? null,
      ownerReleaseRecovery: primaryFailure.ownerReleaseRecovery ?? null,
      cleanupFailures: cleanupFailures.map((error) => String(error?.message ?? error)),
      windows: { darkUnknown: darkUnknownWindowClosure, dark: darkWindowClosure, light: lightWindowClosure },
      cliDiagnostics,
      appServerSessionLifecycle,
      appServerOwnershipBeforeLight,
      isolatedAppServerProcessIds: stoppedAppServerProcessIds,
      isolatedProfileRemoved: isolated ? !(await stat(isolated.root).then(() => true, () => false)) : true,
    };
    await writeFile(join(evidenceRoot, "failure.json"), `${JSON.stringify(failedReport, null, 2)}\n`, "utf8").catch(() => undefined);
    throw primaryFailure;
  }
  outcome.cleanup.isolatedAppServerProcessIds = stoppedAppServerProcessIds;
  outcome.cleanup.isolatedProfileRemoved = isolated ? !(await stat(isolated.root).then(() => true, () => false)) : true;
  await writeFile(join(evidenceRoot, "report.json"), `${JSON.stringify(outcome, null, 2)}\n`, "utf8");
  return outcome;
}

/** 用一条隔离 loopback 计量请求补当前 CLI 的 Campbell 深色宽窗真像素证据，全程禁止终端焦点切换。 @author kongweiguang */
export async function runCliUsageDarkBackgroundAcceptance() {
  await prepareEvidenceDirectory();
  const artifacts = await resolveArtifacts();
  let provider;
  let isolated;
  let directories;
  let terminalHelper;
  let darkWindow;
  let darkWindowClosure;
  let primaryFailure;
  const cleanupFailures = [];
  const stoppedAppServerProcessIds = [];
  let outcome;
  const runId = `${Date.now()}-${process.pid}`;
  try {
    provider = await startUsageProvider();
    isolated = await createIsolatedDirectories();
    directories = {
      ...isolated,
      data: join(isolated.home, "data"),
      run: join(isolated.home, "run"),
      logs: join(isolated.home, "logs", "java"),
    };
    await Promise.all([directories.data, directories.run, directories.logs].map((path) => mkdir(path, { recursive: true })));
    const thread = await seedThread(artifacts, directories, provider.endpoint);
    const wt = await resolveWindowsTerminal();
    terminalHelper = await writeWindowHelper();
    const foregroundBefore = await readForegroundContext(terminalHelper);
    darkWindow = await launchTerminalWithoutActivation(wt, artifacts, directories, thread.threadId, terminalHelper, userMarker);
    const started = await waitForFile(darkWindow.started, "background Campbell launcher startup", 20_000);
    assert.ok(Number(started) > 0, "dedicated launcher must report its PowerShell process id");
    const cliState = JSON.parse(await waitForFile(darkWindow.cliState, "background Campbell CLI identity", 20_000));
    assert.equal(resolve(cliState.executablePath).toLowerCase(), resolve(artifacts.cli).toLowerCase());
    const consoleDiagnostic = JSON.parse(await waitForFile(darkWindow.diagnostic, "background Campbell console diagnostic", 20_000));
    const initialWindow = await waitForBackgroundTerminal(terminalHelper, darkWindow);
    assert.notEqual(initialWindow.handle, foregroundBefore.handle, "the test terminal must remain a background window");
    const foregroundRecovery = await recoverBackgroundTerminalForeground(
      terminalHelper,
      darkWindow,
      foregroundBefore.handle,
    );
    const { placement } = foregroundRecovery;
    const heldRequest = await Promise.race([
      provider.received,
      delay(45_000).then(() => { throw new Error("isolated Provider did not receive the one background-window startup request"); }),
    ]);
    assert.equal(heldRequest.requestCount, 1);
    assert.equal(heldRequest.markerFound, true);
    assert.equal(consoleDiagnostic.colorEnvironmentForJa?.noColorPresent, false, "dark screenshot must use normal ANSI colors");
    provider.release();
    assert.equal((await provider.waitForResponses(1)).requestCount, 1);
    await delay(2_500);

    const screenshotName = `dark-current-${runId}-Campbell-wide.png`;
    const measuredScreenshot = await captureTerminalInBackground(terminalHelper, darkWindow, screenshotName, foregroundBefore.handle);
    assertMeasuredUsageFooterLayout(measuredScreenshot.consoleBuffer.visibleRows, measuredScreenshot.consoleBuffer.windowSize.width, "background measured-wide");
    const foregroundAfterCapture = await readForegroundContext(terminalHelper);
    assert.equal(foregroundAfterCapture.handle, foregroundBefore.handle, "background screenshot must leave the user's foreground HWND unchanged");
    const darkBeforeExitRows = measuredScreenshot.consoleBuffer.visibleRows
      .slice(0, measuredScreenshot.consoleBuffer.cursorPosition.y);
    darkWindow.preExitArchivedUserMessageVisible = darkBeforeExitRows
      .some((row) => row.trim().startsWith("›") && row.includes(userMarker));
    assert.equal(darkWindow.preExitArchivedUserMessageVisible, true,
      "the usage turn must be visible in the TUI alternate buffer before normal exit");

    darkWindowClosure = await closeTerminal(terminalHelper, darkWindow);
    assert.equal(darkWindowClosure.cliExited, true, "the CLI must exit through native console input before cleanup");
    assert.equal(darkWindowClosure.exitCode, 0);
    assert.equal(darkWindowClosure.shellMarkerObserved, true);
    assert.equal(darkWindowClosure.shellMarkerActionError, undefined,
      "Shell restoration and alternate-buffer cleanup assertions must complete without a captured error");
    assert.equal(darkWindowClosure.shellActiveArea?.archivedHistoryCheck, "isolated-in-alternate-buffer",
      "TUI content visible before exit must not leak into the restored Shell scrollback");
    assert.equal(darkWindowClosure.forcedCliTermination, false);
    const serverLedger = await readLedgerWithOwnerReleaseRecovery(artifacts, directories, provider.endpoint, thread.threadId);
    assert.equal(serverLedger.summary.measuredRequestCount, 1);
    assert.equal(serverLedger.summary.newInputTokens, 1_920);
    assert.equal(serverLedger.summary.outputTokens, 449);
    assert.equal(serverLedger.summary.cacheReadTokens, 22_080);
    assert.equal(serverLedger.summary.cacheCompleteInputTokens, 24_000);
    assert.equal(serverLedger.summary.cacheCompleteReadTokens, 22_080);
    assert.equal(serverLedger.contextUsage.inputTokens, 24_000);
    assert.equal(serverLedger.contextUsage.outputTokens, 449);
    assert.equal(serverLedger.contextUsage.profile.contextWindowTokens, 1_000_000);
    outcome = {
      status: "completed",
      visualPassed: true,
      exitRecoveryPassed: true,
      artifactFingerprint: { cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256 },
      provider: "one loopback-only OpenAI Responses request; no paid or real Provider call",
      threadId: thread.threadId,
      foregroundPolicy: {
        backgroundScreenCapture: true,
        activationAttempted: false,
        focusTransferredDuringStartup: foregroundRecovery.focusTransferredDuringStartup,
        foregroundBefore,
        foregroundAfterLaunch: foregroundRecovery.foregroundAfterLaunch,
        foregroundAfterRestore: foregroundRecovery.foregroundAfterRestore,
        foregroundAfterPlacement: foregroundRecovery.foregroundAfterPlacement,
        recoveryPlacement: foregroundRecovery.recoveryPlacement,
        foregroundAfterCapture,
      },
      terminalWindowState: { initialWindow, placement, consoleDiagnostic },
      expectedFromFixture: { newInputTokens: 1_920, outputTokens: 449, cacheReadTokens: 22_080, cacheHitPercent: 92, contextWindowTokens: 1_000_000 },
      serverLedger,
      screenshots: { measuredWide: { path: join(evidenceRoot, screenshotName), ...measuredScreenshot } },
      cleanup: { terminal: darkWindowClosure, isolatedAppServerProcessIds: [], isolatedProfileRemoved: false },
    };
  } catch (error) {
    primaryFailure = error instanceof Error ? error : new Error(String(error));
  } finally {
    if (provider) provider.release();
    if (darkWindow && !darkWindowClosure && terminalHelper) {
      try {
        if (!darkWindow.ownerProcessId) {
          const partialWindow = JSON.parse(await runTerminalAction(terminalHelper, ["-Action", "probe", "-Title", darkWindow.title]));
          if (partialWindow.exists) {
            darkWindow.ownerProcessId = partialWindow.ownerProcessId;
            darkWindow.windowHandle = partialWindow.handle;
          }
        }
        if (await stat(darkWindow.cliState).then(() => true, () => false)) {
          darkWindowClosure = await closeTerminal(terminalHelper, darkWindow);
        } else if (darkWindow.ownerProcessId) {
          await runTerminalAction(terminalHelper, ["-Action", "close", "-Title", darkWindow.title, "-ExpectedOwnerProcessId", String(darkWindow.ownerProcessId)]);
          darkWindowClosure = { cliExited: false, exitCode: null, forcedCliTermination: false, windowCleanupMethod: "wm-close-before-cli-state" };
        }
      } catch (error) {
        cleanupFailures.push(error);
        if (await stat(darkWindow.cliState).then(() => true, () => false)) {
          await forceCloseOwnedTerminal(darkWindow, terminalHelper).catch((cleanupError) => cleanupFailures.push(cleanupError));
        }
        if (darkWindow.ownerProcessId) {
          await runTerminalAction(terminalHelper, ["-Action", "close", "-Title", darkWindow.title, "-ExpectedOwnerProcessId", String(darkWindow.ownerProcessId)]).catch((cleanupError) => cleanupFailures.push(cleanupError));
        }
      }
    }
    if (provider) await provider.close().catch((error) => cleanupFailures.push(error));
    if (isolated) {
      try {
        if (directories) stoppedAppServerProcessIds.push(...await stopIsolatedAppServers(artifacts, directories));
        const cleanupRoot = resolve(isolated.root);
        const physicalTempRoot = resolve(await realpath(tmpdir()));
        assert.equal(dirname(cleanupRoot).toLowerCase(), physicalTempRoot.toLowerCase(), "profile cleanup must stay directly inside the machine's temporary directory");
        assert.ok(cleanupRoot.split(/[\\/]/u).at(-1)?.startsWith("ja-real-provider-"), "profile cleanup must target this run's unique isolated directory");
        await rm(cleanupRoot, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
      } catch (error) {
        cleanupFailures.push(error);
      }
    }
  }
  if (cleanupFailures.length > 0) {
    if (!primaryFailure) primaryFailure = cleanupFailures[0] instanceof Error ? cleanupFailures[0] : new Error(String(cleanupFailures[0]));
    else primaryFailure.cleanupFailures = cleanupFailures.map((error) => String(error?.message ?? error));
  }
  if (primaryFailure) {
    const failedReport = {
      status: "failed",
      visualPassed: Boolean(outcome?.visualPassed),
      exitRecoveryPassed: Boolean(darkWindowClosure?.cliExited && darkWindowClosure?.exitCode === 0 && darkWindowClosure?.shellMarkerObserved),
      artifactFingerprint: { cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256 },
      error: String(primaryFailure.message),
      diagnosticPath: primaryFailure.diagnosticPath ?? primaryFailure.firstAttemptDiagnosticPath ?? null,
      ownerReleaseRecovery: primaryFailure.ownerReleaseRecovery ?? null,
      cleanupFailures: cleanupFailures.map((error) => String(error?.message ?? error)),
      terminal: darkWindowClosure,
      isolatedAppServerProcessIds: stoppedAppServerProcessIds,
      isolatedProfileRemoved: isolated ? !(await stat(isolated.root).then(() => true, () => false)) : true,
    };
    await writeFile(join(evidenceRoot, `dark-background-${runId}-report.json`), `${JSON.stringify(failedReport, null, 2)}\n`, "utf8").catch(() => undefined);
    throw primaryFailure;
  }
  outcome.cleanup.isolatedAppServerProcessIds = stoppedAppServerProcessIds;
  outcome.cleanup.isolatedProfileRemoved = isolated ? !(await stat(isolated.root).then(() => true, () => false)) : true;
  await writeFile(join(evidenceRoot, `dark-background-${runId}-report.json`), `${JSON.stringify(outcome, null, 2)}\n`, "utf8");
  return outcome;
}

/** 为浅色主题实测首请求未知占位到 Provider 计量，并覆盖缩放、窄窗与 F5 草稿；刷新和清草稿均留证后才清理。 @author kongweiguang */
export async function runCliUsageLightAcceptance() {
  await prepareEvidenceDirectory();
  const artifacts = await resolveArtifacts();
  let provider;
  let isolated;
  let directories;
  let terminalHelper;
  let lightWindow;
  let lightWindowClosure;
  let inputSubmissionEvidence;
  let primaryFailure;
  const cleanupFailures = [];
  const stoppedAppServerProcessIds = [];
  let outcome;
  try {
    provider = await startUsageProvider();
    isolated = await createIsolatedDirectories();
    directories = {
      ...isolated,
      data: join(isolated.home, "data"),
      run: join(isolated.home, "run"),
      logs: join(isolated.home, "logs", "java"),
    };
    await Promise.all([directories.data, directories.run, directories.logs].map((path) => mkdir(path, { recursive: true })));
    const thread = await seedThread(artifacts, directories, provider.endpoint);
    const wt = await resolveWindowsTerminal();
    terminalHelper = await writeWindowHelper();
    lightWindow = await launchTerminal(wt, artifacts, directories, thread.threadId, "One Half Light", terminalHelper);
    assert.equal(lightWindow.focusRestoreError, undefined, "light terminal startup must return focus to the original window");
    await waitForFile(lightWindow.started, "light terminal launcher startup", 20_000);
    const consoleDiagnostic = JSON.parse(await waitForFile(lightWindow.diagnostic, "light terminal console diagnostic", 20_000));
    const initialWindow = await waitForTerminalWindow(terminalHelper, lightWindow);
    const placement = await placeTerminalForAcceptance(terminalHelper, lightWindow);
    const unknownSnapshot = await waitForTerminalConsoleState(
      lightWindow,
      "light-initial-unknown",
      ["composer", "model", "unknownUsage"],
      30_000,
    );
    const unknownScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-initial-unknown-wide.png");
    assertUnknownUsageFooter(unknownScreenshot.consoleBuffer.visibleRows, unknownScreenshot.consoleBuffer.windowSize.width, "light initial usage");
    assert.ok(unknownSnapshot.windowSize.width >= 78, "light startup must expose the full fixed footer before input");
    lightWindow.initialPrompt = userMarker;
    const requestInputFocus = await submitTuiPrompt(terminalHelper, lightWindow, userMarker);
    const inputScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-input-after-submit.png");
    const inputRows = inputScreenshot.consoleBuffer.visibleRows;
    const markerRowIndices = inputRows
      .map((row, index) => row.includes(userMarker) ? index : -1)
      .filter((index) => index >= 0);
    const cursorRow = inputScreenshot.consoleBuffer.cursorPosition.y;
    inputSubmissionEvidence = {
      focusHandle: requestInputFocus.focusHandle,
      focusClass: requestInputFocus.focusClass,
      terminalProcessId: requestInputFocus.terminalProcessId,
      foregroundBefore: requestInputFocus.foregroundBefore,
      foregroundAfterInput: requestInputFocus.foregroundAfter,
      foregroundAfterScreenshot: await readForegroundHandle(terminalHelper),
      markerRowIndices,
      cursorRow,
      markerInComposer: markerRowIndices.includes(cursorRow),
      markerInTranscript: markerRowIndices.some((index) => index < cursorRow),
      screenshot: inputScreenshot,
    };
    lightWindow.inputMarkerStillInComposer = inputSubmissionEvidence.markerInComposer;
    await writeFile(join(evidenceRoot, "light-input-submission.json"), `${JSON.stringify(inputSubmissionEvidence, null, 2)}\n`, "utf8");
    assert.equal(inputSubmissionEvidence.foregroundAfterScreenshot, requestInputFocus.foregroundBefore,
      "input and screenshot actions must restore the original foreground before Provider waits");
    assert.equal(inputSubmissionEvidence.markerInTranscript, true,
      "the exact typed acceptance marker must move into TUI history when Enter is consumed");
    assert.equal(inputSubmissionEvidence.markerInComposer, false,
      "the acceptance marker must not remain as an unsent Composer draft after Enter");

    const heldRequest = await Promise.race([
      provider.received,
      delay(45_000).then(() => { throw new Error("isolated Provider did not receive the interactive light-window request"); }),
    ]);
    assert.equal(heldRequest.requestCount, 1);
    assert.equal(heldRequest.markerFound, true, "local Provider must see the exact visual-acceptance marker");
    const estimatedScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-estimated-wide.png");
    const estimateRows = estimatedScreenshot.consoleBuffer.visibleRows.join("").replaceAll(/\s/gu, "");
    assert.match(estimateRows, /上下文(?:\d+(?:\.\d+)?k|\d+)\/1\.0M\s*(?:<0\.1|\d+(?:\.\d+)?)%/u,
      "an in-flight local estimate must show its context size and share without an approximate label");
    assert.doesNotMatch(estimateRows, /约|已报告|正在读取|待刷新|未知/u,
      "the footer keeps transient usage state out of its stable layout");
    provider.release();
    assert.equal((await provider.waitForResponses(1)).requestCount, 1);
    await delay(2_000);

    const measuredScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-measured-wide.png");
    const measuredRows = measuredScreenshot.consoleBuffer.visibleRows.join("\n");
    const measuredFooter = measuredRows.replaceAll(/\s/gu, "");
    assert.match(measuredFooter, /累计24k/u, "light footer must show total reported usage");
    assert.match(measuredFooter, /输入24k/u, "light footer input must include cached tokens");
    assert.match(measuredFooter, /输出449/u, "light footer must show output from the local Provider ledger");
    assert.match(measuredFooter, /含缓存读22k/u, "light footer must show cached input reads within input");
    assert.match(measuredFooter, /上下文24k\/1\.0M2\.4%/u, "light footer must show the measured assistant context share");
    assert.doesNotMatch(measuredFooter, /约|已报告|正在读取|待刷新|未知/u,
      "the completed footer must show values without transient status wording");
    assertMeasuredUsageFooterLayout(measuredScreenshot.consoleBuffer.visibleRows, measuredScreenshot.consoleBuffer.windowSize.width, "light measured-wide");

    await sendConsoleCliAction(terminalHelper, lightWindow, "{F5}");
    await delay(750);
    const refreshedScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-refreshed-wide.png");
    const refreshedRows = refreshedScreenshot.consoleBuffer.visibleRows.join("\n");
    assert.match(refreshedRows.replaceAll(/\s/gu, ""), /累计24k/u, "F5 must retain cumulative usage in the light window");
    assert.match(refreshedRows.replaceAll(/\s/gu, ""), /上下文24k\/1\.0M2\.4%/u, "F5 must retain the latest ordinary assistant context sample");

    await sendConsoleCliAction(terminalHelper, lightWindow, "draft:preserved-across-f5");
    await delay(250);
    const draftBeforeRefreshScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-draft-before-f5.png");
    const draftBeforeRefreshRows = draftBeforeRefreshScreenshot.consoleBuffer.visibleRows.join("\n");
    assert.match(draftBeforeRefreshRows, /preserved-across-f5/u, "light Composer draft must appear before F5");
    await sendConsoleCliAction(terminalHelper, lightWindow, "{F5}");
    await delay(750);
    const draftAfterRefreshScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-draft-after-f5.png");
    const draftAfterRefreshRows = draftAfterRefreshScreenshot.consoleBuffer.visibleRows.join("\n");
    assert.match(draftAfterRefreshRows, /preserved-across-f5/u, "F5 must preserve an unsubmitted light-window Composer draft");
    assert.match(draftAfterRefreshRows.replaceAll(/\s/gu, ""), /累计24k/u, "F5 with a draft must retain cumulative usage");
    await sendConsoleCliAction(terminalHelper, lightWindow, "clear-test-draft");
    await delay(250);
    const draftClearedScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-draft-cleared.png");
    const draftClearedRows = draftClearedScreenshot.consoleBuffer.visibleRows.join("\n");
    assert.doesNotMatch(draftClearedRows, /preserved-across-f5/u, "bounded Backspace input must clear only the F5 test draft");
    assert.match(draftClearedRows.replaceAll(/\s/gu, ""), /累计24k/u, "clearing a Composer draft must not affect usage");

    const baseGrid = refreshedScreenshot.consoleBuffer.windowSize;
    await sendWindowsTerminalShortcut(terminalHelper, lightWindow, "^=");
    await delay(700);
    const zoomedScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-zoomed-wide.png");
    const zoomedGrid = zoomedScreenshot.consoleBuffer.windowSize;
    assert.ok(zoomedGrid.width < baseGrid.width || zoomedGrid.height < baseGrid.height, "Ctrl+= must change the Windows Terminal cell grid");
    await sendWindowsTerminalShortcut(terminalHelper, lightWindow, "^-");
    await delay(700);
    const restoredZoomScreenshot = await captureTerminal(terminalHelper, lightWindow, "light-final-zoom-restored-wide.png");

    const narrowScreenshot = await captureResizedTerminal(terminalHelper, lightWindow, "light-final-measured-narrow.png", 720, 740);
    assertMeasuredUsageFooterLayout(narrowScreenshot.consoleBuffer.visibleRows, narrowScreenshot.consoleBuffer.windowSize.width, "light measured-narrow");

    lightWindowClosure = await closeTerminal(terminalHelper, lightWindow);
    assert.equal(lightWindowClosure.cliExited, true, "light-window CLI must exit on Ctrl+D before profile cleanup");
    assert.equal(lightWindowClosure.exitCode, 0, "light-window CLI must report successful exit");
    assert.equal(lightWindowClosure.shellMarkerObserved, true, "restored PowerShell shell must execute and display the Chinese recovery marker");
    assert.equal(lightWindowClosure.shellMarkerExecutionMethod, "PowerShell launcher after CLI exit");
    assert.equal(lightWindowClosure.shellMarkerActionError, undefined, "recovery marker must be sent only after the CLI has restored the shell");
    assert.equal(lightWindowClosure.forcedCliTermination, false, "normal CLI exit must not need process termination");
    const serverLedger = await readLedgerWithOwnerReleaseRecovery(artifacts, directories, provider.endpoint, thread.threadId);
    outcome = {
      status: "completed",
      runtime: "JDK 25 App Server JAR + final isolated Windows Terminal CLI",
      provider: "loopback-only OpenAI Responses fixture; one local request, no paid or real Provider call",
      artifactFingerprint: { cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256 },
      threadId: thread.threadId,
      serverLedger,
      terminalWindowState: { initialWindow, placement, consoleDiagnostic, requestInputFocus, inputSubmissionEvidence },
      screenshots: {
        initialUnknownWide: unknownScreenshot,
        inputSubmittedWide: inputScreenshot,
        estimatedWide: estimatedScreenshot,
        measuredWide: measuredScreenshot,
        refreshedWide: refreshedScreenshot,
        draftBeforeF5: draftBeforeRefreshScreenshot,
        draftAfterF5: draftAfterRefreshScreenshot,
        draftCleared: draftClearedScreenshot,
        zoomedWide: zoomedScreenshot,
        zoomRestoredWide: restoredZoomScreenshot,
        measuredNarrow: narrowScreenshot,
        shellRestored: lightWindowClosure.shellRestoredScreenshot,
      },
      cleanup: { terminal: lightWindowClosure, isolatedAppServerProcessIds: [], isolatedProfileRemoved: false },
    };
  } catch (error) {
    primaryFailure = error instanceof Error ? error : new Error(String(error));
  } finally {
    if (lightWindow && !lightWindowClosure && terminalHelper) {
      if (lightWindow.inputMarkerStillInComposer) {
        try {
          await sendConsoleCliAction(terminalHelper, lightWindow, "clear-test-prompt");
          const clearedComposer = await waitForNativeConsoleComposer(terminalHelper, lightWindow);
          assert.ok(clearedComposer.cursorPosition.x <= 3, "test-only prompt cleanup must restore an empty Composer before Ctrl+D");
        } catch (error) {
          cleanupFailures.push(error);
        }
      }
      if (provider) provider.release();
      lightWindowClosure = await closeTerminal(terminalHelper, lightWindow).catch(async (error) => {
        cleanupFailures.push(error);
        return await forceCloseOwnedTerminal(lightWindow, terminalHelper).catch((cleanupError) => {
          cleanupFailures.push(cleanupError);
          return { cliExited: false, exitCode: null, shellMarkerInputSent: false, forcedCliTermination: false, windowCleanupMethod: "cleanup-failed" };
        });
      });
    }
    if (provider) await provider.close().catch((error) => cleanupFailures.push(error));
    if (isolated) {
      try {
        if (directories) stoppedAppServerProcessIds.push(...await stopIsolatedAppServers(artifacts, directories));
        const cleanupRoot = resolve(isolated.root);
        const physicalTempRoot = resolve(await realpath(tmpdir()));
        assert.equal(dirname(cleanupRoot).toLowerCase(), physicalTempRoot.toLowerCase(), "profile cleanup must stay directly inside this machine's temporary directory");
        assert.ok(cleanupRoot.split(/[\\/]/u).at(-1)?.startsWith("ja-real-provider-"), "profile cleanup must target this fixture's isolated directory");
        await rm(cleanupRoot, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
      } catch (error) {
        cleanupFailures.push(error);
      }
    }
  }
  if (cleanupFailures.length > 0) {
    if (!primaryFailure) primaryFailure = cleanupFailures[0] instanceof Error ? cleanupFailures[0] : new Error(String(cleanupFailures[0]));
    else primaryFailure.cleanupFailures = cleanupFailures.map((error) => String(error?.message ?? error));
  }
  if (primaryFailure) {
    const failedReport = {
      status: "failed",
      artifactFingerprint: { cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256 },
      error: String(primaryFailure.message),
      inputSubmissionEvidence,
      diagnosticPath: primaryFailure.diagnosticPath ?? primaryFailure.firstAttemptDiagnosticPath ?? null,
      ownerReleaseRecovery: primaryFailure.ownerReleaseRecovery ?? null,
      cleanupFailures: cleanupFailures.map((error) => String(error?.message ?? error)),
      terminalWindow: lightWindowClosure,
      isolatedAppServerProcessIds: stoppedAppServerProcessIds,
      isolatedProfileRemoved: isolated ? !(await stat(isolated.root).then(() => true, () => false)) : true,
    };
    await writeFile(join(evidenceRoot, "light-acceptance-failure.json"), `${JSON.stringify(failedReport, null, 2)}\n`, "utf8").catch(() => undefined);
    throw primaryFailure;
  }
  outcome.cleanup.terminal = lightWindowClosure;
  outcome.cleanup.isolatedAppServerProcessIds = stoppedAppServerProcessIds;
  outcome.cleanup.isolatedProfileRemoved = isolated ? !(await stat(isolated.root).then(() => true, () => false)) : true;
  await writeFile(join(evidenceRoot, "light-acceptance-report.json"), `${JSON.stringify(outcome, null, 2)}\n`, "utf8");
  return outcome;
}

/** 用一个本地 Provider 回合最短复现正式压缩 RPC；失败证据不包含会话正文或凭据。 @author kongweiguang */
export async function runCliUsageCompactDiagnosticAcceptance() {
  await prepareEvidenceDirectory();
  const artifacts = await resolveArtifacts();
  let provider;
  let isolated;
  let directories;
  let terminalHelper;
  let terminal;
  let terminalWindowState;
  let terminalClosure;
  let beforeCompaction;
  let compaction;
  let afterCompaction;
  let primaryFailure;
  const cleanupFailures = [];
  const stoppedAppServerProcessIds = [];
  try {
    provider = await startUsageProvider();
    isolated = await createIsolatedDirectories();
    directories = {
      ...isolated,
      data: join(isolated.home, "data"),
      run: join(isolated.home, "run"),
      logs: join(isolated.home, "logs", "java"),
    };
    await Promise.all([directories.data, directories.run, directories.logs].map((path) => mkdir(path, { recursive: true })));
    const thread = await seedThread(artifacts, directories, provider.endpoint);
    const wt = await resolveWindowsTerminal();
    terminalHelper = await writeWindowHelper();
    terminal = await launchTerminal(wt, artifacts, directories, thread.threadId, "Campbell", terminalHelper, userMarker);
    assert.equal(terminal.focusRestoreError, undefined, "diagnostic window startup must restore the original foreground");
    await waitForFile(terminal.started, "compact diagnostic launcher startup", 20_000);
    await waitForFile(terminal.diagnostic, "compact diagnostic console setup", 20_000);
    terminalWindowState = await waitForTerminalWindow(terminalHelper, terminal);
    const request = await Promise.race([
      provider.received,
      delay(45_000).then(() => { throw new Error("local Provider did not receive the compact diagnostic turn"); }),
    ]);
    assert.equal(request.requestCount, 1);
    assert.equal(request.markerFound, true, "only the isolated user marker may trigger this ordinary turn");
    provider.release();
    await provider.waitForResponses(1);
    await delay(2_500);
    terminalClosure = await closeTerminal(terminalHelper, terminal);
    assert.equal(terminalClosure.cliExited, true, "diagnostic CLI must exit normally before compaction inspection");
    assert.equal(terminalClosure.exitCode, 0);

    beforeCompaction = await readLedgerWithOwnerReleaseRecovery(artifacts, directories, provider.endpoint, thread.threadId);
    compaction = await compactThread(artifacts, directories, provider.endpoint, thread.threadId, thread.workspaceId, provider);
    if (compaction.result.outcome === "compacted") await provider.waitForResponses(2);
    afterCompaction = await readLedgerWithOwnerReleaseRecovery(artifacts, directories, provider.endpoint, thread.threadId, {
      verifyInitialFixture: false,
    });
  } catch (error) {
    primaryFailure = error instanceof Error ? error : new Error(String(error));
  } finally {
    if (terminal && !terminalClosure && terminalHelper) {
      if (provider) provider.release();
      terminalClosure = await closeTerminal(terminalHelper, terminal).catch(async (error) => {
        cleanupFailures.push(error);
        return await forceCloseOwnedTerminal(terminal, terminalHelper).catch((cleanupError) => {
          cleanupFailures.push(cleanupError);
          return { cliExited: false, exitCode: null, forcedCliTermination: false, windowCleanupMethod: "cleanup-failed" };
        });
      });
    }
    if (provider) await provider.close().catch((error) => cleanupFailures.push(error));
    if (isolated) {
      try {
        if (directories) stoppedAppServerProcessIds.push(...await stopIsolatedAppServers(artifacts, directories));
        const cleanupRoot = resolve(isolated.root);
        const physicalTempRoot = resolve(await realpath(tmpdir()));
        assert.equal(dirname(cleanupRoot).toLowerCase(), physicalTempRoot.toLowerCase(), "diagnostic profile cleanup must stay directly inside Temp");
        assert.ok(cleanupRoot.split(/[\\/]/u).at(-1)?.startsWith("ja-real-provider-"), "diagnostic profile cleanup must target its unique fixture");
        await rm(cleanupRoot, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
      } catch (error) {
        cleanupFailures.push(error);
      }
    }
  }
  if (cleanupFailures.length > 0) {
    if (!primaryFailure) primaryFailure = cleanupFailures[0] instanceof Error ? cleanupFailures[0] : new Error(String(cleanupFailures[0]));
    else primaryFailure.cleanupFailures = cleanupFailures.map((error) => String(error?.message ?? error));
  }
  const report = {
    status: primaryFailure ? "failed" : "completed",
    artifactFingerprint: { cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256 },
    providerRequests: provider?.snapshotRequests?.() ?? null,
    beforeCompaction: beforeCompaction?.summary ?? null,
    compaction: compaction ? {
      result: compaction.result,
      previousThreadRevision: compaction.previousThreadRevision,
      previousHistoryCounts: compaction.previousHistoryCounts,
      providerRequests: compaction.providerRequests,
    } : null,
    afterCompaction: afterCompaction?.summary ?? null,
    error: primaryFailure ? String(primaryFailure.message) : null,
    diagnosticPath: primaryFailure?.diagnosticPath ?? null,
    terminalWindowState,
    cleanupFailures: cleanupFailures.map((error) => String(error?.message ?? error)),
    terminal: terminalClosure,
    isolatedAppServerProcessIds: stoppedAppServerProcessIds,
    isolatedProfileRemoved: isolated ? !(await stat(isolated.root).then(() => true, () => false)) : true,
  };
  await writeFile(join(evidenceRoot, "compact-diagnostic-report.json"), `${JSON.stringify(report, null, 2)}\n`, "utf8");
  if (primaryFailure) throw primaryFailure;
  return report;
}

/** 单独用空 Thread 验证 Ctrl+D 返回码和 PowerShell 中文 marker，避免既有对话草稿影响退出判定。 @author kongweiguang */
export async function runCliUsageExitRecoveryAcceptance() {
  await prepareEvidenceDirectory();
  const artifacts = await resolveArtifacts();
  let provider;
  let isolated;
  let directories;
  let terminalHelper;
  let terminal;
  let terminalClosure;
  let emptyComposerVerified = false;
  let primaryFailure;
  const cleanupFailures = [];
  const stoppedAppServerProcessIds = [];
  let outcome;
  try {
    provider = await startUsageProvider();
    isolated = await createIsolatedDirectories();
    directories = {
      ...isolated,
      data: join(isolated.home, "data"),
      run: join(isolated.home, "run"),
      logs: join(isolated.home, "logs", "java"),
    };
    await Promise.all([directories.data, directories.run, directories.logs].map((path) => mkdir(path, { recursive: true })));
    const thread = await seedThread(artifacts, directories, provider.endpoint);
    const wt = await resolveWindowsTerminal();
    terminalHelper = await writeWindowHelper();
    terminal = await launchTerminal(wt, artifacts, directories, thread.threadId, "One Half Light", terminalHelper);
    assert.equal(terminal.focusRestoreError, undefined, "empty-thread terminal startup must restore the original foreground");
    await waitForFile(terminal.started, "empty-thread terminal launcher startup", 20_000);
    const consoleDiagnostic = JSON.parse(await waitForFile(terminal.diagnostic, "empty-thread console diagnostic", 20_000));
    const initialWindow = await waitForTerminalWindow(terminalHelper, terminal);
    const emptyComposerBuffer = await waitForNativeConsoleComposer(terminalHelper, terminal);
    const { cursorPosition, cursorRow } = emptyComposerBuffer;
    assert.ok(cursorRow, "native console snapshot must show an empty composer row before Ctrl+D");
    assert.ok(cursorPosition.x <= 3, "empty composer cursor must remain before any draft text");
    emptyComposerVerified = true;

    terminalClosure = await closeTerminal(terminalHelper, terminal);
    assert.equal(terminalClosure.cliExited, true, "empty-thread CLI must exit normally on Ctrl+D");
    assert.equal(terminalClosure.exitCode, 0, "empty-thread CLI must report successful normal exit");
    assert.equal(terminalClosure.shellMarkerInputSent, false, "the Shell marker is generated by PowerShell, not keyboard input");
    assert.equal(terminalClosure.shellMarkerObserved, true, "restored PowerShell shell must execute and display the Chinese recovery marker");
    assert.equal(terminalClosure.shellMarkerExecutionMethod, "PowerShell launcher after CLI exit");
    assert.equal(terminalClosure.shellMarkerActionError, undefined, "recovery marker must be displayed only after the CLI restores the shell");
    assert.equal(terminalClosure.forcedCliTermination, false, "normal exit recovery must not terminate the CLI process");

    outcome = {
      status: "completed",
      artifactFingerprint: { cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256 },
      threadId: thread.threadId,
      terminalWindowState: { initialWindow, placement: "not-repositioned-or-focused", consoleDiagnostic },
      emptyComposer: { cursorPosition, cursorRow, promptMarker: emptyComposerBuffer.promptMarker, promptMarkerEncodingFallback: emptyComposerBuffer.promptMarkerEncodingFallback, snapshotMethod: emptyComposerBuffer.snapshotMethod, snapshotPath: emptyComposerBuffer.snapshotPath },
      terminal: terminalClosure,
      consoleBuffers: { emptyComposer: emptyComposerBuffer, shellRestored: terminalClosure.shellRestoredBuffer },
      cleanup: { isolatedAppServerProcessIds: [], isolatedProfileRemoved: false },
    };
  } catch (error) {
    primaryFailure = error instanceof Error ? error : new Error(String(error));
  } finally {
    if (terminal && !terminalClosure && terminalHelper) {
      if (emptyComposerVerified) {
        terminalClosure = await closeTerminal(terminalHelper, terminal).catch(async (error) => {
          cleanupFailures.push(error);
          return await forceCloseOwnedTerminal(terminal, terminalHelper).catch((cleanupError) => {
            cleanupFailures.push(cleanupError);
            return { cliExited: false, exitCode: null, shellMarkerInputSent: false, forcedCliTermination: false, windowCleanupMethod: "cleanup-failed" };
          });
        });
      } else {
        terminalClosure = await forceCloseOwnedTerminal(terminal, terminalHelper).catch((error) => {
          cleanupFailures.push(error);
          return { cliExited: false, exitCode: null, shellMarkerInputSent: false, forcedCliTermination: false, windowCleanupMethod: "cleanup-failed" };
        });
      }
    }
    if (provider) await provider.close().catch((error) => cleanupFailures.push(error));
    if (isolated) {
      try {
        if (directories) stoppedAppServerProcessIds.push(...await stopIsolatedAppServers(artifacts, directories));
        const cleanupRoot = resolve(isolated.root);
        const physicalTempRoot = resolve(await realpath(tmpdir()));
        assert.equal(dirname(cleanupRoot).toLowerCase(), physicalTempRoot.toLowerCase(), "profile cleanup must stay directly inside this machine's temporary directory");
        assert.ok(cleanupRoot.split(/[\\/]/u).at(-1)?.startsWith("ja-real-provider-"), "profile cleanup must target this fixture's isolated directory");
        await rm(cleanupRoot, { recursive: true, force: false, maxRetries: 5, retryDelay: 200 });
      } catch (error) {
        cleanupFailures.push(error);
      }
    }
  }
  if (cleanupFailures.length > 0) {
    if (!primaryFailure) primaryFailure = cleanupFailures[0] instanceof Error ? cleanupFailures[0] : new Error(String(cleanupFailures[0]));
    else primaryFailure.cleanupFailures = cleanupFailures.map((error) => String(error?.message ?? error));
  }
  const priorVisualFailure = await readFile(join(evidenceRoot, "light-acceptance-failure.json"), "utf8").then((contents) => JSON.parse(contents)).catch(() => undefined);
  const priorExitRecoveryAttempts = [];
  for (const filename of [
    "exit-attempt-01-normal-cli-exit-marker-unverified.json",
    "exit-attempt-02-empty-thread-ctrl-d-forced.json",
    "exit-attempt-03-shell-marker-not-proven.json",
    "exit-attempt-04-wt-foreground-denied.json",
    "exit-attempt-05-console-stdout-encoding-fallback.json",
  ]) {
    const attempt = await readFile(join(evidenceRoot, filename), "utf8").then((contents) => JSON.parse(contents)).catch(() => undefined);
    if (attempt) priorExitRecoveryAttempts.push({ filename, status: attempt.status, terminal: attempt.terminal, emptyComposer: attempt.emptyComposer, evidence: attempt.evidence, cliExitedNormally: attempt.cliExitedNormally, exitCode: attempt.terminal?.exitCode ?? attempt.launcherExitCodeAfterForcedCleanup, markerObserved: attempt.terminal?.shellMarkerObserved ?? false, forcedCliTermination: attempt.terminal?.forcedCliTermination ?? attempt.forcedCliTermination, promptMarkerEncodingFallback: attempt.emptyComposer?.promptMarkerEncodingFallback ?? false, windowCleanupMethod: attempt.terminal?.windowCleanupMethod ?? attempt.windowCleanupMethod });
  }
  if (primaryFailure) {
    const failedReport = {
      status: "failed",
      visualPassed: Boolean(priorVisualFailure?.artifactFingerprint?.cliSha256 === artifacts.cliSha256),
      exitRecoveryPassed: false,
      artifactFingerprint: { cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256 },
      priorVisualFailure,
      priorExitRecoveryAttempts,
      error: String(primaryFailure.message),
      diagnosticPath: primaryFailure.diagnosticPath ?? primaryFailure.firstAttemptDiagnosticPath ?? null,
      ownerReleaseRecovery: primaryFailure.ownerReleaseRecovery ?? null,
      cleanupFailures: cleanupFailures.map((error) => String(error?.message ?? error)),
      terminal: terminalClosure,
      isolatedAppServerProcessIds: stoppedAppServerProcessIds,
      isolatedProfileRemoved: isolated ? !(await stat(isolated.root).then(() => true, () => false)) : true,
    };
    await writeFile(join(evidenceRoot, "exit-recovery-failure.json"), `${JSON.stringify(failedReport, null, 2)}\n`, "utf8").catch(() => undefined);
    await writeFile(join(evidenceRoot, "final-window-acceptance.json"), `${JSON.stringify(failedReport, null, 2)}\n`, "utf8").catch(() => undefined);
    throw primaryFailure;
  }
  outcome.cleanup.isolatedAppServerProcessIds = stoppedAppServerProcessIds;
  outcome.cleanup.isolatedProfileRemoved = isolated ? !(await stat(isolated.root).then(() => true, () => false)) : true;
  const finalReport = {
    ...outcome,
    visualPassed: Boolean(priorVisualFailure?.artifactFingerprint?.cliSha256 === artifacts.cliSha256),
    exitRecoveryPassed: true,
    priorVisualFailure,
    priorExitRecoveryAttempts,
  };
  await writeFile(join(evidenceRoot, "final-window-acceptance.json"), `${JSON.stringify(finalReport, null, 2)}\n`, "utf8");
  return finalReport;
}

/** 命令行独立入口只输出无敏感正文的摘要，完整验证值留在 target 证据文件。 @author kongweiguang */
async function main() {
  try {
    const outcome = process.env.JA_TEST_COMPACT_DIAGNOSTIC_ONLY === "1"
      ? await runCliUsageCompactDiagnosticAcceptance()
      : process.env.JA_TEST_DARK_BACKGROUND_ONLY === "1"
      ? await runCliUsageDarkBackgroundAcceptance()
      : process.env.JA_TEST_EXIT_ONLY === "1"
      ? await runCliUsageExitRecoveryAcceptance()
      : process.env.JA_TEST_LIGHT_ONLY === "1"
      ? await runCliUsageLightAcceptance()
      : await runCliUsageAcceptance();
    console.log(JSON.stringify({
      status: outcome.status,
      evidenceRoot,
      threadId: outcome.threadId ?? null,
      ledger: outcome.serverLedger?.summary ?? null,
      visualPassed: outcome.visualPassed,
      exitRecoveryPassed: outcome.exitRecoveryPassed,
      screenshots: Object.keys(outcome.screenshots ?? {}),
      consoleSnapshots: Object.keys(outcome.consoleBuffers ?? {}),
      cleanup: outcome.cleanup,
    }));
  } catch (error) {
    console.error(`CLI usage acceptance failed: ${String(error?.message ?? error)}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
