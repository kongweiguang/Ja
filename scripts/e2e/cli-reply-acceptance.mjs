// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { createServer } from "node:http";
import { execFile } from "node:child_process";
import { lstat, mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { createConnection } from "node:net";
import { basename, dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  compileConPtyHarness,
  removeOwnedProfile,
  resolveArtifacts,
  seedThread,
  startConPtyCli,
  waitForRawText,
  writeScreenEvidence,
} from "./cli-usage-conpty.mjs";
import {
  initializeParams,
  readCompleteItemContent,
} from "./real-provider-smoke.mjs";
import { providerStream } from "./media-schema-loopback.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const fixturePath = join(repoRoot, "apps", "cli", "tests", "support", "reply-markdown.md");
const evidenceRoot = resolve(process.env.EVIDENCE_ROOT ?? join(repoRoot, "target", "cli-reply-acceptance"));
const placeholderSecret = "ja-cli-usage-conpty-placeholder";
const promptMarker = "JA_REPLY_RENDER_ACCEPTANCE";
const readMarker = "JA_REPLY_READ_OK";
const toolFirstMarker = "JA_REPLY_TOOL_ROW_0001";
const toolLastMarker = "JA_REPLY_TOOL_ROW_0900";
const toolUnicodeSample = "工具结果中文分页与终端折行验收 🙂";
const finalFirstMarker = "JA_REPLY_LONG_0001";
const finalCheckpointMarker = "JA_REPLY_LONG_0600";
const finalLastMarker = "JA_REPLY_LONG_0900";
const shellRestoredMarker = "JA_REPLY_SHELL_RESTORED";
const shellInputMarker = "JA_REPLY_SHELL_INPUT";
const shellInputAcceptedMarker = "JA_REPLY_SHELL_INPUT_ACCEPTED";
const observerLifetimeId = `native_${randomBytes(16).toString("hex")}`;
const execFileAsync = promisify(execFile);
/** 让流事件与 ConPTY 字节回放有一次调度机会，避免忙轮询抢占测试进程。 @author kongweiguang */
const delay = (milliseconds) => new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));

/** 固定 900 行中文长尾覆盖 64 KiB 边界；逐行标识让回读能检查顺序和重复。 @author kongweiguang */
export function buildReplyBody(markdown, lineCount = 900) {
  assert.equal(typeof markdown, "string");
  assert.ok(Number.isSafeInteger(lineCount) && lineCount >= 900 && lineCount <= 2_000);
  const longLines = Array.from({ length: lineCount }, (_, index) => {
    const ordinal = String(index + 1).padStart(4, "0");
    return `JA_REPLY_LONG_${ordinal} · 第 ${ordinal} 行中文正文保持顺序、折行和唯一性；Emoji 🙂 与组合字符 é 仍完整。`;
  });
  const text = `${markdown.trimEnd()}\n\n## 长流与归档\n\n${longLines.join("\n")}\n`;
  const byteLength = Buffer.byteLength(text, "utf8");
  assert.ok(byteLength > 64 * 1024, "the final fixture must cross the existing 64 KiB stream boundary");
  return { text, longLines, byteLength };
}

/** 必须同时看到工具步骤标题和本场景两条编号工具，避免把消息正文或百分比误判为列表已打开。 @author kongweiguang */
export function isToolStepsListScreen(screenText) {
  if (typeof screenText !== "string") return false;
  const lines = screenText.split("\n");
  const rows = lines.filter((line) => /^[ \t]*›?[ \t]*\d+\.[ \t]+\S/u.test(line));
  return lines.some((line) => line.includes("工具步骤"))
    && rows.length >= 2
    && rows.some((line) => line.includes("reply-source.txt"))
    && rows.some((line) => line.includes("运行 "));
}

/** 对完整文本计算 Unicode code point、UTF-8、哈希和顺序标记，供不同分片层级用内容对账。 @author kongweiguang */
export function summarizeReplyText(text) {
  assert.equal(typeof text, "string");
  const markers = Array.from(text.matchAll(/JA_REPLY_LONG_(\d{4})/gu), (match) => Number(match[1]));
  return {
    characters: Array.from(text).length,
    bytes: Buffer.byteLength(text, "utf8"),
    sha256: createHash("sha256").update(text, "utf8").digest("hex").toUpperCase(),
    firstMarker: markers.length > 0 ? `JA_REPLY_LONG_${String(markers[0]).padStart(4, "0")}` : null,
    lastMarker: markers.length > 0 ? `JA_REPLY_LONG_${String(markers.at(-1)).padStart(4, "0")}` : null,
    markerCount: markers.length,
    markersInOrder: markers.every((ordinal, index) => ordinal === index + 1),
  };
}

/** 验证文本跨层传输时内容完全一致；不把 Provider SSE 帧数当作 App Server 通知帧数。 @author kongweiguang */
export function assertReplyTextMetricsMatch(sourceMetrics, observedMetrics, context = "reply text") {
  for (const key of ["characters", "bytes", "sha256", "firstMarker", "lastMarker", "markerCount"]) {
    assert.equal(observedMetrics[key], sourceMetrics[key], `${context} ${key} mismatch`);
  }
  assert.equal(sourceMetrics.markersInOrder, true, `${context} source markers must be in order and unique`);
  assert.equal(observedMetrics.markersInOrder, true, `${context} observed markers must be in order and unique`);
}

/** 按 Turn 汇总实际收到的 JA-RPC 文本通知；合并时仍用内容哈希、字节数和序号标记检查完整性。 @author kongweiguang */
export function createReplyStreamProbe(checkpointMarker) {
  assert.equal(typeof checkpointMarker, "string");
  let turnId;
  let rpcNotificationFrames = 0;
  let characters = 0;
  let bytes = 0;
  let firstStreamSeq;
  let lastStreamSeq;
  let firstMarker;
  let lastMarkerOrdinal = 0;
  let markerCount = 0;
  let markersInOrder = true;
  let tail = "";
  let checkpointSeen = false;
  const hash = createHash("sha256");

  return {
    append(params, expectedTurnId) {
      if (params?.turnId !== expectedTurnId || typeof params.text !== "string") return false;
      turnId = params.turnId;
      rpcNotificationFrames += 1;
      characters += Array.from(params.text).length;
      bytes += Buffer.byteLength(params.text, "utf8");
      hash.update(params.text, "utf8");
      if (Number.isSafeInteger(params.streamSeq)) {
        firstStreamSeq ??= params.streamSeq;
        lastStreamSeq = params.streamSeq;
      }
      const combined = tail + params.text;
      checkpointSeen ||= combined.includes(checkpointMarker);
      for (const match of combined.matchAll(/JA_REPLY_LONG_(\d{4})/gu)) {
        if ((match.index ?? 0) + match[0].length <= tail.length) continue;
        const ordinal = Number(match[1]);
        if (firstMarker === undefined) firstMarker = match[0];
        if (ordinal !== lastMarkerOrdinal + 1) markersInOrder = false;
        lastMarkerOrdinal = ordinal;
        markerCount += 1;
      }
      tail = Array.from(combined).slice(-64).join("");
      return checkpointSeen;
    },
    snapshot() {
      return {
        turnId: turnId ?? null,
        rpcNotificationFrames,
        characters,
        bytes,
        sha256: hash.copy().digest("hex").toUpperCase(),
        firstStreamSeq: firstStreamSeq ?? null,
        lastStreamSeq: lastStreamSeq ?? null,
        firstMarker: firstMarker ?? null,
        lastMarker: lastMarkerOrdinal > 0 ? `JA_REPLY_LONG_${String(lastMarkerOrdinal).padStart(4, "0")}` : null,
        markerCount,
        markersInOrder,
        hasCheckpoint: checkpointSeen,
        retainedTailCharacters: Array.from(tail).length,
      };
    },
  };
}

/** 构造供工具读取的 UTF-8 900 行本地文本，保留中文和 Emoji 作为 Shell/ConPTY 的字符往返断言。 @author kongweiguang */
function buildToolOutput() {
  const lines = Array.from({ length: 900 }, (_, index) => {
    const ordinal = String(index + 1).padStart(4, "0");
    return `JA_REPLY_TOOL_ROW_${ordinal} · ${toolUnicodeSample}`;
  });
  return `${lines.join("\n")}\n`;
}

/** 将 Unicode code point 分成不同长度的增量，故意让 Markdown 符号和中文边界落在任意片段。 @author kongweiguang */
function splitCodePointChunks(text) {
  const points = Array.from(text);
  const sizes = [1, 7, 2, 19, 5, 31, 3, 61, 11];
  const chunks = [];
  let offset = 0;
  for (let index = 0; offset < points.length; index += 1) {
    const size = sizes[index % sizes.length];
    chunks.push(points.slice(offset, offset + size).join(""));
    offset += size;
  }
  return chunks;
}

