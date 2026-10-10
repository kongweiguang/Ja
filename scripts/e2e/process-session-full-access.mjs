// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { mkdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  collectToolEvidence,
  committedTerminalReply,
  createIsolatedDirectories,
  createOperationKey,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
  requireRpcErrorCode,
} from "./real-provider-smoke.mjs";
import { providerStream } from "./media-schema-loopback.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const markerVariable = "JA_PROCESS_SESSION_ACCEPTANCE_MARKER";
const restartMarkerVariable = "JA_PROCESS_SESSION_RESTART_MARKER";
const restartPidFileVariable = "JA_PROCESS_SESSION_RESTART_PID_FILE";
const cancelMarkerVariable = "JA_PROCESS_SESSION_CANCEL_MARKER";
const cancelRootPidFileVariable = "JA_PROCESS_SESSION_CANCEL_ROOT_PID_FILE";
const cancelChildPidFileVariable = "JA_PROCESS_SESSION_CANCEL_CHILD_PID_FILE";
const cancelChildCodeVariable = "JA_PROCESS_SESSION_CANCEL_CHILD_CODE";
const placeholderSecret = "process-session-loopback-placeholder";
const startMarker = "JA_PROCESS_SESSION_START_TURN";
const firstWriteMarker = "JA_PROCESS_SESSION_WRITE_ONE_TURN";
const lastWriteMarker = "JA_PROCESS_SESSION_WRITE_THREE_TURN";
const restartTurnMarker = "JA_PROCESS_SESSION_RESTART_TURN";
const cancelTurnMarker = "JA_PROCESS_SESSION_CANCEL_TURN";
const requestTimeoutMs = 60_000;
const evidenceOutputLimit = 2_048;
const apis = Object.freeze([
  Object.freeze({
    api: "openai_responses",
    route: "/v1/responses",
    id: "responses",
    providerId: "provider_process_responses",
    modelId: "model_process_responses",
    credentialId: "cred_process_session_responses",
  }),
  Object.freeze({
    api: "openai_chat_completions",
    route: "/v1/chat/completions",
    id: "chat",
    providerId: "provider_process_chat",
    modelId: "model_process_chat",
    credentialId: "cred_process_session_chat",
  }),
  Object.freeze({
    api: "anthropic_messages",
    route: "/v1/messages",
    id: "anthropic",
    providerId: "provider_process_anthropic",
    modelId: "model_process_anthropic",
    credentialId: "cred_process_session_anthropic",
  }),
]);

