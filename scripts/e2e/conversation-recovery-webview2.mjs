// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* global document, getComputedStyle */

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";
import { parseArguments as parseBaseArguments } from "./conversation-progress-webview2.mjs";
import { startRecoveryFixture } from "./conversation-recovery-fixture.mjs";

/** 分离 EdgeDriver 与剪贴板附件开关，让恢复主流程可独立验收并显式保留附件缺口。 */
export function parseArguments(argv) {
  const base = [];
  let edgeDriver;
  let skipClipboardAttachment = false;
  for (let index = 0; index < argv.length; ) {
    const argument = argv[index];
    if (argument === "--skip-clipboard-attachment") {
      if (skipClipboardAttachment) throw new Error("duplicate --skip-clipboard-attachment");
      skipClipboardAttachment = true;
      index += 1;
      continue;
    }
    const value = argv[index + 1];
    if (!value || value.startsWith("--")) throw new Error(`missing value for ${argument}`);
    if (argument === "--edge-driver") edgeDriver = resolve(value);
    else base.push(argument, value);
    index += 2;
  }
  return {
    ...parseBaseArguments(base),
    ...(edgeDriver ? { edgeDriver } : {}),
    skipClipboardAttachment,
  };
}

/** 条件轮询只等待可核验状态，失败始终落在固定 deadline。 */
async function until(label, predicate) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((done) => setTimeout(done, 100));
  }
  throw new Error(`${label} timed out`);
}

/** 等待 fixture 首次收到新请求；若路由意外变化就立即失败，不耗尽整个期限。 */
async function waitForNextFixtureAttempt(fixture, priorAttemptCount, label) {
  await until(label, () => fixture.attempts.length > priorAttemptCount);
  return fixture.attempts[priorAttemptCount];
}

/** 通过应用 Runtime adapter 验证就绪，避免 E2E 依赖已从侧栏移除的运行时状态文案。 */
async function waitForRuntimeReady(page) {
  await page.waitForFunction(
    async () => {
      try {
        const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
        const state = await new TauriRuntimeHostAdapter().state();
        return state.status === "ready" || state.status === "busy";
      } catch {
        return false;
      }
    },
    undefined,
    { timeout: 30_000 },
  );
}

/** Provider loopback endpoint 在隔离 profile 创建时写好；只读核对，避免测试多出一次配置 mutation。 */
async function prepare(page, workspaceRoot, endpoint) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor();
  // Shell 可先于 RuntimeHost ready；读取应用 owner 状态后再使用权威配置和历史端口。
  await waitForRuntimeReady(page);
  return page.evaluate(
    async ({ cwd, endpoint }) => {
      const { TauriSettingsAdapter } = await import("/src/api/tauri/settings.ts");
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      const adapter = new TauriSettingsAdapter();
      const settings = await adapter.snapshot();
      const provider = settings.document.providers[0];
      if (
        provider?.providerId !== "provider_e2e" ||
        provider.baseUrl !== endpoint ||
        new URL(provider.baseUrl).hostname !== "127.0.0.1"
      )
        throw new Error("isolated profile is not pinned to the loopback recovery fixture");
      return await createHistoryAdapter().threadCreate({
        cwd,
        title: "恢复真窗验收",
        providerId: "provider_e2e",
        modelId: "model_e2e",
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
    },
    { cwd: workspaceRoot, endpoint },
  );
}

/**
 * 在 E2E 专用 delegate 边界注入读取延迟、正文页栅栏和单次 ACK 丢失；Tauri 内部 invoke 属性不可替换，
 * 因此与其他真窗 runner 共用测试 composition 的探针，并始终执行真实 Native 调用。
 */
export function installConversationRecoveryInvokeProbe() {
  globalThis.__recoveryInvokeFailures = [];
  globalThis.__recoveryRawInvokes = [];
  globalThis.__recoveryItemContentReads = [];
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  const observedCommands = new Set([
    "ja_operation_read",
    "ja_thread_read",
    "ja_thread_item_content_read",
    "ja_thread_compact",
    "ja_turn_start",
    "ja_turn_continue",
    "ja_turn_reask",
  ]);
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = typeof request?.command === "string" ? request.command : "invalid_command";
    const input = request?.args?.input;
    if (observedCommands.has(command)) {
      globalThis.__recoveryRawInvokes.push({
        command,
        operationKey:
          typeof input?.operationKey === "string" ? input.operationKey.slice(0, 128) : null,
        threadId: typeof input?.threadId === "string" ? input.threadId.slice(0, 128) : null,
        sourceMessageId:
          typeof input?.sourceMessageId === "string" ? input.sourceMessageId.slice(0, 128) : null,
        itemId: typeof input?.itemId === "string" ? input.itemId.slice(0, 128) : null,
        offsetCharacters: Number.isSafeInteger(input?.offsetCharacters)
          ? input.offsetCharacters
          : null,
        limitCharacters: Number.isSafeInteger(input?.limitCharacters)
          ? input.limitCharacters
          : null,
        expectedThreadRevision: Number.isSafeInteger(input?.expectedThreadRevision)
          ? input.expectedThreadRevision
          : null,
      });
    }
    try {
      const readGate = globalThis.__recoveryContinueReadGate;
      if (
        command === "ja_thread_read" &&
        readGate !== undefined &&
        input?.threadId === readGate.threadId
      ) {
        readGate.waiterCount += 1;
        await readGate.pending;
      }
      const itemContentReadGate = globalThis.__recoveryItemContentReadGate;
      if (
        command === "ja_thread_item_content_read" &&
        itemContentReadGate !== undefined &&
        input?.threadId === itemContentReadGate.threadId &&
        input?.itemId === itemContentReadGate.itemId
      ) {
        itemContentReadGate.waiterCount += 1;
        await itemContentReadGate.pending;
      }
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      if (command === "ja_thread_item_content_read") {
        globalThis.__recoveryItemContentReads.push({
          threadId: input.threadId,
          itemId: input.itemId,
          offsetCharacters: input.offsetCharacters,
          limitCharacters: input.limitCharacters,
          nextOffsetCharacters: Number.isSafeInteger(result?.nextOffsetCharacters)
            ? result.nextOffsetCharacters
            : null,
          totalCharacters: Number.isSafeInteger(result?.totalCharacters)
            ? result.totalCharacters
            : null,
          truncated: typeof result?.truncated === "boolean" ? result.truncated : null,
          contentCharacters:
            typeof result?.content === "string" ? Array.from(result.content).length : null,
        });
      }
      // 真实提交后只丢首个 ACK，由产品 adapter 查询持久回执。
      if (command === "ja_turn_continue" && globalThis.__recoveryDropNextContinueAck === true) {
        globalThis.__recoveryDropNextContinueAck = false;
        globalThis.__recoveryLostContinueAck = {
          input: {
            threadId: input.threadId,
            expectedThreadRevision: input.expectedThreadRevision,
            operationKey: input.operationKey,
          },
          accepted: structuredClone(result),
        };
        throw new Error("JA_E2E_CONTINUE_ACK_DROPPED");
      }
      return result;
    } catch (error) {
      const failure = {
        command,
        code:
          typeof error?.code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/u.test(error.code)
            ? error.code
            : Number.isSafeInteger(error?.code)
              ? error.code
              : null,
      };
      if (command !== "ja_thread_compact")
        failure.error = String(error?.message ?? error).slice(0, 300);
      globalThis.__recoveryInvokeFailures.push(failure);
      throw error;
    }
  };
}

/** 重载后先选择隔离项目；按 Runtime owner 状态确认连接，并只记录事件名/身份诊断解析故障。 */
async function restore(page, threadId) {
  // 项目导航在 720px 时折叠隐藏；切回宽屏再 reload，重选真实项目时不绕过响应式交互。
  await page.setViewportSize({ width: 1280, height: 820 });
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor();
  const project = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]').first();
  await project.waitFor();
  await project.click();
  await waitForRuntimeReady(page);
  // 通过产品的 typed RuntimeHost 订阅抓取事件，并在故障时记录脱敏 Schema 诊断，便于定位投影不一致。
  await page.evaluate(async () => {
    const { defaultNativeBridge, TauriRuntimeHostAdapter } = await import(
      "/src/api/tauri/runtime.ts"
    );
    globalThis.__recoveryRuntimeEvents = [];
    globalThis.__recoveryRetryProjections = [];
    globalThis.__recoveryRawFrames = [];
    const diagnosticBridge = {
      invoke: defaultNativeBridge.invoke,
      listen: (event, handler) =>
        defaultNativeBridge.listen(event, (payload) => {
          const root = payload !== null && typeof payload === "object" ? payload : {};
          const params = root.params !== null && typeof root.params === "object" ? root.params : {};
          const method =
            typeof root.method === "string" && /^[a-z0-9/._-]+$/iu.test(root.method)
              ? root.method
              : "invalid_method";
          const frame = {
            method,
            eventId: typeof params.eventId === "string" ? params.eventId : null,
            sequence: Number.isSafeInteger(params.sequence) ? params.sequence : null,
            threadId: typeof params.threadId === "string" ? params.threadId : null,
            turnId: typeof params.turnId === "string" ? params.turnId : null,
            state: typeof params.state === "string" ? params.state : null,
            errorCode: typeof params.errorCode === "string" ? params.errorCode : null,
            paramsKeys: Object.keys(params).sort(),
          };
          globalThis.__recoveryRawFrames.push(frame);
          if (globalThis.__recoveryRawFrames.length > 256) globalThis.__recoveryRawFrames.shift();
          globalThis.__recoveryLastRawFrame = frame;
          handler(payload);
        }),
    };
    globalThis.__recoveryRuntimeUnsubscribe = await new TauriRuntimeHostAdapter(
      diagnosticBridge,
    ).subscribe((hostEvent) => {
      if (hostEvent.kind === "projection_fault") {
        globalThis.__recoveryRuntimeEvents.push({
          kind: "projection_fault",
          reason: hostEvent.reason,
          frame: globalThis.__recoveryLastRawFrame ?? null,
        });
        return;
      }
      if (hostEvent.kind !== "timeline") return;
      const { method, params } = hostEvent.event;
      if (
        ![
          "turn/retry-started",
          "turn/state-changed",
          "turn/terminal",
          "thread/metadata-changed",
        ].includes(method)
      )
        return;
      const allowed = [
        "serverInstanceId",
        "eventId",
        "sequence",
        "generation",
        "workspaceId",
        "threadId",
        "turnId",
        "threadRevision",
        "occurredAt",
        "attempt",
        "to",
        "status",
        "errorCode",
        "revision",
      ];
      globalThis.__recoveryRuntimeEvents.push({
        method,
        params: Object.fromEntries(
          allowed.filter((key) => key in params).map((key) => [key, params[key]]),
        ),
      });
      if (method === "turn/retry-started") {
        setTimeout(async () => {
          try {
            const { selectItemsForThread, useTimelineStore } = await import(
              "/src/features/conversation/application/timelineStore.ts"
            );
            const state = useTimelineStore.getState();
            const items = selectItemsForThread(params.threadId)(state).filter(
              (item) => item.metadata?.phase === "assistant_retry",
            );
            const statusElements = Array.from(
              document.querySelectorAll('[data-retry-status="true"]'),
            );
            globalThis.__recoveryRetryProjections.push({
              attempt: params.attempt,
              turn: state.turns[params.turnId]
                ? {
                    status: state.turns[params.turnId].status,
                    threadId: state.turns[params.turnId].threadId,
                  }
                : null,
              retryAttempt: state.retryAttemptByTurn[params.turnId]?.attempt ?? null,
              storeState: state.retryingByTurn[params.turnId] ?? null,
              retryingTurnIds: Object.keys(state.retryingByTurn),
              resyncReason: state.resyncRequired[params.threadId] ?? null,
              lastOutcome: state.lastOutcome,
              itemTexts: items.map((item) => item.text),
              domTexts: statusElements.map((element) => element.textContent?.trim() ?? ""),
            });
          } catch (error) {
            globalThis.__recoveryRetryProjections.push({
              attempt: params.attempt,
              readError: String(error).slice(0, 160),
            });
          }
        }, 50);
      }
      // 第五次请求前有 2 秒退避；在事件边界触发真窗 Stop，避免 250ms UI/CDP 竞速。
      const retryWaiter = globalThis.__recoveryRetryEventWaiter;
      if (
        method === "turn/retry-started" &&
        retryWaiter?.threadId === params.threadId &&
        params.attempt === 5
      ) {
        globalThis.clearTimeout(retryWaiter.timeoutId);
        retryWaiter.resolve({
          threadId: params.threadId,
          turnId: params.turnId,
          threadRevision: params.threadRevision,
          sequence: params.sequence,
          attempt: params.attempt,
        });
        globalThis.__recoveryRetryEventWaiter = null;
      }
      if (globalThis.__recoveryRuntimeEvents.length > 100)
        globalThis.__recoveryRuntimeEvents.shift();
    });
  });
  await page.evaluate(installConversationRecoveryInvokeProbe);
  await page.evaluate(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const { TauriHistoryAdapter } = await import("/src/api/tauri/history.ts");
    globalThis.__recoveryCalls = { turnStart: [], turnContinue: [], turnReask: [] };
    globalThis.__recoveryCompactionCalls = [];
    for (const name of ["turnStart", "turnContinue", "turnReask"]) {
      const original = TauriRuntimeHostAdapter.prototype[name];
      TauriRuntimeHostAdapter.prototype[name] = async function (input) {
        const call = { input: structuredClone(input), accepted: undefined };
        globalThis.__recoveryCalls[name].push(call);
        try {
          call.accepted = await original.call(this, input);
          return call.accepted;
        } catch (error) {
          // 只留 typed adapter 的稳定分类和 CAS revision，避免失败诊断泄漏原始 input 或原生错误正文。
          call.failure = {
            name: String(error?.name ?? "Error").slice(0, 80),
            code: typeof error?.code === "string" ? error.code.slice(0, 100) : null,
            retryable: typeof error?.retryable === "boolean" ? error.retryable : null,
            message: String(error?.message ?? error).slice(0, 300),
          };
          throw error;
        }
      };
    }

    /** 压缩失败只记录请求身份、revision 与稳定错误分类，不落盘异常文本、消息正文或密钥。 */
    const originalCompact = TauriHistoryAdapter.prototype.threadCompact;
    TauriHistoryAdapter.prototype.threadCompact = async function observedThreadCompact(input) {
      const call = {
        threadId: typeof input?.threadId === "string" ? input.threadId.slice(0, 128) : null,
        expectedThreadRevision: Number.isSafeInteger(input?.expectedThreadRevision)
          ? input.expectedThreadRevision
          : null,
        result: null,
        failure: null,
      };
      globalThis.__recoveryCompactionCalls.push(call);
      try {
        const result = await originalCompact.call(this, input);
        call.result = {
          outcome:
            result?.outcome === "compacted" || result?.outcome === "unchanged"
              ? result.outcome
              : null,
          threadRevision: Number.isSafeInteger(result?.threadRevision)
            ? result.threadRevision
            : null,
        };
        return result;
      } catch (error) {
        call.failure = {
          code:
            typeof error?.code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/u.test(error.code)
              ? error.code
              : Number.isSafeInteger(error?.code)
                ? error.code
                : null,
          retryable: typeof error?.retryable === "boolean" ? error.retryable : null,
        };
        throw error;
      }
    };
  });
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor();
  await row.click();
  await page.getByRole("textbox", { name: "消息", exact: true }).waitFor();
}

