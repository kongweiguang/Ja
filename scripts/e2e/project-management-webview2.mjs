// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* global document, window, HTMLElement */

/**
 * Windows Tauri/WebView2 项目改名、失效目录恢复、重绑和完整进程重启验收。
 * 每轮均由 review-redesign-production 提供隔离 profile；重启阶段只复制该 profile 的 data 目录，
 * 使第二个独立 Tauri/App Server 进程能验证数据库中的项目身份与 Thread 历史。
 */

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import {
  copyFile,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rename,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { tmpdir } from "node:os";
import process from "node:process";
import { startClipboardBroker } from "./image-history-production.mjs";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { responseStream } from "./conversation-recovery-fixture.mjs";
import { createReviewGitFixture, runProduction } from "./review-redesign-production.mjs";

const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const INITIAL_HISTORY_MARKER = "JA_PROJECT_MANAGEMENT_HISTORY_PERSISTED";
const BUSY_TURN_MARKER = "JA_PROJECT_MANAGEMENT_BUSY_TURN";
const BUSY_TURN_REPLY = "JA_PROJECT_MANAGEMENT_BUSY_TURN_COMPLETED";
const RENAMED_PROJECT_NAME = "路径失效后改名的 Ja 项目";
const LONG_PATH_DRAFT = `C:\\JaE2E\\${"nested folder ".repeat(9)}最终项目`;
const MENU_ITEMS = Object.freeze(["新建对话", "编辑项目…", "在资源管理器中打开", "复制项目路径"]);
const MAIN_TURN_TIMEOUT_MS = 60_000;
const PROJECT_IGNORED_FILES = 2_000;
const PROJECT_UNTRACKED_FILES = 2_000;
const execFileAsync = promisify(execFile);
const HIDDEN_WORKSPACE_IO_COMMANDS = Object.freeze([
  "ja_workspace_tree",
  "ja_workspace_read_file",
  "ja_workspace_search",
  "ja_workspace_open_targets",
  "ja_workspace_watch_rescan",
]);

/** Windows identity 比较忽略大小写并统一分隔符；报告仍只保存比较结果，不输出临时绝对路径。 */
function samePath(left, right) {
  const normalize = (value) =>
    resolve(value)
      .replace(/^\\\\\?\\/u, "")
      .replaceAll("/", "\\")
      .toLocaleLowerCase("en-US");
  return normalize(left) === normalize(right);
}

/** @author kongweiguang 解析隔离 runner 参数；明确 JAR、证据与 Cargo target，避免误连用户运行实例。 */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: undefined,
    diagnoseBinding: false,
    diagnoseWorkspaceUpdate: false,
  };
  for (let index = 0; index < argv.length; ) {
    const argument = argv[index];
    if (argument === "--diagnose-binding") {
      options.diagnoseBinding = true;
      index += 1;
      continue;
    }
    if (argument === "--diagnose-workspace-update") {
      options.diagnoseWorkspaceUpdate = true;
      index += 1;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--"))
      throw new Error(`missing value for ${argument}`);
    if (argument === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (argument === "--jar") options.jar = resolve(value);
    else if (argument === "--java-home") options.javaHome = resolve(value);
    else if (argument === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else throw new Error(`unknown argument: ${argument}`);
    index += 2;
  }
  if (options.evidenceDirectory === undefined) throw new Error("--evidence-directory is required");
  if (options.jar === undefined) throw new Error("--jar is required");
  if (options.cargoTargetDirectory === undefined)
    throw new Error("--cargo-target-directory is required to reuse the prebuilt Tauri target");
  if (options.diagnoseBinding && options.diagnoseWorkspaceUpdate)
    throw new Error("diagnostic modes cannot be combined");
  const evidenceRelation = relative(resolve(tmpdir()), options.evidenceDirectory);
  if (
    evidenceRelation.length === 0 ||
    isAbsolute(evidenceRelation) ||
    evidenceRelation === ".." ||
    evidenceRelation.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`)
  )
    throw new Error("--evidence-directory must be inside the operating system Temp directory");
  return options;
}

/** @author kongweiguang 构造允许报告通过的闭集；身份、工作面与重启证据必须完整。 */
export function validateProjectManagementReport(report) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.status, "passed");
  assert.deepEqual(report?.runtime, {
    platform: "win32",
    surface: "tauri_webview2",
    boundary: "debug_jar",
  });
  for (const field of [
    "workspaceIdFormat",
    "projectMenuFourItems",
    "lightDarkThemesVerified",
    "keyboardContextMenu",
    "escapeRestoresFocus",
    "cancelDiscardedDraft",
    "narrowDialogFitsViewport",
    "longPathDialogLayoutVerified",
    "directoryNoticeHiddenDuringEdit",
    "projectPathCopiedToNativeClipboard",
    "missingDirectoryRecheckPreservedHistory",
    "missingDirectoryBindingStable",
    "initialHistoryVisible",
    "busyTurnBlockedDirectoryChange",
    "busyErrorVisible",
    "busyUpdateReturnedExpectedSummary",
    "missingDirectorySelectable",
    "missingDirectoryHistoryVisible",
    "missingDirectoryErrorVisible",
    "renameWithMissingDirectory",
    "directoryRebound",
    "workspaceIdPreservedAfterRestart",
    "threadIdPreservedAfterRestart",
    "historyVisibleAfterRestart",
    "serverRestartVerified",
    "workspaceReadProbePositiveControl",
    "sameWorkspaceThreadSwitchVerified",
    "dirtyDocumentSaveCancelDiscardVerified",
    "pressureFixtureCountsVerified",
    "hiddenReviewSnapshotStable",
    "hiddenFilesIoZero",
  ]) {
    assert.equal(report?.coverage?.[field], true, `coverage.${field} must be true`);
  }
  assert.equal(report?.coverage?.menuItemCount, MENU_ITEMS.length);
  assert.deepEqual(report?.coverage?.menuItemLabels, MENU_ITEMS);
  assert.deepEqual(report?.pageErrors, []);
  assert.ok(Array.isArray(report?.screenshots) && report.screenshots.length >= 4);
  return report;
}

/** @author kongweiguang 取消草稿与两种视口焦点恢复均成功才投影为布尔验收，避免把详情对象当成成功标记。 */
export function projectDialogCancellationPassed(result) {
  return (
    result?.cancelled === true &&
    result.escapeRestoresFocusToProject === true &&
    result.escapeRestoresToVisibleFallbackNarrow === true
  );
}

/** 创建只记录请求数量的 deferred，供 busy-turn fixture 在 UI 尝试重绑时保持真实在途。 */
function deferred() {
  let resolvePromise;
  let rejectPromise;
  const promise = new Promise((resolveValue, rejectValue) => {
    resolvePromise = resolveValue;
    rejectPromise = rejectValue;
  });
  return { promise, resolve: resolvePromise, reject: rejectPromise };
}

/** Cap each user-visible UI wait so a broken menu or dialog leaves useful evidence promptly. */
function interactionTimeout(deadline, maximum = 20_000) {
  return Math.max(1, Math.min(maximum, deadline - Date.now()));
}

/** @author kongweiguang 等待背景提示正常退出后才审查 Modal，避免裁剪截图漏掉窄窗口标题被遮挡的问题。 */
async function waitForDirectoryNoticeToLeaveDialog(page, deadline) {
  await page.waitForFunction(
    () =>
      ![...document.querySelectorAll(".ja-notification-content")].some((node) =>
        node.textContent?.includes("项目目录不可用"),
      ),
    undefined,
    { timeout: interactionTimeout(deadline) },
  );
}

/** @author kongweiguang 真实菜单写入系统剪贴板后只做已知路径匹配，finally 恢复全部原始格式。 */
async function verifyProjectPathClipboard(page, project, workspace, deadline) {
  const clipboard = await startClipboardBroker();
  try {
    await project.click({ button: "right", timeout: interactionTimeout(deadline) });
    await page.getByRole("menuitem", { name: "复制项目路径", exact: true }).click({
      timeout: interactionTimeout(deadline),
    });
    const copyDeadline = Math.min(deadline, Date.now() + 5_000);
    while (Date.now() < copyDeadline) {
      if (await clipboard.matchesText(workspace.root)) return true;
      await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
    }
    throw new Error("project path did not reach the native clipboard");
  } finally {
    await clipboard.restore();
  }
}

/** 启动绑定 loopback 的受控 Responses Provider，并可暂停指定 Turn 以验收目录变更门禁。 */
export async function startProjectManagementProviderFixture() {
  const busyStarted = deferred();
  const releaseBusy = deferred();
  let requestCount = 0;
  let acceptanceTurnCount = 0;
  let busyTurnCount = 0;
  let busyHeld = false;
  const server = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== "/v1/responses") {
      response.writeHead(404, { "content-type": "application/json" });
      response.end('{"error":"not_found"}');
      return;
    }
    const chunks = [];
    let receivedBytes = 0;
    for await (const chunk of request) {
      receivedBytes += chunk.length;
      if (receivedBytes > 2 * 1024 * 1024) {
        response.writeHead(413, { "content-type": "application/json" });
        response.end('{"error":"request_too_large"}');
        return;
      }
      chunks.push(chunk);
    }
    let payload;
    try {
      payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
      response.writeHead(400, { "content-type": "application/json" });
      response.end('{"error":"invalid_json"}');
      return;
    }
    requestCount += 1;
    const input = JSON.stringify(payload?.input ?? []);
    let reply = INITIAL_HISTORY_MARKER;
    if (input.includes(BUSY_TURN_MARKER)) {
      busyTurnCount += 1;
      busyHeld = true;
      busyStarted.resolve();
      await releaseBusy.promise;
      reply = BUSY_TURN_REPLY;
    } else if (input.includes(INITIAL_HISTORY_MARKER)) {
      acceptanceTurnCount += 1;
    }
    if (response.destroyed) return;
    const stream = responseStream(reply, `project_management_${requestCount}`);
    response.writeHead(200, {
      "cache-control": "no-store",
      "content-length": Buffer.byteLength(stream, "utf8"),
      "content-type": "text/event-stream; charset=utf-8",
    });
    response.end(stream, "utf8");
  });
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  return {
    baseUrl: `http://127.0.0.1:${address.port}/v1`,
    /** 只等待已发出的阻塞请求，不复用请求输入，避免把历史正文写入证据。 */
    async waitForBusyTurn(timeoutMs = MAIN_TURN_TIMEOUT_MS) {
      let timeoutHandle;
      try {
        await Promise.race([
          busyStarted.promise,
          new Promise((_, reject) => {
            timeoutHandle = setTimeout(
              () => reject(new Error("busy Provider request timed out")),
              timeoutMs,
            );
          }),
        ]);
      } finally {
        clearTimeout(timeoutHandle);
      }
      return busyHeld;
    },
    /** 放行唯一 held Turn，使后续的路径变更和 App Server 停止处于空闲状态。 */
    releaseBusyTurn() {
      releaseBusy.resolve();
    },
    /** 只暴露闭集数量，不返回任何请求正文、文件路径或 Provider payload。 */
    snapshot() {
      return { requestCount, acceptanceTurnCount, busyTurnCount };
    },
    /** 关闭本轮 loopback listener 与尚未完成请求，避免 runner 遗留后台端口。 */
    async close() {
      releaseBusy.resolve();
      server.closeAllConnections();
      await new Promise((resolvePromise, reject) =>
        server.close((error) => (error === undefined ? resolvePromise() : reject(error))),
      );
    },
  };
}

/** 建立两个真实临时目录；原项目可被移动，重绑目标留在本 runner 的外层 Temp 中跨进程存续。 */
export async function createProjectManagementFixture(root) {
  const originalRoot = join(root, "原始 项目 工作区");
  const movedRoot = join(root, "搬迁后 项目 工作区");
  const otherRoot = join(root, "对照 项目 工作区");
  const lostRoot = join(root, "临时移走 项目 工作区");
  for (const directory of [originalRoot, movedRoot, otherRoot])
    await mkdir(directory, { recursive: true });
  await writeFile(join(originalRoot, "README 中文 示例.md"), "项目重绑历史 fixture。\n", "utf8");
  await writeFile(join(movedRoot, "README 中文 示例.md"), "搬迁后的真实文件夹。\n", "utf8");
  await writeFile(join(originalRoot, "项目重绑保存.txt"), "保存前的原目录内容。\n", "utf8");
  await writeFile(join(movedRoot, "项目重绑保存.txt"), "", "utf8");
  await writeFile(join(movedRoot, "项目重绑放弃.txt"), "放弃前的搬迁目录内容。\n", "utf8");
  await writeFile(join(otherRoot, "README.md"), "用于切换项目时释放旧目录。\n", "utf8");
  return { originalRoot, movedRoot, otherRoot, lostRoot };
}

/** @author kongweiguang 只留 allowlisted command、短 Workspace ID 和脱敏拒绝元组，便于区分 Host 与 App Server。 */
export function installProjectManagementInvokeProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  // This function is serialized into WebView by addInitScript, so it must not close over Node module constants.
  const commands = new Set([
    "ja_runtime_workspace_open",
    "ja_runtime_workspace_read",
    "ja_runtime_workspace_update",
    "ja_runtime_state",
    "ja_review_catalog",
    "ja_review_snapshot",
    "ja_workspace_tree",
    "ja_workspace_read_file",
    "ja_workspace_save_file",
    "ja_workspace_search",
    "ja_workspace_open_targets",
    "ja_workspace_watch_rescan",
    "ja_preview_resolve_file",
  ]);
  globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__ = [];
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = request?.command;
    const input = request?.args?.input;
    const workspaceId = input?.workspaceId;
    const entry = commands.has(command)
      ? {
          command,
          phase: "start",
          workspaceId: typeof workspaceId === "string" ? workspaceId.slice(0, 12) : null,
          atMs: Math.round(performance.now()),
        }
      : undefined;
    if (entry !== undefined) globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__.push(entry);
    const finish = (phase, failure) => {
      if (entry !== undefined) {
        globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__.push({
          ...entry,
          phase,
          ...(failure === undefined ? {} : { failure }),
        });
      }
    };
    try {
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      finish("resolved");
      return result;
    } catch (error) {
      const candidate = error !== null && typeof error === "object" ? error : undefined;
      const errorCode =
        typeof candidate?.code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/u.test(candidate.code)
          ? candidate.code
          : "UNKNOWN";
      const message = typeof candidate?.message === "string" ? candidate.message : "";
      finish("rejected", {
        errorCode,
        runnerPhaseClassification:
          errorCode === "WORKSPACE_BUSY" && message.startsWith("暂时无法更改项目目录：")
            ? "busy_summary_shape"
            : "origin_unconfirmed",
        retryable: typeof candidate?.retryable === "boolean" ? candidate.retryable : null,
      });
      throw error;
    }
  };
}

