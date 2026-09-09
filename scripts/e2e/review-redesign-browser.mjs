// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { basename, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";
import { REVIEW_REDESIGN_MATRIX } from "./review-redesign-webview2-driver.mjs";

const REPOSITORY_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const FIXTURE_PATH = "/tests/app/e2e/reviewRedesignBrowserFixture.html";
const DEFAULT_EVIDENCE_DIRECTORY = join(
  REPOSITORY_ROOT,
  ".tmp",
  "review-redesign-browser-evidence",
);

/** CLI 只允许改证据目录；fixture 永远使用独立 Vite，避免进入 Tauri E2E composition。 */
function parseArguments(argv) {
  const parsed = { evidenceDirectory: DEFAULT_EVIDENCE_DIRECTORY };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--evidence-directory") {
      const value = argv[index + 1];
      if (value === undefined) throw new Error("--evidence-directory requires a value");
      parsed.evidenceDirectory = resolve(value);
      index += 1;
    } else {
      throw new Error(`unknown argument: ${argument}`);
    }
  }
  return parsed;
}

/** 向系统请求临时本地端口，关闭探针后再交由 Vite 绑定。 */
async function reservePort() {
  const server = createServer();
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert.ok(address !== null && typeof address === "object");
  const port = address.port;
  await new Promise((resolvePromise, reject) =>
    server.close((error) => (error === undefined ? resolvePromise() : reject(error))),
  );
  return port;
}

/** 独立启动 Vite，显式删除 JA_E2E_DEV_PORT 以阻止生产配置替换入口。 */
function startFixtureServer(port) {
  const environment = { ...process.env };
  delete environment.JA_E2E_DEV_PORT;
  const viteBin = join(REPOSITORY_ROOT, "node_modules", "vite", "bin", "vite.js");
  const child = spawn(
    process.execPath,
    [
      viteBin,
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
    output = `${output}${chunk}`.slice(-12_000);
  });
  child.stderr.on("data", (chunk) => {
    output = `${output}${chunk}`.slice(-12_000);
  });
  return { child, output: () => output };
}

/** 轮询 fixture HTML 而非仅探测 TCP，确保 Vite transform 已可用。 */
async function waitForFixtureServer(url, processHandle) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (processHandle.child.exitCode !== null)
      throw new Error(`Vite fixture server exited early\n${processHandle.output()}`);
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch {
      // 服务器尚未完成监听，继续有界轮询。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 100));
  }
  throw new Error(`Vite fixture server did not become ready\n${processHandle.output()}`);
}

/** 截图前等待当前文档所有有限动画结束，避免把浮层进入帧误当成稳定视觉。 */
async function waitForAnimations(page) {
  await page.evaluate(async () => {
    const animations = globalThis.document
      .getAnimations()
      .filter((animation) => animation.playState !== "finished");
    await Promise.all(animations.map((animation) => animation.finished.catch(() => undefined)));
  });
}

/** 只终止本 runner 启动的 Vite PID 树；正常退出优先，taskkill 仅作 Windows 兜底。 */
async function stopFixtureServer(child) {
  if (child.exitCode !== null) return;
  child.kill();
  const exited = await Promise.race([
    new Promise((resolvePromise) => child.once("exit", () => resolvePromise(true))),
    new Promise((resolvePromise) => setTimeout(() => resolvePromise(false), 2_000)),
  ]);
  if (exited || process.platform !== "win32" || child.pid === undefined) return;
  await new Promise((resolvePromise) => {
    const killer = spawn("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], {
      windowsHide: true,
      stdio: "ignore",
    });
    killer.once("exit", resolvePromise);
  });
}

/** 文件分组使用紧凑 Radix radio menu，portal 必须从页面根按当前 open 层定位。 */
async function chooseGrouping(page, option) {
  await page.getByRole("button", { name: /^文件分组：/u }).click();
  const menu = page.locator("[role='menu'][data-state='open']").last();
  await menu.waitFor({ state: "visible" });
  await menu.getByRole("menuitemradio", { name: option, exact: true }).click();
}

