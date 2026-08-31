// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 仅在 Windows 上运行的桌面冒烟测试，覆盖真实的 Tauri -> Rust -> Java JSONL 链路。
 * runner 刻意放在产品组合根之外，从而无需为测试新增协议，也能验证仅调试环境开放的启动接缝。
 */

import { execFile, spawn } from "node:child_process";
import { Buffer } from "node:buffer";
import { copyFile, mkdtemp, mkdir, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { createServer as createHttpServer } from "node:http";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import process from "node:process";
import { dirname, isAbsolute, join, parse, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { chromium } from "@playwright/test";

const execFileAsync = promisify(execFile);
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const desktopSmokeLockDirectory = join(tmpdir(), "ja-desktop-e2e-runner.lock");
let java25Home;
let java25;
const configuredRealProviderMode = process.env.JA_E2E_REAL_PROVIDER === "1";
const automaticTitleAcceptanceMode = process.env.JA_E2E_AUTOMATIC_TITLE === "1";
const realProviderMode = configuredRealProviderMode || automaticTitleAcceptanceMode;
const runDeadlineMs = 900_000;
const cdpStartupDeadlineMs = 120_000;
const turnDeadlineMs = realProviderMode ? 180_000 : 30_000;
const closeDeadlineMs = 20_000;
const pollMs = 1_000;
const snapshotTimeoutMs = 15_000;
// 多个同名托盘项需要逐个打开菜单并复验 owner；该预算只包围有界 UIA 脚本，不扩大产品关闭期限。
const trayExitTimeoutMs = 30_000;
const incompleteObservationLimit = 64;
const stablePortRange = Object.freeze({ start: 41_000, size: 8_000 });
const directCdpBrowserArguments =
  "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --remote-debugging-port=0";

/**
 * 把 Wry 生产默认参数与官方 attach 所需的调试参数绑定到预留回环端口。Direct CDP
 * 不是 WebDriver launch，不能注入其后台、网络与自动化开关；端口仍由进程 owner 复验。
 */
function directCdpBrowserArgumentsForPort(cdpPort) {
  if (!Number.isSafeInteger(cdpPort) || cdpPort < 1 || cdpPort > 65_535) {
    throw new Error("WebView2 CDP 端口无效");
  }
  return directCdpBrowserArguments.replace(
    "--remote-debugging-port=0",
    `--remote-debugging-port=${cdpPort}`,
  );
}
// 原生输入刻意使用 PowerShell 7，使冒烟测试遵循 Ja 的 Windows shell 策略；
// 命令查找仍交给 Windows PATH，且绝不隐式启用额外 shell。
const nativeInputPowerShell = "pwsh.exe";
// 重复越过 Windows 注册槽位边界，以充分暴露泄漏的注册，同时保证真窗冒烟仍受单次运行时限约束。
const nativeShortcutRegistrationChurnCycles = 12;
// 将产品的五个公开原生命令及其 Win32 虚拟键组合固化在同一语料中，统一断言事件顺序、焦点目标和 PTY 无干扰性。
// Side Chat 显式使用左 Alt：通用 VK_MENU 会受当前键盘布局解析影响，可能间歇性漏掉已聚焦的子 WebView；
// 右 Alt 则保留给 AltGr 验收。
const nativeShortcutCases = Object.freeze([
  Object.freeze({
    command: "review",
    key: 0x47,
    modifiers: Object.freeze([0x11, 0x10]),
    tab: "review",
  }),
  Object.freeze({
    command: "terminal",
    key: 0xc0,
    modifiers: Object.freeze([0x11]),
    tab: "terminal",
  }),
  Object.freeze({
    command: "preview",
    key: 0x54,
    modifiers: Object.freeze([0x11]),
    tab: "preview",
  }),
  Object.freeze({ command: "files", key: 0x50, modifiers: Object.freeze([0x11]), tab: "files" }),
  Object.freeze({ command: "side_chat", key: 0x53, modifiers: Object.freeze([0x11, 0xa4]) }),
]);
const visualEvidenceDirectory = process.env.JA_E2E_SCREENSHOT_DIR?.trim() || undefined;
const visualTheme = process.env.JA_E2E_THEME === "light" ? "light" : "dark";
const shellOnlyMode = process.env.JA_E2E_SHELL_ONLY === "1";
const allowTrashCommit = process.env.JA_E2E_ALLOW_TRASH === "1";
const configuredEdgeDriverPath = process.env.JA_E2E_EDGEDRIVER_PATH?.trim() || undefined;
const edgeDriverRunnerPath = join(repoRoot, "scripts", "e2e", "webview2-edgedriver-runner.cmd");
const edgeDriverProfileDirectoryName = "edgedriver-profile";

/**
 * 生成 runner 私有 WebView2 profile。该路径只交给 EdgeDriver 的 webviewOptions，
 * 避免 Tauri overlay 与 driver 同时声明 profile owner。
 */
function edgeDriverDataDirectory(directories) {
  return join(directories.local, "main", edgeDriverProfileDirectoryName);
}

/** 解析调用方选定的 Java home/可执行文件，并证明真实桌面 jar 确实使用 Java 25。 */
async function ensureJava25Runtime() {
  if (java25Home !== undefined && java25 !== undefined)
    return { home: java25Home, executable: java25 };
  const configuredExecutable = process.env.JA_TEST_JAVA?.trim();
  const configuredHome = process.env.JA_E2E_JAVA_HOME?.trim() || process.env.JAVA_HOME?.trim();
  const executable =
    configuredExecutable ||
    (configuredHome === undefined ? undefined : join(configuredHome, "bin", "java.exe"));
  if (executable === undefined || executable.length === 0) {
    throw new Error("desktop smoke requires JA_E2E_JAVA_HOME or JA_TEST_JAVA");
  }
  const home = configuredHome ?? resolve(dirname(executable), "..");
  let output = "";
  try {
    const version = await execFileAsync(executable, ["-version"], {
      windowsHide: true,
      maxBuffer: 64 * 1024,
    });
    output = `${version.stdout ?? ""}\n${version.stderr ?? ""}`;
  } catch (error) {
    output = `${error?.stdout ?? ""}\n${error?.stderr ?? ""}`;
    if (output.trim() === "") throw new Error("desktop smoke Java executable could not be started");
  }
  const match = output.match(/\bversion\s+"?(\d+)/iu) ?? output.match(/\bopenjdk\s+(\d+)/iu);
  if (match === null || Number(match[1]) !== 25) {
    throw new Error("desktop smoke requires Java major version 25");
  }
  java25Home = resolve(home);
  java25 = resolve(executable);
  return { home: java25Home, executable: java25 };
}

/** 只接受验收矩阵声明的 Windows 缩放档位，避免任意浮点值把同 viewport 比较伪装成有效证据。 */
function readNativeDevicePixelRatio() {
  const value = Number(process.env.JA_E2E_DEVICE_PIXEL_RATIO ?? "1");
  if (![1, 1.25, 1.5].includes(value))
    throw new Error("JA_E2E_DEVICE_PIXEL_RATIO 只允许 1、1.25 或 1.5");
  return value;
}

const nativeVisualViewport = Object.freeze({
  width: 2560,
  height: 1392,
  devicePixelRatio: readNativeDevicePixelRatio(),
});
const responsiveRendererMatrix = Object.freeze([
  Object.freeze({ width: 1920, height: 1080 }),
  Object.freeze({ width: 1440, height: 900 }),
  Object.freeze({ width: 1280, height: 800 }),
]);
const requireReviewEvidence =
  visualEvidenceDirectory !== undefined || process.env.JA_E2E_REQUIRE_REVIEW === "1";
const approvalFixtureInput = "__JA_FAKE_APPROVAL_FIXTURE__";
const approvalFixtureVisibleInput = "JA_FAKE_APPROVAL_FIXTURE";
const approvalFixtureReason = "Tool requires approval";
const approvalFixtureTool = "shell";
const approvalFixtureCallId = "call_fake_shell";
const businessFixtureFile = "business-context.md";
const attachmentFixtureFile = "attachment-e2e.txt";
const businessExpectedFinal = "ORBIT-7429|DUAL_APPROVAL|48H";
const businessPrompt =
  "必须先调用 read 工具读取项目根目录 business-context.md，再严格按文件中的“验收输出”回复一行；不要添加解释。";
const frozenExitStageSequence = [
  "exit_requested_enter",
  "exit_requested_return",
  "exit_enter",
  "exit_return",
];
const frozenExitStages = new Set(frozenExitStageSequence);
const snapshotHelpers = new Set();
const sensitiveRedactions = new Set();
let snapshotTail = Promise.resolve();
let visualEvidenceRunDirectory;

/**
 * Windows 下将拒绝访问视为进程仍存活，避免无法检查的 PID 授权删除其他 runner 的锁。
 * 只有 ESRCH 能安全证明锁已过期，其他失败一律保持故障关闭。
 */
function desktopSmokeOwnerIsAlive(pid) {
  if (!Number.isSafeInteger(pid) || pid < 1) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code !== "ESRCH";
  }
}

/** 只读取原子 runner 锁写入的进程身份，避免把锁目录中的其他内容误当成所有权证据。 */
async function readDesktopSmokeLockOwner() {
  try {
    const parsed = JSON.parse(
      await readFile(join(desktopSmokeLockDirectory, "owner.json"), "utf8"),
    );
    return Number.isSafeInteger(parsed?.pid) && typeof parsed?.startedAt === "string"
      ? { pid: parsed.pid, startedAt: parsed.startedAt }
      : undefined;
  } catch {
    return undefined;
  }
}

/**
 * 在分配端口、Cargo 或 HWND 之前串行化整轮 Windows 冒烟测试。
 * 原子目录可跨独立 Codex cell 协调；近期且无 owner 的目录仍视为正在抢占，
 * 只有陈旧或 owner 已退出的锁才会回收，防止并发清理误杀另一轮有效运行。
 */
async function acquireDesktopSmokeLock() {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      await mkdir(desktopSmokeLockDirectory);
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
      const owner = await readDesktopSmokeLockOwner();
      const metadata = await stat(desktopSmokeLockDirectory).catch(() => undefined);
      const recentOwnerlessClaim =
        owner === undefined && metadata !== undefined && Date.now() - metadata.mtimeMs < 30_000;
      if (recentOwnerlessClaim || (owner !== undefined && desktopSmokeOwnerIsAlive(owner.pid))) {
        throw new Error(`JA_E2E_RUNNER_BUSY pid=${owner?.pid ?? "claiming"}`);
      }
      if (attempt > 0) throw new Error("JA_E2E_RUNNER_BUSY stale lock could not be reclaimed");
      await rm(desktopSmokeLockDirectory, { recursive: true, force: true });
      continue;
    }
    try {
      await writeFile(
        join(desktopSmokeLockDirectory, "owner.json"),
        JSON.stringify({ pid: process.pid, startedAt: new Date().toISOString() }),
        "utf8",
      );
    } catch (error) {
      await rm(desktopSmokeLockDirectory, { recursive: true, force: true }).catch(() => undefined);
      throw error;
    }
    let released = false;
    return async () => {
      if (released) return;
      released = true;
      const owner = await readDesktopSmokeLockOwner();
      if (owner?.pid === process.pid) {
        await rm(desktopSmokeLockDirectory, { recursive: true, force: true });
      }
    };
  }
  throw new Error("JA_E2E_RUNNER_BUSY");
}

/**
 * 仅在显式启用时，于协议已验证的状态捕获产品 viewport 截图。
 * 将目录置于临时运行根之外，便于 Design QA 保留证据，同时不改变冒烟测试默认的产物策略。
 */
async function captureVisualEvidence(page, filename) {
  if (visualEvidenceDirectory === undefined) {
    return;
  }
  if (visualEvidenceRunDirectory === undefined) {
    throw new Error("视觉证据 staging 尚未初始化");
  }
  await mkdir(visualEvidenceRunDirectory, { recursive: true });
  await page.screenshot({ path: join(visualEvidenceRunDirectory, filename), fullPage: false });
}

/** 只发布完整成功的运行结果，避免后续阶段失败的证据覆盖标准 QA 证据。 */
async function publishVisualEvidenceRun() {
  if (visualEvidenceDirectory === undefined || visualEvidenceRunDirectory === undefined) return [];
  await mkdir(visualEvidenceDirectory, { recursive: true });
  const entries = await readdir(visualEvidenceRunDirectory, { withFileTypes: true });
  const published = [];
  for (const entry of entries) {
    if (!entry.isFile()) continue;
    await copyFile(
      join(visualEvidenceRunDirectory, entry.name),
      join(visualEvidenceDirectory, entry.name),
    );
    published.push(entry.name);
  }
  return published.sort();
}

/**
 * 捕获 renderer 模拟的 CSS viewport。文件名和返回值刻意标明 renderer，
 * 防止这份证据被误报为 Windows 原生窗口尺寸或最大化断言。
 */
async function captureRendererVisualEvidenceAtViewport(page, filename, viewport) {
  if (visualEvidenceDirectory === undefined) {
    return {
      status: "skipped",
      surface: "renderer_emulation",
      reason: "screenshot_directory_not_configured",
    };
  }
  const client = await page.context().newCDPSession(page);
  try {
    await client.send("Emulation.setDeviceMetricsOverride", {
      ...viewport,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await page.waitForTimeout(180);
    await captureVisualEvidence(page, filename);
    const metrics = await page.evaluate(() => ({
      innerWidth: globalThis.innerWidth,
      innerHeight: globalThis.innerHeight,
      devicePixelRatio: globalThis.devicePixelRatio,
    }));
    return { status: "captured", surface: "renderer_emulation", nativeWindow: false, ...metrics };
  } finally {
    await client.send("Emulation.clearDeviceMetricsOverride").catch(() => undefined);
    await client.detach().catch(() => undefined);
  }
}

/**
 * 通过真实 Windows 标题栏命令执行最大化；若 WebView 工作区并非 DPR 1（Windows 100% 缩放）
 * 下的参考 2560x1392 CSS viewport，则拒绝该视觉运行。其他 DPI 档位必须作为独立证据状态验收。
 */
async function assertNativeMaximizedVisualViewport(page, deadline) {
  if (visualEvidenceDirectory === undefined) {
    return {
      status: "skipped",
      surface: "native_window",
      reason: "screenshot_directory_not_configured",
    };
  }
  const timeout = () => Math.max(1, deadline - Date.now());
  const restore = page.getByRole("button", { name: "还原", exact: true });
  if ((await restore.count()) === 0) {
    const maximize = page.getByRole("button", { name: "最大化", exact: true });
    await maximize.waitFor({ state: "visible", timeout: timeout() });
    await clickVerifiedControl(page, maximize, deadline);
  }
  await restore.waitFor({ state: "visible", timeout: timeout() });
  await page
    .locator('.ja-titlebar[data-window-maximized="true"]')
    .waitFor({ state: "visible", timeout: timeout() });
  try {
    await page.waitForFunction(
      (expected) =>
        globalThis.innerWidth === expected.width &&
        globalThis.innerHeight === expected.height &&
        globalThis.devicePixelRatio === expected.devicePixelRatio,
      nativeVisualViewport,
      { timeout: timeout() },
    );
  } catch (error) {
    const current = await page.evaluate(() => ({
      innerWidth: globalThis.innerWidth,
      innerHeight: globalThis.innerHeight,
      devicePixelRatio: globalThis.devicePixelRatio,
      screenWidth: globalThis.screen.width,
      screenHeight: globalThis.screen.height,
      availableWidth: globalThis.screen.availWidth,
      availableHeight: globalThis.screen.availHeight,
    }));
    throw new Error(`native 最大化未达到 2560x1392@1：${JSON.stringify(current)}`, {
      cause: error,
    });
  }
  const metrics = await page.evaluate(() => ({
    innerWidth: globalThis.innerWidth,
    innerHeight: globalThis.innerHeight,
    outerWidth: globalThis.outerWidth,
    outerHeight: globalThis.outerHeight,
    devicePixelRatio: globalThis.devicePixelRatio,
    screenWidth: globalThis.screen.width,
    screenHeight: globalThis.screen.height,
    availableWidth: globalThis.screen.availWidth,
    availableHeight: globalThis.screen.availHeight,
    maximized:
      globalThis.document.querySelector(".ja-titlebar")?.getAttribute("data-window-maximized") ===
      "true",
  }));
  if (
    !metrics.maximized ||
    metrics.innerWidth !== nativeVisualViewport.width ||
    metrics.innerHeight !== nativeVisualViewport.height ||
    metrics.devicePixelRatio !== nativeVisualViewport.devicePixelRatio
  ) {
    throw new Error(`native 最大化视口与视觉基准不一致：${JSON.stringify(metrics)}`);
  }
  return {
    status: "passed",
    surface: "native_window",
    expected: nativeVisualViewport,
    ...metrics,
  };
}

/** 仅在真实最大化 viewport 合同通过后截图，避免用未校准的画面冒充视觉证据。 */
async function captureNativeMaximizedVisualEvidence(page, filename, nativeViewportEvidence) {
  if (visualEvidenceDirectory === undefined) return;
  if (
    nativeViewportEvidence?.status !== "passed" ||
    nativeViewportEvidence.surface !== "native_window"
  ) {
    throw new Error("缺少 2560x1392 native 最大化断言，拒绝生成 native 视觉证据");
  }
  const metrics = await page.evaluate(() => ({
    width: globalThis.innerWidth,
    height: globalThis.innerHeight,
    devicePixelRatio: globalThis.devicePixelRatio,
  }));
  if (
    metrics.width !== nativeVisualViewport.width ||
    metrics.height !== nativeVisualViewport.height ||
    metrics.devicePixelRatio !== nativeVisualViewport.devicePixelRatio
  ) {
    throw new Error(`native 截图前视口发生变化：${JSON.stringify(metrics)}`);
  }
  await captureVisualEvidence(page, filename);
}

/**
 * 在真实 WebView2 renderer 中覆盖设置中心的生产窗口、800/799 相邻断点与辅助状态；
 * 每一帧同时证明专属设置 Shell 已接管内容区，避免对话页截图被误报为设置验收。
 */
async function captureResponsiveVisualEvidence(page) {
  if (visualEvidenceDirectory === undefined) {
    return [];
  }
  const client = await page.context().newCDPSession(page);
  const evidence = [];
  /** 同时记录 viewport 真值与 overflow，使截图无法掩盖布局裁切。 */
  const record = async (label, expectedMedia = {}) => {
    await page.waitForTimeout(180);
    const metrics = await page.evaluate(() => {
      const tabs = [...globalThis.document.querySelectorAll('[role="tab"]')]
        .map((tab) => tab.textContent?.trim() ?? "")
        .filter(Boolean);
      return {
        innerWidth: globalThis.innerWidth,
        innerHeight: globalThis.innerHeight,
        devicePixelRatio: globalThis.devicePixelRatio,
        scrollWidth: globalThis.document.documentElement.scrollWidth,
        scrollHeight: globalThis.document.documentElement.scrollHeight,
        forcedColors: globalThis.matchMedia("(forced-colors: active)").matches,
        reducedMotion: globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches,
        settingsShell:
          globalThis.document.querySelector(".ja-layout.is-settings") !== null &&
          globalThis.document.querySelector('[aria-label="设置页面"]') !== null,
        conversationCount: globalThis.document.querySelectorAll(".ja-conversation").length,
        navigationCount: globalThis.document.querySelectorAll(".ja-navigation-sidebar").length,
        workbenchCount: globalThis.document.querySelectorAll(".ja-workbench").length,
        tabs,
      };
    });
    if (metrics.scrollWidth > metrics.innerWidth + 1) {
      throw new Error(`${label} 出现水平溢出 ${metrics.scrollWidth}/${metrics.innerWidth}`);
    }
    if (
      !metrics.settingsShell ||
      metrics.conversationCount !== 0 ||
      metrics.navigationCount !== 0 ||
      metrics.workbenchCount !== 0 ||
      JSON.stringify(metrics.tabs) !== JSON.stringify(["模型", "Skills", "MCP", "执行确认", "外观"])
    ) {
      throw new Error(`${label} 设置专属 Shell 不完整：${JSON.stringify(metrics)}`);
    }
    if (
      (expectedMedia.forcedColors !== undefined &&
        metrics.forcedColors !== expectedMedia.forcedColors) ||
      (expectedMedia.reducedMotion !== undefined &&
        metrics.reducedMotion !== expectedMedia.reducedMotion)
    ) {
      throw new Error(`${label} 媒体能力模拟未生效：${JSON.stringify(metrics)}`);
    }
    await captureVisualEvidence(
      page,
      `implementation-settings-center-${visualTheme}-renderer-${label}.png`,
    );
    evidence.push({ label, surface: "renderer_emulation", nativeWindow: false, ...metrics });
  };
  try {
    for (const viewport of [
      { width: 1280, height: 820 },
      { width: 980, height: 720 },
      { width: 800, height: 640 },
      { width: 799, height: 640 },
      { width: 720, height: 640 },
    ]) {
      await client.send("Emulation.setDeviceMetricsOverride", {
        ...viewport,
        deviceScaleFactor: 1,
        mobile: false,
      });
      await record(`${viewport.width}x${viewport.height}`);
    }
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: 720,
      height: 640,
      deviceScaleFactor: 2,
      mobile: false,
    });
    await record("dpr2-720x640");
    await client.send("Emulation.setDeviceMetricsOverride", {
      width: 1280,
      height: 820,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await page.emulateMedia({ forcedColors: "active", reducedMotion: "reduce" });
    await record("forced-colors-reduced-motion-1280x820", {
      forcedColors: true,
      reducedMotion: true,
    });
  } finally {
    await page
      .emulateMedia({ forcedColors: "none", reducedMotion: "no-preference" })
      .catch(() => undefined);
    await client.send("Emulation.clearDeviceMetricsOverride").catch(() => undefined);
    // Direct CDP 连接与 WebView2 target 共用生命周期；显式 detach 在 Windows 151
    // 会销毁唯一 renderer，统一交给阶段末尾 browser.close() 释放 transport。
  }
  return evidence;
}

/**
 * 以产品的可访问语义定位唯一对话根面，避免 E2E 依赖已经删除的测试专用 DOM 标记；
 * 后续项目范围、设置返回和重启恢复均共享同一稳定边界。
 */
function conversationSurface(page) {
  return page.getByRole("region", { name: "coding 对话", exact: true });
}

/**
 * 通过设置页的真实 Radix 控件切换本轮视觉主题和产品级辅助偏好。
 * 主题必须落到 document authority，减少动效/高对比度必须进入语义 data attribute；
 * 环境变量只选择本轮目标值，不能再仅用于截图文件名。
 */
async function applyVisualPreferences(page, deadline) {
  const timeout = () => Math.max(1, deadline - Date.now());
  await page.getByRole("button", { name: "设置", exact: true }).click();
  const settings = page.getByRole("region", { name: "设置页面", exact: true });
  await settings.waitFor({ state: "visible", timeout: timeout() });
  await settings.getByRole("tab", { name: "外观", exact: true }).click();
  const theme = settings.getByRole("combobox", { name: "主题", exact: true });
  await theme.click();
  await page
    .getByRole("option", { name: visualTheme === "light" ? "浅色" : "深色", exact: true })
    .click();
  await page.waitForFunction(
    (expectedTheme) =>
      globalThis.document.documentElement.getAttribute("data-theme") === expectedTheme,
    visualTheme,
    { timeout: timeout() },
  );
  for (const [name, attribute] of [
    ["减少动效", "data-reduce-motion"],
    ["提高对比度", "data-high-contrast"],
  ]) {
    const toggle = settings.getByRole("switch", { name, exact: true });
    // 每项设置都经过独立 CAS 保存；等待上一项解除 disabled 后再继续，避免并发点击伪造失败。
    await page.waitForFunction(
      (accessibleName) => {
        const candidate = [...globalThis.document.querySelectorAll('[role="switch"]')].find(
          (element) => element.getAttribute("aria-label") === accessibleName,
        );
        return (
          candidate !== undefined &&
          !candidate.hasAttribute("disabled") &&
          candidate.getAttribute("aria-disabled") !== "true"
        );
      },
      name,
      { timeout: timeout() },
    );
    if ((await toggle.getAttribute("aria-checked")) !== "true") await toggle.click();
    await page.waitForFunction(
      ({ accessibleName, rootAttribute }) => {
        const candidate = [...globalThis.document.querySelectorAll('[role="switch"]')].find(
          (element) => element.getAttribute("aria-label") === accessibleName,
        );
        return (
          candidate?.getAttribute("aria-checked") === "true" &&
          !candidate.hasAttribute("disabled") &&
          candidate.getAttribute("aria-disabled") !== "true" &&
          globalThis.document.documentElement.getAttribute(rootAttribute) === "true"
        );
      },
      { accessibleName: name, rootAttribute: attribute },
      { timeout: timeout() },
    );
  }
  await page.waitForFunction(
    (expectedTheme) => {
      const root = globalThis.document.documentElement;
      return (
        root.getAttribute("data-theme") === expectedTheme &&
        root.getAttribute("data-reduce-motion") === "true" &&
        root.getAttribute("data-high-contrast") === "true"
      );
    },
    visualTheme,
    { timeout: timeout() },
  );
  const evidence = await page.evaluate(() => ({
    theme: globalThis.document.documentElement.getAttribute("data-theme"),
    reducedMotion: globalThis.document.documentElement.getAttribute("data-reduce-motion"),
    highContrast: globalThis.document.documentElement.getAttribute("data-high-contrast"),
  }));
  await captureVisualEvidence(
    page,
    `implementation-settings-center-${visualTheme}-native-current.png`,
  );
  const responsive = await captureResponsiveVisualEvidence(page);
  await settings.getByRole("button", { name: "返回对话", exact: true }).click();
  await conversationSurface(page).waitFor({ state: "visible", timeout: timeout() });
  return { status: "passed", ...evidence, responsive };
}

/**
 * 在发送前安装只观察可见 Draft 文本的 MutationObserver；不保留正文，只记录首次非空文本
 * 的单调时钟和长度，使门禁能证明 WebView2 在 terminal 事件之前已真实渲染增量。
 */
async function beginRealtimeDraftObservation(page) {
  await page.evaluate(() => {
    globalThis.__JA_E2E_DRAFT_OBSERVER__?.disconnect?.();
    globalThis.__JA_E2E_DRAFT_OBSERVATIONS__ = [];
    const sample = () => {
      const draft = globalThis.document.querySelector(".ja-chat-message-draft");
      const length = draft?.textContent?.length ?? 0;
      const samples = Array.isArray(globalThis.__JA_E2E_DRAFT_OBSERVATIONS__)
        ? globalThis.__JA_E2E_DRAFT_OBSERVATIONS__
        : [];
      if (length > 0 && samples.length === 0) {
        samples.push({ observedAt: globalThis.performance.now(), length });
      }
      globalThis.__JA_E2E_DRAFT_OBSERVATIONS__ = samples;
    };
    const observer = new globalThis.MutationObserver(sample);
    observer.observe(globalThis.document.body, {
      childList: true,
      subtree: true,
      characterData: true,
    });
    globalThis.__JA_E2E_DRAFT_OBSERVER__ = observer;
    sample();
  });
}

/**
 * 同时要求协议 delta 顺序和 Draft DOM 时序先于同 Turn terminal；只比较本地单调时钟和业务
 * identity，不复制 Provider 文本，从而可用于真实 OpenAI/Anthropic 流而不泄漏内容。
 */
async function assertRealtimeDeltaBeforeTerminal(page) {
  const events = await captureRawTauriEvents(page);
  const terminal = events.findLast(
    (event) => event.method === "turn/terminal" && event.observedAt !== undefined,
  );
  if (terminal?.turnId === undefined || terminal.observedAt === undefined) {
    throw new Error("实时门禁缺少带本地时钟的 turn/terminal");
  }
  const delta = events.find(
    (event) =>
      event.method === "assistant/text-delta" &&
      event.turnId === terminal.turnId &&
      event.observedAt !== undefined,
  );
  const draft = await page.evaluate(() =>
    Array.isArray(globalThis.__JA_E2E_DRAFT_OBSERVATIONS__)
      ? globalThis.__JA_E2E_DRAFT_OBSERVATIONS__[0]
      : undefined,
  );
  if (
    delta?.observedAt === undefined ||
    delta.observedAt >= terminal.observedAt ||
    !Number.isFinite(draft?.observedAt) ||
    draft.observedAt >= terminal.observedAt ||
    !Number.isSafeInteger(draft?.length) ||
    draft.length < 1
  ) {
    throw new Error(
      `增量未在 terminal 前进入 WebView2 Draft：${JSON.stringify({ deltaAt: delta?.observedAt, draftAt: draft?.observedAt, terminalAt: terminal.observedAt })}`,
    );
  }
  return {
    status: "passed",
    turnId: terminal.turnId,
    deltaBeforeTerminal: true,
    draftBeforeTerminal: true,
  };
}

/**
 * 只调整本轮已复验身份的 HWND，以覆盖要求的桌面矩阵。
 * 每一行同时记录原生窗口与 renderer 事实，避免把 CDP 设备模拟误当成 Windows/WebView2 验收。
 */
async function captureWorkbenchNativeMatrix(page, stateLabel, identity, deadline, signal) {
  const evidence = [];
  // 1920 CSS 行与 2560x1392@1 参考值属于视觉 QA 合同，在当前 2560x1392 显示器的 150% 缩放下
  // 无法物理实现。功能冒烟仍覆盖两个真实 HWND 尺寸；显式启用的视觉运行保留完整固定矩阵，
  // 因而继续保持故障关闭。
  const nativeMatrix =
    visualEvidenceDirectory === undefined
      ? responsiveRendererMatrix.filter(({ width }) => width <= 1440)
      : responsiveRendererMatrix;
  try {
    for (const viewport of nativeMatrix) {
      const nativeViewport = await resizeOwnedNativeViewport(
        page,
        identity,
        viewport,
        deadline,
        signal,
      );
      const metrics = await page.evaluate(() => {
        /** 将实时 DOMRect 转为可安全写入 JSON 的 CSS 像素证据行。 */
        const bounds = (selector) => {
          const rect = globalThis.document.querySelector(selector)?.getBoundingClientRect();
          return rect === undefined
            ? null
            : { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
        };
        const workbenchRoot = globalThis.document.querySelector(".ja-workbench");
        const inspector = globalThis.document.querySelector(
          '.ja-inspector[aria-label="工作区面板"]',
        );
        const newPanel = globalThis.document.querySelector('[data-tab-panel="new"]');
        return {
          innerWidth: globalThis.innerWidth,
          innerHeight: globalThis.innerHeight,
          devicePixelRatio: globalThis.devicePixelRatio,
          scrollWidth: globalThis.document.documentElement.scrollWidth,
          navigation: bounds(".ja-navigation-shell"),
          agent: bounds(".ja-main"),
          workbench: bounds('.ja-inspector[aria-label="工作区面板"]'),
          launcherVisible:
            globalThis.document.querySelector(
              '[data-tab-panel="new"]:not([hidden]) .ja-workbench-launcher',
            ) !== null,
          workbenchState:
            workbenchRoot === null
              ? null
              : {
                  activeTab: workbenchRoot.getAttribute("data-active-tab"),
                  openTabs: workbenchRoot.getAttribute("data-open-tabs"),
                },
          inspectorVisible: inspector?.getAttribute("data-visible") ?? null,
          newPanel:
            newPanel === null
              ? null
              : {
                  hidden: newPanel.hasAttribute("hidden"),
                  inert: newPanel.hasAttribute("inert"),
                  bounds: bounds('[data-tab-panel="new"]'),
                },
          renderedTabs: [
            ...globalThis.document.querySelectorAll(".ja-workbench-tab-shell[data-tab]"),
          ].map((tab) => ({
            tab: tab.getAttribute("data-tab"),
            state: tab.getAttribute("data-state"),
          })),
        };
      });
      if (!metrics.launcherVisible || metrics.workbench === null || metrics.workbench.width <= 1) {
        throw new Error(
          `${viewport.width}x${viewport.height} renderer 矩阵缺少可见工作台 launcher：${JSON.stringify(metrics)}`,
        );
      }
      if (metrics.scrollWidth > metrics.innerWidth + 1) {
        throw new Error(
          `${viewport.width}x${viewport.height} renderer 工作台出现水平溢出 ${metrics.scrollWidth}/${metrics.innerWidth}`,
        );
      }
      await captureVisualEvidence(
        page,
        `implementation-codex-workbench-${stateLabel}-${visualTheme}-native-${viewport.width}x${viewport.height}.png`,
      );
      evidence.push({
        label: `${viewport.width}x${viewport.height}`,
        state: stateLabel,
        surface: "native_window",
        nativeWindow: true,
        window: nativeViewport,
        ...metrics,
      });
    }
  } finally {
    await maximizeOwnedNativeWindow(page, identity, deadline, signal);
  }
  return evidence;
}

/**
 * 为整轮运行创建唯一取消源；阶段 deadline 不得重置，否则卡住的第二次启动可能超出 runner 总预算。
 */
function createDeadline(label, durationMs) {
  const controller = new globalThis.AbortController();
  const deadline = Date.now() + durationMs;
  const timer = globalThis.setTimeout(() => {
    controller.abort(new Error(`${label} 超过 ${durationMs}ms`));
  }, durationMs);
  return {
    signal: controller.signal,
    deadline,
    cancel: () => globalThis.clearTimeout(timer),
  };
}

/**
 * 将 AbortSignal 原因转换为普通 Error，使失败运行仍进入既有 summary writer，
 * 而不是产生未处理的 abort。
 */
function throwIfAborted(signal) {
  if (signal?.aborted) {
    const reason = signal.reason;
    throw reason instanceof Error ? reason : new Error(String(reason ?? "E2E 已取消"));
  }
}

/**
 * 让轮询可取消，使 cleanup 能立即唤醒，既不遗留 timer，也无需等待下一次一秒进程轮询。
 */
function waitForDelay(durationMs, signal) {
  return new Promise((resolvePromise, rejectPromise) => {
    let timer;
    const onAbort = () => {
      globalThis.clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
      rejectPromise(signal.reason instanceof Error ? signal.reason : new Error("E2E 已取消"));
    };
    if (signal?.aborted) {
      onAbort();
      return;
    }
    timer = globalThis.setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolvePromise();
    }, durationMs);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

/**
 * 为不支持原生 AbortSignal 参数的 Playwright 操作设置上界；拒绝后仍由阶段 cleanup 回收产品进程。
 */
function raceWithSignal(operation, signal) {
  if (signal?.aborted) {
    return Promise.reject(signal.reason ?? new Error("E2E 已取消"));
  }
  return new Promise((resolvePromise, rejectPromise) => {
    const onAbort = () => rejectPromise(signal.reason ?? new Error("E2E 已取消"));
    signal?.addEventListener("abort", onAbort, { once: true });
    Promise.resolve()
      .then(operation)
      .then(
        (value) => {
          signal?.removeEventListener("abort", onAbort);
          resolvePromise(value);
        },
        (error) => {
          signal?.removeEventListener("abort", onAbort);
          rejectPromise(error);
        },
      );
  });
}

/**
 * Node 支持时，将整轮 abort 与本地短诊断预算组合；旧 runtime 下的回退仍确保本地 deadline 有界。
 */
function combineSignals(signals) {
  const active = signals.filter((signal) => signal !== undefined);
  if (active.length <= 1) {
    return active[0];
  }
  if (typeof globalThis.AbortSignal?.any === "function") {
    return globalThis.AbortSignal.any(active);
  }
  return active[active.length - 1];
}

/**
 * 保证测试临时目录树仅供一次调用使用；随机后缀还使其 workspace identity
 * 与开发者现有 Ja 历史数据库彼此独立。
 */
async function createRunDirectories() {
  const root = await mkdtemp(join(tmpdir(), "ja-desktop-e2e-"));
  const workspace = join(root, "workspace");
  const settings = join(root, "settings");
  const home = join(root, "home");
  const data = join(root, "data");
  const webview = join(root, "webview");
  const runtime = join(root, "runtime");
  const appData = join(root, "appdata");
  const roaming = join(appData, "roaming");
  const local = join(appData, "local");
  try {
    await Promise.all([
      mkdir(workspace),
      mkdir(settings),
      mkdir(home),
      mkdir(data),
      mkdir(webview),
      mkdir(runtime),
      mkdir(roaming, { recursive: true }),
      mkdir(local, { recursive: true }),
    ]);
  } catch (error) {
    Object.defineProperty(error, "e2eRoot", { value: root, enumerable: false });
    throw error;
  }
  return { root, workspace, settings, home, data, webview, runtime, appData, roaming, local };
}

/**
 * 只读取一次显式启用的 provider，验证不含凭据的 loopback URL，并在复制任何子进程环境前
 * 从 process.env 删除密钥。返回值只由本 runner 与私有 settings 文件持有。
 */
function readRealProviderConfig() {
  if (!configuredRealProviderMode) return undefined;
  if (process.env.JA_E2E_KEEP_TEMP === "1") {
    throw new Error("真实 Provider 验收禁止保留临时目录");
  }
  const apiKey = process.env.JA_E2E_REAL_PROVIDER_API_KEY ?? "";
  delete process.env.JA_E2E_REAL_PROVIDER_API_KEY;
  const baseUrl = process.env.JA_E2E_REAL_PROVIDER_BASE_URL?.trim() || "http://localhost:60842/v1";
  const model = process.env.JA_E2E_REAL_PROVIDER_MODEL?.trim() || "gpt-5.6-sol";
  const api = process.env.JA_E2E_REAL_PROVIDER_API?.trim() || "openai_responses";
  const configureViaUi = process.env.JA_E2E_CONFIGURE_VIA_UI === "1";
  delete process.env.JA_E2E_CONFIGURE_VIA_UI;
  let parsed;
  try {
    parsed = new URL(baseUrl);
  } catch {
    throw new Error("真实 Provider Base URL 无效");
  }
  const loopback =
    parsed.hostname === "localhost" ||
    parsed.hostname === "127.0.0.1" ||
    parsed.hostname === "[::1]";
  if (
    parsed.protocol !== "http:" ||
    !loopback ||
    parsed.username !== "" ||
    parsed.password !== "" ||
    parsed.search !== "" ||
    parsed.hash !== ""
  ) {
    throw new Error("真实 Provider 只接受无凭据、无查询参数的 loopback HTTP URL");
  }
  if (apiKey.length < 1 || apiKey.length > 8_192 || /[\0\r\n]/u.test(apiKey)) {
    throw new Error("真实 Provider API Key 长度或字符不合法");
  }
  if (model.length < 1 || model.length > 256 || /[\0\r\n]/u.test(model)) {
    throw new Error("真实 Provider 模型名称不合法");
  }
  if (api !== "openai_responses" && api !== "anthropic_messages") {
    throw new Error("真实 Provider API 只允许 openai_responses 或 anthropic_messages");
  }
  sensitiveRedactions.add(apiKey);
  sensitiveRedactions.add(parsed.href.replace(/\/$/u, ""));
  return { apiKey, baseUrl: parsed.href.replace(/\/$/u, ""), model, api, configureViaUi };
}

/**
 * 为 Files、search 和四类原生 Git Review 构造已提交、分支与脏改动。
 */
async function initializeWorkspaceFixture(workspace, gitCommand, signal) {
  const sample = join(workspace, "sample.ts");
  const agentChangeFixtureDirectory = join(workspace, ".ja-fixture");
  const agentChangeFixture = join(agentChangeFixtureDirectory, "change.txt");
  const visibleDirectoryFixture = join(workspace, "folder-fixture");
  const overflowDirectoryFixture = join(workspace, "overflow-fixture");
  await Promise.all([
    mkdir(agentChangeFixtureDirectory, { recursive: true }),
    mkdir(visibleDirectoryFixture, { recursive: true }),
    mkdir(overflowDirectoryFixture, { recursive: true }),
  ]);
  await writeFile(agentChangeFixture, "before\n", "utf8");
  await writeFile(
    join(visibleDirectoryFixture, "child.txt"),
    "visible directory fixture\n",
    "utf8",
  );
  await Promise.all(
    Array.from({ length: 64 }, (_, index) =>
      writeFile(
        join(overflowDirectoryFixture, `item-${String(index).padStart(3, "0")}.txt`),
        `overflow ${index}\n`,
        "utf8",
      ),
    ),
  );
  await writeFile(sample, 'export const greeting = "hello";\n', "utf8");
  const runGit = async (args) =>
    execFileAsync(gitCommand, args, {
      cwd: workspace,
      windowsHide: true,
      maxBuffer: 2 * 1024 * 1024,
      timeout: snapshotTimeoutMs,
      signal,
    });
  await runGit(["init"]);
  await runGit(["checkout", "-b", "main"]);
  await runGit(["config", "user.name", "Ja E2E"]);
  await runGit(["config", "user.email", "ja-e2e@localhost"]);
  await runGit([
    "add",
    "sample.ts",
    ".ja-fixture/change.txt",
    "folder-fixture/child.txt",
    "overflow-fixture",
  ]);
  await runGit(["commit", "-m", "fixture"]);
  await runGit(["branch", "review-base"]);
  await writeFile(join(workspace, "committed-change.txt"), "Ja committed review fixture\n", "utf8");
  await runGit(["add", "committed-change.txt"]);
  await runGit(["commit", "-m", "review fixture head"]);
  await writeFile(
    sample,
    'export const greeting = "hello from Ja";\nexport const answer = 42;\n',
    "utf8",
  );
  await writeFile(join(workspace, "new-file.txt"), "Ja workbench search fixture\n", "utf8");
  await writeFile(join(workspace, "trash-cancel.txt"), "Ja trash cancel fixture\n", "utf8");
  await writeFile(join(workspace, "trash-me.txt"), "Ja trash opt-in fixture\n", "utf8");
  await writeFile(
    join(workspace, attachmentFixtureFile),
    "Ja managed attachment fixture\n",
    "utf8",
  );
  await writeFile(
    join(workspace, businessFixtureFile),
    [
      "# 退款业务规则",
      "",
      "- 客户代号：ORBIT-7429",
      "- 超过 500 元的退款必须由两名审批人共同批准。",
      "- 审批应在 48 小时内完成。",
      "- 验收输出：ORBIT-7429|DUAL_APPROVAL|48H",
      "",
    ].join("\n"),
    "utf8",
  );
}

/** 启动带请求计数器的 loopback 页面，用于证明 Preview 确实加载了真实子 WebView。 */
async function startPreviewFixture() {
  let requests = 0;
  const server = createHttpServer((request, response) => {
    requests += 1;
    response.writeHead(200, {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
    });
    response.end(
      "<!doctype html><html><head><title>Ja Preview Fixture</title></head><body><main>JA_PREVIEW_OK</main></body></html>",
    );
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  if (typeof address !== "object" || address === null) {
    server.close();
    throw new Error("无法启动 Preview fixture");
  }
  return {
    url: `http://127.0.0.1:${address.port}/`,
    requestCount: () => requests,
    close: () => new Promise((resolvePromise) => server.close(() => resolvePromise())),
  };
}

/**
 * 固定四类标题验收语料；每个短标题都低于生产 48 code point 上限，并让成功标题保留同一
 * 搜索词，从而能在元数据刷新前后验证搜索输入、焦点与选中项而不制造无结果歧义。
 */
function automaticTitleScenarios() {
  return Object.freeze({
    success: Object.freeze({
      id: "success",
      prompt: "标题同步验收 初始短标题",
      reply: "标题同步验收回复完成",
      automaticTitle: "标题同步验收 智能总结",
      searchQuery: "标题同步验收",
    }),
    titleFailure: Object.freeze({
      id: "title_failure",
      prompt: "标题失败回退 保留短标题",
      reply: "标题失败回退回复完成",
    }),
    manual: Object.freeze({
      id: "manual",
      prompt: "人工重命名竞态 初始短标题",
      reply: "人工重命名竞态回复完成",
      automaticTitle: "不应覆盖的迟到标题",
      manualTitle: "人工最终标题",
    }),
    cancellation: Object.freeze({
      id: "cancellation",
      prompt: "取消首轮 保留短标题",
      reply: "首轮取消前不得输出",
      secondPrompt: "第二轮成功也不改标题",
      secondReply: "取消后第二轮回复完成",
    }),
  });
}

/**
 * 只把 ServerResponse 已 flush 或客户端真实断开视为 Provider 交换终态。`responded` 仅表示
 * fixture 已开始发送，不能代替传输收敛，否则 keep-alive 与半关闭会让负向断言产生假阳性。
 */
function titleFixtureExchangeFinished(attempt) {
  return attempt?.finished === true || attempt?.disconnected === true;
}

/** 创建一次性可查询释放门，测试失败关闭时重复 release 也保持幂等。 */
function createFixtureGate() {
  let released = false;
  let releasePromise;
  const promise = new Promise((resolvePromise) => {
    releasePromise = resolvePromise;
  });
  return {
    promise,
    released: () => released,
    release: () => {
      if (released) return;
      released = true;
      releasePromise();
    },
  };
}

/** 读取有界 JSON 请求；Provider fixture 不保留请求正文、Header 或凭据。 */
async function readBoundedFixtureJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 2 * 1024 * 1024) throw new Error("fixture request exceeded byte limit");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

/** 构造 OpenAI Responses 最小 envelope，字段与生产 Adapter 的严格 SSE 解析合同一致。 */
function titleFixtureResponse(responseId, status, output, includeUsage) {
  const response = {
    id: responseId,
    created_at: 0,
    model: "ja-title-loopback-model",
    object: "response",
    output,
    parallel_tool_calls: true,
    tool_choice: "auto",
    tools: [],
    status,
  };
  if (includeUsage) {
    response.usage = {
      input_tokens: 5,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens: 5,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 10,
    };
  }
  return response;
}

/** 将一个严格 Responses 事件编码为 SSE；不使用隐式换行或 JSON pretty-print。 */
function titleFixtureEvent(type, sequence, payload) {
  return `event: ${type}\ndata: ${JSON.stringify({
    type,
    sequence_number: sequence,
    ...payload,
  })}\n\n`;
}

/** 生成自然结束且带完整 usage 的文本流，使自动标题不会因缺少审计用量而回退。 */
function titleFixtureTextStream(text, requestNumber) {
  const responseId = `resp_title_e2e_${requestNumber}`;
  const itemId = `message_title_e2e_${requestNumber}`;
  const finalItem = {
    id: itemId,
    type: "message",
    role: "assistant",
    status: "completed",
    content: [{ type: "output_text", text, annotations: [], logprobs: [] }],
  };
  return [
    titleFixtureEvent("response.created", 0, {
      response: titleFixtureResponse(responseId, "in_progress", [], false),
    }),
    titleFixtureEvent("response.output_text.delta", 1, {
      content_index: 0,
      delta: text,
      item_id: itemId,
      logprobs: [],
      output_index: 0,
    }),
    titleFixtureEvent("response.output_text.done", 2, {
      content_index: 0,
      item_id: itemId,
      output_index: 0,
      text,
    }),
    titleFixtureEvent("response.completed", 3, {
      response: titleFixtureResponse(responseId, "completed", [finalItem], true),
    }),
  ].join("");
}

/**
 * 启动仅绑定 IPv4 loopback 的生产 Provider fixture。常规首轮与自动标题分别设门，真窗可以
 * 在服务端尚未发送任何模型字节时断言即时短标题，并确定性制造失败、取消和迟到 CAS 竞态。
 */
async function startAutomaticTitleProviderFixture() {
  const scenarios = automaticTitleScenarios();
  const scenarioList = Object.values(scenarios);
  const attempts = [];
  const gates = new Map([
    [`${scenarios.success.id}:turn`, createFixtureGate()],
    [`${scenarios.success.id}:title`, createFixtureGate()],
    [`${scenarios.manual.id}:title`, createFixtureGate()],
    [`${scenarios.cancellation.id}:turn`, createFixtureGate()],
  ]);

  /** 只按受控 fixture 文本识别请求归属，无法识别的生产请求必须失败关闭。 */
  function classify(payload) {
    const serialized = JSON.stringify(payload?.input ?? []);
    const scenario = scenarioList.find(
      (candidate) =>
        serialized.includes(candidate.prompt) ||
        (candidate.secondPrompt !== undefined && serialized.includes(candidate.secondPrompt)),
    );
    if (scenario === undefined) return undefined;
    const kind =
      serialized.includes("<user_request>") && serialized.includes("<assistant_reply>")
        ? "title"
        : "turn";
    const secondTurn =
      kind === "turn" &&
      scenario.secondPrompt !== undefined &&
      serialized.includes(scenario.secondPrompt);
    return { scenario, kind, secondTurn };
  }

  /** 返回指定场景与请求类型的生产 `/responses` 交换次数，不计 token 预估请求。 */
  function attemptCount(scenarioId, kind) {
    return attempts.filter((attempt) => attempt.scenarioId === scenarioId && attempt.kind === kind)
      .length;
  }

  /** 返回已经 flush 响应或被客户端取消的交换次数，作为 UI 负向断言的确定性收敛点。 */
  function finishedCount(scenarioId, kind) {
    return attempts.filter(
      (attempt) =>
        attempt.scenarioId === scenarioId &&
        attempt.kind === kind &&
        titleFixtureExchangeFinished(attempt),
    ).length;
  }

  /** 标记指定请求门可继续；不存在门说明该行为本就应立即响应。 */
  function release(scenarioId, kind) {
    gates.get(`${scenarioId}:${kind}`)?.release();
  }

  /** 暴露不含正文的请求计数快照，供重启时证明没有后台重放。 */
  function snapshot() {
    return scenarioList.map((scenario) => ({
      scenarioId: scenario.id,
      turnAttempts: attemptCount(scenario.id, "turn"),
      titleAttempts: attemptCount(scenario.id, "title"),
      invalidTitleContracts: attempts.filter(
        (attempt) =>
          attempt.scenarioId === scenario.id &&
          attempt.kind === "title" &&
          attempt.contractValid !== true,
      ).length,
    }));
  }

  const server = createHttpServer(async (request, response) => {
    if (request.method !== "POST") {
      response.writeHead(405, { "content-length": "0" });
      response.end();
      return;
    }
    let payload;
    try {
      payload = await readBoundedFixtureJson(request);
    } catch {
      response.writeHead(400, { "content-length": "0" });
      response.end();
      return;
    }
    if (request.url?.endsWith("/responses/input_tokens")) {
      const body = Buffer.from('{"input_tokens":5}', "utf8");
      response.writeHead(200, {
        "content-type": "application/json",
        "content-length": String(body.length),
        "cache-control": "no-store",
      });
      response.end(body);
      return;
    }
    if (!request.url?.endsWith("/responses")) {
      response.writeHead(404, { "content-length": "0" });
      response.end();
      return;
    }
    const classified = classify(payload);
    if (classified === undefined) {
      response.writeHead(422, { "content-length": "0" });
      response.end();
      return;
    }
    const { scenario, kind, secondTurn } = classified;
    const contractValid =
      kind !== "title" ||
      (payload.model === "ja-title-loopback-model" &&
        payload.max_output_tokens === 64 &&
        typeof payload.instructions === "string" &&
        payload.tools === undefined &&
        payload.tool_choice === undefined &&
        payload.parallel_tool_calls === undefined &&
        payload.previous_response_id === undefined &&
        payload.reasoning === undefined &&
        payload.temperature === undefined &&
        payload.top_p === undefined);
    const attempt = {
      scenarioId: scenario.id,
      kind,
      secondTurn,
      contractValid,
      responded: false,
      finished: false,
      disconnected: false,
      streamStarted: false,
    };
    attempts.push(attempt);
    /** `finish` 在响应交给内核后触发，不等待可被 keep-alive 延长的 socket close。 */
    const markFinished = () => {
      attempt.finished = true;
    };
    /** 只有尚未 flush 的连接关闭才是取消；正常 keep-alive close 不能覆盖成功终态。 */
    const markDisconnected = () => {
      if (!attempt.finished) attempt.disconnected = true;
    };
    request.once("aborted", markDisconnected);
    response.once("finish", markFinished);
    response.once("close", markDisconnected);

    if (!contractValid) {
      response.writeHead(400, { "content-length": "0" });
      attempt.responded = true;
      response.end();
      return;
    }

    if (kind === "title" && scenario.id === scenarios.titleFailure.id) {
      const body = Buffer.from(
        '{"error":{"message":"fixture failure","type":"server_error","code":"fixture_failure"}}',
        "utf8",
      );
      response.writeHead(503, {
        "content-type": "application/json",
        "content-length": String(body.length),
        "retry-after": "0",
        "cache-control": "no-store",
      });
      attempt.responded = true;
      response.end(body);
      return;
    }

    const gate = gates.get(`${scenario.id}:${kind}`);
    if (gate !== undefined && !(scenario.id === scenarios.cancellation.id && secondTurn)) {
      await gate.promise;
    }
    if (attempt.disconnected || response.destroyed) return;
    const text =
      kind === "title"
        ? scenario.automaticTitle
        : secondTurn
          ? scenario.secondReply
          : scenario.reply;
    if (typeof text !== "string" || text.length === 0) {
      response.writeHead(500, { "content-length": "0" });
      attempt.responded = true;
      response.end();
      return;
    }
    const body = Buffer.from(titleFixtureTextStream(text, attempts.length), "utf8");
    response.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "content-length": String(body.length),
      "cache-control": "no-store",
    });
    attempt.streamStarted = true;
    for (let offset = 0; offset < body.length; offset += 11) {
      response.write(body.subarray(offset, offset + 11));
    }
    attempt.responded = true;
    response.end();
  });
  await new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  if (typeof address !== "object" || address === null) {
    server.close();
    throw new Error("无法启动自动标题 loopback fixture");
  }

  /** 释放所有门并关闭精确 loopback server，避免失败场景残留活动连接或延迟 Promise。 */
  async function close() {
    for (const gate of gates.values()) gate.release();
    await new Promise((resolvePromise) => {
      server.close(() => resolvePromise());
      server.closeAllConnections?.();
    });
  }

  return {
    scenarios,
    providerConfig: {
      api: "openai_responses",
      apiKey: "JA_TITLE_LOOPBACK_ONLY",
      baseUrl: `http://127.0.0.1:${address.port}/v1`,
      model: "ja-title-loopback-model",
      configureViaUi: false,
    },
    attempts,
    attemptCount,
    finishedCount,
    release,
    snapshot,
    close,
  };
}

/**
 * 在 Windows 动态客户端端口范围之外选择尚未监听的回环端口；调用方可传入本轮已使用
 * 端口集合，避免 Vite、fixture、EdgeDriver 与两轮 CDP 互相复用。函数返回后仍存在很短的
 * bind 窗口，因此最终安全性由进程树 owner 复验保证：端口被抢占只会让 Gate 失败。
 */
async function reservePort(excludedPorts = new Set()) {
  const offset = (Date.now() + process.pid) % stablePortRange.size;
  for (let index = 0; index < stablePortRange.size; index += 1) {
    const port = stablePortRange.start + ((offset + index) % stablePortRange.size);
    if (excludedPorts.has(port)) continue;
    const server = createServer();
    const bound = await new Promise((resolvePromise) => {
      server.once("error", () => resolvePromise(false));
      server.listen(port, "127.0.0.1", () => resolvePromise(true));
    });
    if (!bound) {
      server.close();
      continue;
    }
    await new Promise((resolvePromise) => server.close(resolvePromise));
    return port;
  }
  throw new Error("无法在非动态范围分配 E2E 端口");
}

/**
 * 读取 Windows 最终 main window 配置并按 Tauri 的覆盖顺序合并。
 * E2E 只追加调试参数，不复制尺寸、装饰或拖放等产品事实，避免生产窗口演进后验收仍启动旧壳。
 */
async function readProductionMainWindowConfig() {
  const [baseConfig, windowsConfig] = await Promise.all([
    readFile(join(repoRoot, "src-tauri", "tauri.conf.json"), "utf8").then(JSON.parse),
    readFile(join(repoRoot, "src-tauri", "tauri.windows.conf.json"), "utf8").then(JSON.parse),
  ]);
  const baseWindow = baseConfig?.app?.windows?.find((window) => window?.label === "main");
  const windowsWindow = windowsConfig?.app?.windows?.find((window) => window?.label === "main");
  if (
    baseWindow === undefined ||
    baseWindow === null ||
    typeof baseWindow !== "object" ||
    Array.isArray(baseWindow)
  ) {
    throw new Error("生产 Tauri main window 配置缺失");
  }
  if (
    windowsWindow !== undefined &&
    (windowsWindow === null || typeof windowsWindow !== "object" || Array.isArray(windowsWindow))
  ) {
    throw new Error("Windows Tauri main window 覆盖无效");
  }
  return { ...baseWindow, ...(windowsWindow ?? {}) };
}

/**
 * 写入本轮私有 Tauri overlay；按已独占的前端端口派生测试 identifier，避免 single-instance
 * mutex 与用户或其它隔离验收窗口共享。这里只继承产品窗口事实与 dev origin，WebView2 的
 * UDF 和调试端口仍由同一个子进程环境 owner 注入，避免形成两套 browser 参数。
 */
async function writeE2eTauriConfig(directories, frontendPort, useEdgeDriver) {
  const configPath = join(directories.runtime, "tauri.e2e.conf.json");
  const origin = `http://localhost:${frontendPort}`;
  const websocket = `ws://localhost:${frontendPort}`;
  const devCsp = `default-src 'self'; connect-src 'self' ipc: http://ipc.localhost ${origin} ${websocket}; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self' data:; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`;
  const productionWindow = await readProductionMainWindowConfig();
  const config = {
    identifier: `io.github.kongweiguang.ja.e2e.run${frontendPort}`,
    build: {
      devUrl: origin,
      ...(useEdgeDriver
        ? {
            runner: edgeDriverRunnerPath,
          }
        : {}),
    },
    app: {
      windows: [
        {
          ...productionWindow,
        },
      ],
      security: { devCsp },
    },
  };
  await writeFile(configPath, `${JSON.stringify(config, null, 2)}\n`, "utf8");
  return configPath;
}

/**
 * 以纯函数生成当前唯一受支持的 v4 Provider/Model 配置 fixture，使静态合同可以在不写盘、
 * 不接触凭据的前提下拒绝旧 profile schema 与已移除的 Chat Completions API。
 */
function buildSettingsDocument(providerConfig) {
  const real = providerConfig !== undefined;
  const tomlString = (value) => JSON.stringify(String(value));
  const providerKind = providerConfig?.api === "anthropic_messages" ? "anthropic" : "openai";
  return [
    "schema_version = 4",
    "config_revision = 1",
    'default_access_mode = "approval_required"',
    'default_provider_id = "provider_e2e"',
    'default_model_id = "model_e2e"',
    "default_reasoning_level = { __ja_null = true }",
    "mcp_servers = []",
    "skills = []",
    "",
    "[[providers]]",
    'provider_id = "provider_e2e"',
    `name = ${tomlString(real ? "E2E Real Provider" : "E2E Fake")}`,
    `provider = "${providerKind}"`,
    `api = "${providerConfig?.api ?? "openai_responses"}"`,
    `base_url = ${tomlString(providerConfig?.baseUrl ?? "http://127.0.0.1:9/v1")}`,
    'credential_id = "cred_e2e"',
    "[providers.network_timeouts]",
    "connect_timeout_ms = 5000",
    "request_timeout_ms = 30000",
    "[providers.agent_defaults]",
    "[providers.agent_defaults.context]",
    "auto_compact = true",
    "[providers.agent_defaults.turn_limits]",
    "max_model_rounds = 32",
    "max_tool_calls = 128",
    "wall_timeout_ms = 30000",
    "[[providers.models]]",
    'model_id = "model_e2e"',
    `name = ${tomlString(real ? "E2E Real Model" : "E2E Fake Model")}`,
    `model = ${tomlString(providerConfig?.model ?? "ja-e2e-fake")}`,
    "reasoning_level_map = {}",
    "default_reasoning_level = { __ja_null = true }",
    "[providers.models.capabilities]",
    "context_window_tokens = 128000",
    "max_output_tokens = 8192",
    "",
  ].join("\n");
}

/**
 * 在本轮私有 user Home 下写入当前拆分后的 config/auth schema。
 * 离线 UI 模式使用非敏感占位值且不发起 Turn；真实 loopback 模式只在临时且受 ACL 保护的
 * auth 文件中保存密钥。
 */
async function writeSettings(jaHome, providerConfig) {
  // Ja App Server sidecar 通过 --home-dir-base64 接收这个精确目录。
  // fixture 与该 owner 保持一致，避免桌面 profile 遮蔽配置。
  await mkdir(jaHome, { recursive: true });
  const config = buildSettingsDocument(providerConfig);
  const configPath = join(jaHome, "config.toml");
  const authPath = join(jaHome, "auth.json");
  await writeFile(configPath, config, "utf8");
  await writeFile(
    authPath,
    `${JSON.stringify({
      cred_e2e: providerConfig?.apiKey ?? "DUMMY_ONLY_NOT_A_SECRET",
    })}\n`,
    "utf8",
  );
  const account = `${process.env.USERDOMAIN ?? "."}\\${process.env.USERNAME ?? ""}`;
  if (account.endsWith("\\")) throw new Error("无法确定当前 Windows ACL 用户");
  await execFileAsync("icacls.exe", [authPath, "/inheritance:r", "/grant:r", `${account}:(F)`], {
    windowsHide: true,
    maxBuffer: 1 * 1024 * 1024,
    timeout: snapshotTimeoutMs,
  });
  return { configPath, authPath };
}

/**
 * 在共享 gate 准入后获取一次有界进程快照，并只保留仍可由 Win32 process table 打开的 PID。
 *
 * Windows CIM 偶尔会短暂保留已经退出的空壳行；若把这种缺少 command line 的 stale 行当成
 * 既存 Ja，安全门禁会永久阻止隔离验收。二次存在性核对只删除已退出行，不会放宽任何可关闭
 * 进程的 PID、父链、command line 与创建时间身份要求。
 */
async function runProcessSnapshot(signal) {
  throwIfAborted(signal);
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$live = @{}; Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.HandleCount -gt 0 -and $_.Threads.Count -gt 0 } | ForEach-Object { $live[[int]$_.Id] = $true }",
    "Get-CimInstance Win32_Process | Where-Object { $live.ContainsKey([int]$_.ProcessId) } | Select-Object ProcessId,ParentProcessId,Name,CommandLine,CreationDate | ConvertTo-Json -Compress",
  ].join("; ");
  const stdout = await new Promise((resolvePromise, rejectPromise) => {
    const child = execFile(
      "powershell.exe",
      ["-NoProfile", "-NonInteractive", "-Command", script],
      {
        windowsHide: true,
        maxBuffer: 8 * 1024 * 1024,
        encoding: "utf8",
        timeout: snapshotTimeoutMs,
        killSignal: "SIGTERM",
        signal,
      },
      (error, output) => {
        snapshotHelpers.delete(child.pid);
        if (error) {
          rejectPromise(signal?.aborted ? (signal.reason ?? error) : error);
          return;
        }
        resolvePromise(Buffer.isBuffer(output) ? output.toString("utf8") : String(output ?? ""));
      },
    );
    if (child.pid) {
      snapshotHelpers.add(child.pid);
    }
  });
  if (stdout.trim() === "") {
    return [];
  }
  const value = JSON.parse(stdout);
  const entries = Array.isArray(value) ? value : [value];
  return entries
    .map((entry) => ({
      pid: Number(entry.ProcessId),
      parentPid: Number(entry.ParentProcessId),
      name: typeof entry.Name === "string" ? entry.Name : "",
      commandLine: typeof entry.CommandLine === "string" ? entry.CommandLine : "",
      creationDate: typeof entry.CreationDate === "string" ? entry.CreationDate : "",
    }))
    .filter((entry) => Number.isInteger(entry.pid) && entry.pid > 0);
}

/**
 * 在 watcher、UI 断言与 cleanup 之间串行化每次进程快照；
 * 被拒绝的任务会被吸收，避免一次 timeout 污染后续快照。
 */
async function processSnapshot(signal) {
  const queued = snapshotTail.then(() => runProcessSnapshot(signal));
  snapshotTail = queued.catch(() => undefined);
  return raceWithSignal(() => queued, signal);
}

/**
 * 短暂等待已启动的 command wrapper 出现且具备全部身份字段；
 * cleanup 绝不回退为仅凭 PID 判断根进程。
 */
async function waitForRootIdentity(rootPid, deadline, signal) {
  while (Date.now() < deadline) {
    const entry = (await processSnapshot(signal)).find((candidate) => candidate.pid === rootPid);
    if (hasProcessIdentity(entry)) {
      return entry;
    }
    await waitForDelay(pollMs, signal);
  }
  throw new Error(`Tauri launcher root ${rootPid} 未取得完整进程身份`);
}

/**
 * 要求 cleanup 使用的全部身份字段齐备；不完整 CIM 行会被视为不安全，
 * 而不会转换为可终止的占位进程。
 */
function hasProcessIdentity(entry) {
  return (
    Number.isInteger(entry?.pid) &&
    entry.pid > 0 &&
    Number.isInteger(entry?.parentPid) &&
    entry.parentPid >= 0 &&
    entry.name !== "" &&
    entry.commandLine !== "" &&
    entry.creationDate !== ""
  );
}

/**
 * 对短暂不完整的后代进程行设置有界等待，同时不放宽 launcher 根进程或任何终止目标的完整身份要求。
 */
function createIncompleteObserved() {
  return { current: new Map(), history: [], dropped: 0 };
}

/**
 * 仅复制重新识别不完整 CIM 行所需的进程字段；缺失值保持显式，
 * 避免 runner 将部分行转成仅凭 PID 的 cleanup 候选。
 */
function incompleteProcessMarker(entry) {
  return {
    pid: entry?.pid,
    parentPid: Number.isInteger(entry?.parentPid) ? entry.parentPid : null,
    name: typeof entry?.name === "string" ? entry.name : "",
    creationDate: typeof entry?.creationDate === "string" ? entry.creationDate : "",
    commandLine: typeof entry?.commandLine === "string" ? entry.commandLine : "",
  };
}

/**
 * 只有 PID、进程名与创建时间均已知时才匹配部分行；缺失字段刻意不作为身份依据。
 */
function sameIncompleteIdentity(expected, actual) {
  return (
    Number.isInteger(expected?.pid) &&
    expected.pid > 0 &&
    Number.isInteger(actual?.pid) &&
    actual.pid === expected.pid &&
    expected.name !== "" &&
    actual.name !== "" &&
    expected.name.toLowerCase() === actual.name.toLowerCase() &&
    expected.creationDate !== "" &&
    actual.creationDate !== "" &&
    expected.creationDate === actual.creationDate
  );
}

/**
 * 为一个不完整后代记录有界历史；在后续完整快照升级其身份前，
 * 绝不将其加入可终止的 observed map。
 */
function recordIncompleteObserved(incompleteObserved, entry) {
  if (!(incompleteObserved instanceof Object) || !Number.isInteger(entry?.pid) || entry.pid <= 0) {
    return;
  }
  const marker = incompleteProcessMarker(entry);
  const key = JSON.stringify([marker.pid, marker.name.toLowerCase(), marker.creationDate]);
  if (
    !incompleteObserved.current.has(key) &&
    incompleteObserved.current.size >= incompleteObservationLimit
  ) {
    incompleteObserved.dropped += 1;
    return;
  }
  incompleteObserved.current.set(key, marker);
  if (
    !incompleteObserved.history.some(
      (item) => JSON.stringify([item.pid, item.name.toLowerCase(), item.creationDate]) === key,
    )
  ) {
    if (incompleteObserved.history.length >= incompleteObservationLimit) {
      incompleteObserved.dropped += 1;
    } else {
      incompleteObserved.history.push(marker);
    }
  }
}

/**
 * 构建下一次 owned closure 前先协调此前的部分行：完整行可正常升级，
 * 已消失的行只保留有界历史，仍不完整的行则明确保持不可终止。
 */
function reconcileIncompleteObserved(incompleteObserved, snapshot) {
  if (!(incompleteObserved instanceof Object)) {
    return;
  }
  for (const [key, marker] of incompleteObserved.current) {
    const candidate = snapshot.find((entry) => sameIncompleteIdentity(marker, entry));
    if (candidate === undefined || hasProcessIdentity(candidate)) {
      incompleteObserved.current.delete(key);
      continue;
    }
    incompleteObserved.current.set(key, incompleteProcessMarker(candidate));
  }
}

/**
 * 执行任何 close 或 kill 前，重新验证 PID、名称、command line 与创建时间，
 * 防止 PID 复用跨越本轮运行边界。
 */
function sameProcessIdentity(expected, actual) {
  return (
    hasProcessIdentity(expected) &&
    hasProcessIdentity(actual) &&
    expected.pid === actual.pid &&
    expected.name === actual.name &&
    expected.commandLine === actual.commandLine &&
    expected.creationDate === actual.creationDate
  );
}

/**
 * 在本 runner 创建 launcher 根进程前，冻结所有可见 Ja 实例。
 * 同名但身份不完整的行会阻止启动，因为仅凭 PID 的保护无法证明 cleanup 保留了用户的准确进程。
 */
function selectPreexistingJaIdentities(snapshot) {
  const candidates = snapshot.filter(
    (entry) => typeof entry?.name === "string" && entry.name.toLowerCase() === "ja.exe",
  );
  const incomplete = candidates.filter((entry) => !hasProcessIdentity(entry));
  if (incomplete.length > 0)
    throw new Error(`启动前有 ${incomplete.length} 个 ja.exe 缺少完整进程身份`);
  return candidates.map((entry) => Object.freeze({ ...entry }));
}

/**
 * 将一次新 CIM 快照与冻结基线比较。身份缺失、不完整行与 PID 复用保持为不同状态，
 * 避免证据错误地将其中任何一种标记为“已保留”。
 */
function evaluatePreexistingJaIdentities(expected, snapshot) {
  const preserved = [];
  const missing = [];
  const reused = [];
  const incomplete = [];
  for (const identity of expected) {
    const candidate = snapshot.find((entry) => entry.pid === identity.pid);
    if (candidate === undefined) {
      missing.push(identity);
    } else if (!hasProcessIdentity(candidate)) {
      incomplete.push({ expected: identity, actual: candidate });
    } else if (!sameProcessIdentity(identity, candidate)) {
      reused.push({ expected: identity, actual: candidate });
    } else {
      preserved.push(candidate);
    }
  }
  return { preserved, missing, reused, incomplete };
}

/** 阻止与基线完全一致的身份进入任何直接 close/kill 目标列表。 */
function isProtectedPreexistingJa(entry, preexistingJaIdentities) {
  return preexistingJaIdentities.some((identity) => sameProcessIdentity(identity, entry));
}

/**
 * 只解析实际观察到的两种 PowerShell CIM JSON 日期形式（Microsoft JSON /Date(ms)/ 与 ISO-8601 UTC/offset）；
 * 遇到未知日期文本时，对 PID 复用保持故障关闭。
 */
function parseWindowsCreationDate(value) {
  const text = typeof value === "string" ? value : "";
  const microsoftJsonMatch = /^\/Date\((\d+)\)\/$/.exec(text);
  if (microsoftJsonMatch !== null) {
    const timestamp = Number(microsoftJsonMatch[1]);
    return Number.isSafeInteger(timestamp) ? timestamp : undefined;
  }
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$/.test(text)) {
    return undefined;
  }
  const timestamp = Date.parse(text);
  return Number.isFinite(timestamp) ? timestamp : undefined;
}

/**
 * 若父 PID 与早于本 launcher 创建的进程冲突，则拒绝该候选。
 * Windows 可能复用父 PID，因此即使字段完全一致，也不能授权回退终止 Visual Studio helper 等旧共享工具。
 */
function isCreatedDuringRun(rootIdentity, candidate) {
  const rootTime = parseWindowsCreationDate(rootIdentity?.creationDate);
  const candidateTime = parseWindowsCreationDate(candidate?.creationDate);
  return rootTime !== undefined && candidateTime !== undefined && candidateTime >= rootTime;
}

/**
 * 在启动任何真实进程前，使用无效根、无效候选、较旧、相等和较新的 CIM fixture
 * 固定进程年龄边界。
 */
function assertCreationBoundaryContract() {
  const root = { creationDate: "2026-08-18T11:13:05.166Z" };
  if (isCreatedDuringRun({ creationDate: "not-a-date" }, root)) {
    throw new Error("creation boundary accepted an invalid root timestamp");
  }
  if (isCreatedDuringRun(root, { creationDate: "not-a-date" })) {
    throw new Error("creation boundary accepted an invalid candidate timestamp");
  }
  if (isCreatedDuringRun(root, { creationDate: "2026-08-18T11:13:05.165Z" })) {
    throw new Error("creation boundary accepted an older candidate");
  }
  if (!isCreatedDuringRun(root, { creationDate: root.creationDate })) {
    throw new Error("creation boundary rejected an equal timestamp");
  }
  if (!isCreatedDuringRun(root, { creationDate: "2026-08-18T11:13:05.167Z" })) {
    throw new Error("creation boundary rejected a newer candidate");
  }
  const offsetRoot = { creationDate: "2026-08-18T19:13:05.166000+08:00" };
  if (isCreatedDuringRun(offsetRoot, { creationDate: "2026-08-18T19:13:05.165000+08:00" })) {
    throw new Error("creation boundary accepted an older +08:00 candidate");
  }
  if (!isCreatedDuringRun(offsetRoot, { creationDate: offsetRoot.creationDate })) {
    throw new Error("creation boundary rejected an equal +08:00 timestamp");
  }
  if (!isCreatedDuringRun(offsetRoot, { creationDate: "2026-08-18T19:13:05.167000+08:00" })) {
    throw new Error("creation boundary rejected a newer +08:00 timestamp");
  }
  if (isCreatedDuringRun(offsetRoot, { creationDate: "2026-08-18T19:13:05.167000+0800" })) {
    throw new Error("creation boundary accepted a malformed offset");
  }
  const microsoftJsonRoot = { creationDate: "/Date(1786423603973)/" };
  if (isCreatedDuringRun(microsoftJsonRoot, { creationDate: "/Date(1786423603972)/" })) {
    throw new Error("creation boundary accepted an older Microsoft JSON CIM timestamp");
  }
  if (!isCreatedDuringRun(microsoftJsonRoot, { creationDate: microsoftJsonRoot.creationDate })) {
    throw new Error("creation boundary rejected an equal Microsoft JSON CIM timestamp");
  }
  if (!isCreatedDuringRun(microsoftJsonRoot, { creationDate: "/Date(1786423603974)/" })) {
    throw new Error("creation boundary rejected a newer Microsoft JSON CIM timestamp");
  }
  if (isCreatedDuringRun(microsoftJsonRoot, { creationDate: "/Date(-1)/" })) {
    throw new Error("creation boundary accepted a negative Microsoft JSON timestamp");
  }
}

/** 在不执行命令的前提下检测被禁止的按进程名终止模式。 */
function containsNameScopedJaTermination(source) {
  const value = String(source ?? "");
  return (
    /\/IM["'\s,]+ja\.exe/iu.test(value) ||
    /Get-Process[^;\n]*(?:-Name\s+)?["']?ja(?:\.exe)?\b/iu.test(value) ||
    /Stop-Process[^;\n]*(?:-Name\s+)?["']?ja(?:\.exe)?\b/iu.test(value) ||
    /Win32_Process[^;\n]*ja\.exe[^;\n]*(?:Terminate|Delete)/iu.test(value)
  );
}

/**
 * 在启动任何 launcher 前运行纯负向 fixture。除身份结果外，还将托盘退出/force cleanup
 * 限定到准确 owned PID，并拒绝未来出现按名称终止机器上全部 Ja 实例的实现。
 */
function assertPreexistingJaGuardContract() {
  const original = {
    pid: 106_952,
    parentPid: 4_000,
    name: "ja.exe",
    commandLine: "C:\\Program Files\\Ja\\ja.exe --profile existing",
    creationDate: "2026-08-24T03:00:00.000Z",
  };
  const selected = selectPreexistingJaIdentities([
    original,
    { ...original, pid: 106_953, name: "other.exe" },
  ]);
  if (selected.length !== 1 || !sameProcessIdentity(selected[0], original))
    throw new Error("preexisting Ja baseline selection contract failed");
  const preserved = evaluatePreexistingJaIdentities(selected, [original]);
  if (
    preserved.preserved.length !== 1 ||
    preserved.missing.length + preserved.reused.length + preserved.incomplete.length !== 0
  ) {
    throw new Error("preexisting Ja preserved fixture failed");
  }
  if (evaluatePreexistingJaIdentities(selected, []).missing.length !== 1)
    throw new Error("preexisting Ja missing fixture was accepted");
  const reused = evaluatePreexistingJaIdentities(selected, [
    { ...original, commandLine: "C:\\other\\ja.exe", creationDate: "2026-08-24T03:00:01.000Z" },
  ]);
  if (reused.reused.length !== 1) throw new Error("preexisting Ja PID reuse fixture was accepted");
  const incomplete = evaluatePreexistingJaIdentities(selected, [{ ...original, commandLine: "" }]);
  if (incomplete.incomplete.length !== 1)
    throw new Error("preexisting Ja incomplete fresh row was accepted");
  let rejectedIncompleteBaseline = false;
  try {
    selectPreexistingJaIdentities([{ ...original, commandLine: "" }]);
  } catch {
    rejectedIncompleteBaseline = true;
  }
  if (!rejectedIncompleteBaseline)
    throw new Error("preexisting Ja incomplete baseline was accepted");

  const root = {
    ...original,
    pid: 200_000,
    parentPid: 1_000,
    name: "pnpm.cmd",
    commandLine: "pnpm tauri dev",
    creationDate: "2026-08-24T04:00:00.000Z",
  };
  const parentPidCollision = { ...original, parentPid: root.pid };
  if (
    processTree(root, [root, parentPidCollision], createIncompleteObserved())?.has(
      parentPidCollision.pid,
    )
  ) {
    throw new Error("preexisting Ja entered the owned descendant closure through parent PID reuse");
  }

  const cleanupSource = [requestTrayExit, stopProcessTree, stopSnapshotHelpers]
    .map((operation) => operation.toString())
    .join("\n");
  if (
    containsNameScopedJaTermination(cleanupSource) ||
    !containsNameScopedJaTermination("taskkill.exe /IM ja.exe /F") ||
    !containsNameScopedJaTermination("Get-Process -Name ja | Stop-Process")
  ) {
    throw new Error("preexisting Ja name-scoped termination contract failed");
  }
  const forceSource = stopProcessTree.toString();
  const exitSelectionSource = tauriProcessIds.toString();
  if (
    !forceSource.includes('"/PID"') ||
    !forceSource.includes("sameProcessIdentity") ||
    !forceSource.includes("isProtectedPreexistingJa") ||
    !exitSelectionSource.includes("tree.values") ||
    !exitSelectionSource.includes("sameProcessIdentity") ||
    !exitSelectionSource.includes("isProtectedPreexistingJa")
  ) {
    throw new Error("preexisting Ja exact-owned cleanup contract failed");
  }
  const mainSource = main.toString();
  const baselineIndex = mainSource.indexOf("capturePreexistingJaBaseline");
  const launchIndex = mainSource.indexOf("startTauri(");
  if (
    baselineIndex < 0 ||
    launchIndex < 0 ||
    baselineIndex >= launchIndex ||
    !cleanupPhase.toString().includes("verifyPreexistingJaGuard")
  ) {
    throw new Error("preexisting Ja lifecycle guard is not wired before launch and after cleanup");
  }
}

/**
 * 固定 Terminal 外层事务顺序：普通可见性切换必须保留 PTY；显式关闭则先等待原生 ACK，
 * 验证全部旧 PTY 身份已消失，再移除 Tab，最后只重新打开全新 session。
 */
function assertOuterTerminalCapabilityContract() {
  const source = exerciseOuterWorkbenchLifecycle.toString();
  const switchedAlive = source.indexOf(
    'assertOwnedIdentitiesAlive(remainingShells, signal, "外层能力切换")',
  );
  const drawerAlive = source.indexOf(
    'assertOwnedIdentitiesAlive(remainingShells, signal, "右侧栏收起")',
    switchedAlive,
  );
  const sideChatAlive = source.indexOf(
    'assertOwnedIdentitiesAlive(remainingShells, signal, "侧边聊天收栏")',
    drawerAlive,
  );
  const acknowledged = source.indexOf(
    'JSON.stringify(["start", "rejected", "start", "resolved"])',
    sideChatAlive,
  );
  const ptysGone = source.indexOf("waitForOwnedIdentitiesGone(remainingShells", acknowledged);
  const tabRemoved = source.indexOf("ja-workbench-tab-shell[data-tab=", ptysGone);
  const freshSessions = source.indexOf("reopenedNativeSessionIds.some", tabRemoved);
  if (
    switchedAlive < 0 ||
    drawerAlive <= switchedAlive ||
    sideChatAlive <= drawerAlive ||
    acknowledged <= sideChatAlive ||
    ptysGone <= acknowledged ||
    tabRemoved <= ptysGone ||
    freshSessions <= tabRemoved
  ) {
    throw new Error("outer Terminal ACK-first closeAll contract failed");
  }
  const projectSource = exerciseProjectWorkbench.toString();
  if (
    !projectSource.includes("waitForOwnedIdentitiesGone(tabLifecycle.reopenedShells") ||
    !projectSource.includes('tauriInvokeCount(page, "ja_terminal_close_all")')
  ) {
    throw new Error("workspace switch terminal closeAll contract failed");
  }
}

/** 锁定 Shell 审批必须先恢复项目 Thread，避免无活动项目时无法启动 Turn 被误报为审批缺失。 */
function assertApprovalScopeContract() {
  const source = runFirstSession.toString();
  const localDeadline = source.indexOf("approvalScopeDeadline");
  const projectReselect = source.indexOf(
    "selectProjectThreadById(page, workbench.projectThreadId",
    localDeadline,
  );
  const projectScope = source.indexOf("assertProjectConversationScope", projectReselect);
  const approval = source.indexOf("runParallelApprovalFlow", projectScope);
  if (
    localDeadline < 0 ||
    projectReselect <= localDeadline ||
    projectScope <= projectReselect ||
    approval <= projectScope
  ) {
    throw new Error("approval project scope contract failed");
  }
  if (!String(selectProjectThreadById).includes("ensureNavigationSidebarVisible")) {
    throw new Error("approval navigation restore contract failed");
  }
  const approvalFlow = String(runParallelApprovalFlow);
  if (
    !approvalFlow.includes("approvalFixtureReason") ||
    !approvalFlow.includes("approvalFixtureTool") ||
    !approvalFlow.includes("approvalFixtureCallId") ||
    approvalFlow.includes("approvalFixtureCommand")
  ) {
    throw new Error("approval visible redaction contract failed");
  }
}

/**
 * 仅从完整观测到的根身份计算后代 closure；不完整后代在后续快照补齐身份前只能作为证据，
 * 根进程缺失时仍不返回进程树。
 */
function processTree(rootIdentity, snapshot, incompleteObserved) {
  reconcileIncompleteObserved(incompleteObserved, snapshot);
  const root = snapshot.find((entry) => sameProcessIdentity(rootIdentity, entry));
  if (root === undefined) {
    return undefined;
  }
  const owned = new Map([[root.pid, root]]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const entry of snapshot) {
      if (!owned.has(entry.pid) && owned.has(entry.parentPid)) {
        if (!hasProcessIdentity(entry)) {
          recordIncompleteObserved(incompleteObserved, entry);
          continue;
        }
        if (!isCreatedDuringRun(rootIdentity, entry)) {
          continue;
        }
        owned.set(entry.pid, entry);
        changed = true;
      }
    }
  }
  return owned;
}

/**
 * 在保存 summary 前脱敏临时路径和疑似 token 文本；summary 用于证据，而不是原始进程日志。
 */
function redact(text, directories = {}) {
  let value = String(text ?? "");
  for (const sensitive of [...sensitiveRedactions].sort(
    (left, right) => right.length - left.length,
  )) {
    if (sensitive.length > 0) {
      value = value.replace(
        new RegExp(sensitive.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "g"),
        "<redacted>",
      );
    }
  }
  const paths = [
    repoRoot,
    java25Home,
    process.env.USERPROFILE,
    directories.root,
    directories.workspace,
    directories.settings,
    directories.webview,
    directories.runtime,
    directories.appData,
  ]
    .filter((path) => typeof path === "string" && path.length > 0)
    .sort((left, right) => right.length - left.length);
  for (const path of paths) {
    const segments = path
      .replace(/^\\\\\?\\/, "")
      .split(/[\\/]+/)
      .filter(Boolean);
    if (segments.length > 0) {
      const escaped = segments
        .map((segment) => segment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"))
        .join("[\\\\/]+");
      value = value.replace(new RegExp(escaped, "gi"), "<e2e-path>");
    }
  }
  value = value.replace(
    /((?:api[_-]?key|token|secret|password)\s*[:=]\s*)("[^"]*"|'[^']*'|[^,\s&}]+)/gi,
    "$1<redacted>",
  );
  return value.replace(/\r?\n/g, " ").slice(0, 500);
}

/**
 * Windows 分隔符、大小写或扩展路径脱敏一旦回归便快速失败；
 * 这里使用内联合同检查，避免引入第二套测试框架。
 */
function assertSanitizerContract() {
  const fixtureRoot = "C:\\Users\\JaE2E\\Temp\\ja-desktop-e2e-Fixture";
  const variant = fixtureRoot.replaceAll("\\", "/").toUpperCase();
  const extended = `\\\\?\\${fixtureRoot}`;
  const output = redact(`a=${variant} b=${extended} token: "secret-value"`, { root: fixtureRoot });
  if (
    output.includes("JaE2E") ||
    output.toLowerCase().includes("secret-value") ||
    !output.includes("<e2e-path>")
  ) {
    throw new Error("E2E sanitizer contract failed for Windows path/secret variants");
  }
}

/**
 * 串行化进程树观测，避免缓慢 CIM 查询与下一次轮询重叠并形成无界 helper 进程队列。
 * Stop 会等待在途查询结束，然后 cleanup 才能检查最终进程树。
 */
function startProcessWatcher(rootIdentity, observed, incompleteObserved, signal) {
  let stopped = false;
  let wake = undefined;
  let failure;
  const completion = (async () => {
    while (!stopped) {
      try {
        const snapshot = await processSnapshot(signal);
        const tree = processTree(rootIdentity, snapshot, incompleteObserved);
        if (tree !== undefined) {
          for (const [pid, entry] of tree) {
            observed.set(pid, entry);
          }
        }
      } catch (error) {
        if (!stopped && !signal?.aborted && failure === undefined) {
          failure = error;
        }
        // 进程可能在 CIM 枚举与下一次轮询之间退出，此时无需把缺失误报为观察失败。
      }
      if (stopped || signal?.aborted) {
        break;
      }
      try {
        await new Promise((resolvePromise, rejectPromise) => {
          let timer;
          let rejectNow;
          const finish = (callback, value) => {
            globalThis.clearTimeout(timer);
            signal?.removeEventListener("abort", rejectNow);
            callback(value);
          };
          const resolveNow = () => finish(resolvePromise);
          rejectNow = () => finish(rejectPromise, signal.reason ?? new Error("E2E 已取消"));
          timer = globalThis.setTimeout(resolveNow, pollMs);
          wake = resolveNow;
          signal?.addEventListener("abort", rejectNow, { once: true });
        });
      } catch {
        // 整轮 deadline 是卡住阶段的正常唤醒路径，不应再制造额外错误。
      } finally {
        wake = undefined;
      }
    }
  })();
  return {
    stop: async () => {
      stopped = true;
      wake?.();
      await completion;
    },
    get failure() {
      return failure;
    },
  };
}

/**
 * 只回收本次调用中仍被跟踪的 PowerShell observer。正常路径会等待每个 observer；
 * 该兜底用于防止强制测试失败遗留 CIM helper，同时绝不把 Node runner 当作终止目标。
 */
async function stopSnapshotHelpers(signal) {
  for (const pid of [...snapshotHelpers]) {
    try {
      await execFileAsync("taskkill.exe", ["/PID", String(pid), "/T", "/F"], {
        windowsHide: true,
        maxBuffer: 1 * 1024 * 1024,
        timeout: snapshotTimeoutMs,
        signal,
      });
    } catch {
      // observer 可能在集合查找与 taskkill 之间完成，需要容忍该退出竞态。
    }
    if (signal?.aborted) {
      continue;
    }
    try {
      const current = await processSnapshot(signal);
      if (!current.some((entry) => entry.pid === pid)) {
        snapshotHelpers.delete(pid);
      }
    } catch {
      // 无法安全确认进程已消失时继续登记 PID，避免后续 cleanup 失去追踪。
    }
  }
}

/**
 * 启动前解析 Windows command shim，避免 Node 子进程依赖 PowerShell 命令解析规则或隐式 shell 路径。
 */
async function locateCommand(command, signal) {
  throwIfAborted(signal);
  try {
    const { stdout } = await execFileAsync("where.exe", [command], {
      windowsHide: true,
      maxBuffer: 1 * 1024 * 1024,
      timeout: snapshotTimeoutMs,
      signal,
    });
    const resolved = stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find(Boolean);
    if (resolved) {
      return resolved;
    }
  } catch {
    // 回退失败时向调用方返回稳定的工具缺失错误，避免暴露不确定的解析细节。
  }
  return command;
}

/**
 * 在切换到隔离 USERPROFILE 前解析真实 Toolchain Cargo，避免 rustup shim 把私有 profile
 * 误判为全新安装并在真窗 smoke 中临时下载编译器。
 */
async function locateCargoCommand(signal) {
  throwIfAborted(signal);
  const rustup = await locateCommand("rustup.exe", signal);
  try {
    const { stdout } = await execFileAsync(rustup, ["which", "cargo"], {
      windowsHide: true,
      maxBuffer: 1 * 1024 * 1024,
      timeout: snapshotTimeoutMs,
      signal,
    });
    const resolved = stdout.trim();
    if (resolved.toLowerCase().endsWith("\\cargo.exe")) return resolved;
  } catch {
    // 缺少已安装 Toolchain 时保留稳定 fallback，让 Tauri 输出真实环境错误。
  }
  return locateCommand("cargo.exe", signal);
}

/**
 * 验证用户显式提供的官方 EdgeDriver 可执行文件与版本输出；不自动下载或回退到 PATH，
 * 避免真窗 Gate 在未审计的驱动更新后静默改变自动化语义。
 */
async function validateConfiguredEdgeDriver(signal) {
  if (configuredEdgeDriverPath === undefined) return undefined;
  const path = resolve(configuredEdgeDriverPath);
  const metadata = await stat(path);
  if (!metadata.isFile()) throw new Error("JA_E2E_EDGEDRIVER_PATH 不是文件");
  const { stdout } = await execFileAsync(path, ["--version"], {
    windowsHide: true,
    maxBuffer: 64 * 1024,
    timeout: snapshotTimeoutMs,
    signal,
  });
  const match = /^Microsoft Edge WebDriver ([0-9]+(?:\.[0-9]+){3})\b/u.exec(stdout.trim());
  if (match === null) throw new Error("EdgeDriver 版本输出无效");
  return { path, version: match[1] };
}

/**
 * 构造唯一的 Tauri 启动环境；这里只注入隔离目录与受信工具路径，调试端口由私有
 * overlay 经 Wry API 传递，避免环境变量与窗口配置形成两个 browser 参数 owner。
 */
function buildTauriEnv(
  directories,
  frontendPort,
  cdpPort,
  exitTracePath,
  edgeDriver,
  edgeDriverPort,
  edgeDriverSessionPath,
  rootProcessEnv,
  pnpmCommand,
  cargoCommand,
  productionRuntime,
  enableDirectCdp,
) {
  const env = {
    ...rootProcessEnv,
    APPDATA: directories.roaming,
    LOCALAPPDATA: directories.local,
    USERPROFILE: directories.settings,
    HOME: directories.settings,
    // 应用配置必须隔离，但 Rust registry/toolchain 是构建输入；复用宿主缓存避免每轮 smoke
    // 在私有 HOME 中重新联网下载，同时不让 Tauri 读取真实应用数据。
    CARGO_HOME: rootProcessEnv.CARGO_HOME?.trim() || join(rootProcessEnv.USERPROFILE, ".cargo"),
    RUSTUP_HOME: rootProcessEnv.RUSTUP_HOME?.trim() || join(rootProcessEnv.USERPROFILE, ".rustup"),
    JA_E2E_RUNTIME_ROOT: directories.runtime,
    JA_E2E_EXIT_TRACE_PATH: exitTracePath,
    JA_E2E_DEV_PORT: String(frontendPort),
    VITE_JA_E2E_PROJECT_PATH: directories.workspace,
    JAVA_HOME: java25Home,
    JA_JAVA25_HOME: java25Home,
    JA_E2E_JAVA_HOME: java25Home,
    JA_TEST_JAVA: java25,
    JA_DEBUG_JAVA: java25,
    JA_DEBUG_JAR:
      rootProcessEnv.JA_E2E_APP_SERVER_JAR?.trim() ||
      join(repoRoot, "app-server", "target", "ja-app-server.jar"),
    CARGO_TARGET_DIR: resolve(
      rootProcessEnv.JA_E2E_CARGO_TARGET_DIR?.trim() ||
        join(repoRoot, "src-tauri", "target", "e2e"),
    ),
    // Chrome 136+ 只在非默认 UDF 上接受 remote-debugging；Tauri 2.11 当前配置转换
    // 会丢弃 dataDirectory，因此 direct 模式通过同一组官方环境 override 绑定 UDF 与端口。
    ...(edgeDriver === undefined
      ? {
          WEBVIEW2_USER_DATA_FOLDER: directories.webview,
          ...(enableDirectCdp
            ? {
                WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: directCdpBrowserArgumentsForPort(cdpPort),
              }
            : {}),
        }
      : {}),
  };
  if (edgeDriver !== undefined) {
    env.JA_E2E_CARGO_COMMAND = cargoCommand;
    env.JA_E2E_EDGEDRIVER_PATH = edgeDriver.path;
    env.JA_E2E_EDGEDRIVER_PORT = String(edgeDriverPort);
    env.JA_E2E_EDGEDRIVER_SESSION_PATH = edgeDriverSessionPath;
    env.JA_E2E_WEBVIEW_DATA_DIR = edgeDriverDataDirectory(directories);
  }
  if (productionRuntime) env.JA_E2E_RUNTIME_MODE = "production";
  const inheritedPath = rootProcessEnv.PATH ?? rootProcessEnv.Path ?? "";
  env.PATH = [dirname(pnpmCommand), dirname(cargoCommand), inheritedPath]
    .filter((part) => part !== "")
    .join(";");
  if (Object.hasOwn(env, "Path")) {
    delete env.Path;
  }
  return env;
}

/**
 * 在选择 CDP 端口前完成与真窗完全相同的 Rust feature 编译；这样固定端口不会跨越冷编译
 * 窗口暴露给其它进程，同时仍由后续 Tauri CLI 负责 dev server、配置 overlay 与真实启动。
 */
async function warmTauriBinary(directories, rootProcessEnv, cargoCommand, signal) {
  const inheritedPath = rootProcessEnv.PATH ?? rootProcessEnv.Path ?? "";
  const env = {
    ...rootProcessEnv,
    CARGO_HOME: rootProcessEnv.CARGO_HOME?.trim() || join(rootProcessEnv.USERPROFILE, ".cargo"),
    RUSTUP_HOME: rootProcessEnv.RUSTUP_HOME?.trim() || join(rootProcessEnv.USERPROFILE, ".rustup"),
    CARGO_TARGET_DIR: resolve(
      rootProcessEnv.JA_E2E_CARGO_TARGET_DIR?.trim() ||
        join(repoRoot, "src-tauri", "target", "e2e"),
    ),
    JAVA_HOME: java25Home,
    JA_E2E_JAVA_HOME: java25Home,
    JA_TEST_JAVA: java25,
    PATH: [dirname(cargoCommand), inheritedPath].filter((part) => part !== "").join(";"),
  };
  if (Object.hasOwn(env, "Path")) delete env.Path;
  // 预编译只生成本轮真实启动将使用的 binary，不创建窗口、listener 或应用数据。
  await execFileAsync(cargoCommand, ["build", "-p", "ja", "--bin", "ja"], {
    cwd: repoRoot,
    env,
    windowsHide: true,
    maxBuffer: 4 * 1024 * 1024,
    timeout: Math.min(300_000, runDeadlineMs),
    signal,
  });
}

/**
 * 引用一个内部解析得到的 Windows 命令 token，同时拒绝 cmd.exe 展开字符。
 * E2E runner 从不接收 UI 输入的该路径；此处故障关闭也规避 Node 对 `.cmd` shim
 * 使用已弃用隐式 shell 参数拼接的行为。
 */
function quoteWindowsCommandToken(value) {
  if (typeof value !== "string" || value.length === 0 || /[\0\r\n"%]/u.test(value)) {
    throw new Error("E2E Windows command path is invalid");
  }
  return `"${value}"`;
}

/**
 * 启动真实 Tauri 窗口，并通过独立 user-data、端口和退出轨迹隔离每轮运行；
 * 调试参数只存在于子进程环境，二进制始终使用生产编译面，避免测试 feature 掩盖装配差异。
 */
function startTauri(
  directories,
  frontendPort,
  cdpPort,
  tauriConfigPath,
  exitTracePath,
  edgeDriver,
  edgeDriverPort,
  edgeDriverSessionPath,
  rootProcessEnv,
  pnpmCommand,
  cargoCommand,
  productionRuntime,
  enableDirectCdp,
) {
  const env = buildTauriEnv(
    directories,
    frontendPort,
    cdpPort,
    exitTracePath,
    edgeDriver,
    edgeDriverPort,
    edgeDriverSessionPath,
    rootProcessEnv,
    pnpmCommand,
    cargoCommand,
    productionRuntime,
    enableDirectCdp,
  );
  const commandInterpreter = rootProcessEnv.ComSpec ?? rootProcessEnv.COMSPEC ?? "cmd.exe";
  // `/s /c` 在命令与后续参数都带引号时需要最外层引号，否则 cmd.exe 会剥离错误的一对。
  const commandLine = `"${quoteWindowsCommandToken(pnpmCommand)} tauri dev --no-watch --config ${quoteWindowsCommandToken(tauriConfigPath)}"`;
  const child = spawn(commandInterpreter, ["/d", "/s", "/c", commandLine], {
    env,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
    // 已固定引用的命令行按 `.cmd` 合同原样传给 cmd.exe，避免 Node 再构造一层 shell。
    windowsVerbatimArguments: true,
    cwd: repoRoot,
  });
  const output = { stdout: [], stderr: [] };
  const capture = (target, chunk) => {
    if (target.length < 400) {
      target.push(String(chunk));
    }
  };
  child.stdout?.on("data", (chunk) => capture(output.stdout, chunk));
  child.stderr?.on("data", (chunk) => capture(output.stderr, chunk));
  return { child, output };
}

/**
 * 返回有界 launcher 诊断，使 setup panic 能立即终止 E2E，
 * 避免窗口已经消失后仍等待完整 CDP deadline。
 */
function launcherDiagnostic(launch) {
  const stdout = launch.output.stdout.join("").slice(-1_500);
  const stderr = launch.output.stderr.join("").slice(-1_500);
  return `stdout=${stdout} stderr=${stderr}`;
}

/**
 * 对最终 launcher 诊断设置长度上界并脱敏路径，使失败运行能记录 build/runtime 线索，
 * 又不会把进程输出变成日志或敏感信息传输通道。
 */
function launcherOutputSummary(launch, directories) {
  return {
    stdoutTail: redact(launch.output.stdout.join("").slice(-1_500), directories),
    stderrTail: redact(launch.output.stderr.join("").slice(-1_500), directories),
  };
}

/**
 * 只接受已冻结 Rust exit-trace 合同的有序前缀；保留部分 shutdown 证据，
 * 但路径、命令、敏感信息、重复行和乱序行会在持久化 summary 前被拒绝。
 */
function parseExitTraceStages(text) {
  const rawLines = String(text ?? "").split(/\r?\n/);
  while (rawLines.at(-1) === "") {
    rawLines.pop();
  }
  const lines = rawLines;
  if (lines.length === 0) {
    return { status: "empty", stages: [] };
  }
  if (lines.length > frozenExitStageSequence.length) {
    return { status: "invalid", stages: [] };
  }
  const stages = [];
  for (const [index, line] of lines.entries()) {
    const stage = /^stage=([A-Za-z0-9._-]{1,96})$/.exec(line)?.[1];
    if (
      stage === undefined ||
      !frozenExitStages.has(stage) ||
      stage !== frozenExitStageSequence[index]
    ) {
      return { status: "invalid", stages };
    }
    stages.push(stage);
  }
  return {
    status: stages.length === frozenExitStageSequence.length ? "complete" : "partial",
    stages,
  };
}

/**
 * 使用恶意路径、token、JSON、换行和空白输入检验严格 trace 解析；
 * 该检查保持在本地，使解析器回归在启动任何桌面进程前失败，且不受信文本不会进入 summary。
 */
function assertExitTraceContract() {
  const accepted = [
    "stage=exit_requested_enter",
    "stage=exit_requested_return",
    "stage=exit_enter",
    "stage=exit_return",
  ].join("\n");
  const acceptedResult = parseExitTraceStages(`${accepted}\n`);
  if (
    acceptedResult.status !== "complete" ||
    acceptedResult.stages.length !== frozenExitStageSequence.length
  ) {
    throw new Error("exit trace contract rejected the frozen Rust stage sequence");
  }
  for (let length = 1; length < frozenExitStageSequence.length; length += 1) {
    const prefix = frozenExitStageSequence
      .slice(0, length)
      .map((stage) => `stage=${stage}`)
      .join("\n");
    const partial = parseExitTraceStages(`${prefix}\n`);
    if (partial.status !== "partial" || partial.stages.length !== length) {
      throw new Error("exit trace contract rejected a valid shutdown prefix");
    }
  }
  const rejected = [
    "stage=C:\\Users\\24052\\secret",
    "stage=api_key",
    '{"stage":"exit_enter"}',
    "stage=exit_enter\nstage=unexpected",
    "stage=exit_enter ",
    "stage=exit_requested_enter\nstage=exit_enter",
    "stage=exit_requested_enter\nstage=exit_requested_enter",
    "stage=exit_enter",
    "stage=run_returned",
  ];
  for (const fixture of rejected) {
    if (parseExitTraceStages(fixture).status !== "invalid") {
      throw new Error("exit trace contract accepted untrusted or non-canonical input");
    }
  }
}

/**
 * 仅在 force cleanup 后读取阶段专属 trace；缺失或不可读文件会转换为稳定诊断状态，
 * 此时调用方已回收进程树，且证据中绝不暴露固定 runtime 路径。
 */
async function readExitTrace(runId, phase, runtime) {
  const tracePath = join(runtime, `ja-exit-trace-${runId}-${phase}.jsonl`);
  try {
    return parseExitTraceStages(await readFile(tracePath, "utf8"));
  } catch {
    return { status: "missing", stages: [] };
  }
}

/**
 * graceful signal abort 后使用独立短预算重新采样产品身份；若复制 deadline 前的 map，
 * 会把陈旧进程误报为存活并掩盖真实 shutdown 结果。
 */
async function captureFreshLiveIdentities(observed) {
  const freshDeadline = createDeadline("graceful fresh snapshot", 3_000);
  try {
    const snapshot = await processSnapshot(freshDeadline.signal);
    return {
      live: [...observed.values()].filter((entry) =>
        snapshot.some((candidate) => sameProcessIdentity(entry, candidate)),
      ),
      snapshotStatus: "fresh",
    };
  } catch (error) {
    return { live: [], snapshotStatus: "failed", snapshotError: error };
  } finally {
    freshDeadline.cancel();
  }
}

/**
 * graceful 等待被 abort 时选择新鲜证据；纯 resolver 保证 abort 前的陈旧身份不会进入 `liveAfterGrace`。
 */
function resolveGracefulProductObservation(phase, productGrace, freshGrace) {
  const result = productGrace.aborted ? freshGrace : productGrace;
  const live = Array.isArray(result?.live) ? result.live : [];
  const snapshotError = result?.snapshotError ?? result?.error;
  const snapshotStatus = result?.snapshotStatus;
  let failure;
  if (snapshotStatus !== undefined && snapshotStatus !== "fresh") {
    // 保留原始错误作为内部证据，但只对外暴露稳定消息，避免路径、command line 和敏感信息进入失败 summary。
    failure = new Error(`${phase} 产品进程 graceful cleanup 后 fresh snapshot 状态异常`);
  } else if (snapshotError !== undefined) {
    failure = new Error(`${phase} 产品进程 graceful cleanup 后无法完成 fresh snapshot`);
  } else if (live.length > 0) {
    failure = new Error(`${phase} 产品进程在 graceful deadline 后仍存活`);
  }
  return { live, failure, snapshotError };
}

/**
 * 将被 abort 的 graceful 结果路由到一次独立的新快照；正常结果保持不变，也不会额外触发 CIM 查询。
 */
async function settleGracefulProductObservation(phase, productObserved, productGrace) {
  const freshGrace = productGrace.aborted
    ? await captureFreshLiveIdentities(productObserved)
    : productGrace;
  return resolveGracefulProductObservation(phase, productGrace, freshGrace);
}

/**
 * 在不启动进程也不等待的情况下检验 abort resolver；这样既防止 stale-map 回归，
 * 也让 runner 合同保持在本地。
 */
function assertGracefulAbortContract() {
  const stale = [{ pid: 1 }];
  const resolved = resolveGracefulProductObservation(
    "contract",
    { aborted: true, live: stale },
    { live: [] },
  );
  if (resolved.live.length !== 0 || resolved.failure !== undefined) {
    throw new Error("graceful abort contract retained stale product identities");
  }
  const failed = resolveGracefulProductObservation(
    "contract",
    { aborted: true, live: stale },
    {
      live: [],
      snapshotStatus: "failed",
      snapshotError: new Error("C:\\secret-token"),
    },
  );
  if (failed.failure === undefined || failed.snapshotError?.message !== "C:\\secret-token") {
    throw new Error("graceful abort contract dropped fresh snapshot failure evidence");
  }
  if (failed.failure.message.includes("secret-token")) {
    throw new Error("graceful abort contract exposed raw snapshot error");
  }
}

/**
 * 启动前验证最终原生 runtime 目标，防止格式错误的测试路径回退到开发者 app-data 目录。
 */
function assertLaunchRuntimeRoot(directories) {
  const root = resolve(directories.root);
  const runtime = resolve(directories.runtime);
  const comparable = (value) =>
    String(value)
      .replace(/^\\\\\?\\/, "")
      .replace(/[\\/]+$/, "")
      .toLowerCase();
  const rootValue = comparable(root);
  const runtimeValue = comparable(runtime);
  if (!runtimeValue.startsWith(`${rootValue}\\`) || runtimeValue === rootValue) {
    throw new Error("E2E runtime 目录必须是本轮临时 root 下的绝对路径");
  }
  return runtime;
}

/**
 * 解码 debug sidecar 的四目录身份，并在任何 UI 断言前证明它们都属于本轮临时根。
 * 这里按各目录的真实职责分别比对，不能继续把 durable data 误当成短生命周期 runtime；
 * 同时要求日志留在隔离 USERPROFILE 的固定 Ja Home 内，避免真窗通过但污染开发者目录。
 */
function assertRuntimeIsolation(snapshot, directories) {
  const java = snapshot.find(
    (entry) =>
      entry.name.toLowerCase() === "java.exe" && entry.commandLine.includes("--data-dir-base64="),
  );
  if (java === undefined) {
    throw new Error("未找到带隔离目录标识的 Ja App Server sidecar");
  }
  const comparable = (value) =>
    String(value)
      .replace(/^\\\\\?\\/, "")
      .replace(/[\\/]+$/, "")
      .toLowerCase();
  const expected = {
    home: directories.home,
    data: directories.data,
    run: directories.runtime,
    log: join(directories.settings, ".ja", "logs", "java"),
  };
  const decoded = {};
  // 四个参数必须一次性完整出现；缺少任意一项都说明 Host 与 Java 的目录合同已漂移。
  for (const [name, expectedPath] of Object.entries(expected)) {
    const encoded = new RegExp(`--${name}-dir-base64=([^\\s"]+)`, "iu").exec(java.commandLine)?.[1];
    if (encoded === undefined) {
      throw new Error(`Ja App Server sidecar 缺少 ${name}-dir 标识`);
    }
    const actualPath = Buffer.from(encoded, "base64url").toString("utf8");
    if (comparable(actualPath) !== comparable(expectedPath)) {
      throw new Error(`Ja App Server sidecar ${name}-dir 未隔离到本轮权威目录`);
    }
    decoded[name] = redact(actualPath, directories);
  }
  return {
    javaPid: java.pid,
    decodedDirectories: decoded,
    expectedRoot: redact(directories.root, directories),
  };
}

/**
 * 只读取真实 app-data runtime 的元数据，使本轮可在不打开或修改该目录的前提下，
 * 证明 debug seam 阻止了意外写入。
 */
async function captureRealRuntimeEvidence(baseEnv, directories) {
  const appData = baseEnv.APPDATA;
  const localAppData = baseEnv.LOCALAPPDATA;
  const userProfile = baseEnv.USERPROFILE;
  if (
    typeof appData !== "string" ||
    appData.trim() === "" ||
    typeof localAppData !== "string" ||
    localAppData.trim() === "" ||
    typeof userProfile !== "string" ||
    userProfile.trim() === ""
  ) {
    throw new Error("当前 Windows 环境缺少 APPDATA 或 USERPROFILE，无法建立真实 runtime 不变基线");
  }
  const roamingAppRoot = join(appData, "io.github.kongweiguang.ja");
  const localAppRoot = join(localAppData, "io.github.kongweiguang.ja");
  const realJaHome = join(userProfile, ".ja");
  const candidates = {
    roamingAppRoot,
    roamingRuntime: join(roamingAppRoot, "runtime"),
    roamingSettings: join(roamingAppRoot, "settings"),
    localAppRoot,
    localWebView: join(localAppRoot, "webview"),
    realJaHome,
    realJaConfig: join(realJaHome, "config.toml"),
    realJaAuth: join(realJaHome, "auth.json"),
    realJaData: join(realJaHome, "data"),
  };
  const describe = async (path) => {
    try {
      const metadata = await stat(path);
      return {
        exists: true,
        size: metadata.size,
        birthtimeMs: metadata.birthtimeMs,
        ctimeMs: metadata.ctimeMs,
        mtimeMs: metadata.mtimeMs,
      };
    } catch {
      return { exists: false };
    }
  };
  const entries = await Promise.all(
    Object.entries(candidates).map(async ([name, path]) => [
      name,
      {
        path: redact(path, directories),
        metadata: await describe(path),
      },
    ]),
  );
  return { candidates: Object.fromEntries(entries) };
}

/**
 * 若隔离 sidecar 存活期间真实用户 runtime 发生变化，则冒烟失败；这里只要求元数据相等，
 * 绝不尝试 cleanup 用户拥有的目录。
 */
function assertRealRuntimeUnchanged(before, after) {
  if (JSON.stringify(before.candidates) !== JSON.stringify(after.candidates)) {
    throw new Error("真实 AppData runtime 在隔离 E2E 期间发生变化");
  }
}

/**
 * 把 Windows TCP 表投影为受当前 WebView2 进程身份约束的 loopback listener；任何越界
 * address、port 或 owner 都整体拒绝，避免把本机无关服务误接成桌面验收目标。
 */
function parseOwnedTcpListeners(text, allowedOwners) {
  if (String(text ?? "").trim() === "") return [];
  const value = JSON.parse(text);
  const rows = Array.isArray(value) ? value : [value];
  const listeners = rows.map((row) => {
    const address = row?.LocalAddress;
    const port = Number(row?.LocalPort);
    const ownerPid = Number(row?.OwningProcess);
    if (
      (address !== "127.0.0.1" && address !== "::1") ||
      !Number.isSafeInteger(port) ||
      port < 1 ||
      port > 65_535 ||
      !Number.isSafeInteger(ownerPid) ||
      !allowedOwners.has(ownerPid)
    ) {
      throw new Error("WebView2 TCP listener 投影越界");
    }
    return { address, port, ownerPid };
  });
  return listeners.filter(
    (listener, index) =>
      listeners.findIndex(
        (candidate) =>
          candidate.address === listener.address &&
          candidate.port === listener.port &&
          candidate.ownerPid === listener.ownerPid,
      ) === index,
  );
}

/**
 * 严格解析内部 runner 原子发布的 EdgeDriver ACK；session id 只用于驱动生命周期，主脚本
 * 仅消费 debugger port 与 app PID，不接受任意 host 或附加 capability。
 */
function parseEdgeDriverSession(text) {
  const value = JSON.parse(text);
  const keys = Object.keys(value ?? {}).sort();
  if (
    JSON.stringify(keys) !==
    JSON.stringify(["appPid", "browserVersion", "debuggerPort", "sessionId"])
  ) {
    throw new Error("EdgeDriver session ACK 字段漂移");
  }
  if (
    typeof value.sessionId !== "string" ||
    !/^[a-f0-9]{16,128}$/u.test(value.sessionId) ||
    !Number.isSafeInteger(value.debuggerPort) ||
    value.debuggerPort < 1 ||
    value.debuggerPort > 65_535 ||
    !Number.isSafeInteger(value.appPid) ||
    value.appPid < 1 ||
    typeof value.browserVersion !== "string" ||
    value.browserVersion.length > 64
  ) {
    throw new Error("EdgeDriver session ACK 值无效");
  }
  return value;
}

/**
 * 通过 Windows 网络栈读取已经完成 bind 的 listener；PowerShell 5.1 仅用于稳定提供
 * `Get-NetTCPConnection`，输入只由已重验的整数 PID 构造，不接受 UI 或文件内容。
 */
async function ownedTcpListeners(owners, signal) {
  const ownerPids = [...owners]
    .filter((pid) => Number.isSafeInteger(pid) && pid > 0)
    .sort((left, right) => left - right);
  if (ownerPids.length === 0) return [];
  const script = [
    "$ErrorActionPreference = 'Stop'",
    `$owners = @(${ownerPids.join(",")})`,
    "Get-NetTCPConnection -State Listen | Where-Object { ($_.LocalAddress -eq '127.0.0.1' -or $_.LocalAddress -eq '::1') -and $owners -contains [int]$_.OwningProcess } | Select-Object LocalAddress,LocalPort,OwningProcess | ConvertTo-Json -Compress",
  ].join("; ");
  const { stdout } = await execFileAsync(
    "powershell.exe",
    ["-NoProfile", "-NonInteractive", "-Command", script],
    {
      windowsHide: true,
      maxBuffer: 256 * 1024,
      timeout: snapshotTimeoutMs,
      signal,
    },
  );
  return parseOwnedTcpListeners(stdout, new Set(ownerPids));
}

/**
 * 只接受 Chromium 可能写入 `/json/version` 的回环 hostname 闭集。
 * 实际探测仍连接已由进程树和 listener owner 证明的 IP endpoint，因此接受 `localhost`
 * 不会把 DNS 或任意远端地址带入 CDP 信任边界。
 */
function isLoopbackDebuggerHostname(hostname) {
  return (
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "[::1]" ||
    hostname === "::1"
  );
}

/**
 * 从本轮已重验的 WebView2 进程树发现精确的非零调试 listener，再把 `/json/version` 与
 * 进程 owner 交叉复验。选择端口与 WebView2 bind 之间若被抢占，只会超时失败，绝不连接
 * 到不属于本轮 WebView2 身份的服务。
 */
async function waitForCdp(
  expectedPort,
  edgeDriverSessionPath,
  rootIdentity,
  incompleteObserved,
  deadline,
  launch,
  signal,
) {
  let lastObservation = "process tree not observed";
  while (Date.now() < deadline) {
    throwIfAborted(signal);
    if (launch.child.exitCode !== null || launch.child.signalCode !== null) {
      throw new Error(`Tauri launcher exited before CDP: ${launcherDiagnostic(launch)}`);
    }
    try {
      const before = await processSnapshot(signal);
      const tree = processTree(rootIdentity, before, incompleteObserved);
      if (edgeDriverSessionPath !== undefined && tree !== undefined) {
        try {
          const session = parseEdgeDriverSession(await readFile(edgeDriverSessionPath, "utf8"));
          const appIdentity = tree.get(session.appPid);
          if (appIdentity !== undefined && appIdentity.name.toLowerCase() === "ja.exe") {
            const endpoint = `http://127.0.0.1:${session.debuggerPort}`;
            const probeSignal = AbortSignal.any([
              signal,
              AbortSignal.timeout(Math.min(2_000, Math.max(1, deadline - Date.now()))),
            ]);
            const response = await globalThis.fetch(`${endpoint}/json/version`, {
              signal: probeSignal,
            });
            const version = response.ok ? await response.json() : undefined;
            const webSocket = new URL(version?.webSocketDebuggerUrl);
            const after = await processSnapshot(signal);
            const afterTree = processTree(rootIdentity, after, incompleteObserved);
            if (
              webSocket.protocol === "ws:" &&
              isLoopbackDebuggerHostname(webSocket.hostname) &&
              webSocket.port === String(session.debuggerPort) &&
              /^\/devtools\/browser\/[A-Za-z0-9._-]{1,256}$/u.test(webSocket.pathname) &&
              afterTree?.has(session.appPid) &&
              sameProcessIdentity(appIdentity, afterTree.get(session.appPid))
            ) {
              return {
                port: session.debuggerPort,
                browserPath: webSocket.pathname,
                endpoint,
                transport: "edgedriver",
              };
            }
          }
        } catch {
          // EdgeDriver 先启动 HTTP server、再创建 app、最后原子发布 ACK；未完成前继续轮询。
        }
      }
      const browsers =
        tree === undefined
          ? []
          : [...tree.values()].filter((entry) => entry.name.toLowerCase() === "msedgewebview2.exe");
      const identities = new Map(browsers.map((entry) => [entry.pid, entry]));
      const listeners = await ownedTcpListeners(identities.keys(), signal);
      const candidates = listeners.filter(
        (candidate) => expectedPort === 0 || candidate.port === expectedPort,
      );
      lastObservation = `webview2=${browsers.length} loopbackListeners=${listeners.length} candidateListeners=${candidates.length}`;
      for (const listener of candidates) {
        const host = listener.address === "::1" ? "[::1]" : "127.0.0.1";
        const endpoint = `http://${host}:${listener.port}`;
        const probeSignal = AbortSignal.any([
          signal,
          AbortSignal.timeout(Math.min(2_000, Math.max(1, deadline - Date.now()))),
        ]);
        const response = await globalThis.fetch(`${endpoint}/json/version`, {
          signal: probeSignal,
        });
        if (!response.ok) continue;
        const version = await response.json();
        const webSocket = new URL(version?.webSocketDebuggerUrl);
        const expectedPort = String(listener.port);
        if (
          webSocket.protocol !== "ws:" ||
          !isLoopbackDebuggerHostname(webSocket.hostname) ||
          webSocket.port !== expectedPort ||
          !/^\/devtools\/browser\/[A-Za-z0-9._-]{1,256}$/u.test(webSocket.pathname)
        )
          continue;
        const after = await processSnapshot(signal);
        const afterTree = processTree(rootIdentity, after, incompleteObserved);
        const expectedOwner = identities.get(listener.ownerPid);
        if (
          expectedOwner !== undefined &&
          afterTree?.has(listener.ownerPid) &&
          sameProcessIdentity(expectedOwner, afterTree.get(listener.ownerPid))
        ) {
          return {
            port: listener.port,
            browserPath: webSocket.pathname,
            endpoint,
            transport: "direct",
          };
        }
      }
    } catch (error) {
      // WebView2 初始化期间进程树、listener 与 endpoint 会分阶段出现；期限内统一重试。
      const failureKind = error instanceof Error ? error.name : typeof error;
      lastObservation = `${lastObservation} lastFailure=${failureKind}`.slice(0, 256);
    }
    await waitForDelay(pollMs, signal);
  }
  throw new Error(`WebView2 CDP 在期限内未启动 (${lastObservation})`);
}

/**
 * 等待全新隔离 UDF 完成一次真实 WebView2 初始化。Runtime 151 的 fresh profile 会在首轮
 * 忽略调试 listener，但退出后同一 UDF 可正常启用 CDP；因此用文件与进程身份做预热 ACK，
 * 不通过固定 sleep、真实用户 profile 或测试专用产品 command 猜测就绪。
 */
async function waitForWebViewProfileReady(
  rootIdentity,
  incompleteObserved,
  directories,
  deadline,
  launch,
  signal,
) {
  const profileRoot = join(directories.webview, "EBWebView");
  const expectedProfileKey = windowsPathKey(profileRoot);
  let lastObservation = "profile process tree not observed";
  while (Date.now() < deadline) {
    throwIfAborted(signal);
    if (launch.child.exitCode !== null || launch.child.signalCode !== null) {
      throw new Error(
        `Tauri launcher exited before WebView2 profile ACK: ${launcherDiagnostic(launch)}`,
      );
    }
    try {
      const snapshot = await processSnapshot(signal);
      const tree = processTree(rootIdentity, snapshot, incompleteObserved);
      const products = tree === undefined ? [] : [...tree.values()];
      const browsers = products.filter(
        (entry) => entry.name.toLowerCase() === "msedgewebview2.exe",
      );
      const browser = browsers.find(
        (entry) =>
          !entry.commandLine.includes("--type=") &&
          windowsPathKey(entry.commandLine).includes(expectedProfileKey),
      );
      const renderer = browsers.find((entry) => entry.commandLine.includes("--type=renderer"));
      const app = products.find((entry) => entry.name.toLowerCase() === "ja.exe");
      let localState;
      let preferences;
      try {
        [localState, preferences] = await Promise.all([
          stat(join(profileRoot, "Local State")),
          stat(join(profileRoot, "Default", "Preferences")),
        ]);
      } catch {
        // WebView2 会先创建 browser process，再原子发布 profile 文件；期限内继续轮询。
      }
      lastObservation = `webview2=${browsers.length} browser=${browser === undefined ? 0 : 1} renderer=${renderer === undefined ? 0 : 1} app=${app === undefined ? 0 : 1}`;
      if (
        browser !== undefined &&
        renderer !== undefined &&
        app !== undefined &&
        localState?.isFile() &&
        localState.size > 0 &&
        preferences?.isFile() &&
        preferences.size > 0
      ) {
        return {
          browserPid: browser.pid,
          webviewProcesses: browsers.length,
          localStateBytes: localState.size,
          preferencesBytes: preferences.size,
        };
      }
    } catch (error) {
      const failureKind = error instanceof Error ? error.name : typeof error;
      lastObservation = `${lastObservation} lastFailure=${failureKind}`.slice(0, 256);
    }
    await waitForDelay(pollMs, signal);
  }
  throw new Error(`WebView2 隔离 profile 在期限内未完成预热 (${lastObservation})`);
}

/**
 * 定位真实 Tauri WebView 页面，同时忽略 devtools/blank target；
 * 后续还会检查页面文本，单纯连接 CDP socket 不足以通过验收。
 */
async function waitForPage(browser, frontendPort, deadline, signal) {
  while (Date.now() < deadline) {
    throwIfAborted(signal);
    for (const context of browser.contexts()) {
      for (const page of context.pages()) {
        const url = page.url();
        if (url.includes(`localhost:${frontendPort}`) || url.includes("tauri://localhost")) {
          return page;
        }
      }
    }
    await waitForDelay(pollMs, signal);
  }
  throw new Error("未找到 Ja Tauri WebView 页面");
}

/**
 * 只捕获归类 E2E 故障所需的有界浏览器诊断；路径与疑似 token 的值在进入 summary 前会被脱敏。
 */
function attachPageDiagnostics(page, directories) {
  const diagnostics = { console: [], pageErrors: [], requestFailed: [] };
  const append = (target, value) => {
    if (target.length < 20) {
      target.push(redact(value, directories));
    }
  };
  page.on("console", (message) =>
    append(diagnostics.console, `${message.type()}: ${message.text()}`),
  );
  page.on("pageerror", (error) =>
    append(diagnostics.pageErrors, error?.stack || error?.message || error?.name || String(error)),
  );
  page.on("requestfailed", (request) =>
    append(
      diagnostics.requestFailed,
      `${request.method()} ${request.url()} ${request.failure()?.errorText ?? "failed"}`,
    ),
  );
  return diagnostics;
}

/** Tauri bridge 就绪后安装 RPC、workspace 与 native shortcut 三个有界只读 listener。 */
async function installRawTauriEventProbe(page) {
  await page.waitForFunction(
    () => {
      const internals = globalThis.__TAURI_INTERNALS__;
      return (
        internals !== undefined &&
        typeof internals.transformCallback === "function" &&
        typeof internals.invoke === "function"
      );
    },
    undefined,
    { timeout: 30_000 },
  );
  await page
    .evaluate(async () => {
      const internals = globalThis.__TAURI_INTERNALS__;
      if (
        internals === undefined ||
        typeof internals.transformCallback !== "function" ||
        typeof internals.invoke !== "function"
      ) {
        return;
      }
      globalThis.__JA_E2E_TAURI_EVENTS__ = [];
      globalThis.__JA_E2E_WORKSPACE_EVENTS__ = [];
      globalThis.__JA_E2E_NATIVE_SHORTCUT_EVENTS__ = [];
      const handler = internals.transformCallback((value) => {
        const list = Array.isArray(globalThis.__JA_E2E_TAURI_EVENTS__)
          ? globalThis.__JA_E2E_TAURI_EVENTS__
          : [];
        if (list.length < 128) {
          list.push(
            value !== null && typeof value === "object"
              ? { ...value, __jaObservedAt: globalThis.performance.now() }
              : value,
          );
        }
        globalThis.__JA_E2E_TAURI_EVENTS__ = list;
      });
      globalThis.__JA_E2E_TAURI_LISTENER_ID__ = await internals.invoke("plugin:event|listen", {
        event: "ja://rpc/frame",
        target: { kind: "Any" },
        handler,
      });
      /**
       * 保持 workspace 诊断与高流量 RPC stream 相互独立。
       * 只有路径分类与 revision 是否存在会跨越 E2E 边界，避免 workspace 路径、文件 hash 或内容进入产物。
       */
      const workspaceHandler = internals.transformCallback((value) => {
        try {
          const envelope = value !== null && typeof value === "object" ? value : {};
          const payload =
            envelope.payload !== null && typeof envelope.payload === "object"
              ? envelope.payload
              : envelope;
          const relativePath =
            typeof payload.relativePath === "string" ? payload.relativePath : undefined;
          const list = Array.isArray(globalThis.__JA_E2E_WORKSPACE_EVENTS__)
            ? globalThis.__JA_E2E_WORKSPACE_EVENTS__
            : [];
          if (list.length < 64) {
            list.push({
              sampleFile: relativePath === "sample.ts",
              rootMarker: relativePath === "",
              generation: Number.isSafeInteger(payload.generation) ? payload.generation : undefined,
              requiresRescan: payload.requiresRescan === true,
              revisionPresent: payload.revision !== null && typeof payload.revision === "object",
            });
          }
          globalThis.__JA_E2E_WORKSPACE_EVENTS__ = list;
        } catch {
          // 诊断隔离不得改变生产 listener 链路，因此异常只能在诊断侧吸收。
        }
      });
      globalThis.__JA_E2E_WORKSPACE_LISTENER_STATUS__ = "registering";
      try {
        globalThis.__JA_E2E_WORKSPACE_LISTENER_ID__ = await internals.invoke(
          "plugin:event|listen",
          {
            event: "ja://workspace-changed",
            target: { kind: "Any" },
            handler: workspaceHandler,
          },
        );
        globalThis.__JA_E2E_WORKSPACE_LISTENER_STATUS__ = "ready";
      } catch {
        globalThis.__JA_E2E_WORKSPACE_LISTENER_STATUS__ = "failed";
      }
      /** 只记录固定五值和 revision，不复制随机 epoch 或 native 诊断。 */
      const nativeHandler = internals.transformCallback((value) => {
        const envelope = value !== null && typeof value === "object" ? value : {};
        const payload =
          envelope.payload !== null && typeof envelope.payload === "object"
            ? envelope.payload
            : envelope;
        if (
          !["review", "files", "terminal", "preview", "side_chat"].includes(payload.command) ||
          !Number.isSafeInteger(payload.revision) ||
          payload.revision < 1
        )
          return;
        const list = Array.isArray(globalThis.__JA_E2E_NATIVE_SHORTCUT_EVENTS__)
          ? globalThis.__JA_E2E_NATIVE_SHORTCUT_EVENTS__
          : [];
        if (list.length < 128) list.push({ command: payload.command, revision: payload.revision });
        globalThis.__JA_E2E_NATIVE_SHORTCUT_EVENTS__ = list;
      });
      globalThis.__JA_E2E_NATIVE_SHORTCUT_LISTENER_STATUS__ = "registering";
      try {
        globalThis.__JA_E2E_NATIVE_SHORTCUT_LISTENER_ID__ = await internals.invoke(
          "plugin:event|listen",
          { event: "ja://native-shortcut", target: { kind: "Any" }, handler: nativeHandler },
        );
        globalThis.__JA_E2E_NATIVE_SHORTCUT_LISTENER_STATUS__ = "ready";
      } catch {
        globalThis.__JA_E2E_NATIVE_SHORTCUT_LISTENER_STATUS__ = "failed";
      }
    })
    .catch(() => undefined);
}

/** WebView 关闭前逐个释放诊断 listener，避免旧 callback id 残留。 */
async function removeRawTauriEventProbe(page) {
  await page
    .evaluate(async () => {
      const internals = globalThis.__TAURI_INTERNALS__;
      if (internals === undefined || typeof internals.invoke !== "function") {
        return;
      }
      const listeners = [
        { event: "ja://rpc/frame", eventId: globalThis.__JA_E2E_TAURI_LISTENER_ID__ },
        { event: "ja://workspace-changed", eventId: globalThis.__JA_E2E_WORKSPACE_LISTENER_ID__ },
        {
          event: "ja://native-shortcut",
          eventId: globalThis.__JA_E2E_NATIVE_SHORTCUT_LISTENER_ID__,
        },
      ];
      await Promise.all(
        listeners.map(async ({ event, eventId }) => {
          if (eventId !== undefined) {
            await internals.invoke("plugin:event|unlisten", { event, eventId });
          }
        }),
      );
      globalThis.__JA_E2E_TAURI_LISTENER_ID__ = undefined;
      globalThis.__JA_E2E_WORKSPACE_LISTENER_ID__ = undefined;
      globalThis.__JA_E2E_NATIVE_SHORTCUT_LISTENER_ID__ = undefined;
      globalThis.__JA_E2E_WORKSPACE_LISTENER_STATUS__ = "removed";
      globalThis.__JA_E2E_NATIVE_SHORTCUT_LISTENER_STATUS__ = "removed";
    })
    .catch(() => undefined);
}

/**
 * 在页面最早脚本阶段安装 Ja adapter probe，使 hard reload 不会漏掉 React 启动期
 * 的 native lease/context ACK；函数不捕获 Node 状态，才能安全传给 addInitScript。
 */
function installTauriInvokeProbeInPage() {
  if (typeof globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ === "function") return;
  if (!Array.isArray(globalThis.__JA_E2E_TAURI_INVOKES__)) globalThis.__JA_E2E_TAURI_INVOKES__ = [];
  if (
    globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ === null ||
    typeof globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ !== "object"
  )
    globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ = {};
  if (
    globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__ === null ||
    typeof globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__ !== "object"
  )
    globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__ = {};
  if (
    globalThis.__JA_E2E_WORKSPACE_WATCH_LIFECYCLE__ === null ||
    typeof globalThis.__JA_E2E_WORKSPACE_WATCH_LIFECYCLE__ !== "object"
  )
    globalThis.__JA_E2E_WORKSPACE_WATCH_LIFECYCLE__ = {};
  if (!Number.isSafeInteger(globalThis.__JA_E2E_TERMINAL_INPUT_COUNT__))
    globalThis.__JA_E2E_TERMINAL_INPUT_COUNT__ = 0;
  if (typeof globalThis.__JA_E2E_SUPPRESS_LOOPBACK_OPENER__ !== "boolean")
    globalThis.__JA_E2E_SUPPRESS_LOOPBACK_OPENER__ = false;
  if (typeof globalThis.__JA_E2E_FAIL_NEXT_TERMINAL_CLOSE_ALL__ !== "boolean")
    globalThis.__JA_E2E_FAIL_NEXT_TERMINAL_CLOSE_ALL__ = false;
  globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = async ({ command, args }, delegate) => {
    const calls = Array.isArray(globalThis.__JA_E2E_TAURI_INVOKES__)
      ? globalThis.__JA_E2E_TAURI_INVOKES__
      : [];
    const append = (entry) => {
      const counts =
        globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ !== null &&
        typeof globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ === "object"
          ? globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__
          : {};
      const bucket = counts[entry.command] ?? {};
      const phase = typeof entry.phase === "string" ? entry.phase : "start";
      bucket[phase] = Number.isSafeInteger(bucket[phase]) ? bucket[phase] + 1 : 1;
      if (entry.previewVisible === true || entry.previewVisible === false) {
        const visibleKey = `${phase}Visible${entry.previewVisible ? "True" : "False"}`;
        bucket[visibleKey] = Number.isSafeInteger(bucket[visibleKey]) ? bucket[visibleKey] + 1 : 1;
      }
      if (phase === "start" && (entry.suppressed === true || entry.suppressed === false)) {
        const suppressedKey = `startSuppressed${entry.suppressed ? "True" : "False"}`;
        bucket[suppressedKey] = Number.isSafeInteger(bucket[suppressedKey])
          ? bucket[suppressedKey] + 1
          : 1;
      }
      counts[entry.command] = bucket;
      globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ = counts;
      const traces =
        globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__ !== null &&
        typeof globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__ === "object"
          ? globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__
          : {};
      const commandTrace = Array.isArray(traces[entry.command]) ? traces[entry.command] : [];
      if (commandTrace.length < 64) commandTrace.push(entry);
      traces[entry.command] = commandTrace;
      globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__ = traces;
      if (calls.length < 512) calls.push(entry);
      globalThis.__JA_E2E_TAURI_INVOKES__ = calls;
    };
    const url =
      command === "plugin:opener|open_url" && typeof args?.url === "string" ? args.url : undefined;
    const suppress =
      command === "plugin:opener|open_url" &&
      globalThis.__JA_E2E_SUPPRESS_LOOPBACK_OPENER__ === true &&
      typeof url === "string" &&
      /^http:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?(?:\/|$)/u.test(url);
    const terminalInput =
      command === "ja_terminal_input" && args?.input !== null && typeof args?.input === "object"
        ? args.input
        : undefined;
    const terminalInputMetadata =
      terminalInput === undefined
        ? {}
        : {
            sessionId:
              typeof terminalInput.sessionId === "string" ? terminalInput.sessionId : undefined,
            generation: Number.isSafeInteger(terminalInput.generation)
              ? terminalInput.generation
              : undefined,
            dataLength: Array.isArray(terminalInput.data) ? terminalInput.data.length : undefined,
          };
    if (terminalInput !== undefined) globalThis.__JA_E2E_TERMINAL_INPUT_COUNT__ += 1;
    const previewVisible =
      command === "ja_preview_layout" && args?.input?.viewport?.visible === true;
    const nativeShortcutContext =
      command === "ja_native_shortcut_context_update" &&
      args?.input !== null &&
      typeof args?.input === "object"
        ? args.input
        : undefined;
    const nativeShortcutMetadata =
      nativeShortcutContext === undefined
        ? {}
        : {
            projectCapabilitiesEnabled: nativeShortcutContext.projectCapabilitiesEnabled === true,
            conversationFocusEnabled: nativeShortcutContext.conversationFocusEnabled === true,
          };
    // Workspace read/watch 阶段刻意不记录路径、revision 或 workspace ID。
    // Files 冒烟通过这些计数区分原生事件投递与权威读取投影；若省略，
    // 每次成功读取都会被误判为 timeout。
    const workspaceLifecycleObserved = [
      "ja_workspace_read_file",
      "ja_workspace_watch_start",
      "ja_workspace_watch_rescan",
      "ja_workspace_watch_stop",
    ].includes(command);
    if (command === "ja_workspace_watch_start" && Number.isSafeInteger(args?.input?.generation)) {
      globalThis.__JA_E2E_WORKSPACE_WATCH_LIFECYCLE__.latestStartRequested = args.input.generation;
    }
    if (command === "ja_workspace_watch_stop" && Number.isSafeInteger(args?.input?.generation)) {
      globalThis.__JA_E2E_WORKSPACE_WATCH_LIFECYCLE__.latestStopRequested = args.input.generation;
    }
    const observed =
      command === "plugin:opener|open_url" ||
      command === "ja_turn_start" ||
      command === "ja_terminal_open" ||
      command === "ja_terminal_input" ||
      command === "ja_terminal_poll" ||
      command === "ja_terminal_close_all" ||
      command === "ja_native_shortcut_lease_query" ||
      command === "ja_native_shortcut_context_update" ||
      command === "ja_native_shortcut_context_activate" ||
      command === "ja_workspace_trash_commit" ||
      workspaceLifecycleObserved ||
      command === "ja_preview_open" ||
      command === "ja_preview_navigate" ||
      command === "ja_preview_layout" ||
      command === "ja_preview_close";
    if (observed)
      append({
        command,
        suppressed: suppress,
        phase: "start",
        previewVisible,
        ...terminalInputMetadata,
        ...nativeShortcutMetadata,
      });
    if (suppress) return undefined;
    try {
      if (
        command === "ja_terminal_close_all" &&
        globalThis.__JA_E2E_FAIL_NEXT_TERMINAL_CLOSE_ALL__ === true
      ) {
        globalThis.__JA_E2E_FAIL_NEXT_TERMINAL_CLOSE_ALL__ = false;
        throw new Error("JA_E2E_INJECTED_TERMINAL_CLOSE_ALL_FAILURE");
      }
      const result = await delegate();
      if (command === "ja_turn_start") {
        const accepted = result !== null && typeof result === "object" ? result : {};
        append({
          command,
          phase: "resolved",
          turnId: typeof accepted.turnId === "string" ? accepted.turnId : undefined,
          threadRevision: Number.isSafeInteger(accepted.threadRevision)
            ? accepted.threadRevision
            : undefined,
        });
      } else if (command === "ja_terminal_open") {
        const identity = result !== null && typeof result === "object" ? result : {};
        append({
          command,
          phase: "resolved",
          sessionId: typeof identity.sessionId === "string" ? identity.sessionId : undefined,
          generation: Number.isSafeInteger(identity.generation) ? identity.generation : undefined,
        });
      } else if (command === "ja_terminal_input") {
        append({ command, phase: "resolved", ...terminalInputMetadata });
      } else if (command === "ja_terminal_poll") {
        append({ command, phase: "resolved" });
      } else if (command === "ja_terminal_close_all") {
        append({ command, phase: "resolved" });
      } else if (
        [
          "ja_native_shortcut_lease_query",
          "ja_native_shortcut_context_update",
          "ja_native_shortcut_context_activate",
        ].includes(command)
      ) {
        const snapshot = result !== null && typeof result === "object" ? result : {};
        // Activate 只提交 opaque epoch/revision，能力真值必须从 Rust ACK 读取；否则
        // hard reload 会把更早的全 false ACK 误认成 Side Chat 已可用并过早发键。
        const resolvedNativeShortcutMetadata =
          typeof snapshot.projectCapabilitiesEnabled === "boolean" &&
          typeof snapshot.conversationFocusEnabled === "boolean"
            ? {
                projectCapabilitiesEnabled: snapshot.projectCapabilitiesEnabled,
                conversationFocusEnabled: snapshot.conversationFocusEnabled,
              }
            : nativeShortcutMetadata;
        append({
          command,
          phase: "resolved",
          ...resolvedNativeShortcutMetadata,
          ready: snapshot.ready === true,
          mainHandlerStatus: ["pending", "ready", "unavailable", "unsupported"].includes(
            snapshot.mainHandlerStatus,
          )
            ? snapshot.mainHandlerStatus
            : undefined,
        });
      } else if (workspaceLifecycleObserved) {
        if (command === "ja_workspace_watch_start" && Number.isSafeInteger(result?.generation)) {
          globalThis.__JA_E2E_WORKSPACE_WATCH_LIFECYCLE__.latestStartResolved = result.generation;
        }
        append({ command, phase: "resolved" });
      } else if (
        [
          "ja_preview_open",
          "ja_preview_navigate",
          "ja_preview_layout",
          "ja_preview_close",
        ].includes(command)
      ) {
        append({ command, phase: "resolved", previewVisible });
      }
      return result;
    } catch (error) {
      if (
        command === "ja_turn_start" ||
        command === "ja_terminal_open" ||
        command === "ja_terminal_close_all" ||
        command === "ja_terminal_input" ||
        command === "ja_terminal_poll" ||
        command === "ja_native_shortcut_lease_query" ||
        command === "ja_native_shortcut_context_update" ||
        command === "ja_native_shortcut_context_activate" ||
        workspaceLifecycleObserved ||
        [
          "ja_preview_open",
          "ja_preview_navigate",
          "ja_preview_layout",
          "ja_preview_close",
        ].includes(command)
      ) {
        const candidate = error !== null && typeof error === "object" ? error : {};
        append({
          command,
          phase: "rejected",
          previewVisible,
          errorCode: typeof candidate.code === "string" ? candidate.code : undefined,
          ...terminalInputMetadata,
          ...nativeShortcutMetadata,
        });
      }
      throw error;
    }
  };
}

/**
 * 在 Ja 自有 adapter seam 透明记录安全生命周期。Tauri 2.11 的 injected
 * invoke 是不可写属性，因此 smoke 不再伪装成已成功 monkey-patch 原生对象。
 * 全局诊断与每命令 phase trace 分别限流，避免高频 Preview 事件挤掉后续关闭事务。
 */
async function installTauriInvokeProbe(page) {
  await page.evaluate(installTauriInvokeProbeInPage);
}

/** CDP client detach 或 hard reload 前释放 Ja 自有 probe，避免诊断状态跨阶段存活。 */
async function removeTauriInvokeProbe(page) {
  await page
    .evaluate(() => {
      globalThis.__JA_E2E_NATIVE_INVOKE_PROBE__ = undefined;
      globalThis.__JA_E2E_SUPPRESS_LOOPBACK_OPENER__ = false;
      globalThis.__JA_E2E_FAIL_NEXT_TERMINAL_CLOSE_ALL__ = false;
    })
    .catch(() => undefined);
}

/** 只统计命令调用次数，不读取或序列化其参数 envelope，避免诊断越过数据边界。 */
async function tauriInvokeCount(page, command, suppressed) {
  await installTauriInvokeProbe(page);
  return page.evaluate(
    ({ expectedCommand, expectedSuppressed }) => {
      const counts =
        globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ !== null &&
        typeof globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ === "object"
          ? globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__
          : {};
      const bucket = counts[expectedCommand] ?? {};
      const key =
        expectedSuppressed === undefined
          ? "start"
          : `startSuppressed${expectedSuppressed ? "True" : "False"}`;
      return Number.isSafeInteger(bucket[key]) ? bucket[key] : 0;
    },
    { expectedCommand: command, expectedSuppressed: suppressed },
  );
}

/** 返回单个命令的有界生命周期 trace，不依赖全局诊断上限，避免高流量事件遮蔽关闭事务。 */
async function tauriInvokeTrace(page, command) {
  await installTauriInvokeProbe(page);
  return page.evaluate((expectedCommand) => {
    const traces =
      globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__ !== null &&
      typeof globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__ === "object"
        ? globalThis.__JA_E2E_TAURI_INVOKE_PHASE_TRACES__
        : {};
    const calls = Array.isArray(traces[expectedCommand]) ? traces[expectedCommand] : [];
    return calls.map((call, index) => ({
      index,
      phase: typeof call.phase === "string" ? call.phase : "start",
      sessionId: typeof call.sessionId === "string" ? call.sessionId : undefined,
      generation: Number.isSafeInteger(call.generation) ? call.generation : undefined,
      dataLength: Number.isSafeInteger(call.dataLength) ? call.dataLength : undefined,
      turnId: typeof call.turnId === "string" ? call.turnId : undefined,
      threadRevision: Number.isSafeInteger(call.threadRevision) ? call.threadRevision : undefined,
      errorCode: typeof call.errorCode === "string" ? call.errorCode : undefined,
      projectCapabilitiesEnabled: call.projectCapabilitiesEnabled === true,
      conversationFocusEnabled: call.conversationFocusEnabled === true,
      ready: call.ready === true,
      mainHandlerStatus: ["pending", "ready", "unavailable", "unsupported"].includes(
        call.mainHandlerStatus,
      )
        ? call.mainHandlerStatus
        : undefined,
    }));
  }, command);
}

/** 读取不含 terminal data 的累计次数，避免 512 条诊断上限产生假阴性。 */
async function terminalInputInvokeCount(page) {
  await installTauriInvokeProbe(page);
  return page.evaluate(() =>
    Number.isSafeInteger(globalThis.__JA_E2E_TERMINAL_INPUT_COUNT__)
      ? globalThis.__JA_E2E_TERMINAL_INPUT_COUNT__
      : 0,
  );
}

/** 只统计安全 phase/visible 字段，用于等待 Preview native ACK。 */
async function tauriInvokePhaseCount(page, command, phase, previewVisible) {
  await installTauriInvokeProbe(page);
  return page.evaluate(
    ({ expectedCommand, expectedPhase, expectedVisible }) => {
      const counts =
        globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ !== null &&
        typeof globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ === "object"
          ? globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__
          : {};
      const bucket = counts[expectedCommand] ?? {};
      const key =
        expectedVisible === undefined
          ? expectedPhase
          : `${expectedPhase}Visible${expectedVisible ? "True" : "False"}`;
      return Number.isSafeInteger(bucket[key]) ? bucket[key] : 0;
    },
    { expectedCommand: command, expectedPhase: phase, expectedVisible: previewVisible },
  );
}

/**
 * 只捕获聚合的 workspace 生命周期阶段与 opaque generation，使失败的真窗运行能区分
 * listener 漏接和 session 抖动，同时不序列化 workspace ID、路径、revision 或文件内容。
 */
async function captureWorkspaceInvokeLifecycle(page) {
  await installTauriInvokeProbe(page);
  return page.evaluate(() => {
    const counts =
      globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ !== null &&
      typeof globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__ === "object"
        ? globalThis.__JA_E2E_TAURI_INVOKE_COUNTS__
        : {};
    const lifecycle =
      globalThis.__JA_E2E_WORKSPACE_WATCH_LIFECYCLE__ !== null &&
      typeof globalThis.__JA_E2E_WORKSPACE_WATCH_LIFECYCLE__ === "object"
        ? globalThis.__JA_E2E_WORKSPACE_WATCH_LIFECYCLE__
        : {};
    const commands = [
      "ja_workspace_watch_start",
      "ja_workspace_watch_stop",
      "ja_workspace_watch_rescan",
      "ja_workspace_read_file",
    ];
    return {
      commands: Object.fromEntries(
        commands.map((command) => {
          const bucket = counts[command] ?? {};
          return [
            command,
            {
              start: Number.isSafeInteger(bucket.start) ? bucket.start : 0,
              resolved: Number.isSafeInteger(bucket.resolved) ? bucket.resolved : 0,
              rejected: Number.isSafeInteger(bucket.rejected) ? bucket.rejected : 0,
            },
          ];
        }),
      ),
      latestStartRequested: Number.isSafeInteger(lifecycle.latestStartRequested)
        ? lifecycle.latestStartRequested
        : undefined,
      latestStartResolved: Number.isSafeInteger(lifecycle.latestStartResolved)
        ? lifecycle.latestStartResolved
        : undefined,
      latestStopRequested: Number.isSafeInteger(lifecycle.latestStopRequested)
        ? lifecycle.latestStopRequested
        : undefined,
    };
  });
}

/** 返回固定五值事件；不复制 epoch 或 native 诊断。 */
async function captureNativeShortcutEvents(page) {
  return page.evaluate(() => ({
    status: globalThis.__JA_E2E_NATIVE_SHORTCUT_LISTENER_STATUS__,
    events: Array.isArray(globalThis.__JA_E2E_NATIVE_SHORTCUT_EVENTS__)
      ? [...globalThis.__JA_E2E_NATIVE_SHORTCUT_EVENTS__]
      : [],
  }));
}

/** 读取有界 native shortcut 事件数，并先确认 probe 已就绪。 */
async function nativeShortcutEventCount(page) {
  const trace = await captureNativeShortcutEvents(page);
  if (trace.status !== "ready")
    throw new Error(`native shortcut probe 未就绪：${trace.status ?? "missing"}`);
  return trace.events.length;
}

/** 只收集诊断 native gate 丢失所需的固定、已脱敏 lease/prepare/activate trace。 */
async function captureNativeShortcutInvokeLifecycle(page) {
  const [leaseQuery, contextUpdate, contextActivate] = await Promise.all([
    tauriInvokeTrace(page, "ja_native_shortcut_lease_query"),
    tauriInvokeTrace(page, "ja_native_shortcut_context_update"),
    tauriInvokeTrace(page, "ja_native_shortcut_context_activate"),
  ]);
  return { leaseQuery, contextUpdate, contextActivate };
}

/** 只从真实 pane 投影读取当前 opaque 原生身份，避免测试构造第二份状态事实。 */
async function terminalSessionIdentities(terminalWorkspace) {
  return terminalWorkspace
    .locator(".ja-terminal-pane[data-terminal-session-id][data-terminal-session-generation]")
    .evaluateAll((panes) =>
      panes.flatMap((pane) => {
        const sessionId = pane.getAttribute("data-terminal-session-id");
        const generation = Number.parseInt(
          pane.getAttribute("data-terminal-session-generation") ?? "",
          10,
        );
        return typeof sessionId === "string" &&
          sessionId.length > 0 &&
          Number.isSafeInteger(generation) &&
          generation > 0
          ? [{ sessionId, generation }]
          : [];
      }),
    );
}

/** 注入一次 renderer 边界失败；重试仍调用真实原生 close-all 命令，以验证恢复链路而非 mock 成功。 */
async function injectNextTerminalCloseAllFailure(page) {
  await installTauriInvokeProbe(page);
  await page.evaluate(() => {
    globalThis.__JA_E2E_FAIL_NEXT_TERMINAL_CLOSE_ALL__ = true;
  });
}

/** 为有界 URL 点击检查启用仅限 loopback 的 external opener 防护，避免验收触发真实外部副作用。 */
async function suppressLoopbackExternalOpen(page, enabled) {
  await installTauriInvokeProbe(page);
  await page.evaluate((next) => {
    globalThis.__JA_E2E_SUPPRESS_LOOPBACK_OPENER__ = next;
  }, enabled);
}

/**
 * 将 native callback 缩减为可区分“已送达”和“已投影”的方法、身份、顺序与合同校验事实。
 * Schema 失败只记录 issue path/code；诊断不持久化 prompt、路径、Tool 参数、原始错误或 Secret。
 */
async function captureRawTauriEvents(page) {
  try {
    const values = await page.evaluate(async () => {
      const { JaEventSchema } = await import("/src/api/protocol/protocol.ts");
      const events = Array.isArray(globalThis.__JA_E2E_TAURI_EVENTS__)
        ? globalThis.__JA_E2E_TAURI_EVENTS__
        : [];
      return events.slice(-128).map((value) => {
        const envelope = value !== null && typeof value === "object" ? value : {};
        const payload = envelope.payload !== undefined ? envelope.payload : envelope;
        const parsed = JaEventSchema.safeParse(payload);
        return {
          value,
          contractIssues: parsed.success
            ? []
            : parsed.error.issues.slice(0, 8).map((issue) => ({
                path: issue.path.map(String).join("."),
                code: issue.code,
              })),
        };
      });
    });
    return values.map(({ value, contractIssues }) => {
      const envelope = value !== null && typeof value === "object" ? value : {};
      const payload = envelope.payload !== undefined ? envelope.payload : envelope;
      const root = payload !== null && typeof payload === "object" ? payload : {};
      const params = root.params !== null && typeof root.params === "object" ? root.params : {};
      return {
        event: typeof envelope.event === "string" ? envelope.event : undefined,
        method: typeof root.method === "string" ? root.method : undefined,
        threadId: typeof params.threadId === "string" ? params.threadId : undefined,
        seq: Number.isSafeInteger(params.seq) ? params.seq : undefined,
        sequence: Number.isSafeInteger(params.sequence) ? params.sequence : undefined,
        threadRevision: Number.isSafeInteger(params.threadRevision)
          ? params.threadRevision
          : undefined,
        observedAt: Number.isFinite(envelope.__jaObservedAt) ? envelope.__jaObservedAt : undefined,
        generation: Number.isSafeInteger(params.generation) ? params.generation : undefined,
        eventId: typeof params.eventId === "string" ? params.eventId : undefined,
        serverInstanceId:
          typeof params.serverInstanceId === "string" ? params.serverInstanceId : undefined,
        turnId: typeof params.turnId === "string" ? params.turnId : undefined,
        status: typeof params.status === "string" ? params.status : undefined,
        terminalState: typeof params.state === "string" ? params.state : undefined,
        approvalId: typeof params.approvalId === "string" ? params.approvalId : undefined,
        expiresAt: typeof params.expiresAt === "string" ? params.expiresAt : undefined,
        callId: typeof params.callId === "string" ? params.callId : undefined,
        toolName: typeof params.toolName === "string" ? params.toolName : undefined,
        response: typeof params.response === "string" ? params.response : undefined,
        contractIssues,
      };
    });
  } catch {
    return [];
  }
}

/**
 * 读取独立且已脱敏的 workspace watcher probe。保持数组分离，
 * 可在 RPC frame 达到上限时仍证明原生投递发生。
 */
async function captureWorkspaceWatcherEvents(page) {
  try {
    const probe = await page.evaluate(() => ({
      status:
        typeof globalThis.__JA_E2E_WORKSPACE_LISTENER_STATUS__ === "string"
          ? globalThis.__JA_E2E_WORKSPACE_LISTENER_STATUS__
          : "missing",
      events: Array.isArray(globalThis.__JA_E2E_WORKSPACE_EVENTS__)
        ? globalThis.__JA_E2E_WORKSPACE_EVENTS__
        : [],
    }));
    return {
      status: probe.status,
      events: probe.events.slice(-64).map((value) => ({
        sampleFile: value?.sampleFile === true,
        rootMarker: value?.rootMarker === true,
        generation: Number.isSafeInteger(value?.generation) ? value.generation : undefined,
        requiresRescan: value?.requiresRescan === true,
        revisionPresent: value?.revisionPresent === true,
      })),
    };
  } catch {
    return { status: "unavailable", events: [] };
  }
}

/**
 * 只返回证明焦点协调所需的有界 watcher 事件数与 session generation，
 * 避免把 rescan 误当成新生命周期。
 */
async function workspaceWatcherState(page) {
  return page.evaluate(() => {
    const values = Array.isArray(globalThis.__JA_E2E_WORKSPACE_EVENTS__)
      ? globalThis.__JA_E2E_WORKSPACE_EVENTS__
      : [];
    return {
      count: values.length,
      sampleFileCount: values.filter((value) => value?.sampleFile === true).length,
      generation: values.reduce(
        (highest, value) =>
          Number.isSafeInteger(value?.generation) ? Math.max(highest, value.generation) : highest,
        0,
      ),
      generations: values.map((value) =>
        Number.isSafeInteger(value?.generation) ? value.generation : null,
      ),
    };
  });
}

/**
 * 只从实时 Vite module 读取生命周期安全的 reducer 字段，使真窗失败能区分 parser 拒绝、
 * sequence resync 与陈旧 Composer pending gate，同时不序列化对话文本或任何 provider-owned payload。
 */
async function captureTimelineReducerState(page) {
  try {
    return await page.evaluate(async () => {
      const { useTimelineStore } = await import(
        "/src/features/conversation/application/timelineStore.ts"
      );
      const state = useTimelineStore.getState();
      return {
        lastOutcome: state.lastOutcome,
        serverInstanceId: state.serverInstanceId,
        handshake: state.handshake,
        threadRevisionByThread: state.threadRevisionByThread,
        streamSeqByTurn: state.streamSeqByTurn,
        resyncRequired: state.resyncRequired,
        turns: Object.fromEntries(
          Object.entries(state.turns).map(([turnId, turn]) => [
            turnId,
            {
              threadId: turn.threadId,
              status: turn.status,
            },
          ]),
        ),
      };
    });
  } catch {
    return { unavailable: true };
  }
}

/**
 * 从真实 WebView2 读取 Files 布局和唯一滚动端口；几何只用于验收自适应契约，
 * 不作为产品状态或固定像素快照，避免 DPI/窗口变化产生脆弱断言。
 */
async function captureFilesTreeGeometry(filesWorkspace) {
  return filesWorkspace.evaluate((workspace) => {
    const explorer = workspace.querySelector(".ja-files-workspace-explorer");
    const host = workspace.querySelector(".ja-file-tree-host");
    const toolbar = workspace.querySelector(".ja-file-tree-toolbar");
    const viewport = workspace.querySelector(".ja-file-tree-viewport");
    const tree = viewport?.querySelector('[role="tree"]');
    if (
      explorer === null ||
      host === null ||
      toolbar === null ||
      viewport === null ||
      tree === null
    ) {
      return { valid: false, reason: "missing_files_geometry_surface" };
    }
    const explorerRect = explorer.getBoundingClientRect();
    const hostRect = host.getBoundingClientRect();
    const toolbarRect = toolbar.getBoundingClientRect();
    const viewportRect = viewport.getBoundingClientRect();
    return {
      valid: true,
      explorerHeight: explorerRect.height,
      hostHeight: hostRect.height,
      hostBottom: hostRect.bottom,
      toolbarHeight: toolbarRect.height,
      viewportHeight: viewportRect.height,
      viewportBottom: viewportRect.bottom,
      treeClientHeight: tree.clientHeight,
      treeScrollHeight: tree.scrollHeight,
      overflowY: tree.scrollHeight > tree.clientHeight + 1,
    };
  });
}

/**
 * 只捕获 fixture 专属的 Files 投影标志。probe 省略任意 tree label 与 editor 内容，
 * 确保用户 workspace 数据不会进入证据。
 */
async function captureFilesWorkspaceState(page) {
  try {
    return await page.evaluate(() => {
      const sampleNode = globalThis.document.querySelector('[data-path="sample.ts"]');
      const sampleTab = globalThis.document.querySelector(
        '.ja-files-editor-tab button[title="sample.ts"]',
      );
      const sampleEditor = globalThis.document.querySelector('[aria-label="编辑文件 sample.ts"]');
      const moveTarget = globalThis.document.querySelector('[data-path="e2e-move-target"]');
      const movedChild = globalThis.document.querySelector(
        '[data-path="e2e-move-target/e2e-renamed.txt"]',
      );
      const moveTargetTreeItem = moveTarget?.closest('[role="treeitem"]');
      const moveTargetDisclosure = moveTarget?.querySelector(".ja-file-tree-disclosure");
      return {
        workspaceVisible: globalThis.document.querySelector('[aria-label="文件工作区"]') !== null,
        sampleNodePresent: sampleNode !== null,
        sampleNodeSelected: sampleNode?.classList.contains("is-selected") === true,
        sampleTabPresent: sampleTab !== null,
        sampleTabSelected: sampleTab?.getAttribute("aria-selected") === "true",
        sampleEditorPresent: sampleEditor !== null,
        openTabCount: globalThis.document.querySelectorAll(".ja-files-editor-tab").length,
        conflictVisible: globalThis.document.querySelector(".ja-files-conflict") !== null,
        moveTargetPresent: moveTarget !== null,
        moveTargetRendered: moveTarget?.getClientRects().length > 0,
        moveTargetExpanded: moveTargetTreeItem?.getAttribute("aria-expanded") ?? null,
        moveTargetClosedControl:
          moveTargetDisclosure?.getAttribute("aria-label") === "展开e2e-move-target",
        moveTargetOpenControl:
          moveTargetDisclosure?.getAttribute("aria-label") === "折叠e2e-move-target",
        moveTargetLoading: moveTarget?.querySelector('[aria-label="加载中"]') !== null,
        movedChildPresent: movedChild !== null,
        movedChildRendered: movedChild?.getClientRects().length > 0,
      };
    });
  } catch {
    return { unavailable: true };
  }
}

/** 只捕获有界 Terminal 生命周期计数，省略 PTY 文本与命令，避免终端内容进入证据。 */
async function captureTerminalWorkspaceState(page) {
  try {
    const projection = await page.evaluate(() => {
      const panes = [
        ...globalThis.document.querySelectorAll(
          ".ja-terminal-workspace .ja-terminal-pane[data-pane-id]",
        ),
      ];
      const newTab = globalThis.document.querySelector('[aria-label="新建终端标签页"]');
      return {
        tabCount: globalThis.document.querySelectorAll('.ja-terminal-tabs [role="tab"]').length,
        paneCount: panes.length,
        runningPaneCount: panes.filter(
          (pane) => pane.querySelector(".ja-terminal-pane-state")?.textContent?.trim() === "运行中",
        ).length,
        nativeIdentityCount: panes.filter(
          (pane) =>
            pane.hasAttribute("data-terminal-session-id") &&
            pane.hasAttribute("data-terminal-session-generation"),
        ).length,
        newTabPresent: newTab !== null,
        newTabDisabled:
          newTab instanceof globalThis.HTMLButtonElement ? newTab.disabled : undefined,
      };
    });
    return projection;
  } catch {
    return { unavailable: true };
  }
}

/**
 * 发送点击后立即读取可见 composer/timeline 状态。所有读取并发启动，
 * 避免单个 WebView2 accessibility 查询耗尽诊断预算并遮蔽其余原生事件证据。
 */
async function captureUiEvidence(page, directories, parentSignal) {
  const captureDeadline = createDeadline("UI 诊断", 3_000);
  const signal = combineSignals([parentSignal, captureDeadline.signal]);
  const read = async (operation, fallback) => {
    try {
      return await raceWithSignal(operation, signal);
    } catch {
      return fallback;
    }
  };
  try {
    const [
      textareaValue,
      sendButtonCount,
      cancelButtonCount,
      alerts,
      timeline,
      tauriEvents,
      turnStartInvokeLifecycle,
      workspaceEvents,
      workspaceInvokeLifecycle,
      nativeShortcutEvents,
      nativeShortcutInvokeLifecycle,
      filesState,
      terminalState,
      reducerState,
      runtimeStartup,
    ] = await Promise.all([
      read(() => page.locator('textarea[aria-label="消息"]').inputValue(), "<unavailable>"),
      read(() => page.locator('button[aria-label="发送"]').count(), -1),
      read(() => page.locator('button[aria-label="取消"]').count(), -1),
      read(() => page.locator('[role="alert"]').allTextContents(), []),
      read(() => page.locator('[aria-label="对话时间线"]').innerText(), "<unavailable>"),
      read(() => captureRawTauriEvents(page), []),
      read(() => tauriInvokeTrace(page, "ja_turn_start"), []),
      read(() => captureWorkspaceWatcherEvents(page), { status: "unavailable", events: [] }),
      read(() => captureWorkspaceInvokeLifecycle(page), { unavailable: true }),
      read(() => captureNativeShortcutEvents(page), { status: "unavailable", events: [] }),
      read(() => captureNativeShortcutInvokeLifecycle(page), { unavailable: true }),
      read(() => captureFilesWorkspaceState(page), { unavailable: true }),
      read(() => captureTerminalWorkspaceState(page), { unavailable: true }),
      read(() => captureTimelineReducerState(page), { unavailable: true }),
      read(() => captureRuntimeStartupState(page), { unavailable: true }),
    ]);
    return {
      textareaValue: redact(textareaValue, directories),
      sendButtonCount,
      cancelButtonCount,
      alerts: alerts.map((value) => redact(value, directories)),
      timeline: redact(timeline, directories),
      tauriEvents,
      turnStartInvokeLifecycle,
      workspaceEvents,
      workspaceInvokeLifecycle,
      nativeShortcutEvents,
      nativeShortcutInvokeLifecycle,
      filesState,
      terminalState,
      reducerState,
      runtimeStartup,
    };
  } finally {
    captureDeadline.cancel();
  }
}

/**
 * 等待包含 marker 的真实回答，同时观察终态事件。若 turn 已失败或完成但缺少 marker，
 * 则立即携带有界、已脱敏证据退出，而不是在单个 locator 上耗尽完整 model deadline。
 */
async function waitForRealProviderFinal(page, marker, deadline, directories, signal) {
  let terminalObservedAt;
  let lastEvents = [];
  let lastDom = { finalTexts: [], cancelCount: -1 };
  while (Date.now() < deadline) {
    throwIfAborted(signal);
    lastDom = await page
      .evaluate(() => ({
        finalTexts: [...globalThis.document.querySelectorAll(".ja-chat-message-final")].map(
          (element) => element.textContent ?? "",
        ),
        cancelCount: globalThis.document.querySelectorAll('button[aria-label="取消"]').length,
      }))
      .catch(() => lastDom);
    const markerVisible = lastDom.finalTexts.some((text) => text.includes(marker));
    lastEvents = await captureRawTauriEvents(page);
    const terminal = lastEvents.findLast((event) => event.method === "turn/terminal");
    if (terminal !== undefined) {
      terminalObservedAt ??= Date.now();
      if (terminal.terminalState !== "completed") {
        throw new Error(
          `真实 Provider 回合异常结束：${JSON.stringify({ terminalState: terminal.terminalState })}`,
        );
      }
      // Java 持久关闭 turn 前，streaming 文本可能已经包含 marker。
      // 必须同时满足终态事件与 reducer 的 idle composer，确保只在完成态断言 duration/file summary。
      if (markerVisible && lastDom.cancelCount === 0) return;
      if (Date.now() - terminalObservedAt >= 5_000) {
        throw new Error(
          `真实 Provider 已完成但界面未收敛：${JSON.stringify({ markerVisible, cancelCount: lastDom.cancelCount, finalCount: lastDom.finalTexts.length, finalPreview: redact(lastDom.finalTexts.join(" ").slice(0, 240), directories) })}`,
        );
      }
    }
    await waitForDelay(250, signal);
  }
  throw new Error(
    `真实 Provider 回合未在期限内完成：${JSON.stringify({ cancelCount: lastDom.cancelCount, finalCount: lastDom.finalTexts.length, events: lastEvents.slice(-12) })}`,
  );
}

/**
 * 等待唯一 Turn 行同时稳定包含一个用户块和一个最终答复块。React Virtualizer 会在最终
 * Item 到达时重新测量行；在单个渲染帧立即 count 会把合法重排误判为重复投影，因此这里
 * 等待 DOM 达到领域要求的精确基数，但仍受同一个 Turn deadline 约束。
 */
async function waitForTurnRowConvergence(page, visibleInput, expectedFinal, deadline, signal) {
  throwIfAborted(signal);
  await page.waitForFunction(
    ({ inputText, finalText }) => {
      const rows = [...globalThis.document.querySelectorAll(".ja-chat-timeline__row")];
      const matching = rows.filter((row) => {
        const users = row.querySelectorAll(".ja-chat-message-user");
        const finals = row.querySelectorAll(".ja-chat-message-final");
        return (
          users.length === 1 &&
          finals.length === 1 &&
          (users[0]?.textContent ?? "").includes(inputText) &&
          (finals[0]?.textContent ?? "").includes(finalText)
        );
      });
      return matching.length === 1;
    },
    { inputText: visibleInput, finalText: expectedFinal },
    { timeout: Math.max(1, deadline - Date.now()) },
  );
}

/**
 * 等待一个可见 thread 的用户项与最终响应；使用 DOM 状态而非固定延迟，
 * 使并行证据始终绑定真实投影。可选 visible input 用于区分准确 wire fixture 与 Markdown 渲染文本，
 * 因为成对下划线会被 Markdown 视为强调标记。
 */
async function waitForTurnFinal(
  page,
  input,
  expectedFinal,
  deadline,
  signal,
  directories,
  visibleInput = input,
) {
  throwIfAborted(signal);
  const timeout = () => Math.max(1, deadline - Date.now());
  await page
    .locator('.ja-chat-message-user[data-item-id^="item_"]')
    .filter({ hasText: visibleInput })
    .waitFor({ state: "visible", timeout: timeout() });
  await page
    .getByText(expectedFinal, { exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await page
    .locator(".ja-chat-message-final")
    .filter({ hasText: expectedFinal })
    .waitFor({ state: "visible", timeout: timeout() });
  await waitForTurnRowConvergence(page, visibleInput, expectedFinal, deadline, signal);
  return redact(await page.getByRole("region", { name: "对话时间线" }).innerText(), directories);
}

/**
 * 从选中的历史行读取准确的 server-owned thread identity；
 * 刻意不使用显示标题，因为多个 turn 可能共享同一标题。
 */
async function currentThreadId(page, deadline, signal) {
  throwIfAborted(signal);
  const selected = page
    .getByRole("list", { name: "最近对话列表" })
    .locator('button[aria-current="page"]');
  await selected.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const threadId = await selected.getAttribute("data-thread-id");
  if (threadId === null || !/^(?:thread|thr)_[A-Za-z0-9._-]+$/u.test(threadId)) {
    throw new Error("当前历史行缺少合法 thread id");
  }
  return threadId;
}

/** 即使项目与并行流程新增更多行，仍按 identity 选择唯一持久 thread，避免标题碰撞。 */
async function selectThreadById(page, threadId, deadline, signal) {
  throwIfAborted(signal);
  if (!/^(?:thread|thr)_[A-Za-z0-9._-]+$/u.test(threadId)) throw new Error("目标 thread id 非法");
  const target = page.locator(`[aria-label="最近对话列表"] button[data-thread-id="${threadId}"]`);
  await target.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await target.click();
  await page.waitForFunction(
    (expected) => {
      const selected = globalThis.document.querySelector(
        '[aria-label="最近对话列表"] button[aria-current="page"]',
      );
      return selected?.getAttribute("data-thread-id") === expected;
    },
    threadId,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
}

/** hard reload 后通过公开标题栏控件恢复持久化收起的导航，不直接改偏好存储。 */
async function ensureNavigationSidebarVisible(page, deadline) {
  const sidebar = page.locator('aside[aria-label="项目与对话导航"]');
  if ((await sidebar.count()) === 0 || !(await sidebar.isVisible())) {
    await clickVerifiedControl(
      page,
      page.getByRole("button", { name: "显示侧边栏", exact: true }),
      deadline,
    );
  }
  await sidebar.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
}

/**
 * hard reload 的无项目视图只挂载通用历史；先通过唯一 E2E 项目行恢复 workspace，
 * 再按服务端 thread identity 选择目标，且调用方必须提供独立局部 deadline。
 */
async function selectProjectThreadById(page, threadId, deadline, signal) {
  throwIfAborted(signal);
  await ensureNavigationSidebarVisible(page, deadline);
  const projects = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]');
  await projects.first().waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  if ((await projects.count()) !== 1) throw new Error("E2E profile 中真实项目数量不是一");
  await clickVerifiedControl(page, projects.first(), deadline);
  await page.waitForFunction(
    () =>
      globalThis.document.querySelector(
        '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
      ) !== null,
    undefined,
    {
      timeout: Math.max(1, deadline - Date.now()),
    },
  );
  await selectThreadById(page, threadId, deadline, signal);
}

/**
 * 验证可观察的 approval 关联与事件顺序：A 发起请求，A pending 期间 B 完成，
 * A 只能在 UI 决策后完成。私有 stdio response id 刻意不投影到 WebView，
 * 因此通过可访问 card 状态证明决策已经解析。
 */
function assertApprovalParallelEvidence(events) {
  const requestIndex = events.findIndex(
    (event) =>
      event.method === "approval/requested" &&
      event.approvalId?.startsWith("appr_") &&
      event.callId?.startsWith("call_") &&
      event.toolName === "shell",
  );
  if (requestIndex < 0) {
    throw new Error("未观察到 fake approval/requested 或其 shell 命令身份");
  }
  const request = events[requestIndex];
  if (
    !request.threadId?.startsWith("thr_") ||
    !request.turnId?.startsWith("turn_") ||
    !request.callId?.startsWith("call_")
  ) {
    throw new Error("approval/requested 缺少稳定业务身份");
  }
  const bCompletedIndex = events.findIndex(
    (event, index) =>
      index > requestIndex &&
      event.method === "turn/terminal" &&
      event.terminalState === "completed" &&
      event.threadId?.startsWith("thr_") &&
      event.threadId !== request.threadId,
  );
  if (bCompletedIndex < 0) {
    throw new Error("未观察到 A approval pending 期间另一 Thread B 的 completed");
  }
  const aCompletedIndex = events.findIndex(
    (event, index) =>
      index > bCompletedIndex &&
      event.method === "turn/terminal" &&
      event.terminalState === "completed" &&
      event.threadId === request.threadId,
  );
  if (aCompletedIndex < 0) {
    throw new Error("UI approve 后未观察到 A completed");
  }
  if (!(requestIndex < bCompletedIndex && bCompletedIndex < aCompletedIndex)) {
    throw new Error("approval/Thread 事件顺序不满足 A pending -> B completed -> A completed");
  }
  return {
    approvalId: request.approvalId,
    threadA: request.threadId,
    threadB: events[bCompletedIndex].threadId,
    turnA: request.turnId,
    callA: request.callId,
    requestIndex,
    bCompletedIndex,
    aCompletedIndex,
  };
}

/**
 * 在本轮后代进程中查找已编译 Tauri 进程。名称检查只用于选择窗口目标，
 * ownership 仍来自已记录的根后代 closure。
 */
function tauriProcessIds(tree, snapshot, preexistingJaIdentities = []) {
  return [...tree.values()]
    .filter(
      (entry) =>
        (entry.name.toLowerCase() === "ja.exe" ||
          /\\target\\(?:debug|release)\\ja\.exe/i.test(entry.commandLine)) &&
        snapshot.some((candidate) => sameProcessIdentity(entry, candidate)) &&
        !isProtectedPreexistingJa(entry, preexistingJaIdentities),
    )
    .map((entry) => entry.pid);
}

/**
 * 通过 Windows UI Automation 逐个检查同名托盘项，只对 owner PID 属于本轮 Tauri
 * identity 的“退出 Ja”执行 Invoke。窗口 CloseRequested 在产品中表示隐藏，不能冒充完整退出；
 * owner 复验同时保护用户正在运行的其它 Ja。先走用户真实的右键路径；Windows 11 XAML
 * 代理在浮层切换后可能立即失效，键盘兜底必须重新打开浮层并重新获取元素，菜单 owner 仍须复验。
 */
async function requestTrayExit(processIds, signal) {
  throwIfAborted(signal);
  const targetPids = [...new Set(processIds)].filter(
    (pid) => Number.isSafeInteger(pid) && pid > 0 && pid <= 0xffff_ffff,
  );
  if (targetPids.length === 0) {
    throw new Error("未找到可退出的 Tauri 进程");
  }
  const pidList = targetPids.join(",");
  const script = `
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class JaTrayNativeInput {
  [StructLayout(LayoutKind.Sequential)]
  private struct NativePoint {
    public int X;
    public int Y;
  }

  [StructLayout(LayoutKind.Sequential)]
  private struct NativeRect {
    public int Left;
    public int Top;
    public int Right;
    public int Bottom;
  }

  [StructLayout(LayoutKind.Sequential)]
  private struct MOUSEINPUT {
    public int dx;
    public int dy;
    public uint mouseData;
    public uint dwFlags;
    public uint time;
    public UIntPtr dwExtraInfo;
  }

  [StructLayout(LayoutKind.Sequential)]
  private struct KEYBDINPUT {
    public ushort virtualKey;
    public ushort scanCode;
    public uint flags;
    public uint time;
    public UIntPtr extraInfo;
  }

  [StructLayout(LayoutKind.Explicit)]
  private struct INPUTUNION {
    [FieldOffset(0)]
    public MOUSEINPUT mouseInput;

    [FieldOffset(0)]
    public KEYBDINPUT keyboardInput;
  }

  [StructLayout(LayoutKind.Sequential)]
  private struct INPUT {
    public uint type;
    public INPUTUNION inputUnion;
  }

  [DllImport("user32.dll")]
  public static extern bool SetForegroundWindow(IntPtr window);

  [DllImport("user32.dll")]
  private static extern IntPtr GetForegroundWindow();

  [DllImport("user32.dll")]
  private static extern bool GetPhysicalCursorPos(out NativePoint point);

  [DllImport("user32.dll")]
  private static extern bool SetPhysicalCursorPos(int x, int y);

  [DllImport("user32.dll", SetLastError = true)]
  private static extern uint SendInput(uint inputCount, INPUT[] inputs, int inputSize);

  [DllImport("user32.dll")]
  private static extern IntPtr WindowFromPoint(NativePoint point);

  [DllImport("user32.dll")]
  private static extern IntPtr GetAncestor(IntPtr window, uint flags);

  [DllImport("user32.dll")]
  private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  private static extern int GetClassName(IntPtr window, StringBuilder className, int maximumCount);

  private delegate bool EnumWindowsCallback(IntPtr window, IntPtr parameter);

  [DllImport("user32.dll")]
  private static extern bool EnumWindows(EnumWindowsCallback callback, IntPtr parameter);

  [DllImport("user32.dll")]
  private static extern bool IsWindowVisible(IntPtr window);

  [DllImport("user32.dll", SetLastError = true)]
  private static extern IntPtr SendMessageTimeout(
    IntPtr window,
    uint message,
    IntPtr wParam,
    IntPtr lParam,
    uint flags,
    uint timeoutMilliseconds,
    out IntPtr result
  );

  [DllImport("user32.dll")]
  private static extern int GetMenuItemCount(IntPtr menu);

  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  private static extern int GetMenuString(
    IntPtr menu,
    uint item,
    StringBuilder text,
    int maximumCount,
    uint flags
  );

  [DllImport("user32.dll")]
  private static extern uint GetMenuState(IntPtr menu, uint item, uint flags);

  [DllImport("user32.dll")]
  private static extern bool GetMenuItemRect(
    IntPtr window,
    IntPtr menu,
    uint item,
    out NativeRect rectangle
  );

  public static int[] ReadCursorPosition() {
    if (!GetPhysicalCursorPos(out NativePoint point)) {
      throw new InvalidOperationException("cursor position unavailable");
    }
    return new[] { point.X, point.Y };
  }

  public static void MoveCursor(int x, int y) {
    if (!SetPhysicalCursorPos(x, y)) {
      throw new InvalidOperationException("cursor move failed");
    }
  }

  /// <summary>
  /// 回读物理点击点实际命中的 HWND、根窗口、PID 与 class；这里只生成诊断证据，
  /// 不把 ShellExperienceHost 的窗口身份替代后续 UIA 菜单 owner 授权。
  /// </summary>
  public static string DescribePoint(int x, int y) {
    IntPtr hit = WindowFromPoint(new NativePoint { X = x, Y = y });
    IntPtr root = hit == IntPtr.Zero ? IntPtr.Zero : GetAncestor(hit, 2);
    uint processId = 0;
    if (hit != IntPtr.Zero) {
      GetWindowThreadProcessId(hit, out processId);
    }
    return $"hit=0x{hit.ToInt64():X};root=0x{root.ToInt64():X};pid={processId};class={ReadWindowClass(hit)}";
  }

  /// <summary>
  /// 回读键盘输入实际投递时的前台 HWND/PID/class，证明 UIA SetFocus 没有静默落到其它窗口。
  /// </summary>
  public static string DescribeForeground() {
    IntPtr foreground = GetForegroundWindow();
    IntPtr root = foreground == IntPtr.Zero ? IntPtr.Zero : GetAncestor(foreground, 2);
    uint processId = 0;
    if (foreground != IntPtr.Zero) {
      GetWindowThreadProcessId(foreground, out processId);
    }
    return $"foreground=0x{foreground.ToInt64():X};root=0x{root.ToInt64():X};pid={processId};class={ReadWindowClass(foreground)}";
  }

  /// <summary>
  /// 读取命中窗口 class 仅用于失败诊断；无窗口或读取失败时返回稳定占位值。
  /// </summary>
  private static string ReadWindowClass(IntPtr window) {
    if (window == IntPtr.Zero) {
      return "none";
    }
    StringBuilder className = new StringBuilder(256);
    return GetClassName(window, className, className.Capacity) > 0 ? className.ToString() : "unknown";
  }

  /// <summary>
  /// 一次提交完整输入批次；部分发送会遗留按键或按钮状态，因此必须故障关闭。
  /// </summary>
  private static void SubmitInputs(INPUT[] inputs, string operation) {
    uint sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
    if (sent != inputs.Length) {
      throw new InvalidOperationException(
        $"{operation} injection incomplete: sent={sent}, expected={inputs.Length}, win32={Marshal.GetLastWin32Error()}"
      );
    }
  }

  /// <summary>
  /// 以一个 SendInput 批次提交右键按下和抬起，避免旧式输入 API 在 Windows 11
  /// XAML 通知区丢失半个点击；返回数量不足时拒绝继续，防止误判菜单已经打开。
  /// </summary>
  public static void RightClick() {
    INPUT[] inputs = new[] {
      new INPUT {
        type = 0,
        inputUnion = new INPUTUNION {
          mouseInput = new MOUSEINPUT { dwFlags = 0x0008 }
        }
      },
      new INPUT {
        type = 0,
        inputUnion = new INPUTUNION {
          mouseInput = new MOUSEINPUT { dwFlags = 0x0010 }
        }
      }
    };
    SubmitInputs(inputs, "right click");
  }

  /// <summary>
  /// 以一个 SendInput 批次提交左键按下和抬起；只由已验证 owner 与菜单文案的物理命中路径调用。
  /// </summary>
  public static void LeftClick() {
    INPUT[] inputs = new[] {
      new INPUT {
        type = 0,
        inputUnion = new INPUTUNION {
          mouseInput = new MOUSEINPUT { dwFlags = 0x0002 }
        }
      },
      new INPUT {
        type = 0,
        inputUnion = new INPUTUNION {
          mouseInput = new MOUSEINPUT { dwFlags = 0x0004 }
        }
      }
    };
    SubmitInputs(inputs, "left click");
  }

  /// <summary>
  /// Windows 原生 popup menu 不保证暴露 UIA MenuItem；这里只从可见 #32768 中接受精确
  /// 目标 PID、精确文案且 enabled 的条目，并返回 owner 与物理矩形供真实左键命中。
  /// </summary>
  public static int[] FindOwnedMenuItem(uint[] targetPids, string expectedText) {
    int[] found = Array.Empty<int>();
    EnumWindows((window, parameter) => {
      if (!IsWindowVisible(window) || ReadWindowClass(window) != "#32768") {
        return true;
      }
      GetWindowThreadProcessId(window, out uint processId);
      if (Array.IndexOf(targetPids, processId) < 0) {
        return true;
      }
      IntPtr menu;
      if (SendMessageTimeout(
            window,
            0x01E1,
            IntPtr.Zero,
            IntPtr.Zero,
            0x0002,
            250,
            out menu
          ) == IntPtr.Zero) {
        return true;
      }
      int count = menu == IntPtr.Zero ? 0 : GetMenuItemCount(menu);
      for (uint index = 0; index < count; index++) {
        StringBuilder text = new StringBuilder(256);
        if (GetMenuString(menu, index, text, text.Capacity, 0x0400) <= 0 ||
            !string.Equals(text.ToString().Replace("&", ""), expectedText, StringComparison.Ordinal)) {
          continue;
        }
        uint state = GetMenuState(menu, index, 0x0400);
        if (state == 0xFFFFFFFF || (state & 0x0003) != 0 ||
            !GetMenuItemRect(IntPtr.Zero, menu, index, out NativeRect rectangle) ||
            rectangle.Right <= rectangle.Left || rectangle.Bottom <= rectangle.Top) {
          continue;
        }
        found = new[] {
          checked((int)processId),
          rectangle.Left,
          rectangle.Top,
          rectangle.Right,
          rectangle.Bottom
        };
        return false;
      }
      return true;
    }, IntPtr.Zero);
    return found;
  }

  /// <summary>
  /// 使用真实 VK_APPS 按下/抬起打开当前焦点项的上下文菜单，避免 SendKeys
  /// 依赖消息泵和调用线程焦点而在 Windows 11 XAML 通知区静默丢失。
  /// </summary>
  public static void ContextMenuKey() {
    PressKey(0x5D, "context menu");
  }

  /// <summary>
  /// 某些 Windows 11 XAML 通知区版本忽略 VK_APPS；以同一真实 SendInput 批次发送
  /// Shift+F10 作为第二键盘路径，并保证两个按键都成对释放。
  /// </summary>
  public static void ShiftF10() {
    INPUT[] inputs = new[] {
      new INPUT {
        type = 1,
        inputUnion = new INPUTUNION {
          keyboardInput = new KEYBDINPUT { virtualKey = 0x10 }
        }
      },
      new INPUT {
        type = 1,
        inputUnion = new INPUTUNION {
          keyboardInput = new KEYBDINPUT { virtualKey = 0x79 }
        }
      },
      new INPUT {
        type = 1,
        inputUnion = new INPUTUNION {
          keyboardInput = new KEYBDINPUT { virtualKey = 0x79, flags = 0x0002 }
        }
      },
      new INPUT {
        type = 1,
        inputUnion = new INPUTUNION {
          keyboardInput = new KEYBDINPUT { virtualKey = 0x10, flags = 0x0002 }
        }
      }
    };
    SubmitInputs(inputs, "shift f10");
  }

  /// <summary>
  /// 用同一 Win32 输入通道关闭非目标菜单，确保兜底清理不再混用 SendKeys。
  /// </summary>
  public static void EscapeKey() {
    PressKey(0x1B, "escape");
  }

  /// <summary>
  /// 将单个虚拟键封装为成对 key-down/key-up，防止异常路径留下系统级按键状态。
  /// </summary>
  private static void PressKey(ushort virtualKey, string operation) {
    INPUT[] inputs = new[] {
      new INPUT {
        type = 1,
        inputUnion = new INPUTUNION {
          keyboardInput = new KEYBDINPUT { virtualKey = virtualKey }
        }
      },
      new INPUT {
        type = 1,
        inputUnion = new INPUTUNION {
          keyboardInput = new KEYBDINPUT { virtualKey = virtualKey, flags = 0x0002 }
        }
      }
    };
    SubmitInputs(inputs, operation);
  }
}
'@

$targetPids = @(${pidList}) | ForEach-Object { [int]$_ }
# 原生 popup menu 不暴露 UIA item 时，仍只按精确 owner/文案/可见矩形授权，并在点击前复验命中窗口。
function Invoke-JaOwnedNativeQuit {
  $item = [JaTrayNativeInput]::FindOwnedMenuItem([uint32[]]$targetPids, '退出 Ja')
  if ($null -eq $item -or $item.Length -ne 5) { return $null }
  $owner = [int]$item[0]
  $menuX = [int][Math]::Round(($item[1] + $item[3]) / 2)
  $menuY = [int][Math]::Round(($item[2] + $item[4]) / 2)
  [JaTrayNativeInput]::MoveCursor($menuX, $menuY)
  Start-Sleep -Milliseconds 75
  $hit = [JaTrayNativeInput]::DescribePoint($menuX, $menuY)
  if ($hit -notmatch "pid=$owner;class=#32768$") { return $null }
  [JaTrayNativeInput]::LeftClick()
  Start-Sleep -Milliseconds 250
  return [pscustomobject]@{ pid = $owner; point = "$menuX,$menuY"; hit = $hit }
}
$root = [System.Windows.Automation.AutomationElement]::RootElement
$buttonCondition = New-Object System.Windows.Automation.PropertyCondition(
  [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
  [System.Windows.Automation.ControlType]::Button
)
$menuCondition = New-Object System.Windows.Automation.PropertyCondition(
  [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
  [System.Windows.Automation.ControlType]::MenuItem
)
$menuRootCondition = New-Object System.Windows.Automation.PropertyCondition(
  [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
  [System.Windows.Automation.ControlType]::Menu
)
$menuSurfaceCondition = New-Object System.Windows.Automation.OrCondition(
  $menuCondition,
  $menuRootCondition
)
$taskbarCondition = New-Object System.Windows.Automation.PropertyCondition(
  [System.Windows.Automation.AutomationElement]::ClassNameProperty,
  'Shell_TrayWnd'
)
$flyoutCondition = New-Object System.Windows.Automation.PropertyCondition(
  [System.Windows.Automation.AutomationElement]::ClassNameProperty,
  'TopLevelWindowForOverflowXamlIsland'
)
# Windows 11 会保留已经关闭的离屏 XAML island；只有可见且有有效矩形的元素才能代表当前浮层。
function Test-JaFlyoutVisible($candidate) {
  if ($null -eq $candidate) { return $false }
  try {
    $bounds = $candidate.Current.BoundingRectangle
    return -not $candidate.Current.IsOffscreen -and $bounds.Width -gt 0 -and $bounds.Height -gt 0
  } catch {
    return $false
  }
}
# 枚举而不是 FindFirst，避免 Windows 11 留下的离屏旧 island 挡住当前可见浮层。
function Find-JaVisibleFlyout {
  foreach (
    $candidate in $root.FindAll(
      [System.Windows.Automation.TreeScope]::Descendants,
      $flyoutCondition
    )
  ) {
    if (Test-JaFlyoutVisible $candidate) { return $candidate }
  }
  return $null
}
# 只在当前没有可见浮层时调用系统 overflow button，并返回本次打开后重新取得的 UIA 代理。
function Open-JaFlyout {
  $current = Find-JaVisibleFlyout
  if ($null -ne $current) { return $current }
  $overflowInvoke.Invoke()
  $deadline = [DateTime]::UtcNow.AddSeconds(2)
  while ([DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Milliseconds 50
    $current = Find-JaVisibleFlyout
    if ($null -ne $current) { return $current }
  }
  throw 'Windows notification overflow did not open'
}
# 右键会销毁或重建 XAML island；先收起残余菜单和浮层，再重新打开以建立新的元素身份。
function Reopen-JaFlyout {
  try { [JaTrayNativeInput]::EscapeKey() } catch { }
  $closeDeadline = [DateTime]::UtcNow.AddMilliseconds(500)
  while ([DateTime]::UtcNow -lt $closeDeadline -and $null -ne (Find-JaVisibleFlyout)) {
    Start-Sleep -Milliseconds 50
  }
  $current = Find-JaVisibleFlyout
  if ($null -ne $current) {
    try { $overflowInvoke.Invoke() } catch { }
    $toggleDeadline = [DateTime]::UtcNow.AddMilliseconds(500)
    while ([DateTime]::UtcNow -lt $toggleDeadline -and $null -ne (Find-JaVisibleFlyout)) {
      Start-Sleep -Milliseconds 50
    }
  }
  $reopened = Open-JaFlyout
  return $reopened
}
# 每次从当前 UIA 树生成候选并去重；旧 AutomationElement 不跨浮层切换复用。
function Get-JaTrayCandidates($currentFlyout, $currentTaskbar) {
  $candidates = @()
  $seen = New-Object 'System.Collections.Generic.HashSet[string]'
  foreach ($trayContainer in @($currentFlyout, $currentTaskbar)) {
    foreach (
      $trayItem in $trayContainer.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        $buttonCondition
      )
    ) {
      try {
        if (
          $trayItem.Current.Name -eq 'Ja' -and
          $trayItem.Current.AutomationId -eq 'NotifyItemIcon' -and
          -not $trayItem.Current.IsOffscreen
        ) {
          $trayBounds = $trayItem.Current.BoundingRectangle
          if ($trayBounds.Width -le 0 -or $trayBounds.Height -le 0) { continue }
          $trayKey = [string]::Join('.', $trayItem.GetRuntimeId())
          if (-not $seen.Add($trayKey)) { continue }
          $candidates += [pscustomobject]@{
            element = $trayItem
            key = $trayKey
            targetX = [int][Math]::Round($trayBounds.X + ($trayBounds.Width / 2))
            targetY = [int][Math]::Round($trayBounds.Y + ($trayBounds.Height / 2))
          }
        }
      } catch { }
    }
  }
  return $candidates
}
# 枚举点击后的所有可见菜单面，保留 owner、class 与矩形；这些值只进入有界失败诊断，
# 真正 Invoke 授权仍只接受精确 target PID 的“退出 Ja”MenuItem。
function Get-VisibleMenuDiagnostics {
  $diagnostics = New-Object 'System.Collections.Generic.List[string]'
  foreach ($element in $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $menuSurfaceCondition)) {
    try {
      if ($element.Current.IsOffscreen) { continue }
      $bounds = $element.Current.BoundingRectangle
      if ($bounds.Width -le 0 -or $bounds.Height -le 0) { continue }
      $diagnostics.Add(
        "name=$($element.Current.Name),owner=$($element.Current.ProcessId),class=$($element.Current.ClassName),type=$($element.Current.ControlType.ProgrammaticName),bounds=$([int]$bounds.X),$([int]$bounds.Y),$([int]$bounds.Width),$([int]$bounds.Height)"
      )
    } catch { }
  }
  return [string]::Join('|', $diagnostics)
}
$taskbar = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $taskbarCondition)
if ($null -eq $taskbar) { throw 'Windows primary taskbar was not found' }
$overflowButton = $null
foreach ($button in $taskbar.FindAll([System.Windows.Automation.TreeScope]::Descendants, $buttonCondition)) {
  if ($button.Current.Name -eq '显示隐藏的图标' -and $button.Current.AutomationId -eq 'SystemTrayIcon') {
    $overflowButton = $button
    break
  }
}
if ($null -eq $overflowButton) { throw 'Windows notification overflow button was not found' }
$overflowInvoke = [System.Windows.Automation.InvokePattern]$overflowButton.GetCurrentPattern(
  [System.Windows.Automation.InvokePattern]::Pattern
)
$flyout = Open-JaFlyout
$flyoutHandle = [IntPtr]::new([int]$flyout.Current.NativeWindowHandle)

$selectedPid = $null
$selectedMenu = $null
$observedMenuOwners = New-Object 'System.Collections.Generic.HashSet[int]'
$rejectedTrayItems = New-Object 'System.Collections.Generic.HashSet[string]'
$maximumTrayItems = 0
$pointerAttempts = 0
$pointerPoint = ''
$pointerHit = ''
$postPointerHit = ''
$nativeMenuHit = ''
$nativeMenuPoint = ''
$nativeMenuError = ''
$pointerError = ''
$visibleMenuDiagnostics = New-Object 'System.Collections.Generic.HashSet[string]'
try {
  $selectionDeadline = [DateTime]::UtcNow.AddSeconds(8)
  while (
    [DateTime]::UtcNow -lt $selectionDeadline -and
    $null -eq $selectedMenu -and
    $null -eq $selectedPid
  ) {
    try {
      $flyout = Open-JaFlyout
      $flyoutHandle = [IntPtr]::new([int]$flyout.Current.NativeWindowHandle)
      $trayItems = @(Get-JaTrayCandidates $flyout $taskbar)
    } catch {
      Start-Sleep -Milliseconds 100
      continue
    }
    $maximumTrayItems = [Math]::Max($maximumTrayItems, $trayItems.Count)
    foreach ($trayCandidate in $trayItems) {
      $trayItem = $trayCandidate.element
      $trayKey = [string]$trayCandidate.key
      $targetX = [int]$trayCandidate.targetX
      $targetY = [int]$trayCandidate.targetY
      if ($rejectedTrayItems.Contains($trayKey)) { continue }
      $attemptedTrayKey = $trayKey
      $menuOwner = $null
      # 先走用户真实的右键路径；SetFocus/SendKeys 会改变 Windows 11 XAML 通知区焦点，
      # 若放在前面可能让随后缓存的物理命中落到已经失效的 element proxy。
      try {
        $pointerAttempts += 1
        $pointerPoint = "$targetX,$targetY"
        $originalCursor = [JaTrayNativeInput]::ReadCursorPosition()
        try {
          try {
            $nativeQuit = Invoke-JaOwnedNativeQuit
          } catch {
            $nativeFailure = $_.Exception
            while ($null -ne $nativeFailure.InnerException) {
              $nativeFailure = $nativeFailure.InnerException
            }
        $nativeMenuError = "$($nativeFailure.GetType().Name):$($nativeFailure.Message)" -replace '\\s+', ' '
            $nativeQuit = $null
          }
          if ($null -ne $nativeQuit) {
            $selectedPid = [int]$nativeQuit.pid
            $nativeMenuPoint = [string]$nativeQuit.point
            $nativeMenuHit = [string]$nativeQuit.hit
          } else {
            if ($flyoutHandle -ne [IntPtr]::Zero) {
              [JaTrayNativeInput]::SetForegroundWindow($flyoutHandle) | Out-Null
              Start-Sleep -Milliseconds 50
            }
            [JaTrayNativeInput]::MoveCursor($targetX, $targetY)
            Start-Sleep -Milliseconds 100
            $pointerHit = [JaTrayNativeInput]::DescribePoint($targetX, $targetY)
            [JaTrayNativeInput]::RightClick()
            Start-Sleep -Milliseconds 250
            $postPointerHit = [JaTrayNativeInput]::DescribePoint($targetX, $targetY)
            try {
              $nativeQuit = Invoke-JaOwnedNativeQuit
            } catch {
              $nativeFailure = $_.Exception
              while ($null -ne $nativeFailure.InnerException) {
                $nativeFailure = $nativeFailure.InnerException
              }
              $nativeMenuError = "$($nativeFailure.GetType().Name):$($nativeFailure.Message)" -replace '\\s+', ' '
              $nativeQuit = $null
            }
            if ($null -ne $nativeQuit) {
              $selectedPid = [int]$nativeQuit.pid
              $nativeMenuPoint = [string]$nativeQuit.point
              $nativeMenuHit = [string]$nativeQuit.hit
            }
            $visibleMenus = Get-VisibleMenuDiagnostics
            if (-not [string]::IsNullOrWhiteSpace($visibleMenus)) {
              $visibleMenuDiagnostics.Add($visibleMenus) | Out-Null
            }
          }
        } finally {
          [JaTrayNativeInput]::MoveCursor($originalCursor[0], $originalCursor[1])
        }
        $pointerDeadline = [DateTime]::UtcNow.AddMilliseconds(750)
        if ($pointerDeadline -gt $selectionDeadline) { $pointerDeadline = $selectionDeadline }
        while (
          [DateTime]::UtcNow -lt $pointerDeadline -and
          $null -eq $selectedMenu -and
          $null -eq $selectedPid
        ) {
          foreach ($menuItem in $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $menuCondition)) {
            try {
              if (
                $menuItem.Current.Name -ne '退出 Ja' -or
                $menuItem.Current.IsOffscreen -or
                -not $menuItem.Current.IsEnabled
              ) { continue }
              $bounds = $menuItem.Current.BoundingRectangle
              if ($bounds.Width -le 0 -or $bounds.Height -le 0) { continue }
              $menuOwner = $menuItem.Current.ProcessId
              $observedMenuOwners.Add($menuOwner) | Out-Null
              if ($targetPids -contains $menuOwner) {
                $selectedMenu = $menuItem
                break
              }
            } catch { }
          }
          if ($null -ne $menuOwner -or $null -ne $selectedMenu) { break }
          Start-Sleep -Milliseconds 50
        }
      } catch {
        $pointerFailure = $_.Exception
        while ($null -ne $pointerFailure.InnerException) {
          $pointerFailure = $pointerFailure.InnerException
        }
        $pointerError = "$($pointerFailure.GetType().Name):$($pointerFailure.Message)" -replace '\\s+', ' '
      }
      if ($null -ne $selectedPid) { break }
      if ($null -eq $selectedMenu -and $null -eq $menuOwner) {
        # 键盘路径只作兜底；右键后的旧 XAML proxy 不可复用，必须重新打开 flyout，并按
        # runtime id 优先、可见 Ja 名称回退重新定位，且仍只能产出同一个 owned menu。
        $keyboardAttempted = $false
        try {
          $flyout = Reopen-JaFlyout
          $flyoutHandle = [IntPtr]::new([int]$flyout.Current.NativeWindowHandle)
          $freshTrayItems = @(Get-JaTrayCandidates $flyout $taskbar)
          $maximumTrayItems = [Math]::Max($maximumTrayItems, $freshTrayItems.Count)
          $keyboardCandidate = @(
            $freshTrayItems | Where-Object { $_.key -eq $trayKey }
          ) | Select-Object -First 1
          if ($null -eq $keyboardCandidate) {
            $keyboardCandidate = @(
              $freshTrayItems | Where-Object { -not $rejectedTrayItems.Contains([string]$_.key) }
            ) | Select-Object -First 1
          }
          if ($null -eq $keyboardCandidate) { throw 'Ja tray item was not reacquired' }
          $keyboardTrayItem = $keyboardCandidate.element
          $attemptedTrayKey = [string]$keyboardCandidate.key
          if ($flyoutHandle -ne [IntPtr]::Zero) {
            [JaTrayNativeInput]::SetForegroundWindow($flyoutHandle) | Out-Null
            Start-Sleep -Milliseconds 50
          }
          $keyboardTrayItem.SetFocus()
          [JaTrayNativeInput]::ContextMenuKey()
          Start-Sleep -Milliseconds 100
          $visibleMenus = Get-VisibleMenuDiagnostics
          if (-not [string]::IsNullOrWhiteSpace($visibleMenus)) {
            $visibleMenuDiagnostics.Add($visibleMenus) | Out-Null
          }
          $keyboardAttempted = $true
        } catch { }
        if ($keyboardAttempted) {
          $menuDeadline = [DateTime]::UtcNow.AddMilliseconds(750)
          if ($menuDeadline -gt $selectionDeadline) { $menuDeadline = $selectionDeadline }
          while ([DateTime]::UtcNow -lt $menuDeadline -and $null -eq $selectedMenu) {
            foreach ($menuItem in $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $menuCondition)) {
              try {
                if (
                  $menuItem.Current.Name -ne '退出 Ja' -or
                  $menuItem.Current.IsOffscreen -or
                  -not $menuItem.Current.IsEnabled
                ) { continue }
                $bounds = $menuItem.Current.BoundingRectangle
                if ($bounds.Width -le 0 -or $bounds.Height -le 0) { continue }
                $menuOwner = $menuItem.Current.ProcessId
                $observedMenuOwners.Add($menuOwner) | Out-Null
                if ($targetPids -contains $menuOwner) {
                  $selectedMenu = $menuItem
                  break
                }
              } catch { }
            }
            if ($null -ne $menuOwner -or $null -ne $selectedMenu) { break }
            Start-Sleep -Milliseconds 50
          }
        }
      }
      if ($null -ne $selectedMenu) {
        $candidatePid = $selectedMenu.Current.ProcessId
        $invoke = [System.Windows.Automation.InvokePattern]$selectedMenu.GetCurrentPattern(
          [System.Windows.Automation.InvokePattern]::Pattern
        )
        $selectedPid = $candidatePid
        try {
          $invoke.Invoke()
        } catch {
          # Invoke 可能在动作已送达、产品窗口随即销毁后报告 ElementNotAvailable；只在精确
          # owner PID 已真实退出时接纳该竞态，PID 仍活跃则恢复失败状态并保留原异常。
          $invokeFailure = $_
          $invokeExitDeadline = [DateTime]::UtcNow.AddSeconds(5)
          do {
            $candidateProcess = Get-Process -Id $candidatePid -ErrorAction SilentlyContinue
            if ($null -eq $candidateProcess) { break }
            Start-Sleep -Milliseconds 50
          } while ([DateTime]::UtcNow -lt $invokeExitDeadline)
          if ($null -ne $candidateProcess) {
            $selectedPid = $null
            throw $invokeFailure
          }
        }
        break
      }
      if ($null -ne $menuOwner) {
        $rejectedTrayItems.Add($attemptedTrayKey) | Out-Null
      }
      [JaTrayNativeInput]::EscapeKey()
      $menuCloseDeadline = [DateTime]::UtcNow.AddMilliseconds(300)
      if ($menuCloseDeadline -gt $selectionDeadline) { $menuCloseDeadline = $selectionDeadline }
      while ([DateTime]::UtcNow -lt $menuCloseDeadline) {
        $visibleQuitMenu = $false
        foreach ($menuItem in $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, $menuCondition)) {
          try {
            if ($menuItem.Current.Name -eq '退出 Ja' -and -not $menuItem.Current.IsOffscreen) {
              $visibleQuitMenu = $true
              break
            }
          } catch { }
        }
        if (-not $visibleQuitMenu) { break }
        Start-Sleep -Milliseconds 50
      }
    }
    if ($null -eq $selectedMenu) { Start-Sleep -Milliseconds 100 }
  }
  if ($null -eq $selectedPid) {
    $owners = [string]::Join(',', $observedMenuOwners)
    $visibleMenus = [string]::Join('||', $visibleMenuDiagnostics)
    throw "Owned Ja tray menu was not found; trayItems=$maximumTrayItems menuOwners=$owners pointerAttempts=$pointerAttempts pointerPoint=$pointerPoint pointerHit=$pointerHit postPointerHit=$postPointerHit nativeMenuPoint=$nativeMenuPoint nativeMenuHit=$nativeMenuHit nativeMenuError=$nativeMenuError pointerError=$pointerError flyoutHandle=$flyoutHandle visibleMenus=$visibleMenus"
  }
} finally {
  if ($null -eq $selectedPid) {
    try { [JaTrayNativeInput]::EscapeKey() } catch { }
  }
  # 精确 Quit 已执行后 XAML island 可以立即失效；关闭残余 flyout 是尽力清理，不能反向
  # 把已验证 owner 的成功动作改写为命令失败。
  try {
    $remainingFlyout = Find-JaVisibleFlyout
    if (Test-JaFlyoutVisible $remainingFlyout) {
      try { $overflowInvoke.Invoke() } catch { }
    }
  } catch { }
}
[pscustomobject]@{
  pid = $selectedPid
  action = 'quit'
  transport = 'windows_uia_tray_menu'
} | ConvertTo-Json -Compress
`;
  let execution;
  try {
    execution = await execFileAsync(
      nativeInputPowerShell,
      ["-NoProfile", "-NonInteractive", "-Command", script],
      {
        windowsHide: true,
        maxBuffer: 1 * 1024 * 1024,
        timeout: trayExitTimeoutMs,
        signal,
      },
    );
  } catch (error) {
    // `execFile` 默认 message 会回显并截断整段 PowerShell；只保留有界 stdout/stderr，
    // 让 HWND/menu 诊断可读，同时不把命令正文或环境变量写入运行摘要。
    const stderrTail = String(error?.stderr ?? "")
      .trim()
      .replace(/\s+/gu, " ")
      .slice(-4_096);
    const stdoutTail = String(error?.stdout ?? "")
      .trim()
      .replace(/\s+/gu, " ")
      .slice(-1_024);
    throw new Error(
      `托盘退出命令失败 code=${String(error?.code ?? "unknown")} signal=${String(error?.signal ?? "none")} killed=${String(error?.killed === true)} stderr=${stderrTail || "none"} stdout=${stdoutTail || "none"}`,
      { cause: error },
    );
  }
  const { stdout } = execution;
  const output = stdout
    .trim()
    .split(/\r?\n/u)
    .filter((line) => line.trim() !== "")
    .at(-1);
  let result;
  try {
    result = JSON.parse(output ?? "");
  } catch {
    throw new Error("托盘退出未返回可验证的 owner 证据");
  }
  if (
    !targetPids.includes(result?.pid) ||
    result?.action !== "quit" ||
    result?.transport !== "windows_uia_tray_menu"
  ) {
    const diagnostic = {
      expectedPids: targetPids,
      pid: result?.pid,
      pidType: typeof result?.pid,
      action: result?.action,
      transport: result?.transport,
    };
    throw new Error(`托盘退出 owner 证据无效 ${JSON.stringify(diagnostic)}`);
  }
  return result;
}

/**
 * 固定托盘退出的可见 overflow/主任务栏范围、精确 owner、物理右键与 Win32 键盘兜底语义；
 * 点击后必须重新打开浮层并重取 runtime identity，恢复指针后仍重新核对菜单 owner。
 */
function assertTrayExitContract() {
  const source = requestTrayExit.toString();
  const nativeMenuFunctionIndex = source.indexOf("function Invoke-JaOwnedNativeQuit");
  const nativeMenuLookupIndex = source.indexOf(
    "FindOwnedMenuItem([uint32[]]$targetPids, '退出 Ja')",
    nativeMenuFunctionIndex,
  );
  const nativeMenuHitGateIndex = source.indexOf(
    '$hit -notmatch "pid=$owner;class=#32768$"',
    nativeMenuLookupIndex,
  );
  const nativeMenuClickIndex = source.indexOf(
    "[JaTrayNativeInput]::LeftClick()",
    nativeMenuHitGateIndex,
  );
  const flyoutPolicyIndex = source.indexOf("function Test-JaFlyoutVisible");
  const visibleFlyoutIndex = source.indexOf("function Find-JaVisibleFlyout", flyoutPolicyIndex);
  const flyoutOpenIndex = source.indexOf("function Open-JaFlyout", visibleFlyoutIndex);
  const flyoutReopenIndex = source.indexOf("function Reopen-JaFlyout", flyoutOpenIndex);
  const trayCandidatesIndex = source.indexOf("function Get-JaTrayCandidates", flyoutReopenIndex);
  const initialFlyoutIndex = source.indexOf("$flyout = Open-JaFlyout", trayCandidatesIndex);
  const flyoutCloseIndex = source.indexOf(
    "if (Test-JaFlyoutVisible $remainingFlyout)",
    initialFlyoutIndex,
  );
  const trayBoundsIndex = source.indexOf("$trayBounds = $trayItem.Current.BoundingRectangle");
  const trayBoundsGuardIndex = source.indexOf(
    "$trayBounds.Width -le 0 -or $trayBounds.Height -le 0",
    trayBoundsIndex,
  );
  const cachedXIndex = source.indexOf(
    "targetX = [int][Math]::Round($trayBounds.X + ($trayBounds.Width / 2))",
    trayBoundsGuardIndex,
  );
  const cachedYIndex = source.indexOf(
    "targetY = [int][Math]::Round($trayBounds.Y + ($trayBounds.Height / 2))",
    cachedXIndex,
  );
  const trayContainersIndex = source.indexOf(
    "foreach ($trayContainer in @($currentFlyout, $currentTaskbar))",
    trayCandidatesIndex,
  );
  const trayScopeIndex = source.indexOf("$trayItem in $trayContainer.FindAll", trayContainersIndex);
  const trayOffscreenIndex = source.indexOf("$trayItem.Current.IsOffscreen", trayScopeIndex);
  const pointerIndex = source.indexOf("$pointerAttempts += 1", cachedYIndex);
  const pointerPointIndex = source.indexOf('$pointerPoint = "$targetX,$targetY"', pointerIndex);
  const saveCursorIndex = source.indexOf(
    "$originalCursor = [JaTrayNativeInput]::ReadCursorPosition()",
    pointerPointIndex,
  );
  const preexistingNativeMenuIndex = source.indexOf(
    "$nativeQuit = Invoke-JaOwnedNativeQuit",
    saveCursorIndex,
  );
  const foregroundIndex = source.indexOf(
    "[JaTrayNativeInput]::SetForegroundWindow",
    saveCursorIndex,
  );
  const moveCursorIndex = source.indexOf(
    "[JaTrayNativeInput]::MoveCursor($targetX, $targetY)",
    foregroundIndex,
  );
  const preClickWaitIndex = source.indexOf("Start-Sleep -Milliseconds 100", moveCursorIndex);
  const pointerHitIndex = source.indexOf(
    "$pointerHit = [JaTrayNativeInput]::DescribePoint($targetX, $targetY)",
    preClickWaitIndex,
  );
  const rightClickIndex = source.indexOf("[JaTrayNativeInput]::RightClick()", pointerHitIndex);
  const postClickWaitIndex = source.indexOf("Start-Sleep -Milliseconds 250", rightClickIndex);
  const postClickNativeMenuIndex = source.indexOf(
    "$nativeQuit = Invoke-JaOwnedNativeQuit",
    rightClickIndex,
  );
  const restoreCursorIndex = source.indexOf(
    "[JaTrayNativeInput]::MoveCursor($originalCursor[0], $originalCursor[1])",
    postClickWaitIndex,
  );
  const pointerOwnerIndex = source.indexOf("$targetPids -contains $menuOwner", restoreCursorIndex);
  const keyboardFlagIndex = source.indexOf("$keyboardAttempted = $false", pointerOwnerIndex);
  const keyboardReopenIndex = source.indexOf("$flyout = Reopen-JaFlyout", keyboardFlagIndex);
  const freshTrayItemsIndex = source.indexOf(
    "$freshTrayItems = @(Get-JaTrayCandidates $flyout $taskbar)",
    keyboardReopenIndex,
  );
  const runtimeIdentityIndex = source.indexOf(
    "$freshTrayItems | Where-Object { $_.key -eq $trayKey }",
    freshTrayItemsIndex,
  );
  const keyboardElementIndex = source.indexOf(
    "$keyboardTrayItem = $keyboardCandidate.element",
    runtimeIdentityIndex,
  );
  const focusIndex = source.indexOf("$keyboardTrayItem.SetFocus()", keyboardElementIndex);
  const keyboardInputIndex = source.indexOf("[JaTrayNativeInput]::ContextMenuKey()", focusIndex);
  const keyboardSuccessIndex = source.indexOf("$keyboardAttempted = $true", keyboardInputIndex);
  const keyboardGateIndex = source.indexOf("if ($keyboardAttempted) {", keyboardSuccessIndex);
  const menuIndex = source.indexOf("$menuItem.Current.Name -ne '退出 Ja'", keyboardGateIndex);
  const ownerIndex = source.indexOf("$targetPids -contains $menuOwner", menuIndex);
  const invokeIndex = source.indexOf("$invoke.Invoke()", ownerIndex);
  const required = [
    "UIAutomationClient",
    "TopLevelWindowForOverflowXamlIsland",
    "GetPhysicalCursorPos",
    "SetPhysicalCursorPos",
    "private struct NativeRect",
    "WindowFromPoint",
    "GetAncestor",
    "GetWindowThreadProcessId",
    "GetClassName",
    "private delegate bool EnumWindowsCallback",
    "IsWindowVisible",
    "SendMessageTimeout",
    "0x0002",
    "250",
    "GetMenuItemCount",
    "GetMenuString",
    "GetMenuState",
    "GetMenuItemRect(IntPtr.Zero",
    'ReadWindowClass(window) != "#32768"',
    "string.Equals(text.ToString().Replace",
    "(state & 0x0003) != 0",
    "private struct MOUSEINPUT",
    "private struct KEYBDINPUT",
    "private struct INPUTUNION",
    "private struct INPUT",
    "[FieldOffset(0)]",
    "private static extern uint SendInput",
    "SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>())",
    "if (sent != inputs.Length)",
    "function Find-JaVisibleFlyout",
    "function Open-JaFlyout",
    "function Reopen-JaFlyout",
    "function Get-JaTrayCandidates",
    "function Invoke-JaOwnedNativeQuit",
    "FindOwnedMenuItem([uint32[]]$targetPids, '退出 Ja')",
    '$hit -notmatch "pid=$owner;class=#32768$"',
    "$trayItem.Current.AutomationId -eq 'NotifyItemIcon'",
    "foreach ($trayContainer in @($currentFlyout, $currentTaskbar))",
    "$trayItem in $trayContainer.FindAll",
    "$trayItem.Current.IsOffscreen",
    "$freshTrayItems = @(Get-JaTrayCandidates $flyout $taskbar)",
    "$freshTrayItems | Where-Object { $_.key -eq $trayKey }",
    "$keyboardTrayItem.SetFocus()",
    "$attemptedTrayKey = [string]$keyboardCandidate.key",
    "[JaTrayNativeInput]::ContextMenuKey()",
    "[JaTrayNativeInput]::EscapeKey()",
    "Get-VisibleMenuDiagnostics",
    "$menuDeadline -gt $selectionDeadline",
    "$pointerDeadline -gt $selectionDeadline",
    "timeout: trayExitTimeoutMs",
    "pointerAttempts=$pointerAttempts",
    "pointerHit=$pointerHit",
    "nativeMenuPoint=$nativeMenuPoint",
    "nativeMenuHit=$nativeMenuHit",
    "nativeMenuError=$nativeMenuError",
    "visibleMenus=$visibleMenus",
    "托盘退出命令失败 code=",
    "$menuItem.Current.IsOffscreen",
    "$rejectedTrayItems.Contains($trayKey)",
    "$candidate.Current.IsOffscreen",
    "windows_uia_tray_menu",
  ];
  if (
    required.some((marker) => !source.includes(marker)) ||
    nativeMenuFunctionIndex < 0 ||
    nativeMenuLookupIndex < nativeMenuFunctionIndex ||
    nativeMenuHitGateIndex < nativeMenuLookupIndex ||
    nativeMenuClickIndex < nativeMenuHitGateIndex ||
    flyoutPolicyIndex < 0 ||
    visibleFlyoutIndex < flyoutPolicyIndex ||
    flyoutOpenIndex < visibleFlyoutIndex ||
    flyoutReopenIndex < flyoutOpenIndex ||
    trayCandidatesIndex < flyoutReopenIndex ||
    initialFlyoutIndex < trayCandidatesIndex ||
    flyoutCloseIndex < initialFlyoutIndex ||
    trayBoundsIndex < trayOffscreenIndex ||
    trayBoundsGuardIndex < trayBoundsIndex ||
    cachedXIndex < trayBoundsGuardIndex ||
    cachedYIndex < cachedXIndex ||
    trayContainersIndex < flyoutOpenIndex ||
    trayScopeIndex < trayContainersIndex ||
    trayOffscreenIndex < trayScopeIndex ||
    pointerIndex < cachedYIndex ||
    pointerPointIndex < pointerIndex ||
    saveCursorIndex < pointerPointIndex ||
    preexistingNativeMenuIndex < saveCursorIndex ||
    foregroundIndex < preexistingNativeMenuIndex ||
    moveCursorIndex < foregroundIndex ||
    preClickWaitIndex < moveCursorIndex ||
    pointerHitIndex < preClickWaitIndex ||
    rightClickIndex < pointerHitIndex ||
    postClickWaitIndex < rightClickIndex ||
    postClickNativeMenuIndex < postClickWaitIndex ||
    restoreCursorIndex < postClickWaitIndex ||
    restoreCursorIndex < postClickNativeMenuIndex ||
    pointerOwnerIndex < restoreCursorIndex ||
    keyboardFlagIndex < pointerOwnerIndex ||
    keyboardReopenIndex < keyboardFlagIndex ||
    freshTrayItemsIndex < keyboardReopenIndex ||
    runtimeIdentityIndex < freshTrayItemsIndex ||
    keyboardElementIndex < runtimeIdentityIndex ||
    focusIndex < keyboardElementIndex ||
    keyboardInputIndex < focusIndex ||
    keyboardSuccessIndex < keyboardInputIndex ||
    keyboardGateIndex < keyboardSuccessIndex ||
    menuIndex < keyboardGateIndex ||
    ownerIndex < menuIndex ||
    invokeIndex < ownerIndex ||
    flyoutCloseIndex < invokeIndex ||
    source.includes("mouse_event") ||
    source.includes("GetClickablePoint") ||
    source.includes("GetCursorPos(") ||
    source.includes("SetCursorPos(") ||
    source.includes("WM_COMMAND") ||
    source.includes("SendMessage(") ||
    source.includes("PostMessage(") ||
    source.includes("$trayItem.SetFocus()") ||
    source.includes("System.Windows.Forms") ||
    source.includes("::SendWait(") ||
    source.includes("taskkill.exe")
  ) {
    throw new Error("Tauri owned tray-exit contract failed");
  }
}

/**
 * graceful close deadline 后只终止本 runner 精确拥有的进程树；
 * 每个回退 PID 都已被观察为根后代，因此不会触碰无关 Cargo/Java/WebView2 进程。
 */
async function stopProcessTree(rootIdentity, observed, preexistingJaIdentities, signal) {
  const kill = async (pid, tree) => {
    try {
      await execFileAsync("taskkill.exe", ["/PID", String(pid), ...(tree ? ["/T"] : []), "/F"], {
        windowsHide: true,
        maxBuffer: 1 * 1024 * 1024,
        timeout: snapshotTimeoutMs,
        signal,
      });
      return true;
    } catch {
      // 进程可能在快照与 taskkill 之间退出，需要容忍这一正常竞态。
      return false;
    }
  };
  if (signal?.aborted) {
    return;
  }
  if (!hasProcessIdentity(rootIdentity)) {
    return;
  }
  const current = await processSnapshot(signal);
  const currentRoot = current.find((entry) => sameProcessIdentity(rootIdentity, entry));
  if (
    currentRoot !== undefined &&
    !isProtectedPreexistingJa(currentRoot, preexistingJaIdentities)
  ) {
    // 对准确根进程执行一次 /T kill，让 Windows 遍历已验证的树；
    // 若串行终止陈旧后代列表，可能在 dev wrapper 已消失时耗尽 cleanup deadline。
    await kill(currentRoot.pid, true);
  }
  if (signal?.aborted) {
    return;
  }
  // tree kill 后重新枚举；只有仍保持原完整身份的存活进程可接受直接回退终止，
  // 且这些终止并行执行，避免单个卡住的 Windows 进程拖垮全部 cleanup。
  const afterRoot = await processSnapshot(signal);
  const survivors = [...observed.values()]
    .map((entry) => afterRoot.find((candidate) => sameProcessIdentity(entry, candidate)))
    .filter(
      (entry) => entry !== undefined && !isProtectedPreexistingJa(entry, preexistingJaIdentities),
    );
  if (survivors.length > 0) {
    await Promise.all(survivors.map((entry) => kill(entry.pid, false)));
  }
}

/**
 * 使用新的有界 signal 捕获最终身份；主 deadline 已 abort 时，
 * cleanup 证据绝不能复制 kill 前的 observed map。
 */
async function captureFreshTreeState(observed, rootIdentity, incompleteObserved) {
  const freshDeadline = createDeadline("cleanup fresh snapshot", snapshotTimeoutMs);
  try {
    const current = await processSnapshot(freshDeadline.signal);
    const tree =
      rootIdentity === undefined
        ? undefined
        : processTree(rootIdentity, current, incompleteObserved);
    if (tree !== undefined) {
      for (const [pid, entry] of tree) {
        observed.set(pid, entry);
      }
    }
    const live = [];
    const reused = [];
    for (const entry of observed.values()) {
      const candidate = current.find((item) => item.pid === entry.pid);
      if (candidate === undefined) {
        continue;
      }
      if (sameProcessIdentity(entry, candidate)) {
        live.push(candidate);
      } else {
        reused.push(candidate);
      }
    }
    return {
      live,
      reused,
      incompleteLive: [...(incompleteObserved?.current.values() ?? [])],
      aborted: false,
      snapshotStatus: "fresh",
    };
  } catch (error) {
    return {
      live: [],
      reused: [],
      incompleteLive: [],
      aborted: false,
      snapshotStatus: "failed",
      snapshotError: error,
    };
  } finally {
    freshDeadline.cancel();
  }
}

/**
 * 轮询准确观察到的身份，直到所有已启动后代消失；
 * timeout/abort 路径始终用新的有界查询替换陈旧状态。
 */
async function waitForTreeGone(observed, deadline, signal, rootIdentity, incompleteObserved) {
  while (Date.now() < deadline) {
    if (signal?.aborted) {
      const fresh = await captureFreshTreeState(observed, rootIdentity, incompleteObserved);
      return { ...fresh, aborted: true };
    }
    const current = await processSnapshot(signal);
    const tree =
      rootIdentity === undefined
        ? undefined
        : processTree(rootIdentity, current, incompleteObserved);
    if (tree !== undefined) {
      for (const [pid, entry] of tree) {
        observed.set(pid, entry);
      }
    }
    const live = [...observed.values()].filter((entry) =>
      current.some((item) => sameProcessIdentity(entry, item)),
    );
    const incompleteLive = [...(incompleteObserved?.current.values() ?? [])];
    if (live.length === 0 && incompleteLive.length === 0) {
      return { live: [], reused: [], incompleteLive: [], aborted: false, snapshotStatus: "fresh" };
    }
    await waitForDelay(pollMs, signal);
  }
  return captureFreshTreeState(observed, rootIdentity, incompleteObserved);
}

/**
 * 保留 command-line 证据的审查价值，同时应用与其他用户可见诊断相同的路径和敏感信息脱敏规则。
 */
function summarizeProcessIdentity(entry, directories) {
  return {
    pid: entry.pid,
    parentPid: entry.parentPid,
    name: entry.name,
    creationDate: entry.creationDate,
    commandLine: redact(entry.commandLine, directories),
  };
}

/**
 * 在 `startTauri` 前获取故障关闭基线。私有返回值仅在内存中保留原始 command line；
 * 持久证据使用标准的路径/敏感信息脱敏身份投影。
 */
async function capturePreexistingJaBaseline(directories) {
  const deadline = createDeadline("preexisting Ja baseline", snapshotTimeoutMs);
  let snapshotStatus = "failed";
  try {
    const snapshot = await processSnapshot(deadline.signal);
    snapshotStatus = "fresh";
    const identities = selectPreexistingJaIdentities(snapshot);
    return {
      identities,
      evidence: {
        status: "passed",
        snapshotStatus,
        count: identities.length,
        identities: identities.map((entry) => summarizeProcessIdentity(entry, directories)),
      },
    };
  } catch (error) {
    return {
      identities: [],
      evidence: {
        status: "failed",
        snapshotStatus,
        count: 0,
        error: redact(error?.message ?? error, directories),
      },
      failure: new Error("启动前无法取得完整 fresh preexisting Ja identity baseline", {
        cause: error,
      }),
    };
  } finally {
    deadline.cancel();
  }
}

/**
 * cleanup 后独立于整轮 signal 重新枚举 Windows。每个基线身份必须仍以相同 PID、creationDate
 * 和 commandLine 存在；缺失、复用或不完整行都会使运行失败。
 */
async function verifyPreexistingJaGuard(expected, directories, scope) {
  const deadline = createDeadline(`${scope} preexisting Ja verification`, snapshotTimeoutMs);
  try {
    const snapshot = await processSnapshot(deadline.signal);
    const result = evaluatePreexistingJaIdentities(expected, snapshot);
    const failed =
      result.missing.length > 0 || result.reused.length > 0 || result.incomplete.length > 0;
    return {
      evidence: {
        scope,
        status: failed ? "failed" : "passed",
        snapshotStatus: "fresh",
        expectedCount: expected.length,
        preservedCount: result.preserved.length,
        preserved: result.preserved.map((entry) => summarizeProcessIdentity(entry, directories)),
        missing: result.missing.map((entry) => summarizeProcessIdentity(entry, directories)),
        reused: result.reused.map(({ expected: baseline, actual }) => ({
          expected: summarizeProcessIdentity(baseline, directories),
          actual: summarizeProcessIdentity(actual, directories),
        })),
        incomplete: result.incomplete.map(({ expected: baseline, actual }) => ({
          expected: summarizeProcessIdentity(baseline, directories),
          actual: summarizeProcessIdentity(actual, directories),
        })),
      },
      failure: failed
        ? new Error(`${scope} preexisting Ja identity 缺失、复用或无法完整验证`)
        : undefined,
    };
  } catch (error) {
    return {
      evidence: {
        scope,
        status: "failed",
        snapshotStatus: "failed",
        expectedCount: expected.length,
        preservedCount: 0,
        error: redact(error?.message ?? error, directories),
      },
      failure: new Error(`${scope} 无法取得 fresh preexisting Ja verification snapshot`, {
        cause: error,
      }),
    };
  } finally {
    deadline.cancel();
  }
}

/**
 * 规范化 Windows 路径写法，用于身份范围内的 WebView2 选择与脱敏检查，
 * 同时不把无关同名进程视为 owned。
 */
function windowsPathKey(value) {
  return String(value ?? "")
    .replace(/^\\\\\?\\/, "")
    .replace(/[\\/]+/g, "\\")
    .toLowerCase();
}

/**
 * 只选择已在本 launcher 下观察到的产品进程；dev wrapper 不进入该 graceful gate，
 * 而是在后续强制回收。
 */
function productProcessEntries(observed, directories, preexistingJaIdentities = []) {
  const webviewKey = windowsPathKey(directories.webview);
  return [...observed.values()].filter((entry) => {
    if (isProtectedPreexistingJa(entry, preexistingJaIdentities)) return false;
    const name = entry.name.toLowerCase();
    const commandLine = entry.commandLine.toLowerCase();
    if (name === "ja.exe") {
      return true;
    }
    if (name === "java.exe") {
      return (
        commandLine.includes("ja-app-server.jar") && commandLine.includes("--data-dir-base64=")
      );
    }
    return (
      name === "msedgewebview2.exe" &&
      commandLine.includes("--user-data-dir") &&
      webviewKey !== "" &&
      windowsPathKey(commandLine).includes(webviewKey)
    );
  });
}

/** 停止 watcher 后只等待本轮 identity；exit trace 与进程归零是 shortcut cleanup 的外部边界。 */
async function cleanupPhase(
  runId,
  phase,
  rootIdentity,
  observed,
  incompleteObserved,
  preexistingJaIdentities,
  watcher,
  directories,
  evidence,
  runSignal,
) {
  let failure;
  let finalTree = { live: [], reused: [], incompleteLive: [], snapshotStatus: "not_captured" };
  let tauriPids = [];
  let exitRequest = { status: "not_requested" };
  let productLiveAfterGrace = [];
  let liveAfterForce = [];
  let forcedDevTree = [];
  let productObserved = new Map();
  let gracefulSnapshot = { status: "not_captured" };
  let exitTrace;
  const graceful = createDeadline(`${phase} graceful cleanup`, closeDeadlineMs);
  try {
    await watcher?.stop();
    if (watcher?.failure !== undefined && !runSignal?.aborted) {
      failure = watcher.failure;
    }
    await stopSnapshotHelpers(graceful.signal);
    const current = await processSnapshot(graceful.signal);
    const tree =
      rootIdentity === undefined
        ? undefined
        : processTree(rootIdentity, current, incompleteObserved);
    if (tree !== undefined) {
      for (const [pid, entry] of tree) {
        observed.set(pid, entry);
      }
    }
    productObserved = new Map(
      productProcessEntries(observed, directories, preexistingJaIdentities).map((entry) => [
        entry.pid,
        entry,
      ]),
    );
    if (productObserved.size === 0) {
      failure ??= new Error(`${phase} 未观察到本轮产品进程身份`);
    }
    tauriPids = tauriProcessIds(observed, current, preexistingJaIdentities);
    if (tauriPids.length > 0) {
      try {
        exitRequest = {
          status: "requested",
          ...(await requestTrayExit(tauriPids, graceful.signal)),
        };
      } catch (error) {
        // graceful signal 也可能在原生 UIA/Win32 边界内触发；保留真实请求失败，不能让后续
        // “进程仍存活”覆盖根因，同时 force cleanup 仍按精确 owned tree 继续执行。
        failure ??= new Error(`${phase} 托盘退出请求失败`, { cause: error });
      }
    }
    try {
      const productGrace = await waitForTreeGone(
        productObserved,
        graceful.deadline,
        graceful.signal,
      );
      const settledGrace = await settleGracefulProductObservation(
        phase,
        productObserved,
        productGrace,
      );
      gracefulSnapshot = {
        status: settledGrace.snapshotStatus ?? "fresh",
        error: settledGrace.snapshotError,
      };
      productLiveAfterGrace = settledGrace.live;
      failure ??= settledGrace.failure;
    } catch (error) {
      if (graceful.signal.aborted && productObserved.size > 0) {
        const settledGrace = await settleGracefulProductObservation(phase, productObserved, {
          aborted: true,
          live: [],
        });
        gracefulSnapshot = {
          status: settledGrace.snapshotStatus ?? "fresh",
          error: settledGrace.snapshotError,
        };
        productLiveAfterGrace = settledGrace.live;
        failure ??= settledGrace.failure;
      } else if (!graceful.signal.aborted) {
        failure ??= error;
      }
    }
  } catch (error) {
    if (!graceful.signal.aborted) {
      failure ??= error;
    }
  } finally {
    graceful.cancel();
  }

  let force;
  const forcePreflight = createDeadline(`${phase} force preflight`, snapshotTimeoutMs);
  try {
    await stopSnapshotHelpers(forcePreflight.signal);
    const afterGrace = await processSnapshot(forcePreflight.signal);
    const afterGraceTree =
      rootIdentity === undefined
        ? undefined
        : processTree(rootIdentity, afterGrace, incompleteObserved);
    if (afterGraceTree !== undefined) {
      for (const [pid, entry] of afterGraceTree) {
        observed.set(pid, entry);
      }
    }
    const currentProduct = new Map(
      productProcessEntries(observed, directories, preexistingJaIdentities).map((entry) => [
        entry.pid,
        entry,
      ]),
    );
    liveAfterForce = [...currentProduct.values()].filter((entry) =>
      afterGrace.some((candidate) => sameProcessIdentity(entry, candidate)),
    );
    forcedDevTree = [...observed.values()].filter((entry) =>
      afterGrace.some((candidate) => sameProcessIdentity(entry, candidate)),
    );
    if (liveAfterForce.length > 0) {
      failure ??= new Error(`${phase} 产品进程在 graceful deadline 后仍存活`);
    }
    force = createDeadline(`${phase} force cleanup`, closeDeadlineMs);
    await stopProcessTree(rootIdentity, observed, preexistingJaIdentities, force.signal);
    finalTree = await waitForTreeGone(
      observed,
      force.deadline,
      force.signal,
      rootIdentity,
      incompleteObserved,
    );
    await stopSnapshotHelpers(force.signal);
    if (snapshotHelpers.size > 0) {
      failure ??= new Error("CIM snapshot helper force cleanup 后仍未确认退出");
    }
  } catch (error) {
    failure ??= error;
  } finally {
    forcePreflight.cancel();
    force?.cancel();
  }

  // force signal 可能在 taskkill 与最终轮询之间 abort；写入证据前获取一组独立的新鲜身份，
  // 不把历史 observed map 复制进 liveAfterCleanup/liveAfterFinally。
  if (finalTree.snapshotStatus !== "fresh") {
    const freshFinalTree = await captureFreshTreeState(observed, rootIdentity, incompleteObserved);
    if (freshFinalTree.snapshotStatus === "fresh" || finalTree.snapshotStatus === "not_captured") {
      finalTree = freshFinalTree;
    }
  }
  if (finalTree.snapshotStatus !== "fresh") {
    failure ??= new Error(`${phase} 清理后无法取得 fresh process snapshot`);
  }

  evidence[phase].process = {
    root:
      rootIdentity === undefined ? undefined : summarizeProcessIdentity(rootIdentity, directories),
    observed: [...observed.values()].map((entry) => summarizeProcessIdentity(entry, directories)),
    tauriPids,
    exitRequest,
    liveAfterGrace: productLiveAfterGrace.map((entry) =>
      summarizeProcessIdentity(entry, directories),
    ),
    liveAfterForce: liveAfterForce.map((entry) => summarizeProcessIdentity(entry, directories)),
    forcedDevTree: forcedDevTree.map((entry) => summarizeProcessIdentity(entry, directories)),
    liveAfterCleanup: finalTree.live.map((entry) => summarizeProcessIdentity(entry, directories)),
    reusedPids: finalTree.reused.map((entry) => summarizeProcessIdentity(entry, directories)),
    cleanupSnapshot: {
      status: finalTree.snapshotStatus,
      error:
        finalTree.snapshotError === undefined
          ? undefined
          : redact(finalTree.snapshotError?.message ?? finalTree.snapshotError, directories),
    },
    gracefulSnapshot: {
      status: gracefulSnapshot.status,
      error:
        gracefulSnapshot.error === undefined
          ? undefined
          : redact(gracefulSnapshot.error?.message ?? gracefulSnapshot.error, directories),
    },
    incompleteObserved: {
      current: [...incompleteObserved.current.values()].map((entry) =>
        summarizeProcessIdentity(entry, directories),
      ),
      history: incompleteObserved.history.map((entry) =>
        summarizeProcessIdentity(entry, directories),
      ),
      dropped: incompleteObserved.dropped,
    },
  };
  exitTrace = await readExitTrace(runId, phase, directories.runtime);
  evidence[phase].exitTrace = {
    ...exitTrace,
    outcome: exitTrace.status === "complete" ? "complete" : "incomplete",
  };
  if (exitTrace.status !== "complete") {
    failure ??= new Error(`${phase} exit trace ${exitTrace.status}`);
  }
  evidence.tree.push({
    phase,
    rootPid: rootIdentity?.pid,
    liveAfterFinally: finalTree.live.map((entry) => entry.pid),
    liveAfterFinallyStatus: finalTree.snapshotStatus,
  });
  if (finalTree.live.length > 0) {
    failure ??= new Error(`${phase} 清理后仍有本轮进程`);
  }
  if (finalTree.incompleteLive?.length > 0) {
    failure ??= new Error(`${phase} 清理后仍有身份不完整的本轮进程，未执行 PID-only kill`);
  }
  evidence[phase].nativeShortcutExitCleanup = {
    status:
      exitTrace.status === "complete" &&
      finalTree.snapshotStatus === "fresh" &&
      finalTree.live.length === 0 &&
      (finalTree.incompleteLive?.length ?? 0) === 0
        ? "passed"
        : "failed",
    fullExitTrace: exitTrace.status === "complete",
    ownedProcessesGone:
      finalTree.live.length === 0 && (finalTree.incompleteLive?.length ?? 0) === 0,
    registryBoundary: "private_not_exposed_process_exit_is_final_controller_cleanup",
  };
  const preexistingVerification = await verifyPreexistingJaGuard(
    preexistingJaIdentities,
    directories,
    `${phase} cleanup`,
  );
  evidence[phase].process.preexistingJaGuard = preexistingVerification.evidence;
  evidence.preexistingJaGuard.cleanupVerifications.push(preexistingVerification.evidence);
  failure ??= preexistingVerification.failure;
  return failure;
}

/**
 * 使用与正式 Gate 相同的生产 binary、窗口配置、Java sidecar 和隔离目录预热 WebView2 UDF，
 * 但不开放 CDP。完成 profile ACK 后仍走真实托盘退出、exit trace、进程归零与既有 JA 守卫，
 * 使 fresh-profile 适配不会弱化两轮正式桌面验收或遗留额外产品进程。
 */
async function primeWebViewProfile({
  runId,
  directories,
  frontendPort,
  baseEnv,
  pnpmCommand,
  cargoCommand,
  productionRuntime,
  preexistingJaIdentities,
  evidence,
  runDeadline,
}) {
  const phase = "profilePrime";
  const exitTracePath = join(directories.runtime, `ja-exit-trace-${runId}-${phase}.jsonl`);
  const configPath = await writeE2eTauriConfig(directories, frontendPort, false);
  const launch = startTauri(
    directories,
    frontendPort,
    0,
    configPath,
    exitTracePath,
    undefined,
    undefined,
    undefined,
    baseEnv,
    pnpmCommand,
    cargoCommand,
    productionRuntime,
    false,
  );
  if (!launch.child.pid) throw new Error("profilePrime Tauri launcher 没有 PID");
  const observed = new Map();
  const incompleteObserved = createIncompleteObserved();
  let rootIdentity;
  let watcher;
  let cleanupFailure;
  evidence[phase].stage = `${phase}:launch`;
  try {
    evidence[phase].stage = `${phase}:root_identity`;
    rootIdentity = await waitForRootIdentity(
      launch.child.pid,
      Math.min(runDeadline.deadline, Date.now() + 10_000),
      runDeadline.signal,
    );
    observed.set(rootIdentity.pid, rootIdentity);
    const initial = await processSnapshot(runDeadline.signal);
    const initialTree = processTree(rootIdentity, initial, incompleteObserved);
    if (initialTree === undefined) {
      throw new Error("profilePrime 未能在初始快照中重验 launcher root");
    }
    for (const [pid, entry] of initialTree) observed.set(pid, entry);
    evidence[phase].stage = `${phase}:watcher`;
    watcher = startProcessWatcher(rootIdentity, observed, incompleteObserved, runDeadline.signal);
    evidence[phase].stage = `${phase}:profile_ack`;
    evidence[phase].profile = await waitForWebViewProfileReady(
      rootIdentity,
      incompleteObserved,
      directories,
      Math.min(runDeadline.deadline, Date.now() + cdpStartupDeadlineMs),
      launch,
      runDeadline.signal,
    );
    evidence[phase].status = "ready";
  } finally {
    try {
      cleanupFailure = await cleanupPhase(
        runId,
        phase,
        rootIdentity,
        observed,
        incompleteObserved,
        preexistingJaIdentities,
        watcher,
        directories,
        evidence,
        runDeadline.signal,
      );
    } finally {
      evidence[phase].launcher = launcherOutputSummary(launch, directories);
    }
  }
  if (cleanupFailure !== undefined) throw cleanupFailure;
  return configPath;
}

/**
 * 在单一本地预算内验证 Codex 风格 shell，并报告最后一个语义 checkpoint。
 * 控件缺失时必须趁页面仍可检查立即失败，避免耗尽整轮 deadline 并抹去证据。
 */
async function assertCodexShellStructure(page, deadline, signal, recordStage) {
  const structureDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  const timeout = () => Math.max(1, structureDeadline - Date.now());
  const stage = (name) => recordStage?.(`codex_shell:${name}`);
  throwIfAborted(signal);
  stage("headings");
  await page
    .getByRole("heading", { name: "项目", exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await page
    .getByRole("heading", { name: "最近对话", exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await page
    .getByRole("button", { name: "新会话", exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  if ((await page.getByRole("button", { name: "添加项目", exact: true }).count()) !== 1) {
    throw new Error("项目添加入口不是标题旁唯一的加号按钮");
  }
  stage("layout");
  const structure = await page.evaluate(() => {
    const projects = globalThis.document.querySelector(".ja-navigation-projects");
    const history = globalThis.document.querySelector(".ja-navigation-history");
    const runtime = globalThis.document.querySelector(".ja-navigation-runtime");
    const settings = globalThis.document.querySelector(".ja-navigation-settings");
    const titlebar = globalThis.document.querySelector(".ja-titlebar");
    const follows = (first, second) =>
      first !== null &&
      second !== null &&
      (first.compareDocumentPosition(second) & globalThis.Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
    return {
      projectsBeforeHistory: follows(projects, history),
      runtimeBeforeSettings: follows(runtime, settings),
      titlebarText: titlebar?.textContent?.trim() ?? "",
      captionCount: globalThis.document.querySelectorAll(".ja-titlebar-caption").length,
    };
  });
  if (!structure.projectsBeforeHistory || !structure.runtimeBeforeSettings) {
    throw new Error("Codex shell 区域顺序不符合项目、对话、状态、设置的约束");
  }
  stage("navigation_sections");
  const projectToggle = page.getByRole("button", { name: "折叠项目", exact: true });
  const historyToggle = page.getByRole("button", { name: "折叠最近对话", exact: true });
  await projectToggle.click();
  await page
    .getByRole("list", { name: "项目列表", exact: true })
    .waitFor({ state: "detached", timeout: timeout() });
  await page.getByRole("button", { name: "展开项目", exact: true }).click();
  await page
    .getByRole("list", { name: "项目列表", exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await historyToggle.click();
  await page
    .getByRole("list", { name: "最近对话列表", exact: true })
    .waitFor({ state: "detached", timeout: timeout() });
  await page.getByRole("button", { name: "展开最近对话", exact: true }).click();
  await page
    .getByRole("list", { name: "最近对话列表", exact: true })
    .waitFor({ state: "visible", timeout: timeout() });

  stage("navigation_resize");
  const navigationSeparator = page.getByRole("separator", {
    name: "调整导航栏宽度",
    exact: true,
  });
  await navigationSeparator.waitFor({ state: "visible", timeout: timeout() });
  const navigationSizeBefore = Number(await navigationSeparator.getAttribute("aria-valuenow"));
  const navigationBox = await navigationSeparator.boundingBox();
  if (!Number.isFinite(navigationSizeBefore) || navigationBox === null)
    throw new Error("导航栏分隔器缺少真实尺寸");
  const navigationX = navigationBox.x + navigationBox.width / 2;
  const navigationY = navigationBox.y + navigationBox.height / 2;
  await page.mouse.move(navigationX, navigationY);
  await page.mouse.down();
  await page.mouse.move(navigationX + 48, navigationY, { steps: 6 });
  await page.mouse.up();
  await page.waitForFunction(
    (before) =>
      Number(
        globalThis.document
          .querySelector('[role="separator"][aria-label="调整导航栏宽度"]')
          ?.getAttribute("aria-valuenow"),
      ) >
      before + 1,
    navigationSizeBefore,
    { timeout: timeout() },
  );
  const navigationSizeAfter = Number(await navigationSeparator.getAttribute("aria-valuenow"));
  await navigationSeparator.focus();
  await page.keyboard.press("ArrowLeft");
  await page.keyboard.press("ArrowRight");
  if (
    Math.abs(
      Number(await navigationSeparator.getAttribute("aria-valuenow")) - navigationSizeAfter,
    ) > 0.01
  )
    throw new Error("导航栏键盘调宽未形成可逆事务");
  if (structure.captionCount !== 0 || structure.titlebarText !== "") {
    throw new Error("应用标题栏仍显示 Ja 或其它可见标题");
  }
  if ((await page.getByRole("button", { name: "收起导航栏", exact: true }).count()) !== 0) {
    throw new Error("侧栏内部仍存在重复折叠按钮");
  }

  if (
    (await page.getByRole("button", { name: "显示对话详情", exact: true }).count()) !== 0 ||
    (await page.getByRole("complementary", { name: "对话详情", exact: true }).count()) !== 0
  ) {
    throw new Error("无项目对话仍暴露重复的对话详情右栏");
  }
  stage("workspace_drawer");
  const drawerTrigger = page.getByRole("button", { name: "显示工作区面板", exact: true });
  await drawerTrigger.waitFor({ state: "visible", timeout: timeout() });
  await drawerTrigger.click();
  const workspaceGate = page.locator('.ja-inspector[aria-label="工作区面板"]');
  await workspaceGate.waitFor({ state: "visible", timeout: timeout() });
  await workspaceGate.locator('[data-workbench-tab="files"]').waitFor({
    state: "visible",
    timeout: timeout(),
  });
  await workspaceGate.locator('[data-workbench-tab="preview"]').waitFor({
    state: "visible",
    timeout: timeout(),
  });
  await workspaceGate.getByRole("button", { name: "新建标签页", exact: true }).click();
  await workspaceGate
    .getByRole("heading", { name: "打开工作区工具", exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await workspaceGate
    .getByRole("button", { name: /终端/u })
    .waitFor({ state: "visible", timeout: timeout() });
  await captureVisualEvidence(
    page,
    `implementation-general-workbench-${visualTheme}-native-1280x820.png`,
  );
  await workspaceGate.getByRole("button", { name: "收起右侧栏", exact: true }).click();
  await waitForWorkbenchCollapsed(workspaceGate, deadline, signal);
  await drawerTrigger.waitFor({ state: "visible", timeout: timeout() });
  stage("summary");
  const summaryTrigger = page.getByRole("button", { name: "打开对话摘要", exact: true });
  await summaryTrigger.waitFor({ state: "visible", timeout: timeout() });
  await summaryTrigger.click();
  const summary = page.getByRole("dialog", { name: "环境信息", exact: true });
  await summary.waitFor({ state: "visible", timeout: timeout() });
  await summaryTrigger.click();
  await summary.waitFor({ state: "detached", timeout: timeout() });
}

/**
 * 从只读 native snapshot、生命周期事件与可见失败页提取最小启动证据。三条信号必须同时保留，
 * 因为 start_failed 后权威 snapshot 可能已回落为 stopped，而仅看最终状态会丢失真实失败终态。
 */
async function captureRuntimeStartupState(page) {
  return page
    .evaluate(async () => {
      const internals = globalThis.__TAURI_INTERNALS__;
      let nativeState = { status: "unavailable" };
      try {
        const value = await internals?.invoke?.("ja_runtime_state");
        const state = value !== null && typeof value === "object" ? value : {};
        nativeState = {
          status: typeof state.status === "string" ? state.status : "invalid",
          generation: Number.isSafeInteger(state.generation) ? state.generation : undefined,
          serverInstanceIdPresent:
            typeof state.serverInstanceId === "string" && state.serverInstanceId.length > 0,
        };
      } catch {
        // native 读取失败本身只用于归类，原始异常不得进入持久化 E2E 证据。
      }

      const statusEvents = (
        Array.isArray(globalThis.__JA_E2E_TAURI_EVENTS__) ? globalThis.__JA_E2E_TAURI_EVENTS__ : []
      )
        .slice(-32)
        .flatMap((value) => {
          const envelope = value !== null && typeof value === "object" ? value : {};
          const payload =
            envelope.payload !== null && typeof envelope.payload === "object"
              ? envelope.payload
              : envelope;
          if (payload.method !== "runtime/status-changed") return [];
          const params =
            payload.params !== null && typeof payload.params === "object" ? payload.params : {};
          return [
            {
              status: typeof params.status === "string" ? params.status : "invalid",
              generation: Number.isSafeInteger(params.generation) ? params.generation : undefined,
              reason: typeof params.reason === "string" ? params.reason : undefined,
            },
          ];
        });
      const runtimeSurface = [...globalThis.document.querySelectorAll(".ja-navigation-runtime")]
        .slice(0, 4)
        .map((element) => ({
          accessibleName: element.getAttribute("aria-label"),
          text: element.textContent?.trim().slice(0, 160) ?? "",
        }));
      const failure = globalThis.document.querySelector(".ja-error-state");
      const failureSurface =
        failure instanceof globalThis.HTMLElement
          ? {
              heading: failure.querySelector("h1, h2")?.textContent?.trim().slice(0, 120) ?? "",
              message: failure.querySelector("p")?.textContent?.trim().slice(0, 240) ?? "",
              actions: [...failure.querySelectorAll("button")]
                .slice(0, 4)
                .map((button) => button.textContent?.trim().slice(0, 80) ?? ""),
            }
          : undefined;
      return { nativeState, statusEvents, runtimeSurface, failureSurface };
    })
    .catch(() => ({
      nativeState: { status: "unavailable" },
      statusEvents: [],
      runtimeSurface: [],
      captureUnavailable: true,
    }));
}

/**
 * 侧栏把 runtime 健康度建模为只读 status，设置入口是相邻的独立按钮；轮询同时监听
 * crashed/incompatible/faulted/recovery_required 与产品失败页，使确定失败立即携带现场退出，
 * 而正常冷启动仍拥有独立的局部预算，不会消耗整轮 15 分钟期限。
 */
async function waitForRuntimeReady(page, deadline, signal) {
  const readyDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  const connectedStatus = page.getByRole("status", {
    name: "本地运行时：已连接",
    exact: true,
  });
  const terminalFailures = new Set(["crashed", "incompatible", "faulted", "recovery_required"]);
  let diagnostic = await captureRuntimeStartupState(page);
  while (Date.now() < readyDeadline) {
    throwIfAborted(signal);
    const [connected, observed] = await Promise.all([
      connectedStatus.isVisible().catch(() => false),
      captureRuntimeStartupState(page),
    ]);
    diagnostic = observed;
    if (connected) return;
    const terminalEvent = observed.statusEvents.findLast((event) =>
      terminalFailures.has(event.status),
    );
    if (
      terminalEvent !== undefined ||
      terminalFailures.has(observed.nativeState.status) ||
      observed.failureSurface !== undefined
    ) {
      throw new Error(`本地运行时启动已进入失败终态：${JSON.stringify(observed)}`);
    }
    await waitForDelay(250, signal);
  }
  throw new Error(`本地运行时未在局部启动期限内进入已连接状态：${JSON.stringify(diagnostic)}`);
}

/**
 * 用两个只读原生命令区分“通用 Workspace 不可用”“Thread list 被 Rust 拒绝”和
 * “Thread 已创建但 renderer 解码失败”。证据只保留状态、错误码和字段名，不复制路径或正文。
 */
async function captureInitialHistoryState(page) {
  return page
    .evaluate(async () => {
      const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
      if (typeof invoke !== "function") return { bridge: "unavailable" };
      const errorCode = (error) => {
        const candidate = error !== null && typeof error === "object" ? error : {};
        return typeof candidate.code === "string" ? candidate.code : "unknown";
      };
      let workspaceId;
      let workspace = { status: "unavailable" };
      try {
        const value = await invoke("ja_runtime_general_workspace", {});
        const candidate = value !== null && typeof value === "object" ? value : {};
        workspaceId = typeof candidate.workspaceId === "string" ? candidate.workspaceId : undefined;
        workspace = {
          status: workspaceId === undefined ? "invalid" : "resolved",
          revision: Number.isSafeInteger(candidate.revision) ? candidate.revision : undefined,
        };
      } catch (error) {
        workspace = { status: "rejected", errorCode: errorCode(error) };
      }
      if (workspaceId === undefined) return { workspace, threadList: { status: "skipped" } };
      try {
        const value = await invoke("ja_thread_list", {
          input: { workspaceId, limit: 5 },
        });
        const candidate = value !== null && typeof value === "object" ? value : {};
        const items = Array.isArray(candidate.items) ? candidate.items : [];
        const first = items[0] !== null && typeof items[0] === "object" ? items[0] : undefined;
        const preferences =
          first?.preferences !== null && typeof first?.preferences === "object"
            ? first.preferences
            : undefined;
        return {
          workspace,
          threadList: {
            status: Array.isArray(candidate.items) ? "resolved" : "invalid",
            itemCount: items.length,
            firstFields: first === undefined ? [] : Object.keys(first).sort(),
            preferenceFields: preferences === undefined ? [] : Object.keys(preferences).sort(),
          },
        };
      } catch (error) {
        return { workspace, threadList: { status: "rejected", errorCode: errorCode(error) } };
      }
    })
    .catch(() => ({ bridge: "unavailable" }));
}

/**
 * 冷启动必须最终出现一个可选择的 durable Thread；历史错误是确定失败，立即附带只读原生
 * 诊断退出，避免旧 selector 或公开错误页继续消耗模型场景预算。
 */
async function waitForInitialThread(page, deadline, signal) {
  const historyDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  const history = page.getByRole("list", { name: "最近对话列表" });
  const rows = history.locator("button[data-thread-id]");
  let diagnostic = await captureInitialHistoryState(page);
  while (Date.now() < historyDeadline) {
    throwIfAborted(signal);
    const [visible, alerts] = await Promise.all([
      rows
        .first()
        .isVisible()
        .catch(() => false),
      history
        .locator('[role="alert"]')
        .allTextContents()
        .catch(() => []),
    ]);
    if (visible) return;
    if (alerts.length > 0) {
      diagnostic = await captureInitialHistoryState(page);
      throw new Error(
        `初始历史会话进入失败状态：${JSON.stringify({ alerts: alerts.slice(0, 4), diagnostic })}`,
      );
    }
    await waitForDelay(250, signal);
  }
  diagnostic = await captureInitialHistoryState(page);
  throw new Error(`初始历史会话未在局部期限内就绪：${JSON.stringify(diagnostic)}`);
}

/**
 * 等待产品拥有的 turn-admission fence；冷启动卡住时同时捕获 DOM 与原生状态。
 * 单独可见的“connected”标签刻意不足以通过，因为它无法证明 active thread、profile
 * 与准确 runtime generation 已共同具备接收 turn 的条件。
 */
async function waitForComposerAdmission(page, deadline) {
  const admissionDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  try {
    await page.waitForFunction(
      () => {
        const input = globalThis.document.querySelector('textarea[aria-label="消息"]');
        const form = globalThis.document.querySelector('form[aria-label="发送消息"]');
        return (
          input instanceof globalThis.HTMLTextAreaElement &&
          input.disabled === false &&
          form?.getAttribute("data-state") === "ready"
        );
      },
      undefined,
      { timeout: Math.max(1, admissionDeadline - Date.now()) },
    );
  } catch (error) {
    const diagnostic = await page.evaluate(async () => {
      const input = globalThis.document.querySelector('textarea[aria-label="消息"]');
      const form = globalThis.document.querySelector('form[aria-label="发送消息"]');
      const context = globalThis.document.querySelector(".ja-composer-context");
      const currentThread = globalThis.document.querySelector(
        '[aria-label="最近对话列表"] button[aria-current="page"]',
      );
      const statusRows = Array.from(
        globalThis.document.querySelectorAll('[role="status"], [role="alert"]'),
      )
        .map((node) => node.textContent?.trim() ?? "")
        .filter(Boolean)
        .slice(-12);
      let nativeState;
      try {
        nativeState = await globalThis.__TAURI_INTERNALS__?.invoke?.("ja_runtime_state");
      } catch (nativeError) {
        nativeState = { error: String(nativeError) };
      }
      const rawEvents = Array.isArray(globalThis.__JA_E2E_TAURI_EVENTS__)
        ? globalThis.__JA_E2E_TAURI_EVENTS__.slice(-16)
        : [];
      return {
        inputDisabled: input instanceof globalThis.HTMLTextAreaElement ? input.disabled : null,
        composerState: form?.getAttribute("data-state") ?? null,
        contextDisabled: context instanceof globalThis.HTMLButtonElement ? context.disabled : null,
        contextText: context?.textContent?.trim() ?? null,
        currentThreadText: currentThread?.textContent?.trim() ?? null,
        statusRows,
        nativeState,
        rawEvents,
      };
    });
    throw new Error(`对话输入 admission 未就绪：${JSON.stringify(diagnostic)}`, { cause: error });
  }
}

/**
 * 证明无项目流程没有静默绑定确定性的 E2E picker。项目入口以当前唯一的左栏“添加项目”
 * 为准；项目列表必须选中显式 general 行，真实项目不得冒充当前范围。
 */
async function assertGeneralConversationScope(page, deadline) {
  const scopeDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  const timeout = () => Math.max(1, scopeDeadline - Date.now());
  await conversationSurface(page).waitFor({ state: "visible", timeout: timeout() });
  await page
    .getByRole("list", { name: "项目列表", exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await page
    .getByRole("button", { name: "添加项目", exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  const selectedGeneral = await page
    .locator('[aria-label="项目列表"] button[data-scope-kind="general"][aria-current="page"]')
    .count();
  const selectedProjects = await page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]')
    .count();
  if (selectedGeneral !== 1 || selectedProjects !== 0)
    throw new Error("无项目对话范围的显式选中状态不闭环");
}

/**
 * 证明当前 Thread 真实绑定项目且 Composer 使用 profile 的逐次审批权限；
 * 审批 fixture 不能在无项目只读线程中伪造 ASK 路径。
 */
async function assertProjectConversationScope(page, deadline) {
  const scopeDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  await page.waitForFunction(
    () => {
      const selectedProject = globalThis.document.querySelector(
        '[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]',
      );
      const composer = globalThis.document.querySelector('textarea[aria-label="消息"]');
      return (
        selectedProject !== null &&
        composer instanceof globalThis.HTMLTextAreaElement &&
        composer.disabled === false
      );
    },
    undefined,
    { timeout: Math.max(1, scopeDeadline - Date.now()) },
  );
  await page
    .getByRole("button", { name: "添加项目", exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, scopeDeadline - Date.now()) });
}

const workbenchTabValues = Object.freeze({
  审查: "review",
  文件: "files",
  终端: "terminal",
  浏览器: "preview",
});

/** 打开真实 plus-tab launcher 并验证五个产品操作，避免绕过公开交互入口。 */
async function openWorkbenchLauncher(page, deadline) {
  const trigger = page.getByRole("button", { name: "新建标签页", exact: true });
  await trigger.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await clickVerifiedControl(page, trigger, deadline);
  const launcher = page.getByRole("region", { name: "新标签页启动器", exact: true });
  await launcher.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const actions = Object.freeze({
    审查: "Ctrl+Shift+G",
    终端: "Ctrl+`",
    浏览器: "Ctrl+T",
    文件: "Ctrl+P",
    侧边聊天: "Ctrl+Alt+S",
  });
  for (const [label, shortcut] of Object.entries(actions)) {
    const action = launcher.locator(".ja-workbench-launcher-action").filter({ hasText: label });
    await action.waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
    await action
      .getByText(shortcut, { exact: true })
      .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  }
  return launcher;
}

/** 选择已有 outer tab，若不存在则通过 plus-tab launcher 创建，确保遵循唯一公开路径。 */
async function chooseWorkbenchTool(page, name, deadline) {
  const value = workbenchTabValues[name];
  if (value === undefined) throw new Error(`未知工作区能力：${name}`);
  const tablist = page.getByRole("tablist", { name: "工作区标签", exact: true });
  await tablist.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const existing = tablist.getByRole("tab", { name, exact: true });
  if ((await existing.count()) === 1) {
    // 五个可关闭 tab 可能溢出到固定 launcher 控件下方。先滚动真实 tab strip，
    // 再保留严格 hit-test 与鼠标点击，避免把被裁切的控件误判为可用。
    await existing.evaluate((tab) => tab.scrollIntoView({ block: "nearest", inline: "nearest" }));
    await clickVerifiedControl(page, existing, deadline);
  } else {
    const launcher = await openWorkbenchLauncher(page, deadline);
    await clickVerifiedControl(
      page,
      launcher.locator(".ja-workbench-launcher-action").filter({ hasText: name }),
      deadline,
    );
  }
  await page.locator(`[data-tab-panel="${value}"]:not([hidden])`).waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
}

/**
 * 参考截图前移除无关 outer tab，使证据使用与 Codex 相同的能力状态，
 * 同时不绕过每个 tab 的真实关闭链路。
 */
async function closeWorkbenchTabsExcept(page, workbench, retainedTabs, deadline) {
  const retained = new Set(retainedTabs);
  const tabs = await workbench
    .locator(".ja-workbench-tab-shell")
    .evaluateAll((elements) =>
      elements
        .map((element) => element.getAttribute("data-tab"))
        .filter((value) => typeof value === "string"),
    );
  for (const tab of tabs) {
    if (retained.has(tab)) continue;
    const shell = workbench.locator(`.ja-workbench-tab-shell[data-tab="${tab}"]`);
    const close = shell.locator('[data-tab-close="true"]');
    await clickVerifiedControl(page, close, deadline);
    await page.waitForFunction(
      (value) =>
        globalThis.document.querySelector(`.ja-workbench-tab-shell[data-tab="${value}"]`) === null,
      tab,
      { timeout: Math.max(1, deadline - Date.now()) },
    );
  }
}

/** 仅在中栏 environment popover 尚不可见时打开，避免重复切换造成状态反转。 */
async function ensureConversationSummaryOpen(page, deadline) {
  const summary = page.getByRole("dialog", { name: "环境信息", exact: true });
  if ((await summary.count()) === 0 || !(await summary.isVisible())) {
    const trigger = page.getByRole("button", { name: "打开对话摘要", exact: true });
    await clickVerifiedControl(page, trigger, deadline);
  }
  await summary.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  return summary;
}

/** 检验 Review mutation 控件前关闭中栏 environment popover，避免浮层遮挡改变命中结果。 */
async function ensureConversationSummaryClosed(page, deadline) {
  const summary = page.getByRole("dialog", { name: "环境信息", exact: true });
  if ((await summary.count()) === 1 && (await summary.isVisible())) {
    const trigger = page.getByRole("button", { name: "打开对话摘要", exact: true });
    await clickVerifiedControl(page, trigger, deadline);
    await summary.waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
  }
}

/**
 * 证明 shell 收起面板时没有销毁已挂载的 Files/terminal controller 及其原生资源 ownership。
 */
async function waitForWorkbenchCollapsed(workbench, deadline, signal) {
  while (Date.now() < deadline) {
    throwIfAborted(signal);
    if ((await workbench.count()) !== 1) {
      throw new Error("右侧栏收起时工作台被卸载，无法保留 Tab 与终端生命周期");
    }
    const state = await workbench.evaluate((element) => {
      let current = element;
      while (current instanceof globalThis.HTMLElement) {
        const style = globalThis.getComputedStyle(current);
        if (
          style.display === "none" ||
          style.visibility === "hidden" ||
          current.getAttribute("aria-hidden") === "true"
        ) {
          return { collapsed: true, reason: "hidden_ancestor" };
        }
        current = current.parentElement;
      }
      const rect = element.getBoundingClientRect();
      return {
        collapsed: rect.width <= 1 || rect.height <= 1,
        reason: "bounds",
        width: rect.width,
        height: rect.height,
      };
    });
    if (state.collapsed) return state;
    await waitForDelay(100, signal);
  }
  throw new Error("工作台未在期限内收起");
}

/**
 * 在单个有界循环中观察 Review 成功面与回退面，使 MergeView 缺失时能报告结构状态，
 * 同时绝不将 diff/editor payload 复制到 E2E 诊断。
 */
async function waitForReviewDiff(review, path, deadline) {
  let lastDiagnostic = {
    state: "not_observed",
    safeVisibleText: "",
    targetRow: null,
    selectedRow: null,
    diffNodes: [],
  };
  while (Date.now() < deadline) {
    let observation;
    try {
      observation = await review.evaluate(
        (reviewElement, expectedPath) => {
          /** 直接检验渲染几何，不依赖 Playwright 的 WebView2 visibility bridge。 */
          const isVisible = (element) => {
            if (!(element instanceof globalThis.HTMLElement)) return false;
            let current = element;
            while (current instanceof globalThis.HTMLElement) {
              const style = globalThis.getComputedStyle(current);
              if (
                current.hidden ||
                current.getAttribute("aria-hidden") === "true" ||
                style.display === "none" ||
                style.visibility === "hidden" ||
                Number.parseFloat(style.opacity) === 0
              ) {
                return false;
              }
              current = current.parentElement;
            }
            const rect = element.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0;
          };
          /** 行证据只保留结构信息；文件名仅用于定位被点击的行，不进入诊断产物。 */
          const describeRow = (element) => {
            if (!(element instanceof globalThis.HTMLElement)) return null;
            const select = element.querySelector(".ja-review-file-select");
            const aria = (node) =>
              node instanceof globalThis.HTMLElement
                ? Object.fromEntries(
                    [...node.attributes]
                      .filter((attribute) => attribute.name.startsWith("aria-"))
                      .slice(0, 12)
                      .map((attribute) => [attribute.name, attribute.value]),
                  )
                : {};
            return {
              class: element.getAttribute("class"),
              dataState: element.getAttribute("data-state"),
              dataSelected: element.getAttribute("data-selected"),
              aria: aria(element),
              select:
                select instanceof globalThis.HTMLElement
                  ? {
                      class: select.getAttribute("class"),
                      dataState: select.getAttribute("data-state"),
                      dataSelected: select.getAttribute("data-selected"),
                      aria: aria(select),
                    }
                  : null,
            };
          };
          const diffRoot = reviewElement.querySelector('.ja-review-diff[aria-label="变更 Diff"]');
          const expectedLabel = `只读 Diff ${expectedPath}`;
          const region =
            diffRoot === null
              ? undefined
              : [...diffRoot.querySelectorAll(".ja-editor-diff[aria-label]")].find(
                  (element) => element.getAttribute("aria-label") === expectedLabel,
                );
          const merge = region?.querySelector(".cm-mergeView");
          const empty = diffRoot?.querySelector(".ja-review-empty");
          const emptyText = empty?.textContent?.replace(/\s+/gu, " ").trim() ?? "";
          const regionVisible = isVisible(region);
          const mergeVisible = isVisible(merge);
          const state =
            regionVisible && mergeVisible
              ? "success"
              : empty?.classList.contains("is-error") === true
                ? "error"
                : emptyText.includes("正在读取")
                  ? "loading"
                  : empty !== null && empty !== undefined
                    ? "unavailable"
                    : regionVisible
                      ? "rendering"
                      : "missing";
          const targetRow = [...reviewElement.querySelectorAll(".ja-review-file-row")].find(
            (element) =>
              element.querySelector(".ja-review-file-name")?.textContent?.trim() === expectedPath,
          );
          const selectedRow = reviewElement.querySelector(
            '.ja-review-file-row[data-selected="true"]',
          );
          const relatedNodes =
            diffRoot === null
              ? []
              : [
                  ...new Set([
                    diffRoot,
                    ...diffRoot.children,
                    ...diffRoot.querySelectorAll(
                      ".ja-review-empty, .ja-editor-diff, .cm-mergeView",
                    ),
                  ]),
                ]
                  .slice(0, 12)
                  .map((element) => ({
                    class: element.getAttribute("class"),
                    role: element.getAttribute("role"),
                    ariaLabel: element.getAttribute("aria-label"),
                  }));
          const clone = reviewElement.cloneNode(true);
          if (!(clone instanceof globalThis.HTMLElement)) {
            throw new Error("Review clone is not an HTMLElement");
          }
          clone
            .querySelectorAll(
              [
                ".ja-editor-diff",
                ".ja-editor-viewer",
                ".ja-code-editor",
                ".cm-editor",
                ".cm-mergeView",
                ".xterm",
                "pre",
                "code",
                "textarea",
                "input",
                "[contenteditable='true']",
                "script",
                "style",
              ].join(", "),
            )
            .forEach((element) => element.remove());
          const safeVisibleText = clone.innerText.replace(/\s+/gu, " ").trim().slice(0, 2_000);
          return {
            state,
            regionFound: region !== undefined,
            regionVisible,
            mergeFound: merge !== null && merge !== undefined,
            mergeVisible,
            safeVisibleText,
            targetRow: describeRow(targetRow),
            selectedRow: describeRow(selectedRow),
            diffNodes: relatedNodes,
          };
        },
        path,
        { timeout: Math.max(1, deadline - Date.now()) },
      );
      const sanitize = (value, limit = 160) =>
        typeof value === "string" ? redact(value).slice(0, limit) : (value ?? null);
      const sanitizeAria = (aria) =>
        Object.fromEntries(
          Object.entries(aria ?? {}).map(([name, value]) => [name, sanitize(value)]),
        );
      const sanitizeRow = (row) =>
        row === null
          ? null
          : {
              class: sanitize(row.class),
              dataState: sanitize(row.dataState),
              dataSelected: sanitize(row.dataSelected),
              aria: sanitizeAria(row.aria),
              select:
                row.select === null
                  ? null
                  : {
                      class: sanitize(row.select.class),
                      dataState: sanitize(row.select.dataState),
                      dataSelected: sanitize(row.select.dataSelected),
                      aria: sanitizeAria(row.select.aria),
                    },
            };
      lastDiagnostic = {
        state: observation.state,
        regionFound: observation.regionFound,
        regionVisible: observation.regionVisible,
        mergeFound: observation.mergeFound,
        mergeVisible: observation.mergeVisible,
        safeVisibleText: redact(observation.safeVisibleText),
        targetRow: sanitizeRow(observation.targetRow),
        selectedRow: sanitizeRow(observation.selectedRow),
        diffNodes: observation.diffNodes.map((node) => ({
          class: sanitize(node.class),
          role: sanitize(node.role),
          ariaLabel: sanitize(node.ariaLabel),
        })),
      };
      if (observation.state === "success") return lastDiagnostic;
    } catch (error) {
      lastDiagnostic = {
        ...lastDiagnostic,
        state: "observation_error",
        observationError: redact(error?.message ?? error),
      };
    }
    await waitForDelay(Math.min(100, Math.max(1, deadline - Date.now())));
  }
  throw new Error(`审查 Diff 未达到可见 MergeView 成功态：${JSON.stringify(lastDiagnostic)}`);
}

/**
 * 选择一个 native-owned source，并等待其权威 snapshot 可见，避免把 UI 乐观状态当作事实。
 */
async function selectReviewSource(review, optionName, deadline) {
  const source = review.getByRole("combobox", { name: "审查来源", exact: true });
  const option = source.getByRole("option", { name: optionName, exact: true });
  await option.waitFor({ state: "attached", timeout: Math.max(1, deadline - Date.now()) });
  const value = await option.getAttribute("value");
  if (value === null) throw new Error(`审查来源缺少 value：${optionName}`);
  await source.selectOption(value);
  const kind = value.split(":", 1)[0];
  await review
    .page()
    .waitForFunction(
      ({ expectedKind }) =>
        globalThis.document
          .querySelector('.ja-review-panel[aria-label="审查"]')
          ?.getAttribute("data-source") === expectedKind,
      { expectedKind: kind },
      { timeout: Math.max(1, deadline - Date.now()) },
    );
  return value;
}

/** 选择 catalog 实际返回的首个 Branch 或 Commit，避免测试根据显示文本伪造 Git identity。 */
async function selectReviewSourceByKind(review, kind, deadline) {
  const source = review.getByRole("combobox", { name: "审查来源", exact: true });
  const options = source.locator(`option[value^="${kind}:"]`);
  await options.first().waitFor({ state: "attached", timeout: Math.max(1, deadline - Date.now()) });
  const value = await options.first().getAttribute("value");
  if (value === null) throw new Error(`审查 catalog 缺少 ${kind} identity`);
  await source.selectOption(value);
  await review
    .page()
    .waitForFunction(
      ({ expectedKind }) =>
        globalThis.document
          .querySelector('.ja-review-panel[aria-label="审查"]')
          ?.getAttribute("data-source") === expectedKind,
      { expectedKind: kind },
      { timeout: Math.max(1, deadline - Date.now()) },
    );
  return value;
}

/**
 * 只通过可见控件检验 Unstaged、Staged、Branch 与 Commit 四类当前来源，
 * 并证明文件级 Stage/Unstage/Revert 确实改变真实 Git 状态。
 */
async function exerciseNativeReviewFlow(
  page,
  workbench,
  deadline,
  workspaceRoot,
  nativeViewportEvidence,
  prepareCapture,
) {
  const required = true;
  const review = workbench.locator('.ja-review-panel[aria-label="审查"]');
  if ((await review.count()) !== 1) {
    const result = {
      status: required ? "blocked" : "skipped",
      reason: "native_review_not_bound",
      source: "rust_review_service",
    };
    if (required) throw new Error(`审查未绑定真实 Rust Review adapter：${JSON.stringify(result)}`);
    return result;
  }
  await review.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await selectReviewSource(review, "未暂存", deadline);
  const resolvedDeadline = Math.min(deadline, Date.now() + 15_000);
  const rows = review.locator(".ja-review-file-row");
  const expectedPath = "sample.ts";
  const fixtureRow = rows.filter({ hasText: expectedPath });
  await waitForCondition(
    "Unstaged fixture 稳定可见",
    async () =>
      (await review.locator(".ja-review-empty.is-loading").count()) === 0 &&
      (await fixtureRow.count()) === 1 &&
      (await fixtureRow.isVisible()),
    resolvedDeadline,
  );
  if ((await rows.count()) === 0) {
    const result = {
      status: required ? "blocked" : "skipped",
      reason: "selected_review_source_has_no_changes",
      source: "rust_review_service",
    };
    if (required) throw new Error(`缺少真实 Review Diff：${JSON.stringify(result)}`);
    return result;
  }
  const row = fixtureRow;
  await row.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const select = row.locator(".ja-review-file-select");
  await clickVerifiedControl(page, select, deadline);
  const path = await row.locator(".ja-review-file-name").innerText();
  const diffDeadline = Math.min(deadline, Date.now() + 15_000);
  try {
    await waitForReviewDiff(review, path, diffDeadline);
  } catch (error) {
    await captureNativeMaximizedVisualEvidence(
      page,
      `implementation-codex-workbench-review-failure-${visualTheme}-native-2560x1392.png`,
      nativeViewportEvidence,
    ).catch(() => undefined);
    throw error;
  }
  await prepareCapture?.();
  await captureNativeMaximizedVisualEvidence(
    page,
    `implementation-codex-workbench-review-${visualTheme}-native-2560x1392.png`,
    nativeViewportEvidence,
  );
  await ensureConversationSummaryClosed(page, deadline);
  const currentRow = () => review.locator(".ja-review-file-row").filter({ hasText: expectedPath });
  await clickVerifiedControl(
    page,
    currentRow().getByRole("button", { name: "暂存文件", exact: true }),
    deadline,
  );
  await review
    .getByText("已暂存。", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await selectReviewSource(review, "已暂存", deadline);
  await currentRow().waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await clickVerifiedControl(
    page,
    currentRow().getByRole("button", { name: "取消暂存文件", exact: true }),
    deadline,
  );
  await review
    .getByText("已取消暂存。", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await selectReviewSource(review, "未暂存", deadline);
  await currentRow().waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const confirmation = new Promise((resolvePromise, rejectPromise) => {
    page.once("dialog", async (dialog) => {
      try {
        const type = dialog.type();
        await dialog.accept();
        resolvePromise(type);
      } catch (error) {
        rejectPromise(error);
      }
    });
  });
  await clickVerifiedControl(
    page,
    currentRow().getByRole("button", { name: "撤销文件", exact: true }),
    deadline,
  );
  if ((await confirmation) !== "confirm") throw new Error("Review Revert 未显示确认对话框");
  await review
    .getByText("已撤销。", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  if (
    (await readFile(join(workspaceRoot, "sample.ts"), "utf8")) !==
    'export const greeting = "hello";\n'
  ) {
    throw new Error("Review Revert 未恢复确定性的 before image");
  }
  const filter = review.getByPlaceholder("筛选文件…", { exact: true });
  await filter.fill(expectedPath);
  await review
    .getByText("没有匹配的文件。", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await filter.fill("");
  await selectReviewSourceByKind(review, "branch", deadline);
  await review
    .locator(".ja-review-file-row, .ja-review-empty")
    .first()
    .waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
  await selectReviewSourceByKind(review, "commit", deadline);
  await review
    .locator(".ja-review-file-row, .ja-review-empty")
    .first()
    .waitFor({
      state: "visible",
      timeout: Math.max(1, deadline - Date.now()),
    });
  return {
    status: "passed",
    source: "rust_review_service",
    path,
    sources: ["unstaged", "staged", "branch", "commit"],
    actions: ["stage_file", "unstage_file", "revert_file"],
    screenshot: visualEvidenceDirectory === undefined ? "not_requested" : "native_2560x1392",
  };
}

/**
 * 只关闭文件夹与本轮随机 workspace 完全匹配的 Explorer 窗口。
 * 这里通过 Windows PowerShell 5.1 使用 Shell.Application，因为它是所有受支持机器上
 * 均可用的操作系统原生 COM host。
 */
async function closeWorkspaceExplorerWindow(workspace, signal) {
  const resolvedWorkspace = resolve(workspace);
  const tempPrefix = `${resolve(tmpdir()).replace(/[\\/]$/u, "")}\\ja-desktop-e2e-`.toLowerCase();
  if (!resolvedWorkspace.toLowerCase().startsWith(tempPrefix)) {
    throw new Error("拒绝关闭非 E2E 工作区的 Explorer 窗口");
  }
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$target = [IO.Path]::GetFullPath([Environment]::GetEnvironmentVariable('JA_E2E_EXPLORER_TARGET'))",
    "$deadline = [DateTime]::UtcNow.AddSeconds(12)",
    "do {",
    "  $shell = New-Object -ComObject Shell.Application",
    "  foreach ($window in @($shell.Windows())) {",
    "    try {",
    "      $candidate = [IO.Path]::GetFullPath([string]$window.Document.Folder.Self.Path)",
    "      if ([string]::Equals($candidate, $target, [StringComparison]::OrdinalIgnoreCase)) { $window.Quit(); Write-Output 'closed'; exit 0 }",
    "    } catch {}",
    "  }",
    "  Start-Sleep -Milliseconds 200",
    "} while ([DateTime]::UtcNow -lt $deadline)",
    "exit 4",
  ].join("\n");
  const { stdout } = await execFileAsync(
    "powershell.exe",
    ["-NoProfile", "-NonInteractive", "-Command", script],
    {
      windowsHide: true,
      timeout: 15_000,
      signal,
      env: { ...process.env, JA_E2E_EXPLORER_TARGET: resolvedWorkspace },
    },
  );
  if (!String(stdout).includes("closed")) throw new Error("未观察到 E2E 工作区 Explorer 窗口");
}

/** 刷新准确的 spawned closure，并返回其中唯一的 Ja 窗口 owner。 */
async function resolveOwnedJaWindow(scope, signal) {
  const snapshot = await processSnapshot(signal);
  const tree = processTree(scope.rootIdentity, snapshot, scope.incompleteObserved);
  if (tree === undefined) throw new Error("无法重验本次 Tauri launcher 的进程树");
  for (const [pid, entry] of tree) scope.observed.set(pid, entry);
  const candidates = [...tree.values()].filter((entry) => entry.name.toLowerCase() === "ja.exe");
  if (candidates.length !== 1)
    throw new Error(`本次 launcher 下 Ja 窗口进程数量异常：${candidates.length}`);
  return candidates[0];
}

/**
 * 只有 PowerShell 重新验证本轮 launcher 下观察到的准确 CIM 名称、command line 与创建时间后，
 * 才执行 Win32 窗口操作。该身份 fence 防止 resize/focus 触碰 PID 106952
 * 或其他恰好同名 ja.exe 的开发者窗口。resize 选择已连接显示器中 DPI 最低者，
 * 使固定 CSS 矩阵在混合 DPI 开发工作站上仍是真实 HWND/WebView 测量。
 */
async function invokeOwnedWindowAction(identity, action, signal, size) {
  const creationMillis = parseWindowsCreationDate(identity?.creationDate);
  if (!hasProcessIdentity(identity) || creationMillis === undefined)
    throw new Error("native 窗口操作缺少完整进程身份");
  if (
    size !== undefined &&
    (!Number.isInteger(size.width) ||
      !Number.isInteger(size.height) ||
      size.width < 320 ||
      size.height < 240 ||
      size.width > 8_192 ||
      size.height > 8_192)
  ) {
    throw new Error("native 窗口尺寸越界");
  }
  const typeDefinition = [
    "using System;",
    "using System.Runtime.InteropServices;",
    "public static class JaE2eUser32 {",
    "  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }",
    "  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }",
    '  [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);',
    '  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);',
    '  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr insertAfter, int x, int y, int width, int height, uint flags);',
    '  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);',
    '  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);',
    '  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();',
    '  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();',
    '  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr processId);',
    '  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint source, uint target, bool attach);',
    '  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);',
    '  [DllImport("user32.dll")] public static extern IntPtr SetFocus(IntPtr hWnd);',
    '  [DllImport("user32.dll")] public static extern IntPtr MonitorFromPoint(POINT point, uint flags);',
    '  [DllImport("shcore.dll")] public static extern int GetDpiForMonitor(IntPtr monitor, int dpiType, out uint dpiX, out uint dpiY);',
    "  public static uint DpiAt(int x, int y) { uint dx = 96, dy = 96; var monitor = MonitorFromPoint(new POINT { X = x, Y = y }, 2); return monitor != IntPtr.Zero && GetDpiForMonitor(monitor, 0, out dx, out dy) == 0 ? dx : 96; }",
    "  public static bool FocusOwned(IntPtr hWnd) { uint current = GetCurrentThreadId(); uint target = GetWindowThreadProcessId(hWnd, IntPtr.Zero); IntPtr foreground = GetForegroundWindow(); uint foregroundThread = foreground == IntPtr.Zero ? 0 : GetWindowThreadProcessId(foreground, IntPtr.Zero); bool targetAttached = current != target && AttachThreadInput(current, target, true); bool foregroundAttached = foregroundThread != 0 && foregroundThread != current && foregroundThread != target && AttachThreadInput(current, foregroundThread, true); try { ShowWindowAsync(hWnd, 9); BringWindowToTop(hWnd); SetForegroundWindow(hWnd); SetFocus(hWnd); return GetForegroundWindow() == hWnd; } finally { if (foregroundAttached) AttachThreadInput(current, foregroundThread, false); if (targetAttached) AttachThreadInput(current, target, false); } }",
    "}",
  ].join(" ");
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$pidValue = [int][Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_PID')",
    "$expectedName = [Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_NAME')",
    "$expectedCommand = [Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_COMMAND')",
    "$expectedMillis = [long][Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_CREATED_MS')",
    '$row = Get-CimInstance Win32_Process -Filter "ProcessId = $pidValue"',
    "if ($null -eq $row) { throw 'owned window process vanished' }",
    "$actualMillis = ([DateTimeOffset]$row.CreationDate).ToUnixTimeMilliseconds()",
    "if (-not [string]::Equals([string]$row.Name, $expectedName, [StringComparison]::Ordinal) -or -not [string]::Equals([string]$row.CommandLine, $expectedCommand, [StringComparison]::Ordinal) -or $actualMillis -ne $expectedMillis) { throw 'owned window identity changed' }",
    `Add-Type -TypeDefinition '${typeDefinition}'`,
    "$process = Get-Process -Id $pidValue -ErrorAction Stop",
    "$deadline = [DateTime]::UtcNow.AddSeconds(5)",
    "do { $process.Refresh(); $handle = $process.MainWindowHandle; if ($handle -ne [IntPtr]::Zero) { break }; Start-Sleep -Milliseconds 50 } while ([DateTime]::UtcNow -lt $deadline)",
    "if ($handle -eq [IntPtr]::Zero) { throw 'owned process has no main window handle' }",
    "$action = [Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_ACTION')",
    "$focused = $false",
    "if ($action -eq 'resize') {",
    "  Add-Type -AssemblyName System.Windows.Forms",
    "  $targetScreen = [System.Windows.Forms.Screen]::AllScreens | Sort-Object { [JaE2eUser32]::DpiAt($_.Bounds.X + [int]($_.Bounds.Width / 2), $_.Bounds.Y + [int]($_.Bounds.Height / 2)) }, { -$_.WorkingArea.X } | Select-Object -First 1",
    "  if ($null -eq $targetScreen) { throw 'no attached monitor' }",
    "  [void][JaE2eUser32]::ShowWindowAsync($handle, 9)",
    "  Start-Sleep -Milliseconds 100",
    "  $width = [int][Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_WIDTH')",
    "  $height = [int][Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_HEIGHT')",
    "  if (-not [JaE2eUser32]::SetWindowPos($handle, [IntPtr]::Zero, $targetScreen.WorkingArea.X, $targetScreen.WorkingArea.Y, $width, $height, 0x0040)) { throw 'SetWindowPos failed' }",
    "} elseif ($action -eq 'maximize') { [void][JaE2eUser32]::ShowWindowAsync($handle, 3) }",
    "elseif ($action -eq 'minimize') { [void][JaE2eUser32]::ShowWindowAsync($handle, 6); do { $minimized = [JaE2eUser32]::IsIconic($handle); if ($minimized) { break }; Start-Sleep -Milliseconds 50 } while ([DateTime]::UtcNow -lt $deadline); if (-not $minimized) { throw 'native window did not minimize' } }",
    "elseif ($action -eq 'focus') { do { $focused = [JaE2eUser32]::FocusOwned($handle); if (-not $focused) { $focused = (New-Object -ComObject WScript.Shell).AppActivate($pidValue) }; $minimized = [JaE2eUser32]::IsIconic($handle); $foreground = [JaE2eUser32]::GetForegroundWindow() -eq $handle; if (-not $minimized -and $foreground) { break }; Start-Sleep -Milliseconds 50 } while ([DateTime]::UtcNow -lt $deadline); if ($minimized -or -not $foreground) { throw 'native window did not restore to foreground' } }",
    "elseif ($action -ne 'inspect') { throw 'unsupported window action' }",
    "Start-Sleep -Milliseconds 100",
    "$minimized = [JaE2eUser32]::IsIconic($handle)",
    "$foreground = [JaE2eUser32]::GetForegroundWindow() -eq $handle",
    "$rect = New-Object JaE2eUser32+RECT",
    "if (-not [JaE2eUser32]::GetWindowRect($handle, [ref]$rect)) { throw 'GetWindowRect failed' }",
    "[pscustomobject]@{ pid = $pidValue; action = $action; left = $rect.Left; top = $rect.Top; width = $rect.Right - $rect.Left; height = $rect.Bottom - $rect.Top; focused = [bool]$focused; minimized = [bool]$minimized; foreground = [bool]$foreground } | ConvertTo-Json -Compress",
  ].join("\n");
  const env = {
    ...process.env,
    JA_E2E_WINDOW_PID: String(identity.pid),
    JA_E2E_WINDOW_NAME: identity.name,
    JA_E2E_WINDOW_COMMAND: identity.commandLine,
    JA_E2E_WINDOW_CREATED_MS: String(creationMillis),
    JA_E2E_WINDOW_ACTION: action,
    JA_E2E_WINDOW_WIDTH: String(size?.width ?? 0),
    JA_E2E_WINDOW_HEIGHT: String(size?.height ?? 0),
  };
  const { stdout } = await execFileAsync(
    "powershell.exe",
    ["-NoProfile", "-NonInteractive", "-Command", script],
    {
      windowsHide: true,
      timeout: 10_000,
      maxBuffer: 512 * 1024,
      signal,
      env,
    },
  );
  const lines = String(stdout).trim().split(/\r?\n/u).filter(Boolean);
  const payload = lines.at(-1);
  if (payload === undefined) throw new Error(`native 窗口 ${action} 未返回证据`);
  return JSON.parse(payload);
}

const nativeTextInputMaxLength = 128;

/** 固定 native input 七种形状，禁止自由脚本、越界虚拟键、坐标或文本进入 PowerShell。 */
function normalizeOwnedNativeInput(operation) {
  const allowedModifiers = new Set([0x10, 0x11, 0x12, 0x5b, 0x5c, 0xa4, 0xa5]);
  if (operation?.kind === "reset_modifiers") return { kind: "reset_modifiers" };
  if (operation?.kind === "chord") {
    const modifiers = Array.isArray(operation.modifiers) ? [...operation.modifiers] : [];
    if (
      !Number.isInteger(operation.key) ||
      operation.key < 1 ||
      operation.key > 0xfe ||
      !Number.isInteger(operation.repetitions) ||
      operation.repetitions < 1 ||
      operation.repetitions > 8 ||
      modifiers.length > 4 ||
      new Set(modifiers).size !== modifiers.length ||
      modifiers.some((key) => !allowedModifiers.has(key))
    )
      throw new Error("native chord 输入越界");
    return { kind: "chord", key: operation.key, repetitions: operation.repetitions, modifiers };
  }
  if (operation?.kind === "text" || operation?.kind === "terminal_text") {
    const containsControlCharacter =
      typeof operation.text === "string" &&
      [...operation.text].some((character) => {
        const codePoint = character.codePointAt(0);
        return codePoint !== undefined && (codePoint <= 0x1f || codePoint === 0x7f);
      });
    if (
      typeof operation.text !== "string" ||
      operation.text.length < 1 ||
      operation.text.length > nativeTextInputMaxLength ||
      containsControlCharacter
    )
      throw new Error("native text 输入越界");
    return { kind: operation.kind, text: operation.text };
  }
  if (
    operation?.kind === "move" ||
    operation?.kind === "click" ||
    operation?.kind === "click_chord"
  ) {
    const values = [operation.x, operation.y, operation.rendererWidth, operation.rendererHeight];
    if (
      values.some((value) => !Number.isFinite(value)) ||
      operation.rendererWidth < 1 ||
      operation.rendererHeight < 1 ||
      operation.x < 0 ||
      operation.y < 0 ||
      operation.x >= operation.rendererWidth ||
      operation.y >= operation.rendererHeight
    )
      throw new Error("native click 输入越界");
    if (operation.kind === "move")
      return {
        kind: "move",
        x: operation.x,
        y: operation.y,
        rendererWidth: operation.rendererWidth,
        rendererHeight: operation.rendererHeight,
      };
    if (operation.kind === "click_chord") {
      if (
        !Array.isArray(operation.modifiers) ||
        operation.modifiers.length !== 1 ||
        operation.modifiers[0] !== 0x11
      )
        throw new Error("native modified click 只允许 Ctrl");
      return {
        kind: "click_chord",
        modifiers: [0x11],
        x: operation.x,
        y: operation.y,
        rendererWidth: operation.rendererWidth,
        rendererHeight: operation.rendererHeight,
      };
    }
    return {
      kind: "click",
      x: operation.x,
      y: operation.y,
      rendererWidth: operation.rendererWidth,
      rendererHeight: operation.rendererHeight,
      requireDownstreamFocusProof: operation.requireDownstreamFocusProof === true,
    };
  }
  throw new Error("不支持的 native input 类型");
}

/**
 * 生成固定 Win32 P/Invoke；键盘与鼠标按钮都用 `SendInput`，客户区点击用
 * DPI-safe 坐标。显式 modifier reset 只发送 key-up，用于隔离先前 CDP 或
 * SendInput 动作遗留的逻辑按键状态；输入后的 foreground 只做有界观察，
 * 容忍 child HWND 焦点切换产生的瞬时空 handle，但绝不主动抢回或接受其它进程。
 */
function buildOwnedNativeInputScript() {
  return [
    "$ErrorActionPreference = 'Stop'",
    "$pidValue = [int][Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_PID')",
    "$expectedName = [Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_NAME')",
    "$expectedCommand = [Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_COMMAND')",
    "$expectedMillis = [long][Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_CREATED_MS')",
    "$operation = [Environment]::GetEnvironmentVariable('JA_E2E_NATIVE_INPUT') | ConvertFrom-Json",
    '$row = Get-CimInstance Win32_Process -Filter "ProcessId = $pidValue"',
    "if ($null -eq $row) { throw 'owned window process vanished' }",
    "$actualMillis = ([DateTimeOffset]$row.CreationDate).ToUnixTimeMilliseconds()",
    "if (-not [string]::Equals([string]$row.Name, $expectedName, [StringComparison]::Ordinal) -or -not [string]::Equals([string]$row.CommandLine, $expectedCommand, [StringComparison]::Ordinal) -or $actualMillis -ne $expectedMillis) { throw 'owned window identity changed' }",
    "Add-Type -TypeDefinition @'",
    "using System; using System.Collections.Generic; using System.ComponentModel; using System.Runtime.InteropServices;",
    "public static class JaE2eNativeInput {",
    "[StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }",
    "[StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }",
    "[StructLayout(LayoutKind.Sequential)] public struct MI { public int dx; public int dy; public uint data; public uint flags; public uint time; public UIntPtr extra; }",
    "[StructLayout(LayoutKind.Sequential)] public struct KI { public ushort vk; public ushort scan; public uint flags; public uint time; public UIntPtr extra; }",
    "[StructLayout(LayoutKind.Explicit)] public struct U { [FieldOffset(0)] public MI mouse; [FieldOffset(0)] public KI key; }",
    "[StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public U value; }",
    '[DllImport("user32.dll", SetLastError=true)] public static extern uint SendInput(uint count, INPUT[] inputs, int size);',
    '[DllImport("user32.dll", SetLastError=true)] public static extern bool SetCursorPos(int x,int y);',
    '[DllImport("user32.dll", SetLastError=true)] public static extern bool GetClientRect(IntPtr h,out RECT r);',
    '[DllImport("user32.dll", SetLastError=true)] public static extern bool ClientToScreen(IntPtr h,ref POINT p);',
    '[DllImport("user32.dll", SetLastError=true)] public static extern bool GetWindowRect(IntPtr h,out RECT r);',
    '[DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr h,int c);',
    '[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);',
    '[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();',
    '[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);',
    '[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,IntPtr p);',
    '[DllImport("user32.dll",EntryPoint="GetWindowThreadProcessId")] public static extern uint GetWindowThreadProcessIdWithPid(IntPtr h,out uint p);',
    '[DllImport("user32.dll")] public static extern IntPtr GetKeyboardLayout(uint t);',
    '[DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern short VkKeyScanEx(char c,IntPtr l);',
    "static INPUT K(ushort k,uint f){if(k==0xA5||k==0x5B||k==0x5C)f|=1;return new INPUT{type=1,value=new U{key=new KI{vk=k,flags=f}}};}",
    "static INPUT W(char c,uint f){return new INPUT{type=1,value=new U{key=new KI{scan=c,flags=f|4}}};}",
    "static INPUT M(uint f){return new INPUT{type=0,value=new U{mouse=new MI{flags=f}}};}",
    "static void S(List<INPUT> v){var a=v.ToArray();if(SendInput((uint)a.Length,a,Marshal.SizeOf(typeof(INPUT)))!=(uint)a.Length)throw new Win32Exception(Marshal.GetLastWin32Error());}",
    "public static void ResetModifiers(){var v=new List<INPUT>();foreach(var k in new ushort[]{0x10,0x11,0x12,0x5B,0x5C,0xA0,0xA1,0xA2,0xA3,0xA4,0xA5})v.Add(K(k,2));S(v);}",
    "public static void Chord(ushort[] m,ushort k,int n){var v=new List<INPUT>();foreach(var x in m)v.Add(K(x,0));for(int i=0;i<n;i++)v.Add(K(k,0));v.Add(K(k,2));for(int i=m.Length-1;i>=0;i--)v.Add(K(m[i],2));S(v);}",
    "public static void Text(IntPtr h,string t){var l=GetKeyboardLayout(GetWindowThreadProcessId(h,IntPtr.Zero));foreach(char c in t){short m=VkKeyScanEx(c,l);var v=new List<INPUT>();if(m==-1){v.Add(W(c,0));v.Add(W(c,2));}else{ushort k=(ushort)(m&255);int s=(m>>8)&255;if((s&1)!=0)v.Add(K(0x10,0));if((s&2)!=0)v.Add(K(0x11,0));if((s&4)!=0)v.Add(K(0x12,0));v.Add(K(k,0));v.Add(K(k,2));if((s&4)!=0)v.Add(K(0x12,2));if((s&2)!=0)v.Add(K(0x11,2));if((s&1)!=0)v.Add(K(0x10,2));}S(v);}}",
    "public static void TerminalText(string t){foreach(char c in t){S(new List<INPUT>{W(c,0),W(c,2)});System.Threading.Thread.Sleep(1);}}",
    "static POINT P(IntPtr h,double x,double y,double rw,double rh){RECT r;POINT p=new POINT();if(!GetClientRect(h,out r)||!ClientToScreen(h,ref p))throw new Win32Exception(Marshal.GetLastWin32Error());int w=r.Right-r.Left,q=r.Bottom-r.Top;if(w<1||q<1)throw new InvalidOperationException();p.X+=Math.Min(w-1,Math.Max(0,(int)Math.Round(x*w/rw)));p.Y+=Math.Min(q-1,Math.Max(0,(int)Math.Round(y*q/rh)));return p;}",
    "public static void Move(IntPtr h,double x,double y,double rw,double rh){var p=P(h,x,y,rw,rh);if(!SetCursorPos(p.X,p.Y))throw new Win32Exception(Marshal.GetLastWin32Error());}",
    "public static void Click(IntPtr h,double x,double y,double rw,double rh){var p=P(h,x,y,rw,rh);if(!SetCursorPos(p.X,p.Y))throw new Win32Exception(Marshal.GetLastWin32Error());S(new List<INPUT>{M(2),M(4)});}",
    "public static void ChordClick(IntPtr h,ushort[] m,double x,double y,double rw,double rh){var p=P(h,x,y,rw,rh);if(!SetCursorPos(p.X,p.Y))throw new Win32Exception(Marshal.GetLastWin32Error());var v=new List<INPUT>();foreach(var k in m)v.Add(K(k,0));v.Add(M(2));v.Add(M(4));for(int i=m.Length-1;i>=0;i--)v.Add(K(m[i],2));S(v);}",
    "public static RECT Rect(IntPtr h){RECT r;if(!GetWindowRect(h,out r))throw new Win32Exception(Marshal.GetLastWin32Error());return r;}",
    "}",
    "'@",
    "$process = Get-Process -Id $pidValue -ErrorAction Stop; $deadline = [DateTime]::UtcNow.AddSeconds(5)",
    "do { $process.Refresh(); $handle = $process.MainWindowHandle; if ($handle -ne [IntPtr]::Zero) { break }; Start-Sleep -Milliseconds 50 } while ([DateTime]::UtcNow -lt $deadline)",
    "if ($handle -eq [IntPtr]::Zero) { throw 'owned process has no main window handle' }",
    "if ([JaE2eNativeInput]::GetForegroundWindow() -ne $handle) { [void][JaE2eNativeInput]::ShowWindowAsync($handle,9); do { $focused=[JaE2eNativeInput]::SetForegroundWindow($handle); if(-not $focused){$focused=(New-Object -ComObject WScript.Shell).AppActivate($pidValue)}; if ([JaE2eNativeInput]::GetForegroundWindow() -eq $handle) { break }; Start-Sleep -Milliseconds 50 } while ([DateTime]::UtcNow -lt $deadline) }",
    "if ([JaE2eNativeInput]::GetForegroundWindow() -ne $handle -or [JaE2eNativeInput]::IsIconic($handle)) { throw 'owned window is not foreground' }",
    "if($operation.kind -eq 'reset_modifiers'){[JaE2eNativeInput]::ResetModifiers()}elseif($operation.kind -eq 'chord'){[uint16[]]$m=@($operation.modifiers|ForEach-Object{[uint16]$_});[JaE2eNativeInput]::Chord($m,[uint16]$operation.key,[int]$operation.repetitions)}elseif($operation.kind -eq 'text'){[JaE2eNativeInput]::Text($handle,[string]$operation.text)}elseif($operation.kind -eq 'terminal_text'){[JaE2eNativeInput]::TerminalText([string]$operation.text)}elseif($operation.kind -eq 'move'){[JaE2eNativeInput]::Move($handle,[double]$operation.x,[double]$operation.y,[double]$operation.rendererWidth,[double]$operation.rendererHeight)}elseif($operation.kind -eq 'click'){[JaE2eNativeInput]::Click($handle,[double]$operation.x,[double]$operation.y,[double]$operation.rendererWidth,[double]$operation.rendererHeight)}elseif($operation.kind -eq 'click_chord'){[uint16[]]$m=@($operation.modifiers|ForEach-Object{[uint16]$_});[JaE2eNativeInput]::ChordClick($handle,$m,[double]$operation.x,[double]$operation.y,[double]$operation.rendererWidth,[double]$operation.rendererHeight)}else{throw 'unsupported input'}",
    "$rect=[JaE2eNativeInput]::Rect($handle);$foregroundDeadline=[DateTime]::UtcNow.AddMilliseconds(750);do{$foregroundHandle=[JaE2eNativeInput]::GetForegroundWindow();[uint32]$foregroundPid=0;if($foregroundHandle -ne [IntPtr]::Zero){[void][JaE2eNativeInput]::GetWindowThreadProcessIdWithPid($foregroundHandle,[ref]$foregroundPid)};if($foregroundHandle -eq $handle){break};Start-Sleep -Milliseconds 25}while([DateTime]::UtcNow -lt $foregroundDeadline);[pscustomobject]@{pid=$pidValue;kind=[string]$operation.kind;foreground=$foregroundHandle -eq $handle;foregroundPid=$foregroundPid;foregroundHandle=$foregroundHandle.ToInt64();ownedHandle=$handle.ToInt64();left=$rect.Left;top=$rect.Top;width=$rect.Right-$rect.Left;height=$rect.Bottom-$rect.Top}|ConvertTo-Json -Compress",
  ].join("\n");
}

/**
 * 每次输入前复核完整进程 identity，再由隐藏 PowerShell 7 调用 Win32。只有
 * 专用于建立焦点的纯 click 可把瞬时空 foreground 交给随后的 DOM/child 焦点
 * 断言裁决；文本、按键、URL 点击和任何其它进程前台仍必须立即失败。
 */
async function invokeOwnedNativeInput(identity, operation, signal) {
  const creationMillis = parseWindowsCreationDate(identity?.creationDate);
  if (!hasProcessIdentity(identity) || creationMillis === undefined)
    throw new Error("native input 缺少完整进程身份");
  const normalized = normalizeOwnedNativeInput(operation);
  let stdout;
  try {
    ({ stdout } = await execFileAsync(
      nativeInputPowerShell,
      ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", buildOwnedNativeInputScript()],
      {
        windowsHide: true,
        timeout: 20_000,
        maxBuffer: 512 * 1024,
        signal,
        env: {
          ...process.env,
          JA_E2E_WINDOW_PID: String(identity.pid),
          JA_E2E_WINDOW_NAME: identity.name,
          JA_E2E_WINDOW_COMMAND: identity.commandLine,
          JA_E2E_WINDOW_CREATED_MS: String(creationMillis),
          JA_E2E_NATIVE_INPUT: JSON.stringify(normalized),
        },
      },
    ));
  } catch (error) {
    const output = String(error?.stdout ?? "")
      .trim()
      .split(/\r?\n/u)
      .filter(Boolean)
      .at(-1);
    let evidence;
    try {
      evidence = output === undefined ? undefined : JSON.parse(output);
    } catch {
      evidence = undefined;
    }
    throw new Error(
      `Win32 SendInput helper 失败：${JSON.stringify({ code: error?.code ?? null, signal: error?.signal ?? null, evidence })}`,
    );
  }
  const payload = String(stdout).trim().split(/\r?\n/u).filter(Boolean).at(-1);
  if (payload === undefined) throw new Error("Win32 SendInput 未返回证据");
  const evidence = JSON.parse(payload);
  const downstreamFocusProofRequired =
    normalized.kind === "click" &&
    normalized.requireDownstreamFocusProof === true &&
    evidence.foreground === false &&
    evidence.foregroundHandle === 0 &&
    evidence.foregroundPid === 0;
  if (
    evidence.pid !== identity.pid ||
    evidence.kind !== normalized.kind ||
    (evidence.foreground !== true && !downstreamFocusProofRequired)
  ) {
    throw new Error(`Win32 SendInput 身份或前台证据无效：${JSON.stringify(evidence)}`);
  }
  return { ...evidence, downstreamFocusProofRequired };
}

/**
 * 只为本轮 Ja 进程拥有且已经成为前台的 `#32770` 打开对话框输入私有 fixture 路径。
 * 路径先经过 containment 与普通文件检查；Win32 Unicode `SendInput` 避免污染系统剪贴板，
 * 返回证据也刻意不包含路径或文件内容。
 */
async function completeOwnedFileDialog(identity, ownedRoot, filePath, signal) {
  const creationMillis = parseWindowsCreationDate(identity?.creationDate);
  if (!hasProcessIdentity(identity) || creationMillis === undefined) {
    throw new Error("原生文件选择缺少完整 Ja 进程身份");
  }
  const root = resolve(ownedRoot);
  const candidate = resolve(filePath);
  const relativePath = relative(root, candidate);
  if (
    relativePath === "" ||
    relativePath === ".." ||
    relativePath.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`) ||
    isAbsolute(relativePath)
  ) {
    throw new Error("原生文件选择 fixture 越出本轮私有目录");
  }
  const metadata = await stat(candidate);
  if (!metadata.isFile()) throw new Error("原生文件选择 fixture 不是普通文件");
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "$pidValue = [int][Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_PID')",
    "$expectedName = [Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_NAME')",
    "$expectedCommand = [Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_COMMAND')",
    "$expectedMillis = [long][Environment]::GetEnvironmentVariable('JA_E2E_WINDOW_CREATED_MS')",
    "$filePath = [Environment]::GetEnvironmentVariable('JA_E2E_DIALOG_FILE')",
    '$row = Get-CimInstance Win32_Process -Filter "ProcessId = $pidValue"',
    "if ($null -eq $row) { throw 'owned dialog process vanished' }",
    "$actualMillis = ([DateTimeOffset]$row.CreationDate).ToUnixTimeMilliseconds()",
    "if (-not [string]::Equals([string]$row.Name,$expectedName,[StringComparison]::Ordinal) -or -not [string]::Equals([string]$row.CommandLine,$expectedCommand,[StringComparison]::Ordinal) -or $actualMillis -ne $expectedMillis) { throw 'owned dialog identity changed' }",
    "Add-Type -TypeDefinition @'",
    "using System; using System.Collections.Generic; using System.ComponentModel; using System.Runtime.InteropServices; using System.Text;",
    "public static class JaE2eFileDialog {",
    "public delegate bool EnumWindowsProc(IntPtr h,IntPtr l);",
    "[StructLayout(LayoutKind.Sequential)] public struct KI { public ushort vk; public ushort scan; public uint flags; public uint time; public UIntPtr extra; }",
    "[StructLayout(LayoutKind.Explicit)] public struct U { [FieldOffset(0)] public KI key; }",
    "[StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public U value; }",
    '[DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc p,IntPtr l);',
    '[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);',
    '[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();',
    '[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);',
    '[DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h,StringBuilder s,int n);',
    '[DllImport("user32.dll",SetLastError=true)] public static extern uint SendInput(uint count,INPUT[] inputs,int size);',
    "static INPUT K(ushort k,uint f){return new INPUT{type=1,value=new U{key=new KI{vk=k,flags=f}}};}",
    "static INPUT W(char c,uint f){return new INPUT{type=1,value=new U{key=new KI{scan=c,flags=f|4}}};}",
    "static void S(List<INPUT> v){var a=v.ToArray();if(SendInput((uint)a.Length,a,Marshal.SizeOf(typeof(INPUT)))!=(uint)a.Length)throw new Win32Exception(Marshal.GetLastWin32Error());}",
    "public static string Class(IntPtr h){var s=new StringBuilder(256);return GetClassName(h,s,s.Capacity)>0?s.ToString():string.Empty;}",
    'public static IntPtr Find(uint pid){IntPtr found=IntPtr.Zero;EnumWindows((h,l)=>{uint owner;GetWindowThreadProcessId(h,out owner);if(owner==pid&&IsWindowVisible(h)&&Class(h)=="#32770"){found=h;return false;}return true;},IntPtr.Zero);return found;}',
    "public static void Choose(string path){S(new List<INPUT>{K(0x11,0),K(0x4C,0),K(0x4C,2),K(0x11,2)});System.Threading.Thread.Sleep(80);foreach(char c in path)S(new List<INPUT>{W(c,0),W(c,2)});S(new List<INPUT>{K(0x0D,0),K(0x0D,2)});}",
    "}",
    "'@",
    "$deadline=[DateTime]::UtcNow.AddSeconds(10);$dialog=[IntPtr]::Zero",
    "do{$dialog=[JaE2eFileDialog]::Find([uint32]$pidValue);if($dialog -ne [IntPtr]::Zero){break};Start-Sleep -Milliseconds 50}while([DateTime]::UtcNow -lt $deadline)",
    "if($dialog -eq [IntPtr]::Zero){throw 'owned common file dialog not found'}",
    // Dialog 已创建不代表 foreground 已由 Windows 完成切换；只等待产品自然取得前台，
    // 不调用 SetForegroundWindow 把后台或错误 owner 的窗口伪造成真实用户路径。
    "$foregroundDeadline=[DateTime]::UtcNow.AddSeconds(3);$foreground=[IntPtr]::Zero",
    "do{$foreground=[JaE2eFileDialog]::GetForegroundWindow();if($foreground -eq $dialog){break};Start-Sleep -Milliseconds 50}while([DateTime]::UtcNow -lt $foregroundDeadline)",
    "$foregroundPid=[uint32]0;[JaE2eFileDialog]::GetWindowThreadProcessId($foreground,[ref]$foregroundPid)|Out-Null",
    'if($foreground -ne $dialog){throw "owned common file dialog is not foreground; dialog=$dialog foreground=$foreground foregroundPid=$foregroundPid foregroundClass=$([JaE2eFileDialog]::Class($foreground))"}',
    "[JaE2eFileDialog]::Choose($filePath)",
    "$closeDeadline=[DateTime]::UtcNow.AddSeconds(10);do{if([JaE2eFileDialog]::Find([uint32]$pidValue) -eq [IntPtr]::Zero){break};Start-Sleep -Milliseconds 50}while([DateTime]::UtcNow -lt $closeDeadline)",
    "$closed=[JaE2eFileDialog]::Find([uint32]$pidValue) -eq [IntPtr]::Zero",
    "if(-not $closed){throw 'owned common file dialog did not close'}",
    "[pscustomobject]@{pid=$pidValue;className='#32770';foreground=$true;closed=$closed}|ConvertTo-Json -Compress",
  ].join("\n");
  let stdout;
  try {
    ({ stdout } = await execFileAsync(
      nativeInputPowerShell,
      ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script],
      {
        windowsHide: true,
        timeout: 25_000,
        maxBuffer: 512 * 1024,
        signal,
        env: {
          ...process.env,
          JA_E2E_WINDOW_PID: String(identity.pid),
          JA_E2E_WINDOW_NAME: identity.name,
          JA_E2E_WINDOW_COMMAND: identity.commandLine,
          JA_E2E_WINDOW_CREATED_MS: String(creationMillis),
          JA_E2E_DIALOG_FILE: candidate,
        },
      },
    ));
  } catch (error) {
    // 不回显包含私有 fixture 路径的完整命令；只保留有界 stderr/stdout，供 owner、前台和
    // 对话框生命周期诊断，路径仍由运行摘要的统一脱敏边界处理。
    const stderrTail = String(error?.stderr ?? "")
      .trim()
      .replace(/\s+/gu, " ")
      .slice(-2_048);
    const stdoutTail = String(error?.stdout ?? "")
      .trim()
      .replace(/\s+/gu, " ")
      .slice(-512);
    throw new Error(
      `原生文件选择失败：${JSON.stringify({ code: error?.code ?? null, signal: error?.signal ?? null, stderr: redact(stderrTail), stdout: redact(stdoutTail) })}`,
      { cause: error },
    );
  }
  const payload = String(stdout).trim().split(/\r?\n/u).filter(Boolean).at(-1);
  const evidence = payload === undefined ? undefined : JSON.parse(payload);
  if (
    evidence?.pid !== identity.pid ||
    evidence?.className !== "#32770" ||
    evidence?.foreground !== true ||
    evidence?.closed !== true
  ) {
    throw new Error("原生文件选择未返回有效的 owner/前台/关闭证据");
  }
  return evidence;
}

/**
 * 通过 Composer 的真实用户入口覆盖 import -> discard -> re-import；每次 picker 都由 Ja 所有的
 * 原生对话框完成，最终只把 App Server 签发的 attachment identity 留在草稿中供 Turn 绑定。
 */
async function exerciseAttachmentDraft(page, nativeScope, directories, deadline, signal) {
  const timeout = () => Math.max(1, deadline - Date.now());
  const filePath = join(directories.workspace, attachmentFixtureFile);
  const ownedWindow = await resolveOwnedJaWindow(nativeScope, signal);
  const add = page.getByRole("button", { name: "添加附件", exact: true });
  const pending = page.getByRole("list", { name: "待发送附件", exact: true });
  const importOnce = async () => {
    const invokeCountBefore = await tauriInvokeCount(page, "ja_attachment_import");
    await add.waitFor({ state: "visible", timeout: timeout() });
    const [nativeDialog] = await Promise.all([
      completeOwnedFileDialog(ownedWindow, directories.root, filePath, signal),
      clickVerifiedControl(page, add, deadline),
    ]);
    await pending.waitFor({ state: "visible", timeout: timeout() });
    await pending
      .getByText(attachmentFixtureFile, { exact: true })
      .waitFor({ state: "visible", timeout: timeout() });
    await waitForCondition(
      "attachment import invoke ACK",
      async () => (await tauriInvokeCount(page, "ja_attachment_import")) > invokeCountBefore,
      deadline,
      signal,
    );
    return nativeDialog;
  };
  const firstDialog = await importOnce();
  const discardCountBefore = await tauriInvokeCount(page, "ja_attachment_discard");
  await clickVerifiedControl(
    page,
    page.getByRole("button", { name: `移除附件 ${attachmentFixtureFile}`, exact: true }),
    deadline,
  );
  await pending.waitFor({ state: "detached", timeout: timeout() });
  await waitForCondition(
    "attachment discard invoke ACK",
    async () => (await tauriInvokeCount(page, "ja_attachment_discard")) > discardCountBefore,
    deadline,
    signal,
  );
  const secondDialog = await importOnce();
  return {
    status: "ready_for_turn",
    fileName: attachmentFixtureFile,
    nativeDialog: {
      className: secondDialog.className,
      foreground: secondDialog.foreground,
      closed: secondDialog.closed,
    },
    importedTwice: firstDialog.closed === true && secondDialog.closed === true,
    discardedOnce: true,
  };
}

/** 展开已完成 Turn 的工作过程后核对附件标题，避免 hidden DOM 被误当成用户可见历史。 */
async function assertAttachmentHistoryVisible(turnRow, fileName, deadline) {
  const process = turnRow.locator(".ja-work-process");
  await process.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const attachment = process.getByText(fileName, { exact: true });
  if (!(await attachment.isVisible())) {
    await process.locator(".ja-work-process__trigger").click();
  }
  await attachment.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
}

/** 将 renderer 尺寸与 Win32 外框分开读取，避免混淆客户区与窗口边界。 */
async function readRendererWindowMetrics(page) {
  return page.evaluate(() => ({
    innerWidth: globalThis.innerWidth,
    innerHeight: globalThis.innerHeight,
    outerWidth: globalThis.outerWidth,
    outerHeight: globalThis.outerHeight,
    devicePixelRatio: globalThis.devicePixelRatio,
    maximized:
      globalThis.document.querySelector(".ja-titlebar")?.getAttribute("data-window-maximized") ===
      "true",
  }));
}

/**
 * 迭代调整 owned HWND，直到实时 WebView 客户区达到请求的 viewport。
 * 即使 DPR 为 1.5，该 per-monitor-aware WebView2 也以相同逻辑单位暴露 Win32 rect
 * 与 renderer 客户区几何；DPR 只作为密度证据，不得重复应用到尺寸差值。
 */
async function resizeOwnedNativeViewport(page, identity, viewport, deadline, signal) {
  let renderer = await readRendererWindowMetrics(page);
  let native = await invokeOwnedWindowAction(identity, "inspect", signal);
  let targetWidth = Math.round(viewport.width + Math.max(0, native.width - renderer.innerWidth));
  let targetHeight = Math.round(
    viewport.height + Math.max(0, native.height - renderer.innerHeight),
  );
  for (let attempt = 0; attempt < 4; attempt += 1) {
    native = await invokeOwnedWindowAction(identity, "resize", signal, {
      width: targetWidth,
      height: targetHeight,
    });
    try {
      await page.waitForFunction(
        (expected) =>
          globalThis.innerWidth === expected.width && globalThis.innerHeight === expected.height,
        viewport,
        { timeout: Math.min(4_000, Math.max(1, deadline - Date.now())) },
      );
    } catch {
      // 下一轮校正使用该真实 HWND 的实测客户区差值，避免按理论边框猜测。
    }
    renderer = await readRendererWindowMetrics(page);
    if (renderer.innerWidth === viewport.width && renderer.innerHeight === viewport.height) {
      return {
        status: "passed",
        surface: "native_window",
        nativeWindow: true,
        requested: viewport,
        hwnd: native,
        ...renderer,
      };
    }
    targetWidth += Math.round(viewport.width - renderer.innerWidth);
    targetHeight += Math.round(viewport.height - renderer.innerHeight);
  }
  throw new Error(
    `native 窗口无法达到 ${viewport.width}x${viewport.height}：${JSON.stringify({ renderer, native })}`,
  );
}

/** 最大化 owned HWND，并等待 React 投影原生 resize，确保 UI 已消费真实窗口状态。 */
async function maximizeOwnedNativeWindow(page, identity, deadline, signal) {
  const native = await invokeOwnedWindowAction(identity, "maximize", signal);
  await page.waitForFunction(
    () =>
      globalThis.document.querySelector(".ja-titlebar")?.getAttribute("data-window-maximized") ===
      "true",
    undefined,
    { timeout: Math.min(5_000, Math.max(1, deadline - Date.now())) },
  );
  return { native, ...(await readRendererWindowMetrics(page)) };
}

/**
 * 把 DOM 命中点转换为客户区坐标，并用 Win32 mouse `SendInput` 建立真实焦点。
 * 通知窗口可能在点击后的 750ms 证据窗口内瞬时抢占前台，因此只对“完整 identity
 * 仍有效但前台已转移”的纯聚焦点击做八次有界重试；Codex 等测试控制器可能在
 * 读取 runner 输出时连续激活自身窗口，最终仍必须由 Ja HWND 与 DOM 焦点共同证明成功。
 * 进程身份、helper 或输入错误
 * 继续立即失败。调用方随后仍必须证明 main/child/xterm 的真实焦点，重试本身不算成功。
 */
async function focusOwnedRendererTarget(page, identity, locator, label, deadline, signal) {
  const point = await waitForRenderedSurface(locator, label, deadline, signal);
  const renderer = await page.evaluate(() => ({
    width: globalThis.innerWidth,
    height: globalThis.innerHeight,
  }));
  for (let attempt = 1; attempt <= 8; attempt += 1) {
    try {
      await invokeOwnedNativeInput(
        identity,
        {
          kind: "click",
          x: point.x,
          y: point.y,
          rendererWidth: renderer.width,
          rendererHeight: renderer.height,
          requireDownstreamFocusProof: true,
        },
        signal,
      );
      return { point, renderer, attempts: attempt };
    } catch (error) {
      const message = String(error?.message ?? error);
      const foregroundWasStolen =
        message.startsWith("Win32 SendInput 身份或前台证据无效：") &&
        message.includes('"foreground":false') &&
        message.includes('"ownedHandle":');
      if (!foregroundWasStolen || attempt >= 8 || Date.now() >= deadline) throw error;
    }
  }
  throw new Error(`${label} 未能建立 native 焦点`);
}

/** 只读定位独立 Preview child target；不调用它的 CDP Input 域。 */
async function waitForPreviewChildPage(mainPage, expectedUrl, deadline, signal) {
  let child;
  await waitForCondition(
    "Preview child WebView target",
    () => {
      child = mainPage
        .context()
        .pages()
        .find(
          (candidate) =>
            candidate !== mainPage && !candidate.isClosed() && candidate.url() === expectedUrl,
        );
      return child !== undefined;
    },
    deadline,
    signal,
  );
  return child;
}

/** 物理点击 Preview child，并用其只读 `document.hasFocus()` 证明真实焦点。 */
async function focusOwnedPreviewChild(page, identity, workbench, deadline, signal) {
  const viewport = workbench.locator(
    '[data-tab-panel="preview"]:not([hidden]) .ja-preview-viewport',
  );
  const url = await viewport.getAttribute("data-url");
  if (!url) throw new Error("Preview child 焦点断言缺少 URL");
  const child = await waitForPreviewChildPage(page, url, deadline, signal);
  await focusOwnedRendererTarget(
    page,
    identity,
    viewport,
    "Preview child viewport",
    deadline,
    signal,
  );
  await waitForCondition(
    "Preview child 获得原生焦点",
    async () => child.evaluate(() => globalThis.document.hasFocus()).catch(() => false),
    deadline,
    signal,
  );
  return { url };
}

/** 物理点击活动 xterm，并确认 helper textarea 持有 WebView2 DOM 焦点。 */
async function focusOwnedXterm(page, identity, terminalWorkspace, deadline, signal) {
  const active = await activeTerminalTab(terminalWorkspace, deadline);
  const pane = active.panel.locator(".ja-terminal-pane[data-pane-id]").first();
  const paneId = await pane.getAttribute("data-pane-id");
  if (!paneId) throw new Error("xterm 缺少 pane identity");
  await focusOwnedRendererTarget(
    page,
    identity,
    pane.locator(".xterm-screen"),
    "xterm screen",
    deadline,
    signal,
  );
  await page.waitForFunction(
    (id) => {
      const activeElement = globalThis.document.activeElement;
      return (
        activeElement instanceof globalThis.HTMLTextAreaElement &&
        activeElement.classList.contains("xterm-helper-textarea") &&
        activeElement.closest(".ja-terminal-pane")?.getAttribute("data-pane-id") === id
      );
    },
    paneId,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
}

/**
 * 用真实 Win32 点击把键盘焦点交给 main WebView 的 Composer；foreground HWND
 * 只能证明窗口归属，不能替代 WebView2 controller 内部的 activeElement 证据。
 */
async function focusOwnedMainComposer(page, identity, deadline, signal) {
  const composer = page.getByRole("textbox", { name: "消息", exact: true });
  await focusOwnedRendererTarget(page, identity, composer, "main Composer", deadline, signal);
  await page.waitForFunction(
    () => {
      const activeElement = globalThis.document.activeElement;
      return (
        activeElement instanceof globalThis.HTMLTextAreaElement &&
        activeElement.getAttribute("aria-label") === "消息"
      );
    },
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
}

/**
 * 发送固定、非敏感的 Side Chat 测试标记，并以 Composer 的精确值作为最终 ACK。
 * 外部通知可能在 SendInput 完成后抢走前台；若标记已经完整进入 main 文档则动作
 * 已被证明，若只进入前缀或完全未进入则清除局部 fixture、重新建立 native 焦点后
 * 最多重试两次。其它 native text 调用仍保持严格 foreground fail-closed 语义。
 */
async function enterOwnedComposerMarker(page, identity, composer, marker, deadline, signal) {
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      await invokeOwnedNativeInput(identity, { kind: "text", text: marker }, signal);
    } catch (error) {
      const message = String(error?.message ?? error);
      const foregroundWasStolen =
        message.startsWith("Win32 SendInput 身份或前台证据无效：") &&
        message.includes('"kind":"text"') &&
        message.includes('"foreground":false') &&
        message.includes('"ownedHandle":');
      const value = await composer.inputValue();
      if (value === marker) return attempt;
      if (!foregroundWasStolen || attempt >= 3 || Date.now() >= deadline) throw error;
      await composer.fill("");
      await focusOwnedMainComposer(page, identity, deadline, signal);
      continue;
    }
    await page.waitForFunction(
      (expected) => globalThis.document.querySelector('[aria-label="消息"]')?.value === expected,
      marker,
      { timeout: Math.max(1, deadline - Date.now()) },
    );
    return attempt;
  }
  throw new Error("Side Chat native 标记未进入 main Composer");
}

/** 等待 canonical native dispatcher 的可见落点；Side Chat 必须关闭工作台并聚焦 Composer。 */
async function waitForNativeShortcutDestination(page, workbench, definition, deadline, signal) {
  if (definition.tab !== undefined) {
    await page
      .locator(`[data-tab-panel="${definition.tab}"]:not([hidden])`)
      .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
    return;
  }
  await waitForCondition(
    "Side Chat 返回 main Composer",
    () =>
      page.evaluate(() => {
        const composer = globalThis.document.querySelector('[aria-label="消息"]');
        return (
          composer instanceof globalThis.HTMLTextAreaElement &&
          composer === globalThis.document.activeElement &&
          globalThis.document
            .querySelector('.ja-inspector[aria-label="工作区面板"]')
            ?.getAttribute("data-visible") !== "true"
        );
      }),
    deadline,
    signal,
  );
  await waitForWorkbenchCollapsed(workbench, deadline, signal);
}

/**
 * 发送一个 Win32 chord，断言唯一 native 事件、UI 落点与零 terminal input
 * 增量；Side Chat marker 只用布局稳定的大写字母与下划线，避免数字键在
 * 非 US Windows 布局下经 `VkKeyScanEx` 变成标点造成假失败。
 */
async function sendAndAssertNativeShortcut(
  page,
  workbench,
  identity,
  definition,
  source,
  deadline,
  signal,
  repetitions = 1,
) {
  const composer = page.getByRole("textbox", { name: "消息", exact: true });
  if (definition.command === "side_chat") await composer.fill("");
  const before = await nativeShortcutEventCount(page);
  const terminalBefore = await terminalInputInvokeCount(page);
  await invokeOwnedNativeInput(identity, { kind: "reset_modifiers" }, signal);
  await page.evaluate(() => {
    globalThis.__JA_E2E_NATIVE_KEY_PROBE_CONTROLLER__?.abort?.();
    const controller = new globalThis.AbortController();
    const events = [];
    for (const type of ["keydown", "keyup"]) {
      globalThis.document.addEventListener(
        type,
        (event) => {
          if (!(event instanceof globalThis.KeyboardEvent) || events.length >= 16) return;
          events.push({
            type,
            code: event.code,
            key: event.key,
            control: event.ctrlKey,
            shift: event.shiftKey,
            alt: event.altKey,
            repeat: event.repeat,
            trusted: event.isTrusted,
            defaultPrevented: event.defaultPrevented,
          });
        },
        { capture: true, signal: controller.signal },
      );
    }
    globalThis.__JA_E2E_NATIVE_KEY_PROBE_CONTROLLER__ = controller;
    globalThis.__JA_E2E_NATIVE_KEY_PROBE__ = events;
  });
  await invokeOwnedNativeInput(
    identity,
    { kind: "chord", key: definition.key, modifiers: definition.modifiers, repetitions },
    signal,
  );
  try {
    await waitForCondition(
      `${source} ${definition.command} native event`,
      async () => (await nativeShortcutEventCount(page)) >= before + 1,
      Math.min(deadline, Date.now() + 12_000),
      signal,
    );
  } catch (error) {
    const diagnostic = await page.evaluate(() => ({
      activeElement:
        globalThis.document.activeElement instanceof globalThis.HTMLElement
          ? {
              tag: globalThis.document.activeElement.tagName,
              className: globalThis.document.activeElement.className,
              ariaLabel: globalThis.document.activeElement.getAttribute("aria-label"),
            }
          : null,
      keyEvents: Array.isArray(globalThis.__JA_E2E_NATIVE_KEY_PROBE__)
        ? globalThis.__JA_E2E_NATIVE_KEY_PROBE__
        : [],
    }));
    const [prepareTrace, activateTrace] = await Promise.all([
      tauriInvokeTrace(page, "ja_native_shortcut_context_update").catch(() => []),
      tauriInvokeTrace(page, "ja_native_shortcut_context_activate").catch(() => []),
    ]);
    throw new Error(
      `${source} ${definition.command} native event 超时：${JSON.stringify({ diagnostic, prepareTrace: prepareTrace.slice(-8), activateTrace: activateTrace.slice(-8) })}`,
      { cause: error },
    );
  } finally {
    await page
      .evaluate(() => globalThis.__JA_E2E_NATIVE_KEY_PROBE_CONTROLLER__?.abort?.())
      .catch(() => undefined);
  }
  await waitForNativeShortcutDestination(page, workbench, definition, deadline, signal);
  const added = (await captureNativeShortcutEvents(page)).events.slice(before);
  if (added.length !== 1 || added[0]?.command !== definition.command)
    throw new Error(`${source} native 事件错误：${JSON.stringify(added)}`);
  if ((await terminalInputInvokeCount(page)) !== terminalBefore)
    throw new Error(`${source} ${definition.command} 增加了 ja_terminal_input`);
  let composerMarkerEntered = false;
  let composerMarkerAttempts = 0;
  if (definition.command === "side_chat") {
    const marker = `JA_NATIVE_${source.toUpperCase()}_MARKER`;
    composerMarkerAttempts = await enterOwnedComposerMarker(
      page,
      identity,
      composer,
      marker,
      deadline,
      signal,
    );
    if ((await terminalInputInvokeCount(page)) !== terminalBefore)
      throw new Error("Side Chat 文本进入 terminal input");
    await composer.fill("");
    composerMarkerEntered = true;
  }
  return {
    command: definition.command,
    terminalInputDelta: 0,
    composerMarkerEntered,
    composerMarkerAttempts,
  };
}

/**
 * 只接受最新 prepare=false 与 activate=true 两阶段 ACK；任一阶段仍 pending 时继续等待，
 * 避免把 renderer 尚未预置 exact identity 的窗口误判成原生快捷键已就绪。
 */
async function waitForNativeShortcutContextReady(page, deadline, signal) {
  let latest;
  await waitForCondition(
    "native shortcut context ready",
    async () => {
      const [prepareTrace, activateTrace] = await Promise.all([
        tauriInvokeTrace(page, "ja_native_shortcut_context_update"),
        tauriInvokeTrace(page, "ja_native_shortcut_context_activate"),
      ]);
      const prepare = prepareTrace.at(-1);
      const activate = activateTrace.at(-1);
      latest = { prepare, activate };
      return (
        prepare?.phase === "resolved" &&
        !prepare.ready &&
        prepare.mainHandlerStatus === "ready" &&
        prepare.projectCapabilitiesEnabled &&
        prepare.conversationFocusEnabled &&
        activate?.phase === "resolved" &&
        activate.ready &&
        activate.mainHandlerStatus === "ready"
      );
    },
    Math.min(deadline, Date.now() + 15_000),
    signal,
  );
  return latest;
}

/** 每个动作前重新建立同一焦点源，确保五键不是由上一动作留下的 main 焦点触发。 */
async function exerciseNativeShortcutSurface(
  page,
  workbench,
  identity,
  source,
  prepareFocus,
  deadline,
  signal,
) {
  const offset = await nativeShortcutEventCount(page);
  const terminalBefore = await terminalInputInvokeCount(page);
  const cases = [];
  for (const definition of nativeShortcutCases) {
    await prepareFocus();
    cases.push(
      await sendAndAssertNativeShortcut(
        page,
        workbench,
        identity,
        definition,
        source,
        deadline,
        signal,
      ),
    );
  }
  const commands = (await captureNativeShortcutEvents(page)).events
    .slice(offset)
    .map(({ command }) => command);
  const expected = nativeShortcutCases.map(({ command }) => command);
  if (
    JSON.stringify(commands) !== JSON.stringify(expected) ||
    (await terminalInputInvokeCount(page)) !== terminalBefore
  )
    throw new Error(`${source} 五键证据不完整`);
  return { status: "passed", focus: source, commands, terminalInputDelta: 0, cases };
}

/** 用随后合法 Preview chord 作消息队列 barrier，证明负向 chord 没有产生事件。 */
async function assertNativeChordRejectedWithBarrier(
  page,
  workbench,
  identity,
  preparePreviewFocus,
  fixture,
  deadline,
  signal,
) {
  await preparePreviewFocus();
  const before = await nativeShortcutEventCount(page);
  await invokeOwnedNativeInput(
    identity,
    { kind: "chord", key: fixture.key, modifiers: fixture.modifiers, repetitions: 1 },
    signal,
  );
  // 刻意不处理的 chord 仍会传给子页面，并可能改变其自身焦点。因此该输入后按顺序执行一次物理重新聚焦，
  // 再以合法 chord 作为消息队列 barrier；不能假设子页面对被拒绝组合没有浏览器原生响应。
  await preparePreviewFocus();
  await invokeOwnedNativeInput(
    identity,
    { kind: "chord", key: 0x54, modifiers: [0x11], repetitions: 1 },
    signal,
  );
  await waitForCondition(
    `${fixture.label} barrier`,
    async () => (await nativeShortcutEventCount(page)) >= before + 1,
    deadline,
    signal,
  );
  await waitForNativeShortcutDestination(page, workbench, nativeShortcutCases[2], deadline, signal);
  const added = (await captureNativeShortcutEvents(page)).events.slice(before);
  if (added.length !== 1 || added[0]?.command !== "preview")
    throw new Error(`${fixture.label} 被错误消费：${JSON.stringify(added)}`);
}

/**
 * 覆盖 AltRight、AltGr、额外 Shift 和 held-key repeat，负向输入不污染 PTY。
 * 额外 Shift 使用 Ctrl+Shift+反引号，避开 WebView/系统会接管的打印快捷键。
 */
async function exerciseNativeShortcutNegativeInputs(
  page,
  workbench,
  identity,
  preparePreviewFocus,
  deadline,
  signal,
) {
  const terminalBefore = await terminalInputInvokeCount(page);
  await assertNativeChordRejectedWithBarrier(
    page,
    workbench,
    identity,
    preparePreviewFocus,
    { label: "AltRight", key: 0x53, modifiers: [0xa5] },
    deadline,
    signal,
  );
  await assertNativeChordRejectedWithBarrier(
    page,
    workbench,
    identity,
    preparePreviewFocus,
    { label: "AltGr", key: 0x53, modifiers: [0x11, 0xa5] },
    deadline,
    signal,
  );
  await assertNativeChordRejectedWithBarrier(
    page,
    workbench,
    identity,
    preparePreviewFocus,
    { label: "extra modifier", key: 0xc0, modifiers: [0x11, 0x10] },
    deadline,
    signal,
  );
  await preparePreviewFocus();
  const before = await nativeShortcutEventCount(page);
  await invokeOwnedNativeInput(
    identity,
    { kind: "chord", key: 0x54, modifiers: [0x11], repetitions: 4 },
    signal,
  );
  await waitForCondition(
    "auto-repeat initial event",
    async () => (await nativeShortcutEventCount(page)) >= before + 1,
    deadline,
    signal,
  );
  const added = (await captureNativeShortcutEvents(page)).events.slice(before);
  if (
    added.length !== 1 ||
    added[0]?.command !== "preview" ||
    (await terminalInputInvokeCount(page)) !== terminalBefore
  )
    throw new Error("auto-repeat 或负向输入门禁失败");
  return {
    status: "passed",
    altRight: true,
    altGr: true,
    extraModifier: true,
    autoRepeatExtraEvents: 0,
    terminalInputDelta: 0,
  };
}

/** 在 xterm 与真实 Preview child 各跑五键并恢复 Preview，不改变既有视觉 capture 状态。 */
async function exerciseNativeShortcuts(
  page,
  workbench,
  terminalWorkspace,
  identity,
  deadline,
  signal,
) {
  /** 每轮重新打开 Terminal 并物理聚焦 xterm。 */
  const prepareXtermFocus = async () => {
    await ensureWorkbenchVisible(page, deadline);
    await chooseWorkbenchTool(page, "终端", deadline);
    await focusOwnedXterm(page, identity, terminalWorkspace, deadline, signal);
  };
  /** 每轮等待 Preview visible ACK 后物理聚焦 child。 */
  const preparePreviewFocus = async () => {
    await ensureWorkbenchVisible(page, deadline);
    const active = await workbench
      .locator('.ja-workbench-tab-shell[data-state="active"]')
      .getAttribute("data-tab");
    const ack = await tauriInvokePhaseCount(page, "ja_preview_layout", "resolved", true);
    await chooseWorkbenchTool(page, "浏览器", deadline);
    if (active !== "preview")
      await waitForCondition(
        "Preview visible layout ACK",
        async () =>
          (await tauriInvokePhaseCount(page, "ja_preview_layout", "resolved", true)) > ack,
        deadline,
        signal,
      );
    await focusOwnedPreviewChild(page, identity, workbench, deadline, signal);
  };
  const context = await waitForNativeShortcutContextReady(page, deadline, signal);
  const xterm = await exerciseNativeShortcutSurface(
    page,
    workbench,
    identity,
    "xterm",
    prepareXtermFocus,
    deadline,
    signal,
  );
  const preview = await exerciseNativeShortcutSurface(
    page,
    workbench,
    identity,
    "preview",
    preparePreviewFocus,
    deadline,
    signal,
  );
  const negative = await exerciseNativeShortcutNegativeInputs(
    page,
    workbench,
    identity,
    preparePreviewFocus,
    deadline,
    signal,
  );
  await ensureWorkbenchVisible(page, deadline);
  await chooseWorkbenchTool(page, "浏览器", deadline);
  return { status: "passed", input: "win32_send_input", context, xterm, preview, negative };
}

/**
 * hard reload 前释放 probes，重载后用不依赖项目 Thread 恢复的 Side Chat chord 证明 renderer lease 已重绑。
 * 该阶段只验证 listener 生命周期；完整五键和项目能力随后仍在 xterm 与 Preview child 上逐项验收。
 */
async function exerciseNativeShortcutHardReload(page, workbench, identity, deadline, signal) {
  await removeRawTauriEventProbe(page);
  await removeTauriInvokeProbe(page);
  await page.addInitScript(installTauriInvokeProbeInPage);
  await page.reload({ waitUntil: "domcontentloaded", timeout: Math.max(1, deadline - Date.now()) });
  await waitForRuntimeReady(page, deadline, signal);
  await installRawTauriEventProbe(page);
  await installTauriInvokeProbe(page);
  await waitForCondition(
    "hard reload native shortcut lease",
    async () => {
      const trace = await tauriInvokeTrace(page, "ja_native_shortcut_context_activate");
      return trace.some(
        (entry) =>
          entry.phase === "resolved" &&
          entry.ready &&
          entry.mainHandlerStatus === "ready" &&
          entry.conversationFocusEnabled,
      );
    },
    deadline,
    signal,
  );
  await ensureWorkbenchVisible(page, deadline);
  await invokeOwnedWindowAction(identity, "focus", signal);
  await focusOwnedMainComposer(page, identity, deadline, signal);
  const result = await sendAndAssertNativeShortcut(
    page,
    workbench,
    identity,
    nativeShortcutCases[4],
    "hard_reload_main",
    deadline,
    signal,
  );
  await ensureWorkbenchVisible(page, deadline);
  return { status: "passed", leaseRebound: true, command: result.command, terminalInputDelta: 0 };
}

/** 只返回本轮已复验 ja.exe 的后代 terminal shell，避免按名称误收宿主进程。 */
async function captureOwnedTerminalShells(scope, signal) {
  const snapshot = await processSnapshot(signal);
  const tree = processTree(scope.rootIdentity, snapshot, scope.incompleteObserved);
  if (tree === undefined) throw new Error("无法读取本次 Tauri 进程树中的 PTY");
  for (const [pid, entry] of tree) scope.observed.set(pid, entry);
  const ja = [...tree.values()].filter((entry) => entry.name.toLowerCase() === "ja.exe");
  if (ja.length !== 1) throw new Error(`PTY owner 数量异常：${ja.length}`);
  const descendants = new Set([ja[0].pid]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const entry of tree.values()) {
      if (!descendants.has(entry.pid) && descendants.has(entry.parentPid)) {
        descendants.add(entry.pid);
        changed = true;
      }
    }
  }
  const shellNames = new Set(["pwsh.exe", "powershell.exe", "cmd.exe"]);
  return [...tree.values()].filter(
    (entry) => descendants.has(entry.pid) && shellNames.has(entry.name.toLowerCase()),
  );
}

/** 等待每个已记录 shell identity 消失，而不是按名称匹配宿主进程。 */
async function waitForOwnedIdentitiesGone(identities, deadline, signal) {
  while (Date.now() < deadline) {
    const snapshot = await processSnapshot(signal);
    if (
      identities.every(
        (identity) => !snapshot.some((entry) => sameProcessIdentity(identity, entry)),
      )
    )
      return;
    await waitForDelay(100, signal);
  }
  throw new Error(`workspace closeAll 后仍有 ${identities.length} 个已记录 PTY identity 未释放`);
}

/** 证明非破坏性 UI 切换保留了此前记录的全部 PTY。 */
async function assertOwnedIdentitiesAlive(identities, signal, transition) {
  const snapshot = await processSnapshot(signal);
  const alive = identities.filter((identity) =>
    snapshot.some((entry) => sameProcessIdentity(identity, entry)),
  ).length;
  if (alive !== identities.length)
    throw new Error(`${transition} 意外终止 ${identities.length - alive} 个后台 PTY`);
}

/** fixture 缺失时返回 false，且不暴露宿主文件系统错误。 */
async function pathExists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

/** 区分已创建目录与同名文件，同时不向证据暴露 IO 错误文本。 */
async function directoryExists(path) {
  try {
    return (await stat(path)).isDirectory();
  } catch {
    return false;
  }
}

/** 比较有界文本 fixture；路径尚未创建时按 false 处理，以容忍 watcher 生效前的正常窗口。 */
async function fileTextEquals(path, expected) {
  try {
    return (await readFile(path, "utf8")) === expected;
  } catch {
    return false;
  }
}

/** 等待 Node 侧条件成立，不引入未经验证的固定 sleep。 */
async function waitForCondition(label, predicate, deadline, signal) {
  while (Date.now() < deadline) {
    throwIfAborted(signal);
    if (await predicate()) return;
    await waitForDelay(100, signal);
  }
  throw new Error(`${label} 超时`);
}

/**
 * 通过真实布局、继承可见性与 viewport hit-testing 验证 portal surface。
 * WebView2 的 CDP bridge 可能经 Playwright 通用 checkVisibility 链路将已渲染 Radix portal
 * 误报为隐藏，因此桌面测试使用浏览器自身 paint 与 pointer target 作为证据。
 */
async function waitForRenderedSurface(locator, label, deadline, signal) {
  let lastEvidence = { reason: "locator_missing" };
  while (Date.now() < deadline) {
    throwIfAborted(signal);
    try {
      if ((await locator.count()) === 1) {
        const evidence = await locator.evaluate((element) => {
          let clippedLeft = 0;
          let clippedRight = globalThis.innerWidth;
          let clippedTop = 0;
          let clippedBottom = globalThis.innerHeight;
          let current = element;
          while (current instanceof globalThis.HTMLElement) {
            const style = globalThis.getComputedStyle(current);
            if (
              style.display === "none" ||
              style.visibility !== "visible" ||
              Number.parseFloat(style.opacity) === 0
            ) {
              return {
                reason: "hidden_ancestor",
                className: current.className,
                display: style.display,
                visibility: style.visibility,
                opacity: style.opacity,
              };
            }
            // scrollport 只绘制与其 padding box 相交的部分。必须纳入每个裁切祖先，
            // 避免 hit-testing 命中横向滚动 tab 或 editor surface 的隐藏半边。
            if (current !== element) {
              const clipsX = ["auto", "scroll", "hidden", "clip"].includes(style.overflowX);
              const clipsY = ["auto", "scroll", "hidden", "clip"].includes(style.overflowY);
              if (clipsX || clipsY) {
                const currentRect = current.getBoundingClientRect();
                if (clipsX) {
                  clippedLeft = Math.max(clippedLeft, currentRect.left);
                  clippedRight = Math.min(clippedRight, currentRect.right);
                }
                if (clipsY) {
                  clippedTop = Math.max(clippedTop, currentRect.top);
                  clippedBottom = Math.min(clippedBottom, currentRect.bottom);
                }
              }
            }
            current = current.parentElement;
          }
          const rect = element.getBoundingClientRect();
          if (
            rect.width <= 0 ||
            rect.height <= 0 ||
            rect.right <= 0 ||
            rect.bottom <= 0 ||
            rect.left >= globalThis.innerWidth ||
            rect.top >= globalThis.innerHeight
          ) {
            return {
              reason: "outside_viewport",
              rect: rect.toJSON(),
              innerWidth: globalThis.innerWidth,
              innerHeight: globalThis.innerHeight,
            };
          }
          // 选择已绘制交集的中心，而不是完整元素中心；控件在一个或多个 scrollport 中可能只部分可见。
          const visibleLeft = Math.max(clippedLeft, rect.left);
          const visibleRight = Math.min(clippedRight, rect.right);
          const visibleTop = Math.max(clippedTop, rect.top);
          const visibleBottom = Math.min(clippedBottom, rect.bottom);
          if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) {
            return {
              reason: "outside_clipping_ancestor",
              rect: rect.toJSON(),
              clipRect: {
                left: clippedLeft,
                right: clippedRight,
                top: clippedTop,
                bottom: clippedBottom,
              },
            };
          }
          const x = Math.min(
            globalThis.innerWidth - 1,
            visibleLeft + (visibleRight - visibleLeft) / 2,
          );
          const y = Math.min(
            globalThis.innerHeight - 1,
            visibleTop + (visibleBottom - visibleTop) / 2,
          );
          const top = globalThis.document.elementFromPoint(x, y);
          if (top === null || (top !== element && !element.contains(top))) {
            // 在不改变 scroll/focus 状态的前提下捕获 ownership 与几何，
            // 用于区分产品重叠与自动化竞态。
            const topButton = top instanceof globalThis.Element ? top.closest("button") : null;
            const tabbar = element.closest(".ja-workbench-tabbar");
            const tabs = element.closest(".ja-workbench-tabs");
            const rectOf = (candidate) =>
              candidate instanceof globalThis.Element
                ? candidate.getBoundingClientRect().toJSON()
                : null;
            return {
              reason: "covered",
              x,
              y,
              targetRect: rect.toJSON(),
              topTag: top?.tagName ?? null,
              topClass: top instanceof globalThis.HTMLElement ? top.className : null,
              topButton:
                topButton === null
                  ? null
                  : {
                      className: topButton.className,
                      ariaLabel: topButton.getAttribute("aria-label"),
                      rect: rectOf(topButton),
                    },
              tabbarRect: rectOf(tabbar),
              tabsRect: rectOf(tabs),
              fixedControls:
                tabbar === null
                  ? []
                  : [...tabbar.querySelectorAll(":scope > button")].map((button) => ({
                      className: button.className,
                      ariaLabel: button.getAttribute("aria-label"),
                      rect: rectOf(button),
                    })),
            };
          }
          return { reason: "ready", x, y, width: rect.width, height: rect.height };
        });
        lastEvidence = evidence;
        if (evidence.reason === "ready") return evidence;
      }
    } catch (error) {
      // 并发 React 投影可能替换节点，因此通过 locator 重新解析后重试。
      lastEvidence = {
        reason: "evaluation_error",
        message: error instanceof Error ? error.message : String(error),
      };
    }
    await waitForDelay(100, signal);
  }
  throw new Error(`${label} 未形成可见且可命中的真实界面：${JSON.stringify(lastEvidence)}`);
}

/** 通过 CodeMirror 真实 contenteditable surface 替换文档，避免绕过编辑器事件链。 */
async function replaceCodeMirrorContent(editor, content, deadline) {
  const input = editor.locator('.cm-content[contenteditable="true"]');
  await input.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await input.fill(content);
}

/** 从 CodeMirror 已渲染文档 surface 读取当前 editor 文本，以 UI 投影作为验收事实。 */
async function codeMirrorText(editor) {
  return editor.locator(".cm-content").innerText();
}

/**
 * 仅解码足以证明原始路径与删除时间的 Windows 私有 $I 回收站元数据；
 * payload 内容与 opaque $R 名称绝不进入 E2E 结果或日志。
 */
function decodeRecycleMetadata(buffer) {
  if (buffer.length < 24) return undefined;
  const version = buffer.readBigUInt64LE(0);
  const pathOffset = version === 2n ? 28 : version === 1n ? 24 : undefined;
  if (pathOffset === undefined || buffer.length <= pathOffset) return undefined;
  const originalPath = buffer.subarray(pathOffset).toString("utf16le").split("\0", 1)[0]?.trim();
  if (!originalPath) return undefined;
  const fileTime = buffer.readBigUInt64LE(16);
  const deletedAtUnixMillis = Number(fileTime / 10_000n - 11_644_473_600_000n);
  if (!Number.isSafeInteger(deletedAtUnixMillis)) return undefined;
  return { originalPath, deletedAtUnixMillis };
}

/**
 * 必须同时匹配 Windows 回收站元数据及其成对 $R payload，
 * 不能仅凭路径消失就认定项目仍可恢复。
 */
async function hasRecycleRecordFor(originalPath, deletedAfterUnixMillis, expectedPayload) {
  const recycleRoot = join(parse(originalPath).root, "$Recycle.Bin");
  let accountDirectories;
  try {
    accountDirectories = await readdir(recycleRoot, { withFileTypes: true });
  } catch {
    throw new Error("无法读取当前卷的系统回收站元数据，不能证明真实回收站提交");
  }
  const normalizedOriginalPath = resolve(originalPath).toLowerCase();
  for (const accountDirectory of accountDirectories) {
    if (!accountDirectory.isDirectory()) continue;
    const accountPath = join(recycleRoot, accountDirectory.name);
    let entries;
    try {
      entries = await readdir(accountPath, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      if (!entry.isFile() || !entry.name.toUpperCase().startsWith("$I")) continue;
      try {
        const metadata = decodeRecycleMetadata(await readFile(join(accountPath, entry.name)));
        if (
          metadata === undefined ||
          resolve(metadata.originalPath).toLowerCase() !== normalizedOriginalPath ||
          metadata.deletedAtUnixMillis < deletedAfterUnixMillis - 5_000
        )
          continue;
        const payloadName = `$R${entry.name.slice(2)}`.toUpperCase();
        const payloadEntry = entries.find(
          (candidate) => candidate.name.toUpperCase() === payloadName,
        );
        if (payloadEntry?.isFile() !== true) continue;
        const payload = await readFile(join(accountPath, payloadEntry.name));
        if (payload.equals(expectedPayload)) return true;
      } catch {
        // 有界扫描期间，并发 shell cleanup 可能删除一条元数据记录；下一轮轮询会重新读取权威状态。
      }
    }
  }
  return false;
}

/**
 * 仅针对磁盘上的随机 E2E workspace 检验类型化 Files mutation、CAS autosave、
 * watcher 冲突恢复与焦点协调。
 */
async function exerciseFilesWorkspace(
  page,
  filesWorkspace,
  directories,
  ownedWindow,
  deadline,
  signal,
) {
  const timeout = () => Math.max(1, deadline - Date.now());
  const fileTree = filesWorkspace.getByRole("tree", { name: "工作区文件", exact: true });
  await fileTree.waitFor({ state: "visible", timeout: timeout() });

  const visibleDirectory = fileTree.locator('[data-path="folder-fixture"]');
  await visibleDirectory.waitFor({ state: "visible", timeout: timeout() });
  if ((await visibleDirectory.getAttribute("data-kind")) !== "directory")
    throw new Error("Files 未把普通文件夹投影为 directory");
  const initialGeometry = await captureFilesTreeGeometry(filesWorkspace);
  if (initialGeometry.valid !== true) {
    throw new Error(`Files 自适应布局表面缺失：${JSON.stringify(initialGeometry)}`);
  }
  const expectedViewportHeight = initialGeometry.hostHeight - initialGeometry.toolbarHeight;
  if (
    Math.abs(initialGeometry.hostHeight - initialGeometry.explorerHeight) > 4 ||
    Math.abs(initialGeometry.viewportHeight - expectedViewportHeight) > 4 ||
    Math.abs(initialGeometry.viewportBottom - initialGeometry.hostBottom) > 4 ||
    Math.abs(initialGeometry.treeClientHeight - initialGeometry.viewportHeight) > 4
  ) {
    throw new Error(`Files 文件树没有占满可用高度：${JSON.stringify(initialGeometry)}`);
  }
  if (initialGeometry.overflowY) {
    throw new Error(`Files 少量根节点时提前出现滚动：${JSON.stringify(initialGeometry)}`);
  }

  const overflowDirectory = fileTree.locator('[data-path="overflow-fixture"]');
  await overflowDirectory
    .getByRole("button", { name: "展开overflow-fixture", exact: true })
    .click({ timeout: timeout() });
  const overflowChild = fileTree.locator('[data-path="overflow-fixture/item-000.txt"]');
  await overflowChild.waitFor({ state: "visible", timeout: timeout() });
  const overflowGeometry = await captureFilesTreeGeometry(filesWorkspace);
  if (overflowGeometry.valid !== true || overflowGeometry.overflowY !== true) {
    throw new Error(`Files 内容超过 viewport 后没有内部滚动：${JSON.stringify(overflowGeometry)}`);
  }
  await overflowDirectory
    .getByRole("button", { name: "折叠overflow-fixture", exact: true })
    .click({ timeout: timeout() });
  await overflowChild.waitFor({ state: "hidden", timeout: timeout() });
  const collapsedGeometry = await captureFilesTreeGeometry(filesWorkspace);
  if (collapsedGeometry.valid !== true || collapsedGeometry.overflowY) {
    throw new Error(`Files 折叠大量节点后没有释放滚动：${JSON.stringify(collapsedGeometry)}`);
  }

  await clickVerifiedControl(
    page,
    filesWorkspace.getByRole("button", { name: "新建文件", exact: true }),
    deadline,
  );
  const createInput = filesWorkspace.getByRole("textbox", { name: "新建文件名", exact: true });
  await createInput.fill("e2e-created.txt");
  await createInput.press("Enter");
  await fileTree
    .locator('[data-path="e2e-created.txt"]')
    .waitFor({ state: "visible", timeout: timeout() });
  await waitForCondition(
    "新建文件落盘",
    () => pathExists(join(directories.workspace, "e2e-created.txt")),
    deadline,
    signal,
  );
  await clickVerifiedControl(
    page,
    filesWorkspace.getByRole("button", { name: "新建目录", exact: true }),
    deadline,
  );
  const createDirectoryInput = filesWorkspace.getByRole("textbox", {
    name: "新建目录名",
    exact: true,
  });
  await createDirectoryInput.fill("e2e-move-target");
  await createDirectoryInput.press("Enter");
  await waitForCondition(
    "新建目录落盘",
    () => directoryExists(join(directories.workspace, "e2e-move-target")),
    deadline,
    signal,
  );
  // 原生磁盘状态是创建操作的权威事实。watcher/root refresh 可能与 renderer 投影竞态，
  // 因此只重试真实 refresh 控件，并以绑定路径的行作为严格可见完成条件。
  const moveTargetProjection = fileTree.locator('[data-path="e2e-move-target"]');
  const refreshFileTree = filesWorkspace.getByRole("button", { name: "刷新文件树", exact: true });
  await waitForCondition(
    "新建目录树投影",
    async () => {
      if (await moveTargetProjection.isVisible().catch(() => false)) return true;
      const attemptDeadline = Math.min(deadline, Date.now() + 5_000);
      await refreshFileTree
        .click({ timeout: Math.max(1, attemptDeadline - Date.now()) })
        .catch(() => undefined);
      await moveTargetProjection
        .waitFor({
          state: "visible",
          timeout: Math.max(1, attemptDeadline - Date.now()),
        })
        .catch(() => undefined);
      return moveTargetProjection.isVisible().catch(() => false);
    },
    deadline,
    signal,
  );

  await fileTree.locator('[data-path="e2e-created.txt"]').click({ button: "right" });
  await clickVerifiedControl(
    page,
    page.getByRole("menuitem", { name: "重命名", exact: true }),
    deadline,
  );
  const renameInput = filesWorkspace.getByRole("textbox", {
    name: "重命名 e2e-created.txt",
    exact: true,
  });
  await renameInput.fill("e2e-renamed.txt");
  await renameInput.press("Enter");
  await fileTree
    .locator('[data-path="e2e-renamed.txt"]')
    .waitFor({ state: "visible", timeout: timeout() });
  await waitForCondition(
    "重命名文件落盘",
    async () =>
      (await pathExists(join(directories.workspace, "e2e-renamed.txt"))) &&
      !(await pathExists(join(directories.workspace, "e2e-created.txt"))),
    deadline,
    signal,
  );

  const moveSource = fileTree.locator('[data-path="e2e-renamed.txt"]');
  const moveTarget = fileTree.locator('[data-path="e2e-move-target"]');
  const [sourceBox, targetBox] = await Promise.all([
    moveSource.boundingBox(),
    moveTarget.boundingBox(),
  ]);
  if (sourceBox === null || targetBox === null) throw new Error("内部文件拖动缺少可见源或目标目录");
  await page.mouse.move(sourceBox.x + sourceBox.width / 2, sourceBox.y + sourceBox.height / 2);
  await page.mouse.down();
  await page.mouse.move(targetBox.x + targetBox.width / 2, targetBox.y + targetBox.height / 2, {
    steps: 8,
  });
  await page.mouse.up();
  await waitForCondition(
    "内部拖动移动落盘",
    async () =>
      (await pathExists(join(directories.workspace, "e2e-move-target", "e2e-renamed.txt"))) &&
      !(await pathExists(join(directories.workspace, "e2e-renamed.txt"))),
    deadline,
    signal,
  );
  // watcher refresh 可能在点击已派发后替换 Arborist 行并重置其瞬时展开状态。
  // 仅当绑定路径的 disclosure 当前关闭时才重新解析；已展开或 loading 的行不做干预，
  // nested child 仍是权威完成断言。
  const movedTargetClosed = moveTarget.getByRole("button", {
    name: "展开e2e-move-target",
    exact: true,
  });
  const movedTargetOpen = moveTarget.getByRole("button", {
    name: "折叠e2e-move-target",
    exact: true,
  });
  const movedTargetLoading = moveTarget.getByLabel("加载中", { exact: true });
  const movedChild = fileTree.locator('[data-path="e2e-move-target/e2e-renamed.txt"]');
  await waitForCondition(
    "移动目标目录展开",
    async () => {
      if (await movedChild.isVisible().catch(() => false)) return true;
      if (
        (await movedTargetOpen.isVisible().catch(() => false)) ||
        (await movedTargetLoading.isVisible().catch(() => false))
      )
        return false;
      if (await movedTargetClosed.isVisible().catch(() => false)) {
        const attemptDeadline = Math.min(deadline, Date.now() + 5_000);
        await movedTargetClosed
          .click({ timeout: Math.min(1_000, Math.max(1, attemptDeadline - Date.now())) })
          .catch(() => undefined);
        const disclosureProgress = movedChild.or(movedTargetOpen).or(movedTargetLoading).first();
        await disclosureProgress
          .waitFor({
            state: "visible",
            timeout: Math.min(1_000, Math.max(1, attemptDeadline - Date.now())),
          })
          .catch(() => undefined);
        if (!(await disclosureProgress.isVisible().catch(() => false))) {
          // move 前的 root page 可能仍将原空目标分类为无子项，导致 Arborist 忽略 toggle()。
          // 只有确认 disclosure 点击未产生状态转换后，才刷新真实页面。
          await refreshFileTree
            .click({ timeout: Math.max(1, attemptDeadline - Date.now()) })
            .catch(() => undefined);
          await moveTarget
            .waitFor({
              state: "visible",
              timeout: Math.max(1, attemptDeadline - Date.now()),
            })
            .catch(() => undefined);
        }
        return movedChild.isVisible().catch(() => false);
      }
      // move 后 root watcher reconciliation 可能替换整行目标。重试 disclosure 前先恢复权威 root 投影；
      // 目录是否打开仍由 nested child 决定。
      const attemptDeadline = Math.min(deadline, Date.now() + 5_000);
      await refreshFileTree
        .click({ timeout: Math.max(1, attemptDeadline - Date.now()) })
        .catch(() => undefined);
      await moveTarget
        .waitFor({
          state: "visible",
          timeout: Math.max(1, attemptDeadline - Date.now()),
        })
        .catch(() => undefined);
      return movedChild.isVisible().catch(() => false);
    },
    deadline,
    signal,
  );
  await movedChild.waitFor({ state: "visible", timeout: timeout() });

  const cancelPath = join(directories.workspace, "trash-cancel.txt");
  const cancelNode = fileTree.locator('[data-path="trash-cancel.txt"]');
  await cancelNode.click({ button: "right" });
  let trashAction = page.getByRole("menuitem", { name: "移入回收站", exact: true });
  await trashAction.waitFor({ state: "visible", timeout: timeout() });
  await trashAction.click();
  let trashDialog = page.getByRole("alertdialog", { name: "移入回收站", exact: true });
  await waitForRenderedSurface(trashDialog, "应用内回收站取消确认", deadline, signal);
  await trashDialog
    .getByText("trash-cancel.txt", { exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  const commitsBeforeCancel = await tauriInvokeCount(page, "ja_workspace_trash_commit");
  await clickVerifiedControl(
    page,
    trashDialog.getByRole("button", { name: "取消", exact: true }),
    deadline,
  );
  await trashDialog.waitFor({ state: "detached", timeout: timeout() });
  if (!(await pathExists(cancelPath)) || (await cancelNode.count()) !== 1)
    throw new Error("回收站取消后文件或树节点被移除");
  if ((await tauriInvokeCount(page, "ja_workspace_trash_commit")) !== commitsBeforeCancel)
    throw new Error("回收站取消仍调用了 native commit");

  const trashPath = join(directories.workspace, "trash-me.txt");
  const trashPayload = await readFile(trashPath);
  const trashSize = trashPayload.length;
  const trashNode = fileTree.locator('[data-path="trash-me.txt"]');
  await trashNode.click({ button: "right" });
  trashAction = page.getByRole("menuitem", { name: "移入回收站", exact: true });
  await trashAction.waitFor({ state: "visible", timeout: timeout() });
  await trashAction.click();
  trashDialog = page.getByRole("alertdialog", { name: "移入回收站", exact: true });
  await waitForRenderedSurface(trashDialog, "应用内回收站确认", deadline, signal);
  await trashDialog
    .getByText("trash-me.txt", { exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await trashDialog
    .getByText("1 个文件", { exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await trashDialog
    .getByText(`${trashSize.toLocaleString("zh-CN")} 字节`, { exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  let trash;
  if (allowTrashCommit) {
    const commitStartedAt = Date.now();
    await clickVerifiedControl(
      page,
      trashDialog.getByRole("button", { name: "移入回收站", exact: true }),
      deadline,
    );
    const recycleUnavailableAlert = trashDialog.getByRole("alert").filter({
      hasText: "当前磁盘未启用系统回收站，文件没有被删除",
    });
    const outcome = await Promise.race([
      trashDialog.waitFor({ state: "detached", timeout: timeout() }).then(() => "committed"),
      recycleUnavailableAlert
        .waitFor({ state: "visible", timeout: timeout() })
        .then(() => "recycle_unavailable"),
    ]);
    if (outcome === "committed") {
      await waitForCondition(
        "回收站 commit",
        async () => !(await pathExists(trashPath)),
        deadline,
        signal,
      );
      await trashNode.waitFor({ state: "detached", timeout: timeout() });
      await waitForCondition(
        "Windows 系统回收站元数据与 payload",
        () => hasRecycleRecordFor(trashPath, commitStartedAt, trashPayload),
        deadline,
        signal,
      );
      trash = {
        status: "passed",
        gate: "JA_E2E_ALLOW_TRASH=1",
        committed: true,
        cancelVerified: true,
        recycleMetadataVerified: true,
        recyclePayloadVerified: true,
      };
    } else {
      let preservation = { disk: false, treeCount: 0, treeVisible: false };
      await waitForCondition(
        "系统回收站不可用时 fail-closed 保留",
        async () => {
          preservation = {
            disk: await pathExists(trashPath),
            treeCount: await trashNode.count(),
            treeVisible: await trashNode.isVisible().catch(() => false),
          };
          return preservation.disk && preservation.treeCount === 1 && preservation.treeVisible;
        },
        deadline,
        signal,
      ).catch((error) => {
        throw new Error(
          `系统回收站不可用时文件或树节点未被 fail-closed 保留：${JSON.stringify(preservation)}`,
          { cause: error },
        );
      });
      await clickVerifiedControl(
        page,
        trashDialog.getByRole("button", { name: "取消", exact: true }),
        deadline,
      );
      await trashDialog.waitFor({ state: "detached", timeout: timeout() });
      trash = {
        status: "blocked",
        capability: "workspace_system_recycle",
        reason: "system_recycle_disabled",
        gate: "JA_E2E_ALLOW_TRASH=1",
        committed: false,
        cancelVerified: true,
        failClosed: true,
      };
    }
  } else {
    await clickVerifiedControl(
      page,
      trashDialog.getByRole("button", { name: "取消", exact: true }),
      deadline,
    );
    await trashDialog.waitFor({ state: "detached", timeout: timeout() });
    if (!(await pathExists(trashPath))) throw new Error("未启用回收站提交时文件仍被移除");
    trash = {
      status: "gated",
      gate: "JA_E2E_ALLOW_TRASH=1",
      committed: false,
      cancelVerified: true,
      reason: "destructive_opt_in_not_enabled",
    };
  }

  const sampleNode = fileTree.locator('[data-path="sample.ts"]');
  // 原生 watcher refresh 可能在读取布局与派发 pointer 之间替换 Arborist 行；
  // locator.click 会针对当前绑定路径的行重试，同时仍检验真实 WebView pointer 交互。
  await sampleNode.click({ timeout: timeout() });
  const editor = filesWorkspace.getByRole("region", { name: "编辑文件 sample.ts", exact: true });
  await editor.waitFor({ state: "visible", timeout: timeout() });
  const samplePath = join(directories.workspace, "sample.ts");
  const initialDisk = await readFile(samplePath, "utf8");
  const debounceContent = "export const autosave = 'JA_500MS_AUTOSAVE';\n";
  const debounceStarted = Date.now();
  await replaceCodeMirrorContent(editor, debounceContent, deadline);
  await filesWorkspace
    .locator('.ja-files-editor-status[data-status="dirty"]')
    .waitFor({ state: "visible", timeout: timeout() });
  await waitForDelay(250, signal);
  if ((await readFile(samplePath, "utf8")) !== initialDisk)
    throw new Error("500ms debounce 在 250ms 保护窗口内提前写盘");
  await waitForCondition(
    "500ms 自动保存",
    () => fileTextEquals(samplePath, debounceContent),
    deadline,
    signal,
  );
  const debounceElapsedMs = Date.now() - debounceStarted;
  await filesWorkspace
    .locator('.ja-files-editor-status[data-status="clean"]')
    .waitFor({ state: "visible", timeout: timeout() });

  const controlSaveContent = "export const autosave = 'JA_CTRL_S';\n";
  await replaceCodeMirrorContent(editor, controlSaveContent, deadline);
  await filesWorkspace
    .locator('.ja-files-editor-status[data-status="dirty"]')
    .waitFor({ state: "visible", timeout: timeout() });
  await editor.locator(".cm-content").press("Control+S");
  await waitForCondition(
    "Ctrl+S 立即保存",
    () => fileTextEquals(samplePath, controlSaveContent),
    deadline,
    signal,
  );

  const localDraft = "export const conflict = 'JA_LOCAL_DRAFT';\n";
  const externalDraft = "export const conflict = 'JA_EXTERNAL_WRITE';\n";
  await replaceCodeMirrorContent(editor, localDraft, deadline);
  await filesWorkspace
    .locator('.ja-files-editor-status[data-status="dirty"]')
    .waitFor({ state: "visible", timeout: timeout() });
  await writeFile(samplePath, externalDraft, "utf8");
  const conflictAlert = filesWorkspace.locator(".ja-files-conflict");
  await conflictAlert.waitFor({ state: "visible", timeout: timeout() });
  await conflictAlert
    .getByText("文件已在外部修改，请比较或重新加载。", { exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await clickVerifiedControl(
    page,
    conflictAlert.getByRole("button", { name: "比较", exact: true }),
    deadline,
  );
  const conflictDiff = filesWorkspace.getByLabel("文件冲突比较", { exact: true });
  await conflictDiff.waitFor({ state: "visible", timeout: timeout() });
  await conflictDiff.locator(".cm-mergeView").waitFor({ state: "visible", timeout: timeout() });
  await clickVerifiedControl(
    page,
    conflictDiff.getByRole("button", { name: "返回编辑", exact: true }),
    deadline,
  );

  await clickVerifiedControl(
    page,
    conflictAlert.getByRole("button", { name: "另存为", exact: true }),
    deadline,
  );
  const saveAs = page.getByRole("dialog", { name: "另存为", exact: true });
  await saveAs.waitFor({ state: "visible", timeout: timeout() });
  await saveAs
    .getByRole("textbox", { name: "工作区相对路径", exact: true })
    .fill("sample.local-copy.ts");
  await clickVerifiedControl(
    page,
    saveAs.getByRole("button", { name: "保存副本", exact: true }),
    deadline,
  );
  await saveAs.waitFor({ state: "detached", timeout: timeout() });
  await waitForCondition(
    "冲突草稿另存为",
    () => fileTextEquals(join(directories.workspace, "sample.local-copy.ts"), localDraft),
    deadline,
    signal,
  );

  await clickVerifiedControl(
    page,
    filesWorkspace.locator('.ja-files-editor-tab button[title="sample.ts"]'),
    deadline,
  );
  await conflictAlert.waitFor({ state: "visible", timeout: timeout() });
  await clickVerifiedControl(
    page,
    conflictAlert.getByRole("button", { name: "重新加载", exact: true }),
    deadline,
  );
  await filesWorkspace
    .locator('.ja-files-editor-status[data-status="clean"]')
    .waitFor({ state: "visible", timeout: timeout() });
  await waitForCondition(
    "冲突重新加载",
    async () => (await codeMirrorText(editor)).includes("JA_EXTERNAL_WRITE"),
    deadline,
    signal,
  );

  const watcherContent = "export const watcher = 'JA_WATCHER_AUTO_READ';\n";
  const watcherBeforeAutoRead = await workspaceWatcherState(page);
  const watcherReadStartsBefore = await tauriInvokePhaseCount(
    page,
    "ja_workspace_read_file",
    "start",
  );
  const watcherReadResolvesBefore = await tauriInvokePhaseCount(
    page,
    "ja_workspace_read_file",
    "resolved",
  );
  await writeFile(samplePath, watcherContent, "utf8");
  await waitForCondition(
    "clean 文件 Watcher 事件",
    async () => {
      const current = await workspaceWatcherState(page);
      return current.sampleFileCount > watcherBeforeAutoRead.sampleFileCount;
    },
    deadline,
    signal,
  );
  await waitForCondition(
    "clean 文件 Watcher 权威读取启动",
    async () =>
      (await tauriInvokePhaseCount(page, "ja_workspace_read_file", "start")) >
      watcherReadStartsBefore,
    deadline,
    signal,
  );
  await waitForCondition(
    "clean 文件 Watcher 权威读取完成",
    async () =>
      (await tauriInvokePhaseCount(page, "ja_workspace_read_file", "resolved")) >
      watcherReadResolvesBefore,
    deadline,
    signal,
  );
  await waitForCondition(
    "clean 文件 Watcher 自动重读",
    async () => (await codeMirrorText(editor)).includes("JA_WATCHER_AUTO_READ"),
    deadline,
    signal,
  );

  const watcherBeforeFocus = await workspaceWatcherState(page);
  if (watcherBeforeFocus.generation <= 0)
    throw new Error("focus rescan 前缺少 watcher session generation");
  const minimizedResult = await invokeOwnedWindowAction(ownedWindow, "minimize", signal);
  if (minimizedResult.minimized !== true) throw new Error("Win32 未确认本次 Ja 窗口已经最小化");
  await page
    .waitForFunction(() => !globalThis.document.hasFocus(), undefined, {
      timeout: Math.min(3_000, timeout()),
    })
    .catch(() => undefined);
  const focusContent = "export const watcher = 'JA_FOCUS_RESCAN';\n";
  await writeFile(samplePath, focusContent, "utf8");
  const focusResult = await invokeOwnedWindowAction(ownedWindow, "focus", signal);
  // AppActivate/SetForegroundWindow 的返回值只是请求是否被立即接受；真实证据是
  // HWND 已成为 foreground 且随后 WebView document.hasFocus()，两者都必须成立。
  if (focusResult.minimized !== false || focusResult.foreground !== true) {
    throw new Error("Win32 未能把本次 Ja 窗口恢复为前台窗口");
  }
  await page.waitForFunction(() => globalThis.document.hasFocus(), undefined, {
    timeout: Math.min(5_000, timeout()),
  });
  let watcherAfterFocus = watcherBeforeFocus;
  await waitForCondition(
    "focus watchRescan event",
    async () => {
      watcherAfterFocus = await workspaceWatcherState(page);
      return watcherAfterFocus.count > watcherBeforeFocus.count;
    },
    deadline,
    signal,
  );
  const focusEventGenerations = watcherAfterFocus.generations.slice(watcherBeforeFocus.count);
  if (
    focusEventGenerations.length === 0 ||
    focusEventGenerations.some((generation) => generation !== watcherBeforeFocus.generation)
  ) {
    throw new Error("focus watchRescan 改变了 watcher session generation");
  }
  await waitForCondition(
    "focus rescan 文件重读",
    async () => (await codeMirrorText(editor)).includes("JA_FOCUS_RESCAN"),
    deadline,
    signal,
  );
  await maximizeOwnedNativeWindow(page, ownedWindow, deadline, signal);

  return {
    layout: {
      status: "passed",
      directoryVisible: true,
      fillsAvailableHeight: true,
      overflowOnlyWhenNeeded: true,
      initialViewportHeight: initialGeometry.viewportHeight,
      overflowScrollHeight: overflowGeometry.treeScrollHeight,
    },
    create: { status: "passed", file: true, directory: true },
    rename: "passed",
    internalMove: "passed",
    autosave: { status: "passed", debounceElapsedMs, earlyWriteGuardMs: 250, ctrlSave: true },
    conflict: { status: "passed", compare: true, reload: true, saveAs: "sample.local-copy.ts" },
    watcher: {
      status: "passed",
      cleanAutoRead: true,
      focusRescan: true,
      nativeFocus: focusResult.foreground === true,
    },
    trash,
    nativeDrop: {
      status: "blocked",
      reason:
        "CDP cannot create a Tauri native drag/drop event or Rust-issued one-shot dropToken; requires a real Explorer drag into Files and Terminal",
      syntheticEventUsed: false,
    },
  };
}

/** 返回活动 terminal tab button、panel 与稳定 id，供后续事务按同一身份验收。 */
async function activeTerminalTab(terminalWorkspace, deadline) {
  const tab = terminalWorkspace.locator('.ja-terminal-tabs [role="tab"][aria-selected="true"]');
  await tab.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const panelId = await tab.getAttribute("aria-controls");
  if (panelId === null || panelId.length === 0) throw new Error("活动终端 Tab 缺少 aria-controls");
  const panel = terminalWorkspace.locator(`[id="${panelId}"]`);
  await panel.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  return { tab, panel, panelId };
}

/** 拆分一个活动 pane，并返回新分配的 pane id，避免调用方推测布局身份。 */
async function splitTerminalPane(page, panel, paneId, orientation, deadline) {
  const before = await panel
    .locator(".ja-terminal-pane[data-pane-id]")
    .evaluateAll((panes) => panes.map((pane) => pane.getAttribute("data-pane-id")));
  const pane = panel.locator(`.ja-terminal-pane[data-pane-id="${paneId}"]`);
  await clickVerifiedControl(
    page,
    pane.getByRole("button", {
      name: orientation === "horizontal" ? "横向分屏" : "纵向分屏",
      exact: true,
    }),
    deadline,
  );
  await page.waitForFunction(
    ({ panelSelector, expected }) =>
      globalThis.document
        .querySelector(panelSelector)
        ?.querySelectorAll(".ja-terminal-pane[data-pane-id]").length === expected,
    { panelSelector: `[id="${await panel.getAttribute("id")}"]`, expected: before.length + 1 },
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  const after = await panel
    .locator(".ja-terminal-pane[data-pane-id]")
    .evaluateAll((panes) => panes.map((pane) => pane.getAttribute("data-pane-id")));
  const next = after.find((candidate) => candidate !== null && !before.includes(candidate));
  if (typeof next !== "string") throw new Error("终端分屏未生成新 pane identity");
  await panel
    .locator(`.ja-terminal-pane[data-pane-id="${next}"] .ja-terminal-pane-state`)
    .getByText("运行中", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  for (const candidate of after) {
    if (typeof candidate === "string") {
      await assertTerminalPaneGeometry(
        page,
        panel,
        candidate,
        `${orientation} 分屏后的终端 ${candidate}`,
        deadline,
      );
    }
  }
  return next;
}

/**
 * 若递归 split 布局中可见 xterm 裁切边界超出 pane、active panel 或 viewport，则拒绝。
 * Xterm 会将内部 screen 量化为整行，因此单轴 overscan 只有在真实 `.xterm` host
 * 对该轴实施裁切且渲染 screen 仍与 host 相交时才有效。
 */
async function assertTerminalPaneGeometry(page, panel, paneId, label, deadline) {
  const pane = panel.locator(`.ja-terminal-pane[data-pane-id="${paneId}"]`);
  let geometry = { valid: false, reason: "missing_surface" };
  while (Date.now() < deadline) {
    geometry = await pane.evaluate((pane) => {
      const panel = pane.closest('[role="tabpanel"]');
      const screen = pane.querySelector(".xterm-screen");
      const clippingOwner = screen?.closest(".xterm");
      if (
        !(panel instanceof globalThis.HTMLElement) ||
        !(screen instanceof globalThis.HTMLElement) ||
        !(clippingOwner instanceof globalThis.HTMLElement)
      ) {
        return { valid: false, reason: "missing_surface" };
      }
      const toObject = (rect) => ({
        left: rect.left,
        top: rect.top,
        right: rect.right,
        bottom: rect.bottom,
        width: rect.width,
        height: rect.height,
      });
      const panelRect = panel.getBoundingClientRect();
      const paneRect = pane.getBoundingClientRect();
      const clippingOwnerRect = clippingOwner.getBoundingClientRect();
      const screenRect = screen.getBoundingClientRect();
      const contains = (outer, inner) =>
        inner.left >= outer.left - 0.5 &&
        inner.top >= outer.top - 0.5 &&
        inner.right <= outer.right + 0.5 &&
        inner.bottom <= outer.bottom + 0.5;
      const viewport = {
        left: 0,
        top: 0,
        right: globalThis.innerWidth,
        bottom: globalThis.innerHeight,
      };
      const clippingStyle = globalThis.getComputedStyle(clippingOwner);
      const clips = (value) => value === "hidden" || value === "clip";
      const screenContainedHorizontally =
        screenRect.left >= clippingOwnerRect.left - 0.5 &&
        screenRect.right <= clippingOwnerRect.right + 0.5;
      const screenContainedVertically =
        screenRect.top >= clippingOwnerRect.top - 0.5 &&
        screenRect.bottom <= clippingOwnerRect.bottom + 0.5;
      const screenIntersection = {
        width: Math.max(
          0,
          Math.min(clippingOwnerRect.right, screenRect.right) -
            Math.max(clippingOwnerRect.left, screenRect.left),
        ),
        height: Math.max(
          0,
          Math.min(clippingOwnerRect.bottom, screenRect.bottom) -
            Math.max(clippingOwnerRect.top, screenRect.top),
        ),
      };
      return {
        valid:
          paneRect.width > 0 &&
          paneRect.height > 0 &&
          clippingOwnerRect.width > 0 &&
          clippingOwnerRect.height > 0 &&
          screenRect.width > 0 &&
          screenRect.height > 0 &&
          screenIntersection.width > 0 &&
          screenIntersection.height > 0 &&
          contains(panelRect, paneRect) &&
          contains(viewport, paneRect) &&
          contains(paneRect, clippingOwnerRect) &&
          contains(panelRect, clippingOwnerRect) &&
          contains(viewport, clippingOwnerRect) &&
          (screenContainedHorizontally || clips(clippingStyle.overflowX)) &&
          (screenContainedVertically || clips(clippingStyle.overflowY)),
        panel: toObject(panelRect),
        pane: toObject(paneRect),
        clippingOwner: toObject(clippingOwnerRect),
        screen: toObject(screenRect),
        screenIntersection,
        screenContainment: {
          horizontal: screenContainedHorizontally,
          vertical: screenContainedVertically,
        },
        clipping: {
          overflowX: clippingStyle.overflowX,
          overflowY: clippingStyle.overflowY,
        },
        viewport: { width: globalThis.innerWidth, height: globalThis.innerHeight },
      };
    });
    if (geometry.valid) return;
    await page.waitForTimeout(Math.min(50, Math.max(1, deadline - Date.now())));
  }
  throw new Error(`${label} 超出活动终端面板或 WebView 视口：${JSON.stringify(geometry)}`);
}

/**
 * 聚焦一个真实 xterm，并通过 Win32 `SendInput` 写入，使 PTY 测试覆盖桌面用户相同的
 * 原生键盘/IME 链路，而不是 CDP 键盘合成。
 */
async function writeTerminalCommand(
  page,
  panel,
  paneId,
  command,
  marker,
  identity,
  deadline,
  signal,
) {
  const pane = panel.locator(`.ja-terminal-pane[data-pane-id="${paneId}"]`);
  const terminal = pane.getByRole("application", { name: /^终端窗格 /u });
  const screen = terminal.locator(".xterm-screen");
  const inputTraceOffset = (await tauriInvokeTrace(page, "ja_terminal_input")).length;
  await assertTerminalPaneGeometry(page, panel, paneId, "写入命令前的活动终端", deadline);
  // Files/Editor 的 CDP 快捷键与 Win32 输入共享 WebView 键盘状态；在安装
  // xterm probe 前只发送 modifier key-up，避免残留 Ctrl 把 Enter 改写成 Ctrl+Enter。
  await invokeOwnedNativeInput(identity, { kind: "reset_modifiers" }, signal);
  await pane.evaluate((surface) => {
    const xterms = [...surface.querySelectorAll(".xterm")];
    const helpers = [...surface.querySelectorAll(".xterm-helper-textarea")];
    const input = surface
      .querySelector(".xterm-screen")
      ?.closest(".xterm")
      ?.querySelector(".xterm-helper-textarea");
    if (!(input instanceof globalThis.HTMLTextAreaElement))
      throw new Error("xterm helper textarea 缺失");
    const eventNames = [
      "keydown",
      "keypress",
      "keyup",
      "beforeinput",
      "input",
      "compositionstart",
      "compositionupdate",
      "compositionend",
    ];
    const probe = {
      xtermCount: xterms.length,
      helperCount: helpers.length,
      targetOwnerIndex: xterms.indexOf(input.closest(".xterm")),
      keydown: 0,
      keypress: 0,
      keyup: 0,
      beforeinput: 0,
      input: 0,
      compositionstart: 0,
      compositionupdate: 0,
      compositionend: 0,
      keySamples: [],
      defaultPrevented: Object.fromEntries(eventNames.map((eventName) => [eventName, 0])),
      modifiers: { ctrl: 0, alt: 0, shift: 0, meta: 0 },
    };
    for (const eventName of eventNames) {
      input.addEventListener(
        eventName,
        (event) => {
          probe[eventName] += 1;
          if (event.defaultPrevented) probe.defaultPrevented[eventName] += 1;
          if (event instanceof globalThis.KeyboardEvent) {
            if (probe.keySamples.length < 32)
              probe.keySamples.push({
                type: eventName,
                key: event.key,
                code: event.code,
                keyCode: event.keyCode,
                composing: event.isComposing,
              });
            if (event.ctrlKey) probe.modifiers.ctrl += 1;
            if (event.altKey) probe.modifiers.alt += 1;
            if (event.shiftKey) probe.modifiers.shift += 1;
            if (event.metaKey) probe.modifiers.meta += 1;
          }
        },
        { capture: true },
      );
    }
    globalThis.__JA_E2E_XTERM_INPUT_PROBE__ = probe;
  });
  await focusOwnedRendererTarget(page, identity, screen, "活动终端输入面", deadline, signal);
  await page.waitForFunction(
    (expectedPaneId) => {
      const active = globalThis.document.activeElement;
      return (
        active instanceof globalThis.HTMLTextAreaElement &&
        active.classList.contains("xterm-helper-textarea") &&
        active.closest(".ja-terminal-pane")?.getAttribute("data-pane-id") === expectedPaneId
      );
    },
    paneId,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  // terminal 命令是确定性的 ASCII 测试数据，并非 IME 验收样本。VK_PACKET 绕过用户当前 IME，
  // 但仍经过 Win32、WebView2、xterm textarea/onData、typed IPC 与真实 PTY。
  /** 在接受 Enter 或任何聚焦后例外前，要求完整输入命令已到达 owned xterm。 */
  const waitForCommandEcho = (echoDeadline) =>
    page.waitForFunction(
      ({ selector, expected }) => {
        const rows = globalThis.document.querySelector(selector)?.querySelectorAll(":scope > div");
        if (rows === undefined || rows.length === 0) return false;
        return [...rows]
          .map((row) => (row.textContent ?? "").trimEnd())
          .join("")
          .includes(expected);
      },
      { selector: `[data-pane-id="${paneId}"] .xterm-rows`, expected: command },
      { timeout: Math.max(1, echoDeadline - Date.now()) },
    );
  let textInput;
  let textAttempts = 0;
  let textEchoError;
  // 新拆分的 WebView2/xterm 可能比 VK_PACKET 到达 helper 提前一个原生消息轮次暴露 DOM focus。
  // 只允许在 Enter 前重试，并先通过 xterm/typed IPC 发送真实 Ctrl+C，
  // 避免部分输入与下一次尝试拼接或重复执行。
  for (let attempt = 1; attempt <= 3 && Date.now() < deadline; attempt += 1) {
    textAttempts = attempt;
    let candidate;
    try {
      candidate = await invokeOwnedNativeInput(
        identity,
        { kind: "terminal_text", text: command },
        signal,
      );
    } catch (error) {
      const message = String(error?.message ?? error);
      const foregroundWasStolenAfterInput =
        message.startsWith("Win32 SendInput 身份或前台证据无效：") &&
        message.includes('"kind":"terminal_text"') &&
        message.includes('"foreground":false') &&
        message.includes('"ownedHandle":');
      if (!foregroundWasStolenAfterInput) throw error;
      candidate = { kind: "terminal_text", foreground: false, downstreamEcho: true };
    }
    try {
      // VK_PACKET 变更通过 textarea reconciliation 到达 xterm。本地 PTY echo ACK
      // 可防止一次焦点竞态耗尽整轮预算。
      await waitForCommandEcho(Math.min(deadline, Date.now() + 3_000));
      textInput = candidate;
      textEchoError = undefined;
      break;
    } catch (error) {
      textEchoError = error;
    }
    if (attempt >= 3 || Date.now() >= deadline) break;
    await focusOwnedRendererTarget(
      page,
      identity,
      screen,
      "清理终端残留输入前的活动输入面",
      deadline,
      signal,
    );
    const clearInputResolvedBefore = await tauriInvokePhaseCount(
      page,
      "ja_terminal_input",
      "resolved",
    );
    await invokeOwnedNativeInput(
      identity,
      { kind: "chord", key: 0x43, modifiers: [0x11], repetitions: 1 },
      signal,
    );
    await waitForCondition(
      "终端 Ctrl+C 清理已进入 typed IPC",
      async () =>
        (await tauriInvokePhaseCount(page, "ja_terminal_input", "resolved")) >
        clearInputResolvedBefore,
      Math.min(deadline, Date.now() + 3_000),
      signal,
    );
    await focusOwnedRendererTarget(
      page,
      identity,
      screen,
      "重试终端文本前的活动输入面",
      deadline,
      signal,
    );
    await page.waitForFunction(
      (expectedPaneId) => {
        const active = globalThis.document.activeElement;
        return (
          active instanceof globalThis.HTMLTextAreaElement &&
          active.classList.contains("xterm-helper-textarea") &&
          active.closest(".ja-terminal-pane")?.getAttribute("data-pane-id") === expectedPaneId
        );
      },
      paneId,
      { timeout: Math.max(1, deadline - Date.now()) },
    );
    await invokeOwnedNativeInput(identity, { kind: "reset_modifiers" }, signal);
  }
  if (textInput === undefined) {
    const rendered = await pane.evaluate((surface, expected) => {
      const active = globalThis.document.activeElement;
      const rows = surface.querySelector(".xterm-rows");
      const rowTexts = rows === null ? [] : [...rows.children].map((row) => row.textContent ?? "");
      const logicalText = rowTexts.map((row) => row.trimEnd()).join("");
      return {
        lifecycle:
          surface.querySelector(".ja-terminal-pane-state")?.textContent?.trim() ?? "unknown",
        helperFocused:
          active instanceof globalThis.HTMLTextAreaElement &&
          active.classList.contains("xterm-helper-textarea") &&
          surface.contains(active),
        commandRendered: logicalText.includes(expected),
        renderedTextLength: rows?.textContent?.length ?? 0,
        rowCount: rows?.childElementCount ?? 0,
        helperValueLength:
          active instanceof globalThis.HTMLTextAreaElement &&
          active.classList.contains("xterm-helper-textarea")
            ? active.value.length
            : undefined,
        inputProbe: globalThis.__JA_E2E_XTERM_INPUT_PROBE__ ?? null,
      };
    }, command);
    const inputTrace = await tauriInvokeTrace(page, "ja_terminal_input")
      .then((trace) => trace.slice(inputTraceOffset))
      .catch(() => []);
    const inputTraceSummary = {
      count: inputTrace.length,
      phases: Object.fromEntries(
        ["start", "resolved", "rejected"].map((phase) => [
          phase,
          inputTrace.filter((entry) => entry.phase === phase).length,
        ]),
      ),
      dataLengths: [
        ...new Set(inputTrace.map((entry) => entry.dataLength).filter(Number.isSafeInteger)),
      ],
    };
    throw new Error(
      `终端文本未完整进入对应 PTY：${JSON.stringify({ textAttempts, inputTrace: inputTraceSummary, rendered })}`,
      { cause: textEchoError },
    );
  }
  let enterInput;
  let enterAttempts = 0;
  if (marker !== undefined) {
    let markerError;
    // Win32 可以在命令完整回显后、单次 Enter 到达前被短暂通知窗口抢走；每次
    // 重试只重新建立 xterm 焦点并发送 Enter，不重打命令，避免重复执行副作用。
    for (let attempt = 1; attempt <= 3 && Date.now() < deadline; attempt += 1) {
      enterAttempts = attempt;
      if (attempt > 1) {
        await focusOwnedRendererTarget(
          page,
          identity,
          screen,
          "重试终端 Enter 前的活动输入面",
          deadline,
          signal,
        );
        await page.waitForFunction(
          (expectedPaneId) => {
            const active = globalThis.document.activeElement;
            return (
              active instanceof globalThis.HTMLTextAreaElement &&
              active.classList.contains("xterm-helper-textarea") &&
              active.closest(".ja-terminal-pane")?.getAttribute("data-pane-id") === expectedPaneId
            );
          },
          paneId,
          { timeout: Math.max(1, deadline - Date.now()) },
        );
      }
      enterInput = await invokeOwnedNativeInput(
        identity,
        { kind: "chord", key: 0x0d, modifiers: [], repetitions: 1 },
        signal,
      );
      try {
        await page.waitForFunction(
          ({ selector, expected }) => {
            const rows = globalThis.document
              .querySelector(selector)
              ?.querySelectorAll(":scope > div");
            if (rows === undefined || rows.length === 0) return false;
            const logicalText = [...rows].map((row) => (row.textContent ?? "").trimEnd()).join("");
            return logicalText.includes(expected);
          },
          { selector: `[data-pane-id="${paneId}"] .xterm-rows`, expected: marker },
          { timeout: Math.max(1, Math.min(deadline, Date.now() + 3_000) - Date.now()) },
        );
        markerError = undefined;
        break;
      } catch (error) {
        markerError = error;
      }
    }
    if (markerError !== undefined) {
      const rendered = await pane.evaluate((surface, expected) => {
        const active = globalThis.document.activeElement;
        const rows = surface.querySelector(".xterm-rows");
        const rowTexts =
          rows === null ? [] : [...rows.children].map((row) => row.textContent ?? "");
        const logicalText = rowTexts.map((row) => row.trimEnd()).join("");
        return {
          lifecycle:
            surface.querySelector(".ja-terminal-pane-state")?.textContent?.trim() ?? "unknown",
          helperFocused:
            active instanceof globalThis.HTMLTextAreaElement &&
            active.classList.contains("xterm-helper-textarea") &&
            surface.contains(active),
          markerRendered: logicalText.includes(expected),
          rawMarkerRendered: rows?.textContent?.includes(expected) === true,
          renderedTextLength: rows?.textContent?.length ?? 0,
          rowCount: rows?.childElementCount ?? 0,
          nonEmptyRowLengths: rowTexts
            .map((row, index) => ({ index, rendered: row.length, logical: row.trimEnd().length }))
            .filter(({ logical }) => logical > 0),
          helperValueLength:
            active instanceof globalThis.HTMLTextAreaElement &&
            active.classList.contains("xterm-helper-textarea")
              ? active.value.length
              : undefined,
          inputProbe: globalThis.__JA_E2E_XTERM_INPUT_PROBE__ ?? null,
        };
      }, marker);
      const inputTrace = await tauriInvokeTrace(page, "ja_terminal_input")
        .then((trace) => trace.slice(inputTraceOffset))
        .catch(() => []);
      const inputTraceSummary = {
        count: inputTrace.length,
        phases: Object.fromEntries(
          ["start", "resolved", "rejected"].map((phase) => [
            phase,
            inputTrace.filter((entry) => entry.phase === phase).length,
          ]),
        ),
        dataLengths: [
          ...new Set(inputTrace.map((entry) => entry.dataLength).filter(Number.isSafeInteger)),
        ],
      };
      const nativeInput = {
        text: {
          kind: textInput.kind,
          foreground: textInput.foreground,
          downstreamEcho: textInput.downstreamEcho === true,
          length: command.length,
          attempts: textAttempts,
        },
        enter: {
          kind: enterInput?.kind ?? "not_sent",
          foreground: enterInput?.foreground === true,
          attempts: enterAttempts,
        },
      };
      throw new Error(
        `终端命令未形成对应 PTY 输出：${JSON.stringify({ inputTrace: inputTraceSummary, nativeInput, rendered })}`,
        { cause: markerError },
      );
    }
  } else {
    enterAttempts = 1;
    enterInput = await invokeOwnedNativeInput(
      identity,
      { kind: "chord", key: 0x0d, modifiers: [], repetitions: 1 },
      signal,
    );
  }
  return terminal;
}

/** 枚举已渲染 URL 字符中心，不依赖 xterm 私有 model，确保验收只观察公开画面。 */
async function terminalUrlPoints(terminal, url) {
  return terminal.evaluate((surface, expected) => {
    const rows = [...surface.querySelectorAll(".xterm-rows > div")];
    const segments = [];
    let logicalText = "";
    for (const row of rows) {
      const text = (row.textContent ?? "").trimEnd();
      segments.push({ row, text, start: logicalText.length });
      logicalText += text;
    }
    const matchStarts = [];
    let searchOffset = 0;
    while (searchOffset < logicalText.length) {
      const matchStart = logicalText.indexOf(expected, searchOffset);
      if (matchStart < 0) break;
      matchStarts.push(matchStart);
      searchOffset = matchStart + Math.max(1, expected.length);
    }
    if (matchStarts.length === 0) return [];
    const candidateOffsets = [
      ...new Set([
        Math.floor(expected.length / 2),
        Math.floor(expected.length / 3),
        0,
        Math.max(0, expected.length - 1),
      ]),
    ];
    const points = [];
    for (const matchStart of matchStarts) {
      for (const candidateOffset of candidateOffsets) {
        const target = matchStart + candidateOffset;
        const segment = segments.find(
          ({ start, text }) => target >= start && target < start + text.length,
        );
        if (segment === undefined) continue;
        const rowOffset = target - segment.start;
        const walker = globalThis.document.createTreeWalker(
          segment.row,
          globalThis.NodeFilter.SHOW_TEXT,
        );
        let textOffset = 0;
        while (walker.nextNode()) {
          const node = walker.currentNode;
          const length = node.textContent?.length ?? 0;
          if (rowOffset < textOffset + length) {
            const nodeOffset = rowOffset - textOffset;
            if (nodeOffset < 0 || nodeOffset >= length) break;
            const range = globalThis.document.createRange();
            range.setStart(node, nodeOffset);
            range.setEnd(node, nodeOffset + 1);
            const rect = range.getBoundingClientRect();
            if (rect.width > 0 && rect.height > 0)
              points.push({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 });
            break;
          }
          textOffset += length;
        }
      }
    }
    return points;
  }, url);
}

/**
 * 构造两个真实 terminal tab、每个含四个 pane，验证隐藏输出、键盘/search/link 语义，
 * 随后显式关闭一个 pane 与一个 tab。
 */
async function exerciseTerminalWorkspace(
  page,
  terminalWorkspace,
  previewFixture,
  nativeScope,
  deadline,
  signal,
) {
  const timeout = () => Math.max(1, deadline - Date.now());
  const ownedWindow = await resolveOwnedJaWindow(nativeScope, signal);
  const first = await activeTerminalTab(terminalWorkspace, deadline);
  let activePaneId = await first.panel
    .locator(".ja-terminal-pane[data-pane-id]")
    .first()
    .getAttribute("data-pane-id");
  if (activePaneId === null) throw new Error("首个终端窗格缺少 pane id");
  for (const orientation of ["horizontal", "vertical", "horizontal"]) {
    activePaneId = await splitTerminalPane(page, first.panel, activePaneId, orientation, deadline);
  }
  const firstPaneIds = await first.panel
    .locator(".ja-terminal-pane[data-pane-id]")
    .evaluateAll((panes) => panes.map((pane) => pane.getAttribute("data-pane-id")).sort());
  if (firstPaneIds.length !== 4) throw new Error("首个终端 Tab 未达到四 pane 上限");
  const perTabLimitControl = first.panel
    .locator(`.ja-terminal-pane[data-pane-id="${activePaneId}"]`)
    .getByRole("button", { name: /纵向分屏不可用/u });
  await perTabLimitControl.waitFor({ state: "visible", timeout: timeout() });
  if (await perTabLimitControl.isEnabled())
    throw new Error("单个终端 Tab 达到四 pane 后仍允许分屏");
  await waitForDelay(200, signal);
  if ((await first.panel.locator(".ja-terminal-pane[data-pane-id]").count()) !== 4)
    throw new Error("单个终端 Tab 超过四 pane 上限");

  // pane-limit 提交可能替换 toolbar 节点；当前语义按钮会在原生创建前打开产品拥有的 allow-list/cwd form。
  await terminalWorkspace
    .getByRole("button", { name: "新建终端标签页", exact: true })
    .click({ timeout: timeout() });
  const creator = terminalWorkspace.getByRole("dialog", { name: "新建终端", exact: true });
  await waitForRenderedSurface(creator, "新建终端表单", deadline, signal);
  await creator
    .getByRole("combobox", { name: "Shell profile", exact: true })
    .selectOption("power_shell");
  await creator.getByRole("textbox", { name: "工作目录", exact: true }).fill("e2e-move-target");
  await clickVerifiedControl(
    page,
    creator.getByRole("button", { name: "创建终端", exact: true }),
    deadline,
  );
  await creator.waitFor({ state: "detached", timeout: timeout() });
  await page.waitForFunction(
    () => globalThis.document.querySelectorAll('.ja-terminal-tabs [role="tab"]').length === 2,
    undefined,
    { timeout: timeout() },
  );
  const second = await activeTerminalTab(terminalWorkspace, deadline);
  let secondActivePane = await second.panel
    .locator(".ja-terminal-pane[data-pane-id]")
    .first()
    .getAttribute("data-pane-id");
  if (secondActivePane === null) throw new Error("第二个终端 Tab 缺少 pane id");
  await second.panel
    .locator(`.ja-terminal-pane[data-pane-id="${secondActivePane}"] .ja-terminal-pane-state`)
    .getByText("运行中", { exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await writeTerminalCommand(
    page,
    second.panel,
    secondActivePane,
    "if ((split-path -leaf $pwd) -eq 'e2e-move-target') { 'ja_terminal_relative_cwd_ok' } else { 'ja_terminal_relative_cwd_bad' }",
    "ja_terminal_relative_cwd_ok",
    ownedWindow,
    deadline,
    signal,
  );
  for (const orientation of ["vertical", "horizontal", "vertical"]) {
    secondActivePane = await splitTerminalPane(
      page,
      second.panel,
      secondActivePane,
      orientation,
      deadline,
    );
  }
  await page.waitForFunction(
    () => {
      const panes = [
        ...globalThis.document.querySelectorAll(
          ".ja-terminal-workspace .ja-terminal-pane[data-pane-id]",
        ),
      ];
      return (
        panes.length === 8 &&
        panes.every(
          (pane) => pane.querySelector(".ja-terminal-pane-state")?.textContent?.trim() === "运行中",
        )
      );
    },
    undefined,
    { timeout: timeout() },
  );
  const workspaceLimitControl = second.panel
    .locator(`.ja-terminal-pane[data-pane-id="${secondActivePane}"]`)
    .getByRole("button", { name: /横向分屏不可用/u });
  await workspaceLimitControl.waitFor({ state: "visible", timeout: timeout() });
  if (await workspaceLimitControl.isEnabled())
    throw new Error("终端工作区达到八 pane 后仍允许分屏");
  await waitForDelay(200, signal);
  if ((await terminalWorkspace.locator(".ja-terminal-pane[data-pane-id]").count()) !== 8)
    throw new Error("终端工作区超过八个 live pane 上限");

  let shellsAtLimit = [];
  await waitForCondition(
    "八个真实 PTY 进程",
    async () => {
      shellsAtLimit = await captureOwnedTerminalShells(nativeScope, signal);
      return shellsAtLimit.length === 8;
    },
    deadline,
    signal,
  );
  const nativeSessionsAtLimit = await terminalSessionIdentities(terminalWorkspace);
  if (
    nativeSessionsAtLimit.length !== 8 ||
    new Set(nativeSessionsAtLimit.map(({ sessionId }) => sessionId)).size !== 8
  ) {
    throw new Error(`八个 PTY 缺少唯一 native session identity：${nativeSessionsAtLimit.length}`);
  }

  // VK_PACKET 不能证明真实 Windows IME composition 链路。本 gate 继续使用 ASCII PTY marker；
  // 下方证据将 IME 明确标为 blocked，等待物理键盘/IME 验收会话。
  const searchMarker = "ja_terminal_search_input";
  const activeTerminal = await writeTerminalCommand(
    page,
    second.panel,
    secondActivePane,
    `echo ${searchMarker}`,
    searchMarker,
    ownedWindow,
    deadline,
    signal,
  );
  await page.keyboard.press("Control+F");
  const search = activeTerminal.getByRole("search");
  await search.waitFor({ state: "visible", timeout: timeout() });
  await search.getByRole("textbox", { name: "终端搜索", exact: true }).fill(searchMarker);
  await search.getByRole("textbox", { name: "终端搜索", exact: true }).press("Enter");
  let searchStatus = "";
  await waitForCondition(
    "终端搜索结果发布",
    async () => {
      searchStatus = (await search.locator('[aria-live="polite"]').innerText()).trim();
      return searchStatus.length > 0;
    },
    deadline,
    signal,
  );
  if (searchStatus === "未找到") throw new Error("终端搜索未定位真实 PTY 输出");
  await clickVerifiedControl(
    page,
    search.getByRole("button", { name: "关闭终端搜索", exact: true }),
    deadline,
  );
  await search.waitFor({ state: "detached", timeout: timeout() });

  const terminalUrl = `${previewFixture.url}terminal-link`;
  await writeTerminalCommand(
    page,
    second.panel,
    secondActivePane,
    `echo ${terminalUrl}`,
    terminalUrl,
    ownedWindow,
    deadline,
    signal,
  );
  const candidatePoints = await terminalUrlPoints(activeTerminal, terminalUrl);
  if (candidatePoints.length === 0) throw new Error("终端 URL 未形成可点击文本范围");
  const linkRenderer = await page.evaluate(() => ({
    width: globalThis.innerWidth,
    height: globalThis.innerHeight,
  }));
  await activeTerminal.evaluate((surface) => {
    const trace = [];
    for (const type of [
      "pointermove",
      "mousemove",
      "pointerdown",
      "mousedown",
      "pointerup",
      "mouseup",
      "click",
    ]) {
      surface.addEventListener(
        type,
        (event) => {
          if (trace.length >= 64 || !(event instanceof globalThis.MouseEvent)) return;
          trace.push({
            type,
            trusted: event.isTrusted,
            control: event.ctrlKey,
            button: event.button,
            defaultPrevented: event.defaultPrevented,
          });
        },
        { capture: true },
      );
    }
    globalThis.__JA_E2E_TERMINAL_LINK_TRACE__ = trace;
  });
  await suppressLoopbackExternalOpen(page, true);
  try {
    const openerBefore = await tauriInvokeCount(page, "plugin:opener|open_url");
    const point = candidatePoints[0];
    if (point === undefined) throw new Error("终端 URL 缺少可用字符坐标");
    await invokeOwnedNativeInput(
      ownedWindow,
      {
        kind: "move",
        x: point.x,
        y: point.y,
        rendererWidth: linkRenderer.width,
        rendererHeight: linkRenderer.height,
      },
      signal,
    );
    await waitForDelay(150, signal);
    await invokeOwnedNativeInput(
      ownedWindow,
      {
        kind: "click",
        x: point.x,
        y: point.y,
        rendererWidth: linkRenderer.width,
        rendererHeight: linkRenderer.height,
      },
      signal,
    );
    await waitForDelay(200, signal);
    if ((await tauriInvokeCount(page, "plugin:opener|open_url")) !== openerBefore)
      throw new Error("普通终端 URL 点击意外触发外部打开");
    await invokeOwnedNativeInput(
      ownedWindow,
      {
        kind: "click_chord",
        modifiers: [0x11],
        x: point.x,
        y: point.y,
        rendererWidth: linkRenderer.width,
        rendererHeight: linkRenderer.height,
      },
      signal,
    );
    try {
      await waitForCondition(
        "Ctrl+点击 URL 语义",
        async () => (await tauriInvokeCount(page, "plugin:opener|open_url", true)) > 0,
        deadline,
        signal,
      );
    } catch (error) {
      const linkTrace = await page.evaluate(() =>
        Array.isArray(globalThis.__JA_E2E_TERMINAL_LINK_TRACE__)
          ? globalThis.__JA_E2E_TERMINAL_LINK_TRACE__
          : [],
      );
      throw new Error(`Ctrl+点击 URL 未触发 opener：${JSON.stringify({ linkTrace })}`, {
        cause: error,
      });
    }
  } finally {
    await suppressLoopbackExternalOpen(page, false);
  }

  const beforeHidePaneIds = await terminalWorkspace
    .locator(".ja-terminal-pane[data-pane-id]")
    .evaluateAll((panes) => panes.map((pane) => pane.getAttribute("data-pane-id")).sort());
  const backgroundStartedMarker = "ja_terminal_background_started";
  const backgroundCompletedMarker = "ja_terminal_background_ok";
  await writeTerminalCommand(
    page,
    second.panel,
    secondActivePane,
    "powershell -nop -c \"'ja_terminal_'+'background_started';sleep -Milliseconds 900;'ja_terminal_'+'background_ok'\"",
    backgroundStartedMarker,
    ownedWindow,
    deadline,
    signal,
  );
  const hiddenPollsBefore = await tauriInvokePhaseCount(page, "ja_terminal_poll", "resolved");
  const hiddenAt = Date.now();
  const hiddenRemainingMs = deadline - hiddenAt;
  await chooseWorkbenchTool(page, "浏览器", deadline);
  // outer panel 为 `hidden` 时，xterm 可能延迟 DOM 绘制；产品合同要求继续原生轮询并缓冲输出，
  // 用户返回后准确渲染，而不是修改不可见 canvas。
  try {
    await waitForCondition(
      "隐藏终端继续后台 poll",
      async () =>
        Date.now() - hiddenAt >= 1_100 &&
        (await tauriInvokePhaseCount(page, "ja_terminal_poll", "resolved")) > hiddenPollsBefore,
      Math.min(deadline, hiddenAt + 15_000),
      signal,
    );
  } catch (error) {
    const pollPhases = Object.fromEntries(
      await Promise.all(
        ["start", "resolved", "rejected"].map(async (phase) => [
          phase,
          await tauriInvokePhaseCount(page, "ja_terminal_poll", phase),
        ]),
      ),
    );
    const terminalState = await page.evaluate(() => {
      const workbench = globalThis.document.querySelector(".ja-workbench");
      const surface = globalThis.document.querySelector(".ja-terminal-workspace");
      const terminalPanel = globalThis.document.querySelector('[data-tab-panel="terminal"]');
      return {
        workspacePresent: surface !== null,
        hidden: terminalPanel?.hasAttribute("hidden") ?? null,
        activeWorkbenchTab: workbench?.getAttribute("data-active-tab") ?? null,
        openWorkbenchTabs: workbench?.getAttribute("data-open-tabs") ?? null,
        renderedTabs: [
          ...globalThis.document.querySelectorAll(".ja-workbench-tab-shell[data-tab]"),
        ].map((tab) => tab.getAttribute("data-tab")),
        panes:
          surface === null
            ? []
            : [...surface.querySelectorAll(".ja-terminal-pane[data-pane-id]")].map((pane) => ({
                paneId: pane.getAttribute("data-pane-id"),
                lifecycle:
                  pane.querySelector(".ja-terminal-pane-state")?.textContent?.trim() ?? null,
              })),
      };
    });
    const sessions =
      (await terminalWorkspace.count()) === 1
        ? await terminalSessionIdentities(terminalWorkspace)
        : [];
    throw new Error(
      `隐藏终端继续后台 poll 失败：${JSON.stringify({ hiddenPollsBefore, pollPhases, hiddenRemainingMs, elapsedMs: Date.now() - hiddenAt, sessionCount: sessions.length, terminalState })}`,
      { cause: error },
    );
  }
  await chooseWorkbenchTool(page, "终端", deadline);
  await page.waitForFunction(
    (expected) =>
      [...globalThis.document.querySelectorAll(".ja-terminal-workspace .xterm-rows")].some(
        (rows) => rows.textContent?.includes(expected) === true,
      ),
    backgroundCompletedMarker,
    { timeout: timeout() },
  );
  const afterShowPaneIds = await terminalWorkspace
    .locator(".ja-terminal-pane[data-pane-id]")
    .evaluateAll((panes) => panes.map((pane) => pane.getAttribute("data-pane-id")).sort());
  if (JSON.stringify(beforeHidePaneIds) !== JSON.stringify(afterShowPaneIds))
    throw new Error("外层 Tab 隐藏后终端 pane identity 变化");

  await clickVerifiedControl(
    page,
    second.panel
      .locator(`.ja-terminal-pane[data-pane-id="${secondActivePane}"]`)
      .getByRole("button", { name: "关闭终端窗格", exact: true }),
    deadline,
  );
  await page.waitForFunction(
    (panelId) =>
      globalThis.document
        .getElementById(panelId)
        ?.querySelectorAll(".ja-terminal-pane[data-pane-id]").length === 3,
    second.panelId,
    { timeout: timeout() },
  );
  const secondTitle = (await second.tab.locator(".ja-terminal-tab-title").innerText()).trim();
  await clickVerifiedControl(
    page,
    terminalWorkspace.getByRole("button", { name: `关闭${secondTitle}`, exact: true }),
    deadline,
  );
  await page.waitForFunction(
    () =>
      globalThis.document.querySelectorAll('.ja-terminal-tabs [role="tab"]').length === 1 &&
      globalThis.document.querySelectorAll(".ja-terminal-workspace .ja-terminal-pane[data-pane-id]")
        .length === 4,
    undefined,
    { timeout: timeout() },
  );
  let remainingShells = [];
  await waitForCondition(
    "关闭 pane/tab 后仅保留四个 PTY",
    async () => {
      remainingShells = await captureOwnedTerminalShells(nativeScope, signal);
      return remainingShells.length === 4;
    },
    deadline,
    signal,
  );

  return {
    evidence: {
      status: "passed",
      tabsAtLimit: 2,
      panesAtLimit: 8,
      perTabLimit: 4,
      nestedOrientations: ["horizontal", "vertical"],
      preservedAcrossOuterTab: true,
      closedPaneAndTab: true,
      unicodeInput: {
        status: "blocked",
        focus: true,
        nativeImeComposition: "blocked_requires_real_keyboard_ime",
      },
      search: true,
      url: {
        ordinaryClickOpened: false,
        ctrlClickHandler: true,
        externalLaunch: "safely_suppressed_loopback",
      },
      nativeDrop: "blocked_with_files_native_drop",
      shellProcessesAtLimit: shellsAtLimit.length,
    },
    persistedPaneIds: firstPaneIds,
    openedSessionIds: nativeSessionsAtLimit.map(({ sessionId }) => sessionId),
    remainingShells,
  };
}

/** 覆盖真实 child 请求、前端 scheme 拒绝、loopback load failure 与同 session 恢复。 */
async function exerciseBrowserWorkspace(page, workbench, previewFixture, deadline, signal) {
  await chooseWorkbenchTool(page, "浏览器", deadline);
  const timeout = () => Math.max(1, deadline - Date.now());
  const address = workbench.getByRole("textbox", { name: "Preview 地址", exact: true });
  const navigate = workbench.getByRole("button", { name: "刷新或访问", exact: true });
  const before = previewFixture.requestCount();
  await address.fill(previewFixture.url);
  await clickVerifiedControl(page, navigate, deadline);
  await page.waitForFunction(
    (url) =>
      globalThis.document.querySelector(".ja-preview-viewport")?.getAttribute("data-url") === url,
    previewFixture.url,
    { timeout: timeout() },
  );
  await waitForCondition(
    "Preview 真实请求",
    () => previewFixture.requestCount() > before,
    deadline,
    signal,
  );

  const nativeNavigates = await tauriInvokeCount(page, "ja_preview_navigate");
  await address.fill("file:///C:/Windows/System32");
  await clickVerifiedControl(page, navigate, deadline);
  await workbench
    .getByRole("alert")
    .getByText("Preview 只支持 http:// 或 https:// 地址。", { exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  if ((await tauriInvokeCount(page, "ja_preview_navigate")) !== nativeNavigates)
    throw new Error("Preview 非 http(s) 输入越过前端策略进入 native navigate");

  const secondUrl = `${previewFixture.url}after-policy-error`;
  const afterPolicyCount = previewFixture.requestCount();
  await address.fill(secondUrl);
  await clickVerifiedControl(page, navigate, deadline);
  await page.waitForFunction(
    (url) =>
      globalThis.document.querySelector(".ja-preview-viewport")?.getAttribute("data-url") === url,
    secondUrl,
    { timeout: timeout() },
  );
  await waitForCondition(
    "Preview 策略错误后恢复导航",
    () => previewFixture.requestCount() > afterPolicyCount,
    deadline,
    signal,
  );

  const unavailablePort = await reservePort();
  const failureUrl = `http://127.0.0.1:${unavailablePort}/native-load-failure`;
  await address.fill(failureUrl);
  await clickVerifiedControl(page, navigate, deadline);
  await page.waitForFunction(
    (url) =>
      globalThis.document.querySelector(".ja-preview-viewport")?.getAttribute("data-url") === url,
    failureUrl,
    { timeout: timeout() },
  );
  const nativeFailure = workbench.locator('.ja-preview-error[role="alert"]');
  await nativeFailure.waitFor({ state: "visible", timeout: timeout() });
  if ((await nativeFailure.innerText()).includes("只支持 http://"))
    throw new Error("Preview native failure 被前端 scheme 错误冒充");

  const recoveryUrl = `${previewFixture.url}after-native-load-failure`;
  const recoveryCount = previewFixture.requestCount();
  await address.fill(recoveryUrl);
  await clickVerifiedControl(page, navigate, deadline);
  await page.waitForFunction(
    (url) =>
      globalThis.document.querySelector(".ja-preview-viewport")?.getAttribute("data-url") === url,
    recoveryUrl,
    { timeout: timeout() },
  );
  await waitForCondition(
    "Preview native failure 后恢复",
    () => previewFixture.requestCount() > recoveryCount,
    deadline,
    signal,
  );
  await nativeFailure.waitFor({ state: "detached", timeout: timeout() });
  return {
    status: "passed",
    realChildWebViewRequest: true,
    policyError: true,
    nativeLoadFailure: true,
    recovered: true,
  };
}

/** 快速 open/close 后连续创建十二轮 UUID child，以真实 ACK 暴露 registration slot 泄漏。 */
async function exercisePreviewShortcutRegistrationLifecycle(
  page,
  workbench,
  previewFixture,
  deadline,
  signal,
  recordStage,
) {
  const timeout = () => Math.max(1, deadline - Date.now());
  const stage = (name) => recordStage?.(`preview_shortcut_registration:${name}`);
  /** 关闭当前 child 并等待 native close ACK 与 target 消失。 */
  const closePreview = async (requireAck = true) => {
    const shell = workbench.locator('.ja-workbench-tab-shell[data-tab="preview"]');
    const url = await workbench.locator(".ja-preview-viewport").getAttribute("data-url");
    const before = await tauriInvokePhaseCount(page, "ja_preview_close", "resolved");
    await clickVerifiedControl(page, shell.locator('[data-tab-close="true"]'), deadline);
    await shell.waitFor({ state: "detached", timeout: timeout() });
    if (requireAck)
      await waitForCondition(
        "Preview close ACK",
        async () => (await tauriInvokePhaseCount(page, "ja_preview_close", "resolved")) > before,
        deadline,
        signal,
      );
    if (url)
      await waitForCondition(
        "Preview target 释放",
        () =>
          !page
            .context()
            .pages()
            .some(
              (candidate) => candidate !== page && !candidate.isClosed() && candidate.url() === url,
            ),
        deadline,
        signal,
      );
  };
  /** 从真实地址栏创建 child，并等待 open ACK、HTTP 请求和独立 target。 */
  const openPreview = async (url) => {
    await chooseWorkbenchTool(page, "浏览器", deadline);
    const before = await tauriInvokePhaseCount(page, "ja_preview_open", "resolved");
    const requests = previewFixture.requestCount();
    await workbench.getByRole("textbox", { name: "Preview 地址", exact: true }).fill(url);
    await clickVerifiedControl(
      page,
      workbench.getByRole("button", { name: "刷新或访问", exact: true }),
      deadline,
    );
    await waitForCondition(
      "Preview open ACK",
      async () => (await tauriInvokePhaseCount(page, "ja_preview_open", "resolved")) > before,
      deadline,
      signal,
    );
    await waitForCondition(
      "Preview churn request",
      () => previewFixture.requestCount() > requests,
      deadline,
      signal,
    );
    await waitForPreviewChildPage(page, url, deadline, signal);
  };
  stage("initial_close");
  await closePreview();
  stage("rapid_toggle");
  await chooseWorkbenchTool(page, "浏览器", deadline);
  const rapidClose = workbench.locator(
    '.ja-workbench-tab-shell[data-tab="preview"] [data-tab-close="true"]',
  );
  await workbench
    .getByRole("textbox", { name: "Preview 地址", exact: true })
    .fill(`${previewFixture.url}rapid-toggle`);
  await clickVerifiedControl(
    page,
    workbench.getByRole("button", { name: "刷新或访问", exact: true }),
    deadline,
  );
  // Native open 的 Promise settle 前可能重新渲染或滚动条带；导航前测得的点可能命中新移位 SVG，
  // 而非 close 控件，因此需在竞态窗口内复验当前最上层命中点。
  const point = await waitForRenderedSurface(rapidClose, "Preview rapid close", deadline, signal);
  await page.mouse.click(point.x, point.y);
  await workbench
    .locator('.ja-workbench-tab-shell[data-tab="preview"]')
    .waitFor({ state: "detached", timeout: timeout() });
  const openStart = await tauriInvokeCount(page, "ja_preview_open");
  for (let index = 0; index < nativeShortcutRegistrationChurnCycles; index += 1) {
    stage(`cycle_${index}_open`);
    await openPreview(`${previewFixture.url}native-shortcut-registration-${index}`);
    if (index < nativeShortcutRegistrationChurnCycles - 1) {
      stage(`cycle_${index}_close`);
      await closePreview();
    }
  }
  if (
    (await tauriInvokeCount(page, "ja_preview_open")) - openStart !==
    nativeShortcutRegistrationChurnCycles
  )
    throw new Error("Preview registration churn 未完成");
  return {
    status: "passed",
    rapidToggleRecovered: true,
    registrationChurnCycles: nativeShortcutRegistrationChurnCycles,
    registrationLimitLeakGuard: true,
    finalChildOpen: true,
  };
}

/** 只读取当前 UI preference 的 Workbench 投影，避免把无关 localStorage 内容带入证据。 */
async function readWorkbenchPreference(page) {
  return page.evaluate(() => {
    const raw = globalThis.localStorage.getItem("ja-ui-preferences-v10");
    const document = raw === null ? undefined : JSON.parse(raw);
    return {
      version: document?.version,
      tabs: document?.state?.rightPanelTabs,
      active: document?.state?.rightPanelTab,
      workbenchSize: document?.state?.workbenchSize,
      inspectorOpen: document?.state?.inspectorOpen,
    };
  });
}

/**
 * 在真实 WebView2 中验证分隔器的 pointer、键盘与持久化闭环；中栏和右栏必须保持几何相邻，
 * 当前 workbenchSize 是唯一尺寸事实，退役字段不能被兼容读取复活。
 */
async function verifyJoinedInspectorLayout(page, deadline, signal) {
  const workspaceLayout = page.locator("#ja-workspace-layout");
  const conversation = workspaceLayout.locator("#conversation");
  const workbench = workspaceLayout.locator("#workbench");
  const separator = page.getByRole("separator", { name: "调整工作台宽度", exact: true });
  await separator.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await waitForRenderedSurface(conversation, "中栏面板", deadline, signal);
  await waitForRenderedSurface(workbench, "右栏面板", deadline, signal);
  await waitForCondition(
    "中栏与右栏使用同一尺寸事实且无缝相邻",
    async () => {
      const current = await workspaceLayout.evaluate((layout) => {
        const conversationPanel = layout.querySelector("#conversation");
        const workbenchPanel = layout.querySelector("#workbench");
        if (
          !(conversationPanel instanceof globalThis.HTMLElement) ||
          !(workbenchPanel instanceof globalThis.HTMLElement)
        )
          return undefined;
        const conversationRect = conversationPanel.getBoundingClientRect();
        const workbenchRect = workbenchPanel.getBoundingClientRect();
        const layoutRect = layout.getBoundingClientRect();
        const configured = Number.parseFloat(
          globalThis.getComputedStyle(layout).getPropertyValue("--ja-workbench-size"),
        );
        return {
          gapPx: workbenchRect.left - conversationRect.right,
          cssGap: globalThis.getComputedStyle(layout).columnGap,
          workbenchRatio:
            layoutRect.width === 0 ? undefined : (workbenchRect.width / layoutRect.width) * 100,
          configured,
        };
      });
      return (
        current !== undefined &&
        Math.abs(current.gapPx) <= 0.75 &&
        (current.cssGap === "0px" || current.cssGap === "0") &&
        Number.isFinite(current.configured) &&
        current.configured >= 24 &&
        current.configured <= 60 &&
        current.workbenchRatio !== undefined &&
        Math.abs(current.workbenchRatio - current.configured) <= 0.1
      );
    },
    deadline,
    signal,
  );

  const beforeSize = Number(await separator.getAttribute("aria-valuenow"));
  const separatorBox = await separator.boundingBox();
  const layoutBox = await workspaceLayout.boundingBox();
  if (!Number.isFinite(beforeSize) || separatorBox === null || layoutBox === null)
    throw new Error("工作台分隔器缺少可测量的真实布局");
  const dragDistance = Math.min(96, Math.max(48, layoutBox.width * 0.07));
  const startX = separatorBox.x + separatorBox.width / 2;
  const startY = separatorBox.y + separatorBox.height / 2;
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(startX - dragDistance, startY, { steps: 8 });
  await page.mouse.up();
  await waitForCondition(
    "工作台 pointer resize 提交",
    async () => Number(await separator.getAttribute("aria-valuenow")) > beforeSize + 1,
    deadline,
    signal,
  );
  const resizedSize = Number(await separator.getAttribute("aria-valuenow"));

  await separator.focus();
  await page.keyboard.press("ArrowRight");
  await waitForCondition(
    "工作台键盘缩小",
    async () =>
      Math.abs(Number(await separator.getAttribute("aria-valuenow")) - (resizedSize - 0.5)) < 0.01,
    deadline,
    signal,
  );
  await page.keyboard.press("ArrowLeft");
  await waitForCondition(
    "工作台键盘恢复",
    async () =>
      Math.abs(Number(await separator.getAttribute("aria-valuenow")) - resizedSize) < 0.01,
    deadline,
    signal,
  );

  const snapshot = await workspaceLayout.evaluate((layout) => {
    const conversationPanel = layout.querySelector("#conversation");
    const workbenchPanel = layout.querySelector("#workbench");
    const conversationRect = conversationPanel?.getBoundingClientRect();
    const workbenchRect = workbenchPanel?.getBoundingClientRect();
    const layoutRect = layout.getBoundingClientRect();
    const raw = globalThis.localStorage.getItem("ja-ui-preferences-v10");
    const document = raw === null ? {} : JSON.parse(raw);
    const state = document?.state ?? {};
    const configuredSize = Number.parseFloat(
      globalThis.getComputedStyle(layout).getPropertyValue("--ja-workbench-size"),
    );
    return {
      gapPx:
        conversationRect === undefined || workbenchRect === undefined
          ? undefined
          : workbenchRect.left - conversationRect.right,
      workbenchRatio:
        workbenchRect === undefined || layoutRect.width === 0
          ? undefined
          : (workbenchRect.width / layoutRect.width) * 100,
      configuredSize,
      persistedSize: state.workbenchSize,
      preferenceVersion: document?.version,
      retiredInspectorSize: Object.hasOwn(state, "inspectorSize"),
      retiredInspectorRatio: Object.hasOwn(state, "inspectorRatio"),
      retiredConversationRatio: Object.hasOwn(state, "conversationRatio"),
    };
  });
  if (
    snapshot.retiredInspectorSize ||
    snapshot.retiredInspectorRatio ||
    snapshot.retiredConversationRatio
  )
    throw new Error(`右栏退役尺寸仍在偏好中：${JSON.stringify(snapshot)}`);
  if (
    snapshot.preferenceVersion !== 12 ||
    !Number.isFinite(snapshot.persistedSize) ||
    Math.abs(snapshot.persistedSize - snapshot.configuredSize) > 0.01 ||
    snapshot.workbenchRatio === undefined ||
    Math.abs(snapshot.workbenchRatio - snapshot.configuredSize) > 0.1 ||
    snapshot.gapPx === undefined ||
    Math.abs(snapshot.gapPx) > 0.75
  )
    throw new Error(`工作台 resize 未形成尺寸闭环：${JSON.stringify(snapshot)}`);
  return {
    status: "passed",
    separatorAvailable: true,
    resized: resizedSize > beforeSize,
    gapPx: snapshot.gapPx,
    workbenchRatio: snapshot.workbenchRatio,
    persistedSize: snapshot.persistedSize,
    retiredSizingRemoved: true,
  };
}

/**
 * 在打开项目 Workbench 后立即往返 general/project scope，使用户的退出项目路径不依赖
 * Files、Terminal 或原生快捷键等后续长流程；回到原项目后继续复用同一 durable thread。
 */
async function exerciseWorkspaceScopeRoundTrip(
  page,
  originalThreadId,
  projectThreadId,
  deadline,
  signal,
) {
  const timeout = () => Math.max(1, deadline - Date.now());
  const closeAllBefore = await tauriInvokeCount(page, "ja_terminal_close_all");
  await clickVerifiedControl(
    page,
    page.getByRole("button", { name: "切换到无项目对话", exact: true }),
    deadline,
  );
  await page
    .getByRole("button", { name: "当前范围：无项目对话", exact: true })
    .waitFor({ state: "visible", timeout: timeout() });
  await waitForCondition(
    "early general scope switch closeAll invoke",
    async () => (await tauriInvokeCount(page, "ja_terminal_close_all")) > closeAllBefore,
    deadline,
    signal,
  );
  await selectThreadById(page, originalThreadId, deadline, signal);
  await assertGeneralConversationScope(page, deadline);
  await selectProjectThreadById(page, projectThreadId, deadline, signal);
  await assertProjectConversationScope(page, deadline);
  return {
    status: "passed",
    generalScopeReached: true,
    originalGeneralThreadRestored: true,
    projectThreadRestored: true,
  };
}

/**
 * 通过真实 pointer handler 拖动外层 Tab，并等待 React 条带与当前 UiPreferences
 * 提交同一顺序。释放 pointer 可能先清除 `aria-grabbed`，因此不能把拖拽态清理当成 ACK。
 */
async function reorderWorkbenchTab(page, workbench, from, to, deadline, signal) {
  const source = workbench.locator(`.ja-workbench-tab-shell[data-tab="${from}"]`);
  const target = workbench.locator(`.ja-workbench-tab-shell[data-tab="${to}"]`);
  // 狭窄 scrollport 可能只露出 shell 尾部 close button。手势必须在语义 tab button 上开始和结束，
  // 使 E2E 遵循受支持 drag handle，绝不把 close 安全区误当成拖拽区。
  const sourceHandle = source.locator(`[data-workbench-tab="${from}"]`);
  const targetHandle = target.locator(`[data-workbench-tab="${to}"]`);
  // 条带在窄 workbench 上会刻意滚动，且没有 edge-drag auto-scroll。
  // 只有相邻两个 shell 都暴露真实最上层命中点后才重排，以匹配受支持用户交互，而非强制拖拽。
  await target.scrollIntoViewIfNeeded({ timeout: Math.max(1, deadline - Date.now()) });
  // 最后再露出 source：拖拽不能从只剩 close 尾部的裁切区域开始，
  // 同时相邻 target 必须留在同一有界条带内。
  await source.scrollIntoViewIfNeeded({ timeout: Math.max(1, deadline - Date.now()) });
  const orderBefore = await workbench
    .locator(".ja-workbench-tab-shell")
    .evaluateAll((tabs) => tabs.map((tab) => tab.getAttribute("data-tab")));
  const expectedOrder = [...orderBefore];
  const fromIndex = expectedOrder.indexOf(from);
  const toIndex = expectedOrder.indexOf(to);
  if (fromIndex < 0 || toIndex < 0 || fromIndex === toIndex)
    throw new Error(`工作区 Tab 拖序输入无效：${JSON.stringify({ from, to, orderBefore })}`);
  expectedOrder.splice(fromIndex, 1);
  expectedOrder.splice(toIndex, 0, from);
  const sourcePoint = await waitForRenderedSurface(
    sourceHandle,
    `工作区 ${from} Tab 拖动句柄`,
    deadline,
  );
  const targetPoint = await waitForRenderedSurface(
    targetHandle,
    `工作区 ${to} Tab 拖动句柄`,
    deadline,
  );
  await page.mouse.move(sourcePoint.x, sourcePoint.y);
  await page.mouse.down();
  await page.mouse.move(targetPoint.x, targetPoint.y, { steps: 6 });
  await page.mouse.up();
  await waitForCondition(
    `工作区 ${from}->${to} Tab 拖序提交`,
    async () => {
      const rendered = await workbench
        .locator(".ja-workbench-tab-shell")
        .evaluateAll((tabs) => tabs.map((tab) => tab.getAttribute("data-tab")));
      const persisted = await readWorkbenchPreference(page);
      return (
        globalThis.JSON.stringify(rendered) === globalThis.JSON.stringify(expectedOrder) &&
        globalThis.JSON.stringify(persisted.tabs) === globalThis.JSON.stringify(expectedOrder) &&
        (await workbench.locator('.ja-workbench-tab-shell[aria-grabbed="true"]').count()) === 0
      );
    },
    deadline,
    signal,
  );
}

/** 打开可能已持久化为收起状态的 inspector，避免依赖默认偏好。 */
async function ensureWorkbenchVisible(page, deadline) {
  const workbench = page.locator('.ja-inspector[aria-label="工作区面板"]');
  if ((await workbench.getAttribute("data-visible")) !== "true") {
    await clickVerifiedControl(
      page,
      page.getByRole("button", { name: "显示工作区面板", exact: true }),
      deadline,
    );
  }
  await workbench.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  return workbench;
}

/**
 * 覆盖无破坏抽屉转换、事务化终端关闭、休眠布局重开、关闭最后 Tab 与 v10 确定性恢复。
 */
async function exerciseOuterWorkbenchLifecycle(
  page,
  workbench,
  terminalWorkspace,
  remainingShells,
  priorNativeSessionIds,
  nativeScope,
  deadline,
  signal,
) {
  let launcher = await openWorkbenchLauncher(page, deadline);
  await clickVerifiedControl(
    page,
    workbench.getByRole("button", { name: "关闭新标签页", exact: true }),
    deadline,
  );
  const activeAfterClose = await workbench
    .locator('.ja-workbench-tab-shell[data-state="active"]')
    .getAttribute("data-tab");
  if (
    activeAfterClose === null ||
    activeAfterClose === "new" ||
    (await workbench.locator('.ja-workbench-tab-shell[data-tab="new"]').count()) !== 0
  ) {
    throw new Error("关闭活动 Tab 后没有选择相邻存活 Tab");
  }

  for (const capability of ["审查", "文件", "终端", "浏览器"])
    await chooseWorkbenchTool(page, capability, deadline);
  const orderBefore = await workbench
    .locator(".ja-workbench-tab-shell")
    .evaluateAll((tabs) => tabs.map((tab) => tab.getAttribute("data-tab")));
  await reorderWorkbenchTab(page, workbench, "preview", "terminal", deadline, signal);
  const orderAfter = await workbench
    .locator(".ja-workbench-tab-shell")
    .evaluateAll((tabs) => tabs.map((tab) => tab.getAttribute("data-tab")));
  if (JSON.stringify(orderBefore) === JSON.stringify(orderAfter))
    throw new Error("工作区 Tab 拖序没有改变持久化顺序");
  await assertOwnedIdentitiesAlive(remainingShells, signal, "外层能力切换");

  await chooseWorkbenchTool(page, "终端", deadline);
  await clickVerifiedControl(
    page,
    workbench.getByRole("button", { name: "收起右侧栏", exact: true }),
    deadline,
  );
  const drawerCollapsed = await waitForWorkbenchCollapsed(workbench, deadline, signal);
  await assertOwnedIdentitiesAlive(remainingShells, signal, "右侧栏收起");

  await ensureWorkbenchVisible(page, deadline);
  launcher = await openWorkbenchLauncher(page, deadline);
  await clickVerifiedControl(
    page,
    launcher.locator(".ja-workbench-launcher-action").filter({ hasText: "侧边聊天" }),
    deadline,
  );
  const sideChatCollapsed = await waitForWorkbenchCollapsed(workbench, deadline, signal);
  await page.waitForFunction(
    () => globalThis.document.activeElement?.getAttribute("aria-label") === "消息",
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  await assertOwnedIdentitiesAlive(remainingShells, signal, "侧边聊天收栏");

  await ensureWorkbenchVisible(page, deadline);
  const newTabCloseBeforeTerminal = workbench.getByRole("button", {
    name: "关闭新标签页",
    exact: true,
  });
  if ((await newTabCloseBeforeTerminal.count()) === 1)
    await clickVerifiedControl(page, newTabCloseBeforeTerminal, deadline);
  // dormant tab panel 刻意设置 aria-hidden，因此可见 role locator 在此解析为零；
  // 但已挂载 Terminal 仍然拥有其 PTY。
  const dormantTerminalWorkspace = workbench.locator(
    '[data-tab-panel="terminal"] .ja-terminal-workspace',
  );
  let persistedPaneIds = [];
  await waitForCondition(
    "显式关闭 Terminal 前 dormant 工作区稳定",
    async () => {
      if ((await dormantTerminalWorkspace.count()) !== 1) return false;
      persistedPaneIds = await dormantTerminalWorkspace
        .locator(".ja-terminal-pane[data-pane-id]")
        .evaluateAll((panes) => panes.map((pane) => pane.getAttribute("data-pane-id")).sort());
      return persistedPaneIds.length === 4;
    },
    deadline,
    signal,
  );

  const previewCloseBefore = await tauriInvokeCount(page, "ja_preview_close");
  await clickVerifiedControl(
    page,
    workbench.getByRole("button", { name: "关闭浏览器", exact: true }),
    deadline,
  );
  await page.waitForFunction(
    () => globalThis.document.querySelector('.ja-workbench-tab-shell[data-tab="preview"]') === null,
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  await waitForCondition(
    "Browser 外层 Tab 关闭 native WebView",
    async () => (await tauriInvokeCount(page, "ja_preview_close")) > previewCloseBefore,
    deadline,
    signal,
  );
  // outer-tab ownership 会在原生 close ACK 后卸载 PreviewPanel；
  // 若仍保留空 viewport，则属于陈旧 renderer 状态，而非成功 cleanup。
  await page.waitForFunction(
    () => globalThis.document.querySelector(".ja-preview-viewport") === null,
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );

  for (const [tab, label] of [
    ["review", "审查"],
    ["files", "文件"],
    ["new", "新标签页"],
  ]) {
    const close = workbench.getByRole("button", { name: `关闭${label}`, exact: true });
    if ((await close.count()) !== 1) continue;
    await clickVerifiedControl(page, close, deadline);
    await page.waitForFunction(
      (value) =>
        globalThis.document.querySelector(`.ja-workbench-tab-shell[data-tab="${value}"]`) === null,
      tab,
      { timeout: Math.max(1, deadline - Date.now()) },
    );
  }
  await chooseWorkbenchTool(page, "终端", deadline);
  const survivingTabs = await workbench
    .locator(".ja-workbench-tab-shell")
    .evaluateAll((tabs) => tabs.map((tab) => tab.getAttribute("data-tab")));
  if (JSON.stringify(survivingTabs) !== JSON.stringify(["terminal"]))
    throw new Error(`显式关闭前 Terminal 不是最后一个外层 Tab：${JSON.stringify(survivingTabs)}`);

  const closeTraceOffset = (await tauriInvokeTrace(page, "ja_terminal_close_all")).length;
  await injectNextTerminalCloseAllFailure(page);
  await clickVerifiedControl(
    page,
    workbench.getByRole("button", { name: "关闭终端", exact: true }),
    deadline,
  );
  await waitForCondition(
    "Terminal closeAll 注入失败",
    async () => {
      const phases = (await tauriInvokeTrace(page, "ja_terminal_close_all"))
        .slice(closeTraceOffset)
        .map(({ phase }) => phase);
      return phases.length >= 2 && phases[0] === "start" && phases[1] === "rejected";
    },
    deadline,
    signal,
  );
  const terminalCloseError = workbench
    .locator(".ja-feature-error")
    .filter({ hasText: "终端关闭失败，请重试。" });
  await terminalCloseError
    .getByText("终端关闭失败，请重试。", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  if ((await workbench.locator('.ja-workbench-tab-shell[data-tab="terminal"]').count()) !== 1)
    throw new Error("closeAll 失败后 Terminal Tab 被移除");
  const failedPreference = await readWorkbenchPreference(page);
  if (
    !Array.isArray(failedPreference.tabs) ||
    !failedPreference.tabs.includes("terminal") ||
    failedPreference.inspectorOpen !== true
  ) {
    throw new Error(`closeAll 失败后 Terminal 偏好未保留：${JSON.stringify(failedPreference)}`);
  }
  await assertOwnedIdentitiesAlive(remainingShells, signal, "Terminal closeAll 失败");
  const failedPaneIds = await terminalWorkspace
    .locator(".ja-terminal-pane[data-pane-id]")
    .evaluateAll((panes) => panes.map((pane) => pane.getAttribute("data-pane-id")).sort());
  if (JSON.stringify(failedPaneIds) !== JSON.stringify(persistedPaneIds))
    throw new Error("closeAll 失败后 Terminal pane identity 变化");

  await clickVerifiedControl(
    page,
    terminalCloseError.getByRole("button", { name: "重试", exact: true }),
    deadline,
  );
  let closePhases = [];
  await waitForCondition(
    "Terminal closeAll 重试成功",
    async () => {
      closePhases = (await tauriInvokeTrace(page, "ja_terminal_close_all"))
        .slice(closeTraceOffset)
        .map(({ phase }) => phase);
      return (
        JSON.stringify(closePhases) === JSON.stringify(["start", "rejected", "start", "resolved"])
      );
    },
    deadline,
    signal,
  );
  await waitForOwnedIdentitiesGone(remainingShells, deadline, signal);
  await page.waitForFunction(
    () =>
      globalThis.document.querySelector('.ja-workbench-tab-shell[data-tab="terminal"]') === null,
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  if ((await terminalWorkspace.count()) !== 0)
    throw new Error("Terminal 外层 Tab 成功关闭后工作区仍保持挂载");
  const collapsed = await waitForWorkbenchCollapsed(workbench, deadline, signal);
  const closedPreference = await readWorkbenchPreference(page);
  if (
    JSON.stringify(closedPreference.tabs) !== JSON.stringify([]) ||
    closedPreference.inspectorOpen !== false
  ) {
    throw new Error(`关闭最后一个 Terminal Tab 后偏好未收起：${JSON.stringify(closedPreference)}`);
  }

  await ensureWorkbenchVisible(page, deadline);
  launcher = await openWorkbenchLauncher(page, deadline);
  await clickVerifiedControl(
    page,
    launcher.locator(".ja-workbench-launcher-action").filter({ hasText: "终端" }),
    deadline,
  );
  await terminalWorkspace.waitFor({
    state: "visible",
    timeout: Math.max(1, deadline - Date.now()),
  });
  await page.waitForFunction(
    (paneCount) => {
      const panes = [
        ...globalThis.document.querySelectorAll(
          ".ja-terminal-workspace .ja-terminal-pane[data-pane-id]",
        ),
      ];
      return (
        panes.length === paneCount &&
        panes.every(
          (pane) => pane.querySelector(".ja-terminal-pane-state")?.textContent?.trim() === "运行中",
        )
      );
    },
    persistedPaneIds.length,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  const reopenedPaneIds = await terminalWorkspace
    .locator(".ja-terminal-pane[data-pane-id]")
    .evaluateAll((panes) => panes.map((pane) => pane.getAttribute("data-pane-id")).sort());
  if (JSON.stringify(reopenedPaneIds) !== JSON.stringify(persistedPaneIds))
    throw new Error("重开 Terminal 未恢复 dormant pane 布局");
  let reopenedNativeSessions = [];
  await waitForCondition(
    "重开 Terminal 创建全新 native session",
    async () => {
      reopenedNativeSessions = await terminalSessionIdentities(terminalWorkspace);
      return reopenedNativeSessions.length === persistedPaneIds.length;
    },
    deadline,
    signal,
  );
  const reopenedNativeSessionIds = reopenedNativeSessions.map(({ sessionId }) => sessionId);
  if (
    new Set(reopenedNativeSessionIds).size !== persistedPaneIds.length ||
    reopenedNativeSessionIds.some((sessionId) => priorNativeSessionIds.includes(sessionId))
  ) {
    throw new Error("重开 Terminal 复用了已关闭的 native session identity");
  }
  let reopenedShells = [];
  await waitForCondition(
    "重开 Terminal 创建四个真实 PTY",
    async () => {
      reopenedShells = await captureOwnedTerminalShells(nativeScope, signal);
      return reopenedShells.length === persistedPaneIds.length;
    },
    deadline,
    signal,
  );
  if (
    reopenedShells.some((entry) =>
      remainingShells.some((previous) => sameProcessIdentity(previous, entry)),
    )
  )
    throw new Error("重开 Terminal 复用了已关闭的 PTY identity");

  const newTabClose = workbench.getByRole("button", { name: "关闭新标签页", exact: true });
  if ((await newTabClose.count()) === 1) await clickVerifiedControl(page, newTabClose, deadline);
  await chooseWorkbenchTool(page, "文件", deadline);
  await chooseWorkbenchTool(page, "审查", deadline);
  await chooseWorkbenchTool(page, "终端", deadline);
  await reorderWorkbenchTab(page, workbench, "terminal", "files", deadline, signal);
  await chooseWorkbenchTool(page, "终端", deadline);
  const order = await workbench
    .locator(".ja-workbench-tab-shell")
    .evaluateAll((tabs) => tabs.map((tab) => tab.getAttribute("data-tab")));
  const active = await workbench
    .locator('.ja-workbench-tab-shell[data-state="active"]')
    .getAttribute("data-tab");
  const persisted = await readWorkbenchPreference(page);
  if (
    JSON.stringify(persisted.tabs) !== JSON.stringify(order) ||
    persisted.active !== active ||
    persisted.version !== 12
  ) {
    throw new Error(`当前工作区 Tab 偏好未同步：${JSON.stringify({ order, active, persisted })}`);
  }
  return {
    evidence: {
      activeClose: true,
      dragged: { before: orderBefore, after: orderAfter },
      ordinarySwitchPreservedPty: true,
      drawerCollapse: drawerCollapsed,
      sideChat: sideChatCollapsed,
      browserNativeClose: true,
      terminalExplicitClose: {
        failedAttemptRetainedTab: true,
        retrySucceeded: true,
        phases: closePhases,
        releasedPtyCount: remainingShells.length,
        restoredPaneCount: reopenedPaneIds.length,
        freshNativeSessionCount: reopenedNativeSessionIds.length,
      },
      closeLast: collapsed,
      preference: { order, active, version: persisted.version },
    },
    reopenedNativeSessionIds,
    reopenedShells,
  };
}

/**
 * 通过真实 WebView2 控件/native adapter 覆盖项目工作台；新增 hard reload 与 Win32
 * 快捷键阶段保持既有 Launcher/Review 截图顺序和 fixture 状态，最后仍按 durable thread 恢复。
 */
async function exerciseProjectWorkbench(
  page,
  deadline,
  directories,
  previewFixture,
  nativeScope,
  signal,
  recordStage,
) {
  const stage = (name) => recordStage?.(`workbench:${name}`);
  const stepDeadline = (duration = 30_000) => Math.min(deadline, Date.now() + duration);
  const timeout = (actionDeadline) => Math.max(1, actionDeadline - Date.now());
  stage("identify_original_thread");
  const originalThreadId = await currentThreadId(page, stepDeadline(), signal);
  stage("add_project");
  let actionDeadline = stepDeadline();
  const addProject = page.getByRole("button", { name: "添加项目", exact: true });
  await addProject.waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  await addProject.click();
  await page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]')
    .waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  await page.waitForFunction(
    (previous) => {
      const selected = globalThis.document.querySelector(
        '[aria-label="最近对话列表"] button[aria-current="page"]',
      );
      return selected?.getAttribute("data-thread-id") !== previous;
    },
    originalThreadId,
    { timeout: timeout(actionDeadline) },
  );
  const projectThreadId = await currentThreadId(page, stepDeadline(), signal);
  stage("open_drawer");
  actionDeadline = stepDeadline();
  const openWorkbench = page.getByRole("button", { name: "显示工作区面板", exact: true });
  await openWorkbench.waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  await openWorkbench.click();
  const workbench = page.locator('.ja-inspector[aria-label="工作区面板"]');
  await workbench.waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  stage("joined_inspector_layout");
  const inspectorLayout = await verifyJoinedInspectorLayout(page, stepDeadline(), signal);
  stage("scope_round_trip");
  actionDeadline = stepDeadline(45_000);
  const scopeRoundTrip = await exerciseWorkspaceScopeRoundTrip(
    page,
    originalThreadId,
    projectThreadId,
    actionDeadline,
    signal,
  );
  await workbench.waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  const ownedWindow = await resolveOwnedJaWindow(nativeScope, signal);
  stage("open_with_menu");
  actionDeadline = stepDeadline();
  const openWithTrigger = page.getByRole("button", { name: "打开方式", exact: true });
  if ((await openWithTrigger.count()) !== 1)
    throw new Error("中栏标题缺少唯一的工作区打开方式入口");
  await openWithTrigger.waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  await clickVerifiedControl(page, openWithTrigger, actionDeadline);
  const openWithMenu = page.locator('.ja-workspace-open-menu[role="menu"][data-state="open"]');
  await waitForRenderedSurface(openWithMenu, "打开方式菜单", actionDeadline, signal);
  const targetCount = await openWithMenu.getByRole("menuitem").count();
  if (targetCount < 1) throw new Error("打开方式白名单为空");
  const fileExplorer = openWithMenu
    .locator('[role="menuitem"]')
    .filter({ hasText: "文件资源管理器" });
  if ((await fileExplorer.count()) !== 1 || !(await fileExplorer.isEnabled()))
    throw new Error("文件资源管理器白名单目标不可用");
  stage("open_explorer");
  await clickVerifiedControl(page, fileExplorer, actionDeadline);
  await closeWorkspaceExplorerWindow(directories.workspace, signal);

  stage("summary");
  actionDeadline = stepDeadline();
  const summaryTrigger = page.getByRole("button", { name: "打开对话摘要", exact: true });
  await clickVerifiedControl(page, summaryTrigger, actionDeadline);
  const summary = page.getByRole("dialog", { name: "环境信息", exact: true });
  await summary.waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  await captureRendererVisualEvidenceAtViewport(
    page,
    `implementation-codex-workbench-summary-${visualTheme}-renderer-1280x720.png`,
    { width: 1280, height: 720 },
  );
  await clickVerifiedControl(page, summaryTrigger, actionDeadline);
  await summary.waitFor({ state: "detached", timeout: timeout(actionDeadline) });

  stage("native_maximize");
  actionDeadline = stepDeadline();
  let nativeViewport = await assertNativeMaximizedVisualViewport(page, actionDeadline);
  stage("launcher");
  actionDeadline = stepDeadline(60_000);
  await closeWorkbenchTabsExcept(page, workbench, ["review"], actionDeadline);
  const launcher = await openWorkbenchLauncher(page, actionDeadline);
  await launcher.waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  await ensureConversationSummaryOpen(page, actionDeadline);
  await captureNativeMaximizedVisualEvidence(
    page,
    `implementation-codex-workbench-launcher-${visualTheme}-native-2560x1392.png`,
    nativeViewport,
  );
  const launcherResponsive = await captureWorkbenchNativeMatrix(
    page,
    "launcher",
    ownedWindow,
    actionDeadline,
    signal,
  );
  nativeViewport = await assertNativeMaximizedVisualViewport(page, actionDeadline);

  stage("review");
  await clickVerifiedControl(
    page,
    launcher.locator(".ja-workbench-launcher-action").filter({ hasText: "审查" }),
    actionDeadline,
  );
  await page
    .locator('[data-tab-panel="review"]:not([hidden])')
    .waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  const review = await exerciseNativeReviewFlow(
    page,
    workbench,
    actionDeadline,
    directories.workspace,
    nativeViewport,
    () => ensureConversationSummaryOpen(page, actionDeadline),
  );

  // Preview 不依赖 Files/Terminal。先运行其真实 child-WebView 合同，
  // 可避免后续 watcher 或 PTY 失败遮蔽原生 navigation/load 证据；完整 workbench 矩阵仍会在下方执行。
  stage("browser");
  actionDeadline = stepDeadline(45_000);
  const browser = await exerciseBrowserWorkspace(
    page,
    workbench,
    previewFixture,
    actionDeadline,
    signal,
  );
  await captureRendererVisualEvidenceAtViewport(
    page,
    `implementation-codex-workbench-browser-${visualTheme}-renderer-1280x720.png`,
    { width: 1280, height: 720 },
  );
  stage("preview_shortcut_registration_lifecycle");
  actionDeadline = stepDeadline(150_000);
  const previewShortcutLifecycle = await exercisePreviewShortcutRegistrationLifecycle(
    page,
    workbench,
    previewFixture,
    actionDeadline,
    signal,
    stage,
  );

  stage("files");
  // 文件管理、冲突恢复与两次真实 watcher reconciliation 共用此阶段；
  // 即使 Windows Defender/IO 较慢，也必须到达每个有界断言，不能由最后一项检查耗尽全部预算。
  actionDeadline = stepDeadline(240_000);
  await chooseWorkbenchTool(page, "文件", actionDeadline);
  const filesWorkspace = workbench.getByRole("region", { name: "文件工作区", exact: true });
  await filesWorkspace.waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  const files = await exerciseFilesWorkspace(
    page,
    filesWorkspace,
    directories,
    ownedWindow,
    actionDeadline,
    signal,
  );
  await captureRendererVisualEvidenceAtViewport(
    page,
    `implementation-codex-workbench-file-editor-${visualTheme}-renderer-1280x720.png`,
    { width: 1280, height: 720 },
  );

  stage("file_search");
  actionDeadline = stepDeadline();
  await clickVerifiedControl(
    page,
    filesWorkspace.getByRole("tab", { name: "搜索", exact: true }),
    actionDeadline,
  );
  const searchInput = filesWorkspace.getByRole("searchbox", { name: "搜索工作区", exact: true });
  await searchInput.fill("Ja workbench");
  await filesWorkspace
    .getByRole("list", { name: "搜索结果", exact: true })
    .getByText("new-file.txt", { exact: true })
    .waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  await captureRendererVisualEvidenceAtViewport(
    page,
    `implementation-codex-workbench-files-${visualTheme}-renderer-1280x720.png`,
    { width: 1280, height: 720 },
  );

  stage("terminal");
  actionDeadline = stepDeadline(360_000);
  await chooseWorkbenchTool(page, "终端", actionDeadline);
  const terminalWorkspace = workbench.getByRole("region", { name: "终端工作区", exact: true });
  await terminalWorkspace.waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  const terminal = await exerciseTerminalWorkspace(
    page,
    terminalWorkspace,
    previewFixture,
    nativeScope,
    actionDeadline,
    signal,
  );
  await captureRendererVisualEvidenceAtViewport(
    page,
    `implementation-codex-workbench-terminal-split-${visualTheme}-renderer-1280x720.png`,
    { width: 1280, height: 720 },
  );

  stage("native_shortcuts");
  // 二十多个隐藏 PowerShell/SendInput 边界、两个真实焦点源与三个负向 barrier 被刻意串行化。
  // 此阶段必须低于 15 分钟总上限，也不能让进程启动在繁忙 Windows 桌面上耗尽
  // 最后一个 Preview chord 的可观察事件窗口。
  actionDeadline = stepDeadline(240_000);
  const nativeShortcuts = await exerciseNativeShortcuts(
    page,
    workbench,
    terminalWorkspace,
    ownedWindow,
    actionDeadline,
    signal,
  );

  stage("outer_tab_lifecycle");
  actionDeadline = stepDeadline(60_000);
  const tabLifecycle = await exerciseOuterWorkbenchLifecycle(
    page,
    workbench,
    terminalWorkspace,
    terminal.remainingShells,
    terminal.openedSessionIds,
    nativeScope,
    actionDeadline,
    signal,
  );

  stage("restore_general");
  actionDeadline = stepDeadline(45_000);
  const closeAllBefore = await tauriInvokeCount(page, "ja_terminal_close_all");
  await clickVerifiedControl(
    page,
    page.getByRole("button", { name: "切换到无项目对话", exact: true }),
    actionDeadline,
  );
  await page
    .getByRole("button", { name: "当前范围：无项目对话", exact: true })
    .waitFor({ state: "visible", timeout: timeout(actionDeadline) });
  await waitForCondition(
    "explicit general scope switch closeAll invoke",
    async () => (await tauriInvokeCount(page, "ja_terminal_close_all")) > closeAllBefore,
    actionDeadline,
    signal,
  );
  await selectThreadById(page, originalThreadId, actionDeadline, signal);
  await waitForOwnedIdentitiesGone(tabLifecycle.reopenedShells, actionDeadline, signal);
  await assertGeneralConversationScope(page, actionDeadline);
  stage("native_shortcut_hard_reload");
  actionDeadline = stepDeadline(60_000);
  const nativeShortcutHardReload = await exerciseNativeShortcutHardReload(
    page,
    workbench,
    ownedWindow,
    actionDeadline,
    signal,
  );
  stage("completed");
  return {
    openTargets: targetCount,
    projectThreadId,
    review,
    launcher: true,
    launcherResponsive,
    files,
    search: true,
    terminal: {
      ...terminal.evidence,
      persistedPaneIds: terminal.persistedPaneIds,
      workspaceSwitchCloseAll: true,
    },
    browser,
    nativeShortcuts: { ...nativeShortcuts, hardReload: nativeShortcutHardReload },
    previewShortcutLifecycle,
    tabs: tabLifecycle.evidence,
    explorer: true,
    explicitGeneralScopeReturn: true,
    scopeRoundTrip,
    inspectorLayout,
    nativeViewport,
    originalThreadId,
    restartTerminalSessionIds: tabLifecycle.reopenedNativeSessionIds,
  };
}

/**
 * 通过真实 Settings 表单创建首个 Provider 与模型；密钥只进入 password input，
 * 保存后必须立即清空并只留下“已配置”投影，避免页面验收依赖预写配置或 Secret 回显。
 */
async function configureRealProviderThroughUi(page, providerConfig, deadline) {
  const timeout = () => Math.max(1, deadline - Date.now());
  const settings = page.getByRole("region", { name: "设置页面", exact: true });
  await settings.waitFor({ state: "visible", timeout: timeout() });
  await settings.getByRole("button", { name: "新增 Provider", exact: true }).click();
  await settings.getByLabel("服务商名称", { exact: true }).fill("E2E Real Provider");
  if (providerConfig.api === "anthropic_messages") {
    await settings.getByRole("combobox", { name: "服务商", exact: true }).click();
    await page.getByRole("option", { name: "Anthropic", exact: true }).click();
  }
  const apiLabel =
    providerConfig.api === "anthropic_messages" ? "Anthropic Messages" : "OpenAI Responses";
  await settings.getByRole("combobox", { name: "接口", exact: true }).click();
  await page.getByRole("option", { name: apiLabel, exact: true }).click();
  await settings.getByLabel("Base URL", { exact: true }).fill(providerConfig.baseUrl);
  await settings.getByLabel("首个模型名称", { exact: true }).fill("E2E Real Model");
  await settings.getByLabel("上游模型", { exact: true }).fill(providerConfig.model);
  await settings.getByRole("button", { name: "保存 Provider", exact: true }).click();
  await settings.getByText("E2E Real Provider", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(),
  });
  const keyInput = settings.getByLabel("API key / token", { exact: true });
  if ((await keyInput.getAttribute("type")) !== "password") {
    throw new Error("Provider API Key 输入框不是 password 类型");
  }
  await keyInput.fill(providerConfig.apiKey);
  await settings.getByRole("button", { name: "保存或替换密钥", exact: true }).click();
  await settings.getByRole("status").filter({ hasText: "密钥已保存到系统凭据库" }).waitFor({
    state: "visible",
    timeout: timeout(),
  });
  if ((await keyInput.inputValue()) !== "") {
    throw new Error("模型 API Key 保存后仍残留在 WebView 输入框");
  }
  await settings.getByText("已配置 · 密钥不会回显", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(),
  });
  await settings.getByRole("button", { name: "返回对话", exact: true }).click();
}

/** 验证 model editor 只回显已配置状态，password input 必须保持空值。 */
async function assertRedactedModelCredential(page, deadline) {
  const timeout = () => Math.max(1, deadline - Date.now());
  await page.getByRole("button", { name: "设置", exact: true }).click();
  const settings = page.getByRole("region", { name: "设置页面", exact: true });
  await settings.waitFor({ state: "visible", timeout: timeout() });
  await settings.getByText("已配置 · 密钥不会回显", { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(),
  });
  await settings.getByText("E2E Real Provider", { exact: true }).first().click();
  const keyInput = settings.getByLabel("API key / token", { exact: true });
  await keyInput.waitFor({ state: "visible", timeout: timeout() });
  if (
    (await keyInput.getAttribute("type")) !== "password" ||
    (await keyInput.inputValue()) !== ""
  ) {
    throw new Error("模型 API Key 编辑器泄漏了已保存凭据");
  }
  await settings.getByRole("button", { name: "返回对话", exact: true }).click();
  await conversationSurface(page).waitFor({ state: "visible", timeout: timeout() });
}

/**
 * 在页面创建出的真实项目 Thread 中要求模型读取业务文件，并同时验证 Tool 投影、最终答案与
 * SQLite 可恢复时间线；固定输出来自文件而非 prompt，避免把普通文本回声误报为 Agent 闭环。
 */
async function runProjectBusinessConversation(page, workbench, deadline, directories, signal) {
  const turnDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  await selectProjectThreadById(page, workbench.projectThreadId, turnDeadline, signal);
  await assertProjectConversationScope(page, turnDeadline);
  await waitForComposerAdmission(page, turnDeadline);
  await page.getByRole("textbox", { name: "消息" }).fill(businessPrompt);
  await page.getByRole("button", { name: "发送" }).click();
  const turnRow = page.locator(".ja-chat-timeline__row").filter({ hasText: businessPrompt });
  await turnRow
    .locator('.ja-chat-message-user[data-item-id^="item_"]')
    .waitFor({ state: "visible", timeout: Math.max(1, turnDeadline - Date.now()) });
  await waitForRealProviderFinal(page, businessExpectedFinal, turnDeadline, directories, signal);
  await waitForTurnRowConvergence(
    page,
    businessPrompt,
    businessExpectedFinal,
    turnDeadline,
    signal,
  );
  const process = turnRow.locator(".ja-work-process");
  await process.waitFor({ state: "visible", timeout: Math.max(1, turnDeadline - Date.now()) });
  const steps = process.locator(".ja-work-step");
  if ((await steps.count()) === 0) {
    await process.locator(".ja-work-process__trigger").click();
    await steps.first().waitFor({
      state: "visible",
      timeout: Math.max(1, turnDeadline - Date.now()),
    });
  }
  const stepTexts = await steps.allInnerTexts();
  if (!stepTexts.some((text) => /\bread\b/u.test(text))) {
    throw new Error(`项目业务问答未观察到 read 工具：${JSON.stringify(stepTexts)}`);
  }
  return {
    input: businessPrompt,
    expectedFinal: businessExpectedFinal,
    timelineText: redact(
      await page.getByRole("region", { name: "对话时间线" }).innerText(),
      directories,
    ),
    conversationScope: "project",
  };
}

/**
 * 向已验证控件发送一次真实 pointer click。Approval button 会立即替换为 loading/resolved DOM；
 * 使用冻结命中点可避免 locator 对正确消失的节点重试，后续产品状态断言仍作为最终裁决。
 */
async function clickVerifiedControl(page, locator, deadline) {
  await locator.waitFor({ state: "attached", timeout: Math.max(1, deadline - Date.now()) });
  if (!(await locator.isEnabled())) {
    throw new Error("E2E control is visible but disabled");
  }
  // 横向条带中的 tab 可能有效，但部分被固定 launcher 控件裁切。先滚动真实 DOM container；
  // 随后的最上层 hit-test 仍会拒绝任何剩余重叠。
  await locator.scrollIntoViewIfNeeded({ timeout: Math.max(1, deadline - Date.now()) });
  const hit = await waitForRenderedSurface(locator, "E2E control", deadline);
  await page.mouse.click(hit.x, hit.y);
}

/**
 * 通过真实“新会话”控件创建一个 Thread，并等待列表数量、active identity 与旧 Thread 同时变化；
 * 不调用隐藏 RPC，确保后续标题断言覆盖和用户相同的创建路径。
 */
async function createConversationThread(page, deadline, signal) {
  const previousThreadId = await currentThreadId(page, deadline, signal);
  const rows = page.getByRole("list", { name: "最近对话列表" }).locator("button[data-thread-id]");
  const countBefore = await rows.count();
  const create = page.locator('button[aria-label="新会话"]');
  await create.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await page.waitForFunction(
    () => {
      const button = globalThis.document.querySelector('button[aria-label="新会话"]');
      return button instanceof globalThis.HTMLButtonElement && !button.disabled;
    },
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  await create.click();
  await page.waitForFunction(
    ({ previous, before }) => {
      const candidates = Array.from(
        globalThis.document.querySelectorAll('[aria-label="最近对话列表"] button[data-thread-id]'),
      );
      const active = candidates.find(
        (candidate) => candidate.getAttribute("aria-current") === "page",
      );
      return candidates.length >= before + 1 && active?.getAttribute("data-thread-id") !== previous;
    },
    { previous: previousThreadId, before: countBefore },
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  return currentThreadId(page, deadline, signal);
}

/** 读取侧栏指定 Thread 的可见标题，忽略状态圆点与完成状态文案。 */
async function sidebarThreadTitle(page, threadId) {
  const row = page.locator(`button[data-thread-id="${threadId}"]`);
  if ((await row.count()) !== 1) return undefined;
  return row.locator("span:not(.ja-navigation-thread-dot)").first().textContent();
}

/** 等待 active Thread 的侧栏与页头同时显示同一权威标题。 */
async function assertVisibleConversationTitle(page, threadId, expected, deadline, signal) {
  await waitForCondition(
    `会话标题 ${expected}`,
    async () => {
      const [sidebar, heading] = await Promise.all([
        sidebarThreadTitle(page, threadId),
        page
          .locator(".ja-conversation-heading strong")
          .textContent()
          .catch(() => undefined),
      ]);
      return sidebar?.trim() === expected && heading?.trim() === expected;
    },
    deadline,
    signal,
  );
}

/** 等待 loopback fixture 观察到精确数量的生产 `/responses` 交换。 */
async function waitForTitleFixtureAttempts(fixture, scenarioId, kind, expected, deadline, signal) {
  await waitForCondition(
    `${scenarioId} ${kind} attempt=${expected}`,
    () => fixture.attemptCount(scenarioId, kind) === expected,
    deadline,
    signal,
  );
}

/** 等待一次 Provider 交换已响应或被取消，避免以 HTTP 请求到达误当应用层已收敛。 */
async function waitForTitleFixtureFinished(fixture, scenarioId, kind, expected, deadline, signal) {
  await waitForCondition(
    `${scenarioId} ${kind} finished=${expected}`,
    () => fixture.finishedCount(scenarioId, kind) === expected,
    deadline,
    signal,
  );
}

/**
 * 在明确的负向观察窗口内拒绝额外 Provider 请求；该等待用于证明 SINGLE_ATTEMPT 与取消后不补生成，
 * 不是用固定 sleep 猜测 UI 完成时间。
 */
async function assertNoAdditionalTitleAttempts(
  fixture,
  scenarioId,
  kind,
  expected,
  durationMs,
  signal,
) {
  const deadline = Date.now() + durationMs;
  while (Date.now() < deadline) {
    throwIfAborted(signal);
    const actual = fixture.attemptCount(scenarioId, kind);
    if (actual !== expected) {
      throw new Error(`${scenarioId} ${kind} 产生额外请求：expected=${expected} actual=${actual}`);
    }
    await waitForDelay(Math.min(100, Math.max(1, deadline - Date.now())), signal);
  }
}

/** 在迟到自动结果处理窗口内持续验证人工标题和元数据事件数均未改变。 */
async function assertManualTitleRemainsAuthoritative(
  page,
  threadId,
  expected,
  metadataCount,
  durationMs,
  signal,
) {
  const deadline = Date.now() + durationMs;
  while (Date.now() < deadline) {
    throwIfAborted(signal);
    const [sidebar, heading, events] = await Promise.all([
      sidebarThreadTitle(page, threadId),
      page.locator(".ja-conversation-heading strong").textContent(),
      captureRawTauriEvents(page),
    ]);
    const currentMetadataCount = events.filter(
      (event) => event.method === "thread/metadata-changed" && event.threadId === threadId,
    ).length;
    if (
      sidebar?.trim() !== expected ||
      heading?.trim() !== expected ||
      currentMetadataCount !== metadataCount
    ) {
      throw new Error("迟到自动标题覆盖了人工标题或发布了额外元数据事件");
    }
    await waitForDelay(Math.min(100, Math.max(1, deadline - Date.now())), signal);
  }
}

/** 关闭搜索 Dialog 并等待 portal 卸载，避免后续标题场景把焦点留在隐藏输入中。 */
async function closeConversationSearch(page, deadline) {
  const input = page.getByRole("searchbox", { name: "搜索对话" });
  if ((await input.count()) === 0) return;
  await input.press("Escape");
  await input.waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
}

/**
 * 在自动标题请求被 loopback 门阻塞时打开真实搜索 Dialog；释放后验证结果静默刷新且不丢失
 * query、DOM focus、active descendant、选中项和 Dialog/行几何。
 */
async function assertSearchRefreshDuringAutomaticTitle(
  page,
  fixture,
  threadId,
  scenario,
  deadline,
  signal,
) {
  await clickVerifiedControl(
    page,
    page.getByRole("button", { name: "搜索对话", exact: true }),
    deadline,
  );
  const input = page.getByRole("searchbox", { name: "搜索对话" });
  await input.fill(scenario.searchQuery);
  const provisional = page.getByRole("option").filter({ hasText: scenario.prompt });
  await provisional.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const before = await page.evaluate(() => {
    const inputElement = globalThis.document.querySelector('input[aria-label="搜索对话"]');
    const dialog = globalThis.document.querySelector(".ja-conversation-search-dialog");
    const selected = globalThis.document.querySelector('[role="option"][aria-selected="true"]');
    const rect = (element) => {
      const value = element?.getBoundingClientRect();
      return value === undefined
        ? undefined
        : { x: value.x, y: value.y, width: value.width, height: value.height };
    };
    return {
      value: inputElement instanceof globalThis.HTMLInputElement ? inputElement.value : undefined,
      focused: globalThis.document.activeElement === inputElement,
      activeDescendant: inputElement?.getAttribute("aria-activedescendant"),
      selectedId: selected?.id,
      dialog: rect(dialog),
      row: rect(selected),
    };
  });
  if (
    before.value !== scenario.searchQuery ||
    !before.focused ||
    before.activeDescendant === null ||
    before.activeDescendant !== before.selectedId
  ) {
    throw new Error("自动标题前搜索输入、焦点或选中项不稳定");
  }

  fixture.release(scenario.id, "title");
  await waitForTitleFixtureFinished(fixture, scenario.id, "title", 1, deadline, signal);
  await assertVisibleConversationTitle(page, threadId, scenario.automaticTitle, deadline, signal);
  const updated = page.getByRole("option").filter({ hasText: scenario.automaticTitle });
  await updated.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await page
    .getByLabel("正在搜索")
    .waitFor({
      state: "detached",
      timeout: Math.max(1, deadline - Date.now()),
    })
    .catch(async () => {
      if ((await page.getByLabel("正在搜索").count()) !== 0)
        throw new Error("标题刷新后搜索仍在 loading");
    });
  const after = await page.evaluate(() => {
    const inputElement = globalThis.document.querySelector('input[aria-label="搜索对话"]');
    const dialog = globalThis.document.querySelector(".ja-conversation-search-dialog");
    const selected = globalThis.document.querySelector('[role="option"][aria-selected="true"]');
    const rect = (element) => {
      const value = element?.getBoundingClientRect();
      return value === undefined
        ? undefined
        : { x: value.x, y: value.y, width: value.width, height: value.height };
    };
    return {
      value: inputElement instanceof globalThis.HTMLInputElement ? inputElement.value : undefined,
      focused: globalThis.document.activeElement === inputElement,
      activeDescendant: inputElement?.getAttribute("aria-activedescendant"),
      selectedId: selected?.id,
      dialog: rect(dialog),
      row: rect(selected),
    };
  });
  const sameRect = (left, right) =>
    left !== undefined &&
    right !== undefined &&
    ["x", "y", "width", "height"].every((key) => Math.abs(left[key] - right[key]) <= 1);
  if (
    after.value !== scenario.searchQuery ||
    !after.focused ||
    after.activeDescendant !== before.activeDescendant ||
    after.selectedId !== before.selectedId ||
    !sameRect(before.dialog, after.dialog) ||
    !sameRect(before.row, after.row)
  ) {
    throw new Error("自动标题刷新丢失搜索状态或引发布局跳动");
  }
  await closeConversationSearch(page, deadline);
  return {
    queryPreserved: true,
    focusPreserved: true,
    selectionPreserved: true,
    layoutStable: true,
  };
}

/** 通过侧栏菜单完成一次人工重命名并等待服务端 CAS 回执关闭 Dialog。 */
async function renameConversationThroughUi(page, currentTitle, nextTitle, deadline) {
  await clickVerifiedControl(
    page,
    page.getByRole("button", { name: `对话菜单：${currentTitle}`, exact: true }),
    deadline,
  );
  await clickVerifiedControl(
    page,
    page.getByRole("menuitem", { name: "重命名", exact: true }),
    deadline,
  );
  const dialog = page.getByRole("dialog", { name: "重命名对话" });
  const input = dialog.getByRole("textbox", { name: "会话标题" });
  await input.fill(nextTitle);
  await clickVerifiedControl(
    page,
    dialog.getByRole("button", { name: "保存", exact: true }),
    deadline,
  );
  await dialog.waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
}

/** 等待指定 Thread 的权威终态事件，并可选择限制 cancelled/completed 状态。 */
async function waitForConversationTerminal(page, threadId, expectedState, deadline, signal) {
  let terminal;
  await waitForCondition(
    `${threadId} terminal ${expectedState}`,
    async () => {
      terminal = (await captureRawTauriEvents(page)).findLast(
        (event) =>
          event.method === "turn/terminal" &&
          event.threadId === threadId &&
          (expectedState === undefined || event.terminalState === expectedState),
      );
      return terminal !== undefined;
    },
    deadline,
    signal,
  );
  return terminal;
}

/**
 * 将可见最终答复、当前 Thread 的 completed 终态与 idle Composer 三者绑定；多 Thread 场景不能
 * 复用只看最后一个全局 terminal 的普通 Provider helper，否则会误读上一场景的终态。
 */
async function waitForAutomaticTitleTurnFinal(page, threadId, expectedText, deadline, signal) {
  await page
    .locator(".ja-chat-message-final")
    .filter({ hasText: expectedText })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await waitForConversationTerminal(page, threadId, "completed", deadline, signal);
  await waitForCondition(
    `${threadId} composer idle`,
    () =>
      page
        .locator('button[aria-label="取消"]')
        .count()
        .then((count) => count === 0),
    deadline,
    signal,
  );
}

/**
 * 在生产 App Server、真实 Tauri/WebView2 与内建 loopback Provider 上覆盖即时标题、自动标题、
 * 失败回退、人工 CAS、首轮取消、后续不补生成和搜索刷新；不使用测试 RPC 或客户端 fake。
 */
async function runAutomaticTitleAcceptanceSession(
  page,
  deadline,
  directories,
  fixture,
  recordIsolation,
  signal,
  recordStage,
) {
  const stage = (name) => recordStage?.(`title:${name}`);
  const startupSurfaceDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  stage("load");
  await page.waitForFunction(
    () =>
      globalThis.document.readyState === "interactive" ||
      globalThis.document.readyState === "complete",
    undefined,
    { timeout: Math.max(1, startupSurfaceDeadline - Date.now()) },
  );
  stage("general_heading");
  await page
    .getByRole("heading", { name: "你想让 Ja 帮你完成什么？", exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, startupSurfaceDeadline - Date.now()) });
  stage("connected");
  await waitForRuntimeReady(page, startupSurfaceDeadline, signal);
  stage("history");
  await waitForInitialThread(page, startupSurfaceDeadline, signal);
  await waitForComposerAdmission(page, startupSurfaceDeadline);
  const scenarios = fixture.scenarios;

  stage("success_admission");
  const successThreadId = await currentThreadId(page, deadline, signal);
  await assertVisibleConversationTitle(page, successThreadId, "新对话", deadline, signal);
  await page.getByRole("textbox", { name: "消息" }).fill(scenarios.success.prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await waitForTitleFixtureAttempts(fixture, scenarios.success.id, "turn", 1, deadline, signal);
  const successTurnAttempt = fixture.attempts.find(
    (attempt) => attempt.scenarioId === scenarios.success.id && attempt.kind === "turn",
  );
  if (successTurnAttempt?.streamStarted || successTurnAttempt?.responded) {
    throw new Error("即时短标题断言前 loopback 已发送模型输出");
  }
  await assertVisibleConversationTitle(
    page,
    successThreadId,
    scenarios.success.prompt,
    deadline,
    signal,
  );
  if (
    (await page
      .locator(".ja-chat-message-final")
      .filter({ hasText: scenarios.success.reply })
      .count()) !== 0
  ) {
    throw new Error("首个模型输出前已经出现最终回复");
  }
  fixture.release(scenarios.success.id, "turn");
  await waitForAutomaticTitleTurnFinal(
    page,
    successThreadId,
    scenarios.success.reply,
    deadline,
    signal,
  );
  await waitForTitleFixtureAttempts(fixture, scenarios.success.id, "title", 1, deadline, signal);
  const search = await assertSearchRefreshDuringAutomaticTitle(
    page,
    fixture,
    successThreadId,
    scenarios.success,
    deadline,
    signal,
  );

  stage("failure_create");
  const failureThreadId = await createConversationThread(page, deadline, signal);
  stage("failure_submit");
  await page.getByRole("textbox", { name: "消息" }).fill(scenarios.titleFailure.prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  stage("failure_turn_final");
  await waitForAutomaticTitleTurnFinal(
    page,
    failureThreadId,
    scenarios.titleFailure.reply,
    deadline,
    signal,
  );
  stage("failure_title_attempt");
  await waitForTitleFixtureAttempts(
    fixture,
    scenarios.titleFailure.id,
    "title",
    1,
    deadline,
    signal,
  );
  stage("failure_title_finished");
  await waitForTitleFixtureFinished(
    fixture,
    scenarios.titleFailure.id,
    "title",
    1,
    deadline,
    signal,
  );
  stage("failure_single_attempt");
  await assertNoAdditionalTitleAttempts(
    fixture,
    scenarios.titleFailure.id,
    "title",
    1,
    1_200,
    signal,
  );
  stage("failure_visible_title");
  await assertVisibleConversationTitle(
    page,
    failureThreadId,
    scenarios.titleFailure.prompt,
    deadline,
    signal,
  );
  if (
    (await page.locator("[data-sonner-toast]").count()) !== 0 ||
    (await page.getByText(/AI\s*生成/u).count()) !== 0
  ) {
    throw new Error("标题 Provider 失败产生了用户可见错误或生成标记");
  }

  stage("manual_cas");
  const manualThreadId = await createConversationThread(page, deadline, signal);
  await page.getByRole("textbox", { name: "消息" }).fill(scenarios.manual.prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await waitForAutomaticTitleTurnFinal(
    page,
    manualThreadId,
    scenarios.manual.reply,
    deadline,
    signal,
  );
  await waitForTitleFixtureAttempts(fixture, scenarios.manual.id, "title", 1, deadline, signal);
  await assertVisibleConversationTitle(
    page,
    manualThreadId,
    scenarios.manual.prompt,
    deadline,
    signal,
  );
  await renameConversationThroughUi(
    page,
    scenarios.manual.prompt,
    scenarios.manual.manualTitle,
    deadline,
  );
  await assertVisibleConversationTitle(
    page,
    manualThreadId,
    scenarios.manual.manualTitle,
    deadline,
    signal,
  );
  const manualMetadataCount = (await captureRawTauriEvents(page)).filter(
    (event) => event.method === "thread/metadata-changed" && event.threadId === manualThreadId,
  ).length;
  fixture.release(scenarios.manual.id, "title");
  await waitForTitleFixtureFinished(fixture, scenarios.manual.id, "title", 1, deadline, signal);
  await assertManualTitleRemainsAuthoritative(
    page,
    manualThreadId,
    scenarios.manual.manualTitle,
    manualMetadataCount,
    1_200,
    signal,
  );

  stage("cancel_first_turn");
  const cancellationThreadId = await createConversationThread(page, deadline, signal);
  await page.getByRole("textbox", { name: "消息" }).fill(scenarios.cancellation.prompt);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await waitForTitleFixtureAttempts(
    fixture,
    scenarios.cancellation.id,
    "turn",
    1,
    deadline,
    signal,
  );
  await assertVisibleConversationTitle(
    page,
    cancellationThreadId,
    scenarios.cancellation.prompt,
    deadline,
    signal,
  );
  await clickVerifiedControl(
    page,
    page.getByRole("button", { name: "取消", exact: true }),
    deadline,
  );
  await waitForConversationTerminal(page, cancellationThreadId, "cancelled", deadline, signal);
  await waitForTitleFixtureFinished(
    fixture,
    scenarios.cancellation.id,
    "turn",
    1,
    deadline,
    signal,
  );
  fixture.release(scenarios.cancellation.id, "turn");
  if (
    (await page
      .locator(".ja-chat-message-final")
      .filter({ hasText: scenarios.cancellation.reply })
      .count()) !== 0
  ) {
    throw new Error("已取消首轮仍显示了 Provider 回复");
  }
  await waitForComposerAdmission(page, deadline);
  await page.getByRole("textbox", { name: "消息" }).fill(scenarios.cancellation.secondPrompt);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  await waitForTitleFixtureAttempts(
    fixture,
    scenarios.cancellation.id,
    "turn",
    2,
    deadline,
    signal,
  );
  await waitForAutomaticTitleTurnFinal(
    page,
    cancellationThreadId,
    scenarios.cancellation.secondReply,
    deadline,
    signal,
  );
  await assertVisibleConversationTitle(
    page,
    cancellationThreadId,
    scenarios.cancellation.prompt,
    deadline,
    signal,
  );
  await assertNoAdditionalTitleAttempts(
    fixture,
    scenarios.cancellation.id,
    "title",
    0,
    1_200,
    signal,
  );

  stage("restart_anchor");
  await selectThreadById(page, successThreadId, deadline, signal);
  await assertVisibleConversationTitle(
    page,
    successThreadId,
    scenarios.success.automaticTitle,
    deadline,
    signal,
  );
  await captureVisualEvidence(page, "automatic-thread-title-webview2.png");
  recordIsolation(assertRuntimeIsolation(await processSnapshot(signal), directories));
  const providerAttempts = fixture.snapshot();
  if (providerAttempts.some((entry) => entry.invalidTitleContracts !== 0)) {
    throw new Error("自动标题请求未满足冻结模型、64 Token、无 Tool 或无续接合同");
  }
  return {
    search,
    titles: [
      { threadId: successThreadId, title: scenarios.success.automaticTitle, source: "auto" },
      { threadId: failureThreadId, title: scenarios.titleFailure.prompt, source: "placeholder" },
      { threadId: manualThreadId, title: scenarios.manual.manualTitle, source: "manual" },
      {
        threadId: cancellationThreadId,
        title: scenarios.cancellation.prompt,
        source: "placeholder",
      },
    ],
    activeThreadId: successThreadId,
    providerAttempts,
  };
}

/**
 * 第二个真实 Tauri 生命周期只从持久 SQLite/UI 恢复标题；同时确认重启没有重放成功、失败或
 * 迟到标题请求，并再次从搜索 Dialog 读取自动标题。
 */
async function runAutomaticTitleRestartSession(page, expectation, fixture, deadline, signal) {
  await page.waitForFunction(
    () =>
      globalThis.document.readyState === "interactive" ||
      globalThis.document.readyState === "complete",
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  await waitForRuntimeReady(page, deadline, signal);
  for (const expected of expectation.titles) {
    await waitForCondition(
      `重启恢复标题 ${expected.title}`,
      async () => (await sidebarThreadTitle(page, expected.threadId))?.trim() === expected.title,
      deadline,
      signal,
    );
  }
  await selectThreadById(page, expectation.activeThreadId, deadline, signal);
  const active = expectation.titles.find((entry) => entry.threadId === expectation.activeThreadId);
  if (active === undefined) throw new Error("重启标题锚点缺失");
  await assertVisibleConversationTitle(
    page,
    expectation.activeThreadId,
    active.title,
    deadline,
    signal,
  );
  await clickVerifiedControl(
    page,
    page.getByRole("button", { name: "搜索对话", exact: true }),
    deadline,
  );
  const search = page.getByRole("searchbox", { name: "搜索对话" });
  await search.fill(fixture.scenarios.success.searchQuery);
  await page
    .getByRole("option")
    .filter({ hasText: fixture.scenarios.success.automaticTitle })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  if (!(await search.evaluate((element) => globalThis.document.activeElement === element))) {
    throw new Error("重启后搜索标题未保持输入焦点");
  }
  await closeConversationSearch(page, deadline);
  await assertNoAdditionalTitleAttempts(
    fixture,
    fixture.scenarios.success.id,
    "title",
    1,
    600,
    signal,
  );
  const afterRestart = fixture.snapshot();
  if (JSON.stringify(afterRestart) !== JSON.stringify(expectation.providerAttempts)) {
    throw new Error("重启重放了自动标题 Provider 请求");
  }
  return {
    titles: expectation.titles,
    providerAttempts: afterRestart,
    searchRecovered: true,
  };
}

/**
 * 完成一次无项目 UI turn，并返回 restart 断言使用的稳定 DOM 证据。
 * 最终回答通过文本验证，而不是截图或客户端 fake adapter。
 */
async function runFirstSession(
  page,
  runId,
  deadline,
  directories,
  previewFixture,
  nativeScope,
  providerConfig,
  recordAppearance,
  recordAfterSend,
  recordIsolation,
  signal,
  recordStage,
) {
  throwIfAborted(signal);
  const stage = (name) => recordStage?.(`first:${name}`);
  stage("load");
  await page.waitForFunction(
    () =>
      globalThis.document.readyState === "interactive" ||
      globalThis.document.readyState === "complete",
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  if (providerConfig?.configureViaUi === true) {
    stage("provider_ui_configuration");
    await configureRealProviderThroughUi(page, providerConfig, deadline);
  }
  stage("general_heading");
  // malformed settings 文档必须携带实时 DOM/原生证据失败；
  // 若等待整轮 deadline，页面会在采样前关闭。
  const startupSurfaceDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  await page
    .getByRole("heading", { name: "你想让 Ja 帮你完成什么？", exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, startupSurfaceDeadline - Date.now()) });
  stage("connected");
  await waitForRuntimeReady(page, startupSurfaceDeadline, signal);
  stage("history");
  await waitForInitialThread(page, startupSurfaceDeadline, signal);
  stage("codex_shell");
  await assertCodexShellStructure(page, deadline, signal, (name) => recordStage?.(`first:${name}`));
  stage("appearance_preferences");
  // 外观页是启动验收面，不得借用整轮 15 分钟预算；缺失设置控件应在一个 turn
  // deadline 内暴露，避免把后续 Files/Terminal 真窗矩阵全部遮蔽。
  const appearanceDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  const appearance = await applyVisualPreferences(page, appearanceDeadline);
  recordAppearance(appearance);
  const visual = appearance.responsive;
  stage("general_scope_after_visual");
  await assertGeneralConversationScope(page, deadline);
  if (providerConfig !== undefined) {
    stage("redacted_credential");
    await assertRedactedModelCredential(page, deadline);
  }
  stage("isolation");
  recordIsolation(assertRuntimeIsolation(await processSnapshot(signal), directories));
  const marker = `JA_REAL_PROVIDER_${runId}`;
  const input = providerConfig === undefined ? `E2E turn ${runId}` : `只回复一行：${marker}`;
  const expectedFinal = providerConfig === undefined ? `Fake response: ${input}` : marker;
  stage("ordinary_admission");
  await waitForComposerAdmission(page, deadline);
  await assertGeneralConversationScope(page, deadline);
  stage("attachment_draft");
  const attachment = await exerciseAttachmentDraft(
    page,
    nativeScope,
    directories,
    deadline,
    signal,
  );
  stage("ordinary_send");
  await beginRealtimeDraftObservation(page);
  await page.getByRole("textbox", { name: "消息" }).fill(input);
  await page.getByRole("button", { name: "发送" }).click();
  await page
    .getByRole("list", { name: "待发送附件", exact: true })
    .waitFor({ state: "detached", timeout: Math.max(1, deadline - Date.now()) });
  recordAfterSend(await captureUiEvidence(page, directories, signal));
  throwIfAborted(signal);
  const turnDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  // fake response metadata 会回显 prompt，因此宽泛 hasText locator 会同时匹配两张 card；
  // 语义 user class 与通用 item 前缀是稳定契约，而 opaque Kernel item ID 刻意不编码角色。
  stage("ordinary_user");
  await page
    .locator('.ja-chat-message-user[data-item-id^="item_"]')
    .filter({ hasText: input })
    .waitFor({ state: "visible", timeout: Math.max(1, turnDeadline - Date.now()) });
  stage("ordinary_final_wait");
  const finalMessage = page.locator(".ja-chat-message-final").filter({ hasText: expectedFinal });
  if (providerConfig === undefined) {
    await finalMessage.waitFor({
      state: "visible",
      timeout: Math.max(1, turnDeadline - Date.now()),
    });
  } else {
    await waitForRealProviderFinal(page, expectedFinal, turnDeadline, directories, signal);
  }
  stage("ordinary_completed");
  await waitForTurnRowConvergence(page, input, expectedFinal, turnDeadline, signal);
  const realtime = await assertRealtimeDeltaBeforeTerminal(page);
  const turnRow = page.locator(".ja-chat-timeline__row").filter({ hasText: input });
  await assertAttachmentHistoryVisible(turnRow, attachment.fileName, turnDeadline);
  attachment.status = "bound_and_visible";
  if ((await page.locator(".ja-chat-avatar, .ja-chat-message__avatar").count()) !== 0) {
    throw new Error("对话时间线仍存在头像");
  }
  if (providerConfig !== undefined) {
    const process = turnRow.locator(".ja-work-process");
    await process.waitFor({ state: "visible", timeout: Math.max(1, turnDeadline - Date.now()) });
    const steps = process.locator(".ja-work-step");
    if ((await steps.count()) === 0) {
      await process.locator(".ja-work-process__trigger").click();
      await steps
        .first()
        .waitFor({ state: "visible", timeout: Math.max(1, turnDeadline - Date.now()) });
    }
    if ((await steps.count()) < 1 || !/\b(?:ms|s)\b/u.test(await process.innerText())) {
      throw new Error("真实 Provider 回合缺少可见工作步骤或处理时间");
    }
  }
  // 纯文本 turn 没有 work-process group；下方 approval 场景才是权威 work-process 断言，
  // 此处不得先耗费 30 秒再运行下一项产品状态检查。
  await assertGeneralConversationScope(page, deadline);
  await captureVisualEvidence(
    page,
    `implementation-conversation-final-${visualTheme}-native-1280x820.png`,
  );
  const timelineText = await page.getByRole("region", { name: "对话时间线" }).innerText();
  stage("project_workbench");
  const workbench = await exerciseProjectWorkbench(
    page,
    deadline,
    directories,
    previewFixture,
    nativeScope,
    signal,
    (name) => recordStage?.(`first:${name}`),
  );
  let primaryConversation = {
    input,
    expectedFinal,
    timelineText: redact(timelineText, directories),
    conversationScope: "general",
  };
  if (providerConfig !== undefined) {
    stage("project_business_conversation");
    primaryConversation = await runProjectBusinessConversation(
      page,
      workbench,
      deadline,
      directories,
      signal,
    );
  }
  let parallel;
  let approvalMatrix;
  if (!shellOnlyMode && providerConfig === undefined) {
    stage("approval_project_reselect");
    const approvalScopeDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
    await selectProjectThreadById(page, workbench.projectThreadId, approvalScopeDeadline, signal);
    await assertProjectConversationScope(page, approvalScopeDeadline);
    parallel = await runParallelApprovalFlow(page, runId, deadline, directories, signal, (name) =>
      recordStage?.(`first:${name}`),
    );
    stage("approval_lifecycle_matrix");
    approvalMatrix = await runApprovalLifecycleMatrix(page, deadline, signal, (name) =>
      recordStage?.(`first:${name}`),
    );
  }
  return {
    ...primaryConversation,
    parallel,
    approvalMatrix,
    workbench,
    visual,
    appearance,
    realtime,
    attachment,
    attachmentConversation: { input, fileName: attachment.fileName },
  };
}

/**
 * 围绕一个 pending approval 检验两个真实 UI-owned thread。流程刻意等待可访问 DOM 状态，
 * 因为固定 sleep 或隐藏 bridge 调用可能在 Rust business-id/private-request-id 关联仍损坏时误通过。
 */
async function runParallelApprovalFlow(page, runId, deadline, directories, signal, recordStage) {
  throwIfAborted(signal);
  const stage = (name) => recordStage?.(`approval:${name}`);
  const timeout = () => Math.max(1, deadline - Date.now());
  // Admission 必须在与普通 turn 相同的有界预算内失败；后续人工 approval 刻意使用整轮预算。
  const admissionDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  const admissionTimeout = () => Math.max(1, admissionDeadline - Date.now());
  const input = approvalFixtureInput;
  const aThreadId = await currentThreadId(page, deadline, signal);
  const historyCountBefore = await page
    .getByRole("list", { name: "最近对话列表" })
    .locator("button[data-thread-id]")
    .count();
  // Markdown 会将 sentinel 的成对下划线渲染为强调标记；
  // 保持提交的 wire input 精确不变，同时等待稳定可见文本。
  const userItem = page
    .locator('.ja-chat-message-user[data-item-id^="item_"]')
    .filter({ hasText: approvalFixtureVisibleInput });
  // 绑定 ApprovalCard 的完整可访问标题；完整 Shell 参数属于脱敏边界，真窗只校验
  // Kernel 批准展示的 reason、tool 与 callId，后台事件再校验 approvalId 关联和顺序。
  const approvalHeading = page.getByRole("heading", { name: "工具调用需要确认", exact: true });
  const approvalCard = approvalHeading.locator("xpath=ancestor::section[1]");
  const approvalReason = approvalCard.getByText(approvalFixtureReason, { exact: true });
  const approvalTool = approvalCard.getByText(approvalFixtureTool, { exact: true });
  const approvalCall = approvalCard.getByText(approvalFixtureCallId, { exact: true });
  stage("A_send");
  await page.getByRole("textbox", { name: "消息" }).fill(input);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  stage("A_user");
  await userItem.waitFor({ state: "visible", timeout: admissionTimeout() });
  stage("A_approval");
  await approvalHeading.waitFor({ state: "visible", timeout: admissionTimeout() });
  await approvalReason.waitFor({ state: "visible", timeout: admissionTimeout() });
  await approvalTool.waitFor({ state: "visible", timeout: admissionTimeout() });
  await approvalCall.waitFor({ state: "visible", timeout: admissionTimeout() });
  await captureVisualEvidence(
    page,
    `implementation-conversation-approval-${visualTheme}-native-1280x820.png`,
  );
  if ((await page.getByText(`Fake response: ${input}`, { exact: true }).count()) !== 0) {
    throw new Error("A Thread 在切换 B 前已经结束，未证明 pending approval 并行");
  }
  const pendingTimeline = redact(
    await page.getByRole("region", { name: "对话时间线" }).innerText(),
    directories,
  );

  const newConversation = page.locator('button[aria-label="新会话"]');
  stage("B_create_wait");
  await newConversation.waitFor({ state: "visible", timeout: timeout() });
  await page.waitForFunction(
    () => {
      const button = globalThis.document.querySelector('button[aria-label="新会话"]');
      return button instanceof globalThis.HTMLButtonElement && !button.disabled;
    },
    undefined,
    { timeout: timeout() },
  );
  await newConversation.click();
  stage("B_created");
  await page.waitForFunction(
    ({ previous, before }) => {
      const rows = Array.from(
        globalThis.document.querySelectorAll('[aria-label="最近对话列表"] button'),
      );
      const selected = rows.find((row) => row.getAttribute("aria-current") === "page");
      return rows.length >= before + 1 && selected?.getAttribute("data-thread-id") !== previous;
    },
    { previous: aThreadId, before: historyCountBefore },
    { timeout: timeout() },
  );
  stage("B_selected");

  const bInput = `E2E parallel B ${runId}`;
  stage("B_send");
  await page.getByRole("textbox", { name: "消息" }).fill(bInput);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  stage("B_final_wait");
  const bTimeline = await waitForTurnFinal(
    page,
    bInput,
    `Fake response: ${bInput}`,
    Math.min(deadline, Date.now() + turnDeadlineMs),
    signal,
    directories,
  );
  stage("B_completed");
  if (
    bTimeline.includes(approvalFixtureCallId) ||
    (await approvalHeading.count()) !== 0 ||
    (await page.getByRole("button", { name: "批准", exact: true }).count()) !== 0
  ) {
    throw new Error("B Thread 显示了 A 的审批卡或调用身份");
  }

  stage("A_reselect_wait");
  await selectThreadById(page, aThreadId, deadline, signal);
  stage("A_reselected");
  await approvalHeading.waitFor({ state: "visible", timeout: timeout() });
  await approvalReason.waitFor({ state: "visible", timeout: timeout() });
  await approvalTool.waitFor({ state: "visible", timeout: timeout() });
  await approvalCall.waitFor({ state: "visible", timeout: timeout() });
  stage("A_card_restored");
  const approve = page.getByRole("button", { name: "批准", exact: true });
  stage("A_approve_click");
  await clickVerifiedControl(page, approve, deadline);
  await page.getByText("已批准", { exact: true }).waitFor({ state: "visible", timeout: timeout() });
  stage("A_allowed");
  const aTimeline = await waitForTurnFinal(
    page,
    input,
    `Fake response: ${approvalFixtureVisibleInput}`,
    Math.min(deadline, Date.now() + turnDeadlineMs),
    signal,
    directories,
    approvalFixtureVisibleInput,
  );
  stage("A_completed");
  const events = await captureRawTauriEvents(page);
  const correlation = assertApprovalParallelEvidence(events);
  return {
    input,
    bInput,
    pendingTimeline,
    bTimeline,
    aTimeline,
    correlation,
    // 这是响应的用户可见证据；approval/resolved 刻意不作为 WebView event，
    // 因为 Rust 按业务 approval id 路由时会将该响应保持私有。
    resolvedDecision: "approve",
  };
}

/**
 * 新建一个 UI-owned Thread 并让 fake Tool 进入待审批；返回值只包含业务 identity、revision
 * 与可访问卡片，不复制 Tool 参数或用户路径，供取消、拒绝、CAS、过期和重启场景复用。
 */
async function createPendingApprovalThread(page, deadline, signal, stage) {
  const timeout = () => Math.max(1, deadline - Date.now());
  const previousThreadId = await currentThreadId(page, deadline, signal);
  const history = page
    .getByRole("list", { name: "最近对话列表" })
    .locator("button[data-thread-id]");
  const countBefore = await history.count();
  const create = page.locator('button[aria-label="新会话"]');
  stage?.("create");
  await create.waitFor({ state: "visible", timeout: timeout() });
  await page.waitForFunction(
    () => {
      const button = globalThis.document.querySelector('button[aria-label="新会话"]');
      return button instanceof globalThis.HTMLButtonElement && !button.disabled;
    },
    undefined,
    { timeout: timeout() },
  );
  await create.click();
  await page.waitForFunction(
    ({ previous, before }) => {
      const rows = Array.from(
        globalThis.document.querySelectorAll('[aria-label="最近对话列表"] button[data-thread-id]'),
      );
      const selected = rows.find((row) => row.getAttribute("aria-current") === "page");
      return rows.length >= before + 1 && selected?.getAttribute("data-thread-id") !== previous;
    },
    { previous: previousThreadId, before: countBefore },
    { timeout: timeout() },
  );
  const threadId = await currentThreadId(page, deadline, signal);
  stage?.("send");
  await page.getByRole("textbox", { name: "消息" }).fill(approvalFixtureInput);
  await page.getByRole("button", { name: "发送", exact: true }).click();
  const heading = page.getByRole("heading", { name: "工具调用需要确认", exact: true });
  const card = heading.locator("xpath=ancestor::section[1]");
  await heading.waitFor({ state: "visible", timeout: timeout() });
  await card.getByText(approvalFixtureReason, { exact: true }).waitFor({
    state: "visible",
    timeout: timeout(),
  });
  const events = await captureRawTauriEvents(page);
  const requested = events.findLast(
    (event) =>
      event.method === "approval/requested" &&
      event.threadId === threadId &&
      event.approvalId?.startsWith("appr_") &&
      event.turnId?.startsWith("turn_") &&
      Number.isSafeInteger(event.threadRevision),
  );
  if (requested === undefined) throw new Error("待审批 Thread 缺少业务 identity 或 revision");
  return {
    threadId,
    turnId: requested.turnId,
    approvalId: requested.approvalId,
    threadRevision: requested.threadRevision,
    expiresAt: requested.expiresAt,
    card,
  };
}

/** 等待指定 Turn 的权威 terminal 事件，避免把按钮本地状态当成取消或审批完成。 */
async function waitForApprovalTurnTerminal(page, turnId, deadline, signal) {
  let terminal;
  await waitForCondition(
    "approval Turn terminal",
    async () => {
      terminal = (await captureRawTauriEvents(page)).findLast(
        (event) => event.method === "turn/terminal" && event.turnId === turnId,
      );
      return terminal !== undefined;
    },
    deadline,
    signal,
  );
  return terminal;
}

/**
 * 用陈旧 revision 触发真实 `CONFLICT`，随后确认审批仍可从 UI 拒绝并收敛；服务端单响应者
 * 门闩位于 CAS 之前，因此不能用同一审批的并发响应冒充 revision 冲突。
 */
async function exerciseApprovalRevisionCas(page, pending, deadline, signal) {
  if (!Number.isSafeInteger(pending.threadRevision) || pending.threadRevision < 1) {
    throw new Error("审批 revision CAS 缺少可构造陈旧值的 revision");
  }
  const input = {
    approvalId: pending.approvalId,
    turnId: pending.turnId,
    decision: "approve",
    expectedThreadRevision: pending.threadRevision - 1,
  };
  const outcome = await page.evaluate(async (input) => {
    const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
    if (typeof invoke !== "function") throw new Error("Tauri invoke bridge unavailable");
    try {
      await invoke("ja_approval_respond", { input });
      return { status: "fulfilled" };
    } catch (error) {
      let value = error;
      if (typeof value === "string") {
        try {
          value = JSON.parse(value);
        } catch {
          value = undefined;
        }
      }
      const code =
        value !== null && typeof value === "object" && typeof value.code === "string"
          ? value.code
          : "UNKNOWN";
      return { status: "rejected", code };
    }
  }, input);
  if (outcome.status !== "rejected" || outcome.code !== "CONFLICT") {
    throw new Error(`审批陈旧 revision 未返回 CONFLICT：${JSON.stringify(outcome)}`);
  }
  await clickVerifiedControl(
    page,
    pending.card.getByRole("button", { name: "拒绝", exact: true }),
    deadline,
  );
  await pending.card
    .getByText("已拒绝", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const terminal = await waitForApprovalTurnTerminal(page, pending.turnId, deadline, signal);
  return { status: "passed", conflictCode: outcome.code, terminalState: terminal?.terminalState };
}

/** 同 revision 并发相反决策验证单响应者门闩，只允许一个成功且败者稳定为已解决。 */
async function exerciseApprovalResponderCompetition(page, pending, deadline, signal) {
  const input = {
    approvalId: pending.approvalId,
    turnId: pending.turnId,
    expectedThreadRevision: pending.threadRevision,
  };
  const outcomes = await page.evaluate(async (input) => {
    const invoke = globalThis.__TAURI_INTERNALS__?.invoke;
    if (typeof invoke !== "function") throw new Error("Tauri invoke bridge unavailable");
    const submit = async (decision) => {
      try {
        await invoke("ja_approval_respond", { input: { ...input, decision } });
        return { status: "fulfilled" };
      } catch (error) {
        let value = error;
        if (typeof value === "string") {
          try {
            value = JSON.parse(value);
          } catch {
            value = undefined;
          }
        }
        return {
          status: "rejected",
          code:
            value !== null && typeof value === "object" && typeof value.code === "string"
              ? value.code
              : "UNKNOWN",
        };
      }
    };
    return Promise.all([submit("approve"), submit("deny")]);
  }, input);
  const fulfilled = outcomes.filter((outcome) => outcome.status === "fulfilled");
  const rejected = outcomes.filter((outcome) => outcome.status === "rejected");
  if (
    fulfilled.length !== 1 ||
    rejected.length !== 1 ||
    rejected[0]?.code !== "APPROVAL_ALREADY_RESOLVED"
  ) {
    throw new Error(`审批单响应者竞争未稳定收敛：${JSON.stringify(outcomes)}`);
  }
  const terminal = await waitForApprovalTurnTerminal(page, pending.turnId, deadline, signal);
  return { status: "passed", outcomes, terminalState: terminal?.terminalState };
}

/**
 * 用独立 Thread 覆盖取消、拒绝、revision CAS、真实 expiresAt 与待审批重启恢复准备。
 * 各场景串行化是为了让单一 WebView 的 ApprovalCard 与 terminal 事件保持可归因。
 */
async function runApprovalLifecycleMatrix(page, deadline, signal, recordStage) {
  const stage = (name) => recordStage?.(`approval_matrix:${name}`);
  const stepDeadline = (duration = turnDeadlineMs + 10_000) =>
    Math.min(deadline, Date.now() + duration);

  stage("cancel");
  const cancellation = await createPendingApprovalThread(page, stepDeadline(), signal, stage);
  const cancelInvokesBefore = await tauriInvokeCount(page, "ja_turn_cancel");
  await clickVerifiedControl(
    page,
    page.getByRole("button", { name: "取消", exact: true }),
    stepDeadline(),
  );
  const cancelledTerminal = await waitForApprovalTurnTerminal(
    page,
    cancellation.turnId,
    stepDeadline(),
    signal,
  );
  await cancellation.card
    .getByText("Turn 已结束", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, stepDeadline() - Date.now()) });
  if (
    cancelledTerminal?.terminalState !== "cancelled" ||
    (await cancellation.card.getByRole("button").count()) !== 0 ||
    (await tauriInvokeCount(page, "ja_turn_cancel")) <= cancelInvokesBefore
  ) {
    throw new Error("待审批 Turn 取消未收敛到 cancelled 或审批卡仍可操作");
  }

  stage("deny");
  const denied = await createPendingApprovalThread(page, stepDeadline(), signal, stage);
  await clickVerifiedControl(
    page,
    denied.card.getByRole("button", { name: "拒绝", exact: true }),
    stepDeadline(),
  );
  await denied.card
    .getByText("已拒绝", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, stepDeadline() - Date.now()) });
  const deniedTerminal = await waitForApprovalTurnTerminal(
    page,
    denied.turnId,
    stepDeadline(),
    signal,
  );
  if ((await denied.card.getByRole("button").count()) !== 0) {
    throw new Error("已拒绝审批卡仍保留可操作按钮");
  }

  stage("revision_cas");
  const casPending = await createPendingApprovalThread(page, stepDeadline(), signal, stage);
  const cas = await exerciseApprovalRevisionCas(page, casPending, stepDeadline(), signal);

  stage("responder_competition");
  const competitionPending = await createPendingApprovalThread(page, stepDeadline(), signal, stage);
  const competition = await exerciseApprovalResponderCompetition(
    page,
    competitionPending,
    stepDeadline(),
    signal,
  );

  stage("expiry");
  const expiring = await createPendingApprovalThread(page, stepDeadline(), signal, stage);
  const expiresAt = Date.parse(expiring.expiresAt ?? "");
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    throw new Error("审批过期场景缺少未来 expiresAt");
  }
  const expiryDeadline = Math.min(deadline, expiresAt + 10_000);
  await expiring.card
    .getByText("已过期", { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, expiryDeadline - Date.now()) });
  const expiredTerminal = await waitForApprovalTurnTerminal(
    page,
    expiring.turnId,
    expiryDeadline,
    signal,
  );
  if ((await expiring.card.getByRole("button").count()) !== 0) {
    throw new Error("已过期审批卡仍保留可操作按钮");
  }

  stage("prepare_restart");
  const restartPending = await createPendingApprovalThread(page, stepDeadline(), signal, stage);
  return {
    cancellation: {
      status: "passed",
      terminalState: cancelledTerminal.terminalState,
      invoke: "ja_turn_cancel",
    },
    deny: { status: "passed", terminalState: deniedTerminal.terminalState },
    revisionCas: cas,
    responderCompetition: competition,
    expiry: { status: "passed", terminalState: expiredTerminal.terminalState },
    restartPending: {
      threadId: restartPending.threadId,
      turnId: restartPending.turnId,
      approvalId: restartPending.approvalId,
    },
  };
}

/**
 * 进程重启后重开持久项目，验证当前外层 Tab 与休眠终端布局恢复，再经 closeAll 切回。
 */
async function exerciseRestartedWorkbench(page, expected, nativeScope, deadline, signal) {
  const timeout = () => Math.max(1, deadline - Date.now());
  const projectRows = page.locator('[aria-label="项目列表"] button[data-scope-kind="project"]');
  if ((await projectRows.count()) !== 1)
    throw new Error("重启后的隔离 profile 未恢复唯一 E2E 项目");
  await clickVerifiedControl(page, projectRows.first(), deadline);
  await page
    .locator('[aria-label="项目列表"] button[data-scope-kind="project"][aria-current="page"]')
    .waitFor({ state: "visible", timeout: timeout() });
  await page.waitForFunction(
    (generalThreadId) =>
      globalThis.document
        .querySelector('[aria-label="最近对话列表"] button[aria-current="page"]')
        ?.getAttribute("data-thread-id") !== generalThreadId,
    expected.originalThreadId,
    { timeout: timeout() },
  );
  const workbench = await ensureWorkbenchVisible(page, deadline);
  await page.waitForFunction(
    (preference) => {
      const tabs = [...globalThis.document.querySelectorAll(".ja-workbench-tab-shell")].map((tab) =>
        tab.getAttribute("data-tab"),
      );
      const active = globalThis.document
        .querySelector('.ja-workbench-tab-shell[data-state="active"]')
        ?.getAttribute("data-tab");
      return (
        JSON.stringify(tabs) === JSON.stringify(preference.order) && active === preference.active
      );
    },
    expected.preference,
    { timeout: timeout() },
  );
  const terminalWorkspace = workbench.getByRole("region", { name: "终端工作区", exact: true });
  await terminalWorkspace.waitFor({ state: "visible", timeout: timeout() });
  await page.waitForFunction(
    (paneCount) => {
      const panes = [
        ...globalThis.document.querySelectorAll(
          ".ja-terminal-workspace .ja-terminal-pane[data-pane-id]",
        ),
      ];
      return (
        panes.length === paneCount &&
        panes.every(
          (pane) => pane.querySelector(".ja-terminal-pane-state")?.textContent?.trim() === "运行中",
        )
      );
    },
    expected.persistedPaneIds.length,
    { timeout: timeout() },
  );
  const restoredPaneIds = await terminalWorkspace
    .locator(".ja-terminal-pane[data-pane-id]")
    .evaluateAll((panes) => panes.map((pane) => pane.getAttribute("data-pane-id")).sort());
  if (JSON.stringify(restoredPaneIds) !== JSON.stringify(expected.persistedPaneIds)) {
    throw new Error(
      `重启后 dormant 终端布局不一致：${JSON.stringify({ expected: expected.persistedPaneIds, actual: restoredPaneIds })}`,
    );
  }
  let restartedNativeSessions = [];
  await waitForCondition(
    "重启后创建全新 native session",
    async () => {
      restartedNativeSessions = await terminalSessionIdentities(terminalWorkspace);
      return restartedNativeSessions.length === expected.persistedPaneIds.length;
    },
    deadline,
    signal,
  );
  const restartedNativeSessionIds = restartedNativeSessions.map(({ sessionId }) => sessionId);
  if (
    new Set(restartedNativeSessionIds).size !== expected.persistedPaneIds.length ||
    restartedNativeSessionIds.some((sessionId) =>
      expected.priorTerminalSessionIds.includes(sessionId),
    )
  ) {
    throw new Error("进程重启后复用了旧 native terminal session identity");
  }
  let restartedShells = [];
  await waitForCondition(
    "重启后创建新 PTY",
    async () => {
      restartedShells = await captureOwnedTerminalShells(nativeScope, signal);
      return restartedShells.length === expected.persistedPaneIds.length;
    },
    deadline,
    signal,
  );
  const closeAllBefore = await tauriInvokeCount(page, "ja_terminal_close_all");
  await selectThreadById(page, expected.originalThreadId, deadline, signal);
  await waitForCondition(
    "重启阶段 workspace closeAll",
    async () => (await tauriInvokeCount(page, "ja_terminal_close_all")) > closeAllBefore,
    deadline,
    signal,
  );
  await waitForOwnedIdentitiesGone(restartedShells, deadline, signal);
  await assertGeneralConversationScope(page, deadline);
  return {
    status: "passed",
    preference: expected.preference,
    restoredPaneIds,
    dormantSessionsRestoredAsFreshPty: true,
    freshNativeSessionCount: restartedNativeSessionIds.length,
    priorNativeSessionReused: false,
    workspaceSwitchCloseAll: true,
  };
}

/** restart 后重新打开原生 general scope，并证明此前行来自 SQLite snapshot 恢复。 */
async function runRestartSession(
  page,
  firstInput,
  expectedFinal,
  conversationScope,
  expectedWorkbench,
  expectedAttachment,
  expectedPendingApproval,
  nativeScope,
  deadline,
  directories,
  recordIsolation,
  signal,
) {
  throwIfAborted(signal);
  await page.waitForFunction(
    () =>
      globalThis.document.readyState === "interactive" ||
      globalThis.document.readyState === "complete",
    undefined,
    { timeout: Math.max(1, deadline - Date.now()) },
  );
  await waitForRuntimeReady(page, deadline, signal);
  // 重启只要求当前唯一的对话面与项目目录恢复；具体 general/project 选中态交给下方
  // scope 断言复验，避免重新依赖已经从产品删除的旧对话区入口。
  const restartSurfaceDeadline = Math.min(deadline, Date.now() + turnDeadlineMs);
  await conversationSurface(page).waitFor({
    state: "visible",
    timeout: Math.max(1, restartSurfaceDeadline - Date.now()),
  });
  await page.getByRole("list", { name: "项目列表", exact: true }).waitFor({
    state: "visible",
    timeout: Math.max(1, restartSurfaceDeadline - Date.now()),
  });
  recordIsolation(assertRuntimeIsolation(await processSnapshot(signal), directories));
  if (conversationScope === "project") {
    await selectProjectThreadById(page, expectedWorkbench.projectThreadId, deadline, signal);
    await assertProjectConversationScope(page, deadline);
  } else if (conversationScope === "general") {
    await selectThreadById(page, expectedWorkbench.originalThreadId, deadline, signal);
    await assertGeneralConversationScope(page, deadline);
  } else if (conversationScope !== "general") {
    throw new Error(`未知的重启会话 scope：${conversationScope}`);
  }
  await page
    .locator('.ja-chat-message-user[data-item-id^="item_"]')
    .filter({ hasText: firstInput })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  await page
    .getByText(expectedFinal, { exact: true })
    .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
  const timeline = redact(
    await page.getByRole("region", { name: "对话时间线" }).innerText(),
    directories,
  );
  let attachment;
  if (expectedAttachment !== undefined) {
    await selectThreadById(page, expectedWorkbench.originalThreadId, deadline, signal);
    const restoredRow = page
      .locator(".ja-chat-timeline__row")
      .filter({ hasText: expectedAttachment.input });
    await assertAttachmentHistoryVisible(restoredRow, expectedAttachment.fileName, deadline);
    attachment = {
      status: "restored",
      fileName: expectedAttachment.fileName,
      threadId: expectedWorkbench.originalThreadId,
    };
    if (conversationScope === "project") {
      await selectProjectThreadById(page, expectedWorkbench.projectThreadId, deadline, signal);
      await assertProjectConversationScope(page, deadline);
    }
  }
  const workbench = await exerciseRestartedWorkbench(
    page,
    expectedWorkbench,
    nativeScope,
    deadline,
    signal,
  );
  let approvalRecovery;
  if (expectedPendingApproval !== undefined) {
    await selectThreadById(page, expectedPendingApproval.threadId, deadline, signal);
    const card = page
      .getByRole("heading", { name: "工具调用需要确认", exact: true })
      .locator("xpath=ancestor::section[1]");
    await card.waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
    await clickVerifiedControl(
      page,
      card.getByRole("button", { name: "拒绝", exact: true }),
      deadline,
    );
    await card
      .getByText("已拒绝", { exact: true })
      .waitFor({ state: "visible", timeout: Math.max(1, deadline - Date.now()) });
    const terminal = await waitForApprovalTurnTerminal(
      page,
      expectedPendingApproval.turnId,
      deadline,
      signal,
    );
    approvalRecovery = {
      status: "restored_and_denied",
      threadId: expectedPendingApproval.threadId,
      terminalState: terminal.terminalState,
    };
  }
  return { timeline, workbench, attachment, approvalRecovery };
}

/**
 * 要求使用独立构建的 main jar，避免本冒烟测试通过隐式启动自身构建进程掩盖 packaging 失败。
 */
async function assertAppServerJar(signal) {
  throwIfAborted(signal);
  const jar =
    process.env.JA_E2E_APP_SERVER_JAR?.trim() ||
    join(repoRoot, "app-server", "target", "ja-app-server.jar");
  const metadata = await stat(jar);
  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error("app-server/target/ja-app-server.jar 不存在或为空；请先由外层构建门生成");
  }
  return jar;
}

/**
 * 为 setup 与 runtime 失败写入同一份有界、已脱敏产物；
 * 将其置于生命周期循环之外，保证 setup 错误可见。
 */
async function writeRunSummary(runId, evidence, status, error, directories) {
  const summaryRoot = join(tmpdir(), "ja-e2e-results");
  await mkdir(summaryRoot, { recursive: true });
  const summaryPath = join(summaryRoot, `${runId}.json`);
  const payload = {
    ...evidence,
    status,
    ...(error === undefined ? {} : { error: redact(error?.stack ?? error, directories) }),
  };
  await writeFile(summaryPath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  return summaryPath;
}

/**
 * 只移除 runner 创建的临时根；部分 setup 失败可能只提供该根路径，
 * 因此防护逻辑不得假设目录对象完整。
 */
async function cleanupRunRoot(root) {
  if (typeof root !== "string" || root.trim() === "") {
    return;
  }
  if (process.env.JA_E2E_KEEP_TEMP === "1") {
    process.stdout.write(`JA_E2E_TEMP_RETAINED root=${root}\n`);
    return;
  }
  const resolvedRoot = resolve(root);
  const resolvedTemp = resolve(tmpdir()).replace(/[\\/]$/, "");
  const prefix = `${resolvedTemp}\\ja-desktop-e2e-`.toLowerCase();
  if (
    resolvedRoot.toLowerCase() === resolvedTemp.toLowerCase() ||
    !resolvedRoot.toLowerCase().startsWith(prefix)
  ) {
    return;
  }
  await rm(resolvedRoot, { recursive: true, force: true }).catch(() => undefined);
}

/** 静态锁定 SendInput、固定五键、800/799 与超过九槽的 churn，不启动窗口或进程。 */
function assertNativeInputContract() {
  const script = buildOwnedNativeInputScript();
  if (
    !script.includes("SendInput(uint count, INPUT[] inputs, int size)") ||
    !script.includes("VkKeyScanEx") ||
    !script.includes("GetKeyboardLayout") ||
    !script.includes("TerminalText(string t)") ||
    !script.includes("S(new List<INPUT>{W(c,0),W(c,2)})") ||
    !script.includes("Thread.Sleep(1)") ||
    !script.includes("ResetModifiers()") ||
    !script.includes("ChordClick(IntPtr h,ushort[] m") ||
    !script.includes("Move(IntPtr h,double x,double y") ||
    !script.includes("$operation.kind -eq 'terminal_text'") ||
    !script.includes("$operation.kind -eq 'reset_modifiers'") ||
    !script.includes("$operation.kind -eq 'click_chord'") ||
    !script.includes("$operation.kind -eq 'move'") ||
    script.includes("SendKeys") ||
    !script.includes("ClientToScreen")
  )
    throw new Error("Win32 SendInput contract 漂移");
  if (
    `${invokeOwnedNativeInput}${exerciseNativeShortcuts}`.includes("page.keyboard") ||
    nativeShortcutRegistrationChurnCycles <= 9
  )
    throw new Error("native shortcut 输入或 churn 门禁漂移");
  if (
    !String(sendAndAssertNativeShortcut).includes("reset_modifiers") ||
    !String(sendAndAssertNativeShortcut).includes("__JA_E2E_NATIVE_KEY_PROBE__") ||
    !String(exerciseNativeShortcuts).includes("waitForNativeShortcutContextReady")
  )
    throw new Error("native shortcut modifier、诊断或 context ACK 门禁漂移");
  if (
    !String(writeTerminalCommand).includes('kind: "terminal_text"') ||
    !String(writeTerminalCommand).includes("PTY echo") ||
    !String(writeTerminalCommand).includes("downstreamEcho")
  )
    throw new Error("终端物理键输入或 Enter 栅栏契约漂移");
  if (
    !String(enterOwnedComposerMarker).includes("value === marker") ||
    !String(enterOwnedComposerMarker).includes("focusOwnedMainComposer") ||
    !String(enterOwnedComposerMarker).includes("attempt <= 3")
  )
    throw new Error("Side Chat marker ACK 或有界聚焦恢复漂移");
  if (
    !String(installRawTauriEventProbe).includes("ja://native-shortcut") ||
    !String(exerciseProjectWorkbench).includes("exerciseNativeShortcuts")
  )
    throw new Error("native shortcut probe 或真实调用链缺失");
  if (
    JSON.stringify(nativeShortcutCases.map(({ command }) => command)) !==
    JSON.stringify(["review", "terminal", "preview", "files", "side_chat"])
  )
    throw new Error("native shortcut 五值漂移");
  const invokeProbeSource = String(installTauriInvokeProbeInPage);
  if (
    JSON.stringify(nativeShortcutCases[4]?.modifiers) !== JSON.stringify([0x11, 0xa4]) ||
    !invokeProbeSource.includes("__JA_E2E_TAURI_INVOKE_PHASE_TRACES__") ||
    !invokeProbeSource.includes("ja_native_shortcut_context_activate") ||
    (invokeProbeSource.match(/ja_terminal_poll/gu)?.length ?? 0) < 3 ||
    !String(tauriInvokeTrace).includes("__JA_E2E_TAURI_INVOKE_PHASE_TRACES__") ||
    !String(exerciseNativeShortcutHardReload).includes("addInitScript") ||
    !String(exerciseNativeShortcutHardReload).includes("ja_native_shortcut_context_activate") ||
    !String(exerciseNativeShortcutHardReload).includes("focusOwnedMainComposer")
  )
    throw new Error("Left Alt、按命令 trace 或 reload lease/focus 门禁漂移");
  normalizeOwnedNativeInput({ kind: "chord", key: 0x50, modifiers: [0x11], repetitions: 1 });
  normalizeOwnedNativeInput({ kind: "reset_modifiers" });
  normalizeOwnedNativeInput({ kind: "chord", key: 0x53, modifiers: [0x11, 0xa4], repetitions: 1 });
  normalizeOwnedNativeInput({ kind: "click", x: 1, y: 1, rendererWidth: 2, rendererHeight: 2 });
  normalizeOwnedNativeInput({ kind: "move", x: 1, y: 1, rendererWidth: 2, rendererHeight: 2 });
  normalizeOwnedNativeInput({
    kind: "click_chord",
    modifiers: [0x11],
    x: 1,
    y: 1,
    rendererWidth: 2,
    rendererHeight: 2,
  });
  normalizeOwnedNativeInput({ kind: "text", text: "Ja 原生输入" });
  normalizeOwnedNativeInput({ kind: "terminal_text", text: "x".repeat(nativeTextInputMaxLength) });
  let rejectedControlText = false;
  try {
    normalizeOwnedNativeInput({ kind: "text", text: `Ja${String.fromCharCode(0)}INPUT` });
  } catch {
    rejectedControlText = true;
  }
  let rejectedOversizedText = false;
  try {
    normalizeOwnedNativeInput({ kind: "text", text: "x".repeat(nativeTextInputMaxLength + 1) });
  } catch {
    rejectedOversizedText = true;
  }
  let rejectedModifiedClick = false;
  try {
    normalizeOwnedNativeInput({
      kind: "click_chord",
      modifiers: [0x12],
      x: 1,
      y: 1,
      rendererWidth: 2,
      rendererHeight: 2,
    });
  } catch {
    rejectedModifiedClick = true;
  }
  if (
    !rejectedControlText ||
    !rejectedOversizedText ||
    !rejectedModifiedClick ||
    nativeTextInputMaxLength !== 128
  )
    throw new Error("native text 或 modified click 边界漂移");
  const responsive = String(captureResponsiveVisualEvidence);
  if (
    !responsive.includes("width: 799") ||
    responsive.includes("width: 704") ||
    responsive.includes("width: 703")
  )
    throw new Error("responsive 800/799 门禁漂移");
}

/**
 * 用允许/拒绝 fixture 固定动态 CDP 与 owner 发现边界；端口 0 只作为 WebView2 分配请求，
 * 最终连接仍必须来自精确进程身份持有的非零 loopback listener。
 */
function assertCdpDiscoveryContract() {
  const owners = new Set([101, 202]);
  const parsed = parseOwnedTcpListeners(
    JSON.stringify([
      { LocalAddress: "127.0.0.1", LocalPort: 49_152, OwningProcess: 101 },
      { LocalAddress: "::1", LocalPort: 9_222, OwningProcess: 202 },
    ]),
    owners,
  );
  if (parsed.length !== 2 || parsed[0]?.port !== 49_152 || parsed[1]?.ownerPid !== 202) {
    throw new Error("WebView2 TCP listener 合法 fixture 解析漂移");
  }
  for (const fixture of [
    { LocalAddress: "0.0.0.0", LocalPort: 9_222, OwningProcess: 101 },
    { LocalAddress: "127.0.0.1", LocalPort: 0, OwningProcess: 101 },
    { LocalAddress: "127.0.0.1", LocalPort: 65_536, OwningProcess: 101 },
    { LocalAddress: "127.0.0.1", LocalPort: 9_222, OwningProcess: 303 },
  ]) {
    let rejected = false;
    try {
      parseOwnedTcpListeners(JSON.stringify(fixture), owners);
    } catch {
      rejected = true;
    }
    if (!rejected) throw new Error("WebView2 TCP listener 非法 fixture 未被拒绝");
  }
  for (const hostname of ["localhost", "127.0.0.1", "[::1]", "::1"]) {
    if (!isLoopbackDebuggerHostname(hostname))
      throw new Error("WebView2 回环 debugger hostname 合法 fixture 被拒绝");
  }
  for (const hostname of ["0.0.0.0", "example.com", "127.0.0.2", ""]) {
    if (isLoopbackDebuggerHostname(hostname))
      throw new Error("WebView2 回环 debugger hostname 非法 fixture 未被拒绝");
  }
  const configSource = String(writeE2eTauriConfig);
  const envSource = String(buildTauriEnv);
  const envContractSource = envSource.replace(/\/\*[\s\S]*?\*\//gu, "").replace(/\/\/.*$/gmu, "");
  if (
    configSource.includes("additionalBrowserArgs") ||
    configSource.includes("dataDirectory") ||
    !configSource.includes("io.github.kongweiguang.ja.e2e.run${frontendPort}") ||
    configSource.includes('identifier: "io.github.kongweiguang.ja.e2e"') ||
    !envContractSource.includes("WEBVIEW2_USER_DATA_FOLDER") ||
    !envContractSource.includes("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS") ||
    !envContractSource.includes("directCdpBrowserArgumentsForPort") ||
    !directCdpBrowserArguments.includes("--remote-debugging-port=0") ||
    !directCdpBrowserArguments.includes(
      "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection",
    ) ||
    !directCdpBrowserArguments.includes("--autoplay-policy=no-user-gesture-required") ||
    directCdpBrowserArguments.includes("--enable-automation") ||
    directCdpBrowserArguments.includes("--test-type=webdriver")
  ) {
    throw new Error("WebView2 环境级动态 CDP 启动合同漂移");
  }
  if (
    !envSource.includes("JA_E2E_WEBVIEW_DATA_DIR") ||
    !String(edgeDriverDataDirectory).includes("edgeDriverProfileDirectoryName")
  ) {
    throw new Error("EdgeDriver 与 Tauri WebView2 profile 所有权合同漂移");
  }
  if (
    !String(waitForCdp).includes("sameProcessIdentity") ||
    !String(waitForCdp).includes("/json/version") ||
    !String(ownedTcpListeners).includes("Get-NetTCPConnection")
  ) {
    throw new Error("WebView2 CDP 发现合同漂移");
  }
  if (
    !String(waitForCdp).includes("expectedPort === 0") ||
    !String(waitForCdp).includes("candidate.port === expectedPort") ||
    !String(buildTauriEnv).includes("directCdpBrowserArgumentsForPort(cdpPort)")
  ) {
    throw new Error("WebView2 动态端口 owner 复验合同漂移");
  }
  if (
    String(startTauri).includes("--features") ||
    String(warmTauriBinary).includes("tauri-smoke")
  ) {
    throw new Error("Windows 真窗不得通过测试 feature 改变生产编译面");
  }
  const mainSource = String(main);
  const warmIndex = mainSource.indexOf("await warmTauriBinary(");
  const primeIndex = mainSource.indexOf("await primeWebViewProfile(", warmIndex);
  const phaseIndex = mainSource.indexOf('for (const phase of ["first", "second"])');
  const dynamicPortIndex = mainSource.indexOf("const cdpPort =", phaseIndex);
  const configIndex = mainSource.indexOf("writeE2eTauriConfig(", dynamicPortIndex);
  const startIndex = mainSource.indexOf("startTauri(", configIndex);
  if (
    warmIndex < 0 ||
    primeIndex <= warmIndex ||
    phaseIndex <= primeIndex ||
    dynamicPortIndex <= phaseIndex ||
    configIndex <= dynamicPortIndex ||
    startIndex <= configIndex
  ) {
    throw new Error("Windows CDP 预编译、动态端口配置与 owner 栅栏顺序漂移");
  }
  const primeSource = String(primeWebViewProfile);
  if (
    !primeSource.includes("waitForWebViewProfileReady") ||
    !primeSource.includes("cleanupPhase") ||
    !String(waitForWebViewProfileReady).includes('join(profileRoot, "Local State")') ||
    !String(waitForWebViewProfileReady).includes('join(profileRoot, "Default", "Preferences")')
  ) {
    throw new Error("WebView2 fresh-profile 预热与完整清理合同漂移");
  }
}

/**
 * 直接检查纯 TOML fixture 的字段闭集，避免注释或死代码中的旧名让字符串源码检查误报。
 * 两个可空默认值必须使用可逆 null sentinel，省略任一处都会重新引入 Native round-trip 漂移。
 */
function assertSettingsV4Contract() {
  const fixtures = [
    buildSettingsDocument(undefined),
    buildSettingsDocument({
      api: "anthropic_messages",
      baseUrl: "http://127.0.0.1:9/v1",
      model: "ja-e2e-anthropic",
    }),
  ];
  const required = [
    "schema_version = 4",
    "[[providers]]",
    "[[providers.models]]",
    'default_provider_id = "provider_e2e"',
    'default_model_id = "model_e2e"',
  ];
  const forbidden = [
    "schema_version = 3",
    "schema_version = 2",
    "default_profile_id",
    "[[profiles]]",
    "openai_chat_completions",
    "default_reasoning_effort",
    "reasoning_efforts",
    "skill_ids",
    "mcp_ids",
    "input_modalities",
  ];
  for (const fixture of fixtures) {
    if (required.some((token) => !fixture.split(/\r?\n/u).includes(token))) {
      throw new Error("v4 Provider/Model 设置 fixture 缺少必填字段");
    }
    if (forbidden.some((token) => fixture.includes(token))) {
      throw new Error("v4 设置 fixture 重新引入旧 schema、字段或已移除 API");
    }
    if (
      (fixture.match(/^default_reasoning_level = \{ __ja_null = true \}$/gmu)?.length ?? 0) !== 2
    ) {
      throw new Error("v4 设置 fixture 的 reasoning null sentinel 不完整");
    }
    if ((fixture.match(/^reasoning_level_map = \{\}$/gmu)?.length ?? 0) !== 1) {
      throw new Error("v4 设置 fixture 的 reasoning level map 不完整");
    }
  }
  if (!fixtures[0].includes('api = "openai_responses"')) {
    throw new Error("v4 设置 fixture 缺少 OpenAI Responses API");
  }
  if (!fixtures[1].includes('api = "anthropic_messages"')) {
    throw new Error("v4 设置 fixture 缺少 Anthropic Messages API");
  }
}

/**
 * 固定自动标题 fixture 的传输终态：开始发送不能算完成，只有 `finish` 或真实断开才能解除
 * 等待；同时锁住事件接线，避免未来又把 keep-alive socket close 当作正常响应完成信号。
 */
function assertAutomaticTitleFixtureLifecycleContract() {
  if (
    titleFixtureExchangeFinished({ responded: true, finished: false, disconnected: false }) ||
    !titleFixtureExchangeFinished({ responded: true, finished: true, disconnected: false }) ||
    !titleFixtureExchangeFinished({ responded: false, finished: false, disconnected: true })
  ) {
    throw new Error("自动标题 fixture 响应终态判定漂移");
  }
  const source = String(startAutomaticTitleProviderFixture);
  if (
    !source.includes('response.once("finish", markFinished)') ||
    !source.includes('response.once("close", markDisconnected)') ||
    !source.includes("titleFixtureExchangeFinished(attempt)") ||
    !source.includes("if (!attempt.finished) attempt.disconnected = true")
  ) {
    throw new Error("自动标题 fixture finish/close 事件接线漂移");
  }
}

/** 锁定桌面生产门禁必须走到的真实 UI/native 命令，防止 helper 再次成为未接线死代码。 */
function assertDesktopInteractionContract() {
  const firstSession = String(runFirstSession);
  const workbench = String(exerciseProjectWorkbench);
  const restart = String(runRestartSession);
  const runtimeReady = String(waitForRuntimeReady);
  const connectedSurfaces = [
    exerciseNativeShortcutHardReload,
    runAutomaticTitleAcceptanceSession,
    runAutomaticTitleRestartSession,
    runFirstSession,
    runRestartSession,
  ].map(String);
  if (
    !firstSession.includes("applyVisualPreferences") ||
    !firstSession.includes("beginRealtimeDraftObservation") ||
    !firstSession.includes("assertRealtimeDeltaBeforeTerminal") ||
    !firstSession.includes("exerciseAttachmentDraft") ||
    !workbench.includes("verifyJoinedInspectorLayout") ||
    !String(exerciseAttachmentDraft).includes("ja_attachment_import") ||
    !String(exerciseAttachmentDraft).includes("ja_attachment_discard") ||
    !String(runApprovalLifecycleMatrix).includes("ja_turn_cancel") ||
    !String(exerciseApprovalRevisionCas).includes("ja_approval_respond") ||
    !String(runApprovalLifecycleMatrix).includes("exerciseApprovalResponderCompetition") ||
    !firstSession.includes(
      "const appearanceDeadline = Math.min(deadline, Date.now() + turnDeadlineMs)",
    ) ||
    !firstSession.includes("applyVisualPreferences(page, appearanceDeadline)") ||
    !restart.includes("expectedPendingApproval")
  ) {
    throw new Error("桌面附件、流式、右栏、取消或审批恢复门禁未接入真实调用链");
  }
  if (
    !runtimeReady.includes('name: "本地运行时：已连接"') ||
    !runtimeReady.includes("captureRuntimeStartupState") ||
    !runtimeReady.includes("terminalFailures") ||
    !runtimeReady.includes("turnDeadlineMs") ||
    connectedSurfaces.some(
      (source) =>
        !source.includes("waitForRuntimeReady") ||
        source.includes("本地运行时：已连接，打开运行时设置"),
    )
  ) {
    throw new Error("桌面运行时 status 语义或局部启动期限门禁漂移");
  }
  const nativeDialog = String(completeOwnedFileDialog);
  if (
    !nativeDialog.includes("#32770") ||
    !nativeDialog.includes("SendInput") ||
    !nativeDialog.includes("$foregroundDeadline=[DateTime]::UtcNow.AddSeconds(3)") ||
    !nativeDialog.includes("$foregroundPid=[uint32]0") ||
    !nativeDialog.includes("stderr: redact(stderrTail)") ||
    nativeDialog.includes("clipboard")
  ) {
    throw new Error("原生附件对话框 owner 或无剪贴板输入合同漂移");
  }
}

/** 不创建进程或文件地运行全部安全契约，供 Windows 真窗前先做 dry-run。 */
function assertStaticContracts() {
  assertSanitizerContract();
  assertCreationBoundaryContract();
  assertTrayExitContract();
  assertPreexistingJaGuardContract();
  assertOuterTerminalCapabilityContract();
  assertApprovalScopeContract();
  assertGracefulAbortContract();
  assertExitTraceContract();
  assertNativeInputContract();
  assertCdpDiscoveryContract();
  assertSettingsV4Contract();
  assertAutomaticTitleFixtureLifecycleContract();
  assertDesktopInteractionContract();
  if (!String(clickVerifiedControl).includes("scrollIntoViewIfNeeded"))
    throw new Error("真实控件滚动命中门禁漂移");
  if (
    !String(reorderWorkbenchTab).includes("waitForRenderedSurface") ||
    !String(reorderWorkbenchTab).includes("readWorkbenchPreference")
  )
    throw new Error("工作区 Tab 可见拖序或 v10 ACK 门禁漂移");
}

/**
 * 先预热全新隔离 WebView2 profile，再运行两轮真实桌面生命周期并分别保存进程树与
 * CDP 证据；第二轮成功不能掩盖第一轮 Cargo、Java 或 WebView2 泄漏。
 */
async function main() {
  if (process.platform !== "win32") {
    throw new Error("该 E2E 仅支持 Windows 11");
  }
  if (automaticTitleAcceptanceMode && configuredRealProviderMode) {
    throw new Error("自动标题验收禁止同时启用外部 Provider 模式");
  }
  const runId = `run_${Date.now().toString(36)}`;
  assertStaticContracts();
  await ensureJava25Runtime();
  let providerConfig = readRealProviderConfig();
  const runDeadline = createDeadline("E2E 全局期限", runDeadlineMs);
  // readRealProviderConfig 已删除环境中的明文 key；子进程只接收私有设置文档路径。
  delete process.env.JA_E2E_AUTOMATIC_TITLE;
  const baseEnv = { ...process.env };
  let directories = {};
  let cleanupRoot;
  let frontendPort;
  let edgeDriver;
  let edgeDriverPort;
  let tauriConfigPath;
  let previewFixture;
  let automaticTitleFixture;
  const evidence = {
    runId,
    frontendPort: undefined,
    setup: {
      directories: "pending",
      ports: "pending",
      compile: "pending",
      config: "pending",
      profilePrime: "pending",
      jar: "pending",
      settings: "pending",
      edgeDriver: configuredEdgeDriverPath === undefined ? "disabled" : "pending",
      provider: automaticTitleAcceptanceMode
        ? "automatic_title_loopback"
        : providerConfig === undefined
          ? "fake"
          : "real_loopback",
    },
    visualContract: {
      requested: visualEvidenceDirectory !== undefined,
      theme: visualTheme,
      responsiveMatrix: {
        workbench: {
          surface: "native_window",
          nativeWindow: true,
          sizes: responsiveRendererMatrix,
        },
        generalConversation: {
          surface: "renderer_emulation",
          nativeWindow: false,
          sizes: responsiveRendererMatrix,
        },
      },
      referenceState: { surface: "native_window", maximized: true, viewport: nativeVisualViewport },
      requireReviewEvidence,
    },
    realRuntime: {},
    preexistingJaGuard: {
      baseline: { status: "pending", snapshotStatus: "not_captured" },
      cleanupVerifications: [],
      final: { status: "pending", snapshotStatus: "not_captured" },
    },
    profilePrime: {},
    first: {},
    second: {},
    tree: [],
  };
  let firstInput;
  let firstExpectedFinal;
  let firstConversationScope;
  let firstWorkbench;
  let firstAttachment;
  let firstPendingApproval;
  let automaticTitleExpectation;
  let preexistingJaIdentities = [];
  let preexistingJaBaselineReady = false;
  try {
    throwIfAborted(runDeadline.signal);
    directories = await createRunDirectories();
    cleanupRoot = directories.root;
    visualEvidenceRunDirectory =
      visualEvidenceDirectory === undefined ? undefined : join(directories.root, "visual-evidence");
    evidence.setup.directories = "ready";
    const preexistingBaseline = await capturePreexistingJaBaseline(directories);
    evidence.preexistingJaGuard.baseline = preexistingBaseline.evidence;
    if (preexistingBaseline.failure !== undefined) throw preexistingBaseline.failure;
    preexistingJaIdentities = preexistingBaseline.identities;
    preexistingJaBaselineReady = true;
    edgeDriver = await validateConfiguredEdgeDriver(runDeadline.signal);
    if (edgeDriver !== undefined) evidence.setup.edgeDriver = { version: edgeDriver.version };
    frontendPort = await raceWithSignal(() => reservePort(), runDeadline.signal);
    if (edgeDriver !== undefined) {
      do {
        edgeDriverPort = await raceWithSignal(() => reservePort(), runDeadline.signal);
      } while (edgeDriverPort === frontendPort);
    }
    const usedPorts = new Set(
      [frontendPort, edgeDriverPort].filter((port) => Number.isSafeInteger(port)),
    );
    evidence.frontendPort = frontendPort;
    evidence.setup.ports = "ready";
    evidence.realRuntime.before = await captureRealRuntimeEvidence(baseEnv, directories);
    const jar = await assertAppServerJar(runDeadline.signal);
    evidence.setup.jar = { present: true, path: redact(jar, directories) };
    const [pnpmCommand, cargoCommand, gitCommand] = await Promise.all([
      locateCommand("pnpm.cmd", runDeadline.signal),
      locateCargoCommand(runDeadline.signal),
      locateCommand("git.exe", runDeadline.signal),
    ]);
    await warmTauriBinary(directories, baseEnv, cargoCommand, runDeadline.signal);
    evidence.setup.compile = "ready";
    await initializeWorkspaceFixture(directories.workspace, gitCommand, runDeadline.signal);
    previewFixture = await startPreviewFixture();
    if (automaticTitleAcceptanceMode) {
      automaticTitleFixture = await startAutomaticTitleProviderFixture();
      providerConfig = automaticTitleFixture.providerConfig;
    }
    if (providerConfig?.configureViaUi === true) {
      await mkdir(directories.home, { recursive: true });
      evidence.setup.settings = "pending_ui_configuration";
    } else {
      await writeSettings(directories.home, providerConfig);
      evidence.setup.settings = "ready";
    }
    assertLaunchRuntimeRoot(directories);
    tauriConfigPath = await primeWebViewProfile({
      runId,
      directories,
      frontendPort,
      baseEnv,
      pnpmCommand,
      cargoCommand,
      productionRuntime: providerConfig !== undefined,
      preexistingJaIdentities,
      evidence,
      runDeadline,
    });
    evidence.setup.profilePrime = "ready";
    for (const phase of ["first", "second"]) {
      throwIfAborted(runDeadline.signal);
      assertLaunchRuntimeRoot(directories);
      const exitTracePath = join(directories.runtime, `ja-exit-trace-${runId}-${phase}.jsonl`);
      const edgeDriverSessionPath =
        edgeDriver === undefined
          ? undefined
          : join(directories.runtime, `edgedriver-session-${phase}.json`);
      // exact-feature 预编译已完成；直接模式使用 WebView2 动态端口并从精确进程 owner 发现，
      // EdgeDriver 才预留自己的 HTTP 端口。任何无关 listener 都无法通过 owner 复验。
      const cdpPort = await raceWithSignal(() => reservePort(usedPorts), runDeadline.signal);
      usedPorts.add(cdpPort);
      tauriConfigPath = await writeE2eTauriConfig(
        directories,
        frontendPort,
        edgeDriver !== undefined,
      );
      if (phase === "first") {
        evidence.setup.config = { ready: true, path: redact(tauriConfigPath, directories) };
      }
      if (edgeDriverSessionPath !== undefined) await rm(edgeDriverSessionPath, { force: true });
      const launch = startTauri(
        directories,
        frontendPort,
        cdpPort,
        tauriConfigPath,
        exitTracePath,
        edgeDriver,
        edgeDriverPort,
        edgeDriverSessionPath,
        baseEnv,
        pnpmCommand,
        cargoCommand,
        providerConfig !== undefined,
        true,
      );
      if (!launch.child.pid) {
        throw new Error(`${phase} Tauri launcher 没有 PID`);
      }
      const rootPid = launch.child.pid;
      const observed = new Map();
      const incompleteObserved = createIncompleteObserved();
      let rootIdentity;
      let watcher;
      let cleanupFailure;
      let phaseStage = `${phase}:launch`;
      const recordStage = (value) => {
        if (typeof value === "string" && value.length <= 64) {
          phaseStage = value;
        }
      };
      try {
        recordStage(`${phase}:root_identity`);
        rootIdentity = await waitForRootIdentity(
          rootPid,
          Math.min(runDeadline.deadline, Date.now() + 10_000),
          runDeadline.signal,
        );
        observed.set(rootIdentity.pid, rootIdentity);
        const initial = await processSnapshot(runDeadline.signal);
        const initialTree = processTree(rootIdentity, initial, incompleteObserved);
        if (initialTree === undefined) {
          throw new Error(`${phase} 未能在初始快照中重验 launcher root`);
        }
        for (const [pid, entry] of initialTree) {
          observed.set(pid, entry);
        }
        recordStage(`${phase}:watcher`);
        watcher = startProcessWatcher(
          rootIdentity,
          observed,
          incompleteObserved,
          runDeadline.signal,
        );
        const sessionDeadline = runDeadline.deadline;
        recordStage(`${phase}:cdp`);
        const cdp = await waitForCdp(
          cdpPort,
          edgeDriverSessionPath,
          rootIdentity,
          incompleteObserved,
          Math.min(sessionDeadline, Date.now() + cdpStartupDeadlineMs),
          launch,
          runDeadline.signal,
        );
        evidence[phase].cdp = {
          port: cdp.port,
          browserPath: cdp.browserPath,
          transport: cdp.transport,
        };
        recordStage(`${phase}:connect_cdp`);
        const browser = await raceWithSignal(
          () => chromium.connectOverCDP(cdp.endpoint),
          runDeadline.signal,
        );
        let sessionError;
        let page;
        let diagnostics;
        try {
          recordStage(`${phase}:page`);
          page = await raceWithSignal(
            () => waitForPage(browser, frontendPort, sessionDeadline, runDeadline.signal),
            runDeadline.signal,
          );
          recordStage(`${phase}:event_probe`);
          await raceWithSignal(() => installRawTauriEventProbe(page), runDeadline.signal);
          await raceWithSignal(() => installTauriInvokeProbe(page), runDeadline.signal);
          recordStage(`${phase}:diagnostics`);
          diagnostics = attachPageDiagnostics(page, directories);
          if (phase === "first") {
            if (automaticTitleAcceptanceMode) {
              if (automaticTitleFixture === undefined) {
                throw new Error("自动标题验收缺少 loopback fixture");
              }
              let isolation;
              const titleAcceptance = await raceWithSignal(
                () =>
                  runAutomaticTitleAcceptanceSession(
                    page,
                    sessionDeadline,
                    directories,
                    automaticTitleFixture,
                    (value) => {
                      isolation = value;
                    },
                    runDeadline.signal,
                    recordStage,
                  ),
                runDeadline.signal,
              );
              automaticTitleExpectation = titleAcceptance;
              evidence.first = {
                ...evidence.first,
                automaticTitle: titleAcceptance,
                pageUrl: redact(page.url(), directories),
                pageTitle: redact(await page.title(), directories),
                diagnostics,
                launcher: launcherOutputSummary(launch, directories),
                runtime: redact(directories.runtime, directories),
                isolation,
              };
            } else {
              let afterSend;
              let isolation;
              const first = await raceWithSignal(
                () =>
                  runFirstSession(
                    page,
                    runId,
                    sessionDeadline,
                    directories,
                    previewFixture,
                    { rootIdentity, observed, incompleteObserved },
                    providerConfig,
                    (value) => {
                      evidence.first.settings = value;
                    },
                    (value) => {
                      afterSend = value;
                    },
                    (value) => {
                      isolation = value;
                    },
                    runDeadline.signal,
                    recordStage,
                  ),
                runDeadline.signal,
              );
              firstInput = first.input;
              firstExpectedFinal = first.expectedFinal;
              firstConversationScope = first.conversationScope;
              firstAttachment = first.attachmentConversation;
              firstPendingApproval = first.approvalMatrix?.restartPending;
              if (providerConfig?.configureViaUi === true) {
                evidence.setup.settings = "configured_via_ui";
              }
              const { restartTerminalSessionIds, ...workbenchEvidence } = first.workbench;
              if (
                !Array.isArray(restartTerminalSessionIds) ||
                restartTerminalSessionIds.length !==
                  first.workbench.terminal.persistedPaneIds.length
              ) {
                throw new Error("第一轮缺少重启 fresh-session 对照 identity");
              }
              firstWorkbench = {
                originalThreadId: first.workbench.originalThreadId,
                projectThreadId: first.workbench.projectThreadId,
                preference: first.workbench.tabs.preference,
                persistedPaneIds: first.workbench.terminal.persistedPaneIds,
                priorTerminalSessionIds: restartTerminalSessionIds,
              };
              evidence.first = {
                ...evidence.first,
                timeline: first.timelineText,
                parallel: first.parallel,
                approvalMatrix: first.approvalMatrix,
                workbench: workbenchEvidence,
                visual: first.visual,
                appearance: first.appearance,
                realtime: first.realtime,
                attachment: first.attachment,
                pageUrl: redact(page.url(), directories),
                pageTitle: redact(await page.title(), directories),
                afterSend,
                diagnostics,
                launcher: launcherOutputSummary(launch, directories),
                runtime: redact(directories.runtime, directories),
                isolation,
              };
            }
          } else {
            if (automaticTitleAcceptanceMode) {
              if (automaticTitleFixture === undefined || automaticTitleExpectation === undefined) {
                throw new Error("重启标题验收缺少第一轮证据");
              }
              const restarted = await raceWithSignal(
                () =>
                  runAutomaticTitleRestartSession(
                    page,
                    automaticTitleExpectation,
                    automaticTitleFixture,
                    sessionDeadline,
                    runDeadline.signal,
                  ),
                runDeadline.signal,
              );
              evidence.second = {
                ...evidence.second,
                automaticTitle: restarted,
                pageUrl: redact(page.url(), directories),
                pageTitle: redact(await page.title(), directories),
                diagnostics,
                launcher: launcherOutputSummary(launch, directories),
                runtime: redact(directories.runtime, directories),
                isolation: assertRuntimeIsolation(
                  await processSnapshot(runDeadline.signal),
                  directories,
                ),
              };
            } else {
              if (
                !firstInput ||
                !firstExpectedFinal ||
                !firstConversationScope ||
                firstWorkbench === undefined
              ) {
                throw new Error("重启断言缺少第一轮输入");
              }
              let isolation;
              const restarted = await raceWithSignal(
                () =>
                  runRestartSession(
                    page,
                    firstInput,
                    firstExpectedFinal,
                    firstConversationScope,
                    firstWorkbench,
                    firstAttachment,
                    firstPendingApproval,
                    { rootIdentity, observed, incompleteObserved },
                    sessionDeadline,
                    directories,
                    (value) => {
                      isolation = value;
                    },
                    runDeadline.signal,
                  ),
                runDeadline.signal,
              );
              evidence.second = {
                ...evidence.second,
                timeline: restarted.timeline,
                workbench: restarted.workbench,
                attachment: restarted.attachment,
                approvalRecovery: restarted.approvalRecovery,
                pageUrl: redact(page.url(), directories),
                pageTitle: redact(await page.title(), directories),
                diagnostics,
                launcher: launcherOutputSummary(launch, directories),
                runtime: redact(directories.runtime, directories),
                isolation,
              };
            }
          }
        } catch (error) {
          evidence[phase].stage = phaseStage;
          if (page !== undefined) {
            evidence[phase].afterFailure = await captureUiEvidence(
              page,
              directories,
              runDeadline.signal,
            );
            if (phase === "first" && evidence[phase].afterSend === undefined) {
              evidence[phase].afterSend = evidence[phase].afterFailure;
            }
          }
          evidence[phase].diagnostics = diagnostics ?? {
            console: [],
            pageErrors: [],
            requestFailed: [],
            rawTauriEvents: [],
          };
          evidence[phase].launcher = launcherOutputSummary(launch, directories);
          sessionError = error;
          throw error;
        } finally {
          // Playwright 的 CDP Browser 暴露 close()，但它只关闭 connectOverCDP 客户端 transport；
          // 下方仍由产品真实托盘退出请求 Tauri shutdown；CDP transport 不拥有应用生命周期。
          if (page !== undefined) {
            await raceWithSignal(() => removeRawTauriEventProbe(page), runDeadline.signal).catch(
              () => undefined,
            );
            await raceWithSignal(() => removeTauriInvokeProbe(page), runDeadline.signal).catch(
              () => undefined,
            );
          }
          try {
            await raceWithSignal(() => browser.close(), runDeadline.signal);
          } catch (closeError) {
            if (!sessionError) {
              sessionError = closeError;
            }
          }
        }
        if (sessionError !== undefined) {
          throw sessionError;
        }
      } finally {
        evidence[phase].stage = phaseStage;
        try {
          cleanupFailure = await cleanupPhase(
            runId,
            phase,
            rootIdentity,
            observed,
            incompleteObserved,
            preexistingJaIdentities,
            watcher,
            directories,
            evidence,
            runDeadline.signal,
          );
        } finally {
          // 在托盘退出/force cleanup 后捕获 stream tail，使进程退出期间发出的 shutdown marker
          // 能保留在 summary 中。
          evidence[phase].launcher = launcherOutputSummary(launch, directories);
        }
      }
      if (cleanupFailure !== undefined) {
        throw cleanupFailure;
      }
    }
    const finalPreexistingVerification = await verifyPreexistingJaGuard(
      preexistingJaIdentities,
      directories,
      "run final",
    );
    evidence.preexistingJaGuard.final = finalPreexistingVerification.evidence;
    if (finalPreexistingVerification.failure !== undefined)
      throw finalPreexistingVerification.failure;
    if (automaticTitleAcceptanceMode) {
      if (
        evidence.first.automaticTitle === undefined ||
        evidence.second.automaticTitle === undefined ||
        evidence.first.automaticTitle.titles?.length !== 4 ||
        evidence.second.automaticTitle.titles?.length !== 4
      ) {
        throw new Error("自动标题真窗证据不完整");
      }
      evidence.acceptance = { blocked: [], gated: [] };
    } else {
      evidence.acceptance = {
        blocked: [
          evidence.first.workbench?.files?.nativeDrop,
          evidence.first.workbench?.files?.trash,
          {
            status: "blocked",
            capability: "terminal_native_ime",
            reason: evidence.first.workbench?.terminal?.unicodeInput?.nativeImeComposition,
          },
          {
            status: "blocked",
            capability: "dpi_125_150",
            reason:
              "system DPI changes require separate Windows sessions and are not synthesized by CDP",
          },
        ].filter((item) => item?.status === "blocked"),
        gated: [evidence.first.workbench?.files?.trash].filter((item) => item?.status === "gated"),
      };
      if (
        !String(evidence.first.timeline).includes(firstInput) ||
        !String(evidence.first.timeline).includes(firstExpectedFinal) ||
        !String(evidence.second.timeline).includes(firstInput) ||
        !String(evidence.second.timeline).includes(firstExpectedFinal)
      ) {
        throw new Error("DOM 时间线缺少用户消息或最终答复");
      }
    }
    evidence.realRuntime.after = await captureRealRuntimeEvidence(baseEnv, directories);
    assertRealRuntimeUnchanged(evidence.realRuntime.before, evidence.realRuntime.after);
    evidence.realRuntime.unchanged = true;
    evidence.visualContract.published = await publishVisualEvidenceRun();
    if (visualEvidenceDirectory !== undefined && evidence.visualContract.published.length === 0) {
      throw new Error("桌面视觉验收未发布任何截图");
    }
    const summaryPath = await writeRunSummary(runId, evidence, "passed", undefined, directories);
    process.stdout.write(
      `JA_E2E_OK run=${runId} cdp=${evidence.first.cdp?.port},${evidence.second.cdp?.port} blocked=${evidence.acceptance.blocked.length} summary=${summaryPath}\n`,
    );
  } catch (error) {
    if (directories.root === undefined && typeof error?.e2eRoot === "string") {
      directories = { root: error.e2eRoot };
      cleanupRoot = error.e2eRoot;
    }
    const currentStage = evidence.first.stage ?? evidence.second.stage ?? "setup";
    const errorMessage = error instanceof Error ? error.message : String(error ?? "E2E 失败");
    let reportError = new Error(`${errorMessage} [stage=${currentStage}]`, { cause: error });
    if (evidence.realRuntime.before !== undefined) {
      try {
        evidence.realRuntime.after = await captureRealRuntimeEvidence(baseEnv, directories);
        assertRealRuntimeUnchanged(evidence.realRuntime.before, evidence.realRuntime.after);
        evidence.realRuntime.unchanged = true;
      } catch (runtimeError) {
        reportError = new Error(
          `${redact(error?.stack ?? error, directories)}; ${redact(runtimeError?.stack ?? runtimeError, directories)}`,
        );
      }
    }
    if (preexistingJaBaselineReady) {
      const finalPreexistingVerification = await verifyPreexistingJaGuard(
        preexistingJaIdentities,
        directories,
        "failed run final",
      );
      evidence.preexistingJaGuard.final = finalPreexistingVerification.evidence;
      if (finalPreexistingVerification.failure !== undefined) {
        reportError = new Error(
          `${reportError.message}; ${finalPreexistingVerification.failure.message}`,
          { cause: reportError },
        );
      }
    }
    const summaryPath = await writeRunSummary(runId, evidence, "failed", reportError, directories);
    process.stderr.write(`JA_E2E_FAILED run=${runId} summary=${summaryPath}\n`);
    throw reportError;
  } finally {
    runDeadline.cancel();
    await automaticTitleFixture?.close().catch(() => undefined);
    await previewFixture?.close().catch(() => undefined);
    await cleanupRunRoot(cleanupRoot ?? directories.root);
  }
}

if (process.env.JA_E2E_STATIC_CONTRACT_ONLY === "1") {
  assertStaticContracts();
  process.stdout.write("JA_E2E_STATIC_CONTRACT_OK\n");
} else {
  const releaseDesktopSmokeLock = await acquireDesktopSmokeLock();
  try {
    const exitCode = await main();
    if (Number.isInteger(exitCode)) process.exitCode = exitCode;
  } finally {
    await releaseDesktopSmokeLock();
  }
}
