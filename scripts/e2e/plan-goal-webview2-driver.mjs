// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { join } from "node:path";
import { PLAN_GOAL_RPC_CONTRACT, PLAN_GOAL_UI_CONTRACT } from "./plan-goal-production.mjs";

/** 在绝对期限内轮询权威条件；每次轮询都受同一 AbortSignal 约束，避免测试超时被重置。 */
async function waitForCondition(label, predicate, deadline, signal, intervalMs = 100) {
  let lastError;
  while (Date.now() < deadline) {
    if (signal?.aborted) throw signal.reason ?? new Error(`${label} aborted`);
    try {
      const value = await predicate();
      if (value !== false && value !== undefined && value !== null) return value;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolvePromise, rejectPromise) => {
      const timer = setTimeout(
        resolvePromise,
        Math.min(intervalMs, Math.max(1, deadline - Date.now())),
      );
      signal?.addEventListener(
        "abort",
        () => {
          clearTimeout(timer);
          rejectPromise(signal.reason ?? new Error(`${label} aborted`));
        },
        { once: true },
      );
    });
  }
  throw new Error(`${label} 未在期限内满足`, { cause: lastError });
}

/** 通过真实 Tauri invoke 调用闭集 command，并只把稳定错误码带回 runner。 */
async function invokeCommand(page, command, input) {
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
        return { ok: false, code: code ?? "GOAL_RPC_REJECTED" };
      }
    },
    { commandName: command, commandInput: input },
  );
}

/** Mutation 必须返回完整 Plan projection；runner 不从 DOM 或局部 ACK 拼接 revision。 */
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

/** Mutation 必须返回完整 Goal projection；关联身份只认服务端 planLink/currentRunId。 */
function requireGoalProjection(result, label) {
  if (
    result?.ok !== true ||
    result.value === null ||
    typeof result.value !== "object" ||
    result.value.goal === null ||
    typeof result.value.goal !== "object" ||
    typeof result.value.goal.goalId !== "string" ||
    !Number.isSafeInteger(result.value.goal.revision)
  ) {
    throw new Error(`${label} 未返回完整 Goal 投影：${result?.code ?? "UNKNOWN"}`);
  }
  return result.value;
}

