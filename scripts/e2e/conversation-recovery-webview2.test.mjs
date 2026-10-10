// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { EventEmitter } from "node:events";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import test from "node:test";
import {
  createClipboardFileBrokerLaunchSpec,
  findAcceptedContinuationTurn,
  installConversationRecoveryInvokeProbe,
  parseArguments,
  superviseClipboardFileBrokerChild,
  startClipboardFileBroker,
} from "./conversation-recovery-webview2.mjs";

const execFileAsync = promisify(execFile);

/** 验证探针使用真实 delegate 边界，延迟状态读取且仅在真实提交后丢弃一次 ACK。 */
test("recovery invoke probe holds authoritative reads and drops only the first committed ACK", async () => {
  const keys = [
    "__JA_E2E_NATIVE_INVOKE_PROBE__",
    "__recoveryInvokeFailures",
    "__recoveryRawInvokes",
    "__recoveryItemContentReads",
    "__recoveryContinueReadGate",
    "__recoveryItemContentReadGate",
    "__recoveryDropNextContinueAck",
    "__recoveryLostContinueAck",
  ];
  const prior = keys.map((key) => Object.getOwnPropertyDescriptor(globalThis, key));
  let release;
  let releaseItemContent;
  const pending = new Promise((resolveRead) => {
    release = resolveRead;
  });
  const pendingItemContent = new Promise((resolveRead) => {
    releaseItemContent = resolveRead;
  });
  try {
    delete globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    installConversationRecoveryInvokeProbe();
    const probe = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    globalThis.__recoveryContinueReadGate = { threadId: "thread_fixture", pending, waiterCount: 0 };
    let reads = 0;
    const held = probe(
      { command: "ja_thread_read", args: { input: { threadId: "thread_fixture" } } },
      async () => {
        reads += 1;
        return { revision: 3 };
      },
    );
    assert.equal(globalThis.__recoveryContinueReadGate.waiterCount, 1);
    assert.equal(reads, 0);
    release();
    assert.deepEqual(await held, { revision: 3 });
    assert.equal(reads, 1);

    const contentInput = {
      threadId: "thread_fixture",
      itemId: "item_fixture",
      offsetCharacters: 32_768,
      limitCharacters: 32_768,
    };
    globalThis.__recoveryItemContentReadGate = {
      threadId: contentInput.threadId,
      itemId: contentInput.itemId,
      pending: pendingItemContent,
      waiterCount: 0,
    };
    let contentReads = 0;
    const pendingContentRead = probe(
      { command: "ja_thread_item_content_read", args: { input: contentInput } },
      async () => {
        contentReads += 1;
        return { itemId: contentInput.itemId, content: "private body" };
      },
    );
    assert.equal(globalThis.__recoveryItemContentReadGate.waiterCount, 1);
    assert.equal(contentReads, 0);
    releaseItemContent();
    assert.deepEqual(await pendingContentRead, { itemId: "item_fixture", content: "private body" });
    assert.equal(contentReads, 1);
    assert.deepEqual(globalThis.__recoveryItemContentReads, [
      {
        threadId: "thread_fixture",
        itemId: "item_fixture",
        offsetCharacters: 32_768,
        limitCharacters: 32_768,
        nextOffsetCharacters: null,
        totalCharacters: null,
        truncated: null,
        contentCharacters: 12,
      },
    ]);
    assert.equal(
      JSON.stringify(globalThis.__recoveryItemContentReads).includes("private body"),
      false,
    );
    assert.deepEqual(globalThis.__recoveryRawInvokes[1], {
      command: "ja_thread_item_content_read",
      operationKey: null,
      threadId: "thread_fixture",
      sourceMessageId: null,
      itemId: "item_fixture",
      offsetCharacters: 32_768,
      limitCharacters: 32_768,
      expectedThreadRevision: null,
    });

    const input = {
      threadId: "thread_fixture",
      expectedThreadRevision: 3,
      operationKey: "op_fixture",
    };
    const accepted = { threadId: "thread_fixture", turnId: "turn_fixture", threadRevision: 4 };
    const request = { command: "ja_turn_continue", args: { input } };
    let commits = 0;
    const commit = async () => {
      commits += 1;
      return accepted;
    };
    globalThis.__recoveryDropNextContinueAck = true;
    await assert.rejects(probe(request, commit), /JA_E2E_CONTINUE_ACK_DROPPED/u);
    assert.equal(commits, 1);
    assert.deepEqual(globalThis.__recoveryLostContinueAck, { input, accepted });
    assert.equal(globalThis.__recoveryDropNextContinueAck, false);
    assert.deepEqual(await probe(request, commit), accepted);
    assert.equal(commits, 2);
    assert.deepEqual(
      globalThis.__recoveryRawInvokes.map((call) => call.command),
      ["ja_thread_read", "ja_thread_item_content_read", "ja_turn_continue", "ja_turn_continue"],
    );
  } finally {
    release();
    releaseItemContent();
    keys.forEach((key, index) => {
      if (prior[index] === undefined) delete globalThis[key];
      else Object.defineProperty(globalThis, key, prior[index]);
    });
  }
});