/** 创建可由 loopback Provider 与验收控制流共同推进的一次性屏障。 */
function createDeferred() {
  let resolve;
  const promise = new Promise((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

/** 优先使用显式指定的验收 JDK，防止 PATH 中的其它 Java 版本被静默选中。 */
function resolveJava(environment = process.env) {
  if (environment.JA_TEST_JAVA) return environment.JA_TEST_JAVA;
  if (environment.JAVA_HOME) {
    return join(environment.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java");
  }
  return process.platform === "win32" ? "java.exe" : "java";
}

/** 冻结 JVM 或 Native 的唯一启动规格；显式 Native 路径避免对 GraalVM 产物套用 JAR/JDK 参数。 */
export async function resolveLaunchSpec(environment = process.env) {
  const jarInput = environment.JA_PROCESS_SESSION_JAR?.trim();
  const executableInput = environment.JA_PROCESS_SESSION_EXECUTABLE?.trim();
  if (jarInput && executableInput) {
    throw new Error("JA_PROCESS_SESSION_EXECUTABLE and JA_PROCESS_SESSION_JAR are mutually exclusive");
  }
  if (executableInput) {
    const executable = resolve(executableInput);
    const metadata = await stat(executable);
    if (!metadata.isFile() || metadata.size === 0) {
      throw new Error("process-session acceptance Native executable is unavailable");
    }
    return Object.freeze({
      kind: "native",
      artifactPath: executable,
      command: executable,
      prefixArgs: Object.freeze([]),
    });
  }

  const jar = resolve(jarInput
    ?? join(repoRoot, "ja-agent", "ja-app-server", "target", "ja-app-server.jar"));
  const metadata = await stat(jar);
  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error("process-session acceptance JAR is unavailable");
  }
  return Object.freeze({
    kind: "jvm",
    artifactPath: jar,
    command: resolveJava(environment),
    prefixArgs: Object.freeze(["-jar", jar]),
  });
}

/** 在启动服务前核实 JDK 主版本，遵守项目 Java 25 门禁。 */
async function assertJava25(java) {
  let output = "";
  try {
    const result = await execFileAsync(java, ["-version"], { windowsHide: true, maxBuffer: 64 * 1024 });
    output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  } catch (error) {
    output = `${error?.stdout ?? ""}\n${error?.stderr ?? ""}`;
    if (output.trim() === "") throw new Error("selected Java executable could not be started");
  }
  const match = output.match(/\bversion\s+"?(\d+)/iu) ?? output.match(/\bopenjdk\s+(\d+)/iu);
  if (match === null || Number(match[1]) !== 25) {
    throw new Error("process-session acceptance requires Java 25");
  }
  return Number(match[1]);
}

/** 校验 JA-RPC 封套，并只把错误目录中的稳定错误码带入诊断。 */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    throw new Error(`${operation} failed: ${requireRpcErrorCode(frame.error)}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 只记录有界进程观测；证据离开临时 profile 前必须移除宿主环境标记值。 */
function processSnapshotEvidence(snapshot) {
  const output = String(snapshot?.output ?? "");
  const redactedOutput = output.replace(/JA_PROCESS_HOST_ENV:[^\r\n]*/gu, "JA_PROCESS_HOST_ENV:<redacted>");
  return {
    sessionId: snapshot?.sessionId ?? null,
    generation: snapshot?.generation ?? null,
    state: snapshot?.state ?? "unknown",
    outputSequence: snapshot?.outputSequence ?? null,
    nextSequence: snapshot?.nextSequence ?? null,
    exitCode: snapshot?.exitCode ?? null,
    hostEnvironmentMarkerObserved: output.includes("JA_PROCESS_HOST_ENV:"),
    output: redactedOutput.slice(-evidenceOutputLimit),
    outputTruncated: redactedOutput.length > evidenceOutputLimit,
  };
}

/** Anthropic 会把 tool_result 编成新的 user 消息；逆序查找含当前 marker 的用户块，避免续答落入 fallback。 */
function latestScenario(payload) {
  const messages = Array.isArray(payload?.input)
    ? payload.input
    : Array.isArray(payload?.messages)
      ? payload.messages
      : [];
  const collectText = (value, output = []) => {
    if (typeof value === "string") output.push(value);
    else if (Array.isArray(value)) value.forEach((item) => collectText(item, output));
    else if (value !== null && typeof value === "object") {
      if (typeof value.text === "string") output.push(value.text);
      else Object.values(value).forEach((item) => collectText(item, output));
    }
    return output.join(" ");
  };
  const userContents = typeof payload?.input === "string"
    ? [payload.input]
    : [...messages].reverse().filter((item) => item?.role === "user").map((item) => item.content);
  for (const content of userContents) {
    const text = collectText(content);
    if (text.includes(startMarker)) return "start";
    if (text.includes(firstWriteMarker)) return "write_one";
    if (text.includes(lastWriteMarker)) return "write_three";
    if (text.includes(restartTurnMarker)) return "restart";
    if (text.includes(cancelTurnMarker)) return "cancel";
  }
  return null;
}

/** 不猜协议专属 call 输出字段；本 fixture 的唯一模型工具调用 ID 已具备全局唯一性。 */
function hasToolResult(payload, callId) {
  return JSON.stringify(payload).includes(callId);
}

/** 只投影失败 Tool 的稳定 JA-RPC 字段；cause type 不在公开事件中，避免把宿主异常或参数带入 evidence。 */
export function collectToolResultDiagnostics(events, turnId) {
  const toolNames = new Map();
  for (const frame of events) {
    if (frame?.method !== "assistant/model-step-committed" || frame.params?.turnId !== turnId) continue;
    for (const call of frame.params?.toolCalls ?? []) {
      if (typeof call?.callId === "string" && typeof call?.toolName === "string") {
        toolNames.set(call.callId, call.toolName);
      }
    }
  }

  const diagnostics = [];
  for (const frame of events) {
    if (frame?.method !== "tool/batch-committed" || frame.params?.turnId !== turnId) continue;
    for (const result of frame.params?.results ?? []) {
      if (result?.outcome !== "failed") continue;
      const presentation = result.presentation;
      const errorCode = typeof result.errorCode === "string" && /^[A-Z][A-Z0-9_]{0,127}$/u.test(result.errorCode)
        ? result.errorCode
        : null;
      diagnostics.push({
        callId: typeof result.callId === "string" ? result.callId : null,
        operation: toolNames.get(result.callId) ?? "unknown",
        causeType: null,
        causeTypeAvailable: false,
        error: { code: errorCode, outcome: result.outcome },
        presentation: {
          kind: ["read", "edit", "write", "shell", "mcp"].includes(presentation?.kind)
            ? presentation.kind
            : null,
          status: ["pending", "running", "waiting_approval", "success", "error", "cancelled"]
            .includes(presentation?.status) ? presentation.status : null,
        },
      });
    }
  }
  return diagnostics;
}

/** 在每次 sidecar 启动后等待 ready，并核对初始化事件中的代际确实来自本次启动参数。 */
async function initializeSidecar(session, expectedGeneration, label) {
  const initialized = rpcResult(
    await session.request("runtime/initialize", initializeParams()),
    `runtime/initialize ${label}`,
  );
  assert.equal(initialized?.runtime?.engine, "ja-kernel");
  session.notifyInitialized();
  const ready = await session.waitForEvent(
    "runtime/status-changed",
    (frame) => frame.params?.status === "ready" && frame.params?.generation === expectedGeneration,
    30_000,
  );
  return { initialized, generation: ready.params.generation };
}

/** 为每个协议和阶段生成独立 call identity，避免历史 Thread 被错误匹配为当前结果。 */
function processCallId(entry, stage) {
  return `call_process_${entry.id}_${stage}`;
}

/** 统一读取原生工具定义，保留 Responses、Chat 与 Anthropic 的真实 schema 外形。 */
function providerToolCatalog(payload) {
  return (Array.isArray(payload?.tools) ? payload.tools : []).flatMap((tool) => {
    const definition = tool?.function ?? tool;
    if (typeof definition?.name !== "string") return [];
    const schema = definition.parameters ?? definition.input_schema;
    return [{
      name: definition.name,
      properties: Object.keys(schema?.properties ?? {}).sort(),
    }];
  });
}

/** 限制 loopback 请求体大小，避免验收 fixture 无界保留模型上下文。 */
async function readBoundedJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("Provider request exceeded 4 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 固定进程 Turn 顺序；子进程复用当前 PowerShell 路径以兼容 pwsh，PTY 用 CR、重定向 stdin 用 CRLF 提交行。 */
async function handleProviderRequest(request, response, state) {
  response.setHeader("cache-control", "no-store");
  response.setHeader("connection", "close");
  const api = apis.find((entry) => entry.route === request.url);
  if (request.method !== "POST" || api === undefined) {
    response.writeHead(404, { "content-type": "application/json" });
    response.end('{"error":"not_found"}');
    return;
  }

  try {
    const payload = await readBoundedJson(request);
    const serializedPayload = JSON.stringify(payload);
    if (serializedPayload.includes("<user_request>") && serializedPayload.includes("<assistant_reply>")) {
      state.observations.push({ api: api.api, scenario: "title", phase: "title" });
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      response.end(providerStream(api.api, "process_title", undefined, "Process session acceptance"));
      return;
    }
    const scenario = latestScenario(payload);
    if (scenario === null) {
      state.observations.push({ api: api.api, scenario: "fallback", phase: "fallback" });
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      response.end(providerStream(api.api, "process_fallback", undefined, "Process session acceptance"));
      return;
    }

    const stage = scenario;
    const callId = processCallId(api, stage);
    const call = scenario === "start"
      ? {
        callId,
        name: "exec_command",
        arguments: {
          cmd: `[Console]::Out.WriteLine('JA_PROCESS_HOST_ENV:' + $env:${markerVariable}); `
            + "$count = 0; while ($count -lt 3) { $line = [Console]::In.ReadLine(); "
            + "if ($null -eq $line) { break }; $count++; "
            + "[Console]::Out.WriteLine(('JA_PROCESS_LINE_{0}:{1}' -f $count, $line)) }",
          tty: api.id === "responses",
          yield_time_ms: 250,
        },
      }
      : scenario === "write_one"
        ? {
          callId,
          name: "write_stdin",
          arguments: {
          session_id: state.sessionId,
            input: api.id === "responses" ? "PROCESS_LINE_ONE\r" : "PROCESS_LINE_ONE\r\n",
            yield_time_ms: 2_000,
          },
        }
        : scenario === "write_three"
          ? {
            callId,
            name: "write_stdin",
            arguments: {
              session_id: state.sessionId,
            input: api.id === "responses" ? "PROCESS_LINE_THREE\r" : "PROCESS_LINE_THREE\r\n",
            close_input: api.id !== "responses",
              yield_time_ms: 2_000,
            },
          }
          : scenario === "restart"
            ? {
              callId,
              name: "exec_command",
              arguments: {
                cmd: `[IO.File]::WriteAllText($env:${restartPidFileVariable}, `
                  + `"${state.restartMarker}|$PID"); `
                  + `[Console]::Out.WriteLine('JA_PROCESS_RESTART_ROOT:${state.restartMarker}:' + $PID); `
                  + "[void][Console]::In.ReadLine()",
                yield_time_ms: 250,
              },
            }
            : {
              callId,
              name: "exec_command",
              arguments: {
                cmd: "$shell = (Get-Process -Id $PID).Path; $child = Start-Process -FilePath $shell "
                  + `-ArgumentList @('-NoProfile','-NoLogo','-NonInteractive','-EncodedCommand',`
                  + `$env:${cancelChildCodeVariable}) -PassThru -WindowStyle Hidden; `
                  + `[IO.File]::WriteAllText($env:${cancelRootPidFileVariable}, `
                  + `"${state.cancelMarker}|$PID"); `
                  + `[Console]::Out.WriteLine('JA_PROCESS_CANCEL_ROOT:${state.cancelMarker}:' `
                  + `+ $PID + ':CHILD:' + $child.Id); `
                  + "[void][Console]::In.ReadLine()",
                yield_time_ms: 250,
              },
            };

  if (["write_one", "write_three"].includes(scenario) && typeof state.sessionId !== "string") {
      throw new Error("process session identity was not provided before the write turn");
    }
    const toolCatalog = providerToolCatalog(payload);
    const phase = hasToolResult(payload, call.callId) ? "final" : "tool";
    state.observations.push({
      api: api.api,
      scenario,
      phase,
      toolCatalog,
      toolCall: {
        callId: call.callId,
        name: call.name,
        ...(scenario === "start" && call.name === "exec_command" ? {
          command: call.arguments.cmd,
          tty: call.arguments.tty === true,
        } : {}),
        ...(call.name === "write_stdin" ? {
          sessionId: call.arguments.session_id,
          inputEscaped: JSON.stringify(call.arguments.input),
          closeInput: call.arguments.close_input === true,
          yieldTimeMs: call.arguments.yield_time,
        } : {}),
      },
    });
    const hasResult = hasToolResult(payload, call.callId);
    if (scenario === "cancel" && hasResult) {
      state.cancelContinuation.resolve();
      await state.releaseCancelContinuation.promise;
      if (response.destroyed) return;
    }
    const stream = hasResult
      ? providerStream(api.api, `process_${api.id}_${scenario}_final`, undefined, `PROCESS_${scenario.toUpperCase()}_FINAL_OK`)
      : providerStream(api.api, `process_${api.id}_${scenario}_tool`, call, "");
    response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
    response.end(stream);
  } catch (error) {
    response.writeHead(400, { "content-type": "application/json" });
    response.end(JSON.stringify({ error: String(error?.message ?? error).slice(0, 200) }));
  }
}

/** 仅绑定 IPv4 loopback，防止验收 Provider 暴露到外部网络。 */
async function startProviderFixture({ cancelMarker, restartMarker }) {
  const cancelContinuation = createDeferred();
  const releaseCancelContinuation = createDeferred();
  const state = {
    observations: [],
    sessionId: undefined,
    cancelMarker,
    restartMarker,
    cancelContinuation,
    releaseCancelContinuation,
  };
  const server = createServer((request, response) => {
    void handleProviderRequest(request, response, state);
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("fixture port unavailable");
  return {
    endpoint: `http://127.0.0.1:${address.port}/v1`,
    observations: state.observations,
    /** 等待 Tool 已提交且模型续答请求已到达，确保取消发生在后台进程仍运行时。 */
    async waitForCancelContinuation() {
      let timer;
      try {
        await Promise.race([
          state.cancelContinuation.promise,
          new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error("cancel continuation did not reach loopback Provider")), 15_000);
          }),
        ]);
      } finally {
        clearTimeout(timer);
      }
    },
    /** 释放受控 Provider 请求，使取消后的 HTTP 资源可以收敛。 */
    releaseCancelContinuation() {
      state.releaseCancelContinuation.resolve();
    },
    /** 将 JA-RPC 查询到的会话身份交给后续模型 Turn，避免 fixture 猜测会话 ID。 */
    setSessionId(sessionId) {
      state.sessionId = sessionId;
    },
    /** 所有请求结束后关闭唯一 listener，避免验收遗留端口。 */
    async close() {
      server.closeAllConnections?.();
      await new Promise((resolvePromise, rejectPromise) => {
        server.close((error) => (error ? rejectPromise(error) : resolvePromise()));
      });
    },
  };
}

