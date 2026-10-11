// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import process from "node:process";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const REPOSITORY_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const FIXTURE_PATH = "/tests/app/e2e/turnChangesCardBrowserFixture.html";
const DEFAULT_EVIDENCE_DIRECTORY = join(tmpdir(), "ja-turn-changes-card-browser-evidence");
const PALETTES = ["ja", "xcode", "jetbrains", "obsidian", "claude"];
const WIDTHS = [320, 480, 760];
const ZOOMS = [100, 150, 200];

/** CLI 接受单一绝对证据目录，防止截图与运行报告散落到源码树。 */
function parseArguments(argv) {
  const options = { evidenceDirectory: DEFAULT_EVIDENCE_DIRECTORY };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument !== "--evidence-directory") throw new Error(`unknown argument: ${argument}`);
    const value = argv[index + 1];
    if (value === undefined || value.startsWith("--")) {
      throw new Error("--evidence-directory requires a path");
    }
    options.evidenceDirectory = resolve(value);
    index += 1;
  }
  return options;
}

/** 释放探测端口后立即让 Vite 绑定，避免复用其它开发服务的固定端口。 */
export async function reservePort() {
  const server = createServer();
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  await new Promise((resolvePromise, rejectPromise) =>
    server.close((error) => (error === undefined ? resolvePromise() : rejectPromise(error))),
  );
  return address.port;
}