/** @author kongweiguang 投影只含 allowlisted command、phase 和短 Workspace ID，足以计算真实树/Review 边界。 */
export async function projectManagementInvokeSnapshot(page) {
  return page.evaluate(() =>
    (globalThis.__JA_PROJECT_MANAGEMENT_INVOKES__ ?? []).map(
      ({ command, phase, workspaceId, failure }) => ({
        command,
        phase,
        workspaceId,
        ...(failure === undefined
          ? {}
          : {
              failure: {
                errorCode: failure.errorCode,
                runnerPhaseClassification: failure.runnerPhaseClassification,
                retryable: failure.retryable,
              },
            }),
      }),
    ),
  );
}

/** @author kongweiguang 统计 isolated Git root 的实际 ignored/untracked 项，避免只相信 fixture 参数。 */
export async function countProjectPressureFiles(workspaceRoot) {
  const run = (args) =>
    execFileAsync("git.exe", args, {
      cwd: workspaceRoot,
      windowsHide: true,
      timeout: 30_000,
      maxBuffer: 8 * 1024 * 1024,
    });
  const [ignored, untracked] = await Promise.all([
    run(["ls-files", "--others", "--ignored", "--exclude-standard", "-z", "--", ".review-ignored"]),
    run(["ls-files", "--others", "--exclude-standard", "-z", "--", "zz-review-untracked"]),
  ]);
  const count = (output) => output.split("\0").filter((entry) => entry.length > 0).length;
  return { ignoredFiles: count(ignored.stdout), untrackedFiles: count(untracked.stdout) };
}

/** 从真窗 UI 等待当前 Runtime ready；排除壳层先显示、Java Host 尚未握手的半启动状态。 */
async function waitForApplication(page, deadline) {
  const timeout = Math.max(1, Math.min(30_000, deadline - Date.now()));
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({ state: "visible", timeout });
  await page.waitForFunction(
    async () => {
      try {
        const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
        const state = await new TauriRuntimeHostAdapter().state();
        return (
          (state.status === "ready" || state.status === "busy") &&
          typeof state.serverInstanceId === "string" &&
          state.serverInstanceId.length > 0
        );
      } catch {
        return false;
      }
    },
    undefined,
    { timeout, polling: 250 },
  );
}

