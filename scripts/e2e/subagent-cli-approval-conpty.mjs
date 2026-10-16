// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { createConnection } from "node:net";
import { copyFile, mkdir, mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { basename, delimiter, dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { execFile, spawn } from "node:child_process";
import { promisify } from "node:util";
import {
  compileConPtyHarness,
  removeOwnedProfile,
  startConPtyCli,
  writeScreenEvidence,
} from "./cli-usage-conpty.mjs";
import {
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
} from "./real-provider-smoke.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const execFileAsync = promisify(execFile);
const placeholderSecret = "ja-subagent-cli-approval-loopback-placeholder";
const providerId = "provider_subagent_cli_approval";
const modelId = "model_subagent_cli_approval";
const credentialId = "cred_subagent_cli_approval";
const nativeSidecarName = "ja-app-server-x86_64-pc-windows-msvc.exe";
const childScenarios = [
  {
    taskName: "cli-approval-allow-child",
    parentMarker: "CLI_APPROVAL_PARENT_ALLOW",
    childMarker: "CLI_APPROVAL_CHILD_ALLOW",
    spawnCallId: "call_cli_approval_spawn_allow",
    execCallId: "call_cli_approval_exec_allow",
    command: "Write-Output CLI_APPROVAL_COMMAND_ALLOWED",
    childFinal: "CLI_APPROVAL_CHILD_ALLOW_FINAL",
    parentFinal: "CLI_APPROVAL_PARENT_ALLOW_FINAL",
    decision: "approve",
    commandOutput: "CLI_APPROVAL_COMMAND_ALLOWED",
  },
  {
    taskName: "cli-approval-reject-child",
    parentMarker: "CLI_APPROVAL_PARENT_REJECT",
    childMarker: "CLI_APPROVAL_CHILD_REJECT",
    spawnCallId: "call_cli_approval_spawn_reject",
    execCallId: "call_cli_approval_exec_reject",
    command: "Write-Output CLI_APPROVAL_COMMAND_REJECTED_MUST_NOT_RUN",
    childFinal: "CLI_APPROVAL_CHILD_REJECT_FINAL",
    parentFinal: "CLI_APPROVAL_PARENT_REJECT_FINAL",
    decision: "deny",
    commandOutput: "CLI_APPROVAL_COMMAND_REJECTED_MUST_NOT_RUN",
  },
];
const expiryScenario = {
  taskName: "cli-approval-expiry-child",
  parentMarker: "CLI_APPROVAL_PARENT_EXPIRY",
  childMarker: "CLI_APPROVAL_CHILD_EXPIRY",
  spawnCallId: "call_cli_approval_spawn_expiry",
  execCallId: "call_cli_approval_expired_exec",
  resumedExecCallId: "call_cli_approval_resumed_exec",
  childFinal: "CLI_APPROVAL_CHILD_EXPIRY_FINAL",
  parentFinal: "CLI_APPROVAL_PARENT_EXPIRY_FINAL",
  expiredCommandMarker: "CLI_EXPIRED_WRITE_MUST_NOT_RUN",
  approvedCommandMarker: "CLI_APPROVED_WRITE_ONCE",
  expiry: true,
};

/** CLI、Native、JDK25 工具与辅助 JAR 都由调用方显式指定，避免安装态验收静默退回 debug JAR。 @author kongweiguang */
export function parseArguments(argv = process.argv.slice(2)) {
  const options = {};
  const names = new Map([
    ["--cli", "cli"],
    ["--native-executable", "nativeExecutable"],
    ["--jar", "jar"],
    ["--java", "java"],
    ["--evidence-directory", "evidenceDirectory"],
  ]);
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === "--scenario") {
      const value = argv[index + 1];
      if (value === undefined || value.startsWith("--") || value.trim() === "") {
        throw new Error("--scenario requires a value");
      }
      if (options.scenario !== undefined) throw new Error("--scenario can only be specified once");
      if (!["full", "expiry"].includes(value)) throw new Error("--scenario must be full or expiry");
      options.scenario = value;
      index += 1;
      continue;
    }
    const key = names.get(argv[index]);
    if (key === undefined) throw new Error(`unknown argument: ${argv[index]}`);
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--") || value.trim() === "") {
      throw new Error(`${argv[index]} requires a path`);
    }
    if (options[key] !== undefined) throw new Error(`${argv[index]} can only be specified once`);
    options[key] = resolve(value);
    index += 1;
  }
  for (const required of ["cli", "nativeExecutable", "jar", "java"]) {
    if (options[required] === undefined) throw new Error(`missing --${required === "nativeExecutable" ? "native-executable" : required}`);
  }
  options.scenario ??= "full";
  options.evidenceDirectory ??= join(repoRoot, "target", "subagent-cli-approval-conpty");
  return options;
}

/** 固定显式二进制指纹和 JDK25 身份；允许 Native Image 原名，随后仍按正式 sidecar 路径隔离启动。 @author kongweiguang */
async function inspectArtifacts(options) {
  assert.equal(process.platform, "win32", "this acceptance requires Windows ConPTY");
  const paths = {
    cli: options.cli,
    nativeExecutable: options.nativeExecutable,
    jar: options.jar,
    java: options.java,
    javac: join(dirname(options.java), "javac.exe"),
    jarTool: join(dirname(options.java), "jar.exe"),
  };
  const fingerprints = {};
  for (const [label, path] of Object.entries(paths)) {
    const metadata = await stat(path);
    assert.ok(metadata.isFile() && metadata.size > 0, `${label} artifact is missing or empty`);
    fingerprints[label] = {
      path,
      sizeBytes: metadata.size,
      sha256: createHash("sha256").update(await readFile(path)).digest("hex").toUpperCase(),
    };
  }
  assert.ok(
    [nativeSidecarName, "ja-app-server.exe"].includes(basename(paths.nativeExecutable)),
    "Native input must be the explicitly supplied Windows x64 sidecar or Native Image",
  );
  const version = await execFileAsync(paths.java, ["-version"], {
    windowsHide: true,
    timeout: 5_000,
    maxBuffer: 64 * 1024,
  });
  const javaVersion = `${version.stdout}\n${version.stderr}`.trim();
  assert.match(javaVersion, /version\s+"25(?:[.]|"|\s)/u, "ConPTY host must compile and run with JDK 25");
  return { paths, fingerprints, javaVersion };
}

/** 把两个指定安装制品复制到本次孤立便携目录，并让 CLI 只能按正式 sidecars 路径发现同一 Native。 @author kongweiguang */
async function stagePackage(artifacts, runRoot) {
  const packageDirectory = join(runRoot, "cli-package");
  const sidecarDirectory = join(packageDirectory, "sidecars");
  const cliPath = join(packageDirectory, "ja.exe");
  const nativePath = join(sidecarDirectory, nativeSidecarName);
  await Promise.all([mkdir(sidecarDirectory, { recursive: true }), mkdir(join(sidecarDirectory, "tools"), { recursive: true })]);
  await Promise.all([copyFile(artifacts.paths.cli, cliPath), copyFile(artifacts.paths.nativeExecutable, nativePath)]);
  for (const [source, destination, label] of [
    [artifacts.paths.cli, cliPath, "CLI"],
    [artifacts.paths.nativeExecutable, nativePath, "Native sidecar"],
  ]) {
    const copied = createHash("sha256").update(await readFile(destination)).digest("hex").toUpperCase();
    assert.equal(copied, artifacts.fingerprints[label === "CLI" ? "cli" : "nativeExecutable"].sha256, `${label} changed while staging`);
    assert.deepEqual(await stat(destination).then(({ size }) => size), await stat(source).then(({ size }) => size));
  }
  const sourceTools = join(repoRoot, "apps", "desktop", "src-tauri", "sidecars", "tools");
  const manifest = JSON.parse(await readFile(join(sourceTools, "tools-manifest.json"), "utf8"));
  assert.equal(manifest?.schemaVersion, 1);
  assert.equal(manifest?.product, "Ja");
  assert.equal(manifest?.target?.targetTriple, "x86_64-pc-windows-msvc");
  assert.ok(Array.isArray(manifest.tools) && manifest.tools.length === 2);
  for (const entry of manifest.tools) {
    const source = join(sourceTools, basename(entry.binary.relativePath));
    const destination = join(sidecarDirectory, "tools", basename(entry.binary.relativePath));
    await copyFile(source, destination);
    const copied = createHash("sha256").update(await readFile(destination)).digest("hex");
    assert.equal(copied, entry.binary.sha256, `packaged ${entry.name} identity changed while staging`);
  }
  return { packageDirectory, cliPath, nativePath };
}

