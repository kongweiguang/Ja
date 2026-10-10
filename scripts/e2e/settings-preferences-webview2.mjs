// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { mkdir } from "node:fs/promises";
import { isAbsolute, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { chromium, expect } from "@playwright/test";

const endpoint = process.env.JA_E2E_SETTINGS_CDP_ENDPOINT;
let artifacts = process.env.JA_E2E_SETTINGS_ARTIFACT_DIR;
const restart = process.env.JA_E2E_SETTINGS_PHASE === "restart";
const mainConversationSelector = 'section.ja-conversation[aria-label="coding 对话"]';
let preferencesStep;
let executionScopeFacts;

/** 原生生命周期测试只能操作显式隔离的回环实例，不能连接用户主窗口。 */
function validateEnvironment() {
  if (
    process.platform !== "win32" ||
    process.env.JA_E2E_SETTINGS_ISOLATED !== "1" ||
    !/^http:\/\/(127\.0\.0\.1|localhost):\d+\/?$/.test(endpoint ?? "") ||
    !artifacts ||
    !isAbsolute(artifacts)
  )
    throw new Error("需要隔离 Windows 实例、回环 CDP 地址及绝对证据目录");
}

/** 用本地 Anthropic fixture 验证真实发送链路，不触发外部模型或计费请求。 */
export async function startProvider() {
  let sequence = 0;
  const server = createServer(async (request, response) => {
    let raw = "";
    for await (const chunk of request) {
      raw += chunk;
      if (raw.length > 2_000_000) {
        response.writeHead(413).end();
        return;
      }
    }
    const body = JSON.parse(raw);
    sequence += 1;
    const text = `PREFERENCES_REPLY_${sequence}\n\n\`\`\`javascript\nconst size = 16;\n\`\`\``;
    const message = {
      id: `msg_preferences_${sequence}`,
      type: "message",
      role: "assistant",
      model: body.model,
      content: [{ type: "text", text }],
      stop_reason: "end_turn",
      stop_sequence: null,
      usage: { input_tokens: 20, output_tokens: 20 },
    };
    if (body.stream !== true) {
      response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(message));
      return;
    }
    const events = [
      ["message_start", { message: { ...message, content: [], stop_reason: null } }],
      ["content_block_start", { index: 0, content_block: { type: "text", text: "" } }],
      ["content_block_delta", { index: 0, delta: { type: "text_delta", text } }],
      ["content_block_stop", { index: 0 }],
      [
        "message_delta",
        { delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 20 } },
      ],
      ["message_stop", {}],
    ];
    response.writeHead(200, { "content-type": "text/event-stream" });
    response.end(
      events
        .map(([type, value]) => `event: ${type}\ndata: ${JSON.stringify({ type, ...value })}\n\n`)
        .join(""),
    );
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

/** 首屏保持对话，设置始终由用户可见入口打开；不直接改导航 store 来替代真实操作。 */
async function openSettings(page, section) {
  const settings = page.locator(".ja-settings");
  if (!(await settings.isVisible())) {
    const reveal = page.getByRole("button", { name: "显示侧边栏", exact: true });
    if (await reveal.isVisible()) await reveal.click();
    await page.getByRole("button", { name: "设置", exact: true }).click();
  }
  await expect(settings).toBeVisible({ timeout: 60000 });
  await expect(settings).not.toHaveAttribute("inert", "", { timeout: 60000 });
  await settings.getByRole("tab", { name: section, exact: true }).click();
  return settings;
}

/** 所有设置选择都经过真实 Radix 控件，保存与错误反馈不能被直接改 store 绕过。 */
async function select(page, label, option) {
  const control = page.getByRole("combobox", { name: label, exact: true });
  await expect(control).toBeEnabled();
  await control.click();
  await page.getByRole("option", { name: option, exact: true }).click();
  await expect(control).toContainText(option);
}

/** 使用真实全局样式截图，并等待有限过渡完成。 */
async function capture(page, name) {
  await page.evaluate(async () => {
    await Promise.all(
      globalThis.document
        .getAnimations()
        .filter((animation) =>
          Number.isFinite(animation.effect?.getComputedTiming().endTime ?? Infinity),
        )
        .map((animation) => animation.finished.catch(() => undefined)),
    );
  });
  await page.screenshot({ path: join(artifacts, `${name}.png`), animations: "disabled" });
}

/** 只投影配置身份与 configured；Secret 和原生响应正文不得进入测试报告或断言输出。 */
async function providerReadback(page) {
  return page.evaluate(async () => {
    const { TauriSettingsAdapter } = await import("/src/api/tauri/settings.ts");
    const loaded = await new TauriSettingsAdapter().snapshot();
    const provider = loaded.document.providers.find((entry) => entry.name === "Settings fixture");
    return provider === undefined ? null : {
      providerId: provider.providerId,
      modelIds: provider.models.map((model) => model.modelId),
      credentialConfigured: provider.credentialConfigured,
      providerCount: loaded.document.providers.length,
      userVersion: loaded.cas.userVersion,
    };
  });
}

/** 主会话必须来自当前生产 composition 的唯一容器，不能误用侧聊或隐藏工作面的输入器。 */
function mainComposer(page) {
  return page.locator(mainConversationSelector).getByRole("textbox", { name: "消息", exact: true });
}

/** 唯一 Thread 来自有界权威目录；设置隐藏侧栏时另核对已挂载 Timeline，权限仅返回闭集枚举。 */
async function conversationContextReadback(page) {
  return page.evaluate(async (selector) => {
    const { TauriHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const history = new TauriHistoryAdapter();
    const projects = await history.workspaceList({ kind: "project", limit: 2 });
    const workspace = projects.items[0];
    const threads = workspace === undefined ? { items: [] } :
      await history.threadList({ workspaceId: workspace.workspaceId, limit: 2 });
    const selectedThreadId = globalThis.document.querySelector(
      '[aria-label="最近对话列表"] button[data-thread-id][aria-current="page"]',
    )?.getAttribute("data-thread-id");
    const currentThread = threads.items.find((thread) => thread.threadId === selectedThreadId);
    const uniqueThread = threads.items.length === 1 ? threads.items[0] : undefined;
    const accessMode = uniqueThread?.preferences?.accessMode;
    const timelineThreadId = globalThis.document.querySelector(selector)
      ?.querySelector(".ja-chat-timeline")?.getAttribute("data-thread-id");
    return { workspaceCount: projects.items.length, threadCount: threads.items.length,
      workspaceId: workspace?.workspaceId, threadId: uniqueThread?.threadId,
      workspaceTrusted: workspace?.trust === "trusted",
      threadAccessMode: ["full_access", "approval_required"].includes(accessMode) ? accessMode :
        accessMode === undefined ? "absent" : "unlisted",
      currentTimelineThreadMatches: uniqueThread !== undefined && timelineThreadId === uniqueThread.threadId,
      currentThreadMatches: currentThread !== undefined &&
        globalThis.document.querySelector(selector) !== null };
  }, mainConversationSelector);
}

/** 全局与 Thread 权限分别回读闭集枚举；控件与保存反馈只投影布尔值，不输出原生响应或页面正文。 */
async function executionScopeReadback(page) {
  const context = await conversationContextReadback(page);
  const ownerFacts = await page.evaluate(async (workspaceId) => {
    const { TauriSettingsAdapter } = await import("/src/api/tauri/settings.ts");
    const loaded = await new TauriSettingsAdapter().snapshot(
      workspaceId === undefined ? undefined : { workspaceId },
    );
    const globalMode = loaded.userDocument.defaultAccessMode;
    const effectiveMode = loaded.document.defaultAccessMode;
    return {
      globalDefaultAccessMode: ["full_access", "approval_required"].includes(globalMode) ? globalMode :
        globalMode === undefined ? "absent" : "unlisted",
      effectiveDefaultAccessMode: ["full_access", "approval_required"].includes(effectiveMode) ? effectiveMode :
        effectiveMode === undefined ? "absent" : "unlisted",
      projectAccessOverride: loaded.projectOverrides.accessMode === true,
    };
  }, context.workspaceId);
  const note = await page.evaluate(() => {
    const notes = [...globalThis.document.querySelectorAll('[role="note"][aria-label="执行确认生效范围"]')];
    const element = notes.length === 1 ? notes[0] : undefined;
    const text = element?.textContent ?? "";
    const radios = [...globalThis.document.querySelectorAll('fieldset[aria-label="工具执行方式"] input[type="radio"][value="approval_required"]')];
    const radio = radios.length === 1 ? radios[0] : undefined;
    const section = globalThis.document.querySelector('[data-setting-id="permission-mode"]');
    const status = section?.querySelector('[role="status"]');
    const retry = section?.querySelector(".ja-settings-form-actions button");
    const scopeButtons = [...globalThis.document.querySelectorAll(".ja-settings button[aria-pressed]")];
    return { noteCount: notes.length, noteHasLayout: (element?.getClientRects().length ?? 0) > 0,
      hasDefaultRule: text.includes("新会话采用默认值；已有会话保留选择，执行时以更严格的权限为准。"),
      hasFullAccessConversation: text.includes("当前会话：全部执行（会话选择）"),
      hasApprovalRequiredConversation: text.includes("当前会话：需要确认（会话选择）"),
      isSynchronizingProject: text.includes("正在同步当前项目设置…"),
      approvalRadioUnique: radios.length === 1,
      approvalRadioEnabled: radio !== undefined && !radio.disabled,
      approvalRadioVisible: radio !== undefined && radio.getClientRects().length > 0 &&
        globalThis.getComputedStyle(radio).visibility === "visible",
      approvalRadioChecked: radio?.checked === true,
      permissionWritePending: radio?.closest("fieldset")?.getAttribute("aria-busy") === "true",
      saveFeedbackPresent: status != null,
      saveRetryPresent: retry != null,
      saveRetryEnabled: retry != null && !retry.disabled,
      saveFailureShown: status != null && retry != null,
      globalScopeSelected: scopeButtons.some((button) => button.textContent?.trim() === "全局" &&
        button.getAttribute("aria-pressed") === "true"),
      projectScopeSelected: scopeButtons.some((button) => button.textContent?.trim() === "当前项目" &&
        button.getAttribute("aria-pressed") === "true") };
  });
  return { ...note, uniqueWorkspaceAndThread: context.workspaceCount === 1 && context.threadCount === 1,
    currentTimelineThreadMatches: context.currentTimelineThreadMatches, threadAccessMode: context.threadAccessMode,
    ...ownerFacts };
}

/** 仅用现有设置控件回归已证实的 owner 错配；全局、项目覆盖和继承必须分别得到权威回读。 */
async function verifyAccessScopeOwnership(page, expectedContext) {
  const settings = page.locator(".ja-settings");
  const group = page.getByRole("group", { name: "工具执行方式", exact: true });
  const fullRadio = group.getByRole("radio", { name: /^全部执行/ });
  const approvalRadio = group.getByRole("radio", { name: /^需要确认/ });
  preferencesStep = "execution_scope_global_owner_readback";
  assert.equal(executionScopeFacts.after.globalScopeSelected, true);
  assert.equal(executionScopeFacts.after.projectScopeSelected, false);
  assert.equal(executionScopeFacts.after.projectAccessOverride, false);
  assert.equal(executionScopeFacts.after.effectiveDefaultAccessMode, "approval_required");
  assertSameConversationIdentity(await conversationContextReadback(page), expectedContext);

  preferencesStep = "execution_scope_project_override_click";
  await settings.getByRole("button", { name: "当前项目", exact: true }).click();
  await expect(settings.getByRole("button", { name: "当前项目", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await expect(fullRadio).toHaveCount(1);
  await expect(fullRadio).toBeVisible();
  await expect(fullRadio).toBeEnabled();
  await fullRadio.click();
  preferencesStep = "execution_scope_project_override_checked";
  await expect(fullRadio).toBeChecked();
  executionScopeFacts.projectOverride = await executionScopeReadback(page);
  assert.equal(executionScopeFacts.projectOverride.projectScopeSelected, true);
  assert.equal(executionScopeFacts.projectOverride.globalDefaultAccessMode, "approval_required");
  assert.equal(executionScopeFacts.projectOverride.effectiveDefaultAccessMode, "full_access");
  assert.equal(executionScopeFacts.projectOverride.projectAccessOverride, true);
  assert.equal(executionScopeFacts.projectOverride.threadAccessMode, "full_access");
  assert.equal(executionScopeFacts.projectOverride.currentTimelineThreadMatches, true);
  assertSameConversationIdentity(await conversationContextReadback(page), expectedContext);
  await capture(page, "05-execution-scope-project-override");

  preferencesStep = "execution_scope_project_inherit_click";
  const inherit = settings.getByRole("button", { name: "使用全局设置", exact: true });
  await expect(inherit).toHaveCount(1);
  await expect(inherit).toBeEnabled();
  await inherit.click();
  preferencesStep = "execution_scope_project_inherit_readback";
  await expect(inherit).not.toBeVisible();
  await expect(approvalRadio).toBeChecked();
  executionScopeFacts.projectInherited = await executionScopeReadback(page);
  assert.equal(executionScopeFacts.projectInherited.projectScopeSelected, true);
  assert.equal(executionScopeFacts.projectInherited.globalDefaultAccessMode, "approval_required");
  assert.equal(executionScopeFacts.projectInherited.effectiveDefaultAccessMode, "approval_required");
  assert.equal(executionScopeFacts.projectInherited.projectAccessOverride, false);
  assert.equal(executionScopeFacts.projectInherited.threadAccessMode, "full_access");
  assert.equal(executionScopeFacts.projectInherited.currentTimelineThreadMatches, true);
  assertSameConversationIdentity(await conversationContextReadback(page), expectedContext);
  await capture(page, "05-execution-scope-project-inherited");

  preferencesStep = "execution_scope_global_return";
  await settings.getByRole("button", { name: "全局", exact: true }).click();
  await expect(settings.getByRole("button", { name: "全局", exact: true }))
    .toHaveAttribute("aria-pressed", "true");
  await expect(approvalRadio).toBeChecked();
  executionScopeFacts.globalRestored = await executionScopeReadback(page);
  assert.equal(executionScopeFacts.globalRestored.globalScopeSelected, true);
  assert.equal(executionScopeFacts.globalRestored.globalDefaultAccessMode, "approval_required");
  assert.equal(executionScopeFacts.globalRestored.effectiveDefaultAccessMode, "approval_required");
  assert.equal(executionScopeFacts.globalRestored.projectAccessOverride, false);
  assert.equal(executionScopeFacts.globalRestored.threadAccessMode, "full_access");
  assert.equal(executionScopeFacts.globalRestored.currentTimelineThreadMatches, true);
  assertSameConversationIdentity(await conversationContextReadback(page), expectedContext);
  executionScopeFacts.scopeOwnerCrosscheckPassed = true;
  await capture(page, "05-execution-scope-global-return");
}

/** 所有 trust 回读都同时固定唯一 Workspace/Thread，避免成功换权限却悄悄新建会话。 */
function assertSameConversationIdentity(actual, expected) {
  assert.equal(actual.workspaceCount, 1);
  assert.equal(actual.threadCount, 1);
  assert.equal(actual.workspaceId, expected.workspaceId);
  assert.equal(actual.threadId, expected.threadId);
}

/** 仅投影指定项目的固定错误码和失败边界；native 委托不代表 RPC 已到 Java，原始诊断不得进入证据。 */
async function installProjectTrustObservation(page, workspaceId) {
  await page.evaluate((expectedWorkspaceId) => {
    const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const allowedErrorCodes = new Set(["WORKSPACE_IO_ERROR", "RUNTIME_CONFIG_INVALID", "INVALID_PARAMS",
      "RUNTIME_UNAVAILABLE", "RUNTIME_QUEUE_FULL", "RUNTIME_COMMAND_DEADLINE",
      "RUNTIME_EVENT_DELIVERY_FAILED", "RUNTIME_SHUTDOWN_TIMEOUT"]);
    const unregisteredTrustCommand = "Command ja_workspace_set_trust not found";
    const permissionDeniedTrustCommand = "ja_workspace_set_trust not allowed. Command not found";
    const facts = { requests: 0, delegates: 0, failuresBeforeDelegate: 0,
      failuresDuringDelegate: 0, lastFailureCode: null, lastFailureStage: null,
      isUnregisteredTrustCommand: false, isTrustCommandPermissionDenied: false,
      trustedCompleted: 0, untrustedCompleted: 0,
      expectedWorkspaceOnly: true, failNext: false };
    globalThis.__JA_SETTINGS_TRUST_FACTS__ = facts;
    /** 保持真实 native 调用路径；失败只保存闭集码或固定未注册命令判别，绝不复制错误正文。 */
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
      if (request.command !== "ja_workspace_set_trust")
        return previous === undefined ? delegate() : previous(request, delegate);
      facts.requests += 1;
      facts.expectedWorkspaceOnly &&= request.args?.input?.workspaceId === expectedWorkspaceId;
      let delegateCalled = false;
      /** 计数真实 native 委托和成功回执，保留 transport 拒绝与服务端错误的区别。 */
      const observedDelegate = () => { delegateCalled = true; facts.delegates += 1; return delegate(); };
      try {
        if (facts.failNext) {
          facts.failNext = false;
          throw { code: "WORKSPACE_IO_ERROR" };
        }
        const result = await (previous === undefined ? observedDelegate() : previous(request, observedDelegate));
        if (result?.accepted === true) {
          if (request.args?.input?.trust === "trusted") facts.trustedCompleted += 1;
          if (request.args?.input?.trust === "untrusted") facts.untrustedCompleted += 1;
        }
        return result;
      } catch (error) {
        const errorCode = error !== null && typeof error === "object" ? error.code : null;
        facts.lastFailureCode = allowedErrorCodes.has(errorCode) ? errorCode : "UNLISTED_ERROR";
        facts.lastFailureStage = delegateCalled ? "delegate" : "before_delegate";
        facts.isUnregisteredTrustCommand = error === unregisteredTrustCommand ||
          (error !== null && typeof error === "object" && error.message === unregisteredTrustCommand);
        facts.isTrustCommandPermissionDenied = error === permissionDeniedTrustCommand ||
          (error !== null && typeof error === "object" && error.message === permissionDeniedTrustCommand);
        if (delegateCalled) facts.failuresDuringDelegate += 1;
        else facts.failuresBeforeDelegate += 1;
        throw error;
      }
    };
  }, workspaceId);
}

/** 报告只保留计数、固定错误闭集和归属布尔值，故障控制及 native 请求或错误正文不进入证据。 */
async function projectTrustFacts(page) {
  return page.evaluate(() => {
    const facts = globalThis.__JA_SETTINGS_TRUST_FACTS__;
    return facts === undefined ? null : { requests: facts.requests, delegates: facts.delegates,
      failuresBeforeDelegate: facts.failuresBeforeDelegate, failuresDuringDelegate: facts.failuresDuringDelegate,
      lastFailureCode: facts.lastFailureCode, lastFailureStage: facts.lastFailureStage,
      isUnregisteredTrustCommand: facts.isUnregisteredTrustCommand,
      isTrustCommandPermissionDenied: facts.isTrustCommandPermissionDenied, trustedCompleted: facts.trustedCompleted,
      untrustedCompleted: facts.untrustedCompleted, expectedWorkspaceOnly: facts.expectedWorkspaceOnly };
  });
}

/** 工具栏唯一的信任动作随服务端投影变更名称，不能用 disabled 项目范围按钮代替。 */
function projectTrustButton(settings, trusted) {
  return settings.getByRole("group", { name: "项目与配置范围", exact: true })
    .getByRole("button", { name: trusted ? /^撤销项目“.+”的信任$/u : /^信任项目“.+”$/u });
}

/** 失败截图先保留诊断再严格核验自然焦点；取消和同 Dialog 重试均不得用主动 focus 伪造恢复。 */
async function changeProjectTrustFromUi(page, expectedContext, trusted, injectFailure,
  target = trusted ? "trust" : "revoke") {
  const settings = await openSettings(page, "通用");
  const opener = projectTrustButton(settings, !trusted);
  const dialog = page.getByRole("dialog", {
    name: trusted ? "信任此项目？" : "撤销项目的信任？", exact: true,
  });
  const countsBefore = await projectTrustFacts(page);
  preferencesStep = `project_${target}_cancel`;
  await expect(opener).toBeEnabled({ timeout: 60000 });
  await opener.click();
  const cancel = dialog.getByRole("button", { name: "取消", exact: true });
  await expect(cancel).toBeFocused();
  await cancel.click();
  await expect(dialog).not.toBeVisible();
  await expect(opener).toBeFocused();
  const cancelled = await conversationContextReadback(page);
  assertSameConversationIdentity(cancelled, expectedContext);
  assert.equal(cancelled.workspaceTrusted, !trusted);
  assert.deepEqual(await projectTrustFacts(page), countsBefore);

  preferencesStep = `project_${target}_confirm`;
  await opener.click();
  await expect(cancel).toBeFocused();
  const action = dialog.getByRole("button", { name: trusted ? "信任项目" : "撤销信任", exact: true });
  if (injectFailure) {
    await page.evaluate(() => { globalThis.__JA_SETTINGS_TRUST_FACTS__.failNext = true; });
    await action.click();
    preferencesStep = `project_${target}_transport_failure`;
    await expect(dialog.getByRole("alert")).toContainText("信任状态或项目设置尚未完成确认");
    await expect(action).toBeEnabled();
    await expect(cancel).toBeEnabled();
    await capture(page, `00-project-${target}-failure`);
    await assertLiveDialogFocus(dialog);
    const failed = await conversationContextReadback(page);
    assertSameConversationIdentity(failed, expectedContext);
    assert.equal(failed.workspaceTrusted, !trusted);
    const failureFacts = await projectTrustFacts(page);
    assert.equal(failureFacts.requests, countsBefore.requests + 1);
    assert.equal(failureFacts.delegates, countsBefore.delegates);
    assert.equal(failureFacts.failuresBeforeDelegate, countsBefore.failuresBeforeDelegate + 1);
  }
  preferencesStep = `project_${target}_successful_readback`;
  await action.click();
  await expect(dialog).not.toBeVisible({ timeout: 60000 });
  const nextOpener = projectTrustButton(settings, trusted);
  await expect(nextOpener).toBeFocused();
  await expect.poll(async () => (await conversationContextReadback(page)).workspaceTrusted,
    { timeout: 60000 }).toBe(trusted);
  const completed = await conversationContextReadback(page);
  assertSameConversationIdentity(completed, expectedContext);
  const projectScope = settings.getByRole("button", { name: "当前项目", exact: true });
  if (trusted) await expect(projectScope).toBeEnabled({ timeout: 60000 });
  else await expect(projectScope).toBeDisabled({ timeout: 60000 });
  const finalFacts = await projectTrustFacts(page);
  assert.equal(finalFacts.expectedWorkspaceOnly, true);
  assert.equal(finalFacts.requests, countsBefore.requests + (injectFailure ? 2 : 1));
  assert.equal(finalFacts.delegates, countsBefore.delegates + 1);
  assert.equal(finalFacts[trusted ? "trustedCompleted" : "untrustedCompleted"],
    countsBefore[trusted ? "trustedCompleted" : "untrustedCompleted"] + 1);
  await capture(page, `00-project-${target}-complete`);
  return finalFacts;
}

/** reload 后重新打开真实 Settings，确认持久信任和会话身份，再为下一阶段建立独立观察器。 */
async function reloadProjectTrustFromUi(page, expectedContext, trusted) {
  preferencesStep = `project_${trusted ? "trusted" : "revoked"}_reload_readback`;
  await page.reload();
  await selectExistingConversationFromUi(page, expectedContext);
  const settings = await openSettings(page, "通用");
  await expect(projectTrustButton(settings, trusted)).toBeEnabled({ timeout: 60000 });
  const restored = await conversationContextReadback(page);
  assertSameConversationIdentity(restored, expectedContext);
  assert.equal(restored.workspaceTrusted, trusted);
  await capture(page, `00-project-${trusted ? "trusted" : "revoked"}-reload`);
  await installProjectTrustObservation(page, expectedContext.workspaceId);
}

/** 注册不授信；三个真实 UI 阶段覆盖授信、撤信和重新授信，最终状态供同 profile 冷窗回读。 */
async function verifyProjectTrustUi(page, registered) {
  preferencesStep = "project_default_untrusted_readback";
  assert.equal(registered.workspaceTrusted, false);
  await installProjectTrustObservation(page, registered.workspaceId);
  const settings = await openSettings(page, "通用");
  await expect(projectTrustButton(settings, false)).toBeEnabled({ timeout: 60000 });
  await expect(settings.getByRole("button", { name: "当前项目", exact: true })).toBeDisabled();
  await capture(page, "00-project-default-untrusted");
  const initialTrust = await changeProjectTrustFromUi(page, registered, true, true);
  await reloadProjectTrustFromUi(page, registered, true);
  const revocation = await changeProjectTrustFromUi(page, registered, false, true);
  await reloadProjectTrustFromUi(page, registered, false);
  const retrust = await changeProjectTrustFromUi(page, registered, true, false, "retrust");
  return { defaultUntrusted: true, explicitUi: true, cancelPreservedTrust: true,
    failureRetainedDialog: true, sameDialogRetry: true, failureBeforeDelegate: true,
    authoritativeReadback: true, trustedReloadReadback: true, revokedReloadReadback: true,
    sameWorkspaceAndThread: true, finalTrusted: true, initialTrust, revocation, retrust,
    javaSynchronizationFailureVerified: false };
}

/** 侧栏开关走可见控件；测试不能直接写导航状态或把隔离 picker 当作已打开项目。 */
async function revealSidebar(page) {
  const reveal = page.getByRole("button", { name: "显示侧边栏", exact: true });
  if (await reveal.isVisible()) await reveal.click();
}

/** 新项目自动创建唯一 Thread；先核验默认未信任，再经 Settings 显式授权后要求 Composer 可用。 */
async function establishInitialConversation(page) {
  preferencesStep = "initial_context_return_to_app";
  await page.getByRole("button", { name: "返回应用", exact: true }).click();
  preferencesStep = "initial_context_open_private_project";
  await revealSidebar(page);
  const projects = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]');
  await expect(projects).toHaveCount(0);
  const addProject = page.getByRole("button", { name: "添加项目", exact: true });
  await expect(addProject).toBeEnabled({ timeout: 60000 });
  await addProject.click();
  await expect(page.locator('[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]')).toBeVisible({ timeout: 60000 });
  preferencesStep = "initial_context_registered_thread_readback";
  await expect.poll(async () => (await conversationContextReadback(page)).threadCount,
    { timeout: 60000 }).toBe(1);
  const registered = await conversationContextReadback(page);
  assert.equal(registered.workspaceCount, 1);
  assert.equal(registered.workspaceTrusted, false);
  const counts = await page.evaluate(() => globalThis.__JA_SETTINGS_SAVE_COUNTS__ === undefined ?
    null : { ...globalThis.__JA_SETTINGS_SAVE_COUNTS__ });
  if (counts !== null) {
    assert.equal(counts.workspaceOpens, 1);
    assert.equal(counts.threadCreates, 1);
  }
  const projectTrustFlow = await verifyProjectTrustUi(page, registered);
  preferencesStep = "initial_context_composer_focus";
  await returnFromSettingsToComposer(page, "initial_trusted_context");
  const context = await conversationContextReadback(page);
  assertSameConversationIdentity(context, registered);
  assert.equal(context.workspaceTrusted, true);
  assert.equal(context.currentThreadMatches, true);
  await capture(page, "00-initial-conversation-context");
  return { context, projectTrustFlow, nativeSetupFacts: counts === null ? null :
    { workspaceOpens: counts.workspaceOpens, threadCreates: counts.threadCreates } };
}

/** 已有会话从设置返回时必须自行恢复可用与焦点；调用方只能触发真实返回控件。 */
async function returnFromSettingsToComposer(page, phase) {
  preferencesStep = `${phase}_settings_return`;
  await page.getByRole("button", { name: "返回应用", exact: true }).click();
  preferencesStep = `${phase}_composer_ready`;
  const composer = mainComposer(page);
  await expect(page.locator(mainConversationSelector)).toHaveCount(1);
  await expect(composer).toHaveCount(1);
  await expect(composer).toBeEnabled({ timeout: 60000 });
  preferencesStep = `${phase}_composer_focus`;
  await expect(composer).toBeFocused();
  return composer;
}

/** reload 与冷窗均用真实侧栏选择既有身份；空会话没有正文 Timeline，也不能额外创建 Thread。 */
async function selectExistingConversationFromUi(page, expectedContext) {
  await revealSidebar(page);
  const projects = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]');
  await expect(projects).toHaveCount(1, { timeout: 60000 });
  await projects.click();
  const threads = page.locator('[aria-label="最近对话列表"] button[data-thread-id]');
  await expect(threads).toHaveCount(1, { timeout: 60000 });
  await threads.click();
  await expect(threads).toHaveAttribute("aria-current", "page");
  const restored = await conversationContextReadback(page);
  if (expectedContext !== undefined) assertSameConversationIdentity(restored, expectedContext);
  assert.equal(restored.currentThreadMatches, true);
}

/** 冷窗只选择已持久化的项目与 Thread；禁止创建新身份来伪造同会话恢复或终端准入。 */
async function restoreExistingConversation(page, expectedContext) {
  preferencesStep = "cold_context_project_and_thread_selection";
  await page.getByRole("button", { name: "返回应用", exact: true }).click();
  await selectExistingConversationFromUi(page, expectedContext);
  preferencesStep = "cold_context_composer_ready";
  const composer = mainComposer(page);
  await expect(page.locator(mainConversationSelector)).toHaveCount(1);
  await expect(composer).toHaveCount(1);
  await expect(composer).toBeEnabled({ timeout: 60000 });
  const restored = await conversationContextReadback(page);
  assert.equal(restored.workspaceCount, 1);
  assert.equal(restored.threadCount, 1);
  assert.equal(restored.workspaceTrusted, true);
  assert.equal(restored.currentThreadMatches, true);
  if (expectedContext !== undefined) {
    assert.equal(restored.workspaceId, expectedContext.workspaceId);
    assert.equal(restored.threadId, expectedContext.threadId);
  }
  preferencesStep = "cold_context_composer_focus";
  await expect(composer).toBeFocused();
}

/** 观察真实 native 提交，不替换响应；故障仅由隔离启动器阻断实际凭据文件写入。 */
async function installSaveObservation(page, fault) {
  await page.exposeFunction("__JA_SETTINGS_CREDENTIAL_FAULT_START", () => fault.start());
  await page.evaluate(() => {
    const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
    const counts = { configuration: 0, credentials: 0, credentialFailures: 0,
      credentialDelegates: 0, credentialHookFailures: 0, faultStarts: 0, faultCompleted: 0,
      workspaceOpens: 0, threadCreates: 0 };
    globalThis.__JA_SETTINGS_SAVE_COUNTS__ = counts;
    globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
      const credential = request.command === "ja_credential_set";
      if (request.command === "ja_runtime_workspace_open") counts.workspaceOpens += 1;
      if (request.command === "ja_thread_create") counts.threadCreates += 1;
      if (["ja_configuration_patch", "ja_configuration_replace"].includes(request.command)) {
        counts.configuration += 1;
      }
      if (credential && ++counts.credentials === 1) {
        counts.faultStarts += 1;
        await globalThis.__JA_SETTINGS_CREDENTIAL_FAULT_START();
        counts.faultCompleted += 1;
      }
      let delegateCalled = false;
      /** 委托回调仍执行原生 invoke，只记录是否真的到达该边界，不能以 hook 自身失败冒充原生写失败。 */
      const observedDelegate = () => {
        delegateCalled = true;
        if (credential) counts.credentialDelegates += 1;
        return delegate();
      };
      try {
        return await (previous ? previous(request, observedDelegate) : observedDelegate());
      } catch (error) {
        if (credential && delegateCalled) counts.credentialFailures += 1;
        else if (credential) counts.credentialHookFailures += 1;
        throw error;
      }
    };
  });
}

/** 失败焦点必须留在真实、已恢复可操作的 Sheet 控件内，不能以脚本主动 focus 伪造恢复。 */
async function assertLiveDialogFocus(dialog) {
  await expect.poll(() => dialog.evaluate((element) => {
    const active = element.ownerDocument.activeElement;
    return element.contains(active) && active?.matches("input, button") && !active.matches(":disabled");
  })).toBe(true);
}

/** 凭据失败保留内存输入且重试只完成凭据；成功停留在 Settings，由项目建档流程独占返回应用动作。 */
async function configureProvider(page, url, fault) {
  preferencesStep = "provider_open_and_initial_focus";
  const settings = await openSettings(page, "模型");
  const editing =
    (await settings.getByRole("button", { name: "编辑供应商", exact: true }).count()) > 0;
  const action = editing ? "编辑供应商" : "新增供应商";
  const opener = settings.getByRole("button", { name: action, exact: true });
  await opener.click();
  const dialog = page.getByRole("dialog", { name: action, exact: true });
  await expect(dialog.getByLabel("供应商名称")).toBeFocused();
  if (!editing) {
    await dialog.getByLabel("供应商名称").fill("Discarded settings draft");
    await dialog.getByLabel("API Key", { exact: true }).fill(`discard-${randomUUID()}`);
  }
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  preferencesStep = "provider_cancel_returns_focus";
  await expect(dialog).not.toBeVisible();
  await expect(opener).toBeFocused();
  await opener.click();
  preferencesStep = "provider_reopen_clears_secret";
  if (!editing) assert.equal(await dialog.getByLabel("API Key", { exact: true }).inputValue() === "", true);
  await dialog.getByLabel("供应商名称").fill("Settings fixture");
  await select(page, "API 规范", "Anthropic Messages");
  await dialog.getByLabel("Base URL").fill(url);
  const secret = `isolated-${randomUUID()}`;
  const secretInput = dialog.getByLabel("API Key", { exact: true });
  await secretInput.fill(secret);
  await dialog.getByLabel("显示名称", { exact: true }).fill("Settings fixture model");
  await dialog.getByLabel("上游模型标识").fill("settings-fixture");
  const save = dialog.getByRole("button", { name: editing ? "保存更改" : "保存供应商", exact: true });
  if (fault !== undefined) await installSaveObservation(page, fault);
  preferencesStep = "provider_real_partial_save";
  await save.click();
  let partial;
  let countsBeforeRetry;
  if (fault !== undefined) {
    preferencesStep = "provider_wait_partial_saved_message";
    await expect(dialog.getByRole("alert")).toContainText("配置已保存");
    preferencesStep = "provider_wait_input_retained_message";
    await expect(dialog.getByRole("alert")).toContainText("输入已保留");
    preferencesStep = "provider_retained_input";
    assert.equal(await secretInput.inputValue() === secret, true, "failed save retains in-memory input");
    preferencesStep = "provider_failed_focus";
    await assertLiveDialogFocus(dialog);
    preferencesStep = "provider_partial_readback_and_native_counts";
    partial = await providerReadback(page);
    assert.equal(partial?.credentialConfigured, false);
    assert.equal(partial?.modelIds.length, 1);
    countsBeforeRetry = await page.evaluate(() => ({ ...globalThis.__JA_SETTINGS_SAVE_COUNTS__ }));
    assert.equal(countsBeforeRetry.credentialFailures, 1);
    assert.equal(countsBeforeRetry.configuration, 1);
    await capture(page, "00-provider-partial-save");
    await fault.release();
    preferencesStep = "provider_credential_only_retry";
    await save.click();
  }
  await expect(dialog).not.toBeVisible();
  const complete = await providerReadback(page);
  assert.equal(complete?.credentialConfigured, true);
  if (partial !== undefined) {
    const counts = await page.evaluate(() => ({ ...globalThis.__JA_SETTINGS_SAVE_COUNTS__ }));
    assert.deepEqual(complete.modelIds, partial.modelIds);
    assert.equal(complete.providerId, partial.providerId);
    assert.equal(complete.providerCount, partial.providerCount);
    assert.equal(complete.userVersion, partial.userVersion);
    assert.equal(counts.configuration, countsBeforeRetry.configuration);
    assert.equal(counts.credentials, 2);
  }
  preferencesStep = "provider_success_returns_focus";
  await expect(opener).toBeFocused();
  return complete;
}

/** 以进程正常关闭作为退出完成条件，不杀进程来伪造资源清理成功。 */
async function closeApplication(page) {
  const closed = page.waitForEvent("close", { timeout: 35000 });
  await page
    .getByRole("button", { name: "关闭", exact: true })
    .click()
    .catch((error) => {
      if (!page.isClosed()) throw error;
    });
  await closed;
}

/** 信任 UI 先保证同一项目可执行；范围断言逐阶段记录固定事实，不能用宽松文案掩盖会话投影缺失。 */
async function verify(page, provider, fault) {
  preferencesStep = "general_preferences_and_background_close";
  await page.reload();
  await expect(page.locator(".ja-settings, .ja-composer textarea").first()).toBeVisible({
    timeout: 60000,
  });
  await openSettings(page, "通用");
  await expect(page.getByRole("combobox", { name: "关闭窗口时" })).toContainText("留在后台");
  await select(page, "发送快捷键", "Enter 发送");
  await capture(page, "01-general-default");
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "关闭窗口时" })).toBeVisible();

  const savedProvider = await configureProvider(page, provider.url, fault);
  // 供应商边界完成后立即冻结计数，后续权限保存与 reload 不得污染或清空重试证据。
  const nativeSaveFacts = await page.evaluate(() => globalThis.__JA_SETTINGS_SAVE_COUNTS__ === undefined ?
    null : { ...globalThis.__JA_SETTINGS_SAVE_COUNTS__ });
  const { context, nativeSetupFacts, projectTrustFlow } = await establishInitialConversation(page);
  preferencesStep = "enter_send_and_shift_enter_newline";
  const composer = mainComposer(page);
  await composer.fill("Preference first");
  await composer.press("Shift+Enter");
  await expect(composer).toHaveValue("Preference first\n");
  await composer.press("Enter");
  await expect(composer).toHaveValue("");
  await expect(
    page
      .getByRole("article", { name: "最终答复", exact: true })
      .filter({ hasText: "PREFERENCES_REPLY" })
      .first(),
  ).toBeVisible({ timeout: 30000 });

  await openSettings(page, "通用");
  preferencesStep = "modifier_send_and_enter_newline";
  await select(page, "发送快捷键", "Ctrl + Enter 发送");
  await capture(page, "02-general-modifier");
  await returnFromSettingsToComposer(page, "existing_shortcut");
  preferencesStep = "modifier_send_and_enter_newline";
  await composer.fill("Preference second");
  await composer.press("Enter");
  await expect(composer).toHaveValue("Preference second\n");
  await composer.press("Control+Enter");
  await expect(composer).toHaveValue("");
  await expect(
    page
      .getByRole("article", { name: "最终答复", exact: true })
      .filter({ hasText: "PREFERENCES_REPLY" }),
  ).toHaveCount(2, { timeout: 30000 });

  await openSettings(page, "外观");
  preferencesStep = "appearance_font_application";
  await select(page, "界面字号", "大");
  await select(page, "代码与终端字号", "16px");
  await expect
    .poll(() =>
      page.evaluate(
        () => globalThis.getComputedStyle(globalThis.document.documentElement).fontSize,
      ),
    )
    .toBe("18px");
  await capture(page, "03-appearance-large");
  await returnFromSettingsToComposer(page, "existing_appearance");
  preferencesStep = "appearance_font_application";
  await capture(page, "04-conversation-large-code");
  const code = page.locator(".ja-markdown__code-block pre code").first();
  await expect(code).toBeVisible();
  await expect
    .poll(() => code.evaluate((element) => globalThis.getComputedStyle(element).fontSize))
    .toBe("16px");

  await openSettings(page, "执行确认");
  preferencesStep = "execution_scope_note_before";
  const note = page.getByRole("note", { name: "执行确认生效范围" });
  await capture(page, "05-execution-scope-before");
  executionScopeFacts = { before: await executionScopeReadback(page) };
  await expect(note).toContainText("当前会话：全部执行（会话选择）");
  preferencesStep = "execution_scope_radio_click";
  const permissionGroup = page.getByRole("group", { name: "工具执行方式", exact: true });
  const approvalRadio = permissionGroup.getByRole("radio", { name: /^需要确认/ });
  await expect(approvalRadio).toHaveCount(1);
  await expect(approvalRadio).toBeVisible();
  await expect(approvalRadio).toBeEnabled();
  await approvalRadio.click();
  preferencesStep = "execution_scope_radio_checked";
  await expect(approvalRadio).toBeChecked();
  preferencesStep = "execution_scope_note_after";
  executionScopeFacts.after = await executionScopeReadback(page);
  assert.equal(executionScopeFacts.after.globalDefaultAccessMode, "approval_required");
  assert.equal(executionScopeFacts.after.threadAccessMode, "full_access");
  assert.equal(executionScopeFacts.after.currentTimelineThreadMatches, true);
  await expect(note).toContainText("当前会话：全部执行（会话选择）");
  await capture(page, "05-execution-scope");
  await verifyAccessScopeOwnership(page, context);
  await returnFromSettingsToComposer(page, "existing_execution");

  await page.reload();
  preferencesStep = "preferences_reload_and_exit_mode";
  await openSettings(page, "通用");
  await expect(page.getByRole("combobox", { name: "发送快捷键" })).toContainText("Ctrl + Enter");
  await expect
    .poll(() =>
      page.evaluate(
        () => globalThis.getComputedStyle(globalThis.document.documentElement).fontSize,
      ),
    )
    .toBe("18px");
  await select(page, "关闭窗口时", "退出 Ja");
  await page.setViewportSize({ width: 720, height: 640 });
  await capture(page, "06-general-narrow-large");
  await openSettings(page, "外观");
  await expect(page.getByRole("combobox", { name: "代码与终端字号" })).toContainText("16px");
  await expect
    .poll(() =>
      page
        .locator(".ja-settings")
        .evaluate((element) => element.scrollWidth <= element.clientWidth),
    )
    .toBe(true);
  await capture(page, "07-appearance-narrow-large");
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await capture(page, "08-appearance-dark");
  preferencesStep = "normal_controls_ui_exit";
  await closeApplication(page);
  return { savedProvider, nativeSaveFacts, context, nativeSetupFacts, projectTrustFlow, executionScopeFacts };
}