/** 在 isolated Java 配置中登记实际 Project 与持久 Thread，避免测试夹具只伪造侧栏节点。 */
async function createProjectAndThread(
  page,
  cwd,
  displayName,
  endpoint,
  threadTitle = "项目目录恢复历史验收",
) {
  return page.evaluate(
    async ({ directory, name, providerEndpoint, threadTitle }) => {
      const [{ TauriSettingsAdapter }, { createHistoryAdapter }] = await Promise.all([
        import("/src/api/tauri/settings.ts"),
        import("/src/api/tauri/history.ts"),
      ]);
      const settings = new TauriSettingsAdapter();
      const snapshot = await settings.snapshot();
      const provider = snapshot.document.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (provider === undefined || provider.baseUrl !== providerEndpoint)
        throw new Error("isolated loopback Provider was not staged");
      const modelId = provider.models[0]?.modelId;
      if (modelId === undefined) throw new Error("isolated Provider model is missing");
      const history = createHistoryAdapter();
      const workspace = await history.workspaceOpen({ cwd: directory, displayName: name });
      await history.workspaceSetTrust({ workspaceId: workspace.workspaceId, trust: "trusted" });
      const thread = await history.threadCreate({
        cwd: workspace.root,
        title: threadTitle,
        providerId: provider.providerId,
        modelId,
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
      if (thread.workspaceId !== workspace.workspaceId)
        throw new Error("project Thread did not keep its Workspace identity");
      return {
        workspaceId: workspace.workspaceId,
        threadId: thread.threadId,
        displayName: workspace.displayName,
      };
    },
    { directory: cwd, name: displayName, providerEndpoint: endpoint, threadTitle },
  );
}

/** @author kongweiguang 按真实 command trace 聚合边界计数；每个 phase 都是 typed adapter 的实际调用。 */
export function summarizeProjectManagementInvokes(trace) {
  const counts = {};
  const byWorkspace = {};
  for (const event of trace) {
    if (
      typeof event?.command !== "string" ||
      !["start", "resolved", "rejected"].includes(event.phase)
    )
      continue;
    const bucket = (target) => {
      const current = target[event.command] ?? { start: 0, resolved: 0, rejected: 0 };
      current[event.phase] += 1;
      target[event.command] = current;
    };
    bucket(counts);
    if (typeof event.workspaceId === "string") {
      const workspaceCounts = (byWorkspace[event.workspaceId] ??= {});
      bucket(workspaceCounts);
    }
  }
  return { counts, byWorkspace };
}

/** @author kongweiguang 比较同一窗口 command trace，明确区分隐藏零调用与探针未安装。 */
export function projectManagementInvokeDelta(before, after, command, workspaceId) {
  const select = (trace) =>
    trace.filter(
      (event) =>
        event.command === command &&
        event.phase === "start" &&
        (workspaceId === undefined || event.workspaceId === workspaceId),
    ).length;
  return select(after) - select(before);
}

/** 点击真实项目行并等绑定结束；仅等 aria-current 会撞上项目切换中的临时禁用态。 */
async function selectProject(page, displayName, deadline) {
  const project = page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: displayName });
  await project.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  if ((await project.getAttribute("aria-current")) !== "page")
    await project.click({ timeout: Math.max(1, deadline - Date.now()) });
  await page.waitForFunction(
    (name) =>
      [
        ...document.querySelectorAll('[aria-label="项目列表"] button[data-scope-kind="project"]'),
      ].some(
        (node) =>
          node.textContent?.includes(name) &&
          node.getAttribute("aria-current") === "page" &&
          node.disabled === false,
      ),
    displayName,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  return project;
}

/** Capture caught React console diagnostics before the isolated WebView is closed; pageerror alone misses boundaries. */
export async function captureRendererDiagnostics(page, errors, evidenceDirectory, stage) {
  const serializedErrors = await Promise.all(
    errors.map(async (entry) => ({
      ...entry,
      arguments: await Promise.all(
        (entry.handles ?? []).map(async (handle) => {
          try {
            return await handle.evaluate((value) => {
              if (value instanceof Error)
                return { name: value.name, message: value.message, stack: value.stack ?? null };
              if (typeof value === "string") return value.slice(0, 2_000);
              try {
                return JSON.stringify(value)?.slice(0, 2_000) ?? String(value).slice(0, 2_000);
              } catch {
                return Object.prototype.toString.call(value);
              }
            });
          } catch (error) {
            return { unavailable: String(error?.message ?? error).slice(0, 300) };
          }
        }),
      ),
    })),
  );
  const dom = await page
    .evaluate(() => ({
      title: document.title,
      url: window.location.href,
      readyState: document.readyState,
      viewport: { width: window.innerWidth, height: window.innerHeight },
      shell: [...document.querySelectorAll(".ja-shell")].map((node) => ({
        ready: node.getAttribute("data-app-ready"),
        className: node.className,
        text: node.innerText.slice(0, 1_500),
      })),
      projectRows: [...document.querySelectorAll('[aria-label="项目列表"] button')]
        .slice(0, 30)
        .map((node) => ({
          text: node.innerText.slice(0, 160),
          kind: node.getAttribute("data-scope-kind"),
          current: node.getAttribute("aria-current"),
          disabled: node.disabled,
          ariaDisabled: node.getAttribute("aria-disabled"),
          className: node.className,
        })),
      dialogs: [...document.querySelectorAll('[role="dialog"], [role="alertdialog"]')].map((node) =>
        node.innerText.slice(0, 500),
      ),
      bodyText: document.body.innerText.slice(0, 5_000),
    }))
    .catch((error) => ({ unavailable: String(error?.message ?? error).slice(0, 500) }));
  const runtime = await Promise.race([
    page.evaluate(async () => {
      try {
        const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
        const state = await new TauriRuntimeHostAdapter().state();
        return {
          status: state.status,
          generationPresent: state.generation !== undefined,
          serverInstancePresent: typeof state.serverInstanceId === "string",
        };
      } catch (error) {
        return { error: String(error?.message ?? error).slice(0, 500) };
      }
    }),
    new Promise((resolvePromise) =>
      setTimeout(() => resolvePromise({ unavailable: "runtime state probe exceeded 2s" }), 2_000),
    ),
  ]);
  const invokeProbe = await page
    .evaluate(() => {
      const trace = globalThis.__JA_PROJECT_MANAGEMENT_RPC_TRACE__;
      if (!Array.isArray(trace)) return { installed: false, events: [] };
      const pending = new Map();
      const counts = {};
      for (const event of trace) {
        counts[event.command] = (counts[event.command] ?? 0) + 1;
        if (event.phase === "start") pending.set(event.sequence, event.command);
        else pending.delete(event.sequence);
      }
      return {
        installed: true,
        counts,
        pending: [...pending.entries()].map(([sequence, command]) => ({ sequence, command })),
        events: trace.slice(-100),
      };
    })
    .catch((error) => ({ unavailable: String(error?.message ?? error).slice(0, 500) }));
  await mkdir(evidenceDirectory, { recursive: true });
  await writeFile(
    join(evidenceDirectory, `${stage}-console-errors.json`),
    `${JSON.stringify(
      serializedErrors.map((entry) => ({
        type: entry.type,
        text: entry.text,
        location: entry.location,
        arguments: entry.arguments,
      })),
      null,
      2,
    )}\n`,
    "utf8",
  );
  await writeFile(
    join(evidenceDirectory, `${stage}-dom.json`),
    `${JSON.stringify({ ...dom, runtime, invokeProbe }, null, 2)}\n`,
    "utf8",
  );
  await page
    .screenshot({ path: join(evidenceDirectory, `${stage}-failure.png`), animations: "disabled" })
    .catch(() => {});
  return { consoleErrors: serializedErrors, dom };
}

/**
 * @author kongweiguang
 * 直接包裹 WebView2 注入的 invoke，以观察跨 reload 的原生调用完成态；只持久化命令、
 * 错误码、短 Workspace ID 与耗时，不序列化参数、配置、凭据或完整异常消息。
 */
export function installDirectProjectManagementInvokeProbe(options = {}) {
  const targetWindow = options.window ?? window;
  const storage = options.storage ?? targetWindow.sessionStorage;
  const storageKey = options.storageKey ?? "ja-project-binding-diagnostic-v1";
  const internals = targetWindow.__TAURI_INTERNALS__;
  const original = internals?.invoke;
  if (typeof original !== "function") return { installed: false, reason: "invoke_unavailable" };
  if (internals.__jaProjectBindingInvokeProbe === true) return { installed: true, reused: true };

  const commands = new Set([
    "ja_runtime_workspace_open",
    "ja_runtime_workspace_read",
    "ja_runtime_workspace_update",
    "ja_configuration_read",
    "ja_runtime_state",
  ]);
  let records = [];
  try {
    const parsed = JSON.parse(storage.getItem(storageKey) ?? "[]");
    if (Array.isArray(parsed)) records = parsed.slice(-500);
  } catch {
    records = [];
  }
  const persist = () => {
    try {
      storage.setItem(storageKey, JSON.stringify(records.slice(-500)));
    } catch {
      // A full sessionStorage must not change the native call's behavior.
    }
  };
  const shortId = (value) =>
    typeof value === "string" && value.length > 0 ? value.slice(0, 12) : undefined;
  const safeCode = (error) => {
    const candidates = [error?.code, error?.error?.code, error?.data?.code];
    const code = candidates.find(
      (value) => typeof value === "string" && /^[A-Z0-9_]{1,96}$/u.test(value),
    );
    if (code !== undefined) return code;
    const message =
      typeof error === "string" ? error : (error?.message ?? error?.error?.message ?? "");
    if (/invalid args|missing (?:required )?(?:key|field)/iu.test(message))
      return "ARGUMENT_DESERIALIZATION";
    return "NATIVE_INVOKE_REJECTED";
  };
  const wrapped = function projectBindingObservedInvoke(command, args, ...rest) {
    if (!commands.has(command)) return original.call(this, command, args, ...rest);
    const startedAt = Date.now();
    const record = {
      command,
      workspaceId: shortId(args?.input?.workspaceId ?? args?.workspaceId),
      startedAt,
      phase: "pending",
    };
    records.push(record);
    if (records.length > 500) records.splice(0, records.length - 500);
    persist();
    const finish = (phase, error, result) => {
      record.phase = phase;
      record.durationMs = Math.max(0, Date.now() - startedAt);
      if (error !== undefined) record.errorCode = safeCode(error);
      if (command === "ja_runtime_workspace_open" && typeof result?.workspaceId === "string") {
        record.resultWorkspaceId = shortId(result.workspaceId);
      }
      persist();
    };
    try {
      return Promise.resolve(original.call(this, command, args, ...rest)).then(
        (result) => {
          finish("fulfilled", undefined, result);
          return result;
        },
        (error) => {
          finish("rejected", error);
          throw error;
        },
      );
    } catch (error) {
      finish("rejected", error);
      throw error;
    }
  };
  try {
    internals.invoke = wrapped;
    if (internals.invoke !== wrapped) {
      const descriptor = Object.getOwnPropertyDescriptor(internals, "invoke");
      if (descriptor?.configurable !== true) return { installed: false, reason: "invoke_readonly" };
      Object.defineProperty(internals, "invoke", { ...descriptor, value: wrapped });
    }
    Object.defineProperty(internals, "__jaProjectBindingInvokeProbe", { value: true });
  } catch {
    return { installed: false, reason: "invoke_readonly" };
  }
  persist();
  return { installed: true, reused: false };
}

/** @author kongweiguang 只读取可见 UI 和 Runtime 的稳定投影；8 秒采样用于辨别调用重试与身份抖动。 */
export async function sampleProjectBindingState(
  page,
  durationMs = 8_000,
  intervalMs = 100,
  displayName = "项目目录 E2E 诊断",
) {
  assert.ok(Number.isSafeInteger(durationMs) && durationMs >= 1 && durationMs <= 10_000);
  assert.ok(Number.isSafeInteger(intervalMs) && intervalMs >= 50 && intervalMs <= 1_000);
  const startedAt = Date.now();
  const samples = [];
  while (Date.now() - startedAt < durationMs) {
    const sampleStartedAt = Date.now();
    const state = await page
      .evaluate(async (targetName) => {
        const rows = [
          ...document.querySelectorAll('[aria-label="项目列表"] button[data-scope-kind="project"]'),
        ];
        const target = rows.find((node) => node.textContent?.includes(targetName));
        const selected = rows.find((node) => node.getAttribute("aria-current") === "page");
        const targetBounds = target?.getBoundingClientRect();
        const rowNodeIds = (globalThis.__JA_E2E_PROJECT_ROW_IDS ??= {
          values: new WeakMap(),
          next: 1,
        }).values;
        const targetNodeId =
          target === undefined
            ? null
            : (rowNodeIds.get(target) ??
              (() => {
                const registry = globalThis.__JA_E2E_PROJECT_ROW_IDS;
                const id = registry.next++;
                rowNodeIds.set(target, id);
                return id;
              })());
        const activeThread = document.querySelector(
          '[aria-label="最近对话列表"] button[aria-current="page"][data-thread-id]',
        );
        const bodyText = document.body.innerText;
        const unavailableNotices = [
          ...document.querySelectorAll(".ja-notification-content"),
        ].filter((node) => node.textContent?.includes("项目目录不可用"));
        const unavailableNotice = unavailableNotices[0];
        if (unavailableNotice !== undefined && !rowNodeIds.has(unavailableNotice)) {
          rowNodeIds.set(unavailableNotice, globalThis.__JA_E2E_PROJECT_ROW_IDS.next++);
        }
        const dom = {
          shellReady: document.querySelector(".ja-shell")?.getAttribute("data-app-ready") ?? null,
          targetPresent: target !== undefined,
          targetConnected: target?.isConnected ?? false,
          targetNodeId,
          targetVisible:
            targetBounds !== undefined &&
            targetBounds.width > 0 &&
            targetBounds.height > 0 &&
            window.getComputedStyle(target).visibility !== "hidden",
          targetDisabled: target?.disabled ?? null,
          targetCurrent: target?.getAttribute("aria-current") === "page",
          targetActive: target?.getAttribute("data-active") === "true",
          projectRowCount: rows.length,
          selectedProjectMatchesTarget: selected?.textContent?.includes(targetName) ?? false,
          selectedThreadIdPrefix:
            activeThread?.getAttribute("data-thread-id")?.slice(0, 12) ?? null,
          visibleAlertCount: [...document.querySelectorAll('[role="alert"]')].filter(
            (node) => node.getBoundingClientRect().width > 0,
          ).length,
          unavailableVisible: bodyText.includes("项目目录不可用"),
          unavailableCount: unavailableNotices.length,
          unavailableNodeId:
            unavailableNotice === undefined ? null : rowNodeIds.get(unavailableNotice),
        };
        try {
          const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
          if (typeof invoke !== "function")
            return { dom, runtime: { errorCode: "INVOKE_UNAVAILABLE" } };
          const result = await Promise.race([
            invoke("ja_runtime_state", {}),
            new Promise((resolvePromise) =>
              setTimeout(() => resolvePromise({ __timeout: true }), 750),
            ),
          ]);
          if (result?.__timeout === true)
            return { dom, runtime: { errorCode: "RUNTIME_STATE_TIMEOUT" } };
          return {
            dom,
            runtime: {
              status: typeof result?.status === "string" ? result.status : "invalid",
              generation: Number.isSafeInteger(result?.generation) ? result.generation : null,
              serverInstanceId:
                typeof result?.serverInstanceId === "string" ? result.serverInstanceId : null,
            },
          };
        } catch (error) {
          const code = error?.code ?? error?.error?.code;
          return {
            dom,
            runtime: {
              errorCode:
                typeof code === "string" && /^[A-Z0-9_]{1,96}$/u.test(code)
                  ? code
                  : "RUNTIME_STATE_REJECTED",
            },
          };
        }
      }, displayName)
      .catch(() => ({
        dom: { unavailable: true },
        runtime: { errorCode: "PAGE_EVALUATE_FAILED" },
      }));
    samples.push({ atMs: sampleStartedAt - startedAt, ...state });
    const delayMs = intervalMs - (Date.now() - sampleStartedAt);
    if (delayMs > 0) await new Promise((resolvePromise) => setTimeout(resolvePromise, delayMs));
  }
  return { durationMs: Date.now() - startedAt, samples };
}

/** @author kongweiguang 聚合直接 invoke trace 但只输出次数和脱敏失败项，便于把瞬态循环归因到命令。 */
export function summarizeProjectBindingInvokes(records) {
  const counts = {};
  for (const record of records) {
    const count = (counts[record.command] ??= { pending: 0, fulfilled: 0, rejected: 0 });
    if (record.phase in count) count[record.phase] += 1;
  }
  return {
    counts,
    rejected: records
      .filter((record) => record.phase === "rejected")
      .map(({ command, workspaceId, errorCode, durationMs }) => ({
        command,
        workspaceId,
        errorCode,
        durationMs,
      })),
    pending: records
      .filter((record) => record.phase === "pending")
      .map(({ command, workspaceId, startedAt }) => ({ command, workspaceId, startedAt })),
  };
}

/** 以持久 Thread identity 选择会话，保证重启后的历史内容不是相似标题替代。 */
async function selectThread(page, threadId, deadline) {
  const thread = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await thread.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  if ((await thread.getAttribute("aria-current")) !== "page")
    await thread.click({ timeout: Math.max(1, deadline - Date.now()) });
  await page.waitForFunction(
    (expected) =>
      document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  return thread;
}

/** 读取 App Server 权威 Workspace 投影；用于证明 UI 点击与草稿字段确实提交到原身份。 */
async function readWorkspace(page, workspaceId) {
  return page.evaluate(async (id) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const value = await createHistoryAdapter().workspaceRead({ workspaceId: id });
    return {
      workspaceId: value.workspaceId,
      root: value.root,
      displayName: value.displayName,
      revision: value.revision,
      kind: value.kind,
    };
  }, workspaceId);
}

/** @author kongweiguang 繁忙摘要描述实际阻塞会话，使用数据库目录回读当前标题，不能拿项目名称代替。 */
async function readBusyThreadTitle(page, workspaceId, threadId) {
  const title = await page.evaluate(
    async ({ workspaceId, threadId }) => {
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      const result = await createHistoryAdapter().threadList({ workspaceId, limit: 100 });
      return result.items.find((thread) => thread.threadId === threadId)?.title;
    },
    { workspaceId, threadId },
  );
  assert.ok(typeof title === "string" && title.trim().length > 0, "busy Thread title must exist");
  return title;
}

/** @author kongweiguang 检查可操作的会话摘要，避免非空通用错误或项目名称误判为真实繁忙反馈。 */
export function isExpectedWorkspaceBusySummary(message, threadTitle) {
  return (
    typeof message === "string" &&
    typeof threadTitle === "string" &&
    threadTitle.length > 0 &&
    message.startsWith("暂时无法更改项目目录：") &&
    message.includes(threadTitle) &&
    message.includes("运行中")
  );
}

/** @author kongweiguang 短诊断先保存失败证据再拒绝交付，防止 diagnostic_complete 或 exit 0 被当作验收通过。 */
export function validateWorkspaceUpdateDiagnostic(report) {
  for (const [section, fields] of Object.entries({
    fixture: ["workspaceReadProbePositiveControl", "providerHeld"],
    busyDirectoryUpdate: [
      "dialogVisible",
      "attempted",
      "alertVerified",
      "expectedBusySummaryObserved",
      "directoryUnchanged",
    ],
    nameOnlyUpdate: [
      "savedWhileProviderHeld",
      "workspaceIdentityPreserved",
      "directoryPreserved",
      "displayNameChanged",
      "nativeUpdateResolved",
    ],
    dialogLayout: [
      "cancelled",
      "dialogFitsNarrowViewport",
      "longPathDialogLayoutVerified",
      "longPathDialogWideLayoutVerified",
      "escapeRestoresFocusToProject",
      "escapeRestoresToVisibleFallbackNarrow",
    ],
  })) {
    for (const field of fields) {
      assert.equal(report?.[section]?.[field], true, `${section}.${field} must be true`);
    }
  }
  const sample = report.bindingSample;
  assert.ok(sample.sampleCount > 0);
  for (const field of ["targetPresentCount", "targetVisibleCount", "targetCurrentCount"]) {
    assert.equal(sample[field], sample.sampleCount, `bindingSample.${field} must stay stable`);
  }
  assert.equal(sample.targetDisabledCount, 0);
  assert.equal(sample.distinctTargetNodeIds, 1);
  assert.equal(sample.workspaceRootStable, true);
  assert.equal(sample.workspaceIdentityStable, true);
  assert.deepEqual(report.consoleErrors, []);
  return report;
}

/** 让隔离 Provider 为真实用户输入返回固定文本，再从 Timeline 完成态读取持久历史证据。 */
async function sendPromptAndWait(page, prompt, marker, deadline) {
  const input = page.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  const answer = page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .filter({ hasText: marker })
    .last();
  await answer.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  return answer;
}

/** 打开目标项目上下文菜单并确认完整且顺序稳定的四项；键盘菜单关闭后必须恢复原行焦点。 */
async function verifyProjectMenu(
  page,
  project,
  displayName,
  evidenceDirectory,
  deadline,
  theme = "light",
) {
  const screenshots = [];
  const openMenu = async (input) => {
    if (input === "keyboard") {
      await project.focus({ timeout: interactionTimeout(deadline) });
      await project.press("ContextMenu", { timeout: interactionTimeout(deadline) });
    } else {
      await project.click({ button: "right", timeout: interactionTimeout(deadline) });
    }
    const menu = page.getByRole("menu", { name: `项目操作：${displayName}`, exact: true });
    await menu.waitFor({ state: "visible", timeout: interactionTimeout(deadline, 15_000) });
    const labels = await menu.getByRole("menuitem").allTextContents();
    assert.deepEqual(
      labels.map((label) => label.trim()),
      MENU_ITEMS,
    );
    return menu;
  };
  let menu = await openMenu("mouse");
  const screenshotName = `project-menu-${theme}.png`;
  await page.screenshot({
    path: join(evidenceDirectory, screenshotName),
    animations: "disabled",
  });
  screenshots.push(screenshotName);
  console.log(`[Ja project E2E] ${theme}: mouse menu captured`);
  await page.keyboard.press("Escape", { timeout: interactionTimeout(deadline) });
  await menu.waitFor({ state: "detached", timeout: interactionTimeout(deadline, 15_000) });

  menu = await openMenu("keyboard");
  console.log(`[Ja project E2E] ${theme}: keyboard menu opened`);
  await page.keyboard.press("Escape", { timeout: interactionTimeout(deadline) });
  await menu.waitFor({ state: "detached", timeout: interactionTimeout(deadline, 15_000) });
  const projectHandle = await project.elementHandle();
  assert.ok(projectHandle !== null, "context menu trigger must remain mounted after close");
  await page.waitForFunction((trigger) => document.activeElement === trigger, projectHandle, {
    timeout: interactionTimeout(deadline, 5_000),
    polling: "raf",
  });
  const escapeRestoresFocus = await project.evaluate((node) => document.activeElement === node);
  assert.equal(escapeRestoresFocus, true);
  return {
    menuItemCount: MENU_ITEMS.length,
    menuItemLabels: [...MENU_ITEMS],
    screenshots,
    escapeRestoresFocus,
  };
}

/** 主题仅通过 Ja 设置页面切换；真窗验收不直接篡改 localStorage 或文档主题属性。 */
async function setThemeMode(page, mode, deadline) {
  await page.getByRole("button", { name: "设置", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await page.getByRole("tab", { name: "外观", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await page.getByRole("combobox", { name: "外观模式", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await page
    .getByRole("option", { name: mode === "light" ? "浅色" : "深色", exact: true })
    .click({ timeout: Math.max(1, deadline - Date.now()) });
  await page.waitForFunction(
    (expected) => document.documentElement.dataset.theme === expected,
    mode,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  await page.getByRole("button", { name: "返回应用", exact: true }).click({
    timeout: Math.max(1, deadline - Date.now()),
  });
  await waitForApplication(page, deadline);
}

/** @author kongweiguang 以真实 Dialog 验证窄窗长路径布局，同时取消草稿以保持数据库投影不变。 */
async function cancelProjectDraft(page, project, workspace, evidenceDirectory, deadline) {
  const originalViewport = await page.evaluate(() => ({
    width: window.innerWidth,
    height: window.innerHeight,
  }));
  assert.ok(
    originalViewport.width > 0 && originalViewport.height > 0,
    "WebView2 runner must expose a measurable viewport",
  );
  let dialogFitsNarrowViewport = false;
  let longPathDialogLayoutVerified = false;
  let longPathDialogWideLayoutVerified = false;
  let escapeRestoresFocusToProject = false;
  let escapeRestoresToVisibleFallbackNarrow = false;
  const narrowViewport = {
    width: Math.min(520, originalViewport.width),
    height: Math.min(620, originalViewport.height),
  };
  try {
    for (const theme of ["light", "dark"]) {
      // 先在宽布局完成菜单和主题操作，再保持真实 Dialog 打开来测试窄窗重排。
      await page.setViewportSize(originalViewport);
      await setThemeMode(page, theme, Math.min(deadline, Date.now() + 30_000));
      await project.click({ button: "right", timeout: interactionTimeout(deadline, 30_000) });
      const menu = page.getByRole("menu", {
        name: `项目操作：${workspace.displayName}`,
        exact: true,
      });
      await menu.waitFor({ state: "visible", timeout: interactionTimeout(deadline, 30_000) });
      await menu.getByRole("menuitem", { name: "编辑项目…", exact: true }).click({
        timeout: interactionTimeout(deadline, 30_000),
      });
      const dialog = page.getByRole("dialog", { name: "编辑项目", exact: true });
      await dialog.waitFor({ state: "visible", timeout: interactionTimeout(deadline, 30_000) });
      await dialog
        .getByRole("textbox", { name: "项目名称", exact: true })
        .fill("取消后不应保存的名称");
      const pathInput = dialog.getByRole("textbox", { name: "项目目录", exact: true });
      await pathInput.fill(LONG_PATH_DRAFT);
      assert.equal(await pathInput.inputValue(), LONG_PATH_DRAFT);
      await waitForDirectoryNoticeToLeaveDialog(page, deadline);

      const wideBounds = await dialog.boundingBox();
      assert.ok(wideBounds !== null);
      const wideViewport = await page.evaluate(() => ({
        width: window.innerWidth,
        height: window.innerHeight,
      }));
      const wideLayout = await dialog.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        const title = element.querySelector("h2")?.getBoundingClientRect();
        const finalAction = element.querySelector(".ja-project-edit-actions button:last-child");
        const action = finalAction?.getBoundingClientRect();
        return {
          dialogLeft: rect.left,
          dialogTop: rect.top,
          dialogRight: rect.right,
          dialogBottom: rect.bottom,
          titleLeft: title?.left ?? rect.left,
          titleTop: title?.top ?? rect.top,
          actionRight: action?.right ?? rect.right,
          actionBottom: action?.bottom ?? rect.bottom,
          dialogScrollWidth: element.scrollWidth,
          documentScrollWidth: document.documentElement.scrollWidth,
          viewportWidth: window.innerWidth,
          viewportHeight: window.innerHeight,
        };
      });

      const wideDialogFitsViewport =
        wideBounds.x >= 0 &&
        wideBounds.y >= 0 &&
        wideBounds.x + wideBounds.width <= wideViewport.width &&
        wideBounds.y + wideBounds.height <= wideViewport.height;
      assert.equal(wideDialogFitsViewport, true, "project editor must fit the full viewport");
      assert.ok(
        wideLayout.titleLeft - wideLayout.dialogLeft >= 20,
        "wide dialog title must have visible left inset",
      );
      assert.ok(
        wideLayout.actionRight <= wideLayout.dialogRight - 20,
        "wide dialog actions must have right inset",
      );
      assert.ok(wideLayout.dialogScrollWidth <= wideBounds.width);
      assert.ok(wideLayout.documentScrollWidth <= wideLayout.viewportWidth);
      longPathDialogWideLayoutVerified = true;
      await page.screenshot({
        path: join(evidenceDirectory, `project-edit-wide-long-path-${theme}.png`),
        animations: "disabled",
      });
      await dialog.screenshot({
        path: join(evidenceDirectory, `project-edit-dialog-wide-long-path-${theme}.png`),
        animations: "disabled",
      });

      await page.setViewportSize(narrowViewport);
      const bounds = await dialog.boundingBox();
      assert.ok(bounds !== null);
      await waitForDirectoryNoticeToLeaveDialog(page, deadline);
      const viewport = await page.evaluate(() => ({
        width: window.innerWidth,
        height: window.innerHeight,
      }));
      const layout = await dialog.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        const title = element.querySelector("h2")?.getBoundingClientRect();
        const finalAction = element.querySelector(".ja-project-edit-actions button:last-child");
        const action = finalAction?.getBoundingClientRect();
        return {
          dialogLeft: rect.left,
          dialogTop: rect.top,
          dialogRight: rect.right,
          dialogBottom: rect.bottom,
          titleLeft: title?.left ?? rect.left,
          titleTop: title?.top ?? rect.top,
          actionRight: action?.right ?? rect.right,
          actionBottom: action?.bottom ?? rect.bottom,
          dialogScrollWidth: element.scrollWidth,
          documentScrollWidth: document.documentElement.scrollWidth,
          viewportWidth: window.innerWidth,
        };
      });
      dialogFitsNarrowViewport =
        bounds.x >= 0 &&
        bounds.y >= 0 &&
        bounds.x + bounds.width <= viewport.width &&
        bounds.y + bounds.height <= viewport.height;
      assert.equal(
        dialogFitsNarrowViewport,
        true,
        "project editor must fit a narrow WebView viewport",
      );
      assert.ok(
        layout.titleLeft - layout.dialogLeft >= 20,
        "dialog title must have visible left inset",
      );
      assert.ok(
        layout.actionRight <= layout.dialogRight - 20,
        "dialog actions must have right inset",
      );
      assert.ok(
        layout.titleTop - layout.dialogTop >= 20,
        "dialog title must have visible top inset",
      );
      assert.ok(
        layout.dialogBottom - layout.actionBottom >= 20,
        "dialog actions must have bottom inset",
      );
      assert.ok(layout.dialogScrollWidth <= bounds.width, "long path must not overflow the dialog");
      assert.ok(
        layout.documentScrollWidth <= layout.viewportWidth,
        "project editor must not introduce horizontal viewport overflow",
      );
      longPathDialogLayoutVerified = true;
      await page.screenshot({
        path: join(evidenceDirectory, `project-edit-narrow-long-path-${theme}.png`),
        animations: "disabled",
      });
      if (theme === "light") {
        const narrowFocusTarget = await page.evaluate(() => {
          const isVisible = (element) => {
            if (!(element instanceof HTMLElement)) return false;
            const rect = element.getBoundingClientRect();
            return (
              rect.width > 0 &&
              rect.height > 0 &&
              rect.right > 0 &&
              rect.left < window.innerWidth &&
              rect.bottom > 0 &&
              rect.top < window.innerHeight &&
              window.getComputedStyle(element).visibility !== "hidden"
            );
          };
          const selectedRow = document.querySelector(
            '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
          );
          if (isVisible(selectedRow)) return "project-row";
          const sidebarToggle = document.querySelector('button[aria-label="显示侧边栏"]');
          if (isVisible(sidebarToggle)) return "sidebar-toggle";
          const composer = document.querySelector(
            '[role="textbox"][aria-label="消息"], textarea[aria-label="消息"]',
          );
          return isVisible(composer) ? "composer" : null;
        });
        assert.ok(narrowFocusTarget, "narrow layout must expose a safe focus restoration target");
        await page.keyboard.press("Escape");
        await dialog.waitFor({ state: "detached", timeout: interactionTimeout(deadline, 30_000) });
        const restoredNarrowTarget = await page.waitForFunction(
          (kind) => {
            const selectedRow = document.querySelector(
              '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
            );
            const sidebarToggle = document.querySelector('button[aria-label="显示侧边栏"]');
            const composer = document.querySelector(
              '[role="textbox"][aria-label="消息"], textarea[aria-label="消息"]',
            );
            const target =
              kind === "project-row"
                ? selectedRow
                : kind === "sidebar-toggle"
                  ? sidebarToggle
                  : composer;
            return target !== null && document.activeElement === target;
          },
          narrowFocusTarget,
          { timeout: interactionTimeout(deadline, 15_000) },
        );
        escapeRestoresToVisibleFallbackNarrow = (await restoredNarrowTarget.jsonValue()) === true;
        await restoredNarrowTarget.dispose();
        await page.setViewportSize(originalViewport);
      } else {
        await page.setViewportSize(originalViewport);
        await page.keyboard.press("Escape");
        await dialog.waitFor({ state: "detached", timeout: interactionTimeout(deadline, 30_000) });
        await page.waitForFunction(
          (name) => {
            const row = [
              ...document.querySelectorAll(
                '[aria-label="项目列表"] button[data-scope-kind="project"]',
              ),
            ].find(
              (candidate) =>
                candidate.getAttribute("aria-current") === "page" &&
                candidate.textContent?.includes(name),
            );
            const rect = row?.getBoundingClientRect();
            return (
              row !== undefined &&
              !row.disabled &&
              rect !== undefined &&
              rect.width > 0 &&
              rect.height > 0 &&
              document.activeElement === row
            );
          },
          workspace.displayName,
          { timeout: interactionTimeout(deadline, 15_000) },
        );
        escapeRestoresFocusToProject = true;
      }
    }
    const actual = await readWorkspace(page, workspace.workspaceId);
    assert.equal(actual.displayName, workspace.displayName);
    assert.equal(actual.root, workspace.root);
    await page.setViewportSize(originalViewport);
    await setThemeMode(page, "light", Math.min(deadline, Date.now() + 30_000));
    return {
      cancelled: true,
      dialogFitsNarrowViewport,
      longPathDialogLayoutVerified,
      longPathDialogWideLayoutVerified,
      directoryNoticeHiddenDuringEdit: true,
      escapeRestoresFocusToProject,
      escapeRestoresToVisibleFallbackNarrow,
    };
  } finally {
    await page.setViewportSize(originalViewport);
  }
}

/** 通过 Dialog 发起目录更新；失败需留在原 Dialog 且可见具体错误，成功则等待关闭回执。 */
async function editProject(page, project, currentName, { name, path }, deadline) {
  const timeout = interactionTimeout(deadline, 30_000);
  await project.click({ button: "right", timeout });
  const menu = page.getByRole("menu", { name: `项目操作：${currentName}`, exact: true });
  await menu.waitFor({ state: "visible", timeout });
  await menu.getByRole("menuitem", { name: "编辑项目…", exact: true }).click({ timeout });
  const dialog = page.getByRole("dialog", { name: "编辑项目", exact: true });
  await dialog.waitFor({ state: "visible", timeout });
  if (name !== undefined)
    await dialog.getByRole("textbox", { name: "项目名称", exact: true }).fill(name);
  if (path !== undefined)
    await dialog.getByRole("textbox", { name: "项目目录", exact: true }).fill(path);
  await dialog.getByRole("button", { name: "保存", exact: true }).click({
    timeout,
  });
  return dialog;
}

/** @author kongweiguang 文件 capability 只由真实 Workbench Tab 菜单激活，不能通过 test-only state 绕过入口。 */
async function openProjectWorkbenchCapability(page, key, label, deadline) {
  const inspector = page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
  if ((await inspector.getAttribute("data-visible")) !== "true") {
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click({
      timeout: interactionTimeout(deadline),
    });
  }
  const workbench = inspector.locator(".ja-workbench:visible");
  await workbench.waitFor({ state: "visible", timeout: interactionTimeout(deadline) });
  let tab = workbench.locator(`[data-workbench-tab="${key}"]`);
  if ((await tab.count()) === 0) {
    await workbench.getByRole("button", { name: "新建标签页", exact: true }).click({
      timeout: interactionTimeout(deadline),
    });
    await page
      .getByRole("menuitem")
      .filter({ hasText: label })
      .first()
      .click({ timeout: interactionTimeout(deadline) });
    tab = workbench.locator(`[data-workbench-tab="${key}"]`);
  }
  await tab.waitFor({ state: "visible", timeout: interactionTimeout(deadline) });
  await tab.click({ timeout: interactionTimeout(deadline) });
  return { inspector, workbench, tab };
}

/** @author kongweiguang 收起右栏后等 Thread Host 失活，后续零扫描断言不会把隐藏窗口误当成已收起。 */
async function hideProjectWorkbench(page, inspector, deadline) {
  if ((await inspector.getAttribute("data-visible")) === "true") {
    await page.getByRole("button", { name: "收起右侧栏", exact: true }).click({
      timeout: interactionTimeout(deadline),
    });
  }
  await page.waitForFunction(
    () =>
      document
        .querySelector(
          '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
        )
        ?.getAttribute("data-visible") !== "true",
    undefined,
    { timeout: interactionTimeout(deadline), polling: 100 },
  );
}

/** @author kongweiguang 编辑隔离磁盘文件并以真实保存失败制造 dirty 文档，避免靠 React 私有状态造草稿。 */
async function openAndDirtyProjectFile(page, relativePath, draft, deadline) {
  const { workbench } = await openProjectWorkbenchCapability(page, "files", "文件", deadline);
  const files = workbench.locator('[aria-label="文件工作区"]');
  const entry = files.locator(`.ja-file-tree-host [data-path="${relativePath}"]`);
  await entry.waitFor({ state: "visible", timeout: interactionTimeout(deadline) });
  await entry.dblclick({ timeout: interactionTimeout(deadline) });
  const editor = files.locator('.cm-content[contenteditable="true"]');
  await editor.waitFor({ state: "visible", timeout: interactionTimeout(deadline) });
  await editor.fill(draft);
  await files.locator('.ja-files-editor-status[data-status="saveError"]').waitFor({
    state: "visible",
    timeout: interactionTimeout(deadline),
  });
  assert.ok((await editor.innerText()).includes(draft.trim()));
  return { workbench, files, editor };
}

/** @author kongweiguang 只拦截指定 fixture 文件的 Native CAS 保存；其它 Workspace 请求照常下沉到真实 Tauri。 */
async function installProjectFileSaveFault(page, relativePath) {
  return page.evaluate((path) => {
    const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const state = { blocked: true, failed: 0, saved: 0, path };
    globalThis.__JA_PROJECT_FILE_SAVE_FAULT__ = state;
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
      if (
        request?.command !== "ja_workspace_save_file" ||
        request?.args?.input?.relativePath !== state.path
      )
        return previous === undefined ? delegate() : previous(request, delegate);
      if (state.blocked) {
        state.failed += 1;
        const rejectNativeSave = async () => {
          throw Object.assign(new Error("isolated E2E save fault"), { code: "WORKSPACE_IO_ERROR" });
        };
        return previous === undefined ? rejectNativeSave() : previous(request, rejectNativeSave);
      }
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      state.saved += 1;
      return result;
    };
    state.restore = () => {
      globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = previous;
      delete globalThis.__JA_PROJECT_FILE_SAVE_FAULT__;
    };
    return { installed: true, relativePath: path };
  }, relativePath);
}

/** @author kongweiguang 在同一真实项目顺序验证取消保稿、保存旧 root、放弃草稿三条重绑分支。 */
async function exerciseProjectRebindDirtyDocuments(
  page,
  projectRow,
  projectWorkspace,
  fixture,
  deadline,
  evidenceDirectory,
) {
  const savePath = "项目重绑保存.txt";
  const saveDraft = "重绑前已安全保存到旧目录。\n";
  const saveFault = await installProjectFileSaveFault(page, savePath);
  assert.equal(saveFault.installed, true);
  let saveEditor;
  let saveEditDialog;
  let saveState = { failed: 0, saved: 0 };
  try {
    ({ editor: saveEditor } = await openAndDirtyProjectFile(page, savePath, saveDraft, deadline));
    assert.equal(
      await readFile(join(fixture.originalRoot, savePath), "utf8"),
      "保存前的原目录内容。\n",
    );
    saveEditDialog = await editProject(
      page,
      projectRow,
      projectWorkspace.displayName,
      { path: fixture.movedRoot },
      deadline,
    );
    let rebindDialog = page.getByRole("dialog", { name: "处理未保存文件", exact: true });
    await rebindDialog.waitFor({ state: "visible", timeout: interactionTimeout(deadline) });
    await page.screenshot({
      path: join(evidenceDirectory, "project-rebind-unsaved-cancel.png"),
      animations: "disabled",
    });
    await rebindDialog.getByRole("button", { name: "取消", exact: true }).click();
    await rebindDialog.waitFor({ state: "detached", timeout: interactionTimeout(deadline) });
    assert.equal(
      await saveEditDialog.isVisible(),
      true,
      "cancel must keep the path draft in the project editor",
    );
    assert.ok(
      (await saveEditor.innerText()).includes(saveDraft.trim()),
      "cancel must retain editor draft",
    );
    assert.equal(
      await readFile(join(fixture.originalRoot, savePath), "utf8"),
      "保存前的原目录内容。\n",
    );
    assert.equal(
      samePath(
        (await readWorkspace(page, projectWorkspace.workspaceId)).root,
        fixture.originalRoot,
      ),
      true,
    );
    await page.screenshot({
      path: join(evidenceDirectory, "project-rebind-unsaved-cancel-retained.png"),
      animations: "disabled",
    });

    await saveEditDialog
      .getByRole("button", { name: "保存", exact: true })
      .click({ timeout: interactionTimeout(deadline) });
    rebindDialog = page.getByRole("dialog", { name: "处理未保存文件", exact: true });
    await rebindDialog.waitFor({ state: "visible", timeout: interactionTimeout(deadline) });
    await page.evaluate(() => {
      globalThis.__JA_PROJECT_FILE_SAVE_FAULT__.blocked = false;
    });
    await rebindDialog
      .getByRole("button", { name: "保存并继续", exact: true })
      .click({ timeout: interactionTimeout(deadline) });
    await rebindDialog.waitFor({ state: "detached", timeout: interactionTimeout(deadline) });
    await saveEditDialog.waitFor({ state: "detached", timeout: interactionTimeout(deadline) });
    const savedWorkspace = await readWorkspace(page, projectWorkspace.workspaceId);
    assert.equal(samePath(savedWorkspace.root, fixture.movedRoot), true);
    assert.equal(await readFile(join(fixture.originalRoot, savePath), "utf8"), saveDraft);
    assert.equal(await readFile(join(fixture.movedRoot, savePath), "utf8"), "");
    saveState = await page.evaluate(() => ({
      failed: globalThis.__JA_PROJECT_FILE_SAVE_FAULT__?.failed ?? 0,
      saved: globalThis.__JA_PROJECT_FILE_SAVE_FAULT__?.saved ?? 0,
    }));
    assert.ok(saveState.failed > 0, "dirty fixture must have had a real failed native save");
    assert.ok(saveState.saved > 0, "save-and-rebind must commit through the native save command");
    await page.evaluate(async (workspaceId) => {
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      await createHistoryAdapter().workspaceSetTrust({ workspaceId, trust: "trusted" });
    }, projectWorkspace.workspaceId);
  } finally {
    await page
      .evaluate(() => globalThis.__JA_PROJECT_FILE_SAVE_FAULT__?.restore?.())
      .catch(() => undefined);
  }

  const discardPath = "项目重绑放弃.txt";
  const discardDraft = "这份草稿必须被放弃。\n";
  const discardFault = await installProjectFileSaveFault(page, discardPath);
  assert.equal(discardFault.installed, true);
  try {
    await openAndDirtyProjectFile(page, discardPath, discardDraft, deadline);
    assert.equal(
      await readFile(join(fixture.movedRoot, discardPath), "utf8"),
      "放弃前的搬迁目录内容。\n",
    );
    const discardEditDialog = await editProject(
      page,
      projectRow,
      projectWorkspace.displayName,
      { path: fixture.originalRoot },
      deadline,
    );
    const rebindDialog = page.getByRole("dialog", { name: "处理未保存文件", exact: true });
    await rebindDialog.waitFor({ state: "visible", timeout: interactionTimeout(deadline) });
    assert.ok((await rebindDialog.innerText()).includes(discardPath));
    await page.screenshot({
      path: join(evidenceDirectory, "project-rebind-unsaved-discard.png"),
      animations: "disabled",
    });
    await rebindDialog
      .getByRole("button", { name: "放弃修改", exact: true })
      .click({ timeout: interactionTimeout(deadline) });
    await rebindDialog.waitFor({ state: "detached", timeout: interactionTimeout(deadline) });
    await discardEditDialog.waitFor({ state: "detached", timeout: interactionTimeout(deadline) });
    const discardedWorkspace = await readWorkspace(page, projectWorkspace.workspaceId);
    assert.equal(samePath(discardedWorkspace.root, fixture.originalRoot), true);
    assert.equal(
      await readFile(join(fixture.movedRoot, discardPath), "utf8"),
      "放弃前的搬迁目录内容。\n",
    );
    const files = page.locator(
      '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"] .ja-workbench:visible [aria-label="文件工作区"]',
    );
    if ((await files.count()) > 0)
      assert.equal(await files.locator(`[data-file-tab-path="${discardPath}"]`).count(), 0);
    const state = await page.evaluate(() => ({
      failed: globalThis.__JA_PROJECT_FILE_SAVE_FAULT__?.failed ?? 0,
    }));
    assert.ok(
      state.failed > 0,
      "discard fixture must be dirty after an injected native save rejection",
    );
    await page.screenshot({
      path: join(evidenceDirectory, "project-rebind-unsaved-discard-closed.png"),
      animations: "disabled",
    });
    return {
      cancelRetainedDraftAndOldRoot: true,
      saveWroteOriginalDirectoryBeforeRebind: true,
      saveAttemptedNativeWrite: saveState.saved > 0,
      discardKeptDiskBaseline: true,
      discardClosedDirtyDocument: true,
      failedSaveAttempts: saveState.failed + state.failed,
      nativeSaveCommits: saveState.saved,
    };
  } finally {
    await page
      .evaluate(() => globalThis.__JA_PROJECT_FILE_SAVE_FAULT__?.restore?.())
      .catch(() => undefined);
  }
}

/** 等待 Runtime stop 回到稳定 stopped，并把 SQLite data 复制到外层临时夹具供下一次真实启动使用。 */
async function stopAndPreserveDatabase(page, isolatedRuntimeHome, stashDirectory, deadline) {
  const before = await page.evaluate(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    return new TauriRuntimeHostAdapter().state();
  });
  assert.equal(typeof before.serverInstanceId, "string");
  const stopped = await page.evaluate(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const runtime = new TauriRuntimeHostAdapter();
    await runtime.stop();
    return runtime.state();
  });
  assert.equal(stopped.status, "stopped");
  const sourceData = join(isolatedRuntimeHome, "data");
  const database = join(sourceData, "ja.db");
  await stat(database);
  await mkdir(stashDirectory, { recursive: true });
  await copyFile(database, join(stashDirectory, "ja.db"));
  for (const suffix of ["-wal"]) {
    const source = `${database}${suffix}`;
    try {
      await copyFile(source, join(stashDirectory, `ja.db${suffix}`));
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
  }
  assert.ok(Date.now() < deadline, "database preservation exceeded project acceptance deadline");
  return { serverInstanceId: before.serverInstanceId, stoppedGeneration: stopped.generation };
}

/** 第二个新 Tauri 进程从前一轮 clean stop 后复制的数据库启动，以页面事实证明重新加载身份与历史。 */
async function runPostRestart({ page, fixture, evidenceDirectory }) {
  const deadline = Date.now() + 5 * 60_000;
  const pageErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  page.on("console", (message) => {
    if (message.type() === "error") pageErrors.push(message.text().slice(0, 500));
  });
  await page.reload({ waitUntil: "domcontentloaded", timeout: Math.max(1, deadline - Date.now()) });
  await waitForApplication(page, deadline);
  const workspace = await readWorkspace(page, fixture.workspaceId);
  const after = await page.evaluate(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    return new TauriRuntimeHostAdapter().state();
  });
  assert.equal(workspace.workspaceId, fixture.workspaceId);
  assert.equal(workspace.displayName, RENAMED_PROJECT_NAME);
  assert.equal(samePath(workspace.root, fixture.movedRoot), true);
  const project = await selectProject(page, RENAMED_PROJECT_NAME, deadline);
  const thread = await selectThread(page, fixture.threadId, deadline);
  const answer = page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .filter({ hasText: INITIAL_HISTORY_MARKER })
    .last();
  await answer.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await page.screenshot({
    path: join(evidenceDirectory, "project-history-after-restart.png"),
    animations: "disabled",
  });
  assert.ok((await project.getAttribute("aria-current")) === "page");
  assert.ok((await thread.getAttribute("data-thread-id")) === fixture.threadId);
  return {
    workspace,
    threadId: fixture.threadId,
    historyVisible: (await answer.innerText()).includes(INITIAL_HISTORY_MARKER),
    serverInstanceId: after.serverInstanceId,
    screenshot: "project-history-after-restart.png",
    pageErrors,
  };
}

/** @author kongweiguang 将真窗隐藏但保持 WebView/CDP 可用，避免短诊断占用用户前台。 */
async function hideDiagnosticWindow(page) {
  return page.evaluate(async () => {
    try {
      const { getCurrentWindow } = await import("@tauri-apps/api/window");
      await getCurrentWindow().hide();
      return { hidden: true };
    } catch {
      return { hidden: false, errorCode: "WINDOW_HIDE_FAILED" };
    }
  });
}

/** @author kongweiguang 只建一组隔离 Workspace/Thread，reload 后点击一次真实项目行并采样状态。 */
async function runBindingDiagnostic({ page, providerBaseUrl, outerFixture, evidenceDirectory }) {
  const storageKey = "ja-project-binding-diagnostic-v1";
  const consoleErrors = [];
  page.on("pageerror", () => consoleErrors.push("pageerror"));
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push("console.error");
  });
  await mkdir(evidenceDirectory, { recursive: true });
  await page.reload({ waitUntil: "domcontentloaded", timeout: 20_000 });
  await waitForApplication(page, Date.now() + 30_000);
  const project = await createProjectAndThread(
    page,
    outerFixture.originalRoot,
    "项目目录 E2E 诊断",
    providerBaseUrl,
  );
  const probeOptions = { storageKey };
  const initialProbe = await page.evaluate(installDirectProjectManagementInvokeProbe, probeOptions);
  await page.addInitScript(installDirectProjectManagementInvokeProbe, probeOptions);
  const hiddenWindow = await hideDiagnosticWindow(page);
  await page.reload({ waitUntil: "domcontentloaded", timeout: 20_000 });

  const row = page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: project.displayName })
    .first();
  let selection = {
    targetVisible: false,
    enabled: false,
    clickAttempted: false,
    clickSucceeded: false,
  };
  try {
    await row.waitFor({ state: "visible", timeout: 12_000 });
    selection.targetVisible = true;
    selection.enabled = await row.isEnabled();
    if (selection.enabled) {
      selection.clickAttempted = true;
      try {
        await row.click({ timeout: 2_000 });
        selection.clickSucceeded = true;
      } catch (error) {
        selection.clickErrorKind = error?.name === "TimeoutError" ? "TIMEOUT" : "CLICK_REJECTED";
      }
    }
  } catch {
    selection.waitTimedOut = true;
  }

  const sampled = await sampleProjectBindingState(page, 8_000, 100);
  const invocationTrace = await page.evaluate((key) => {
    try {
      const parsed = JSON.parse(sessionStorage.getItem(key) ?? "[]");
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }, storageKey);
  const invokeSummary = summarizeProjectBindingInvokes(invocationTrace);
  const targetSelectionSamples = sampled.samples.filter(
    (sample) => sample.dom?.targetCurrent,
  ).length;
  const screenshot = "project-binding-diagnostic.png";
  await page
    .screenshot({ path: join(evidenceDirectory, screenshot), animations: "disabled" })
    .catch(() => {});
  const report = {
    contractVersion: 1,
    status: "diagnostic_complete",
    runtime: { platform: process.platform, surface: "tauri_webview2", boundary: "debug_jar" },
    isolation: { hiddenWindow: hiddenWindow.hidden, externalCalls: 0 },
    fixture: {
      workspaceId: project.workspaceId.slice(0, 12),
      threadId: project.threadId.slice(0, 12),
      createdUsingTypedHistoryApi: true,
    },
    probe: { initialInstall: initialProbe, records: invocationTrace.length, ...invokeSummary },
    selection: { ...selection, targetSelectedSamples: targetSelectionSamples },
    sampling: sampled,
    consoleErrorCount: consoleErrors.length,
    screenshot,
  };
  await writeFile(
    join(evidenceDirectory, "project-management-binding-diagnostic.json"),
    `${JSON.stringify(report, null, 2)}\n`,
    "utf8",
  );
  console.log(
    `[Ja project binding] diagnostic complete: selection=${JSON.stringify(selection)} samples=${sampled.samples.length} commands=${JSON.stringify(invokeSummary.counts)}`,
  );
  return report;
}

