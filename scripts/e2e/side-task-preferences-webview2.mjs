// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 侧边任务偏好与主任务隔离的 Windows/WebView2 真窗验收。
 *
 * 该 runner 只连接调用方显式提供的隔离 CDP endpoint；它不会启动、停止或重启
 * Tauri/Java 进程。Provider fixture 绑定 IPv4 loopback，并只保留模型、场景 marker
 * 和响应终态，避免把正文、凭据或用户文件写入证据。
 */

import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdir, writeFile } from "node:fs/promises";
import { isAbsolute, join } from "node:path";
import process from "node:process";
import { chromium, expect } from "@playwright/test";

const endpoint = process.env.JA_E2E_SIDE_TASK_CDP_ENDPOINT?.trim();
const evidenceDirectory = process.env.JA_E2E_SIDE_TASK_ARTIFACT_DIR?.trim();
const isolated = process.env.JA_E2E_SIDE_TASK_ISOLATED === "1";

const ROOT_MARKER = "JA_SIDE_TASK_PARENT_CONTEXT";
const SIDE_MARKER = "JA_SIDE_TASK_CHILD_MODEL_B";
const FOLLOWUP_MARKER = "JA_SIDE_TASK_CHILD_FOLLOWUP_MODEL_A";
const PLAN_MARKER = "JA_SIDE_TASK_PLAN_EXECUTION";
const PRIMARY_MODEL = "ja-side-primary";
const SECONDARY_MODEL = "ja-side-secondary";

/** 仅接受隔离 Windows WebView2 的 loopback CDP 和绝对证据目录。 */
function validateEnvironment() {
  if (process.platform !== "win32" || !isolated)
    throw new Error("侧边任务真窗验收必须在 Windows 隔离实例中设置 JA_E2E_SIDE_TASK_ISOLATED=1");
  if (!/^http:\/\/(?:127\.0\.0\.1|localhost):\d+\/?$/u.test(endpoint ?? ""))
    throw new Error("JA_E2E_SIDE_TASK_CDP_ENDPOINT 必须是 loopback CDP endpoint");
  if (evidenceDirectory === undefined || !isAbsolute(evidenceDirectory))
    throw new Error("JA_E2E_SIDE_TASK_ARTIFACT_DIR 必须是绝对目录");
}

/** 等待有限动画结束，截图只记录稳定的真实组件和全局样式。 */
async function settleVisuals(page) {
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
}

/** 保存当前 WebView2 真窗截图，路径不进入页面或 Provider 请求。 */
async function capture(page, name) {
  await settleVisuals(page);
  await page.screenshot({ path: join(evidenceDirectory, `${name}.png`), animations: "disabled" });
}

