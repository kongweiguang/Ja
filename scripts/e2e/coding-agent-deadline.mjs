// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { access, mkdir, readFile, realpath, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import {
  committedTerminalReply,
  createOperationKey,
  createIsolatedDirectories,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
  requireRpcErrorCode,
} from "./real-provider-smoke.mjs";
import {
  codingAgentDeadlineMarkers,
  startCodingAgentDeadlineProvider,
} from "./coding-agent-deadline-provider.mjs";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const providerId = "provider_coding_agent_deadline";
const modelId = "model_coding_agent_deadline";
const credentialId = "cred_coding_agent_deadline";
const placeholderSecret = "coding-agent-deadline-loopback-placeholder";
const runTimeoutMs = 60_000;
const expectedMainCalls = Object.freeze([
  ["call_deadline_write", "write"],
  ["call_deadline_read", "read"],
  ["call_deadline_grep", "grep"],
  ["call_deadline_find", "find"],
  ["call_deadline_ls", "ls"],
  ["call_deadline_edit", "edit"],
  ["call_deadline_shell", "exec_command"],
]);

/** 只解析有界的本地 JAR 输入，避免 runner 猜测可执行文件或改动用户配置。 */
export function parseArguments(argv = process.argv.slice(2)) {
  const parsed = {
    jar: process.env.JA_CODING_AGENT_JAR,
    java: process.env.JA_TEST_JAVA,
    evidenceDirectory: process.env.JA_CODING_AGENT_EVIDENCE_DIR,
    expectDeadlineRegression: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--expect-deadline-regression") {
      parsed.expectDeadlineRegression = true;
      continue;
    }
    if (!["--jar", "--java", "--evidence-directory"].includes(argument)) {
      throw new Error(`unknown coding-agent deadline argument: ${argument}`);
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`${argument} requires a value`);
    index += 1;
    if (argument === "--jar") parsed.jar = value;
    if (argument === "--java") parsed.java = value;
    if (argument === "--evidence-directory") parsed.evidenceDirectory = value;
  }
  return parsed;
}

/** 产物是 Java 25 生产 JAR，因此优先使用项目指定的 JDK，不回退到 PATH。 */
function resolveJava(explicitJava) {
  if (explicitJava) return explicitJava;
  if (process.env.JAVA_HOME) {
    return join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java");
  }
  return process.platform === "win32" ? "java.exe" : "java";
}

/** 定位已存在且非空的 JAR，不构建或替换产物。 */
async function resolveJar(explicitJar) {
  const jar = resolve(
    explicitJar ?? join(repoRoot, "ja-agent", "ja-app-server", "target", "ja-app-server.jar"),
  );
  const metadata = await stat(jar);
  if (!metadata.isFile() || metadata.size === 0)
    throw new Error("coding-agent acceptance JAR is unavailable");
  return jar;
}

/** Java 版本不是 25 时立即失败，避免测试悄然验证了不同的运行时。 */
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
    throw new Error("coding-agent acceptance requires Java 25");
  return Number(match[1]);
}

/** 只接受完整的 JA-RPC 结果封套，并将协议故障收敛为稳定错误码。 */
function rpcResult(frame, operation) {
  if (frame?.error !== undefined) {
    const code = requireRpcErrorCode(frame.error);
    const message =
      typeof frame.error.message === "string" ? frame.error.message.slice(0, 300) : "";
    throw new Error(`${operation} failed: ${code}${message ? ` (${message})` : ""}`);
  }
  if (frame?.result === undefined) throw new Error(`${operation} returned no result`);
  return frame.result;
}

/** 启动指定的生产 sidecar，并在配置前等待权威 ready 事件。 */
async function startReadySession({ command, jar, java, directories }) {
  const session = new JsonlSession({
    command,
    prefixArgs: ["-jar", jar],
    directories,
    apiKey: placeholderSecret,
    endpoint: "http://127.0.0.1",
  });
  try {
    const initialized = rpcResult(
      await session.request("runtime/initialize", initializeParams()),
      "runtime/initialize",
    );
    if (
      initialized?.runtime?.engine !== "ja-kernel" ||
      typeof initialized?.runtime?.engineVersion !== "string"
    ) {
      throw new Error("coding-agent acceptance did not start the Ja Kernel runtime");
    }
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready" && frame.params?.generation > 0,
      30_000,
    );
    return { session, javaMajor: java.major };
  } catch (error) {
    await session.forceClose();
    throw error;
  }
}