/** 固定同一来源消息有失败和成功续答两轮时，按 accepted Turn ID 断言当前结果。 */
test("accepted continuation selects its completed turn when source message also has a failed turn", () => {
  const turns = [
    { turnId: "turn_failed", sourceMessageId: "item_shared", status: "failed" },
    { turnId: "turn_continued", sourceMessageId: "item_shared", status: "completed" },
  ];

  assert.equal(findAcceptedContinuationTurn(turns, "turn_continued", "item_shared"), turns[1]);
});

/** 确认附件跳过标志不会吞掉真实 WebView2 runner 的 JAR、JDK、target 和证据参数。 */
test("skip clipboard attachment preserves isolated WebView2 launch arguments", () => {
  const options = parseArguments([
    "--skip-clipboard-attachment",
    "--jar",
    "C:/fixtures/ja-app-server.jar",
    "--java-home",
    "C:/fixtures/jdk-25",
    "--cargo-target-directory",
    "C:/fixtures/cargo-target",
    "--evidence-directory",
    "C:/fixtures/evidence",
  ]);

  assert.equal(options.skipClipboardAttachment, true);
  assert.equal(options.jar, "C:\\fixtures\\ja-app-server.jar");
  assert.equal(options.javaHome, "C:\\fixtures\\jdk-25");
  assert.equal(options.cargoTargetDirectory, "C:\\fixtures\\cargo-target");
  assert.equal(options.evidenceDirectory, "C:\\fixtures\\evidence");
});

/** @author kongweiguang 单独构造单路径探针命令，供生产 opt-in 与无 Clipboard fake-list 回归共用。 */
function createSyntheticClipboardPathProbeScript(filePath) {
  const encodedPath = Buffer.from(filePath, "utf8").toString("base64");
  return [
    "Add-Type -AssemblyName System.Windows.Forms",
    `$expected = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedPath}'))`,
    "function Invoke-ClipboardWithRetry { param([scriptblock]$Operation); $delays = @(30,60,120,240,480,800); for ($attempt = 0; $attempt -le $delays.Length; $attempt++) { try { return & $Operation } catch { $exception = $_.Exception; while ($null -ne $exception.InnerException -and $exception -isnot [System.Runtime.InteropServices.ExternalException]) { $exception = $exception.InnerException }; if ($exception -isnot [System.Runtime.InteropServices.ExternalException] -or $attempt -eq $delays.Length) { throw }; Start-Sleep -Milliseconds $delays[$attempt] } }; throw 'clipboard operation did not complete' }",
    "$files = @(Invoke-ClipboardWithRetry { [Windows.Forms.Clipboard]::GetFileDropList() })",
    "if ($null -eq $files -or $files.Count -ne 1) { [Console]::Out.WriteLine('MISMATCH'); exit 2 }",
    "if ([StringComparer]::Ordinal.Equals($files[0], $expected)) { [Console]::Out.WriteLine('MATCH'); exit 0 }",
    "[Console]::Out.WriteLine('MISMATCH'); exit 2",
  ].join("; ");
}