/** 使用真实 Anthropic Messages SSE 响应，记录请求模型与场景而不保存完整请求正文。 */
async function startLoopbackProvider() {
  const requests = [];
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
    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      response.writeHead(400).end();
      return;
    }
    const serialized = JSON.stringify(body);
    const evaluation = serialized.includes("independent acceptance evaluator");
    const marker = evaluation
      ? "evaluation"
      : serialized.includes(FOLLOWUP_MARKER)
        ? "followup"
        : serialized.includes(PLAN_MARKER)
          ? "plan"
          : serialized.includes(SIDE_MARKER)
            ? "side"
            : serialized.includes(ROOT_MARKER)
              ? "root"
              : "unknown";
    const model = typeof body.model === "string" ? body.model : "unknown";
    const requestRecord = {
      sequence: ++sequence,
      marker,
      model,
      hasParentMarker: serialized.includes(ROOT_MARKER),
    };
    requests.push(requestRecord);
    // 后续 Plan 请求留出真实停止窗口；取消后不向已关闭的响应写入。
    if (marker === "plan" && requests.filter((item) => item.marker === "plan").length > 1) {
      await new Promise((resolve) => setTimeout(resolve, 2_000));
      if (response.destroyed || response.writableEnded) return;
    }
    const text = evaluation
      ? JSON.stringify({ verdict: "met", summary: "隔离 Goal 已验证", criteria: [] })
      : marker === "followup"
        ? "JA_SIDE_TASK_FOLLOWUP_DONE"
        : marker === "plan"
          ? "JA_SIDE_TASK_PLAN_DONE"
          : marker === "side"
            ? "JA_SIDE_TASK_CHILD_DONE"
            : "JA_SIDE_TASK_PARENT_DONE";
    const message = {
      id: `msg_side_task_${sequence}`,
      type: "message",
      role: "assistant",
      model,
      content: [{ type: "text", text }],
      stop_reason: "end_turn",
      stop_sequence: null,
      usage: { input_tokens: 16, output_tokens: 12 },
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
        { delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 12 } },
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
  const address = server.address();
  assert.equal(typeof address, "object");
  return {
    server,
    url: `http://127.0.0.1:${address.port}`,
    requests,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

/** 通过真实设置页配置双模型 loopback；不调用模型验证按钮，也不触碰付费网络。 */
async function configureLoopbackProvider(page, provider) {
  const settings = page.locator(".ja-settings");
  const runtimeRetry = page.getByRole("button", { name: "重新读取", exact: true });
  if (await runtimeRetry.isVisible().catch(() => false)) {
    await runtimeRetry.click();
    await page.waitForTimeout(1_000);
  }
  if (!(await settings.isVisible())) {
    const sidebar = page.getByRole("button", { name: "显示侧边栏", exact: true });
    if (await sidebar.isVisible()) await sidebar.click();
    await page.getByRole("button", { name: "设置", exact: true }).click();
  }
  try {
    await settings.waitFor({ state: "visible", timeout: 60_000 });
  } catch (error) {
    await page.screenshot({
      path: join(evidenceDirectory, "settings-timeout.png"),
      animations: "disabled",
    });
    await writeFile(
      join(evidenceDirectory, "settings-timeout.json"),
      JSON.stringify(
        {
          url: page.url(),
          title: await page.title(),
          bodyText: (await page.locator("body").innerText()).slice(0, 12_000),
          buttons: await page.getByRole("button").allTextContents(),
          runtimeStart: await page
            .evaluate(async () => {
              try {
                return await globalThis.__TAURI_INTERNALS__?.invoke("ja_runtime_start");
              } catch (invokeError) {
                return { invokeError: String(invokeError) };
              }
            })
            .catch((evaluateError) => ({ evaluateError: String(evaluateError) })),
          runtimeState: await page
            .evaluate(async () => {
              try {
                return await globalThis.__TAURI_INTERNALS__?.invoke("ja_runtime_state");
              } catch (invokeError) {
                return { invokeError: String(invokeError) };
              }
            })
            .catch((evaluateError) => ({ evaluateError: String(evaluateError) })),
        },
        null,
        2,
      ),
      "utf8",
    );
    throw error;
  }
  await settings.getByRole("tab", { name: "模型", exact: true }).click();
  const add = settings.getByRole("button", { name: "新增供应商", exact: true });
  const edit = settings.getByRole("button", { name: "编辑供应商", exact: true });
  const creating = (await add.count()) > 0;
  await (creating ? add : edit).first().click();
  const dialog = page.getByRole("dialog", {
    name: creating ? "新增供应商" : "编辑供应商",
    exact: true,
  });
  await dialog.getByLabel("供应商名称").fill("Side Task Loopback");
  const api = page.getByRole("combobox", { name: "API 规范", exact: true });
  if (await api.count()) {
    await api.click();
    await page.getByRole("option", { name: "Anthropic Messages", exact: true }).click();
  }
  await dialog.getByLabel("Base URL").fill(provider.url);
  await dialog.getByLabel("API key / token").fill("isolated-loopback-no-billing-token");
  const rows = dialog.locator(".ja-provider-model-row");
  assert.ok((await rows.count()) >= 1, "模型设置页没有模型行");
  const fill = async (row, model) => {
    await row.getByLabel("显示名称").fill(model);
    await row.getByLabel("上游模型标识").fill(model);
    await row.getByLabel("上下文 Tokens").fill("128000");
    await row.getByLabel("最大输出 Tokens").fill("8192");
  };
  await fill(rows.nth(0), PRIMARY_MODEL);
  if ((await rows.count()) < 2)
    await dialog.getByRole("button", { name: "添加模型", exact: true }).click();
  await fill(rows.nth(1), SECONDARY_MODEL);
  await dialog
    .getByRole("button", { name: creating ? "保存供应商" : "保存更改", exact: true })
    .click();
  await dialog.waitFor({ state: "hidden", timeout: 30_000 });
  const back = settings.getByRole("button", { name: "返回应用", exact: true });
  if (await back.isVisible()) await back.click();
  await page
    .getByRole("textbox", { name: "消息", exact: true })
    .first()
    .waitFor({ state: "visible", timeout: 60_000 });
}

/** 在指定 Composer 中选择真实模型，不直接改 React state 或 Tauri store。 */
async function chooseModel(composer, modelIdentifier) {
  const trigger = composer.locator(".ja-composer__model-trigger");
  await trigger.click();
  const item = composer
    .page()
    .getByRole("menuitemradio", { name: new RegExp(modelIdentifier, "u") })
    .last();
  await item.waitFor({ state: "visible", timeout: 15_000 });
  await item.click();
  await expect(trigger).toContainText(modelIdentifier, { timeout: 15_000 });
}

/** 通过真实 Radix Select 改变访问模式并等待可见选中值收敛。 */
async function chooseAccess(composer, label) {
  const control = composer.getByRole("combobox", { name: "访问模式", exact: true });
  await control.click();
  await composer.page().getByRole("option", { name: label, exact: true }).click();
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    if ((await control.innerText()) === label) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`访问模式未收敛到 ${label}`);
}

/** 读取 Composer 当前模型和访问权限，作为父子隔离的可观察基线。 */
async function readPreferences(composer) {
  const model = await composer.locator(".ja-composer__model-trigger").innerText();
  const access = await composer
    .getByRole("combobox", { name: "访问模式", exact: true })
    .innerText();
  return { model: model.trim(), access: access.trim() };
}

/** 以 thread/read 的权威 revision 收口主会话首轮，避免空白主会话冒充父上下文。 */
async function readThreadRevision(page, threadId) {
  return page.evaluate(async (id) => {
    const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
    if (typeof invoke !== "function") return null;
    try {
      const value = await invoke("ja_thread_read", { input: { threadId: id, limit: 50 } });
      return Number.isSafeInteger(value?.revision) ? value.revision : null;
    } catch {
      return null;
    }
  }, threadId);
}