/** 冷窗按原项目与 Thread 回读 owner 分离和继承状态，防止热缓存掩盖错误持久化；PTY 与退出仍走原生链。 */
async function verifyRestart(page, expectedProvider, expectedContext) {
  preferencesStep = "cold_provider_and_preferences_readback";
  await page.reload();
  await expect(page.locator(".ja-settings, .ja-composer textarea").first()).toBeVisible({
    timeout: 60000,
  });
  await openSettings(page, "通用");
  if (expectedContext !== undefined) {
    preferencesStep = "cold_project_trust_readback";
    const restored = await conversationContextReadback(page);
    assertSameConversationIdentity(restored, expectedContext);
    assert.equal(restored.workspaceTrusted, true);
  }
  if (expectedProvider !== undefined) {
    const restored = await providerReadback(page);
    assert.deepEqual(restored?.modelIds, expectedProvider.modelIds);
    assert.equal(restored?.providerId, expectedProvider.providerId);
    assert.equal(restored?.credentialConfigured, true);
  }
  await expect(page.getByRole("combobox", { name: "关闭窗口时" })).toContainText("退出 Ja");
  await expect(page.getByRole("combobox", { name: "发送快捷键" })).toContainText("Ctrl + Enter");
  await capture(page, "09-cold-restart");
  await restoreExistingConversation(page, expectedContext);
  if (expectedContext !== undefined) {
    await openSettings(page, "执行确认");
    preferencesStep = "cold_execution_scope_owner_readback";
    await expect(page.getByRole("group", { name: "工具执行方式", exact: true })
      .getByRole("radio", { name: /^需要确认/ })).toBeChecked();
    await expect(page.getByRole("note", { name: "执行确认生效范围" }))
      .toContainText("当前会话：全部执行（会话选择）");
    const cold = await executionScopeReadback(page);
    assert.equal(cold.globalScopeSelected, true);
    assert.equal(cold.globalDefaultAccessMode, "approval_required");
    assert.equal(cold.effectiveDefaultAccessMode, "approval_required");
    assert.equal(cold.projectAccessOverride, false);
    assert.equal(cold.threadAccessMode, "full_access");
    assert.equal(cold.currentTimelineThreadMatches, true);
    assertSameConversationIdentity(await conversationContextReadback(page), expectedContext);
    executionScopeFacts = { cold };
    await capture(page, "09-cold-execution-scope");
  }
  await openSettings(page, "外观");
  if (expectedContext !== undefined) {
    preferencesStep = "cold_project_trust_toolbar_readback";
    await expect(projectTrustButton(page.locator(".ja-settings"), true)).toBeEnabled({ timeout: 60000 });
    await capture(page, "09-cold-project-trust");
  }
  await verifyTerminalFont(page);
  preferencesStep = "normal_cold_ui_exit";
  await closeApplication(page);
}

