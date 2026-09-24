// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 真实 JVM JAR + Tauri/WebView2 的公开工作过程验收 runner。
 *
 * 每次运行复用 review-redesign-production 的隔离 profile、CDP 和进程清理，只把 Provider
 * 替换成本轮 loopback fixture。它验证公开 summary/commentary 与 read/shell Tool 的实时顺序、
 * SQLite 历史 reload 顺序及 Tool 去重，不代表 Native Image 或付费 Provider 验收。
 */

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { promisify } from "node:util";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";
import {
  conversationProgressFixtureMarkers,
  startConversationProgressFixture,
} from "./fixtures/conversation-progress.mjs";

const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const PROMPT = "请先读取隔离 fixture，再执行一次低影响 Shell 回显，最后总结结果。";
const TITLE = "Conversation Progress E2E";
const PROGRESS_TURN_WALL_TIMEOUT_MS = 120_000;
const PROGRESS_INVOKE_LOG_KEY = "__JA_CONVERSATION_PROGRESS_INVOKES__";
const ACTIVE_STREAM_REGISTRY_SOURCE_PATH =
  "app-server/src/main/java/io/github/kongweiguang/ja/transport/rpc/runtime/ActiveStreamRegistry.java";
const execFileAsync = promisify(execFile);
const SOURCE_IDENTITY_FILES = Object.freeze({
  controller: "apps/desktop/src/features/conversation/application/useConversationController.ts",
  interaction:
    "apps/desktop/src/features/conversation/application/useConversationInteractionController.ts",
  timelineStore: "apps/desktop/src/features/conversation/application/timelineStore.ts",
  timelineReducer: "apps/desktop/src/features/conversation/domain/timelineReducer.ts",
  timelineContracts: "apps/desktop/src/features/conversation/domain/timelineContracts.ts",
  historyApi: "apps/desktop/src/api/tauri/history.ts",
  chatTimeline: "apps/desktop/src/features/conversation/ui/timeline/ChatTimeline.tsx",
  workProcess: "apps/desktop/src/features/conversation/ui/timeline/WorkProcess.tsx",
  activeStreamRegistry: ACTIVE_STREAM_REGISTRY_SOURCE_PATH,
  rpcSession:
    "app-server/src/main/java/io/github/kongweiguang/ja/transport/rpc/runtime/RpcSession.java",
  threadHistoryHandler:
    "app-server/src/main/java/io/github/kongweiguang/ja/transport/rpc/handler/ThreadHistoryHandler.java",
  rpcResults:
    "app-server/src/main/java/io/github/kongweiguang/ja/transport/rpc/protocol/RpcResults.java",
  threadReadContract:
    "app-server/src/main/java/io/github/kongweiguang/ja/transport/rpc/protocol/ThreadReadContract.java",
  rustHistoryModel: "src-tauri/src/app_runtime/interface/history_model.rs",
  mybatisHistoryService:
    "app-server/src/main/java/io/github/kongweiguang/ja/infrastructure/persistence/repository/MybatisHistoryService.java",
  agentLoopPersistence:
    "app-server/src/main/java/io/github/kongweiguang/ja/conversation/application/loop/AgentLoopPersistence.java",
  turnEventSink:
    "app-server/src/main/java/io/github/kongweiguang/ja/conversation/port/in/TurnEventSink.java",
  threadSnapshot:
    "app-server/src/main/java/io/github/kongweiguang/ja/conversation/domain/ThreadSnapshot.java",
});

/** 将真窗阶段限制在统一 deadline 内，避免 selector 漂移隐藏真正失败阶段。 */
function timeout(deadline) {
  return Math.max(1, Math.min(30_000, deadline - Date.now()));
}

/** 以固定 SHA-256 读取源码身份；失败只记录 unavailable，不把文件内容或配置带入验收报告。 */
async function sha256File(path) {
  const digest = createHash("sha256");
  digest.update(await readFile(path));
  return digest.digest("hex");
}

/** 读取 git HEAD 与 dirty 布尔状态，报告不保存文件名列表，避免把工作区路径扩散到证据。 */
async function readGitIdentity() {
  try {
    const [headResult, statusResult] = await Promise.all([
      execFileAsync("git.exe", ["rev-parse", "HEAD"], {
        cwd: repoRoot,
        windowsHide: true,
        timeout: 15_000,
        maxBuffer: 64 * 1024,
      }),
      execFileAsync("git.exe", ["status", "--porcelain=v1", "--untracked-files=all"], {
        cwd: repoRoot,
        windowsHide: true,
        timeout: 15_000,
        maxBuffer: 8 * 1024 * 1024,
      }),
    ]);
    const statusLines = statusResult.stdout.split(/\r?\n/u).filter((line) => line.length > 0);
    return {
      head: headResult.stdout.trim(),
      dirty: statusLines.length > 0,
      dirtyEntryCount: statusLines.length,
    };
  } catch (error) {
    return {
      head: null,
      dirty: null,
      dirtyEntryCount: null,
      status: "unavailable",
      errorCategory: error?.code === "ENOENT" ? "git_unavailable" : "git_read_failed",
    };
  }
}

/** 对每个关键跨栈源文件只发布相对路径、大小和哈希，缺失时不伪造源码身份。 */
async function readSourceFileIdentities() {
  const entries = {};
  for (const [name, relativePath] of Object.entries(SOURCE_IDENTITY_FILES)) {
    const path = resolve(repoRoot, relativePath);
    try {
      const metadata = await stat(path);
      if (!metadata.isFile()) throw new Error("source identity path is not a file");
      entries[name] = {
        path: relativePath,
        size: metadata.size,
        sha256: await sha256File(path),
      };
    } catch {
      entries[name] = { path: relativePath, status: "unavailable" };
    }
  }
  return entries;
}

/** 只在隔离 runner 的预期 Cargo 目标存在时记录 Ja 可执行文件哈希，否则明确标记未取得。 */
async function readRunnerJaExecutableIdentity(cargoTargetDirectory) {
  if (typeof cargoTargetDirectory !== "string" || cargoTargetDirectory.length === 0)
    return { status: "unavailable", reason: "cargo_target_directory_missing" };
  const path = join(cargoTargetDirectory, "debug", "ja.exe");
  try {
    const metadata = await stat(path);
    if (!metadata.isFile()) throw new Error("runner executable path is not a file");
    return { status: "available", path, size: metadata.size, sha256: await sha256File(path) };
  } catch {
    return { status: "unavailable", reason: "runner_executable_not_observed" };
  }
}

/** 收集源码、JAR、隔离 runner 可执行文件和当前 WebView 页面身份，不读取配置或凭据。 */
async function collectRunIdentity(page, jarPath, cargoTargetDirectory, consoleErrors, pageErrors) {
  const [git, sources, jaExecutable] = await Promise.all([
    readGitIdentity(),
    readSourceFileIdentities(),
    readRunnerJaExecutableIdentity(cargoTargetDirectory),
  ]);
  let jar;
  if (typeof jarPath === "string") {
    try {
      const metadata = await stat(jarPath);
      jar = { path: jarPath, size: metadata.size, sha256: await sha256File(jarPath) };
    } catch {
      jar = { status: "unavailable", reason: "jar_not_observed" };
    }
  } else {
    jar = { status: "unavailable", reason: "jar_path_not_supplied" };
  }
  const window = await page
    .evaluate(() => ({
      title: globalThis.document.title,
      url: globalThis.location.href,
      viewport: {
        width: globalThis.innerWidth,
        height: globalThis.innerHeight,
        devicePixelRatio: globalThis.devicePixelRatio,
      },
      userAgent: globalThis.navigator.userAgent,
      webViewVersion:
        /(?:Edg|WebView2)\/([\d.]+)/u.exec(globalThis.navigator.userAgent)?.[1] ?? null,
    }))
    .catch(() => ({
      title: null,
      url: null,
      viewport: null,
      userAgent: null,
      webViewVersion: null,
    }));
  return {
    git,
    sources,
    jar,
    jaExecutable,
    window,
    consoleErrors: consoleErrors.slice(-16),
    pageErrors: pageErrors.slice(-16),
  };
}

/** 将真窗异常归入固定类别，避免失败报告携带易变的 selector 或路径细节。 */
function runnerErrorCategory(error) {
  const message = String(error?.message ?? error);
  if (error?.name === "AssertionError" || error?.code === "ERR_ASSERTION") return "assertion";
  if (/超时|timeout/u.test(message)) return "timeout";
  if (/locator|selector/u.test(message)) return "selector";
  if (/protocol|webview|cdp/u.test(message)) return "runtime_protocol";
  return "runner";
}

/** 只保留 fixture 的阶段与已知回合，防止诊断意外记录 Provider 请求或 Tool 正文。 */
function safeFixtureFailureSnapshot(snapshot) {
  const stages = Array.isArray(snapshot?.stages) ? snapshot.stages.slice(-16) : [];
  const attempts = Array.isArray(snapshot?.attempts)
    ? snapshot.attempts.slice(-8).map((attempt) => ({
        kind: attempt?.kind === "title" ? "title" : "turn",
        step: Number.isSafeInteger(attempt?.step) ? attempt.step : null,
        progressInstruction: attempt?.progressInstruction === true,
        outcome:
          attempt?.outcome === "failed" || attempt?.outcome === "completed"
            ? attempt.outcome
            : undefined,
      }))
    : [];
  return { stage: stages.at(-1) ?? "none", stages, attempts };
}

/**
 * 在 E2E composition 的 nativeInvoke delegate 边界观测 command 生命周期；这里不改 Tauri
 * 内部 bridge，因为生产 adapter 已由 Vite E2E plugin 换成 tests/app/e2e/nativeInvoke.ts，
 * 该边界才是 controller、history adapter 和 runtime adapter 的共同真实入口。函数由
 * Playwright 注入到每个新 document，所有输出只保留命令名、阶段和脱敏流摘要。
 */