/** 读取 Child Thread 的低频权威快照；空闲侧边会话必须没有 Turn，不能用 task/create 猜测。 */
async function readThreadSnapshot(page, threadId) {
  return page.evaluate(async (id) => {
    const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
    if (typeof invoke !== "function") return null;
    try {
      const value = await invoke("ja_thread_read", { input: { threadId: id, limit: 50 } });
      return {
        threadId: value?.threadId,
        revision: Number.isSafeInteger(value?.revision) ? value.revision : null,
        turnCount: Array.isArray(value?.turns) ? value.turns.length : null,
        activeTurnCount: Array.isArray(value?.turns)
          ? value.turns.filter(
              (turn) => !["completed", "failed", "cancelled"].includes(turn.status),
            ).length
          : null,
      };
    } catch {
      return null;
    }
  }, threadId);
}

/** 通过真实页面 bridge 调用闭集 Goal/Plan command；只返回稳定 projection 或错误码。 */
async function invokePlanCommand(page, command, input) {
  return page.evaluate(
    async ({ commandName, commandInput }) => {
      const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
      if (typeof invoke !== "function") return { ok: false, code: "TAURI_BRIDGE_UNAVAILABLE" };
      try {
        return { ok: true, value: await invoke(commandName, { input: commandInput }) };
      } catch (error) {
        const candidate = error !== null && typeof error === "object" ? error : {};
        const nested =
          candidate.error !== null && typeof candidate.error === "object" ? candidate.error : {};
        const code = [candidate.code, candidate.errorCode, nested.code].find(
          (value) => typeof value === "string" && /^[A-Z][A-Z0-9_]{2,63}$/u.test(value),
        );
        return { ok: false, code: code ?? "PLAN_RPC_REJECTED" };
      }
    },
    { commandName: command, commandInput: input },
  );
}

/** Plan projection 必须由服务端完整返回，验收脚本不从 DOM 或局部字段拼接 hash/revision。 */
function requirePlanProjection(result, label) {
  if (
    result?.ok !== true ||
    result.value === null ||
    typeof result.value !== "object" ||
    result.value.plan === null ||
    typeof result.value.plan !== "object" ||
    typeof result.value.plan.planId !== "string" ||
    !Number.isSafeInteger(result.value.plan.revision)
  ) {
    throw new Error(`${label} 未返回完整 Plan 投影：${result?.code ?? "UNKNOWN"}`);
  }
  return result.value;
}

/** Plan 创建、编辑、执行和停止都走真实 UI；native read 仅核验权威 owner 与状态。 */
async function exerciseStandalonePlan(page, childThreadId, provider, composer) {
  process.stdout.write("JA_SIDE_STAGE plan_create_ui\n");
  const input = composer.getByRole("textbox", { name: "消息", exact: true });
  await input.fill("/plan on");
  await input.press("Enter");
  await expect(composer.locator('[data-goal-ui="mode-status"]')).toBeVisible();
  await input.fill(PLAN_MARKER);
  await composer.getByRole("button", { name: "发送", exact: true }).click();
  await page
    .getByText("JA_SIDE_TASK_PLAN_DONE", { exact: true })
    .last()
    .waitFor({ state: "visible", timeout: 60_000 });
  const current = await invokePlanCommand(page, "ja_runtime_plan_current_read", {
    threadId: childThreadId,
  });
  const created = requirePlanProjection(
    { ok: current.ok, value: current.value?.current },
    "UI Plan create",
  );
  assert.equal(created.plan.owner.threadId, childThreadId);
  await composer.locator('[data-goal-ui="mode-status"]').click();
  await page.getByRole("button", { name: "查看计划", exact: true }).click();
  const panel = page.locator(".ja-task-detail .ja-plan-workbench");
  await panel.getByRole("button", { name: "编辑计划", exact: true }).click();
  const editor = panel.getByRole("region", { name: "编辑计划草稿", exact: true });
  await editor.getByRole("textbox", { name: "范围", exact: true }).fill("仅验证隔离侧边任务");
  await editor
    .getByRole("textbox", { name: "验证策略", exact: true })
    .fill("观察真实 Provider 请求与停止状态");
  if ((await editor.getByRole("textbox", { name: "步骤 1 标题", exact: true }).count()) === 0)
    await editor.getByRole("button", { name: "添加步骤", exact: true }).click();
  await editor.getByRole("textbox", { name: "步骤 1 标题", exact: true }).fill("运行侧边计划");
  await editor
    .getByRole("textbox", { name: "步骤 1 说明", exact: true })
    .fill("调用隔离 Provider，并由用户停止本轮验证");
  if ((await editor.getByRole("textbox", { name: "验收条件 1", exact: true }).count()) === 0)
    await editor.getByRole("button", { name: "添加验收条件", exact: true }).click();
  await editor
    .getByRole("textbox", { name: "验收条件 1", exact: true })
    .fill("独立计划能够启动并响应停止");
  await editor.getByRole("button", { name: "完成编辑", exact: true }).click();
  await expect(editor).toBeHidden({ timeout: 30_000 });
  await capture(page, "04-side-plan-ready");
  process.stdout.write("JA_SIDE_STAGE plan_execute_ui\n");
  const beforeExecute = provider.requests.length;
  await panel.getByRole("button", { name: "执行", exact: true }).click();
  await expect
    .poll(
      () =>
        provider.requests.find(
          (request) => request.marker === "plan" && request.sequence > beforeExecute,
        ),
      { timeout: 30_000 },
    )
    .toBeTruthy();
  const request = provider.requests.find(
    (candidate) => candidate.marker === "plan" && candidate.sequence > beforeExecute,
  );
  assert.equal(request.hasParentMarker, true, "Plan 执行没有继承父上下文");
  await panel.getByRole("button", { name: "停止计划", exact: true }).click();
  let stopped;
  await expect
    .poll(
      async () => {
        stopped = await invokePlanCommand(page, "ja_runtime_plan_read", {
          threadId: childThreadId,
          planId: created.plan.planId,
        });
        return stopped.value?.plan?.status;
      },
      { timeout: 30_000 },
    )
    .toBe("stopped");
  const validation = await page.evaluate(async (projection) => {
    const { PlanProjectionSchema } = await import("/src/api/protocol/goal.ts");
    const parsed = PlanProjectionSchema.safeParse(projection);
    return parsed.success ? { ok: true } : { ok: false, issues: parsed.error.issues };
  }, stopped.value);
  assert.equal(validation.ok, true, `停止 Plan 必须通过生产协议校验：${JSON.stringify(validation)}`);
  await expect(panel.locator(".ja-plan-header__copy")).toContainText("已停止", { timeout: 30_000 });
  await capture(page, "05-side-plan-stopped");
  await page
    .locator(".ja-task-detail")
    .getByRole("button", { name: "返回对话", exact: true })
    .click();
  return {
    created: true,
    proposed: true,
    executed: true,
    stopped: true,
    via: "ui",
    planId: created.plan.planId,
    ownerThreadId: childThreadId,
    providerRequest: request,
  };
}

