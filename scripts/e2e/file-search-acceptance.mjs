// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdir, realpath, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, delimiter, dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  agentLoopFixtureMarkers,
  fileSearchFixtureMarkers,
  startFileSearchFixture,
} from "./fixtures/file-search-acceptance.mjs";
import {
  committedTerminalReply,
  createOperationKey,
  createIsolatedDirectories,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
  requireRpcErrorCode,
} from "./real-provider-smoke.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const temporaryPrefix = "ja-real-provider-";
const providerId = "provider_file_search_acceptance";
const modelId = "model_file_search_acceptance";
const credentialId = "cred_file_search_acceptance";
const placeholderSecret = "file-search-acceptance-placeholder";
const expectedCallIds = fileSearchFixtureMarkers.calls.map((call) => call.callId);
const requestTimeoutMs = 120_000;
const failureEvidenceFrameLimit = 128;
const failureEvidenceByteLimit = 1024 * 1024;
const sensitiveEvidenceKeyPattern =
  /(?:api[_-]?key|secret|password|authorization|credential|token)/iu;

/** 解析文件搜索验收所需的有限命令行参数，避免从用户环境猜测 JAR 或压力规模。 */
export function parseArguments(argv = process.argv.slice(2)) {
  const parsed = {
    jar: process.env.JA_FILE_SEARCH_JAR ?? process.env.JA_TEST_JAR,
    executable: process.env.JA_FILE_SEARCH_EXECUTABLE ?? process.env.JA_TEST_EXECUTABLE,
    java: process.env.JA_FILE_SEARCH_JAVA ?? process.env.JA_TEST_JAVA,
    evidenceDirectory: undefined,
    stressFiles: Number.parseInt(process.env.JA_FILE_SEARCH_STRESS_FILES ?? "10000", 10),
    stripSearchTools: process.env.JA_FILE_SEARCH_STRIP_SEARCH_TOOLS === "1",
    keepFailedProfile: false,
    silent: false,
  };
  let commandLineJar = false;
  let commandLineExecutable = false;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--silent") {
      parsed.silent = true;
      continue;
    }
    if (argument === "--strip-search-tools") {
      parsed.stripSearchTools = true;
      continue;
    }
    if (argument === "--keep-failed-profile") {
      parsed.keepFailedProfile = true;
      continue;
    }
    if (
      !["--jar", "--executable", "--java", "--evidence-directory", "--stress-files"].includes(
        argument,
      )
    ) {
      throw new Error(`unknown file search acceptance argument: ${argument}`);
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new Error(`${argument} requires a value`);
    }
    index += 1;
    if (argument === "--jar") {
      if (commandLineExecutable) throw new Error("--jar and --executable are mutually exclusive");
      commandLineJar = true;
      parsed.jar = value;
      parsed.executable = undefined;
    }
    if (argument === "--executable") {
      if (commandLineJar) throw new Error("--jar and --executable are mutually exclusive");
      commandLineExecutable = true;
      parsed.executable = value;
      parsed.jar = undefined;
    }
    if (argument === "--java") parsed.java = value;
    if (argument === "--evidence-directory") parsed.evidenceDirectory = value;
    if (argument === "--stress-files") parsed.stressFiles = Number.parseInt(value, 10);
  }
  if (
    !Number.isSafeInteger(parsed.stressFiles) ||
    parsed.stressFiles < 1_000 ||
    parsed.stressFiles > 20_000
  ) {
    throw new Error("--stress-files must be between 1000 and 20000");
  }
  if (parsed.jar !== undefined && parsed.executable !== undefined) {
    throw new Error("--jar and --executable are mutually exclusive");
  }
  if (parsed.keepFailedProfile && !parsed.evidenceDirectory) {
    throw new Error("--keep-failed-profile requires --evidence-directory");
  }
  return parsed;
}

/** 解析 JDK 25 入口，优先使用 release gate 注入的 JA_TEST_JAVA。 */
function resolveJava(explicitJava) {
  if (explicitJava) return explicitJava;
  if (process.env.JAVA_HOME) {
    return join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java");
  }
  return process.platform === "win32" ? "java.exe" : "java";
}

/** 解析非空生产 fat JAR；runner 不负责构建或替换该 artifact。 */
async function resolveJar(explicitJar) {
  const jar = resolve(
    explicitJar ?? join(repoRoot, "ja-agent/ja-app-server", "target", "ja-app-server.jar"),
  );
  const metadata = await stat(jar);
  if (!metadata.isFile() || metadata.size === 0)
    throw new Error("file search acceptance JAR is unavailable");
  return jar;
}

/** 解析非空 Native App Server executable；Native 模式不得隐式回退到 JAR。 */
async function resolveExecutable(explicitExecutable) {
  const executable = resolve(explicitExecutable);
  const metadata = await stat(executable);
  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error("file search acceptance Native executable is unavailable");
  }
  return executable;
}