/** @author kongweiguang 在隔离真窗保持真实 Turn 在途，区分目录 Busy 拒绝与运行中纯改名能力。 */
async function runWorkspaceUpdateDiagnostic({
  page,
  providerBaseUrl,
  provider,
  outerFixture,
  evidenceDirectory,
}) {
  const deadline = Date.now() + 2 * 60_000;
  const consoleErrors = [];
  const safeConsoleText = (value) =>
    String(value)
      .replaceAll(outerFixture.originalRoot, "[fixture-path]")
      .replaceAll(outerFixture.movedRoot, "[fixture-path]")
      .replace(/(?:[A-Za-z]:\\|file:\/\/)[^\r\n]*/gu, "[local-path]")
      .split(/\r?\n/u)[0]
      .slice(0, 500);
  page.on("console", (message) => {
    if (message.type() !== "error" || consoleErrors.length >= 20) return;
    const location = message.location();
    consoleErrors.push({
      type: "console.error",
      text: safeConsoleText(message.text()),
      source: location.url.split(/[\\/]/u).at(-1) ?? "",
      line: location.lineNumber,
      column: location.columnNumber,
    });
  });
  page.on("pageerror", (error) => {
    if (consoleErrors.length >= 20) return;
    consoleErrors.push({ type: "pageerror", text: safeConsoleText(error?.message ?? error) });
  });
  await mkdir(evidenceDirectory, { recursive: true });
  await page.addInitScript(installProjectManagementInvokeProbe);
  await page.reload({
    waitUntil: "domcontentloaded",
    timeout: interactionTimeout(deadline, 20_000),
  });
  await waitForApplication(page, deadline);

  const primary = await createProjectAndThread(
    page,
    outerFixture.originalRoot,
    "项目目录 Busy 诊断",
    providerBaseUrl,
  );
  await page.reload({
    waitUntil: "domcontentloaded",
    timeout: interactionTimeout(deadline, 20_000),
  });
  await waitForApplication(page, deadline);
  const projectRow = await selectProject(page, primary.displayName, deadline);
  await selectThread(page, primary.threadId, deadline);
  const originalWorkspace = await readWorkspace(page, primary.workspaceId);
  const initialTrace = await projectManagementInvokeSnapshot(page);
  const workspaceReadProbePositiveControl = initialTrace.some(
    (event) =>
      event.command === "ja_runtime_workspace_read" &&
      event.phase === "resolved" &&
      event.workspaceId === primary.workspaceId.slice(0, 12),
  );
  assert.equal(
    workspaceReadProbePositiveControl,
    true,
    "nativeInvoke hook must capture a real Workspace read",
  );
  assert.equal(samePath(originalWorkspace.root, outerFixture.originalRoot), true);
  const bindingSamples = await sampleProjectBindingState(page, 8_000, 100, primary.displayName);
  const workspaceAfterSampling = await readWorkspace(page, primary.workspaceId);
  const bindingSampleSummary = {
    sampleCount: bindingSamples.samples.length,
    targetPresentCount: bindingSamples.samples.filter((sample) => sample.dom?.targetPresent).length,
    targetVisibleCount: bindingSamples.samples.filter((sample) => sample.dom?.targetVisible).length,
    targetDisabledCount: bindingSamples.samples.filter((sample) => sample.dom?.targetDisabled)
      .length,
    targetCurrentCount: bindingSamples.samples.filter((sample) => sample.dom?.targetCurrent).length,
    distinctTargetNodeIds: new Set(
      bindingSamples.samples.map((sample) => sample.dom?.targetNodeId).filter(Number.isSafeInteger),
    ).size,
    runtimeStatuses: [...new Set(bindingSamples.samples.map((sample) => sample.runtime?.status))],
    runtimeGenerations: [
      ...new Set(bindingSamples.samples.map((sample) => sample.runtime?.generation)),
    ],
    runtimeInstancePrefixes: [
      ...new Set(
        bindingSamples.samples
          .map((sample) => sample.runtime?.serverInstanceId?.slice(0, 12))
          .filter(Boolean),
      ),
    ],
    selectedThreadIdPrefix: primary.threadId.slice(0, 12),
    workspaceRootStable: samePath(workspaceAfterSampling.root, originalWorkspace.root),
    workspaceIdentityStable: workspaceAfterSampling.workspaceId === originalWorkspace.workspaceId,
  };
  await page.screenshot({
    path: join(evidenceDirectory, "project-management-workspace-update-binding-sample.png"),
    animations: "disabled",
  });
  await writeFile(
    join(evidenceDirectory, "project-management-workspace-update-binding-sample.json"),
    `${JSON.stringify({ summary: bindingSampleSummary, samples: bindingSamples, consoleErrors }, null, 2)}\n`,
    "utf8",
  );

  let dialogLayout = {
    cancelled: false,
    dialogFitsNarrowViewport: false,
    longPathDialogLayoutVerified: false,
    longPathDialogWideLayoutVerified: false,
    escapeRestoresFocusToProject: false,
  };
  let dialogLayoutError;
  try {
    dialogLayout = await cancelProjectDraft(
      page,
      projectRow,
      { ...primary, ...originalWorkspace },
      evidenceDirectory,
      deadline,
    );
  } catch (error) {
    dialogLayoutError = {
      kind: error?.name === "TimeoutError" ? "TIMEOUT" : "LAYOUT_ASSERTION_FAILED",
      message: safeConsoleText(error?.message ?? error),
    };
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const dialog = page.getByRole("dialog", { name: "编辑项目", exact: true });
      if (!(await dialog.isVisible().catch(() => false))) break;
      await page.keyboard.press("Escape").catch(() => undefined);
      await dialog.waitFor({ state: "detached", timeout: 5_000 }).catch(() => undefined);
    }
  }
  await writeFile(
    join(evidenceDirectory, "project-management-workspace-update-progress.json"),
    `${JSON.stringify(
      {
        phase: dialogLayoutError
          ? "project-editor-layout-failed"
          : "project-editor-layout-verified",
        ...dialogLayout,
        error: dialogLayoutError,
      },
      null,
      2,
    )}\n`,
    "utf8",
  );
  await sendPromptAndWait(page, INITIAL_HISTORY_MARKER, INITIAL_HISTORY_MARKER, deadline);
  const busyTraceBefore = await projectManagementInvokeSnapshot(page);
  await page.getByRole("textbox", { name: "消息", exact: true }).fill(BUSY_TURN_MARKER);
  await page.getByRole("button", { name: "发送", exact: true }).click({
    timeout: interactionTimeout(deadline),
  });
  const providerHeld = await provider.waitForBusyTurn(interactionTimeout(deadline, 30_000));
  assert.equal(providerHeld, true, "the deterministic Provider must hold the active Turn");
  const busyThreadTitle = await readBusyThreadTitle(page, primary.workspaceId, primary.threadId);
  const runtimeWhileBusy = await page.evaluate(async () => {
    const { TauriRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    return new TauriRuntimeHostAdapter().state();
  });

  const busyDialog = page.getByRole("dialog", { name: "编辑项目", exact: true });
  let busyAlertText = "";
  let busyDialogVisible = false;
  let busyUpdateUiErrorKind;
  try {
    await editProject(
      page,
      projectRow,
      primary.displayName,
      { path: outerFixture.movedRoot },
      deadline,
    );
    const busyAlert = busyDialog.getByRole("alert");
    await busyAlert.waitFor({ state: "visible", timeout: interactionTimeout(deadline, 30_000) });
    busyAlertText = (await busyAlert.innerText()).trim();
  } catch (error) {
    busyUpdateUiErrorKind = error?.name === "TimeoutError" ? "TIMEOUT" : "UI_ACTION_FAILED";
  }
  busyDialogVisible = await busyDialog.isVisible().catch(() => false);

  const busyTraceAfter = await projectManagementInvokeSnapshot(page);
  const busyUpdateRejected = busyTraceAfter
    .slice(busyTraceBefore.length)
    .findLast(
      (event) =>
        event.command === "ja_runtime_workspace_update" &&
        event.phase === "rejected" &&
        event.workspaceId === primary.workspaceId.slice(0, 12),
    );
  const busyFailureTuple = busyUpdateRejected?.failure ?? null;
  const busyUpdateReturnedExpectedSummary =
    busyFailureTuple?.errorCode === "WORKSPACE_BUSY" &&
    busyFailureTuple.runnerPhaseClassification === "busy_summary_shape" &&
    busyFailureTuple.retryable === true;
  const busyAlertVerified = isExpectedWorkspaceBusySummary(busyAlertText, busyThreadTitle);
  await page.screenshot({
    path: join(evidenceDirectory, "project-management-busy-update-diagnostic.png"),
    animations: "disabled",
  });
  const workspaceAfterBlockedUpdate = await readWorkspace(page, primary.workspaceId);
  const directoryUnchanged = samePath(workspaceAfterBlockedUpdate.root, originalWorkspace.root);

  const renamedProject = "运行中的改名验证";
  let nameOnlyDialog = busyDialog;
  let nameOnlyUiErrorKind;
  let nameOnlySaveResolved = false;
  try {
    if (!busyDialogVisible) {
      nameOnlyDialog = await editProject(
        page,
        projectRow,
        primary.displayName,
        { name: renamedProject },
        deadline,
      );
    } else {
      await busyDialog.getByRole("textbox", { name: "项目名称", exact: true }).fill(renamedProject);
      await busyDialog
        .getByRole("textbox", { name: "项目目录", exact: true })
        .fill(originalWorkspace.root);
      await busyDialog.getByRole("button", { name: "保存", exact: true }).click({
        timeout: interactionTimeout(deadline),
      });
    }
    nameOnlySaveResolved = await nameOnlyDialog
      .waitFor({ state: "detached", timeout: interactionTimeout(deadline, 30_000) })
      .then(() => true)
      .catch(() => false);
  } catch (error) {
    nameOnlyUiErrorKind = error?.name === "TimeoutError" ? "TIMEOUT" : "UI_ACTION_FAILED";
  }
  const workspaceAfterRename = await readWorkspace(page, primary.workspaceId);
  const nameOnlyWorkspaceUpdate = busyTraceAfter;
  const finalTrace = await projectManagementInvokeSnapshot(page);
  const nameOnlyUpdateResolved = finalTrace
    .slice(nameOnlyWorkspaceUpdate.length)
    .some(
      (event) =>
        event.command === "ja_runtime_workspace_update" &&
        event.phase === "resolved" &&
        event.workspaceId === primary.workspaceId.slice(0, 12),
    );
  const nameOnlyUpdateVerified =
    nameOnlySaveResolved &&
    nameOnlyUpdateResolved &&
    workspaceAfterRename.workspaceId === primary.workspaceId &&
    workspaceAfterRename.displayName === renamedProject &&
    samePath(workspaceAfterRename.root, originalWorkspace.root);
  await page.screenshot({
    path: join(evidenceDirectory, "project-management-name-only-while-busy.png"),
    animations: "disabled",
  });

  provider.releaseBusyTurn();
  const busyAnswer = page
    .locator('.ja-chat-message-final[data-response-state="completed"]')
    .filter({ hasText: BUSY_TURN_REPLY })
    .last();
  await busyAnswer.waitFor({ state: "visible", timeout: interactionTimeout(deadline, 30_000) });

  const report = {
    contractVersion: 1,
    status: "diagnostic_complete",
    runtime: { platform: process.platform, surface: "tauri_webview2", boundary: "debug_jar" },
    fixture: {
      workspaceId: primary.workspaceId.slice(0, 12),
      threadId: primary.threadId.slice(0, 12),
      workspaceReadProbePositiveControl,
      providerHeld,
    },
    bindingSample: bindingSampleSummary,
    consoleErrors,
    runtimeWhileBusy: {
      status: runtimeWhileBusy.status,
      generation: runtimeWhileBusy.generation,
      serverInstanceIdPresent: typeof runtimeWhileBusy.serverInstanceId === "string",
    },
    busyDirectoryUpdate: {
      dialogVisible: busyDialogVisible,
      uiErrorKind: busyUpdateUiErrorKind,
      attempted: busyTraceAfter
        .slice(busyTraceBefore.length)
        .some(
          (event) =>
            event.command === "ja_runtime_workspace_update" &&
            event.phase === "start" &&
            event.workspaceId === primary.workspaceId.slice(0, 12),
        ),
      alertVerified: busyAlertVerified,
      alertText: busyAlertVerified ? busyAlertText : undefined,
      expectedBusySummaryObserved: busyUpdateReturnedExpectedSummary,
      rejection: busyFailureTuple,
      directoryUnchanged,
    },
    nameOnlyUpdate: {
      savedWhileProviderHeld: nameOnlyUpdateVerified,
      uiErrorKind: nameOnlyUiErrorKind,
      workspaceIdentityPreserved: workspaceAfterRename.workspaceId === primary.workspaceId,
      directoryPreserved: samePath(workspaceAfterRename.root, originalWorkspace.root),
      displayNameChanged: workspaceAfterRename.displayName === renamedProject,
      nativeUpdateResolved: nameOnlyUpdateResolved,
    },
    dialogLayout: { ...dialogLayout, error: dialogLayoutError },
    screenshots: [
      "project-management-workspace-update-binding-sample.png",
      "project-edit-wide-long-path-light.png",
      "project-edit-wide-long-path-dark.png",
      "project-edit-dialog-wide-long-path-light.png",
      "project-edit-dialog-wide-long-path-dark.png",
      "project-edit-narrow-long-path-light.png",
      "project-edit-narrow-long-path-dark.png",
      "project-management-busy-update-diagnostic.png",
      "project-management-name-only-while-busy.png",
    ],
    invokeCounts: summarizeProjectManagementInvokes(finalTrace).counts,
  };
  await writeFile(
    join(evidenceDirectory, "project-management-workspace-update-diagnostic.json"),
    `${JSON.stringify(report, null, 2)}\n`,
    "utf8",
  );
  console.log(
    `[Ja project update diagnostic] ${JSON.stringify({
      busyFailureTuple,
      busyAlertVerified,
      directoryUnchanged,
      nameOnlyUpdateVerified,
      bindingSampleSummary,
      dialogLayoutError,
    })}`,
  );
  return validateWorkspaceUpdateDiagnostic(report);
}

