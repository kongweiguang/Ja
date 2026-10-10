// @author kongweiguang
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { chromium } from "@playwright/test";
import { createServer } from "vite";

/** 用服务端状态确认 Turn 已受理，避免刷新时中断尚未提交的请求。 */
async function waitForAcceptedTurn(backend, turn) {
  const url = new URL("/api/chat/status", backend);
  const deadline = Date.now() + 15_000;
  let lastFailure;
  while (Date.now() < deadline) {
    let response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ threadId: turn.threadId, turnId: turn.turnId }),
        signal: AbortSignal.timeout(1_000),
      });
    } catch (failure) {
      lastFailure = failure;
      await new Promise((resolve) => setTimeout(resolve, 50));
      continue;
    }
    if (response.status === 404) {
      await new Promise((resolve) => setTimeout(resolve, 50));
      continue;
    }
    if (!response.ok) throw new Error(`Turn status returned HTTP ${response.status}.`);
    return await response.json();
  }
  const detail = lastFailure instanceof Error ? ` ${lastFailure.message}` : "";
  throw new Error(`The server did not admit Turn ${turn.turnId} before the deadline.${detail}`);
}

/** 超时后在宿主关闭前回读持久状态，区分 202 受理与恢复实际完成。 */
async function captureDurableTurnState(backend, turn, recordId) {
  /** 同时保留 HTTP 状态和响应体，避免空结果掩盖接口错误。 */
  async function readSnapshot(action, payload) {
    try {
      const response = await fetch(new URL(`/api/chat/${action}`, backend), {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(2_000),
      });
      const raw = await response.text();
      let body = raw;
      try {
        body = raw ? JSON.parse(raw) : null;
      } catch {
        /* 保留非 JSON 响应供诊断。 */
      }
      return { action, status: response.status, body };
    } catch (failure) {
      return { action, error: failure instanceof Error ? failure.message : String(failure) };
    }
  }

  const identity = { threadId: turn.threadId, turnId: turn.turnId };
  const probes = [
    readSnapshot("status", identity),
    readSnapshot("recoveries", identity),
    readSnapshot("events", { ...identity, afterSequence: 0, limit: 256 }),
    ...(recordId ? [readSnapshot("wait", { ...identity, recordId })] : []),
  ];
  return {
    capturedAt: new Date().toISOString(),
    identity,
    recordId: recordId ?? null,
    results: await Promise.all(probes),
  };
}