export function installConversationProgressInvokeProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  const records = [];
  const heldThreadReads = [];
  let invocationSequence = 0;
  /** 真窗可能跨多个回合运行；有界记录避免诊断本身改变 renderer 的内存和时序。 */
  const appendBoundedRecord = (record) => {
    records.push(record);
    if (records.length > 512) records.splice(0, records.length - 512);
  };
  globalThis.__JA_CONVERSATION_PROGRESS_INVOKES__ = records;
  globalThis.__JA_CONVERSATION_THREAD_READ_HOLD__ = false;
  globalThis.__JA_CONVERSATION_THREAD_READ_RELEASE__ = () => {
    while (heldThreadReads.length > 0) heldThreadReads.shift()();
  };
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = request?.command;
    const watched = typeof command === "string" && command.startsWith("ja_");
    const invocationId = ++invocationSequence;
    if (watched)
      appendBoundedRecord({ invocationId, command, phase: "start", at: performance.now() });
    try {
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      if (watched) {
        const state = result !== null && typeof result === "object" ? result : {};
        const liveStream =
          command === "ja_thread_read" &&
          state.liveStream !== null &&
          typeof state.liveStream === "object"
            ? {
                turnId:
                  typeof state.liveStream.turnId === "string" ? state.liveStream.turnId : undefined,
                streamSeq: Number.isSafeInteger(state.liveStream.streamSeq)
                  ? state.liveStream.streamSeq
                  : undefined,
                segmentCount: Array.isArray(state.liveStream.segments)
                  ? state.liveStream.segments.length
                  : undefined,
              }
            : command === "ja_thread_read"
              ? null
              : undefined;
        appendBoundedRecord({
          invocationId,
          command,
          phase: "received",
          at: performance.now(),
          ...(command === "ja_thread_read"
            ? {
                threadId: typeof state.threadId === "string" ? state.threadId : undefined,
                revision: Number.isSafeInteger(state.revision) ? state.revision : undefined,
                liveStream,
              }
            : {}),
        });
        if (command === "ja_thread_read" && globalThis.__JA_CONVERSATION_THREAD_READ_HOLD__) {
          await new Promise((resolvePromise) => heldThreadReads.push(resolvePromise));
        }
        appendBoundedRecord({
          invocationId,
          command,
          phase: "resolved",
          at: performance.now(),
          status: typeof state.status === "string" ? state.status : undefined,
        });
      }
      return result;
    } catch (error) {
      if (watched) {
        const value = error !== null && typeof error === "object" ? error : {};
        appendBoundedRecord({
          invocationId,
          command,
          phase: "rejected",
          at: performance.now(),
          errorCode: typeof value.code === "string" ? value.code.slice(0, 96) : undefined,
        });
      }
      throw error;
    }
  };
}

/**
 * 注册 delegate probe 一次；必须在 reload 前注册，确保新 document 的首个真实 adapter 调用
 * 就可观测。重复调用只复用同一 BrowserContext，避免多层 probe 改变 command 时序。
 */
const instrumentedContexts = new WeakSet();

async function instrumentRuntimeInvocations(page) {
  const context = page.context();
  if (instrumentedContexts.has(context)) return;
  await context.addInitScript(installConversationProgressInvokeProbe);
  instrumentedContexts.add(context);
}

/**
 * 用生产 RuntimeHost adapter 发起一次无副作用状态读取，确认 probe 真的位于 adapter
 * delegate 边界，而不是只在页面上挂了一个未被调用的函数；失败立即终止，避免 fixture
 * 的文本门闩把 Provider 等到 30 秒后才暴露问题。
 */
async function assertRuntimeInvocationProbe(page) {
  const result = await page.evaluate(async (key) => {
    const records = globalThis[key];
    const before = Array.isArray(records) ? records.length : 0;
    const probeInstalled = typeof globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ === "function";
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const state = await createRuntimeHostAdapter().state();
    const entries = Array.isArray(globalThis[key]) ? globalThis[key].slice(before) : [];
    return {
      boundary: "e2e_nativeInvoke_delegate",
      probeInstalled,
      observedCommand: entries.find((entry) => entry?.command === "ja_runtime_state")?.command,
      startObserved: entries.some(
        (entry) => entry?.command === "ja_runtime_state" && entry.phase === "start",
      ),
      resolvedObserved: entries.some(
        (entry) => entry?.command === "ja_runtime_state" && entry.phase === "resolved",
      ),
      runtimeStatus: state.status,
    };
  }, PROGRESS_INVOKE_LOG_KEY);
  assert.equal(result.probeInstalled, true, "E2E nativeInvoke delegate probe was not installed");
  assert.equal(result.observedCommand, "ja_runtime_state", "probe missed adapter state command");
  assert.equal(result.startObserved, true, "probe missed adapter command start");
  assert.equal(result.resolvedObserved, true, "probe missed adapter command resolution");
  assert.ok(
    result.runtimeStatus === "ready" || result.runtimeStatus === "busy",
    `runtime state is not ready for progress E2E: ${result.runtimeStatus}`,
  );
  return result;
}

/** 读取失败时的最小 Tauri 投影，确认 UI 受限是否由 native runtime 状态造成而非 DOM 选择器漂移。 */
async function readConversationProgressRuntimeDiagnostics(page) {
  if (page === undefined) return { status: "page_unavailable" };
  return page
    .evaluate(async () => {
      const internals = globalThis.__TAURI_INTERNALS__;
      const errorProjection = (error) => {
        const value = error !== null && typeof error === "object" ? error : {};
        return {
          code: typeof value.code === "string" ? value.code.slice(0, 96) : undefined,
          message: typeof value.message === "string" ? value.message.slice(0, 256) : undefined,
          retryable: typeof value.retryable === "boolean" ? value.retryable : undefined,
        };
      };
      const invoke = async (command, project) => {
        if (internals === undefined || typeof internals.invoke !== "function") {
          return { status: "invoke_unavailable" };
        }
        try {
          return project(await internals.invoke(command, {}));
        } catch (error) {
          return { error: errorProjection(error) };
        }
      };
      return {
        appReady:
          globalThis.document.querySelector(".ja-shell")?.getAttribute("data-app-ready") ?? null,
        runtimeLabel:
          globalThis.document
            .querySelector('[aria-label^="本地运行时："]')
            ?.getAttribute("aria-label") ?? null,
        runtimeState: await invoke("ja_runtime_state", (value) => {
          const state = value !== null && typeof value === "object" ? value : {};
          return {
            status: typeof state.status === "string" ? state.status : "invalid",
            generation: Number.isSafeInteger(state.generation) ? state.generation : undefined,
            serverInstanceIdPresent:
              typeof state.serverInstanceId === "string" && state.serverInstanceId.length > 0,
          };
        }),
        recoveryState: await invoke("ja_runtime_recovery_state", (value) => {
          const recovery = value !== null && typeof value === "object" ? value : {};
          return {
            required: recovery.required === true,
            acknowledgeable: recovery.acknowledgeable === true,
            recoveryIdPresent:
              typeof recovery.recoveryId === "string" && recovery.recoveryId.length > 0,
            revision: Number.isSafeInteger(recovery.revision) ? recovery.revision : undefined,
          };
        }),
        nativeInvokeProbe: {
          boundary: "e2e_nativeInvoke_delegate",
          installed: typeof globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ === "function",
          recordCount: Array.isArray(globalThis["__JA_CONVERSATION_PROGRESS_INVOKES__"])
            ? globalThis["__JA_CONVERSATION_PROGRESS_INVOKES__"].length
            : 0,
          commands: Array.isArray(globalThis["__JA_CONVERSATION_PROGRESS_INVOKES__"])
            ? [
                ...new Set(
                  globalThis["__JA_CONVERSATION_PROGRESS_INVOKES__"]
                    .map((record) => record?.command)
                    .filter((command) => typeof command === "string")
                    .slice(-48),
                ),
              ]
            : [],
        },
        invocations: Array.isArray(globalThis["__JA_CONVERSATION_PROGRESS_INVOKES__"])
          ? globalThis["__JA_CONVERSATION_PROGRESS_INVOKES__"].slice(-48)
          : [],
      };
    })
    .catch(() => ({ status: "evaluate_failed" }));
}

/** 读取隔离 Java 日志的有限尾部并脱敏 fixture 根与凭据样式字段，保留超时根因的异常类型。 */
async function readIsolatedRuntimeLogs(home) {
  if (typeof home !== "string") return { status: "unavailable" };
  const logDirectory = join(home, "logs", "java");
  const logs = {};
  for (const name of ["app-server-error.log", "app-server.log"]) {
    try {
      const path = join(logDirectory, name);
      const metadata = await stat(path);
      if (!metadata.isFile() || metadata.size > 4 * 1024 * 1024) {
        logs[name] = { status: "invalid_size" };
        continue;
      }
      logs[name] = {
        status: "available",
        tail: (await readFile(path, "utf8"))
          .split(/\r?\n/u)
          .filter((line) => line.length > 0)
          .slice(-120)
          .map((line) =>
            line
              .replaceAll(home, "<JA_HOME>")
              .replace(/https?:\/\/[^\s]+/gu, "<URL>")
              .replace(
                /\b(?:api[_-]?key|authorization|token|credential)\s*[=:]\s*\S+/giu,
                "<REDACTED>",
              )
              .slice(0, 800),
          ),
      };
    } catch {
      logs[name] = { status: "unavailable" };
    }
  }
  return logs;
}

/** 等待最终 DOM 条件，使用有界轮询而不是任意 sleep 掩盖事件丢失。 */
async function waitForCondition(label, predicate, deadline) {
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`${label} 超时`);
}

/**
 * 安装真实应用 DOM 的窄采样器；只观测后台恢复窗口，不把 Turn 自身的合法 sending/active
 * 禁用计入闪烁。MutationObserver 记录属性变化，短周期采样补上 WebView2 合并属性提交的边界。
 * 采样器由 runner 主动关闭，避免 timer 在页面 reload 或失败 cleanup 后继续持有 renderer。
 */