/** 使用隔离的操作标识启动一个真实 Turn，并从受理边界开始计时。 */
async function startAcceptanceTurn(session, threadId, text, label) {
  const accepted = rpcResult(
    await session.request("turn/start", {
      threadId,
      operationKey: createOperationKey(),
      content: [{ type: "text", text }],
    }),
    `turn/start ${label}`,
  );
  if (typeof accepted.turnId !== "string" || !accepted.turnId.startsWith("turn_")) {
    throw new Error(`turn/start ${label} returned an invalid Turn identity`);
  }
  const acceptedAt = Date.now();
  const terminal = await session.waitForEvent(
    "turn/terminal",
    (frame) => frame.params?.turnId === accepted.turnId,
    runTimeoutMs,
  );
  return {
    turnId: accepted.turnId,
    elapsedMs: Date.now() - acceptedAt,
    terminal,
    finalText: committedTerminalReply(terminal, accepted.turnId),
  };
}

/** 收集已提交结果和模型可见的工具标识，不依赖瞬时增量事件。 */
function collectTurnFacts(session, turnId) {
  const results = session.events
    .filter((frame) => frame?.method === "tool/batch-committed" && frame.params?.turnId === turnId)
    .flatMap((frame) => (Array.isArray(frame.params?.results) ? frame.params.results : []));
  const calls = session.events
    .filter(
      (frame) =>
        frame?.method === "assistant/model-step-committed" && frame.params?.turnId === turnId,
    )
    .flatMap((frame) => (Array.isArray(frame.params?.toolCalls) ? frame.params.toolCalls : []));
  return {
    calls: calls.map((call) => ({ callId: call.callId, toolName: call.toolName })),
    results: results.map((result) => ({
      callId: result.callId,
      outcome: result.outcome,
      errorCode: result.errorCode ?? null,
    })),
  };
}

/** 每个预期调用必须恰有一个已提交结果，同时保留失败结果作为负向对照证据。 */
function assertOutcomes(facts, expected, label, expectedFailures = []) {
  const byCallId = new Map(facts.results.map((result) => [result.callId, result]));
  for (const [callId, toolName] of expected) {
    assert.equal(
      facts.results.filter((result) => result.callId === callId).length,
      1,
      `${label} must commit exactly one ${toolName} result`,
    );
    const result = byCallId.get(callId);
    assert.ok(result, `${label} is missing a committed ${toolName} result`);
    const expectedFailure = expectedFailures.find((failure) => failure.callId === callId);
    if (expectedFailure) {
      assert.equal(
        result.outcome,
        "failed",
        `${label} ${toolName} must fail at the asserted boundary`,
      );
      assert.equal(result.errorCode, expectedFailure.errorCode);
    } else {
      assert.equal(result.outcome, "succeeded", `${label} ${toolName} must succeed`);
    }
  }
  return byCallId;
}

/** 将 Provider 实际暴露的封闭参数名与当前生产 ToolSpecs 比较。 */
function assertToolCatalog(observations) {
  const catalog = observations.find((item) => item.stage === "write")?.toolCatalog ?? [];
  const expected = new Map([
    ["write", ["content", "path"]],
    ["read", ["limit", "offset", "path"]],
    ["edit", ["edits", "path"]],
    ["exec_command", ["cmd"]],
    ["grep", ["context", "glob", "ignoreCase", "limit", "literal", "path", "pattern"]],
    ["find", ["limit", "path", "pattern"]],
    ["ls", ["maxEntries", "path"]],
  ]);
  for (const [name, properties] of expected) {
    const definition = catalog.find((item) => item.name === name);
    assert.ok(definition, `Provider tool catalog is missing ${name}`);
    assert.deepEqual(
      definition.properties,
      properties,
      `${name} catalog schema drifted from production`,
    );
  }
  return [...expected.keys()];
}

/** 检查持久化 Thread 投影，确保已完成的答复不会掩盖缺失或未关联的 Tool 历史。 */
function assertThreadHistory(history, threadId, turnId, expectedCallIds, finalMarker) {
  assert.equal(history?.threadId, threadId);
  assert.ok(Number.isSafeInteger(history?.revision));
  assert.ok(Array.isArray(history?.items));
  const calls = history.items.filter((item) => item.kind === "tool_call" && item.turnId === turnId);
  assert.deepEqual(
    calls.map((item) => item.callId),
    expectedCallIds,
  );
  assert.ok(
    history.items.some(
      (item) =>
        item.kind === "final_answer" &&
        item.turnId === turnId &&
        typeof item.text === "string" &&
        item.text.includes(finalMarker),
    ),
    `thread/read must persist ${finalMarker}`,
  );
  return {
    revision: history.revision,
    itemCount: history.items.length,
    toolCallCount: calls.length,
  };
}

