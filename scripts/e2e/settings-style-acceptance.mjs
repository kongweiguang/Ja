// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Settings 真实组件的浏览器验收矩阵。
 *
 * 默认启动隔离的 Vite + headless Edge 页面，fixture 端口为本地替身，仅验证布局、焦点与反馈。
 * 显式 JA_SETTINGS_NATIVE_ONLY=1 才启动独立 Tauri/JDK25 环境；两种模式均不访问用户配置
 * 或真实计费 Provider。原生探针与浏览器证据分别报告，不以模拟端口证明配置持久化。
 */
import { createServer } from "vite";
import { chromium, expect } from "@playwright/test";
import { execFile, spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { createServer as createTcpServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const repoRoot = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const previewPath = join(repoRoot, "apps/desktop/tests/app/e2e/settingsStyle.preview.tsx");
const evidenceDirectory = join(tmpdir(), `ja-settings-style-acceptance-${Date.now()}`);
const previewPort = 15482;
const previewEntry = "/settings-style-preview.tsx";
const execFileAsync = promisify(execFile);

/** 从系统分配两个 loopback 端口，避免 native probe 与用户或其它 runner 猜测固定端口冲突。 */
async function allocateLoopbackPort() {
  const server = createTcpServer();
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  await new Promise((resolvePromise) => server.close(resolvePromise));
  if (address === null || typeof address === "string" || !Number.isSafeInteger(address.port)) {
    throw new Error("native probe failed to allocate a loopback port");
  }
  return address.port;
}

/** 只创建本轮 Tauri 配置与运行目录，所有应用状态都留在 probe 临时根下。 */
async function createNativeProbeDirectories(frontendPort) {
  const root = await mkdtemp(join(tmpdir(), "ja-settings-native-probe-"));
  const runtime = join(root, "runtime");
  const directories = {
    root,
    runtime,
    cargoTarget: join(root, "cargo-target"),
    profile: join(root, "webview"),
    appData: join(root, "appdata"),
    localAppData: join(root, "localappdata"),
    userProfile: join(root, "userprofile"),
    workspace: join(root, "workspace"),
  };
  await mkdir(runtime, { recursive: true });
  await Promise.all(
    Object.values(directories)
      .filter((directory) => directory !== root && directory !== runtime)
      .map((directory) => mkdir(directory, { recursive: true })),
  );
  const productionConfig = JSON.parse(
    await readFile(join(repoRoot, "src-tauri", "tauri.conf.json"), "utf8"),
  );
  const windowsConfigPath = join(repoRoot, "src-tauri", "tauri.windows.conf.json");
  const windowsConfig = JSON.parse(await readFile(windowsConfigPath, "utf8"));
  const productionWindow = {
    ...productionConfig.app.windows.find((window) => window.label === "main"),
    ...(windowsConfig.app?.windows?.find((window) => window.label === "main") ?? {}),
    visible: false,
  };
  const origin = `http://127.0.0.1:${frontendPort}`;
  const configPath = join(runtime, "tauri.native.conf.json");
  await writeFile(
    configPath,
    `${JSON.stringify(
      {
        identifier: `io.github.kongweiguang.ja.e2e.settingsnative${frontendPort}`,
        build: { devUrl: origin },
        app: {
          windows: [productionWindow],
          security: {
            devCsp: `default-src 'self'; connect-src 'self' ipc: http://ipc.localhost ${origin} ws://127.0.0.1:${frontendPort}; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self' data:; worker-src 'self' blob:; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`,
          },
        },
      },
      null,
      2,
    )}\n`,
    "utf8",
  );
  return { ...directories, configPath };
}

/** 等待本轮 WebView2 CDP listener；没有 listener 时保留 launcher 日志并失败关闭。 */
async function waitForNativeCdp(port, child, stdout, stderr) {
  const deadline = Date.now() + 300_000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) break;
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`, {
        signal: AbortSignal.timeout(1_000),
      });
      if (response.ok) return;
    } catch {
      // Tauri、Vite、JVM 与 WebView2 listener 分阶段启动；继续有界等待。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 500));
  }
  throw new Error(
    `native probe CDP 未就绪 pid=${child.pid ?? "none"} exited=${child.exitCode ?? "no"} stdout=${stdout.join("").slice(-3000)} stderr=${stderr.join("").slice(-3000)}`,
  );
}