/** 读取已建侧边任务的稳定 Tab identity；草稿 Tab 明确不能伪造 Child Thread。 */
async function currentChildThreadId(page) {
  const tab = page
    .locator('.ja-workbench-tab[aria-selected="true"][data-workbench-tab^="side-task:thr_"]')
    .first();
  await tab.waitFor({ state: "visible", timeout: 20_000 });
  const key = await tab.getAttribute("data-workbench-tab");
  if (typeof key !== "string" || !key.startsWith("side-task:thr_"))
    throw new Error("无法从真实 Workbench Tab 读取侧边 Child Thread identity");
  return key.slice("side-task:".length);
}

/** 包住真实 Goal create ACK；后续 read 仍通过原生 command，证据不依赖 React 内部状态。 */
async function captureGoalCreate(page) {
  await page.evaluate(() => {
    const internals = globalThis.__TAURI_INTERNALS__;
    const invoke = internals?.invoke;
    if (typeof invoke !== "function") throw new Error("Tauri invoke 不可用，无法验收 Goal owner");
    const calls = [];
    const wrapped = async (...args) => {
      const result = await Reflect.apply(invoke, internals, args);
      if (args[0] === "ja_runtime_goal_create") calls.push(result);
      return result;
    };
    globalThis.__JA_SIDE_TASK_GOAL_CREATE_RESULTS__ = calls;
    internals.invoke = wrapped;
  });
}

