// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { once } from "node:events";
import { access, mkdir, mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

import {
  createOperationKey,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
} from "./real-provider-smoke.mjs";

const execFileAsync = promisify(execFile);
const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDirectory, "..", "..");
const fixtureSecret = "ja-turn-change-fixture-secret";
const providerId = "provider_turn_change_fixture";
const modelId = "model_turn_change_fixture";
const credentialId = "cred_turn_change_fixture";
const relativePath = "change-set-rpc-fixture.txt";
const fileContent = "Ja Turn 修改记录：\nUTF-8 artifact 验证 β\n";
const finalMarker = "JA_TURN_CHANGE_ARTIFACT_RPC_OK";
const fixtureModel = "ja-turn-change-fixture";
const maxRequestBytes = 4 * 1024 * 1024;

/** 向 Responses 状态机发送最小但完整的生产格式流，兼容 Tool 与普通终答两类 output item。 */
function responsesEventStream(responseId, output, text) {
  const envelope = {
    id: responseId,
    object: "response",
    created_at: 0,
    model: fixtureModel,
    status: "completed",
    output,
    parallel_tool_calls: false,
    tool_choice: "auto",
    tools: [],
    usage: {
      input_tokens: 1,
      output_tokens: 1,
      total_tokens: 2,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens_details: { reasoning_tokens: 0 },
    },
  };
  const frames = [
    [
      "response.created",
      { response: { ...envelope, status: "in_progress", output: [], usage: undefined } },
    ],
  ];
  output.forEach((item, index) => {
    frames.push([
      "response.output_item.added",
      {
        output_index: index,
        item: item.type === "function_call" ? { ...item, arguments: "" } : item,
      },
    ]);
    if (item.type === "function_call") {
      frames.push([
        "response.function_call_arguments.done",
        { output_index: index, item_id: item.id, arguments: item.arguments },
      ]);
    } else if (text !== undefined) {
      frames.push([
        "response.output_text.delta",
        { output_index: index, item_id: item.id, content_index: 0, delta: text, logprobs: [] },
      ]);
      frames.push([
        "response.output_text.done",
        { output_index: index, item_id: item.id, content_index: 0, text },
      ]);
    }
    frames.push(["response.output_item.done", { output_index: index, item }]);
  });
  frames.push(["response.completed", { response: envelope }]);
  return frames
    .map(
      ([event, payload], sequence) =>
        `event: ${event}\ndata: ${JSON.stringify({ type: event, sequence_number: sequence, ...payload })}\n\n`,
    )
    .join("");
}

/** 限制 Provider fixture 入站正文，防止失配请求被无界缓冲或写入诊断。 */
async function readJsonRequest(request) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > maxRequestBytes) throw new Error("fixture_request_too_large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 要求真实模型请求声明严格的内建 write 参数，避免 fixture 越过生产 Tool 契约。 */
function assertWriteTool(payload) {
  const tool = Array.isArray(payload?.tools)
    ? payload.tools.find(
        (candidate) => candidate?.type === "function" && candidate?.name === "write",
      )
    : undefined;
  const schema = tool?.parameters;
  const required = schema?.required;
  if (
    tool?.strict !== true ||
    !Array.isArray(required) ||
    !required.includes("path") ||
    !required.includes("content") ||
    schema?.properties?.path?.type !== "string" ||
    schema?.properties?.content?.type !== "string"
  ) {
    throw new Error("provider request did not expose the expected strict write Tool");
  }
}

/** 创建只监听 IPv4 loopback 的两阶段 provider，基于 Ja 回传的 ToolResult 确定终答阶段。 */
function createProviderFixture() {
  const state = { requestCount: 0, toolResponseCount: 0, finalResponseCount: 0 };

  /** 仅接受 Responses POST，并根据是否已有 write ToolResult 返回固定、可重放响应。 */
  async function handleRequest(request, response) {
    state.requestCount += 1;
    try {
      if (request.method !== "POST" || request.url !== "/v1/responses") {
        response.writeHead(404, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: { code: "fixture_route_not_found" } }));
        return;
      }
      if (state.requestCount > 4) {
        response.writeHead(429, { "content-type": "application/json" });
        response.end(JSON.stringify({ error: { code: "fixture_request_budget_exceeded" } }));
        return;
      }

      const payload = await readJsonRequest(request);
      const items = Array.isArray(payload?.input) ? payload.input : [];
      const toolResult = items.find(
        (item) =>
          item?.type === "function_call_output" && item?.call_id === "call_turn_change_write",
      );
      let stream;
      if (toolResult === undefined) {
        assertWriteTool(payload);
        state.toolResponseCount += 1;
        const argumentsText = JSON.stringify({ path: relativePath, content: fileContent });
        stream = responsesEventStream("resp_turn_change_tool", [
          {
            id: "item_turn_change_write",
            type: "function_call",
            call_id: "call_turn_change_write",
            name: "write",
            arguments: argumentsText,
          },
        ]);
      } else {
        if (typeof toolResult.output !== "string" || toolResult.output.length === 0) {
          throw new Error("write ToolResult was empty");
        }
        state.finalResponseCount += 1;
        const itemId = "msg_turn_change_final";
        stream = responsesEventStream(
          "resp_turn_change_final",
          [
            {
              id: itemId,
              type: "message",
              role: "assistant",
              status: "completed",
              content: [{ type: "output_text", text: finalMarker, annotations: [], logprobs: [] }],
            },
          ],
          finalMarker,
        );
      }
      response.writeHead(200, {
        "content-type": "text/event-stream; charset=utf-8",
        "cache-control": "no-cache",
        connection: "close",
      });
      response.end(stream);
    } catch (error) {
      response.writeHead(400, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: { code: "fixture_request_invalid" } }));
      state.failureCode =
        error instanceof Error ? error.message.slice(0, 120) : "fixture_request_invalid";
    }
  }

  // Keep request parsing in one named, bounded handler so fixture failures cannot leak request bodies.
  const server = createServer((request, response) => void handleRequest(request, response));
  return { server, state };
}