/** 将 JA-RPC 错误缩为操作标签，不把隔离 Provider 请求或任意响应正文带入验收报告。 @author kongweiguang */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = frame.error?.data?.errorCode ?? frame.error?.data?.category ?? "RPC_ERROR";
    throw new Error(`${operation} failed: ${String(code)}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 用指定 Native 在隔离 JA_HOME 建立模型、凭据与两个真实 MAIN Thread，并完整关闭 seed owner 后才启动 CLI。 @author kongweiguang */
async function seedProfile(nativePath, directories, endpoint) {
  const session = new JsonlSession({
    command: nativePath,
    prefixArgs: [],
    directories,
    apiKey: placeholderSecret,
    endpoint,
  });
  let ready = false;
  try {
    const initialized = rpcResult(await session.request("runtime/initialize", initializeParams(), 30_000), "runtime/initialize");
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    ready = true;
    const current = rpcResult(await session.request("configuration/read", {}), "configuration/read");
    const document = providerConfigurationDocument({
      endpoint,
      name: "Ja CLI approval ConPTY loopback",
      api: "openai_responses",
      model: "subagent-cli-approval-fixture",
      selectedProviderId: providerId,
      selectedModelId: modelId,
      selectedCredentialId: credentialId,
    });
    document.providers[0].network_timeouts.request_timeout_ms = 120_000;
    const configured = rpcResult(await session.request("configuration/replace", {
      scope: "user",
      expectedVersion: current.cas.userVersion,
      document,
    }), "configuration/replace");
    assert.equal(configured.accepted, true);
    const credential = rpcResult(await session.request("credential/set", {
      credentialId,
      secret: placeholderSecret,
      expectedVersion: current.cas.credentialVersion,
    }), "credential/set");
    assert.equal(credential.configured, true);
    const workspace = rpcResult(await session.request("workspace/open", {
      cwd: directories.workspace,
      displayName: "Ja CLI approval ConPTY",
    }), "workspace/open");
    const trusted = rpcResult(await session.request("workspace/set-trust", {
      workspaceId: workspace.workspaceId,
      trust: "trusted",
    }), "workspace/set-trust");
    assert.equal(trusted.accepted, true);
    const createThread = async (title, accessMode) => rpcResult(await session.request("thread/create", {
      cwd: directories.workspace,
      title,
      providerId,
      modelId,
      reasoningLevel: "medium",
      accessMode,
      collaborationMode: "default",
    }), `thread/create ${title}`);
    const parent = await createThread("CLI Subagent Approval Parent", "full_access");
    const emptyParent = await createThread("CLI Empty Parent", "full_access");
    assert.match(parent.threadId, /^thr_[A-Za-z0-9_.-]+$/u);
    assert.match(emptyParent.threadId, /^thr_[A-Za-z0-9_.-]+$/u);
    const shutdown = await session.shutdown();
    assert.equal(shutdown?.code, 0, "Native seed owner must exit before the CLI opens this JA_HOME");
    return { parentThreadId: parent.threadId, emptyParentThreadId: emptyParent.threadId, workspaceId: workspace.workspaceId };
  } catch (error) {
    if (ready && !session.exited) await session.shutdown().catch(() => undefined);
    await session.forceClose();
    throw error;
  } finally {
    await session.forceClose();
  }
}

/** 从 Responses 请求的最后一条用户输入取稳定场景 marker，避免旧对话历史把后续请求路由错。 @author kongweiguang */
function latestUserText(payload) {
  if (typeof payload?.input === "string") return payload.input;
  if (!Array.isArray(payload?.input)) return "";
  for (let index = payload.input.length - 1; index >= 0; index -= 1) {
    const item = payload.input[index];
    if (item?.role !== "user") continue;
    if (typeof item.content === "string") return item.content;
    if (!Array.isArray(item.content)) return JSON.stringify(item.content ?? "");
    return item.content.map((part) => part?.text ?? part?.input_text ?? "").join("\n");
  }
  return "";
}

/** 读取已提交 function_call_output，按 call_id 继续本地模型脚本而不重放任何 Ja Tool。 @author kongweiguang */
function outputsByCall(payload) {
  const entries = Array.isArray(payload?.input) ? payload.input : [];
  return new Map(entries
    .filter((item) => item?.type === "function_call_output" && typeof item.call_id === "string")
    .map((item) => [item.call_id, item.output]));
}

/** Provider fixture 只记录本次合成 exec_command 的有限结果片段，便于诊断异步返回且不物化真实用户数据。 @author kongweiguang */
export function summarizeSyntheticToolResult(value) {
  const serialized = typeof value === "string" ? value : JSON.stringify(value) ?? String(value);
  const safe = serialized.replaceAll(placeholderSecret, "<redacted>");
  return {
    valueType: Array.isArray(value) ? "array" : value === null ? "null" : typeof value,
    length: safe.length,
    preview: safe.slice(0, 500),
  };
}

/** Marker 需要检查完整本地 fixture 结果，报告另由 summarizeSyntheticToolResult 限长。 @author kongweiguang */
function serializeSyntheticToolResult(value) {
  return typeof value === "string" ? value : JSON.stringify(value) ?? String(value);
}

/** 从 spawn_agent 的完整 ToolResult 中提取服务端签发的 Thread ID，不合成子会话身份。 @author kongweiguang */
function findThreadId(value) {
  if (typeof value === "string") {
    const matches = [...value.matchAll(/"(?:taskThreadId|threadId)"\s*:\s*"(thr_[A-Za-z0-9_.-]+)"/gu)];
    return matches.length === 1 ? matches[0][1] : null;
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findThreadId(item);
      if (found !== null) return found;
    }
    return null;
  }
  if (value !== null && typeof value === "object") {
    for (const [key, item] of Object.entries(value)) {
      if (["threadId", "taskThreadId"].includes(key) && typeof item === "string" && /^thr_[A-Za-z0-9_.-]+$/u.test(item)) return item;
      const found = findThreadId(item);
      if (found !== null) return found;
    }
  }
  return null;
}

/** 编码 OpenAI Responses SSE 帧，走正式 Provider adapter 的完整工具参数与 completed 生命周期。 @author kongweiguang */
function toolStream(responseId, callId, name, args) {
  const item = {
    id: `item_${callId}`,
    type: "function_call",
    call_id: callId,
    name,
    arguments: JSON.stringify(args),
  };
  const frames = [
    sseEvent("response.created", 0, { response: responseEnvelope(responseId, "in_progress", [], false) }),
    sseEvent("response.output_item.added", 1, { output_index: 0, item: { ...item, arguments: "" } }),
    sseEvent("response.function_call_arguments.delta", 2, { item_id: item.id, delta: item.arguments, output_index: 0 }),
    sseEvent("response.function_call_arguments.done", 3, { item_id: item.id, name, arguments: item.arguments, output_index: 0 }),
    sseEvent("response.output_item.done", 4, { output_index: 0, item }),
    sseEvent("response.completed", 5, { response: responseEnvelope(responseId, "completed", [item]) }),
  ];
  return frames.join("");
}

/** 编码 Provider 最终回复，响应正文只含合成标记以便从实际 ConPTY 屏幕验证流程完成。 @author kongweiguang */
function finalStream(responseId, text) {
  const item = {
    id: `message_${responseId}`,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    sseEvent("response.created", 0, { response: responseEnvelope(responseId, "in_progress", [], false) }),
    sseEvent("response.output_text.delta", 1, { content_index: 0, delta: text, item_id: item.id, output_index: 0, logprobs: [] }),
    sseEvent("response.output_text.done", 2, { content_index: 0, item_id: item.id, output_index: 0, text }),
    sseEvent("response.completed", 3, { response: responseEnvelope(responseId, "completed", [item]) }),
  ].join("");
}

/** 为 Provider 请求封装 JA 可消费的 Responses envelope，并将 token 账本保持在小型本地数据。 @author kongweiguang */
function responseEnvelope(responseId, status, output, includeUsage = true) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "subagent-cli-approval-fixture",
    object: "response",
    output,
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 32,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens: 8,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 40,
    };
  }
  return response;
}

/** SSE 帧序号连续且每帧以空行结束，保持 Provider 流解析器的生产边界。 @author kongweiguang */
function sseEvent(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({ type, sequence_number: sequenceNumber, ...payload })}\n\n`;
}

