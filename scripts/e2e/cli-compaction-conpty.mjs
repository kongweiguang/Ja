// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createConnection } from "node:net";
import { readFile, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { spawn } from "node:child_process";
import {
  compileConPtyHarness,
  createRunDirectories,
  removeOwnedProfile,
  resolveArtifacts,
  seedThread,
  startConPtyCli,
  waitForRawText,
  writeScreenEvidence,
} from "./cli-usage-conpty.mjs";
import {
  createOperationKey,
  initializeParams,
  JsonlSession,
  providerConfigurationDocument,
} from "./real-provider-smoke.mjs";
import { providerStream } from "./media-schema-loopback.mjs";

const secret = "ja-cli-usage-conpty-placeholder";
const seedMarker = "JA_COMPACTION_CONPTY_SEED";
const finalMarker = "JA_COMPACTION_CONPTY_COMPLETED";
const delay = (milliseconds) => new Promise((done) => setTimeout(done, milliseconds));

/** 控制点只阻塞本地 fixture，不连接真实 Provider，确保截图能覆盖短暂的退避与活动压缩。 */
function gate() {
  let release;
  const promise = new Promise((done) => {
    release = done;
  });
  return { promise, release };
}

/** 仅校验 RPC 成功分类，避免将正文、配置或凭据带入失败诊断。 */
function result(frame) {
  assert.equal(frame.error, undefined, `RPC failed: ${frame.error?.data?.errorCode ?? "unknown"}`);
  assert.notEqual(frame.result, undefined);
  return frame.result;
}

/** 摘要使用 Responses 原生字符串 input；每个 chunk 的证据序号来自其自身 envelope，不能复用首块。 */
function summaryDocument(payload) {
  assert.equal(typeof payload.input, "string");
  const evidence = JSON.parse(payload.input);
  const ordinal = evidence.evictedMessages[0]?.ordinal;
  assert.ok(Number.isSafeInteger(ordinal));
  const document = evidence.previousSummary ?? {
    goals: [{ text: "完成本地压缩状态验收", sourceOrdinal: ordinal }],
    constraints: [],
    completedProgress: [],
    currentProgress: [],
    blockers: [],
    decisions: [],
    nextSteps: [],
    criticalFacts: [],
    files: [],
    pendingEffects: [],
    retirements: [],
  };
  for (const message of evidence.evictedMessages.filter((item) => item.role === "user")) {
    if (!document.criticalFacts.some((fact) => fact.sourceOrdinal === message.ordinal)) {
      document.criticalFacts.push({
        text: `保留本地验收输入 ${message.ordinal}`,
        sourceOrdinal: message.ordinal,
      });
    }
  }
  return JSON.stringify(document);
}

/** 先用 503 验证恢复，再受控成功或确定性拒绝；取消场景保持 IO 等待，全部使用本地占位凭据。 */
async function startProvider(terminal) {
  const first = gate();
  const retry = gate();
  const sockets = new Set();
  let summaries = 0;
  let finalResponses = 0;
  let failure;
  const server = createServer(async (request, response) => {
    try {
      assert.equal(request.url, "/v1/responses");
      assert.equal(request.headers.authorization, `Bearer ${secret}`);
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const isSummary = payload.text?.format?.name === "ja_context_summary";
      if (isSummary) {
        summaries += 1;
        if (summaries === 1) {
          await first.promise;
          response.writeHead(503, { "content-type": "application/json" });
          response.end('{"error":{"type":"server_error","message":"local fixture unavailable"}}');
          return;
        }
        await retry.promise;
        if (terminal === "failed") {
          response.writeHead(400, { "content-type": "application/json" });
          response.end(
            '{"error":{"type":"invalid_request_error","message":"local fixture rejected"}}',
          );
          return;
        }
      }
      const latestUser = Array.isArray(payload.input)
        ? payload.input.filter((item) => item.role === "user").at(-1)
        : payload.input;
      const text = isSummary
        ? summaryDocument(payload)
        : JSON.stringify(latestUser).includes(seedMarker)
          ? "背景事实用于本地验收。\n".repeat(2_000)
          : JSON.stringify(payload).includes("你为桌面对话生成简洁标题")
            ? "本地压缩状态验收"
            : finalMarker;
      if (text === finalMarker) finalResponses += 1;
      response.writeHead(200, { "content-type": "text/event-stream; charset=utf-8" });
      response.end(
        providerStream("openai_responses", `resp_compact_${Date.now()}`, undefined, text),
      );
    } catch (error) {
      failure = error;
      response.writeHead(500).end();
    }
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.once("close", () => sockets.delete(socket));
  });
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  return {
    endpoint: `http://127.0.0.1:${server.address().port}/v1`,
    first,
    retry,
    summaries: () => summaries,
    finalResponses: () => finalResponses,
    check: () => {
      if (failure) throw failure;
    },
    close: async () => {
      first.release();
      retry.release();
      sockets.forEach((socket) => socket.destroy());
      await new Promise((done) => server.close(done));
    },
  };
}

/** 只读隔离数据库的真实终态；短暂提交锁继续等待，避免把重绘空帧或写锁误判为执行结果。 */
async function waitForTerminal(directories, terminal) {
  const { DatabaseSync } = await import("node:sqlite");
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const database = new DatabaseSync(join(directories.data, "ja.db"), { readOnly: true });
    try {
      database.exec("PRAGMA busy_timeout = 250");
      const states = database
        .prepare("SELECT state, COUNT(*) AS count FROM agent_execution GROUP BY state")
        .all();
      const expected = terminal === "completed" ? 7 : 1;
      if (
        states.some(
          (state) => state.state === terminal.toUpperCase() && state.count === expected,
        ) &&
        states.every((state) => ["COMPLETED", "CANCELLED", "FAILED"].includes(state.state))
      )
        return states;
    } catch (error) {
      if (error.errcode !== 5 && error.errcode !== 6) throw error;
    } finally {
      database.close();
    }
    await delay(80);
  }
  throw new Error(`isolated execution did not reach ${terminal}`);
}