/** 在单个隔离真窗中建立项目和历史、阻断忙碌重绑、移动目录、改名后重绑并关闭 Runtime。 */
async function runPreRestart({
  page,
  evidenceDirectory,
  isolatedRuntimeHome,
  providerBaseUrl,
  provider,
  outerFixture,
  pressureFacts,
  databaseStash,
}) {
  const deadline = Date.now() + 8 * 60_000;
  const pageErrors = [];
  const consoleErrors = [];
  page.on("pageerror", (error) =>
    pageErrors.push({
      type: "pageerror",
      text: String(error?.stack ?? error?.message ?? error).slice(0, 2_000),
    }),
  );
  page.on("console", (message) => {
    if (message.type() !== "error") return;
    pageErrors.push({
      type: "console.error",
      text: message.text().slice(0, 2_000),
      location: message.location(),
    });
    consoleErrors.push({
      type: "console.error",
      text: message.text().slice(0, 2_000),
      location: message.location(),
      handles: message.args(),
    });
  });
  await mkdir(evidenceDirectory, { recursive: true });
  try {
    await page.addInitScript(installProjectManagementInvokeProbe);
    await page.reload({
      waitUntil: "domcontentloaded",
      timeout: Math.max(1, deadline - Date.now()),
    });
    await waitForApplication(page, deadline);
    const primary = await createProjectAndThread(
      page,
      outerFixture.originalRoot,
      "项目目录 E2E 原名",
      providerBaseUrl,
    );
    const sibling = await createProjectAndThread(
      page,
      outerFixture.originalRoot,
      "项目目录 E2E 原名",
      providerBaseUrl,
      "项目目录 E2E 同项目会话",
    );
    assert.equal(sibling.workspaceId, primary.workspaceId);
    assert.notEqual(sibling.threadId, primary.threadId);
    const secondary = await createProjectAndThread(
      page,
      outerFixture.otherRoot,
      "项目目录 E2E 对照",
      providerBaseUrl,
    );
    assert.match(
      primary.workspaceId,
      /^ws_[0-9a-f]{32}$/u,
      "Java must mint a stable ws_ hexadecimal identity",
    );
    assert.notEqual(primary.workspaceId, secondary.workspaceId);
    await page.reload({
      waitUntil: "domcontentloaded",
      timeout: Math.max(1, deadline - Date.now()),
    });
    await waitForApplication(page, deadline);
    // A missing row is an early diagnostic signal; don't burn the full suite deadline before capturing React state.
    let project = await selectProject(
      page,
      primary.displayName,
      Math.min(deadline, Date.now() + 30_000),
    );
    await selectThread(page, primary.threadId, deadline);
    const projectFacts = await readWorkspace(page, primary.workspaceId);
    assert.equal(samePath(projectFacts.root, outerFixture.originalRoot), true);
    const initialInvokeTrace = await projectManagementInvokeSnapshot(page);
    const workspaceReadProbePositiveControl = initialInvokeTrace.some(
      (event) =>
        event.command === "ja_runtime_workspace_read" &&
        event.phase === "resolved" &&
        event.workspaceId === primary.workspaceId.slice(0, 12),
    );
    assert.equal(
      workspaceReadProbePositiveControl,
      true,
      "E2E nativeInvoke probe must positively capture the selected Workspace read",
    );

    await setThemeMode(page, "light", deadline);

    const menu = await verifyProjectMenu(
      page,
      project,
      primary.displayName,
      evidenceDirectory,
      deadline,
      "light",
    );
    await setThemeMode(page, "dark", deadline);
    const darkMenu = await verifyProjectMenu(
      page,
      await selectProject(page, primary.displayName, deadline),
      primary.displayName,
      evidenceDirectory,
      deadline,
      "dark",
    );
    await setThemeMode(page, "light", deadline);
    const projectPathCopiedToNativeClipboard = await verifyProjectPathClipboard(
      page,
      project,
      projectFacts,
      deadline,
    );
    const initialAnswer = await sendPromptAndWait(
      page,
      INITIAL_HISTORY_MARKER,
      INITIAL_HISTORY_MARKER,
      deadline,
    );
    const initialHistoryVisible = (await initialAnswer.innerText()).includes(
      INITIAL_HISTORY_MARKER,
    );
    assert.equal(initialHistoryVisible, true);
    await page.screenshot({
      path: join(evidenceDirectory, "project-history-before-move.png"),
      animations: "disabled",
    });
    const reviewInspector = page.locator(
      '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
    );
    await hideProjectWorkbench(page, reviewInspector, deadline);
    const hiddenReviewBaseline = await projectManagementInvokeSnapshot(page);
    const hiddenReviewSnapshotBaseline =
      summarizeProjectManagementInvokes(hiddenReviewBaseline).counts.ja_review_snapshot?.start ?? 0;
    await selectThread(page, sibling.threadId, deadline);
    await selectThread(page, primary.threadId, deadline);
    const afterSameWorkspaceThreadSwitch = await projectManagementInvokeSnapshot(page);
    const sameWorkspaceThreadSwitchVerified =
      sibling.workspaceId === primary.workspaceId && sibling.threadId !== primary.threadId;
    assert.equal(sameWorkspaceThreadSwitchVerified, true);
    assert.equal(
      projectManagementInvokeDelta(
        hiddenReviewBaseline,
        afterSameWorkspaceThreadSwitch,
        "ja_review_snapshot",
      ),
      0,
      "hidden Review must not request a second snapshot while switching same-project Threads",
    );
    for (const command of HIDDEN_WORKSPACE_IO_COMMANDS) {
      assert.equal(
        projectManagementInvokeDelta(hiddenReviewBaseline, afterSameWorkspaceThreadSwitch, command),
        0,
        `hidden Files must not issue ${command} while switching same-project Threads`,
      );
    }
    const busyTraceBefore = await projectManagementInvokeSnapshot(page);
    await page.getByRole("textbox", { name: "消息", exact: true }).fill(BUSY_TURN_MARKER);
    await page.getByRole("button", { name: "发送", exact: true }).click({
      timeout: Math.max(1, deadline - Date.now()),
    });
    await provider.waitForBusyTurn(Math.max(1, deadline - Date.now()));
    const busyThreadTitle = await readBusyThreadTitle(page, primary.workspaceId, primary.threadId);
    const busyTarget = join(dirname(outerFixture.otherRoot), "忙碌中不应绑定 的目录");
    await mkdir(busyTarget, { recursive: true });
    const busyDialog = await editProject(
      page,
      project,
      primary.displayName,
      { path: busyTarget },
      deadline,
    );
    const busyAlert = busyDialog.getByRole("alert");
    await busyAlert.waitFor({ state: "visible", timeout: interactionTimeout(deadline, 30_000) });
    const busyErrorText = (await busyAlert.innerText()).trim();
    const busyErrorVisible = isExpectedWorkspaceBusySummary(busyErrorText, busyThreadTitle);
    const busyTraceAfter = await projectManagementInvokeSnapshot(page);
    const busyUpdateFailure = busyTraceAfter
      .slice(busyTraceBefore.length)
      .findLast(
        (event) =>
          event.command === "ja_runtime_workspace_update" &&
          event.phase === "rejected" &&
          event.workspaceId === primary.workspaceId.slice(0, 12),
      );
    const busyUpdateReturnedExpectedSummary =
      busyUpdateFailure?.failure?.errorCode === "WORKSPACE_BUSY" &&
      busyUpdateFailure.failure.runnerPhaseClassification === "busy_summary_shape" &&
      busyUpdateFailure.failure.retryable === true;
    const busyUpdateFailureTuple = busyUpdateFailure?.failure ?? null;
    const busyStillOpen = await busyDialog.isVisible();
    const busyWorkspace = await readWorkspace(page, primary.workspaceId);
    const busyTurnBlockedDirectoryChange =
      busyStillOpen && samePath(busyWorkspace.root, outerFixture.originalRoot);
    await page.screenshot({
      path: join(evidenceDirectory, "project-rebind-blocked-by-busy-turn.png"),
      animations: "disabled",
    });
    assert.equal(busyTurnBlockedDirectoryChange, true);
    console.log(
      `[Ja project E2E] busy-turn update: ${JSON.stringify({
        alertVerified: busyErrorVisible,
        rejection: busyUpdateFailureTuple,
      })}`,
    );
    provider.releaseBusyTurn();
    await busyDialog.getByRole("button", { name: "取消", exact: true }).click();
    await busyDialog.waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
    const busyAnswer = page
      .locator('.ja-chat-message-final[data-response-state="completed"]')
      .filter({ hasText: BUSY_TURN_REPLY })
      .last();
    await busyAnswer.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });

    const dirtyDocumentEvidence = await exerciseProjectRebindDirtyDocuments(
      page,
      project,
      primary,
      outerFixture,
      deadline,
      evidenceDirectory,
    );
    const activeFilesTrace = await projectManagementInvokeSnapshot(page);
    const selectedWorkspaceIo =
      summarizeProjectManagementInvokes(activeFilesTrace).byWorkspace[
        primary.workspaceId.slice(0, 12)
      ] ?? {};
    assert.ok(
      (selectedWorkspaceIo.ja_workspace_tree?.resolved ?? 0) > 0,
      "opening Files and a real text document must positively exercise the selected Workspace tree",
    );
    const filesInspector = page.locator(
      '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
    );
    await hideProjectWorkbench(page, filesInspector, deadline);
    const hiddenBoundaryBaseline = await projectManagementInvokeSnapshot(page);

    await selectProject(page, secondary.displayName, deadline);
    await renameDirectory(outerFixture.originalRoot, outerFixture.lostRoot);
    project = await selectProject(page, primary.displayName, deadline);
    const missingDirectorySelectable = (await project.getAttribute("aria-current")) === "page";
    assert.equal(missingDirectorySelectable, true);
    const unavailableAlert = page.getByText(/项目目录不可用/u).first();
    await unavailableAlert.waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
    await selectThread(page, primary.threadId, deadline);
    const persistedHistory = page
      .locator('.ja-chat-message-final[data-response-state="completed"]')
      .filter({ hasText: INITIAL_HISTORY_MARKER })
      .last();
    await persistedHistory.waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
    const missingDirectoryHistoryVisible = (await persistedHistory.innerText()).includes(
      INITIAL_HISTORY_MARKER,
    );
    assert.equal(missingDirectoryHistoryVisible, true);
    await page.screenshot({
      path: join(evidenceDirectory, "project-history-with-missing-directory.png"),
      animations: "disabled",
    });
    await page.getByRole("button", { name: "重新检查", exact: true }).click({
      timeout: Math.max(1, deadline - Date.now()),
    });
    await page.waitForFunction(
      () => {
        const button = [...document.querySelectorAll("button")].find(
          (candidate) =>
            candidate.getAttribute("aria-label") === "重新检查" ||
            candidate.textContent?.trim() === "重新检查",
        );
        return button !== undefined && !button.disabled;
      },
      undefined,
      { timeout: Math.max(1, deadline - Date.now()) },
    );
    const missingDirectoryRecheckPreservedHistory =
      (await unavailableAlert.isVisible()) &&
      (await persistedHistory.innerText()).includes(INITIAL_HISTORY_MARKER);
    assert.equal(missingDirectoryRecheckPreservedHistory, true);
    const missingDirectoryErrorVisible = await unavailableAlert.isVisible();
    assert.equal(missingDirectoryErrorVisible, true);

    // 已完成一次显式重新检查后，目录失效必须稳定等待修复；8 秒内不得自行重试或重建提示。
    await project.waitFor({ state: "visible", timeout: interactionTimeout(deadline) });
    await page.waitForFunction(
      (name) => {
        const row = [...document.querySelectorAll('button[data-scope-kind="project"]')].find(
          (node) => node.textContent?.includes(name),
        );
        return row !== undefined && !row.disabled;
      },
      primary.displayName,
      { timeout: interactionTimeout(deadline) },
    );
    const missingBindingBaseline = await projectManagementInvokeSnapshot(page);
    const missingBindingSamples = await sampleProjectBindingState(
      page,
      8_000,
      100,
      primary.displayName,
    );
    const missingBindingAfter = await projectManagementInvokeSnapshot(page);
    const missingDirectoryBindingStable =
      missingBindingSamples.samples.length > 0 &&
      missingBindingSamples.samples.every(
        ({ dom }) =>
          dom?.targetPresent &&
          dom.targetVisible &&
          dom.targetCurrent &&
          dom.targetDisabled === false &&
          dom.unavailableVisible &&
          dom.unavailableCount === 1 &&
          Number.isSafeInteger(dom.unavailableNodeId),
      ) &&
      new Set(missingBindingSamples.samples.map(({ dom }) => dom.targetNodeId)).size === 1 &&
      new Set(missingBindingSamples.samples.map(({ dom }) => dom.unavailableNodeId)).size === 1 &&
      projectManagementInvokeDelta(
        missingBindingBaseline,
        missingBindingAfter,
        "ja_runtime_workspace_open",
      ) === 0;
    await writeFile(
      join(evidenceDirectory, "project-missing-directory-stability.json"),
      `${JSON.stringify({ stable: missingDirectoryBindingStable, sampling: missingBindingSamples }, null, 2)}\n`,
      "utf8",
    );
    assert.equal(
      missingDirectoryBindingStable,
      true,
      "missing directory must not trigger a retry loop",
    );

    const menuAfterMove = await verifyProjectMenu(
      page,
      project,
      primary.displayName,
      evidenceDirectory,
      deadline,
    );
    const cancelDiscardedDraft = await cancelProjectDraft(
      page,
      project,
      { ...primary, ...projectFacts },
      evidenceDirectory,
      deadline,
    );

    const beforeRename = await readWorkspace(page, primary.workspaceId);
    const renameDialog = await editProject(
      page,
      project,
      primary.displayName,
      {
        name: RENAMED_PROJECT_NAME,
      },
      deadline,
    );
    await renameDialog.waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
    project = await selectProject(page, RENAMED_PROJECT_NAME, deadline);
    const renamed = await readWorkspace(page, primary.workspaceId);
    const renameWithMissingDirectory =
      renamed.workspaceId === primary.workspaceId &&
      renamed.displayName === RENAMED_PROJECT_NAME &&
      samePath(renamed.root, beforeRename.root) &&
      !(await pathExists(renamed.root));
    assert.equal(renameWithMissingDirectory, true);

    const rebindDialog = await editProject(
      page,
      project,
      RENAMED_PROJECT_NAME,
      {
        path: outerFixture.movedRoot,
      },
      deadline,
    );
    await rebindDialog.waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
    const rebound = await readWorkspace(page, primary.workspaceId);
    const directoryRebound =
      rebound.workspaceId === primary.workspaceId &&
      rebound.displayName === RENAMED_PROJECT_NAME &&
      samePath(rebound.root, outerFixture.movedRoot);
    assert.equal(directoryRebound, true);
    await selectThread(page, primary.threadId, deadline);
    await page.waitForFunction(
      () =>
        ![...document.querySelectorAll(".ja-notification-content")].some((node) =>
          node.textContent?.includes("项目目录不可用"),
        ),
      undefined,
      { timeout: interactionTimeout(deadline) },
    );
    await page.screenshot({
      path: join(evidenceDirectory, "project-directory-rebound.png"),
      animations: "disabled",
    });
    const finalInvokeTrace = await projectManagementInvokeSnapshot(page);
    const hiddenReviewSnapshotDelta = projectManagementInvokeDelta(
      hiddenBoundaryBaseline,
      finalInvokeTrace,
      "ja_review_snapshot",
      primary.workspaceId.slice(0, 12),
    );
    const hiddenFilesCommandDeltas = Object.fromEntries(
      HIDDEN_WORKSPACE_IO_COMMANDS.map((command) => [
        command,
        projectManagementInvokeDelta(
          hiddenBoundaryBaseline,
          finalInvokeTrace,
          command,
          primary.workspaceId.slice(0, 12),
        ),
      ]),
    );
    assert.equal(
      hiddenReviewSnapshotDelta,
      0,
      "hidden Review must have no snapshot delta across root rebinding",
    );
    for (const [command, delta] of Object.entries(hiddenFilesCommandDeltas))
      assert.equal(delta, 0, `hidden Files must not issue ${command} across root rebinding`);

    const stopEvidence = await stopAndPreserveDatabase(
      page,
      isolatedRuntimeHome,
      databaseStash,
      deadline,
    );
    const providerFacts = provider.snapshot();
    assert.ok(providerFacts.acceptanceTurnCount >= 1);
    assert.equal(providerFacts.busyTurnCount, 1);
    assert.equal(pageErrors.length, 0, `renderer errors: ${pageErrors.join(" | ")}`);
    return {
      fixture: {
        workspaceId: primary.workspaceId,
        threadId: primary.threadId,
        movedRoot: outerFixture.movedRoot,
      },
      evidence: {
        workspaceIdFormat: /^ws_[0-9a-f]{32}$/u.test(primary.workspaceId),
        projectMenuFourItems:
          menu.menuItemCount === MENU_ITEMS.length &&
          menuAfterMove.menuItemCount === MENU_ITEMS.length,
        lightDarkThemesVerified:
          menu.screenshots.includes("project-menu-light.png") &&
          darkMenu.screenshots.includes("project-menu-dark.png"),
        keyboardContextMenu: true,
        escapeRestoresFocus: menu.escapeRestoresFocus && menuAfterMove.escapeRestoresFocus,
        cancelDiscardedDraft,
        projectPathCopiedToNativeClipboard,
        narrowDialogFitsViewport: cancelDiscardedDraft.dialogFitsNarrowViewport,
        initialHistoryVisible,
        busyTurnBlockedDirectoryChange,
        busyErrorVisible,
        busyUpdateReturnedExpectedSummary,
        busyUpdateFailureTuple,
        missingDirectorySelectable,
        missingDirectoryHistoryVisible,
        missingDirectoryRecheckPreservedHistory,
        missingDirectoryBindingStable,
        missingDirectoryErrorVisible,
        renameWithMissingDirectory,
        directoryRebound,
        workspaceReadProbePositiveControl,
        sameWorkspaceThreadSwitchVerified,
        dirtyDocumentSaveCancelDiscardVerified:
          dirtyDocumentEvidence.cancelRetainedDraftAndOldRoot &&
          dirtyDocumentEvidence.saveWroteOriginalDirectoryBeforeRebind &&
          dirtyDocumentEvidence.discardKeptDiskBaseline &&
          dirtyDocumentEvidence.discardClosedDirtyDocument,
        dirtyDocumentEvidence,
        pressureFixtureCountsVerified:
          pressureFacts.ignoredFiles === PROJECT_IGNORED_FILES &&
          pressureFacts.untrackedFiles === PROJECT_UNTRACKED_FILES,
        pressureFacts,
        hiddenReviewSnapshotBaseline,
        hiddenReviewSnapshotDelta,
        hiddenReviewSnapshotStable: hiddenReviewSnapshotDelta === 0,
        hiddenFilesCommandDeltas,
        hiddenFilesIoZero: Object.values(hiddenFilesCommandDeltas).every((delta) => delta === 0),
        preRestartWorkspace: rebound,
        serverInstanceId: stopEvidence.serverInstanceId,
      },
      screenshots: [
        ...menu.screenshots,
        ...darkMenu.screenshots,
        ...menuAfterMove.screenshots,
        "project-edit-cancel-draft.png",
        "project-edit-narrow-long-path-light.png",
        "project-edit-narrow-long-path-dark.png",
        "project-history-before-move.png",
        "project-rebind-blocked-by-busy-turn.png",
        "project-rebind-unsaved-cancel.png",
        "project-rebind-unsaved-cancel-retained.png",
        "project-rebind-unsaved-discard.png",
        "project-rebind-unsaved-discard-closed.png",
        "project-history-with-missing-directory.png",
        "project-directory-rebound.png",
      ],
      pageErrors: pageErrors.map(({ type, text, location }) => ({ type, text, location })),
    };
  } catch (error) {
    const diagnostic = await captureRendererDiagnostics(
      page,
      consoleErrors,
      evidenceDirectory,
      "project-management-pre-restart",
    );
    const commandTrace = await projectManagementInvokeSnapshot(page).catch(() => []);
    await writeFile(
      join(evidenceDirectory, "project-management-pre-restart-command-trace.json"),
      `${JSON.stringify(commandTrace, null, 2)}\n`,
      "utf8",
    );
    const firstConsoleError = diagnostic.consoleErrors.find(
      (entry) => entry.type === "console.error",
    );
    const errorStack = String(error?.stack ?? error?.message ?? error).slice(0, 2_000);
    throw new Error(
      `${errorStack}\nRenderer diagnostics: ${JSON.stringify({
        firstConsoleError: firstConsoleError?.arguments ?? firstConsoleError?.text ?? null,
        projectRows: diagnostic.dom.projectRows,
        shell: diagnostic.dom.shell,
        artifacts: [
          "project-management-pre-restart-console-errors.json",
          "project-management-pre-restart-dom.json",
          "project-management-pre-restart-failure.png",
          "project-management-pre-restart-command-trace.json",
        ],
      })}`,
      { cause: error },
    );
  }
}

