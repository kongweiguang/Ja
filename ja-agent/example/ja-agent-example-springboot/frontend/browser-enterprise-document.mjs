// @author kongweiguang
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { chromium } from "@playwright/test";
import { createServer } from "vite";

/**
 * Read durable records without touching the live SSE; timing callbacks receive fixed names and status only.
 * @author kongweiguang
 */
async function readBackendDiagnostics(backend, submission, recordTiming) {
  if (!submission?.threadId || !submission?.turnId) return {};

  const identity = { threadId: submission.threadId, turnId: submission.turnId };
  const queries = [
    ["status", "/api/chat/status", identity],
    ["events", "/api/chat/events", { ...identity, afterSequence: 0, limit: 256 }],
    [
      "policyFacts",
      "/api/chat/experience/policy/facts",
      { ...identity, afterSequence: 0, limit: 256 },
    ],
    ["notes", "/api/chat/experience/notes", { threadId: submission.threadId }],
    [
      "threadHistory",
      "/api/chat/threads/history",
      {
        threadId: submission.threadId,
        afterSequence: 0,
        throughSequence: null,
        limit: 256,
      },
    ],
  ];
  const diagnostics = {};
  for (const [name, route, body] of queries) {
    const startedAt = performance.now();
    recordTiming("diagnostic_http", "started", { operation: name });
    try {
      const response = await fetch(new URL(route, backend), {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(5_000),
      });
      const text = await response.text();
      diagnostics[name] = {
        status: response.status,
        body: text.length <= 30_000 ? JSON.parse(text) : `${text.slice(0, 30_000)} [truncated]`,
      };
      recordTiming("diagnostic_http", "completed", {
        operation: name,
        status: response.status,
        durationMs: performance.now() - startedAt,
      });
    } catch (error) {
      diagnostics[name] = { error: error instanceof Error ? error.message : String(error) };
      recordTiming("diagnostic_http", "failed", {
        operation: name,
        durationMs: performance.now() - startedAt,
      });
    }
  }
  return diagnostics;
}

/**
 * Passively observe only the first visible approval form; do not inspect rendered text or consume SSE frames.
 * @author kongweiguang
 */
async function armApprovalFormObserver(page) {
  return page.evaluate(() => {
    const state = {
      armedAtMs: globalThis.performance.now(),
      submitClickedAtMs: null,
      firstVisibleAtMs: null,
      visibleCountAtFirst: null,
    };
    const isVisible = (element) => {
      const style = globalThis.getComputedStyle(element);
      const bounds = element.getBoundingClientRect();
      return (
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        style.visibility !== "collapse" &&
        bounds.width > 0 &&
        bounds.height > 0
      );
    };
    let observer;
    const sample = () => {
      if (state.firstVisibleAtMs !== null) return;
      const visibleForms = [...globalThis.document.querySelectorAll(".wait-form")].filter(
        isVisible,
      );
      if (visibleForms.length === 0) return;
      state.firstVisibleAtMs = globalThis.performance.now();
      state.visibleCountAtFirst = visibleForms.length;
      observer.disconnect();
    };
    observer = new globalThis.MutationObserver(sample);
    globalThis.__jaEnterpriseWaitFormTiming = state;
    observer.observe(globalThis.document.documentElement, {
      attributes: true,
      attributeFilter: ["class", "style", "hidden", "aria-hidden"],
      childList: true,
      subtree: true,
    });
    sample();
    return state.armedAtMs;
  });
}

/**
 * Return only monotonic approval-form timing; omit DOM text, attributes, identity, and storage.
 * @author kongweiguang
 */
async function readApprovalFormTiming(page) {
  return page.evaluate(() => {
    const state = globalThis.__jaEnterpriseWaitFormTiming;
    if (!state) return { status: "unknown", firstVisibleAfterSubmitMs: null };
    const visibleAfterSubmit =
      Number.isFinite(state.firstVisibleAtMs) &&
      Number.isFinite(state.submitClickedAtMs) &&
      state.firstVisibleAtMs >= state.submitClickedAtMs;
    const firstVisibleAfterSubmitMs = visibleAfterSubmit
      ? state.firstVisibleAtMs - state.submitClickedAtMs
      : null;
    return {
      status:
        state.firstVisibleAtMs === null
          ? "not_observed"
          : visibleAfterSubmit
            ? "observed"
            : "visible_before_submit",
      observerArmedAtMs: Math.round(state.armedAtMs * 10) / 10,
      submitClickedAtMs: Number.isFinite(state.submitClickedAtMs)
        ? Math.round(state.submitClickedAtMs * 10) / 10
        : null,
      firstVisibleAtMs: Number.isFinite(state.firstVisibleAtMs)
        ? Math.round(state.firstVisibleAtMs * 10) / 10
        : null,
      firstVisibleAfterSubmitMs:
        firstVisibleAfterSubmitMs === null ? null : Math.round(firstVisibleAfterSubmitMs * 10) / 10,
      visibleCountAtFirst: state.visibleCountAtFirst,
    };
  });
}

/**
 * Capture a bounded structural snapshot at failure time without serializing user-visible content.
 * @author kongweiguang
 */
async function captureFailureDomSummary(page) {
  return page.evaluate(() => {
    const forms = [...globalThis.document.querySelectorAll(".wait-form")];
    const isVisible = (element) => {
      const style = globalThis.getComputedStyle(element);
      const bounds = element.getBoundingClientRect();
      return (
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        style.visibility !== "collapse" &&
        bounds.width > 0 &&
        bounds.height > 0
      );
    };
    return {
      readyState: globalThis.document.readyState,
      waitFormCount: forms.length,
      waitFormVisibleCount: forms.filter(isVisible).length,
      waitFormControlCount: forms.reduce(
        (count, form) => count + form.querySelectorAll("input, select, textarea, button").length,
        0,
      ),
    };
  });
}

/** Dispatch genuine browser File objects through the same React drop or clipboard boundary users invoke. */
async function dispatchFileInput(page, selector, mode, fileName, mediaType, bytes) {
  await page.locator(selector).evaluate(
    (target, payload) => {
      const binary = atob(payload.base64);
      const fileBytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
      const transfer = new globalThis.DataTransfer();
      transfer.items.add(new File([fileBytes], payload.fileName, { type: payload.mediaType }));
      if (payload.mode === "drop") {
        target.dispatchEvent(
          new globalThis.DragEvent("dragover", {
            bubbles: true,
            cancelable: true,
            dataTransfer: transfer,
          }),
        );
        target.dispatchEvent(
          new globalThis.DragEvent("drop", {
            bubbles: true,
            cancelable: true,
            dataTransfer: transfer,
          }),
        );
        return;
      }
      target.dispatchEvent(
        new globalThis.ClipboardEvent("paste", {
          bubbles: true,
          cancelable: true,
          clipboardData: transfer,
        }),
      );
    },
    { mode, fileName, mediaType, base64: bytes.toString("base64") },
  );
}

/** Confirm an upload reached a removable draft card, then await its server discard before continuing. */
async function removeUploadedDraft(page, fileName, composer) {
  const draft = page.locator(".attachment-draft").filter({ hasText: fileName });
  await draft.getByText("已上传", { exact: true }).waitFor({ state: "visible" });
  if (!(await composer.evaluate((element) => globalThis.document.activeElement === element))) {
    throw new Error(`Uploading ${fileName} did not preserve focus in the message composer.`);
  }
  await draft.getByRole("button", { name: `移除附件 ${fileName}` }).click();
  await draft.waitFor({ state: "detached" });
  if (!(await composer.evaluate((element) => globalThis.document.activeElement === element))) {
    throw new Error(`Removing ${fileName} did not return focus to the message composer.`);
  }
}