/** 用临时 IPv4 端口监听本地测试 Provider，阻止该 Runner 产生外部网络请求。 */
async function listenLoopback(server) {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("loopback fixture did not bind an IPv4 port");
  }
  return `http://127.0.0.1:${address.port}`;
}

/** 关闭已绑定的 Provider socket，保证 App Server 停止后不留下后台监听器。 */
async function closeServer(server) {
  if (!server.listening) return;
  server.close();
  await once(server, "close");
}

/** 把 JA-RPC 失败压缩为稳定错误码，避免诊断输出持久化消息或配置内容。 */
function assertRpcSuccess(frame, method) {
  if (frame?.error !== undefined) {
    const code = frame.error?.data?.errorCode ?? "UNKNOWN_ERROR";
    throw new Error(`${method} failed: ${code}`);
  }
  if (frame?.result === undefined) throw new Error(`${method} returned no result`);
  return frame.result;
}

/** 为本次进程记录有限 JA-RPC 方法计数，再复用 Session 的关联 ID 与超时保护。 */
async function requestRpc(session, counts, method, params) {
  counts.set(method, (counts.get(method) ?? 0) + 1);
  return assertRpcSuccess(await session.request(method, params), method);
}

/** 确认 Java 25 与构建目录中的真实 App Server JAR，避免拿错误运行时解释持久化结果。 */
async function resolveRuntime() {
  const configuredJava = process.env.JA_REAL_PROVIDER_JAVA?.trim();
  const java =
    configuredJava ||
    (process.env.JAVA_HOME
      ? join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java")
      : process.platform === "win32"
        ? "java.exe"
        : "java");
  let version = "";
  try {
    const output = await execFileAsync(java, ["-version"], {
      windowsHide: true,
      maxBuffer: 64 * 1024,
    });
    version = `${output.stdout}\n${output.stderr}`;
  } catch (error) {
    version = `${error?.stdout ?? ""}\n${error?.stderr ?? ""}`;
  }
  if (!/version\s+"25(?:\.|"|\+)/u.test(version)) {
    throw new Error("Java 25 is required to run the real App Server JAR");
  }

  const jar = join(repoRoot, "ja-agent", "ja-app-server", "target", "ja-app-server.jar");
  const metadata = await stat(jar).catch(() => undefined);
  if (metadata === undefined || !metadata.isFile() || metadata.size === 0) {
    throw new Error("ja-agent/ja-app-server/target/ja-app-server.jar is unavailable");
  }
  await access(jar);
  return { java, jar, jarBytes: metadata.size };
}