/** 仅启动 fixture Vite，清除桌面 composition 变量以直连真实 React feature。 */
export function startFixtureServer(port) {
  const environment = { ...process.env };
  delete environment.JA_E2E_DEV_PORT;
  const child = spawn(
    process.execPath,
    [
      join(REPOSITORY_ROOT, "node_modules", "vite", "bin", "vite.js"),
      "--config",
      join(REPOSITORY_ROOT, "apps", "desktop", "vite.config.ts"),
      "--host",
      "127.0.0.1",
      "--port",
      String(port),
      "--strictPort",
    ],
    {
      cwd: REPOSITORY_ROOT,
      env: environment,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let output = "";
  child.stdout.on("data", (chunk) => {
    output = `${output}${chunk}`.slice(-10_000);
  });
  child.stderr.on("data", (chunk) => {
    output = `${output}${chunk}`.slice(-10_000);
  });
  return { child, output: () => output };
}

/** 等待真实 HTML 可访问并检查进程仍存活；启动失败输出仅保留有界末尾。 */
export async function waitForFixture(url, server) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (server.child.exitCode !== null) {
      throw new Error(`fixture server exited early\n${server.output()}`);
    }
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // Vite 尚未监听，继续有界探测。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`fixture server did not become ready\n${server.output()}`);
}

/** 先请求正常退出，只对本脚本创建的 Vite PID 使用 Windows taskkill 兜底。 */
export async function stopFixtureServer(server) {
  if (server.child.exitCode !== null) return;
  server.child.kill();
  const exited = await Promise.race([
    new Promise((resolvePromise) => server.child.once("exit", () => resolvePromise(true))),
    new Promise((resolvePromise) => setTimeout(() => resolvePromise(false), 2_000)),
  ]);
  if (exited || process.platform !== "win32" || server.child.pid === undefined) return;
  await new Promise((resolvePromise) => {
    const killer = spawn("taskkill.exe", ["/PID", String(server.child.pid), "/T", "/F"], {
      windowsHide: true,
      stdio: "ignore",
    });
    killer.once("exit", resolvePromise);
  });
}

/** 编码受控的视觉场景并调整物理 viewport，让放大后的 Card 完整留在截图中。 */
async function openCardScenario(page, fixtureUrl, scenario) {
  const viewportWidth = Math.max(320, Math.ceil((scenario.width * scenario.zoom) / 100));
  await page.setViewportSize({ width: viewportWidth, height: 800 });
  await page.emulateMedia({ reducedMotion: "reduce", colorScheme: scenario.theme });
  const query = new URLSearchParams({
    scene: "card",
    width: String(scenario.width),
    files: String(scenario.fileCount),
    state: scenario.state,
    longPath: scenario.longPath ? "1" : "0",
    palette: scenario.palette,
    theme: scenario.theme,
    zoom: String(scenario.zoom),
  });
  await page.goto(`${fixtureUrl}?${query.toString()}`, { waitUntil: "domcontentloaded" });
  const card = page.locator(".ja-turn-changes");
  await card.waitFor({ state: "visible" });
  await page.evaluate(async () => {
    await globalThis.document.fonts.ready;
  });
  await page.waitForTimeout(30);
  return card;
}

/** 真实生产 Card 必须在容器与每行边界内布局，标题和操作不得造成水平溢出。 */
async function inspectCardGeometry(card) {
  const geometry = await card.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const rows = [...element.querySelectorAll(".ja-turn-changes__file")];
    const header = element.querySelector(".ja-turn-changes__header");
    const headerRect = header?.getBoundingClientRect();
    return {
      x: Math.round(rect.x * 10) / 10,
      y: Math.round(rect.y * 10) / 10,
      width: Math.round(rect.width * 10) / 10,
      height: Math.round(rect.height * 10) / 10,
      cardClientWidth: element.clientWidth,
      cardScrollWidth: element.scrollWidth,
      headerClientWidth: header?.clientWidth ?? 0,
      headerScrollWidth: header?.scrollWidth ?? 0,
      headerWithinCard:
        headerRect !== undefined &&
        headerRect.left >= rect.left &&
        headerRect.right <= rect.right + 1,
      rowCount: rows.length,
      rowsWithinCard: rows.every((row) => {
        const rowRect = row.getBoundingClientRect();
        return rowRect.left >= rect.left && rowRect.right <= rect.right + 1 && rowRect.width > 0;
      }),
      filenameFontSizes: rows.map(
        (row) =>
          globalThis.getComputedStyle(row.querySelector(".ja-turn-changes__filename")).fontSize,
      ),
      palette: globalThis.document.documentElement.dataset["palette"],
      theme: globalThis.document.documentElement.dataset["theme"],
      zoom: globalThis.document.documentElement.style.zoom,
    };
  });
  assert.ok(geometry.width > 0 && geometry.height > 0, "Card must have visible dimensions");
  assert.ok(
    geometry.cardScrollWidth <= geometry.cardClientWidth + 1,
    "Card has horizontal overflow",
  );
  assert.ok(
    geometry.headerScrollWidth <= geometry.headerClientWidth + 1,
    "Card header has horizontal overflow",
  );
  assert.ok(geometry.headerWithinCard, "Card header escaped the surface");
  assert.ok(geometry.rowsWithinCard, "A file row escaped the Card surface");
  assert.ok(geometry.filenameFontSizes.every((size) => Number.parseFloat(size) >= 14));
  return geometry;
}