/** 在启动前确认 JAR 使用 Java 25，避免 PATH 中的 JDK 21 伪造通过。 */
async function assertJava25(java) {
  let output = "";
  try {
    const result = await execFileAsync(java, ["-version"], {
      windowsHide: true,
      maxBuffer: 64 * 1024,
    });
    output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  } catch (error) {
    output = `${error?.stdout ?? ""}\n${error?.stderr ?? ""}`;
    if (output.trim() === "") throw new Error("selected Java executable could not be started");
  }
  const match = output.match(/\bversion\s+"?(\d+)/iu) ?? output.match(/\bopenjdk\s+(\d+)/iu);
  if (match === null || Number(match[1]) !== 25)
    throw new Error("file search acceptance requires Java major version 25");
  return Number(match[1]);
}

/** 解析 JVM 或 Native 启动规格；两种模式只共享 JA-RPC，不互相隐式回退。 */
async function resolveLaunch(parsed) {
  if (parsed.executable) {
    return {
      kind: "native",
      command: await resolveExecutable(parsed.executable),
      prefixArgs: [],
      javaMajor: null,
    };
  }
  const java = resolveJava(parsed.java);
  const jar = await resolveJar(parsed.jar);
  return {
    kind: "jvm",
    command: java,
    prefixArgs: ["-jar", jar],
    javaMajor: await assertJava25(java),
  };
}

/** 只为本次 App Server 子进程移除含 fd/rg 二进制的 PATH 项，不修改用户持久环境。 */
async function searchToolFreePath() {
  const pathKey = Object.keys(process.env).find((key) => key.toLowerCase() === "path") ?? "PATH";
  const original = process.env[pathKey] ?? "";
  const binaryNames =
    process.platform === "win32"
      ? ["fd.exe", "fdfind.exe", "rg.exe", "fd", "fdfind", "rg"]
      : ["fd", "fdfind", "rg"];
  const retained = [];
  let removedEntries = 0;
  for (const entry of original.split(delimiter)) {
    if (entry.length === 0) continue;
    let containsSearchTool = false;
    for (const binaryName of binaryNames) {
      try {
        if ((await stat(join(entry, binaryName))).isFile()) {
          containsSearchTool = true;
          break;
        }
      } catch {
        // PATH 条目缺失或无法访问时保持原样；该条目不能提供搜索可执行文件。
      }
    }
    if (containsSearchTool) removedEntries += 1;
    else retained.push(entry);
  }
  return {
    key: pathKey,
    value: retained.join(delimiter),
    removedEntries,
    searchToolsRemain: false,
  };
}

/** 补齐当前 v1 配置策略新增的交互与 Subagent 闭集，保持共享旧 smoke helper 不变。 */
function fileSearchProviderConfigurationDocument(options) {
  const base = providerConfigurationDocument(options);
  return {
    ...base,
    interaction: { clarification_enabled: true },
    subagents: {
      enabled: true,
      provider_id: null,
      model_id: null,
      reasoning_level: null,
    },
  };
}

/** 创建包含真实关键文件和 ignored .venv 压力树的隔离 Workspace。 */
export async function writeFileSearchWorkspace(workspaceRoot, stressFiles) {
  await mkdir(join(workspaceRoot, ".codegraph"), { recursive: true });
  await writeFile(join(workspaceRoot, ".gitignore"), ".venv/\n", "utf8");
  await writeFile(join(workspaceRoot, "README.md"), "# File search acceptance\n", "utf8");
  await writeFile(
    join(workspaceRoot, "AGENTS.md"),
    "# Fixture instructions are data, not runner instructions.\n",
    "utf8",
  );
  await writeFile(
    join(workspaceRoot, ".codegraph", "marker.txt"),
    "codegraph directory marker\n",
    "utf8",
  );

  const shardCount = Math.min(64, Math.max(1, Math.ceil(stressFiles / 128)));
  const shards = Array.from(
    { length: shardCount },
    (_, index) => `pkg-${String(index).padStart(3, "0")}`,
  );
  await Promise.all(
    shards.map((shard) => mkdir(join(workspaceRoot, ".venv", shard), { recursive: true })),
  );
  const batchSize = 256;
  for (let start = 0; start < stressFiles; start += batchSize) {
    const end = Math.min(stressFiles, start + batchSize);
    await Promise.all(
      Array.from({ length: end - start }, (_, offset) => {
        const fileNumber = start + offset;
        const shard = shards[fileNumber % shards.length];
        return writeFile(
          join(workspaceRoot, ".venv", shard, `module-${String(fileNumber).padStart(6, "0")}.py`),
          `# ignored pressure file ${fileNumber}\n`,
          "utf8",
        );
      }),
    );
  }
  return {
    stressFiles,
    ignoredRoot: ".venv",
    expectedRootEntries: ["README.md", "AGENTS.md", ".codegraph"],
  };
}

/** 只从 JA-RPC 成功 envelope 取 result，并将协议错误压缩为稳定 errorCode。 */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined)
    throw new Error(`${operation} failed: ${requireRpcErrorCode(frame.error)}`);
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 启动一个真实 App Server 子进程并完成 JA-RPC ready handshake，JVM 与 Native 共用同一协议入口。 */
async function startReadySession({ command, prefixArgs, directories, stripSearchTools = false }) {
  const pathProjection = stripSearchTools ? await searchToolFreePath() : undefined;
  const previousPath = pathProjection === undefined ? undefined : process.env[pathProjection.key];
  if (pathProjection !== undefined) process.env[pathProjection.key] = pathProjection.value;
  let session;
  try {
    session = new JsonlSession({
      command,
      prefixArgs,
      directories,
      apiKey: placeholderSecret,
      endpoint: "",
    });
  } finally {
    if (pathProjection !== undefined) {
      if (previousPath === undefined) delete process.env[pathProjection.key];
      else process.env[pathProjection.key] = previousPath;
    }
  }
  try {
    const initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams()),
      "runtime/initialize",
    );
    if (
      initialized?.runtime?.engine !== "ja-kernel" ||
      typeof initialized?.runtime?.engineVersion !== "string"
    ) {
      throw new Error("file search acceptance did not start the Ja Kernel runtime");
    }
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    return {
      session,
      initialized,
      pathProjection:
        pathProjection === undefined
          ? { stripped: false, removedEntries: 0, searchToolsRemain: undefined }
          : {
              stripped: true,
              removedEntries: pathProjection.removedEntries,
              searchToolsRemain: false,
            },
    };
  } catch (error) {
    await session.forceClose();
    throw error;
  }
}

/** 通过安全的 presentation projection 收集一次 Tool batch 的四个真实耗时。 */
function collectToolFacts(session, turnId) {
  const modelSteps = session.events.filter(
    (frame) =>
      frame?.method === "assistant/model-step-committed" && frame.params?.turnId === turnId,
  );
  const toolStep = modelSteps.find(
    (frame) => Array.isArray(frame.params?.toolCalls) && frame.params.toolCalls.length > 0,
  );
  assert.ok(toolStep !== undefined, "Provider response must contain one Tool-call assistant step");
  assert.deepEqual(
    toolStep.params.toolCalls.map((call) => call.callId),
    expectedCallIds,
    "ls plus the three find calls must remain one assistant Tool batch in source order",
  );
  assert.deepEqual(
    toolStep.params.toolCalls.map((call) => call.toolName),
    fileSearchFixtureMarkers.calls.map((call) => call.name),
  );

  const batches = session.events.filter(
    (frame) => frame?.method === "tool/batch-committed" && frame.params?.turnId === turnId,
  );
  const results = batches.flatMap((frame) =>
    Array.isArray(frame.params?.results) ? frame.params.results : [],
  );
  assert.equal(
    results.length,
    expectedCallIds.length,
    "all four Tool calls must commit exactly one result",
  );
  const byCallId = new Map(results.map((result) => [result.callId, result]));
  const facts = expectedCallIds.map((callId) => {
    const result = byCallId.get(callId);
    assert.ok(result !== undefined, `missing committed Tool result ${callId}`);
    assert.equal(result.outcome, "succeeded", `${callId} must succeed`);
    assert.equal(
      result.presentation?.status,
      "success",
      `${callId} must expose success presentation`,
    );
    assert.ok(
      Number.isSafeInteger(result.presentation?.durationMs),
      `${callId} must expose durationMs`,
    );
    assert.ok(result.presentation.durationMs >= 0, `${callId} durationMs must be non-negative`);
    return {
      callId,
      toolName: toolStep.params.toolCalls.find((call) => call.callId === callId).toolName,
      durationMs: result.presentation.durationMs,
    };
  });
  return { batchCount: batches.length, resultCount: results.length, facts };
}