/** 通过 Tauri 原生 command 读取 Goal owner，确保 UI ACK 没有把独立任务伪装成主任务。 */
async function readCapturedChildGoal(page, childThreadId) {
  return page.evaluate(async (taskThreadId) => {
    const internals = globalThis.__TAURI_INTERNALS__;
    const invoke = internals?.invoke;
    if (typeof invoke !== "function") throw new Error("Tauri invoke 不可用，无法读取 Goal owner");
    // Goal create 由 adapter 持有原生 bridge 引用，不能依赖替换 window.invoke 的旁路捕获；
    // task/read 的 activeGoalId 是同一服务端状态的权威投影。
    let taskRead;
    let goalId;
    const deadline = Date.now() + 20_000;
    while (Date.now() < deadline) {
      taskRead = await invoke("ja_runtime_task_read", {
        input: { taskThreadId, limit: 200 },
      });
      goalId = taskRead?.value?.thread?.activeGoalId ?? taskRead?.thread?.activeGoalId;
      if (typeof goalId === "string") break;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    if (typeof goalId !== "string")
      throw new Error(`真实 Goal create 未在 task/read 投影：${JSON.stringify(taskRead)}`);
    const read = await Reflect.apply(invoke, internals, [
      "ja_runtime_goal_read",
      { input: { goalId } },
    ]);
    const owner = read?.value?.goal?.owner ?? read?.goal?.owner;
    if (owner?.kind !== "independent_task" || owner.taskThreadId !== taskThreadId)
      throw new Error(`Goal owner 不匹配：${JSON.stringify(owner)}`);
    return { goalId, owner };
  }, childThreadId);
}

/** 通过主导航按钮取得当前 Thread identity，避免假设随机隔离 workspace 的固定 ID。 */
async function currentThreadId(page) {
  const direct = page
    .locator(
      '.ja-navigation-thread-row[data-state="active"] button[data-thread-id], .ja-navigation-thread-row[aria-current="page"] button[data-thread-id], .ja-navigation-thread-row button[data-thread-id][aria-current="page"]',
    )
    .first();
  const candidate =
    (await direct.count()) > 0
      ? direct
      : page.locator(".ja-navigation-thread-row button[data-thread-id]").first();
  const id = await candidate.getAttribute("data-thread-id");
  if (id === null) {
    const fallback = await page
      .locator(".ja-navigation-thread-row button[data-thread-id]")
      .first()
      .getAttribute("data-thread-id");
    if (fallback === null) throw new Error("无法从真实历史导航读取主任务 identity");
    return fallback;
  }
  return id;
}

/** 新建主会话，确保本轮侧边任务绑定到本轮有效 root revision。 */
async function prepareParentConversation(page) {
  const newConversation = page.getByRole("button", { name: "新会话", exact: true });
  if (await newConversation.count()) await newConversation.click();
  const composer = page.locator(".ja-conversation .ja-composer").first();
  await chooseModel(composer, PRIMARY_MODEL);
  const input = composer.getByRole("textbox", { name: "消息", exact: true });
  await input.fill(ROOT_MARKER);
  await composer.getByRole("button", { name: "发送", exact: true }).click();
  await page
    .getByText("JA_SIDE_TASK_PARENT_DONE", { exact: true })
    .last()
    .waitFor({ state: "visible", timeout: 60_000 });
  const threadId = await currentThreadId(page);
  const revision = await readThreadRevision(page, threadId);
  if (revision === null || revision < 1)
    throw new Error("主任务首轮完成后没有可用的 root revision");
  return { composer, threadId, revision };
}

/** 打开右栏侧边会话；新建动作会先建立真实 idle Child Thread，再挂载 Composer。 */
async function openSideTask(page) {
  const inspector = page.locator(
    '.ja-thread-workbench-session:not([hidden]) .ja-inspector[aria-label="工作区面板"]',
  );
  if ((await inspector.getAttribute("data-visible")) !== "true") {
    await page.getByRole("button", { name: "显示工作区面板", exact: true }).click();
  }
  await inspector.waitFor({ state: "visible", timeout: 20_000 });
  const workbench = inspector.locator(".ja-workbench:visible");
  await workbench.waitFor({ state: "visible", timeout: 20_000 });
  const add = workbench.getByRole("button", { name: "新建标签页", exact: true });
  const previousKey = await workbench
    .locator('.ja-workbench-tab[aria-selected="true"]')
    .first()
    .getAttribute("data-workbench-tab");
  await add.click();
  await page.getByRole("menuitem", { name: "新建侧边任务", exact: true }).last().click();
  const tab = page
    .locator('.ja-workbench-tab[aria-selected="true"][data-workbench-tab^="side-task:"]')
    .first();
  await tab.waitFor({ state: "visible", timeout: 20_000 });
  if (previousKey !== null)
    await expect(tab).not.toHaveAttribute("data-workbench-tab", previousKey, { timeout: 20_000 });
  const tabKey = await tab.getAttribute("data-workbench-tab");
  if (typeof tabKey !== "string" || tabKey.startsWith("side-task:draft_"))
    throw new Error("新建侧边任务没有建立真实 Child Thread");
  const childThreadId = await currentChildThreadId(page);
  const region = page.locator(".ja-task-detail:visible").last();
  await region.waitFor({ state: "visible", timeout: 20_000 });
  const label = await region.getAttribute("aria-label");
  if (label === null || label.trim() === "") throw new Error("侧边任务区域缺少实际 aria-label");
  const composer = region.locator(".ja-composer");
  await composer.waitFor({ state: "visible", timeout: 20_000 });
  try {
    await composer.getByRole("combobox", { name: "访问模式", exact: true }).waitFor({
      state: "visible",
      timeout: 20_000,
    });
  } catch (error) {
    await capture(page, "side-access-timeout");
    await writeFile(
      join(evidenceDirectory, "side-access-timeout.json"),
      JSON.stringify(
        await page.evaluate(() => {
          const side = Array.from(globalThis.document.querySelectorAll(".ja-task-detail")).find(
            (element) => element instanceof globalThis.HTMLElement && element.offsetParent !== null,
          );
          return {
            sideText: side?.textContent ?? "",
            sideAria: Array.from(side?.querySelectorAll("[aria-label]") ?? []).map((element) => ({
              tag: element.tagName,
              label: element.getAttribute("aria-label"),
              role: element.getAttribute("role"),
              value: element instanceof globalThis.HTMLInputElement ? element.value : undefined,
            })),
            sideComposer: side?.querySelector(".ja-composer")?.outerHTML.slice(0, 20_000),
          };
        }),
        null,
        2,
      ),
      "utf8",
    );
    throw error;
  }
  return { composer, region, childThreadId, label };
}

/** 侧边 Composer 必须真正暴露主任务同款 slash 入口且能无损返回焦点。 */
async function exercisePlanGoalEntrypoints(page, composer, { createGoal = false } = {}) {
  const input = composer.getByRole("textbox", { name: "消息", exact: true });
  const waitForPlanStatus = async (visible) => {
    const status = composer.locator('[data-goal-ui="mode-status"]');
    await status.waitFor({ state: visible ? "visible" : "hidden", timeout: 15_000 });
  };
  await input.fill("/");
  const listbox = page.getByRole("listbox");
  await listbox.waitFor({ state: "visible", timeout: 15_000 });
  const menuText = await listbox.innerText();
  assert.match(menuText, /先制定计划再决定是否执行/u);
  assert.match(menuText, /设置要持续追求的目标/u);
  await input.press("Escape");
  await listbox.waitFor({ state: "hidden", timeout: 15_000 });
  await input.fill("/plan");
  await input.press("Enter");
  await waitForPlanStatus(true);
  await input.fill("/plan off");
  await input.press("Enter");
  await waitForPlanStatus(false);
  let goalEditor = false;
  if (createGoal) {
    await input.fill("/goal");
    await input.press("Enter");
    const editor = page.getByRole("textbox", { name: /目标/u }).last();
    await editor.waitFor({ state: "visible", timeout: 15_000 });
    await editor.fill("侧边任务 Goal 入口隔离验收");
    await composer.getByRole("button", { name: "创建目标", exact: true }).click();
    goalEditor = true;
  }
  await input.waitFor({ state: "visible", timeout: 15_000 });
  const focusDeadline = Date.now() + 5_000;
  let focusReturned = false;
  while (Date.now() < focusDeadline) {
    focusReturned = await input.evaluate((node) => node === globalThis.document.activeElement);
    if (focusReturned) break;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  assert.equal(focusReturned, true, "slash 操作后焦点没有回到侧边 Composer");
  return { slashMenu: true, planToggle: true, goalEditor, focusReturned };
}

/** 空闲 Child 的验收只看真实 Provider 请求和 Thread turns，避免把创建路径误判为模型启动。 */
async function assertIdleChild(page, provider, childThreadId, requestCount, label) {
  await page.waitForTimeout(300);
  assert.equal(
    provider.requests.length,
    requestCount,
    `${label} 创建后 Provider 请求数增加：${JSON.stringify(provider.requests)}`,
  );
  const snapshot = await readThreadSnapshot(page, childThreadId);
  assert.equal(snapshot?.threadId, childThreadId, `${label} Thread identity 读取失败`);
  assert.equal(snapshot?.turnCount, 0, `${label} 空闲 Child 不应存在 Turn`);
  return snapshot;
}

/** 等待 loopback 请求计数在有限窗口内稳定，避免流式终态文本先于重复提交 ACK 到达。 */
async function waitForProviderQuiescence(provider) {
  let previous = provider.requests.length;
  let stableSince = Date.now();
  const deadline = Date.now() + 5_000;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 50));
    const current = provider.requests.length;
    if (current !== previous) {
      previous = current;
      stableSince = Date.now();
    } else if (Date.now() - stableSince >= 500) return;
  }
}