/** @author kongweiguang 只读取本轮合成路径的闭集结果，并将子进程诊断压缩为枚举值与数值退出码。 */
async function probeSyntheticClipboardPath(filePath) {
  const script = createSyntheticClipboardPathProbeScript(filePath);
  const classifyOutcome = (stdout) => {
    const outcome = typeof stdout === "string" ? stdout.trim() : "";
    return outcome === "MATCH" || outcome === "MISMATCH" ? outcome : null;
  };
  const failure = (stdout, exitCode) => {
    const error = new Error("synthetic clipboard probe failed");
    error.clipboardProbeOutcome = classifyOutcome(stdout);
    error.clipboardProbeExitCode = Number.isSafeInteger(exitCode) ? exitCode : null;
    return error;
  };
  try {
    const result = await execFileAsync(
      "pwsh.exe",
      [
        "-STA",
        "-NoProfile",
        "-NonInteractive",
        "-EncodedCommand",
        Buffer.from(script, "utf16le").toString("base64"),
      ],
      { windowsHide: true, timeout: 8_000, maxBuffer: 2_048, encoding: "utf8" },
    );
    const outcome = classifyOutcome(result.stdout);
    if (outcome === null) throw failure(result.stdout, 0);
    return outcome;
  } catch (sourceError) {
    if (sourceError.message === "synthetic clipboard probe failed") throw sourceError;
    throw failure(sourceError?.stdout, sourceError?.code);
  }
}

/** @author kongweiguang 以 fake StringCollection 执行同一 PowerShell 投影，断言单路径不折叠成首字符且不碰 Clipboard。 */
async function runFakeSyntheticClipboardPathProbe(expectedPath, actualPaths) {
  let script = createSyntheticClipboardPathProbeScript(expectedPath);
  const encodedExpectedPath = Buffer.from(expectedPath, "utf8").toString("base64");
  const expectedAssignment = `$expected = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedExpectedPath}'))`;
  const encodedActualPaths = actualPaths.map((path) =>
    Buffer.from(path, "utf8").toString("base64"),
  );
  const fakeReader = [
    "function Get-FakeClipboardFileDropList { $paths = New-Object System.Collections.Specialized.StringCollection",
    ...encodedActualPaths.map(
      (encodedPath) =>
        `$null = $paths.Add([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedPath}')))`,
    ),
    "return $paths }",
  ].join("; ");
  assert.equal(script.split(expectedAssignment).length - 1, 1);
  script = script.replace(expectedAssignment, `${expectedAssignment}; ${fakeReader}`);
  assert.equal(script.split("[Windows.Forms.Clipboard]::GetFileDropList()").length - 1, 1);
  script = script.replace(
    "[Windows.Forms.Clipboard]::GetFileDropList()",
    "Get-FakeClipboardFileDropList",
  );
  script = script.replace("Add-Type -AssemblyName System.Windows.Forms", "");
  assert.doesNotMatch(script, /\[Windows\.Forms\.Clipboard\]::/u);

  return execFileAsync(
    "pwsh.exe",
    [
      "-NoProfile",
      "-NonInteractive",
      "-STA",
      "-EncodedCommand",
      Buffer.from(script, "utf16le").toString("base64"),
    ],
    { windowsHide: true, timeout: 5_000, maxBuffer: 2_048, encoding: "utf8" },
  );
}

/** @author kongweiguang 用 PowerShell fake StringCollection 回归单路径管道收集，不访问系统 Clipboard。 */
test(
  "synthetic clipboard path probe preserves StringCollection items without Clipboard access",
  { skip: process.platform === "win32" ? false : "requires Windows PowerShell" },
  async () => {
    const expectedPath = "C:\\fixtures\\single-file.txt";
    const matching = await runFakeSyntheticClipboardPathProbe(expectedPath, [expectedPath]);
    assert.equal(matching.stdout.trim(), "MATCH");

    for (const actualPaths of [
      ["C:\\fixtures\\another-file.txt"],
      [expectedPath, "C:\\fixtures\\another-file.txt"],
    ]) {
      await assert.rejects(
        runFakeSyntheticClipboardPathProbe(expectedPath, actualPaths),
        (error) => {
          assert.equal(error.code, 2);
          assert.equal(error.stdout.trim(), "MISMATCH");
          return true;
        },
      );
    }
  },
);

/** @author kongweiguang 构造无操作系统 I/O 的子进程替身，显式控制 stdout 协议与自然退出。 */
function createFakeClipboardBrokerChild() {
  const child = new EventEmitter();
  child.pid = 43127;
  child.exitCode = null;
  child.signalCode = null;
  child.killed = false;
  child.restoreCommands = [];
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  child.stderr.resume = () => child.stderr;
  child.stdin = {
    end(command) {
      child.restoreCommands.push(String(command));
      child.onRestoreRequest?.();
    },
  };
  child.writeStdout = (text) => child.stdout.emit("data", Buffer.from(text, "utf8"));
  child.finish = (code = 0, signal = null) => {
    child.exitCode = code;
    child.signalCode = signal;
    child.stdout.emit("end");
    child.emit("exit", code, signal);
    child.emit("close", code, signal);
  };
  child.kill = () => {
    child.killed = true;
    child.finish(1);
  };
  return child;
}