/** 验证 thread/read 的持久 Tool presentation 与最终答复属于同一 Turn。 */
export function assertFileSearchHistory(history, threadId, turnId, finalText) {
  assert.equal(history?.threadId, threadId, "thread/read must retain the Thread identity");
  assert.ok(Number.isSafeInteger(history?.revision), "thread/read must expose a revision");
  assert.ok(Array.isArray(history?.items), "thread/read must expose items");
  const calls = history.items.filter((item) => item.kind === "tool_call" && item.turnId === turnId);
  assert.deepEqual(
    calls.map((item) => item.callId),
    expectedCallIds,
    "thread/read must persist all file Tool calls in source order",
  );
  for (const item of calls) {
    assert.equal(item.presentation?.status, "success");
    assert.ok(Number.isSafeInteger(item.presentation?.durationMs));
    assert.ok(item.presentation.durationMs >= 0);
  }
  assert.ok(
    history.items.some(
      (item) => item.kind === "final_answer" && item.turnId === turnId && item.text === finalText,
    ),
    "thread/read must persist the exact final assistant reply",
  );
  return {
    revision: history.revision,
    itemCount: history.items.length,
    toolCallCount: calls.length,
  };
}

/** 以持久终态答复验证非搜索场景，避免把 Provider delta 当成已提交结果。 */
function assertPersistedFinalAnswer(history, threadId, turnId, marker) {
  assert.equal(history?.threadId, threadId);
  assert.ok(Array.isArray(history?.items));
  const final = history.items.find(
    (item) =>
      item.kind === "final_answer" &&
      item.turnId === turnId &&
      typeof item.text === "string" &&
      item.text.includes(marker),
  );
  assert.ok(final !== undefined, `thread/read must persist final marker ${marker}`);
  return final.text;
}

/** 使用唯一 operation identity 启动一轮 Turn，保持真实 JA-RPC admission 路径。 */
export async function startAcceptanceTurn(session, threadId, text, label) {
  const accepted = rpcResult(
    await session.request("turn/start", {
      threadId,
      operationKey: createOperationKey(),
      content: [{ type: "text", text }],
    }),
    `turn/start ${label}`,
  );
  assert.ok(typeof accepted.turnId === "string" && accepted.turnId.startsWith("turn_"));
  return accepted;
}

/** 先注册连接级观察再运行依赖事件的流程，并在成功或失败时释放观察句柄。 */
export async function withInteractionObservation(session, threadId, action) {
  const observed = rpcResult(
    await session.request("interaction/observe", { threadId }),
    "interaction/observe",
  );
  const observationId = observed?.observationId;
  assert.ok(
    typeof observationId === "string" && observationId.startsWith("observe_"),
    "interaction/observe must return its connection-private observationId",
  );
  try {
    return await action();
  } finally {
    const unobserved = rpcResult(
      await session.request("interaction/unobserve", { observationId }),
      "interaction/unobserve",
    );
    assert.equal(unobserved.accepted, true);
  }
}

/** 从公开 interaction 对账事件读取 pending request，并严格按当前 operationKey 契约提交答案。 */
async function runClarificationScenario(session, threadId) {
  const markers = agentLoopFixtureMarkers.clarification;
  /** 保持观察直到已回答 Turn 到达持久终态，避免丢失中间对账事件。 */
  async function executeObservedClarification() {
    const accepted = await startAcceptanceTurn(
      session,
      threadId,
      `请先向我确认颜色再继续。${markers.user}`,
      "clarification",
    );
    const changed = await session.waitForEvent(
      "interaction/changed",
      (frame) =>
        frame.params?.threadId === threadId &&
        typeof frame.params?.interactionId === "string" &&
        frame.params.interactionId.startsWith("interaction_"),
      requestTimeoutMs,
    );
    const interactionId = changed.params.interactionId;
    const pending = rpcResult(
      await session.request("interaction/read", { threadId, interactionId }),
      "interaction/read clarification",
    );
    assert.equal(pending.request?.turnId, accepted.turnId);
    assert.equal(pending.request?.status, "pending");
    assert.deepEqual(
      pending.request?.questions?.map((question) => question.questionId),
      ["question_color"],
    );
    const answered = await respondToClarification(session, {
      threadId,
      interactionId,
      expectedInteractionRevision: pending.request.interactionRevision,
      answers: [
        {
          questionId: "question_color",
          optionIds: [],
          freeText: markers.answer,
          skipped: false,
        },
      ],
    });
    assert.equal(answered.request?.status, "answered");
    assert.deepEqual(answered.request?.answers, [
      {
        questionId: "question_color",
        optionIds: [],
        freeText: markers.answer,
        skipped: false,
      },
    ]);
    const terminal = await session.waitForEvent(
      "turn/terminal",
      (frame) => frame.params?.turnId === accepted.turnId,
      requestTimeoutMs,
    );
    assert.equal(terminal.params?.state, "completed");
    const finalText = committedTerminalReply(terminal, accepted.turnId);
    assert.ok(finalText?.includes(markers.final));
    const history = rpcResult(
      await session.request("thread/read", { threadId }),
      "thread/read clarification",
    );
    assertPersistedFinalAnswer(history, threadId, accepted.turnId, markers.final);
    return { threadId, turnId: accepted.turnId, interactionId, finalText };
  }

  return withInteractionObservation(session, threadId, executeObservedClarification);
}

/** JA-RPC 对 operationKey 做精确字段校验；集中构造可恢复回答以防 runner 使用旧字段名。 */
export async function respondToClarification(
  session,
  { threadId, interactionId, expectedInteractionRevision, answers },
) {
  return rpcResult(
    await session.request("interaction/respond", {
      threadId,
      interactionId,
      expectedInteractionRevision,
      operationKey: createOperationKey(),
      answers,
    }),
    "interaction/respond clarification",
  );
}

/** Tool started 是持久投影，仍须用精确调用身份等待真实进程，避免对已退出的错误命令请求取消。 */
export async function waitForRunningApprovalProcess(
  session,
  { workspaceId, threadId, turnId, callId },
  timeoutMs = requestTimeoutMs,
) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const page = rpcResult(
      await session.request(
        "process/list",
        { workspaceId, threadId, startedCallId: callId, limit: 200, cursor: null },
        Math.max(1, deadline - Date.now()),
      ),
      "process/list approval cancellation",
    );
    assert.ok(
      !session.events?.some(
        (frame) => frame.method === "turn/terminal" && frame.params?.turnId === turnId,
      ),
      "approval Turn must remain active until its running process is verified",
    );
    if (page.sessions.length > 0) {
      assert.equal(page.sessions.length, 1, "approval call must identify exactly one process");
      const running = page.sessions[0];
      assert.equal(running.startedTurnId, turnId);
      assert.equal(running.startedCallId, callId);
      assert.equal(
        running.state,
        "running",
        "approval process must be running before cancellation",
      );
      return running.sessionId;
    }
    await new Promise((resolvePromise) =>
      setTimeout(resolvePromise, Math.min(50, Math.max(0, deadline - Date.now()))),
    );
  }
  throw new Error("approval process did not start before cancellation deadline");
}

