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
  fileSearchFixtureMarkers,
  startFileSearchFixture,
} from "./fixtures/file-search-acceptance.mjs";
import {
  committedTerminalReply,
  createClientOperationId,
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

/** 解析文件搜索验收所需的有限命令行参数，避免从用户环境猜测 JAR 或压力规模。 */
export function parseArguments(argv = process.argv.slice(2)) {
  const parsed = {
    jar: process.env.JA_FILE_SEARCH_JAR ?? process.env.JA_TEST_JAR,
    executable: process.env.JA_FILE_SEARCH_EXECUTABLE ?? process.env.JA_TEST_EXECUTABLE,
    java: process.env.JA_FILE_SEARCH_JAVA ?? process.env.JA_TEST_JAVA,
    evidenceDirectory: undefined,
    stressFiles: Number.parseInt(process.env.JA_FILE_SEARCH_STRESS_FILES ?? "10000", 10),
    stripSearchTools: process.env.JA_FILE_SEARCH_STRIP_SEARCH_TOOLS === "1",
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
  const jar = resolve(explicitJar ?? join(repoRoot, "app-server", "target", "ja-app-server.jar"));
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
        // A missing or inaccessible PATH entry remains unchanged; it cannot provide a search executable.
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
  assert.equal(report?.persistence?.restartRecovered, true);
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

/**
 * Run a real JVM/Native App Server acceptance with a unique v1 operation identity so the tested
 * mutation follows production deduplication semantics before checking search and restart recovery.
 */
export async function runAcceptance(options = {}) {
  const parsed = {
    ...parseArguments([]),
    ...options,
  };
  if (parsed.jar !== undefined && parsed.executable !== undefined) {
    throw new Error("--jar and --executable are mutually exclusive");
  }
  const launch = await resolveLaunch(parsed);
  const directories = await createIsolatedDirectories();
  let fixture;
  let session;
  try {
    const workspace = await writeFileSearchWorkspace(directories.workspace, parsed.stressFiles);
    fixture = await startFileSearchFixture();
    let initialized;
    let pathProjection;
    ({ session, initialized, pathProjection } = await startReadySession({
      command: launch.command,
      prefixArgs: launch.prefixArgs,
      directories,
      stripSearchTools: parsed.stripSearchTools,
    }));

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
    const configured = rpcResult(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: configuration.cas.userVersion,
        document,
      }),
      "configuration/replace",
    );
    assert.equal(configured.accepted, true);
    const credential = rpcResult(
      await session.request("credential/set", {
        credentialId,
        secret: placeholderSecret,
        expectedVersion: configuration.cas.credentialVersion,
      }),
      "credential/set",
    );
    assert.equal(credential.configured, true);

    const opened = rpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "File search acceptance",
      }),
      "workspace/open",
    );
    assert.ok(typeof opened.workspaceId === "string" && opened.workspaceId.startsWith("ws_"));
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
    const accepted = rpcResult(
      await session.request("turn/start", {
        threadId: created.threadId,
        clientOperationId: createClientOperationId(),
        content: [
          {
            type: "text",
            text: `请检查当前工作区：先列出根目录，再查找 README*、AGENTS.md 和 .codegraph，最后总结。${fileSearchFixtureMarkers.user}`,
          },
        ],
      }),
      "turn/start",
    );
    assert.ok(typeof accepted.turnId === "string" && accepted.turnId.startsWith("turn_"));
    const terminal = await session.waitForEvent(
      "turn/terminal",
      (frame) => frame.params?.turnId === accepted.turnId,
      requestTimeoutMs,
    );
    const finalText = committedTerminalReply(terminal, accepted.turnId);
    assert.ok(finalText !== null, "file search Turn must end with one committed final reply");
    assert.match(finalText, new RegExp(fileSearchFixtureMarkers.final, "u"));
    const toolFacts = collectToolFacts(session, accepted.turnId);
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
    const firstExit = await session.shutdown();

    let recoveredPathProjection;
    ({ session, pathProjection: recoveredPathProjection } = await startReadySession({
      command: launch.command,
      prefixArgs: launch.prefixArgs,
      directories,
      stripSearchTools: parsed.stripSearchTools,
    }));
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
    const secondExit = await session.shutdown();
    const fixtureSnapshot = fixture.snapshot();
    assert.equal(fixtureSnapshot.failure, null);
    const searchAttempts = fixtureSnapshot.attempts.filter(
      (attempt) => attempt.kind === "initial" || attempt.kind === "continuation",
    );
    assert.equal(searchAttempts.length, 2);
    assert.equal(searchAttempts[1].outputCount, 4);
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
      persistence: { beforeRestart, afterRestart, restartRecovered: true },
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
  } finally {
    await session?.forceClose();
    await fixture?.close();
    await cleanupDirectories(directories.root);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await runAcceptance(parseArguments()).catch((error) => {
    process.stderr.write(`file-search-acceptance failed: ${error?.message ?? error}\n`);
    process.exitCode = 1;
  });
}