/** 同一状态行需同时含阶段和重试，不能把用户问题中的“压缩”当成窄窗验收结果。 */
function showsRetry(_text, lines) {
  return lines.some((line) => line.includes("正在工作") && line.includes("摘要重试"));
}

/** 终态快照必须清除阶段及工作动效；检查当前帧而非历史 VT 中曾经出现的文字。 */
function showsIdle(text) {
  return (
    !text.includes("正在工作") && !text.includes("摘要重试") && !text.includes("正在压缩上下文")
  );
}

/** 先以关闭自动压缩的配置写入长历史，再通过真实配置 CAS 开启；不直接修改 SQLite 或用户设置。 */
async function prepareHistory(artifacts, directories, endpoint, threadId) {
  const session = new JsonlSession({
    command: artifacts.java,
    prefixArgs: ["-jar", artifacts.jar],
    directories,
    apiKey: secret,
    endpoint,
  });
  try {
    result(await session.request("runtime/initialize", initializeParams()));
    session.notifyInitialized();
    await session.waitForEvent(
      "runtime/status-changed",
      (frame) => frame.params?.status === "ready",
    );
    result(
      await session.request("workspace/open", {
        cwd: directories.workspace,
        displayName: "Ja CLI compaction ConPTY",
      }),
    );
    for (let index = 0; index < 6; index += 1) {
      const accepted = result(
        await session.request("turn/start", {
          threadId,
          operationKey: createOperationKey(),
          content: [{ type: "text", text: `${seedMarker}_${index}` }],
        }),
      );
      const terminal = await session.waitForEvent(
        "turn/terminal",
        (frame) => frame.params?.turnId === accepted.turnId,
      );
      assert.equal(terminal.params.state, "completed");
    }
    const current = result(await session.request("configuration/read", {}));
    const document = providerConfigurationDocument({
      endpoint,
      name: "Ja CLI compaction ConPTY loopback",
      api: "openai_responses",
      model: "gpt-6-luna",
      selectedProviderId: "provider_cli_usage_conpty",
      selectedModelId: "model_cli_usage_conpty",
      selectedCredentialId: "cred_cli_usage_conpty",
      reasoningLevel: "xhigh",
    });
    result(
      await session.request("configuration/replace", {
        scope: "user",
        expectedVersion: current.cas.userVersion,
        document,
      }),
    );
    const stopped = await session.shutdown();
    assert.equal(stopped.code, 0);
  } catch (error) {
    throw new Error(`${error.message}\n${session.stderr}`, { cause: error });
  } finally {
    await session.forceClose();
  }
}

