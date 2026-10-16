// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Installed Native Tauri/WebView2 acceptance through the user's copied real model configuration.
 * All prompts are sent through the actual Composer; Tauri reads only bootstrap, task and evidence facts.
 */

import assert from "node:assert/strict";
import { access, mkdir, readFile, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import process from "node:process";
import { chromium } from "@playwright/test";

const endpoint = process.env.JA_REAL_MODEL_DESKTOP_CDP_ENDPOINT?.trim();
const evidenceDirectory = process.env.JA_REAL_MODEL_DESKTOP_EVIDENCE_DIR?.trim();
const workspacePath = process.env.JA_REAL_MODEL_DESKTOP_WORKSPACE?.trim();
const approvalWriteDirectory = process.env.JA_REAL_MODEL_DESKTOP_WRITE_DIR?.trim();
const scenario = process.env.JA_REAL_MODEL_DESKTOP_SCENARIO?.trim();
const EXPECTED_PROVIDER_ID = "provider_migrated_fd8586ce8ceafbd77fb9b933";
const EXPECTED_MODEL_ID = "model_5bff936c5d7d45ac8297069cf9d686a7";
const CHILD_MARKER = "JA_REAL_MODEL_SUBAGENT_RESULT_323";
const APPROVAL_ALLOW = "JA_REAL_MODEL_APPROVAL_ALLOW";
const APPROVAL_DENY = "JA_REAL_MODEL_APPROVAL_DENY";
const BTW_FIRST = "JA_REAL_MODEL_BTW_FIRST";
const BTW_SECOND = "JA_REAL_MODEL_BTW_SECOND";
const BTW_IDLE = "JA_REAL_MODEL_BTW_IDLE_REPLY";
let activePhase = "driver_startup";
let submittedModelTurnCount = 0;
let connectedBrowser;

/** 在私有报告中标出最后完成的真实 UI 阶段，区分配置/导航失败与已发送模型 Turn。@author kongweiguang */
function setEvidencePhase(phase) {
  activePhase = phase;
}

/** 等待有业务含义的状态边沿；超时保留当前 phase，便于区分模型阻塞与 UI 导航失败。@author kongweiguang */
async function waitForCondition(label, predicate, timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) return;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 200));
  }
  throw new Error(`${label} 未在期限内完成`);
}

/** 经 Tauri bridge 发起窄命令；错误正文可能含路径或运行细节，因此只回传稳定错误码。@author kongweiguang */
async function invoke(page, command, input = {}) {
  return page.evaluate(async ({ commandName, commandInput }) => {
    const bridge = globalThis.__TAURI_INTERNALS__?.invoke;
    if (typeof bridge !== "function") return { ok: false, code: "TAURI_BRIDGE_UNAVAILABLE" };
    try {
      return { ok: true, value: await bridge(commandName, { input: commandInput }) };
    } catch (error) {
      const candidate = error !== null && typeof error === "object" ? error : {};
      const code = [candidate.code, candidate.error?.code, candidate.errorCode].find(
        (value) => typeof value === "string" && /^[A-Z][A-Z0-9_]{2,63}$/u.test(value),
      );
      return { ok: false, code: code ?? "RPC_REJECTED" };
    }
  }, { commandName: command, commandInput: input });
}

/** 将桥接失败收敛为无敏感正文的验收错误。@author kongweiguang */
function requireInvoke(result, operation) {
  if (result?.ok !== true) throw new Error(`${operation} 失败：${result?.code ?? "RPC_REJECTED"}`);
  return result.value;
}

/** 通过 Tauri 启动原生 Workspace owner，再从权威回执验证工作区只指向本轮临时目录。@author kongweiguang */
async function createIsolatedWorkspace(page) {
  const opened = requireInvoke(
    await invoke(page, "ja_runtime_workspace_open", {
      cwd: workspacePath,
      displayName: "Ja Real Model Acceptance",
    }),
    "隔离 Workspace 打开",
  );
  const expectedRoot = resolve(workspacePath).replaceAll("/", "\\").replace(/\\+$/u, "").toLowerCase();
  const actualRoot = resolve(opened.root).replaceAll("/", "\\").replace(/\\+$/u, "").toLowerCase();
  assert.equal(actualRoot, expectedRoot, "Native Workspace canonical root 脱离隔离目录");
  assert.equal(opened.kind, "project", "验收目录没有以 Project Workspace 登记");
  const trust = requireInvoke(
    await invoke(page, "ja_workspace_set_trust", {
      workspaceId: opened.workspaceId,
      trust: "trusted",
    }),
    "隔离 Workspace 授信",
  );
  assert.equal(trust.accepted, true);
  const activated = requireInvoke(
    await invoke(page, "ja_runtime_workspace_activate", { workspaceId: opened.workspaceId }),
    "隔离 Workspace 激活",
  );
  assert.equal(activated.workspaceId, opened.workspaceId, "激活结果更换了 Workspace identity");
  return {
    workspaceId: opened.workspaceId,
    kind: opened.kind,
    trust: "trusted",
    canonicalRootMatched: true,
  };
}

