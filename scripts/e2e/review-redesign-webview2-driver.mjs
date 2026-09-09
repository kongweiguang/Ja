// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Review redesign 的 WebView2 驱动层。调用方必须提供已连接到真实 Tauri main WebView 的
 * Playwright Page；本模块只经可见控件和真实 Tauri invoke adapter 验收，不替换 Git 命令。
 */

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { basename, join } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const REVIEW_SHELL = "[data-ja-review-shell]";
const REVIEW_TREE = "[data-ja-review-tree]";
const REVIEW_FILE = "[data-review-file-id][data-review-layer]";
const REVIEW_GROUP = "[data-review-group]";
const STEP_TIMEOUT_MS = 30_000;

/**
 * 以 4 个容器宽度交叉 3 个主题；DPI 与 reduced motion 均匀轮换，明确形成 12 帧代表矩阵，
 * 不把它描述成 72 帧的全笛卡尔积。
 */
export const REVIEW_REDESIGN_MATRIX = Object.freeze(
  [360, 520, 760, 1000].flatMap((width, widthIndex) =>
    ["light", "dark", "system"].map((theme, themeIndex) =>
      Object.freeze({
        width,
        viewportWidth: width <= 520 ? 1280 : width === 760 ? 1600 : 2200,
        viewportHeight: 900,
        theme,
        systemColorScheme: theme === "system" ? "dark" : theme,
        devicePixelRatio: [1, 1.25, 1.5][(widthIndex + themeIndex) % 3],
        reducedMotion: (widthIndex + themeIndex) % 2 === 1,
      }),
    ),
  ),
);

/** 将每个 DOM/IPC 步骤限制在本地期限，避免一个缺失 selector 消耗整轮真窗预算。 */
function timeout(deadline) {
  return Math.max(1, Math.min(STEP_TIMEOUT_MS, deadline - Date.now()));
}

/**
 * 在 E2E bundle 的窄 nativeInvoke 接缝记录 Review 命令阶段；delegate 始终原样调用真实
 * Tauri command，记录中不保留 workspace、路径、revision、Diff 或错误正文。
 */
export function installReviewInvokeProbe() {
  const previous = globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__;
  globalThis.__JA_REVIEW_REDESIGN_COUNTS__ = Object.create(null);
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async (request, delegate) => {
    const observed = new Set([
      "ja_review_catalog",
      "ja_review_snapshot",
      "ja_review_file_diff",
      "ja_review_apply",
      "ja_review_cancel",
      "ja_turn_change_preview_open",
      "ja_turn_change_preview_read",
      "ja_turn_change_preview_close",
      "ja_turn_change_set_read",
    ]);
    const command = request?.command;
    const append = (phase) => {
      if (!observed.has(command)) return;
      const counts = globalThis.__JA_REVIEW_REDESIGN_COUNTS__;
      const bucket = counts[command] ?? { start: 0, resolved: 0, rejected: 0 };
      bucket[phase] += 1;
      counts[command] = bucket;
    };
    append("start");
    try {
      const result = previous === undefined ? await delegate() : await previous(request, delegate);
      append("resolved");
      return result;
    } catch (error) {
      append("rejected");
      throw error;
    }
  };
}

/** 只读取闭集计数，避免 E2E 报告携带任何 native 参数或响应。 */
async function reviewInvokeCounts(page) {
  return page.evaluate(() => {
    const source = globalThis.__JA_REVIEW_REDESIGN_COUNTS__ ?? {};
    return Object.fromEntries(
      Object.entries(source).map(([command, phases]) => [
        command,
        {
          start: Number(phases?.start ?? 0),
          resolved: Number(phases?.resolved ?? 0),
          rejected: Number(phases?.rejected ?? 0),
        },
      ]),
    );
  });
}

/** 读取单个命令阶段计数；不存在的命令按零处理。 */
function invokeCount(counts, command, phase = "start") {
  return Number(counts?.[command]?.[phase] ?? 0);
}

/** 等待真实 runtime 和 React Shell 就绪，失败时不读取页面正文。 */
async function waitForApplication(page, deadline) {
  await page.locator('.ja-shell[data-app-ready="true"]').waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
  await page.getByRole("status", { name: "本地运行时：已连接", exact: true }).waitFor({
    state: "visible",
    timeout: timeout(deadline),
  });
}

