// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createServer } from "node:http";

const SHELL_CALL_ID = "call_shell_environment";
const CANCEL_PROMPT = "JA_E2E_CANCEL_REQUEST";
const FINAL_MARKER = "JA_SHELL_ENV_FINAL_OK";
const TITLE_MARKER = "JA_SHELL_ENV_TITLE";
const FACT_KEYS = new Set([
  "appdata_digest",
  "localappdata_digest",
  "userprofile_digest",
  "gh_config_dir_present",
  "gh_installed",
  "gh_logged_in",
  "gh_account",
]);

/** 将 Responses SSE 事件固定为严格 JSON，避免 fixture 依赖 Provider 宽松解析。 */
function event(type, sequenceNumber, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({
    type,
    sequence_number: sequenceNumber,
    ...payload,
  })}\n\n`;
}

/** 构造 JVM Provider adapter 所需的最小 Responses envelope，并保持 usage 字段稳定。 */
function responseEnvelope(responseId, status, output, includeUsage = true) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "gpt-5.6-sol",
    object: "response",
    output,
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 20,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens: 12,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 32,
    };
  }
  return response;
}

/** 生成只读环境探针；路径只在进程内转换为 digest，避免终端和报告泄露绝对路径。 */
export function buildEnvironmentProbeCommand(prefix) {
  assert.match(prefix, /^JA_(?:AGENT|TERMINAL)_ENV$/u);
  return [
    "$ErrorActionPreference='SilentlyContinue'",
    "function Get-JaDigest([string]$Value) { if ([string]::IsNullOrEmpty($Value)) { return '' }; $sha=[System.Security.Cryptography.SHA256]::Create(); try { return (([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-','').Substring(0,16).ToLowerInvariant()) } finally { $sha.Dispose() } }",
    "$gh=Get-Command gh.exe -ErrorAction SilentlyContinue",
    "$ghInstalled=$null -ne $gh",
    "$ghLoggedIn=$false",
    "$ghAccount=''",
    "if ($ghInstalled) { $ghStatus = (& gh.exe auth status --hostname github.com 2>&1 | Out-String); $ghLoggedIn = $LASTEXITCODE -eq 0; if ($ghLoggedIn) { $m=[regex]::Match($ghStatus,'account\\s+([^\\s]+)'); if ($m.Success) { $ghAccount=$m.Groups[1].Value } } }",
    `Write-Output '${prefix}_BEGIN'`,
    `Write-Output ('appdata_digest=' + (Get-JaDigest $env:APPDATA))`,
    `Write-Output ('localappdata_digest=' + (Get-JaDigest $env:LOCALAPPDATA))`,
    `Write-Output ('userprofile_digest=' + (Get-JaDigest $env:USERPROFILE))`,
    `Write-Output ('gh_config_dir_present=' + (-not [string]::IsNullOrWhiteSpace($env:GH_CONFIG_DIR)))`,
    `Write-Output ('gh_installed=' + [bool]$ghInstalled)`,
    `Write-Output ('gh_logged_in=' + [bool]$ghLoggedIn)`,
    `Write-Output ('gh_account=' + $ghAccount)`,
    `Write-Output '${prefix}_END'`,
  ].join("; ");
}

/** 生成一次 shell function_call；command 由受控 runner 固定，Provider 不可注入额外命令。 */
function shellStream(command) {
  const responseId = "resp_shell_environment";
  const itemId = "item_shell_environment";
  const messageId = `${itemId}_message`;
  const call = {
    id: itemId,
    type: "function_call",
    call_id: SHELL_CALL_ID,
    name: "shell",
    arguments: JSON.stringify({ command, timeout_ms: 30_000 }),
  };
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text: "我先核对两条终端链路的环境。", annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
    event("response.output_text.delta", 1, {
      content_index: 0,
      delta: "我先核对两条终端链路的环境。",
      item_id: messageId,
      output_index: 0,
      logprobs: [],
    }),
    event("response.output_text.done", 2, {
      content_index: 0,
      item_id: messageId,
      output_index: 0,
      text: "我先核对两条终端链路的环境。",
    }),
    event("response.output_item.added", 3, {
      output_index: 1,
      item: { ...call, arguments: "" },
    }),
    event("response.function_call_arguments.done", 4, {
      item_id: itemId,
      arguments: call.arguments,
      output_index: 1,
    }),
    event("response.output_item.done", 5, { output_index: 1, item: call }),
    event("response.completed", 6, {
      response: responseEnvelope(responseId, "completed", [message, call]),
    }),
  ].join("");
}

/** 生成不含 Tool 的最终 Provider 回复，保证 Shell continuation 有可观察终态。 */
function finalStream() {
  const responseId = "resp_shell_environment_final";
  const messageId = "message_shell_environment_final";
  const text = `两条终端链路已完成环境核对：${FINAL_MARKER}。`;
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
    event("response.output_text.delta", 1, {
      content_index: 0,
      delta: text,
      item_id: messageId,
      output_index: 0,
      logprobs: [],
    }),
    event("response.output_text.done", 2, {
      content_index: 0,
      item_id: messageId,
      output_index: 0,
      text,
    }),
    event("response.completed", 3, {
      response: responseEnvelope(responseId, "completed", [message]),
    }),
  ].join("");
}

/** 自动标题使用普通文本完成，避免标题请求混入 Shell continuation 统计。 */
function titleStream() {
  const responseId = "resp_shell_environment_title";
  const messageId = "message_shell_environment_title";
  const text = TITLE_MARKER;
  const message = {
    id: messageId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    event("response.created", 0, {
      response: responseEnvelope(responseId, "in_progress", [], false),
    }),
    event("response.output_text.delta", 1, {
      content_index: 0,
      delta: text,
      item_id: messageId,
      output_index: 0,
      logprobs: [],
    }),
    event("response.output_text.done", 2, {
      content_index: 0,
      item_id: messageId,
      output_index: 0,
      text,
    }),
    event("response.completed", 3, {
      response: responseEnvelope(responseId, "completed", [message]),
    }),
  ].join("");
}

/** 生成只读 cancel Provider 响应；客户端断开 HTTP 后才允许 fixture 结束该请求。 */
async function holdCancellationResponse(request, response) {
  response.writeHead(200, {
    "cache-control": "no-store",
    "content-type": "text/event-stream; charset=utf-8",
    connection: "close",
  });
  response.write(
    event("response.created", 0, {
      response: responseEnvelope("resp_shell_environment_cancel", "in_progress", [], false),
    }),
  );
  await new Promise((resolvePromise) => {
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      request.off("close", finish);
      response.off("close", finish);
      resolvePromise();
    };
    // 不同 HTTP 客户端可能只关闭响应流而保留已读完的请求流；两侧任一关闭都代表 cancel 已收口。
    request.once("close", finish);
    response.once("close", finish);
  });
}

/** 有界读取请求体；fixture 只需解析 continuation 结构，不保存 Provider 原始输入。 */
async function readBoundedJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 4 * 1024 * 1024) throw new Error("shell environment request exceeded 4 MiB");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 只从指定 function_call_output 字段取 Tool 结果，避免把 command echo 当作环境证据。 */
function findFunctionCallOutput(value, callId) {
  if (Array.isArray(value)) {
    for (const child of value) {
      const found = findFunctionCallOutput(child, callId);
      if (found !== undefined) return found;
    }
    return undefined;
  }
  if (value === null || typeof value !== "object") return undefined;
  const candidate = value;
  if (
    candidate.type === "function_call_output" &&
    candidate.call_id === callId &&
    typeof candidate.output === "string"
  ) {
    return candidate.output;
  }
  for (const child of Object.values(candidate)) {
    const found = findFunctionCallOutput(child, callId);
    if (found !== undefined) return found;
  }
  return undefined;
}

/** 从 Tool 输出提取固定字段并丢弃所有未知行，防止 token 或路径进入报告。 */
export function parseEnvironmentFacts(text, prefix) {
  assert.match(prefix, /^JA_(?:AGENT|TERMINAL)_ENV$/u);
  const begin = `${prefix}_BEGIN`;
  const end = `${prefix}_END`;
  // 终端会先回显用户输入；取最后一个 block 起点，避免把命令本身误当作输出事实。
  const start = text.lastIndexOf(begin);
  const finish = text.indexOf(end, start + begin.length);
  if (start < 0 || finish < 0) return undefined;
  const facts = {};
  for (const line of text.slice(start + begin.length, finish).split(/\r?\n/u)) {
    const separator = line.indexOf("=");
    if (separator <= 0) continue;
    const key = line.slice(0, separator);
    const value = line.slice(separator + 1).trim();
    if (!FACT_KEYS.has(key)) continue;
    if (key === "gh_account") {
      facts[key] = /^[A-Za-z0-9_.-]{0,128}$/u.test(value) ? value : "";
    } else if (key.endsWith("_present") || key.endsWith("_installed") || key.endsWith("_logged_in")) {
      facts[key] = value.toLowerCase() === "true";
    } else {
      facts[key] = /^[a-f0-9]{16}$/u.test(value) ? value : "";
    }
  }
  // 只接受完整探针；半截输出可能来自进程取消或 ConPTY 截断，不能被当作一致性证据。
  return FACT_KEYS.size === Object.keys(facts).length && [...FACT_KEYS].every((key) => key in facts)
    ? facts
    : undefined;
}

/** 启动只监听 IPv4 loopback 的 deterministic Provider，并记录脱敏的 cancel/Tool 事实。 */
export async function startShellEnvironmentFixture() {
  const attempts = [];
  const server = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    try {
      const payload = await readBoundedJson(request);
      const serialized = JSON.stringify(payload?.input ?? []);
      const isTitle =
        serialized.includes("<user_request>") && serialized.includes("<assistant_reply>");
      if (isTitle) {
        attempts.push({ kind: "title" });
        response.writeHead(200, {
          "cache-control": "no-store",
          "content-type": "text/event-stream; charset=utf-8",
          connection: "close",
        });
        response.end(titleStream());
        return;
      }
      // Cancel 是当前请求的控制信号；即使 input 携带历史 Shell output，也不能被旧 Tool 结果分支吞掉。
      if (serialized.includes(CANCEL_PROMPT)) {
        const attempt = { kind: "cancel", cancelled: false };
        attempts.push(attempt);
        await holdCancellationResponse(request, response);
        attempt.cancelled = true;
        return;
      }
      const functionOutput = findFunctionCallOutput(payload?.input, SHELL_CALL_ID);
      if (functionOutput !== undefined) {
        const facts = parseEnvironmentFacts(functionOutput, "JA_AGENT_ENV");
        attempts.push({ kind: "agent_shell", facts });
        if (facts === undefined) throw new Error("Agent Shell output did not contain a safe fact block");
        response.writeHead(200, {
          "cache-control": "no-store",
          "content-type": "text/event-stream; charset=utf-8",
          connection: "close",
        });
        response.end(finalStream());
        return;
      }
      attempts.push({ kind: "agent_shell_requested" });
      response.writeHead(200, {
        "cache-control": "no-store",
        "content-type": "text/event-stream; charset=utf-8",
        connection: "close",
      });
      response.end(shellStream(buildEnvironmentProbeCommand("JA_AGENT_ENV")));
    } catch (error) {
      if (!response.headersSent) response.writeHead(400, { "content-type": "application/json" });
      if (!response.writableEnded) {
        response.end(JSON.stringify({ error: String(error?.message ?? error).slice(0, 200) }));
      }
    }
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    attempts,
    /** 只返回已过滤的事实，绝不返回 Provider 请求正文或 Shell 原始输出。 */
    snapshot() {
      return { attempts: attempts.map((attempt) => ({ ...attempt })) };
    },
    /** 关闭唯一 loopback listener，保证 fixture 生命周期不越过 runner cleanup。 */
    async close() {
      server.closeAllConnections?.();
      await new Promise((resolvePromise) => server.close(resolvePromise));
    },
  };
}

export const shellEnvironmentFixtureMarkers = Object.freeze({
  agentShellCallId: SHELL_CALL_ID,
  cancelPrompt: CANCEL_PROMPT,
  final: FINAL_MARKER,
});