/** 编码 OpenAI Responses SSE 事件并为每个 Provider response 重置单调递增序号。 @author kongweiguang */
function responseEvent(type, sequence, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequence, ...payload })}\n\n`;
}

/** 生成 OpenAI Responses envelope；固定 loopback model 标识且不依赖真实 Provider 参数。 @author kongweiguang */
function responseEnvelope(id, status, output) {
  return {
    id,
    created_at: 0,
    model: "ja-cli-reply-acceptance",
    object: "response",
    output,
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    status,
  };
}

/** 构造公开 reasoning summary 和一个原生 function_call，工具完成后由 App Server 回送结果。 @author kongweiguang */
function toolResponseFrames(id, call, summary) {
  const reasoningId = `reasoning_${id}`;
  const itemId = `item_${id}`;
  const argumentsText = JSON.stringify(call.arguments);
  const reasoning = {
    id: reasoningId,
    type: "reasoning",
    summary: [{ type: "summary_text", text: summary }],
  };
  const item = {
    id: itemId,
    type: "function_call",
    call_id: call.callId,
    name: call.name,
    arguments: argumentsText,
  };
  let sequence = 0;
  const frame = (type, payload) => responseEvent(type, sequence++, payload);
  return [
    frame("response.created", { response: responseEnvelope(id, "in_progress", []) }),
    frame("response.output_item.added", {
      output_index: 0,
      item: { ...reasoning, summary: [] },
    }),
    frame("response.reasoning_summary_text.delta", {
      delta: summary,
      item_id: reasoningId,
      output_index: 0,
      summary_index: 0,
    }),
    frame("response.reasoning_summary_text.done", {
      item_id: reasoningId,
      output_index: 0,
      summary_index: 0,
      text: summary,
    }),
    frame("response.output_item.done", { output_index: 0, item: reasoning }),
    frame("response.output_item.added", {
      output_index: 1,
      item: { ...item, arguments: "" },
    }),
    frame("response.function_call_arguments.delta", {
      item_id: itemId,
      delta: argumentsText,
      output_index: 1,
    }),
    frame("response.function_call_arguments.done", {
      item_id: itemId,
      name: call.name,
      arguments: argumentsText,
      output_index: 1,
    }),
    frame("response.output_item.done", { output_index: 1, item }),
    frame("response.completed", {
      response: responseEnvelope(id, "completed", [reasoning, item]),
    }),
  ];
}

/** 创建隔离 JA_HOME 和唯一证据目录，所有可删除目录都位于本次 runRoot 之下。 @author kongweiguang */
async function createReplyRunDirectories() {
  await mkdir(evidenceRoot, { recursive: true });
  const runRoot = await mkdtemp(join(evidenceRoot, "run-"));
  const profileRoot = join(runRoot, "profile");
  const directories = {
    root: profileRoot,
    home: join(profileRoot, "home"),
    data: join(profileRoot, "home", "data"),
    run: join(profileRoot, "home", "run"),
    logs: join(profileRoot, "home", "logs", "java"),
    workspace: join(profileRoot, "workspace"),
  };
  await Promise.all(Object.values(directories).map((path) => mkdir(path, { recursive: true })));
  return { runRoot, profileRoot, directories };
}

/** 等待有状态的 RPC 或 ConPTY 交互并在一个期限内失败，避免 E2E 进程挂起。 @author kongweiguang */
async function withTimeout(promise, milliseconds, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, rejectPromise) => {
        timer = setTimeout(() => rejectPromise(new Error(`${label} timed out`)), milliseconds);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

/** 校验 observer 收到的 WebSocket 响应帧形状和请求身份；保持完整 frame 契约供既有 JA-RPC 读取器使用。 @author kongweiguang */
export function validateObserverResponseFrame(frame, expectedId) {
  if (frame === null || typeof frame !== "object" || Array.isArray(frame)) {
    throw new Error("JA-RPC observer received an invalid response frame");
  }
  const keys = Object.keys(frame);
  if (
    frame.jsonrpc !== "2.0"
    || typeof frame.id !== "string"
    || (expectedId !== undefined && frame.id !== expectedId)
    || keys.some((key) => !["jsonrpc", "id", "result", "error"].includes(key))
  ) {
    throw new Error("JA-RPC observer received an invalid response frame");
  }
  const hasResult = Object.hasOwn(frame, "result") && frame.result !== undefined;
  const hasError = Object.hasOwn(frame, "error");
  if (hasResult === hasError) throw new Error("JA-RPC observer received an invalid response frame");
  if (hasError && (
    frame.error === null
    || typeof frame.error !== "object"
    || !Number.isInteger(frame.error.code)
    || typeof frame.error.message !== "string"
  )) {
    throw new Error("JA-RPC observer received an invalid response frame");
  }
  return frame;
}

/** 从已验证的完整响应 frame 取出 RPC 结果，错误只暴露稳定分类而不输出服务端正文。 @author kongweiguang */
export function rpcResult(frame, operation) {
  validateObserverResponseFrame(frame);
  if (frame?.error !== undefined) {
    const code = frame.error?.data?.errorCode ?? frame.error?.data?.category ?? "RPC_ERROR";
    throw new Error(`${operation} failed: ${String(code)}`);
  }
  return frame.result;
}

/** 按 JA-RPC code point 游标读完单个 Tool 产物，校验每页身份与推进位置后再计算完整内容摘要。 @author kongweiguang */
export async function readCompleteToolArtifact(session, identity, pageLimit = 65_536) {
  assert.ok(Number.isSafeInteger(pageLimit) && pageLimit >= 1 && pageLimit <= 65_536);
  const { threadId, turnId, callId, artifactId } = identity;
  let offsetCharacters = 0;
  let totalCharacters;
  const chunks = [];
  for (;;) {
    const page = rpcResult(await session.request("tool/artifact/read", {
      threadId,
      turnId,
      callId,
      artifactId,
      offsetCharacters,
      limitCharacters: pageLimit,
    }), "tool/artifact/read");
    assert.equal(page.artifactId, artifactId, "artifact page identity must stay bound to the requested artifact");
    assert.equal(page.offsetCharacters, offsetCharacters, "artifact page must begin at the requested code point offset");
    assert.equal(typeof page.content, "string");
    const contentCharacters = Array.from(page.content).length;
    assert.ok(contentCharacters <= pageLimit, "artifact page must respect the negotiated character limit");
    assert.ok(Number.isSafeInteger(page.totalCharacters) && page.totalCharacters >= 0);
    if (totalCharacters === undefined) totalCharacters = page.totalCharacters;
    assert.equal(page.totalCharacters, totalCharacters, "artifact length must remain stable across pages");
    chunks.push(page.content);
    if (page.nextOffsetCharacters === null) break;
    assert.ok(
      Number.isSafeInteger(page.nextOffsetCharacters)
        && page.nextOffsetCharacters === offsetCharacters + contentCharacters
        && page.nextOffsetCharacters > offsetCharacters
        && page.nextOffsetCharacters < totalCharacters,
      "artifact pagination must advance by the returned code point count",
    );
    offsetCharacters = page.nextOffsetCharacters;
  }
  const content = chunks.join("");
  assert.equal(Array.from(content).length, totalCharacters, "all artifact pages must reconstruct the declared content");
  return {
    content,
    totalCharacters,
    pageCount: chunks.length,
    byteLength: Buffer.byteLength(content, "utf8"),
    sha256: createHash("sha256").update(content, "utf8").digest("hex").toUpperCase(),
  };
}

/** 读取本轮 Ja owner 的私有 endpoint，只把认证 token 留在内存且拒绝符号链接或非 loopback 地址。 @author kongweiguang */
async function readReplyRuntimeEndpoint(directories) {
  const endpointPath = join(directories.run, "app-server.endpoint.json");
  let metadata;
  try {
    metadata = await lstat(endpointPath);
  } catch (error) {
    if (error?.code === "ENOENT") return undefined;
    throw new Error("isolated App Server endpoint could not be inspected");
  }
  assert.ok(metadata.isFile() && !metadata.isSymbolicLink() && metadata.size <= 4_096, "invalid isolated endpoint file");
  let value;
  try {
    const text = await readFile(endpointPath, "utf8");
    assert.ok(Buffer.byteLength(text, "utf8") <= 4_096, "isolated endpoint exceeds its protocol limit");
    value = JSON.parse(text);
  } catch {
    throw new Error("isolated App Server endpoint could not be decoded");
  }
  const expectedKeys = [
    "host", "port", "protocolMajor", "protocolMinor", "runtimeGeneration", "serverInstanceId", "token",
  ].sort();
  assert.deepEqual(Object.keys(value ?? {}).sort(), expectedKeys, "isolated endpoint fields do not match JA-RPC v2");
  assert.equal(value.host, "127.0.0.1");
  assert.ok(Number.isSafeInteger(value.port) && value.port > 0 && value.port <= 65_535);
  assert.equal(value.protocolMajor, 2);
  assert.equal(value.protocolMinor, 0);
  assert.match(value.serverInstanceId, /^srv_[A-Za-z0-9._-]{1,97}$/u);
  assert.ok(Number.isSafeInteger(value.runtimeGeneration) && value.runtimeGeneration > 0);
  if (typeof value.token !== "string" || !/^[0-9a-f]{64}$/u.test(value.token)) {
    throw new Error("isolated endpoint token has an invalid format");
  }
  return value;
}

/** 有界等待 CLI 本轮 owner 写出私有 endpoint，避免另启 stdio owner 与 CLI 竞争 SQLite。 @author kongweiguang */
async function waitForReplyRuntimeEndpoint(directories, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const endpoint = await readReplyRuntimeEndpoint(directories);
    if (endpoint !== undefined) return endpoint;
    await delay(80);
  }
  throw new Error("isolated CLI did not publish an App Server endpoint");
}

/** 仅按本轮私有 endpoint 端口解析监听进程，并在内存中核对可执行文件与 JAR，不写入原始命令行。 @author kongweiguang */
async function inspectRuntimeOwnerProvenance(endpoint, artifacts) {
  const command = [
    `$port=${endpoint.port}`,
    `$expectedJar=${quotePowerShellLiteral(artifacts.jar)}`,
    `$expectedJava=${quotePowerShellLiteral(artifacts.java)}`,
    "$processIds=@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction Stop | Select-Object -ExpandProperty OwningProcess -Unique)",
    "$rows=@(foreach($processId in $processIds){$owner=Get-CimInstance Win32_Process -Filter ('ProcessId = '+$processId) -ErrorAction SilentlyContinue;if($owner){[pscustomobject]@{processId=[int]$owner.ProcessId;processName=[System.IO.Path]::GetFileName($owner.ExecutablePath);executablePath=$owner.ExecutablePath;usesConfiguredJar=($owner.CommandLine -and $owner.CommandLine.Contains($expectedJar,[StringComparison]::OrdinalIgnoreCase));usesConfiguredJdk25=($owner.ExecutablePath -and $owner.ExecutablePath.Equals($expectedJava,[StringComparison]::OrdinalIgnoreCase))}}})",
    "$rows | ConvertTo-Json -Compress -Depth 4",
  ].join("; ");
  try {
    const result = await execFileAsync(process.env.JA_TEST_PWSH ?? "pwsh.exe", ["-NoLogo", "-NoProfile", "-Command", command], {
      windowsHide: true,
      timeout: 10_000,
      maxBuffer: 16 * 1024,
    });
    const output = result.stdout.trim();
    const processes = output === "" ? [] : JSON.parse(output);
    return {
      status: "resolved",
      endpoint: { host: endpoint.host, port: endpoint.port },
      processes: Array.isArray(processes) ? processes : [processes],
    };
  } catch {
    return {
      status: "lookup-failed",
      endpoint: { host: endpoint.host, port: endpoint.port },
      processes: [],
    };
  }
}

/** 只核对已确认 owner 的 TCP 表中是否存在到固定 loopback Provider 端口的同四元组连接。 @author kongweiguang */
async function inspectRuntimeProviderSocket(processId, providerPort, clientPort) {
  const command = [
    `$ownerPid=${processId}`,
    `$providerPort=${providerPort}`,
    `$clientPort=${clientPort}`,
    "$connections=@(Get-NetTCPConnection -State Established -OwningProcess $ownerPid -RemotePort $providerPort -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -eq $clientPort } | ForEach-Object { [pscustomobject]@{localPort=[int]$_.LocalPort;remotePort=[int]$_.RemotePort;remoteAddressIsLoopback=($_.RemoteAddress -in @('127.0.0.1','::1','::ffff:127.0.0.1'))} })",
    "$connections | ConvertTo-Json -Compress -Depth 3",
  ].join("; ");
  try {
    const result = await execFileAsync(process.env.JA_TEST_PWSH ?? "pwsh.exe", ["-NoLogo", "-NoProfile", "-Command", command], {
      windowsHide: true,
      timeout: 10_000,
      maxBuffer: 8 * 1024,
    });
    const output = result.stdout.trim();
    const rows = output === "" ? [] : JSON.parse(output);
    const connections = Array.isArray(rows) ? rows : [rows];
    return {
      status: "resolved",
      queriedOwnerProcessId: processId,
      providerPort,
      expectedOwnerLocalPort: clientPort,
      directConnectionMatches: connections,
    };
  } catch {
    return { status: "lookup-failed", queriedOwnerProcessId: processId, providerPort, expectedOwnerLocalPort: clientPort };
  }
}

/** 使用已核实的 JDK25 jcmd 语法仅转储本轮 owner，不触碰其他 Java 进程。 @author kongweiguang */
async function captureOwnedRuntimeThreadDump(processId, artifacts, outputPath) {
  assert.ok(Number.isSafeInteger(processId) && processId > 0);
  const jcmd = join(dirname(artifacts.java), "jcmd.exe");
  const dumpPath = resolve(outputPath);
  await execFileAsync(jcmd, [String(processId), "Thread.dump_to_file", "-format=json", dumpPath], {
    windowsHide: true,
    timeout: 30_000,
    maxBuffer: 32 * 1024,
  });
  const dump = await readFile(dumpPath);
  return {
    status: "captured",
    processId,
    command: "Thread.dump_to_file -format=json",
    path: basename(dumpPath),
    bytes: dump.byteLength,
    sha256: createHash("sha256").update(dump).digest("hex").toUpperCase(),
  };
}

/** 只探测已验证 loopback owner 端口；ECONNREFUSED 证明 endpoint 文件已陈旧，可安全清理本轮 profile。 @author kongweiguang */
async function inspectReplyOwnerPort(endpoint, timeoutMs = 1_500) {
  return new Promise((resolvePromise) => {
    const socket = createConnection({ host: endpoint.host, port: endpoint.port });
    let settled = false;
    let timer;
    const finish = (state) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      resolvePromise(state);
    };
    timer = setTimeout(() => finish("inconclusive"), timeoutMs);
    socket.once("connect", () => finish("listening"));
    socket.once("error", (error) => finish(error?.code === "ECONNREFUSED" ? "refused" : "inconclusive"));
  });
}

/** 以本轮 token 与 ready challenge 连接 CLI 的 shared owner，只观察 JA-RPC，不创建第二个服务进程。 @author kongweiguang */
async function connectReplySession(directories, threadId) {
  const endpoint = await waitForReplyRuntimeEndpoint(directories);
  assert.equal(typeof WebSocket, "function", "Node must provide a WebSocket client for the shared App Server observer");
  const socket = new WebSocket(`ws://${endpoint.host}:${endpoint.port}/ja-rpc`);
  const pending = new Map();
  const waiters = [];
  const events = [];
  const notificationTrace = [];
  const streamProbe = createReplyStreamProbe(finalCheckpointMarker);
  let observedTurnId;
  const closed = new Promise((resolvePromise) => socket.addEventListener("close", resolvePromise, { once: true }));
  let sequence = 0;
  let failure;
  let shutdownAcknowledged = false;
  let baselineRevision;

  /** 连接故障时一次性拒绝未完成的 RPC 和事件等待，不保存原始错误帧或 endpoint token。 @author kongweiguang */
  function failObserver(error) {
    if (failure !== undefined) return;
    failure = error instanceof Error ? error : new Error("JA-RPC observer failed");
    for (const entry of pending.values()) {
      clearTimeout(entry.timer);
      entry.reject(failure);
    }
    pending.clear();
    for (const waiter of waiters.splice(0)) {
      clearTimeout(waiter.timer);
      waiter.reject(failure);
    }
  }

  /** 依 frame ID 完成请求，并保留有界脱敏通知轨迹以诊断 Turn/审批事件先后。 @author kongweiguang */
  function receiveObserverFrame(data) {
    let frame;
    try {
      frame = JSON.parse(typeof data === "string" ? data : String(data));
    } catch {
      failObserver(new Error("JA-RPC observer received invalid JSON"));
      return;
    }
    if (frame?.jsonrpc !== "2.0") {
      failObserver(new Error("JA-RPC observer received an invalid frame"));
      return;
    }
    if (frame.id !== undefined) {
      const entry = pending.get(String(frame.id));
      if (entry === undefined) {
        failObserver(new Error("JA-RPC observer received an unknown response ID"));
        return;
      }
      pending.delete(String(frame.id));
      clearTimeout(entry.timer);
      try {
        validateObserverResponseFrame(frame, String(frame.id));
        entry.resolve(frame);
      } catch {
        failObserver(new Error("JA-RPC observer received an invalid response frame"));
      }
      return;
    }
    if (typeof frame.method !== "string") return;
    if (frame.method === "assistant/text-delta") {
      streamProbe.append(frame.params, observedTurnId);
    }
    if (["runtime/status-changed", "turn/state-changed", "approval/requested", "approval/resolved", "turn/terminal"].includes(frame.method)) {
      const params = frame.params ?? {};
      const fields = [
        "threadId", "turnId", "from", "to", "state", "threadRevision", "sequence", "generation",
        "serverInstanceId", "eventId", "approvalId", "callId", "toolName", "decision",
      ];
      notificationTrace.push({
        method: frame.method,
        params: Object.fromEntries(fields.filter((key) => params[key] !== undefined).map((key) => [key, params[key]])),
      });
      if (notificationTrace.length > 128) notificationTrace.shift();
    }
    for (const waiter of [...waiters]) {
      if (waiter.method === frame.method && waiter.predicate(frame)) {
        waiters.splice(waiters.indexOf(waiter), 1);
        clearTimeout(waiter.timer);
        waiter.resolve(frame);
        return;
      }
    }
    if (["runtime/status-changed", "turn/state-changed", "approval/requested", "approval/resolved", "turn/terminal"].includes(frame.method)) {
      events.push(frame);
      if (events.length > 64) events.shift();
    }
  }

  /** 发送有限期限的 v2 请求；RPC 错误只暴露 errorCode，避免正文、路径或凭据进入诊断。 @author kongweiguang */
  function request(method, params, timeoutMs = 30_000) {
    if (failure !== undefined) return Promise.reject(failure);
    if (socket.readyState !== WebSocket.OPEN) return Promise.reject(new Error("JA-RPC observer is not open"));
    const id = `c:${++sequence}`;
    return new Promise((resolvePromise, rejectPromise) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        rejectPromise(new Error(`${method} observer request timed out`));
      }, timeoutMs);
      pending.set(id, {
        timer,
        resolve: resolvePromise,
        reject: rejectPromise,
      });
      try {
        socket.send(JSON.stringify({ jsonrpc: "2.0", id, method, params }));
      } catch {
        clearTimeout(timer);
        pending.delete(id);
        rejectPromise(new Error(`${method} observer request could not be sent`));
      }
    });
  }

  /** 先检查已缓存通知再注册限时等待，避免快速 Provider 流在 await 注册前越过状态事件。 @author kongweiguang */
  function waitForEvent(method, predicate = () => true, timeoutMs = 30_000) {
    const index = events.findIndex((frame) => frame.method === method && predicate(frame));
    if (index >= 0) return Promise.resolve(events.splice(index, 1)[0]);
    if (failure !== undefined) return Promise.reject(failure);
    return new Promise((resolvePromise, rejectPromise) => {
      const timer = setTimeout(() => {
        const index = waiters.findIndex((waiter) => waiter.timer === timer);
        if (index >= 0) waiters.splice(index, 1);
        rejectPromise(new Error(`${method} observer event timed out`));
      }, timeoutMs);
      waiters.push({ method, predicate, resolve: resolvePromise, reject: rejectPromise, timer });
    });
  }

  socket.addEventListener("message", (event) => receiveObserverFrame(event.data));
  socket.addEventListener("error", () => failObserver(new Error("JA-RPC observer transport failed")));
  socket.addEventListener("close", () => {
    if (!shutdownAcknowledged) failObserver(new Error("JA-RPC observer closed before owner shutdown"));
  });
  try {
    await withTimeout(new Promise((resolvePromise, rejectPromise) => {
      socket.addEventListener("open", resolvePromise, { once: true });
      if (socket.readyState === WebSocket.OPEN) resolvePromise();
      socket.addEventListener("error", () => rejectPromise(new Error("JA-RPC observer could not connect")), { once: true });
    }), 10_000, "shared App Server WebSocket open");
    socket.send(JSON.stringify({ token: endpoint.token }));
    const initialized = rpcResult(await request("runtime/initialize", initializeParams()), "runtime/initialize");
    assert.equal(initialized.protocolMajor, 2);
    assert.equal(initialized.protocolMinor, 0);
    assert.equal(initialized.serverInstanceId, endpoint.serverInstanceId);
    const readyToken = randomBytes(16).toString("hex");
    socket.send(JSON.stringify({ jsonrpc: "2.0", method: "runtime/initialized", params: { readyToken } }));
    const ready = await waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready"
        && frame.params?.serverInstanceId === endpoint.serverInstanceId
        && frame.params?.readyToken === readyToken
        && frame.params?.generation === endpoint.runtimeGeneration,
      30_000,
    );
    assert.match(ready.params.eventId, /^evt_[A-Za-z0-9._:-]{1,96}$/u);
    assert.ok(Number.isSafeInteger(ready.params.sequence) && ready.params.sequence > 0);
    assert.ok(Number.isFinite(Date.parse(ready.params.occurredAt)));
    assert.deepEqual(ready.params.features, ["task_threads", "plan", "interaction"]);
    const context = rpcResult(await request("runtime/context/register", {
      environment: { ...process.env },
      shell: null,
      clientProcessId: process.pid,
      clientLifetimeId: observerLifetimeId,
    }), "runtime/context/register");
    assert.deepEqual(Object.keys(context), ["contextId"]);
    assert.match(context.contextId, /^ctx_[0-9a-f]{32}$/u);
    const observation = rpcResult(await request("thread/observe", { threadId }), "thread/observe");
    assert.deepEqual(Object.keys(observation).sort(), ["accepted", "threadId"]);
    assert.equal(observation.accepted, true);
    assert.equal(observation.threadId, threadId);
    const baseline = rpcResult(await request("thread/read", { threadId, tail: true, limit: 200 }), "thread/read after thread/observe");
    assert.equal(baseline.threadId, threadId);
    assert.ok(Number.isSafeInteger(baseline.revision) && baseline.revision >= 0);
    assert.ok(Array.isArray(baseline.turns) && Array.isArray(baseline.items));
    baselineRevision = baseline.revision;
  } catch (error) {
    if (socket.readyState !== WebSocket.CLOSED) socket.close();
    throw error;
  }

  return {
    threadId,
    endpoint: { host: endpoint.host, port: endpoint.port },
    identity: {
      serverInstanceId: endpoint.serverInstanceId,
      runtimeGeneration: endpoint.runtimeGeneration,
    },
    request,
    waitForEvent,
    observeTurn(turnId) {
      observedTurnId = turnId;
    },
    streamTrace() {
      return streamProbe.snapshot();
    },
    async waitForStreamText(sourceMetrics, timeoutMs = 45_000) {
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        const snapshot = streamProbe.snapshot();
        if (snapshot.turnId === observedTurnId) {
          if (snapshot.characters >= sourceMetrics.characters || snapshot.bytes >= sourceMetrics.bytes) {
            assertReplyTextMetricsMatch(sourceMetrics, snapshot, "shared owner text stream");
          }
          if (snapshot.characters === sourceMetrics.characters && snapshot.bytes === sourceMetrics.bytes) return snapshot;
        }
        if (failure !== undefined) throw failure;
        await delay(50);
      }
      const snapshot = streamProbe.snapshot();
      throw new Error(
        `shared owner text stream stopped before source metrics: ${snapshot.characters}/${sourceMetrics.characters} code points, ${snapshot.bytes}/${sourceMetrics.bytes} UTF-8 bytes, checkpoint=${snapshot.hasCheckpoint}`,
      );
    },
    /** 返回本连接收到的有限脱敏事件轨迹供失败归因，不暴露正文或认证信息。 @author kongweiguang */
    eventTrace() {
      return notificationTrace.map((entry) => ({ method: entry.method, params: { ...entry.params } }));
    },
    baselineRevision,
    get exited() { return socket.readyState === WebSocket.CLOSED; },
    async shutdown() {
      const result = rpcResult(await request("runtime/shutdown", {}), "runtime/shutdown");
      assert.equal(result.accepted, true);
      assert.equal(result.status, "shutting_down");
      shutdownAcknowledged = true;
      if (socket.readyState !== WebSocket.CLOSED) socket.close();
      await withTimeout(closed, 10_000, "shared App Server owner shutdown");
      return result;
    },
    async close() {
      if (socket.readyState !== WebSocket.CLOSED) socket.close();
      await withTimeout(closed, 5_000, "shared App Server observer close");
    },
  };
}