/** 在真实 WebView2 中只读取首屏并尝试进入设置，证明独立 profile/CDP 没有误连用户实例。 */
async function inspectNativeSettings(cdpPort, evidencePath) {
  const browser = await chromium.connectOverCDP(`http://127.0.0.1:${cdpPort}`);
  try {
    const page = browser.contexts().flatMap((context) => context.pages())[0];
    if (page === undefined) throw new Error("native probe found no WebView2 page");
    await page.setViewportSize({ width: 1280, height: 820 });
    await page.waitForLoadState("domcontentloaded");
    await expect
      .poll(
        async () =>
          (await page.locator(".ja-settings").isVisible()) ||
          (await page.getByRole("button", { name: "设置", exact: true }).isVisible()),
        { timeout: 60_000 },
      )
      .toBe(true);
    await page.screenshot({
      path: join(evidencePath, "native-first-window.png"),
      animations: "disabled",
    });
    const settingsButton = page.getByRole("button", { name: "设置", exact: true });
    if (!(await page.locator(".ja-settings").isVisible())) {
      await settingsButton.click();
    }
    await expect(page.locator(".ja-settings")).toBeVisible({ timeout: 30_000 });
    await page.screenshot({
      path: join(evidencePath, "native-settings.png"),
      animations: "disabled",
    });
    return {
      pages: browser.contexts().flatMap((context) => context.pages()).length,
      url: page.url(),
      title: await page.title(),
      settingsVisible: true,
    };
  } finally {
    await browser.close();
  }
}