/** PowerShell 字符串以单引号定界；双写路径中的单引号，避免临时目录字符改变实际写入目标。 @author kongweiguang */
function powershellLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

/** 只接受 loopback 请求并按唯一场景推进 spawn、子命令审批和侧聊输入；未知 Provider 请求 fail closed。 @author kongweiguang */
async function startLoopbackProvider(workspace) {
  const observations = [];
  let failure;
  let requestIndex = 0;
  const server = createServer(async (request, response) => {
    response.setHeader("cache-control", "no-store");
    response.setHeader("connection", "close");
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      response.writeHead(404).end();
      return;
    }
    try {
      assert.equal(request.headers.authorization, `Bearer ${placeholderSecret}`);
      const chunks = [];
      let bytes = 0;
      for await (const chunk of request) {
        bytes += chunk.length;
        if (bytes > 4 * 1024 * 1024) throw new Error("provider request exceeded fixture limit");
        chunks.push(chunk);
      }
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const serializedInput = JSON.stringify(payload?.input ?? []);
      const latest = latestUserText(payload);
      requestIndex += 1;
      let output;
      if (serializedInput.includes("<user_request>") && serializedInput.includes("<assistant_reply>")) {
        output = finalStream(`resp_cli_approval_title_${requestIndex}`, "CLI approval ConPTY");
        observations.push({ role: "title", requestIndex });
      } else {
        const scenarios = [...childScenarios, expiryScenario];
        const scenario = scenarios.find((candidate) => latest.includes(candidate.parentMarker));
        const childScenario = scenarios.find((candidate) => latest.includes(candidate.childMarker));
        const outputs = outputsByCall(payload);
        if (scenario) {
          if (!outputs.has(scenario.spawnCallId)) {
            output = toolStream(`resp_${scenario.spawnCallId}`, scenario.spawnCallId, "spawn_agent", {
              taskName: scenario.taskName,
              brief: `Run exactly one exec_command requiring approval, then finish. ${scenario.childMarker}`,
              accessMode: "approval_required",
            });
            observations.push({ role: "parent", stage: "spawn_requested", taskName: scenario.taskName, requestIndex });
          } else {
            const threadId = findThreadId(outputs.get(scenario.spawnCallId));
            if (!threadId) throw new Error("spawn_agent result did not contain a server-issued child Thread ID");
            observations.push({ role: "parent", stage: "spawn_accepted", taskName: scenario.taskName, threadId, requestIndex });
            output = finalStream(`resp_${scenario.parentFinal}`, scenario.parentFinal);
          }
        } else if (childScenario) {
          const availableTools = Array.isArray(payload?.tools) ? payload.tools : [];
          const exposesExecCommand = availableTools.some((tool) => tool?.name === "exec_command" || tool?.function?.name === "exec_command");
          if (childScenario.expiry) {
            if (outputs.has(childScenario.resumedExecCallId)) {
              const toolResult = outputs.get(childScenario.resumedExecCallId);
              const resultText = serializeSyntheticToolResult(toolResult);
              const commandMarkerObserved = resultText.includes(childScenario.approvedCommandMarker);
              observations.push({
                role: "child",
                stage: "expiry_retried_exec_resolved",
                taskName: childScenario.taskName,
                commandMarkerObserved,
                toolResult: summarizeSyntheticToolResult(toolResult),
                requestIndex,
              });
              output = finalStream(`resp_${childScenario.childFinal}`, childScenario.childFinal);
            } else if (outputs.has(childScenario.execCallId)) {
              if (!exposesExecCommand) throw new Error("resumed subagent Provider request did not expose exec_command");
              const toolResult = outputs.get(childScenario.execCallId);
              const expiredResult = serializeSyntheticToolResult(toolResult);
              assert.match(expiredResult, /approval expired/iu, "F6 resume must return the expired ToolResult to the Provider");
              observations.push({
                role: "child",
                stage: "expired_tool_result_received",
                taskName: childScenario.taskName,
                expiredToolResultObserved: true,
                oldWriteMarkerIncludedInExpiredResult: expiredResult.includes(childScenario.expiredCommandMarker),
                toolResult: summarizeSyntheticToolResult(toolResult),
                requestIndex,
              });
              output = toolStream(`resp_${childScenario.resumedExecCallId}`, childScenario.resumedExecCallId, "exec_command", {
                cmd: `Add-Content -LiteralPath ${powershellLiteral(join(workspace, "cli-approved-write-once.txt"))} -Value ${powershellLiteral(childScenario.approvedCommandMarker)}; Write-Output ${powershellLiteral(childScenario.approvedCommandMarker)}`,
                workdir: workspace,
                yield_time_ms: 1_000,
                timeout_ms: 15_000,
              });
              observations.push({ role: "child", stage: "expiry_resumed_approval_requested", taskName: childScenario.taskName, callId: childScenario.resumedExecCallId, requestIndex });
            } else {
              if (!exposesExecCommand) throw new Error("subagent Provider request did not expose exec_command");
              output = toolStream(`resp_${childScenario.execCallId}`, childScenario.execCallId, "exec_command", {
                cmd: `Add-Content -LiteralPath ${powershellLiteral(join(workspace, "cli-expired-write-must-not-run.txt"))} -Value ${powershellLiteral(childScenario.expiredCommandMarker)}`,
                workdir: workspace,
                yield_time_ms: 1_000,
                timeout_ms: 15_000,
              });
              observations.push({ role: "child", stage: "expiry_first_approval_requested", taskName: childScenario.taskName, requestedAtMs: Date.now(), requestIndex });
            }
          } else if (!outputs.has(childScenario.execCallId)) {
            if (!exposesExecCommand) throw new Error("subagent Provider request did not expose exec_command");
            output = toolStream(`resp_${childScenario.execCallId}`, childScenario.execCallId, "exec_command", {
              cmd: childScenario.command,
              workdir: workspace,
              yield_time_ms: 1_000,
              timeout_ms: 15_000,
            });
            observations.push({ role: "child", stage: "approval_requested", taskName: childScenario.taskName, requestIndex });
          } else {
            const toolResult = outputs.get(childScenario.execCallId);
            const resultText = serializeSyntheticToolResult(toolResult);
            const commandMarkerObserved = resultText.includes(childScenario.commandOutput);
            observations.push({
              role: "child",
              stage: "approval_resolved",
              taskName: childScenario.taskName,
              decisionExpected: childScenario.decision,
              commandMarkerObserved,
              toolResult: summarizeSyntheticToolResult(toolResult),
              requestIndex,
            });
            output = finalStream(`resp_${childScenario.childFinal}`, childScenario.childFinal);
          }
        } else if (latest.includes("CLI_BTW_BODY_EXACT")) {
          observations.push({
            role: "side_chat",
            stage: "body_received",
            exactBodyPreserved: latest.includes("CLI_BTW_BODY_EXACT  中文🙂  "),
            requestIndex,
          });
          output = finalStream(`resp_cli_btw_body_${requestIndex}`, "CLI_BTW_BODY_SENT_ONCE");
        } else if (latest.includes("CLI_EMPTY_SIDE_CHAT_FIRST_INPUT")) {
          observations.push({ role: "side_chat", stage: "empty_child_first_input", requestIndex });
          output = finalStream(`resp_cli_btw_empty_${requestIndex}`, "CLI_EMPTY_SIDE_CHAT_FIRST_REPLY");
        } else {
          throw new Error("loopback fixture received an unrecognized latest user request");
        }
      }
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      response.end(output);
    } catch (error) {
      failure ??= error instanceof Error ? error.message.slice(0, 240) : String(error).slice(0, 240);
      response.writeHead(500, { "content-type": "application/json" });
      response.end('{"error":"subagent_cli_approval_fixture_failed"}');
    }
  });
  await new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");
  return {
    endpoint: `http://127.0.0.1:${address.port}/v1`,
    observations,
    failure: () => failure ?? null,
    async close() {
      server.closeAllConnections();
      await new Promise((resolveClose) => server.close((error) => error ? resolveClose(error) : resolveClose()));
    },
  };
}

/** 只在所给 runRoot 下创建 Ja 标准四目录；证据目录与实际 profile 分离，最终仅删 profile。 @author kongweiguang */
function runtimeDirectories(isolated) {
  return isolated.directories;
}

/** 等待终端当前真实可见 VT Buffer 满足条件，失败时带最后屏幕但不输出 App Server 原始 stderr。 @author kongweiguang */
async function waitScreen(cli, predicate, label, timeoutMs = 45_000) {
  return cli.waitForScreen((visible, lines) => predicate(visible, lines), label, timeoutMs);
}