/** 以独有 JA_HOME 停止本轮 daemon，绝不接触开发者当前正在运行的共享会话。 */
async function stopIsolatedDaemon(artifacts, directories) {
  if (!(await isolatedDaemonListening(directories))) return;
  const child = spawn(artifacts.cli, ["server", "stop", "--force"], {
    cwd: directories.workspace,
    windowsHide: true,
    stdio: "ignore",
    env: {
      ...process.env,
      JA_HOME: directories.home,
      JA_DEBUG_JAVA: artifacts.java,
      JA_DEBUG_JAR: artifacts.jar,
    },
  });
  await new Promise((done, fail) => {
    child.once("exit", done);
    child.once("error", fail);
  });
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (!(await isolatedDaemonListening(directories))) return;
    await delay(100);
  }
  throw new Error("isolated daemon did not stop");
}

/** 受限宿主 Job 会随 CLI 退出结束 Java，却留下 endpoint；以该隔离 loopback listener 的实际存活判定清理。 */
async function isolatedDaemonListening(directories) {
  let endpoint;
  try {
    endpoint = JSON.parse(
      await readFile(join(directories.run, "app-server.endpoint.json"), "utf8"),
    );
  } catch (error) {
    if (error.code === "ENOENT") return false;
    throw error;
  }
  assert.equal(endpoint.host, "127.0.0.1");
  return new Promise((done) => {
    const socket = createConnection({ host: endpoint.host, port: endpoint.port });
    socket.setTimeout(1_000);
    socket.once("connect", () => {
      socket.destroy();
      done(true);
    });
    socket.once("error", () => {
      socket.destroy();
      done(false);
    });
    socket.once("timeout", () => {
      socket.destroy();
      done(false);
    });
  });
}

/** 失败时仅保留隔离数据库的阶段与事件分类和安全日志，不导出请求正文、配置或 endpoint token。 */
async function captureDiagnostics(isolated) {
  const { DatabaseSync } = await import("node:sqlite");
  const database = new DatabaseSync(join(isolated.directories.data, "ja.db"), { readOnly: true });
  try {
    database.exec("PRAGMA busy_timeout = 1000");
    const eventCounts = database
      .prepare("SELECT event_type, COUNT(*) AS count FROM agent_event GROUP BY event_type")
      .all();
    const states = database
      .prepare("SELECT state, COUNT(*) AS count FROM agent_execution GROUP BY state")
      .all();
    const log = await readFile(join(isolated.directories.logs, "app-server.log"), "utf8").catch(
      () => "",
    );
    await writeFile(join(isolated.runRoot, "app-server-tail.log"), log.slice(-12_000), "utf8");
    return { eventCounts, states };
  } finally {
    database.close();
  }
}