/** 要求 full_access Turn 提交成功 Tool 结果并完成续答；回调只接收不含正文的定位证据。 */
async function runToolTurn(session, threadId, marker, expectedCallId, expectedToolName, onEvidence = undefined) {
  const accepted = rpcResult(await session.request("turn/start", {
    threadId,
    operationKey: createOperationKey(),
    content: [{ type: "text", text: `Complete the requested process action. ${marker}` }],
  }), `turn/start ${expectedToolName}`);
  assert.ok(typeof accepted.turnId === "string" && accepted.turnId.startsWith("turn_"));
  onEvidence?.({ stage: "accepted", turnId: accepted.turnId });
  const terminal = await session.waitForEvent(
    "turn/terminal",
    (frame) => frame.params?.turnId === accepted.turnId,
    requestTimeoutMs,
  );
  const finalMessage = terminal.params?.finalMessage;
  const finalText = committedTerminalReply(terminal, accepted.turnId);
  onEvidence?.({
    stage: "terminal",
    turnId: accepted.turnId,
    state: terminal.params?.state ?? "unknown",
    messageId: typeof finalMessage?.messageId === "string" ? finalMessage.messageId : null,
    textLength: typeof finalMessage?.text === "string" ? finalMessage.text.length : null,
    finalOk: finalText?.includes("FINAL_OK") === true,
  });
  assert.equal(terminal.params?.state, "completed", `${expectedToolName} must complete without approval`);
  assert.ok(finalText?.includes("FINAL_OK"));

  const evidence = collectToolEvidence(session.events, accepted.turnId);
  try {
    assert.deepEqual(evidence.map(({ callId, toolName, outcome }) => ({ callId, toolName, outcome })), [
      { callId: expectedCallId, toolName: expectedToolName, outcome: "succeeded" },
    ]);
  } catch (error) {
    error.toolResultDiagnostics = collectToolResultDiagnostics(session.events, accepted.turnId);
    throw error;
  }
  assert.equal(
    session.events.some((frame) => frame?.method === "approval/requested"
      && frame.params?.turnId === accepted.turnId),
    false,
    `${expectedToolName} in full_access must not request an approval wait`,
  );
  return { turnId: accepted.turnId, finalText: terminal.params.finalMessage.text };
}