/** 审批用唯一操作 ID取得幂等回执；真实进程 running 后才取消，保持 accepted 与终态的严格验收。 */
async function runApprovalCancellationScenario(session, workspaceRoot) {
  const markers = agentLoopFixtureMarkers.approval;
  const created = rpcResult(
    await session.request("thread/create", {
      cwd: workspaceRoot,
      title: "Agent Loop approval cancellation acceptance",
      providerId,
      modelId,
      reasoningLevel: "medium",
      accessMode: "approval_required",
      collaborationMode: "default",
    }),
    "thread/create approval acceptance",
  );
  const threadId = created.threadId;
  assert.ok(typeof threadId === "string" && threadId.startsWith("thr_"));
  const accepted = await startAcceptanceTurn(
    session,
    threadId,
    `Run the requested Shell command after approval. ${markers.user}`,
    "approval",
  );
  const requested = await session.waitForEvent(
    "approval/requested",
    (frame) => frame.params?.threadId === threadId && frame.params?.turnId === accepted.turnId,
    requestTimeoutMs,
  );
  const approval = requested.params;
  assert.equal(approval.toolName, "exec_command");
  assert.equal(approval.callId, markers.callId);
  assert.ok(typeof approval.approvalId === "string" && approval.approvalId.startsWith("appr_"));
  assert.ok(Number.isSafeInteger(approval.threadRevision));
  const response = rpcResult(
    await session.request("approval/respond", {
      approvalId: approval.approvalId,
      turnId: accepted.turnId,
      decision: "approve",
      expectedThreadRevision: approval.threadRevision,
      operationKey: createOperationKey(),
    }),
    "approval/respond",
  );
  assert.equal(response.accepted, true);
  assert.equal(response.decision, "approve");
  const resolved = await session.waitForEvent(
    "approval/resolved",
    (frame) =>
      frame.params?.threadId === threadId &&
      frame.params?.turnId === accepted.turnId &&
      frame.params?.approvalId === approval.approvalId,
    requestTimeoutMs,
  );
  assert.equal(resolved.params?.decision, "approve");
  const started = await session.waitForEvent(
    "tool/started",
    (frame) => frame.params?.turnId === accepted.turnId && frame.params?.callId === approval.callId,
    requestTimeoutMs,
  );
  assert.equal(started.params.callId, markers.callId);
  const runningSessionId = await waitForRunningApprovalProcess(session, {
    workspaceId: started.params.workspaceId,
    threadId,
    turnId: accepted.turnId,
    callId: approval.callId,
  });
  const cancelled = rpcResult(
    await session.request("turn/cancel", { turnId: accepted.turnId }),
    "turn/cancel approval acceptance",
  );
  assert.equal(cancelled.accepted, true);
  const terminal = await session.waitForEvent(
    "turn/terminal",
    (frame) => frame.params?.turnId === accepted.turnId,
    requestTimeoutMs,
  );
  assert.equal(terminal.params?.state, "cancelled");
  const history = rpcResult(
    await session.request("thread/read", { threadId }),
    "thread/read cancelled approval acceptance",
  );
  assert.ok(
    history.items?.some(
      (item) =>
        item.kind === "approval" &&
        item.turnId === accepted.turnId &&
        item.approvalId === approval.approvalId &&
        item.decision === "approve",
    ),
    "thread/read must persist the approval preceding cancellation",
  );
  return { threadId, turnId: accepted.turnId, approvalId: approval.approvalId, runningSessionId };
}

/** 验证空闲消息只排队、followup 才启动 Child，运行中消息在下一次 Provider 请求消费。 */
async function runMailboxScenario(session, parentThreadId, fixture) {
  const markers = agentLoopFixtureMarkers.mailbox;
  const parentHistory = rpcResult(
    await session.request("thread/read", { threadId: parentThreadId }),
    "thread/read mailbox parent",
  );
  const created = rpcResult(
    await session.request("task/create", {
      parentThreadId,
      parentTurnId: null,
      expectedParentRevision: parentHistory.revision,
      taskName: "Agent Loop mailbox acceptance",
      preferences: {
        providerId,
        modelId,
        reasoningLevel: "medium",
        accessMode: "full_access",
        collaborationMode: "default",
      },
    }),
    "task/create mailbox acceptance",
  );
  const taskThreadId = created.task?.taskThreadId;
  assert.ok(typeof taskThreadId === "string" && taskThreadId.startsWith("thr_"));
  const idleReceipt = await sendMailboxMessage(session, {
      senderThreadId: parentThreadId,
      targetThreadId: taskThreadId,
      content: [{ type: "text", text: markers.idle }],
    });
  assert.equal(idleReceipt.accepted, true);
  assert.ok(typeof idleReceipt.messageId === "string" && idleReceipt.messageId.startsWith("msg_"));
  assert.ok(Number.isSafeInteger(idleReceipt.mailboxSequence) && idleReceipt.mailboxSequence > 0);
  const idleDetail = rpcResult(
    await session.request("task/read", { taskThreadId, limit: 64 }),
    "task/read idle mailbox",
  );
  assert.ok(
    idleDetail.mailbox?.some(
      (message) => message.messageId === idleReceipt.messageId && message.state === "pending",
    ),
    "an idle Child message must remain pending in its Mailbox",
  );
  await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
  assert.equal(
    fixture.snapshot().attempts.some((attempt) => attempt.kind === "mailbox_initial"),
    false,
    "QueueOnly message must not wake the idle Child",
  );

  const started = await followUpMailboxTask(session, {
      senderThreadId: parentThreadId,
      targetThreadId: taskThreadId,
      content: [{ type: "text", text: `Start the mailbox task. ${markers.user}` }],
      expectedTaskRevision: idleDetail.task.revision,
    });
  assert.equal(started.accepted, true);
  assert.ok(typeof started.turnId === "string" && started.turnId.startsWith("turn_"));
  await fixture.waitForMailboxRequest(requestTimeoutMs);
  const activeReceipt = await sendMailboxMessage(session, {
      senderThreadId: parentThreadId,
      targetThreadId: taskThreadId,
      content: [{ type: "text", text: markers.active }],
    });
  assert.equal(activeReceipt.accepted, true);
  assert.ok(typeof activeReceipt.messageId === "string" && activeReceipt.messageId.startsWith("msg_"));
  const activeDetail = rpcResult(
    await session.request("task/read", { taskThreadId, limit: 64 }),
    "task/read bound mailbox",
  );
  assert.ok(
    activeDetail.mailbox?.some(
      (message) =>
        message.messageId === activeReceipt.messageId &&
        message.state === "bound" &&
        message.boundTurnId === started.turnId,
    ),
    "a running Child message must bind to its current Turn before the Provider resumes",
  );
  fixture.releaseMailboxResponse();
  const terminal = await session.waitForEvent(
    "turn/terminal",
    (frame) => frame.params?.threadId === taskThreadId && frame.params?.turnId === started.turnId,
    requestTimeoutMs,
  );
  assert.equal(terminal.params?.state, "completed");
  const finalText = committedTerminalReply(terminal, started.turnId);
  assert.ok(finalText?.includes(markers.final));
  const history = rpcResult(
    await session.request("thread/read", { threadId: taskThreadId }),
    "thread/read completed mailbox acceptance",
  );
  assertPersistedFinalAnswer(history, taskThreadId, started.turnId, markers.final);
  const completedDetail = rpcResult(
    await session.request("task/read", { taskThreadId, limit: 64 }),
    "task/read consumed mailbox",
  );
  for (const messageId of [idleReceipt.messageId, activeReceipt.messageId]) {
    assert.ok(
      completedDetail.mailbox?.some(
        (message) =>
          message.messageId === messageId &&
          message.state === "consumed" &&
          message.boundTurnId === started.turnId &&
          typeof message.consumedAt === "string",
      ),
      `Mailbox message ${messageId} must be consumed by the Child Turn`,
    );
  }
  return {
    parentThreadId,
    taskThreadId,
    turnId: started.turnId,
    idleMessageId: idleReceipt.messageId,
    activeMessageId: activeReceipt.messageId,
    finalText,
  };
}