/** 按任务标题所在投影块核对状态详情，避免命中另一个可见子任务的状态。 @author kongweiguang */
function taskActivityShowsState(lines, taskName, state) {
  const taskLine = lines.findIndex((line) => line.includes(`子任务 ${taskName}`));
  return taskLine >= 0
    && lines.slice(taskLine, taskLine + 4).some((line) => line.includes(`状态：${state}`));
}

/** 轮询父 Thread 的最新任务行直到真实状态到达；长等待每分钟给出阶段进度，不把旧审批文案当成暂停。 @author kongweiguang */
async function waitForTaskActivityState(cli, taskName, state, timeoutMs, requireNoApproval = false) {
  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  let nextProgressAt = startedAt + 60_000;
  while (Date.now() < deadline) {
    await cli.pullVt();
    const visible = cli.screen.text();
    const lines = cli.screen.lines();
    if (taskActivityShowsState(lines, taskName, state) && (!requireNoApproval || !visible.includes("等待审批"))) {
      return { text: visible, lines, elapsedMs: Date.now() - startedAt };
    }
    if (Date.now() >= nextProgressAt) {
      process.stdout.write(`WAITING_FOR_CLI_TASK_STATE task=${taskName} state=${state} elapsedSeconds=${Math.floor((Date.now() - startedAt) / 1_000)}\n`);
      nextProgressAt += 60_000;
    }
    if (cli.child.exitCode !== null) throw new Error(`CLI exited before Task Activity reached ${state}`);
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
  }
  await cli.pullVt();
  throw new Error(`timed out waiting for Task Activity ${taskName} state ${state}; last visible screen:\n${cli.screen.text()}`);
}

/**
 * /resume 候选只把暂停子任务标为“已暂停”；“待恢复”属于父 Activity 的说明且已在调用前权威核实。
 * 不依赖候选列表重复 timeline 文案，也不从相邻父卡片借状态；暂停状态必须在匹配到的候选行本身。 @author kongweiguang
 */
export function hasSuspendedChildCandidate(lines, taskName) {
  const row = lines.findIndex((line) => /^\s*(?:›\s*)?\d+\.\s*/u.test(line)
    && line.includes(taskName) && line.includes("子任务"));
  if (row < 0) return false;
  return lines[row].includes("已暂停");
}

/** 经真实 /resume 候选选中已暂停子任务，再由 UI 暴露 F6 恢复入口。 @author kongweiguang */
async function selectSuspendedChild(cli, taskName, runRoot) {
  await submitComposer(cli, "/resume", "/resume", "suspended child /resume command");
  await waitScreen(cli, (visible) => visible.includes("恢复会话") && visible.includes("搜索标题"), "suspended child selector");
  cli.sendKeys(taskName);
  const candidate = await waitScreen(cli, (_visible, lines) => hasSuspendedChildCandidate(lines, taskName),
    "visible suspended child candidate", 30_000);
  await writeScreenEvidence(runRoot, "expiry-suspended-child-candidate", cli.screen);
  await new Promise((resolveDelay) => setTimeout(resolveDelay, 150));
  cli.sendKeys("\r");
  const suspended = await waitScreen(cli, (visible) =>
    visible.includes("F6 恢复任务") && visible.includes("运行已暂停或中断") && !visible.includes("需要审批"),
  "selected suspended child with explicit F6 affordance", 30_000);
  return { candidate, suspended };
}