/** @author kongweiguang 锁住发布顺序、重试间隔的 STA pump 与原 IDataObject finally 回放。 */
test("clipboard broker pumps STA messages around CF_HDROP publish and opaque restore", () => {
  const launch = createClipboardFileBrokerLaunchSpec("C:\\fixtures\\synthetic-drop.txt");
  assert.equal(launch.executable, "pwsh.exe");
  assert.deepEqual(launch.options.stdio, ["pipe", "pipe", "pipe"]);
  const encodedCommand = launch.args.at(-1);
  const script = Buffer.from(encodedCommand, "base64").toString("utf16le");
  const publishing = script.indexOf("WriteLine('PUBLISHING')");
  const inputReader = script.indexOf("$inputTask = [JaClipboardBrokerInput]::ReadLineAsync()");
  const publish = script.indexOf("Clipboard]::SetFileDropList($files)");
  const ready = script.indexOf("WriteLine('READY')");
  const finallyStart = script.indexOf("finally {");
  const restore = script.indexOf("Clipboard]::SetDataObject($original, $true)", finallyStart);
  const restored = script.indexOf("WriteLine('RESTORED')", finallyStart);

  assert.ok(
    publishing >= 0 && publishing < inputReader && inputReader < publish && publish < ready,
  );
  assert.ok(script.includes("Task.Run(() => Console.In.ReadLine())"));
  assert.ok(script.includes("此读取可能阻塞；放到 worker"));
  assert.doesNotMatch(script, /\[Console\]::In\.ReadLineAsync\(\)/u);
  assert.match(
    script,
    /while \(-not \$inputTask\.IsCompleted\) \{ \[Windows\.Forms\.Application\]::DoEvents\(\); Start-Sleep -Milliseconds 10 \}/u,
  );
  assert.ok(finallyStart > ready && restore > finallyStart && restored > restore);
  assert.match(
    script,
    /while \(\[DateTime\]::UtcNow -lt \$retryUntil\) \{ \[Windows\.Forms\.Application\]::DoEvents\(\); \[Threading\.Thread\]::Sleep\(10\) \}/u,
  );
});

/** @author kongweiguang 以拆分 CRLF chunk 覆盖 READY 识别，并证明正常恢复必须等 ACK 与自然退出。 */
test("clipboard broker requires RESTORED acknowledgement before releasing its synthetic fixture", async () => {
  const child = createFakeClipboardBrokerChild();
  child.onRestoreRequest = () => {
    child.writeStdout("RESTORED\r\n");
    child.finish(0);
  };
  const opening = superviseClipboardFileBrokerChild(child, {
    readinessTimeoutMs: 100,
    restoreTimeoutMs: 100,
  });
  child.writeStdout("PUBLISHING\r\nREA");
  child.writeStdout("DY\r\n");
  const broker = await opening;
  assert.equal(broker.clipboardState, "published");

  await broker.restore();
  await broker.restore();
  assert.equal(broker.clipboardState, "restored");
  assert.deepEqual(child.restoreCommands, ["restore\n"]);
  assert.equal(child.killed, false);
});

/** @author kongweiguang 故障时仅排队恢复并保留 unknown，绝不按期限 kill 可能仍在发布/回放的 STA owner。 */
test("clipboard broker timeout preserves an unconfirmed child and restoration state", async () => {
  const child = createFakeClipboardBrokerChild();
  const opening = superviseClipboardFileBrokerChild(child, {
    readinessTimeoutMs: 5,
    restoreTimeoutMs: 5,
  });
  child.writeStdout("PUBLISHING\r\n");

  let startupError;
  await assert.rejects(opening, (error) => {
    startupError = error;
    return error.message === "clipboard broker ready acknowledgement timed out";
  });

  assert.equal(startupError.clipboardState, "unknown");
  assert.equal(startupError.clipboardRestored, false);
  assert.equal(startupError.clipboardUnchanged, false);
  assert.equal(startupError.clipboardBrokerPid, 43127);
  assert.equal(startupError.clipboardBrokerExited, false);
  assert.equal(startupError.clipboardBrokerExitCode, null);
  assert.equal(startupError.clipboardRecovery.state, "unknown");
  assert.equal(startupError.clipboardRecovery.pid, 43127);
  assert.equal(startupError.clipboardRecovery.exited, false);
  assert.equal(startupError.clipboardRecovery.canRestore, true);
  assert.deepEqual(child.restoreCommands, ["restore\n"]);
  assert.equal(child.killed, false);
  assert.equal(child.exitCode, null);
});