/** 只在页面内将脱敏配置裁剪成 provider/model/reasoning/subagent 元数据，原始配置绝不离开 WebView。@author kongweiguang */
async function readRealModelConfiguration(page) {
  const projection = await page.evaluate(async () => {
    const bridge = globalThis.__TAURI_INTERNALS__?.invoke;
    if (typeof bridge !== "function") throw new Error("Tauri bridge unavailable");
    let document;
    try {
      document = await bridge("ja_configuration_read", { input: {} });
    } catch {
      throw new Error("脱敏配置读取失败");
    }
    const source = document?.effective ?? {};
    const providerId = source.default_provider_id;
    const modelId = source.default_model_id;
    const provider = Array.isArray(source.providers)
      ? source.providers.find((entry) => entry?.provider_id === providerId)
      : undefined;
    const model = provider?.models?.find((entry) => entry?.model_id === modelId);
    const credential = provider?.credential_id;
    const credentialState = credential ? document?.credentials?.[credential]?.configured : undefined;
    const subagents = source.subagents ?? {};
    return {
      providerId: typeof providerId === "string" ? providerId : undefined,
      modelId: typeof modelId === "string" ? modelId : undefined,
      model: typeof model?.model === "string" ? model.model : undefined,
      reasoningLevel: typeof source.default_reasoning_level === "string"
        ? source.default_reasoning_level
        : null,
      credentialConfigured: credentialState === true,
      userLayer: {
        present: document?.user?.present === true,
        trusted: document?.user?.trusted === true,
        status: typeof document?.user?.status === "string" ? document.user.status : null,
      },
      subagents: {
        enabled: subagents.enabled === true,
        providerOverride: subagents.provider_id ?? null,
        modelOverride: subagents.model_id ?? null,
        reasoningOverride: subagents.reasoning_level ?? null,
      },
    };
  });
  assert.equal(projection.providerId, EXPECTED_PROVIDER_ID, "实际 Provider 与本轮预核配置不符");
  assert.equal(projection.modelId, EXPECTED_MODEL_ID, "实际默认模型与本轮预核配置不符");
  assert.equal(projection.model, "gpt-6-luna", "默认模型标签不是 gpt-6-luna");
  assert.equal(projection.reasoningLevel, "max", "实际思考等级不是 max");
  assert.equal(projection.credentialConfigured, true, "真实凭据在隔离 JA_HOME 中不可用");
  assert.deepEqual(projection.userLayer, {
    present: true,
    trusted: true,
    status: "valid",
  }, "隔离 JA_HOME 的用户配置层未通过 App Server 信任与有效性校验");
  assert.deepEqual(projection.subagents, {
    enabled: true,
    providerOverride: null,
    modelOverride: null,
    reasoningOverride: null,
  });
  return projection;
}

/** 选择实际创建的隔离 Project，防止首次会话落到自动 Session 或用户默认 Workspace。@author kongweiguang */
async function selectIsolatedProject(page, workspaceId) {
  await page.reload({ waitUntil: "domcontentloaded", timeout: 30_000 });
  const row = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: "Ja Real Model Acceptance" });
  await row.waitFor({ state: "visible", timeout: 30_000 });
  assert.equal(await row.locator("xpath=..").getAttribute("data-workspace-id"), workspaceId);
  await row.click();
  await waitForCondition("隔离 Project 成为当前 Workspace", async () =>
    await row.getAttribute("aria-current") === "page");
}