/** 等待指定 Turn 的持久终态，避免把恢复受理后的 SUSPENDED 瞬态报告为最终结果。 */
async function waitForTurnState(backend, turn, expectedState) {
  const deadline = Date.now() + 15_000;
  let latest;
  while (Date.now() < deadline) {
    const response = await fetch(new URL("/api/chat/status", backend), {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ threadId: turn.threadId, turnId: turn.turnId }),
      signal: AbortSignal.timeout(1_000),
    });
    if (!response.ok) throw new Error(`Turn status returned HTTP ${response.status}.`);
    latest = await response.json();
    if (latest.state === expectedState) return latest;
    if (["COMPLETED", "FAILED", "CANCELLED"].includes(latest.state)) {
      throw new Error(`Turn ${turn.turnId} reached unexpected terminal state ${latest.state}.`);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Turn ${turn.turnId} did not reach ${expectedState}: ${JSON.stringify(latest)}.`);
}

/**
 * 记录既有终态定位器的自然结果，不改变 Playwright 默认等待预算。
 * @author kongweiguang
 */
async function waitForCompletedStatus(page, completedWaits) {
  const startedAt = new Date().toISOString();
  const startedMonotonicAt = performance.now();
  const observation = {
    startedAt,
    settledAt: null,
    failedAt: null,
    durationMs: null,
    outcome: null,
  };
  completedWaits.push(observation);

  try {
    await page.locator('.status-line[data-state="COMPLETED"]').waitFor({ state: "attached" });
    observation.outcome = "resolved";
  } catch (failure) {
    observation.outcome = "rejected";
    observation.failedAt = new Date().toISOString();
    throw failure;
  } finally {
    observation.settledAt = new Date().toISOString();
    observation.durationMs = Math.round((performance.now() - startedMonotonicAt) * 100) / 100;
  }
}

/**
 * 先记录原有终态等待，再核对答复只呈现一次；观测不得改变既有时限。
 * @author kongweiguang
 */
async function expectCompletedAnswer(page, text, completedWaits) {
  await waitForCompletedStatus(page, completedWaits);
  const answer = page.getByText(text, { exact: true });
  await answer.first().waitFor({ state: "visible" });
  if ((await answer.count()) !== 1)
    throw new Error(`The completed answer rendered more than once: ${text}`);
}

/** 立即收敛响应等待的拒绝，避免后续点击等待期间 Node 将其作为未处理 Promise 退出。 */
function observeResponseWait(page, predicate, label, responseWaits) {
  const trace = { label, startedAt: new Date().toISOString(), outcome: "pending" };
  responseWaits.push(trace);
  const settled = page.waitForResponse(predicate).then(
    (response) => {
      trace.outcome = "response";
      trace.settledAt = new Date().toISOString();
      trace.status = response.status();
      trace.url = response.url();
      let payload;
      try {
        payload = response.request().postDataJSON();
      } catch {
        payload = undefined;
      }
      trace.request = { method: response.request().method(), payload };
      return { response };
    },
    (failure) => {
      trace.outcome = "rejected";
      trace.settledAt = new Date().toISOString();
      trace.error = failure instanceof Error ? failure.message : String(failure);
      if (failure instanceof Error && failure.stack) trace.stack = failure.stack;
      return { failure };
    },
  );
  return async function readResponse() {
    const result = await settled;
    if ("failure" in result) throw result.failure;
    return result.response;
  };
}

/** 仅当唯一目标取消注入及其精确 URL、Turn、操作键和 409 控制台诊断全部匹配时豁免。 */
function classifyConsoleErrors(consoleErrors, injection, apiResponses) {
  const rejection = injection?.injectedResponse;
  const matchingResponses =
    rejection?.status === 409 && injection.injectionCount === 1
      ? apiResponses.filter(
          (response) =>
            response.action === "cancel" &&
            response.status === rejection.status &&
            response.url === rejection.url &&
            response.method === rejection.method &&
            response.payload?.threadId === rejection.threadId &&
            response.payload?.turnId === rejection.turnId &&
            response.payload?.operationKey === rejection.operationKey,
        )
      : [];
  const expectedResponse = matchingResponses.length === 1 ? matchingResponses[0] : undefined;
  const expectedConsoleIndex = expectedResponse
    ? consoleErrors.findIndex(
        (error) =>
          error.url === expectedResponse.url &&
          /failed to load resource:.*\b409\b/i.test(error.text),
      )
    : -1;
  return {
    expectedHttpFailures: expectedResponse
      ? [
          {
            url: expectedResponse.url,
            method: expectedResponse.method,
            threadId: expectedResponse.payload.threadId,
            turnId: expectedResponse.payload.turnId,
            operationKey: expectedResponse.payload.operationKey,
            status: expectedResponse.status,
            injectionCount: injection.injectionCount,
            consoleError:
              expectedConsoleIndex < 0
                ? null
                : {
                    text: consoleErrors[expectedConsoleIndex].text,
                    url: consoleErrors[expectedConsoleIndex].url,
                  },
          },
        ]
      : [],
    unexpectedConsoleErrors: consoleErrors.filter((_, index) => index !== expectedConsoleIndex),
  };
}

/** 通知不得挡住 Header 或 Composer；停止只保留输入框动作位，关闭按钮仍须可命中。 */
async function assertCancelNoticeReachability(page) {
  const result = await page.evaluate(() => {
    const toast = globalThis.document.querySelector(".toast");
    const actions = globalThis.document.querySelector(".topbar-actions");
    const cancel = globalThis.document.querySelector('.composer-cancel[aria-label="取消执行"]');
    const close = toast?.querySelector('button[aria-label="关闭通知"]');
    const text = toast?.querySelector(":scope > span");
    if (!toast || !actions || !cancel || !close || !text) return null;

    const toastBounds = toast.getBoundingClientRect();
    const actionBounds = actions.getBoundingClientRect();
    const textBounds = text.getBoundingClientRect();
    const cancelBounds = cancel.getBoundingClientRect();
    const closeBounds = close.getBoundingClientRect();
    const hit = (bounds) =>
      globalThis.document.elementFromPoint(
        bounds.left + bounds.width / 2,
        bounds.top + bounds.height / 2,
      );
    const textHit = hit(textBounds);
    const cancelHit = hit(cancelBounds);
    const closeHit = hit(closeBounds);
    return {
      headerActionsUncovered:
        toastBounds.right <= actionBounds.left ||
        toastBounds.left >= actionBounds.right ||
        toastBounds.bottom <= actionBounds.top ||
        toastBounds.top >= actionBounds.bottom,
      noticeTextPassesThrough: textHit !== null && !toast.contains(textHit),
      cancelButtonReachable: cancelHit?.closest('button[aria-label="取消执行"]') === cancel,
      closeButtonReachable: closeHit?.closest('button[aria-label="关闭通知"]') === close,
    };
  });
  if (!result || Object.values(result).some((reachable) => !reachable)) {
    throw new Error(
      `The cancellation notice blocked an action or covered Header controls: ${JSON.stringify(result)}.`,
    );
  }
  return result;
}

/**
 * 用真实持久记录验证按需读取边界；等待异步行后只取 record-row 的直接标题，读取身份前显式展开技术详情。
 */
async function exerciseRecords(page, usageRequests, artifacts) {
  const requestsBeforeOpen = usageRequests.length;
  await page.getByRole("button", { name: "打开能力面板", exact: true }).click();
  const panel = page.locator(".workbench-panel");
  await panel.getByRole("tab", { name: "记录", exact: true }).click();
  const turnTitle = panel
    .locator(".record-row > summary")
    .filter({ hasText: "验证工具失败后继续" });
  await turnTitle.waitFor({ state: "visible" });
  const matchingTitles = await turnTitle.count();
  if (matchingTitles !== 1) {
    throw new Error(`Expected one matching Turn title, found ${matchingTitles}.`);
  }
  const turn = turnTitle.locator("xpath=..");
  if (usageRequests.length !== requestsBeforeOpen)
    throw new Error("Opening Records automatically fetched Usage.");
  await turnTitle.click();
  const usageSection = turn.locator(".record-usage");
  const heading = await usageSection.locator(".section-intro").innerText();
  const totalMatch = heading.match(/模型用量\s+0\s*\/\s*(\d+)/u);
  if (!totalMatch)
    throw new Error(`The persisted Usage record count was not displayed: ${heading}`);
  const expectedBatchSize = Math.min(8, Number(totalMatch[1]));
  if (expectedBatchSize < 1)
    throw new Error("The completed tool-correction Turn has no persisted Usage records.");
  await turn.locator(".record-technical > summary").click();
  const turnId = await turn.locator(".record-technical > p code").innerText();
  if (usageRequests.length !== requestsBeforeOpen)
    throw new Error("Expanding a Turn automatically fetched Usage.");
  await turn.getByRole("button", { name: "读取用量", exact: true }).click();
  // Playwright 在页面环境执行此判断，须明确引用 DOM 全局对象，避免 Node 静态检查误判作用域。
  await page.waitForFunction(
    ({ turnId, expectedBatchSize }) => {
      const row = [...globalThis.document.querySelectorAll(".record-row")].find(
        (candidate) =>
          candidate.querySelector(".record-detail > .record-technical > p code")?.textContent ===
          turnId,
      );
      return row?.querySelectorAll(".usage-row").length === expectedBatchSize;
    },
    { turnId, expectedBatchSize },
  );
  const batch = usageRequests.slice(requestsBeforeOpen);
  if (batch.length !== expectedBatchSize || batch.some((request) => request.turnId !== turnId)) {
    throw new Error(
      "The Usage batch did not match the explicitly opened Turn and bounded page size.",
    );
  }
  if (new Set(batch.map((request) => request.recordId)).size !== expectedBatchSize) {
    throw new Error("The Usage batch repeated a persisted record.");
  }
  await page.screenshot({ path: path.join(artifacts, "records.png") });
  await panel.getByRole("tab", { name: "工具", exact: true }).click();
  await panel.getByRole("tab", { name: "记录", exact: true }).click();
  await panel.locator(".record-row").first().waitFor({ state: "visible" });
  if (usageRequests.length !== requestsBeforeOpen + expectedBatchSize) {
    throw new Error("Changing workbench tabs automatically fetched another Usage batch.");
  }
  await panel.getByRole("button", { name: "关闭能力面板", exact: true }).click();
  await panel.waitFor({ state: "hidden" });
  if (usageRequests.length !== requestsBeforeOpen + expectedBatchSize) {
    throw new Error("Closing Records left an automatic Usage request active.");
  }
  return {
    turnId,
    persistedRecords: Number(totalMatch[1]),
    requestedRecords: expectedBatchSize,
    onDemand: true,
  };
}

/**
 * 在隔离 Chromium 中验证业务闭环并旁观既有事件；诊断不得增加请求或改变等待预算。
 * @author kongweiguang
 */
async function main() {
  const backend = process.env.JA_EXAMPLE_BACKEND_URL;
  if (!backend) throw new Error("JA_EXAMPLE_BACKEND_URL is required.");

  const root = process.cwd();
  const configFile = path.join(
    root,
    "ja-agent/example/ja-agent-example-springboot/frontend/vite.config.ts",
  );
  const artifacts = await mkdtemp(path.join(tmpdir(), "ja-spring-workbench-"));
  const viteCacheDirectory = path.join(artifacts, "vite-cache");
  const desktopViewport = { width: 1280, height: 820 };
  const mobileViewport = { width: 390, height: 844 };
  const vite = await createServer({
    configFile,
    cacheDir: viteCacheDirectory,
    server: { host: "127.0.0.1", port: 0, strictPort: false },
    clearScreen: false,
  });
  let browser;
  let page;
  const pageErrors = [];
  const consoleErrors = [];
  const abortTraces = [];
  const submissions = [];
  const reconnections = [];
  const cancelRequests = [];
  const cancelClicks = [];
  const responseWaits = [];
  const waitResponses = [];
  const apiResponses = [];
  const failedRequests = [];
  const completedWaits = [];
  const usageRequests = [];
  const responseBodyReads = [];
  const apiRequestTimings = new WeakMap();
  let durableStateAtFailure;
  let reconnectAdmission;
  let recordsEvidence;
  let clarificationDurableStatus;
  let conversationScrollCheck;
  let cancelFailureInjection;
  let cancelNoticeReachability;
  const cancelNoticeScreenshots = [];

  try {
    await vite.listen();
    const address = vite.httpServer?.address();
    if (!address || typeof address === "string") throw new Error("Vite did not bind an HTTP port.");
    browser = await chromium.launch({ headless: true });
    if (path.resolve(vite.config.cacheDir) !== path.resolve(viteCacheDirectory)) {
      throw new Error("The browser workbench did not receive its isolated Vite cache directory.");
    }
    page = await browser.newPage({ viewport: desktopViewport, colorScheme: "light" });
    page.setDefaultTimeout(15_000);
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("console", (message) => {
      if (message.text().startsWith("JA_ABORT_TRACE ")) abortTraces.push(message.text());
      if (message.type() === "error")
        consoleErrors.push({ text: message.text(), url: message.location().url });
    });
    await page.addInitScript(() => {
      const requestsBySignal = new WeakMap();
      const nativeFetch = globalThis.fetch;
      globalThis.fetch = function (input, init = {}) {
        if (init.signal) {
          const requests = requestsBySignal.get(init.signal) ?? [];
          requests.push(typeof input === "string" ? input : input.url);
          requestsBySignal.set(init.signal, requests);
        }
        return nativeFetch.call(this, input, init);
      };
      const NativeAbortController = globalThis.AbortController;
      globalThis.AbortController = class extends NativeAbortController {
        abort(reason) {
          console.debug(
            `JA_ABORT_TRACE ${JSON.stringify({ requests: requestsBySignal.get(this.signal) ?? [], stack: new Error().stack ?? "" })}`,
          );
          return super.abort(reason);
        }
      };
    });
    page.on("request", (request) => {
      const requestPath = new URL(request.url()).pathname;
      if (requestPath.startsWith("/api/chat/")) {
        apiRequestTimings.set(request, {
          startedAt: new Date().toISOString(),
          startedMonotonicAt: performance.now(),
        });
      }
      if (request.url().endsWith("/api/chat/stream")) submissions.push(request.postDataJSON());
      if (request.url().endsWith("/api/chat/reconnect")) reconnections.push(request.postDataJSON());
      if (request.url().endsWith("/api/chat/usage")) usageRequests.push(request.postDataJSON());
      if (new URL(request.url()).pathname.endsWith("/api/chat/cancel")) {
        let payload;
        try {
          payload = request.postDataJSON();
        } catch {
          payload = undefined;
        }
        cancelRequests.push({ at: new Date().toISOString(), payload });
      }
    });
    page.on("response", (response) => {
      const pathname = new URL(response.url()).pathname;
      if (!pathname.startsWith("/api/chat/")) return;
      const request = response.request();
      const timing = apiRequestTimings.get(request);
      const responseReceivedAt = new Date().toISOString();
      let payload;
      try {
        payload = request.postDataJSON();
      } catch {
        payload = undefined;
      }
      const entry = {
        action: pathname.slice("/api/chat/".length),
        url: response.url(),
        method: request.method(),
        payload,
        status: response.status(),
        requestStartedAt: timing?.startedAt ?? null,
        responseReceivedAt,
        durationMs: timing
          ? Math.round((performance.now() - timing.startedMonotonicAt) * 100) / 100
          : null,
      };
      apiResponses.push(entry);
      if (entry.action === "wait") waitResponses.push(entry);
      if (
        entry.action !== "stream" &&
        entry.action !== "reconnect" &&
        entry.action !== "events/stream"
      ) {
        responseBodyReads.push(
          response
            .text()
            .then((body) => {
              entry.body = body.slice(0, 4_000);
            })
            .catch((failure) => {
              entry.bodyReadError = failure instanceof Error ? failure.message : String(failure);
            }),
        );
      }
    });
    page.on("requestfailed", (request) => {
      const timing = apiRequestTimings.get(request);
      const failedAt = new Date().toISOString();
      let payload;
      try {
        payload = request.postDataJSON();
      } catch {
        payload = undefined;
      }
      failedRequests.push({
        url: request.url(),
        method: request.method(),
        payload,
        failure: request.failure()?.errorText,
        requestStartedAt: timing?.startedAt ?? null,
        failedAt,
        durationMs: timing
          ? Math.round((performance.now() - timing.startedMonotonicAt) * 100) / 100
          : null,
      });
    });

    await page.goto(`http://127.0.0.1:${address.port}`, { waitUntil: "networkidle" });
    if ((await page.title()) !== "Ja") throw new Error("The browser opened the wrong page.");
    if (!(await page.locator("#root").evaluate((root) => root.childElementCount > 0))) {
      throw new Error("The workbench root rendered no application content.");
    }
    const composer = page.getByRole("textbox", { name: "消息" });
    await composer.waitFor({ state: "visible" });

    await composer.fill("浏览器普通回复");
    const send = page.getByRole("button", { name: "发送" });
    await send.click();
    if (!(await send.isDisabled()))
      throw new Error("The composer remained enabled after clearing an accepted draft.");
    await send.click({ timeout: 300 }).catch(() => undefined);
    await expectCompletedAnswer(page, "浏览器首轮答复", completedWaits);
    if (submissions.length !== 1)
      throw new Error(`Repeated send created ${submissions.length} submissions.`);

    await page.reload({ waitUntil: "networkidle" });
    await page.getByText("浏览器首轮答复", { exact: true }).waitFor({ state: "visible" });
    await waitForCompletedStatus(page, completedWaits);

    await composer.fill("验证工具失败后继续");
    await page.getByRole("button", { name: "发送" }).click();
    await page.getByText("unknown_tool", { exact: true }).waitFor({ state: "visible" });
    await expectCompletedAnswer(page, "工具失败后已纠正", completedWaits);

    await composer.fill("验证审批拒绝");
    await page.getByRole("button", { name: "发送" }).click();
    const approval = page.locator(".wait-form");
    const approvalPrompt = approval.locator("legend").first();
    await approvalPrompt.getByText("允许执行 approval_probe？").waitFor({ state: "visible" });
    await approval.locator('input[type="radio"]').nth(1).check();
    await approval.getByRole("button", { name: "确认并继续" }).click();
    await expectCompletedAnswer(page, "用户拒绝，未执行", completedWaits);

    await composer.fill("验证澄清后继续");
    await page.getByRole("button", { name: "发送" }).click();
    const clarificationTurn = submissions.at(-1);
    if (!clarificationTurn) throw new Error("The clarification scenario was not submitted.");
    const clarification = page.locator(".wait-form");
    await clarification
      .locator("fieldset legend")
      .filter({ hasText: "选择风格" })
      .first()
      .waitFor({ state: "visible" });
    await clarification.locator('input[type="radio"]').first().check();
    await clarification.getByRole("button", { name: "确认并继续" }).click();
    await expectCompletedAnswer(page, "已按你的选择回答", completedWaits);
    clarificationDurableStatus = await waitForTurnState(backend, clarificationTurn, "COMPLETED");

    await composer.fill("验证断线重连");
    await page.getByRole("button", { name: "发送" }).click();
    const reconnectTurn = submissions.at(-1);
    if (!reconnectTurn) throw new Error("The reconnect scenario was not submitted.");
    await page.getByRole("button", { name: "取消执行" }).first().waitFor({ state: "visible" });
    reconnectAdmission = await waitForAcceptedTurn(backend, reconnectTurn);
    const readReconnectResponse = observeResponseWait(
      page,
      (response) => {
        const request = response.request();
        if (!new URL(response.url()).pathname.endsWith("/api/chat/reconnect")) return false;
        try {
          return request.postDataJSON()?.turnId === reconnectTurn.turnId;
        } catch {
          return false;
        }
      },
      `reconnect:${reconnectTurn.turnId}`,
      responseWaits,
    );
    await page.reload({ waitUntil: "domcontentloaded" });
    const reconnectResult = await readReconnectResponse();
    if (!reconnectResult.ok())
      throw new Error(`Reconnect returned HTTP ${reconnectResult.status()}.`);
    await page.getByRole("button", { name: "取消执行" }).first().waitFor({ state: "visible" });
    if (
      !reconnections.some(
        (request) =>
          request?.threadId === reconnectTurn.threadId && request?.turnId === reconnectTurn.turnId,
      )
    ) {
      throw new Error("Reload did not reconnect to the existing Thread and Turn.");
    }
    await expectCompletedAnswer(page, "迟到的回答", completedWaits);
    if (submissions.filter((request) => request?.turnId === reconnectTurn.turnId).length !== 1) {
      throw new Error("Reload submitted the active Turn more than once.");
    }

    await composer.fill("验证取消");
    await page.getByRole("button", { name: "发送" }).click();
    const cancelTurn = submissions.at(-1);
    if (!cancelTurn) throw new Error("The cancellation scenario was not submitted.");
    await waitForAcceptedTurn(backend, cancelTurn);
    await page.getByText("wait_seconds", { exact: true }).waitFor({ state: "visible" });
    const cancelActiveTurn = page.getByRole("button", { name: "取消执行" }).first();
    await cancelActiveTurn.waitFor({ state: "visible" });
    const statusReadsBeforeCancel = apiResponses.filter(
      (response) => response.action === "status" && response.payload?.turnId === cancelTurn.turnId,
    ).length;
    cancelFailureInjection = {
      threadId: cancelTurn.threadId,
      turnId: cancelTurn.turnId,
      injectionCount: 0,
      injectedResponse: null,
      forwardedRequests: [],
    };
    await page.route("**/api/chat/cancel", async (route) => {
      const request = route.request();
      let payload;
      try {
        payload = request.postDataJSON();
      } catch {
        payload = undefined;
      }
      const requestUrl = new URL(request.url());
      const isTargetCancel =
        request.method() === "POST" &&
        requestUrl.pathname === "/api/chat/cancel" &&
        payload?.threadId === cancelFailureInjection.threadId &&
        payload?.turnId === cancelFailureInjection.turnId;
      if (isTargetCancel && cancelFailureInjection.injectionCount === 0) {
        cancelFailureInjection.injectionCount += 1;
        cancelFailureInjection.injectedResponse = {
          at: new Date().toISOString(),
          url: request.url(),
          method: request.method(),
          threadId: payload.threadId,
          turnId: payload.turnId,
          operationKey: payload.operationKey,
          status: 409,
        };
        await route.fulfill({
          status: 409,
          contentType: "application/problem+json",
          body: JSON.stringify({
            type: "about:blank",
            title: "Conflict",
            status: 409,
            detail: "Test-injected transient cancellation conflict.",
          }),
        });
        return;
      }
      if (payload?.turnId === cancelFailureInjection.turnId) {
        cancelFailureInjection.forwardedRequests.push({
          at: new Date().toISOString(),
          operationKey: payload.operationKey,
        });
      }
      await route.continue();
    });
    const readCancelResponse = observeResponseWait(
      page,
      (response) => {
        const request = response.request();
        if (!new URL(response.url()).pathname.endsWith("/api/chat/cancel")) return false;
        try {
          return request.postDataJSON()?.turnId === cancelTurn.turnId;
        } catch {
          return false;
        }
      },
      `cancel:${cancelTurn.turnId}`,
      responseWaits,
    );
    cancelClicks.push({ stage: "initial", state: "click-started", at: new Date().toISOString() });
    await cancelActiveTurn.click();
    cancelClicks.push({ stage: "initial", state: "click-completed", at: new Date().toISOString() });
    const cancelResponse = await readCancelResponse();
    if (
      cancelResponse.status() !== 409 ||
      cancelFailureInjection.injectedResponse?.status !== 409 ||
      cancelFailureInjection.injectionCount !== 1
    ) {
      throw new Error(
        "The first cancellation was not rejected by the deterministic HTTP 409 test injection.",
      );
    }
    if (cancelResponse.status() === 409) {
      await page
        .getByText("取消请求未被接受，服务端显示执行仍在进行。再次点击可沿用同一操作身份重试。", {
          exact: true,
        })
        .waitFor({ state: "visible" });
      const desktopCancelNotice = path.join(artifacts, "cancel-conflict-desktop.png");
      await page.screenshot({ path: desktopCancelNotice });
      cancelNoticeScreenshots.push(desktopCancelNotice);
      cancelNoticeReachability = { desktop: await assertCancelNoticeReachability(page) };
      await page.setViewportSize(mobileViewport);
      cancelNoticeReachability.mobile = await assertCancelNoticeReachability(page);
      const mobileCancelNotice = path.join(artifacts, "cancel-conflict-mobile.png");
      await page.screenshot({ path: mobileCancelNotice });
      cancelNoticeScreenshots.push(mobileCancelNotice);
      const statusReads = apiResponses.filter(
        (response) =>
          response.action === "status" && response.payload?.turnId === cancelTurn.turnId,
      );
      if (statusReads.length <= statusReadsBeforeCancel) {
        throw new Error(
          "A conflicted cancellation was not reconciled against authoritative Turn status.",
        );
      }
      if (!(await cancelActiveTurn.isEnabled())) {
        throw new Error(
          "A conflicted cancel request left the active Turn without an available retry.",
        );
      }
      const operationKey = cancelResponse.request().postDataJSON()?.operationKey;
      const readRetryResponse = observeResponseWait(
        page,
        (response) => {
          const request = response.request();
          if (!new URL(response.url()).pathname.endsWith("/api/chat/cancel")) return false;
          try {
            const payload = request.postDataJSON();
            return payload?.turnId === cancelTurn.turnId && payload?.operationKey === operationKey;
          } catch {
            return false;
          }
        },
        `cancel-retry:${cancelTurn.turnId}:${operationKey ?? "missing-operation-key"}`,
        responseWaits,
      );
      cancelClicks.push({
        stage: "retry",
        state: "click-started",
        turnId: cancelTurn.turnId,
        operationKey,
        at: new Date().toISOString(),
      });
      await cancelActiveTurn.click();
      cancelClicks.push({
        stage: "retry",
        state: "click-completed",
        turnId: cancelTurn.turnId,
        operationKey,
        at: new Date().toISOString(),
      });
      const retryResponse = await readRetryResponse();
      if (retryResponse.status() !== 204) {
        throw new Error(
          `Retrying the same cancellation identity returned HTTP ${retryResponse.status()}.`,
        );
      }
      const turnCancelRequests = cancelRequests.filter(
        (request) => request.payload?.turnId === cancelTurn.turnId,
      );
      if (turnCancelRequests.length !== 2) {
        throw new Error(
          `Expected the injected conflict and one retry, observed ${turnCancelRequests.length} cancel requests.`,
        );
      }
      if (turnCancelRequests.some((request) => request.payload?.operationKey !== operationKey)) {
        throw new Error("The cancellation retry changed its operation identity.");
      }
      if (
        cancelFailureInjection.forwardedRequests.length !== 1 ||
        cancelFailureInjection.forwardedRequests[0]?.operationKey !== operationKey
      ) {
        throw new Error(
          "The retry was not the only cancellation request forwarded to the real Spring endpoint.",
        );
      }
      if (submissions.filter((request) => request?.turnId === cancelTurn.turnId).length !== 1) {
        throw new Error("Cancellation retry submitted the Turn more than once.");
      }
    }
    await page.getByRole("button", { name: "关闭通知", exact: true }).click();
    await page.locator(".toast").waitFor({ state: "detached" });
    cancelNoticeReachability.dismissedAfterRetry = true;
    await page.locator('.status-line[data-state="CANCELLED"]').waitFor({ state: "attached" });
    await page.setViewportSize(desktopViewport);

    recordsEvidence = await exerciseRecords(page, usageRequests, artifacts);

    const scrollViewport = { width: desktopViewport.width, height: 420 };
    await page.setViewportSize(scrollViewport);
    conversationScrollCheck = await page
      .locator(".conversation-scroll")
      .evaluate(async (element) => {
        const scroll = element;
        const nextFrame = () => new Promise((resolve) => globalThis.requestAnimationFrame(resolve));
        const maxScroll = scroll.scrollHeight - scroll.clientHeight;
        scroll.scrollTop = 0;
        await nextFrame();
        const top = scroll.scrollTop;
        scroll.scrollTop = scroll.scrollHeight;
        await nextFrame();
        const bottom = scroll.scrollTop;
        const composerRect = globalThis.document
          .querySelector(".composer-dock")
          ?.getBoundingClientRect();
        return {
          maxScroll,
          top,
          bottom,
          composerBottom: composerRect?.bottom ?? null,
          viewportHeight: globalThis.window.innerHeight,
        };
      });
    if (
      conversationScrollCheck.maxScroll <= 0 ||
      conversationScrollCheck.bottom <= conversationScrollCheck.top
    ) {
      throw new Error(
        "The multi-turn conversation did not scroll through real content at a constrained height.",
      );
    }
    if (
      conversationScrollCheck.composerBottom === null ||
      conversationScrollCheck.composerBottom > conversationScrollCheck.viewportHeight
    ) {
      throw new Error("The composer is not reachable while the multi-turn conversation scrolls.");
    }
    await page.screenshot({ path: path.join(artifacts, "conversation-scroll-bottom.png") });

    await page.setViewportSize(desktopViewport);
    await page.screenshot({ path: path.join(artifacts, "desktop.png") });

    await page.setViewportSize(mobileViewport);
    await page.getByRole("button", { name: "打开会话栏" }).click();
    const sidebar = page.locator(".sidebar");
    // 这些判断在 WebView 或浏览器环境执行，不得依赖未声明的 Node 全局变量。
    await page.waitForFunction(() =>
      globalThis.document.querySelector(".sidebar")?.classList.contains("sidebar-open"),
    );
    await page.locator(".sidebar.sidebar-open .icon-button[aria-label='关闭会话栏']").click();
    await page.waitForFunction(
      () => !globalThis.document.querySelector(".sidebar")?.classList.contains("sidebar-open"),
    );
    await page.waitForFunction(() => {
      const sidebar = globalThis.document.querySelector(".sidebar");
      return sidebar !== null && sidebar.getBoundingClientRect().right <= 0;
    });
    if ((await page.locator(".sidebar-scrim").count()) !== 0) {
      throw new Error("The closed mobile sidebar left its interaction scrim visible.");
    }
    const mobileComposer = page.locator(".composer-dock");
    await mobileComposer.getByRole("textbox", { name: "消息" }).waitFor({ state: "visible" });
    const composerBounds = await mobileComposer.boundingBox();
    if (
      composerBounds === null ||
      composerBounds.x < 0 ||
      composerBounds.x + composerBounds.width > mobileViewport.width ||
      composerBounds.y + composerBounds.height > mobileViewport.height
    ) {
      throw new Error("The mobile composer is outside the viewport.");
    }
    const composerCenter = {
      x: composerBounds.x + composerBounds.width / 2,
      y: composerBounds.y + composerBounds.height / 2,
    };
    const composerIsReachable = await page.evaluate(({ x, y }) => {
      const target = globalThis.document.elementFromPoint(x, y);
      return target instanceof globalThis.Element && target.closest(".composer-dock") !== null;
    }, composerCenter);
    if (!composerIsReachable) throw new Error("The mobile composer is covered by another surface.");
    const overflow = await page.evaluate(
      () => globalThis.document.documentElement.scrollWidth > globalThis.window.innerWidth,
    );
    if (overflow) throw new Error("The narrow viewport has horizontal overflow.");
    await page.screenshot({ path: path.join(artifacts, "mobile.png") });
    const consoleHealth = classifyConsoleErrors(
      consoleErrors,
      cancelFailureInjection,
      apiResponses,
    );
    if (pageErrors.length || consoleHealth.unexpectedConsoleErrors.length) {
      throw new Error(
        `Browser errors: ${[
          ...pageErrors,
          ...consoleHealth.unexpectedConsoleErrors.map((error) => `${error.text} (${error.url})`),
        ].join("; ")}`,
      );
    }

    const report = {
      browser: "Chromium",
      backend,
      artifactsDirectory: artifacts,
      viteCacheDirectory: vite.config.cacheDir,
      submittedTurns: submissions.map(({ threadId, turnId, operationKey }) => ({
        threadId,
        turnId,
        operationKey,
      })),
      reconnectedTurns: reconnections.map(({ threadId, turnId }) => ({ threadId, turnId })),
      flows: [
        "normal reply",
        "refresh history",
        "duplicate-submit guard",
        "tool failure correction",
        "approval refusal",
        "clarification and resume",
        "reload and reconnect",
        "cancel",
        "on-demand records and bounded Usage",
        "workbench tab switch and close",
      ],
      pageErrors,
      consoleErrors,
      viewportAssertions: {
        desktop: desktopViewport,
        conversationScroll: conversationScrollCheck,
        mobile: {
          ...mobileViewport,
          sidebarClosed: !(await sidebar.evaluate((element) =>
            element.classList.contains("sidebar-open"),
          )),
          composer: composerBounds,
          composerReachable: composerIsReachable,
          horizontalOverflow: overflow,
        },
      },
      reconnectAdmission,
      clarificationDurableStatus,
      recordsEvidence,
      cancelFailureInjection,
      expectedHttpFailures: consoleHealth.expectedHttpFailures,
      unexpectedErrors: { page: pageErrors, console: consoleHealth.unexpectedConsoleErrors },
      cancelNoticeReachability,
      cancelNoticeScreenshots,
      cancelRequests,
      cancelClicks,
      responseWaits,
      usageRequests,
      abortTraces,
      waitResponses,
      completedWaits,
      apiResponses,
      failedRequests,
      screenshots: [
        ...cancelNoticeScreenshots,
        path.join(artifacts, "conversation-scroll-bottom.png"),
        path.join(artifacts, "desktop.png"),
        path.join(artifacts, "mobile.png"),
        path.join(artifacts, "records.png"),
      ],
    };
    await writeFile(path.join(artifacts, "report.json"), JSON.stringify(report, null, 2), "utf8");
    process.stdout.write(`SPRING_BROWSER_OK ${path.join(artifacts, "report.json")}\n`);
  } catch (failure) {
    if (page)
      await page
        .screenshot({ path: path.join(artifacts, "failure.png"), fullPage: true })
        .catch(() => undefined);
    const visibleText = page
      ? await page
          .locator("body")
          .innerText()
          .catch(() => "")
      : "";
    const consoleHealth = classifyConsoleErrors(
      consoleErrors,
      cancelFailureInjection,
      apiResponses,
    );
    await Promise.allSettled(responseBodyReads);
    const latestTurn = submissions.at(-1);
    const latestWait =
      latestTurn &&
      [...waitResponses]
        .reverse()
        .find(
          (entry) =>
            entry.action === "wait" &&
            entry.status === 200 &&
            entry.payload?.threadId === latestTurn.threadId &&
            entry.payload?.turnId === latestTurn.turnId,
        );
    durableStateAtFailure = latestTurn
      ? await captureDurableTurnState(backend, latestTurn, latestWait?.payload?.recordId)
      : undefined;
    await writeFile(
      path.join(artifacts, "report.json"),
      JSON.stringify(
        {
          browser: "Chromium",
          backend,
          artifactsDirectory: artifacts,
          viteCacheDirectory: vite.config.cacheDir,
          error: failure instanceof Error ? failure.message : String(failure),
          submittedTurns: submissions,
          reconnectedTurns: reconnections,
          pageErrors,
          consoleErrors,
          reconnectAdmission,
          recordsEvidence,
          cancelFailureInjection,
          expectedHttpFailures: consoleHealth.expectedHttpFailures,
          unexpectedErrors: { page: pageErrors, console: consoleHealth.unexpectedConsoleErrors },
          cancelNoticeReachability,
          cancelNoticeScreenshots,
          cancelRequests,
          cancelClicks,
          responseWaits,
          usageRequests,
          abortTraces,
          waitResponses,
          completedWaits,
          apiResponses,
          failedRequests,
          visibleText,
          durableStateAtFailure,
        },
        null,
        2,
      ),
      "utf8",
    );
    process.stdout.write(`SPRING_BROWSER_FAIL ${path.join(artifacts, "report.json")}\n`);
    throw failure;
  } finally {
    await browser?.close();
    await vite.close();
  }
}

await main();