/** 仅在显式 native-only 模式运行独立 Tauri；清理范围严格限定为本轮 child PID 树。 */
async function runNativeProbe() {
  if (process.platform !== "win32") throw new Error("native settings probe requires Windows");
  const frontendPort = await allocateLoopbackPort();
  const cdpPort = await allocateLoopbackPort();
  const directories = await createNativeProbeDirectories(frontendPort);
  const javaHome = process.env.JA_E2E_JAVA_HOME?.trim() || process.env.JAVA_HOME?.trim();
  if (javaHome === undefined) throw new Error("native settings probe requires JA_E2E_JAVA_HOME");
  const javaExecutable = join(javaHome, "bin", "java.exe");
  const jar = join(repoRoot, "app-server", "target", "ja-app-server.jar");
  const pnpm = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
  const stdout = [];
  const stderr = [];
  const environment = {
    ...process.env,
    // 隔离应用用户目录不应隐藏宿主已安装工具链；只读复用工具缓存，产物仍独立。
    CARGO_HOME: process.env.CARGO_HOME || join(process.env.USERPROFILE, ".cargo"),
    RUSTUP_HOME: process.env.RUSTUP_HOME || join(process.env.USERPROFILE, ".rustup"),
    APPDATA: directories.appData,
    CARGO_TARGET_DIR: process.env.JA_SETTINGS_NATIVE_TARGET_DIR || directories.cargoTarget,
    JAVA_HOME: javaHome,
    JA_DEBUG_JAR: jar,
    JA_DEBUG_JAVA: javaExecutable,
    JA_E2E_DEV_PORT: String(frontendPort),
    JA_E2E_EXIT_TRACE_PATH: join(directories.runtime, "exit-trace.jsonl"),
    JA_E2E_JAVA_HOME: javaHome,
    JA_E2E_RUNTIME_ROOT: directories.runtime,
    JA_E2E_SCREENSHOT_DIR: directories.root,
    JA_TEST_JAVA: javaExecutable,
    LOCALAPPDATA: directories.localAppData,
    PATH: `${join(javaHome, "bin")};${process.env.PATH ?? ""}`,
    USERPROFILE: directories.userProfile,
    VITE_JA_E2E_PROJECT_PATH: directories.workspace,
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection --autoplay-policy=no-user-gesture-required --remote-debugging-port=${cdpPort}`,
    WEBVIEW2_USER_DATA_FOLDER: directories.profile,
  };
  await execFileAsync(javaExecutable, ["-version"], {
    env: environment,
    windowsHide: true,
    timeout: 15_000,
  });
  await execFileAsync("mvn.cmd", ["-version"], {
    cwd: repoRoot,
    env: environment,
    shell: true,
    windowsHide: true,
    timeout: 15_000,
  });
  const child = spawn(pnpm, ["tauri", "dev", "--no-watch", "--config", directories.configPath], {
    cwd: repoRoot,
    env: environment,
    shell: true,
    windowsHide: true,
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout?.on("data", (chunk) => stdout.push(String(chunk).slice(-2000)));
  child.stderr?.on("data", (chunk) => {
    const message = String(chunk);
    stderr.push(message.slice(-2000));
    if (/Compiling ja |Finished |Running |error:/u.test(message)) process.stdout.write(message);
  });
  try {
    await waitForNativeCdp(cdpPort, child, stdout, stderr);
    const observation = await inspectNativeSettings(cdpPort, directories.root);
    await writeFile(
      join(directories.root, "native-report.json"),
      `${JSON.stringify(observation, null, 2)}\n`,
      "utf8",
    );
    process.stdout.write(
      `JA_SETTINGS_NATIVE_PROBE_OK root=${directories.root} cdpPort=${cdpPort} pid=${child.pid}\n`,
    );
  } finally {
    if (child.pid !== undefined && child.exitCode === null) {
      await execFileAsync("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], {
        windowsHide: true,
        timeout: 15_000,
      }).catch(() => undefined);
    }
  }
}

/** 在应用 Vite 配置上挂载一次性入口，确保预览使用生产 alias 与 CSS 管线。 */
async function startPreviewServer() {
  const source = await readFile(previewPath, "utf8");
  const server = await createServer({
    configFile: join(repoRoot, "apps/desktop/vite.config.ts"),
    server: { port: previewPort, strictPort: true },
    plugins: [
      {
        name: "ja-settings-style-preview-entry",
        resolveId(id) {
          return id === previewEntry ? previewPath : null;
        },
        load(id) {
          return id === previewPath ? source : null;
        },
      },
    ],
  });
  await server.listen();
  return server;
}

/** 让 HTML 入口加载真实 Settings fixture，而不是另写一套静态 HTML 假页面。 */
function previewHtml(html) {
  const marker = '<script type="module" src="/src/main.tsx"></script>';
  if (!html.includes(marker)) throw new Error("Ja preview HTML entry marker is missing");
  return html.replace(marker, `<script type="module" src="${previewEntry}"></script>`);
}

/** 统一设置主题、调色板、系统媒体和文字缩放，模拟 WebView2 可观察的渲染条件。 */
async function configureVisualMode(page, mode, viewport, scale = 1) {
  await page.setViewportSize(viewport);
  await page.emulateMedia({
    colorScheme: mode.colorScheme ?? mode.theme,
    reducedMotion: mode.reducedMotion ? "reduce" : "no-preference",
    forcedColors: mode.forcedColors ? "active" : "none",
  });
  await page.evaluate((fontScale) => {
    globalThis.document.documentElement.style.fontSize = `${fontScale * 100}%`;
  }, scale);
  const expectedTheme = mode.theme === "system" ? mode.colorScheme : mode.theme;
  await expect.poll(() => page.locator("html").getAttribute("data-theme")).toBe(expectedTheme);
}

/** 检查当前真实 DOM 的滚动端口和交互控件是否发生横向截断。 */
async function assertLayout(page, label) {
  const metrics = await page.evaluate(() => {
    const settings = globalThis.document.querySelector(".ja-settings");
    const content = globalThis.document.querySelector(".ja-settings-content");
    const visibleClippedControls = Array.from(
      globalThis.document.querySelectorAll(
        "button, input, select, textarea, [role='tab'], [role='switch']",
      ),
    )
      .filter((element) => {
        const rect = element.getBoundingClientRect();
        return rect.width > 0 && globalThis.getComputedStyle(element).visibility !== "hidden";
      })
      .filter((element) => {
        const rect = element.getBoundingClientRect();
        return rect.left < -1 || rect.right > globalThis.innerWidth + 1;
      })
      .map(
        (element) => element.getAttribute("aria-label") || element.textContent?.trim().slice(0, 48),
      );
    const scrollContainers = [
      settings,
      content,
      globalThis.document.querySelector(".ja-settings-panel[data-state='active']"),
    ]
      .filter((element) => element !== null)
      .map((element) => ({
        className: element.className,
        overflow: element.scrollWidth - element.clientWidth,
      }))
      .filter((item) => item.overflow > 1);
    return {
      documentOverflow: globalThis.document.documentElement.scrollWidth - globalThis.innerWidth,
      settingsWidth: settings?.getBoundingClientRect().width ?? 0,
      contentWidth: content?.getBoundingClientRect().width ?? 0,
      visibleClippedControls,
      scrollContainers,
      activePanel: globalThis.document
        .querySelector('[role="tabpanel"]:not([hidden])')
        ?.textContent?.trim()
        .slice(0, 80),
    };
  });
  expect(metrics.settingsWidth, `${label}: settings should render`).toBeGreaterThan(0);
  expect(metrics.contentWidth, `${label}: content should render`).toBeGreaterThan(0);
  expect(metrics.documentOverflow, `${label}: document horizontal overflow`).toBeLessThanOrEqual(1);
  expect(metrics.visibleClippedControls, `${label}: visible controls clipped`).toEqual([]);
  expect(metrics.scrollContainers, `${label}: visible scroll containers overflow`).toEqual([]);
}

/** 截图前保存主题、尺寸和状态标签，便于主任务对照视觉证据而不依赖 console。 */
async function capture(page, name, metadata) {
  const path = join(evidenceDirectory, `${name}.png`);
  await page.screenshot({ path, fullPage: false, animations: "disabled" });
  metadata.screenshots.push(path);
}

/** 遍历八个真实 Radix 分类并断言当前 panel 存在，覆盖空态与有数据页面。 */
async function inspectSections(page, metadata, prefix) {
  const labels = ["通用", "外观", "模型", "子智能体", "执行确认", "Skills", "MCP", "关于"];
  for (const label of labels) {
    const tab = page.getByRole("tab", { name: label, exact: true });
    await expect(tab).toBeVisible();
    await tab.click();
    await expect(page.getByRole("tabpanel", { name: label, exact: true })).toBeVisible();
    await assertLayout(page, `${prefix}/${label}`);
    await capture(page, `${prefix}-${label}`, metadata);
  }
}

/** 验证高频菜单、编辑 sheet、焦点返回、MCP 失败反馈和 Escape 关闭均走真实组件状态机。 */
async function exerciseWorkflows(page, metadata) {
  await page.getByRole("tab", { name: "模型", exact: true }).click();
  const providerMenu = page.getByRole("button", { name: /供应商 .*更多操作/u }).first();
  await providerMenu.click();
  await expect(page.getByRole("menuitem", { name: "上移" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(providerMenu).toBeFocused();

  const modelEdit = page.getByRole("button", { name: /编辑模型 gpt-5\.6-sol/u });
  await modelEdit.click();
  await expect(page.getByRole("dialog")).toBeVisible();
  const sheetBounds = await page.locator(".ja-settings-provider-sheet").boundingBox();
  expect(sheetBounds.x + sheetBounds.width).toBeGreaterThan(1240);
  expect(sheetBounds.height).toBeGreaterThan(740);
  await expect(page.locator("#model-id-model_sol")).toBeFocused();
  const deleteCredential = page.getByRole("button", { name: "删除凭据", exact: true });
  await deleteCredential.click();
  const credentialConfirm = page.getByRole("dialog", { name: "删除系统凭据", exact: true });
  await expect(credentialConfirm).toBeVisible();
  const confirmStyle = await credentialConfirm.evaluate((element) => {
    const style = globalThis.getComputedStyle(element);
    return { background: style.backgroundColor, zIndex: Number(style.zIndex) };
  });
  expect(confirmStyle.background).not.toBe("rgba(0, 0, 0, 0)");
  expect(confirmStyle.zIndex).toBeGreaterThan(1200);
  await capture(page, "workflow-credential-confirm", metadata);
  await credentialConfirm.getByRole("button", { name: "取消", exact: true }).click();
  await expect(deleteCredential).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(modelEdit).toBeFocused();

  await page.getByRole("tab", { name: "MCP", exact: true }).click();
  const failingServer = page.getByRole("button", { name: "测试" }).last();
  await failingServer.click();
  await expect(page.getByRole("status")).toContainText("连接失败");
  const addServer = page.getByRole("button", { name: "新增服务", exact: true });
  await addServer.click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.locator("#mcp-name")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(addServer).toBeFocused();
  await capture(page, "workflow-mcp-error-recovery", metadata);
}

/** 验证首次进入设置时每个空态只保留一个明确的下一步，避免重复 CTA 造成决策负担。 */
async function exerciseEmptyStates(page, metadata) {
  await page.goto(`http://127.0.0.1:${previewPort}/?state=empty`, { waitUntil: "networkidle" });
  await expect(page.getByRole("tab", { name: "通用", exact: true })).toBeVisible();

  await page.getByRole("tab", { name: "Skills", exact: true }).click();
  await expect(page.getByText("暂无 Skills", { exact: true })).toHaveCount(4);
  await assertLayout(page, "empty/Skills");
  await capture(page, "empty-skills", metadata);

  await page.getByRole("tab", { name: "MCP", exact: true }).click();
  await expect(page.getByText("还没有 MCP 服务", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "新增服务", exact: true })).toHaveCount(1);
  await assertLayout(page, "empty/MCP");
  await capture(page, "empty-mcp", metadata);

  await page.getByRole("tab", { name: "模型", exact: true }).click();
  await expect(page.getByText("尚未配置 Provider", { exact: true })).toBeVisible();
  await expect(page.getByText("从一个供应商开始", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "新增供应商", exact: true })).toHaveCount(1);
  await assertLayout(page, "empty/模型");
  await capture(page, "empty-models", metadata);
}