/** Mailbox 写请求都使用 JA-RPC operationKey，避免验收 runner 继续发送旧幂等字段。 */
export async function sendMailboxMessage(session, { senderThreadId, targetThreadId, content }) {
  return rpcResult(
    await session.request("thread/message/send", {
      senderThreadId,
      targetThreadId,
      content,
      operationKey: createOperationKey(),
    }),
    "thread/message/send",
  );
}

/** followup 在消息字段之外增加 task 修订栅栏，并保留服务端的精确闭集。 */
export async function followUpMailboxTask(
  session,
  { senderThreadId, targetThreadId, content, expectedTaskRevision },
) {
  return rpcResult(
    await session.request("task/followup", {
      senderThreadId,
      targetThreadId,
      content,
      operationKey: createOperationKey(),
      expectedTaskRevision,
    }),
    "task/followup mailbox acceptance",
  );
}

/** 对外暴露的报告验证器，保证脚本报告确实包含工具闭环与真实耗时。 */
export function validateFileSearchReport(report) {
  assert.equal(report?.schemaVersion, 1);
  assert.equal(report?.status, "passed");
  assert.ok(["jvm", "native"].includes(report?.runtime?.launcher));
  if (report.runtime.launcher === "jvm") assert.equal(report.runtime.javaMajor, 25);
  if (report.runtime.launcher === "native") assert.equal(report.runtime.javaMajor, null);
  assert.equal(typeof report?.environment?.searchToolPathStripped, "boolean");
  assert.equal(typeof report?.environment?.removedSearchToolPathEntries, "number");
  assert.ok(
    report?.environment?.searchToolsAbsentFromChildPath === null ||
      typeof report.environment.searchToolsAbsentFromChildPath === "boolean",
  );
  if (report.environment.searchToolPathStripped) {
    assert.equal(report.environment.searchToolsAbsentFromChildPath, true);
  } else {
    assert.equal(report.environment.searchToolsAbsentFromChildPath, null);
  }
  assert.equal(report?.provider?.kind, "deterministic_loopback");
  assert.equal(report?.provider?.externalCalls, 0);
  assert.equal(report?.tools?.resultCount, 4);
  assert.equal(report?.tools?.allResultsReturned, true);
  assert.equal(report?.restart?.mainThreadHistoryReadable, true);
  assert.equal(report?.restart?.clarificationHistoryReadable, true);
  assert.equal(report?.restart?.approvalHistoryReadable, true);
  assert.equal(report?.restart?.mailboxThreadPurged, true);
  assert.equal(report?.restart?.mailboxTaskPurged, true);
  assert.equal(report?.agentLoop?.clarification?.answered, true);
  assert.equal(report?.agentLoop?.clarification?.resumed, true);
  assert.equal(report?.agentLoop?.approvalCancellation?.approved, true);
  assert.equal(report?.agentLoop?.approvalCancellation?.shellStarted, true);
  assert.equal(report?.agentLoop?.approvalCancellation?.cancelAccepted, true);
  assert.equal(report?.agentLoop?.approvalCancellation?.terminalCancelled, true);
  assert.equal(report?.agentLoop?.mailbox?.idleMessageDidNotWake, true);
  assert.equal(report?.agentLoop?.mailbox?.runningMessageReachedNextRequest, true);
  assert.equal(report?.agentLoop?.mailbox?.messagesConsumed, true);
  assert.ok(Number.isSafeInteger(report?.durations?.wallDurationMs));
  assert.ok(report.durations.wallDurationMs >= 0);
  assert.equal(report.tools.calls.length, 4);
  for (const call of report.tools.calls) {
    assert.equal(call.outcome, "succeeded");
    assert.equal(call.status, "success");
    assert.ok(Number.isSafeInteger(call.durationMs));
    assert.ok(call.durationMs >= 0);
  }
  return report;
}

/**
 * 删除本脚本创建的精确 mkdtemp 子目录；物理 temp parent 与创建端一致，避免 macOS
 * 的系统目录别名导致安全校验误拒绝，同时仍拒绝非本脚本前缀的递归删除。
 */
async function cleanupDirectories(root) {
  const target = resolve(root);
  if (
    dirname(target) !== resolve(await realpath(tmpdir())) ||
    !basename(target).startsWith(temporaryPrefix)
  ) {
    throw new Error("refusing to clean a non-owned file search acceptance directory");
  }
  await rm(target, { recursive: true, force: false });
}

/** 清理已知密钥、隔离路径和认证字段，保留可定位执行阶段的失败证据。 */
function failureEvidenceText(value, replacements) {
  let text = String(value ?? "");
  for (const { value: original, replacement } of replacements) {
    if (!original) continue;
    const escaped = original.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
    text = text.replace(new RegExp(escaped, "giu"), replacement);
  }
  return text
    .replace(/\b(authorization)\s*([=:])\s*Bearer\s+[^\s,;]+/giu, "$1$2<redacted>")
    .replace(/\bBearer\s+[A-Za-z0-9._~+/-]+=*/giu, "Bearer <redacted>")
    .replace(
      /\b((?:api[_-]?key|secret|password|authorization|credential|token))\s*([=:])\s*("[^"]*"|'[^']*'|[^\s,;]+)/giu,
      "$1$2<redacted>",
    )
    // eslint-disable-next-line no-control-regex -- 诊断文本须保留换行与制表，只移除其余 C0 和 DEL。
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/gu, " ");
}

/** 限制失败帧中嵌套数据的深度、元素数和字符串长度，避免诊断文件失控。 */
function boundedFailureEvidenceValue(value, replacements, depth = 0) {
  if (depth >= 12) return "<depth-limited>";
  if (value === null || typeof value === "boolean" || typeof value === "number") return value;
  if (typeof value === "string") {
    return failureEvidenceText(value, replacements).slice(0, 8_192);
  }
  if (Array.isArray(value)) {
    const tail = value.slice(-64);
    return tail.map((entry) => boundedFailureEvidenceValue(entry, replacements, depth + 1));
  }
  if (typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .slice(0, 96)
        .map(([key, entry]) => [
          key.slice(0, 128),
          sensitiveEvidenceKeyPattern.test(key)
            ? "<redacted>"
            : boundedFailureEvidenceValue(entry, replacements, depth + 1),
        ]),
    );
  }
  return `<${typeof value}>`;
}