/** 验证真实组件的菜单、导航与双向布局切换，防止仅有按钮状态而正文不变。 */
async function verifyInteractions(page, fixtureUrl, evidenceDirectory) {
  await page.setViewportSize({ width: 1000, height: 760 });
  await page.goto(`${fixtureUrl}?theme=light`, { waitUntil: "domcontentloaded" });
  const shell = page.locator("[data-ja-review-shell]");
  await shell.waitFor({ state: "visible" });
  await assertLayout(shell, "wide");

  const range = shell.getByRole("button", { name: /^审阅范围：/u });
  await range.waitFor({ state: "visible" });
  await range.focus();
  await range.press("Enter");
  await page.waitForTimeout(100);
  const menuFacts = await page.evaluate(() => ({
    triggerState: globalThis.document
      .querySelector(".ja-review-source-trigger")
      ?.getAttribute("data-state"),
    menuCount: globalThis.document.querySelectorAll("[role='menu']").length,
    openSurfaceCount: globalThis.document.querySelectorAll(".ja-menu-content[data-state='open']")
      .length,
    portalText: [...globalThis.document.querySelectorAll("[role='menu']")].map(
      (node) => node.textContent,
    ),
  }));
  assert.equal(menuFacts.triggerState, "open", `范围菜单未打开：${JSON.stringify(menuFacts)}`);
  const rangeMenu = page.locator("[role='menu'][data-state='open']").first();
  await rangeMenu.waitFor({ state: "visible" });
  const uncommitted = rangeMenu.getByRole("menuitem").filter({ hasText: /^未提交$/u });
  await uncommitted.focus();
  await uncommitted.press("ArrowRight");
  const openMenus = page.locator("[role='menu'][data-state='open']");
  await openMenus.nth(1).waitFor({ state: "visible" });
  await openMenus.nth(1).getByRole("menuitem", { name: "全部", exact: true }).waitFor({
    state: "visible",
  });
  await page.keyboard.press("Escape");
  await page.keyboard.press("Escape");

  const search = shell.getByRole("searchbox", { name: "筛选文件", exact: true });
  await search.fill("partially-staged.ts");
  const layered = shell
    .locator("[data-review-file-id][data-review-layer]")
    .filter({ hasText: "partially-staged.ts" });
  assert.equal(await layered.count(), 2, "同路径的 staged/unstaged 条目必须同时可见");
  const layers = [];
  for (let index = 0; index < 2; index += 1) {
    const row = layered.nth(index);
    layers.push(await row.getAttribute("data-review-layer"));
    await row.click();
    await expectSelectedIdentity(shell, row);
  }
  assert.deepEqual(new Set(layers), new Set(["staged", "unstaged"]));

  await search.fill("AppearancePanel");
  await shell.getByText("AppearancePanel.tsx", { exact: true }).waitFor({ state: "visible" });
  await search.fill("");
  await chooseGrouping(page, "目录");
  await chooseGrouping(page, "平铺");
  await chooseGrouping(page, "状态与目录");
  await shell.getByRole("button", { name: "全部折叠", exact: true }).click();

  const separator = shell.getByRole("separator", { name: "调整文件树宽度", exact: true });
  const widthBefore = Number(await separator.getAttribute("aria-valuenow"));
  await separator.focus();
  await separator.press("ArrowLeft");
  const widthAfter = Number(await separator.getAttribute("aria-valuenow"));
  assert.ok(widthAfter > widthBefore, "ArrowLeft 应扩大位于右侧的文件树");

  for (const width of [1000, 520, 360]) {
    await page.setViewportSize({ width, height: 760 });
    for (const mode of ["split", "unified"]) {
      await shell
        .getByRole("button", { name: mode === "split" ? "双栏 Diff" : "统一 Diff", exact: true })
        .click();
      const viewer = shell.locator(`[data-review-diff-mode="${mode}"]`);
      await viewer.waitFor({ state: "visible" });
      if (mode === "split") {
        const left = await viewer.locator('[data-review-diff-side="old"]').first().boundingBox();
        const right = await viewer.locator('[data-review-diff-side="new"]').first().boundingBox();
        assert.ok(left && right && left.x + left.width <= right.x + 1, "双栏未左右排列");
      } else {
        const counts = await viewer
          .locator(".is-line")
          .evaluateAll((rows) =>
            rows.map((row) => row.querySelectorAll(".ja-review-unified-diff-number").length),
          );
        assert.ok(counts.length > 0 && counts.every((count) => count === 1), "统一 Diff 重复行号");
      }
      await shell.screenshot({ path: join(evidenceDirectory, `layout-${mode}-${width}.png`) });
    }
  }

  await page.setViewportSize({ width: 520, height: 760 });
  await assertLayout(shell, "narrow");
  const back = shell.getByRole("button", { name: "返回变更文件", exact: true });
  if (await back.isVisible()) await back.click();
  await search.fill("merge.ts");
  const firstFile = shell.locator("[data-review-file-id][data-review-layer]").first();
  await firstFile.click();
  await back.waitFor({ state: "visible" });
  await back.click();
  await firstFile.waitFor({ state: "visible" });
  return {
    rangeMenuTrigger: true,
    rangeMenuPortal: true,
    uncommittedSubmenu: true,
    layeredIdentity: true,
    search: true,
    groupingControl: true,
    groupingSwitch: true,
    narrowBack: true,
    keyboardResize: true,
    diffLayouts: true,
  };
}

