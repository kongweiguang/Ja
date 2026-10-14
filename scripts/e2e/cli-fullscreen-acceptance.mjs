// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import {
  compileConPtyHarness,
  createRunDirectories,
  removeOwnedProfile,
  resolveArtifacts,
  startConPtyCli,
  writeScreenEvidence,
} from "./cli-usage-conpty.mjs";
import { JsonlSession, providerConfigurationDocument } from "./real-provider-smoke.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const handshakeSourcePath = join(
  repoRoot,
  "ja-agent",
  "ja-app-server",
  "src",
  "main",
  "java",
  "io",
  "github",
  "kongweiguang",
  "ja",
  "transport",
  "rpc",
  "handler",
  "HandshakeHandler.java",
);
const historyMapperEntry = "BOOT-INF/classes/io/github/kongweiguang/ja/infrastructure/persistence/mapper/HistoryMapper.xml";
const historyMapperSourcePath = join(
  repoRoot,
  "ja-agent",
  "ja-app-server",
  "src",
  "main",
  "resources",
  "io",
  "github",
  "kongweiguang",
  "ja",
  "infrastructure",
  "persistence",
  "mapper",
  "HistoryMapper.xml",
);
const apiSecret = "ja-cli-usage-conpty-placeholder";
const providerId = "provider_cli_fullscreen_acceptance";
const modelId = "model_cli_fullscreen_acceptance";
const credentialId = "cred_cli_fullscreen_acceptance";
const promptMarker = "JA_CLI_FULLSCREEN_ACCEPTANCE_PROMPT";
const draftText = "全屏草稿🙂";
/** 让 ConPTY 在每段 SSE 到达之间绘制新帧，以便验证浏览状态与位置锚点。 @author kongweiguang */
const delay = (milliseconds) => new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));

/** Responses SSE 帧统一维护递增序号，保证 ConPTY 覆盖真实的 Provider 解码和流式归档路径。 @author kongweiguang */
function responseEvent(type, sequence, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequence, ...payload })}\n\n`;
}

/** 从 Java 握手类提取冻结词汇表，避免复用版本落后的其他 E2E 脚本参数。 @author kongweiguang */
function javaStringList(source, name) {
  const declaration = source.match(
    new RegExp(`static final List<String> ${name} = List\\.of\\(([\\s\\S]*?)\\);`, "u"),
  );
  assert.ok(declaration, `HandshakeHandler is missing the ${name} declaration`);
  return [...declaration[1].matchAll(/"([^"]+)"/gu)].map((entry) => entry[1]);
}

/** 仅解析握手源码中受限的十进制乘法常量，不对 Java 片段求值或执行。 @author kongweiguang */
function javaLimitValue(expression) {
  const normalized = expression.trim();
  assert.match(normalized, /^[0-9_\s*]+$/u, "handshake limits must remain numeric constants");
  const value = normalized.split("*").reduce((product, part) => product * Number(part.trim().replaceAll("_", "")), 1);
  assert.ok(Number.isSafeInteger(value) && value > 0, "handshake limit must be a positive safe integer");
  return value;
}

/** 用同一份 Java 握手源构造 v2 initialize 请求；这只同步协议字段，不复制或放宽资源限制，缩进匹配也保持对源码边界的精确约束。 @author kongweiguang */
export async function fullscreenInitializeParams() {
  const source = await readFile(handshakeSourcePath, "utf8");
  const limitsMethod = source.match(/public static ObjectNode limits\([\s\S]*?\n {4}\}/u)?.[0];
  assert.ok(limitsMethod, "HandshakeHandler is missing its limits source");
  const limits = Object.fromEntries(
    [...limitsMethod.matchAll(/\.put\("([^"]+)",\s*([0-9_][0-9_\s*]*)\)/gu)]
      .map((entry) => [entry[1], javaLimitValue(entry[2])]),
  );
  assert.ok(Object.keys(limits).length >= 10, "HandshakeHandler limits must be fully discoverable");
  return {
    protocolMajor: 2,
    protocolMinor: 0,
    clientVersion: "ja-cli-fullscreen-acceptance",
    capabilities: {
      methods: javaStringList(source, "METHODS"),
      events: javaStringList(source, "EVENTS"),
      accessModes: javaStringList(source, "ACCESS_MODES"),
      collaborationModes: javaStringList(source, "COLLABORATION_MODES"),
      features: javaStringList(source, "FEATURES"),
    },
    limits,
  };
}

/** 只将脱敏 RPC 分类写入诊断，避免测试把 endpoint、Secret 或请求正文带到控制台。 @author kongweiguang */
function requireRpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = frame.error?.data?.errorCode ?? frame.error?.data?.category ?? "RPC_ERROR";
    throw new Error(`${operation} failed: ${String(code)}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 在测试私有 profile 中创建 provider、凭据、信任工作区及 Thread；完成后关闭短命 RPC 会话。 @author kongweiguang */
async function seedFullscreenThread(artifacts, directories, endpoint) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: apiSecret,
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
    const current = requireRpcResult(await session.request("configuration/read", {}), "configuration/read");
    const document = providerConfigurationDocument({
      endpoint,
      name: "Ja CLI full-screen local fixture",
      api: "openai_responses",
      model: "gpt-6-luna",
      selectedProviderId: providerId,
      selectedModelId: modelId,
      selectedCredentialId: credentialId,
      reasoningLevel: "xhigh",
    });
    document.providers[0].models[0].capabilities.context_window_tokens = 1_000_000;
    document.providers[0].agent_defaults.context.auto_compact = false;
    requireRpcResult(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: current.cas.userVersion,
        document,
      }),
      "configuration/replace",
    );
    requireRpcResult(
      await session.request("credential/set", {
        credentialId,
        secret: apiSecret,
        expectedVersion: current.cas.credentialVersion,
      }),
      "credential/set",
    );
    const workspace = requireRpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Ja CLI fullscreen acceptance",
      }),
      "workspace/open",
    );
    requireRpcResult(
      await session.request("workspace/set-trust", {
        workspaceId: workspace.workspaceId,
        trust: "trusted",
      }),
      "workspace/set-trust",
    );
    const thread = requireRpcResult(
      await session.request("thread/create", {
        cwd: directories.workspace,
        title: "Ja CLI 全屏 ConPTY 验收",
        providerId,
        modelId,
        reasoningLevel: "xhigh",
        accessMode: "approval_required",
        collaborationMode: "default",
      }),
      "thread/create",
    );
    assert.match(thread.threadId, /^thr_[A-Za-z0-9_.-]+$/u);
    return thread;
  } finally {
    if (ready && !session.exited) await session.shutdown().catch(() => undefined);
    await session.forceClose();
  }
}