/** 启动器独占资源清理；controls 使用独立局部别名，避免遮蔽冷窗共享事实产生 TDZ，报告仍只返回固定事实。 */
export async function runSettingsPreferences({ page, provider, phase, evidenceDirectory, fault, expectedProvider, expectedContext }) {
  artifacts = evidenceDirectory;
  executionScopeFacts = undefined;
  await mkdir(artifacts, { recursive: true });
  try {
    if (phase === "restart") {
      await verifyRestart(page, expectedProvider, expectedContext);
      return { coldReadback: true, coldContextReadback: expectedContext !== undefined,
        coldProjectTrustReadback: expectedContext !== undefined, executionScopeFacts };
    }
    const { savedProvider, nativeSaveFacts, context, nativeSetupFacts, projectTrustFlow, executionScopeFacts: controlScopeFacts } = await verify(page, provider, fault);
    return { provider: savedProvider, partialSaveRetainedInput: fault !== undefined, credentialOnlyRetry: fault !== undefined, credentialFault: fault?.kind, nativeSaveFacts, conversationContext: context, nativeSetupFacts, projectTrustFlow, executionScopeFacts: controlScopeFacts };
  } catch (error) {
    error.settingsPreferencesStep = preferencesStep;
    if (executionScopeFacts !== undefined) error.settingsExecutionScopeFacts = { ...executionScopeFacts };
    // 焦点失败只投影固定控件与可操作性的布尔事实，不能读取 input.value、动态 DOM 文本或 Locator 错误正文。
    if (!page.isClosed()) {
      try {
        if (preferencesStep?.startsWith("execution_scope_")) {
          error.settingsExecutionScopeFacts ??= {};
          error.settingsExecutionScopeFacts.atFailure = await executionScopeReadback(page);
          if (preferencesStep.startsWith("execution_scope_radio_")) {
            await capture(page, "05-execution-scope-radio-failure");
          } else if (preferencesStep.startsWith("execution_scope_project_") ||
            preferencesStep.startsWith("execution_scope_global_")) {
            await capture(page, "05-execution-scope-owner-failure");
          }
        }
        error.settingsFocusFacts = await page.evaluate((selector) => {
          const active = globalThis.document.activeElement;
          const dialog = globalThis.document.querySelector(".ja-settings-provider-sheet");
          const trustDialog = globalThis.document.querySelector(".ja-settings-trust-dialog");
          const roots = [...globalThis.document.querySelectorAll(selector)];
          const composers = roots.flatMap((root) => [...root.querySelectorAll('textarea[aria-label="消息"], [role="textbox"][aria-label="消息"]')]);
          const composer = composers.length === 1 ? composers[0] : null;
          const settings = globalThis.document.querySelector(".ja-settings");
          return { providerDialogPresent: dialog !== null, focusInsideProviderDialog: dialog?.contains(active) ?? false,
            trustDialogPresent: trustDialog !== null,
            focusInsideTrustDialog: trustDialog?.contains(active) ?? false,
            trustCancelButtonFocused: active === trustDialog?.querySelector(".ja-settings-dialog-actions > button:first-child"),
            trustConfirmButtonFocused: active === trustDialog?.querySelector(".ja-settings-dialog-actions > button:last-child"),
            activeTag: ["INPUT", "BUTTON", "DIV", "TEXTAREA", "SELECT", "P", "BODY"].includes(active?.tagName) ? active.tagName : "OTHER",
            focusIsInput: active?.matches("input") ?? false,
            focusIsButton: active?.matches("button") ?? false,
            focusDisabled: active?.matches(":disabled") ?? false,
            focusIsSheetContainer: active === dialog,
            focusIsAlert: active?.getAttribute("role") === "alert",
            providerNameFocused: active === globalThis.document.getElementById("provider-name"),
            providerSecretFocused: active === globalThis.document.getElementById("provider-secret"),
            providerSaveButtonFocused: active === dialog?.querySelector(".ja-settings-sheet-footer > button:last-child"),
            providerCloseButtonFocused: active?.getAttribute("aria-label") === "关闭供应商编辑",
            settingsPresent: settings !== null,
            settingsHasLayout: (settings?.getClientRects().length ?? 0) > 0,
            mainConversationRootCount: roots.length,
            composerCount: composers.length,
            composerPresent: composer !== null,
            composerHasLayout: (composer?.getClientRects().length ?? 0) > 0,
            composerDisabled: composer?.matches(":disabled") ?? false,
            composerAriaDisabled: composer?.getAttribute("aria-disabled") === "true",
            composerReadOnly: composer?.readOnly === true || composer?.getAttribute("aria-readonly") === "true",
            composerWithinInert: (composer?.closest("[inert]") ?? null) !== null,
            composerVisible: composer !== null && composer.getClientRects().length > 0 &&
              globalThis.getComputedStyle(composer).visibility === "visible" && composer.closest("[hidden]") === null,
            selectedProjectPresent: globalThis.document.querySelector('[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]') !== null,
            currentThreadPresent: Boolean(roots[0]?.querySelector(".ja-chat-timeline")?.getAttribute("data-thread-id")),
            composerFocused: active === composer };
        }, mainConversationSelector);
        error.settingsSaveFacts = await page.evaluate(() => {
          const alerts = [...globalThis.document.querySelectorAll(".ja-settings-provider-sheet [role=alert]")]
            .map((element) => element.textContent ?? "");
          return { nativeCounts: globalThis.__JA_SETTINGS_SAVE_COUNTS__ === undefined ? null : { ...globalThis.__JA_SETTINGS_SAVE_COUNTS__ },
            partialSavedMessageShown: alerts.some((text) => text.includes("配置已保存")),
            inputRetainedMessageShown: alerts.some((text) => text.includes("输入已保留")) };
        });
        error.settingsSaveFacts.provider = await providerReadback(page);
        error.settingsTrustFacts = { nativeFacts: await projectTrustFacts(page),
          context: await conversationContextReadback(page) };
      } catch { error.settingsFocusFacts ??= { unavailable: true }; }
    }
    throw error;
  }
}