/** 等待 ReviewShell 的容器驱动布局属性收敛。 */
async function assertLayout(shell, expected) {
  await shell
    .page()
    .waitForFunction(
      ({ expectedLayout }) =>
        globalThis.document
          .querySelector("[data-ja-review-shell]")
          ?.getAttribute("data-review-layout") === expectedLayout,
      { expectedLayout: expected },
    );
  assert.equal(await shell.getAttribute("data-review-layout"), expected);
}

/** 文件点击后外层 Diff identity 必须与行的 fileId/layer 完全一致。 */
async function expectSelectedIdentity(shell, row) {
  const expected = {
    id: await row.getAttribute("data-review-file-id"),
    layer: await row.getAttribute("data-review-layer"),
  };
  await shell.page().waitForFunction(({ id, layer }) => {
    const diff = globalThis.document.querySelector(
      "[data-ja-review-shell] [data-review-selected-file-id][data-review-selected-layer]",
    );
    return (
      diff?.getAttribute("data-review-selected-file-id") === id &&
      diff?.getAttribute("data-review-selected-layer") === layer
    );
  }, expected);
}

/** 对可见交互控件检查可访问名称，并验证 Review 根节点没有水平溢出。 */
async function auditFrame(shell) {
  return shell.evaluate((node) => {
    const visible = (element) => {
      const style = globalThis.getComputedStyle(element);
      return (
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        element.getClientRects().length > 0
      );
    };
    const named = (element) => {
      const labelledBy = (element.getAttribute("aria-labelledby") ?? "")
        .split(/\s+/u)
        .filter(Boolean)
        .map((id) => globalThis.document.getElementById(id)?.textContent ?? "")
        .join(" ");
      const labelText =
        element instanceof globalThis.HTMLInputElement
          ? [...element.labels].map((label) => label.textContent ?? "").join(" ")
          : "";
      return [
        element.getAttribute("aria-label"),
        labelledBy,
        labelText,
        element.getAttribute("title"),
        element.textContent,
      ].some((value) => (value ?? "").trim().length > 0);
    };
    const controls = [
      ...node.querySelectorAll("button,input,[role='combobox'],[role='separator']"),
    ].filter(visible);
    const shellBounds = node.getBoundingClientRect();
    const controlFacts = controls.map((element) => {
      const bounds = element.getBoundingClientRect();
      const hitTarget =
        element instanceof globalThis.HTMLInputElement
          ? (element.closest("label") ?? element)
          : element;
      const hitBounds = hitTarget.getBoundingClientRect();
      const ellipsisManaged = [element, ...element.querySelectorAll("*")].some(
        (candidate) => globalThis.getComputedStyle(candidate).textOverflow === "ellipsis",
      );
      const labelText =
        element instanceof globalThis.HTMLInputElement
          ? [...element.labels].map((label) => label.textContent ?? "").join(" ")
          : "";
      return {
        name:
          element.getAttribute("aria-label") ??
          (labelText.trim() || undefined) ??
          element.getAttribute("title") ??
          element.textContent?.trim().slice(0, 80) ??
          element.tagName,
        width: bounds.width,
        height: bounds.height,
        hitWidth: hitBounds.width,
        hitHeight: hitBounds.height,
        sizeGate: element.matches("button,input,[role='combobox']"),
        contained:
          bounds.left >= shellBounds.left - 1 &&
          bounds.right <= shellBounds.right + 1 &&
          bounds.top >= shellBounds.top - 1 &&
          bounds.bottom <= shellBounds.bottom + 1,
        internalOverflow: element.scrollWidth > element.clientWidth + 1 && !ellipsisManaged,
      };
    });
    return {
      accessibleControls: controls.length,
      unnamedControls: controls.filter((element) => !named(element)).length,
      clippedControls: controlFacts.filter((control) => !control.contained),
      unusablySmallControls: controlFacts.filter(
        (control) => control.sizeGate && (control.hitWidth < 20 || control.hitHeight < 20),
      ),
      uncontrolledOverflowControls: controlFacts.filter((control) => control.internalOverflow),
      shellClientWidth: node.clientWidth,
      shellScrollWidth: node.scrollWidth,
      horizontalOverflow: node.scrollWidth > node.clientWidth + 1,
      layout: node.getAttribute("data-review-layout"),
    };
  });
}