async function installConversationFlickerProbe(page) {
  await page.evaluate(() => {
    const previous = globalThis.__JA_CONVERSATION_FLICKER_PROBE__;
    previous?.stop?.();
    const evidence = {
      phase: "idle",
      samples: [],
      disabledTransitions: { newConversation: 0, composer: 0 },
      backgroundReadWindow: {
        started: false,
        ended: false,
        newConversationDisabledTransitions: 0,
        composerDisabledTransitions: 0,
        newConversationStates: [],
        composerStates: [],
      },
      responseTextRegressions: 0,
      progressTextRegressions: 0,
      spinnerStartTimes: { history: [], response: [] },
    };
    let last = undefined;
    const responseLengths = new WeakMap();
    /** 统一限制采样窗口，保证 5 秒静默观察和异常重试不会让证据数组无界增长。 */
    const appendBoundedSample = (samples, value, limit) => {
      samples.push(value);
      if (samples.length > limit) samples.shift();
    };
    const readState = () => {
      const newConversation = globalThis.document.querySelector('button[aria-label="新会话"]');
      const composer = globalThis.document.querySelector('textarea[aria-label="消息"]');
      const history = globalThis.document.querySelector('[aria-label="最近对话列表"]');
      const responseNodes = [
        ...globalThis.document.querySelectorAll('.ja-chat-message-final[data-role="response"]'),
      ];
      for (const response of responseNodes) {
        const length = response.textContent?.length ?? 0;
        const previousLength = responseLengths.get(response);
        if (previousLength !== undefined && length < previousLength)
          evidence.responseTextRegressions += 1;
        responseLengths.set(response, length);
      }
      const progressNodes = [
        ...globalThis.document.querySelectorAll(
          ".ja-work-process .ja-work-step--commentary, .ja-work-process .ja-work-step--reasoning",
        ),
      ];
      for (const progress of progressNodes) {
        const length = progress.textContent?.length ?? 0;
        const previousLength = responseLengths.get(progress);
        if (previousLength !== undefined && length < previousLength)
          evidence.progressTextRegressions += 1;
        responseLengths.set(progress, length);
      }
      return {
        at: globalThis.performance.now(),
        newConversationDisabled: newConversation?.hasAttribute("disabled") ?? null,
        composerDisabled: composer?.hasAttribute("disabled") ?? null,
        historyBusy: history?.getAttribute("aria-busy") === "true",
        historyLoadingVisible:
          globalThis.document.querySelector(".ja-navigation-history-loading") !== null,
        responseTextLengths: responseNodes.map((response) => response.textContent?.length ?? 0),
        progressTextLengths: progressNodes.map((progress) => progress.textContent?.length ?? 0),
      };
    };
    const sample = (source) => {
      const state = readState();
      state.source = source;
      if (last !== undefined) {
        if (
          state.newConversationDisabled !== null &&
          last.newConversationDisabled !== null &&
          state.newConversationDisabled !== last.newConversationDisabled
        ) {
          evidence.disabledTransitions.newConversation += 1;
          if (evidence.phase === "background-resync") {
            evidence.backgroundReadWindow.newConversationDisabledTransitions += 1;
          }
        }
        if (
          state.composerDisabled !== null &&
          last.composerDisabled !== null &&
          state.composerDisabled !== last.composerDisabled
        ) {
          evidence.disabledTransitions.composer += 1;
          if (evidence.phase === "background-resync") {
            evidence.backgroundReadWindow.composerDisabledTransitions += 1;
          }
        }
      }
      if (evidence.phase === "background-resync") {
        appendBoundedSample(
          evidence.backgroundReadWindow.newConversationStates,
          state.newConversationDisabled,
          128,
        );
        appendBoundedSample(
          evidence.backgroundReadWindow.composerStates,
          state.composerDisabled,
          128,
        );
      }
      appendBoundedSample(evidence.samples, state, 256);
      last = state;
      return state;
    };
    const observer = new globalThis.MutationObserver(() => sample("mutation"));
    observer.observe(globalThis.document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["disabled", "aria-busy", "class", "data-response-state"],
    });
    const interval = globalThis.setInterval(() => sample("interval"), 50);
    sample("initial");
    globalThis.__JA_CONVERSATION_FLICKER_PROBE__ = {
      evidence,
      beginBackgroundResync() {
        evidence.phase = "background-resync";
        evidence.backgroundReadWindow.started = true;
        sample("background-start");
      },
      endBackgroundResync() {
        sample("background-end");
        evidence.backgroundReadWindow.ended = true;
        evidence.phase = "streaming-after-resync";
      },
      sample,
      spinnerStartTimes() {
        const animationStart = (selector, animationName) => {
          const target = globalThis.document.querySelector(selector);
          const animation = target
            ?.getAnimations()
            .find((candidate) => candidate.animationName === animationName);
          return typeof animation?.startTime === "number" ? animation.startTime : null;
        };
        const values = {
          history: animationStart(
            ".ja-navigation-thread-state.is-running svg",
            "ja-navigation-thread-spin",
          ),
          response: animationStart(".ja-chat-message-draft", "ja-chat-response-enter"),
        };
        appendBoundedSample(evidence.spinnerStartTimes.history, values.history, 32);
        appendBoundedSample(evidence.spinnerStartTimes.response, values.response, 32);
        return values;
      },
      stop() {
        observer.disconnect();
        globalThis.clearInterval(interval);
      },
    };
  });
}

/**
 * 在 reload 后重新绑定动画生命周期监听；监听器不跨 document 存活，缺失 evidence 必须
 * 直接报错，不能用零值掩盖新 Turn 的真实动画是否触发。调用方先确保 DOM flicker probe 存在。
 */
async function installConversationAnimationReplayProbe(page) {
  await page.evaluate(() => {
    const evidence = globalThis.__JA_CONVERSATION_FLICKER_PROBE__?.evidence;
    if (evidence === undefined)
      throw new Error("flicker probe is unavailable for animation evidence");
    globalThis.__JA_CONVERSATION_ANIMATION_LISTENER__?.stop?.();
    evidence.animationReplays = { response: 0, history: 0 };
    globalThis.__JA_CONVERSATION_FLICKER_EVIDENCE__ = evidence;
    const listener = (event) => {
      const target = event.target;
      if (!(target instanceof globalThis.Element)) return;
      if (target.matches(".ja-chat-message-draft")) evidence.animationReplays.response += 1;
      if (target.matches(".ja-navigation-thread-state.is-running svg"))
        evidence.animationReplays.history += 1;
    };
    globalThis.document.addEventListener("animationstart", listener, true);
    globalThis.__JA_CONVERSATION_ANIMATION_LISTENER__ = {
      stop() {
        globalThis.document.removeEventListener("animationstart", listener, true);
      },
    };
  });
}

/** 读取已安装的响应动画计数；探针未安装或结构不完整时立即失败，不返回 fallback 0。 */
export async function readResponseAnimationReplayCount(page) {
  return page.evaluate(() => {
    const replays = globalThis.__JA_CONVERSATION_FLICKER_EVIDENCE__?.animationReplays;
    if (replays === undefined || !Number.isSafeInteger(replays.response))
      throw new Error("conversation animation evidence is unavailable");
    return replays.response;
  });
}

/** 由 driver 注入一次可回退的 Timeline resync 请求；controller 仍负责真实 ja_thread_read 和合并。 */
async function requestTimelineResync(page, threadId) {
  return page.evaluate(async (expectedThreadId) => {
    const module = await import("/src/features/conversation/index.ts");
    const store = module.useTimelineStore;
    const registryStore = globalThis.__JA_TIMELINE_STORE_V1__;
    const current = store.getState();
    if (current.threads[expectedThreadId] === undefined)
      throw new Error("resync target is not loaded in the live timeline");
    store.getState().requestThreadResync(expectedThreadId);
    return {
      threadId: expectedThreadId,
      generation: current.handshake.generation,
      serverInstanceId: current.serverInstanceId ?? null,
      publicStoreSingleton: registryStore === store,
    };
  }, threadId);
}

/** 读取 driver 记录的 thread/read 收敛证据；正文和参数始终留在 renderer/native 内。 */
async function readThreadReadEvidence(page) {
  return page.evaluate((key) => {
    const records = globalThis[key];
    if (!Array.isArray(records)) return { starts: 0, startIds: [], received: [], resolved: 0 };
    const reads = records.filter((record) => record?.command === "ja_thread_read");
    return {
      starts: reads.filter((record) => record.phase === "start").length,
      startIds: reads
        .filter((record) => record.phase === "start" && Number.isSafeInteger(record.invocationId))
        .map((record) => record.invocationId),
      received: reads
        .filter((record) => record.phase === "received")
        .map((record) => ({
          invocationId: record.invocationId,
          threadId: record.threadId,
          revision: record.revision,
          liveStream: record.liveStream,
        })),
      resolved: reads.filter((record) => record.phase === "resolved").length,
    };
  }, PROGRESS_INVOKE_LOG_KEY);
}

/** 读取有界 DOM 采样摘要；完整 samples 不写入报告，避免 5 秒监测放大证据文件。 */
async function readFlickerEvidence(page) {
  return page.evaluate(() => {
    const evidence = globalThis.__JA_CONVERSATION_FLICKER_PROBE__?.evidence;
    if (evidence === undefined) return { status: "unavailable" };
    return {
      status: "available",
      sampleCount: evidence.samples.length,
      disabledTransitions: { ...evidence.disabledTransitions },
      backgroundReadWindow: {
        ...evidence.backgroundReadWindow,
        newConversationStates: [...evidence.backgroundReadWindow.newConversationStates],
        composerStates: [...evidence.backgroundReadWindow.composerStates],
      },
      responseTextRegressions: evidence.responseTextRegressions,
      progressTextRegressions: evidence.progressTextRegressions,
      spinnerStartTimes: {
        history: [...evidence.spinnerStartTimes.history],
        response: [...evidence.spinnerStartTimes.response],
      },
      animationReplays: { ...evidence.animationReplays },
      historyLoadingIndicatorMounts: evidence.historyLoadingIndicatorMounts,
    };
  });
}

/**
 * 只有本次 thread/read 返回完整活动流基线才算恢复证据；空快照必须立即失败，不能在
 * 同一响应上轮询到 deadline。Turn、revision、序号和段数共同构成可继续重放的最小合同。
 */
export function assertLiveStreamEvidence(readEvidence, threadId, invocationId) {
  const active = readEvidence.received.find(
    (record) =>
      record.threadId === threadId &&
      (invocationId === undefined || record.invocationId === invocationId) &&
      Number.isSafeInteger(record.revision) &&
      record.liveStream !== null &&
      typeof record.liveStream?.turnId === "string" &&
      record.liveStream.turnId.length > 0 &&
      record.liveStream?.streamSeq > 0 &&
      record.liveStream?.segmentCount > 0,
  );
  assert.ok(active, "this thread/read response must expose a complete liveStream baseline");
  return active.liveStream;
}

/** 校验启动时固定的隔离 Provider，再通过真实 typed adapter 建会话，不在验收中热改配置或密钥。 */
async function configureFixtureAndCreateThread(page, workspaceRoot, baseUrl) {
  return page.evaluate(
    async ({ cwd, endpoint, title }) => {
      const [{ TauriSettingsAdapter }, { createHistoryAdapter }] = await Promise.all([
        import("/src/api/tauri/settings.ts"),
        import("/src/api/tauri/history.ts"),
      ]);
      const settings = new TauriSettingsAdapter();
      const loaded = await settings.snapshot();
      const current = loaded.document.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (current === undefined) throw new Error("isolated provider_e2e is missing");
      const modelId = current.models[0]?.modelId;
      if (modelId === undefined) throw new Error("isolated provider_e2e model is missing");
      if (current.baseUrl !== endpoint) throw new Error("fixture Provider endpoint was not staged");
      const history = createHistoryAdapter();
      const workspace = await history.workspaceOpen({
        cwd,
        displayName: "Conversation Progress E2E",
      });
      const created = await history.threadCreate({
        cwd: workspace.root,
        title,
        providerId: "provider_e2e",
        modelId,
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
      return {
        threadId: created.threadId,
        workspaceId: workspace.workspaceId,
        workspaceName: workspace.displayName,
        modelId,
      };
    },
    { cwd: workspaceRoot, endpoint: baseUrl, title: TITLE },
  );
}

/** 通过真实侧栏恢复 runner Thread，绑定断言到 Java 返回的 identity 而非标题猜测。 */
async function selectThread(page, threadId, deadline) {
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await row.getAttribute("aria-current")) !== "page")
    await row.click({ timeout: timeout(deadline) });
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: timeout(deadline) },
  );
}