/** 每次普通提交都显式返回正文，供续答验收精确验证历史没有复制原问题。 */
async function send(page, step, padding = "") {
  const text = `JA_RECOVERY_TURN_${step} 请回复当前恢复状态。${padding}`;
  await sendText(page, text);
  return text;
}

/** 失败时记录侧聊 Composer 的可见按钮和命中目标，区分不可操作控件与过时的 role 定位。 */
async function readComposerHitTestDiagnostic(page) {
  return page.evaluate(() => {
    const visible = (element) => {
      const rect = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      return (
        rect.width > 0 &&
        rect.height > 0 &&
        style.display !== "none" &&
        style.visibility !== "hidden"
      );
    };
    const describe = (element) => {
      const rect = element.getBoundingClientRect();
      const x = rect.left + rect.width / 2;
      const y = rect.top + rect.height / 2;
      const hit = document.elementFromPoint(x, y);
      return {
        tag: element.tagName.toLowerCase(),
        ariaLabel: element.getAttribute("aria-label"),
        title: element.getAttribute("title"),
        role: element.getAttribute("role"),
        disabled: element.tagName === "BUTTON" ? element.disabled : null,
        visible: visible(element),
        pointerEvents: getComputedStyle(element).pointerEvents,
        rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
        hitTarget: hit
          ? {
              tag: hit.tagName.toLowerCase(),
              ariaLabel: hit.getAttribute("aria-label"),
              className: typeof hit.className === "string" ? hit.className.slice(0, 160) : "",
            }
          : null,
      };
    };
    return [...document.querySelectorAll(".ja-task-detail")].filter(visible).map((region) => ({
      className: typeof region.className === "string" ? region.className.slice(0, 160) : "",
      composerVisible: visible(region.querySelector(".ja-composer")),
      textboxes: [
        ...region.querySelectorAll('.ja-composer [role="textbox"], .ja-composer textarea'),
      ]
        .filter(visible)
        .map(describe),
      buttons: [...region.querySelectorAll(".ja-composer button")].filter(visible).map(describe),
    }));
  });
}

/** 将主会话和侧聊都通过真实 Composer 提交，避免测试绕过各自的应用控制器。 */
async function sendText(page, text, scope = page) {
  await scope.getByRole("textbox", { name: "消息", exact: true }).fill(text);
  const sendButton = scope.getByRole("button", { name: "发送", exact: true });
  try {
    await sendButton.waitFor();
  } catch (error) {
    if (scope !== page)
      page.__recoveryComposerHitTest = await readComposerHitTestDiagnostic(page).catch(() => null);
    throw error;
  }
  await sendButton.click();
}

/**
 * 续答只通过真实 Composer 图标按钮触发；名称留在 aria-label 与 tooltip，
 * 首轮连续键盘激活验证可访问性与 single-flight，共用真实提交链和服务端回执，
 * 双次激活期间延迟真实状态读取，保证检查待提交阶段，随后原请求仍取得权威状态。
 * 不经由页面内调用绕过焦点、tooltip 或 application single-flight。
 */
async function continueReply(page, activation) {
  const button = page.getByRole("button", { name: "继续回复", exact: true });
  await button.waitFor();
  assert.equal((await button.textContent())?.trim(), "");
  // 冻结点击前的 Timeline CAS 状态，以区分 UI 读旧 revision 与服务端冲突。
  page.__recoveryTimelineBeforeContinue = await readTimelineDiagnostic(
    page,
    page.__recoveryThreadId,
  );
  if (activation === "keyboard" || activation === "keyboard-double") {
    if (activation === "keyboard-double") {
      await page.evaluate((threadId) => {
        let release;
        const pending = new Promise((resolveRead) => {
          release = resolveRead;
        });
        globalThis.__recoveryContinueReadGate = { threadId, pending, release, waiterCount: 0 };
      }, page.__recoveryThreadId);
    }
    try {
      await button.focus();
      await page.keyboard.press("Enter");
      if (activation === "keyboard-double") {
        await until("continuation waits for authoritative state before repeated activation", () =>
          page.evaluate(() => globalThis.__recoveryContinueReadGate?.waiterCount > 0),
        );
        assert.equal(await button.getAttribute("aria-label"), "继续回复");
        assert.equal(await button.isDisabled(), true);
        await page.keyboard.press("Enter");
      }
    } finally {
      if (activation === "keyboard-double") {
        await page.evaluate(() => {
          globalThis.__recoveryContinueReadGate?.release();
          delete globalThis.__recoveryContinueReadGate;
        });
      }
    }
  } else {
    await button.click();
  }
}

/** 用户消息只以已渲染 Timeline 为准，避免由本地草稿或 Provider 请求倒推历史。 */
async function userMessages(page) {
  return page.locator('.ja-chat-message-user[data-role="user"]').allTextContents();
}

const CLIPBOARD_BROKER_READY_TIMEOUT_MS = 10_000;
const CLIPBOARD_BROKER_RESTORE_TIMEOUT_MS = 2_000;

/**
 * @author kongweiguang 只构造受控 STA broker 的启动参数；原 IDataObject 仅在子进程内不透明保存，
 * retry 间隔持续泵消息以避免粘贴/OLE owner 等待，并让 fake child 单测覆盖真实命令编排。
 */
export function createClipboardFileBrokerLaunchSpec(filePath) {
  const encodedPath = Buffer.from(filePath, "utf8").toString("base64");
  const script = [
    "Add-Type -AssemblyName System.Windows.Forms",
    "Add-Type -TypeDefinition 'using System; using System.Threading.Tasks; public static class JaClipboardBrokerInput { /* @author kongweiguang 此读取可能阻塞；放到 worker，避免卡住 STA 上的 Clipboard 消息泵。 */ public static Task<string> ReadLineAsync() { return Task.Run(() => Console.In.ReadLine()); } }'",
    `$target = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedPath}'))`,
    "function Invoke-ClipboardWithRetry { param([scriptblock]$Operation); $delays = @(30,60,120,240,480,800); for ($attempt = 0; $attempt -le $delays.Length; $attempt++) { try { [Windows.Forms.Application]::DoEvents(); $result = & $Operation; [Windows.Forms.Application]::DoEvents(); return $result } catch { $exception = $_.Exception; while ($null -ne $exception.InnerException -and $exception -isnot [System.Runtime.InteropServices.ExternalException]) { $exception = $exception.InnerException }; if ($exception -isnot [System.Runtime.InteropServices.ExternalException] -or $attempt -eq $delays.Length) { throw }; $retryUntil = [DateTime]::UtcNow.AddMilliseconds($delays[$attempt]); while ([DateTime]::UtcNow -lt $retryUntil) { [Windows.Forms.Application]::DoEvents(); [Threading.Thread]::Sleep(10) } } }; throw 'clipboard operation did not complete' }",
    "$original = Invoke-ClipboardWithRetry { [Windows.Forms.Clipboard]::GetDataObject() }",
    "$files = New-Object System.Collections.Specialized.StringCollection",
    "$null = $files.Add($target)",
    "try { [Console]::Out.WriteLine('PUBLISHING'); [Console]::Out.Flush(); $inputTask = [JaClipboardBrokerInput]::ReadLineAsync(); Invoke-ClipboardWithRetry { [Windows.Forms.Clipboard]::SetFileDropList($files) } | Out-Null; [Windows.Forms.Application]::DoEvents(); [Console]::Out.WriteLine('READY'); [Console]::Out.Flush(); while (-not $inputTask.IsCompleted) { [Windows.Forms.Application]::DoEvents(); Start-Sleep -Milliseconds 10 }; $restoreCommand = $inputTask.GetAwaiter().GetResult(); if ($null -ne $restoreCommand -and $restoreCommand -ne 'restore') { throw 'invalid broker shutdown command' } } finally { if ($null -eq $original) { Invoke-ClipboardWithRetry { [Windows.Forms.Clipboard]::Clear() } | Out-Null } else { Invoke-ClipboardWithRetry { [Windows.Forms.Clipboard]::SetDataObject($original, $true) } | Out-Null }; [Console]::Out.WriteLine('RESTORED'); [Console]::Out.Flush() }",
  ].join("; ");
  return {
    executable: "pwsh.exe",
    args: [
      "-STA",
      "-NoProfile",
      "-NonInteractive",
      "-EncodedCommand",
      Buffer.from(script, "utf16le").toString("base64"),
    ],
    options: { windowsHide: true, stdio: ["pipe", "pipe", "pipe"] },
  };
}

/**
 * @author kongweiguang 用 READY/RESTORED 行驱动真实与 fake child；不强杀超时 owner，
 * 已退出且缺少 RESTORED ACK 时保留 PID/退出事实并标 unknown，避免谎称原 IDataObject 可恢复。
 */