/** 通过 JA-RPC 有界轮询持久快照，并把最后一次观测交给诊断记录而不保留无限事件流。 */
async function waitForSnapshot(session, scope, sessionId, predicate, label, onRead = undefined) {
  const deadline = Date.now() + 15_000;
  let snapshot;
  let pollCount = 0;
  while (Date.now() < deadline) {
    pollCount += 1;
    snapshot = rpcResult(await session.request("process/read", {
      ...scope,
      sessionId,
      afterSequence: 0,
      maxBytes: 65_536,
    }), `process/read ${label}`);
    onRead?.(snapshot, pollCount);
    if (predicate(snapshot)) return snapshot;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 75));
  }
  const error = new Error(`timed out waiting for process ${label}; state=${snapshot?.state ?? "unknown"}`);
  error.lastSnapshot = snapshot === undefined ? undefined : processSnapshotEvidence(snapshot);
  error.pollCount = pollCount;
  throw error;
}

/** 按完整 marker 计数，确保重放断言不会被子串匹配误报。 */
function occurrences(value, marker) {
  return String(value).split(marker).length - 1;
}

/** 验证临时目录归属后才递归清理本 runner 创建的隔离 profile。 */
async function cleanupProfile(root) {
  const physicalTemp = resolve(await realpath(tmpdir()));
  const target = resolve(root);
  if (dirname(target) !== physicalTemp || !basename(target).startsWith("ja-real-provider-")) {
    throw new Error("refusing to clean a profile not owned by this acceptance runner");
  }
  await rm(target, { recursive: true, force: false });
}

/** 等待固定 fixture PID 文件落盘；标记与角色不匹配时立即拒绝把任意进程当作测试资源。 */
async function waitForFixturePid(filePath, marker, label) {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    let value;
    try {
      value = (await readFile(filePath, "utf8")).replace(/^\uFEFF/u, "").trim();
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    if (value !== undefined) {
      const prefix = `${marker}|`;
      if (!value.startsWith(prefix)) throw new Error(`${label} PID marker does not match this fixture`);
      const pid = Number(value.slice(prefix.length));
      if (!Number.isSafeInteger(pid) || pid < 1) throw new Error(`${label} PID is invalid`);
      return pid;
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 50));
  }
  throw new Error(`${label} PID marker was not written`);
}

/** 通过固定 PID 查询系统进程命令行，只回传当前进程身份验证所需内容。 */
async function fixtureCommandLine(pid) {
  const command = `$fixture = Get-CimInstance -ClassName Win32_Process -Filter 'ProcessId = ${pid}'; `
    + "if ($null -ne $fixture -and $null -ne $fixture.CommandLine) { "
    + "[Console]::Out.WriteLine($fixture.CommandLine) }";
  const result = await execFileAsync("powershell.exe", [
    "-NoProfile",
    "-NonInteractive",
    "-Command",
    command,
  ], { windowsHide: true, maxBuffer: 16 * 1024, timeout: 5_000 });
  return result.stdout.trim();
}

/** 确认 PID 仍对应带有本轮随机 marker 的精确进程，避免把 PID 重用误判为验收成功。 */
async function assertFixtureProcessAlive(pid, marker, label) {
  const commandLine = await fixtureCommandLine(pid);
  assert.ok(commandLine.length > 0, `${label} process exited before its lifecycle assertion`);
  assert.ok(commandLine.includes(marker), `${label} PID no longer identifies this fixture`);
}