/** 等待真实应用 ready、运行时连接和消息 Composer；项目由后续 thread identity 恢复，避免隐藏窗口伪造目录选择。 */
async function waitForApplication(page, deadline) {
  await page
    .locator('.ja-shell[data-app-ready="true"]')
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
}

/** 选择已由 App Server `workspaceOpen` 建立的隔离项目，不把回复验收耦合到原生目录 picker。 */
async function selectProject(page, workspaceName, deadline) {
  const project = page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: workspaceName });
  await project.waitFor({ state: "visible", timeout: timeout(deadline) });
  if ((await project.getAttribute("aria-current")) !== "page") {
    await project.click({ timeout: timeout(deadline) });
  }
}

/** reload 后经生产 typed adapter 恢复 stopped generation，并二次确认 ready identity。 */
async function restoreRuntimeAfterReload(page, deadline) {
  await page.waitForFunction(
    () => typeof globalThis.__TAURI_INTERNALS__?.invoke === "function",
    undefined,
    { timeout: timeout(deadline) },
  );
  await page.evaluate(
    async ({ timeoutMs }) => {
      const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
      const adapter = createRuntimeHostAdapter();
      const deadlineAt = Date.now() + timeoutMs;
      let state = await adapter.state();
      while (state.status === "starting" || state.status === "stopping") {
        if (Date.now() >= deadlineAt)
          throw new Error(`runtime restore timed out in ${state.status}`);
        await new Promise((resolvePromise) => setTimeout(resolvePromise, 250));
        state = await adapter.state();
      }
      if (state.status === "stopped") state = await adapter.start();
      if (state.status !== "ready" && state.status !== "busy") {
        throw new Error(`runtime restore did not reach ready: ${state.status}`);
      }
      const confirmed = await adapter.state();
      if (confirmed.status !== "ready" && confirmed.status !== "busy") {
        throw new Error(`runtime restore confirmation failed: ${confirmed.status}`);
      }
    },
    { timeoutMs: timeout(deadline) },
  );
  await waitForApplication(page, deadline);
}

/** 从工作过程读取已经持久化的 Commentary/Reasoning，避免把正在输出的最终正文误计入过程。 */
function publicNarrative(process, marker) {
  return process
    .locator(".ja-work-step--commentary, .ja-work-step--reasoning")
    .filter({ hasText: marker });
}

/** 读取当前工作过程的公开 DOM projection，只返回叙事/tool kind 和安全文本摘要。 */
async function processItems(page, deadline) {
  const process = page.locator("section.ja-work-process").last();
  await process.waitFor({ state: "visible", timeout: timeout(deadline) });
  return process.locator(".ja-work-process__steps > li").evaluateAll((items) =>
    items.map((item) => {
      if (
        item.classList.contains("ja-work-step--commentary") ||
        item.classList.contains("ja-work-step--reasoning")
      ) {
        return { kind: "commentary", text: item.textContent?.trim() ?? "" };
      }
      const tool = item.querySelector(".ja-tool-details");
      return {
        kind: "tool",
        toolKind: tool?.getAttribute("data-tool-kind") ?? "unknown",
        toolLabel:
          tool?.querySelector(".ja-tool-details__trigger")?.getAttribute("aria-label") ?? "",
        text: item.textContent?.trim() ?? "",
      };
    }),
  );
}

/** 用按钮的可访问展开状态判断折叠，CSS 高度裁剪不保证设置 hidden 属性。 */
async function expandProcess(page, deadline) {
  const process = page.locator("section.ja-work-process").last();
  await process.waitFor({ state: "visible", timeout: timeout(deadline) });
  const trigger = process.locator(".ja-work-process__trigger");
  if ((await trigger.getAttribute("aria-expanded")) !== "true") {
    await trigger.click({ timeout: timeout(deadline) });
  }
  await process
    .locator(".ja-work-process__steps")
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  return process;
}

/**
 * 展开真实 read 步骤并确认 Java metadata 驱动的摘要与实底正文同时出现；这覆盖新结果视图的真实
 * Tauri/WebView2 渲染，不以手写 DOM 或静态截图替代 Tool 执行链路。
 */
async function expandReadResult(process, deadline) {
  const read = process.locator('.ja-tool-details[data-tool-kind="read"]');
  await read.waitFor({ state: "visible", timeout: timeout(deadline) });
  const trigger = read.locator(".ja-tool-details__trigger");
  if ((await trigger.getAttribute("aria-expanded")) !== "true") {
    await trigger.click({ timeout: timeout(deadline) });
  }
  const summary = read.locator(".ja-tool-details__overview");
  await summary.waitFor({ state: "visible", timeout: timeout(deadline) });
  const text = (await summary.textContent())?.trim() ?? "";
  assert.match(text, /^已读取 \d+ 行，共 \d+ 行/u, "read summary must use authoritative metadata");
  await read
    .locator(".ja-tool-details__output")
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  return text;
}

/**
 * 断言已结算的过程仍按 Tool 交错，且最后一轮正文不回流到过程。Tool 的终态不作为视觉摘要，
 * 因此只验证真实 Tool identity 与可访问名称，不把“完成”等旧状态文案重新固定进界面契约。
 *
 * 末尾 reasoning summary 在 terminal 后继续可审计，但最终 output_text 只属于 AssistantResponse；
 * live 与 reload 都必须保留相同的公开过程类型顺序。
 */
function assertProcessSequence(items, label) {
  assert.deepEqual(
    items.map((item) => item.kind),
    ["commentary", "tool", "commentary", "commentary", "tool", "commentary"],
    `${label} must interleave commentary and tools`,
  );
  assert.deepEqual(
    items.filter((item) => item.kind === "tool").map((item) => item.toolKind),
    ["read", "shell"],
    `${label} must contain exactly one read and one shell`,
  );
  assert.equal(
    new Set(items.filter((item) => item.kind === "tool").map((item) => item.toolKind)).size,
    2,
  );
  assert.equal(
    items
      .filter((item) => item.kind === "tool")
      .every((item) => item.toolLabel.trim() !== "" && item.toolLabel.includes(item.toolKind)),
    true,
    `${label} Tool controls must retain an accessible native identity`,
  );
}

/**
 * 终态 reasoning summary 由实时 Draft 保留到历史 read 接管；最终 output_text 无论何时都不能进入过程。
 */
function assertFinalBodyOutsideProcess(items, label) {
  assert.equal(
    items.some((item) => item.text.includes(conversationProgressFixtureMarkers.final)),
    false,
    `${label} WorkProcess must not contain the final response body`,
  );
}

/**
 * 在真实结算和重载后验收上下文浮层：账本数字必须来自 JVM 持久化回读，布局则以 WebView2
 * 的实际碰撞结果为准。这里同时覆盖 hover 阅读、点击固定、外部/Escape 关闭和焦点回归，避免
 * 组件测试中的 JSDOM Portal 行为替代桌面交互事实。
 */
async function verifyContextUsagePopover(page, evidenceDirectory, deadline) {
  const trigger = page.getByRole("button", { name: "上下文用量详情", exact: true });
  const popover = page.locator(".ja-context-usage-popover");
  await trigger.waitFor({ state: "visible", timeout: timeout(deadline) });
  /**
   * UI 只把读取失败投影为“暂不可用”；真窗验收额外读取同一 typed command 的脱敏结果，
   * 让 IPC 拒绝可定位而不把 Thread 正文、配置或 Provider 请求记录到证据文件。
   */
  const nativeUsage = await page.evaluate(async () => {
    try {
      const active = globalThis.document.querySelector(
        '[aria-label="最近对话列表"] button[aria-current="page"]',
      );
      const threadId = active?.getAttribute("data-thread-id");
      if (threadId === null || threadId === undefined) return { status: "missing_thread" };
      const value = await globalThis.__TAURI_INTERNALS__?.invoke("ja_thread_usage_read", {
        input: { threadId },
      });
      const record = value !== null && typeof value === "object" ? value : {};
      return {
        status: "ok",
        requestCount: record.requestCount,
        totalTokens: record.totalTokens,
        outputTokens: record.outputTokens,
      };
    } catch (error) {
      const record = error !== null && typeof error === "object" ? error : {};
      return {
        status: "error",
        code: typeof record.code === "string" ? record.code.slice(0, 96) : undefined,
        message: typeof record.message === "string" ? record.message.slice(0, 256) : undefined,
        detail: String(error ?? "unknown").slice(0, 256),
      };
    }
  });
  assert.deepEqual(nativeUsage, {
    status: "ok",
    requestCount: 3,
    totalTokens: 96,
    outputTokens: 36,
  });
  await trigger.hover({ timeout: timeout(deadline) });
  await popover.waitFor({ state: "visible", timeout: timeout(deadline) });
  await popover.getByText("Token · 本会话", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });

  const initialFacts = await popover.evaluate((element) => {
    const valueFor = (label) => {
      const row = [...element.querySelectorAll(".ja-context-usage-popover__row")].find(
        (candidate) => candidate.firstElementChild?.textContent?.trim() === label,
      );
      return row?.lastElementChild?.textContent?.trim() ?? null;
    };
    const bounds = element.getBoundingClientRect();
    return {
      classNames: element.className,
      userSelect: globalThis.getComputedStyle(element).userSelect,
      width: Math.round(bounds.width),
      left: Math.round(bounds.left),
      right: Math.round(bounds.right),
      viewportWidth: globalThis.innerWidth,
      documentOverflows: globalThis.document.documentElement.scrollWidth > globalThis.innerWidth,
      metrics: {
        newInput: valueFor("输入（未缓存）"),
        output: valueFor("输出"),
        cacheRead: valueFor("缓存读取"),
        total: valueFor("总计"),
        cacheRate: valueFor("缓存命中率"),
        context: valueFor("上下文"),
        used: valueFor("已用"),
      },
    };
  });
  assert.equal(initialFacts.classNames.includes("ja-floating-surface"), true);
  assert.equal(initialFacts.userSelect, "text");
  // WebView2 在非整数 DPI 下会把 280px CSS 宽度投影为 279px 的布局边界；限定紧凑范围，
  // 既锁住约 280px 的信息密度，也不把平台子像素取整误判为响应式回归。
  assert.equal(
    initialFacts.width >= 278 && initialFacts.width <= 280,
    true,
    "context usage popover must retain its compact width",
  );
  assert.equal(
    initialFacts.left >= 12 && initialFacts.right <= initialFacts.viewportWidth - 12,
    true,
  );
  assert.equal(initialFacts.documentOverflows, false);
  assert.deepEqual(initialFacts.metrics, {
    newInput: "60",
    output: "36",
    cacheRead: "0",
    total: "96",
    // cacheCompleteRequestCount/inputTokens 已覆盖完整样本；读取量为零是已知 0.0%，不是未知横线。
    cacheRate: "0.0%",
    context: "0.0%",
    used: "20 / 128k",
  });
  await page.screenshot({
    path: join(evidenceDirectory, "context-usage-light-wide.png"),
    animations: "disabled",
  });

  // Hover 展开后点击只固定阅读，不会因为入口和浮层之间的微小间隙关闭。
  await trigger.click({ timeout: timeout(deadline) });
  await popover.hover({ timeout: timeout(deadline) });
  // 浮层可能与 Composer 输入框在垂直方向重叠；选择已可见的用户消息作为真实外部命中点，
  // 避免测试为了关闭浮层去点击被浮层正确遮挡的元素。
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .last()
    .click({ timeout: timeout(deadline) });
  await popover.waitFor({ state: "hidden", timeout: timeout(deadline) });
  await trigger.click({ timeout: timeout(deadline) });
  await popover.waitFor({ state: "visible", timeout: timeout(deadline) });
  await page.keyboard.press("Escape");
  await popover.waitFor({ state: "hidden", timeout: timeout(deadline) });
  assert.equal(
    await trigger.evaluate((element) => globalThis.document.activeElement === element),
    true,
    "Escape must restore focus to the usage trigger",
  );

  await trigger.click({ timeout: timeout(deadline) });
  await popover.waitFor({ state: "visible", timeout: timeout(deadline) });
  await page.setViewportSize({ width: 360, height: 640 });
  await page.waitForFunction(() => globalThis.innerWidth <= 360, undefined, {
    timeout: timeout(deadline),
  });
  const narrowFacts = await popover.evaluate((element) => {
    const bounds = element.getBoundingClientRect();
    return {
      left: Math.round(bounds.left),
      right: Math.round(bounds.right),
      viewportWidth: globalThis.innerWidth,
      documentOverflows: globalThis.document.documentElement.scrollWidth > globalThis.innerWidth,
    };
  });
  assert.equal(narrowFacts.left >= 12 && narrowFacts.right <= narrowFacts.viewportWidth - 12, true);
  assert.equal(narrowFacts.documentOverflows, false);
  await page.screenshot({
    path: join(evidenceDirectory, "context-usage-light-narrow.png"),
    animations: "disabled",
  });
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await page.screenshot({
    path: join(evidenceDirectory, "context-usage-dark-narrow.png"),
    animations: "disabled",
  });
  return { ...initialFacts, narrow: narrowFacts };
}