export async function superviseClipboardFileBrokerChild(
  child,
  {
    readinessTimeoutMs = CLIPBOARD_BROKER_READY_TIMEOUT_MS,
    restoreTimeoutMs = CLIPBOARD_BROKER_RESTORE_TIMEOUT_MS,
  } = {},
) {
  if (
    !child?.stdout?.on ||
    !child?.stderr?.on ||
    typeof child?.stderr?.resume !== "function" ||
    typeof child?.stdin?.end !== "function"
  ) {
    throw new TypeError("clipboard broker child streams are incomplete");
  }
  if (
    !Number.isSafeInteger(readinessTimeoutMs) ||
    readinessTimeoutMs < 1 ||
    readinessTimeoutMs > CLIPBOARD_BROKER_READY_TIMEOUT_MS ||
    !Number.isSafeInteger(restoreTimeoutMs) ||
    restoreTimeoutMs < 1 ||
    restoreTimeoutMs > CLIPBOARD_BROKER_RESTORE_TIMEOUT_MS
  ) {
    throw new RangeError("clipboard broker deadlines must stay within the production budget");
  }

  const protocolMarkers = new Set(["PUBLISHING", "READY", "RESTORED"]);
  const markers = new Set();
  const markerWaiters = new Map();
  const closeWaiters = new Set();
  let lineBuffer = "";
  let closed = false;
  let protocolFailure = false;
  let exitCode = child.exitCode ?? null;
  let signalCode = child.signalCode ?? null;
  let processExited = exitCode !== null || signalCode !== null;
  let processError = false;
  let restoreRequested = false;
  let restorePromise;

  const state = () => {
    if (markers.has("RESTORED") && markers.has("PUBLISHING")) return "restored";
    if (protocolFailure || processError) return "unknown";
    if (processExited || closed) return markers.has("PUBLISHING") ? "unknown" : "unchanged";
    if (markers.has("READY") && markers.has("PUBLISHING")) return "published";
    return "unknown";
  };

  const observeLine = (line) => {
    const marker = line.replace(/\r$/u, "");
    if (!protocolMarkers.has(marker)) return;
    const validOrder =
      (marker === "PUBLISHING" && markers.size === 0) ||
      (marker === "READY" && markers.has("PUBLISHING") && !markers.has("READY")) ||
      (marker === "RESTORED" && markers.has("PUBLISHING") && !markers.has("RESTORED"));
    if (!validOrder) {
      protocolFailure = true;
      rejectMarkerWaiters();
      return;
    }
    markers.add(marker);
    for (const waiter of markerWaiters.get(marker) ?? []) {
      clearTimeout(waiter.timer);
      waiter.resolve();
    }
    markerWaiters.delete(marker);
  };

  const waitForMarker = (marker, timeoutMs) => {
    if (markers.has(marker)) return Promise.resolve();
    if (closed || processError || protocolFailure)
      return Promise.reject(new Error("clipboard broker closed before protocol acknowledgement"));
    return new Promise((resolvePromise, rejectPromise) => {
      const waiters = markerWaiters.get(marker) ?? new Set();
      const waiter = {
        resolve: resolvePromise,
        reject: rejectPromise,
        timer: setTimeout(() => {
          waiters.delete(waiter);
          if (waiters.size === 0) markerWaiters.delete(marker);
          rejectPromise(
            new Error(`clipboard broker ${marker.toLowerCase()} acknowledgement timed out`),
          );
        }, timeoutMs),
      };
      waiters.add(waiter);
      markerWaiters.set(marker, waiters);
    });
  };

  const waitForClose = (timeoutMs) => {
    if (closed) return Promise.resolve();
    if (processError) return Promise.reject(new Error("clipboard broker process failed"));
    return new Promise((resolvePromise, rejectPromise) => {
      const waiter = {
        resolve: resolvePromise,
        reject: rejectPromise,
        timer: setTimeout(() => {
          closeWaiters.delete(waiter);
          rejectPromise(new Error("clipboard broker did not exit after restoration"));
        }, timeoutMs),
      };
      closeWaiters.add(waiter);
    });
  };

  const rejectMarkerWaiters = () => {
    for (const waiters of markerWaiters.values()) {
      for (const waiter of waiters) {
        clearTimeout(waiter.timer);
        waiter.reject(new Error("clipboard broker closed before protocol acknowledgement"));
      }
    }
    markerWaiters.clear();
  };

  const rejectPendingWaiters = () => {
    rejectMarkerWaiters();
    for (const waiter of closeWaiters) {
      clearTimeout(waiter.timer);
      waiter.reject(new Error("clipboard broker process failed"));
    }
    closeWaiters.clear();
  };

  const settleCloseWaiters = () => {
    for (const waiter of closeWaiters) {
      clearTimeout(waiter.timer);
      waiter.resolve();
    }
    closeWaiters.clear();
  };

  const requestRestore = () => {
    if (restoreRequested || processExited || closed) return;
    restoreRequested = true;
    try {
      child.stdin.end("restore\n");
    } catch {
      // A failed pipe write remains unknown; never infer restoration or terminate the STA owner.
    }
  };

  const markProcessError = () => {
    processError = true;
    rejectPendingWaiters();
  };

  const recoveryHandle = {
    restore: () => restore(),
    get state() {
      return state();
    },
    get pid() {
      return Number.isSafeInteger(child.pid) ? child.pid : null;
    },
    get exited() {
      return processExited || closed;
    },
    get exitCode() {
      return exitCode;
    },
    get signalCode() {
      return signalCode;
    },
    get canRestore() {
      return !processExited && !closed;
    },
  };

  const attachFailureFacts = (error) => {
    error.clipboardState = state();
    error.clipboardRestored = error.clipboardState === "restored";
    error.clipboardUnchanged = error.clipboardState === "unchanged";
    error.clipboardBrokerPid = recoveryHandle.pid;
    error.clipboardBrokerExited = recoveryHandle.exited;
    error.clipboardBrokerExitCode = exitCode;
    error.clipboardBrokerSignalCode = signalCode;
    error.clipboardRecovery = recoveryHandle;
    return error;
  };

  const restore = async () => {
    if (restorePromise) return restorePromise;
    requestRestore();
    const deadline = Date.now() + restoreTimeoutMs;
    const remaining = () => Math.max(1, deadline - Date.now());
    const attempt = (async () => {
      await waitForMarker("RESTORED", remaining());
      await waitForClose(remaining());
      if (exitCode !== 0)
        throw new Error("clipboard broker exited unsuccessfully after restoration acknowledgement");
    })();
    restorePromise = attempt;
    try {
      await attempt;
    } catch (error) {
      throw attachFailureFacts(error);
    } finally {
      if (restorePromise === attempt) restorePromise = undefined;
    }
  };

  child.stdout.on("data", (chunk) => {
    lineBuffer = `${lineBuffer}${String(chunk)}`;
    const lines = lineBuffer.split("\n");
    lineBuffer = lines.pop() ?? "";
    if (lineBuffer.length > 256) lineBuffer = lineBuffer.slice(-256);
    for (const line of lines) observeLine(line);
  });
  // Drain but discard PowerShell diagnostics so stderr backpressure cannot stall the STA protocol or persist clipboard-adjacent data.
  child.stderr.on("data", () => {});
  child.stdout.on("end", () => {
    if (lineBuffer !== "") observeLine(lineBuffer);
    lineBuffer = "";
  });
  // Drop child diagnostics instead of retaining PowerShell text; the stream still must be drained.
  child.stderr.resume();
  child.stdout.on("error", markProcessError);
  child.stderr.on("error", markProcessError);
  child.once("error", markProcessError);
  child.once("exit", (code, signal) => {
    processExited = true;
    exitCode = code;
    signalCode = signal;
  });
  child.once("close", (code, signal) => {
    if (lineBuffer !== "") observeLine(lineBuffer);
    lineBuffer = "";
    if (exitCode === null) exitCode = code;
    if (signalCode === null) signalCode = signal;
    processExited = true;
    closed = true;
    rejectMarkerWaiters();
    settleCloseWaiters();
  });

  try {
    await waitForMarker("READY", readinessTimeoutMs);
    if (processExited || closed || processError || protocolFailure || !markers.has("PUBLISHING")) {
      throw new Error("clipboard broker closed before readiness was usable");
    }
  } catch (sourceError) {
    const error =
      sourceError instanceof Error ? sourceError : new Error("clipboard broker startup failed");
    try {
      await restore();
    } catch {
      // Keep the readiness error; the tri-state and recovery handle below preserve cleanup truth.
    }
    throw attachFailureFacts(error);
  }

  return {
    get clipboardState() {
      return state();
    },
    get clipboardBrokerExitCode() {
      return recoveryHandle.exitCode;
    },
    get clipboardCanRestore() {
      return recoveryHandle.canRestore;
    },
    restore,
  };
}

/**
 * @author kongweiguang 发布仅限测试生成的 CF_HDROP；失败清理由同一状态机处理，
 * 让 caller 只在自然退出及 RESTORED ACK 后释放合成文件。
 */
export async function startClipboardFileBroker(filePath) {
  const launch = createClipboardFileBrokerLaunchSpec(filePath);
  const child = spawn(launch.executable, launch.args, launch.options);
  return superviseClipboardFileBrokerChild(child);
}

/** 通过隔离 workspace 的文件与系统 paste action 验证附件导入、提交和历史呈现。 */
async function attachFixtureFile(page, workspaceRoot, fileName) {
  const path = join(workspaceRoot, fileName);
  await writeFile(path, "Ja recovery fixture attachment\n", "utf8");
  const broker = await startClipboardFileBroker(path);
  try {
    const input = page.getByRole("textbox", { name: "消息", exact: true });
    await input.focus();
    await page.keyboard.press("Control+V");
    const attachment = page
      .locator(`.ja-composer__attachments .ja-composer-attachment[data-state="ready"]`)
      .filter({ hasText: fileName });
    await attachment.waitFor({ state: "visible", timeout: 30_000 });
    return path;
  } finally {
    await broker.restore();
  }
}

/** 读取服务端权威快照和累计用量，reload 前后比较当前路径事实而不复用 React 缓存。 */
async function readThreadState(page, threadId) {
  return page.evaluate(async (id) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const history = createHistoryAdapter();
    const [snapshot, usage] = await Promise.all([
      history.threadRead({ threadId: id, limit: 200 }),
      history.threadUsageRead({ threadId: id }),
    ]);
    if (snapshot.nextCursor !== null) throw new Error("thread/read fixture exceeded one page");
    return { snapshot, usage };
  }, threadId);
}

/** 通过唯一 Timeline store 只读采集版本、最新 Turn 与 resync 计数，不导出会话正文。 */
async function readTimelineDiagnostic(page, threadId) {
  if (!threadId) return null;
  return page.evaluate(async (id) => {
    const { useTimelineStore } = await import(
      "/src/features/conversation/application/timelineStore.ts"
    );
    const state = useTimelineStore.getState();
    const thread = state.threads[id];
    return {
      threadRevision: state.threadRevisionByThread[id] ?? null,
      snapshotRevision: state.snapshotRevisionByThread[id] ?? null,
      thread: thread
        ? {
            revision: thread.revision,
            latestTurnId: thread.latestTurnId ?? null,
            activeTurnId: thread.activeTurnId ?? null,
          }
        : null,
      turns: Object.values(state.turns)
        .filter((turn) => turn.threadId === id)
        .map(({ turnId, status, threadRevision, sourceMessageId }) => ({
          turnId,
          status,
          threadRevision: threadRevision ?? null,
          sourceMessageId: sourceMessageId ?? null,
        })),
      resyncReason: state.resyncRequired[id] ?? null,
      resyncRequestSequence: state.resyncRequestSequenceByThread[id] ?? 0,
    };
  }, threadId);
}

