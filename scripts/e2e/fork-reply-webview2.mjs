// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
/* global document, window, Element, HTMLButtonElement, HTMLElement, MutationObserver, getComputedStyle, matchMedia */

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { tmpdir } from "node:os";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import { runProduction } from "./review-redesign-production.mjs";
import {
  forkReplyLongAnswerText,
  forkReplyMarkers,
  startForkReplyProviderFixture,
} from "./fixtures/fork-reply-provider.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_JAVA_HOME = "C:\\Users\\24052\\.jdks\\liberica-25.0.2";
const MAX_HISTORY_PAGES = 64;
const PROVIDER_STABILIZE_MS = 350;

/** Playwright 等待沿用单个 E2E deadline，超时诊断不会因为分阶段 helper 重置预算。 @author kongweiguang */
function remaining(deadline) {
  const value = deadline - Date.now();
  if (value <= 0) throw new Error("fork reply WebView2 scenario timed out");
  return Math.min(value, 30_000);
}

/** 证据目录必须是系统临时目录下的专用子目录，避免截图覆盖仓库或用户资料。 @author kongweiguang */
export function assertTemporaryEvidenceDirectory(path) {
  if (typeof path !== "string" || !isAbsolute(path)) {
    throw new Error("evidence directory must be an absolute path");
  }
  const root = resolve(tmpdir());
  const target = resolve(path);
  const relation = relative(root, target);
  if (relation.length === 0 || relation.startsWith("..") || isAbsolute(relation)) {
    throw new Error("evidence directory must be a child of the OS temp directory");
  }
  return target;
}

/** 只在 WebView native invoke 边界注入一次性回执故障；被观测的真实 command 始终先 delegate。 @author kongweiguang */
export function installForkReplyInvokeProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  const records = [];
  globalThis.__JA_FORK_REPLY_INVOKES__ = records;
  const pendingEvidence = [];
  globalThis.__JA_FORK_REPLY_PENDING__ = pendingEvidence;
  const inputEvents = [];
  globalThis.__JA_FORK_REPLY_INPUT_EVENTS__ = inputEvents;
  const faultEvents = [];
  globalThis.__JA_FORK_REPLY_FAULT_EVENTS__ = faultEvents;
  for (const type of ["pointerdown", "pointerup", "touchstart", "touchend", "click"]) {
    document.addEventListener(
      type,
      (event) => {
        const target = event.target;
        const button =
          target instanceof Element ? target.closest('button[data-testid="fork-reply"]') : null;
        if (!(button instanceof HTMLButtonElement)) return;
        inputEvents.push({
          type,
          sourceItemId: button.getAttribute("data-source-item-id"),
          pointerType: "pointerType" in event ? event.pointerType : null,
          isTrusted: event.isTrusted,
        });
      },
      true,
    );
  }
  document.addEventListener(
    "click",
    (event) => {
      const target = event.target;
      const button =
        target instanceof Element ? target.closest('button[data-testid="fork-reply"]') : null;
      if (!(button instanceof HTMLButtonElement)) return;
      const bounds = button.getBoundingClientRect();
      pendingEvidence.push({
        phase: "click",
        sourceItemId: button.getAttribute("data-source-item-id"),
        width: bounds.width,
        height: bounds.height,
      });
    },
    true,
  );
  new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      const button = mutation.target;
      if (
        !(button instanceof HTMLButtonElement) ||
        button.dataset.testid !== "fork-reply" ||
        button.dataset.forkState !== "pending"
      ) {
        continue;
      }
      const bounds = button.getBoundingClientRect();
      pendingEvidence.push({
        phase: "pending",
        sourceItemId: button.getAttribute("data-source-item-id"),
        disabled: button.disabled,
        width: bounds.width,
        height: bounds.height,
      });
    }
  }).observe(document, { subtree: true, attributes: true, attributeFilter: ["data-fork-state"] });
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const command = request?.command;
    const input = request?.args?.input;
    const watched = [
      "ja_thread_fork",
      "ja_operation_read",
      "ja_thread_read",
      "ja_thread_item_content_read",
      "ja_review_snapshot",
    ].includes(command);
    const record = watched
      ? {
          command,
          phase: "start",
          ...(command === "ja_thread_fork"
            ? {
                sourceThreadId: input?.sourceThreadId ?? null,
                sourceItemId: input?.sourceItemId ?? null,
                expectedThreadRevision: input?.expectedThreadRevision ?? null,
                operationKey: input?.operationKey ?? null,
                inputKeys: Object.keys(input ?? {}).sort(),
              }
            : command === "ja_operation_read"
              ? {
                  operationKey: input?.operationKey ?? null,
                  sourceThreadId: input?.sourceThreadId ?? null,
                }
              : command === "ja_thread_read"
                ? {
                    threadId: input?.threadId ?? null,
                    cursorPresent: typeof input?.cursor === "string",
                    limit: input?.limit ?? null,
                  }
                : command === "ja_thread_item_content_read"
                  ? {
                      threadId: input?.threadId ?? null,
                      itemId: input?.itemId ?? null,
                      offsetCharacters: input?.offsetCharacters ?? null,
                      limitCharacters: input?.limitCharacters ?? null,
                    }
                  : {}),
        }
      : undefined;
    if (record !== undefined) records.push(record);
    const faultPlan = globalThis.__JA_FORK_REPLY_FAULT_PLAN__;
    const invokeReal = () => (previous === undefined ? delegate() : previous(request, delegate));
    /** E2E 账本只保留稳定身份与长度，绝不捕获正文或凭据。 @author kongweiguang */
    const recordResult = (result) => {
      if (record === undefined) return result;
      record.phase = "resolved";
      if (command === "ja_thread_fork") {
        record.targetThreadId = result?.thread?.threadId ?? null;
        record.receiptSourceItemId = result?.source?.itemId ?? null;
        record.receiptSourceThreadId = result?.source?.threadId ?? null;
        record.receiptSourceThreadRevision = result?.source?.threadRevision ?? null;
        if (
          faultPlan?.mode === "fail_first_created_thread_read" &&
          input?.sourceThreadId === faultPlan.sourceThreadId &&
          input?.sourceItemId === faultPlan.sourceItemId
        ) {
          faultPlan.operationKey = input.operationKey;
          faultPlan.targetThreadId = record.targetThreadId;
          faultPlan.phase = "native_fork_completed_before_child_read";
        }
      } else if (command === "ja_operation_read") {
        record.resultStatus = result?.status ?? null;
        record.resultMethod = result?.method ?? null;
        record.resultOwnerThreadId = result?.threadId ?? null;
        record.resultTargetThreadId = result?.result?.thread?.threadId ?? null;
        record.resultSourceItemId = result?.result?.source?.itemId ?? null;
      } else if (command === "ja_thread_read") {
        record.resultThreadId = result?.threadId ?? null;
        record.resultRevision = result?.revision ?? null;
        record.resultItemCount = Array.isArray(result?.items) ? result.items.length : null;
        record.resultNextCursorPresent = typeof result?.nextCursor === "string";
      } else if (command === "ja_thread_item_content_read") {
        record.resultItemId = result?.itemId ?? null;
        record.resultOffsetCharacters = result?.offsetCharacters ?? null;
        record.resultNextOffsetCharacters = result?.nextOffsetCharacters ?? null;
        record.resultTotalCharacters = result?.totalCharacters ?? null;
        record.resultTruncated = result?.truncated ?? null;
        record.resultContentCharacters = Array.from(result?.content ?? "").length;
        record.resultContentBytes = new TextEncoder().encode(result?.content ?? "").length;
      }
      return result;
    };
    const matchesSourceAnchor =
      command === "ja_thread_fork" &&
      input?.sourceThreadId === faultPlan?.sourceThreadId &&
      input?.sourceItemId === faultPlan?.sourceItemId;
    try {
      if (matchesSourceAnchor && faultPlan?.mode === "drop_fork_and_operation_read_receipts") {
        const result = recordResult(await invokeReal());
        faultPlan.operationKey = input.operationKey;
        faultPlan.targetThreadId = result?.thread?.threadId ?? null;
        faultPlan.phase = "fork_created_receipt_dropped";
        faultEvents.push({
          scenario: faultPlan.mode,
          command,
          delegated: true,
          responseDropped: true,
          targetThreadId: faultPlan.targetThreadId,
        });
        if (record !== undefined) record.phase = "response_dropped_by_e2e";
        throw new Error("E2E injected a lost fork receipt after the native create completed");
      }
      if (
        command === "ja_operation_read" &&
        faultPlan?.mode === "drop_fork_and_operation_read_receipts" &&
        input?.operationKey === faultPlan.operationKey &&
        input?.sourceThreadId === faultPlan.sourceThreadId &&
        faultPlan.operationReadDropped !== true
      ) {
        const result = recordResult(await invokeReal());
        faultPlan.operationReadDropped = true;
        faultPlan.phase = "owner_receipt_temporarily_unavailable";
        faultEvents.push({
          scenario: faultPlan.mode,
          command,
          delegated: true,
          responseDropped: true,
          ownerThreadId: result?.threadId ?? null,
        });
        if (record !== undefined) record.phase = "response_dropped_by_e2e";
        throw new Error("E2E temporarily dropped the owner-scoped operation/read response");
      }
      if (matchesSourceAnchor && faultPlan?.mode === "delay_fork_receipt_until_selection") {
        const result = recordResult(await invokeReal());
        faultPlan.operationKey = input.operationKey;
        faultPlan.targetThreadId = result?.thread?.threadId ?? null;
        faultPlan.phase = "real_create_waiting_for_ui_receipt";
        faultEvents.push({
          scenario: faultPlan.mode,
          command,
          delegated: true,
          waiting: true,
          targetThreadId: faultPlan.targetThreadId,
        });
        await new Promise((resolve) => {
          faultPlan.release = resolve;
        });
        if (record !== undefined) record.phase = "resolved_after_ui_switch";
        faultPlan.phase = "receipt_released_after_ui_switch";
        faultEvents.push({
          scenario: faultPlan.mode,
          command,
          delegated: true,
          released: true,
          targetThreadId: faultPlan.targetThreadId,
        });
        return result;
      }
      if (
        command === "ja_thread_read" &&
        faultPlan?.mode === "fail_first_created_thread_read" &&
        faultPlan.targetThreadId !== undefined &&
        input?.threadId === faultPlan.targetThreadId &&
        faultPlan.childReadDropped !== true
      ) {
        const result = recordResult(await invokeReal());
        faultPlan.childReadDropped = true;
        faultPlan.phase = "created_child_open_read_failed";
        faultEvents.push({
          scenario: faultPlan.mode,
          command,
          delegated: true,
          responseDropped: true,
          targetThreadId: result?.threadId ?? null,
        });
        if (record !== undefined) record.phase = "response_dropped_by_e2e";
        throw new Error(
          "E2E injected one lost child thread/read response after native read success",
        );
      }
      return recordResult(await invokeReal());
    } catch (error) {
      if (record !== undefined && record.phase !== "response_dropped_by_e2e")
        record.phase = "rejected";
      throw error;
    }
  };
}

/** 为回复正文查找真实 article 锚点，读取 UI 声明的持久 itemId，不按列表下标推算。 @author kongweiguang */
async function readReplyAnchor(page, marker, deadline) {
  const article = page.locator("[data-fork-source-item-id]").filter({ hasText: marker }).last();
  await article.waitFor({ state: "visible", timeout: remaining(deadline) });
  const itemId = await article.getAttribute("data-fork-source-item-id");
  assert.ok(
    typeof itemId === "string" && itemId.length > 0,
    "reply DOM must expose its persisted fork anchor",
  );
  return { article, itemId };
}

/** 回复过程 marker 等待失败时同时保存真实 thread/read 记录、DOM 可见状态与截图，区分持久化和投影问题。 @author kongweiguang */
async function capturePublicWorkFailureEvidence(page, threadId, marker, evidenceDirectory) {
  const snapshot = await readThreadSnapshot(page, threadId).catch((error) => ({
    readError: String(error?.message ?? error).slice(0, 400),
    items: [],
    turns: [],
  }));
  const apiItems = snapshot.items
    .filter((item) => JSON.stringify(item).includes(marker))
    .map((item) => ({
      itemId: item.itemId,
      kind: item.kind,
      origin: item.origin,
      turnId: item.turnId,
      markerPresent: JSON.stringify(item).includes(marker),
    }));
  const dom = await page.evaluate((expectedMarker) => {
    const elements = Array.from(document.querySelectorAll("body *"));
    const matches = elements
      .filter(
        (element) =>
          element.childElementCount === 0 && element.textContent?.includes(expectedMarker),
      )
      .slice(0, 12)
      .map((element) => {
        const style = getComputedStyle(element);
        const rect = element.getBoundingClientRect();
        return {
          tagName: element.tagName,
          text: element.textContent?.slice(0, 800) ?? "",
          display: style.display,
          visibility: style.visibility,
          opacity: style.opacity,
          rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
          parentHtml: element.parentElement?.outerHTML.slice(0, 2_000) ?? "",
        };
      });
    const timelineItems = Array.from(
      document.querySelectorAll("[data-item-id], [data-fork-source-item-id]"),
    )
      .slice(-24)
      .map((element) => ({
        itemId: element.getAttribute("data-item-id"),
        forkSourceItemId: element.getAttribute("data-fork-source-item-id"),
        kind: element.getAttribute("data-kind"),
        text: (element.innerText ?? "").slice(0, 1_200),
        visible:
          element.getBoundingClientRect().width > 0 && element.getBoundingClientRect().height > 0,
      }));
    return { matches, timelineItems };
  }, marker);
  const screenshot = join(evidenceDirectory, "fork-public-work-marker-timeout.png");
  await page.screenshot({ path: screenshot, fullPage: false }).catch(() => undefined);
  const path = join(evidenceDirectory, "fork-public-work-marker-timeout.json");
  await writeFile(
    path,
    `${JSON.stringify({ threadId, marker, apiItems, readError: snapshot.readError ?? null, dom, screenshot }, null, 2)}\n`,
    "utf8",
  );
  return { path, apiItems, dom };
}