/** 仅停止本轮 owner；endpoint 已删除或 loopback 明确拒绝连接时才视为停机，端口状态不确定则保留 profile。 @author kongweiguang */
async function shutdownReplyOwner(directories, threadId, session) {
  const endpoint = await readReplyRuntimeEndpoint(directories);
  if (endpoint === undefined) return { stopped: true, alreadyStopped: true };
  if (session && !session.exited) {
    try {
      const result = await session.shutdown();
      return { stopped: true, result };
    } catch {
      // Observer may close with the CLI; reconnect only to the same validated loopback owner.
    }
  }
  let observer;
  try {
    observer = await connectReplySession(directories, threadId);
  } catch (error) {
    const portState = await inspectReplyOwnerPort(endpoint);
    if (portState === "refused") {
      return { stopped: true, alreadyStopped: true, endpointStale: true, portState };
    }
    throw error;
  }
  try {
    const result = await observer.shutdown();
    return { stopped: true, result, reconnected: true };
  } catch (error) {
    await observer.close().catch(() => undefined);
    throw error;
  }
}

/** 将同一 Markdown 交给实际安装的 Pi TUI Markdown 组件，并独立记录本地参考 checkout 的源码指纹。 @author kongweiguang */
async function renderPiReference(markdown, runRoot) {
  const piRoot = "C:\\kong\\personal-projects\\参考源码\\lizi\\pi-mono";
  const checkoutComponentPath = join(piRoot, "packages", "tui", "src", "components", "markdown.ts");
  let sourceCheckout = { status: "missing", path: checkoutComponentPath };
  try {
    const checkoutSource = await readFile(checkoutComponentPath);
    sourceCheckout = {
      status: "source-only",
      path: checkoutComponentPath,
      componentSha256: createHash("sha256").update(checkoutSource).digest("hex").toUpperCase(),
      renderStatus: "not-run; checkout dependencies are not installed by this acceptance script",
    };
  } catch {
    // Source checkout is an optional reference; installed Pi detection remains independent.
  }

  let launcherPath;
  try {
    const result = await execFileAsync(process.env.POWERSHELL ?? "pwsh.exe", [
      "-NoProfile",
      "-Command",
      "(Get-Command pi -CommandType ExternalScript -ErrorAction Stop | Select-Object -First 1).Source",
    ], { windowsHide: true, timeout: 10_000, maxBuffer: 8 * 1024 });
    launcherPath = result.stdout.trim();
  } catch {
    return { status: "skipped", reason: "Pi is not installed on PATH", sourceCheckout };
  }
  assert.notEqual(launcherPath, "", "Get-Command pi returned an empty launcher path");
  const agentRoot = join(dirname(launcherPath), "node_modules", "@earendil-works", "pi-coding-agent");
  const agentPackagePath = join(agentRoot, "package.json");
  const agentPackage = JSON.parse(await readFile(agentPackagePath, "utf8"));
  assert.equal(agentPackage.name, "@earendil-works/pi-coding-agent");
  const requireFromPi = createRequire(agentPackagePath);
  const markdownComponentPath = requireFromPi.resolve("@earendil-works/pi-tui/dist/components/markdown.js");
  const tuiPackagePath = join(dirname(dirname(dirname(markdownComponentPath))), "package.json");
  const tuiPackage = JSON.parse(await readFile(tuiPackagePath, "utf8"));
  const themePath = join(agentRoot, "dist", "modes", "interactive", "theme", "theme.js");
  const darkThemePath = join(dirname(themePath), "dark.json");
  const [componentSource, themeSource, darkThemeSource, { Markdown }, { getMarkdownTheme, initTheme }] = await Promise.all([
    readFile(markdownComponentPath),
    readFile(themePath),
    readFile(darkThemePath),
    import(pathToFileURL(markdownComponentPath).href),
    import(pathToFileURL(themePath).href),
  ]);
  initTheme("dark");
  const lines = new Markdown(markdown, 0, 0, getMarkdownTheme()).render(88);
  const rendered = `${lines.join("\n")}\n`;
  await writeFile(join(runRoot, "pi-markdown-88.txt"), rendered, "utf8");
  return {
    status: "rendered",
    columns: 88,
    renderedLines: lines.length,
    outputSha256: createHash("sha256").update(rendered).digest("hex").toUpperCase(),
    theme: "built-in dark (isolated; no Pi user profile read)",
    renderer: {
      package: tuiPackage.name,
      version: tuiPackage.version,
      componentSha256: createHash("sha256").update(componentSource).digest("hex").toUpperCase(),
      themeSha256: createHash("sha256").update(themeSource).digest("hex").toUpperCase(),
      darkThemeSha256: createHash("sha256").update(darkThemeSource).digest("hex").toUpperCase(),
    },
    codingAgent: {
      package: agentPackage.name,
      version: agentPackage.version,
      themePath,
    },
    sourceCheckout,
  };
}