/**
 * 经 E2E composition 的确定性 picker 点击真实“添加项目”；picker 只选择隔离路径，后续
 * Workspace identity、Git snapshot 和 Diff 仍全部由 Java/Rust/Tauri 生产链路签发。
 */
async function selectFixtureProject(page, deadline) {
  const selected = page.locator(
    '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
  );
  if ((await selected.count()) === 0) {
    await page.getByRole("button", { name: "添加项目", exact: true }).click({
      timeout: timeout(deadline),
    });
  }
  await selected.waitFor({ state: "visible", timeout: timeout(deadline) });
}

/** 通过 Workbench 的真实 Tab/菜单打开 Review，不直接改 React 状态或 localStorage。 */
async function openReview(page, deadline) {
  const showWorkbench = page.getByRole("button", { name: "显示工作区面板", exact: true });
  if (await showWorkbench.isVisible().catch(() => false)) {
    await showWorkbench.click({ timeout: timeout(deadline) });
  }
  let tab = page.locator('[data-workbench-tab="review"]');
  if ((await tab.count()) === 0) {
    await page.getByRole("button", { name: "新建标签页", exact: true }).click({
      timeout: timeout(deadline),
    });
    await page
      .getByRole("menuitem")
      .filter({ hasText: "审查" })
      .first()
      .click({
        timeout: timeout(deadline),
      });
    tab = page.locator('[data-workbench-tab="review"]');
  }
  await tab.click({ timeout: timeout(deadline) });
  const panel = page.locator('[data-tab-panel="review"]');
  await panel.waitFor({ state: "visible", timeout: timeout(deadline) });
  return page.locator(REVIEW_SHELL);
}

/** Radix Select option 位于 portal；分组和主题切换都从可访问 trigger 出发。 */
async function chooseSelectOption(page, trigger, optionName, deadline) {
  await trigger.click({ timeout: timeout(deadline) });
  await page.getByRole("option", { name: optionName, exact: true }).click({
    timeout: timeout(deadline),
  });
}

/**
 * 范围入口名称包含当前范围，因此只接受产品发布的“审阅范围：”前缀；不退回到任意按钮，
 * 避免把文件分组或 Diff 模式误当成来源入口。
 */
async function reviewRangeTrigger(shell) {
  const candidate = shell.getByRole("button", { name: /^审阅范围：/u });
  if ((await candidate.count()) !== 1) throw new Error("Review 缺少唯一的审阅范围菜单按钮");
  return candidate;
}

/** 父级“未提交”是 MenuSubTrigger，必须显式打开子菜单后选择聚合的“全部”范围。 */
async function chooseAllUncommitted(page, shell, deadline) {
  const trigger = await reviewRangeTrigger(shell);
  await trigger.click({ timeout: timeout(deadline) });
  const submenuTrigger = page.getByRole("menuitem", { name: /未提交/u, exact: false });
  await submenuTrigger.waitFor({ state: "visible", timeout: timeout(deadline) });
  await submenuTrigger.hover({ timeout: timeout(deadline) });
  const submenu = page.getByRole("menu", { name: "未提交范围", exact: true });
  if (!(await submenu.isVisible().catch(() => false))) {
    await submenuTrigger.focus();
    await submenuTrigger.press("ArrowRight");
  }
  await submenu.getByRole("menuitem", { name: "全部", exact: true }).click({
    timeout: timeout(deadline),
  });
}

/** 读取稳定文件身份，不复制完整路径到报告。 */
async function fileIdentity(file) {
  return {
    fileId: await file.getAttribute("data-review-file-id"),
    layer: await file.getAttribute("data-review-layer"),
    name: basename((await file.getAttribute("aria-label")) ?? "unknown"),
  };
}

/** 等待 shell 暴露与当前选择一致的 Diff 身份，严格捕获旧请求晚到覆盖新选择。 */
async function waitForSelectedIdentity(page, expected, deadline) {
  await page.waitForFunction(
    ({ selector, fileId, layer }) => {
      const shell = globalThis.document.querySelector(selector);
      const diff = shell?.querySelector(
        "[data-review-selected-file-id][data-review-selected-layer]",
      );
      return (
        diff?.getAttribute("data-review-selected-file-id") === fileId &&
        diff?.getAttribute("data-review-selected-layer") === layer &&
        diff?.querySelector('[role="status"]') === null
      );
    },
    { selector: REVIEW_SHELL, fileId: expected.fileId, layer: expected.layer },
    { timeout: timeout(deadline) },
  );
}