/** 验证共享 Composer 视觉合同及宽窄侧栏布局，截图来自实际 WebView2 DOM。 */
async function inspectComposerParity(page) {
  const evidence = await page.evaluate(() => {
    const root = globalThis.document.querySelector(".ja-conversation .ja-composer");
    const child = globalThis.document.querySelector(".ja-task-detail .ja-composer");
    const sideRegion = child?.closest(".ja-task-detail");
    const rail = child?.closest(".ja-task-composer");
    const parentStyle =
      root instanceof globalThis.HTMLElement ? globalThis.getComputedStyle(root) : null;
    const childStyle =
      child instanceof globalThis.HTMLElement ? globalThis.getComputedStyle(child) : null;
    const rect = (element) =>
      element instanceof globalThis.HTMLElement ? element.getBoundingClientRect() : null;
    return {
      rootFound: root instanceof globalThis.HTMLElement,
      childFound: child instanceof globalThis.HTMLElement,
      sharedSurface:
        root instanceof globalThis.HTMLElement &&
        child instanceof globalThis.HTMLElement &&
        ["borderRadius", "borderStyle", "borderWidth", "backgroundColor", "gap", "padding"].every(
          (field) => parentStyle[field] === childStyle[field],
        ),
      childOverflow:
        rail instanceof globalThis.HTMLElement ? rail.scrollWidth - rail.clientWidth : null,
      regionOverflow:
        sideRegion instanceof globalThis.HTMLElement
          ? sideRegion.scrollWidth - sideRegion.clientWidth
          : null,
      parentRect: rect(root),
      childRect: rect(child),
    };
  });
  assert.equal(
    evidence.sharedSurface,
    true,
    `侧边 Composer 没有复用主 Composer 样式：${JSON.stringify(evidence)}`,
  );
  assert.ok(
    (evidence.childOverflow ?? 99) <= 1,
    `侧边 Composer 横向溢出：${JSON.stringify(evidence)}`,
  );
  assert.ok(
    (evidence.regionOverflow ?? 99) <= 1,
    `侧边任务区域横向溢出：${JSON.stringify(evidence)}`,
  );
  return evidence;
}