/** 仅在固定 isolated SQLite 中暂时改写目标 artifact 主键，用于核实详情失败后的 F5 重读恢复。 @author kongweiguang */
async function setArtifactLookupAvailable(databasePath, identity, available) {
  const unavailableId = `${identity.artifactId}_temporary_missing`;
  const statement = "UPDATE ja_tool_artifact SET artifact_id=? WHERE thread_id=? AND turn_id=? AND call_id=? AND artifact_id=?";
  const oldId = available ? unavailableId : identity.artifactId;
  const newId = available ? identity.artifactId : unavailableId;
  const python = [
    "import sqlite3,sys",
    "db,old_id,new_id,thread_id,turn_id,call_id=sys.argv[1:]",
    "connection=sqlite3.connect(db,timeout=10)",
    "connection.execute('PRAGMA busy_timeout=10000')",
    `cursor=connection.execute(${JSON.stringify(statement)},(new_id,thread_id,turn_id,call_id,old_id))`,
    "if cursor.rowcount != 1:",
    "    connection.rollback()",
    "    connection.close()",
    "    raise SystemExit('artifact test fixture identity was not unique')",
    "connection.commit()",
    "connection.close()",
  ].join("\n");
  await execFileAsync(process.env.PYTHON ?? "python", [
    "-c",
    python,
    databasePath,
    oldId,
    newId,
    identity.threadId,
    identity.turnId,
    identity.callId,
  ], { windowsHide: true, timeout: 15_000, maxBuffer: 16 * 1024 });
  return unavailableId;
}

/** 将可执行路径和 Thread 标识作为 PowerShell 单引号字面量传递，避免空格或引号改变命令边界。 @author kongweiguang */
function quotePowerShellLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

/** 在 CLI 正常返回后要求父 PowerShell 输出并读取固定标识，实际验证终端输入控制权已恢复。 @author kongweiguang */
function shellReturnCommand(artifacts, threadId) {
  const shellScript = [
    `& ${quotePowerShellLiteral(artifacts.cli)} resume ${quotePowerShellLiteral(threadId)}`,
    "$jaExitCode = $LASTEXITCODE",
    `Write-Output (${quotePowerShellLiteral("JA_REPLY_CLI_EXIT=")} + $jaExitCode)`,
    `Write-Output ${quotePowerShellLiteral(shellRestoredMarker)}`,
    "$returnedInput = Read-Host 'Ja shell restoration check'",
    `if ($returnedInput -ceq ${quotePowerShellLiteral(shellInputMarker)}) { Write-Output ${quotePowerShellLiteral(shellInputAcceptedMarker)}; exit 0 }`,
    "exit 47",
  ].join("; ");
  return [process.env.JA_TEST_PWSH ?? "pwsh.exe", "-NoLogo", "-NoProfile", "-Command", shellScript];
}