/** 以真实 PTY 输出验证 xterm 字号原位变化与 scrollback 保留，避免只验证外层 CSS。 */
async function verifyTerminalFont(page) {
  preferencesStep = "cold_terminal_font_roundtrip";
  await returnFromSettingsToComposer(page, "existing_cold_terminal");
  preferencesStep = "cold_terminal_font_roundtrip";
  const showWorkbench = page.getByRole("button", { name: "显示工作区面板", exact: true });
  if (await showWorkbench.isVisible()) await showWorkbench.click();
  await page.getByRole("button", { name: /^终端/ }).click();
  const rows = page.locator(".xterm-rows");
  await expect(rows).toBeVisible({ timeout: 30000 });
  await expect
    .poll(() => rows.evaluate((element) => globalThis.getComputedStyle(element).fontSize))
    .toBe("16px");
  await page.locator(".xterm-helper-textarea").focus();
  await page.keyboard.type("Write-Output SETTINGS_FONT_MARKER");
  await page.keyboard.press("Enter");
  await expect(rows).toContainText("SETTINGS_FONT_MARKER");
  await openSettings(page, "外观");
  await select(page, "代码与终端字号", "14px");
  await page.getByRole("button", { name: "返回应用", exact: true }).click();
  await expect(rows).toBeVisible();
  await expect
    .poll(() => rows.evaluate((element) => globalThis.getComputedStyle(element).fontSize))
    .toBe("14px");
  await expect(rows).toContainText("SETTINGS_FONT_MARKER");
  await capture(page, "10-terminal-font-roundtrip");
  await openSettings(page, "外观");
  await select(page, "代码与终端字号", "16px");
}

/** 测试仅保留截图证据；fixture server与CDP transport在失败时也立即释放。 */
async function main() {
  validateEnvironment();
  await mkdir(artifacts, { recursive: true });
  const provider = restart ? undefined : await startProvider();
  const browser = await chromium.connectOverCDP(endpoint);
  try {
    const page = browser.contexts().flatMap((context) => context.pages())[0];
    if (!page) throw new Error("没有隔离 WebView2 页面");
    await page.setViewportSize({ width: 1280, height: 820 });
    await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
    if (restart) await verifyRestart(page);
    else await verify(page, provider);
    process.stdout.write(
      `JA_SETTINGS_PREFERENCES_OK phase=${restart ? "restart" : "controls"} evidence=${artifacts}\n`,
    );
  } finally {
    await browser.close();
    provider?.server.closeAllConnections();
    if (provider) await new Promise((resolve) => provider.server.close(resolve));
  }
}

if (process.argv[1] !== undefined && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await main();
}