/** 主流程只做用户可见真实操作；异常时保留截图和已观察到的 loopback 请求。 */
async function run(page, provider) {
  process.stdout.write("JA_SIDE_STAGE configure\n");
  await page.reload({ waitUntil: "domcontentloaded", timeout: 60_000 });
  await page.locator(".ja-composer").first().waitFor({ state: "visible", timeout: 60_000 });
  await configureLoopbackProvider(page, provider);
  const parent = await prepareParentConversation(page);
  process.stdout.write("JA_SIDE_STAGE parent_ready\n");
  const parentPreferences = await readPreferences(parent.composer);
  await capture(page, "01-parent-completed");
  await waitForProviderQuiescence(provider);

  const requestCountBeforeFirstSide = provider.requests.length;
  const firstSide = await openSideTask(page);
  process.stdout.write("JA_SIDE_STAGE child_ready\n");
  const child = firstSide.composer;
  const childControls = await child.locator(".ja-composer__model-trigger").count();
  const childAccess = await child.getByRole("combobox", { name: "访问模式", exact: true }).count();
  assert.equal(childControls, 1, "侧边空闲会话缺少模型选择控件");
  assert.equal(childAccess, 1, "侧边空闲会话缺少访问权限选择控件");
  const childDefaults = await readPreferences(child);
  assert.equal(childDefaults.model, parentPreferences.model, "侧边空闲会话默认模型没有继承主任务");
  assert.equal(
    childDefaults.access,
    parentPreferences.access,
    "侧边空闲会话默认权限没有继承主任务",
  );
  const firstIdleSnapshot = await assertIdleChild(
    page,
    provider,
    firstSide.childThreadId,
    requestCountBeforeFirstSide,
    firstSide.label,
  );
  const parity = await inspectComposerParity(page);
  await capture(page, "02-side-idle-wide");

  await chooseModel(child, SECONDARY_MODEL);
  await chooseAccess(child, parentPreferences.access === "完全访问" ? "需要审批" : "完全访问");
  const firstSidePreferences = await readPreferences(child);
  await captureGoalCreate(page);
  const planGoal = await exercisePlanGoalEntrypoints(page, child, { createGoal: true });
  const goalOwner = await readCapturedChildGoal(page, firstSide.childThreadId);
  process.stdout.write("JA_SIDE_STAGE goal_created\n");
  // 简单 fixture 不报告业务进展，Goal 应有界续跑后暂停；不能伪造“已达成”来结束测试。
  let settledGoal;
  await expect
    .poll(
      async () => {
        settledGoal = await invokePlanCommand(page, "ja_runtime_goal_read", {
          goalId: goalOwner.goalId,
        });
        return ["paused", "achieved", "stopped"].includes(settledGoal.value?.goal?.status);
      },
      { timeout: 30_000 },
    )
    .toBe(true);
  goalOwner.observedStatus = settledGoal.value.goal.status;
  await expect
    .poll(async () => (await readThreadSnapshot(page, firstSide.childThreadId))?.activeTurnCount, {
      timeout: 30_000,
    })
    .toBe(0);
  if (settledGoal.value.goal.status === "paused") {
    const stopped = await invokePlanCommand(page, "ja_runtime_goal_stop", {
      goalId: goalOwner.goalId,
      expectedGoalRevision: settledGoal.value.goal.revision,
      idempotencyKey: `side-goal-cleanup-${Date.now()}`,
    });
    assert.equal(stopped.ok, true);
  }
  // Goal create 可按服务端策略立即启动独立 continuation；这里仍读取 Child 快照，
  // 但不把该合法 Goal 请求误判为侧边任务创建时的隐式 Provider 启动。
  const afterGoalSnapshot = await readThreadSnapshot(page, firstSide.childThreadId);
  const sideInput = child.getByRole("textbox", { name: "消息", exact: true });
  await sideInput.fill(SIDE_MARKER);
  await child.getByRole("button", { name: "发送", exact: true }).click();
  await page
    .getByText("JA_SIDE_TASK_CHILD_DONE", { exact: true })
    .last()
    .waitFor({ state: "visible", timeout: 60_000 });
  const firstSideRequest = await waitForRequest(provider.requests, "side");
  process.stdout.write(`JA_SIDE_STAGE first_child_request ${JSON.stringify(firstSideRequest)}\n`);
  assert.equal(firstSideRequest.model, SECONDARY_MODEL, "侧边首轮 loopback 请求没有使用选择的模型");
  assert.equal(
    firstSideRequest.hasParentMarker,
    true,
    `侧边首轮 loopback 请求没有携带主任务 context marker：${JSON.stringify(provider.requests)}`,
  );
  const sideContext = await firstSide.region.innerText();
  assert.match(
    sideContext,
    /继承自主任务 revision \d+/u,
    "侧边详情没有展示父任务 context revision",
  );
  assert.equal(
    await readPreferences(parent.composer).then((value) => value.model),
    parentPreferences.model,
    "侧边改模型污染了父任务模型",
  );
  assert.equal(
    await readPreferences(parent.composer).then((value) => value.access),
    parentPreferences.access,
    "侧边改权限污染了父任务权限",
  );

  await chooseModel(child, PRIMARY_MODEL);
  await chooseAccess(child, parentPreferences.access);
  const followup = child.getByRole("textbox", { name: "消息", exact: true });
  await followup.fill(FOLLOWUP_MARKER);
  await child.getByRole("button", { name: "发送", exact: true }).click();
  await page
    .getByText("JA_SIDE_TASK_FOLLOWUP_DONE", { exact: true })
    .last()
    .waitFor({ state: "visible", timeout: 60_000 });
  const followupRequest = await waitForRequest(provider.requests, "followup");
  process.stdout.write("JA_SIDE_STAGE followup_done\n");
  assert.equal(followupRequest.model, PRIMARY_MODEL, "侧边 follow-up 改模型后没有作用于真实请求");

  const requestCountBeforeSecondSide = provider.requests.length;
  const secondSide = await openSideTask(page);
  const secondControls = await secondSide.composer.locator(".ja-composer__model-trigger").count();
  const secondAccess = await secondSide.composer
    .getByRole("combobox", { name: "访问模式", exact: true })
    .count();
  assert.equal(secondControls, 1, "第二个侧边空闲会话缺少模型选择控件");
  assert.equal(secondAccess, 1, "第二个侧边空闲会话缺少访问权限选择控件");
  const secondDefaults = await readPreferences(secondSide.composer);
  assert.deepEqual(secondDefaults, parentPreferences, "第二个侧边空闲会话没有继承主任务偏好");
  const secondIdleSnapshot = await assertIdleChild(
    page,
    provider,
    secondSide.childThreadId,
    requestCountBeforeSecondSide,
    secondSide.label,
  );
  const secondPlanGoal = await exercisePlanGoalEntrypoints(page, secondSide.composer);
  const secondAfterPlanSnapshot = await assertIdleChild(
    page,
    provider,
    secondSide.childThreadId,
    requestCountBeforeSecondSide,
    secondSide.label,
  );
  const standalonePlan = await exerciseStandalonePlan(
    page,
    secondSide.childThreadId,
    provider,
    secondSide.composer,
  );

  const rootAfter = await readPreferences(parent.composer);
  assert.deepEqual(rootAfter, parentPreferences, "父任务偏好在侧边首轮/follow-up 后发生变化");
  await page.setViewportSize({ width: 720, height: 640 });
  await inspectComposerParity(page);
  await capture(page, "03-side-task-narrow");
  return {
    status: "passed",
    root: { threadId: parent.threadId, revision: parent.revision, preferences: parentPreferences },
    child: {
      threadId: firstSide.childThreadId,
      label: firstSide.label,
      defaults: childDefaults,
      firstIdleSnapshot,
      afterGoalSnapshot,
      firstRequest: firstSideRequest,
      followupRequest,
      firstSidePreferences,
    },
    secondChild: {
      threadId: secondSide.childThreadId,
      label: secondSide.label,
      defaults: secondDefaults,
      idleSnapshot: secondIdleSnapshot,
      afterPlanSnapshot: secondAfterPlanSnapshot,
      planGoal: secondPlanGoal,
      standalonePlan,
    },
    goalOwner,
    isolation: {
      parentPreferencesUnchanged: true,
      contextInherited:
        /继承自主任务 revision \d+/u.test(sideContext) && firstSideRequest.hasParentMarker,
    },
    planGoal,
    composerParity: parity,
    requests: provider.requests,
    screenshots: ["01-parent-completed.png", "02-side-idle-wide.png", "03-side-task-narrow.png", "04-side-plan-ready.png", "05-side-plan-stopped.png"],
  };
}