/** 仅将有界且脱敏的结果摘要写入源码树之外的指定证据目录。 */
async function persistEvidence(directory, report) {
  if (!directory) return null;
  const target = resolve(directory);
  if (
    target === repoRoot ||
    target.startsWith(`${repoRoot}/`) ||
    target.startsWith(`${repoRoot}\\`)
  ) {
    throw new Error("coding-agent evidence must not be written inside the source repository");
  }
  await mkdir(target, { recursive: true });
  const file = join(
    target,
    `coding-agent-deadline-${new Date().toISOString().replaceAll(/[:.]/gu, "-")}.json`,
  );
  await writeFile(file, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  return file;
}

/** 核实实际父目录后，只删除 createIsolatedDirectories 创建的那个 profile。 */
async function cleanupOwnedProfile(root) {
  const physicalTemp = await realpath(tmpdir());
  const target = resolve(root);
  if (dirname(target) !== physicalTemp || !basename(target).startsWith("ja-real-provider-")) {
    throw new Error("refusing to clean a profile not owned by this acceptance runner");
  }
  await rm(target, { recursive: true, force: false });
}

/** 通过生产 JA-RPC 执行三次隔离的 Provider 续传，再验证重启后的持久性。 */
export async function runAcceptance(options = {}) {
  const parsed = options.parsed ?? parseArguments(options.argv);
  const jar = await resolveJar(parsed.jar);
  const javaPath = resolveJava(parsed.java);
  const javaMajor = await assertJava25(javaPath);
  const directories = await createIsolatedDirectories();
  const report = {
    status: "running",
    jar: basename(jar),
    javaMajor,
    requestTimeoutMs: 2_000,
    expectedMainToolCount: expectedMainCalls.length,
    phases: [],
  };
  let fixture;
  let session;
  try {
    report.phases.push("prepare_workspace");
    await mkdir(join(directories.workspace, "src"));
    await writeFile(
      join(directories.workspace, "README.md"),
      "Coding Agent deadline acceptance.\n",
      "utf8",
    );
    fixture = await startCodingAgentDeadlineProvider();

    report.phases.push("start_app_server");
    ({ session } = await startReadySession({
      command: javaPath,
      jar,
      java: { major: javaMajor },
      directories,
    }));
    const configuration = rpcResult(
      await session.request("configuration/read", {}),
      "configuration/read",
    );
    const userDocument = configuration.user?.document ?? null;
    report.configurationReadShape = {
      topLevelKeys: Object.keys(configuration).sort(),
      casKeys: Object.keys(configuration.cas ?? {}).sort(),
      userVersionType: typeof configuration.cas?.userVersion,
      credentialVersionType: typeof configuration.cas?.credentialVersion,
      userStatus: configuration.user?.status ?? null,
      userDocumentKeys: Object.keys(userDocument ?? {}).sort(),
      userSchemaVersion: userDocument?.schema_version ?? null,
      userConfigRevision: userDocument?.config_revision ?? null,
      providerFieldKeys: Object.keys(userDocument?.providers?.[0] ?? {}).sort(),
      modelFieldKeys: Object.keys(userDocument?.providers?.[0]?.models?.[0] ?? {}).sort(),
      diagnosticCodes: (Array.isArray(configuration.diagnostics) ? configuration.diagnostics : [])
        .map((item) => item.code)
        .filter((code) => typeof code === "string"),
    };
    const document = providerConfigurationDocument({
      endpoint: fixture.baseUrl,
      name: "Coding Agent deadline loopback fixture",
      api: "openai_responses",
      model: "coding-agent-deadline-fixture",
      selectedProviderId: providerId,
      selectedModelId: modelId,
      selectedCredentialId: credentialId,
      reasoningLevel: "medium",
    });
    document.providers[0].network_timeouts.request_timeout_ms = fixture.requestTimeoutMs;
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

    report.phases.push("open_workspace_and_thread");
    const opened = rpcResult(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Coding Agent deadline acceptance",
      }),
      "workspace/open",
    );
    assert.ok(typeof opened.workspaceId === "string" && opened.workspaceId.startsWith("ws_"));
    const thread = rpcResult(
      await session.request("thread/create", {
        cwd: directories.workspace,
        title: "Coding Agent deadline acceptance",
        providerId,
        modelId,
        reasoningLevel: "medium",
        accessMode: "full_access",
        collaborationMode: "default",
      }),
      "thread/create",
    );
    assert.ok(typeof thread.threadId === "string" && thread.threadId.startsWith("thr_"));

    report.phases.push("run_deadline_tool_flow");
    const deadline = await startAcceptanceTurn(
      session,
      thread.threadId,
      `Create, inspect, search, edit, and verify a workspace file. ${codingAgentDeadlineMarkers.deadline}`,
      "deadline tool flow",
    );
    const deadlineFacts = collectTurnFacts(session, deadline.turnId);
    report.deadline = {
      turnId: deadline.turnId,
      elapsedMs: deadline.elapsedMs,
      exceededRequestWindow: deadline.elapsedMs > fixture.requestTimeoutMs,
      terminalState: deadline.terminal.params?.state ?? null,
      finalText: deadline.finalText,
      calls: deadlineFacts.calls,
      results: deadlineFacts.results,
      fixture: fixture.observations.filter((item) => item.scenario === "deadline"),
      fixtureFailure: fixture.failure(),
    };
    report.deadline.postimage = await readFile(
      join(directories.workspace, "src", "deadline-proof.txt"),
      "utf8",
    ).catch(() => null);

    if (parsed.expectDeadlineRegression) {
      assert.ok(
        report.deadline.exceededRequestWindow,
        "the regression control Turn must exceed its request window",
      );
      assertOutcomes(
        deadlineFacts,
        [
          ["call_deadline_write", "write"],
          ["call_deadline_read", "read"],
          ["call_deadline_grep", "grep"],
          ["call_deadline_find", "find"],
          ["call_deadline_ls", "ls"],
          ["call_deadline_edit", "edit"],
        ],
        "pre-fix deadline control",
        [{ callId: "call_deadline_edit", errorCode: "WORKSPACE_WRITE_COORDINATION_FAILED" }],
      );
      assertToolCatalog(report.deadline.fixture);
      report.deadline.firstExpiredMutation = "call_deadline_edit";
      assert.match(report.deadline.postimage ?? "", /^ORIGINAL_MARKER\nshell-target\n$/u);
      report.status = "regression_reproduced";
      return report;
    }

    report.phases.push("run_invalid_argument_recovery");
    const correction = await startAcceptanceTurn(
      session,
      thread.threadId,
      `Correct invalid file tool arguments and finish the write. ${codingAgentDeadlineMarkers.correction}`,
      "invalid argument recovery",
    );
    const correctionFacts = collectTurnFacts(session, correction.turnId);
    report.correction = {
      turnId: correction.turnId,
      terminalState: correction.terminal.params?.state ?? null,
      finalText: correction.finalText,
      calls: correctionFacts.calls,
      results: correctionFacts.results,
    };

    report.phases.push("run_truncated_call_recovery");
    const truncation = await startAcceptanceTurn(
      session,
      thread.threadId,
      `Regenerate a write call after the previous model response was truncated. ${codingAgentDeadlineMarkers.truncation}`,
      "truncated call recovery",
    );
    const truncationFacts = collectTurnFacts(session, truncation.turnId);
    report.truncation = {
      turnId: truncation.turnId,
      terminalState: truncation.terminal.params?.state ?? null,
      finalText: truncation.finalText,
      calls: truncationFacts.calls,
      results: truncationFacts.results,
    };

    report.phases.push("verify_workspace_postimages_and_history");
    const deadlineFile = await readFile(
      join(directories.workspace, "src", "deadline-proof.txt"),
      "utf8",
    ).catch(() => null);
    const correctedFile = await readFile(
      join(directories.workspace, "corrected.txt"),
      "utf8",
    ).catch(() => null);
    const recoveredFile = await readFile(
      join(directories.workspace, "truncation-recovered.txt"),
      "utf8",
    ).catch(() => null);
    let truncatedFileExists = true;
    try {
      await access(join(directories.workspace, "must-not-exist.txt"));
    } catch {
      truncatedFileExists = false;
    }
    report.postimages = {
      deadlineFile,
      correctedFile,
      recoveredFile,
      truncatedFileExists,
    };
    const history = rpcResult(
      await session.request("thread/read", { threadId: thread.threadId }),
      "thread/read before restart",
    );
    report.historyBeforeRestart = {
      deadline: assertThreadHistory(
        history,
        thread.threadId,
        deadline.turnId,
        expectedMainCalls.map(([callId]) => callId),
        "CODING_AGENT_DEADLINE_FINAL_OK",
      ),
      correction: assertThreadHistory(
        history,
        thread.threadId,
        correction.turnId,
        ["call_invalid_write", "call_corrected_write"],
        "CODING_AGENT_CORRECTION_FINAL_OK",
      ),
      truncation: assertThreadHistory(
        history,
        thread.threadId,
        truncation.turnId,
        ["call_truncated_write", "call_recovered_write"],
        "CODING_AGENT_TRUNCATION_FINAL_OK",
      ),
    };

    report.phases.push("restart_and_read_history");
    await session.shutdown();
    session = undefined;
    ({ session } = await startReadySession({
      command: javaPath,
      jar,
      java: { major: javaMajor },
      directories,
    }));
    const recovered = rpcResult(
      await session.request("thread/read", { threadId: thread.threadId }),
      "thread/read after restart",
    );
    report.historyAfterRestart = {
      deadline: assertThreadHistory(
        recovered,
        thread.threadId,
        deadline.turnId,
        expectedMainCalls.map(([callId]) => callId),
        "CODING_AGENT_DEADLINE_FINAL_OK",
      ),
      correction: assertThreadHistory(
        recovered,
        thread.threadId,
        correction.turnId,
        ["call_invalid_write", "call_corrected_write"],
        "CODING_AGENT_CORRECTION_FINAL_OK",
      ),
      truncation: assertThreadHistory(
        recovered,
        thread.threadId,
        truncation.turnId,
        ["call_truncated_write", "call_recovered_write"],
        "CODING_AGENT_TRUNCATION_FINAL_OK",
      ),
    };

    assert.ok(
      report.deadline.exceededRequestWindow,
      "the main Turn must exceed its 2 second request window",
    );
    report.toolCatalog = assertToolCatalog(report.deadline.fixture);
    assert.match(deadline.finalText ?? "", /CODING_AGENT_DEADLINE_FINAL_OK/u);
    assertOutcomes(deadlineFacts, expectedMainCalls, "deadline tool flow");
    assert.match(deadlineFile ?? "", /^EDITED_MARKER\nshell-target\n$/u);
    assert.equal(
      report.deadline.fixture.find((item) => item.stage === "final")?.shellSawEditedContent,
      true,
    );
    assert.match(correction.finalText ?? "", /CODING_AGENT_CORRECTION_FINAL_OK/u);
    assertOutcomes(
      correctionFacts,
      [
        ["call_invalid_write", "write"],
        ["call_corrected_write", "write"],
      ],
      "invalid argument recovery",
      [{ callId: "call_invalid_write", errorCode: "TOOL_ARGUMENTS_INVALID" }],
    );
    assert.match(correctedFile ?? "", /^CORRECTED_WRITE_OK\n$/u);
    assert.match(truncation.finalText ?? "", /CODING_AGENT_TRUNCATION_FINAL_OK/u);
    assertOutcomes(
      truncationFacts,
      [
        ["call_truncated_write", "write"],
        ["call_recovered_write", "write"],
      ],
      "truncated call recovery",
      [{ callId: "call_truncated_write", errorCode: "TOOL_CALL_TRUNCATED" }],
    );
    assert.equal(
      truncatedFileExists,
      false,
      "a truncated write must not cross the side-effect boundary",
    );
    assert.match(recoveredFile ?? "", /^TRUNCATION_RECOVERED_OK\n$/u);
    assert.equal(
      fixture.failure(),
      null,
      "local Provider fixture must not observe unexpected requests",
    );
    report.status = "completed";
    report.toolCount = expectedMainCalls.length;
    report.evidenceScope = "JVM JAR, JA-RPC, loopback Responses, workspace tools, restart history";
    return report;
  } catch (error) {
    report.status = "failed";
    report.failure = String(error?.message ?? error).slice(0, 500);
    throw error;
  } finally {
    if (session) {
      await session.shutdown().catch(() => session.forceClose());
    }
    await fixture?.close().catch(() => undefined);
    await cleanupOwnedProfile(directories.root);
    report.evidenceFile = await persistEvidence(parsed.evidenceDirectory, report);
  }
}

/** 输出一行简要结果；验收证据可选地保存在外部 JSON 文件中。 */
async function main() {
  const report = await runAcceptance();
  if (report.status === "regression_reproduced") {
    process.stdout.write(
      `CODING_AGENT_DEADLINE_REGRESSION_REPRODUCED elapsedMs=${report.deadline.elapsedMs} ` +
        `error=WORKSPACE_WRITE_COORDINATION_FAILED\n`,
    );
    if (report.evidenceFile) process.stdout.write(`evidence=${report.evidenceFile}\n`);
    return;
  }
  process.stdout.write(
    `CODING_AGENT_DEADLINE_ACCEPTANCE_OK elapsedMs=${report.deadline.elapsedMs} ` +
      `requestTimeoutMs=${report.requestTimeoutMs} tools=${report.toolCount} ` +
      `historyItems=${report.historyAfterRestart.deadline.itemCount}\n`,
  );
  if (report.evidenceFile) process.stdout.write(`evidence=${report.evidenceFile}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  await main();
}