/**
 * 在同一真实 Thread 中验证失败终态的唯一恢复入口：失败只显示可访问的继续图标，点击后创建新的
 * Turn，旧 Tool 不回放；新 Turn 保留正常入场动画，terminal 后再观察 5 秒确认工作指示与对账
 * 都停止。该路径复用 loopback fixture，不把失败或继续行为伪造成静态 DOM。
 */
async function verifyFailureContinuation(page, fixture, evidenceDirectory, deadline) {
  await page.keyboard.press("Escape").catch(() => undefined);
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "no-preference" });
  const input = page.getByRole("textbox", { name: "消息", exact: true });
  await input.fill("触发失败后继续验收");
  await page
    .getByRole("button", { name: "发送", exact: true })
    .click({ timeout: timeout(deadline) });
  await waitForCondition(
    "controlled failed terminal",
    () => fixture.stages.includes("failure_terminal"),
    deadline,
  );
  const failedResponse = page
    .locator('.ja-chat-message-final[data-response-state="failed"]')
    .last();
  await failedResponse.waitFor({ state: "visible", timeout: timeout(deadline) });
  const continueButton = page.getByRole("button", { name: "继续回复", exact: true });
  await continueButton.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(
    await page.locator(".ja-navigation-thread-state.is-running").count(),
    0,
    "a failed terminal must stop the running history indicator",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "conversation-progress-failure.png"),
    animations: "allow",
  });

  // reload 后旧 document 的 DOM listener 已失效；重新安装真实 probe，再记录继续前基线，
  // 这样新 Turn 的 animationstart 只能来自当前 document，缺失探针会立即暴露。
  await installConversationFlickerProbe(page);
  await installConversationAnimationReplayProbe(page);
  const readCountBeforeContinue = (await readThreadReadEvidence(page)).starts;
  const animationReplaysBeforeContinue = await readResponseAnimationReplayCount(page);
  await continueButton.click({ timeout: timeout(deadline) });
  await waitForCondition(
    "continuation summary stream",
    () => fixture.stages.includes("summary_continue"),
    deadline,
  );
  fixture.releaseContinuationNarrative();
  const continuationProcess = page.locator("section.ja-work-process").last();
  await publicNarrative(
    continuationProcess,
    conversationProgressFixtureMarkers.continueCommentary,
  ).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const continuationResponse = page.locator('.ja-chat-message-final[data-role="response"]').last();
  await continuationResponse.getByText("正在工作", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const continuationAnimationStart = await page.evaluate(() => {
    const target = globalThis.document.querySelector(".ja-chat-message-draft");
    const animation = target
      ?.getAnimations()
      .find((candidate) => candidate.animationName === "ja-chat-response-enter");
    return typeof animation?.startTime === "number" ? animation.startTime : null;
  });
  assert.equal(
    typeof continuationAnimationStart,
    "number",
    "a normal continued Turn must retain its entry animation",
  );
  await waitForCondition(
    "continuation text stream",
    () => fixture.stages.includes("text_continue"),
    deadline,
  );
  assert.equal(
    await continuationResponse
      .getByText(conversationProgressFixtureMarkers.continueFinal, {
        exact: false,
      })
      .count(),
    0,
    "continued text must remain in WorkProcess before terminal",
  );
  fixture.releaseContinuationText();
  const completedContinuation = page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .last();
  await completedContinuation
    .getByText(conversationProgressFixtureMarkers.continueFinal, { exact: false })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await expandProcess(page, deadline);
  const continuationItems = await processItems(page, deadline);
  assert.equal(
    continuationItems.some((item) => item.kind === "tool"),
    false,
    "a continued Turn must not replay the failed Turn's Tool rows",
  );
  assert.equal(
    continuationItems.some(
      (item) =>
        item.text.includes(conversationProgressFixtureMarkers.read) ||
        item.text.includes(conversationProgressFixtureMarkers.shell),
    ),
    false,
    "a continued Turn must not replay old Tool output text",
  );
  const animationReplaysAfterContinue = await readResponseAnimationReplayCount(page);
  assert.equal(
    animationReplaysAfterContinue > animationReplaysBeforeContinue,
    true,
    "a normal continued Turn may start a new response animation",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "conversation-progress-continued.png"),
    animations: "allow",
  });

  const terminalReadCountBeforeQuiet = (await readThreadReadEvidence(page)).starts;
  const quietStart = await page.evaluate(() => performance.now());
  await waitForCondition(
    "continued terminal quiet window",
    () => page.evaluate((startedAt) => performance.now() - startedAt >= 5_000, quietStart),
    deadline,
  );
  const terminalReadCountAfterQuiet = (await readThreadReadEvidence(page)).starts;
  assert.equal(
    terminalReadCountAfterQuiet,
    terminalReadCountBeforeQuiet,
    "continued terminal must stop reconciliation after five seconds",
  );
  assert.equal(
    await page
      .locator('.ja-chat-message-final[data-response-state="completed"] .ja-chat-response__status')
      .count(),
    0,
    "continued terminal must stop the response work indicator",
  );
  assert.equal(
    await page.locator(".ja-navigation-thread-state.is-running").count(),
    0,
    "continued terminal must stop the running history indicator",
  );
  return {
    failedTerminalVisible: true,
    continueActionVisible: true,
    newTurnCompleted: true,
    oldToolsReplayed: false,
    continuationAnimationStart,
    animationReplaysBeforeContinue,
    animationReplaysAfterContinue,
    terminalReadCountBeforeQuiet,
    terminalReadCountAfterQuiet,
    workIndicatorStopped: true,
    screenshots: ["conversation-progress-failure.png", "conversation-progress-continued.png"],
    readCountBeforeContinue,
  };
}