/** 验证同一路径 staged/unstaged 两个稳定身份均可点击并显示自己的 Diff。 */
async function verifyLayeredFileSelection(page, shell, deadline) {
  const candidates = shell.locator(REVIEW_FILE).filter({ hasText: "partially-staged.ts" });
  await candidates.first().waitFor({ state: "visible", timeout: timeout(deadline) });
  const count = await candidates.count();
  assert.equal(count, 2, "部分暂存文件必须按两个 layer 展示");
  const identities = [];
  for (let index = 0; index < count; index += 1) {
    const row = candidates.nth(index);
    const identity = await fileIdentity(row);
    identities.push(identity);
    await row.click({ timeout: timeout(deadline) });
    await waitForSelectedIdentity(page, identity, deadline);
  }
  assert.deepEqual(new Set(identities.map(({ layer }) => layer)), new Set(["staged", "unstaged"]));
  return identities;
}

/** 连续点击两个真实文件且不等待首个 Diff，最终身份必须严格等于第二个。 */
async function verifyLatestWinsSelection(page, shell, deadline) {
  const files = shell.locator(REVIEW_FILE);
  assert.ok((await files.count()) >= 3, "快速选择至少需要三个真实文件");
  const first = files.nth(0);
  const second = files.nth(2);
  const expected = await fileIdentity(second);
  await first.click({ noWaitAfter: true, timeout: timeout(deadline) });
  await second.click({ noWaitAfter: true, timeout: timeout(deadline) });
  await waitForSelectedIdentity(page, expected, deadline);
  return expected;
}

/** 搜索期间允许临时展开祖先，清空后必须恢复用户先前折叠状态。 */
async function verifySearchRestoresExpansion(page, shell, deadline) {
  const group = shell.locator(`${REVIEW_GROUP}[aria-expanded="true"]`).first();
  await group.waitFor({ state: "visible", timeout: timeout(deadline) });
  const rowId = await group.locator("xpath=..").getAttribute("data-row-id");
  assert.ok(rowId, "搜索恢复验收缺少稳定 group row identity");
  await group.click({ timeout: timeout(deadline) });
  assert.equal(await group.getAttribute("aria-expanded"), "false");
  const search = shell.getByRole("searchbox", { name: "筛选文件", exact: true });
  await search.fill("created.ts");
  await shell
    .locator(REVIEW_FILE)
    .filter({ hasText: "created.ts" })
    .waitFor({
      state: "visible",
      timeout: timeout(deadline),
    });
  await search.fill("");
  await page.waitForFunction(
    ({ selector, identity, value }) => {
      const row = [...globalThis.document.querySelectorAll(selector)].find(
        (candidate) => candidate.getAttribute("data-row-id") === identity,
      );
      return row?.querySelector("[aria-expanded]")?.getAttribute("aria-expanded") === value;
    },
    { selector: "[data-row-id]", identity: rowId, value: "false" },
    { timeout: timeout(deadline) },
  );
  return { restored: true, groupIdentity: rowId };
}

/** 三种分组均经真实 Select 切换；选择身份在投影变化期间不得被清空。 */
async function verifyGroupingModes(page, shell, deadline) {
  const trigger = shell.getByRole("combobox", { name: "文件分组", exact: true });
  const modes = ["目录", "平铺", "状态与目录"];
  const selectedDiff = shell.locator("[data-review-selected-file-id][data-review-selected-layer]");
  const selectedBefore = await selectedDiff.getAttribute("data-review-selected-file-id");
  assert.ok(selectedBefore, "切换分组前缺少所选文件 identity");
  for (const mode of modes) {
    await chooseSelectOption(page, trigger, mode, deadline);
    assert.equal(await selectedDiff.getAttribute("data-review-selected-file-id"), selectedBefore);
  }
  await shell.getByRole("button", { name: "全部折叠", exact: true }).click({
    timeout: timeout(deadline),
  });
  const groups = await shell
    .locator(REVIEW_GROUP)
    .evaluateAll((nodes) =>
      nodes.map((node) => node.getAttribute("data-review-group")).filter(Boolean),
    );
  for (const required of ["conflicted", "unstaged", "staged", "untracked"]) {
    assert.ok(groups.includes(required), `状态分组缺少 ${required}`);
  }
  return { modes, groups };
}