/** 解析 temporary root 下目录是否存在；路径删除判断不调用 workspace resolver，以免污染命令证据。 */
async function pathExists(path) {
  try {
    await stat(path);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

/** 只允许重命名本 runner 自己创建的临时项目，避免对隔离边界外路径执行数据变更。 */
async function renameDirectory(originalRoot, movedRoot) {
  const temporaryRoot = resolve(tmpdir());
  for (const candidate of [originalRoot, movedRoot]) {
    const relation = relative(temporaryRoot, resolve(candidate));
    assert.ok(
      relation.length > 0 &&
        relation !== ".." &&
        !relation.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`),
    );
  }
  assert.equal(
    await pathExists(movedRoot),
    false,
    "move target must be absent to preserve fixture data",
  );
  await rename(originalRoot, movedRoot);
  assert.equal(await pathExists(originalRoot), false);
  assert.equal(await pathExists(movedRoot), true);
}

/** 仅将隔离 SQLite 与 WAL 状态恢复到新 profile 的 data 目录，不覆写本轮 loopback 配置或认证。 */
async function restoreIsolatedDatabase(sourceDirectory, home) {
  const targetDirectory = join(home, "data");
  await mkdir(targetDirectory, { recursive: true });
  const entries = await readdir(sourceDirectory, { withFileTypes: true });
  for (const entry of entries) {
    if (!entry.isFile() || !["ja.db", "ja.db-wal"].includes(entry.name)) continue;
    await copyFile(join(sourceDirectory, entry.name), join(targetDirectory, entry.name));
  }
  await stat(join(targetDirectory, "ja.db"));
}

/** @author kongweiguang 顺序运行隔离真窗；诊断短路与完整重启验收共用临时 fixture 和进程所有权。 */
export async function runProjectManagementWebView2(options) {
  assert.equal(process.platform, "win32", "project management acceptance requires Windows 11");
  await mkdir(options.evidenceDirectory, { recursive: true });
  const outerRoot = await mkdtemp(join(tmpdir(), "ja-project-management-"));
  const outerFixture = await createProjectManagementFixture(outerRoot);
  const databaseStash = join(outerRoot, "preserved-isolated-data");
  const provider = await startProjectManagementProviderFixture();
  let preRestart;
  try {
    if (options.diagnoseBinding) {
      const diagnostic = await runProduction({
        ...options,
        providerBaseUrl: provider.baseUrl,
        maxModelRounds: 1,
        scope: "git",
        fixture: "no-head",
        ignoredFiles: 0,
        untrackedFiles: 0,
        hiddenWindow: true,
        prewarmWebview: true,
        preserveFailedProfile: true,
        reportFileName: "project-management-binding-runtime.json",
        driver: (driverOptions) =>
          runBindingDiagnostic({
            ...driverOptions,
            providerBaseUrl: provider.baseUrl,
            outerFixture,
          }),
      });
      await writeFile(
        join(options.evidenceDirectory, "project-management-binding-diagnostic-result.json"),
        `${JSON.stringify(diagnostic, null, 2)}\n`,
        "utf8",
      );
      return diagnostic;
    }
    if (options.diagnoseWorkspaceUpdate) {
      const diagnostic = await runProduction({
        ...options,
        providerBaseUrl: provider.baseUrl,
        maxModelRounds: 3,
        scope: "git",
        fixture: "no-head",
        ignoredFiles: 0,
        untrackedFiles: 0,
        hiddenWindow: true,
        prewarmWebview: true,
        preserveFailedProfile: true,
        reportFileName: "project-management-workspace-update-runtime.json",
        driver: (driverOptions) =>
          runWorkspaceUpdateDiagnostic({
            ...driverOptions,
            providerBaseUrl: provider.baseUrl,
            provider,
            outerFixture,
          }),
      });
      await writeFile(
        join(
          options.evidenceDirectory,
          "project-management-workspace-update-diagnostic-result.json",
        ),
        `${JSON.stringify(diagnostic, null, 2)}\n`,
        "utf8",
      );
      return diagnostic;
    }
    const pressureFixture = await createReviewGitFixture(outerFixture.originalRoot, {
      fixture: "full",
      ignoredFiles: PROJECT_IGNORED_FILES,
      untrackedFiles: PROJECT_UNTRACKED_FILES,
    });
    assert.deepEqual(
      {
        ignoredFiles: pressureFixture.ignoredFiles,
        untrackedFiles: pressureFixture.untrackedFiles,
      },
      { ignoredFiles: PROJECT_IGNORED_FILES, untrackedFiles: PROJECT_UNTRACKED_FILES },
    );
    const pressureFacts = await countProjectPressureFiles(outerFixture.originalRoot);
    assert.deepEqual(pressureFacts, {
      ignoredFiles: PROJECT_IGNORED_FILES,
      untrackedFiles: PROJECT_UNTRACKED_FILES,
    });
    preRestart = await runProduction({
      ...options,
      providerBaseUrl: provider.baseUrl,
      maxModelRounds: 4,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      hiddenWindow: true,
      prewarmWebview: true,
      preserveFailedProfile: true,
      reportFileName: "project-management-pre-restart-runtime.json",
      driver: (driverOptions) =>
        runPreRestart({
          ...driverOptions,
          providerBaseUrl: provider.baseUrl,
          provider,
          outerFixture,
          pressureFacts,
          databaseStash,
        }),
    });
    const postRestart = await runProduction({
      ...options,
      providerBaseUrl: provider.baseUrl,
      maxModelRounds: 4,
      scope: "git",
      fixture: "no-head",
      ignoredFiles: 0,
      untrackedFiles: 0,
      hiddenWindow: true,
      prewarmWebview: true,
      preserveFailedProfile: true,
      reportFileName: "project-management-post-restart-runtime.json",
      prepareIsolatedHome: ({ home }) => restoreIsolatedDatabase(databaseStash, home),
      driver: (driverOptions) =>
        runPostRestart({
          ...driverOptions,
          fixture: preRestart.fixture,
        }),
    });
    assert.equal(preRestart.evidence.workspaceIdFormat, true);
    assert.equal(
      preRestart.evidence.preRestartWorkspace.workspaceId,
      postRestart.workspace.workspaceId,
    );
    assert.equal(preRestart.fixture.threadId, postRestart.threadId);
    assert.notEqual(preRestart.evidence.serverInstanceId, postRestart.serverInstanceId);
    assert.deepEqual([...preRestart.pageErrors, ...postRestart.pageErrors], []);
    const report = {
      contractVersion: 1,
      status: "passed",
      runtime: { platform: process.platform, surface: "tauri_webview2", boundary: "debug_jar" },
      coverage: {
        workspaceIdFormat: preRestart.evidence.workspaceIdFormat,
        projectMenuFourItems: preRestart.evidence.projectMenuFourItems,
        lightDarkThemesVerified: preRestart.evidence.lightDarkThemesVerified,
        menuItemCount: MENU_ITEMS.length,
        menuItemLabels: [...MENU_ITEMS],
        keyboardContextMenu: preRestart.evidence.keyboardContextMenu,
        escapeRestoresFocus: preRestart.evidence.escapeRestoresFocus,
        cancelDiscardedDraft: projectDialogCancellationPassed(
          preRestart.evidence.cancelDiscardedDraft,
        ),
        narrowDialogFitsViewport: preRestart.evidence.narrowDialogFitsViewport,
        longPathDialogLayoutVerified:
          preRestart.evidence.cancelDiscardedDraft.longPathDialogLayoutVerified,
        directoryNoticeHiddenDuringEdit:
          preRestart.evidence.cancelDiscardedDraft.directoryNoticeHiddenDuringEdit,
        projectPathCopiedToNativeClipboard: preRestart.evidence.projectPathCopiedToNativeClipboard,
        missingDirectoryRecheckPreservedHistory:
          preRestart.evidence.missingDirectoryRecheckPreservedHistory,
        missingDirectoryBindingStable: preRestart.evidence.missingDirectoryBindingStable,
        initialHistoryVisible: preRestart.evidence.initialHistoryVisible,
        busyTurnBlockedDirectoryChange: preRestart.evidence.busyTurnBlockedDirectoryChange,
        busyErrorVisible: preRestart.evidence.busyErrorVisible,
        busyUpdateReturnedExpectedSummary: preRestart.evidence.busyUpdateReturnedExpectedSummary,
        missingDirectorySelectable: preRestart.evidence.missingDirectorySelectable,
        missingDirectoryHistoryVisible: preRestart.evidence.missingDirectoryHistoryVisible,
        missingDirectoryErrorVisible: preRestart.evidence.missingDirectoryErrorVisible,
        renameWithMissingDirectory: preRestart.evidence.renameWithMissingDirectory,
        directoryRebound: preRestart.evidence.directoryRebound,
        workspaceIdPreservedAfterRestart:
          preRestart.evidence.preRestartWorkspace.workspaceId === postRestart.workspace.workspaceId,
        threadIdPreservedAfterRestart: preRestart.fixture.threadId === postRestart.threadId,
        historyVisibleAfterRestart: postRestart.historyVisible,
        serverRestartVerified:
          preRestart.evidence.serverInstanceId !== postRestart.serverInstanceId,
        workspaceReadProbePositiveControl: preRestart.evidence.workspaceReadProbePositiveControl,
        sameWorkspaceThreadSwitchVerified: preRestart.evidence.sameWorkspaceThreadSwitchVerified,
        dirtyDocumentSaveCancelDiscardVerified:
          preRestart.evidence.dirtyDocumentSaveCancelDiscardVerified,
        pressureFixtureCountsVerified: preRestart.evidence.pressureFixtureCountsVerified,
        hiddenReviewSnapshotStable: preRestart.evidence.hiddenReviewSnapshotStable,
        hiddenFilesIoZero: preRestart.evidence.hiddenFilesIoZero,
      },
      project: {
        directoryReboundAfterMove: true,
        pressureFiles: {
          ignored: preRestart.evidence.pressureFacts.ignoredFiles,
          untracked: preRestart.evidence.pressureFacts.untrackedFiles,
        },
        hiddenReviewSnapshotDelta: preRestart.evidence.hiddenReviewSnapshotDelta,
        hiddenFilesCommandDeltas: preRestart.evidence.hiddenFilesCommandDeltas,
        busyUpdateFailureTuple: preRestart.evidence.busyUpdateFailureTuple,
        dirtyDocuments: preRestart.evidence.dirtyDocumentEvidence,
        provider: "deterministic_loopback",
        providerCalls: provider.snapshot().requestCount,
        externalCalls: 0,
      },
      screenshots: [...preRestart.screenshots, postRestart.screenshot],
      pageErrors: [],
    };
    validateProjectManagementReport(report);
    await writeFile(
      join(options.evidenceDirectory, "project-management-webview2-report.json"),
      `${JSON.stringify(report, null, 2)}\n`,
      "utf8",
    );
    return report;
  } finally {
    await provider.close();
    const relation = relative(resolve(tmpdir()), resolve(outerRoot));
    assert.ok(relation.length > 0 && !relation.startsWith(".."));
    await rm(outerRoot, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  }
}

/** @author kongweiguang CLI 只输出稳定结果标记，具体真窗证据与截图留在隔离 Temp 目录。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  const report = await runProjectManagementWebView2(options);
  if (options.diagnoseBinding || options.diagnoseWorkspaceUpdate) {
    console.log(
      options.diagnoseBinding
        ? `JA_PROJECT_BINDING_DIAGNOSTIC_COMPLETE ${JSON.stringify({
            selection: report.selection,
            samples: report.sampling.samples.length,
            commands: report.probe.counts,
          })}`
        : `JA_PROJECT_WORKSPACE_UPDATE_DIAGNOSTIC_COMPLETE ${JSON.stringify({
            busyDirectoryUpdate: report.busyDirectoryUpdate,
            nameOnlyUpdate: report.nameOnlyUpdate,
            dialogLayout: report.dialogLayout,
          })}`,
    );
    return;
  }
  console.log(`JA_PROJECT_MANAGEMENT_WEBVIEW2_PASS ${JSON.stringify({ status: report.status })}`);
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  main().catch((error) => {
    console.error(
      `JA_PROJECT_MANAGEMENT_WEBVIEW2_FAIL ${String(error?.stack ?? error?.message ?? error).slice(0, 4_000)}`,
    );
    process.exitCode = 1;
  });
}