/** 对外报告必须同时证明流式正文留在工作过程、持续生命信号、terminal 收口与历史顺序。 */
export function validateConversationProgressReport(report) {
  assert.equal(report?.schemaVersion, 1);
  assert.equal(report?.status, "passed");
  assert.equal(report?.runtime?.platform, "win32");
  assert.equal(report?.runtime?.surface, "tauri_webview2");
  assert.equal(report?.runtime?.boundary, "jvm_jar");
  assert.equal(report?.runtime?.nativeImageVerified, false);
  assert.equal(report?.runtimeInvocationProbe?.boundary, "e2e_nativeInvoke_delegate");
  assert.equal(report?.runtimeInvocationProbe?.probeInstalled, true);
  assert.equal(report?.runtimeInvocationProbe?.observedCommand, "ja_runtime_state");
  assert.equal(report?.runtimeInvocationProbe?.startObserved, true);
  assert.equal(report?.runtimeInvocationProbe?.resolvedObserved, true);
  assert.equal(
    report?.runtimeInvocationProbe?.runtimeStatus === "ready" ||
      report?.runtimeInvocationProbe?.runtimeStatus === "busy",
    true,
  );
  assert.equal(report?.runtimeInvocationProbe?.publicTimelineStoreSingleton, true);
  assert.equal(report?.provider?.kind, "deterministic_loopback");
  assert.equal(report?.provider?.externalCalls, 0);
  assert.equal(report?.provider?.toolCalls, 2);
  assert.match(report?.identity?.git?.head, /^[0-9a-f]{40}$/u);
  assert.equal(typeof report?.identity?.git?.dirty, "boolean");
  assert.equal(report?.identity?.git?.dirtyEntryCount >= 0, true);
  for (const source of Object.values(report?.identity?.sources ?? {})) {
    assert.match(source?.sha256, /^[0-9a-f]{64}$/u);
  }
  assert.equal(
    report?.identity?.sources?.activeStreamRegistry?.path,
    ACTIVE_STREAM_REGISTRY_SOURCE_PATH,
  );
  for (const name of [
    "chatTimeline",
    "workProcess",
    "mybatisHistoryService",
    "agentLoopPersistence",
    "turnEventSink",
    "threadSnapshot",
  ]) {
    assert.equal(report?.identity?.sources?.[name]?.path, SOURCE_IDENTITY_FILES[name]);
  }
  assert.equal(
    report?.identity?.jar?.status === "available" ||
      /^[0-9a-f]{64}$/u.test(report?.identity?.jar?.sha256 ?? ""),
    true,
  );
  assert.equal(
    report?.identity?.jaExecutable?.status === "available" ||
      report?.identity?.jaExecutable?.status === "unavailable",
    true,
  );
  assert.equal(typeof report?.identity?.window?.title, "string");
  assert.equal(typeof report?.identity?.window?.url, "string");
  assert.equal(report?.identity?.window?.viewport?.width > 0, true);
  assert.equal(report?.identity?.window?.viewport?.height > 0, true);
  assert.equal(Array.isArray(report?.identity?.consoleErrors), true);
  assert.equal(Array.isArray(report?.identity?.pageErrors), true);
  assert.equal(report?.live?.progressInsideProcessBeforeFirstTool, true);
  assert.equal(report?.live?.workingStatusWithProcess, true);
  assert.equal(report?.live?.finalDraftInsideProcessBeforeTerminal, true);
  assert.equal(report?.live?.finalBodyOutsideProcess, true);
  assert.equal(report?.live?.processNodeStable, true);
  assert.equal(report?.live?.responseNodeStable, true);
  assert.equal(report?.live?.historyRunningStatusNodeStable, true);
  assert.deepEqual(report?.live?.animationReplays, { response: 0, history: 0 });
  assert.equal(report?.live?.historyLoadingIndicatorMounts, 0);
  assert.equal(report?.live?.terminalCalibratedExistingResponse, true);
  assert.equal(report?.live?.completedProcessCollapsed, true);
  assert.equal(report?.live?.readSummaryVisible, true);
  assert.equal(report?.live?.flicker?.resyncReadCount >= 1, true);
  assert.equal(report?.live?.flicker?.threadReadCount >= 1, true);
  assert.equal(report?.live?.flicker?.liveStreamBaseline?.streamSeq > 0, true);
  assert.equal(report?.live?.flicker?.liveStreamBaseline?.segmentCount > 0, true);
  assert.equal(report?.live?.flicker?.terminalReadCountAfterQuiet >= 1, true);
  assert.equal(
    report?.live?.flicker?.terminalReadCountAfterQuiet,
    report?.live?.flicker?.terminalReadCountBeforeQuiet,
  );
  assert.equal(report?.live?.flicker?.responseTextRegressions, 0);
  assert.equal(report?.live?.flicker?.progressTextRegressions, 0);
  assert.equal(report?.live?.flicker?.backgroundReadWindow?.started, true);
  assert.equal(report?.live?.flicker?.backgroundReadWindow?.ended, true);
  assert.equal(report?.live?.flicker?.backgroundReadWindow?.newConversationDisabledTransitions, 0);
  assert.equal(report?.live?.flicker?.backgroundReadWindow?.composerDisabledTransitions, 0);
  assert.equal(
    report?.live?.flicker?.spinnerStartTimes?.history?.every(
      (startTime) => typeof startTime === "number",
    ),
    true,
  );
  assert.equal(
    report?.live?.flicker?.spinnerStartTimes?.response?.every(
      (startTime) => typeof startTime === "number",
    ),
    true,
  );
  assert.deepEqual(report?.live?.sequence, [
    "commentary",
    "tool:read",
    "commentary",
    "commentary",
    "tool:shell",
    "commentary",
  ]);
  assert.equal(report?.live?.noDuplicateTools, true);
  assert.equal(report?.reload?.finalBodyOutsideProcess, true);
  assert.deepEqual(report?.reload?.sequence, [
    "commentary",
    "tool:read",
    "commentary",
    "commentary",
    "tool:shell",
    "commentary",
  ]);
  assert.equal(report?.reload?.readSummaryVisible, true);
  assert.equal(report?.reload?.sameThread, true);
  assert.equal(report?.finalVisible, true);
  assert.equal(
    report?.contextUsage?.width >= 278 && report?.contextUsage?.width <= 280,
    true,
    "context usage report must retain compact width across WebView2 DPI rounding",
  );
  assert.equal(report?.contextUsage?.userSelect, "text");
  assert.deepEqual(report?.contextUsage?.metrics, {
    newInput: "60",
    output: "36",
    cacheRead: "0",
    total: "96",
    cacheRate: "0.0%",
    context: "0.0%",
    used: "20 / 128k",
  });
  assert.equal(report?.contextUsage?.narrow?.documentOverflows, false);
  assert.equal(report?.failureContinuation?.failedTerminalVisible, true);
  assert.equal(report?.failureContinuation?.continueActionVisible, true);
  assert.equal(report?.failureContinuation?.newTurnCompleted, true);
  assert.equal(report?.failureContinuation?.oldToolsReplayed, false);
  assert.equal(typeof report?.failureContinuation?.continuationAnimationStart, "number");
  assert.equal(
    report?.failureContinuation?.animationReplaysAfterContinue >
      report?.failureContinuation?.animationReplaysBeforeContinue,
    true,
  );
  assert.equal(report?.failureContinuation?.terminalReadCountAfterQuiet >= 1, true);
  assert.equal(
    report?.failureContinuation?.terminalReadCountAfterQuiet,
    report?.failureContinuation?.terminalReadCountBeforeQuiet,
  );
  assert.equal(report?.failureContinuation?.workIndicatorStopped, true);
  return report;
}

/**
 * 在真实窗口提交 Turn；当前 text delta 始终留在 WorkProcess，terminal 到达后才校准为最终答复，
 * 同时观测回复壳、侧栏进行中状态与历史加载圈，避免高频事件重挂载后重播入场动画。
 *
 * 受控 fixture gate 让每个结构化边界都可单独观察，不依赖任意等待时间或最后一个 Tool 的位置猜测。
 */