/**
 * 通过 Workbench 外层真实 pointer resize 调整 Review 容器；最多三次几何校正，禁止直接写
 * CSS variable 冒充用户拖动。
 */
async function resizeReviewContainer(page, shell, targetWidth, deadline) {
  const handle = page.getByRole("separator", { name: "调整工作台宽度", exact: true });
  await handle.waitFor({ state: "visible", timeout: timeout(deadline) });
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const shellBox = await shell.boundingBox();
    const handleBox = await handle.boundingBox();
    if (shellBox === null || handleBox === null) throw new Error("Review resize 几何不可用");
    if (Math.abs(shellBox.width - targetWidth) <= 1.5) break;
    const delta = shellBox.width - targetWidth;
    const startX = handleBox.x + handleBox.width / 2;
    const y = handleBox.y + handleBox.height / 2;
    await page.mouse.move(startX, y);
    await page.mouse.down();
    await page.mouse.move(startX + delta, y, { steps: 6 });
    await page.mouse.up();
  }
  const box = await shell.boundingBox();
  if (box === null || Math.abs(box.width - targetWidth) > 2) {
    throw new Error(`Review 容器宽度未收敛：target=${targetWidth} actual=${box?.width}`);
  }
  const expectedLayout = targetWidth < 760 ? "narrow" : "wide";
  await page.waitForFunction(
    ({ selector, expected }) =>
      globalThis.document.querySelector(selector)?.getAttribute("data-review-layout") === expected,
    { selector: REVIEW_SHELL, expected: expectedLayout },
    { timeout: timeout(deadline) },
  );
  return { requestedWidth: targetWidth, actualWidth: box.width, layout: expectedLayout };
}

/** 所有主题与减少动效均通过真实 Settings 控件持久化，不直接写 DOM 属性。 */
async function applyAppearance(page, frame, deadline) {
  await page.emulateMedia({
    colorScheme: frame.systemColorScheme,
    reducedMotion: frame.reducedMotion ? "reduce" : "no-preference",
  });
  await page
    .getByRole("button", { name: "设置", exact: true })
    .click({ timeout: timeout(deadline) });
  const settings = page.getByRole("region", { name: "设置页面", exact: true });
  await settings.waitFor({ state: "visible", timeout: timeout(deadline) });
  await settings
    .getByRole("tab", { name: "外观", exact: true })
    .click({ timeout: timeout(deadline) });
  const themeOption =
    frame.theme === "light" ? "浅色" : frame.theme === "dark" ? "深色" : "跟随系统";
  await chooseSelectOption(
    page,
    settings.getByRole("combobox", { name: "外观模式", exact: true }),
    themeOption,
    deadline,
  );
  const motion = settings.getByRole("switch", { name: "减少动效", exact: true });
  const checked = (await motion.getAttribute("aria-checked")) === "true";
  if (checked !== frame.reducedMotion) await motion.click({ timeout: timeout(deadline) });
  await page.waitForFunction(
    ({ mode, reduced }) => {
      const root = globalThis.document.documentElement;
      return (
        root.getAttribute("data-theme-mode") === mode &&
        root.getAttribute("data-reduce-motion") === String(reduced) &&
        globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches === reduced
      );
    },
    { mode: frame.theme, reduced: frame.reducedMotion },
    { timeout: timeout(deadline) },
  );
  await settings.getByRole("button", { name: "返回应用", exact: true }).click({
    timeout: timeout(deadline),
  });
  return page.locator(REVIEW_SHELL);
}