/** 先捕获默认折叠态，再单独检查展开长路径，避免把未渲染行误判为布局缺陷。 */
async function captureScenario(page, fixtureUrl, evidenceDirectory, scenario, label) {
  const card = await openCardScenario(page, fixtureUrl, scenario);
  const expectedHeading = `${scenario.state === "partial" ? "已确认" : "已编辑"} ${scenario.fileCount} 个文件`;
  await card
    .locator(".ja-turn-changes__summary strong")
    .getByText(expectedHeading, { exact: true })
    .waitFor({
      state: "visible",
    });
  assert.equal(
    await card.locator(".ja-turn-changes__files li").count(),
    Math.min(scenario.fileCount, 3),
    `visible file count did not match ${label}`,
  );
  const expand = card.locator(".ja-turn-changes__expand");
  if (scenario.fileCount > 3) {
    await expand
      .getByText(`再显示 ${scenario.fileCount - 3} 个文件`, { exact: true })
      .waitFor({ state: "visible" });
  } else {
    assert.equal(await expand.count(), 0, `unexpected expand action in ${label}`);
  }
  if (scenario.state !== "complete") {
    await card
      .getByText("仅展示已确认的修改，文件列表或统计可能不完整。", { exact: true })
      .waitFor({ state: "visible" });
  }
  const geometry = await inspectCardGeometry(card);
  const screenshot = join(evidenceDirectory, `${label}.png`);
  await page.screenshot({ path: screenshot, fullPage: true, animations: "disabled" });
  let cardScreenshot;
  let expandedGeometry;
  let expandedScreenshot;
  if (label === "card-ja-dark-files-23-width-760-zoom-100") {
    cardScreenshot = join(evidenceDirectory, `${label}-card.png`);
    await card.screenshot({ path: cardScreenshot, animations: "disabled" });
  }
  if (scenario.longPath) {
    await expand.click();
    await card
      .locator(".ja-turn-changes__filename")
      .filter({ hasText: "reviewed-file-with-a-deliberately-long-readable-name.tsx" })
      .waitFor({ state: "visible" });
    assert.equal(
      await card.locator(".ja-turn-changes__files li").count(),
      scenario.fileCount,
      `expanded file count did not match ${label}`,
    );
    expandedGeometry = await inspectCardGeometry(card);
    expandedScreenshot = join(evidenceDirectory, `${label}-expanded.png`);
    await card.screenshot({ path: expandedScreenshot, animations: "disabled" });
  }
  return {
    label,
    scenario,
    geometry,
    screenshot,
    ...(cardScreenshot === undefined ? {} : { cardScreenshot }),
    ...(expandedGeometry === undefined ? {} : { expandedGeometry }),
    ...(expandedScreenshot === undefined ? {} : { expandedScreenshot }),
  };
}

/** 从明确的历史起点分段滚动到目标虚拟行，避免初始贴底状态绕过中段。 */
async function revealTimelineCard(page, targetTurnId) {
  const scroller = page.locator(".ja-chat-timeline__scroll");
  await scroller.waitFor({ state: "visible" });
  await scroller.evaluate((element) => {
    element.scrollTop = 0;
  });
  await page.waitForTimeout(35);
  for (let attempt = 0; attempt < 24; attempt += 1) {
    const visible = await page.locator(`[data-turn-id="${targetTurnId}"] .ja-turn-changes`).count();
    if (visible === 1) {
      const position = await scroller.evaluate((element) => ({
        scrollTop: element.scrollTop,
        viewportTop: element.getBoundingClientRect().top,
      }));
      return { scroller, scrollTop: position.scrollTop, viewportTop: position.viewportTop };
    }
    await scroller.evaluate((element) => {
      element.scrollTop = Math.min(
        element.scrollHeight - element.clientHeight,
        element.scrollTop + Math.max(180, Math.floor(element.clientHeight * 0.65)),
      );
    });
    await page.waitForTimeout(35);
  }
  const diagnostics = await page.evaluate((turnId) => {
    const element = globalThis.document.querySelector(".ja-chat-timeline__scroll");
    return {
      scroll:
        element instanceof globalThis.HTMLElement
          ? {
              top: element.scrollTop,
              height: element.scrollHeight,
              clientHeight: element.clientHeight,
            }
          : null,
      renderedTurns: [
        ...globalThis.document.querySelectorAll(".ja-chat-timeline__row[data-turn-id]"),
      ].map((row) => row.getAttribute("data-turn-id")),
      targetRow: globalThis.document
        .querySelector(`[data-turn-id="${turnId}"]`)
        ?.outerHTML.slice(0, 500),
    };
  }, targetTurnId);
  throw new Error(
    `timeline target Turn ${targetTurnId} never entered the virtual viewport: ${JSON.stringify(diagnostics)}`,
  );
}

/**
 * 虚拟行重测可能在一次 scrollTop 写入后改写 spacer 高度；每帧重算真实边界，
 * 直到目标离开/重新进入虚拟范围。5 秒截止避免吞掉真实失败，并保留实际 range 诊断。
 */