/** 轮询指定 fixture PID 的退出；若操作系统已重用 PID，则立即失败而不触碰新进程。 */
async function waitForFixtureProcessExit(pid, marker, label) {
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    const commandLine = await fixtureCommandLine(pid);
    if (commandLine.length === 0) return;
    assert.ok(commandLine.includes(marker), `${label} PID changed ownership during cleanup`);
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} fixture process remained alive after its owner closed`);
}

/** 仅在 marker 文件和真实命令行同时证明归属时停止固定 fixture PID，绝不按进程名清理。 */
async function stopOwnedFixtureProcess(pid, marker, label) {
  const commandLine = await fixtureCommandLine(pid);
  if (commandLine.length === 0) return;
  if (!commandLine.includes(marker)) return;
  const expectedMarker = marker.replaceAll("'", "''");
  const command = `$fixture = Get-CimInstance -ClassName Win32_Process -Filter 'ProcessId = ${pid}'; `
    + `if ($null -eq $fixture) { exit 0 }; `
    + `if ($null -eq $fixture.CommandLine -or -not $fixture.CommandLine.Contains('${expectedMarker}')) { exit 23 }; `
    + `Stop-Process -Id ${pid} -Force`;
  try {
    await execFileAsync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", command], {
      windowsHide: true,
      maxBuffer: 16 * 1024,
      timeout: 5_000,
    });
  } catch (error) {
    if (error?.code === 23) throw new Error(`refusing to stop an unrelated ${label} PID`);
    throw error;
  }
  await waitForFixtureProcessExit(pid, marker, label);
}

/** 按协议创建独立 Provider、Model 与凭据引用，避免多个 Provider 隐式共享 API Key。 */
export function multiProviderConfiguration(endpoint) {
  const documents = apis.map((entry) => providerConfigurationDocument({
    endpoint,
    name: "Process session " + entry.id + " loopback fixture",
    api: entry.api,
    model: "process-session-" + entry.id,
    selectedProviderId: entry.providerId,
    selectedModelId: entry.modelId,
    selectedCredentialId: entry.credentialId,
    reasoningLevel: "off",
  }));
  const document = structuredClone(documents[0]);
  document.providers = documents.flatMap((providerDocument) => providerDocument.providers);
  document.default_access_mode = "full_access";
  document.default_provider_id = apis[0].providerId;
  document.default_model_id = apis[0].modelId;
  for (const provider of document.providers) {
    provider.network_timeouts.request_timeout_ms = requestTimeoutMs;
  }
  return document;
}

/** 验证 Windows 进程会话闭环；同一冻结启动规格贯穿首次启动与重启，并保留脱敏失败证据。 */
export async function runAcceptance() {
  if (process.platform !== "win32") throw new Error("process-session acceptance requires Windows");
  const launchSpec = await resolveLaunchSpec();
  const javaMajor = launchSpec.kind === "jvm" ? await assertJava25(launchSpec.command) : null;
  const artifact = { kind: launchSpec.kind, name: basename(launchSpec.artifactPath) };
  const evidencePath = process.env.JA_PROCESS_SESSION_EVIDENCE_PATH?.trim()
    ? resolve(process.env.JA_PROCESS_SESSION_EVIDENCE_PATH)
    : undefined;
  const runEvidence = {
    contractVersion: 1,
    status: "running",
    javaMajor,
    artifact,
    jar: launchSpec.kind === "jvm" ? basename(launchSpec.artifactPath) : null,
    protocols: [],
  };
  const directories = await createIsolatedDirectories();
  const envMarker = randomBytes(12).toString("hex");
  const restartMarker = randomBytes(12).toString("hex");
  const cancelMarker = randomBytes(12).toString("hex");
  const restartPidFile = join(directories.root, "restart-process.pid");
  const cancelRootPidFile = join(directories.root, "cancel-root.pid");
  const cancelChildPidFile = join(directories.root, "cancel-child.pid");
  const cancelChildScript = `[IO.File]::WriteAllText($env:${cancelChildPidFileVariable}, `
    + `"${cancelMarker}|$PID"); Start-Sleep -Seconds 120 # JA_PROCESS_CANCEL_CHILD:${cancelMarker}`;
  const cancelChildCode = Buffer.from(cancelChildScript, "utf16le").toString("base64");
  const scopedEnvironment = [
    markerVariable,
    restartMarkerVariable,
    restartPidFileVariable,
    cancelMarkerVariable,
    cancelRootPidFileVariable,
    cancelChildPidFileVariable,
    cancelChildCodeVariable,
  ];
  const priorEnvironment = new Map(scopedEnvironment.map((name) => [name, process.env[name]]));
  process.env[markerVariable] = envMarker;
  process.env[restartMarkerVariable] = restartMarker;
  process.env[restartPidFileVariable] = restartPidFile;
  process.env[cancelMarkerVariable] = cancelMarker;
  process.env[cancelRootPidFileVariable] = cancelRootPidFile;
  process.env[cancelChildPidFileVariable] = cancelChildPidFile;
  process.env[cancelChildCodeVariable] = cancelChildCode;
  let fixture;
  let session;
  const ownedProcesses = [];
  const fixturePids = [];
  let report;
  let cleanupFailure;
  let runFailure;
  let activeProtocol;
  try {
    await mkdir(join(directories.workspace, "src"));
    fixture = await startProviderFixture({ cancelMarker, restartMarker });
    session = new JsonlSession({
      command: launchSpec.command,
      prefixArgs: [...launchSpec.prefixArgs],
      directories,
      apiKey: placeholderSecret,
      endpoint: fixture.endpoint,
    });

    await initializeSidecar(session, 1, "initial");

    const configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read",
    );
    const document = multiProviderConfiguration(fixture.endpoint);
    rpcResult(await session.request("configuration/replace", {
      scope: "user",
      expectedVersion: configuration.cas.userVersion,
      document,
    }), "configuration/replace");
    let credentialVersion = configuration.cas.credentialVersion;
    for (const entry of apis) {
      const credential = rpcResult(await session.request("credential/set", {
        credentialId: entry.credentialId,
        secret: placeholderSecret,
        expectedVersion: credentialVersion,
      }), "credential/set");
      assert.equal(credential.credentialId, entry.credentialId);
      assert.equal(credential.configured, true);
      credentialVersion = credential.version;
    }

    const workspace = rpcResult(await session.request("workspace/open", {
      cwd: directories.workspace,
      displayName: "Process session full-access acceptance",
    }), "workspace/open");
    const protocolReports = {};
    const protocolThreads = new Map();
    for (const api of apis) {
      const thread = rpcResult(await session.request("thread/create", {
        cwd: directories.workspace,
        title: "Process session " + api.id,
        providerId: api.providerId,
        modelId: api.modelId,
        reasoningLevel: "off",
        accessMode: "full_access",
        collaborationMode: "default",
      }), "thread/create " + api.id);
      assert.equal(thread.workspaceId, workspace.workspaceId);
      assert.ok(typeof thread.threadId === "string" && thread.threadId.startsWith("thr_"));
      const foreignThread = rpcResult(await session.request("thread/create", {
        cwd: directories.workspace,
        title: "Process session foreign owner " + api.id,
        providerId: api.providerId,
        modelId: api.modelId,
        reasoningLevel: "off",
        accessMode: "full_access",
        collaborationMode: "default",
      }), "thread/create foreign owner " + api.id);
      assert.equal(foreignThread.workspaceId, workspace.workspaceId);
      assert.notEqual(foreignThread.threadId, thread.threadId);
      protocolThreads.set(api.id, thread);

      const startCallId = processCallId(api, "start");
      const firstWriteCallId = processCallId(api, "write_one");
      const lastWriteCallId = processCallId(api, "write_three");
      const processEvidence = {
        api: api.api,
        threadId: thread.threadId,
        startTurnId: null,
        startTurnEvidence: null,
        sessionId: null,
        beforeFirstWrite: null,
        firstWriteToolTurn: null,
        firstWriteWait: null,
        directWrite: null,
        idempotentWriteWait: null,
        finalWriteToolTurn: null,
        finalWriteWait: null,
      };
      runEvidence.protocols.push(processEvidence);
      activeProtocol = processEvidence;
      activeProtocol.activeStage = "exec_command first tool turn";
      const startTurn = await runToolTurn(
        session,
        thread.threadId,
        startMarker,
        startCallId,
        "exec_command",
        (evidence) => { processEvidence.startTurnEvidence = evidence; },
      );
      processEvidence.startTurnId = startTurn.turnId;
      const processPage = rpcResult(await session.request("process/list", {
        workspaceId: workspace.workspaceId,
        threadId: thread.threadId,
        limit: 20,
        cursor: null,
        startedCallId: startCallId,
      }), "process/list after exec_command " + api.id);
      assert.equal(processPage.sessions.length, 1, "exec_command must leave one owned live session");
      const processSummary = processPage.sessions[0];
      assert.equal(processSummary.state, "running");
      assert.equal(processSummary.startedTurnId, startTurn.turnId);
      assert.equal(processSummary.startedCallId, startCallId);
      const processSessionId = processSummary.sessionId;
      processEvidence.sessionId = processSessionId;
      const processScope = {
        workspaceId: workspace.workspaceId,
        threadId: thread.threadId,
        generation: processPage.generation,
      };
      ownedProcesses.push({ ...processScope, sessionId: processSessionId });
      fixture.setSessionId(processSessionId);

      activeProtocol.activeStage = "process/read before first stdin write";
      processEvidence.beforeFirstWrite = processSnapshotEvidence(rpcResult(
        await session.request("process/read", {
          ...processScope,
          sessionId: processSessionId,
          afterSequence: 0,
          maxBytes: 65_536,
        }),
        "process/read before first tool write " + api.id,
      ));

      activeProtocol.activeStage = "write_stdin first tool turn";
      const firstWriteTurn = await runToolTurn(
        session,
        thread.threadId,
        firstWriteMarker,
        firstWriteCallId,
        "write_stdin",
      );
      processEvidence.firstWriteToolTurn = {
        turnId: firstWriteTurn.turnId,
        state: "completed",
        finalText: firstWriteTurn.finalText,
        tools: collectToolEvidence(session.events, firstWriteTurn.turnId),
      };
      activeProtocol.activeStage = "process/read after first tool write";
      const firstLine = await waitForSnapshot(
        session,
        processScope,
        processSessionId,
        (snapshot) => snapshot.output.includes("JA_PROCESS_LINE_1:PROCESS_LINE_ONE"),
        api.id + " first tool write",
        (snapshot, pollCount) => {
          processEvidence.firstWriteWait = {
            pollCount,
            lastRead: processSnapshotEvidence(snapshot),
          };
        },
      );
      processEvidence.firstWrite = processSnapshotEvidence(firstLine);
      assert.ok(firstLine.output.includes(`JA_PROCESS_HOST_ENV:${envMarker}`));
      assert.notEqual(firstWriteTurn.turnId, startTurn.turnId);

      const foreignRead = await session.request("process/read", {
        ...processScope,
        threadId: foreignThread.threadId,
        sessionId: processSessionId,
        afterSequence: 0,
        maxBytes: 65_536,
      });
      assert.equal(requireRpcErrorCode(foreignRead.error), "THREAD_NOT_FOUND");
      const wrongThreadWrite = await session.request("process/write", {
        ...processScope,
        threadId: foreignThread.threadId,
        sessionId: processSessionId,
        afterSequence: 0,
        input: "UNAUTHORIZED_PROCESS_LINE\n",
        closeInput: false,
        operationKey: createOperationKey(),
      });
      assert.equal(requireRpcErrorCode(wrongThreadWrite.error), "THREAD_NOT_FOUND");

      const operationKey = createOperationKey();
      const secondWriteParams = {
        ...processScope,
        sessionId: processSessionId,
        afterSequence: firstLine.nextSequence,
        input: api.id === "responses" ? "PROCESS_LINE_TWO\r" : "PROCESS_LINE_TWO\r\n",
        closeInput: false,
        operationKey,
      };
      activeProtocol.activeStage = "process/write first delivery and idempotent replay";
      const firstWriteReceipt = rpcResult(
        await session.request("process/write", secondWriteParams),
        "process/write first delivery",
      );
      const replayWriteReceipt = rpcResult(
        await session.request("process/write", secondWriteParams),
        "process/write idempotent replay",
      );
      processEvidence.directWrite = {
        request: {
          afterSequence: secondWriteParams.afterSequence,
          inputEscaped: JSON.stringify(secondWriteParams.input),
          closeInput: secondWriteParams.closeInput,
          operationKey: secondWriteParams.operationKey,
        },
        firstDelivery: processSnapshotEvidence(firstWriteReceipt),
        idempotentReplay: processSnapshotEvidence(replayWriteReceipt),
      };
      activeProtocol.activeStage = "process/read after idempotent process/write";
      const secondLine = await waitForSnapshot(
        session,
        processScope,
        processSessionId,
        (snapshot) => snapshot.output.includes("JA_PROCESS_LINE_2:PROCESS_LINE_TWO"),
        api.id + " idempotent process write",
        (snapshot, pollCount) => {
          processEvidence.idempotentWriteWait = {
            pollCount,
            lastRead: processSnapshotEvidence(snapshot),
          };
        },
      );
      processEvidence.afterIdempotentWrite = processSnapshotEvidence(secondLine);
      assert.equal(occurrences(secondLine.output, "JA_PROCESS_LINE_2:PROCESS_LINE_TWO"), 1);
      assert.equal(occurrences(secondLine.output, "UNAUTHORIZED_PROCESS_LINE"), 0);
      assert.equal(secondLine.state, "running");

      activeProtocol.activeStage = "write_stdin final tool turn";
      const lastWriteTurn = await runToolTurn(
        session,
        thread.threadId,
        lastWriteMarker,
        lastWriteCallId,
        "write_stdin",
      );
      processEvidence.finalWriteToolTurn = {
        turnId: lastWriteTurn.turnId,
        state: "completed",
        finalText: lastWriteTurn.finalText,
        tools: collectToolEvidence(session.events, lastWriteTurn.turnId),
      };
      assert.notEqual(lastWriteTurn.turnId, firstWriteTurn.turnId);
      activeProtocol.activeStage = "process/read after final tool write";
      const completed = await waitForSnapshot(
        session,
        processScope,
        processSessionId,
        (snapshot) => snapshot.state === "exited"
          && snapshot.output.includes("JA_PROCESS_LINE_3:PROCESS_LINE_THREE"),
        api.id + " final cross-turn tool write",
        (snapshot, pollCount) => {
          processEvidence.finalWriteWait = {
            pollCount,
            lastRead: processSnapshotEvidence(snapshot),
          };
        },
      );
      processEvidence.afterFinalWrite = processSnapshotEvidence(completed);
      assert.equal(completed.exitCode, 0);
      for (const [index, line] of ["ONE", "TWO", "THREE"].entries()) {
        assert.equal(occurrences(completed.output, `JA_PROCESS_LINE_${index + 1}:PROCESS_LINE_${line}`), 1);
      }

      const toolDefinitions = fixture.observations.find((item) =>
        item.api === api.api && item.scenario === "start")?.toolCatalog ?? [];
      for (const [name, expectedProperties] of [
        ["exec_command", ["cmd", "timeout_ms", "tty", "workdir", "yield_time_ms"]],
        ["write_stdin", ["after_sequence", "close_input", "input", "session_id", "yield_time_ms"]],
      ]) {
        const tool = toolDefinitions.find((definition) => definition.name === name);
        assert.ok(tool, api.api + " Provider tool catalog is missing " + name);
        assert.deepEqual(tool.properties, expectedProperties);
      }
      assert.equal(fixture.observations.filter((item) => item.api === api.api && item.phase === "tool").length, 3,
        api.api + " must request all three process actions");
      assert.equal(fixture.observations.filter((item) => item.api === api.api && item.phase === "final").length, 3,
        api.api + " must complete each result continuation");
      protocolReports[api.id] = {
        turns: 3,
        processExitCode: completed.exitCode,
        outputLinesVerified: 3,
      };
    }

    activeProtocol = { activeStage: "turn/start cancellable process" };
    const lifecycleThread = protocolThreads.get(apis[0].id);
    const cancelCallId = processCallId(apis[0], "cancel");
    const cancelAccepted = rpcResult(await session.request("turn/start", {
      threadId: lifecycleThread.threadId,
      operationKey: createOperationKey(),
      content: [{ type: "text", text: `Start a cancellable child process. ${cancelTurnMarker}` }],
    }), "turn/start cancellable process");
    activeProtocol.activeStage = "wait for cancel Provider continuation";
    await fixture.waitForCancelContinuation();
    const cancelEvidence = collectToolEvidence(session.events, cancelAccepted.turnId);
    assert.deepEqual(cancelEvidence.map(({ callId: id, toolName, outcome }) => ({ callId: id, toolName, outcome })), [
      { callId: cancelCallId, toolName: "exec_command", outcome: "succeeded" },
    ]);

    activeProtocol.activeStage = "process/list cancellable process";
    const cancelPage = rpcResult(await session.request("process/list", {
      workspaceId: workspace.workspaceId,
      threadId: lifecycleThread.threadId,
      limit: 20,
      cursor: null,
      startedCallId: cancelCallId,
    }), "process/list cancellable process");
    assert.equal(cancelPage.sessions.length, 1);
    const cancelProcess = cancelPage.sessions[0];
    assert.equal(cancelProcess.state, "running");
    assert.equal(cancelProcess.startedTurnId, cancelAccepted.turnId);
    const cancelScope = {
      workspaceId: workspace.workspaceId,
      threadId: lifecycleThread.threadId,
      generation: cancelPage.generation,
    };
    ownedProcesses.push({ ...cancelScope, sessionId: cancelProcess.sessionId });
    activeProtocol.activeStage = "wait for cancel root PID marker";
    const cancelRootPid = await waitForFixturePid(cancelRootPidFile, cancelMarker, "cancel root");
    const cancelRootToken = `JA_PROCESS_CANCEL_ROOT:${cancelMarker}`;
    fixturePids.push({ pid: cancelRootPid, marker: cancelRootToken, label: "cancel root" });
    activeProtocol.activeStage = "wait for cancel child PID marker";
    const cancelChildPid = await waitForFixturePid(cancelChildPidFile, cancelMarker, "cancel child");
    fixturePids.push({ pid: cancelChildPid, marker: cancelChildCode, label: "cancel child" });
    const expectedCancelOutput = `${cancelRootToken}:${cancelRootPid}:CHILD:${cancelChildPid}`;
    activeProtocol.activeStage = "process/read cancellable root and child output";
    let cancelStartupWait;
    const cancelStartupSnapshot = await waitForSnapshot(
      session,
      cancelScope,
      cancelProcess.sessionId,
      (snapshot) => snapshot.output.includes(expectedCancelOutput),
      "cancellable root and child PID output",
      (snapshot, pollCount) => {
        cancelStartupWait = { pollCount, lastRead: processSnapshotEvidence(snapshot) };
      },
    );
    runEvidence.lifecycle = {
      cancelStartupWait,
      cancelStartup: processSnapshotEvidence(cancelStartupSnapshot),
    };
    await assertFixtureProcessAlive(cancelRootPid, cancelRootToken, "cancel root");
    await assertFixtureProcessAlive(cancelChildPid, cancelChildCode, "cancel child");
    activeProtocol.activeStage = "turn/cancel process";
    rpcResult(await session.request("turn/cancel", { turnId: cancelAccepted.turnId }), "turn/cancel process");
    fixture.releaseCancelContinuation();
    const cancelledTurn = await session.waitForEvent(
      "turn/terminal",
      (frame) => frame.params?.turnId === cancelAccepted.turnId,
      requestTimeoutMs,
    );
    assert.equal(cancelledTurn.params?.state, "cancelled");
    const cancelledProcess = await waitForSnapshot(
      session,
      cancelScope,
      cancelProcess.sessionId,
      (snapshot) => snapshot.state === "exited",
      "cancelled root and child process",
    );
    assert.ok(cancelledProcess.output.includes(cancelRootToken));
    await waitForFixtureProcessExit(cancelRootPid, cancelRootToken, "cancel root");
    await waitForFixtureProcessExit(cancelChildPid, cancelChildCode, "cancel child");

    const restartCallId = processCallId(apis[0], "restart");
    const restartTurn = await runToolTurn(
      session,
      lifecycleThread.threadId,
      restartTurnMarker,
      restartCallId,
      "exec_command",
    );
    const restartPage = rpcResult(await session.request("process/list", {
      workspaceId: workspace.workspaceId,
      threadId: lifecycleThread.threadId,
      limit: 20,
      cursor: null,
      startedCallId: restartCallId,
    }), "process/list before runtime restart");
    assert.equal(restartPage.sessions.length, 1);
    const restartProcess = restartPage.sessions[0];
    assert.equal(restartProcess.state, "running");
    assert.equal(restartProcess.startedTurnId, restartTurn.turnId);
    const restartScope = {
      workspaceId: workspace.workspaceId,
      threadId: lifecycleThread.threadId,
      generation: restartPage.generation,
    };
    ownedProcesses.push({ ...restartScope, sessionId: restartProcess.sessionId });
    const restartSnapshot = await waitForSnapshot(
      session,
      restartScope,
      restartProcess.sessionId,
      (snapshot) => snapshot.output.includes(`JA_PROCESS_RESTART_ROOT:${restartMarker}:`),
      "pre-restart process marker",
    );
    const restartPid = await waitForFixturePid(restartPidFile, restartMarker, "restart root");
    const restartRootToken = `JA_PROCESS_RESTART_ROOT:${restartMarker}`;
    assert.ok(restartSnapshot.output.includes(`${restartRootToken}:${restartPid}`));
    fixturePids.push({ pid: restartPid, marker: restartRootToken, label: "restart root" });
    await assertFixtureProcessAlive(restartPid, restartRootToken, "restart root");
    assert.equal(restartPage.generation, 1, "first sidecar must use generation one");

    const crashedSession = session;
    await crashedSession.forceClose();
    session = undefined;
    await waitForFixtureProcessExit(restartPid, restartRootToken, "restart root");
    const nextGeneration = restartPage.generation + 1;
    session = new JsonlSession({
      command: launchSpec.command,
      prefixArgs: [...launchSpec.prefixArgs],
      directories,
      apiKey: placeholderSecret,
      endpoint: fixture.endpoint,
      runtimeGeneration: nextGeneration,
    });
    await initializeSidecar(session, nextGeneration, "after abrupt restart");

    const recoveredPage = rpcResult(await session.request("process/list", {
      workspaceId: workspace.workspaceId,
      threadId: lifecycleThread.threadId,
      limit: 20,
      cursor: null,
      startedCallId: restartCallId,
    }), "process/list after runtime restart");
    assert.equal(recoveredPage.generation, nextGeneration);
    assert.equal(recoveredPage.sessions.length, 1);
    const recoveredSession = recoveredPage.sessions[0];
    assert.equal(recoveredSession.sessionId, restartProcess.sessionId);
    assert.equal(recoveredSession.generation, restartPage.generation);
    assert.equal(recoveredSession.state, "interrupted");
    const recoveredScope = {
      workspaceId: workspace.workspaceId,
      threadId: lifecycleThread.threadId,
      generation: nextGeneration,
    };
    const recoveredHistory = rpcResult(await session.request("process/read", {
      ...recoveredScope,
      sessionId: restartProcess.sessionId,
      afterSequence: 0,
      maxBytes: 65_536,
    }), "process/read interrupted historical session");
    assert.equal(recoveredHistory.state, "interrupted");
    assert.equal(recoveredHistory.generation, restartPage.generation);
    assert.ok(recoveredHistory.output.includes(`${restartRootToken}:${restartPid}`));

    const staleResourceWrite = await session.request("process/write", {
      ...recoveredScope,
      sessionId: restartProcess.sessionId,
      afterSequence: recoveredHistory.outputSequence,
      input: "UNAUTHORIZED_WRITE_AFTER_RESTART\n",
      closeInput: false,
      operationKey: createOperationKey(),
    });
    assert.equal(requireRpcErrorCode(staleResourceWrite.error), "THREAD_NOT_FOUND");
    const staleCallerWrite = await session.request("process/write", {
      ...recoveredScope,
      generation: restartPage.generation,
      sessionId: restartProcess.sessionId,
      afterSequence: recoveredHistory.outputSequence,
      input: "UNAUTHORIZED_OLD_GENERATION_WRITE\n",
      closeInput: false,
      operationKey: createOperationKey(),
    });
    assert.equal(requireRpcErrorCode(staleCallerWrite.error), "THREAD_NOT_FOUND");
    const finalHistory = rpcResult(await session.request("process/read", {
      ...recoveredScope,
      sessionId: restartProcess.sessionId,
      afterSequence: 0,
      maxBytes: 65_536,
    }), "process/read after rejected stale writes");
    assert.equal(finalHistory.state, "interrupted");
    assert.equal(finalHistory.output.includes("UNAUTHORIZED_WRITE_AFTER_RESTART"), false);
    assert.equal(finalHistory.output.includes("UNAUTHORIZED_OLD_GENERATION_WRITE"), false);

    report = {
      status: "passed",
      javaMajor,
      artifact,
      jar: launchSpec.kind === "jvm" ? basename(launchSpec.artifactPath) : null,
      protocols: protocolReports,
      ptyStdinOutput: "openai_responses",
      crossThreadReadWriteRejected: true,
      turnCancellation: "root and child fixture PIDs exited",
      sqliteRestart: "generation one running session recovered as interrupted and remained readable but unwritable",
      fullAccessApprovalRequests: 0,
      processScope: "workspace+thread+generation",
      processWriteIdempotency: "same operation key delivered one input",
      shellHostEnvironmentInherited: true,
    };
  } catch (error) {
    runFailure = error;
    runEvidence.status = "failed";
    runEvidence.failure = {
      stage: activeProtocol?.activeStage ?? "setup",
      message: String(error?.message ?? error).slice(0, 1_000),
      ...(Array.isArray(error?.toolResultDiagnostics) && error.toolResultDiagnostics.length > 0
        ? { toolResultDiagnostics: error.toolResultDiagnostics } : {}),
      ...(Number.isSafeInteger(error?.pollCount) ? { pollCount: error.pollCount } : {}),
      ...(error?.lastSnapshot !== undefined ? { lastSnapshot: error.lastSnapshot } : {}),
    };
  } finally {
    fixture?.releaseCancelContinuation();
    if (session !== undefined) {
      for (const ownedProcess of ownedProcesses) {
        await session.request("process/stop", ownedProcess).catch(() => undefined);
      }
    }
    if (session !== undefined) {
      await session.shutdown().catch(() => undefined);
      await session.forceClose();
    }
    const cleanupErrors = [];
    for (const { pid, marker, label } of fixturePids) {
      try {
        await stopOwnedFixtureProcess(pid, marker, label);
      } catch (error) {
        cleanupErrors.push(`${label}: ${error?.message ?? error}`);
      }
    }
    await fixture?.close();
    for (const [name, value] of priorEnvironment) {
      if (value === undefined) delete process.env[name];
      else process.env[name] = value;
    }
    if (launchSpec.kind === "native" && runFailure !== undefined) {
      runEvidence.failureProfile = directories.root;
    } else {
      await cleanupProfile(directories.root);
    }
    if (cleanupErrors.length > 0) {
      cleanupFailure = `fixture process cleanup failed: ${cleanupErrors.join("; ")}`;
    }
  }
  if (cleanupFailure !== undefined) {
    runEvidence.cleanupFailed = true;
    if (runFailure === undefined) runFailure = new Error("fixture process cleanup failed");
  }
  if (runFailure === undefined) {
    runEvidence.status = "passed";
    runEvidence.summary = report;
  }
  if (fixture !== undefined) {
    runEvidence.providerRequests = fixture.observations
      .map(({ api, scenario, phase }) => ({ api, scenario, phase }));
    runEvidence.providerToolCalls = fixture.observations
      .filter((observation) => ["exec_command", "write_stdin"].includes(observation.toolCall?.name))
      .map((observation) => ({
        api: observation.api,
        scenario: observation.scenario,
        phase: observation.phase,
        ...observation.toolCall,
      }));
  }
  if (evidencePath !== undefined) {
    await mkdir(dirname(evidencePath), { recursive: true });
    await writeFile(evidencePath, `${JSON.stringify(runEvidence, null, 2)}\n`, "utf8");
  }
  if (runFailure !== undefined) throw runFailure;
  process.stdout.write(`${JSON.stringify(report)}\n`);
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await runAcceptance().catch((error) => {
    process.stderr.write(`process-session-full-access failed: ${error?.message ?? error}\n`);
    process.exitCode = 1;
  });
}