/** 采集一帧布局、主题、DPI、焦点和 overflow 事实，并把截图写入显式证据目录。 */
async function captureMatrixFrame(page, client, shell, frame, evidenceDirectory, deadline) {
  await client.send("Emulation.setDeviceMetricsOverride", {
    width: frame.viewportWidth,
    height: frame.viewportHeight,
    deviceScaleFactor: frame.devicePixelRatio,
    mobile: false,
  });
  await page.waitForFunction(
    ({ width, ratio }) =>
      globalThis.innerWidth === width && Math.abs(globalThis.devicePixelRatio - ratio) <= 0.000001,
    { width: frame.viewportWidth, ratio: frame.devicePixelRatio },
    { timeout: timeout(deadline) },
  );
  shell = await applyAppearance(page, frame, deadline);
  const geometry = await resizeReviewContainer(page, shell, frame.width, deadline);
  const tree = shell.locator(REVIEW_TREE);
  await tree.waitFor({ state: "visible", timeout: timeout(deadline) });
  await tree.locator('[role="treeitem"]').first().focus();
  await tree.locator('[role="treeitem"]').first().press("ArrowDown");
  const facts = await shell.evaluate((node, expected) => {
    const root = globalThis.document.documentElement;
    const visible = (element) => {
      const style = globalThis.getComputedStyle(element);
      return (
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        element.getClientRects().length > 0
      );
    };
    const referencedText = (element) =>
      (element.getAttribute("aria-labelledby") ?? "")
        .split(/\s+/u)
        .filter(Boolean)
        .map((id) => globalThis.document.getElementById(id)?.textContent?.trim() ?? "")
        .join(" ")
        .trim();
    const accessibleName = (element) => {
      const labels =
        "labels" in element && element.labels !== null
          ? [...element.labels]
              .map((label) => label.textContent?.trim() ?? "")
              .join(" ")
              .trim()
          : "";
      return (
        element.getAttribute("aria-label")?.trim() ||
        referencedText(element) ||
        labels ||
        element.textContent?.trim() ||
        element.getAttribute("title")?.trim() ||
        ""
      );
    };
    const unnamed = [
      ...node.querySelectorAll(
        "button, input, select, textarea, [role='button'], [role='combobox']",
      ),
    ].filter((element) => visible(element) && accessibleName(element).length === 0).length;
    return {
      themeMode: root.getAttribute("data-theme-mode"),
      theme: root.getAttribute("data-theme"),
      reducedMotion: root.getAttribute("data-reduce-motion") === "true",
      mediaReducedMotion: globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches,
      devicePixelRatio: globalThis.devicePixelRatio,
      horizontalOverflow: node.scrollWidth - node.clientWidth,
      documentOverflow:
        globalThis.document.documentElement.scrollWidth -
        globalThis.document.documentElement.clientWidth,
      unnamedInteractiveCount: unnamed,
      dpiEvidence: "cdp_emulated",
      expected,
    };
  }, frame);
  assert.equal(facts.themeMode, frame.theme);
  assert.equal(facts.reducedMotion, frame.reducedMotion);
  assert.equal(facts.mediaReducedMotion, frame.reducedMotion);
  assert.ok(Math.abs(facts.devicePixelRatio - frame.devicePixelRatio) <= 0.000001);
  assert.ok(facts.horizontalOverflow <= 1, "Review 容器存在水平溢出");
  assert.ok(facts.documentOverflow <= 1, "文档存在水平溢出");
  assert.equal(facts.unnamedInteractiveCount, 0);
  const fileName = `${frame.width}-${frame.theme}-dpi${String(frame.devicePixelRatio).replace(".", "_")}-${frame.reducedMotion ? "reduced" : "normal"}.png`;
  await shell.screenshot({ path: join(evidenceDirectory, fileName) });
  return { ...geometry, ...facts, screenshot: fileName };
}

/** 不只点击布局按钮，还验证真实双栏、单列行号与零额外读取，防止静默回退掩盖失效。 */
async function verifyWideControls(page, shell, deadline, evidenceDirectory) {
  const resizer = shell.locator('[data-ja-review-tree-resizer][aria-label="调整文件树宽度"]');
  await resizer.waitFor({ state: "visible", timeout: timeout(deadline) });
  const before = await resizer.getAttribute("aria-valuenow");
  await resizer.focus();
  await resizer.press("ArrowLeft");
  const after = await resizer.getAttribute("aria-valuenow");
  assert.notEqual(after, before, "文件树键盘 resize 未改变宽度");
  for (const name of ["上一个文件", "下一个文件", "双栏 Diff", "统一 Diff"]) {
    const control = shell.getByRole("button", { name, exact: true });
    assert.equal(await control.count(), 1, `缺少 ${name} 控件`);
  }
  const countsBefore = await reviewInvokeCounts(page);
  await shell.getByRole("button", { name: "双栏 Diff", exact: true }).click({
    timeout: timeout(deadline),
  });
  const split = shell.locator('[data-review-diff-mode="split"]');
  await split.waitFor({ state: "visible", timeout: timeout(deadline) });
  const oldCell = split.locator('[data-review-diff-side="old"]').first();
  const newCell = split.locator('[data-review-diff-side="new"]').first();
  await oldCell.waitFor({ state: "visible", timeout: timeout(deadline) });
  const oldBox = await oldCell.boundingBox();
  const newBox = await newCell.boundingBox();
  assert.ok(oldBox && newBox && oldBox.x + oldBox.width <= newBox.x + 1, "双栏没有左右排列");
  await split.screenshot({ path: join(evidenceDirectory, "layout-split.png") });
  await shell.getByRole("button", { name: "统一 Diff", exact: true }).click({
    timeout: timeout(deadline),
  });
  const unified = shell.locator('[data-review-diff-mode="unified"]');
  await unified.waitFor({ state: "visible", timeout: timeout(deadline) });
  const numberCounts = await unified
    .locator(".is-line")
    .evaluateAll((rows) =>
      rows.map((row) => row.querySelectorAll(".ja-review-unified-diff-number").length),
    );
  assert.ok(
    numberCounts.length > 0 && numberCounts.every((count) => count === 1),
    "统一视图重复行号",
  );
  await unified.screenshot({ path: join(evidenceDirectory, "layout-unified.png") });
  const countsAfter = await reviewInvokeCounts(page);
  for (const command of ["ja_review_snapshot", "ja_review_file_diff"])
    assert.equal(
      invokeCount(countsAfter, command),
      invokeCount(countsBefore, command),
      "布局切换额外读取文件",
    );
  await shell.getByRole("button", { name: "下一个文件", exact: true }).click({
    timeout: timeout(deadline),
  });
  return { treeResizeKeyboard: true, diffModes: ["split", "unified"], fileNavigation: true };
}