async function synchronizeTimelineVirtualRange(
  page,
  targetTurnId,
  targetRowIndex,
  targetScrollTop,
) {
  const result = await page.evaluate(
    async ({ turnId, rowIndex, scrollTop }) => {
      const startedAt = globalThis.performance.now();
      const deadline = startedAt + 5_000;
      const selector = `[data-turn-id="${turnId}"] .ja-turn-changes`;
      const element = globalThis.document.querySelector(".ja-chat-timeline__scroll");
      if (!(element instanceof globalThis.HTMLElement)) {
        throw new Error("timeline scroll container is unavailable");
      }
      let stableFrames = 0;
      let boundaryCorrections = 0;
      let snapshot = null;
      while (globalThis.performance.now() < deadline) {
        const maxScrollTop = Math.max(0, element.scrollHeight - element.clientHeight);
        const requestedTop = scrollTop === null ? maxScrollTop : Math.min(scrollTop, maxScrollTop);
        element.scrollTop = requestedTop;
        await new Promise((resolveFrame) => globalThis.requestAnimationFrame(resolveFrame));

        const currentTarget = globalThis.document.querySelector(selector);
        const currentMaxScrollTop = Math.max(0, element.scrollHeight - element.clientHeight);
        if (scrollTop === null && Math.abs(requestedTop - currentMaxScrollTop) > 1) {
          boundaryCorrections += 1;
        }
        const atEnd = Math.abs(element.scrollTop - currentMaxScrollTop) <= 1;
        const atRequestedOffset =
          scrollTop !== null &&
          Math.abs(element.scrollTop - Math.min(scrollTop, currentMaxScrollTop)) <= 1;
        const virtualized = currentTarget === null;
        const inRange = currentTarget !== null;
        const synchronized =
          scrollTop === null ? virtualized && atEnd : inRange && atRequestedOffset;
        stableFrames = synchronized ? stableFrames + 1 : 0;
        const rows = [
          ...globalThis.document.querySelectorAll(".ja-chat-timeline__row[data-index]"),
        ].map((row) => Number(row.getAttribute("data-index")));
        const viewport = element.getBoundingClientRect();
        const targetRow = currentTarget?.closest(".ja-chat-timeline__row[data-index]");
        snapshot = {
          ready: stableFrames >= 2,
          phase: scrollTop === null ? "scroll-to-end" : "restore-target-range",
          elapsedMs: Math.round(globalThis.performance.now() - startedAt),
          targetTurnId: turnId,
          targetRowIndex: rowIndex,
          targetMounted: currentTarget !== null,
          targetRenderedIndex:
            targetRow === null || targetRow === undefined
              ? null
              : Number(targetRow.getAttribute("data-index")),
          viewport: { top: viewport.top, bottom: viewport.bottom, height: viewport.height },
          scroll: {
            top: element.scrollTop,
            requestedTop,
            height: element.scrollHeight,
            clientHeight: element.clientHeight,
            maxTop: currentMaxScrollTop,
            atEnd,
            boundaryCorrections,
          },
          renderedIndices: rows,
          renderedRange:
            rows.length === 0 ? null : { first: Math.min(...rows), last: Math.max(...rows) },
        };
        if (stableFrames >= 2) return snapshot;
      }
      return (
        snapshot ?? {
          ready: false,
          phase: scrollTop === null ? "scroll-to-end" : "restore-target-range",
          targetTurnId: turnId,
          targetRowIndex: rowIndex,
        }
      );
    },
    { turnId: targetTurnId, rowIndex: targetRowIndex, scrollTop: targetScrollTop },
  );
  assert.equal(
    result.ready,
    true,
    `timeline virtual range did not synchronize: ${JSON.stringify(result)}`,
  );
  return result;
}

/**
 * 在真实 ChatTimeline 布局中验收锚点、焦点、缓存恢复和 Review 读取边界；使用浏览器实测的
 * scroll 与虚拟行，避免固定几何的单测替身掩盖 ResizeObserver 与 React commit 的时序差异。
 */
