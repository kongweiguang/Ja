// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Runtime refresh E2E 的 stdio MCP fixture。控制文件是测试进程与被 App Server 拥有的
 * 子进程之间唯一协调边界，避免测试依赖 PID、stdin 旁路或产品内测试命令。
 */

import { appendFile, readFile, stat } from "node:fs/promises";
import process from "node:process";

const [controlPath, reportPath] = process.argv.slice(2);
if (!controlPath || !reportPath) {
  throw new Error("usage: runtime-refresh-mcp.mjs <control.json> <report.ndjson>");
}

let currentRevision = 1;
let currentControlStamp = "";
let closed = false;
let inputBuffer = "";
let reportTail = Promise.resolve();

/** 报告只保存协议方法、schema revision 和参数类型，禁止把 Workspace 或模型正文写入证据。 */
function record(event) {
  reportTail = reportTail.then(() =>
    appendFile(reportPath, `${JSON.stringify({ at: new Date().toISOString(), ...event })}\n`, "utf8"),
  );
  return reportTail;
}

/** stdout 是 MCP JSONL 专用通道；所有诊断均写入独立 report 文件。 */
function send(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

/** revision 1/2 使用同名远端 Tool 但互不兼容的 schema，以证明旧 batch 不会同名重路由。 */
function toolForRevision(revision) {
  const valueSchema =
    revision === 1
      ? { type: "string", description: "revision one string" }
      : { type: "integer", description: "revision two integer" };
  return {
    name: "refresh_echo",
    description: `Runtime refresh fixture revision ${revision}`,
    inputSchema: {
      type: "object",
      properties: { value: valueSchema },
      required: ["value"],
      additionalProperties: false,
    },
  };
}

/** 控制文件只接受递增的闭集 revision；畸形或回退输入保持旧目录并记录拒绝事实。 */
async function refreshControl({ notify }) {
  let metadata;
  let parsed;
  try {
    metadata = await stat(controlPath);
    const stamp = `${metadata.mtimeMs}:${metadata.size}`;
    if (stamp === currentControlStamp) return;
    parsed = JSON.parse(await readFile(controlPath, "utf8"));
    currentControlStamp = stamp;
  } catch (error) {
    await record({ kind: "control_rejected", reason: error?.code ?? "invalid_json" });
    return;
  }
  const next = parsed?.revision;
  if (![1, 2].includes(next) || next < currentRevision) {
    await record({ kind: "control_rejected", reason: "invalid_revision" });
    return;
  }
  if (next === currentRevision) return;
  currentRevision = next;
  await record({ kind: "schema_changed", revision: currentRevision });
  if (notify) {
    send({ jsonrpc: "2.0", method: "notifications/tools/list_changed" });
    await record({ kind: "notification", revision: currentRevision });
  }
}

/** 每个请求先读取控制文件，使 tools/list 与通知即使发生重排也返回同一个权威 revision。 */
async function handleRequest(frame) {
  const method = frame?.method;
  if (typeof method !== "string") return;
  if (frame.id === undefined) {
    await record({ kind: "notification_received", method });
    return;
  }
  await refreshControl({ notify: false });
  await record({ kind: "request", method, revision: currentRevision });
  if (method === "initialize") {
    send({
      jsonrpc: "2.0",
      id: frame.id,
      result: {
        protocolVersion: "2025-06-18",
        capabilities: { tools: { listChanged: true } },
        serverInfo: { name: "ja-runtime-refresh-fixture", version: "1" },
      },
    });
    return;
  }
  if (method === "tools/list") {
    send({
      jsonrpc: "2.0",
      id: frame.id,
      result: { tools: [toolForRevision(currentRevision)] },
    });
    return;
  }
  if (method === "tools/call") {
    const value = frame?.params?.arguments?.value;
    await record({
      kind: "tool_call",
      revision: currentRevision,
      name: frame?.params?.name,
      valueType: typeof value,
    });
    send({
      jsonrpc: "2.0",
      id: frame.id,
      result: {
        content: [{ type: "text", text: `revision-${currentRevision}:${typeof value}` }],
        structuredContent: { revision: currentRevision, valueType: typeof value },
        isError: false,
      },
    });
    return;
  }
  send({ jsonrpc: "2.0", id: frame.id, result: {} });
}

/** JSONL framing mirrors the Java MCP integration fixture and serializes handlers to preserve request order. */
async function consumeInput(chunk) {
  inputBuffer += chunk;
  while (true) {
    const newline = inputBuffer.indexOf("\n");
    if (newline < 0) return;
    const line = inputBuffer.slice(0, newline).trim();
    inputBuffer = inputBuffer.slice(newline + 1);
    if (!line) continue;
    try {
      await handleRequest(JSON.parse(line));
    } catch (error) {
      await record({ kind: "request_rejected", reason: error?.name ?? "Error" });
    }
  }
}

await refreshControl({ notify: false });
await record({ kind: "started", pid: process.pid, revision: currentRevision });
const watcher = globalThis.setInterval(() => {
  if (!closed) void refreshControl({ notify: true });
}, 50);
watcher.unref?.();

let inputTail = Promise.resolve();
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  inputTail = inputTail.then(() => consumeInput(chunk));
});
process.stdin.on("end", async () => {
  closed = true;
  globalThis.clearInterval(watcher);
  await inputTail;
  await record({ kind: "stopped", revision: currentRevision });
  await reportTail;
});