/** 窄栏必须先显示文件树，选文件后进入 Diff，并由明确返回动作恢复同一树。 */
async function verifyNarrowNavigation(page, shell, deadline) {
  assert.equal(await shell.getAttribute("data-review-layout"), "narrow");
  const file = shell.locator(REVIEW_FILE).first();
  const identity = await fileIdentity(file);
  await file.click({ timeout: timeout(deadline) });
  await waitForSelectedIdentity(page, identity, deadline);
  const back = shell.getByRole("button", { name: "返回变更文件", exact: true });
  await back.waitFor({ state: "visible", timeout: timeout(deadline) });
  await back.click({ timeout: timeout(deadline) });
  await shell.locator(REVIEW_TREE).waitFor({ state: "visible", timeout: timeout(deadline) });
  return { treeToDiffToTree: true };
}

/** 用真实 git status 证明页面操作没有把 fixture 偷换成 mock 数据。 */
async function captureGitFacts(workspaceRoot) {
  const { stdout } = await execFileAsync("git.exe", ["status", "--porcelain=v2", "--branch"], {
    cwd: workspaceRoot,
    windowsHide: true,
    maxBuffer: 2 * 1024 * 1024,
    timeout: 15_000,
  });
  return {
    repositoryObserved: stdout.includes("# branch.head"),
    conflictObserved: stdout.split(/\r?\n/u).some((line) => line.startsWith("u ")),
    statusRecordCount: stdout.split(/\r?\n/u).filter((line) => /^[12u?] /u.test(line)).length,
  };
}

/**
 * 执行 Git Review 重设计真窗验收。scope=full 还要求调用方先在真实 UI 建立最后一轮入口；
 * 本 driver 不自行伪造 Provider Turn，因此缺失 Turn 证据时报告 NOT VERIFIED。
 */