/** 用一个约束集分发标题、read、UTF-8 Shell、长流终答；非标题 Provider 请求严格按三步顺序。 @author kongweiguang */
async function startReplyProvider(finalText) {
  let agentRequestCount = 0;
  let responseCount = 0;
  let titleRequestCount = 0;
  let httpRequestCount = 0;
  const statusPathCounts = {};
  let releaseFinal;
  let releaseFirstPartial;
  let releaseDisconnectCheckpoint;
  let notifyFinalRequest;
  let notifyFinalPartial;
  let notifyDisconnectCheckpoint;
  let resolveFinalComplete;
  const finalGate = new Promise((resolvePromise) => { releaseFinal = resolvePromise; });
  const firstPartial = new Promise((resolvePromise) => { notifyFinalPartial = resolvePromise; });
  const firstPartialGate = new Promise((resolvePromise) => { releaseFirstPartial = resolvePromise; });
  const disconnectCheckpoint = new Promise((resolvePromise) => { notifyDisconnectCheckpoint = resolvePromise; });
  const disconnectGate = new Promise((resolvePromise) => { releaseDisconnectCheckpoint = resolvePromise; });
  const finalRequest = new Promise((resolvePromise) => { notifyFinalRequest = resolvePromise; });
  const finalComplete = new Promise((resolvePromise) => { resolveFinalComplete = resolvePromise; });
  const agentRequestWaiters = new Map();
  const stages = [];
  const faults = [];
  const completedIds = [];
  const finalNetworkMetrics = {
    attemptedFrames: 0,
    acceptedFrames: 0,
    sourceTextDeltaFrames: 0,
    writeCallbacks: 0,
    writeCallbackErrors: 0,
    falseWriteReturns: 0,
    drainEvents: 0,
    envelopeBytes: 0,
  };

  /** 累计脱敏 HTTP 状态与固定路由标签，避免把认证头或任意请求路径写入验收证据。 @author kongweiguang */
  function recordStatusPath(status, route) {
    const key = `${status} ${route}`;
    statusPathCounts[key] = (statusPathCounts[key] ?? 0) + 1;
  }

  /** 有界读取 Provider 请求，仅路由阶段标志，不将请求正文或凭据写入 evidence。 @author kongweiguang */
  async function readProviderRequest(request) {
    const chunks = [];
    let size = 0;
    for await (const chunk of request) {
      size += chunk.length;
      if (size > 8 * 1024 * 1024) throw new Error("loopback request exceeds its fixture limit");
      chunks.push(chunk);
    }
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  }

  /** 按帧回送固定 JSONL-compatible SSE；客户端断开后继续收束本地模型流以核实后台 Turn。 @author kongweiguang */
  async function sendFrames(response, frames) {
    response.writeHead(200, {
      "cache-control": "no-store",
      "content-type": "text/event-stream; charset=utf-8",
      connection: "keep-alive",
    });
    recordStatusPath(200, "/v1/responses");
    for (const frame of frames) {
      if (!response.destroyed && !response.writableEnded) response.write(frame);
    }
    if (!response.destroyed && !response.writableEnded) {
      await new Promise((resolvePromise) => response.end(resolvePromise));
    }
    responseCount += 1;
  }

  /** 流式发送 reasoning 与逐 code point 回复；两个门分别暴露初始布局和断连前进度。 @author kongweiguang */
  async function sendFinal(response, id, requestSocket) {
    const reasoningId = `reasoning_${id}`;
    const messageId = `message_${id}`;
    const summary = "工具结果已核实，正在保持 Markdown 区块顺序输出。";
    const reasoning = {
      id: reasoningId,
      type: "reasoning",
      summary: [{ type: "summary_text", text: summary }],
    };
    const message = {
      id: messageId,
      type: "message",
      role: "assistant",
      status: "completed",
      content: [{ type: "output_text", text: finalText, annotations: [], logprobs: [] }],
    };
    response.writeHead(200, {
      "cache-control": "no-store",
      "content-type": "text/event-stream; charset=utf-8",
      connection: "keep-alive",
    });
    response.on("drain", () => { finalNetworkMetrics.drainEvents += 1; });
    recordStatusPath(200, "/v1/responses");
    let sequence = 0;
    let outputDeltaFrames = 0;
    const write = (type, payload) => {
      const frame = responseEvent(type, sequence++, payload);
      if (response.destroyed || response.writableEnded) return;
      finalNetworkMetrics.attemptedFrames += 1;
      finalNetworkMetrics.envelopeBytes += Buffer.byteLength(frame, "utf8");
      const canContinue = response.write(frame, (error) => {
        if (error) finalNetworkMetrics.writeCallbackErrors += 1;
        else finalNetworkMetrics.writeCallbacks += 1;
      });
      finalNetworkMetrics.acceptedFrames += 1;
      if (!canContinue) finalNetworkMetrics.falseWriteReturns += 1;
    };
    write("response.created", { response: responseEnvelope(id, "in_progress", []) });
    write("response.output_item.added", {
      output_index: 0,
      item: { ...reasoning, summary: [] },
    });
    write("response.reasoning_summary_text.delta", {
      delta: summary,
      item_id: reasoningId,
      output_index: 0,
      summary_index: 0,
    });
    write("response.reasoning_summary_text.done", {
      item_id: reasoningId,
      output_index: 0,
      summary_index: 0,
      text: summary,
    });
    write("response.output_item.done", { output_index: 0, item: reasoning });
    write("response.output_item.added", {
      output_index: 1,
      item: { ...message, status: "in_progress", content: [] },
    });
    write("response.content_part.added", {
      content_index: 0,
      item_id: messageId,
      output_index: 1,
      part: { type: "output_text", text: "", annotations: [] },
    });
    const chunks = splitCodePointChunks(finalText);
    let accumulated = "";
    let partialReleased = false;
    let disconnectReleased = false;
    for (let index = 0; index < chunks.length; index += 1) {
      const delta = chunks[index];
      write("response.output_text.delta", {
        content_index: 0,
        delta,
        item_id: messageId,
        output_index: 1,
        logprobs: [],
      });
      outputDeltaFrames += 1;
      finalNetworkMetrics.sourceTextDeltaFrames = outputDeltaFrames;
      accumulated += delta;
      if (!partialReleased && index === 24) {
        partialReleased = true;
        notifyFinalPartial();
        await firstPartialGate;
      }
      if (!disconnectReleased && accumulated.includes(finalCheckpointMarker)) {
        disconnectReleased = true;
        notifyDisconnectCheckpoint({
          sourceSseDeltaFrames: outputDeltaFrames,
          ...summarizeReplyText(accumulated),
          clientConnected: !response.destroyed,
          network: {
            socketBytesWritten: response.socket?.bytesWritten ?? null,
            socketLocalPort: response.socket?.localPort ?? null,
            socketRemotePort: response.socket?.remotePort ?? null,
            writableLength: response.writableLength,
            writableNeedDrain: response.writableNeedDrain,
            destroyed: response.destroyed,
            closed: response.closed,
            attemptedFrames: finalNetworkMetrics.attemptedFrames,
            acceptedFrames: finalNetworkMetrics.acceptedFrames,
            writeCallbacks: finalNetworkMetrics.writeCallbacks,
            writeCallbackErrors: finalNetworkMetrics.writeCallbackErrors,
            falseWriteReturns: finalNetworkMetrics.falseWriteReturns,
            drainEvents: finalNetworkMetrics.drainEvents,
            envelopeBytes: finalNetworkMetrics.envelopeBytes,
            requestSocket,
          },
        });
        await disconnectGate;
      }
    }
    assert.equal(accumulated, finalText, "Provider source deltas must reconstruct the fixed final reply exactly");
    write("response.output_text.done", {
      content_index: 0,
      item_id: messageId,
      output_index: 1,
      text: finalText,
    });
    write("response.content_part.done", {
      content_index: 0,
      item_id: messageId,
      output_index: 1,
      part: { type: "output_text", text: finalText, annotations: [] },
    });
    write("response.output_item.done", { output_index: 1, item: message });
    write("response.completed", {
      response: responseEnvelope(id, "completed", [reasoning, message]),
    });
    if (!response.destroyed && !response.writableEnded) {
      await new Promise((resolvePromise) => response.end(resolvePromise));
    }
    responseCount += 1;
    completedIds.push(id);
    resolveFinalComplete({
      id,
      disconnected: response.destroyed,
      sourceSseDeltaFrames: outputDeltaFrames,
      ...summarizeReplyText(accumulated),
    });
  }

  const server = createServer(async (request, response) => {
    httpRequestCount += 1;
    const requestSocket = {
      localPort: request.socket.localPort ?? null,
      remotePort: request.socket.remotePort ?? null,
    };
    try {
      if (request.method !== "POST" || request.url !== "/v1/responses") {
        recordStatusPath(404, "unexpected-route");
        response.writeHead(404).end();
        return;
      }
      if (request.headers.authorization !== `Bearer ${placeholderSecret}`) {
        recordStatusPath(401, "/v1/responses");
        response.writeHead(401).end();
        return;
      }
      const payload = await readProviderRequest(request);
      const isAgent = Array.isArray(payload.tools) && payload.tools.some((tool) => tool?.name === "exec_command");
      if (!isAgent) {
        titleRequestCount += 1;
        await sendFrames(response, providerStream("openai_responses", "reply_auto_title", undefined, "CLI 回复渲染验收"));
        return;
      }
      agentRequestCount += 1;
      const inputText = JSON.stringify(payload.input ?? []);
      const metadata = { ordinal: agentRequestCount, inputHasReadResult: inputText.includes(readMarker) };
      if (agentRequestWaiters.has(agentRequestCount)) agentRequestWaiters.get(agentRequestCount)(metadata);

      if (agentRequestCount === 1) {
        stages.push("reasoning_read", "tool_read");
        const call = { callId: "call_reply_read", name: "read", arguments: { path: "reply-source.txt" } };
        await sendFrames(response, toolResponseFrames("resp_reply_read", call, "先核实固定 Markdown 来源，再读取长流结果。"));
        return;
      }
      if (agentRequestCount === 2) {
        assert.ok(inputText.includes(readMarker), "the read Tool result must return to the next model request");
        stages.push("reasoning_shell", "tool_shell");
        const call = {
          callId: "call_reply_shell",
          name: "exec_command",
          arguments: {
            cmd: "$p='tool-output.txt'; [Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false); $OutputEncoding=[System.Text.UTF8Encoding]::new($false); Get-Content -Encoding utf8 -Raw -LiteralPath $p",
            timeout_ms: 60_000,
            yield_time_ms: 10_000,
          },
        };
        await sendFrames(response, toolResponseFrames("resp_reply_shell", call, "读取结果已返回，核实工具全文并保留 artifact。"));
        return;
      }
      if (agentRequestCount === 3) {
        assert.ok(inputText.includes(readMarker), "the final request must retain the earlier read result");
        const finalRequestMetadata = {
          inputHasToolTail: inputText.includes(toolLastMarker),
          inputHasToolPreview: inputText.includes(toolFirstMarker),
        };
        if (!finalRequestMetadata.inputHasToolPreview) {
          throw new Error("the shell Tool result was absent from the final Provider request");
        }
        stages.push("reasoning_final", "final");
        notifyFinalRequest(finalRequestMetadata);
        await finalGate;
        await sendFinal(response, "resp_reply_markdown", requestSocket);
        return;
      }
      throw new Error("unexpected additional assistant request");
    } catch (error) {
      faults.push(error instanceof Error ? error.message : String(error));
      if (!response.headersSent) {
        recordStatusPath(400, "/v1/responses");
        response.writeHead(400, { "content-type": "application/json" });
      }
      response.end('{"error":{"message":"isolated reply fixture rejected the request"}}');
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
    stages,
    faults,
    completedIds,
    finalNetworkMetrics() { return { ...finalNetworkMetrics }; },
    get agentRequestCount() { return agentRequestCount; },
    get responseCount() { return responseCount; },
    get titleRequestCount() { return titleRequestCount; },
    get httpRequestCount() { return httpRequestCount; },
    get statusPathCounts() { return { ...statusPathCounts }; },
    get providerPort() { return address.port; },
    waitForAgentRequest(ordinal) {
      if (agentRequestCount >= ordinal) return Promise.resolve({ ordinal });
      return new Promise((resolvePromise) => agentRequestWaiters.set(ordinal, resolvePromise));
    },
    finalRequest,
    finalPartial: firstPartial,
    disconnectCheckpoint,
    finalComplete,
    releaseFinal() { releaseFinal(); },
    releaseFirstPartial() { releaseFirstPartial(); },
    releaseDisconnectCheckpoint() { releaseDisconnectCheckpoint(); },
    async close() {
      releaseFinal();
      releaseFirstPartial();
      releaseDisconnectCheckpoint();
      server.closeAllConnections();
      await new Promise((resolvePromise) => server.close(() => resolvePromise()));
    },
  };
}

/** 以事件定位 Tool 审批，再用 thread/read 核验待批与决定快照；证据目录显式传入，避免依赖 UI harness 私有状态。 @author kongweiguang */
async function approveTool(ui, session, turnId, toolName, runRoot) {
  const approvalPromise = session.waitForEvent(
    "approval/requested",
    (frame) => frame.params?.turnId === turnId && frame.params?.toolName === toolName,
    45_000,
  );
  const approval = await withTimeout(approvalPromise, 45_000, `${toolName} approval request`);
  assert.equal(approval.params?.threadId, session.threadId);
  assert.equal(approval.params?.turnId, turnId);
  assert.equal(approval.params?.generation, session.identity.runtimeGeneration);
  assert.equal(approval.params?.serverInstanceId, session.identity.serverInstanceId);
  const pendingHistory = rpcResult(await session.request("thread/read", { threadId: session.threadId, tail: true, limit: 200 }), "thread/read pending approval");
  const pendingApproval = pendingHistory.items.find(
    (item) => item.kind === "approval" && item.approvalId === approval.params?.approvalId,
  );
  assert.ok(pendingApproval, `${toolName} approval event must exist in the authoritative Thread snapshot`);
  assert.equal(pendingApproval.turnId, turnId);
  assert.equal(pendingApproval.callId, approval.params?.callId);
  assert.equal(pendingApproval.toolName, toolName);
  assert.equal(pendingApproval.decision, null);
  assert.ok(pendingHistory.revision >= approval.params.threadRevision);
  const choices = await ui.waitForScreen(
    (visible) => {
      const lines = visible.split("\n");
      return visible.includes("需要审批")
        && visible.includes("允许执行")
        && lines.some((line) => /^[ \t]*›?[ \t]*1\.[ \t]+允许[ \t]*$/u.test(line))
        && lines.some((line) => /^[ \t]*2\.[ \t]+拒绝[ \t]*$/u.test(line));
    },
    `${toolName} approval choices`,
    45_000,
  );
  await writeScreenEvidence(runRoot, `approval-${toolName}`, ui.screen);
  assert.ok(choices.text.includes("需要审批"));
  ui.sendKeys("\r");
  const resolved = await withTimeout(
    session.waitForEvent(
      "approval/resolved",
      (frame) => frame.params?.turnId === turnId && frame.params?.approvalId === approval.params?.approvalId,
      30_000,
    ),
    30_000,
    `${toolName} approval resolution`,
  );
  assert.equal(resolved.params?.decision, "approve");
  assert.equal(resolved.params?.threadId, session.threadId);
  assert.equal(resolved.params?.turnId, turnId);
  assert.equal(resolved.params?.generation, session.identity.runtimeGeneration);
  const resolvedHistory = rpcResult(await session.request("thread/read", { threadId: session.threadId, tail: true, limit: 200 }), "thread/read resolved approval");
  const resolvedApproval = resolvedHistory.items.find(
    (item) => item.kind === "approval" && item.approvalId === approval.params?.approvalId,
  );
  assert.equal(resolvedApproval?.decision, "approve");
  assert.ok(resolvedHistory.revision >= resolved.params.threadRevision);
  return { approvalId: approval.params?.approvalId, callId: approval.params?.callId };
}

/** 在长流门控点清空已恢复草稿并用 Ctrl+D 退出 Ja，父 PowerShell 与 ConPTY 保持存活供后台 Turn 继续。 @author kongweiguang */
async function disconnectCli(ui, runRoot) {
  ui.sendKeys("\u0003");
  const emptyComposer = await ui.waitForScreen(
    (visible) => !visible.includes("详情返回后保留的草稿🙂"),
    "clear the verified restored draft before Ctrl+D",
  );
  ui.sendKeys("\u0004");
  const shellReady = await ui.waitForScreen(
    (visible) => visible.includes("JA_REPLY_CLI_EXIT=0") && visible.includes(shellRestoredMarker),
    "PowerShell receives control after the streaming CLI exits normally",
    15_000,
  );
  const statusStart = ui.messages.length;
  ui.child.stdin.write("STATUS\n");
  await ui.waitForMessage("CHILD_ALIVE=true", statusStart, 5_000);
  await ui.pullVt();
  return {
    cliExitMarker: "JA_REPLY_CLI_EXIT=0",
    draftCleared: !emptyComposer.text.includes("详情返回后保留的草稿🙂"),
    parentShellReady: shellReady.text.includes(shellRestoredMarker),
    parentShellAlive: true,
    transcript: await writeScreenEvidence(runRoot, "markdown-parent-shell-restored", ui.screen),
  };
}

/** STATUS 只是一刻快照；重复查询并等到退出码帧，避免把仍存活父 Shell 误判为已退出。 @author kongweiguang */
async function waitForConPtyChildExit(ui, label, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const start = ui.messages.length;
    ui.child.stdin.write("STATUS\n");
    let status;
    try {
      status = await ui.waitForMessage("CHILD_ALIVE=", start, Math.min(750, deadline - Date.now()));
    } catch {
      continue;
    }
    if (status !== "CHILD_ALIVE=false") continue;
    const exitLine = await ui.waitForMessage("CHILD_EXIT_CODE=", start, 1_000);
    const exitCode = Number(exitLine.slice("CHILD_EXIT_CODE=".length));
    assert.ok(Number.isInteger(exitCode), `${label} must report an integer exit code`);
    return exitCode;
  }
  throw new Error(`${label} did not exit before the ConPTY child deadline`);
}

/** 通过 Read-Host 固定输入自然退出父 PowerShell，再关闭它所属的 ConPTY Harness Job。 @author kongweiguang */
async function finishWaitingPowerShell(ui, runRoot, evidenceName, waitLabel) {
  ui.sendKeys(`${shellInputMarker}\r`);
  const input = await ui.waitForScreen(
    (visible) => visible.includes(shellInputAcceptedMarker),
    waitLabel,
    15_000,
  );
  const transcript = await writeScreenEvidence(runRoot, evidenceName, ui.screen);
  const childExitCode = await waitForConPtyChildExit(ui, "restored PowerShell shell");
  const harnessExit = await ui.close();
  const exit = { ...harnessExit, childExitCode };
  return {
    accepted: input.text.includes(shellInputAcceptedMarker),
    transcript,
    exit,
  };
}

/** 重读完整历史并确认答案长度、Turn 顺序和唯一 final item 均来自服务端持久快照。 @author kongweiguang */
async function readCompletedReply(session, threadId, turnId, expectedText) {
  const history = rpcResult(await session.request("thread/read", { threadId, tail: true, limit: 200 }), "thread/read completed reply");
  assert.equal(history.threadId, threadId);
  const turn = history.turns?.find((item) => item.turnId === turnId);
  assert.equal(turn?.status, "completed");
  const items = history.items.filter((item) => item.turnId === turnId);
  const tools = items.filter((item) => item.kind === "tool_call");
  assert.deepEqual(tools.map((item) => item.toolName), ["read", "exec_command"]);
  const finalItems = items.filter((item) => item.kind === "final_answer");
  assert.equal(finalItems.length, 1, "the completed stream must be archived exactly once");
  const summaryIndices = items.flatMap((item, index) => item.kind === "reasoning_summary" ? [index] : []);
  const toolIndices = items.flatMap((item, index) => item.kind === "tool_call" ? [index] : []);
  const finalIndex = items.findIndex((item) => item.kind === "final_answer");
  assert.equal(summaryIndices.length, 3, "each model response must preserve its public reasoning summary");
  assert.equal(toolIndices.length, 2);
  assert.ok(
    summaryIndices[0] < toolIndices[0]
      && toolIndices[0] < summaryIndices[1]
      && summaryIndices[1] < toolIndices[1]
      && toolIndices[1] < summaryIndices[2]
      && summaryIndices[2] < finalIndex,
    "reasoning, read Tool, reasoning, shell Tool, reasoning, and final answer must remain ordered",
  );
  const body = finalItems[0].truncated === true
    ? await readCompleteItemContent(session, threadId, finalItems[0].itemId)
    : finalItems[0].text;
  assert.equal(body, expectedText);
  const longLines = body.split("\n").filter((line) => /^JA_REPLY_LONG_\d{4}/u.test(line));
  assert.equal(longLines.length, 900);
  assert.equal(new Set(longLines).size, 900);
  assert.ok(body.includes(finalFirstMarker) && body.includes(finalLastMarker));
  assert.equal(typeof tools[1].presentation?.artifactId, "string");
  return { history, finalItem: finalItems[0], toolCall: tools[1], itemKinds: items.map((item) => item.kind) };
}

/** 子命令只继承本次临时 JA_HOME/JDK/JAR，不接受真实 Provider key 或 endpoint 环境变量。 @author kongweiguang */
function isolatedCliEnvironment(directories, artifacts) {
  const environment = {
    ...process.env,
    JA_HOME: directories.home,
    JA_DEBUG_JAVA: artifacts.java,
    JA_DEBUG_JAR: artifacts.jar,
  };
  for (const key of [
    "JA_REAL_PROVIDER_API_KEY",
    "JA_REAL_PROVIDER_BASE_URL",
    "JA_REAL_PROVIDER_MODEL",
    "JA_REAL_PROVIDER_NAME",
    "JA_REAL_PROVIDER_API",
    "JA_REAL_PROVIDER_REASONING_LEVEL",
    "JA_REAL_PROVIDER_AUTHORIZED",
  ]) delete environment[key];
  return environment;
}

/** 只记录代理变量是否存在、代理协议类别和 loopback bypass，不输出任何 URL、主机或凭据。 @author kongweiguang */
function summarizeProxyEnvironment() {
  const proxyNames = ["HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"];
  const proxies = proxyNames.flatMap((name) => {
    const value = process.env[name];
    if (typeof value !== "string" || value.trim() === "") return [];
    let type = "non-url";
    try {
      type = new URL(value).protocol.replace(/:$/u, "").toLowerCase();
    } catch {
      // Keep malformed proxy values private while recording that a value was configured.
    }
    return [{ name, type }];
  });
  const noProxy = [process.env.NO_PROXY, process.env.no_proxy].filter((value) => typeof value === "string");
  const loopbackBypass = noProxy.some((value) => value.split(",").some((entry) => {
    const target = entry.trim().toLowerCase().replace(/^\./u, "");
    return target === "127.0.0.1" || target === "localhost" || target === "::1" || target === "*";
  }));
  const javaOptions = `${process.env.JAVA_TOOL_OPTIONS ?? ""} ${process.env.JDK_JAVA_OPTIONS ?? ""}`;
  return {
    proxies,
    noProxySet: noProxy.length > 0,
    noProxyIncludesLoopback: loopbackBypass,
    javaOptionsContainProxyProperty: /-Dhttps?\.proxy(Host|Port)\s*=/iu.test(javaOptions),
  };
}

/** 运行完整离线 ConPTY 回复场景，逐阶段落盘可复核证据并 finally 清理隔离 profile。 @author kongweiguang */
export async function runAcceptance() {
  assert.equal(process.platform, "win32", "reply acceptance requires the real Windows ConPTY harness");
  const artifacts = await resolveArtifacts();
  const isolated = await createReplyRunDirectories();
  const evidence = {
    status: "running",
    runtime: "current ja.exe + isolated JDK 25 App Server JAR + Windows ConPTY",
    artifactFingerprint: {
      cliSha256: artifacts.cliSha256,
      appServerJarSha256: artifacts.jarSha256,
      appServerJarBytes: artifacts.jarBytes,
      javaVersion: artifacts.javaVersion,
    },
    provider: "127.0.0.1 OpenAI Responses scripted fixture; no paid or real Provider request",
    providerEndpoint: { host: "127.0.0.1", port: null },
    proxyEnvironment: summarizeProxyEnvironment(),
    evidenceRoot,
    markdown: {},
    interaction: {},
    recovery: {},
  };
  let provider;
  let session;
  let ui;
  let disconnectedUi;
  let restoredUi;
  let hiddenArtifactId;
  let hiddenArtifactIdentity;
  let profileRemoved = false;
  let ownerStopped = false;
  let uiWasStarted = false;
  let failure;
  try {
    const markdown = await readFile(fixturePath, "utf8");
    const finalFixture = buildReplyBody(markdown);
    const toolOutput = buildToolOutput();
    assert.ok(Buffer.byteLength(toolOutput, "utf8") > 64 * 1024);
    await writeFile(join(isolated.directories.workspace, "reply-source.txt"), `${readMarker}\nold tool detail source\n`, "utf8");
    await writeFile(join(isolated.directories.workspace, "tool-output.txt"), toolOutput, "utf8");
    await writeFile(join(isolated.runRoot, "reply-markdown.fixture.md"), markdown, "utf8");
    await writeFile(join(isolated.runRoot, "reply-expected.md"), finalFixture.text, "utf8");
    const piComparison = await renderPiReference(finalFixture.text, isolated.runRoot);
    evidence.markdown = {
      fixtureSha256: createHash("sha256").update(markdown).digest("hex").toUpperCase(),
      finalSha256: createHash("sha256").update(finalFixture.text).digest("hex").toUpperCase(),
      finalBytes: finalFixture.byteLength,
      finalLines: finalFixture.longLines.length,
      toolOutputBytes: Buffer.byteLength(toolOutput, "utf8"),
      toolOutputSha256: createHash("sha256").update(toolOutput).digest("hex").toUpperCase(),
      piComparison,
      streamDelivery: {
        source: "unpaced code-point SSE deltas between the first-partial and disconnect gates",
        reconciliation: "compare code-point count, UTF-8 bytes, SHA-256, and marker order; RPC notification count may differ",
      },
    };
    provider = await startReplyProvider(finalFixture.text);
    evidence.providerEndpoint.port = provider.providerPort;
    const thread = await seedThread(artifacts, isolated.directories, provider.endpoint);
    evidence.threadId = thread.threadId;
    const harness = await compileConPtyHarness(artifacts, isolated.runRoot);
    const firstLog = join(isolated.runRoot, "reply-live.vt");
    ui = startConPtyCli(
      artifacts,
      isolated.directories,
      harness,
      thread.threadId,
      undefined,
      firstLog,
      shellReturnCommand(artifacts, thread.threadId),
    );
    uiWasStarted = true;
    await ui.harnessReady;
    session = await connectReplySession(isolated.directories, thread.threadId);
    evidence.runtimeOwner = {
      ...session.identity,
      observedBaselineRevision: session.baselineRevision,
      process: await inspectRuntimeOwnerProvenance(session.endpoint, artifacts),
    };
    await ui.waitForScreen((visible) => visible.includes("Ja CLI 用量 ConPTY 验收"), "isolated thread in CLI", 45_000);
    ui.sendKeys(`\u001b[200~${promptMarker}\u001b[201~`);
    const draftScreen = await ui.waitForScreen(
      (visible) => visible.includes(promptMarker),
      "bracketed-paste prompt draft",
    );
    evidence.interaction.promptDraft = await writeScreenEvidence(isolated.runRoot, "prompt-draft", ui.screen);
    assert.ok(draftScreen.text.includes(promptMarker));
    await delay(250);
    ui.sendKeys("\r");
    const accepted = await withTimeout(
      session.waitForEvent(
        "turn/state-changed",
        (frame) => frame.params?.threadId === thread.threadId
          && ["queued", "running"].includes(frame.params?.to)
          && typeof frame.params?.turnId === "string",
        45_000,
      ),
      45_000,
      "assistant Turn acceptance",
    );
    assert.equal(accepted.params?.serverInstanceId, session.identity.serverInstanceId);
    assert.equal(accepted.params?.generation, session.identity.runtimeGeneration);
    assert.match(accepted.params?.eventId, /^evt_[A-Za-z0-9._:-]{1,96}$/u);
    assert.ok(Number.isSafeInteger(accepted.params?.sequence) && accepted.params.sequence > 0);
    assert.ok(Number.isSafeInteger(accepted.params?.threadRevision) && accepted.params.threadRevision >= 0);
    assert.ok(
      ["queued", "running", "waiting_input", "waiting_approval", "suspended", "completed", "failed", "cancelled"]
        .includes(accepted.params?.from),
      "turn/state-changed from must match the v2 Turn state enum",
    );
    assert.ok(["queued", "running"].includes(accepted.params?.to), "a submitted prompt must enter an active state");
    const turnId = accepted.params?.turnId;
    assert.match(turnId, /^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u);
    evidence.turnId = turnId;
    evidence.turnAdmission = {
      method: "turn/state-changed",
      serverInstanceId: accepted.params.serverInstanceId,
      runtimeGeneration: accepted.params.generation,
      threadId: accepted.params.threadId,
      turnId,
      from: accepted.params.from,
      to: accepted.params.to,
      threadRevision: accepted.params.threadRevision,
      sequence: accepted.params.sequence,
    };
    session.observeTurn(turnId);

    await withTimeout(provider.waitForAgentRequest(1), 45_000, "read Tool model request");
    const readApproval = await approveTool(ui, session, turnId, "read", isolated.runRoot);
    evidence.interaction.readApprovalId = readApproval.approvalId;
    await withTimeout(provider.waitForAgentRequest(2), 45_000, "Shell Tool model request");
    const shellApproval = await approveTool(ui, session, turnId, "exec_command", isolated.runRoot);
    evidence.interaction.shellApprovalId = shellApproval.approvalId;
    const finalRequest = await withTimeout(provider.finalRequest, 45_000, "gated Markdown final request");
    assert.equal(finalRequest.inputHasToolPreview, true);
    evidence.interaction.toolResultReachedFinalRequest = true;

    const draft = "详情返回后保留的草稿🙂";
    ui.sendKeys(draft);
    await ui.waitForScreen((visible) => visible.includes(draft), "draft before tool step details");
    ui.sendKeys("\u000f");
    const choices = await ui.waitForScreen(
      isToolStepsListScreen,
      "Ctrl+O tool step list",
    );
    evidence.interaction.toolList = await writeScreenEvidence(isolated.runRoot, "tool-list", ui.screen);
    assert.ok(choices.text.includes("reply-source.txt"));
    ui.sendKeys("\u001b[A\r");
    const olderToolDetail = await ui.waitForScreen(
      (visible) => visible.includes(readMarker)
        && visible.includes("reply-source.txt")
        && visible.includes("Esc 返回")
        && !visible.includes("工具步骤"),
      "older read Tool detail",
    );
    evidence.interaction.olderToolDetail = await writeScreenEvidence(isolated.runRoot, "older-tool-detail", ui.screen);
    assert.ok(olderToolDetail.text.includes(readMarker));
    ui.sendKeys("\u001b");
    await ui.waitForScreen(
      isToolStepsListScreen,
      "tool list after detail",
    );
    ui.sendKeys("\u001b[B\r");
    await ui.waitForScreen(
      (visible) => visible.includes(toolFirstMarker) && visible.includes("Esc 返回") && !visible.includes("工具步骤"),
      "large Tool preview detail",
    );

    const historyBeforeFinal = rpcResult(await session.request(
      "thread/read",
      { threadId: thread.threadId, tail: true, limit: 200 },
    ), "thread/read before final content");
    const toolCall = historyBeforeFinal.items.find(
      (item) => item.turnId === turnId && item.kind === "tool_call" && item.toolName === "exec_command",
    );
    const artifactId = toolCall?.presentation?.artifactId;
    assert.equal(typeof artifactId, "string");
    hiddenArtifactIdentity = {
      threadId: thread.threadId,
      turnId,
      callId: toolCall.callId,
      artifactId,
    };
    const storedToolArtifact = await readCompleteToolArtifact(session, hiddenArtifactIdentity);
    const storedToolRows = Array.from(storedToolArtifact.content.matchAll(/^JA_REPLY_TOOL_ROW_(\d{4})/gmu), (match) => match[0]);
    assert.ok(storedToolRows.length > 0, "the stored Tool artifact must retain at least one stable output row");
    assert.equal(new Set(storedToolRows).size, storedToolRows.length, "stored Tool artifact rows must not duplicate");
    const storedToolOrdinals = storedToolRows.map((row) => Number(row.slice("JA_REPLY_TOOL_ROW_".length)));
    for (let index = 1; index < storedToolOrdinals.length; index += 1) {
      assert.equal(storedToolOrdinals[index], storedToolOrdinals[index - 1] + 1, "stored Tool artifact rows must keep their source order");
    }
    assert.ok(storedToolArtifact.content.includes(toolFirstMarker));
    assert.ok(storedToolArtifact.content.includes(toolUnicodeSample), "stored Tool artifact must preserve its Unicode sample");
    const storedToolLastMarker = storedToolRows.at(-1);
    evidence.interaction.storedToolArtifact = {
      characters: storedToolArtifact.totalCharacters,
      bytes: storedToolArtifact.byteLength,
      sha256: storedToolArtifact.sha256,
      pages: storedToolArtifact.pageCount,
      markerRows: storedToolRows.length,
      firstMarker: storedToolRows[0],
      lastMarker: storedToolLastMarker,
      matchesGeneratedOutput: storedToolArtifact.content === toolOutput,
      presentationTruncated: toolCall.presentation.truncated,
    };
    hiddenArtifactId = await setArtifactLookupAvailable(
      join(isolated.directories.data, "ja.db"),
      hiddenArtifactIdentity,
      false,
    );
    ui.sendKeys("\u001b");
    await ui.waitForScreen(
      isToolStepsListScreen,
      "tool list before artifact error",
    );
    ui.sendKeys("\r");
    const failedArtifactDetail = await ui.waitForScreen(
      (visible) => visible.includes(toolFirstMarker)
        && visible.includes("完整工具产物读取失败")
        && visible.includes("Esc 返回")
        && !visible.includes("工具步骤"),
      "failed artifact read preserves the preview",
    );
    evidence.interaction.failedArtifactDetail = await writeScreenEvidence(isolated.runRoot, "artifact-read-failed", ui.screen);
    assert.ok(failedArtifactDetail.text.includes(toolFirstMarker));
    ui.sendKeys("\u001b[15~");
    await delay(250);
    await ui.pullVt();
    assert.ok(ui.screen.text().includes(toolFirstMarker), "F5 failure recovery must retain the previously available preview");
    hiddenArtifactId = await setArtifactLookupAvailable(
      join(isolated.directories.data, "ja.db"),
      hiddenArtifactIdentity,
      true,
    );
    evidence.interaction.artifactIdentityRestored = true;
    hiddenArtifactId = undefined;
    ui.sendKeys("\u001b[15~");
    await ui.waitForScreen(
      (visible) => visible.split("\n").some((line) => line.trim() === "工具产物")
        && visible.includes(toolFirstMarker)
        && visible.includes("Esc 返回")
        && !visible.includes("工具步骤"),
      "F5 replaces the preview with the recovered artifact",
      45_000,
    );
    ui.sendKeys("\u001b[F");
    const recoveredArtifactDetail = await ui.waitForScreen(
      (visible) => visible.includes(storedToolLastMarker),
      "F5 artifact retry renders the stored tail",
      45_000,
    );
    evidence.interaction.recoveredArtifactDetail = await writeScreenEvidence(isolated.runRoot, "artifact-read-recovered", ui.screen);
    assert.ok(recoveredArtifactDetail.text.includes(storedToolLastMarker));
    assert.ok(recoveredArtifactDetail.text.includes(toolUnicodeSample), "the recovered Tool artifact must preserve its Chinese sample and emoji");

    ui.sendKeys("\u001b");
    await ui.waitForScreen(
      isToolStepsListScreen,
      "tool list after artifact retry",
    );
    ui.sendKeys("\u001b");
    await ui.waitForScreen((visible) => visible.includes(draft), "draft restored after detail return");
    evidence.interaction.draftRestored = true;
    evidence.interaction.returnedComposer = await writeScreenEvidence(isolated.runRoot, "draft-restored", ui.screen);

    provider.releaseFinal();
    await withTimeout(provider.finalPartial, 30_000, "first streamed Markdown segment");
    const initialStream = await ui.waitForScreen(
      (visible) => visible.includes("流式回复渲染验收"),
      "streamed Markdown heading",
      30_000,
    );
    evidence.markdown.firstPartial = await writeScreenEvidence(isolated.runRoot, "markdown-first-partial", ui.screen);
    assert.ok(initialStream.text.includes("流式回复渲染验收"));
    await ui.resize(88, 24);
    evidence.markdown.resize88 = await writeScreenEvidence(isolated.runRoot, "markdown-resize-88", ui.screen);
    ui.sendKeys("\u001b[6~");
    await ui.resize(34, 12);
    evidence.markdown.resize34 = await writeScreenEvidence(isolated.runRoot, "markdown-resize-34", ui.screen);
    provider.releaseFirstPartial();

    const providerCheckpoint = await withTimeout(provider.disconnectCheckpoint, 240_000, "long streaming checkpoint");
    evidence.markdown.providerCheckpoint = providerCheckpoint;
    evidence.markdown.streamDelivery.sourceSseDeltaFramesAtCheckpoint = providerCheckpoint.sourceSseDeltaFrames;
    const confirmedOwner = evidence.runtimeOwner.process.processes.find(
      (entry) => entry.usesConfiguredJdk25 && entry.usesConfiguredJar,
    );
    if (confirmedOwner && providerCheckpoint.network?.requestSocket?.remotePort) {
      evidence.markdown.providerSocketOwnership = await inspectRuntimeProviderSocket(
        confirmedOwner.processId,
        provider.providerPort,
        providerCheckpoint.network.requestSocket.remotePort,
      );
    }
    const beforeStallSample = session.streamTrace();
    await delay(500);
    const afterStallSample = session.streamTrace();
    evidence.markdown.observerProgressSamples = [beforeStallSample, afterStallSample];
    if (!afterStallSample.hasCheckpoint && (afterStallSample.lastStreamSeq ?? 0) < 100) {
      const owner = evidence.runtimeOwner.process.processes.find(
        (entry) => entry.usesConfiguredJdk25 && entry.usesConfiguredJar,
      );
      if (owner) {
        evidence.recovery.ownerThreadDump = await captureOwnedRuntimeThreadDump(
          owner.processId,
          artifacts,
          join(isolated.runRoot, "owner-thread-dump.json"),
        );
      }
    }
    evidence.markdown.observerTextCheckpoint = await withTimeout(
      session.waitForStreamText(providerCheckpoint, 240_000),
      240_000,
      "shared owner assistant text checkpoint",
    );
    evidence.markdown.streamDelivery.rpcNotificationFramesAtCheckpoint = evidence.markdown.observerTextCheckpoint.rpcNotificationFrames;
    const checkpointSnapshot = rpcResult(await withTimeout(
      session.request("thread/read", { threadId: thread.threadId, tail: true, limit: 200 }),
      15_000,
      "shared owner stream checkpoint snapshot",
    ), "thread/read long stream checkpoint");
    const checkpointStream = checkpointSnapshot.liveStream;
    const checkpointAssistantText = (checkpointStream?.segments ?? [])
      .filter((segment) => segment.kind === "assistant")
      .map((segment) => segment.text)
      .join("");
    const checkpointSnapshotMetrics = summarizeReplyText(checkpointAssistantText);
    const checkpointStreamMarkers = Array.from(
      checkpointAssistantText.matchAll(/JA_REPLY_LONG_\d{4}/gu),
      (match) => match[0],
    );
    evidence.markdown.sharedOwnerCheckpoint = {
      revision: checkpointSnapshot.revision,
      turnId: checkpointStream?.turnId ?? null,
      streamSeq: checkpointStream?.streamSeq ?? null,
      segmentCount: checkpointStream?.segments.length ?? 0,
      assistantCharacters: Array.from(checkpointAssistantText).length,
      hasCheckpoint: checkpointAssistantText.includes(finalCheckpointMarker),
      firstMarker: checkpointStreamMarkers[0] ?? null,
      lastMarker: checkpointStreamMarkers.at(-1) ?? null,
      bytes: checkpointSnapshotMetrics.bytes,
      sha256: checkpointSnapshotMetrics.sha256,
      markerCount: checkpointSnapshotMetrics.markerCount,
      markersInOrder: checkpointSnapshotMetrics.markersInOrder,
    };
    assert.equal(checkpointStream?.turnId, turnId, "shared owner snapshot must identify the active Turn");
    assertReplyTextMetricsMatch(providerCheckpoint, checkpointSnapshotMetrics, "thread/read live stream checkpoint");
    const progress = await ui.waitForScreen(
      (visible) => visible.includes(finalCheckpointMarker),
      "long streamed Markdown checkpoint",
      240_000,
    );
    evidence.markdown.disconnectCheckpoint = await writeScreenEvidence(isolated.runRoot, "markdown-disconnect-checkpoint", ui.screen);
    assert.ok(progress.text.includes(finalCheckpointMarker));
    evidence.recovery.disconnectedCli = await disconnectCli(ui, isolated.runRoot);
    disconnectedUi = ui;
    ui = undefined;
    provider.releaseDisconnectCheckpoint();
    const providerFinal = await withTimeout(provider.finalComplete, 180_000, "Provider stream completion after CLI disconnect");
    const finalSourceMetrics = summarizeReplyText(finalFixture.text);
    assertReplyTextMetricsMatch(finalSourceMetrics, providerFinal, "Provider SSE source");
    assert.equal(providerFinal.sourceSseDeltaFrames, provider.finalNetworkMetrics().sourceTextDeltaFrames);
    evidence.markdown.providerStreamFinal = providerFinal;
    const terminal = await withTimeout(
      session.waitForEvent("turn/terminal", (frame) => frame.params?.turnId === turnId, 180_000),
      180_000,
      "background Turn terminal after CLI disconnect",
    );
    assert.equal(terminal.params?.state, "completed");
    evidence.recovery.backgroundTurnCompleted = true;
    const observerFinal = await withTimeout(
      session.waitForStreamText(finalSourceMetrics, 30_000),
      30_000,
      "shared owner final text after Turn terminal",
    );
    evidence.markdown.observerStreamFinal = observerFinal;
    evidence.markdown.streamDelivery.sourceSseDeltaFrames = providerFinal.sourceSseDeltaFrames;
    evidence.markdown.streamDelivery.rpcNotificationFrames = observerFinal.rpcNotificationFrames;

    const completed = await readCompletedReply(session, thread.threadId, turnId, finalFixture.text);
    evidence.recovery.persistedItemKinds = completed.itemKinds;
    evidence.recovery.finalCharacters = Array.from(finalFixture.text).length;
    evidence.recovery.finalBytes = Buffer.byteLength(finalFixture.text, "utf8");
    evidence.recovery.finalSha256 = finalSourceMetrics.sha256;
    assert.deepEqual(provider.stages, [
      "reasoning_read", "tool_read", "reasoning_shell", "tool_shell", "reasoning_final", "final",
    ]);
    assert.deepEqual(provider.completedIds, ["resp_reply_markdown"]);
    assert.equal(provider.agentRequestCount, 3);
    const expectedProviderResponses = provider.agentRequestCount + provider.titleRequestCount;
    assert.equal(provider.responseCount, expectedProviderResponses, "total Responses must include legitimate title requests");
    assert.equal(provider.httpRequestCount, expectedProviderResponses);
    assert.deepEqual(provider.statusPathCounts, { "200 /v1/responses": expectedProviderResponses });
    assert.deepEqual(provider.faults, []);
    evidence.recovery.providerStageOrder = provider.stages;

    const resumedLog = join(isolated.runRoot, "reply-resumed.vt");
    restoredUi = startConPtyCli(
      artifacts,
      isolated.directories,
      harness,
      thread.threadId,
      undefined,
      resumedLog,
      shellReturnCommand(artifacts, thread.threadId),
    );
    await restoredUi.harnessReady;
    await waitForRawText(resumedLog, new RegExp(finalLastMarker, "u"), 60_000);
    await restoredUi.pullVt();
    evidence.recovery.resumedThreadBeforeExit = await writeScreenEvidence(
      isolated.runRoot,
      "reply-resumed-before-exit",
      restoredUi.screen,
    );
    restoredUi.sendKeys("\u0004");
    const shellReady = await restoredUi.waitForScreen(
      (visible) => visible.includes("JA_REPLY_CLI_EXIT=0") && visible.includes(shellRestoredMarker),
      "PowerShell control restored after Ctrl+D closes the completed resumed CLI",
      60_000,
    );
    evidence.recovery.resumedScreen = await writeScreenEvidence(isolated.runRoot, "reply-resumed", restoredUi.screen);
    assert.ok(shellReady.text.includes("JA_REPLY_CLI_EXIT=0"));
    const shellRestoration = await finishWaitingPowerShell(
      restoredUi,
      isolated.runRoot,
      "shell-restored",
      "restored PowerShell reads input and completes its command",
    );
    restoredUi = undefined;
    evidence.recovery.shellRestoration = {
      cliExitCodeMarker: "JA_REPLY_CLI_EXIT=0",
      shellReadyMarker: shellRestoredMarker,
      inputAcceptedMarker: shellInputAcceptedMarker,
      accepted: shellRestoration.accepted,
      transcript: shellRestoration.transcript,
    };
    const exit = shellRestoration.exit;
    assert.equal(exit.forced, false, "the resumed TUI and PowerShell must exit through their ordinary terminal paths");
    assert.equal(exit.childExitCode, 0);
    evidence.recovery.normalTuiExit = {
      cliExitMarker: "JA_REPLY_CLI_EXIT=0",
      shellExitCode: exit.childExitCode,
      harnessExitCode: exit.harnessExitCode,
      childExitCode: exit.childExitCode,
      forced: exit.forced,
    };

    const exportPath = join(isolated.directories.workspace, "reply-answer-export.md");
    const exported = await execFileAsync(artifacts.cli, ["export-answer", thread.threadId, turnId, exportPath], {
      cwd: isolated.directories.workspace,
      env: isolatedCliEnvironment(isolated.directories, artifacts),
      windowsHide: true,
      timeout: 60_000,
      maxBuffer: 2 * 1024 * 1024,
    });
    const exportText = await readFile(exportPath, "utf8");
    assert.equal(exportText, finalFixture.text);
    evidence.recovery.answerExport = {
      bytes: Buffer.byteLength(exportText, "utf8"),
      sha256: createHash("sha256").update(exportText).digest("hex").toUpperCase(),
      notice: exported.stdout.trim().slice(-300),
    };
    evidence.cleanup = { ...(evidence.cleanup ?? {}), owner: await shutdownReplyOwner(isolated.directories, thread.threadId, session) };
    ownerStopped = true;
    const backgroundShell = await finishWaitingPowerShell(
      disconnectedUi,
      isolated.runRoot,
      "background-shell-restored",
      "the original PowerShell accepts input after background completion and owner shutdown",
    );
    disconnectedUi = undefined;
    assert.equal(backgroundShell.accepted, true);
    assert.equal(backgroundShell.exit.forced, false);
    assert.equal(backgroundShell.exit.childExitCode, 0);
    evidence.recovery.backgroundShellRestoration = {
      inputAcceptedMarker: shellInputAcceptedMarker,
      accepted: backgroundShell.accepted,
      transcript: backgroundShell.transcript,
      shellExitCode: backgroundShell.exit.childExitCode,
      harnessExitCode: backgroundShell.exit.harnessExitCode,
      forced: backgroundShell.exit.forced,
    };
    evidence.status = "completed";
  } catch (error) {
    failure = error instanceof Error ? error : new Error(String(error));
    evidence.status = "failed";
    evidence.failure = failure.message;
    if (session) {
      try {
        const snapshot = rpcResult(await withTimeout(
          session.request("thread/read", { threadId: evidence.threadId, tail: true, limit: 20 }),
          10_000,
          "failure Thread snapshot",
        ), "thread/read failure diagnosis");
        const itemKindCounts = {};
        for (const item of snapshot.items ?? []) itemKindCounts[item.kind] = (itemKindCounts[item.kind] ?? 0) + 1;
        evidence.failureThreadSnapshot = {
          revision: snapshot.revision,
          turnCount: snapshot.turns?.length ?? 0,
          turns: (snapshot.turns ?? []).slice(-8).map(({ turnId, status }) => ({ turnId, status })),
          itemCount: snapshot.items?.length ?? 0,
          itemKindCounts,
          liveStream: snapshot.liveStream === null ? null : {
            turnId: snapshot.liveStream?.turnId ?? null,
            streamSeq: snapshot.liveStream?.streamSeq ?? null,
            segments: (snapshot.liveStream?.segments ?? []).map((segment) => ({
              kind: segment.kind,
              characters: Array.from(segment.text ?? "").length,
              containsCheckpoint: segment.kind === "assistant" && segment.text.includes(finalCheckpointMarker),
              lastMarker: Array.from(
                (segment.text ?? "").matchAll(/JA_REPLY_LONG_\d{4}/gu),
                (match) => match[0],
              ).at(-1) ?? null,
            })),
          },
        };
      } catch (diagnosticError) {
        evidence.failureThreadReadError = diagnosticError instanceof Error ? diagnosticError.message : "thread/read diagnostic failed";
      }
    }
    if (ui) {
      try {
        await ui.pullVt();
        evidence.failureScreen = await writeScreenEvidence(isolated.runRoot, "failure-current-screen", ui.screen);
      } catch (diagnosticError) {
        evidence.failureScreenError = diagnosticError instanceof Error ? diagnosticError.message : "current screen capture failed";
      }
    }
  } finally {
    if (session) {
      evidence.observerNotifications = session.eventTrace();
      evidence.markdown.observerTextDeltaTrace = session.streamTrace();
    }
    if (provider) {
      evidence.providerTrace = {
        stages: [...provider.stages],
        faults: [...provider.faults],
        agentRequestCount: provider.agentRequestCount,
        responseCount: provider.responseCount,
        titleRequestCount: provider.titleRequestCount,
        httpRequestCount: provider.httpRequestCount,
        statusPathCounts: provider.statusPathCounts,
        completedIds: [...provider.completedIds],
        finalNetworkMetrics: provider.finalNetworkMetrics(),
      };
    }
    if (provider) await provider.close().catch((error) => { evidence.cleanup = { ...(evidence.cleanup ?? {}), provider: String(error) }; });
    if (uiWasStarted && !ownerStopped) {
      try {
        evidence.cleanup = {
          ...(evidence.cleanup ?? {}),
          owner: await shutdownReplyOwner(isolated.directories, evidence.threadId, session),
        };
        ownerStopped = true;
      } catch (error) {
        evidence.cleanup = {
          ...(evidence.cleanup ?? {}),
          ownerShutdownError: error instanceof Error ? error.message : String(error),
        };
      }
    }
    if (session && !ownerStopped) await session.close().catch(() => undefined);
    if (!uiWasStarted) ownerStopped = true;
    if (hiddenArtifactId && hiddenArtifactIdentity) {
      try {
        await setArtifactLookupAvailable(
          join(isolated.directories.data, "ja.db"),
          hiddenArtifactIdentity,
          true,
        );
      } catch (error) {
        evidence.cleanup = { ...(evidence.cleanup ?? {}), artifactRestoreError: String(error) };
      }
    }
    if (ui) {
      evidence.cleanup = { ...(evidence.cleanup ?? {}), cli: await ui.close().catch((error) => ({ error: error.message })) };
    }
    if (restoredUi) {
      evidence.cleanup = { ...(evidence.cleanup ?? {}), restoredCli: await restoredUi.close().catch((error) => ({ error: error.message })) };
    }
    if (disconnectedUi) {
      try {
        evidence.cleanup = {
          ...(evidence.cleanup ?? {}),
          disconnectedShell: await finishWaitingPowerShell(
            disconnectedUi,
            isolated.runRoot,
            "background-shell-cleanup",
            "cleanup exits the original restored PowerShell shell",
          ),
        };
      } catch (error) {
        evidence.cleanup = {
          ...(evidence.cleanup ?? {}),
          disconnectedShellError: error instanceof Error ? error.message : "original PowerShell cleanup failed",
          disconnectedShell: await disconnectedUi.close().catch((closeError) => ({ error: closeError.message })),
        };
      }
      disconnectedUi = undefined;
    }
    try {
      if (ownerStopped) {
        await removeOwnedProfile(isolated.runRoot, isolated.profileRoot);
        profileRemoved = true;
      } else {
        evidence.cleanup = { ...(evidence.cleanup ?? {}), profileRemovalSkipped: "isolated App Server owner did not confirm shutdown" };
      }
    } catch (error) {
      evidence.cleanup = { ...(evidence.cleanup ?? {}), profileRemovalError: String(error) };
      if (!failure) failure = error instanceof Error ? error : new Error(String(error));
      evidence.status = "failed";
    }
    evidence.cleanup = { ...(evidence.cleanup ?? {}), profileRemoved };
    await writeFile(join(isolated.runRoot, "acceptance.json"), `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  }
  if (failure) {
    process.stderr.write(`CLI reply ConPTY acceptance failed. Evidence: ${isolated.runRoot}\n`);
    throw failure;
  }
  process.stdout.write(`${JSON.stringify({
    status: evidence.status,
    evidenceDirectory: isolated.runRoot,
    artifactFingerprint: evidence.artifactFingerprint,
    markdown: evidence.markdown,
    recovery: evidence.recovery,
    cleanup: evidence.cleanup,
  }, null, 2)}\n`);
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await runAcceptance();
}
