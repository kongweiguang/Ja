// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Interaction/Plan 真窗 driver。
 *
 * 生产 desktop runner 负责启动 Native Image sidecar、隔离 profile 和确定性 Provider，
 * 本文件只通过 WebView2 CDP 页面与 typed Tauri invoke 观察真实行为。所有外部状态
 * 通过 callback 注入，避免 driver 复制 Java 权威状态机或偷偷使用 fixture 代替真窗。
 */

import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import {
  INTERACTION_PLAN_RPC_CONTRACT,
  INTERACTION_PLAN_UI_CONTRACT,
} from "./interaction-plan-production.mjs";
import { captureInteractionPlanAccessibility } from "./interaction-plan-accessibility.mjs";

/** 将浏览器或回归 fixture 的有限矩形规范化，拒绝 NaN/反向边界污染碰撞证据。 */
function normalizeRect(rect) {
  if (
    rect === null ||
    typeof rect !== "object" ||
    !Number.isFinite(rect.left) ||
    !Number.isFinite(rect.top) ||
    !Number.isFinite(rect.right) ||
    !Number.isFinite(rect.bottom) ||
    rect.right <= rect.left ||
    rect.bottom <= rect.top
  ) {
    return null;
  }
  return {
    left: rect.left,
    top: rect.top,
    right: rect.right,
    bottom: rect.bottom,
    width: rect.right - rect.left,
    height: rect.bottom - rect.top,
  };
}

/** 读取 PNG IHDR 的物理像素尺寸，确保截图证据没有被 CSS viewport 二次缩小。 */
function readPngDimensions(png) {
  if (
    !Buffer.isBuffer(png) ||
    png.length < 24 ||
    png.readUInt32BE(0) !== 0x89504e47 ||
    png.readUInt32BE(4) !== 0x0d0a1a0a
  ) {
    throw new Error("CDP 截图不是合法 PNG");
  }
  return {
    width: png.readUInt32BE(16),
    height: png.readUInt32BE(20),
  };
}

/** 返回两个矩形的真实绘制交集；边界接触不算控件重叠。 */
function intersectRects(left, right) {
  const a = normalizeRect(left);
  const b = normalizeRect(right);
  if (a === null || b === null) return null;
  const intersection = {
    left: Math.max(a.left, b.left),
    top: Math.max(a.top, b.top),
    right: Math.min(a.right, b.right),
    bottom: Math.min(a.bottom, b.bottom),
  };
  return normalizeRect(intersection);
}

/**
 * 将 raw 控件矩形裁剪到 viewport 与所有 overflow clipping ancestor 的实际交集。
 * 该函数不依赖浏览器，既供真窗 driver 使用，也供 geometry 回归验证滚动裁剪与真实
 * 重叠的边界；调用方提供的 clipping rect 已按未裁剪轴扩展到 viewport 边界。
 */
export function computePaintedRect(control, viewport) {
  let painted = intersectRects(control?.rect, viewport);
  for (const clipping of control?.clipping ?? []) {
    painted = intersectRects(painted, clipping?.rect);
    if (painted === null) return null;
  }
  return painted;
}

/**
 * 计算可绘制控件和同一 surface 内的真实重叠。包含关系由页面采集阶段标注并跳过，
 * 因为 label/input 等语义嵌套控件不是布局冲突；只有超过 1px 的共同绘制面积才失败。
 */
export function computeVisibleControlOverlaps(controls, viewport) {
  const visibleControls = (Array.isArray(controls) ? controls : []).flatMap((control) => {
    if (control?.hidden === true) return [];
    const paintedRect = computePaintedRect(control, viewport);
    return paintedRect === null ? [] : [{ ...control, paintedRect }];
  });
  const overlaps = [];
  for (let index = 0; index < visibleControls.length; index += 1) {
    const left = visibleControls[index];
    for (const right of visibleControls.slice(index + 1)) {
      if (left.surfaceIndex !== right.surfaceIndex) continue;
      if (
        left.containsControlIds?.includes(right.controlId) === true ||
        right.containsControlIds?.includes(left.controlId) === true
      ) {
        continue;
      }
      const intersection = intersectRects(left.paintedRect, right.paintedRect);
      if (intersection === null || intersection.width <= 1 || intersection.height <= 1) continue;
      overlaps.push({
        left: {
          controlId: left.controlId,
          name: left.name,
          rect: left.rect,
          paintedRect: left.paintedRect,
          clipping: left.clipping,
        },
        right: {
          controlId: right.controlId,
          name: right.name,
          rect: right.rect,
          paintedRect: right.paintedRect,
          clipping: right.clipping,
        },
        intersection,
      });
    }
  }
  return { visibleControls, overlaps };
}

/** 在真实 WebView2 状态尚未收敛时轮询；取消与期限都由主 runner 统一控制。 */
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
      let timer;
      const onAbort = () => finish(signal.reason ?? new Error(`${label} aborted`), true);
      const finish = (error, rejected = false) => {
        clearTimeout(timer);
        signal?.removeEventListener("abort", onAbort);
        if (rejected) rejectPromise(error);
        else resolvePromise();
      };
      timer = setTimeout(finish, Math.min(intervalMs, Math.max(1, deadline - Date.now())));
      signal?.addEventListener("abort", onAbort, { once: true });
    });
  }
  throw new Error(`${label} 未在期限内满足`, { cause: lastError });
}

