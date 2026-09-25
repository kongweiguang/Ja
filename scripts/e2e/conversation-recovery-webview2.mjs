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

/** 显式选择已验证的 EdgeDriver 替代无监听的直接 CDP，保持共享 launcher 的隔离与清理所有权。 */
export function parseArguments(argv) {
  const base = [];
  let edgeDriver;
  for (let index = 0; index < argv.length; index += 2) {
    if (argv[index] === "--edge-driver") {
      if (!argv[index + 1] || argv[index + 1].startsWith("--"))
        throw new Error("missing value for --edge-driver");
      edgeDriver = resolve(argv[index + 1]);
    } else base.push(argv[index], argv[index + 1]);
  }
  return { ...parseBaseArguments(base), ...(edgeDriver ? { edgeDriver } : {}) };
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

/** Provider loopback endpoint 在隔离 profile 创建时写好；只读核对，避免测试多出一次配置 mutation。 */
async function prepare(page, workspaceRoot, endpoint) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor();
  // Settings 由 App Server owner 提供，壳完成首帧时 RuntimeHost 仍可能正在启动；与 reload
  // 使用相同的 authority fence，避免把启动竞态误判为续答链路拒绝。
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor();
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

/** 重载后先选择隔离项目再等待 Runtime；空项目首页不暴露 ready status，之后再选原 Thread identity。 */
async function restore(page, threadId) {
  // 项目导航在 720px 时折叠隐藏；切回宽屏再 reload，重选真实项目时不绕过响应式交互。
  await page.setViewportSize({ width: 1280, height: 820 });
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor();
  const project = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]').first();
  await project.waitFor();
  await project.click();
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor();
  // 通过产品的 typed RuntimeHost 订阅抓取事件，不窥探或修改原生事件通道；仅留公共身份、时序和重试计数，便于定位 UI 投影故障。
  await page.evaluate(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    globalThis.__recoveryRuntimeEvents = [];
    globalThis.__recoveryRuntimeUnsubscribe = await new TauriRuntimeHostAdapter().subscribe(
      (hostEvent) => {
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
      },
    );
  });
  await page.evaluate(() => {
    globalThis.__recoveryInvokeFailures = [];
    const original = globalThis.__TAURI_INTERNALS__.invoke.bind(globalThis.__TAURI_INTERNALS__);
    globalThis.__TAURI_INTERNALS__.invoke = async (...args) => {
      try {
        return await original(...args);
      } catch (error) {
        globalThis.__recoveryInvokeFailures.push({
          command: args[0],
          error: String(error?.message ?? error),
          code: error?.code,
        });
        throw error;
      }
    };
  });
  await page.evaluate(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    globalThis.__recoveryCalls = { turnStart: [], turnContinue: [], turnReask: [] };
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
 * 首轮使用键盘证明极简按钮仍保留原生可访问性，
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
  if (activation === "keyboard") {
    await button.focus();
    await page.keyboard.press("Enter");
  } else {
    await button.click();
  }
}

/** 用户消息只以已渲染 Timeline 为准，避免由本地草稿或 Provider 请求倒推历史。 */
async function userMessages(page) {
  return page.locator('.ja-chat-message-user[data-role="user"]').allTextContents();
}

/**
 * WebView2 的文件粘贴会走 Rust clipboard importer；STA broker 临时提供 CF_HDROP，
 * 并在 finally 恢复原 IDataObject，使附件验收走真实产品路径且不留下用户剪贴板副作用。
 */