/** 从当前生产导航读取被选中的 Thread ID；不从 DOM 顺序推断新会话身份。@author kongweiguang */
async function currentThreadId(page) {
  const button = page.locator(
    '.ja-navigation-thread-row button[data-thread-id][aria-current="page"], [aria-label="最近对话列表"] button[data-thread-id][aria-current="page"]',
  ).first();
  await button.waitFor({ state: "visible", timeout: 20_000 });
  const threadId = await button.getAttribute("data-thread-id");
  if (threadId === null) throw new Error("当前导航项缺少 Java Thread identity");
  return threadId;
}

/** 新会话由真实导航动作创建；父级保留 full_access，审批仅在稍后显式收紧的子任务上触发。@author kongweiguang */
async function prepareRootComposer(page) {
  const newConversation = page.getByRole("button", { name: "新会话", exact: true });
  await newConversation.waitFor({ state: "visible", timeout: 20_000 });
  await newConversation.click();
  const composer = page.locator(".ja-conversation .ja-composer").first();
  await composer.waitFor({ state: "visible", timeout: 20_000 });
  const modelButton = composer.locator('.ja-composer__model-trigger[aria-label]');
  const modelLabel = await modelButton.getAttribute("aria-label");
  assert.match(modelLabel ?? "", /gpt-6-luna/u, "Composer 没有使用预核实的真实模型");
  const accessMode = composer.getByRole("combobox", { name: "访问模式", exact: true });
  await accessMode.click();
  await page.getByRole("option", { name: "完全访问", exact: true }).click();
  await waitForCondition("新 Thread 访问模式设为完全访问", async () => {
    const value = await accessMode.getAttribute("data-value");
    const text = await accessMode.innerText().catch(() => "");
    return value === "full_access" || text.includes("完全访问");
  });
  return { composer, modelLabel, accessMode: "full_access" };
}

/** 读取有限大小的权威 Thread snapshot；调用方只派生 task、turn 和 item 结构事实。@author kongweiguang */
async function readThread(page, threadId) {
  return requireInvoke(
    await invoke(page, "ja_thread_read", { threadId, limit: 200 }),
    "Thread history 回读",
  );
}

/** 同时要求 Task 真状态和父 Thread 最新 activity 收敛，避免 UI 旧卡片冒充真实完成。@author kongweiguang */
async function waitForOwnedTaskState(page, rootThreadId, taskThreadId, expectedState, timeoutMs = 180_000) {
  let latest;
  await waitForCondition(`Task 与父 Activity 收敛到 ${expectedState}`, async () => {
    const tasks = requireInvoke(
      await invoke(page, "ja_runtime_task_list", { rootThreadId }),
      "委派 Task list",
    );
    const task = tasks.items?.find((entry) => entry.taskThreadId === taskThreadId);
    const owner = await readThread(page, rootThreadId);
    const activity = (owner.taskActivities ?? [])
      .filter((entry) => entry.task?.taskThreadId === taskThreadId)
      .reduce((current, entry) =>
        current === undefined || entry.activity?.activitySequence > current.activity.activitySequence
          ? entry
          : current, undefined);
    latest = { task, ownerTask: activity?.task, activity: activity?.activity };
    return task?.state === expectedState && latest.ownerTask?.state === expectedState;
  }, timeoutMs);
  return latest;
}

/** 通过真实 Composer 发出用户输入；busy true→false 是单轮完成的 UI 边界，不使用固定等待猜测模型状态。@author kongweiguang */
async function submitComposerPrompt(page, composer, prompt, { waitForCompletion = true } = {}) {
  const input = composer.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(prompt);
  submittedModelTurnCount += 1;
  setEvidencePhase(`model_turn_${submittedModelTurnCount}_submitted`);
  await composer.getByRole("button", { name: "发送", exact: true }).click();
  if (!waitForCompletion) return;
  let sawBusy = false;
  await waitForCondition("真实模型 Turn 完成", async () => {
    const busy = await composer.getAttribute("aria-busy");
    if (busy === "true") sawBusy = true;
    return sawBusy && busy !== "true";
  }, 180_000);
  setEvidencePhase(`model_turn_${submittedModelTurnCount}_ui_idle`);
}

/** 只在持久 final_answer item 中匹配真实助手结果，拒绝 user_input、brief 或进行中 item 命中 marker。@author kongweiguang */
function finalAnswerContains(thread, marker) {
  return (thread?.items ?? []).some(
    (item) => item.kind === "final_answer" && typeof item.text === "string" && item.text.includes(marker),
  );
}

