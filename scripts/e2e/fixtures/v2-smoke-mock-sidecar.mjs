// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 为 Native smoke 客户端提供无网络、无持久化兼容层的 JA-RPC v2 子进程。
 *
 * 该 fixture 只模拟客户端解析、关联、Unicode argv 与清理所需的当前协议事实；Provider、
 * MCP、ACL、恢复与 Native Image 能力必须继续由生产可执行文件证明，不能在这里伪造绿色证据。
 */

import { writeFileSync } from "node:fs";
import process from "node:process";
import { createInterface } from "node:readline";

const readyToken = "0123456789abcdef0123456789abcdef";
const occurredAt = "2026-08-29T00:00:00Z";
const serverInstanceId = "srv_native_fixture";
const workspaceId = "ws_native_fixture";
const threadId = "thr_native_fixture";
const methods = [
  "runtime/initialize", "runtime/health", "runtime/shutdown", "workspace/open",
  "workspace/open-general", "workspace/list", "workspace/set-trust", "workspace/unregister",
  "thread/create", "thread/list", "thread/read", "thread/archive", "thread/delete",
  "turn/start", "turn/cancel", "turn/steer", "turn/follow-up", "approval/respond",
  "configuration/read", "configuration/patch", "configuration/replace", "configuration/reset",
  "credential/set", "credential/delete", "skill/list", "mcp/list", "mcp/test", "mcp/list-tools",
];
const events = [
  "runtime/status-changed", "turn/state-changed", "assistant/model-step-committed",
  "assistant/text-delta", "assistant/reasoning-summary-delta", "tool/batch-committed",
  "approval/requested", "approval/resolved", "context/compacted", "workspace/dirty",
  "turn/terminal", "configuration/changed",
];
const limits = {
  maxFrameBytes: 4_194_304,
  maxInFlightRequests: 64,
  maxInboundQueueFrames: 256,
  maxControlOutboundQueueFrames: 64,
  maxDataOutboundQueueFrames: 1_024,
  maxConcurrentTurns: 8,
  maxAdmittedTurns: 64,
  maxThreadQueuedTurns: 8,
  maxSnapshotPageItems: 200,
  maxToolBatchConcurrency: 8,
};

let userVersion = "cfg_missing";
let credentialVersion = "cfg_missing";
let credentialConfigured = false;
let sequence = 0;
let workspaceRoot = "C:/fixture-workspace";

/** 解码 smoke 独占目录；fixture 只读取 argv 中精确命名的 Base64URL 参数。 */
function decodedArgument(name) {
  const prefix = `--${name}-base64=`;
  const value = process.argv.find((argument) => argument.startsWith(prefix))?.slice(prefix.length);
  if (value === undefined || value.length === 0) return undefined;
  return Buffer.from(value, "base64url").toString("utf8");
}

/** 向 stdout 写入唯一允许的 JSONL 协议帧，禁止日志污染传输。 */
function send(frame) {
  process.stdout.write(`${JSON.stringify(frame)}\n`);
}

/** 生成当前唯一的严格空配置，避免 fixture 保留已淘汰配置键。 */
function emptyConfiguration() {
  return {
    schema_version: 2,
    config_revision: 0,
    permission_mode: "full_access",
    default_profile_id: null,
    profiles: [],
    mcp_servers: [],
    skills: [],
  };
}

/** 返回 CAS 独立承载三个版本的当前读取投影，层对象不再复制版本字段。 */
function configurationReadResult() {
  return {
    workspaceId: null,
    effective: emptyConfiguration(),
    user: { present: false, trusted: true, status: "missing", document: null },
    project: { present: false, trusted: false, status: "untrusted", document: null },
    credentials: credentialConfigured
      ? { cred_native_smoke: { configured: true } }
      : {},
    cas: {
      userVersion,
      projectVersion: "cfg_missing",
      credentialVersion,
    },
    diagnostics: ["PROJECT_UNTRUSTED"],
    trusted: false,
  };
}

/** 构造与 initialize offer 同闭集的当前 v2 接受结果。 */
function initializeResult() {
  return {
    protocolMajor: 2,
    protocolMinor: 0,
    serverInstanceId,
    runtime: { engine: "ja-kernel", engineVersion: "2.0.0-fixture" },
    capabilities: { methods, events, accessModes: ["approval_required", "full_access"] },
    limits,
  };
}