/** PDF 原生阅读器有自己的 Tab 导航，因此用头部按钮验证可预测键盘循环，并独立断言后台夺焦与模态边界。 */
async function auditAttachmentDialogFocus(page, dialog, screenshotPath, options = {}) {
  const backgroundComposer = page.locator('textarea[aria-label="消息"]');
  const inspect = () =>
    page.evaluate(() => {
      const active = globalThis.document.activeElement;
      const modal = globalThis.document.querySelector('[role="dialog"][aria-modal="true"]');
      return {
        tag: active instanceof globalThis.HTMLElement ? active.tagName : active?.nodeName,
        label: active instanceof globalThis.HTMLElement ? active.getAttribute("aria-label") : null,
        title: active instanceof globalThis.HTMLElement ? active.getAttribute("title") : null,
        insideDialog: Boolean(modal?.contains(active)),
        dialogOpen: Boolean(modal && globalThis.getComputedStyle(modal).visibility !== "hidden"),
        isPdfFrame: active instanceof globalThis.HTMLIFrameElement,
      };
    });

  await dialog.waitFor({ state: "visible" });
  await page.waitForFunction(
    () => globalThis.document.querySelector('[role="dialog"][aria-modal="true"]') !== null,
  );
  const focusAfterOpen = await inspect();
  const openedFocusEvents = await page.evaluate(
    (start) => globalThis.__jaDialogFocusEvents.slice(start),
    options.focusEventStart,
  );
  const initialCloseFocusObserved = openedFocusEvents.some(
    (focus) => focus.label === "关闭预览" && focus.insideDialog,
  );
  await dialog.getByRole("button", { name: "关闭预览" }).focus();
  const focusBeforeShiftTab = await inspect();
  await page.keyboard.press("Shift+Tab");
  const focusAfterShiftTab = await inspect();
  await page.keyboard.press("Tab");
  const focusAfterTab = await inspect();
  await backgroundComposer.evaluate((element) => element.focus({ preventScroll: true }));
  const focusAfterBackgroundTarget = await inspect();
  await page.keyboard.press("Tab");
  const focusAfterBackgroundTab = await inspect();
  await page.screenshot({ path: screenshotPath });

  const evidence = {
    focusAfterOpen,
    openedFocusEvents,
    initialCloseFocusObserved,
    focusBeforeShiftTab,
    focusAfterShiftTab,
    focusAfterTab,
    focusAfterBackgroundTarget,
    focusAfterBackgroundTab,
    screenshotPath,
  };
  const focusStayedModal = [
    focusAfterOpen,
    focusAfterShiftTab,
    focusAfterTab,
    focusAfterBackgroundTarget,
    focusAfterBackgroundTab,
  ].every((focus) => focus.insideDialog && focus.dialogOpen);
  const focusChanged = (before, after) =>
    before.tag !== after.tag ||
    before.label !== after.label ||
    before.title !== after.title ||
    before.isPdfFrame !== after.isPdfFrame;
  const shiftTabMovedFocus = focusChanged(focusBeforeShiftTab, focusAfterShiftTab);
  const tabMovedFocus = focusChanged(focusAfterShiftTab, focusAfterTab);
  const initialFocusAllowed =
    focusAfterOpen.label === "关闭预览" ||
    (options.allowPostOpenFocusTransfer && focusAfterOpen.insideDialog);
  if (
    !focusStayedModal ||
    !initialCloseFocusObserved ||
    !initialFocusAllowed ||
    !shiftTabMovedFocus ||
    !tabMovedFocus ||
    focusAfterShiftTab.label !== "下载附件" ||
    focusAfterTab.label !== "关闭预览" ||
    !focusAfterBackgroundTarget.insideDialog
  ) {
    throw new Error(`Attachment preview focus boundary failed: ${JSON.stringify(evidence)}`);
  }
  return evidence;
}

/** 从受控 multipart 请求读取身份字段，核对重试仍使用原操作键和 Thread。 */
function multipartTextField(body, fieldName) {
  const match = body
    .toString("latin1")
    .match(new RegExp(`name="${fieldName}"\\r\\n\\r\\n([^\\r\\n]+)`));
  return match?.[1];
}

/** Annotate only requests active at explicit user navigation boundaries before treating aborts as expected. */
function markActiveCancellations(activeRequests, cancellationReasons, stage, matches, reason) {
  for (const request of activeRequests) {
    if (matches(new URL(request.url()).pathname))
      cancellationReasons.set(request, { stage, reason });
  }
}

/** Allow only attachment reads superseded or released by a named preview action to abort without hiding unrelated request failures. */
function expectedAttachmentCancellation(stage, pathname, noteSourcePdfAttachmentId) {
  const content = pathname.includes("/attachments/") && pathname.endsWith("/content");
  const metadata = pathname.endsWith("/attachments/metadata");
  const noteSourcePdfPath = noteSourcePdfAttachmentId
    ? `/api/chat/attachments/${encodeURIComponent(noteSourcePdfAttachmentId)}/content`
    : null;
  const reasons = {
    "draft-preview-open": content && "draft thumbnail replaced by its preview",
    "draft-preview-close": content && "draft preview closed with Escape",
    "history-preview-open": content && "history thumbnail replaced by its preview",
    "history-preview-close":
      (content || metadata) && "history preview closed and pending attachment reads released",
    "note-source-preview-open":
      pathname === noteSourcePdfPath && "PDF source thumbnail replaced by its preview",
    "note-source-preview-close": content && "note source preview closed",
  };
  const reason = reasons[stage];
  return reason ? { stage, reason } : null;
}

/** Wait for the PDF document to load and save a screenshot only after repeated identical paints. */
async function captureStablePdfPreview(page, dialog, iframe, expectedBlobUrl, screenshotPath) {
  const iframeElement = await iframe.elementHandle();
  const frame = await iframeElement?.contentFrame();
  if (!frame) throw new Error("The PDF preview iframe did not create a browser document.");
  await frame.waitForLoadState("load");
  const documentState = await frame.evaluate(() => ({
    url: globalThis.location.href,
    readyState: globalThis.document.readyState,
  }));
  if (frame.url() !== expectedBlobUrl || documentState.url !== expectedBlobUrl) {
    throw new Error(
      `The PDF iframe did not navigate to its verified Blob URL: ${JSON.stringify({
        frameUrl: frame.url(),
        documentState,
      })}`,
    );
  }
  if (documentState.readyState !== "complete") {
    throw new Error(`The PDF preview document is not complete: ${documentState.readyState}`);
  }

  let previous;
  let stablePaints = 0;
  let screenshot;
  for (let attempt = 0; attempt < 24; attempt += 1) {
    await page.evaluate(
      () =>
        new Promise((resolve) =>
          globalThis.requestAnimationFrame(() => globalThis.requestAnimationFrame(resolve)),
        ),
    );
    const current = await dialog.screenshot({ animations: "disabled" });
    if (previous?.equals(current)) stablePaints += 1;
    else stablePaints = 0;
    if (stablePaints >= 2) {
      screenshot = current;
      break;
    }
    previous = current;
  }
  if (!screenshot) throw new Error("The PDF preview did not reach a stable rendered frame.");
  await writeFile(screenshotPath, screenshot);
  return {
    frameUrl: frame.url(),
    documentReadyState: documentState.readyState,
    stablePaints,
    screenshotPath,
  };
}

/** Accept a PDF Blob abort only when its exact URL, stage, byte checks, screenshot, and revoke evidence agree. */
function classifyVerifiedPdfPreviewAborts(
  pending,
  verifiedPreviews,
  abortedRequests,
  failedRequests,
) {
  for (const detail of pending) {
    const evidence = verifiedPreviews.get(detail.url);
    if (
      evidence?.requestStage === detail.stage &&
      evidence.blobType === "application/pdf" &&
      evidence.contentType === "application/pdf" &&
      evidence.exactBytes === true &&
      evidence.frameReady === true &&
      evidence.screenshotPath &&
      evidence.closedAndRevoked === true
    ) {
      abortedRequests.push({
        ...detail,
        cancellation: "verified PDF preview closed and its validated Blob URL was revoked",
        boundary: evidence.requestStage,
        previewScreenshot: evidence.screenshotPath,
      });
    } else {
      failedRequests.push(detail);
    }
  }
}

/** Require the same Turn to prove approval, one committed note, all policy facts, and terminal completion. */
function verifyDurableWorkflow(diagnostics, submission, approvalAnswers, approvalStatuses) {
  const status = diagnostics.status?.body;
  const events = diagnostics.events?.body?.events ?? [];
  const history = diagnostics.threadHistory?.body;
  const facts = diagnostics.policyFacts?.body?.facts ?? [];
  const notes = (diagnostics.notes?.body ?? []).filter((note) => note.turnId === submission.turnId);
  const attachmentIds = submission.content?.attachmentIds ?? [];
  const userInput = history?.items?.find(
    (item) => item.turnId === submission.turnId && item.kind === "user",
  );
  const inputAttachmentNames =
    userInput?.content?.attachments?.map((attachment) => attachment.fileName) ?? [];
  const sourceAttachmentIds = notes[0]?.sourceAttachmentIds ?? [];
  const approves = approvalAnswers.some((request) =>
    request.answers?.some(
      (answer) => answer.optionIds?.includes("option_approve") && !answer.skipped,
    ),
  );
  const approvalWaitRequested = events.some(
    (event) =>
      event.eventType === "agent.wait.requested" &&
      JSON.parse(event.payloadJson).kind === "approval",
  );
  const resolved = events.some(
    (event) =>
      event.eventType === "agent.execution.control" &&
      JSON.parse(event.payloadJson).action === "WAIT_RESOLVED",
  );
  const approvalCommitted = approves && approvalWaitRequested && resolved;
  const requiredFacts = [
    "document.note.prepared",
    "document.policy.decision",
    "document.note.saved",
    "document.delivery.completed",
  ];
  const factsComplete = requiredFacts.every(
    (type) => facts.filter((fact) => fact.type === type).length === 1,
  );
  const closure = {
    status: status?.state,
    identityTurnId: status?.identity?.turnId,
    latestTurn: history?.latestTurn,
    approvalOptionSubmitted: approves,
    approvalHttpStatuses: approvalStatuses,
    approvalCommitted,
    durableWaitResolution: resolved,
    noteCount: notes.length,
    inputAttachmentIds: attachmentIds,
    inputAttachmentNames,
    noteSourceAttachmentIds: sourceAttachmentIds,
    policyFactTypes: facts.map((fact) => fact.type),
  };
  if (
    status?.state !== "COMPLETED" ||
    status.identity?.turnId !== submission.turnId ||
    history?.latestTurn?.turnId !== submission.turnId ||
    history.latestTurn.state !== "COMPLETED" ||
    !approves ||
    !approvalCommitted ||
    !resolved ||
    notes.length !== 1 ||
    !factsComplete
  ) {
    throw new Error(
      `Durable enterprise workflow evidence is incomplete: ${JSON.stringify(closure)}`,
    );
  }
  if (
    attachmentIds.length !== 2 ||
    inputAttachmentNames.length !== 2 ||
    inputAttachmentNames.includes("browser-drop.png") ||
    inputAttachmentNames.includes("browser-paste.png") ||
    attachmentIds.some((attachmentId) => !sourceAttachmentIds.includes(attachmentId))
  ) {
    throw new Error(
      `Saved note does not retain both submitted source attachments: ${JSON.stringify(closure)}`,
    );
  }
  return closure;
}