/** 从受信 thread snapshot 读取稳定完成态；最后一条 Turn 可以等待 running→completed 收敛。@author kongweiguang */
function latestTurnCompleted(thread) {
  const latest = thread?.turns?.at(-1);
  return latest?.status === "completed";
}

/** 审批关闭后先等待父模型调用自然收敛，避免把新输入并入仍在运行的旧 request。@author kongweiguang */
async function waitForComposerIdle(composer, label) {
  await waitForCondition(`${label} Composer 空闲`, async () =>
    (await composer.getAttribute("aria-busy")) !== "true",
  180_000);
}

/** 从空历史创建一个独立真实子任务并回读 child final item 与父 activity；不解析或保存模型全文。@author kongweiguang */
async function runRealSubagent(page, workspaceId, composer) {
  setEvidencePhase("subagent_parent_turn");
  await submitComposerPrompt(
    page,
    composer,
    `请用 spawn_agent 创建且只创建一个子任务，任务名为 Ja Real Model Child。brief 要求子任务计算 17×19，并只回复 ${CHILD_MARKER}。不要在主任务直接计算；创建后必须对该唯一子任务调用 wait_agent，等它真实完成，再向我报告它的确切回复。没有看到 wait_agent 成功结果和子任务答复时不要声称完成。`,
  );
  const rootThreadId = await currentThreadId(page);
  const threadPage = requireInvoke(
    await invoke(page, "ja_thread_list", { workspaceId, limit: 200 }),
    "隔离 Thread 偏好回读",
  );
  const rootThread = threadPage.items?.find((entry) => entry.threadId === rootThreadId);
  assert.equal(rootThread?.preferences?.providerId, EXPECTED_PROVIDER_ID);
  assert.equal(rootThread?.preferences?.modelId, EXPECTED_MODEL_ID);
  assert.equal(rootThread?.preferences?.reasoningLevel, "max");
  assert.equal(rootThread?.preferences?.accessMode, "full_access");
  const baseline = new Set();
  let task;
  await waitForCondition("真实 spawn_agent Task 创建", async () => {
    const current = requireInvoke(await invoke(page, "ja_runtime_task_list", { rootThreadId }), "子任务目录");
    task = (current.items ?? []).find((entry) =>
      entry.taskKind === "subagent" && entry.parentThreadId === rootThreadId && !baseline.has(entry.taskThreadId));
    return task !== undefined;
  }, 180_000);
  assert.equal(task.rootThreadId, rootThreadId);
  const terminal = await waitForOwnedTaskState(page, rootThreadId, task.taskThreadId, "completed");
  const child = await readThread(page, task.taskThreadId);
  const childContainsMarker = finalAnswerContains(child, CHILD_MARKER);
  assert.equal(childContainsMarker, true, "子任务 transcript 缺少要求的真实结果 marker");
  const rootHistory = await readThread(page, rootThreadId);
  const successfulSpawnCalls = (rootHistory.items ?? []).filter(
    (item) => item.kind === "tool_call" && item.toolName === "spawn_agent" && item.presentation?.status === "success",
  );
  const successfulWaitCalls = (rootHistory.items ?? []).filter(
    (item) => item.kind === "tool_call" && item.toolName === "wait_agent" && item.presentation?.status === "success",
  );
  assert.equal(successfulSpawnCalls.length, 1, "父历史没有恰好一个成功的原生 spawn_agent ToolCall");
  assert.ok(successfulWaitCalls.length >= 1, "父历史没有成功的原生 wait_agent ToolCall");
  assert.equal(latestTurnCompleted(rootHistory), true, "父 Thread 最后 Turn 尚未持久完成");
  assert.equal(finalAnswerContains(rootHistory, CHILD_MARKER), true, "父助手最终答复没有回读子任务结果");
  return {
    taskThreadId: task.taskThreadId,
    parentThreadId: task.parentThreadId,
    rootThreadId: task.rootThreadId,
    ownerState: terminal.ownerTask?.state,
    state: terminal.task.state,
    ownerActivityKind: terminal.activity?.kind,
    childResultMarkerObserved: childContainsMarker,
    rootSpawnAgentToolCallsSucceeded: successfulSpawnCalls.length,
    rootWaitAgentToolCallsSucceeded: successfulWaitCalls.length,
    parentFinalAnswerContainsChildResult: true,
  };
}