/** 从固定 JAR 提取真实 MyBatis 映射，与当前源码逐字节比对，拒绝用过期分页 SQL 做验收。 @author kongweiguang */
async function verifyHistoryMapperArtifact(artifacts, runRoot) {
  const extractionRoot = join(runRoot, "jar-mapper-fingerprint");
  await mkdir(extractionRoot, { recursive: true });
  const jarTool = join(dirname(artifacts.java), "jar.exe");
  const extracted = spawnSync(jarTool, ["xf", artifacts.jar, historyMapperEntry], {
    cwd: extractionRoot,
    windowsHide: true,
    stdio: "ignore",
  });
  if (extracted.error || extracted.status !== 0) throw new Error("could not extract the pinned App Server mapper resource");
  const packagedSource = await readFile(join(extractionRoot, ...historyMapperEntry.split("/")));
  const currentSource = await readFile(historyMapperSourcePath);
  const packagedSha256 = createHash("sha256").update(packagedSource).digest("hex");
  const currentSha256 = createHash("sha256").update(currentSource).digest("hex");
  return { packagedSha256, currentSha256, matchesCurrentSource: packagedSha256 === currentSha256 };
}

/** 构造 Ja App Server 实际使用的最小 Responses 信封，避免 fixture 返回与正常终态不一致的快照。 @author kongweiguang */
function responseEnvelope(id, status, output, includeUsage = true) {
  const response = {
    id,
    created_at: 0,
    model: "ja-cli-fullscreen-loopback",
    object: "response",
    output,
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 32,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens: 128,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 160,
    };
  }
  return response;
}