/** 每帧以组件可见与布局收敛为就绪条件，避免 HMR 流量使 networkidle 永远不能达成。 */
async function captureFrame(browser, fixtureUrl, evidenceDirectory, frame, index, mode = "git") {
  const context = await browser.newContext({
    viewport: { width: frame.width, height: 760 },
    deviceScaleFactor: frame.devicePixelRatio,
    colorScheme: frame.systemColorScheme,
    reducedMotion: frame.reducedMotion ? "reduce" : "no-preference",
  });
  try {
    const page = await context.newPage();
    await page.goto(`${fixtureUrl}?theme=${frame.theme}&mode=${mode}`, {
      waitUntil: "domcontentloaded",
    });
    const shell = page.locator("[data-ja-review-shell]");
    await shell.waitFor({ state: "visible" });
    if (mode === "turn") {
      await page.waitForFunction(() => {
        const turnShell = globalThis.document.querySelector(
          "[data-ja-review-shell][data-turn-review-kind='frozen_turn']",
        );
        return turnShell?.querySelector("[data-review-unified-diff]") !== null;
      });
    }
    const expectedLayout = frame.width < 760 ? "narrow" : "wide";
    await assertLayout(shell, expectedLayout);
    const audit = await auditFrame(shell);
    const screenshot = `${mode === "turn" ? "turn" : "review"}-${String(index + 1).padStart(2, "0")}-${frame.width}-${frame.theme}-${frame.devicePixelRatio}x.png`;
    await page.screenshot({ path: join(evidenceDirectory, screenshot), fullPage: false });
    return {
      requestedWidth: frame.width,
      themeMode: frame.theme,
      devicePixelRatio: frame.devicePixelRatio,
      reducedMotion: frame.reducedMotion,
      dpiEvidence: "playwright_emulated",
      screenshot,
      ...audit,
    };
  } finally {
    await context.close();
  }
}