/** @author kongweiguang 错序 READY 不得伪装发布完成；协议损坏时保留 unknown 并等待真实恢复 ACK。 */
test("clipboard broker rejects out-of-order readiness without claiming clipboard safety", async () => {
  const child = createFakeClipboardBrokerChild();
  const opening = superviseClipboardFileBrokerChild(child, {
    readinessTimeoutMs: 5,
    restoreTimeoutMs: 5,
  });
  child.writeStdout("READY\r\n");

  let startupError;
  await assert.rejects(opening, (error) => {
    startupError = error;
    return error.message === "clipboard broker closed before protocol acknowledgement";
  });

  assert.equal(startupError.clipboardState, "unknown");
  assert.equal(startupError.clipboardRestored, false);
  assert.equal(startupError.clipboardUnchanged, false);
  assert.deepEqual(child.restoreCommands, ["restore\n"]);
  assert.equal(child.killed, false);
});

/** @author kongweiguang 恢复 ACK 可先于失败进程退出码；状态只由 ACK 决定，不被非零启动结果抹掉。 */
test("clipboard broker retains a confirmed restore even when startup exits unsuccessfully", async () => {
  const child = createFakeClipboardBrokerChild();
  child.onRestoreRequest = () => {
    child.writeStdout("RESTORED\n");
    child.finish(1);
  };
  const opening = superviseClipboardFileBrokerChild(child, {
    readinessTimeoutMs: 5,
    restoreTimeoutMs: 100,
  });
  child.writeStdout("PUBLISHING\n");

  let startupError;
  await assert.rejects(opening, (error) => {
    startupError = error;
    return error.message === "clipboard broker ready acknowledgement timed out";
  });

  assert.equal(startupError.clipboardState, "restored");
  assert.equal(startupError.clipboardRestored, true);
  assert.equal(startupError.clipboardUnchanged, false);
  assert.equal(startupError.clipboardBrokerExited, true);
  assert.equal(startupError.clipboardBrokerExitCode, 1);
  assert.equal(startupError.clipboardBrokerSignalCode, null);
  assert.equal(startupError.clipboardRecovery.canRestore, false);
  assert.equal(child.killed, false);
});

/** @author kongweiguang 自然退出且无 RESTORED 时原对象已离开唯一 owner，状态只能 unknown 并保留退出事实。 */
test("clipboard broker does not claim recovery after an unacknowledged published process exits", async () => {
  const child = createFakeClipboardBrokerChild();
  const opening = superviseClipboardFileBrokerChild(child, {
    readinessTimeoutMs: 100,
    restoreTimeoutMs: 100,
  });
  child.writeStdout("PUBLISHING\r\nREADY\r\n");
  const broker = await opening;
  assert.equal(broker.clipboardState, "published");

  child.finish(7);

  assert.equal(broker.clipboardState, "unknown");
  await assert.rejects(broker.restore(), (error) => {
    assert.equal(error.clipboardState, "unknown");
    assert.equal(error.clipboardRestored, false);
    assert.equal(error.clipboardBrokerPid, 43127);
    assert.equal(error.clipboardBrokerExited, true);
    assert.equal(error.clipboardBrokerExitCode, 7);
    assert.equal(error.clipboardBrokerSignalCode, null);
    assert.equal(error.clipboardRecovery.pid, 43127);
    assert.equal(error.clipboardRecovery.exited, true);
    assert.equal(error.clipboardRecovery.exitCode, 7);
    assert.equal(error.clipboardRecovery.canRestore, false);
    return true;
  });
  assert.deepEqual(child.restoreCommands, []);
  assert.equal(child.killed, false);
});