/**
 * Exercise the isolated enterprise workflow; monotonic network/UI evidence stays separate from live SSE data.
 * @author kongweiguang
 */
async function main() {
  const backend = process.env.JA_EXAMPLE_BACKEND_URL;
  const fixture = process.env.JA_EXAMPLE_ATTACHMENT_FIXTURE_PATH;
  const pdfFixture = process.env.JA_EXAMPLE_PDF_FIXTURE_PATH;
  if (!backend || !fixture || !pdfFixture)
    throw new Error("The Spring backend URL and image/PDF fixtures are required.");

  const root = process.cwd();
  const artifacts = await mkdtemp(path.join(tmpdir(), "ja-spring-enterprise-document-"));
  const configFile = path.join(
    root,
    "ja-agent/example/ja-agent-example-springboot/frontend/vite.config.ts",
  );
  const vite = await createServer({
    configFile,
    cacheDir: path.join(artifacts, "vite-cache"),
    server: { host: "127.0.0.1", port: 0, strictPort: false },
    clearScreen: false,
  });
  let browser;
  let page;
  const pageErrors = [];
  const consoleErrors = [];
  const expectedUploadLossConsoleErrors = [];
  const failedRequests = [];
  const abortedRequests = [];
  const pendingSubmissionAborts = [];
  const failedResponses = [];
  const submissions = [];
  const approvalAnswers = [];
  const approvalStatuses = [];
  const attachmentUploadResponses = [];
  const faviconResponses = [];
  const phaseTimings = [];
  const pendingPdfBlobAborts = [];
  const injectedUploadLosses = new WeakMap();
  const injectedUploadFailures = [];
  const verifiedPdfPreviews = new Map();
  let noteSourcePdfAttachmentId;
  let pdfBlobAbortsClassified = false;
  let lostResponseRetryEvidence;
  const activeRequests = new Set();
  const requestStages = new WeakMap();
  const cancellationReasons = new WeakMap();
  const waitReadStartedAt = new WeakMap();
  const waitReadStatuses = new WeakMap();
  const submissionStreamRequests = new WeakSet();
  const timingOrigin = performance.now();
  /**
   * Keep timing evidence to fixed phases, outcomes, HTTP status, and monotonic durations.
   * @author kongweiguang
   */
  const recordTiming = (phase, outcome, details = {}) => {
    const event = {
      phase,
      outcome,
      elapsedMs: Math.round((performance.now() - timingOrigin) * 10) / 10,
    };
    if (Number.isInteger(details.status)) event.httpStatus = details.status;
    if (Number.isFinite(details.durationMs))
      event.durationMs = Math.round(details.durationMs * 10) / 10;
    if (["status", "events", "policyFacts", "notes", "threadHistory"].includes(details.operation))
      event.operation = details.operation;
    phaseTimings.push(event);
  };
  let stage = "startup";
  let attachmentInteractionEvidence;
  let focusAuditEvidence;

  try {
    await vite.listen();
    const address = vite.httpServer?.address();
    if (!address || typeof address === "string") throw new Error("Vite did not bind an HTTP port.");
    browser = await chromium.launch({ headless: true, channel: "chromium" });
    page = await browser.newPage({ viewport: { width: 1280, height: 820 }, colorScheme: "light" });
    page.setDefaultTimeout(15_000);
    await page.addInitScript(() => {
      const created = [];
      const revoked = [];
      const dialogFocusEvents = [];
      const createObjectURL = URL.createObjectURL.bind(URL);
      const revokeObjectURL = URL.revokeObjectURL.bind(URL);
      URL.createObjectURL = (blob) => {
        const url = createObjectURL(blob);
        created.push({ url, type: blob.type });
        return url;
      };
      URL.revokeObjectURL = (url) => {
        revoked.push(url);
        revokeObjectURL(url);
      };
      globalThis.__jaAttachmentObjectUrls = { created, revoked };
      globalThis.__jaDialogFocusEvents = dialogFocusEvents;
      globalThis.document.addEventListener(
        "focusin",
        () => {
          const active = globalThis.document.activeElement;
          const dialog = globalThis.document.querySelector('[role="dialog"][aria-modal="true"]');
          if (!dialog) return;
          dialogFocusEvents.push({
            tag: active instanceof globalThis.HTMLElement ? active.tagName : active?.nodeName,
            label:
              active instanceof globalThis.HTMLElement ? active.getAttribute("aria-label") : null,
            title: active instanceof globalThis.HTMLElement ? active.getAttribute("title") : null,
            insideDialog: Boolean(dialog.contains(active)),
            isPdfFrame: active instanceof globalThis.HTMLIFrameElement,
          });
        },
        true,
      );
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("console", (message) => {
      if (message.type() === "error") {
        const detail = { text: message.text(), location: message.location() };
        if (
          stage === "upload-response-loss" &&
          detail.text === "Failed to load resource: net::ERR_FAILED" &&
          new URL(detail.location.url).pathname === "/api/chat/attachments/upload"
        ) {
          expectedUploadLossConsoleErrors.push(detail);
        } else consoleErrors.push(detail);
      }
    });
    page.on("requestfailed", (request) => {
      activeRequests.delete(request);
      const pathname = new URL(request.url()).pathname;
      const failure = request.failure()?.errorText;
      if (waitReadStartedAt.has(request)) {
        recordTiming("wait_read", "request_failed", {
          durationMs: performance.now() - waitReadStartedAt.get(request),
        });
      }
      const detail = { url: request.url(), failure, stage: requestStages.get(request) };
      const injectedUploadLoss = injectedUploadLosses.get(request);
      if (injectedUploadLoss) {
        injectedUploadLoss.transportFailure = failure;
        if (lostResponseRetryEvidence) lostResponseRetryEvidence.transportFailure = failure;
        injectedUploadFailures.push({ ...detail, reason: injectedUploadLoss.reason });
        return;
      }
      if (failure === "net::ERR_ABORTED" && detail.url.startsWith("blob:")) {
        pendingPdfBlobAborts.push(detail);
        return;
      }
      const intentional =
        cancellationReasons.get(request) ??
        (failure === "net::ERR_ABORTED"
          ? expectedAttachmentCancellation(
              requestStages.get(request),
              pathname,
              noteSourcePdfAttachmentId,
            )
          : null);
      if (
        failure === "net::ERR_ABORTED" &&
        pathname === "/api/chat/stream" &&
        detail.stage === "message-submit"
      ) {
        pendingSubmissionAborts.push(detail);
        return;
      }
      if (
        failure === "net::ERR_ABORTED" &&
        (intentional || (pathname.endsWith("/answers") && detail.stage === "approval-response"))
      ) {
        abortedRequests.push({
          ...detail,
          cancellation:
            intentional?.reason ?? "approval response transport ended; durable receipt must verify",
          boundary: intentional?.stage ?? "approval-response",
        });
      } else {
        failedRequests.push(detail);
      }
    });
    page.on("response", (response) => {
      const request = response.request();
      activeRequests.delete(request);
      const pathname = new URL(response.url()).pathname;
      if (submissionStreamRequests.has(request))
        recordTiming("submission_http", "response_headers", { status: response.status() });
      if (waitReadStartedAt.has(request)) {
        waitReadStatuses.set(request, response.status());
        recordTiming("wait_read", "response_headers", { status: response.status() });
      }
      if (pathname === "/favicon.png")
        faviconResponses.push({ url: response.url(), status: response.status() });
      if (pathname.endsWith("/attachments/upload") && response.status() < 400) {
        attachmentUploadResponses.push(response.json());
      }
      if (pathname.endsWith("/answers")) approvalStatuses.push(response.status());
      if (response.status() >= 400)
        failedResponses.push({
          status: response.status(),
          url: response.url(),
        });
    });
    page.on("request", (request) => {
      activeRequests.add(request);
      requestStages.set(request, stage);
      const pathname = new URL(request.url()).pathname;
      if (pathname === "/api/chat/stream") {
        submissions.push(request.postDataJSON());
        if (requestStages.get(request) === "message-submit") {
          submissionStreamRequests.add(request);
          recordTiming("submission_http", "request_started");
        }
      }
      if (pathname === "/api/chat/wait") {
        waitReadStartedAt.set(request, performance.now());
        recordTiming("wait_read", "request_started");
      }
      if (pathname === "/api/chat/answers") approvalAnswers.push(request.postDataJSON());
    });
    page.on("requestfinished", (request) => {
      activeRequests.delete(request);
      if (waitReadStartedAt.has(request)) {
        recordTiming("wait_read", "response_complete", {
          status: waitReadStatuses.get(request),
          durationMs: performance.now() - waitReadStartedAt.get(request),
        });
      }
    });

    await page.goto(`http://127.0.0.1:${address.port}`, { waitUntil: "networkidle" });
    if (
      !faviconResponses.some((response) => response.status === 200) ||
      faviconResponses.some((response) => response.status >= 400)
    ) {
      throw new Error(
        `The Ja favicon was not served successfully: ${JSON.stringify(faviconResponses)}`,
      );
    }
    const composer = page.getByRole("textbox", { name: "消息" });
    await composer.waitFor({ state: "visible" });
    const composerThreadId = await composer.getAttribute("data-thread-id");
    if (!composerThreadId)
      throw new Error("The message composer is missing its active Thread identity.");
    const entryScreenshot = path.join(artifacts, "enterprise-document-workbench-entry.png");
    await page.screenshot({ path: entryScreenshot });
    await composer.fill("输入法候选草稿");
    await composer.evaluate((element) => {
      element.dispatchEvent(
        new globalThis.CompositionEvent("compositionstart", { bubbles: true, data: "zh" }),
      );
      element.dispatchEvent(
        new globalThis.KeyboardEvent("keydown", {
          key: "Enter",
          code: "Enter",
          keyCode: 229,
          which: 229,
          isComposing: true,
          bubbles: true,
        }),
      );
      element.dispatchEvent(
        new globalThis.CompositionEvent("compositionend", { bubbles: true, data: "中" }),
      );
    });
    if (submissions.length !== 0 || (await composer.inputValue()) !== "输入法候选草稿") {
      throw new Error("Enter during IME composition submitted or changed the draft.");
    }
    await composer.press("Shift+Enter");
    if (!(await composer.inputValue()).includes("\n") || submissions.length !== 0) {
      throw new Error("Shift+Enter did not preserve multiline draft input.");
    }
    await composer.fill("");
    const fileName = "browser-evidence.png";

    await page.getByRole("button", { name: "添加资料或 Skill" }).click();
    await page.getByRole("menuitem", { name: "上传资料" }).click();
    stage = "attachment-drop";
    const imageBytes = await readFile(fixture);
    await composer.focus();
    await dispatchFileInput(
      page,
      ".conversation-content",
      "drop",
      "browser-drop.png",
      "image/png",
      imageBytes,
    );
    await removeUploadedDraft(page, "browser-drop.png", composer);
    stage = "attachment-paste";
    await composer.focus();
    await dispatchFileInput(
      page,
      'textarea[aria-label="消息"]',
      "paste",
      "browser-paste.png",
      "image/png",
      imageBytes,
    );
    await removeUploadedDraft(page, "browser-paste.png", composer);
    await composer.focus();
    const lostResponseFileName = "browser-lost-response.png";
    const lostResponseFileBytes = await readFile(fixture);
    await page.route("**/api/chat/attachments/upload", async (route) => {
      const request = route.request();
      const body = request.postDataBuffer() ?? Buffer.alloc(0);
      if (!body.includes(Buffer.from(`filename="${lostResponseFileName}"`))) {
        await route.continue();
        return;
      }

      const operationKey = multipartTextField(body, "operationKey");
      const threadId = multipartTextField(body, "threadId");
      const exactFileBytes = body.includes(lostResponseFileBytes);
      if (!operationKey || !threadId || !exactFileBytes) {
        throw new Error(
          "The lost-response upload request did not contain its original key, Thread, and bytes.",
        );
      }
      if (!lostResponseRetryEvidence) {
        const committed = await route.fetch();
        const responseBytes = await committed.body();
        const metadata = JSON.parse(responseBytes.toString("utf8"));
        if (committed.status() !== 200 || !metadata.id) {
          throw new Error(
            `The injected response loss requires a committed 200 upload: ${committed.status()}`,
          );
        }
        lostResponseRetryEvidence = {
          fileName: lostResponseFileName,
          operationKey,
          threadId,
          firstServerStatus: committed.status(),
          firstAttachmentId: metadata.id,
          firstRequestExactBytes: exactFileBytes,
          firstResponseDropped: true,
          retryCount: 0,
          transportFailure: null,
        };
        injectedUploadLosses.set(request, {
          reason: "the browser response was dropped after the upstream server committed HTTP 200",
        });
        await route.abort("failed");
        return;
      }

      lostResponseRetryEvidence.retryCount += 1;
      lostResponseRetryEvidence.retryOperationKey = operationKey;
      lostResponseRetryEvidence.retryThreadId = threadId;
      lostResponseRetryEvidence.retryExactBytes = exactFileBytes;
      if (
        lostResponseRetryEvidence.retryCount !== 1 ||
        operationKey !== lostResponseRetryEvidence.operationKey ||
        threadId !== lostResponseRetryEvidence.threadId ||
        !exactFileBytes
      ) {
        throw new Error(
          `The upload retry changed its original identity or bytes: ${JSON.stringify(lostResponseRetryEvidence)}`,
        );
      }
      const retried = await route.fetch();
      const retryBytes = await retried.body();
      const retryMetadata = JSON.parse(retryBytes.toString("utf8"));
      if (
        retried.status() !== 200 ||
        retryMetadata.id !== lostResponseRetryEvidence.firstAttachmentId
      ) {
        throw new Error(
          `The automatic idempotent retry did not return the committed attachment: ${JSON.stringify({ status: retried.status(), metadata: retryMetadata, evidence: lostResponseRetryEvidence })}`,
        );
      }
      lostResponseRetryEvidence.retryAttachmentId = retryMetadata.id;
      lostResponseRetryEvidence.retryResponseStatus = retried.status();
      await route.fulfill({ response: retried, body: retryBytes });
    });
    stage = "upload-response-loss";
    await page.getByRole("button", { name: "添加资料或 Skill" }).click();
    await page.getByRole("menuitem", { name: "上传资料" }).click();
    await page
      .getByLabel("选择图片或 PDF 资料")
      .setInputFiles([
        { name: lostResponseFileName, mimeType: "image/png", buffer: lostResponseFileBytes },
      ]);
    const uncertainDraft = page
      .locator(".attachment-draft")
      .filter({ hasText: lostResponseFileName });
    await uncertainDraft.getByText("已上传", { exact: true }).waitFor({ state: "visible" });
    if (
      injectedUploadFailures.length !== 1 ||
      injectedUploadFailures[0]?.failure !== "net::ERR_FAILED" ||
      lostResponseRetryEvidence?.transportFailure !== "net::ERR_FAILED" ||
      expectedUploadLossConsoleErrors.length !== 1 ||
      lostResponseRetryEvidence.retryCount !== 1
    ) {
      throw new Error(
        `The server-committed upload response loss and automatic reconciliation were not isolated: ${JSON.stringify({ injectedUploadFailures, expectedUploadLossConsoleErrors, lostResponseRetryEvidence })}`,
      );
    }
    const lostResponseRetryMetadata = (await Promise.all(attachmentUploadResponses)).find(
      (attachment) => attachment.fileName === lostResponseFileName,
    );
    if (
      !lostResponseRetryMetadata ||
      lostResponseRetryMetadata.id !== lostResponseRetryEvidence.firstAttachmentId
    ) {
      throw new Error(
        `The idempotent upload retry did not return the already committed attachment: ${JSON.stringify({ lostResponseRetryMetadata, lostResponseRetryEvidence })}`,
      );
    }
    if (lostResponseRetryEvidence.retryAttachmentId !== lostResponseRetryMetadata.id) {
      throw new Error("The browser and upstream retry receipts returned different attachment IDs.");
    }
    await removeUploadedDraft(page, lostResponseFileName, composer);

    const pdfBytes = await readFile(pdfFixture);
    stage = "attachment-upload";
    await page.getByRole("button", { name: "添加资料或 Skill" }).click();
    await page.getByRole("menuitem", { name: "上传资料" }).click();
    await page.getByLabel("选择图片或 PDF 资料").setInputFiles([
      { name: fileName, mimeType: "image/png", buffer: imageBytes },
      { name: "browser-evidence.pdf", mimeType: "application/pdf", buffer: pdfBytes },
    ]);
    const imageDraft = page.locator(".attachment-draft").filter({ hasText: fileName });
    const pdfDraft = page.locator(".attachment-draft").filter({ hasText: "browser-evidence.pdf" });
    await imageDraft.getByText("已上传", { exact: true }).waitFor({ state: "visible" });
    await pdfDraft.getByText("已上传", { exact: true }).waitFor({ state: "visible" });
    if (!(await composer.evaluate((element) => globalThis.document.activeElement === element))) {
      throw new Error("File-picker uploads did not return focus to the message composer.");
    }
    const uploadMetadata = await Promise.all(attachmentUploadResponses);
    const pdfAttachment = uploadMetadata.find(
      (attachment) => attachment.fileName === "browser-evidence.pdf",
    );
    if (!pdfAttachment?.id)
      throw new Error("The PDF upload did not return its durable attachment ID.");
    noteSourcePdfAttachmentId = pdfAttachment.id;
    const pdfContentPath = `/api/chat/attachments/${encodeURIComponent(pdfAttachment.id)}/content`;
    const uploadScreenshot = path.join(artifacts, "enterprise-document-uploaded-drafts.png");
    await page.screenshot({ path: uploadScreenshot });

    stage = "draft-preview-open";
    markActiveCancellations(
      activeRequests,
      cancellationReasons,
      stage,
      (pathname) => pathname.includes("/attachments/") && pathname.endsWith("/content"),
      "draft thumbnail replaced by its preview",
    );
    const pdfPreviewResponse = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname === pdfContentPath && url.searchParams.get("download") !== "true";
    });
    const pdfFocusEventStart = await page.evaluate(() => globalThis.__jaDialogFocusEvents.length);
    await pdfDraft.locator(".attachment-open").click();
    const draftPreview = page.getByRole("dialog", { name: "browser-evidence.pdf" });
    const pdfFrame = draftPreview.locator('iframe[title="预览 browser-evidence.pdf"]');
    await pdfFrame.waitFor({ state: "visible" });
    const pdfBlobUrl = await pdfFrame.getAttribute("src");
    if (!pdfBlobUrl?.startsWith("blob:"))
      throw new Error("PDF preview did not use the fetched attachment Blob URL.");
    const pdfBlobType = await page.evaluate(
      (url) => globalThis.__jaAttachmentObjectUrls.created.find((item) => item.url === url)?.type,
      pdfBlobUrl,
    );
    if (pdfBlobType !== "application/pdf")
      throw new Error(`PDF preview Blob has unexpected MIME type: ${pdfBlobType}`);
    const previewResponse = await pdfPreviewResponse;
    const previewBytes = await previewResponse.body();
    const previewContentType = previewResponse.headers()["content-type"]?.split(";")[0];
    const previewBytesMatch = previewBytes.equals(pdfBytes);
    if (
      previewContentType !== "application/pdf" ||
      !previewBytes.subarray(0, 8).equals(Buffer.from("%PDF-1.4")) ||
      !previewBytesMatch
    ) {
      throw new Error(
        `PDF preview response mismatch: ${JSON.stringify({
          url: previewResponse.url(),
          expectedPath: pdfContentPath,
          contentType: previewContentType,
          byteLength: previewBytes.length,
          fixtureLength: pdfBytes.length,
          exactBytes: previewBytesMatch,
        })}`,
      );
    }
    const pdfPreviewScreenshot = path.join(artifacts, "enterprise-document-pdf-preview.png");
    const pdfPreviewPaint = await captureStablePdfPreview(
      page,
      draftPreview,
      pdfFrame,
      pdfBlobUrl,
      pdfPreviewScreenshot,
    );
    verifiedPdfPreviews.set(pdfBlobUrl, {
      requestStage: "draft-preview-open",
      blobType: pdfBlobType,
      contentType: previewContentType,
      exactBytes: previewBytesMatch,
      frameReady: pdfPreviewPaint.documentReadyState === "complete",
      screenshotPath: pdfPreviewPaint.screenshotPath,
      closedAndRevoked: false,
    });
    focusAuditEvidence = {
      pdf: await auditAttachmentDialogFocus(
        page,
        draftPreview,
        path.join(artifacts, "enterprise-document-pdf-focus-boundary.png"),
        { focusEventStart: pdfFocusEventStart, allowPostOpenFocusTransfer: true },
      ),
    };
    await draftPreview.getByRole("button", { name: "关闭预览" }).focus();
    const downloadResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname === pdfContentPath && url.searchParams.get("download") === "true";
    });
    const browserDownloadPromise = page.waitForEvent("download");
    await draftPreview.getByRole("button", { name: "下载附件" }).click();
    const downloadResponse = await downloadResponsePromise;
    const browserDownload = await browserDownloadPromise;
    const downloadBytes = await downloadResponse.body();
    if (
      browserDownload.suggestedFilename() !== "browser-evidence.pdf" ||
      downloadResponse.headers()["content-type"]?.split(";")[0] !== "application/pdf" ||
      downloadResponse.headers()["content-disposition"]?.toLowerCase().includes("attachment") !==
        true ||
      !downloadBytes.equals(pdfBytes)
    ) {
      throw new Error(
        "PDF download did not preserve its file name, attachment disposition, MIME type, and original bytes.",
      );
    }
    stage = "draft-preview-close";
    markActiveCancellations(
      activeRequests,
      cancellationReasons,
      stage,
      (pathname) => pathname.includes("/attachments/") && pathname.endsWith("/content"),
      "draft PDF preview closed",
    );
    await page.keyboard.press("Escape");
    await draftPreview.waitFor({ state: "hidden" });
    await page.waitForFunction(
      (url) => globalThis.__jaAttachmentObjectUrls.revoked.includes(url),
      pdfBlobUrl,
    );
    await page.waitForFunction(
      (threadId) =>
        globalThis.document.activeElement instanceof globalThis.HTMLTextAreaElement &&
        globalThis.document.activeElement.dataset.threadId === threadId,
      composerThreadId,
    );
    verifiedPdfPreviews.get(pdfBlobUrl).closedAndRevoked = true;
    const pdfPreviewReturnedToComposer = await composer.evaluate(
      (element) => globalThis.document.activeElement === element,
    );
    attachmentInteractionEvidence = {
      dropCardRemoved: true,
      pasteCardRemoved: true,
      filePickerReturnedToComposer: true,
      pdfPreview: {
        iframeBlobUrl: pdfBlobUrl,
        blobType: pdfBlobType,
        contentType: previewResponse.headers()["content-type"],
        byteLength: previewBytes.length,
        exactBytes: true,
        blobUrlRevokedOnClose: true,
        returnedToComposer: pdfPreviewReturnedToComposer,
      },
      pdfDownload: {
        suggestedFileName: browserDownload.suggestedFilename(),
        contentType: downloadResponse.headers()["content-type"],
        attachmentDisposition: downloadResponse.headers()["content-disposition"],
        byteLength: downloadBytes.length,
        exactBytes: true,
      },
    };
    stage = "draft-composer";
    await composer.focus();

    const prompt = "请阅读我上传的图片资料并保存为笔记";
    stage = "message-submit";
    await composer.fill(prompt);
    await armApprovalFormObserver(page);
    const submitButton = page.getByRole("button", { name: "发送" });
    recordTiming("submission", "click_started");
    await page.evaluate(() => {
      const state = globalThis.__jaEnterpriseWaitFormTiming;
      if (state) state.submitClickedAtMs = globalThis.performance.now();
    });
    await submitButton.click();
    recordTiming("submission", "click_resolved");
    const approval = page.locator(".wait-form");
    recordTiming("approval_form", "wait_started");
    try {
      await approval.waitFor({ state: "visible" });
      recordTiming("approval_form", "wait_resolved");
    } catch (failure) {
      recordTiming(
        "approval_form",
        failure?.name === "TimeoutError" ? "wait_timed_out" : "wait_failed",
      );
      throw failure;
    }
    stage = "approval-wait";
    markActiveCancellations(
      activeRequests,
      cancellationReasons,
      stage,
      (pathname) => pathname.endsWith("/stream") || pathname.endsWith("/reconnect"),
      "live stream handed off at durable approval wait",
    );
    const approvalQuestion = (await approval.locator("legend").first().innerText()).trim();
    const approvalNote = approval.locator(".approval-note");
    const approvalBody = approval.getByRole("region", { name: "待保存的笔记正文" });
    const approvalFields = await approvalNote.locator("dd").allTextContents();
    const approvalSourceNames = await approvalNote
      .locator(".queue-attachments .attachment-name")
      .allTextContents();
    const approvalTechnical = approval.locator(".approval-technical");
    const approvalRawJson = approvalTechnical.locator("pre");
    const approvalSummaryText = await approvalNote.innerText();
    if (
      !approvalQuestion.includes("是否保存这份笔记？") ||
      approvalFields[0]?.trim() !== "企业资料摘要" ||
      approvalFields[1]?.trim() !== "资料事实：浏览器上传的原始图片。" ||
      (await approvalBody.getAttribute("tabindex")) !== "0" ||
      !approvalSourceNames.includes(fileName) ||
      !approvalSourceNames.includes("browser-evidence.pdf") ||
      (await approvalTechnical.evaluate((element) => element.open)) ||
      (await approvalRawJson.isVisible()) ||
      approvalSummaryText.includes("sourceAttachmentIds") ||
      /att_[a-f0-9]{16,}/.test(approvalSummaryText)
    ) {
      throw new Error(
        `The approval summary exposed incomplete business details or raw parameters: ${JSON.stringify({ approvalQuestion, approvalFields, approvalSourceNames, approvalSummaryText })}`,
      );
    }
    await approvalTechnical.locator("summary").click();
    await approvalRawJson.waitFor({ state: "visible" });
    const rawApprovalPrompt = await approvalRawJson.innerText();
    const approvalPromptPrefix = "允许执行 save_note？\n";
    let finalApprovalParameters;
    try {
      if (!rawApprovalPrompt.startsWith(approvalPromptPrefix))
        throw new Error("The native approval question prefix changed.");
      finalApprovalParameters = JSON.parse(rawApprovalPrompt.slice(approvalPromptPrefix.length));
    } catch (failure) {
      throw new Error(
        `The expanded technical details did not show the native final JSON: ${String(failure)}`,
      );
    }
    if (
      finalApprovalParameters.title !== "企业资料摘要" ||
      finalApprovalParameters.body !== "资料事实：浏览器上传的原始图片。" ||
      JSON.stringify(finalApprovalParameters.sourceAttachmentIds) !==
        JSON.stringify(submissions[0].content.attachmentIds)
    ) {
      throw new Error(
        `The expanded native approval JSON did not match the final note parameters: ${JSON.stringify(finalApprovalParameters)}`,
      );
    }
    await approvalTechnical.locator("summary").click();
    await approvalRawJson.waitFor({ state: "hidden" });
    if (await approvalTechnical.evaluate((element) => element.open))
      throw new Error("The approval technical details did not collapse after inspection.");
    const approvalScreenshot = path.join(artifacts, "enterprise-document-approval.png");
    await page.screenshot({ path: approvalScreenshot });
    await approval.locator('input[type="radio"]').first().check();
    stage = "approval-response";
    await approval.getByRole("button", { name: "确认并继续" }).click();
    const finalAnswer = page
      .locator(".assistant-prose, .final-answer")
      .filter({ hasText: "企业资料摘要" })
      .last();
    await finalAnswer.waitFor({ state: "visible", timeout: 30_000 });
    await page.locator('.status-line[data-state="COMPLETED"]').waitFor({ state: "attached" });
    if (submissions.length !== 1 || submissions[0]?.content?.attachmentIds?.length !== 2) {
      throw new Error(
        "The submitted user Content did not retain exactly the selected PNG and PDF attachments.",
      );
    }
    const durableDiagnostics = await readBackendDiagnostics(backend, submissions[0], recordTiming);
    const durableWorkflow = verifyDurableWorkflow(
      durableDiagnostics,
      submissions[0],
      approvalAnswers,
      approvalStatuses,
    );
    if (pendingSubmissionAborts.length === 1) {
      abortedRequests.push({
        ...pendingSubmissionAborts[0],
        cancellation:
          "stream ended after the same Turn reached and resolved its durable approval wait",
        boundary: "verified-durable-workflow",
      });
    } else if (pendingSubmissionAborts.length > 1) {
      failedRequests.push(...pendingSubmissionAborts);
    }

    stage = "history-reload";
    markActiveCancellations(
      activeRequests,
      cancellationReasons,
      stage,
      (pathname) =>
        (pathname.startsWith("/api/chat/") &&
          /\/(stream|reconnect|events\/stream|attachments\/metadata)$/.test(pathname)) ||
        (pathname.includes("/attachments/") && pathname.endsWith("/content")),
      "page reloaded to verify persisted history",
    );
    await page.reload({ waitUntil: "domcontentloaded" });
    await page
      .locator(".assistant-prose, .final-answer")
      .filter({ hasText: "企业资料摘要" })
      .last()
      .waitFor({ state: "visible" });
    stage = "history-preview-open";
    for (const historicalName of [fileName, "browser-evidence.pdf"]) {
      await page
        .locator(".message-attachments .attachment-open")
        .filter({ hasText: historicalName })
        .waitFor({ state: "visible" });
    }
    const historicalAttachment = page
      .locator(".message-attachments .attachment-open")
      .filter({ hasText: fileName });
    await historicalAttachment.waitFor({ state: "visible" });
    markActiveCancellations(
      activeRequests,
      cancellationReasons,
      stage,
      (pathname) => pathname.includes("/attachments/") && pathname.endsWith("/content"),
      "history thumbnail replaced by its preview",
    );
    const pngFocusEventStart = await page.evaluate(() => globalThis.__jaDialogFocusEvents.length);
    await historicalAttachment.click();
    const historyPreview = page.getByRole("dialog", { name: fileName });
    await historyPreview.getByRole("img", { name: fileName }).waitFor({ state: "visible" });
    focusAuditEvidence.png = await auditAttachmentDialogFocus(
      page,
      historyPreview,
      path.join(artifacts, "enterprise-document-png-focus-boundary.png"),
      { focusEventStart: pngFocusEventStart },
    );
    stage = "history-preview-close";
    markActiveCancellations(
      activeRequests,
      cancellationReasons,
      stage,
      (pathname) =>
        (pathname.includes("/attachments/") && pathname.endsWith("/content")) ||
        pathname.endsWith("/attachments/metadata"),
      "history preview closed and pending attachment reads released",
    );
    await page.keyboard.press("Escape");
    await historyPreview.waitFor({ state: "hidden" });
    await page.waitForFunction(
      (threadId) =>
        globalThis.document.activeElement instanceof globalThis.HTMLTextAreaElement &&
        globalThis.document.activeElement.dataset.threadId === threadId,
      composerThreadId,
    );
    if (!(await composer.evaluate((element) => globalThis.document.activeElement === element))) {
      throw new Error(
        "Closing the PNG history preview with Escape did not restore Composer focus.",
      );
    }

    await page.getByRole("button", { name: "设置", exact: true }).click();
    await page.getByRole("combobox", { name: "界面主题" }).selectOption("dark");
    await page.waitForFunction(() => globalThis.document.documentElement.dataset.theme === "dark");
    await page.keyboard.press("Escape");

    await page.getByRole("button", { name: "打开能力面板", exact: true }).click();
    const panel = page.locator(".workbench-panel");
    await panel.getByRole("tab", { name: "记录", exact: true }).click();
    const record = panel.locator(".record-row").filter({ hasText: prompt });
    const recordSummary = record.locator(":scope > summary");
    await recordSummary.waitFor({ state: "visible" });
    if (!(await record.evaluate((element) => element.open))) await recordSummary.click();
    await record.locator(".policy-fact-row").first().waitFor({ state: "visible" });
    const expectedFactLabels = {
      "document.note.prepared": "准备待审批笔记：企业资料摘要",
      "document.policy.decision": "保存处理结局：执行成功：企业资料摘要",
      "document.note.saved": "已保存笔记：企业资料摘要",
      "document.delivery.completed": "已交付结果：企业资料摘要",
    };
    const requiredFactTypes = Object.keys(expectedFactLabels);
    const policyFacts = durableDiagnostics.policyFacts.body.facts.filter((fact) =>
      requiredFactTypes.includes(fact.type),
    );
    const policyRows = record.locator(".policy-fact-row");
    if (policyFacts.length !== requiredFactTypes.length || (await policyRows.count()) !== 4) {
      throw new Error(
        `The record view did not expose exactly four committed policy facts: ${JSON.stringify({ policyFacts, rows: await policyRows.count() })}`,
      );
    }
    for (let index = 0; index < requiredFactTypes.length; index += 1) {
      const type = requiredFactTypes[index];
      const fact = policyFacts.find((item) => item.type === type);
      const row = policyRows.nth(index);
      const summary = row.locator(":scope > summary");
      const businessLabel = (await summary.locator("span").innerText()).trim();
      const detailLabel = (await summary.locator("small").innerText()).trim();
      if (
        !fact ||
        businessLabel !== expectedFactLabels[type] ||
        detailLabel !== "技术详情" ||
        (await row.evaluate((element) => element.open)) ||
        businessLabel.includes(type) ||
        businessLabel.includes(fact.occurredAt) ||
        businessLabel.includes(fact.turnId)
      ) {
        throw new Error(
          `The collapsed record fact did not show a concise business title: ${JSON.stringify({ type, fact, businessLabel, detailLabel })}`,
        );
      }
      await summary.click();
      const technical = row.locator(":scope > .record-technical");
      await technical.waitFor({ state: "visible" });
      const technicalText = await technical.innerText();
      let displayedPayload;
      try {
        displayedPayload = JSON.parse(await technical.locator("pre").innerText());
      } catch (failure) {
        throw new Error(`The expanded ${type} payload was not readable JSON: ${String(failure)}`);
      }
      if (
        !technicalText.includes(type) ||
        !technicalText.includes(fact.occurredAt) ||
        JSON.stringify(displayedPayload) !== JSON.stringify(fact.payload)
      ) {
        throw new Error(
          `The expanded ${type} fact did not preserve its raw type, time, and payload: ${JSON.stringify({ technicalText, displayedPayload, fact })}`,
        );
      }
      await summary.click();
      if ((await row.evaluate((element) => element.open)) || (await technical.isVisible())) {
        throw new Error(`The ${type} technical details did not hide when its fact row collapsed.`);
      }
    }
    const turnTechnical = record.locator(".record-detail > details.record-technical");
    const turnTechnicalId = turnTechnical.getByText(submissions[0].turnId, { exact: true });
    if (
      (await turnTechnical.evaluate((element) => element.open)) ||
      (await turnTechnicalId.isVisible())
    ) {
      throw new Error("The Turn ID was visible before opening its separate technical details.");
    }
    await turnTechnical.locator("summary").click();
    await turnTechnicalId.waitFor({ state: "visible" });
    if (!(await turnTechnical.innerText()).includes(submissions[0].turnId))
      throw new Error("Opening the Turn technical details did not reveal its exact Turn ID.");
    await turnTechnical.locator("summary").click();
    await turnTechnicalId.waitFor({ state: "hidden" });
    const note = panel.locator(".note-row").filter({ hasText: "企业资料摘要" });
    await note
      .getByText("资料事实：浏览器上传的原始图片。", { exact: true })
      .waitFor({ state: "visible" });
    const source = note.locator(".queue-attachments button").filter({ hasText: fileName });
    const pdfSource = note
      .locator(".queue-attachments button")
      .filter({ hasText: "browser-evidence.pdf" });
    await source.waitFor({ state: "visible" });
    await pdfSource.waitFor({ state: "visible" });
    const policySourcesScreenshot = path.join(
      artifacts,
      "enterprise-document-policy-and-note-sources.png",
    );
    await page.screenshot({ path: policySourcesScreenshot });
    await source.click();
    const notePreview = page.getByRole("dialog", { name: fileName });
    await notePreview.getByRole("img", { name: fileName }).waitFor({ state: "visible" });
    stage = "note-source-preview-close";
    markActiveCancellations(
      activeRequests,
      cancellationReasons,
      stage,
      (pathname) => pathname.includes("/attachments/") && pathname.endsWith("/content"),
      "note source preview closed",
    );
    await notePreview.getByRole("button", { name: "关闭预览" }).click();
    stage = "note-source-preview-open";
    markActiveCancellations(
      activeRequests,
      cancellationReasons,
      stage,
      (pathname) => pathname === pdfContentPath,
      "PDF source thumbnail replaced by its preview",
    );
    const notePdfPreviewResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname === pdfContentPath && url.searchParams.get("download") !== "true";
    });
    await pdfSource.click();
    const notePdfPreview = page.getByRole("dialog", { name: "browser-evidence.pdf" });
    const notePdfFrame = notePdfPreview.locator('iframe[title="预览 browser-evidence.pdf"]');
    await notePdfFrame.waitFor({ state: "visible" });
    const notePdfBlobUrl = await notePdfFrame.getAttribute("src");
    const notePdfBlobType = await page.evaluate(
      (url) => globalThis.__jaAttachmentObjectUrls.created.find((item) => item.url === url)?.type,
      notePdfBlobUrl,
    );
    if (!notePdfBlobUrl?.startsWith("blob:") || notePdfBlobType !== "application/pdf") {
      throw new Error(`The note source PDF preview did not create a PDF Blob: ${notePdfBlobType}`);
    }
    const notePdfPreviewResponse = await notePdfPreviewResponsePromise;
    const notePdfPreviewBytes = await notePdfPreviewResponse.body();
    const notePdfPreviewType = notePdfPreviewResponse.headers()["content-type"]?.split(";")[0];
    const notePdfPreviewExactBytes = notePdfPreviewBytes.equals(pdfBytes);
    if (
      notePdfPreviewType !== "application/pdf" ||
      !notePdfPreviewBytes.subarray(0, 8).equals(Buffer.from("%PDF-1.4")) ||
      !notePdfPreviewExactBytes
    ) {
      throw new Error("The saved note PDF source preview did not retain its validated PDF bytes.");
    }
    const notePdfPreviewPaint = await captureStablePdfPreview(
      page,
      notePdfPreview,
      notePdfFrame,
      notePdfBlobUrl,
      path.join(artifacts, "enterprise-document-note-source-pdf-preview.png"),
    );
    verifiedPdfPreviews.set(notePdfBlobUrl, {
      requestStage: "note-source-preview-open",
      blobType: notePdfBlobType,
      contentType: notePdfPreviewType,
      exactBytes: notePdfPreviewExactBytes,
      frameReady: notePdfPreviewPaint.documentReadyState === "complete",
      screenshotPath: notePdfPreviewPaint.screenshotPath,
      closedAndRevoked: false,
    });
    stage = "note-source-preview-close";
    markActiveCancellations(
      activeRequests,
      cancellationReasons,
      stage,
      (pathname) => pathname.includes("/attachments/") && pathname.endsWith("/content"),
      "note PDF source preview closed",
    );
    await notePdfPreview.getByRole("button", { name: "关闭预览" }).click();
    await page.waitForFunction(
      (url) => globalThis.__jaAttachmentObjectUrls.revoked.includes(url),
      notePdfBlobUrl,
    );
    verifiedPdfPreviews.get(notePdfBlobUrl).closedAndRevoked = true;

    await panel.getByRole("button", { name: "关闭能力面板" }).click();
    await panel.waitFor({ state: "hidden" });
    await page.screenshot({ path: path.join(artifacts, "enterprise-document-desktop-dark.png") });
    await page.setViewportSize({ width: 1280, height: 520 });
    const scrollCheck = await page.locator(".conversation-scroll").evaluate((element) => {
      const scroll = element;
      const maxScroll = scroll.scrollHeight - scroll.clientHeight;
      scroll.scrollTop = 0;
      const top = scroll.scrollTop;
      scroll.scrollTop = scroll.scrollHeight;
      const bottom = scroll.scrollTop;
      const composerRect = globalThis.document.querySelector(".composer")?.getBoundingClientRect();
      return {
        maxScroll,
        top,
        bottom,
        composerBottom: composerRect?.bottom ?? null,
        viewportHeight: globalThis.window.innerHeight,
      };
    });
    if (scrollCheck.maxScroll > 0 && scrollCheck.bottom <= scrollCheck.top) {
      throw new Error("The conversation viewport did not scroll through its content.");
    }
    if (
      scrollCheck.composerBottom === null ||
      scrollCheck.composerBottom > scrollCheck.viewportHeight
    ) {
      throw new Error("The composer is not reachable while the conversation scrolls.");
    }
    await page.setViewportSize({ width: 360, height: 800 });
    const sidebar = page.locator(".sidebar");
    if (!(await sidebar.evaluate((element) => element.classList.contains("sidebar-open")))) {
      await page.getByRole("button", { name: "打开会话栏" }).click();
    }
    await page.waitForFunction(() => {
      const element = globalThis.document.querySelector(".sidebar");
      const bounds = element?.getBoundingClientRect();
      const transform = element ? globalThis.getComputedStyle(element).transform : "none";
      const identityTransform =
        transform === "none" || new globalThis.DOMMatrixReadOnly(transform).isIdentity;
      return (
        element?.classList.contains("sidebar-open") &&
        globalThis.getComputedStyle(element).visibility === "visible" &&
        bounds !== undefined &&
        identityTransform
      );
    });
    const mobileSidebarOpen = await sidebar.evaluate(async (element) => {
      const bounds = () => element.getBoundingClientRect().toJSON();
      const previousBounds = bounds();
      await new Promise((resolve) =>
        globalThis.requestAnimationFrame(() => globalThis.requestAnimationFrame(resolve)),
      );
      return {
        previousBounds,
        bounds: bounds(),
        transform: globalThis.getComputedStyle(element).transform,
        visibility: globalThis.getComputedStyle(element).visibility,
      };
    });
    const boundsAreStable = ["left", "right", "width", "top", "bottom", "height"].every(
      (key) =>
        Math.abs(mobileSidebarOpen.bounds[key] - mobileSidebarOpen.previousBounds[key]) <= 0.5,
    );
    const boundsFitViewport = (bounds) => bounds.left >= -0.5 && bounds.right <= 360.5;
    if (
      !boundsAreStable ||
      !boundsFitViewport(mobileSidebarOpen.previousBounds) ||
      !boundsFitViewport(mobileSidebarOpen.bounds)
    ) {
      throw new Error(
        `The open mobile sidebar did not reach its final viewport bounds: ${JSON.stringify(mobileSidebarOpen)}`,
      );
    }
    await page.screenshot({
      path: path.join(artifacts, "enterprise-document-mobile-drawer-open-dark.png"),
    });
    await sidebar.getByRole("button", { name: "关闭会话栏" }).click();
    await page.waitForFunction(() => {
      const element = globalThis.document.querySelector(".sidebar");
      const bounds = element?.getBoundingClientRect();
      return (
        element !== null &&
        !element.classList.contains("sidebar-open") &&
        globalThis.getComputedStyle(element).visibility === "hidden" &&
        bounds !== undefined &&
        bounds.right <= 0.5 &&
        globalThis.document.querySelector(".sidebar-scrim") === null
      );
    });
    const mobileSidebarClosed = await sidebar.evaluate((element) => ({
      bounds: element.getBoundingClientRect().toJSON(),
      transform: globalThis.getComputedStyle(element).transform,
      visibility: globalThis.getComputedStyle(element).visibility,
    }));
    const narrowLayout = await page.evaluate(() => ({
      viewport: globalThis.window.innerWidth,
      document: globalThis.document.documentElement.scrollWidth,
      composer: globalThis.document.querySelector(".composer")?.getBoundingClientRect().toJSON(),
      theme: globalThis.document.documentElement.dataset.theme,
    }));
    if (narrowLayout.document > narrowLayout.viewport)
      throw new Error("The narrow workbench has horizontal overflow.");
    if (
      !narrowLayout.composer ||
      narrowLayout.composer.left < 0 ||
      narrowLayout.composer.right > narrowLayout.viewport
    ) {
      throw new Error("The composer is not fully reachable at a narrow viewport.");
    }
    await page.screenshot({
      path: path.join(artifacts, "enterprise-document-mobile-closed-dark.png"),
    });

    if (!pdfBlobAbortsClassified) {
      classifyVerifiedPdfPreviewAborts(
        pendingPdfBlobAborts,
        verifiedPdfPreviews,
        abortedRequests,
        failedRequests,
      );
      pdfBlobAbortsClassified = true;
    }

    if (
      pageErrors.length ||
      consoleErrors.length ||
      failedRequests.length ||
      failedResponses.length
    ) {
      throw new Error(
        JSON.stringify({
          pageErrors,
          consoleErrors,
          failedRequests,
          abortedRequests,
          failedResponses,
        }),
      );
    }
    if (
      injectedUploadFailures.length !== 1 ||
      lostResponseRetryEvidence?.retryCount !== 1 ||
      lostResponseRetryEvidence.retryAttachmentId !== lostResponseRetryEvidence.firstAttachmentId ||
      expectedUploadLossConsoleErrors.length !== 1
    ) {
      throw new Error(
        `The expected lost-response recovery was not completed exactly once: ${JSON.stringify({ injectedUploadFailures, lostResponseRetryEvidence })}`,
      );
    }
    if (!attachmentInteractionEvidence.pdfPreview.returnedToComposer) {
      throw new Error("Closing PDF preview did not return focus to the message composer.");
    }
    const report = {
      backend,
      title: "企业资料摘要",
      observationTiming: {
        runnerClock: "node performance.now relative to runner start",
        events: phaseTimings,
        diagnosticQueryCount: Object.keys(durableDiagnostics).length,
        sseAdmissionSignal: "unknown; SSE response content is intentionally not consumed",
        waitSignal: "unknown; SSE response content is intentionally not consumed",
        approvalForm: await readApprovalFormTiming(page),
      },
      faviconResponses,
      submittedAttachmentCount: submissions[0].content.attachmentIds.length,
      attachmentInteractionEvidence,
      lostResponseRetryEvidence,
      injectedUploadFailures,
      expectedUploadLossConsoleErrors,
      focusAuditEvidence,
      durableWorkflow,
      policyFacts: [
        "document.note.prepared",
        "document.policy.decision",
        "document.note.saved",
        "document.delivery.completed",
      ],
      scrollCheck,
      narrowLayout,
      mobileSidebar: { open: mobileSidebarOpen, closed: mobileSidebarClosed },
      abortedRequests,
      unexpectedErrors: {
        page: pageErrors,
        console: consoleErrors,
        requests: failedRequests,
        responses: failedResponses,
      },
      screenshots: [
        entryScreenshot,
        uploadScreenshot,
        approvalScreenshot,
        policySourcesScreenshot,
        path.join(artifacts, "enterprise-document-pdf-focus-boundary.png"),
        path.join(artifacts, "enterprise-document-png-focus-boundary.png"),
        ...[...verifiedPdfPreviews.values()].map((preview) => preview.screenshotPath),
        path.join(artifacts, "enterprise-document-desktop-dark.png"),
        path.join(artifacts, "enterprise-document-mobile-drawer-open-dark.png"),
        path.join(artifacts, "enterprise-document-mobile-closed-dark.png"),
      ],
    };
    const reportPath = path.join(artifacts, "report.json");
    await writeFile(reportPath, JSON.stringify(report, null, 2), "utf8");
    process.stdout.write(`SPRING_DOCUMENT_BROWSER_OK ${reportPath}\n`);
  } catch (failure) {
    const failureCapture = {
      startedAtElapsedMs: Math.round((performance.now() - timingOrigin) * 10) / 10,
      domSummary: null,
      domCaptureStatus: "unavailable",
      screenshotStatus: "unavailable",
    };
    recordTiming("failure_capture", "started");
    if (page) {
      try {
        failureCapture.domSummary = await captureFailureDomSummary(page);
        failureCapture.domCaptureStatus = "captured";
        recordTiming("failure_capture", "dom_captured");
      } catch {
        recordTiming("failure_capture", "dom_unavailable");
      }
      try {
        await page.screenshot({ path: path.join(artifacts, "failure.png"), fullPage: true });
        failureCapture.screenshotStatus = "captured";
        recordTiming("failure_capture", "screenshot_captured");
      } catch {
        recordTiming("failure_capture", "screenshot_unavailable");
      }
    }
    failureCapture.completedAtElapsedMs = Math.round((performance.now() - timingOrigin) * 10) / 10;
    recordTiming("failure_capture", "completed");
    if (!pdfBlobAbortsClassified) {
      classifyVerifiedPdfPreviewAborts(
        pendingPdfBlobAborts,
        verifiedPdfPreviews,
        abortedRequests,
        failedRequests,
      );
      pdfBlobAbortsClassified = true;
    }
    recordTiming("backend_diagnostics", "started");
    const backendDiagnostics = await readBackendDiagnostics(backend, submissions[0], recordTiming);
    recordTiming("backend_diagnostics", "completed");
    let approvalFormTiming = { status: "unknown", firstVisibleAfterSubmitMs: null };
    if (page) {
      try {
        approvalFormTiming = await readApprovalFormTiming(page);
        recordTiming("approval_form", "visibility_observation_read");
      } catch {
        approvalFormTiming = { status: "unavailable", firstVisibleAfterSubmitMs: null };
      }
    }
    const reportPath = path.join(artifacts, "failure.json");
    await writeFile(
      reportPath,
      JSON.stringify(
        {
          error: failure instanceof Error ? failure.message : String(failure),
          pageErrors,
          consoleErrors,
          failedRequests,
          abortedRequests,
          failedResponses,
          attachmentInteractionEvidence,
          focusAuditEvidence,
          lostResponseRetryEvidence,
          injectedUploadFailures,
          expectedUploadLossConsoleErrors,
          observationTiming: {
            runnerClock: "node performance.now relative to runner start",
            events: phaseTimings,
            diagnosticQueryCount: Object.keys(backendDiagnostics).length,
            sseAdmissionSignal: "unknown; SSE response content is intentionally not consumed",
            waitSignal: "unknown; SSE response content is intentionally not consumed",
            approvalForm: approvalFormTiming,
            failureCapture,
          },
          backendDiagnostics,
          artifacts,
        },
        null,
        2,
      ),
      "utf8",
    );
    process.stderr.write(`SPRING_DOCUMENT_BROWSER_FAIL ${reportPath}\n`);
    throw failure;
  } finally {
    if (browser) await browser.close();
    await vite.close();
  }
}

main().catch((failure) => {
  process.stderr.write(`${failure instanceof Error ? failure.stack : String(failure)}\n`);
  process.exitCode = 1;
});