export async function verifyTimelineWorkflow(page, fixtureUrl, evidenceDirectory) {
  await page.setViewportSize({ width: 1_200, height: 820 });
  await page.emulateMedia({ reducedMotion: "reduce", colorScheme: "dark" });
  await page.goto(`${fixtureUrl}?scene=timeline&palette=ja&theme=dark&width=760`, {
    waitUntil: "domcontentloaded",
  });
  const fixture = page.locator('[data-fixture-scene="timeline"]');
  await fixture.waitFor({ state: "visible" });
  const readCount = () =>
    page.evaluate(() => {
      const probe = globalThis.__JA_TURN_CHANGES__;
      if (probe === undefined) throw new Error("fixture read probe is missing");
      return probe.readCount();
    });
  assert.equal(await readCount(), 0, "mounting a hidden Review panel must not read frozen changes");

  const targetTurnId = "turn_timeline_target";
  const targetPath = "src/features/conversation/turn-changes/reviewed-file-08.tsx";
  const target = page.locator(`[data-turn-id="${targetTurnId}"] .ja-turn-changes`);
  await revealTimelineCard(page, targetTurnId);
  await target.waitFor({ state: "visible" });
  await page.screenshot({
    path: join(evidenceDirectory, "timeline-760-dark-before.png"),
    animations: "disabled",
  });

  const toggle = target.locator(".ja-turn-changes__expand");
  const initialName = `再显示 ${23 - 3} 个文件`;
  await toggle.getByText(initialName, { exact: true }).waitFor({ state: "visible" });
  await toggle.focus();
  const before = await page.evaluate((turnId) => {
    const scrollerElement = globalThis.document.querySelector(".ja-chat-timeline__scroll");
    const cardElement = globalThis.document.querySelector(
      `[data-turn-id="${turnId}"] .ja-turn-changes`,
    );
    if (
      !(scrollerElement instanceof globalThis.HTMLElement) ||
      !(cardElement instanceof globalThis.HTMLElement)
    ) {
      throw new Error("timeline anchor is unavailable");
    }
    return {
      scrollTop: scrollerElement.scrollTop,
      cardTop: cardElement.getBoundingClientRect().top,
      focusIsDisclosure:
        globalThis.document.activeElement === cardElement.querySelector(".ja-turn-changes__expand"),
    };
  }, targetTurnId);
  assert.equal(before.focusIsDisclosure, true);
  await page.keyboard.press("Enter");
  await target
    .locator('.ja-turn-changes__expand[aria-expanded="true"]')
    .waitFor({ state: "visible" });
  await page.waitForTimeout(100);
  const after = await page.evaluate((turnId) => {
    const scrollerElement = globalThis.document.querySelector(".ja-chat-timeline__scroll");
    const cardElement = globalThis.document.querySelector(
      `[data-turn-id="${turnId}"] .ja-turn-changes`,
    );
    if (
      !(scrollerElement instanceof globalThis.HTMLElement) ||
      !(cardElement instanceof globalThis.HTMLElement)
    ) {
      throw new Error("expanded timeline anchor is unavailable");
    }
    return {
      scrollTop: scrollerElement.scrollTop,
      cardTop: cardElement.getBoundingClientRect().top,
      focusIsDisclosure:
        globalThis.document.activeElement === cardElement.querySelector(".ja-turn-changes__expand"),
    };
  }, targetTurnId);
  assert.ok(
    Math.abs(after.scrollTop - before.scrollTop) <= 1,
    "expansion moved the user's scroll position",
  );
  assert.ok(
    Math.abs(after.cardTop - before.cardTop) <= 1,
    "expansion moved the visible Turn anchor",
  );
  assert.equal(after.focusIsDisclosure, true, "keyboard focus must remain on the disclosure");
  assert.equal(await readCount(), 0, "expanding file rows must not read Review content");

  const targetRowIndex = await target.evaluate((element) =>
    Number(element.closest(".ja-chat-timeline__row[data-index]")?.getAttribute("data-index")),
  );
  assert.ok(Number.isSafeInteger(targetRowIndex), "timeline target row index is unavailable");
  const unmounted = await synchronizeTimelineVirtualRange(page, targetTurnId, targetRowIndex, null);
  /**
   * 用已经实际显示过目标行的 scrollTop 复位；按滚动范围取半程会受异步测量的行高影响，
   * 可能落在 overscan 边缘之外，使“恢复缓存”断言在浏览器里偶发超时。
   */
  const returnScrollTop = before.scrollTop;
  const restored = await synchronizeTimelineVirtualRange(
    page,
    targetTurnId,
    targetRowIndex,
    returnScrollTop,
  );
  await target.waitFor({ state: "visible" });
  assert.equal(
    await target.locator(".ja-turn-changes__expand").getAttribute("aria-expanded"),
    "true",
    "the Thread/Turn disclosure cache must restore expansion after virtual unmount",
  );

  const fileButton = target.getByRole("button", {
    name: `查看 ${targetPath} 的修改，修改`,
    exact: true,
  });
  await fileButton.waitFor({ state: "visible" });
  await fileButton.click();
  const error = page.getByRole("alert").filter({ hasText: "无法读取本轮修改，请重试。" });
  await error.waitFor({ state: "visible" });
  assert.equal(await readCount(), 1, "opening one frozen file must perform one read");
  const request = await page.evaluate(() => globalThis.__JA_TURN_CHANGES__?.lastRequest());
  assert.deepEqual(request, {
    threadId: "thread_turn_changes_fixture",
    turnId: targetTurnId,
    path: targetPath,
  });
  const firstRead = await page.evaluate(() => globalThis.__JA_TURN_CHANGES__?.reads()[0]);
  assert.deepEqual(firstRead, {
    threadId: "thread_turn_changes_fixture",
    turnId: targetTurnId,
    artifactId: "artifact_turn_changes_fixture",
    path: targetPath,
  });
  await page.screenshot({
    path: join(evidenceDirectory, "timeline-review-read-error.png"),
    animations: "disabled",
  });

  await error.getByRole("button", { name: "重试", exact: true }).press("Enter");
  const diffPath = page.getByRole("heading", { name: targetPath, level: 2, exact: true });
  await diffPath.waitFor({ state: "visible" });
  await page.locator(".ja-review-unified-diff").waitFor({ state: "visible" });
  assert.equal(await readCount(), 2, "retry must issue exactly one new frozen read");
  assert.equal(
    await diffPath.evaluate((element) => globalThis.document.activeElement === element),
    true,
    "keyboard retry must return focus to the selected file detail",
  );
  await page.screenshot({
    path: join(evidenceDirectory, "timeline-review-success.png"),
    animations: "disabled",
  });

  await page.getByTestId("review-visibility-toggle").click();
  await page.getByTestId("review-pane").waitFor({ state: "hidden" });
  await page.waitForTimeout(160);
  assert.equal(await readCount(), 2, "hiding the Review pane must not start another read");
  await target.locator(".ja-turn-changes__expand").click();
  assert.equal(await readCount(), 2, "expanding while Review is hidden must not read files");
  await target.locator(".ja-turn-changes__expand").click();
  assert.equal(await readCount(), 2, "collapsing while Review is hidden must not read files");
  await page.screenshot({
    path: join(evidenceDirectory, "timeline-review-hidden-zero-read.png"),
    animations: "disabled",
  });
  return {
    initialReads: 0,
    expansionReads: 0,
    hiddenPanelReads: 0,
    openReadCount: 1,
    retryReadCount: 1,
    totalReads: await readCount(),
    requestedTurnId: request.turnId,
    requestedPath: request.path,
    scrollDelta: Math.round((after.scrollTop - before.scrollTop) * 10) / 10,
    anchorDelta: Math.round((after.cardTop - before.cardTop) * 10) / 10,
    focusRetained: after.focusIsDisclosure,
    virtualUnmount: unmounted,
    virtualRemount: restored,
    disclosureRestoredAfterUnmount: true,
    retryFocusRestored: true,
  };
}