/** 对齐原生 HomeLayout：JA_HOME 唯一承载 data、run 与 Java 日志，便于同库桌面回读。 */
async function createHomeLayout() {
  const root = await mkdtemp(join(tmpdir(), "ja-turn-change-artifact-rpc-"));
  const home = join(root, "home-家");
  const data = join(home, "data");
  const run = join(home, "run");
  const logs = join(home, "logs", "java");
  const workspace = join(root, "workspace-工作区");
  await Promise.all(
    [home, data, run, logs, workspace].map((path) => mkdir(path, { recursive: true })),
  );
  return { root, home, data, run, logs, workspace };
}

/** 启动真实 JAR、写入一份文件并从冻结 ChangeSet 读取摘要与 Diff 正文。 */
async function runAcceptance() {
  const runtime = await resolveRuntime();
  const directories = await createHomeLayout();
  const fixture = createProviderFixture();
  const counts = new Map();
  let session;
  let shutdown = null;
  let providerUrl;

  try {
    providerUrl = await listenLoopback(fixture.server);
    session = new JsonlSession({
      command: runtime.java,
      prefixArgs: ["-jar", runtime.jar],
      directories,
      apiKey: fixtureSecret,
      endpoint: providerUrl,
    });
    const initialized = await requestRpc(session, counts, "runtime/initialize", initializeParams());
    if (initialized?.runtime?.engine !== "ja-kernel") {
      throw new Error("App Server did not start the Ja Kernel runtime");
    }
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );

    const configuration = await requestRpc(session, counts, "configuration/read", {});
    if (
      configuration?.cas?.userVersion !== "cfg_missing" ||
      configuration?.cas?.credentialVersion !== "cfg_missing"
    ) {
      throw new Error("isolated home did not start without configuration or credentials");
    }
    const document = providerConfigurationDocument({
      endpoint: providerUrl,
      name: "Loopback turn change fixture",
      api: "openai_responses",
      model: fixtureModel,
      selectedProviderId: providerId,
      selectedModelId: modelId,
      selectedCredentialId: credentialId,
    });
    const configured = await requestRpc(session, counts, "configuration/replace", {
      scope: "user",
      expectedVersion: configuration.cas.userVersion,
      document,
    });
    if (configured?.accepted !== true || typeof configured?.version !== "string") {
      throw new Error("configuration/replace returned an invalid fixture projection");
    }
    const credential = await requestRpc(session, counts, "credential/set", {
      credentialId,
      secret: fixtureSecret,
      expectedVersion: configuration.cas.credentialVersion,
    });
    if (credential?.accepted !== true || credential?.configured !== true) {
      throw new Error("credential/set did not accept the isolated fixture credential");
    }

    const workspace = await requestRpc(session, counts, "workspace/open", {
      cwd: directories.workspace,
      displayName: "Turn change artifact fixture",
    });
    if (workspace?.kind !== "project" || typeof workspace?.workspaceId !== "string") {
      throw new Error("workspace/open returned an invalid project identity");
    }
    const created = await requestRpc(session, counts, "thread/create", {
      cwd: directories.workspace,
      title: "Turn change artifact RPC fixture",
      providerId,
      modelId,
      reasoningLevel: "medium",
      accessMode: "full_access",
      collaborationMode: "default",
    });
    const threadId = created?.threadId;
    if (
      typeof threadId !== "string" ||
      !threadId.startsWith("thr_") ||
      created.workspaceId !== workspace.workspaceId
    ) {
      throw new Error("thread/create did not retain the isolated workspace identity");
    }
    const accepted = await requestRpc(session, counts, "turn/start", {
      threadId,
      operationKey: createOperationKey(),
      content: [
        {
          type: "text",
          text: `Use the write tool once to create ${relativePath} with exactly this content:\n${fileContent}\nThen reply with ${finalMarker}.`,
        },
      ],
    });
    const turnId = accepted?.turnId;
    if (typeof turnId !== "string" || !turnId.startsWith("turn_")) {
      throw new Error("turn/start returned an invalid Turn identity");
    }
    const terminal = await session.waitForEvent(
      "turn/terminal",
      (frame) => frame.params?.turnId === turnId,
      180_000,
    );
    if (terminal.params?.state !== "completed") {
      throw new Error(
        `fixture Turn ended as ${terminal.params?.state ?? "unknown"} with ${terminal.params?.errorCode ?? "UNKNOWN_ERROR"}`,
      );
    }
    if (
      terminal.params?.finalMessage?.text !== finalMarker ||
      fixture.state.toolResponseCount < 1 ||
      fixture.state.finalResponseCount < 1
    ) {
      throw new Error(
        "fixture did not complete the write ToolResult and committed final reply loop",
      );
    }

    const history = await requestRpc(session, counts, "thread/read", { threadId });
    if (history?.threadId !== threadId || !Number.isSafeInteger(history?.revision)) {
      throw new Error("thread/read returned an invalid durable Thread snapshot");
    }
    const turn = Array.isArray(history.turns)
      ? history.turns.find((candidate) => candidate?.turnId === turnId)
      : undefined;
    const changeSet = turn?.changeSet;
    const file = Array.isArray(changeSet?.files)
      ? changeSet.files.find((candidate) => candidate?.path === relativePath)
      : undefined;
    if (
      turn?.status !== "completed" ||
      changeSet?.state !== "complete" ||
      !Array.isArray(changeSet?.incompleteReasons) ||
      changeSet.incompleteReasons.length !== 0 ||
      typeof changeSet?.artifactId !== "string" ||
      file?.status !== "added" ||
      file.binary !== false ||
      file.truncated !== false
    ) {
      throw new Error(
        "thread/read did not restore a complete ChangeSet artifact for the written file",
      );
    }

    const artifact = await requestRpc(session, counts, "turn/change-set/read", {
      threadId,
      turnId,
      artifactId: changeSet.artifactId,
      filePath: relativePath,
    });
    if (
      artifact?.artifactId !== changeSet.artifactId ||
      artifact.filePath !== relativePath ||
      typeof artifact.contentBase64 !== "string" ||
      !/^[0-9a-f]{64}$/u.test(artifact.sha256)
    ) {
      throw new Error("turn/change-set/read returned an invalid artifact projection");
    }
    const diffBytes = Buffer.from(artifact.contentBase64, "base64");
    if (
      diffBytes.toString("base64") !== artifact.contentBase64 ||
      diffBytes.length !== artifact.byteLength
    ) {
      throw new Error("ChangeSet artifact Base64 or UTF-8 byte length did not round-trip");
    }
    if (createHash("sha256").update(diffBytes).digest("hex") !== artifact.sha256) {
      throw new Error("ChangeSet artifact SHA-256 did not match its bytes");
    }
    const diff = new TextDecoder("utf-8", { fatal: true }).decode(diffBytes);
    if (!diff.includes(`+++ b/${relativePath}`)) {
      throw new Error("ChangeSet artifact did not contain the expected unified diff file header");
    }
    for (const line of fileContent.trimEnd().split("\n")) {
      if (!diff.includes(`+${line}`)) {
        throw new Error("ChangeSet artifact diff omitted expected UTF-8 added content");
      }
    }
    const workspaceContent = await readFile(join(directories.workspace, relativePath), "utf8");
    if (workspaceContent !== fileContent) {
      throw new Error("workspace file did not match the successful write Tool call");
    }

    const exit = await session.shutdown();
    shutdown = { code: exit.code, signal: exit.signal };
    if (exit.code !== 0 || exit.signal !== null) {
      throw new Error(`App Server exited with code ${exit.code ?? "none"}`);
    }
    const evidence = {
      status: "TURN_CHANGE_ARTIFACT_RPC_OK",
      jar: runtime.jar,
      jarBytes: runtime.jarBytes,
      directories,
      workspaceId: workspace.workspaceId,
      threadId,
      turnId,
      revision: history.revision,
      artifactId: changeSet.artifactId,
      filePath: relativePath,
      byteLength: artifact.byteLength,
      sha256: artifact.sha256,
      diffContainsExpectedLines: true,
      providerRequests: fixture.state.requestCount,
      rpcMethods: Object.fromEntries(
        [...counts.entries()].sort(([left], [right]) => left.localeCompare(right)),
      ),
      processExit: shutdown,
      evidencePath: join(directories.root, "evidence.json"),
    };
    await writeFile(evidence.evidencePath, `${JSON.stringify(evidence)}\n`, {
      encoding: "utf8",
      flag: "wx",
    });
    return evidence;
  } finally {
    if (session !== undefined && !session.exited) await session.forceClose();
    await closeServer(fixture.server);
  }
}

/** 以一行 JSON 交付保留目录与真实持久化身份，失败时只显示有界的稳定错误。 */
async function main() {
  try {
    const result = await runAcceptance();
    process.stdout.write(`${JSON.stringify(result)}\n`);
  } catch (error) {
    const message = error instanceof Error ? error.message.slice(0, 1_000) : "unknown failure";
    process.stderr.write(`TURN_CHANGE_ARTIFACT_RPC_FAILED: ${message}\n`);
    process.exitCode = 1;
  }
}

await main();