/** 已结算工作过程默认折叠；按答复真实 turn 锚点展开，再验证其中的公开摘要，不假设折叠内容可见。 @author kongweiguang */
async function revealPublicWorkForReply(page, article, marker, deadline) {
  const turnId = await article.evaluate((element) =>
    element.closest(".ja-chat-timeline__row")?.getAttribute("data-turn-id"),
  );
  assert.ok(turnId, "reply article must be associated with its persisted Turn");
  const row = page.locator(`.ja-chat-timeline__row[data-turn-id="${turnId}"]`);
  await row.waitFor({ state: "visible", timeout: remaining(deadline) });
  const trigger = row.locator(".ja-work-process__trigger");
  await trigger.waitFor({ state: "visible", timeout: remaining(deadline) });
  const accessibleName = (await trigger.getAttribute("aria-label")) ?? (await trigger.innerText());
  const alreadyExpanded = (await trigger.getAttribute("aria-expanded")) === "true";
  assert.match(accessibleName, alreadyExpanded ? /收起工作过程/u : /查看工作过程/u);
  if (!alreadyExpanded) {
    await trigger.click({ timeout: remaining(deadline) });
  }
  const section = row.locator("section.ja-work-process .ja-work-process__content");
  await section.getByText(marker, { exact: false }).waitFor({
    state: "visible",
    timeout: remaining(deadline),
  });
  return { turnId, section };
}