/** E2E 幂等键只携带阶段与单调 nonce，不把目标正文、hash 或本机路径写入日志。 */
function idempotencyKey(stage) {
  return `plan-goal-e2e:${stage}:${Date.now().toString(36)}:${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * attach/detach 会替换 Goal run；活动 Goal 先暂停，等待旧 Turn/lease 收口后执行关联变更，再恢复。
 * settling 重试复用同一 mutation key，报告同时保留关联 mutation 自身的 revision 增量。
 */
async function replaceGoalPlanBinding(
  page,
  commands,
  goal,
  { stage, command, input },
  deadline,
  signal,
) {
  const shouldResume = goal.goal.status === "active";
  const paused = shouldResume
    ? requireGoalProjection(
        await invokeCommand(page, commands.goalPause, {
          goalId: goal.goal.goalId,
          expectedGoalRevision: goal.goal.revision,
          idempotencyKey: idempotencyKey(`${stage}-pause`),
        }),
        `${stage} pause`,
      )
    : goal;
  const mutationKey = idempotencyKey(stage);
  const settlementDeadline = Math.min(deadline, Date.now() + 5_000);
  const mutated = await waitForCondition(
    `${stage} settlement`,
    async () => {
      const result = await invokeCommand(page, command, {
        ...input,
        goalId: paused.goal.goalId,
        expectedGoalRevision: paused.goal.revision,
        idempotencyKey: mutationKey,
      });
      if (result.ok === true) return requireGoalProjection(result, stage);
      if (result.code === "GOAL_INVALID_STATE") return false;
      throw new Error(`${stage} 被拒绝：${result.code}`);
    },
    settlementDeadline,
    signal,
    50,
  );
  const projection = shouldResume
    ? requireGoalProjection(
        await invokeCommand(page, commands.goalResume, {
          goalId: mutated.goal.goalId,
          expectedGoalRevision: mutated.goal.revision,
          idempotencyKey: idempotencyKey(`${stage}-resume`),
        }),
        `${stage} resume`,
      )
    : mutated;
  return { paused, mutated, projection };
}

/** 结构化定义固定一条必要步骤和验收条件，便于把真实 Tool result 精确挂到当前 revision。 */
function planDefinition(scenario, suffix = "main") {
  return {
    objective: suffix === "main" ? scenario.revisedObjective : scenario.recoveryObjective,
    scope: [scenario.scope],
    nonGoals: ["不调用真实或付费 Provider"],
    constraints: ["只使用隔离 Windows Tauri/WebView2 与确定性 loopback Provider"],
    acceptanceCriteria: [
      {
        criterionId: `criterion_${suffix}`,
        description: suffix === "main" ? scenario.criterion : scenario.recoveryCriterion,
        required: true,
      },
    ],
    steps: [
      {
        stepId: `step_${suffix}`,
        title: suffix === "main" ? scenario.stepTitle : scenario.recoveryStepTitle,
        description:
          suffix === "main" ? scenario.stepDescription : scenario.recoveryStepDescription,
        required: true,
        dependsOn: [],
      },
    ],
    dependencies: [],
    risks: ["外部副作用结果未知时必须进入人工恢复"],
    verificationStrategy: [scenario.verification],
  };
}

/**
 * 使用当前 Thread revision 创建独立 Plan；前一个内部 Turn 的 terminal 与 Thread revision 提交可能
 * 紧邻本次读取，因此只对明确的 CAS 冲突做三次有界重读，并保持同一幂等键。
 */
async function createPlan(page, commands, threadId, readThread, objective, stage) {
  const createIdempotencyKey = idempotencyKey(`${stage}-create`);
  let result;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const thread = await readThread(page, threadId);
    result = await invokeCommand(page, commands.planCreate, {
      owner: { kind: "thread", threadId },
      objective,
      expectedThreadRevision: thread.revision,
      idempotencyKey: createIdempotencyKey,
    });
    if (result.ok === true || result.code !== "GOAL_REVISION_CONFLICT") break;
  }
  return requirePlanProjection(result, `${stage} create`);
}

  /** 保存并冻结精确 Plan revision；提案本身不得启动 standalone Run。 */
async function proposePlanRevision(page, commands, threadId, projection, definition, stage) {
  let current = requirePlanProjection(
    await invokeCommand(page, commands.planDraftSave, {
      threadId,
      planId: projection.plan.planId,
      expectedPlanRevision: projection.plan.revision,
      idempotencyKey: idempotencyKey(`${stage}-draft`),
      draft: definition,
    }),
    `${stage} draft`,
  );
  current = requirePlanProjection(
    await invokeCommand(page, commands.planPropose, {
      threadId,
      planId: current.plan.planId,
      expectedPlanRevision: current.plan.revision,
      idempotencyKey: idempotencyKey(`${stage}-propose`),
    }),
    `${stage} propose`,
  );
  assert.equal(typeof current.currentRevision?.planRevisionId, "string");
  assert.equal(typeof current.currentRevision?.planHash, "string");
  const proposed = current.currentRevision;
  assert.equal(current.plan.status, "awaiting_approval");
  assert.equal(current.plan.activeRunId, null, "plan/propose 不得启动 run");
  return { projection: current, revision: proposed };
}

/** 独立 Plan Tool 审批通过后回读 Thread item，避免仅凭按钮消失认定 Tool 已结算。 */
async function approvePlanTool(page, readThread, threadId, toolName, deadline, signal) {
  const pending = await waitForCondition(
    `standalone Plan ${toolName} 审批出现`,
    async () => {
      const thread = await readThread(page, threadId);
      return thread.approvals.findLast(
        (approval) => approval.toolName === toolName && approval.decision === null,
      );
    },
    deadline,
    signal,
  );
  const row = page.locator(`.ja-chat-timeline__row[data-turn-id="${pending.turnId}"]`);
  await row
    .getByRole("button", { name: "批准", exact: true })
    .click({ timeout: Math.max(1, deadline - Date.now()) });
  await waitForCondition(
    `standalone Plan ${toolName} 审批持久化`,
    async () => {
      const thread = await readThread(page, threadId);
      return thread.approvals.some(
        (approval) => approval.approvalId === pending.approvalId && approval.decision === "approve",
      );
    },
    deadline,
    signal,
  );
}

/**
 * 仅通过 Composer 的可访问合同验证 slash 键盘流；`/goal` 的 Escape 不创建 aggregate，
 * 主 Goal 仍由 authority 阶段携带完整验收定义创建。
 */
async function exerciseComposer(page, deadline) {
  const form = page.getByRole("form", { name: "发送消息", exact: true });
  const input = form.getByRole("textbox", { name: "消息", exact: true });
  await input.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  assert.equal(await input.getAttribute("placeholder"), PLAN_GOAL_UI_CONTRACT.placeholders.default);

  await input.fill("/");
  const listbox = page.getByRole("listbox");
  await listbox.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const menuText = await listbox.innerText();
  assert.match(menuText, /添加/u);
  assert.match(menuText, /先制定计划再决定是否执行/u);
  assert.match(menuText, /设置要持续追求的目标/u);
  await input.press("ArrowDown");
  await input.press("ArrowUp");
  await input.press("Escape");
  await listbox.waitFor({ state: "hidden", timeout: Math.max(1, deadline - Date.now()) });
  assert.equal(
    await input.evaluate((element) => element === globalThis.document.activeElement),
    true,
  );

  await input.fill("/plan");
  await input.press("Enter");
  await waitForCondition(
    "Plan mode placeholder",
    async () =>
      (await input.getAttribute("placeholder")) === PLAN_GOAL_UI_CONTRACT.placeholders.plan,
    deadline,
  );
  await input.fill("/plan off");
  await input.press("Enter");
  await waitForCondition(
    "default placeholder",
    async () =>
      (await input.getAttribute("placeholder")) === PLAN_GOAL_UI_CONTRACT.placeholders.default,
    deadline,
  );

  await input.fill("/goal");
  await input.press("Enter");
  const goalEditor = form.getByRole("textbox", {
    name: PLAN_GOAL_UI_CONTRACT.placeholders.goal,
    exact: true,
  });
  await goalEditor.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  assert.equal(
    await goalEditor.getAttribute("placeholder"),
    PLAN_GOAL_UI_CONTRACT.placeholders.goal,
  );
  await goalEditor.fill("这是用于验证长文本、焦点返回和无损退出的目标描述。".repeat(8));
  await goalEditor.press("Escape");
  await input.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  assert.equal(
    await input.evaluate((element) => element === globalThis.document.activeElement),
    true,
  );

  await input.fill("/plan on");
  await input.press("Enter");
  await waitForCondition(
    "Plan mode restored",
    async () =>
      (await input.getAttribute("placeholder")) === PLAN_GOAL_UI_CONTRACT.placeholders.plan,
    deadline,
  );
  return {
    slashGrouped: true,
    slashGroupLabel: PLAN_GOAL_UI_CONTRACT.slashGroupLabel,
    slashCommands: [...PLAN_GOAL_UI_CONTRACT.slashCommands],
    slashDescriptions: { ...PLAN_GOAL_UI_CONTRACT.slashDescriptions },
    keyboardKeys: [...PLAN_GOAL_UI_CONTRACT.keys],
    enterExecutedSelection: true,
    escapeClosedMenu: true,
    focusReturnedToInput: true,
    planToggleOnChangedPlaceholder: true,
    planToggleOffRestoredPlaceholder: true,
    defaultPlaceholder: PLAN_GOAL_UI_CONTRACT.placeholders.default,
    planPlaceholder: PLAN_GOAL_UI_CONTRACT.placeholders.plan,
    goalEditorLabel: PLAN_GOAL_UI_CONTRACT.placeholders.goal,
    goalEditorPlaceholder: PLAN_GOAL_UI_CONTRACT.placeholders.goal,
    goalObjectiveEditedInline: true,
  };
}

/** 对状态行区域做确定性几何检查；只检查同一产品区域，避免把相邻全局工具栏误报为重叠。 */
async function visualFrameEvidence(page, width, scale, flags) {
  return page.evaluate(
    ({ expectedWidth, expectedScale, expectedFlags }) => {
      const visible = (element) => {
        if (!(element instanceof globalThis.HTMLElement)) return false;
        const rect = element.getBoundingClientRect();
        const style = globalThis.getComputedStyle(element);
        return (
          rect.width > 0 &&
          rect.height > 0 &&
          style.visibility !== "hidden" &&
          style.display !== "none"
        );
      };
      const roots = Array.from(
        globalThis.document.querySelectorAll(
          '[data-goal-ui="status"], [data-goal-ui="mode-status"], form[aria-label="发送消息"]',
        ),
      ).filter(visible);
      const controls = roots.flatMap((root) =>
        Array.from(
          root.querySelectorAll("button, input, textarea, select, [role='button']"),
        ).filter(visible),
      );
      const unnamedControlCount = controls.filter((control) => {
        const text = control.textContent?.trim() ?? "";
        return !(
          control.getAttribute("aria-label") ||
          control.getAttribute("aria-labelledby") ||
          control.getAttribute("title") ||
          text
        );
      }).length;
      let controlOverlapCount = 0;
      controls.forEach((left, index) => {
        const a = left.getBoundingClientRect();
        controls.slice(index + 1).forEach((right) => {
          if (
            left.contains(right) ||
            right.contains(left) ||
            left.closest("form") !== right.closest("form")
          )
            return;
          const b = right.getBoundingClientRect();
          if (
            Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 &&
            Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1
          )
            controlOverlapCount += 1;
        });
      });
      const root = globalThis.document.documentElement;
      const status = globalThis.document.querySelector('[data-goal-ui="status"]');
      const statusLineCount = status instanceof globalThis.HTMLElement && visible(status) ? 1 : 0;
      return {
        width: expectedWidth,
        scale: expectedScale,
        reducedMotion: expectedFlags.reducedMotion,
        reducedTransparency: root.dataset["reducedTransparency"] === "true",
        longText: (status?.textContent?.length ?? 0) > 80,
        documentOverflow: Math.max(0, root.scrollWidth - root.clientWidth),
        controlOverlapCount,
        unnamedControlCount,
        goalStatusLineCount: statusLineCount,
      };
    },
    { expectedWidth: width, expectedScale: scale, expectedFlags: flags },
  );
}

/** 相邻断点与三档 Windows 缩放全部通过 CDP metrics 覆盖，并保存可人工复核截图。 */
async function captureVisualMatrix(page, screenshotDirectory, prepareVisualPreferences, deadline) {
  await prepareVisualPreferences(page, deadline);
  await mkdir(screenshotDirectory, { recursive: true });
  const client = await page.context().newCDPSession(page);
  const frames = [];
  try {
    for (const width of PLAN_GOAL_UI_CONTRACT.widths) {
      for (const scale of PLAN_GOAL_UI_CONTRACT.scales) {
        const reducedMotion = width === 799 && scale === 1;
        await page.emulateMedia({ reducedMotion: reducedMotion ? "reduce" : "no-preference" });
        await client.send("Emulation.setDeviceMetricsOverride", {
          width,
          height: 900,
          deviceScaleFactor: scale,
          mobile: false,
        });
        await waitForCondition(
          `viewport ${width}@${scale}`,
          () => page.evaluate((expected) => globalThis.innerWidth === expected, width),
          deadline,
        );
        const frame = await visualFrameEvidence(page, width, scale, { reducedMotion });
        frames.push(frame);
        await page.screenshot({
          path: join(
            screenshotDirectory,
            `plan-goal-${width}-${String(scale).replace(".", "_")}.png`,
          ),
          fullPage: false,
        });
      }
    }
  } finally {
    await client.send("Emulation.clearDeviceMetricsOverride").catch(() => undefined);
    await page.emulateMedia({ reducedMotion: "no-preference" }).catch(() => undefined);
    await client.detach().catch(() => undefined);
  }
  return frames;
}

/**
 * 构造按报告阶段惰性执行的真窗 driver。状态只存在于当前 runner 进程，权威状态始终回读
 * App Server；后续阶段不能用前一阶段缓存替代 CAS。
 */
export function createPlanGoalWebView2Driver(options) {
  const {
    page,
    threadId,
    scenario,
    deadline,
    signal,
    soakMinutes,
    screenshotDirectory,
    readThread,
    approveGoalTool,
    setProviderContext,
    resetProviderContext,
    prepareVisualPreferences,
    runCrashRecovery,
    invokeCount,
    nativeSidecar,
    recordStage = () => {},
  } = options;
  const commands = PLAN_GOAL_RPC_CONTRACT.commands;
  const state = {};

  /** 只统计 Plan 详情的三类重 IO；Goal 状态行所需的轻量 read/observe 不计入隐藏能力预算。 */
  async function planDetailIoCounts() {
    if (typeof invokeCount !== "function") {
      throw new Error("Plan/Goal driver 缺少 Tauri invoke 计数器");
    }
    const [planRead, revisionList, evidenceList] = await Promise.all([
      invokeCount(commands.planRead),
      invokeCount("ja_runtime_plan_revisions_list"),
      invokeCount("ja_runtime_goal_evidence_list"),
    ]);
    return { planRead, revisionList, evidenceList };
  }

  /** 真实 surface 必须同时具备 Tauri invoke 与 Playwright CDP page，浏览器 preview 不满足。 */
  async function runtimeEvidence() {
    const runtime = await page.evaluate(() => ({
      tauri: typeof globalThis.__TAURI_INTERNALS__?.invoke === "function",
      protocol: globalThis.location.protocol,
    }));
    assert.equal(runtime.tauri, true);
    assert.match(runtime.protocol, /^(?:https?:|tauri:)/u);
    assert.equal(nativeSidecar?.used, true);
    assert.equal(nativeSidecar?.identityMatched, true);
    assert.equal(nativeSidecar?.noFallback, true);
    return {
      surface: "tauri_webview2",
      nativeWindow: true,
      deterministicMockProvider: true,
      realProviderRequests: 0,
      nativeSidecar,
    };
  }

  /** 独立 Plan 全生命周期、Goal-only、attach/detach 与两个 stale 错误在同一真实 Thread 验证。 */
  async function authorityEvidence() {
    recordStage("plan_goal_v1:composer");
    state.composer = await exerciseComposer(page, deadline);

    recordStage("plan_goal_v1:standalone_plan");
    let standalone = await createPlan(
      page,
      commands,
      threadId,
      readThread,
      scenario.objective,
      "standalone",
    );
    assert.equal(standalone.plan.status, "draft");
    ({ projection: standalone } = await proposePlanRevision(
      page,
      commands,
      threadId,
      standalone,
      planDefinition(scenario, "main"),
      "standalone",
    ));
    const proposedRunId = standalone.plan.activeRunId;
    resetProviderContext("standalone");
    standalone = requirePlanProjection(
      await invokeCommand(page, commands.planExecute, {
        threadId,
        planId: standalone.plan.planId,
        expectedPlanRevision: standalone.plan.revision,
        planRevisionId: standalone.currentRevision.planRevisionId,
        planHash: standalone.currentRevision.planHash,
        idempotencyKey: idempotencyKey("standalone-execute"),
      }),
      "standalone execute",
    );
    assert.equal(typeof standalone.plan.activeRunId, "string");
    setProviderContext({
      kind: "plan",
      planId: standalone.plan.planId,
      planRevision: standalone.plan.revision,
      runId: standalone.plan.activeRunId,
      planRevisionId: standalone.currentRevision.planRevisionId,
      stepId: standalone.currentRevision.steps[0].stepId,
      criterionId: standalone.currentRevision.acceptanceCriteria[0].criterionId,
    });
    await approvePlanTool(page, readThread, threadId, "shell", deadline, signal);
    await approvePlanTool(page, readThread, threadId, "plan_step_update", deadline, signal);
    await approvePlanTool(page, readThread, threadId, "plan_step_update", deadline, signal);
    const standaloneCompletionDeadline = Math.min(deadline, Date.now() + 60_000);
    standalone = await waitForCondition(
      "standalone Plan completed",
      async () => {
        const current = requirePlanProjection(
          await invokeCommand(page, commands.planRead, {
            threadId,
            planId: standalone.plan.planId,
          }),
          "standalone read",
        );
        return current.plan.status === "completed" ? current : false;
      },
      standaloneCompletionDeadline,
      signal,
    );

    recordStage("plan_goal_v1:attach_plan");
    let attachPlan = await createPlan(
      page,
      commands,
      threadId,
      readThread,
      scenario.revisedObjective,
      "attach",
    );
    const firstProposal = await proposePlanRevision(
      page,
      commands,
      threadId,
      attachPlan,
      planDefinition(scenario, "main"),
      "attach-v1",
    );
    attachPlan = firstProposal.projection;
    attachPlan = requirePlanProjection(
      await invokeCommand(page, commands.planDraftSave, {
        threadId,
        planId: attachPlan.plan.planId,
        expectedPlanRevision: attachPlan.plan.revision,
        idempotencyKey: idempotencyKey("attach-v2-draft"),
        draft: {
          ...planDefinition(scenario, "main"),
          objective: `${scenario.revisedObjective} v2`,
        },
      }),
      "attach v1 draft",
    );
    attachPlan = requirePlanProjection(
      await invokeCommand(page, commands.planPropose, {
        threadId,
        planId: attachPlan.plan.planId,
        expectedPlanRevision: attachPlan.plan.revision,
        idempotencyKey: idempotencyKey("attach-v2-propose"),
      }),
      "attach v1 propose",
    );
    const stalePlan = await invokeCommand(page, commands.planExecute, {
      threadId,
      planId: attachPlan.plan.planId,
      expectedPlanRevision: attachPlan.plan.revision,
      planRevisionId: firstProposal.revision.planRevisionId,
      planHash: firstProposal.revision.planHash,
      idempotencyKey: idempotencyKey("stale-plan"),
    });
    assert.equal(stalePlan.ok, false);
    assert.equal(stalePlan.code, PLAN_GOAL_RPC_CONTRACT.errors.stalePlanRevision);
    const attachRevision = attachPlan.currentRevision;
    assert.equal(attachPlan.plan.status, "awaiting_approval");
    assert.equal(attachPlan.plan.activeRunId, null);

    let goal = requireGoalProjection(
      await invokeCommand(page, commands.goalCreate, {
        owner: { kind: "thread", threadId },
        objective: `${scenario.revisedObjective} `.repeat(8).trim(),
        acceptanceCriteria: [],
        expectedGoalRevision: 0,
        idempotencyKey: idempotencyKey("goal-create"),
      }),
      "goal create",
    );
    assert.equal(goal.goal.planLink, null);
    const goalOnlyRunId = goal.goal.currentRunId;
    const staleGoal = await invokeCommand(page, commands.attach, {
      goalId: goal.goal.goalId,
      expectedGoalRevision: Number.MAX_SAFE_INTEGER,
      planId: attachPlan.plan.planId,
      planRevisionId: attachRevision.planRevisionId,
      planHash: attachRevision.planHash,
      idempotencyKey: idempotencyKey("stale-goal"),
    });
    assert.equal(staleGoal.ok, false);
    assert.equal(staleGoal.code, PLAN_GOAL_RPC_CONTRACT.errors.staleGoalRevision);

    resetProviderContext("normal");
    const attachResult = await replaceGoalPlanBinding(
      page,
      commands,
      goal,
      {
        stage: "goal-attach",
        command: commands.attach,
        input: {
          planId: attachPlan.plan.planId,
          planRevisionId: attachRevision.planRevisionId,
          planHash: attachRevision.planHash,
        },
      },
      deadline,
      signal,
    );
    const attached = attachResult.projection;
    assert.notEqual(attached.goal.currentRunId, goalOnlyRunId);
    const attachedRunId = attached.goal.currentRunId;
    const detachResult = await replaceGoalPlanBinding(
      page,
      commands,
      attached,
      { stage: "goal-detach", command: commands.detach, input: {} },
      deadline,
      signal,
    );
    goal = detachResult.projection;
    assert.equal(goal.goal.status, "active");
    assert.equal(goal.goal.phase, "working");
    assert.equal(detachResult.mutated.goal.revision, detachResult.paused.goal.revision + 1);
    assert.notEqual(goal.goal.currentRunId, attachedRunId);

    const detachMutationRevisionDelta =
      detachResult.mutated.goal.revision - detachResult.paused.goal.revision;
    const hiddenIoBefore = await planDetailIoCounts();
    resetProviderContext("normal");
    goal = (
      await replaceGoalPlanBinding(
        page,
        commands,
        goal,
        {
          stage: "goal-reattach",
          command: commands.attach,
          input: {
            planId: attachPlan.plan.planId,
            planRevisionId: attachRevision.planRevisionId,
            planHash: attachRevision.planHash,
          },
        },
        deadline,
        signal,
      )
    ).projection;
    state.goal = goal;
    state.plan = attachPlan;
    state.planRevision = attachRevision;
    setProviderContext({
      goalId: goal.goal.goalId,
      goalRevision: goal.goal.revision,
      runId: goal.goal.currentRunId,
      planRevisionId: attachRevision.planRevisionId,
      stepId: attachRevision.steps[0].stepId,
      criterionId: attachRevision.acceptanceCriteria[0].criterionId,
      projection: { latestEvaluation: goal.goal.latestEvaluation },
    });
    const quietWindowEndsAt = Date.now() + 500;
    await waitForCondition(
      "隐藏 Plan capability IO 静默窗口",
      () => Date.now() >= quietWindowEndsAt,
      deadline,
      signal,
      50,
    );
    const hiddenIoAfter = await planDetailIoCounts();
    state.hiddenPlanDetailIoDelta = Object.fromEntries(
      Object.keys(hiddenIoBefore).map((key) => [key, hiddenIoAfter[key] - hiddenIoBefore[key]]),
    );
    assert.deepEqual(state.hiddenPlanDetailIoDelta, {
      planRead: 0,
      revisionList: 0,
      evidenceList: 0,
    });
    return {
      planCreatedWithoutGoal: true,
      standalonePlanCompleted: standalone.plan.status === "completed",
      planProposalDidNotExecute: proposedRunId === null,
      goalCreatedWithoutPlan: true,
      attachApprovedPlan: goal.goal.planLink?.planRevisionId === attachRevision.planRevisionId,
      attachedGoalOwnedRunStarted: typeof goal.goal.currentRunId === "string",
      attachDidNotStartStandaloneRun: attachPlan.plan.activeRunId === null,
      detachGoalStatus: "active",
      detachGoalPhase: "working",
      detachGoalRevisionAdvancedBy: detachMutationRevisionDelta,
      detachGoalContinued: true,
      staleGoalRevisionCode: staleGoal.code,
      stalePlanRevisionCode: stalePlan.code,
      hiddenPlanDetailIoDelta: state.hiddenPlanDetailIoDelta,
    };
  }

  /**
   * Composer 报告补充“Goal 覆盖 Plan”，并证明 Plan 详情只在状态行的显式动作后挂载和读取；
   * 关闭 capability 后必须卸载，不能把隐藏的重型详情留在默认 render tree。
   */
  async function composerEvidence() {
    const modeStatus = page.locator('[data-goal-ui="mode-status"]');
    await modeStatus.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
    const status = page.locator('[data-goal-ui="status"]');
    await status.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
    const planWorkbench = page.locator(
      `[data-goal-ui="plan-workbench"][data-plan-id="${state.plan.plan.planId}"]`,
    );
    const persistentPlanWorkbenchCount = await planWorkbench.count();
    assert.equal(persistentPlanWorkbenchCount, 0);
    const explicitIoBefore = await planDetailIoCounts();
    await status
      .locator("button")
      .first()
      .click({ timeout: Math.max(1, deadline - Date.now()) });
    await planWorkbench.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
    const explicitIoAfter = await waitForCondition(
      "显式 Plan capability 详情 IO",
      async () => {
        const counts = await planDetailIoCounts();
        return counts.revisionList > explicitIoBefore.revisionList &&
          counts.evidenceList > explicitIoBefore.evidenceList
          ? counts
          : false;
      },
      deadline,
      signal,
    );
    const explicitPlanDetailIoDelta = Object.fromEntries(
      Object.keys(explicitIoBefore).map((key) => [
        key,
        explicitIoAfter[key] - explicitIoBefore[key],
      ]),
    );
    const planTab = page.locator('.ja-workbench-tab-shell[data-tab="plan"]');
    await planTab
      .getByRole("button", { name: "关闭计划", exact: true })
      .click({ timeout: Math.max(1, deadline - Date.now()) });
    await planWorkbench.waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
    const collapseInspector = page.getByRole("button", { name: "收起右侧栏", exact: true });
    if (await collapseInspector.isVisible().catch(() => false)) {
      await collapseInspector.click({ timeout: Math.max(1, deadline - Date.now()) });
    }
    return {
      ...state.composer,
      persistentSegmentedControlCount: await page
        .locator('[data-goal-ui="collaboration-mode"]')
        .count(),
      persistentPlanWorkbenchCount,
      planDetailsOpenedExplicitly: true,
      planDetailsUnmountedAfterClose: (await planWorkbench.count()) === 0,
      explicitPlanDetailIoDelta,
      visibleModeIndicatorCount: await modeStatus.count(),
      goalIndicatorOverridesPlan: (await modeStatus.getAttribute("data-kind")) === "goal",
      goalStatusSingleLine: (await status.count()) === 1,
    };
  }

  /** 视觉矩阵只在主 Goal 运行态采集，避免终态释放状态行后用空截图冒充验收。 */
  async function visualEvidence() {
    return captureVisualMatrix(page, screenshotDirectory, prepareVisualPreferences, deadline);
  }

  /** 首轮 Tool/步骤/evaluator 必须真实落到 not_met；met 留给 soak 后的显式继续。 */
  async function evaluatorEvidence() {
    recordStage("plan_goal_v1:evaluator_not_met");
    const goalId = state.goal.goal.goalId;
    await approveGoalTool(page, { goalId, threadId, toolName: "shell" }, deadline, signal);
    await approveGoalTool(
      page,
      { goalId, threadId, toolName: "plan_step_update" },
      deadline,
      signal,
    );
    await approveGoalTool(
      page,
      { goalId, threadId, toolName: "plan_step_update" },
      deadline,
      signal,
    );
    await approveGoalTool(
      page,
      { goalId, threadId, toolName: "goal_request_evaluation" },
      deadline,
      signal,
    );
    state.goal = await waitForCondition(
      "Goal evaluator not_met",
      async () => {
        const current = requireGoalProjection(
          await invokeCommand(page, "ja_runtime_goal_read", { goalId }),
          "goal evaluator read",
        );
        return current.goal.latestEvaluation?.verdict === "not_met" ? current : false;
      },
      deadline,
      signal,
    );
    setProviderContext({
      goalId,
      goalRevision: state.goal.goal.revision,
      runId: state.goal.goal.currentRunId,
      planRevisionId: state.planRevision.planRevisionId,
      stepId: state.planRevision.steps[0].stepId,
      criterionId: state.planRevision.acceptanceCriteria[0].criterionId,
      projection: { latestEvaluation: state.goal.goal.latestEvaluation },
    });
    state.evaluator = { verdicts: ["not_met"], continuedAfterNotMet: false };
    if (soakMinutes > 0) {
      recordStage("plan_goal_v1:soak_interaction");
      state.goal = await waitForCondition(
        "Goal soak Interaction waiting_input",
        async () => {
          const current = requireGoalProjection(
            await invokeCommand(page, "ja_runtime_goal_read", { goalId }),
            "goal soak read",
          );
          const interaction = await invokeCommand(page, "ja_runtime_interaction_read", { threadId });
          return current.goal.phase === "waiting_input" &&
            interaction.ok === true &&
            interaction.value?.request?.status === "pending" &&
            interaction.value.request.threadId === threadId &&
            interaction.value.request.goalId === goalId &&
            interaction.value.request.runId === current.goal.activeRunId &&
            interaction.value.request.questions?.some((question) => question.questionId === "question_goal_soak_continue")
            ? current
            : false;
        },
        deadline,
        signal,
      );
    }
    return state.evaluator;
  }

  /** 长稳使用公共 Interaction 作为稳定阻塞；窗口结束后显式回答并继续 evaluator。 */
  async function soakEvidence() {
    recordStage("plan_goal_v1:soak");
    const goalId = state.goal.goal.goalId;
    const startedAt = Date.now();
    let healthChecks = 0;
    const soakDeadline = startedAt + soakMinutes * 60_000;
    while (Date.now() < soakDeadline) {
      const current = requireGoalProjection(
        await invokeCommand(page, "ja_runtime_goal_read", { goalId }),
        "goal soak health",
      );
      assert.equal(current.goal.status, "active");
      assert.equal(current.goal.phase, "waiting_input");
      assert.equal(current.goal.latestEvaluation?.verdict, "not_met");
      healthChecks += 1;
      await new Promise((resolvePromise) =>
        setTimeout(resolvePromise, Math.min(30_000, Math.max(1, soakDeadline - Date.now()))),
      );
    }
    if (soakMinutes > 0) {
      recordStage("plan_goal_v1:soak_resume");
      const beforeInteractionResume = state.goal.goal.revision;
      const interaction = await waitForCondition(
        "Goal soak Interaction snapshot",
        async () => {
          const result = await invokeCommand(page, "ja_runtime_interaction_read", { threadId });
          return result.ok === true &&
            result.value?.request?.status === "pending" &&
            result.value.request.goalId === goalId &&
            result.value.request.runId === state.goal.goal.activeRunId &&
            result.value.request.questions?.some((question) => question.questionId === "question_goal_soak_continue")
            ? result.value
            : false;
        },
        deadline,
        signal,
      );
      const answeredInteraction = await invokeCommand(page, "ja_runtime_interaction_respond", {
        threadId,
        requestId: interaction.request.requestId,
        expectedRevision: interaction.request.revision,
        answers: [{
          questionId: "question_goal_soak_continue",
          optionIds: ["option_continue_current_revision"],
          freeText: null,
          skipped: false,
        }],
        idempotencyKey: idempotencyKey("goal-soak-interaction-respond"),
      });
      if (
        answeredInteraction.ok !== true ||
        answeredInteraction.value?.request?.status !== "answered"
      ) {
        throw new Error(`Goal soak Interaction 回答失败：${answeredInteraction.code ?? "UNKNOWN"}`);
      }
      assert.equal(
        answeredInteraction.value.request.answers?.some(
          (answer) => answer.questionId === "question_goal_soak_continue" &&
            answer.optionIds?.includes("option_continue_current_revision") &&
            answer.skipped === false,
        ),
        true,
      );
      state.goal = await waitForCondition(
        "Goal resume after Interaction",
        async () => {
          const current = requireGoalProjection(
            await invokeCommand(page, "ja_runtime_goal_read", { goalId }),
            "goal resume after interaction read",
          );
          return current.goal.revision > beforeInteractionResume && current.goal.phase !== "waiting_input"
            ? current
            : false;
        },
        deadline,
        signal,
      );
      setProviderContext({
        goalId,
        goalRevision: state.goal.goal.revision,
        runId: state.goal.goal.currentRunId,
        planRevisionId: state.planRevision.planRevisionId,
        stepId: state.planRevision.steps[0].stepId,
        criterionId: state.planRevision.acceptanceCriteria[0].criterionId,
        projection: { latestEvaluation: state.goal.goal.latestEvaluation },
      });
    }
    recordStage("plan_goal_v1:evaluator_met");
    await approveGoalTool(
      page,
      { goalId, threadId, toolName: "goal_request_evaluation" },
      deadline,
      signal,
    );
    state.goal = await waitForCondition(
      "Goal evaluator met",
      async () => {
        const current = requireGoalProjection(
          await invokeCommand(page, "ja_runtime_goal_read", { goalId }),
          "goal met read",
        );
        return current.goal.status === "achieved" &&
          current.goal.latestEvaluation?.verdict === "met"
          ? current
          : false;
      },
      deadline,
      signal,
    );
    state.evaluator.verdicts.push("met");
    state.evaluator.continuedAfterNotMet = true;
    recordStage("plan_goal_v1:soak_complete");
    return {
      requestedMinutes: soakMinutes,
      elapsedMs: Date.now() - startedAt,
      healthChecks,
      healthy: true,
    };
  }

  /** 崩溃恢复由共享 runner 持有进程 identity 与 SQLite 事实，本 driver 只校验闭集报告。 */
  async function crashRecoveryEvidence() {
    const recovery = await runCrashRecovery({ page, threadId, deadline, signal, scenario });
    assert.equal(recovery?.status, "passed");
    assert.equal(recovery?.blindReplay, false);
    assert.equal(recovery?.goalStatus, "paused");
    assert.equal(recovery?.goalPhase, "needs_attention");
    return {
      status: "passed",
      noBlindReplay: true,
      recoveredGoalStatus: recovery.goalStatus,
      recoveredGoalPhase: recovery.goalPhase,
    };
  }

  return Object.freeze({
    runtimeEvidence,
    authorityEvidence,
    composerEvidence,
    visualEvidence,
    evaluatorEvidence,
    soakEvidence,
    crashRecoveryEvidence,
  });
}