/** 追加六位统计和真实 Radix 双层菜单截图，覆盖矩阵中无法保留的瞬时状态。 */
async function captureSpecialEvidence(browser, fixtureUrl, evidenceDirectory) {
  const stressContext = await browser.newContext({ viewport: { width: 360, height: 760 } });
  let stress;
  try {
    const page = await stressContext.newPage();
    await page.goto(`${fixtureUrl}?theme=light&stats=stress`, { waitUntil: "domcontentloaded" });
    const shell = page.locator("[data-ja-review-shell]");
    await shell.waitFor({ state: "visible" });
    await assertLayout(shell, "narrow");
    const screenshot = "review-stress-360-light-six-digit-stats.png";
    stress = { screenshot, ...(await auditFrame(shell)) };
    await page.screenshot({ path: join(evidenceDirectory, screenshot), fullPage: false });
  } finally {
    await stressContext.close();
  }

  const menuContext = await browser.newContext({ viewport: { width: 520, height: 760 } });
  try {
    const page = await menuContext.newPage();
    await page.goto(`${fixtureUrl}?theme=light`, { waitUntil: "domcontentloaded" });
    const range = page.getByRole("button", { name: /^审阅范围：/u });
    await range.focus();
    await range.press("Enter");
    const rootMenu = page.locator("[role='menu'][data-state='open']").first();
    await rootMenu.waitFor({ state: "visible" });
    await waitForAnimations(page);
    const rootScreenshot = "review-menu-root-520-light.png";
    await page.screenshot({ path: join(evidenceDirectory, rootScreenshot), fullPage: false });
    const uncommitted = rootMenu.getByRole("menuitem").filter({ hasText: /^未提交$/u });
    await uncommitted.focus();
    await uncommitted.press("ArrowRight");
    const submenu = page.locator("[role='menu'][data-state='open']").nth(1);
    await submenu.waitFor({ state: "visible" });
    await submenu.getByRole("menuitem", { name: "全部", exact: true }).waitFor({
      state: "visible",
    });
    await waitForAnimations(page);
    const submenuScreenshot = "review-menu-uncommitted-520-light.png";
    await page.screenshot({ path: join(evidenceDirectory, submenuScreenshot), fullPage: false });
    return {
      stress,
      menu: { rootOpen: true, submenuOpen: true, rootScreenshot, submenuScreenshot },
    };
  } finally {
    await menuContext.close();
  }
}

/** 一帧必须同时满足根布局与逐控件几何约束，overflow:hidden 不能掩盖裁切。 */
function validateFrame(frame, label) {
  assert.equal(frame.horizontalOverflow, false, `${label} Review shell 水平溢出`);
  assert.equal(frame.unnamedControls, 0, `${label} 存在无可访问名称控件`);
  assert.deepEqual(frame.clippedControls, [], `${label} 存在被 shell 裁切的控件`);
  assert.deepEqual(frame.unusablySmallControls, [], `${label} 存在被挤压的控件`);
  assert.deepEqual(frame.uncontrolledOverflowControls, [], `${label} 存在未受控的控件内部溢出`);
}

/** 只有 Git 12 帧、Turn 四宽度和关键工作流全部闭合时，浏览器 fixture 才能独立通过。 */
function validateBrowserReport(report) {
  assert.equal(report.runtime, "browser_fixture");
  assert.equal(report.nativeVerified, false);
  assert.equal(report.nativeGitVerified, false);
  assert.equal(report.matrix.length, REVIEW_REDESIGN_MATRIX.length, "Git 视觉矩阵必须为 12 帧");
  assert.deepEqual(
    new Set(report.turnReview.matrix.map((frame) => frame.requestedWidth)),
    new Set([360, 520, 760, 1000]),
    "Turn 视觉矩阵必须覆盖四种容器宽度",
  );
  for (const required of [
    "rangeMenuPortal",
    "uncommittedSubmenu",
    "layeredIdentity",
    "search",
    "groupingSwitch",
    "narrowBack",
    "keyboardResize",
    "diffLayouts",
  ]) {
    assert.equal(report.interactions?.[required], true, `关键交互未闭合：${required}`);
  }
  report.matrix.forEach((frame, index) => validateFrame(frame, `Git frame ${index}`));
  report.turnReview.matrix.forEach((frame, index) => validateFrame(frame, `Turn frame ${index}`));
  validateFrame(report.specialEvidence.stress, "Git six-digit stress frame");
  assert.equal(report.specialEvidence.menu.rootOpen, true, "范围根菜单未打开");
  assert.equal(report.specialEvidence.menu.submenuOpen, true, "未提交子菜单未打开");
  return report;
}