async function startClipboardFileBroker(filePath) {
  const encodedPath = Buffer.from(filePath, "utf8").toString("base64");
  const script = [
    "Add-Type -AssemblyName System.Windows.Forms",
    `$target = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedPath}'))`,
    "$original = [Windows.Forms.Clipboard]::GetDataObject()",
    "$files = New-Object System.Collections.Specialized.StringCollection",
    "$null = $files.Add($target)",
    "try { [Windows.Forms.Clipboard]::SetFileDropList($files); [Console]::Out.WriteLine('READY'); [Console]::Out.Flush(); [Console]::In.ReadLine() | Out-Null } finally { if ($null -eq $original) { [Windows.Forms.Clipboard]::Clear() } else { [Windows.Forms.Clipboard]::SetDataObject($original, $true) }; [Console]::Out.WriteLine('RESTORED') }",
  ].join("; ");
  const child = spawn(
    "pwsh.exe",
    [
      "-STA",
      "-NoProfile",
      "-NonInteractive",
      "-EncodedCommand",
      Buffer.from(script, "utf16le").toString("base64"),
    ],
    { windowsHide: true, stdio: ["pipe", "pipe", "pipe"] },
  );
  let stdout = "";
  let stderr = "";
  const ready = new Promise((resolveReady, rejectReady) => {
    child.stdout.on("data", (chunk) => {
      stdout = `${stdout}${chunk}`.slice(-2_048);
      if (/^READY\r?\n/u.test(stdout)) resolveReady();
    });
    child.stderr.on("data", (chunk) => {
      stderr = `${stderr}${chunk}`.slice(-2_048);
    });
    child.once("error", rejectReady);
    child.once("exit", (code) => {
      if (!/^READY\r?\n/u.test(stdout))
        rejectReady(new Error(`clipboard broker exited before ready (${code}): ${stderr}`));
    });
  });
  let readyTimer;
  try {
    await Promise.race([
      ready,
      new Promise((_, reject) => {
        readyTimer = setTimeout(
          () => reject(new Error("clipboard broker readiness timed out")),
          10_000,
        );
      }),
    ]);
  } catch (error) {
    if (child.exitCode === null) {
      const exited = new Promise((resolveExit) => child.once("exit", resolveExit));
      child.stdin.end("restore\n");
      await Promise.race([exited, new Promise((done) => setTimeout(done, 2_000))]);
      if (child.exitCode === null) child.kill();
    }
    throw error;
  } finally {
    clearTimeout(readyTimer);
  }
  return {
    async restore() {
      if (child.exitCode !== null)
        throw new Error(`clipboard broker exited unexpectedly: ${stderr}`);
      const exited = new Promise((resolveExit) => child.once("exit", resolveExit));
      child.stdin.end("restore\n");
      const code = await exited;
      if (code !== 0 || !stdout.includes("RESTORED"))
        throw new Error(`clipboard restoration failed: ${stderr}`);
    },
  };
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
  const button = page.locator(
    '.ja-thread-compaction-feedback button[aria-label="关闭上下文压缩提示"]',
  );
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
  assert.ok(Math.abs(geometry.width - 1.55 * geometry.rootFontSize) < 0.1);
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

/**
 * 在真实隐藏 WebView2 中验收自动重试、无气泡续答、末问重问、附件回读和侧聊工具续跑；
 * fixture 只监听 loopback，报告包含可复核截图与服务端当前路径事实；消息关联使用
 * thread/read 的公开 ID，避免实时事件与快照对同一消息重新编码时误报续答失败。
 */
export async function runRecovery({ page, workspaceRoot, evidenceDirectory, fixture }) {
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
  await attachFixtureFile(page, workspaceRoot, attachmentName);
  const basePrompt = "JA_RECOVERY_BASE_ATTACHED 请检查隔离附件并返回简短答复。";
  await sendText(page, basePrompt);
  await page.getByText("JA_RECOVERY_SUCCESS_JA_RECOVERY_BASE_ATTACHED", { exact: true }).waitFor();
  const baseUser = page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: "JA_RECOVERY_BASE_ATTACHED" });
  await baseUser
    .locator(".ja-chat-attachment__name")
    .getByText(attachmentName, { exact: true })
    .waitFor();

  const continuePrompt = "JA_RECOVERY_CONTINUE 请回答这个原始问题。";
  await sendText(page, continuePrompt);
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
  await continueReply(page, "keyboard");
  await until("manual continuation reaches gated loopback response", () =>
    fixture.attempts.some(
      (attempt) => attempt.step === "JA_RECOVERY_CONTINUE" && attempt.requestNumber === 8,
    ),
  );
  const gatedContinuation = fixture.attempts.find(
    (attempt) => attempt.step === "JA_RECOVERY_CONTINUE" && attempt.requestNumber === 8,
  );
  assert.equal(gatedContinuation?.status, 200);
  assert.equal(gatedContinuation?.continuationContext?.continueMessageCount, 0);
  assert.equal(gatedContinuation?.continuationContext?.originalPromptCount, 1);
  assert.equal(gatedContinuation?.continuationContext?.replayedToolCallCount, 0);
  const continueCall = await page.evaluate(() => globalThis.__recoveryCalls.turnContinue.at(-1));
  assert.equal(continueCall?.input.threadId, created.threadId);
  assert.deepEqual(Object.keys(continueCall?.input ?? {}).sort(), [
    "expectedThreadRevision",
    "threadId",
  ]);
  await page.getByRole("button", { name: "停止生成", exact: true }).waitFor();
  await page.getByRole("button", { name: "继续回复", exact: true }).waitFor({ state: "hidden" });
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
  const completedContinuation = afterContinue.snapshot.turns.find(
    (turn) => turn.sourceMessageId === authoritativeContinueSourceId,
  );
  assert.equal(completedContinuation?.status, "completed");
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
  const currentBeforeReload = await readThreadState(page, created.threadId);
  const reaskTurn = currentBeforeReload.snapshot.turns.find(
    (turn) => turn.turnId === reaskAccepted.turnId,
  );
  assert.equal(
    reaskTurn?.sourceMessageId,
    null,
    "reask Turn is a visible new question, not a continuation",
  );
  assert.equal(
    currentBeforeReload.snapshot.items.some((item) =>
      snapshotItemText(item).includes("JA_RECOVERY_REASK_ORIGINAL"),
    ),
    false,
    "reask must remove the replaced current-path suffix from the projection",
  );
  const continuedTurnAfterReask = currentBeforeReload.snapshot.turns.find(
    (turn) => turn.sourceMessageId === authoritativeContinueSourceId,
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
  assert.ok(
    attachedItem?.attachments?.some((attachment) => attachment.displayName === attachmentName),
    "current-path snapshot must retain the real submitted attachment",
  );
  const beforeReloadFingerprint = recoverySnapshotFingerprint(currentBeforeReload);
  await restore(page, created.threadId);
  await page.getByText("JA_RECOVERY_REASK_EDITED_SUCCESS", { exact: true }).waitFor();
  const afterReload = await readThreadState(page, created.threadId);
  assert.equal(
    recoverySnapshotFingerprint(afterReload),
    beforeReloadFingerprint,
    "reload must restore the same current-path turns, attachment, request usage and history",
  );
  assert.equal(
    (await userMessages(page)).some((text) => text.includes("JA_RECOVERY_REASK_ORIGINAL")),
    false,
  );
  await page
    .locator('.ja-chat-message-user[data-role="user"]')
    .filter({ hasText: "JA_RECOVERY_BASE_ATTACHED" })
    .locator(".ja-chat-attachment__name")
    .getByText(attachmentName, { exact: true })
    .waitFor();
  await page.getByRole("progressbar", { name: "上下文使用量", exact: true }).waitFor();

  const compaction = await verifyCompaction(page, fixture, evidenceDirectory, created.threadId);
  const side = await openSideChat(page);
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
  await longAnswer.getByRole("button", { name: "查看完整回复", exact: true })
    .waitFor({ timeout: 90_000 });
  const finalMessageId = await longAnswer.getAttribute("data-item-id");
  const terminal = await page.evaluate((threadId) =>
    (globalThis.__recoveryRuntimeEvents ?? []).filter((event) =>
      event.method === "turn/terminal" && event.params.threadId === threadId)
      .at(-1)?.params ?? null, side.threadId);
  const expectedRevision = terminal?.threadRevision ?? null;
  const directRead = await page.evaluate(async ({ threadId, messageId, expectedRevision, turnId }) => {
    const { TauriHistoryAdapter } = await import("/src/api/tauri/history.ts");
    // 该路径属于隔离 WebView 的 Vite 模块 URL，保持运行时拼接以免仓库静态依赖分析误作磁盘导入。
    const moduleUrl = ["/src", "features", "conversation", "application", "readFullMessageContent.ts"].join("/");
    const { readFullAnswerContent } = await import(moduleUrl);
    const adapter = new TauriHistoryAdapter();
    const calls = [];
    for (const method of ["threadRead", "messageContentRead"]) {
      const original = adapter[method].bind(adapter);
      adapter[method] = async (input) => {
        try {
          const result = await original(input);
          calls.push({ method, itemId: input.messageId ?? null, count: result.items?.length ??
            result.content?.length ?? null, cursor: input.cursor ?? null,
            revision: result.revision ?? null, nextCursor: result.nextCursor ?? null,
            containsFinal: result.items?.some((item) => item.itemId === messageId) ?? null });
          return result;
        } catch (error) {
          calls.push({ method, itemId: input.messageId ?? null, cursor: input.cursor ?? null,
            code: error?.code ?? null, error: String(error?.message ?? error) });
          throw error;
        }
      };
    }
    try {
      const content = await readFullAnswerContent(adapter, threadId, messageId,
        () => true, expectedRevision ?? undefined, turnId ?? undefined);
      return { characters: [...content].length, complete: content.endsWith("\nJA_RECOVERY_LONG_END"), calls };
    } catch (error) {
      return { error: String(error?.message ?? error), code: error?.code ?? null, calls };
    }
  }, { threadId: side.threadId, messageId: finalMessageId, expectedRevision, turnId: terminal?.turnId });
  assert.equal(directRead?.error, undefined,
    JSON.stringify({ finalMessageId, expectedRevision, directRead }));
  assert.equal(directRead.characters, 70_044);
  assert.equal(directRead.complete, true);
  const pickerAvailable = await page.evaluate(() => typeof globalThis.showSaveFilePicker === "function");
  assert.equal(pickerAvailable, true, "WebView2 must expose a native user-selected save handle");
  await longAnswer.getByRole("button", { name: "查看完整回复", exact: true }).click();
  await until("long answer is complete after pagination", async () =>
    (await longAnswer.locator(".ja-chat-response__long-text").textContent())
      ?.endsWith("\nJA_RECOVERY_LONG_END") === true,
  );
  await page.evaluate(() => {
    globalThis.__recoverySavePickerDescriptor =
      Object.getOwnPropertyDescriptor(globalThis, "showSaveFilePicker");
    Object.defineProperty(globalThis, "showSaveFilePicker", {
      configurable: true,
      value: async (options) => ({
        createWritable: async () => {
          const chunks = [];
          return {
            write: async (chunk) => { chunks.push(chunk); },
            close: async () => {
              const decoder = new TextDecoder();
              let content = "";
              for (const chunk of chunks) content += decoder.decode(chunk, { stream: true });
              content += decoder.decode();
              globalThis.__recoveryExport = {
                suggestedName: options.suggestedName,
                characters: [...content].length,
                complete: content.startsWith("JA_RECOVERY_LONG_START\n") &&
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
  await page.screenshot({ path: join(evidenceDirectory, "side-chat-long-answer-expanded.png") });
  return {
    schemaVersion: 2,
    status: "passed",
    runtime: {
      surface: "tauri_webview2_hidden_window",
      platform: process.platform,
      boundary: "jvm_jar",
      nativeImageVerified: false,
    },
    provider: { kind: "deterministic_loopback", externalCalls: 0, attempts: fixture.attempts },
    recovery: {
      fixtureAttemptCount: 6,
      continueAttemptStatuses: fixture.attempts
        .filter((attempt) => attempt.step === "JA_RECOVERY_CONTINUE")
        .map((attempt) => attempt.status),
      continueHasNoVisibleUserMessage: true,
      continueSourceMessageId: authoritativeContinueSourceId,
      reaskSourceMessageId: authoritativeReaskSourceId,
      reaskCreatesOrdinaryTurn: true,
      reaskRemovesOnlyUnfinishedSuffix: true,
      reloadCurrentPathAttachmentUsageHistory: true,
      sideChatFiveFailuresThenSuccess: true,
      truncatedDraftNotRepeated: true,
      toolExecutedOnceWithOnePersistedResult: true,
      deterministic400DoesNotRetry: true,
      cancelledBackoffSentNoNextRequest: true,
      longAnswerPagedAndExported: true,
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
        ...compaction.screenshots,
      ],
      mainLayouts,
      sideLayouts,
    },
    compaction,
    longContent: { pickerAvailable, ...longExport },
  };
}

/** 长历史压缩在上游持续失败时保持工作态，端点恢复后自行完成且不要求用户重试。 */
async function verifyCompaction(page, fixture, evidenceDirectory, threadId) {
  for (const step of [6, 7, 8]) {
    await send(page, step, " bounded historical evidence ".repeat(800));
    await page.getByText(`JA_RECOVERY_SUCCESS_${step}`, { exact: true }).waitFor();
  }
  const original = await page.locator('.ja-chat-message-user[data-role="user"]').allTextContents();
  const attemptsBefore = fixture.attempts.filter((attempt) => attempt.step === "summary").length;
  await page.getByRole("button", { name: "打开对话操作", exact: true }).click();
  await page.getByRole("menuitem", { name: "压缩上下文", exact: true }).click();
  await page.locator(".ja-thread-compaction-feedback.is-running").waitFor({ timeout: 30_000 });
  await until("summary continues beyond old retry ceiling", () =>
    fixture.attempts.filter((attempt) => attempt.step === "summary" && attempt.status === 503)
      .length >= attemptsBefore + 7,
  );
  assert.equal(await page.locator(".ja-thread-compaction-feedback.is-error").count(), 0);
  // 压缩等待不占据 Bridge actor；同一窗口仍能读取当前会话和累计用量。
  let readDeadline;
  try {
    const duringCompaction = await Promise.race([
      readThreadState(page, threadId),
      new Promise((_, reject) => {
        readDeadline = setTimeout(() => reject(new Error("history read blocked by compaction")), 5_000);
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
  const usageIndicator = page.getByRole("img", {
    name: "上下文使用量待确认",
    exact: true,
  });
  await usageIndicator.waitFor({ timeout: 90_000 });
  // 压缩刚结算时不复用旧请求的比例；圆环以 unknown tone 表达待下一次 Provider 计量确认。
  await until(
    "context usage pending confirmation",
    async () => (await usageIndicator.getAttribute("data-tone")) === "unknown",
  );
  assert.ok(
    fixture.attempts.some((attempt) => attempt.step === "summary" && attempt.status === 200),
  );
  assert.deepEqual(
    await page.locator('.ja-chat-message-user[data-role="user"]').allTextContents(),
    original,
  );
  await page.screenshot({ path: join(evidenceDirectory, "compaction-recovered-unknown.png") });
  const toast = await closeToast(page);
  await send(page, 9);
  await until("last response after compaction", async () => {
    const latest = page.getByRole("button", { name: "回到最新", exact: true });
    if (await latest.isVisible()) await latest.click();
    return await page.getByText("JA_RECOVERY_SUCCESS_9", { exact: true }).isVisible();
  });
  // 下一次真实模型响应重新带回计量后，必须重新成为 progressbar，不能保留压缩前的陈旧值。
  await page.getByRole("progressbar", { name: "上下文使用量", exact: true }).waitFor();
  await page.screenshot({ path: join(evidenceDirectory, "compaction-next-response-known.png") });
  return {
    verified: true,
    toast,
    retainedHistoryAcrossRetries: true,
    recoveredWithoutManualRetry: true,
    unknownAfterCompaction: true,
    knownAfterNextResponse: true,
    screenshots: ["compaction-retrying.png", "compaction-retrying-dark.png",
      "compaction-recovered-unknown.png", "compaction-next-response-known.png"],
  };
}

/** CLI 复用唯一隔离 launcher；失败截图在真窗清理前生成。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const fixture = await startRecoveryFixture();
  try {
    assert.match(fixture.baseUrl, /^http:\/\/127\.0\.0\.1:\d+\/v1$/u);
    await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      prewarmWebview: false,
      hiddenWindow: true,
      preserveFailedProfile: true,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      reportFileName: "conversation-recovery-report.json",
      driver: async (driverOptions) => {
        try {
          return await runRecovery({ ...driverOptions, fixture });
        } catch (error) {
          const diagnostic = {
            attempts: fixture.attempts,
            pageErrors: driverOptions.page.__recoveryPageErrors,
            consoleErrors: driverOptions.page.__recoveryConsoleErrors,
            runtimeEvents: await driverOptions.page
              .evaluate(() => globalThis.__recoveryRuntimeEvents ?? [])
              .catch((error) => String(error).slice(0, 300)),
            timelineBeforeContinue: driverOptions.page.__recoveryTimelineBeforeContinue ?? null,
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
                state: await globalThis.__TAURI_INTERNALS__.invoke("ja_runtime_state", {}),
                recovery: await globalThis.__TAURI_INTERNALS__.invoke(
                  "ja_runtime_recovery_state",
                  {},
                ),
              }))
              .catch((error) => String(error)),
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