/** 编排首张 760px 深色图、Palette/模式、内容数量、宽度与 CSS 缩放矩阵，不将其视为系统 DPI 验收。 */
export async function runVisualMatrix(page, fixtureUrl, evidenceDirectory) {
  const captures = [];
  const capture = async (scenario, label) => {
    const result = await captureScenario(page, fixtureUrl, evidenceDirectory, scenario, label);
    captures.push(result);
    return result;
  };
  const base = {
    scene: "card",
    width: 760,
    fileCount: 23,
    state: "complete",
    longPath: true,
    palette: "ja",
    theme: "dark",
    zoom: 100,
  };

  const firstDark = await capture(base, "card-ja-dark-files-23-width-760-zoom-100");
  for (const palette of PALETTES) {
    for (const theme of ["light", "dark"]) {
      if (palette === "ja" && theme === "dark") continue;
      await capture(
        { ...base, palette, theme, state: "partial" },
        `theme-${palette}-${theme}-files-23-width-760`,
      );
    }
  }

  for (const fileCount of [1, 3, 23]) {
    await capture(
      { ...base, width: 480, fileCount, longPath: fileCount === 23 },
      `files-${fileCount}-width-480`,
    );
  }
  for (const state of ["partial", "truncated"]) {
    await capture({ ...base, width: 480, state }, `state-${state}-width-480-long-path`);
  }
  for (const width of WIDTHS) {
    await capture({ ...base, width }, `width-${width}-dark-ja`);
  }
  for (const zoom of ZOOMS) {
    await capture({ ...base, zoom }, `zoom-${zoom}-width-760-dark-ja`);
  }
  return { firstDark, captures };
}