/** 故障现场只读回服务端路径元数据与计量，不把问题正文、附件路径或模型内容写入证据。 */
async function recoveryDiagnosticThreadRead(page, threadId) {
  if (!threadId) return null;
  try {
    const state = await readThreadState(page, threadId);
    return {
      threadRevision: state.snapshot.revision,
      usageSnapshotRevision: state.usage.snapshotRevision,
      turns: state.snapshot.turns.map(
        ({ turnId, sourceMessageId, status, errorCode, threadRevision }) => ({
          turnId,
          sourceMessageId,
          status,
          errorCode,
          threadRevision,
        }),
      ),
      items: state.snapshot.items.map(({ itemId, turnId, kind, status }) => ({
        itemId,
        turnId,
        kind,
        status,
      })),
      contextUsage: state.snapshot.contextUsage,
      usage: state.usage,
    };
  } catch (error) {
    return { readError: String(error?.message ?? error).slice(0, 300) };
  }
}

/** Snapshot USER_INPUT 正文保存在 content blocks，统一提取可见文字以校验重问路径。 */
function snapshotItemText(item) {
  if (typeof item.text === "string") return item.text;
  if (!Array.isArray(item.content)) return "";
  return item.content
    .filter((block) => block?.type === "text" && typeof block.text === "string")
    .map((block) => block.text)
    .join("\n");
}

/** reload 一致性只比较用户可观察的当前路径、附件和计量，不把无关 revision 当作内容变化。 */
function recoverySnapshotFingerprint(state) {
  const usage = { ...state.usage };
  delete usage.snapshotRevision;
  return JSON.stringify({
    turns: state.snapshot.turns.map(({ turnId, sourceMessageId, status, errorCode }) => ({
      turnId,
      sourceMessageId,
      status,
      errorCode,
    })),
    items: state.snapshot.items.map((item) => ({
      itemId: item.itemId,
      turnId: item.turnId,
      kind: item.kind,
      status: item.status,
      text: item.text ?? null,
      content: item.content ?? null,
      attachments: (item.attachments ?? []).map((attachment) => ({
        attachmentId: attachment.attachmentId,
        displayName: attachment.displayName,
        sizeBytes: attachment.sizeBytes,
        mediaKind: attachment.mediaKind,
        mediaType: attachment.mediaType,
      })),
    })),
    contextUsage: state.snapshot.contextUsage,
    usage,
  });
}

/** 在独立侧聊里沿用真实 Workbench 创建入口，确认恢复行为适用于另一条 Thread identity。 */
async function openSideChat(page) {
  const inspector = page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
  if ((await inspector.getAttribute("data-visible")) !== "true")
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click();
  await inspector.waitFor({ state: "visible" });
  const workbench = inspector.locator(".ja-workbench:visible");
  await workbench.waitFor({ state: "visible" });
  const before = await workbench
    .locator('[data-workbench-tab^="side-task:thr_"], [data-tab^="side-task:thr_"]')
    .count();
  await workbench.getByRole("button", { name: "新建标签页", exact: true }).click();
  await page
    .getByRole("menuitem")
    .filter({ hasText: /新建(?:侧边任务|侧聊)/u })
    .last()
    .click();
  await until(
    "new side chat tab",
    async () =>
      (await workbench
        .locator('[data-workbench-tab^="side-task:thr_"], [data-tab^="side-task:thr_"]')
        .count()) > before,
  );
  const tab = workbench
    .locator(
      '.ja-workbench-tab[aria-selected="true"][data-workbench-tab^="side-task:thr_"], .ja-workbench-tab-shell[data-tab^="side-task:thr_"]',
    )
    .last();
  const key =
    (await tab.getAttribute("data-workbench-tab")) ?? (await tab.getAttribute("data-tab"));
  assert.match(key ?? "", /^side-task:thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u);
  const region = page.locator(".ja-task-detail:visible").last();
  const composer = region.locator(".ja-composer");
  await composer.waitFor({ state: "visible" });
  return { threadId: key.slice("side-task:".length), region, composer };
}

/** 只按服务端 Thread identity 切换 Workbench 标签，验证隐藏期间的读取请求失效。 */
async function activateSideChatTab(page, threadId) {
  const key = `side-task:${threadId}`;
  const workbench = page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"] .ja-workbench:visible',
  );
  const tab = workbench.locator(
    `.ja-workbench-tab[data-workbench-tab="${key}"], .ja-workbench-tab-shell[data-tab="${key}"]`,
  );
  await tab.waitFor({ state: "visible" });
  await tab.click();
  await page.locator(".ja-task-detail:visible .ja-composer").last().waitFor({ state: "visible" });
}

/** 真窗主题和窄屏检查读取实际 root theme 与水平溢出，截图保留供人工复核。 */
async function captureLayout(page, evidenceDirectory, { name, width, colorScheme }) {
  await page.setViewportSize({ width, height: 820 });
  await page.emulateMedia({ colorScheme, reducedMotion: "reduce" });
  await until(`${name} ${colorScheme} theme`, () =>
    page.evaluate((expectedDark) => {
      const theme = document.documentElement.dataset.theme;
      const dark =
        theme === undefined
          ? document.documentElement.classList.contains("dark")
          : theme === "dark";
      return dark === expectedDark;
    }, colorScheme === "dark"),
  );
  const layout = await page.evaluate(() => ({
    width: globalThis.innerWidth,
    overflow: document.documentElement.scrollWidth > globalThis.innerWidth + 1,
    dark:
      document.documentElement.dataset.theme === undefined
        ? document.documentElement.classList.contains("dark")
        : document.documentElement.dataset.theme === "dark",
  }));
  assert.equal(layout.width, width);
  assert.equal(layout.overflow, false, `${name} layout overflows at ${width}px`);
  assert.equal(layout.dark, colorScheme === "dark");
  await page.screenshot({ path: join(evidenceDirectory, `${name}-${colorScheme}-${width}.png`) });
  return layout;
}

/** 压缩完成提示使用局部主题按钮，不能出现浏览器默认白底，并由真实点击关闭。 */
async function closeToast(page) {
  const button = page.locator('.ja-toaster button[aria-label="关闭上下文压缩提示"]');
  await button.waitFor();
  const geometry = await button.evaluate((element) => {
    const style = getComputedStyle(element);
    const box = element.getBoundingClientRect();
    const iconBox = element.querySelector("svg").getBoundingClientRect();
    return {
      width: box.width,
      height: box.height,
      radius: style.borderRadius,
      background: style.backgroundColor,
      appearance: style.appearance,
      rootFontSize: parseFloat(getComputedStyle(document.documentElement).fontSize),
      iconOffsetX: Math.abs(iconBox.x + iconBox.width / 2 - box.x - box.width / 2),
      iconOffsetY: Math.abs(iconBox.y + iconBox.height / 2 - box.y - box.height / 2),
    };
  });
  assert.ok(Math.abs(geometry.width - 1.75 * geometry.rootFontSize) < 0.1);
  assert.equal(geometry.width, geometry.height);
  assert.equal(geometry.appearance, "none");
  assert.ok(geometry.iconOffsetX <= 1 && geometry.iconOffsetY <= 1);
  assert.equal(geometry.background, "rgba(0, 0, 0, 0)");
  await button.hover();
  await until(
    "feedback close hover theme",
    async () =>
      (await button.evaluate((element) => getComputedStyle(element).backgroundColor)) !==
      "rgba(0, 0, 0, 0)",
  );
  const hoverBackground = await button.evaluate(
    (element) => getComputedStyle(element).backgroundColor,
  );
  assert.notEqual(hoverBackground, "rgba(0, 0, 0, 0)");
  await button.click();
  await button.waitFor({ state: "hidden" });
  return {
    ...geometry,
    hoverBackground,
    trigger: "automatic_compaction_recovery",
    closedByClick: true,
  };
}

/** 以接受时的 Turn 身份消歧同一 sourceMessageId 上先失败、后续答的两条历史记录。 */
export function findAcceptedContinuationTurn(turns, acceptedTurnId, sourceMessageId) {
  return turns.find(
    (turn) => turn.turnId === acceptedTurnId && turn.sourceMessageId === sourceMessageId,
  );
}

/**
 * 在真实隐藏 WebView2 中验收自动重试、无气泡续答、末问重问、侧聊工具续跑和长用户正文分页；附件可单独跳过。
 * fixture 只监听 loopback，报告包含可复核截图与服务端当前路径事实；accepted 仅表明准入，
 * 重问需从持久历史核对来源、回执修订版与终态，避免把请求成功返回当作业务完成。
 * 续答按接受的 Turn ID 定位并核对来源消息，因为原失败 Turn 与新续答 Turn 会合法共享一个 sourceMessageId。
 * 侧聊 ID 用于失败现场比对权威快照与界面终态，定位事件到达后仍显示工作的恢复缺陷。
 */