/** 最新 owner Activity 提供 UI 状态文案；只有该父任务入口能打开对应 subagent 详情。@author kongweiguang */
async function openSubagentDetail(page, task, state) {
  const stateLabels = { queued: "排队中", running: "运行中", waiting_approval: "等待确认", completed: "已完成", failed: "失败", cancelled: "已取消" };
  const button = page.getByRole("button", {
    name: `打开${task.taskName}，${stateLabels[state] ?? state}`,
    exact: true,
  });
  await button.waitFor({ state: "visible", timeout: 30_000 });
  await button.click();
  await page.locator(`[data-workbench-tab="subagent:${task.taskThreadId}"]`).waitFor({ state: "visible", timeout: 30_000 });
  const region = page.locator(".ja-task-detail:visible").last();
  await region.waitFor({ state: "visible", timeout: 30_000 });
  await waitForCondition("打开目标 subagent 详情", async () =>
    await region.getAttribute("aria-label") === task.taskName);
  return region;
}

/** 强制模型仅以真实 exec_command 向本轮目录写 marker；文件验证留给隔离 harness，避免额外 read 审批。@author kongweiguang */
async function runApprovalCase(page, workspaceId, rootThreadId, composer, key) {
  const marker = key === "allow" ? APPROVAL_ALLOW : APPROVAL_DENY;
  const fileName = `approval-${key}.txt`;
  const filePath = join(approvalWriteDirectory, fileName);
  try {
    await access(filePath);
    throw new Error(`审批 ${key} 文件在执行前已存在`);
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
  await mkdir(approvalWriteDirectory, { recursive: true });
  const before = requireInvoke(await invoke(page, "ja_runtime_task_list", { rootThreadId }), "审批 Task 基线");
  const baseline = new Set((before.items ?? []).map((task) => task.taskThreadId));
  const quotedPath = filePath.replaceAll("'", "''");
  const prompt = `请用 spawn_agent 创建且只创建一个子任务，任务名为 Ja Real Approval ${key}。spawn_agent 参数 accessMode 必须设为 approval_required。brief 必须要求它只调用一次 exec_command 执行 pwsh -NoProfile -Command "Set-Content -LiteralPath '${quotedPath}' -Value '${marker}' -NoNewline"，不要调用其他写入工具或写其他位置。${key === "deny" ? "若命令遭到拒绝，不要重试，直接如实汇报拒绝结果。" : "等待该 exec_command 的真实 ToolResult，再仅根据 ToolResult 汇报成功或失败；不要调用 read 或任何其他工具，也不要重新尝试命令。"} 主任务不要直接运行该命令；隔离 harness 会独立验证文件是否创建。`;
  await submitComposerPrompt(page, composer, prompt, { waitForCompletion: false });
  let task;
  await waitForCondition(`审批 ${key} child 创建`, async () => {
    const current = requireInvoke(await invoke(page, "ja_runtime_task_list", { rootThreadId }), "审批 Task 目录");
    task = (current.items ?? []).find((entry) =>
      entry.taskKind === "subagent" && entry.parentThreadId === rootThreadId && !baseline.has(entry.taskThreadId));
    return task !== undefined;
  }, 180_000);
  await waitForOwnedTaskState(page, rootThreadId, task.taskThreadId, "waiting_approval");
  const region = await openSubagentDetail(page, task, "waiting_approval");
  const snapshot = await readThread(page, task.taskThreadId);
  const pending = (snapshot.items ?? []).filter((item) =>
    item.kind === "approval" && item.toolName === "exec_command" && item.decision === null);
  assert.equal(pending.length, 1, "child history 没有唯一待处理的真实 exec_command 审批");
  const approvalId = pending[0].approvalId;
  const card = region.locator(".ja-approval-card").filter({ hasText: "工具调用需要确认" });
  await card.waitFor({ state: "visible", timeout: 30_000 });
  assert.equal(await card.count(), 1, "详情中没有唯一待确认审批卡");
  await card.getByRole("heading", { name: "工具调用需要确认", exact: true })
    .waitFor({ state: "visible", timeout: 30_000 });
  const decision = key === "allow" ? "批准" : "拒绝";
  await card.getByRole("button", { name: decision, exact: true }).click();
  const terminalHeading = key === "allow" ? "工具调用已批准" : "工具调用已拒绝";
  await region.getByRole("heading", { name: terminalHeading, exact: true })
    .waitFor({ state: "visible", timeout: 30_000 });
  const terminal = await waitForOwnedTaskState(page, rootThreadId, task.taskThreadId, "completed", 180_000);
  await waitForComposerIdle(composer, `审批 ${key} 父 Turn`);
  await waitForCondition(`审批 ${key} 父 Thread Turn 完成`, async () => {
    const listed = requireInvoke(
      await invoke(page, "ja_thread_list", { workspaceId, limit: 200 }),
      "审批父 Thread state",
    );
    const root = listed.items?.find((entry) => entry.threadId === rootThreadId);
    return root?.latestTurnStatus === "completed";
  }, 180_000);
  const finalSnapshot = await readThread(page, task.taskThreadId);
  const toolCalls = (finalSnapshot.items ?? []).filter((item) =>
    item.kind === "tool_call" && item.toolName === "exec_command");
  assert.equal(toolCalls.length, 1, "子任务 exec_command 不是恰好一次");
  const expectedPresentationStatus = key === "allow" ? "success" : "error";
  assert.equal(
    toolCalls[0].presentation?.status,
    expectedPresentationStatus,
    "持久 Tool 展示结果与审批动作不一致",
  );
  const closedApproval = (finalSnapshot.items ?? []).find(
    (item) => item.kind === "approval" && item.approvalId === approvalId,
  );
  const expectedDecision = key === "allow" ? "approve" : "deny";
  assert.equal(closedApproval?.decision, expectedDecision, "审批历史决定与用户 UI 动作不一致");
  const fileExists = await access(filePath).then(() => true, () => false);
  if (key === "allow") {
    assert.equal(fileExists, true, "批准后临时工作区文件未创建");
    assert.equal(await readFile(filePath, "utf8"), marker);
  } else {
    assert.equal(fileExists, false, "拒绝后临时工作区出现文件副作用");
  }
  return {
    taskThreadId: task.taskThreadId,
    approvalId,
    state: terminal.task.state,
    ownerState: terminal.ownerTask?.state,
    ownerActivityKind: terminal.activity?.kind,
    decision: expectedDecision,
    oneExecCommand: toolCalls.length === 1,
    execPresentationStatus: toolCalls[0].presentation.status,
    fileExists,
    fileContentMatches: key === "allow" ? fileExists && (await readFile(filePath, "utf8")) === marker : false,
  };
}

/** 先验证空 /btw 无自动模型轮次，再真实发送空侧聊消息及非空首发/追问，最后验证归属与 UI 清理。@author kongweiguang */
async function runBtwLifecycle(page, rootThreadId, composer) {
  const rootBefore = await readThread(page, rootThreadId);
  const knownTabs = new Set(await page.locator('[data-workbench-tab^="side-task:thr_"]').evaluateAll((tabs) =>
    tabs.map((tab) => tab.getAttribute("data-workbench-tab")).filter(Boolean)));
  const mainInput = composer.getByRole("textbox", { name: "消息", exact: true });
  await mainInput.fill("/btw");
  await mainInput.press("Enter");
  let idleTabKey;
  await waitForCondition("/btw 空命令创建空闲 Thread", async () => {
    const tabs = await page.locator('[data-workbench-tab^="side-task:thr_"]').evaluateAll((elements) =>
      elements.map((element) => element.getAttribute("data-workbench-tab")).filter(Boolean));
    idleTabKey = tabs.find((key) => !knownTabs.has(key));
    return idleTabKey !== undefined;
  }, 60_000);
  const idleThreadId = idleTabKey.slice("side-task:".length);
  const idleHistory = await readThread(page, idleThreadId);
  assert.equal((idleHistory.turns ?? []).length, 0, "/btw 空命令触发了模型 Turn");
  assert.equal((await readThread(page, rootThreadId)).turns.length, rootBefore.turns.length);
  const idleRegion = page.locator(".ja-task-detail:visible").last();
  const idleComposer = idleRegion.locator(".ja-composer");
  await idleComposer.waitFor({ state: "visible", timeout: 30_000 });
  await submitComposerPrompt(page, idleComposer, `请只回复 ${BTW_IDLE}`);
  let idleCompletedHistory;
  await waitForCondition("空 /btw 主动首发的助手答复持久完成", async () => {
    idleCompletedHistory = await readThread(page, idleThreadId);
    return latestTurnCompleted(idleCompletedHistory) && finalAnswerContains(idleCompletedHistory, BTW_IDLE);
  }, 180_000);

  const beforeContentTabs = new Set([idleTabKey, ...knownTabs]);
  await composer.getByRole("textbox", { name: "消息", exact: true }).fill(`/btw ${BTW_FIRST} 请只回复 ${BTW_FIRST}`);
  await composer.getByRole("textbox", { name: "消息", exact: true }).press("Enter");
  let contentTabKey;
  await waitForCondition("/btw 正文命令创建独立 Thread", async () => {
    const tabs = await page.locator('[data-workbench-tab^="side-task:thr_"]').evaluateAll((elements) =>
      elements.map((element) => element.getAttribute("data-workbench-tab")).filter(Boolean));
    contentTabKey = tabs.find((key) => !beforeContentTabs.has(key));
    return contentTabKey !== undefined;
  }, 60_000);
  const contentThreadId = contentTabKey.slice("side-task:".length);
  const contentRegion = page.locator(".ja-task-detail:visible").last();
  const contentComposer = contentRegion.locator(".ja-composer");
  await contentComposer.waitFor({ state: "visible", timeout: 30_000 });
  let firstHistory;
  await waitForCondition("/btw 首条独立回答持久化", async () => {
    firstHistory = await readThread(page, contentThreadId);
    return latestTurnCompleted(firstHistory) && finalAnswerContains(firstHistory, BTW_FIRST);
  }, 180_000);
  const rootAfterFirst = await readThread(page, rootThreadId);
  assert.equal(rootAfterFirst.turns.length, rootBefore.turns.length, "/btw 正文增加了主会话 Turn");
  await submitComposerPrompt(page, contentComposer, `这是同一侧聊的独立追问，请只回复 ${BTW_SECOND}`);
  let secondHistory;
  await waitForCondition("/btw 独立追问的助手答复持久完成", async () => {
    secondHistory = await readThread(page, contentThreadId);
    return latestTurnCompleted(secondHistory) && finalAnswerContains(secondHistory, BTW_SECOND);
  }, 180_000);
  assert.ok(secondHistory.turns.length > firstHistory.turns.length, "侧聊追问没有创建独立 Turn");
  assert.equal((await readThread(page, rootThreadId)).turns.length, rootBefore.turns.length);

  await page.locator(`[data-workbench-tab="side-task:${idleThreadId}"]`)
    .locator("..").locator('[data-tab-close="true"]').click();
  await page.locator(`[data-workbench-tab="side-task:${contentThreadId}"]`)
    .locator("..").locator('[data-tab-close="true"]').click();
  await waitForCondition("/btw 两个侧聊 UI 关闭完成", async () =>
    (await page.locator(`[data-workbench-tab="side-task:${idleThreadId}"], [data-workbench-tab="side-task:${contentThreadId}"]`).count()) === 0,
  60_000);
  const [idleAfterClose, contentAfterClose] = await Promise.all([
    invoke(page, "ja_thread_read", { threadId: idleThreadId, limit: 20 }),
    invoke(page, "ja_thread_read", { threadId: contentThreadId, limit: 20 }),
  ]);
  assert.equal(idleAfterClose.ok, false, "空闲 /btw Thread 在关闭后仍可读取");
  assert.equal(contentAfterClose.ok, false, "有正文 /btw Thread 在关闭后仍可读取");
  const rootRow = page.locator(`[data-thread-id="${rootThreadId}"]`).first();
  await rootRow.click();
  await page.locator(".ja-conversation .ja-composer").waitFor({ state: "visible", timeout: 20_000 });
  return {
    idleThreadId,
    emptyTurnCount: idleHistory.turns.length,
    emptySideChatTurnCount: idleCompletedHistory.turns.length,
    emptySideChatAssistantReplyObserved: true,
    contentThreadId,
    firstTurnCount: firstHistory.turns.length,
    independentReplyTurnCount: secondHistory.turns.length - firstHistory.turns.length,
    rootTurnCountUnchanged: true,
    returnedToRoot: true,
    closedThreadIds: [idleThreadId, contentThreadId],
    cleanupVerified: true,
  };
}

/** 无模型 smoke 仅验证实际 provider config 和私有 Workspace；主验收则顺序运行委派、审批与 BTW。@author kongweiguang */
async function main() {
  setEvidencePhase("desktop_driver_preflight");
  if (process.platform !== "win32") throw new Error("真实模型桌面验收仅支持 Windows");
  if (!/^http:\/\/127\.0\.0\.1:\d+$/u.test(endpoint ?? "") || ![evidenceDirectory, workspacePath, approvalWriteDirectory].every((value) => value && resolve(value) === value))
    throw new Error("真实模型桌面验收环境不完整或路径不是绝对路径");
  if (!new Set(["real-model", "real-model-smoke"]).has(scenario ?? ""))
    throw new Error("真实模型桌面验收场景无效");
  await mkdir(evidenceDirectory, { recursive: true });
  connectedBrowser = await chromium.connectOverCDP(endpoint);
  const browser = connectedBrowser;
  setEvidencePhase("private_desktop_cdp_connected");
  const context = browser.contexts()[0];
  if (context === undefined) throw new Error("WebView2 CDP 没有 default browser context");
  const pages = context.pages();
  let page;
  await waitForCondition("Tauri 页面和实际桥接就绪", async () => {
    for (const candidate of pages) {
      if (await candidate.evaluate(() => typeof globalThis.__TAURI_INTERNALS__?.invoke === "function").catch(() => false)) {
        page = candidate;
        return true;
      }
    }
    return false;
  }, 60_000);
  page.setDefaultTimeout(30_000);
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({ state: "visible", timeout: 60_000 });
  await page.locator(".ja-conversation .ja-composer").first().waitFor({ state: "visible", timeout: 60_000 });
  const configuration = await readRealModelConfiguration(page);
  setEvidencePhase("real_model_configuration_validated");
  const workspace = await createIsolatedWorkspace(page);
  setEvidencePhase("isolated_workspace_ready");
  if (scenario === "real-model-smoke") {
    await writeReport({
      status: "real_model_desktop_smoke_passed",
      model: configuration,
      workspace,
      modelTurnsSent: 0,
      cdpConnected: true,
    });
    await browser.close();
    connectedBrowser = undefined;
    return;
  }
  await selectIsolatedProject(page, workspace.workspaceId);
  setEvidencePhase("isolated_project_selected");
  const root = await prepareRootComposer(page);
  const subagent = await runRealSubagent(page, workspace.workspaceId, root.composer);
  const rootThreadId = subagent.rootThreadId;
  const approvals = {
    allow: await runApprovalCase(page, workspace.workspaceId, rootThreadId, root.composer, "allow"),
    deny: await runApprovalCase(page, workspace.workspaceId, rootThreadId, root.composer, "deny"),
  };
  const btw = await runBtwLifecycle(page, rootThreadId, root.composer);
  const report = {
    status: "real_model_desktop_passed",
    installedRuntime: "native_tauri_webview2",
    model: configuration,
    workspace,
    rootThreadId,
    rootModelLabel: root.modelLabel,
    rootAccessMode: root.accessMode,
    subagent,
    approvals,
    btw,
    realModelProviderTurnsObserved: true,
  };
  await writeReport(report);
  await browser.close();
  connectedBrowser = undefined;
}

/** 写出白名单证据字段；调用方不传完整配置、模型全文、工具参数或错误正文。@author kongweiguang */
async function writeReport(report) {
  const safeReport = JSON.parse(JSON.stringify(report));
  await writeFile(join(evidenceDirectory, "real-model-desktop.json"), `${JSON.stringify(safeReport, null, 2)}\n`, "utf8");
}

main().catch(async (error) => {
  await connectedBrowser?.close().catch(() => undefined);
  connectedBrowser = undefined;
  const code = typeof error?.code === "string" ? error.code : "REAL_MODEL_DESKTOP_FAILED";
  const message = error instanceof Error ? error.message : "unknown error";
  const failure = {
    status: "real_model_desktop_failed",
    phase: activePhase,
    errorCode: code,
    error: message.replace(/(?:[A-Z]:\\|\\\\)[^\s'"<>]+/giu, "<path>"),
    submittedModelTurnCount,
    modelTurnsMayHaveBeenSent: submittedModelTurnCount > 0,
  };
  if (evidenceDirectory && resolve(evidenceDirectory) === evidenceDirectory) {
    await mkdir(evidenceDirectory, { recursive: true }).catch(() => undefined);
    const { writeFile } = await import("node:fs/promises");
    await writeFile(join(evidenceDirectory, "real-model-desktop-failure.json"), `${JSON.stringify(failure, null, 2)}\n`, "utf8").catch(() => undefined);
  }
  process.stderr.write(`JA_REAL_MODEL_DESKTOP_FAILED ${code}\n`);
  process.exitCode = 1;
});