/** 三种终态复用真实压缩链路，覆盖重试、F5、跨客户端恢复、窄窗、输入焦点与资源释放。 */
export async function runAcceptance({ terminal = "completed" } = {}) {
  assert.ok(["completed", "cancelled", "failed"].includes(terminal));
  const artifacts = await resolveArtifacts();
  const isolated = await createRunDirectories();
  const provider = await startProvider(terminal);
  let cli;
  let restored;
  let daemonStarted = false;
  let primaryFailure;
  const evidence = {
    status: "running",
    terminal,
    runtime: "Windows ConPTY + isolated JDK25 JAR + loopback Provider",
    cliSha256: artifacts.cliSha256,
    jarSha256: artifacts.jarSha256,
    screens: {},
  };
  try {
    const seeded = await seedThread(artifacts, isolated.directories, provider.endpoint);
    await prepareHistory(artifacts, isolated.directories, provider.endpoint, seeded.threadId);
    const harness = await compileConPtyHarness(artifacts, isolated.runRoot);
    daemonStarted = true;
    cli = startConPtyCli(
      artifacts,
      isolated.directories,
      harness,
      seeded.threadId,
      "继续完成本地压缩验收",
      join(isolated.runRoot, "compaction.vt"),
    );
    await cli.harnessReady;
    await cli.waitForScreen((text) => text.includes("正在压缩上下文"), "compaction starts");
    evidence.screens.started = await writeScreenEvidence(
      isolated.runRoot,
      "compaction-started",
      cli.screen,
    );
    provider.first.release();
    await cli.waitForScreen(showsRetry, "summary retry");
    evidence.screens.retry = await writeScreenEvidence(
      isolated.runRoot,
      "compaction-retry",
      cli.screen,
    );
    cli.sendKeys("\u001b[15~");
    await waitForRawText(join(isolated.runRoot, "compaction.vt"), /后台状态已更新/u, 15_000);
    await cli.waitForScreen(showsRetry, "F5 restores retry");
    evidence.screens.refreshed = await writeScreenEvidence(
      isolated.runRoot,
      "compaction-refreshed",
      cli.screen,
    );
    restored = startConPtyCli(
      artifacts,
      isolated.directories,
      harness,
      seeded.threadId,
      undefined,
      join(isolated.runRoot, "compaction-restored.vt"),
    );
    await restored.harnessReady;
    await restored.waitForScreen(showsRetry, "reenter restores retry");
    evidence.screens.restored = await writeScreenEvidence(
      isolated.runRoot,
      "compaction-restored",
      restored.screen,
    );
    await restored.resize(40, 18);
    await restored.waitForScreen(showsRetry, "narrow summary retry state");
    evidence.screens.narrow = await writeScreenEvidence(
      isolated.runRoot,
      "compaction-narrow",
      restored.screen,
    );
    const narrow = evidence.screens.narrow.join("\n");
    assert.ok(narrow.includes("摘要重试 · 第 2 次请求"));
    assert.ok(narrow.includes("Esc 中断"));
    if (terminal === "cancelled") cli.sendKeys("\u001b");
    else provider.retry.release();
    evidence.executionStates = await waitForTerminal(isolated.directories, terminal);
    await cli.waitForScreen(showsIdle, `${terminal} clears compaction`);
    await restored.waitForScreen(showsIdle, `${terminal} clears restored state`);
    if (terminal === "completed") {
      await cli.waitForScreen((text) => text.includes(finalMarker), "final answer remains visible");
      assert.equal(provider.finalResponses(), 1);
    } else {
      assert.equal(
        provider.finalResponses(),
        0,
        "stopped summary must not send the original full context",
      );
      if (terminal === "failed")
        await cli.waitForScreen(
          (text) => text.includes("任务未完成") && text.includes("Enter 继续回复"),
          "deterministic failure keeps a visible recovery action",
        );
    }
    const draft = "后续输入草稿";
    cli.sendKeys(draft);
    await cli.waitForScreen((text) => text.includes(draft), `${terminal} restores Composer input`);
    cli.sendKeys("\u007f".repeat(Array.from(draft).length));
    await cli.waitForScreen((text) => !text.includes(draft), "clear verification draft");
    provider.check();
    assert.ok(provider.summaries() >= 2);
    evidence.summaryAttempts = provider.summaries();
    evidence.screens[terminal] = await writeScreenEvidence(
      isolated.runRoot,
      `compaction-${terminal}`,
      cli.screen,
    );
    evidence.status = "passed";
  } catch (error) {
    evidence.status = "failed";
    evidence.failure = error.message;
    primaryFailure = error;
  } finally {
    try {
      evidence.diagnostics = await captureDiagnostics(isolated);
    } catch (error) {
      evidence.diagnosticsFailure = error.name;
    }
    provider.first.release();
    provider.retry.release();
    evidence.summaryAttempts = provider.summaries();
    evidence.finalResponses = provider.finalResponses();
    evidence.cleanup = {};
    /** 单步失败不能跳过其余资源释放或丢失原始故障；失败的清理也必须进入最终证据。 */
    async function cleanupStep(name, operation) {
      try {
        const closed = await operation();
        evidence.cleanup[name] = closed ?? { released: true };
        if ((name === "cli" || name === "restored") && closed) {
          assert.equal(
            closed.forced,
            false,
            "terminal CLI must close without forced process cleanup",
          );
          assert.equal(closed.childExitCode, 0, "terminal CLI must exit with code zero");
        }
      } catch (error) {
        evidence.status = "failed";
        evidence.failure ??= error.message;
        evidence.cleanup[name] ??= { failed: true, errorType: error.name };
        primaryFailure ??= error;
      }
    }
    await cleanupStep("restored", () => restored?.close());
    await cleanupStep("cli", () => cli?.close());
    if (daemonStarted)
      await cleanupStep("daemon", () => stopIsolatedDaemon(artifacts, isolated.directories));
    await cleanupStep("provider", () => provider.close());
    await cleanupStep("profile", () => removeOwnedProfile(isolated.runRoot, isolated.profileRoot));
    await writeFile(
      join(isolated.runRoot, "evidence.json"),
      JSON.stringify(evidence, null, 2) + "\n",
      "utf8",
    );
    process.stdout.write(
      `CLI_COMPACTION_CONPTY_${evidence.status.toUpperCase()} ${isolated.runRoot}\n`,
    );
  }
  if (primaryFailure) throw primaryFailure;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const terminal = process.argv[2] ?? "completed";
  await runAcceptance({ terminal });
}