/** 捕获浏览器控制台错误并在 finally 关闭页面与 Vite，防止遗留 runner 子进程。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  await mkdir(options.evidenceDirectory, { recursive: true });
  const port = await reservePort();
  const fixtureUrl = `http://127.0.0.1:${port}${FIXTURE_PATH}`;
  const server = startFixtureServer(port);
  let browser;
  const consoleErrors = [];
  try {
    await waitForFixture(fixtureUrl, server);
    browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    page.on("pageerror", (error) => consoleErrors.push(error.message));
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    const visual = await runVisualMatrix(page, fixtureUrl, options.evidenceDirectory);
    const timeline = await verifyTimelineWorkflow(page, fixtureUrl, options.evidenceDirectory);
    assert.deepEqual(consoleErrors, [], "fixture emitted browser console errors");
    const report = {
      schemaVersion: 1,
      status: "passed",
      runtime: "production_component_browser_fixture",
      nativeVerified: false,
      nativeGitVerified: false,
      matrix: visual.captures.map(
        ({ label, scenario, geometry, screenshot, expandedGeometry, expandedScreenshot }) => ({
          label,
          scenario,
          geometry,
          screenshot,
          ...(expandedGeometry === undefined ? {} : { expandedGeometry }),
          ...(expandedScreenshot === undefined ? {} : { expandedScreenshot }),
          ...(visual.firstDark.cardScreenshot !== undefined && label === visual.firstDark.label
            ? { cardScreenshot: visual.firstDark.cardScreenshot }
            : {}),
        }),
      ),
      timeline,
      consoleErrors,
    };
    const reportPath = join(options.evidenceDirectory, "turn-changes-card-browser.json");
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
    process.stdout.write(
      `JA_TURN_CHANGES_CARD_BROWSER_OK screenshots=${visual.captures.length + visual.captures.filter((capture) => capture.expandedScreenshot !== undefined).length + 5} report=${reportPath}\n`,
    );
  } finally {
    await browser?.close().catch(() => undefined);
    await stopFixtureServer(server);
  }
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`${error?.stack ?? error}\n`);
    process.exitCode = 1;
  });
}