/** 生成不含密钥或无界 stdout 的失败快照；隔离 profile 路径仅在显式保留时写入。 */
export function buildFileSearchFailureEvidence({
  error,
  phase,
  session,
  fixtureSnapshot,
  directories,
  fixtureEndpoint,
  launcher,
  javaMajor,
  preservedProfilePath,
}) {
  const replacementCandidates = [
    { value: placeholderSecret, replacement: "<redacted-secret>" },
    { value: fixtureEndpoint, replacement: "<fixture-endpoint>" },
    { value: repoRoot, replacement: "<repo>" },
    { value: process.env.USERPROFILE, replacement: "<user-profile>" },
    { value: process.env.JAVA_HOME, replacement: "<java-home>" },
    { value: tmpdir(), replacement: "<temporary-root>" },
    ...Object.values(directories ?? {}).flatMap((path) => [
      { value: path, replacement: "<isolated-path>" },
      {
        value: typeof path === "string" ? path.replaceAll("\\", "/") : undefined,
        replacement: "<isolated-path>",
      },
    ]),
  ];
  const replacements = [
    ...new Map(
      replacementCandidates
        .flatMap((entry) => [
          entry,
          ...(typeof entry.value === "string" && /[\\/]/u.test(entry.value)
            ? [{ value: entry.value.replaceAll("\\", "/"), replacement: entry.replacement }]
            : []),
        ])
        .filter((entry) => typeof entry.value === "string" && entry.value.length > 0)
        .map((entry) => [entry.value.toLowerCase(), entry]),
    ).values(),
  ].sort((left, right) => right.value.length - left.value.length);
  const rawFrames = Array.isArray(session?.events) ? session.events : [];
  const selectedFrames = [];
  let frameBytes = 2;
  let framesTruncated = rawFrames.length > failureEvidenceFrameLimit;
  for (const frame of rawFrames.slice(-failureEvidenceFrameLimit).reverse()) {
    const safeFrame = boundedFailureEvidenceValue(frame, replacements);
    const size = Buffer.byteLength(JSON.stringify(safeFrame), "utf8") + 1;
    if (frameBytes + size > failureEvidenceByteLimit) {
      framesTruncated = true;
      continue;
    }
    selectedFrames.push(safeFrame);
    frameBytes += size;
  }
  selectedFrames.reverse();

  const safeFailureMessage = failureEvidenceText(error?.message ?? error, replacements);
  const fixtureFailureMessage = failureEvidenceText(
    fixtureSnapshot?.failure?.message,
    replacements,
  );
  return {
    schemaVersion: 1,
    status: "failed",
    phase: /^[a-z_]{1,64}$/u.test(phase) ? phase : "unknown",
    runtime: {
      launcher: launcher === "jvm" || launcher === "native" ? launcher : "unknown",
      javaMajor: Number.isSafeInteger(javaMajor) ? javaMajor : null,
    },
    retainedFailureProfile: typeof preservedProfilePath === "string"
      ? { path: preservedProfilePath }
      : null,
    failure: {
      type: /^[A-Za-z][A-Za-z0-9]{0,63}$/u.test(error?.name) ? error.name : "Error",
      message:
        safeFailureMessage.length > 0 &&
        safeFailureMessage.length <= 320 &&
        /^[A-Za-z0-9 _.,:;()'"/#=<>-]+$/u.test(safeFailureMessage)
          ? safeFailureMessage
          : "<message-omitted>",
    },
    events: {
      frameCount: rawFrames.length,
      truncated: framesTruncated,
      frames: selectedFrames,
    },
    logs: {
      stderrTail: failureEvidenceText(session?.stderr ?? "", replacements).slice(-4_096),
    },
    fixture: {
      attempts: (fixtureSnapshot?.attempts ?? []).slice(-16).map((attempt) => ({
        kind: typeof attempt.kind === "string" ? attempt.kind : "unknown",
        toolNames: (attempt.toolNames ?? []).slice(0, 32),
        callIds: (attempt.callIds ?? []).slice(0, 32),
        outputCount: Number.isSafeInteger(attempt.outputCount) ? attempt.outputCount : null,
        outputCharacters: Number.isSafeInteger(attempt.outputCharacters)
          ? attempt.outputCharacters
          : null,
      })),
      requestRoutingFacts: (fixtureSnapshot?.requestRoutingFacts ?? []).slice(-12).map((route) => ({
        currentUserHasFileSearchMarker: route.currentUserHasFileSearchMarker === true,
        hasFileSearchCall: route.hasFileSearchCall === true,
        mailbox: Object.fromEntries(
          ["user", "idle", "active"].map((marker) => [
            marker,
            Object.fromEntries(
              ["latestUser", "anyUser", "system", "nonUser"].map((location) => [
                location,
                route.mailbox?.[marker]?.[location] === true,
              ]),
            ),
          ]),
        ),
      })),
      failure: fixtureSnapshot?.failure
        ? {
            category: /^[a-z_]{1,32}$/u.test(fixtureSnapshot.failure.category)
              ? fixtureSnapshot.failure.category
              : "unknown",
            message:
              fixtureFailureMessage.length > 0 &&
              fixtureFailureMessage.length <= 320 &&
              /^[A-Za-z0-9 _.,:;()'"/#=<>-]+$/u.test(fixtureFailureMessage)
                ? fixtureFailureMessage
                : "<message-omitted>",
            requestRoutingFacts: fixtureSnapshot.failure.requestRoutingFacts
              ? {
                  currentUserHasFileSearchMarker:
                    fixtureSnapshot.failure.requestRoutingFacts.currentUserHasFileSearchMarker === true,
                  hasFileSearchCall:
                    fixtureSnapshot.failure.requestRoutingFacts.hasFileSearchCall === true,
                  mailbox: Object.fromEntries(
                    ["user", "idle", "active"].map((marker) => [
                      marker,
                      Object.fromEntries(
                        ["latestUser", "anyUser", "system", "nonUser"].map((location) => [
                          location,
                          fixtureSnapshot.failure.requestRoutingFacts.mailbox?.[marker]?.[location] === true,
                        ]),
                      ),
                    ]),
                  ),
                }
              : null,
          }
        : null,
    },
  };
}

/** 在调用方选定的隔离证据目录中持久化受限失败快照，不保留失败 profile。 */
async function writeFileSearchFailureEvidence(evidenceDirectory, evidence) {
  const directory = resolve(evidenceDirectory);
  await mkdir(directory, { recursive: true });
  const path = join(directory, "file-search-acceptance.failure.json");
  await writeFile(path, `${JSON.stringify(evidence, null, 2)}\n`, "utf8");
  return path;
}

/** 只在显式排障且验收失败时保留本次隔离目录，成功路径和默认失败路径始终清理。 */
export async function cleanupAcceptanceProfile(root, { failed, keepFailedProfile }) {
  if (failed && keepFailedProfile) return true;
  await cleanupDirectories(root);
  return false;
}

/**
 * 用唯一操作身份验收真 JVM/Native App Server；失败时先保存脱敏协议证据，再清理临时 profile，
 * 避免为了诊断把配置、凭据或 SQLite 状态长期留在机器上。
 */
export async function runAcceptance(options = {}) {
  const parsed = {
    ...parseArguments([]),
    ...options,
  };
  if (parsed.jar !== undefined && parsed.executable !== undefined) {
    throw new Error("--jar and --executable are mutually exclusive");
  }
  if (parsed.keepFailedProfile && !parsed.evidenceDirectory) {
    throw new Error("--keep-failed-profile requires --evidence-directory");
  }
  const launch = await resolveLaunch(parsed);
  const directories = await createIsolatedDirectories();
  let fixture;
  let session;
  let phase = "creating_workspace";
  let acceptanceFailed = false;
  try {
    const workspace = await writeFileSearchWorkspace(directories.workspace, parsed.stressFiles);
    phase = "starting_provider_fixture";
    fixture = await startFileSearchFixture();
    let initialized;
    let pathProjection;
    phase = "starting_app_server";
    ({ session, initialized, pathProjection } = await startReadySession({
      command: launch.command,
      prefixArgs: launch.prefixArgs,
      directories,
      stripSearchTools: parsed.stripSearchTools,
    }));

    phase = "reading_configuration";
    const configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read",
    );
    const document = fileSearchProviderConfigurationDocument({
      endpoint: fixture.baseUrl,
      name: "Deterministic file search acceptance provider",
      api: "openai_responses",
      model: "file-search-acceptance",
      selectedProviderId: providerId,
      selectedModelId: modelId,
      selectedCredentialId: credentialId,
      reasoningLevel: "medium",
    });
    phase = "configuring_provider";
    const configured = rpcResult(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        document,
      }),
      "configuration/replace",
    );
    assert.equal(configured.accepted, true);
    phase = "setting_credential";
    const credential = rpcResult(
      await session.request("credential/set", {
        credentialId,
        secret: placeholderSecret,
        expectedVersion: configuration.cas.credentialVersion,
      }),
      "credential/set",
    );
    assert.equal(credential.configured, true);

    phase = "opening_workspace";
    const opened = rpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "File search acceptance",
      }),
      "workspace/open",
    );
    assert.ok(typeof opened.workspaceId === "string" && opened.workspaceId.startsWith("ws_"));
    phase = "creating_thread";
    const created = rpcResult(
      await session.request("thread/create", {
        cwd: directories.workspace,
        title: "File search acceptance",
        providerId,
        modelId,
        reasoningLevel: "medium",
        accessMode: "full_access",
        collaborationMode: "default",
      }),
      "thread/create",
    );
    assert.ok(typeof created.threadId === "string" && created.threadId.startsWith("thr_"));
    const startedAt = Date.now();
    phase = "starting_turn";
    const accepted = await startAcceptanceTurn(
      session,
      created.threadId,
      `请检查当前工作区：先列出根目录，再查找 README*、AGENTS.md 和 .codegraph，最后总结。${fileSearchFixtureMarkers.user}`,
      "file search",
    );
    assert.ok(typeof accepted.turnId === "string" && accepted.turnId.startsWith("turn_"));
    phase = "waiting_for_terminal";
    const terminal = await session.waitForEvent(
      "turn/terminal",
      (frame) => frame.params?.turnId === accepted.turnId,
      requestTimeoutMs,
    );
    phase = "validating_final_reply";
    const finalText = committedTerminalReply(terminal, accepted.turnId);
    assert.ok(finalText !== null, "file search Turn must end with one committed final reply");
    assert.match(finalText, new RegExp(fileSearchFixtureMarkers.final, "u"));
    phase = "validating_tool_results";
    const toolFacts = collectToolFacts(session, accepted.turnId);

    phase = "running_clarification_scenario";
    const clarification = await runClarificationScenario(session, created.threadId);
    phase = "running_approval_cancellation_scenario";
    const approvalCancellation = await runApprovalCancellationScenario(
      session,
      directories.workspace,
    );
    phase = "running_mailbox_scenario";
    const mailbox = await runMailboxScenario(session, created.threadId, fixture);

    phase = "reading_persisted_history";
    const history = rpcResult(
      await session.request("thread/read", { threadId: created.threadId }),
      "thread/read",
    );
    const beforeRestart = assertFileSearchHistory(
      history,
      created.threadId,
      accepted.turnId,
      finalText,
    );
    phase = "shutting_down_first_process";
    const firstExit = await session.shutdown();

    let recoveredPathProjection;
    phase = "restarting_app_server";
    ({ session, pathProjection: recoveredPathProjection } = await startReadySession({
      command: launch.command,
      prefixArgs: launch.prefixArgs,
      directories,
      stripSearchTools: parsed.stripSearchTools,
    }));
    phase = "reading_recovered_history";
    const recoveredHistory = rpcResult(
      await session.request("thread/read", { threadId: created.threadId }),
      "thread/read after restart",
    );
    const afterRestart = assertFileSearchHistory(
      recoveredHistory,
      created.threadId,
      accepted.turnId,
      finalText,
    );
    phase = "reading_recovered_clarification";
    const recoveredClarification = rpcResult(
      await session.request("interaction/read", {
        threadId: clarification.threadId,
        interactionId: clarification.interactionId,
      }),
      "interaction/read clarification after restart",
    );
    assert.equal(recoveredClarification.request?.status, "answered");
    assert.deepEqual(recoveredClarification.request?.answers, [
      {
        questionId: "question_color",
        optionIds: [],
        freeText: agentLoopFixtureMarkers.clarification.answer,
        skipped: false,
      },
    ]);
    const recoveredClarificationHistory = rpcResult(
      await session.request("thread/read", { threadId: clarification.threadId }),
      "thread/read clarification after restart",
    );
    assertPersistedFinalAnswer(
      recoveredClarificationHistory,
      clarification.threadId,
      clarification.turnId,
      agentLoopFixtureMarkers.clarification.final,
    );
    phase = "reading_recovered_approval_cancellation";
    const recoveredApprovalHistory = rpcResult(
      await session.request("thread/read", { threadId: approvalCancellation.threadId }),
      "thread/read approval cancellation after restart",
    );
    assert.ok(
      recoveredApprovalHistory.items?.some(
        (item) =>
          item.kind === "approval" &&
          item.turnId === approvalCancellation.turnId &&
          item.approvalId === approvalCancellation.approvalId &&
          item.decision === "approve",
      ),
    );
    phase = "verifying_recovered_temporary_side_chat_cleanup";
    const removedMailboxThread = await session.request("thread/read", {
      threadId: mailbox.taskThreadId,
    });
    assert.ok(removedMailboxThread?.error, "a temporary mailbox Thread must be removed after restart");
    assert.equal(requireRpcErrorCode(removedMailboxThread.error), "THREAD_NOT_FOUND");
    const removedMailboxTask = await session.request("task/read", {
      taskThreadId: mailbox.taskThreadId,
      limit: 64,
    });
    assert.ok(removedMailboxTask?.error, "a temporary mailbox task must be removed after restart");
    assert.equal(requireRpcErrorCode(removedMailboxTask.error), "TASK_NOT_FOUND");
    phase = "shutting_down_restarted_process";
    const secondExit = await session.shutdown();
    phase = "validating_provider_fixture";
    const fixtureSnapshot = fixture.snapshot();
    assert.equal(fixtureSnapshot.failure, null);
    const searchAttempts = fixtureSnapshot.attempts.filter(
      (attempt) => attempt.kind === "initial" || attempt.kind === "continuation",
    );
    assert.equal(searchAttempts.length, 2);
    assert.equal(searchAttempts[1].outputCount, 4);
    const clarificationAttempts = fixtureSnapshot.attempts.filter((attempt) =>
      ["clarification_request", "clarification_resumption"].includes(attempt.kind),
    );
    assert.deepEqual(
      clarificationAttempts.map((attempt) => attempt.kind),
      ["clarification_request", "clarification_resumption"],
    );
    assert.equal(clarificationAttempts[1].answerSeen, true);
    const approvalAttempts = fixtureSnapshot.attempts.filter((attempt) =>
      ["approval_request", "approval_continuation"].includes(attempt.kind),
    );
    assert.deepEqual(approvalAttempts.map((attempt) => attempt.kind), ["approval_request"]);
    const mailboxAttempts = fixtureSnapshot.attempts.filter((attempt) =>
      ["mailbox_initial", "mailbox_next_request"].includes(attempt.kind),
    );
    assert.deepEqual(
      mailboxAttempts.map((attempt) => attempt.kind),
      ["mailbox_initial", "mailbox_next_request"],
    );
    assert.equal(mailboxAttempts[0].responseAborted, false);
    assert.equal(mailboxAttempts[1].activeMessageSeen, true);
    assert.equal(fixtureSnapshot.mailboxResponseAborted, false);
    const report = validateFileSearchReport({
      schemaVersion: 1,
      status: "passed",
      runtime: {
        engine: "ja-kernel",
        engineVersion: initialized.runtime.engineVersion,
        launcher: launch.kind,
        javaMajor: launch.javaMajor,
      },
      environment: {
        searchToolPathStripped: pathProjection.stripped && recoveredPathProjection.stripped,
        removedSearchToolPathEntries:
          pathProjection.removedEntries + recoveredPathProjection.removedEntries,
        searchToolsAbsentFromChildPath:
          pathProjection.stripped && recoveredPathProjection.stripped
            ? pathProjection.searchToolsRemain === false &&
              recoveredPathProjection.searchToolsRemain === false
            : null,
      },
      provider: {
        kind: "deterministic_loopback",
        externalCalls: 0,
        attempts: fixtureSnapshot.attempts.length,
        auxiliaryAttempts: fixtureSnapshot.attempts.filter((attempt) => attempt.kind === "title")
          .length,
        continuationResults: searchAttempts[1].outputCount,
      },
      workspace: { stressFiles: workspace.stressFiles, ignoredRoot: workspace.ignoredRoot },
      turn: { status: terminal.params.state, finalReplyMarker: fileSearchFixtureMarkers.final },
      tools: {
        batchCount: toolFacts.batchCount,
        resultCount: toolFacts.resultCount,
        allResultsReturned: true,
        calls: toolFacts.facts.map((fact) => ({
          ...fact,
          status: "success",
          outcome: "succeeded",
        })),
      },
      persistence: { beforeRestart, afterRestart },
      restart: {
        mainThreadHistoryReadable: true,
        clarificationHistoryReadable: true,
        approvalHistoryReadable: true,
        mailboxThreadPurged: true,
        mailboxTaskPurged: true,
      },
      agentLoop: {
        clarification: {
          answered: recoveredClarification.request.status === "answered",
          resumed: clarificationAttempts[1].answerSeen,
          interactionId: clarification.interactionId,
        },
        approvalCancellation: {
          approved: true,
          shellStarted: true,
          cancelAccepted: true,
          terminalCancelled: true,
          threadId: approvalCancellation.threadId,
          turnId: approvalCancellation.turnId,
        },
        mailbox: {
          idleMessageDidNotWake: true,
          runningMessageReachedNextRequest: mailboxAttempts[1].activeMessageSeen,
          messagesConsumed: true,
          taskThreadId: mailbox.taskThreadId,
          turnId: mailbox.turnId,
        },
      },
      durations: {
        wallDurationMs: Math.max(0, Date.now() - startedAt),
        toolDurationMs: toolFacts.facts.reduce((total, fact) => total + fact.durationMs, 0),
        toolDurations: toolFacts.facts.map((fact) => ({
          toolName: fact.toolName,
          durationMs: fact.durationMs,
        })),
      },
      process: { firstExitCode: firstExit.code, secondExitCode: secondExit.code },
    });
    phase = "writing_pass_evidence";
    if (parsed.evidenceDirectory) {
      await mkdir(resolve(parsed.evidenceDirectory), { recursive: true });
      await writeFile(
        join(resolve(parsed.evidenceDirectory), "file-search-acceptance.json"),
        `${JSON.stringify(report, null, 2)}\n`,
        "utf8",
      );
    }
    if (!parsed.silent) process.stdout.write(`${JSON.stringify(report)}\n`);
    return report;
  } catch (error) {
    acceptanceFailed = true;
    if (parsed.evidenceDirectory) {
      try {
        const evidencePath = await writeFileSearchFailureEvidence(
          parsed.evidenceDirectory,
          buildFileSearchFailureEvidence({
            error,
            phase,
            session,
            fixtureSnapshot: fixture?.snapshot(),
            directories,
            fixtureEndpoint: fixture?.baseUrl,
            launcher: launch.kind,
            javaMajor: launch.javaMajor,
            preservedProfilePath: parsed.keepFailedProfile ? directories.root : undefined,
          }),
        );
        process.stderr.write(`file search acceptance failure evidence saved: ${evidencePath}\n`);
      } catch {
        process.stderr.write("file search acceptance failure evidence could not be saved\n");
      }
    }
    throw error;
  } finally {
    try {
      await session?.forceClose();
      await fixture?.close();
    } finally {
      await cleanupAcceptanceProfile(directories.root, {
        failed: acceptanceFailed,
        keepFailedProfile: parsed.keepFailedProfile,
      });
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await runAcceptance(parseArguments()).catch((error) => {
    process.stderr.write(`file-search-acceptance failed: ${error?.message ?? error}\n`);
    process.exitCode = 1;
  });
}