export async function runConversationProgressWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  fixture,
  jarPath,
  cargoTargetDirectory,
}) {
  assert.ok(page, "page is required");
  assert.ok(workspaceRoot, "workspaceRoot is required");
  assert.ok(fixture, "fixture is required");
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + 5 * 60_000;
  const pageErrors = [];
  const consoleErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  // React Error Boundary 会把渲染异常投影为界面而非 pageerror；保留受限 console 摘要才能让
  // 隔离真窗失败指向实际组件根因，而不是笼统归为 locator 超时。
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text().slice(0, 1_000));
  });
  await instrumentRuntimeInvocations(page);
  // runner 已连接到新启动的真实窗口；这里额外 reload 会与旧 renderer 的 stop cleanup 竞争。
  // 历史恢复阶段仍执行一次真实 reload，因而不会削弱 reload + persisted history 的验收边界。
  await waitForApplication(page, deadline);
  const created = await configureFixtureAndCreateThread(page, workspaceRoot, fixture.baseUrl);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await instrumentRuntimeInvocations(page);
  await restoreRuntimeAfterReload(page, deadline);
  let runtimeInvocationProbe = await assertRuntimeInvocationProbe(page);
  let publicTimelineStoreSingleton = false;
  await selectProject(page, created.workspaceName, deadline);
  await selectThread(page, created.threadId, deadline);
  const historyRow = page.locator(
    `[aria-label="最近对话列表"] button[data-thread-id="${created.threadId}"]`,
  );
  await historyRow.waitFor({ state: "visible", timeout: timeout(deadline) });
  assert.equal(
    await page.locator(".ja-navigation-history-loading").count(),
    0,
    "an existing history projection must not expose the loading indicator before streaming",
  );
  await installConversationFlickerProbe(page);
  await page.evaluate(() => {
    const historySection = globalThis.document.querySelector(".ja-navigation-history");
    if (historySection === null) throw new Error("history section is unavailable");
    const evidence = globalThis.__JA_CONVERSATION_FLICKER_PROBE__?.evidence;
    if (evidence === undefined) throw new Error("flicker probe is unavailable");
    evidence.historyLoadingIndicatorMounts = 0;
    const observer = new globalThis.MutationObserver((records) => {
      for (const record of records) {
        for (const node of record.addedNodes) {
          if (!(node instanceof globalThis.Element)) continue;
          if (
            node.matches(".ja-navigation-history-loading") ||
            node.querySelector(".ja-navigation-history-loading") !== null
          ) {
            evidence.historyLoadingIndicatorMounts += 1;
          }
        }
      }
    });
    observer.observe(historySection, { childList: true, subtree: true });
    globalThis.__JA_CONVERSATION_FLICKER_EVIDENCE__ = evidence;
    globalThis.__JA_CONVERSATION_HISTORY_OBSERVER__ = observer;
  });
  const input = page.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(PROMPT);
  await page
    .getByRole("button", { name: "发送", exact: true })
    .click({ timeout: timeout(deadline) });
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: PROMPT })
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });

  const responseShell = page.locator('.ja-chat-message-final[data-role="response"]').last();
  await responseShell.getByText("正在工作", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const workProcess = page.locator("section.ja-work-process").last();
  await publicNarrative(workProcess, conversationProgressFixtureMarkers.commentary1).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  // 首个真实 delta 已归属权威 Turn；响应壳与工作过程分别记录 terminal 和过程节点稳定性。
  const responseShellHandle = await responseShell.elementHandle();
  assert.ok(responseShellHandle, "the authoritative response shell must have a DOM node");
  const streamingProcessHandle = await workProcess.elementHandle();
  assert.ok(streamingProcessHandle, "the streaming WorkProcess must have a DOM node");
  const runningHistoryState = historyRow.locator(".ja-navigation-thread-state.is-running");
  await runningHistoryState.waitFor({ state: "visible", timeout: timeout(deadline) });
  const runningHistoryStateHandle = await runningHistoryState.elementHandle();
  assert.ok(runningHistoryStateHandle, "the running history state must have a DOM node");
  const initialSpinnerStartTimes = await page.evaluate(() => {
    const probe = globalThis.__JA_CONVERSATION_FLICKER_PROBE__;
    if (probe === undefined) throw new Error("flicker probe is unavailable");
    return probe.spinnerStartTimes();
  });
  assert.equal(
    typeof initialSpinnerStartTimes.history,
    "number",
    "history spinner must expose a real Web Animations startTime",
  );
  assert.equal(
    typeof initialSpinnerStartTimes.response,
    "number",
    "response draft must expose a real Web Animations startTime",
  );
  await installConversationAnimationReplayProbe(page);
  assert.equal(
    await responseShell
      .getByText(conversationProgressFixtureMarkers.commentary1, { exact: false })
      .count(),
    0,
    "text before the first Tool must remain inside WorkProcess",
  );
  assert.equal(
    await page.locator(".ja-tool-details").count(),
    0,
    "public output text must render before the first Tool row",
  );
  assert.equal(
    fixture.stages.includes("text_read"),
    true,
    "fixture did not stream first public text",
  );
  assert.equal(
    fixture.stages.includes("tool_read"),
    false,
    "fixture sent read Tool before text checkpoint",
  );

  // 受控 fault injection 只建立一次真实 controller resync 意图；之后的读取、Live baseline 合并、
  // React 状态更新和 Provider 流继续都走生产链路。driver 在读取 ACK 前保持 5 秒静默，覆盖
  // 原先由后台 busy 反复禁用新会话/Composer 的窗口，并分别采样鼠标在侧栏内外的布局状态。
  const readBeforeResync = await readThreadReadEvidence(page);
  let liveStreamBaseline;
  let backgroundReadReleased = false;
  try {
    await page.evaluate(() => {
      const probe = globalThis.__JA_CONVERSATION_FLICKER_PROBE__;
      if (probe === undefined) throw new Error("flicker probe is unavailable");
      probe.beginBackgroundResync();
      globalThis.__JA_CONVERSATION_THREAD_READ_HOLD__ = true;
      globalThis.__JA_CONVERSATION_QUIET_WINDOW_START__ = performance.now();
    });
    const resyncRequest = await requestTimelineResync(page, created.threadId);
    publicTimelineStoreSingleton = resyncRequest.publicStoreSingleton;
    assert.equal(
      resyncRequest.publicStoreSingleton,
      true,
      "resync driver must use the public Timeline store singleton consumed by the controller",
    );
    await waitForCondition(
      "background thread/read start",
      async () => (await readThreadReadEvidence(page)).starts > readBeforeResync.starts,
      deadline,
    );
    const readEvidenceAfterStart = await readThreadReadEvidence(page);
    const backgroundReadInvocationId = readEvidenceAfterStart.startIds.at(-1);
    assert.ok(
      Number.isSafeInteger(backgroundReadInvocationId),
      "background thread/read start must expose an invocation identity",
    );
    await waitForCondition(
      "background thread/read received",
      async () => {
        const evidence = await readThreadReadEvidence(page);
        return evidence.received.some(
          (record) => record.invocationId === backgroundReadInvocationId,
        );
      },
      deadline,
    );
    const readEvidenceDuringResync = await readThreadReadEvidence(page);
    // received 一到达就校验；若 Java 返回 null/残缺基线，assert 立即抛错，finally 会释放
    // 当前 hold，诊断可保留真实 null，而不会再用同一响应等待 5 分钟掩盖合同缺陷。
    liveStreamBaseline = assertLiveStreamEvidence(
      readEvidenceDuringResync,
      created.threadId,
      backgroundReadInvocationId,
    );
    await page.evaluate(() => {
      const probe = globalThis.__JA_CONVERSATION_FLICKER_PROBE__;
      if (probe === undefined) throw new Error("flicker probe is unavailable");
      probe.sample("background-read-received");
      probe.spinnerStartTimes();
    });
    const sidebar = page.locator(".ja-navigation-sidebar");
    const sidebarBounds = await sidebar.boundingBox();
    assert.ok(sidebarBounds, "navigation sidebar must be measurable in the real WebView2 window");
    await historyRow.hover({ timeout: timeout(deadline) });
    const sidebarHoverState = await page.evaluate(() => {
      const probe = globalThis.__JA_CONVERSATION_FLICKER_PROBE__;
      return probe?.sample?.("sidebar-hover");
    });
    await page.mouse.move(sidebarBounds.x + sidebarBounds.width + 40, sidebarBounds.y + 40);
    const outsideSidebarState = await page.evaluate(() => {
      const probe = globalThis.__JA_CONVERSATION_FLICKER_PROBE__;
      return probe?.sample?.("sidebar-outside");
    });
    assert.equal(sidebarHoverState?.historyLoadingVisible, false);
    assert.equal(outsideSidebarState?.historyLoadingVisible, false);
    await waitForCondition(
      "five second background quiet window",
      () =>
        page.evaluate(
          () =>
            performance.now() -
              Number(globalThis.__JA_CONVERSATION_QUIET_WINDOW_START__ ?? performance.now()) >=
            5_000,
        ),
      deadline,
    );
    const quietState = await page.evaluate(() => {
      const probe = globalThis.__JA_CONVERSATION_FLICKER_PROBE__;
      return probe?.sample?.("background-quiet");
    });
    assert.equal(quietState?.newConversationDisabled, false);
    assert.equal(quietState?.composerDisabled, false);
    await page.evaluate(() => {
      globalThis.__JA_CONVERSATION_THREAD_READ_HOLD__ = false;
      globalThis.__JA_CONVERSATION_THREAD_READ_RELEASE__?.();
    });
    backgroundReadReleased = true;
    await waitForCondition(
      "background thread/read resolve",
      async () => {
        const evidence = await readThreadReadEvidence(page);
        return evidence.resolved > readBeforeResync.resolved;
      },
      deadline,
    );
    await page.evaluate(() => {
      const probe = globalThis.__JA_CONVERSATION_FLICKER_PROBE__;
      if (probe === undefined) throw new Error("flicker probe is unavailable");
      probe.endBackgroundResync();
    });
  } finally {
    if (!backgroundReadReleased)
      await page
        .evaluate(() => {
          globalThis.__JA_CONVERSATION_THREAD_READ_HOLD__ = false;
          globalThis.__JA_CONVERSATION_THREAD_READ_RELEASE__?.();
        })
        .catch(() => undefined);
  }
  fixture.releaseFirstText();

  await workProcess.locator('.ja-tool-details[data-tool-kind="read"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await publicNarrative(workProcess, conversationProgressFixtureMarkers.commentary1).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await responseShell.getByText("正在工作", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  const responseWithProcessHandle = await responseShell.elementHandle();
  assert.ok(responseWithProcessHandle, "the response below WorkProcess must have a DOM node");
  assert.equal(
    await responseShellHandle.evaluate(
      (streamingNode, processNode) => streamingNode === processNode,
      responseWithProcessHandle,
    ),
    true,
    "Tool settlement must retain the authoritative response node",
  );
  const processWithToolHandle = await workProcess.elementHandle();
  assert.ok(processWithToolHandle, "the WorkProcess with Tool must have a DOM node");
  assert.equal(
    await streamingProcessHandle.evaluate(
      (streamingNode, processNode) => streamingNode === processNode,
      processWithToolHandle,
    ),
    true,
    "Tool arrival must retain the WorkProcess node",
  );
  await publicNarrative(workProcess, conversationProgressFixtureMarkers.commentary2).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  fixture.releaseSecondNarrative();
  await workProcess
    .locator('.ja-tool-details[data-tool-kind="shell"]')
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  await publicNarrative(workProcess, conversationProgressFixtureMarkers.commentary3).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  fixture.releaseFinalNarrative();
  await publicNarrative(workProcess, conversationProgressFixtureMarkers.final).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await responseShell.getByText("正在工作", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  assert.equal(
    await responseShell
      .getByText(conversationProgressFixtureMarkers.final, { exact: false })
      .count(),
    0,
    "the final draft must remain inside WorkProcess before terminal",
  );
  const runningHistoryStateBeforeTerminalHandle = await runningHistoryState.elementHandle();
  assert.ok(
    runningHistoryStateBeforeTerminalHandle,
    "the running history state must remain mounted before terminal",
  );
  const historyRunningStatusNodeStable = await runningHistoryStateHandle.evaluate(
    (initialNode, currentNode) => initialNode === currentNode,
    runningHistoryStateBeforeTerminalHandle,
  );
  assert.equal(
    historyRunningStatusNodeStable,
    true,
    "streaming updates must retain the running history status node",
  );
  const animationReplays = await page.evaluate(
    () => globalThis.__JA_CONVERSATION_FLICKER_EVIDENCE__?.animationReplays,
  );
  assert.deepEqual(
    animationReplays,
    { response: 0, history: 0 },
    "streaming updates must not replay response or history entry animations",
  );
  fixture.releaseFinalText();
  await waitForCondition(
    "completed turn",
    () =>
      page
        .locator('.ja-chat-message-final[data-response-state="completed"]')
        .count()
        .then((count) => count > 0),
    deadline,
  );
  const completedAnswer = page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .last();
  await completedAnswer
    .getByText(conversationProgressFixtureMarkers.final, { exact: false })
    .waitFor({ state: "visible", timeout: timeout(deadline) });
  const completedAnswerHandle = await completedAnswer.elementHandle();
  assert.ok(completedAnswerHandle, "terminal must expose a completed final-answer node");
  const responseNodeStable = await responseShellHandle.evaluate(
    (streamingNode, completedNode) => streamingNode === completedNode,
    completedAnswerHandle,
  );
  assert.equal(responseNodeStable, true, "terminal must calibrate the existing response node");
  const completedProcessHandle = await workProcess.elementHandle();
  assert.ok(completedProcessHandle, "terminal must retain the WorkProcess node");
  const processNodeStable = await streamingProcessHandle.evaluate(
    (streamingNode, completedNode) => streamingNode === completedNode,
    completedProcessHandle,
  );
  assert.equal(processNodeStable, true, "terminal must not replace the WorkProcess container");
  await waitForCondition(
    "completed WorkProcess collapse",
    () =>
      workProcess
        .locator(".ja-work-process__trigger")
        .getAttribute("aria-expanded")
        .then((expanded) => expanded === "false"),
    deadline,
  );
  await expandProcess(page, deadline);
  const liveReadSummary = await expandReadResult(workProcess, deadline);
  const liveItems = await processItems(page, deadline);
  assertProcessSequence(liveItems, "live");
  assertFinalBodyOutsideProcess(liveItems, "live");
  const liveSequence = liveItems.map((item) =>
    item.kind === "tool" ? `tool:${item.toolKind}` : item.kind,
  );
  await page.screenshot({
    path: join(evidenceDirectory, "conversation-progress-live.png"),
    animations: "allow",
  });
  const historyLoadingIndicatorMounts = await page.evaluate(() => {
    globalThis.__JA_CONVERSATION_HISTORY_OBSERVER__?.disconnect();
    return globalThis.__JA_CONVERSATION_FLICKER_EVIDENCE__?.historyLoadingIndicatorMounts;
  });
  assert.equal(
    historyLoadingIndicatorMounts,
    0,
    "streaming and terminal settlement must not mount the history loading indicator",
  );
  const terminalObservationStart = await page.evaluate(() => performance.now());
  await waitForCondition(
    "terminal reconciliation settle window",
    () =>
      page.evaluate(
        (startedAt) => performance.now() - startedAt >= 1_200,
        terminalObservationStart,
      ),
    deadline,
  );
  const terminalReadCountBeforeQuiet = (await readThreadReadEvidence(page)).starts;
  const terminalQuietStart = await page.evaluate(() => performance.now());
  await waitForCondition(
    "terminal timer quiet window",
    () => page.evaluate((startedAt) => performance.now() - startedAt >= 1_200, terminalQuietStart),
    deadline,
  );
  const terminalReadCountAfterQuiet = (await readThreadReadEvidence(page)).starts;
  assert.equal(
    terminalReadCountAfterQuiet,
    terminalReadCountBeforeQuiet,
    "terminal settlement must stop reconciliation timers",
  );
  const liveThreadReadEvidence = await readThreadReadEvidence(page);
  const liveFlickerEvidence = await readFlickerEvidence(page);
  assert.equal(liveFlickerEvidence.status, "available");
  assert.equal(liveFlickerEvidence.responseTextRegressions, 0);
  assert.equal(liveFlickerEvidence.progressTextRegressions, 0);
  assert.deepEqual(liveFlickerEvidence.animationReplays, { response: 0, history: 0 });
  assert.equal(liveFlickerEvidence.backgroundReadWindow.started, true);
  assert.equal(liveFlickerEvidence.backgroundReadWindow.ended, true);
  assert.equal(liveFlickerEvidence.backgroundReadWindow.newConversationDisabledTransitions, 0);
  assert.equal(liveFlickerEvidence.backgroundReadWindow.composerDisabledTransitions, 0);
  assert.equal(
    liveFlickerEvidence.spinnerStartTimes.history.every(
      (startTime) =>
        typeof startTime === "number" && startTime === initialSpinnerStartTimes.history,
    ),
    true,
    "history spinner must keep one animation start time across recovery",
  );
  assert.equal(
    liveFlickerEvidence.spinnerStartTimes.response.every(
      (startTime) =>
        typeof startTime === "number" && startTime === initialSpinnerStartTimes.response,
    ),
    true,
    "response draft must keep one animation start time across recovery",
  );

  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await restoreRuntimeAfterReload(page, deadline);
  runtimeInvocationProbe = await assertRuntimeInvocationProbe(page);
  await selectProject(page, created.workspaceName, deadline);
  await selectThread(page, created.threadId, deadline);
  await page
    .getByText(conversationProgressFixtureMarkers.final, { exact: false })
    .last()
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  await expandProcess(page, deadline);
  const reloadReadSummary = await expandReadResult(workProcess, deadline);
  const restoredItems = await processItems(page, deadline);
  assert.deepEqual(
    restoredItems.map((item) => item.kind),
    ["commentary", "tool", "commentary", "commentary", "tool", "commentary"],
    "reload must restore the persisted final-round reasoning summary",
  );
  assertFinalBodyOutsideProcess(restoredItems, "reload");
  const restoredSequence = restoredItems.map((item) =>
    item.kind === "tool" ? `tool:${item.toolKind}` : item.kind,
  );
  await page.screenshot({
    path: join(evidenceDirectory, "conversation-progress-reload.png"),
    animations: "allow",
  });
  let contextUsage;
  try {
    contextUsage = await verifyContextUsagePopover(page, evidenceDirectory, deadline);
  } catch (error) {
    const consoleTail = consoleErrors.slice(-8).join(" | ");
    throw new Error(
      consoleTail === ""
        ? String(error?.message ?? error)
        : `${String(error?.message ?? error)}; WebView2 console: ${consoleTail}`,
      { cause: error },
    );
  }
  let failureContinuation;
  try {
    failureContinuation = await verifyFailureContinuation(
      page,
      fixture,
      evidenceDirectory,
      deadline,
    );
  } catch (error) {
    const consoleTail = consoleErrors.slice(-8).join(" | ");
    throw new Error(
      consoleTail === ""
        ? String(error?.message ?? error)
        : `${String(error?.message ?? error)}; WebView2 console: ${consoleTail}`,
      { cause: error },
    );
  }
  const identity = await collectRunIdentity(
    page,
    jarPath,
    cargoTargetDirectory,
    consoleErrors,
    pageErrors,
  );
  assert.deepEqual(pageErrors, [], `WebView2 page errors: ${pageErrors.join(" | ")}`);
  const provider = fixture.snapshot();
  const turnAttempts = provider.attempts.filter((attempt) => attempt.kind === "turn");
  assert.equal(turnAttempts.length, 5);
  assert.equal(
    turnAttempts.slice(0, 3).every((attempt, index) => attempt.step === index),
    true,
  );
  assert.equal(
    turnAttempts.every((attempt) => attempt.progressInstruction),
    true,
  );
  assert.equal(turnAttempts[3]?.outcome, "failed");
  assert.equal(turnAttempts[4]?.outcome, "completed");
  return {
    schemaVersion: 1,
    status: "passed",
    runtime: {
      platform: process.platform,
      surface: "tauri_webview2",
      boundary: "jvm_jar",
      nativeImageVerified: false,
    },
    runtimeInvocationProbe: {
      ...runtimeInvocationProbe,
      publicTimelineStoreSingleton,
    },
    provider: {
      kind: "deterministic_loopback",
      externalCalls: 0,
      toolCalls: 2,
      attempts: provider.attempts,
    },
    live: {
      progressInsideProcessBeforeFirstTool: true,
      workingStatusWithProcess: true,
      finalDraftInsideProcessBeforeTerminal: true,
      finalBodyOutsideProcess: true,
      processNodeStable,
      responseNodeStable,
      historyRunningStatusNodeStable,
      animationReplays,
      historyLoadingIndicatorMounts,
      terminalCalibratedExistingResponse: true,
      completedProcessCollapsed: true,
      readSummaryVisible: liveReadSummary.length > 0,
      sequence: liveSequence,
      noDuplicateTools: liveItems.filter((item) => item.kind === "tool").length === 2,
      flicker: {
        resyncReadCount: liveThreadReadEvidence.starts - readBeforeResync.starts,
        threadReadCount: liveThreadReadEvidence.starts,
        liveStreamBaseline,
        terminalReadCountBeforeQuiet,
        terminalReadCountAfterQuiet,
        responseTextRegressions: liveFlickerEvidence.responseTextRegressions,
        progressTextRegressions: liveFlickerEvidence.progressTextRegressions,
        backgroundReadWindow: liveFlickerEvidence.backgroundReadWindow,
        disabledTransitions: liveFlickerEvidence.disabledTransitions,
        spinnerStartTimes: liveFlickerEvidence.spinnerStartTimes,
      },
    },
    reload: {
      sameThread: true,
      readSummaryVisible: reloadReadSummary.length > 0,
      finalBodyOutsideProcess: true,
      sequence: restoredSequence,
    },
    finalVisible: true,
    failureContinuation,
    identity,
    contextUsage,
    screenshots: [
      "conversation-progress-live.png",
      "conversation-progress-reload.png",
      "context-usage-light-wide.png",
      "context-usage-light-narrow.png",
      "context-usage-dark-narrow.png",
      ...failureContinuation.screenshots,
    ],
    pageErrors,
  };
}

/** 解析 runner CLI，强制 evidence/JAR 参数显式传入，避免误连真实用户 profile 或旧产物。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-conversation-progress"),
  };
  for (let index = 0; index < argv.length; index += 2) {
    const argument = argv[index];
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (argument === "--jar") options.jar = resolve(value);
    else if (argument === "--java-home") options.javaHome = resolve(value);
    else if (argument === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else throw new Error(`unknown argument: ${argument}`);
  }
  if (options.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (options.jar === undefined) throw new Error("--jar is required");
  return options;
}

/** 启动 loopback fixture 与复用 production runner 的隔离真窗生命周期，并保证 listener 总能关闭。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const fixture = await startConversationProgressFixture();
  try {
    const report = await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      // 三轮 Provider + 两次 Tool continuation 在慢速 Windows WebView2 上合法超过 Review 的单轮预算；
      // 该值仅写入一次性 E2E profile，不能改变用户或生产 Provider 配置。
      wallTimeoutMs: PROGRESS_TURN_WALL_TIMEOUT_MS,
      scope: "git",
      fixture: "no-head",
      hiddenWindow: true,
      preserveFailedProfile: true,
      // Windows 新 UDF 首次启动不会稳定接受远程调试参数；先只完成 profile 初始化，
      // 再由同一隔离 profile 的受控实例承载 CDP 与业务验收，不会触及用户窗口或数据。
      prewarmWebview: true,
      ignoredFiles: 0,
      untrackedFiles: 0,
      // 失败截图必须在 runner 回收 WebView2 前保存，使等待超时仍保留可核验的真实界面。
      driver: async (driverOptions) => {
        try {
          return await runConversationProgressWebView2({
            ...driverOptions,
            fixture,
            jarPath: options.jar,
            cargoTargetDirectory: options.cargoTargetDirectory,
          });
        } catch (error) {
          const fixtureDiagnostic = safeFixtureFailureSnapshot(fixture.snapshot());
          const runtimeDiagnostic = await readConversationProgressRuntimeDiagnostics(
            driverOptions.page,
          );
          const diagnostic = {
            errorCategory: runnerErrorCategory(error),
            fixture: fixtureDiagnostic,
            runtime: runtimeDiagnostic,
            isolatedRuntimeLogs: await readIsolatedRuntimeLogs(driverOptions.isolatedRuntimeHome),
          };
          await writeFile(
            join(options.evidenceDirectory, "conversation-progress-runtime-diagnostic.json"),
            `${JSON.stringify(diagnostic, null, 2)}\n`,
            "utf8",
          ).catch(() => undefined);
          console.error(
            `JA_CONVERSATION_PROGRESS_RUNTIME_DIAGNOSTIC ${JSON.stringify(diagnostic)}`,
          );
          await driverOptions.page
            .screenshot({
              path: join(options.evidenceDirectory, "failure.png"),
            })
            .catch(() => {});
          throw error;
        }
      },
      validateReport: validateConversationProgressReport,
      reportFileName: "conversation-progress-report.json",
    });
    console.log(`JA_CONVERSATION_PROGRESS_PASS ${JSON.stringify({ status: report.status })}`);
  } finally {
    await fixture.close();
  }
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(
      `JA_CONVERSATION_PROGRESS_FAIL ${String(error?.message ?? error).slice(0, 2000)}`,
    );
    process.exitCode = 1;
  });
}