/** @author kongweiguang ACK 证明原对象已回放；非零退出仍是操作失败且退出码必须保留。 */
test("clipboard broker reports restoration ACK separately from a nonzero exit", async () => {
  const child = createFakeClipboardBrokerChild();
  const opening = superviseClipboardFileBrokerChild(child, {
    readinessTimeoutMs: 100,
    restoreTimeoutMs: 100,
  });
  child.writeStdout("PUBLISHING\nREADY\n");
  const broker = await opening;
  child.onRestoreRequest = () => {
    child.writeStdout("RESTORED\n");
    child.finish(9);
  };

  await assert.rejects(broker.restore(), (error) => {
    assert.match(error.message, /exited unsuccessfully after restoration acknowledgement/u);
    assert.equal(error.clipboardState, "restored");
    assert.equal(error.clipboardRestored, true);
    assert.equal(error.clipboardBrokerExited, true);
    assert.equal(error.clipboardBrokerExitCode, 9);
    assert.equal(error.clipboardRecovery.canRestore, false);
    return true;
  });
  assert.equal(child.killed, false);
});

/** @author kongweiguang 只有自然退出且从未进入发布阶段才能报 unchanged，不把不可观测值压成已恢复。 */
test("clipboard broker reports unchanged only when it exits before publication", async () => {
  const child = createFakeClipboardBrokerChild();
  const opening = superviseClipboardFileBrokerChild(child, {
    readinessTimeoutMs: 5,
    restoreTimeoutMs: 5,
  });
  setImmediate(() => child.finish(1));

  let startupError;
  await assert.rejects(opening, (error) => {
    startupError = error;
    return error.message === "clipboard broker closed before protocol acknowledgement";
  });

  assert.equal(startupError.clipboardState, "unchanged");
  assert.equal(startupError.clipboardRestored, false);
  assert.equal(startupError.clipboardUnchanged, true);
  assert.equal(startupError.clipboardRecovery.state, "unchanged");
  assert.equal(child.killed, false);
});

/** @author kongweiguang 测试可以缩短等待，但不可借监督器参数抬高生产恢复期限。 */
test("clipboard broker supervisor refuses extended deadlines", async () => {
  const child = createFakeClipboardBrokerChild();
  await assert.rejects(
    superviseClipboardFileBrokerChild(child, { readinessTimeoutMs: 10_001 }),
    /deadlines must stay within the production budget/u,
  );
  assert.equal(child.restoreCommands.length, 0);
  assert.equal(child.killed, false);
});

/** @author kongweiguang 只用显式 opt-in 验证真实 STA/CF_HDROP；未知状态保留合成文件和 broker 现场。 */
test(
  "clipboard broker serves a synthetic file drop while pumping STA messages",
  {
    skip:
      process.platform === "win32" && process.env.JA_RUN_CLIPBOARD_BROKER_TEST === "1"
        ? false
        : "requires Windows and explicit JA_RUN_CLIPBOARD_BROKER_TEST=1 because it temporarily sets the OS clipboard",
  },
  async () => {
    const isolatedRoot = await mkdtemp(join(tmpdir(), "ja-clipboard-broker-"));
    const syntheticPath = join(isolatedRoot, "synthetic-fixture.txt");
    await writeFile(syntheticPath, "synthetic CF_HDROP fixture\n", "utf8");
    let broker;
    let restored = false;
    let failure;
    try {
      broker = await startClipboardFileBroker(syntheticPath);
      assert.equal(await probeSyntheticClipboardPath(syntheticPath), "MATCH");
      await broker.restore();
      assert.equal(broker.clipboardState, "restored");
      restored = true;
    } catch (error) {
      failure = error instanceof Error ? error : new Error("clipboard broker test failed");
    } finally {
      const recovery = broker ?? failure?.clipboardRecovery;
      const canRestore = broker?.clipboardCanRestore ?? failure?.clipboardRecovery?.canRestore;
      if (recovery && !restored && canRestore) {
        try {
          await recovery.restore();
          restored = true;
        } catch (cleanupError) {
          if (failure === undefined)
            failure =
              cleanupError instanceof Error ? cleanupError : new Error("clipboard restore failed");
        }
      }

      const cleanupState =
        broker?.clipboardState ??
        failure?.clipboardRecovery?.state ??
        failure?.clipboardState ??
        "unknown";
      if (failure !== undefined) {
        failure.clipboardCleanupState = cleanupState;
        failure.clipboardCleanupRestored = cleanupState === "restored";
        const exitCode =
          broker?.clipboardBrokerExitCode ?? failure?.clipboardRecovery?.exitCode ?? null;
        failure.clipboardCleanupExitCode = Number.isSafeInteger(exitCode) ? exitCode : null;
      }

      restored = cleanupState === "restored";
      if (restored || cleanupState === "unchanged")
        await rm(isolatedRoot, { recursive: true, force: true });
    }
    if (failure !== undefined) throw failure;
  },
);