/** 主流程启动/关闭浏览器与 Vite，任何失败都保留错误和已生成截图供主任务判断。 */
async function main() {
  await mkdir(evidenceDirectory, { recursive: true });
  const metadata = { screenshots: [], modes: [], pageErrors: [], requestFailures: [] };
  const server = await startPreviewServer();
  const browser = await chromium.launch({ channel: "msedge", headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 820 } });
  page.on("pageerror", (error) => metadata.pageErrors.push(error.message));
  page.on("requestfailed", (request) =>
    metadata.requestFailures.push(
      `${request.method()} ${request.url()} ${request.failure()?.errorText ?? ""}`,
    ),
  );
  try {
    await page.route(
      (url) => url.origin === `http://127.0.0.1:${previewPort}` && url.pathname === "/",
      async (route) => {
        const response = await route.fetch();
        await route.fulfill({ response, body: previewHtml(await response.text()) });
      },
    );
    await page.goto(`http://127.0.0.1:${previewPort}/`, { waitUntil: "networkidle" });
    if ((await page.getByRole("tab", { name: "通用", exact: true }).count()) === 0) {
      throw new Error(
        `settings preview did not mount; errors=${metadata.pageErrors.join(" | ")} body=${(await page.locator("body").innerText()).slice(0, 1200)}`,
      );
    }
    await expect(page.getByRole("tab", { name: "通用", exact: true })).toBeVisible();

    const modes = [
      {
        name: "light-wide",
        theme: "light",
        viewport: { width: 1280, height: 820 },
        reducedMotion: false,
      },
      {
        name: "dark-wide",
        theme: "dark",
        viewport: { width: 1280, height: 820 },
        reducedMotion: false,
      },
      {
        name: "light-medium",
        theme: "light",
        viewport: { width: 1018, height: 720 },
        reducedMotion: true,
      },
      {
        name: "dark-medium",
        theme: "dark",
        viewport: { width: 1018, height: 720 },
        reducedMotion: true,
      },
      {
        name: "light-narrow",
        theme: "light",
        viewport: { width: 720, height: 640 },
        reducedMotion: true,
      },
      {
        name: "dark-narrow",
        theme: "dark",
        viewport: { width: 720, height: 640 },
        reducedMotion: true,
      },
    ];
    for (const mode of modes) {
      metadata.modes.push(mode);
      await configureVisualMode(page, mode, mode.viewport);
      await inspectSections(page, metadata, mode.name);
    }

    for (const mode of [
      { name: "system-light", theme: "system", colorScheme: "light", reducedMotion: true },
      { name: "system-dark", theme: "system", colorScheme: "dark", reducedMotion: true },
    ]) {
      metadata.modes.push(mode);
      await configureVisualMode(page, mode, { width: 1018, height: 720 });
      await page.getByRole("tab", { name: "通用", exact: true }).click();
      await assertLayout(page, `${mode.name}/通用`);
      await capture(page, `${mode.name}-general`, metadata);
    }

    const zoomMode = {
      name: "light-200-percent",
      theme: "light",
      reducedMotion: true,
      forcedColors: false,
    };
    metadata.modes.push({ ...zoomMode, viewport: { width: 1018, height: 720 }, scale: 2 });
    await configureVisualMode(page, zoomMode, { width: 1018, height: 720 }, 2);
    await page.getByRole("tab", { name: "外观", exact: true }).click();
    await assertLayout(page, "200-percent/外观");
    await capture(page, "light-200-percent-appearance", metadata);

    const forcedMode = {
      name: "light-forced-colors",
      theme: "light",
      reducedMotion: true,
      forcedColors: true,
    };
    metadata.modes.push({ ...forcedMode, viewport: { width: 720, height: 640 } });
    await configureVisualMode(page, forcedMode, { width: 720, height: 640 });
    await page.getByRole("tab", { name: "执行确认", exact: true }).click();
    await assertLayout(page, "forced-colors/执行确认");
    await capture(page, "light-forced-colors-permissions", metadata);

    await configureVisualMode(
      page,
      { theme: "light", reducedMotion: true, forcedColors: false },
      { width: 1280, height: 820 },
    );
    await exerciseWorkflows(page, metadata);
    await exerciseEmptyStates(page, metadata);
    metadata.pageErrors = [...new Set(metadata.pageErrors)];
    metadata.requestFailures = [...new Set(metadata.requestFailures)];
    await writeFile(
      join(evidenceDirectory, "report.json"),
      JSON.stringify(metadata, null, 2),
      "utf8",
    );
    if (metadata.pageErrors.length > 0)
      throw new Error(`page errors: ${metadata.pageErrors.join(" | ")}`);
    if (metadata.requestFailures.length > 0)
      throw new Error(`request failures: ${metadata.requestFailures.join(" | ")}`);
    process.stdout.write(
      `JA_SETTINGS_STYLE_ACCEPTANCE_OK evidence=${evidenceDirectory} screenshots=${metadata.screenshots.length}\n`,
    );
  } finally {
    await browser.close();
    await server.close();
  }
}

if (process.env.JA_SETTINGS_NATIVE_ONLY === "1") {
  await runNativeProbe();
} else {
  await main();
}