export async function runRecovery({
  page,
  workspaceRoot,
  evidenceDirectory,
  fixture,
  skipClipboardAttachment = false,
}) {
  page.__recoveryPageErrors = [];
  page.__recoveryConsoleErrors = [];
  page.on("pageerror", (error) =>
    page.__recoveryPageErrors.push(String(error.message).slice(0, 700)),
  );
  page.on("console", (message) => {
    if (message.type() !== "error" || page.__recoveryConsoleErrors.length >= 80) return;
    page.__recoveryConsoleErrors.push(
      message
        .text()
        .replace(/JA_RECOVERY_[A-Z0-9_]+/gu, "JA_RECOVERY_MARKER")
        .slice(0, 1_500),
    );
  });
  await mkdir(evidenceDirectory, { recursive: true });
  const created = await prepare(page, workspaceRoot, fixture.baseUrl);
  page.__recoveryThreadId = created.threadId;
  await restore(page, created.threadId);
  await page.setViewportSize({ width: 1280, height: 820 });
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });

  const attachmentName = "recovery-fixture.txt";
  if (!skipClipboardAttachment) await attachFixtureFile(page, workspaceRoot, attachmentName);
  const basePrompt = skipClipboardAttachment
    ? "JA_RECOVERY_BASE_ATTACHED 请简短确认隔离恢复验收已启动。"
    : "JA_RECOVERY_BASE_ATTACHED 请检查隔离附件并返回简短答复。";
  await sendText(page, basePrompt);
  await page.getByText("JA_RECOVERY_SUCCESS_JA_RECOVERY_BASE_ATTACHED", { exact: true }).waitFor();
  const baseUser = page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: "JA_RECOVERY_BASE_ATTACHED" });
  if (!skipClipboardAttachment) {
    await baseUser
      .locator(".ja-chat-attachment__name")
      .getByText(attachmentName, { exact: true })
      .waitFor();
  }

  const continuePrompt = "JA_RECOVERY_CONTINUE 请回答这个原始问题。";
  await page.evaluate(async (threadId) => {
    const { useTimelineStore } = await import(
      "/src/features/conversation/application/timelineStore.ts"
    );
    globalThis.__recoveryRetryStoreTrace = [];
    let prior = "";
    globalThis.__recoveryRetryStoreUnsubscribe = useTimelineStore.subscribe((state) => {
      const trace = {
        turns: Object.values(state.turns)
          .filter((turn) => turn.threadId === threadId)
          .map(({ turnId, status }) => ({ turnId, status })),
        retryAttemptByTurn: Object.fromEntries(
          Object.entries(state.retryAttemptByTurn).filter(
            ([turnId]) => state.turns[turnId]?.threadId === threadId,
          ),
        ),
        retryingByTurn: Object.fromEntries(
          Object.entries(state.retryingByTurn).filter(
            ([turnId]) => state.turns[turnId]?.threadId === threadId,
          ),
        ),
        resyncReason: state.resyncRequired[threadId] ?? null,
        lastOutcome: state.lastOutcome,
      };
      const signature = JSON.stringify(trace);
      if (signature === prior) return;
      prior = signature;
      globalThis.__recoveryRetryStoreTrace.push(trace);
    });
  }, created.threadId);
  await sendText(page, continuePrompt);
  await page.waitForFunction(
    () =>
      (globalThis.__recoveryRuntimeEvents ?? []).some(
        (event) => event.method === "turn/retry-started",
      ),
    undefined,
    { timeout: 30_000 },
  );
  await page.waitForTimeout(100);
  await page.screenshot({ path: join(evidenceDirectory, "main-retry-event-light.png") });
  const continueRetry = page.locator('[data-retry-status="true"]');
  await continueRetry.first().waitFor({ state: "visible" });
  assert.equal(await continueRetry.first().innerText(), "正在工作 · 连接中断，正在恢复");
  await page.screenshot({ path: join(evidenceDirectory, "main-retrying-light.png") });
  await until("deterministic rejection after transient continuation attempts", async () => {
    const attempts = fixture.attempts.filter((attempt) => attempt.step === "JA_RECOVERY_CONTINUE");
    return (
      attempts.length === 7 &&
      attempts.slice(0, 6).every((attempt) => attempt.status === 503) &&
      attempts[6]?.status === 400 &&
      (await page.locator('[data-response-state="failed"]').count()) === 1
    );
  });
  const continueSource = page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: "JA_RECOVERY_CONTINUE" });
  const continueSourceId = await continueSource.getAttribute("data-item-id");
  assert.match(continueSourceId ?? "", /^item_/u);
  await page.getByRole("button", { name: "继续回复", exact: true }).waitFor();
  await page.screenshot({ path: join(evidenceDirectory, "main-failed-continue-light.png") });
  const usersBeforeContinue = await userMessages(page);
  const stateBeforeContinue = await readThreadState(page, created.threadId);
  const attemptsBeforeManualContinue = fixture.attempts.length;
  await page.evaluate(() => {
    globalThis.__recoveryDropNextContinueAck = true;
  });
  await continueReply(page, "keyboard-double");
  const manualContinuationAttempt = await waitForNextFixtureAttempt(
    fixture,
    attemptsBeforeManualContinue,
    "manual continuation reaches loopback Provider",
  );
  assert.equal(
    manualContinuationAttempt.step,
    "JA_RECOVERY_CONTINUE",
    `manual continuation was routed as ${manualContinuationAttempt.step}`,
  );
  const gatedContinuation = manualContinuationAttempt;
  assert.equal(gatedContinuation.requestNumber, 8);
  assert.equal(gatedContinuation?.status, 200);
  assert.equal(gatedContinuation?.continuationContext?.continueMessageCount, 0);
  assert.equal(gatedContinuation?.continuationContext?.originalPromptCount, 1);
  assert.equal(gatedContinuation?.continuationContext?.replayedToolCallCount, 0);
  await until("lost continuation ACK reconciled from persistent receipt", () =>
    page.evaluate(() => globalThis.__recoveryCalls.turnContinue.at(-1)?.accepted !== undefined),
  );
  const continueCall = await page.evaluate(() => globalThis.__recoveryCalls.turnContinue.at(-1));
  assert.equal(continueCall?.input.threadId, created.threadId);
  assert.deepEqual(Object.keys(continueCall?.input ?? {}).sort(), [
    "expectedThreadRevision",
    "threadId",
  ]);
  await page.getByRole("button", { name: "停止生成", exact: true }).waitFor();
  await page.getByRole("button", { name: "继续回复", exact: true }).waitFor({ state: "hidden" });
  const continuationReceipt = await page.evaluate(async () => {
    const calls = globalThis.__recoveryCalls.turnContinue;
    const submitted = globalThis.__recoveryRawInvokes.filter(
      (call) => call.command === "ja_turn_continue",
    );
    const lost = globalThis.__recoveryLostContinueAck;
    if (!lost) throw new Error("continuation ACK loss was not exercised");
    const reconciled = globalThis.__recoveryRawInvokes.some(
      (call) =>
        call.command === "ja_operation_read" && call.operationKey === lost.input.operationKey,
    );
    // 重放相同操作 ID 和原 CAS revision，必须返回原 ACK；不能让新 revision 或新 ID 掩盖重复执行。
    const duplicate = await globalThis.__TAURI_INTERNALS__.invoke("ja_turn_continue", {
      input: lost.input,
    });
    const receipt = await globalThis.__TAURI_INTERNALS__.invoke("ja_operation_read", {
      input: { operationKey: lost.input.operationKey },
    });
    return {
      activationCalls: calls.length,
      activationSubmissions: submitted.length,
      reconciled,
      original: lost.accepted,
      duplicate,
      receipt,
      operationKey: lost.input.operationKey,
    };
  });
  assert.equal(continuationReceipt.activationCalls, 1);
  assert.equal(continuationReceipt.activationSubmissions, 1);
  assert.equal(continuationReceipt.reconciled, true);
  assert.deepEqual(continuationReceipt.original, continueCall.accepted);
  assert.deepEqual(continuationReceipt.duplicate, continueCall.accepted);
  assert.equal(continuationReceipt.receipt.status, "committed");
  assert.equal(continuationReceipt.receipt.method, "turn/continue");
  assert.equal(continuationReceipt.receipt.threadId, created.threadId);
  assert.deepEqual(continuationReceipt.receipt.result, continueCall.accepted);
  assert.equal(
    fixture.attempts.filter((attempt) => attempt.step === "JA_RECOVERY_CONTINUE").length,
    8,
    "duplicate continuation must not produce another Provider request",
  );
  const usersWhileContinueRuns = await userMessages(page);
  assert.deepEqual(usersWhileContinueRuns, usersBeforeContinue);
  assert.equal(usersWhileContinueRuns.filter((text) => text.trim() === "继续").length, 0);
  assert.equal(
    usersWhileContinueRuns.filter((text) => text.includes("JA_RECOVERY_CONTINUE")).length,
    1,
  );
  await page.screenshot({ path: join(evidenceDirectory, "main-continuation-running-light.png") });
  fixture.release();
  await page.getByText("JA_RECOVERY_CONTINUE_SUCCESS", { exact: true }).waitFor();
  let afterContinue;
  await until("manual continuation reaches authoritative completion", async () => {
    afterContinue = await readThreadState(page, created.threadId);
    return afterContinue.snapshot.turns.some(
      (turn) => turn.turnId === continueCall?.accepted?.turnId && turn.status === "completed",
    );
  });
  const authoritativeContinueSourceId = afterContinue.snapshot.items.find(
    (item) => item.kind === "user_input" && snapshotItemText(item).includes("JA_RECOVERY_CONTINUE"),
  )?.itemId;
  assert.match(authoritativeContinueSourceId ?? "", /^item_/u);
  const completedContinuation = findAcceptedContinuationTurn(
    afterContinue.snapshot.turns,
    continueCall?.accepted?.turnId,
    authoritativeContinueSourceId,
  );
  assert.equal(completedContinuation?.status, "completed");
  assert.equal(afterContinue.snapshot.turns.length, stateBeforeContinue.snapshot.turns.length + 1);
  await restore(page, created.threadId);
  await page.getByText("JA_RECOVERY_CONTINUE_SUCCESS", { exact: true }).waitFor();
  assert.equal(
    (await userMessages(page)).filter((text) => text.trim() === "继续").length,
    0,
    "manual continue must not create a visible user message",
  );
  await page.getByRole("progressbar", { name: "上下文使用量", exact: true }).waitFor();
  const mainLayouts = [];
  mainLayouts.push(
    await captureLayout(page, evidenceDirectory, {
      name: "main-reloaded",
      width: 1280,
      colorScheme: "light",
    }),
  );
  mainLayouts.push(
    await captureLayout(page, evidenceDirectory, {
      name: "main-reloaded",
      width: 1280,
      colorScheme: "dark",
    }),
  );
  mainLayouts.push(
    await captureLayout(page, evidenceDirectory, {
      name: "main-reloaded",
      width: 720,
      colorScheme: "dark",
    }),
  );
  mainLayouts.push(
    await captureLayout(page, evidenceDirectory, {
      name: "main-reloaded",
      width: 720,
      colorScheme: "light",
    }),
  );

  const reaskOriginal = "JA_RECOVERY_REASK_ORIGINAL 旧问题正文。";
  await sendText(page, reaskOriginal);
  await until("reask source reaches deterministic rejection", async () => {
    const attempts = fixture.attempts.filter(
      (attempt) => attempt.step === "JA_RECOVERY_REASK_ORIGINAL",
    );
    return (
      attempts.length === 7 &&
      attempts.slice(0, 6).every((attempt) => attempt.status === 503) &&
      attempts[6]?.status === 400 &&
      (await page.locator('[data-response-state="failed"]').count()) === 1
    );
  });
  const reaskSource = page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: "JA_RECOVERY_REASK_ORIGINAL" });
  const reaskSourceId = await reaskSource.getAttribute("data-item-id");
  assert.match(reaskSourceId ?? "", /^item_/u);
  const authoritativeReaskSourceId = (
    await readThreadState(page, created.threadId)
  ).snapshot.items.find(
    (item) =>
      item.kind === "user_input" && snapshotItemText(item).includes("JA_RECOVERY_REASK_ORIGINAL"),
  )?.itemId;
  assert.match(authoritativeReaskSourceId ?? "", /^item_/u);
  for (const previousQuestion of ["JA_RECOVERY_BASE_ATTACHED", "JA_RECOVERY_CONTINUE"]) {
    const previous = page
      .locator('.ja-chat-message-user[data-role="user"]')
      .filter({ hasText: previousQuestion });
    assert.equal(await previous.getByRole("button", { name: "编辑问题", exact: true }).count(), 0);
  }
  const editButton = reaskSource.getByRole("button", { name: "编辑问题", exact: true });
  await editButton.waitFor({ state: "visible" });
  assert.equal(await page.getByRole("button", { name: "编辑问题", exact: true }).count(), 1);
  // 桌面指针下操作区只在消息 hover/focus 时接收 pointer；先走真实 hover，避免 Playwright 点到父 article。
  await reaskSource.hover();
  await until(
    "question edit action receives hover pointer",
    async () =>
      (await editButton.evaluate((element) => getComputedStyle(element).pointerEvents)) === "auto",
  );
  await editButton.click();
  await page.getByRole("button", { name: "取消编辑问题", exact: true }).waitFor();
  const editedPrompt = "JA_RECOVERY_REASK_EDITED 请只按修改后的问题重新回答。";
  await page.getByRole("textbox", { name: "消息", exact: true }).fill(editedPrompt);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await page.getByText("JA_RECOVERY_REASK_EDITED_SUCCESS", { exact: true }).waitFor();
  const reaskCall = await page.evaluate(() => globalThis.__recoveryCalls.turnReask.at(-1));
  assert.equal(reaskCall?.input.threadId, created.threadId);
  assert.equal(reaskCall?.input.sourceMessageId, authoritativeReaskSourceId);
  assert.match(reaskCall?.input.content?.[0]?.text ?? "", /JA_RECOVERY_REASK_EDITED/u);
  const reaskAccepted = reaskCall?.accepted;
  assert.equal(typeof reaskAccepted?.turnId, "string");
  assert.ok(Number.isSafeInteger(reaskCall?.input.expectedThreadRevision));
  assert.ok(Number.isSafeInteger(reaskAccepted?.threadRevision));
  assert.ok(reaskAccepted.threadRevision > reaskCall.input.expectedThreadRevision);
  await until("edited reask reaches persisted completed terminal", async () => {
    const state = await readThreadState(page, created.threadId);
    return state.snapshot.turns.some(
      (turn) => turn.turnId === reaskAccepted.turnId && turn.status === "completed",
    );
  });
  const currentBeforeReload = await readThreadState(page, created.threadId);
  const reaskTurn = currentBeforeReload.snapshot.turns.find(
    (turn) => turn.turnId === reaskAccepted.turnId,
  );
  assert.equal(reaskTurn?.status, "completed", "edited reask must reach a persisted terminal");
  assert.equal(
    reaskTurn?.sourceMessageId,
    authoritativeReaskSourceId,
    "reask Turn retains the validated source identity while the replacement gets a new current-path item",
  );
  assert.equal(
    currentBeforeReload.snapshot.items.some(
      (item) =>
        item.kind === "user_input" && snapshotItemText(item).includes("JA_RECOVERY_REASK_EDITED"),
    ),
    true,
    "accepted replacement question must exist in the persisted current path",
  );
  assert.equal(
    currentBeforeReload.snapshot.items.some((item) =>
      snapshotItemText(item).includes("JA_RECOVERY_REASK_ORIGINAL"),
    ),
    false,
    "reask must remove the replaced current-path suffix from the projection",
  );
  const continuedTurnAfterReask = findAcceptedContinuationTurn(
    currentBeforeReload.snapshot.turns,
    continueCall?.accepted?.turnId,
    authoritativeContinueSourceId,
  );
  assert.equal(continuedTurnAfterReask?.status, "completed");
  assert.ok(
    currentBeforeReload.snapshot.contextUsage,
    "current path must retain latest request usage",
  );
  assert.ok(
    currentBeforeReload.usage.totalRequestCount >= 3,
    "usage ledger must retain answered requests",
  );
  const attachedItem = currentBeforeReload.snapshot.items.find((item) =>
    snapshotItemText(item).includes("JA_RECOVERY_BASE_ATTACHED"),
  );
  if (skipClipboardAttachment) {
    assert.equal(
      attachedItem?.attachments?.length ?? 0,
      0,
      "skip mode must not create a clipboard attachment",
    );
  } else {
    assert.ok(
      attachedItem?.attachments?.some((attachment) => attachment.displayName === attachmentName),
      "current-path snapshot must retain the real submitted attachment",
    );
  }
  const beforeReloadFingerprint = recoverySnapshotFingerprint(currentBeforeReload);
  await restore(page, created.threadId);
  await page.getByText("JA_RECOVERY_REASK_EDITED_SUCCESS", { exact: true }).waitFor();
  const afterReload = await readThreadState(page, created.threadId);
  assert.equal(
    recoverySnapshotFingerprint(afterReload),
    beforeReloadFingerprint,
    "reload must restore the same current-path turns, optional attachment, usage and history",
  );
  assert.equal(
    (await userMessages(page)).some((text) => text.includes("JA_RECOVERY_REASK_ORIGINAL")),
    false,
  );
  if (skipClipboardAttachment) {
    assert.equal(
      await baseUser.locator(".ja-chat-attachment__name").count(),
      0,
      "reload in skip mode must not show a clipboard attachment",
    );
  } else {
    await baseUser
      .locator(".ja-chat-attachment__name")
      .getByText(attachmentName, { exact: true })
      .waitFor();
  }
  await page.getByRole("progressbar", { name: "上下文使用量", exact: true }).waitFor();

  const compaction = await verifyCompaction(page, fixture, evidenceDirectory, created.threadId);
  const side = await openSideChat(page);
  page.__recoverySideThreadId = side.threadId;
  const sideLayouts = [
    await captureLayout(page, evidenceDirectory, {
      name: "side-chat-empty",
      width: 1280,
      colorScheme: "light",
    }),
  ];
  const sideRetryPrompt = "JA_RECOVERY_RETRY_SUCCESS 侧聊重试后应完成。";
  await sendText(page, sideRetryPrompt, side.composer);
  await until("side chat exposes automatic retry status", () =>
    side.region
      .locator('[data-retry-status="true"]')
      .count()
      .then((count) => count > 0),
  );
  const sideRetryStatus = side.region.locator('[data-retry-status="true"]').last();
  await sideRetryStatus.waitFor({ state: "visible" });
  assert.equal(await sideRetryStatus.innerText(), "正在工作 · 连接中断，正在恢复");
  await page.screenshot({ path: join(evidenceDirectory, "side-chat-retrying-light.png") });
  await side.region.getByText("JA_RECOVERY_RETRY_SUCCESS_AFTER_FIVE", { exact: true }).waitFor();
  const sideRetryAttempts = fixture.attempts.filter(
    (attempt) => attempt.step === "JA_RECOVERY_RETRY_SUCCESS",
  );
  assert.deepEqual(
    sideRetryAttempts.map((attempt) => attempt.status),
    [503, 503, 503, 503, 503, 200],
  );

  await sendText(page, "JA_RECOVERY_PARTIAL 断流后的半截草稿不可复用。", side.composer);
  await side.region.getByText("JA_RECOVERY_PARTIAL_RETRY_SUCCESS", { exact: true }).waitFor();
  assert.equal(
    await side.region
      .getByText("JA_RECOVERY_PARTIAL_DRAFT_MUST_NOT_REPEAT", { exact: true })
      .count(),
    0,
    "a truncated assistant draft must be cleared before retry output is rendered",
  );
  await sendText(
    page,
    "JA_RECOVERY_TOOL_ONCE 请执行 fixture 允许的单次回显后回答。",
    side.composer,
  );
  await side.region.getByText("JA_RECOVERY_TOOL_ONCE_SUCCESS", { exact: true }).waitFor();
  const toolAttempts = fixture.attempts.filter(
    (attempt) => attempt.step === "JA_RECOVERY_TOOL_ONCE",
  );
  assert.deepEqual(
    toolAttempts.map((attempt) => attempt.functionCallCount),
    [0, 1, 1],
  );
  assert.deepEqual(
    toolAttempts.map((attempt) => attempt.functionCallOutputCount),
    [0, 1, 1],
  );

  // 先验证可取消重试；取消后用末问编辑走 reask，避免把继续状态误当普通新消息入口。
  await page.evaluate((threadId) => {
    let resolve;
    const promise = new Promise((done) => {
      resolve = done;
    });
    const timeoutId = globalThis.setTimeout(() => resolve(null), 10_000);
    globalThis.__recoveryRetryEventWaiter = { threadId, resolve, timeoutId };
    globalThis.__recoveryRetryEventPromise = promise;
  }, side.threadId);
  await sendText(page, "JA_RECOVERY_CANCEL_BACKOFF 在重试等待期间停止。", side.composer);
  const retryStarted = await page.evaluate(() => globalThis.__recoveryRetryEventPromise);
  assert.equal(retryStarted?.threadId, side.threadId);
  assert.equal(retryStarted?.attempt, 5);
  assert.equal(
    fixture.attempts.filter((attempt) => attempt.step === "JA_RECOVERY_CANCEL_BACKOFF").length,
    4,
    "stop must be activated after retry-started and before attempt five",
  );
  await side.region.getByRole("button", { name: "停止生成", exact: true }).click();
  await side.region.locator('[data-response-state="cancelled"]').waitFor({ state: "visible" });
  // 跨过完整 2 秒退避窗口，才能证明取消阻止了第五次请求。
  await page.waitForTimeout(2400);
  const cancelledAttempts = fixture.attempts.filter(
    (attempt) => attempt.step === "JA_RECOVERY_CANCEL_BACKOFF",
  );
  assert.deepEqual(
    cancelledAttempts.map((attempt) => attempt.requestNumber),
    [1, 2, 3, 4],
  );

  const cancelledSource = side.region
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: "JA_RECOVERY_CANCEL_BACKOFF" });
  const cancelledSourceId = await cancelledSource.getAttribute("data-item-id");
  assert.match(cancelledSourceId ?? "", /^item_/u);
  await cancelledSource.hover();
  const cancelledEdit = cancelledSource.getByRole("button", { name: "编辑问题", exact: true });
  await until(
    "cancelled source edit action receives hover pointer",
    async () =>
      (await cancelledEdit.evaluate((element) => getComputedStyle(element).pointerEvents)) ===
      "auto",
  );
  await cancelledEdit.click();
  await page.getByRole("button", { name: "取消编辑问题", exact: true }).waitFor();
  await side.composer
    .getByRole("textbox", { name: "消息", exact: true })
    .fill("JA_RECOVERY_BAD_REQUEST 确定性拒绝不应进入重试。");
  await side.composer.getByRole("button", { name: "发送", exact: true }).click();
  await side.region.locator('[data-response-state="failed"]').waitFor({ state: "visible" });
  await page.waitForTimeout(1_200);
  const badRequestAttempts = fixture.attempts.filter(
    (attempt) => attempt.step === "JA_RECOVERY_BAD_REQUEST",
  );
  assert.deepEqual(
    badRequestAttempts.map((attempt) => attempt.status),
    [400],
  );
  const sideReaskCall = await page.evaluate(() => globalThis.__recoveryCalls.turnReask.at(-1));
  assert.equal(sideReaskCall?.input.threadId, side.threadId);
  assert.equal(sideReaskCall?.input.sourceMessageId, cancelledSourceId);
  assert.match(sideReaskCall?.input.content?.[0]?.text ?? "", /JA_RECOVERY_BAD_REQUEST/u);

  const sideState = await readThreadState(page, side.threadId);
  assert.equal(sideState.snapshot.threadId, side.threadId);
  assert.ok(sideState.snapshot.contextUsage);
  sideLayouts.push(
    await captureLayout(page, evidenceDirectory, {
      name: "side-chat",
      width: 1280,
      colorScheme: "light",
    }),
  );
  sideLayouts.push(
    await captureLayout(page, evidenceDirectory, {
      name: "side-chat",
      width: 1280,
      colorScheme: "dark",
    }),
  );
  sideLayouts.push(
    await captureLayout(page, evidenceDirectory, {
      name: "side-chat",
      width: 720,
      colorScheme: "dark",
    }),
  );
  sideLayouts.push(
    await captureLayout(page, evidenceDirectory, {
      name: "side-chat",
      width: 720,
      colorScheme: "light",
    }),
  );
  // 长答复走真实 Java 分页与 WebView2 UI；保存句柄在页面内替换为只读记录器，避免验收写入用户 Downloads。
  await sendText(page, "JA_RECOVERY_LONG_OUTPUT 请返回超过安全预览的正文。", side.composer);
  const longAnswer = side.region.locator('article[data-role="final"]').last();
  await longAnswer
    .getByRole("button", { name: "查看完整回复", exact: true })
    .waitFor({ timeout: 90_000 });
  const finalMessageId = await longAnswer.getAttribute("data-item-id");
  const terminal = await page.evaluate(
    (threadId) =>
      (globalThis.__recoveryRuntimeEvents ?? [])
        .filter((event) => event.method === "turn/terminal" && event.params.threadId === threadId)
        .at(-1)?.params ?? null,
    side.threadId,
  );
  const expectedRevision = terminal?.threadRevision ?? null;
  const directRead = await page.evaluate(
    async ({ threadId, itemId, expectedRevision, turnId }) => {
      const { TauriHistoryAdapter } = await import("/src/api/tauri/history.ts");
      // 该路径属于隔离 WebView 的 Vite 模块 URL，保持运行时拼接以免仓库静态依赖分析误作磁盘导入。
      const moduleUrl = [
        "/src",
        "features",
        "conversation",
        "application",
        "readFullMessageContent.ts",
      ].join("/");
      const { readFullAnswerContent } = await import(moduleUrl);
      const adapter = new TauriHistoryAdapter();
      const calls = [];
      for (const method of ["threadRead", "itemContentRead"]) {
        const original = adapter[method].bind(adapter);
        adapter[method] = async (input) => {
          try {
            const result = await original(input);
            calls.push({
              method,
              itemId: input.itemId ?? null,
              count: result.items?.length ?? result.content?.length ?? null,
              cursor: input.cursor ?? null,
              revision: result.revision ?? null,
              nextCursor: result.nextCursor ?? null,
              containsFinal: result.items?.some((item) => item.itemId === itemId) ?? null,
            });
            return result;
          } catch (error) {
            calls.push({
              method,
              itemId: input.itemId ?? null,
              cursor: input.cursor ?? null,
              code: error?.code ?? null,
              error: String(error?.message ?? error),
            });
            throw error;
          }
        };
      }
      try {
        const content = await readFullAnswerContent(
          adapter,
          threadId,
          itemId,
          () => true,
          expectedRevision ?? undefined,
          turnId ?? undefined,
        );
        return {
          characters: [...content].length,
          complete: content.endsWith("\nJA_RECOVERY_LONG_END"),
          calls,
        };
      } catch (error) {
        return { error: String(error?.message ?? error), code: error?.code ?? null, calls };
      }
    },
    {
      threadId: side.threadId,
      itemId: finalMessageId,
      expectedRevision,
      turnId: terminal?.turnId,
    },
  );
  assert.equal(
    directRead?.error,
    undefined,
    JSON.stringify({ finalMessageId, expectedRevision, directRead }),
  );
  assert.equal(directRead.characters, 70_044);
  assert.equal(directRead.complete, true);
  const pickerAvailable = await page.evaluate(
    () => typeof globalThis.showSaveFilePicker === "function",
  );
  assert.equal(pickerAvailable, true, "WebView2 must expose a native user-selected save handle");
  await longAnswer.getByRole("button", { name: "查看完整回复", exact: true }).click();
  await until(
    "long answer is complete after pagination",
    async () =>
      (await longAnswer.locator(".ja-chat-response__long-text").textContent())?.endsWith(
        "\nJA_RECOVERY_LONG_END",
      ) === true,
  );
  await page.evaluate(() => {
    globalThis.__recoverySavePickerDescriptor = Object.getOwnPropertyDescriptor(
      globalThis,
      "showSaveFilePicker",
    );
    Object.defineProperty(globalThis, "showSaveFilePicker", {
      configurable: true,
      value: async (options) => ({
        createWritable: async () => {
          const chunks = [];
          return {
            write: async (chunk) => {
              chunks.push(chunk);
            },
            close: async () => {
              const decoder = new TextDecoder();
              let content = "";
              for (const chunk of chunks) content += decoder.decode(chunk, { stream: true });
              content += decoder.decode();
              globalThis.__recoveryExport = {
                suggestedName: options.suggestedName,
                characters: [...content].length,
                complete:
                  content.startsWith("JA_RECOVERY_LONG_START\n") &&
                  content.endsWith("\nJA_RECOVERY_LONG_END"),
              };
            },
          };
        },
      }),
    });
  });
  await longAnswer.getByRole("button", { name: "导出全文", exact: true }).click();
  await longAnswer.getByText("已导出全文。", { exact: true }).waitFor();
  const longExport = await page.evaluate(() => {
    const result = globalThis.__recoveryExport;
    const original = globalThis.__recoverySavePickerDescriptor;
    if (original) Object.defineProperty(globalThis, "showSaveFilePicker", original);
    else Reflect.deleteProperty(globalThis, "showSaveFilePicker");
    return result;
  });
  assert.equal(longExport?.suggestedName, "Ja-回复.txt");
  assert.equal(longExport?.characters, 70_044);
  assert.equal(longExport?.complete, true);

  // 超过单页的真实中文/emoji 用户输入在隐藏侧聊时只允许完成当前页；重新显示后必须从头重试并读完。
  const longQuestion = `JA_RECOVERY_LONG_USER ${"中文😀".repeat(21_850)} USER_INPUT_END`;
  const longQuestionCharacters = Array.from(longQuestion).length;
  assert.ok(longQuestionCharacters > 65_536);
  await sendText(page, longQuestion, side.composer);
  await side.region.getByText("JA_RECOVERY_SUCCESS_LONG_USER", { exact: true }).waitFor();
  const longUserRow = side.region
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: "JA_RECOVERY_LONG_USER" })
    .last();
  const longUserItemId = await longUserRow.getAttribute("data-item-id");
  assert.match(longUserItemId ?? "", /^item_/u);
  const openLongQuestion = longUserRow.getByRole("button", {
    name: "查看完整问题",
    exact: true,
  });
  await openLongQuestion.waitFor();
  await page.evaluate(
    ({ threadId, itemId }) => {
      let release;
      const pending = new Promise((resolve) => {
        release = resolve;
      });
      globalThis.__recoveryItemContentReadGate = {
        threadId,
        itemId,
        pending,
        release,
        waiterCount: 0,
      };
    },
    { threadId: side.threadId, itemId: longUserItemId },
  );
  await openLongQuestion.click();
  await until("long user content read reaches the page gate", () =>
    page.evaluate(() => globalThis.__recoveryItemContentReadGate?.waiterCount === 1),
  );
  let otherSide;
  try {
    otherSide = await openSideChat(page);
    assert.notEqual(otherSide.threadId, side.threadId);
  } finally {
    await page.evaluate(() => {
      globalThis.__recoveryItemContentReadGate?.release();
      delete globalThis.__recoveryItemContentReadGate;
    });
  }
  await until("hidden long user page settles", () =>
    page.evaluate(
      ({ threadId, itemId }) =>
        globalThis.__recoveryItemContentReads?.some(
          (read) => read.threadId === threadId && read.itemId === itemId,
        ),
      { threadId: side.threadId, itemId: longUserItemId },
    ),
  );
  // 两次真实浏览器绘制让隐藏视图的状态提交完成；不能以 Node timer 代替渲染边界。
  await page.evaluate(
    () =>
      new Promise((resolve) =>
        globalThis.requestAnimationFrame(() => globalThis.requestAnimationFrame(resolve)),
      ),
  );
  const hiddenQuestionReads = await page.evaluate(
    ({ threadId, itemId }) => ({
      requests: globalThis.__recoveryRawInvokes.filter(
        (call) =>
          call.command === "ja_thread_item_content_read" &&
          call.threadId === threadId &&
          call.itemId === itemId,
      ),
      pages: globalThis.__recoveryItemContentReads.filter(
        (read) => read.threadId === threadId && read.itemId === itemId,
      ),
    }),
    { threadId: side.threadId, itemId: longUserItemId },
  );
  assert.deepEqual(
    hiddenQuestionReads.requests.map((call) => call.offsetCharacters),
    [0],
    "隐藏目标 Thread 后不能继续请求正文后续页",
  );
  assert.equal(hiddenQuestionReads.pages.length, 1);
  const pageLimit = hiddenQuestionReads.requests[0].limitCharacters;
  assert.ok(Number.isSafeInteger(pageLimit) && pageLimit > 0);
  assert.equal(hiddenQuestionReads.pages[0].nextOffsetCharacters, pageLimit);
  await activateSideChatTab(page, side.threadId);
  const retryLongQuestion = longUserRow.getByRole("button", {
    name: /^(?:查看完整问题|重试读取全文)$/u,
  });
  await retryLongQuestion.click();
  await until(
    "long user text is complete in the visible Thread",
    async () => (await longUserRow.textContent())?.includes("USER_INPUT_END") === true,
  );
  await page.waitForFunction(
    ({ threadId, itemId, expectedCount }) =>
      globalThis.__recoveryItemContentReads.filter(
        (read) => read.threadId === threadId && read.itemId === itemId,
      ).length === expectedCount,
    {
      threadId: side.threadId,
      itemId: longUserItemId,
      expectedCount: 1 + Math.ceil(longQuestionCharacters / pageLimit),
    },
  );
  const fullQuestionReads = await page.evaluate(
    ({ threadId, itemId }) =>
      globalThis.__recoveryItemContentReads.filter(
        (read) => read.threadId === threadId && read.itemId === itemId,
      ),
    { threadId: side.threadId, itemId: longUserItemId },
  );
  assert.deepEqual(
    fullQuestionReads.map((read) => read.offsetCharacters),
    [
      0,
      ...Array.from(
        { length: Math.ceil(longQuestionCharacters / pageLimit) },
        (_, index) => index * pageLimit,
      ),
    ],
  );
  assert.deepEqual(
    fullQuestionReads.slice(1).map((read) => ({
      offsetCharacters: read.offsetCharacters,
      nextOffsetCharacters: read.nextOffsetCharacters,
      totalCharacters: read.totalCharacters,
      truncated: read.truncated,
      contentCharacters: read.contentCharacters,
    })),
    Array.from({ length: Math.ceil(longQuestionCharacters / pageLimit) }, (_, index) => {
      const offsetCharacters = index * pageLimit;
      const contentCharacters = Math.min(pageLimit, longQuestionCharacters - offsetCharacters);
      const truncated = offsetCharacters + contentCharacters < longQuestionCharacters;
      return {
        offsetCharacters,
        nextOffsetCharacters: truncated ? offsetCharacters + contentCharacters : null,
        totalCharacters: longQuestionCharacters,
        truncated,
        contentCharacters,
      };
    }),
  );
  const renderedLongQuestion = await longUserRow.textContent();
  assert.ok(renderedLongQuestion?.includes(longQuestion));
  await page.screenshot({ path: join(evidenceDirectory, "side-chat-long-question-expanded.png") });
  await page.screenshot({ path: join(evidenceDirectory, "side-chat-long-answer-expanded.png") });
  assert.deepEqual(page.__recoveryPageErrors, [], "recovery flow emitted unhandled page errors");
  assert.deepEqual(page.__recoveryConsoleErrors, [], "recovery flow emitted console errors");
  return {
    schemaVersion: 2,
    status: "passed",
    runtime: {
      surface: "tauri_webview2_hidden_window",
      platform: process.platform,
      boundary: "jvm_jar",
      nativeImageVerified: false,
    },
    clipboardAttachment: skipClipboardAttachment
      ? { status: "skipped", reason: "clipboard broker readiness was not established" }
      : { status: "passed" },
    provider: { kind: "deterministic_loopback", externalCalls: 0, attempts: fixture.attempts },
    diagnostics: {
      pageErrors: page.__recoveryPageErrors,
      consoleErrors: page.__recoveryConsoleErrors,
    },
    recovery: {
      fixtureAttemptCount: 6,
      continueAttemptStatuses: fixture.attempts
        .filter((attempt) => attempt.step === "JA_RECOVERY_CONTINUE")
        .map((attempt) => attempt.status),
      continueHasNoVisibleUserMessage: true,
      continueRepeatedActivationSingleFlight: true,
      continueLostAckReconciled: true,
      continueDuplicateReceiptIdempotent: true,
      continueOperationKey: continuationReceipt.operationKey,
      continueSourceMessageId: authoritativeContinueSourceId,
      reaskSourceMessageId: authoritativeReaskSourceId,
      reaskAcceptedTurnId: reaskAccepted.turnId,
      reaskExpectedThreadRevision: reaskCall.input.expectedThreadRevision,
      reaskAcceptedThreadRevision: reaskAccepted.threadRevision,
      reaskTerminalStatus: reaskTurn.status,
      reaskCreatesOrdinaryTurn: true,
      reaskRemovesOnlyUnfinishedSuffix: true,
      reloadCurrentPathAttachmentUsageHistory: !skipClipboardAttachment,
      reloadCurrentPathThreadUsageHistory: true,
      sideChatFiveFailuresThenSuccess: true,
      truncatedDraftNotRepeated: true,
      toolExecutedOnceWithOnePersistedResult: true,
      deterministic400DoesNotRetry: true,
      cancelledBackoffSentNoNextRequest: true,
      longAnswerPagedAndExported: true,
      longQuestionPagedAfterThreadSwitch: true,
    },
    evidence: {
      screenshots: [
        "main-retrying-light.png",
        "main-failed-continue-light.png",
        "main-continuation-running-light.png",
        "main-reloaded-light-1280.png",
        "main-reloaded-dark-1280.png",
        "main-reloaded-dark-720.png",
        "main-reloaded-light-720.png",
        "side-chat-empty-light-1280.png",
        "side-chat-retrying-light.png",
        "side-chat-light-1280.png",
        "side-chat-dark-1280.png",
        "side-chat-dark-720.png",
        "side-chat-light-720.png",
        "side-chat-long-answer-expanded.png",
        "side-chat-long-question-expanded.png",
        ...compaction.screenshots,
      ],
      mainLayouts,
      sideLayouts,
    },
    compaction,
    longContent: {
      pickerAvailable,
      ...longExport,
      userQuestionCharacters: longQuestionCharacters,
      userQuestionReadPages: fullQuestionReads.length,
    },
  };
}