/** 处理一个当前 v2 请求；未知方法 fail-closed，不提供旧协议 fallback。 */
function handleRequest(frame) {
  switch (frame.method) {
    case "runtime/initialize":
      send({ jsonrpc: "2.0", id: frame.id, result: initializeResult() });
      return false;
    case "configuration/read":
      send({ jsonrpc: "2.0", id: frame.id, result: configurationReadResult() });
      return false;
    case "configuration/replace":
      userVersion = "cfg_user_fixture";
      send({
        jsonrpc: "2.0",
        id: frame.id,
        result: { accepted: true, scope: "user", version: userVersion },
      });
      return false;
    case "credential/set":
      credentialConfigured = true;
      credentialVersion = "cfg_auth_fixture";
      send({
        jsonrpc: "2.0",
        id: frame.id,
        result: {
          accepted: true,
          credentialId: frame.params.credentialId,
          configured: true,
          version: credentialVersion,
        },
      });
      return false;
    case "credential/delete":
      credentialConfigured = false;
      credentialVersion = "cfg_auth_deleted_fixture";
      send({
        jsonrpc: "2.0",
        id: frame.id,
        result: {
          accepted: true,
          credentialId: frame.params.credentialId,
          configured: false,
          version: credentialVersion,
        },
      });
      return false;
    case "workspace/open":
      workspaceRoot = frame.params.cwd;
      send({
        jsonrpc: "2.0",
        id: frame.id,
        result: {
          workspaceId,
          root: workspaceRoot,
          displayName: frame.params.displayName,
          trust: "trusted",
          revision: 0,
        },
      });
      return false;
    case "runtime/health":
      send({
        jsonrpc: "2.0",
        id: frame.id,
        result: {
          status: "ready",
          generation: 1,
          components: [
            { name: "sqlite", status: "healthy" },
            { name: "kernel", status: "healthy" },
          ],
        },
      });
      return false;
    case "skill/list":
      send({
        jsonrpc: "2.0",
        id: frame.id,
        result: {
          items: [{
            skillId: "skill_native_coding",
            name: "coding",
            scope: "builtin",
            enabled: true,
            status: "healthy",
            description: "Native smoke fixture skill",
          }],
          nextCursor: null,
        },
      });
      return false;
    case "thread/create":
      send({
        jsonrpc: "2.0",
        id: frame.id,
        result: {
          threadId,
          workspaceId,
          profileId: null,
          title: frame.params.title,
          status: "active",
          revision: 0,
          createdAt: occurredAt,
          updatedAt: occurredAt,
        },
      });
      return false;
    case "thread/read":
      send({
        jsonrpc: "2.0",
        id: frame.id,
        result: {
          threadId,
          workspaceId,
          profileId: null,
          title: "Ja Kernel smoke",
          status: "active",
          revision: 0,
          snapshotSeq: 0,
          items: [],
          nextCursor: null,
        },
      });
      return false;
    case "runtime/shutdown":
      send({
        jsonrpc: "2.0",
        id: frame.id,
        result: { accepted: true, status: "shutting_down" },
      });
      return true;
    default:
      send({
        jsonrpc: "2.0",
        id: frame.id,
        error: {
          code: -32601,
          message: "Method not found",
          data: {
            errorCode: "METHOD_NOT_FOUND",
            category: "protocol",
            retryable: false,
            errorId: "err_00000000000000000000000000000001",
          },
        },
      });
      return false;
  }
}

const logDirectory = decodedArgument("log-dir");
if (logDirectory !== undefined) {
  writeFileSync(`${logDirectory}/app-server.log`, "fixture started\n", { encoding: "utf8" });
}

const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on("line", (line) => {
  let frame;
  try {
    frame = JSON.parse(line);
  } catch {
    process.exitCode = 1;
    input.close();
    return;
  }
  if (frame.method === "runtime/initialized") {
    sequence += 1;
    send({
      jsonrpc: "2.0",
      method: "runtime/status-changed",
      params: {
        serverInstanceId,
        eventId: "evt_native_fixture_ready",
        sequence,
        occurredAt,
        status: "ready",
        generation: 1,
        readyToken,
      },
    });
    return;
  }
  if (handleRequest(frame)) input.close();
});