/** 只把稳定错误 code 带回验收 runner，避免报告泄漏 Provider/路径/secret。 */
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
          (value) => typeof value === "string" && /^[A-Z][A-Z0-9_]{1,63}$/u.test(value),
        );
        return { ok: false, code: code ?? "COMMAND_FAILED" };
      }
    },
    { commandName: command, commandInput: input },
  );
}

/** 将生产 runner 的真实接线设为硬依赖，防止缺 callback 时退化成静态 DOM 通过。 */
function requiredCallback(callback, name) {
  if (typeof callback !== "function") throw new TypeError(`Interaction/Plan driver 缺少 ${name}`);
  return callback;
}

/**
 * 创建完整报告 driver。callback 必须由真实 desktop runner 实现；driver 不接受
 * `fixtureOnly` 或 `sourceOnly` 选项，防止静态/模拟结果冒充生产验收。
 */
export function createInteractionPlanWebView2Driver(options) {
  const {
    page,
    deadline,
    signal,
    nativeSidecar,
    screenshotDirectory,
    prepareVisualPreferences,
    assertVisibleState,
    exerciseInteractionScenario,
    attemptReadonly,
    runExecutionScenario,
    runRecoveryScenario,
    runIsolationScenario,
    runGoalRegressionScenario,
    runSoakScenario,
    recordStage = () => {},
  } = options;
  if (page === undefined) throw new TypeError("Interaction/Plan driver requires a WebView2 page");
  const root = page.locator(INTERACTION_PLAN_UI_CONTRACT.questionRoot);

  async function runtimeEvidence() {
    const surface = await page.evaluate(() => ({
      tauri: typeof globalThis.__TAURI_INTERNALS__?.invoke === "function",
      protocol: globalThis.location.protocol,
    }));
    assert.equal(surface.tauri, true);
    assert.match(surface.protocol, /^(?:https?:|tauri:)/u);
    assert.deepEqual(nativeSidecar?.used, true);
    assert.deepEqual(nativeSidecar?.identityMatched, true);
    assert.deepEqual(nativeSidecar?.noFallback, true);
    return {
      surface: "tauri_webview2",
      nativeWindow: true,
      deterministicMockProvider: true,
      realProviderRequests: 0,
      nativeSidecar,
    };
  }

  /** 通过真实卡片完成单选、多选、自填、跳过和收起，验证答案提交不依赖普通文本。 */
  async function interactionEvidence() {
    recordStage("interaction_plan:questions");
    const evidence = await requiredCallback(
      exerciseInteractionScenario,
      "exerciseInteractionScenario",
    )({
      page,
      root,
      invoke: (command, input) => invokeCommand(page, command, input),
      commands: INTERACTION_PLAN_RPC_CONTRACT.commands,
    });
    assert.deepEqual(
      [...new Set(evidence?.questionKinds ?? [])].sort(),
      [...INTERACTION_PLAN_UI_CONTRACT.requiredQuestionKinds].sort(),
    );
    return evidence;
  }

  async function readonlyEvidence() {
    recordStage("interaction_plan:readonly");
    return requiredCallback(attemptReadonly, "attemptReadonly")();
  }

  async function executionEvidence() {
    recordStage("interaction_plan:execute");
    return requiredCallback(
      runExecutionScenario,
      "runExecutionScenario",
    )({
      invoke: (command, input) => invokeCommand(page, command, input),
      commands: INTERACTION_PLAN_RPC_CONTRACT.commands,
    });
  }

  async function recoveryEvidence() {
    recordStage("interaction_plan:recovery");
    return requiredCallback(
      runRecoveryScenario,
      "runRecoveryScenario",
    )({
      invoke: (command, input) => invokeCommand(page, command, input),
      commands: INTERACTION_PLAN_RPC_CONTRACT.commands,
    });
  }

  async function isolationEvidence() {
    recordStage("interaction_plan:isolation");
    return requiredCallback(runIsolationScenario, "runIsolationScenario")();
  }

  /**
   * 采集真实问题卡与 Plan surface 的视觉矩阵。缩放和外观必须由生产 runner 经真实
   * Settings/WebView2 接口设置；driver 不修改 CDP viewport，避免把固定 CSS 宽度或
   * deviceScaleFactor 当成浏览器缩放。每一帧都检查真实 DOM、可访问名称、几何碰撞和
   * 溢出，空画面直接失败。
   */
  async function visualEvidence() {
    recordStage("interaction_plan:visual");
    const prepare = requiredCallback(prepareVisualPreferences, "prepareVisualPreferences");
    const assertState = requiredCallback(assertVisibleState, "assertVisibleState");
    await mkdir(screenshotDirectory, { recursive: true });
    const frames = [];
    const cdpSession = await page.context().newCDPSession(page);
    const visualStepWindowMs = 20_000;

    /** 视觉矩阵每个准备/交互步骤独立限时，避免长稳总期限掩盖单步卡死。 */
    const visualDeadline = () => Math.min(deadline, Date.now() + visualStepWindowMs);

    /** 通过 WebView2 CDP surface 获取原始物理像素，避免 Playwright 传入 CSS clip 或缩放截图。 */
    async function captureRawScreenshot(path) {
      const result = await cdpSession.send("Page.captureScreenshot", {
        format: "png",
        fromSurface: true,
        captureBeyondViewport: false,
      });
      if (typeof result?.data !== "string" || result.data.length === 0) {
        throw new Error("CDP Page.captureScreenshot 未返回 PNG 数据");
      }
      const png = Buffer.from(result.data, "base64");
      const dimensions = readPngDimensions(png);
      await writeFile(path, png);
      return { ...dimensions, byteLength: png.length };
    }

    /** 让真实 Timeline 物化尾部行；计划位于长历史尾部时不能用顶部 DOM 判断缺失。 */
    async function scrollTimelineToBottom({
      required = true,
      stepDeadline = visualDeadline(),
    } = {}) {
      const scrollport = page.locator(".ja-chat-timeline__scroll").first();
      if (!required) {
        if (
          (await scrollport.count()) === 0 ||
          !(await scrollport.isVisible().catch(() => false))
        ) {
          return { status: "not_visible" };
        }
      }
      await scrollport.waitFor({
        state: "visible",
        timeout: Math.max(1, stepDeadline - Date.now()),
      });
      return scrollport.evaluate((element) => {
        const maximum = Math.max(0, element.scrollHeight - element.clientHeight);
        const before = element.scrollTop;
        element.scrollTo({ top: maximum, behavior: "auto" });
        return {
          before,
          requested: maximum,
          after: element.scrollTop,
          clientHeight: element.clientHeight,
          scrollHeight: element.scrollHeight,
        };
      });
    }

    /**
     * 读取计划目标与主动作的真实绘制边界；Composer dock 会缩短 Timeline 的可读底边，
     * 所以不能只用 plan locator 的 isVisible 或 card 的任意露边作为成功条件。
     */
    async function readPlanAnchorState() {
      return page.evaluate(() => {
        const scrollport = globalThis.document.querySelector(".ja-chat-timeline__scroll");
        const plan = globalThis.document.querySelector(".ja-plan-timeline");
        const scrollRect = scrollport?.getBoundingClientRect() ?? null;
        const composerRect = globalThis.document.querySelector(".ja-composer")?.getBoundingClientRect() ?? null;
        const rectData = (rect) => rect === null
          ? null
          : {
              left: rect.left,
              top: rect.top,
              right: rect.right,
              bottom: rect.bottom,
              width: rect.width,
              height: rect.height,
            };
        if (scrollport === null || scrollRect === null || plan === null) {
          return {
            planCount: 0,
            objectiveVisible: false,
            actionVisible: false,
            objectiveRect: null,
            actionRect: null,
            scrollTop: scrollport?.scrollTop ?? 0,
            maxScrollTop: scrollport === null
              ? 0
              : Math.max(0, scrollport.scrollHeight - scrollport.clientHeight),
          };
        }
        const composerOverlapsHorizontally = composerRect !== null &&
          composerRect.right > scrollRect.left && composerRect.left < scrollRect.right;
        const usableBottom = composerOverlapsHorizontally
          ? Math.min(scrollRect.bottom, composerRect.top - 1)
          : scrollRect.bottom;
        const anchorData = (element) => {
          if (!(element instanceof globalThis.HTMLElement)) {
            return { visible: false, rect: null };
          }
          const rect = element.getBoundingClientRect();
          return {
            visible: rect.width > 0 && rect.height > 0 &&
              rect.left >= scrollRect.left - 1 && rect.right <= scrollRect.right + 1 &&
              rect.top >= scrollRect.top - 1 && rect.bottom <= usableBottom + 1,
            rect: rectData(rect),
          };
        };
        const objective = anchorData(plan.querySelector(":scope > h3"));
        const action = anchorData(plan.querySelector(".ja-plan-timeline__actions button.is-primary"));
        return {
          planCount: 1,
          objectiveVisible: objective.visible,
          actionVisible: action.visible,
          objectiveRect: objective.rect,
          actionRect: action.rect,
          planRect: rectData(plan.getBoundingClientRect()),
          scrollTop: scrollport.scrollTop,
          maxScrollTop: Math.max(0, scrollport.scrollHeight - scrollport.clientHeight),
        };
      });
    }

    /** 让虚拟 Timeline 完成一轮 materialize，避免在 React commit 前读取旧的 plan DOM。 */
    async function waitForTimelineRender() {
      await page.evaluate(() => new Promise((resolve) => {
        globalThis.requestAnimationFrame(() => globalThis.requestAnimationFrame(resolve));
      }));
    }

    /**
     * 从 Timeline 顶部按真实滚动距离寻找计划行；计划不一定在 live tail，不能把尾部
     * 的普通问题/Tool 行当成计划证据，也不能改写 virtualizer 的 DOM 或样式。
     */
    async function revealPlanSurface(stepDeadline) {
      const scrollport = page.locator(".ja-chat-timeline__scroll").first();
      await scrollport.waitFor({ state: "visible", timeout: Math.max(1, stepDeadline - Date.now()) });
      await scrollport.evaluate((element) => element.scrollTo({ top: 0, behavior: "auto" }));
      await waitForTimelineRender();
      let state = await readPlanAnchorState();
      while (Date.now() < stepDeadline) {
        if (state.planCount > 0) return state;
        const movement = await scrollport.evaluate((element) => {
          const maximum = Math.max(0, element.scrollHeight - element.clientHeight);
          const before = element.scrollTop;
          const increment = Math.max(1, Math.floor(element.clientHeight * 0.75));
          const after = Math.min(maximum, before + increment);
          element.scrollTo({ top: after, behavior: "auto" });
          return { before, after, maximum };
        });
        await waitForTimelineRender();
        state = await readPlanAnchorState();
        if (state.planCount > 0) return state;
        if (movement.after <= movement.before || movement.after >= movement.maximum) break;
      }
      throw new Error(`视觉矩阵无法在真实 Timeline 中定位 Plan：${JSON.stringify(state)}`);
    }

    /** 将计划目标或执行按钮滚动到可完整阅读的真实窗口区域，并返回边界证据。 */
    async function revealPlanAnchor(anchor, stepDeadline) {
      const found = await page.evaluate((anchorName) => {
        const plan = globalThis.document.querySelector(".ja-plan-timeline");
        const target = anchorName === "objective"
          ? plan?.querySelector(":scope > h3")
          : plan?.querySelector(".ja-plan-timeline__actions button.is-primary");
        if (!(target instanceof globalThis.HTMLElement)) return false;
        target.scrollIntoView({ block: "center", inline: "nearest", behavior: "auto" });
        return true;
      }, anchor);
      if (found !== true) throw new Error(`视觉矩阵 Plan 缺少 ${anchor} anchor`);
      const visibleState = await waitForCondition(
        `Plan ${anchor} anchor 可见`,
        async () => {
          const state = await readPlanAnchorState();
          return state[anchor === "objective" ? "objectiveVisible" : "actionVisible"] === true
            ? state
            : false;
        },
        stepDeadline,
        signal,
      );
      return visibleState;
    }

    /** 通过真实无障碍按钮切换 surface，保留问题草稿并为计划帧释放可用高度。 */
    async function collapseQuestionAndRevealPlan() {
      const stepDeadline = visualDeadline();
      const expanded = page
        .locator('.ja-interaction-card[data-interaction-status="pending"]')
        .first();
      await expanded.getByRole("button", { name: "收起问题", exact: true }).click({
        timeout: Math.max(1, stepDeadline - Date.now()),
      });
      const collapsed = page
        .locator('.ja-interaction-card[data-interaction-status="pending-collapsed"]')
        .first();
      await collapsed.waitFor({
        state: "visible",
        timeout: Math.max(1, stepDeadline - Date.now()),
      });
      await revealPlanSurface(stepDeadline);
      await revealPlanAnchor("objective", stepDeadline);
    }

    /** 计划帧完成后恢复展开问题，确保下一个主题/缩放组合仍从同一真实入口开始。 */
    async function expandQuestionAfterPlan() {
      const stepDeadline = visualDeadline();
      const collapsed = page
        .locator('.ja-interaction-card[data-interaction-status="pending-collapsed"]')
        .first();
      if (await collapsed.isVisible().catch(() => false)) {
        await collapsed.getByRole("button", { name: "展开问题", exact: true }).click({
          timeout: Math.max(1, stepDeadline - Date.now()),
        });
      }
      await page
        .locator('.ja-interaction-card[data-interaction-status="pending"]')
        .first()
        .waitFor({
          state: "visible",
          timeout: Math.max(1, stepDeadline - Date.now()),
        });
    }

    /** 从真实 DOM 采集单个 surface 帧，surfaceMode 区分问题与折叠后的计划验收。 */
    async function captureSurfaceFrame({
      width,
      nativeWidth,
      nativeHeight,
      cssViewportWidth,
      zoom,
      theme,
      systemColorScheme,
      reducedMotion,
      reducedTransparency,
      highContrast,
      longText,
      surfaceMode,
      surfacePhase,
    }) {
      const capture = await page.evaluate(
        ({
          width: expectedWidth,
          nativeWidth: expectedNativeWidth,
          nativeHeight: expectedNativeHeight,
          cssViewportWidth: expectedCssViewportWidth,
          zoom: expectedZoom,
          theme: expectedTheme,
          systemColorScheme: expectedSystemColorScheme,
          reducedMotion: expectedReducedMotion,
          reducedTransparency: expectedReducedTransparency,
          highContrast: expectedHighContrast,
          longText: expectedLongText,
          longTextThreshold,
          surfaceMode: expectedSurfaceMode,
          surfacePhase: expectedSurfacePhase,
        }) => {
          const expandedQuestionSelector =
            '.ja-interaction-card[data-interaction-status="pending"]';
          const answeredQuestionSelector =
            '.ja-interaction-card[data-interaction-status="answered"]';
          const questionSelector = `${expandedQuestionSelector}, ${answeredQuestionSelector}`;
          const surfaceSelector =
            expectedSurfaceMode === "question"
              ? `${questionSelector}, .ja-plan-timeline, .ja-plan-workbench`
              : ".ja-plan-timeline, .ja-plan-workbench";
          const controlSelector =
            "button, input, textarea, select, [role='button'], [role='checkbox'], [role='radio'], [role='combobox']";
          const viewport = {
            left: 0,
            top: 0,
            right: globalThis.innerWidth,
            bottom: globalThis.innerHeight,
          };
          const devicePixelRatio = globalThis.devicePixelRatio;
          const rectData = (rect) => ({
            left: rect.left,
            top: rect.top,
            right: rect.right,
            bottom: rect.bottom,
            width: rect.width,
            height: rect.height,
          });
          const accessibleName = (control) => {
            const labelledBy = (control.getAttribute("aria-labelledby") ?? "")
              .split(/\s+/u)
              .filter(Boolean)
              .map((id) => globalThis.document.getElementById(id)?.textContent?.trim() ?? "")
              .join(" ")
              .trim();
            const labels =
              "labels" in control && control.labels !== null
                ? [...control.labels]
                    .map((label) => label.textContent?.trim() ?? "")
                    .join(" ")
                    .trim()
                : "";
            return (
              control.getAttribute("aria-label")?.trim() ||
              labelledBy ||
              labels ||
              control.getAttribute("title")?.trim() ||
              control.textContent?.trim() ||
              ""
            );
          };
          const clippingOverflow = new Set(["auto", "scroll", "hidden", "clip", "overlay"]);
          const describeElement = (element) => {
            const clipping = [];
            const hiddenAncestors = [];
            let current = element;
            while (current instanceof globalThis.HTMLElement) {
              const style = globalThis.getComputedStyle(current);
              if (
                style.display === "none" ||
                style.visibility === "hidden" ||
                style.visibility === "collapse" ||
                style.opacity === "0"
              ) {
                hiddenAncestors.push({
                  tagName: current.tagName.toLowerCase(),
                  className:
                    typeof current.className === "string" ? current.className.slice(0, 160) : "",
                  display: style.display,
                  visibility: style.visibility,
                  opacity: style.opacity,
                });
              }
              if (current !== element) {
                const clipsX = clippingOverflow.has(style.overflowX);
                const clipsY = clippingOverflow.has(style.overflowY);
                if (clipsX || clipsY) {
                  const ancestorRect = current.getBoundingClientRect();
                  clipping.push({
                    source: {
                      tagName: current.tagName.toLowerCase(),
                      className:
                        typeof current.className === "string"
                          ? current.className.slice(0, 160)
                          : "",
                    },
                    rect: {
                      left: clipsX ? ancestorRect.left : viewport.left,
                      top: clipsY ? ancestorRect.top : viewport.top,
                      right: clipsX ? ancestorRect.right : viewport.right,
                      bottom: clipsY ? ancestorRect.bottom : viewport.bottom,
                    },
                    overflowX: style.overflowX,
                    overflowY: style.overflowY,
                  });
                }
              }
              current = current.parentElement;
            }
            return {
              rect: rectData(element.getBoundingClientRect()),
              clipping,
              hidden: hiddenAncestors.length > 0,
              hiddenAncestors,
            };
          };
          const surfaceElements = [...globalThis.document.querySelectorAll(surfaceSelector)];
          const surfaces = surfaceElements.map((surface, surfaceIndex) => {
            const isQuestion = surface.matches(questionSelector);
            const objective = isQuestion ? null : surface.querySelector(":scope > h3, :scope > h2");
            const primaryAction = isQuestion
              ? null
              : surface.querySelector(
                  ".ja-plan-timeline__actions button.is-primary, button.is-primary",
                );
            return {
              surfaceIndex,
              kind: isQuestion ? "question" : "plan",
              geometry: describeElement(surface),
              scrollWidth: surface.scrollWidth,
              clientWidth: surface.clientWidth,
              questionText: isQuestion
                ? [
                    ...surface.querySelectorAll(
                      ".ja-interaction-card__body h3, .ja-interaction-card__summary-list li span",
                    ),
                  ]
                    .map((node) => node.textContent?.trim() ?? "")
                    .join(" ")
                    .trim()
                : "",
              anchors: isQuestion
                ? null
                : {
                    objective: objective === null ? null : describeElement(objective),
                    primaryAction: primaryAction === null ? null : describeElement(primaryAction),
                  },
            };
          });
          const controlElements = [];
          for (const surface of surfaceElements) {
            for (const element of surface.querySelectorAll(controlSelector)) {
              if (
                element instanceof globalThis.HTMLElement &&
                !controlElements.some((candidate) => candidate === element)
              ) {
                controlElements.push(element);
              }
            }
          }
          const controls = controlElements.map((element, controlId) => {
            const closestSurface = element.closest(surfaceSelector);
            const surfaceIndex = surfaceElements.indexOf(closestSurface);
            const geometry = describeElement(element);
            return {
              controlId,
              surfaceIndex,
              name: accessibleName(element),
              rect: geometry.rect,
              clipping: geometry.clipping,
              hidden: geometry.hidden,
              hiddenAncestors: geometry.hiddenAncestors,
              isPrimary: element.classList.contains("is-primary"),
              containsControlIds: controlElements
                .map((candidate, candidateId) =>
                  candidate !== element && element.contains(candidate) ? candidateId : null,
                )
                .filter((candidateId) => candidateId !== null),
            };
          });
          const root = globalThis.document.documentElement;
          const composer = globalThis.document.querySelector(".ja-composer");
          const allQuestionText = [
            ...globalThis.document.querySelectorAll(
              `:is(${questionSelector}) .ja-interaction-card__body h3, :is(${questionSelector}) .ja-interaction-card__summary-list li span`,
            ),
          ]
            .map((node) => node.textContent?.trim() ?? "")
            .join(" ")
            .trim();
          return {
            viewport,
            devicePixelRatio,
            surfaces,
            controls,
            pendingQuestionCount:
              globalThis.document.querySelectorAll(expandedQuestionSelector).length,
            collapsedQuestionCount: globalThis.document.querySelectorAll(
              '.ja-interaction-card[data-interaction-status="pending-collapsed"]',
            ).length,
            questionTextLength: allQuestionText.length,
            root: {
              scrollWidth: root.scrollWidth,
              clientWidth: root.clientWidth,
            },
            composer: composer === null ? null : rectData(composer.getBoundingClientRect()),
            documentElement: {
              theme: root.getAttribute("data-theme-mode"),
              resolvedTheme: root.getAttribute("data-theme"),
              systemColorScheme: globalThis.matchMedia("(prefers-color-scheme: dark)").matches
                ? "dark"
                : "light",
              reducedMotion: expectedReducedMotion,
              mediaReducedMotion: globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches,
              reducedTransparency: root.getAttribute("data-reduced-transparency") === "true",
              highContrast: root.getAttribute("data-high-contrast") === "true",
            },
            expected: {
              width: expectedWidth,
              nativeWidth: expectedNativeWidth,
              nativeHeight: expectedNativeHeight,
              cssViewportWidth: expectedCssViewportWidth,
              zoom: expectedZoom,
              theme: expectedTheme,
              systemColorScheme: expectedSystemColorScheme,
              reducedMotion: expectedReducedMotion,
              reducedTransparency: expectedReducedTransparency,
              highContrast: expectedHighContrast,
              longText: expectedLongText,
              longTextThreshold,
              surfaceMode: expectedSurfaceMode,
              surfacePhase: expectedSurfacePhase,
            },
          };
        },
        {
          width,
          nativeWidth,
          nativeHeight,
          cssViewportWidth,
          zoom,
          theme,
          systemColorScheme,
          reducedMotion,
          reducedTransparency,
          highContrast,
          longText,
          surfaceMode,
          surfacePhase,
          longTextThreshold: INTERACTION_PLAN_UI_CONTRACT.longTextThreshold,
        },
      );
      const controlGeometry = computeVisibleControlOverlaps(capture.controls, capture.viewport);
      const visibleSurfaces = capture.surfaces
        .map((surface) => ({
          ...surface,
          paintedRect: computePaintedRect(surface.geometry, capture.viewport),
        }))
        .filter((surface) => surface.geometry.hidden !== true && surface.paintedRect !== null);
      const questionCards = visibleSurfaces.filter((surface) => surface.kind === "question");
      const planSurfaces = visibleSurfaces.filter((surface) => surface.kind === "plan");
      const horizontalOverflow = visibleSurfaces.reduce(
        (max, surface) => Math.max(max, surface.scrollWidth - surface.clientWidth),
        0,
      );
      const composerPaintedRect = capture.composer === null
        ? null
        : computePaintedRect({ rect: capture.composer, clipping: [] }, capture.viewport);
      const questionComposerOverlaps = composerPaintedRect === null
        ? []
        : questionCards.flatMap((surface) => {
            const intersection = intersectRects(surface.paintedRect, composerPaintedRect);
            // 允许相邻边框的 1px 接触，只有实际绘制面积才算 UI 遮挡。
            if (intersection === null || intersection.width <= 1 || intersection.height <= 1) return [];
            return [{
              surfaceIndex: surface.surfaceIndex,
              questionRect: surface.paintedRect,
              composerRect: composerPaintedRect,
              intersection,
            }];
          });
      const composerInViewport =
        capture.composer !== null &&
        capture.composer.width > 0 &&
        capture.composer.height > 0 &&
        capture.composer.top >= capture.viewport.top &&
        capture.composer.bottom <= capture.viewport.bottom &&
        capture.composer.left >= capture.viewport.left &&
        capture.composer.right <= capture.viewport.right;
      const readableAnchorRect = (geometry) => {
        if (geometry === null || geometry.hidden === true) return null;
        const painted = computePaintedRect(geometry, capture.viewport);
        if (painted === null ||
            painted.left < capture.viewport.left - 1 ||
            painted.top < capture.viewport.top - 1 ||
            painted.right > capture.viewport.right + 1 ||
            painted.bottom > capture.viewport.bottom + 1) {
          return null;
        }
        const composerOverlap = composerPaintedRect === null
          ? null
          : intersectRects(painted, composerPaintedRect);
        if (composerOverlap !== null && composerOverlap.width > 1 && composerOverlap.height > 1) {
          return null;
        }
        return painted;
      };
      const planSurfaceIndexes = new Set(planSurfaces.map((surface) => surface.surfaceIndex));
      const planAnchors = planSurfaces.flatMap((surface) => [
        {
          surfaceIndex: surface.surfaceIndex,
          kind: "objective",
          rect: readableAnchorRect(surface.anchors?.objective ?? null),
        },
        {
          surfaceIndex: surface.surfaceIndex,
          kind: "action",
          rect: readableAnchorRect(surface.anchors?.primaryAction ?? null),
        },
      ]);
      const planObjectiveRect = planAnchors.find(
        (anchor) => anchor.kind === "objective" && anchor.rect !== null,
      )?.rect ?? null;
      const planActionRect = planAnchors.find(
        (anchor) => anchor.kind === "action" && anchor.rect !== null,
      )?.rect ?? null;
      const planControlCount = capture.controls.filter((control) =>
        planSurfaceIndexes.has(control.surfaceIndex),
      ).length;
      const frameSurfacePhase = surfaceMode === "plan" ? surfacePhase : "question";
      const frameSuffix = surfaceMode === "plan"
        ? surfacePhase === "content" ? "plan-content" : "plan-action"
        : "question";
      const fileStem = `interaction-plan-${width}-${zoom}-${theme}-${frameSuffix}`;
      // AX 证据只绑定展开的问题帧；helper 在 AX tree、group、role/name、checked 和
      // required 任一必需事实缺失时失败关闭。Narrator 是否实际朗读仍需独立系统验收。
      const accessibilityEvidence = surfaceMode === "question"
        ? {
            ...(await captureInteractionPlanAccessibility(page, {
              cdpSession,
              expectedRequired: true,
            })),
            fileName: `${fileStem}.accessibility.json`,
            screenReaderNarrationVerified: false,
          }
        : null;
      if (accessibilityEvidence !== null) {
        await writeFile(
          join(screenshotDirectory, accessibilityEvidence.fileName),
          `${JSON.stringify(accessibilityEvidence, null, 2)}\n`,
          "utf8",
        );
      }
      const png = await captureRawScreenshot(join(screenshotDirectory, `${fileStem}.png`));
      const frame = {
        width,
        nativeWidth: capture.expected.nativeWidth,
        nativeHeight: capture.expected.nativeHeight,
        cssViewportWidth: capture.expected.cssViewportWidth,
        zoom,
        frameId: `${width}-${zoom}-${theme}-${frameSuffix}`,
        surfaceMode,
        surfacePhase: frameSurfacePhase,
        theme: capture.documentElement.theme,
        resolvedTheme: capture.documentElement.resolvedTheme,
        systemColorScheme: capture.documentElement.systemColorScheme,
        reducedMotion: capture.documentElement.reducedMotion,
        mediaReducedMotion: capture.documentElement.mediaReducedMotion,
        reducedTransparency: capture.documentElement.reducedTransparency,
        highContrast: capture.documentElement.highContrast,
        longText:
          surfaceMode === "question" &&
          capture.questionTextLength >= INTERACTION_PLAN_UI_CONTRACT.longTextThreshold,
        questionTextLength: capture.questionTextLength,
        pendingQuestionCount: capture.pendingQuestionCount,
        collapsedQuestionCount: capture.collapsedQuestionCount,
        documentOverflow: Math.max(0, capture.root.scrollWidth - capture.root.clientWidth),
        horizontalOverflow: Math.max(0, horizontalOverflow),
        controlOverlapCount: controlGeometry.overlaps.length,
        controlOverlaps: controlGeometry.overlaps,
        unnamedControlCount: controlGeometry.visibleControls.filter(
          (control) => control.name.length === 0,
        ).length,
        questionCardCount: questionCards.length,
        questionComposerOverlapCount: questionComposerOverlaps.length,
        questionComposerOverlaps,
        accessibilityEvidence,
        composerInViewport,
        planSurfaceCount: planSurfaces.length,
        planControlCount,
        planObjectiveVisible: planObjectiveRect !== null,
        planObjectiveRect,
        planActionVisible: planActionRect !== null,
        planActionRect,
        planActionCount: controlGeometry.visibleControls.filter(
          (control) =>
            control.isPrimary &&
            planSurfaces.some((surface) => surface.surfaceIndex === control.surfaceIndex),
        ).length,
        devicePixelRatio: capture.devicePixelRatio,
        pngWidth: png.width,
        pngHeight: png.height,
        pngByteLength: png.byteLength,
        pngDimensionsMatchNativeViewport:
          png.width === capture.expected.nativeWidth &&
          png.height === capture.expected.nativeHeight,
      };
      frame.zoomEvidence = "native_webview_zoom";
      // 每帧先落下真实截图和完整几何证据，再执行断言；失败时仍能复盘 clipping 与 overlap。
      await writeFile(
        join(screenshotDirectory, `${fileStem}.geometry.json`),
        `${JSON.stringify({ frame, capture, visibleSurfaces, visibleControls: controlGeometry.visibleControls }, null, 2)}\n`,
        "utf8",
      );
      assert.equal(frame.theme, theme);
      assert.equal(frame.systemColorScheme, systemColorScheme);
      assert.equal(frame.resolvedTheme, theme === "system" ? systemColorScheme : theme);
      assert.equal(frame.reducedMotion, reducedMotion);
      assert.equal(frame.mediaReducedMotion, reducedMotion);
      assert.equal(frame.reducedTransparency, reducedTransparency);
      assert.equal(frame.highContrast, highContrast);
      assert.equal(frame.composerInViewport, true);
      assert.equal(Number.isFinite(frame.devicePixelRatio) && frame.devicePixelRatio > 0, true);
      assert.equal(Number.isSafeInteger(nativeHeight) && nativeHeight > 0, true);
      assert.equal(frame.pngWidth, nativeWidth);
      assert.equal(frame.pngHeight, nativeHeight);
      assert.equal(frame.pngByteLength > 24, true);
      assert.equal(frame.pngDimensionsMatchNativeViewport, true);
      assert.equal(frame.documentOverflow, 0);
      assert.equal(frame.horizontalOverflow, 0);
      if (frame.controlOverlapCount !== 0) {
        throw new Error(
          `视觉矩阵控件真实重叠 frame=${frame.frameId}: ${JSON.stringify(frame.controlOverlaps)}`,
        );
      }
      if (frame.questionComposerOverlapCount !== 0) {
        throw new Error(
          `视觉矩阵问题卡遮挡 Composer frame=${frame.frameId}: ${JSON.stringify(frame.questionComposerOverlaps)}`,
        );
      }
      assert.equal(frame.unnamedControlCount, 0);
      if (surfaceMode === "question") {
        assert.equal(frame.surfacePhase, "question");
        assert.equal(frame.questionCardCount, 1);
        assert.equal(frame.pendingQuestionCount, 1);
        assert.equal(frame.collapsedQuestionCount, 0);
        if (longText) assert.equal(frame.longText, true);
      } else {
        assert.equal(["content", "action"].includes(frame.surfacePhase), true);
        assert.equal(frame.questionCardCount, 0);
        assert.equal(frame.pendingQuestionCount, 0);
        assert.equal(frame.collapsedQuestionCount, 1);
        assert.equal(frame.planSurfaceCount > 0, true);
        assert.equal(frame.planControlCount > 0, true);
        if (frame.surfacePhase === "content") {
          assert.equal(frame.planObjectiveVisible, true);
          assert.equal(frame.planObjectiveRect !== null, true);
        } else {
          assert.equal(frame.planActionVisible, true);
          assert.equal(frame.planActionRect !== null, true);
          assert.equal(frame.planActionCount > 0, true);
        }
      }
      return frame;
    }
    try {
      for (const width of INTERACTION_PLAN_UI_CONTRACT.widths) {
        for (const [index, zoom] of INTERACTION_PLAN_UI_CONTRACT.zooms.entries()) {
          const theme =
            INTERACTION_PLAN_UI_CONTRACT.themes[
              (width + index) % INTERACTION_PLAN_UI_CONTRACT.themes.length
            ];
          const systemColorScheme = index % 2 === 0 ? "light" : "dark";
          const reducedMotion = index === 0 || (width === 800 && index === 3);
          const reducedTransparency = index === 1 || (width === 800 && index === 3);
          const highContrast = index === 2 || (width === 800 && index === 3);
          const longText = index === 3;
          await page.emulateMedia({
            colorScheme: systemColorScheme,
            reducedMotion: reducedMotion ? "reduce" : "no-preference",
          });
          const prepareDeadline = visualDeadline();
          const preferenceEvidence = await prepare({
            page,
            width,
            theme,
            systemColorScheme,
            zoom,
            reducedMotion,
            reducedTransparency,
            highContrast,
            longText,
            deadline: prepareDeadline,
            signal,
          });
          assert.equal(preferenceEvidence?.width, width);
          assert.equal(preferenceEvidence?.themeMode, theme);
          assert.equal(preferenceEvidence?.zoom, zoom);
          assert.equal(preferenceEvidence?.zoomEvidence, "native_webview_zoom");
          assert.equal(Number.isSafeInteger(preferenceEvidence?.nativeWidth), true);
          assert.equal(preferenceEvidence.nativeWidth, width);
          assert.equal(Number.isSafeInteger(preferenceEvidence?.nativeHeight), true);
          assert.equal(preferenceEvidence.nativeHeight > 0, true);
          assert.equal(Number.isSafeInteger(preferenceEvidence?.cssViewportWidth), true);
          assert.equal(preferenceEvidence.cssViewportWidth > 0, true);
          const viewportDeadline = visualDeadline();
          await waitForCondition(
            "native viewport " + width + "@" + zoom,
            () =>
              page.evaluate(
                (expected) => globalThis.innerWidth === expected,
                preferenceEvidence.cssViewportWidth,
              ),
            viewportDeadline,
            signal,
          );
          // 先对齐尾部并验收展开问题帧；受限视口下不强求问题和长计划同屏。
          await scrollTimelineToBottom({ required: false, stepDeadline: visualDeadline() });
          const questionDeadline = visualDeadline();
          await assertState({
            page,
            surfaceMode: "question",
            requireQuestion: true,
            requirePlan: false,
            requireLongText: longText,
            deadline: questionDeadline,
            signal,
          });
          const questionFrame = await captureSurfaceFrame({
            width,
            nativeWidth: preferenceEvidence.nativeWidth,
            nativeHeight: preferenceEvidence.nativeHeight,
            cssViewportWidth: preferenceEvidence.cssViewportWidth,
            zoom,
            theme,
            systemColorScheme,
            reducedMotion,
            reducedTransparency,
            highContrast,
            longText,
            surfaceMode: "question",
            surfacePhase: "question",
          });

          // 通过生产 UI 收起问题，再定位真实 Plan row；计划可能位于最后一个提问之前。
          await collapseQuestionAndRevealPlan();
          await assertState({
            page,
            surfaceMode: "plan",
            requireQuestion: false,
            requirePlan: true,
            requireLongText: false,
            deadline: visualDeadline(),
            signal,
          });
          const planFrame = await captureSurfaceFrame({
            width,
            nativeWidth: preferenceEvidence.nativeWidth,
            nativeHeight: preferenceEvidence.nativeHeight,
            cssViewportWidth: preferenceEvidence.cssViewportWidth,
            zoom,
            theme,
            systemColorScheme,
            reducedMotion,
            reducedTransparency,
            highContrast,
            longText: false,
            surfaceMode: "plan",
            surfacePhase: "content",
          });
          await revealPlanAnchor("action", visualDeadline());
          const planActionFrame = await captureSurfaceFrame({
            width,
            nativeWidth: preferenceEvidence.nativeWidth,
            nativeHeight: preferenceEvidence.nativeHeight,
            cssViewportWidth: preferenceEvidence.cssViewportWidth,
            zoom,
            theme,
            systemColorScheme,
            reducedMotion,
            reducedTransparency,
            highContrast,
            longText: false,
            surfaceMode: "plan",
            surfacePhase: "action",
          });
          frames.push(questionFrame, planFrame, planActionFrame);
          await expandQuestionAfterPlan();
        }
      }
    } finally {
      await page
        .emulateMedia({ colorScheme: "light", reducedMotion: "no-preference" })
        .catch(() => undefined);
      await cdpSession.detach().catch(() => undefined);
    }
    return frames;
  }

  async function goalRegressionEvidence() {
    recordStage("interaction_plan:goal-regression");
    return requiredCallback(runGoalRegressionScenario, "runGoalRegressionScenario")();
  }

  async function soakEvidence() {
    recordStage("interaction_plan:soak");
    return requiredCallback(runSoakScenario, "runSoakScenario")();
  }

  return Object.freeze({
    runtimeEvidence,
    interactionEvidence,
    readonlyEvidence,
    executionEvidence,
    recoveryEvidence,
    isolationEvidence,
    visualEvidence,
    goalRegressionEvidence,
    soakEvidence,
  });
}