/** 有界等待指定 loopback 场景，避免 Provider 网络异常被误报成 UI 通过。 */
async function waitForRequest(requests, marker) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const match = requests.find((request) => request.marker === marker);
    if (match !== undefined) return match;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`loopback Provider 未观察到 ${marker} 请求`);
}

/** CDP transport 与 loopback server 都在本进程收口，绝不替隔离 launcher 关闭产品进程。 */
async function main() {
  validateEnvironment();
  await mkdir(evidenceDirectory, { recursive: true });
  const provider = await startLoopbackProvider();
  let browser;
  try {
    browser = await chromium.connectOverCDP(endpoint);
    const page = browser
      .contexts()
      .flatMap((context) => context.pages())
      .find((candidate) => !candidate.isClosed());
    if (page === undefined) throw new Error("隔离 WebView2 没有可用页面");
    page.on("pageerror", (error) => {
      process.stderr.write(`JA_SIDE_TASK_PAGEERROR ${error.stack ?? error.message}\n`);
    });
    page.on("console", (message) => {
      if (message.type() === "error")
        process.stderr.write(`JA_SIDE_TASK_CONSOLE_ERROR ${message.text()}\n`);
    });
    await page.setViewportSize({ width: 1280, height: 820 });
    await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
    const report = await run(page, provider).catch(async (error) => {
      await capture(page, "failure").catch(() => undefined);
      await writeReport({
        status: "failed",
        error: error.message,
        requests: provider.requests,
        body: (
          await page
            .locator("body")
            .innerText()
            .catch(() => "")
        ).slice(-6000),
      });
      throw error;
    });
    await writeReport(report);
    process.stdout.write(`JA_SIDE_TASK_PREFERENCES_OK evidence=${evidenceDirectory}\n`);
  } finally {
    await browser?.close();
    provider.server.closeAllConnections();
    await provider.close();
  }
}

/** 证据只保留稳定枚举、identity 和尺寸，不写入 Provider 请求正文或凭据。 */
async function writeReport(report) {
  const path = join(evidenceDirectory, "side-task-preferences.json");
  const safe = JSON.parse(JSON.stringify(report));
  await writeFile(path, `${JSON.stringify(safe, null, 2)}\n`, "utf8");
}

await main();