/**
 * 将当前上下文 aria 状态与脱正文 thread/read 计量逐阶段写入 evidence；后续 sidecar 故障
 * 不应丢失压缩恢复前后已验证的来源切换事实。
 */
async function recordContextUsageSnapshot(page, evidenceDirectory, threadId, phase) {
  const usageIndicator = page.getByRole("progressbar", {
    name: "上下文使用量",
    exact: true,
  });
  const [ariaValueText, threadRead] = await Promise.all([
    usageIndicator.getAttribute("aria-valuetext"),
    recoveryDiagnosticThreadRead(page, threadId),
  ]);
  const snapshot = {
    phase,
    capturedAt: new Date().toISOString(),
    ariaValueText,
    threadRead: threadRead
      ? {
          threadRevision: threadRead.threadRevision ?? null,
          usageSnapshotRevision: threadRead.usageSnapshotRevision ?? null,
          contextUsage: threadRead.contextUsage ?? null,
          usage: threadRead.usage ?? null,
          ...(threadRead.readError ? { readError: threadRead.readError } : {}),
        }
      : null,
  };
  const snapshots = page.__recoveryContextUsageSnapshots ?? [];
  snapshots.push(snapshot);
  page.__recoveryContextUsageSnapshots = snapshots;
  await writeFile(
    join(evidenceDirectory, "context-usage-snapshots.json"),
    `${JSON.stringify(snapshots, null, 2)}\n`,
    "utf8",
  );
  return snapshot;
}