/** 无论通过或失败都覆盖同一报告，避免调试轮次遗留空矩阵 PASS。 */
async function writeBrowserReport(path, report) {
  await writeFile(path, `${JSON.stringify(report, null, 2)}\n`, "utf8");
}

/** 执行真实生产组件的浏览器视觉矩阵，并以显式边界报告与 native 验收隔离。 */
async function main() {
  const options = parseArguments(process.argv.slice(2));
  await mkdir(options.evidenceDirectory, { recursive: true });
  const port = await reservePort();
  const fixtureUrl = `http://127.0.0.1:${port}${FIXTURE_PATH}`;
  const server = startFixtureServer(port);
  const reportPath = join(options.evidenceDirectory, "review-redesign-browser-report.json");
  const frames = [];
  const turnFrames = [];
  let interactions;
  let specialEvidence;
  let browser;
  try {
    await waitForFixtureServer(fixtureUrl, server);
    browser = await chromium.launch({ headless: true });
    if (process.env.JA_REVIEW_BROWSER_INTERACTIONS_ONLY !== "1") {
      for (const [index, frame] of REVIEW_REDESIGN_MATRIX.entries()) {
        frames.push(
          await captureFrame(browser, fixtureUrl, options.evidenceDirectory, frame, index),
        );
      }
      const turnMatrix = [
        REVIEW_REDESIGN_MATRIX[0],
        REVIEW_REDESIGN_MATRIX[4],
        REVIEW_REDESIGN_MATRIX[7],
        REVIEW_REDESIGN_MATRIX[11],
      ];
      for (const [index, frame] of turnMatrix.entries()) {
        turnFrames.push(
          await captureFrame(browser, fixtureUrl, options.evidenceDirectory, frame, index, "turn"),
        );
      }
      specialEvidence = await captureSpecialEvidence(
        browser,
        fixtureUrl,
        options.evidenceDirectory,
      );
    }
    const interactionContext = await browser.newContext({
      viewport: { width: 1000, height: 760 },
    });
    const interactionPage = await interactionContext.newPage();
    interactions = await verifyInteractions(interactionPage, fixtureUrl, options.evidenceDirectory);
    await interactionContext.close();
    const report = {
      contractVersion: 1,
      runtime: "browser_fixture",
      verdict: "BROWSER_FIXTURE_PASS",
      nativeVerified: false,
      nativeGitVerified: false,
      physicalDpiVerified: false,
      fixture: basename(FIXTURE_PATH),
      interactions,
      matrix: frames,
      turnReview: { status: "browser_fixture_visual_passed", matrix: turnFrames },
      specialEvidence,
    };
    validateBrowserReport(report);
    await writeBrowserReport(reportPath, report);
    process.stdout.write(
      `JA_REVIEW_REDESIGN_BROWSER_OK ${JSON.stringify({ report: reportPath, screenshots: frames.length + turnFrames.length + 3, nativeVerified: false })}\n`,
    );
  } catch (error) {
    await writeBrowserReport(reportPath, {
      contractVersion: 1,
      runtime: "browser_fixture",
      verdict: "NOT VERIFIED",
      nativeVerified: false,
      nativeGitVerified: false,
      physicalDpiVerified: false,
      fixture: basename(FIXTURE_PATH),
      interactions,
      matrix: frames,
      turnReview: { status: "not_verified", matrix: turnFrames },
      specialEvidence,
      failure: {
        name: error instanceof Error ? error.name : "Error",
        message: error instanceof Error ? error.message : "browser fixture failed",
      },
    });
    throw error;
  } finally {
    await browser?.close();
    await stopFixtureServer(server.child);
  }
}

await main();