export async function runReviewRedesignWebView2({
  page,
  workspaceRoot,
  evidenceDirectory,
  scope,
  deadlineMs = 15 * 60_000,
}) {
  assert.ok(page, "page is required");
  assert.ok(["git", "full"].includes(scope), "scope must be git or full");
  await mkdir(evidenceDirectory, { recursive: true });
  const deadline = Date.now() + deadlineMs;
  await page.context().addInitScript(installReviewInvokeProbe);
  await page.reload({ waitUntil: "domcontentloaded", timeout: timeout(deadline) });
  await waitForApplication(page, deadline);
  const hiddenBefore = await reviewInvokeCounts(page);
  await selectFixtureProject(page, deadline);
  await page.waitForTimeout(750);
  const hiddenAfter = await reviewInvokeCounts(page);
  const hiddenSnapshotDelta =
    invokeCount(hiddenAfter, "ja_review_snapshot") -
    invokeCount(hiddenBefore, "ja_review_snapshot");
  assert.equal(hiddenSnapshotDelta, 0, "隐藏 Review 在项目切换时发起 snapshot");

  let shell = await openReview(page, deadline);
  await shell.waitFor({ state: "visible", timeout: timeout(deadline) });
  await chooseAllUncommitted(page, shell, deadline);
  await shell.locator(REVIEW_TREE).waitFor({ state: "visible", timeout: timeout(deadline) });

  const layeredSelection = await verifyLayeredFileSelection(page, shell, deadline);
  const latestWins = await verifyLatestWinsSelection(page, shell, deadline);
  const search = await verifySearchRestoresExpansion(page, shell, deadline);
  const grouping = await verifyGroupingModes(page, shell, deadline);
  const git = await captureGitFacts(workspaceRoot);
  assert.equal(git.repositoryObserved, true);
  assert.equal(git.conflictObserved, true);

  const matrix = [];
  const cdp = await page.context().newCDPSession(page);
  let narrowNavigation;
  let wideControls;
  for (const frame of REVIEW_REDESIGN_MATRIX) {
    shell = page.locator(REVIEW_SHELL);
    const evidence = await captureMatrixFrame(page, cdp, shell, frame, evidenceDirectory, deadline);
    matrix.push(evidence);
    if (frame.width === 360 && narrowNavigation === undefined) {
      narrowNavigation = await verifyNarrowNavigation(page, shell, deadline);
    }
    if (frame.width === 1000 && wideControls === undefined) {
      wideControls = await verifyWideControls(page, shell, deadline, evidenceDirectory);
    }
  }

  const counts = await reviewInvokeCounts(page);
  assert.ok(invokeCount(counts, "ja_review_snapshot", "resolved") >= 1);
  assert.ok(invokeCount(counts, "ja_review_file_diff", "resolved") >= 2);
  const turnReviewVisible = (await page.locator("[data-turn-review-kind]").count()) > 0;
  const report = {
    contractVersion: 1,
    runtime: "tauri_webview2",
    gitAdapter: "real_native",
    scope,
    verdict: scope === "full" && turnReviewVisible ? "PASS" : "NOT VERIFIED",
    gitReview: {
      status: "passed",
      hiddenSnapshotDelta,
      layeredSelection,
      latestWins,
      search,
      grouping,
      narrowNavigation,
      wideControls,
      git,
      invokeCounts: counts,
      matrix,
    },
    turnReview: turnReviewVisible
      ? { status: "observed", note: "requires dedicated Turn lifecycle report for full acceptance" }
      : { status: "not_verified", reason: "no deterministic Turn fixture in this session" },
  };
  validateReviewRedesignReport(report, { requestedScope: scope });
  return report;
}

/**
 * 报告验证器不允许 Git-only 或仅可见 Turn surface 冒充完整 PASS；full 必须由后续专用 Turn
 * 生命周期报告扩展并将 turnReview.status 提升为 passed。
 */
export function validateReviewRedesignReport(report, { requestedScope }) {
  assert.equal(report?.contractVersion, 1);
  assert.equal(report?.runtime, "tauri_webview2");
  assert.equal(report?.gitAdapter, "real_native");
  assert.equal(report?.scope, requestedScope);
  assert.equal(report?.gitReview?.status, "passed");
  assert.equal(report?.gitReview?.hiddenSnapshotDelta, 0);
  assert.equal(report?.gitReview?.matrix?.length, REVIEW_REDESIGN_MATRIX.length);
  const widths = new Set(report.gitReview.matrix.map(({ requestedWidth }) => requestedWidth));
  const themes = new Set(report.gitReview.matrix.map(({ themeMode }) => themeMode));
  const ratios = new Set(report.gitReview.matrix.map(({ devicePixelRatio }) => devicePixelRatio));
  assert.deepEqual(widths, new Set([360, 520, 760, 1000]));
  assert.deepEqual(themes, new Set(["light", "dark", "system"]));
  assert.deepEqual(ratios, new Set([1, 1.25, 1.5]));
  assert.ok(report.gitReview.matrix.every(({ dpiEvidence }) => dpiEvidence === "cdp_emulated"));
  assert.ok(report.gitReview.matrix.some(({ reducedMotion }) => reducedMotion));
  if (requestedScope === "full") {
    assert.equal(report.turnReview?.status, "passed", "完整验收缺少 Turn lifecycle 证据");
    assert.equal(report.verdict, "PASS");
  } else {
    assert.notEqual(report.verdict, "PASS", "Git-only 不得冒充完整 PASS");
  }
  return report;
}