/** 仅处理受限 loopback 请求；首个回答由测试显式放行，其余标题类请求快速结束以隔离 UI 验收。 @author kongweiguang */
async function readProviderRequest(request) {
  const chunks = [];
  let totalBytes = 0;
  for await (const chunk of request) {
    totalBytes += chunk.length;
    if (totalBytes > 8 * 1024 * 1024) throw new Error("loopback request exceeds 8 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 以相同 response/item 身份分段发送文本，使终端在更新之间可观察到稳定的阅读锚点。 @author kongweiguang */
async function writeTextResponse(response, id, text, chunkLines, pauseMs, progress) {
  const itemId = `message_${id}`;
  const outputItem = {
    id: itemId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  response.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache",
    connection: "keep-alive",
  });
  response.write(responseEvent("response.created", 0, {
    response: responseEnvelope(id, "in_progress", [], false),
  }));
  const rows = text.match(/[^\n]*\n|[^\n]+$/gu) ?? [];
  let sequence = 1;
  for (let first = 0; first < rows.length; first += chunkLines) {
    const delta = rows.slice(first, first + chunkLines).join("");
    response.write(responseEvent("response.output_text.delta", sequence, {
      content_index: 0,
      delta,
      item_id: itemId,
      output_index: 0,
      logprobs: [],
    }));
    sequence += 1;
    progress?.(sequence - 1);
    if (pauseMs > 0) await delay(pauseMs);
  }
  response.write(responseEvent("response.output_text.done", sequence, {
    content_index: 0,
    item_id: itemId,
    output_index: 0,
    text,
  }));
  sequence += 1;
  response.write(responseEvent("response.completed", sequence, {
    response: responseEnvelope(id, "completed", [outputItem]),
  }));
  response.end();
}

/** 启动仅监听 127.0.0.1 的可控 Provider；每个回合都返回完整 SSE 终态，且不触碰外部 Provider。 @author kongweiguang */
async function startFullscreenProvider() {
  let releaseFirst;
  let notifyFirstRequest;
  let notifyProgress;
  let notifyFirstComplete;
  let progressCount = 0;
  let requestCount = 0;
  let responseCount = 0;
  let firstRequestPayload;
  const firstGate = new Promise((resolvePromise) => { releaseFirst = resolvePromise; });
  const firstRequest = new Promise((resolvePromise) => { notifyFirstRequest = resolvePromise; });
  const firstComplete = new Promise((resolvePromise) => { notifyFirstComplete = resolvePromise; });
  const progress = new Promise((resolvePromise) => { notifyProgress = resolvePromise; });
  const completedText = "全屏回复完毕 ✅";
  const replyLines = Array.from({ length: 180 }, (_, index) =>
    `全屏回复行 ${String(index).padStart(3, "0")} · 浏览时不应跳动 🙂`,
  );
  replyLines.push(completedText);
  const fullReply = `${replyLines.join("\n")}\n`;
  const server = createServer(async (request, response) => {
    try {
      if (request.method !== "POST" || request.url !== "/v1/responses") {
        response.writeHead(404).end();
        return;
      }
      if (request.headers.authorization !== `Bearer ${apiSecret}`) {
        response.writeHead(401).end();
        return;
      }
      const payload = await readProviderRequest(request);
      requestCount += 1;
      if (requestCount === 1) {
        firstRequestPayload = payload;
        notifyFirstRequest({ markerFound: JSON.stringify(payload).includes(promptMarker) });
        await firstGate;
        await writeTextResponse(response, `resp_ja_fullscreen_${requestCount}`, fullReply, 3, 55, (count) => {
          progressCount = count;
          if (progressCount >= 6) notifyProgress(progressCount);
        });
      } else {
        await writeTextResponse(
          response,
          `resp_ja_fullscreen_${requestCount}`,
          "Ja CLI 全屏验收标题",
          1,
          0,
        );
      }
      responseCount += 1;
      if (requestCount === 1) notifyFirstComplete();
    } catch {
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: { message: "isolated loopback fixture rejected the request" } }));
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
    firstRequest,
    firstComplete,
    progress,
    fullReply,
    completedText,
    get firstRequestPayload() { return structuredClone(firstRequestPayload); },
    get requestCount() { return requestCount; },
    get responseCount() { return responseCount; },
    releaseFirst() { releaseFirst(); },
    async close() {
      releaseFirst();
      server.closeAllConnections();
      await new Promise((resolvePromise) => server.close(() => resolvePromise()));
    },
  };
}

/** 有界等待 Provider 与 ConPTY 状态，错误名称只描述测试阶段、不回显请求或凭据。 @author kongweiguang */
async function withTimeout(promise, timeoutMs, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, rejectPromise) => {
        timer = setTimeout(() => rejectPromise(new Error(`${label} timed out`)), timeoutMs);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

/** 在同一 STA 线程复制所有原生剪贴板格式并保留内存对象，保证实际 Ctrl+C 测试后可以恢复用户数据。 @author kongweiguang */
export async function startClipboardGuard() {
  const source = String.raw`
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Add-Type -AssemblyName System.Windows.Forms
function Write-GuardResponse {
  param([object]$Value)
  [Console]::Out.WriteLine((ConvertTo-Json -InputObject $Value -Compress -Depth 5))
  [Console]::Out.Flush()
}
$script:closing = $false
$script:backup = $null
$script:hadClipboardData = $false
$script:captured = $false
$script:failedFormat = $null
while ($null -ne ($line = [Console]::In.ReadLine())) {
  try {
    $request = ConvertFrom-Json -InputObject $line
    switch ($request.action) {
      'begin' {
        $data = [System.Windows.Forms.Clipboard]::GetDataObject()
        $formats = @()
        if ($null -ne $data) { $formats = @($data.GetFormats($false)) }
        $backup = New-Object System.Windows.Forms.DataObject
        $streamCopies = [System.Collections.Generic.List[System.IO.MemoryStream]]::new()
        foreach ($format in $formats) {
          $script:failedFormat = [string]$format
          $value = $data.GetData([string]$format, $false)
          if ($null -eq $value) { throw 'clipboard format could not be materialized' }
          if ($value -is [System.IO.Stream]) {
            $copy = [System.IO.MemoryStream]::new()
            $oldPosition = $null
            if ($value.CanSeek) { $oldPosition = $value.Position; $value.Position = 0 }
            $value.CopyTo($copy)
            if ($null -ne $oldPosition) { $value.Position = $oldPosition }
            $copy.Position = 0
            $backup.SetData([string]$format, $false, $copy)
            $streamCopies.Add($copy)
          } else {
            $backup.SetData([string]$format, $false, $value)
          }
        }
        $script:backup = $backup
        $script:hadClipboardData = ($null -ne $data)
        $script:captured = $true
        $script:failedFormat = $null
        Write-GuardResponse @{ ready = $true; formats = $formats; hadData = $script:hadClipboardData }
      }
      'read' {
        $text = [System.Windows.Forms.Clipboard]::GetText([System.Windows.Forms.TextDataFormat]::UnicodeText)
        Write-GuardResponse @{ text = $text }
      }
      'restore' {
        if (-not $script:captured) { throw 'clipboard snapshot unavailable' }
        if (-not $script:hadClipboardData) { [System.Windows.Forms.Clipboard]::Clear() }
        else { [System.Windows.Forms.Clipboard]::SetDataObject($script:backup, $true) }
        Write-GuardResponse @{ restored = $true }
      }
      'close' {
        Write-GuardResponse @{ closed = $true }
        $script:closing = $true
      }
      default { throw 'unknown clipboard guard action' }
    }
  } catch {
    Write-GuardResponse @{ error = $_.Exception.GetType().Name; format = $script:failedFormat }
  }
  if ($script:closing) { break }
}
`;
  const encoded = Buffer.from(source, "utf16le").toString("base64");
  const child = spawn("pwsh.exe", ["-NoLogo", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand", encoded], {
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"],
  });
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  let stderr = "";
  let pending = "";
  const waiters = [];
  const exitPromise = new Promise((resolvePromise) => child.once("exit", (code, signal) => resolvePromise({ code, signal })));
  child.stdout.on("data", (chunk) => {
    pending += chunk;
    while (true) {
      const newline = pending.indexOf("\n");
      if (newline < 0) break;
      const line = pending.slice(0, newline).replace(/\r$/u, "");
      pending = pending.slice(newline + 1);
      const waiter = waiters.shift();
      if (waiter) {
        try { waiter.resolve(JSON.parse(line)); }
        catch { waiter.reject(new Error("clipboard guard returned invalid JSON")); }
      }
    }
  });
  child.stderr.on("data", (chunk) => { stderr = `${stderr}${chunk}`.slice(-1024); });
  let childError;
  child.once("error", (error) => {
    childError = error;
    for (const waiter of waiters.splice(0)) waiter.reject(new Error(`could not start clipboard guard: ${error.code ?? "process error"}`));
  });
  child.once("exit", (code) => {
    for (const waiter of waiters.splice(0)) waiter.reject(new Error(`clipboard guard exited (${code ?? "signal"})`));
  });
  /** 每次只发一个命令，降低 STA 剪贴板操作竞争，并为卡住的系统 API 设置边界。 */
  async function request(action) {
    if (childError) throw new Error(`could not start clipboard guard: ${childError.code ?? "process error"}`);
    let waiter;
    const result = new Promise((resolvePromise, rejectPromise) => {
      waiter = { resolve: resolvePromise, reject: rejectPromise };
      waiters.push(waiter);
    });
    try {
      child.stdin.write(`${JSON.stringify({ action })}\n`);
      const response = await withTimeout(result, 5_000, `clipboard ${action}`);
      if (response.error) {
        const format = response.format ? ` format=${response.format}` : "";
        throw new Error(`clipboard ${action} failed: ${response.error}${format}${stderr ? ` (${stderr.trim()})` : ""}`);
      }
      return response;
    } finally {
      const index = waiters.indexOf(waiter);
      if (index >= 0) waiters.splice(index, 1);
    }
  }
  return {
    begin: () => request("begin"),
    readText: async () => (await request("read")).text,
    restore: () => request("restore"),
    async close() {
      if (child.exitCode === null) {
        await request("close").catch(() => {});
        child.stdin.end();
        if (!childError && child.exitCode === null) await withTimeout(exitPromise, 3_000, "clipboard guard exit");
      }
    },
  };
}

/** 将终端显示行中的 Unicode 文本位置换算为 SGR 鼠标使用的显示列。 @author kongweiguang */
function displayCellWidth(value) {
  let width = 0;
  for (const glyph of value) {
    const point = glyph.codePointAt(0);
    if (point === undefined || (point >= 0x0300 && point <= 0x036f)) continue;
    width += point >= 0x1100 && (
      point <= 0x115f || point === 0x2329 || point === 0x232a
      || (point >= 0x2e80 && point <= 0xa4cf)
      || (point >= 0xac00 && point <= 0xd7a3)
      || (point >= 0xf900 && point <= 0xfaff)
      || (point >= 0xfe10 && point <= 0xfe6f)
      || (point >= 0xff00 && point <= 0xff60)
      || (point >= 0xffe0 && point <= 0xffe6)
      || (point >= 0x1f300 && point <= 0x1faff)
      || (point >= 0x20000 && point <= 0x3fffd)
    ) ? 2 : 1;
  }
  return width;
}

/** 先备份全部剪贴板格式再拖选复制；原文回读与 UI 成功回执分别核对，最后恢复原剪贴板。 @author kongweiguang */
async function verifySelectionClipboard(cli, lines) {
  const guard = await startClipboardGuard();
  let captured = false;
  try {
    const snapshot = await guard.begin();
    captured = snapshot.ready;
    assert.equal(snapshot.ready, true, "all existing clipboard formats must be captured before testing copy");
    const expected = "全屏回复行 179";
    const row = lines.findIndex((line) => line.includes(expected));
    assert.ok(row >= 0, "the response tail line is visible for a real drag selection");
    const start = lines[row].indexOf(expected);
    const firstColumn = displayCellWidth(lines[row].slice(0, start));
    const lastColumn = firstColumn + displayCellWidth(expected) - 1;
    const sgrMouse = (button, x, y, end) => `\u001b[<${button};${x + 1};${y + 1}${end}`;
    cli.sendKeys(sgrMouse(0, firstColumn, row, "M"));
    cli.sendKeys(sgrMouse(32, lastColumn, row, "M"));
    cli.sendKeys(sgrMouse(0, lastColumn, row, "m"));
    await delay(180);
    cli.sendKeys("\u0003");
    let actual = "";
    const deadline = Date.now() + 5_000;
    while (Date.now() < deadline) {
      actual = await guard.readText();
      if (actual === expected) break;
      await delay(100);
    }
    if (actual !== expected) throw new Error("Ctrl+C clipboard readback did not match the expected selected text");
    const restored = await guard.restore();
    assert.equal(restored.restored, true, "the original text clipboard object is restored after readback");
    captured = false;
    const copiedScreen = await cli.waitForScreen((visible) => visible.includes("选区已复制"), "selection copy success feedback");
    assert.equal(copiedScreen.lines.findIndex((line) => line.includes("选区已复制")), copiedScreen.lines.length - 1, "copy receipt stays in the fixed operation footer");
    return {
      status: "passed",
      selectedText: expected,
      readbackMatches: true,
      originalFormats: snapshot.formats,
      originalHadData: snapshot.hadData,
      originalClipboardRestored: true,
      successFeedbackVisible: true,
    };
  } finally {
    try {
      if (captured) await guard.restore();
    } finally {
      await guard.close();
    }
  }
}

/** 把 ConPTY 当前可见行栅格化供评审；依赖 Windows 自带 GDI，明确不冒充真实 Windows Terminal 桌面截图。 @author kongweiguang */
async function writeConPtyScreenPng(runRoot, name, lines) {
  const path = join(runRoot, `${name}.png`);
  const source = String.raw`
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$lines = @([System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:JA_CLI_FULLSCREEN_PNG_LINES)) | ConvertFrom-Json)
$width = [int]$env:JA_CLI_FULLSCREEN_PNG_WIDTH
$height = [Math]::Max(1, $lines.Count) * 21 + 12
$bitmap = [System.Drawing.Bitmap]::new($width, $height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$font = [System.Drawing.Font]::new('Consolas', 16, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$foreground = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(220, 224, 230))
try {
  $graphics.Clear([System.Drawing.Color]::FromArgb(15, 17, 21))
  $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
  for ($row = 0; $row -lt $lines.Count; $row++) {
    $graphics.DrawString([string]$lines[$row], $font, $foreground, 12, 6 + ($row * 21))
  }
  $bitmap.Save($env:JA_CLI_FULLSCREEN_PNG_PATH, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
  $foreground.Dispose()
  $font.Dispose()
  $graphics.Dispose()
  $bitmap.Dispose()
}
`;
  const encoded = Buffer.from(source, "utf16le").toString("base64");
  const width = Math.max(960, Math.max(...lines.map(displayCellWidth), 1) * 10 + 24);
  const result = spawnSync("pwsh.exe", ["-NoLogo", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand", encoded], {
    windowsHide: true,
    encoding: "utf8",
    timeout: 15_000,
    env: {
      ...process.env,
      JA_CLI_FULLSCREEN_PNG_LINES: Buffer.from(JSON.stringify(lines), "utf8").toString("base64"),
      JA_CLI_FULLSCREEN_PNG_PATH: path,
      JA_CLI_FULLSCREEN_PNG_WIDTH: String(width),
    },
  });
  if (result.error || result.status !== 0) {
    const diagnostic = (result.stderr ?? "").trim().slice(-500);
    throw new Error(`could not rasterize ConPTY visible rows${diagnostic ? `: ${diagnostic}` : ""}`);
  }
  return path;
}

/** 操作提示随 Turn 状态变化；输入草稿时空闲状态可能不显示按键提示，此时用固定模型信息确认底栏仍占据终端末行。 @author kongweiguang */
function fixedRowPositions(lines, expectedDraft) {
  const composerRow = lines.findIndex((line) => line.includes(expectedDraft));
  const actionRow = lines.findIndex((line) => /(?:Enter 调整 · Tab 排队|Esc 中断|Tab 排队|再次按 Ctrl\+C 退出 Ja)/u.test(line));
  const footerRow = actionRow >= 0
    ? actionRow
    : lines.some((line) => line.includes("gpt-6-luna")) ? lines.length - 1 : -1;
  return { composerRow, footerRow };
}

/** 校验窗口尺寸和底部交互区在视口内；短终端允许压缩留白，但不能把输入或反馈挤出可见行。 @author kongweiguang */
function assertBottomLayout(cli, columns, rows, label) {
  const lines = cli.screen.lines();
  assert.equal(cli.screen.columns, columns, `${label}: reported terminal width`);
  assert.equal(cli.screen.rowCount, rows, `${label}: reported terminal height`);
  assert.equal(lines.length, rows, `${label}: every terminal row remains in bounds`);
  const { composerRow, footerRow } = fixedRowPositions(lines, draftText);
  assert.ok(composerRow >= 0 && composerRow < rows, `${label}: draft stays visible in the Composer`);
  assert.ok(footerRow >= 0 && footerRow < rows, `${label}: operation feedback stays visible`);
  assert.ok(rows - composerRow <= 7, `${label}: Composer remains docked near the bottom`);
  assert.ok(rows - footerRow <= 2, `${label}: footer remains on the bottom row`);
  return { composerRow, footerRow };
}

/** 在实际父 PowerShell 的同一 ConPTY 内退出 Ja 后再执行中文/普通命令，验证终端输入已交还 Shell；用字符串匹配核对必须完整出现的原始 VT 模式序列。 @author kongweiguang */
async function verifyParentPowerShellRecovery(artifacts, directories, harness, threadId, completedText, runRoot) {
  const exitMarker = "JA_PARENT_SHELL_AFTER_CLI";
  const inputMarker = "JA_PARENT_SHELL_INPUT_ACCEPTED";
  const quotePowerShell = (value) => `'${value.replaceAll("'", "''")}'`;
  const command = `Write-Output 'JA_PARENT_SHELL_BEFORE_CLI'; & ${quotePowerShell(artifacts.cli)} resume ${quotePowerShell(threadId)}; Write-Output '${exitMarker}'`;
  let shell;
  try {
    shell = startConPtyCli(
      artifacts,
      directories,
      harness,
      threadId,
      undefined,
      join(runRoot, "parent-powershell.vt"),
      ["pwsh.exe", "-NoLogo", "-NoProfile", "-NoExit", "-Command", command],
    );
    await shell.harnessReady;
    await shell.resize(120, 32);
    await shell.waitForScreen((visible) => visible.includes(completedText), "Ja launched inside its parent PowerShell");
    shell.sendKeys("\u0004");
    const parentPrompt = await shell.waitForScreen((visible) =>
      visible.includes(exitMarker) && visible.includes("PS "),
    "parent PowerShell prompt after Ja exits",
    );
    const vt = await readFile(join(runRoot, "parent-powershell.vt"), "utf8");
    assert.ok(vt.includes("\u001b[?1049l"), "Ja exits the alternate screen back into its parent PowerShell");
    assert.ok(vt.includes("\u001b[?2004l"), "Ja disables bracketed paste before returning to PowerShell");
    assert.ok(vt.includes("\u001b[?1003;1006l"), "Ja disables mouse reporting before returning to PowerShell");
    assert.ok(vt.includes("\u001b[?25h"), "Ja restores a visible cursor before returning to PowerShell");
    const chineseShellText = "Ja 退出后 PowerShell 输入正常 ✅";
    shell.sendKeys(`Write-Output '${chineseShellText}'; Write-Output '${inputMarker}'\r`);
    const acceptedInput = await shell.waitForScreen((visible) => visible.includes(inputMarker), "PowerShell accepts a command after Ja exits");
    assert.ok(acceptedInput.text.includes(inputMarker), "PowerShell command output is visible after Ja restores terminal modes");
    assert.ok(acceptedInput.text.includes(chineseShellText), "PowerShell accepts and displays Chinese UTF-8 input after Ja exits");
    shell.sendKeys("exit\r");
    await delay(250);
    const cleanup = await shell.close();
    // Harness 分两行回报退出状态，close 的快捷摘要可能在退出码行到达前读到 CHILD_ALIVE=false；以完整协议行作最终证据。
    const shellExitLine = shell.messages.findLast((line) => /^CHILD_EXIT_CODE=\d+$/u.test(line));
    const shellExitCode = shellExitLine === undefined ? undefined : Number(shellExitLine.slice("CHILD_EXIT_CODE=".length));
    shell = undefined;
    assert.equal(cleanup.forced, false, "parent PowerShell exits without forcing the ConPTY Job Object");
    assert.equal(shellExitCode, 0, "the parent PowerShell exits successfully through its own command loop");
    return {
      sameConPtyParent: true,
      alternateScreenRestored: true,
      bracketedPasteDisabled: true,
      mouseReportingDisabled: true,
      cursorVisibleRestored: true,
      powershellInputAccepted: true,
      chineseInputAccepted: true,
      shellExitCode,
      forced: cleanup.forced,
      parentPromptVisible: parentPrompt.text.includes(exitMarker),
    };
  } finally {
    if (shell) await shell.close().catch(() => undefined);
  }
}

/** 在唯一 ConPTY 会话中验证终端模式、固定 dock、滚动状态、流式增长、缩放和正常退出恢复；原始 VT 文件中的模式序列按完整字面量核对。 @author kongweiguang */
async function runAcceptance() {
  const isolated = await createRunDirectories();
  const resolvedArtifacts = await resolveArtifacts();
  const jarSnapshot = join(isolated.runRoot, "ja-app-server.jar");
  await copyFile(resolvedArtifacts.jar, jarSnapshot);
  const artifacts = { ...resolvedArtifacts, jar: jarSnapshot };
  const javaSha256 = createHash("sha256").update(await readFile(artifacts.java)).digest("hex");
  const mapperFingerprint = await verifyHistoryMapperArtifact(artifacts, isolated.runRoot);
  process.stdout.write(`${JSON.stringify({ phase: "resolved immutable artifacts before Provider startup", cli: artifacts.cli, appServerJar: artifacts.jar, appServerJarSource: resolvedArtifacts.jar, java: artifacts.java, javaVersion: artifacts.javaVersion, cliSha256: artifacts.cliSha256, appServerJarSha256: artifacts.jarSha256, javaSha256 }, null, 2)}\n`);
  const evidence = {
    status: "running",
    provider: "isolated 127.0.0.1 Responses fixture; no real or paid Provider calls",
    runtime: "Windows ConPTY, fixed ja.exe, isolated profile and explicit JDK 25",
    artifacts: {
      cliSha256: artifacts.cliSha256,
      appServerJarSha256: artifacts.jarSha256,
      appServerJarBytes: artifacts.jarBytes,
      javaVersion: artifacts.javaVersion,
      javaPath: artifacts.java,
      javaSha256,
      appServerJarSnapshot: artifacts.jar,
      historyMapper: mapperFingerprint,
    },
    checks: {},
    transcript: {},
  };
  let provider;
  let cli;
  let failure;
  try {
    assert.equal(mapperFingerprint.matchesCurrentSource, true, "pinned App Server JAR must contain the current source mapper");
    provider = await startFullscreenProvider();
    const thread = await seedFullscreenThread(artifacts, isolated.directories, provider.endpoint);
    evidence.threadId = thread.threadId;
    const harness = await compileConPtyHarness(artifacts, isolated.runRoot);
    const prompt = Array.from({ length: 96 }, (_, index) =>
      `${promptMarker} 初始长正文 ${String(index).padStart(3, "0")}`,
    ).join("\n");
    const vtLog = join(isolated.runRoot, "fullscreen.vt");
    const editorMarker = "外部编辑器已保存 ✅";
    const editorFixture = join(isolated.runRoot, "editor-fixture.mjs");
    await writeFile(
      editorFixture,
      [
        'import { readFile, writeFile } from "node:fs/promises";',
        'const path = process.argv.at(-1);',
        'await new Promise((resolve) => setTimeout(resolve, 300));',
        'const draft = await readFile(path, "utf8");',
        `await writeFile(path, draft + String.fromCharCode(10) + "${editorMarker}", "utf8");`,
        "",
      ].join("\n"),
      "utf8",
    );
    const originalEditor = process.env.EDITOR;
    const originalVisual = process.env.VISUAL;
    const quoteEditorArg = (argument) => `"${argument.replaceAll('"', '\\"')}"`;
    process.env.EDITOR = `${quoteEditorArg(process.execPath)} ${quoteEditorArg(editorFixture)}`;
    delete process.env.VISUAL;
    try {
      cli = startConPtyCli(artifacts, isolated.directories, harness, thread.threadId, prompt, vtLog);
    } finally {
      if (originalEditor === undefined) delete process.env.EDITOR;
      else process.env.EDITOR = originalEditor;
      if (originalVisual === undefined) delete process.env.VISUAL;
      else process.env.VISUAL = originalVisual;
    }
    await cli.harnessReady;
    await cli.resize(120, 32);
    const request = await withTimeout(provider.firstRequest, 45_000, "first local Provider request");
    assert.equal(request.markerFound, true, "the isolated request includes the unique test prompt");
    await cli.waitForScreen((visible) => visible.includes("全屏草稿") || visible.includes("正在工作"), "active full-screen thread");
    await cli.pullVt();
    assert.equal(cli.screen.alternate, true, "Ja owns the alternate screen while interactive");
    let rawVt = await (await import("node:fs/promises")).readFile(vtLog, "utf8");
    assert.ok(rawVt.includes("\u001b[?1049h"), "alternate screen is entered");
    assert.ok(rawVt.includes("\u001b[?2004h"), "bracketed paste is enabled");
    assert.ok(rawVt.includes("\u001b[?1003;1006h"), "SGR mouse reporting is enabled");
    evidence.checks.terminalEntry = { alternateScreen: true, bracketedPaste: true, sgrMouse: true, rawMode: "input behavior verified through ConPTY" };

    cli.sendKeys(draftText);
    await cli.waitForScreen((visible) => visible.includes(draftText), "draft before browsing");
    const initial120 = assertBottomLayout(cli, 120, 32, "120x32 before scroll");
    evidence.checks.initialDock = initial120;

    const resizeObservations = [];
    for (const [columns, rows] of [[80, 24], [40, 10], [120, 32]]) {
      await cli.resize(columns, rows);
      await cli.waitForScreen((visible) => visible.includes(draftText), `${columns}x${rows} draft after resize`);
      resizeObservations.push({
        size: `${columns}x${rows}`,
        ...assertBottomLayout(cli, columns, rows, `${columns}x${rows} resize`),
      });
    }
    evidence.checks.resizeAndDraft = resizeObservations;

    cli.sendKeys("\u001b[5~");
    const pageUp = await cli.waitForScreen((visible) => visible.includes("回到底部") || visible.includes("返回 · Esc"), "PageUp browse hint");
    const browsedDock = fixedRowPositions(pageUp.lines, draftText);
    assert.deepEqual(browsedDock, initial120, "PageUp does not move the bottom dock from its initial rows");
    evidence.checks.pageUp = { hintVisible: true, ...browsedDock };

    cli.sendKeys("\u001b[1;5H");
    const top = await cli.waitForScreen((visible) =>
      (visible.includes("回到底部") || visible.includes("返回 · Esc"))
        && visible.includes(`${promptMarker} 初始长正文 000`),
    "Ctrl+Home reaches the unique first transcript line",
    );
    const anchoredRows = top.lines.slice(0, browsedDock.composerRow - 1);
    evidence.checks.ctrlHome = { browseHintVisible: true, visibleTranscriptRows: anchoredRows.length };

    cli.sendKeys("\u001b[6~");
    const pageDown = await cli.waitForScreen((visible) => {
      const firstTranscriptLine = visible.match(new RegExp(`${promptMarker} 初始长正文 (\\d{3})`, "u"));
      const lineNumber = Number(firstTranscriptLine?.[1]);
      return (visible.includes("回到底部") || visible.includes("返回 · Esc"))
        && Number.isInteger(lineNumber) && lineNumber > 0 && lineNumber < 49;
    }, "PageDown advances from the top while remaining in browse mode");
    const pageDownFirstLine = pageDown.lines.find((line) => line.includes(promptMarker))
      ?.match(/初始长正文 (\d{3})/u)?.[1];
    assert.ok(pageDownFirstLine, "PageDown leaves a unique transcript line visible");
    evidence.checks.pageDown = { browseHintVisible: true, firstVisibleTranscriptLine: Number(pageDownFirstLine) };

    cli.sendKeys("\u001b[1;5F");
    await cli.waitForScreen((visible) => !visible.includes("回到底部") && !visible.includes("返回 · Esc"), "Ctrl+End follow latest");
    cli.sendKeys("\u001b[<64;15;8M");
    const wheel = await cli.waitForScreen((visible) => visible.includes("回到底部") || visible.includes("返回 · Esc"), "SGR wheel scroll");
    assert.ok(wheel.text.includes("回到底部") || wheel.text.includes("返回 · Esc"), "SGR wheel enters browse mode");
    evidence.checks.sgrWheel = { reportSent: true, browseHintVisible: true };

    cli.sendKeys("\u001b");
    await cli.waitForScreen((visible) => !visible.includes("回到底部") && !visible.includes("返回 · Esc"), "Esc returns to latest");
    cli.sendKeys("\u001b[5~");
    const beforeStream = await cli.waitForScreen((visible) =>
      (visible.includes("回到底部") || visible.includes("返回 · Esc"))
        && visible.includes(`${promptMarker} 初始长正文 049`),
    "PageUp reaches a unique middle transcript anchor before stream",
    );
    const beforeDock = fixedRowPositions(beforeStream.lines, draftText);
    const pinnedAnchor = beforeStream.lines
      .map((line, row) => ({ row, line }))
      .filter(({ line }) => line.includes(promptMarker));
    assert.ok(pinnedAnchor.length > 0, "the visible user-message anchor is available before streaming");

    provider.releaseFirst();
    const streamed = await withTimeout(provider.progress, 30_000, "streaming while browsing");
    await delay(180);
    await cli.pullVt();
    const duringStream = cli.screen.lines();
    const duringDock = fixedRowPositions(duringStream, draftText);
    assert.deepEqual(duringDock, beforeDock, "stream updates leave Composer and footer at their fixed rows");
    assert.deepEqual(
      duringStream
        .map((line, row) => ({ row, line }))
        .filter(({ line }) => line.includes(promptMarker)),
      pinnedAnchor,
      "streamed content does not move the visible transcript anchor while browsing",
    );
    assert.ok(cli.screen.text().includes("有新内容") || cli.screen.text().includes("新内容"), "new streamed activity is announced while browsing");
    evidence.checks.streamingWhileBrowsing = {
      deltasSentBeforeObservation: streamed,
      anchorRowsStable: true,
      composerRow: duringDock.composerRow,
      footerRow: duringDock.footerRow,
      newActivityHint: true,
    };
    evidence.transcript.browsing = await writeScreenEvidence(isolated.runRoot, "browsing-during-stream", cli.screen);
    evidence.transcript.browsingPng = await writeConPtyScreenPng(
      isolated.runRoot,
      "browsing-during-stream",
      cli.screen.lines(),
    );

    await withTimeout(provider.firstComplete, 30_000, "local Provider response completion while browsing");
    await delay(250);
    await cli.pullVt();
    const completedBrowse = await cli.waitForScreen((visible) => visible.includes("有新内容") || visible.includes("新内容"), "new activity hint after Provider response completion");
    const completedDock = fixedRowPositions(completedBrowse.lines, draftText);
    assert.deepEqual(completedDock, beforeDock, "turn completion does not move the dock while the reader is browsing");
    evidence.checks.providerResponseCompletedWhileBrowsing = { responseCompleted: true, browseHintVisible: true, ...completedDock };
    cli.sendKeys("\u001b[1;5F");
    const latest = await cli.waitForScreen((visible) =>
      visible.includes(provider.completedText)
        && !visible.includes("正在工作")
        && !visible.includes("回到底部")
        && !visible.includes("返回 · Esc"),
    "Ctrl+End returns to the completed answer");
    assert.ok(latest.text.includes("全屏回复行 179"), "latest response tail becomes visible after following latest");
    assert.ok(latest.text.includes(draftText), "following latest preserves the draft");
    const latestDock = fixedRowPositions(latest.lines, draftText);
    assert.deepEqual(latestDock, beforeDock, "returning to latest preserves dock coordinates");
    evidence.checks.completionAndReturn = { newActivityCleared: true, finalResponseVisible: true, draftPreserved: true, ...latestDock };
    evidence.transcript.latest = await writeScreenEvidence(isolated.runRoot, "latest-after-stream", cli.screen);
    evidence.transcript.latestPng = await writeConPtyScreenPng(
      isolated.runRoot,
      "latest-after-stream",
      cli.screen.lines(),
    );

    evidence.checks.selectionClipboardReadback = await verifySelectionClipboard(cli, latest.lines);

    cli.sendKeys("\u001b[5~");
    await cli.waitForScreen((visible) => visible.includes("回到底部") || visible.includes("返回 · Esc"), "browse before editor handoff");
    cli.sendKeys("\u001b[1;5H");
    const beforeEditor = await cli.waitForScreen((visible) =>
      (visible.includes("回到底部") || visible.includes("返回 · Esc"))
        && visible.includes(`${promptMarker} 初始长正文 000`),
    "Ctrl+Home reaches the top before editor handoff",
    );
    const editorDockBefore = fixedRowPositions(beforeEditor.lines, draftText);
    const editorAnchorBefore = beforeEditor.lines
      .map((line, row) => ({ row, line }))
      .filter(({ line }) => line.includes(promptMarker));
    await cli.pullVt();
    const editorHandoffStart = (await (await import("node:fs/promises")).readFile(vtLog, "utf8")).length;
    cli.sendKeys("\u0007");
    const editedDraft = `${draftText}\n${editorMarker}`;
    const afterEditor = await cli.waitForScreen((visible) =>
      visible.includes(editorMarker) && visible.includes("回到底部"),
    "external editor resumes the full-screen browsing session");
    const editorDockAfter = fixedRowPositions(afterEditor.lines, draftText);
    assert.equal(editorDockAfter.footerRow, editorDockBefore.footerRow, "editor handoff keeps the footer docked");
    assert.equal(editorDockAfter.composerRow, editorDockBefore.composerRow - 1, "a second draft line grows the Composer upward while retaining its bottom edge");
    const restoredEditorAnchor = afterEditor.lines
      .map((line, row) => ({ row, line }))
      .filter(({ line }) => line.includes(promptMarker));
    assert.deepEqual(
      restoredEditorAnchor,
      editorAnchorBefore.slice(0, restoredEditorAnchor.length),
      "external editor handoff restores the same top anchor while the taller Composer may cover the last transcript row",
    );
    assert.equal(cli.screen.alternate, true, "external editor returns Ja to its alternate screen");
    const editorVt = await (await import("node:fs/promises")).readFile(vtLog, "utf8");
    const editorExitAt = editorVt.indexOf("\u001b[?1049l");
    const editorReentryAt = editorVt.lastIndexOf("\u001b[?1049h");
    assert.ok(editorExitAt >= 0 && editorReentryAt > editorExitAt, "external editor releases and reacquires the alternate screen");
    const releasedModes = editorVt.slice(editorHandoffStart, editorReentryAt);
    const reacquiredModes = editorVt.slice(editorReentryAt);
    assert.ok(releasedModes.includes("\u001b[?1049l"), "external editor handoff leaves the alternate screen");
    assert.ok(releasedModes.includes("\u001b[?2004l") && releasedModes.includes("\u001b[?1003;1006l"), "external editor handoff releases paste and mouse modes");
    assert.ok(reacquiredModes.includes("\u001b[?2004h") && reacquiredModes.includes("\u001b[?1003;1006h"), "external editor return reacquires paste and mouse modes");
    evidence.checks.externalEditor = {
      status: "passed",
      draftPreservedAndEdited: true,
      browseAnchorRestored: true,
      composerExpandedUpward: true,
      alternateScreenReacquired: true,
      mouseAndPasteModesReacquired: true,
    };

    cli.sendKeys("\u007f".repeat(Array.from(editedDraft).length));
    await cli.waitForScreen((visible) => !visible.includes(draftText), "clear acceptance draft");
    const exit = await cli.close();
    cli = undefined;
    assert.equal(exit.forced, false, "CLI exits through its normal terminal path");
    const exitLog = await (await import("node:fs/promises")).readFile(vtLog, "utf8");
    assert.ok(exitLog.includes("\u001b[?1049l"), "normal exit restores the primary screen");
    assert.ok(exitLog.includes("\u001b[?2004l"), "normal exit disables bracketed paste");
    assert.ok(exitLog.includes("\u001b[?1003;1006l"), "normal exit disables SGR mouse reporting");
    assert.ok(exitLog.includes("\u001b[?25h"), "normal exit makes the terminal cursor visible");
    assert.equal(provider.requestCount, provider.responseCount, "every local Provider request reaches a complete SSE response");
    evidence.checks.terminalExit = { forced: false, alternateScreenRestored: true, bracketedPasteDisabled: true, mouseDisabled: true };
    evidence.checks.parentPowerShellRecovery = await verifyParentPowerShellRecovery(
      artifacts,
      isolated.directories,
      harness,
      thread.threadId,
      provider.completedText,
      isolated.runRoot,
    );
    evidence.provider = { endpoint: "127.0.0.1 loopback", requests: provider.requestCount, completedResponses: provider.responseCount };
    evidence.status = "completed";
  } catch (error) {
    failure = error instanceof Error ? error : new Error(String(error));
    evidence.status = "failed";
    evidence.failure = failure.message;
  } finally {
    if (cli) {
      if (failure) evidence.cliStderr = cli.stderr().slice(-4096);
      evidence.cleanup = await cli.close().catch((error) => ({ error: error.message }));
    }
    if (provider) await provider.close().catch((error) => { evidence.cleanup = { ...evidence.cleanup, providerCloseError: error.message }; });
    if (!failure) {
      try {
        await removeOwnedProfile(isolated.runRoot, isolated.profileRoot);
        evidence.profileRemoved = true;
      } catch (error) {
        evidence.profileRemoved = false;
        evidence.cleanup = { ...evidence.cleanup, profileRemovalError: error instanceof Error ? error.message : String(error) };
        failure = error instanceof Error ? error : new Error(String(error));
        evidence.status = "failed";
      }
    }
    if (failure) {
      evidence.profileRemoved = false;
      evidence.profileRetainedPath = isolated.profileRoot;
      evidence.javaLogsDirectory = isolated.directories.logs;
    }
    await writeFile(join(isolated.runRoot, "acceptance.json"), `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  }
  if (failure) {
    process.stderr.write(`CLI fullscreen acceptance failed. Evidence: ${isolated.runRoot}\n`);
    throw failure;
  }
  process.stdout.write(`${JSON.stringify({ status: evidence.status, evidenceDirectory: isolated.runRoot, artifacts: evidence.artifacts, checks: evidence.checks }, null, 2)}\n`);
}

/** 命令行入口保留导入能力，便于工具仅做语法检查时不启动终端或局部服务。 @author kongweiguang */
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await runAcceptance();
}