/** 长历史压缩在上游持续失败时保持工作态；恢复后先展示检查点估算，再由下一次响应替换为实测。 */
async function verifyCompaction(page, fixture, evidenceDirectory, threadId) {
  for (const step of [6, 7, 8]) {
    await send(page, step, " bounded historical evidence ".repeat(800));
    await page.getByText(`JA_RECOVERY_SUCCESS_${step}`, { exact: true }).waitFor();
  }
  const original = await page.locator('.ja-chat-message-user[data-role="user"]').allTextContents();
  const attemptsBefore = fixture.attempts.filter((attempt) => attempt.step === "summary").length;
  await page.getByRole("button", { name: "打开对话操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "压缩上下文", exact: true }).click();
  await page.waitForFunction(
    () => {
      const feedback = [...document.querySelectorAll(".ja-toaster [data-sonner-toast]")].find(
        (element) => element.textContent.includes("上下文"),
      );
      return (
        feedback?.querySelector('[role="status"]') || feedback?.querySelector('[role="alert"]')
      );
    },
    undefined,
    { timeout: 30_000 },
  );
  assert.ok(
    await page.locator('.ja-toaster [role="status"]').filter({ hasText: "上下文" }).isVisible(),
    "manual compaction failed before entering its running phase",
  );
  await until(
    "summary continues beyond old retry ceiling",
    () =>
      fixture.attempts.filter((attempt) => attempt.step === "summary" && attempt.status === 503)
        .length >=
      attemptsBefore + 7,
  );
  assert.equal(
    await page.locator('.ja-toaster [role="alert"]').filter({ hasText: "上下文" }).count(),
    0,
  );
  // 压缩等待不占据 Bridge actor；同一窗口仍能读取当前会话和累计用量。
  let readDeadline;
  try {
    const duringCompaction = await Promise.race([
      readThreadState(page, threadId),
      new Promise((_, reject) => {
        readDeadline = setTimeout(
          () => reject(new Error("history read blocked by compaction")),
          5_000,
        );
      }),
    ]);
    assert.equal(duringCompaction.snapshot.threadId, threadId);
  } finally {
    clearTimeout(readDeadline);
  }
  assert.deepEqual(
    await page.locator('.ja-chat-message-user[data-role="user"]').allTextContents(),
    original,
  );
  // 与续答完成态一致，确认压缩前的真实 Provider 计量仍可访问。
  await page.getByRole("progressbar", { name: "上下文使用量", exact: true }).waitFor();
  await page.screenshot({ path: join(evidenceDirectory, "compaction-retrying.png") });
  await recordContextUsageSnapshot(
    page,
    evidenceDirectory,
    threadId,
    "before-checkpoint-during-summary-retry",
  );
  await page.emulateMedia({ colorScheme: "dark" });
  await until("system dark theme", () =>
    page.evaluate(
      () =>
        document.documentElement.classList.contains("dark") ||
        document.documentElement.dataset.theme === "dark",
    ),
  );
  await page.screenshot({ path: join(evidenceDirectory, "compaction-retrying-dark.png") });
  fixture.recoverSummary();
  const usageIndicator = page.getByRole("progressbar", {
    name: "上下文使用量",
    exact: true,
  });
  await usageIndicator.waitFor({ timeout: 90_000 });
  // Checkpoint 已给出新的本地预算时应立即显示 estimate，不沿用压缩前的 Provider 实测值。
  await until("context usage switches to checkpoint estimate", async () =>
    (await usageIndicator.getAttribute("aria-valuetext"))?.includes("本地估算"),
  );
  assert.ok(
    fixture.attempts.some((attempt) => attempt.step === "summary" && attempt.status === 200),
  );
  assert.deepEqual(
    await page.locator('.ja-chat-message-user[data-role="user"]').allTextContents(),
    original,
  );
  await page.screenshot({ path: join(evidenceDirectory, "compaction-recovered-estimated.png") });
  await recordContextUsageSnapshot(page, evidenceDirectory, threadId, "checkpoint-estimate");
  const toast = await closeToast(page);
  await send(page, 9);
  await until("last response after compaction", async () => {
    const latest = page.getByRole("button", { name: "回到最新", exact: true });
    if (await latest.isVisible()) await latest.click();
    return await page.getByText("JA_RECOVERY_SUCCESS_9", { exact: true }).isVisible();
  });
  // 下一次 Provider 响应结算后验证来源切换到实测，避免估算或旧值永久停留。
  await until("context usage switches to Provider measurement", async () =>
    (await usageIndicator.getAttribute("aria-valuetext"))?.includes("Provider 完整用量"),
  );
  await page.screenshot({ path: join(evidenceDirectory, "compaction-next-response-known.png") });
  await recordContextUsageSnapshot(
    page,
    evidenceDirectory,
    threadId,
    "next-response-provider-known",
  );
  return {
    verified: true,
    toast,
    retainedHistoryAcrossRetries: true,
    recoveredWithoutManualRetry: true,
    estimatedAfterCompaction: true,
    knownAfterNextResponse: true,
    contextUsageSnapshots: page.__recoveryContextUsageSnapshots ?? [],
    screenshots: [
      "compaction-retrying.png",
      "compaction-retrying-dark.png",
      "compaction-recovered-estimated.png",
      "compaction-next-response-known.png",
    ],
  };
}

/** CLI 复用隔离 launcher；fresh UDF 首启可能有 WebView2 进程却无 CDP listener，先预热同一隔离 profile；失败截图与诊断在清理前生成。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const fixture = await startRecoveryFixture();
  try {
    assert.match(fixture.baseUrl, /^http:\/\/127\.0\.0\.1:\d+\/v1$/u);
    await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      prewarmWebview: true,
      hiddenWindow: true,
      preserveFailedProfile: true,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      reportFileName: "conversation-recovery-report.json",
      driver: async (driverOptions) => {
        try {
          return await runRecovery({
            ...driverOptions,
            fixture,
            skipClipboardAttachment: options.skipClipboardAttachment,
          });
        } catch (error) {
          const diagnostic = {
            attempts: fixture.attempts,
            pageErrors: driverOptions.page.__recoveryPageErrors,
            consoleErrors: driverOptions.page.__recoveryConsoleErrors,
            runtimeEvents: await driverOptions.page
              .evaluate(() => globalThis.__recoveryRuntimeEvents ?? [])
              .catch((error) => String(error).slice(0, 300)),
            rawRuntimeFrames: await driverOptions.page
              .evaluate(() => globalThis.__recoveryRawFrames ?? [])
              .catch((error) => String(error).slice(0, 300)),
            retryProjections: await driverOptions.page
              .evaluate(() => globalThis.__recoveryRetryProjections ?? [])
              .catch((error) => String(error).slice(0, 300)),
            retryStoreTrace: await driverOptions.page
              .evaluate(() => globalThis.__recoveryRetryStoreTrace ?? [])
              .catch((error) => String(error).slice(0, 300)),
            timelineBeforeContinue: driverOptions.page.__recoveryTimelineBeforeContinue ?? null,
            contextUsageSnapshots: driverOptions.page.__recoveryContextUsageSnapshots ?? [],
            sideThreadId: driverOptions.page.__recoverySideThreadId ?? null,
            sideTimeline: await readTimelineDiagnostic(
              driverOptions.page,
              driverOptions.page.__recoverySideThreadId,
            ).catch((error) => ({ readError: String(error).slice(0, 300) })),
            sideThreadRead: await recoveryDiagnosticThreadRead(
              driverOptions.page,
              driverOptions.page.__recoverySideThreadId,
            ),
            sideResponseStates: await driverOptions.page
              .locator(".ja-task-detail:visible")
              .last()
              .locator("[data-response-state]")
              .evaluateAll((elements) =>
                elements.map((element) => ({
                  state: element.getAttribute("data-response-state"),
                  itemId: element.getAttribute("data-item-id"),
                  threadId: element.getAttribute("data-thread-id"),
                  turnId: element.getAttribute("data-turn-id"),
                })),
              )
              .catch((error) => ({ readError: String(error).slice(0, 300) })),
            timelineAfterFailure: await readTimelineDiagnostic(
              driverOptions.page,
              driverOptions.page.__recoveryThreadId,
            ).catch((error) => ({ readError: String(error).slice(0, 300) })),
            threadRead: await recoveryDiagnosticThreadRead(
              driverOptions.page,
              driverOptions.page.__recoveryThreadId,
            ),
            visibleDom: await driverOptions.page
              .locator("body")
              .innerText()
              .then((text) =>
                text.replace(/JA_RECOVERY_[A-Z0-9_]+/gu, "JA_RECOVERY_MARKER").slice(-2_000),
              )
              .catch((error) => String(error).slice(0, 300)),
            composerHitTest: driverOptions.page.__recoveryComposerHitTest ?? null,
            invokes: await driverOptions.page
              .evaluate(async () => ({
                calls: Object.fromEntries(
                  Object.entries(globalThis.__recoveryCalls ?? {}).map(([name, calls]) => [
                    name,
                    calls.map(({ input, accepted, failure }) => ({
                      accepted: accepted
                        ? { turnId: accepted.turnId, threadRevision: accepted.threadRevision }
                        : null,
                      hasContent: Boolean(input?.content),
                      sourceMessageId: input?.sourceMessageId ?? null,
                      expectedThreadRevision: input?.expectedThreadRevision ?? null,
                      failure: failure ?? null,
                    })),
                  ]),
                ),
                failures: globalThis.__recoveryInvokeFailures,
                rawNativeCalls: globalThis.__recoveryRawInvokes ?? [],
                compactionCalls: globalThis.__recoveryCompactionCalls ?? [],
                state: await globalThis.__TAURI_INTERNALS__.invoke("ja_runtime_state", {}),
                recovery: await globalThis.__TAURI_INTERNALS__.invoke(
                  "ja_runtime_recovery_state",
                  {},
                ),
              }))
              .catch((error) => String(error)),
            pendingOperations: await driverOptions.page
              .evaluate(async () => {
                const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
                return (await new TauriRuntimeHostAdapter().pendingOperations()).map((record) => ({
                  operationKey:
                    typeof record.operationKey === "string"
                      ? record.operationKey.slice(0, 128)
                      : null,
                  method:
                    typeof record.method === "string" && /^[a-z0-9/._-]+$/iu.test(record.method)
                      ? record.method
                      : "invalid_method",
                  threadId:
                    typeof record.threadId === "string" ? record.threadId.slice(0, 128) : null,
                }));
              })
              .catch((error) => ({ readError: String(error).slice(0, 300) })),
          };
          await writeFile(
            join(options.evidenceDirectory, "diagnostics.json"),
            JSON.stringify(diagnostic, null, 2),
          );
          console.error("RECOVERY_DIAGNOSTIC", JSON.stringify(diagnostic));
          await driverOptions.page
            .screenshot({ path: join(options.evidenceDirectory, "failure.png") })
            .catch(() => {});
          throw error;
        }
      },
    });
    console.log("JA_CONVERSATION_RECOVERY_PASS");
  } finally {
    await fixture.close();
  }
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url)
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