/** 仅把缺失文件视为空；其他读错误必须作为验证失败暴露。 @author kongweiguang */
async function readFileOrAbsent(path) {
  try {
    return await readFile(path, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw error;
  }
}

/** 让实际 TUI 先消费独立文本 key burst，再单独发送 Enter，避免 ConPTY 将正文和 CR 作为粘贴保留。 @author kongweiguang */
async function submitComposer(cli, text, visibleMarker, label) {
  cli.sendKeys(text);
  await waitScreen(cli, (visible) => visible.includes(visibleMarker), `${label} text settled`, 10_000);
  await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
  cli.sendKeys("\r");
}

/** CLI daemon 是 profile 的唯一后台进程；删除隔离数据前确认 server/stop 已撤销 endpoint receipt。 @author kongweiguang */
async function stopIsolatedDaemon(cliPath, directories) {
  const endpointPath = join(directories.run, "app-server.endpoint.json");
  let receipt;
  try {
    receipt = JSON.parse(await readFile(endpointPath, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return { stopped: false, endpointAbsent: true };
    throw new Error("isolated server endpoint receipt could not be checked");
  }
  assert.ok(["127.0.0.1", "::1"].includes(receipt.host), "isolated endpoint must stay loopback-only");
  assert.ok(Number.isInteger(receipt.port) && receipt.port > 0 && receipt.port <= 65_535);
  const endpointIdentity = {
    host: receipt.host,
    port: receipt.port,
    serverInstanceId: receipt.serverInstanceId,
    runtimeGeneration: receipt.runtimeGeneration,
  };
  const before = await runIsolatedCommand(cliPath, ["server", "status"], directories);
  const beforeStatus = {
    exitCode: before.code,
    stdout: before.stdout.trim().slice(-500),
    stderr: before.stderr.trim().slice(-500),
  };
  const loopbackListening = await loopbackPortAcceptsConnection(receipt.host, receipt.port);
  if (before.code !== 0 || !loopbackListening) {
    throw new Error(`isolated server owner is not confirmed before stop (${JSON.stringify({ endpointIdentity, beforeStatus, loopbackListening })})`);
  }
  const result = await runIsolatedCommand(cliPath, ["server", "stop"], directories);
  assert.equal(
    result.code,
    0,
    `isolated server stop was not confirmed (endpoint ${JSON.stringify(endpointIdentity)}; status ${before.code}; stdout ${beforeStatus.stdout}; stderr ${beforeStatus.stderr}; listening ${loopbackListening}; stop ${result.code}; ${result.stderr.trim().slice(-500)})`,
  );
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    try {
      await stat(endpointPath);
    } catch (error) {
      if (error?.code === "ENOENT") return { stopped: true, endpointAbsent: true, endpointIdentity, beforeStatus, loopbackListening };
      throw new Error("isolated server endpoint could not be checked");
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
  }
  throw new Error("isolated server stop was acknowledged but endpoint remained published");
}

/** Probe only an endpoint proven by this exact isolated profile; never send data or signal a port owner. @author kongweiguang */
function loopbackPortAcceptsConnection(host, port) {
  return new Promise((resolveConnection) => {
    const socket = createConnection({ host, port });
    const finish = (connected) => {
      socket.destroy();
      resolveConnection(connected);
    };
    socket.setTimeout(500, () => finish(false));
    socket.once("connect", () => finish(true));
    socket.once("error", () => finish(false));
  });
}

/** 通过 /resume 搜索精确 taskName；选中后强制断言候选是等待审批的子任务并显示当前 exec_command。 @author kongweiguang */
async function selectPendingChild(cli, taskName, runRoot, evidenceKey) {
  cli.sendKeys("/resume");
  await waitScreen(cli, (visible) => visible.includes("/resume"), `${evidenceKey} /resume command text`);
  await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
  cli.sendKeys("\r");
  await waitScreen(cli, (visible) => visible.includes("恢复会话") && visible.includes("搜索标题"), `${evidenceKey} /resume selector`);
  cli.sendKeys(taskName);
  const candidate = await waitScreen(cli, (_visible, lines) => lines.some((line) =>
    /^\s*(?:›\s*)?\d+\.\s*/u.test(line) &&
    line.includes(taskName) && line.includes("子任务") && line.includes("等待审批"),
  ), `${evidenceKey} visible approval child candidate`);
  await writeScreenEvidence(runRoot, `${evidenceKey}-child-candidate`, cli.screen);
  await new Promise((resolveDelay) => setTimeout(resolveDelay, 150));
  cli.sendKeys("\r");
  const approval = await waitScreen(cli, (visible) =>
    visible.includes("需要审批") && visible.includes("exec_command") && visible.includes("允许") && visible.includes("拒绝"),
  `${evidenceKey} child approval panel`);
  await writeScreenEvidence(runRoot, `${evidenceKey}-approval-panel`, cli.screen);
  assert.ok(approval.text.includes("CLI_APPROVAL_"), "approval prompt must remain tied to the child command context");
}

/** 按审批面板稳定的允许首项/拒绝次项键盘顺序提交决策，再等待同一 Child Turn 的 Provider 续答。 @author kongweiguang */
async function decideChildApproval(cli, scenario, runRoot) {
  if (scenario.decision === "deny") {
    cli.sendKeys("\u001b[B");
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
    cli.sendKeys("\r");
  }
  else cli.sendKeys("\r");
  const completed = await waitScreen(cli, (visible) => visible.includes(scenario.childFinal), `${scenario.decision} child Turn completion`, 90_000);
  await writeScreenEvidence(runRoot, `${scenario.taskName}-resolved`, cli.screen);
  return completed;
}

/** 执行有界 helper 命令；只允许传入本脚本生成的 isolated JA_HOME，超时只结束该命令子进程。 @author kongweiguang */
async function runIsolatedCommand(command, args, directories, timeoutMs = 30_000) {
  return new Promise((resolveCommand, rejectCommand) => {
    const environment = { ...process.env, JA_HOME: directories.home };
    for (const name of [
      "JA_DEBUG_JAVA", "JA_DEBUG_JAR", "JA_E2E_APP_SERVER_JAR", "JA_E2E_RUNTIME_ROOT",
      "JA_REAL_PROVIDER_API_KEY", "JA_REAL_PROVIDER_BASE_URL", "JA_REAL_PROVIDER_MODEL",
      "JA_REAL_PROVIDER_NAME", "JA_REAL_PROVIDER_API", "JA_REAL_PROVIDER_REASONING_LEVEL",
      "JA_REAL_PROVIDER_AUTHORIZED",
    ]) delete environment[name];
    if (process.platform !== "win32") {
      rejectCommand(new Error("isolated commands require Windows"));
      return;
    }
    const processChild = spawn(command, args, {
      cwd: directories.workspace,
      env: environment,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    const timer = setTimeout(() => processChild.kill(), timeoutMs);
    processChild.stdout.setEncoding("utf8").on("data", (chunk) => { stdout = `${stdout}${chunk}`.slice(-8_192); });
    processChild.stderr.setEncoding("utf8").on("data", (chunk) => { stderr = `${stderr}${chunk}`.slice(-8_192); });
    processChild.once("error", () => {
      clearTimeout(timer);
      rejectCommand(new Error("could not start isolated Ja command"));
    });
    processChild.once("close", (code, signal) => {
      clearTimeout(timer);
      resolveCommand({ code, signal, stdout, stderr });
    });
  });
}

/** 将证据留在调用方给定目录，把真实 Ja profile 限定为它的唯一 profile 子目录。 @author kongweiguang */
async function createIsolatedRun(evidenceDirectory) {
  const runRoot = await mkdtemp(join(evidenceDirectory, "run-"));
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

/** 仅在 CLI 自然退出并释放 owner 后启动隔离 Native，作最终权威 task/read 与 thread/read 冷核验。 @author kongweiguang */
async function startReadyNativeSession(nativePath, directories, endpoint) {
  const session = new JsonlSession({
    command: nativePath,
    prefixArgs: [],
    directories,
    apiKey: placeholderSecret,
    endpoint,
  });
  try {
    const initialized = rpcResult(await session.request("runtime/initialize", initializeParams(), 30_000), "runtime/initialize for CLI expiry evidence");
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    return session;
  } catch (error) {
    await session.forceClose();
    throw error;
  }
}

/** 同一 ConPTY TUI 经真实审批到期、只读 SUSPENDED 核实、F6 同 Turn 恢复与父活动闭环。 @author kongweiguang */
async function runExpiryConPtyScenario({ staged, conPtyArtifacts, harness, directories, provider, seed, runRoot, report }) {
  const scenario = expiryScenario;
  const expiredTarget = join(directories.workspace, "cli-expired-write-must-not-run.txt");
  const approvedTarget = join(directories.workspace, "cli-approved-write-once.txt");
  let cli;
  let session;

  /** 仅在 child Turn 已完成且 CLI 自然退出后停止隔离 Native，避免暂停期间 shutdown 拒绝或中断任务。 @author kongweiguang */
  const closeCli = async (label) => {
    const activeCli = cli;
    cli = undefined;
    const closed = await activeCli.close({
      beforeHarnessClose: async () => {
        report.cleanup[`${label}RuntimeAfterCliExit`] = await stopIsolatedDaemon(staged.cliPath, directories);
      },
    });
    assert.equal(closed.forced, false, `${label} TUI must exit naturally`);
    assert.equal(closed.childExitCode, 0, `${label} CLI must exit successfully`);
    report.cleanup[label] = closed;
  };

  try {
    cli = startConPtyCli(
      conPtyArtifacts,
      directories,
      harness,
      seed.parentThreadId,
      scenario.parentMarker,
      join(runRoot.runRoot, "expiry-parent-before.vt"),
      undefined,
      true,
    );
    await cli.harnessReady;
    await waitScreen(cli, (visible) => visible.includes(scenario.parentFinal), "expiry parent spawn Turn completes", 90_000);
    const spawned = await waitForObservation(
      provider,
      (item) => item.role === "parent" && item.taskName === scenario.taskName && item.stage === "spawn_accepted",
      60_000,
    );
    const childThreadId = spawned.threadId;
    report.childThreads ??= {};
    report.childThreads[scenario.taskName] = childThreadId;
    const firstApproval = await waitForObservation(
      provider,
      (item) => item.role === "child" && item.taskName === scenario.taskName && item.stage === "expiry_first_approval_requested",
      60_000,
    );
    const pendingActivity = await waitForTaskActivityState(cli, scenario.taskName, "等待审批", 60_000);
    report.screens.expiryParentWaitingApproval = await writeScreenEvidence(runRoot.runRoot, "expiry-parent-waiting-approval", cli.screen);
    assert.ok(pendingActivity.text.includes("等待审批"));
    report.expiry = {
      taskName: scenario.taskName,
      childThreadId,
      approvalRequestedObservedAt: new Date(firstApproval.requestedAtMs).toISOString(),
      firstCallId: scenario.execCallId,
      resumedCallId: scenario.resumedExecCallId,
    };

    const suspendedActivity = await waitForTaskActivityState(
      cli,
      scenario.taskName,
      "已暂停 · 待恢复",
      8 * 60_000,
      true,
    );
    const elapsedFromApprovalMs = Date.now() - firstApproval.requestedAtMs;
    assert.ok(elapsedFromApprovalMs >= 285_000, "CLI must observe the real five-minute approval expiry, not a simulated state");
    report.expiry.elapsedToSuspendedMs = elapsedFromApprovalMs;
    report.expiry.cliTaskActivityStateAfterExpiry = "suspended";
    report.expiry.noPendingApprovalTextAfterExpiry = !suspendedActivity.text.includes("等待审批");
    report.screens.expiryParentSuspended = await writeScreenEvidence(runRoot.runRoot, "expiry-parent-suspended", cli.screen);

    assert.equal(await readFileOrAbsent(expiredTarget), null, "expired exec_command must not write before explicit resume");
    report.expiry.parentTimelineStateAfterExpiry = "suspended";
    report.expiry.parentTimelineHasNoPendingApproval = true;
    report.expiry.noOldWriteBeforeResume = true;

    const selected = await selectSuspendedChild(cli, scenario.taskName, runRoot.runRoot);
    assert.ok(selected.candidate.text.includes("已暂停"));
    assert.ok(!selected.suspended.text.includes("等待审批"), "selected child must no longer expose its expired approval card");
    report.screens.expirySuspendedCandidate = await writeScreenEvidence(runRoot.runRoot, "expiry-suspended-child-selected", cli.screen);
    report.expiry.sameConPtySessionSelectedChild = true;
    cli.sendKeys("\u001b[17~");

    const expiredToolResult = await waitForObservation(
      provider,
      (item) => item.role === "child" && item.taskName === scenario.taskName && item.stage === "expired_tool_result_received",
      90_000,
    );
    assert.equal(expiredToolResult.expiredToolResultObserved, true);
    const secondApproval = await waitForObservation(
      provider,
      (item) => item.role === "child" && item.taskName === scenario.taskName && item.stage === "expiry_resumed_approval_requested",
      60_000,
    );
    const approvalPanel = await waitScreen(cli, (visible) =>
      visible.includes("需要审批") && visible.includes("exec_command") && visible.includes("允许") && visible.includes("拒绝"),
    "new approval after F6 resume of the original Turn", 60_000);
    report.expiry.secondApprovalCallId = secondApproval.callId;
    report.screens.expirySecondApproval = await writeScreenEvidence(runRoot.runRoot, "expiry-second-approval", cli.screen);
    assert.ok(approvalPanel.text.includes("需要审批"));
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 150));
    cli.sendKeys("\r");

    const childFinal = await waitScreen(cli, (visible) => visible.includes(scenario.childFinal), "same child Turn completes after new approval", 90_000);
    report.screens.expiryChildCompleted = await writeScreenEvidence(runRoot.runRoot, "expiry-child-completed", cli.screen);
    assert.ok(childFinal.text.includes(scenario.childFinal));
    const successfulRetry = await waitForObservation(
      provider,
      (item) => item.role === "child" && item.taskName === scenario.taskName && item.stage === "expiry_retried_exec_resolved",
      60_000,
    );
    assert.equal(successfulRetry.commandMarkerObserved, true, "the newly approved write must execute and return its marker once");
    report.expiry.childTurnCompleted = true;

    await submitComposer(cli, "/back", "/back", "return to parent after F6 expiry recovery");
    await waitScreen(cli, (visible) => visible.includes(scenario.parentFinal), "return from resumed child to parent");
    const completedActivity = await waitForTaskActivityState(cli, scenario.taskName, "已完成", 45_000);
    assert.ok(completedActivity.text.includes("已完成"));
    report.screens.expiryParentActivityCompleted = await writeScreenEvidence(runRoot.runRoot, "expiry-parent-activity-completed", cli.screen);
    await closeCli("expiryCompletedCli");

    session = await startReadyNativeSession(staged.nativePath, directories, provider.endpoint);
    const completedDetail = rpcResult(
      await session.request("task/read", { taskThreadId: childThreadId, limit: 200 }, 20_000),
      "task/read after F6 resumed completion",
    );
    assert.equal(completedDetail.task?.taskThreadId, childThreadId);
    assert.equal(completedDetail.task?.state, "completed");
    assert.equal(completedDetail.task?.taskKind, "subagent");
    assert.equal(completedDetail.task?.parentThreadId, seed.parentThreadId);
    assert.equal(completedDetail.task?.rootThreadId, seed.parentThreadId);
    assert.match(completedDetail.task?.originTurnId ?? "", /^turn_[A-Za-z0-9_.-]+$/u);
    const completedHistory = rpcResult(
      await session.request("thread/read", { threadId: childThreadId, cursor: null, limit: 200 }, 20_000),
      "thread/read after F6 resumed completion",
    );
    assert.equal(completedHistory.threadId, childThreadId);
    assert.equal(completedHistory.turns?.length, 1, "a fresh child Thread must still contain only its original Turn after F6");
    const resumedTurn = completedHistory.turns[0];
    assert.equal(resumedTurn.status, "completed", "F6 must complete the child Thread's original Turn");
    const expiredExecCalls = (completedHistory.items ?? []).filter((item) =>
      item.kind === "tool_call" && item.callId === scenario.execCallId && item.toolName === "exec_command",
    );
    assert.equal(expiredExecCalls.length, 1, "the expired approval call must remain in the original Turn history");
    assert.equal(expiredExecCalls[0].turnId, resumedTurn.turnId);
    const successfulExecCalls = (completedHistory.items ?? []).filter((item) =>
      item.kind === "tool_call" && item.turnId === resumedTurn.turnId
      && item.toolName === "exec_command" && item.presentation?.status === "success",
    );
    assert.equal(successfulExecCalls.length, 1);
    assert.equal(successfulExecCalls[0].callId, scenario.resumedExecCallId);
    const parentHistory = rpcResult(
      await session.request("thread/read", { threadId: seed.parentThreadId, cursor: null, limit: 200 }, 20_000),
      "parent thread/read after F6 resumed completion",
    );
    const matchingActivities = (parentHistory.taskActivities ?? []).filter((pair) => pair.task?.taskThreadId === childThreadId);
    assert.ok(matchingActivities.length > 0, "parent Thread must retain its child Task Activity");
    const latestParentActivity = matchingActivities.reduce((latest, candidate) =>
      String(candidate.activity?.createdAt ?? "") > String(latest.activity?.createdAt ?? "") ? candidate : latest,
    );
    assert.equal(latestParentActivity.task?.state, "completed");
    assert.equal(latestParentActivity.task?.originTurnId, completedDetail.task.originTurnId);
    assert.equal(await readFileOrAbsent(expiredTarget), null, "the expired command must remain unexecuted after resume");
    const approvedContents = await readFile(approvedTarget, "utf8");
    assert.equal(approvedContents.replaceAll("\r\n", "\n"), `${scenario.approvedCommandMarker}\n`);
    report.expiry.finalTaskState = completedDetail.task.state;
    report.expiry.finalTaskKind = completedDetail.task.taskKind;
    report.expiry.finalParentThreadId = completedDetail.task.parentThreadId;
    report.expiry.finalRootThreadId = completedDetail.task.rootThreadId;
    report.expiry.finalParentActivityState = latestParentActivity.task.state;
    report.expiry.finalTurnId = resumedTurn.turnId;
    report.expiry.successfulExecCommandCount = successfulExecCalls.length;
    report.expiry.approvedFileContents = approvedContents.replaceAll("\r\n", "\n");
    report.expiry.expiredWriteAbsentAfterCompletion = true;
    const finalReadShutdown = await session.shutdown();
    session = undefined;
    assert.equal(finalReadShutdown?.code, 0, "Native final readback owner must stop cleanly");

    report.phases.push("natural_five_minute_expiry_and_cli_suspended_candidate_verified", "cli_f6_resumed_same_turn_and_approved_write_once", "parent_task_activity_completed_verified");
  } finally {
    if (cli) {
      const activeCli = cli;
      cli = undefined;
      if (report.expiry?.childTurnCompleted === true) {
        report.cleanup.expiryActiveCli = await activeCli.close({
          beforeHarnessClose: async () => {
            report.cleanup.expiryActiveRuntimeAfterCliExit = await stopIsolatedDaemon(staged.cliPath, directories);
          },
        }).catch((error) => ({ error: error.message }));
      } else {
        report.cleanup.expiryActiveCli = await activeCli.close().catch((error) => ({ error: error.message }));
        report.cleanup.expiryRuntimeStopSkippedForNonterminalTurn = true;
      }
    }
    if (session) {
      report.cleanup.expiryNativeSession = await session.forceClose().then(() => "closed").catch(() => "close_failed");
      session = undefined;
    }
  }
}

/** 串起显式 release CLI 与 Native 的真实 ConPTY 验收；expiry 场景独立执行五分钟审批到期与 F6 恢复，其它场景覆盖允许/拒绝及 /btw。 @author kongweiguang */
export async function runAcceptance(argv = process.argv.slice(2)) {
  const options = parseArguments(argv);
  const artifacts = await inspectArtifacts(options);
  await mkdir(options.evidenceDirectory, { recursive: true });
  const runRoot = await createIsolatedRun(options.evidenceDirectory);
  const directories = runtimeDirectories(runRoot);
  const report = {
    status: "running",
    runtime: "explicit installed CLI + explicit bundled Native sidecar + real Windows ConPTY + loopback Responses fixture",
    inputs: artifacts.fingerprints,
    javaVersion: artifacts.javaVersion,
    phases: [],
    provider: { requests: 0 },
    screens: {},
    cleanup: {},
  };
  let provider;
  let cli;
  let session;
  let staged;
  let failure;
  try {
    staged = await stagePackage(artifacts, runRoot.runRoot);
    report.stagedPaths = staged;
    report.stagedCliSha256 = createHash("sha256").update(await readFile(staged.cliPath)).digest("hex").toUpperCase();
    report.stagedNativeSha256 = createHash("sha256").update(await readFile(staged.nativePath)).digest("hex").toUpperCase();
    assert.equal(report.stagedCliSha256, artifacts.fingerprints.cli.sha256);
    assert.equal(report.stagedNativeSha256, artifacts.fingerprints.nativeExecutable.sha256);
    provider = await startLoopbackProvider(directories.workspace);
    const seed = await seedProfile(staged.nativePath, directories, provider.endpoint);
    report.parentThreadId = seed.parentThreadId;
    report.emptyParentThreadId = seed.emptyParentThreadId;
    const conPtyArtifacts = {
      cli: staged.cliPath,
      java: artifacts.paths.java,
      jar: artifacts.paths.jar,
      javac: artifacts.paths.javac,
      jarTool: artifacts.paths.jarTool,
    };
    const harness = await compileConPtyHarness(conPtyArtifacts, runRoot.runRoot);
    report.phases.push("native_seed_ready", "ConPTY_harness_compiled_with_explicit_JDK25");

    if (options.scenario === "expiry") {
      await runExpiryConPtyScenario({
        staged,
        conPtyArtifacts,
        harness,
        directories,
        provider,
        seed,
        runRoot,
        report,
      });
      const expiredToolResult = provider.observations.find((item) => item.stage === "expired_tool_result_received");
      const resumedToolResult = provider.observations.find((item) => item.stage === "expiry_retried_exec_resolved");
      report.provider = {
        requests: provider.observations.length,
        observations: provider.observations,
        noFixtureFailure: provider.failure() === null,
        expiredToolResultReturnedToProvider: expiredToolResult?.expiredToolResultObserved === true,
        approvedRetryOutputObserved: resumedToolResult?.commandMarkerObserved === true,
      };
      assert.equal(report.provider.noFixtureFailure, true, `loopback fixture failed: ${provider.failure() ?? ""}`);
      assert.equal(report.provider.expiredToolResultReturnedToProvider, true);
      assert.equal(report.provider.approvedRetryOutputObserved, true);
      report.status = "completed";
    } else {
    cli = startConPtyCli(
      conPtyArtifacts,
      directories,
      harness,
      seed.parentThreadId,
      childScenarios[0].parentMarker,
      join(runRoot.runRoot, "subagent-approval.vt"),
      undefined,
      true,
    );
    await cli.harnessReady;
    await waitScreen(cli, (visible) => visible.includes(childScenarios[0].parentFinal), "parent spawn result and completed parent Turn");
    const firstSpawn = await waitForObservation(provider, (item) => item.role === "parent" && item.taskName === childScenarios[0].taskName && item.stage === "spawn_accepted", 60_000);
    report.childThreads ??= {};
    report.childThreads[childScenarios[0].taskName] = firstSpawn.threadId;
    await waitForObservation(provider, (item) => item.role === "child" && item.taskName === childScenarios[0].taskName && item.stage === "approval_requested", 60_000);
    const parentActivity = await waitScreen(cli, (visible) => visible.includes(childScenarios[0].taskName) && visible.includes("等待审批"), "parent Task Activity approval status", 45_000);
    report.screens.parentWaitingApproval = await writeScreenEvidence(runRoot.runRoot, "parent-waiting-approval", cli.screen);
    assert.ok(parentActivity.text.includes(childScenarios[0].taskName));
    await selectPendingChild(cli, childScenarios[0].taskName, runRoot.runRoot, "allow");
    const allowed = await decideChildApproval(cli, childScenarios[0], runRoot.runRoot);
    assert.ok(allowed.text.includes(childScenarios[0].childFinal));
    const allowResult = await waitForObservation(provider, (item) => item.role === "child" && item.taskName === childScenarios[0].taskName && item.stage === "approval_resolved", 60_000);
    assert.equal(allowResult.commandMarkerObserved, true, "approved exec_command must reach the loopback Provider as successful output");
    await submitComposer(cli, "/permissions", "/permissions", "subagent preference picker");
    const permissionPicker = await waitScreen(
      cli,
      (visible) => visible.includes("需要审批") && visible.includes("完全访问"),
      "subagent permission picker from authoritative preferences",
    );
    report.screens.subagentPermissionPicker = await writeScreenEvidence(runRoot.runRoot, "subagent-permission-picker", cli.screen);
    assert.ok(permissionPicker.text.includes("需要审批"));
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
    cli.sendKeys("\r");
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 300));
    await submitComposer(cli, "/status", "/status", "subagent authoritative status after preference readback");
    const subagentStatus = await waitScreen(
      cli,
      (visible) => visible.includes("会话状态") && visible.includes("权限：需要审批"),
      "subagent status panel after preference verification",
      30_000,
    );
    assert.ok(!subagentStatus.text.includes("状态部分未核实"));
    assert.ok(!subagentStatus.text.includes("未在 Workspace 的有界历史目录中找到该会话"));
    report.screens.subagentStatus = await writeScreenEvidence(runRoot.runRoot, "subagent-status", cli.screen);
    cli.sendKeys("\u001b[15~");
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 500));
    const f5Status = await waitScreen(
      cli,
      (visible) => visible.includes("会话状态") && visible.includes("权限：需要审批"),
      "F5 authoritative metadata refresh for child Thread",
      15_000,
    );
    assert.ok(!f5Status.text.includes("状态部分未核实"));
    assert.ok(!f5Status.text.includes("未在 Workspace 的有界历史目录中找到该会话"));
    report.screens.subagentStatusAfterF5 = await writeScreenEvidence(runRoot.runRoot, "subagent-status-after-f5", cli.screen);
    cli.sendKeys("\u001b");
    await waitScreen(cli, (visible) => visible.includes(childScenarios[0].childFinal) && !visible.includes("会话状态"), "return from child status panel");
    await submitComposer(cli, "/back", "/back", "return from approved child");
    await waitScreen(cli, (visible) => visible.includes(childScenarios[0].parentFinal), "return from approved Subagent to its parent");
    await waitScreen(
      cli,
      (_visible, lines) => taskActivityShowsState(lines, childScenarios[0].taskName, "已完成"),
      "parent Task Activity completion after approval",
      30_000,
    );
    report.screens.parentAfterAllow = await writeScreenEvidence(runRoot.runRoot, "parent-after-allow", cli.screen);

    await submitComposer(cli, childScenarios[1].parentMarker, childScenarios[1].parentMarker, "second parent turn");
    await waitScreen(cli, (visible) => visible.includes(childScenarios[1].parentFinal), "second parent turn spawning rejection child", 90_000);
    const secondSpawn = await waitForObservation(provider, (item) => item.role === "parent" && item.taskName === childScenarios[1].taskName && item.stage === "spawn_accepted", 60_000);
    report.childThreads[childScenarios[1].taskName] = secondSpawn.threadId;
    await waitForObservation(provider, (item) => item.role === "child" && item.taskName === childScenarios[1].taskName && item.stage === "approval_requested", 60_000);
    await selectPendingChild(cli, childScenarios[1].taskName, runRoot.runRoot, "reject");
    const rejected = await decideChildApproval(cli, childScenarios[1], runRoot.runRoot);
    assert.ok(rejected.text.includes(childScenarios[1].childFinal));
    const rejectResult = await waitForObservation(provider, (item) => item.role === "child" && item.taskName === childScenarios[1].taskName && item.stage === "approval_resolved", 60_000);
    assert.equal(rejectResult.commandMarkerObserved, false, "rejected exec_command must not run its command");
    await submitComposer(cli, "/back", "/back", "return from rejected child");
    await waitScreen(cli, (visible) => visible.includes(childScenarios[1].parentFinal), "return from rejected Subagent to its parent");
    await waitScreen(
      cli,
      (_visible, lines) => taskActivityShowsState(lines, childScenarios[1].taskName, "已完成"),
      "parent Task Activity completion after rejected approval",
      30_000,
    );
    report.screens.parentAfterReject = await writeScreenEvidence(runRoot.runRoot, "parent-after-reject", cli.screen);
    const approvalCli = cli;
    cli = undefined;
    const closedFirstCli = await approvalCli.close({
      beforeHarnessClose: async () => {
        report.cleanup.approvalRuntimeAfterCliExit = await stopIsolatedDaemon(staged.cliPath, directories);
      },
    });
    assert.equal(closedFirstCli.forced, false, "approval TUI must close through its normal terminal path");
    assert.equal(closedFirstCli.childExitCode, 0);
    report.cleanup.approvalCli = closedFirstCli;
    report.phases.push("subagent_selector_allow_and_reject_verified");

    cli = startConPtyCli(
      conPtyArtifacts,
      directories,
      harness,
      seed.emptyParentThreadId,
      undefined,
      join(runRoot.runRoot, "side-chat.vt"),
      undefined,
      true,
    );
    await cli.harnessReady;
    await submitComposer(cli, "/btw  CLI_BTW_BODY_EXACT  中文🙂  ", "CLI_BTW_BODY_EXACT", "body-bearing /btw");
    const bodyReply = await waitScreen(cli, (visible) => visible.includes("CLI_BTW_BODY_SENT_ONCE"), "/btw exact body child reply", 90_000);
    report.screens.btwBody = await writeScreenEvidence(runRoot.runRoot, "btw-body", cli.screen);
    assert.ok(bodyReply.text.includes("CLI_BTW_BODY_EXACT"));
    await submitComposer(cli, "/back", "/back", "return from body side chat");
    await waitScreen(cli, (visible) => !visible.includes("CLI_BTW_BODY_SENT_ONCE") && !visible.includes("侧聊关闭未确认"), "return and close body side chat");
    report.screens.backAfterBody = await writeScreenEvidence(runRoot.runRoot, "back-after-body", cli.screen);

    await submitComposer(cli, "/btw", "/btw", "empty /btw");
    const emptySideChat = await waitScreen(cli, (visible) => visible.includes("侧聊") && !visible.includes("正在工作"), "empty /btw creates and focuses idle side chat", 45_000);
    report.screens.btwEmptyIdle = await writeScreenEvidence(runRoot.runRoot, "btw-empty-idle", cli.screen);
    assert.ok(emptySideChat.lines.some((line) => line.trim() === "›"), "empty side chat must leave the focused Composer empty");
    await submitComposer(cli, "CLI_EMPTY_SIDE_CHAT_FIRST_INPUT", "CLI_EMPTY_SIDE_CHAT_FIRST_INPUT", "first empty side-chat prompt");
    const emptyReply = await waitScreen(cli, (visible) => visible.includes("CLI_EMPTY_SIDE_CHAT_FIRST_REPLY"), "first input in an empty side chat", 90_000);
    report.screens.btwEmptyFirstReply = await writeScreenEvidence(runRoot.runRoot, "btw-empty-first-reply", cli.screen);
    assert.ok(emptyReply.text.includes("CLI_EMPTY_SIDE_CHAT_FIRST_INPUT"));
    await submitComposer(cli, "/back", "/back", "return from empty side chat");
    await waitScreen(cli, (visible) => !visible.includes("CLI_EMPTY_SIDE_CHAT_FIRST_REPLY") && !visible.includes("侧聊关闭未确认"), "return and close empty side chat");
    report.screens.backAfterEmpty = await writeScreenEvidence(runRoot.runRoot, "back-after-empty", cli.screen);
    const sideChatCli = cli;
    cli = undefined;
    const closedSideCli = await sideChatCli.close({
      beforeHarnessClose: async () => {
        report.cleanup.sideChatRuntimeAfterCliExit = await stopIsolatedDaemon(staged.cliPath, directories);
      },
    });
    assert.equal(closedSideCli.forced, false, "side-chat TUI must close through its normal terminal path");
    assert.equal(closedSideCli.childExitCode, 0);
    report.cleanup.sideChatCli = closedSideCli;
    report.phases.push("btw_body_empty_and_back_verified");

    session = new JsonlSession({
      command: staged.nativePath,
      prefixArgs: [],
      directories,
      apiKey: placeholderSecret,
      endpoint: provider.endpoint,
    });
    const initialized = rpcResult(await session.request("runtime/initialize", initializeParams(), 30_000), "runtime/initialize after CLI stop");
    assert.equal(initialized?.runtime?.engine, "ja-kernel");
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    const taskTree = rpcResult(await session.request("task/list", { rootThreadId: seed.parentThreadId }, 20_000), "task/list after CLI approval");
    assert.ok(Array.isArray(taskTree.items));
    for (const scenario of childScenarios) {
      const childId = report.childThreads[scenario.taskName];
      const child = taskTree.items.find((task) => task.taskThreadId === childId);
      assert.ok(child, `${scenario.taskName} must remain in its authoritative parent Task tree`);
      assert.equal(child.state, "completed", `${scenario.taskName} must finish after its approval decision`);
    }
    const emptyParentTree = rpcResult(await session.request("task/list", { rootThreadId: seed.emptyParentThreadId }, 20_000), "task/list after empty /btw cleanup");
    assert.ok(Array.isArray(emptyParentTree.items));
    assert.equal(emptyParentTree.items.some((task) => task.taskKind === "side_task"), false, "both /back receipts must leave no side-task rows under the empty parent");
    report.persistedTasks = {
      approvalParent: taskTree.items.map((task) => ({ taskThreadId: task.taskThreadId, taskKind: task.taskKind, state: task.state })),
      emptyParent: emptyParentTree.items.map((task) => ({ taskThreadId: task.taskThreadId, taskKind: task.taskKind, state: task.state })),
    };
    const finalShutdown = await session.shutdown();
    session = undefined;
    assert.equal(finalShutdown?.code, 0);
    assert.ok(provider.failure() === null, `loopback fixture failed: ${provider.failure() ?? ""}`);
    report.provider = {
      requests: provider.observations.length,
      observations: provider.observations,
      noFixtureFailure: true,
      bodyPreservedExactly: provider.observations.some((item) => item.role === "side_chat" && item.stage === "body_received" && item.exactBodyPreserved),
      emptySideChatAcceptedFirstInput: provider.observations.some((item) => item.role === "side_chat" && item.stage === "empty_child_first_input"),
    };
    assert.equal(report.provider.bodyPreservedExactly, true);
    assert.equal(report.provider.emptySideChatAcceptedFirstInput, true);
    report.status = "completed";
    report.phases.push("side_task_purge_verified_in_native_task_list");
    }
  } catch (error) {
    failure = error instanceof Error ? error : new Error(String(error));
    report.status = "failed";
    report.failure = failure.message.replaceAll(placeholderSecret, "<redacted>");
  } finally {
    if (cli) {
      const activeCli = cli;
      cli = undefined;
      if (options.scenario === "expiry" && report.cleanup.expiryRuntimeStopSkippedForNonterminalTurn === true) {
        report.cleanup.activeCli = await activeCli.close().catch((error) => ({ error: error.message }));
      } else {
        report.cleanup.activeCli = await activeCli.close({
          beforeHarnessClose: async () => {
            if (staged) report.cleanup.activeRuntimeStop = await stopIsolatedDaemon(staged.cliPath, directories);
          },
        }).catch((error) => ({ error: error.message }));
      }
    }
    if (session) {
      report.cleanup.nativeSession = await session.forceClose().then(() => "closed").catch(() => "close_failed");
      session = undefined;
    }
    if (provider) await provider.close().catch(() => undefined);
    let endpointAbsent = true;
    if (staged) {
      if (report.cleanup.expiryRuntimeStopSkippedForNonterminalTurn === true) {
        endpointAbsent = false;
        report.cleanup.finalServerStopSkippedForNonterminalTurn = true;
      } else {
        try {
          report.cleanup.finalServerStop = await stopIsolatedDaemon(staged.cliPath, directories);
        } catch (error) {
          endpointAbsent = false;
          report.cleanup.finalServerStopError = error instanceof Error ? error.message : String(error);
          if (!failure) failure = error instanceof Error ? error : new Error(String(error));
          report.status = "failed";
        }
      }
    }
    if (endpointAbsent) {
      try {
        await removeOwnedProfile(runRoot.runRoot, runRoot.profileRoot);
        report.cleanup.profileRemoved = true;
      } catch (error) {
        report.cleanup.profileRemoved = false;
        report.cleanup.profileRemovalError = error instanceof Error ? error.message : String(error);
        if (!failure) failure = error instanceof Error ? error : new Error(String(error));
        report.status = "failed";
      }
    } else {
      report.cleanup.profileRemoved = false;
    }
    if (provider) {
      report.provider = {
        ...report.provider,
        requests: provider.observations.length,
        observations: provider.observations,
        noFixtureFailure: provider.failure() === null,
        ...(provider.failure() === null ? {} : { fixtureFailure: provider.failure() }),
      };
    }
    report.evidenceDirectory = runRoot.runRoot;
    await writeFile(join(runRoot.runRoot, "acceptance.json"), `${JSON.stringify(report, null, 2)}\n`, "utf8");
  }
  if (failure) {
    process.stderr.write(`Subagent CLI approval ConPTY acceptance failed. Evidence: ${runRoot.runRoot}\n`);
    throw failure;
  }
  process.stdout.write(`${JSON.stringify({ status: report.status, evidenceDirectory: runRoot.runRoot, inputs: report.inputs, phases: report.phases, cleanup: report.cleanup }, null, 2)}\n`);
}

/** 等待有界 Provider 观察事件，避免 TUI 状态超时把异步模型请求误判为命令失败。 @author kongweiguang */
async function waitForObservation(provider, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (provider.failure() !== null) throw new Error(`loopback fixture failed: ${provider.failure()}`);
    const observation = provider.observations.find(predicate);
    if (observation) return observation;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 50));
  }
  throw new Error("loopback fixture did not observe the expected collaboration stage");
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  runAcceptance().catch((error) => {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  });
}