/** 核对新 Thread 打开后视口停在来源回复附近；缺失时同时保存 API 与 DOM 身份证据，不由测试脚本代替产品滚动。 @author kongweiguang */
async function assertForkPointVisible(page, threadId, sourceItemId, sourceTurnId, deadline) {
  const snapshot = await readThreadSnapshot(page, threadId);
  assertSnapshotOrigins(snapshot);
  const targetItem = findInheritedFinalAnswer(snapshot);
  assert.notEqual(
    targetItem.itemId,
    sourceItemId,
    "forked history must use the destination Thread identity for inherited items",
  );
  assert.notEqual(
    targetItem.turnId,
    sourceTurnId,
    "forked history must use the destination Thread identity for inherited Turns",
  );
  const article = page.locator(`[data-fork-source-item-id="${targetItem.itemId}"]`).last();
  try {
    await article.waitFor({ state: "visible", timeout: Math.min(5_000, remaining(deadline)) });
  } catch (error) {
    const failureSnapshot = await readThreadSnapshot(page, threadId).catch((readError) => ({
      readError: String(readError?.message ?? readError).slice(0, 500),
      items: [],
      turns: [],
    }));
    const dom = await page.evaluate(() =>
      Array.from(
        document.querySelectorAll("article[data-item-id], article[data-fork-source-item-id]"),
      )
        .slice(-32)
        .map((element) => {
          const rect = element.getBoundingClientRect();
          return {
            itemId: element.getAttribute("data-item-id"),
            forkSourceItemId: element.getAttribute("data-fork-source-item-id"),
            text: (element.innerText ?? "").slice(0, 500),
            visible: rect.width > 0 && rect.height > 0,
            rect: { top: rect.top, bottom: rect.bottom },
          };
        }),
    );
    const screenshot = join(page.__forkEvidenceDirectory, "fork-target-location-missing.png");
    await page.screenshot({ path: screenshot, fullPage: false }).catch(() => undefined);
    const evidencePath = join(page.__forkEvidenceDirectory, "fork-target-location-missing.json");
    await writeFile(
      evidencePath,
      `${JSON.stringify(
        {
          threadId,
          expectedSourceItemId: sourceItemId,
          expectedTargetItemId: targetItem.itemId,
          apiItems: failureSnapshot.items.map((item) => ({
            itemId: item.itemId,
            kind: item.kind,
            origin: item.origin,
            turnId: item.turnId,
          })),
          apiTurns: failureSnapshot.turns.map((turn) => ({
            turnId: turn.turnId,
            origin: turn.origin,
            status: turn.status,
          })),
          readError: failureSnapshot.readError ?? null,
          dom,
          screenshot,
          timeout: String(error?.message ?? error).slice(0, 600),
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
    throw new Error(
      `forked reply item ${targetItem.itemId} from source ${sourceItemId} is not rendered; evidence=${evidencePath}`,
    );
  }
  const bounds = await article.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { top: rect.top, bottom: rect.bottom, viewportHeight: globalThis.innerHeight };
  });
  assert.ok(
    bounds.bottom > 0 && bounds.top < bounds.viewportHeight,
    "fork location must remain in the viewport",
  );
}

/** 确认真实按钮经历 disabled pending 状态且尺寸不变，避免请求期间操作栏跳动。 @author kongweiguang */
async function assertForkPendingEvidence(page, itemId) {
  const evidence = await page.evaluate(
    (expected) =>
      (globalThis.__JA_FORK_REPLY_PENDING__ ?? []).filter(
        (entry) => entry.sourceItemId === expected,
      ),
    itemId,
  );
  const click = evidence.findLast((entry) => entry.phase === "click");
  const pending = evidence.findLast((entry) => entry.phase === "pending");
  assert.ok(click, "the production fork button click must be observed");
  assert.ok(pending, "the production fork button must expose its pending state");
  assert.equal(pending.disabled, true);
  assert.equal(pending.width, click.width, "pending loader must preserve button width");
  assert.equal(pending.height, click.height, "pending loader must preserve button height");
}

/** 通过生产 history adapter 创建隔离会话；后续对话、分叉和恢复均经真实界面交互。 @author kongweiguang */
async function createFixtureThread(page, workspaceRoot, endpoint) {
  return page.evaluate(
    async ({ cwd, baseUrl }) => {
      const [{ TauriSettingsAdapter }, { createHistoryAdapter }] = await Promise.all([
        import("/src/api/tauri/settings.ts"),
        import("/src/api/tauri/history.ts"),
      ]);
      const settings = new TauriSettingsAdapter();
      const settingsSnapshot = await settings.snapshot();
      const provider = settingsSnapshot.document.providers.find(
        (candidate) => candidate.providerId === "provider_e2e",
      );
      if (provider === undefined || provider.baseUrl !== baseUrl) {
        throw new Error("the isolated loopback Provider is not configured");
      }
      const modelId = provider.models[0]?.modelId;
      if (modelId === undefined) throw new Error("the isolated loopback model is unavailable");
      const history = createHistoryAdapter();
      const workspace = await history.workspaceOpen({ cwd, displayName: "Reply Fork E2E" });
      await history.workspaceSetTrust({ workspaceId: workspace.workspaceId, trust: "trusted" });
      const thread = await history.threadCreate({
        cwd: workspace.root,
        title: "指定回复分叉验收",
        providerId: provider.providerId,
        modelId,
        reasoningLevel: null,
        accessMode: "full_access",
        collaborationMode: "default",
      });
      return {
        workspaceId: workspace.workspaceId,
        workspaceName: workspace.displayName,
        threadId: thread.threadId,
      };
    },
    { cwd: workspaceRoot, baseUrl: endpoint },
  );
}

/** 等待真实桌面 UI、项目和 Thread identity，避免通过设置 React store 伪造已选会话。 @author kongweiguang */
async function selectThread(page, workspaceName, threadId, deadline) {
  const project = page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"]')
    .filter({ hasText: workspaceName });
  await project.waitFor({ state: "visible", timeout: remaining(deadline) });
  if ((await project.getAttribute("aria-current")) !== "page") {
    await project.click({ timeout: remaining(deadline) });
  }
  const row = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await row.waitFor({ state: "visible", timeout: remaining(deadline) });
  if ((await row.getAttribute("aria-current")) !== "page") {
    await row.click({ timeout: remaining(deadline) });
  }
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: remaining(deadline) },
  );
}

/** 触屏验收仍要通过真实侧栏观察 fork 后的选中会话，响应式收起时只点真实展开控件恢复入口。 @author kongweiguang */
async function ensureNavigationSidebarVisible(page, workspaceName, threadId, deadline) {
  const projectList = page.locator('[aria-label="项目列表"]');
  const historyList = page.locator('[aria-label="最近对话列表"]');
  const sidebarToggle = page.getByRole("button", { name: "显示侧边栏", exact: true });

  if (!(await projectList.isVisible()) && (await sidebarToggle.isVisible())) {
    await sidebarToggle.click({ timeout: remaining(deadline) });
  }
  const expandProjects = page.getByRole("button", { name: "展开项目", exact: true });
  if (await expandProjects.isVisible()) {
    await expandProjects.click({ timeout: remaining(deadline) });
  }
  const expandHistory = page.getByRole("button", { name: "展开最近对话", exact: true });
  if (await expandHistory.isVisible()) {
    await expandHistory.click({ timeout: remaining(deadline) });
  }

  await projectList.waitFor({ state: "visible", timeout: remaining(deadline) });
  await historyList.waitFor({ state: "visible", timeout: remaining(deadline) });
  await projectList
    .locator('button[data-scope-kind="project"]')
    .filter({ hasText: workspaceName })
    .waitFor({ state: "visible", timeout: remaining(deadline) });
  await historyList
    .locator(`button[data-thread-id="${threadId}"]`)
    .waitFor({ state: "visible", timeout: remaining(deadline) });
}

/** 真实 Composer 提交每个 marker，等待该回复的持久 DOM 锚点而非仅凭流式 delta 判定完成。 @author kongweiguang */
async function sendPrompt(page, prompt, answerMarker, deadline) {
  const composer = page.getByRole("textbox", { name: "消息", exact: true });
  await composer.fill(prompt);
  await page
    .getByRole("button", { name: "发送", exact: true })
    .click({ timeout: remaining(deadline) });
  return readReplyAnchor(page, answerMarker, deadline);
}

/** 按 fixture 返回的全量分页读取 history；页数有限且每页身份必须保持一致。 @author kongweiguang */
async function readThreadSnapshot(page, threadId) {
  return page.evaluate(
    async ({ expectedThreadId, maxPages }) => {
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      const history = createHistoryAdapter();
      let cursor;
      let first;
      const items = [];
      const turns = [];
      for (let pageNumber = 0; pageNumber < maxPages; pageNumber += 1) {
        const page = await history.threadRead({
          threadId: expectedThreadId,
          tail: true,
          ...(cursor === undefined ? {} : { cursor }),
          limit: 64,
        });
        if (page.threadId !== expectedThreadId)
          throw new Error("history returned a different Thread");
        first ??= page;
        items.push(...page.items);
        turns.push(...page.turns);
        if (page.nextCursor === null) {
          return { ...first, items, turns, nextCursor: null };
        }
        if (page.nextCursor === cursor) throw new Error("history cursor did not advance");
        cursor = page.nextCursor;
      }
      throw new Error("fork history exceeded the E2E page bound");
    },
    { expectedThreadId: threadId, maxPages: MAX_HISTORY_PAGES },
  );
}

/** Fork 流程失败时只保存 marker 命中、item 身份与分页摘要，区分 provider、持久化和 DOM 呈现而不落正文。 @author kongweiguang */
async function captureForkFailureEvidence({ page, fixture, evidenceDirectory, error }) {
  const diagnostic = {
    error: String(error?.message ?? error).slice(0, 1200),
    providerRequests: fixture.snapshot(),
    selectedThreadId: null,
    dom: [],
    history: null,
    forkInputEvents: [],
    forkBridge: null,
  };
  try {
    const pageEvidence = await page.evaluate(async () => {
      const selectedThreadId = document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id");
      const markers = [
        "JA_FORK_LONG_ANSWER_MARKER",
        "JA_FORK_LONG_BRANCH_ANSWER",
        "JA_FORK_LONG_BRANCH_PROMPT",
      ];
      const dom = [...document.querySelectorAll("[data-fork-source-item-id]")].map((article) => {
        const text = article.querySelector(".ja-chat-message__body")?.innerText ?? "";
        return {
          itemId: article.getAttribute("data-fork-source-item-id"),
          visible: article.getClientRects().length > 0,
          bodyCharacters: text.length,
          markerHits: Object.fromEntries(markers.map((marker) => [marker, text.includes(marker)])),
        };
      });
      const activeStreamArticles = [...document.querySelectorAll("article")].map((article) => {
        const text = article.querySelector(".ja-chat-message__body")?.innerText ?? "";
        return {
          visible: article.getClientRects().length > 0,
          bodyCharacters: text.length,
          containsActivePartial: text.includes("JA_FORK_ACTIVE_PARTIAL"),
          hasForkButton: article.querySelector('button[data-testid="fork-reply"]') !== null,
        };
      });
      const activeTurnRows = [...document.querySelectorAll(".ja-chat-timeline__row")].map((row) => {
        const work = row.querySelector("section.ja-work-process .ja-work-process__content");
        const trigger = row.querySelector(".ja-work-process__trigger");
        const visibleText = row.innerText ?? "";
        const workText = work?.textContent ?? "";
        return {
          turnId: row.getAttribute("data-turn-id"),
          containsPrompt: visibleText.includes("JA_FORK_ACTIVE_PROMPT"),
          containsPartial: visibleText.includes("JA_FORK_ACTIVE_PARTIAL"),
          workContainsPartial: workText.includes("JA_FORK_ACTIVE_PARTIAL"),
          workContentVisible: (work?.getClientRects().length ?? 0) > 0,
          workExpanded: trigger?.getAttribute("aria-expanded") ?? null,
          articleCount: row.querySelectorAll("article").length,
          forkButtonCount: row.querySelectorAll('button[data-testid="fork-reply"]').length,
        };
      });
      let history;
      if (selectedThreadId) {
        try {
          const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
          const page = await createHistoryAdapter().threadRead({
            threadId: selectedThreadId,
            tail: true,
            limit: 200,
          });
          history = {
            threadId: page.threadId,
            itemCount: page.items.length,
            turnCount: page.turns.length,
            nextCursorPresent: page.nextCursor !== null,
            turns: page.turns.map((turn) => ({
              turnId: turn.turnId,
              origin: turn.origin,
              status: turn.status ?? null,
              errorCode: turn.errorCode ?? null,
              updatedAt: turn.updatedAt ?? null,
            })),
            items: page.items.map((item) => {
              const summary = JSON.stringify(item);
              return {
                itemId: item.itemId,
                turnId: item.turnId,
                kind: item.kind,
                origin: item.origin,
                truncated: item.truncated ?? null,
                serializedCharacters: summary.length,
                markerHits: Object.fromEntries(
                  markers.map((marker) => [marker, summary.includes(marker)]),
                ),
              };
            }),
          };
        } catch (historyError) {
          history = { error: String(historyError?.message ?? historyError).slice(0, 600) };
        }
      }
      return {
        selectedThreadId,
        dom,
        activeStreamArticles,
        activeTurnRows,
        history,
        forkInputEvents: globalThis.__JA_FORK_REPLY_INPUT_EVENTS__ ?? [],
        forkPointerEvents: globalThis.__JA_FORK_POINTER_EVENTS__ ?? [],
        forkBridge: {
          invocations: globalThis.__JA_FORK_REPLY_INVOKES__ ?? [],
          faults: globalThis.__JA_FORK_REPLY_FAULT_EVENTS__ ?? [],
        },
      };
    });
    diagnostic.selectedThreadId = pageEvidence.selectedThreadId ?? null;
    diagnostic.dom = pageEvidence.dom;
    diagnostic.activeStreamArticles = pageEvidence.activeStreamArticles;
    diagnostic.activeTurnRows = pageEvidence.activeTurnRows;
    diagnostic.history = pageEvidence.history ?? null;
    diagnostic.forkInputEvents = pageEvidence.forkInputEvents;
    diagnostic.forkPointerEvents = pageEvidence.forkPointerEvents;
    diagnostic.forkBridge = pageEvidence.forkBridge;
  } catch (captureError) {
    diagnostic.captureError = String(captureError?.message ?? captureError).slice(0, 600);
  }
  await writeFile(
    join(evidenceDirectory, "fork-failure-diagnostics.json"),
    `${JSON.stringify(diagnostic, null, 2)}\n`,
    "utf8",
  );
}

/** 从真实 history adapter 分页读取隔离目录，只返回身份和标题供验收核对，不扫描正文。 @author kongweiguang */
async function readThreadDirectory(page, workspaceId) {
  return page.evaluate(async (expectedWorkspaceId) => {
    const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
    const history = createHistoryAdapter();
    const items = [];
    let cursor;
    for (let pageNumber = 0; pageNumber < 16; pageNumber += 1) {
      const result = await history.threadList({
        workspaceId: expectedWorkspaceId,
        limit: 200,
        ...(cursor === undefined ? {} : { cursor }),
      });
      items.push(...result.items);
      if (result.nextCursor === null)
        return items.map(({ threadId, title }) => ({ threadId, title }));
      if (result.nextCursor === cursor) throw new Error("Thread directory cursor did not advance");
      cursor = result.nextCursor;
    }
    throw new Error("Thread directory exceeded the bounded page count");
  }, workspaceId);
}

/** 读取本次页面的脱敏 bridge 账本；正文、模型请求和原生错误 message 都不会进入 E2E 证据。 @author kongweiguang */
async function readForkBridgeEvidence(page) {
  return page.evaluate(() => ({
    invocations: (globalThis.__JA_FORK_REPLY_INVOKES__ ?? []).map((entry) => ({ ...entry })),
    faults: (globalThis.__JA_FORK_REPLY_FAULT_EVENTS__ ?? []).map((entry) => ({ ...entry })),
    inputEvents: (globalThis.__JA_FORK_REPLY_INPUT_EVENTS__ ?? []).map((entry) => ({ ...entry })),
    faultPlan: globalThis.__JA_FORK_REPLY_FAULT_PLAN__
      ? {
          mode: globalThis.__JA_FORK_REPLY_FAULT_PLAN__.mode,
          phase: globalThis.__JA_FORK_REPLY_FAULT_PLAN__.phase,
          targetThreadId: globalThis.__JA_FORK_REPLY_FAULT_PLAN__.targetThreadId ?? null,
          operationKey: globalThis.__JA_FORK_REPLY_FAULT_PLAN__.operationKey ?? null,
        }
      : null,
  }));
}

/** 仅配置当前 WebView 的下一次明确 native invoke 故障；不替换 adapter、IPC 或 Java 响应。 @author kongweiguang */
async function configureForkBridgeFault(page, plan) {
  await page.evaluate((input) => {
    globalThis.__JA_FORK_REPLY_FAULT_PLAN__ = {
      ...input,
      phase: "armed",
      release: undefined,
    };
  }, plan);
}

/** 结束本次单点故障注入；保留原账本供断言，确保后续真实 adapter 请求不再被拦截。 @author kongweiguang */
async function releaseForkBridgeFault(page) {
  await page.evaluate(() => {
    const plan = globalThis.__JA_FORK_REPLY_FAULT_PLAN__;
    if (plan !== undefined) {
      plan.mode = "off";
      plan.release?.();
    }
  });
}

/** 只按 UI 创建后的真实目标 Thread ID 等待选择，不依赖新条目恰好位于列表首位。 @author kongweiguang */
async function waitForSelectedThread(page, threadId, deadline) {
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    threadId,
    { timeout: remaining(deadline) },
  );
}

/** 真实 fork 已创建但首个目标读取响应丢失时，验证同一按钮只重开已保存目标。 @author kongweiguang */
async function runOpenFailureScenario(
  page,
  workspaceName,
  workspaceId,
  sourceThreadId,
  itemId,
  fixture,
  deadline,
) {
  await selectThread(page, workspaceName, sourceThreadId, deadline);
  const beforeThreads = await readThreadDirectory(page, workspaceId);
  const beforeProviderRequests = fixture.snapshot().length;
  const button = page.locator(`button[data-testid="fork-reply"][data-source-item-id="${itemId}"]`);
  await button.waitFor({ state: "visible", timeout: remaining(deadline) });
  const beforeForkCalls = await page.evaluate(
    () =>
      globalThis.__JA_FORK_REPLY_INVOKES__?.filter((entry) => entry.command === "ja_thread_fork")
        .length ?? 0,
  );
  await configureForkBridgeFault(page, {
    mode: "fail_first_created_thread_read",
    sourceThreadId,
    sourceItemId: itemId,
  });
  await clickForkReplyOnce(page, button);
  await page.waitForFunction(
    (expected) => {
      const target = document.querySelector(
        `button[data-testid="fork-reply"][data-source-item-id="${expected}"]`,
      );
      return target?.getAttribute("data-fork-state") === "open-failed";
    },
    itemId,
    { timeout: remaining(deadline) },
  );
  assert.match(await button.getAttribute("aria-label"), /已创建，打开失败/u);
  const failedEvidence = await readForkBridgeEvidence(page);
  const firstFork = failedEvidence.invocations.find(
    (entry) => entry.command === "ja_thread_fork" && entry.sourceItemId === itemId,
  );
  assert.ok(firstFork?.targetThreadId, "the actual delegated fork must return a durable target");
  assert.equal(firstFork.phase, "resolved");
  assert.ok(
    failedEvidence.faults.some(
      (entry) =>
        entry.scenario === "fail_first_created_thread_read" &&
        entry.delegated === true &&
        entry.responseDropped === true &&
        entry.targetThreadId === firstFork.targetThreadId,
    ),
    "the fault must drop only the first post-create target read after its native call succeeded",
  );
  const afterFailedOpen = await readThreadDirectory(page, workspaceId);
  assert.equal(afterFailedOpen.length, beforeThreads.length + 1);
  assert.ok(afterFailedOpen.some((thread) => thread.threadId === firstFork.targetThreadId));
  const sourceComposer = page.getByRole("textbox", { name: "消息", exact: true });
  await page.waitForFunction(
    (element) => document.activeElement === element,
    await sourceComposer.elementHandle(),
    { timeout: remaining(deadline) },
  );

  await releaseForkBridgeFault(page);
  await clickForkReplyOnce(page, button);
  await waitForSelectedThread(page, firstFork.targetThreadId, deadline);
  const afterRetry = await readThreadDirectory(page, workspaceId);
  const forkCallsAfterRetry = await page.evaluate(
    () =>
      globalThis.__JA_FORK_REPLY_INVOKES__?.filter((entry) => entry.command === "ja_thread_fork")
        .length ?? 0,
  );
  assert.equal(forkCallsAfterRetry, beforeForkCalls + 1);
  assert.equal(afterRetry.length, beforeThreads.length + 1);
  assert.equal(fixture.snapshot().length, beforeProviderRequests);
  await page.screenshot({
    path: join(page.__forkEvidenceDirectory, "fork-open-failure-retry.png"),
    fullPage: false,
  });
  return { targetThreadId: firstFork.targetThreadId, createdOnce: true, reopenedSameTarget: true };
}

/** 在已创建与回执交付之间切换来源会话，核验迟到回执只更新目录、不抢占当前选择。 @author kongweiguang */
async function runDelayedReceiptScenario(
  page,
  workspaceName,
  workspaceId,
  sourceThreadId,
  otherThreadId,
  itemId,
  fixture,
  deadline,
) {
  await selectThread(page, workspaceName, sourceThreadId, deadline);
  const beforeThreads = await readThreadDirectory(page, workspaceId);
  const beforeProviderRequests = fixture.snapshot().length;
  const beforeForkCalls = await page.evaluate(
    () =>
      globalThis.__JA_FORK_REPLY_INVOKES__?.filter((entry) => entry.command === "ja_thread_fork")
        .length ?? 0,
  );
  const button = page.locator(`button[data-testid="fork-reply"][data-source-item-id="${itemId}"]`);
  await button.waitFor({ state: "visible", timeout: remaining(deadline) });
  await configureForkBridgeFault(page, {
    mode: "delay_fork_receipt_until_selection",
    sourceThreadId,
    sourceItemId: itemId,
  });
  await clickForkReplyOnce(page, button);
  await page.waitForFunction(
    () => globalThis.__JA_FORK_REPLY_FAULT_PLAN__?.phase === "real_create_waiting_for_ui_receipt",
    undefined,
    { timeout: remaining(deadline) },
  );
  const waiting = await readForkBridgeEvidence(page);
  const delayedFork = waiting.invocations.find(
    (entry) => entry.command === "ja_thread_fork" && entry.sourceItemId === itemId,
  );
  const targetThreadId = delayedFork?.targetThreadId;
  assert.ok(targetThreadId, "native fork must have completed before the UI receipt is held");
  assert.ok(
    (await readThreadDirectory(page, workspaceId)).some((item) => item.threadId === targetThreadId),
  );

  await selectThread(page, workspaceName, otherThreadId, deadline);
  await releaseForkBridgeFault(page);
  await page.waitForFunction(
    () => globalThis.__JA_FORK_REPLY_FAULT_PLAN__?.phase === "receipt_released_after_ui_switch",
    undefined,
    { timeout: remaining(deadline) },
  );
  await page
    .locator(`[aria-label="最近对话列表"] button[data-thread-id="${targetThreadId}"]`)
    .waitFor({ state: "visible", timeout: remaining(deadline) });
  await waitForSelectedThread(page, otherThreadId, deadline);
  const afterRelease = await readThreadDirectory(page, workspaceId);
  assert.equal(afterRelease.length, beforeThreads.length + 1);
  assert.ok(afterRelease.some((item) => item.threadId === targetThreadId));
  const invocations = await readForkBridgeEvidence(page);
  assert.ok(
    invocations.faults.some(
      (entry) =>
        entry.scenario === "delay_fork_receipt_until_selection" &&
        entry.delegated === true &&
        entry.waiting === true &&
        entry.targetThreadId === targetThreadId,
    ),
  );
  assert.equal(
    invocations.invocations.filter((entry) => entry.command === "ja_thread_fork").length,
    beforeForkCalls + 1,
  );
  assert.equal(fixture.snapshot().length, beforeProviderRequests);

  await selectThread(page, workspaceName, sourceThreadId, deadline);
  const retryButton = page.locator(
    `button[data-testid="fork-reply"][data-source-item-id="${itemId}"]`,
  );
  await retryButton.waitFor({ state: "visible", timeout: remaining(deadline) });
  await clickForkReplyOnce(page, retryButton);
  await waitForSelectedThread(page, targetThreadId, deadline);
  assert.equal(
    await page.evaluate(
      () =>
        globalThis.__JA_FORK_REPLY_INVOKES__?.filter((entry) => entry.command === "ja_thread_fork")
          .length ?? 0,
    ),
    beforeForkCalls + 1,
    "opening the delayed committed receipt must not create a second child",
  );
  await page.screenshot({
    path: join(page.__forkEvidenceDirectory, "fork-delayed-receipt-selection.png"),
    fullPage: false,
  });
  return { targetThreadId, selectedThreadPreserved: otherThreadId };
}

/** 丢弃真实 fork 与 owner receipt 的桥接响应后重载，再验证原请求恢复并只打开既有 child。 @author kongweiguang */
async function runLostReceiptRecoveryScenario(
  page,
  workspaceName,
  workspaceId,
  sourceThreadId,
  itemId,
  fixture,
  deadline,
) {
  await selectThread(page, workspaceName, sourceThreadId, deadline);
  const beforeThreads = await readThreadDirectory(page, workspaceId);
  const beforeProviderRequests = fixture.snapshot().length;
  const button = page.locator(`button[data-testid="fork-reply"][data-source-item-id="${itemId}"]`);
  await button.waitFor({ state: "visible", timeout: remaining(deadline) });
  await configureForkBridgeFault(page, {
    mode: "drop_fork_and_operation_read_receipts",
    sourceThreadId,
    sourceItemId: itemId,
  });
  await clickForkReplyOnce(page, button);
  await page.waitForFunction(
    (expected) =>
      document
        .querySelector(`button[data-testid="fork-reply"][data-source-item-id="${expected}"]`)
        ?.getAttribute("data-fork-state") === "unconfirmed",
    itemId,
    { timeout: remaining(deadline) },
  );
  const unknownEvidence = await readForkBridgeEvidence(page);
  const droppedFork = unknownEvidence.invocations.find(
    (entry) => entry.command === "ja_thread_fork" && entry.sourceItemId === itemId,
  );
  const targetThreadId = droppedFork?.targetThreadId;
  const operationKey = droppedFork?.operationKey;
  assert.ok(targetThreadId && operationKey);
  assert.ok(
    unknownEvidence.faults.some(
      (entry) =>
        entry.scenario === "drop_fork_and_operation_read_receipts" &&
        entry.command === "ja_thread_fork" &&
        entry.delegated === true &&
        entry.responseDropped === true &&
        entry.targetThreadId === targetThreadId,
    ),
  );
  assert.ok(
    unknownEvidence.faults.some(
      (entry) =>
        entry.scenario === "drop_fork_and_operation_read_receipts" &&
        entry.command === "ja_operation_read" &&
        entry.delegated === true &&
        entry.responseDropped === true &&
        entry.ownerThreadId === sourceThreadId,
    ),
    "the source owner receipt must be read natively before the E2E boundary withholds it",
  );
  const directoryAfterUnknown = await readThreadDirectory(page, workspaceId);
  assert.equal(directoryAfterUnknown.length, beforeThreads.length + 1);
  assert.ok(directoryAfterUnknown.some((thread) => thread.threadId === targetThreadId));
  await releaseForkBridgeFault(page);

  await page.reload({ waitUntil: "domcontentloaded", timeout: remaining(deadline) });
  await restoreRuntimeAfterReload(page, deadline);
  await selectThread(page, workspaceName, sourceThreadId, deadline);
  await page.waitForFunction(
    ({ expectedOperationKey, expectedSourceThreadId }) =>
      (globalThis.__JA_FORK_REPLY_INVOKES__ ?? []).some(
        (entry) =>
          entry.command === "ja_operation_read" &&
          entry.operationKey === expectedOperationKey &&
          entry.sourceThreadId === expectedSourceThreadId &&
          entry.phase === "resolved" &&
          entry.resultStatus === "committed",
      ),
    { expectedOperationKey: operationKey, expectedSourceThreadId: sourceThreadId },
    { timeout: remaining(deadline) },
  );
  await page.waitForFunction(
    (expected) =>
      document
        .querySelector(`button[data-testid="fork-reply"][data-source-item-id="${expected}"]`)
        ?.getAttribute("data-fork-state") === "created",
    itemId,
    { timeout: remaining(deadline) },
  );
  const recoveredEvidence = await readForkBridgeEvidence(page);
  const recoveredReceipt = recoveredEvidence.invocations.find(
    (entry) =>
      entry.command === "ja_operation_read" &&
      entry.operationKey === operationKey &&
      entry.sourceThreadId === sourceThreadId,
  );
  assert.equal(recoveredReceipt?.resultTargetThreadId, targetThreadId);
  const recoveredButton = page.locator(
    `button[data-testid="fork-reply"][data-source-item-id="${itemId}"]`,
  );
  await clickForkReplyOnce(page, recoveredButton);
  await waitForSelectedThread(page, targetThreadId, deadline);
  const afterRecoveryEvidence = await readForkBridgeEvidence(page);
  const afterRecoveryOpen = await readThreadDirectory(page, workspaceId);
  assert.equal(afterRecoveryOpen.length, beforeThreads.length + 1);
  assert.equal(
    afterRecoveryEvidence.invocations.filter((entry) => entry.command === "ja_thread_fork").length,
    0,
    "reload recovery and reopening must use the owner receipt, never create another fork",
  );
  assert.equal(fixture.snapshot().length, beforeProviderRequests);
  await page.screenshot({
    path: join(page.__forkEvidenceDirectory, "fork-lost-receipt-recovered.png"),
    fullPage: false,
  });
  return { targetThreadId, operationKey, ownerReceiptRecovered: true, createdOnce: true };
}

/** 以生产 history adapter 逐页读取公开正文，并只返回分页元数据与待做字节比较的完整文本。 @author kongweiguang */
async function readCompleteItemContent(page, threadId, itemId) {
  return page.evaluate(
    async ({ expectedThreadId, expectedItemId, maxPages }) => {
      const { createHistoryAdapter } = await import("/src/api/tauri/history.ts");
      const history = createHistoryAdapter();
      const pages = [];
      let offsetCharacters = 0;
      let content = "";
      for (let index = 0; index < maxPages; index += 1) {
        const page = await history.itemContentRead({
          threadId: expectedThreadId,
          itemId: expectedItemId,
          offsetCharacters,
          limitCharacters: 65_536,
        });
        const pageCharacters = Array.from(page.content).length;
        if (
          page.itemId !== expectedItemId ||
          page.offsetCharacters !== offsetCharacters ||
          pageCharacters === 0 ||
          page.totalCharacters < offsetCharacters + pageCharacters ||
          page.truncated !== (page.nextOffsetCharacters !== null)
        )
          throw new Error("item-content/read returned an invalid page identity or offset");
        pages.push({
          offsetCharacters: page.offsetCharacters,
          nextOffsetCharacters: page.nextOffsetCharacters,
          totalCharacters: page.totalCharacters,
          truncated: page.truncated,
          contentCharacters: pageCharacters,
          contentBytes: new TextEncoder().encode(page.content).length,
        });
        content += page.content;
        if (page.nextOffsetCharacters === null) {
          if (Array.from(content).length !== page.totalCharacters)
            throw new Error("item-content/read completed before the authoritative character count");
          return {
            itemId: page.itemId,
            totalCharacters: page.totalCharacters,
            content,
            contentBytes: new TextEncoder().encode(content).length,
            pages,
          };
        }
        if (page.nextOffsetCharacters !== offsetCharacters + pageCharacters)
          throw new Error("item-content/read cursor skipped characters");
        offsetCharacters = page.nextOffsetCharacters;
      }
      throw new Error("item-content/read exceeded the bounded page count");
    },
    { expectedThreadId: threadId, expectedItemId: itemId, maxPages: MAX_HISTORY_PAGES },
  );
}

/** 断言完整冻结快照的 origin 仅属两态，并核对每个预期回复在读取历史中只出现一次。 @author kongweiguang */
export function assertSnapshotOrigins(snapshot) {
  assert.ok(Array.isArray(snapshot?.items), "thread/read must return items");
  assert.ok(Array.isArray(snapshot?.turns), "thread/read must return turns");
  for (const item of snapshot.items) assert.ok(["native", "inherited"].includes(item.origin));
  for (const turn of snapshot.turns) assert.ok(["native", "inherited"].includes(turn.origin));
}

/** 冻结快照必须止于已完成锚点，不能把来源后续活动 Turn、错误码或时间投影到子会话。 @author kongweiguang */
export function assertForkChildTurnBoundary(snapshot, boundaryCompletedAt) {
  assertSnapshotOrigins(snapshot);
  assert.equal(snapshot.liveStream, null, "a forked child cannot inherit a source live stream");
  const boundaryMillis = Date.parse(boundaryCompletedAt);
  assert.ok(
    Number.isFinite(boundaryMillis),
    "the selected reply must have a valid completion time",
  );
  const terminalStates = new Set(["completed", "failed", "cancelled"]);
  const activeTurnIds = snapshot.turns
    .filter((turn) => !terminalStates.has(turn.status))
    .map((turn) => turn.turnId);
  const inheritedActiveTurnIds = snapshot.turns
    .filter((turn) => turn.origin === "inherited" && !terminalStates.has(turn.status))
    .map((turn) => turn.turnId);
  assert.deepEqual(activeTurnIds, [], "fork history cannot expose active Turn identities");
  assert.deepEqual(inheritedActiveTurnIds, [], "inherited Turns cannot remain active");

  const inheritedTurns = snapshot.turns.filter((turn) => turn.origin === "inherited");
  assert.ok(inheritedTurns.length > 0, "the selected reply must freeze inherited Turn history");
  for (const turn of inheritedTurns) {
    assert.equal(turn.errorCode, null, "the successful fork prefix cannot inherit a later error");
    assert.ok(turn.completedAt !== null, "each inherited settled Turn must have a completion time");
    for (const field of ["requestedAt", "updatedAt", "completedAt"]) {
      const timestamp = Date.parse(turn[field]);
      assert.ok(Number.isFinite(timestamp), `inherited ${field} must be a valid timestamp`);
      assert.ok(
        timestamp <= boundaryMillis,
        `inherited ${field} must not extend past the selected reply boundary`,
      );
    }
  }
}

/** 用 item kind/origin 在目标 Thread 自己的身份域中找继承答复；跨 Thread 只用 marker 关联，不复用来源 ID。 @author kongweiguang */
export function findInheritedFinalAnswer(snapshot, marker = undefined) {
  const candidates = snapshot.items.filter(
    (item) => item.kind === "final_answer" && item.origin === "inherited",
  );
  if (marker !== undefined) {
    const matches = candidates.filter((item) => JSON.stringify(item).includes(marker));
    assert.equal(
      matches.length,
      1,
      `child history must contain one inherited final answer for ${marker}`,
    );
    return matches[0];
  }
  assert.ok(candidates.length > 0, "child history must contain an inherited final answer");
  return candidates.at(-1);
}

/** 鼠标先使操作栏进入真实 hover 状态，再验证坐标命中，不以 force/DOM click 绕过产品交互。 @author kongweiguang */
async function moveToForkReplyAndAssertHit(page, button) {
  await button.scrollIntoViewIfNeeded();
  const itemId = await button.getAttribute("data-source-item-id");
  assert.ok(itemId, "fork button must expose its real source item identity");
  const article = page.locator(`article[data-fork-source-item-id="${itemId}"]`);
  await article.waitFor({ state: "visible" });
  const articleBox = await article.boundingBox();
  assert.ok(articleBox, "fork reply article must have visible geometry");
  await page.evaluate(() => {
    globalThis.__JA_FORK_POINTER_EVENTS__ ??= [];
    if (globalThis.__JA_FORK_POINTER_PROBE_INSTALLED__ === true) return;
    globalThis.__JA_FORK_POINTER_PROBE_INSTALLED__ = true;
    for (const type of [
      "pointermove",
      "pointerover",
      "pointerenter",
      "pointerleave",
      "mouseover",
      "mousemove",
      "mouseout",
    ]) {
      document.addEventListener(
        type,
        (event) => {
          const target = event.target instanceof Element ? event.target : null;
          const owner = target?.closest("article[data-fork-source-item-id]");
          const related =
            event.relatedTarget instanceof Element
              ? event.relatedTarget.closest("article[data-fork-source-item-id]")
              : null;
          if ((owner === null || owner === undefined) && related === null) {
            return;
          }
          globalThis.__JA_FORK_POINTER_EVENTS__.push({
            type,
            trusted: event.isTrusted,
            itemId: owner?.getAttribute("data-fork-source-item-id") ?? null,
            relatedItemId: related?.getAttribute("data-fork-source-item-id") ?? null,
            target: target?.tagName ?? null,
            hasFocus: document.hasFocus(),
            className: typeof target.className === "string" ? target.className : "",
            clientX: event.clientX,
            clientY: event.clientY,
          });
        },
        true,
      );
    }
  });
  const articlePoint = {
    x: articleBox.x + Math.min(articleBox.width / 2, 240),
    y: articleBox.y + articleBox.height / 2,
  };
  await page.mouse.move(articlePoint.x, articlePoint.y);
  try {
    await page.waitForFunction(
      (expectedItemId) => {
        const article = document.querySelector(
          `article[data-fork-source-item-id="${expectedItemId}"]`,
        );
        const actionBar = article?.querySelector(".ja-chat-message__actions");
        return (
          article?.matches(":hover") === true &&
          actionBar !== null &&
          getComputedStyle(actionBar).pointerEvents === "auto"
        );
      },
      itemId,
      { timeout: 2_500 },
    );
  } catch (hoverError) {
    const hoverEvidence = await page.evaluate((expectedItemId) => {
      const article = document.querySelector(
        `article[data-fork-source-item-id="${expectedItemId}"]`,
      );
      const actionBar = article?.querySelector(".ja-chat-message__actions");
      const rect = article?.getBoundingClientRect();
      return {
        itemId: expectedItemId,
        documentHasFocus: document.hasFocus(),
        articleConnected: article?.isConnected ?? false,
        articleMatchesHover: article?.matches(":hover") ?? false,
        articleRect: rect ? { x: rect.x, y: rect.y, width: rect.width, height: rect.height } : null,
        pointTarget:
          rect === undefined
            ? null
            : (document.elementFromPoint(
                rect.x + Math.min(rect.width / 2, 240),
                rect.y + rect.height / 2,
              )?.tagName ?? null),
        actionBar: actionBar
          ? {
              opacity: getComputedStyle(actionBar).opacity,
              pointerEvents: getComputedStyle(actionBar).pointerEvents,
            }
          : null,
        hoverPath: [...document.querySelectorAll(":hover")].map((node) => ({
          tagName: node.tagName,
          className: typeof node.className === "string" ? node.className : "",
          forkSourceItemId: node.getAttribute("data-fork-source-item-id"),
        })),
        pointerEvents: (globalThis.__JA_FORK_POINTER_EVENTS__ ?? []).filter(
          (event) => event.itemId === expectedItemId || event.relatedItemId === expectedItemId,
        ),
      };
    }, itemId);
    if (page.__forkEvidenceDirectory !== undefined) {
      await page
        .screenshot({
          path: join(page.__forkEvidenceDirectory, "fork-real-hover-failure.png"),
          fullPage: false,
        })
        .catch(() => undefined);
      await writeFile(
        join(page.__forkEvidenceDirectory, "fork-real-hover-failure.json"),
        `${JSON.stringify(hoverEvidence, null, 2)}\n`,
        "utf8",
      );
    }
    throw new Error(
      `real mouse movement did not activate reply hover: ${JSON.stringify(hoverEvidence)}; ${String(hoverError?.message ?? hoverError)}`,
    );
  }
  const point = await button.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  });
  await page.mouse.move(point.x, point.y);
  await page.waitForFunction(
    (expectedItemId) => {
      const article = document.querySelector(
        `article[data-fork-source-item-id="${expectedItemId}"]`,
      );
      const actionBar = article?.querySelector(".ja-chat-message__actions");
      return (
        article?.matches(":hover") === true &&
        actionBar !== null &&
        getComputedStyle(actionBar).pointerEvents === "auto" &&
        Number(getComputedStyle(actionBar).opacity) > 0.95
      );
    },
    itemId,
    { timeout: 2_500 },
  );
  const hitTest = await button.evaluate((element, coordinates) => {
    const target = document.elementFromPoint(coordinates.x, coordinates.y);
    const styleSummary = (node) => {
      const style = getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return {
        tagName: node.tagName,
        className: typeof node.className === "string" ? node.className : "",
        testId: node.getAttribute("data-testid"),
        itemId: node.getAttribute("data-item-id"),
        forkSourceItemId: node.getAttribute("data-fork-source-item-id"),
        position: style.position,
        zIndex: style.zIndex,
        pointerEvents: style.pointerEvents,
        opacity: style.opacity,
        visibility: style.visibility,
        rect: { x: rect.x, y: rect.y, width: rect.width, height: rect.height },
        outerHtml: node.outerHTML.slice(0, 1_200),
      };
    };
    const article = element.closest("article[data-fork-source-item-id]");
    const actionBar = article?.querySelector(".ja-chat-message__actions");
    const actionBarStyle = actionBar == null ? null : getComputedStyle(actionBar);
    const hoverPath = [];
    for (
      let node = document.querySelector(":hover");
      node !== null;
      node = node.querySelector(":hover")
    ) {
      hoverPath.push({
        tagName: node.tagName,
        className: typeof node.className === "string" ? node.className : "",
        itemId: node.getAttribute("data-item-id"),
        forkSourceItemId: node.getAttribute("data-fork-source-item-id"),
        testId: node.getAttribute("data-testid"),
      });
    }
    const ancestors = [];
    for (let node = element; node !== null && ancestors.length < 8; node = node.parentElement) {
      ancestors.push(styleSummary(node));
      if (node === article) break;
    }
    const articleBefore = article === null ? null : getComputedStyle(article, "::before");
    const articleAfter = article === null ? null : getComputedStyle(article, "::after");
    return {
      point: coordinates,
      documentHasFocus: document.hasFocus(),
      articleMatchesHover: article?.matches(":hover") ?? false,
      targetIsButton: target === element || element.contains(target),
      target: target === null ? null : styleSummary(target),
      stack: document.elementsFromPoint(coordinates.x, coordinates.y).slice(0, 6).map(styleSummary),
      hoverPath,
      pointerEvents: globalThis.__JA_FORK_POINTER_EVENTS__ ?? [],
      ancestors,
      actionBar:
        actionBarStyle === null
          ? null
          : {
              opacity: actionBarStyle.opacity,
              pointerEvents: actionBarStyle.pointerEvents,
              transform: actionBarStyle.transform,
            },
      articlePseudo: {
        before:
          articleBefore === null
            ? null
            : {
                content: articleBefore.content,
                position: articleBefore.position,
                zIndex: articleBefore.zIndex,
                pointerEvents: articleBefore.pointerEvents,
              },
        after:
          articleAfter === null
            ? null
            : {
                content: articleAfter.content,
                position: articleAfter.position,
                zIndex: articleAfter.zIndex,
                pointerEvents: articleAfter.pointerEvents,
              },
      },
    };
  }, point);
  if (page.__forkEvidenceDirectory !== undefined && !page.__forkHitTestCaptured) {
    const screenshot = join(page.__forkEvidenceDirectory, "fork-button-hit-test.png");
    await page.screenshot({ path: screenshot, fullPage: false });
    await writeFile(
      join(page.__forkEvidenceDirectory, "fork-button-hit-test.json"),
      `${JSON.stringify({ ...hitTest, screenshot }, null, 2)}\n`,
      "utf8",
    );
    page.__forkHitTestCaptured = true;
  }
  assert.ok(
    hitTest.actionBar?.pointerEvents === "auto" && Number(hitTest.actionBar.opacity) > 0.95,
    `hovered fork action bar must be visible and pointer-enabled: ${JSON.stringify(hitTest.actionBar)}`,
  );
  assert.ok(
    hitTest.targetIsButton,
    `real pointer hit test missed the fork button: ${JSON.stringify(hitTest)}`,
  );
  return point;
}

/** 故障恢复场景也通过真实鼠标单击，验证悬停后的图标可以由用户命中。 @author kongweiguang */
async function clickForkReplyOnce(page, button) {
  const point = await moveToForkReplyAndAssertHit(page, button);
  await page.mouse.click(point.x, point.y);
}

/** 双击回归保留真实鼠标时序，并只允许组件提交一条原生创建请求。 @author kongweiguang */
async function clickForkReplyTwice(page, button) {
  const point = await moveToForkReplyAndAssertHit(page, button);
  await page.mouse.click(point.x, point.y);
  await page.mouse.click(point.x, point.y, { delay: 80 });
}

/** 等待按钮真正完成新会话创建和选中，再核对只产生一次 native fork 请求。 @author kongweiguang */
async function forkReply(
  page,
  itemId,
  expectedSourceThreadId,
  fixture,
  deadline,
  activate = (button) => clickForkReplyTwice(page, button),
) {
  const button = page.locator(`button[data-testid="fork-reply"][data-source-item-id="${itemId}"]`);
  await button.waitFor({ state: "visible", timeout: remaining(deadline) });
  assert.equal(await button.getAttribute("aria-label"), "从此回复新建会话");
  assert.equal(await button.getAttribute("title"), "从此回复新建会话");
  const sourceSnapshot = await readThreadSnapshot(page, expectedSourceThreadId);
  assertSnapshotOrigins(sourceSnapshot);
  const sourceItem = sourceSnapshot.items.find((item) => item.itemId === itemId);
  assert.equal(sourceItem?.kind, "final_answer", "fork anchor must belong to a settled answer");
  assert.equal(sourceItem?.origin === "native" || sourceItem?.origin === "inherited", true);
  const beforeProviderRequests = fixture.snapshot().length;
  const beforeForkCalls = await page.evaluate(
    () =>
      globalThis.__JA_FORK_REPLY_INVOKES__?.filter((entry) => entry.command === "ja_thread_fork")
        .length ?? 0,
  );
  await activate(button);
  await page.waitForFunction(
    (previous) =>
      globalThis.__JA_FORK_REPLY_INVOKES__
        ?.filter((entry) => entry.command === "ja_thread_fork")
        .slice(previous)
        .some((entry) => entry.phase === "resolved") ?? false,
    beforeForkCalls,
    { timeout: remaining(deadline) },
  );
  await page.waitForTimeout(250);
  const observed = await page.evaluate(
    (offset) =>
      (globalThis.__JA_FORK_REPLY_INVOKES__ ?? [])
        .filter((entry) => entry.command === "ja_thread_fork")
        .slice(offset),
    beforeForkCalls,
  );
  assert.equal(observed.length, 1, "double click must create one fork request");
  assert.deepEqual(observed[0].inputKeys, [
    "expectedThreadRevision",
    "operationKey",
    "sourceItemId",
    "sourceThreadId",
  ]);
  assert.equal(observed[0].sourceThreadId, expectedSourceThreadId);
  assert.equal(observed[0].sourceItemId, itemId);
  assert.ok(Number.isSafeInteger(observed[0].expectedThreadRevision));
  assert.match(observed[0].operationKey ?? "", /^op_[A-Za-z0-9_-]+$/u);
  const result = await page.evaluate((offset) => {
    const entries = globalThis.__JA_FORK_REPLY_INVOKES__ ?? [];
    return entries
      .filter((entry) => entry.command === "ja_thread_fork" && entry.phase === "resolved")
      .slice(offset)
      .at(-1);
  }, beforeForkCalls);
  assert.ok(typeof result?.targetThreadId === "string" && result.targetThreadId.length > 0);
  assert.equal(result.receiptSourceItemId, itemId);
  assert.equal(result.receiptSourceThreadId, expectedSourceThreadId);
  assert.equal(result.receiptSourceThreadRevision, observed[0].expectedThreadRevision);
  await assertForkPendingEvidence(page, itemId);
  await page.waitForFunction(
    (expected) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") === expected,
    result.targetThreadId,
    { timeout: remaining(deadline) },
  );
  const composer = page.getByRole("textbox", { name: "消息", exact: true });
  await composer.waitFor({ state: "visible" });
  assert.equal(await composer.inputValue(), "");
  await page.waitForFunction(
    (element) => document.activeElement === element,
    await composer.elementHandle(),
    {
      timeout: remaining(deadline),
    },
  );
  await assertForkPointVisible(page, result.targetThreadId, itemId, sourceItem.turnId, deadline);
  assert.equal(
    fixture.snapshot().length,
    beforeProviderRequests,
    "fork must not issue any Provider request",
  );
  return result.targetThreadId;
}

/** 在实际回复操作栏核验复制与分叉相邻、窄窗不裁切，证据来自 Chromium layout boxes。 @author kongweiguang */
async function inspectReplyActionGeometry(page, itemId) {
  return page
    .locator(`button[data-testid="fork-reply"][data-source-item-id="${itemId}"]`)
    .evaluate((button) => {
      const group = button.closest("[role='group']");
      if (!(group instanceof HTMLElement)) throw new Error("reply action group is missing");
      const controls = [...group.querySelectorAll("button")];
      const forkIndex = controls.indexOf(button);
      const copyIndex = controls.findIndex(
        (control) => control.getAttribute("aria-label") === "复制回复",
      );
      const rect = button.getBoundingClientRect();
      const copyRect = controls[copyIndex]?.getBoundingClientRect();
      const groupRect = group.getBoundingClientRect();
      return {
        forkIndex,
        copyIndex,
        copyRight: copyRect?.right ?? null,
        copyLeft: copyRect?.left ?? null,
        forkLeft: rect.left,
        forkRight: rect.right,
        groupLeft: groupRect.left,
        groupRight: groupRect.right,
        viewportWidth: globalThis.innerWidth,
        visible:
          rect.width > 0 && rect.height > 0 && getComputedStyle(button).visibility !== "hidden",
        opacity: Number(getComputedStyle(button).opacity),
        focusVisible: button.matches(":focus-visible"),
      };
    });
}

/** 读取 invoke probe 的稳定计数；返回值不暴露调用参数或 Thread 正文。 @author kongweiguang */
async function reviewSnapshotCount(page) {
  return page.evaluate(
    () =>
      (globalThis.__JA_FORK_REPLY_INVOKES__ ?? []).filter(
        (entry) => entry.command === "ja_review_snapshot",
      ).length,
  );
}

/** fresh page reload 后恢复实际 Rust/Java runtime generation，再记录 CDP 焦点模拟结果并通过导航重新打开 Thread。 @author kongweiguang */
async function restoreRuntimeAfterReload(page, deadline) {
  await page.waitForFunction(
    () => typeof globalThis.__TAURI_INTERNALS__?.invoke === "function",
    undefined,
    {
      timeout: remaining(deadline),
    },
  );
  await page.evaluate(async () => {
    const { createRuntimeHostAdapter } = await import("/src/api/tauri/runtime.ts");
    const runtime = createRuntimeHostAdapter();
    let state = await runtime.state();
    if (state.status === "stopped") state = await runtime.start();
    if (!["ready", "busy"].includes(state.status))
      throw new Error("runtime did not restore to ready");
  });
  await page
    .locator('.ja-shell[data-app-ready="true"]')
    .waitFor({ state: "visible", timeout: remaining(deadline) });
  await page
    .getByRole("textbox", { name: "消息", exact: true })
    .waitFor({ state: "visible", timeout: remaining(deadline) });
  const focusSession =
    page.__forkFocusEmulationSession ?? (await page.context().newCDPSession(page));
  page.__forkFocusEmulationSession = focusSession;
  let focusEmulation = { enabled: false, error: null };
  try {
    await focusSession.send("Emulation.setFocusEmulationEnabled", { enabled: true });
    focusEmulation = { enabled: true, error: null };
  } catch (error) {
    focusEmulation = { enabled: false, error: String(error?.message ?? error).slice(0, 400) };
  }
  page.__forkFocusEmulation = focusEmulation;
}

/** 真实 WebView2 driver：经生产 Timeline 与 history adapter 覆盖精确冻结、长正文分页及原生回执故障恢复；触屏后复位视口并确认真实侧栏可观察新会话选择。 @author kongweiguang */
export async function runForkReplyWebView2({ page, workspaceRoot, evidenceDirectory, fixture }) {
  assert.ok(page, "WebView2 page is required");
  assert.ok(fixture, "the loopback Provider fixture is required");
  page.__forkEvidenceDirectory = evidenceDirectory;
  page.__forkFocusEmulation = { enabled: false, error: "not attempted" };
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + 15 * 60_000;
  const pageErrors = [];
  const consoleErrors = [];
  page.on("pageerror", (error) => pageErrors.push(String(error?.message ?? error).slice(0, 500)));
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text().slice(0, 900));
  });
  await page.context().addInitScript(installForkReplyInvokeProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: remaining(deadline) });
  await page
    .locator('.ja-shell[data-app-ready="true"]')
    .waitFor({ state: "visible", timeout: remaining(deadline) });
  await page
    .getByRole("textbox", { name: "消息", exact: true })
    .waitFor({ state: "visible", timeout: remaining(deadline) });
  const desktopViewportSize = await page.evaluate(() => ({
    width: window.innerWidth,
    height: window.innerHeight,
  }));
  assert.equal(
    await page.getByRole("dialog").count(),
    0,
    "isolated app must start without an overlay",
  );
  assert.equal(await page.getByRole("button", { name: "从最近一轮分叉", exact: true }).count(), 0);

  const created = await createFixtureThread(page, workspaceRoot, fixture.baseUrl);
  await page.reload({ waitUntil: "domcontentloaded", timeout: remaining(deadline) });
  await restoreRuntimeAfterReload(page, deadline);
  const hiddenReviewBefore = await reviewSnapshotCount(page);
  await selectThread(page, created.workspaceName, created.threadId, deadline);
  await page.waitForTimeout(PROVIDER_STABILIZE_MS);
  const hiddenReviewAfter = await reviewSnapshotCount(page);
  const hiddenReviewSnapshotDelta = hiddenReviewAfter - hiddenReviewBefore;
  assert.equal(hiddenReviewSnapshotDelta, 0, "hidden Review must not create a workspace snapshot");

  const sourceAnswers = [];
  for (let index = 0; index < forkReplyMarkers.sourcePrompts.length; index += 1) {
    const prompt = `请按中文说明本轮事实 ${forkReplyMarkers.sourcePrompts[index]}`;
    const response = await sendPrompt(
      page,
      prompt,
      forkReplyMarkers.sourceAnswers[index],
      deadline,
    );
    sourceAnswers.push({ itemId: response.itemId, article: response.article });
    try {
      await revealPublicWorkForReply(
        page,
        response.article,
        forkReplyMarkers.publicWork[index],
        deadline,
      );
    } catch (error) {
      const evidence = await capturePublicWorkFailureEvidence(
        page,
        created.threadId,
        forkReplyMarkers.publicWork[index],
        evidenceDirectory,
      );
      throw new Error(
        `public work marker ${forkReplyMarkers.publicWork[index]} was not visible; evidence=${evidence.path}; API items=${evidence.apiItems.length}; DOM matches=${evidence.dom.matches.length}; ${String(error?.message ?? error)}`,
      );
    }
  }
  const longAnswer = await sendPrompt(
    page,
    `请生成可分页核对的完整中文长答复 ${forkReplyMarkers.longAnswerPrompt}`,
    forkReplyMarkers.longAnswer,
    deadline,
  );
  assert.ok(
    Array.from(forkReplyLongAnswerText).length > 65_536,
    "long-answer fixture must exceed one item-content/read page",
  );
  assert.equal(
    await sourceAnswers[1].article.getAttribute("data-fork-source-item-id"),
    sourceAnswers[1].itemId,
  );
  const sourceA = await readThreadSnapshot(page, created.threadId);
  assertSnapshotOrigins(sourceA);
  assert.ok(
    sourceA.items.some(
      (item) => item.itemId === sourceAnswers[1].itemId && item.origin === "native",
    ),
  );
  assert.ok(
    sourceA.items.some((item) => JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[2])),
  );
  const sourceLongAnswer = sourceA.items.find((item) => item.itemId === longAnswer.itemId);
  assert.equal(sourceLongAnswer?.kind, "final_answer");
  assert.equal(sourceLongAnswer?.truncated, true);
  assert.equal(Array.from(sourceLongAnswer?.text ?? "").length, 512);
  assert.equal(sourceLongAnswer?.totalCharacters, Array.from(forkReplyLongAnswerText).length);
  const sourceReplyGeometry = await inspectReplyActionGeometry(page, sourceAnswers[1].itemId);
  assert.equal(sourceReplyGeometry.visible, true);
  assert.equal(sourceReplyGeometry.forkIndex, sourceReplyGeometry.copyIndex + 1);
  await sourceAnswers[1].article.hover();
  await page.waitForTimeout(180);
  await page.screenshot({
    path: join(evidenceDirectory, "fork-source-three-replies.png"),
    fullPage: false,
  });

  const sourceDraft = `来源草稿应保留 ${forkReplyMarkers.sourcePrompts[1]}`;
  const sourceComposer = page.getByRole("textbox", { name: "消息", exact: true });
  await sourceComposer.fill(sourceDraft);
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  await page.waitForFunction(() => document.documentElement.dataset.theme === "light", undefined, {
    timeout: remaining(deadline),
  });
  const lightColors = await page.evaluate(() => ({
    theme: document.documentElement.dataset.theme,
    background: getComputedStyle(document.documentElement)
      .getPropertyValue("--ja-background")
      .trim(),
  }));
  const sourceProviderCountBeforeFork = fixture
    .snapshot()
    .filter((entry) => entry.kind === "agent_turn").length;
  const branchB = await forkReply(
    page,
    sourceAnswers[1].itemId,
    created.threadId,
    fixture,
    deadline,
  );
  assert.equal(
    fixture.snapshot().filter((entry) => entry.kind === "agent_turn").length,
    sourceProviderCountBeforeFork,
  );
  await selectThread(page, created.workspaceName, created.threadId, deadline);
  assert.equal(await sourceComposer.inputValue(), sourceDraft);
  await selectThread(page, created.workspaceName, branchB, deadline);
  const branchBHistory = await readThreadSnapshot(page, branchB);
  assertSnapshotOrigins(branchBHistory);
  assert.ok(
    branchBHistory.items.length > 0,
    "forked Thread must immediately expose inherited history",
  );
  assert.equal(
    branchBHistory.items.some(
      (item) =>
        item.kind === "final_answer" &&
        item.origin === "inherited" &&
        JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[1]),
    ),
    true,
  );
  const branchBSecondAnswer = findInheritedFinalAnswer(
    branchBHistory,
    forkReplyMarkers.sourceAnswers[1],
  );
  assert.notEqual(branchBSecondAnswer.itemId, sourceAnswers[1].itemId);
  assert.equal(
    branchBHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[0]),
    ),
    true,
  );
  assert.equal(
    branchBHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourcePrompts[0]),
    ),
    true,
  );
  assert.equal(
    branchBHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourcePrompts[1]),
    ),
    true,
  );
  assert.equal(
    branchBHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.publicWork[0]),
    ),
    true,
  );
  assert.equal(
    branchBHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[2]),
    ),
    false,
  );
  assert.equal(
    branchBHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourcePrompts[2]),
    ),
    false,
  );
  assert.equal(
    branchBHistory.items.some((item) => JSON.stringify(item).includes(forkReplyMarkers.longAnswer)),
    false,
  );
  assert.equal(
    branchBHistory.items.filter((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[1]),
    ).length,
    1,
  );
  assert.equal(
    branchBHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.publicWork[1]),
    ),
    true,
  );
  const hiddenThreadReadStart = await page.evaluate(
    () => globalThis.__JA_FORK_REPLY_INVOKES__?.length ?? 0,
  );
  const idleProviderCount = fixture
    .snapshot()
    .filter((entry) => entry.kind === "agent_turn").length;
  const hiddenIdleReadWindowMs = 1_250;
  await page.waitForTimeout(hiddenIdleReadWindowMs);
  const hiddenIdleReads = await page.evaluate(
    ({ start, selectedThreadId }) =>
      (globalThis.__JA_FORK_REPLY_INVOKES__ ?? [])
        .slice(start)
        .filter(
          (entry) =>
            ["ja_thread_read", "ja_thread_item_content_read"].includes(entry.command) &&
            entry.threadId !== selectedThreadId,
        )
        .map(({ command, threadId }) => ({ command, threadId })),
    { start: hiddenThreadReadStart, selectedThreadId: branchB },
  );
  const idleProviderCountAfter = fixture
    .snapshot()
    .filter((entry) => entry.kind === "agent_turn").length;
  assert.deepEqual(
    hiddenIdleReads,
    [],
    "an idle hidden source Thread must not trigger background history or long-body reads",
  );
  assert.equal(idleProviderCountAfter, idleProviderCount);
  await page.screenshot({
    path: join(evidenceDirectory, "fork-branch-b-inherited.png"),
    fullPage: false,
  });

  const branchBFirstAnswer = findInheritedFinalAnswer(
    branchBHistory,
    forkReplyMarkers.sourceAnswers[0],
  );
  const branchA1Button = page.locator(
    `button[data-testid="fork-reply"][data-source-item-id="${branchBFirstAnswer.itemId}"]`,
  );
  await branchA1Button.waitFor({ state: "visible", timeout: remaining(deadline) });
  const branchC = await forkReply(page, branchBFirstAnswer.itemId, branchB, fixture, deadline);
  const branchCHistory = await readThreadSnapshot(page, branchC);
  assertSnapshotOrigins(branchCHistory);
  assert.ok(
    branchCHistory.items.some(
      (item) =>
        item.kind === "final_answer" &&
        item.origin === "inherited" &&
        JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[0]),
    ),
  );
  assert.notEqual(
    findInheritedFinalAnswer(branchCHistory, forkReplyMarkers.sourceAnswers[0]).itemId,
    branchBFirstAnswer.itemId,
  );
  assert.equal(
    branchCHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourcePrompts[0]),
    ),
    true,
  );
  assert.equal(
    branchCHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.publicWork[0]),
    ),
    true,
  );
  assert.equal(
    branchCHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[1]),
    ),
    false,
  );
  assert.equal(
    branchCHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourcePrompts[1]),
    ),
    false,
  );
  assert.equal(
    branchCHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[2]),
    ),
    false,
  );
  assert.equal(
    branchCHistory.items.some((item) => JSON.stringify(item).includes(forkReplyMarkers.longAnswer)),
    false,
  );

  await selectThread(page, created.workspaceName, created.threadId, deadline);
  const longBranch = await forkReply(page, longAnswer.itemId, created.threadId, fixture, deadline);
  const longBranchHistory = await readThreadSnapshot(page, longBranch);
  assertSnapshotOrigins(longBranchHistory);
  const inheritedLongAnswer = findInheritedFinalAnswer(longBranchHistory);
  assert.notEqual(inheritedLongAnswer.itemId, longAnswer.itemId);
  assert.equal(inheritedLongAnswer?.origin, "inherited");
  assert.equal(inheritedLongAnswer?.truncated, true);
  const completeLongAnswer = await readCompleteItemContent(
    page,
    longBranch,
    inheritedLongAnswer.itemId,
  );
  assert.equal(completeLongAnswer.content, forkReplyLongAnswerText);
  assert.equal(completeLongAnswer.totalCharacters, Array.from(forkReplyLongAnswerText).length);
  assert.equal(completeLongAnswer.contentBytes, Buffer.byteLength(forkReplyLongAnswerText, "utf8"));
  assert.ok(completeLongAnswer.pages.length > 1);
  assert.equal(completeLongAnswer.pages.length, 2);
  assert.equal(
    completeLongAnswer.pages[0].nextOffsetCharacters,
    completeLongAnswer.pages[1].offsetCharacters,
  );
  await selectThread(page, created.workspaceName, longBranch, deadline);
  const longBranchForkButton = page.locator(
    `button[data-testid="fork-reply"][data-source-item-id="${inheritedLongAnswer.itemId}"]`,
  );
  await longBranchForkButton.waitFor({ state: "visible", timeout: remaining(deadline) });
  assert.equal(
    await longBranchForkButton.getAttribute("data-source-item-id"),
    inheritedLongAnswer.itemId,
  );
  await sendPrompt(
    page,
    `请基于继承的超长答复继续 ${forkReplyMarkers.longBranchPrompt}`,
    forkReplyMarkers.longBranchAnswer,
    deadline,
  );
  const longBranchProviderRequest = fixture
    .snapshot()
    .findLast((entry) => entry.prompt === forkReplyMarkers.longBranchPrompt);
  assert.deepEqual(longBranchProviderRequest?.longAnswerPromptOccurrences, 1);
  assert.deepEqual(longBranchProviderRequest?.longAnswerOccurrences, 1);
  const nestedLongBranch = await forkReply(
    page,
    inheritedLongAnswer.itemId,
    longBranch,
    fixture,
    deadline,
  );
  const nestedLongHistory = await readThreadSnapshot(page, nestedLongBranch);
  assertSnapshotOrigins(nestedLongHistory);
  const nestedInheritedLongAnswer = findInheritedFinalAnswer(nestedLongHistory);
  assert.notEqual(nestedInheritedLongAnswer.itemId, inheritedLongAnswer.itemId);
  assert.equal(
    nestedLongHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.longBranchPrompt),
    ),
    false,
    "reforking the inherited long reply must stop before the child-native continuation",
  );
  assert.equal(
    nestedLongHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.longBranchAnswer),
    ),
    false,
  );
  const nestedLongBody = await readCompleteItemContent(
    page,
    nestedLongBranch,
    nestedInheritedLongAnswer.itemId,
  );
  assert.equal(nestedLongBody.content, forkReplyLongAnswerText);
  assert.equal(nestedLongBody.contentBytes, Buffer.byteLength(forkReplyLongAnswerText, "utf8"));
  assert.equal(nestedLongBody.pages.length, 2);
  const longBodyReadEvidence = await page.evaluate(
    ({ firstThreadId, secondThreadId, expectedItemIds }) =>
      (globalThis.__JA_FORK_REPLY_INVOKES__ ?? []).filter(
        (entry) =>
          entry.command === "ja_thread_item_content_read" &&
          expectedItemIds.includes(entry.itemId) &&
          [firstThreadId, secondThreadId].includes(entry.threadId),
      ),
    {
      firstThreadId: longBranch,
      secondThreadId: nestedLongBranch,
      expectedItemIds: [inheritedLongAnswer.itemId, nestedInheritedLongAnswer.itemId],
    },
  );
  assert.deepEqual(
    longBodyReadEvidence.map(({ threadId, offsetCharacters }) => ({
      threadId,
      offsetCharacters,
    })),
    [
      { threadId: longBranch, offsetCharacters: 0 },
      { threadId: longBranch, offsetCharacters: 65_536 },
      { threadId: nestedLongBranch, offsetCharacters: 0 },
      { threadId: nestedLongBranch, offsetCharacters: 65_536 },
    ],
    "both inherited copies must be read via the real paginated item-content command",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "fork-long-body-page-and-refork.png"),
    fullPage: false,
  });

  await selectThread(page, created.workspaceName, branchB, deadline);
  const branchBReply = await sendPrompt(
    page,
    `继续第二轮之后的分支讨论 ${forkReplyMarkers.branchBPrompt}`,
    forkReplyMarkers.branchBAnswer,
    deadline,
  );
  await selectThread(page, created.workspaceName, branchC, deadline);
  const branchCReply = await sendPrompt(
    page,
    `从第一轮继续讨论 ${forkReplyMarkers.branchCPrompt}`,
    forkReplyMarkers.branchCAnswer,
    deadline,
  );
  const branchBRequest = fixture
    .snapshot()
    .findLast((entry) => entry.prompt === forkReplyMarkers.branchBPrompt);
  const branchCRequest = fixture
    .snapshot()
    .findLast((entry) => entry.prompt === forkReplyMarkers.branchCPrompt);
  assert.deepEqual(branchBRequest?.sourceAnswerOccurrences, [1, 1, 0]);
  assert.deepEqual(branchBRequest?.sourcePromptOccurrences, [1, 1, 0]);
  assert.deepEqual(branchCRequest?.sourceAnswerOccurrences, [1, 0, 0]);
  assert.deepEqual(branchCRequest?.sourcePromptOccurrences, [1, 0, 0]);
  assert.equal(
    fixture.snapshot().filter((entry) => entry.prompt === forkReplyMarkers.branchBPrompt).length,
    1,
  );
  assert.equal(
    fixture.snapshot().filter((entry) => entry.prompt === forkReplyMarkers.branchCPrompt).length,
    1,
  );

  await selectThread(page, created.workspaceName, branchB, deadline);
  const activeComposer = page.getByRole("textbox", { name: "消息", exact: true });
  await activeComposer.fill(`在已有分支继续工作 ${forkReplyMarkers.activePrompt}`);
  await page
    .getByRole("button", { name: "发送", exact: true })
    .click({ timeout: remaining(deadline) });
  await fixture.waitForActivePartial(remaining(deadline));
  const activeRow = page
    .locator(".ja-chat-timeline__row")
    .filter({ hasText: forkReplyMarkers.activePrompt })
    .last();
  await activeRow.waitFor({ state: "visible", timeout: remaining(deadline) });
  const activeTurnId = await activeRow.getAttribute("data-turn-id");
  assert.ok(activeTurnId, "the active user input must identify the in-progress Turn row");
  const activeWorkTrigger = activeRow.locator(".ja-work-process__trigger");
  if ((await activeWorkTrigger.count()) > 0) {
    const expanded = (await activeWorkTrigger.getAttribute("aria-expanded")) === "true";
    if (!expanded) await activeWorkTrigger.click({ timeout: remaining(deadline) });
  }
  const partial = activeRow.locator("section.ja-work-process .ja-work-process__content");
  await partial.getByText(forkReplyMarkers.activePartial, { exact: false }).waitFor({
    state: "visible",
    timeout: remaining(deadline),
  });
  assert.equal(await partial.locator('button[data-testid="fork-reply"]').count(), 0);
  assert.equal(
    await activeRow.locator('article button[data-testid="fork-reply"]').count(),
    0,
    "the active Turn's article placeholder must not offer a fork before a final answer is committed",
  );
  assert.equal(
    await page
      .locator("[data-fork-source-item-id]")
      .filter({ hasText: forkReplyMarkers.activePartial })
      .count(),
    0,
    "an uncommitted streaming response must not expose a persisted fork anchor",
  );
  const sourceAtFork = await readThreadSnapshot(page, branchB);
  const selectedSourceItem = sourceAtFork.items.find((item) => item.itemId === branchBReply.itemId);
  assert.equal(selectedSourceItem?.kind, "final_answer");
  assert.equal(selectedSourceItem?.origin, "native");
  const selectedSourceTurn = sourceAtFork.turns.find(
    (turn) => turn.turnId === selectedSourceItem.turnId,
  );
  assert.equal(selectedSourceTurn?.status, "completed");
  assert.ok(selectedSourceTurn?.completedAt, "selected source reply must have a settled boundary");
  const activeSourceTurn = sourceAtFork.turns.find((turn) => turn.turnId === activeTurnId);
  assert.equal(activeSourceTurn?.origin, "native");
  assert.ok(
    activeSourceTurn !== undefined &&
      !["completed", "failed", "cancelled"].includes(activeSourceTurn.status),
    "the source must still have a genuinely active Turn when the earlier reply is forked",
  );
  const branchD = await forkReply(page, branchBReply.itemId, branchB, fixture, deadline);
  const branchDHistory = await readThreadSnapshot(page, branchD);
  assertForkChildTurnBoundary(branchDHistory, selectedSourceTurn.completedAt);
  assert.ok(
    branchDHistory.items.some(
      (item) =>
        item.kind === "final_answer" &&
        item.origin === "inherited" &&
        JSON.stringify(item).includes(forkReplyMarkers.branchBAnswer),
    ),
  );
  assert.notEqual(
    findInheritedFinalAnswer(branchDHistory, forkReplyMarkers.branchBAnswer).itemId,
    branchBReply.itemId,
  );
  assert.equal(
    branchDHistory.items.some((item) =>
      JSON.stringify(item).includes(forkReplyMarkers.activePartial),
    ),
    false,
  );
  const branchDRow = page.locator(
    `[aria-label="最近对话列表"] button[data-thread-id="${branchD}"]`,
  );
  assert.equal(
    await branchDRow.locator('.ja-navigation-thread-state[aria-label="正在工作"]').count(),
    0,
    "the forked child cannot show its source Turn as working in navigation",
  );
  assert.equal(
    await page.locator(".ja-chat-response__status").filter({ hasText: "正在工作" }).count(),
    0,
    "the forked child timeline cannot show the source Turn as working",
  );
  fixture.releaseActive();
  await selectThread(page, created.workspaceName, branchB, deadline);
  const activeReply = await readReplyAnchor(page, forkReplyMarkers.activeAnswer, deadline);
  assert.equal(
    await activeReply.article.evaluate((article) =>
      article.closest(".ja-chat-timeline__row")?.getAttribute("data-turn-id"),
    ),
    activeTurnId,
    "the settled assistant answer must replace the active placeholder in the same Turn row",
  );
  assert.equal(
    await activeReply.article.locator('button[data-testid="fork-reply"]').count(),
    1,
    "the committed answer in the active Turn must expose its settled reply fork action",
  );

  await page.reload({ waitUntil: "domcontentloaded", timeout: remaining(deadline) });
  await restoreRuntimeAfterReload(page, deadline);
  await selectThread(page, created.workspaceName, branchB, deadline);
  const reloadedBReply = await readReplyAnchor(page, forkReplyMarkers.branchBAnswer, deadline);
  const reloadedB = await readThreadSnapshot(page, branchB);
  assertSnapshotOrigins(reloadedB);
  assert.ok(
    reloadedB.items.some(
      (item) =>
        item.kind === "final_answer" &&
        item.origin === "inherited" &&
        JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[1]),
    ),
  );
  assert.ok(
    reloadedB.items.some(
      (item) =>
        JSON.stringify(item).includes(forkReplyMarkers.branchBAnswer) && item.origin === "native",
    ),
  );

  await page.setViewportSize({ width: 1280, height: 800 });
  await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
  await reloadedBReply.article.hover();
  await page.waitForTimeout(180);
  const lightGeometry = await inspectReplyActionGeometry(page, branchBReply.itemId);
  await page.screenshot({
    path: join(evidenceDirectory, "fork-reply-light-wide.png"),
    fullPage: false,
  });
  await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
  await page.waitForFunction(() => document.documentElement.dataset.theme === "dark", undefined, {
    timeout: remaining(deadline),
  });
  await reloadedBReply.article.hover();
  await page.waitForTimeout(180);
  const darkColors = await page.evaluate(() => ({
    theme: document.documentElement.dataset.theme,
    background: getComputedStyle(document.documentElement)
      .getPropertyValue("--ja-background")
      .trim(),
  }));
  await page.screenshot({
    path: join(evidenceDirectory, "fork-reply-dark-wide.png"),
    fullPage: false,
  });
  assert.notEqual(
    lightColors.background,
    darkColors.background,
    "light and dark theme tokens must differ",
  );
  await page.setViewportSize({ width: 390, height: 760 });
  await reloadedBReply.article.hover();
  await page.waitForTimeout(180);
  const narrowGeometry = await inspectReplyActionGeometry(page, branchBReply.itemId);
  assert.equal(narrowGeometry.visible, true);
  assert.ok(
    narrowGeometry.groupLeft >= 0 && narrowGeometry.groupRight <= narrowGeometry.viewportWidth,
  );
  await page.screenshot({
    path: join(evidenceDirectory, "fork-reply-dark-narrow.png"),
    fullPage: false,
  });

  // Keep keyboard traversal scoped to this persisted reply; other visible answers may be later in the virtualized timeline.
  const copyButton = reloadedBReply.article.getByRole("button", {
    name: "复制回复",
    exact: true,
  });
  const forkButton = page.locator(
    `button[data-testid="fork-reply"][data-source-item-id="${branchBReply.itemId}"]`,
  );
  await copyButton.focus();
  await page.keyboard.press("Tab");
  await page.waitForFunction(
    (expected) => document.activeElement?.getAttribute("data-source-item-id") === expected,
    branchBReply.itemId,
    { timeout: remaining(deadline) },
  );
  const keyboardGeometry = await inspectReplyActionGeometry(page, branchBReply.itemId);
  assert.equal(keyboardGeometry.focusVisible, true);
  await page.screenshot({
    path: join(evidenceDirectory, "fork-reply-keyboard-focus.png"),
    fullPage: false,
  });

  // Touch emulation keeps coarse-pointer styles, while restoring desktop geometry keeps the real fork result observable in navigation.
  await page.setViewportSize(desktopViewportSize);
  await ensureNavigationSidebarVisible(page, created.workspaceName, branchB, deadline);

  let touchBranchInheritedItemCount = null;
  const cdp = await page.context().newCDPSession(page);
  try {
    await cdp.send("Emulation.setEmulatedMedia", {
      features: [
        { name: "hover", value: "none" },
        { name: "pointer", value: "coarse" },
        { name: "prefers-color-scheme", value: "dark" },
      ],
    });
    await cdp.send("Emulation.setTouchEmulationEnabled", {
      enabled: true,
      maxTouchPoints: 1,
      configuration: "mobile",
    });
    const touchFacts = await forkButton.evaluate((button) => {
      const rect = button.getBoundingClientRect();
      return {
        visible:
          button.getClientRects().length > 0 && getComputedStyle(button).visibility !== "hidden",
        hoverNone: matchMedia("(hover: none)").matches,
        coarsePointer: matchMedia("(pointer: coarse)").matches,
        label: button.getAttribute("aria-label"),
        state: button.getAttribute("data-fork-state"),
        disabled: button.disabled,
        hitTargetIsButton: (() => {
          const target = document.elementFromPoint(
            rect.left + rect.width / 2,
            rect.top + rect.height / 2,
          );
          return target === button || button.contains(target);
        })(),
        x: rect.left + rect.width / 2,
        y: rect.top + rect.height / 2,
      };
    });
    assert.deepEqual(touchFacts, {
      visible: true,
      hoverNone: true,
      coarsePointer: true,
      label: "从此回复新建会话",
      state: "ready",
      disabled: false,
      hitTargetIsButton: true,
      x: touchFacts.x,
      y: touchFacts.y,
    });
    assert.ok(Number.isFinite(touchFacts.x) && Number.isFinite(touchFacts.y));
    await page.screenshot({
      path: join(evidenceDirectory, "fork-reply-touch-visible.png"),
      fullPage: false,
    });
    const providerCountBeforeTouch = fixture.snapshot().length;
    const touchBranch = await forkReply(
      page,
      branchBReply.itemId,
      branchB,
      fixture,
      deadline,
      async () => {
        await cdp.send("Input.dispatchTouchEvent", {
          type: "touchStart",
          touchPoints: [
            { x: touchFacts.x, y: touchFacts.y, id: 1, radiusX: 1, radiusY: 1, force: 1 },
          ],
        });
        await page.waitForTimeout(50);
        await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
        await page.waitForFunction(
          (expectedItemId) =>
            (globalThis.__JA_FORK_REPLY_INPUT_EVENTS__ ?? []).some(
              (event) =>
                event.type === "click" && event.isTrusted && event.sourceItemId === expectedItemId,
            ),
          branchBReply.itemId,
          { timeout: remaining(deadline) },
        );
        const inputEvents = (await readForkBridgeEvidence(page)).inputEvents.filter(
          (event) => event.sourceItemId === branchBReply.itemId,
        );
        assert.ok(
          inputEvents.some((event) => event.type === "touchstart"),
          `touchstart must reach the visible fork button: ${JSON.stringify(inputEvents)}`,
        );
        assert.ok(
          inputEvents.some((event) => event.type === "touchend"),
          `touchend must reach the visible fork button: ${JSON.stringify(inputEvents)}`,
        );
        assert.ok(
          inputEvents.some((event) => event.type === "click" && event.isTrusted),
          `a real touch tap must produce a trusted click: ${JSON.stringify(inputEvents)}`,
        );
      },
    );
    assert.equal(fixture.snapshot().length, providerCountBeforeTouch);
    const touchHistory = await readThreadSnapshot(page, touchBranch);
    assertSnapshotOrigins(touchHistory);
    touchBranchInheritedItemCount = touchHistory.items.filter(
      (item) => item.origin === "inherited",
    ).length;
    assert.ok(
      touchHistory.items.some(
        (item) =>
          item.kind === "final_answer" &&
          item.origin === "inherited" &&
          JSON.stringify(item).includes(forkReplyMarkers.branchBAnswer),
      ),
    );
    await cdp.send("Emulation.setTouchEmulationEnabled", { enabled: false }).catch(() => undefined);
  } finally {
    await cdp.send("Emulation.setTouchEmulationEnabled", { enabled: false }).catch(() => undefined);
    await cdp.send("Emulation.setEmulatedMedia", { features: [] }).catch(() => undefined);
    await cdp.detach().catch(() => undefined);
  }
  await page.setViewportSize(desktopViewportSize);

  const sourceAfterBranches = await readThreadSnapshot(page, created.threadId);
  assertSnapshotOrigins(sourceAfterBranches);
  assert.ok(
    sourceAfterBranches.items.some(
      (item) => item.itemId === sourceAnswers[2].itemId && item.origin === "native",
    ),
  );

  const openFailure = await runOpenFailureScenario(
    page,
    created.workspaceName,
    created.workspaceId,
    created.threadId,
    sourceAnswers[2].itemId,
    fixture,
    deadline,
  );
  const delayedReceipt = await runDelayedReceiptScenario(
    page,
    created.workspaceName,
    created.workspaceId,
    branchC,
    created.threadId,
    branchCReply.itemId,
    fixture,
    deadline,
  );
  const lostReceiptRecovery = await runLostReceiptRecoveryScenario(
    page,
    created.workspaceName,
    created.workspaceId,
    branchB,
    activeReply.itemId,
    fixture,
    deadline,
  );
  assert.equal(await reviewSnapshotCount(page), hiddenReviewAfter);
  assert.deepEqual(pageErrors, [], `WebView2 page errors: ${pageErrors.join(" | ")}`);
  assert.deepEqual(consoleErrors, [], `WebView2 console errors: ${consoleErrors.join(" | ")}`);

  const providerRequests = fixture.snapshot();
  const sourceInputChecks = providerRequests.filter((entry) =>
    entry.prompt?.startsWith("JA_FORK_SOURCE_PROMPT_"),
  );
  assert.equal(sourceInputChecks.length, 3);
  assert.equal(
    providerRequests.some((entry) => entry.bytes > 8 * 1024 * 1024),
    false,
  );
  return {
    schemaVersion: 1,
    status: "passed",
    runtime: {
      platform: process.platform,
      surface: "tauri_webview2",
      boundary: "jvm_jar",
      externalProviderCalls: 0,
    },
    source: {
      replies: sourceAnswers.map((answer, index) => ({
        ordinal: index + 1,
        persistedAnchorObserved: true,
      })),
      sourceRetainedAfterForks: sourceAfterBranches.items.some(
        (item) => item.itemId === sourceAnswers[2].itemId,
      ),
      thirdReplyExcludedFromB: !branchBHistory.items.some((item) =>
        JSON.stringify(item).includes(forkReplyMarkers.sourceAnswers[2]),
      ),
    },
    branches: {
      bInheritedItems: branchBHistory.items.filter((item) => item.origin === "inherited").length,
      cInheritedItems: branchCHistory.items.filter((item) => item.origin === "inherited").length,
      dForkedWhileSourceActive: true,
      childForkBoundaryChecked: true,
      bFirstProviderSourceAnswers: branchBRequest?.sourceAnswerOccurrences,
      cFirstProviderSourceAnswers: branchCRequest?.sourceAnswerOccurrences,
      bLongAnswerExcluded: branchBRequest?.longAnswerOccurrences === 0,
      cLongAnswerExcluded: branchCRequest?.longAnswerOccurrences === 0,
      longAnswerInheritedExactly: completeLongAnswer.content === forkReplyLongAnswerText,
      longAnswerProviderOccurrences: longBranchProviderRequest?.longAnswerOccurrences,
      nestedLongForkExcludesContinuation: !nestedLongHistory.items.some((item) =>
        JSON.stringify(item).includes(forkReplyMarkers.longBranchAnswer),
      ),
      longBodyPages: completeLongAnswer.pages.length,
      longBodyUtf8Bytes: completeLongAnswer.contentBytes,
      reloadPreservedBHistory: true,
    },
    interaction: {
      doubleClickCreatesOneFork: true,
      forkDoesNotCallProvider: true,
      sourceDraftRetained: true,
      composerFocusedAndEmptyAfterFork: true,
      streamingReplyHasNoForkAction: true,
      hiddenReviewSnapshotDelta,
      hiddenIdleReads: {
        observationWindowMs: hiddenIdleReadWindowMs,
        selectedThreadId: branchB,
        readsForHiddenThreads: hiddenIdleReads,
      },
      focusEmulation: page.__forkFocusEmulation,
      sourceActionGeometry: sourceReplyGeometry,
      lightGeometry,
      narrowGeometry,
      keyboardFocus: keyboardGeometry,
      touch: { emulated: true, visible: true },
      touchCreatesFork: true,
      touchBranchInheritedItems: touchBranchInheritedItemCount,
      openFailureReopensSameChild: openFailure,
      delayedReceiptPreservesSelection: delayedReceipt,
      lostReceiptReloadRecovery: lostReceiptRecovery,
      sourceComposerRestoredAfterOpenFailure: true,
      lightColors,
      darkColors,
    },
    provider: {
      requestCount: providerRequests.length,
      agentTurnCount: providerRequests.filter((entry) => entry.kind === "agent_turn").length,
      observations: providerRequests.map(
        ({ ordinal, kind, prompt, sourcePromptOccurrences, sourceAnswerOccurrences, bytes }) => ({
          ordinal,
          kind,
          prompt,
          sourcePromptOccurrences,
          sourceAnswerOccurrences,
          bytes,
        }),
      ),
    },
    nativeBridgeFaultInjection: {
      boundary:
        "E2E window native invoke probe; every injected command delegates to the real native call first",
      firstTargetReadFailure: openFailure,
      delayedForkReceiptAfterSelectionChange: delayedReceipt,
      droppedForkAndOwnerReceiptAcrossReload: lostReceiptRecovery,
    },
    pageErrors,
    consoleErrors,
    screenshots: [
      "fork-source-three-replies.png",
      "fork-branch-b-inherited.png",
      "fork-reply-light-wide.png",
      "fork-reply-dark-wide.png",
      "fork-reply-dark-narrow.png",
      "fork-reply-keyboard-focus.png",
      "fork-reply-touch-visible.png",
      "fork-long-body-page-and-refork.png",
      "fork-open-failure-retry.png",
      "fork-delayed-receipt-selection.png",
      "fork-lost-receipt-recovered.png",
    ],
  };
}

/** 固定 runner 参数与新鲜 JAR 输入；截图只落到系统临时目录中的本轮证据文件夹。 @author kongweiguang */
export function parseArguments(argv) {
  const options = {
    evidenceDirectory: undefined,
    jar: undefined,
    javaHome: DEFAULT_JAVA_HOME,
    cargoTargetDirectory: join(repoRoot, "target", "codex-fork-reply"),
  };
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) throw new Error(`missing value for ${name}`);
    if (name === "--evidence-directory") options.evidenceDirectory = resolve(value);
    else if (name === "--jar") options.jar = resolve(value);
    else if (name === "--java-home") options.javaHome = resolve(value);
    else if (name === "--cargo-target-directory") options.cargoTargetDirectory = resolve(value);
    else throw new Error(`unknown argument: ${name}`);
  }
  if (options.evidenceDirectory === undefined) {
    options.evidenceDirectory = join(
      tmpdir(),
      "ja-fork-reply-e2e",
      new Date().toISOString().replace(/[:.]/gu, "-"),
    );
  }
  options.evidenceDirectory = assertTemporaryEvidenceDirectory(options.evidenceDirectory);
  if (options.jar === undefined)
    throw new Error("--jar is required and must identify the JAR built for this acceptance run");
  return options;
}

/** 启动 loopback Provider 与隔离真窗 runner，并在任何失败路径释放来源活动 gate。 @author kongweiguang */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  await mkdir(options.evidenceDirectory, { recursive: true });
  const fixture = await startForkReplyProviderFixture();
  try {
    const report = await runProduction({
      ...options,
      providerBaseUrl: fixture.baseUrl,
      wallTimeoutMs: 900_000,
      scope: "git",
      fixture: "no-head",
      hiddenWindow: true,
      preserveFailedProfile: true,
      prewarmWebview: true,
      ignoredFiles: 0,
      untrackedFiles: 0,
      reportFileName: "fork-reply-report.json",
      driver: async (driverOptions) => {
        try {
          return await runForkReplyWebView2({
            ...driverOptions,
            fixture,
            jarPath: options.jar,
          });
        } catch (error) {
          await captureForkFailureEvidence({
            page: driverOptions.page,
            fixture,
            evidenceDirectory: options.evidenceDirectory,
            error,
          }).catch(() => undefined);
          await driverOptions.page
            .screenshot({
              path: join(options.evidenceDirectory, "fork-flow-failure.png"),
              fullPage: false,
            })
            .catch(() => undefined);
          throw error;
        }
      },
    });
    const jar = await readFile(options.jar);
    await stat(options.jar);
    await writeFile(
      join(options.evidenceDirectory, "fork-reply-artifact-identity.json"),
      `${JSON.stringify({ jarBytes: jar.byteLength, jarSha256: createHash("sha256").update(jar).digest("hex").toUpperCase() }, null, 2)}\n`,
      "utf8",
    );
    process.stdout.write(`JA_FORK_REPLY_E2E_OK evidence=${options.evidenceDirectory}\n`);
    return report;
  } finally {
    fixture.releaseActive();
    await fixture.close();
  }
}

if (
  process.argv[1] !== undefined &&
  pathToFileURL(resolve(process.argv[1])).href === import.meta.url
) {
  await main();
}
